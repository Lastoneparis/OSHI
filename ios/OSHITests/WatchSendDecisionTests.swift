//
//  WatchSendDecisionTests.swift
//  OSHITests
//
//  __WATCH_REPLY_QUEUE_2026_08_24__
//
//  These cover the two judgements that decide whether a reply typed on the
//  wrist reaches the phone. Neither has any UI: a reply lost because the
//  session had not finished activating looked exactly like a reply that was
//  never typed, and a reply delivered twice only shows up on the RECIPIENT's
//  screen. So the decisions are pure functions and they are pinned here.
//

import XCTest
import WatchConnectivity
@testable import OSHI

final class WatchSendDecisionTests: XCTestCase {

    // MARK: - transport

    /// The whole bug in one assertion. Before the fix every send path did
    /// `guard session.isReachable else { fail }`, so a cold launch — which is
    /// precisely what the notification's Reply button causes, because
    /// REPLY_ACTION carries `.foreground` — threw the text away while the
    /// session was still coming up.
    func testNotActivatedBuffersRatherThanFailing() {
        XCTAssertEqual(
            WatchSendDecision.transport(activationState: .notActivated, isReachable: false),
            .bufferUntilActivated
        )
        // Even claiming reachability, a session that has not activated cannot
        // be handed anything: `transferUserInfo` raises on it.
        XCTAssertEqual(
            WatchSendDecision.transport(activationState: .notActivated, isReachable: true),
            .bufferUntilActivated
        )
    }

    func testInactiveBuffers() {
        XCTAssertEqual(
            WatchSendDecision.transport(activationState: .inactive, isReachable: true),
            .bufferUntilActivated
        )
    }

    func testActivatedAndReachableGoesLive() {
        XCTAssertEqual(
            WatchSendDecision.transport(activationState: .activated, isReachable: true),
            .live
        )
    }

    /// Unreachable is NOT a refusal. It only means no live round-trip is
    /// available; the queued transfer survives the app exiting and the phone
    /// being out of range.
    func testActivatedButUnreachableQueuesInsteadOfRefusing() {
        XCTAssertEqual(
            WatchSendDecision.transport(activationState: .activated, isReachable: false),
            .queued
        )
    }

    /// No combination of states may drop a payload on the floor.
    func testNoStateEverRefusesOutright() {
        let states: [WCSessionActivationState] = [.notActivated, .inactive, .activated]
        for state in states {
            for reachable in [true, false] {
                let t = WatchSendDecision.transport(activationState: state, isReachable: reachable)
                XCTAssertTrue([.live, .queued, .bufferUntilActivated].contains(t),
                              "state \(state.rawValue) reachable=\(reachable) produced \(t)")
            }
        }
    }

    // MARK: - recovery

    /// The two codes that mean "it went over, only the answer was lost".
    /// Re-sending these would put the user's message on the recipient's screen
    /// twice — the one failure mode that is worse than an uncertain send.
    func testRepliesThatMayHaveArrivedAreNotResent() {
        XCTAssertEqual(WatchSendDecision.recovery(from: .messageReplyTimedOut), .giveUpMayHaveArrived)
        XCTAssertEqual(WatchSendDecision.recovery(from: .messageReplyFailed), .giveUpMayHaveArrived)
    }

    func testUndeliveredCodesAreQueued() {
        for code: WCError.Code in [.notReachable, .deliveryFailed, .sessionInactive,
                                   .sessionNotActivated, .transferTimedOut, .genericError] {
            XCTAssertEqual(WatchSendDecision.recovery(from: code), .queue,
                           "\(code.rawValue) should be retried over the queue")
        }
    }

    func testHopelessCodesAreNotRetried() {
        for code: WCError.Code in [.deviceNotPaired, .watchAppNotInstalled,
                                   .companionAppNotInstalled, .watchOnlyApp,
                                   .sessionNotSupported, .sessionMissingDelegate,
                                   .invalidParameter, .payloadTooLarge,
                                   .payloadUnsupportedTypes, .insufficientSpace,
                                   .fileAccessDenied] {
            XCTAssertEqual(WatchSendDecision.recovery(from: code), .giveUpPermanent,
                           "\(code.rawValue) cannot be fixed by any transport")
        }
    }

    /// A watchOS that invents a new code must fall on the side that loses no
    /// messages. Documented as a deliberate choice in `recovery`, pinned here
    /// so nobody "tidies" the default into a refusal.
    func testUnknownCodeDefaultsToQueueing() {
        let invented = WCError.Code(rawValue: 7099)!
        XCTAssertEqual(WatchSendDecision.recovery(from: invented), .queue)
    }

    /// A new check proves nothing until it has been watched to fail.
    ///
    /// This states the PRE-FIX behaviour — an unreachable session refuses the
    /// send — and marks it as a known failure. XCTest reports an *unexpected
    /// pass* if it ever stops failing, so the day someone reinstates the
    /// `guard session.isReachable else { completion(false) }` that started all
    /// this, the suite says so instead of going quietly green.
    ///
    /// Written as an inverted assertion rather than by sabotaging the source:
    /// it is verified on every run and leaves the tree intact.
    func testTheOldRefusalWouldStillBeCaught() {
        XCTExpectFailure("pre-fix behaviour: an unreachable session refused the send outright") {
            XCTAssertNotEqual(
                WatchSendDecision.transport(activationState: .activated, isReachable: false),
                .queued
            )
        }
    }

    /// Guards the assumption every mapping above rests on: these raw values are
    /// read from the WatchConnectivity SDK, not from memory. If Apple ever
    /// renumbers them, the mappings are wrong and this fails first.
    func testErrorCodeRawValuesMatchTheSDK() {
        XCTAssertEqual(WCError.Code.notReachable.rawValue, 7007)
        XCTAssertEqual(WCError.Code.messageReplyFailed.rawValue, 7011)
        XCTAssertEqual(WCError.Code.messageReplyTimedOut.rawValue, 7012)
        XCTAssertEqual(WCError.Code.deliveryFailed.rawValue, 7014)
    }
}
