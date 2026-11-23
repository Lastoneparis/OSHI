package com.oshi.messenger.network.v2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.oshi.messenger.R
import com.oshi.messenger.devsync.AndroidDeviceKeyProvider
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.v2.devicemailbox.DeviceApproval
import com.oshi.messenger.network.v2.devicemailbox.DeviceAuth
import com.oshi.messenger.network.v2.devicemailbox.DeviceHttpOutcome
import com.oshi.messenger.network.v2.devicemailbox.DeviceKeys
import com.oshi.messenger.network.v2.devicemailbox.DeviceListCache
import com.oshi.messenger.network.v2.devicemailbox.RegisteredDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * __PER_DEVICE_MAILBOX_2026_09_23__ The Android side of per-device mailboxes
 * (ServerPatches/per_device_mailbox/CLIENT_SPEC.md). Pure decisions live in
 * `devicemailbox/DeviceMailboxCore.kt`; this class owns the keys, the local state and the
 * registration lifecycle. [V2MessageRouter] asks it whether device mode is on and for the
 * credentials that sign device requests.
 *
 * GATE (§3.1). Everything is dormant unless `/v2/config.device_mailbox_enabled` is true (it is
 * false in production today). With the flag off nothing here touches the network and the router
 * keeps today's path exactly. When the flag turns off while running, the router stops sending
 * device headers and falls back to legacy polling; the registration state here is KEPT for when the
 * flag returns.
 *
 * KEYS. `dk` is the devsync device key (same Keystore-wrapped store as
 * [AndroidDeviceKeyProvider] — one device, one id everywhere). `dsk` is a NEW per-install Ed25519
 * seed, EncryptedSharedPreferences under a Keystore master key, never exported or backed up.
 *
 * DEVSYNC HOOKS (the devsync core in `network/v2/devsync/` is owned by another change and is not
 * edited here). Wire, where the engine is built (`devsync/DevSyncManager.startEngine`):
 * ```
 * engine.approvalExtras = { peerId, peerDk -> deviceMailbox.approvalExtras(peerId, peerDk) }
 * engine.onApprovalReceived = { peerId, body -> deviceMailbox.onApprovalReceived(peerId, body) }
 * ```
 * The approver must know the NEW device's `dsk` to sign `OSHI-DEVICE-APPROVE/1` (the string binds
 * it). Nothing in today's HELLO carries it: the new device must send it (proposed: optional HELLO
 * field `"dsk"`, value [myDskB64]), and the core hands it over via [notePeerDsk] before the user
 * taps Allow. Until then [approvalExtras] returns null and the new device gets `403
 * approval-required` (surfaced in [state]).
 */
@Singleton
class DeviceMailboxManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: V2Config,
    private val cryptoManager: CryptoManager,
    private val client: V2DeviceClient,
    private val prekeyStore: V2PrekeyStore,
) {
    data class State(
        val flagOn: Boolean = false,
        val deviceId: String? = null,
        val registered: Boolean = false,
        val holdsAccountBundle: Boolean? = null,
        val approvalRequired: Boolean = false,
        val removed: Boolean = false,
        val tooManyDevices: Boolean = false,
        val devices: List<RegisteredDevice> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val lifecycle = Mutex()
    private val deviceLists = DeviceListCache()

    // ------------------------------------------------------------------ keys

    @Volatile private var cachedKeys: DeviceKeys? = null

    /** This install's credentials, or null when the Keystore refuses (device mode then stays off). */
    fun keys(): DeviceKeys? {
        cachedKeys?.let { return it }
        return try {
            val dkPriv = AndroidDeviceKeyProvider(context).loadOrCreate()
            val dkPub = OSHICryptoV2.x25519PubFromPriv(dkPriv)
            dkPriv.fill(0)
            DeviceKeys(dkPub, loadOrCreateDskSeed()).also { cachedKeys = it }
        } catch (e: Exception) {
            Log.w(TAG, "device keys unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun securePrefs() = EncryptedSharedPreferences.create(
        context, KEY_PREFS,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun loadOrCreateDskSeed(): ByteArray {
        val p = try { securePrefs() } catch (e: Exception) {
            // Restored onto a new Keystore: the old seed is gone for good — a new dsk means a new
            // registration (which needs an approval), exactly the "ThisDeviceOnly" behaviour.
            context.deleteSharedPreferences(KEY_PREFS); securePrefs()
        }
        p.getString(DSK_SEED, null)?.let { s ->
            runCatching { java.util.Base64.getDecoder().decode(s) }.getOrNull()?.takeIf { it.size == 32 }?.let { return it }
        }
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        check(p.edit().putString(DSK_SEED, java.util.Base64.getEncoder().encodeToString(seed)).commit()) { "could not persist dsk" }
        return seed
    }

    fun myDeviceId(): String? = keys()?.deviceId

    /** Public `dsk` (standard base64), for the devsync HELLO so an approver can sign for us. */
    fun myDskB64(): String? = keys()?.dskB64

    private fun me(): String = DeviceAuth.normalizeIdentity(cryptoManager.getPublicKeyString())

    // ------------------------------------------------------------------ gate

    fun flagOn(): Boolean = config.deviceMailboxEnabledCached()

    private fun registeredId(): String? = prefs.getString(REGISTERED_ID, null)

    /** Device mode = flag on, this install registered under its CURRENT key, and not removed. */
    fun deviceModeActive(): Boolean {
        if (!flagOn()) return false
        val k = keys() ?: return false
        return registeredId() == k.deviceId && !prefs.getBoolean(REMOVED, false)
    }

    /**
     * Whether THIS device keeps publishing the ACCOUNT bundle for old senders (§3.2 "Account bundle
     * ownership"). Unknown (never checked) counts as true, which is today's behaviour.
     */
    fun holdsAccountBundle(): Boolean = !prefs.contains(HOLDS_ACCOUNT) || prefs.getBoolean(HOLDS_ACCOUNT, true)

    // ------------------------------------------------------------------ cursors (§3.3)

    /** Device-mode cursor, separate from the legacy `last_seq` (seq is global, `after` works as today). */
    fun deviceCursor(): Long = prefs.getLong(DEVICE_CURSOR, 0L)
    fun setDeviceCursor(v: Long) = prefs.edit().putLong(DEVICE_CURSOR, v).apply()

    // ------------------------------------------------------------------ server answers

    /**
     * React to a device-route outcome seen anywhere (poll, ack, publish, send). Returns true when the
     * caller must drop device mode for this call and use the legacy path.
     */
    fun handleOutcome(o: DeviceHttpOutcome): Boolean = when (o) {
        DeviceHttpOutcome.DeviceUnknown -> { onRemoved(); true }
        DeviceHttpOutcome.MailboxDisabled -> { config.noteDeviceMailboxDisabled(); true }
        else -> false
    }

    /** `410 device-unknown`: removed by another device, a reset, or 30 days silent (§3.3). */
    private fun onRemoved() {
        if (prefs.getBoolean(REMOVED, false)) return
        prefs.edit().putBoolean(REMOVED, true).remove(REGISTERED_ID).remove(DEVICE_PUBLISHED).apply()
        _state.value = _state.value.copy(registered = false, removed = true)
        Log.w(TAG, "this device was removed from the account mailbox")
        notifyUser(R.string.device_mailbox_removed_title, R.string.device_mailbox_removed_body, NOTIF_REMOVED)
    }

    // ------------------------------------------------------------------ lifecycle (§3.2)

    /**
     * Register (once per install), publish/top up this device's bundle, decide account-bundle
     * ownership and watch the device list for devices nobody here approved. Cheap and idempotent;
     * the router calls it from its bundle-maintenance step. No-op while the flag is off.
     */
    suspend fun maintain(): Boolean = lifecycle.withLock {
        val flag = flagOn()
        _state.value = _state.value.copy(flagOn = flag)
        if (!flag) return@withLock false
        val k = keys() ?: return@withLock false
        _state.value = _state.value.copy(deviceId = k.deviceId)

        val (listAnswer, devices) = client.listDevices()
        if (handleOutcome(listAnswer.outcome)) return@withLock false
        if (listAnswer.status !in 200..299) return@withLock false
        watchDevices(devices, k.deviceId)

        var registered = devices.any { it.deviceId == k.deviceId && it.dsk == k.dskB64 }
        if (!registered) {
            if (prefs.getBoolean(REMOVED, false) && pendingApproval() == null && devices.isNotEmpty()) {
                // Removed earlier: re-linking needs a fresh approval from another device.
                _state.value = _state.value.copy(removed = true, approvalRequired = true)
                return@withLock false
            }
            registered = register(k, devices)
            if (!registered) return@withLock false
        }
        prefs.edit().putString(REGISTERED_ID, k.deviceId).putBoolean(REMOVED, false).apply()
        _state.value = _state.value.copy(registered = true, removed = false, approvalRequired = false, tooManyDevices = false)

        decideAccountBundleOwnership()
        publishOrTopUp(k)
        true
    }

    private suspend fun register(k: DeviceKeys, devices: List<RegisteredDevice>): Boolean {
        val approval = if (devices.isEmpty()) null else pendingApproval()
        if (devices.isNotEmpty() && approval == null) {
            _state.value = _state.value.copy(approvalRequired = true)
            return false
        }
        val body = k.registerBody(me(), System.currentTimeMillis(), approval = approval, adoptBacklog = true, retireLegacy = false)
        val a = client.register(body)
        return when (val o = a.outcome) {
            DeviceHttpOutcome.Ok -> {
                prefs.edit().remove(PENDING_APPROVAL).apply()
                Log.i(TAG, "registered this device (${if (approval == null) "first device" else "approved by ${approval.by.take(8)}"})")
                true
            }
            DeviceHttpOutcome.ApprovalRequired -> { _state.value = _state.value.copy(approvalRequired = true); false }
            DeviceHttpOutcome.BadApproval -> {
                prefs.edit().remove(PENDING_APPROVAL).apply()
                _state.value = _state.value.copy(approvalRequired = true); false
            }
            DeviceHttpOutcome.TooManyDevices -> { _state.value = _state.value.copy(tooManyDevices = true); false }
            DeviceHttpOutcome.Conflict -> {
                // Should never happen (the id is a hash of our own dk). Do NOT silently rotate the devsync
                // key from here — that would unlink devsync too. Logged for diagnosis.
                Log.e(TAG, "device-conflict for our own deviceId"); false
            }
            else -> { handleOutcome(o); false }
        }
    }

    /** true = this device's SPK is the live account bundle, false = another device's, "" (none) → we take it. */
    private suspend fun decideAccountBundleOwnership() {
        val now = System.currentTimeMillis()
        if (prefs.contains(HOLDS_ACCOUNT) && now - prefs.getLong(HOLDS_CHECKED_AT, 0) < DAY_MS) {
            _state.value = _state.value.copy(holdsAccountBundle = prefs.getBoolean(HOLDS_ACCOUNT, true)); return
        }
        val live = client.liveAccountSpk(me()) ?: return
        val mine = java.util.Base64.getEncoder().encodeToString(prekeyStore.currentSignedPreKey().pair.pub)
        val holds = live.isEmpty() || live == mine
        prefs.edit().putBoolean(HOLDS_ACCOUNT, holds).putLong(HOLDS_CHECKED_AT, now).apply()
        _state.value = _state.value.copy(holdsAccountBundle = holds)
        Log.i(TAG, "account bundle holder: $holds")
    }

    private suspend fun publishOrTopUp(k: DeviceKeys) {
        val spk = prekeyStore.currentSignedPreKey()
        val published = prefs.getString(DEVICE_PUBLISHED, null) == "${k.deviceId}|${spk.keyId}"
        val want = if (!published) OPK_TARGET else {
            val left = client.deviceOneTimeCount(me(), k.deviceId) ?: return
            if (left >= OPK_LOW_WATER) return
            OPK_TARGET - left
        }
        val (a, _) = client.publishDeviceBundle(k, spk, prekeyStore.generateOneTimePreKeys(want), caps = GroupLegacyCompat.MY_CAPS /* __LEGACY_COMPAT_2026_09_23__ */)
        if (handleOutcome(a.outcome)) return
        if (a.status in 200..299) prefs.edit().putString(DEVICE_PUBLISHED, "${k.deviceId}|${spk.keyId}").apply()
    }

    /** Alert, unprompted, when a deviceId appears that this device neither approved nor already knew (§3.7). */
    private fun watchDevices(devices: List<RegisteredDevice>, mine: String) {
        _state.value = _state.value.copy(devices = devices)
        val ids = devices.map { it.deviceId }.filter { it != mine }
        val known: Set<String>? = prefs.getString(KNOWN_DEVICES, null)?.let { s ->
            runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrNull()
        }
        val approvedHere = prefs.getStringSet(APPROVED_HERE, emptySet()) ?: emptySet()
        // Same first-run seeding rule as LinkedDeviceWatch: the first list only seeds the baseline.
        val fresh = if (known == null) emptyList() else ids.filter { it !in known && it !in approvedHere }
        prefs.edit().putString(KNOWN_DEVICES, JSONArray(ids).toString()).apply()
        if (fresh.isNotEmpty()) {
            Log.w(TAG, "${fresh.size} unknown device(s) joined the account mailbox")
            notifyUser(R.string.linked_device_alert_title,
                if (fresh.size > 1) R.string.linked_device_alert_body_plural else R.string.linked_device_alert_body,
                NOTIF_NEW_DEVICE, fresh.size)
        }
    }

    // ------------------------------------------------------------------ approvals (devsync hooks)

    private fun pendingApproval(): DeviceApproval? {
        val raw = prefs.getString(PENDING_APPROVAL, null) ?: return null
        val a = DeviceApproval.fromJson(runCatching { JSONObject(raw) }.getOrNull()) ?: return null
        val age = System.currentTimeMillis() - (a.ts.toLongOrNull() ?: 0)
        if (age > DeviceApproval.MAX_AGE_MS) { prefs.edit().remove(PENDING_APPROVAL).apply(); return null }
        return a
    }

    /** Record the `dsk` a paired device announced (devsync HELLO), keyed by its deviceId. */
    fun notePeerDsk(peerDeviceId: String, dskB64: String) {
        if (!DeviceAuth.isDeviceId(peerDeviceId)) return
        val raw = runCatching { DeviceAuth.unb64(dskB64) }.getOrNull() ?: return
        if (raw.size != 32) return
        val m = runCatching { JSONObject(prefs.getString(PEER_DSKS, "{}")!!) }.getOrDefault(JSONObject())
        m.put(peerDeviceId, DeviceAuth.b64(raw))
        prefs.edit().putString(PEER_DSKS, m.toString()).apply()
    }

    /**
     * Sign the mailbox approval for a device the user just allowed. [newDskB64] is the NEW device's
     * public `dsk`. Returns null when this device is not itself registered (only a registered device's
     * signature is accepted).
     */
    fun approvalFor(newDeviceId: String, newDskB64: String): DeviceApproval? {
        if (!flagOn() && registeredId() == null) return null
        val k = keys() ?: return null
        if (registeredId() != k.deviceId) return null
        val a = k.approve(me(), newDeviceId, newDskB64, System.currentTimeMillis())
        val set = HashSet(prefs.getStringSet(APPROVED_HERE, emptySet()) ?: emptySet()); set.add(newDeviceId)
        prefs.edit().putStringSet(APPROVED_HERE, set).apply()
        return a
    }

    /**
     * `DevSyncEngine.approvalExtras`: the `{mailbox:{by,ts,sig}}` merged into our `APPROVAL{approved:true}`
     * for [peerDeviceId]. Needs the peer's `dsk` (see [notePeerDsk]); null otherwise.
     */
    fun approvalExtras(peerDeviceId: String, peerDkPub: ByteArray): JSONObject? {
        if (peerDkPub.size != 32 || DeviceAuth.deviceId(peerDkPub) != peerDeviceId) return null
        val dsk = runCatching { JSONObject(prefs.getString(PEER_DSKS, "{}")!!).optString(peerDeviceId) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val a = approvalFor(peerDeviceId, dsk) ?: return null
        return JSONObject().put(DeviceApproval.FIELD, a.toJson())
    }

    /** `DevSyncEngine.onApprovalReceived`: keep `body.mailbox` and register with it (within 15 min). */
    fun onApprovalReceived(peerDeviceId: String, body: JSONObject) {
        val a = DeviceApproval.fromJson(body.optJSONObject(DeviceApproval.FIELD)) ?: return
        if (a.by != peerDeviceId) Log.w(TAG, "approval signed by ${a.by.take(8)} relayed by ${peerDeviceId.take(8)}")
        prefs.edit().putString(PENDING_APPROVAL, a.toJson().toString()).apply()
        _state.value = _state.value.copy(approvalRequired = false)
    }

    // ------------------------------------------------------------------ device lists (§3.5)

    /** Bundled devices of [peer] (cached 10 min). null = could not ask → caller uses the legacy path. */
    suspend fun devicesOf(peer: String): List<String>? {
        deviceLists.get(peer)?.let { return it }
        val list = client.bundledDevices(peer) ?: return null
        deviceLists.put(peer, list)
        return list
    }

    fun replaceDevicesOf(peer: String, devices: List<String>) = deviceLists.put(peer, devices)

    // ------------------------------------------------------------------ removal (§3.7)

    /** "Remove device": any registered device may remove any other (device-signed by THIS device). */
    suspend fun removeDevice(deviceId: String): DeviceHttpOutcome {
        val k = keys() ?: return DeviceHttpOutcome.Failed(-1, "no-keys")
        val o = client.unregister(k, deviceId).outcome
        if (o == DeviceHttpOutcome.Ok) {
            deviceLists.invalidate(me())
            if (deviceId == k.deviceId) prefs.edit().remove(REGISTERED_ID).remove(DEVICE_PUBLISHED).apply()
        } else handleOutcome(o)
        return o
    }

    /** On sign-out / identity deletion the device unregisters itself. Best effort. */
    suspend fun unregisterSelf(): Boolean {
        val k = keys() ?: return false
        if (registeredId() != k.deviceId) return true
        return removeDevice(k.deviceId) == DeviceHttpOutcome.Ok
    }

    /** "Reset all devices" (recovery only). Loud: every device gets 410 and alerts. */
    suspend fun resetAllDevices(): Boolean {
        val ok = client.reset().status in 200..299
        if (ok) prefs.edit().remove(REGISTERED_ID).remove(DEVICE_PUBLISHED).remove(KNOWN_DEVICES).apply()
        return ok
    }

    /** Local wipe (account deletion): the server list is erased by `DELETE /v2/account`. */
    fun clearLocal() {
        prefs.edit().clear().apply()
        runCatching { context.deleteSharedPreferences(KEY_PREFS) }
        cachedKeys = null
        deviceLists.clear()
        _state.value = State()
    }

    // ------------------------------------------------------------------ alerts

    private fun notifyUser(titleRes: Int, bodyRes: Int, id: Int, count: Int = 1) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_SECURITY) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_SECURITY, "Security alerts", NotificationManager.IMPORTANCE_HIGH))
            }
            val body = if (count > 1) context.getString(bodyRes, count) else context.getString(bodyRes)
            val n = NotificationCompat.Builder(context, CHANNEL_SECURITY)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(titleRes))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "device alert suppressed (no notification permission)")
        } catch (e: Exception) {
            Log.w(TAG, "device alert failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "DeviceMailbox"
        const val PREFS = "oshi_device_mailbox"
        const val KEY_PREFS = "oshi_device_mailbox_keys"
        const val DSK_SEED = "dsk_seed_b64"
        const val REGISTERED_ID = "registered_device_id"
        const val REMOVED = "removed"
        const val HOLDS_ACCOUNT = "holds_account_bundle"
        const val HOLDS_CHECKED_AT = "holds_checked_at"
        const val DEVICE_CURSOR = "device_cursor"
        const val DEVICE_PUBLISHED = "device_bundle_published"
        const val PENDING_APPROVAL = "pending_approval"
        const val PEER_DSKS = "peer_dsks"
        const val KNOWN_DEVICES = "known_devices"
        const val APPROVED_HERE = "approved_here"
        const val CHANNEL_SECURITY = "oshi_security"
        const val NOTIF_REMOVED = 90211
        const val NOTIF_NEW_DEVICE = 90212
        const val OPK_TARGET = 20
        const val OPK_LOW_WATER = 8
        const val DAY_MS = 24 * 3600 * 1000L
    }
}
