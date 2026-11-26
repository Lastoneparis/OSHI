package com.oshi.messenger.service.audio.bench

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * __CALL_FLUIDITY_BENCH_2026_11_21__ The test signal a bench "speaks" and the analysis of
 * what came out of the far speaker. Implementation-independent on purpose: it reads only
 * PCM in and PCM out with their clocks, so the same numbers mean the same thing on the
 * old receive chain and the new one, on desktop and on Android.
 *
 * Signal: voiced bursts (a 140 Hz harmonic complex with a 4 Hz syllable envelope) of
 * [BURST_MS] every [PERIOD_MS], separated by −60 dBFS noise (never digital zero, which the
 * desktop reads as a blocked microphone).
 *
 * Metrics:
 *  - mouth-to-ear latency per burst: output onset − onset of the latest burst sent ≥ 20 ms
 *    before it (valid below one period, 800 ms);
 *  - gap ratio: 5 ms steps INSIDE a burst (30 ms away from its edges) whose 20 ms window came
 *    out at < 30 % of the level the codec alone produces there (the `reference`: same
 *    encoder and decoder over a perfect network, so codec artefacts are not counted) — what a listener hears as a drop-out (good
 *    concealment that sounds like the voice is not counted, silence is);
 *  - gap events per minute: runs of ≥ 10 ms of such blocks;
 *  - drift: latency of the last bursts − latency of the first;
 *  - outage recovery: gap ratio of the audio SENT in the 2 s after the network came back,
 *    and how much later than before the outage it is heard.
 *
 * KEEP IDENTICAL with the Android copy.
 */
object FluidityProbe {
    const val RATE = 48_000
    const val BURST_MS = 480
    const val PERIOD_MS = 800
    private const val BLOCK = 240 // 5 ms
    private const val EDGE_MS = 30
    private const val WIN = 960

    /** Sample [i] of the signal (i counted from the start of capture). */
    fun sample(i: Long, seed: Int = 0): Short {
        val tMs = i * 1000.0 / RATE
        val inPeriod = tMs % PERIOD_MS
        if (inPeriod >= BURST_MS) {
            // −60 dBFS noise, deterministic.
            val h = ((i * 2654435761L + seed * 97L) ushr 7) and 0xFF
            return ((h - 128) / 4).toShort()
        }
        val t = i.toDouble() / RATE
        val f0 = 140.0 + 8 * sin(2 * Math.PI * 0.7 * t) + seed * 13
        var v = 0.0
        for (k in 1..10) v += sin(2 * Math.PI * f0 * k * t + k) / k
        val env = 0.6 + 0.4 * sin(2 * Math.PI * 4.0 * t)
        val edge = minOf(1.0, inPeriod / 10.0, (BURST_MS - inPeriod) / 10.0)
        return (4200 * v * env * edge).toInt().coerceIn(-32768, 32767).toShort()
    }

    fun frame(index: Long, samples: Int = 960, seed: Int = 0): ShortArray =
        ShortArray(samples) { sample(index * samples + it, seed) }

    private fun rms(a: ShortArray, from: Int, n: Int): Double {
        var s = 0.0
        val end = minOf(a.size, from + n)
        if (end <= from) return 0.0
        for (i in from until end) s += a[i].toDouble() * a[i]
        return sqrt(s / (end - from))
    }

    /**
     * @param output every sample that reached the speaker, contiguous.
     * @param outputStartMs wall time of output[0] (ms, same clock as the input).
     * @param inputStartMs wall time of input sample 0.
     * @param inputRate the sender's real sample rate (≠ 48 000 under clock skew).
     * @param outageAtMs/outageMs sender-time window of a network outage, or -1.
     */
    class Result(
        val latencies: List<Pair<Double, Double>>, // (input onset ms, latency ms)
        val gapBlocks: Int,
        val expectedBlocks: Int,
        val gapEvents: Int,
        val durationMs: Double,
        val postOutageGapRatio: Double,
        val postOutageExtraMs: Double,
        /** Sender-time (ms from start) of every gap step, for "where did it break". */
        val gapAtMs: List<Double> = emptyList(),
    ) {
        val gapRatio get() = if (expectedBlocks == 0) 0.0 else gapBlocks.toDouble() / expectedBlocks
        val gapsPerMin get() = gapEvents * 60_000.0 / durationMs
        private fun med(v: List<Double>) = if (v.isEmpty()) Double.NaN else v.sorted()[v.size / 2]
        val medianLatency get() = med(latencies.map { it.second })
        val p95Latency get() = latencies.map { it.second }.sorted().let { if (it.isEmpty()) Double.NaN else it[minOf(it.size - 1, (it.size * 0.95).toInt())] }
        /** Median of the last 5 bursts − median of bursts 2..6. */
        val driftMs get() = if (latencies.size < 10) Double.NaN else med(latencies.takeLast(5).map { it.second }) - med(latencies.subList(1, 6).map { it.second })
    }

    fun analyze(
        output: ShortArray,
        outputStartMs: Double,
        inputStartMs: Double,
        inputSamples: Long,
        inputRate: Double = RATE.toDouble(),
        seed: Int = 0,
        outageAtMs: Long = -1,
        outageMs: Long = 0,
        settleMs: Double = 1000.0,
        /** What the codec alone makes of the input (same encoder, perfect network), indexed like the input. */
        reference: ShortArray? = null,
    ): Result {
        val outMs = { j: Int -> outputStartMs + j * 1000.0 / RATE }
        val inMs = { i: Long -> inputStartMs + i * 1000.0 / inputRate }
        // Input bursts (in input sample index).
        val bursts = ArrayList<LongRange>()
        val perSamples = PERIOD_MS * RATE / 1000L
        val burstSamples = BURST_MS * RATE / 1000L
        var b = 0L
        while (b + burstSamples <= inputSamples) { bursts += b until b + burstSamples; b += perSamples }
        // Output onsets: a block above threshold after ≥ 80 ms below half of it.
        val thr = 600.0
        val onsets = ArrayList<Double>()
        var quiet = 0
        var j = 0
        while (j + BLOCK <= output.size) {
            val r = rms(output, j, BLOCK)
            if (r >= thr) { if (quiet >= 16) onsets += outMs(j); quiet = 0 } else if (r < thr / 2) quiet++
            j += BLOCK
        }
        // Match each onset to the LAST burst that started ≥ 20 ms before it. Unambiguous
        // while the latency stays under one period (800 ms), which every steady state here
        // does; matching by continuity instead locked onto the wrong period after a stall.
        val burstLat = arrayOfNulls<Double>(bursts.size)
        for (o in onsets) {
            val k = bursts.indices.lastOrNull { o - inMs(bursts[it].first) >= 20.0 } ?: continue
            if (burstLat[k] == null) burstLat[k] = o - inMs(bursts[k].first)
        }
        // Fill unmatched bursts with the neighbours' latency.
        val known = burstLat.indices.filter { burstLat[it] != null }
        fun latFor(k: Int): Double? {
            burstLat[k]?.let { return it }
            val before = known.lastOrNull { it < k }; val after = known.firstOrNull { it > k }
            return when { before != null -> burstLat[before]; after != null -> burstLat[after]; else -> null }
        }
        val input = { i: Long -> if (reference != null) (if (i in reference.indices) reference[i.toInt()] else 0) else sample(i, seed) }
        var gapBlocks = 0; var expected = 0; var events = 0
        var postGap = 0; var postExp = 0
        val gapAt = ArrayList<Double>()
        val edge = EDGE_MS * RATE / 1000L
        for ((k, r) in bursts.withIndex()) {
            val sentMs = inMs(r.first)
            if (sentMs - inputStartMs < settleMs) continue
            val inOutage = outageAtMs >= 0 && sentMs + BURST_MS > inputStartMs + outageAtMs && sentMs < inputStartMs + outageAtMs + outageMs
            if (inOutage) continue
            val lat = latFor(k) ?: continue
            var run = 0
            var i = r.first + edge
            while (i + BLOCK <= r.last - edge) {
                val tOut = inMs(i) + lat
                val jj = ((tOut - outputStartMs) * RATE / 1000.0).toInt()
                if (jj < 0 || jj + BLOCK > output.size) { i += BLOCK; continue }
                // 20 ms windows, and the best of three alignments 10 ms apart: the per-burst
                // latency is only known to a block, and a voiced signal's 5 ms RMS swings.
                var e = 0.0
                for (q in -WIN / 2 until WIN / 2) { val v = input(i + q).toDouble(); e += v * v }
                val inR = sqrt(e / WIN)
                if (inR < 300) { i += BLOCK; continue }                  // the codec itself made a dip here
                var outR = 0.0
                for (sh in intArrayOf(-480, 0, 480)) outR = maxOf(outR, rms(output, maxOf(0, jj + sh - WIN / 2), WIN))
                expected++
                val post = outageAtMs >= 0 && sentMs >= inputStartMs + outageAtMs + outageMs && sentMs < inputStartMs + outageAtMs + outageMs + 2000
                if (post) postExp++
                if (outR < 0.3 * inR) {
                    gapBlocks++; run++
                    gapAt += inMs(i) - inputStartMs
                    if (post) postGap++
                    if (run == 2) events++
                } else run = 0
                i += BLOCK
            }
        }
        val lats = bursts.indices.filter { burstLat[it] != null && inMs(bursts[it].first) - inputStartMs >= settleMs }
            .map { inMs(bursts[it].first) - inputStartMs to burstLat[it]!! }
        var extra = Double.NaN
        if (outageAtMs >= 0) {
            val end = outageAtMs + outageMs
            val pre = lats.filter { it.first < outageAtMs && it.first > outageAtMs - 4000 }.map { it.second }.sorted()
            val post = lats.filter { it.first >= end && it.first < end + 3000 }.map { it.second }.sorted()
            if (pre.isNotEmpty() && post.isNotEmpty()) extra = post[post.size / 2] - pre[pre.size / 2]
        }
        return Result(lats, gapBlocks, expected, events, inputSamples * 1000.0 / inputRate,
            if (postExp == 0) Double.NaN else postGap.toDouble() / postExp, extra, gapAt)
    }
}
