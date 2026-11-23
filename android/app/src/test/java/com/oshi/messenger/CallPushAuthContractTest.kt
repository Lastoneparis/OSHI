package com.oshi.messenger

import com.oshi.messenger.network.auth.CallPushAuth
import com.oshi.messenger.network.auth.CallPushAuthInterceptor
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

/**
 * __CALL_PUSH_AUTH_2026_09_23__ docs/CALL_PUSH_AUTH_CONTRACT.md §6 test vectors, byte for byte.
 * Ed25519 is deterministic, so the SIGNATURE is asserted, not just "verifies": a canonical
 * string with two fields swapped still signs fine and still 403s on the server.
 */
class CallPushAuthContractTest {

    private val seed = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
    private val priv = Ed25519PrivateKeyParameters(seed, 0)
    private val pub = priv.generatePublicKey().encoded
    private val ts = 1790000000000L
    private val me = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s"

    private fun sign(msg: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, priv); update(msg, 0, msg.size); generateSignature()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun headers(method: String, path: String, body: String) =
        CallPushAuth.headers(method, path, body.toByteArray(Charsets.UTF_8), ts, pub, ::sign)

    @Test
    fun `signing public key matches the vector`() {
        assertEquals("ebVWLo/mVPlAeLES6KmLp5AfhTrmlb7X4OORC60ElmQ=", Base64.getEncoder().encodeToString(pub))
    }

    @Test
    fun `V1 POST api-call-signal`() {
        val body = """{"sender":"$me","recipient":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","signal":"AA==","callId":"00000000-0000-4000-8000-000000000001","type":"callEnd"}"""
        assertEquals(
            "POST\n/api/call/signal\n7eb060c7b241f3056a6070b998167d6f3063c3d7ea2da86a1da4f0b746602b81\n1790000000000",
            CallPushAuth.canonical("POST", "/api/call/signal", body.toByteArray(), "1790000000000"),
        )
        val h = headers("POST", "/api/call/signal", body)
        assertEquals("t40jSdP7dJC4Tfa6E0+6j6m4LX9tCbtuYf6Jc6ryS4cCcPDzOuW/AGb2nwIvDyuC48uTwno0oWxfMHnkmdW2CQ==", h[CallPushAuth.HEADER_SIGNATURE])
        assertEquals("1790000000000", h[CallPushAuth.HEADER_TIMESTAMP])
        assertEquals("ebVWLo/mVPlAeLES6KmLp5AfhTrmlb7X4OORC60ElmQ=", h[CallPushAuth.HEADER_SIGNING_PUBKEY])
    }

    @Test
    fun `V2 GET signals, query not signed`() {
        assertEquals(
            "GET\n/api/call/signals/$me\n${CallPushAuth.EMPTY_BODY_SHA256}\n1790000000000",
            CallPushAuth.canonical("GET", "/api/call/signals/$me", ByteArray(0), "1790000000000"),
        )
        assertEquals(
            "AfMWbSytNqos3cJ8kiJO2Vh84C7zIVOiHNhQZAG0tr8yZ3RQbu1ZFb0Vp78clm0EV9FB+Qj3bXAkQA7YCobUDg==",
            headers("GET", "/api/call/signals/$me", "")[CallPushAuth.HEADER_SIGNATURE],
        )
    }

    @Test
    fun `V3 WebSocket upgrade`() {
        assertEquals(
            "igxm0cDqwPYMtl7uE98/RLHFkyol29VKmoMiFEv+nqJz+N6vnHOBuB8b+Htg8Mcsqv0Wt9nTRzIKY3e8NvfBCQ==",
            headers("GET", "/voip/", "")[CallPushAuth.HEADER_SIGNATURE],
        )
    }

    @Test
    fun `V4 POST push-unregister-device`() {
        val body = """{"publicKey":"q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s=","deviceId":"dev-1"}"""
        assertEquals(
            "POST\n/push/unregister-device\n159004baa70dfde3466d8846958f44260c76cdbf79853c4d4179be14175dcaad\n1790000000000",
            CallPushAuth.canonical("POST", "/push/unregister-device", body.toByteArray(), "1790000000000"),
        )
        assertEquals(
            "M5hYgsOthXPkLqfvNK4yZfjW1vE5CNA1Puz8I/oXMWeJZ6ydy9FWtZsqDssG3xA86yzboJ1AsPqTyA1EnytoAQ==",
            headers("POST", "/push/unregister-device", body)[CallPushAuth.HEADER_SIGNATURE],
        )
    }

    @Test
    fun `covered paths are exactly call, voip and push`() {
        listOf("/api/call/signal", "/api/call/signals/x", "/api/call/", "/api/call", "/voip/", "/voip",
            "/push/send-push", "/push/send-voip-push", "/push/signal-answered", "/push/register-device",
            "/push/register_fcm", "/push/unregister-device").forEach { assertTrue(it, CallPushAuth.covers(it)) }
        // V2 signs itself (sometimes over a non-body hash); a second signature would break it.
        listOf("/v2/messages", "/v2/keys/abc", "/api/queue/x", "/api/pending/x", "/api/callx", "/pushy")
            .forEach { assertFalse(it, CallPushAuth.covers(it)) }
    }

    // ---- The interceptor: path as requested, exact body bytes, no query ----

    private class Capture : Interceptor {
        var seen: Request? = null
        var seenBody: ByteArray? = null
        override fun intercept(chain: Interceptor.Chain): Response {
            seen = chain.request()
            seenBody = chain.request().body?.let { b -> Buffer().also { b.writeTo(it) }.readByteArray() }
            return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{}".toResponseBody()).build()
        }
    }

    private fun client(capture: Capture, headersFor: (String, String, ByteArray) -> Map<String, String>) =
        OkHttpClient.Builder()
            .addInterceptor(CallPushAuthInterceptor("OSHI-Android/test", headersFor))
            .addInterceptor(capture)
            .build()

    @Test
    fun `interceptor signs the requested path without query over the sent bytes`() {
        val capture = Capture()
        var signedPath: String? = null
        var signedBody: ByteArray? = null
        val c = client(capture) { m, p, b ->
            signedPath = p; signedBody = b
            CallPushAuth.headers(m, p, b, ts, pub, ::sign)
        }
        c.newCall(Request.Builder().url("https://oshi-messenger.com/api/call/signals/$me?deviceId=dev-1").get().build())
            .execute().close()
        assertEquals("/api/call/signals/$me", signedPath)
        assertEquals(0, signedBody!!.size)
        assertEquals(
            "AfMWbSytNqos3cJ8kiJO2Vh84C7zIVOiHNhQZAG0tr8yZ3RQbu1ZFb0Vp78clm0EV9FB+Qj3bXAkQA7YCobUDg==",
            capture.seen!!.header("x-oshi-signature"),
        )
        assertEquals("OSHI-Android/test", capture.seen!!.header("User-Agent"))

        val body = """{"publicKey":"q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s=","deviceId":"dev-1"}"""
        c.newCall(Request.Builder().url("https://oshi-messenger.com/push/unregister-device")
            .post(body.toRequestBody("application/json".toMediaType())).build()).execute().close()
        assertEquals("/push/unregister-device", signedPath)
        assertEquals(body, String(capture.seenBody!!, Charsets.UTF_8))
        assertEquals(
            "M5hYgsOthXPkLqfvNK4yZfjW1vE5CNA1Puz8I/oXMWeJZ6ydy9FWtZsqDssG3xA86yzboJ1AsPqTyA1EnytoAQ==",
            capture.seen!!.header("x-oshi-signature"),
        )
    }

    @Test
    fun `interceptor leaves v2 and already-signed requests alone`() {
        val capture = Capture()
        val c = client(capture) { _, _, _ -> error("must not be called") }
        c.newCall(Request.Builder().url("https://oshi-messenger.com/v2/messages").get().build()).execute().close()
        assertNull(capture.seen!!.header("x-oshi-signature"))
        c.newCall(Request.Builder().url("https://oshi-messenger.com/api/call/signal")
            .header("x-oshi-signature", "keep").post("{}".toRequestBody()).build()).execute().close()
        assertEquals("keep", capture.seen!!.header("x-oshi-signature"))
    }

    @Test
    fun `no signing key fails the request instead of sending it unsigned`() {
        val capture = Capture()
        val c = client(capture) { _, _, _ -> throw IllegalStateException("No signing key available") }
        try {
            c.newCall(Request.Builder().url("https://oshi-messenger.com/push/send-push")
                .post("{}".toRequestBody()).build()).execute().close()
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
        assertNull("nothing may reach the network unsigned", capture.seen)
    }
}
