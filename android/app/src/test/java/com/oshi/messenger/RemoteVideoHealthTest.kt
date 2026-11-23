package com.oshi.messenger

import com.oshi.messenger.service.RemoteVideoHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Their video is frozen" vs "their camera is off" vs "we are still connecting".
 *
 * Reference: iOS `remoteStallSeconds` / `remoteWeakFPS` / `tickRemoteHealth` /
 * `isRemoteVideoWeak`, `OSHI/VideoCallManager.swift:168-176` and `:1367-1395`.
 *
 * What was broken: Android had no probe at all. `isReceivingVideo` is a one-way
 * latch — it flips true on the first decoded frame and only clears at teardown
 * (`VideoCallManager.kt:1601-1602`) — so a peer whose stream died still read as
 * "receiving" and the screen held the last decoded picture indefinitely with nothing
 * said about it. It also blocked the third condition of the controls-auto-hide guard.
 */
class RemoteVideoHealthTest {

    private val now = 1_700_000_000_000L

    // ── stalled ───────────────────────────────────────────────────────────────

    /**
     * Nothing has EVER arrived. That is "connecting", not "stalled": the waiting
     * placeholder already says so, and a warning badge on top of it would fire on
     * every single call. iOS makes the same distinction with an optional
     * `lastRemoteFrameAt` (`OSHI/VideoCallManager.swift:1372-1380`).
     */
    @Test
    fun `nothing ever received is connecting, not stalled`() {
        assertFalse(RemoteVideoHealth.isStalled(lastDecodedAtMs = 0L, nowMs = now))
        assertFalse(RemoteVideoHealth.isStalled(lastDecodedAtMs = -1L, nowMs = now))
    }

    @Test
    fun `a frame that just arrived is not a stall`() {
        assertFalse(RemoteVideoHealth.isStalled(now - 200L, now))
    }

    @Test
    fun `three seconds of silence is a stall`() {
        assertTrue(RemoteVideoHealth.isStalled(now - RemoteVideoHealth.STALL_MS, now))
        assertTrue(RemoteVideoHealth.isStalled(now - 10_000L, now))
    }

    /**
     * The threshold is deliberately ABOVE the 2 s "frozen keyframe" interval a peer
     * uses when it has frozen its own video to protect audio
     * (`OSHI/VideoCallManager.swift:165`). That peer is still sending a real, if
     * terrible, stream and must read as **weak**, not as **stalled** — otherwise the
     * badge accuses a working link.
     */
    @Test
    fun `a peer trickling one keyframe every two seconds is not stalled`() {
        assertFalse(RemoteVideoHealth.isStalled(now - 2_000L, now))
        assertFalse(RemoteVideoHealth.isStalled(now - 2_999L, now))
        assertTrue("3000 ms is the edge and it is inclusive",
            RemoteVideoHealth.isStalled(now - 3_000L, now))
    }

    @Test
    fun `the stall threshold matches the iOS constant`() {
        assertEquals(3_000L, RemoteVideoHealth.STALL_MS)
    }

    // ── weak ──────────────────────────────────────────────────────────────────

    @Test
    fun `frames arriving too slowly to be a moving picture read as weak`() {
        assertTrue(RemoteVideoHealth.isWeak(isReceiving = true, isStalled = false, fps = 1))
        assertTrue(RemoteVideoHealth.isWeak(isReceiving = true, isStalled = false, fps = 3))
    }

    @Test
    fun `a healthy frame rate is not weak`() {
        assertFalse(RemoteVideoHealth.isWeak(isReceiving = true, isStalled = false, fps = 4))
        assertFalse(RemoteVideoHealth.isWeak(isReceiving = true, isStalled = false, fps = 30))
    }

    /**
     * Zero fps is either "stalled" or "connecting"; it is never "weak". Two badges at
     * once, or a "very weak connection" badge on a call that has not started yet,
     * would both be wrong.
     */
    @Test
    fun `zero fps is never weak`() {
        assertFalse(RemoteVideoHealth.isWeak(isReceiving = true, isStalled = false, fps = 0))
        assertFalse(RemoteVideoHealth.isWeak(isReceiving = true, isStalled = true, fps = 0))
    }

    @Test
    fun `stalled and weak are mutually exclusive`() {
        assertFalse(RemoteVideoHealth.isWeak(isReceiving = true, isStalled = true, fps = 1))
    }

    /** Never accuse a stream that has not begun. */
    @Test
    fun `not receiving is never weak`() {
        assertFalse(RemoteVideoHealth.isWeak(isReceiving = false, isStalled = false, fps = 1))
    }

    @Test
    fun `the weak threshold matches the iOS constant`() {
        assertEquals(4, RemoteVideoHealth.WEAK_FPS)
    }

    /**
     * The call iOS records in its own comment: 146 decodable frames in 255 seconds —
     * 0.57 fps — presented as a still image with no explanation, reported afterwards
     * as "I never saw his video" (`OSHI/VideoCallManager.swift:103-112`). Rounded to
     * the nearest integer that is 1 fps, and it must raise the badge.
     */
    @Test
    fun `the call that motivated this raises the badge`() {
        assertTrue(RemoteVideoHealth.isWeak(isReceiving = true, isStalled = false, fps = 1))
    }
}
