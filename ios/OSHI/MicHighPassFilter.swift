//
//  MicHighPassFilter.swift
//  OSHI — 100 Hz high-pass on the microphone capture path.
//
//  Speech intelligibility lives roughly 300 Hz–3.4 kHz; almost nothing below
//  100 Hz is voice. What IS down there is handling noise, footsteps, wind, table
//  thumps, car rumble and mains hum — and it is frequently the loudest thing in
//  the frame.
//
//  That matters beyond taste, because AAC-ELD allocates bits by energy. Rumble it
//  will never reproduce usefully still takes bits away from the band that carries
//  words, and the effect is worst exactly where it hurts most: at the 48 kbps
//  floor a thin link falls back to. Removing it is one of the few clarity wins
//  that costs no bandwidth and no latency.
//
//  Extracted from VoiceCallManager so it can actually be MEASURED. A filter whose
//  only evidence is "the code looks like the cookbook formula" is a filter nobody
//  has checked — a wrong sign or a swapped coefficient still compiles, still runs,
//  and still sounds like something. MicHighPassFilterTests feeds it real sine
//  waves and asserts the attenuation at each frequency.
//
//  RBJ audio-EQ-cookbook biquad, Q = 1/√2 (Butterworth: maximally flat, no
//  resonant peak at the corner). Direct Form II transposed, per-channel state.
//

import Foundation

struct MicHighPassFilter {
    private let cutoffHz: Double
    private var b0: Float = 1, b1: Float = 0, b2: Float = 0, a1: Float = 0, a2: Float = 0
    private var state: [(z1: Float, z2: Float)] = []
    private var sampleRate: Double = 0

    init(cutoffHz: Double) { self.cutoffHz = cutoffHz }

    /// Recomputes coefficients when the rate changes, and sizes per-channel state.
    mutating func prepare(sampleRate rate: Double, channels: Int) {
        if rate > 0, rate != sampleRate {
            sampleRate = rate
            let w0 = 2.0 * Double.pi * cutoffHz / rate
            let cosW0 = cos(w0), sinW0 = sin(w0)
            let alpha = sinW0 / (2.0 * 0.7071067811865476)
            let a0 = 1 + alpha
            b0 = Float((1 + cosW0) / 2 / a0)
            b1 = Float(-(1 + cosW0) / a0)
            b2 = Float((1 + cosW0) / 2 / a0)
            a1 = Float(-2 * cosW0 / a0)
            a2 = Float((1 - alpha) / a0)
            state.removeAll()
        }
        if state.count != channels {
            state = Array(repeating: (0, 0), count: max(0, channels))
        }
    }

    /// Filters `frames` samples in place.
    ///
    /// State is carried ACROSS calls deliberately. Resetting per 20 ms buffer
    /// would put a discontinuity at every frame boundary — audible as a periodic
    /// tick at 50 Hz, which is worse than the rumble being removed.
    mutating func process(_ samples: UnsafeMutablePointer<Float>, frames: Int, channel: Int) {
        guard channel >= 0, channel < state.count, frames > 0 else { return }
        var z1 = state[channel].z1, z2 = state[channel].z2
        for n in 0..<frames {
            let x = samples[n]
            let y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            samples[n] = y
        }
        state[channel] = (z1, z2)
    }

    /// Test seam: filter an array and hand it back.
    mutating func processed(_ input: [Float], sampleRate rate: Double) -> [Float] {
        var out = input
        prepare(sampleRate: rate, channels: 1)
        out.withUnsafeMutableBufferPointer { buf in
            if let base = buf.baseAddress { process(base, frames: buf.count, channel: 0) }
        }
        return out
    }
}
