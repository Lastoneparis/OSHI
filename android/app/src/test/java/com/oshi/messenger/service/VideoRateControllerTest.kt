package com.oshi.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** __VIDEO_ABR_2026_09_23__ The send-side ladder shared by Android and the desktop. */
class VideoRateControllerTest {

    private var now = 0L
    private fun rc() = VideoRateController(1_200_000, nowMs = { now })

    private fun run(c: VideoRateController, ms: Long, each: (VideoRateController) -> Unit = {}): VideoRateController.Decision {
        var d = c.tick()
        var t = 0L
        while (t < ms) {
            now += VideoRateController.TICK_MS; t += VideoRateController.TICK_MS
            each(c)
            d = c.tick()
        }
        return d
    }

    @Test
    fun `a clean link stays at the ceiling`() {
        val d = run(rc(), 20_000) { it.onLossSample(0.0); it.onRtt(40.0) }
        assertEquals(0, d.rung)
        assertEquals(1_200_000, d.bitrateBps)
        assertEquals(30, d.fps)
    }

    @Test
    fun `the congestion mapping is iOS's audioCongestionFactor`() {
        assertEquals(1.0, VideoRateController.lossFactor(0.0), 1e-9)
        assertEquals(0.7, VideoRateController.lossFactor(3.0), 1e-9)
        assertEquals(0.0, VideoRateController.lossFactor(12.0), 1e-9)
        assertEquals(1.0, VideoRateController.rttFactor(0.0), 1e-9)
        assertEquals(1.0, VideoRateController.rttFactor(80.0), 1e-9)
        assertEquals(0.5, VideoRateController.rttFactor(240.0), 1e-9)
        assertEquals(0.0, VideoRateController.rttFactor(500.0), 1e-9)
    }

    @Test
    fun `heavy loss steps down fast, to the floor and the low rungs`() {
        val c = rc()
        now = 5_000
        var firstDrop = -1L
        val start = now
        val d = run(c, 8_000) {
            it.onLossSample(9.0)
            if (firstDrop < 0 && it.currentRung > 0) firstDrop = now - start
        }
        assertTrue("first step within a second, was $firstDrop ms", firstDrop in 0..1_000)
        assertEquals(VideoRateController.LADDER.size - 1, d.rung)
        assertEquals(VideoRateController.FLOOR_BPS, d.bitrateBps)
        assertTrue(d.fps < 30 && d.scalePct < 100)
    }

    @Test
    fun `it climbs back slowly, one rung at a time`() {
        val c = rc()
        now = 5_000
        run(c, 8_000) { it.onLossSample(9.0) }
        val low = c.currentRung
        // Link heals: the first rung up needs UP_HOLD_MS of headroom.
        run(c, 3_000) { it.onLossSample(0.0) }
        assertEquals("no climb before the hold", low, c.currentRung)
        run(c, 3_000) { it.onLossSample(0.0) }
        assertEquals(low - 1, c.currentRung)
        run(c, 60_000) { it.onLossSample(0.0) }
        assertEquals(0, c.currentRung)
    }

    /** The peer's keyframe requests are feedback about OUR uplink. */
    @Test
    fun `the peer's keyframe requests step the bitrate down`() {
        val c = rc()
        now = 10_000
        val d = run(c, 3_000) { it.onPeerKeyframeRequest(); it.onPeerKeyframeRequest() }
        assertTrue("rung ${d.rung}", d.rung >= 2)
        assertEquals(12L, c.peerKeyframeRequests)
    }

    @Test
    fun `requests during the warm-up do not count`() {
        val c = rc()
        val d = run(c, 2_500) { it.onPeerKeyframeRequest() }
        assertEquals(0, d.rung)
    }

    @Test
    fun `the floor holds on a dead link`() {
        val c = VideoRateController(600_000, nowMs = { now })
        now = 5_000
        val d = run(c, 20_000) { it.onLossSample(50.0); it.onRtt(900.0) }
        assertEquals(VideoRateController.FLOOR_BPS, d.bitrateBps)
    }

    @Test
    fun `rung changes are few, not one per tick`() {
        val c = rc()
        now = 5_000
        var flip = false
        run(c, 60_000) { flip = !flip; it.onLossSample(if (flip) 4.0 else 0.0) }
        assertTrue("changes ${c.rungChanges}", c.rungChanges <= 6)
    }
}
