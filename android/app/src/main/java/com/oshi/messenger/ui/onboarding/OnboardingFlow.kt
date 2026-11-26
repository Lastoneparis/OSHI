package com.oshi.messenger.ui.onboarding

import android.os.Build

/**
 * __ONBOARDING_2027__ Every step a new user goes through before the chat list,
 * in order. Pure (no Android types beyond an SDK int) so the order, the skip
 * target and back navigation are unit-tested.
 *
 * The old flow was seven pages of equal weight (WELCOME, SEND_MESSAGE,
 * CONNECTIVITY, FEATURES, GOOD_CONDUCT, DISCLAIMER, IDENTITY), three of them
 * walls of text. It is now:
 *
 * 1. four SLIDES, one idea each, in a swipeable pager — [PRIVATE], [OFFLINE],
 *    [ANONYMOUS], [TOGETHER];
 * 2. [RULES]: the two safety disclosures the old GOOD_CONDUCT and DISCLAIMER
 *    pages carried (Play's user-generated-content policy, Apple 1.2), on one
 *    page — the house rules in full, the six "good to know" limits as
 *    expandable rows, every original string still shown. Skip lands HERE,
 *    never past it: a user can skip the tour, not the rules;
 * 3. [NOTIFICATIONS]: the notification permission, asked in context with one
 *    line of why — only on Android 13+ and only while it is not granted. It used
 *    to be the system dialog thrown over the launch screen on the very first
 *    open, before the user had seen a single word of OSHI;
 * 4. [IDENTITY]: the terminal step. Its button creates the identity; restore
 *    from a recovery key and recover-from-this-device live here too.
 */
enum class OnboardingPage(val isSlide: Boolean = false) {
    PRIVATE(isSlide = true),
    OFFLINE(isSlide = true),
    ANONYMOUS(isSlide = true),
    TOGETHER(isSlide = true),

    /** House rules + honest limits. Must precede [IDENTITY]; Skip stops here. */
    RULES,

    /** Notification permission, in context. Dropped from the flow when not needed. */
    NOTIFICATIONS,

    /** Terminal: its primary action creates the identity, not "Next". */
    IDENTITY;

    companion object {
        /** The full order, as on a fresh Android 13+ install. */
        val ORDER: List<OnboardingPage> = entries.toList()
    }
}

object OnboardingFlow {

    /** The swipeable part: the slides, then the rules page. */
    val TOUR: List<OnboardingPage> = OnboardingPage.ORDER.filter { it.isSlide } + OnboardingPage.RULES

    /** Where "Skip" goes: the rules, never past them. */
    val SKIP_TARGET: OnboardingPage = OnboardingPage.RULES

    /**
     * Ask for POST_NOTIFICATIONS during onboarding? Only where the runtime
     * permission exists (API 33+) and is not already granted.
     */
    fun needsNotificationStep(sdkInt: Int, granted: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU && !granted

    /** The steps this user will see, in order. */
    fun steps(askNotifications: Boolean): List<OnboardingPage> =
        OnboardingPage.ORDER.filter { it != OnboardingPage.NOTIFICATIONS || askNotifications }

    /** The step after [current], or null on the terminal step. */
    fun next(current: OnboardingPage, askNotifications: Boolean): OnboardingPage? {
        val s = steps(askNotifications)
        val i = s.indexOf(current)
        return if (i < 0) s.first() else s.getOrNull(i + 1)
    }

    /**
     * The step system Back returns to, or null on the first slide (Back then
     * leaves the app, as on any first screen). The old screen had no
     * BackHandler at all: Back on page seven closed the app and threw the
     * user's progress away.
     */
    fun previous(current: OnboardingPage, askNotifications: Boolean): OnboardingPage? {
        val s = steps(askNotifications)
        val i = s.indexOf(current)
        return if (i <= 0) null else s[i - 1]
    }

    /** Index of [page] in the pager, or -1 if it is not a pager page. */
    fun tourIndex(page: OnboardingPage): Int = TOUR.indexOf(page)
}
