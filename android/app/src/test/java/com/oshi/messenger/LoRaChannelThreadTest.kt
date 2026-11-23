package com.oshi.messenger

import com.oshi.messenger.service.lora.LoRaChannelThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __LORA_CHANNEL_IS_A_ROOM_2026_11_04__
 *
 * A LoRa channel is a room: everyone in range on the same channel hears everyone
 * else, and several people talk at once. OSHI stored none of that. Every inbound
 * packet became `sender = <that node>, recipient = me`, which is a PRIVATE
 * conversation, so three people talking on LongFast produced three separate
 * one-to-one threads that each looked like a direct message.
 *
 * The room is the recipient and the person is the sender, which is what those
 * two fields already mean, so no column and no migration. The tests below pin
 * the two properties that make that work: the key is stable for a channel, and
 * it is recognisable, because the one-to-one side has to exclude these rows or
 * the same message shows up in two places.
 */
class LoRaChannelThreadTest {

    // ------------------------------------------------------------- stability

    @Test
    fun `a named channel keys on its name, not on its local slot`() {
        // The property that matters most. `MeshPacket.channel` is an index into
        // the NODE's own table, and the proto says it "has no meaning when sent
        // between nodes". Keying on it would move every message into a different
        // room the moment the user reordered their channels, or the moment two
        // phones numbered the same channel differently.
        assertEquals(
            LoRaChannelThread.keyFor(1, "manif"),
            LoRaChannelThread.keyFor(5, "manif"),
        )
    }

    @Test
    fun `capitalisation does not split a channel into two rooms`() {
        assertEquals(
            LoRaChannelThread.keyFor(0, "Manif"),
            LoRaChannelThread.keyFor(0, "manif"),
        )
    }

    @Test
    fun `surrounding space does not split a channel either`() {
        assertEquals(
            LoRaChannelThread.keyFor(0, "manif"),
            LoRaChannelThread.keyFor(0, "  manif  "),
        )
    }

    @Test
    fun `two different channels are two different rooms`() {
        assertNotEquals(
            LoRaChannelThread.keyFor(0, "manif"),
            LoRaChannelThread.keyFor(0, "rando"),
        )
    }

    @Test
    fun `an unnamed channel falls back to its slot`() {
        // The stock primary channel carries no name at all, so there is nothing
        // else to key on. Distinct slots stay distinct.
        assertNotEquals(LoRaChannelThread.keyFor(0, null), LoRaChannelThread.keyFor(1, null))
        assertEquals(LoRaChannelThread.keyFor(0, null), LoRaChannelThread.keyFor(0, ""))
        assertEquals(LoRaChannelThread.keyFor(0, null), LoRaChannelThread.keyFor(0, "   "))
    }

    // --------------------------------------------------------- recognisable

    @Test
    fun `a channel key is recognisable as one`() {
        assertTrue(LoRaChannelThread.isChannelKey(LoRaChannelThread.keyFor(0, null)))
        assertTrue(LoRaChannelThread.isChannelKey(LoRaChannelThread.keyFor(2, "manif")))
    }

    @Test
    fun `a node pseudo-key is NOT mistaken for a channel`() {
        // `lora!<nodehex>` is the OTHER synthetic key in this app and shares the
        // `lora` family deliberately. If these two collided, a private thread
        // with one radio would be filtered out of the conversation list as
        // though it were a public room.
        assertFalse(LoRaChannelThread.isChannelKey("lora!a1b2c3d4"))
    }

    @Test
    fun `a real public key is NOT mistaken for a channel`() {
        assertFalse(LoRaChannelThread.isChannelKey("MCowBQYDK2VwAyEAabc123"))
        assertFalse(LoRaChannelThread.isChannelKey(""))
    }

    // --------------------------------------------------------------- display

    @Test
    fun `an unnamed primary channel is shown as LongFast`() {
        // What every stock Meshtastic install calls it. "#0" would be accurate
        // and useless.
        assertEquals("LongFast", LoRaChannelThread.displayName(LoRaChannelThread.keyFor(0, null)))
    }

    @Test
    fun `a named channel shows its name`() {
        assertEquals("manif", LoRaChannelThread.displayName(LoRaChannelThread.keyFor(0, "manif")))
    }

    @Test
    fun `an unnamed secondary keeps its number`() {
        // There is nothing truer to call it, and calling it LongFast would be a
        // lie: LongFast is the primary.
        assertEquals("#3", LoRaChannelThread.displayName(LoRaChannelThread.keyFor(3, null)))
    }

    @Test
    fun `a non-channel key is handed back untouched`() {
        assertEquals("lora!a1b2c3d4", LoRaChannelThread.displayName("lora!a1b2c3d4"))
    }

    // ------------------------------------------- the room keeps its two halves

    @Test
    fun `a channel key never collides with a node key`() {
        // The two synthetic keys in this app live side by side: `lora!<nodehex>`
        // names a RADIO and `lorachan!<name>` names a ROOM. A broadcast is stored
        // with one in `senderPublicKey` and the other in `recipientPublicKey`, so
        // if either could be read as the other a public message would be filed as
        // a private one, or a private thread would vanish from the list.
        val room = LoRaChannelThread.keyFor(0, "manif")
        val radio = "lora!a1b2c3d4"
        assertTrue(LoRaChannelThread.isChannelKey(room))
        assertFalse(LoRaChannelThread.isChannelKey(radio))
        assertNotEquals(room, radio)
    }

    @Test
    fun `a channel named like a node prefix is still a channel`() {
        // Someone will name a channel "lora". The prefixes are distinct strings,
        // not a shared namespace, so this has to stay unambiguous.
        val key = LoRaChannelThread.keyFor(0, "lora")
        assertTrue(LoRaChannelThread.isChannelKey(key))
        assertEquals("lora", LoRaChannelThread.displayName(key))
    }

    @Test
    fun `a channel whose name looks like a slot is not read as one`() {
        // `keyFor` writes "#<n>" only for an UNNAMED channel. A channel the user
        // actually called "#3" must keep its own name rather than be renamed by
        // the display rule for empty slots.
        val named = LoRaChannelThread.keyFor(7, "#3")
        val unnamed = LoRaChannelThread.keyFor(3, null)
        assertNotEquals(
            "a named '#3' and the unnamed slot 3 are different rooms",
            named, unnamed,
        )
    }
}
