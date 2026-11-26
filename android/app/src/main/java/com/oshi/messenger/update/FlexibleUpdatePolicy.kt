package com.oshi.messenger.update

/**
 * __UPDATE_PROMPT_ANDROID_2026_11_21__
 *
 * "A new version is in production on the store — offer it." The NON-forced
 * half of updating, next to [ForcedUpdatePolicy] (which keeps sole charge of the
 * full-screen IMMEDIATE wall below the server's blocking floor).
 *
 * Pure: no Android, no Play, no clock. Every branch is a JVM unit test.
 *
 * ### What it decides
 *
 * One of three things to draw, from what Play said ([PlayFacts]) and what the
 * screen is doing ([Surface]):
 *
 *  * [Prompt.ASK] — the "Update available · Update / Later" dialog;
 *  * [Prompt.RESTART] — the persistent "Update ready · Restart" bar, once a
 *    flexible download has finished (in this run or a previous one);
 *  * [Prompt.NONE] — nothing.
 *
 * ### Why not `clientVersionStalenessDays` / `updatePriority`
 *
 * Both were considered and left out on purpose:
 *  * Staleness would delay the dialog by N days after Play first sees the
 *    release on this device. The request is the opposite — say so when a new
 *    version is in production — and the 3-day snooze already stops the nag.
 *    Staleness is also `null` for the first hours, which would make the first
 *    check of a fresh release silently show nothing.
 *  * Priority can only be set through the Play Developer API at upload time;
 *    every release so far has priority 0, so any rule keyed on it is dead code
 *    today, and an urgent release already has its own lever (the blocking floor
 *    → [ForcedUpdatePolicy]).
 */
object FlexibleUpdatePolicy {

    /** "Later" hides the dialog for this many ms — for THAT version only. */
    const val SNOOZE_MS: Long = 3L * 24 * 60 * 60 * 1000

    enum class Prompt { NONE, ASK, RESTART }

    /** Play's install state, reduced to what changes the decision. */
    enum class Install {
        /** Nothing downloading; nothing waiting. */
        IDLE,

        /** PENDING / DOWNLOADING / INSTALLING: the user already said yes. */
        IN_PROGRESS,

        /** Downloaded, waiting for `completeUpdate()`. */
        DOWNLOADED,
    }

    /**
     * What Play said, already reduced.
     *
     * @param flexibleAvailable `UPDATE_AVAILABLE` **and** `isUpdateTypeAllowed(FLEXIBLE)`.
     * @param availableVersionCode the store's versionCode (what "Later" snoozes).
     * @param install the flexible install state.
     * @param forced the forced policy would put up the IMMEDIATE wall now; the
     *   wall owns the screen and this must draw nothing over or under it.
     */
    data class PlayFacts(
        val flexibleAvailable: Boolean,
        val availableVersionCode: Int,
        val install: Install,
        val forced: Boolean,
    )

    /** What the screen is doing right now. */
    data class Surface(
        val onboarded: Boolean,
        val inCall: Boolean,
        val locked: Boolean,
    ) {
        /** A dialog or bar here would land on the wrong screen. */
        val busy: Boolean get() = !onboarded || inCall || locked
    }

    /**
     * @param play `null` = Play has not answered (or could not be asked): nothing.
     * @param askedThisStart the dialog already appeared once in this process.
     * @param snoozedVersionCode / [snoozedUntilMs] what "Later" stored.
     */
    fun decide(
        play: PlayFacts?,
        surface: Surface,
        askedThisStart: Boolean,
        snoozedVersionCode: Int?,
        snoozedUntilMs: Long?,
        nowMs: Long,
    ): Prompt {
        if (play == null) return Prompt.NONE
        // The IMMEDIATE wall is in charge. Nothing of ours on top of it.
        if (play.forced) return Prompt.NONE
        // Never over onboarding, a call (restarting would also END the call), or
        // the lock cover. The decision is re-taken when the surface frees up.
        if (surface.busy) return Prompt.NONE
        // A finished download beats everything else, snooze included: the
        // user already chose to update, only the restart is left.
        if (play.install == Install.DOWNLOADED) return Prompt.RESTART
        // Accepted and downloading: Play shows its own progress notification.
        if (play.install == Install.IN_PROGRESS) return Prompt.NONE
        if (!play.flexibleAvailable) return Prompt.NONE
        if (askedThisStart) return Prompt.NONE
        if (isSnoozed(play.availableVersionCode, snoozedVersionCode, snoozedUntilMs, nowMs)) {
            return Prompt.NONE
        }
        return Prompt.ASK
    }

    /**
     * Snoozed iff "Later" was pressed for THIS version and the 3 days are not
     * over. A newer store version is a new question and is asked at once.
     *
     * A deadline further away than [SNOOZE_MS] can only come from a clock set
     * back after "Later"; it is treated as expired rather than as a snooze that
     * could last for years.
     */
    fun isSnoozed(
        availableVersionCode: Int,
        snoozedVersionCode: Int?,
        snoozedUntilMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (snoozedVersionCode == null || snoozedUntilMs == null) return false
        if (snoozedVersionCode != availableVersionCode) return false
        val left = snoozedUntilMs - nowMs
        return left > 0 && left <= SNOOZE_MS
    }

    /**
     * What [ForcedUpdateManager] should take Play's `DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS`
     * to mean.
     *
     * Play reports that same value for a FLEXIBLE download the user accepted in
     * the dialog. Read as "an interrupted IMMEDIATE update" (which is what the
     * forced path was written for), every resume during a background download
     * would throw Play's full-screen wall over a user who only said "yes, in the
     * background". So when the in-progress update is one this class started,
     * the forced path sees it as an ordinary available update instead — which
     * still blocks if, and only if, the build is below the blocking floor.
     */
    fun forcedPlayState(
        inProgress: Boolean,
        updateAvailable: Boolean,
        immediateAllowed: Boolean,
        flexibleOwnsInProgress: Boolean,
    ): ForcedUpdatePolicy.PlayState = when {
        inProgress && !flexibleOwnsInProgress -> ForcedUpdatePolicy.PlayState.IN_PROGRESS
        inProgress && immediateAllowed -> ForcedUpdatePolicy.PlayState.AVAILABLE
        inProgress -> ForcedUpdatePolicy.PlayState.NOT_AVAILABLE
        updateAvailable && immediateAllowed -> ForcedUpdatePolicy.PlayState.AVAILABLE
        else -> ForcedUpdatePolicy.PlayState.NOT_AVAILABLE
    }
}
