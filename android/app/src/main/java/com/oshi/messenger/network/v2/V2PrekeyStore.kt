package com.oshi.messenger.network.v2

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists our X3DH prekey PRIVATE material so that when a peer fetches our
 * published bundle and starts a session, we (the responder) can look the private
 * keys back up by keyId to recompute the shared secret. Public halves + signature
 * are published via [V2KeysClient]; only the privates live here.
 *
 * One-time prekeys are consumed (removed) on use — an OPK is single-use by design.
 */
@Singleton
class V2PrekeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class SignedPreKey(val keyId: String, val pair: OSHICryptoV2.X25519Pair)

    /**
     * X3DH prekey PRIVATES, in EncryptedSharedPreferences.
     *
     * This store held the signed-prekey private and every one-time-prekey private as
     * base64 in plain `shared_prefs` XML, unconditionally — not as a fallback. iOS
     * keeps the same material in the Keychain (`OSHI/V2SessionStore.swift:349-357`).
     * The plain file is migrated in on first use and then deleted.
     *
     * __LEAK_FIX_2026_09_25__ Keystore unavailable ⇒ NO new private key is ever written
     * to the plain XML any more (it used to become the store). Instead:
     *  * what the plain file already holds stays readable (an install that was running
     *    on it keeps answering the bundle it published — nothing is lost);
     *  * a NEW signed prekey lives in memory only and is never flagged as published
     *    on disk, so the next launch publishes a fresh bundle instead of advertising a
     *    key it no longer has;
     *  * no one-time prekeys are minted (X3DH without an OPK is valid on every
     *    platform: `oneTimePreKeyId` is optional), because an OPK that cannot outlive
     *    the process would only make a peer's first message fail after a restart.
     * The migration into the encrypted store now `commit()`s and clears the plain file
     * only once that commit has succeeded (two `apply()`s raced to disk).
     */
    private val plain = context.getSharedPreferences(PLAIN_NAME, Context.MODE_PRIVATE)

    private val secure: android.content.SharedPreferences? = run {
        val enc = try {
            // SECURITY (CSPN 2026-09): prefer StrongBox; inner fallback avoids the
            // outer catch (which would disable the encrypted store entirely).
            val masterKey = try {
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .setRequestStrongBoxBacked(true).build()
            } catch (e: Exception) {
                Log.w("V2PrekeyStore", "StrongBox unavailable for prekey master key, falling back: ${e.message}")
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            }
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("V2PrekeyStore", "EncryptedSharedPreferences unavailable — new prekeys kept in memory only: ${e.message}")
            null
        }
        if (enc != null && plain.all.isNotEmpty()) {
            val edit = enc.edit()
            for ((k, v) in plain.all) {
                when (v) {
                    is String -> edit.putString(k, v)
                    is Boolean -> edit.putBoolean(k, v)
                }
            }
            if (edit.commit()) {
                plain.edit().clear().commit()
                Log.d("V2PrekeyStore", "Migrated prekey privates from plain to encrypted storage")
            } else {
                Log.w("V2PrekeyStore", "prekey migration did not commit — plain copy kept for the next launch")
            }
        }
        enc
    }

    /** Process-only values when [secure] is unavailable. Never written to disk. */
    private val volatileValues = HashMap<String, Any>()

    private fun getString(key: String): String? =
        secure?.getString(key, null)
            ?: if (secure == null) (volatileValues[key] as? String) ?: plain.getString(key, null) else null

    /**
     * Store [value]. [newSecret]: the value carries private key material that was not
     * on disk before — never allowed into the plain file.
     */
    private fun putString(key: String, value: String, newSecret: Boolean) {
        val s = secure
        if (s != null) { s.edit().putString(key, value).apply(); return }
        volatileValues[key] = value
        // A SUBSET of what the plain file already holds (an OPK consumed) may be
        // written back: it removes material, it never adds any.
        if (!newSecret && plain.contains(key)) plain.edit().putString(key, value).apply()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    /** The current signed prekey, generating one on first use. */
    @Synchronized
    fun currentSignedPreKey(): SignedPreKey {
        val id = getString(SPK_ID)
        val priv = getString(SPK_PRIV)
        val pub = getString(SPK_PUB)
        if (id != null && priv != null && pub != null) {
            return SignedPreKey(id, OSHICryptoV2.X25519Pair(unb64(priv), unb64(pub)))
        }
        return rotateSignedPreKey()
    }

    @Synchronized
    fun rotateSignedPreKey(): SignedPreKey {
        val pair = OSHICryptoV2.generateX25519()
        val id = UUID.randomUUID().toString()
        val s = secure
        if (s != null) {
            s.edit()
                .putString(SPK_ID, id)
                .putString(SPK_PRIV, b64(pair.priv))
                .putString(SPK_PUB, b64(pair.pub))
                .apply()
        } else {
            // __LEAK_FIX_2026_09_25__ memory only; see the class note.
            volatileValues[SPK_ID] = id
            volatileValues[SPK_PRIV] = b64(pair.priv)
            volatileValues[SPK_PUB] = b64(pair.pub)
            volatileValues[PUBLISHED] = false
        }
        return SignedPreKey(id, pair)
    }

    /** Private X25519 for a signed-prekey id (responder X3DH), or null if rotated away. */
    @Synchronized
    fun signedPreKeyPriv(keyId: String): ByteArray? =
        if (getString(SPK_ID) == keyId) getString(SPK_PRIV)?.let { unb64(it) } else null

    /**
     * Generate `count` one-time prekeys, persist their privates, return their PUBLIC
     * halves.
     *
     * The map is CAPPED at [OPK_CAP] (iOS uses the same generous cap,
     * `OSHI/V2SessionStore.swift:396-404`). It used to only ever append: 20 more
     * privates per launch, forever, re-serialised in full on every write. The ids just
     * minted are always kept; what is dropped when the cap is hit is old generations
     * the server popped long ago.
     *
     * __LEAK_FIX_2026_09_25__ Without the encrypted store: none (see the class note).
     */
    @Synchronized
    fun generateOneTimePreKeys(count: Int): List<Pair<String, ByteArray>> {
        if (secure == null) {
            Log.w("V2PrekeyStore", "no encrypted store — publishing no one-time prekeys")
            return emptyList()
        }
        val obj = JSONObject(getString(OPK_MAP) ?: "{}")
        val out = ArrayList<Pair<String, ByteArray>>(count)
        repeat(count) {
            val pair = OSHICryptoV2.generateX25519()
            val id = UUID.randomUUID().toString()
            obj.put(id, b64(pair.priv))
            out.add(id to pair.pub)
        }
        val fresh = out.map { it.first }.toSet()
        if (obj.length() > OPK_CAP) {
            val droppable = obj.keys().asSequence().filterNot { it in fresh }.toMutableList()
            var excess = obj.length() - OPK_CAP
            while (excess > 0 && droppable.isNotEmpty()) {
                obj.remove(droppable.removeAt(0)); excess--
            }
        }
        putString(OPK_MAP, obj.toString(), newSecret = true)
        return out
    }

    /** True once a bundle has been published from this install (drives the refill, not a full republish). */
    @Synchronized
    fun hasPublished(): Boolean {
        secure?.let { return it.getBoolean(PUBLISHED, false) }
        (volatileValues[PUBLISHED] as? Boolean)?.let { return it }
        return plain.getBoolean(PUBLISHED, false)
    }

    @Synchronized
    fun markPublished() {
        val s = secure
        when {
            s != null -> s.edit().putBoolean(PUBLISHED, true).apply()
            // A memory-only SPK must not be remembered as published across a restart.
            volatileValues.containsKey(SPK_PRIV) -> volatileValues[PUBLISHED] = true
            else -> plain.edit().putBoolean(PUBLISHED, true).apply()   // a flag, not a key
        }
    }

    /** Peek the private X25519 for a one-time prekey WITHOUT consuming it (OPK-burn
     *  discipline: only [consumeOneTimePreKeyPriv] after the first message verifies). */
    @Synchronized
    fun oneTimePreKeyPrivPeek(keyId: String): ByteArray? {
        val obj = JSONObject(getString(OPK_MAP) ?: "{}")
        return if (obj.has(keyId)) unb64(obj.getString(keyId)) else null
    }

    /** Consume (remove + return) the private X25519 for a one-time prekey id. */
    @Synchronized
    fun consumeOneTimePreKeyPriv(keyId: String): ByteArray? {
        val obj = JSONObject(getString(OPK_MAP) ?: "{}")
        if (!obj.has(keyId)) return null
        val priv = unb64(obj.getString(keyId))
        obj.remove(keyId)
        putString(OPK_MAP, obj.toString(), newSecret = false)
        return priv
    }

    @Synchronized
    fun oneTimePreKeyCount(): Int = JSONObject(getString(OPK_MAP) ?: "{}").length()

    /** Wipe all prekey material (called on account deletion) — BOTH stores. */
    @Synchronized
    fun clear() {
        secure?.edit()?.clear()?.apply()
        volatileValues.clear()
        plain.edit().clear().apply()
    }

    // Helper so V2KeysClient can list current OPK ids without re-parsing here.
    @Synchronized
    fun oneTimePreKeyIds(): List<String> {
        val obj = JSONObject(getString(OPK_MAP) ?: "{}")
        return obj.keys().asSequence().toList()
    }

    private companion object {
        const val ENCRYPTED_NAME = "v2_prekeys_encrypted"
        const val PLAIN_NAME = "v2_prekeys"
        const val SPK_ID = "spk_id"
        const val SPK_PRIV = "spk_priv"
        const val SPK_PUB = "spk_pub"
        const val OPK_MAP = "opk_map"
        const val PUBLISHED = "published_v1"
        const val OPK_CAP = 200
    }
}
