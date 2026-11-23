package com.oshi.messenger.service

import com.oshi.messenger.network.v2.CoverInterceptor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Audit 2026-11-18 finding 1 (cover fallback leak) and finding 5 (gzip).
 *
 * A local relay stand-in (MockWebServer) serves BOTH the disguised V2 alias and
 * the legacy `/api/queue`. The disguised path is made to fail (503). With cover
 * ON the send pipeline — the same [CoverInterceptor] + [CoverOutbox] rule the
 * repository uses — must make NO request to `/api/queue`; with cover OFF the
 * legacy relay is used as before (control). The repository's two call sites are
 * pinned by [repositoryHasNoLegacyFallbackInCoverMode].
 */
class CoverFailClosedTest {
    private lateinit var server: MockWebServer
    private val seen = mutableListOf<RecordedRequest>()
    @Volatile private var coverReplies503 = true

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/queue") -> MockResponse().setResponseCode(200).setBody("{}")
                    coverReplies503 -> MockResponse().setResponseCode(503)
                    else -> MockResponse().setResponseCode(503)
                }
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private val coverClient = OkHttpClient.Builder().addInterceptor(CoverInterceptor { "event" }).build()
    private val plainClient = OkHttpClient()

    /** The V2 send as the router does it: a signed POST to /v2/messages, through the cover interceptor. */
    private fun v2Send(): Boolean = try {
        val req = Request.Builder().url(server.url("/v2/messages"))
            .header("x-oshi-signature", "sig").post("{}".toRequestBody("application/json".toMediaType())).build()
        coverClient.newCall(req).execute().use { it.isSuccessful }
    } catch (_: IOException) { false }

    /** The legacy relay send (`VPSClient.notifyVPSQueue`). */
    private fun legacySend(): Boolean {
        val req = Request.Builder().url(server.url("/api/queue/recipient/QmHash")).post(ByteArray(0).toRequestBody(null)).build()
        return plainClient.newCall(req).execute().use { it.isSuccessful }
    }

    /** The decision chain of `sendTextMessage` / `sendMediaMessage` after the V2 attempt. */
    private fun sendPipeline(coverOn: Boolean, parked: MutableList<String>): Boolean {
        if (v2Send()) return true
        if (!CoverOutbox.legacyRelayAllowed(coverOn)) { parked += "msg-1"; return false }
        return legacySend()
    }

    @Test
    fun coverOnV2FailsNoLegacyQueueRequest() {
        val parked = mutableListOf<String>()
        assertFalse(sendPipeline(coverOn = true, parked = parked))
        assertEquals(listOf("msg-1"), parked)
        val paths = synchronized(seen) { seen.map { it.path.orEmpty() } }
        assertTrue("the disguised attempt reached the relay: $paths", paths.any { it.startsWith("/v2/cover") || it.startsWith("/v2/collect") || it.startsWith("/v2/metrics") })
        assertTrue("NO legacy /api/ request in cover mode: $paths", paths.none { it.startsWith("/api/") })
    }

    @Test
    fun coverOffV2FailsUsesLegacyQueueAsBefore() {
        val parked = mutableListOf<String>()
        assertTrue(sendPipeline(coverOn = false, parked = parked))
        assertTrue(parked.isEmpty())
        assertTrue(synchronized(seen) { seen.any { it.path.orEmpty().startsWith("/api/queue") } })
    }

    @Test
    fun coverRequestsAskForIdentityEncoding() {
        v2Send()
        val cover = synchronized(seen) { seen.filter { it.path.orEmpty().startsWith("/v2/") } }
        assertTrue(cover.isNotEmpty())
        // Not OkHttp's transparent `gzip`: the reply must not be compressed (size leak).
        cover.forEach { assertEquals("identity", it.getHeader("Accept-Encoding")) }
    }

    private class MemStore : CoverOutbox.IdStore {
        var ids = setOf<String>()
        override fun read() = ids
        override fun write(ids: Set<String>) { this.ids = ids }
    }

    @Test
    fun outboxRetriesOnlyTheDisguisedPathUntilDelivered() = runBlocking {
        val store = MemStore()
        var attempts = 0; var delivered = false; var gaveUp = false
        val job = CoverOutbox.parkWith(store, CoroutineScope(Dispatchers.Default), "m-2",
            attempt = { attempts++; attempts >= 3 },
            onDelivered = { delivered = true }, onGaveUp = { gaveUp = true },
            sleep = { })
        assertTrue("m-2" in CoverOutbox.awaiting.value || job.isCompleted)
        job.join()
        assertEquals(3, attempts); assertTrue(delivered); assertFalse(gaveUp)
        assertFalse("m-2" in CoverOutbox.awaiting.value)
        assertTrue(store.ids.isEmpty())
    }

    @Test
    fun outboxGivesUpAfterAnHourAndMarksFailed() = runBlocking {
        val store = MemStore()
        var now = 0L; var attempts = 0; var gaveUp = false
        val job = CoverOutbox.parkWith(store, CoroutineScope(Dispatchers.Default), "m-3",
            attempt = { attempts++; false }, onDelivered = { }, onGaveUp = { gaveUp = true },
            clock = { now }, sleep = { now += it })
        job.join()
        assertTrue(gaveUp)
        assertTrue(now >= CoverOutbox.GIVE_UP_AFTER_MS)
        assertTrue(attempts in 5..20)
    }

    /** Device finding: typing pings / receipts used the plain relay in cover mode. */
    @Test
    fun coverModeClosesTheLegacyDoorForEveryOneToOneSendButNotGroups() {
        assertFalse(CoverOutbox.legacyRelayAllowedFor(coverModeOn = true, recipient = "peer", conversationId = "peer"))
        assertTrue(CoverOutbox.legacyRelayAllowedFor(coverModeOn = false, recipient = "peer", conversationId = "peer"))
        // Group fan-out (documented as not disguised in any mode).
        assertTrue(CoverOutbox.legacyRelayAllowedFor(coverModeOn = true, recipient = "member", conversationId = "group-1"))
        assertTrue(CoverOutbox.legacyRelayAllowedFor(coverModeOn = true, recipient = "member", conversationId = ""))
    }

    @Test
    fun vpsClientDoorsAskTheCoverRule() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        var src: File? = null
        while (d != null && src == null) {
            src = listOf("src/main/java/com/oshi/messenger/network/vps/VPSClient.kt",
                "app/src/main/java/com/oshi/messenger/network/vps/VPSClient.kt").map { File(d, it) }.firstOrNull { it.exists() }
            d = d.parentFile
        }
        // sendMessage + notifyVPSQueue (the group path's own door).
        assertEquals(2, Regex("CoverOutbox\\.legacyRelayAllowedFor\\(").findAll(src!!.readText()).count())
    }

    @Test
    fun parkedIdsFromADeadProcessAreRecoveredAsFailed() {
        val store = MemStore().apply { ids = setOf("old-1", "old-2") }
        assertEquals(setOf("old-1", "old-2"), CoverOutbox.recoverWith(store))
        assertTrue(store.ids.isEmpty())
    }

    /** Source pin: both repository call sites route through the gate; the dead covert branch is gone. */
    @Test
    fun repositoryHasNoLegacyFallbackInCoverMode() {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        var src: File? = null
        while (d != null && src == null) {
            src = listOf("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt",
                "app/src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt")
                .map { File(d, it) }.firstOrNull { it.exists() }
            d = d.parentFile
        }
        val text = src!!.readText()
        assertFalse(text.contains("sendViaCovertChannel("))
        assertFalse(text.contains("rejectUnsupportedSteganographyIfEnabled()"))
        assertEquals("text + media park sites", 3, Regex("parkForCover\\(message,").findAll(text).count())
        assertEquals("both iOS-peer dual sends gated", 2,
            Regex("val vpsOk = if \\(!CoverOutbox\\.legacyRelayAllowed\\(coverOn\\)\\) false").findAll(text).count())
    }
}
