package com.oshi.messenger.service.audio

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal

/**
 * __OPUS_CODEC_2026_09_23__ Opus call codec — wire type 0x19, capability byte 0x20.
 *
 * WHY: 0x18 (WB-ADPCM) fixed fragmentation but scores PESQ-WB ~2.5 (3.6 with the receiver
 * post-filter). Opus at 32 kbit/s scores 4.0-4.4 on the same voices, in ~70 B per frame.
 *
 * SOURCE RULE (owner, permanent): Opus only from source vendored in the repo at a pinned
 * upstream release, checksum/signature verified, compiled by our own build. JVM (Android +
 * Desktop): Concentus 1.0.2 pure-Java port (`Vendor/concentus`, see `Vendor/README-OSHI.md`).
 * iOS/Catalyst: libopus 1.5.2 C (`Vendor/opus`). No system codec anywhere, no I/O in the
 * codec; AES-GCM is applied to these bytes afterwards exactly as for every other type.
 * Bitstream interop Concentus ⇄ libopus is measured (audio_lab, 2026-09-23).
 *
 * ANDROID COPY of `OSHI-Desktop/.../call/media/OpusCallCodec.kt` — keep the two
 * identical (package line aside). The wire format is mirrored in iOS `OSHI/OshiCodec.swift`.
 *
 * Payload (before AES-GCM):
 *   [0]    (VERSION << 4) | (frameCount - 1)
 *   [1..2] seq of the NEWEST frame, UInt16 BE (a codec-private 20 ms frame counter — the
 *          outer media seq is shared with video/control packets so it is not contiguous)
 *   then frameCount Opus packets, OLDEST first, consecutive seqs; every one but the last
 *   is prefixed by its length (1 byte), the last runs to the end.
 * The older frames are plain repeats of the previous packets (redundancy, up to
 * [MAX_REDUNDANT]) so an isolated loss — or two in a row — is recovered from the next
 * packet with no extra round trip. Holes nothing covers are concealed by the Opus decoder
 * itself (PLC). Budget: ≤ [MAX_PAYLOAD_BYTES] so the datagram stays ≤ 300 B.
 */
object OpusWire {
    const val WIRE_TYPE = 0x19
    const val CAP_FLAG = 0x20
    const val SAMPLE_RATE = 48_000
    const val FRAME_SAMPLES = 960
    const val VERSION = 1
    const val HEADER_BYTES = 3
    const val MAX_REDUNDANT = 2
    /** + [type 1][seq 8] + AES-GCM nonce 12 + tag 16 = 300 B on the wire. */
    const val MAX_PAYLOAD_BYTES = 263
    /** Longest hole the decoder fills with Opus PLC; longer outages are the jitter buffer's. */
    const val MAX_CONCEAL = 5

    /**
     * __OPUS_RESYNC_2026_10_29__ How far behind the high-water mark a packet may be and
     * still be read as "already played" rather than "the mark is wrong".
     *
     * WHY: both marks here — the decoder's [OpusCallDecoder.last] and the caller's
     * `opusRxLastKey` — only ever move FORWARD, and everything at or behind them is
     * discarded. That is right for a duplicate or a packet a few slots late. It is wrong
     * for a packet that OVERTOOK the stream: one frame delivered early (UDP reordering,
     * the relay's WS mirror of an unproven endpoint, a burst drained from the /audio
     * buffer) pushes the mark far ahead, and every packet that follows then reads as
     * "late" until the stream grinds back up to it. Nothing recovered, nothing logged:
     * the call simply goes silent in one direction while the counters stay perfect.
     * Measured on the shipped code: one packet 800 slots early cost 834 of 1500 frames.
     *
     * 100 frames = 2 s, past the jitter buffer's reach — a packet that far behind could
     * not be played anyway, so reading it as a resync costs nothing and buys recovery.
     */
    const val RESYNC_FRAMES = 100

    class Parsed(val seq: Int, val frames: List<ByteArray>)

    fun pack(seq: Int, frames: List<ByteArray>): ByteArray {
        require(frames.isNotEmpty() && frames.size <= 16)
        var size = HEADER_BYTES
        for (i in frames.indices) size += frames[i].size + if (i < frames.size - 1) 1 else 0
        val out = ByteArray(size)
        out[0] = ((VERSION shl 4) or (frames.size - 1)).toByte()
        out[1] = (seq shr 8).toByte(); out[2] = seq.toByte()
        var o = HEADER_BYTES
        for (i in frames.indices) {
            val f = frames[i]
            if (i < frames.size - 1) { require(f.size <= 255); out[o++] = f.size.toByte() }
            System.arraycopy(f, 0, out, o, f.size); o += f.size
        }
        return out
    }

    fun unpack(p: ByteArray): Parsed? {
        if (p.size <= HEADER_BYTES) return null
        val h = p[0].toInt() and 0xFF
        if (h shr 4 != VERSION) return null
        val n = (h and 0x0F) + 1
        val seq = ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF)
        val frames = ArrayList<ByteArray>(n)
        var o = HEADER_BYTES
        for (i in 0 until n) {
            val len = if (i < n - 1) { if (o >= p.size) return null; p[o++].toInt() and 0xFF } else p.size - o
            if (len <= 0 || o + len > p.size) return null
            frames.add(p.copyOfRange(o, o + len)); o += len
        }
        return Parsed(seq, frames)
    }

    /** Signed distance a - b in the 16-bit seq space. */
    fun dist(a: Int, b: Int): Int = (((a - b) and 0xFFFF) xor 0x8000) - 0x8000
}

/**
 * Stateful: one per call and direction. Accepts 48 kHz mono PCM of ANY length (a FIFO
 * re-cuts it into 20 ms frames) and returns one wire payload per complete frame — usually
 * exactly one, occasionally zero or two when the capture buffers are not 20 ms.
 */
class OpusCallEncoder(
    bitrate: Int = 32_000,
    complexity: Int = 10,
    private val redundancy: Int = OpusWire.MAX_REDUNDANT,
    /**
     * __CALL_FLUIDITY_2026_11_21__ Opus in-band FEC (LBRR) with this packet-loss hint, 0 = off.
     * Inside the Opus bitstream: every decoder plays such a frame normally and ignores the
     * FEC unless asked for it, so turning it on never breaks a peer.
     */
    inbandFecLossPct: Int = 0,
) {
    private val enc = OpusEncoder(OpusWire.SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        setBitrate(bitrate)
        setComplexity(complexity)
        setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
        setUseVBR(true)
        setUseConstrainedVBR(true)
        setUseInbandFEC(inbandFecLossPct > 0)
        if (inbandFecLossPct > 0) setPacketLossPercent(inbandFecLossPct.coerceIn(0, 100))
        setUseDTX(false)
    }
    private var fifo = ShortArray(OpusWire.FRAME_SAMPLES * 4)
    private var fifoLen = 0
    private val history = ArrayDeque<ByteArray>()
    private var seq = 0
    private val scratch = ByteArray(1275)

    fun encode(pcm: ShortArray): List<ByteArray> {
        // Never more than 959 samples are left over between calls, so this only grows for
        // an unusually large capture buffer (e.g. 200 ms after an audio-session hiccup).
        if (fifoLen + pcm.size > fifo.size) fifo = fifo.copyOf(fifoLen + pcm.size)
        System.arraycopy(pcm, 0, fifo, fifoLen, pcm.size); fifoLen += pcm.size
        val out = ArrayList<ByteArray>(1)
        var off = 0
        while (fifoLen - off >= OpusWire.FRAME_SAMPLES) {
            val len = enc.encode(fifo, off, OpusWire.FRAME_SAMPLES, scratch, 0, scratch.size)
            off += OpusWire.FRAME_SAMPLES
            if (len > 0) out.add(frame(scratch.copyOf(len)))
        }
        if (off > 0) { System.arraycopy(fifo, off, fifo, 0, fifoLen - off); fifoLen -= off }
        return out
    }

    /** Little-endian 16-bit PCM bytes in (the capture format), payloads out. */
    fun encodePcm48(le: ByteArray): List<ByteArray> =
        encode(ShortArray(le.size / 2) { ((le[2 * it + 1].toInt() shl 8) or (le[2 * it].toInt() and 0xFF)).toShort() })

    private fun frame(opus: ByteArray): ByteArray {
        seq = (seq + 1) and 0xFFFF
        // Newest older frames first, while they fit (≤ 255 B each, total within budget).
        val frames = ArrayList<ByteArray>(redundancy + 1)
        var size = OpusWire.HEADER_BYTES + opus.size
        for (old in history.reversed()) {
            if (old.size > 255 || size + old.size + 1 > OpusWire.MAX_PAYLOAD_BYTES) break
            frames.add(0, old); size += old.size + 1
        }
        frames.add(opus)
        history.addLast(opus)
        while (history.size > redundancy) history.removeFirst()
        return OpusWire.pack(seq, frames)
    }
}

/**
 * Stateful: one per call and direction. Returns the frames to play, oldest first: frames
 * recovered from the redundancy or concealed by Opus PLC for a hole, then the new one.
 * [Frame.offset] is the frame's position relative to the packet's newest frame (0 = the
 * newest, -1 = the one before, …) so a seq-keyed jitter buffer can file each one.
 */
class OpusCallDecoder {
    class Frame(val offset: Int, val pcm: ShortArray) {
        fun le(): ByteArray {
            val b = ByteArray(pcm.size * 2)
            for (i in pcm.indices) { val v = pcm[i].toInt(); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
            return b
        }
    }

    companion object {
        /**
         * File one packet's frames under seq keys for a seq-keyed jitter buffer: the newest
         * at [outerSeq], the older ones at outerSeq-1, … Keys ≤ [lastKey] were already
         * filed, so frames that cannot get a key of their own are MERGED into the oldest
         * free one (the jitter buffer plays variable-length entries): nothing is dropped.
         * Returns nothing when the packet has no free key at all (stale / duplicate).
         */
        fun keyed(frames: List<Frame>, outerSeq: Long, lastKey: Long): List<Pair<Long, ShortArray>> {
            val n = frames.size
            if (n == 0) return emptyList()
            // __OPUS_RESYNC_2026_10_29__ a key far BEHIND the mark means the mark was set
            // by a packet that overtook the stream, not that this packet is late.
            val behind = if (lastKey < 0) 0L else outerSeq - lastKey
            val free = if (lastKey < 0 || behind < -OpusWire.RESYNC_FRAMES) n.toLong() else behind
            if (free <= 0) return emptyList()
            if (free >= n) return frames.mapIndexed { i, f -> (outerSeq - (n - 1 - i)) to f.pcm }
            val k = free.toInt()
            val merge = n - k + 1
            val first = ShortArray(frames.take(merge).sumOf { it.pcm.size })
            var o = 0
            for (f in frames.take(merge)) { System.arraycopy(f.pcm, 0, first, o, f.pcm.size); o += f.pcm.size }
            val out = ArrayList<Pair<Long, ShortArray>>(k)
            out.add((outerSeq - (k - 1)) to first)
            for (i in merge until n) out.add((outerSeq - (n - 1 - i)) to frames[i].pcm)
            return out
        }
    }

    private val dec = OpusDecoder(OpusWire.SAMPLE_RATE, 1)
    private var last = -1
    private val buf = ShortArray(5760)

    /** __OPUS_RESYNC_2026_10_29__ How often the stream had to be picked up again. */
    var resyncs: Int = 0
        private set

    fun reset() { last = -1; resyncs = 0 }

    fun decode(payload: ByteArray): List<Frame> {
        val p = OpusWire.unpack(payload) ?: return emptyList()
        val n = p.frames.size
        val out = ArrayList<Frame>(n + 1)
        if (last < 0) {
            // First packet: only the newest frame — older ones are audio we never started.
            decodeInto(p.frames[n - 1], 0, out)
            last = p.seq
            return out
        }
        val ahead = OpusWire.dist(p.seq, last)
        if (ahead <= 0) {
            // __OPUS_RESYNC_2026_10_29__ Far behind the mark is not a late packet — it is
            // the live stream, and the mark belongs to one that overtook it. Start again
            // from here (newest frame only, as on the first packet) instead of discarding
            // the rest of the call.
            if (ahead < -OpusWire.RESYNC_FRAMES) {
                decodeInto(p.frames[n - 1], 0, out)
                last = p.seq
                resyncs++
            }
            return out                                                     // duplicate / late
        }
        for (i in 0 until n) {
            val fs = (p.seq - (n - 1 - i)) and 0xFFFF
            val rel = OpusWire.dist(fs, last)
            if (rel <= 0) continue                                             // already played
            if (rel > 1 && rel - 1 <= OpusWire.MAX_CONCEAL) {
                for (k in rel - 1 downTo 1) conceal(OpusWire.dist((fs - k) and 0xFFFF, p.seq), out)
            }
            decodeInto(p.frames[i], OpusWire.dist(fs, p.seq), out)
            last = fs
        }
        return out
    }

    private fun decodeInto(opus: ByteArray, offset: Int, out: MutableList<Frame>) {
        val n = try { dec.decode(opus, 0, opus.size, buf, 0, buf.size, false) } catch (_: Throwable) { -1 }
        if (n > 0) out.add(Frame(offset, buf.copyOf(n)))
        else conceal(offset, out)
    }

    private fun conceal(offset: Int, out: MutableList<Frame>) {
        val n = try { dec.decode(null, 0, 0, buf, 0, OpusWire.FRAME_SAMPLES, false) } catch (_: Throwable) { -1 }
        out.add(Frame(offset, if (n > 0) buf.copyOf(n) else ShortArray(OpusWire.FRAME_SAMPLES)))
    }
}

/**
 * __CALL_FLUIDITY_2026_11_21__ The Opus half of [AdaptiveJitterBuffer]: frames are queued
 * ENCODED and decoded here at playout time, in playout order, so the decoder's state always
 * follows what the speaker actually plays.
 *
 * Concealment of a missing frame: when the NEXT frame is already queued, it is decoded with
 * `decode_fec = true` — if the sender put in-band FEC (LBRR) in it, that recovers the lost
 * frame; if not, libopus/Concentus fall back to their own PLC, so this is never worse than
 * plain PLC. With nothing queued, plain Opus PLC.
 */
class OpusPlayoutDecoder : AdaptiveJitterBuffer.FrameDecoder {
    private var dec = OpusDecoder(OpusWire.SAMPLE_RATE, 1)
    private val buf = ShortArray(5760)

    /** Concealed frames that came from the next frame's in-band FEC path. Diagnostic. */
    var fecDecodes = 0L; private set

    override fun decode(frame: ByteArray): ShortArray {
        val n = try { dec.decode(frame, 0, frame.size, buf, 0, buf.size, false) } catch (_: Throwable) { -1 }
        return if (n > 0) buf.copyOf(n) else ShortArray(0)
    }

    override fun conceal(next: ByteArray?): ShortArray? {
        val n = try {
            if (next != null) dec.decode(next, 0, next.size, buf, 0, OpusWire.FRAME_SAMPLES, true).also { if (it > 0) fecDecodes++ }
            else dec.decode(null, 0, 0, buf, 0, OpusWire.FRAME_SAMPLES, false)
        } catch (_: Throwable) { -1 }
        return if (n > 0) buf.copyOf(n) else null
    }

    override fun reset() { dec = OpusDecoder(OpusWire.SAMPLE_RATE, 1) }

    companion object {
        /**
         * Queue every frame a 0x19 payload carries (the newest and its redundant copies) in
         * [jb], keyed by the codec's own 20 ms counter unwrapped against [unwrap]. Returns
         * false for a payload that does not parse.
         */
        fun file(payload: ByteArray, jb: AdaptiveJitterBuffer, unwrap: SeqUnwrapper): Boolean {
            val p = OpusWire.unpack(payload) ?: return false
            // Several transports deliver on their own threads: one packet is filed atomically.
            synchronized(jb) {
                val newest = unwrap.unwrap(p.seq)
                val n = p.frames.size
                for (i in 0 until n) jb.put(newest - (n - 1 - i), p.frames[i])
            }
            return true
        }
    }
}

/**
 * 16-bit codec seq → monotonic Long, relative to the last one seen.
 *
 * __SEQ_ORIGIN_2026_11_22__ The first seq maps to [ORIGIN] + seq16, not seq16: a stream first
 * heard at seq 0 or 1 (or right after the 16-bit wrap) filed its redundant copies under −1,
 * −2, and −1 is [AdaptiveJitterBuffer]'s "nothing yet" mark — the first frames were lost.
 * Only differences between keys matter to the buffer, so the offset changes nothing else.
 * Same constant as iOS `SeqUnwrapper.origin` (OSHI/OshiCodec.swift) — keep them equal.
 */
class SeqUnwrapper {
    private var last = -1
    private var base = 0L
    @Synchronized
    fun unwrap(seq16: Int): Long {
        if (last < 0) { last = seq16; base = ORIGIN + seq16; return base }
        base += OpusWire.dist(seq16, last)
        last = seq16
        return base
    }
    @Synchronized
    fun reset() { last = -1; base = 0L }

    companion object {
        const val ORIGIN = 1L shl 32
    }
}
