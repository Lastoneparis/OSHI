package com.oshi.messenger

import com.oshi.messenger.service.audio.AdaptiveBitrateController
import com.oshi.messenger.service.audio.AdaptiveCodecBitratePolicy
import com.oshi.messenger.service.audio.AdaptiveCodecBitratePolicy.HIGH_BPS
import com.oshi.messenger.service.audio.AdaptiveCodecBitratePolicy.LOW_BPS
import com.oshi.messenger.service.audio.AdaptiveCodecBitratePolicy.MEDIUM_BPS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flap that made calls sound "metallic", pinned as arithmetic.
 *
 * The old inline rule was `rtt < 100 && loss < 1.0 -> 80k`, read off a 50-packet
 * window whose only representable values near 1 % are 0.0 and 2.0. The encoder
 * therefore reconfigured itself once per second, all call, on a perfectly ordinary
 * link. Every test here would have failed against that rule.
 */
class AdaptiveCodecBitratePolicyTest {

    private fun run(readings: List<Pair<Long, Double>>, samples: Int = 1000): List<Int> {
        var current = MEDIUM_BPS
        return readings.map { (rtt, loss) ->
            current = AdaptiveCodecBitratePolicy.nextBitrate(current, rtt, loss, samples)
            current
        }
    }

    // ─────────────────────────────────────────────────── the regression itself

    @Test
    fun `a steady one percent loss does not move the tier at all`() {
        // 60 seconds of a link sitting exactly on the old threshold.
        val out = run(List(60) { 60L to 1.0 })
        assertEquals("the tier must never change", 1, out.distinct().size)
    }

    @Test
    fun `the quantised zero-two-zero-two reading the old window produced cannot flap`() {
        // What a 50-packet window actually reports for a true 1 % loss.
        val alternating = (0 until 60).map { 60L to if (it % 2 == 0) 0.0 else 2.0 }
        val out = run(alternating)
        assertEquals(1, out.distinct().size)
    }

    @Test
    fun `rtt hovering on the boundary cannot flap either`() {
        val hovering = (0 until 60).map { (if (it % 2 == 0) 95L else 105L) to 0.0 }
        val out = run(hovering)
        assertEquals(1, out.distinct().size)
    }

    // ─────────────────────────────────────────────────── the band is a band

    @Test
    fun `a genuinely good link still reaches the top tier`() {
        assertEquals(HIGH_BPS, AdaptiveCodecBitratePolicy.nextBitrate(MEDIUM_BPS, 40L, 0.1, 1000))
    }

    @Test
    fun `a genuinely bad link still drops to the bottom tier`() {
        assertEquals(LOW_BPS, AdaptiveCodecBitratePolicy.nextBitrate(MEDIUM_BPS, 400L, 0.0, 1000))
        assertEquals(LOW_BPS, AdaptiveCodecBitratePolicy.nextBitrate(MEDIUM_BPS, 50L, 20.0, 1000))
    }

    @Test
    fun `the dead band opposes the change being proposed, in both directions`() {
        // Sitting at HIGH, a reading inside the band keeps HIGH…
        assertEquals(HIGH_BPS, AdaptiveCodecBitratePolicy.nextBitrate(HIGH_BPS, 100L, 1.0, 1000))
        // …and sitting at MEDIUM, the SAME reading keeps MEDIUM. That is what makes it
        // a damper. (iOS's bug was a band applied only on one side, which instead made
        // degrading easy and recovering hard.)
        assertEquals(MEDIUM_BPS, AdaptiveCodecBitratePolicy.nextBitrate(MEDIUM_BPS, 100L, 1.0, 1000))
    }

    @Test
    fun `leaving LOW takes a clearly better link than staying in it`() {
        // Inside the low band: still bad enough to stay down.
        assertEquals(LOW_BPS, AdaptiveCodecBitratePolicy.nextBitrate(LOW_BPS, 300L, 0.0, 1000))
        assertEquals(LOW_BPS, AdaptiveCodecBitratePolicy.nextBitrate(LOW_BPS, 50L, 10.0, 1000))
        // Clearly recovered.
        assertEquals(MEDIUM_BPS, AdaptiveCodecBitratePolicy.nextBitrate(LOW_BPS, 200L, 3.0, 1000))
    }

    // ─────────────────────────────────────────────────── the window is evidence

    @Test
    fun `a loss figure measured over too few packets is not consulted`() {
        // 2 % measured over 50 packets is one lost packet — noise, not a signal.
        assertEquals(HIGH_BPS, AdaptiveCodecBitratePolicy.nextBitrate(MEDIUM_BPS, 40L, 2.0, 50))
        // The same figure over a real window is a signal, and holds the tier down.
        assertEquals(MEDIUM_BPS, AdaptiveCodecBitratePolicy.nextBitrate(MEDIUM_BPS, 40L, 2.0, 1000))
    }

    @Test
    fun `rtt still governs before enough loss samples exist`() {
        assertEquals(LOW_BPS, AdaptiveCodecBitratePolicy.nextBitrate(MEDIUM_BPS, 500L, 0.0, 3))
    }

    // ─────────────────────────────────────────── the window that feeds it

    @Test
    fun `the controller's codec window is long enough to represent sub-percent loss`() {
        val c = AdaptiveBitrateController()
        // 1 lost in 500 = 0.2 %, a number the 50-packet window cannot express at all.
        repeat(499) { c.reportPacketReceived() }
        c.reportPacketLost()

        val (loss, samples) = c.codecLossWindowStats
        assertEquals(500, samples)
        assertEquals(0.2, loss, 0.0001)
        assertTrue(
            "the short window can only say 0 or 2",
            c.packetLossPercent == 0.0 || c.packetLossPercent == 2.0
        )
    }

    @Test
    fun `reset empties the codec window too`() {
        val c = AdaptiveBitrateController()
        repeat(10) { c.reportPacketReceived() }
        c.reset()
        assertEquals(0 to 0.0, c.codecLossWindowStats.second to c.codecLossWindowStats.first)
    }
}
