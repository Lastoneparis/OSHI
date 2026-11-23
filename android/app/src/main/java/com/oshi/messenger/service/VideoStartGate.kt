package com.oshi.messenger.service

/**
 * What `VideoCallManager.startVideo()` should do when it is called.
 *
 * Port of iOS `VideoCallManager.swift:663-673` (commit 59972bb, "a failed camera
 * start stayed failed, and the guard forbade retrying").
 *
 * The bug this replaces: the guard was `if (_isVideoEnabled.value) return`, and
 * `_isVideoEnabled` was set to `true` unconditionally after an `openCamera()` that
 * returns normally on a missing CAMERA permission, a missing camera id, a lock
 * timeout and `StateCallback.onError`. A failed start therefore left the object in
 * "video on, no camera" — and that same flag then FORBADE the retry. The only way
 * out was to turn video off and back on, because stopping is what clears the flag.
 *
 * The flag also lies in the other direction: it stays false throughout the whole
 * asynchronous camera bring-up, so it never protected against the double call it
 * claimed to forbid. `isStartingVideo` does that job.
 *
 * `cameraLive` must come from evidence that frames are flowing — not from a flag,
 * and not from `openCamera()` having returned.
 */
object VideoStartGate {

    enum class Decision {
        /** A start is already in flight; this call is a duplicate. */
        IGNORE_STARTING,

        /** Video is on and the camera is producing frames. Nothing to do. */
        IGNORE_RUNNING,

        /** Normal cold start. */
        START,

        /** Flag says video is on but no frames are coming: relaunch instead of refusing. */
        RESTART
    }

    fun decide(isStartingVideo: Boolean, isVideoEnabled: Boolean, cameraLive: Boolean): Decision = when {
        isStartingVideo -> Decision.IGNORE_STARTING
        isVideoEnabled && cameraLive -> Decision.IGNORE_RUNNING
        isVideoEnabled -> Decision.RESTART
        else -> Decision.START
    }

    /** Both work-doing outcomes; the two IGNOREs return early. */
    fun proceeds(decision: Decision): Boolean =
        decision == Decision.START || decision == Decision.RESTART
}
