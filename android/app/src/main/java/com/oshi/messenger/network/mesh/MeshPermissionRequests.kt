package com.oshi.messenger.network.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * __MESH_PERMANENT_DENIAL_2026_10_12__
 *
 * The Android side of [MeshPermissionPolicy]: which permissions we ask for, and
 * the one fact Android will not tell us — whether we have ever asked.
 *
 * It lives in one object because there were TWO requesters with TWO different
 * permission sets, and they interfered:
 *
 *  • `MainActivity.MeshPermissionGate`, on entering the Nearby route, eagerly
 *    asked for BLUETOOTH_SCAN / _ADVERTISE / _CONNECT **plus**
 *    ACCESS_FINE_LOCATION **plus** NEARBY_WIFI_DEVICES — five dialogs, three of
 *    which the BLE lanes do not need on API 31+ — and then ignored the result
 *    entirely ("mesh works best-effort").
 *  • `PeersScreen`'s permission card asked for the three Bluetooth ones only.
 *
 * A user who waved away the gate's interrogation had already spent their
 * refusals by the time the card appeared, and the card could not tell, because
 * it tracked denials in a `remember { mutableStateOf(false) }` that reset the
 * moment they left the screen. Reported on Google Play 12/10/2026 (Honor 90
 * Lite, Galaxy Tab A9, v1.6.36): *"the Bluetooth permission feature… nothing
 * happens"*. It did nothing because `requestPermissions` on a permanently
 * denied permission returns with no dialog.
 *
 * One set, asked from either place, and every ask recorded.
 */
object MeshPermissionRequests {

    private const val PREFS = "oshi_mesh_permissions"
    private const val ASKED_KEY = "ever_asked"

    /**
     * The runtime permissions the BLE lanes need and do not yet hold, in ask order.
     *
     * Sourced from [MeshPermissionPolicy] so the set cannot drift from the one
     * the transport checks. Deliberately NOT [MeshPermissionPolicy.allRequestable]:
     * that adds the NEARBY lane's location and Wi-Fi permissions, which
     * Android-to-iOS mesh never uses, and asking for them is what produced the
     * dialog storm above.
     */
    fun missingForBleLanes(context: Context): List<String> {
        val sdk = Build.VERSION.SDK_INT
        val wanted = (MeshPermissionPolicy.permissionsFor(MeshPermissionPolicy.Lane.BLE_ADVERTISE, sdk) +
            MeshPermissionPolicy.permissionsFor(MeshPermissionPolicy.Lane.BLE_SCAN, sdk))
            .distinct()
            // The legacy pair is install-time below API 31; handing it to
            // requestPermissions produces a dialog that cannot be granted.
            .filter { it != MeshPermissionPolicy.BLUETOOTH_LEGACY && it != MeshPermissionPolicy.BLUETOOTH_ADMIN_LEGACY }
        return wanted.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * __WIFI_AWARE_2026_11_25__ What Wi-Fi Aware adds to a Bluetooth request that
     * is being made anyway: `NEARBY_WIFI_DEVICES`, Android 13+ only, and only when
     * the phone has Wi-Fi Aware, the mesh and the Aware switch are on, and it is
     * not already held.
     *
     * On Android 13+ it belongs to the same "Nearby devices" permission group as
     * BLUETOOTH_SCAN / _ADVERTISE / _CONNECT, so it rides in the SAME dialog the
     * user is already being shown, and a phone that already holds that group
     * gets it without a dialog. Below 13 Aware needs location, which is a
     * different dialog — so nothing is added there; Aware then simply runs if
     * location was granted for another feature, and stays off otherwise.
     *
     * Never part of [missingForBleLanes]: the Peers card must not start nagging
     * about a permission the iOS lane does not use.
     */
    fun wifiAwareRideAlong(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < MeshPermissionPolicy.SDK_TIRAMISU) return emptyList()
        if (!com.oshi.messenger.network.mesh.wifiaware.WifiAwarePrefs.isSupported(context)) return emptyList()
        if (!com.oshi.messenger.network.mesh.wifiaware.WifiAwarePrefs.isEnabled(context)) return emptyList()
        if (!MeshPrefs.isEnabled(context)) return emptyList()
        return MeshPermissionPolicy.wifiAwarePermissions(Build.VERSION.SDK_INT).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * The Bluetooth set is already held, so the "Nearby devices" group is granted:
     * asking for [wifiAwareRideAlong] now is answered by the system without a
     * dialog. Asked at most once ever ([everAsked]), so even a system that did
     * show one would show it a single time.
     */
    fun silentWifiAwareAsk(context: Context): List<String> {
        if (missingForBleLanes(context).isNotEmpty()) return emptyList()
        val asked = everAsked(context)
        return wifiAwareRideAlong(context).filter { it !in asked }
    }

    /** Assert the literals in [MeshPermissionPolicy] really are the platform's. */
    internal fun platformNamesAgree(): Boolean =
        MeshPermissionPolicy.BLUETOOTH_SCAN == Manifest.permission.BLUETOOTH_SCAN &&
            MeshPermissionPolicy.BLUETOOTH_CONNECT == Manifest.permission.BLUETOOTH_CONNECT &&
            MeshPermissionPolicy.BLUETOOTH_ADVERTISE == Manifest.permission.BLUETOOTH_ADVERTISE &&
            MeshPermissionPolicy.ACCESS_FINE_LOCATION == Manifest.permission.ACCESS_FINE_LOCATION

    /** Permissions we have ever launched a request for. Survives a restart. */
    fun everAsked(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(ASKED_KEY, emptySet()) ?: emptySet()

    /**
     * Record an ask. MUST be called before launching a request, from EVERY
     * requester — a denial the app does not know it caused is a denial it will
     * keep offering a dead button for.
     */
    fun recordAsked(context: Context, permissions: Collection<String>) {
        if (permissions.isEmpty()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(ASKED_KEY, everAsked(context) + permissions)
            .apply()
    }
}
