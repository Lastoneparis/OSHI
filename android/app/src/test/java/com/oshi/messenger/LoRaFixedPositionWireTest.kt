package com.oshi.messenger

import com.oshi.messenger.service.lora.LoRaPacketParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * __LORA_FIXED_POSITION_2026_10_28__ The admin frame that actually puts this node on other
 * radios' maps, pinned to bytes computed OUTSIDE this codebase.
 *
 * This test exists because of a defect that every other kind of test missed. The beacon
 * shipped this morning sent a POSITION_APP packet to the broadcast address; it had unit
 * tests, a policy digest matching two other platforms, and a payload checked against an
 * independent Python encoding of `Position`. All of that was correct, and the frame reached
 * no other node: measured with two ThinkNode M1s in one room, ten of them over four minutes
 * arrived nowhere while a text broadcast from the same radio in the same second arrived in
 * 3 seconds. The firmware owns a node's position — a client sets it with
 * `AdminMessage{ set_fixed_position }`, field 41, and the node announces on its own clock.
 *
 * So the expected hex below is again from Python, and the SAME literals are asserted on the
 * desktop (`LoRaFixedPositionAdminWireTest`) and on iOS (`LoRaFixedPositionWireTests`).
 * Three clients, one set of bytes.
 */
class LoRaFixedPositionWireTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `set_fixed_position is field 41 wrapping a Position`() {
        // ca02 = field 41, wire type 2. 0a = the Position submessage is 10 bytes.
        assertEquals(
            "ca020a0d603b8a1b15c060a903",
            hex(LoRaPacketParser.setFixedPositionPayload(46.2044, 6.1432)!!),
        )
    }

    @Test
    fun `a southern and eastern point keeps its signs through the admin wrapper`() {
        assertEquals(
            "ca020a0d0008d0eb1548b5205a",
            hex(LoRaPacketParser.setFixedPositionPayload(-33.8688, 151.2093)!!),
        )
    }

    @Test
    fun `remove_fixed_position is field 42, a bool`() {
        assertEquals("d00201", hex(LoRaPacketParser.removeFixedPositionPayload()))
    }

    @Test
    fun `a position that is not on Earth is refused rather than framed`() {
        // Null, not a frame. This writes the node's CONFIGURATION, so a 0/0 would park the
        // radio in the Gulf of Guinea persistently — worse than a bad broadcast, which at
        // least stops when the sender does.
        assertNull(LoRaPacketParser.setFixedPositionPayload(0.0, 0.0))
        assertNull(LoRaPacketParser.setFixedPositionPayload(91.0, 6.0))
        assertNull(LoRaPacketParser.setFixedPositionPayload(46.0, 181.0))
        assertNotNull("Greenwich is a real place", LoRaPacketParser.setFixedPositionPayload(51.4779, 0.0))
    }

    @Test
    fun `the admin port is 6`() {
        assertEquals(6L, LoRaPacketParser.PORT_ADMIN)
    }
}
