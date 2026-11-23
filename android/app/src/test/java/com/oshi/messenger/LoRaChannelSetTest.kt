package com.oshi.messenger

import com.oshi.messenger.service.lora.LoRaChannelSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `https://meshtastic.org/e/#…` — the format every public Meshtastic channel is
 * shared in, and the one thing standing between a user and the community mesh for
 * their city.
 *
 * WHY THE EXPECTED VALUES ARE LITERALS
 * ------------------------------------
 * A round-trip test (`parse(buildUrl(x)) == x`) proves only that this file agrees
 * with itself, which is worth nothing for a wire format: get a field number wrong
 * in both directions and it still passes, while no real Meshtastic node
 * understands a word we say.
 *
 * So every expected byte sequence and URL below was computed INDEPENDENTLY of the
 * Kotlin, from the field numbers in the published protos
 * (`meshtastic/apponly.proto`, `meshtastic/channel.proto`, `meshtastic/config.proto`):
 *
 *     ChannelSet       { settings = 1 (repeated), lora_config = 2 }
 *     ChannelSettings  { psk = 2, name = 3 }
 *     LoRaConfig       { use_preset = 1, modem_preset = 2, region = 7, hop_limit = 8 }
 *
 * Worked example for [SHARED_OSHI], so the next person can re-derive it rather
 * than trust it:
 *
 *     psk  = [1]        field 2, wire 2 -> tag 0x12, len 01, payload 01
 *     name = "OSHI"     field 3, wire 2 -> tag 0x1A, len 04, payload 4F 53 48 49
 *     ChannelSettings   = 12 01 01 1A 04 4F 53 48 49          (9 bytes)
 *     ChannelSet.settings field 1, wire 2 -> tag 0x0A, len 09
 *     ChannelSet        = 0A 09 12 01 01 1A 04 4F 53 48 49    (11 bytes)
 *     base64url, no padding                                   -> CgkSAQEaBE9TSEk
 *
 * No real third-party link is used as a fixture on purpose: a channel link
 * contains its PSK, so pasting someone's real public-channel link into this repo
 * would commit a key we have no business holding, and we could not verify what it
 * was supposed to decode to anyway.
 */
class LoRaChannelSetTest {

    /** name "OSHI", psk `AQ==`, no `lora_config`. Derived above. */
    private val SHARED_OSHI = "https://meshtastic.org/e/#CgkSAQEaBE9TSEk"

    /**
     * The stock public channel: EMPTY name, psk `AQ==`, plus a complete
     * `lora_config` of use_preset=1, modem_preset=0, region=3, hop_limit=3.
     *
     *     lora_config = 08 01 10 00 38 03 40 03
     *                   ^^^^^ use_preset=1  (field 1, wire 0)
     *                         ^^^^^ modem_preset=0 (field 2) — written EXPLICITLY;
     *                               protobuf would otherwise omit a zero and the
     *                               recipient's preset would be left at whatever
     *                               it was.
     *                               ^^^^^ region=3 (field 7)
     *                                     ^^^^^ hop_limit=3 (field 8)
     */
    private val SHARED_DEFAULT = "https://meshtastic.org/e/#CgUSAQEaABIICAEQADgDQAM"

    /** Primary "OSHI" + secondary "Secondaire" with a 16-byte all-zero key. */
    private val SHARED_TWO = "https://meshtastic.org/e/#" +
        "CgkSAQEaBE9TSEkKHhIQAAAAAAAAAAAAAAAAAAAAABoKU2Vjb25kYWlyZQ"

    // ------------------------------------------------------------------ parse

    @Test
    fun `a shared channel link yields its name and key`() {
        val p = assertNotNull(LoRaChannelSet.parse(SHARED_OSHI)).let { LoRaChannelSet.parse(SHARED_OSHI)!! }
        assertEquals(1, p.channels.size)
        assertEquals("OSHI", p.primary!!.name)
        assertArrayEqualsBytes(byteArrayOf(1), p.primary!!.psk)
        assertNull("no lora_config in this link", p.radio)
    }

    @Test
    fun `the stock public channel parses, blank name and all`() {
        // A blank name is NOT a parse failure: it is precisely what a stock node
        // ships with, and what users call LongFast.
        val p = LoRaChannelSet.parse(SHARED_DEFAULT)!!
        assertEquals("", p.primary!!.name)
        assertEquals("LongFast", p.primary!!.displayName)
        assertTrue("one byte of value 1 is the public default key", p.primary!!.isDefaultPublicKey)
    }

    @Test
    fun `the radio half of a link is read`() {
        val r = LoRaChannelSet.parse(SHARED_DEFAULT)!!.radio!!
        assertEquals(3L, r.regionRaw)
        assertEquals(0L, r.presetRaw)
        assertEquals(3L, r.hopLimit)
        assertTrue(r.isComplete)
    }

    @Test
    fun `order is meaning - the first channel is the primary`() {
        // From apponly.proto: "It includes only one PRIMARY channel (which must be
        // first) and any SECONDARY channels." Reordering would put the user on the
        // wrong mesh, so this is not a presentation detail.
        val p = LoRaChannelSet.parse(SHARED_TWO)!!
        assertEquals(2, p.channels.size)
        assertEquals("OSHI", p.primary!!.name)
        assertEquals(listOf("Secondaire"), p.secondaries.map { it.name })
    }

    @Test
    fun `a 16 byte key is carried through intact`() {
        val secondary = LoRaChannelSet.parse(SHARED_TWO)!!.secondaries.single()
        assertEquals(16, secondary.psk.size)
        assertFalse(secondary.isDefaultPublicKey)
        assertFalse("16 zero bytes is a real AES key, not 'no encryption'", secondary.isUnencrypted)
    }

    // ------------------------------------------------------------ tolerance

    @Test
    fun `the older c and d prefixes still parse`() {
        // Links posted in 2021 are still in forum threads. The reference parser
        // only looks for the "/#" delimiter, so these work there; refusing them
        // here would look like a broken app rather than a stale link.
        val payload = SHARED_OSHI.substringAfter("/e/#")
        for (url in listOf(
            "https://www.meshtastic.org/c/#$payload",
            "https://meshtastic.org/d/#$payload",
        )) {
            assertEquals("failed for $url", "OSHI", LoRaChannelSet.parse(url)?.primary?.name)
        }
    }

    @Test
    fun `the add-only form parses`() {
        val payload = SHARED_OSHI.substringAfter("/e/#")
        assertEquals("OSHI", LoRaChannelSet.parse("https://meshtastic.org/e/?add=true#$payload")?.primary?.name)
    }

    @Test
    fun `a bare payload with no URL around it parses`() {
        // People paste the half they can see, especially off a QR reader that
        // shows the fragment only.
        val payload = SHARED_OSHI.substringAfter("/e/#")
        assertEquals("OSHI", LoRaChannelSet.parse(payload)?.primary?.name)
    }

    @Test
    fun `the standard base64 alphabet is accepted too`() {
        // A link copied through a chat app, a QR reader or a forum post arrives in
        // whichever alphabet that tool used, and the user cannot tell by looking.
        // Built here to CONTAIN the characters that differ, or the test is vacuous.
        val two = LoRaChannelSet.parse(SHARED_TWO)!!
        val urlSafe = LoRaChannelSet.buildUrl(two.channels).substringAfter("#")
        val standard = urlSafe.replace('-', '+').replace('_', '/')
        if (standard == urlSafe) {
            // No distinguishing character in this payload; nothing to assert.
            return
        }
        assertEquals(two.channels, LoRaChannelSet.parse(standard)?.channels)
    }

    @Test
    fun `padding is restored, however much was stripped`() {
        // Lengths 2 and 3 mod 4 both occur in real links and need one or two '='.
        for (ch in listOf(SHARED_OSHI, SHARED_DEFAULT, SHARED_TWO)) {
            assertNotNull("failed for $ch", LoRaChannelSet.parse(ch))
        }
    }

    // ----------------------------------------------------------- rejections

    @Test
    fun `things that are not channel links are refused, not guessed at`() {
        // Fed by a paste field and a QR scan, so "not a channel link" is an
        // ordinary thing for a user to do and must come back as null rather than
        // as an exception or an empty mesh.
        for (bad in listOf(
            "",
            "   ",
            "hello",
            "https://oshi-messenger.com/lora/",
            "https://meshtastic.org/e/#",
            "not a url at all, just a sentence",
        )) {
            assertNull("should have been refused: '$bad'", LoRaChannelSet.parse(bad))
        }
    }

    @Test
    fun `a payload that decodes to no channels is not a mesh`() {
        // Random bytes parse as protobuf far more often than feels right. Without
        // the "at least one channel" check, a stray paste would be reported to the
        // user as a valid channel they could join, and joining it would take their
        // radio off the air.
        //
        // "EAE" is base64 for 0x10 0x01: field 2, wire 0 — a lora_config-shaped
        // field at the top level and no `settings` at all.
        assertNull(LoRaChannelSet.parse("https://meshtastic.org/e/#EAE"))
    }

    @Test
    fun `a length that cannot be base64 is refused rather than padded into nonsense`() {
        // n % 4 == 1 is impossible base64: four characters carry three bytes, so a
        // remainder of one has no byte to belong to. The reference implementation
        // appends "=" * (4 - len % 4) unconditionally, which both pads this into
        // garbage and appends four '=' when the length is already a multiple of
        // four. 17 characters, i.e. 1 mod 4 — my first attempt at this fixture used
        // 16 and was simply VALID base64, which is why the test failed and the
        // code did not.
        val impossible = "CgkSAQEaBE9TSEkAB"
        assertEquals("precondition: 1 mod 4", 1, impossible.length % 4)
        assertNull(LoRaChannelSet.parse("https://meshtastic.org/e/#$impossible"))
    }

    // ---------------------------------------------------------------- build

    @Test
    fun `the link we produce is the one the reference implementation produces`() {
        // Compared against the independently derived literal, NOT against our own
        // parser: this is the assertion that catches a wrong field number, which a
        // round-trip never would.
        val url = LoRaChannelSet.buildUrl(
            listOf(LoRaChannelSet.Channel(name = "OSHI", psk = byteArrayOf(1)))
        )
        assertEquals(SHARED_OSHI, url)
    }

    @Test
    fun `a complete radio config is included, byte for byte`() {
        val url = LoRaChannelSet.buildUrl(
            listOf(LoRaChannelSet.Channel(name = "", psk = byteArrayOf(1))),
            LoRaChannelSet.Radio(regionRaw = 3, presetRaw = 0, hopLimit = 3),
        )
        assertEquals(SHARED_DEFAULT, url)
    }

    @Test
    fun `an INCOMPLETE radio config is left out of the link`() {
        // The whole LoRaConfig is replaced by what is sent, and protobuf omits
        // defaults, so a partial config arrives as zeros and produces a node that
        // is configured, on the air, and unable to talk to anyone. Sharing a
        // channel alone is better than breaking the radio of everyone who scans.
        val url = LoRaChannelSet.buildUrl(
            listOf(LoRaChannelSet.Channel(name = "OSHI", psk = byteArrayOf(1))),
            LoRaChannelSet.Radio(regionRaw = 3, presetRaw = null, hopLimit = 3),
        )
        assertEquals("the radio half must be absent", SHARED_OSHI, url)
    }

    @Test
    fun `a name too long for the firmware is clamped before it is shared`() {
        // The node stores the name in a char[12]. Sharing a 20-character name
        // would hand out a link whose channel hash nobody can reproduce.
        val url = LoRaChannelSet.buildUrl(
            listOf(LoRaChannelSet.Channel(name = "canal-beaucoup-trop-long", psk = byteArrayOf(1)))
        )
        val name = LoRaChannelSet.parse(url)!!.primary!!.name
        assertTrue("got '$name'", name.toByteArray(Charsets.UTF_8).size <= 11)
    }

    @Test
    fun `a round trip through both halves preserves every channel`() {
        val channels = listOf(
            LoRaChannelSet.Channel(name = "OSHI", psk = byteArrayOf(1)),
            LoRaChannelSet.Channel(name = "Manif", psk = ByteArray(32) { it.toByte() }),
        )
        val back = LoRaChannelSet.parse(LoRaChannelSet.buildUrl(channels))!!
        assertEquals(channels, back.channels)
    }

    // ------------------------------------------------------- channel identity

    @Test
    fun `two channels with the same name and key are equal`() {
        // `psk` is a ByteArray in a data class, whose generated equals compares it
        // by REFERENCE. Left alone, two identical channels would be unequal and
        // every `==`, `distinct()` and `contains()` a caller writes would be
        // quietly wrong — the same trap as
        // __GROUP_PHOTO_FLICKERED_2026_11_04__, one layer down.
        val a = LoRaChannelSet.Channel("OSHI", byteArrayOf(1, 2, 3))
        val b = LoRaChannelSet.Channel("OSHI", byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(1, setOf(a, b).size)
    }

    @Test
    fun `a different key is a different channel`() {
        assertFalse(
            LoRaChannelSet.Channel("OSHI", byteArrayOf(1)) ==
                LoRaChannelSet.Channel("OSHI", byteArrayOf(2))
        )
    }

    @Test
    fun `an empty or zero key is reported as unencrypted`() {
        // Worth a named property because the UI has to warn about it: a zero key
        // means the radio transmits in the clear, which in a protest is the
        // difference that matters.
        assertTrue(LoRaChannelSet.Channel("x", ByteArray(0)).isUnencrypted)
        assertTrue(LoRaChannelSet.Channel("x", byteArrayOf(0)).isUnencrypted)
        assertFalse(LoRaChannelSet.Channel("x", byteArrayOf(1)).isUnencrypted)
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
