package com.oshi.messenger.atrest

import com.oshi.messenger.data.local.atrest.AtRestSubKeys
import com.oshi.messenger.data.local.atrest.ChunkedMediaCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.security.SecureRandom

class ChunkedMediaCipherTest {

    private val master = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val key = AtRestSubKeys.derive(master, AtRestSubKeys.PURPOSE_MEDIA)
    private val cs = 1024                              // small chunks ⇒ many chunks cheaply
    private val full = cs + ChunkedMediaCipher.TAG_SIZE
    private val hdr = ChunkedMediaCipher.HEADER_SIZE

    private fun bytes(n: Int, seed: Int = 3) = ByteArray(n) { ((it * 31 + seed) % 253).toByte() }

    private fun assertRejected(blob: ByteArray, what: String) {
        try {
            ChunkedMediaCipher.decrypt(key, blob)
            fail("$what was accepted")
        } catch (_: IOException) {
        }
    }

    @Test fun `round trip empty, small, exact multiple and multi-chunk`() {
        for (n in listOf(0, 1, 100, cs - 1, cs, cs + 1, 2 * cs, 5 * cs + 17)) {
            val pt = bytes(n)
            val ct = ChunkedMediaCipher.encrypt(key, pt, cs)
            assertTrue(ChunkedMediaCipher.hasMagic(ct))
            assertArrayEquals("n=$n", pt, ChunkedMediaCipher.decrypt(key, ct))
            assertEquals("plaintextLength n=$n", n.toLong(),
                ChunkedMediaCipher.plaintextLength(ct.copyOf(hdr), ct.size.toLong()))
        }
    }

    @Test fun `default chunk size multi-MB round trip`() {
        val pt = bytes(3 * 1024 * 1024 + 12345)
        val ct = ChunkedMediaCipher.encrypt(key, pt)
        assertArrayEquals(pt, ChunkedMediaCipher.decrypt(key, ct))
    }

    @Test fun `same plaintext encrypts differently each time (random nonce prefix)`() {
        val pt = bytes(3000)
        assertFalse(ChunkedMediaCipher.encrypt(key, pt, cs).contentEquals(ChunkedMediaCipher.encrypt(key, pt, cs)))
    }

    @Test fun `truncation at a chunk boundary is rejected`() {
        val ct = ChunkedMediaCipher.encrypt(key, bytes(3 * cs + 10), cs)
        assertRejected(ct.copyOf(hdr + 2 * full), "drop last chunk")
        assertRejected(ct.copyOf(hdr + full), "keep only first chunk")
        assertRejected(ct.copyOf(hdr), "header only")
    }

    @Test fun `truncation mid-chunk and appended bytes are rejected`() {
        val ct = ChunkedMediaCipher.encrypt(key, bytes(3 * cs + 10), cs)
        assertRejected(ct.copyOf(ct.size - 1), "one byte short")
        assertRejected(ct.copyOf(hdr + full + 5), "mid second chunk")
        assertRejected(ct + byteArrayOf(0), "one byte appended")
        assertRejected(ct + ct.copyOfRange(hdr, hdr + full), "chunk appended")
    }

    @Test fun `swapping or duplicating chunks is rejected`() {
        val ct = ChunkedMediaCipher.encrypt(key, bytes(3 * cs), cs)   // exactly 3 full chunks
        val c0 = ct.copyOfRange(hdr, hdr + full)
        val c1 = ct.copyOfRange(hdr + full, hdr + 2 * full)
        val c2 = ct.copyOfRange(hdr + 2 * full, hdr + 3 * full)
        val h = ct.copyOf(hdr)
        assertRejected(h + c1 + c0 + c2, "swap 0/1")
        assertRejected(h + c0 + c2 + c1, "swap last")
        assertRejected(h + c0 + c0 + c2, "duplicate")
    }

    @Test fun `splicing a chunk from another file is rejected`() {
        val a = ChunkedMediaCipher.encrypt(key, bytes(2 * cs, 1), cs)
        val b = ChunkedMediaCipher.encrypt(key, bytes(2 * cs, 2), cs)
        val spliced = a.copyOf(hdr + full) + b.copyOfRange(hdr + full, b.size)
        assertRejected(spliced, "chunk from another file")
    }

    @Test fun `any bit flip in header, body or tag is rejected`() {
        val ct = ChunkedMediaCipher.encrypt(key, bytes(2 * cs + 5), cs)
        for (pos in listOf(9, 12, 15, 19, hdr, hdr + 500, hdr + full - 1, ct.size - 1)) {
            val t = ct.copyOf()
            t[pos] = (t[pos].toInt() xor 0x01).toByte()
            assertRejected(t, "flip @$pos")
        }
    }

    @Test fun `wrong key and wrong purpose are rejected`() {
        val ct = ChunkedMediaCipher.encrypt(key, bytes(500), cs)
        val other = AtRestSubKeys.derive(ByteArray(32) { 7 }, AtRestSubKeys.PURPOSE_MEDIA)
        try { ChunkedMediaCipher.decrypt(other, ct); fail() } catch (_: IOException) {}
        val sealedKey = AtRestSubKeys.derive(master, AtRestSubKeys.PURPOSE_SEALED)
        try { ChunkedMediaCipher.decrypt(sealedKey, ct); fail() } catch (_: IOException) {}
    }

    @Test fun `a stream never hands out bytes of a chunk that fails authentication`() {
        val pt = bytes(3 * cs)
        val ct = ChunkedMediaCipher.encrypt(key, pt, cs)
        ct[hdr + 2 * full + 3] = (ct[hdr + 2 * full + 3].toInt() xor 0x80).toByte()   // corrupt chunk 2
        val out = java.io.ByteArrayOutputStream()
        try {
            ChunkedMediaCipher.decryptingStream(key, ct.inputStream()).use { it.copyTo(out, 100) }
            fail("tampered stream read to the end")
        } catch (_: IOException) {
        }
        // Only the two authenticated chunks were ever released.
        assertTrue(out.size() <= 2 * cs)
        assertArrayEquals(pt.copyOf(out.size()), out.toByteArray())
    }

    @Test fun `plaintextLength rejects impossible layouts`() {
        val ct = ChunkedMediaCipher.encrypt(key, bytes(2 * cs + 5), cs)
        val h = ct.copyOf(hdr)
        assertEquals(-1L, ChunkedMediaCipher.plaintextLength(h, hdr.toLong()))
        assertEquals(-1L, ChunkedMediaCipher.plaintextLength(h, (hdr + full + 3).toLong()))
        assertEquals(-1L, ChunkedMediaCipher.plaintextLength("NOTMAGIC".toByteArray() + ByteArray(12), 100))
    }
}
