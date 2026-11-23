package com.oshi.messenger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Receiver to restart OSHI services after device boot
 * Ensures mesh network and message sync resume automatically
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Mesh network service: foregroundServiceType="connectedDevice", which Android 15
        // still allows from BOOT_COMPLETED.
        //
        // (N22) Not if the user turned the mesh off. This receiver was the one
        // starter of the service that did not ask: switching mesh off stopped
        // the service and its "Réseau mesh Oshi actif" notification, and the
        // next reboot brought both straight back — which is the complaint,
        // only now with a switch that looked like it had worked.
        // __FGS_DID_NOT_START_2026_11_10__ also needs a Bluetooth permission on
        // Android 14+, or startForeground is refused and the process is killed.
        if (MeshNetworkService.canGoForeground(context)) {
            startSafely(context, Intent(context, MeshNetworkService::class.java))
        }

        // __BOOT_FGS_ANDROID15_2026_09_24__ MessageSyncService is foregroundServiceType=
        // "dataSync". Android 15 (API 35) FORBIDS starting a dataSync foreground service from
        // a BOOT_COMPLETED receiver (ForegroundServiceStartNotAllowedException — flagged by
        // Play Console on 1.6.29). Messages still arrive through FCM push, and sync resumes
        // as soon as the app is opened, so on 35+ we simply don't start it here.
        if (Build.VERSION.SDK_INT < 35 && BackgroundPresence.shouldRun(context)) {
            startSafely(context, Intent(context, MessageSyncService::class.java))
        }
    }

    /** Never let a refused service start crash the app at boot. */
    private fun startSafely(context: Context, serviceIntent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.w("BootReceiver", "could not start ${serviceIntent.component?.shortClassName} at boot: ${e.javaClass.simpleName}")
        }
    }
}
