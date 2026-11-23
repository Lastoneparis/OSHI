package com.oshi.messenger.service.audio

/**
 * __WB_ADPCM_CODEC_2026_09_23__ Wideband IMA-ADPCM — wire type 0x18, capability byte 0x10.
 *
 * WHY: an Android phone and an iPhone had NO codec in common. AAC-ELD bitstreams are not
 * interoperable between MediaCodec and AudioToolbox, OshiCodec (0x16) is disabled (LPC
 * artefacts), the 16 kHz PCM path (0x17) is disabled (biquad precision) — so every
 * cross-platform frame was raw 48 kHz PCM: 1957 B per 20 ms datagram, 782 kbit/s, two IP
 * fragments each. MEASURED B37DC742 (2026-09-23, iPhone 5G+VPN ⇄ Android): 620 pkts /
 * 1185 KB ⇒ 1911 B/pkt. VPNs and carriers drop fragments, so direct P2P black-holed and the
 * UDP relay lost frames.
 *
 * Pure integer code, bit-identical with iOS `OSHI/OshiCodec.swift` (enum WbAdpcm) and
 * Desktop. 16 kHz wideband, 4 bit/sample: one 20 ms frame = 320 samples ⇒ 3-byte header +
 * 160 B = 163 B payload ⇒ 200 B on the wire after [type 1][seq 8] + AES-GCM (12 + 16).
 *
 * Payload: [pred Int16 BE][stepIndex UInt8][nibbles: sample 2k in the LOW nibble, 2k+1 in
 * the HIGH nibble]. The header is the encoder state at frame start, so every packet decodes
 * on its own — a lost packet never desynchronises the decoder. Standard IMA/DVI tables.
 */
object WbAdpcmCodec {
    const val WIRE_TYPE: Byte = 0x18
    const val CAP_FLAG: Int = 0x10
    const val SAMPLE_RATE = 16_000
    const val HEADER_BYTES = 3

    private val STEP = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
        253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
        1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
        3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
        12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )
    private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)

    /** Encoder state carried across frames (continuity); written into every header. */
    class EncoderState { var predictor = 0; var index = 0 }

    fun encode(samples: ShortArray, state: EncoderState): ByteArray {
        val out = ByteArray(HEADER_BYTES + (samples.size + 1) / 2)
        out[0] = (state.predictor shr 8).toByte()
        out[1] = state.predictor.toByte()
        out[2] = state.index.toByte()
        var pred = state.predictor
        var index = state.index
        var o = HEADER_BYTES
        for (i in samples.indices) {
            var step = STEP[index]
            var diff = samples[i].toInt() - pred
            var nib = 0
            if (diff < 0) { nib = 8; diff = -diff }
            var vp = step shr 3
            if (diff >= step) { nib = nib or 4; diff -= step; vp += step }
            step = step shr 1
            if (diff >= step) { nib = nib or 2; diff -= step; vp += step }
            step = step shr 1
            if (diff >= step) { nib = nib or 1; vp += step }
            pred = if (nib and 8 != 0) pred - vp else pred + vp
            pred = pred.coerceIn(-32768, 32767)
            index = (index + INDEX[nib and 7]).coerceIn(0, 88)
            if (i and 1 == 0) out[o] = nib.toByte()
            else { out[o] = (out[o].toInt() or (nib shl 4)).toByte(); o++ }
        }
        state.predictor = pred
        state.index = index
        return out
    }

    fun decode(data: ByteArray): ShortArray = decode(data, null)

    /**
     * Decode, and when [steps] is non-null also fill it with the quantiser step used for
     * each output sample (the decoder knows its own quantisation noise — [WbPostFilter]
     * uses it). The samples are bit-identical to [decode]'s.
     */
    fun decode(data: ByteArray, steps: IntArray?): ShortArray {
        if (data.size <= HEADER_BYTES) return ShortArray(0)
        var pred = ((data[0].toInt() shl 8) or (data[1].toInt() and 0xFF)).toShort().toInt()
        var index = (data[2].toInt() and 0xFF).coerceIn(0, 88)
        val out = ShortArray((data.size - HEADER_BYTES) * 2)
        var o = 0
        for (i in HEADER_BYTES until data.size) {
            val b = data[i].toInt() and 0xFF
            for (nib in intArrayOf(b and 0x0F, b shr 4)) {
                val step = STEP[index]
                var vp = step shr 3
                if (nib and 4 != 0) vp += step
                if (nib and 2 != 0) vp += step shr 1
                if (nib and 1 != 0) vp += step shr 2
                pred = if (nib and 8 != 0) pred - vp else pred + vp
                pred = pred.coerceIn(-32768, 32767)
                index = (index + INDEX[nib and 7]).coerceIn(0, 88)
                if (steps != null && o < steps.size) steps[o] = step
                out[o++] = pred.toShort()
            }
        }
        return out
    }

    /** 48 kHz Int16 LE bytes → one encoded 0x18 payload. */
    fun encodePcm48(pcm48Le: ByteArray, resampler: WbResampler, state: EncoderState): ByteArray {
        val n = pcm48Le.size / 2
        val s = ShortArray(n) { ((pcm48Le[2 * it + 1].toInt() shl 8) or (pcm48Le[2 * it].toInt() and 0xFF)).toShort() }
        return encode(resampler.down(s), state)
    }

    /**
     * One 0x18 payload → 48 kHz Int16 LE bytes (empty on malformed input).
     * [postFilter]: the receiver's [WbPostFilter] (one per call, like the resampler); null
     * plays the raw ADPCM output. Receiver-side only — the wire format is untouched.
     */
    fun decodeToPcm48(payload: ByteArray, resampler: WbResampler, postFilter: WbPostFilter? = null): ByteArray {
        val pcm16 = if (postFilter == null) decode(payload) else {
            val steps = IntArray((payload.size - HEADER_BYTES).coerceAtLeast(0) * 2)
            postFilter.process(decode(payload, steps), steps)
        }
        val up = resampler.up(pcm16)
        val out = ByteArray(up.size * 2)
        for (i in up.indices) {
            out[2 * i] = up[i].toInt().toByte()
            out[2 * i + 1] = (up[i].toInt() shr 8).toByte()
        }
        return out
    }
}

/**
 * 48 kHz ⇄ 16 kHz polyphase FIR resampler (windowed-sinc low-pass, 7 kHz cutoff, 48 taps,
 * Blackman). Float math, stateful across frames; NOT part of the wire contract. Replaces the
 * 0x17 biquad whose Q30 state truncation crackled on real voice. One instance per direction.
 */
class WbResampler {
    companion object {
        const val TAPS = 48
        val H: FloatArray = run {
            val n = TAPS
            val fc = 7_000.0 / 48_000.0
            val m = (n - 1).toDouble()
            val h = DoubleArray(n) { i ->
                val x = i - m / 2
                val sinc = if (x == 0.0) 2 * fc else Math.sin(2 * Math.PI * fc * x) / (Math.PI * x)
                val w = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / m) + 0.08 * Math.cos(4 * Math.PI * i / m)
                sinc * w
            }
            val sum = h.sum()
            FloatArray(n) { (h[it] / sum).toFloat() }
        }
    }

    private var history = FloatArray(TAPS)   // most recent input last

    fun reset() { history = FloatArray(TAPS) }

    /** 48 kHz → 16 kHz (count/3 samples). */
    fun down(input: ShortArray): ShortArray {
        val n = TAPS
        val buf = FloatArray(n + input.size)
        System.arraycopy(history, 0, buf, 0, n)
        for (i in input.indices) buf[n + i] = input[i].toFloat()
        val out = ShortArray(input.size / 3)
        var i = n + 2
        var o = 0
        while (i < buf.size && o < out.size) {
            var acc = 0f
            for (k in 0 until n) acc += H[k] * buf[i - k]
            out[o++] = Math.round(acc).coerceIn(-32768, 32767).toShort()
            i += 3
        }
        history = buf.copyOfRange(buf.size - n, buf.size)
        return if (o == out.size) out else out.copyOf(o)
    }

    /** 16 kHz → 48 kHz (count×3 samples): zero-stuff ×3 then low-pass ×3. */
    fun up(input: ShortArray): ShortArray {
        val n = TAPS
        val k3 = n / 3
        val buf = FloatArray(k3 + input.size)
        System.arraycopy(history, n - k3, buf, 0, k3)
        for (i in input.indices) buf[k3 + i] = input[i].toFloat()
        val out = ShortArray(input.size * 3)
        var o = 0
        for (j in input.indices) {
            val jj = j + k3
            for (p in 0 until 3) {
                var acc = 0f
                var k = p
                while (k < n) {
                    val src = jj - (k - p) / 3
                    if (src >= 0) acc += H[k] * buf[src]
                    k += 3
                }
                out[o++] = Math.round(3f * acc).coerceIn(-32768, 32767).toShort()
            }
        }
        val h = FloatArray(n)
        System.arraycopy(buf, buf.size - k3, h, n - k3, k3)
        history = h
        return out
    }
}

/**
 * __WB_POSTFILTER_2026_09_23__ Receiver-side ADPCM hiss suppressor for 0x18 (16 kHz domain).
 *
 * WHY: IMA-ADPCM's quantisation noise is WHITE, ~23 dB under the voice overall but only
 * 8-10 dB under it between 2 and 7 kHz, where voice has little energy: a hiss that rides
 * on every word. MEASURED (scratchpad audio_lab, `say` FR/EN voices, 48k→16k→ADPCM→48k):
 * PESQ-WB 2.53/2.62/2.83/2.00 with the codec alone vs 4.62-4.64 for the resampler alone —
 * the resampler is transparent, the ADPCM noise is the whole loss.
 *
 * The decoder knows its own noise: each sample was quantised with interval step/4, so the
 * noise variance is (step/4)²/12, exactly, per sample. A short-time Wiener filter (20 ms
 * periodic sqrt-Hann (16 ms, radix-2 FFT), 8 ms hop, decision-directed a-priori SNR,
 * α = 0.98, gain floor 0.2 = −14 dB) removes that noise and nothing else — a microphone's own background noise
 * is "signal" to it and stays. Measured on the full 48k→16k→ADPCM→48k path, four voices:
 * PESQ-WB 2.53/2.62/2.83/2.00 → 3.69/3.69/3.97/2.91 (mean 2.50 → 3.57), STOI unchanged
 * (0.966-0.997 → 0.981-0.996), no level change (−0.03 dB), no clipping, no DC.
 *
 * Receiver-only, so it changes nothing on the wire: 1.6.26 senders benefit, and a peer
 * without it still decodes our frames exactly as before. NOT part of the bit-exact
 * contract (Float math). Costs 192 samples (12 ms) of latency and ~10 µs per 20 ms frame.
 * One instance per call, fed in arrival order, like [WbResampler]. Any frame length works;
 * 320-sample frames (every current sender) never under-run the output FIFO.
 */
class WbPostFilter {
    companion object {
        const val N = 256                 // 16 ms analysis window at 16 kHz
        const val HOP = 128               // 8 ms
        /** Output primed with this many zeros so 320-sample frames never run dry: 12 ms total delay. */
        const val PRIME = 64
        const val GAIN_FLOOR = 0.2f
        const val ALPHA = 0.98f
        private const val LOG2N = 8
        private const val QCAP = 8192
        private val WIN = FloatArray(N) { Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * it / N)).toFloat() }
        private val TW_COS = FloatArray(N / 2) { Math.cos(2 * Math.PI * it / N).toFloat() }
        private val TW_SIN = FloatArray(N / 2) { Math.sin(2 * Math.PI * it / N).toFloat() }
        private val REV = IntArray(N) { Integer.reverse(it) ushr (32 - LOG2N) }
    }

    private val inBuf = FloatArray(N)       // last N input samples, oldest first
    private val varBuf = FloatArray(N)      // their quantisation-noise variances
    private val ola = FloatArray(N)
    private val prevS = FloatArray(N / 2 + 1)
    private var primed = false
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private var pending = 0                 // new samples waiting for the next hop
    private val q = ShortArray(QCAP)        // processed output FIFO
    private var qHead = 0
    private var qLen = PRIME

    fun reset() {
        inBuf.fill(0f); varBuf.fill(0f); ola.fill(0f); prevS.fill(0f); primed = false
        pending = 0; q.fill(0); qHead = 0; qLen = PRIME
    }

    /** [pcm16] 16 kHz samples, [steps] the step of each (from [WbAdpcmCodec.decode]). */
    fun process(pcm16: ShortArray, steps: IntArray): ShortArray {
        if (pcm16.isEmpty() || steps.size < pcm16.size || pcm16.size > QCAP / 2) return pcm16
        for (i in pcm16.indices) {
            inBuf[N - HOP + pending] = pcm16[i].toFloat()
            val s = steps[i] * 0.25f
            varBuf[N - HOP + pending] = s * s / 12f
            if (++pending == HOP) {
                runFrame()
                for (j in 0 until HOP) {
                    q[(qHead + qLen) % QCAP] = Math.floor(ola[j] + 0.5).toInt().coerceIn(-32768, 32767).toShort()
                    qLen++
                }
                System.arraycopy(ola, HOP, ola, 0, N - HOP)
                java.util.Arrays.fill(ola, N - HOP, N, 0f)
                System.arraycopy(inBuf, HOP, inBuf, 0, N - HOP)
                System.arraycopy(varBuf, HOP, varBuf, 0, N - HOP)
                pending = 0
            }
        }
        val out = ShortArray(pcm16.size)
        for (i in out.indices) {
            // Never short for 320-sample frames (PRIME); an odd-sized sender gets a zero
            // here once, which then permanently deepens the FIFO by that much.
            if (qLen == 0) continue
            out[i] = q[qHead]; qHead = (qHead + 1) % QCAP; qLen--
        }
        return out
    }

    private fun fft(inverse: Boolean) {
        for (i in 0 until N) {
            val j = REV[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        val sign = if (inverse) 1f else -1f
        var len = 2
        while (len <= N) {
            val halfLen = len / 2
            val stride = N / len
            var start = 0
            while (start < N) {
                for (k in 0 until halfLen) {
                    val c = TW_COS[k * stride]; val s = sign * TW_SIN[k * stride]
                    val a = start + k; val b = a + halfLen
                    val xr = re[b] * c - im[b] * s
                    val xi = re[b] * s + im[b] * c
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] = re[a] + xr; im[a] = im[a] + xi
                }
                start += len
            }
            len *= 2
        }
    }

    private fun runFrame() {
        var vsum = 0f
        for (i in 0 until N) { vsum += varBuf[i]; re[i] = inBuf[i] * WIN[i]; im[i] = 0f }
        // Σ WIN² = N/2 for a periodic sqrt-Hann.
        val noise = maxOf(vsum / N * (N / 2), 1e-3f)
        fft(false)
        val half = N / 2
        for (k in 0..half) {
            val p = re[k] * re[k] + im[k] * im[k]
            val post = maxOf(p / noise - 1f, 0f)
            val prio = if (primed) ALPHA * prevS[k] / noise + (1f - ALPHA) * post else post
            val g = maxOf(prio / (1f + prio), GAIN_FLOOR)
            re[k] *= g; im[k] *= g
            if (k in 1 until half) { re[N - k] *= g; im[N - k] *= g }
            prevS[k] = re[k] * re[k] + im[k] * im[k]
        }
        primed = true
        fft(true)
        val inv = 1f / N
        for (n in 0 until N) ola[n] += re[n] * inv * WIN[n]
    }
}
