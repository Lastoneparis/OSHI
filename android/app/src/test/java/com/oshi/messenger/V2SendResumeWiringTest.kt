package com.oshi.messenger

import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.service.media.V2SendResumeStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * M-4, the half `V2SendResumeStoreTest` cannot reach: what `V2MessageRouter.sendFile`
 * does with a record once it has one.
 *
 * `sendFile` itself needs the whole Hilt graph, so what is pinned here is the three
 * properties the wiring RELIES on — each one, if it were false, would turn "resume"
 * into either a silently corrupt upload or a nonce reuse:
 *
 *  1. re-encrypting with a record's key + nonce reproduces the FIRST attempt's chunks
 *     byte for byte, which is the only reason topping up the server's gaps is valid;
 *  2. the chunk-count guard in `sendFile` agrees with what `encryptFile` actually
 *     produces, so a healthy resume is never refused as a mismatch;
 *  3. when the ledger says no, the send genuinely starts over with FRESH key material —
 *     a "no resume" that quietly kept the old nonce would be the catastrophic case.
 *
 * iOS reference: `V2MessageRouter.swift:775-802` and the store at `:1290-1457`.
 */
class V2SendResumeWiringTest {

    private val scratch: File = File(
        System.getProperty("java.io.tmpdir"),
        "oshi-send-resume-${System.nanoTime()}"
    ).apply { mkdirs() }

    @After
    fun tearDown() {
        scratch.deleteRecursively()
    }

    private val peer = "cGVlcl9wdWJsaWNfa2V5X2Jhc2U2NF8zMmJ5dGVzXzAxMjM0NTY3OA=="
    private val msgId = "5d1a7f30-2b64-4e8c-a1f7-90c3e6b28d41"

    /** Deterministic, and NOT produced by anything under test. */
    private fun bytes(n: Int, salt: Int) = ByteArray(n) { i -> ((i * 17 + salt) and 0xFF).toByte() }

    /** The formula `sendFile` uses to sanity-check a record against today's bytes. */
    private fun expectedChunks(size: Int) =
        (size + OSHICryptoV2.DEFAULT_CHUNK_SIZE - 1) / OSHICryptoV2.DEFAULT_CHUNK_SIZE

    @Test
    fun `a resumed encryption reproduces the first attempt byte for byte`() {
        val data = bytes(5 * 1024 + 7, 3)
        val first = OSHICryptoV2.encryptFile(data, "clip.mp4", "video/mp4", chunkSize = 2048)

        val store = V2SendResumeStore(scratch)
        store.put(
            msgId = msgId,
            recipientKey = peer,
            plaintextSha256 = V2SendResumeStore.digest(data),
            blobId = "blob-1",
            fileKey = first.fileKey,
            fileNonce = first.fileNonce,
            chunkCount = first.chunks.size,
        )

        val record = store.record(msgId, peer, V2SendResumeStore.digest(data))
        assertNotNull("the same bytes under the same id must resume", record)

        val second = OSHICryptoV2.encryptFile(
            data, "clip.mp4", "video/mp4", chunkSize = 2048,
            reuseFileKey = record!!.fileKey, reuseFileNonce = record.fileNonce,
        )

        assertEquals(first.chunks.size, second.chunks.size)
        for (i in first.chunks.indices) {
            assertArrayEquals("chunk $i must be identical or the server's copy is garbage",
                first.chunks[i], second.chunks[i])
        }
        assertArrayEquals(first.manifest, second.manifest)
        assertEquals("blob-1", record.blobId)
    }

    @Test
    fun `the chunk-count guard agrees with what encryptFile produces`() {
        // Boundaries first: one byte, exactly one chunk, one byte over, two chunks.
        val sizes = intArrayOf(
            1,
            OSHICryptoV2.DEFAULT_CHUNK_SIZE - 1,
            OSHICryptoV2.DEFAULT_CHUNK_SIZE,
            OSHICryptoV2.DEFAULT_CHUNK_SIZE + 1,
            2 * OSHICryptoV2.DEFAULT_CHUNK_SIZE,
        )
        for (size in sizes) {
            val enc = OSHICryptoV2.encryptFile(bytes(size, 11), "f.bin", "application/octet-stream")
            assertEquals("size=$size", enc.chunks.size, expectedChunks(size))
        }
    }

    @Test
    fun `different bytes under the same send id yield a fresh key, never the old nonce`() {
        val original = bytes(4096, 1)
        val edited = bytes(4096, 2)                       // same id, same length, other bytes
        val first = OSHICryptoV2.encryptFile(original, "p.jpg", "image/jpeg", chunkSize = 2048)

        val store = V2SendResumeStore(scratch)
        store.put(
            msgId = msgId, recipientKey = peer,
            plaintextSha256 = V2SendResumeStore.digest(original),
            blobId = "blob-1", fileKey = first.fileKey, fileNonce = first.fileNonce,
            chunkCount = first.chunks.size,
        )

        val record = store.record(msgId, peer, V2SendResumeStore.digest(edited))
        assertNull("key material must never meet a different plaintext", record)

        // What `sendFile` then does: no record ⇒ no reuse arguments ⇒ fresh material.
        val second = OSHICryptoV2.encryptFile(edited, "p.jpg", "image/jpeg", chunkSize = 2048)
        assertFalse("a fresh send must not reuse the recorded nonce",
            second.fileNonce.contentEquals(first.fileNonce))
        assertFalse(second.fileKey.contentEquals(first.fileKey))

        // And the poisoned record is gone, so a later honest retry starts clean.
        assertNull(store.record(msgId, peer, V2SendResumeStore.digest(original)))
    }

    @Test
    fun `a record whose chunk count no longer matches is not resumed`() {
        // A chunkSize change between builds would leave a record addressing a blob with
        // a different shape; `sendFile` drops it rather than top up the wrong gaps.
        val data = bytes(6000, 5)
        val enc = OSHICryptoV2.encryptFile(data, "f.bin", "application/octet-stream")

        val store = V2SendResumeStore(scratch)
        store.put(
            msgId = msgId, recipientKey = peer,
            plaintextSha256 = V2SendResumeStore.digest(data),
            blobId = "blob-1", fileKey = enc.fileKey, fileNonce = enc.fileNonce,
            chunkCount = enc.chunks.size + 1,             // stale shape
        )

        val record = store.record(msgId, peer, V2SendResumeStore.digest(data))
        assertNotNull("the digest still matches, so the STORE hands it over", record)
        assertNull("but sendFile's guard must refuse it",
            record!!.takeIf { it.chunkCount == expectedChunks(data.size) })
    }

    /** __MEDIA_PADDING_2026_11_10__ padded sends: the guard is computed on the PADDED size. */
    @Test
    fun `the padded chunk-count guard agrees with what encryptFile produces`() {
        val sizes = intArrayOf(1, 4096, 4097, OSHICryptoV2.DEFAULT_CHUNK_SIZE - 1, OSHICryptoV2.DEFAULT_CHUNK_SIZE,
            OSHICryptoV2.DEFAULT_CHUNK_SIZE + 1, 2 * OSHICryptoV2.DEFAULT_CHUNK_SIZE + 5)
        for (size in sizes) {
            val enc = OSHICryptoV2.encryptFile(bytes(size, 13), "f.bin", "application/octet-stream", pad = true)
            assertEquals("size=$size", OSHICryptoV2.mediaChunkCount(size.toLong(), padded = true), enc.chunks.size)
            assertEquals("size=$size", expectedChunks(size), OSHICryptoV2.mediaChunkCount(size.toLong(), padded = false))
        }
        // Small chunk sizes make the padded and unpadded counts diverge (11 B pads to 4 096 B).
        assertEquals(2, OSHICryptoV2.mediaChunkCount(4097, padded = false, chunkSize = 4096))
        assertEquals(2, OSHICryptoV2.mediaChunkCount(4097, padded = true, chunkSize = 4096))
        assertEquals(1, OSHICryptoV2.mediaChunkCount(11, padded = true, chunkSize = 4096))
        assertEquals(2, OSHICryptoV2.mediaChunkCount(11, padded = true, chunkSize = 2048))
    }

    /** A padded resume reproduces the first attempt byte for byte (same key, nonce, pad). */
    @Test
    fun `a padded resumed encryption reproduces the first attempt`() {
        val data = bytes(5000, 5)
        val first = OSHICryptoV2.encryptFile(data, "a.jpg", "image/jpeg", chunkSize = 2048, pad = true)
        val second = OSHICryptoV2.encryptFile(data, "a.jpg", "image/jpeg", chunkSize = 2048,
            reuseFileKey = first.fileKey, reuseFileNonce = first.fileNonce, pad = true)
        assertEquals(first.chunks.size, second.chunks.size)
        for (i in first.chunks.indices) assertArrayEquals(first.chunks[i], second.chunks[i])
        assertArrayEquals(first.manifest, second.manifest)
    }
}
