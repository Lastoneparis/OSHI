package com.oshi.messenger

import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.OSHIRatchetV2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __PANIC_WIPE_2026_11_03__ Can the two of you still talk after one of you
 * panics?
 *
 * The panic wipe destroys this device's ratchet state. The other phone does not
 * know that and goes on writing under the session it still holds. This file
 * drives the REAL crypto — `OSHICryptoV2` X3DH and `OSHIRatchetV2` — through
 * exactly that, in both directions and for both "one side reset" and "both
 * sides reset", because the question is not whether the code reads correctly
 * but whether the keys still agree afterwards.
 *
 * What it establishes, in one line: a fresh X3DH fully restores BOTH
 * directions, and the peer's surviving old session is not merely useless but
 * actively undecryptable — which is why `RatchetHealSignal` has to exist and
 * why the wipe must take `v2_prekeys` with it (see `PanicWipePolicyTest`).
 */
class PanicRatchetRecoveryTest {

    private val ad = "oshi-ad-v2".toByteArray(Charsets.UTF_8)

    /** Bob's published bundle: long-lived identity + signed prekey, one-time prekeys. */
    private class Bundle {
        val ik = OSHICryptoV2.generateX25519()
        val spk = OSHICryptoV2.generateX25519()
        /** One-time prekeys are burned on use, so a re-handshake takes the next. */
        val opks = List(3) { OSHICryptoV2.generateX25519() }
    }

    /**
     * One X3DH handshake, returning both ratchet states.
     *
     * `opkIndex` matters: a second handshake with the same peer must consume a
     * DIFFERENT one-time prekey, which is the thing the server's prekey pool
     * exists to supply and the thing a panic wipe forces to be republished.
     */
    private fun handshake(bob: Bundle, opkIndex: Int): Pair<OSHIRatchetV2.State, OSHIRatchetV2.State> {
        val aIk = OSHICryptoV2.generateX25519()
        val aEk = OSHICryptoV2.generateX25519()
        val opk = bob.opks[opkIndex]
        val skA = OSHICryptoV2.x3dhInitiator(aIk, aEk, bob.ik.pub, bob.spk.pub, opk.pub)
        val skB = OSHICryptoV2.x3dhResponder(bob.ik, bob.spk, opk, aIk.pub, aEk.pub)
        assertArrayEquals("X3DH must agree or nothing below means anything", skA, skB)
        return OSHIRatchetV2.initAlice(skA, bob.spk.pub) to OSHIRatchetV2.initBob(skB, bob.spk)
    }

    private fun send(from: OSHIRatchetV2.State, to: OSHIRatchetV2.State, text: String) {
        val (header, cipher) = OSHIRatchetV2.encrypt(from, text.toByteArray(), ad)
        assertArrayEquals(text.toByteArray(), OSHIRatchetV2.decrypt(to, header, cipher, ad))
    }

    // ------------------------------------------------------ one side resets

    @Test
    fun `after one side panics, a fresh handshake restores BOTH directions`() {
        val bob = Bundle()
        val (alice1, bob1) = handshake(bob, opkIndex = 0)

        // A normal conversation first, so the states are genuinely advanced and
        // not fresh out of init — a reset from message #1 would prove less.
        send(alice1, bob1, "see you at the square")
        send(alice1, bob1, "bring water")
        send(bob1, alice1, "on my way")

        // 🔴 Alice triple-taps. Her ratchet state, prekeys and sessions are gone.
        // `alice1` is dead from here on and deliberately never touched again.

        // She re-onboards and the app re-runs X3DH against Bob's bundle, taking
        // the NEXT one-time prekey. Bob adopts the fresh session while
        // decrypting her first message, because it carries the X3DH header.
        val (alice2, bob2) = handshake(bob, opkIndex = 1)

        // Alice -> Bob works again.
        send(alice2, bob2, "it's me again, I wiped my phone")
        // And Bob -> Alice, which is the direction that was broken: Bob only
        // gains a sending chain after the DH ratchet on his first decrypt.
        send(bob2, alice2, "understood, you're back")
        // Both ways, repeatedly, not just the first message each way.
        send(alice2, bob2, "still here?")
        send(bob2, alice2, "still here")
    }

    @Test
    fun `the peer's surviving old session cannot read the new one — this is why the heal signal exists`() {
        val bob = Bundle()
        val (alice1, bob1) = handshake(bob, opkIndex = 0)
        send(alice1, bob1, "before")

        // Alice panics and re-handshakes; Bob has NOT yet adopted it.
        val (alice2, _) = handshake(bob, opkIndex = 1)
        val (header, cipher) = OSHIRatchetV2.encrypt(alice2, "after".toByteArray(), ad)

        // Bob's OLD state cannot open it. Not "returns empty", not "returns
        // garbage" — it throws. That is the 8-failed-decrypt path that makes
        // V2MessageRouter give up on the envelope and send __RATCHET_HEAL__v1.
        assertThrows(Exception::class.java) {
            OSHIRatchetV2.decrypt(bob1, header, cipher, ad)
        }
    }

    @Test
    fun `a message written under the old session is lost to the side that panicked`() {
        val bob = Bundle()
        val (alice1, bob1) = handshake(bob, opkIndex = 0)
        send(alice1, bob1, "prime so bob has a sending chain")

        // Bob replies, unaware. Alice has already wiped: the only state that
        // could read this is gone, so the message is unrecoverable and the
        // notification without a message is the user-visible form.
        val (header, cipher) = OSHIRatchetV2.encrypt(bob1, "did you see this?".toByteArray(), ad)

        val (alice2, _) = handshake(bob, opkIndex = 1)
        assertThrows(Exception::class.java) {
            OSHIRatchetV2.decrypt(alice2, header, cipher, ad)
        }
        // The cure is not to recover this ciphertext — it cannot be recovered —
        // but for the resend list on the heal signal to ask for it again.
        assertTrue(
            "the heal signal must be able to carry the ids to re-request",
            com.oshi.messenger.network.v2.RatchetHealSignal.MAX_RESEND_PER_REQUEST > 0
        )
    }

    // ----------------------------------------------------- both sides reset

    @Test
    fun `when BOTH sides panic, a fresh handshake still works`() {
        val bobOld = Bundle()
        val (alice1, bob1) = handshake(bobOld, opkIndex = 0)
        send(alice1, bob1, "both of us are about to wipe")

        // Both phones are wiped. Bob's identity is gone too, so he re-onboards
        // with an entirely NEW bundle — in OSHI the address IS the identity
        // key, so this is a different address and Alice must re-add him. That
        // is panic working as designed, not a ratchet fault.
        val bobNew = Bundle()
        assertNotEquals(
            "a wiped identity must not come back with the same key",
            bobOld.ik.pub.toList(), bobNew.ik.pub.toList()
        )

        val (alice2, bob2) = handshake(bobNew, opkIndex = 0)
        send(alice2, bob2, "fresh start")
        send(bob2, alice2, "fresh start back")
    }

    @Test
    fun `a re-handshake burns a different one-time prekey`() {
        // Why the wipe MUST clear v2_prekeys: if `hasPublished()` survived, no
        // fresh pool would be published, the server would keep handing out the
        // old one-time prekeys, and their private halves died with the wipe.
        // Every later handshake would then derive a secret this device cannot
        // reproduce — permanently undecryptable, and indistinguishable from a
        // network fault.
        val bob = Bundle()
        val first = bob.opks[0].pub.toList()
        val second = bob.opks[1].pub.toList()
        assertNotEquals(first, second)

        val (a1, b1) = handshake(bob, opkIndex = 0)
        val (a2, b2) = handshake(bob, opkIndex = 1)
        send(a1, b1, "session one")
        send(a2, b2, "session two")
        // Two live sessions with the same peer derive different roots, so one
        // cannot read the other's traffic.
        val (h, c) = OSHIRatchetV2.encrypt(a2, "only session two".toByteArray(), ad)
        assertThrows(Exception::class.java) { OSHIRatchetV2.decrypt(b1, h, c, ad) }
    }

    @Test
    fun `the wipe leaves no prekey state that could suppress a republish`() {
        // Belt to the braces in PanicWipePolicyTest: the three preference files
        // whose survival would each produce a silent, permanent failure.
        for (prefs in listOf("v2_prekeys", "oshi_ratchet", "oshi_identity_backup")) {
            assertEquals(
                "$prefs must not survive a panic wipe",
                true,
                com.oshi.messenger.util.PrefsWipePolicy.shouldWipe(prefs)
            )
        }
    }
}
