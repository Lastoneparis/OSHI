package com.oshi.messenger.network.mesh

import android.util.Log

/**
 * __MESH_DIAGNOSTICS_SURVIVE_RELEASE_2026_10_23__ The handful of mesh facts that
 * stay readable on a release build.
 *
 * ## Why this exists
 *
 * `proguard-rules.pro` deletes every `Log.v/d/i/w` from release, and it is right
 * to: those calls print decrypted message bodies, `plaintextContent`, and the
 * first twenty base64 characters of the ratchet ROOT KEY. Anything holding
 * READ_LOGS on the phone would read all of it.
 *
 * The cost of that rule showed up on 2026-10-23: a Galaxy A17 sitting fifty
 * centimetres from an iPhone was not advertising the mesh service, every
 * Bluetooth permission was granted, the foreground service was held — and there
 * was no way to tell whether `canAdvertise()` had refused, whether
 * `startAdvertising` had failed, or whether the mesh had started at all,
 * because the entire diagnostic trail was `Log.d`. Blind on the exact device
 * where the bug lives.
 *
 * ## What makes this safe to ship
 *
 * It carries STATE, never CONTENT. A transition, a failure code, a count. No
 * message text, no key material, no peer name, no address. The rule above stays
 * exactly as it is — this is not a hole in it, it is a separate, deliberately
 * narrow channel.
 *
 * It survives because `Log.println` is not in the `assumenosideeffects` list;
 * `v`, `d`, `i` and `w` are. That is a real constraint, not a trick: adding
 * `println` to that list would silence this too, which is why the rule file now
 * says so.
 *
 * ## The discipline
 *
 * Everything passed here must be safe to read over someone's shoulder. If a
 * line needs an address, a name or a key to be useful, it belongs in `Log.d`
 * and it stays out of release.
 */
object MeshDiag {

    private const val TAG = "OSHIMeshDiag"

    /** A state transition or an outcome worth seeing on a real phone. */
    fun log(message: String) {
        Log.println(Log.INFO, TAG, message)
    }

    /** Something refused us, with the platform's own code. */
    fun fail(what: String, code: Int? = null, reason: String? = null) {
        val parts = buildString {
            append(what)
            if (code != null) append(" code=").append(code)
            if (reason != null) append(" (").append(reason).append(')')
        }
        Log.println(Log.WARN, TAG, parts)
    }
}
