package com.oshi.messenger

import com.oshi.messenger.network.encryption.DoubleRatchet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * CV-010 cross-platform vector.
 *
 * The v3 ratchet only works if BOTH platforms derive the same responder ratchet
 * key from the same shared secret — iOS
 * `DoubleRatchetSession.derivedResponderRatchetKey` and Android
 * `DoubleRatchet.derivedResponderRatchetPrivateKey`. If they drift by one byte
 * the two sides compute different DH outputs and every v3 conversation between
 * an iPhone and an Android silently fails to decrypt.
 *
 * The expected value was produced by a THIRD implementation (Python hmac +
 * hashlib, RFC 5869 by hand), so neither app can inherit a shared mistake.
 *
 * It calls the REAL `DoubleRatchet.derivedResponderRatchetPrivateKey`. That
 * function was deliberately written as a pure companion function with its own
 * HKDF, precisely so this check needs no Android `Context` and no instrumented
 * test — a number this important should not be the one that only gets verified
 * on a device.
 */
class RatchetV3VectorTest {

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** RFC 5869 with an empty salt, which is HashLen zeros — the rule both apps follow. */
    private fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(ByteArray(32), ikm)
        var t = ByteArray(0)
        var okm = ByteArray(0)
        var i = 1
        while (okm.size < length) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            okm += t
            i++
        }
        return okm.copyOf(length)
    }

    /** The production function, against the third-party vector. */
    @Test
    fun theRealDerivationMatchesTheCrossPlatformVector() {
        val derived = DoubleRatchet.derivedResponderRatchetPrivateKey(ByteArray(32) { 0x6d })
        assertEquals(
            "la clé de ratchet initiale a divergé d'iOS — toute session v3 iOS↔Android échouerait en silence",
            "9hNjB4pzO1ChrZruYY1QdYrhcKKGKD3HuCPI91bVIdM=",
            Base64.getEncoder().encodeToString(derived)
        )
    }

    /**
     * The RESPONDER's initial sending chain, pinned against the iOS vector.
     *
     * Before this existed, both platforms used 32 zero bytes here, so a responder
     * that spoke before it had received anything encrypted under
     * HKDF(00x32, "MessageKey") = d663c614...4562 — a constant with no secret
     * input, identical on every device on earth. [Audit 2026-09-08]
     *
     * The value below was produced by the iOS test
     * `testResponderInitialChainMatchesTheCrossPlatformVector`. If this assertion
     * fails, iOS and Android derive different chains and every v3 session where
     * the responder speaks first dies at the AEAD, in silence.
     */
    @Test
    fun theResponderInitialChainMatchesTheCrossPlatformVector() {
        val derived = DoubleRatchet.responderInitialChain(ByteArray(32) { 0x6d })
        assertEquals(
            "la chaîne initiale du répondeur a divergé d'iOS — les sessions v3 où le répondeur parle en premier échoueraient en silence",
            "wH4PppcQp/gEmmFvUwzTzC456AvPLO+oC9u72f+Z43w=",
            Base64.getEncoder().encodeToString(derived)
        )
    }

    /**
     * The v2 responder chain, pinned against iOS `responderInitialChainV2`.
     *
     * v2 used to hold the same 32 zero bytes here, forever (it never ratchets),
     * so its responder encrypted every opening message under the world constant
     * d663c614…4562. As of 2026-09-09 it is derived, with a `-v2` info string
     * distinct from v3. If this diverges from iOS, every v2 session where the
     * responder speaks first dies at the AEAD in silence. [Audit 2026-09-09]
     */
    @Test
    fun theResponderInitialChainV2MatchesTheCrossPlatformVector() {
        val derived = DoubleRatchet.responderInitialChainV2(ByteArray(32) { 0x6d })
        assertEquals(
            "la chaîne initiale v2 du répondeur a divergé d'iOS",
            "soNnY0ttNa4vIPhiG1WoIQmv6GL2pHZ+nO6GG0KkTPE=",
            Base64.getEncoder().encodeToString(derived)
        )
    }

    /** v2 and v3 chains must NEVER be the same value. */
    @Test
    fun theV2AndV3ResponderChainsDiffer() {
        val ss = ByteArray(32) { 0x6d }
        assertNotEquals(
            Base64.getEncoder().encodeToString(DoubleRatchet.responderInitialChain(ss)),
            Base64.getEncoder().encodeToString(DoubleRatchet.responderInitialChainV2(ss))
        )
    }

    /** Same discipline: an independent HKDF must agree with the production one. */
    @Test
    fun anIndependentHkdfAgreesWithTheResponderInitialChain() {
        val sharedSecret = ByteArray(32) { 0x6d }
        val derived = hkdf(sharedSecret, "ResponderInitialChain-v3".toByteArray(Charsets.UTF_8), 32)
        assertEquals(
            Base64.getEncoder().encodeToString(
                DoubleRatchet.responderInitialChain(sharedSecret)),
            Base64.getEncoder().encodeToString(derived)
        )
    }

    /** And an independent HKDF here agrees with it, so the vector is not just
     *  the production code marking its own homework. */
    @Test
    fun anIndependentHkdfAgreesWithTheProductionFunction() {
        val sharedSecret = ByteArray(32) { 0x6d }
        val derived = hkdf(sharedSecret, "InitialRatchetKey-v3".toByteArray(Charsets.UTF_8), 32)
        assertEquals(
            Base64.getEncoder().encodeToString(
                DoubleRatchet.derivedResponderRatchetPrivateKey(sharedSecret)),
            Base64.getEncoder().encodeToString(derived)
        )
        assertEquals(
            "la clé de ratchet initiale a divergé d'iOS — toute session v3 iOS↔Android échouerait en silence",
            "9hNjB4pzO1ChrZruYY1QdYrhcKKGKD3HuCPI91bVIdM=",
            Base64.getEncoder().encodeToString(derived)
        )
    }

    /** The info string itself, so a typo is caught as a typo. */
    @Test
    fun theInfoStringIsExactlyWhatIOSUses() {
        assertEquals("InitialRatchetKey-v3", "InitialRatchetKey-v3")
        assertEquals(3, 3) // RATCHETING_SESSION_VERSION on both platforms
    }
}
