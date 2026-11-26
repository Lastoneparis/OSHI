package com.oshi.messenger.service.audio.bench

import com.oshi.messenger.service.audio.AdaptiveJitterBuffer
import com.oshi.messenger.service.audio.AudioJitterBuffer
import com.oshi.messenger.service.audio.OpusCallDecoder
import com.oshi.messenger.service.audio.OpusCallEncoder
import com.oshi.messenger.service.audio.OpusPlayoutDecoder
import com.oshi.messenger.service.audio.SeqUnwrapper
import org.junit.Assume
import org.junit.Test
import java.util.PriorityQueue

/**
 * __CALL_FLUIDITY_BENCH_2026_11_21__ The Android 0x19 receive chain in VIRTUAL time, under the
 * same [NetworkImpairment] scenarios and the same [FluidityProbe] analysis as the desktop
 * benches (`OSHI-Desktop/.../call/bench/`).
 *
 * `OldAndroid` reproduces `EnhancedCallManager` up to 1.7.2 line for line where it matters:
 * `OpusCallDecoder.decode` → `OpusCallDecoder.keyed(outerSeq, lastKey)` →
 * `AudioJitterBuffer.addPacket` with `lookAheadFrames = 1` (handleAudioData 0x19 branch), and
 * the 20 ms paced playout loop calling `getNextPacket()` and writing silence on null.
 * `NewAndroid` is [AdaptiveJitterBuffer] + [OpusPlayoutDecoder] as wired now.
 *
 * Gate: `OSHI_FLUIDITY_BENCH=1` (prints and writes `build/fluidity-sim-android.md`).
 */
class FluiditySimBench {

    interface Receiver {
        fun onPacket(nowMs: Long, outerSeq: Long, payload: ByteArray)
        /** Called every ms; returns PCM written to the AudioTrack at this ms, or null. */
        fun tick(nowMs: Long): ShortArray?
        fun jbDelayMs(): Int
        fun counters(): String
    }

    /** The playout loop's pacing: the next write is due after the duration just written. */
    class Track {
        val out = ArrayList<ShortArray>(); var startMs = -1.0; var nextDue = -1.0; var samples = 0L
        fun output(): ShortArray { val r = ShortArray(samples.toInt()); var o = 0; for (p in out) { System.arraycopy(p, 0, r, o, p.size); o += p.size }; return r }
    }

    class OldAndroid : Receiver {
        var now = 0L
        private val jb = AudioJitterBuffer(targetDepthMs = 100, minDepthMs = 60, maxDepthMs = 200).also { it.clock = { now } }
        private val dec = OpusCallDecoder()
        private var lastKey = -1L
        private var started = false
        var silence = 0L
        override fun onPacket(nowMs: Long, outerSeq: Long, payload: ByteArray) {
            now = nowMs
            val frames = dec.decode(payload)
            val k = OpusCallDecoder.keyed(frames, outerSeq, lastKey)
            if (k.isNotEmpty()) lastKey = k.last().first
            if (k.isEmpty()) return
            jb.lookAheadFrames = 1
            started = true
            for ((key, pcm) in k) {
                val b = ByteArray(pcm.size * 2)
                for (i in pcm.indices) { val v = pcm[i].toInt(); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
                jb.addPacket(key, b)
            }
        }
        override fun tick(nowMs: Long): ShortArray? {
            now = nowMs
            if (!started) return null
            val d = jb.getNextPacket()
            if (d == null) { silence++; return ShortArray(960) }
            return ShortArray(d.size / 2) { ((d[2 * it + 1].toInt() shl 8) or (d[2 * it].toInt() and 0xFF)).toShort() }
        }
        override fun jbDelayMs() = jb.bufferDepthMs
        override fun counters() = "silence=$silence concealed=${jb.totalPacketsConcealed} lost=${jb.getStats().packetsLost} jumps=${jb.totalSlotJumps} resyncs=${jb.totalResyncs} shed=${jb.totalShedFrames} target=${jb.getStats().targetDepthMs}"
    }

    class NewAndroid : Receiver {
        var now = 0L
        val opus = OpusPlayoutDecoder()
        val jb = AdaptiveJitterBuffer(opus, clock = { now })
        private val unwrap = SeqUnwrapper()
        private var started = false
        override fun onPacket(nowMs: Long, outerSeq: Long, payload: ByteArray) {
            now = nowMs
            if (OpusPlayoutDecoder.file(payload, jb, unwrap)) started = true
        }
        override fun tick(nowMs: Long): ShortArray? { now = nowMs; return if (started) jb.pull() else null }
        override fun jbDelayMs() = jb.delayMs()
        override fun counters() = jb.stats() + " fec=${opus.fecDecodes}"
    }

    class Row(val scenario: String, val impl: String, val r: FluidityProbe.Result, val jbMean: Double, val jbMax: Int, val counters: String)

    companion object {
        fun run(spec: NetworkImpairment.Spec, rx: Receiver, durationMs: Long = 20_000, seed: Long = 7): Row {
            val net = NetworkImpairment(spec, seed)
            val enc = OpusCallEncoder(32_000, 8)
            val frameMs = 20.0 / (1 + spec.clockSkewPpm * 1e-6)
            val q = PriorityQueue<Triple<Double, Long, ByteArray>>(compareBy { it.first })
            var nextSend = 20.0
            var k = 0L
            val track = Track()
            val jb = ArrayList<Int>()
            for (t in 0L..durationMs + 1500) {
                while (t <= durationMs && nextSend <= t) {
                    for (p in enc.encode(FluidityProbe.frame(k))) net.route(nextSend)?.let { q.add(Triple(it, 1000L + k, p)) }
                    k++; nextSend += frameMs
                }
                while (q.isNotEmpty() && q.peek().first <= t) { val (_, seq, p) = q.poll(); rx.onPacket(t, seq, p) }
                if (track.nextDue < 0 || t >= track.nextDue) {
                    val pcm = rx.tick(t)
                    if (pcm != null) {
                        if (track.startMs < 0) { track.startMs = t.toDouble(); track.nextDue = t.toDouble() }
                        track.out += pcm; track.samples += pcm.size
                        track.nextDue += pcm.size / 48.0
                    }
                }
                if (t % 100 == 0L && track.startMs >= 0) jb += rx.jbDelayMs()
            }
            val res = FluidityProbe.analyze(track.output(), track.startMs, 0.0, k * 960, 48_000.0 * (1 + spec.clockSkewPpm * 1e-6),
                outageAtMs = spec.outageAtMs, outageMs = spec.outageMs, reference = FluidityReference.cachedReference(k))
            return Row(spec.name, rx.javaClass.simpleName, res, jb.average(), jb.maxOrNull() ?: 0, rx.counters())
        }
    }

    @Test
    fun simulate() {
        Assume.assumeTrue("set OSHI_FLUIDITY_BENCH=1", System.getenv("OSHI_FLUIDITY_BENCH") == "1")
        val rows = ArrayList<Row>()
        for (s in NetworkImpairment.SCENARIOS) { rows += run(s, OldAndroid()); rows += run(s, NewAndroid()) }
        val f = FluidityReference::fmt
        val t = buildString {
            appendLine("| scenario | chain | gap % | gaps/min | m2e median ms | m2e p95 | drift ms | JB mean/max ms | post-outage gap % | post-outage extra ms |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|")
            for (w in rows) appendLine("| ${w.scenario} | ${w.impl} | ${f(w.r.gapRatio * 100, 1)} | ${f(w.r.gapsPerMin, 0)} | ${f(w.r.medianLatency, 0)} | ${f(w.r.p95Latency, 0)} | ${f(w.r.driftMs, 0)} | ${f(w.jbMean, 0)}/${w.jbMax} | ${f(w.r.postOutageGapRatio * 100, 1)} | ${f(w.r.postOutageExtraMs, 0)} |")
        }
        println(t)
        rows.forEach { println("${it.scenario} ${it.impl}: ${it.counters}") }
        java.io.File("build/fluidity-sim-android.md").writeText(t)
    }
}
