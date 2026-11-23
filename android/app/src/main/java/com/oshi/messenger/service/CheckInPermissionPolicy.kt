package com.oshi.messenger.service

import com.oshi.messenger.data.model.CheckInStatus

/**
 * Which location dialog an armed check-in still owes the user, and whether the
 * check-in can honestly be armed at all.
 *
 * Deliberately pure Kotlin — no `android.*` imports — so the decision table is
 * covered by JVM unit tests instead of only being exercisable on a device. The
 * Android-facing halves (reading the grants, launching the dialog, starting the
 * service) live in `CheckInLocationService` / `CheckInScreen` and call in here.
 *
 * ══ ONE STEP, NOT TWO (changed 2026-10-24) ══
 *
 * This used to be a two-step request: fine/coarse, then `ACCESS_BACKGROUND_LOCATION`
 * on its own. Google Play rejected the app on 2026-10-24 under the Prominent
 * Disclosure and Consent Requirement of the User Data policy for holding that second
 * permission, so the app no longer declares it (see the comment on the removed
 * `uses-permission` line in AndroidManifest.xml) and no longer asks for it.
 *
 * Nothing about the journey changes: the capability that keeps an armed check-in
 * receiving positions while the user is in another app, or with the screen off, is
 * `CheckInLocationService` — a foreground service typed `location`, started while
 * OSHI is on screen — and that runs on the while-in-use grant alone. iOS still asks
 * for `requestAlwaysAuthorization()` in `startLocationMonitoring`
 * (`OSHI/CheckInManager.swift:230`); the platforms diverge here on purpose, because
 * the Play policy has no App Store equivalent.
 *
 * What the user loses is stated plainly in the UI rather than hidden: if OSHI is
 * swiped out of recent apps or killed by a battery saver, the check-in stops with it.
 */

/** What the check-in path should do next, given the permissions currently held. */
enum class CheckInArmingDecision {
    /** Everything needed is granted — arm the session. */
    READY,

    /** No location grant at all yet — ask for it, in context. */
    REQUEST_FOREGROUND,

    /**
     * No location permission after asking. A check-in cannot detect arrival and
     * cannot attach a position to the emergency alert, so refuse to arm rather
     * than hand the user a safety feature that cannot fire.
     */
    BLOCKED_NO_LOCATION,
}

object CheckInPermissionPolicy {

    // String literals rather than `Manifest.permission.*` so this object stays
    // free of the android.jar stubs and remains unit-testable. These values are
    // frozen platform constants.
    const val PERMISSION_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val PERMISSION_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"

    /**
     * @param hasForegroundLocation fine OR coarse is enough — arrival is a 100 m
     *        radius test, which coarse can answer.
     * @param foregroundAlreadyRequested true once the dialog has come back
     *        (granted or not) in this arming attempt — distinguishes "not asked
     *        yet" from "asked and refused".
     */
    fun decide(
        hasForegroundLocation: Boolean,
        foregroundAlreadyRequested: Boolean,
    ): CheckInArmingDecision = when {
        hasForegroundLocation -> CheckInArmingDecision.READY
        foregroundAlreadyRequested -> CheckInArmingDecision.BLOCKED_NO_LOCATION
        else -> CheckInArmingDecision.REQUEST_FOREGROUND
    }

    /**
     * The permissions an arming attempt may ask for — and the whole list, which is
     * the point: `ACCESS_BACKGROUND_LOCATION` is not in it and must not be added
     * (the manifest removes it; asking for an undeclared permission is an instant
     * silent denial anyway).
     */
    fun foregroundPermissions(): List<String> =
        listOf(PERMISSION_FINE_LOCATION, PERMISSION_COARSE_LOCATION)
}

/**
 * When the `location` foreground service that hosts an armed check-in may run.
 *
 * Mirrors the iOS gate exactly: `OSHI/CheckInManager.swift:236-238` turns
 * `allowsBackgroundLocationUpdates` ON only for the duration of an active
 * session and `:256` flips it back OFF in `stopLocationMonitoring`, so the app
 * stops touching location the instant the check-in ends. On Android the
 * equivalent of "we are allowed to touch location in the background" is *this
 * service running*, so its lifetime must match the window in which the check-in is
 * still watching the user, and nothing wider.
 *
 * ══ WHY `expired` IS INSIDE THAT WINDOW (changed 2026-08-24) ══
 *
 * This object used to say `status == active` and nothing else, and it had **zero
 * production call sites** — a specification nothing obeyed, whose tests therefore
 * proved nothing. Wiring it up (`CheckInManager.hasOpenSession`, which
 * `CheckInLocationService.resumeOrStop` consults) exposed that the rule was also
 * wrong.
 *
 * `expired` is not a terminal state. It is the fifteen minutes between "Are you
 * okay?" and the automatic emergency alert — the single most important window in the
 * feature. Two things break if the service stops there:
 *
 *   * the emergency alert's payload is the user's **last known position**, collected
 *     from the location stream this service exists to keep alive. Stop it at
 *     `expired` and the alert that finally goes out carries a stale fix, or none;
 *   * a process restart during that window would tear the check-in down entirely,
 *     so neither the user's "I'm safe" nor the escalation would ever happen.
 *
 * iOS agrees: its `checkDeadline` warning branch does **not** call
 * `stopLocationMonitoring` (`OSHI/CheckInManager.swift:330-360`); only
 * `stopCheckIn` and `triggerEmergency` do, and its banner is shown for
 * `.active || .expired` (`OSHI/CheckInView.swift:540`).
 */
object CheckInServicePolicy {

    /**
     * True while the check-in is still watching — armed, or overdue and waiting for
     * the user's answer. Both states have an off-ramp the user can still press.
     */
    fun shouldServiceRun(status: CheckInStatus?): Boolean =
        status == CheckInStatus.active || status == CheckInStatus.expired

    /**
     * Whether ending a session with [reason] must tear the service down.
     * Arrival, cancellation and the expiry→emergency escalation all end it;
     * expiry itself does not, because the journey is not over — only late.
     */
    fun shouldStopServiceOn(reason: CheckInStatus): Boolean =
        !shouldServiceRun(reason)
}
