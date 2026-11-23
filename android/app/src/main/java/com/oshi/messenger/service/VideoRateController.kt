package com.oshi.messenger.service

/**
 * __VIDEO_ABR_2026_09_23__
 *
 * Video bandwidth adaptation for the SENDING side, shared by Android
 * (`VideoCallManager`) and the desktop (`CallVideoSession`) — no imports, so the desktop
 * compiles this very file (OSHI-Desktop/build.gradle.kts).
 *
 * Until now only iOS adapted (`VideoCallManager.swift` `tickAdaptiveBitrate`): Android and
 * the desktop sent 1.2 Mbit/s into any link, so a thin uplink overflowed its queue on
 * every keyframe, the peer asked for another keyframe, which overflowed it again.
 * Measured with `VideoLossBench` (bottleneck 700 kbit/s, 1 % loss): 3.7 % clean frames.
 *
 * SIGNALS — the same ones iOS folds in, plus the only one that crosses the wire:
 *  - **loss** (%) and **RTT** (ms) of the call as this device measures it (the audio
 *    loop's sequence gaps / keepalive RTT, or the video frame gaps), mapped exactly like
 *    iOS `audioCongestionFactor`: loss 0 → 1.0, 10 %+ → 0.0; RTT ≤ 80 ms → 1.0, 400 ms+
 *    → 0.0; the WORSE of the two wins.
 *  - **the peer's keyframe requests** (`0x0B`, from iOS, Android or a desktop, on the
 *    media or the signal lane). No client ships a receiver report, so a PLI is the one
 *    piece of feedback about OUR uplink: each says "a reference frame you sent did not
 *    arrive". [PLI_WINDOW_MS] of them are counted, each costing [PLI_WEIGHT].
 *  - optionally an external 0…1 factor (iOS: `audioCongestionFactor`).
 *
 * POLICY — step down fast, up slowly, floor and ceiling:
 *  - the continuous target is `ceiling × min(factors)`, smoothed DOWN with α = 0.6 and UP
 *    with α = 0.15 per 500 ms tick;
 *  - it is quantised onto a LADDER of rungs (bitrate fraction, fps, resolution), because
 *    an encoder that cannot change its rate live (FFmpeg/VideoToolbox, OpenH264) has to be
 *    rebuilt — one IDR per change — so changes must be few;
 *  - DOWN: as soon as the target falls under the current rung (≥ [DOWN_DWELL_MS] apart);
 *  - UP: one rung at a time, only after [UP_HOLD_MS] of target ≥ 1.15 × the next rung
 *    (≥ 95 % of the ceiling for rung 0) and no PLI in that time;
 *  - floor [floorBps] (150 kbit/s, iOS `applyAdaptiveBitrate`'s `max(150_000, …)`),
 *    ceiling = the quality preset.
 */
class VideoRateController(
    val ceilingBps: Int,
    val floorBps: Int = FLOOR_BPS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val FLOOR_BPS = 150_000
        const val TICK_MS = 500L
        const val PLI_WINDOW_MS = 3_000L
        const val PLI_WEIGHT = 0.08
        const val PLI_FACTOR_MIN = 0.35
        /** PLIs right after (re)start are decoder warm-up, not congestion. */
        const val STARTUP_GRACE_MS = 3_000L
        const val DOWN_DWELL_MS = 1_000L
        const val UP_HOLD_MS = 4_000L
        const val ALPHA_DOWN = 0.6
        const val ALPHA_UP = 0.15

        /** (fraction of ceiling, fps, resolution %). Rung 0 is the full preset. */
        val LADDER: List<Rung> = listOf(
            Rung(1.00, 30, 100),
            Rung(0.75, 30, 100),
            Rung(0.55, 30, 100),
            Rung(0.40, 20, 100),
            Rung(0.28, 15, 100),
            Rung(0.18, 15, 75),
            Rung(0.125, 12, 75),
        )

        /** iOS `audioCongestionFactor`'s loss term: 0 % → 1.0, 10 %+ → 0.0. */
        fun lossFactor(lossPct: Double): Double = (1.0 - lossPct / 10.0).coerceIn(0.0, 1.0)

        /** iOS `audioCongestionFactor`'s RTT term: ≤ 80 ms → 1.0, 400 ms+ → 0.0; unknown → 1.0. */
        fun rttFactor(rttMs: Double): Double =
            if (rttMs <= 0.0) 1.0 else (1.0 - (rttMs - 80.0) / 320.0).coerceIn(0.0, 1.0)
    }

    class Rung(val fraction: Double, val fps: Int, val scalePct: Int)

    /** What the encoder should run at. [changed] is true on the tick that moved rungs. */
    class Decision(
        val rung: Int,
        val bitrateBps: Int,
        val fps: Int,
        val scalePct: Int,
        val changed: Boolean,
        val targetBps: Int,
    )

    private val startedAt = nowMs()
    private val plis = ArrayDeque<Long>()
    private var lossEwma = 0.0
    private var haveLoss = false
    private var rttMs = 0.0
    private var external = 1.0
    private var smoothed = ceilingBps.toDouble()
    private var rung = 0
    private var lastChangeAt = Long.MIN_VALUE / 2
    private var upSince = -1L

    var peerKeyframeRequests = 0L
        private set
    var rungChanges = 0L
        private set

    fun rungBps(i: Int): Int = maxOf(floorBps, (ceilingBps * LADDER[i].fraction).toInt())

    /** 15 % headroom above the next rung — capped under the ceiling, or rung 0 is unreachable. */
    private fun upThreshold(i: Int): Double = minOf(rungBps(i) * 1.15, ceilingBps * 0.95)

    /** The peer asked for a keyframe (`0x0B`): a reference frame of ours did not arrive. */
    @Synchronized
    fun onPeerKeyframeRequest() {
        val now = nowMs()
        peerKeyframeRequests++
        if (now - startedAt < STARTUP_GRACE_MS) return
        plis.addLast(now)
        upSince = -1L
    }

    /** One loss measurement, in percent, over the last interval. */
    @Synchronized
    fun onLossSample(lossPct: Double) {
        val l = lossPct.coerceIn(0.0, 100.0)
        lossEwma = if (!haveLoss) l else 0.7 * lossEwma + 0.3 * l
        haveLoss = true
    }

    @Synchronized
    fun onRtt(rtt: Double) { if (rtt > 0) rttMs = if (rttMs <= 0) rtt else 0.7 * rttMs + 0.3 * rtt }

    /** An external 0…1 headroom (1 = pristine), e.g. the audio loop's congestion factor. */
    @Synchronized
    fun onExternalFactor(f: Double) { external = f.coerceIn(0.0, 1.0) }

    @Synchronized
    fun congestionFactor(): Double {
        val now = nowMs()
        while (plis.isNotEmpty() && now - plis.first() > PLI_WINDOW_MS) plis.removeFirst()
        val pli = (1.0 - PLI_WEIGHT * plis.size).coerceAtLeast(PLI_FACTOR_MIN)
        return minOf(lossFactor(lossEwma), rttFactor(rttMs), pli, external)
    }

    /** Call every [TICK_MS]. */
    @Synchronized
    fun tick(): Decision {
        val now = nowMs()
        val target = (ceilingBps * congestionFactor()).coerceAtLeast(floorBps.toDouble())
        smoothed += (if (target < smoothed) ALPHA_DOWN else ALPHA_UP) * (target - smoothed)
        var changed = false
        // Down: the lowest rung whose bitrate still fits under the smoothed target.
        var want = rung
        while (want < LADDER.size - 1 && rungBps(want) > smoothed * 1.02) want++
        if (want > rung) {
            if (now - lastChangeAt >= DOWN_DWELL_MS) {
                rung = want; lastChangeAt = now; changed = true; upSince = -1L
            }
        } else if (rung > 0 && smoothed >= upThreshold(rung - 1)) {
            if (upSince < 0) upSince = now
            if (now - upSince >= UP_HOLD_MS && now - lastChangeAt >= UP_HOLD_MS) {
                rung -= 1; lastChangeAt = now; changed = true; upSince = -1L
            }
        } else {
            upSince = -1L
        }
        if (changed) rungChanges++
        val r = LADDER[rung]
        return Decision(rung, rungBps(rung), r.fps, r.scalePct, changed, target.toInt())
    }

    @get:Synchronized
    val currentRung: Int get() = rung
}
