package com.oshi.messenger.network.v2

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The `.oshiexport` container — the ONLY way a message export leaves the device.
 *
 * Cross-platform: byte-compatible with iOS CryptoKit (and anything else that
 * follows this layout). Pure JVM (javax.crypto + BouncyCastle X25519), no
 * Android dependency, so it can be compiled by the Desktop project as-is.
 *
 * Layout:
 * ```
 *  "OSHIEXP1" (8) | version 0x01 (1) | salt (32) | accountTag (8)   ← header, 49 bytes, = GCM AAD
 *  | nonce (12) | AES-256-GCM ciphertext | GCM tag (16)
 * ```
 *  - key        = HKDF-SHA256(IKM = raw 32-byte X25519 encryption private key,
 *                             salt = the 32-byte salt, info = "oshi-message-export-v1", L = 32)
 *  - accountTag = SHA-256(raw 32-byte X25519 public key)[0..8)
 *
 * The key is derived from the ACCOUNT key (the one the recovery key restores),
 * never from a device key: the whole point is that the file can be imported on
 * another device once the same account has been restored there, and by nobody
 * else.
 *
 * Known-answer vector: `MessageExportCryptoTest`.
 */
object MessageExportCrypto {

    const val FILE_EXTENSION = "oshiexport"
    const val MIME_TYPE = "application/octet-stream"

    val MAGIC: ByteArray = "OSHIEXP1".toByteArray(Charsets.US_ASCII)
    const val VERSION: Byte = 0x01
    const val SALT_LEN = 32
    const val TAG_LEN = 8
    const val NONCE_LEN = 12
    const val GCM_TAG_LEN = 16
    const val HEADER_LEN = 8 + 1 + SALT_LEN + TAG_LEN          // 49
    const val MIN_LEN = HEADER_LEN + NONCE_LEN + GCM_TAG_LEN   // 77
    private val INFO = "oshi-message-export-v1".toByteArray(Charsets.UTF_8)

    /** Why an import was refused. The UI maps each case to its own message. */
    sealed class OpenError(message: String) : Exception(message) {
        /** Not an `.oshiexport` file at all (magic mismatch or too short). */
        class NotAnExport : OpenError("not an OSHI export")
        /** A future container version this build does not understand. */
        class UnsupportedVersion(val version: Int) : OpenError("unsupported export version $version")
        /** Sealed for a different account key — restore that account first. */
        class WrongAccount : OpenError("export belongs to another OSHI account")
        /** GCM authentication failed: truncated, corrupted or tampered with. */
        class Damaged : OpenError("export is damaged or has been modified")
    }

    /** X25519 public key for a raw 32-byte private key (RFC 7748, clamps internally). */
    fun publicKeyFor(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        return X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
    }

    fun accountTag(publicKey: ByteArray): ByteArray {
        require(publicKey.size == 32) { "X25519 public key must be 32 bytes" }
        return MessageDigest.getInstance("SHA-256").digest(publicKey).copyOfRange(0, TAG_LEN)
    }

    /** HKDF-SHA256 (RFC 5869), L = 32. */
    fun deriveKey(privateKey: ByteArray, salt: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        require(salt.size == SALT_LEN) { "salt must be $SALT_LEN bytes" }
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = extract.doFinal(privateKey)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        expand.update(INFO)
        expand.update(0x01)
        return expand.doFinal() // exactly one 32-byte block == L
    }

    /**
     * Seal [plaintext] for the account whose X25519 private key is [privateKey].
     * [salt] and [nonce] are random unless given (tests only).
     */
    fun seal(
        plaintext: ByteArray,
        privateKey: ByteArray,
        salt: ByteArray? = null,
        nonce: ByteArray? = null,
    ): ByteArray {
        val rng = SecureRandom()
        val s = salt ?: ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val n = nonce ?: ByteArray(NONCE_LEN).also { rng.nextBytes(it) }
        require(s.size == SALT_LEN && n.size == NONCE_LEN)
        val header = MAGIC + byteArrayOf(VERSION) + s + accountTag(publicKeyFor(privateKey))
        val key = deriveKey(privateKey, s)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LEN * 8, n))
            cipher.updateAAD(header)
            val ctAndTag = cipher.doFinal(plaintext) // JCE appends the 16-byte tag
            return header + n + ctAndTag
        } finally {
            key.fill(0)
        }
    }

    /**
     * Open an `.oshiexport` [file] with the account key [privateKey].
     * Checks, in order: magic → version → accountTag → GCM authentication.
     * @throws OpenError for every refusal; never returns unauthenticated bytes.
     */
    fun open(file: ByteArray, privateKey: ByteArray): ByteArray {
        if (file.size < MIN_LEN || !file.copyOfRange(0, 8).contentEquals(MAGIC)) {
            throw OpenError.NotAnExport()
        }
        val version = file[8].toInt() and 0xFF
        if (version != VERSION.toInt()) throw OpenError.UnsupportedVersion(version)
        val salt = file.copyOfRange(9, 9 + SALT_LEN)
        val tag = file.copyOfRange(9 + SALT_LEN, HEADER_LEN)
        val mine = accountTag(publicKeyFor(privateKey))
        if (!MessageDigest.isEqual(tag, mine)) throw OpenError.WrongAccount()
        val header = file.copyOfRange(0, HEADER_LEN)
        val nonce = file.copyOfRange(HEADER_LEN, HEADER_LEN + NONCE_LEN)
        val key = deriveKey(privateKey, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LEN * 8, nonce))
            cipher.updateAAD(header)
            return cipher.doFinal(file, HEADER_LEN + NONCE_LEN, file.size - HEADER_LEN - NONCE_LEN)
        } catch (e: AEADBadTagException) {
            throw OpenError.Damaged()
        } catch (e: java.security.GeneralSecurityException) {
            throw OpenError.Damaged()
        } finally {
            key.fill(0)
        }
    }
}
