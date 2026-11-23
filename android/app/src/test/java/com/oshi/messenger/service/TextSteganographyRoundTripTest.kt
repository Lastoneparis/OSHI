package com.oshi.messenger.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip coverage for [TextSteganography] — the test that was missing.
 *
 * Motivation: on iOS the zero-width stego was VERIFIED broken because
 * `extractZeroWidth` iterated Swift `Character` (grapheme clusters), so the
 * zero-width joiners U+200C/U+200D fused with their neighbours and
 * embed -> extract never round-tripped. Kotlin's `for (c in String)` iterates
 * UTF-16 code units (`Char`) and every zero-width marker here (U+200B, U+200C,
 * U+200D, U+FEFF) is a single BMP code unit, so the grapheme-cluster defect
 * should NOT exist on Android. These tests prove that empirically: they embed a
 * payload into cover text and assert the extracted bytes equal the input, for
 * the default method and several payload sizes including a 1-byte one.
 *
 * The class is deliberately framework-free (no android.util.*), so the real
 * code runs on the plain JVM and we exercise it directly rather than a port.
 */
class TextSteganographyRoundTripTest {

    private val stego = TextSteganography()

    // Raw 32-byte X25519-style shared secret (NOT a passphrase). Fixed so the
    // test is deterministic regardless of the random GCM nonce.
    private val secret = ByteArray(32) { (it * 7 + 3).toByte() }

    private val cover: String = stego.generateCoverText(TextSteganography.CoverTextStyle.SOCIAL_COMMENT)

    // Homoglyph and whitespace carry far fewer bits per character than zero-width
    // (one bit per substitutable letter / per inter-word gap), so give them a much
    // larger cover. The round-trip logic is identical regardless of length.
    private val richCover: String =
        (TextSteganography.CoverTextStyle.values()).joinToString("\n\n") { style ->
            stego.coverTemplates(style).joinToString(" ")
        }

    private fun payload(n: Int): ByteArray = ByteArray(n) { ((it * 31 + 17) and 0xFF).toByte() }

    // --- Full encrypt + embed + extract + decrypt round trip ------------------

    @Test
    fun zeroWidth_default_roundTrips_shortPayload() {
        val data = payload(1) // the short one
        val stegoText = stego.embed(data, cover, secret) // default method = ZERO_WIDTH
        assertNotNull("embed returned null for a 1-byte payload", stegoText)
        val out = stego.extract(stegoText!!, secret)
        assertNotNull("extract returned null", out)
        assertArrayEquals(data, out)
    }

    @Test
    fun zeroWidth_default_roundTrips_variousSizes() {
        for (n in intArrayOf(1, 2, 7, 16, 64, 200)) {
            val data = payload(n)
            val stegoText = stego.embed(data, cover, secret)
            assertNotNull("embed null for size $n", stegoText)
            // The visible cover text must survive unchanged for zero-width.
            assertEquals(
                "zero-width must not alter visible characters (size $n)",
                cover,
                stego.clean(stegoText!!)
            )
            val out = stego.extract(stegoText, secret)
            assertNotNull("extract null for size $n", out)
            assertArrayEquals("round-trip mismatch for size $n", data, out)
        }
    }

    @Test
    fun homoglyph_roundTrips() {
        val cap = stego.capacity(richCover, TextSteganography.Method.HOMOGLYPH)
        assertTrue("rich cover must have homoglyph capacity, was $cap", cap >= 4)
        val data = payload(minOf(8, cap))
        val stegoText = stego.embed(data, richCover, secret, TextSteganography.Method.HOMOGLYPH)
        assertNotNull("homoglyph embed null (cover too short?)", stegoText)
        val out = stego.extract(stegoText!!, secret, TextSteganography.Method.HOMOGLYPH)
        assertNotNull(out)
        assertArrayEquals(data, out)
    }

    @Test
    fun whitespace_roundTrips() {
        val cap = stego.capacity(richCover, TextSteganography.Method.WHITESPACE)
        assertTrue("rich cover must have whitespace capacity, was $cap", cap >= 2)
        val data = payload(minOf(4, cap))
        val stegoText = stego.embed(data, richCover, secret, TextSteganography.Method.WHITESPACE)
        assertNotNull("whitespace embed null (not enough spaces?)", stegoText)
        val out = stego.extract(stegoText!!, secret, TextSteganography.Method.WHITESPACE)
        assertNotNull(out)
        assertArrayEquals(data, out)
    }

    @Test
    fun combined_roundTrips() {
        val data = payload(12)
        val stegoText = stego.embed(data, cover, secret, TextSteganography.Method.COMBINED)
        assertNotNull(stegoText)
        // COMBINED embeds via zero-width (fallback homoglyph); auto-detect extract finds it.
        val out = stego.extract(stegoText!!, secret)
        assertNotNull(out)
        assertArrayEquals(data, out)
    }

    @Test
    fun autoDetect_findsZeroWidthPayload() {
        val data = payload(20)
        val stegoText = stego.embed(data, cover, secret, TextSteganography.Method.ZERO_WIDTH)!!
        assertTrue(stego.containsHiddenData(stegoText))
        val out = stego.extract(stegoText, secret) // method = null => auto-detect
        assertArrayEquals(data, out)
    }

    @Test
    fun wrongKey_doesNotDecrypt() {
        val data = payload(16)
        val stegoText = stego.embed(data, cover, secret)!!
        val wrong = ByteArray(32) { 0x5A }
        assertNull("GCM tag must reject a wrong key", stego.extract(stegoText, wrong))
    }

    @Test
    fun unicodeSanitization_failsClosedInsteadOfReturningAlteredPayload() {
        val data = payload(16)
        val carrier = stego.embed(data, cover, secret)!!
        val stripped = carrier.filterNot { it == '\u200B' || it == '\u200C' || it == '\u200D' || it == '\uFEFF' }
        assertNull("stripped zero-width carrier must not yield plaintext", stego.extract(stripped, secret))
    }

    // --- Raw zero-width bit layer, independent of the AES-GCM crypto ----------
    // Proves the exact defect that broke iOS (marker chars fusing into grapheme
    // clusters) is absent: embed then extract the bytes verbatim.

    @Test
    fun zeroWidthBitLayer_roundTripsWithoutCrypto() {
        for (n in intArrayOf(1, 3, 15, 33)) {
            val data = payload(n)
            val embedded = stego.embedZeroWidth(data, cover)
            assertNotNull("embedZeroWidth null for size $n", embedded)
            val out = stego.extractZeroWidth(embedded!!)
            assertArrayEquals("raw zero-width round-trip failed for size $n", data, out)
        }
    }

    @Test
    fun zeroWidthMarkersSurviveAdjacentToEachOther() {
        // Minimal 2-char cover forces many bits into one slot, so the markers
        // (U+200B / U+200C) sit immediately next to each other and next to the
        // U+FEFF start / U+200D separator. This is exactly the arrangement that
        // the Swift grapheme-cluster iteration destroyed.
        val data = payload(4)
        val embedded = stego.embedZeroWidth(data, "Hi")
        assertNotNull(embedded)
        assertArrayEquals(data, stego.extractZeroWidth(embedded!!))
    }
}
