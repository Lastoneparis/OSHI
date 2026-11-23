package com.oshi.messenger

import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.OSHIRatchetV2
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Self-consistency of the Double Ratchet state machine. The KDFs/AEAD it rides on
 * are already proven byte-identical to iOS (V2CryptoVectorTest); combined with a
 * clean Alice<->Bob round-trip (in-order, DH-ratchet reply, and out-of-order),
 * an Android ratchet session interoperates with an iOS one.
 */
class OSHIRatchetV2Test {

    private val ad = "oshi-ad-v2".toByteArray(Charsets.UTF_8)

    private fun session(): Pair<OSHIRatchetV2.State, OSHIRatchetV2.State> {
        val aIk = OSHICryptoV2.generateX25519(); val aEk = OSHICryptoV2.generateX25519()
        val bIk = OSHICryptoV2.generateX25519(); val bSpk = OSHICryptoV2.generateX25519()
        val bOpk = OSHICryptoV2.generateX25519()
        val skA = OSHICryptoV2.x3dhInitiator(aIk, aEk, bIk.pub, bSpk.pub, bOpk.pub)
        val skB = OSHICryptoV2.x3dhResponder(bIk, bSpk, bOpk, aIk.pub, aEk.pub)
        assertArrayEquals("X3DH SK must agree", skA, skB)
        // Bob's initial ratchet pair is his signed-prekey pair.
        val alice = OSHIRatchetV2.initAlice(skA, bSpk.pub)
        val bob = OSHIRatchetV2.initBob(skB, bSpk)
        return alice to bob
    }

    @Test
    fun `in-order messages round-trip both directions`() {
        val (alice, bob) = session()

        val m1 = "hello from alice".toByteArray()
        val (h1, c1) = OSHIRatchetV2.encrypt(alice, m1, ad)
        assertArrayEquals(m1, OSHIRatchetV2.decrypt(bob, h1, c1, ad))

        val m2 = "second from alice".toByteArray()
        val (h2, c2) = OSHIRatchetV2.encrypt(alice, m2, ad)
        assertArrayEquals(m2, OSHIRatchetV2.decrypt(bob, h2, c2, ad))

        // Bob now has a sending chain (from the DH ratchet on his first decrypt).
        val r1 = "reply from bob".toByteArray()
        val (h3, c3) = OSHIRatchetV2.encrypt(bob, r1, ad)
        assertArrayEquals(r1, OSHIRatchetV2.decrypt(alice, h3, c3, ad))
    }

    @Test
    fun `out-of-order delivery is recovered from skipped keys`() {
        val (alice, bob) = session()

        // Prime a chain so both sides share a sending/receiving chain.
        val (h0, c0) = OSHIRatchetV2.encrypt(alice, "prime".toByteArray(), ad)
        OSHIRatchetV2.decrypt(bob, h0, c0, ad)

        val m3 = "message three".toByteArray()
        val m4 = "message four".toByteArray()
        val (h3, c3) = OSHIRatchetV2.encrypt(alice, m3, ad)
        val (h4, c4) = OSHIRatchetV2.encrypt(alice, m4, ad)

        // Deliver #4 BEFORE #3 → bob skips and stores #3's key.
        assertArrayEquals(m4, OSHIRatchetV2.decrypt(bob, h4, c4, ad))
        // Now the late #3 decrypts from the skipped store.
        assertArrayEquals(m3, OSHIRatchetV2.decrypt(bob, h3, c3, ad))
    }

    @Test
    fun `a full back-and-forth conversation stays in sync`() {
        val (alice, bob) = session()
        repeat(6) { i ->
            val am = "a$i".toByteArray()
            val (ah, ac) = OSHIRatchetV2.encrypt(alice, am, ad)
            assertArrayEquals(am, OSHIRatchetV2.decrypt(bob, ah, ac, ad))
            val bm = "b$i".toByteArray()
            val (bh, bc) = OSHIRatchetV2.encrypt(bob, bm, ad)
            assertArrayEquals(bm, OSHIRatchetV2.decrypt(alice, bh, bc, ad))
        }
    }

    @Test
    fun `damaged first packet does not advance the ratchet`() {
        val (alice, bob) = session()
        val plain = "valid retransmission".toByteArray()
        val (header, ciphertext) = OSHIRatchetV2.encrypt(alice, plain, ad)
        val damaged = ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        try {
            OSHIRatchetV2.decrypt(bob, header, damaged, ad)
            org.junit.Assert.fail("damaged ciphertext accepted")
        } catch (_: javax.crypto.AEADBadTagException) { }
        assertArrayEquals(plain, OSHIRatchetV2.decrypt(bob, header, ciphertext, ad))
    }

    @Test
    fun `damaged out of order packet does not consume its skipped key`() {
        val (alice, bob) = session()
        val packets = (0..2).map { OSHIRatchetV2.encrypt(alice, "packet-$it".toByteArray(), ad) }
        OSHIRatchetV2.decrypt(bob, packets[2].first, packets[2].second, ad)
        val (header, ciphertext) = packets[0]
        val damaged = ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        try {
            OSHIRatchetV2.decrypt(bob, header, damaged, ad)
            org.junit.Assert.fail("damaged skipped packet accepted")
        } catch (_: javax.crypto.AEADBadTagException) { }
        assertArrayEquals("packet-0".toByteArray(), OSHIRatchetV2.decrypt(bob, header, ciphertext, ad))
        assertArrayEquals("packet-1".toByteArray(), OSHIRatchetV2.decrypt(bob, packets[1].first, packets[1].second, ad))
    }
}
