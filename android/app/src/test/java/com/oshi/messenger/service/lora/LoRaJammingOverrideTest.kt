package com.oshi.messenger.service.lora

import com.oshi.messenger.service.lora.LoRaJammingDetector.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __ANTI_JAMMING_OVERRIDE_2026_11_13__ "Send over LoRa anyway": 10 minutes, or until the detector says
 * CLEAR, whichever comes first — and never a way to switch LoRa back on when nothing excluded it.
 */
class LoRaJammingOverrideTest {

    private val min = 60_000L
    private var now = 1_000_000L
    private val o = LoRaJammingOverride(clock = { now })

    @Test
    fun aJammedRadioIsExcludedUntilThePersonOverrules() {
        assertTrue(o.excludesLoRa(State.PROBABLE_JAMMING))
        assertEquals(now + 10 * min, o.activate(State.PROBABLE_JAMMING))
        assertFalse(o.excludesLoRa(State.PROBABLE_JAMMING))
        assertTrue(o.isActive())
    }

    @Test
    fun onlyJammingExcludesLoRaWithOrWithoutAnOverride() {
        assertFalse(o.excludesLoRa(State.CLEAR))
        assertFalse(o.excludesLoRa(State.CONGESTED))
    }

    @Test
    fun thereIsNothingToOverruleUnlessTheDetectorSaysJammed() {
        assertNull(o.activate(State.CLEAR))
        assertNull(o.activate(State.CONGESTED))
        assertFalse(o.isActive())
        assertNull(o.activeUntil())
    }

    @Test
    fun itRunsOutAfterTenMinutes() {
        val end = o.activate(State.PROBABLE_JAMMING)!!
        now = end - 1
        assertFalse(o.excludesLoRa(State.PROBABLE_JAMMING))
        assertEquals(end, o.activeUntil())
        now = end
        assertTrue(o.excludesLoRa(State.PROBABLE_JAMMING))
        assertNull(o.activeUntil())
        // And stays off: time going on does not bring it back.
        now += 60 * min
        assertTrue(o.excludesLoRa(State.PROBABLE_JAMMING))
    }

    @Test
    fun aClearChannelEndsItEarly() {
        o.activate(State.PROBABLE_JAMMING)
        now += 2 * min
        assertTrue(o.onDetectorState(State.CLEAR))
        assertFalse(o.isActive())
        // A fresh jamming verdict after CLEAR has not been overruled.
        assertTrue(o.excludesLoRa(State.PROBABLE_JAMMING))
    }

    @Test
    fun congestionDoesNotEndIt() {
        val end = o.activate(State.PROBABLE_JAMMING)
        now += 3 * min
        assertFalse(o.onDetectorState(State.CONGESTED))
        assertFalse(o.onDetectorState(State.PROBABLE_JAMMING))
        assertEquals(end, o.activeUntil())
        assertFalse(o.excludesLoRa(State.PROBABLE_JAMMING))
    }

    @Test
    fun clearWithNoActiveOverrideReportsNoChange() {
        assertFalse(o.onDetectorState(State.CLEAR))
        val end = o.activate(State.PROBABLE_JAMMING)!!
        now = end + 1   // already ran out on its own
        assertFalse(o.onDetectorState(State.CLEAR))
    }

    @Test
    fun tappingAgainRestartsTheTenMinutes() {
        o.activate(State.PROBABLE_JAMMING)
        now += 7 * min
        assertEquals(now + 10 * min, o.activate(State.PROBABLE_JAMMING))
        now += 9 * min
        assertTrue(o.isActive())
    }

    @Test
    fun cancelEndsIt() {
        o.activate(State.PROBABLE_JAMMING)
        o.cancel()
        assertFalse(o.isActive())
        assertTrue(o.excludesLoRa(State.PROBABLE_JAMMING))
    }

    @Test
    fun theDurationIsConfigurable() {
        val short = LoRaJammingOverride(clock = { now }, durationMs = 30_000L)
        assertEquals(now + 30_000L, short.activate(State.PROBABLE_JAMMING))
        assertEquals(10 * min, LoRaJammingOverride.DEFAULT_DURATION_MS)
    }
}
