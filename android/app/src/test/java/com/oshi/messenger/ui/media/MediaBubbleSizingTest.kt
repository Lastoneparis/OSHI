package com.oshi.messenger.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __BUBBLE_MEDIA_2026_11_21__ BUBBLE_MEDIA_SPEC §1 — the box a picture gets in a bubble.
 * The bubble IS this box's width, so every case below is also "no empty band beside it".
 */
class MediaBubbleSizingTest {

    private val phone = 411f                                   // Pixel-class width, dp
    private val chatMax = MediaBubbleSizing.maxBubbleWidth(phone, channel = false)
    private val channelMax = MediaBubbleSizing.maxBubbleWidth(phone, channel = true)

    @Test
    fun `max bubble width is 75 percent capped at 420 and 90 percent capped at 560 for channels`() {
        assertEquals(308.25f, chatMax, 0.01f)
        assertEquals(369.9f, channelMax, 0.01f)
        assertEquals(420f, MediaBubbleSizing.maxBubbleWidth(1200f, channel = false), 0f)
        assertEquals(560f, MediaBubbleSizing.maxBubbleWidth(1200f, channel = true), 0f)
    }

    @Test
    fun `a large landscape photo takes the full bubble width at its own aspect, uncropped`() {
        val s = MediaBubbleSizing.displaySize(1600f, 1000f, chatMax)
        assertEquals(chatMax, s.widthDp, 0.1f)
        assertEquals(chatMax / 1.6f, s.heightDp, 0.1f)
        assertFalse(s.cropped)
    }

    @Test
    fun `a tall portrait is centre-cropped at 1_3 x width - never letterboxed`() {
        val s = MediaBubbleSizing.displaySize(1080f, 1920f, chatMax)
        assertEquals(chatMax, s.widthDp, 0.1f)
        assertEquals(chatMax * 1.3f, s.heightDp, 0.1f)
        assertTrue(s.cropped)
    }

    @Test
    fun `a mild portrait under the cap keeps its exact aspect`() {
        val s = MediaBubbleSizing.displaySize(1000f, 1250f, chatMax)   // 4:5
        assertEquals(chatMax * 1.25f, s.heightDp, 0.1f)
        assertFalse(s.cropped)
    }

    @Test
    fun `a panorama is cropped at half its width instead of becoming a strip`() {
        val s = MediaBubbleSizing.displaySize(3000f, 600f, chatMax)
        assertEquals(chatMax, s.widthDp, 0.1f)
        assertEquals(chatMax * 0.5f, s.heightDp, 0.1f)
        assertTrue(s.cropped)
    }

    @Test
    fun `a small image is scaled UP to the 200 dp minimum rather than leaving a gap`() {
        val s = MediaBubbleSizing.displaySize(61.5f, 46f, chatMax)     // 160x120 px at 2.6x
        assertEquals(200f, s.widthDp, 0f)
        assertEquals(200f * 46f / 61.5f, s.heightDp, 0.1f)
        assertFalse(s.cropped)
    }

    @Test
    fun `a mid-size image keeps its natural width`() {
        val s = MediaBubbleSizing.displaySize(250f, 250f, chatMax)
        assertEquals(250f, s.widthDp, 0f)
        assertEquals(250f, s.heightDp, 0f)
    }

    @Test
    fun `channel posts use the wider card`() {
        val s = MediaBubbleSizing.displaySize(1600f, 1066f, channelMax)
        assertEquals(channelMax, s.widthDp, 0.1f)
    }

    @Test
    fun `unknown size gives the placeholder box at full width so the list does not jump in width`() {
        val s = MediaBubbleSizing.displaySize(0f, 0f, chatMax)
        assertEquals(chatMax, s.widthDp, 0.1f)
        assertEquals(chatMax * 3f / 4f, s.heightDp, 0.1f)
        assertFalse(s.cropped)
    }

    @Test
    fun `on a screen narrower than the minimum the bubble cap wins`() {
        val narrowMax = MediaBubbleSizing.maxBubbleWidth(240f, channel = false)   // 180
        val s = MediaBubbleSizing.displaySize(40f, 40f, narrowMax)
        assertEquals(narrowMax, s.widthDp, 0.1f)
    }

    @Test
    fun `height always stays within the 0_5 to 1_3 band for any aspect`() {
        for (w in listOf(10f, 100f, 500f, 4000f)) for (h in listOf(10f, 100f, 500f, 4000f)) {
            val s = MediaBubbleSizing.displaySize(w, h, chatMax)
            assertTrue("$w x $h", s.heightDp >= s.widthDp * 0.5f - 0.1f && s.heightDp <= s.widthDp * 1.3f + 0.1f)
            assertTrue("$w x $h", s.widthDp in 200f..chatMax + 0.1f)
        }
    }
}
