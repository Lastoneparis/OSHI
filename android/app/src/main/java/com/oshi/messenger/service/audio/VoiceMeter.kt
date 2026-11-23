package com.oshi.messenger.service.audio

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * __VOICE_METER_2026_09_22__ — port of iOS `VoiceCallManager.meterLevel(rms:)` and the
 * call screen's `LevelSmoother` (VoiceCallView.swift, same tag).
 *
 * The Android call screen drew a sine wave on a 16 ms timer that moved identically
 * whether anyone spoke or not. These are the pieces that let it follow the VOICE: my
 * microphone and the peer's voice, on a dB scale, with fast attack and soft release.
 *
 * Pure — no Android types — so `VoiceMeterTest` covers it on the JVM.
 */
object VoiceMeter {

    /** Room noise: maps to 0. */
    const val FLOOR_DBFS = -55.0

    /** Loud voice: maps to 1. */
    const val CEILING_DBFS = -12.0

    /** UI publish interval (iOS `audioLevelUpdateInterval` = 50 ms, 20 Hz). */
    const val PUBLISH_INTERVAL_MS = 50L

    /**
     * RMS (full scale = 1.0) → 0…1 on a dB scale: −55 dBFS → 0, −12 dBFS → 1.
     * A linear meter only moved when shouting — the ear is logarithmic.
     */
    fun meterLevel(rms: Double): Float {
        if (rms <= 0.0 || rms.isNaN()) return 0f
        val db = 20.0 * log10(rms)
        return ((db - FLOOR_DBFS) / (CEILING_DBFS - FLOOR_DBFS)).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * RMS of signed 16-bit little-endian mono PCM, normalised to full scale 1.0.
     * Reads at most [length] bytes; an odd trailing byte is ignored.
     */
    fun rmsInt16Le(pcm: ByteArray, length: Int = pcm.size): Double {
        val n = (length.coerceAtMost(pcm.size)) / 2
        if (n <= 0) return 0.0
        var sumSq = 0.0
        var i = 0
        while (i < n) {
            val lo = pcm[2 * i].toInt() and 0xFF
            val hi = pcm[2 * i + 1].toInt()
            val s = ((hi shl 8) or lo).toShort().toDouble() / 32768.0
            sumSq += s * s
            i++
        }
        return sqrt(sumSq / n)
    }

    /** Convenience: [meterLevel] of [rmsInt16Le]. */
    fun levelOfInt16Le(pcm: ByteArray, length: Int = pcm.size): Float =
        meterLevel(rmsInt16Le(pcm, length))
}

/**
 * Attack/release smoothing for the call-screen bars. Attack τ = 30 ms (≈ 2 frames),
 * release τ = 150 ms: follows syllables without flicker, and settles flat in silence —
 * which is what makes the motion readable when someone does speak.
 */
class LevelSmoother {
    private var value = 0f
    private var lastT = 0.0

    /** @param tSeconds a monotonic time in seconds. */
    fun step(target: Float, tSeconds: Double): Float {
        val dt = if (lastT == 0.0) 1.0 / 60 else (tSeconds - lastT).coerceIn(0.0, 0.1)
        lastT = tSeconds
        val tau = if (target > value) 0.03 else 0.15
        val k = (1 - exp(-dt / tau)).toFloat()
        value += (target - value) * k
        return value
    }
}
