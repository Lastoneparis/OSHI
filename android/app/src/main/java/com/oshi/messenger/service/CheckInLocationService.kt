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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.oshi.messenger.R

/**
 * Foreground service that hosts an **armed check-in**, and nothing else.
 *
 * Why it exists: `CheckInManager` used to request location updates straight from
 * the app process with no service behind them, so an armed check-in stopped
 * receiving positions the moment the user left the app — which silently defeats
 * the whole safety feature, including the emergency escalation that is the point
 * of it. On Android, "may keep reading location while backgrounded" is granted by
 * a running foreground service typed `location` (declared in the manifest as
 * `android:foregroundServiceType="location"`) — and from 2026-10-24 that service is
 * the ONLY thing granting it: ACCESS_BACKGROUND_LOCATION was removed after a Google
 * Play rejection, so this service's lifetime is exactly the check-in's reach.
 *
 * Lifecycle is copied from iOS rather than invented:
 * `OSHI/CheckInManager.swift:236-238` turns `allowsBackgroundLocationUpdates` ON
 * only for the duration of an active session, and `:256` flips it back OFF in
 * `stopLocationMonitoring` so the app stops touching location the instant the
 * check-in ends. This service is started in `CheckInManager.startCheckIn` and
 * stopped in `stopCheckIn` (arrival / cancel) and `triggerEmergency` (expiry) —
 * see [CheckInServicePolicy]. It is *not* used for live location sharing, which
 * is deliberately foreground-only on both platforms
 * (`OSHI/LocationSharingManager.swift:185-190`).
 */
class CheckInLocationService : Service() {

    companion object {
        private const val TAG = "CheckInLocationSvc"

        /** Separate from the alert channel so muting one cannot mute the other. */
        const val CHANNEL_ID = "oshi_checkin_journey"
        const val NOTIFICATION_ID = 91_010

        const val EXTRA_DESTINATION = "checkin_destination"
        const val EXTRA_ETA = "checkin_eta"

        /**
         * Lets `CheckInManager` resume a persisted session without re-starting a
         * service that is already up — which matters because the restart path
         * below asks the manager to resume, and the manager asks for the service.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Start (or refresh the notification of) the check-in service.
         * Safe to call repeatedly; `onStartCommand` just updates the text.
         */
        fun start(
            context: Context,
            destination: String,
            etaText: String,
        ) {
            val intent = Intent(context, CheckInLocationService::class.java).apply {
                putExtra(EXTRA_DESTINATION, destination)
                putExtra(EXTRA_ETA, etaText)
            }
            try {
                if (isRunning) {
                    // Already in the foreground: a plain startService is exempt
                    // from the background-start restriction and just refreshes the
                    // notification. Going through startForegroundService again
                    // would risk the Android 12+ throw for no benefit — which is
                    // the path an ETA refresh from a background tick takes.
                    context.startService(intent)
                } else {
                    ContextCompat.startForegroundService(context, intent)
                }
            } catch (e: Exception) {
                // Android 12+ throws ForegroundServiceStartNotAllowedException if
                // we are somehow off-foreground (e.g. resuming a persisted session
                // from a background message callback). Log it — an armed check-in
                // that silently lost its service must be visible in a bug report.
                Log.e(TAG, "❌ Could not start check-in foreground service: ${e.message}")
            }
        }

        /** Stop the service. No-op if it was never started. */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CheckInLocationService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "❌ Could not stop check-in foreground service: ${e.message}")
            }
        }
    }

    private var destination: String = ""
    private var etaText: String = ""

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            destination = intent.getStringExtra(EXTRA_DESTINATION) ?: destination
            etaText = intent.getStringExtra(EXTRA_ETA) ?: etaText
        }

        startForegroundCompat()

        if (intent == null) {
            // START_STICKY redelivery after the process was killed. The session
            // itself lives in SharedPreferences, so hand control back to the
            // manager: constructing it resumes any persisted active session
            // (loadPersistedSession). Posted to the main looper so we never
            // re-enter the singleton's constructor from inside a start we caused.
            Handler(Looper.getMainLooper()).post { resumeOrStop() }
        }

        // A safety session must outlive a low-memory kill; the notification is
        // re-posted and the session re-armed from disk above.
        return START_STICKY
    }

    private fun resumeOrStop() {
        val manager = CheckInManager.getInstance(applicationContext)
        // `hasOpenSession`, not `hasActiveSession`: a session that has already shown
        // its "Are you okay?" prompt has status `expired`, and tearing the service
        // down for it would end the check-in in exactly the fifteen minutes the user
        // still has to answer.
        if (!manager.hasOpenSession) {
            // Nothing to guard — do not keep a location-typed service alive, and
            // do not leave a notification claiming a journey is being watched.
            Log.d(TAG, "🛡️ No active check-in after restart — stopping service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        manager.activeSession.value?.let { session ->
            destination = session.destinationAddress
            etaText = session.formattedRemainingTime
            startForegroundCompat()
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ requires the type at runtime too; Android 14+ kills
                // the process if it is missing or does not match the manifest.
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ startForeground(location) refused: ${e.message}")
        }
    }

    override fun onDestroy() {
        isRunning = false
        Log.d(TAG, "🛡️ Check-in foreground service stopped (background location OFF)")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // MARK: - Notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.checkin_service_channel_name),
            // LOW: the ongoing status must be visible (the platform requires it
            // for a foreground service) but must not buzz — the WARNING and
            // EMERGENCY alerts on the separate high-importance channel do that.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.checkin_service_channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val title = getString(R.string.checkin_active)
        val body = if (destination.isBlank()) {
            getString(R.string.checkin_service_status_generic)
        } else {
            getString(
                R.string.checkin_service_status_line,
                getString(R.string.checkin_to, destination),
                getString(R.string.checkin_eta_time, etaText.ifBlank { "—" })
            )
        }
        // __NO_BACKGROUND_LOCATION_2026_10_24__ Always appended, not conditional.
        // OSHI holds no background-location grant, so this notification IS the thing
        // keeping the check-in alive — honest, not decorative, and the same sentence
        // the arming screen showed before the user committed.
        val fullBody = body + "\n" + getString(R.string.checkin_service_foreground_only)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullBody))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
        contentIntent()?.let { builder.setContentIntent(it) }

        // ── The off-ramp, permanently on screen ──
        //
        // This notification is the one thing guaranteed to be visible for the whole
        // journey — the platform requires it while the service runs. Until this pass
        // it was a status line with nothing to press, so the only way to end a
        // check-in was to reach the moment it ended itself, by escalating to an
        // emergency alert sent to a contact.
        //
        // The three buttons are the same three iOS puts on `CheckInActiveBanner`
        // (`OSHI/CheckInView.swift:568-628`): arrived / +15 min / stop. They are
        // handled by `CheckInActionReceiver`, which reaches the manager from a cold
        // process — so they work when the user has not opened OSHI in an hour.
        builder.addAction(
            R.drawable.ic_notification,
            getString(R.string.checkin_arrived),
            CheckInActionReceiver.actionIntent(this, CheckInActionReceiver.ACTION_ARRIVED)
        )
        builder.addAction(
            R.drawable.ic_notification,
            getString(R.string.checkin_extend_15),
            CheckInActionReceiver.actionIntent(this, CheckInActionReceiver.ACTION_EXTEND, 15)
        )
        builder.addAction(
            R.drawable.ic_notification,
            getString(R.string.map_nav_stop),
            CheckInActionReceiver.actionIntent(this, CheckInActionReceiver.ACTION_CANCEL)
        )
        return builder.build()
    }

    /** Tapping the ongoing notification opens the check-in UI, as the alerts do. */
    private fun contentIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launch.putExtra(CheckInManager.EXTRA_OPEN_CHECKIN, true)
        return PendingIntent.getActivity(
            this, NOTIFICATION_ID, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
