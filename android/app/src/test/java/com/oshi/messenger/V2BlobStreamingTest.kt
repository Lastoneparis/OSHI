package com.oshi.messenger

import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.OSHICryptoV2Streaming
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

/**
 * The streaming download path (M-2). [OSHICryptoV2.decryptFile] materialises every
 * ciphertext chunk AND the whole plaintext; [OSHICryptoV2Streaming.decryptToStream]
 * opens one chunk at a time. These tests exist to prove the two produce EXACTLY the
 * same bytes — a divergence here means an iPhone photo downloads and then fails to
 * open — and that nothing accumulates per chunk.
 *
 * iOS reference: `OSHICryptoV2.swift:693-760` ("peak memory ≈ chunkSize, independent
 * of file size"), driven from `MessageManager+V2.swift:715-722`.
 */
class V2BlobStreamingTest {

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** A source that hands back chunks from a list and records the access pattern. */
    private class RecordingSource(private val chunks: List<ByteArray>) : OSHICryptoV2Streaming.ChunkSource {
        val requested = ArrayList<Int>()
        override fun chunk(index: Int): ByteArray? {
            requested.add(index)
            return chunks.getOrNull(index)
        }
    }

    @Test
    fun `a multi-chunk payload streams to exactly what decryptFile returns`() {
        val plain = Random(7).nextBytes(9 * 1024 + 13)     // 5 chunks at 2 KiB, last partial
        val enc = OSHICryptoV2.encryptFile(plain, "clip.mp4", "video/mp4", chunkSize = 2048)
        assertEquals(5, enc.chunks.size)

        val inMemory = OSHICryptoV2.decryptFile(enc.fileKey, enc.fileNonce, enc.chunks, enc.manifest)

        val sink = ByteArrayOutputStream()
        val written = OSHICryptoV2Streaming.decryptToStream(
            enc.fileKey, enc.fileNonce, enc.manifest, sink, expectedChunkCount = enc.chunks.size,
        ) { i -> enc.chunks.getOrNull(i) }

        assertEquals(plain.size.toLong(), written)
        assertArrayEquals("streaming must equal the in-memory result", inMemory, sink.toByteArray())
        assertArrayEquals(plain, sink.toByteArray())
    }

    @Test
    fun `a single-chunk payload and an empty-tail payload both round-trip`() {
        for (size in intArrayOf(1, 2048, 2049, 4096)) {
            val plain = Random(size).nextBytes(size)
            val enc = OSHICryptoV2.encryptFile(plain, "f.bin", "application/octet-stream", chunkSize = 2048)
            val sink = ByteArrayOutputStream()
            OSHICryptoV2Streaming.decryptToStream(
                enc.fileKey, enc.fileNonce, enc.manifest, sink, expectedChunkCount = enc.chunks.size,
            ) { i -> enc.chunks.getOrNull(i) }
            assertArrayEquals("size=$size", plain, sink.toByteArray())
        }
    }

    /**
     * The honest version of "peak allocation is bounded": run a payload far larger than
     * a single chunk through the streamer and assert the OUTPUT HASH, while proving the
     * loop asks for each chunk exactly once, in order, and never revisits one — i.e. it
     * has no reason to hold on to any of them. (A JVM heap-delta assertion would be
     * flaky under GC scheduling; this asserts the property that makes the bound true.)
     */
    @Test
    fun `a multi-chunk payload at the real 2 MiB chunk size streams with a matching hash`() {
        val chunkSize = 2 * 1024 * 1024                 // the real /v2/blobs chunk size
        val plain = Random(1234).nextBytes(9 * 1024 * 1024)
        val enc = OSHICryptoV2.encryptFile(plain, "big.mp4", "video/mp4", chunkSize = chunkSize)
        assertEquals(5, enc.chunks.size)

        val source = RecordingSource(enc.chunks)
        val sink = ByteArrayOutputStream(plain.size)
        val written = OSHICryptoV2Streaming.decryptToStream(
            enc.fileKey, enc.fileNonce, enc.manifest, sink, expectedChunkCount = 5, source = source,
        )

        assertEquals(plain.size.toLong(), written)
        assertEquals(sha256(plain), sha256(sink.toByteArray()))
        assertEquals("each chunk fetched exactly once, in order", (0 until 5).toList(), source.requested)
        // Every plaintext chunk is at most one chunkSize, so the loop's live set is one
        // ciphertext chunk + its plaintext, whatever the file size.
        assertTrue(enc.chunks.all { it.size <= chunkSize + 16 })
    }

    @Test
    fun `an unavailable chunk raises IOException, not a corruption error`() {
        val plain = Random(3).nextBytes(5000)
        val enc = OSHICryptoV2.encryptFile(plain, "f.bin", "application/octet-stream", chunkSize = 1024)
        try {
            OSHICryptoV2Streaming.decryptToStream(
                enc.fileKey, enc.fileNonce, enc.manifest, ByteArrayOutputStream(),
            ) { i -> if (i == 2) null else enc.chunks[i] }
            fail("a missing chunk must not decode")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("chunk 2"))
        }
    }

    @Test
    fun `a tampered chunk fails the AEAD instead of yielding corrupt media`() {
        val plain = Random(4).nextBytes(5000)
        val enc = OSHICryptoV2.encryptFile(plain, "f.bin", "application/octet-stream", chunkSize = 1024)
        val tampered = enc.chunks.map { it.copyOf() }
        tampered[1][0] = (tampered[1][0].toInt() xor 0x01).toByte()
        try {
            OSHICryptoV2Streaming.decryptToStream(
                enc.fileKey, enc.fileNonce, enc.manifest, ByteArrayOutputStream(),
            ) { i -> tampered[i] }
            fail("a flipped ciphertext bit must not decode")
        } catch (e: Exception) {
            assertTrue(e is javax.crypto.AEADBadTagException || e is java.security.GeneralSecurityException)
        }
    }

    @Test
    fun `chunks served out of order decode to garbage-or-throw, never silently`() {
        // The per-chunk nonce is fileNonce || u32be(i), so serving chunk j at index i
        // cannot authenticate. Order is load-bearing exactly as in downloadBlob.
        val plain = Random(5).nextBytes(4096)
        val enc = OSHICryptoV2.encryptFile(plain, "f.bin", "application/octet-stream", chunkSize = 1024)
        try {
            OSHICryptoV2Streaming.decryptToStream(
                enc.fileKey, enc.fileNonce, enc.manifest, ByteArrayOutputStream(),
            ) { i -> enc.chunks[enc.chunks.size - 1 - i] }
            fail("a scrambled reassembly must not decode")
        } catch (e: Exception) {
            assertTrue(e is javax.crypto.AEADBadTagException || e is java.security.GeneralSecurityException)
        }
    }

    @Test
    fun `a chunkCount that disagrees with the manifest is refused`() {
        val plain = Random(6).nextBytes(4096)
        val enc = OSHICryptoV2.encryptFile(plain, "f.bin", "application/octet-stream", chunkSize = 1024)
        try {
            OSHICryptoV2Streaming.decryptToStream(
                enc.fileKey, enc.fileNonce, enc.manifest, ByteArrayOutputStream(),
                expectedChunkCount = 3,
            ) { i -> enc.chunks.getOrNull(i) }
            fail("the announced chunkCount must be checked against the manifest")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("chunkCount"))
        }
    }

    @Test
    fun `the manifest is readable before a single chunk is fetched`() {
        // This is what lets the caller refuse an oversized blob BEFORE allocating.
        val plain = Random(8).nextBytes(3 * 1024)
        val enc = OSHICryptoV2.encryptFile(plain, "photo.jpg", "image/jpeg", chunkSize = 1024)
        val info = OSHICryptoV2Streaming.openManifest(enc.fileKey, enc.fileNonce, enc.manifest)
        assertEquals("photo.jpg", info.filename)
        assertEquals("image/jpeg", info.mime)
        assertEquals(3 * 1024L, info.size)
        assertEquals(3, info.chunkCount)
        assertEquals(1024, info.chunkSize)
    }
}
