package com.oshi.messenger.network.v2

import com.oshi.messenger.network.v2.OSHICryptoV2.X25519Pair

/**
 * Ties X3DH to the Double Ratchet to establish a 1:1 session, matching iOS
 * V2MessageRouter. Built on the vector-verified [OSHICryptoV2] + the round-trip-
 * verified [OSHIRatchetV2], so an Android session interoperates with iOS.
 *
 * Initiator (we started the conversation): fetch the peer bundle, X3DH as Alice,
 * ratchet-init-Alice, and carry the X3DH header on message #1 only.
 * Responder (we received an X3DH first message): recompute the same SK from our
 * signed/one-time prekey privates, ratchet-init-Bob.
 */
object V2Session {

    /** Rides message #1 only (V2ClientModels.swift:46-73). */
    data class X3DHHeader(
        val identityKey: ByteArray,   // initiator X25519 identity pub
        val ephemeralKey: ByteArray,  // initiator ephemeral pub
        val signedPreKeyId: String,
        val oneTimePreKeyId: String?,
    )

    data class InitiatorInit(val state: OSHIRatchetV2.State, val x3dh: X3DHHeader)

    /** Alice side, from a fetched (already signature-verified + TOFU'd) bundle. */
    fun initiator(myIdentity: X25519Pair, bundle: FetchedBundle): InitiatorInit {
        val ek = OSHICryptoV2.generateX25519()
        val sk = OSHICryptoV2.x3dhInitiator(
            ik = myIdentity, ek = ek,
            theirIkPub = bundle.identityKey,
            theirSpkPub = bundle.signedPreKey,
            theirOpkPub = bundle.oneTimePreKey,
        )
        val state = OSHIRatchetV2.initAlice(sk, bundle.signedPreKey)
        return InitiatorInit(
            state,
            X3DHHeader(myIdentity.pub, ek.pub, bundle.signedPreKeyId, bundle.oneTimePreKeyId),
        )
    }

    /**
     * Bob side. `myOpk` must match the initiator's `oneTimePreKeyId` (null if they
     * had none / the pool was drained). Caller must only consume (burn) the OPK
     * AFTER the first message AEAD-verifies (iOS OPK-burn discipline).
     */
    fun responder(myIdentity: X25519Pair, mySpk: X25519Pair, myOpk: X25519Pair?, x3dh: X3DHHeader): OSHIRatchetV2.State {
        val sk = OSHICryptoV2.x3dhResponder(
            ik = myIdentity, spk = mySpk, opk = myOpk,
            theirIkPub = x3dh.identityKey,
            theirEkPub = x3dh.ephemeralKey,
        )
        return OSHIRatchetV2.initBob(sk, mySpk)
    }
}
