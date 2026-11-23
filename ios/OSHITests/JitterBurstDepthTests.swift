//
//  JitterBurstDepthTests.swift
//  OSHITests
//
//  Two calls with Natalia sounded choppy and noisy. The capture said the link
//  was fine: 5093 of 5110 packets delivered over 105 s — 99.7 %. Nothing was
//  lost. But `targetDepthMs=180` for the WHOLE call while `iaP95Ms=85.4` and
//  `iaMaxMs` peaked at 254 / 291 / 327 ms.
//
//  Every expansion path in the buffer was driven by `networkJitterMs`, the
//  RFC 3550 exponentially-weighted mean. A mean cannot see a burst: fifty
//  arrivals at 20 ms and one at 327 ms average out to J≈18, so the estimator
//  reported a calm link while the pipeline was asked to cover a third of a
//  second with 180 ms of audio. It underran and PLC filled the hole.
//
//  These tests replay the MEASURED arrival profile rather than a made-up one,
//  because the whole failure was that a plausible-looking metric disagreed with
//  what the link actually did. The first test is the regression: it reproduces
//  the blindness by asserting the smoothed estimate stays low across exactly the
//  pattern that broke the call. If someone "simplifies" the new path back onto
//  the mean, that test still passes and this one fails — which is the point.
//

import XCTest
@testable import OSHI

final class JitterBurstDepthTests: XCTestCase {

    /// Drives the buffer on a synthetic clock so a 105 s call runs instantly and
    /// the arrival pattern is exact rather than whatever the machine was doing.
    private func makeBuffer() -> (AdvancedJitterBuffer, () -> Void, (Double) -> Void) {
        let buf = AdvancedJitterBuffer()
        var nowMs: Double = 1_000_000
        buf.clock = { nowMs }
        return (buf, { nowMs += 20 }, { nowMs += $0 })
    }

    private func silence() -> Data { Data(repeating: 0, count: 640) }

    /// Feeds `count` packets 20 ms apart, injecting a stall every `burstEvery`
    /// arrivals. Mirrors a relayed link: steady pacing punctuated by a TCP-ish
    /// stall, then the backlog drains in a clump.
    ///
    /// `seq` is `inout` deliberately. The first version restarted at 0 on every
    /// call, so any test that drove the buffer twice replayed sequence numbers
    /// the consumer had already passed. Those arrive LATE, which fires
    /// `recordLateArrival` — a different expansion path entirely. Two tests went
    /// green off that path while proving nothing about the peak detector they
    /// were written to cover.
    private func drive(
        _ buf: AdvancedJitterBuffer,
        seq: inout UInt64,
        advance: (Double) -> Void,
        count: Int,
        burstEvery: Int,
        stallMs: Double
    ) {
        for i in 0..<count {
            if burstEvery > 0 && i > 0 && i % burstEvery == 0 {
                advance(stallMs)
            } else {
                advance(20)
            }
            _ = buf.addPacket(sequenceNumber: seq, audioData: silence())
            seq += 1
        }
    }

    // MARK: - The blindness itself

    func testSmoothedJitterStaysLowAcrossTheBurstsThatBrokeTheCall() {
        let (buf, _, advance) = makeBuffer()
        AdvancedJitterBuffer.burstDepthEnabled = false
        defer { AdvancedJitterBuffer.burstDepthEnabled = true }
        buf.reset()
        var seq: UInt64 = 0

        drive(buf, seq: &seq, advance: advance, count: 600, burstEvery: 50, stallMs: 300)

        // This is the measured failure, pinned. The link stalls for 300 ms
        // roughly once a second and the smoothed estimator still reports a
        // near-calm number — comfortably under the 4× multiplier's threshold for
        // meaningful expansion.
        XCTAssertLessThan(
            buf.jitterEstimateMs, 40.0,
            "RFC 3550 smoothing is supposed to be blind to these bursts — if this now reads high, the estimator changed and the fix's premise needs rechecking"
        )
    }

    func testDepthStaysAtTheStartingValueWithoutTheFix() {
        let (buf, _, advance) = makeBuffer()
        AdvancedJitterBuffer.burstDepthEnabled = false
        defer { AdvancedJitterBuffer.burstDepthEnabled = true }
        buf.reset()
        var seq: UInt64 = 0

        drive(buf, seq: &seq, advance: advance, count: 600, burstEvery: 50, stallMs: 300)

        // Exactly what the Natalia capture showed: 180 ms, unmoved, while the
        // link demanded ~300.
        XCTAssertEqual(buf.targetDepthMs, 180, "reproduces the captured behaviour")
    }

    // MARK: - The fix

    func testBufferExpandsToCoverAPersistentlyBurstyLink() {
        let (buf, _, advance) = makeBuffer()
        AdvancedJitterBuffer.burstDepthEnabled = true
        buf.reset()
        var seq: UInt64 = 0

        drive(buf, seq: &seq, advance: advance, count: 600, burstEvery: 50, stallMs: 300)

        // A 300 ms stall drains 280 ms of depth. Anything shallower than that
        // underruns, which is the chop. Assert it actually covers the gap rather
        // than merely "grew a bit".
        XCTAssertGreaterThanOrEqual(
            buf.targetDepthMs, 280,
            "must be deep enough to ride out the stall it measured, or the chop remains"
        )
    }

    func testACleanLinkIsLeftShallow() {
        let (buf, _, advance) = makeBuffer()
        AdvancedJitterBuffer.burstDepthEnabled = true
        buf.reset()
        var seq: UInt64 = 0

        // Perfectly paced arrivals — a good Wi-Fi call.
        drive(buf, seq: &seq, advance: advance, count: 600, burstEvery: 0, stallMs: 0)

        // Depth is latency. The fix must not tax calls that were already fine;
        // this is the regression that would show up as "calls feel delayed now".
        XCTAssertLessThanOrEqual(
            buf.targetDepthMs, 180,
            "a clean link must never be pushed deeper than the starting depth"
        )
    }

    func testOneIsolatedStallDoesNotPinTheBufferDeep() {
        let (buf, _, advance) = makeBuffer()
        AdvancedJitterBuffer.burstDepthEnabled = true
        buf.reset()
        var seq: UInt64 = 0

        // A single 500 ms hiccup in an otherwise clean call — a lift, a handover.
        drive(buf, seq: &seq, advance: advance, count: 40, burstEvery: 0, stallMs: 0)
        advance(500)
        _ = buf.addPacket(sequenceNumber: seq, audioData: silence())
        seq += 1
        drive(buf, seq: &seq, advance: advance, count: 400, burstEvery: 0, stallMs: 0)

        // Expanding on the first peak would hold ~540 ms for the rest of the call
        // and make every later word late. Requiring TWO live peaks is what keeps
        // one hiccup from costing the whole conversation its latency.
        XCTAssertLessThanOrEqual(
            buf.targetDepthMs, 200,
            "a lone peak is not evidence of a bursty link and must not expand the buffer"
        )
    }

    func testShrinkCannotUndoTheBurstExpansionWhileBurstsContinue() {
        let (buf, _, advance) = makeBuffer()
        AdvancedJitterBuffer.burstDepthEnabled = true
        buf.reset()
        var seq: UInt64 = 0

        drive(buf, seq: &seq, advance: advance, count: 600, burstEvery: 50, stallMs: 300)
        let expanded = buf.targetDepthMs
        XCTAssertGreaterThanOrEqual(expanded, 280)

        // Keep the same bursty profile running. The shrink path is gated on the
        // smoothed mean, which reads "calm" here — so without an explicit floor
        // it would walk the depth back down to 80 ms while the link is still
        // stalling for 300 ms. That regression is silent and only audible on a
        // real call, so pin it.
        drive(buf, seq: &seq, advance: advance, count: 900, burstEvery: 50, stallMs: 300)

        XCTAssertGreaterThanOrEqual(
            buf.targetDepthMs, 280,
            "shrink is gated on the same blind mean; the gap floor must hold the depth"
        )
    }

    func testDepthRecoversAfterTheLinkGenuinelyCalms() {
        let (buf, _, advance) = makeBuffer()
        AdvancedJitterBuffer.burstDepthEnabled = true
        buf.reset()
        var seq: UInt64 = 0

        drive(buf, seq: &seq, advance: advance, count: 600, burstEvery: 50, stallMs: 300)
        XCTAssertGreaterThanOrEqual(buf.targetDepthMs, 280)

        // The floor must be a floor, not a ratchet. Once the bursts stop, the
        // window ages out and the added latency has to be given back — otherwise
        // one bad minute degrades the rest of the call.
        drive(buf, seq: &seq, advance: advance, count: 3000, burstEvery: 0, stallMs: 0)

        XCTAssertLessThan(
            buf.targetDepthMs, 280,
            "a link that calmed down must get its latency back"
        )
    }

    func testResetClearsTheGapWindow() {
        let (buf, _, advance) = makeBuffer()
        AdvancedJitterBuffer.burstDepthEnabled = true
        buf.reset()
        var seq: UInt64 = 0

        drive(buf, seq: &seq, advance: advance, count: 600, burstEvery: 50, stallMs: 300)
        XCTAssertGreaterThan(buf.gapBasedTargetMs, 0)

        buf.reset()

        // reset() is what runs between calls. A stale window would carry one
        // caller's bad cellular link into the next, unrelated conversation.
        XCTAssertEqual(buf.gapBasedTargetMs, 0)
        XCTAssertEqual(buf.targetDepthMs, 180)
    }
}
