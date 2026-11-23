package com.oshi.messenger.network.ohttp

import com.oshi.messenger.network.ohttp.Ohttp.hex
import com.oshi.messenger.network.ohttp.Ohttp.toHex
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * RFC 9458 Appendix A test vector (Oblivious HTTP), reproduced byte-for-byte with the
 * vector's ephemeral key, plus response decapsulation, and an end-to-end interceptor
 * test against a fake relay+gateway on MockWebServer.
 */
class OhttpTest {

    // ---- RFC 9458 Appendix A ---------------------------------------------------------
    private val skR = hex("3c168975674b2fa8e465970b79c8dcf09f1c741626480bd4c6162fc5b6a98e1a")
    private val pkR = hex("31e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e798155")
    private val keyConfigBytes = hex(
        "01002031e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e798155" +
            "0008" + "00010001" + "00010003" // (HKDF-SHA256, AES-128-GCM), (HKDF-SHA256, ChaCha20Poly1305)
    )
    private val bhttpRequest = hex("00034745540568747470730b6578616d706c652e636f6d012f")
    private val skE = hex("bc51d5e930bda26589890ac7032f70ad12e4ecb37abb1b65b1256c9c48999c73")
    private val pkE = hex("4b28f881333e7c164ffc499ad9796f877f4e1051ee6d31bad19dec96c208b472")
    private val encapsulatedRequest = hex(
        "010020000100014b28f881333e7c164ffc499ad9796f877f4e1051ee6d31bad19dec96c208b472" +
            "6374e469135906992e1268c594d2a10c695d858c40a026e7965e7d86b83dd440b2c0185204b4d63525"
    )
    private val bhttpResponse = hex("0140c8")
    private val responseNonce = hex("c789e7151fcba46158ca84b04464910d")
    private val encapsulatedResponse = hex("c789e7151fcba46158ca84b04464910d86f9013e404feea014e7be4a441f234f857fbd")

    @Test fun keyConfigParsesAndSelectsTheAes128Suite() {
        val cfg = Ohttp.parseKeyConfig(keyConfigBytes)
        assertNotNull(cfg)
        assertEquals(1, cfg!!.keyId)
        assertArrayEquals(pkR, cfg.publicKey)
        assertEquals("01002000010001", toHex(cfg.header()))
        // application/ohttp-keys wrapping (RFC 9540): 2-byte length prefix.
        val media = byteArrayOf(0, keyConfigBytes.size.toByte()) + keyConfigBytes
        assertArrayEquals(pkR, Ohttp.selectFromKeysMedia(media)!!.publicKey)
    }

    @Test fun keyConfigWithoutOurSuiteIsRejected() {
        // Same key, only (HKDF-SHA256, ChaCha20-Poly1305).
        val onlyChacha = hex("01002031e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e798155" + "000400010003")
        assertNull(Ohttp.parseKeyConfig(onlyChacha))
    }

    @Test fun requestEncapsulationMatchesRfc9458AppendixA() {
        val cfg = Ohttp.parseKeyConfig(keyConfigBytes)!!
        val hpke = Ohttp.hpke()
        val ephemeral = hpke.deserializePrivateKey(skE, pkE)
        val ctx = Ohttp.encapsulateRequest(cfg, bhttpRequest, ephemeral)
        assertEquals(toHex(encapsulatedRequest), toHex(ctx.encapsulatedRequest))
    }

    @Test fun responseDecapsulationMatchesRfc9458AppendixA() {
        val cfg = Ohttp.parseKeyConfig(keyConfigBytes)!!
        val ctx = Ohttp.encapsulateRequest(cfg, bhttpRequest, Ohttp.hpke().deserializePrivateKey(skE, pkE))
        assertArrayEquals(bhttpResponse, Ohttp.decapsulateResponse(ctx, encapsulatedResponse))
        // Gateway side with the vector's nonce re-creates the exact response bytes.
        val gw = gatewayOpen(encapsulatedRequest, Ohttp.hpke().deserializePrivateKey(skR, pkR))
        assertArrayEquals(bhttpRequest, gw.plaintext)
        assertEquals(toHex(encapsulatedResponse), toHex(Ohttp.encapsulateResponse(gw.enc, gw.secret, bhttpResponse, responseNonce)))
        val decoded = Bhttp.decodeResponse(bhttpResponse)
        assertEquals(200, decoded.status)
    }

    @Test fun vectorRequestDecodesAsGetExampleCom() {
        val r = Bhttp.decodeRequest(bhttpRequest) // truncated form: no header/content/trailer sections
        assertEquals("GET", r.method); assertEquals("https", r.scheme)
        assertEquals("example.com", r.authority); assertEquals("/", r.path)
        assertTrue(r.headers.isEmpty() && r.content.isEmpty())
    }

    @Test fun tamperedResponseFailsAuthentication() {
        val cfg = Ohttp.parseKeyConfig(keyConfigBytes)!!
        val ctx = Ohttp.encapsulateRequest(cfg, bhttpRequest, Ohttp.hpke().deserializePrivateKey(skE, pkE))
        val bad = encapsulatedResponse.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try { Ohttp.decapsulateResponse(ctx, bad); fail("tampered response accepted") } catch (_: IOException) {}
    }

    @Test fun freshEphemeralRoundTripWithRecipientKey() {
        val cfg = Ohttp.parseKeyConfig(keyConfigBytes)!!
        val a = Ohttp.encapsulateRequest(cfg, bhttpRequest)
        val b = Ohttp.encapsulateRequest(cfg, bhttpRequest)
        assertFalse("ephemeral key must differ per request", a.encapsulatedRequest.contentEquals(b.encapsulatedRequest))
        val gw = gatewayOpen(a.encapsulatedRequest, Ohttp.hpke().deserializePrivateKey(skR, pkR))
        assertArrayEquals(bhttpRequest, gw.plaintext)
        val resp = Ohttp.encapsulateResponse(gw.enc, gw.secret, bhttpResponse)
        assertArrayEquals(bhttpResponse, Ohttp.decapsulateResponse(a, resp))
        try { Ohttp.decapsulateResponse(b, resp); fail("response opened with another request's context") } catch (_: IOException) {}
    }

    // ---- interceptor against a fake relay + gateway ----------------------------------

    private val server = MockWebServer()
    @After fun tearDown() { server.shutdown() }

    private fun transport(): OhttpTransport {
        server.start()
        return OhttpTransport(server.url("/relay"), Ohttp.parseKeyConfig(keyConfigBytes)!!)
    }

    private fun client(t: OhttpTransport?, direct: AtomicInteger) = OkHttpClient.Builder()
        .addInterceptor(OhttpInterceptor({ t }, Interceptor { chain ->
            direct.incrementAndGet(); chain.proceed(chain.request())
        }))
        .build()

    @Test fun signedV2CallIsEncapsulatedAndAnswerIsSynthesised() {
        val t = transport()
        val seen = mutableListOf<Bhttp.Request>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("POST", request.method)
                assertEquals("message/ohttp-req", request.getHeader("Content-Type"))
                assertNull("no identifying headers to the relay", request.getHeader("x-oshi-signature"))
                val gw = gatewayOpen(request.body.readByteArray(), Ohttp.hpke().deserializePrivateKey(skR, pkR))
                seen += Bhttp.decodeRequest(gw.plaintext)
                val inner = Bhttp.encodeResponse(Bhttp.Response(201, listOf("content-type" to "application/json", "retry-after" to "3"),
                    """{"accepted":["m1"]}""".toByteArray()))
                return MockResponse().setResponseCode(200).setHeader("Content-Type", "message/ohttp-res")
                    .setBody(Buffer().write(Ohttp.encapsulateResponse(gw.enc, gw.secret, inner)))
            }
        }
        val direct = AtomicInteger()
        val body = """{"to":"x"}""".toByteArray()
        val req = Request.Builder().url("https://oshi-messenger.com/v2/messages?after=5")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("x-oshi-signature", "sig==").header("x-oshi-timestamp", "123").build()
        client(t, direct).newCall(req).execute().use { resp ->
            assertEquals(201, resp.code)
            assertEquals("3", resp.header("Retry-After"))
            assertEquals("""{"accepted":["m1"]}""", resp.body!!.string())
            assertEquals("https://oshi-messenger.com/v2/messages?after=5", resp.request.url.toString())
        }
        assertEquals(0, direct.get())
        val inner = seen.single()
        assertEquals("POST", inner.method); assertEquals("https", inner.scheme)
        assertEquals("oshi-messenger.com", inner.authority); assertEquals("/v2/messages?after=5", inner.path)
        assertArrayEquals(body, inner.content)
        val h = inner.headers.toMap()
        assertEquals("sig==", h["x-oshi-signature"]); assertEquals("123", h["x-oshi-timestamp"])
        assertEquals("application/json; charset=utf-8", h["content-type"])
    }

    @Test fun relayFailureFailsTheCallAndNeverGoesDirect() {
        val t = transport()
        server.enqueue(MockResponse().setResponseCode(502))
        val direct = AtomicInteger()
        try {
            client(t, direct).newCall(Request.Builder().url("https://oshi-messenger.com/v2/keys/abc").build()).execute()
            fail("expected IOException")
        } catch (e: IOException) { assertTrue(e.message!!.contains("502")) }
        assertEquals(0, direct.get())
    }

    @Test fun keyMismatchProblemIsSurfaced() {
        val t = transport()
        server.enqueue(MockResponse().setResponseCode(422).setHeader("Content-Type", "application/problem+json")
            .setBody("""{"type":"https://iana.org/assignments/http-problem-types#ohttp-key","title":"key identifier unknown"}"""))
        try {
            client(t, AtomicInteger()).newCall(Request.Builder().url("https://oshi-messenger.com/v2/config").build()).execute()
            fail("expected KeyMismatchException")
        } catch (_: Ohttp.KeyMismatchException) {}
    }

    @Test fun wrongContentTypeIsRejected() {
        val t = transport()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>"))
        try {
            client(t, AtomicInteger()).newCall(Request.Builder().url("https://oshi-messenger.com/v2/config").build()).execute()
            fail("expected IOException")
        } catch (_: IOException) {}
    }

    @Test fun eligibilityPhase1() {
        val t = OhttpTransport(okhttp3.HttpUrl.Builder().scheme("https").host("relay.example").build(), Ohttp.parseKeyConfig(keyConfigBytes)!!)
        fun r(url: String) = Request.Builder().url(url).build()
        assertTrue(OhttpInterceptor.isEligible(r("https://oshi-messenger.com/v2/messages/abc"), t))
        assertTrue(OhttpInterceptor.isEligible(r("https://oshi-messenger.com/v2/keys/publish"), t))
        // __OHTTP_MEDIA_2026_11_10__ media goes through the privacy relay too.
        assertTrue(OhttpInterceptor.isEligible(r("https://oshi-messenger.com/v2/blobs"), t))
        assertTrue(OhttpInterceptor.isEligible(r("https://oshi-messenger.com/v2/blobs/x/chunk/0"), t))
        assertTrue(OhttpInterceptor.isEligible(r("https://oshi-messenger.com/v2/blobs/x/status"), t))
        assertFalse(OhttpInterceptor.isEligible(r("https://oshi-messenger.com/api/legacy"), t))
        assertFalse(OhttpInterceptor.isEligible(r("https://evil.example/v2/messages"), t))
        assertFalse(OhttpInterceptor.isEligible(r("http://oshi-messenger.com/v2/messages"), t))
    }

    @Test fun inactiveTransportPassesThroughUntouched() {
        server.start()
        server.enqueue(MockResponse().setResponseCode(200).setBody("plain"))
        val direct = AtomicInteger()
        client(null, direct).newCall(Request.Builder().url(server.url("/v2/config")).build()).execute().use {
            assertEquals("plain", it.body!!.string())
        }
        assertEquals(1, direct.get())
    }

    @Test fun pinnedProductionKeyConfigParses() {
        val cfg = Ohttp.selectFromKeysMedia(hex(OhttpConfig.PINNED_KEYS_HEX))
        assertNotNull("pinned key config must be usable", cfg)
        assertEquals(1, cfg!!.keyId)
        assertEquals("c93f9075d86e2e7dca66b2e3309d584486ef53f7d6dca5cbff3d4ccf87cda019", toHex(cfg.publicKey))
    }

    @Test fun placeholdersMeanUnavailable() {
        assertFalse(OhttpConfig.isConfigured("", ""))
        assertFalse(OhttpConfig.isConfigured("https://relay.example/ohttp", ""))
        assertTrue(OhttpConfig.isConfigured("https://relay.example/ohttp", "002d" + toHex(keyConfigBytes)))
        assertNotNull(OhttpSettings.build("https://relay.example/ohttp", "00" + "%02x".format(keyConfigBytes.size) + toHex(keyConfigBytes)))
        assertNull(OhttpSettings.build("https://relay.example/ohttp", "zz"))
    }

    // ---- fake gateway ----------------------------------------------------------------

    private class Opened(val enc: ByteArray, val secret: ByteArray, val plaintext: ByteArray)

    private fun gatewayOpen(req: ByteArray, kpR: org.bouncycastle.crypto.AsymmetricCipherKeyPair): Opened {
        val header = req.copyOfRange(0, 7)
        val enc = req.copyOfRange(7, 7 + Ohttp.NPK)
        val ct = req.copyOfRange(7 + Ohttp.NPK, req.size)
        val ctx = Ohttp.hpke().setupBaseR(enc, kpR, Ohttp.requestInfo(header))
        val pt = ctx.open(ByteArray(0), ct)
        val secret = ctx.export("message/bhttp response".toByteArray(), Ohttp.RESPONSE_NONCE_LEN)
        return Opened(enc, secret, pt)
    }
}
