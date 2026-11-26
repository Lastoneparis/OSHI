//
//  FluidityChains.swift
//  OSHITests
//
//  __CALL_FLUIDITY_BENCH_2026_11_21__ The iOS 0x19 receive chain in VIRTUAL time, before and
//  after, for `FluiditySimBench` — same scenarios (`NetworkImpairment`), same analysis
//  (`FluidityProbe`) and same run loop as the Kotlin benches (Android `FluiditySimBench`).
//
//  Both chains end in the same model of AVAudioPlayerNode (`PlayerModel`): buffers scheduled
//  back to back, consumed by a 10 ms IO cycle (`__IO_BUFFER_10MS_2026_08_14__`), silence when
//  the queue is empty, a scheduled buffer counted until fully consumed (the completion
//  handler that maintains `scheduledAudioFrames`). The pre-gain/tanh, far low-pass and echo
//  gate that `playAudioData` applies to every frame are the same in both chains and left out.
//
//  `OldIOS` reproduces release/ios-1.0.49 line for line where it matters: the 0x19 branch of
//  the receive path (`OpusCallDecoder.decode` → `OpusCallDecoder.keyed(outerSeq, lastKey)` →
//  `processReceivedAudioData`), i.e. `AdvancedJitterBuffer.addPacket` then the drain
//  `while getNextPacket()` through `PacketLossConcealer`, `min(holes, 4)` PLC frames for loss
//  advances, and `playAudioData`'s PlayoutTrimmer, queue cap (`min(1200, max(200, target +
//  250))` ms, newest frame dropped, 2 ms fade-in after) and the 1 s auto-resync watchdog.
//  `NewIOS` is `OpusPlayoutClock` exactly as the call manager drives it.
//

import Foundation
#if canImport(OSHI)
@testable import OSHI
#endif

protocol FluidityReceiver: AnyObject {
    var name: String { get }
    func onPacket(nowMs: Int64, outerSeq: UInt64, payload: Data)
    /// Called every ms while due; returns the PCM the speaker played since the last call.
    func tick(nowMs: Int64) -> [Int16]?
    /// Audio queued between the network and the speaker, ms (jitter buffer + player queue).
    func queueMs() -> Int
    func counters() -> String
}

/// AVAudioPlayerNode, as far as timing goes.
final class PlayerModel {
    static let ioSamples = 480
    private var buffers: [[Int16]] = []
    private var head = 0          // offset into buffers[0]
    private(set) var scheduledFrames = 0
    private(set) var started = false
    private(set) var underrunSamples: Int64 = 0

    func schedule(_ pcm: [Int16]) {
        guard !pcm.isEmpty else { return }
        buffers.append(pcm); scheduledFrames += pcm.count; started = true
    }

    func render(_ n: Int = PlayerModel.ioSamples) -> [Int16] {
        var out = [Int16](repeating: 0, count: n)
        var o = 0
        while o < n, let b = buffers.first {
            let take = min(n - o, b.count - head)
            for i in 0..<take { out[o + i] = b[head + i] }
            o += take; head += take
            if head == b.count { buffers.removeFirst(); head = 0; scheduledFrames -= b.count }
        }
        underrunSamples += Int64(n - o)
        return out
    }

    func flush() { buffers.removeAll(); head = 0; scheduledFrames = 0 }
    var queuedMs: Double { Double(scheduledFrames) / 48.0 }
}

final class OldIOS: FluidityReceiver {
    let name = "OldIOS"
    private var now: Int64 = 0
    private let ajb = AdvancedJitterBuffer()
    private let plc = PacketLossConcealer()
    private var trimmer = PlayoutTrimmer()
    private let dec = OpusCallDecoder()!
    private var lastKey: UInt64?
    let player = PlayerModel()
    private var fadeIn = false
    private var overLimitTicks = 0
    private var lastWatchdogMs: Int64 = -1
    private(set) var dropped = 0, trimmed = 0, plcInjected = 0, autoResyncs = 0, played = 0

    init() { ajb.clock = { [unowned self] in Double(self.now) } }

    private var dropThresholdMs: Double { min(1200.0, max(200.0, Double(ajb.targetDepthMs) + 250.0)) }
    private var autoResyncMs: Double { min(1800.0, max(500.0, dropThresholdMs + 300.0)) }

    func onPacket(nowMs: Int64, outerSeq: UInt64, payload: Data) {
        now = nowMs
        let keyed = OpusCallDecoder.keyed(dec.decode(payload), outerSeq: outerSeq, lastKey: lastKey)
        guard let newest = keyed.last?.key else { return }
        lastKey = newest
        for f in keyed { processReceived(Self.toFloat(f.pcm), seq: f.key) }
    }

    /// `processReceivedAudioData`, advanced-jitter-buffer branch, `playoutFixEnabled`.
    private func processReceived(_ audio: Data, seq: UInt64) {
        _ = ajb.addPacket(sequenceNumber: seq, audioData: audio)
        let before = ajb.lossAdvancesSnapshot
        while let p = ajb.getNextPacket() { play(plc.process(packet: p)) }
        let after = ajb.lossAdvancesSnapshot
        let holes = after >= before ? Int(after - before) : 0
        if holes > 0 { for _ in 0..<min(holes, 4) { plcInjected += 1; play(plc.process(packet: nil)) } }
    }

    /// `playAudioData`: trimmer (frame RMS as `pcmStatsFromBuffer` sees it after the earpiece
    /// pre-gain), queue cap, fade-in after a drop.
    private func play(_ floatData: Data) {
        var f = Self.floats(floatData)
        guard !f.isEmpty else { return }
        var sumSq: Float = 0
        for x in f { let y = tanhf(x * 1.43); sumSq += y * y }
        let rms = Double((sumSq / Float(f.count)).squareRoot() * 32767)
        let frameMs = Double(f.count) / 48.0
        if case .trim = trimmer.decide(queuedMs: player.queuedMs, targetMs: Double(ajb.targetDepthMs), frameRms: rms, frameMs: frameMs) {
            trimmed += 1; return
        }
        if player.queuedMs >= dropThresholdMs { dropped += 1; fadeIn = true; return }
        if fadeIn {
            fadeIn = false
            let n = min(f.count, 96)
            for i in 0..<n { f[i] *= Float(i) / Float(n) }
        }
        played += 1
        player.schedule(f.map { Int16(clamping: Int(($0 * 32768).rounded(.towardZero))) })
    }

    func tick(nowMs: Int64) -> [Int16]? {
        now = nowMs
        // emitDiagPlaybackRateIfDue's auto-resync watchdog, once a second.
        if player.started && (lastWatchdogMs < 0 || nowMs - lastWatchdogMs >= 1000) {
            lastWatchdogMs = nowMs
            if player.queuedMs >= autoResyncMs {
                overLimitTicks += 1
                if overLimitTicks >= 2 { ajb.reset(); plc.reset(); player.flush(); trimmer.reset(); overLimitTicks = 0; autoResyncs += 1 }
            } else { overLimitTicks = 0 }
        }
        guard player.started, nowMs % 10 == 0 else { return nil }
        return player.render()
    }

    func queueMs() -> Int { Int(ajb.bufferDepthMs + player.queuedMs) }
    func counters() -> String {
        "played=\(played) plcInjected=\(plcInjected) trimmed=\(trimmed) dropsQueueFull=\(dropped) autoResync=\(autoResyncs) slotJumps=\(ajb.totalSlotJumps) target=\(ajb.targetDepthMs) underrunMs=\(player.underrunSamples / 48)"
    }

    static func toFloat(_ pcm: [Int16]) -> Data {
        let f = pcm.map { Float($0) / 32768.0 }
        return f.withUnsafeBytes { Data($0) }
    }
    static func floats(_ d: Data) -> [Float] {
        d.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
    }
}

final class NewIOS: FluidityReceiver {
    let name = "NewIOS"
    private var now: Int64 = 0
    private(set) lazy var clock = OpusPlayoutClock(clock: { [unowned self] in self.now })
    let player = PlayerModel()
    private var active = false

    func onPacket(nowMs: Int64, outerSeq: UInt64, payload: Data) {
        now = nowMs
        if clock.file(payload) { active = true }
    }

    func tick(nowMs: Int64) -> [Int16]? {
        now = nowMs
        guard active, nowMs % 10 == 0 else { return nil }
        // The playout clock's tick (top the player up to 2 frames), then the IO cycle.
        for f in clock.due(queuedSamples: player.scheduledFrames) { player.schedule(f.pcm) }
        return player.render()
    }

    func queueMs() -> Int { clock.jitter.delayMs() + Int(player.queuedMs) }
    func counters() -> String { clock.jitter.stats() + " fec=\(clock.decoder.fecDecodes) underrunMs=\(player.underrunSamples / 48)" }
}

enum FluidityRun {
    struct Row {
        let scenario: String
        let impl: String
        let r: FluidityProbe.Result
        let queueMean: Double
        let queueMax: Int
        let counters: String
    }

    /// The Kotlin `FluiditySimBench.run` loop: 1 ms steps, sender on its own (skewed) clock,
    /// packets delivered in arrival order, the receiver ticked when its output is due.
    static func run(_ spec: NetworkImpairment.Spec, _ rx: FluidityReceiver, durationMs: Int64 = 20_000, seed: Int64 = 7) -> Row {
        let net = NetworkImpairment(spec, seed: seed)
        let enc = OpusCallEncoder(bitrate: 32_000, complexity: 8)!
        let frameMs = 20.0 / (1 + spec.clockSkewPpm * 1e-6)
        var q: [(at: Double, seq: UInt64, p: Data)] = []   // sorted by `at`, FIFO on ties
        var qHead = 0
        var nextSend = 20.0
        var k: Int64 = 0
        var out: [Int16] = []
        var startMs = -1.0, nextDue = -1.0
        var jb: [Int] = []
        for t in Int64(0)...(durationMs + 1500) {
            while t <= durationMs && nextSend <= Double(t) {
                for p in enc.encode(FluidityProbe.frame(k)) {
                    if let at = net.route(nextSend) {
                        var lo = qHead, hi = q.count
                        while lo < hi { let mid = (lo + hi) / 2; if q[mid].at <= at { lo = mid + 1 } else { hi = mid } }
                        q.insert((at, UInt64(1000 + k), p), at: lo)
                    }
                }
                k += 1; nextSend += frameMs
            }
            while qHead < q.count && q[qHead].at <= Double(t) {
                let e = q[qHead]; qHead += 1
                rx.onPacket(nowMs: t, outerSeq: e.seq, payload: e.p)
            }
            if qHead > 4096 { q.removeFirst(qHead); qHead = 0 }
            if nextDue < 0 || Double(t) >= nextDue {
                if let pcm = rx.tick(nowMs: t) {
                    if startMs < 0 { startMs = Double(t); nextDue = Double(t) }
                    out += pcm
                    nextDue += Double(pcm.count) / 48.0
                }
            }
            if t % 100 == 0 && startMs >= 0 { jb.append(rx.queueMs()) }
        }
        let res = FluidityProbe.analyze(output: out, outputStartMs: startMs, inputStartMs: 0, inputSamples: k * 960,
                                        inputRate: 48_000.0 * (1 + spec.clockSkewPpm * 1e-6),
                                        outageAtMs: spec.outageAtMs, outageMs: spec.outageMs,
                                        reference: FluidityReference.cachedReference(frames: k))
        let mean = jb.isEmpty ? 0 : Double(jb.reduce(0, +)) / Double(jb.count)
        return Row(scenario: spec.name, impl: rx.name, r: res, queueMean: mean, queueMax: jb.max() ?? 0, counters: rx.counters())
    }

    static func table(_ rows: [Row]) -> String {
        let f = FluidityReference.fmt
        var t = "| scenario | chain | gap % | gaps/min | m2e median ms | m2e p95 | drift ms | queue mean/max ms | post-outage gap % | post-outage extra ms |\n"
        t += "|---|---|---|---|---|---|---|---|---|---|\n"
        for w in rows {
            t += "| \(w.scenario) | \(w.impl) | \(f(w.r.gapRatio * 100, 1)) | \(f(w.r.gapsPerMin, 0)) | \(f(w.r.medianLatency, 0)) | \(f(w.r.p95Latency, 0)) | \(f(w.r.driftMs, 0)) | \(f(w.queueMean, 0))/\(w.queueMax) | \(f(w.r.postOutageGapRatio * 100, 1)) | \(f(w.r.postOutageExtraMs, 0)) |\n"
        }
        return t
    }
}
