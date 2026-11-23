package com.oshi.messenger.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.oshi.messenger.R
import com.oshi.messenger.data.model.AliveCheckInPolicy
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * __ALIVE_CHECK_IN_2026_11_04__ The half of the feature that runs when nobody is
 * looking.
 *
 * A check-in that is never confirmed produces **nothing on the wire** — that is
 * what "missed" means. So the deadline has to be noticed locally, by the device
 * that is waiting, with no help from a peer and none from a server. Which is
 * also why this works at all in the conditions the feature is for: no relay, no
 * push, mesh-only, airplane mode on one side of the conversation.
 *
 * ## One alarm, not one per conversation
 *
 * [reschedule] enqueues a SINGLE unique work item, timed to the nearest
 * deadline across every conversation. The worker then evaluates all of them and
 * re-arms itself for the next one. A work item per conversation would be N
 * wakeups where one does, and on a phone in Doze the difference between one
 * deferred alarm and forty is the difference between a feature and a battery
 * complaint.
 *
 * ## What it can promise
 *
 * Less than it looks, and the UI says so. WorkManager defers under Doze, a
 * force-stopped app runs nothing, and notification permission can be absent or
 * the channel muted. So the overdue notice is a best effort, and the countdown
 * in the conversation — which is computed from stored timestamps at the moment
 * it is drawn, not from any timer — is the part that is always right.
 *
 * iOS: `OSHI/AliveCheckInWatcher.swift`, where the same job is done with
 * `UNTimeIntervalNotificationTrigger` because iOS lets a notification be
 * scheduled for an instant and shown without the app running at all.
 */
object AliveCheckInWatcher {

    private const val TAG = "AliveCheckIn"
    private const val WORK_NAME = "alive_check_in_deadline"
    const val CHANNEL_ID = "oshi_alive_checkin"

    /**
     * A floor under the computed delay. WorkManager treats a zero or negative
     * initial delay as "run now", and a deadline that has only just passed
     * would then re-enqueue itself immediately, evaluate, re-enqueue — a spin
     * that only ends when the user confirms.
     */
    private const val MIN_DELAY_MILLIS = 30_000L

    /**
     * Look at every conversation, post what is owed, and re-arm.
     *
     * Idempotent and cheap: safe from the worker, from app start, and from the
     * send path. The claim calls inside
     * ([AliveCheckInStore.claimTheirOverdueNotice]) are what make "safe to call
     * twice" true rather than hopeful.
     */
    fun evaluate(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        AliveCheckInStore.attach(context)
        val entries = AliveCheckInStore.entries.value

        for ((conversationId, entry) in entries) {
            if (entry.isIdle) continue

            // Their deadline, missed.
            if (entry.theirsEnabled &&
                entry.theirsLastConfirmedAtMillis > 0L &&
                entry.theirsIntervalSeconds > 0L
            ) {
                val due = AliveCheckInPolicy.dueAtMillis(
                    entry.theirsLastConfirmedAtMillis, entry.theirsIntervalSeconds
                )
                if (nowMillis >= due && AliveCheckInStore.claimTheirOverdueNotice(conversationId, due)) {
                    postTheirOverdue(context, conversationId, due, nowMillis)
                }
            }

            // Our own deadline, reached. A reminder, not an alarm: the person
            // who set the schedule is the one who can still act on it, and
            // reminding them is the difference between a missed check-in and a
            // forgotten one.
            if (entry.mineEnabled &&
                entry.mineLastConfirmedAtMillis > 0L &&
                entry.mineIntervalSeconds > 0L
            ) {
                val due = AliveCheckInPolicy.dueAtMillis(
                    entry.mineLastConfirmedAtMillis, entry.mineIntervalSeconds
                )
                if (nowMillis >= due && AliveCheckInStore.claimMyDueReminder(conversationId, due)) {
                    postMyReminder(context, conversationId)
                }
            }
        }

        reschedule(context, nowMillis)
    }

    /**
     * The next instant at which anything happens, or null when nothing is
     * scheduled anywhere. Pure, so the choice of "nearest deadline still ahead"
     * is testable without WorkManager.
     */
    fun nextDeadlineMillis(
        entries: Map<String, AliveCheckInStore.Entry>,
        nowMillis: Long,
    ): Long? = entries.values
        .asSequence()
        .filter { !it.isIdle }
        .flatMap { e ->
            sequence {
                if (e.mineEnabled && e.mineLastConfirmedAtMillis > 0L && e.mineIntervalSeconds > 0L) {
                    yield(AliveCheckInPolicy.dueAtMillis(e.mineLastConfirmedAtMillis, e.mineIntervalSeconds))
                }
                if (e.theirsEnabled && e.theirsLastConfirmedAtMillis > 0L && e.theirsIntervalSeconds > 0L) {
                    yield(AliveCheckInPolicy.dueAtMillis(e.theirsLastConfirmedAtMillis, e.theirsIntervalSeconds))
                }
            }
        }
        .filter { it > nowMillis }
        .minOrNull()

    fun reschedule(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        AliveCheckInStore.attach(context)
        val next = nextDeadlineMillis(AliveCheckInStore.entries.value, nowMillis)
        val wm = WorkManager.getInstance(context.applicationContext)
        if (next == null) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val delay = (next - nowMillis).coerceAtLeast(MIN_DELAY_MILLIS)
        wm.enqueueUniqueWork(
            WORK_NAME,
            // REPLACE: there is one alarm and it always points at the nearest
            // deadline. KEEP would pin it to whatever the first caller computed
            // and leave a newly-set, sooner schedule unwatched.
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AliveCheckInWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag("alive_check_in")
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
        )
        Log.d(TAG, "next deadline in ${delay / 1000}s")
    }

    // ── Notifications ──

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            android.app.NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.alive_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.alive_channel_description)
                enableVibration(true)
                // Deliberately NOT bypassing Do Not Disturb, unlike the journey
                // check-in's emergency alert. A missed heartbeat is a silence,
                // not an emergency, and a feature that wakes someone at 04:00 to
                // say "no response yet" is a feature they turn off.
            }
        )
    }

    /** A stable id per conversation, so a second notice replaces the first. */
    private fun notificationId(conversationId: String, salt: Int): Int =
        abs(conversationId.hashCode() * 31 + salt) % 1_000_000 + 7_100_000

    private fun openChatIntent(context: Context, conversationId: String, requestCode: Int): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // The extra `MainActivity.handleIntent` already routes on — it opens the
        // 1:1 thread, which is also the "contact this person" action the overdue
        // notice owes the reader.
        launch.putExtra("senderKey", conversationId)
        return PendingIntent.getActivity(
            context, requestCode, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * "No response from X." Never "X is in danger", never a guess at why —
     * see [AliveCheckInPolicy.OVERDUE_IS_NOT_PROOF]. The elapsed time is in the
     * body because it is the only fact the device actually has.
     */
    private fun postTheirOverdue(context: Context, conversationId: String, dueAtMillis: Long, nowMillis: Long) {
        if (!canPost(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted — overdue notice NOT delivered")
            return
        }
        val name = AliveCheckInNames.displayName(context, conversationId)
        val elapsed = AliveCheckInPolicy.breakdown(nowMillis - dueAtMillis)
        val body = context.getString(
            R.string.alive_notification_overdue_body,
            AliveDurationText.short(context, elapsed)
        )
        val id = notificationId(conversationId, 1)
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.alive_notification_overdue_title, name))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        openChatIntent(context, conversationId, id)?.let {
            builder.setContentIntent(it)
            builder.addAction(
                R.drawable.ic_notification,
                context.getString(R.string.alive_action_message_them),
                it
            )
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
            .onFailure { Log.w(TAG, "overdue notice refused: ${it.javaClass.simpleName}") }
    }

    /** "Your check-in is due" — with the button that answers it in one tap. */
    private fun postMyReminder(context: Context, conversationId: String) {
        if (!canPost(context)) return
        val name = AliveCheckInNames.displayName(context, conversationId)
        val id = notificationId(conversationId, 2)
        ensureChannel(context)
        val body = context.getString(R.string.alive_notification_mine_due_body, name)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.alive_notification_mine_due_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        openChatIntent(context, conversationId, id)?.let { builder.setContentIntent(it) }
        builder.addAction(
            R.drawable.ic_notification,
            context.getString(R.string.alive_action_im_alive),
            AliveCheckInActionReceiver.confirmIntent(context, conversationId, id)
        )
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
            .onFailure { Log.w(TAG, "reminder refused: ${it.javaClass.simpleName}") }
    }

    /**
     * Take a stale "no response from X" out of the shade.
     *
     * Called when X answers, or ends their schedule. No replacement banner: the
     * check-in is `.rendered`, so the ordinary message notification already
     * carries the news, and two banners for one event is the notification spam
     * the design brief rules out. What would be wrong is leaving the old one up.
     *
     * The claim is cleared in the store by the same event
     * ([AliveCheckInStore.applyIncoming] resets
     * `notifiedTheirOverdueForDueAtMillis`), so the NEXT missed deadline can
     * still raise its own notice.
     */
    fun clearOverdueNotice(context: Context, conversationId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationId(conversationId, 1))
        }
    }
}

/**
 * __ALIVE_CHECK_IN_2026_11_04__ Re-arms the watcher when the deadline arrives.
 *
 * [AliveCheckInWatcher.evaluate] does the work and schedules the next run, so
 * this stays a two-line worker on purpose.
 */
class AliveCheckInWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            AliveCheckInWatcher.evaluate(context.applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w("AliveCheckIn", "deadline sweep threw: ${e.javaClass.simpleName}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
