package com.oshi.messenger

import com.oshi.messenger.service.PushRetryQueue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * `PushRetryQueue` — the wake pushes that failed, retried later.
 *
 * Port of iOS `OSHI/PushRetryQueue.swift`. The three bounds each stop a different
 * wrong behaviour and each is asserted separately below, because getting one right
 * and another wrong is silent: attempts alone still fires a banner for a message
 * that was read an hour ago (`PushRetryQueue.swift:53-64`).
 *
 * The clock is injected, so nothing here sleeps.
 */
class PushRetryQueueTest {

    private lateinit var dir: File
    private var now = 1_700_000_000_000L

    private fun queue() = PushRetryQueue(dir) { now }

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "push_retry_test_${System.nanoTime()}")
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun PushRetryQueue.push(recipient: String, messageId: String) = enqueue(
        recipientPublicKey = recipient,
        type = "message",
        title = "New Message",
        body = "You have a new encrypted message",
        data = mapOf("messageId" to messageId, "v2" to "1"),
    )

    @Test
    fun `a failed push is queued and retried`() = runBlocking {
        val q = queue()
        q.push("peerA", "m1")
        assertEquals(1, q.pendingCount)

        val seen = mutableListOf<String>()
        val sent = q.drain { e -> seen.add(e.data["messageId"] ?: ""); true }

        assertEquals(1, sent)
        assertEquals(listOf("m1"), seen)
        assertEquals(0, q.pendingCount)     // success drops the entry
    }

    @Test
    fun `a retry that fails again stays queued`() = runBlocking {
        val q = queue()
        q.push("peerA", "m1")
        q.drain { false }
        assertEquals(1, q.pendingCount)
        assertEquals(1, q.snapshot().single().attempts)
    }

    @Test
    fun `the same message is queued once, however many times it fails`() {
        val q = queue()
        q.push("peerA", "m1")
        q.push("peerA", "m1")
        q.push("peerA", "m1")
        assertEquals(1, q.pendingCount)
        // A different message for the same peer is its own entry.
        q.push("peerA", "m2")
        assertEquals(2, q.pendingCount)
    }

    @Test
    fun `backoff holds an entry back until the gap has elapsed`() = runBlocking {
        val q = queue()
        q.push("peerA", "m1")
        q.drain { false }                                 // attempt 1, at `now`

        now += PushRetryQueue.BACKOFF_MS - 1
        var tried = 0
        q.drain { tried++; false }
        assertEquals(0, tried)                            // too soon
        assertEquals(1, q.snapshot().single().attempts)

        now += 2
        q.drain { tried++; false }
        assertEquals(1, tried)
        assertEquals(2, q.snapshot().single().attempts)
    }

    @Test
    fun `it gives up after the attempt cap`() = runBlocking {
        val q = queue()
        q.push("peerA", "m1")
        var tried = 0
        repeat(PushRetryQueue.MAX_ATTEMPTS + 3) {
            q.drain { tried++; false }
            now += PushRetryQueue.BACKOFF_MS
        }
        assertEquals(PushRetryQueue.MAX_ATTEMPTS, tried)
        assertEquals(0, q.pendingCount)
    }

    @Test
    fun `a stale push is dropped even with attempts left`() = runBlocking {
        // The bound that matters most: a push carries no content, so once the message
        // is old the banner has nothing behind it.
        val q = queue()
        q.push("peerA", "m1")
        now += PushRetryQueue.MAX_AGE_MS + 1

        var tried = 0
        val sent = q.drain { tried++; true }

        assertEquals(0, tried)             // never even attempted
        assertEquals(0, sent)
        assertEquals(0, q.pendingCount)    // and gone from the queue
        assertTrue(PushRetryQueue.MAX_ATTEMPTS > 1)   // attempts were NOT what dropped it
    }

    @Test
    fun `the queue survives a process restart`() = runBlocking {
        queue().push("peerA", "m1")
        // A brand-new instance over the same directory == a fresh process.
        val reloaded = queue()
        assertEquals(1, reloaded.pendingCount)
        assertEquals("peerA", reloaded.snapshot().single().recipientPublicKey)
        assertEquals("m1", reloaded.snapshot().single().data["messageId"])
        assertEquals(1, reloaded.drain { true })
        assertEquals(0, queue().pendingCount)          // and the drain persisted too
    }

    @Test
    fun `draining an empty queue is a no-op`() = runBlocking {
        var tried = 0
        assertEquals(0, queue().drain { tried++; true })
        assertEquals(0, tried)
    }

    @Test
    fun `a sender that throws leaves the entry queued`() = runBlocking {
        val q = queue()
        q.push("peerA", "m1")
        q.drain { throw IllegalStateException("network down") }
        assertEquals(1, q.pendingCount)
        assertEquals(1, q.snapshot().single().attempts)
    }

    @Test
    fun `the file is bounded`() {
        val q = queue()
        repeat(PushRetryQueue.CAP + 25) { i -> q.push("peer$i", "m$i") }
        assertEquals(PushRetryQueue.CAP, q.pendingCount)
        // Oldest evicted first, so the most recent failures are the ones kept.
        assertEquals("peer${PushRetryQueue.CAP + 24}", q.snapshot().last().recipientPublicKey)
    }
}
