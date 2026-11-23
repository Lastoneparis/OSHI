package com.oshi.messenger

import com.oshi.messenger.service.lora.LoRaChannelSet
import com.oshi.messenger.service.lora.LoRaPacketParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __NODE_CHANNELS_WERE_DROPPED_2026_11_04__
 *
 * The node streams all eight of its channel slots during the ordinary
 * `wantConfig` handshake — the same handshake that already delivers `my_info`,
 * `node_info` and `config`. `parseFromRadio` had no case for field 10, so every
 * one of them was dropped. The app could WRITE the primary channel and never READ
 * a single slot, which is why "which mesh is my node on?" had no answer in the UI
 * and secondary channels were invisible. Nothing new had to be requested; it was
 * already arriving.
 *
 * Every expected byte string below was derived from the published protos
 * independently of the Kotlin, so a wrong field number fails here rather than
 * passing a round trip and confusing every real node:
 *
 *     FromRadio        { channel = 10 }
 *     Channel          { index = 1, settings = 2, role = 3 }
 *     ChannelSettings  { psk = 2, name = 3 }
 *     Channel.Role     { DISABLED = 0, PRIMARY = 1, SECONDARY = 2 }
 *     AdminMessage     { set_channel = 33 }
 *
 * Worked example for the SECONDARY frame, so it can be re-derived:
 *
 *     index = 1              field 1, wire 0 -> 08 01
 *     psk   = 32 zero bytes  field 2, wire 2 -> 12 20 00*32
 *     name  = "Manif"        field 3, wire 2 -> 1A 05 4D 61 6E 69 66
 *     ChannelSettings = 12 20 00*32 1A 05 4D 61 6E 69 66        (41 bytes)
 *     settings               field 2, wire 2 -> 12 29 <41 bytes>
 *     role  = SECONDARY      field 3, wire 0 -> 18 02
 */
class LoRaNodeChannelWireTest {

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** A SECONDARY slot as the node reports it. Derived above. */
    private val SECONDARY_FRAME =
        "08011229122000000000000000000000000000000000000000000000000000000000000000001a054d616e69661802"

    // --------------------------------------------------------------- reading

    @Test
    fun `a channel reported by the node is read in full`() {
        val c = LoRaPacketParser.parseChannel(hex(SECONDARY_FRAME))!!
        assertEquals(1, c.index)
        assertEquals("Manif", c.name)
        assertEquals(32, c.psk.size)
        assertTrue(c.isSecondary)
        assertFalse(c.isPrimary)
        assertFalse(c.isDisabled)
    }

    @Test
    fun `the channel arrives through parseFromRadio under field 10`() {
        // The actual defect: the bytes were reaching `parseFromRadio` and falling
        // through its `when` because field 10 had no branch.
        val fromRadio = hex("522f$SECONDARY_FRAME")
        val events = LoRaPacketParser.parseFromRadio(fromRadio)
        val update = events.filterIsInstance<LoRaPacketParser.FromRadio.ChannelUpdate>().single()
        assertEquals("Manif", update.channel.name)
    }

    @Test
    fun `a blank primary reads as the stock default`() {
        // index 0, empty psk-less settings carrying only an empty name, role PRIMARY.
        // What a node straight from the flasher reports.
        val frame = hex("0800120018 01".replace(" ", ""))
        val c = LoRaPacketParser.parseChannel(frame)!!
        assertEquals(0, c.index)
        assertEquals("", c.name)
        assertEquals("LongFast", c.displayName)
        assertTrue(c.isPrimary)
    }

    @Test
    fun `a DISABLED slot is still parsed, so the caller can drop it knowingly`() {
        // Seven of the eight slots are normally disabled. They must parse — the
        // manager needs to see index 4 come back DISABLED to remove a channel the
        // user just left — but they are not channels anyone is on.
        val c = LoRaPacketParser.parseChannel(hex("0804120018 00".replace(" ", "")))!!
        assertEquals(4, c.index)
        assertTrue(c.isDisabled)
    }

    @Test
    fun `an empty frame is not slot zero`() {
        // index defaults to 0 in protobuf, so an empty or unrelated frame would
        // otherwise materialise as a real PRIMARY channel named "" and overwrite
        // the one the node actually reported.
        assertNull(LoRaPacketParser.parseChannel(ByteArray(0)))
    }

    @Test
    fun `a slot index outside the hardware's eight is refused`() {
        // The firmware has eight slots. A frame claiming index 11 is corrupt, and
        // trusting it would put a channel in the UI that no node can hold.
        assertNull(LoRaPacketParser.parseChannel(hex("080b120018 02".replace(" ", ""))))
    }

    // --------------------------------------------------------------- writing

    @Test
    fun `writing a secondary slot puts the index and role on the wire`() {
        // Before this change `adminSetChannel` hard-coded index 0 and role
        // PRIMARY, so there was no way to write a secondary channel at all —
        // joining a link with two channels could only ever apply the first.
        assertEquals(
            "8a020f080212091201011a044f5348491802",
            hexOf(LoRaPacketParser.adminSetChannel(
                name = "OSHI",
                psk = byteArrayOf(1),
                index = 2,
                roleRaw = LoRaChannelSet.ROLE_SECONDARY,
            )),
        )
    }

    @Test
    fun `the default call still writes the primary slot, unchanged`() {
        // Every existing caller passes name and psk only. If the defaults were
        // wrong, setting the channel would quietly start writing a secondary and
        // the node would stay on its old mesh.
        val withDefaults = LoRaPacketParser.adminSetChannel("OSHI", byteArrayOf(1))
        val explicit = LoRaPacketParser.adminSetChannel(
            "OSHI", byteArrayOf(1), index = 0, roleRaw = LoRaChannelSet.ROLE_PRIMARY,
        )
        assertEquals(hexOf(explicit), hexOf(withDefaults))
    }

    @Test
    fun `disabling a slot is a role of zero with empty settings`() {
        // Needed so joining a two-channel link onto a node that held five leaves
        // the node with two, not with two new ones and three stale ones still
        // receiving.
        assertEquals(
            "8a0206080312001800",
            hexOf(LoRaPacketParser.adminDisableChannel(3)),
        )
    }

    @Test
    fun `a name too long for the firmware is clamped on the way out`() {
        val written = LoRaPacketParser.adminSetChannel("canal-beaucoup-trop-long", byteArrayOf(1))
        // Dig the name back out through the parser rather than eyeballing hex.
        val channelField = LoRaProtoTestHelp.firstLengthDelimited(written, 33)!!
        val parsed = LoRaPacketParser.parseChannel(channelField)!!
        assertTrue(
            "got '${parsed.name}'",
            parsed.name.toByteArray(Charsets.UTF_8).size <= 11,
        )
    }

    // ------------------------------------------------------------- identity

    @Test
    fun `two identical slots are equal, despite the ByteArray key`() {
        // `psk` is a ByteArray in a data class, compared by REFERENCE unless
        // overridden. Left alone, every handshake would look like a change and
        // the channel list would re-emit and re-render for ever.
        val a = LoRaPacketParser.NodeChannel(0, "OSHI", byteArrayOf(1, 2), 1)
        val b = LoRaPacketParser.NodeChannel(0, "OSHI", byteArrayOf(1, 2), 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `a different key makes it a different channel`() {
        assertFalse(
            LoRaPacketParser.NodeChannel(0, "OSHI", byteArrayOf(1), 1) ==
                LoRaPacketParser.NodeChannel(0, "OSHI", byteArrayOf(2), 1)
        )
    }

    // ------------------------------- broadcast vs private (MeshPacket.to/.channel)

    /**
     * __A_CHANNEL_BROADCAST_IS_NOT_A_PRIVATE_MESSAGE_2026_11_04__
     *
     * `MeshPacket.to` (field 2, fixed32) and `MeshPacket.channel` (field 3,
     * varint) were never parsed. So a message put on a PUBLIC channel — readable
     * by every radio in range, since the default key is published in Meshtastic's
     * own source — arrived indistinguishable from a private one and was shown in
     * an ordinary one-to-one thread. Someone at a protest could reasonably read
     * that as private and answer accordingly.
     *
     * Bytes derived from the protos, not from our writer:
     *
     *     from    = 0x11223344   field 1, wire 5 -> 0D 44 33 22 11
     *     to      = 0xFFFFFFFF   field 2, wire 5 -> 15 FF FF FF FF   (broadcast)
     *     channel = 2            field 3, wire 0 -> 18 02
     *     decoded                field 4, wire 2 -> 22 <len> ...
     *       portnum = 1 (TEXT)   field 1, wire 0 -> 08 01
     *       payload = "hi"       field 2, wire 2 -> 12 02 68 69
     */
    @Test
    fun `a message to the broadcast address is marked as a channel message`() {
        val decoded = "08011202" + "6869"
        val frame = hex("0D44332211" + "15FFFFFFFF" + "1802" + "22" + "%02x".format(decoded.length / 2) + decoded)
        val p = LoRaPacketParser.parseMeshPacket(frame)!!
        assertEquals(0x11223344u, p.from)
        assertTrue("to == broadcast must read as a channel message", p.isBroadcast)
        assertEquals("the secondary slot it arrived on", 2, p.channelIndex)
        assertEquals("hi", String(p.payload, Charsets.UTF_8))
    }

    @Test
    fun `a message addressed to one node is NOT a broadcast`() {
        // The distinction the UI hangs on. `to` is our own node number here.
        val decoded = "08011202" + "6869"
        val frame = hex("0D44332211" + "1578563412" + "22" + "%02x".format(decoded.length / 2) + decoded)
        val p = LoRaPacketParser.parseMeshPacket(frame)!!
        assertFalse(p.isBroadcast)
        assertEquals("absent channel means the primary", 0, p.channelIndex)
    }

    @Test
    fun `an absent to field does not read as a broadcast`() {
        // Protobuf omits zero, and 0 is not the broadcast address. Reading a
        // missing `to` as "broadcast" would relabel every private message as
        // public, which is the error in the safe direction but still a lie.
        val decoded = "08011202" + "6869"
        val frame = hex("0D44332211" + "22" + "%02x".format(decoded.length / 2) + decoded)
        val p = LoRaPacketParser.parseMeshPacket(frame)!!
        assertFalse(p.isBroadcast)
    }
}

/** Minimal field picker, so the test reads the wire rather than trusting hex. */
internal object LoRaProtoTestHelp {
    fun firstLengthDelimited(d: ByteArray, field: Int): ByteArray? =
        com.oshi.messenger.service.lora.LoRaProto.parseFields(d)
            .firstOrNull { it.number == field && it.wire == 2 }?.payload
}
