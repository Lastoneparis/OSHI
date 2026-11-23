package com.oshi.messenger

import com.oshi.messenger.service.CallOfferCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capability run trailing a call offer's callId.
 * Reference: `VoiceCallManager.swift:13034-13070 peelOfferCapabilities`.
 *
 * Offer body: `[type(1)][ts(8)][sessionKey(32)][salt(4)][callId][caps…]`, so the
 * callId starts at 45.
 */
class CallOfferCapabilitiesTest {

    private val callId = "0A1B2C3D-4E5F-6071-8293-A4B5C6D7E8F9"

    private fun body(vararg caps: Int): ByteArray =
        ByteArray(45) + callId.toByteArray(Charsets.UTF_8) + caps.map { it.toByte() }.toByteArray()

    private fun callIdOf(body: ByteArray, end: Int) =
        String(body, 45, end - 45, Charsets.UTF_8)

    @Test
    fun `no capability tail leaves the callId whole`() {
        val b = body()
        val p = CallOfferCapabilities.peel(b, 45)
        assertEquals(callId, callIdOf(b, p.callIdEnd))
        assertFalse(p.oshiCodec)
        assertFalse(p.pcm16k)
        assertFalse(p.appleCodec)
    }

    @Test
    fun `three slots are read positionally in append order`() {
        val b = body(0x01, 0x02, 0x04)
        val p = CallOfferCapabilities.peel(b, 45)
        assertEquals(callId, callIdOf(b, p.callIdEnd))
        assertTrue(p.oshiCodec)
        assertTrue(p.pcm16k)
        assertTrue(p.appleCodec)
    }

    @Test
    fun `a two byte tail from an older peer still reads correctly`() {
        val b = body(0x01, 0x00)
        val p = CallOfferCapabilities.peel(b, 45)
        assertEquals(callId, callIdOf(b, p.callIdEnd))
        assertTrue(p.oshiCodec)
        assertFalse(p.pcm16k)
    }

    /**
     * THE reason positional beats `contains()`. A peer that advertises no OshiCodec
     * but does advertise 16 kHz PCM sends `[0x00][0x02]`. `contains(0x02)` and
     * `slot(1) == 0x02` agree here — but `[0x02][0x00]`, which is what a future flag
     * whose value collides with an earlier slot's would look like, does not: slot(0)
     * is not OshiCodec, while `contains(0x02)` would still claim 16 kHz PCM in the
     * wrong slot.
     */
    @Test
    fun `a flag in the wrong slot is not credited to another capability`() {
        val b = body(0x02, 0x00)
        val p = CallOfferCapabilities.peel(b, 45)
        assertFalse("0x02 in slot 0 is not OshiCodec", p.oshiCodec)
        assertFalse("slot 1 is 0x00, so no 16 kHz PCM", p.pcm16k)
    }

    /**
     * iOS bounds the run at 8 bytes so "a malformed body must not be able to eat the
     * whole callId" (swift:13060). Without the bound an all-low-bytes body yields
     * `callId = ""` — and a wrong callId changes its SHA-256, so the two ends derive
     * different P2P cids and the call connects with zero audio.
     */
    @Test
    fun `an all-low-byte body cannot eat the whole callId`() {
        val malformed = ByteArray(45) + ByteArray(40) { 0x01 }
        val p = CallOfferCapabilities.peel(malformed, 45)
        assertEquals("peel stops after 8 bytes", malformed.size - 8, p.callIdEnd)
        assertTrue("callId must not be emptied", p.callIdEnd > 45)
    }

    @Test
    fun `peel never runs past the callId start`() {
        val onlyCaps = ByteArray(45) + byteArrayOf(0x01, 0x02)
        val p = CallOfferCapabilities.peel(onlyCaps, 45)
        assertEquals(45, p.callIdEnd)
    }
}
