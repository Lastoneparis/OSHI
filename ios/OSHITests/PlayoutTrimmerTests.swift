//
//  PlayoutTrimmerTests.swift
//  OSHITests
//
//  These tests exist to assert what must be IMPOSSIBLE, not what is expected to
//  work. A trimmer that removes 20 ms of a vowel is a click, and a click on a
//  call is worse than the latency it bought. So the load-bearing tests here are
//  the negative ones: never a voiced frame, never at the edge of a pause, never
//  below the jitter buffer's floor.
//
//  Every value is injected. No wall clock, no audio hardware.
//

import XCTest
@testable import OSHI

final class PlayoutTrimmerTests: XCTestCase {

    /// Above `silenceRmsCeiling` (400). Median speech on the measured call was
    /// 1057, so this is an ordinary voiced frame.
    private let speech: Double = 1057
    /// Inside the quiet tail — p10 of the measured call was 302.
    private let quiet: Double = 300
    private let frameMs: Double = 20
    private let target: Double = 200

    /// Deep enough to be trimming, by a wide margin.
    private var deepQueue: Double { target + 600 }

    /// Drive N quiet frames through and count how many were trimmed.
    private func runQuietFrames(_ n: Int,
                                trimmer: inout PlayoutTrimmer,
                                queuedMs: Double) -> Int {
        var trimmed = 0
        for _ in 0..<n {
            if case .trim = trimmer.decide(queuedMs: queuedMs, targetMs: target,
                                           frameRms: quiet, frameMs: frameMs) {
                trimmed += 1
            }
        }
        return trimmed
    }

    // MARK: - The things that must never happen

    /// THE ONE THAT MATTERS. Speech is never removed, no matter how deep the
    /// backlog gets. If this fails the trimmer has become a click generator.
    func testNeverTrimsAVoicedFrameHoweverDeepTheQueue() {
        var t = PlayoutTrimmer()
        for depth in [target + 200, target + 600, target + 5000, 10_000] {
            for _ in 0..<50 {
                let d = t.decide(queuedMs: depth, targetMs: target,
                                 frameRms: speech, frameMs: frameMs)
                guard case .keep = d else {
                    return XCTFail("trimmed a voiced frame at queue depth \(depth)")
                }
            }
        }
        XCTAssertEqual(t.framesTrimmed, 0)
    }

    /// The guard that gets forgotten. The FIRST quiet frame after speech is the
    /// leading edge of a pause; removing it eats into the attack of the next
    /// word, which is precisely where a trimmer is heard. Only from the second
    /// quiet frame on are we provably inside the pause.
    func testNeverTrimsTheFirstQuietFrameAfterSpeech() {
        var t = PlayoutTrimmer()
        // Arm it, then land on speech so the edge state is real.
        _ = t.decide(queuedMs: deepQueue, targetMs: target, frameRms: speech, frameMs: frameMs)

        let first = t.decide(queuedMs: deepQueue, targetMs: target, frameRms: quiet, frameMs: frameMs)
        guard case .keep = first else { return XCTFail("trimmed the leading edge of a pause") }

        let second = t.decide(queuedMs: deepQueue, targetMs: target, frameRms: quiet, frameMs: frameMs)
        guard case .trim = second else { return XCTFail("should trim once inside the pause") }
    }

    /// Starving the player triggers concealment, and a hole is far more audible
    /// than the latency being removed. The trimmer must stop before the floor
    /// even when it is sitting in an endless silence.
    func testNeverTrimsBelowTheJitterBufferFloor() {
        var t = PlayoutTrimmer()
        var queued = target + 400
        // 1000 quiet frames — vastly more opportunity than there is debt.
        for _ in 0..<1000 {
            let d = t.decide(queuedMs: queued, targetMs: target,
                             frameRms: quiet, frameMs: frameMs)
            if case .trim = d {
                queued -= frameMs
                XCTAssertGreaterThanOrEqual(queued, target,
                                            "trimmed past the floor the jitter buffer asked for")
            }
        }
        XCTAssertGreaterThanOrEqual(queued, target + PlayoutTrimmer.stopSlackMs - frameMs)
    }

    // MARK: - It has to actually converge

    /// The measured case: 800 ms queued against a 200 ms target. It must come
    /// down, and it must stop.
    func testConvergesFromTheMeasuredBacklogAndThenStops() {
        var t = PlayoutTrimmer()
        var queued: Double = 800
        // Prime the edge guard so the first eligible frame is inside a pause.
        _ = t.decide(queuedMs: queued, targetMs: target, frameRms: quiet, frameMs: frameMs)
        for _ in 0..<500 {
            if case .trim = t.decide(queuedMs: queued, targetMs: target,
                                     frameRms: quiet, frameMs: frameMs) {
                queued -= frameMs
            }
        }
        XCTAssertLessThanOrEqual(queued, target + PlayoutTrimmer.startSlackMs,
                                 "did not converge out of the trimming band")
        XCTAssertGreaterThanOrEqual(queued, target, "overshot below the floor")
        // ~600 ms of debt at 20 ms a frame.
        XCTAssertGreaterThan(t.framesTrimmed, 20)
        XCTAssertLessThan(t.framesTrimmed, 40)
    }

    /// Hysteresis. A queue resting just above the target must be left alone, or
    /// the trimmer sits on the boundary and trims for the whole call.
    func testDoesNothingWhenTheQueueIsAlreadyHealthy() {
        var t = PlayoutTrimmer()
        let healthy = target + PlayoutTrimmer.stopSlackMs + 20   // inside the band, below start
        XCTAssertEqual(runQuietFrames(200, trimmer: &t, queuedMs: healthy), 0)
        XCTAssertEqual(t.framesTrimmed, 0)
    }

    /// Once disarmed it must stay disarmed until the queue climbs back past the
    /// START threshold — not merely past the stop threshold.
    func testStaysDisarmedUntilTheStartThresholdIsCrossedAgain() {
        var t = PlayoutTrimmer()
        // Arm and converge.
        var queued: Double = 800
        _ = t.decide(queuedMs: queued, targetMs: target, frameRms: quiet, frameMs: frameMs)
        for _ in 0..<500 {
            if case .trim = t.decide(queuedMs: queued, targetMs: target,
                                     frameRms: quiet, frameMs: frameMs) { queued -= frameMs }
        }
        let after = t.framesTrimmed
        // Now sit just under the start threshold. Nothing more may be removed.
        let justUnder = target + PlayoutTrimmer.startSlackMs - 1
        _ = runQuietFrames(200, trimmer: &t, queuedMs: justUnder)
        XCTAssertEqual(t.framesTrimmed, after, "re-armed below the start threshold")
    }

    // MARK: - Bookkeeping

    func testKillSwitchIsHonoured() {
        let saved = PlayoutTrimmer.enabled
        defer { PlayoutTrimmer.enabled = saved }
        PlayoutTrimmer.enabled = false
        var t = PlayoutTrimmer()
        XCTAssertEqual(runQuietFrames(200, trimmer: &t, queuedMs: deepQueue), 0)
        XCTAssertEqual(t.framesTrimmed, 0)
    }

    /// `reset()` runs where the player queue is dropped. It must forget the
    /// armed state and the edge state, but keep the per-call totals — those are
    /// the evidence that the feature ever engaged.
    func testResetForgetsStateButKeepsTheEvidence() {
        var t = PlayoutTrimmer()
        var queued: Double = 800
        _ = t.decide(queuedMs: queued, targetMs: target, frameRms: quiet, frameMs: frameMs)
        for _ in 0..<10 {
            if case .trim = t.decide(queuedMs: queued, targetMs: target,
                                     frameRms: quiet, frameMs: frameMs) { queued -= frameMs }
        }
        let trimmedSoFar = t.framesTrimmed
        XCTAssertGreaterThan(trimmedSoFar, 0)

        t.reset()
        XCTAssertEqual(t.framesTrimmed, trimmedSoFar, "totals must survive reset")
        // Edge state is cleared, so the very next quiet frame is a leading edge
        // again and must be kept.
        let d = t.decide(queuedMs: 800, targetMs: target, frameRms: quiet, frameMs: frameMs)
        guard case .keep = d else { return XCTFail("reset did not clear the pause-edge state") }
    }

    /// A zero-length buffer carries no time, so removing it buys nothing and
    /// would only corrupt the accounting.
    func testZeroLengthFrameIsNeverTrimmed() {
        var t = PlayoutTrimmer()
        _ = t.decide(queuedMs: deepQueue, targetMs: target, frameRms: quiet, frameMs: 20)
        let d = t.decide(queuedMs: deepQueue, targetMs: target, frameRms: quiet, frameMs: 0)
        guard case .keep = d else { return XCTFail("trimmed a zero-length frame") }
    }

    // MARK: - The control

    /// A regression test proves nothing until the fixture is shown to
    /// discriminate. `testNeverTrimsAVoicedFrameHoweverDeepTheQueue` would pass
    /// against a trimmer that never trims ANYTHING — so this pins the other
    /// side: on the SAME depth and the SAME frame duration, a quiet frame
    /// inside a pause IS removed. The two together say the gate is keyed on
    /// level, not on nothing at all.
    func testTheFixtureDiscriminatesOnLevelAlone() {
        var voiced = PlayoutTrimmer()
        var silent = PlayoutTrimmer()

        // Identical inputs except the level.
        _ = voiced.decide(queuedMs: deepQueue, targetMs: target, frameRms: speech, frameMs: frameMs)
        _ = voiced.decide(queuedMs: deepQueue, targetMs: target, frameRms: speech, frameMs: frameMs)
        _ = silent.decide(queuedMs: deepQueue, targetMs: target, frameRms: quiet, frameMs: frameMs)
        _ = silent.decide(queuedMs: deepQueue, targetMs: target, frameRms: quiet, frameMs: frameMs)

        XCTAssertEqual(voiced.framesTrimmed, 0, "control broken: speech path is not exercised")
        XCTAssertGreaterThan(silent.framesTrimmed, 0, "control broken: silence path never trims")

        // And the ceiling is a real boundary, not a value nothing ever reaches.
        XCTAssertLessThan(quiet, PlayoutTrimmer.silenceRmsCeiling)
        XCTAssertGreaterThan(speech, PlayoutTrimmer.silenceRmsCeiling)
    }
}
