package com.oshi.messenger.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * __BACKGROUND_PRESENCE_2026_11_10__ Whether OSHI keeps a foreground service
 * running after the user leaves the app.
 *
 * User report (10/11): "the OSHI logo stays in my status bar even when I'm not in
 * the app". That logo is the notification of [MessageSyncService] and
 * [MeshNetworkService]. Android requires that notification for as long as a
 * foreground service runs, and raises a MIN channel to LOW (checked on an API 36
 * emulator: importance=2), so the icon cannot be hidden while they run. The only
 * real fix is to not run them when nobody needs them.
 *
 * Who needs them:
 *  - **Phones without Google Play services** (Huawei, de-Googled): no FCM push,
 *    so the background poll kept alive by [MessageSyncService] is the ONLY way a
 *    message or a call reaches them. They keep the service, always.
 *  - **Users who want Nearby (mesh) reachable while the app is closed** — the BLE
 *    read an iPhone makes is answered only by a live process. Opt-in setting.
 *
 * Everyone else gets messages and calls through FCM push, which is how iOS has
 * always worked (it declares no background modes at all).
 */
object BackgroundPresence {
    private const val TAG = "BackgroundPresence"
    private const val FILE = "oshi_background"
    private const val KEY_KEEP_RUNNING = "keep_running_in_background"

    /** Pure rule, unit-tested. */
    fun shouldRun(userOptIn: Boolean, pushAvailable: Boolean): Boolean =
        userOptIn || !pushAvailable

    fun userOptIn(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_RUNNING, false)

    @Volatile private var pushCache: Boolean? = null

    /** Google Play services present and usable ⇒ FCM can wake us. */
    fun pushAvailable(context: Context): Boolean {
        pushCache?.let { return it }
        val ok = try {
            com.google.android.gms.common.GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) ==
                com.google.android.gms.common.ConnectionResult.SUCCESS
        } catch (e: Throwable) {
            Log.w(TAG, "Play services check failed (${e.javaClass.simpleName}); assuming no push")
            false
        }
        pushCache = ok
        return ok
    }

    /** True when the always-on foreground services (and their status-bar icon) are wanted. */
    fun shouldRun(context: Context): Boolean = shouldRun(userOptIn(context), pushAvailable(context))

    fun setUserOptIn(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_KEEP_RUNNING, value).apply()
        if (shouldRun(context)) {
            startSync(context)
            MeshNetworkService.hold(context, appIsForeground = true)
        } else {
            stopAll(context)
        }
    }

    /** Start [MessageSyncService] if wanted. Never throws. */
    fun startSync(context: Context) {
        if (!shouldRun(context)) return
        try {
            val intent = Intent(context, MessageSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sync service start refused (${e.javaClass.simpleName}); FCM still delivers")
        }
    }

    /** Stop both services: their notification (and icon) goes with them. Never throws. */
    fun stopAll(context: Context) {
        runCatching { context.stopService(Intent(context, MessageSyncService::class.java)) }
        MeshNetworkService.release(context)
    }

    /** The user left the app. */
    fun onBackground(context: Context) {
        if (!shouldRun(context)) {
            Log.i(TAG, "left the app; push available and no opt-in — stopping background services")
            stopAll(context)
        }
    }
}
