//
//  CallSignalGuardsTests.swift
//  OSHITests
//
//  __CROSS_CALL_GUARD_2026_11_21__ / __AUTO_ACCEPT_AUTHENTICATED_2026_11_21__ /
//  __ANSWER_WITHOUT_KEY_2026_11_21__
//

import XCTest
@testable import OSHI

final class CallSignalGuardsTests: XCTestCase {

    private let active = "D5E5983D-1111-4222-8333-944455556666"

    func testForeignAcceptDeclineEndAreDropped() {
        for t in [CallPacketType.callAccept, .videoCallAccept, .callDecline, .callEnd] {
            XCTAssertTrue(CallSignalGuards.isForeignCallSignal(t, signalCallId: "12BA9315-0000-4000-8000-000000000000", activeCallId: active), "\(t)")
            XCTAssertFalse(CallSignalGuards.isForeignCallSignal(t, signalCallId: active, activeCallId: active), "\(t)")
        }
    }

    func testOtherTypesAreNeverGuarded() {
        for t in [CallPacketType.callRequest, .videoCallRequest, .callAnsweredElsewhere, .keepAlive, .iceCandidateExchange, .resyncRequest] {
            XCTAssertFalse(CallSignalGuards.isForeignCallSignal(t, signalCallId: "other", activeCallId: active), "\(t)")
        }
    }

    func testComparisonIsCaseInsensitiveAndNulTrimmed() {
        XCTAssertFalse(CallSignalGuards.isForeignCallSignal(.callEnd, signalCallId: active.lowercased(), activeCallId: active))
        XCTAssertFalse(CallSignalGuards.isForeignCallSignal(.callEnd, signalCallId: active + "\u{0}", activeCallId: " \(active)\n"))
        XCTAssertEqual(CallSignalGuards.normalizeCallId("\u{0}AbC\u{0}\u{0}"), "abc")
    }

    func testMissingIdOnEitherSideKeepsLegacyBehaviour() {
        XCTAssertFalse(CallSignalGuards.isForeignCallSignal(.callEnd, signalCallId: nil, activeCallId: active))
        XCTAssertFalse(CallSignalGuards.isForeignCallSignal(.callEnd, signalCallId: "", activeCallId: active))
        XCTAssertFalse(CallSignalGuards.isForeignCallSignal(.callAccept, signalCallId: "x", activeCallId: nil))
        XCTAssertFalse(CallSignalGuards.isForeignCallSignal(.callDecline, signalCallId: "\u{0}", activeCallId: active))
    }

    func testAutoAcceptNeedsAuthenticatedMediaWhileRingingOut() {
        XCTAssertTrue(CallSignalGuards.mayAutoAcceptFromMedia(ringingOutgoing: true, decryptedUnderSessionKey: true))
        XCTAssertFalse(CallSignalGuards.mayAutoAcceptFromMedia(ringingOutgoing: true, decryptedUnderSessionKey: false))
        XCTAssertFalse(CallSignalGuards.mayAutoAcceptFromMedia(ringingOutgoing: false, decryptedUnderSessionKey: true))
    }

    func testNoAcceptWithoutSessionKey() {
        XCTAssertFalse(CallSignalGuards.mayCompleteAccept(hasSessionKey: false))
        XCTAssertTrue(CallSignalGuards.mayCompleteAccept(hasSessionKey: true))
    }

    func testMeshDeclineOlderThanTheCallIsRecognised() {
        let start = Date(timeIntervalSince1970: 1_795_000_000)
        let startMs: Int64 = 1_795_000_000_000
        // Decline of the cancelled call, made 4 s before the redial.
        XCTAssertTrue(CallSignalGuards.predatesCurrentCall(packetStampMs: startMs - 4_000, callStart: start, effectiveOffsetMs: 0))
        // Within the 2 s tolerance, or after the start: about this call.
        XCTAssertFalse(CallSignalGuards.predatesCurrentCall(packetStampMs: startMs - 1_500, callStart: start, effectiveOffsetMs: 0))
        XCTAssertFalse(CallSignalGuards.predatesCurrentCall(packetStampMs: startMs + 3_000, callStart: start, effectiveOffsetMs: 0))
        // No call start, or a stamp that is not a stamp: never condemn.
        XCTAssertFalse(CallSignalGuards.predatesCurrentCall(packetStampMs: startMs - 4_000, callStart: nil, effectiveOffsetMs: 0))
        XCTAssertFalse(CallSignalGuards.predatesCurrentCall(packetStampMs: 0x0101_0000, callStart: start, effectiveOffsetMs: 0))
        // This phone 60 s fast (offset −60 s): a peer's server-time stamp 1 s after our
        // (local) start reads 59 s "before" it unless rebased.
        XCTAssertFalse(CallSignalGuards.predatesCurrentCall(packetStampMs: startMs - 59_000, callStart: start, effectiveOffsetMs: -60_000))
    }

    func testPacketStampPeek() {
        var d = Data([0x03])
        var be = UInt64(1_795_000_000_123).bigEndian
        d.append(Data(bytes: &be, count: 8))
        XCTAssertEqual(VoiceCallManager.peekPacketStampMs(d), 1_795_000_000_123)
        XCTAssertEqual(VoiceCallManager.peekPacketStampMs(d.suffix(from: 0)), 1_795_000_000_123)
        XCTAssertNil(VoiceCallManager.peekPacketStampMs(Data([0x03, 0x00])))
        XCTAssertEqual(VoiceCallManager.envelopeStampMs(NSNumber(value: 1_795_000_000_000 as Int64)), 1_795_000_000_000)
        XCTAssertNil(VoiceCallManager.envelopeStampMs(nil))
        XCTAssertNil(VoiceCallManager.envelopeStampMs(NSNumber(value: 0)))
    }
}
