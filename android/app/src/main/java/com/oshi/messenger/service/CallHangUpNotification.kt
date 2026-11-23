package com.oshi.messenger.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.oshi.messenger.R
import com.oshi.messenger.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * __END_CALL_ALWAYS_VISIBLE_2026_09_23__ A way out of a call that does not depend on the
 * call screen.
 *
 * On a real Galaxy A17 the in-call screen rendered with its End Call button laid out past
 * the bottom edge, so the user could not hang up at all. The layout is fixed in
 * `VoiceCallScreen`, but a call you cannot end is bad enough to deserve a second exit:
 * while a call is ringing out, connecting or connected, an ongoing notification carries a
 * "Hang up" action that ends the call through [EnhancedCallManager.endCall].
 *
 * `CallService` already declared an "End Call" action, but nothing ever starts that
 * service, and its action only stopped the service, not the call. This notifier is a
 * plain notification, not a foreground service, because a `phoneCall` foreground-service
 * type has start-up requirements on Android 14+ that a crash-prone safety net must not
 * take on. If notifications are disabled, it silently does nothing: the screen is still
 * the primary control.
 */
object CallHangUpNotification {
    private const val CHANNEL_ID = "oshi_call_hangup"
    private const val NOTIFICATION_ID = 1004
    const val ACTION_HANG_UP = "com.oshi.messenger.HANG_UP_CALL"

    /** Show (or refresh) the notification for a call in [peerName] with a Hang up action. */
    fun show(context: Context, peerName: String, isVideo: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)

        val openIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hangUpIntent = PendingIntent.getBroadcast(
            context, NOTIFICATION_ID,
            Intent(context, HangUpCallReceiver::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = context.getString(if (isVideo) R.string.chat_video_call else R.string.chat_voice_call)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(peerName)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.call_end),
                hangUpIntent
            )
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_ongoing_call_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }

    /** True for the states in which the user is on, or placing, a call they may want to end. */
    fun isHangUpAble(state: CallState, isOutgoing: Boolean): Boolean = when (state) {
        CallState.IN_CALL, CallState.CONNECTING -> true
        CallState.RINGING -> isOutgoing
        CallState.IDLE, CallState.ENDED -> false
    }
}

/** Receives the notification's Hang up tap and ends the call. */
@AndroidEntryPoint
class HangUpCallReceiver : BroadcastReceiver() {
    @Inject lateinit var enhancedCallManager: EnhancedCallManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CallHangUpNotification.ACTION_HANG_UP) return
        runCatching { enhancedCallManager.endCall() }
        CallHangUpNotification.cancel(context)
    }
}
