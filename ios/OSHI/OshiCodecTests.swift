//
//  OshiCodecTests.swift
//  OSHI
//
//  Unit test for the OshiCodec Swift wrapper: round-trips 1 second of
//  a 440 Hz sine at 48 kHz (50 frames of 960 samples) through encode
//  then decode, and asserts RMS error is under 30 % of input peak.
//
//  NOTE: The OSHI.xcodeproj currently does not expose a unit-test target.
//  This file lives in OSHI/ so it can be picked up if/when a test target
//  is added, and is excluded from the main app target to avoid dragging
//  XCTest into the shipping binary.
//

#if canImport(XCTest)
import XCTest

final class OshiCodecTests: XCTestCase {

    /// Generate 1 s of a 440 Hz sine at 48 kHz mono as int16 PCM.
    private func generateSine(
        seconds: Double = 1.0,
        frequency: Double = 440.0,
        sampleRate: Double = 48_000.0,
        amplitude: Double = 0.5
    ) -> [Int16] {
        let sampleCount = Int(seconds * sampleRate)
        var pcm = [Int16](repeating: 0, count: sampleCount)
        let twoPiFOverFs = 2.0 * .pi * frequency / sampleRate
        let scale = amplitude * Double(Int16.max)
        for n in 0..<sampleCount {
            let v = scale * sin(twoPiFOverFs * Double(n))
            pcm[n] = Int16(max(-32768.0, min(32767.0, v.rounded())))
        }
        return pcm
    }

    func testRoundTripSine1SecondUnder30PctRMS() {
        let codec = OshiCodec(sampleRate: OshiCodec.sampleRate, channels: 1)
        codec.setBitrate(48_000) // BR_48K
        codec.reset()

        let frames = 50
        let samplesPerFrame = OshiCodec.frameSize
        let totalSamples = frames * samplesPerFrame

        let input = generateSine()
        XCTAssertEqual(input.count, totalSamples, "expected 48 000 samples for 1 s")

        // Encode in frame-size batches (the wrapper supports any
        // multiple of frameSize but the spec asks for 50 frames of
        // 960 samples, so we exercise that path explicitly).
        var encoded = Data()
        input.withUnsafeBufferPointer { buf in
            guard let base = buf.baseAddress else { return }
            for f in 0..<frames {
                let framePtr = base.advanced(by: f * samplesPerFrame)
                let d = codec.encode(pcm: framePtr, samples: samplesPerFrame)
                XCTAssertFalse(d.isEmpty, "frame \(f) encoded to zero bytes")
                encoded.append(d)
            }
        }

        // Decode in one shot — the wrapper walks concatenated frames
        // using the header's bitrate-level field.
        let decoded = codec.decode(encoded)
        XCTAssertEqual(decoded.count, totalSamples,
                       "decoder must produce exactly totalSamples int16 samples")

        // RMS error vs input, compared against input peak.
        var peak: Double = 0
        var sqErr: Double = 0
        for n in 0..<totalSamples {
            let x = Double(input[n])
            let y = Double(decoded[n])
            let ax = abs(x)
            if ax > peak { peak = ax }
            let e = y - x
            sqErr += e * e
        }
        let rms = sqrt(sqErr / Double(totalSamples))
        // Sanity: peak of a 0.5-amplitude sine is ~16384.
        XCTAssertGreaterThan(peak, 1000.0, "peak suspiciously low, check input generator")

        let ratio = rms / peak
        XCTAssertLessThan(ratio, 0.30,
                          "RMS error \(rms) / peak \(peak) = \(ratio), expected < 0.30")
    }
}
#endif
