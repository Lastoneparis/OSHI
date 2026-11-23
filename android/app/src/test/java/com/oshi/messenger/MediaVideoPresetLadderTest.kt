package com.oshi.messenger

import com.oshi.messenger.service.media.VideoPresetLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CHAT_VIDEO_BITRATE_2026_10_09__ The encode target, against
 * `OSHI/ChatVideoTranscoder.swift`.
 *
 * ## What these tests used to say, and why that was the bug
 *
 * This file used to assert a ladder of three *presets* — 1080p at 5 Mbps, 720p at
 * 2.5 Mbps, 540p at 1.2 Mbps — and that `select()` took the first whose estimate fit
 * the budget. Every one of those assertions passed, and the feature was broken: the
 * budget for a cloud send is 200 MB, so the top rung always fit and every clip went
 * out at 1080p and 5 Mbps. "17 Mo pour 9 s."
 *
 * The replacement does not have a bitrate to choose from a list. It DERIVES one from
 * the output pixels (`pixels × 0.085 × fps`), so the first thing these tests pin is
 * that a budget no longer buys a bigger encode.
 */
class MediaVideoPresetLadderTest {

    private val cloudBudget = 200L * 1024 * 1024
    private val meshBudget = 10L * 1024 * 1024

    // ── The regression itself ─────────────────────────────────────────────────

    @Test
    fun `a generous budget no longer buys a bigger encode — the 17 MB per 9 s bug`() {
        val nineSeconds = 9_000L
        // The clip from the report: 1080p off the camera, nine seconds.
        val plan = VideoPresetLadder.plan(1920, 1080, nineSeconds, 30, cloudBudget)

        assertEquals("720p-class long side, whatever the budget", 1280, maxOf(plan.width, plan.height))
        assertTrue("bitrate must be in the chat band, not 5-15 Mbps: ${plan.videoBitrate}",
            plan.videoBitrate in 1_800_000..3_200_000)
        // 2.5-3.5 MB, where it used to be 17.
        assertTrue("estimated ${plan.estimatedBytes} bytes", plan.estimatedBytes < 4L * 1024 * 1024)
        assertTrue(plan.fitsBudget)
    }

    @Test
    fun `the plan is the same whatever the cloud budget is`() {
        // A budget that is not binding must not change the answer at all. This is
        // the property the preset ladder did not have.
        val tenSeconds = 10_000L
        val small = VideoPresetLadder.plan(1920, 1080, tenSeconds, 30, 20L * 1024 * 1024)
        val huge = VideoPresetLadder.plan(1920, 1080, tenSeconds, 30, Long.MAX_VALUE)
        assertEquals(small, huge)
    }

    @Test
    fun `1280x720 at 30fps lands on the band iOS targets`() {
        // ChatVideoTranscoder: "1280×720 at 30 fps lands at ~2.35 Mbit/s".
        val plan = VideoPresetLadder.plan(1280, 720, 10_000L, 30, cloudBudget)
        assertEquals(1280, plan.width)
        assertEquals(720, plan.height)
        assertEquals(2_350_000.0, plan.videoBitrate.toDouble(), 60_000.0)
    }

    // ── The budget's remaining job: the mesh link ─────────────────────────────

    @Test
    fun `the mesh budget still walks the ladder down`() {
        // Three minutes at 720p is ~55 MB — far past a 10 MB link. The rungs exist
        // for exactly this, and the step down must actually save bytes.
        val threeMinutes = 180_000L
        val cloud = VideoPresetLadder.plan(1920, 1080, threeMinutes, 30, cloudBudget)
        val mesh = VideoPresetLadder.plan(1920, 1080, threeMinutes, 30, meshBudget)
        assertTrue("mesh must pick a smaller frame: ${mesh.label} vs ${cloud.label}",
            maxOf(mesh.width, mesh.height) < maxOf(cloud.width, cloud.height))
        assertTrue(mesh.estimatedBytes < cloud.estimatedBytes)
    }

    @Test
    fun `a clip too long for any rung is still planned, never truncated`() {
        // Twenty minutes fits nothing at 10 MB. The contract is the smallest rung
        // and `fitsBudget = false` — not a failure and not a cut video, because
        // "a shorter video is a worse failure than a larger one".
        val plan = VideoPresetLadder.plan(1920, 1080, 20 * 60_000L, 30, meshBudget)
        assertEquals(640, maxOf(plan.width, plan.height))
        assertFalse(plan.fitsBudget)
        assertTrue(plan.estimatedBytes > meshBudget)
    }

    @Test
    fun `a tighter budget never yields a heavier plan`() {
        for (durationMs in listOf(5_000L, 15_000L, 30_000L, 60_000L, 180_000L)) {
            val mesh = VideoPresetLadder.plan(1920, 1080, durationMs, 30, meshBudget)
            val cloud = VideoPresetLadder.plan(1920, 1080, durationMs, 30, cloudBudget)
            assertTrue("$durationMs ms: mesh planned a bigger frame than cloud",
                maxOf(mesh.width, mesh.height) <= maxOf(cloud.width, cloud.height))
            assertTrue("$durationMs ms: mesh planned more bytes than cloud",
                mesh.estimatedBytes <= cloud.estimatedBytes)
        }
    }

    // ── Floors, caps and degenerate input ─────────────────────────────────────

    @Test
    fun `the bitrate floor holds even when the budget would prefer mush`() {
        // An hour on a 10 MB link wants a few tens of kbps. 600 kbps is the floor:
        // a video the recipient cannot make out is a worse outcome than a big one.
        val plan = VideoPresetLadder.plan(640, 480, 3_600_000L, 30, 1024L)
        assertTrue(plan.videoBitrate >= VideoPresetLadder.MIN_VIDEO_BITRATE)
        assertFalse(plan.fitsBudget)
    }

    @Test
    fun `a source smaller than the ladder is never upscaled`() {
        val plan = VideoPresetLadder.plan(640, 480, 10_000L, 30, cloudBudget)
        assertEquals(640, plan.width)
        assertEquals(480, plan.height)
    }

    @Test
    fun `frame rate is capped at 30 and an unknown source rate does not mean zero`() {
        assertEquals(30, VideoPresetLadder.plan(1280, 720, 10_000L, 60, cloudBudget).frameRate)
        assertEquals(24, VideoPresetLadder.plan(1280, 720, 10_000L, 24, cloudBudget).frameRate)
        assertEquals(30, VideoPresetLadder.plan(1280, 720, 10_000L, 0, cloudBudget).frameRate)
        // …and a lower frame rate costs fewer bits, which is the point of capping it.
        assertTrue(
            VideoPresetLadder.plan(1280, 720, 10_000L, 24, cloudBudget).videoBitrate <
                VideoPresetLadder.plan(1280, 720, 10_000L, 30, cloudBudget).videoBitrate
        )
    }

    @Test
    fun `an unreadable probe still yields an encodable plan`() {
        val plan = VideoPresetLadder.plan(0, 0, 10_000L, 30, cloudBudget)
        assertTrue(plan.width > 0 && plan.height > 0)
        assertTrue(plan.videoBitrate >= VideoPresetLadder.MIN_VIDEO_BITRATE)
    }

    @Test
    fun `estimate scales with duration and counts the audio`() {
        val ten = VideoPresetLadder.estimatedBytes(2_000_000, 10.0, 0)
        val twenty = VideoPresetLadder.estimatedBytes(2_000_000, 20.0, 0)
        // Header floor aside, doubling the duration doubles the payload.
        assertEquals(2.0, (twenty - 16_384).toDouble() / (ten - 16_384), 0.01)
        assertTrue(
            VideoPresetLadder.estimatedBytes(2_000_000, 60.0, 128_000) >
                VideoPresetLadder.estimatedBytes(2_000_000, 60.0, 0)
        )
    }

    // =====================================================================
    // computeTargetSize — resolution cap, aspect preserved, 16-aligned
    // =====================================================================

    @Test
    fun `4K landscape is capped to the ladder's longest side with aspect preserved`() {
        val (w, h) = VideoPresetLadder.computeTargetSize(3840, 2160, 1280)
        assertEquals(1280, w)
        assertEquals(720, h)
        assertEquals(16.0 / 9.0, w.toDouble() / h, 0.02)
    }

    @Test
    fun `portrait video caps its HEIGHT, not its width`() {
        val (w, h) = VideoPresetLadder.computeTargetSize(1080, 1920, 1280)
        assertEquals(1280, h)
        assertEquals(720, w)
    }

    @Test
    fun `a source already under the cap is never upscaled`() {
        val (w, h) = VideoPresetLadder.computeTargetSize(640, 480, 1920)
        assertEquals(640, w)
        assertEquals(480, h)
    }

    @Test
    fun `both dimensions are multiples of 16 for hardware encoders`() {
        for (src in listOf(1920 to 1080, 1440 to 1079, 1234 to 567, 999 to 333)) {
            val (w, h) = VideoPresetLadder.computeTargetSize(src.first, src.second, 1280)
            assertEquals("width $w not 16-aligned", 0, w % 16)
            assertEquals("height $h not 16-aligned", 0, h % 16)
            assertTrue(w >= 16 && h >= 16)
        }
    }

    @Test
    fun `every planned dimension is 16-aligned, on every rung`() {
        // The plan is what reaches `MediaFormat.createVideoFormat`, so the alignment
        // guarantee has to survive the ladder walk and the no-upscale clamp.
        for (src in listOf(3840 to 2160, 1920 to 1080, 1080 to 1920, 1234 to 567, 720 to 720)) {
            for (budget in listOf(cloudBudget, meshBudget, 1024L)) {
                val p = VideoPresetLadder.plan(src.first, src.second, 60_000L, 30, budget)
                assertEquals("${src} @$budget → ${p.label}", 0, p.width % 16)
                assertEquals("${src} @$budget → ${p.label}", 0, p.height % 16)
            }
        }
    }

    @Test
    fun `degenerate input returns zero rather than a bogus format`() {
        assertEquals(0 to 0, VideoPresetLadder.computeTargetSize(0, 0, 1280))
        assertEquals(0 to 0, VideoPresetLadder.computeTargetSize(-1, 100, 1280))
    }
}
