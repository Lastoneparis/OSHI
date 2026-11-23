package com.oshi.messenger.service.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * __QUALITY_FROM_WHAT_IS_HEARD_2026_09_22__ — the iOS badge rules, ported.
 */
class CallQualityPolicyTest {

    // ---------------------------------------------------------------- tiers

    @Test
    fun perfect_call_with_5G_jitter_bursts_is_excellent() {
        // Call 6216CD2F: J 22–43 ms, 0.0 % heard loss. Jitter below the cap does not count.
        assertEquals(NetworkQuality.EXCELLENT, CallQualityPolicy.tier(0.0, 30, 43.0))
    }

    @Test
    fun jitter_only_counts_past_150ms_and_then_caps_at_good() {
        assertEquals(NetworkQuality.EXCELLENT, CallQualityPolicy.tier(0.0, 30, 149.9))
        assertEquals(NetworkQuality.GOOD, CallQualityPolicy.tier(0.0, 30, 150.0))
        assertEquals(NetworkQuality.GOOD, CallQualityPolicy.tier(0.0, 30, 900.0))
    }

    @Test
    fun loss_bands_are_ios_bands() {
        assertEquals(NetworkQuality.EXCELLENT, CallQualityPolicy.tier(1.99, 0, 0.0))
        assertEquals(NetworkQuality.GOOD, CallQualityPolicy.tier(2.0, 0, 0.0))
        assertEquals(NetworkQuality.GOOD, CallQualityPolicy.tier(4.99, 0, 0.0))
        assertEquals(NetworkQuality.FAIR, CallQualityPolicy.tier(5.0, 0, 0.0))
        assertEquals(NetworkQuality.FAIR, CallQualityPolicy.tier(9.99, 0, 0.0))
        assertEquals(NetworkQuality.POOR, CallQualityPolicy.tier(10.0, 0, 0.0))
        assertEquals(NetworkQuality.POOR, CallQualityPolicy.tier(19.99, 0, 0.0))
        assertEquals(NetworkQuality.VERY_POOR, CallQualityPolicy.tier(20.0, 0, 0.0))
    }

    @Test
    fun latency_bands_and_untrusted_readings() {
        assertEquals(NetworkQuality.EXCELLENT, CallQualityPolicy.tier(0.0, 99, 0.0))
        assertEquals(NetworkQuality.GOOD, CallQualityPolicy.tier(0.0, 100, 0.0))
        assertEquals(NetworkQuality.FAIR, CallQualityPolicy.tier(0.0, 200, 0.0))
        assertEquals(NetworkQuality.POOR, CallQualityPolicy.tier(0.0, 300, 0.0))
        // ≥ 400 ms is an HTTP round trip to the VPS, not the call: ignored.
        assertEquals(NetworkQuality.EXCELLENT, CallQualityPolicy.tier(0.0, 400, 0.0))
        // 0 = unknown (an iPhone never echoes Android's keepalive).
        assertEquals(NetworkQuality.EXCELLENT, CallQualityPolicy.tier(0.0, 0, 0.0))
    }

    // ---------------------------------------------------------------- tracker

    /** Feed one 2 s window of [real] real frames and [concealed] PLC frames. */
    private class Feed(val t: CallQualityTracker) {
        var real = 0L
        var concealed = 0L
        fun window(real: Int, concealed: Int, latency: Long = 30, jitter: Double = 20.0): NetworkQuality {
            this.real += real; this.concealed += concealed
            return t.sample(this.real, this.concealed, latency, jitter)
        }
    }

    @Test
    fun clean_windows_show_excellent() {
        val f = Feed(CallQualityTracker())
        assertEquals(NetworkQuality.EXCELLENT, f.window(100, 0))
    }

    @Test
    fun a_single_bad_window_does_not_repaint_the_badge() {
        val f = Feed(CallQualityTracker())
        f.window(100, 0)
        // 10 % concealed in one window → EWMA 3 % → GOOD, but only ONE sample: held.
        assertEquals(NetworkQuality.EXCELLENT, f.window(90, 10))
        // Next window clean → EWMA 2.1 % → GOOD again: second confirmation → shown.
        assertEquals(NetworkQuality.GOOD, f.window(100, 0))
        // Clean → EWMA 1.47 % → EXCELLENT: upgrades show immediately.
        assertEquals(NetworkQuality.EXCELLENT, f.window(100, 0))
    }

    @Test
    fun a_confirmed_downgrade_shows_the_less_severe_of_the_two_samples() {
        val f = Feed(CallQualityTracker())
        f.window(100, 0)
        assertEquals(NetworkQuality.EXCELLENT, f.window(50, 50)) // EWMA 15 → POOR, pending
        // EWMA 25.5 → VERY_POOR; pending POOR is less severe → POOR shown.
        assertEquals(NetworkQuality.POOR, f.window(50, 50))
    }

    @Test
    fun silence_is_not_loss() {
        // A muted peer: nothing real, the buffer concealed 5 frames then went silent.
        // < 10 frames played in the window is no evidence — the EWMA does not move.
        val f = Feed(CallQualityTracker())
        f.window(100, 0)
        assertEquals(NetworkQuality.EXCELLENT, f.window(0, 5))
        assertEquals(NetworkQuality.EXCELLENT, f.window(0, 0))
        assertEquals(0.0, f.t.lossEwmaPercent, 1e-9)
    }

    @Test
    fun reconnecting_is_left_immediately_for_the_sampled_tier() {
        // 20 % concealed → EWMA 6 % → FAIR, shown at once: RECONNECTING never holds a downgrade.
        assertEquals(NetworkQuality.FAIR, CallQualityTracker(NetworkQuality.RECONNECTING).sample(80, 20, 30, 20.0))
        assertEquals(NetworkQuality.EXCELLENT, CallQualityTracker(NetworkQuality.RECONNECTING).sample(100, 0, 30, 20.0))
    }
}
