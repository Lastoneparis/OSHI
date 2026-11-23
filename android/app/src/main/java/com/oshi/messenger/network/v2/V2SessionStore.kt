package com.oshi.messenger.network.v2

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists V2 Double-Ratchet sessions per peer, matching iOS V2SessionStore.
 * Session secrets live in EncryptedSharedPreferences (Keystore-wrapped) — losing
 * them on uninstall is fine, a session simply re-establishes via X3DH.
 *
 * SerializedRatchet = {dhsPriv,dhsPub,dhr,rk,cks,ckr,ns,nr,pn,skipped}
 * SessionRecord     = {peerUserKey, role, ratchet, peerIdentityKey?, peerSigningKey?,
 *                      pendingInitiatorHeader?}
 */
@Singleton
class V2SessionStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    enum class Role { INITIATOR, RESPONDER }

    data class Record(
        val peerUserKey: String,
        val role: Role,
        val ratchet: OSHIRatchetV2.State,
        val peerIdentityKey: ByteArray?,
        val peerSigningKey: ByteArray?,
        // Set on the initiator until the peer replies — the X3DH header to attach
        // to message #1 so the responder can establish. Cleared after first reply.
        var pendingInitiatorHeader: V2Session.X3DHHeader?,
        // __PER_DEVICE_MAILBOX_2026_09_23__ sessions are keyed by (peerUserKey, peerDeviceId?).
        // null = the legacy account session, stored under its old key, so nothing migrates.
        val peerDeviceId: String? = null,
    )

    /**
     * The encrypted store, or null when the Keystore refuses.
     *
     * FAIL CLOSED on write. A record here is the root key, both chain keys, the ratchet
     * private and every skipped message key for one peer; the previous fallback wrote
     * all of it to plain `v2_sessions_plain` XML, which Android Auto Backup and any
     * root/ADB path can read. iOS refuses the same degradation rather than downgrading
     * the storage (`OSHI/DoubleRatchet.swift:687-691`). Reads still consult the plain
     * store so a device that already degraded keeps its sessions, and they are migrated
     * up the moment the Keystore works again.
     */
    private val securePrefs: SharedPreferences? = try {
        // SECURITY (CSPN 2026-09): prefer a StrongBox-backed master key; the inner
        // try/catch keeps StrongBox-unavailable from falling through to the outer
        // (plaintext-disabling) handler — we still get a TEE-backed encrypted store.
        val masterKey = try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(true)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "StrongBox unavailable for V2 session master key, falling back: ${e.message}")
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }
        EncryptedSharedPreferences.create(
            context, "v2_sessions", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences unavailable — V2 sessions will NOT be persisted: ${e.message}")
        null
    }

    private val legacyPlain: SharedPreferences =
        context.getSharedPreferences("v2_sessions_plain", Context.MODE_PRIVATE)

    init {
        val secure = securePrefs
        if (secure != null && legacyPlain.all.isNotEmpty()) {
            val edit = secure.edit()
            for ((k, v) in legacyPlain.all) if (v is String) edit.putString(k, v)
            edit.apply()
            legacyPlain.edit().clear().apply()
            Log.d(TAG, "Migrated V2 sessions from plain to encrypted storage")
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
    private fun key(peer: String, peerDeviceId: String? = null) =
        "sess_" + com.oshi.messenger.network.v2.devicemailbox.DeviceRouting.sessionKey(peer, peerDeviceId)

    @Synchronized
    fun load(peerUserKey: String, peerDeviceId: String? = null): Record? {
        val s = securePrefs?.getString(key(peerUserKey, peerDeviceId), null)
            ?: legacyPlain.getString(key(peerUserKey, peerDeviceId), null)
            ?: return null
        return try { deserialize(JSONObject(s)) } catch (_: Exception) { null }
    }

    @Synchronized
    fun save(rec: Record) {
        val prefs = securePrefs
        if (prefs == null) {
            Log.e(TAG, "session for ${rec.peerUserKey.take(12)}… NOT persisted (no secure store)")
            return
        }
        prefs.edit().putString(key(rec.peerUserKey, rec.peerDeviceId), serialize(rec).toString()).apply()
    }

    @Synchronized
    fun has(peerUserKey: String, peerDeviceId: String? = null): Boolean =
        securePrefs?.contains(key(peerUserKey, peerDeviceId)) == true || legacyPlain.contains(key(peerUserKey, peerDeviceId))

    /**
     * Forget ONE session, so the next send re-runs X3DH and carries the header.
     * This is the ratchet-heal primitive (iOS `V2SessionStore.deleteSession`) — it
     * existed on iOS for months without a caller, and on Android not at all.
     */
    @Synchronized
    fun delete(peerUserKey: String, peerDeviceId: String? = null) {
        securePrefs?.edit()?.remove(key(peerUserKey, peerDeviceId))?.apply()
        legacyPlain.edit().remove(key(peerUserKey, peerDeviceId)).apply()
    }

    /** Wipe every session (account deletion) — both stores. */
    @Synchronized
    fun clear() {
        securePrefs?.edit()?.clear()?.apply()
        legacyPlain.edit().clear().apply()
    }

    // ---- serialization ------------------------------------------------------

    private fun serialize(rec: Record): JSONObject = JSONObject().apply {
        put("peerUserKey", rec.peerUserKey)
        put("role", rec.role.name)
        rec.peerDeviceId?.let { put("peerDeviceId", it) }
        put("ratchet", serializeRatchet(rec.ratchet))
        rec.peerIdentityKey?.let { put("peerIdentityKey", b64(it)) }
        rec.peerSigningKey?.let { put("peerSigningKey", b64(it)) }
        rec.pendingInitiatorHeader?.let {
            put("pendingInitiatorHeader", JSONObject().apply {
                put("identityKey", b64(it.identityKey))
                put("ephemeralKey", b64(it.ephemeralKey))
                put("signedPreKeyId", it.signedPreKeyId)
                it.oneTimePreKeyId?.let { id -> put("oneTimePreKeyId", id) }
            })
        }
    }

    private fun serializeRatchet(s: OSHIRatchetV2.State): JSONObject = JSONObject().apply {
        put("dhsPriv", b64(s.dhs.priv))
        put("dhsPub", b64(s.dhs.pub))
        s.dhr?.let { put("dhr", b64(it)) }
        put("rk", b64(s.rk))
        s.cks?.let { put("cks", b64(it)) }
        s.ckr?.let { put("ckr", b64(it)) }
        put("ns", s.ns); put("nr", s.nr); put("pn", s.pn)
        put("skipped", JSONObject().apply { s.skipped.forEach { (k, v) -> put(k, b64(v)) } })
    }

    private fun deserialize(o: JSONObject): Record {
        val ph = o.optJSONObject("pendingInitiatorHeader")?.let {
            V2Session.X3DHHeader(
                identityKey = unb64(it.getString("identityKey")),
                ephemeralKey = unb64(it.getString("ephemeralKey")),
                signedPreKeyId = it.optString("signedPreKeyId"),
                oneTimePreKeyId = if (it.has("oneTimePreKeyId")) it.optString("oneTimePreKeyId") else null,
            )
        }
        return Record(
            peerUserKey = o.getString("peerUserKey"),
            role = Role.valueOf(o.getString("role")),
            ratchet = deserializeRatchet(o.getJSONObject("ratchet")),
            peerIdentityKey = o.optString("peerIdentityKey", "").ifEmpty { null }?.let { unb64(it) },
            peerSigningKey = o.optString("peerSigningKey", "").ifEmpty { null }?.let { unb64(it) },
            pendingInitiatorHeader = ph,
            peerDeviceId = o.optString("peerDeviceId", "").ifEmpty { null },
        )
    }

    private fun deserializeRatchet(o: JSONObject): OSHIRatchetV2.State {
        val skipped = HashMap<String, ByteArray>()
        o.optJSONObject("skipped")?.let { sk -> sk.keys().forEach { skipped[it] = unb64(sk.getString(it)) } }
        return OSHIRatchetV2.State(
            dhs = OSHICryptoV2.X25519Pair(unb64(o.getString("dhsPriv")), unb64(o.getString("dhsPub"))),
            dhr = o.optString("dhr", "").ifEmpty { null }?.let { unb64(it) },
            rk = unb64(o.getString("rk")),
            cks = o.optString("cks", "").ifEmpty { null }?.let { unb64(it) },
            ckr = o.optString("ckr", "").ifEmpty { null }?.let { unb64(it) },
            ns = o.optLong("ns"), nr = o.optLong("nr"), pn = o.optLong("pn"),
            skipped = skipped,
        )
    }

    private companion object { const val TAG = "V2SessionStore" }
}
