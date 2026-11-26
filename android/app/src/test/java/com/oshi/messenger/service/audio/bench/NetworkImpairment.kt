package com.oshi.messenger.service.audio.bench

import java.util.Random

/**
 * __CALL_FLUIDITY_BENCH_2026_11_21__ A seeded, reproducible bad network.
 *
 * One instance per direction. [route] is asked once per packet, in send order, with the
 * time it was sent, and answers the time it is delivered or null for a loss. Everything
 * random comes from one [Random] with a fixed seed, so a scenario is the same network on
 * every run and on both the before and the after build.
 *
 * KEEP IDENTICAL with the Android copy (`OSHI-Android/app/src/test/.../audio/bench/`).
 */
class NetworkImpairment(val spec: Spec, seed: Long) {

    /**
     * @param lossPct independent random loss, percent.
     * @param burstLossPct average loss of a Gilbert–Elliott channel, percent (0 = off);
     *   [burstLen] is the mean number of packets lost in a row while it is in the bad state.
     * @param latencyMs one-way base delay.
     * @param jitterMs delay added on top of [latencyMs], drawn from an exponential-ish
     *   distribution capped at 3× its mean so that most packets see little and a tail sees a
     *   lot, the shape of Wi-Fi/LTE scheduling — packets may overtake each other.
     * @param reorderPct share of packets held back [reorderMs] extra (they arrive after
     *   later packets).
     * @param outageAtMs / [outageMs] a total blackout (Wi-Fi → 4G handover): every packet
     *   sent inside it is lost. With [outageHold] the packets are instead HELD and released
     *   in one burst when it ends — a stalled radio, not a dead one.
     * @param clockSkewPpm the SENDER's clock runs this much fast (mic and speaker of two
     *   phones never share a crystal).
     */
    data class Spec(
        val name: String,
        val lossPct: Double = 0.0,
        val burstLossPct: Double = 0.0,
        val burstLen: Double = 3.0,
        val latencyMs: Double = 10.0,
        val jitterMs: Double = 0.0,
        val reorderPct: Double = 0.0,
        val reorderMs: Double = 40.0,
        val outageAtMs: Long = -1,
        val outageMs: Long = 0,
        val outageHold: Boolean = false,
        val clockSkewPpm: Double = 0.0,
    )

    private val rng = Random(seed)
    private var bad = false

    var sent = 0L; private set
    var lost = 0L; private set

    fun route(sentAtMs: Double): Double? {
        sent++
        val t = sentAtMs
        if (spec.outageMs > 0 && spec.outageAtMs >= 0 && t >= spec.outageAtMs && t < spec.outageAtMs + spec.outageMs) {
            if (!spec.outageHold) { lost++; return null }
            return spec.outageAtMs + spec.outageMs + spec.latencyMs + (t - spec.outageAtMs) * 0.01
        }
        if (spec.lossPct > 0 && rng.nextDouble() * 100 < spec.lossPct) { lost++; return null }
        if (spec.burstLossPct > 0) {
            // Gilbert–Elliott: bad → good with p = 1/burstLen; good → bad chosen so the
            // stationary loss equals burstLossPct.
            val pBg = 1.0 / spec.burstLen
            val pi = spec.burstLossPct / 100.0
            val pGb = pi * pBg / (1 - pi)
            bad = if (bad) rng.nextDouble() >= pBg else rng.nextDouble() < pGb
            if (bad) { lost++; return null }
        }
        var d = spec.latencyMs
        if (spec.jitterMs > 0) d += minOf(-Math.log(1 - rng.nextDouble()) * spec.jitterMs, 3 * spec.jitterMs)
        if (spec.reorderPct > 0 && rng.nextDouble() * 100 < spec.reorderPct) d += spec.reorderMs
        return t + d
    }

    companion object {
        /** The scenarios of the task, same order everywhere. Durations assume a 20 s run. */
        val SCENARIOS = listOf(
            Spec("clean"),
            Spec("loss 2%", lossPct = 2.0),
            Spec("loss 5%", lossPct = 5.0),
            Spec("loss 10%", lossPct = 10.0),
            Spec("bursty 5% (len 4)", burstLossPct = 5.0, burstLen = 4.0),
            Spec("jitter 30ms", latencyMs = 40.0, jitterMs = 30.0),
            Spec("jitter 100ms", latencyMs = 40.0, jitterMs = 100.0),
            Spec("200ms+j30+2%", latencyMs = 200.0, jitterMs = 30.0, lossPct = 2.0),
            Spec("reorder 10%/40ms", reorderPct = 10.0, reorderMs = 40.0),
            Spec("outage 3s @8s", outageAtMs = 8_000, outageMs = 3_000),
            Spec("stall 1.5s @8s", outageAtMs = 8_000, outageMs = 1_500, outageHold = true),
            Spec("skew +500ppm", clockSkewPpm = 500.0, jitterMs = 5.0),
        )
    }
}
