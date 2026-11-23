package com.oshi.messenger.util

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import java.util.Collections

/**
 * Decides *when* to surface Google Play's in-app review flow.
 *
 * This is the portfolio's single rating mechanism, ported rule for rule from
 * Supernova iOS (`supernova/Sources/Services/RatingPrompter.swift`). Only the
 * triggers differ per app. For OSHI there are exactly THREE moments
 * (__RATING_THREE_MOMENTS_2026_11_15__):
 *
 *  1. **Welcome reply** — the user's first message in the welcome thread to the
 *     developer. The only ask allowed on day 1 / the first session: it waives the
 *     session floor entirely, but still waits for a calm screen.
 *  2. **First success** — the first message that provably reached a real contact
 *     (delivery receipt) or the first completed call, whichever comes first.
 *     Waits [MIN_DAYS_AFTER_EARLY_ASK_MILLIS] after a previous ask (moment 1),
 *     and is kept OWED, not lost, until it can arm.
 *  3. **Ten messages sent** — at least [MIN_DAYS_BETWEEN_MILLIS] (21 days) after
 *     the previous ask, with the ordinary session floor.
 *
 * All three share one ledger: at most [MAX_PER_YEAR] attempts per rolling 365
 * days, Apple's own ceiling. Nothing is ever asked within
 * [FRICTION_QUIET_MILLIS] of a failed send or a failed call.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * WHAT THIS REPLACED, AND WHY
 * ──────────────────────────────────────────────────────────────────────────────
 * OSHI used to raise a soft "Enjoying OSHI?" dialog and send only the positive
 * answers to Play. The reasoning was that Play's quota is finite, so a prompt
 * shown to someone about to leave two stars costs the quota *and* buys the bad
 * review. Two things were wrong with it:
 *
 *  1. **Play forbids it.** The in-app review design guidelines are explicit:
 *     "Your app shouldn't ask the user any questions before or while presenting
 *     the rating button or card, including questions about their opinion (such
 *     as 'Do you like the app?')". The pre-ask was exactly that question.
 *  2. **It asked almost nobody.** One lifetime prompt, behind a dialog, behind a
 *     ten-message threshold, behind a 14-to-180-day cooldown on every way out of
 *     that dialog — including a swipe-away. OSHI's Play listing has 0 ratings
 *     and 0 reviews all-time.
 *
 * Sentiment is still handled, but by *which moment arms the ask* rather than by
 * asking the user how they feel: the ask is armed when OSHI has demonstrably
 * worked for this person — a delivery receipt came back — never after friction.
 *
 * The Settings "Rate OSHI" row still opens the Play listing directly. That is
 * user-initiated, unthrottled, costs no quota, and is allowed.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * ARM FIRST, CHARGE SECOND
 * ──────────────────────────────────────────────────────────────────────────────
 * A milestone only ARMS a request, on disk, and spends nothing. The budget is
 * debited in [deliverArmedRequest], the one place the flow is handed to Play, and
 * only once the screen is free. An armed request that never finds a calm moment
 * stays armed for [ARMED_LIFETIME_MILLIS] and is then dropped, so a rating card
 * never appears at some later launch with no visible cause.
 *
 * This is the defect the old Android code shipped twice: "the budget was spent at
 * the decision, not at the display". iOS burned two commits on the same pair
 * (`d8403e1`, `3d48a3a`).
 */
class RatingPrompter internal constructor(
    private val store: RatingStore,
    private val clock: () -> Long,
    private val launcher: ReviewLauncher,
) {

    constructor(context: Context) : this(
        store = SharedPreferencesRatingStore(context.applicationContext),
        clock = System::currentTimeMillis,
        launcher = PlayReviewLauncher(context.applicationContext),
    )

    private val presented: MutableSet<ExclusiveMoment> =
        Collections.synchronizedSet(mutableSetOf<ExclusiveMoment>())

    @Volatile
    private var lastDismissedAt: Long? = null

    /**
     * Last failed send / failed call. In memory on purpose: it guards a ten-minute
     * window, and a process death longer than that has already outlived it.
     */
    @Volatile
    private var lastFrictionAt: Long? = null

    /** A send failed, a call failed: no ask for [FRICTION_QUIET_MILLIS]. */
    fun recordFriction() {
        lastFrictionAt = clock()
    }

    /**
     * A moment the review card must never share the screen with.
     *
     * None of these is a dialog the host can find by walking a view hierarchy —
     * Compose dialogs, a biometric prompt and Play's own update screen all own
     * their windows — so each surface reports itself.
     */
    enum class ExclusiveMoment { CALL, APP_LOCK, FORCED_UPDATE, SHARE_SHEET, SHEET }

    /** Called by the UI when one of those surfaces appears or goes away. */
    fun setPresented(moment: ExclusiveMoment, isPresented: Boolean) {
        if (isPresented) {
            presented.add(moment)
        } else if (presented.remove(moment)) {
            lastDismissedAt = clock()
        }
    }

    /** Cold-launch count. Called once per launch from the app's start path. */
    fun registerSession() {
        store.sessionCount = store.sessionCount + 1
    }

    // MARK: - Arming (the milestone side). Spends nothing.

    /**
     * Arms the ask after a message provably reached its peer.
     *
     * A delivery receipt — not "we handed it to a transport". Composing and
     * sending prove nothing: the key could be wrong, the peer could be gone, the
     * LoRa frame could be sitting in a queue. A receipt is the first moment OSHI
     * has demonstrably worked for this person, which is why it is the hero
     * moment and lowers the newness floor.
     */
    fun armIfAllowedAfterDelivery(): Boolean = armIfAllowedAfterFirstSuccess()

    /**
     * Moment 2: the first delivery receipt from a real contact, or the first
     * completed call. Lowers the session floor to [MIN_SESSIONS_AFTER_SUCCESS] and
     * only needs [MIN_DAYS_AFTER_EARLY_ASK_MILLIS] since the previous ask, so the
     * early welcome ask does not cost it three weeks.
     */
    fun armIfAllowedAfterFirstSuccess(): Boolean =
        arm(waivesNewness = true, waivesFirstSession = false)

    /**
     * Moment 1: the user's first message in the welcome thread to the developer.
     *
     * The early ask. It is the first deliberate act in the app, so it waives the
     * session floor ENTIRELY — it may be delivered in the very first session — but
     * still only on a calm screen, inside the yearly budget, and never right after
     * friction.
     */
    fun armIfAllowedAfterWelcomeReply(): Boolean =
        arm(waivesNewness = true, waivesFirstSession = true)

    /**
     * Arms the ask after [MESSAGES_THRESHOLD] messages sent. An ordinary
     * milestone: real use, but not proof of anything on its own, so it does not
     * lower the newness floor.
     *
     * 🔴 `>=`, NEVER `==`. The old gate was an exact equality, so the window was
     * a single message: one dropped increment — process death between two
     * `apply()` calls — closed it forever.
     */
    fun armIfAllowedAfterMessagesSent(messagesSent: Int): Boolean {
        if (messagesSent < MESSAGES_THRESHOLD) return false
        return arm(waivesNewness = false, waivesFirstSession = false)
    }

    /**
     * Arming checks the budget only (an arm that could not be delivered inside
     * its lifetime is pointless). It must NOT check the session floor: a first
     * delivery receipt on launch two would be thrown away for good, because
     * nothing re-derives a moment that has already passed.
     */
    private fun arm(waivesNewness: Boolean, waivesFirstSession: Boolean): Boolean {
        val now = clock()
        if (!budgetAllows(now, minGapMillis(waivesNewness))) return false
        if (store.armedAtEpochMillis != null) return false
        store.armedAtEpochMillis = now
        store.armedWaivesNewness = waivesNewness
        store.armedWaivesFirstSession = waivesFirstSession
        return true
    }

    /**
     * Spacing to the previous ask. The early moments (welcome reply, first
     * success) only need a week; the ordinary ten-message moment needs three.
     */
    private fun minGapMillis(waivesNewness: Boolean): Long =
        if (waivesNewness) MIN_DAYS_AFTER_EARLY_ASK_MILLIS else MIN_DAYS_BETWEEN_MILLIS

    // MARK: - The three moments (once-only bookkeeping)

    /**
     * Moment 1, the welcome reply. Once per install, EVER: the flag is written at
     * the attempt, because a later message in that thread is no longer "the first
     * thing the user did".
     */
    fun onWelcomeReply(): Boolean {
        if (store.welcomeReplyMomentUsed) return false
        store.welcomeReplyMomentUsed = true
        return armIfAllowedAfterWelcomeReply()
    }

    /**
     * Moment 2, the first delivery receipt from a real contact or the first
     * completed call. OWED until it is actually DELIVERED: the flag is written in
     * [deliverArmedRequest], so a receipt that lands inside the week after the
     * welcome ask, while that ask is still armed, or an arm that expires on a busy
     * screen, leaves the moment for the next receipt or call instead of losing it.
     */
    fun onFirstSuccess(): Boolean {
        if (store.firstSuccessMomentUsed) return false
        return armIfAllowedAfterFirstSuccess()
    }

    /** Whether a request is waiting to be handed to Play. */
    val hasArmedRequest: Boolean get() = store.armedAtEpochMillis != null

    // MARK: - Delivery (the only place a slot is spent)

    /**
     * Hands an armed request to Play if the screen is genuinely free.
     *
     * @param canPresent false while anything is on screen that the card must not
     *   cover. Injected so this stays a pure policy type.
     * @return true only when the request was issued (and only then is the yearly
     *   budget debited). False leaves it armed for the next calm moment, unless it
     *   has expired.
     */
    fun deliverArmedRequest(canPresent: () -> Boolean, request: () -> Unit): Boolean {
        val armedAt = store.armedAtEpochMillis ?: return false
        val now = clock()
        if (now - armedAt > ARMED_LIFETIME_MILLIS) {
            clearArmed()
            return false
        }
        // Re-checked at delivery: the arm may be hours old and a slot may have
        // been spent since.
        if (!budgetAllows(now, minGapMillis(store.armedWaivesNewness))) return false
        val floor = when {
            store.armedWaivesFirstSession -> 0
            store.armedWaivesNewness -> MIN_SESSIONS_AFTER_SUCCESS
            else -> MIN_SESSIONS
        }
        if (store.sessionCount < floor) return false
        if (frictionBlocks(now)) return false
        if (exclusiveMomentBlocks(now)) return false
        if (!canPresent()) return false // stays armed

        if (store.armedWaivesNewness && !store.armedWaivesFirstSession) {
            store.firstSuccessMomentUsed = true // moment 2 has had its ask
        }
        clearArmed()
        recordAsk(now)
        request()
        return true
    }

    /** Shows Play's review card. The Activity is never stored. */
    fun requestReview(activity: Activity) = launcher.launch(activity)

    // MARK: - Policy

    private fun frictionBlocks(now: Long): Boolean {
        val friction = lastFrictionAt ?: return false
        return now - friction < FRICTION_QUIET_MILLIS
    }

    private fun exclusiveMomentBlocks(now: Long): Boolean {
        if (presented.isNotEmpty()) return true
        val dismissed = lastDismissedAt ?: return false
        return now - dismissed < EXCLUSION_COOLDOWN_MILLIS
    }

    /** Quota and spacing only: what the budget allows right now. Reads, never spends. */
    internal fun budgetAllows(now: Long, minGapMillis: Long = MIN_DAYS_BETWEEN_MILLIS): Boolean {
        migrateLegacyFlagIfNeeded(now)
        val attempts = recentAsks(now)
        if (attempts.size >= MAX_PER_YEAR) return false
        val last = attempts.maxOrNull()
        if (last != null && now - last < minGapMillis) return false
        return true
    }

    private fun recentAsks(now: Long): List<Long> =
        store.askEpochMillis.filter { now - it < ROLLING_YEAR_MILLIS }

    /**
     * Debit one slot.
     *
     * The ledger records ATTEMPTS. Play never reports whether the card was
     * rendered — it is silently dropped over quota, with no Play Store account, on
     * a sideloaded build — so nothing here may ever be read as "the user rated".
     */
    private fun recordAsk(now: Long) {
        store.askEpochMillis = recentAsks(now) + now
        store.ratingPromptSeen = true
    }

    private fun clearArmed() {
        store.armedAtEpochMillis = null
        store.armedWaivesNewness = false
        store.armedWaivesFirstSession = false
    }

    /**
     * The old policy kept one `has_prompted_for_review` boolean that retired the
     * ask for the lifetime of the install. It becomes ONE slot in the rolling
     * window, dated [MIN_DAYS_BETWEEN_MILLIS] in the past: one slot spent, no
     * fresh 21-day silence handed to every existing install on update day, and
     * the other two slots available again.
     */
    private fun migrateLegacyFlagIfNeeded(now: Long) {
        if (store.legacyMigrated) return
        store.legacyMigrated = true
        if (store.askEpochMillis.isNotEmpty() || !store.ratingPromptSeen) return
        store.askEpochMillis = listOf(now - MIN_DAYS_BETWEEN_MILLIS)
    }

    companion object {
        internal const val TAG = "OshiRating"

        /** Messages sent before the ordinary milestone becomes eligible. */
        const val MESSAGES_THRESHOLD = 10

        /** Never on the first launches. */
        internal const val MIN_SESSIONS = 3

        /**
         * A demonstrated success (moment 2) lowers the floor, but not to the first
         * launch. Only the welcome reply (moment 1) waives it entirely.
         */
        internal const val MIN_SESSIONS_AFTER_SUCCESS = 2

        /** Spacing before the ordinary ten-message moment (3). */
        internal const val MIN_DAYS_BETWEEN_MILLIS = 21L * 86_400_000L

        /** Spacing before an early moment (1 or 2) when an ask already went out. */
        internal const val MIN_DAYS_AFTER_EARLY_ASK_MILLIS = 7L * 86_400_000L

        /** No ask within ten minutes of a failed send or a failed call (iOS figure). */
        internal const val FRICTION_QUIET_MILLIS = 10L * 60_000L
        internal const val MAX_PER_YEAR = 3
        internal const val ROLLING_YEAR_MILLIS = 365L * 86_400_000L

        /** An armed request older than this is dropped, not shown out of context. */
        internal const val ARMED_LIFETIME_MILLIS = 24L * 3_600_000L

        /** How long after a blocking surface closes the ask stays blocked. */
        internal const val EXCLUSION_COOLDOWN_MILLIS = 5_000L

        // Delivery schedule, the iOS RootView numbers.
        const val DELIVERY_DELAY_AFTER_ARM_MILLIS = 1_200L
        const val DELIVERY_DELAY_ON_FOREGROUND_MILLIS = 800L
        const val DELIVERY_DELAY_ON_LAUNCH_MILLIS = 2_000L
        const val DELIVERY_ATTEMPTS = 10
        const val DELIVERY_RETRY_MILLIS = 1_200L
    }
}

/** What the policy persists. Extracted so the rules are testable off-device. */
interface RatingStore {
    var sessionCount: Int

    /** True once the flow has been requested at least once (legacy flag). */
    var ratingPromptSeen: Boolean

    /** Timestamps of past ATTEMPTS, oldest first. Never "the user rated". */
    var askEpochMillis: List<Long>

    /** When the pending request was armed; null when none is armed. */
    var armedAtEpochMillis: Long?

    /** Whether the pending request came from an early moment (1 or 2). */
    var armedWaivesNewness: Boolean

    /** Whether the pending request is the welcome reply (moment 1): no session floor. */
    var armedWaivesFirstSession: Boolean

    /** True once the legacy flag has been folded into [askEpochMillis]. */
    var legacyMigrated: Boolean

    /** Moment 1 (welcome reply) has been attempted. */
    var welcomeReplyMomentUsed: Boolean

    /** Moment 2 (first delivery receipt / completed call) has been delivered. */
    var firstSuccessMomentUsed: Boolean
}

/**
 * Lives in `app_review_prefs`, the file the retired policy already used, so the
 * legacy "already prompted" flag is readable for the migration. Key names mirror
 * the iOS `MetaProgressStore` ones used across the portfolio, so one support dump
 * reads the same on both platforms.
 */
internal class SharedPreferencesRatingStore(context: Context) : RatingStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var sessionCount: Int
        get() = prefs.getInt(KEY_SESSION_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_SESSION_COUNT, value).apply()

    /** Either ledger counts as "we have asked before": the new flag, or the old one. */
    override var ratingPromptSeen: Boolean
        get() = prefs.getBoolean(KEY_RATING_PROMPT_SEEN, false) ||
            prefs.getBoolean(LEGACY_KEY_HAS_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_RATING_PROMPT_SEEN, value).apply()

    /** A CSV of epoch millis, not a `StringSet`: a set has no order. */
    override var askEpochMillis: List<Long>
        get() = prefs.getString(KEY_RATING_ASK_DATES, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?: emptyList()
        set(value) = prefs.edit()
            .putString(KEY_RATING_ASK_DATES, value.joinToString(","))
            .apply()

    override var armedAtEpochMillis: Long?
        get() = if (prefs.contains(KEY_ARMED_AT)) prefs.getLong(KEY_ARMED_AT, 0L) else null
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove(KEY_ARMED_AT) else edit.putLong(KEY_ARMED_AT, value)
            edit.apply()
        }

    override var armedWaivesNewness: Boolean
        get() = prefs.getBoolean(KEY_ARMED_WAIVES_NEWNESS, false)
        set(value) = prefs.edit().putBoolean(KEY_ARMED_WAIVES_NEWNESS, value).apply()

    override var armedWaivesFirstSession: Boolean
        get() = prefs.getBoolean(KEY_ARMED_WAIVES_FIRST_SESSION, false)
        set(value) = prefs.edit().putBoolean(KEY_ARMED_WAIVES_FIRST_SESSION, value).apply()

    override var legacyMigrated: Boolean
        get() = prefs.getBoolean(KEY_LEGACY_MIGRATED, false)
        set(value) = prefs.edit().putBoolean(KEY_LEGACY_MIGRATED, value).apply()

    override var welcomeReplyMomentUsed: Boolean
        get() = prefs.getBoolean(KEY_WELCOME_REPLY_MOMENT, false)
        set(value) = prefs.edit().putBoolean(KEY_WELCOME_REPLY_MOMENT, value).apply()

    /** Same key the old AppReviewManager wrote for "first delivery", so it carries over. */
    override var firstSuccessMomentUsed: Boolean
        get() = prefs.getBoolean(KEY_FIRST_SUCCESS_MOMENT, false)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_SUCCESS_MOMENT, value).apply()

    internal companion object {
        const val PREFS_NAME = "app_review_prefs"
        const val KEY_SESSION_COUNT = "meta.sessionCount"
        const val KEY_RATING_PROMPT_SEEN = "meta.ratingPromptSeen"
        const val KEY_RATING_ASK_DATES = "meta.ratingAskDates"
        const val KEY_ARMED_AT = "meta.ratingArmedAt"
        const val KEY_ARMED_WAIVES_NEWNESS = "meta.ratingArmedWaivesNewness"
        const val KEY_LEGACY_MIGRATED = "meta.ratingLegacyMigrated"
        const val KEY_ARMED_WAIVES_FIRST_SESSION = "meta.ratingArmedWaivesFirstSession"
        const val KEY_WELCOME_REPLY_MOMENT = "armed_on_welcome_reply"
        const val KEY_FIRST_SUCCESS_MOMENT = "armed_on_first_delivery"

        /** The retired one-lifetime-prompt flag. */
        const val LEGACY_KEY_HAS_PROMPTED = "has_prompted_for_review"
    }
}

/** Indirection so the policy can be tested without the Play card. */
interface ReviewLauncher {
    fun launch(activity: Activity)
}

internal class PlayReviewLauncher(context: Context) : ReviewLauncher {

    private val manager: ReviewManager = ReviewManagerFactory.create(context.applicationContext)

    override fun launch(activity: Activity) {
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful) {
                // No Play Store, no account, sideloaded build: nothing to retry.
                Log.w(RatingPrompter.TAG, "in-app review unavailable on this device", request.exception)
                return@addOnCompleteListener
            }
            // The Activity may have gone while Play was answering.
            if (activity.isFinishing || activity.isDestroyed) return@addOnCompleteListener
            manager.launchReviewFlow(activity, request.result).addOnCompleteListener { launch ->
                if (!launch.isSuccessful) {
                    Log.w(RatingPrompter.TAG, "the in-app review flow did not run", launch.exception)
                }
            }
        }
    }
}
