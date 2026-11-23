package com.oshi.messenger.network.web

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.util.Base64

/**
 * __WEBSESSION_E2E_2026_09_23__ The per-session OSHI Web v2 keys (`k_b2p`, `k_p2b`) and the SAS,
 * keyed by `sessionId`, in EncryptedSharedPreferences under a Keystore master key (CLIENT_SPEC §6
 * Android item 2). Nothing here is ever sent anywhere; losing it simply means re-pairing.
 *
 * FAIL CLOSED: if the Keystore refuses, nothing is persisted (keys live for this process only), so a
 * v2 session never degrades into keys on disk in the clear.
 */
class WebE2EKeyStore(private val context: Context) {

    private val secure: SharedPreferences? by lazy {
        try {
            EncryptedSharedPreferences.create(
                context, PREFS,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.w(TAG, "web e2e key store unavailable (${e.javaClass.simpleName}) — keys kept in memory only")
            runCatching { context.deleteSharedPreferences(PREFS) }
            null
        }
    }
    private val memory = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun raw(sid: String): String? = memory[sid] ?: secure?.getString(sid, null)?.also { memory[sid] = it }

    @Synchronized
    fun put(sid: String, b2p: ByteArray, p2b: ByteArray, sas: String) {
        val v = JSONObject().put("b2p", Base64.getEncoder().encodeToString(b2p))
            .put("p2b", Base64.getEncoder().encodeToString(p2b)).put("sas", sas)
            .put("at", System.currentTimeMillis()).toString()
        memory[sid] = v
        secure?.edit()?.putString(sid, v)?.apply()
    }

    /** (k_b2p, k_p2b) for [sid], or null. */
    fun keys(sid: String): Pair<ByteArray, ByteArray>? = raw(sid)?.let { s ->
        runCatching {
            val o = JSONObject(s)
            Base64.getDecoder().decode(o.getString("b2p")) to Base64.getDecoder().decode(o.getString("p2b"))
        }.getOrNull()
    }

    fun sas(sid: String): String? = raw(sid)?.let { runCatching { JSONObject(it).optString("sas").ifEmpty { null } }.getOrNull() }

    fun has(sid: String): Boolean = raw(sid) != null

    fun hasAny(): Boolean = memory.isNotEmpty() || (secure?.all?.isNotEmpty() == true)

    @Synchronized
    fun remove(sid: String) {
        memory.remove(sid)
        secure?.edit()?.remove(sid)?.apply()
    }

    /** Drop every session not in [sids] (the server ended them while we were away). */
    @Synchronized
    fun retainOnly(sids: Set<String>) {
        val all = (memory.keys + (secure?.all?.keys ?: emptySet())).toSet()
        for (sid in all) if (sid !in sids) remove(sid)
    }

    @Synchronized
    fun clear() {
        memory.clear()
        secure?.edit()?.clear()?.apply()
    }

    private companion object {
        const val PREFS = "oshi_web_e2e_keys"
        const val TAG = "WebE2EKeyStore"
    }
}
