package com.oshi.messenger.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * StaleIdentityStore — local set of public keys the user has decided are no
 * longer valid because the peer rotated identity (regenerated keys).
 *
 * Stale ≠ blocked.
 *   - Blocked = "I don't want this person at all" (Contact.isBlocked in DB)
 *   - Stale   = "this key is no longer this person" (this store)
 *
 * The drop semantics are similar (silently reject inbound), but stale is
 * surfaced separately in UI and is auto-populated on contact rotation
 * (QR re-scan with same alias, KEY_ROTATION group control).
 *
 * Consumed at every inbound boundary:
 *   - EnhancedCallManager.handleIncomingCall  → drop CALL_REQUEST silently
 *   - MessageRepository.handleCrossPlatformMessage → drop direct message
 *   - GroupManager.handleIncomingGroupMessage → drop group message
 *
 * Persisted in SharedPreferences (`stale_identities_v1`) as a JSON array of
 * { "k": <normalized base64 key>, "n": <displayName?>, "t": <epochMs>, "r": <reason>, "rb": <replacedBy?> }
 *
 * Cross-platform key normalization MUST mirror iOS StaleIdentityManager and
 * Android BlockedContactsManager-equivalent: trim, fold base64url (-/_) to
 * standard base64 (+,/), strip `=` padding. Without it, a stale entry
 * stored as `eAbM…cyc=` won't match an inbound key serialized as `eAbM…cyc`
 * (or vice-versa across iOS↔Android transports) and the call/message will
 * slip through.
 */
class StaleIdentityStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory mirror for hot-path checks (called per inbound packet).
    // synchronizedSet because reads happen on call/messaging threads and
    // mutations from UI/main thread.
    private val staleSet: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    init {
        loadIntoMemory()
    }

    companion object {
        private const val TAG = "StaleIdentityStore"
        private const val PREFS_NAME = "stale_identities_v1"
        private const val KEY_ENTRIES = "entries"

        const val REASON_MANUAL = "manual"
        const val REASON_QR_REPLACEMENT = "qr_replacement"
        const val REASON_GROUP_KEY_ROTATION = "group_key_rotation"

        @Volatile private var INSTANCE: StaleIdentityStore? = null

        fun get(context: Context): StaleIdentityStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StaleIdentityStore(context).also { INSTANCE = it }
            }
        }

        /**
         * Cross-platform key normalization. MUST stay in sync with iOS
         * StaleIdentityManager.normalizeKey and BlockedContactsManager.
         *
         * Folds base64url (-,_) → standard base64 (+,/), trims whitespace,
         * strips `=` padding. Returns "" for null/blank input so callers
         * can short-circuit on empty.
         */
        fun normalizeBase64Key(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw.trim()
                .replace('-', '+')
                .replace('_', '/')
                .trimEnd('=')
        }
    }

    // ─── Public API ────────────────────────────────────────────────────────

    /** True when this key has been marked as a stale (rotated) identity. */
    fun isStale(publicKey: String?): Boolean {
        val k = normalizeBase64Key(publicKey)
        if (k.isEmpty()) return false
        return staleSet.contains(k)
    }

    /**
     * Mark a key as stale. Idempotent — re-marking is a no-op (preserves
     * original markedAt timestamp). `replacedBy` is optional metadata for
     * the auto-rotation cases (QR re-scan, KEY_ROTATION control msg).
     */
    fun markStale(
        publicKey: String,
        displayName: String? = null,
        reason: String = REASON_MANUAL,
        replacedBy: String? = null
    ) {
        val k = normalizeBase64Key(publicKey)
        if (k.isEmpty()) return

        synchronized(staleSet) {
            if (staleSet.contains(k)) {
                Log.d(TAG, "🪦 already stale: ${k.take(12)}…")
                return
            }
            staleSet.add(k)
        }

        // Append to persisted JSON array
        val arr = readArray()
        val obj = JSONObject().apply {
            put("k", k)
            put("n", displayName ?: "")
            put("t", System.currentTimeMillis())
            put("r", reason)
            put("rb", normalizeBase64Key(replacedBy))
        }
        arr.put(obj)
        writeArray(arr)

        Log.w(TAG, "🪦 marked stale ($reason): ${displayName ?: k.take(12)}")
    }

    /** Remove a key from the stale set. */
    fun unmarkStale(publicKey: String) {
        val k = normalizeBase64Key(publicKey)
        if (k.isEmpty()) return

        synchronized(staleSet) {
            if (!staleSet.remove(k)) return
        }

        // Rewrite array sans k
        val src = readArray()
        val dst = JSONArray()
        for (i in 0 until src.length()) {
            val o = src.optJSONObject(i) ?: continue
            if (o.optString("k") == k) continue
            dst.put(o)
        }
        writeArray(dst)

        Log.d(TAG, "🪦 unmarked stale: ${k.take(12)}…")
    }

    /** All stale entries (for a future settings/admin UI). */
    fun getAll(): List<Entry> {
        val arr = readArray()
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Entry(
                    publicKey = o.optString("k"),
                    displayName = o.optString("n").takeIf { it.isNotEmpty() },
                    markedAtMs = o.optLong("t", 0L),
                    reason = o.optString("r", REASON_MANUAL),
                    replacedByPublicKey = o.optString("rb").takeIf { it.isNotEmpty() }
                )
            )
        }
        return out.sortedByDescending { it.markedAtMs }
    }

    // ─── Internals ─────────────────────────────────────────────────────────

    private fun loadIntoMemory() {
        val arr = readArray()
        synchronized(staleSet) {
            staleSet.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val k = o.optString("k")
                if (k.isNotEmpty()) staleSet.add(k)
            }
        }
        Log.d(TAG, "loaded ${staleSet.size} stale identities")
    }

    private fun readArray(): JSONArray {
        val s = prefs.getString(KEY_ENTRIES, "[]") ?: "[]"
        return try {
            JSONArray(s)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun writeArray(arr: JSONArray) {
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    data class Entry(
        val publicKey: String,
        val displayName: String?,
        val markedAtMs: Long,
        val reason: String,
        val replacedByPublicKey: String?
    )
}
