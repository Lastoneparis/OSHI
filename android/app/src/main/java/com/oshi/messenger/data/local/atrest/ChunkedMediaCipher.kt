package com.oshi.messenger.data.local.atrest

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * `OSHIMED1` — chunked, streaming AES-256-GCM for attachments at rest.
 *
 * Videos can be hundreds of MB, so neither side ever holds more than one chunk.
 *
 * ```
 * header  = "OSHIMED1" (8) | chunkSize u32 BE (4) | noncePrefix (8, random per file)   = 20 bytes
 * chunk i = AES-GCM(key, nonce_i, aad_i, plaintext_i)  → |plaintext_i| + 16 (tag)
 *   nonce_i = noncePrefix (8) | i u32 BE (4)                                         = 12 bytes
 *   aad_i   = header (20)     | i u32 BE (4) | last (1: 0x01 on the final chunk, else 0x00)
 * ```
 *
 * Every chunk but the last carries exactly `chunkSize` plaintext bytes; the last
 * carries 0..chunkSize (an empty file is one empty final chunk, so the file always
 * ends in an authenticated "last" marker).
 *
 *  * **reordering / swapping** chunks fails: the index is in both nonce and AAD;
 *  * **truncation at a chunk boundary** fails: the new tail chunk was sealed with
 *    `last = 0` and is opened with `last = 1`;
 *  * **truncation mid-chunk / appending** fails: the tail chunk's tag breaks;
 *  * **header tampering** (chunk size, prefix) fails: the header is in every AAD;
 *  * **splicing chunks from another file** fails: different random nonce prefix,
 *    and the prefix is in the AAD.
 *
 * The random 64-bit prefix + 32-bit counter keeps (key, nonce) unique across files
 * under one key: a collision needs ~2^32 files (birthday bound), far beyond any
 * device.
 */
object ChunkedMediaCipher {

    val MAGIC: ByteArray = "OSHIMED1".toByteArray(Charsets.US_ASCII)
    const val HEADER_SIZE = 20
    const val TAG_SIZE = 16
    const val DEFAULT_CHUNK_SIZE = 64 * 1024
    const val MIN_CHUNK_SIZE = 1024
    const val MAX_CHUNK_SIZE = 8 * 1024 * 1024
    private const val NONCE_PREFIX_SIZE = 8

    private val random = SecureRandom()

    /** True when [head] (the first bytes of a file) starts with the magic. */
    fun hasMagic(head: ByteArray, len: Int = head.size): Boolean {
        if (len < MAGIC.size) return false
        for (i in MAGIC.indices) if (head[i] != MAGIC[i]) return false
        return true
    }

    /** True when [file] is in `OSHIMED1` format (only the first 8 bytes are read). */
    fun isEncryptedFile(file: File): Boolean {
        if (!file.isFile || file.length() < MAGIC.size) return false
        return try {
            file.inputStream().use { input ->
                val head = ByteArray(MAGIC.size)
                readFully(input, head, 0, head.size) == head.size && hasMagic(head)
            }
        } catch (_: IOException) {
            false
        }
    }

    // =====================================================================
    // ENCRYPT
    // =====================================================================

    /**
     * Encrypt everything [input] yields into [output]. Neither stream is closed.
     * @return the number of plaintext bytes consumed.
     */
    fun encrypt(
        key: SecretKey,
        input: InputStream,
        output: OutputStream,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
    ): Long {
        require(chunkSize in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) { "bad chunk size $chunkSize" }
        val prefix = ByteArray(NONCE_PREFIX_SIZE).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC).putInt(chunkSize).put(prefix).array()
        output.write(header)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        var cur = ByteArray(chunkSize)
        var next = ByteArray(chunkSize)
        var curLen = readFully(input, cur, 0, chunkSize)
        var index = 0L
        var total = 0L
        val out = ByteArray(chunkSize + TAG_SIZE)
        while (true) {
            // Look ahead one chunk to know whether `cur` is the last one.
            val nextLen = if (curLen == chunkSize) readFully(input, next, 0, chunkSize) else 0
            val last = nextLen == 0
            if (index > 0xFFFF_FFFFL) throw IOException("file too large for OSHIMED1")
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE * 8, nonce(prefix, index)))
                cipher.updateAAD(aad(header, index, last))
                val n = cipher.doFinal(cur, 0, curLen, out, 0)
                output.write(out, 0, n)
            } catch (e: GeneralSecurityException) {
                throw IOException("at-rest encryption failed", e)
            }
            total += curLen
            index++
            if (last) break
            val t = cur; cur = next; next = t
            curLen = nextLen
        }
        return total
    }

    /** Convenience for small buffers and tests. */
    fun encrypt(key: SecretKey, plaintext: ByteArray, chunkSize: Int = DEFAULT_CHUNK_SIZE): ByteArray {
        val bos = java.io.ByteArrayOutputStream(plaintext.size + HEADER_SIZE + TAG_SIZE * (plaintext.size / chunkSize + 1))
        encrypt(key, plaintext.inputStream(), bos, chunkSize)
        return bos.toByteArray()
    }

    // =====================================================================
    // DECRYPT
    // =====================================================================

    /**
     * A stream over the plaintext of an `OSHIMED1` file. Chunks are authenticated
     * BEFORE any of their bytes are returned; any failure surfaces as [IOException]
     * (never as silently short data). Closing it closes [source].
     */
    fun decryptingStream(key: SecretKey, source: InputStream): InputStream =
        DecryptingInputStream(key, source)

    fun decrypt(key: SecretKey, data: ByteArray): ByteArray =
        decryptingStream(key, data.inputStream()).use { it.readBytes() }

    /**
     * Plaintext size of an `OSHIMED1` file of [fileLength] bytes whose header is
     * [header] — computed from the layout alone, no decryption. -1 when the length
     * cannot be a valid file (which decryption would reject anyway).
     */
    fun plaintextLength(header: ByteArray, fileLength: Long): Long {
        if (header.size < HEADER_SIZE || !hasMagic(header)) return -1
        val chunkSize = ByteBuffer.wrap(header, MAGIC.size, 4).int
        if (chunkSize !in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) return -1
        val body = fileLength - HEADER_SIZE
        if (body < TAG_SIZE) return -1
        val full = chunkSize.toLong() + TAG_SIZE
        val chunks = (body + full - 1) / full
        val lastLen = body - (chunks - 1) * full
        if (lastLen < TAG_SIZE) return -1
        return body - chunks * TAG_SIZE
    }

    /** [plaintextLength] for a file on disk; -1 when unreadable / not OSHIMED1. */
    fun plaintextLength(file: File): Long = try {
        file.inputStream().use { input ->
            val header = ByteArray(HEADER_SIZE)
            if (readFully(input, header, 0, HEADER_SIZE) != HEADER_SIZE) -1L
            else plaintextLength(header, file.length())
        }
    } catch (_: IOException) {
        -1L
    }

    private class DecryptingInputStream(
        private val key: SecretKey,
        private val source: InputStream,
    ) : InputStream() {
        private var header: ByteArray? = null
        private var prefix = ByteArray(0)
        private var chunkSize = 0
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private var sealed = ByteArray(0)
        private var plain = ByteArray(0)
        private var plainLen = 0
        private var plainPos = 0
        private var index = 0L
        private var sawLast = false
        /** One byte read past the current chunk to learn whether it was the last. */
        private var lookahead = -1

        private fun readHeader() {
            val h = ByteArray(HEADER_SIZE)
            if (readFully(source, h, 0, HEADER_SIZE) != HEADER_SIZE || !hasMagic(h)) {
                throw IOException("not an OSHIMED1 file (bad or truncated header)")
            }
            val cs = ByteBuffer.wrap(h, MAGIC.size, 4).int
            if (cs !in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) throw IOException("OSHIMED1: bad chunk size")
            chunkSize = cs
            prefix = h.copyOfRange(MAGIC.size + 4, HEADER_SIZE)
            sealed = ByteArray(cs + TAG_SIZE)
            plain = ByteArray(cs)
            header = h
        }

        /** Loads and authenticates the next chunk. Returns false at clean end. */
        private fun nextChunk(): Boolean {
            if (header == null) readHeader()
            if (sawLast) return false
            var n = 0
            if (lookahead >= 0) { sealed[0] = lookahead.toByte(); n = 1; lookahead = -1 }
            n += readFully(source, sealed, n, sealed.size - n)
            if (n == 0) throw EOFException("OSHIMED1: truncated (no final chunk)")
            if (n < TAG_SIZE) throw IOException("OSHIMED1: truncated chunk")
            val last = if (n < sealed.size) true else {
                val b = source.read()
                if (b < 0) true else { lookahead = b; false }
            }
            if (index > 0xFFFF_FFFFL) throw IOException("OSHIMED1: too many chunks")
            try {
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE * 8, nonce(prefix, index)))
                cipher.updateAAD(aad(header!!, index, last))
                plainLen = cipher.doFinal(sealed, 0, n, plain, 0)
            } catch (e: GeneralSecurityException) {
                // Wrong key, tampered chunk, swapped chunk or truncation — all the same
                // to the caller: these bytes are not what we wrote.
                throw IOException("OSHIMED1: authentication failed at chunk $index", e)
            }
            plainPos = 0
            index++
            sawLast = last
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (plainPos >= plainLen) {
                if (!nextChunk()) return -1
            }
            val n = minOf(len, plainLen - plainPos)
            System.arraycopy(plain, plainPos, b, off, n)
            plainPos += n
            return n
        }

        override fun available(): Int = plainLen - plainPos

        override fun close() = source.close()
    }

    // =====================================================================
    // Internals
    // =====================================================================

    private fun nonce(prefix: ByteArray, index: Long): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(index.toInt()).array()

    private fun aad(header: ByteArray, index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE + 5)
            .put(header, 0, HEADER_SIZE)
            .putInt(index.toInt())
            .put(if (last) 1.toByte() else 0.toByte())
            .array()

    /** Reads until [len] bytes or EOF; returns the count read. */
    internal fun readFully(input: InputStream, b: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(b, off + total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
