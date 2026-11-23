//
//  PlayoutTrimmer.swift
//  OSHI — decides, per decoded playback frame, whether to drop it in order to
//  shorten the backlog sitting on AVAudioPlayerNode.
//
//  MEASURED, on the 22-minute call of 2026-08-26 (build 110, iPhone 17 Pro Max,
//  cellular, TURN relay, Bluetooth HFP):
//
//      playerQueueMs = 550–800 ms, for the whole call
//      realPackets   = 50/s   concealed=0   silence=0   plcInjected=0
//      iaP95Ms       = 81     iaMeanMs = 20     iaMaxMs = 282
//      depthPackets  = 0      ← the jitter buffer itself holds NOTHING
//
//  A perfectly healthy stream — no loss, no concealment, no starvation — and
//  more than half a second of latency in each direction anyway.
//
//  WHERE IT COMES FROM
//  `scheduleBuffer(at: nil)` appends. The queue only shortens by playing in real
//  time, so a burst adds to the backlog permanently: the relay delivers in
//  clumps (iaMax 282 ms against a 20 ms cadence), the clump is scheduled at
//  once, and from then on in == out. The queue oscillates — 800 → 234 → 117 →
//  714 — climbing on every burst and drifting down at ~7 ms/s, which is far too
//  slow to matter. The ONLY thing that trims it is the full-queue drop at
//  `targetDepthMs + 250`, which is why it rides just under that cap.
//
//  So the fix cannot be a one-shot drain at call start: it must run for the
//  whole call, because the backlog is rebuilt for the whole call.
//
//  WHY THAT COSTS MORE THAN LATENCY
//  600 ms one way is the largest single term in the echo the owner reported
//  ("ma voix en écho, environ 1 seconde de décalage"), and it is the only term
//  this device controls — the others are the peer's own queue, the TURN relay,
//  the cellular leg and Bluetooth HFP. It is also why people talk over each
//  other: at 600 ms you cannot hear that the other person has started.
//
//  THE RULE: NEVER REMOVE SOUND, ONLY REMOVE SILENCE
//  Dropping 20 ms of speech is a click. Dropping 20 ms from the middle of a
//  pause is nothing at all. Conversation is 40–60 % pauses, so the opportunity
//  is plentiful and there is never a reason to touch a syllable.
//
//  The silence ceiling is CALIBRATED, not guessed. On that call's playback
//  frames: p1 = 138, p5 = 238, p10 = 302, p20 = 422, median = 1057, p90 = 3708.
//  400 sits at the top of the quiet tail — ~18 % of frames, about 8 dB below
//  median speech — which is roughly 9 candidate frames per second. Clearing a
//  600 ms backlog therefore takes a few seconds of ordinary conversation.
//
//  THE GUARD THAT GETS FORGOTTEN is `prevWasQuiet`. Removing silence from the
//  middle of a pause is inaudible; removing it at the pause's LEADING EDGE eats
//  into the attack of the next word, and that is exactly where a trimmer is
//  heard. Requiring the previous frame to have been quiet too means we are
//  provably inside a pause, never at its boundary.
//
//  AND NEVER UNDERSHOOT. Starving the player triggers concealment, and a hole is
//  far more audible than latency. Trimming stops dead at `target + stopSlack`,
//  and a frame is kept whenever removing it would cross that floor.
//
//  NOT DONE HERE, deliberately: WSOLA/PSOLA time compression — accelerating
//  playout by removing pitch periods with overlap-add, which is what WebRTC's
//  NetEQ does and which works even during continuous speech. That is real DSP,
//  and in a codebase where VoiceProcessingIO silenced every call three times
//  (b56–b60, 2026-07-26, 2026-08-14) a time-stretcher does not go in blind.
//  Silence-gated trimming gets most of the benefit for a fraction of the risk.
//  If DIAG_TRIM shows pauses cannot keep up with the bursts, WSOLA is the NEXT
//  step — not the first one.
//

import Foundation

struct PlayoutTrimmer {

    enum Decision {
        /// Schedule this frame normally.
        case keep
        /// Drop it: the backlog is too deep and this frame is silence.
        case trim
    }

    // MARK: - Tunables

    /// Kill switch. Anything that touches the audio path in this project needs
    /// one that works without a rebuild — see the VPIO history above.
    static var enabled: Bool = !UserDefaults.standard.bool(forKey: "OSHIDisablePlayoutTrim")

    /// A frame at or below this RMS (Int16-equivalent, 0…32767, measured AFTER
    /// the playback pre-gain) counts as silence. Calibrated from the 2026-08-26
    /// call: ~18 % of frames, ~8 dB under median speech.
    static let silenceRmsCeiling: Double = 400

    /// Start trimming once the queue is this far above the jitter buffer's own
    /// target. Above this, the depth is not protecting against anything the
    /// buffer asked for.
    static let startSlackMs: Double = 150

    /// Stop here — two frames above target. The gap between start and stop is
    /// the hysteresis: without it the trimmer would sit on the boundary and
    /// trim continuously.
    static let stopSlackMs: Double = 40

    // MARK: - State

    private var trimming = false
    private var prevWasQuiet = false
    private(set) var framesTrimmed: UInt64 = 0
    private(set) var msTrimmed: Double = 0

    init() {}

    /// Forget everything. Called wherever the player queue is dropped — the
    /// depth this was reasoning about no longer exists.
    mutating func reset() {
        trimming = false
        prevWasQuiet = false
        // `framesTrimmed` / `msTrimmed` are per-call totals for DIAG_TRIM and
        // deliberately survive: they answer "did this ever engage, and by how
        // much", which is the whole point of shipping it behind a measurement.
    }

    /// - Parameters:
    ///   - queuedMs: audio currently pending on the player node.
    ///   - targetMs: the jitter buffer's own target depth — the amount it says
    ///     it needs. Never trim below it.
    ///   - frameRms: this frame's level, same units as `silenceRmsCeiling`.
    ///   - frameMs: this frame's duration.
    mutating func decide(queuedMs: Double,
                         targetMs: Double,
                         frameRms: Double,
                         frameMs: Double) -> Decision {
        let quiet = frameRms <= PlayoutTrimmer.silenceRmsCeiling
        // Runs on EVERY exit path, including the early ones. The edge guard is
        // only correct if this is never skipped.
        defer { prevWasQuiet = quiet }

        guard PlayoutTrimmer.enabled else { return .keep }
        guard frameMs > 0 else { return .keep }

        // Hysteresis: arm high, disarm low.
        if trimming {
            if queuedMs <= targetMs + PlayoutTrimmer.stopSlackMs { trimming = false }
        } else if queuedMs >= targetMs + PlayoutTrimmer.startSlackMs {
            trimming = true
        }
        guard trimming else { return .keep }

        // Silence only, and only from INSIDE a pause — never at its edge.
        guard quiet, prevWasQuiet else { return .keep }

        // Removing this frame must not cross the floor. Starving the player is
        // worse than the latency we are trying to remove.
        guard queuedMs - frameMs >= targetMs + PlayoutTrimmer.stopSlackMs else { return .keep }

        framesTrimmed &+= 1
        msTrimmed += frameMs
        return .trim
    }
}
