package com.oshi.messenger

import com.oshi.messenger.service.TextSteganography
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Byte-level parity of Android text steganography against `OSHI/TextSteganography.swift`.
 *
 * These assertions are the wire contract. If any fails, an iPhone cannot decode what
 * Android embeds (or vice versa) even though both "work" in isolation — which is
 * exactly the failure the 2026-08-13 audit found on all four methods plus the key
 * derivation.
 */
class StegoWireFormatTest {

    private val stego = TextSteganography()

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    private fun hexStr(d: ByteArray): String =
        d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Stand-in for a raw 32-byte X25519 shared secret. */
    private val secret = hex("9075dca881d3bf1d61bb3d8887500ae4ab7e07533c7f2c23e918f87c49dde973")

    // =====================================================================
    // 1. Key derivation — swift:383
    // =====================================================================

    @Test
    fun `key is exactly SHA256 of raw secret concat TEXT_STEG_V1`() {
        // iOS: SymmetricKey(data: SHA256.hash(data: key + Data("TEXT_STEG_V1".utf8)))
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(secret + "TEXT_STEG_V1".toByteArray(Charsets.UTF_8))
        val actual = TextSteganography.deriveKeyBytes(secret)
        assertEquals(hexStr(expected), hexStr(actual))
        assertEquals(32, actual.size)
    }

    @Test
    fun `key derivation has no OSHI_STEGO_V1 base64 layer`() {
        // The old Android path was SHA256(base64(SHA256(secret + "OSHI_STEGO_V1")) + "TEXT_STEG_V1").
        val oldInner = MessageDigest.getInstance("SHA-256")
            .digest(secret + "OSHI_STEGO_V1".toByteArray(Charsets.UTF_8))
        val oldPass = java.util.Base64.getEncoder().withoutPadding().encodeToString(oldInner)
        val oldKey = MessageDigest.getInstance("SHA-256")
            .digest((oldPass + "TEXT_STEG_V1").toByteArray(Charsets.UTF_8))
        org.junit.Assert.assertNotEquals(hexStr(oldKey), hexStr(TextSteganography.deriveKeyBytes(secret)))
    }

    // =====================================================================
    // 2. Bit framing — swift:408-458
    // =====================================================================

    @Test
    fun `dataToBits prepends BE32 length MSB first`() {
        val data = byteArrayOf(0xAB.toByte(), 0x01)
        val bits = stego.dataToBits(data)
        assertEquals(32 + 16, bits.size)

        // BE32(2) = 00 00 00 02
        val header = (0 until 32).map { if (bits[it]) '1' else '0' }.joinToString("")
        assertEquals("00000000000000000000000000000010", header)

        // 0xAB = 10101011, MSB first
        val first = (32 until 40).map { if (bits[it]) '1' else '0' }.joinToString("")
        assertEquals("10101011", first)
    }

    @Test
    fun `bitsToData is the exact inverse and ignores trailing padding bits`() {
        val data = ByteArray(37) { (it * 7 + 3).toByte() }
        val bits = stego.dataToBits(data).toMutableList()
        // Zero-width embedding pads the final slot; iOS slices exactly `declaredLen`
        // bytes and ignores the rest (swift:447-455).
        repeat(19) { bits.add(it % 2 == 0) }
        assertArrayEquals(data, stego.bitsToData(bits))
    }

    @Test
    fun `bitsToData enforces the iOS sanity gates`() {
        assertNull("fewer than 32 bits", stego.bitsToData(List(31) { true }))
        // declared length 0
        assertNull("zero length", stego.bitsToData(List(64) { false }))
        // declared length >= 100_000  (swift:442)
        val huge = ArrayList<Boolean>()
        val n = 100_000
        for (i in 31 downTo 0) huge.add((n shr i) and 1 == 1)
        repeat(64) { huge.add(false) }
        assertNull("length >= 100000", stego.bitsToData(huge))
        // declared length larger than the bits present (swift:443-444)
        val short = ArrayList<Boolean>()
        for (i in 31 downTo 0) short.add((500 shr i) and 1 == 1)
        repeat(80) { short.add(true) }
        assertNull("truncated payload", stego.bitsToData(short))
    }

    // =====================================================================
    // 3. Homoglyph table — swift:62-85
    // =====================================================================

    @Test
    fun `homoglyph table is the 22 iOS entries verbatim`() {
        val map = TextSteganography.HOMOGLYPH_MAP
        assertEquals(22, map.size)
        val expected = mapOf(
            'a' to 'а', 'c' to 'с', 'e' to 'е', 'o' to 'о',
            'p' to 'р', 'x' to 'х', 'y' to 'у', 's' to 'ѕ',
            'i' to 'і', 'j' to 'ј', 'h' to 'һ',
            'A' to 'А', 'B' to 'В', 'C' to 'С', 'E' to 'Е',
            'H' to 'Н', 'K' to 'К', 'M' to 'М', 'O' to 'О',
            'P' to 'Р', 'T' to 'Т', 'X' to 'Х'
        )
        assertEquals(expected, map)
        // The 13 extra lowercase letters the old Android map carried would shift every
        // subsequent bit index against iOS.
        for (c in listOf('d', 'g', 'k', 'l', 'm', 'n', 'q', 'r', 't', 'u', 'v', 'w', 'z')) {
            assertTrue("'$c' must not be substitutable", c !in map)
        }
    }

    // =====================================================================
    // 4. Carrier round-trips
    // =====================================================================

    private val cover = TextSteganography().generateCoverText(TextSteganography.CoverTextStyle.SOCIAL_COMMENT)

    @Test
    fun `zero-width round trip`() {
        val payload = "{\"messageId\":\"abc\",\"fragmentIndex\":0}".toByteArray()
        val text = stego.embed(payload, cover, secret, TextSteganography.Method.ZERO_WIDTH)
        assertNotNull(text)
        assertTrue(text!!.contains(TextSteganography.ZW_START))
        assertTrue(text.endsWith(TextSteganography.ZW_SEP))
        assertArrayEquals(payload, stego.extract(text, secret))
    }

    @Test
    fun `homoglyph round trip on a cover with enough substitutable characters`() {
        val payload = "hi".toByteArray()   // 2 + 28 sealed bytes ⇒ 34 bytes ⇒ 272 bits
        val bigCover = cover.repeat(4)
        val text = stego.embed(payload, bigCover, secret, TextSteganography.Method.HOMOGLYPH)
        assertNotNull(text)
        assertArrayEquals(payload, stego.extract(text!!, secret, TextSteganography.Method.HOMOGLYPH))
    }

    @Test
    fun `homoglyph embed FAILS instead of silently dropping overflow bits`() {
        // iOS swift:275-277 returns nil when the cover runs out. The old Kotlin silently
        // truncated, producing text that decoded to garbage on the other side.
        val payload = ByteArray(200) { it.toByte() }
        assertNull(stego.embed(payload, "aeiou", secret, TextSteganography.Method.HOMOGLYPH))
    }

    @Test
    fun `whitespace round trip`() {
        val payload = "x".toByteArray()
        val bigCover = cover.repeat(6)
        val text = stego.embed(payload, bigCover, secret, TextSteganography.Method.WHITESPACE)
        assertNotNull(text)
        assertArrayEquals(payload, stego.extract(text!!, secret, TextSteganography.Method.WHITESPACE))
    }

    @Test
    fun `whitespace extract records one bit per RUN of spaces (inWord gate)`() {
        // swift:330-348. "a<sp><sp>b" is ONE bit, not two; a leading space is zero bits.
        val text = "  a \u00A0b c"     // lead run, then NBSP-run (bit 1), then space (bit 0)
        val bits = ArrayList<Boolean>()
        var inWord = false
        for (ch in text) when (ch) {
            ' ' -> if (inWord) { bits.add(false); inWord = false }
            '\u00A0' -> if (inWord) { bits.add(true); inWord = false }
            else -> inWord = true
        }
        assertEquals(listOf(false, false), bits)

        // Ungated (the old Android behaviour) the same string yields FIVE bits, which is
        // why any double space desynced the whole stream.
        val ungated = text.count { it == ' ' || it == '\u00A0' }
        assertEquals(5, ungated)

        // The production extractor uses the gate and does not blow up on 2 bits.
        assertNull(stego.extractWhitespace(text))
    }

    @Test
    fun `combined falls back to homoglyph rather than splitting the payload`() {
        // swift:116-122. Zero-width needs >1 cover char; give it exactly one so it
        // returns nil and the homoglyph branch has to carry the whole payload.
        val payload = "z".toByteArray()
        val oneChar = "a"
        assertNull(stego.embed(payload, oneChar, secret, TextSteganography.Method.COMBINED))

        val big = cover.repeat(4)
        val text = stego.embed(payload, big, secret, TextSteganography.Method.COMBINED)
        assertNotNull(text)
        // COMBINED must produce a SINGLE complete payload, decodable by zero-width alone.
        assertArrayEquals(payload, stego.extract(text!!, secret, TextSteganography.Method.ZERO_WIDTH))
    }

    // =====================================================================
    // 5. Sealed-box layout — swift:387-391 / 394-404
    // =====================================================================

    @Test
    fun `sealed layout is nonce12 then ciphertext then tag16`() {
        val plain = "hello".toByteArray()
        val sealed = stego.encryptForText(plain, secret)!!
        assertEquals(12 + plain.size + 16, sealed.size)
        assertArrayEquals(plain, stego.decryptFromText(sealed, secret))
        // Wrong key ⇒ null, never an exception.
        assertNull(stego.decryptFromText(sealed, ByteArray(32)))
        // Too short ⇒ null (swift:395 `guard data.count > 28`)
        assertNull(stego.decryptFromText(ByteArray(28), secret))
    }

    /**
     * The literal iOS-format fixture: a hand-built `BE32(len) ‖ bits` stream carrying a
     * sealed box we produced ourselves, wrapped in the iOS zero-width layout
     * (`cover[0] ‖ U+FEFF ‖ bits… ‖ cover[1..] ‖ U+200D`). Decoding it end-to-end proves
     * the framing, not just that embed and extract agree with each other.
     */
    @Test
    fun `decodes a hand-assembled iOS-layout carrier`() {
        val plain = "OSHI".toByteArray()
        val sealed = stego.encryptForText(plain, secret)!!

        val sb = StringBuilder()
        sb.append('T')                                   // cover[0]
        sb.append(TextSteganography.ZW_START)
        for (b in stego.dataToBits(sealed)) {
            sb.append(if (b) TextSteganography.ZW_ONE else TextSteganography.ZW_ZERO)
        }
        sb.append("he rest of an innocent sentence.")     // remaining cover chars
        sb.append(TextSteganography.ZW_SEP)

        assertArrayEquals(plain, stego.extract(sb.toString(), secret))
    }

    // =====================================================================
    // 6. containsHiddenData / clean / capacity
    // =====================================================================

    @Test
    fun `containsHiddenData no longer fires on ordinary Cyrillic or a pasted NBSP`() {
        org.junit.Assert.assertFalse(stego.containsHiddenData("Привет, как дела?"))
        org.junit.Assert.assertFalse(stego.containsHiddenData("Hello world"))
        org.junit.Assert.assertFalse(stego.containsHiddenData("Καλημέρα"))

        val carrier = stego.embed("x".toByteArray(), cover, secret)!!
        assertTrue(stego.containsHiddenData(carrier))
    }

    @Test
    fun `clean strips zero-width markers and folds homoglyphs back to ASCII`() {
        val dirty = "h\u200Be\uFEFFll\u043E w\u043Erld\u200D !"
        assertEquals("hello world !", stego.clean(dirty))
    }

    @Test
    fun `capacity matches the iOS formula`() {
        val text = "hello world this is a cover"   // 27 chars
        assertEquals(maxOf(0, (text.length - 1) * 8 / 8 - 4), stego.capacity(text, TextSteganography.Method.ZERO_WIDTH))
        val subst = text.count { it in TextSteganography.HOMOGLYPH_MAP }
        assertEquals(maxOf(0, subst / 8 - 4), stego.capacity(text, TextSteganography.Method.HOMOGLYPH))
        val spaces = text.count { it == ' ' }
        assertEquals(maxOf(0, spaces / 8 - 4), stego.capacity(text, TextSteganography.Method.WHITESPACE))
    }

    @Test
    fun `cover corpus is the iOS five-by-five`() {
        for (style in TextSteganography.CoverTextStyle.values()) {
            val t = stego.coverTemplates(style)
            assertEquals("style $style", 5, t.size)
            t.forEach { assertTrue("template too short for iOS parity: ${it.take(40)}", it.length >= 300) }
        }
    }
}
