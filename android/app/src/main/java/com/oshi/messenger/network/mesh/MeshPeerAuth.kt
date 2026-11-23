package com.oshi.messenger.network.mesh

import com.oshi.messenger.network.v2.OSHICryptoV2
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * MeshPeerAuth — proof-of-possession PEER AUTHENTICATION for the BLE / TCP
 * cross-platform mesh (Android mirror of iOS `MeshPeerAuth.swift`).
 *
 * ── WHY THIS EXISTS ─────────────────────────────────────────────────────────
 * CrossPlatformMesh learns a peer's X25519 identity key K by simply BELIEVING
 * the key it announces (TOFU). Nothing proves the announcing device holds K's
 * private half, so a nearby attacker can announce a contact's key and poison
 * routing / impersonate at the TRANSPORT layer.
 *
 * THREAT MODEL — be precise: message CONTENT is already protected by the Double
 * Ratchet (an impostor who cannot decrypt cannot read or forge a message that
 * decrypts). This gap is therefore ROUTING-POISONING / DoS / metadata, NOT a
 * content-confidentiality break. This is DEFENSE-IN-DEPTH.
 *
 * ── THE PROOF (DH challenge-response) ───────────────────────────────────────
 *   1. On learning K we send a random 32-byte nonce N (the CHALLENGE).
 *   2. The peer replies tag = HMAC-SHA256(sharedSecret, N) where
 *        sharedSecret = DH(their_private, our_public).
 *      By X25519 symmetry this equals DH(our_private, K), so only a device that
 *      holds K's private half can produce it.
 *   3. We recompute HMAC(DH(our_private, K), N) and compare constant-time.
 * Reuses the existing X25519 + HMAC-SHA256 primitives in [OSHICryptoV2] — no new
 * crypto. The shared secret is used directly as the HMAC key (no HKDF) so the
 * wire value matches iOS byte-for-byte and is pinnable as a test vector.
 *
 * ── ADDITIVE / ADVISORY ONLY (non-negotiable) ───────────────────────────────
 * The result is a `verified` SIGNAL and nothing more. Callers MUST NOT reject,
 * drop or refuse delivery to a peer that fails or ignores the challenge: iOS and
 * older Android builds do not implement it yet, and rejecting them would sever
 * the mesh (a one-sided wire break). TOFU delivery keeps working for everyone.
 */
object MeshPeerAuth {

    /** Challenge nonce size, in bytes. Matches the iOS side. */
    const val NONCE_LENGTH = 32
    /** HMAC-SHA256 tag size, in bytes. */
    const val TAG_LENGTH = 32

    private val secureRandom = SecureRandom()

    /** Fresh cryptographically-random challenge nonce. */
    fun makeNonce(): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH)
        secureRandom.nextBytes(nonce)
        return nonce
    }

    /**
     * Raw X25519 shared secret (no hashing), matching iOS CryptoKit
     * `sharedSecretFromKeyAgreement`. Inputs are 32-byte raw X25519 keys.
     */
    fun sharedSecret(myPrivateKeyRaw: ByteArray, peerPublicKeyRaw: ByteArray): ByteArray =
        OSHICryptoV2.dh(myPrivateKeyRaw, peerPublicKeyRaw)

    /** tag = HMAC-SHA256(sharedSecret, nonce). */
    fun tag(sharedSecret: ByteArray, nonce: ByteArray): ByteArray =
        OSHICryptoV2.hmac(sharedSecret, nonce)

    /** Constant-time verification of a received tag against the expected one. */
    fun verify(receivedTag: ByteArray, sharedSecret: ByteArray, nonce: ByteArray): Boolean =
        constantTimeEquals(receivedTag, tag(sharedSecret, nonce))

    /**
     * Length-checked, constant-time byte comparison. [MessageDigest.isEqual] is
     * documented to run in constant time, avoiding a timing side-channel on how
     * many leading bytes of a forged tag happened to be correct.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
