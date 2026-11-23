package com.oshi.messenger.service

import com.oshi.messenger.network.v2.CoverInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64

/**
 * The cover transport on bad networks (tools/covert-regression/netem, 2026-11-18).
 * A MockWebServer plays the relay — or a middlebox in front of it — and the
 * PRODUCTION [CoverInterceptor] / [CoverOutbox] are driven exactly as the V2
 * clients drive them (`followRedirects(false)`, as in V2MessagesClient/V2BlobClient).
 */
class CoverBadNetworkTest {
    private lateinit var server: MockWebServer
    private val seen = mutableListOf<RecordedRequest>()
    @Volatile private var reply: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(503) }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                return reply(request)
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private val client = OkHttpClient.Builder().addInterceptor(CoverInterceptor { "event" })
        .followRedirects(false).followSslRedirects(false).build()

    /** What the relay's cover handler sends back for an inner `200 {"accepted":["m1"]}`. */
    private fun coverReply(): ByteArray {
        val inner = JSONObject().put("v", 1).put("status", 200)
            .put("headers", JSONObject().put("content-type", "application/json"))
            .put("body", Base64.getEncoder().encodeToString("""{"accepted":["m1"]}""".toByteArray()))
        return CoverCodec.encode(inner, "event").body
    }

    private fun send(): String {
        val req = Request.Builder().url(server.url("/v2/messages"))
            .header("x-oshi-signature", "sig").post("{}".toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { r -> assertEquals(200, r.code); return r.body!!.string() }
    }

    private fun gzip(b: ByteArray): Buffer {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(b) }
        return Buffer().write(out.toByteArray())
    }

    @Test
    fun replyGzippedByAMiddleboxStillDecodes() {
        reply = { MockResponse().setHeader("Content-Type", "application/json").setHeader("Content-Encoding", "gzip").setBody(gzip(coverReply())) }
        assertEquals("""{"accepted":["m1"]}""", send())
    }

    @Test
    fun replyRelabelledAndChunkedByAMiddleboxStillDecodes() {
        reply = { MockResponse().setHeader("Content-Type", "text/plain; charset=utf-8").setHeader("Via", "1.1 middlebox")
            .setChunkedBody(Buffer().write(coverReply()), 1024) }
        assertEquals("""{"accepted":["m1"]}""", send())
    }

    @Test
    fun captivePortalPageIsAFailedAttemptNeverAMessage() {
        reply = { MockResponse().setHeader("Content-Type", "text/html").setBody("<html><form>Accept terms</form></html>") }
        try { send(); fail("a portal page was accepted as a relay reply") } catch (_: IOException) { }
        reply = { MockResponse().setHeader("Content-Type", "application/json").setHeader("Content-Encoding", "gzip").setBody("{not gzip") }
        try { send(); fail("a broken gzip reply was accepted") } catch (_: IOException) { }
    }

    @Test
    fun aRedirectIsAFailedAttemptAndIsNotFollowed() {
        reply = { r -> if (r.path!!.startsWith("/captive")) MockResponse().setBody("portal")
                       else MockResponse().setResponseCode(302).setHeader("Location", "/captive/login") }
        try { send(); fail("302 accepted") } catch (_: IOException) { }
        val paths = synchronized(seen) { seen.map { it.path.orEmpty() } }
        assertTrue("only cover aliases on the wire: $paths", paths.all { p -> listOf("/v2/cover", "/v2/collect", "/v2/metrics").any { p.startsWith(it) } })
    }

    private class MemStore : CoverOutbox.IdStore {
        var ids = setOf<String>()
        override fun read() = ids
        override fun write(ids: Set<String>) { this.ids = ids }
    }

    /** A successful disguised request brings a parked retry forward (≥ 5 s spacing, not the 10 s backoff). */
    @Test
    fun kickBringsTheParkedRetryForwardButNotBelowTheMinimumSpacing() = runBlocking {
        val t0 = System.currentTimeMillis()
        var attemptAt = 0L
        val job = CoverOutbox.parkWith(MemStore(), CoroutineScope(Dispatchers.Default), "kick-1",
            attempt = { attemptAt = System.currentTimeMillis(); true }, onDelivered = { }, onGaveUp = { })
        delay(500); CoverOutbox.kick()
        job.join()
        val waited = attemptAt - t0
        assertTrue("kick honoured the 5 s minimum spacing ($waited ms)", waited >= CoverOutbox.KICK_MIN_SPACING_MS)
        assertTrue("kick cut the 10 s backoff short ($waited ms)", waited < CoverOutbox.BACKOFF_MS[0])
    }

    @Test
    fun coverTimeoutGrowsWithThePaddedBodyAndIsBounded() {
        assertEquals(15_000 + 1_000, com.oshi.messenger.network.v2.coverTimeoutMs(4096))
        assertTrue(com.oshi.messenger.network.v2.coverTimeoutMs(350_000) >= 90_000)      // padded 120 KB photo chunk
        assertEquals(600_000, com.oshi.messenger.network.v2.coverTimeoutMs(6 * 1024 * 1024))
    }

    @Test
    fun kickWithNothingParkedIsANoOp() {
        CoverOutbox.kick()   // must not throw nor wake anything
    }

    @Test
    fun parkedTextResumesAfterARestartWithinTheHourOnly() {
        assertTrue(CoverOutbox.resumeAfterRestart(coverModeOn = true, isText = true, ageMs = 5 * 60_000L))
        assertFalse(CoverOutbox.resumeAfterRestart(coverModeOn = true, isText = true, ageMs = CoverOutbox.GIVE_UP_AFTER_MS))
        assertFalse(CoverOutbox.resumeAfterRestart(coverModeOn = false, isText = true, ageMs = 1_000L))
        assertFalse(CoverOutbox.resumeAfterRestart(coverModeOn = true, isText = false, ageMs = 1_000L))
        assertFalse(CoverOutbox.resumeAfterRestart(coverModeOn = true, isText = true, ageMs = -1L))
    }
}
