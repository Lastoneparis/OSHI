package com.oshi.messenger.ui

import androidx.compose.ui.graphics.Color
import com.oshi.messenger.ui.theme.BubbleAccent
import com.oshi.messenger.ui.theme.BubbleSilhouette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * F-13. The two things about the bubble styles that are decidable off-device:
 * the contrast of white ink on every accent stop, and the stored-value encoding
 * that decides whether "never chosen" means "off" or "comic".
 */
class BubbleAppearanceTest {

    // WCAG 2.1 relative luminance, then contrast against pure white.
    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrastWithWhite(color: Color): Double = 1.05 / (luminance(color) + 0.05)

    /**
     * iOS ships four accents that fail this and documents it honestly
     * (BubbleStyleManager.swift:71-90): ocean 3.62, forest 2.54, rose 3.38 at
     * stop 0, and sunset 2.88/3.94 at both. Stop 0 is the top-leading corner,
     * i.e. exactly where the first line of text starts. Porting those values
     * unchanged would port the defect; this test is what stops that happening
     * again the next time someone tweaks a hue.
     */
    @Test
    fun `white ink clears WCAG AA on both stops of every accent`() {
        for (accent in BubbleAccent.entries) {
            val start = contrastWithWhite(accent.stopStart)
            val end = contrastWithWhite(accent.stopEnd)
            assertTrue(
                "${accent.name} stop0 contrast $start is below 4.5:1",
                start >= 4.5
            )
            assertTrue(
                "${accent.name} stop1 contrast $end is below 4.5:1",
                end >= 4.5
            )
        }
    }

    @Test
    fun `the gradient runs light to dark, never the other way`() {
        // A ramp that darkens downwards is what makes the bubble read as a
        // surface. Inverted, the first line of text sits on the darkest point
        // and the bubble looks lit from below.
        for (accent in BubbleAccent.entries) {
            assertTrue(
                "${accent.name} gradient is inverted",
                luminance(accent.stopStart) > luminance(accent.stopEnd)
            )
        }
    }

    @Test
    fun `every accent inks white and every silhouette declares insets`() {
        for (accent in BubbleAccent.entries) {
            assertEquals(Color.White, accent.onAccent)
        }
        for (silhouette in BubbleSilhouette.entries) {
            // A silhouette with no reserved room is one that draws over its own
            // text. There is no legitimate zero here.
            assertTrue(silhouette.sample.isNotEmpty())
            assertTrue(silhouette.labelRes != 0)
        }
    }

    // MARK: - The rawValue + 1 trick

    @Test
    fun `a never-written preference means OFF, not comic`() {
        // 0 is what SharedPreferences returns for a key nobody has written. If
        // that decoded as ordinal 0, every existing user would wake up to comic
        // bubbles after the update (BubbleStyleManager.swift:180-186).
        assertNull(BubbleSilhouette.fromStored(0))
    }

    @Test
    fun `every silhouette round-trips through the stored encoding`() {
        assertEquals(0, BubbleSilhouette.toStored(null))
        for (silhouette in BubbleSilhouette.entries) {
            val stored = BubbleSilhouette.toStored(silhouette)
            assertTrue("stored value must be positive", stored > 0)
            assertEquals(silhouette, BubbleSilhouette.fromStored(stored))
        }
    }

    @Test
    fun `an unknown stored value falls back to off, never to nothing rendered`() {
        assertNull(BubbleSilhouette.fromStored(99))
        assertNull(BubbleSilhouette.fromStored(-3))
        assertEquals(BubbleAccent.OSHI, BubbleAccent.fromStored(99))
        assertEquals(BubbleAccent.OSHI, BubbleAccent.fromStored(-1))
    }

    @Test
    fun `accent stored encoding is the plain ordinal`() {
        // Unlike the silhouette there is no off state, so 0 legitimately means
        // the default accent and no offset is needed.
        assertEquals(BubbleAccent.OSHI, BubbleAccent.fromStored(0))
        assertEquals(BubbleAccent.ROSE, BubbleAccent.fromStored(BubbleAccent.ROSE.ordinal))
    }
}
