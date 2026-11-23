package com.oshi.messenger.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.util.Base64

/**
 * Provisions and persists the SQLCipher passphrase for the Room database
 * (`oshi_database`), keyed from a Keystore-sealed master key.
 *
 * CSPN data-at-rest (2026-09): the passphrase is a random 256-bit value, held
 * only inside a KeyStore-wrapped EncryptedSharedPreferences file
 * (`oshi_db_key`). It is NEVER written to plaintext prefs and the store is
 * excluded from Auto Backup / device transfer (see backup_rules.xml +
 * data_extraction_rules.xml, and allowBackup=false on the application).
 *
 * The passphrase is a printable Base64 string so it can be handed to
 * SQLCipher two ways that MUST derive the same key:
 *   • Room open path — `SupportOpenHelperFactory(passphrase.encodeToByteArray())`
 *   • one-time migration — `ATTACH DATABASE ? AS encrypted KEY ?` (bound as text)
 * Both feed the identical UTF-8 bytes through SQLCipher's PBKDF2, so the key is
 * the same regardless of path.
 *
 * FAIL SAFE, not fail closed: when the KeyStore refuses (returns null), the
 * caller keeps the database UNENCRYPTED rather than losing access to it. Losing
 * the user's entire message history is a worse outcome than a plaintext DB on a
 * device whose KeyStore is broken; the feature flag (DatabaseEncryption.ENABLED)
 * governs whether we attempt encryption at all.
 */
object DatabasePassphrase {
    private const val TAG = "DatabasePassphrase"

    /** Dedicated EncryptedSharedPreferences file — excluded from backup. */
    const val PREFS_NAME = "oshi_db_key"
    private const val KEY_PASSPHRASE = "db_passphrase_b64"

    /** 256-bit passphrase. */
    private const val PASSPHRASE_BYTES = 32

    /**
     * Pure, Android-free passphrase generator (unit-testable on the JVM).
     * Returns a Base64 string of [PASSPHRASE_BYTES] cryptographically-random
     * bytes. Standard Base64 alphabet (`A-Za-z0-9+/=`) contains no single quote,
     * so the value embeds safely wherever it is bound or quoted.
     */
    fun generatePassphrase(): String {
        val raw = ByteArray(PASSPHRASE_BYTES)
        SecureRandom().nextBytes(raw)
        return Base64.getEncoder().encodeToString(raw)
    }

    /**
     * Returns the persisted passphrase, generating and storing one on first
     * call. Returns null only when the Keystore-backed store is unavailable —
     * in which case the caller MUST fall back to a plaintext database.
     */
    fun getOrCreate(context: Context): String? {
        val prefs = securePrefsWithRetry(context) ?: return null
        prefs.getString(KEY_PASSPHRASE, null)?.let { return it }
        return synchronized(this) {
            // Re-check under lock: another thread may have provisioned it.
            prefs.getString(KEY_PASSPHRASE, null) ?: run {
                val pass = generatePassphrase()
                // commit() (synchronous): the DB open that follows depends on it
                // being durably written; a lost async write would strand the DB.
                val ok = prefs.edit().putString(KEY_PASSPHRASE, pass).commit()
                if (!ok) {
                    Log.e(TAG, "Failed to persist DB passphrase")
                    return@run null
                }
                pass
            }
        }
    }

    /**
     * 🔴 __DB_KEYSTORE_HICCUP_2026_11_11__ The Keystore can refuse for a moment at
     * startup (StrongBox busy while several encrypted stores open at once, a
     * device just unlocked, a vendor Keystore restarting after an update). One
     * refusal used to be final for the whole launch: [getOrCreate] returned null,
     * Room opened the ENCRYPTED file without a key, Android called it corrupt and
     * DELETED it — every contact and message gone, reproduced on the Play build of
     * 1.6.93 (customer report 11/11: "all my contacts disappeared, and the ones I
     * add again do not appear"). A few short retries clear a transient refusal;
     * [DatabaseEncryption] handles one that persists without touching the data.
     */
    private fun securePrefsWithRetry(context: Context): SharedPreferences? {
        val backoffMs = longArrayOf(0L, 150L, 400L, 1000L)
        for ((attempt, wait) in backoffMs.withIndex()) {
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            securePrefs(context)?.let {
                if (attempt > 0) Log.w(TAG, "DB passphrase store opened on attempt ${attempt + 1}")
                return it
            }
        }
        return null
    }

    private fun securePrefs(context: Context): SharedPreferences? = try {
        val masterKey = try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(true)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "StrongBox unavailable for DB passphrase master key, falling back: ${e.message}")
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences unavailable — DB will stay plaintext: ${e.message}")
        null
    }
}
