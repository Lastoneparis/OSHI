package com.oshi.messenger.service

import com.oshi.messenger.network.v2.CoverConnectionPolicy
import com.oshi.messenger.network.v2.CoverInterceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * __COVER_SHORT_CONNECTIONS_2026_11_19__ Russia-TSPU-style per-connection freeze
 * (tools/covert-regression/netem `tspu16k`): after a transport failure the cover
 * transport stops reusing connections for a while, so each exchange stays under the cap.
 * `RecordedRequest.sequenceNumber` is the request's index on its TCP connection: 0 = a
 * fresh connection.
 */
class CoverShortConnectionsTest {
    private lateinit var server: MockWebServer
    private var now = 1_000_000L

    @Before
    fun start() {
        server = MockWebServer(); server.start()
        CoverConnectionPolicy.reset(); CoverConnectionPolicy.clock = { now }
    }

    @After
    fun stop() { server.shutdown(); CoverConnectionPolicy.reset(); CoverConnectionPolicy.clock = System::currentTimeMillis }

    private val client = OkHttpClient.Builder().addInterceptor(CoverInterceptor { "event" })
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .readTimeout(3, TimeUnit.SECONDS).build()

    private fun ok() = MockResponse().setHeader("Content-Type", "application/json").setBody(Buffer().write(
        CoverCodec.encode(JSONObject().put("v", 1).put("status", 200)
            .put("headers", JSONObject().put("content-type", "application/json"))
            .put("body", Base64.getEncoder().encodeToString("{}".toByteArray())), "event").body))

    private fun send(): Int {
        val req = Request.Builder().url(server.url("/v2/messages"))
            .header("x-oshi-signature", "sig").post("{}".toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { return it.code }
    }
    private fun seq() = server.takeRequest(2, TimeUnit.SECONDS)!!.sequenceNumber

    @Test
    fun healthyLinkKeepsPooling() {
        repeat(3) { server.enqueue(ok()) }
        repeat(3) { assertEquals(200, send()) }
        assertEquals(listOf(0, 1, 2), List(3) { seq() })
        assertFalse(CoverConnectionPolicy.freshConnections())
    }

    @Test
    fun stalledExchangeSwitchesToOneConnectionPerCall() {
        server.enqueue(ok())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))   // frozen: nothing comes back
        repeat(3) { server.enqueue(ok()) }
        assertEquals(200, send())
        try { send(); throw AssertionError("a frozen exchange must fail") } catch (_: IOException) {}
        assertTrue(CoverConnectionPolicy.freshConnections())
        repeat(3) { assertEquals(200, send()) }
        assertEquals(0, seq()); assertEquals(1, seq())               // before: pooled
        assertEquals(listOf(0, 0, 0), List(3) { seq() })             // after: each on its own connection
    }

    @Test
    fun relayHttpErrorIsNotAStall() {
        server.enqueue(MockResponse().setResponseCode(503))
        try { send() } catch (_: IOException) {}
        assertFalse(CoverConnectionPolicy.freshConnections())
    }

    @Test
    fun windowExpiresAndFurtherStallsExtendIt() {
        CoverConnectionPolicy.onFailure(java.net.SocketTimeoutException("timeout"))
        assertTrue(CoverConnectionPolicy.freshConnections())
        now += CoverConnectionPolicy.WINDOW_MS - 1
        CoverConnectionPolicy.onFailure(java.io.EOFException("unexpected end of stream"))
        now += CoverConnectionPolicy.WINDOW_MS - 1
        assertTrue(CoverConnectionPolicy.freshConnections())
        now += 2
        assertFalse(CoverConnectionPolicy.freshConnections())
        CoverConnectionPolicy.onFailure(IOException("Obfuscated transport HTTP 502"))
        assertFalse(CoverConnectionPolicy.freshConnections())
    }
}
