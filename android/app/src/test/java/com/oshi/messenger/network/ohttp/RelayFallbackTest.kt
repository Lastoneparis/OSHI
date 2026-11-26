package com.oshi.messenger.network.ohttp

import com.oshi.messenger.network.ohttp.Ohttp.hex
import com.oshi.messenger.network.v2.CoverConnectionPolicy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** __RELAY_FALLBACK_2026_11_19__ blocked-host fallback through the built-in OHTTP relay. */
class RelayFallbackTest {
    private val skR = hex("3c168975674b2fa8e465970b79c8dcf09f1c741626480bd4c6162fc5b6a98e1a")
    private val pkR = hex("31e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e798155")
    private val keyConfigBytes = hex("01002031e1f05a740102115220e9af918f738674aec95f54db6e04eb705aae8e798155" + "0008" + "00010001" + "00010003")

    private val direct = MockWebServer()
    private val relay = MockWebServer()
    private val relayed = AtomicInteger()
    private val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var now = 1_000_000L

    @Before fun setUp() {
        RelayFallback.clock = { now }; CoverConnectionPolicy.clock = { now }
        RelayFallback.reset(); CoverConnectionPolicy.reset()
        relay.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                relayed.incrementAndGet()
                val req = request.body.readByteArray()
                val header = req.copyOfRange(0, 7); val enc = req.copyOfRange(7, 7 + Ohttp.NPK)
                val ctx = Ohttp.hpke().setupBaseR(enc, Ohttp.hpke().deserializePrivateKey(skR, pkR), Ohttp.requestInfo(header))
                val inner = Bhttp.decodeRequest(ctx.open(ByteArray(0), req.copyOfRange(7 + Ohttp.NPK, req.size)))
                assertEquals("oshi-messenger.com", inner.authority)
                val secret = ctx.export("message/bhttp response".toByteArray(), Ohttp.RESPONSE_NONCE_LEN)
                val out = Bhttp.encodeResponse(Bhttp.Response(200, listOf("content-type" to "application/json"), """{"via":"relay","path":"${inner.path}"}""".toByteArray()))
                return MockResponse().setHeader("Content-Type", "message/ohttp-res").setBody(Buffer().write(Ohttp.encapsulateResponse(enc, secret, out)))
            }
        }
        direct.start(); relay.start()
        RelayFallback.overrideForTests(OhttpTransport(v4(relay, "/api/v1/batch"), Ohttp.parseKeyConfig(keyConfigBytes)!!,
            directOrigin = v4(direct, "/")))
    }

    @After fun tearDown() {
        RelayFallback.overrideForTests(null); RelayFallback.clock = System::currentTimeMillis
        CoverConnectionPolicy.reset(); CoverConnectionPolicy.clock = System::currentTimeMillis
        direct.shutdown(); relay.shutdown()
    }

    private val client by lazy {
        OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(2, TimeUnit.SECONDS)
            .addInterceptor(OhttpInterceptor({ null }, Interceptor {
                try { it.proceed(it.request()) } catch (e: IOException) { errors += "${e.javaClass.simpleName}:${e.message}"; throw e }
            })).build()
    }
    /** MockWebServer listens on IPv4; "localhost" would try ::1 first (a dead route, see RelayFallback). */
    private fun v4(s: MockWebServer, path: String) = s.url(path).newBuilder().host("127.0.0.1").build()
    private fun call(path: String = "/v2/messages/abc?after=0"): String =
        client.newCall(Request.Builder().url(v4(direct, path)).header("x-oshi-signature", "s").build()).execute().use { it.body!!.string() }
    private fun post(): String = client.newCall(Request.Builder().url(v4(direct, "/v2/messages"))
        .post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use { it.body!!.string() }

    @Test fun healthyDirectNeverTouchesTheRelay() {
        repeat(3) { direct.enqueue(MockResponse().setBody("""{"via":"direct"}""")) }
        repeat(3) { assertTrue(call().contains("direct")) }
        assertEquals(0, relayed.get()); assertFalse(RelayFallback.active())
    }

    @Test fun oneTransientResetDoesNotMoveTraffic() {
        direct.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        direct.enqueue(MockResponse().setBody("""{"via":"direct"}"""))
        direct.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try { call(); fail() } catch (_: IOException) {}
        val second = call()
        assertTrue("got: $second relayed=${relayed.get()} directCount=${direct.requestCount} errors=$errors", second.contains("direct"))
        try { call(); fail() } catch (_: IOException) {}   // streak restarted by the success
        assertEquals(0, relayed.get()); assertFalse(RelayFallback.active())
    }

    @Test fun aBlockedHostMovesToTheRelayOnTheThirdFailureAndRetriesThatCallThroughIt() {
        repeat(5) { direct.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        try { post(); fail("first failure is surfaced") } catch (_: IOException) {}
        try { post(); fail("second failure is surfaced") } catch (_: IOException) {}
        assertTrue("3rd failure: same call answered by the relay", post().contains("relay"))
        assertTrue(RelayFallback.active())
        val before = direct.requestCount
        assertTrue(call().contains("relay"))
        assertEquals("while active, direct is not tried", before, direct.requestCount)
        // After the window the next call probes direct again (here: still blocked -> error).
        now += RelayFallback.WINDOW_MS + 1
        assertFalse(RelayFallback.active())
        try { val r = call(); fail("after window got: $r relayed=${relayed.get()} directCount=${direct.requestCount}") } catch (_: IOException) {}
        assertEquals(before + 1, direct.requestCount)
    }

    @Test fun dnsAndHandshakeFailuresActivateAtOnceARefusedRouteNeedsTwo() {
        assertTrue(RelayFallback.isConnectPhase(ConnectException("refused")))
        assertTrue(RelayFallback.isConnectPhase(SocketTimeoutException("connect timed out")))
        assertFalse(RelayFallback.isConnectPhase(SocketTimeoutException("timeout")))
        assertFalse(RelayFallback.onDirectFailure(ConnectException("refused (dead IPv6 route)")))
        assertFalse(RelayFallback.active())
        RelayFallback.reset()
        assertTrue(RelayFallback.onDirectFailure(IOException("x", javax.net.ssl.SSLHandshakeException("reset after ClientHello"))))
        assertTrue(RelayFallback.active())
        RelayFallback.reset()
        assertTrue(RelayFallback.onDirectFailure(java.net.UnknownHostException("oshi-messenger.com")))
    }

    @Test fun httpStatusesAndCoverDecodeErrorsNeverCount() {
        assertFalse(RelayFallback.onDirectFailure(IOException("Obfuscated transport HTTP 403")))
        assertFalse(RelayFallback.onDirectFailure(IOException("Obfuscated transport HTTP 403")))
        assertFalse(RelayFallback.active())
    }

    @Test fun builtInRelaysAreTheOshiOperatedFallbacksInOrderNotThePrivacyRelayDefault() {
        assertEquals(listOf("https://foodies.agency/api/v1/batch", "https://static-84-75-255-200.sslip.io/api/v1/batch"),
            OhttpConfig.FALLBACK_RELAY_URLS)
        assertEquals("", OhttpConfig.RELAY_URL)
        RelayFallback.overrideForTests(null)
        val ts = RelayFallback.transports()
        assertEquals(listOf("foodies.agency", "static-84-75-255-200.sslip.io"), ts.map { it.relayUrl.host })
        assertEquals("foodies.agency", RelayFallback.transport()!!.relayUrl.host)
        for (t in ts) {
            assertTrue(OhttpInterceptor.isEligible(Request.Builder().url("https://oshi-messenger.com/v2/config").build(), t))
            assertFalse(OhttpInterceptor.isEligible(Request.Builder().url("https://oshi-messenger.com/api/x").build(), t))
        }
    }

    // __RELAY_LIST_CH1_2026_11_19__ ordered relay list: next relay on failure, sticky preference.
    private val relay2 = MockWebServer()
    private val relayed2 = AtomicInteger()
    private fun twoRelays(firstDown: Boolean) {
        relay2.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                relayed2.incrementAndGet(); return relay.dispatcher.dispatch(request).also { relayed.decrementAndGet() }
            }
        }
        relay2.start()
        val cfg = Ohttp.parseKeyConfig(keyConfigBytes)!!
        val first = if (firstDown) "http://127.0.0.1:9/api/v1/batch".toHttpUrl() else v4(relay, "/api/v1/batch")
        RelayFallback.overrideAllForTests(listOf(
            OhttpTransport(first, cfg, directOrigin = v4(direct, "/")),
            OhttpTransport(v4(relay2, "/api/v1/batch"), cfg, directOrigin = v4(direct, "/"))))
    }

    @Test fun aBlockedFirstRelayFallsThroughToTheSecondInTheSameCallAndTheSecondStaysPreferred() {
        twoRelays(firstDown = true)
        try {
            repeat(4) { direct.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
            try { post(); fail() } catch (_: IOException) {}
            try { post(); fail() } catch (_: IOException) {}
            assertTrue("3rd failure: the second relay answers that same call", post().contains("relay"))
            assertEquals(1, relayed2.get())
            assertTrue(RelayFallback.active())
            assertEquals("second relay is now tried first", relay2.port, RelayFallback.transport()!!.relayUrl.port)
            assertTrue(call().contains("relay"))
            assertEquals(2, relayed2.get())
        } finally { relay2.shutdown() }
    }

    @Test fun aHealthyFirstRelayIsUsedAndTheSecondNeverTouched() {
        twoRelays(firstDown = false)
        try {
            repeat(3) { direct.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
            repeat(2) { try { post(); fail() } catch (_: IOException) {} }
            assertTrue(post().contains("relay")); assertTrue(call().contains("relay"))
            assertEquals(2, relayed.get()); assertEquals(0, relayed2.get())
        } finally { relay2.shutdown() }
    }

    @Test fun allRelaysUnreachableSurfacesTheErrorAndLetsTheNextCallProbeDirect() {
        val cfg = Ohttp.parseKeyConfig(keyConfigBytes)!!
        RelayFallback.overrideAllForTests(listOf(
            OhttpTransport("http://127.0.0.1:9/a".toHttpUrl(), cfg, directOrigin = v4(direct, "/")),
            OhttpTransport("http://127.0.0.1:7/b".toHttpUrl(), cfg, directOrigin = v4(direct, "/"))))
        repeat(3) { direct.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        repeat(2) { try { post(); fail() } catch (_: IOException) {} }
        try { post(); fail("both relays refused") } catch (e: IOException) {
            assertTrue("first relay's error kept: ${e.suppressed.map { it.message }}", e.suppressed.isNotEmpty())
        }
        assertFalse("connect-phase failure of every relay ends the window", RelayFallback.active())
    }

    @Test fun aGatewayKeyMismatchIsNotRetriedOnTheNextRelay() {
        twoRelays(firstDown = false)
        try {
            relay.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(400)
                    .setHeader("Content-Type", "application/problem+json").setBody("""{"type":"https://iana.org/assignments/http-problem-types#ohttp-key"}""")
            }
            repeat(3) { direct.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
            repeat(2) { try { post(); fail() } catch (_: IOException) {} }
            try { post(); fail() } catch (e: Ohttp.KeyMismatchException) {}
            assertEquals(0, relayed2.get())
        } finally { relay2.shutdown() }
    }
}
