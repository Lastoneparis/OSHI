//
//  NetworkImpairment.swift
//  OSHITests
//
//  __CALL_FLUIDITY_BENCH_2026_11_21__ SWIFT PORT of the bench's `NetworkImpairment.kt`
//  (OSHI-Desktop `src/test/.../call/bench`, OSHI-Android `src/test/.../audio/bench`).
//
//  A seeded, reproducible bad network. One instance per direction. `route` is asked once per
//  packet, in send order, with the time it was sent, and answers the time it is delivered or
//  nil for a loss. Everything random comes from ONE `JavaRandom` with a fixed seed — a
//  bit-exact copy of `java.util.Random` — so a scenario is the SAME network here as in the
//  Kotlin benches, packet for packet, and the three platforms' tables compare like for like.
//

import Foundation

/// `java.util.Random`, bit for bit (48-bit LCG; nextDouble / nextInt(bound) as in the JDK).
struct JavaRandom {
    private var seed: UInt64
    private static let mask: UInt64 = (1 << 48) - 1

    init(_ seed: Int64) { self.seed = (UInt64(bitPattern: seed) ^ 0x5DEECE66D) & Self.mask }

    private mutating func next(_ bits: Int) -> Int32 {
        seed = (seed &* 0x5DEECE66D &+ 0xB) & Self.mask
        return Int32(truncatingIfNeeded: seed >> UInt64(48 - bits))
    }

    mutating func nextDouble() -> Double {
        Double((Int64(next(26)) << 27) + Int64(next(27))) * 0x1.0p-53
    }

    mutating func nextInt(_ bound: Int32) -> Int32 {
        precondition(bound > 0)
        var r = next(31)
        let m = bound &- 1
        if bound & m == 0 {
            r = Int32(truncatingIfNeeded: (Int64(bound) * Int64(r)) >> 31)
        } else {
            var u = r
            r = u % bound
            while u &- r &+ m < 0 { u = next(31); r = u % bound }
        }
        return r
    }
}

final class NetworkImpairment {

    /// - lossPct: independent random loss, percent.
    /// - burstLossPct: average loss of a Gilbert–Elliott channel, percent (0 = off);
    ///   `burstLen` is the mean number of packets lost in a row in the bad state.
    /// - latencyMs: one-way base delay.
    /// - jitterMs: delay added on top, exponential-ish, capped at 3× its mean (packets may
    ///   overtake each other).
    /// - reorderPct: share of packets held back `reorderMs` extra.
    /// - outageAtMs / outageMs: a total blackout; with `outageHold` the packets are HELD and
    ///   released in one burst when it ends (a stalled radio, not a dead one).
    /// - clockSkewPpm: the SENDER's clock runs this much fast.
    struct Spec {
        let name: String
        var lossPct = 0.0
        var burstLossPct = 0.0
        var burstLen = 3.0
        var latencyMs = 10.0
        var jitterMs = 0.0
        var reorderPct = 0.0
        var reorderMs = 40.0
        var outageAtMs: Int64 = -1
        var outageMs: Int64 = 0
        var outageHold = false
        var clockSkewPpm = 0.0
    }

    let spec: Spec
    private var rng: JavaRandom
    private var bad = false
    private(set) var sent: Int64 = 0
    private(set) var lost: Int64 = 0

    init(_ spec: Spec, seed: Int64) { self.spec = spec; rng = JavaRandom(seed) }

    func route(_ sentAtMs: Double) -> Double? {
        sent += 1
        let t = sentAtMs
        if spec.outageMs > 0 && spec.outageAtMs >= 0 && t >= Double(spec.outageAtMs) && t < Double(spec.outageAtMs + spec.outageMs) {
            if !spec.outageHold { lost += 1; return nil }
            return Double(spec.outageAtMs + spec.outageMs) + spec.latencyMs + (t - Double(spec.outageAtMs)) * 0.01
        }
        if spec.lossPct > 0 && rng.nextDouble() * 100 < spec.lossPct { lost += 1; return nil }
        if spec.burstLossPct > 0 {
            // Gilbert–Elliott: bad → good with p = 1/burstLen; good → bad chosen so the
            // stationary loss equals burstLossPct.
            let pBg = 1.0 / spec.burstLen
            let pi = spec.burstLossPct / 100.0
            let pGb = pi * pBg / (1 - pi)
            bad = bad ? rng.nextDouble() >= pBg : rng.nextDouble() < pGb
            if bad { lost += 1; return nil }
        }
        var d = spec.latencyMs
        if spec.jitterMs > 0 { d += min(-log(1 - rng.nextDouble()) * spec.jitterMs, 3 * spec.jitterMs) }
        if spec.reorderPct > 0 && rng.nextDouble() * 100 < spec.reorderPct { d += spec.reorderMs }
        return t + d
    }

    /// The scenarios of the task, same order everywhere. Durations assume a 20 s run.
    static let scenarios: [Spec] = [
        Spec(name: "clean"),
        Spec(name: "loss 2%", lossPct: 2.0),
        Spec(name: "loss 5%", lossPct: 5.0),
        Spec(name: "loss 10%", lossPct: 10.0),
        Spec(name: "bursty 5% (len 4)", burstLossPct: 5.0, burstLen: 4.0),
        Spec(name: "jitter 30ms", latencyMs: 40.0, jitterMs: 30.0),
        Spec(name: "jitter 100ms", latencyMs: 40.0, jitterMs: 100.0),
        Spec(name: "200ms+j30+2%", lossPct: 2.0, latencyMs: 200.0, jitterMs: 30.0),
        Spec(name: "reorder 10%/40ms", reorderPct: 10.0, reorderMs: 40.0),
        Spec(name: "outage 3s @8s", outageAtMs: 8_000, outageMs: 3_000),
        Spec(name: "stall 1.5s @8s", outageAtMs: 8_000, outageMs: 1_500, outageHold: true),
        Spec(name: "skew +500ppm", jitterMs: 5.0, clockSkewPpm: 500.0),
    ]
}
