package com.oshi.messenger.ui

/**
 * __ONBOARDING_2027__ When MainActivity asks for POST_NOTIFICATIONS on its own.
 *
 * Before: in onCreate, on every launch — on a fresh install that meant the
 * system dialog over the launch screen, out of any context. Now: only once the
 * settings have loaded, and only if the user was already onboarded at that
 * moment. A new user gets the in-context onboarding step instead
 * ([com.oshi.messenger.ui.onboarding.OnboardingPage.NOTIFICATIONS]); a user who
 * restored silently from Auto Backup (onboarded at load) is asked as before.
 */
object StartupNotificationAsk {
    fun shouldAsk(isLoading: Boolean, onboardedAtLoad: Boolean): Boolean = !isLoading && onboardedAtLoad
}
