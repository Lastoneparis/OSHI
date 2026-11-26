//
//  OpusPlayoutClock.swift
//  OSHI
//
//  __CALL_FLUIDITY_2026_11_21__ The 0x19 (Opus) receive path, played by a CLOCK.
//
//  WHY (audit of release/ios-1.0.49, measured with `FluiditySimBench`): playout used to be
//  driven by packet ARRIVAL — every packet that came in drained `AdvancedJitterBuffer` into
//  AVAudioPlayerNode, and nothing at all happened between arrivals. A network stall therefore
//  played hard silence (the player queue ran dry), PLC only ran when the NEXT packet arrived,
//  the buffer never refilled to its target after a slot jump, frames that overtook others
//  were concealed on arrival and the real ones discarded, and a burst was either queued in
//  the player (delay) or dropped newest-first at the queue cap.
//
//  Now: packets are FILED encoded in `AdaptiveJitterBuffer` (keyed by the codec seq), and a
//  timer on a dedicated user-interactive queue hands the speaker exactly one 20 ms frame
//  each time the player has at most `deviceAheadFrames − 1` frames left — the hardware's own
//  consumption paces it, the jitter buffer (not the player queue) holds the delay, and every
//  slot is filled by a real frame, Opus FEC/PLC, or the buffer's time-stretch. Same rule as
//  the desktop's `renderFromJitter` (DEVICE_AHEAD_FRAMES = 2) and Android's 20 ms playout loop.
//
//  The class has no AVFoundation in it: the call manager gives it two closures (how much is
//  queued in the player, and how to play a frame), which is also how the bench drives it.
//

import Foundation

final class OpusPlayoutClock {
    /// Timer period. The player consumes in IO-buffer chunks (10 ms here, see
    /// `__IO_BUFFER_10MS_2026_08_14__`), so a 10 ms look keeps the device queue between one
    /// and two frames without ever letting it run dry.
    static let tickMs = 10
    /// Frames the player may hold ahead of what is playing (the desktop's DEVICE_AHEAD_FRAMES).
    static let deviceAheadFrames = 2
    /// Never more than this many frames handed over in one tick (a late timer catches up,
    /// a player that does not consume cannot pull the buffer down faster than real time).
    static let maxFramesPerTick = 3

    let decoder = OpusPlayoutDecoder()
    let jitter: AdaptiveJitterBuffer
    let unwrap = SeqUnwrapper()

    private let stateLock = NSLock()
    private var _active = false
    private var timer: DispatchSourceTimer?
    private let queue = DispatchQueue(label: "com.oshi.call.opus-playout", qos: .userInteractive)
    private(set) var ticks: Int64 = 0
    private(set) var framesHanded: Int64 = 0

    init(clock: @escaping () -> Int64 = AdaptiveJitterBuffer.monotonicMs) {
        jitter = AdaptiveJitterBuffer(decoder: decoder, clock: clock)
    }

    /// True once a 0x19 packet was filed this call (the clock then owns the speaker).
    var isActive: Bool { stateLock.lock(); defer { stateLock.unlock() }; return _active }
    var isRunning: Bool { stateLock.lock(); defer { stateLock.unlock() }; return timer != nil }

    /// File one decrypted 0x19 payload. False when it does not parse.
    @discardableResult
    func file(_ payload: Data) -> Bool {
        guard OpusPlayoutDecoder.file(payload, into: jitter, unwrap: unwrap) else { return false }
        stateLock.lock(); _active = true; stateLock.unlock()
        return true
    }

    /// The frames to hand the speaker now, given what it still has queued: one 20 ms frame
    /// each time it holds at most `deviceAheadFrames − 1` frames.
    func due(queuedSamples: Int) -> [(pcm: [Int16], kind: AdaptiveJitterBuffer.Kind)] {
        var q = queuedSamples
        var out: [(pcm: [Int16], kind: AdaptiveJitterBuffer.Kind)] = []
        let ahead = (Self.deviceAheadFrames - 1) * AdaptiveJitterBuffer.frameSamples
        while q <= ahead && out.count < Self.maxFramesPerTick {
            jitter.lock.lock()
            let pcm = jitter.pull()
            let kind = jitter.kind()
            jitter.lock.unlock()
            out.append((pcm, kind))
            q += pcm.count
        }
        return out
    }

    /// Start the clock. `queuedSamples` answers what the player still has to play, or nil when
    /// there is no running player (then nothing is pulled: the buffer keeps the audio and
    /// cuts any backlog once the player is back). `play` schedules one frame; both are called
    /// on the clock's queue.
    func start(queuedSamples: @escaping () -> Int?,
               play: @escaping ([Int16], AdaptiveJitterBuffer.Kind) -> Void,
               diag: ((String) -> Void)? = nil) {
        stateLock.lock(); defer { stateLock.unlock() }
        guard timer == nil else { return }
        let t = DispatchSource.makeTimerSource(flags: .strict, queue: queue)
        t.schedule(deadline: .now(), repeating: .milliseconds(Self.tickMs), leeway: .milliseconds(1))
        t.setEventHandler { [weak self] in
            guard let self else { return }
            self.ticks &+= 1
            if let diag, self.ticks % 1000 == 1 {
                diag("DIAG_OPUS_PLAYOUT | \(self.jitter.stats()) fec=\(self.decoder.fecDecodes) handed=\(self.framesHanded)")
            }
            guard let q = queuedSamples() else { return }
            for f in self.due(queuedSamples: q) {
                self.framesHanded &+= 1
                play(f.pcm, f.kind)
            }
        }
        timer = t
        t.resume()
    }

    func stop() {
        stateLock.lock()
        let t = timer
        timer = nil
        stateLock.unlock()
        t?.cancel()
    }

    /// Drop everything queued and re-prime (peer resync request / backlog flush). The call
    /// stays on the clock.
    func flush() { jitter.reset() }

    /// End of call: forget the stream entirely.
    func reset() {
        stop()
        jitter.reset()
        unwrap.reset()
        stateLock.lock(); _active = false; stateLock.unlock()
        ticks = 0; framesHanded = 0
    }
}
