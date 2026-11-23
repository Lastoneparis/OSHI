package com.oshi.messenger.service.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Media-over-LoRa parity (2026-09-11): the pre-send confirmation shows an airtime
 * estimate and the send path caps a message at [LoRaEnvelope.MAX_CHUNKS] frames.
 * These pin the pure cost arithmetic both rely on. The real end-to-end send is a
 * two-device LoRa test (no unit test has a radio); this covers the math the UI
 * and the TOO_LARGE refusal compute from. Mirrors iOS LoRaSendEstimateTests.
 */
class LoRaSendEstimateTest {

    @Test
    fun frameCount_matchesChunking() {
        assertEquals(1, LoRaEnvelope.frameCount(0))        // never zero frames
        assertEquals(1, LoRaEnvelope.frameCount(1))
        assertEquals(1, LoRaEnvelope.frameCount(LoRaEnvelope.CHUNK_BYTES))
        assertEquals(2, LoRaEnvelope.frameCount(LoRaEnvelope.CHUNK_BYTES + 1))
        assertEquals(12, LoRaEnvelope.frameCount(LoRaEnvelope.CHUNK_BYTES * 12))
        assertEquals(13, LoRaEnvelope.frameCount(LoRaEnvelope.CHUNK_BYTES * 12 + 1))
        assertEquals(1, LoRaEnvelope.frameCount(-5))       // guarded
    }

    @Test
    fun airtime_followsPacing() {
        assertEquals(0, LoRaEnvelope.airtimeSeconds(0))
        assertEquals(2, LoRaEnvelope.airtimeSeconds(1))
        assertEquals(24, LoRaEnvelope.airtimeSeconds(12))
    }

    @Test
    fun mediaEstimate_isInternallyConsistent() {
        for (media in intArrayOf(0, 100, 500, 1_000, 1_136, 1_200, 5_000, 100_000)) {
            val (frames, seconds, fits) = LoRaEnvelope.mediaEstimate(media)
            assertEquals("seconds must track frames (media=$media)",
                frames * LoRaEnvelope.SEND_SPACING_SECONDS, seconds)
            assertEquals("fits must mirror the frame ceiling (media=$media)",
                frames <= LoRaEnvelope.MAX_CHUNKS, fits)
        }
    }

    @Test
    fun mediaEstimate_boundary() {
        assertTrue(LoRaEnvelope.mediaEstimate(500).third)      // tiny thumbnail fits
        assertFalse(LoRaEnvelope.mediaEstimate(100_000).third) // a real photo does not
    }

    @Test
    fun mediaEstimate_isConservative() {
        // The estimate is never rosier than the raw bytes would need (wireFactor >= 1).
        val media = 900
        val (frames, _, _) = LoRaEnvelope.mediaEstimate(media)
        assertTrue(frames >= LoRaEnvelope.frameCount(media))
    }
}
