package com.oshi.messenger

import com.oshi.messenger.service.CallSignalGuards
import com.oshi.messenger.service.UDPRelayClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** __BARE_CALLEND_ATTESTED_2026_11_21__ a cleartext 0x04 on the media path may not hang up just any call. */
class BareMediaCallEndGuardTest {

    private val peer = "q83vEjRWeJq8_-0SNFZ4mrzv7RI0VniavO_tEjRWeJo"          // base64url, as the relay stamps it
    private val peerStored = "q83vEjRWeJq8/+0SNFZ4mrzv7RI0VniavO/tEjRWeJo="  // base64, as Android stores it
    private val callId = "12ba9315-0000-4000-8000-000000000001"

    private fun may(
        size: Int = 9,
        live: Boolean = true,
        sender: String? = peer,
        frameCallId: String? = callId,
        peerKey: String? = peerStored,
        active: String? = callId,
    ) = CallSignalGuards.mayEndFromBareMediaCallEnd(size, live, sender, frameCallId, peerKey, active)

    @Test fun `relay-attested bare 0x04 from the peer for this call ends it`() {
        assertTrue(may())
    }

    @Test fun `callId case and trailing NUL do not make it another call`() {
        assertTrue(may(frameCallId = callId.uppercase() + "\u0000"))
    }

    @Test fun `no relay attestation - P2P, mesh, WS, polled signal - never ends the call`() {
        assertFalse(may(sender = null, frameCallId = null))
    }

    @Test fun `a straggler stamped with the previous callId does not end the redial (12ba9315 shape)`() {
        assertFalse(may(frameCallId = "d5e5983d-0000-4000-8000-000000000000"))
    }

    @Test fun `a frame stamped with no callId is not this call`() {
        assertFalse(may(frameCallId = ""))
        assertFalse(may(active = ""))
    }

    @Test fun `someone other than the peer cannot hang up`() {
        assertFalse(may(sender = "AAAAEjRWeJq8_-0SNFZ4mrzv7RI0VniavO_tEjRWeJo"))
        assertFalse(may(sender = ""))
        assertFalse(may(peerKey = null))
    }

    @Test fun `only the exact legacy 9-byte shape counts`() {
        assertFalse(may(size = 8))
        assertFalse(may(size = 10))
        assertFalse(may(size = 64))
    }

    @Test fun `nothing to end when no call is live`() {
        assertFalse(may(live = false))
    }

    @Test fun `relay frame parse keeps the stamped sender and callId`() {
        val sender = peer.toByteArray(Charsets.UTF_8)
        val cid = callId.toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(0x04, 0, 0, 1, 0x9A.toByte(), 0, 0, 0, 1)
        val frame = byteArrayOf(0x01, sender.size.toByte()) + sender + byteArrayOf(cid.size.toByte()) + cid + payload
        val parsed = UDPRelayClient.parseInbound(frame)
        assertNotNull(parsed)
        parsed!!
        assertEquals(0x01.toByte(), parsed.type)
        assertEquals(peer, parsed.sender)
        assertEquals(callId, parsed.callId)
        assertArrayEquals(payload, parsed.payload)
        assertTrue(may(size = parsed.payload.size, sender = parsed.sender, frameCallId = parsed.callId))
    }

    @Test fun `truncated relay frames are dropped`() {
        assertNull(UDPRelayClient.parseInbound(byteArrayOf(0x01, 5, 1)))
        assertNull(UDPRelayClient.parseInbound(byteArrayOf(0x01, 1, 65)))
        assertNull(UDPRelayClient.parseInbound(byteArrayOf(0x01, 1, 65, 9, 1)))
    }
}
