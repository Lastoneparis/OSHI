//
//  MicHighPassFilterTests.swift
//  OSHITests
//
//  Measures the filter instead of trusting it. A biquad with a wrong sign or two
//  swapped coefficients still compiles, still runs, and still produces plausible
//  audio — it just does the wrong thing to the voice. So these feed real sine
//  waves through and assert the attenuation in dB at each frequency.
//
//  The numbers come from what the filter is FOR: kill rumble, keep speech.
//

import XCTest
@testable import OSHI

final class MicHighPassFilterTests: XCTestCase {

    private let rate: Double = 48_000

    /// One second of a sine at `hz`, amplitude 1.
    private func sine(_ hz: Double, seconds: Double = 1.0) -> [Float] {
        let n = Int(rate * seconds)
        return (0..<n).map { Float(sin(2 * Double.pi * hz * Double($0) / rate)) }
    }

    private func rms(_ x: [Float]) -> Double {
        guard !x.isEmpty else { return 0 }
        let sum = x.reduce(0.0) { $0 + Double($1) * Double($1) }
        return (sum / Double(x.count)).squareRoot()
    }

    /// Gain in dB at `hz`. The first 20 ms are dropped: a biquad starts from zero
    /// state and its transient would otherwise be measured as attenuation.
    private func gainDb(at hz: Double) -> Double {
        var f = MicHighPassFilter(cutoffHz: 100)
        let input = sine(hz)
        let output = f.processed(input, sampleRate: rate)
        let skip = Int(rate * 0.02)
        let inRms = rms(Array(input[skip...]))
        let outRms = rms(Array(output[skip...]))
        guard inRms > 0 else { return 0 }
        return 20 * log10(outRms / inRms)
    }

    // MARK: - Speech must survive untouched

    func testSpeechBandIsEssentiallyUnchanged() {
        // The band that carries intelligibility. If the filter touches this, it is
        // making calls worse, not clearer — which is the only failure that matters.
        for hz in [300.0, 500, 1000, 2000, 3400] {
            let g = gainDb(at: hz)
            XCTAssertGreaterThan(g, -1.0,
                                 "\(Int(hz)) Hz lost \(String(format: "%.2f", -g)) dB — speech must pass")
            XCTAssertLessThan(g, 0.5,
                              "\(Int(hz)) Hz was BOOSTED by \(String(format: "%.2f", g)) dB — a resonant peak means wrong Q")
        }
    }

    func testTheLowestVoiceFundamentalIsNotGutted() {
        // A deep male voice bottoms out around 85 Hz, below the corner. It should
        // be reduced, not removed: the harmonics carry the words, but taking the
        // fundamental to nothing makes a voice sound thin and telephone-y.
        let g = gainDb(at: 85)
        XCTAssertLessThan(g, -1.0, "should be attenuated at all")
        XCTAssertGreaterThan(g, -12.0,
                             "85 Hz lost \(String(format: "%.1f", -g)) dB — too aggressive for a low voice")
    }

    // MARK: - Rumble must go

    func testRumbleIsAttenuated() {
        // 50 Hz mains hum, and the 20–30 Hz band where handling noise and footsteps
        // live. These are the bits AAC-ELD would otherwise spend on nothing.
        XCTAssertLessThan(gainDb(at: 50), -10.0, "50 Hz mains hum must drop by >10 dB")
        XCTAssertLessThan(gainDb(at: 30), -18.0, "30 Hz rumble must drop by >18 dB")
        XCTAssertLessThan(gainDb(at: 20), -22.0, "20 Hz must be gone")
    }

    func testCornerIsRoughlyMinus3dBAt100Hz() {
        // Defines where the filter actually sits. A corner that has drifted means
        // the coefficients no longer match the stated 100 Hz.
        let g = gainDb(at: 100)
        XCTAssertEqual(g, -3.0, accuracy: 1.5,
                       "corner measured at \(String(format: "%.2f", g)) dB")
    }

    // MARK: - It must not damage the signal

    func testFilterIsStableOverALongRun() {
        // A biquad with the sign of a feedback coefficient wrong is stable for a
        // few samples and then diverges. Ten seconds catches that; one 20 ms
        // buffer would not.
        var f = MicHighPassFilter(cutoffHz: 100)
        let out = f.processed(sine(1000, seconds: 10), sampleRate: rate)
        let peak = out.map { abs($0) }.max() ?? 0
        XCTAssertLessThan(peak, 2.0, "output peak \(peak) — filter is diverging")
        XCTAssertFalse(out.contains { !$0.isFinite }, "produced NaN/Inf")
    }

    func testStateCarriesAcrossBuffersWithoutClicks() {
        // Real audio arrives in 20 ms frames. Resetting state per frame would put
        // a step discontinuity at every boundary — a 50 Hz tick, worse than the
        // rumble being removed. Filtering in chunks must match filtering whole.
        var whole = MicHighPassFilter(cutoffHz: 100)
        let input = sine(220, seconds: 0.5)
        let reference = whole.processed(input, sampleRate: rate)

        var chunked = MicHighPassFilter(cutoffHz: 100)
        chunked.prepare(sampleRate: rate, channels: 1)
        var pieces: [Float] = []
        let frame = Int(rate * 0.02)
        var i = 0
        while i < input.count {
            var slice = Array(input[i..<min(i + frame, input.count)])
            slice.withUnsafeMutableBufferPointer { buf in
                if let base = buf.baseAddress {
                    chunked.process(base, frames: buf.count, channel: 0)
                }
            }
            pieces.append(contentsOf: slice)
            i += frame
        }

        XCTAssertEqual(pieces.count, reference.count)
        let maxDelta = zip(pieces, reference).map { abs($0 - $1) }.max() ?? 0
        XCTAssertLessThan(maxDelta, 1e-4,
                          "chunked output diverges by \(maxDelta) — state is not carrying across frames")
    }

    func testSilenceStaysSilent() {
        var f = MicHighPassFilter(cutoffHz: 100)
        let out = f.processed([Float](repeating: 0, count: 4800), sampleRate: rate)
        XCTAssertEqual(out.max() ?? 0, 0)
        XCTAssertEqual(out.min() ?? 0, 0)
    }

    func testDCOffsetIsRemoved() {
        // A capture chain with DC bias wastes headroom and encoder bits on a
        // constant. A high-pass should take it to zero.
        var f = MicHighPassFilter(cutoffHz: 100)
        let out = f.processed([Float](repeating: 0.5, count: Int(rate)), sampleRate: rate)
        let tail = Array(out[(out.count - 1000)...])
        XCTAssertLessThan(rms(tail), 0.01, "DC survived the high-pass")
    }
}
