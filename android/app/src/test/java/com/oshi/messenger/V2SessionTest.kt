package com.oshi.messenger

import com.oshi.messenger.network.v2.FetchedBundle
import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.OSHIRatchetV2
import com.oshi.messenger.network.v2.V2Session
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * End-to-end V2 1:1 session establishment: an initiator fetches the responder's
 * published bundle, does X3DH + ratchet, and the responder recomputes the same
 * session from an X3DH first-message header and decrypts. Then a reply flows back.
 *
 * This is the full message-crypto path minus the HTTP relay, so it proves the
 * interoperable part end to end. (The primitives + ratchet underneath are already
 * vector-/round-trip-verified.)
 */
class V2SessionTest {

    private val ad = ByteArray(0) // iOS uses associatedData = empty (header-only AAD)

    @Test
    fun `first-message X3DH session establishes and both directions decrypt`() {
        // Responder (Bob) publishes: identity, signed prekey, one-time prekey.
        val bIdentity = OSHICryptoV2.generateX25519()
        val bSpk = OSHICryptoV2.generateX25519()
        val bOpk = OSHICryptoV2.generateX25519()

        // Initiator (Alice) fetches Bob's (verified) bundle.
        val bundle = FetchedBundle(
            identityKey = bIdentity.pub,
            signingKey = ByteArray(32),        // not used in this crypto path
            signedPreKeyId = "spk-1",
            signedPreKey = bSpk.pub,
            oneTimePreKeyId = "opk-1",
            oneTimePreKey = bOpk.pub,
        )
        val aIdentity = OSHICryptoV2.generateX25519()
        val (aliceState, x3dh) = V2Session.initiator(aIdentity, bundle)

        // Alice sends message #1 (carries the x3dh header on the wire).
        val m1 = "hi bob, first v2 message".toByteArray()
        val (h1, c1) = OSHIRatchetV2.encrypt(aliceState, m1, ad)

        // Bob receives the x3dh header, recomputes the session, decrypts.
        val bobState = V2Session.responder(bIdentity, bSpk, bOpk, x3dh)
        assertArrayEquals(m1, OSHIRatchetV2.decrypt(bobState, h1, c1, ad))

        // Reply flows back (Bob now has a sending chain).
        val r1 = "got it, alice".toByteArray()
        val (h2, c2) = OSHIRatchetV2.encrypt(bobState, r1, ad)
        assertArrayEquals(r1, OSHIRatchetV2.decrypt(aliceState, h2, c2, ad))

        // And subsequent messages keep flowing.
        val m2 = "second one".toByteArray()
        val (h3, c3) = OSHIRatchetV2.encrypt(aliceState, m2, ad)
        assertArrayEquals(m2, OSHIRatchetV2.decrypt(bobState, h3, c3, ad))
    }

    @Test
    fun `session works without a one-time prekey (drained pool)`() {
        val bIdentity = OSHICryptoV2.generateX25519()
        val bSpk = OSHICryptoV2.generateX25519()
        val bundle = FetchedBundle(
            identityKey = bIdentity.pub, signingKey = ByteArray(32),
            signedPreKeyId = "spk-1", signedPreKey = bSpk.pub,
            oneTimePreKeyId = null, oneTimePreKey = null,
        )
        val aIdentity = OSHICryptoV2.generateX25519()
        val (aliceState, x3dh) = V2Session.initiator(aIdentity, bundle)
        val m1 = "no opk here".toByteArray()
        val (h1, c1) = OSHIRatchetV2.encrypt(aliceState, m1, ad)
        val bobState = V2Session.responder(bIdentity, bSpk, null, x3dh)
        assertArrayEquals(m1, OSHIRatchetV2.decrypt(bobState, h1, c1, ad))
    }
}
