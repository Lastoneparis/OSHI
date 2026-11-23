package com.oshi.messenger.network.ohttp

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.hpke.HPKE
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Oblivious HTTP client-side encapsulation (RFC 9458 §4.3/§4.4) for ONE suite:
 * DHKEM(X25519, HKDF-SHA256) 0x0020 / HKDF-SHA256 0x0001 / AES-128-GCM 0x0001.
 *
 * HPKE comes from BouncyCastle's lightweight API (`org.bouncycastle.crypto.hpke`),
 * already a dependency (bcprov-jdk18on) — no new library, no APK growth. Tink's
 * Android HPKE does not expose the ephemeral key for the RFC test vector.
 */
object Ohttp {
    const val KEM_X25519_SHA256: Int = 0x0020
    const val KDF_HKDF_SHA256: Int = 0x0001
    const val AEAD_AES_128_GCM: Int = 0x0001
    const val NPK = 32           // X25519 public key / enc length
    const val NK = 16            // AES-128 key
    const val NN = 12            // GCM nonce
    const val RESPONSE_NONCE_LEN = 16 // max(Nn, Nk)

    const val REQUEST_MEDIA_TYPE = "message/ohttp-req"
    const val RESPONSE_MEDIA_TYPE = "message/ohttp-res"
    const val KEYS_MEDIA_TYPE = "application/ohttp-keys"
    private val REQUEST_LABEL = "message/bhttp request".toByteArray(Charsets.US_ASCII)
    private val RESPONSE_LABEL = "message/bhttp response".toByteArray(Charsets.US_ASCII)

    private val rng = SecureRandom()

    /** One RFC 9458 §3 key configuration, restricted to the suite we implement. */
    data class KeyConfig(val keyId: Int, val publicKey: ByteArray) {
        init { require(keyId in 0..255 && publicKey.size == NPK) }
        /** hdr = key_id(1) | kem_id(2) | kdf_id(2) | aead_id(2) */
        fun header(): ByteArray = byteArrayOf(
            keyId.toByte(),
            (KEM_X25519_SHA256 shr 8).toByte(), KEM_X25519_SHA256.toByte(),
            (KDF_HKDF_SHA256 shr 8).toByte(), KDF_HKDF_SHA256.toByte(),
            (AEAD_AES_128_GCM shr 8).toByte(), AEAD_AES_128_GCM.toByte(),
        )
    }

    class KeyMismatchException(message: String) : IOException(message)

    /**
     * Parse one bare key config (RFC 9458 §3.1) — returns null if it does not offer our
     * KEM with the (HKDF-SHA256, AES-128-GCM) pair.
     */
    fun parseKeyConfig(bytes: ByteArray): KeyConfig? {
        val r = Bhttp.Reader(bytes)
        val keyId = r.byte()
        val kem = (r.byte() shl 8) or r.byte()
        if (kem != KEM_X25519_SHA256) return null
        val pk = r.bytes(NPK.toLong())
        val algLen = (r.byte() shl 8) or r.byte()
        if (algLen % 4 != 0 || algLen == 0) throw Bhttp.FormatException("bad symmetric algorithms length $algLen")
        val algs = Bhttp.Reader(r.bytes(algLen.toLong()))
        var supported = false
        while (!algs.atEnd()) {
            val kdf = (algs.byte() shl 8) or algs.byte()
            val aead = (algs.byte() shl 8) or algs.byte()
            if (kdf == KDF_HKDF_SHA256 && aead == AEAD_AES_128_GCM) supported = true
        }
        if (!r.atEnd()) throw Bhttp.FormatException("trailing bytes in key config")
        return if (supported) KeyConfig(keyId, pk) else null
    }

    /**
     * `application/ohttp-keys` (RFC 9540 §3): a sequence of 2-byte length-prefixed key
     * configs. Returns the first usable one, or null.
     */
    fun selectFromKeysMedia(bytes: ByteArray): KeyConfig? {
        val r = Bhttp.Reader(bytes)
        while (!r.atEnd()) {
            val len = (r.byte() shl 8) or r.byte()
            val cfg = parseKeyConfig(r.bytes(len.toLong()))
            if (cfg != null) return cfg
        }
        return null
    }

    fun hpke() = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM128)

    fun requestInfo(header: ByteArray): ByteArray = REQUEST_LABEL + byteArrayOf(0) + header

    /** Per-request client state needed to open the matching response. */
    class Context internal constructor(val encapsulatedRequest: ByteArray, internal val enc: ByteArray, internal val secret: ByteArray)

    /**
     * RFC 9458 §4.3: `hdr | enc | ct`. [ephemeral] exists ONLY for the RFC test vector;
     * production always passes null (fresh ephemeral X25519 key per request).
     */
    fun encapsulateRequest(config: KeyConfig, bhttpRequest: ByteArray, ephemeral: AsymmetricCipherKeyPair? = null): Context {
        val hpke = hpke()
        val header = config.header()
        val info = requestInfo(header)
        val pkR = hpke.deserializePublicKey(config.publicKey)
        val ctx = if (ephemeral == null) hpke.setupBaseS(pkR, info) else hpke.setupBaseS(pkR, info, ephemeral)
        val enc = ctx.encapsulation
        val ct = ctx.seal(ByteArray(0), bhttpRequest)
        val secret = ctx.export(RESPONSE_LABEL, RESPONSE_NONCE_LEN)
        return Context(header + enc + ct, enc, secret)
    }

    /** RFC 9458 §4.4: `response_nonce(16) | ct`. Throws on any authentication failure. */
    fun decapsulateResponse(ctx: Context, encapsulatedResponse: ByteArray): ByteArray =
        decapsulateResponse(ctx.enc, ctx.secret, encapsulatedResponse)

    fun decapsulateResponse(enc: ByteArray, secret: ByteArray, encapsulatedResponse: ByteArray): ByteArray {
        if (encapsulatedResponse.size < RESPONSE_NONCE_LEN + 16) throw IOException("OHTTP response too short")
        val nonce = encapsulatedResponse.copyOfRange(0, RESPONSE_NONCE_LEN)
        val (aeadKey, aeadNonce) = responseKeys(enc, secret, nonce)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(aeadKey, "AES"), GCMParameterSpec(128, aeadNonce))
            c.doFinal(encapsulatedResponse, RESPONSE_NONCE_LEN, encapsulatedResponse.size - RESPONSE_NONCE_LEN)
        } catch (e: Exception) {
            throw IOException("OHTTP response failed authentication", e)
        }
    }

    /** Gateway-side response sealing (used by tests / fake gateways). */
    fun encapsulateResponse(enc: ByteArray, secret: ByteArray, bhttpResponse: ByteArray, nonce: ByteArray? = null): ByteArray {
        val responseNonce = nonce ?: ByteArray(RESPONSE_NONCE_LEN).also { rng.nextBytes(it) }
        val (aeadKey, aeadNonce) = responseKeys(enc, secret, responseNonce)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aeadKey, "AES"), GCMParameterSpec(128, aeadNonce))
        return responseNonce + c.doFinal(bhttpResponse)
    }

    internal fun responseKeys(enc: ByteArray, secret: ByteArray, responseNonce: ByteArray): Pair<ByteArray, ByteArray> {
        val prk = hmac(enc + responseNonce, secret)                 // HKDF-Extract(salt, IKM)
        return expand(prk, "key", NK) to expand(prk, "nonce", NN)    // HKDF-Expand(prk, info, L)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** HKDF-Expand for L <= 32 (a single HMAC block). */
    private fun expand(prk: ByteArray, info: String, len: Int): ByteArray {
        require(len <= 32)
        val t = hmac(prk, info.toByteArray(Charsets.US_ASCII) + byteArrayOf(1))
        return t.copyOf(len)
    }

    fun hex(s: String): ByteArray {
        val clean = s.filter { !it.isWhitespace() }
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
