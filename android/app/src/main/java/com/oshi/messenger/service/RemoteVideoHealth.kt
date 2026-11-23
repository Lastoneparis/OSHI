package com.oshi.messenger.service

/**
 * Is the peer's picture actually moving?
 *
 * `isReceivingVideo` **latches**: it flips true on the first decoded frame and is
 * only cleared at teardown (`VideoCallManager.kt:389`, `:1601-1602`). So a peer
 * whose stream dies — their app crashed, the link collapsed, or the frame rate fell
 * to a fraction of one per second — still reads as "receiving", and the screen holds
 * the last decoded picture forever with nothing said about it. iOS documents exactly
 * this from a real call: 146 decodable frames in 255 seconds, reported afterwards as
 * *"I never saw his video"* (`OSHI/VideoCallManager.swift:103-112`).
 *
 * The reflex on seeing a frozen picture is to hang up and call back, which throws
 * away a working **voice** call — which is why the banner this feeds says the audio
 * is fine.
 *
 * Pure so the thresholds can be tested without a decoder. iOS:
 * `OSHI/VideoCallManager.swift:168-176` and `:1367-1395`.
 */
object RemoteVideoHealth {

    /**
     * No decoded frame for this long ⇒ stalled.
     *
     * Deliberately longer than the 2 s "frozen keyframe" interval a peer uses when it
     * has frozen its own video to protect audio (`OSHI/VideoCallManager.swift:165`):
     * that peer still trickles one keyframe every two seconds, and that is a real, if
     * terrible, stream — it must read as **weak**, not as **stalled**.
     * iOS `remoteStallSeconds = 3.0`, `OSHI/VideoCallManager.swift:173`.
     */
    const val STALL_MS = 3_000L

    /**
     * Below this the picture is not a video any more; say so.
     * iOS `remoteWeakFPS = 4.0`, `OSHI/VideoCallManager.swift:175`.
     */
    const val WEAK_FPS = 4

    /**
     * @param lastDecodedAtMs wall clock of the last decoded frame, or `0` when
     *   **nothing has ever arrived**. That case is "connecting", not "stalled": the
     *   waiting placeholder already says so, and a warning badge on top of it would
     *   fire on every single call. iOS makes the same distinction with an optional
     *   `lastRemoteFrameAt` (`OSHI/VideoCallManager.swift:1372-1380`).
     */
    fun isStalled(lastDecodedAtMs: Long, nowMs: Long): Boolean {
        if (lastDecodedAtMs <= 0L) return false
        return nowMs - lastDecodedAtMs >= STALL_MS
    }

    /**
     * Frames are arriving, but far too slowly to read as a moving picture. Distinct
     * from stalled: something IS coming. iOS `isRemoteVideoWeak`,
     * `OSHI/VideoCallManager.swift:1393-1395`.
     */
    fun isWeak(isReceiving: Boolean, isStalled: Boolean, fps: Int): Boolean =
        isReceiving && !isStalled && fps > 0 && fps < WEAK_FPS
}
