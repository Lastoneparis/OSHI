package com.oshi.messenger.service.audio

/**
 * __CALL_TX_SILENT_DROP_2026_11_10__ Why captured voice did not leave this phone.
 *
 * Call 608d4c6a (Android 1.6.86 → iOS, 56 s, rated 2/5 "theyCouldNotHearMe"): the
 * microphone captured voice (44 % of frames, peak 100 %), yet `sendAudio` produced
 * TWO frames in 56 s (~2 800 expected) and the relay received 70 packets / 3 KB from
 * this side. Every way out of `sendAudio` before a frame is counted was silent — an
 * early `return` without a key, an Opus encode that threw or yielded nothing
 * (`?: return@let`), and a `catch (e: Exception) { /* Ignore */ }` around the whole
 * body — so the report could only say "voice was captured", and the call stayed dead
 * for its whole length: a broken encoder was never rebuilt.
 *
 * This counts each kind of drop (sent with the rating, so the next report names the
 * stage) and decides the recovery for a failing Opus encoder: rebuild it after
 * [FAILS_BEFORE_ACTION] consecutive failed full frames (200 ms), and after
 * [RESETS_BEFORE_FALLBACK] rebuilds that did not help, stop using Opus for the rest
 * of the call — the send path then falls back to WB-ADPCM / raw PCM, which every iOS
 * build decodes. A degraded codec beats a silent call.
 */
class TxHealth {
    enum class Action { NONE, RESET_ENCODER, DISABLE_OPUS }

    var droppedNoKey = 0L; private set
    var droppedEncode = 0L; private set
    var droppedError = 0L; private set
    var opusResets = 0; private set
    var opusDisabled = false; private set
    var lastError: String? = null; private set
    private var consecutiveOpusFails = 0

    fun reset() {
        droppedNoKey = 0; droppedEncode = 0; droppedError = 0
        opusResets = 0; opusDisabled = false; lastError = null; consecutiveOpusFails = 0
    }

    fun noKey() { droppedNoKey++ }

    fun error(t: Throwable) {
        droppedError++
        lastError = (t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")).take(60)
    }

    fun opusOk() { consecutiveOpusFails = 0 }

    /** A full frame went in and no payload came out (or the encoder threw). */
    fun opusFailed(t: Throwable? = null): Action {
        droppedEncode++
        if (t != null) lastError = ("opus " + t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")).take(60)
        consecutiveOpusFails++
        if (consecutiveOpusFails < FAILS_BEFORE_ACTION) return Action.NONE
        consecutiveOpusFails = 0
        return if (opusResets < RESETS_BEFORE_FALLBACK) {
            opusResets++
            Action.RESET_ENCODER
        } else {
            opusDisabled = true
            Action.DISABLE_OPUS
        }
    }

    /** Total frames captured but never sent. */
    val dropped: Long get() = droppedNoKey + droppedEncode + droppedError

    companion object {
        const val FAILS_BEFORE_ACTION = 10
        const val RESETS_BEFORE_FALLBACK = 2
    }
}
