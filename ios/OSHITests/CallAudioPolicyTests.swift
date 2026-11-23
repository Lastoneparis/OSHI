import XCTest
@testable import OSHI

/// __CALL_AUDIO_POLICY_2026_11_02__ The two reported symptoms, as assertions.
final class CallAudioPolicyTests: XCTestCase {

    // MARK: - Music must stop when a call starts

    func testDiallingWhileMusicPlaysTakesTheSession() {
        XCTAssertTrue(CallAudioPolicy.shouldInterruptOtherAudio(
            otherAudioPlaying: true, callSessionAlreadyActive: false))
    }

    func testDiallingInSilenceLeavesTheSessionAlone() {
        // The whole point of the guard: the quiet case behaves exactly as it
        // did before this policy existed, so the CallKit handoff cannot regress.
        XCTAssertFalse(CallAudioPolicy.shouldInterruptOtherAudio(
            otherAudioPlaying: false, callSessionAlreadyActive: false))
    }

    func testAnAlreadyActiveCallSessionIsNotBounced() {
        // Re-activating a live CallKit session is the bounce that muted the
        // ringback (2026-06-10) and broke the audio handoff (2026-07-26).
        XCTAssertFalse(CallAudioPolicy.shouldInterruptOtherAudio(
            otherAudioPlaying: true, callSessionAlreadyActive: true))
    }

    // MARK: - An interruption with no `.ended` must not be permanent

    private func recovery(_ seconds: TimeInterval,
                          callKitOwns: Bool = true,
                          callKitActive: Bool = false,
                          otherAudio: Bool = true,
                          foreground: Bool = false) -> CallAudioPolicy.Recovery {
        CallAudioPolicy.recovery(interruptedFor: seconds,
                                 callKitOwnsSession: callKitOwns,
                                 callKitSessionActive: callKitActive,
                                 otherAudioPlaying: otherAudio,
                                 appIsForeground: foreground)
    }

    func testTheFirstInstantsAreNeverFought() {
        XCTAssertEqual(recovery(0.0), .wait)
        XCTAssertEqual(recovery(1.0), .wait)
        // Even with every other signal screaming "resume".
        XCTAssertEqual(recovery(0.5, callKitActive: true, otherAudio: false, foreground: true), .wait)
    }

    func testCallKitSayingItsSessionIsLiveUnlatchesImmediately() {
        XCTAssertEqual(recovery(2.0, callKitActive: true), .resume("callkit-session-live"))
    }

    func testTheInterrupterGoingAwayInTheForegroundUnlatches() {
        XCTAssertEqual(recovery(2.0, otherAudio: false, foreground: true), .resume("interrupter-gone"))
    }

    func testTheInterrupterGoingAwayInTheBackgroundIsNotEnoughOnItsOwn() {
        // Siri reports no "other audio" while it is talking. Resuming there
        // just throws against a session we do not own.
        XCTAssertEqual(recovery(2.0, otherAudio: false, foreground: false), .wait)
    }

    func testTheLatchCannotHoldForeverEvenWithNoOtherSignal() {
        // THE REPORTED BUG: screen off → app backgrounded → `.ended` never
        // delivered → audio dead in both directions, watchdogs muzzled, for
        // the rest of the call. Time alone must release it.
        XCTAssertEqual(recovery(19.9), .wait)
        XCTAssertEqual(recovery(20.0), .resume("latch-timeout"))
        XCTAssertEqual(recovery(120.0), .resume("latch-timeout"))
    }

    func testWithoutCallKitTheActiveFlagProvesNothing() {
        // China bypass / in-app accept: we own the session, so CallKit's
        // "active" flag is stale state, not evidence.
        XCTAssertEqual(recovery(3.0, callKitOwns: false, callKitActive: true), .wait)
        XCTAssertEqual(recovery(25.0, callKitOwns: false, callKitActive: true), .resume("latch-timeout"))
    }

    func testTheHoldWindowIsOverridable() {
        XCTAssertEqual(CallAudioPolicy.recovery(interruptedFor: 5,
                                                callKitOwnsSession: true,
                                                callKitSessionActive: false,
                                                otherAudioPlaying: true,
                                                appIsForeground: false,
                                                minimumHold: 1,
                                                maximumHold: 4), .resume("latch-timeout"))
    }
}
