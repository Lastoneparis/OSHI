package com.oshi.messenger.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The off-ramp. Everything the user can say about their own journey from **outside**
 * a running app arrives here.
 *
 * ══ THE BUG THIS EXISTS FOR ══
 *
 * `CheckInManager.stopCheckIn`, `confirmSafe` and `extendTime` had **zero call
 * sites**. The warning notification carried no buttons, `CheckInActiveBanner` was
 * never composed, and `showWarningAlert` was never collected. So a user who armed a
 * check-in had, literally, no way to end it: **100 % of overdue check-ins escalated
 * to `triggerEmergency()`** and shipped their position and battery level to a
 * contact under an "Emergency" banner. The feature could only ever produce false
 * alarms.
 *
 * ══ WHY A BROADCAST RECEIVER AND NOT A LAMBDA ══
 *
 * This is the same mistake, one layer up, that made the *send* side silent: the only
 * production caller of `onMessageSend` was `{ _ -> showCheckInSheet = false }` in
 * `ChatScreen`, a lambda whose scope was dead long before the alert fired.
 *
 * The realistic sequence for the off-ramp is: arm a check-in, put the phone away,
 * and twenty minutes later — with OSHI long since evicted from memory — see "Are you
 * okay?" on the lock screen and press "I'm safe". At that instant there is no
 * Activity, no ViewModel, no composition and no `CheckInManager` object. A
 * `BroadcastReceiver` is the one component Android will start a **fresh process**
 * for, and [CheckInManager.getInstance] rebuilds the singleton from
 * `SharedPreferences` in its constructor (`loadPersistedSession`). So the chain from
 * a lock-screen tap to a sent message is:
 *
 * ```
 * lock screen "I'm safe"
 *   → CheckInActionReceiver.onReceive (process may be COLD; Android starts it)
 *   → CheckInManager.getInstance(appContext)      ← constructor reloads the session
 *   → manager.applyUserAction(CONFIRM_SAFE)
 *   → confirmSafe() → extendTime(15) → dispatch(CheckInMessage.extended)
 *   → MessageRepository.sendTextMessage(session.contactPublicKey, …)
 *   → manager.awaitPendingSend()                  ← goAsync() held until it lands
 * ```
 *
 * [goAsync] is what keeps the receiver — and therefore the process — alive across
 * that send. Without it `onReceive` returns, the process becomes killable, and the
 * "I'm safe" the user pressed is a coroutine that may never run: exactly the class
 * of silent failure this feature already shipped once.
 *
 * ══ [ACTION_DEADLINE_TICK] ══
 *
 * The deadline was watched only by a `delay(60_000)` loop inside the app process. A
 * process kill therefore froze the escalation entirely — no warning, no emergency,
 * silence. The alarm scheduled by [CheckInDeadlineAlarm] wakes this receiver at the
 * deadline (and again at deadline + response window) so the check-in keeps its
 * promise from a cold process. `setExactAndAllowWhileIdle` mirrors
 * `ScheduledCallManager.scheduleAlarm`, including its degradation to an inexact
 * alarm when `SCHEDULE_EXACT_ALARM` is refused.
 */
class CheckInActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CheckInAction"

        const val ACTION_SAFE = "com.oshi.messenger.action.CHECKIN_SAFE"
        const val ACTION_ARRIVED = "com.oshi.messenger.action.CHECKIN_ARRIVED"
        const val ACTION_EXTEND = "com.oshi.messenger.action.CHECKIN_EXTEND"
        const val ACTION_CANCEL = "com.oshi.messenger.action.CHECKIN_CANCEL"
        const val ACTION_DEADLINE_TICK = "com.oshi.messenger.action.CHECKIN_DEADLINE_TICK"

        /** Minutes for [ACTION_EXTEND]. Defaults to 15, as iOS's "+15 min" action does. */
        const val EXTRA_MINUTES = "checkin_extend_minutes"

        /**
         * Distinct request codes so the four PendingIntents are four objects.
         * `PendingIntent` equality ignores extras, so sharing a request code between
         * "I'm safe" and "Stop" would silently make one of them do the other's job.
         */
        private const val RC_SAFE = 91_101
        private const val RC_ARRIVED = 91_102
        private const val RC_EXTEND = 91_103
        private const val RC_CANCEL = 91_104

        private fun requestCodeFor(action: String): Int = when (action) {
            ACTION_SAFE -> RC_SAFE
            ACTION_ARRIVED -> RC_ARRIVED
            ACTION_EXTEND -> RC_EXTEND
            else -> RC_CANCEL
        }

        /** Maps a broadcast action to the manager call it performs, or null. */
        fun userActionFor(action: String?): CheckInUserAction? = when (action) {
            ACTION_SAFE -> CheckInUserAction.CONFIRM_SAFE
            ACTION_ARRIVED -> CheckInUserAction.ARRIVED
            ACTION_EXTEND -> CheckInUserAction.EXTEND
            ACTION_CANCEL -> CheckInUserAction.CANCEL
            else -> null
        }

        /**
         * A `PendingIntent` for one notification action.
         *
         * `FLAG_IMMUTABLE` because nothing outside the app may rewrite it, and
         * `FLAG_UPDATE_CURRENT` so re-posting the notification refreshes the extras
         * of an existing one rather than reusing stale ones.
         */
        fun actionIntent(
            context: Context,
            action: String,
            minutes: Int = 15,
        ): PendingIntent {
            val intent = Intent(context, CheckInActionReceiver::class.java).apply {
                this.action = action
                // Target the package explicitly: an implicit broadcast with a custom
                // action would be delivered to any receiver that declared it.
                setPackage(context.packageName)
                putExtra(EXTRA_MINUTES, minutes)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCodeFor(action),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_DEADLINE_TICK && userActionFor(action) == null) {
            Log.w(TAG, "Ignoring unknown check-in action: $action")
            return
        }
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 15)
        val appContext = context.applicationContext

        // Hold the process open until the resulting message has actually been sent.
        val pending = goAsync()
        scope.launch {
            try {
                // The manager's StateFlows and its deadline loop are main-dispatched,
                // and `getInstance` may be CONSTRUCTING it here (cold process), which
                // resumes the persisted session. Do that on the main thread so no
                // state is published from an arbitrary broadcast thread.
                val manager = withContext(Dispatchers.Main) {
                    CheckInManager.getInstance(appContext)
                }
                if (action == ACTION_DEADLINE_TICK) {
                    Log.d(TAG, "⏰ Deadline alarm fired — running escalation check")
                    withContext(Dispatchers.Main) { manager.runDeadlineCheckNow() }
                } else {
                    val userAction = userActionFor(action)!!
                    Log.d(TAG, "🛡️ Check-in action from notification: $userAction")
                    val applied = withContext(Dispatchers.Main) {
                        manager.applyUserAction(userAction, minutes)
                    }
                    if (!applied) {
                        Log.w(TAG, "🛡️ $userAction arrived with no session to apply it to")
                    }
                }
                // The send is launched on the manager's own IO scope; wait for it
                // here, while `goAsync` still guarantees us a process.
                manager.awaitPendingSend()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Check-in action failed: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/** The four things a user can say about their own check-in. */
enum class CheckInUserAction {
    /** "I'm safe" — not there yet, but fine. Pushes the deadline out. */
    CONFIRM_SAFE,

    /** "I've arrived" — ends the session and tells the contact. */
    ARRIVED,

    /** "+15 min" — pushes the deadline out without any claim about safety. */
    EXTEND,

    /** "Stop" — cancels the check-in and tells the contact it was cancelled. */
    CANCEL,
}

/**
 * The deadline backstop: an exact alarm that survives the app process.
 *
 * See [CheckInActionReceiver] for why the in-process `delay(60_000)` loop is not
 * enough on its own. Both are kept: the loop gives minute-resolution updates while
 * the app is alive, the alarm guarantees the escalation happens at all.
 */
object CheckInDeadlineAlarm {

    private const val TAG = "CheckInAlarm"
    private const val RC_DEADLINE = 91_201
    private const val RC_ESCALATION = 91_202

    /**
     * A few seconds past each boundary. Firing exactly *on* the deadline races the
     * comparison inside `checkDeadline` (`now > deadline`) and would waste a wake-up
     * deciding to do nothing.
     */
    private const val SKEW_MS = 5_000L

    private fun pendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, CheckInActionReceiver::class.java).apply {
            action = CheckInActionReceiver.ACTION_DEADLINE_TICK
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * (Re)arm both alarms for [deadlineMs]. Safe to call on every start, extend and
     * resume: `FLAG_UPDATE_CURRENT` replaces the previous trigger time rather than
     * stacking a second alarm.
     */
    fun schedule(context: Context, deadlineMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        set(am, context, RC_DEADLINE, deadlineMs + SKEW_MS)
        set(
            am, context, RC_ESCALATION,
            deadlineMs + CheckInEscalationPolicy.EMERGENCY_AFTER_WARNING_MS + SKEW_MS
        )
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(context, RC_DEADLINE))
        am.cancel(pendingIntent(context, RC_ESCALATION))
        Log.d(TAG, "⏰ Check-in deadline alarms cancelled")
    }

    private fun set(am: AlarmManager, context: Context, requestCode: Int, triggerAtMs: Long) {
        val pi = pendingIntent(context, requestCode)
        // A boundary already in the past needs no alarm: the caller runs the same
        // check inline, and a past trigger time fires immediately anyway.
        if (triggerAtMs <= System.currentTimeMillis()) {
            am.cancel(pi)
            return
        }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } catch (e: SecurityException) {
            // SCHEDULE_EXACT_ALARM refused. An inexact alarm can slip by several
            // minutes in Doze, which delays a safety alert — it does not cancel it,
            // and it is strictly better than the nothing that was here before.
            Log.w(TAG, "Exact alarm refused, falling back to inexact: ${e.message}")
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }
}
