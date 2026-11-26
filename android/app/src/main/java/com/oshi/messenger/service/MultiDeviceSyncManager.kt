package com.oshi.messenger.service

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.vps.VPSClient
import com.oshi.messenger.ui.viewmodel.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MultiDeviceSyncManager - Compatible with iOS MultiDeviceSyncManager.swift
 * Handles synchronization of messages and settings across multiple devices
 */
@Singleton
class MultiDeviceSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val vpsClient: VPSClient
) {
    companion object {
        private const val TAG = "MultiDeviceSyncManager"
        private const val SYNC_PREFIX = "\uD83D\uDD04SYNC\uD83D\uDD04"  // 🔄SYNC🔄
        private const val DEVICE_LINK_PREFIX = "\uD83D\uDD17LINK\uD83D\uDD17"  // 🔗LINK🔗
        private const val SYNC_INTERVAL_MS = 30000L  // 30 seconds

        const val MAX_LINK_CODE_ATTEMPTS = DeviceLinkPolicy.MAX_LINK_CODE_ATTEMPTS

        /**
         * How often the "last synced" timestamp is actually committed to disk.
         * See the comment at the write site: persisting it every cycle was a
         * SharedPreferences fsync every 30 s for a value nothing reads at that
         * resolution.
         */
        private const val LAST_SYNC_PERSIST_INTERVAL_MS = 5 * 60_000L

        /**
         * True for the prefix-LESS sync envelope `{type, deviceId, encryptedData}`.
         *
         * The iOS-shaped envelope carries no emoji prefix, so a receive chain cannot find
         * it with `startsWith`. Its first key is `type`, so the media chains'
         * `startsWith("{\"type\":")` claims it first — `encryptedData` is the
         * discriminant that tells the two apart.
         *
         * Recognition only: [handleSyncData] still drops anything from an unlinked sender.
         */
        fun isSyncEnvelope(content: String): Boolean =
            content.startsWith("{") &&
                runCatching { JSONObject(content).has("encryptedData") }.getOrDefault(false)

        /**
         * True for anything [handleSyncData] can read: the legacy `🔄SYNC🔄` prefix or
         * the prefix-less envelope.
         *
         * This and [isLinkPayload] are the ONLY recognition seam the receive chains use.
         * `MessageRepository` has four near-copies of the prefix chain; spelling the two
         * markers out in each of them is how a prefix list drifts (see
         * `OSHIControlPayload`, which exists because four such copies once diverged).
         */
        fun isSyncPayload(content: String): Boolean =
            content.startsWith(SYNC_PREFIX) || isSyncEnvelope(content)

        /**
         * True for the device-link handshake `🔗LINK🔗{action:…}`.
         *
         * Not to be confused with `🔗LINKS_VISIBILITY🔗` (profile links): the two share
         * a leading 🔗 but diverge at the character after `LINK`, so neither prefix
         * matches the other's payload.
         */
        fun isLinkPayload(content: String): Boolean =
            content.startsWith(DEVICE_LINK_PREFIX)

        // =====================================================================
        // SERVER-MEDIATED SYNC (iOS MultiDeviceSyncManager.swift:456-1190)
        // =====================================================================
        //
        // iOS `syncBaseURL` (swift:29). The five blob routes below are the ONLY
        // /api/sync/* endpoints that exist in production — verified read-only
        // 2026-08-13 with `OPTIONS`:
        //
        //   /api/sync/messages/:key       → 200, Allow: GET, HEAD, POST
        //   /api/sync/groups/:key         → 200
        //   /api/sync/contacts/:key       → 200
        //   /api/sync/groupMessages/:key  → 200
        //   /api/sync/profile/:key        → 200
        //
        // The device-registry half that iOS also calls — /api/sync/register,
        // /unregister, /message, /read, /pull, /devices, /remove-device
        // (swift:220,261,296,319,349,402,427) — returns 404 for both GET and
        // OPTIONS on the live server. Those seven are DEAD ON iOS TOO: the code
        // path exists, the route does not. Android deliberately does not
        // implement them; a "registered devices" list cannot be built against a
        // 404, and shipping the UI for it would be theatre.
        const val SYNC_BASE_URL = "https://oshi-messenger.com"

        /** Seconds between the Unix epoch and Apple's 2001-01-01 reference date. */
        const val APPLE_EPOCH_OFFSET_SECONDS = 978_307_200L

        /**
         * Swift's `JSONEncoder` default date strategy is `.deferredToDate`, i.e. a
         * Double of seconds since 2001-01-01. Encoding epoch millis here instead
         * would land every timestamp in the archive somewhere in the year 32000
         * on iOS. Not theoretical: the whole point of this archive is that the
         * other device may be an iPhone.
         */
        @JvmStatic
        fun appleTimeFromEpochMillis(ms: Long): Double =
            (ms / 1000.0) - APPLE_EPOCH_OFFSET_SECONDS

        @JvmStatic
        fun epochMillisFromAppleTime(t: Double): Long =
            ((t + APPLE_EPOCH_OFFSET_SECONDS) * 1000.0).toLong()

        /**
         * iOS `urlEncodedKey` (swift:449-454): base64url, padding stripped, so the
         * key survives nginx + Express path parsing untouched. A percent-encoded
         * key hits a DIFFERENT storage slot than the iPhone's and the two devices
         * silently never see each other's archive.
         */
        @JvmStatic
        fun urlEncodedKey(key: String): String =
            key.replace("+", "-").replace("/", "_").replace("=", "")

        /** iOS upload envelope: `["deviceId": …, "data": <base64 AES-GCM>]` (swift:477-480). */
        @JvmStatic
        fun buildSyncUploadBody(deviceId: String, encryptedBase64: String): JSONObject =
            JSONObject().apply {
                put("deviceId", deviceId)
                put("data", encryptedBase64)
            }

        /**
         * One `SecureMessage` in the exact shape iOS's `JSONDecoder` expects
         * (`MessageManager.swift:7983-8055`). Non-optional keys — id,
         * senderAddress, recipientAddress, encryptedContent, timestamp, isRead,
         * deliveryStatus — MUST all be present: a single missing key throws
         * inside the array decode and iOS discards the whole archive.
         *
         * `deliveryMethod` is deliberately omitted: its raw values are
         * iOS-specific ("Direct"/"Cloud"/…) and iOS degrades an absent or
         * unknown value to nil by design (swift:8035-8047).
         */
        @JvmStatic
        fun messageToSyncJson(
            id: String,
            senderPublicKey: String,
            recipientPublicKey: String,
            content: String,
            epochMillis: Long,
            isRead: Boolean,
            deliveryStatus: String,
            mediaTypeRaw: String?,
            mediaBase64: String?,
            editedAtMillis: Long?,
            editedContent: String?,
            isDeletedForEveryone: Boolean,
            replyToId: String?
        ): JSONObject = JSONObject().apply {
            val appleTs = appleTimeFromEpochMillis(epochMillis)
            put("id", id)
            put("senderAddress", senderPublicKey.take(8))
            put("recipientAddress", recipientPublicKey.take(8))
            put("encryptedContent", JSONObject().apply {
                // Android keeps the plaintext and drops the ciphertext once
                // decrypted; iOS only ever displays `plaintextContent`, so an
                // empty envelope is honest rather than fabricated ciphertext.
                put("ciphertext", "")
                put("signature", "")
                put("senderPublicKey", senderPublicKey)
                put("timestamp", appleTs)
            })
            put("timestamp", appleTs)
            put("isRead", isRead)
            put("deliveryStatus", deliveryStatus)
            put("senderPublicKey", senderPublicKey)
            put("recipientPublicKey", recipientPublicKey)
            put("plaintextContent", content)
            if (mediaTypeRaw != null) put("mediaType", mediaTypeRaw)
            if (mediaBase64 != null) put("mediaAttachment", mediaBase64)
            if (editedAtMillis != null) put("editedAt", appleTimeFromEpochMillis(editedAtMillis))
            if (editedContent != null) put("editedContent", editedContent)
            put("isDeletedForEveryone", isDeletedForEveryone)
            if (replyToId != null) put("replyToId", replyToId)
        }

        /** iOS `MediaManager.MediaType` raw values (MediaManager.swift:37-43). */
        @JvmStatic
        fun mediaTypeToIos(name: String?): String? = when (name?.uppercase()) {
            "IMAGE" -> "image"
            "VIDEO" -> "video"
            "AUDIO" -> "audio"
            "DOCUMENT" -> "document"
            "CONTACT" -> "contact"
            // LOCATION has no iOS MediaType case, and a raw value iOS has never
            // heard of THROWS inside its decoder, killing the whole archive.
            else -> null
        }

        /**
         * The inverse of [mediaTypeToIos] — and deliberately NOT
         * `MediaType.fromWireLabel`: this table is pinned to the cases iOS's archive
         * can actually contain, so `location` stays null in both directions (see the
         * test of the same name).
         *
         * `gif` is the one addition (2026-09-12): iOS is adding the case, its archive
         * will carry the label, and Android has no such enum value. IMAGE is the
         * honest landing place — a still frame beats a message that syncs back with
         * no type at all.
         */
        @JvmStatic
        fun mediaTypeFromIos(raw: String?): String? = when (raw?.lowercase()) {
            "image", "gif" -> "IMAGE"
            "video" -> "VIDEO"
            "audio" -> "AUDIO"
            "document" -> "DOCUMENT"
            "contact" -> "CONTACT"
            else -> null
        }

        /** iOS `DeliveryStatus` raw values (MessageManager.swift:8122-8136). */
        @JvmStatic
        fun deliveryStatusToIos(
            isRead: Boolean,
            isDelivered: Boolean,
            isFailed: Boolean,
            isFromMe: Boolean
        ): String = when {
            isFailed -> "failed"
            isRead -> "read"
            isDelivered -> "delivered"
            isFromMe -> "sent"
            else -> "delivered"
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = context.getSharedPreferences("multi_device", Context.MODE_PRIVATE)

    // State
    private val _linkedDevices = MutableStateFlow<List<LinkedDevice>>(emptyList())
    val linkedDevices: StateFlow<List<LinkedDevice>> = _linkedDevices.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<Long>(0)
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    // Device info — single source of truth lives in FCMService so the push
    // service and the sync service agree on which deviceId represents this
    // physical phone. Previously they read different SharedPreferences files
    // and the server saw two ghost device entries per real device.
    private val deviceId: String by lazy {
        FCMService.getDeviceId(context)
    }

    private val deviceName: String by lazy {
        android.os.Build.MODEL
    }

    // Sync job
    private var syncJob: Job? = null

    // --- server-mediated sync state (declared before init{} so it is usable there)
    private val syncPrefs = context.getSharedPreferences("oshi_multidevice_sync", Context.MODE_PRIVATE)

    private val httpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * iOS defaults this ON at first launch (swift:96-101) so a reinstall pulls
     * the user's history back; the user can turn it off in Settings.
     */
    private val _isSyncEnabled = MutableStateFlow(syncPrefs.getBoolean("oshi_sync_enabled", true))
    val isSyncEnabled: StateFlow<Boolean> = _isSyncEnabled.asStateFlow()

    private var serverSyncJob: Job? = null

    /**
     * 🔴 PENDING SYNC DATA — GUARDED, AND DRAINED BY SNAPSHOT.
     *
     * Two defects lived here, both of them the ones the iOS audit found in its own
     * queues on 2026-09-12:
     *
     *  1. **Unsynchronised.** [queueMessageForSync] is called from whatever thread
     *     sent the message, while [syncWithLinkedDevices] iterated the same list on
     *     the sync scope. `ArrayList` gives no guarantee there: the iteration throws
     *     `ConcurrentModificationException` (which the surrounding catch turns into
     *     a bare "Sync failed" log) or, worse, silently reads a torn array.
     *
     *  2. **`clear()` after the await.** The fan-out suspends on the network; every
     *     message queued during that window — exactly the busy period a sync is for
     *     — was then wiped by an unconditional `clear()` without ever being sent.
     *     Never retried, because nothing remembered it existed.
     *
     * So: every touch takes [pendingLock], the drain works from a snapshot, and on
     * success it removes ONLY what the snapshot contained. Anything queued meanwhile
     * survives to the next sweep.
     */
    private val pendingLock = Any()
    private val pendingMessages = mutableListOf<Message>()
    private val pendingSettings = mutableMapOf<String, Any>()

    /** Wall clock of the last `last_sync` value actually written to disk. */
    private var lastSyncPersistedAtMs: Long = 0L

    init {
        loadLinkedDevices()
        _lastSyncTime.value = syncPrefs.getLong("last_sync", 0L)
        lastSyncPersistedAtMs = _lastSyncTime.value
        // Server-mediated sync needs no pairing step: sharing the recovery key
        // IS the pairing, so it can start as soon as the identity exists.
        startServerSync()
    }

    // =========================================================================
    // DEVICE LINKING
    // =========================================================================

    /**
     * Generate link code for another device
     */
    fun generateLinkCode(): DeviceLinkCode {
        val code = DeviceLinkPolicy.newLinkCode()
        val expiry = System.currentTimeMillis() + (5 * 60 * 1000)  // 5 minutes

        prefs.edit()
            .putString("pending_link_code", code)
            .putLong("pending_link_expiry", expiry)
            .putInt("pending_link_attempts", 0)
            .apply()

        return DeviceLinkCode(
            code = code,
            expiresAt = expiry,
            deviceId = deviceId,
            deviceName = deviceName
        )
    }

    /**
     * Link a new device using a code
     */
    fun linkDevice(code: String, callback: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                // In real implementation, this would verify with server
                val json = JSONObject().apply {
                    put("action", "link_request")
                    put("code", code)
                    put("deviceId", deviceId)
                    put("deviceName", deviceName)
                    put("publicKey", cryptoManager.getPublicKeyString())
                }

                val content = "$DEVICE_LINK_PREFIX${json}"

                // Broadcast to find device with matching code
                // In production, this would go through a server
                Log.d(TAG, "Requesting device link with code: $code")

                // Simulate success for now
                callback(true, "Device linking initiated")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to link device: ${e.message}")
                callback(false, e.message ?: "Link failed")
            }
        }
    }

    /**
     * Handle incoming link request
     */
    fun handleLinkRequest(content: String, senderPublicKey: String) {
        if (!content.startsWith(DEVICE_LINK_PREFIX)) return

        try {
            val jsonStr = content.removePrefix(DEVICE_LINK_PREFIX)
            val json = JSONObject(jsonStr)

            val action = json.getString("action")

            when (action) {
                "link_request" -> handleIncomingLinkRequest(json, senderPublicKey)
                "link_accept" -> handleLinkAccepted(json, senderPublicKey)
                "link_reject" -> handleLinkRejected(json)
                "unlink" -> handleUnlink(json, senderPublicKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle link request: ${e.message}")
        }
    }

    /**
     * Unlink a device
     */
    fun unlinkDevice(linkedDeviceId: String) {
        val device = _linkedDevices.value.find { it.deviceId == linkedDeviceId } ?: return

        scope.launch {
            // Notify the other device
            val json = JSONObject().apply {
                put("action", "unlink")
                put("deviceId", deviceId)
            }

            val content = "$DEVICE_LINK_PREFIX${json}"
            // Multi-device sync must not wake the far phone. iOS never
            // pushes for sync at all: its sync rides dedicated
            // /api/sync/* REST routes (MultiDeviceSyncManager.swift:220-769)
            // and never touches the message queue. Android routes sync
            // through sendMessage, which pushed "New Message" for every
            // envelope — the own-device banners 8c3dd73 was reported for.
            vpsClient.sendMessage(device.publicKey, content, suppressPush = true)

            // Remove locally
            val current = _linkedDevices.value.toMutableList()
            current.removeAll { it.deviceId == linkedDeviceId }
            _linkedDevices.value = current
            saveLinkedDevices()

            Log.d(TAG, "Unlinked device: ${device.deviceName}")
        }
    }

    // =========================================================================
    // SYNC
    // =========================================================================

    /**
     * Start automatic sync
     */
    fun startAutoSync() {
        if (syncJob?.isActive == true) return

        syncJob = scope.launch {
            while (isActive) {
                if (_linkedDevices.value.isNotEmpty()) {
                    syncWithLinkedDevices()
                }
                delay(AppPollState.auxSyncDelayMs(SYNC_INTERVAL_MS))
            }
        }
        Log.d(TAG, "Started auto sync")
    }

    /**
     * Stop automatic sync
     */
    fun stopAutoSync() {
        syncJob?.cancel()
        syncJob = null
        Log.d(TAG, "Stopped auto sync")
    }

    /**
     * Trigger manual sync
     */
    fun syncNow() {
        scope.launch {
            syncWithLinkedDevices()
        }
    }

    /**
     * Queue a message for sync
     */
    fun queueMessageForSync(message: Message) {
        synchronized(pendingLock) { pendingMessages.add(message) }
        Log.d(TAG, "Queued message for sync: ${message.id}")
    }

    /**
     * Queue a setting for sync
     */
    fun queueSettingForSync(key: String, value: Any) {
        synchronized(pendingLock) { pendingSettings[key] = value }
        Log.d(TAG, "Queued setting for sync: $key")
    }

    /**
     * Handle incoming sync data.
     *
     * Supports TWO formats for cross-platform + backwards compatibility:
     *   1. (New, iOS-compatible) JSON envelope: {type, deviceId, encryptedData}
     *      where encryptedData is AES-256-GCM(payload JSON) using the key derived
     *      from the user's own private key (same HKDF as iOS MultiDeviceSyncManager.swift).
     *      This is how iOS sends and what iOS expects.
     *   2. (Legacy, Android-only) Prefix-delimited plaintext: SYNC_PREFIX + JSON.
     *      Nothing EMITS this any more — the fail-open `?: "$SYNC_PREFIX$syncData"` that
     *      produced it is gone (see [fanOutSync]). Kept on the read side only, so an
     *      older Android still on that build stays readable.
     */
    fun handleSyncData(content: String, senderPublicKey: String) {
        // Verify sender is a linked device
        if (_linkedDevices.value.none { it.publicKey == senderPublicKey }) {
            Log.w(TAG, "Received sync from unlinked device")
            return
        }

        try {
            val innerJson: JSONObject = when {
                // Legacy prefix format (backwards-compat)
                content.startsWith(SYNC_PREFIX) -> {
                    val jsonStr = content.removePrefix(SYNC_PREFIX)
                    JSONObject(jsonStr)
                }
                // New envelope format (iOS-compatible)
                else -> {
                    val envelope = try {
                        JSONObject(content)
                    } catch (e: Exception) {
                        Log.w(TAG, "Sync content is neither prefixed nor valid JSON envelope")
                        return
                    }
                    val encryptedData = envelope.optString("encryptedData", "")
                    if (encryptedData.isEmpty()) {
                        Log.w(TAG, "Sync envelope missing encryptedData")
                        return
                    }
                    val decrypted = decryptSyncEnvelope(encryptedData)
                        ?: run {
                            Log.e(TAG, "Failed to decrypt sync envelope")
                            return
                        }
                    // Envelope top-level `type` drives dispatch; decrypted body carries the payload.
                    // We merge the envelope `type` onto the inner payload so downstream handlers
                    // work whether the type sits in the envelope or the inner JSON.
                    val inner = JSONObject(decrypted)
                    if (!inner.has("type")) inner.put("type", envelope.optString("type"))
                    inner
                }
            }

            val syncType = innerJson.optString("type")

            when (syncType) {
                "messages" -> handleMessageSync(innerJson)
                "settings" -> handleSettingsSync(innerJson)
                "contacts" -> handleContactsSync(innerJson)
                "profile_links" -> handleProfileLinksSync(innerJson)
                "full" -> handleFullSync(innerJson)
            }

            _lastSyncTime.value = System.currentTimeMillis()
            _syncStatus.value = SyncStatus.Idle

        } catch (e: Exception) {
            // __RELEASE_LOG_LEAKS_2026_09_23__ org.json quotes the WHOLE input in its
            // exception message, and the input here is the decrypted sync payload
            // (messages, contacts). Class name only — in the log and in the UI state.
            Log.e(TAG, "Failed to handle sync: ${e.javaClass.simpleName}")
            _syncStatus.value = SyncStatus.Error("Sync failed (${e.javaClass.simpleName})")
        }
    }

    /**
     * Sync all contacts to linked devices
     */
    fun syncContactsToLinkedDevices() {
        scope.launch {
            try {
                val db = AppDatabase.getInstance(context)
                val contacts = db.contactDao().getAllContactsList()
                val contactsArray = JSONArray()
                val nowTs = System.currentTimeMillis()
                for (contact in contacts) {
                    // Include per-contact syncTimestamp for last-write-wins conflict resolution (task 1.4.9)
                    val contactTs = contactSyncPrefs.getLong("ts_${contact.publicKey}", nowTs)
                    contactsArray.put(JSONObject().apply {
                        put("publicKey", contact.publicKey)
                        put("alias", contact.alias)
                        put("isBlocked", contact.isBlocked)
                        put("isVerified", contact.isVerified)
                        put("syncTimestamp", contactTs)
                    })
                }
                val syncData = JSONObject().apply {
                    put("type", "contacts")
                    put("contacts", contactsArray)
                    put("timestamp", System.currentTimeMillis())
                    put("deviceId", deviceId)
                }
                sendSyncToLinkedDevices(syncData)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync contacts: ${e.message}")
            }
        }
    }

    /**
     * Send sync data to all linked devices.
     * Wraps the payload in the iOS-compatible AES-256-GCM envelope
     *   {type, deviceId, encryptedData}
     * so iOS can decrypt cross-platform syncs. If the envelope cannot be built the
     * send is REFUSED — see [fanOutSync].
     */
    private suspend fun sendSyncToLinkedDevices(syncData: JSONObject) {
        val sent = fanOutSync(syncData, _linkedDevices.value, ::buildSyncEnvelope) { device, content ->
            try {
                // Multi-device sync must not wake the far phone. iOS never
                // pushes for sync at all: its sync rides dedicated
                // /api/sync/* REST routes (MultiDeviceSyncManager.swift:220-769)
                // and never touches the message queue. Android routes sync
                // through sendMessage, which pushed "New Message" for every
                // envelope — the own-device banners 8c3dd73 was reported for.
                vpsClient.sendMessage(device.publicKey, content, suppressPush = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send sync to ${device.deviceName}: ${e.message}")
            }
        }
        if (!sent) {
            Log.e(TAG, "Sync refused: sync envelope key unavailable")
            _syncStatus.value = SyncStatus.Error("Encryption unavailable")
        }
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    private fun handleIncomingLinkRequest(json: JSONObject, senderPublicKey: String) {
        val code = json.getString("code")
        val pendingCode = prefs.getString("pending_link_code", null)
        val pendingExpiry = prefs.getLong("pending_link_expiry", 0)

        if (!DeviceLinkPolicy.linkCodeMatches(code, pendingCode)) {
            Log.w(TAG, "Invalid link code")
            // __DEVICE_LINK_AUTHZ_2026_11_20__ burn the code after a few misses.
            if (pendingCode != null) {
                val attempts = prefs.getInt("pending_link_attempts", 0) + 1
                if (attempts >= MAX_LINK_CODE_ATTEMPTS) {
                    prefs.edit().remove("pending_link_code").remove("pending_link_expiry")
                        .remove("pending_link_attempts").apply()
                    Log.w(TAG, "Link code burned after $attempts wrong attempts")
                } else {
                    prefs.edit().putInt("pending_link_attempts", attempts).apply()
                }
            }
            return
        }

        if (System.currentTimeMillis() > pendingExpiry) {
            Log.w(TAG, "Link code expired")
            return
        }

        val requestingDeviceId = json.getString("deviceId")
        val requestingDeviceName = json.getString("deviceName")

        // Accept the link
        scope.launch {
            val acceptJson = JSONObject().apply {
                put("action", "link_accept")
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("publicKey", cryptoManager.getPublicKeyString())
            }

            val content = "$DEVICE_LINK_PREFIX${acceptJson}"
            // Multi-device sync must not wake the far phone. iOS never
            // pushes for sync at all: its sync rides dedicated
            // /api/sync/* REST routes (MultiDeviceSyncManager.swift:220-769)
            // and never touches the message queue. Android routes sync
            // through sendMessage, which pushed "New Message" for every
            // envelope — the own-device banners 8c3dd73 was reported for.
            vpsClient.sendMessage(senderPublicKey, content, suppressPush = true)

            // Add to linked devices
            val device = LinkedDevice(
                deviceId = requestingDeviceId,
                deviceName = requestingDeviceName,
                publicKey = senderPublicKey,
                linkedAt = System.currentTimeMillis()
            )

            val current = _linkedDevices.value.toMutableList()
            if (current.none { it.deviceId == requestingDeviceId }) {
                current.add(device)
                _linkedDevices.value = current
                saveLinkedDevices()
            }

            // Clear pending code
            prefs.edit()
                .remove("pending_link_code")
                .remove("pending_link_expiry")
                .apply()

            Log.d(TAG, "Accepted link from: $requestingDeviceName")
        }
    }

    private fun handleLinkAccepted(json: JSONObject, senderPublicKey: String) {
        // __DEVICE_LINK_AUTHZ_2026_11_20__ unsolicited accepts are ignored. This build
        // never sends a link_request itself ([linkDevice] is a stub), so nothing sets
        // the pending target and every accept is refused — which is the point.
        if (!DeviceLinkPolicy.isExpectedLinkAccept(
                senderPublicKey,
                prefs.getString("pending_outgoing_link_target", null),
                prefs.getLong("pending_outgoing_link_expiry", 0L),
                System.currentTimeMillis()
            )
        ) {
            Log.w(TAG, "Ignoring unsolicited link_accept")
            return
        }
        prefs.edit().remove("pending_outgoing_link_target").remove("pending_outgoing_link_expiry").apply()
        val acceptedDeviceId = json.getString("deviceId")
        val acceptedDeviceName = json.getString("deviceName")

        val device = LinkedDevice(
            deviceId = acceptedDeviceId,
            deviceName = acceptedDeviceName,
            publicKey = senderPublicKey,
            linkedAt = System.currentTimeMillis()
        )

        val current = _linkedDevices.value.toMutableList()
        if (current.none { it.deviceId == acceptedDeviceId }) {
            current.add(device)
            _linkedDevices.value = current
            saveLinkedDevices()
        }

        Log.d(TAG, "Link accepted by: $acceptedDeviceName")
    }

    private fun handleLinkRejected(json: JSONObject) {
        Log.d(TAG, "Link request rejected")
    }

    private fun handleUnlink(json: JSONObject, senderPublicKey: String) {
        if (!DeviceLinkPolicy.mayUnlink(senderPublicKey, _linkedDevices.value.map { it.publicKey })) {
            Log.w(TAG, "Ignoring unlink from a device that is not linked")
            return
        }
        val unlinkingDeviceId = json.getString("deviceId")

        val current = _linkedDevices.value.toMutableList()
        current.removeAll { it.deviceId == unlinkingDeviceId }
        _linkedDevices.value = current
        saveLinkedDevices()

        Log.d(TAG, "Device unlinked: $unlinkingDeviceId")
    }

    private suspend fun syncWithLinkedDevices() {
        if (_linkedDevices.value.isEmpty()) return

        _syncStatus.value = SyncStatus.Syncing

        try {
            // The snapshot the rest of this sweep works from. Taken under the lock so
            // it cannot tear, and it — not the live list — is what gets removed on
            // success. See [pendingLock].
            val messagesSnapshot: List<Message>
            val settingsSnapshot: Map<String, Any>
            synchronized(pendingLock) {
                messagesSnapshot = pendingMessages.toList()
                settingsSnapshot = pendingSettings.toMap()
            }

            // Prepare sync data
            val syncJson = JSONObject().apply {
                put("type", "messages")
                put("deviceId", deviceId)
                put("timestamp", System.currentTimeMillis())

                // Add pending messages
                val messagesArray = JSONArray()
                messagesSnapshot.forEach { message ->
                    messagesArray.put(JSONObject().apply {
                        put("id", message.id)
                        put("content", message.content)
                        put("timestamp", message.timestamp)
                        put("senderPublicKey", message.senderPublicKey)
                        put("recipientPublicKey", message.recipientPublicKey)
                        put("isFromMe", message.isFromMe)
                    })
                }
                put("messages", messagesArray)

                // Add pending settings
                val settingsObject = JSONObject()
                settingsSnapshot.forEach { (key, value) ->
                    settingsObject.put(key, value)
                }
                put("settings", settingsObject)
            }

            // Wrap in the iOS-compatible envelope, or refuse — see [fanOutSync].
            val sent = fanOutSync(syncJson, _linkedDevices.value, ::buildSyncEnvelope) { device, content ->
                try {
                    // Multi-device sync must not wake the far phone. iOS never
                    // pushes for sync at all: its sync rides dedicated
                    // /api/sync/* REST routes (MultiDeviceSyncManager.swift:220-769)
                    // and never touches the message queue. Android routes sync
                    // through sendMessage, which pushed "New Message" for every
                    // envelope — the own-device banners 8c3dd73 was reported for.
                    vpsClient.sendMessage(device.publicKey, content, suppressPush = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync with ${device.deviceName}: ${e.message}")
                }
            }

            if (!sent) {
                // Nothing left the device, so the pending queues must survive for the
                // next attempt — clearing them here would silently drop the payload.
                Log.e(TAG, "Sync refused: sync envelope key unavailable")
                _syncStatus.value = SyncStatus.Error("Encryption unavailable")
                return
            }

            // Clear ONLY what actually went out. A `clear()` here would drop every
            // message queued while the fan-out above was suspended on the network.
            synchronized(pendingLock) {
                val syncedIds = messagesSnapshot.mapTo(HashSet()) { it.id }
                pendingMessages.removeAll { it.id in syncedIds }
                // A setting re-queued during the sweep holds a NEWER value than the
                // one just sent, so only an unchanged entry is retired.
                settingsSnapshot.forEach { (key, value) ->
                    if (pendingSettings[key] == value) pendingSettings.remove(key)
                }
            }

            _lastSyncTime.value = System.currentTimeMillis()
            _syncStatus.value = SyncStatus.Idle

            Log.d(TAG, "Sync completed with ${_linkedDevices.value.size} devices")

        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}")
            _syncStatus.value = SyncStatus.Error(e.message ?: "Sync failed")
        }
    }

    private fun handleMessageSync(json: JSONObject) {
        val messagesArray = json.optJSONArray("messages") ?: return
        Log.d(TAG, "Syncing ${messagesArray.length()} messages from linked device")
        scope.launch {
            val db = AppDatabase.getInstance(context)
            for (i in 0 until messagesArray.length()) {
                val msgJson = messagesArray.optJSONObject(i) ?: continue
                val id = msgJson.optString("id", "")
                if (id.isEmpty()) continue

                try {
                    // Deduplication: check if message already exists
                    val existing = db.messageDao().getMessageById(id)
                    if (existing != null) {
                        Log.d(TAG, "Skipping duplicate message: $id")
                        continue
                    }

                    val message = Message(
                        id = id,
                        content = msgJson.optString("content", ""),
                        timestamp = msgJson.optLong("timestamp", System.currentTimeMillis()),
                        senderPublicKey = msgJson.optString("senderPublicKey", ""),
                        recipientPublicKey = msgJson.optString("recipientPublicKey", ""),
                        isFromMe = msgJson.optBoolean("isFromMe", false)
                    )
                    db.messageDao().insert(message)
                    Log.d(TAG, "Synced message: $id")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync message $id: ${e.message}")
                }
            }
        }
    }

    private fun handleSettingsSync(json: JSONObject) {
        val settings = json.optJSONObject("settings") ?: return
        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = settings.get(key)
            Log.d(TAG, "Applying synced setting: $key = $value")
            // Skip device-specific settings
            if (key in listOf("biometric_enabled", "screenshot_protection")) {
                Log.d(TAG, "Skipping device-specific setting: $key")
                continue
            }
            scope.launch {
                applySettingLocally(key, value)
            }
        }
    }

    private suspend fun applySettingLocally(key: String, value: Any) {
        try {
            val prefs = context.getSharedPreferences("oshi_settings", Context.MODE_PRIVATE)
            when (value) {
                is String -> prefs.edit().putString(key, value).apply()
                is Boolean -> prefs.edit().putBoolean(key, value).apply()
                is Int -> prefs.edit().putInt(key, value).apply()
                is Long -> prefs.edit().putLong(key, value).apply()
                is Float -> prefs.edit().putFloat(key, value).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply setting $key: ${e.message}")
        }
    }

    /**
     * Per-contact last-write-wins conflict resolution (task 1.4.9).
     * Each contact in the sync payload includes a `syncTimestamp`.
     * We compare against locally stored timestamps; the newest write wins.
     */
    private val contactSyncPrefs by lazy {
        context.getSharedPreferences("contact_sync_timestamps", Context.MODE_PRIVATE)
    }

    private fun handleContactsSync(json: JSONObject) {
        val contactsArray = json.optJSONArray("contacts") ?: return
        val incomingDeviceTimestamp = json.optLong("timestamp", 0L)
        Log.d(TAG, "Syncing ${contactsArray.length()} contacts (ts=$incomingDeviceTimestamp) from linked device")
        scope.launch {
            val db = AppDatabase.getInstance(context)
            for (i in 0 until contactsArray.length()) {
                val contactJson = contactsArray.optJSONObject(i) ?: continue
                val publicKey = contactJson.optString("publicKey", "")
                if (publicKey.isEmpty()) continue
                val alias = contactJson.optString("alias", "")
                val isBlocked = contactJson.optBoolean("isBlocked", false)
                val isVerified = contactJson.optBoolean("isVerified", false)
                // Per-contact timestamp for last-write-wins
                val incomingTs = contactJson.optLong("syncTimestamp", incomingDeviceTimestamp)

                try {
                    val existing = db.contactDao().getContactByPublicKey(publicKey)
                    val localTs = contactSyncPrefs.getLong("ts_$publicKey", 0L)

                    if (existing == null) {
                        // New contact — insert regardless
                        db.contactDao().insert(Contact(
                            publicKey = publicKey,
                            alias = alias,
                            isBlocked = isBlocked,
                            isVerified = isVerified
                        ))
                        contactSyncPrefs.edit().putLong("ts_$publicKey", incomingTs).apply()
                        Log.d(TAG, "Synced new contact: ${publicKey.take(12)}...")
                    } else if (incomingTs > localTs) {
                        // __BLOCK_SYNC_LWW_2026_09_24__ the block itself is compared with the time
                        // it was made/lifted HERE (BlockedKeysStore), not with the last import: a
                        // stale record must not undo a newer local block or unblock, and when it
                        // does win it is applied to the durable list too (not only the row).
                        val store = BlockedKeysStore.get(context)
                        val localBlockTs = maxOf(store.entry(publicKey)?.blockedAt ?: 0L, store.unblockedAt(publicKey) ?: 0L)
                        val applyBlock = contactJson.has("isBlocked") && incomingTs > localBlockTs
                        if (applyBlock && isBlocked != store.isBlocked(publicKey)) {
                            if (isBlocked) store.blockAt(publicKey, existing.alias, incomingTs) else store.unblockAt(publicKey, incomingTs)
                        }
                        // Incoming is newer — last-write-wins: update local contact
                        db.contactDao().update(existing.copy(
                            alias = alias.ifEmpty { existing.alias },
                            // An ABSENT field is "unknown", not "unblocked": a record
                            // that omits it must not silently lift a block made here.
                            isBlocked = if (applyBlock) isBlocked else existing.isBlocked,
                            isVerified = isVerified
                        ))
                        contactSyncPrefs.edit().putLong("ts_$publicKey", incomingTs).apply()
                        Log.d(TAG, "Conflict resolved (incoming wins): ${publicKey.take(12)}...")
                    } else {
                        Log.d(TAG, "Conflict resolved (local wins): ${publicKey.take(12)}...")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync contact ${publicKey.take(12)}: ${e.message}")
                }
            }
            // The receive gates read MessageRepository's block cache, not the DAO.
            com.oshi.messenger.data.repository.MessageRepository.reloadBlockedCache()
        }
    }

    private fun handleFullSync(json: JSONObject) {
        Log.d(TAG, "Handling full sync from linked device")
        if (json.has("messages")) {
            handleMessageSync(json)
        }
        if (json.has("settings")) {
            handleSettingsSync(json)
        }
        if (json.has("contacts")) {
            handleContactsSync(json)
        }
        if (json.has("profile_links")) {
            handleProfileLinksSync(json)
        }
    }

    // =========================================================================
    // PROFILE LINKS SYNC (last-write-wins)
    // =========================================================================
    //
    // Two devices on the same identity edit links independently. To avoid
    // clobbering each other we stamp every save with `updatedAt` and only
    // apply incoming links if the remote stamp is strictly newer. Mirrors
    // iOS ProfileLinks.updatedAt + MultiDeviceSyncManager.swift conflict
    // resolution in pullProfileFromSync.

    private val profileLinksPrefs by lazy {
        context.getSharedPreferences("oshi_profile_links", Context.MODE_PRIVATE)
    }

    /**
     * Push the current profile links to all linked devices. Call this from the
     * editor's Save button — does nothing when no devices are linked yet.
     */
    fun syncProfileLinksToLinkedDevices() {
        scope.launch {
            try {
                if (_linkedDevices.value.isEmpty()) return@launch
                val updatedAt = profileLinksPrefs.getLong("profile_links_updated_at", 0L)
                val payload = JSONObject().apply {
                    put("type", "profile_links")
                    put("deviceId", deviceId)
                    put("timestamp", System.currentTimeMillis())
                    put("updatedAt", updatedAt)
                    put("website", profileLinksPrefs.getString("profile_link_website", "") ?: "")
                    put("linkedin", profileLinksPrefs.getString("profile_link_linkedin", "") ?: "")
                    put("twitter", profileLinksPrefs.getString("profile_link_twitter", "") ?: "")
                    put("instagram", profileLinksPrefs.getString("profile_link_instagram", "") ?: "")
                    put("github", profileLinksPrefs.getString("profile_link_github", "") ?: "")
                }
                sendSyncToLinkedDevices(payload)
                Log.d(TAG, "Profile links synced to ${_linkedDevices.value.size} linked device(s)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync profile links: ${e.message}")
            }
        }
    }

    /** Upload only the encrypted profile archive after a local profile edit.
     * This avoids making a nickname change wait for the periodic full-sync job. */
    fun syncProfileNow() {
        scope.launch {
            uploadProfileForSync()
        }
    }

    private fun handleProfileLinksSync(json: JSONObject) {
        try {
            val incomingUpdatedAt = json.optLong("updatedAt", 0L)
            val localUpdatedAt = profileLinksPrefs.getLong("profile_links_updated_at", 0L)
            if (incomingUpdatedAt <= localUpdatedAt) {
                Log.d(TAG, "Profile links: local copy is newer (incoming=$incomingUpdatedAt, local=$localUpdatedAt) — skipping")
                return
            }
            profileLinksPrefs.edit()
                .putString("profile_link_website", json.optString("website", ""))
                .putString("profile_link_linkedin", json.optString("linkedin", ""))
                .putString("profile_link_twitter", json.optString("twitter", ""))
                .putString("profile_link_instagram", json.optString("instagram", ""))
                .putString("profile_link_github", json.optString("github", ""))
                .putLong("profile_links_updated_at", incomingUpdatedAt)
                .apply()
            Log.d(TAG, "Profile links updated from linked device (updatedAt=$incomingUpdatedAt)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply profile links sync: ${e.message}")
        }
    }

    // =========================================================================
    // PERSISTENCE
    // =========================================================================

    private fun loadLinkedDevices() {
        try {
            val devicesJson = prefs.getString("linked_devices", null) ?: return
            val jsonArray = JSONArray(devicesJson)

            val devices = mutableListOf<LinkedDevice>()
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                devices.add(LinkedDevice(
                    deviceId = json.getString("deviceId"),
                    deviceName = json.getString("deviceName"),
                    publicKey = json.getString("publicKey"),
                    linkedAt = json.getLong("linkedAt")
                ))
            }

            _linkedDevices.value = devices
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load linked devices: ${e.message}")
        }
    }

    private fun saveLinkedDevices() {
        try {
            val jsonArray = JSONArray()
            _linkedDevices.value.forEach { device ->
                jsonArray.put(JSONObject().apply {
                    put("deviceId", device.deviceId)
                    put("deviceName", device.deviceName)
                    put("publicKey", device.publicKey)
                    put("linkedAt", device.linkedAt)
                })
            }
            prefs.edit().putString("linked_devices", jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save linked devices: ${e.message}")
        }
    }

    // =========================================================================
    // SERVER-MEDIATED SYNC CLIENT — the live /api/sync/* blob routes
    // =========================================================================
    //
    // This is the half of iOS's design that actually exists on the server:
    // each device encrypts an archive with a key derived from the shared
    // private key and PUTs it at /api/sync/<kind>/<base64url(publicKey)>; the
    // other device GETs it and merges. No pairing step, no link code — sharing
    // the recovery key IS the pairing, which is why the peer-to-peer link-code
    // scheme above never had a caller and never could work.

    @Volatile
    private var isServerSyncing = false

    val thisDeviceName: String get() = deviceName

    /**
     * __DEVSYNC_DIRECT_2026_09_22__ Direct own-device sync (docs/OSHI_DEVICE_SYNC_DIRECT.md, D8).
     * While `/v2/config.devsync_enabled` (or its local override) is on, the 30 s `/api/sync`
     * uploads and pulls STOP: no stored sync data. Runtime only and NOT persisted, so turning
     * the server flag off again restores the legacy path (the rollback lever of phase 1).
     */
    @Volatile
    var directSyncActive: Boolean = false
        private set

    fun setDirectSyncActive(active: Boolean) {
        if (directSyncActive == active) return
        directSyncActive = active
        if (active) stopServerSync() else startServerSync()
        Log.i(TAG, if (active) "Legacy /api/sync stopped: direct device sync is on" else "Legacy /api/sync resumed")
    }

    fun setSyncEnabled(enabled: Boolean) {
        syncPrefs.edit().putBoolean("oshi_sync_enabled", enabled).apply()
        _isSyncEnabled.value = enabled
        if (enabled) startServerSync() else stopServerSync()
    }

    /** Called once the identity is known. iOS: `initialize(publicKey:)` (swift:168-178). */
    fun startServerSync() {
        if (!_isSyncEnabled.value) return
        if (directSyncActive) return
        if (serverSyncJob?.isActive == true) return
        serverSyncJob = scope.launch {
            // iOS delays the first cycle ~5 s so the stores finish loading (swift:110).
            delay(5_000)
            while (isActive) {
                performServerSync()
                // 30 s on screen (iOS `MultiDeviceSyncManager.swift:118`),
                // ×PollBackoff.BACKGROUND_AUX_MULTIPLIER when backgrounded. iOS's own
                // timer stops entirely on background — the process is suspended — and
                // it makes up for it with a `performFullSync()` on the foreground
                // burst (`SecureWeb3MessengerApp.swift:334-336`). Slowing rather than
                // stopping keeps a second device converging while the app is pocketed.
                delay(AppPollState.auxSyncDelayMs(SYNC_INTERVAL_MS))
            }
        }
        Log.d(TAG, "Server-mediated sync started")
    }

    fun stopServerSync() {
        serverSyncJob?.cancel()
        serverSyncJob = null
    }

    /**
     * One cycle. iOS pulls first and uploads the merged result afterwards
     * (swift:141-162) so the archive always holds the union of both devices —
     * uploading first would overwrite the other device's slot with a strict
     * subset and silently delete its history.
     */
    fun performServerSync() {
        if (!_isSyncEnabled.value) return
        if (directSyncActive) return
        if (isServerSyncing) {
            Log.d(TAG, "Sync already in progress, skipping")
            return
        }
        scope.launch {
            isServerSyncing = true
            _syncStatus.value = SyncStatus.Syncing
            // Counted, not caught. See [SyncCycleTally]: the helpers below
            // swallow their own failures, so a try/catch here can never see an
            // outage and "Synced" was stamped on a device that had never
            // reached the server.
            val tally = SyncCycleTally()
            currentTally = tally
            try {
                pullContactsFromSync()
                pullMessagesFromSync()
                pullProfileFromSync()

                uploadContactsForSync()
                uploadMessagesForSync()
                uploadProfileForSync()
            } catch (e: Exception) {
                Log.e(TAG, "Server sync failed: ${e.message}")
                tally.serverFailed()
            } finally {
                currentTally = null
                isServerSyncing = false
            }

            when {
                tally.didSync -> {
                    val now = System.currentTimeMillis()
                    _lastSyncTime.value = now
                    // 💾 This line is the `queued-work-loo` thread in the battery
                    // profile. It used to run on EVERY successful cycle — a new
                    // timestamp every 30 s, so the value always differed, so
                    // SharedPreferences really did rewrite and fsync
                    // `oshi_multidevice_sync.xml` 2 880 times a day. Confirmed on
                    // device: logcat carried
                    // `SharedPreferencesImpl: Time required to fsync
                    //  /data/user/0/com.oshi.messenger/shared_prefs/oshi_multidevice_sync.xml`.
                    //
                    // The in-memory `_lastSyncTime` is what the UI binds to and it is
                    // still exact. The persisted copy only has to survive process
                    // death well enough to render "last synced …", so it is written at
                    // most once per LAST_SYNC_PERSIST_INTERVAL_MS.
                    if (now - lastSyncPersistedAtMs >= LAST_SYNC_PERSIST_INTERVAL_MS) {
                        lastSyncPersistedAtMs = now
                        syncPrefs.edit().putLong("last_sync", now).apply()
                    }
                    _syncStatus.value = SyncStatus.Success
                }
                tally.idle -> {
                    // No identity yet, or nothing to send and nothing stored.
                    // Neither a success nor a failure — say nothing.
                    _syncStatus.value = SyncStatus.Idle
                }
                else -> {
                    Log.w(TAG, "Server sync reached nothing (${tally.failed} failed call(s))")
                    _syncStatus.value = SyncStatus.Error("Sync server unreachable")
                }
            }
        }
    }

    /**
     * The tally for the cycle currently running, so [uploadBlob] / [pullBlob]
     * can record their outcome without changing their return types (a dozen
     * call sites treat `false`/`null` as "nothing to do" and must keep doing
     * so). Only one cycle runs at a time — `isServerSyncing` guarantees it.
     */
    @Volatile
    private var currentTally: SyncCycleTally? = null

    private fun myPublicKeyOrNull(): String? =
        runCatching { cryptoManager.getPublicKeyString() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun syncUrl(kind: String, pubKey: String, withDeviceId: Boolean): String {
        val base = "$SYNC_BASE_URL/api/sync/$kind/${urlEncodedKey(pubKey)}"
        return if (withDeviceId) "$base?deviceId=$deviceId" else base
    }

    /** POST an encrypted archive. Returns true on HTTP 200. */
    private suspend fun uploadBlob(kind: String, payload: String): Boolean = withContext(Dispatchers.IO) {
        if (directSyncActive) return@withContext false // __DEVSYNC_DIRECT_2026_09_22__ no stored sync
        val pubKey = myPublicKeyOrNull() ?: return@withContext false
        val encrypted = encryptSyncData(payload.toByteArray(Charsets.UTF_8)) ?: return@withContext false
        try {
            val body = buildSyncUploadBody(deviceId, encrypted).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = okhttp3.Request.Builder()
                .url(syncUrl(kind, pubKey, withDeviceId = false))
                .post(body)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    currentTally?.serverReached()
                } else {
                    Log.w(TAG, "sync upload $kind → HTTP ${resp.code}")
                    currentTally?.serverFailed()
                }
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "sync upload $kind failed: ${e.message}")
            currentTally?.serverFailed()
            false
        }
    }

    /** GET + decrypt an archive. Returns the decrypted JSON text, or null when empty. */
    private suspend fun pullBlob(kind: String): String? = withContext(Dispatchers.IO) {
        if (directSyncActive) return@withContext null // __DEVSYNC_DIRECT_2026_09_22__ no stored sync
        val pubKey = myPublicKeyOrNull() ?: return@withContext null
        try {
            val req = okhttp3.Request.Builder()
                .url(syncUrl(kind, pubKey, withDeviceId = true))
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    currentTally?.serverFailed()
                    return@withContext null
                }
                // 2xx counts as a real sync even when the archive is empty —
                // "nothing stored yet" IS an answer from the server.
                currentTally?.serverReached()
                val text = resp.body?.string().orEmpty()
                // Server answers {"success":true,"data":null} when nothing is stored.
                val encrypted = JSONObject(text).optString("data").takeIf {
                    it.isNotEmpty() && it != "null"
                } ?: return@withContext null
                decryptSyncEnvelope(encrypted)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sync pull $kind failed: ${e.message}")
            currentTally?.serverFailed()
            null
        }
    }

    // ---- contacts: iOS stores a plain [publicKey: alias] map (swift:567-604) ----

    private suspend fun uploadContactsForSync() {
        val db = AppDatabase.getInstance(context)
        val contacts = db.contactDao().getAllContactsList()
        if (contacts.isEmpty()) return
        val map = JSONObject()
        for (c in contacts) if (c.alias.isNotEmpty()) map.put(c.publicKey, c.alias)
        if (map.length() == 0) return
        uploadBlob("contacts", map.toString())
    }

    private suspend fun pullContactsFromSync() {
        val text = pullBlob("contacts") ?: return
        val map = runCatching { JSONObject(text) }.getOrNull() ?: return
        val db = AppDatabase.getInstance(context)
        var added = 0
        for (key in map.keys()) {
            val alias = map.optString(key)
            if (alias.isEmpty()) continue
            // iOS fills gaps only, never overwrites a local alias (swift:634-640).
            if (db.contactDao().getContactByPublicKey(key) != null) continue
            runCatching { db.contactDao().insert(Contact(publicKey = key, alias = alias)) }
                .onSuccess { added++ }
        }
        if (added > 0) Log.d(TAG, "📥 Merged $added contacts from sync archive")
    }

    // ---- 1-to-1 messages: iOS stores [SecureMessage] (swift:690-844) ----

    /** iOS caps the body at ~4 MB and 1 MB of media per message (swift:718-719). */
    private suspend fun uploadMessagesForSync() {
        val db = AppDatabase.getInstance(context)
        val contacts = db.contactDao().getAllContactsList()
        if (contacts.isEmpty()) return
        val all = mutableListOf<Message>()
        for (c in contacts) all += db.messageDao().getMessagesForContact(c.publicKey).first()
        if (all.isEmpty()) return

        val perMessageMediaCap = 1_000_000
        val totalBodyCap = 4_000_000
        var running = 0
        val arr = JSONArray()
        for (m in all.distinctBy { it.id }.sortedByDescending { it.timestamp }) {
            val media = m.mediaData?.takeIf { it.size <= perMessageMediaCap }
            val obj = messageToSyncJson(
                id = m.id,
                senderPublicKey = m.senderPublicKey,
                recipientPublicKey = m.recipientPublicKey,
                content = m.content,
                epochMillis = m.timestamp,
                isRead = m.isRead,
                deliveryStatus = deliveryStatusToIos(m.isRead, m.isDelivered, m.isFailed, m.isFromMe),
                mediaTypeRaw = mediaTypeToIos(m.mediaType?.name),
                mediaBase64 = media?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                editedAtMillis = m.editedAt,
                editedContent = m.editedContent,
                isDeletedForEveryone = m.isDeletedForEveryone,
                replyToId = m.replyToId
            )
            val size = obj.toString().length
            if (running + size > totalBodyCap) break
            running += size
            arr.put(obj)
        }
        if (arr.length() == 0) return
        uploadBlob("messages", arr.toString())
        Log.d(TAG, "📤 Uploaded ${arr.length()} messages to the sync archive (~${running / 1024} KB)")
    }

    private suspend fun pullMessagesFromSync() {
        val text = pullBlob("messages") ?: return
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return
        val db = AppDatabase.getInstance(context)
        val me = myPublicKeyOrNull().orEmpty()
        var added = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            if (db.messageDao().getMessageById(id) != null) continue
            val sender = o.optString("senderPublicKey")
            val recipient = o.optString("recipientPublicKey")
            if (sender.isEmpty() && recipient.isEmpty()) continue
            val mediaRaw = mediaTypeFromIos(o.optString("mediaType").takeIf { it.isNotEmpty() })
            val message = Message(
                id = id,
                senderPublicKey = sender,
                recipientPublicKey = recipient,
                content = o.optString("plaintextContent"),
                timestamp = epochMillisFromAppleTime(o.optDouble("timestamp", 0.0)),
                isFromMe = sender == me,
                isRead = o.optBoolean("isRead", false),
                isDelivered = o.optString("deliveryStatus") in listOf("delivered", "read"),
                mediaType = mediaRaw?.let {
                    runCatching { com.oshi.messenger.data.model.MediaType.valueOf(it) }.getOrNull()
                },
                mediaData = o.optString("mediaAttachment").takeIf { it.isNotEmpty() }
                    ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() },
                replyToId = o.optString("replyToId").takeIf { it.isNotEmpty() },
                editedContent = o.optString("editedContent").takeIf { it.isNotEmpty() },
                isDeletedForEveryone = o.optBoolean("isDeletedForEveryone", false)
            )
            runCatching { db.messageDao().insert(message) }.onSuccess { added++ }
        }
        if (added > 0) Log.d(TAG, "📥 Merged $added messages from the sync archive")
    }

    // ---- profile: iOS stores {userName, profileImageData, profileLinks} (swift:1095-1190) ----

    private val profilePrefs by lazy {
        context.getSharedPreferences("profile", Context.MODE_PRIVATE)
    }

    private suspend fun uploadProfileForSync() {
        val payload = JSONObject()
        val name = runCatching {
            context.dataStore.data.first()[stringPreferencesKey("display_name")]
        }.getOrNull().orEmpty()
        if (name.isNotEmpty()) payload.put("userName", name)
        profilePrefs.getString("profile_picture", null)?.let { payload.put("profileImageData", it) }
        // Preserve whatever iOS wrote for its own `ProfileLinks` blob rather than
        // dropping it: Android has no equivalent Codable and re-uploading without
        // this key would erase the iPhone's links from the shared archive.
        syncPrefs.getString("remote_profile_links", null)?.let { payload.put("profileLinks", it) }
        if (payload.length() == 0) return
        uploadBlob("profile", payload.toString())
    }

    private suspend fun pullProfileFromSync() {
        val text = pullBlob("profile") ?: return
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        o.optString("userName").takeIf { it.isNotEmpty() }?.let { name ->
            runCatching {
                context.dataStore.edit { it[stringPreferencesKey("display_name")] = name }
            }
        }
        o.optString("profileImageData").takeIf { it.isNotEmpty() }?.let { b64 ->
            profilePrefs.edit().putString("profile_picture", b64).apply()
        }
        o.optString("profileLinks").takeIf { it.isNotEmpty() }?.let { links ->
            syncPrefs.edit().putString("remote_profile_links", links).apply()
        }
        Log.d(TAG, "📥 Profile merged from the sync archive")
    }

    fun cleanup() {
        stopAutoSync()
        stopServerSync()
        scope.cancel()
    }

    // =========================================================================
    // SYNC ENVELOPE (iOS-compatible AES-256-GCM)
    // =========================================================================
    //
    // Mirrors iOS MultiDeviceSyncManager.swift:880-912 exactly so iOS and Android
    // devices sharing the same identity can sync cross-platform.
    //
    // Key derivation:
    //   key = SHA-256(privateKeyBytes || "oshi-sync-key".utf8)
    //
    // Encryption:
    //   sealed = AES-GCM(key, nonce=12B, aad=empty).seal(payload)
    //   encryptedData = Base64( nonce || ciphertext || tag )   // matches CryptoKit .combined
    //
    // Envelope:
    //   { "type": <syncType>, "deviceId": <myDeviceId>, "encryptedData": <base64> }

    private fun deriveSyncKey(): ByteArray? {
        return try {
            val priv = cryptoManager.exportPrivateKeyBytes()
            val salt = "oshi-sync-key".toByteArray(Charsets.UTF_8)
            val md = MessageDigest.getInstance("SHA-256")
            md.update(priv)
            md.update(salt)
            md.digest()
        } catch (e: Exception) {
            Log.e(TAG, "deriveSyncKey failed: ${e.message}")
            null
        }
    }

    /**
     * Build the iOS-compatible JSON envelope for outgoing sync data.
     * Returns null if encryption setup fails — caller should fall back to legacy prefix.
     */
    private fun buildSyncEnvelope(payload: JSONObject): String? {
        val encrypted = encryptSyncData(payload.toString().toByteArray(Charsets.UTF_8)) ?: return null
        val syncType = payload.optString("type", "full")
        val envelope = JSONObject().apply {
            put("type", syncType)
            put("deviceId", deviceId)
            put("encryptedData", encrypted)
        }
        return envelope.toString()
    }

    /**
     * AES-256-GCM encrypt, returns base64( nonce(12) || ciphertext || tag(16) )
     * to match Apple CryptoKit's AES.GCM.SealedBox.combined layout.
     */
    private fun encryptSyncData(data: ByteArray): String? {
        val key = deriveSyncKey() ?: return null
        return try {
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            val ctAndTag = cipher.doFinal(data)  // JCE appends the 16B tag here
            val combined = ByteArray(nonce.size + ctAndTag.size)
            System.arraycopy(nonce, 0, combined, 0, nonce.size)
            System.arraycopy(ctAndTag, 0, combined, nonce.size, ctAndTag.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "encryptSyncData failed: ${e.message}")
            null
        }
    }

    /**
     * AES-256-GCM decrypt of the iOS CryptoKit .combined layout
     * (nonce(12) || ciphertext || tag(16)), base64 input.
     */
    private fun decryptSyncEnvelope(base64String: String): String? {
        val key = deriveSyncKey() ?: return null
        return try {
            val combined = Base64.decode(base64String, Base64.NO_WRAP)
            if (combined.size < 12 + 16) return null
            val nonce = combined.copyOfRange(0, 12)
            val ctAndTag = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            String(cipher.doFinal(ctAndTag), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "decryptSyncEnvelope failed: ${e.message}")
            null
        }
    }
}

/**
 * Linked device data
 */
data class LinkedDevice(
    val deviceId: String,
    val deviceName: String,
    val publicKey: String,
    val linkedAt: Long
)

/**
 * Fan a sync payload out to [devices], or refuse.
 *
 * [buildEnvelope] returns null when the sync key cannot be derived. That is a REFUSAL,
 * not a downgrade. Both call sites used to read
 * `buildSyncEnvelope(payload) ?: "$SYNC_PREFIX$payload"`: a crypto failure silently
 * swapped the AES-256-GCM envelope for the raw sync JSON — the user's contacts, message
 * bodies and settings — and sent it anyway. A send that cannot be encrypted must not
 * happen; the caller reports the failure and keeps the pending data for the next attempt.
 *
 * Returns false when nothing was sent. Per-device transport failures belong inside
 * [send]; they are not a refusal.
 *
 * Top-level so it is reachable from a JVM unit test without a Hilt-built manager.
 */
suspend fun fanOutSync(
    payload: JSONObject,
    devices: List<LinkedDevice>,
    buildEnvelope: (JSONObject) -> String?,
    send: suspend (device: LinkedDevice, content: String) -> Unit
): Boolean {
    val content = buildEnvelope(payload) ?: return false
    devices.forEach { device -> send(device, content) }
    return true
}

/**
 * Device link code for pairing
 */
data class DeviceLinkCode(
    val code: String,
    val expiresAt: Long,
    val deviceId: String,
    val deviceName: String
) {
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAt
    val remainingSeconds: Long get() = maxOf(0, (expiresAt - System.currentTimeMillis()) / 1000)
}

/**
 * Sync status
 */
sealed class SyncStatus {
    object Idle : SyncStatus()
    object Syncing : SyncStatus()
    /** iOS `SyncStatus.success` (MultiDeviceSyncManager.swift:36). */
    object Success : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}

/**
 * Outcome tally for one server-sync cycle.
 *
 * 🔧 P1 FIX (F-5) — a virgin install with no linked device and no sync ever
 * performed rendered "Synced · Last sync: <today>". `performServerSync()`
 * stamped `_lastSyncTime`, `last_sync` and `SyncStatus.Success` inside a `try`
 * block whose `catch` was unreachable: every HTTP helper swallows its own
 * failure (`uploadBlob` returns false, `pullBlob` returns null), so nothing
 * could throw and Success was unconditional. The sync routes 404 on the live
 * server, so the user was told "Synced" by a subsystem whose endpoints do not
 * exist.
 *
 * iOS has no such shape: `syncFromCloud()` requires `statusCode == 200` and a
 * successful decode BEFORE it writes `lastSyncDate` / `oshi_last_sync` /
 * `.success` (MultiDeviceSyncManager.swift:355-379).
 *
 * A cycle may stamp a sync only if at least one request actually reached the
 * server and came back 2xx. Pure and Android-free so it unit-tests on the JVM.
 */
class SyncCycleTally {
    var reached: Int = 0
        private set
    var failed: Int = 0
        private set

    /** An HTTP call came back 2xx. */
    fun serverReached() { reached++ }

    /** An HTTP call failed, timed out, or came back non-2xx. */
    fun serverFailed() { failed++ }

    /** Only a cycle that reached the server may stamp `last_sync`. */
    val didSync: Boolean get() = reached > 0

    /** Nothing was even attempted — no identity yet, or nothing to send. */
    val idle: Boolean get() = reached == 0 && failed == 0
}
