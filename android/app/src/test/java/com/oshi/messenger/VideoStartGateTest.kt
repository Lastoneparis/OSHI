package com.oshi.messenger

import com.oshi.messenger.service.VideoStartGate
import com.oshi.messenger.service.VideoStartGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera-start guard, ported from iOS 59972bb
 * (`VideoCallManager.swift:663-673`).
 *
 * The defect being locked down: `_isVideoEnabled` was set to `true` unconditionally
 * after an `openCamera()` that returns normally on a missing CAMERA permission, a
 * missing camera id, a lock timeout and `StateCallback.onError` — and that same flag
 * was the retry guard. A failed start therefore latched "video on, no camera" and
 * then FORBADE the retry; the user's only remedy was turning video off and back on.
 */
class VideoStartGateTest {

    /** Cold start: nothing running, nothing in flight. */
    @Test
    fun `cold start proceeds`() {
        val d = VideoStartGate.decide(isStartingVideo = false, isVideoEnabled = false, cameraLive = false)
        assertEquals(Decision.START, d)
        assertTrue(VideoStartGate.proceeds(d))
    }

    /**
     * THE regression. A start that failed leaves `isVideoEnabled = true` (it gates the
     * RX path, so it is set even when our own camera never came up) with no frames
     * captured. A retry must be allowed — and must RESTART, not merely proceed as if
     * nothing were there.
     */
    @Test
    fun `a failed start leaves the camera flag false and permits a retry`() {
        // cameraLive is set from the capture callback, so a failed start leaves it false.
        val d = VideoStartGate.decide(isStartingVideo = false, isVideoEnabled = true, cameraLive = false)
        assertEquals(Decision.RESTART, d)
        assertTrue("a failed start must not lock out recovery", VideoStartGate.proceeds(d))
    }

    /** A healthy session is left alone — the guard still has to do its real job. */
    @Test
    fun `a running camera is not restarted`() {
        val d = VideoStartGate.decide(isStartingVideo = false, isVideoEnabled = true, cameraLive = true)
        assertEquals(Decision.IGNORE_RUNNING, d)
        assertFalse(VideoStartGate.proceeds(d))
    }

    /**
     * The other half of the old flag's lie: it stayed false through the whole
     * asynchronous bring-up, so it never protected against the double call it claimed
     * to forbid. `isStartingVideo` does, and it wins over every other state.
     */
    @Test
    fun `a start already in flight wins over every other state`() {
        for (enabled in listOf(false, true)) {
            for (live in listOf(false, true)) {
                assertEquals(
                    "isStartingVideo must dominate (enabled=$enabled live=$live)",
                    Decision.IGNORE_STARTING,
                    VideoStartGate.decide(isStartingVideo = true, isVideoEnabled = enabled, cameraLive = live)
                )
            }
        }
    }

    /**
     * `cameraLive` alone must never suppress a start: if the flag is off, the session
     * is down and a stale "live" reading must not talk us out of starting.
     */
    @Test
    fun `camera live without the video flag still starts`() {
        assertEquals(
            Decision.START,
            VideoStartGate.decide(isStartingVideo = false, isVideoEnabled = false, cameraLive = true)
        )
    }
}
