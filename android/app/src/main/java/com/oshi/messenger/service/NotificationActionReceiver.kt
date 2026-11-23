package com.oshi.messenger.service

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.oshi.messenger.R
import com.oshi.messenger.data.repository.MessageRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REPLY = "com.oshi.messenger.ACTION_REPLY"
        const val ACTION_MARK_READ = "com.oshi.messenger.ACTION_MARK_READ"
        const val EXTRA_SENDER_KEY = "sender_public_key"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val KEY_TEXT_REPLY = "key_text_reply"
        private const val TAG = "NotificationAction"
    }

    // Hilt entry point — the codebase's pattern for injecting into non-Hilt
    // components (mirrors ScheduledMessageWorkerEntryPoint).
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NotificationActionEntryPoint {
        fun messageRepository(): MessageRepository
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val senderKey = intent.getStringExtra(EXTRA_SENDER_KEY) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val repo = EntryPointAccessors.fromApplication(
            context.applicationContext,
            NotificationActionEntryPoint::class.java
        ).messageRepository()

        when (intent.action) {
            ACTION_REPLY -> {
                val replyText = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_TEXT_REPLY)?.toString()
                if (!replyText.isNullOrBlank()) {
                    handleReply(context, repo, senderKey, replyText, notificationId)
                }
            }
            ACTION_MARK_READ -> handleMarkRead(context, repo, senderKey, notificationId)
        }
    }

    private fun handleReply(
        context: Context, repo: MessageRepository,
        senderKey: String, replyText: String, notificationId: Int
    ) {
        // goAsync keeps the receiver alive past onReceive for the network send.
        val pending = goAsync()
        scope.launch {
            try {
                repo.sendTextMessage(senderKey, replyText)
                    .onFailure { Log.e(TAG, "Quick reply send failed: ${it.message}") }
                repo.markConversationAsRead(senderKey)
            } catch (e: Exception) {
                Log.e(TAG, "Quick reply error: ${e.message}")
            } finally {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(
                    notificationId,
                    NotificationCompat.Builder(context, FCMService.CHANNEL_MESSAGES)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentText(context.getString(R.string.notification_reply_sent))
                        .setTimeoutAfter(2500)
                        .build()
                )
                pending.finish()
            }
        }
    }

    private fun handleMarkRead(
        context: Context, repo: MessageRepository,
        senderKey: String, notificationId: Int
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                repo.markConversationAsRead(senderKey)
            } catch (e: Exception) {
                Log.e(TAG, "Mark-read error: ${e.message}")
            } finally {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(notificationId)
                pending.finish()
            }
        }
    }
}
