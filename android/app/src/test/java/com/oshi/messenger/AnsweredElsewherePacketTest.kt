package com.oshi.messenger

import com.oshi.messenger.service.AnsweredElsewherePacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `callAnsweredElsewhere` (0x0A), against the Swift original.
 *
 * Reference:
 *  - body layout       VoiceCallManager.swift:7566-7571
 *  - transport         VoiceCallManager.swift:7683-7718
 *  - own-echo refusal  VoiceCallManager.swift:7069-7072 / :6437-6440
 *
 * What was broken: the Android packet was a 9-byte plaintext `[0x0A][ts]` with no
 * deviceId at all, sent to the PEER on the audio channel. Multi-device dismissal
 * never worked, and the only thing stopping the peer from hanging up on it was an
 * unrelated `size > 9` filter in VPSClient.
 */
class AnsweredElsewherePacketTest {

    private val deviceA = "11111111-2222-3333-4444-555555555555"
    private val deviceB = "99999999-8888-7777-6666-555555555555"
    private val callId = "0A1B2C3D-4E5F-6071-8293-A4B5C6D7E8F9"

    @Test
    fun `layout matches the Swift offsets`() {
        val ts = 0x0000_0193_ABCD_EF01L
        val packet = AnsweredElsewherePacket.build(ts, deviceA, callId)

        assertEquals("type byte", 0x0A.toByte(), packet[0])
        // [type(1)][timestamp BE8] — timestamp big-endian, byte 1 is the MSB.
        var decoded = 0L
        for (i in 1..8) decoded = (decoded shl 8) or (packet[i].toLong() and 0xFF)
        assertEquals("timestamp BE8", ts, decoded)
        assertEquals("deviceIdLen at offset 9", deviceA.length, packet[9].toInt() and 0xFF)
        assertEquals(
            "total size",
            9 + 1 + deviceA.toByteArray().size + callId.toByteArray().size,
            packet.size
        )
    }

    @Test
    fun `round trips deviceId and callId`() {
        val packet = AnsweredElsewherePacket.build(1L, deviceA, callId)
        assertEquals(deviceA, AnsweredElsewherePacket.deviceIdOf(packet))
        assertEquals(callId, AnsweredElsewherePacket.callIdOf(packet))
    }

    /**
     * The packet is addressed to our OWN public key, so the VPS fan-out hands it
     * straight back to the device that sent it. Without this guard that device ends
     * the call it has just answered.
     */
    @Test
    fun `own echo does not end the call`() {
        val packet = AnsweredElsewherePacket.build(1L, deviceA, callId)
        assertFalse(AnsweredElsewherePacket.endsThisDevicesCall(packet, myDeviceId = deviceA))
    }

    @Test
    fun `a sibling device does end the call`() {
        val packet = AnsweredElsewherePacket.build(1L, deviceA, callId)
        assertTrue(AnsweredElsewherePacket.endsThisDevicesCall(packet, myDeviceId = deviceB))
    }

    /**
     * The 9-byte packet Android used to emit. It carries no deviceId, so it can only
     * have come from another device — it must still dismiss, and it must not crash
     * the parser.
     */
    @Test
    fun `legacy nine byte packet parses as unknown sender and still dismisses`() {
        val legacy = ByteArray(9).also { it[0] = 0x0A }
        assertNull(AnsweredElsewherePacket.deviceIdOf(legacy))
        assertNull(AnsweredElsewherePacket.callIdOf(legacy))
        assertTrue(AnsweredElsewherePacket.endsThisDevicesCall(legacy, myDeviceId = deviceA))
    }

    @Test
    fun `truncated deviceId is refused rather than read out of bounds`() {
        val full = AnsweredElsewherePacket.build(1L, deviceA, callId)
        val cut = full.copyOfRange(0, 12)   // len says 36, only 2 bytes follow
        assertNull(AnsweredElsewherePacket.deviceIdOf(cut))
    }

    @Test
    fun `a packet of another type is not parsed`() {
        val notOurs = AnsweredElsewherePacket.build(1L, deviceA, callId).copyOf()
        notOurs[0] = 0x0C   // videoPaused
        assertNull(AnsweredElsewherePacket.deviceIdOf(notOurs))
    }

    @Test
    fun `an empty callId leaves the deviceId readable`() {
        val packet = AnsweredElsewherePacket.build(1L, deviceA, "")
        assertEquals(deviceA, AnsweredElsewherePacket.deviceIdOf(packet))
        assertNull(AnsweredElsewherePacket.callIdOf(packet))
    }

    // ------------------------------------------------------------------
    // dismisses() — the guard set shared by the 0x0A packet leg and the
    // callAnsweredElsewhere FCM push leg. Round 5.
    // ------------------------------------------------------------------

    private fun dismisses(
        sender: String? = deviceB,
        me: String = deviceA,
        ringing: Boolean = true,
        theirCall: String? = callId,
        ourCall: String? = callId
    ) = AnsweredElsewherePacket.dismisses(sender, me, ringing, theirCall, ourCall)

    @Test
    fun `a ringing device dismisses a sibling's signal for the same call`() {
        assertTrue(dismisses())
    }

    /** iOS VoiceCallManager.swift:7069-7072 — the fan-out hands it back to the sender. */
    @Test
    fun `own device id never dismisses, on either leg`() {
        assertFalse(dismisses(sender = deviceA, me = deviceA))
    }

    /**
     * iOS `guard callState == .ringing || incomingCall != nil`,
     * VoiceCallManager.swift:7511-7514. Talking to Alice on the phone while the tablet
     * declines Bob must NOT end the Alice call.
     */
    @Test
    fun `a live call is not torn down by a sibling dismissing another call`() {
        assertFalse(dismisses(ringing = false))
    }

    /**
     * Stricter than iOS, which carries the callId but never compares it. Android
     * reuses RINGING for the OUTGOING ringing state too, so the state guard alone
     * would still let a sibling kill a call we are placing.
     */
    @Test
    fun `a signal about a different call is refused`() {
        assertFalse(dismisses(theirCall = "SOME-OTHER-CALL"))
    }

    /**
     * Leniency is deliberate and one-directional: unknown on EITHER side ⇒ honour it.
     * A false negative here leaves a phone ringing.
     */
    @Test
    fun `an unknown callId on either side still dismisses`() {
        assertTrue("legacy peer sends no callId", dismisses(theirCall = null))
        assertTrue("empty callId is the same as absent", dismisses(theirCall = ""))
        assertTrue("we have not learned our callId yet", dismisses(ourCall = null))
        assertTrue(dismisses(ourCall = ""))
    }

    /** An old peer's 9-byte body carries no deviceId; it can only be from elsewhere. */
    @Test
    fun `an absent sender device id still dismisses`() {
        assertTrue(dismisses(sender = null))
        assertTrue(dismisses(sender = ""))
    }

    /** Order of the guards must not matter: not ringing wins over everything. */
    @Test
    fun `not ringing refuses even a perfectly matching signal`() {
        assertFalse(dismisses(ringing = false, theirCall = callId, ourCall = callId))
    }

    @Test
    fun `deviceId bytes sit exactly where Swift reads them`() {
        val packet = AnsweredElsewherePacket.build(1L, deviceA, callId)
        val len = packet[9].toInt() and 0xFF
        assertArrayEquals(
            deviceA.toByteArray(Charsets.UTF_8),
            packet.copyOfRange(10, 10 + len)
        )
    }
}
