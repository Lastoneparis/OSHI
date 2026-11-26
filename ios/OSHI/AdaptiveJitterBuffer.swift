//
//  AdaptiveJitterBuffer.swift
//  OSHI
//
//  __CALL_FLUIDITY_2026_11_21__ SWIFT PORT of `AdaptiveJitterBuffer.kt` (OSHI-Desktop
//  `call/media/`, OSHI-Android `service/audio/` — the two Kotlin copies are identical).
//  The logic is pure and must behave the same on the three platforms: keep this file in
//  step with the Kotlin ones, rule for rule and constant for constant. The tests
//  (`AdaptiveJitterBufferTests`, `OpusPlayoutTests`) are ports of the Kotlin tests.
//
//  A clock-driven, adaptive playout buffer (NetEQ's ideas, sized for this app): frames go
//  in keyed by sequence, exactly 20 ms of PCM comes out per `pull()`.
//
//  WHY (measured, `FluiditySimBench`): the receive chain it replaces on iOS
//  (`AdvancedJitterBuffer` drained on every ARRIVAL into AVAudioPlayerNode) decides "late"
//  by a slot clock that keeps ticking while nothing arrives, and plays nothing at all
//  between arrivals — the player queue simply runs dry and the speaker plays hard silence.
//  A packet 30 ms late was concealed and then DISCARDED. Here a slot with no frame and
//  nothing behind it is NOT given up: it is concealed (EXPAND) and the buffer waits for it,
//  so a late frame still plays and the buffer has grown by exactly the lateness the network
//  showed. When frames are queued beyond the target the buffer shrinks back (ACCELERATE): a
//  quiet frame is skipped whole, a voiced one loses one pitch period with a cross-fade, and
//  a backlog far beyond the target (a stalled radio that delivers 1.5 s at once) is cut
//  back in one step.
//
//  Rules, in the order `produce()` applies them:
//   1. Priming: silence until `targetMs` of audio is queued (at the start and after a resync).
//   2. The next frame is here → play it (cross-faded out of concealment if one preceded).
//   3. It is missing and NOTHING later is queued → EXPAND: one concealment frame, the slot
//      does not advance (the frame is late, not lost).
//   4. It is missing and a later frame IS queued → the frames in between are lost; the
//      concealment already played while expanding stands in for as many of them as it
//      lasted (no added delay after an outage), the rest are concealed one per slot, and a
//      hole longer than `jumpFrames` is jumped.
//
//  Target delay: P95 − min of the per-frame relative delay (FIRST arrival of a frame, from
//  whichever packet carried it, − seq × frame; plus frames that arrived after their slot)
//  over the last `windowMs`, clamped to [minTargetMs, maxTargetMs]. Spikes more than
//  `spikeMs` above the median are left out of the estimate.
//
//  Frames are queued ENCODED and decoded at playout by a `FrameDecoder`: for Opus
//  (`OpusPlayoutDecoder`, OshiCodec.swift) a missing frame is concealed by the codec itself —
//  in-band FEC from the next frame when it is queued, Opus PLC otherwise. Codecs without
//  their own concealment (`PcmFrames`) get pitch tiling. After five concealed frames: silence.
//
//  Thread-safety: every public method takes `lock` (receive queues put, the playout clock
//  pulls). The lock is recursive so `OpusPlayoutDecoder.file` can file one packet's frames
//  atomically (the Kotlin `synchronized(jb)`).
//

import Foundation

/// Turns a queued frame into PCM AT PLAYOUT TIME, in playout order.
///
/// WHY decode at playout (measured, jitter 100 ms): decoding on arrival meant a packet that
/// overtook four others made the decoder fill those four with PLC on the spot — and then
/// discard the real ones when they arrived 30 ms later, still in time. Queued encoded, a
/// frame is usable until its slot is played, and the codec's own concealment runs exactly
/// for the slots that are really missing.
protocol JitterFrameDecoder: AnyObject {
    /// One queued frame → PCM; empty if it does not decode (then concealed).
    func decode(_ frame: Data) -> [Int16]
    /// The codec's concealment for one missing frame, given the frame that follows it if
    /// that one is queued (Opus in-band FEC), or nil to use the buffer's own pitch PLC.
    func conceal(next: Data?) -> [Int16]?
    func reset()
}

final class AdaptiveJitterBuffer {
    static let frameSamples = 960
    static let samplesPerMs = 48
    static let minTargetMsDefault = 20
    static let maxTargetMsDefault = 400
    static let windowMs: Int64 = 4_000
    static let spikeMs: Int64 = 350
    /// Added to the measured spread (the 20 ms pull granularity).
    static let targetMarginMs: Int64 = 0
    /// A lost run longer than this (100 ms) is skipped instead of concealed slot by slot.
    static let jumpFrames: Int64 = 5
    /// Older than this behind the playout point: not a late frame, a new stream.
    static let resyncFrames: Int64 = 100
    /// Queue cap (5 s of 20 ms frames): a flood is not worth buffering.
    static let maxFrames = 250
    /// Shrink starts when the queue exceeds the target by this much …
    static let accelAboveMs = 20
    /// … for this long, and stops at target + `accelStopMs`.
    static let accelHoldMs: Int64 = 200
    /// Window of `floorMs`.
    static let floorWindowMs: Int64 = 1_000
    static let accelStopMs = 10
    /// Voiced frames are shortened only after the excess lasted this long (pauses first).
    static let accelForceMs: Int64 = 1_000
    /// Beyond the target by this much: cut the backlog in one step.
    static let flushAboveMs = 300
    /// RMS below this (−40 dBFS) is a pause: safe to skip whole.
    static let quietRms = 330.0
    static let crossfadeLen = 96
    private static let plcGains: [Float] = [1.0, 0.8, 0.6, 0.4, 0.2, 0.0]
    private static let pitchMin = 96   // 500 Hz
    private static let pitchMax = 720  // 67 Hz
    private static let pitchWin = 240

    /// Frames that ARE PCM: Int16 little-endian; generic PLC.
    final class PcmFrames: JitterFrameDecoder {
        static let shared = PcmFrames()
        func decode(_ frame: Data) -> [Int16] {
            let b = [UInt8](frame)
            return (0..<(b.count / 2)).map { Int16(bitPattern: UInt16(b[2 * $0 + 1]) << 8 | UInt16(b[2 * $0])) }
        }
        func conceal(next: Data?) -> [Int16]? { nil }
        func reset() {}
    }

    /// What the last `pull()` was made of.
    enum Kind { case priming, normal, expand, lossConceal, silence }

    /// Monotonic milliseconds (Kotlin: `System.nanoTime() / 1_000_000`).
    static func monotonicMs() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000) }

    private let decoder: JitterFrameDecoder
    private let clock: () -> Int64
    private let minTargetMs: Int
    private let maxTargetMs: Int
    let lock = NSRecursiveLock()

    init(decoder: JitterFrameDecoder = PcmFrames.shared,
         clock: @escaping () -> Int64 = AdaptiveJitterBuffer.monotonicMs,
         minTargetMs: Int = AdaptiveJitterBuffer.minTargetMsDefault,
         maxTargetMs: Int = AdaptiveJitterBuffer.maxTargetMsDefault) {
        self.decoder = decoder
        self.clock = clock
        self.minTargetMs = minTargetMs
        self.maxTargetMs = maxTargetMs
        self.target = minTargetMs
    }

    @inline(__always) private func sync<T>(_ body: () -> T) -> T {
        lock.lock(); defer { lock.unlock() }
        return body()
    }

    /// Kotlin TreeMap<Long, ByteArray>. At most `maxFrames` entries; the ordered queries are
    /// linear scans (50 Hz, ≤ 250 keys).
    private var frames: [Int64: Data] = [:]
    /// The last run of concealment came from the codec (it smooths its own way back out).
    private var codecConcealed = false
    private var nextSeq: Int64 = -1
    /// Highest seq played as a real frame (copies at or below it are redundancy).
    private var lastPlayedReal: Int64 = -1
    private var newestSeq: Int64 = -1
    private var priming = true
    private var expandRun = 0
    private var plcRun = 0
    private var lastReal: [Int16]?
    private var plcPeriod = 0
    private var plcPhase = 0
    private var plcGain: Float = 0
    private var lastFrameSamples = AdaptiveJitterBuffer.frameSamples
    private var out = [Int16](repeating: 0, count: AdaptiveJitterBuffer.frameSamples * 8)
    private var outLen = 0
    private var lastKind = Kind.priming

    // Delay estimator: (arrival ms, relative delay ms).
    private var arrivals = TimedQueue()
    private var target: Int
    private var targetAt: Int64 = 0
    private var excessSince: Int64 = 0
    private var shrinking = false

    // ---- counters (cumulative, for the bench and the quality badge)
    private(set) var framesPlayed: Int64 = 0
    private(set) var framesExpanded: Int64 = 0
    private(set) var framesLossConcealed: Int64 = 0
    private(set) var framesSilence: Int64 = 0
    private(set) var framesLate: Int64 = 0
    private(set) var framesDuplicate: Int64 = 0
    private(set) var framesSkipped: Int64 = 0
    private(set) var samplesAccelerated: Int64 = 0
    private(set) var jumps: Int64 = 0
    private(set) var flushes: Int64 = 0
    private(set) var resyncs: Int64 = 0
    private(set) var overflowDrops: Int64 = 0

    /// Queued audio ahead of the speaker, ms (frames from the playout point to the newest + output carry).
    func delayMs() -> Int { sync { levelMs() } }

    func targetMs() -> Int { sync { target } }

    func kind() -> Kind { sync { lastKind } }

    var isEmpty: Bool { sync { frames.isEmpty } }

    /// One line for a call log / the bench.
    func stats() -> String {
        sync {
            "played=\(framesPlayed) expand=\(framesExpanded) lossConceal=\(framesLossConcealed) silence=\(framesSilence) " +
            "behind=\(framesLate) skipped=\(framesSkipped) accelMs=\(samplesAccelerated / Int64(Self.samplesPerMs)) jumps=\(jumps) " +
            "flushes=\(flushes) resyncs=\(resyncs) delay=\(levelMs()) target=\(target)"
        }
    }

    /// Queue one (still encoded) frame under `seq`. A copy already queued or played is ignored.
    func put(_ seq: Int64, _ frame: Data) {
        lock.lock(); defer { lock.unlock() }
        if frame.isEmpty { return }
        if nextSeq < 0 { nextSeq = seq; newestSeq = seq }
        let now = clock()
        if seq < nextSeq {
            if nextSeq - seq > Self.resyncFrames { resync(seq); resyncs += 1 }
            else {
                // Behind the playout point: a copy of a frame already played (redundancy —
                // says nothing) or a frame that came after its slot (says: the target is
                // short). Only the second is a measurement.
                framesLate += 1
                if seq > lastPlayedReal { observe(seq, now) }
                return
            }
        }
        if frames[seq] != nil { framesDuplicate += 1; return }
        // The delay that matters is when a frame's audio FIRST became available, from
        // whichever packet carried it: a packet held back 40 ms whose frame already came
        // inside the next packet's redundancy cost nothing and must not raise the target.
        observe(seq, now)
        frames[seq] = frame
        if seq > newestSeq { newestSeq = seq }
        while frames.count > Self.maxFrames { pollFirst(); overflowDrops += 1 }
    }

    private func resync(_ seq: Int64) {
        frames.removeAll(keepingCapacity: true); arrivals.removeAll(); levels.removeAll()
        nextSeq = seq; newestSeq = seq; priming = true; expandRun = 0
    }

    func reset() {
        lock.lock(); defer { lock.unlock() }
        resync(-1); nextSeq = -1; lastPlayedReal = -1; outLen = 0; plcRun = 0; lastReal = nil; decoder.reset()
        target = minTargetMs; excessSince = 0; shrinking = false
    }

    /// Exactly `frameSamples` samples of what the speaker plays next.
    func pull() -> [Int16] {
        lock.lock(); defer { lock.unlock() }
        while outLen < Self.frameSamples { produce() }
        let r = Array(out[0..<Self.frameSamples])
        let rest = outLen - Self.frameSamples
        if rest > 0 {
            out.withUnsafeMutableBufferPointer { p in
                let base = p.baseAddress!
                base.update(from: base + Self.frameSamples, count: rest)
            }
        }
        outLen = rest
        return r
    }

    // MARK: - ordered-map helpers (TreeMap)

    private func firstKey() -> Int64? { frames.keys.min() }

    private func ceilingKey(_ k: Int64) -> Int64? {
        var best: Int64?
        for key in frames.keys where key >= k { if best == nil || key < best! { best = key } }
        return best
    }

    private func pollFirst() { if let k = firstKey() { frames.removeValue(forKey: k) } }

    // MARK: - playout

    private func produce() {
        let now = clock()
        updateTarget(now)
        if nextSeq < 0 || priming {
            if nextSeq >= 0, let first = firstKey() {
                // A late first frame or a hole at the start: begin on what is here.
                if first > nextSeq { nextSeq = first }
                if (newestSeq - nextSeq + 1) * Int64(frameMs()) >= Int64(target) { priming = false }
            }
            if priming { emitZeros(Self.frameSamples); lastKind = .priming; return }
        }
        if let f = frames.removeValue(forKey: nextSeq) { playReal(f, now); return }
        guard let later = ceilingKey(nextSeq) else {
            // Nothing behind it: late, not lost. Conceal and WAIT (the delay grows by a frame).
            expandRun += 1
            framesExpanded += 1
            conceal(.expand)
            return
        }
        let missing = later - nextSeq
        // Concealment played while waiting stands in for the frames that turned out lost.
        let covered = min(missing, Int64(expandRun))
        nextSeq += covered; expandRun -= Int(covered)
        if nextSeq == later { playReal(frames.removeValue(forKey: nextSeq)!, now); return }
        if later - nextSeq > Self.jumpFrames {
            // A long hole with audio queued behind it (sender restart, outage that the
            // expansion did not cover): skip it rather than concealing for seconds.
            jumps += 1
            nextSeq = later
            playReal(frames.removeValue(forKey: nextSeq)!, now)
            return
        }
        nextSeq += 1
        framesLossConcealed += 1
        conceal(.lossConceal, next: frames[nextSeq])
    }

    /// The LOWEST queue level over the last `floorWindowMs`: the cushion that was never
    /// needed. The instantaneous level jumps every time an early packet lands, so shrinking
    /// on it never fired and every expansion stayed in the delay.
    private var levels = TimedQueue()
    private func floorMs(_ now: Int64, _ level: Int) -> Int {
        levels.append(now, Int64(level))
        while levels.count > 1 && now - levels.first.0 > Self.floorWindowMs { levels.removeFirst() }
        if levels.count < 5 { return 0 }
        return Int(levels.minValue())
    }

    private func frameMs() -> Int { max(1, lastFrameSamples / Self.samplesPerMs) }

    private func levelMs() -> Int {
        let queued: Int64 = (nextSeq < 0 || frames.isEmpty) ? 0 : (newestSeq - nextSeq + 1) * Int64(frameMs())
        return Int(queued + Int64(outLen / Self.samplesPerMs))
    }

    private func playReal(_ encoded: Data, _ now: Int64) {
        lastPlayedReal = nextSeq
        nextSeq += 1
        expandRun = 0
        let f0 = decoder.decode(encoded)
        if f0.isEmpty { framesLossConcealed += 1; conceal(.lossConceal, next: nil); return }
        lastFrameSamples = f0.count
        framesPlayed += 1
        var f = f0
        // Shrink a standing queue.
        let level = levelMs()
        if level > target + Self.flushAboveMs {
            // Cut the backlog to the target in one step; the frame being played becomes
            // the newest-but-target one.
            let keep = Int64(max(1, target / frameMs()))
            let newStart = newestSeq - keep + 1
            if newStart > nextSeq {
                while let k = firstKey(), k < newStart { frames.removeValue(forKey: k); framesSkipped += 1 }
                nextSeq = newStart
                flushes += 1
                if let enc = frames.removeValue(forKey: nextSeq) {
                    let g = decoder.decode(enc)
                    if !g.isEmpty { nextSeq += 1; f = crossfade(f0, g) }
                }
            }
            excessSince = 0
        } else if floorMs(now, level) >= target + Self.accelAboveMs || (shrinking && level > target + Self.accelStopMs) {
            if excessSince == 0 { excessSince = now }
            let held = now - excessSince
            if held >= Self.accelHoldMs {
                shrinking = true
                let quiet = rms(f) < Self.quietRms
                if quiet && f.count == Self.frameSamples && plcRun == 0 {
                    framesSkipped += 1
                    lastReal = f
                    lastKind = .normal
                    return                                                  // whole pause frame dropped
                }
                if held >= Self.accelForceMs || quiet {
                    if let shorter = removePitchPeriod(f) { samplesAccelerated += Int64(f.count - shorter.count); f = shorter }
                }
            }
        } else { excessSince = 0; shrinking = false }
        if plcRun > 0 && !codecConcealed { f = mergeFromConcealment(f) }
        plcRun = 0
        lastReal = f0
        append(f)
        lastKind = .normal
    }

    // MARK: - concealment

    private func conceal(_ kind: Kind, next: Data? = nil) {
        plcRun += 1
        let src = lastReal
        if src != nil && plcRun < Self.plcGains.count {
            if let c = decoder.conceal(next: next), !c.isEmpty {
                codecConcealed = true
                append(c)
                lastKind = kind
                return
            }
        }
        codecConcealed = false
        guard let s = src, plcRun < Self.plcGains.count else {
            framesSilence += 1
            emitZeros(lastFrameSamples)
            lastKind = .silence
            plcGain = 0
            return
        }
        let n = s.count
        if plcRun == 1 { plcPeriod = pitch(s); plcPhase = 0 }
        let g0 = Self.plcGains[plcRun - 1]
        let g1 = Self.plcGains[plcRun]
        var o = [Int16](repeating: 0, count: n)
        let p = plcPeriod
        for i in 0..<n {
            let env = g0 + (g1 - g0) * Float(i) / Float(n)
            let v: Float = p > 0 ? Float(s[n - p + (plcPhase + i) % p]) : Float(s[i]) * (plcRun == 1 ? 0.6 : 0.3)
            o[i] = Self.clamp16(Int(v * env))
        }
        if p > 0 { plcPhase = (plcPhase + n) % p }
        plcGain = g1
        append(o)
        lastKind = kind
    }

    /// First `crossfadeLen` samples of `real` blended in from the concealment's continuation.
    private func mergeFromConcealment(_ real: [Int16]) -> [Int16] {
        guard let src = lastReal else { return real }
        var o = real
        let m = min(Self.crossfadeLen, real.count)
        let p = plcPeriod
        let n = src.count
        for i in 0..<m {
            let t: Float = (p > 0 && plcGain > 0) ? Float(src[n - p + (plcPhase + i) % p]) * plcGain : 0
            let w = Float(i) / Float(m)
            o[i] = Self.clamp16(Int(Float(real[i]) * w + t * (1 - w)))
        }
        return o
    }

    private func crossfade(_ a: [Int16], _ b: [Int16]) -> [Int16] {
        // Play the start of `a` fading into `b`: one frame's worth, no step.
        var o = b
        let m = min(Self.crossfadeLen * 2, b.count, a.count)
        for i in 0..<m {
            let w = Float(i) / Float(m)
            o[i] = Int16(truncatingIfNeeded: Int(Float(a[i]) * (1 - w) + Float(b[i]) * w))
        }
        return o
    }

    /// One pitch period cut out of a voiced frame (WSOLA-style), or nil if it is not periodic.
    private func removePitchPeriod(_ f: [Int16]) -> [Int16]? {
        let n = f.count
        var bestP = 0; var best = 0.0
        let maxP = min(Self.pitchMax, n / 2)
        if maxP >= Self.pitchMin {
            for p in stride(from: Self.pitchMin, through: maxP, by: 2) {
                var num = 0.0, e1 = 0.0, e2 = 0.0
                for i in 0..<p { let a = Double(f[i]), b = Double(f[i + p]); num += a * b; e1 += a * a; e2 += b * b }
                let c = (e1 > 0 && e2 > 0) ? num / (e1 * e2).squareRoot() : 0.0
                if c > best { best = c; bestP = p }
            }
        }
        if bestP == 0 || best < 0.6 { return nil }
        let p = bestP
        var o = [Int16](repeating: 0, count: n - p)
        for i in 0..<p {
            let w = Float(i) / Float(p)
            o[i] = Int16(truncatingIfNeeded: Int(Float(f[i]) * (1 - w) + Float(f[i + p]) * w))
        }
        for i in (2 * p)..<n { o[i - p] = f[i] }
        return o
    }

    private func pitch(_ s: [Int16]) -> Int {
        let n = s.count
        let win = Self.pitchWin
        let maxP = min(Self.pitchMax, n - win)
        if maxP <= Self.pitchMin { return 0 }
        var e0 = 0.0
        for i in (n - win)..<n { e0 += Double(s[i]) * Double(s[i]) }
        if e0 < Double(win) * 900.0 { return 0 }
        var score = [Double](repeating: 0, count: maxP + 1)
        var best = 0.0
        for p in Self.pitchMin...maxP {
            var num = 0.0, den = 0.0
            for i in (n - win)..<n { let b = Double(s[i - p]); num += Double(s[i]) * b; den += b * b }
            score[p] = den > 0 ? num / (den * e0).squareRoot() : 0.0
            if score[p] > best { best = score[p] }
        }
        if best < 0.5 { return 0 }
        for p in Self.pitchMin...maxP {
            let prev = p > Self.pitchMin ? score[p - 1] : -1.0
            let next = p < maxP ? score[p + 1] : -1.0
            if score[p] >= 0.9 * best && score[p] >= prev && score[p] >= next { return p }
        }
        return 0
    }

    // MARK: - delay estimate

    private func observe(_ seq: Int64, _ now: Int64) {
        arrivals.append(now, now - seq * Int64(frameMs()))
        while !arrivals.isEmpty && now - arrivals.first.0 > Self.windowMs { arrivals.removeFirst() }
    }

    private func updateTarget(_ now: Int64) {
        if now - targetAt < 100 || arrivals.count < 10 { return }
        targetAt = now
        let d = arrivals.values().sorted()
        let minD = d[0]
        let med = d[d.count / 2]
        let kept = d.filter { $0 - med <= Self.spikeMs }
        let p95 = kept[min(kept.count - 1, Int(Double(kept.count) * 0.95))]
        target = min(max(Int(p95 - minD + Self.targetMarginMs), minTargetMs), maxTargetMs)
    }

    // MARK: - output carry

    private func append(_ a: [Int16]) {
        if outLen + a.count > out.count { out += [Int16](repeating: 0, count: outLen + a.count + Self.frameSamples - out.count) }
        for i in 0..<a.count { out[outLen + i] = a[i] }
        outLen += a.count
    }

    private func emitZeros(_ n: Int) {
        if outLen + n > out.count { out += [Int16](repeating: 0, count: outLen + n + Self.frameSamples - out.count) }
        for i in outLen..<(outLen + n) { out[i] = 0 }
        outLen += n
    }

    private func rms(_ a: [Int16]) -> Double {
        var s = 0.0
        for v in a { s += Double(v) * Double(v) }
        return (s / Double(max(1, a.count))).squareRoot()
    }

    @inline(__always) static func clamp16(_ v: Int) -> Int16 { Int16(max(-32768, min(32767, v))) }
}

/// (time, value) FIFO with amortised O(1) pops — the Kotlin `ArrayDeque<LongArray>`.
private struct TimedQueue {
    private var t: [Int64] = []
    private var v: [Int64] = []
    private var head = 0
    var count: Int { t.count - head }
    var isEmpty: Bool { count == 0 }
    var first: (Int64, Int64) { (t[head], v[head]) }
    mutating func append(_ time: Int64, _ value: Int64) { t.append(time); v.append(value) }
    mutating func removeFirst() {
        head += 1
        if head > 512 && head * 2 > t.count { t.removeFirst(head); v.removeFirst(head); head = 0 }
    }
    mutating func removeAll() { t.removeAll(keepingCapacity: true); v.removeAll(keepingCapacity: true); head = 0 }
    func values() -> [Int64] { Array(v[head...]) }
    func minValue() -> Int64 { v[head...].min() ?? 0 }
}
