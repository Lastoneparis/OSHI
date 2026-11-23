package com.oshi.messenger.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.oshi.messenger.R
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.data.model.AliveCheckInPolicy
import com.oshi.messenger.data.model.PeerNicknameStore
import com.oshi.messenger.data.repository.MessageRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * __ALIVE_CHECK_IN_2026_11_04__ Naming the other person on a notification, from
 * LOCAL data only.
 *
 * `getContactByPublicKeySync` rather than the suspend twin: this is called from
 * a notification build, which has no scope to suspend in, and the same choice
 * `FCMService` made for the same reason. The base64 spelling fan-out is
 * `localContactName`'s, verbatim — one transport hands us base64url and another
 * standard base64, and a lookup that tries one spelling finds nobody.
 *
 * __LEAK_FIX_2026_09_25__ applies here too: no name ever comes off the wire. An
 * unknown key degrades to its first eight characters, which is a label, not a
 * claim about who someone is.
 */
object AliveCheckInNames {

    fun displayName(context: Context, publicKey: String): String {
        val alias = localAlias(context, publicKey)
        // `init` is idempotent and already called from `OSHIApplication.onCreate`;
        // repeated here because a WorkManager wakeup can reach this before any UI
        // has run, and an un-initialised store resolves every nickname to null.
        runCatching { PeerNicknameStore.init(context) }
        val resolved = runCatching { PeerNicknameStore.displayName(alias, publicKey) }.getOrNull()
        return resolved?.trim()?.takeIf { it.isNotEmpty() }
            ?: alias?.trim()?.takeIf { it.isNotEmpty() }
            ?: publicKey.take(8).ifEmpty { context.getString(R.string.alive_unknown_peer) }
    }

    private fun localAlias(context: Context, publicKey: String): String? {
        if (publicKey.isBlank() || publicKey.startsWith("bot:")) return null
        val std = publicKey.replace('-', '+').replace('_', '/').trimEnd('=')
        val candidates = linkedSetOf(
            publicKey,
            std,
            std + "=".repeat((4 - std.length % 4) % 4),
            std.replace('+', '-').replace('/', '_'),
        )
        return try {
            val dao = AppDatabase.getInstance(context.applicationContext).contactDao()
            candidates.firstNotNullOfOrNull {
                dao.getContactByPublicKeySync(it)?.alias?.trim()?.takeIf { a -> a.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.w("AliveCheckIn", "name lookup failed: ${e.javaClass.simpleName}")
            null
        }
    }
}

/**
 * __ALIVE_CHECK_IN_2026_11_04__ A [AliveCheckInPolicy.Breakdown] as words.
 *
 * Compact units ("2d 7h", "45m") because this text lands inside a conversation
 * row beside a name and a timestamp, where a full sentence would push one of
 * the two off the end. The format strings themselves are resources, so a locale
 * that abbreviates differently can.
 */
object AliveDurationText {

    fun short(context: Context, b: AliveCheckInPolicy.Breakdown): String = when {
        b.days > 0 -> context.getString(R.string.alive_duration_days_hours, b.days, b.hours)
        b.hours > 0 -> context.getString(R.string.alive_duration_hours_minutes, b.hours, b.minutes)
        b.minutes > 0 -> context.getString(R.string.alive_duration_minutes, b.minutes)
        else -> context.getString(R.string.alive_duration_now)
    }

    fun short(context: Context, durationMillis: Long): String =
        short(context, AliveCheckInPolicy.breakdown(durationMillis))

    /** How often, as words: "every 24h". */
    fun interval(context: Context, intervalSeconds: Long): String =
        context.getString(R.string.alive_interval_every, short(context, intervalSeconds * 1_000L))
}

/**
 * __ALIVE_CHECK_IN_2026_11_04__ "I'm alive", from the notification shade.
 *
 * The one-tap answer exists because the reminder arrives at the moment the user
 * is least likely to open an app: the deadline. A reminder whose only off-ramp
 * is "unlock, find the conversation, scroll, tap" is a reminder that produces
 * missed check-ins. Mirrors [CheckInActionReceiver], which does the same job
 * for the journey check-in's "I'm safe".
 */
class AliveCheckInActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AliveEntryPoint {
        fun messageRepository(): MessageRepository
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONFIRM) return
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        AliveCheckInStore.attach(context)
        val event = AliveCheckInStore.confirmMine(conversationId) ?: return

        // goAsync, like the quick-reply receiver: the send is network work and
        // onReceive would otherwise return before it starts.
        val pending = goAsync()
        scope.launch {
            try {
                val repo = EntryPointAccessors.fromApplication(
                    context.applicationContext, AliveEntryPoint::class.java
                ).messageRepository()
                repo.sendTextMessage(conversationId, event.toMessageString())
                    .onFailure { Log.w(TAG, "confirmation send failed: ${it.javaClass.simpleName}") }
            } catch (e: Exception) {
                Log.w(TAG, "confirmation threw: ${e.javaClass.simpleName}")
            } finally {
                // The local state is already confirmed, so the deadline moved
                // whether or not the send reached anyone — which is the honest
                // behaviour: the user did check in. Re-arm, then clear.
                runCatching { AliveCheckInWatcher.reschedule(context) }
                runCatching { NotificationManagerCompat.from(context).cancel(notificationId) }
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AliveCheckIn"
        const val ACTION_CONFIRM = "com.oshi.messenger.action.ALIVE_CONFIRM"
        const val EXTRA_CONVERSATION_ID = "alive_conversation_id"
        const val EXTRA_NOTIFICATION_ID = "alive_notification_id"

        fun confirmIntent(context: Context, conversationId: String, notificationId: Int): PendingIntent {
            val intent = Intent(context, AliveCheckInActionReceiver::class.java).apply {
                action = ACTION_CONFIRM
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(
                context, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
