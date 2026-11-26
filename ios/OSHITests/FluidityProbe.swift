//
//  FluidityProbe.swift
//  OSHITests
//
//  __CALL_FLUIDITY_BENCH_2026_11_21__ SWIFT PORT of the bench's `FluidityProbe.kt` and
//  `FluidityReference.kt` (desktop + Android). The test signal a bench "speaks" and the
//  analysis of what came out of the far speaker. Implementation-independent on purpose: it
//  reads only PCM in and PCM out with their clocks, so the same numbers mean the same thing
//  on the old receive chain and the new one, on iOS, desktop and Android.
//
//  Signal: voiced bursts (a 140 Hz harmonic complex with a 4 Hz syllable envelope) of
//  `burstMs` every `periodMs`, separated by −60 dBFS noise.
//
//  Metrics:
//   - mouth-to-ear latency per burst: output onset − onset of the latest burst sent ≥ 20 ms
//     before it (valid below one period, 800 ms);
//   - gap ratio: 5 ms steps INSIDE a burst (30 ms away from its edges) whose 20 ms window
//     came out at < 30 % of the level the codec alone produces there (the `reference`) —
//     what a listener hears as a drop-out;
//   - gap events per minute: runs of ≥ 10 ms of such blocks;
//   - drift: latency of the last bursts − latency of the first;
//   - outage recovery: gap ratio of the audio SENT in the 2 s after the network came back,
//     and how much later than before the outage it is heard.
//

import Foundation
#if canImport(OSHI)
@testable import OSHI
#endif

enum FluidityProbe {
    static let rate = 48_000
    static let burstMs = 480
    static let periodMs = 800
    private static let block = 240 // 5 ms
    private static let edgeMs = 30
    private static let win = 960

    /// Sample `i` of the signal (i counted from the start of capture).
    static func sample(_ i: Int64, seed: Int = 0) -> Int16 {
        let tMs = Double(i) * 1000.0 / Double(rate)
        let inPeriod = tMs.truncatingRemainder(dividingBy: Double(periodMs))
        if inPeriod >= Double(burstMs) {
            // −60 dBFS noise, deterministic (Kotlin: ((i * 2654435761L + seed * 97L) ushr 7) and 0xFF).
            let x = UInt64(bitPattern: i &* 2654435761 &+ Int64(seed) &* 97)
            let h = Int64((x >> 7) & 0xFF)
            return Int16((h - 128) / 4)
        }
        let t = Double(i) / Double(rate)
        let f0: Double = 140.0 + 8 * sin(2 * Double.pi * 0.7 * t) + Double(seed * 13)
        var v = 0.0
        for k in 1...10 {
            let ph: Double = 2 * Double.pi * f0 * Double(k) * t + Double(k)
            v += sin(ph) / Double(k)
        }
        let env: Double = 0.6 + 0.4 * sin(2 * Double.pi * 4.0 * t)
        let edge = min(1.0, inPeriod / 10.0, (Double(burstMs) - inPeriod) / 10.0)
        return Int16(clampToInt16(kotlinToInt(4200 * v * env * edge)))
    }

    /// Kotlin `Double.toInt()`: truncation, saturating, NaN → 0.
    static func kotlinToInt(_ d: Double) -> Int {
        if d.isNaN { return 0 }
        if d >= Double(Int32.max) { return Int(Int32.max) }
        if d <= Double(Int32.min) { return Int(Int32.min) }
        return Int(d)
    }
    static func clampToInt16(_ v: Int) -> Int { max(-32768, min(32767, v)) }

    static func frame(_ index: Int64, samples: Int = 960, seed: Int = 0) -> [Int16] {
        (0..<samples).map { sample(index * Int64(samples) + Int64($0), seed: seed) }
    }

    private static func rms(_ a: [Int16], _ from: Int, _ n: Int) -> Double {
        let end = min(a.count, from + n)
        if end <= from { return 0 }
        var s = 0.0
        for i in from..<end { let v = Double(a[i]); s += v * v }
        return (s / Double(end - from)).squareRoot()
    }

    struct Result {
        let latencies: [(Double, Double)] // (input onset ms, latency ms)
        let gapBlocks: Int
        let expectedBlocks: Int
        let gapEvents: Int
        let durationMs: Double
        let postOutageGapRatio: Double
        let postOutageExtraMs: Double

        var gapRatio: Double { expectedBlocks == 0 ? 0 : Double(gapBlocks) / Double(expectedBlocks) }
        var gapsPerMin: Double { Double(gapEvents) * 60_000.0 / durationMs }
        private func med(_ v: [Double]) -> Double { v.isEmpty ? .nan : v.sorted()[v.count / 2] }
        var medianLatency: Double { med(latencies.map { $0.1 }) }
        var p95Latency: Double {
            let s = latencies.map { $0.1 }.sorted()
            return s.isEmpty ? .nan : s[min(s.count - 1, Int(Double(s.count) * 0.95))]
        }
        /// Median of the last 5 bursts − median of bursts 2..6.
        var driftMs: Double {
            latencies.count < 10 ? .nan
                : med(latencies.suffix(5).map { $0.1 }) - med(latencies[1..<6].map { $0.1 })
        }
    }

    static func analyze(output: [Int16], outputStartMs: Double, inputStartMs: Double, inputSamples: Int64,
                        inputRate: Double = Double(rate), seed: Int = 0,
                        outageAtMs: Int64 = -1, outageMs: Int64 = 0, settleMs: Double = 1000.0,
                        reference: [Int16]? = nil) -> Result {
        let outMs = { (j: Int) -> Double in outputStartMs + Double(j) * 1000.0 / Double(rate) }
        let inMs = { (i: Int64) -> Double in inputStartMs + Double(i) * 1000.0 / inputRate }
        // Input bursts (in input sample index).
        var bursts: [Range<Int64>] = []
        let perSamples = Int64(periodMs * rate / 1000)
        let burstSamples = Int64(burstMs * rate / 1000)
        var b: Int64 = 0
        while b + burstSamples <= inputSamples { bursts.append(b..<(b + burstSamples)); b += perSamples }
        // Output onsets: a block above threshold after ≥ 80 ms below half of it.
        let thr = 600.0
        var onsets: [Double] = []
        var quiet = 0
        var j = 0
        while j + block <= output.count {
            let r = rms(output, j, block)
            if r >= thr { if quiet >= 16 { onsets.append(outMs(j)) }; quiet = 0 } else if r < thr / 2 { quiet += 1 }
            j += block
        }
        // Match each onset to the LAST burst that started ≥ 20 ms before it.
        var burstLat = [Double?](repeating: nil, count: bursts.count)
        for o in onsets {
            guard let k = bursts.indices.last(where: { o - inMs(bursts[$0].lowerBound) >= 20.0 }) else { continue }
            if burstLat[k] == nil { burstLat[k] = o - inMs(bursts[k].lowerBound) }
        }
        // Fill unmatched bursts with the neighbours' latency.
        let known = burstLat.indices.filter { burstLat[$0] != nil }
        func latFor(_ k: Int) -> Double? {
            if let l = burstLat[k] { return l }
            if let before = known.last(where: { $0 < k }) { return burstLat[before] }
            if let after = known.first(where: { $0 > k }) { return burstLat[after] }
            return nil
        }
        let input = { (i: Int64) -> Int16 in
            if let reference { return (i >= 0 && i < Int64(reference.count)) ? reference[Int(i)] : 0 }
            return sample(i, seed: seed)
        }
        var gapBlocks = 0, expected = 0, events = 0, postGap = 0, postExp = 0
        let edge = Int64(edgeMs * rate / 1000)
        for (k, r) in bursts.enumerated() {
            let sentMs = inMs(r.lowerBound)
            if sentMs - inputStartMs < settleMs { continue }
            let inOutage = outageAtMs >= 0 && sentMs + Double(burstMs) > inputStartMs + Double(outageAtMs)
                && sentMs < inputStartMs + Double(outageAtMs + outageMs)
            if inOutage { continue }
            guard let lat = latFor(k) else { continue }
            var run = 0
            var i = r.lowerBound + edge
            let last = r.upperBound - 1
            while i + Int64(block) <= last - edge {
                let tOut = inMs(i) + lat
                let jj = Int((tOut - outputStartMs) * Double(rate) / 1000.0)
                if jj < 0 || jj + block > output.count { i += Int64(block); continue }
                var e = 0.0
                for q in (-win / 2)..<(win / 2) { let v = Double(input(i + Int64(q))); e += v * v }
                let inR = (e / Double(win)).squareRoot()
                if inR < 300 { i += Int64(block); continue }                  // the codec itself made a dip here
                var outR = 0.0
                for sh in [-480, 0, 480] { outR = max(outR, rms(output, max(0, jj + sh - win / 2), win)) }
                expected += 1
                let post = outageAtMs >= 0 && sentMs >= inputStartMs + Double(outageAtMs + outageMs)
                    && sentMs < inputStartMs + Double(outageAtMs + outageMs) + 2000
                if post { postExp += 1 }
                if outR < 0.3 * inR {
                    gapBlocks += 1; run += 1
                    if post { postGap += 1 }
                    if run == 2 { events += 1 }
                } else { run = 0 }
                i += Int64(block)
            }
        }
        let lats: [(Double, Double)] = bursts.indices
            .filter { burstLat[$0] != nil && inMs(bursts[$0].lowerBound) - inputStartMs >= settleMs }
            .map { (inMs(bursts[$0].lowerBound) - inputStartMs, burstLat[$0]!) }
        var extra = Double.nan
        if outageAtMs >= 0 {
            let end = Double(outageAtMs + outageMs)
            let pre = lats.filter { $0.0 < Double(outageAtMs) && $0.0 > Double(outageAtMs) - 4000 }.map { $0.1 }.sorted()
            let post = lats.filter { $0.0 >= end && $0.0 < end + 3000 }.map { $0.1 }.sorted()
            if !pre.isEmpty && !post.isEmpty { extra = post[post.count / 2] - pre[pre.count / 2] }
        }
        return Result(latencies: lats, gapBlocks: gapBlocks, expectedBlocks: expected, gapEvents: events,
                      durationMs: Double(inputSamples) * 1000.0 / inputRate,
                      postOutageGapRatio: postExp == 0 ? .nan : Double(postGap) / Double(postExp),
                      postOutageExtraMs: extra)
    }
}

/// Kotlin `FluidityReference`: the codec-only rendering of `frames` of the probe signal,
/// encoded and decoded in order with nothing lost, shifted back by the decoder's output delay
/// so it lines up with the input (found by cross-correlating the first burst). Uses only the
/// pre-existing `OpusCallEncoder` / `OpusCallDecoder`.
enum FluidityReference {
    static func reference(frames: Int64, redundancy: Int = OpusWire.maxRedundant) -> [Int16] {
        let enc = OpusCallEncoder(bitrate: 32_000, complexity: 8, redundancy: redundancy)!
        let dec = OpusCallDecoder()!
        var out = [Int16](repeating: 0, count: Int(frames) * 960)
        var o = 0
        for k in 0..<frames {
            for p in enc.encode(FluidityProbe.frame(k)) {
                for f in dec.decode(p) {
                    let n = min(f.pcm.count, out.count - o)
                    if n > 0 { for x in 0..<n { out[o + x] = f.pcm[x] } }
                    o += f.pcm.count
                }
            }
        }
        var best = 0, bestC = -1.0
        let ref = (0..<9600).map { Double(FluidityProbe.sample(Int64($0))) }
        for lag in 0...960 {
            var c = 0.0
            for i in 0..<9600 { c += ref[i] * Double(out[i + lag]) }
            if c > bestC { bestC = c; best = lag }
        }
        return Array(out[best...])
    }

    private static var cache: [Int64: [Int16]] = [:]
    private static let cacheLock = NSLock()
    static func cachedReference(frames: Int64) -> [Int16] {
        cacheLock.lock(); defer { cacheLock.unlock() }
        if let r = cache[frames] { return r }
        let r = reference(frames: frames)
        cache[frames] = r
        return r
    }

    static func fmt(_ v: Double, _ d: Int = 0) -> String { v.isNaN ? "–" : String(format: "%.\(d)f", v) }
}
