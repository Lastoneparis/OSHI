//
//  CallReconnectStateTests.swift
//  OSHITests
//
//  Non-regression tests for the rule behind the "Reconnecting" badge.
//
//  The reported failure: on a real 1.0.35 call the badge appeared a few seconds
//  in and stayed for the whole call, while audio was perfect at both ends. The
//  cause was structural — two sources armed the state, but every clear path was
//  gated on a flag only ONE of them set — so the tests that matter are the ones
//  that pin the SHAPE of the rule, not any particular call site:
//
//    * a source that arms can always clear (the actual bug),
//    * a source cannot clear a reason it does not hold (the fix must not
//      overshoot and start masking real outages),
//    * the badge is on iff at least one reason is held (no second source of
//      truth to drift out of sync).
//
//  These exist because the original bug was invisible to every existing test and
//  to code review: nothing was wrong on any single line.
//

import XCTest
@testable import OSHI

final class CallReconnectStateTests: XCTestCase {

    // MARK: The reported bug

    /// THE regression test. The probe used to arm the badge through a path with
    /// no working release, so once it fired the call was stuck showing
    /// "Reconnecting" over flawless audio until it ended.
    func testProbeCanClearTheBadgeItArmed() {
        var state = CallReconnectState()

        XCTAssertTrue(state.arm(.probeFailed), "arming from idle should report the transition")
        XCTAssertTrue(state.isReconnecting)

        XCTAssertTrue(state.disarm(.probeFailed), "the probe must be able to release its own reason")
        XCTAssertFalse(state.isReconnecting, "badge stuck on after the only reason was released")
    }

    /// The same property for the other source, so a future refactor cannot fix
    /// one and break the other.
    func testMediaSilenceCanClearTheBadgeItArmed() {
        var state = CallReconnectState()
        state.arm(.mediaSilence)
        XCTAssertTrue(state.disarm(.mediaSilence))
        XCTAssertFalse(state.isReconnecting)
    }

    /// Every source, mechanically — this is what makes a third one safe to add.
    func testEveryReasonCanArmAndClearOnItsOwn() {
        for reason in CallReconnectState.Reason.allCases {
            var state = CallReconnectState()
            state.arm(reason)
            XCTAssertTrue(state.isReconnecting, "\(reason) did not arm")
            state.disarm(reason)
            XCTAssertFalse(state.isReconnecting, "\(reason) armed a badge it cannot clear")
        }
    }

    // MARK: The fix must not overshoot

    /// A recovering probe must NOT clear a badge that a real media outage is
    /// still holding — that would replace a stuck badge with a lying one.
    func testOneSourceRecoveringDoesNotClearAnotherSourcesReason() {
        var state = CallReconnectState()
        state.arm(.mediaSilence)
        state.arm(.probeFailed)

        XCTAssertFalse(state.disarm(.probeFailed),
                       "clearing one of two reasons must not report the badge as off")
        XCTAssertTrue(state.isReconnecting, "media is still silent — the badge must stay up")

        XCTAssertTrue(state.disarm(.mediaSilence))
        XCTAssertFalse(state.isReconnecting)
    }

    /// Order must not matter; neither source is privileged.
    func testClearingInEitherOrderEndsIdle() {
        for order in [[CallReconnectState.Reason.mediaSilence, .probeFailed],
                      [CallReconnectState.Reason.probeFailed, .mediaSilence]] {
            var state = CallReconnectState()
            state.arm(.mediaSilence)
            state.arm(.probeFailed)
            for r in order { state.disarm(r) }
            XCTAssertFalse(state.isReconnecting, "order \(order) left the badge armed")
        }
    }

    // MARK: Idempotence — these run off timers that fire repeatedly

    /// `checkConnectionStatus` ticks every 500 ms and the quality sampler every
    /// 2 s, so both arm repeatedly while a problem lasts. Re-arming must not
    /// require a matching number of releases.
    func testRepeatedArmingStillClearsWithASingleRelease() {
        var state = CallReconnectState()
        for _ in 0..<50 { state.arm(.mediaSilence) }
        XCTAssertTrue(state.disarm(.mediaSilence))
        XCTAssertFalse(state.isReconnecting)
    }

    /// Releasing something never held is a no-op, not an underflow. `audioReceived`
    /// can fire on the very first packet of a call with nothing armed.
    func testDisarmingAReasonNeverArmedIsHarmless() {
        var state = CallReconnectState()
        XCTAssertFalse(state.disarm(.mediaSilence))
        XCTAssertFalse(state.isReconnecting)

        state.arm(.probeFailed)
        XCTAssertFalse(state.disarm(.mediaSilence), "released a reason that was never held")
        XCTAssertTrue(state.isReconnecting, "an unrelated release must not clear the badge")
    }

    /// Only the transition should be reported, or the logs and the badge restore
    /// would fire on every repeat.
    func testArmReportsTheTransitionOnlyOnce() {
        var state = CallReconnectState()
        XCTAssertTrue(state.arm(.mediaSilence))
        XCTAssertFalse(state.arm(.mediaSilence))
        XCTAssertFalse(state.arm(.probeFailed), "badge was already on")
    }

    // MARK: Teardown

    /// Reasons are per-call. One left armed would gate the NEXT call's quality
    /// sampler and hand it a permanent badge — the cross-call half of the bug.
    func testClearAllSettlesEverythingAndReportsTheTransition() {
        var state = CallReconnectState()
        state.arm(.mediaSilence)
        state.arm(.probeFailed)

        XCTAssertTrue(state.clearAll())
        XCTAssertFalse(state.isReconnecting)
        XCTAssertTrue(state.reasons.isEmpty)

        XCTAssertFalse(state.clearAll(), "clearing an already-idle state is not a transition")
    }

    /// A fresh call starts with nobody complaining.
    func testAFreshStateIsIdle() {
        let state = CallReconnectState()
        XCTAssertFalse(state.isReconnecting)
        XCTAssertTrue(state.reasons.isEmpty)
    }

    // MARK: The invariant that makes the badge derivable

    /// `isReconnecting` must be a pure function of the held reasons. The bug was
    /// two representations of one fact drifting apart; this pins them together.
    func testIsReconnectingIsExactlyWhetherAnyReasonIsHeld() {
        var state = CallReconnectState()
        let script: [(CallReconnectState.Reason, Bool)] = [
            (.mediaSilence, true), (.probeFailed, true),
            (.mediaSilence, false), (.probeFailed, true),
            (.probeFailed, false), (.mediaSilence, false),
        ]
        for (reason, arming) in script {
            if arming { state.arm(reason) } else { state.disarm(reason) }
            XCTAssertEqual(state.isReconnecting, !state.reasons.isEmpty,
                           "badge and reasons disagreed after \(arming ? "arm" : "disarm") \(reason)")
        }
        XCTAssertFalse(state.isReconnecting)
    }
}
