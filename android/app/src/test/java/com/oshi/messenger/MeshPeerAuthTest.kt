package com.oshi.messenger

import com.oshi.messenger.network.mesh.MeshPeerAuth
import com.oshi.messenger.network.v2.OSHICryptoV2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-crypto tests for the mesh proof-of-possession challenge-response
 * (see MeshPeerAuth.kt). Mirrors iOS OSHITests/MeshPeerAuthTests.swift.
 *
 *   1. Round-trip: a peer holding K's private half produces a tag we accept.
 *   2. Wrong key fails: a peer using a DIFFERENT private key cannot forge an
 *      acceptable tag — the whole security property.
 *   3. Cross-platform vector: a FIXED (keys + nonce) → tag must match the exact
 *      bytes the iOS XCTest also pins. If iOS and Android ever disagree on the
 *      X25519 / HMAC framing, one of these two tests goes red instead of the
 *      mismatch only surfacing on two physical devices.
 *
 * NOTE: this exercises crypto only. LIVE mesh verification needs two physical
 * devices — BLE / TCP mesh does not run in the JVM/emulator.
 */
class MeshPeerAuthTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    private fun hexStr(d: ByteArray): String =
        d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `challenge-response round trip verifies`() {
        val alice = OSHICryptoV2.generateX25519()
        val bob = OSHICryptoV2.generateX25519()
        val nonce = MeshPeerAuth.makeNonce()
        assertEquals(MeshPeerAuth.NONCE_LENGTH, nonce.size)

        // Bob answers Alice's challenge with DH(bobPriv, alicePub).
        val bobSecret = MeshPeerAuth.sharedSecret(bob.priv, alice.pub)
        val tag = MeshPeerAuth.tag(bobSecret, nonce)

        // Alice verifies with DH(alicePriv, bobPub) — symmetry means it matches.
        val aliceSecret = MeshPeerAuth.sharedSecret(alice.priv, bob.pub)
        assertArrayEquals(bobSecret, aliceSecret)
        assertTrue(MeshPeerAuth.verify(tag, aliceSecret, nonce))
    }

    @Test
    fun `wrong key fails verification`() {
        val alice = OSHICryptoV2.generateX25519()
        val bob = OSHICryptoV2.generateX25519()
        val mallory = OSHICryptoV2.generateX25519()
        val nonce = MeshPeerAuth.makeNonce()

        // Mallory announces Bob's key but signs with her own private key.
        val forgedTag = MeshPeerAuth.tag(MeshPeerAuth.sharedSecret(mallory.priv, alice.pub), nonce)

        // Alice verifies against the key Mallory CLAIMED (Bob's). Must fail.
        val aliceSecret = MeshPeerAuth.sharedSecret(alice.priv, bob.pub)
        assertFalse(MeshPeerAuth.verify(forgedTag, aliceSecret, nonce))
    }

    /** FIXED cross-platform vector — must stay byte-identical to iOS. */
    @Test
    fun `cross platform vector matches iOS`() {
        // A_priv = 01..20, B_pub derived from B_priv = 21..40, nonce = 0xAB * 32.
        val aPriv = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
        val bPub = hex("5869aff450549732cbaaed5e5df9b30a6da31cb0e5742bad5ad4a1a768f1a67b")
        val nonce = ByteArray(32) { 0xAB.toByte() }
        val expectedShared = "a84dc7c3c8f058b1b2dc4cd1e9b5dc0a7987f88b6a9564cde3391fc421159e77"
        val expectedTag = "d31d2641e11190fd13c4ae33c41eaad7356164f3dd4a602eb5cf36a9ade6ee1b"

        val secret = MeshPeerAuth.sharedSecret(aPriv, bPub)
        assertEquals(expectedShared, hexStr(secret))

        val tag = MeshPeerAuth.tag(secret, nonce)
        assertEquals(expectedTag, hexStr(tag))
    }

    @Test
    fun `constant time equals rejects length mismatch`() {
        assertFalse(MeshPeerAuth.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertTrue(MeshPeerAuth.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
    }
}
