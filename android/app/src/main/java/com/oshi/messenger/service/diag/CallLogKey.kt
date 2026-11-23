package com.oshi.messenger.service.diag

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * __CALL_LOG_AT_REST_2026_09_23__ The call log key: 32 random bytes wrapped by a
 * NON-EXPORTABLE Android Keystore AES-GCM key, stored as `iv ‖ ct ‖ tag` in its own
 * private prefs file (the whole sandbox is excluded from backup and device transfer).
 *
 * Wrapped rather than a bare Keystore key for the reason `AtRestKey` gives: every GCM
 * operation on a Keystore key is a binder round trip, and an export of an hour-long
 * call decrypts thousands of records. One unwrap per process, then Conscrypt.
 *
 * Its own key rather than `AtRestKey`'s: a throwaway diagnostic must not share a key
 * with the message store (same rule as iOS `CallLogKey`).
 */
object CallLogKey {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "oshi_call_log_wrap_v1"
    const val PREFS_NAME = "oshi_call_log_key"
    private const val PREF_WRAPPED = "wrapped_b64"

    @Volatile private var cached: ByteArray? = null

    @Synchronized
    fun key(context: Context): ByteArray {
        cached?.let { return it }
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wrapKey = ks.getKey(ALIAS, null) as? SecretKey
        val wrapped = prefs.getString(PREF_WRAPPED, null)?.let { Base64.getDecoder().decode(it) }
        if (wrapKey != null && wrapped != null && wrapped.size > 12) {
            try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, wrapKey, GCMParameterSpec(128, wrapped, 0, 12))
                val raw = c.doFinal(wrapped, 12, wrapped.size - 12)
                if (raw.size == 32) { cached = raw; return raw }
            } catch (e: AEADBadTagException) {
                // Wrapped blob no longer matches the Keystore key: lost, start over below.
            } catch (e: KeyPermanentlyInvalidatedException) {
                ks.deleteEntry(ALIAS)
            }
            // Any other exception propagates: a transient Keystore failure must not
            // orphan every sealed log by minting a new key. The logger retries later.
        }
        // Only a MISSING (or provably lost) key is replaced; old logs then fail to open
        // and the logger deletes them silently.
        val wk = (ks.getKey(ALIAS, null) as? SecretKey) ?: generateWrapKey()
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wk)   // Keystore picks the IV (randomized encryption)
        val blob = c.iv + c.doFinal(fresh)
        check(c.iv.size == 12) { "unexpected GCM IV size" }
        check(prefs.edit().putString(PREF_WRAPPED, Base64.getEncoder().encodeToString(blob)).commit()) {
            "failed to persist the call log key"
        }
        cached = fresh
        return fresh
    }

    private fun generateWrapKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
