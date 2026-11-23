package com.oshi.messenger.mail

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Opens (and creates) the sealed blobs of OSHI Mail — the Android half of
 * `crypto_mail.js seal()` on the server and `OSHIMailClient.seal/open` on iOS.
 *
 * Wire format, identical on all three:
 *
 *     epk(32) ‖ iv(12) ‖ tag(16) ‖ ciphertext
 *     key = HKDF-SHA256(ikm = X25519(eph, recipient), salt = epk ‖ recipientPub,
 *                       info = "oshi-mail-v1", len = 32)
 *     AES-256-GCM
 *
 * BouncyCastle rather than the platform: `KeyPairGenerator("XDH")` needs API 33 and this
 * app ships to minSdk 26, so the JDK route would fail on most installs. BouncyCastle is
 * already a dependency and `CryptoManager` already uses exactly these X25519 classes.
 *
 * Note the field order: AES-GCM implementations in Java append the tag to the ciphertext,
 * so the two are split apart on seal and re-joined on open. Getting that wrong produces a
 * blob that this code can read and the other two platforms cannot.
 */
object MailCrypto {

    private const val INFO = "oshi-mail-v1"
    private const val EPK_LEN = 32
    private const val IV_LEN = 12
    private const val TAG_LEN = 16
    const val SEAL_OVERHEAD = EPK_LEN + IV_LEN + TAG_LEN

    private val random = SecureRandom()

    class SealedBlobError(message: String, cause: Throwable? = null) : Exception(message, cause)

    private fun deriveKey(shared: ByteArray, epk: ByteArray, recipientPub: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(shared, epk + recipientPub, INFO.toByteArray(Charsets.UTF_8)))
        return ByteArray(32).also { hkdf.generateBytes(it, 0, it.size) }
    }

    /** Encrypts to [recipientPub]; only the matching private key can reverse it. */
    fun seal(plaintext: ByteArray, recipientPub: ByteArray): ByteArray {
        require(recipientPub.size == 32) { "recipient public key must be 32 bytes" }

        val ephemeralPriv = X25519PrivateKeyParameters(random)
        val epk = ephemeralPriv.generatePublicKey().encoded

        val shared = ByteArray(32)
        X25519Agreement().apply {
            init(ephemeralPriv)
            calculateAgreement(X25519PublicKeyParameters(recipientPub, 0), shared, 0)
        }

        val key = deriveKey(shared, epk, recipientPub)
        shared.fill(0)

        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, iv))
        val ctAndTag = cipher.doFinal(plaintext)
        key.fill(0)

        // Java puts the tag last; the wire format puts it before the ciphertext.
        val ct = ctAndTag.copyOfRange(0, ctAndTag.size - TAG_LEN)
        val tag = ctAndTag.copyOfRange(ctAndTag.size - TAG_LEN, ctAndTag.size)
        return epk + iv + tag + ct
    }

    /** Reverses [seal]. Throws [SealedBlobError] when this device is not the recipient. */
    fun open(blob: ByteArray, recipientPriv: ByteArray): ByteArray {
        if (blob.size < SEAL_OVERHEAD) throw SealedBlobError("blob too short to be sealed")
        require(recipientPriv.size == 32) { "recipient private key must be 32 bytes" }

        val epk = blob.copyOfRange(0, EPK_LEN)
        val iv = blob.copyOfRange(EPK_LEN, EPK_LEN + IV_LEN)
        val tag = blob.copyOfRange(EPK_LEN + IV_LEN, SEAL_OVERHEAD)
        val ct = blob.copyOfRange(SEAL_OVERHEAD, blob.size)

        val priv = X25519PrivateKeyParameters(recipientPriv, 0)
        val recipientPub = priv.generatePublicKey().encoded

        val shared = ByteArray(32)
        X25519Agreement().apply {
            init(priv)
            calculateAgreement(X25519PublicKeyParameters(epk, 0), shared, 0)
        }

        val key = deriveKey(shared, epk, recipientPub)
        shared.fill(0)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, iv))
            cipher.doFinal(ct + tag)
        } catch (e: Exception) {
            // A wrong key and a tampered blob are indistinguishable here, and should be:
            // saying which would tell an attacker whether they guessed the key.
            throw SealedBlobError("sealed for another device, or tampered with", e)
        } finally {
            key.fill(0)
        }
    }

    /** Generates the device's mail key. The private half must never leave the device. */
    fun generateMailKeyPair(): Pair<ByteArray, ByteArray> {
        val priv = X25519PrivateKeyParameters(random)
        return priv.encoded to priv.generatePublicKey().encoded
    }

    fun publicKeyFor(privateKey: ByteArray): ByteArray =
        X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
