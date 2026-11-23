package com.oshi.messenger.data.local.atrest

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.util.Base64

/**
 * The production [AtRestKeyProvider]: a random 256-bit key, wrapped by the Android
 * Keystore exactly like the SQLCipher passphrase ([com.oshi.messenger.data.local.DatabasePassphrase]),
 * in its own EncryptedSharedPreferences file `oshi_files_key` (covered by the
 * catch-all backup / device-transfer excludes).
 *
 * ## Why a wrapped key and not a non-exportable Keystore AES key
 *
 * A Keystore key would never leave secure hardware, but every AES-GCM operation on it
 * is a binder round trip into keystore2 (and, on StrongBox, into a secure element that
 * moves tens of KB/s). A 300 MB video is ~4,800 chunks at 64 KiB; hydrating a scrolled
 * conversation would be thousands of IPCs. The wrapped key runs on Conscrypt with the
 * CPU's AES instructions. The threat model is the same one the database already
 * accepted: the SQLCipher key is in process memory whenever the DB is open, and an
 * attacker who can read this process's memory reads the messages directly.
 * What this buys is the thing that matters for files: a copied `files/` directory
 * (forensic image, adb backup on a debuggable build, a rooted file browser that does
 * not run code in our process) is ciphertext without this device's Keystore.
 *
 * Loaded lazily on first use (EncryptedSharedPreferences costs ~50-200 ms the first
 * time); [install] only records the context, so it is safe as the first line of
 * `Application.onCreate`. A Keystore failure is remembered for [RETRY_AFTER_MS]
 * instead of being retried on every file access — not for the whole process any more
 * (__PLAINTEXT_LEFTOVERS_2026_09_24__): media accepted meanwhile is held in memory by
 * `MediaAttachmentStore` and sealed once the key comes back, so a transient Keystore
 * hiccup (device still locked after a direct-boot start, keystore2 restart) recovers.
 */
object AtRestKey : AtRestKeyProvider {
    private const val TAG = "AtRestKey"
    const val PREFS_NAME = "oshi_files_key"
    private const val KEY_MASTER = "files_master_key_b64"
    private const val KEY_BYTES = 32

    @Volatile private var appContext: Context? = null
    @Volatile private var cached: ByteArray? = null
    /** Back-off between two attempts after a Keystore failure. */
    const val RETRY_AFTER_MS = 60_000L

    @Volatile private var failedAtMs = 0L
    private val failed: Boolean
        get() = failedAtMs != 0L && System.currentTimeMillis() - failedAtMs < RETRY_AFTER_MS

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    override fun masterKey(): ByteArray? {
        cached?.let { return it }
        if (failed) return null
        val ctx = appContext ?: return null
        synchronized(this) {
            cached?.let { return it }
            if (failed) return null
            val key = try { loadOrCreate(ctx) } catch (e: Throwable) {
                Log.e(TAG, "at-rest key unavailable — retrying in ${RETRY_AFTER_MS / 1000}s: ${e.message}")
                null
            }
            if (key == null) failedAtMs = System.currentTimeMillis() else { cached = key; failedAtMs = 0L }
            return key
        }
    }

    private fun loadOrCreate(context: Context): ByteArray? {
        val prefs = securePrefs(context) ?: return null
        prefs.getString(KEY_MASTER, null)?.let { b64 ->
            val raw = Base64.getDecoder().decode(b64)
            if (raw.size == KEY_BYTES) return raw
            Log.e(TAG, "stored at-rest key has the wrong size — not replacing it")
            return null
        }
        val raw = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        // commit(): files sealed with this key must never outlive a lost async write.
        if (!prefs.edit().putString(KEY_MASTER, Base64.getEncoder().encodeToString(raw)).commit()) {
            Log.e(TAG, "failed to persist at-rest key")
            return null
        }
        return raw
    }

    private fun securePrefs(context: Context): SharedPreferences? {
        val masterKey = try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(true)
                .build()
        } catch (e: Exception) {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
