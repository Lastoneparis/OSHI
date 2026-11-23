package com.oshi.messenger.atrest

import com.oshi.messenger.data.local.atrest.AtRestKeyProvider
import com.oshi.messenger.data.local.atrest.ChunkedMediaCipher
import com.oshi.messenger.data.local.atrest.PlaintextScratch
import com.oshi.messenger.data.local.atrest.SealedFile
import com.oshi.messenger.network.v2.V2PendingEnvelopeStore
import com.oshi.messenger.service.PushRetryQueue
import com.oshi.messenger.service.media.MediaAttachmentStore
import com.oshi.messenger.service.media.V2SendResumeStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.SecureRandom

/**
 * At-rest encryption of the file stores, with an injected key (no Keystore on the JVM).
 */
class AtRestStoresTest {

    private lateinit var root: File
    private val master = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val keys = AtRestKeyProvider { master }
    private val noKey = AtRestKeyProvider.NONE

    @Before fun setUp() { root = Files.createTempDirectory("oshi-atrest-").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    private fun bytes(n: Int, seed: Int = 0) = ByteArray(n) { ((it * 7 + seed) % 251).toByte() }

    private fun mediaFile(store: MediaAttachmentStore, id: String) =
        File(store.directory, MediaAttachmentStore.encodeName(id) + ".dat")

    /** What a pre-2026-09-22 build (or a no-key build before 2026-09-24) left on disk. */
    private fun writeLegacy(id: String, data: ByteArray) {
        val dir = File(root, MediaAttachmentStore.DIR_NAME).apply { mkdirs() }
        File(dir, MediaAttachmentStore.encodeName(id) + ".dat").writeBytes(data)
    }

    /** Crude "is any plaintext window visible on disk" check. */
    private fun containsWindow(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    // =====================================================================
    // MediaAttachmentStore
    // =====================================================================

    @Test fun `media saved with a key is OSHIMED1 on disk and round trips (multi-chunk)`() {
        val store = MediaAttachmentStore(root, keys)
        val data = bytes(3 * 1024 * 1024 + 777)
        assertTrue(store.save("vid-1", data))
        val onDisk = mediaFile(store, "vid-1").readBytes()
        assertTrue(ChunkedMediaCipher.hasMagic(onDisk))
        assertFalse(containsWindow(onDisk.copyOfRange(0, 200_000), data.copyOfRange(1000, 1064)))
        assertTrue(store.isEncryptedOnDisk("vid-1"))
        assertEquals(data.size.toLong(), store.sizeOf("vid-1"))          // PLAINTEXT size
        // cold instance: bytes come from disk, not the LRU
        assertArrayEquals(data, MediaAttachmentStore(root, keys).load("vid-1"))
        MediaAttachmentStore(root, keys).openStream("vid-1")!!.use { assertArrayEquals(data, it.readBytes()) }
    }

    @Test fun `small and empty media round trip`() {
        val store = MediaAttachmentStore(root, keys)
        assertTrue(store.save("small", bytes(10)))
        assertTrue(store.save("empty", ByteArray(0)))
        val cold = MediaAttachmentStore(root, keys)
        assertArrayEquals(bytes(10), cold.load("small"))
        assertArrayEquals(ByteArray(0), cold.load("empty"))
        assertEquals(0L, cold.sizeOf("empty"))
    }

    @Test fun `save from file encrypts, consumes the source when moved, and never loses it on failure`() {
        val store = MediaAttachmentStore(root, keys)
        val src = File(root, "scratch.bin").apply { writeBytes(bytes(200_000, 5)) }
        assertTrue(store.save("inbound", src, moveSource = true))
        assertFalse(src.exists())
        assertTrue(store.isEncryptedOnDisk("inbound"))
        assertArrayEquals(bytes(200_000, 5), MediaAttachmentStore(root, keys).load("inbound"))

        val kept = File(root, "kept.bin").apply { writeBytes(bytes(5000, 9)) }
        assertTrue(store.save("copied", kept, moveSource = false))
        assertTrue(kept.exists())
        assertArrayEquals(bytes(5000, 9), MediaAttachmentStore(root, keys).load("copied"))
    }

    @Test fun `legacy plaintext blobs are still readable with a key`() {
        writeLegacy("legacy", bytes(4096, 1))   // what an old build wrote
        val store = MediaAttachmentStore(root, keys)
        assertFalse(store.isEncryptedOnDisk("legacy"))
        assertArrayEquals(bytes(4096, 1), store.load("legacy"))
        assertEquals(4096L, store.sizeOf("legacy"))
        store.openStream("legacy")!!.use { assertArrayEquals(bytes(4096, 1), it.readBytes()) }
    }

    // __PLAINTEXT_LEFTOVERS_2026_09_24__ no plaintext fallback any more.
    @Test fun `no key never writes plaintext - bytes held in memory, sealed once the key is back`() {
        var keyOn = false
        val flaky = AtRestKeyProvider { if (keyOn) master else null }
        val store = MediaAttachmentStore(root, flaky)
        assertFalse("not durable without a key", store.save("p", bytes(100)))
        assertFalse("nothing written in clear", mediaFile(store, "p").exists())
        // …but nothing is lost for this process: served from memory, by any instance.
        val other = MediaAttachmentStore(root, flaky)
        assertArrayEquals(bytes(100), other.load("p"))
        assertTrue(other.has("p"))
        assertEquals(100L, other.sizeOf("p"))

        // A handed-over scratch file is held by reference, not copied in clear.
        val scratch = File(root, "v2blob.bin").apply { writeBytes(bytes(5000, 3)) }
        assertFalse(store.save("f", scratch, moveSource = true))
        assertFalse(mediaFile(store, "f").exists())
        // The router deletes its path in a `finally` after we return: we must have moved it.
        assertFalse(scratch.exists())
        val held = File(root, "v2blob.bin.held")
        assertArrayEquals(bytes(5000, 3), other.load("f"))

        keyOn = true
        assertEquals(2, store.flushAwaitingKey())
        assertTrue(store.isEncryptedOnDisk("p"))
        assertTrue(store.isEncryptedOnDisk("f"))
        assertFalse("the plaintext scratch is consumed once sealed", held.exists())
        val cold = MediaAttachmentStore(root, keys)
        assertArrayEquals(bytes(100), cold.load("p"))
        assertArrayEquals(bytes(5000, 3), cold.load("f"))
    }

    @Test fun `no key and a borrowed source file - refused, caller keeps it, nothing written`() {
        val src = File(root, "keep.bin").apply { writeBytes(bytes(64)) }
        val store = MediaAttachmentStore(root, noKey)
        assertFalse(store.save("b", src, moveSource = false))
        assertTrue(src.exists())
        assertFalse(mediaFile(store, "b").exists())
        assertNull(store.load("b"))
    }

    @Test fun `migration encrypts legacy blobs in place, verified and idempotent`() {
        writeLegacy("a", bytes(3 * 1024 * 1024, 2))
        writeLegacy("b", bytes(10, 3))
        writeLegacy("c", ByteArray(0))
        val store = MediaAttachmentStore(root, keys)
        store.save("already", bytes(50))

        val r = store.migrateLegacy()
        assertEquals(3, r.migrated)
        assertEquals(1, r.alreadyEncrypted)
        assertEquals(0, r.failed)
        for (id in listOf("a", "b", "c", "already")) assertTrue(id, store.isEncryptedOnDisk(id))
        val cold = MediaAttachmentStore(root, keys)
        assertArrayEquals(bytes(3 * 1024 * 1024, 2), cold.load("a"))
        assertArrayEquals(bytes(10, 3), cold.load("b"))
        assertArrayEquals(ByteArray(0), cold.load("c"))
        // no temp debris, ids unchanged
        assertTrue(store.directory.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertEquals(setOf("a", "b", "c", "already"), store.listStoredIds().toSet())

        val again = store.migrateLegacy()
        assertEquals(0, again.migrated)
        assertEquals(4, again.alreadyEncrypted)
    }

    @Test fun `migration without a key touches nothing`() {
        writeLegacy("x", bytes(100))
        val r = MediaAttachmentStore(root, noKey).migrateLegacy()
        assertFalse(r.keyAvailable)
        assertArrayEquals(bytes(100), File(File(root, "media"), MediaAttachmentStore.encodeName("x") + ".dat").readBytes())
    }

    @Test fun `tampered or undecryptable blobs read as null and are never deleted`() {
        val store = MediaAttachmentStore(root, keys)
        store.save("t", bytes(5000))
        val f = mediaFile(store, "t")
        val raw = f.readBytes()
        raw[raw.size - 3] = (raw[raw.size - 3].toInt() xor 1).toByte()
        f.writeBytes(raw)
        val cold = MediaAttachmentStore(root, keys)
        assertNull(cold.load("t"))
        assertNull(cold.decryptToTempFile("t", File(root, "cache")))
        assertTrue(f.exists())
        // no leftover partial plaintext copy
        assertTrue(PlaintextScratch.dir(File(root, "cache")).listFiles().orEmpty().isEmpty())

        store.save("k", bytes(100))
        assertNull(MediaAttachmentStore(root, noKey).load("k"))      // key lost / Keystore down
        assertTrue(mediaFile(store, "k").exists())
        assertNull(MediaAttachmentStore(root, AtRestKeyProvider { ByteArray(32) { 9 } }).load("k"))
        assertTrue(mediaFile(store, "k").exists())
    }

    @Test fun `decryptToTempFile writes plaintext only under the scratch dir, swept at startup`() {
        val store = MediaAttachmentStore(root, keys)
        val data = bytes(300_000, 4)
        store.save("v", data)
        val cache = File(root, "cache").apply { mkdirs() }
        val tmp = MediaAttachmentStore(root, keys).decryptToTempFile("v", cache, ".mp4")!!
        assertEquals(PlaintextScratch.dir(cache), tmp.parentFile)
        assertTrue(tmp.name.endsWith(".mp4"))
        assertArrayEquals(data, tmp.readBytes())

        val legacyOld = File(cache, "share_media.jpg").apply { writeBytes(bytes(10)); setLastModified(0) }
        val legacyFresh = File(cache, "media_viewer_doc.pdf").apply { writeBytes(bytes(10)) }
        val unrelated = File(cache, "keep_me.json").apply { writeText("{}") }
        val removed = PlaintextScratch.sweepAtStartup(cache)
        assertEquals(2, removed)
        assertFalse(tmp.exists())
        assertFalse(legacyOld.exists())
        assertTrue(legacyFresh.exists())      // a share target may still be reading it
        assertTrue(unrelated.exists())
    }

    @Test fun `sweepTempFiles still removes crash leftovers`() {
        val store = MediaAttachmentStore(root, keys)
        store.save("s", bytes(10))
        val leftover = File(store.directory, "deadbeef.dat.123.tmp").apply { writeBytes(bytes(5)) }
        assertEquals(1, store.sweepTempFiles())
        assertFalse(leftover.exists())
        assertArrayEquals(bytes(10), MediaAttachmentStore(root, keys).load("s"))
    }

    // =====================================================================
    // SealedFile (small JSON stores)
    // =====================================================================

    @Test fun `sealed file round trip, label binding and tamper rejection`() {
        val f = File(root, "store.json")
        SealedFile.writeAtomic(f, "label-a", keys, """{"x":1}""".toByteArray())
        val raw = f.readBytes()
        assertTrue(SealedFile.isSealed(raw))
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("\"x\""))
        assertEquals("""{"x":1}""", SealedFile.readText(f, "label-a", keys))
        try { SealedFile.readText(f, "label-b", keys); fail("label swap accepted") } catch (_: IOException) {}
        try { SealedFile.readText(f, "label-a", noKey); fail("read without key") } catch (_: IOException) {}
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        f.writeBytes(raw)
        try { SealedFile.readText(f, "label-a", keys); fail("tamper accepted") } catch (_: IOException) {}
    }

    @Test fun `sealed writer refuses to clobber a sealed file without a key`() {
        val f = File(root, "store.json")
        SealedFile.writeAtomic(f, "l", keys, "secret".toByteArray())
        val before = f.readBytes()
        try { SealedFile.writeAtomic(f, "l", noKey, "[]".toByteArray()); fail() } catch (_: IOException) {}
        assertArrayEquals(before, f.readBytes())
    }

    @Test fun `sealed migrate converts legacy plaintext and leaves no temp`() {
        val f = File(root, "legacy.json").apply { writeText("""[{"a":"b"}]""") }
        assertEquals("""[{"a":"b"}]""", SealedFile.readText(f, "l", keys))    // legacy passthrough
        assertFalse(SealedFile.migrate(f, "l", noKey))
        assertTrue(SealedFile.isLegacyPlaintext(f))
        assertTrue(SealedFile.migrate(f, "l", keys))
        assertFalse(SealedFile.isLegacyPlaintext(f))
        assertEquals("""[{"a":"b"}]""", SealedFile.readText(f, "l", keys))
        assertFalse(SealedFile.migrate(f, "l", keys))                        // idempotent
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    // =====================================================================
    // The JSON stores themselves
    // =====================================================================

    @Test fun `push retry queue is sealed and a legacy queue is read then sealed`() {
        val q = PushRetryQueue(root, keys = keys)
        q.enqueue("recipientKeyAAAA", "message", "Alice", "hello secret", mapOf("messageId" to "m1"))
        val f = File(root, "push_retry_queue.json")
        assertTrue(SealedFile.isSealed(f.readBytes()))
        assertEquals("hello secret", PushRetryQueue(root, keys = keys).snapshot().single().body)

        // legacy: what an old build left on disk
        f.delete()
        PushRetryQueue(root, keys = noKey).enqueue("rk", "message", "t", "legacy body", mapOf("messageId" to "m2"))
        assertTrue(SealedFile.isLegacyPlaintext(f))
        val migrated = PushRetryQueue(root, keys = keys)
        assertEquals("legacy body", migrated.snapshot().single().body)
        assertTrue(SealedFile.isSealed(f.readBytes()))
    }

    @Test fun `pending envelope store is sealed, reads legacy, and fails closed without a key`() {
        val body = "pending 🔒".toByteArray()
        val packet = JSONObject().put("ciphertext", "opaque")
        V2PendingEnvelopeStore(root, keys).save("alice", "bob", "id", body, packet)
        val dir = File(root, "v2_pending_envelopes")
        val rec = dir.listFiles()!!.single { it.extension == "json" }
        assertTrue(SealedFile.isSealed(rec.readBytes()))
        assertEquals(packet.toString(), V2PendingEnvelopeStore(root, keys).load("alice", "bob", "id", body)!!.toString())
        // Sealed + no key must THROW (null would let the caller re-encrypt the same id).
        try { V2PendingEnvelopeStore(root, noKey).load("alice", "bob", "id", body); fail() } catch (_: IOException) {}

        V2PendingEnvelopeStore(root, noKey).save("alice", "carol", "id2", body, packet)   // legacy record
        val legacyStore = V2PendingEnvelopeStore(root, keys)
        assertEquals(1, legacyStore.migrateLegacy())
        assertTrue(dir.listFiles()!!.filter { it.extension == "json" }.all { SealedFile.isSealed(it.readBytes()) })
        assertEquals(packet.toString(), legacyStore.load("alice", "carol", "id2", body)!!.toString())
    }

    @Test fun `send resume ledger holding file keys is sealed`() {
        val s = V2SendResumeStore(root, keys)
        val fileKey = ByteArray(32) { 0x42 }
        s.put("m", "rk", "sha", "blob", fileKey, ByteArray(12) { 1 }, 3)
        val f = File(root, "v2_send_resume.json")
        assertTrue(SealedFile.isSealed(f.readBytes()))
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("4242424242"))
        assertNotNull(V2SendResumeStore(root, keys).record("m", "rk", "sha"))
        assertNull(V2SendResumeStore(root, noKey).record("m", "rk", "sha"))   // degrades to "no resume"
    }
}
