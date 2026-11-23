package com.oshi.messenger

import com.oshi.messenger.network.encryption.RatchetSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The legacy ratchet must be safe to call from several coroutines at once.
 *
 * `MessageRepository` serialises every RECEIVE behind `decryptionMutex` and no send
 * path takes it: a location-share tick, a read receipt fired from a receive callback
 * and a user tap all land on `Dispatchers.IO` for the same peer. Unguarded, two of
 * them read the same `sendingChainKey`, derive the SAME AES-256-GCM message key and
 * encrypt two different plaintexts under it.
 *
 * These tests drive the real critical section ([RatchetSession.withSendKey], which is
 * what `DoubleRatchet.encrypt` calls) from real threads. `DoubleRatchet` itself cannot
 * be exercised in a JVM unit test — it needs `android.util.Base64`/`Log` and a
 * `CryptoManager` — but the state it mutates is exactly this object.
 */
class DoubleRatchetConcurrencyTest {

    private fun kdf(tag: String, input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(tag.toByteArray() + input)

    private val derive: (ByteArray) -> ByteArray = { kdf("MessageKey", it) }
    private val advance: (ByteArray) -> ByteArray = { kdf("ChainKey", it) }

    private fun session() = RatchetSession(
        rootKey = ByteArray(32) { 1 },
        sendingChainKey = ByteArray(32) { 2 },
        receivingChainKey = ByteArray(32) { 3 },
        ourRatchetPrivateKey = ByteArray(32) { 4 },
        ourRatchetPublicKey = ByteArray(32) { 5 },
        theirRatchetPublicKey = null,
        sessionId = "sess",
        isInitiator = true,
    )

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `concurrent sends never derive the same message key`() {
        val threads = 8
        val perThread = 60
        val s = session()
        val keys = Collections.synchronizedList(ArrayList<String>())
        val numbers = Collections.synchronizedList(ArrayList<Int>())
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            Thread {
                start.await()
                repeat(perThread) {
                    s.withSendKey(derive, advance) { n, key, _ ->
                        // Widen the derive→advance window the way a real AES-GCM seal
                        // does: without the lock this is where the second thread walks in.
                        Thread.yield()
                        keys.add(hex(key))
                        numbers.add(n)
                    }
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("threads did not finish", done.await(30, TimeUnit.SECONDS))

        val total = threads * perThread
        assertEquals(total, keys.size)
        assertEquals("two sends derived the SAME message key — (key,nonce) reuse", total, keys.toSet().size)
        assertEquals("two sends took the same message number", total, numbers.toSet().size)
        assertEquals("message numbers must be a dense 0..n-1 range", (0 until total).toSet(), numbers.toSet())
    }

    @Test
    fun `a failing send does not advance the chain`() {
        val s = session()
        val first = s.withSendKey(derive, advance) { _, key, _ -> hex(key) }
        val chainAfterFirst = hex(s.sendingChainKey)

        val boom = runCatching {
            s.withSendKey(derive, advance) { _, _, _ -> throw IllegalStateException("seal failed") }
        }
        assertTrue(boom.isFailure)
        assertEquals("a throwing encrypt must leave the chain untouched", chainAfterFirst, hex(s.sendingChainKey))
        assertEquals(1, s.sendingMessageNumber)

        // ...and the next send still gets a key that was never used.
        val third = s.withSendKey(derive, advance) { _, key, _ -> hex(key) }
        assertTrue(first != third)
    }

    @Test
    fun `the DH-ratchet flag rides exactly one send`() {
        val s = session()
        s.needsDHRatchet = true
        assertTrue(s.withSendKey(derive, advance) { _, _, flag -> flag })
        assertTrue(!s.withSendKey(derive, advance) { _, _, flag -> flag })
    }

    @Test
    fun `serialising skipped keys never races a writer`() {
        val s = session()
        val writes = 4000
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failures = Collections.synchronizedList(ArrayList<Throwable>())

        val writer = Thread {
            start.await()
            repeat(writes) { i -> s.putSkippedKey("k$i", ByteArray(32) { i.toByte() }) }
            done.countDown()
        }
        val reader = Thread {
            start.await()
            try {
                repeat(writes) { s.snapshotSkippedKeys().forEach { (_, v) -> v.size } }
            } catch (t: Throwable) {
                // A bare HashMap throws ConcurrentModificationException here — which is
                // what `saveSessions` used to swallow, silently never persisting.
                failures.add(t)
            }
            done.countDown()
        }
        writer.start(); reader.start(); start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        assertEquals("snapshot raced the writer: $failures", 0, failures.size)
        assertEquals(writes, s.skippedKeyCount())
    }

    @Test
    fun `skipped keys expire after the iOS 7-day window`() {
        val s = session()
        val now = 1_800_000_000L
        val week = 7L * 24 * 60 * 60
        s.putSkippedKey("old", ByteArray(32), nowSeconds = now - week - 1)
        s.putSkippedKey("fresh", ByteArray(32), nowSeconds = now - 10)

        assertEquals(1, s.pruneSkippedKeys(week, nowSeconds = now))
        assertEquals(setOf("fresh"), s.snapshotSkippedKeys().keys)
    }
}
