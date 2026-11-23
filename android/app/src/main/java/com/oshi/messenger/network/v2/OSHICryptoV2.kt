package com.oshi.messenger.network.v2

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * OSHI V2 crypto core — X3DH + Double Ratchet + file-chunk AEAD, byte-identical
 * to iOS `OSHICryptoV2.swift`. Kept as pure JVM (BouncyCastle X25519 + javax.crypto
 * GCM/HMAC + BC HKDF, no Android APIs) so its parity is verified on the JVM against
 * the same known-answer vectors iOS ships (OSHICryptoV2Tests.swift / test_vectors.json).
 *
 * Contracts (iOS OSHICryptoV2.swift):
 *   X3DH   SK = HKDF(ikm = 0xFF*32 ‖ DH1‖DH2‖DH3[‖DH4], salt=0*32, info="OSHI_X3DH", 32)
 *          DH1=DH(IK_a,SPK_b) DH2=DH(EK_a,IK_b) DH3=DH(EK_a,SPK_b) DH4=DH(EK_a,OPK_b)
 *   KDF_RK(rk,dh) = HKDF(ikm=dh, salt=rk, info="OSHI_DR_ROOT", 64) -> (rk'=0:32, ck=32:64)
 *   KDF_CK(ck)    = mk=HMAC(ck,0x01), ck'=HMAC(ck,0x02)
 *   MSG(mk)       = HKDF(ikm=mk, salt=0*32, info="OSHI_DR_MSG", 44) -> key=0:32 nonce=32:44
 *   header wire   = dh(32) ‖ pn(u32be) ‖ n(u32be)
 *   chunkNonce    = fileNonce(8) ‖ u32be(counter)  -> 12 bytes
 *   AEAD          = AES-256-GCM, 12-byte nonce, output = ciphertext ‖ tag(16)
 */
object OSHICryptoV2 {

    const val GCM_NONCE_LEN = 12
    private val ZERO32 = ByteArray(32)
    private val X3DH_F = ByteArray(32) { 0xFF.toByte() }
    private val X3DH_INFO = "OSHI_X3DH".toByteArray(Charsets.UTF_8)
    private val DR_ROOT_INFO = "OSHI_DR_ROOT".toByteArray(Charsets.UTF_8)
    private val DR_MSG_INFO = "OSHI_DR_MSG".toByteArray(Charsets.UTF_8)

    data class X25519Pair(val priv: ByteArray, val pub: ByteArray)
    data class Header(val dh: ByteArray, val pn: Long, val n: Long)

    // ---- Primitives ---------------------------------------------------------

    fun generateX25519(): X25519Pair {
        val priv = X25519PrivateKeyParameters(SecureRandom())
        return X25519Pair(priv.encoded, priv.generatePublicKey().encoded)
    }

    /** Derive the X25519 public key from a raw private key. */
    fun x25519PubFromPriv(priv: ByteArray): ByteArray =
        X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded

    /** Raw X25519 shared secret (no hashing), matching CryptoKit sharedSecret bytes. */
    fun dh(minePriv: ByteArray, theirPub: ByteArray): ByteArray {
        val agree = X25519Agreement()
        agree.init(X25519PrivateKeyParameters(minePriv, 0))
        val out = ByteArray(agree.agreementSize)
        agree.calculateAgreement(X25519PublicKeyParameters(theirPub, 0), out, 0)
        return out
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        gen.generateBytes(out, 0, length)
        return out
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** AES-256-GCM seal → ciphertext ‖ tag(16). */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** AES-256-GCM open of ciphertext ‖ tag(16). */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ctAndTag: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ctAndTag)
    }

    fun u32be(n: Long): ByteArray = byteArrayOf(
        ((n ushr 24) and 0xFF).toByte(),
        ((n ushr 16) and 0xFF).toByte(),
        ((n ushr 8) and 0xFF).toByte(),
        (n and 0xFF).toByte(),
    )

    // ---- X3DH ---------------------------------------------------------------

    fun x3dhDeriveSK(dhs: List<ByteArray>): ByteArray {
        var ikm = X3DH_F
        for (d in dhs) ikm += d
        return hkdf(ikm, ZERO32, X3DH_INFO, 32)
    }

    /** Alice (initiator). theirOpkPub null → DH4 omitted. */
    fun x3dhInitiator(
        ik: X25519Pair, ek: X25519Pair,
        theirIkPub: ByteArray, theirSpkPub: ByteArray, theirOpkPub: ByteArray?,
    ): ByteArray {
        val dhs = mutableListOf(
            dh(ik.priv, theirSpkPub),  // DH1
            dh(ek.priv, theirIkPub),   // DH2
            dh(ek.priv, theirSpkPub),  // DH3
        )
        if (theirOpkPub != null) dhs.add(dh(ek.priv, theirOpkPub)) // DH4
        return x3dhDeriveSK(dhs)
    }

    /** Bob (responder) — recomputes the identical SK. opk null must match initiator. */
    fun x3dhResponder(
        ik: X25519Pair, spk: X25519Pair, opk: X25519Pair?,
        theirIkPub: ByteArray, theirEkPub: ByteArray,
    ): ByteArray {
        val dhs = mutableListOf(
            dh(spk.priv, theirIkPub),
            dh(ik.priv, theirEkPub),
            dh(spk.priv, theirEkPub),
        )
        if (opk != null) dhs.add(dh(opk.priv, theirEkPub))
        return x3dhDeriveSK(dhs)
    }

    // ---- Double Ratchet KDFs ------------------------------------------------

    /** KDF_RK → (newRootKey, chainKey). */
    fun kdfRK(rk: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val out = hkdf(dhOut, rk, DR_ROOT_INFO, 64)
        return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
    }

    /** KDF_CK → (nextChainKey, messageKey). */
    fun kdfCK(ck: ByteArray): Pair<ByteArray, ByteArray> {
        val mk = hmac(ck, byteArrayOf(0x01))
        val nextCK = hmac(ck, byteArrayOf(0x02))
        return nextCK to mk
    }

    /** Message key/nonce from a message key → (key32, nonce12). */
    fun deriveMsgKey(mk: ByteArray): Pair<ByteArray, ByteArray> {
        val out = hkdf(mk, ZERO32, DR_MSG_INFO, GCM_NONCE_LEN + 32)
        return out.copyOfRange(0, 32) to out.copyOfRange(32, 32 + GCM_NONCE_LEN)
    }

    /** Deterministic header wire encoding: dh(32) ‖ pn(u32be) ‖ n(u32be). */
    fun encodeHeader(h: Header): ByteArray = h.dh + u32be(h.pn) + u32be(h.n)

    /** Inverse of [encodeHeader] — parse a 40-byte ratchet header off the wire. */
    fun decodeHeader(bytes: ByteArray): Header {
        require(bytes.size >= 40) { "ratchet header must be 40 bytes" }
        val dh = bytes.copyOfRange(0, 32)
        fun u32(off: Int): Long =
            ((bytes[off].toLong() and 0xFF) shl 24) or
            ((bytes[off + 1].toLong() and 0xFF) shl 16) or
            ((bytes[off + 2].toLong() and 0xFF) shl 8) or
            (bytes[off + 3].toLong() and 0xFF)
        return Header(dh = dh, pn = u32(32), n = u32(36))
    }

    // ---- File chunk AEAD ----------------------------------------------------

    /** Per-chunk nonce = fileNonce(8) ‖ u32be(counter) = 12 bytes. */
    fun chunkNonce(fileNonce: ByteArray, counter: Long): ByteArray = fileNonce + u32be(counter)

    /**
     * The manifest rides under a reserved counter, NOT a chunk index
     * (iOS OSHICryptoV2.swift:493 `manifestCounter`).
     */
    const val MANIFEST_COUNTER: Long = 0xffffffffL

    /** Default plaintext chunk size — iOS OSHICryptoV2.swift:546. */
    const val DEFAULT_CHUNK_SIZE: Int = 2 * 1024 * 1024

    /** Result of [encryptFile]: the blob's opaque chunks + the sealed manifest. */
    data class EncryptedFile(
        val fileKey: ByteArray,
        val fileNonce: ByteArray,
        val chunks: List<ByteArray>,   // each = ct‖tag, in order
        val manifest: ByteArray,       // ct‖tag
        val infoJson: ByteArray,       // plaintext manifest JSON (caller convenience)
    )

    /**
     * Minimal JSON string escaping matching Node `JSON.stringify` for the cases a
     * filename/mime can hit — iOS OSHICryptoV2.swift:512-532 (`jsonEscape`).
     * Load-bearing: the manifest bytes are AEAD plaintext, so a divergence here
     * changes nothing on the wire but a divergence in the *reader* would.
     */
    fun jsonEscape(s: String): String {
        val out = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * Compact manifest JSON reproducing Node `JSON.stringify` key ordering +
     * formatting — iOS OSHICryptoV2.swift:503-508. EXACT key order, no whitespace:
     * {"filename":…,"mime":…,"size":N,"chunkCount":N,"chunkSize":N}
     */
    fun manifestJson(filename: String, mime: String, size: Int, chunkCount: Int, chunkSize: Int, paddedSize: Int? = null): ByteArray =
        ("{\"filename\":\"${jsonEscape(filename)}\",\"mime\":\"${jsonEscape(mime)}\"," +
            "\"size\":$size,\"chunkCount\":$chunkCount,\"chunkSize\":$chunkSize" +
            // __MEDIA_PADDING_2026_11_10__ ONE extra key, appended LAST, only for a padded file:
            // an unpadded manifest stays byte-identical to every earlier build's.
            (if (paddedSize != null) ",\"paddedSize\":$paddedSize" else "") + "}")
            .toByteArray(Charsets.UTF_8)

    // ---- __MEDIA_PADDING_2026_11_10__ (docs/MEDIA_PADDING_AND_OHTTP_MEDIA_SPEC.md §2) ----

    /** Floor of the padded size: every file up to 4 KiB looks the same. */
    const val MEDIA_PAD_MIN: Long = 4096L

    /** The 200 MiB plaintext ceiling (iOS `V2.maxPlaintextBytes`): padding never crosses it. */
    const val MEDIA_PAD_MAX: Long = 200L * 1024 * 1024

    /**
     * Padmé (PURBs paper): leaks O(log log L) bits of the length, overhead ≤ ~12 %, typically
     * 1–6 %. 4 KiB floor; never above [MEDIA_PAD_MAX] for an L that fits under it; never
     * shrinks. Pinned by the spec's vectors on Android, desktop and iOS.
     */
    fun mediaPaddedSize(length: Long): Long {
        require(length >= 0) { "negative length" }
        if (length <= MEDIA_PAD_MIN) return MEDIA_PAD_MIN
        val e = 63 - java.lang.Long.numberOfLeadingZeros(length)          // floor(log2 L)
        val s = 64 - java.lang.Long.numberOfLeadingZeros(e.toLong())      // bit length of E
        val mask = (1L shl (e - s)) - 1
        val p = (length + mask) and mask.inv()
        return if (length <= MEDIA_PAD_MAX) minOf(p, MEDIA_PAD_MAX) else length
    }

    /** Chunks a file of [plaintextLength] bytes is sealed into, padded or not. */
    fun mediaChunkCount(plaintextLength: Long, padded: Boolean, chunkSize: Int = DEFAULT_CHUNK_SIZE): Int {
        val total = if (padded) mediaPaddedSize(plaintextLength) else plaintextLength
        return ((total + chunkSize - 1) / chunkSize).toInt()
    }

    /**
     * Chunked per-file AEAD — iOS OSHICryptoV2.swift:545-582 (`encryptFile`).
     *   nonce_i  = fileNonce(8) ‖ u32be(i)
     *   chunk_i  = AES-256-GCM(fileKey, nonce_i, slice, aad = EMPTY) = ct‖tag
     *   manifest = AES-256-GCM(fileKey, nonce_0xFFFFFFFF, manifestJSON, aad = EMPTY)
     *
     * `reuseFileKey`/`reuseFileNonce` (both-or-neither) reproduce a previous
     * ciphertext byte-for-byte so an interrupted upload can resume into the same
     * server-side blob. NEVER pass them back with different plaintext: GCM
     * (key, nonce) reuse across distinct plaintexts is fatal.
     */
    fun encryptFile(
        bytes: ByteArray,
        filename: String,
        mime: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        reuseFileKey: ByteArray? = null,
        reuseFileNonce: ByteArray? = null,
        /**
         * __MEDIA_PADDING_2026_11_10__ Seal `bytes ‖ zeros(P − L)` with P = [mediaPaddedSize]
         * and announce P as `paddedSize`. ONLY when every recipient device reads padded media
         * (caps bit [V2Padding.CAP_MEDIA_PADDING]); an older reader rejects the file. False ⇒
         * byte-identical to every earlier build. A resume MUST pass the same value it was
         * reserved with: the same key/nonce over a different tail is GCM nonce reuse.
         */
        pad: Boolean = false,
    ): EncryptedFile {
        require(chunkSize > 0) { "chunkSize must be > 0" }
        require((reuseFileKey == null) == (reuseFileNonce == null)) { "reuse key/nonce is both-or-neither" }
        val rnd = SecureRandom()
        val fileKey = reuseFileKey ?: ByteArray(32).also { rnd.nextBytes(it) }
        val fileNonce = reuseFileNonce ?: ByteArray(8).also { rnd.nextBytes(it) }

        // The AEAD sees a virtual plaintext of [total] bytes: the file, then zeros. Built one
        // chunk at a time so a 200 MB video never gets a second full-size copy.
        val paddedSize: Int? = if (pad) mediaPaddedSize(bytes.size.toLong()).toInt() else null
        val total = paddedSize ?: bytes.size
        val chunks = ArrayList<ByteArray>(((total.toLong() + chunkSize - 1) / chunkSize).toInt())
        var off = 0L
        var i = 0L
        while (off < total) {
            val end = minOf(off + chunkSize, total.toLong())
            val slice = if (end <= bytes.size) bytes.copyOfRange(off.toInt(), end.toInt()) else {
                ByteArray((end - off).toInt()).also { buf ->              // zero-filled tail
                    if (off < bytes.size) System.arraycopy(bytes, off.toInt(), buf, 0, bytes.size - off.toInt())
                }
            }
            chunks.add(aesGcmSeal(fileKey, chunkNonce(fileNonce, i), slice, EMPTY_AAD))
            off += chunkSize
            i++
        }

        val info = manifestJson(filename, mime, bytes.size, chunks.size, chunkSize, paddedSize)
        val manifest = aesGcmSeal(fileKey, chunkNonce(fileNonce, MANIFEST_COUNTER), info, EMPTY_AAD)
        return EncryptedFile(fileKey, fileNonce, chunks, manifest, info)
    }

    /**
     * Inverse of [encryptFile] — iOS OSHICryptoV2.swift:584-611 (`decryptFile`).
     * Opens the manifest FIRST and asserts `chunkCount` and the reassembled `size`,
     * so a truncated or reordered download fails loudly instead of yielding
     * corrupt media.
     */
    fun decryptFile(
        fileKey: ByteArray,
        fileNonce: ByteArray,
        chunks: List<ByteArray>,
        manifest: ByteArray,
    ): ByteArray {
        val infoBytes = aesGcmOpen(fileKey, chunkNonce(fileNonce, MANIFEST_COUNTER), manifest, EMPTY_AAD)
        val info = org.json.JSONObject(String(infoBytes, Charsets.UTF_8))
        val chunkCount = info.getInt("chunkCount")
        val size = info.getInt("size")
        // __MEDIA_PADDING_2026_11_10__ absent ⇒ today's behaviour exactly.
        val paddedSize: Int? = if (info.has("paddedSize")) info.getInt("paddedSize") else null
        if (paddedSize != null) require(size in 0..paddedSize) { "manifest size=$size above paddedSize=$paddedSize" }
        require(chunkCount == chunks.size) { "manifest chunkCount=$chunkCount but got ${chunks.size} chunks" }
        val total = paddedSize ?: size
        val out = java.io.ByteArrayOutputStream(total)
        for ((idx, c) in chunks.withIndex()) {
            out.write(aesGcmOpen(fileKey, chunkNonce(fileNonce, idx.toLong()), c, EMPTY_AAD))
        }
        val bytes = out.toByteArray()
        if (paddedSize == null) {
            require(bytes.size == size) { "manifest size=$size but reassembled ${bytes.size}" }
            return bytes
        }
        require(bytes.size == paddedSize) { "manifest paddedSize=$paddedSize but reassembled ${bytes.size}" }
        for (k in size until paddedSize) require(bytes[k] == 0.toByte()) { "non-zero media padding at $k" }
        return bytes.copyOf(size)
    }

    private val EMPTY_AAD = ByteArray(0)
}
