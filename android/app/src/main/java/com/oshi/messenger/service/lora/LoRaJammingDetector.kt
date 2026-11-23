package com.oshi.messenger.service.lora

/**
 * __ANTI_JAMMING_PHASE1_2026_11_13__ Is the radio channel being jammed, merely busy, or fine?
 *
 * Phase 1 of the anti-jamming work: DETECT and let the transport policy route around LoRa. Nothing here
 * changes what the radio does on the air (no frequency hopping: that would break Meshtastic interop).
 *
 * Pure Kotlin, no Android, no clock of its own: every input carries its timestamp, so the whole decision
 * is unit-testable (LoRaJammingDetectorTest). `MeshtasticManager` feeds it and publishes [state].
 *
 * ## Inputs (everything a radio already tells its phone)
 *  - [onStats]: channel utilisation / TX airtime / noise floor / RX counters. Sources:
 *      * stock Meshtastic `Telemetry.device_metrics` (channel_utilization, air_util_tx), every ~60 s;
 *      * stock Meshtastic `Telemetry.local_stats` (+ num_packets_rx, num_packets_rx_bad, noise_floor on
 *        firmware with upstream's noise-floor sampling, #11826, Sept 2026), every 15 min or on request;
 *      * OSHI Mesh firmware's RADIO frame ("OS" 0x18), every 30 s (OshiMeshProtocol.parseRadio).
 *  - [onValidPacket]: a packet from ANOTHER node reached the phone (CRC passed, decoded) — proof the
 *    channel still carries traffic.
 *  - [onAckResult]: what the radio said about a unicast we sent with want_ack (ROUTING_APP), or a
 *    pending ack that never resolved.
 *
 * ## Decision (evaluated on every input and on [evaluate])
 *  RF anomaly — any one of, from readings no older than [Config.signalStaleMs]:
 *    - noise floor ≥ [Config.noiseAbsoluteDbm], or ≥ [Config.noiseRiseDb] above the quietest reading this
 *      session (the baseline: a jammer can raise the floor, never lower it);
 *    - channel utilisation ≥ [Config.jamUtilPct];
 *    - between two counter readings, ≥ [Config.minBadPackets] bad packets making ≥ [Config.badRatio] of RX.
 *  Silence — no valid packet from another node (phone-visible, or the radio's rxGood counter moving) for
 *    [Config.silenceMs].
 *  Link failing — ≥ [Config.minAckFailures] failed acks in [Config.windowMs] and no successful one.
 *  Strong RF — noise ≥ baseline + [Config.strongNoiseRiseDb] or ≥ the absolute ceiling, or utilisation
 *    ≥ [Config.strongUtilPct].
 *
 *  PROBABLE_JAMMING ⇐ anomaly ∧ silence ∧ (link failing ∨ strong RF)
 *  CONGESTED        ⇐ otherwise: utilisation ≥ [Config.congestedUtilPct] (stays until it drops below
 *                     [Config.congestedExitUtilPct]), or TX airtime ≥ [Config.congestedAirTxPct], or an RF
 *                     anomaly while packets still arrive (a busy mesh, not a jammed one)
 *  CLEAR            ⇐ otherwise.
 *
 * ## Hysteresis
 *  Once PROBABLE_JAMMING, the state is left only on POSITIVE evidence: a valid packet or a successful ack
 *  received after entering (immediate), or — after at least [Config.exitHoldMs] — no RF anomaly in the
 *  current readings. Ack failures cannot come back to rescue the state (LoRa sends are skipped while
 *  jammed), which is why the exit does not wait for them.
 */
class LoRaJammingDetector(private val config: Config = Config()) {

    enum class State { CLEAR, CONGESTED, PROBABLE_JAMMING }

    data class Config(
        val windowMs: Long = 10 * 60_000L,
        val silenceMs: Long = 3 * 60_000L,
        val signalStaleMs: Long = 20 * 60_000L,
        val congestedUtilPct: Float = 25f,
        val congestedExitUtilPct: Float = 20f,
        val congestedAirTxPct: Float = 8f,
        val jamUtilPct: Float = 50f,
        val strongUtilPct: Float = 75f,
        val noiseAbsoluteDbm: Int = -90,
        val noiseRiseDb: Int = 15,
        val strongNoiseRiseDb: Int = 25,
        val badRatio: Double = 0.5,
        val minBadPackets: Long = 5,
        val minAckFailures: Int = 2,
        val exitHoldMs: Long = 2 * 60_000L,
    )

    /**
     * One radio report. Any field may be null: a DeviceMetrics packet carries utilisation only, an old
     * firmware's LocalStats has no noise floor. Counters are CUMULATIVE since the radio booted.
     */
    data class Sample(
        val atMs: Long,
        val channelUtilPct: Float? = null,
        val airUtilTxPct: Float? = null,
        val noiseFloorDbm: Int? = null,
        val rxTotal: Long? = null,
        val rxBad: Long? = null,
    )

    /** The state plus WHY, for the traffic log and for tests. */
    data class Assessment(val state: State, val reasons: List<String>)

    private var startedAtMs: Long? = null
    private var lastValidRxMs: Long? = null

    private var utilPct: Float? = null; private var utilAtMs = 0L
    private var airTxPct: Float? = null; private var airTxAtMs = 0L
    private var noiseDbm: Int? = null; private var noiseAtMs = 0L
    private var noiseBaselineDbm: Int? = null
    private var lastCounters: Pair<Long, Long>? = null   // rxTotal, rxBad
    private var badBurst = false; private var badBurstAtMs = 0L

    private val acks = ArrayDeque<Pair<Long, Boolean>>()

    private var current = Assessment(State.CLEAR, emptyList())
    private var jammedSinceMs: Long? = null

    val state: State get() = current.state
    val assessment: Assessment get() = current

    /** Forget everything: a new radio, a reconnect, or a radio that rebooted. */
    fun reset(nowMs: Long) {
        startedAtMs = nowMs
        lastValidRxMs = null
        utilPct = null; airTxPct = null; noiseDbm = null; noiseBaselineDbm = null
        lastCounters = null; badBurst = false
        acks.clear()
        current = Assessment(State.CLEAR, emptyList())
        jammedSinceMs = null
    }

    fun onStats(s: Sample): Assessment {
        ensureStarted(s.atMs)
        s.channelUtilPct?.takeIf { it.isFinite() && it >= 0f }?.let { utilPct = it; utilAtMs = s.atMs }
        s.airUtilTxPct?.takeIf { it.isFinite() && it >= 0f }?.let { airTxPct = it; airTxAtMs = s.atMs }
        // 0 is what stock LocalStats reports when it has no sample (no presence bit); a real floor is negative.
        // -128 is RadioLib's "invalid", and anything under -127 cannot be a reading.
        s.noiseFloorDbm?.takeIf { it < 0 && it >= -127 }?.let { n ->
            noiseDbm = n; noiseAtMs = s.atMs
            // The baseline only learns while we are not calling it jamming: a jammer must not become normal.
            if (current.state != State.PROBABLE_JAMMING) {
                noiseBaselineDbm = minOf(noiseBaselineDbm ?: n, n)
            }
        }
        if (s.rxTotal != null && s.rxBad != null) {
            val prev = lastCounters
            lastCounters = s.rxTotal to s.rxBad
            if (prev != null && s.rxTotal >= prev.first && s.rxBad >= prev.second) {
                val dTotal = s.rxTotal - prev.first
                val dBad = s.rxBad - prev.second
                val dGood = dTotal - dBad
                // The radio decoded a packet with a valid CRC: the channel still carries traffic, even if
                // nothing of it was for the phone.
                if (dGood > 0) markValid(s.atMs)
                badBurst = dBad >= config.minBadPackets && dBad.toDouble() / maxOf(1L, dTotal) >= config.badRatio
                badBurstAtMs = s.atMs
            } else {
                badBurst = false   // first reading, or the counters went backwards: the radio rebooted
            }
        }
        return evaluate(s.atMs)
    }

    fun onValidPacket(atMs: Long): Assessment {
        ensureStarted(atMs)
        markValid(atMs)
        return evaluate(atMs)
    }

    fun onAckResult(atMs: Long, success: Boolean): Assessment {
        ensureStarted(atMs)
        acks.addLast(atMs to success)
        while (acks.size > 64) acks.removeFirst()
        if (success) markValid(atMs)
        return evaluate(atMs)
    }

    fun evaluate(nowMs: Long): Assessment {
        ensureStarted(nowMs)
        val cutoff = nowMs - config.windowMs
        while (acks.isNotEmpty() && acks.first().first < cutoff) acks.removeFirst()

        val reasons = mutableListOf<String>()
        val util = utilPct?.takeIf { nowMs - utilAtMs <= config.signalStaleMs }
        val airTx = airTxPct?.takeIf { nowMs - airTxAtMs <= config.signalStaleMs }
        val noise = noiseDbm?.takeIf { nowMs - noiseAtMs <= config.signalStaleMs }
        val bursty = badBurst && nowMs - badBurstAtMs <= config.signalStaleMs
        val baseline = noiseBaselineDbm

        val noiseRaised = noise != null &&
            (noise >= config.noiseAbsoluteDbm || (baseline != null && noise - baseline >= config.noiseRiseDb))
        val noiseStrong = noise != null &&
            (noise >= config.noiseAbsoluteDbm || (baseline != null && noise - baseline >= config.strongNoiseRiseDb))
        val utilJam = util != null && util >= config.jamUtilPct
        val utilStrong = util != null && util >= config.strongUtilPct

        if (noiseRaised) reasons += "noise floor ${noise} dBm (baseline ${baseline ?: "?"})"
        if (utilJam) reasons += "channel utilisation ${"%.0f".format(util)}%"
        if (bursty) reasons += "bad packets dominate RX"
        val rfAnomaly = noiseRaised || utilJam || bursty

        val sinceValid = nowMs - (lastValidRxMs ?: startedAtMs ?: nowMs)
        val silent = sinceValid >= config.silenceMs
        if (silent) reasons += "no valid packet for ${sinceValid / 1000} s"

        val failures = acks.count { !it.second }
        val successes = acks.count { it.second }
        val linkFailing = failures >= config.minAckFailures && successes == 0
        if (linkFailing) reasons += "$failures unanswered sends"
        val strongRf = noiseStrong || utilStrong

        val jammedNow = rfAnomaly && silent && (linkFailing || strongRf)

        val next: State = when {
            current.state == State.PROBABLE_JAMMING -> {
                val since = jammedSinceMs ?: nowMs
                val linkBack = (lastValidRxMs ?: Long.MIN_VALUE) > since ||
                    acks.any { it.second && it.first > since }
                when {
                    linkBack -> congestionOrClear(util, airTx, rfAnomaly)
                    !rfAnomaly && nowMs - since >= config.exitHoldMs -> congestionOrClear(util, airTx, false)
                    else -> State.PROBABLE_JAMMING
                }
            }
            jammedNow -> State.PROBABLE_JAMMING
            else -> congestionOrClear(util, airTx, rfAnomaly)
        }
        if (next == State.PROBABLE_JAMMING && current.state != State.PROBABLE_JAMMING) jammedSinceMs = nowMs
        if (next != State.PROBABLE_JAMMING) jammedSinceMs = null
        current = Assessment(next, reasons)
        return current
    }

    private fun congestionOrClear(util: Float?, airTx: Float?, rfAnomaly: Boolean): State {
        val wasCongested = current.state == State.CONGESTED
        val utilBusy = util != null &&
            util >= (if (wasCongested) config.congestedExitUtilPct else config.congestedUtilPct)
        val txBusy = airTx != null && airTx >= config.congestedAirTxPct
        return if (utilBusy || txBusy || rfAnomaly) State.CONGESTED else State.CLEAR
    }

    private fun markValid(atMs: Long) {
        lastValidRxMs = maxOf(lastValidRxMs ?: atMs, atMs)
    }

    private fun ensureStarted(nowMs: Long) {
        if (startedAtMs == null) startedAtMs = nowMs
    }
}
