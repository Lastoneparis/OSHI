package com.oshi.messenger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.oshi.messenger.service.CallRatingManager
import com.oshi.messenger.service.CallState
import com.oshi.messenger.util.AppReviewManager
import com.oshi.messenger.util.RatingPrompter

/**
 * Port of iOS `RootView.deliverArmedReviewRequest(after:)` (Supernova).
 *
 * Hands an armed review request to Play once the screen is calm. Hosted once at
 * the Compose root, it draws nothing and runs the delivery loop at the same three
 * occasions iOS does:
 *
 *  - right after the milestone that armed it ([AppReviewManager.armedSignal] moves),
 *    after a beat so the moment lands first;
 *  - at every return to the foreground — whatever covered the screen on the
 *    previous attempt is gone by then;
 *  - at cold launch, for a request armed in an earlier run. OSHI is killed by the
 *    system routinely, so the token lives on disk and is picked up later.
 *
 * The budget is only spent inside `RatingPrompter.deliverArmedRequest`, at the
 * instant the flow is handed to Play. An attempt that finds a busy screen changes
 * nothing and leaves the request armed, so a token is never lost to a sheet —
 * which is what the retired policy did with the single prompt it had.
 *
 * ## Why the screen test is a list of OSHI's own state
 *
 * There is no general Android way to ask "is anything on screen": a Compose
 * dialog, a biometric prompt and Play's own update screen each own their window,
 * and none of them appears in this Activity's view hierarchy. So the root reports
 * what it knows, and anything else that must block the card brackets itself
 * through [RatingPrompter.ExclusiveMoment].
 */
@Composable
fun ReviewDeliveryEffect(
    isOnboarded: Boolean,
    locked: Boolean,
    callState: CallState,
    currentRoute: String?,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val armedSignal by AppReviewManager.armedSignal.collectAsState()

    /**
     * A calm screen: the user is signed in and settled, not locked out, not on or
     * being called, and not inside a call screen.
     *
     * `rememberUpdatedState` so the loop's later retries see the screen as it is
     * *now* rather than as it was when the pass started — the whole point of
     * retrying is that the screen changes underneath it.
     */
    val isFree = rememberUpdatedState {
        isOnboarded &&
            !locked &&
            callState == CallState.IDLE &&
            currentRoute != null &&
            !currentRoute.startsWith("call/") &&
            !currentRoute.startsWith("incomingCall/") &&
            !currentRoute.startsWith("onboarding") &&
            // __RATING_THREE_MOMENTS_2026_11_15__ Not on top of "how was the
            // call?" (both follow a call ending, seconds apart), and not while the
            // keyboard is up: the welcome-reply ask is armed by a send, and the
            // user may still be typing the next line. Read LIVE at each attempt.
            CallRatingManager.pending.value == null &&
            !isImeVisible(view)
    }

    // Report the screen to the host once, and keep the lambda current.
    LaunchedEffect(Unit) {
        AppReviewManager.setScreenFreeProvider { isFree.value() }
    }

    // A milestone armed while the app is on screen gets its own pass. The host
    // also schedules one itself, so this is the retry for a pass that started
    // while something was still up.
    LaunchedEffect(armedSignal) {
        if (armedSignal == 0) return@LaunchedEffect
        AppReviewManager.scheduleDelivery(context, RatingPrompter.DELIVERY_DELAY_AFTER_ARM_MILLIS)
    }

    // A new screen is a natural pause (leaving the chat for the list, closing a
    // sheet route): give an armed request another pass. Cheap when nothing is
    // armed — `scheduleDelivery` returns at once.
    LaunchedEffect(currentRoute) {
        if (currentRoute == null) return@LaunchedEffect
        AppReviewManager.scheduleDelivery(context, RatingPrompter.DELIVERY_DELAY_ON_FOREGROUND_MILLIS)
    }

    // Cold launch, then every foreground. `repeatOnLifecycle` re-enters its block
    // on each STARTED, which is exactly iOS's `scenePhase == .active` retry point;
    // the first pass is the launch one and waits a beat longer.
    LaunchedEffect(lifecycleOwner) {
        var firstStart = true
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (firstStart) {
                AppReviewManager.registerSession(context)
                AppReviewManager.scheduleDelivery(
                    context, RatingPrompter.DELIVERY_DELAY_ON_LAUNCH_MILLIS,
                )
                firstStart = false
            } else {
                AppReviewManager.scheduleDelivery(
                    context, RatingPrompter.DELIVERY_DELAY_ON_FOREGROUND_MILLIS,
                )
            }
        }
    }
}

/** The soft keyboard is showing. Root insets are null before attach: not visible. */
private fun isImeVisible(view: android.view.View): Boolean =
    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
