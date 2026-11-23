package com.oshi.messenger.service.lora

import com.oshi.messenger.service.lora.LoRaJammingDetector.Sample
import com.oshi.messenger.service.lora.LoRaJammingDetector.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * __ANTI_JAMMING_PHASE1_2026_11_13__ The detector's contract, on synthetic timelines.
 *
 * The distinction that matters most: a BUSY mesh (high utilisation, packets still arriving) is CONGESTED,
 * a LONELY radio (silence, unanswered sends, quiet RF) is CLEAR, and only RF trouble together with silence
 * and failing sends — or overwhelming RF trouble with silence — is PROBABLE_JAMMING. Calling a busy mesh
 * "jammed" would push every off-grid message off the radio for nothing.
 */
class LoRaJammingDetectorTest {

    private val min = 60_000L
    private lateinit var d: LoRaJammingDetector

    @Before
    fun setUp() {
        d = LoRaJammingDetector()
        d.reset(0L)
    }

    @Test
    fun startsClear() {
        assertEquals(State.CLEAR, d.evaluate(10 * min).state)
    }

    @Test
    fun busyMeshIsCongestedNotJammed() {
        var t = 0L
        repeat(20) {
            t += 30_000
            d.onValidPacket(t)
            d.onStats(Sample(t, channelUtilPct = 65f, noiseFloorDbm = -112))
        }
        assertEquals(State.CONGESTED, d.state)
    }

    @Test
    fun aLonelyRadioWithQuietRfIsNotJammed() {
        d.onStats(Sample(1_000, channelUtilPct = 2f, noiseFloorDbm = -118))
        d.onAckResult(4 * min, false)
        d.onAckResult(5 * min, false)
        d.onAckResult(6 * min, false)
        assertEquals(State.CLEAR, d.evaluate(7 * min).state)
    }

    @Test
    fun raisedNoiseWithSilenceAndFailedSendsIsJamming() {
        d.onStats(Sample(1_000, noiseFloorDbm = -118))               // baseline
        d.onStats(Sample(4 * min, noiseFloorDbm = -100))             // +18 dB: anomaly, not "strong"
        assertNotEquals(State.PROBABLE_JAMMING, d.state)             // no failed sends yet
        d.onAckResult(4 * min + 10_000, false)
        val a = d.onAckResult(5 * min, false)
        assertEquals(State.PROBABLE_JAMMING, a.state)
        assertTrue(a.reasons.joinToString().contains("noise floor"))
    }

    @Test
    fun raisedNoiseWithoutFailedSendsIsOnlyCongested() {
        d.onStats(Sample(1_000, noiseFloorDbm = -118))
        assertEquals(State.CONGESTED, d.onStats(Sample(5 * min, noiseFloorDbm = -100)).state)
    }

    @Test
    fun overwhelmingNoiseWithSilenceIsJammingEvenWithoutSends() {
        d.onStats(Sample(1_000, noiseFloorDbm = -117))
        assertEquals(State.PROBABLE_JAMMING, d.onStats(Sample(4 * min, noiseFloorDbm = -88)).state)
    }

    @Test
    fun overwhelmingNoiseWhilePacketsStillArriveIsNotJamming() {
        d.onStats(Sample(1_000, noiseFloorDbm = -117))
        d.onValidPacket(3 * min + 50_000)
        assertEquals(State.CONGESTED, d.onStats(Sample(4 * min, noiseFloorDbm = -88)).state)
    }

    @Test
    fun saturatedChannelWithSilenceIsJamming() {
        // A LoRa-modulated jammer keeps the channel "busy" (CAD sees preambles) and nothing decodes.
        assertEquals(State.PROBABLE_JAMMING, d.onStats(Sample(4 * min, channelUtilPct = 90f)).state)
    }

    @Test
    fun badPacketBurstWithSilenceAndFailuresIsJamming() {
        d.onStats(Sample(1_000, rxTotal = 100, rxBad = 3))
        d.onAckResult(3 * min, false)
        d.onAckResult(3 * min + 30_000, false)
        // 24 new packets, 22 of them bad: the 2 good ones count as traffic at this sample, so silence is
        // measured from here and jamming needs it to persist.
        assertEquals(State.CONGESTED, d.onStats(Sample(4 * min, rxTotal = 124, rxBad = 25)).state)
        d.onAckResult(7 * min + 10_000, false)
        d.onAckResult(7 * min + 20_000, false)
        assertEquals(State.PROBABLE_JAMMING, d.onStats(Sample(7 * min + 30_000, rxTotal = 140, rxBad = 41)).state)
    }

    @Test
    fun movingGoodCounterIsTrafficEvenIfNothingReachedThePhone() {
        d.onStats(Sample(1_000, noiseFloorDbm = -117, rxTotal = 10, rxBad = 0))
        d.onAckResult(3 * min, false); d.onAckResult(3 * min + 1, false)
        assertEquals(State.CONGESTED, d.onStats(Sample(4 * min, noiseFloorDbm = -100, rxTotal = 15, rxBad = 0)).state)
    }

    @Test
    fun countersGoingBackwardsAreARebootNotABurst() {
        d.onStats(Sample(1_000, rxTotal = 500, rxBad = 100))
        assertEquals(State.CLEAR, d.onStats(Sample(4 * min, rxTotal = 10, rxBad = 9)).state)
    }

    @Test
    fun aValidPacketEndsJammingAtOnce() {
        d.onStats(Sample(1_000, noiseFloorDbm = -117))
        assertEquals(State.PROBABLE_JAMMING, d.onStats(Sample(4 * min, noiseFloorDbm = -85)).state)
        assertEquals(State.PROBABLE_JAMMING, d.evaluate(5 * min).state)
        assertNotEquals(State.PROBABLE_JAMMING, d.onValidPacket(5 * min + 1).state)
    }

    @Test
    fun aSuccessfulAckEndsJamming() {
        d.onStats(Sample(1_000, noiseFloorDbm = -117))
        d.onStats(Sample(4 * min, noiseFloorDbm = -85))
        assertNotEquals(State.PROBABLE_JAMMING, d.onAckResult(5 * min, true).state)
    }

    @Test
    fun quietRfEndsJammingOnlyAfterTheHold() {
        d.onStats(Sample(1_000, noiseFloorDbm = -117))
        assertEquals(State.PROBABLE_JAMMING, d.onStats(Sample(4 * min, noiseFloorDbm = -85)).state)
        // Noise back to normal 30 s later: too soon, a pulsed jammer would make the state flap.
        assertEquals(State.PROBABLE_JAMMING, d.onStats(Sample(4 * min + 30_000, noiseFloorDbm = -116)).state)
        assertEquals(State.CLEAR, d.onStats(Sample(6 * min + 1_000, noiseFloorDbm = -116)).state)
    }

    @Test
    fun theBaselineDoesNotLearnTheJammer() {
        d.onStats(Sample(1_000, noiseFloorDbm = -117))
        d.onStats(Sample(4 * min, noiseFloorDbm = -85))
        // Hours of jamming at -100 must still read as +17 dB over the -117 baseline, not as the new normal.
        for (i in 1..10) d.onStats(Sample(4 * min + i * 30_000L, noiseFloorDbm = -100))
        d.onAckResult(10 * min, false); d.onAckResult(10 * min + 1, false)
        assertEquals(State.PROBABLE_JAMMING, d.evaluate(10 * min + 2).state)
    }

    @Test
    fun staleReadingsStopCounting() {
        d.onStats(Sample(1_000, noiseFloorDbm = -117))
        d.onStats(Sample(4 * min, channelUtilPct = 60f))
        d.onAckResult(4 * min + 1, false); d.onAckResult(4 * min + 2, false)
        assertEquals(State.PROBABLE_JAMMING, d.state)
        // No utilisation report for 25 minutes: the anomaly is no longer evidenced.
        assertEquals(State.CLEAR, d.evaluate(30 * min).state)
    }

    @Test
    fun congestionHasHysteresis() {
        d.onValidPacket(1_000)
        assertEquals(State.CONGESTED, d.onStats(Sample(2_000, channelUtilPct = 26f)).state)
        assertEquals(State.CONGESTED, d.onStats(Sample(3_000, channelUtilPct = 22f)).state)
        assertEquals(State.CLEAR, d.onStats(Sample(4_000, channelUtilPct = 18f)).state)
        assertEquals(State.CLEAR, d.onStats(Sample(5_000, channelUtilPct = 22f)).state)
    }

    @Test
    fun ownAirtimeNearTheDutyCycleIsCongestion() {
        d.onValidPacket(1_000)
        assertEquals(State.CONGESTED, d.onStats(Sample(2_000, airUtilTxPct = 9f)).state)
    }

    @Test
    fun placeholderNoiseValuesAreIgnored() {
        // Stock LocalStats sends 0 when it has no sample; -128 is RadioLib's "invalid".
        d.onStats(Sample(1_000, noiseFloorDbm = 0))
        d.onStats(Sample(2_000, noiseFloorDbm = -128))
        assertEquals(State.CLEAR, d.evaluate(5 * min).state)
    }

    @Test
    fun oldAckFailuresFallOutOfTheWindow() {
        d.onStats(Sample(1_000, noiseFloorDbm = -117))
        d.onAckResult(2 * min, false); d.onAckResult(2 * min + 1, false)
        // 15 minutes later the failures are history; the raised noise alone is only congestion.
        assertEquals(State.CONGESTED, d.onStats(Sample(17 * min, noiseFloorDbm = -100)).state)
    }

    @Test
    fun resetForgetsEverything() {
        d.onStats(Sample(1_000, noiseFloorDbm = -117))
        d.onStats(Sample(4 * min, noiseFloorDbm = -85))
        d.reset(5 * min)
        assertEquals(State.CLEAR, d.state)
        assertEquals(State.CLEAR, d.evaluate(6 * min).state)
    }
}
