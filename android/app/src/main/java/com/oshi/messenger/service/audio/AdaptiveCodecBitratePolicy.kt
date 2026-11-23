package com.oshi.messenger.service.audio

/**
 * Which encoder bitrate a call should be running at, given how the link is doing.
 *
 * ## What was wrong
 *
 * The rule used to live inline in `EnhancedCallManager.startStatsUpdater`, evaluated
 * once per second, and read in full:
 *
 * ```kotlin
 * val rtt = _latencyMs.value * 2
 * val lossPercent = adaptiveBitrate.packetLossPercent      // 50-packet window
 * val targetBitrate = when {
 *     rtt < 100 && lossPercent < 1.0 -> 80000
 *     rtt > 300 || lossPercent > 10.0 -> 24000
 *     else -> 48000
 * }
 * ```
 *
 * Three separate defects, and they compound:
 *
 *  1. **The loss window was 50 packets.** At 20 ms per packet that is one second of
 *     audio, and — far worse — its resolution is *one fiftieth*, i.e. **2 percentage
 *     points per packet**. A link losing a true 1 % therefore never reports 1 %:
 *     it reports 0.0 % or 2.0 %, alternating, because those are the only two values
 *     the window can hold.
 *
 *  2. **The thresholds had no hysteresis.** `lossPercent < 1.0` sits *exactly between*
 *     the only two numbers that window produces. So on a link at a steady, entirely
 *     ordinary 1 % loss the tier flipped 80 kbps → 48 kbps → 80 kbps **once per
 *     second, for the whole call**, and every flip is a `setBitrate` on a live
 *     AAC-ELD encoder. That reconfiguration is what a user hears as "metallic":
 *     not bad audio, but audio whose character changes twice a second.
 *
 *  3. **No dead band on RTT at all.** The same story at the `rtt < 100` edge for any
 *     link whose latency hovers near 50 ms one-way. (iOS's version of this file had a
 *     dead band but applied it in the WRONG DIRECTION — widened only once already
 *     degraded, which makes degrading easy and recovering hard, so it flapped *inside*
 *     the band instead of being damped by it. The rule below is the corrected shape:
 *     the band always opposes whatever change is being proposed.)
 *
 * ## The rule
 *
 * Every threshold comes in a pair — one to ENTER a tier, a slacker one to LEAVE it —
 * and the gap between them is the dead band. A reading inside the band changes
 * nothing, in either direction. Concretely, to climb to [HIGH_BPS] the link must be
 * genuinely good (loss < 0.5 %, RTT < 80 ms); to fall back out of it, it must be
 * genuinely not (loss > 3 %, RTT > 120 ms). A steady 1 % loss lands between the two
 * and the tier simply does not move — which is the entire point.
 *
 * Pure, so the flap is a unit test rather than a phone call.
 */
object AdaptiveCodecBitratePolicy {

    /** Good link. iOS `kAudioConverterEncodeBitRate` at its top tier. */
    const val HIGH_BPS = 80_000

    /** The default, and where an undecidable link stays. */
    const val MEDIUM_BPS = 48_000

    /** Bad link: intelligibility over fidelity. */
    const val LOW_BPS = 24_000

    // ── Loss, in percent. Enter is strict, exit is slack; the gap is the dead band.
    /** Climb to [HIGH_BPS] only below this. */
    const val LOSS_ENTER_HIGH = 0.5
    /**
     * Leave [HIGH_BPS] only above this.
     *
     * 3 and not 2, deliberately. **2.0 is the number a 50-packet window prints for a
     * 1 % link** (one lost packet out of fifty), and an exit threshold sitting exactly
     * on a value the measurement can land on is not a dead band at all — it is the
     * original flap with more steps. The long window this policy is fed from does not
     * produce that quantum, but the threshold should not depend on that being true.
     */
    const val LOSS_EXIT_HIGH = 3.0
    /** Drop to [LOW_BPS] only above this. */
    const val LOSS_ENTER_LOW = 12.0
    /** Climb out of [LOW_BPS] only below this. */
    const val LOSS_EXIT_LOW = 8.0

    // ── Round-trip time, in milliseconds. Same shape.
    /** Climb to [HIGH_BPS] only below this. */
    const val RTT_ENTER_HIGH = 80L
    /** Leave [HIGH_BPS] only above this. */
    const val RTT_EXIT_HIGH = 120L
    /** Drop to [LOW_BPS] only above this. */
    const val RTT_ENTER_LOW = 340L
    /** Climb out of [LOW_BPS] only below this. */
    const val RTT_EXIT_LOW = 260L

    /**
     * Below this many packets the loss figure is not evidence, it is quantisation
     * noise — see defect (1) in the header. 200 packets is ~4 s of 20 ms audio and
     * gives 0.5-point resolution, which is finally fine enough to sit either side of
     * [LOSS_ENTER_HIGH] meaningfully.
     *
     * With fewer samples than this the loss term is simply not consulted; the RTT
     * terms still are, so a call that starts on a visibly terrible link still drops
     * tier within the first second.
     */
    const val MIN_LOSS_SAMPLES = 200

    /**
     * @param currentBps what the encoder is set to right now — the hysteresis is
     *        relative to this, so it must be the value actually applied, not a wish.
     * @param rttMs      round-trip time in ms.
     * @param lossPercent packet loss over the window, `0.0..100.0`.
     * @param lossSamples how many packets that percentage was measured over; below
     *        [MIN_LOSS_SAMPLES] the loss term is ignored (see its doc).
     * @return the bitrate to be at. Equal to [currentBps] whenever the reading is
     *         inside the dead band — callers compare and only reconfigure on a change.
     */
    fun nextBitrate(
        currentBps: Int,
        rttMs: Long,
        lossPercent: Double,
        lossSamples: Int
    ): Int {
        val lossIsEvidence = lossSamples >= MIN_LOSS_SAMPLES

        // Anything not one of the three known tiers (a fresh call still carrying the
        // codec's 64 kbps default, say) is treated as MEDIUM, the tier it sits in.
        val current = when (currentBps) {
            HIGH_BPS -> HIGH_BPS
            LOW_BPS -> LOW_BPS
            else -> MEDIUM_BPS
        }

        // ── Is the link bad enough to be, or to stay, at LOW?
        val enterLow = rttMs > RTT_ENTER_LOW || (lossIsEvidence && lossPercent > LOSS_ENTER_LOW)
        val stayLow = rttMs > RTT_EXIT_LOW || (lossIsEvidence && lossPercent > LOSS_EXIT_LOW)
        if (current == LOW_BPS) {
            if (stayLow) return LOW_BPS
        } else if (enterLow) {
            return LOW_BPS
        }

        // ── Is it good enough to be, or to stay, at HIGH?
        val enterHigh = rttMs < RTT_ENTER_HIGH && (!lossIsEvidence || lossPercent < LOSS_ENTER_HIGH)
        val stayHigh = rttMs < RTT_EXIT_HIGH && (!lossIsEvidence || lossPercent < LOSS_EXIT_HIGH)
        if (current == HIGH_BPS) {
            if (stayHigh) return HIGH_BPS
        } else if (enterHigh) {
            return HIGH_BPS
        }

        return MEDIUM_BPS
    }

    /** The label the call-quality log line and the stats surface use for [bps]. */
    fun label(bps: Int): String = when (bps) {
        HIGH_BPS -> "Good"
        LOW_BPS -> "Bad"
        else -> "Medium"
    }
}
