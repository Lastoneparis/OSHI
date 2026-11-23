package com.oshi.messenger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.oshi.messenger.R

/**
 * Foreground service that hosts an active **live location share**, and nothing else.
 *
 * iOS keeps the position flowing while the app is backgrounded by setting
 * `allowsBackgroundLocationUpdates = true` / `pausesLocationUpdatesAutomatically = false`
 * / `showsBackgroundLocationIndicator = true` on the sharing path
 * (`OSHI/LocationSharingManager.swift:459-462`). Android's equivalent is a running
 * foreground service typed `location`: without one, Android 10+ throttles the app to
 * a handful of fixes per hour once it leaves the foreground, so the peer's dot froze
 * as soon as the sender put the phone in their pocket — which is the whole point of
 * sharing live location while walking.
 *
 * A `location`-typed foreground service grants continued access on the *while-in-use*
 * grant alone, so this deliberately does not need — and must not ask for —
 * `ACCESS_BACKGROUND_LOCATION`, which stays reserved for the armed check-in.
 *
 * Started by [com.oshi.messenger.network.location.LocationSharingManager.startLiveSharing]
 * and stopped in `stopLiveSharing`, so its lifetime is exactly the share's, mirroring
 * iOS flipping the flags back off at `LocationSharingManager.swift:524-525`.
 *
 * No new translation keys: the notification reuses `location_sharing_live` and
 * `location_expires_in`, which already exist in all 34 languages.
 */
class LiveLocationService : Service() {

    companion object {
        private const val TAG = "LiveLocationSvc"

        const val CHANNEL_ID = "oshi_live_location"
        const val NOTIFICATION_ID = 91_020

        const val EXTRA_END_TIME = "live_location_end_time"

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Start (or refresh) the service. Safe to call repeatedly.
         *
         * @param endTimeMillis when the share expires, shown in the notification.
         */
        fun start(context: Context, endTimeMillis: Long) {
            val intent = Intent(context, LiveLocationService::class.java)
                .putExtra(EXTRA_END_TIME, endTimeMillis)
            try {
                if (isRunning) {
                    context.startService(intent)
                } else {
                    ContextCompat.startForegroundService(context, intent)
                }
            } catch (e: Exception) {
                // Android 12+ throws ForegroundServiceStartNotAllowedException when the
                // app is off-foreground. Sharing still works while the app is open; the
                // failure must be visible in a bug report rather than silently degrade.
                Log.e(TAG, "❌ Could not start live-location foreground service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, LiveLocationService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "❌ Could not stop live-location foreground service: ${e.message}")
            }
        }
    }

    private var endTimeMillis: Long = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            endTimeMillis = intent.getLongExtra(EXTRA_END_TIME, endTimeMillis)
        }
        startForegroundCompat()

        if (intent == null) {
            // Redelivery after a process kill. The share itself lives only in memory,
            // so there is nothing left to keep alive — do not leave a location-typed
            // service running, nor a notification claiming a share is live.
            Log.d(TAG, "📍 No live share after restart — stopping service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ startForeground(location) refused: ${e.message}")
        }
    }

    override fun onDestroy() {
        isRunning = false
        Log.d(TAG, "📍 Live-location foreground service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.location_sharing_live),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.location_updates_realtime)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val endText = if (endTimeMillis > 0) {
            java.text.DateFormat
                .getTimeInstance(java.text.DateFormat.SHORT)
                .format(java.util.Date(endTimeMillis))
        } else {
            "—"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.location_sharing_live))
            .setContentText(getString(R.string.location_expires_in, endText))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
        contentIntent()?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    private fun contentIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this, NOTIFICATION_ID, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
