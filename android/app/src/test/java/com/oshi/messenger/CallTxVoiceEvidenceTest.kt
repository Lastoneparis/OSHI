package com.oshi.messenger

import com.oshi.messenger.service.audio.CallTxVoiceEvidence
import com.oshi.messenger.service.audio.VoiceMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * __TX_VOICE_EVIDENCE_2026_10_29__ The number that tells a silent microphone apart from
 * a peer that did not play what arrived. See [CallTxVoiceEvidence] for the call that
 * made it necessary.
 */
class CallTxVoiceEvidenceTest {

    private fun levelAt(dbfs: Double): Float = VoiceMeter.meterLevel(10.0.pow(dbfs / 20.0))

    @Test
    fun `nothing observed is not a measurement of zero`() {
        assertNull(CallTxVoiceEvidence().snapshot())
    }

    @Test
    fun `a call of room noise reports no voice`() {
        val e = CallTxVoiceEvidence()
        // -60 dBFS is below VoiceMeter's own floor: the meter reads 0.
        repeat(1_700) { e.observe(levelAt(-60.0)) }
        val s = e.snapshot()!!
        assertEquals(1_700L, s.observedFrames)
        assertEquals(0, s.voicePercent)
        assertEquals(0, s.peakPercent)
    }

    @Test
    fun `a call of speech reports voice throughout`() {
        val e = CallTxVoiceEvidence()
        repeat(1_700) { e.observe(levelAt(-25.0)) }
        val s = e.snapshot()!!
        assertEquals(100, s.voicePercent)
        assertTrue("peak should be well up the scale, was ${s.peakPercent}", s.peakPercent > 50)
    }

    @Test
    fun `half speech half silence reports about half`() {
        val e = CallTxVoiceEvidence()
        repeat(500) { e.observe(levelAt(-25.0)) }
        repeat(500) { e.observe(levelAt(-60.0)) }
        assertEquals(50, e.snapshot()!!.voicePercent)
    }

    @Test
    fun `the floor sits between room noise and speech`() {
        // The whole point of the number: quiet room must read 0, ordinary speech 100.
        // A floor that drifted into either would make the field answer the wrong question.
        assertTrue(levelAt(-55.0) < CallTxVoiceEvidence.VOICE_FLOOR_LEVEL)
        assertTrue(levelAt(-30.0) > CallTxVoiceEvidence.VOICE_FLOOR_LEVEL)
    }

    @Test
    fun `peak survives a single loud frame among quiet ones`() {
        val e = CallTxVoiceEvidence()
        repeat(100) { e.observe(levelAt(-60.0)) }
        e.observe(1.0f)
        repeat(100) { e.observe(levelAt(-60.0)) }
        val s = e.snapshot()!!
        assertEquals(100, s.peakPercent)
        assertEquals(0, s.voicePercent)   // 1 of 201 frames rounds down to 0%
    }

    @Test
    fun `reset clears the previous call`() {
        val e = CallTxVoiceEvidence()
        repeat(10) { e.observe(1.0f) }
        e.reset()
        assertNull(e.snapshot())
    }

    @Test
    fun `out of range and NaN levels cannot corrupt the share`() {
        val e = CallTxVoiceEvidence()
        e.observe(Float.NaN)                 // ignored outright
        assertNull(e.snapshot())
        repeat(10) { e.observe(5f) }         // clamped to 1.0
        repeat(10) { e.observe(-5f) }        // clamped to 0.0
        val s = e.snapshot()!!
        assertEquals(20L, s.observedFrames)
        assertEquals(50, s.voicePercent)
        assertEquals(100, s.peakPercent)
    }
}
