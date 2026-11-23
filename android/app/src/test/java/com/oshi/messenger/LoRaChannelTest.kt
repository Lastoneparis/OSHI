package com.oshi.messenger

import com.oshi.messenger.service.lora.LoRaChannel
import com.oshi.messenger.service.lora.LoRaPacketParser
import com.oshi.messenger.service.lora.LoRaRegion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __LORA_CHANNEL_2026_10_26__ The channel is WHICH MESH the node is on, and a
 * wrong byte here is not a cosmetic bug: it puts the radio on a channel nobody
 * else is on (wrong name), or on the public one while the user believes they
 * are keyed (wrong psk).
 *
 * Golden bytes, not a round trip: a round trip through our own writer and our
 * own reader passes happily while BOTH sides share the same wrong field number
 * — which is exactly how "Set region" once shipped writing a Config under
 * field 33 and doing nothing at all.
 */
class LoRaChannelTest {

    // ---------------------------------------------------------------- Name

    /** The firmware's name field is a `char[12]`: 11 bytes, counted as BYTES. */
    @Test
    fun theNameBudgetIsElevenBytesNotElevenChars() {
        for (raw in listOf("Chile", "LongFast", "ééééééééééé", "日本語チャンネル", "🚀🚀🚀🚀", "")) {
            val out = LoRaChannel.clampName(raw)
            assertTrue(
                "\"$raw\" produced ${out.toByteArray(Charsets.UTF_8).size} bytes: \"$out\"",
                out.toByteArray(Charsets.UTF_8).size <= LoRaChannel.NAME_MAX_BYTES
            )
        }
    }

    @Test
    fun aSurrogatePairIsNeverSplit() {
        // Four rockets are 16 bytes; two fit in eleven and the third must not be
        // cut in half (a half pair encodes as U+FFFD, a black diamond in every
        // other client).
        val out = LoRaChannel.clampName("🚀🚀🚀🚀")
        assertEquals(2, out.codePointCount(0, out.length))
        assertTrue(out.none { it == '�' })
    }

    @Test
    fun aShortNamePassesThroughUntouched() {
        assertEquals("Chile", LoRaChannel.clampName("  Chile  "))
    }

    /** Blank is the LongFast default, not an error — and not the string. */
    @Test
    fun blankStaysBlankAndDisplaysAsLongFast() {
        assertEquals("", LoRaChannel.clampName("   "))
        assertEquals("LongFast", LoRaChannel.displayName(""))
        assertEquals("Chile", LoRaChannel.displayName("Chile"))
    }

    // ----------------------------------------------------------------- Key

    /** Empty means the public default key: one byte, value 1 (`AQ==`). */
    @Test
    fun anEmptyKeyIsTheDefaultIndexByte() {
        assertArrayEquals(byteArrayOf(1), LoRaChannel.parsePsk(""))
        assertArrayEquals(byteArrayOf(1), LoRaChannel.parsePsk("   "))
        assertArrayEquals(byteArrayOf(1), LoRaChannel.parsePsk("AQ=="))
    }

    /** A Meshtastic channel URL carries the key base64URL and unpadded. */
    @Test
    fun bothBase64AlphabetsAndMissingPaddingAreAccepted() {
        val raw = ByteArray(16) { (it * 17 + 3).toByte() }
        val std = java.util.Base64.getEncoder().encodeToString(raw)
        val url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        assertArrayEquals(raw, LoRaChannel.parsePsk(std))
        assertArrayEquals(raw, LoRaChannel.parsePsk(url))
        assertArrayEquals(raw, LoRaChannel.parsePsk(std.trimEnd('=')))
    }

    @Test
    fun aThirtyTwoByteKeyIsAccepted() {
        val raw = ByteArray(32) { it.toByte() }
        assertArrayEquals(raw, LoRaChannel.parsePsk(java.util.Base64.getEncoder().encodeToString(raw)))
    }

    /**
     * THE property. A mistyped key must refuse, never fall back to the public
     * default: that would publish to the open channel under a key the user
     * believed was private.
     */
    @Test
    fun aKeyThatIsNotAKeyRefusesInsteadOfFallingBack() {
        for (bad in listOf("hello", "not base64!!", "QUJD", "////", "AAAAAAAAAAAAAAAAAAAA")) {
            assertNull("\"$bad\" was accepted as a key", LoRaChannel.parsePsk(bad))
        }
    }

    // ---------------------------------------------------------------- Wire

    /**
     * Golden bytes for the stock LongFast channel: empty name, default key.
     *
     *   AdminMessage.set_channel = field 33  -> key 0x0A 0x02 (33<<3|2 = 266)
     *     Channel.index    = field 1 varint  -> 0x08 0x00
     *     Channel.settings = field 2 bytes   -> 0x12 len
     *       ChannelSettings.psk  = field 2   -> 0x12 0x01 0x01
     *       ChannelSettings.name = field 3   -> 0x1A 0x00
     *     Channel.role     = field 3 varint  -> 0x18 0x01  (PRIMARY)
     */
    @Test
    fun theDefaultChannelIsTheseExactBytes() {
        val out = LoRaPacketParser.adminSetChannel("", LoRaChannel.DEFAULT_PSK)
        val expected = byteArrayOf(
            0x8A.toByte(), 0x02,              // field 33, wire 2
            0x0B,                             // length of Channel
            0x08, 0x00,                       // index = 0
            0x12, 0x05,                       // settings, 5 bytes
            0x12, 0x01, 0x01,                 //   psk = 0x01
            0x1A, 0x00,                       //   name = ""
            0x18, 0x01                        // role = PRIMARY
        )
        assertArrayEquals(hex(expected), hex(out))
    }

    /** A named channel carries the name as UTF-8, and nothing else moves. */
    @Test
    fun aNamedChannelCarriesTheNameAsUtf8() {
        val out = LoRaPacketParser.adminSetChannel("Chile", LoRaChannel.DEFAULT_PSK)
        val expected = byteArrayOf(
            0x8A.toByte(), 0x02,
            0x10,                             // length of Channel
            0x08, 0x00,
            0x12, 0x0A,                       // settings, 10 bytes
            0x12, 0x01, 0x01,
            0x1A, 0x05, 'C'.code.toByte(), 'h'.code.toByte(), 'i'.code.toByte(),
            'l'.code.toByte(), 'e'.code.toByte(),
            0x18, 0x01
        )
        assertArrayEquals(hex(expected), hex(out))
    }

    /**
     * Field 33, NOT 34. `set_config` is 34 and carries a Config; swapping them
     * is the mistake that made a config write silently do nothing.
     */
    @Test
    fun theChannelGoesUnderFieldThirtyThreeAndTheRegionUnderThirtyFour() {
        assertEquals(0x8A.toByte(), LoRaPacketParser.adminSetChannel("", LoRaChannel.DEFAULT_PSK)[0])
        assertEquals(0x92.toByte(), LoRaPacketParser.adminSetLoRaConfig(1L, 0L, 3L)[0])
    }

    /** The name is clamped on the WIRE too, not only in the text field. */
    @Test
    fun anOverlongNameIsClampedBeforeItReachesTheNode() {
        val out = LoRaPacketParser.adminSetChannel("ABCDEFGHIJKLMNOP", LoRaChannel.DEFAULT_PSK)
        // name field is 0x1A then its length; find it inside the settings blob.
        val i = out.indexOfFirst { it == 0x1A.toByte() }
        assertTrue(i > 0)
        assertEquals(LoRaChannel.NAME_MAX_BYTES, out[i + 1].toInt())
    }

    // -------------------------------------------------------------- Region

    /**
     * The review that asked for all of this came from Chile, whose nodes are
     * 915 MHz nodes — and `suggestedForRegionCode("CL")` answered null, so the
     * picker opened with nothing selected.
     */
    @Test
    fun chileAndItsNeighboursProposeTheNineOneFiveBand() {
        for (code in listOf("CL", "AR", "PE", "CO", "UY", "EC", "BO", "PY", "VE")) {
            assertEquals("$code proposed the wrong region",
                LoRaRegion.US, LoRaRegion.suggestedForRegionCode(code))
        }
    }

    /** Brazil keeps its OWN code; it was mapped before and must not move. */
    @Test
    fun brazilKeepsItsOwnRegion() {
        assertEquals(LoRaRegion.BR902, LoRaRegion.suggestedForRegionCode("BR"))
    }

    private fun hex(b: ByteArray): Array<String> = b.map { "%02x".format(it) }.toTypedArray()
}
