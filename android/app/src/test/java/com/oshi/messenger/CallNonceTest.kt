package com.oshi.messenger

import com.oshi.messenger.service.audio.CallNonce
import com.oshi.messenger.service.audio.NonceReplayWindow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Proves the AES-GCM nonce layouts Android transmits match the shipping iOS
 * client byte for byte, and that the two nonce-reuse breaks the 2026-08-13 audit
 * found (A-1 audio, A-2 video) can no longer occur.
 *
 * iOS reference:
 *   audio salt derivation  — VoiceCallManager.swift:3019-3034
 *   audio nonce layout     — VoiceCallManager.swift:12482-12491
 *   video salt + counter   — VideoCallManager.swift:293-322
 *   video replay window    — VideoCallManager.swift:332-384
 */
class CallNonceTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ── AUDIO: per-direction salt ───────────────────────────────────────────

    /** iOS VoiceCallManager.swift:3022 — `s[0] ^= 0xA5`, other bytes untouched. */
    @Test
    fun responderSaltFlipsOnlyTheFirstByteWith0xA5() {
        val base = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val responder = CallNonce.responderNonceSalt(base)
        assertEquals("b4223344", hex(responder))
        // base must not be mutated in place
        assertEquals("11223344", hex(base))
    }

    @Test
    fun responderSaltIsAlwaysDifferentFromBase() {
        // 0xA5 XOR is never the identity, for any first byte.
        for (b0 in 0..255) {
            val base = byteArrayOf(b0.toByte(), 0, 0, 0)
            assertNotEquals(hex(base), hex(CallNonce.responderNonceSalt(base)))
        }
    }

    /**
     * THE BUG (audit A-1): both ends used the shared salt AND both counters start
     * at 1 under the same key, so packet n in each direction had a bit-identical
     * (key, nonce) pair. With the directional split, initiator and responder
     * nonces differ at every counter value.
     */
    @Test
    fun audioNoncesAreDistinctPerDirectionAtEveryCounter() {
        val shared = byteArrayOf(0x7A, 0x0B.toByte(), 0xCD.toByte(), 0xEF.toByte())
        val callerTx = CallNonce.audioTxSalt(shared, isInitiator = true)
        val calleeTx = CallNonce.audioTxSalt(shared, isInitiator = false)

        for (counter in 1L..5000L) {
            val a = CallNonce.audioNonce(callerTx, counter)
            val b = CallNonce.audioNonce(calleeTx, counter)
            assertNotEquals("counter=$counter collided across directions", hex(a), hex(b))
        }
    }

    /** Each side's rx salt is the other side's tx salt. */
    @Test
    fun audioTxAndRxSaltsAreMirrored() {
        val shared = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(
            CallNonce.audioTxSalt(shared, isInitiator = true),
            CallNonce.audioRxSalt(shared, isInitiator = false)
        )
        assertArrayEquals(
            CallNonce.audioTxSalt(shared, isInitiator = false),
            CallNonce.audioRxSalt(shared, isInitiator = true)
        )
    }

    /** No repeat within a session: the counter is strictly monotonic. */
    @Test
    fun audioNoncesNeverRepeatWithinASession() {
        val salt = CallNonce.audioTxSalt(byteArrayOf(9, 8, 7, 6), isInitiator = true)
        val seen = HashSet<String>()
        for (counter in 1L..20_000L) {
            assertTrue("repeat at counter=$counter", seen.add(hex(CallNonce.audioNonce(salt, counter))))
        }
    }

    /**
     * FIXTURE round-trip in the iOS layout: `[salt(4)][counter BE(8)]`.
     * The expected bytes below are the layout iOS's `encryptAudioData`
     * (VoiceCallManager.swift:12482-12491) writes for salt 0xDEADBEEF, counter 1.
     */
    @Test
    fun audioNonceMatchesIOSFixtureLayout() {
        val salt = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val nonce = CallNonce.audioNonce(salt, 1L)
        assertEquals("deadbeef0000000000000001", hex(nonce))

        // counter 0x0102030405060708 → big-endian, high byte first
        assertEquals(
            "deadbeef0102030405060708",
            hex(CallNonce.audioNonce(salt, 0x0102030405060708L))
        )
    }

    // ── VIDEO: direction bit + domain bit ───────────────────────────────────

    /** iOS VideoCallManager.swift:302 — caller sets bit 7 of byte 0, callee clears it. */
    @Test
    fun videoSaltCarriesTheDirectionBitInTheMsb() {
        val random = byteArrayOf(0x00, 0x11, 0x22, 0x33)
        val caller = CallNonce.videoTxSalt(random, weAreCaller = true)
        val callee = CallNonce.videoTxSalt(random, weAreCaller = false)
        assertEquals(0x80, caller[0].toInt() and 0xFF)
        assertEquals(0x00, callee[0].toInt() and 0xFF)
        // remaining 31 bits preserved
        assertEquals("112233", hex(caller.copyOfRange(1, 4)))
        assertEquals("112233", hex(callee.copyOfRange(1, 4)))
    }

    /** iOS VideoCallManager.swift:289,315 — bit 63 of the counter field is forced set. */
    @Test
    fun videoNonceForcesTheDomainBit() {
        val salt = CallNonce.videoTxSalt(byteArrayOf(0, 0, 0, 0), weAreCaller = false)
        val nonce = CallNonce.videoNonce(salt, 1L)
        assertEquals("000000008000000000000001", hex(nonce))
        // Domain bit makes video disjoint from audio under the same key even when
        // the salts happen to collide.
        val audio = CallNonce.audioNonce(salt, 1L)
        assertNotEquals(hex(audio), hex(nonce))
    }

    /**
     * THE BUG (audit A-2): `[counter LE(8)][0x00 ×4]` gave both Android peers
     * bit-identical nonces for fragment n. The direction bit makes the two
     * spaces disjoint even at identical counters.
     */
    @Test
    fun videoNoncesAreDistinctPerDirectionAtEveryCounter() {
        // Worst case for a collision: both sides drew the SAME 31 random bits.
        val random = byteArrayOf(0x5A, 0x5A, 0x5A, 0x5A)
        val callerSalt = CallNonce.videoTxSalt(random, weAreCaller = true)
        val calleeSalt = CallNonce.videoTxSalt(random, weAreCaller = false)
        for (counter in 1L..5000L) {
            assertNotEquals(
                "counter=$counter collided across directions",
                hex(CallNonce.videoNonce(callerSalt, counter)),
                hex(CallNonce.videoNonce(calleeSalt, counter))
            )
        }
    }

    @Test
    fun videoNoncesNeverRepeatWithinASession() {
        val salt = CallNonce.videoTxSalt(byteArrayOf(3, 1, 4, 1), weAreCaller = true)
        val seen = HashSet<String>()
        for (counter in 1L..20_000L) {
            assertTrue("repeat at counter=$counter", seen.add(hex(CallNonce.videoNonce(salt, counter))))
        }
    }

    /**
     * The old layout was `[counter LE(8)][0000]`. Prove the new one is NOT that,
     * so a regression to the broken layout fails loudly.
     */
    @Test
    fun videoNonceIsNotTheOldBrokenLayout() {
        val salt = CallNonce.videoTxSalt(byteArrayOf(0x12, 0x34, 0x56, 0x78), weAreCaller = true)
        val nonce = CallNonce.videoNonce(salt, 1L)
        val oldBroken = ByteArray(12).also { for (i in 0..7) it[i] = (1L shr (i * 8)).toByte() }
        assertNotEquals(hex(oldBroken), hex(nonce))
        // Old layout had 4 trailing zero bytes; new layout ends in the BE counter.
        assertEquals(0x01, nonce[11].toInt() and 0xFF)
    }

    // ── Decode round-trip against a fixture in the iOS envelope ─────────────

    /**
     * Full `[nonce(12)][ciphertext][tag(16)]` round-trip — the envelope both
     * platforms use (iOS VoiceCallManager.swift:12501 / VideoCallManager
     * `decryptPacket`). Decryption reads the nonce OFF THE WIRE, which is why
     * changing the transmitted nonce is invisible to any peer version.
     */
    @Test
    fun sealedBoxRoundTripsUsingTheWireNonceOnly() {
        val key = ByteArray(32) { it.toByte() }
        val shared = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val plaintext = "hello oshi".toByteArray()

        for (isInitiator in listOf(true, false)) {
            val txSalt = CallNonce.audioTxSalt(shared, isInitiator)
            val nonce = CallNonce.audioNonce(txSalt, 1L)

            val enc = Cipher.getInstance("AES/GCM/NoPadding")
            enc.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            val sealed = nonce + enc.doFinal(plaintext)

            // Receiver side: take the 12 nonce bytes straight off the wire.
            val wireNonce = sealed.copyOfRange(0, 12)
            val dec = Cipher.getInstance("AES/GCM/NoPadding")
            dec.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, wireNonce))
            assertArrayEquals(plaintext, dec.doFinal(sealed.copyOfRange(12, sealed.size)))
        }
    }

    @Test
    fun videoSealedBoxRoundTripsUsingTheWireNonceOnly() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val salt = CallNonce.videoTxSalt(byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D), weAreCaller = true)
        val plaintext = ByteArray(1100) { (it % 251).toByte() }

        val nonce = CallNonce.videoNonce(salt, 42L)
        val enc = Cipher.getInstance("AES/GCM/NoPadding")
        enc.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val sealed = nonce + enc.doFinal(plaintext)

        assertEquals(12 + plaintext.size + 16, sealed.size)

        val dec = Cipher.getInstance("AES/GCM/NoPadding")
        dec.init(
            Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, sealed.copyOfRange(0, 12))
        )
        assertArrayEquals(plaintext, dec.doFinal(sealed.copyOfRange(12, sealed.size)))
    }

    /**
     * Cross-direction ciphertext XOR attack is no longer possible: with the old
     * layout the two directions produced the same keystream, so
     * `c1 xor c2 == p1 xor p2`. Assert that no longer holds.
     */
    @Test
    fun crossDirectionCiphertextsNoLongerShareAKeystream() {
        val key = ByteArray(32) { 0x5A }
        val shared = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val p1 = ByteArray(64) { 0x01 }
        val p2 = ByteArray(64) { 0x02 }

        fun seal(salt: ByteArray, counter: Long, pt: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, CallNonce.audioNonce(salt, counter))
            )
            return c.doFinal(pt).copyOfRange(0, pt.size) // ciphertext without tag
        }

        val c1 = seal(CallNonce.audioTxSalt(shared, true), 1L, p1)
        val c2 = seal(CallNonce.audioTxSalt(shared, false), 1L, p2)

        val cipherXor = ByteArray(64) { (c1[it].toInt() xor c2[it].toInt()).toByte() }
        val plainXor = ByteArray(64) { (p1[it].toInt() xor p2[it].toInt()).toByte() }
        assertNotEquals(hex(plainXor), hex(cipherXor))
    }

    // ── Replay window ───────────────────────────────────────────────────────

    @Test
    fun replayWindowRejectsAnExactRepeatAndAcceptsFreshNonces() {
        val w = NonceReplayWindow()
        val salt = CallNonce.videoTxSalt(byteArrayOf(1, 2, 3, 4), weAreCaller = false)
        val n1 = CallNonce.videoNonce(salt, 1L)
        val n2 = CallNonce.videoNonce(salt, 2L)

        assertFalse(w.isReplay(n1))
        assertTrue(w.isReplay(n1))
        assertFalse(w.isReplay(n2))
        assertEquals(1L, w.dropCount)
    }

    /** iOS VideoCallManager.swift:352 — capped at 1024 entries, oldest evicted. */
    @Test
    fun replayWindowIsBoundedAt1024Entries() {
        val w = NonceReplayWindow()
        val salt = CallNonce.videoTxSalt(byteArrayOf(0, 0, 0, 1), weAreCaller = true)
        for (i in 1L..2000L) w.isReplay(CallNonce.videoNonce(salt, i))
        assertEquals(NonceReplayWindow.MAX_SEEN_NONCES, w.size())
        // The oldest nonce has rolled out of the window, so it is accepted again.
        assertFalse(w.isReplay(CallNonce.videoNonce(salt, 1L)))
    }

    /**
     * An OLD peer emits `[counter LE(8)][0000]`, whose bytes 4..11 are constant
     * zero. A counter-based window would read every packet as "counter 0" and
     * reject the whole stream; the whole-nonce window does not.
     * iOS VideoCallManager.swift:336-342.
     */
    @Test
    fun replayWindowAcceptsAnOldPeerLegacyNonceStream() {
        val w = NonceReplayWindow()
        for (counter in 0L..500L) {
            val legacy = ByteArray(12).also { for (i in 0..7) it[i] = (counter shr (i * 8)).toByte() }
            assertFalse("legacy counter=$counter wrongly dropped", w.isReplay(legacy))
        }
    }
}
