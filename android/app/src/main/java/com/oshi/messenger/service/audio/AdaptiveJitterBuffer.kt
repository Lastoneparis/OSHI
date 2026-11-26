package com.oshi.messenger.service.audio

import kotlin.math.sqrt

/**
 * __CALL_FLUIDITY_2026_11_21__ A clock-driven, adaptive playout buffer (NetEQ's ideas, sized
 * for this app): frames go in keyed by sequence, exactly 20 ms of PCM comes out per [pull].
 *
 * WHY (measured, `CallFluidityBench` / `FluiditySimBench`): the receive chains it replaces
 * decide "late" by a slot clock that keeps ticking while nothing arrives. A packet 30 ms
 * late was therefore already concealed and then DISCARDED — the content is lost even
 * though it arrived — and every jitter spike cost an audible hole. Here a slot with no
 * frame and nothing behind it is NOT given up: it is concealed (EXPAND) and the buffer
 * waits for it, so a late frame still plays and the buffer has grown by exactly the
 * lateness the network showed. When frames are queued beyond the target the buffer
 * shrinks back (ACCELERATE): a quiet frame is skipped whole, a voiced one loses one pitch
 * period with a cross-fade, and a backlog far beyond the target (a stalled radio that
 * delivers 1.5 s at once) is cut back in one step.
 *
 * Rules, in the order [produce] applies them:
 *  1. Priming: silence until [targetMs] of audio is queued (at the start and after a resync).
 *  2. The next frame is here → play it (cross-faded out of concealment if one preceded).
 *  3. It is missing and NOTHING later is queued → EXPAND: one concealment frame, the slot
 *     does not advance (the frame is late, not lost).
 *  4. It is missing and a later frame IS queued → the frames in between are lost; the
 *     concealment already played while expanding stands in for as many of them as it
 *     lasted (no added delay after an outage), the rest are concealed one per slot, and a
 *     hole longer than [JUMP_FRAMES] is jumped.
 *
 * Target delay: P95 − min of the per-frame relative delay (FIRST arrival of a frame, from
 * whichever packet carried it, − seq × frame; plus frames that arrived after their slot) over the
 * last [WINDOW_MS], + one frame (so the next packet's redundant copy of a lost frame lands
 * before its slot), clamped to [[MIN_TARGET_MS], [MAX_TARGET_MS]]. Spikes more than
 * [SPIKE_MS] above the median are left out of the estimate: a stall is handled by EXPAND
 * and the backlog cut, not by holding a long delay for the next [WINDOW_MS].
 *
 * Frames are queued ENCODED and decoded at playout by a [FrameDecoder]: for Opus
 * ([OpusPlayoutDecoder]) a missing frame is concealed by the codec itself — in-band FEC from
 * the next frame when it is queued, Opus PLC otherwise. Codecs without their own
 * concealment ([PcmFrames]) get pitch tiling (Android's `__PITCH_PLC_2026_09_23__`, which
 * measured better than frame repetition). After five concealed frames: silence.
 *
 * Thread-safety: every public method is synchronized (receive thread puts, render thread
 * pulls). KEEP IDENTICAL with the Android copy (package line aside).
 */
class AdaptiveJitterBuffer(
    private val decoder: FrameDecoder = PcmFrames,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val minTargetMs: Int = MIN_TARGET_MS,
    private val maxTargetMs: Int = MAX_TARGET_MS,
) {
    companion object {
        const val FRAME_SAMPLES = 960
        const val SAMPLES_PER_MS = 48
        const val MIN_TARGET_MS = 20
        const val MAX_TARGET_MS = 400
        const val WINDOW_MS = 4_000L
        const val SPIKE_MS = 350
        /** Added to the measured spread (the 20 ms pull granularity). */
        const val TARGET_MARGIN_MS = 0
        /** A lost run longer than this (100 ms) is skipped instead of concealed slot by slot. */
        const val JUMP_FRAMES = 5
        /** Older than this behind the playout point: not a late frame, a new stream. */
        const val RESYNC_FRAMES = 100
        /** Queue cap (5 s of 20 ms frames): a flood is not worth buffering. */
        const val MAX_FRAMES = 250
        /** Shrink starts when the queue exceeds the target by this much … */
        const val ACCEL_ABOVE_MS = 20
        /** … for this long, and stops at target + [ACCEL_STOP_MS]. */
        const val ACCEL_HOLD_MS = 200L
        /** Window of [floorMs]. */
        const val FLOOR_WINDOW_MS = 1_000L
        const val ACCEL_STOP_MS = 10
        /** Voiced frames are shortened only after the excess lasted this long (pauses first). */
        const val ACCEL_FORCE_MS = 1_000L
        /** Beyond the target by this much: cut the backlog in one step. */
        const val FLUSH_ABOVE_MS = 300
        /** RMS below this (−40 dBFS) is a pause: safe to skip whole. */
        const val QUIET_RMS = 330.0
        const val CROSSFADE = 96
        private val PLC_GAINS = floatArrayOf(1.0f, 0.8f, 0.6f, 0.4f, 0.2f, 0.0f)
        private const val PITCH_MIN = 96   // 500 Hz
        private const val PITCH_MAX = 720  // 67 Hz
        private const val PITCH_WIN = 240
    }

    /**
     * Turns a queued frame into PCM AT PLAYOUT TIME, in playout order.
     *
     * WHY decode at playout (measured, jitter 100 ms, `FluiditySimBench`): decoding on
     * arrival meant a packet that overtook four others made the decoder fill those four
     * with PLC on the spot — and then discard the real ones when they arrived 30 ms later,
     * still in time. Queued encoded, a frame is usable until its slot is played, and the
     * codec's own concealment runs exactly for the slots that are really missing.
     */
    interface FrameDecoder {
        /** One queued frame → PCM; empty if it does not decode (then concealed). */
        fun decode(frame: ByteArray): ShortArray
        /**
         * The codec's concealment for one missing frame, given the frame that follows it if
         * that one is queued (Opus in-band FEC), or null to use the buffer's own pitch PLC.
         */
        fun conceal(next: ByteArray?): ShortArray?
        fun reset() {}
    }

    /** Frames that ARE PCM (0x15 raw, decoded 0x18): Int16 little-endian; generic PLC. */
    object PcmFrames : FrameDecoder {
        override fun decode(frame: ByteArray): ShortArray =
            ShortArray(frame.size / 2) { ((frame[2 * it + 1].toInt() shl 8) or (frame[2 * it].toInt() and 0xFF)).toShort() }
        override fun conceal(next: ByteArray?): ShortArray? = null
    }

    /** What the last [pull] was made of. */
    enum class Kind { PRIMING, NORMAL, EXPAND, LOSS_CONCEAL, SILENCE }

    private val frames = java.util.TreeMap<Long, ByteArray>()
    /** The last run of concealment came from the codec (it smooths its own way back out). */
    private var codecConcealed = false
    private var nextSeq = -1L
    /** Highest seq played as a real frame (copies at or below it are redundancy). */
    private var lastPlayedReal = -1L
    private var newestSeq = -1L
    private var priming = true
    private var expandRun = 0
    private var plcRun = 0
    private var lastReal: ShortArray? = null
    private var plcPeriod = 0
    private var plcPhase = 0
    private var plcGain = 0f
    private var lastFrameSamples = FRAME_SAMPLES
    private var out = ShortArray(FRAME_SAMPLES * 8)
    private var outLen = 0
    private var lastKind = Kind.PRIMING

    // Delay estimator: (arrival ms, relative delay ms).
    private val arrivals = ArrayDeque<LongArray>()
    private var target = minTargetMs
    private var targetAt = 0L
    private var excessSince = 0L
    private var shrinking = false

    // ---- counters (cumulative, for the bench and the quality badge)
    @Volatile var framesPlayed = 0L; private set
    @Volatile var framesExpanded = 0L; private set
    @Volatile var framesLossConcealed = 0L; private set
    @Volatile var framesSilence = 0L; private set
    @Volatile var framesLate = 0L; private set
    @Volatile var framesDuplicate = 0L; private set
    @Volatile var framesSkipped = 0L; private set
    @Volatile var samplesAccelerated = 0L; private set
    @Volatile var jumps = 0L; private set
    @Volatile var flushes = 0L; private set
    @Volatile var resyncs = 0L; private set
    @Volatile var overflowDrops = 0L; private set

    /** Queued audio ahead of the speaker, ms (frames from the playout point to the newest + output carry). */
    @Synchronized fun delayMs(): Int = levelMs()

    @Synchronized fun targetMs(): Int = target

    @Synchronized fun kind(): Kind = lastKind

    @Synchronized fun isEmpty(): Boolean = frames.isEmpty()

    /** One line for a call log / the bench. */
    @Synchronized fun stats(): String =
        "played=$framesPlayed expand=$framesExpanded lossConceal=$framesLossConcealed silence=$framesSilence " +
            "behind=$framesLate skipped=$framesSkipped accelMs=${samplesAccelerated / SAMPLES_PER_MS} jumps=$jumps " +
            "flushes=$flushes resyncs=$resyncs delay=${levelMs()} target=$target"

    /** Queue one (still encoded) frame under [seq]. A copy already queued or played is ignored. */
    @Synchronized
    fun put(seq: Long, pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (nextSeq < 0) { nextSeq = seq; newestSeq = seq }
        val now = clock()
        if (seq < nextSeq) {
            if (nextSeq - seq > RESYNC_FRAMES) { resync(seq); resyncs++ }
            else {
                // Behind the playout point: a copy of a frame already played (redundancy —
                // says nothing) or a frame that came after its slot (says: the target is
                // short). Only the second is a measurement.
                framesLate++
                if (seq > lastPlayedReal) observe(seq, now)
                return
            }
        }
        if (frames.containsKey(seq)) { framesDuplicate++; return }
        // The delay that matters is when a frame's audio FIRST became available, from
        // whichever packet carried it: a packet held back 40 ms whose frame already came
        // inside the next packet's redundancy cost nothing and must not raise the target.
        observe(seq, now)
        frames[seq] = pcm
        if (seq > newestSeq) newestSeq = seq
        while (frames.size > MAX_FRAMES) { frames.pollFirstEntry(); overflowDrops++ }
    }

    private fun resync(seq: Long) {
        frames.clear(); arrivals.clear(); levels.clear()
        nextSeq = seq; newestSeq = seq; priming = true; expandRun = 0
    }

    @Synchronized
    fun reset() {
        resync(-1); nextSeq = -1; lastPlayedReal = -1; outLen = 0; plcRun = 0; lastReal = null; decoder.reset()
        target = minTargetMs; excessSince = 0; shrinking = false
    }

    /** Exactly [FRAME_SAMPLES] samples of what the speaker plays next. */
    @Synchronized
    fun pull(): ShortArray {
        while (outLen < FRAME_SAMPLES) produce()
        val r = out.copyOf(FRAME_SAMPLES)
        System.arraycopy(out, FRAME_SAMPLES, out, 0, outLen - FRAME_SAMPLES)
        outLen -= FRAME_SAMPLES
        return r
    }

    // ------------------------------------------------------------------ playout

    private fun produce() {
        val now = clock()
        updateTarget(now)
        if (nextSeq < 0 || priming) {
            if (nextSeq >= 0 && frames.isNotEmpty()) {
                // A late first frame or a hole at the start: begin on what is here.
                val first = frames.firstKey()
                if (first > nextSeq) nextSeq = first
                if ((newestSeq - nextSeq + 1) * frameMs() >= target) priming = false
            }
            if (priming) { emitZeros(FRAME_SAMPLES); lastKind = Kind.PRIMING; return }
        }
        val f = frames.remove(nextSeq)
        if (f != null) { playReal(f, now); return }
        val later = frames.ceilingKey(nextSeq)
        if (later == null) {
            // Nothing behind it: late, not lost. Conceal and WAIT (the delay grows by a frame).
            expandRun++
            framesExpanded++
            conceal(Kind.EXPAND)
            return
        }
        val missing = later - nextSeq
        // Concealment played while waiting stands in for the frames that turned out lost.
        val covered = minOf(missing, expandRun.toLong())
        nextSeq += covered; expandRun -= covered.toInt()
        if (nextSeq == later) { playReal(frames.remove(nextSeq)!!, now); return }
        if (later - nextSeq > JUMP_FRAMES) {
            // A long hole with audio queued behind it (sender restart, outage that the
            // expansion did not cover): skip it rather than concealing for seconds.
            jumps++
            nextSeq = later
            playReal(frames.remove(nextSeq)!!, now)
            return
        }
        nextSeq++
        framesLossConcealed++
        conceal(Kind.LOSS_CONCEAL, frames[nextSeq])
    }

    /**
     * The LOWEST queue level over the last [FLOOR_WINDOW_MS]: the cushion that was never
     * needed. The instantaneous level jumps every time an early packet lands (measured,
     * jitter 30 ms: level ≈ target + 16 on average while the floor sat 60 ms above the
     * target), so shrinking on it never fired and every expansion stayed in the delay.
     */
    private val levels = ArrayDeque<LongArray>()
    private fun floorMs(now: Long, level: Int): Int {
        levels.addLast(longArrayOf(now, level.toLong()))
        while (levels.size > 1 && now - levels.first()[0] > FLOOR_WINDOW_MS) levels.removeFirst()
        if (levels.size < 5) return 0
        var m = Long.MAX_VALUE
        for (l in levels) if (l[1] < m) m = l[1]
        return m.toInt()
    }

    private fun frameMs(): Int = maxOf(1, lastFrameSamples / SAMPLES_PER_MS)

    private fun levelMs(): Int {
        val queued = if (nextSeq < 0 || frames.isEmpty()) 0L else (newestSeq - nextSeq + 1) * frameMs()
        return (queued + outLen / SAMPLES_PER_MS).toInt()
    }

    private fun playReal(encoded: ByteArray, now: Long) {
        lastPlayedReal = nextSeq
        nextSeq++
        expandRun = 0
        val f0 = decoder.decode(encoded)
        if (f0.isEmpty()) { framesLossConcealed++; conceal(Kind.LOSS_CONCEAL, null); return }
        lastFrameSamples = f0.size
        framesPlayed++
        var f = f0
        // Shrink a standing queue.
        val level = levelMs()
        if (level > target + FLUSH_ABOVE_MS) {
            // Cut the backlog to the target in one step; the frame being played becomes
            // the newest-but-target one.
            val keep = maxOf(1, target / frameMs())
            val newStart = newestSeq - keep + 1
            if (newStart > nextSeq) {
                while (frames.isNotEmpty() && frames.firstKey() < newStart) { frames.pollFirstEntry(); framesSkipped++ }
                nextSeq = newStart
                flushes++
                val g = frames.remove(nextSeq)?.let { decoder.decode(it) }
                if (g != null && g.isNotEmpty()) { nextSeq++; f = crossfade(f0, g) }
            }
            excessSince = 0
        } else if (floorMs(now, level) >= target + ACCEL_ABOVE_MS || (shrinking && level > target + ACCEL_STOP_MS)) {
            if (excessSince == 0L) excessSince = now
            val held = now - excessSince
            if (held >= ACCEL_HOLD_MS) {
                shrinking = true
                val quiet = rms(f) < QUIET_RMS
                if (quiet && f.size == FRAME_SAMPLES && plcRun == 0) {
                    framesSkipped++
                    lastReal = f
                    lastKind = Kind.NORMAL
                    return                                                  // whole pause frame dropped
                }
                if (held >= ACCEL_FORCE_MS || quiet) {
                    val shorter = removePitchPeriod(f)
                    if (shorter != null) { samplesAccelerated += f.size - shorter.size; f = shorter }
                }
            }
        } else { excessSince = 0; shrinking = false }
        if (plcRun > 0 && !codecConcealed) f = mergeFromConcealment(f)
        plcRun = 0
        lastReal = f0
        append(f)
        lastKind = Kind.NORMAL
    }

    // ------------------------------------------------------------------ concealment

    private fun conceal(kind: Kind, next: ByteArray? = null) {
        plcRun++
        val src = lastReal
        if (src != null && plcRun < PLC_GAINS.size) {
            val c = decoder.conceal(next)
            if (c != null && c.isNotEmpty()) {
                codecConcealed = true
                append(c)
                lastKind = kind
                return
            }
        }
        codecConcealed = false
        if (src == null || plcRun >= PLC_GAINS.size) {
            framesSilence++
            emitZeros(lastFrameSamples)
            lastKind = Kind.SILENCE
            plcGain = 0f
            return
        }
        val n = src.size
        if (plcRun == 1) { plcPeriod = pitch(src); plcPhase = 0 }
        val g0 = PLC_GAINS[plcRun - 1]
        val g1 = PLC_GAINS[plcRun]
        val o = ShortArray(n)
        val p = plcPeriod
        for (i in 0 until n) {
            val env = g0 + (g1 - g0) * i / n
            val v: Float = if (p > 0) src[n - p + (plcPhase + i) % p].toFloat() else src[i] * (if (plcRun == 1) 0.6f else 0.3f)
            o[i] = (v * env).toInt().coerceIn(-32768, 32767).toShort()
        }
        if (p > 0) plcPhase = (plcPhase + n) % p
        plcGain = g1
        append(o)
        lastKind = kind
    }

    /** First [CROSSFADE] samples of [real] blended in from the concealment's continuation. */
    private fun mergeFromConcealment(real: ShortArray): ShortArray {
        val src = lastReal ?: return real
        val out = real.copyOf()
        val m = minOf(CROSSFADE, real.size)
        val p = plcPeriod
        val n = src.size
        for (i in 0 until m) {
            val t = if (p > 0 && plcGain > 0f) src[n - p + (plcPhase + i) % p] * plcGain else 0f
            val w = i.toFloat() / m
            out[i] = (real[i] * w + t * (1 - w)).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun crossfade(a: ShortArray, b: ShortArray): ShortArray {
        // Play the start of [a] fading into [b]: one frame's worth, no step.
        val n = b.size
        val o = b.copyOf()
        val m = minOf(CROSSFADE * 2, n, a.size)
        for (i in 0 until m) { val w = i.toFloat() / m; o[i] = (a[i] * (1 - w) + b[i] * w).toInt().toShort() }
        return o
    }

    /** One pitch period cut out of a voiced frame (WSOLA-style), or null if it is not periodic. */
    private fun removePitchPeriod(f: ShortArray): ShortArray? {
        val n = f.size
        var bestP = 0; var best = 0.0
        val maxP = minOf(PITCH_MAX, n / 2)
        for (p in PITCH_MIN..maxP step 2) {
            var num = 0.0; var e1 = 0.0; var e2 = 0.0
            for (i in 0 until p) { val a = f[i].toDouble(); val b = f[i + p].toDouble(); num += a * b; e1 += a * a; e2 += b * b }
            val c = if (e1 > 0 && e2 > 0) num / sqrt(e1 * e2) else 0.0
            if (c > best) { best = c; bestP = p }
        }
        if (bestP == 0 || best < 0.6) return null
        val p = bestP
        val o = ShortArray(n - p)
        for (i in 0 until p) { val w = i.toFloat() / p; o[i] = (f[i] * (1 - w) + f[i + p] * w).toInt().toShort() }
        System.arraycopy(f, 2 * p, o, p, n - 2 * p)
        return o
    }

    private fun pitch(s: ShortArray): Int {
        val n = s.size
        val win = PITCH_WIN
        val maxP = minOf(PITCH_MAX, n - win)
        if (maxP <= PITCH_MIN) return 0
        var e0 = 0.0
        for (i in n - win until n) e0 += s[i].toDouble() * s[i]
        if (e0 < win * 900.0) return 0
        val score = DoubleArray(maxP + 1)
        var best = 0.0
        for (p in PITCH_MIN..maxP) {
            var num = 0.0; var den = 0.0
            for (i in n - win until n) { val b = s[i - p].toDouble(); num += s[i] * b; den += b * b }
            score[p] = if (den > 0) num / sqrt(den * e0) else 0.0
            if (score[p] > best) best = score[p]
        }
        if (best < 0.5) return 0
        for (p in PITCH_MIN..maxP) {
            val prev = if (p > PITCH_MIN) score[p - 1] else -1.0
            val next = if (p < maxP) score[p + 1] else -1.0
            if (score[p] >= 0.9 * best && score[p] >= prev && score[p] >= next) return p
        }
        return 0
    }

    // ------------------------------------------------------------------ delay estimate

    private fun observe(seq: Long, now: Long) {
        arrivals.addLast(longArrayOf(now, now - seq * frameMs()))
        while (arrivals.isNotEmpty() && now - arrivals.first()[0] > WINDOW_MS) arrivals.removeFirst()
    }

    private fun updateTarget(now: Long) {
        if (now - targetAt < 100 || arrivals.size < 10) return
        targetAt = now
        val d = LongArray(arrivals.size) { arrivals.elementAt(it)[1] }
        d.sort()
        val min = d[0]
        val med = d[d.size / 2]
        val kept = d.filter { it - med <= SPIKE_MS }
        val p95 = kept[minOf(kept.size - 1, (kept.size * 0.95).toInt())]
        target = (p95 - min + TARGET_MARGIN_MS).toInt().coerceIn(minTargetMs, maxTargetMs)
    }

    // ------------------------------------------------------------------ output carry

    private fun append(a: ShortArray) {
        if (outLen + a.size > out.size) out = out.copyOf(outLen + a.size + FRAME_SAMPLES)
        System.arraycopy(a, 0, out, outLen, a.size); outLen += a.size
    }

    private fun emitZeros(n: Int) {
        if (outLen + n > out.size) out = out.copyOf(outLen + n + FRAME_SAMPLES)
        java.util.Arrays.fill(out, outLen, outLen + n, 0); outLen += n
    }

    private fun rms(a: ShortArray): Double {
        var s = 0.0
        for (v in a) s += v.toDouble() * v
        return sqrt(s / maxOf(1, a.size))
    }
}
