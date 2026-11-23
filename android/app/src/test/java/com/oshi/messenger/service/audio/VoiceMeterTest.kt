package com.oshi.messenger.service.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** __VOICE_METER_2026_09_22__ */
class VoiceMeterTest {

    @Test
    fun db_scale_endpoints_match_ios() {
        assertEquals(0f, VoiceMeter.meterLevel(0.0), 0f)
        // −55 dBFS → 0, −12 dBFS → 1 (iOS `(db + 55) / 43`).
        assertEquals(0f, VoiceMeter.meterLevel(Math.pow(10.0, -55.0 / 20)), 1e-4f)
        assertEquals(1f, VoiceMeter.meterLevel(Math.pow(10.0, -12.0 / 20)), 1e-4f)
        assertEquals(1f, VoiceMeter.meterLevel(1.0), 0f)
        assertEquals(0.5f, VoiceMeter.meterLevel(Math.pow(10.0, -33.5 / 20)), 1e-4f)
    }

    @Test
    fun normal_speech_moves_the_meter_where_the_linear_one_did_not() {
        // iOS measured ~0.03 RMS while talking: the old ×5 linear meter gave 0.15, the
        // view's floor. On the dB scale that is ≈ 0.57.
        val l = VoiceMeter.meterLevel(0.03)
        assertTrue("got $l", l in 0.5f..0.65f)
    }

    @Test
    fun rms_of_int16_le() {
        // Full-scale square wave: ±32767 → RMS ≈ 1.
        val pcm = ByteArray(960 * 2)
        for (i in 0 until 960) {
            val v = if (i % 2 == 0) 32767 else -32767
            pcm[2 * i] = (v and 0xFF).toByte(); pcm[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        assertEquals(1.0, VoiceMeter.rmsInt16Le(pcm), 1e-3)
        assertEquals(0.0, VoiceMeter.rmsInt16Le(ByteArray(1920)), 0.0)
        assertEquals(0.0, VoiceMeter.rmsInt16Le(ByteArray(1)), 0.0)
        // `length` bounds the read (AudioRecord.read may return a short count).
        assertEquals(0.0, VoiceMeter.rmsInt16Le(pcm, 0), 0.0)
    }

    @Test
    fun smoother_attacks_fast_and_releases_slowly() {
        val s = LevelSmoother()
        var t = 1.0
        var v = 0f
        repeat(6) { t += 1.0 / 60; v = s.step(1f, t) } // 100 ms of attack
        assertTrue("attack too slow: $v", v > 0.9f)
        repeat(6) { t += 1.0 / 60; v = s.step(0f, t) } // 100 ms of release
        assertTrue("release too fast: $v", v > 0.4f)
        repeat(60) { t += 1.0 / 60; v = s.step(0f, t) }
        assertTrue("never settles: $v", v < 0.01f)
    }
}
