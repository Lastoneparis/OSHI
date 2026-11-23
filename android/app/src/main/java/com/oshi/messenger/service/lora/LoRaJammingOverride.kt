package com.oshi.messenger.service.lora

import com.oshi.messenger.service.lora.LoRaJammingDetector.State

/**
 * __ANTI_JAMMING_OVERRIDE_2026_11_13__ "Send over LoRa anyway".
 *
 * [LoRaJammingDetector] can be wrong. On a false PROBABLE_JAMMING, a person who is off-grid with no
 * Bluetooth neighbour has NOTHING else: LoRa is left out of `MessageSendPolicy.order()`, the radio holds
 * the messages, and they sit there until the detector changes its mind. This lets the person overrule it.
 *
 * Rules:
 *  - it can only be switched on while the detector says PROBABLE_JAMMING (there is nothing to overrule
 *    otherwise);
 *  - it lasts [durationMs] (10 min) from the tap, or until the detector returns to CLEAR, whichever comes
 *    first. CONGESTED does not end it (that is not "the channel is fine"), and a CLEAR followed by a new
 *    PROBABLE_JAMMING is a new verdict the person has not overruled yet, so it is not carried over;
 *  - tapping again while active restarts the 10 minutes;
 *  - [cancel] ends it (the radio disconnected, a fresh detector baseline).
 *
 * Pure Kotlin, in memory only (deliberately not persisted: an override must not outlive the situation
 * it was given for). The clock is injected so the whole contract is unit-tested (LoRaJammingOverrideTest).
 */
class LoRaJammingOverride(
    private val clock: () -> Long = System::currentTimeMillis,
    val durationMs: Long = DEFAULT_DURATION_MS,
) {
    companion object {
        const val DEFAULT_DURATION_MS = 10 * 60_000L
    }

    /** End of the override in epoch millis; 0 = none. */
    private var until = 0L

    /**
     * The person tapped "Send over LoRa anyway" while the detector says [state].
     * Returns when the override ends, or null when there is nothing to overrule.
     */
    @Synchronized
    fun activate(state: State): Long? {
        if (state != State.PROBABLE_JAMMING) return null
        until = clock() + durationMs
        return until
    }

    /**
     * The detector moved to [state]. Returns true when this ended an active override (CLEAR), so the
     * caller can publish the change.
     */
    @Synchronized
    fun onDetectorState(state: State): Boolean {
        if (state != State.CLEAR) return false
        val wasActive = activeUntilLocked() != null
        until = 0L
        return wasActive
    }

    @Synchronized
    fun cancel() {
        until = 0L
    }

    /** When the active override ends (epoch millis), or null when none is active. */
    @Synchronized
    fun activeUntil(): Long? = activeUntilLocked()

    fun isActive(): Boolean = activeUntil() != null

    /**
     * What routing obeys: true when LoRa must be left out — the detector says PROBABLE_JAMMING and the
     * person has not overruled it (or their override has run out).
     */
    fun excludesLoRa(state: State): Boolean = state == State.PROBABLE_JAMMING && !isActive()

    private fun activeUntilLocked(): Long? {
        if (until == 0L) return null
        if (clock() >= until) {
            until = 0L
            return null
        }
        return until
    }
}
