package com.oshi.messenger.service.audio.bench

import com.oshi.messenger.service.audio.OpusCallDecoder
import com.oshi.messenger.service.audio.OpusCallEncoder
import com.oshi.messenger.service.audio.OpusWire

/**
 * __CALL_FLUIDITY_BENCH_2026_11_21__ Helpers shared by the two benches. Uses only classes that
 * existed before `__CALL_FLUIDITY_2026_11_21__`, so [CallFluidityBench] compiles and runs on
 * the old code too (that is how its "before" column is produced).
 */
object FluidityReference {
    /**
         * The codec-only rendering of [frames] of the probe signal: encoded and decoded in
         * order with nothing lost, shifted back by the decoder's output delay so it lines
         * up with the input (found by cross-correlating the first burst).
         */
        fun reference(frames: Long, redundancy: Int = OpusWire.MAX_REDUNDANT): ShortArray {
            val enc = OpusCallEncoder(32_000, 8, redundancy)
            val dec = OpusCallDecoder()
            val out = ShortArray((frames * 960).toInt())
            var o = 0
            for (k in 0 until frames) for (p in enc.encode(FluidityProbe.frame(k))) for (f in dec.decode(p)) {
                val n = minOf(f.pcm.size, out.size - o); if (n > 0) System.arraycopy(f.pcm, 0, out, o, n); o += f.pcm.size
            }
            var best = 0; var bestC = -1.0
            for (lag in 0..960) {
                var c = 0.0
                for (i in 0 until 9600) c += FluidityProbe.sample(i.toLong()).toDouble() * out[i + lag]
                if (c > bestC) { bestC = c; best = lag }
            }
            return out.copyOfRange(best, out.size)
        }
        private val refCache = HashMap<Long, ShortArray>()
        fun cachedReference(frames: Long) = synchronized(refCache) { refCache.getOrPut(frames) { reference(frames) } }

        fun fmt(v: Double, d: Int = 0) = if (v.isNaN()) "–" else "%.${d}f".format(v)

}
