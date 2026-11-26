//
//  CallFluidityWiringTests.swift
//  OSHITests
//
//  __CALL_FLUIDITY_2026_11_21__ The pieces of the iOS port that are not the jitter buffer
//  itself: the playout clock's pacing rule, and when the UDP relay counts as stalled (the
//  WebSocket bridge — desktop `UdpStallBridgeTest`).
//

import XCTest
@testable import OSHI

final class CallFluidityWiringTests: XCTestCase {

    // MARK: playout clock

    func testTheClockTopsThePlayerUpToTwoFramesAndNoMore() {
        let c = OpusPlayoutClock(clock: { 0 })
        XCTAssertEqual(c.due(queuedSamples: 0).count, 2, "empty player: two frames")
        XCTAssertEqual(c.due(queuedSamples: 960).count, 1, "one frame left: one more")
        XCTAssertEqual(c.due(queuedSamples: 961).count, 0, "more than one frame left: wait")
        XCTAssertEqual(c.due(queuedSamples: 4000).count, 0)
        for f in c.due(queuedSamples: 0) { XCTAssertEqual(f.pcm.count, 960) }
    }

    func testTheClockPullsOnlyWhileAPlayerIsRunning() {
        let c = OpusPlayoutClock()
        let lock = NSLock()
        var played = 0
        var playerReady = false
        let first = expectation(description: "a frame is handed over once the player runs")
        first.assertForOverFulfill = false
        c.start(queuedSamples: { lock.lock(); defer { lock.unlock() }; return playerReady ? 0 : nil },
                play: { _, _ in lock.lock(); played += 1; lock.unlock(); first.fulfill() })
        Thread.sleep(forTimeInterval: 0.1)
        lock.lock(); XCTAssertEqual(played, 0, "no player: nothing pulled"); playerReady = true; lock.unlock()
        wait(for: [first], timeout: 2)
        c.stop()
        XCTAssertFalse(c.isRunning)
    }

    func testFilingMarksTheCallAsClockDrivenAndResetForgetsIt() {
        let c = OpusPlayoutClock(clock: { 0 })
        XCTAssertFalse(c.isActive)
        XCTAssertFalse(c.file(Data([0x20, 0, 1, 1, 2])), "a wrong version does not parse")
        XCTAssertFalse(c.isActive)
        let p = OpusCallEncoder()!.encode([Int16](repeating: 100, count: 960))[0]
        XCTAssertTrue(c.file(p))
        XCTAssertTrue(c.isActive)
        c.reset()
        XCTAssertFalse(c.isActive)
        XCTAssertTrue(c.jitter.isEmpty)
    }

    // MARK: UDP stall bridge (desktop UdpStallBridgeTest)

    func testNeverCarriedMediaIsNotAStall() {
        XCTAssertFalse(VoiceCallManager.udpStalled(lastMediaRx: nil, now: Date()))
    }

    func testCarriedMediaRecentlyIsNotAStall() {
        let t = Date()
        XCTAssertFalse(VoiceCallManager.udpStalled(lastMediaRx: t, now: t.addingTimeInterval(VoiceCallManager.udpStallSeconds - 0.001)))
    }

    func testCarriedMediaThenWentSilentForTheWindowIsAStall() {
        let t = Date()
        XCTAssertTrue(VoiceCallManager.udpStalled(lastMediaRx: t, now: t.addingTimeInterval(VoiceCallManager.udpStallSeconds)))
    }

    func testTheBridgeEngagesBeforeTheEvidenceWindowGivesTheRelayUp() {
        // `transportLivenessWindow` (2 s) is when the relay stops being the primary on
        // evidence; the bridge must already be carrying by then.
        XCTAssertLessThan(VoiceCallManager.udpStallSeconds, 2.0)
    }
}
