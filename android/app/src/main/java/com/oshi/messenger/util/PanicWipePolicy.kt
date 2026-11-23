package com.oshi.messenger.util

/**
 * __PANIC_WIPE_2026_11_03__ "Make this phone stop being evidence."
 *
 * A panic wipe is not account deletion with a shorter confirmation sheet. The
 * two differ on the only axis that matters when someone is reaching for it:
 *
 * | | Delete account | Panic wipe |
 * |---|---|---|
 * | Asks the server first | Yes — `DELETE /v2/account`, signed, and it ABORTS if the request fails | Never |
 * | Needs a network | Yes | No. It must finish in a basement, in a kettle, in airplane mode |
 * | Confirmation | A sheet, deliberately | None. The gesture IS the confirmation |
 * | Server records | Removed | Left. Unreachable by anyone, including their owner |
 *
 * That last row is the deliberate trade. [SettingsViewModel.deleteAccount] is
 * careful to reach the server BEFORE destroying the signing key, because wiping
 * first orphans the user's server-side records for ever. A panic wipe accepts
 * exactly that cost: orphaned rows on a relay are not what gets someone hurt at
 * a demonstration, and a wipe that can be defeated by jamming the signal is not
 * a wipe. So panic never waits for the network, and never aborts.
 *
 * Pure and Android-free, like [PrefsWipePolicy], so the ORDER and the invariants
 * below are unit-testable on the JVM. The step bodies live in
 * `SettingsViewModel.panicWipe()`; what belongs here is the part that is easy to
 * get wrong and invisible when you do.
 *
 * iOS mirror: `OSHI/PanicWipePolicy.swift`.
 */
object PanicWipePolicy {

    /**
     * The steps of a panic wipe, in the order they must run.
     *
     * The order is not cosmetic. Each position below is load-bearing, and
     * [ORDER] is asserted against these reasons in `PanicWipePolicyTest`.
     */
    enum class Step {
        /**
         * First, always. While the radios are up this phone is still
         * advertising a BLE identity, still relaying other people's mesh
         * traffic and still answering LoRa. Wiping the database under a live
         * mesh session leaves the device transmitting under a name whose keys
         * no longer exist — visible, attributable, and talking.
         *
         * It is also the step that helps even if everything after it fails: a
         * phone that has gone quiet has already stopped adding to the record.
         */
        STOP_RADIOS,

        /**
         * The notification shade is a copy of the message list that
         * `clearAllTables()` does not touch. Wipe the database and leave the
         * shade, and the message previews are still there to be read off a
         * locked screen — the one surface the person panicking is most likely
         * to be holding up to someone.
         */
        CANCEL_NOTIFICATIONS,

        /**
         * Decrypted media and scratch files before the keys that protect them.
         * Files whose key is already gone are noise; files whose key is gone
         * but which were never deleted are still a thumbnail away from being
         * recovered by a forensic tool that does not need our key at all.
         */
        WIPE_MEDIA,

        /** Messages, contacts, call records, group state. */
        WIPE_DATABASE,

        /** Identity key, ratchet chain keys, X3DH prekeys. */
        WIPE_KEYS,

        /**
         * Last of the destructive steps, and the one most easily forgotten —
         * which is why skipping it is tested.
         *
         * `oshi_identity_backup` is a SharedPreferences blob that
         * `MainViewModel.recoverIdentityFromDevice()` silently re-onboards from
         * on the next launch. A panic wipe that erased the keys, the database
         * and the media but left that file would hand the identity straight
         * back the moment the app was reopened — and would look, to the person
         * who used it, exactly like a wipe that worked.
         */
        WIPE_PREFERENCES,

        /**
         * Dropping `onboarded` is what `MainViewModel` observes, so the app
         * returns to the first-run screen on its own. That is the only feedback
         * a panic wipe gives, and it is the right one: no toast to screenshot,
         * no dialog to dismiss, and a device that looks like a fresh install.
         */
        RETURN_TO_ONBOARDING,
    }

    /** The one true order. */
    val ORDER: List<Step> = listOf(
        Step.STOP_RADIOS,
        Step.CANCEL_NOTIFICATIONS,
        Step.WIPE_MEDIA,
        Step.WIPE_DATABASE,
        Step.WIPE_KEYS,
        Step.WIPE_PREFERENCES,
        Step.RETURN_TO_ONBOARDING,
    )

    /**
     * Steps whose failure leaves the identity or its data RECOVERABLE, and
     * which therefore may never be skipped, short-circuited or made
     * conditional on anything — least of all on a network call succeeding.
     */
    val MUST_RUN: Set<Step> = setOf(
        Step.WIPE_MEDIA,
        Step.WIPE_DATABASE,
        Step.WIPE_KEYS,
        Step.WIPE_PREFERENCES,
    )

    /**
     * No step of a panic wipe may depend on the network. Kept as a function
     * rather than a comment so that a step added later has to answer the
     * question, and a step that answers it wrong fails a test.
     */
    fun requiresNetwork(step: Step): Boolean = when (step) {
        Step.STOP_RADIOS,
        Step.CANCEL_NOTIFICATIONS,
        Step.WIPE_MEDIA,
        Step.WIPE_DATABASE,
        Step.WIPE_KEYS,
        Step.WIPE_PREFERENCES,
        Step.RETURN_TO_ONBOARDING -> false
    }

    /**
     * How long the DETACHED push de-registration may run for.
     *
     * De-registering is worth attempting: the relay otherwise keeps pushing to
     * a key this device no longer has, the "phantom ringing" symptom iOS
     * documents in `IdentityManager.disconnectWallet()`. But it is a network
     * round trip, and a panic wipe waits for no network at all — so the wipe
     * does not await it. It is launched and abandoned, and this budget bounds
     * only that abandoned attempt.
     *
     * Note what this constant is NOT: a timeout that makes the wipe fast. The
     * request inside `FCMService.unregisterThisDevice` is a blocking
     * `HttpURLConnection`, and cancelling a coroutine does not interrupt a
     * socket already blocked in `read`. A timeout wrapped around it would
     * return on schedule while the thread stayed stuck — a bound in name only.
     * The wipe is fast because it never waits, not because of this number.
     */
    const val PUSH_UNREGISTER_BUDGET_MS: Long = 500L

    /**
     * A wipe in flight swallows further triggers. There is no confirmation
     * dialog to absorb a shaking hand, so the fourth and fifth taps of a
     * frightened triple-tap arrive as fresh requests; running two wipes
     * concurrently over one database is how you get a crash instead of a wipe.
     */
    fun shouldStart(alreadyRunning: Boolean): Boolean = !alreadyRunning

    /** Did a wipe that reported these steps actually finish the job? */
    fun isComplete(completed: Set<Step>): Boolean = completed.containsAll(MUST_RUN)
}

/**
 * __PANIC_WIPE_2026_11_03__ The gesture that fires [PanicWipePolicy].
 *
 * Three taps on the OSHI wordmark, and nothing else: no menu entry, no settings
 * row, no long-press. A panic switch that has to be found in a menu is not one,
 * and a labelled button marked "erase everything" is a button an officer can be
 * asked to press.
 *
 * Pure, so the thing that is genuinely hard here — telling a triple-tap apart
 * from three deliberate single taps without a timer in the test — is testable.
 */
object PanicGesture {

    /** Taps needed. Matches bitchat's own triple-tap, for muscle memory. */
    const val TAPS_REQUIRED: Int = 3

    /**
     * How long the taps have to arrive in. Short enough that three separate
     * visits to the screen cannot add up to a wipe; long enough to survive a
     * cold phone, gloves, and a hand that is not steady.
     */
    const val WINDOW_MILLIS: Long = 1_500L

    /**
     * Taps still inside the window at [now], oldest first.
     *
     * Dropping the stale ones is what stops a tap from yesterday being the
     * first of today's three. Call this before [shouldTrigger] and keep the
     * result as the new tap list.
     */
    fun retained(taps: List<Long>, now: Long): List<Long> =
        taps.filter { now - it < WINDOW_MILLIS }

    /**
     * `true` when [taps] — already filtered by [retained] — completes the
     * gesture. The caller clears its tap list on `true`, so the tap AFTER a
     * wipe starts counting from nothing rather than firing again.
     */
    fun shouldTrigger(taps: List<Long>): Boolean = taps.size >= TAPS_REQUIRED

    // ── (N6, Anatole 13/11) Opt-in ────────────────────────────────────────────
    //
    // "Erase everything after a few taps on the logo" surprised a tester who had
    // never asked for it: three taps on a wordmark happen by accident, and the
    // cost of an accident is the whole phone. The gesture is now ARMED only by a
    // switch in Settings → Security, off by default. Still no confirmation once
    // armed — that is the point of a panic switch — but nobody gets it unasked.

    /** DataStore key (in the app's main `settings` store) for the opt-in. */
    const val PREF_KEY: String = "panic_wipe_on_logo_enabled"

    /** A fresh install, an upgrade from a build without the switch, a wiped phone: off. */
    const val DEFAULT_ARMED: Boolean = false

    /**
     * Does the gesture do anything? Only an explicit stored `true` arms it; an
     * absent key (every install before this switch existed) is OFF, so an
     * upgrade does not silently keep a destructive gesture live.
     */
    fun isArmed(storedValue: Boolean?): Boolean = storedValue ?: DEFAULT_ARMED
}
