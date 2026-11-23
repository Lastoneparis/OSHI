package com.oshi.messenger

import com.oshi.messenger.service.audio.NetworkQuality
import com.oshi.messenger.service.audio.NetworkQualityBands
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The loss → quality bands, against iOS `CallQualityStats.quality`,
 * `OSHI/VoiceCallManager.swift:2003-2008` (the loss-only branch, which is the whole
 * of the information Android has since it never computes jitter).
 *
 * What was broken: Android's top two cut-offs were one tier harsher than iOS's —
 * `fair` below 5 % instead of 6 %, `poor` below 10 % instead of 12 %. On the same
 * call over the same link the two handsets showed different quality words, different
 * signal-bar counts and different background gradients.
 */
class NetworkQualityBandsTest {

    private val plenty = 50

    @Test
    fun `iOS edges, exactly`() {
        assertEquals(NetworkQuality.EXCELLENT, NetworkQualityBands.forLoss(0.000f, plenty))
        assertEquals(NetworkQuality.EXCELLENT, NetworkQualityBands.forLoss(0.009f, plenty))
        assertEquals(NetworkQuality.GOOD, NetworkQualityBands.forLoss(0.010f, plenty))
        assertEquals(NetworkQuality.GOOD, NetworkQualityBands.forLoss(0.029f, plenty))
        assertEquals(NetworkQuality.FAIR, NetworkQualityBands.forLoss(0.030f, plenty))
        assertEquals(NetworkQuality.FAIR, NetworkQualityBands.forLoss(0.059f, plenty))
        assertEquals(NetworkQuality.POOR, NetworkQualityBands.forLoss(0.060f, plenty))
        assertEquals(NetworkQuality.POOR, NetworkQualityBands.forLoss(0.119f, plenty))
        assertEquals(NetworkQuality.VERY_POOR, NetworkQualityBands.forLoss(0.120f, plenty))
        assertEquals(NetworkQuality.VERY_POOR, NetworkQualityBands.forLoss(1.000f, plenty))
    }

    /**
     * The two readings that used to disagree across platforms. These are the whole
     * reason the change exists, so they get their own assertions.
     */
    @Test
    fun `the two readings that used to diverge from the iPhone`() {
        assertEquals(
            "5.5 % loss: the iPhone says fair, Android used to say poor",
            NetworkQuality.FAIR,
            NetworkQualityBands.forLoss(0.055f, plenty)
        )
        assertEquals(
            "11 % loss: the iPhone says poor, Android used to say very poor",
            NetworkQuality.POOR,
            NetworkQualityBands.forLoss(0.11f, plenty)
        )
    }

    /**
     * A window of four packets is not evidence of anything. Reporting the pessimistic
     * end there would flash a red meter over the first second of every call.
     */
    @Test
    fun `an undersized window reports excellent regardless of loss`() {
        assertEquals(NetworkQuality.EXCELLENT, NetworkQualityBands.forLoss(1.0f, 0))
        assertEquals(NetworkQuality.EXCELLENT, NetworkQualityBands.forLoss(1.0f, 4))
        assertEquals(
            "5 samples is the edge and it counts",
            NetworkQuality.VERY_POOR,
            NetworkQualityBands.forLoss(1.0f, 5)
        )
    }

    /** RECONNECTING and DISCONNECTED are assigned by the call manager, never here. */
    @Test
    fun `the bands never produce a manager-only tier`() {
        val produced = (0..100).map { NetworkQualityBands.forLoss(it / 100f, plenty) }.toSet()
        assertEquals(
            setOf(
                NetworkQuality.EXCELLENT, NetworkQuality.GOOD, NetworkQuality.FAIR,
                NetworkQuality.POOR, NetworkQuality.VERY_POOR
            ),
            produced
        )
    }
}
