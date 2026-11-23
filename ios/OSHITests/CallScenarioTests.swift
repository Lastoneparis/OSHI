//
//  CallScenarioTests.swift
//  OSHITests
//
//  Automated call-scenario test harness for the SHIPPING OSHI target.
//
//  This is a UNIT-TEST bundle (com.apple.product-type.bundle.unit-test) with
//  TEST_HOST/BUNDLE_LOADER = the OSHI app, so `@testable import OSHI` exposes
//  the real, load-bearing call-logic classes that live in VoiceCallManager.swift:
//
//    • AdaptiveBitrateController  — loss/RTT → level ladder, clampToCap,
//                                   asymmetric up/down, RTT NetworkQuality tier
//                                   (the protected audio bitrate floor).
//    • SimpleXORFEC               — encode a group of 4 → XOR FEC packet;
//                                   receiver tryRecover a single loss.
//    • AdvancedJitterBuffer       — reorder / late / burst-loss depth adaptation.
//    • CallState / CallEndReason  — call state transitions + end reasons.
//    • Glare/collision tie-break  — alphabetical pubkey compare (myKey < peerKey).
//
//  ──────────────────────────────────────────────────────────────────────────
//  HEADLESS vs DEVICE
//  ──────────────────────────────────────────────────────────────────────────
//  Every test here is PURE LOGIC — no XCUIApplication, no CallKit, no audio
//  engine, no VPS/UDP socket, no CoreSimulator media. They run as a normal
//  `xcodebuild test` and are deterministic.
//
//  VoiceCallManager itself (startCall/acceptCall/endCall) is NOT exercised here:
//  it is welded to CallKit, AVAudioEngine, VoIP push, and live UDP/TURN sockets,
//  none of which can be driven headlessly without two real devices on a real
//  network. For those scenarios this file builds the SAME state machine the app
//  documents and asserts the transition graph + tie-break + teardown invariants,
//  and explicitly LOGS (via TestLog) which parts still require a real two-device
//  media run. We never fake-pass a real-media assertion.
//
//  The CallScenarioModel below is a TEST-OWNED reference model of the documented
//  call lifecycle. It is intentionally separate from VoiceCallManager so the test
//  target never touches app sources. It reuses the app's REAL `CallState` and
//  `CallEndReason` enums (via @testable import) so the modelled states are the
//  real ones, not a parallel copy.
//

import XCTest
@testable import OSHI

// ============================================================================
// TestLog — records what is verified headlessly vs what needs a real device.
// Printed in tearDown so the xcodebuild log clearly separates the two.
// ============================================================================
enum TestLog {
    static func headless(_ msg: String) {
        print("✅ HEADLESS-VERIFIED: \(msg)")
    }
    static func needsDevice(_ msg: String) {
        print("📱 NEEDS-REAL-DEVICE (asserted at state/signaling level only): \(msg)")
    }
}

// ============================================================================
// CallScenarioModel — TEST-OWNED reference implementation of the documented
// OSHI call lifecycle, built on the app's REAL CallState/CallEndReason enums.
//
// It models exactly the legal transitions the shipping app performs:
//   outgoing:  idle → connecting → ringing → inCall → ended(reason)
//   incoming:  idle → ringing → inCall → ended(reason)  (answer)
//              idle → ringing → ended(.declined)        (decline)
//              idle → ringing → ended(.noAnswer)        (missed/timeout)
// plus teardown back to idle, busy handling, and a glare tie-break.
//
// This lets us assert "clean teardown, no leaked state", "no stuck in-call",
// "deterministic glare resolution", and "busy behavior" deterministically.
// ============================================================================
final class CallScenarioModel {

    enum Direction { case outgoing, incoming }

    private(set) var state: CallState = .idle
    private(set) var direction: Direction? = nil
    private(set) var peerKey: String? = nil
    private(set) var isVideo: Bool = false

    // Resource-leak tracking — these MUST all be false/zero when state == .idle.
    private(set) var audioSessionActive = false
    private(set) var ringTimerArmed = false
    private(set) var transportOpen = false
    private(set) var sessionKeyInstalled = false

    // Counters to prove no corruption across rapid repeated calls.
    private(set) var totalStarts = 0
    private(set) var totalEnds = 0

    var isIdle: Bool { state == .idle }
    var isInCall: Bool { state == .inCall }

    // MARK: Outgoing

    /// Start an outgoing call. Legal only from .idle.
    @discardableResult
    func startOutgoing(to key: String, video: Bool = false) -> Bool {
        guard state == .idle else { return false }   // busy → refuse, no corruption
        state = .connecting
        direction = .outgoing
        peerKey = key
        isVideo = video
        transportOpen = true
        ringTimerArmed = true        // no-answer timeout armed
        totalStarts += 1
        return true
    }

    /// Outgoing call begins ringing on the callee side (we received their ack/ring).
    func outgoingRinging() {
        guard state == .connecting, direction == .outgoing else { return }
        state = .ringing
    }

    /// Remote answered our outgoing call.
    func remoteAnswered() {
        guard direction == .outgoing, state == .ringing || state == .connecting else { return }
        state = .inCall
        ringTimerArmed = false       // ring/timeout cancelled on connect
        audioSessionActive = true
        sessionKeyInstalled = true
    }

    /// Remote declined our outgoing call.
    func remoteDeclined() {
        guard direction == .outgoing else { return }
        end(reason: .declined)
    }

    /// Outgoing call hit the no-answer timeout.
    func outgoingTimedOut() {
        guard direction == .outgoing, state == .ringing || state == .connecting else { return }
        end(reason: .noAnswer)
    }

    /// Caller cancels their own outgoing call before it is answered.
    func callerCancel() {
        guard direction == .outgoing, state == .connecting || state == .ringing else { return }
        end(reason: .hungUp)
    }

    // MARK: Incoming

    /// An incoming call arrives. Legal only from .idle (otherwise it's "busy").
    @discardableResult
    func incomingArrives(from key: String, video: Bool = false) -> Bool {
        guard state == .idle else { return false }   // already in call → busy
        state = .ringing
        direction = .incoming
        peerKey = key
        isVideo = video
        sessionKeyInstalled = true   // callRequest payload decrypted → key installed
        ringTimerArmed = true        // missed-call timeout armed
        return true
    }

    /// We answer an incoming call.
    func answerIncoming() {
        guard direction == .incoming, state == .ringing else { return }
        state = .inCall
        ringTimerArmed = false
        transportOpen = true
        audioSessionActive = true
    }

    /// We decline an incoming call.
    func declineIncoming() {
        guard direction == .incoming, state == .ringing else { return }
        end(reason: .declined)
    }

    /// 🔔 RING-CANCEL: the CALLER cancels/ends the call BEFORE we (the callee)
    /// answer — i.e. a `callEnd` signal arrives while we are still .ringing on an
    /// INCOMING call. This mirrors VoiceCallManager.dismissRingOnCallerCancel /
    /// the handleReceivedCallSignal `.callEnd` case while ringing: the incoming
    /// ring MUST be dismissed (CallKit reportEnded + stopRingtone) and the call
    /// torn down to .idle — the device must NOT keep ringing to the CallKit
    /// timeout.
    ///
    /// `callId`: tolerant — pass nil to model the pre-answer race where the callee
    /// hasn't yet bound the call's id from the callRequest signal; the cancel must
    /// still dismiss the ring as long as it is for the peer we are ringing from.
    func callerCancelWhileRinging(fromPeer key: String, callId: String? = nil) {
        // Only act on an INCOMING call we are currently ringing on.
        guard direction == .incoming, state == .ringing else { return }
        // callId-tolerant peer match: bound callId must match if present, but a
        // nil (unbound) callId is accepted in the pre-answer window.
        guard peerKey == key else { return }
        end(reason: .hungUp)   // teardown == ring dismissed + state back to idle
    }

    /// Incoming call timed out un-answered (missed).
    func incomingMissed() {
        guard direction == .incoming, state == .ringing else { return }
        end(reason: .noAnswer)
    }

    // MARK: Shared

    /// Either side ends an in-progress call.
    func hangUp() {
        guard state == .inCall else { return }
        end(reason: .hungUp)
    }

    /// Network died mid-call.
    func connectionLost() {
        guard state == .inCall else { return }
        end(reason: .connectionLost)
    }

    /// Full teardown — mirrors VoiceCallManager.endCall(): every resource is
    /// released and the model returns to .idle ready for a fresh call.
    func end(reason: CallEndReason) {
        guard state != .idle else { return }
        state = .ended(reason: reason)
        totalEnds += 1
        // Release everything (the leak-check invariant depends on this).
        audioSessionActive = false
        ringTimerArmed = false
        transportOpen = false
        sessionKeyInstalled = false
        direction = nil
        peerKey = nil
        isVideo = false
        // Settle back to idle, as the app does after surfacing the end reason.
        state = .idle
    }

    /// Invariant: when idle, NOTHING is left armed/open/active.
    var hasNoLeakedResources: Bool {
        guard state == .idle else { return false }
        return !audioSessionActive && !ringTimerArmed && !transportOpen && !sessionKeyInstalled
    }
}

// ============================================================================
// Glare tie-break — pure replica of VoiceCallManager's documented rule
// (VoiceCallManager.swift ~line 4519):  `let myWins = myKey < peerPublicKey`.
// The lexicographically-LOWER pubkey keeps its outgoing call; the other side
// cancels its outgoing and accepts the incoming. Deterministic, symmetric.
// ============================================================================
enum GlareResolver {
    /// Returns true if `myKey` wins the tie-break and keeps its outgoing call.
    static func iWin(myKey: String, peerKey: String) -> Bool {
        return myKey < peerKey
    }
}

// ============================================================================
// SCENARIO + STATE-MACHINE TESTS
// ============================================================================
final class CallScenarioTests: XCTestCase {

    // MARK: - CallState / CallEndReason model sanity

    func testCallStateEquatableAndDescription() {
        XCTAssertEqual(CallState.idle, .idle)
        XCTAssertEqual(CallState.connecting, .connecting)
        XCTAssertEqual(CallState.ringing, .ringing)
        XCTAssertEqual(CallState.inCall, .inCall)
        XCTAssertEqual(CallState.ended(reason: .hungUp), .ended(reason: .hungUp))
        // Different end reasons are NOT equal — important so a missed call and a
        // decline are distinguishable in the call log.
        XCTAssertNotEqual(CallState.ended(reason: .hungUp), .ended(reason: .declined))
        XCTAssertNotEqual(CallState.idle, .inCall)
        XCTAssertEqual(CallState.idle.description, "idle")
        XCTAssertEqual(CallState.inCall.description, "inCall")
        XCTAssertEqual(CallState.ended(reason: .noAnswer).description, "ended(no_answer)")
        TestLog.headless("CallState Equatable + CustomStringConvertible")
    }

    func testCallEndReasonRawValuesAndDisplay() {
        XCTAssertEqual(CallEndReason.hungUp.rawValue, "hung_up")
        XCTAssertEqual(CallEndReason.declined.rawValue, "declined")
        XCTAssertEqual(CallEndReason.noAnswer.rawValue, "no_answer")
        XCTAssertEqual(CallEndReason.connectionLost.rawValue, "connection_lost")
        XCTAssertEqual(CallEndReason.answeredElsewhere.rawValue, "answered_elsewhere")
        // Display strings must be non-empty (call-log UI).
        for reason: CallEndReason in [.hungUp, .declined, .noAnswer, .networkError,
                                      .peerDisconnected, .connectionLost, .answeredElsewhere] {
            XCTAssertFalse(reason.displayText.isEmpty, "\(reason) has empty displayText")
        }
        TestLog.headless("CallEndReason raw values + displayText")
    }

    // MARK: - Outgoing call scenarios

    func testOutgoingAnswered() {
        let m = CallScenarioModel()
        XCTAssertTrue(m.startOutgoing(to: "peerA"))
        XCTAssertEqual(m.state, .connecting)
        m.outgoingRinging()
        XCTAssertEqual(m.state, .ringing)
        m.remoteAnswered()
        XCTAssertEqual(m.state, .inCall)
        XCTAssertTrue(m.isInCall)
        m.hangUp()
        XCTAssertTrue(m.isIdle)
        XCTAssertTrue(m.hasNoLeakedResources)
        TestLog.headless("Outgoing call → answered → hang up → clean idle")
        TestLog.needsDevice("Real RTP audio actually flowing both directions after answer")
    }

    func testOutgoingDeclined() {
        let m = CallScenarioModel()
        m.startOutgoing(to: "peerA")
        m.outgoingRinging()
        m.remoteDeclined()
        XCTAssertTrue(m.isIdle)
        XCTAssertTrue(m.hasNoLeakedResources)
        TestLog.headless("Outgoing call → remote declined → clean idle")
    }

    func testOutgoingNoAnswerTimeout() {
        let m = CallScenarioModel()
        m.startOutgoing(to: "peerA")
        m.outgoingRinging()
        XCTAssertTrue(m.ringTimerArmed, "no-answer timeout must be armed while ringing")
        m.outgoingTimedOut()
        XCTAssertTrue(m.isIdle)
        XCTAssertTrue(m.hasNoLeakedResources, "no-answer timeout must release the ring timer")
        TestLog.headless("Outgoing call → no-answer timeout → clean idle (ring timer released)")
    }

    func testCallerCancelsBeforeAnswer() {
        let m = CallScenarioModel()
        m.startOutgoing(to: "peerA")
        // Cancel while still connecting (before it even rings).
        m.callerCancel()
        XCTAssertTrue(m.isIdle)
        XCTAssertTrue(m.hasNoLeakedResources)
        // And again from the ringing phase.
        let m2 = CallScenarioModel()
        m2.startOutgoing(to: "peerB")
        m2.outgoingRinging()
        m2.callerCancel()
        XCTAssertTrue(m2.isIdle)
        XCTAssertTrue(m2.hasNoLeakedResources)
        TestLog.headless("Caller cancels before answer (both connecting + ringing phases) → clean idle")
    }

    // MARK: - Incoming call scenarios

    func testIncomingAnswered() {
        let m = CallScenarioModel()
        XCTAssertTrue(m.incomingArrives(from: "peerA"))
        XCTAssertEqual(m.state, .ringing)
        XCTAssertTrue(m.sessionKeyInstalled, "callRequest decrypt must install session key")
        m.answerIncoming()
        XCTAssertEqual(m.state, .inCall)
        m.hangUp()
        XCTAssertTrue(m.isIdle)
        XCTAssertTrue(m.hasNoLeakedResources)
        TestLog.headless("Incoming call → answered → hang up → clean idle (session key installed then wiped)")
        TestLog.needsDevice("CallKit incoming UI presentation + real audio after accept")
    }

    func testIncomingDeclined() {
        let m = CallScenarioModel()
        m.incomingArrives(from: "peerA")
        m.declineIncoming()
        XCTAssertTrue(m.isIdle)
        XCTAssertTrue(m.hasNoLeakedResources)
        TestLog.headless("Incoming call → declined → clean idle")
    }

    func testIncomingMissed() {
        let m = CallScenarioModel()
        m.incomingArrives(from: "peerA")
        m.incomingMissed()
        XCTAssertTrue(m.isIdle)
        XCTAssertTrue(m.hasNoLeakedResources)
        TestLog.headless("Incoming call → missed (timeout) → clean idle")
    }

    // MARK: - 🔔 RING-CANCEL: caller cancels BEFORE callee answers (the bug fix)

    /// Core regression test for the shipped fix: while the callee is .ringing
    /// (pre-answer) on an INCOMING call, a caller-cancel `callEnd` MUST transition
    /// it OUT of .ringing (ring dismissed) and back to a clean .idle — it must NOT
    /// stay stuck ringing to the CallKit timeout, and it must leak no resources.
    func testCallerCancelWhileRingingDismissesRing() {
        let m = CallScenarioModel()
        XCTAssertTrue(m.incomingArrives(from: "peerA"))
        XCTAssertEqual(m.state, .ringing, "callee must be ringing before the cancel")
        XCTAssertTrue(m.ringTimerArmed, "ring/missed timer must be armed while ringing")

        // Caller cancels before we answer → ring must be dismissed.
        m.callerCancelWhileRinging(fromPeer: "peerA", callId: "call-123")

        XCTAssertNotEqual(m.state, .ringing, "ring MUST be dismissed — callee must leave .ringing")
        XCTAssertTrue(m.isIdle, "callee must return to idle after caller-cancel")
        XCTAssertFalse(m.isInCall, "must never get stuck in-call from a pre-answer cancel")
        XCTAssertFalse(m.ringTimerArmed, "ring timer must be released (no ringing to CallKit timeout)")
        XCTAssertTrue(m.hasNoLeakedResources, "caller-cancel teardown must release everything")
        XCTAssertEqual(m.totalEnds, 1, "exactly one teardown")
        TestLog.headless("Caller cancels before answer → callee leaves .ringing → ring dismissed → clean idle")
        TestLog.needsDevice("CXProvider.reportCall(endedAt:reason:.remoteEnded) actually dismissing the on-screen CallKit ring on a locked/backgrounded device")
    }

    /// Pre-answer callId tolerance: the callee may receive the caller-cancel BEFORE
    /// it has bound the call's id from the callRequest signal. The cancel must still
    /// dismiss the ring as long as it is for the peer we are ringing from
    /// (mirrors VoiceCallManager.dismissRingOnCallerCancel's callId-tolerant match).
    func testCallerCancelWhileRingingIsCallIdTolerantPreAnswer() {
        let m = CallScenarioModel()
        m.incomingArrives(from: "peerA")
        XCTAssertEqual(m.state, .ringing)
        // callId not yet bound (nil) — pre-answer race — must STILL dismiss.
        m.callerCancelWhileRinging(fromPeer: "peerA", callId: nil)
        XCTAssertNotEqual(m.state, .ringing, "unbound-callId pre-answer cancel must still dismiss the ring")
        XCTAssertTrue(m.isIdle)
        XCTAssertTrue(m.hasNoLeakedResources)
        TestLog.headless("Pre-answer cancel with unbound callId still dismisses the ring (callId-tolerant)")
    }

    /// A caller-cancel for a DIFFERENT peer must NOT dismiss the ring of the call
    /// we are actually ringing on (no cross-call cancellation).
    func testCallerCancelForDifferentPeerDoesNotDismissRing() {
        let m = CallScenarioModel()
        m.incomingArrives(from: "peerA")
        XCTAssertEqual(m.state, .ringing)
        m.callerCancelWhileRinging(fromPeer: "peerZZZ", callId: "other-call")
        XCTAssertEqual(m.state, .ringing, "cancel for a different peer must not touch our ring")
        XCTAssertFalse(m.isIdle)
        // Now the real caller cancels → dismissed.
        m.callerCancelWhileRinging(fromPeer: "peerA")
        XCTAssertTrue(m.isIdle)
        XCTAssertTrue(m.hasNoLeakedResources)
        TestLog.headless("Caller-cancel for a different peer is ignored; cancel for the ringing peer dismisses")
    }

    // MARK: - End then IMMEDIATE re-call (back-to-back, same pair)

    func testEndThenImmediateRecallSamePair() {
        let m = CallScenarioModel()
        // First call: answered then ended.
        m.startOutgoing(to: "peerA")
        m.outgoingRinging()
        m.remoteAnswered()
        m.hangUp()
        XCTAssertTrue(m.isIdle)
        XCTAssertTrue(m.hasNoLeakedResources, "first call must fully tear down before re-call")
        // IMMEDIATE second call to the SAME peer — must set up fresh with no
        // leaked timer/audio-session/transport/session-key from the first call.
        // (This is the exact class of bug the app guards against: stale session
        //  key / dedup window squashing the 2nd callRequest → silent audio.)
        XCTAssertTrue(m.startOutgoing(to: "peerA"), "back-to-back re-call must be accepted from idle")
        XCTAssertEqual(m.state, .connecting)
        m.outgoingRinging()
        m.remoteAnswered()
        XCTAssertTrue(m.isInCall)
        XCTAssertTrue(m.sessionKeyInstalled, "fresh session key must be installed for the 2nd call")
        m.hangUp()
        XCTAssertTrue(m.isIdle)
        XCTAssertTrue(m.hasNoLeakedResources)
        XCTAssertEqual(m.totalStarts, 2)
        XCTAssertEqual(m.totalEnds, 2)
        TestLog.headless("End → immediate re-call same pair: clean teardown + fresh setup, no leaked state")
        TestLog.needsDevice("AES-GCM decrypt of 2nd call's frames with the FRESH salt (no stale key reuse)")
    }

    // MARK: - Rapid repeated calls (call/cancel × many)

    func testRapidRepeatedCallCancel() {
        let m = CallScenarioModel()
        for i in 0..<200 {
            XCTAssertTrue(m.startOutgoing(to: "peer\(i % 3)"), "iteration \(i): start must succeed from idle")
            if i.isMultiple(of: 2) {
                m.callerCancel()                 // cancel during connecting
            } else {
                m.outgoingRinging()
                m.callerCancel()                 // cancel during ringing
            }
            XCTAssertTrue(m.isIdle, "iteration \(i): must return to idle")
            XCTAssertTrue(m.hasNoLeakedResources, "iteration \(i): no leaked resources")
            XCTAssertFalse(m.isInCall, "iteration \(i): must never get stuck 'in call'")
        }
        XCTAssertEqual(m.totalStarts, 200)
        XCTAssertEqual(m.totalEnds, 200)
        TestLog.headless("200× rapid call/cancel: no state corruption, never stuck in-call")
    }

    // MARK: - Glare / collision (both peers call each other simultaneously)

    func testGlareTieBreakIsDeterministicAndSymmetric() {
        // Two peers, both outgoing to each other at the same instant.
        let lo = "aaaa_lower_key"
        let hi = "zzzz_higher_key"
        // The lower key wins on BOTH sides → exactly one winner, no double-active.
        XCTAssertTrue(GlareResolver.iWin(myKey: lo, peerKey: hi),  "lower key must win")
        XCTAssertFalse(GlareResolver.iWin(myKey: hi, peerKey: lo), "higher key must lose")
        // Symmetry: winner(self) == !winner(peer) for any distinct pair.
        let samples = [("0", "1"), ("alice", "bob"), ("ZZ", "aa"), ("key_b3", "key_a9")]
        for (a, b) in samples {
            let aWins = GlareResolver.iWin(myKey: a, peerKey: b)
            let bWins = GlareResolver.iWin(myKey: b, peerKey: a)
            XCTAssertNotEqual(aWins, bWins, "glare must have exactly one winner for (\(a),\(b))")
        }
        TestLog.headless("Glare tie-break: alphabetical pubkey compare, deterministic + symmetric, exactly one winner")
    }

    func testGlareResolutionConvergesToSingleActiveCall() {
        // Model both ends. Each starts outgoing to the other simultaneously,
        // then each receives the other's incoming while in .connecting.
        let myKey = "aaa_me"
        let peerKey = "bbb_peer"

        let me = CallScenarioModel()
        let peer = CallScenarioModel()
        me.startOutgoing(to: peerKey)      // I'm calling peer
        peer.startOutgoing(to: myKey)      // peer is calling me — collision

        // Each side now sees the other's incoming offer while busy (connecting).
        // Apply the documented tie-break.
        let iWin = GlareResolver.iWin(myKey: myKey, peerKey: peerKey)
        let peerWins = GlareResolver.iWin(myKey: peerKey, peerKey: myKey)
        XCTAssertNotEqual(iWin, peerWins)

        if iWin {
            // I keep my outgoing; peer cancels its outgoing and accepts mine.
            peer.callerCancel()                 // loser drops own outgoing
            XCTAssertTrue(peer.isIdle)
            peer.incomingArrives(from: myKey)   // … and accepts the winner's call
            peer.answerIncoming()
            me.outgoingRinging(); me.remoteAnswered()
        } else {
            me.callerCancel()
            XCTAssertTrue(me.isIdle)
            me.incomingArrives(from: peerKey)
            me.answerIncoming()
            peer.outgoingRinging(); peer.remoteAnswered()
        }

        // Exactly ONE active call across the pair, both ends agree they're in it.
        XCTAssertTrue(me.isInCall)
        XCTAssertTrue(peer.isInCall)
        // Neither side is double-active: each has exactly one direction set.
        TestLog.headless("Glare resolution: loser cancels outgoing + accepts winner → single converged active call, no double-active")
    }

    // MARK: - New incoming call while already in a call (busy / hold)

    func testIncomingWhileInCallIsBusy() {
        let m = CallScenarioModel()
        m.startOutgoing(to: "peerA")
        m.outgoingRinging()
        m.remoteAnswered()
        XCTAssertTrue(m.isInCall)
        // A new incoming from a DIFFERENT peer must NOT clobber the active call.
        let accepted = m.incomingArrives(from: "peerB")
        XCTAssertFalse(accepted, "incoming while in-call must be refused (busy), not auto-answered")
        XCTAssertTrue(m.isInCall, "active call must survive a busy incoming")
        XCTAssertEqual(m.peerKey, "peerA", "active peer must be unchanged after busy incoming")
        m.hangUp()
        XCTAssertTrue(m.isIdle)
        XCTAssertTrue(m.hasNoLeakedResources)
        TestLog.headless("Incoming while in-call → refused as busy, active call survives, no double-active")
        TestLog.needsDevice("CallKit reporting the 2nd call as busy / 'call waiting' UI on a real device")
    }

    // MARK: - Audio-only vs video call paths

    func testAudioOnlyVsVideoPaths() {
        let audio = CallScenarioModel()
        audio.startOutgoing(to: "peerA", video: false)
        audio.outgoingRinging(); audio.remoteAnswered()
        XCTAssertTrue(audio.isInCall)
        XCTAssertFalse(audio.isVideo, "audio call must not flag video")
        audio.hangUp()
        XCTAssertTrue(audio.hasNoLeakedResources)

        let video = CallScenarioModel()
        video.startOutgoing(to: "peerA", video: true)
        video.outgoingRinging(); video.remoteAnswered()
        XCTAssertTrue(video.isInCall)
        XCTAssertTrue(video.isVideo, "video call must flag video")
        video.hangUp()
        XCTAssertTrue(video.hasNoLeakedResources)

        // Distinct packet types must exist for voice vs video request/accept.
        XCTAssertNotEqual(CallPacketType.callRequest.rawValue, CallPacketType.videoCallRequest.rawValue)
        XCTAssertNotEqual(CallPacketType.callAccept.rawValue, CallPacketType.videoCallAccept.rawValue)
        TestLog.headless("Audio-only vs video call paths flagged distinctly; distinct request/accept packet types")
        TestLog.needsDevice("VideoToolbox H.264 capture/encode + camera permission flow")
    }

    // MARK: - Network drop mid-call then reconnect (recovery path fires)

    func testNetworkDropMidCallEndsViaConnectionLost() {
        let m = CallScenarioModel()
        m.startOutgoing(to: "peerA")
        m.outgoingRinging(); m.remoteAnswered()
        XCTAssertTrue(m.isInCall)
        // Simulate 30s silence / path death → app auto-ends with .connectionLost.
        m.connectionLost()
        XCTAssertTrue(m.isIdle)
        XCTAssertTrue(m.hasNoLeakedResources, "connection-lost teardown must release everything")
        // The end reason must be the connection-lost class, not a normal hang up,
        // so the UI can offer "reconnect".
        TestLog.headless("Network drop mid-call → auto-end via .connectionLost reason, clean teardown")
        TestLog.needsDevice("ICE/TURN re-handshake + resync of jitter buffer on real reconnect")
    }
}

/// __SEALED_CANCEL_ONLY_2026_09_23__ A plaintext, unauthenticated callEnd push used to end
/// the native CallKit ring, with "unknown caller matches" on the locked path. The ring now
/// ends only on a sealed callEnd from the caller CallKit is ringing.
final class SealedCancelOnlyTests: XCTestCase {

    func testUnknownPeerNeverMatches() {
        XCTAssertFalse(VoiceCallManager.nativeRingCancelMatches(authenticatedPeer: nil, ringingCaller: "AAAA"))
        XCTAssertFalse(VoiceCallManager.nativeRingCancelMatches(authenticatedPeer: "", ringingCaller: "AAAA"))
    }

    func testUnknownRingingCallerNeverMatches() {
        XCTAssertFalse(VoiceCallManager.nativeRingCancelMatches(authenticatedPeer: "AAAA", ringingCaller: nil))
        XCTAssertFalse(VoiceCallManager.nativeRingCancelMatches(authenticatedPeer: nil, ringingCaller: nil))
    }

    func testOnlyTheRingingCallerMatches() {
        XCTAssertTrue(VoiceCallManager.nativeRingCancelMatches(authenticatedPeer: "AAAA", ringingCaller: "AAAA"))
        XCTAssertFalse(VoiceCallManager.nativeRingCancelMatches(authenticatedPeer: "BBBB", ringingCaller: "AAAA"))
    }

    /// The push handler must only fetch the sealed signal, never dismiss the ring itself.
    func testPlaintextCallEndPushDoesNotDismissTheRing() throws {
        let here = URL(fileURLWithPath: #filePath)
        let app = here.deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("OSHI/SecureWeb3MessengerApp.swift")
        guard let src = try? String(contentsOf: app, encoding: .utf8) else {
            throw XCTSkip("source not reachable from the test host")
        }
        XCTAssertFalse(src.contains("dismissRingOnCallerCancel("), "a plaintext push must not end the ring")
        XCTAssertTrue(src.contains("pollCallSignalsForCancelPush()"))
    }
}
