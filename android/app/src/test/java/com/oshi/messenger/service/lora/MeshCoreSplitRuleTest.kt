package com.oshi.messenger.service.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The split rule shared with iOS (MeshCoreInterop.splitText): the same cases, with the same inputs and expected
 * values, as OSHITests/MeshCoreRadioTests.swift (testShortTextIsOneMessage, testLongTextIsNumberedParts,
 * testSplitRespectsTheByteBudget, testLabelRoomIsReservedForTheDigitCount, testCharactersAreCodePoints,
 * testAnEmojiIsNeverCutInHalf, testCutsAtTheLastSpaceInTheSecondHalf), plus flags, CJK, the exact boundaries and
 * the 20-part limit. A change on one platform must change both.
 */
class MeshCoreSplitRuleTest {
    private fun split(t: String, maxBytes: Int = MeshCoreText.DM_MAX_BYTES) = MeshCoreText.split(t, maxBytes)
    private fun cps(s: String) = s.codePointCount(0, s.length)
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size
    private fun body(p: String) = p.substringAfter(") ")

    // ── iOS cases, verbatim ──────────────────────────────────────────────

    @Test fun shortTextIsOneMessage() {
        assertEquals(listOf("hello"), split("hello"))
        assertEquals(1, split("a".repeat(133))!!.size)
        assertNull(split("   "))
        assertEquals(listOf("hello"), split("  hello \n"))                              // trimmed, no label
    }

    @Test fun longTextIsNumberedParts() {
        val words = (0 until 60).joinToString(" ") { "word$it" }
        val parts = split(words)!!
        assertTrue(parts.size > 1)
        parts.forEachIndexed { i, p ->
            assertTrue(p, p.startsWith("(${i + 1}/${parts.size}) "))
            assertTrue(cps(p) <= 133); assertTrue(bytes(p) <= MeshCoreText.DM_MAX_BYTES)
            assertFalse(p.endsWith(" "))
        }
        assertEquals(words, parts.joinToString(" ") { body(it) })                       // every word whole, in order
        assertEquals(2, split("a".repeat(134))!!.size)
        // The 45-word DM iOS sends as exactly 3 parts.
        assertEquals(3, split((0 until 45).joinToString(" ") { "word$it" })!!.size)
    }

    @Test fun splitRespectsTheByteBudget() {
        val emoji = "🛰️".repeat(100)
        assertTrue(split(emoji)!!.all { bytes(it) <= MeshCoreText.DM_MAX_BYTES })
        assertEquals(156, MeshCoreText.DM_MAX_BYTES)
        assertEquals(160 - 4 - 2 - 2, MeshCoreText.channelMaxBytes("Hugo"))
        assertEquals(125, MeshCoreText.channelMaxBytes(null))
        assertEquals(125, MeshCoreText.channelMaxBytes("n".repeat(40)))
        assertEquals(1, split("é".repeat(78))!!.size)                                   // 156 B
        assertEquals(2, split("é".repeat(79))!!.size)                                   // 158 B
        val budget = MeshCoreText.channelMaxBytes("Hugo")
        assertTrue(split("b".repeat(200), budget)!!.all { bytes(it) <= budget })
        assertNull(split("c".repeat(133 * 25)))                                         // beyond 20 parts
    }

    @Test fun labelRoomIsReservedForTheDigitCount() {
        val parts = split("x".repeat(125 * 12 + 1))!!
        assertEquals(13, parts.size)
        assertTrue(parts[0].startsWith("(1/13) "))
        assertEquals("(1/13) ".length + 133 - 8, cps(parts[0]))
        assertTrue(parts.all { cps(it) <= 133 && bytes(it) <= 156 })
        val nine = split("y".repeat(127 * 3))!!
        assertEquals(3, nine.size)
        assertEquals("(1/3) " + "y".repeat(127), nine[0])
    }

    @Test fun charactersAreCodePoints() {
        assertEquals(1, split("🛰️".repeat(22))!!.size)                                   // 44 cp, 154 B
        assertEquals(2, split("🛰️".repeat(23))!!.size)                                   // 161 B > 156
    }

    @Test fun anEmojiIsNeverCutInHalf() {
        val family = "👨‍👩‍👧‍👦"                                                          // 7 cp, 25 B, one grapheme
        val parts = split("a".repeat(124) + family + "b".repeat(40))!!
        assertEquals(2, parts.size)
        assertTrue(parts.toString(), parts.all { !it.contains('‍') || it.contains(family) })
        assertEquals("(1/2) " + "a".repeat(124), parts[0])                              // backed up to the emoji's start
        assertTrue(parts[1].contains(family))
    }

    @Test fun cutsAtTheLastSpaceInTheSecondHalf() {
        assertEquals(
            listOf("(1/2) " + "a".repeat(100), "(2/2) " + "b".repeat(60)),
            split("a".repeat(100) + " " + "b".repeat(60)),
        )
        val p2 = split("a".repeat(20) + " " + "b".repeat(200))!!
        assertEquals(133, cps(p2[0]))                                                   // early space ignored: hard cut
    }

    // ── Beyond iOS's list (same rule) ────────────────────────────────────

    @Test fun flagsStayWholePairs() {
        val flags = "🇫🇷🇩🇪🇯🇵".repeat(30)                                                // 180 cp, 720 B
        val parts = split(flags)!!
        assertTrue(parts.size > 1)
        parts.forEach { p ->
            val ri = body(p).codePoints().filter { it in 0x1F1E6..0x1F1FF }.count()
            assertEquals(p, 0L, ri % 2)                                                 // never half a flag
            assertTrue(bytes(p) <= 156)
        }
        assertEquals(flags, parts.joinToString("") { body(it) })
        // Skin-tone modifiers stay on their emoji.
        val thumbs = split("👍🏽".repeat(40))!!
        thumbs.forEach { assertTrue(body(it).codePointAt(0) == 0x1F44D) }
    }

    @Test fun cjkIsBudgetedInBytes() {
        assertEquals(listOf("漢".repeat(52)), split("漢".repeat(52)))                      // 156 B exactly
        val two = split("漢".repeat(53))!!
        assertEquals(2, two.size)
        assertEquals("(1/2) " + "漢".repeat(50), two[0])                                  // 6 + 150 B
        assertEquals(listOf("한국어 메시지"), split("한국어 메시지"))
    }

    @Test fun exactlyAtTheBoundaries() {
        assertEquals(1, split("a".repeat(133))!!.size)
        assertEquals(2, split("a".repeat(134))!!.size)
        val b = MeshCoreText.channelMaxBytes("OSHI-TEA-MC")                              // 145
        assertEquals(1, split("é".repeat(72) + "x", b)!!.size)                          // 145 B
        assertEquals(2, split("é".repeat(73), b)!!.size)                                // 146 B
    }

    @Test fun twentyPartsIsTheLimit() {
        assertEquals(20, split("z".repeat(125 * 20))!!.size)
        assertNull(split("z".repeat(125 * 20 + 1)))                                     // 21 parts: refused
        val ten = split("z".repeat(127 * 9 + 1))!!                                      // 10 parts: two-digit labels
        assertEquals(10, ten.size)
        assertTrue(ten[9].startsWith("(10/10) ")); assertTrue(ten.all { cps(it) <= 133 })
    }
}
