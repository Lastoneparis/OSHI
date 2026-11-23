package com.oshi.messenger

import com.oshi.messenger.ui.components.SwipeToReplyDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RP-1 — swipe-to-reply, pinned to iOS's numbers.
 *
 * Reference: `OSHI/GroupViews.swift:8615-8666`, `struct SwipeToReply: ViewModifier`.
 * These are feel constants; a silent drift in any of them is a regression nobody catches
 * in a code review, so each row of the port table gets an assertion.
 */
class SwipeToReplyTest {

    private val threshold = SwipeToReplyDefaults.COMMIT_THRESHOLD_DP
    private val maxOffset = SwipeToReplyDefaults.MAX_OFFSET_DP

    // ── the constants themselves ─────────────────────────────────────────────

    @Test
    fun `constants match the iOS reference exactly`() {
        // GroupViews.swift:8645 — DragGesture(minimumDistance: 18)
        assertEquals(18f, SwipeToReplyDefaults.SWIPE_MIN_DISTANCE_DP, 0f)
        // :8650 — abs(dx) > abs(dy) * 1.5
        assertEquals(1.5f, SwipeToReplyDefaults.HORIZONTAL_INTENT_RATIO, 0f)
        // :8655 — min(translation.width * 0.55, 72)
        assertEquals(0.55f, SwipeToReplyDefaults.RUBBER_BAND_FACTOR, 0f)
        assertEquals(72f, SwipeToReplyDefaults.MAX_OFFSET_DP, 0f)
        // :8629 — private let threshold: CGFloat = 56
        assertEquals(56f, SwipeToReplyDefaults.COMMIT_THRESHOLD_DP, 0f)
        // :8641 / :8639 — arrow offset -28, opacity capped at 0.9
        assertEquals(28f, SwipeToReplyDefaults.ARROW_OFFSET_DP, 0f)
        assertEquals(0.9f, SwipeToReplyDefaults.ARROW_MAX_ALPHA, 0f)
    }

    // ── rubber-banding and the cap ───────────────────────────────────────────

    @Test
    fun `offset is rubber-banded at 0_55`() {
        assertEquals(55f, SwipeToReplyDefaults.offsetFor(100f, maxOffset), 0.001f)
        assertEquals(11f, SwipeToReplyDefaults.offsetFor(20f, maxOffset), 0.001f)
    }

    @Test
    fun `offset is capped at 72`() {
        // 200 * 0.55 = 110, well past the cap.
        assertEquals(72f, SwipeToReplyDefaults.offsetFor(200f, maxOffset), 0.001f)
        assertEquals(72f, SwipeToReplyDefaults.offsetFor(10_000f, maxOffset), 0.001f)
    }

    @Test
    fun `swipe is right-only`() {
        // iOS: `guard g.translation.width > 0` — a LEFT drag does nothing at all,
        // for sent and received bubbles alike. It is not mirrored per side.
        assertEquals(0f, SwipeToReplyDefaults.offsetFor(-100f, maxOffset), 0f)
        assertEquals(0f, SwipeToReplyDefaults.offsetFor(-1f, maxOffset), 0f)
        assertEquals(0f, SwipeToReplyDefaults.offsetFor(0f, maxOffset), 0f)
    }

    // ── the commit threshold ─────────────────────────────────────────────────

    @Test
    fun `commit happens at exactly 56 and not below`() {
        assertFalse(SwipeToReplyDefaults.commits(55.99f, threshold))
        assertTrue(SwipeToReplyDefaults.commits(56f, threshold))
        assertTrue(SwipeToReplyDefaults.commits(72f, threshold))
    }

    @Test
    fun `the cap is reachable past the threshold`() {
        // A finger has to be able to actually cross 56 after rubber-banding:
        // 56 / 0.55 ≈ 101.8 raw px, comfortably inside a phone's width.
        val rawNeeded = threshold / SwipeToReplyDefaults.RUBBER_BAND_FACTOR
        assertTrue("threshold must be reachable before the cap", rawNeeded * SwipeToReplyDefaults.RUBBER_BAND_FACTOR <= maxOffset)
        assertTrue(SwipeToReplyDefaults.commits(SwipeToReplyDefaults.offsetFor(rawNeeded, maxOffset), threshold))
    }

    // ── horizontal-intent guard ──────────────────────────────────────────────

    @Test
    fun `a mostly-vertical drag is not a swipe`() {
        // A list scroll: 10 across, 40 down.
        assertFalse(SwipeToReplyDefaults.isHorizontalIntent(10f, 40f))
        // Diagonal at 45°: 30/30 — dx must beat dy by 1.5x, so this is NOT a swipe.
        assertFalse(SwipeToReplyDefaults.isHorizontalIntent(30f, 30f))
        // Just under the ratio.
        assertFalse(SwipeToReplyDefaults.isHorizontalIntent(29f, 20f))
    }

    @Test
    fun `a mostly-horizontal drag is a swipe`() {
        assertTrue(SwipeToReplyDefaults.isHorizontalIntent(60f, 10f))
        assertTrue(SwipeToReplyDefaults.isHorizontalIntent(31f, 20f))
        assertTrue(SwipeToReplyDefaults.isHorizontalIntent(20f, 0f))
    }

    // ── arrow affordance ─────────────────────────────────────────────────────

    @Test
    fun `arrow alpha ramps to 0_9 and stops there`() {
        assertEquals(0f, SwipeToReplyDefaults.arrowAlphaFor(0f, threshold), 0.001f)
        assertEquals(0.45f, SwipeToReplyDefaults.arrowAlphaFor(28f, threshold), 0.001f)
        assertEquals(0.9f, SwipeToReplyDefaults.arrowAlphaFor(56f, threshold), 0.001f)
        // Past the threshold it must NOT keep climbing past 0.9.
        assertEquals(0.9f, SwipeToReplyDefaults.arrowAlphaFor(72f, threshold), 0.001f)
    }

    // ── the haptic latch — the detail that makes it feel right ───────────────

    private fun hapticFires(frames: List<Float>): Int {
        var previous = 0f
        var fires = 0
        for (offset in frames) {
            if (SwipeToReplyDefaults.shouldFireHaptic(previous, offset, threshold)) fires++
            previous = offset
        }
        return fires
    }

    @Test
    fun `haptic fires once on the crossing, not on release`() {
        // A hesitant user: out past the threshold, wobble, release.
        assertEquals(
            "one crossing must fire exactly one haptic",
            1,
            hapticFires(listOf(0f, 20f, 40f, 55f, 56f, 60f, 72f, 60f, 56f))
        )
    }

    @Test
    fun `holding past the threshold does not repeat the haptic`() {
        // 60 frames of a finger held still past the threshold. The failure mode this
        // guards is a buzz on EVERY frame, which reads as a broken phone.
        assertEquals(1, hapticFires(List(60) { 70f }))
    }

    @Test
    fun `dropping back below and crossing again fires a second time`() {
        assertEquals(2, hapticFires(listOf(20f, 60f, 70f, 30f, 10f, 40f, 56f)))
    }

    @Test
    fun `never firing below the threshold`() {
        assertEquals(0, hapticFires(listOf(0f, 10f, 30f, 55.9f, 40f, 0f)))
    }
}
