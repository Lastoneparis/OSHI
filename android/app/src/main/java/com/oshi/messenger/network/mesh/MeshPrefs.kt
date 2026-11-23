package com.oshi.messenger.network.mesh

import android.content.Context

/**
 * (N22) "Pouvoir enlever la notification toujours présente 'Réseau mesh
 * Oshi actif'" — that notification belongs to [com.oshi.messenger.service.MeshNetworkService],
 * a foreground service, and Android will not let a foreground service run
 * without one (true since Android 8 — see `ForegroundServicePolicy`). There
 * is no way to hide it while the mesh keeps running underneath; the only
 * honest fix is a real switch to turn the mesh itself off, which is also
 * then the reason the notification disappears, rather than hiding a
 * notification for work that is still happening.
 *
 * Persisted so the choice survives a relaunch — without this, turning mesh
 * off would only last until the next cold start re-ran [MeshNetworkManager.startMesh].
 */
object MeshPrefs {
    private const val FILE = "mesh_prefs"
    private const val KEY_ENABLED = "oshi.mesh.enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Defaults to true: mesh is the app's core feature, on until a user turns it off. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }
}
