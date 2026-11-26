//
//  AdaptiveJitterBufferTests.swift
//  OSHITests
//
//  __CALL_FLUIDITY_2026_11_21__ SWIFT PORT of `AdaptiveJitterBufferTest.kt` (desktop + Android,
//  identical): the `AdaptiveJitterBuffer` rules, one per test, in virtual time. Frames are PCM
//  (`AdaptiveJitterBuffer.PcmFrames`) whose sample `tag` carries the seq, so what reached the
//  speaker can be read back exactly. Same scenarios, same assertions as the Kotlin tests.
//

import XCTest
@testable import OSHI

final class AdaptiveJitterBufferTests: XCTestCase {
    private let tag = 480
    private var now: Int64 = 0
    private typealias Kind = AdaptiveJitterBuffer.Kind

    private func jb() -> AdaptiveJitterBuffer { AdaptiveJitterBuffer(clock: { [unowned self] in self.now }) }

    /// A 20 ms PCM frame, loud (voiced) or quiet, tagged with `seq` in sample `tag`.
    private func frame(_ seq: Int64, amp: Int = 6000) -> Data {
        var b = [UInt8](repeating: 0, count: 1920)
        for i in 0..<960 {
            let v = i == tag ? Int(seq) : Int(Double(amp) * sin(2 * Double.pi * 150 * Double(i) / 48_000.0))
            b[2 * i] = UInt8(truncatingIfNeeded: v); b[2 * i + 1] = UInt8(truncatingIfNeeded: v >> 8)
        }
        return Data(b)
    }

    private func feed(_ b: AdaptiveJitterBuffer, _ seq: Int64, amp: Int = 6000) { b.put(seq, frame(seq, amp: amp)) }

    /// Pull one frame 20 ms later; returns (kind, sample `tag`).
    private func tick(_ b: AdaptiveJitterBuffer) -> (Kind, Int) {
        now += 20
        let p = b.pull()
        return (b.kind(), Int(p[tag]))
    }

    private func assertTick(_ b: AdaptiveJitterBuffer, _ kind: Kind, _ value: Int, file: StaticString = #filePath, line: UInt = #line) {
        let (k, t) = tick(b)
        XCTAssertEqual(k, kind, file: file, line: line)
        XCTAssertEqual(t, value, file: file, line: line)
    }

    func testEveryPullIsExactly20ms() {
        let b = jb()
        for _ in 0..<5 { XCTAssertEqual(b.pull().count, 960) }
        feed(b, 1)
        for _ in 0..<5 { XCTAssertEqual(b.pull().count, 960) }
    }

    func testInOrderStreamPlaysEveryFrameOnceInOrder() {
        let b = jb()
        feed(b, 100)
        var got: [Int] = []
        for s in Int64(101)...150 { feed(b, s); let (k, t) = tick(b); if k == .normal { got.append(t) } }
        XCTAssertEqual(got, Array(100...149))
        XCTAssertEqual(b.framesExpanded + b.framesLossConcealed, 0)
    }

    func testALateFrameIsWaitedForAndStillPlayed() {
        let b = jb()
        feed(b, 1); feed(b, 2)
        _ = tick(b); _ = tick(b)                          // 1, 2 played
        XCTAssertEqual(tick(b).0, .expand)                // 3 not here, nothing queued
        feed(b, 3); feed(b, 4)                            // 3 arrives 20+ ms late
        assertTick(b, .normal, 3)
        assertTick(b, .normal, 4)
        XCTAssertEqual(b.framesLate, 0, "nothing was discarded")
    }

    func testAFrameMissingWithLaterOnesQueuedIsConcealedAndTheSlotAdvances() {
        let b = jb()
        feed(b, 1); _ = tick(b)
        feed(b, 3); feed(b, 4)                            // 2 lost
        XCTAssertEqual(tick(b).0, .lossConceal)
        assertTick(b, .normal, 3)
        XCTAssertEqual(b.framesLossConcealed, 1)
    }

    func testAfterAnOutageTheConcealmentPlayedStandsInForTheLostFrames() {
        let b = jb()
        for s in Int64(1)...5 { feed(b, s); _ = tick(b) }
        for _ in 0..<150 { _ = tick(b) }                  // 3 s outage: 150 expands
        for s in Int64(156)...160 { feed(b, s) }          // stream resumes where real time is
        assertTick(b, .normal, 156)
        XCTAssertEqual(b.jumps, 0, "no jump, no backlog")
        XCTAssertLessThanOrEqual(b.delayMs(), 100)
    }

    func testABurstAfterAStallIsCutBackToTheTargetInOneStep() {
        let b = jb()
        for s in Int64(1)...20 { feed(b, s); _ = tick(b) }
        for s in Int64(21)...100 { feed(b, s) }           // 1.6 s delivered at once
        _ = tick(b)
        XCTAssertEqual(b.flushes, 1)
        XCTAssertLessThanOrEqual(b.delayMs(), b.targetMs() + 40)
    }

    func testAStandingExcessIsDrainedPausesFirst() {
        let b = jb()
        // 240 ms delivered at once, all quiet, then a steady stream: once the burst has left
        // the estimate window the standing queue is shed — whole quiet frames, nothing voiced.
        for s in Int64(1)...12 { feed(b, s, amp: 50) }
        var seq: Int64 = 13
        for _ in 0..<300 { feed(b, seq, amp: 50); seq += 1; _ = tick(b) }
        XCTAssertGreaterThanOrEqual(b.framesSkipped, 5)
        XCTAssertEqual(b.samplesAccelerated, 0)
        XCTAssertLessThanOrEqual(b.delayMs(), b.targetMs() + AdaptiveJitterBuffer.accelAboveMs + 20)
    }

    func testVoicedExcessIsShortenedByPitchPeriodsOnceForced() {
        let b = jb()
        for s in Int64(1)...12 { feed(b, s) }
        var seq: Int64 = 13
        for _ in 0..<150 { feed(b, seq); seq += 1; _ = tick(b) }
        XCTAssertGreaterThan(b.samplesAccelerated, 960 * 3)
        XCTAssertEqual(b.framesSkipped, 0, "voiced frames are not dropped whole")
    }

    func testTargetFollowsMeasuredJitterAndIsCapped() {
        let b = jb()
        var rnd = JavaRandom(1)
        // Arrival = send + 0..120 ms jitter (java.util.Random(1), Kotlin's stable sortedBy).
        var arrivals: [(Int, Int64)] = []
        for s in 0..<300 {
            let at: Int64 = Int64(s) * 20 + Int64(rnd.nextInt(120))
            arrivals.append((s, at))
        }
        arrivals.sort { (a: (Int, Int64), b: (Int, Int64)) -> Bool in a.1 != b.1 ? a.1 < b.1 : a.0 < b.0 }
        var seq: Int64 = 0
        for (s, at) in arrivals { now = at; b.put(Int64(s), frame(Int64(s))); _ = b.pull(); seq = Int64(s) }
        XCTAssertTrue((100...AdaptiveJitterBuffer.maxTargetMsDefault).contains(b.targetMs()), "target \(b.targetMs())")
        // Then a clean network for longer than the window: the target comes back down.
        for _ in 1...400 { now += 20; seq += 1; feed(b, seq); _ = b.pull() }
        XCTAssertLessThanOrEqual(b.targetMs(), 40)
    }

    func testACopyOfAFrameThatAlreadyArrivedDoesNotRaiseTheTarget() {
        let b = jb()
        for s in Int64(0)..<60 { now = s * 20; feed(b, s); _ = b.pull() }
        let before = b.targetMs()
        // Frame 40's own packet comes ~400 ms late — its audio came long ago in 41's redundancy.
        b.put(40, frame(40))
        for i in 0..<10 { now += 20; feed(b, 60 + Int64(i)); _ = b.pull() }
        XCTAssertEqual(b.targetMs(), before)
    }

    func testAFrameThatOnlyArrivesAfterItsSlotRaisesTheTarget() {
        let b = jb()
        for s in Int64(0)..<60 { now = s * 20; feed(b, s); _ = b.pull() }
        let before = b.targetMs()
        // 60..64 arrive 200 ms late, nothing carried them earlier.
        now += 200
        for s in Int64(60)...64 { feed(b, s) }
        for i in 0..<10 { now += 20; feed(b, 65 + Int64(i)); _ = b.pull() }
        XCTAssertGreaterThanOrEqual(b.targetMs(), before + 100)
    }

    func testDuplicatesAndFramesBehindThePlayoutPointAreIgnored() {
        let b = jb()
        feed(b, 1); feed(b, 1); _ = tick(b)
        XCTAssertEqual(b.framesDuplicate, 1)
        feed(b, 1)
        XCTAssertEqual(b.framesLate, 1)
        XCTAssertEqual(b.resyncs, 0)
    }

    func testASeqFarBehindThePlayoutPointIsANewStreamResync() {
        let b = jb()
        for s in Int64(1000)...1010 { feed(b, s); _ = tick(b) }
        feed(b, 5); feed(b, 6)
        XCTAssertEqual(b.resyncs, 1)
        assertTick(b, .normal, 5)                         // the new stream plays from its start
    }

    func testALongHoleWithAudioQueuedBehindItIsJumped() {
        let b = jb()
        feed(b, 1); _ = tick(b)
        for s in Int64(40)...45 { feed(b, s) }            // 38 frames never came, no expansion yet
        assertTick(b, .normal, 40)
        XCTAssertEqual(b.jumps, 1)
    }

    func testCodecConcealmentIsAskedWithTheNextFrameWhenItIsQueued() {
        final class Spy: JitterFrameDecoder {
            var seen: [Data?] = []
            func decode(_ frame: Data) -> [Int16] { AdaptiveJitterBuffer.PcmFrames.shared.decode(frame) }
            func conceal(next: Data?) -> [Int16]? { seen.append(next); return [Int16](repeating: 0, count: 960) }
            func reset() {}
        }
        let spy = Spy()
        let b = AdaptiveJitterBuffer(decoder: spy, clock: { [unowned self] in self.now })
        feed(b, 1); _ = tick(b)
        feed(b, 3)
        _ = tick(b)                                       // 2 lost, 3 queued
        XCTAssertEqual(spy.seen.count, 1)
        XCTAssertEqual(Int(AdaptiveJitterBuffer.PcmFrames.shared.decode(spy.seen[0]!)[tag]), 3)
        _ = tick(b); _ = tick(b)                          // 3 played, then nothing queued → plain PLC
        XCTAssertNil(spy.seen.last!)
    }

    /// iOS-only: `SeqUnwrapper` keeps every key positive (the buffer's −1 "nothing yet" mark
    /// is never a real key), and unwraps across 65535 → 0.
    func testSeqUnwrapperStaysPositiveAndCrossesTheWrap() {
        let u = SeqUnwrapper()
        let a = u.unwrap(0)
        XCTAssertGreaterThan(a - 2, 0, "redundant copies of seq 0 file under positive keys")
        XCTAssertEqual(u.unwrap(65535), a - 1)
        XCTAssertEqual(u.unwrap(1), a + 1)
        let v = SeqUnwrapper()
        let b0 = v.unwrap(65534)
        XCTAssertEqual(v.unwrap(65535), b0 + 1)
        XCTAssertEqual(v.unwrap(0), b0 + 2)
        XCTAssertEqual(v.unwrap(3), b0 + 5)
    }
}
