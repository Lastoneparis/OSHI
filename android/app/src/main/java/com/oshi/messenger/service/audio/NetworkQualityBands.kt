package com.oshi.messenger.service.audio

/**
 * Packet loss → the quality word, the signal bars and the background gradient.
 *
 * Pulled out of [AudioJitterBuffer] so the bands can be tested: the buffer itself
 * touches `android.util.Log` and cannot be constructed on the JVM.
 *
 * ⚠️ These numbers are **iOS's**, not Android's. iOS `CallQualityStats.quality`
 * (`OSHI/VoiceCallManager.swift:1998-2021`) blends latency, jitter and loss, but
 * takes a loss-only branch whenever the latency reading is not trustworthy
 * (`:2003-2008`) — and that branch is the whole of the information Android has,
 * since Android never computes jitter (`EnhancedCallManager` reports
 * `jitterMs = 0`). Those are the bands reproduced here.
 *
 * Android's top two cut-offs used to be one tier harsher than iOS's — `fair` below
 * 5 % instead of 6 %, `poor` below 10 % instead of 12 %. On the same call over the
 * same link at 5.5 % loss the iPhone said *Fair* with two bars and the Android
 * phone said *Poor* with one, plus a different background gradient. The 1 % and 3 %
 * edges already agreed and are unchanged.
 */
object NetworkQualityBands {

    /**
     * Below this many samples the window is not evidence of anything, so report the
     * optimistic end rather than flashing a red meter over the first second of every
     * call.
     */
    const val MIN_SAMPLES = 5

    /**
     * @param lossRatio lost / total over the rolling window, in `0f..1f`.
     * @param samples how many packets that ratio was measured over.
     */
    fun forLoss(lossRatio: Float, samples: Int): NetworkQuality {
        if (samples < MIN_SAMPLES) return NetworkQuality.EXCELLENT
        return when {
            lossRatio < 0.01f -> NetworkQuality.EXCELLENT
            lossRatio < 0.03f -> NetworkQuality.GOOD
            lossRatio < 0.06f -> NetworkQuality.FAIR
            lossRatio < 0.12f -> NetworkQuality.POOR
            else -> NetworkQuality.VERY_POOR
        }
    }
}
