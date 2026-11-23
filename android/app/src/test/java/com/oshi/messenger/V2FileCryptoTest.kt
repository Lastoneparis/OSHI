package com.oshi.messenger

import com.oshi.messenger.network.v2.OSHICryptoV2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Chunked per-file AEAD parity with iOS `OSHICryptoV2.encryptFile` / `decryptFile`
 * (OSHICryptoV2.swift:485-611). This is what the /v2/blobs bytes actually are, so a
 * divergence here means an iPhone photo downloads and then fails to open.
 *
 *   nonce_i  = fileNonce(8) ‖ u32be(i)
 *   chunk_i  = AES-256-GCM(fileKey, nonce_i, slice, aad = EMPTY) = ct‖tag
 *   manifest = AES-256-GCM(fileKey, nonce_0xFFFFFFFF, manifestJSON, aad = EMPTY)
 */
class V2FileCryptoTest {

    private fun hexStr(d: ByteArray) = d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `manifest JSON matches the exact Node JSON stringify layout iOS reproduces`() {
        // iOS OSHICryptoV2.swift:503-508 — EXACT key order, no whitespace.
        val json = OSHICryptoV2.manifestJson("photo.jpg", "image/jpeg", 1234, 1, 2 * 1024 * 1024)
        assertEquals(
            """{"filename":"photo.jpg","mime":"image/jpeg","size":1234,"chunkCount":1,"chunkSize":2097152}""",
            String(json, Charsets.UTF_8),
        )
    }

    @Test
    fun `manifest JSON escapes like Node JSON stringify`() {
        val json = OSHICryptoV2.manifestJson("a\"b\\c\nd\te", "text/plain", 1, 1, 16)
        assertEquals(
            """{"filename":"a\"b\\c\nd\te","mime":"text/plain","size":1,"chunkCount":1,"chunkSize":16}""",
            String(json, Charsets.UTF_8),
        )
    }

    @Test
    fun `chunk nonce is fileNonce concat u32be counter and the manifest uses 0xFFFFFFFF`() {
        val fileNonce = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        assertEquals("000102030405060700000000", hexStr(OSHICryptoV2.chunkNonce(fileNonce, 0)))
        assertEquals("000102030405060700000101", hexStr(OSHICryptoV2.chunkNonce(fileNonce, 257)))
        assertEquals(
            "0001020304050607ffffffff",
            hexStr(OSHICryptoV2.chunkNonce(fileNonce, OSHICryptoV2.MANIFEST_COUNTER)),
        )
        assertEquals(12, OSHICryptoV2.chunkNonce(fileNonce, 0).size)
    }

    @Test
    fun `single chunk file round-trips`() {
        val bytes = Random(7).nextBytes(50_000)
        val enc = OSHICryptoV2.encryptFile(bytes, "photo.jpg", "image/jpeg")
        assertEquals(1, enc.chunks.size)
        assertEquals(32, enc.fileKey.size)
        assertEquals(8, enc.fileNonce.size)
        // ct‖tag = plaintext + the 16-byte GCM tag.
        assertEquals(bytes.size + 16, enc.chunks[0].size)
        val out = OSHICryptoV2.decryptFile(enc.fileKey, enc.fileNonce, enc.chunks, enc.manifest)
        assertArrayEquals(bytes, out)
    }

    @Test
    fun `multi chunk file round-trips with the per-index nonce`() {
        // 5 chunks + a partial one, at a small chunk size so the test stays fast.
        val chunkSize = 4096
        val bytes = Random(11).nextBytes(chunkSize * 5 + 123)
        val enc = OSHICryptoV2.encryptFile(bytes, "clip.mp4", "video/mp4", chunkSize = chunkSize)
        assertEquals(6, enc.chunks.size)
        assertEquals(
            """{"filename":"clip.mp4","mime":"video/mp4","size":${bytes.size},"chunkCount":6,"chunkSize":$chunkSize}""",
            String(enc.infoJson, Charsets.UTF_8),
        )
        // Each chunk must open under ITS OWN derived nonce, never a shared one.
        for (i in enc.chunks.indices) {
            val plain = OSHICryptoV2.aesGcmOpen(
                enc.fileKey, OSHICryptoV2.chunkNonce(enc.fileNonce, i.toLong()), enc.chunks[i], ByteArray(0)
            )
            val expectedLen = if (i == 5) 123 else chunkSize
            assertEquals("chunk $i length", expectedLen, plain.size)
        }
        assertArrayEquals(bytes, OSHICryptoV2.decryptFile(enc.fileKey, enc.fileNonce, enc.chunks, enc.manifest))
    }

    @Test
    fun `manifest opens under the reserved counter and describes the file`() {
        val bytes = Random(3).nextBytes(100)
        val enc = OSHICryptoV2.encryptFile(bytes, "note.txt", "text/plain", chunkSize = 64)
        val info = OSHICryptoV2.aesGcmOpen(
            enc.fileKey,
            OSHICryptoV2.chunkNonce(enc.fileNonce, OSHICryptoV2.MANIFEST_COUNTER),
            enc.manifest,
            ByteArray(0),
        )
        assertEquals(
            """{"filename":"note.txt","mime":"text/plain","size":100,"chunkCount":2,"chunkSize":64}""",
            String(info, Charsets.UTF_8),
        )
    }

    @Test
    fun `reusing the key material reproduces byte-identical ciphertext`() {
        // This determinism is what makes an interrupted upload resumable into the
        // SAME server-side blob (iOS OSHICryptoV2.swift:534-544).
        val bytes = Random(5).nextBytes(9000)
        val a = OSHICryptoV2.encryptFile(bytes, "f.bin", "application/octet-stream", chunkSize = 4096)
        val b = OSHICryptoV2.encryptFile(
            bytes, "f.bin", "application/octet-stream", chunkSize = 4096,
            reuseFileKey = a.fileKey, reuseFileNonce = a.fileNonce,
        )
        assertEquals(a.chunks.size, b.chunks.size)
        for (i in a.chunks.indices) assertArrayEquals("chunk $i", a.chunks[i], b.chunks[i])
        assertArrayEquals(a.manifest, b.manifest)

        // A FRESH key must not reproduce them (guards against a broken RNG path).
        val c = OSHICryptoV2.encryptFile(bytes, "f.bin", "application/octet-stream", chunkSize = 4096)
        assertNotEquals(hexStr(a.chunks[0]), hexStr(c.chunks[0]))
    }

    @Test
    fun `a truncated download is rejected instead of yielding corrupt media`() {
        val bytes = Random(13).nextBytes(9000)
        val enc = OSHICryptoV2.encryptFile(bytes, "f.bin", "application/octet-stream", chunkSize = 4096)
        assertEquals(3, enc.chunks.size)
        try {
            OSHICryptoV2.decryptFile(enc.fileKey, enc.fileNonce, enc.chunks.dropLast(1), enc.manifest)
            fail("a missing chunk must not decrypt")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("chunkCount"))
        }
    }

    @Test
    fun `reordered chunks fail the AEAD rather than scrambling the file`() {
        val bytes = Random(17).nextBytes(9000)
        val enc = OSHICryptoV2.encryptFile(bytes, "f.bin", "application/octet-stream", chunkSize = 4096)
        val swapped = listOf(enc.chunks[1], enc.chunks[0], enc.chunks[2])
        try {
            OSHICryptoV2.decryptFile(enc.fileKey, enc.fileNonce, swapped, enc.manifest)
            fail("out-of-order chunks must not decrypt")
        } catch (e: Exception) {
            assertTrue(e is javax.crypto.AEADBadTagException || e is IllegalArgumentException)
        }
    }

    @Test
    fun `half-reused key material is refused`() {
        try {
            OSHICryptoV2.encryptFile(ByteArray(10), "f", "m", reuseFileKey = ByteArray(32))
            fail("a reused key with a fresh nonce is a fatal GCM misuse")
        } catch (_: IllegalArgumentException) {
        }
    }
}
