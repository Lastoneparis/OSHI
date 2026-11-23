//
//  OshiCodec.swift
//  OSHI
//
//  Swift wrapper around the proprietary OshiCodec C API
//  (see llama.cpp/jni/oshi_codec/oshi_codec.h).
//
//  - 20 ms frames @ 48 kHz mono int16 (960 samples/frame).
//  - Bit-identical wire format with the Android binding.
//  - This class is NOT wired into VoiceCallManager's audio paths yet;
//    Agent 6 of the CALL_V2_PLAN rollout handles that.
//

import Foundation

/// Thread-safety: one OshiCodec instance is NOT thread-safe. Create
/// separate instances for encode-side and decode-side if you need to
/// use them from different queues.
final class OshiCodec {

    // MARK: - Constants

    /// Samples per 20 ms frame at 48 kHz mono.
    static let frameSize: Int = 960

    /// Target sample rate.
    static let sampleRate: Int32 = 48000

    /// Upper bound for any encoded-frame size (matches OSHI_MAX_FRAME_BYTES).
    static let maxFrameBytes: Int = 200

    // MARK: - State

    // The C API exposes `oshi_codec_ctx*` which Swift imports as OpaquePointer?.
    private var ctx: OpaquePointer?

    // MARK: - Lifecycle

    init(sampleRate: Int32 = OshiCodec.sampleRate, channels: Int32 = 1) {
        // oshi_codec_create returns an OpaquePointer? (from forward-declared struct).
        self.ctx = oshi_codec_create(sampleRate, channels)
    }

    deinit {
        if let ctx = self.ctx {
            oshi_codec_destroy(ctx)
        }
    }

    // MARK: - Public API

    /// Set target bitrate. Accepts raw bits-per-second
    /// (rounded to nearest supported level) or one of the
    /// OSHI_BR_* enum values passed as an int.
    func setBitrate(_ bps: Int32) {
        guard let ctx = self.ctx else { return }
        oshi_codec_set_bitrate(ctx, bps)
    }

    /// Reset encoder/decoder internal filters (does not change bitrate).
    func reset() {
        guard let ctx = self.ctx else { return }
        oshi_codec_reset(ctx)
    }

    /// Encode one or more 20 ms frames of PCM int16 samples.
    /// `samples` must be a positive multiple of `frameSize` (960).
    /// Returns the concatenated wire bytes for all encoded frames.
    /// Returns an empty `Data` on bad input or if the codec context failed to create.
    func encode(pcm: UnsafePointer<Int16>, samples: Int) -> Data {
        guard let ctx = self.ctx else { return Data() }
        guard samples > 0, samples % OshiCodec.frameSize == 0 else { return Data() }

        let frameCount = samples / OshiCodec.frameSize
        var output = Data()
        output.reserveCapacity(frameCount * OshiCodec.maxFrameBytes)

        var scratch = [UInt8](repeating: 0, count: OshiCodec.maxFrameBytes)

        for i in 0..<frameCount {
            let framePtr = pcm.advanced(by: i * OshiCodec.frameSize)
            let written = scratch.withUnsafeMutableBufferPointer { buf -> Int32 in
                return oshi_codec_encode(
                    ctx,
                    framePtr,
                    Int32(OshiCodec.frameSize),
                    buf.baseAddress,
                    Int32(OshiCodec.maxFrameBytes)
                )
            }
            if written <= 0 {
                // Skip broken frames rather than aborting mid-buffer;
                // higher layer can detect the size mismatch if needed.
                continue
            }
            output.append(scratch, count: Int(written))
        }
        return output
    }

    /// Decode a byte buffer that contains one or more concatenated OshiCodec
    /// frames (as produced by `encode`). Returns the PCM int16 samples,
    /// in frame-sized chunks of `frameSize`.
    func decode(_ bytes: Data) -> [Int16] {
        guard let ctx = self.ctx else { return [] }
        guard !bytes.isEmpty else { return [] }

        var output: [Int16] = []
        output.reserveCapacity((bytes.count / 40 + 1) * OshiCodec.frameSize)

        var pcmScratch = [Int16](repeating: 0, count: OshiCodec.frameSize)

        // Frames are variable-size depending on bitrate level. The level
        // lives in the high 3 bits of byte 0 of each frame header;
        // we walk the buffer frame-by-frame using that to advance the cursor.
        var cursor = 0
        bytes.withUnsafeBytes { rawBuf in
            guard let base = rawBuf.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            while cursor < bytes.count {
                let remaining = bytes.count - cursor
                let header0 = base.advanced(by: cursor).pointee
                let level = Int((header0 >> 5) & 0x07) // 3 bits
                let frameBytes = Self.frameBytes(forLevel: level)
                guard frameBytes > 0, frameBytes <= remaining else {
                    // Malformed or truncated tail — stop decoding.
                    break
                }
                let decoded = pcmScratch.withUnsafeMutableBufferPointer { pcmBuf -> Int32 in
                    return oshi_codec_decode(
                        ctx,
                        base.advanced(by: cursor),
                        Int32(frameBytes),
                        pcmBuf.baseAddress,
                        Int32(OshiCodec.frameSize)
                    )
                }
                if decoded != Int32(OshiCodec.frameSize) {
                    break
                }
                output.append(contentsOf: pcmScratch)
                cursor += frameBytes
            }
        }
        return output
    }

    // MARK: - Internal helpers

    /// Wire-frame size in bytes for each bitrate level.
    /// Mirrors `kBitrateCfg[*].payload_bytes` in oshi_codec.cpp.
    private static func frameBytes(forLevel level: Int) -> Int {
        switch level {
        case 0: return 40   // BR_16K
        case 1: return 60   // BR_24K
        case 2: return 80   // BR_32K
        case 3: return 120  // BR_48K (default)
        case 4: return 160  // BR_64K
        default: return 0
        }
    }
}

// MARK: - Wideband IMA-ADPCM (wire type 0x18) — __WB_ADPCM_CODEC_2026_09_23__
//
// WHY: an iPhone↔Android call had NO codec in common. AAC-ELD bitstreams are not
// interoperable between AudioToolbox and MediaCodec, OshiCodec (0x16) is disabled
// (LPC artefacts) and the 16 kHz PCM path (0x17) is disabled (biquad precision). So
// every cross-platform frame was raw 48 kHz PCM: 1957 B per 20 ms datagram, 782 kbit/s,
// two IP fragments each — MEASURED B37DC742 (2026-09-23, iPhone 5G+VPN ⇄ Android):
// 1185 KB for 620 packets ⇒ 1911 B/pkt. VPNs and carriers drop fragments, so the
// direct path black-holed and the UDP relay lost frames.
//
// This codec is pure integer code, bit-identical on iOS (here), Android
// (`service/audio/WbAdpcmCodec.kt`) and Desktop: 16 kHz wideband, 4 bit/sample.
// One 20 ms frame = 320 samples ⇒ 3-byte header + 160 B = 163 B payload
// ⇒ 200 B on the wire after [type 1][seq 8] + AES-GCM (12 + 16). 64 kbit/s.
//
// Payload: [pred Int16 BE][stepIndex UInt8][nibbles: sample 2k in the LOW nibble,
// sample 2k+1 in the HIGH nibble]. The header is the encoder state at frame start, so
// every packet decodes on its own: a lost packet never desynchronises the decoder.
// Standard IMA/DVI ADPCM step and index tables.
//
// Negotiated with capability byte 0x10 (5th trailing byte of the offer, index 4 of the
// accept body). Only sent to a peer that advertised it; any 0x18 received is decoded.
enum WbAdpcm {
    static let wireType: UInt8 = 0x18
    static let capFlag: UInt8 = 0x10
    static let sampleRate = 16_000
    static let headerBytes = 3

    static let stepTable: [Int32] = [
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
        253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
        1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
        3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
        12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767]
    static let indexTable: [Int32] = [-1, -1, -1, -1, 2, 4, 6, 8]

    /// Encoder state carried across frames (continuity); written into every header.
    struct EncoderState { var predictor: Int32 = 0; var index: Int32 = 0 }

    static func encode(_ samples: [Int16], state: inout EncoderState) -> Data {
        var out = Data(capacity: headerBytes + (samples.count + 1) / 2)
        let p16 = UInt16(bitPattern: Int16(state.predictor))
        out.append(UInt8(p16 >> 8)); out.append(UInt8(p16 & 0xFF)); out.append(UInt8(state.index))
        var pred = state.predictor, index = state.index
        var pending: UInt8 = 0, havePending = false
        for s in samples {
            var step = stepTable[Int(index)]
            var diff = Int32(s) - pred
            var nib: Int32 = 0
            if diff < 0 { nib = 8; diff = -diff }
            var vp = step >> 3
            if diff >= step { nib |= 4; diff -= step; vp += step }
            step >>= 1
            if diff >= step { nib |= 2; diff -= step; vp += step }
            step >>= 1
            if diff >= step { nib |= 1; vp += step }
            pred = (nib & 8) != 0 ? pred - vp : pred + vp
            pred = min(32767, max(-32768, pred))
            index = min(88, max(0, index + indexTable[Int(nib & 7)]))
            if havePending { out.append(pending | UInt8(nib << 4)); havePending = false }
            else { pending = UInt8(nib); havePending = true }
        }
        if havePending { out.append(pending) }
        state.predictor = pred; state.index = index
        return out
    }

    static func decode(_ data: Data) -> [Int16] {
        var none: [Int32]? = nil
        return decode(data, steps: &none)
    }

    /// Decode, and when `steps` is non-nil also fill it with the quantiser step used for
    /// each output sample (the decoder knows its own quantisation noise; `WbPostFilter`
    /// uses it). The samples are bit-identical to `decode(_:)`'s.
    static func decode(_ data: Data, steps: inout [Int32]?) -> [Int16] {
        guard data.count > headerBytes else { return [] }
        let b = [UInt8](data)
        var pred = Int32(Int16(bitPattern: UInt16(b[0]) << 8 | UInt16(b[1])))
        var index = min(88, max(0, Int32(b[2])))
        var out = [Int16](); out.reserveCapacity((b.count - headerBytes) * 2)
        for i in headerBytes..<b.count {
            for nib in [Int32(b[i] & 0x0F), Int32(b[i] >> 4)] {
                let step = stepTable[Int(index)]
                var vp = step >> 3
                if (nib & 4) != 0 { vp += step }
                if (nib & 2) != 0 { vp += step >> 1 }
                if (nib & 1) != 0 { vp += step >> 2 }
                pred = (nib & 8) != 0 ? pred - vp : pred + vp
                pred = min(32767, max(-32768, pred))
                index = min(88, max(0, index + indexTable[Int(nib & 7)]))
                steps?.append(step)
                out.append(Int16(pred))
            }
        }
        return out
    }
}

/// 48 kHz ⇄ 16 kHz polyphase FIR resampler (windowed-sinc low-pass, 7 kHz cutoff,
/// 48 taps, Blackman window). Float math, stateful across frames — NOT part of the wire
/// contract, so each platform may implement it its own way. Replaces the 0x17 biquad,
/// whose Q30 state truncation crackled on real voice. One instance per direction.
final class WbResampler {
    static let taps: [Float] = {
        let n = 48, fc = 7_000.0 / 48_000.0
        var h = [Double](repeating: 0, count: n)
        let m = Double(n - 1)
        for i in 0..<n {
            let x = Double(i) - m / 2
            let sinc = x == 0 ? 2 * fc : sin(2 * Double.pi * fc * x) / (Double.pi * x)
            let w = 0.42 - 0.5 * cos(2 * Double.pi * Double(i) / m) + 0.08 * cos(4 * Double.pi * Double(i) / m)
            h[i] = sinc * w
        }
        let sum = h.reduce(0, +)
        return h.map { Float($0 / sum) }
    }()
    private var history = [Float](repeating: 0, count: 48)   // most recent input last

    func reset() { for i in history.indices { history[i] = 0 } }

    /// 48 kHz Int16 → 16 kHz Int16 (count/3 samples).
    func down(_ input: [Int16]) -> [Int16] {
        let h = Self.taps, n = h.count
        var buf = history + input.map { Float($0) }
        var out = [Int16](); out.reserveCapacity(input.count / 3)
        var i = n + 2   // index (in buf) of the newest sample feeding output 0
        while i < buf.count {
            var acc: Float = 0
            for k in 0..<n { acc += h[k] * buf[i - k] }
            out.append(Int16(max(-32768, min(32767, (acc + 0.5).rounded(.down)))))   // = Kotlin Math.round
            i += 3
        }
        buf.removeFirst(buf.count - n)
        history = buf
        return out
    }

    /// 16 kHz Int16 → 48 kHz Int16 (count×3 samples): zero-stuff ×3 then low-pass ×3.
    func up(_ input: [Int16]) -> [Int16] {
        let h = Self.taps, n = h.count, k3 = n / 3
        // history holds the last k3 16 kHz input samples (as Float) in its tail.
        var buf = Array(history.suffix(k3)) + input.map { Float($0) }
        var out = [Int16](); out.reserveCapacity(input.count * 3)
        for j in 0..<input.count {
            let jj = j + k3          // index of the current input sample in buf
            for p in 0..<3 {
                var acc: Float = 0
                var k = p
                while k < n {
                    let src = jj - (k - p) / 3
                    if src >= 0 { acc += h[k] * buf[src] }
                    k += 3
                }
                out.append(Int16(max(-32768, min(32767, (3 * acc + 0.5).rounded(.down)))))
            }
        }
        buf.removeFirst(buf.count - k3)
        history = [Float](repeating: 0, count: n - k3) + buf
        return out
    }
}

/// __WB_POSTFILTER_2026_09_23__ Receiver-side ADPCM hiss suppressor for 0x18 (16 kHz domain).
///
/// WHY: IMA-ADPCM's quantisation noise is WHITE, ~23 dB under the voice overall but only
/// 8-10 dB under it between 2 and 7 kHz, where voice has little energy: a hiss that rides
/// on every word. MEASURED (audio lab, `say` FR/EN voices, 48k→16k→ADPCM→48k): PESQ-WB
/// 2.53/2.62/2.83/2.00 with the codec alone vs 4.62-4.64 for the resampler alone.
///
/// The decoder knows its own noise: each sample was quantised with interval step/4, so the
/// noise variance is (step/4)²/12 per sample. A short-time Wiener filter (16 ms periodic
/// sqrt-Hann, radix-2 FFT, 8 ms hop, decision-directed a-priori SNR, α = 0.98, gain floor
/// 0.2 = −14 dB) removes that noise and nothing else. Measured PESQ-WB 2.50 → 3.57 mean over four
/// voices, STOI unchanged, no level change, no clipping.
///
/// Receiver-only: nothing changes on the wire, so Android 1.6.26 senders benefit and a peer
/// without it still decodes our frames exactly as before. Same algorithm as Android/Desktop
/// `WbPostFilter` (Float math, not part of the wire contract — yet bit-identical with the
/// Kotlin copies, pinned by a shared CRC in the tests). 12 ms of latency. One instance per
/// call, fed in arrival order, like `WbResampler`. Any frame length works; 320-sample frames
/// (every current sender) never under-run the output FIFO.
final class WbPostFilter {
    static let n = 256                // 16 ms analysis window at 16 kHz
    static let hop = 128              // 8 ms
    /// Output primed with this many zeros so 320-sample frames never run dry: 12 ms total delay.
    static let prime = 64
    static let gainFloor: Float = 0.2
    static let alpha: Float = 0.98
    private static let qcap = 8192
    private static let win: [Float] = (0..<n).map { Float(sqrt(0.5 - 0.5 * cos(2 * Double.pi * Double($0) / Double(n)))) }
    private static let twCos: [Float] = (0..<(n / 2)).map { Float(cos(2 * Double.pi * Double($0) / Double(n))) }
    private static let twSin: [Float] = (0..<(n / 2)).map { Float(sin(2 * Double.pi * Double($0) / Double(n))) }
    private static let rev: [Int] = (0..<n).map { i in
        var r = 0, v = i
        for _ in 0..<8 { r = (r << 1) | (v & 1); v >>= 1 }
        return r
    }

    private var inBuf = [Float](repeating: 0, count: n)     // last n input samples, oldest first
    private var varBuf = [Float](repeating: 0, count: n)    // their quantisation-noise variances
    private var ola = [Float](repeating: 0, count: n)
    private var prevS = [Float](repeating: 0, count: n / 2 + 1)
    private var re = [Float](repeating: 0, count: n)
    private var im = [Float](repeating: 0, count: n)
    private var primed = false
    private var pending = 0                                  // new samples waiting for the next hop
    private var q = [Int16](repeating: 0, count: qcap)       // processed output FIFO
    private var qHead = 0
    private var qLen = prime

    func reset() {
        let n = Self.n
        inBuf = [Float](repeating: 0, count: n); varBuf = inBuf; ola = inBuf; re = inBuf; im = inBuf
        prevS = [Float](repeating: 0, count: n / 2 + 1)
        primed = false; pending = 0
        q = [Int16](repeating: 0, count: Self.qcap); qHead = 0; qLen = Self.prime
    }

    /// `pcm16`: 16 kHz samples; `steps`: the step of each (from `WbAdpcm.decode(_:steps:)`).
    func process(_ pcm16: [Int16], steps: [Int32]) -> [Int16] {
        let n = Self.n, hop = Self.hop, qcap = Self.qcap
        guard !pcm16.isEmpty, steps.count >= pcm16.count, pcm16.count <= qcap / 2 else { return pcm16 }
        for i in 0..<pcm16.count {
            inBuf[n - hop + pending] = Float(pcm16[i])
            let s = Float(steps[i]) * 0.25
            varBuf[n - hop + pending] = s * s / 12
            pending += 1
            if pending == hop {
                runFrame()
                for j in 0..<hop {
                    q[(qHead + qLen) % qcap] = Int16(max(-32768, min(32767, (ola[j] + 0.5).rounded(.down))))
                    qLen += 1
                }
                for j in 0..<(n - hop) { ola[j] = ola[j + hop]; inBuf[j] = inBuf[j + hop]; varBuf[j] = varBuf[j + hop] }
                for j in (n - hop)..<n { ola[j] = 0 }
                pending = 0
            }
        }
        var out = [Int16](repeating: 0, count: pcm16.count)
        for i in 0..<out.count {
            // Never short for 320-sample frames (prime); an odd-sized sender gets a zero
            // here once, which then permanently deepens the FIFO by that much.
            if qLen == 0 { continue }
            out[i] = q[qHead]; qHead = (qHead + 1) % qcap; qLen -= 1
        }
        return out
    }

    private static func fft(_ re: UnsafeMutableBufferPointer<Float>, _ im: UnsafeMutableBufferPointer<Float>, inverse: Bool) {
        let n = Self.n
        Self.rev.withUnsafeBufferPointer { rev in
            for i in 0..<n {
                let j = rev[i]
                if j > i {
                    let tr = re[i]; re[i] = re[j]; re[j] = tr
                    let ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
        }
        let sign: Float = inverse ? 1 : -1
        Self.twCos.withUnsafeBufferPointer { twCos in
        Self.twSin.withUnsafeBufferPointer { twSin in
            var len = 2
            while len <= n {
                let halfLen = len / 2, stride = n / len
                var start = 0
                while start < n {
                    for k in 0..<halfLen {
                        let c = twCos[k * stride], s = sign * twSin[k * stride]
                        let a = start + k, b = a + halfLen
                        let xr = re[b] * c - im[b] * s
                        let xi = re[b] * s + im[b] * c
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] = re[a] + xr; im[a] = im[a] + xi
                    }
                    start += len
                }
                len *= 2
            }
        }}
    }

    private func runFrame() {
        let n = Self.n, half = n / 2
        let alpha = Self.alpha, gainFloor = Self.gainFloor
        var isPrimed = primed
        Self.win.withUnsafeBufferPointer { win in
        inBuf.withUnsafeBufferPointer { inB in
        varBuf.withUnsafeBufferPointer { varB in
        re.withUnsafeMutableBufferPointer { re in
        im.withUnsafeMutableBufferPointer { im in
        prevS.withUnsafeMutableBufferPointer { prevS in
        ola.withUnsafeMutableBufferPointer { ola in
            var vsum: Float = 0
            for i in 0..<n { vsum += varB[i]; re[i] = inB[i] * win[i]; im[i] = 0 }
            // Σ win² = n/2 for a periodic sqrt-Hann.
            let noise = max(vsum / Float(n) * Float(half), 1e-3)
            Self.fft(re, im, inverse: false)
            for k in 0...half {
                let p = re[k] * re[k] + im[k] * im[k]
                let post = max(p / noise - 1, 0)
                let prio = isPrimed ? alpha * prevS[k] / noise + (1 - alpha) * post : post
                let g = max(prio / (1 + prio), gainFloor)
                re[k] *= g; im[k] *= g
                if k >= 1 && k < half { re[n - k] *= g; im[n - k] *= g }
                prevS[k] = re[k] * re[k] + im[k] * im[k]
            }
            isPrimed = true
            Self.fft(re, im, inverse: true)
            let inv = 1 / Float(n)
            for j in 0..<n { ola[j] += re[j] * inv * win[j] }
        }}}}}}}
        primed = isPrimed
    }
}

// MARK: - Opus (wire type 0x19) — __OPUS_CODEC_2026_09_23__
//
// WHY: 0x18 (WB-ADPCM) fixed fragmentation but scores PESQ-WB ~2.5 (3.6 with the receiver
// post-filter). Opus scores 4.0-4.4 on the same voices in ~70 B per 20 ms frame.
//
// SOURCE RULE (owner, permanent): Opus only from source vendored in the repo at a pinned
// upstream release with verified checksums, compiled by our own build — here libopus 1.5.2
// (`Vendor/opus`, see `Vendor/README-OSHI.md`) through the tiny C facade
// `Vendor/opus_oshi/oshi_opus.h` (Swift cannot call the variadic opus_*_ctl). NOT Apple's
// AudioToolbox Opus. Android/Desktop use the pure-Java Concentus port (`Vendor/concentus`);
// bitstream interop both ways is measured. The codec has no I/O: AES-GCM is applied to
// these bytes afterwards exactly as for every other wire type.
//
// Payload (before AES-GCM), identical to Android/Desktop `OpusCallCodec.kt`:
//   [0]    (version 1 << 4) | (frameCount - 1)
//   [1..2] seq of the NEWEST frame, UInt16 BE (codec-private 20 ms counter; the outer
//          media seq is shared with control packets so it is not contiguous)
//   then frameCount Opus packets OLDEST first, consecutive seqs; all but the last are
//   prefixed by a 1-byte length, the last runs to the end.
// The older frames repeat the previous packets (≤ 2) so one or two lost packets in a row
// are recovered from the next one; any other hole is concealed by Opus PLC. Budget ≤ 263 B
// ⇒ ≤ 300 B datagram after [type 1][seq 8] + AES-GCM (12 + 16).
//
// Negotiated with capability byte 0x20: 6th trailing byte of the offer, index 5 of the
// accept body. Only sent to a peer that advertised it; any 0x19 received is decoded.
enum OpusWire {
    static let wireType: UInt8 = 0x19
    static let capFlag: UInt8 = 0x20
    static let sampleRate = 48_000
    static let frameSamples = 960
    static let version: UInt8 = 1
    static let headerBytes = 3
    static let maxRedundant = 2
    static let maxPayloadBytes = 263
    /// Longest hole filled with Opus PLC; longer outages are the jitter buffer's.
    static let maxConceal = 5

    /// __OPUS_RESYNC_2026_10_29__ How far behind the high-water mark a packet may be and still
    /// be read as "already played" rather than "the mark is wrong".
    ///
    /// WHY: both marks — `OpusCallDecoder.last` and the caller's `lastKey` — only ever move
    /// FORWARD, and everything at or behind them is discarded. Right for a duplicate or a packet
    /// a few slots late; wrong for one that OVERTOOK the stream. A single frame delivered early
    /// (UDP reordering, the relay's WS mirror of an unproven endpoint, a burst drained from the
    /// /audio buffer) drags the mark ahead, and every packet after it reads as "late" until the
    /// stream grinds back up to it — nothing recovered, nothing logged, the direction simply
    /// silent while the server's counters stay perfect. That is the shape of the 1/5
    /// "theyCouldNotHearMe" on call 97d98d39 (29/10): 4257 pkt / 905 KB delivered one way,
    /// ratio 0.99, udp 100 %, and the peer heard nothing. Measured on the Kotlin twin: one
    /// packet 800 slots early cost 834 of 1500 frames.
    ///
    /// 100 frames = 2 s, past the jitter buffer's reach — a packet that far behind could not be
    /// played anyway, so reading it as a resync costs nothing and buys recovery.
    /// Keep in step with `OpusWire.RESYNC_FRAMES` in the Kotlin copies.
    static let resyncFrames = 100

    static func pack(seq: UInt16, frames: [Data]) -> Data {
        precondition(!frames.isEmpty && frames.count <= 16)
        var out = Data(capacity: headerBytes + frames.reduce(0) { $0 + $1.count + 1 })
        out.append((version << 4) | UInt8(frames.count - 1))
        out.append(UInt8(seq >> 8)); out.append(UInt8(seq & 0xFF))
        for (i, f) in frames.enumerated() {
            if i < frames.count - 1 { out.append(UInt8(min(f.count, 255))) }
            out.append(f)
        }
        return out
    }

    static func unpack(_ data: Data) -> (seq: UInt16, frames: [Data])? {
        let p = [UInt8](data)
        guard p.count > headerBytes, p[0] >> 4 == version else { return nil }
        let n = Int(p[0] & 0x0F) + 1
        let seq = UInt16(p[1]) << 8 | UInt16(p[2])
        var frames: [Data] = []; frames.reserveCapacity(n)
        var o = headerBytes
        for i in 0..<n {
            let len: Int
            if i < n - 1 { guard o < p.count else { return nil }; len = Int(p[o]); o += 1 } else { len = p.count - o }
            guard len > 0, o + len <= p.count else { return nil }
            frames.append(Data(p[o..<(o + len)])); o += len
        }
        return (seq, frames)
    }

    /// Signed distance a - b in the 16-bit seq space.
    static func dist(_ a: UInt16, _ b: UInt16) -> Int { Int(Int16(bitPattern: a &- b)) }
}

/// One per call. Accepts 48 kHz mono PCM of ANY length (a FIFO re-cuts it into 20 ms
/// frames — the mic tap is not always 960 samples) and returns one wire payload per
/// complete frame: usually one, sometimes zero or two. Not thread-safe.
final class OpusCallEncoder {
    private let enc: UnsafeMutableRawPointer
    private let redundancy: Int
    private var fifo: [Int16] = []
    private var history: [Data] = []
    private var seq: UInt16 = 0
    private var scratch = [UInt8](repeating: 0, count: 1275)

    /// 28 kbit/s: libopus 1.5.2's best PESQ-WB per byte on the lab voices (4.27 mean).
    init?(bitrate: Int32 = 28_000, complexity: Int32 = 10, redundancy: Int = OpusWire.maxRedundant) {
        guard let e = oshi_opus_enc_create(bitrate, complexity) else { return nil }
        enc = e; self.redundancy = redundancy
        fifo.reserveCapacity(OpusWire.frameSamples * 8)
    }
    deinit { oshi_opus_enc_destroy(enc) }

    func encode(_ pcm: [Int16]) -> [Data] {
        fifo.append(contentsOf: pcm)   // never more than 959 samples left over between calls
        var out: [Data] = []
        var off = 0
        while fifo.count - off >= OpusWire.frameSamples {
            let len: Int32 = fifo.withUnsafeBufferPointer { src in
                scratch.withUnsafeMutableBufferPointer { dst in
                    oshi_opus_encode(enc, src.baseAddress! + off, Int32(OpusWire.frameSamples),
                                     dst.baseAddress!, Int32(dst.count))
                }
            }
            off += OpusWire.frameSamples
            if len > 0 { out.append(frame(Data(scratch[0..<Int(len)]))) }
        }
        if off > 0 { fifo.removeFirst(off) }
        return out
    }

    private func frame(_ opus: Data) -> Data {
        seq &+= 1
        var frames: [Data] = []
        var size = OpusWire.headerBytes + opus.count
        for old in history.reversed() {
            if old.count > 255 || size + old.count + 1 > OpusWire.maxPayloadBytes { break }
            frames.insert(old, at: 0); size += old.count + 1
        }
        frames.append(opus)
        history.append(opus)
        if history.count > redundancy { history.removeFirst(history.count - redundancy) }
        return OpusWire.pack(seq: seq, frames: frames)
    }
}

/// One per call. Returns the frames to play, oldest first — recovered from redundancy or
/// concealed by Opus PLC for a hole, then the new one. `offset` is the frame's position
/// relative to the packet's newest frame (0 = newest, -1 = the one before, …) so the
/// seq-keyed jitter buffer can file each one. Not thread-safe.
final class OpusCallDecoder {
    private let dec: UnsafeMutableRawPointer
    private var last: UInt16?
    private var buf = [Int16](repeating: 0, count: 5760)
    /// __OPUS_RESYNC_2026_10_29__ How often the stream had to be picked up again.
    private(set) var resyncs = 0

    init?() {
        guard let d = oshi_opus_dec_create() else { return nil }
        dec = d
    }
    deinit { oshi_opus_dec_destroy(dec) }

    func reset() { last = nil; resyncs = 0 }

    /// File one packet's frames under seq keys for the seq-keyed jitter buffer: the newest at
    /// `outerSeq`, older ones at outerSeq-1, … Keys ≤ `lastKey` were already filed, so frames
    /// that cannot get a key of their own are MERGED into the oldest free one (the buffer
    /// plays variable-length entries): nothing is dropped. Empty when no key is free.
    static func keyed(_ frames: [(offset: Int, pcm: [Int16])], outerSeq: UInt64, lastKey: UInt64?) -> [(key: UInt64, pcm: [Int16])] {
        let n = frames.count
        guard n > 0 else { return [] }
        var free = n
        if let lk = lastKey, outerSeq <= lk {
            // __OPUS_RESYNC_2026_10_29__ A key far BEHIND the mark means the mark was set by a
            // packet that overtook the stream, not that this packet is late.
            guard lk - outerSeq > UInt64(OpusWire.resyncFrames) else { return [] }
        } else if let lk = lastKey {
            free = Int(min(outerSeq - lk, UInt64(n)))
        }
        if free >= n { return frames.enumerated().map { (outerSeq - UInt64(n - 1 - $0.offset), $0.element.pcm) } }
        let merge = n - free + 1
        var out: [(key: UInt64, pcm: [Int16])] = [(outerSeq - UInt64(free - 1), frames[0..<merge].flatMap { $0.pcm })]
        for i in merge..<n { out.append((outerSeq - UInt64(n - 1 - i), frames[i].pcm)) }
        return out
    }

    func decode(_ payload: Data) -> [(offset: Int, pcm: [Int16])] {
        guard let p = OpusWire.unpack(payload) else { return [] }
        let n = p.frames.count
        var out: [(offset: Int, pcm: [Int16])] = []
        guard let prev = last else {
            // First packet: only the newest frame — older ones are audio we never started.
            decodeInto(p.frames[n - 1], offset: 0, &out)
            last = p.seq
            return out
        }
        let ahead = OpusWire.dist(p.seq, prev)
        if ahead <= 0 {
            // __OPUS_RESYNC_2026_10_29__ Far behind the mark is not a late packet — it is the
            // live stream, and the mark belongs to one that overtook it. Pick it up from here
            // (newest frame only, as on the first packet) instead of discarding the rest of
            // the call.
            guard ahead < -OpusWire.resyncFrames else { return [] }       // duplicate / late
            decodeInto(p.frames[n - 1], offset: 0, &out)
            last = p.seq
            resyncs += 1
            return out
        }
        for i in 0..<n {
            let fs = p.seq &- UInt16(n - 1 - i)
            let rel = OpusWire.dist(fs, last ?? prev)
            if rel <= 0 { continue }                                        // already played
            if rel > 1 && rel - 1 <= OpusWire.maxConceal {
                for k in stride(from: rel - 1, through: 1, by: -1) {
                    conceal(offset: OpusWire.dist(fs &- UInt16(k), p.seq), &out)
                }
            }
            decodeInto(p.frames[i], offset: OpusWire.dist(fs, p.seq), &out)
            last = fs
        }
        return out
    }

    private func decodeInto(_ opus: Data, offset: Int, _ out: inout [(offset: Int, pcm: [Int16])]) {
        let n: Int32 = opus.withUnsafeBytes { src in
            buf.withUnsafeMutableBufferPointer { dst in
                oshi_opus_decode(dec, src.bindMemory(to: UInt8.self).baseAddress, Int32(opus.count),
                                 dst.baseAddress!, Int32(dst.count))
            }
        }
        if n > 0 { out.append((offset, Array(buf[0..<Int(n)]))) } else { conceal(offset: offset, &out) }
    }

    private func conceal(offset: Int, _ out: inout [(offset: Int, pcm: [Int16])]) {
        let n: Int32 = buf.withUnsafeMutableBufferPointer { dst in
            oshi_opus_decode(dec, nil, 0, dst.baseAddress!, Int32(OpusWire.frameSamples))
        }
        out.append((offset, n > 0 ? Array(buf[0..<Int(n)]) : [Int16](repeating: 0, count: OpusWire.frameSamples)))
    }
}
