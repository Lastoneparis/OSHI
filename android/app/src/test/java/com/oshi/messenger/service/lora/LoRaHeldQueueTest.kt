package com.oshi.messenger.service.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * __LORA_HELD_DURABLE_2026_11_18__ / __LORA_PROBE_BEFORE_FLUSH_2026_11_18__ / __LORA_AIRTIME_HINT_2026_11_18__
 *
 * The anti-jamming audit (18/11) found the radio's held queue in memory only, capped at 20 with a
 * silent `removeFirst()`, unlocked across three threads, and flushed with `toList()` + `clear()`;
 * and the flush ran before the OSHI Mesh probe answered. These pin each of those.
 */
class LoRaHeldQueueTest {

    private class MemStore : LoRaHeldQueue.Store {
        @Volatile var text: String? = null
        var writes = 0
        override fun read() = text
        override fun write(text: String) { this.text = text; writes++ }
    }

    private fun wire(i: Int) = """{"id":"m$i","pad":"${"x".repeat(i % 7)}"}""".toByteArray()

    // ───────────────────────────── persistence ─────────────────────────────

    @Test
    fun `held messages survive a process death`() {
        val store = MemStore()
        val q = LoRaHeldQueue(store)
        q.add("a", "peerA", wire(1), 10)
        q.add("b", "peerB", wire(2), 20)
        q.remove("a")

        val revived = LoRaHeldQueue(store)   // a new process reading the same store
        val held = revived.snapshot()
        assertEquals(listOf("b"), held.map { it.id })
        assertEquals("peerB", held[0].recipientPublicKey)
        assertEquals(20L, held[0].heldAtMs)
        assertTrue(held[0].wireJson.contentEquals(wire(2)))
    }

    @Test
    fun `file store round-trips and tolerates a corrupt file`() {
        val dir = Files.createTempDirectory("held").toFile()
        val f = File(dir, LoRaHeldQueue.FILE_NAME)
        LoRaHeldQueue(LoRaHeldQueue.FileStore(f)).add("x", "p", wire(3), 5)
        assertEquals(listOf("x"), LoRaHeldQueue(LoRaHeldQueue.FileStore(f)).snapshot().map { it.id })
        f.writeText("{not json")
        assertTrue(LoRaHeldQueue(LoRaHeldQueue.FileStore(f)).isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `the same message held twice is one entry and keeps its first held time`() {
        val q = LoRaHeldQueue()
        q.add("a", "p", wire(1), 10)
        q.add("a", "p", wire(1), 99)
        assertEquals(1, q.size)
        assertEquals(10L, q.snapshot()[0].heldAtMs)
    }

    // ───────────────────────────── no silent drop ─────────────────────────────

    @Test
    fun `over capacity the evicted ids are RETURNED, oldest first, never dropped in silence`() {
        val q = LoRaHeldQueue(capacity = 3)
        assertTrue(q.add("1", "p", wire(1), 1).isEmpty())
        q.add("2", "p", wire(2), 2)
        q.add("3", "p", wire(3), 3)
        assertEquals(listOf("1"), q.add("4", "p", wire(4), 4))
        assertEquals(listOf("2", "3", "4"), q.snapshot().map { it.id })
        // The old cap was 20; the new default leaves room for a long day off-grid.
        assertTrue(LoRaHeldQueue.DEFAULT_CAPACITY >= 200)
    }

    // ───────────────────────────── thread safety ─────────────────────────────

    /**
     * Writers on several threads while a "flusher" drains: every id ends up exactly once in either
     * "sent" or the queue. The old `toList()` + `clear()` lost whatever landed between the two.
     */
    @Test
    fun `concurrent add and drain never lose a message`() {
        val q = LoRaHeldQueue(MemStore(), capacity = 100_000)
        val writers = 6
        val perWriter = 400
        val sent = Collections.synchronizedSet(HashSet<String>())
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)
        val writing = java.util.concurrent.atomic.AtomicBoolean(true)
        val flusher = Thread {
            start.await()
            while (writing.get() || !q.isEmpty()) {
                for (h in q.snapshot()) {
                    if (q.remove(h.id)) assertTrue("sent twice: ${h.id}", sent.add(h.id))
                }
            }
        }
        flusher.start()
        repeat(writers) { w ->
            Thread {
                start.await()
                repeat(perWriter) { i -> q.add("w$w-$i", "p", wire(i), i.toLong()) }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        writing.set(false)
        flusher.join(30_000)
        assertEquals(writers * perWriter, sent.size)
        assertTrue(q.isEmpty())
    }

    // ───────────────────────────── probe before flush ─────────────────────────────

    @Test
    fun `a ready radio holds until the firmware probe is settled`() {
        // Just connected: probe sent, no answer yet. The old code flushed here, onto the stock path.
        assertFalse(LoRaRadioGate.mayFlush(ready = true, probeDone = false, answeredOshi = false, excludedForJamming = false))
        assertEquals(
            "waiting for the radio's firmware probe",
            LoRaRadioGate.holdReason(probeDone = false, answeredOshi = false, excludedForJamming = false),
        )
        // The radio answered: OSHI Mesh, flush now (the probe timer has not even fired).
        assertTrue(LoRaRadioGate.mayFlush(ready = true, probeDone = false, answeredOshi = true, excludedForJamming = false))
        assertNull(LoRaRadioGate.holdReason(probeDone = false, answeredOshi = true, excludedForJamming = false))
        // 6 s without an answer: stock, flush now.
        assertTrue(LoRaRadioGate.mayFlush(ready = true, probeDone = true, answeredOshi = false, excludedForJamming = false))
        // Not connected, or jammed: never.
        assertFalse(LoRaRadioGate.mayFlush(ready = false, probeDone = true, answeredOshi = true, excludedForJamming = false))
        assertFalse(LoRaRadioGate.mayFlush(ready = true, probeDone = true, answeredOshi = true, excludedForJamming = true))
        assertEquals(
            "radio channel looks jammed",
            LoRaRadioGate.holdReason(probeDone = true, answeredOshi = true, excludedForJamming = true),
        )
    }

    @Test
    fun `fits follows the radio - 12 frames stock, 64 frames oshi mesh`() {
        val stockMax = ByteArray(LoRaEnvelope.MAX_CHUNKS * OshiMeshProtocol.MAX_FRAG_DATA)
        assertTrue(LoRaRadioGate.fits(stockMax, oshiFirmware = false))
        assertFalse(LoRaRadioGate.fits(stockMax + 1, oshiFirmware = false))
        assertTrue(LoRaRadioGate.fits(stockMax + 1, oshiFirmware = true))
        assertTrue(LoRaRadioGate.fits(ByteArray(OshiMeshProtocol.MAX_MESSAGE), oshiFirmware = true))
        assertFalse(LoRaRadioGate.fits(ByteArray(OshiMeshProtocol.MAX_MESSAGE + 1), oshiFirmware = true))
    }

    // ───────────────────────────── airtime hint ─────────────────────────────

    @Test
    fun `queued for over a minute is flagged, any progress clears it, stale entries are forgotten`() {
        val w = LoRaAirtimeWait()
        w.onQueued("a", 0)
        w.onQueued("b", 30_000)
        assertTrue(w.waiting(59_999).isEmpty())
        assertEquals(setOf("a"), w.waiting(60_000))
        w.onQueued("a", 70_000)   // a repeated QUEUED does not restart the clock
        assertEquals(setOf("a", "b"), w.waiting(90_000))
        w.onProgress("a")          // SENT
        assertEquals(setOf("b"), w.waiting(90_000))
        assertTrue(w.waiting(30_000 + LoRaAirtimeWait.FORGET_AFTER_MS + 1).isEmpty())
        assertFalse(w.hasPending())
    }
}
