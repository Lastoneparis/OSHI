package com.oshi.messenger

import com.oshi.messenger.service.lora.LoRaChannelThread
import com.oshi.messenger.service.lora.LoRaPacketParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CHANNEL_ROOMS_ALL_SENT_ON_SLOT_ZERO_2026_11_07__
 *
 * `sendToLoRaChannel` broadcast every channel room's text without ever setting
 * `MeshPacket.channel` — the packet builder did not emit field 3 at all, on
 * EITHER platform. So a message typed into a private channel's room went out on
 * slot 0: the primary, normally LongFast, normally public. Wrong delivery, and
 * for a named private channel a disclosure.
 *
 * Two halves, both pure and both tested here: the wire has to carry the slot,
 * and the room key has to resolve to the right slot on THIS radio.
 */
class LoRaChannelSlotTest {

    private fun ch(index: Int, name: String, role: Long = 2L) =
        LoRaPacketParser.NodeChannel(index, name, ByteArray(0), role)

    // ------------------------------------------------------------- the wire

    /**
     * Field 3, varint. Meshtastic's `MeshPacket.channel`.
     *
     * Asserted on the BYTES rather than through a round-trip helper: this file's
     * whole subject is a field that was never written, and a round trip through
     * our own encoder and decoder would have been just as green before the fix.
     */
    @Test
    fun `the packet carries the channel slot`() {
        val frame = LoRaPacketParser.toRadioPacket(
            to = 0xFFFFFFFFu, portnum = LoRaPacketParser.PORT_TEXT,
            payload = "hi".toByteArray(), wantAck = false, packetId = 7u, channel = 3,
        )
        // ToRadio.packet is field 1, wire type 2 → tag 0x0A. field 3 varint → tag 0x18.
        val inner = frame.drop(2).toByteArray()
        val tagIndex = inner.indexOfFirst { it == 0x18.toByte() }
        assertTrue("MeshPacket.channel (field 3, tag 0x18) is not on the wire", tagIndex >= 0)
        assertEquals("wrong slot encoded", 3, inner[tagIndex + 1].toInt())
    }

    /**
     * Slot 0 stays OFF the wire. 0 is the proto default and a stock Meshtastic
     * encoder omits it, so every frame that worked before this change must be
     * byte-identical after it — otherwise the fix is a compatibility risk for
     * every direct message and every OSHI envelope, which all ride slot 0.
     */
    @Test
    fun `slot zero is byte-identical to before the fix`() {
        val withDefault = LoRaPacketParser.toRadioPacket(
            to = 0x12345678u, portnum = LoRaPacketParser.PORT_TEXT,
            payload = "hi".toByteArray(), wantAck = true, packetId = 7u,
        )
        val explicitZero = LoRaPacketParser.toRadioPacket(
            to = 0x12345678u, portnum = LoRaPacketParser.PORT_TEXT,
            payload = "hi".toByteArray(), wantAck = true, packetId = 7u, channel = 0,
        )
        assertTrue(withDefault.contentEquals(explicitZero))
        assertTrue(
            "slot 0 must not be emitted — it is the proto default",
            withDefault.drop(2).none { it == 0x18.toByte() },
        )
    }

    // ------------------------------------------------- key → slot on THIS radio

    private val radio = listOf(
        ch(0, "", role = 1L),      // unnamed primary — the stock LongFast
        ch(1, "Manif"),
        ch(2, "secours"),
        ch(7, "old", role = 0L),   // DISABLED
    )

    @Test
    fun `a named room resolves to its slot regardless of case`() {
        assertEquals(1, LoRaChannelThread.channelIndexFor("lorachan!manif", radio))
        assertEquals(2, LoRaChannelThread.channelIndexFor("lorachan!secours", radio))
    }

    @Test
    fun `an unnamed slot room resolves while the slot is still unnamed`() {
        assertEquals(0, LoRaChannelThread.channelIndexFor("lorachan#0", radio))
    }

    /**
     * The reason this returns null instead of 0 — and the reason the caller
     * refuses rather than falling back. Answering "I don't know which slot"
     * with "the public one" is how a private room's text reaches LongFast.
     */
    @Test
    fun `a channel this radio does not have is null, never slot zero`() {
        assertNull(LoRaChannelThread.channelIndexFor("lorachan!nonesuch", radio))
        assertNull("a disabled slot is not a channel the user is on",
                   LoRaChannelThread.channelIndexFor("lorachan!old", radio))
        assertNull(LoRaChannelThread.channelIndexFor("lorachan#5", radio))
    }

    /**
     * A slot key is only minted for an UNNAMED channel. Once the user names
     * slot 1, `keyFor` produces `lorachan!manif` and `lorachan#1` is the stale
     * room — matching it by number would put old text on a repurposed channel.
     */
    @Test
    fun `a stale slot room does not match a channel that has since been named`() {
        assertNull(LoRaChannelThread.channelIndexFor("lorachan#1", radio))
    }

    @Test
    fun `a key that is not a channel room resolves to nothing`() {
        assertNull(LoRaChannelThread.channelIndexFor("lora!deadbeef", radio))
        assertNull(LoRaChannelThread.channelIndexFor("", radio))
    }

    /** Round-trip with the minting function, so the two cannot drift apart. */
    @Test
    fun `every room key minted from this radio resolves back to its own slot`() {
        for (c in radio.filterNot { it.isDisabled }) {
            val key = LoRaChannelThread.keyFor(c.index, c.name)
            assertEquals("keyFor/channelIndexFor disagree for slot ${c.index}",
                         c.index, LoRaChannelThread.channelIndexFor(key, radio))
        }
    }
}
