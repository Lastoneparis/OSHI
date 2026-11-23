package com.oshi.messenger.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.oshi.messenger.service.WelcomeMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Hosts the review ask: holds the foreground Activity, runs the delivery loop,
 * and offers the Settings "Rate OSHI" route.
 *
 * All the timing lives in [RatingPrompter], the portfolio's single rating policy
 * (a port of Supernova iOS). This object is the Android equivalent of Supernova's
 * `RootView.deliverArmedReviewRequest(after:)`: it knows about the Activity, the
 * lifecycle and the screen, and nothing about the rules.
 *
 * 🔧 THE THREE DEFECTS THIS CODE SHIPPED, kept written down because each of them
 * made the prompt structurally unreachable and nothing failed:
 *
 *  1. **The context was never an Activity.** Every call site is
 *     `MessageRepository.trackMessageSent(context)`, and that `context` is
 *     `@ApplicationContext` — so `context as? Activity` was always null and
 *     `launchReviewFlow` was never reached, on any device, ever. The current
 *     Activity is registered by MainActivity ([setCurrentActivity]) and held
 *     weakly here; the tracking calls keep their `Context` signature so the
 *     repository does not have to know about any of this.
 *  2. **The budget was spent at the decision, not at the display.** Fixed
 *     properly now: the slot is debited inside [RatingPrompter.deliverArmedRequest],
 *     at the instant the flow is handed to Play, and a busy screen leaves the
 *     request armed instead of consuming it.
 *  3. **`count == THRESHOLD`** was an exact equality: a one-message window that
 *     any dropped increment closed forever. Now `>=`, in [RatingPrompter].
 *
 * 🔧 AND THE PRE-ASK IS GONE. A soft "Enjoying OSHI?" dialog used to stand in
 * front of Play's flow so that only positive answers reached it. Play's in-app
 * review design guidelines forbid exactly that ("shouldn't ask the user any
 * questions before or while presenting the rating button or card, including
 * questions about their opinion"), and it asked almost nobody: one lifetime
 * prompt, behind a dialog, behind a ten-message threshold, behind a 14-to-180-day
 * cooldown on every way out of the dialog — a swipe-away included. The listing
 * has 0 ratings all-time. Sentiment is now carried by *which moment arms the
 * ask*: a delivery receipt means OSHI has demonstrably worked for this person.
 */
object AppReviewManager {
    private const val TAG = "AppReviewManager"
    private const val PREFS_NAME = "app_review_prefs"
    private const val KEY_MESSAGES_SENT = "messages_sent_count"

    /**
     * __RATING_THREE_MOMENTS_2026_11_15__ The once-only bookkeeping of moments 1
     * and 2 lives in [RatingPrompter.onWelcomeReply] / [RatingPrompter.onFirstSuccess]
     * (same prefs file, same "armed_on_first_delivery" key as before), where the
     * JVM tests can reach it.
     *
     * The old "first contact added" moment is retired: it would have been a
     * fourth moment, and adding a contact proves nothing worked yet.
     */
    private const val KEY_MESSAGES_DELIVERED = "messages_delivered_count"

    private const val PLAY_STORE_URI = "market://details?id=com.oshi.messenger"
    private const val PLAY_STORE_WEB = "https://play.google.com/store/apps/details?id=com.oshi.messenger"

    /**
     * The Activity the review flow will be launched over. Weak so a rotation or a
     * finish cannot leak it; null simply means "no foreground Activity", and the
     * attempt is skipped without spending the budget.
     */
    private var activityRef: WeakReference<Activity>? = null

    private var prompter: RatingPrompter? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var deliveryJob: Job? = null

    /**
     * Whether the screen is calm enough for Play's card, as reported by the
     * Compose root. Defaults to false: nothing is delivered until the root has
     * actually said the app is past its launch screen and out of onboarding.
     */
    @Volatile
    private var screenIsFree: () -> Boolean = { false }

    private val _armedSignal = MutableStateFlow(0)

    /**
     * Bumped every time a request is armed, so the Compose root can start a
     * delivery pass. A counter rather than a flag: what the root needs is the
     * *edge*, and a flag that is already true would not retrigger.
     */
    val armedSignal: StateFlow<Int> = _armedSignal.asStateFlow()

    private fun prompter(context: Context): RatingPrompter =
        prompter ?: RatingPrompter(context.applicationContext).also { prompter = it }

    private fun currentActivity(): Activity? =
        activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    // MARK: - Host wiring

    /** Called from MainActivity's lifecycle. Pass null on destroy. */
    fun setCurrentActivity(activity: Activity?) {
        activityRef = activity?.let { WeakReference(it) }
    }

    /** Called once from the Compose root, which is the only thing that knows. */
    fun setScreenFreeProvider(provider: () -> Boolean) {
        screenIsFree = provider
    }

    /** One cold launch, for the newness floor. */
    fun registerSession(context: Context) {
        prompter(context).registerSession()
    }

    /** A surface the card must not cover appeared or went away. */
    fun setPresented(context: Context, moment: RatingPrompter.ExclusiveMoment, isPresented: Boolean) {
        prompter(context).setPresented(moment, isPresented)
    }

    // MARK: - Arming (the triggers)

    /**
     * Track a sent message. Arms the ask once [RatingPrompter.MESSAGES_THRESHOLD]
     * have gone out.
     *
     * Arming spends nothing and needs no Activity, so unlike the old code this is
     * safe from a background or LoRa-queued send: the token waits on disk.
     */
    fun trackMessageSent(context: Context, recipientPublicKey: String? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_MESSAGES_SENT, 0) + 1
        prefs.edit().putInt(KEY_MESSAGES_SENT, count).apply()
        // Moment 1: the first message the user writes in the welcome thread.
        if (recipientPublicKey != null && WelcomeMessage.isAuthor(recipientPublicKey)) {
            trackWelcomeReply(context)
            return
        }
        // Moment 3: ten messages sent, three weeks after the previous ask.
        if (prompter(context).armIfAllowedAfterMessagesSent(count)) onArmed(context, "messages=$count")
    }

    /**
     * Moment 1 — the user's FIRST message to the developer in the welcome thread.
     * Fires once per install. The only moment allowed in the first session.
     */
    fun trackWelcomeReply(context: Context) {
        if (prompter(context).onWelcomeReply()) onArmed(context, "welcome-reply")
    }

    /**
     * A message of the user's **provably reached its peer** — a delivery receipt
     * came back, not merely "we handed it to a transport".
     *
     * Call it on every receipt; it self-limits to the first one. Composing and
     * sending prove nothing (the key could be wrong, the peer could be gone); a
     * receipt is the first moment OSHI has demonstrably worked for this person,
     * which is why iOS arms its ask here rather than on send.
     */
    fun trackMessageDelivered(context: Context, senderPublicKey: String? = null) {
        // A receipt from the developer's welcome account is not "a real contact":
        // that thread already had its own moment (1).
        if (senderPublicKey != null && WelcomeMessage.isAuthor(senderPublicKey)) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val delivered = prefs.getInt(KEY_MESSAGES_DELIVERED, 0) + 1
        prefs.edit().putInt(KEY_MESSAGES_DELIVERED, delivered).apply()
        armFirstSuccess(context, "first-delivery")
    }

    /**
     * Moment 2, call side: a call that connected and ended normally. Call it on
     * every such call; it self-limits with the delivery side.
     */
    fun trackCallCompleted(context: Context) {
        armFirstSuccess(context, "first-call")
    }

    /** Moment 2 is OWED until it arms; see [RatingPrompter.onFirstSuccess]. */
    private fun armFirstSuccess(context: Context, source: String) {
        if (prompter(context).onFirstSuccess()) onArmed(context, source)
    }

    /**
     * A send failed or a call failed: no ask for the next ten minutes. The armed
     * request (if any) is kept; it is simply not delivered inside the window.
     */
    fun trackFriction(context: Context) {
        prompter(context).recordFriction()
    }

    private fun onArmed(context: Context, source: String) {
        Log.d(TAG, "review ask armed ($source) — waiting for a calm screen")
        _armedSignal.value = _armedSignal.value + 1
        scheduleDelivery(context, RatingPrompter.DELIVERY_DELAY_AFTER_ARM_MILLIS)
    }

    // MARK: - Delivery

    /** Whether a request is waiting. Read by the root before starting a pass. */
    fun hasArmedRequest(context: Context): Boolean = prompter(context).hasArmedRequest

    /**
     * Look for a calm screen and hand the armed request to Play when one turns up.
     *
     * Called after the milestone that armed it, at every foreground, and at cold
     * launch. The budget is only spent inside [RatingPrompter.deliverArmedRequest],
     * at the instant the flow is launched; a busy screen leaves the request armed
     * for the next occasion, so a token is never lost to a sheet.
     */
    fun scheduleDelivery(context: Context, delayMillis: Long) {
        val p = prompter(context)
        if (!p.hasArmedRequest) return
        if (deliveryJob?.isActive == true) return
        deliveryJob = scope.launch {
            delay(delayMillis)
            repeat(RatingPrompter.DELIVERY_ATTEMPTS) { attempt ->
                if (attempt > 0) delay(RatingPrompter.DELIVERY_RETRY_MILLIS)
                if (!p.hasArmedRequest) return@launch
                val host = currentActivity() ?: return@repeat
                val delivered = p.deliverArmedRequest(
                    canPresent = { screenIsFree() },
                    request = { p.requestReview(host) },
                )
                if (delivered) {
                    Log.d(TAG, "in-app review flow handed to Play")
                    return@launch
                }
            }
        }
    }

    // MARK: - The explicit route

    /**
     * Open the Play Store page directly (the manual "Rate OSHI" row in Settings).
     *
     * User-initiated and not an in-app review flow: it opens the store listing, so
     * it costs none of the finite prompt budget, is never throttled, and is open
     * to everyone. This is the route Play allows an app to offer explicitly.
     */
    fun openPlayStoreReview(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_URI)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            // Fallback to the web URL if the Play Store app is not installed.
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_WEB)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
