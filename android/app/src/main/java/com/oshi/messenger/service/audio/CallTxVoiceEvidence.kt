package com.oshi.messenger.service.audio

import kotlin.math.pow

/**
 * __TX_VOICE_EVIDENCE_2026_10_29__ Was there a VOICE in what we transmitted?
 *
 * WHY: call `97d98d39` was rated 1/5 `theyCouldNotHearMe`, and the server record
 * exonerated every hop between the two phones — 1:25, **4257 frames relayed in the
 * complained-about direction** (50/s, i.e. an unbroken 20 ms stream for the whole
 * call), verdict symmetric at ratio 0.99, UDP proven in both directions, nothing
 * buffered. The relay carried the bytes, and it carried them the whole time.
 *
 * Mute was already ruled out by the code: `EnhancedCallManager` drops outgoing
 * frames while muted at two separate guards, so a muted caller relays ~0 frames,
 * not 4257.
 *
 * That left exactly two explanations — our microphone captured silence, or the
 * peer failed to decode or play what arrived — and **nothing anywhere could tell
 * them apart**, because the only evidence that exists is counted at the relay,
 * which sees byte counts and cannot see whether the bytes encode a voice. The
 * client already measures that ([VoiceMeter], at 20 Hz, for the call screen) and
 * then throws it away when the call ends.
 *
 * This keeps it. The share of our own capture that was above the room-noise floor
 * rides with the rating, so the next report of this kind names the side to look at
 * instead of leaving the question open.
 *
 * Pure — no Android types — so `CallTxVoiceEvidenceTest` covers it on the JVM.
 */
class CallTxVoiceEvidence {

    private var observed: Long = 0
    private var aboveFloor: Long = 0
    private var peak: Float = 0f

    /**
     * One capture frame's meter level, as [VoiceMeter.meterLevel] returns it.
     *
     * Only frames we actually transmit belong here: a muted frame is never sent,
     * so counting it would dilute the very number this exists to report.
     */
    fun observe(level: Float) {
        if (level.isNaN()) return
        val clamped = level.coerceIn(0f, 1f)
        observed++
        if (clamped >= VOICE_FLOOR_LEVEL) aboveFloor++
        if (clamped > peak) peak = clamped
    }

    /** Start of a call. The next call's evidence must not inherit this one's. */
    fun reset() {
        observed = 0
        aboveFloor = 0
        peak = 0f
    }

    /** `null` when nothing was observed — no measurement is not a measurement of zero. */
    fun snapshot(): Snapshot? {
        if (observed <= 0) return null
        return Snapshot(
            observedFrames = observed,
            voicePercent = ((aboveFloor * 100.0) / observed).toInt().coerceIn(0, 100),
            peakPercent = (peak * 100f).toInt().coerceIn(0, 100)
        )
    }

    data class Snapshot(
        val observedFrames: Long,
        /** 0…100: share of transmitted frames whose level cleared [VOICE_FLOOR_LEVEL]. */
        val voicePercent: Int,
        /** 0…100: the loudest frame of the call, on the same scale. */
        val peakPercent: Int
    )

    companion object {
        /**
         * −45 dBFS RMS, expressed on [VoiceMeter]'s scale so the two cannot drift
         * apart. Comfortably above [VoiceMeter.FLOOR_DBFS] (−55, room noise) and
         * well below ordinary speech (−30…−20), which is what makes "0%" mean a
         * dead microphone rather than a quiet room.
         */
        const val VOICE_FLOOR_DBFS = -45.0

        val VOICE_FLOOR_LEVEL: Float =
            VoiceMeter.meterLevel(10.0.pow(VOICE_FLOOR_DBFS / 20.0))
    }
}
