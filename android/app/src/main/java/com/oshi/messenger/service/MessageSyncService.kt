package com.oshi.messenger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service for syncing messages in the background
 * Handles VPS polling when app is backgrounded
 */
class MessageSyncService : Service() {

    companion object {
        private const val TAG = "MessageSyncService"
        const val CHANNEL_ID = "oshi_sync_channel"
        const val NOTIFICATION_ID = 1002
    }

    /**
     * The self-stop that keeps us short of Android 15's cumulative daily `dataSync`
     * budget. Posted once, on the first start, and cancelled in `onDestroy`.
     *
     * A plain `Handler` is enough and is not a frozen-process trap: the whole point
     * of a running foreground service is that this process is NOT frozen, so the
     * delayed message is delivered. That is also exactly why `onTimeout` was not
     * enough on its own — it fires at the moment the platform has already decided to
     * kill us, and we lose the race (3 reports still on versionCode 81).
     */
    private val selfStop = Handler(Looper.getMainLooper())
    private var selfStopArmed = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        // Android 14+ requires the foregroundServiceType to be passed to
        // startForeground() at runtime; otherwise the system kills the process
        // with ForegroundServiceDidNotStopInTimeException.
        //
        // The call itself can also be refused: once the daily dataSync budget is
        // spent, Android 15+ throws ForegroundServiceStartNotAllowedException
        // instead of starting us. START_STICKY means the system retries this
        // service on its own after a timeout stop, so an uncaught throw here is
        // a crash the user never asked for. Refusal means "not now" — stop
        // quietly and let the next launch or the next FCM push bring us back.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground refused (${e.javaClass.simpleName}); stopping", e)
            stopSelf()
            return START_NOT_STICKY
        }

        armSelfStop()

        // START_STICKY asks the platform to restart us in the background — the one
        // start Android 12+ refuses. See [ForegroundServicePolicy]; the mesh service
        // crash-looped on exactly that for 45 reports.
        return if (ForegroundServicePolicy.stickyRestartIsSafe(Build.VERSION.SDK_INT)) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    /**
     * Stop on our own clock, before the platform's. The process stays alive for the
     * call-signal backstop and the mesh for as long as that is permitted, and once
     * the deadline passes messages still arrive through FCM — which is how they
     * arrive on iOS all the time, since iOS declares no background modes at all.
     */
    private fun armSelfStop() {
        if (selfStopArmed) return
        val delay = ForegroundServicePolicy.dataSyncSelfStopMs(Build.VERSION.SDK_INT) ?: return
        selfStopArmed = true
        selfStop.postDelayed({
            Log.i(TAG, "self-stop after ${delay}ms — staying inside the dataSync budget")
            stopCleanly()
        }, delay)
    }

    override fun onDestroy() {
        selfStop.removeCallbacksAndMessages(null)
        selfStopArmed = false
        super.onDestroy()
    }

    // Android enforces a cumulative daily runtime cap on dataSync foreground
    // services. Miss the timeout callback and the process is killed with
    // ForegroundServiceDidNotStopInTimeException — which users were hitting.
    //
    // 🔴 There are TWO callbacks and they are NOT interchangeable:
    //
    //   • onTimeout(startId)            — API 34. The system only ever calls it
    //                                     for a `shortService`, which this is not.
    //   • onTimeout(startId, fgsType)   — API 35. This is the one the platform
    //                                     calls for `dataSync`.
    //
    // Only the one-argument form was overridden here, so on Android 15 and 16 —
    // every device this build now targets — nothing ran when the budget expired
    // and the crash came back after roughly six hours of uptime. Both are
    // overridden now; the API 34 form stays for `shortService` parity and costs
    // nothing.
    override fun onTimeout(startId: Int) {
        Log.i(TAG, "FGS timeout (API 34 path) — stopping cleanly")
        stopCleanly()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.i(TAG, "FGS timeout (type=$fgsType) — stopping cleanly")
        stopCleanly()
    }

    private fun stopCleanly() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(com.oshi.messenger.R.string.channel_sync_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(com.oshi.messenger.R.string.channel_sync_desc)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 🔎 "It's loading all the time" — the other half of the battery complaint, and it
     * was never a spinner in the app. It was THIS.
     *
     * This notification is `setOngoing(true)` on a `dataSync` foreground service, which
     * on Android 14+ means the user cannot swipe it away for as long as the service
     * runs — i.e. always. It said **"OSHI Sync / Syncing messages…"** on
     * `android.R.drawable.ic_popup_sync`, the platform's sync-arrows glyph. So the
     * shade permanently showed a sync icon and the words "Syncing messages…", whether
     * or not anything was being synced. The app was telling the user it was loading,
     * continuously, forever. There is no equivalent on iOS at all — iOS declares no
     * `UIBackgroundModes` and shows the user nothing while backgrounded.
     *
     * The service itself stays: it is what keeps the process alive for the (now much
     * slower) call-signal backstop and the mesh, and `MainActivity` / `BootReceiver`
     * start it. What changes is that it stops making a false progress claim — a
     * neutral title, honest text, and a static icon instead of the sync-arrows one.
     * `setShowWhen(false)` drops the running timestamp, which also read as activity.
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(com.oshi.messenger.R.string.channel_sync_ready_title))
            .setContentText(getString(com.oshi.messenger.R.string.channel_sync_ready_text))
            .setSmallIcon(com.oshi.messenger.R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setShowWhen(false)
            .setOngoing(true)
            .build()
    }
}
