package com.oshi.messenger.service

import android.content.SharedPreferences

/**
 * What turns "this contact's identity key changed" from something you could have found out
 * into something you are told. Sibling of
 * [com.oshi.messenger.network.web.LinkedDeviceWatch], and the same shape of defect.
 *
 * ============================================================ THE ATTACK THIS ANSWERS
 *
 * A safety number is the only thing in an end-to-end messenger that distinguishes "encrypted
 * to your contact" from "encrypted to whoever the server says your contact is". Substitute
 * the key the server hands out and every message re-encrypts, silently and correctly, to the
 * substituted key. The crypto is untouched and works exactly as designed — this is the same
 * class of attack as the linked-device one, and it fails for the same reason: nobody looks.
 *
 * [SafetyNumberManager.hasKeyChanged] already detects it, exactly and correctly, and has
 * since the safety-number port landed. Its only caller outside the class was
 * `SafetyNumberViewModel.load` — the safety-number screen. **So the substitution was found
 * only by a user who already suspected one and went looking.** Anyone who did not open that
 * screen was told nothing, ever. A detector wired only to the screen that exists for the
 * suspicious is a detector for people who do not need it.
 *
 * This file adds no detection. It adds an OCCASION for the existing one: a check on the
 * inbound path, so the app says so unprompted at the moment the key changes.
 *
 * ============================================================ ONE SOURCE OF TRUTH, AND WHY
 * IT IS TWO PREFS KEYS
 *
 * [SafetyNumberManager.markContactVerified] already stores a fingerprint, under
 * `verified_<key>`, for precisely this comparison. Storing a second copy of the same number
 * would let the two disagree, and the one the screen reads is the one the user was shown.
 * So this reads that value and never writes it.
 *
 * It cannot write it, and this is the whole design constraint: [SafetyNumberManager.isContactVerified]
 * is `prefs.contains("verified_<key>")`. Seeding a baseline under that key on first sight of
 * a contact would make every contact who has ever sent a message read back as VERIFIED —
 * every badge in the app claiming a verification that never happened. A proactive check that
 * forges verification badges is worse than no proactive check.
 *
 * Hence [SEEN_PREFIX], which means only "the fingerprint we last observed on the wire", and
 * the precedence in [knownFingerprint]:
 *
 * | `verified_` | `keychange.seen.` | baseline                  | a change is                       |
 * |-------------|-------------------|---------------------------|-----------------------------------|
 * | absent      | absent            | none → [Outcome.FirstSight] | not yet knowable                |
 * | absent      | present           | the observed one          | news                              |
 * | present     | absent            | **the verified one**      | the loudest alarm in the app      |
 * | present     | present           | the observed one          | news                              |
 *
 * Row three is the reason precedence runs that way round rather than "seen, or nothing": a
 * contact the user verified in person, whose key changes before this watch ever ran, must not
 * be swallowed as a first sight.
 *
 * ============================================================ THE FIRST-SIGHT COMPROMISE,
 * STATED RATHER THAN HIDDEN
 *
 * `null` means "we have never seen this contact", and it must not alarm: treating a first
 * message as a key change would fire a security warning at every new conversation and at
 * every existing contact on upgrade day, and teach users to dismiss it — worse than no
 * warning at all. The cost is real and worth naming: if the substitution is already in place
 * the first time a contact is seen, that key is adopted as the baseline silently. That is one
 * window, once, per contact, and it is exactly what the safety number is for — which is why
 * the verified fingerprint outranks the observed one above.
 *
 * After a [Outcome.Changed] the caller adopts the new fingerprint too, deliberately: the
 * alarm must fire once and be acknowledged, not on every message forever, which is how an
 * alarm becomes wallpaper. The alert outlives the baseline in
 * [SafetyNumberManager.keyChanges] until it is acknowledged.
 */
object KeyChangeWatch {

    /** What a check amounted to, so the caller can say the right thing. */
    sealed interface Outcome {
        /**
         * Never seen this contact. Nothing is claimed about this key; record it, do not
         * alarm. NOT the same as [Unchanged] — the caller seeds on this one.
         */
        data object FirstSight : Outcome

        /** Same key as last time. Say nothing, write nothing. */
        data object Unchanged : Outcome

        /** The key is not the one we had. This is the one that must reach the user. */
        data class Changed(val previous: String, val current: String) : Outcome
    }

    /**
     * @param address the contact's public key, as the identity this outcome is *about*. It is
     *   a parameter rather than implied so a caller cannot classify without naming who, and
     *   it is load-bearing: a blank address is a transport artefact, not a contact, and
     *   reporting a key change against one would put a security alert on nobody.
     * @param currentFingerprint the safety number just computed from the key on the wire.
     * @param knownFingerprint what [knownFingerprint] returned, or `null` on first sight.
     *   `null` and a stored value mean different things and must never be conflated: `null`
     *   is "we have never seen this contact", a value is "this is who we were talking to",
     *   and only the second makes a different key newsworthy.
     */
    fun classify(
        address: String,
        currentFingerprint: String,
        knownFingerprint: String?,
    ): Outcome {
        if (address.isBlank() || currentFingerprint.isBlank()) return Outcome.FirstSight
        if (knownFingerprint == null) return Outcome.FirstSight
        return if (knownFingerprint == currentFingerprint) Outcome.Unchanged
        else Outcome.Changed(previous = knownFingerprint, current = currentFingerprint)
    }

    // ---------------------------------------------------------------- persistence

    /**
     * The fingerprint the user was shown and confirmed. Owned by
     * [SafetyNumberManager.markContactVerified]; read here, NEVER written here — see the
     * class note on why writing it would forge verification badges.
     */
    fun verifiedKey(address: String) = "verified_$address"

    /** "The fingerprint we last observed", which claims nothing about verification. */
    const val SEEN_PREFIX = "keychange.seen."

    fun seenKey(address: String) = SEEN_PREFIX + address

    /**
     * The baseline to compare against, or `null` when this contact has never been seen.
     *
     * Verified outranks observed only when there is no observation at all; see the table in
     * the class note.
     */
    fun knownFingerprint(prefs: SharedPreferences, address: String): String? =
        prefs.getString(seenKey(address), null)
            ?: prefs.getString(verifiedKey(address), null)

    /** Adopt [fingerprint] as the baseline. Writes the observed key only. */
    fun remember(prefs: SharedPreferences, address: String, fingerprint: String) {
        prefs.edit().putString(seenKey(address), fingerprint).apply()
    }

    /**
     * Forget what we observed for one contact, so the next check seeds instead of reporting a
     * previous identity's key as a change. Leaves `verified_` alone: a verification is the
     * user's own record of a conversation they had in person, and this watch does not get to
     * delete it.
     */
    fun reset(prefs: SharedPreferences, address: String) {
        prefs.edit().remove(seenKey(address)).apply()
    }

    /** Same, for every contact. Used when the account identity is reset. */
    fun resetAll(prefs: SharedPreferences) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(SEEN_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }
}
