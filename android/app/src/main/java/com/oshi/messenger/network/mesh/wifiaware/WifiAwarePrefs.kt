package com.oshi.messenger.network.mesh.wifiaware

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * __WIFI_AWARE_2026_11_25__ The "Wi-Fi Aware (fast nearby link)" switch.
 *
 * Default ON: the lane is automatic and purely additive (see [WifiAwareArbiter]),
 * so a user should never have to find a setting to get it. Stored next to the
 * mesh switch in `mesh_prefs` ([com.oshi.messenger.network.mesh.MeshPrefs]); the
 * mesh switch still wins — Aware never runs with the mesh off.
 */
object WifiAwarePrefs {
    private const val FILE = "mesh_prefs"
    private const val KEY_ENABLED = "oshi.mesh.wifi_aware.enabled"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** True when this phone has Wi-Fi Aware and an Android version the lane runs on. The switch is hidden otherwise. */
    fun isSupported(context: Context): Boolean =
        WifiAwareArbiter.isSupported(
            featurePresent = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE),
            sdkInt = Build.VERSION.SDK_INT,
        )
}
