package com.oshi.messenger.service

import com.oshi.messenger.network.v2.CoverInterceptor
import com.oshi.messenger.network.v2.CoverPresentation
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.Random

/**
 * MEASURES the cover-transport hardening of 2026-11-13 instead of asserting it
 * exists: N requests go through the production [CoverInterceptor] to a local
 * relay stand-in that decodes them with the same strict rules as
 * `ServerVPS/v2/cover_transport.js`, and the test counts how many distinct
 * outer presentations an observer would have seen. Each request still has to
 * round-trip byte-exactly. See docs/COVER_TRANSPORT_STATUS_2026-11-13.md.
 */
class CoverHardeningTest {
    private lateinit var server: MockWebServer
    private val seen = mutableListOf<RecordedRequest>()

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                val type = request.getHeader("Content-Type").orEmpty()
                val inner = CoverCodec.decode(request.body.readByteArray(), type)
                // Echo the inner record back as the relay would wrap a response.
                val reply = JSONObject().put("v", 1).put("status", 200)
                    .put("headers", JSONObject().put("content-type", "application/json"))
                    .put("body", Base64.getEncoder().encodeToString(inner.toString().toByteArray()))
                val encoded = CoverCodec.encode(reply, "event")
                return MockResponse().setHeader("Content-Type", encoded.type).setBody(Buffer().write(encoded.body))
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    /** Approximate HTTP/1.1 request bytes inside TLS: request line + headers + body. */
    private fun wireLength(r: RecordedRequest): Int =
        r.requestLine.length + 2 + r.headers.toString().length + 2 + r.bodySize.toInt()

    @Test
    fun outerPresentationVariesAcrossRequestsAndEveryRequestStillDecodes() {
        val client = OkHttpClient.Builder().addInterceptor(CoverInterceptor { "event" }).build()
        val n = 300
        val body = """{"to":"peer","ciphertext":"AAAA"}""".toByteArray()
        repeat(n) { i ->
            val req = Request.Builder().url(server.url("/v2/messages?after=$i"))
                .header("x-oshi-signature", "sig-$i")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            client.newCall(req).execute().use { resp ->
                assertEquals(200, resp.code)
                val inner = JSONObject(resp.body!!.string())
                assertEquals("/v2/messages?after=$i", inner.getString("path"))
                assertEquals("sig-$i", inner.getJSONObject("headers").getString("x-oshi-signature"))
                assertTrue(Base64.getDecoder().decode(inner.getString("body")).contentEquals(body))
            }
        }
        assertEquals(n, seen.size)
        val paths = seen.map { it.requestUrl!!.encodedPath }.toSet()
        val queryShapes = seen.map { r -> r.requestUrl!!.queryParameterNames.sorted().joinToString(",") }.toSet()
        val headerOrders = seen.map { r -> r.headers.map { h -> h.first }.joinToString(",") }.toSet()
        val contentTypes = seen.map { it.getHeader("Content-Type") }.toSet()
        val bodyLengths = seen.map { it.bodySize }.toSet()
        val wireLengths = seen.map { wireLength(it) }

        println("cover hardening over $n requests: paths=${paths.size} queryShapes=${queryShapes.size} " +
            "headerOrders=${headerOrders.size} contentTypes=${contentTypes.size} bodyLengths=${bodyLengths.size} " +
            "wireLengths=${wireLengths.toSet().size} (min=${wireLengths.min()} max=${wireLengths.max()})")

        assertEquals(CoverCodec.ENDPOINT_PATHS.toSet(), paths)
        assertEquals("all query templates (incl. none) must appear", CoverPresentation.QUERY_TEMPLATES, queryShapes.size)
        assertTrue("header order/set barely varies: ${headerOrders.size}", headerOrders.size >= 20)
        assertEquals(CoverPresentation.CONTENT_TYPES.toSet(), contentTypes)
        // 2 profiles x {4 KiB, spread 16 KiB}; key order does not change length.
        assertEquals(4, bodyLengths.size)
        assertTrue("request length is near-constant: ${wireLengths.toSet().size}", wireLengths.toSet().size >= 50)
        // No single request length dominates (before: 2 values for every poll).
        val top = wireLengths.groupingBy { it }.eachCount().values.max()
        assertTrue("one length covers $top of $n requests", top < n / 10)

        // Nothing from the inner request leaks into the outer presentation.
        seen.forEach { r ->
            assertFalse(r.requestLine, r.requestLine.contains("messages"))
            assertFalse(r.headers.names().any { it.startsWith("x-oshi", ignoreCase = true) })
            assertFalse(r.headers.names().any { it.equals("Cookie", ignoreCase = true) })
        }
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }

    @Test
    fun bucketSpreadIsBoundedAndOnlyUpward() {
        val small = JSONObject().put("v", 1).put("body", "x")
        val counts = mutableMapOf<Int, Int>()
        val rng = Random(7)
        repeat(4000) { counts.merge(CoverCodec.frame(small, spread = true, random = rng).size, 1, Int::plus) }
        assertEquals(setOf(4096, 16384), counts.keys)
        val upFraction = counts.getValue(16384) / 4000.0
        assertTrue("spread fraction $upFraction", upFraction in 0.2..0.3)
        // Above the 64 KiB cap: never spread (bandwidth bound).
        val big = JSONObject().put("v", 1).put("body", "x".repeat(70_000))
        repeat(200) { assertEquals(256 * 1024, CoverCodec.frame(big, spread = true, random = rng).size) }
        // 16 KiB record may go to 64 KiB, never further.
        val mid = JSONObject().put("v", 1).put("body", "x".repeat(5_000))
        val midSizes = (1..400).map { CoverCodec.frame(mid, spread = true, random = rng).size }.toSet()
        assertEquals(setOf(16384, 65536), midSizes)
        // The deterministic path stays minimal (fixtures, relay parity).
        assertEquals(4096, CoverCodec.frame(small).size)
    }

    @Test
    fun envelopeUsesFourByteShapesAndNeverEscapesSlashes() {
        val record = JSONObject().put("v", 1).put("body", "cGF5bG9hZA==")
        val shapes = mutableSetOf<String>()
        repeat(400) {
            val text = String(CoverCodec.encode(record, "event").body, Charsets.UTF_8)
            assertFalse("escaped slash in $text", text.contains("\\/"))
            shapes += text.substringBefore(":\"", "").let { prefix ->
                when {
                    text.startsWith("""{"events":[{"name"""") -> "event/name-first"
                    text.startsWith("""{"events":[{"params"""") -> "event/params-first"
                    text.startsWith("""{"batch":[{"event"""") -> "batch/event-first"
                    text.startsWith("""{"batch":[{"payload"""") -> "batch/payload-first"
                    else -> "unexpected:$prefix"
                }
            }
            assertEquals("cGF5bG9hZA==", CoverCodec.decode(text.toByteArray(), "application/json").getString("body"))
        }
        assertEquals(setOf("event/name-first", "event/params-first", "batch/event-first", "batch/payload-first"), shapes)
    }

    @Test
    fun coverPollJitterPreservesTheMeanAndRemovesTheFixedPeriod() {
        val rng = Random(11)
        val samples = (1..5000).map { PollBackoff.coverJitteredMs(5_000L, rng) }
        assertTrue(samples.all { it in 2_500L..7_500L })
        assertTrue("too few distinct gaps", samples.toSet().size > 1000)
        val mean = samples.average()
        assertTrue("mean drifted: $mean", mean in 4_850.0..5_150.0)

        AppPollState.resetForTest(now = 1_000_000L)
        assertEquals(PollBackoff.MESSAGE_BASE_MS, AppPollState.messagePollDelayMs())
        AppPollState.coverTimingJitter = true
        val jittered = (1..200).map { AppPollState.messagePollDelayMs() }.toSet()
        assertTrue(jittered.size > 50)
        AppPollState.resetForTest()
    }

    @Test
    fun crossPlatformVectorsDecodeAndStrictRejectsHold() {
        val f = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "docs/fixtures/cover_transport/vectors.json") }
            .firstOrNull { it.isFile } ?: error("cover vectors not found above ${File("").absolutePath}")
        val v = JSONObject(f.readText())
        val accept = v.getJSONArray("accept")
        for (i in 0 until accept.length()) {
            val t = accept.getJSONObject(i)
            val got = CoverCodec.decode(t.getString("body").toByteArray(), t.getString("contentType"))
            val want = t.getJSONObject("expect")
            assertEquals(t.getString("name"), want.toMap(), got.toMap())
        }
        val reject = v.getJSONArray("reject")
        for (i in 0 until reject.length()) {
            val t = reject.getJSONObject(i)
            val accepted = runCatching { CoverCodec.decode(t.getString("body").toByteArray(), t.getString("contentType")) }.isSuccess
            assertFalse("strict Android decoder accepted ${t.getString("name")}", accepted)
        }
        assertEquals(CoverCodec.ENDPOINT_PATHS.toList(), (0 until v.getJSONArray("endpointPaths").length()).map { v.getJSONArray("endpointPaths").getString(it) })
    }
}
