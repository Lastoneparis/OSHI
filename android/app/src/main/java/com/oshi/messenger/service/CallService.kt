package com.oshi.messenger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.oshi.messenger.ui.MainActivity

/**
 * Foreground service for voice/video calls
 * Keeps call active when screen is off or app is backgrounded
 */
class CallService : Service() {

    companion object {
        const val CHANNEL_ID = "oshi_call_channel"
        const val NOTIFICATION_ID = 1003
        const val ACTION_START_CALL = "com.oshi.messenger.START_CALL"
        const val ACTION_END_CALL = "com.oshi.messenger.END_CALL"
        const val EXTRA_PEER_NAME = "peer_name"
        /** __RETURN_TO_CALL_2026_10_28__ the key the content intent navigates back to. */
        const val EXTRA_PEER_KEY = "peer_key"
        const val EXTRA_IS_VIDEO = "is_video"
        const val EXTRA_RETURN_TO_CALL = "return_to_call"
    }

    private var peerName: String = "Unknown"
    private var peerKey: String = ""
    private var isVideoCall: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> {
                peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "Unknown"
                peerKey = intent.getStringExtra(EXTRA_PEER_KEY).orEmpty()
                isVideoCall = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
                val notification = createCallNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        if (isVideoCall)
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                        else
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
            ACTION_END_CALL -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(com.oshi.messenger.R.string.channel_ongoing_call_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(com.oshi.messenger.R.string.channel_ongoing_call_desc)
                setShowBadge(true)
                enableVibration(true)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createCallNotification(): Notification {
        // __RETURN_TO_CALL_2026_10_28__ back to the CALL SCREEN, not just to the app.
        // Landing on MainActivity alone drops the user wherever they happened to be —
        // which for the person who reported this was the chat they had walked into, with
        // the call still running and still no way to hang it up.
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_RETURN_TO_CALL, true)
                putExtra(EXTRA_PEER_KEY, peerKey)
                putExtra(EXTRA_IS_VIDEO, isVideoCall)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endCallIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CallService::class.java).apply {
                action = ACTION_END_CALL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callType = if (isVideoCall) "Video call" else "Voice call"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$callType with $peerName")
            .setContentText(getString(com.oshi.messenger.R.string.channel_ongoing_call_action))
            .setSmallIcon(com.oshi.messenger.R.drawable.ic_notification_call)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "End Call",
                endCallIntent
            )
            .build()
    }
}
