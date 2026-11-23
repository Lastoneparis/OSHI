package com.oshi.messenger

import com.oshi.messenger.data.repository.MessageRepository
import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.OSHICryptoV2Streaming
import com.oshi.messenger.network.v2.V2FileKeyMessage
import com.oshi.messenger.network.v2.V2RevealPlan
import com.oshi.messenger.service.media.MediaAttachmentStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/**
 * M-2's LAST MILE: the decrypted blob reaches `MessageRepository` as a FILE, and the
 * file reaches [MediaAttachmentStore] without anyone allocating the whole plaintext.
 *
 * The streaming decrypt itself is covered by `V2BlobStreamingTest` (it equals
 * `OSHICryptoV2.decryptFile` byte for byte). What is new here, and what these tests
 * exist to pin, is the hand-off that used to undo it: `V2Inbound.File` carried a
 * `ByteArray`, so `deliverFile` read its temp file straight back and peak heap was one
 * whole file again.
 *
 * ## Why the hashes are literals
 *
 * The expected digests below were produced OUTSIDE this codebase, in Python, over a
 * byte pattern defined by a closed form:
 *
 *     x = 0x12345678
 *     out = bytearray(n)
 *     for i in range(n):
 *         x = (x * 1103515245 + 12345) & 0xFFFFFFFF
 *         out[i] = (x >> 24) & 0xFF
 *     hashlib.sha256(bytes(out)).hexdigest()
 *
 * so "the bytes are unchanged" is checked against an external fixture rather than
 * against another call into the code under test. Break the chunk nonce, the manifest
 * counter, the copy loop or the rename and these fail with a different hex string.
 *
 * THE GENERATOR IS LOAD-BEARING, and it is an LCG because two simpler ones were watched
 * NOT to fail. `(i * 31 + 7) & 0xFF` repeats with period 256, and `(i * 31 + 7 + (i >> 8))
 * & 0xFF` repeats with period 2 MiB — both divide the chunk size, so every chunk was the
 * same byte string and a REORDERED reassembly hashed identically. A fixture that cannot
 * see the defect it is aimed at is not a fixture.
 */
class V2InboundFilePayloadTest {

    private val scratch: File = File(
        System.getProperty("java.io.tmpdir"),
        "oshi-inbound-payload-${System.nanoTime()}"
    ).apply { mkdirs() }

    @After
    fun tearDown() {
        scratch.deleteRecursively()
    }

    /** The LCG above, byte for byte — never periodic at any chunk boundary. */
    private fun pattern(n: Int): ByteArray {
        var x = 0x12345678L
        return ByteArray(n) {
            x = (x * 1103515245L + 12345L) and 0xFFFFFFFFL
            ((x shr 24) and 0xFF).toByte()
        }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private companion object {
        /** sha256 of `pattern(9 * 1024 * 1024)`, computed in Python. */
        const val SHA_9MIB = "9cd8a631865e0972dff8e0a46c3c6dc86cc8a40cd7771f4a6683401f509d1f23"

        /** sha256 of `pattern(5 * 1024 + 13)`, computed in Python. */
        const val SHA_SMALL = "3d4af7c4dc54b2e434d13f69cf42885176b632d709818c1fc39e73d12463e458"

        const val CHUNK = 2 * 1024 * 1024      // the real /v2/blobs chunk size
    }

    // =====================================================================
    // The bytes survive the whole file-backed path
    // =====================================================================

    @Test
    fun `a multi-chunk blob streams to disk and into the store with the fixture hash`() {
        val plain = pattern(9 * 1024 * 1024)
        assertEquals("the fixture itself must match, or nothing below means anything", SHA_9MIB, sha256(plain))

        val enc = OSHICryptoV2.encryptFile(plain, "clip.mp4", "video/mp4", chunkSize = CHUNK)
        assertEquals(5, enc.chunks.size)                    // 4 full chunks + a partial

        // 1. download → disk, one chunk at a time (what `deliverFile` does).
        val tmp = File(scratch, "blob.bin")
        tmp.outputStream().use { sink ->
            OSHICryptoV2Streaming.decryptToStream(
                enc.fileKey, enc.fileNonce, enc.manifest, sink, expectedChunkCount = enc.chunks.size,
            ) { i -> enc.chunks.getOrNull(i) }
        }
        assertEquals(plain.size.toLong(), tmp.length())
        assertEquals("streamed plaintext", SHA_9MIB, sha256(tmp))

        // 2. hand the FILE to the store (what `attachInboundPayload` does above the
        //    inline cap). Nothing between step 1 and here holds the file in memory.
        val store = MediaAttachmentStore(File(scratch, "files"), com.oshi.messenger.data.local.atrest.AtRestKeyProvider { TEST_AT_REST_KEY })
        assertTrue(store.save("msg-9mib", tmp, moveSource = true))

        assertFalse("moveSource must consume the scratch file", tmp.exists())
        assertEquals(plain.size.toLong(), store.sizeOf("msg-9mib"))
        assertEquals("stored plaintext", SHA_9MIB, sha256(store.load("msg-9mib")!!))
    }

    /**
     * THE BOUND, asserted rather than asserted-about.
     *
     * A heap-delta assertion would be flaky under GC scheduling, so this measures the
     * property that MAKES the bound true: the streamer emits the plaintext in
     * chunk-sized pieces and never once hands the sink a buffer larger than a chunk.
     * If anyone reintroduces a whole-file `ByteArrayOutputStream` on this path, the
     * write count collapses to 1 and the largest write becomes the file size.
     */
    @Test
    fun `the decrypt emits one chunk per write and never a whole-file buffer`() {
        val plain = pattern(9 * 1024 * 1024)
        val enc = OSHICryptoV2.encryptFile(plain, "clip.mp4", "video/mp4", chunkSize = CHUNK)

        val writes = ArrayList<Int>()
        val sink = object : OutputStream() {
            override fun write(b: Int) { writes.add(1) }
            override fun write(b: ByteArray) { writes.add(b.size) }
            override fun write(b: ByteArray, off: Int, len: Int) { writes.add(len) }
        }
        val written = OSHICryptoV2Streaming.decryptToStream(
            enc.fileKey, enc.fileNonce, enc.manifest, sink, expectedChunkCount = enc.chunks.size,
        ) { i -> enc.chunks.getOrNull(i) }

        assertEquals(plain.size.toLong(), written)
        assertEquals("one write per chunk, never a single assembled buffer", enc.chunks.size, writes.size)
        assertEquals(plain.size, writes.sum())
        val largest = writes.maxOrNull() ?: 0
        assertTrue("largest write was $largest B, over the $CHUNK B chunk", largest <= CHUNK)
    }

    /**
     * The store must not keep the blob it just accepted on the heap: the caller
     * reached for the file overload precisely to avoid that. (The `ByteArray` overload
     * DOES cache — that is its contract and it is exercised by `MediaAttachmentStoreTest`.)
     */
    @Test
    fun `the file overload leaves nothing in the memory cache`() {
        val store = MediaAttachmentStore(File(scratch, "files"), com.oshi.messenger.data.local.atrest.AtRestKeyProvider { TEST_AT_REST_KEY })
        val src = File(scratch, "src.bin").apply { writeBytes(pattern(3 * 1024 * 1024)) }

        assertTrue(store.save("msg-nocache", src, moveSource = true))
        assertEquals("nothing may be retained on the heap", 0, store.cachedCount())
    }

    /** A superseded reveal layer must not survive in the LRU once the file is replaced. */
    @Test
    fun `saving a file evicts a stale cache entry for the same id`() {
        val store = MediaAttachmentStore(File(scratch, "files"), com.oshi.messenger.data.local.atrest.AtRestKeyProvider { TEST_AT_REST_KEY })
        val layer0 = pattern(1024)
        assertTrue(store.save("msg-reveal", layer0))
        assertEquals(1, store.cachedCount())
        assertArrayEquals(layer0, store.load("msg-reveal"))

        val sharper = pattern(5 * 1024 + 13)
        val src = File(scratch, "layer1.bin").apply { writeBytes(sharper) }
        assertTrue(store.save("msg-reveal", src, moveSource = true))

        assertEquals(0, store.cachedCount())
        assertEquals("the sharper layer must be what reads back", SHA_SMALL, sha256(store.load("msg-reveal")!!))
    }

    // =====================================================================
    // The file overload's own failure modes
    // =====================================================================

    @Test
    fun `without moveSource the source file is left alone and the bytes still match`() {
        val store = MediaAttachmentStore(File(scratch, "files"), com.oshi.messenger.data.local.atrest.AtRestKeyProvider { TEST_AT_REST_KEY })
        val src = File(scratch, "keep.bin").apply { writeBytes(pattern(5 * 1024 + 13)) }

        assertTrue(store.save("msg-copy", src, moveSource = false))

        assertTrue("the caller still owns its file", src.exists())
        assertEquals(SHA_SMALL, sha256(src))
        assertEquals(SHA_SMALL, sha256(store.load("msg-copy")!!))
    }

    @Test
    fun `a refused save never consumes the caller's file`() {
        val store = MediaAttachmentStore(File(scratch, "files"), com.oshi.messenger.data.local.atrest.AtRestKeyProvider { TEST_AT_REST_KEY })
        val src = File(scratch, "refused.bin").apply { writeBytes(pattern(64)) }

        assertFalse("an empty message id is not storable", store.save("", src, moveSource = true))
        assertTrue(src.exists())

        val missing = File(scratch, "does-not-exist.bin")
        assertFalse(store.save("msg-missing", missing, moveSource = true))
        assertNull(store.load("msg-missing"))
    }

    // =====================================================================
    // M-5 (download half): the un-downloaded-blob ledger survives the process
    // =====================================================================

    /**
     * A blob whose download failed used to live in an IN-MEMORY map while `seenMsgIds`
     * was persisted, so after a process death the re-pulled envelope was skipped as
     * "already seen", nothing held the seq, and the relay was told to drop the only copy
     * of the photo. The ledger has to round-trip with the key material intact or the
     * retry has nothing to ask the blob store with.
     */
    @Test
    fun `the pending-blob ledger round-trips with its key material`() {
        val a = V2FileKeyMessage(
            blobId = "blob-a", fileKey = pattern(32), fileNonce = pattern(8),
            chunkCount = 3, manifest = pattern(48), filename = "Contrat 2026.pdf",
            mime = "application/pdf", mediaType = "document", isViewOnce = true,
        )
        val b = V2FileKeyMessage(
            blobId = "blob-b", fileKey = pattern(32), fileNonce = pattern(8),
            chunkCount = 1, manifest = pattern(48), filename = "p.jpg", mime = "image/jpeg",
            revealId = revealId, revealLayerIndex = 2, revealLayerCount = 5, revealTotalMs = 60_000,
        )
        val raw = com.oshi.messenger.network.v2.V2MessageRouter.encodePendingFiles(
            linkedMapOf("id-a" to a, "id-b" to b)
        )
        val back = com.oshi.messenger.network.v2.V2MessageRouter.decodePendingFiles(raw)

        assertEquals(setOf("id-a", "id-b"), back.keys)
        assertEquals(a, back["id-a"])
        assertEquals(b, back["id-b"])
        assertArrayEquals("without the file key the retry cannot open the blob", a.fileKey, back["id-a"]!!.fileKey)
        assertEquals(5, back["id-b"]!!.revealPlan!!.layerCount)
    }

    @Test
    fun `a corrupt ledger entry is dropped, not thrown on`() {
        val good = V2FileKeyMessage(
            blobId = "blob-a", fileKey = pattern(32), fileNonce = pattern(8),
            chunkCount = 1, manifest = pattern(48), filename = "p.jpg", mime = "image/jpeg",
        )
        val raw = org.json.JSONObject()
            .put("id-good", good.toJson())
            .put("id-bad", "{\"kind\":\"v2file\",\"blobId\":\"x\"}")   // truncated
            .put("id-junk", "not json at all")
            .toString()

        val back = com.oshi.messenger.network.v2.V2MessageRouter.decodePendingFiles(raw)
        assertEquals(setOf("id-good"), back.keys)

        // Wholesale corruption is empty, never an exception on every poll after it.
        assertEquals(0, com.oshi.messenger.network.v2.V2MessageRouter.decodePendingFiles("}{").size)
        assertEquals(0, com.oshi.messenger.network.v2.V2MessageRouter.decodePendingFiles(null).size)
    }

    // =====================================================================
    // M-1: the wire plan outranks the msgId convention
    // =====================================================================

    private val revealId = "9a1f0c2e-77b4-4d51-8c33-0f9a6d2b1e40"

    private fun plan(index: Int, count: Int = 5, ms: Int = 20 * 60 * 1000, id: String = revealId) =
        V2RevealPlan.from(
            V2FileKeyMessage(
                blobId = "b", fileKey = ByteArray(32), fileNonce = ByteArray(8),
                chunkCount = 1, manifest = ByteArray(16), filename = "p.jpg", mime = "image/jpeg",
                revealId = id, revealLayerIndex = index, revealLayerCount = count, revealTotalMs = ms,
            )
        )

    @Test
    fun `layer zero inserts and later layers rewrite, straight off the wire`() {
        // The sender NAMES the row, so the msgId no longer has to carry the rank —
        // these ids are deliberately plain, with no `#k` suffix at all.
        assertNull(MessageRepository.revealLayerFor(plan(0), "any-id"))
        assertEquals(revealId to 1, MessageRepository.revealLayerFor(plan(1), "any-id"))
        assertEquals(revealId to 4, MessageRepository.revealLayerFor(plan(4), "any-id"))
    }

    @Test
    fun `the wire plan wins over a msgId that says something else`() {
        // `#2` in the id, layer 3 on the wire: the authoritative field decides, and it
        // names the row the SENDER meant — not the one the string happens to spell.
        val other = "3f8b2c11-5d47-4a90-b6e2-1c0d7a94f5b3"
        assertEquals(
            other to 3,
            MessageRepository.revealLayerFor(plan(3, id = other), "$revealId#2"),
        )
    }

    @Test
    fun `with no plan the msgId convention is still the fallback`() {
        assertEquals(revealId to 2, MessageRepository.revealLayerFor(null, "$revealId#2"))
        assertNull(MessageRepository.revealLayerFor(null, revealId))
        assertNull(MessageRepository.revealLayerFor(null, "7c2b91d4-0e55-4a18-9f60-2ad3c8b71e09"))
    }

    @Test
    fun `a half-present reveal never becomes a plan, so the fallback is what runs`() {
        // `layerCount` missing → `V2RevealPlan.from` returns null → the receiver must
        // NOT act on `revealLayerIndex` alone (a count of 1 divides by zero in the
        // countdown). It degrades to the id convention, which is the pre-fix behaviour.
        val half = V2FileKeyMessage(
            blobId = "b", fileKey = ByteArray(32), fileNonce = ByteArray(8),
            chunkCount = 1, manifest = ByteArray(16), filename = "p.jpg", mime = "image/jpeg",
            revealId = revealId, revealLayerIndex = 2, revealLayerCount = null, revealTotalMs = 60_000,
        )
        assertNull(half.revealPlan)
        assertEquals(revealId to 2, MessageRepository.revealLayerFor(half.revealPlan, "$revealId#2"))
    }
}

// __PLAINTEXT_LEFTOVERS_2026_09_24__ The store no longer writes plaintext without an
// at-rest key (there is no Keystore on the JVM), so these tests hand it a fixed one.
private val TEST_AT_REST_KEY = ByteArray(32) { (it * 13 + 7).toByte() }
