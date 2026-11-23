package com.oshi.messenger.network.encryption

import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.*
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CryptoManager - iOS Compatible Encryption Module
 *
 * This module implements the same encryption scheme as iOS OSHI:
 * - X25519 (Curve25519) key exchange - MATCHES iOS CryptoKit Curve25519.KeyAgreement
 * - AES-256-GCM for symmetric encryption
 * - HKDF for key derivation (with EMPTY salt and EMPTY info to match iOS)
 *
 * Cross-platform compatibility requirements:
 * - Same curve: X25519/Curve25519 (32-byte keys)
 * - Same key encoding: Raw 32-byte representation, BASE64 encoded
 * - Same GCM parameters (12-byte nonce, 128-bit tag)
 * - Same HKDF parameters (empty salt, empty info)
 */
@Singleton
class CryptoManager @Inject constructor() {

    companion object {
        private const val TAG = "CryptoManager"
        private const val KEY_SIZE = 32 // X25519 uses 32-byte keys
        private const val GCM_NONCE_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128 // bits
    }

    private var privateKeyParams: X25519PrivateKeyParameters? = null
    private var publicKeyParams: X25519PublicKeyParameters? = null

    // Ed25519 signing keys (matching iOS Curve25519.Signing)
    private var signingPrivateKey: Ed25519PrivateKeyParameters? = null
    private var signingPublicKey: Ed25519PublicKeyParameters? = null

    // ============================================================================
    // KEY GENERATION & MANAGEMENT (X25519 - iOS Compatible)
    // ============================================================================

    /**
     * Generate a new identity key pair using X25519 (Curve25519)
     * Also generates Ed25519 signing key pair (matching iOS)
     * Compatible with iOS CryptoKit Curve25519.KeyAgreement.PrivateKey
     */
    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        Log.d(TAG, "🔑 Generating X25519 key pair (iOS compatible)...")

        // Generate X25519 key agreement key pair
        val keyPairGenerator = X25519KeyPairGenerator()
        keyPairGenerator.init(X25519KeyGenerationParameters(SecureRandom()))
        val keyPair = keyPairGenerator.generateKeyPair()

        privateKeyParams = keyPair.private as X25519PrivateKeyParameters
        publicKeyParams = keyPair.public as X25519PublicKeyParameters

        // Generate Ed25519 signing key pair (matching iOS Curve25519.Signing)
        Log.d(TAG, "🔑 Generating Ed25519 signing key pair...")
        val signingKeyPairGenerator = Ed25519KeyPairGenerator()
        signingKeyPairGenerator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val signingKeyPair = signingKeyPairGenerator.generateKeyPair()

        signingPrivateKey = signingKeyPair.private as Ed25519PrivateKeyParameters
        signingPublicKey = signingKeyPair.public as Ed25519PublicKeyParameters

        Log.d(TAG, "✅ X25519 + Ed25519 key pairs generated successfully")
        return Pair(privateKeyParams!!.encoded, publicKeyParams!!.encoded)
    }

    /**
     * Get the current key pair or generate a new one
     * Returns (privateKey, publicKey) as raw bytes
     */
    fun getOrCreateKeyPair(): Pair<ByteArray, ByteArray> {
        if (privateKeyParams == null || publicKeyParams == null) {
            return generateKeyPair()
        }
        return Pair(privateKeyParams!!.encoded, publicKeyParams!!.encoded)
    }

    /**
     * Set key pair from stored raw keys (32 bytes each)
     * Compatible with iOS raw representation format
     */
    fun setKeyPair(privateKeyBytes: ByteArray, publicKeyBytes: ByteArray) {
        require(privateKeyBytes.size == KEY_SIZE) { "Private key must be 32 bytes" }
        require(publicKeyBytes.size == KEY_SIZE) { "Public key must be 32 bytes" }

        privateKeyParams = X25519PrivateKeyParameters(privateKeyBytes, 0)
        publicKeyParams = X25519PublicKeyParameters(publicKeyBytes, 0)

        Log.d(TAG, "✅ Key pair loaded from stored keys")
    }

    /** Wipe in-memory key material (called by deleteIdentity). */
    fun clearKeys() {
        privateKeyParams = null
        publicKeyParams = null
        signingPrivateKey = null
        signingPublicKey = null
        Log.d(TAG, "🧹 In-memory key pair cleared")
    }

    /**
     * Set signing key pair from stored raw keys (32 bytes each)
     */
    fun setSigningKeyPair(privateKeyBytes: ByteArray, publicKeyBytes: ByteArray) {
        require(privateKeyBytes.size == KEY_SIZE) { "Signing private key must be 32 bytes" }
        require(publicKeyBytes.size == KEY_SIZE) { "Signing public key must be 32 bytes" }

        signingPrivateKey = Ed25519PrivateKeyParameters(privateKeyBytes, 0)
        signingPublicKey = Ed25519PublicKeyParameters(publicKeyBytes, 0)

        Log.d(TAG, "✅ Signing key pair loaded from stored keys")
    }

    /**
     * Apply a new key pair during key rotation
     * @param publicKeyBase64 Public key as BASE64 string (iOS format)
     * @param privateKeyBytes Private key as raw bytes (32 bytes)
     */
    fun applyNewKeyPair(publicKeyBase64: String, privateKeyBytes: ByteArray) {
        require(privateKeyBytes.size == KEY_SIZE) { "Private key must be 32 bytes" }

        val publicKeyBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        require(publicKeyBytes.size == KEY_SIZE) { "Public key must be 32 bytes" }

        privateKeyParams = X25519PrivateKeyParameters(privateKeyBytes, 0)
        publicKeyParams = X25519PublicKeyParameters(publicKeyBytes, 0)
    }

    /**
     * Export private key as raw bytes (32 bytes) for backup/rotation
     */
    fun exportPrivateKeyBytes(): ByteArray {
        return privateKeyParams?.encoded
            ?: throw IllegalStateException("No key pair available")
    }

    /**
     * Deterministically derives the mail X25519 private-key material (32 bytes) from this
     * identity's stable X25519 encryption secret. Because it is a pure function of the
     * identity key — which the seed / key backup restores exactly — the SAME mail key
     * comes back on any device that restores this identity, so the mailbox and drive
     * survive losing the phone (keep the key, keep the mail). Matches iOS
     * IdentityManager.deriveMailKeyMaterial exactly: same IKM (the identity X25519
     * private key), salt "oshi-mail", info "oshi-mail-x25519-v1". The server never sees
     * this key: zero-access is unchanged.
     */
    fun deriveMailKeyMaterial(): ByteArray? {
        val ikm = privateKeyParams?.encoded ?: return null
        return hkdfDerive(
            sharedSecret = ikm,
            salt = "oshi-mail".toByteArray(Charsets.UTF_8),
            info = "oshi-mail-x25519-v1".toByteArray(Charsets.UTF_8),
            outputLength = 32,
        )
    }

    /**
     * Export private key as BASE64 string
     */
    fun exportPrivateKey(): String {
        return Base64.encodeToString(exportPrivateKeyBytes(), Base64.NO_WRAP)
    }

    /**
     * Export public key in format compatible with iOS
     * iOS uses: publicKey.rawRepresentation.base64EncodedString()
     * Returns BASE64 encoded raw 32-byte representation
     */
    fun exportPublicKey(): String {
        val publicKey = publicKeyParams?.encoded
            ?: throw IllegalStateException("No key pair available")
        return Base64.encodeToString(publicKey, Base64.NO_WRAP)
    }

    /**
     * Get public key as ByteArray (raw 32 bytes)
     */
    fun getPublicKey(): ByteArray {
        return publicKeyParams?.encoded
            ?: throw IllegalStateException("No key pair available")
    }

    /**
     * Export public key as Base64 (same as exportPublicKey for iOS compatibility)
     */
    fun exportPublicKeyBase64(): String {
        return exportPublicKey()
    }

    /**
     * Get public key as string (alias for exportPublicKey for compatibility)
     */
    fun getPublicKeyString(): String {
        return publicKeyParams?.encoded?.let {
            Base64.encodeToString(it, Base64.NO_WRAP)
        } ?: ""
    }

    /**
     * Import a public key from iOS format (BASE64 or BASE64URL string of raw 32 bytes)
     */
    fun importPublicKey(base64String: String): X25519PublicKeyParameters {
        // Normalize base64url to standard base64 (iOS may send either format)
        val standardBase64 = base64String
            .replace("-", "+")
            .replace("_", "/")
        val padded = when (standardBase64.length % 4) {
            2 -> "${standardBase64}=="
            3 -> "${standardBase64}="
            else -> standardBase64
        }
        val keyBytes = Base64.decode(padded, Base64.NO_WRAP)
        require(keyBytes.size == KEY_SIZE) {
            "Public key must be 32 bytes, got ${keyBytes.size} (input: ${base64String.take(20)}...)"
        }
        return X25519PublicKeyParameters(keyBytes, 0)
    }

    /**
     * Import public key from raw bytes
     */
    fun importPublicKeyFromBytes(keyBytes: ByteArray): X25519PublicKeyParameters {
        require(keyBytes.size == KEY_SIZE) {
            "Public key must be 32 bytes, got ${keyBytes.size}"
        }
        return X25519PublicKeyParameters(keyBytes, 0)
    }

    // ============================================================================
    // X25519 KEY AGREEMENT (iOS Compatible)
    // ============================================================================

    /**
     * Perform X25519 key agreement to derive shared secret
     * Compatible with iOS CryptoKit sharedSecretFromKeyAgreement
     */
    fun deriveSharedSecret(peerPublicKey: X25519PublicKeyParameters): ByteArray {
        val privateKey = privateKeyParams
            ?: throw IllegalStateException("No private key available")

        val agreement = X25519Agreement()
        agreement.init(privateKey)

        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(peerPublicKey, sharedSecret, 0)

        return sharedSecret
    }

    /**
     * Derive shared secret from peer's BASE64 public key string (iOS format)
     */
    fun deriveSharedSecret(peerPublicKeyBase64: String): ByteArray {
        val peerKey = importPublicKey(peerPublicKeyBase64)
        return deriveSharedSecret(peerKey)
    }

    /**
     * Derive shared secret from peer's public key as raw ByteArray
     */
    fun deriveSharedSecret(peerPublicKeyBytes: ByteArray): ByteArray {
        val peerKey = importPublicKeyFromBytes(peerPublicKeyBytes)
        return deriveSharedSecret(peerKey)
    }

    // ============================================================================
    // KEY DERIVATION (HKDF - iOS Compatible)
    // ============================================================================

    /**
     * Derive symmetric key using HKDF
     * Compatible with iOS CryptoKit HKDF
     *
     * IMPORTANT: iOS uses EMPTY salt and EMPTY info:
     *   sharedSecret.hkdfDerivedSymmetricKey(
     *       using: SHA256.self,
     *       salt: Data(),      // EMPTY
     *       sharedInfo: Data(), // EMPTY
     *       outputByteCount: 32
     *   )
     *
     * @param sharedSecret The X25519 shared secret
     * @param salt Optional salt (default EMPTY to match iOS)
     * @param info Optional context info (default EMPTY to match iOS)
     * @param outputLength Desired key length in bytes
     */
    fun hkdfDerive(
        sharedSecret: ByteArray,
        salt: ByteArray = ByteArray(0),  // EMPTY to match iOS
        info: ByteArray = ByteArray(0),  // EMPTY to match iOS
        outputLength: Int = 32
    ): ByteArray {
        // HKDF-Extract
        // When salt is empty, HKDF spec says to use a string of HashLen zeros
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hkdfExtract(actualSalt, sharedSecret)

        // HKDF-Expand
        return hkdfExpand(prk, info, outputLength)
    }

    /**
     * Convenience method matching iOS signature
     */
    fun hkdfDerive(
        sharedSecret: ByteArray,
        salt: ByteArray? = null,
        info: String = "",
        outputLength: Int = 32
    ): ByteArray {
        return hkdfDerive(
            sharedSecret = sharedSecret,
            salt = salt ?: ByteArray(0),
            info = info.toByteArray(Charsets.UTF_8),
            outputLength = outputLength
        )
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val hashLength = 32 // SHA-256 output length
        val n = (outputLength + hashLength - 1) / hashLength
        val okm = ByteArray(outputLength)

        var t = ByteArray(0)
        var offset = 0

        for (i in 1..n) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()

            val copyLength = minOf(hashLength, outputLength - offset)
            System.arraycopy(t, 0, okm, offset, copyLength)
            offset += copyLength
        }

        return okm
    }

    // ============================================================================
    // EPHEMERAL KEY GENERATION (For Double Ratchet - Does NOT replace identity keys)
    // ============================================================================

    /**
     * Generate an ephemeral X25519 key pair for ratchet operations.
     * IMPORTANT: Does NOT replace the identity key pair.
     * Returns (privateKeyBytes, publicKeyBytes) as raw 32-byte arrays.
     */
    fun generateEphemeralKeyPair(): Pair<ByteArray, ByteArray> {
        val keyPairGenerator = X25519KeyPairGenerator()
        keyPairGenerator.init(X25519KeyGenerationParameters(SecureRandom()))
        val keyPair = keyPairGenerator.generateKeyPair()
        val privateKey = keyPair.private as X25519PrivateKeyParameters
        val publicKey = keyPair.public as X25519PublicKeyParameters
        return Pair(privateKey.encoded, publicKey.encoded)
    }

    /**
     * Derive shared secret using specific private and public keys (NOT the identity key pair).
     * Used by Double Ratchet for ephemeral DH operations.
     */
    fun deriveSharedSecretWithKeys(privateKeyBytes: ByteArray, publicKeyBytes: ByteArray): ByteArray {
        require(privateKeyBytes.size == KEY_SIZE) { "Private key must be 32 bytes" }
        require(publicKeyBytes.size == KEY_SIZE) { "Public key must be 32 bytes" }

        val privateKey = X25519PrivateKeyParameters(privateKeyBytes, 0)
        val publicKey = X25519PublicKeyParameters(publicKeyBytes, 0)

        val agreement = X25519Agreement()
        agreement.init(privateKey)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(publicKey, sharedSecret, 0)
        return sharedSecret
    }

    // ============================================================================
    // AES-256-GCM ENCRYPTION (iOS Compatible)
    // ============================================================================

    /**
     * Encrypt data using AES-256-GCM
     * Compatible with iOS CryptoKit AES.GCM
     *
     * Output format: nonce (12 bytes) || ciphertext || tag (16 bytes)
     * This is the same format iOS uses with AES.GCM.SealedBox
     */
    fun encryptAESGCM(plaintext: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes for AES-256" }

        // Generate random nonce (12 bytes, same as iOS)
        val nonce = ByteArray(GCM_NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        val ciphertextWithTag = cipher.doFinal(plaintext)

        // Return: nonce || ciphertext || tag (iOS SealedBox format)
        return nonce + ciphertextWithTag
    }

    /**
     * Decrypt data using AES-256-GCM
     * Compatible with iOS CryptoKit AES.GCM
     *
     * Input format: nonce (12 bytes) || ciphertext || tag (16 bytes)
     */
    fun decryptAESGCM(sealedBox: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes for AES-256" }
        require(sealedBox.size > GCM_NONCE_LENGTH + GCM_TAG_LENGTH / 8) {
            "Sealed box too short"
        }

        // Extract nonce (first 12 bytes)
        val nonce = sealedBox.sliceArray(0 until GCM_NONCE_LENGTH)

        // Extract ciphertext + tag (rest of the data)
        val ciphertextWithTag = sealedBox.sliceArray(GCM_NONCE_LENGTH until sealedBox.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)

        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        return cipher.doFinal(ciphertextWithTag)
    }

    /**
     * Encrypt using AES (alias for encryptAESGCM)
     */
    fun encryptAES(plaintext: ByteArray, key: ByteArray): ByteArray {
        return encryptAESGCM(plaintext, key)
    }

    /**
     * Decrypt using AES (alias for decryptAESGCM)
     */
    fun decryptAES(sealedBox: ByteArray, key: ByteArray): ByteArray {
        return decryptAESGCM(sealedBox, key)
    }

    /**
     * Encrypt data using AES-256-GCM with Additional Authenticated Data (AAD)
     * Used by Double Ratchet protocol for message authentication.
     * Output format: nonce (12 bytes) || ciphertext || tag (16 bytes)
     */
    fun encryptAESGCMWithAAD(plaintext: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes for AES-256" }

        val nonce = ByteArray(GCM_NONCE_LENGTH)
        SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        cipher.updateAAD(aad)
        val ciphertextWithTag = cipher.doFinal(plaintext)

        return nonce + ciphertextWithTag
    }

    /**
     * Decrypt data using AES-256-GCM with Additional Authenticated Data (AAD)
     * Input format: nonce (12 bytes) || ciphertext || tag (16 bytes)
     */
    fun decryptAESGCMWithAAD(sealedBox: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes for AES-256" }
        require(sealedBox.size > GCM_NONCE_LENGTH + GCM_TAG_LENGTH / 8) {
            "Sealed box too short"
        }

        val nonce = sealedBox.sliceArray(0 until GCM_NONCE_LENGTH)
        val ciphertextWithTag = sealedBox.sliceArray(GCM_NONCE_LENGTH until sealedBox.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)

        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertextWithTag)
    }

    // ============================================================================
    // HIGH-LEVEL ENCRYPTION (For Messages - iOS Compatible)
    // ============================================================================

    /**
     * Encrypt a message for a recipient
     * Uses X25519 + HKDF + AES-GCM (same as iOS)
     *
     * 1. Derive shared secret via X25519
     * 2. Derive symmetric key via HKDF (empty salt, empty info - like iOS)
     * 3. Encrypt with AES-256-GCM
     */
    fun encryptMessage(plaintext: ByteArray, recipientPublicKeyBase64: String): ByteArray {
        val sharedSecret = deriveSharedSecret(recipientPublicKeyBase64)
        // Use empty salt and empty info to match iOS
        val symmetricKey = hkdfDerive(sharedSecret, ByteArray(0), ByteArray(0), 32)
        return encryptAESGCM(plaintext, symmetricKey)
    }

    /**
     * Encrypt a string message
     */
    fun encryptMessage(message: String, recipientPublicKeyBase64: String): ByteArray {
        return encryptMessage(message.toByteArray(Charsets.UTF_8), recipientPublicKeyBase64)
    }

    /**
     * Decrypt a message from a sender
     */
    fun decryptMessage(sealedBox: ByteArray, senderPublicKeyBase64: String): ByteArray {
        val sharedSecret = deriveSharedSecret(senderPublicKeyBase64)
        // Use empty salt and empty info to match iOS
        val symmetricKey = hkdfDerive(sharedSecret, ByteArray(0), ByteArray(0), 32)
        return decryptAESGCM(sealedBox, symmetricKey)
    }

    /**
     * Encrypt call signal for a peer (iOS VoiceCallManager compatible)
     * Uses HKDF salt="OSHI-VoiceCall-v1", info="signaling" to match iOS encryptForPeer()
     */
    fun encryptCallSignal(plaintext: ByteArray, recipientPublicKeyBase64: String): ByteArray {
        val sharedSecret = deriveSharedSecret(recipientPublicKeyBase64)
        val symmetricKey = hkdfDerive(
            sharedSecret,
            salt = "OSHI-VoiceCall-v1".toByteArray(Charsets.UTF_8),
            info = "signaling".toByteArray(Charsets.UTF_8),
            outputLength = 32
        )
        return encryptAESGCM(plaintext, symmetricKey)
    }

    /**
     * Decrypt call signal from a peer (iOS VoiceCallManager compatible)
     * Uses HKDF salt="OSHI-VoiceCall-v1", info="signaling" to match iOS decryptFromPeer()
     */
    fun decryptCallSignal(sealedBox: ByteArray, senderPublicKeyBase64: String): ByteArray {
        val sharedSecret = deriveSharedSecret(senderPublicKeyBase64)
        val symmetricKey = hkdfDerive(
            sharedSecret,
            salt = "OSHI-VoiceCall-v1".toByteArray(Charsets.UTF_8),
            info = "signaling".toByteArray(Charsets.UTF_8),
            outputLength = 32
        )
        return decryptAESGCM(sealedBox, symmetricKey)
    }

    /**
     * Decrypt to string
     */
    fun decryptMessageToString(sealedBox: ByteArray, senderPublicKeyBase64: String): String {
        return decryptMessage(sealedBox, senderPublicKeyBase64).toString(Charsets.UTF_8)
    }

    // ============================================================================
    // ED25519 SIGNING (iOS Compatible)
    // ============================================================================

    /**
     * Sign data using Ed25519
     * Compatible with iOS CryptoKit Curve25519.Signing
     */
    fun sign(data: ByteArray): ByteArray {
        val privateKey = signingPrivateKey
            ?: throw IllegalStateException("No signing key available")

        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    /**
     * Verify an Ed25519 signature
     */
    fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        require(publicKey.size == KEY_SIZE) { "Public key must be 32 bytes" }

        val pubKeyParams = Ed25519PublicKeyParameters(publicKey, 0)
        val verifier = Ed25519Signer()
        verifier.init(false, pubKeyParams)
        verifier.update(data, 0, data.size)
        return verifier.verifySignature(signature)
    }

    /**
     * Get signing public key as raw bytes
     */
    fun getSigningPublicKey(): ByteArray {
        return signingPublicKey?.encoded
            ?: throw IllegalStateException("No signing key available")
    }

    /**
     * Export signing private key as raw bytes
     */
    fun exportSigningPrivateKey(): ByteArray {
        return signingPrivateKey?.encoded
            ?: throw IllegalStateException("No signing key available")
    }

    // ============================================================================
    // UTILITY FUNCTIONS
    // ============================================================================

    /**
     * Generate a random 256-bit symmetric key
     */
    fun generateSymmetricKey(): ByteArray {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        return key
    }

    /**
     * Hash data using SHA-256
     */
    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    /**
     * Safety number for contact verification.
     *
     * Redirected to the single implementation. This one built 6 groups of 5 from
     * 16-bit words mod 100000 — a third algorithm, agreeing neither with
     * `SafetyNumberManager` nor with iOS (`OSHI/SafetyNumber.swift:21-39`), which takes
     * 30 bytes as `byte % 100` and yields TWELVE groups.
     */
    fun generateSafetyNumber(myPublicKey: String, theirPublicKey: String): String =
        com.oshi.messenger.service.SafetyNumberCodec.displayCode(
            com.oshi.messenger.service.SafetyNumberCodec.fingerprint(myPublicKey, theirPublicKey)
        )

    /**
     * Check if this manager has a valid key pair loaded
     */
    fun hasKeyPair(): Boolean {
        return privateKeyParams != null && publicKeyParams != null
    }
}

// ============================================================================
// EXTENSION FUNCTIONS
// ============================================================================

/**
 * Convert ByteArray to hex string
 */
fun ByteArray.toHexString(): String {
    return joinToString("") { "%02x".format(it) }
}

/**
 * Convert hex string to ByteArray
 */
fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
