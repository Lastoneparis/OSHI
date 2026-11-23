package com.oshi.messenger.network.web

import android.content.SharedPreferences

/**
 * What turns "a device was linked" from something you could have found out into something
 * you are told. Android port of `OSHI/LinkedDeviceWatch.swift`.
 *
 * ============================================================ THE ATTACK THIS ANSWERS
 *
 * Netzpolitik published documents in 2026 showing Germany's Customs Office reading Signal,
 * WhatsApp and Telegram messages **without breaking any encryption**. The method is the
 * linked-device feature: with brief access to an unlocked phone, an investigator links a
 * computer they control and receives everything from then on, in plaintext, on their own
 * machine. The crypto is untouched and works exactly as designed.
 *
 * Every one of those apps already shows a list of linked devices, and OSHI does too
 * ([WebSessionManager.fetchActiveSessions]). A list is not a defence. **The method works
 * because nobody opens that screen.** The defence is the app saying so, unprompted, at the
 * moment it happens.
 *
 * ============================================================ WHY THIS FILE EXISTS TWICE
 *
 * iOS shipped this first, in 1.0.45. Android had the identical gap — `_activeSessions.value
 * = list` with nothing compared against the previous set, and every `notify*` in the
 * manager about messages rather than pairing. A defence that exists on one platform of a
 * two-platform messenger protects the half of the users who happen to have the right phone.
 *
 * It is a port and not shared code because there is nowhere to share it to: the shared
 * `network/v2` core is compiled by the desktop and must stay free of `android.*`, and this
 * needs [SharedPreferences]. The logic is nine lines; the decisions in it are the part
 * worth keeping identical, and they are written down in both files.
 *
 * ============================================================ THE FIRST-RUN COMPROMISE,
 * STATED RATHER THAN HIDDEN
 *
 * On the very first check there is no previous set, so [newlyLinked] reports nothing and
 * the caller seeds the baseline. Treating every existing session as new would fire a
 * security alert at every user on upgrade day and teach them to dismiss it, which is worse
 * than not warning at all.
 *
 * The cost is real and worth naming: a device linked **before** this first runs is adopted
 * into the baseline silently. That is one window, once, and it is why [Outcome]
 * distinguishes a seed from a quiet check — the caller shows the existing sessions after a
 * seed rather than saying nothing.
 */
object LinkedDeviceWatch {

    /** What a check amounted to, so the caller can say the right thing. */
    sealed interface Outcome {
        /** First ever check. Nothing is claimed about these sessions; show them, do not alarm. */
        data class Seeded(val count: Int) : Outcome

        /** Nothing changed. */
        data object Unchanged : Outcome

        /** These appeared since last time. This is the one that must reach the user. */
        data class Linked(val sessionIds: List<String>) : Outcome
    }

    /**
     * The ids present now and absent from [known].
     *
     * @param known ids remembered from the previous check, or `null` on the very first one.
     *   `null` and an empty set mean different things and must not be conflated: `null` is
     *   "we have never looked", empty is "last time there were none", and only the second
     *   makes an appearing device newsworthy.
     */
    fun newlyLinked(fetched: List<String>, known: Set<String>?): List<String> {
        if (known == null) return emptyList()
        return fetched.filter { it !in known }
    }

    fun classify(fetched: List<String>, known: Set<String>?): Outcome {
        if (known == null) return Outcome.Seeded(fetched.size)
        val new = newlyLinked(fetched, known)
        return if (new.isEmpty()) Outcome.Unchanged else Outcome.Linked(new)
    }

    // ---------------------------------------------------------------- persistence

    /**
     * `SharedPreferences` and not the keystore: these are session identifiers the server
     * hands out, not secrets, and a baseline that survived reinstall would be one the user
     * could not reset when they deliberately start over.
     */
    const val KEY = "oshi.linkedDevices.known"

    /** Null when no check has ever run — see the note on [newlyLinked]. */
    fun loadKnown(prefs: SharedPreferences): Set<String>? =
        if (prefs.contains(KEY)) prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
        else null

    fun saveKnown(prefs: SharedPreferences, ids: List<String>) {
        // A COPY, not the caller's collection. `getStringSet` documents that the returned
        // set must not be modified, and storing an instance the caller still holds is the
        // same mistake pointed the other way.
        prefs.edit().putStringSet(KEY, LinkedHashSet(ids)).apply()
    }

    /**
     * Forget the baseline, so the next check seeds instead of reporting a previous
     * identity's sessions as newly linked. Used when the account is reset.
     */
    fun reset(prefs: SharedPreferences) {
        prefs.edit().remove(KEY).apply()
    }
}
