package com.oshi.messenger.ui

import androidx.compose.ui.graphics.Color
import com.oshi.messenger.ui.theme.AvatarNameColors
import com.oshi.messenger.ui.theme.BubbleAccent
import com.oshi.messenger.ui.theme.Contrast
import com.oshi.messenger.ui.theme.Contrast.over
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.theme.oshiColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __BUBBLE_MEDIA_2026_11_21__ BUBBLE_MEDIA_SPEC §2 — every new bubble colour, measured.
 * Body text ≥ 4.5:1 against its bubble (both stops of a ramp, sheen composited);
 * timestamps / ticks ≥ 3:1. Light, Dark and OLED.
 */
class BubbleColorTokensTest {

    private val oshi = BubbleAccent.OSHI

    /** Both stops of the outgoing ramp, plus the light stop under the white top sheen. */
    private fun outgoingGrounds(dark: Boolean): List<Color> {
        val (a, b) = oshi.surfaceStops(dark)
        return listOf(a, b, Color.White.copy(alpha = Oshi.Bubble.outgoingSheen).over(a))
    }

    private fun worst(ink: Color, grounds: List<Color>) = grounds.minOf { Contrast.ratio(ink.over(it), it) }

    @Test
    fun `values are the spec's`() {
        assertEquals(Color(0xFFEFE8FF), Oshi.Bubble.outgoingLightStart)
        assertEquals(Color(0xFFE6DCFF), Oshi.Bubble.outgoingLightEnd)
        assertEquals(Color(0xFF5B47B3), Oshi.Bubble.outgoingDarkStart)
        assertEquals(Color(0xFF4A3B8F), Oshi.Bubble.outgoingDarkEnd)
        assertEquals(Color(0xFFFFFFFF), Oshi.Bubble.receivedLight)
        assertEquals(Color(0xFF22252B), Oshi.Bubble.receivedDark)
        assertEquals(Color(0xFF1C1C1E), Oshi.Bubble.receivedOled)
        assertEquals(Color(0xFF17181C), Oshi.Chat.groundDarkTop)
        assertEquals(Color(0xFF0F1115), Oshi.Chat.groundDarkBottom)
    }

    @Test
    fun `light outgoing - near-black body, meta and brand link all clear AA`() {
        val g = outgoingGrounds(dark = false)
        assertTrue(worst(oshi.ink(false), g) >= 12.0)
        assertTrue("meta ${worst(oshi.metaInk(false), g)}", worst(oshi.metaInk(false), g) >= 4.5)
        assertTrue("link ${worst(oshi.linkInk(false), g)}", worst(oshi.linkInk(false), g) >= 4.5)
        assertTrue(worst(Oshi.Chat.readTick(false), g) >= 3.0)
    }

    @Test
    fun `dark outgoing - white body clears AA, meta and read tick clear 3 to 1`() {
        val g = outgoingGrounds(dark = true)
        assertTrue("body ${worst(oshi.ink(true), g)}", worst(oshi.ink(true), g) >= 4.5)
        assertTrue("meta ${worst(oshi.metaInk(true), g)}", worst(oshi.metaInk(true), g) >= 4.5)
        assertTrue("tick ${worst(Oshi.Chat.readTick(true), g)}", worst(Oshi.Chat.readTick(true), g) >= 3.0)
    }

    @Test
    fun `the other accents still carry white ink`() {
        for (a in BubbleAccent.entries.filter { it != BubbleAccent.OSHI }) for (dark in listOf(false, true)) {
            assertEquals(Color.White, a.ink(dark))
            val (s, e) = a.surfaceStops(dark)
            assertTrue("$a", Contrast.ratio(Color.White, s) >= 4.5 && Contrast.ratio(Color.White, e) >= 4.5)
        }
    }

    @Test
    fun `received surfaces carry body, meta, link, brand ink and every group name colour`() {
        for ((dark, oled) in listOf(false to false, true to false, true to true)) {
            val fill = Oshi.Bubble.received(dark, oled)
            val label = "dark=$dark oled=$oled"
            assertTrue(label, Contrast.ratio(oshiColorScheme(dark).onSurface, fill) >= 7.0)
            assertTrue(label, Contrast.ratio(Oshi.Chat.metaInk(dark), fill) >= 4.5)
            assertTrue(label, Contrast.ratio(Oshi.brandInk(dark), fill) >= 4.5)
            assertTrue(label, Contrast.ratio(Oshi.Chat.readTick(dark), fill) >= 3.0)
            for (c in if (dark) AvatarNameColors.dark else AvatarNameColors.light) {
                assertTrue("$label name $c", Contrast.ratio(c, fill) >= 4.5)
            }
        }
    }

    @Test
    fun `the time pill is readable over a white photo`() {
        val pill = Oshi.Bubble.mediaPillFill.over(Color.White)
        assertTrue(Contrast.ratio(Oshi.Bubble.mediaPillInk, pill) >= 4.5)
    }

    @Test
    fun `bubbles separate from their ground`() {
        // Not a WCAG text pair, so the floor is the rim's job; the fills still differ.
        assertTrue(Contrast.ratio(Oshi.Bubble.receivedLight, Oshi.Chat.groundLightBottom) > 1.1)
        assertTrue(Contrast.ratio(Oshi.Bubble.receivedDark, Oshi.Chat.groundDarkBottom) > 1.2)
        // The tonal outgoing bubble gets a violet rim in light, not the invisible white one.
        assertEquals(Color(0xFF2A1F4D), Oshi.Bubble.rimInk(outgoing = true, darkTheme = false, tonal = true))
        assertEquals(Color.White, Oshi.Bubble.rimInk(outgoing = true, darkTheme = false, tonal = false))
    }

    @Test
    fun `link ink default follows the body ink - the old fixed light blue failed on white`() {
        assertTrue(Contrast.ratio(Color(0xFF64B5F6), Color.White) < 3.0)
        assertTrue(Contrast.ratio(OSHIPurple, Oshi.Bubble.receivedLight) >= 4.5)
    }
}
