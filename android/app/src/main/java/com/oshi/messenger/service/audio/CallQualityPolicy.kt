package com.oshi.messenger.service.audio

/**
 * __QUALITY_FROM_WHAT_IS_HEARD_2026_09_22__ — port of iOS
 * `CallQualityStats.quality` + `updateConnectionQuality()` (VoiceCallManager.swift,
 * same tag). The badge judges what is HEARD: transport latency and the loss measured
 * AFTER the jitter buffer (share of concealed frames among the frames played), not raw
 * arrival jitter.
 *
 * Why, measured on iOS (call 6216CD2F, 2026-09-22, 29 min, Wi-Fi ↔ 5G): raw jitter
 * J = 22–43 ms with p95 bursts to 300 ms, and 0.0 % heard loss — the jitter buffer
 * absorbed all of it. 5G produces those bursts, so the same side (often the callee)
 * showed "bad" on a perfect call.
 *
 * What Android did before: `AudioJitterBuffer.networkQuality`, re-read on EVERY audio
 * packet and every 500 ms, from a 50-slot window that also counted every tick of a
 * peer's silence as a loss (a muted peer, or an iPhone App Store build sending ~32 ms
 * frames at ~30 pkt/s against Android's 20 ms slot clock, read as 40 % "loss"). Loss
 * only, no hysteresis, bands 1/3/6/12 %. The concealment ratio below does not have that
 * defect: once concealment gives up (5 frames) the buffer plays silence, and silence is
 * neither real nor concealed, so it is not counted.
 *
 * Pure arithmetic, no Android types — `CallQualityPolicyTest` exercises every boundary.
 */
object CallQualityPolicy {

    /** Latency readings at or above this are an HTTP round trip to the VPS, not the call. */
    const val LATENCY_UNTRUSTED_MS = 400L

    /** Raw jitter only matters once it overflows any reasonable buffer — and then caps at GOOD. */
    const val JITTER_CAP_MS = 150.0

    /** Fewer frames than this played in a window is not evidence of anything. */
    const val MIN_PLAYED_PER_WINDOW = 10L

    /** EWMA weight of the newest window (iOS: `ewma * 0.7 + window * 0.3`). */
    const val EWMA_NEW_WEIGHT = 0.3

    /**
     * iOS tiers, verbatim.
     *
     * @param lossPercent concealed / played × 100, smoothed.
     * @param latencyMs one-way latency; `<= 0` or `>= 400` means "unknown" and is ignored.
     * @param jitterMs raw RFC 3550 inter-arrival jitter.
     */
    fun tier(lossPercent: Double, latencyMs: Long, jitterMs: Double): NetworkQuality {
        val latency = if (latencyMs in 1 until LATENCY_UNTRUSTED_MS) latencyMs else 0L
        val loss = lossPercent
        if (loss < 2.0 && latency < 100 && jitterMs < JITTER_CAP_MS) return NetworkQuality.EXCELLENT
        if (loss < 5.0 && latency < 200) return NetworkQuality.GOOD
        if (loss < 10.0 && latency < 300) return NetworkQuality.FAIR
        if (loss < 20.0) return NetworkQuality.POOR
        return NetworkQuality.VERY_POOR
    }

    /** Larger = better. Transport-owned states rank lowest (iOS `CallQualityStats.rank`). */
    fun rank(q: NetworkQuality): Int = when (q) {
        NetworkQuality.EXCELLENT -> 4
        NetworkQuality.GOOD -> 3
        NetworkQuality.FAIR -> 2
        NetworkQuality.POOR -> 1
        NetworkQuality.VERY_POOR, NetworkQuality.RECONNECTING, NetworkQuality.DISCONNECTED -> 0
    }
}

/**
 * The stateful half: windowed concealment ratio → EWMA → tier → hysteresis.
 *
 * Sample it every ~2 s (iOS samples at 2 s). Upgrades show immediately; a downgrade is
 * shown only when two samples in a row (≈ 4 s) confirm it, and then as the LESS severe
 * of the two — one isolated hole no longer paints the badge orange.
 *
 * Not thread-safe; the owner samples it from one coroutine.
 */
class CallQualityTracker(initial: NetworkQuality = NetworkQuality.GOOD) {

    var shown: NetworkQuality = initial
        private set

    /** Smoothed heard loss, percent. Exposed for the stats sheet. */
    var lossEwmaPercent: Double = 0.0
        private set

    private var lastReal = 0L
    private var lastConcealed = 0L
    private var pendingDowngrade: NetworkQuality? = null

    /**
     * @param realFramesTotal cumulative real frames handed to the speaker this call.
     * @param concealedFramesTotal cumulative PLC frames handed to the speaker this call.
     * @return the tier to show.
     */
    fun sample(
        realFramesTotal: Long,
        concealedFramesTotal: Long,
        latencyMs: Long,
        jitterMs: Double
    ): NetworkQuality {
        val dReal = (realFramesTotal - lastReal).coerceAtLeast(0L)
        val dConcealed = (concealedFramesTotal - lastConcealed).coerceAtLeast(0L)
        lastReal = realFramesTotal
        lastConcealed = concealedFramesTotal
        val played = dReal + dConcealed
        if (played >= CallQualityPolicy.MIN_PLAYED_PER_WINDOW) {
            val windowLoss = dConcealed * 100.0 / played
            lossEwmaPercent = lossEwmaPercent * (1 - CallQualityPolicy.EWMA_NEW_WEIGHT) +
                windowLoss * CallQualityPolicy.EWMA_NEW_WEIGHT
        }
        val quality = CallQualityPolicy.tier(lossEwmaPercent.coerceIn(0.0, 100.0), latencyMs, jitterMs)

        val newRank = CallQualityPolicy.rank(quality)
        val cur = shown
        val pending = pendingDowngrade
        if (cur == NetworkQuality.RECONNECTING || cur == NetworkQuality.DISCONNECTED ||
            newRank >= CallQualityPolicy.rank(cur)
        ) {
            pendingDowngrade = null
            shown = quality
        } else if (pending != null && CallQualityPolicy.rank(pending) >= newRank) {
            pendingDowngrade = null
            shown = pending
        } else {
            pendingDowngrade = quality
        }
        return shown
    }

    fun reset(initial: NetworkQuality = NetworkQuality.GOOD) {
        shown = initial
        lossEwmaPercent = 0.0
        lastReal = 0L
        lastConcealed = 0L
        pendingDowngrade = null
    }
}
