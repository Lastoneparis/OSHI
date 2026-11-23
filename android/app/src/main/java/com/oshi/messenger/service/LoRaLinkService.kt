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
 * __LORA_BACKGROUND_NOTIFICATION_2026_11_03__ Foreground service that keeps the
 * link to a paired LoRa radio alive while OSHI is in the background, and
 * nothing else.
 *
 * WHY A MESSAGE FROM THE RADIO USED TO ARRIVE NOWHERE
 *
 * The GATT connection to the node lives in `MeshtasticManager`, a process
 * singleton, and the inbound collectors (`receivedMessages`, `receivedText`)
 * live in a process-scoped coroutine inside `MessageRepository`. Neither
 * survives the process being frozen: with no foreground service of any type
 * running, Android is free to doze the app minutes after it leaves the screen,
 * the GATT link drops, and a message that reaches the radio reaches nothing
 * else. The user sees it only when they next open OSHI — which is precisely
 * the situation LoRa exists for and the one where opening the app is least
 * likely.
 *
 * This service holds nothing itself. Its whole job is to be running, so the
 * process stays warm and the connection the manager already owns is not torn
 * down underneath it. `connectedDevice` is the honest type for that: the app
 * is maintaining a session with an external device the user deliberately
 * paired.
 *
 * LIFETIME is exactly the radio connection's — started when `MeshtasticManager`
 * reaches `READY`, stopped the moment it leaves it. A node that is not
 * connected has nothing to keep alive, and a permanent notification for a
 * radio that is not there would be a lie the user cannot dismiss.
 *
 * NO NEW TRANSLATION KEYS: the notification reuses `meshtastic_title`
 * ("Long-Range Radio (LoRa)") and `meshtastic_state_ready` ("Connected"),
 * which already exist in all 34 languages.
 */
class LoRaLinkService : Service() {

    companion object {
        private const val TAG = "LoRaLinkSvc"

        const val CHANNEL_ID = "oshi_lora_link"
        const val NOTIFICATION_ID = 91_040

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Safe to call repeatedly; a second start is a no-op for the user. */
        fun start(context: Context) {
            if (isRunning) return
            val intent = Intent(context, LoRaLinkService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "could not start LoRa link service: ${it.message}") }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            runCatching { context.stopService(Intent(context, LoRaLinkService::class.java)) }
                .onFailure { Log.w(TAG, "could not stop LoRa link service: ${it.message}") }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The platform demands startForeground promptly after
        // startForegroundService, so this happens before anything else.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            isRunning = true
            Log.d(TAG, "📡 LoRa link held in the foreground")
        }.onFailure {
            Log.w(TAG, "startForeground refused: ${it.message}")
            stopSelf()
        }
        // START_STICKY would resurrect this with a null intent after the radio
        // had already gone; the connection state owns the lifetime, not the OS.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        Log.d(TAG, "📡 LoRa link released")
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        // LOW: this notification is a status line the platform requires us to
        // show, not news. It must never make a sound — the messages it enables
        // will do that themselves.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.meshtastic_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = runCatching {
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, com.oshi.messenger.ui.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }.getOrNull()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.meshtastic_title))
            .setContentText(getString(R.string.meshtastic_state_ready))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }
}
