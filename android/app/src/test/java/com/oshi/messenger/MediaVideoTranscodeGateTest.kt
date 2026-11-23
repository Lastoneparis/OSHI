package com.oshi.messenger

import com.oshi.messenger.service.media.VideoPresetLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The M-9 gate: WHEN the preset ladder runs at all.
 *
 * Android used to transcode only above the 200 MB delivery ceiling, so a 45-second 4K
 * clip (~150 MB) went out at full size. iOS calls `compressVideo` for every video
 * (`MediaManager.swift:440`) and its ladder tops out at 1080p (`:505-509`), so an
 * iPhone never sends above 1080p whatever the camera shot.
 */
class MediaVideoTranscodeGateTest {

    private val ceiling = 200L * 1024 * 1024      // MediaCompressionManager.IPFS_MAX_SIZE

    @Test
    fun `a 4K clip well under the ceiling is transcoded — the M-9 regression`() {
        // 45 s of 4K ≈ 150 MB: the exact clip the audit describes.
        assertTrue(
            VideoPresetLadder.needsTranscode(3840, 2160, 150L * 1024 * 1024, ceiling)
        )
        // …and even a SHORT 4K clip, which the byte rules alone would let through.
        assertTrue(
            VideoPresetLadder.needsTranscode(3840, 2160, 4L * 1024 * 1024, ceiling)
        )
    }

    @Test
    fun `__CHAT_VIDEO_BITRATE_2026_10_09__ a small 1080p clip is NO LONGER left alone`() {
        // This assertion used to read `assertFalse`, and the gate was 1920 — i.e.
        // "anything 1080p or under is fine". Nine seconds of camera 1080p is the
        // 17 MB the owner reported: in that band, under the soft budget, and waved
        // through by every other rule here. The gate is now the resolution the
        // encode actually targets.
        assertTrue(VideoPresetLadder.needsTranscode(1920, 1080, 3L * 1024 * 1024, ceiling))
        assertTrue(VideoPresetLadder.needsTranscode(1080, 1920, 3L * 1024 * 1024, ceiling))
        assertEquals(1280, VideoPresetLadder.CHAT_LONG_SIDE)
    }

    @Test
    fun `a clip already at the chat target and in its bitrate band is left alone`() {
        // Re-encoding costs a generation of quality for nothing.
        assertFalse(VideoPresetLadder.needsTranscode(1280, 720, 3L * 1024 * 1024, ceiling))
        assertFalse(VideoPresetLadder.needsTranscode(720, 1280, 3L * 1024 * 1024, ceiling))
    }

    @Test
    fun `a clip in the right resolution band but far above the chat bitrate transcodes`() {
        // 720p, nine seconds, 11 MB ≈ 10 Mbps: small, short, in-band on resolution,
        // and three times more bits than a bubble needs. Every rule except the
        // bitrate one would wave this through.
        assertTrue(
            VideoPresetLadder.needsTranscode(
                width = 1280, height = 720, sizeBytes = 11L * 1024 * 1024,
                hardCeilingBytes = ceiling, durationMs = 9_000L,
            )
        )
        // …while the same clip at the target rate is not touched.
        val target = VideoPresetLadder.plan(1280, 720, 9_000L).estimatedBytes
        assertFalse(
            VideoPresetLadder.needsTranscode(
                width = 1280, height = 720, sizeBytes = target,
                hardCeilingBytes = ceiling, durationMs = 9_000L,
            )
        )
        // A duration of 0 means "could not tell", which must not fabricate a trigger.
        assertFalse(
            VideoPresetLadder.needsTranscode(
                width = 1280, height = 720, sizeBytes = 11L * 1024 * 1024,
                hardCeilingBytes = ceiling, durationMs = 0L,
            )
        )
    }

    @Test
    fun `the cap is on the LONGEST side, so portrait is judged the same as landscape`() {
        assertTrue(VideoPresetLadder.needsTranscode(1080, 2400, 1024, ceiling))
        assertTrue(VideoPresetLadder.needsTranscode(2400, 1080, 1024, ceiling))
        assertFalse(VideoPresetLadder.needsTranscode(1280, 720, 1024, ceiling))
    }

    @Test
    fun `a low-resolution but fat clip still goes through the encoder`() {
        // A long 720p clip is heavy on bytes, not on pixels.
        assertFalse(VideoPresetLadder.needsTranscode(1280, 720, VideoPresetLadder.SOFT_BUDGET_BYTES, ceiling))
        assertTrue(VideoPresetLadder.needsTranscode(1280, 720, VideoPresetLadder.SOFT_BUDGET_BYTES + 1, ceiling))
        assertEquals(16L * 1024 * 1024, VideoPresetLadder.SOFT_BUDGET_BYTES)
    }

    @Test
    fun `the old behaviour is preserved above the hard ceiling, probe or no probe`() {
        assertTrue(VideoPresetLadder.needsTranscode(0, 0, ceiling + 1, ceiling))
    }

    @Test
    fun `an unreadable probe does not fabricate a resolution trigger`() {
        // width/height 0 means "we could not tell", not "it is huge" and not "it is fine":
        // the size rules still apply, the resolution rule does not.
        assertFalse(VideoPresetLadder.needsTranscode(0, 0, 1024, ceiling))
        assertTrue(VideoPresetLadder.needsTranscode(0, 0, VideoPresetLadder.SOFT_BUDGET_BYTES + 1, ceiling))
    }

    @Test
    fun `whatever the plan picks, the result is capped at 720p class`() {
        // The gate only decides WHETHER; this is the guarantee it buys.
        for (longSide in VideoPresetLadder.LONG_SIDE_LADDER) {
            assertTrue(longSide <= VideoPresetLadder.CHAT_LONG_SIDE)
        }
        val (w, h) = VideoPresetLadder.computeTargetSize(3840, 2160, VideoPresetLadder.CHAT_LONG_SIDE)
        assertEquals(1280, w)
        assertEquals(720, h)
        // …and the 4K clip the gate catches really does come out at that size.
        val plan = VideoPresetLadder.plan(3840, 2160, 9_000L, 30, ceiling)
        assertEquals(1280, maxOf(plan.width, plan.height))
    }
}
