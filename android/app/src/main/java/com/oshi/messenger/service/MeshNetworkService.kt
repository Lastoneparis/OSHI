package com.oshi.messenger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.oshi.messenger.R

/**
 * Foreground service for maintaining mesh network connections
 * Keeps the app alive for P2P messaging when in background
 */
class MeshNetworkService : Service() {

    companion object {
        private const val TAG = "MeshNetworkService"
        const val CHANNEL_ID = "oshi_mesh_channel"
        const val NOTIFICATION_ID = 1001

        /**
         * __ANDROID_ANSWERS_READS_IN_BACKGROUND_2026_10_23__
         *
         * Hold the process up for as long as the mesh is running, so that
         * `CrossPlatformMesh.onCharacteristicReadRequest` is still there to
         * answer the read an iPhone makes while the app is off screen. See
         * [ForegroundServicePolicy.shouldStartMeshLifeline] for why this type
         * and not `dataSync`, and why the start is gated.
         *
         * Called by `MeshNetworkManager`, which owns the mesh's lifetime —
         * before this existed the only caller in the whole app was
         * [BootReceiver], so outside of a reboot the service never ran at all.
         */
        fun hold(context: Context, appIsForeground: Boolean) {
            if (!ForegroundServicePolicy.shouldStartMeshLifeline(
                    Build.VERSION.SDK_INT, appIsForeground)) {
                Log.d(TAG, "mesh is up but we are in the background; not claiming a foreground service")
                return
            }
            // __FGS_DID_NOT_START_2026_11_10__ Play vitals (Galaxy S25, vc127):
            // ForegroundServiceDidNotStartInTimeException from this start. Every
            // path in onStartCommand that bails before startForeground (mesh off,
            // or startForeground refused because a `connectedDevice` service on
            // Android 14+ needs a Bluetooth permission granted) is a process kill
            // the app cannot catch. So only start when onStartCommand will be able
            // to go foreground.
            if (!canGoForeground(context)) return
            val intent = Intent(context, MeshNetworkService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // A refused start costs a mesh link until the next launch. It must
                // never cost the user their app: this is the exception that put 45
                // crash reports on 8 users (see [ForegroundServicePolicy]).
                Log.w(TAG, "mesh foreground service refused (${e.javaClass.simpleName}); BLE reads will only be answered on screen")
            }
        }

        /** True when onStartCommand will be able to call startForeground. */
        fun canGoForeground(context: Context): Boolean {
            if (!com.oshi.messenger.network.mesh.MeshPrefs.isEnabled(context)) return false
            // __BACKGROUND_PRESENCE_2026_11_10__ the lifeline exists only to answer
            // BLE while the app is closed; without the opt-in (or a phone with no
            // push) it is just a permanent status-bar logo.
            if (!BackgroundPresence.shouldRun(context)) return false
            if (!ForegroundServicePolicy.connectedDeviceTypeAllowed(
                    Build.VERSION.SDK_INT, grantedBluetooth(context))) {
                Log.d(TAG, "no Bluetooth permission granted; not claiming a connectedDevice foreground service")
                return false
            }
            return true
        }

        private fun grantedBluetooth(context: Context): Set<String> =
            ForegroundServicePolicy.CONNECTED_DEVICE_PERMISSIONS.filter {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.toSet()

        /** Let the process be cached again: the mesh is down, nothing to answer. */
        fun release(context: Context) {
            try {
                context.stopService(Intent(context, MeshNetworkService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not stop the mesh foreground service: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 🔴 The single biggest crash OSHI had on Android: 45 reports from 8 users in
        // the 30 days to 2026-10-16, all of them here, all
        // ForegroundServiceStartNotAllowedException. A null intent means the platform
        // revived this service by itself after a process kill — in the background,
        // where Android 12+ refuses a foreground-service start. The old code called
        // startForeground() anyway, uncaught, so the process died and the system tried
        // again. See [ForegroundServicePolicy] for the full account.
        // (N22) A second line of defence behind the boot receiver's own check:
        // whatever starts this service — the system reviving it, an alarm, a
        // future caller — it must not raise a foreground notification for a
        // mesh the user switched off. Checked before `startForeground`, so the
        // notification is never posted at all rather than posted and removed.
        if (!com.oshi.messenger.network.mesh.MeshPrefs.isEnabled(this)) {
            Log.i(TAG, "mesh disabled by the user — not going foreground")
            stopSelf()
            return START_NOT_STICKY
        }

        val decision = ForegroundServicePolicy.decideStart(
            sdkInt = Build.VERSION.SDK_INT,
            isSystemRestart = intent == null,
        )
        if (decision == ForegroundStartDecision.SKIP_SYSTEM_RESTART) {
            Log.i(TAG, "system restart in the background — not allowed to go foreground; stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        // Android 14+ requires the foregroundServiceType at runtime to match the
        // AndroidManifest entry.
        //
        // Wrapped because even an allowed-looking start can be refused: the
        // BOOT_COMPLETED exemption is a short window, and a cold start that is slow
        // (this process loads llama.cpp) can outlive it. A refusal means "not now",
        // not "crash the user's phone" — mesh links are re-established the next time
        // the app is opened.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground refused (${e.javaClass.simpleName}); stopping", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return if (ForegroundServicePolicy.stickyRestartIsSafe(Build.VERSION.SDK_INT)) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    // `connectedDevice` is not budgeted by Android 15 the way `dataSync` is, so
    // neither callback is expected to fire today. They are overridden because the
    // cost is nothing and the failure mode is a process kill
    // (ForegroundServiceDidNotStopInTime) rather than an exception we could catch —
    // and because the platform has twice widened which types it times out.
    //
    // 🔴 The two forms are NOT interchangeable: onTimeout(startId) is API 34 and the
    // platform only calls it for a `shortService`; onTimeout(startId, fgsType) is
    // API 35 and is the one every other type gets. MessageSyncService shipped with
    // only the first overridden and the crash came back.
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
                getString(com.oshi.messenger.R.string.channel_mesh_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(com.oshi.messenger.R.string.channel_mesh_desc)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(com.oshi.messenger.R.string.channel_mesh_status_title))
            .setContentText(getString(com.oshi.messenger.R.string.channel_mesh_status_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
