package com.oshi.messenger.network.encryption

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Where Android keeps the ratchet state — this client's seam, and nothing else.
 *
 * __RATCHET_IS_SHARED_SOURCE_2026_10_28__ These four functions used to be members of
 * [DoubleRatchet]. They were the ONLY reason that file imported `android.content.Context`
 * and `androidx.security.crypto`, and so the only reason `OSHI-Desktop/PARITY.md` row 0.16
 * recorded the legacy ratchet as unportable and budgeted a second implementation of it.
 *
 * They are extension functions with the SAME names and signatures as the members they
 * replace, in the same package, so every call site in the app resolves to them unchanged —
 * the move is invisible above this line. They are written only in terms of
 * [DoubleRatchet.exportSessionsJson], [DoubleRatchet.importSessionsJson] and
 * [DoubleRatchet.clearSessionsInMemory], which are pure.
 *
 * This is the split `build.gradle.kts` already describes for `CallRatingPolicy` (shared,
 * pure, must not drift) against `CallRatingManager` (Context + prefs + Log, each client's
 * own). The desktop's half is `com.oshi.desktop.store.RatchetSessionStore`.
 */

private const val TAG = "DoubleRatchetStore"
private const val SESSIONS_KEY = "sessions_v2"
private const val ENCRYPTED_STORE = "oshi_ratchet_encrypted"
private const val PLAIN_STORE = "oshi_ratchet"

/**
 * EncryptedSharedPreferences for ratchet session storage, or **null** when the Keystore
 * refuses.
 *
 * FAIL CLOSED on write. The serialized sessions are the root key, both chain keys, every
 * skipped message key and the ratchet private key; the old fallback wrote all of that to
 * plain `shared_prefs` XML on any transient Keystore hiccup, where Android Auto Backup and
 * any root/ADB path can read it. iOS refuses the same degradation — on
 * `KeychainError.locked` it returns without falling back or purging
 * (`OSHI/DoubleRatchet.swift:687-691`).
 *
 * Reads still consult the legacy plain store (see [loadSessions]): that data is already on
 * disk, and refusing to read it would only lose sessions.
 */
private fun securePrefs(context: Context): android.content.SharedPreferences? {
    return try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_STORE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences unavailable — ratchet state will NOT be persisted: ${e.message}")
        null
    }
}

/**
 * 🔥 Drop EVERY ratchet session — in memory and on disk.
 *
 * The escape hatch behind Settings → "Clear sessions": when two devices' ratchets desync,
 * nothing decrypts until both sides throw their state away and one of them sends a fresh
 * message. iOS: `DoubleRatchetSessionManager.resetAllSessions()`
 * (`OSHI/DoubleRatchet.swift:649-658`), driven by `MessageManager.clearRatchetSessions()`
 * (`OSHI/MessageManager.swift:1744-1785`).
 *
 * ⚠️ Deliberately does MORE than the iOS original, which has a bug worth not copying:
 * `resetAllSessions()` removes the *UserDefaults* copy of `storageKey`, but the sessions
 * are persisted to the **Keychain** under that same key (`swift:595,616`), and the three
 * Keychain items `clearRatchetSessions` deletes (`swift:1769-1773`) omit the `_v4` suffix
 * the live key actually uses (`swift:466`). So on iOS the blob survives and reloads on next
 * launch — which is why the log insists the user "MUST close app completely" and it still
 * doesn't stick. Here BOTH persisted copies go: the encrypted store AND the plain-prefs
 * migration copy that [loadSessions] falls back to. Miss either one and the next launch
 * resurrects exactly the desynced state the user was trying to clear.
 *
 * @return how many sessions were dropped.
 */
fun DoubleRatchet.resetAllSessions(context: Context): Int {
    val count = clearSessionsInMemory()
    runCatching { securePrefs(context)?.edit()?.remove(SESSIONS_KEY)?.apply() }
        .onFailure { Log.w(TAG, "could not clear encrypted ratchet store: ${it.message}") }
    runCatching {
        context.getSharedPreferences(PLAIN_STORE, Context.MODE_PRIVATE)
            .edit().remove(SESSIONS_KEY).apply()
    }.onFailure { Log.w(TAG, "could not clear plain ratchet store: ${it.message}") }
    Log.d(TAG, "🔥 Reset $count ratchet session(s) — memory + both persisted stores")
    return count
}

/**
 * Persist every session.
 *
 * A ConcurrentModificationException out of [DoubleRatchet.exportSessionsJson] is NOT
 * recoverable noise: it means the write did not happen while the in-memory ratchet had
 * already advanced, so the next process launch rewinds to a state whose message keys are
 * already on the wire. It is rethrown, as before.
 */
fun DoubleRatchet.saveSessions(context: Context) {
    try {
        val json = exportSessionsJson()
        val prefs = securePrefs(context) ?: return   // fail closed: never plaintext
        prefs.edit().putString(SESSIONS_KEY, json).apply()
        Log.d(TAG, "Saved ratchet sessions")
    } catch (e: java.util.ConcurrentModificationException) {
        Log.e(TAG, "Ratchet persist raced a mutation — state NOT saved", e)
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Failed to save sessions: ${e.message}")
    }
}

fun DoubleRatchet.loadSessions(context: Context) {
    try {
        // Try encrypted prefs first, then fall back to plain (migration)
        val secure = securePrefs(context)
        var jsonStr = secure?.getString(SESSIONS_KEY, null)

        if (jsonStr == null) {
            val plainPrefs = context.getSharedPreferences(PLAIN_STORE, Context.MODE_PRIVATE)
            jsonStr = plainPrefs.getString(SESSIONS_KEY, null)
            if (jsonStr != null && secure != null) {
                Log.d(TAG, "Migrating ratchet sessions from plain to encrypted storage")
                secure.edit().putString(SESSIONS_KEY, jsonStr).apply()
                plainPrefs.edit().remove(SESSIONS_KEY).apply()
            }
        }

        if (jsonStr == null) return
        val loaded = importSessionsJson(jsonStr)
        Log.d(TAG, "Loaded $loaded ratchet session(s)")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load sessions: ${e.message}")
    }
}
