package com.oshi.messenger.network.vps

import android.util.Base64
import android.util.Log
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.encryption.GroupEnvelopeV3
import com.oshi.messenger.network.encryption.toHexString
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VPSClient - Server Communication Module
 *
 * 🔧 FIXED: Now uses iOS-compatible IPFS-based message queue:
 * - POST /api/queue/{recipientKey}/{ipfsHash} - queue message
 * - GET /api/pending/{myKey} - poll for pending messages
 *
 * Server URLs (same as iOS):
 * - Public: https://oshi-messenger.com
 * - Tor: http://qak57gcjrxwvy3nz7lbgizmxjffgaekcfz7yimw6z6dx7y3yyc7zriid.onion
 */
@Singleton
class VPSClient @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val v2Signer: com.oshi.messenger.network.v2.V2Signer,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) {
    companion object {
        private const val TAG = "VPSClient"
        // SECURITY (CSPN 2026-09): compile-time guard. Must stay false in release.
        // When false, message plaintext / key material is never handed to Log.*.
        private const val LOG_SENSITIVE = false

        // Same URLs as iOS
        private val VPS_URL = BuildConfig.VPS_URL  // Port 80 for IPFS proxy

        // 🔧 IPFS-based queue endpoints (primary delivery — via nginx port 443)
        private const val ENDPOINT_QUEUE = "/api/queue"    // POST /{recipientKey}/{hash}
        private const val ENDPOINT_PENDING = "/api/pending" // GET /{myKey}

        // Pinata upload endpoint (via VPS proxy on port 80) - MUST match iOS format
        private const val PINATA_UPLOAD_ENDPOINT = "/api/pinata/pinning/pinJSONToIPFS"

        // Call signaling — routed through nginx /api/call/ → localhost:8083
        // (Port 8083 is plain HTTP, so direct HTTPS access fails with TLS error)
        private const val CALL_API_PREFIX = "/api/call"
        private const val ENDPOINT_CALL_SIGNAL = "/signal"
        private const val ENDPOINT_CALL_AUDIO = "/audio"

        // Push notifications — routed through nginx /push/ → localhost:8084
        // Used to wake up iOS devices for incoming calls via APNs VoIP push
        // Also used to send FCM push to Android devices for messages
        private const val PUSH_API_PREFIX = "/push"
        private const val ENDPOINT_VOIP_PUSH = "/send-voip-push"
        private const val ENDPOINT_SEND_PUSH = "/send-push"

        // 🔐 IPFS Proxy endpoint (hides user IP)
        private const val IPFS_PROXY_ENDPOINT = "/api/ipfs/proxy/"

        // Direct IPFS gateways (fallback only - exposes user IP!)
        private val IPFS_GATEWAYS = listOf(
            "https://gateway.pinata.cloud/ipfs/",
            "https://dweb.link/ipfs/",
            "https://ipfs.io/ipfs/",
            "https://w3s.link/ipfs/",
            "https://nftstorage.link/ipfs/"
        )

        // Timeouts
        private const val CONNECT_TIMEOUT = 10L
        private const val READ_TIMEOUT = 30L
        private const val WRITE_TIMEOUT = 30L

        // Polling intervals - fast enough for real-time feel, still battery-friendly
        // iOS uses 2s polling. 5s is a good balance for Android (background services cost more).
        private const val MESSAGE_POLL_INTERVAL = 5000L  // 5 seconds
        private const val CALL_POLL_INTERVAL = 3000L      // 3 seconds (minimum for calls)

        // Rate limit tracking
        private const val RATE_LIMIT_BACKOFF_MS = 60000L  // 60s backoff when rate limited
    }

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _useTor = MutableStateFlow(false)
    val useTor: StateFlow<Boolean> = _useTor.asStateFlow()

    // HTTP Client
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // __CALL_PUSH_AUTH_2026_09_23__ Signs every /api/call/, /push/ request and the
        // call WebSocket upgrade with the v2-bound Ed25519 key (CALL_PUSH_AUTH_CONTRACT §3).
        .addInterceptor(com.oshi.messenger.network.auth.CallPushSigning.interceptor)
        // __LEAK_FIX_2026_09_25__ `OSHI-Android/<ver>` instead of `okhttp/x` on our own
        // host, so the server can split the queue / pin / push traffic per build.
        .addInterceptor(com.oshi.messenger.network.auth.OshiUserAgent.interceptor)
        .build()

    // Coroutine scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Polling jobs
    private var messagePollingJob: Job? = null
    private var callPollingJob: Job? = null

    // Rate limit tracking
    private var isRateLimited = false
    private var rateLimitEndTime = 0L

    // Callbacks
    private var onMessageReceived: ((VPSMessage) -> Unit)? = null
    private var onCallSignalReceived: ((CallSignal) -> Unit)? = null

    // Group envelope decryption: returns list of (groupId, groupKey) candidates
    var groupKeyProvider: ((hint: String?) -> List<Pair<String, ByteArray>>)? = null
    /**
     * CV-002 — opens a group envelope v4, which has NO group key to try.
     *
     * Supplied as a callback for the same reason `groupKeyProvider` is: this
     * class must not reach into the ratchet or the session store. The opener
     * returns the decrypted payload JSON, or null when the envelope is simply
     * not addressed to us — which is the ordinary case for a group we are not in
     * and must stay quiet.
     */
    var groupEnvelopeV4Opener: ((envelope: JSONObject) -> String?)? = null
    // Called when a group envelope is successfully decrypted
    var onGroupEnvelopeDecrypted: ((senderKey: String, groupMessageContent: String) -> Unit)? = null
    // Internal flag: was the last group envelope handled via callback (true) or failed decryption (false)?
    private var lastGroupEnvelopeHandled: Boolean = false
    // Called to try decrypting an iOS-format delivery receipt {"encrypted":"base64..."}
    var onEncryptedReceiptReceived: ((encryptedBase64: String) -> Unit)? = null
    // Called when an iOS group_update broadcast is received (group rename, member change, picture, etc.)
    // groupType: iOS GroupType raw value — "public", "admin_only", or "collaborative"
    // 📌🎨 `pinnedMessageId`/`hasPinnedField`/`pinnedBy`/`avatar`/`hasAvatarField` complete the
    // iOS `MessageGroup` payload (GroupMessaging.swift:104,112, CodingKeys :259). They rode the
    // wire from day one and this parser threw them away, so an iPhone pinning a message in a
    // mixed group changed nothing on any Android member. The `has*` flags separate
    // "explicitly cleared" from "not carried by this update" — iOS clears on omission
    // (swift:800-820), but an Android-native minimal update must not.
    var onGroupUpdateBroadcast: ((groupId: String, groupName: String?, membersJson: String?, groupImageBytes: ByteArray?, adminPublicKey: String?, groupType: String?, groupWallpaperBytes: ByteArray?, groupWallpaperUpdatedBy: String?, groupWallpaperUpdatedAtMs: Long?, pinnedMessageId: String?, hasPinnedField: Boolean, pinnedBy: String?, avatar: String?, hasAvatarField: Boolean) -> Unit)? = null

    // ============================================================================
    // CALL WEBSOCKET (matches iOS VoiceCallManager WebSocket)
    // ============================================================================

    private var callWebSocket: WebSocket? = null
    @Volatile
    var isCallWebSocketConnected: Boolean = false
        private set
    @Volatile
    var serverSupportsBinary: Boolean = false
        private set
    private var callWebSocketReconnectJob: Job? = null
    // Callback for audio packets received via WebSocket
    var onCallAudioReceived: ((ByteArray) -> Unit)? = null
    // Callback for call signals received via WebSocket
    var onCallSignalViaWs: ((CallSignal) -> Unit)? = null
    // Callback for WebSocket state changes (for smart polling)
    var onWebSocketStateChanged: ((Boolean) -> Unit)? = null

    // Identity
    private var myPublicKey: String = ""

    private val currentUrl: String
        get() = VPS_URL  // Tor relay path removed for Play store compliance

    // WebSocket URL for calls (nginx /api/call/ → localhost:8083 with upgrade support)
    private val callWebSocketUrl: String
        get() {
            val wsScheme = if (currentUrl.startsWith("https")) "wss" else "ws"
            val host = currentUrl
                .removePrefix("http://")
                .removePrefix("https://")
            return "$wsScheme://$host$CALL_API_PREFIX/"
        }

    // Call server URL (nginx proxies /api/call/ → localhost:8083)
    private val callServerUrl: String
        get() = "${currentUrl}${CALL_API_PREFIX}"

    // ============================================================================
    // INITIALIZATION
    // ============================================================================

    fun initialize(publicKey: String) {
        myPublicKey = publicKey
        Log.d(TAG, "🚀 VPSClient initialized")
        Log.d(TAG, "   Public key: ${publicKey.take(16)}...")
        Log.d(TAG, "   VPS URL: $VPS_URL")

        // Register with server
        scope.launch {
            register()
        }
    }

    fun setUseTor(enabled: Boolean) {
        _useTor.value = enabled
        Log.d(TAG, "🧅 Tor mode: ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    // ============================================================================
    // INITIALIZATION (iOS uses IPFS queue - no explicit registration needed)
    // ============================================================================

    /**
     * 🔧 FIXED: iOS doesn't use explicit registration
     * Just verify VPS is reachable by checking health endpoint
     */
    /**
     * F6 — fetch short-lived TURN credentials from /api/call/turn-creds.
     * Returns Triple(username, password, ttlSeconds), or null on failure.
     * Caller is expected to cache and refresh ~5 min before expiry.
     */
    suspend fun getTurnCreds(): Triple<String, String, Int>? = withContext(Dispatchers.IO) {
        try {
            val url = "$callServerUrl/turn-creds"
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    Log.w(TAG, "🔑 /turn-creds returned HTTP ${it.code}")
                    return@withContext null
                }
                val body = it.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                Triple(
                    json.getString("username"),
                    json.getString("password"),
                    json.optInt("ttl", 3600)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "🔑 /turn-creds fetch failed: ${e.message}")
            null
        }
    }

    suspend fun register(): Boolean {
        return try {
            val url = "$currentUrl/health"
            Log.d(TAG, "📡 Checking VPS health: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }

            if (response.isSuccessful) {
                _connectionState.value = ConnectionState.CONNECTED
                Log.d(TAG, "✅ VPS is reachable")
                true
            } else {
                Log.w(TAG, "⚠️ VPS returned ${response.code}")
                _connectionState.value = ConnectionState.CONNECTED // Still mark as connected
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ VPS health check failed: ${e.message}")
            _connectionState.value = ConnectionState.ERROR
            false
        }
    }

    // ============================================================================
    // MESSAGING (IPFS queue via nginx → port 8081)
    // ============================================================================

    /**
     * Send message via IPFS queue (upload to IPFS then notify VPS).
     * The message queue server (port 8081) only supports IPFS-based delivery.
     * Nginx handles TLS and proxies to the queue server.
     */
    /**
     * @param originalTimestampMs the moment the message was WRITTEN, in Unix millis.
     *   It becomes the envelope's `timestamp`, which iOS reads as the bubble's date
     *   (`MessageManager.swift:4184` — `Date(timeIntervalSince1970: timestampInterval)`).
     *
     *   __RESEND_TIMESTAMP_2026_09_17__ Same defect as the v2 path, and WORSE here:
     *   this is the fallback, so it only runs after the v2 attempt has already spent
     *   its prekey fetch and relay timeout. Stamping `System.currentTimeMillis()` at
     *   upload time therefore dated the message at the end of the longest possible
     *   send, and the recipient sorted it below texts written after it. Null keeps
     *   "now", which is correct for the control traffic (sync, group updates,
     *   scheduled calls) that makes up most callers.
     */
    suspend fun sendMessage(
        recipientPublicKey: String,
        encryptedContent: ByteArray,
        messageId: String = java.util.UUID.randomUUID().toString(),
        suppressPush: Boolean = false,
        originalTimestampMs: Long? = null,
        /**
         * __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Which conversation this
         * belongs to: a group's id when it is group traffic, otherwise the
         * peer's key, which is why that is the default.
         *
         * It exists because the two are NOT the same for a group. Group fan-out
         * calls this once per member, so defaulting to the member's key would
         * let one member's private 1:1 switch silently stop a group the switch
         * was never aimed at — a restriction nobody asked for is as wrong as a
         * leak nobody noticed.
         */
        conversationId: String = recipientPublicKey,
    ): Boolean {
        // __NEVER_OVER_THE_INTERNET_2026_10_20__ One of the doors to the
        // internet. Every one of them is closed by the same call, because the
        // promise is "nothing of mine leaves over the network", and the door
        // somebody forgot IS the broken promise. Parity: iOS
        // `TransportPolicy.internetAllowedForSending`, nine gates.
        //
        // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ It asks about THIS
        // conversation now, not only about the app. The v2 lane has asked since
        // the feature landed; this legacy lane is where a 1:1 falls back when
        // the peer has no v2 bundle, and a door that knows only the global
        // switch is a door left open for the conversation somebody deliberately
        // took off the relay. Found on iOS first — see `v2TrySend`.
        if (!com.oshi.messenger.service.TransportPreferences.internetAllowedForSending(appContext, conversationId)) {
            Log.d(TAG, "🚫 Internet sending is off for this conversation — not queueing on the relay")
            return false
        }
        // __COVER_FAIL_CLOSED_2026_11_18__ cover mode: no 1:1 traffic on the undisguised relay.
        if (!com.oshi.messenger.service.CoverOutbox.legacyRelayAllowedFor(
                com.oshi.messenger.service.CoverOutbox.coverModeOn, recipientPublicKey, conversationId)) {
            Log.d(TAG, "🕵️ Cover mode — 1:1 legacy relay send refused (not disguised)")
            return false
        }
        Log.d(TAG, "📤 Sending message to ${recipientPublicKey.take(8)}...")

        val success = sendMessageViaIPFS(recipientPublicKey, encryptedContent, messageId, suppressPush,
                                         originalTimestampMs)
        if (success) {
            Log.d(TAG, "✅ Message sent via IPFS queue")
            return true
        }

        Log.e(TAG, "❌ Message delivery failed")
        return false
    }

    /**
     * Send via IPFS queue (upload to IPFS then notify VPS)
     * 🔧 CROSS-PLATFORM FIX: Wraps in v3 encrypted envelope matching iOS format.
     * iOS expects: {v:3, envelope, nonce, hint, from, senderPublicKey}
     */
    private suspend fun sendMessageViaIPFS(
        recipientPublicKey: String,
        encryptedContent: ByteArray,
        messageId: String,
        suppressPush: Boolean = false,
        originalTimestampMs: Long? = null
    ): Boolean {
        return try {
            // Step 1: Build inner payload matching iOS MessageManager.uploadToIPFS() format
            val innerPayload = JSONObject().apply {
                put("id", messageId)
                put("senderAddress", myPublicKey)
                put("recipientAddress", recipientPublicKey)
                // Unix SECONDS (not Apple-epoch: iOS reads this one with
                // `Date(timeIntervalSince1970:)`, MessageManager.swift:4184).
                // __RESEND_TIMESTAMP_2026_09_17__ The message's own date when the
                // caller knows it — see `sendMessage`.
                put("timestamp", (originalTimestampMs ?: System.currentTimeMillis()) / 1000.0)
                put("encryptedContent", Base64.encodeToString(encryptedContent, Base64.NO_WRAP))
            }
            val innerBytes = innerPayload.toString().toByteArray(Charsets.UTF_8)

            // Step 2: Derive envelope key using X25519 shared secret + HKDF
            // Matches iOS: sharedSecret.hkdfDerivedSymmetricKey(salt: "oshi-ipfs-envelope-v2", sharedInfo: Data())
            val sharedSecret = cryptoManager.deriveSharedSecret(recipientPublicKey)
            val envelopeKey = cryptoManager.hkdfDerive(
                sharedSecret = sharedSecret,
                salt = "oshi-ipfs-envelope-v2".toByteArray(Charsets.UTF_8),
                info = ByteArray(0),
                outputLength = 32
            )

            // Step 3: AES-256-GCM encrypt inner payload
            // encryptAESGCM returns: nonce(12) || ciphertext || tag(16)
            val sealed = cryptoManager.encryptAESGCM(innerBytes, envelopeKey)
            val nonce = sealed.copyOfRange(0, 12)
            val ciphertextAndTag = sealed.copyOfRange(12, sealed.size)

            // Step 4: Build v3 envelope (matches iOS MessageManager.uploadToIPFS format)
            val envelopeJson = JSONObject().apply {
                put("v", 3)
                put("envelope", Base64.encodeToString(ciphertextAndTag, Base64.NO_WRAP))
                put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
                put("hint", recipientPublicKey.take(4))         // Recipient hint
                put("from", myPublicKey.take(8))                // Sender hint
                put("senderPublicKey", myPublicKey)             // Full sender key (v3)
            }

            // Step 5: Upload to IPFS and queue for recipient
            //
            // 🔧 Large payloads take the CHUNKED path. The front nginx caps a single POST
            // at 50 MB, so a big video base64'd into this envelope used to 413 outright
            // with no resume of any kind — the send just failed. `ResumableUploader`
            // (a verbatim port of `OSHI/ResumableUploader.swift`) uploads in ≤8 MB
            // chunks and, on interruption, asks the server how many bytes it already
            // holds and continues from exactly there. iOS routes on the same threshold
            // at `MessageManager.swift:4467`.
            //
            // `contentTag` is the MESSAGE ID and must stay so: it is the key of the
            // server-side resume record (`ResumableUploader.swift:64-65`), so a retry
            // with a fresh tag would start a brand-new upload from byte 0 instead of
            // resuming. `upload` is blocking and throws `ResumableException`; the
            // `catch` around this whole function maps that to the same `false` the
            // single-shot failure already returns.
            val envelopeString = envelopeJson.toString()
            val payload = envelopeString.toByteArray(Charsets.UTF_8)
            val ipfsHash = if (
                com.oshi.messenger.service.media.ResumableUploader.shouldUseResumable(payload.size)
            ) {
                Log.d(TAG, "📤 ${payload.size / 1024} KB envelope → resumable chunked upload (tag=${messageId.take(8)})")
                withContext(Dispatchers.IO) {
                    com.oshi.messenger.service.media.ResumableUploaders
                        .create(appContext, currentUrl)
                        .upload(payload, contentTag = messageId)
                }
            } else {
                uploadToIPFS(envelopeString)
            }
            if (ipfsHash == null) {
                Log.e(TAG, "❌ IPFS upload failed")
                return false
            }

            Log.d(TAG, "📦 Uploaded v3 envelope to IPFS: $ipfsHash")
            val queued = notifyVPSQueue(recipientPublicKey, ipfsHash, suppressPush)

            // 🔧 CROSS-PLATFORM FIX (Android → iPhone latency):
            // iOS MessageManager.notifyVPS() fires VoIPPushManager.requestPushNotification()
            // right after queueing, which APNs-wakes the recipient within ~1 s.
            // Android previously stopped at notifyVPSQueue — the iPhone recipient
            // only saw the message on its next 10–30 s polling cycle. That's the
            // entire reason Android→iPhone felt much slower than iOS→iOS or
            // iOS→Android. Now we fire the same /push/send-push payload here so
            // the push service forwards via APNs (iOS recipient) or FCM (Android
            // recipient) transparently. data carries senderPublicKey + messageId
            // so the receiver can deep-link directly to the chat.
            //
            // 🔕 `suppressPush` mirrors iOS `MessageManager.notifyVPS(suppressPush:)`
            // (`OSHI/MessageManager.swift:4797,4808,4888`): the payload still queues,
            // the peer still picks it up on its next poll, but no banner is raised.
            // Live-location update/stop pings take this path — otherwise two people
            // sharing live location banner each other every ~10 seconds.
            if (queued && !suppressPush) {
                val pushData = mutableMapOf<String, String>(
                    "senderPublicKey" to myPublicKey,   // iOS APNs key name
                    "senderKey" to myPublicKey,         // Android FCM key name
                    "messageId" to messageId,
                    // Localized banner: APNs loc-keys so iOS renders the banner
                    // in the RECIPIENT's device language, not the sender's.
                    "bodyLocKey" to "notification.push.body.encrypted",
                    "titleLocKey" to "notification.push.title.message"
                )
                try {
                    sendPushNotification(
                        recipientPublicKey = recipientPublicKey,
                        type = "message",
                        title = "New Message",
                        body = "You have a new encrypted message",
                        data = pushData
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ wake push failed (non-fatal): ${e.message}")
                }
            }
            queued
        } catch (e: Exception) {
            Log.e(TAG, "❌ IPFS send error: ${e.message}")
            false
        }
    }

    /**
     * Upload content to IPFS via Pinata (through VPS proxy)
     * Uses same format as iOS PinataService
     */
    internal suspend fun uploadToIPFS(content: String): String? {
        // __NEVER_OVER_THE_INTERNET_2026_10_20__ One of the doors to the
        // internet. Every one of them is closed by the same call, because the
        // promise is "nothing of mine leaves over the network", and the door
        // somebody forgot IS the broken promise. Parity: iOS
        // `TransportPolicy.internetAllowedForSending`, nine gates.
        if (!com.oshi.messenger.service.TransportPreferences.internetAllowedForSending(appContext)) return null
        return withContext(Dispatchers.IO) {
            try {
                val url = "$currentUrl$PINATA_UPLOAD_ENDPOINT"

                // Parse content as JSON to put in pinataContent
                val contentJson = try {
                    JSONObject(content)
                } catch (e: Exception) {
                    // If content isn't valid JSON, wrap it
                    JSONObject().apply { put("data", content) }
                }

                // Build Pinata-compatible request body (matches iOS format)
                val json = JSONObject().apply {
                    put("pinataContent", contentJson)
                    put("pinataMetadata", JSONObject().apply {
                        put("name", "oshi-message-${System.currentTimeMillis()}")
                    })
                }

                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build()

                Log.d(TAG, "📤 Uploading to IPFS via: $url")
                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val responseJson = JSONObject(responseBody ?: "{}")
                    val hash = responseJson.optString("IpfsHash")
                        .takeIf { it.isNotEmpty() }
                        ?: responseJson.optString("ipfsHash")
                            .takeIf { it.isNotEmpty() }

                    Log.d(TAG, "✅ IPFS upload success: $hash")
                    return@withContext hash
                }

                Log.w(TAG, "⚠️ Pinata upload failed: ${response.code}")
                null
            } catch (e: Exception) {
                Log.e(TAG, "❌ IPFS upload error: ${e.message}")
                null
            }
        }
    }

    /**
     * Convert hex public key to base64url (iOS-compatible)
     */
    // __CALL_MEDIA_AUTH_2026_09_23__ ONE spelling shared with UDPRelayClient, so the relay
    // token's `identity` is byte-for-byte the UDP frame's `sender` (contract §9).
    internal fun publicKeyToBase64url(hexOrBase64Key: String): String =
        com.oshi.messenger.service.CallKeyEncoding.toBase64url(hexOrBase64Key)

    /**
     * __CALL_MEDIA_AUTH_2026_09_23__ `POST /api/call/relay-token` (contract §2). Signed by the
     * CallPushSigning interceptor on [httpClient] over the exact bytes sent. Returns null on any
     * failure (400/403/429/503/network); the caller retries at its heartbeat pace, never faster.
     * The macKey in the response is never logged.
     */
    suspend fun requestRelayToken(peerPublicKey: String, callId: String): com.oshi.messenger.service.RelayToken? =
        withContext(Dispatchers.IO) {
            if (myPublicKey.isEmpty() || peerPublicKey.isEmpty() || callId.isEmpty()) return@withContext null
            val identity = publicKeyToBase64url(myPublicKey)
            val peer = publicKeyToBase64url(peerPublicKey)
            val payload = com.oshi.messenger.service.RelayAuth.relayTokenBody(identity, peer, callId)
            val request = Request.Builder()
                .url("$callServerUrl/relay-token")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .addHeader("X-OSHI-Platform", "android")
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // 403 carries {"reason":...}; no secret in an error body.
                        Log.w(TAG, "🛰️ relay-token HTTP ${response.code} ${body.take(80)}")
                        return@withContext null
                    }
                    com.oshi.messenger.service.RelayToken.fromResponse(body, identity, peer, callId)
                        .also { if (it == null) Log.w(TAG, "🛰️ relay-token: malformed 200 body") }
                }
            } catch (e: IOException) {
                Log.w(TAG, "🛰️ relay-token request failed: ${e.message}")
                null
            }
        }

    /**
     * Notify VPS about new message in IPFS queue
     */
    internal suspend fun notifyVPSQueue(
        recipientPublicKey: String,
        ipfsHash: String,
        suppressPush: Boolean = false,
        /** See [sendMessage]: the group's id for group traffic, else the peer. */
        conversationId: String = recipientPublicKey,
    ): Boolean {
        // __NEVER_OVER_THE_INTERNET_2026_10_20__ One of the doors to the
        // internet. Every one of them is closed by the same call, because the
        // promise is "nothing of mine leaves over the network", and the door
        // somebody forgot IS the broken promise. Parity: iOS
        // `TransportPolicy.internetAllowedForSending`, nine gates.
        // A receipt is traffic too: "she read it, at 23:04" over the relay is
        // exactly what the switch is meant to stop.
        //
        // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Per conversation as well as
        // app-wide — see [sendMessage].
        if (!com.oshi.messenger.service.TransportPreferences.internetAllowedForSending(appContext, conversationId)) return false
        if (!com.oshi.messenger.service.CoverOutbox.legacyRelayAllowedFor(
                com.oshi.messenger.service.CoverOutbox.coverModeOn, recipientPublicKey, conversationId)) return false
        return withContext(Dispatchers.IO) {
            try {
                val encodedKey = publicKeyToBase64url(recipientPublicKey)
                // `?nopush=1` stops the server-side queue fallback from firing its own
                // generic alert — iOS `MessageManager.swift:4808`.
                val url = "$currentUrl$ENDPOINT_QUEUE/$encodedKey/$ipfsHash" +
                        if (suppressPush) "?nopush=1" else ""

                Log.d(TAG, "DIAG_VPS_NOTIFY_REQ | rcpt=${recipientPublicKey.take(16)} | encoded=${encodedKey.take(24)} | hash=${ipfsHash.take(16)} | url=${url.take(120)}")

                val request = Request.Builder()
                    .url(url)
                    .post("".toRequestBody(null))
                    .build()

                val response = httpClient.newCall(request).execute()
                val bodyStr = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
                val ok = response.isSuccessful || response.code == 200
                if (ok) {
                    Log.d(TAG, "DIAG_VPS_NOTIFY_OK | code=${response.code} | rcpt=${encodedKey.take(24)} | hash=${ipfsHash.take(16)} | bodyPreview=${bodyStr.take(120)}")
                } else {
                    Log.w(TAG, "DIAG_VPS_NOTIFY_FAIL | code=${response.code} | rcpt=${encodedKey.take(24)} | hash=${ipfsHash.take(16)} | bodyPreview=${bodyStr.take(200)}")
                }
                ok
            } catch (e: Exception) {
                Log.e(TAG, "DIAG_VPS_NOTIFY_EXC | ${e.javaClass.simpleName}: ${e.message ?: "(no message)"}")
                false
            }
        }
    }

    /**
     * Send a text message (handles encryption)
     */
    suspend fun sendTextMessage(
        recipientPublicKey: String,
        plaintext: String
    ): Boolean {
        val encrypted = cryptoManager.encryptMessage(plaintext, recipientPublicKey)
        return sendMessage(recipientPublicKey, encrypted)
    }

    /**
     * Send a string protocol payload (typing, sync, device link, group update,
     * scheduled call) as a message.
     *
     * The payload is encrypted for [recipientPublicKey] with the SAME primitive
     * [sendTextMessage] uses for user text — X25519 → HKDF(salt="", info="") →
     * AES-256-GCM — because that is the only thing the receiver accepts. Every
     * inbound lane funnels the inner payload through
     * `MessageRepository.decryptIOSMessage`, whose last resort is
     * `CryptoManager.decryptMessageToString`.
     *
     * This overload used to pass `content.toByteArray()` to a parameter named
     * `encryptedContent`, so protocol payloads rode inside the v3 envelope as
     * plaintext and then failed the AES-GCM tag check on arrival — dropped into
     * `failedMessageIds` on both platforms. Encrypting is what makes them arrive.
     *
     * An encryption failure REFUSES the send. There is no plaintext fallback.
     */
    suspend fun sendMessage(
        recipientPublicKey: String,
        content: String,
        suppressPush: Boolean = false,
        /** See the ByteArray overload: the group's id for group traffic. */
        conversationId: String = recipientPublicKey,
    ): Boolean {
        val encrypted = try {
            cryptoManager.encryptMessage(content, recipientPublicKey)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Refusing to send: payload encryption failed (${e.javaClass.simpleName}: ${e.message})")
            return false
        }
        return sendMessage(
            recipientPublicKey,
            encrypted,
            suppressPush = suppressPush,
            conversationId = conversationId
        )
    }

    /**
     * Fetch messages via IPFS queue (the only delivery mechanism on port 8081).
     */
    suspend fun fetchMessages(): List<VPSMessage> {
        return fetchMessagesViaIPFS()
    }

    /**
     * Fetch via IPFS queue on port 80
     */
    private suspend fun fetchMessagesViaIPFS(): List<VPSMessage> {
        return try {
            val encodedKey = publicKeyToBase64url(myPublicKey)
            // Include deviceId so two devices on the same identity each get
            // their own view of the queue. Without it the server defaults to
            // 'default' and whichever phone polls first marks the message as
            // received-by-default, hiding it from the other phone forever.
            val deviceId = com.oshi.messenger.service.FCMService.getDeviceId(appContext)
            val pendingUrl = "$currentUrl$ENDPOINT_PENDING/$encodedKey?deviceId=$deviceId"

            val ipfsHashes = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(pendingUrl)
                    // __BOT_E2E_2026_09_23__ Lets the server's `compat` mode seal bot
                    // messages to this build (it can open bot-seal-v1).
                    .addHeader("X-OSHI-Caps", com.oshi.messenger.network.bot.BotSealedEnvelope.VERSION)
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    try {
                        val jsonArray = JSONArray(body)
                        (0 until jsonArray.length()).map { jsonArray.getString(it) }
                    } catch (e: Exception) {
                        try {
                            val jsonObj = JSONObject(body)
                            val hashesArray = jsonObj.optJSONArray("hashes") ?: JSONArray()
                            (0 until hashesArray.length()).map { hashesArray.getString(it) }
                        } catch (e2: Exception) {
                            emptyList()
                        }
                    }
                } else {
                    emptyList()
                }
            }

            if (ipfsHashes.isEmpty()) {
                Log.v(TAG, "📭 No pending IPFS hashes (queue empty)")
                return emptyList()
            }

            Log.d(TAG, "📥 Found ${ipfsHashes.size} pending IPFS hashes")

            val messages = mutableListOf<VPSMessage>()
            for (hash in ipfsHashes) {
                try {
                    // 🤖 BOT MESSAGE: iOS queues bot messages as "bot:<messageId>:<base64payload>"
                    // not as real IPFS CIDs. Handle them directly without IPFS fetch.
                    if (hash.startsWith("bot:")) {
                        processBotEnvelope(hash)
                        continue
                    }

                    // 🤖 BOT PLATFORM: the newer items (`{"type":"bot_message"…}`,
                    // `{"type":"bot_callback_answer"…}`) may be queued as the JSON
                    // itself rather than as a CID — the `bot:` sentinel above is the
                    // precedent that this queue carries non-CID entries. Handing a raw
                    // JSON blob to `fetchFromIPFS` would just 404 and drop it silently.
                    if (hash.trimStart().startsWith("{")) {
                        val handled = try {
                            handleBotPlatformItem(JSONObject(hash))
                        } catch (_: Exception) {
                            false
                        }
                        if (handled) {
                            markHashAsReceived(hash)
                            continue
                        }
                    }

                    val content = fetchFromIPFS(hash)
                    if (content != null) {
                        val rawStr = String(content, Charsets.UTF_8)
                        if (LOG_SENSITIVE) Log.d(TAG, "📦 Hash ${hash.take(12)}: raw content (${rawStr.length} chars): ${rawStr.take(200)}...")
                        val msgJson = JSONObject(rawStr)

                        // 🔧 CROSS-PLATFORM FIX: Detect iOS encrypted envelope (v2/v3)
                        val version = msgJson.optInt("v", 0)
                        var shouldMarkReceived = true
                        // 🤖 Bot-platform item fetched through IPFS like any other
                        // payload. Tested FIRST because it is identified by `type` and
                        // carries none of the keys the branches below look for — left
                        // to fall through, a `bot_message` would land in the legacy
                        // `VPSMessage.fromJson` branch and surface as a junk 1:1 row.
                        if (handleBotPlatformItem(msgJson)) {
                            markHashAsReceived(hash)
                            continue
                        }
                        if (version >= 2) {
                            Log.d(TAG, "🔐 Detected iOS encrypted envelope v$version, keys: ${msgJson.keys().asSequence().toList()}")
                            lastGroupEnvelopeHandled = false
                            val innerJson = decryptIPFSEnvelope(msgJson)
                            if (innerJson != null) {
                                messages.add(VPSMessage.fromJson(innerJson))
                                Log.d(TAG, "✅ Decrypted iOS envelope successfully")
                            } else if (lastGroupEnvelopeHandled) {
                                Log.d(TAG, "📦 Group envelope handled via callback")
                            } else {
                                // Decryption failed — no matching key yet (groups not synced)
                                Log.w(TAG, "⚠️ Group envelope decryption failed — NOT marking received, will retry next poll")
                                shouldMarkReceived = false
                            }
                        } else if (msgJson.has("encrypted") && !msgJson.has("id")) {
                            // 🔧 iOS delivery receipt format: {"encrypted": "base64..."}
                            Log.d(TAG, "📬 Detected iOS delivery receipt format")
                            handleEncryptedReceipt(msgJson.optString("encrypted", ""))
                        } else if (msgJson.optString("type") == "group_update" && msgJson.has("data")) {
                            // 📢 iOS full-group-update broadcast (from IPFSGroupUploader)
                            // Format: {"type":"group_update","groupId":"...","data":"<base64 MessageGroup JSON>","timestamp":...}
                            Log.d(TAG, "📢 Detected iOS group_update broadcast for group ${msgJson.optString("groupId").take(8)}")
                            handleiOSGroupUpdateBroadcast(msgJson)
                        } else if (msgJson.has("name") && msgJson.has("members") && msgJson.has("adminPublicKey") && msgJson.has("createdAt")) {
                            // 📢 iOS raw MessageGroup JSON (from queueMemberJoinedViaVPS path in broadcastFullGroupUpdate)
                            // iOS encodes MessageGroup struct directly — no "type":"group_update" wrapper.
                            // Synthesize the wrapper so handleiOSGroupUpdateBroadcast can process it.
                            val rawGroupId = msgJson.optString("id", "")
                            if (rawGroupId.isNotEmpty()) {
                                Log.d(TAG, "📢 Detected raw iOS MessageGroup JSON for group ${rawGroupId.take(8)}, handling as group_update")
                                val synthesized = org.json.JSONObject().apply {
                                    put("type", "group_update")
                                    put("groupId", rawGroupId)
                                    put("data", android.util.Base64.encodeToString(
                                        rawStr.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
                                    ))
                                }
                                handleiOSGroupUpdateBroadcast(synthesized)
                            }
                        } else {
                            // Legacy/Android format: plain JSON with id field
                            messages.add(VPSMessage.fromJson(msgJson))
                        }

                        // 🔧 CRITICAL: Mark hash as received on VPS (like iOS markAsReceived)
                        // Without this, the same hashes return on every poll cycle
                        // Exception: group envelope that failed decryption — leave it for next poll
                        if (shouldMarkReceived) markHashAsReceived(hash)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to fetch hash $hash: ${e.message}")
                }
            }

            Log.d(TAG, "📥 Fetched ${messages.size} messages via IPFS")
            messages
        } catch (e: Exception) {
            Log.e(TAG, "❌ IPFS fetch error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Mark IPFS hash as received on VPS (removes from pending queue).
     * Matches iOS: DELETE /api/received/{encodedKey}/{hash}
     * Without this, the same hashes return on every poll cycle.
     */
    private suspend fun markHashAsReceived(ipfsHash: String) {
        try {
            val encodedKey = publicKeyToBase64url(myPublicKey)
            // Per-device ack — must match the deviceId we use to poll, otherwise
            // the queue tracks two separate "received" sets per identity.
            val deviceId = com.oshi.messenger.service.FCMService.getDeviceId(appContext)
            val url = "$currentUrl/api/received/$encodedKey/$ipfsHash?deviceId=$deviceId"
            val request = Request.Builder()
                .url(url)
                .delete("".toRequestBody(null))
                .build()
            val response = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }
            if (response.isSuccessful) {
                Log.d(TAG, "✅ Marked hash as received: ${ipfsHash.take(12)}...")
            } else {
                Log.w(TAG, "⚠️ Mark received returned ${response.code}: ${ipfsHash.take(12)}...")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to mark hash received: ${e.message}")
        }
    }

    /**
     * __BOT_E2E_2026_09_23__ Ack a `bot:<messageId>:<payload>` queue entry by its short
     * messageId: `POST /api/bot-received/<key> {messageId, deviceId}`. The DELETE route
     * carries the WHOLE envelope in the URL, which a sealed media envelope (several MB)
     * cannot fit — it would fail silently and re-download on every poll. DELETE stays as a
     * fallback only for a server that does not have the route (404).
     */
    private suspend fun ackBotEnvelope(messageId: String, envelope: String) {
        if (messageId.isBlank()) {
            markHashAsReceived(envelope)
            return
        }
        val status = try {
            val encodedKey = java.net.URLEncoder.encode(publicKeyToBase64url(myPublicKey), "UTF-8")
            // Same deviceId as the GET /api/pending that served this entry.
            val deviceId = com.oshi.messenger.service.FCMService.getDeviceId(appContext)
            val body = JSONObject().apply {
                put("messageId", messageId)
                put("deviceId", deviceId)
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$currentUrl/api/bot-received/$encodedKey")
                .post(body)
                .build()
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { it.code }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ bot-received ack failed for ${messageId.take(8)}: ${e.javaClass.simpleName}")
            return
        }
        when {
            status in 200..299 -> Log.d(TAG, "✅ bot envelope ${messageId.take(8)} acked")
            status == 404 -> markHashAsReceived(envelope)
            else -> Log.w(TAG, "⚠️ bot-received returned $status for ${messageId.take(8)}")
        }
    }

    // ============================================================================
    // iOS ENCRYPTED ENVELOPE DECRYPTION (v2/v3 format)
    // ============================================================================

    /**
     * 📡 Process a group message envelope received directly via CrossPlatformMesh.
     * The envelope is the same IPFS format: {v:2, envelope:base64, nonce:base64, hint:...}
     * Mirrors the IPFS receive path but skips the IPFS fetch step.
     * Returns true if the message was successfully decrypted and dispatched.
     */
    fun processGroupEnvelopeFromMesh(envelopeJsonStr: String): Boolean {
        return try {
            val json = JSONObject(envelopeJsonStr)
            if (json.optInt("v", 0) < 2) {
                Log.w(TAG, "📡 Mesh group envelope: unsupported version ${json.optInt("v", 0)}")
                return false
            }
            Log.d(TAG, "📡 Processing mesh group envelope (hint=${json.optString("hint", "?")})")
            lastGroupEnvelopeHandled = false
            val innerJson = decryptIPFSEnvelope(json)
            if (innerJson != null) {
                Log.d(TAG, "📡 Mesh group envelope decrypted (non-group payload type)")
                true
            } else {
                lastGroupEnvelopeHandled  // true if handled via onGroupEnvelopeDecrypted callback
            }
        } catch (e: Exception) {
            Log.e(TAG, "📡 Mesh group envelope error: ${e.message}")
            false
        }
    }

    /**
     * 🔐 Decrypt iOS encrypted IPFS envelope
     *
     * iOS wraps all IPFS messages in encrypted envelopes for metadata privacy:
     * {
     *   "v": 3,                          // Version
     *   "envelope": "base64_encrypted",  // AES-GCM encrypted payload
     *   "nonce": "base64_nonce",         // 12-byte nonce
     *   "hint": "xxxx",                  // First 4 chars of recipient key
     *   "from": "xxxxxxxx",             // First 8 chars of sender key
     *   "senderPublicKey": "full_key"   // Full sender public key (v3+)
     * }
     *
     * Encryption uses HKDF with salt "oshi-ipfs-envelope-v2" (different from
     * direct message encryption which uses empty salt).
     *
     * Inner payload after decryption:
     * {
     *   "id": "uuid",
     *   "senderAddress": "sender_key",
     *   "recipientAddress": "recipient_key",
     *   "timestamp": 1234567890.0,
     *   "encryptedContent": "base64_encrypted_message"
     * }
     */
    private fun decryptIPFSEnvelope(json: JSONObject): JSONObject? {
        val envelopeStr = json.optString("envelope")
        val nonceStr = json.optString("nonce")
        val senderPublicKey = json.optString("senderPublicKey")
        val hint = json.optString("hint", "")
        val version = json.optInt("v", 0)

        Log.d(TAG, "🔐 decryptIPFSEnvelope: v=$version, hint=$hint, senderPK=${senderPublicKey.take(16)}, hasEnvelope=${envelopeStr.isNotEmpty()}, hasNonce=${nonceStr.isNotEmpty()}")

        if (envelopeStr.isNullOrEmpty() || nonceStr.isNullOrEmpty()) {
            Log.e(TAG, "🔐 Invalid envelope: missing envelope or nonce")
            return null
        }

        if (senderPublicKey.isNullOrEmpty()) {
            // No senderPublicKey = likely a group envelope (uses hint instead)
            Log.d(TAG, "🔐 No senderPublicKey in envelope — trying group decryption (hint=$hint)")
            return decryptGroupIPFSEnvelope(json)
        }

        return try {
            Log.d(TAG, "🔐 Decrypting envelope from ${senderPublicKey.take(16)}...")

            // Step 1: Derive shared secret with sender using X25519
            val sharedSecret = cryptoManager.deriveSharedSecret(senderPublicKey)

            // Step 2: Derive envelope key using HKDF with iOS-compatible salt
            // iOS uses: HKDF(sharedSecret, salt: "oshi-ipfs-envelope-v2", info: empty)
            val envelopeKey = cryptoManager.hkdfDerive(
                sharedSecret = sharedSecret,
                salt = "oshi-ipfs-envelope-v2".toByteArray(Charsets.UTF_8),
                info = ByteArray(0),
                outputLength = 32
            )

            // Step 3: Decode nonce and envelope data
            val nonceBytes = Base64.decode(nonceStr, Base64.NO_WRAP)
            val envelopeBytes = Base64.decode(envelopeStr, Base64.NO_WRAP)

            if (nonceBytes.size != 12) {
                Log.e(TAG, "🔐 Invalid nonce size: ${nonceBytes.size} (expected 12)")
                return null
            }

            // Step 4: Combine nonce + envelope (ciphertext+tag) for AES-GCM decryption
            // iOS format: nonce is separate, envelope = ciphertext || 16-byte tag
            val combined = nonceBytes + envelopeBytes

            // Step 5: Decrypt with AES-256-GCM
            val decrypted = cryptoManager.decryptAESGCM(combined, envelopeKey)

            // Step 6: Parse inner payload as JSON
            val innerStr = String(decrypted, Charsets.UTF_8)
            if (LOG_SENSITIVE) Log.d(TAG, "🔐 ✅ Envelope decrypted: ${innerStr.take(100)}...")

            JSONObject(innerStr)
        } catch (e: Exception) {
            Log.e(TAG, "🔐 ❌ Envelope decryption failed: ${e.message}")
            // Try group envelope decryption as fallback
            decryptGroupIPFSEnvelope(json)
        }
    }

    /**
     * 🔐 Decrypt iOS group message envelope
     * Group envelopes use a key derived from the group ID instead of shared secret
     * Salt: "oshi-group-envelope-v2"
     * Key: SHA256(groupId + "oshi-group-envelope-key")
     */
    private fun decryptGroupIPFSEnvelope(json: JSONObject): JSONObject? {
        // CV-002 — a v4 group envelope carries per-member wraps and no group key,
        // so it is a different shape and is recognised before the keyed path.
        // Checked by SHAPE (`wraps`) as well as version, because `v` is a
        // namespace shared with the 1:1 envelope.
        if (json.optInt("v", -1) == GroupEnvelopeV3.VERSION && json.has("wraps")) {
            val opener = groupEnvelopeV4Opener
            if (opener == null) {
                Log.w(TAG, "group v4 envelope but no opener set")
                return null
            }
            val payloadJson = opener(json)
            if (payloadJson == null) {
                Log.d(TAG, "group v4: not addressed to us, or ratchet desync")
                return null
            }
            return runCatching { JSONObject(payloadJson) }.getOrNull()
        }

        val envelopeStr = json.optString("envelope") ?: return null
        val nonceStr = json.optString("nonce") ?: return null
        val hint = json.optString("hint") // First 4 chars of group ID

        if (envelopeStr.isEmpty() || nonceStr.isEmpty()) return null

        Log.d(TAG, "🔐 Trying group envelope decryption (hint: $hint)...")

        val provider = groupKeyProvider ?: run {
            Log.w(TAG, "🔐 No groupKeyProvider set, cannot decrypt group envelope")
            return null
        }

        val candidates = provider(hint)
        Log.d(TAG, "🔐 Group key candidates: ${candidates.size} groups available (hint=$hint)")
        candidates.forEach { (gid, _) -> Log.d(TAG, "🔐   Candidate: ${gid.take(8)}... hint=${gid.take(4)}") }
        if (candidates.isEmpty()) {
            Log.w(TAG, "🔐 No candidate group keys for hint: $hint — no local groups found")
            return null
        }

        val nonceBytes = Base64.decode(nonceStr, Base64.NO_WRAP)
        val envelopeBytes = Base64.decode(envelopeStr, Base64.NO_WRAP)

        if (nonceBytes.size != 12) {
            Log.e(TAG, "🔐 Invalid nonce size: ${nonceBytes.size} (expected 12)")
            return null
        }

        for ((groupId, groupKey) in candidates) {
            try {
                Log.d(TAG, "🔐 Trying group key for groupId=${groupId.take(8)}... (envelopeBytes=${envelopeBytes.size}, nonceBytes=${nonceBytes.size})")

                // Derive envelope key: SHA256(groupKey + "oshi-group-envelope-v2" + "envelope-key")
                val md = java.security.MessageDigest.getInstance("SHA-256")
                md.update(groupKey)
                md.update("oshi-group-envelope-v2".toByteArray(Charsets.UTF_8))
                md.update("envelope-key".toByteArray(Charsets.UTF_8))
                val envelopeKey = md.digest()

                // Decrypt AES-256-GCM: nonce is separate, envelopeBytes = ciphertext || tag
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                val secretKey = javax.crypto.spec.SecretKeySpec(envelopeKey, "AES")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey,
                    javax.crypto.spec.GCMParameterSpec(128, nonceBytes))
                val decrypted = cipher.doFinal(envelopeBytes)

                val innerStr = String(decrypted, Charsets.UTF_8)
                if (LOG_SENSITIVE) Log.d(TAG, "🔐 ✅ Group envelope decrypted for group $groupId: ${innerStr.take(200)}...")

                val innerPayload = JSONObject(innerStr)
                val payloadType = innerPayload.optString("type", "")

                if (payloadType == "group_message") {
                    // Extract content (base64 of GroupMessage JSON)
                    val contentBase64 = innerPayload.optString("content", "")
                    val senderKey = innerPayload.optString("senderKey", "")
                    if (contentBase64.isNotEmpty() && senderKey.isNotEmpty()) {
                        val groupMessageJson = String(
                            Base64.decode(contentBase64, Base64.NO_WRAP), Charsets.UTF_8
                        )
                        // Route via callback: reconstruct as GROUP_MESSAGE_PREFIX format
                        val groupContent = "👥GROUP_MESSAGE👥$groupMessageJson"
                        onGroupEnvelopeDecrypted?.invoke(senderKey, groupContent)
                        lastGroupEnvelopeHandled = true
                        Log.d(TAG, "🔐 ✅ Routed group message to handler from $senderKey")
                    }
                    return null // Don't add to regular messages (already handled via callback)
                }

                // For other types (delivery_receipt, etc.), return as VPSMessage JSON
                return innerPayload
            } catch (e: Exception) {
                // Try next candidate
                Log.v(TAG, "🔐 Group key ${groupId.take(4)} didn't match: ${e.message}")
            }
        }

        Log.w(TAG, "🔐 ❌ No group key matched for envelope (hint: $hint)")
        return null
    }

    /**
     * 📢 Handle iOS full-group-update broadcast (from IPFSGroupUploader).
     * Format: {"type":"group_update","groupId":"...","data":"<base64 MessageGroup JSON>","timestamp":...}
     * The "data" field is a base64-encoded JSON of the iOS MessageGroup struct.
     * Extracts the group name and notifies GroupManager via onGroupUpdateBroadcast.
     */
    private fun handleiOSGroupUpdateBroadcast(msgJson: JSONObject) {
        try {
            val groupId = msgJson.optString("groupId", "")
            val dataBase64 = msgJson.optString("data", "")
            if (groupId.isEmpty() || dataBase64.isEmpty()) return

            val groupJson = try {
                val decoded = android.util.Base64.decode(dataBase64, android.util.Base64.DEFAULT)
                JSONObject(String(decoded, Charsets.UTF_8))
            } catch (e: Exception) {
                Log.w(TAG, "📢 Failed to decode group_update data: ${e.message}")
                return
            }

            // iOS MessageGroup JSON has "name" field at top level
            val name = groupJson.optString("name", "").ifEmpty { null }
            // iOS uses "members" array of {publicKey, alias, isAdmin, ...} objects
            val membersJson = groupJson.optJSONArray("members")?.toString()
            // iOS may include groupPictureData as base64 (only when includePicture=true)
            val groupImageBytes = groupJson.optString("groupPictureData", "").let { b64 ->
                if (b64.isNotEmpty()) try { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) } catch (_: Exception) { null } else null
            }
            // iOS may include groupWallpaperData as base64 (when wallpaper was synced)
            val groupWallpaperBytes = groupJson.optString("groupWallpaperData", "").let { b64 ->
                if (b64.isNotEmpty()) try { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) } catch (_: Exception) { null } else null
            }
            val groupWallpaperUpdatedBy = groupJson.optString("groupWallpaperUpdatedBy", "").ifEmpty { null }
            // iOS encodes Date as ISO8601 — convert to unix-ms when present
            val groupWallpaperUpdatedAtMs: Long? = groupJson.optString("groupWallpaperUpdatedAt", "").let { iso ->
                if (iso.isEmpty()) null
                else try {
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.parse(iso)?.time
                } catch (_: Exception) { null }
            }
            // adminPublicKey at top level, plus extract admins from members with isAdmin=true
            val adminPublicKey = groupJson.optString("adminPublicKey", "").ifEmpty { null }
            // iOS GroupType raw value: "public", "admin_only", or "collaborative"
            val groupType = groupJson.optString("type", "").ifEmpty { null }
            // 📌 Pin — iOS encodes `pinnedMessageId` (+ `pinnedBy` when it has one) inside
            // this very payload (GroupMessaging.swift:104, CodingKeys :259). Presence of
            // the KEY is what distinguishes "unpinned" from "this update doesn't carry a
            // pin", so it is passed alongside the value.
            val pinnedMessageId = groupJson.optString("pinnedMessageId", "").ifEmpty { null }
            val hasPinnedField = groupJson.has("pinnedMessageId")
            val pinnedBy = groupJson.optString("pinnedBy", "").ifEmpty { null }
            // 🎨 Emoji avatar — iOS `MessageGroup.avatar` (swift:112, decoded :219).
            val avatar = groupJson.optString("avatar", "").ifEmpty { null }
            val hasAvatarField = groupJson.has("avatar")

            Log.d(TAG, "📢 iOS group_update: groupId=${groupId.take(8)}, name=$name, type=$groupType, hasPicture=${groupImageBytes != null}, hasWallpaper=${groupWallpaperBytes != null}, admin=${adminPublicKey?.take(8)}, pinned=${pinnedMessageId?.take(8)}, avatar=$avatar")
            onGroupUpdateBroadcast?.invoke(
                groupId, name, membersJson, groupImageBytes, adminPublicKey, groupType,
                groupWallpaperBytes, groupWallpaperUpdatedBy, groupWallpaperUpdatedAtMs,
                pinnedMessageId, hasPinnedField, pinnedBy, avatar, hasAvatarField
            )
        } catch (e: Exception) {
            Log.w(TAG, "📢 Failed to handle iOS group_update broadcast: ${e.message}")
        }
    }

    /**
     * 🤖 Process iOS bot envelope: "bot:<messageId>:<base64payload>"
     * iOS queues bot messages with this special format instead of IPFS CIDs.
     * Payload JSON: { botName, content, groupId, timestamp, botToken, [botId],
     *                 [mediaType, mediaData] }
     * `botToken` here is the server's 8-char DISPLAY prefix, not the secret.
     */
    private suspend fun processBotEnvelope(envelope: String) {
        Log.d(TAG, "🤖 Processing bot envelope: ${envelope.take(50)}...")

        val parts = envelope.split(":", limit = 3)
        if (parts.size != 3) {
            Log.w(TAG, "🤖 Invalid bot envelope format (expected 3 parts): ${envelope.take(60)}")
            markHashAsReceived(envelope)
            return
        }

        val messageId = parts[1]
        val payloadBase64 = parts[2]

        val payloadData = try {
            Base64.decode(payloadBase64, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "🤖 Bot payload base64 decode failed: ${e.message}")
            ackBotEnvelope(messageId, envelope)
            return
        }

        val json = try {
            JSONObject(String(payloadData, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "🤖 Bot payload JSON parse failed: ${e.message}")
            ackBotEnvelope(messageId, envelope)
            return
        }

        // __BOT_E2E_2026_09_23__ bot-seal-v1: the outer JSON carries only ids and a
        // placeholder; the real payload is sealed to our identity X25519 key. A failure
        // drops (and acks) the entry — the placeholder is never shown in this build.
        val opened = openBotPayloadIfSealed(json, fallbackMessageId = messageId)
        if (opened == null) {
            ackBotEnvelope(messageId, envelope)
            return
        }
        val (payload, sealKind) = opened

        if (payload.optString("type", "") == "bot_callback_answer") {
            handleBotPlatformItem(payload)
            ackBotEnvelope(messageId, envelope)
            return
        }

        val groupId = payload.optString("groupId", "")
        if (groupId.isEmpty()) {
            Log.w(TAG, "🤖 Bot envelope missing groupId, skipping")
            ackBotEnvelope(messageId, envelope)
            return
        }

        routeBotMessage(payload, fallbackMessageId = messageId)
        com.oshi.messenger.network.bot.BotSealStatus.record(appContext, groupId, sealKind)

        // Always mark as received (bot envelopes have no retry logic)
        ackBotEnvelope(messageId, envelope)
    }

    /**
     * __BOT_E2E_2026_09_23__ Returns the payload to process (the opened inner JSON for a
     * bot-seal-v1 envelope, [json] itself for a legacy one) with its seal kind, or null
     * when a sealed envelope cannot be opened. Logs the reason only, never content.
     */
    private fun openBotPayloadIfSealed(
        json: JSONObject,
        fallbackMessageId: String
    ): Pair<JSONObject, com.oshi.messenger.network.bot.BotSealedEnvelope.SealKind>? {
        val seal = com.oshi.messenger.network.bot.BotSealedEnvelope
        if (!seal.isSealed(json)) return json to com.oshi.messenger.network.bot.BotSealedEnvelope.SealKind.NONE
        // §2.6: the AADs bind the OUTER messageId string exactly as received.
        val outerId = json.optString("messageId", "").ifEmpty { fallbackMessageId }
        return try {
            val inner = seal.open(
                json, outerId,
                cryptoManager.exportPrivateKeyBytes(),
                cryptoManager.getPublicKey()
            )
            inner to seal.sealKind(json, inner)
        } catch (e: com.oshi.messenger.network.bot.BotSealedEnvelope.OpenException) {
            Log.w(TAG, "🤖🔒 sealed bot envelope ${outerId.take(8)} dropped: ${e.reason}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "🤖🔒 sealed bot envelope ${outerId.take(8)} dropped: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * 🤖 Turn a decoded bot-message payload into a group message and hand it to the
     * group store.
     *
     * Shared by the two shapes the server queues today:
     *  - the legacy envelope `bot:<messageId>:<base64 payload>` ([processBotEnvelope]),
     *    whose payload carries the truncated `botToken` prefix;
     *  - the bot-platform item `{"type":"bot_message", messageId, botName, botId,
     *    groupId, content, timestamp, replyMarkup}` ([handleBotPlatformItem]).
     *
     * Every field is read with `opt*` and every one of them is optional except
     * `groupId`, which the callers check — a shape that gains a key must not stop an
     * existing phone from showing the message.
     */
    private fun routeBotMessage(json: JSONObject, fallbackMessageId: String) {
        val botName = json.optString("botName", "Bot")
        val content = json.optString("content", "")
        val groupId = json.optString("groupId", "")
        // ⚠️ `botToken` on this envelope is NOT a credential: the server truncates it to
        // an 8-character prefix (iOS documents the same observation at
        // `OSHI/BotManager.swift:1384-1387,:1504-1510`). It identifies a bot well enough
        // to build a sender key and nothing more, which is why nothing here stores it,
        // caches it, or hands it to an authenticated route.
        val botTokenPrefix = json.optString("botToken", "")
        // __BOT_PUBLIC_ID_2026_09_16__ The public, one-way bot id. THIS is what an
        // inline button authenticates a dispatch with when the user does not own the bot.
        val botId = json.optString("botId", "")
        val messageId = json.optString("messageId", "").ifEmpty { fallbackMessageId }

        val timestamp: Long = parseBotTimestamp(json.opt("timestamp"))

        // Build sender key matching iOS format: "bot:" + the 8-char token prefix.
        //
        // `"bot:"` alone is not a key — every bot without a prefix would collapse into
        // one sender and share one badge — so there are two fallbacks, in order of how
        // well they identify the bot: the public `botId`, then a stable digest of the
        // display name. All three keep the `bot:` prefix, so every downstream
        // `startsWith("bot:")` test keeps working.
        val senderKey = when {
            botTokenPrefix.isNotEmpty() -> "bot:${botTokenPrefix.take(8)}"
            botId.isNotEmpty() -> "bot:${botId.take(8)}"
            else -> "bot:${stableBotKey(botName)}"
        }

        // Build a GroupMessage JSON matching the iOS GroupMessage struct
        val groupMessageJson = JSONObject().apply {
            put("id", messageId)
            put("groupId", groupId)
            put("senderPublicKey", senderKey)
            put("senderName", botName)   // shown as bot badge in UI
            put("content", content)      // raw message text only (no [BotName] prefix)
            // NOT misnamed on our side: `encryptedContent` is iOS's own field name on
            // `GroupMessage` (GroupMessaging.swift:345,378) and iOS itself stores a raw
            // plaintext buffer in it (swift:2554,2571) and decodes it as one (swift:744).
            // Base64 of UTF-8 text is the correct value here; renaming would break the
            // decoder on both sides. The group-message body's confidentiality comes from
            // the group envelope around it, not from this field.
            put("encryptedContent", Base64.encodeToString(
                content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            ))
            put("timestamp", timestamp / 1000.0 - 978307200.0) // Apple epoch
            put("isRead", false)
            put("messageChainIndex", 0)
        }

        // Optional media
        val mediaType = json.optString("mediaType", "")
        if (mediaType.isNotEmpty()) groupMessageJson.put("mediaType", mediaType)
        val mediaData = json.optString("mediaData", "")
        if (mediaData.isNotEmpty()) groupMessageJson.put("mediaData", mediaData)

        // 🤖 Inline keyboard — passed through VERBATIM. Nothing here interprets a
        // button; `BotReplyMarkupParser` validates at render time, which is the only
        // place that knows which kinds this build can draw. A non-object `replyMarkup`
        // is simply absent rather than an error: the message itself must still land.
        json.optJSONObject("replyMarkup")?.let { groupMessageJson.put("replyMarkup", it) }

        // __BOT_PUBLIC_ID_2026_09_16__ Carried alongside the keyboard, because it is the
        // keyboard's authorisation: without it a button on a bot the user does not own
        // has nothing the dispatch route will accept.
        if (botId.isNotEmpty()) groupMessageJson.put("botId", botId)

        val groupContent = "👥GROUP_MESSAGE👥$groupMessageJson"
        if (LOG_SENSITIVE) Log.d(TAG, "🤖 ✅ Bot message routed to group $groupId from $senderKey: [$botName] ${content.take(60)}")
        onGroupEnvelopeDecrypted?.invoke(senderKey, groupContent)
    }

    /**
     * A bot-platform queue item: `{"type":"bot_message"|"bot_callback_answer", …}`.
     *
     * Returns true when the item was recognised and consumed, so the caller knows not
     * to fall through to the ordinary message path (and to mark the hash received).
     */
    private fun handleBotPlatformItem(item: JSONObject): Boolean {
        val type = item.optString("type", "")
        if (type != "bot_message" && type != "bot_callback_answer") return false
        // __BOT_E2E_2026_09_23__ A sealed item that cannot be opened is consumed (the
        // caller acks it) and never shown.
        val (json, sealKind) = openBotPayloadIfSealed(
            item, fallbackMessageId = java.util.UUID.randomUUID().toString()
        ) ?: return true
        return when (json.optString("type", "")) {
            "bot_message" -> {
                val groupId = json.optString("groupId", "")
                if (groupId.isEmpty()) {
                    Log.w(TAG, "🤖 bot_message without groupId — dropping")
                } else {
                    routeBotMessage(json, fallbackMessageId = java.util.UUID.randomUUID().toString())
                    com.oshi.messenger.network.bot.BotSealStatus.record(appContext, groupId, sealKind)
                }
                true
            }
            "bot_callback_answer" -> {
                val callbackQueryId = json.optString("callbackQueryId", "")
                val text = json.optString("text", "")
                // An answer with neither an id to match nor a word to say is nothing to
                // show; publishing it would pop an empty snackbar over the thread.
                if (callbackQueryId.isEmpty() && text.isBlank()) {
                    Log.w(TAG, "🤖 empty bot_callback_answer — dropping")
                } else {
                    com.oshi.messenger.service.BotCallbackAnswers.publish(
                        com.oshi.messenger.data.model.BotCallbackAnswer(
                            callbackQueryId = callbackQueryId,
                            text = text,
                            showAlert = json.optBoolean("showAlert", false),
                            botName = json.optString("botName", ""),
                            timestamp = parseBotTimestamp(json.opt("timestamp"))
                        )
                    )
                    Log.d(TAG, "🤖 bot_callback_answer published (alert=${json.optBoolean("showAlert", false)})")
                }
                true
            }
            // Only reachable for an opened sealed item whose inner type is not a bot
            // type: consumed, never shown.
            else -> true
        }
    }

    /**
     * Unix millis from whatever the bot platform put in `timestamp`.
     *
     * Three shapes are live on this route — ISO8601 with and without milliseconds (what
     * iOS's `JSONEncoder` emits) and a bare number — and the number is ambiguous between
     * seconds and milliseconds. Guessing wrong by a factor of 1000 does not look like a
     * bug, it looks like a message from 1970 sitting at the top of the thread forever,
     * so the magnitude is tested rather than assumed. Anything unreadable becomes "now",
     * which is wrong by seconds instead of by decades.
     */
    private fun parseBotTimestamp(raw: Any?): Long {
        val now = System.currentTimeMillis()
        when (raw) {
            null, JSONObject.NULL -> return now
            is Number -> {
                val d = raw.toDouble()
                if (d <= 0.0) return now
                return if (d < 1_000_000_000_000.0) (d * 1000.0).toLong() else d.toLong()
            }
        }
        val text = raw.toString().trim()
        if (text.isEmpty()) return now
        text.toDoubleOrNull()?.let { d ->
            if (d <= 0.0) return now
            return if (d < 1_000_000_000_000.0) (d * 1000.0).toLong() else d.toLong()
        }
        for (pattern in arrayOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            try {
                val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdf.parse(text)?.time?.let { return it }
            } catch (_: Exception) {
                // try the next pattern
            }
        }
        return now
    }

    /**
     * A stable 8-char per-bot suffix for a bot that arrived without a token, derived
     * from its display name. Not a credential and never used as one — it exists only so
     * two different tokenless bots do not share one `senderPublicKey`.
     */
    private fun stableBotKey(botName: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(botName.toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    /**
     * Handle iOS delivery receipt format: {"encrypted": "base64..."}
     * These are AES-GCM encrypted receipts that need the sender's shared secret to decrypt.
     * Route to MessageRepository via callback for contact-based decryption.
     */
    private fun handleEncryptedReceipt(encryptedBase64: String) {
        if (encryptedBase64.isEmpty()) {
            Log.w(TAG, "📬 Empty encrypted receipt, skipping")
            return
        }
        val callback = onEncryptedReceiptReceived
        if (callback != null) {
            callback(encryptedBase64)
            Log.d(TAG, "📬 Routed iOS receipt to handler")
        } else {
            Log.w(TAG, "📬 No receipt handler set, skipping iOS delivery receipt")
        }
    }

    // ============================================================================
    // CALL SIGNALING (iOS Compatible)
    // ============================================================================

    /**
     * Send call signal through VPS (uses port 8083)
     * Same API as iOS VoiceCallManager
     */
    suspend fun sendCallSignal(
        recipientPublicKey: String,
        signal: CallSignal
    ): Boolean {
        return try {
            val recipientBase64url = publicKeyToBase64url(recipientPublicKey)
            val senderBase64url = publicKeyToBase64url(myPublicKey)
            val json = JSONObject().apply {
                put("sender", senderBase64url)
                put("senderPublicKey", senderBase64url)  // 🔧 iOS compatibility
                put("recipient", recipientBase64url)
                put("recipientPublicKey", recipientBase64url)  // 🔧 iOS compatibility
                put("callId", signal.callId)
                put("type", signal.wireType ?: CallSignal.wireTypeOf(signal.type))
                put("payload", Base64.encodeToString(signal.payload, Base64.NO_WRAP))
                put("signal", Base64.encodeToString(signal.payload, Base64.NO_WRAP))  // 🔧 iOS compatibility
                put("timestamp", System.currentTimeMillis() / 1000.0)
                // 📱 MULTI-DEVICE: lets the server drop our own echo
                // (vps_voip_server.js:50) instead of handing every signal back to
                // the device that sent it. iOS: VoiceCallManager.swift:7700.
                put("senderDeviceId", com.oshi.messenger.service.FCMService.getDeviceId(appContext))
                put("privacy_mode", signal.privacyMode)  // 🔒 Call privacy negotiation (Phase 4)
            }

            // Use message server (port 8083) for call signals
            val response = postJsonToCallServer(ENDPOINT_CALL_SIGNAL, json)
            response != null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Send call signal failed: ${e.message}")
            false
        }
    }

    /**
     * Send VoIP push notification to wake up iOS device for incoming call
     * Matches iOS VoIPPushManager.sendVoIPPush() — POST to /push/send-voip-push
     * This triggers Apple APNs to wake the recipient's iOS app even if killed
     */
    suspend fun sendVoIPPush(
        recipientPublicKey: String,
        callerPublicKey: String,
        callerName: String,
        callId: String,
        isVideoCall: Boolean = false
    ): Boolean {
        return try {
            val json = JSONObject().apply {
                put("recipientPublicKey", recipientPublicKey)
                put("callerPublicKey", callerPublicKey)
                // __LEAK_FIX_2026_09_25__ Never the real name: it rode APNs (and the push
                // service's logs) in clear. iOS 1.0.43's VoIPPushManager still REQUIRES a
                // non-empty `callerName`, so the neutral sentinel keeps those builds
                // ringing; every receiver resolves the name locally from the key.
                put("callerName", pushCallerNameSentinel(callerName))
                put("callId", callId)
                put("isVideoCall", isVideoCall)
            }

            Log.d(TAG, "📲 Sending VoIP push to wake iOS device...")

            // Try via nginx proxy: /push/send-voip-push
            val url = "${currentUrl}${PUSH_API_PREFIX}${ENDPOINT_VOIP_PUSH}"
            val result = withContext(Dispatchers.IO) {
                try {
                    val body = json.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url(url)
                        .post(body)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("X-OSHI-Platform", "android")
                        .build()

                    val response = httpClient.newCall(request).execute()
                    when {
                        response.isSuccessful -> {
                            Log.d(TAG, "✅ VoIP push sent successfully")
                            true
                        }
                        response.code == 404 -> {
                            Log.w(TAG, "⚠️ VoIP push: recipient not registered for push notifications")
                            true // Non-fatal — call can still work via polling
                        }
                        else -> {
                            Log.w(TAG, "⚠️ VoIP push: server error ${response.code}")
                            false
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ VoIP push failed (non-fatal): ${e.message}")
                    false
                }
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ VoIP push error (non-fatal): ${e.message}")
            false
        }
    }

    /**
     * Send push notification to wake recipient device (iOS APNs or Android FCM).
     * Matches iOS VoIPPushManager.requestPushNotification() for group messages.
     * POST /push/send-push on push service (port 8084 via nginx)
     */
    suspend fun sendPushNotification(
        recipientPublicKey: String,
        type: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ): Boolean {
        // __NEVER_OVER_THE_INTERNET_2026_10_20__ One of the doors to the
        // internet. Every one of them is closed by the same call, because the
        // promise is "nothing of mine leaves over the network", and the door
        // somebody forgot IS the broken promise. Parity: iOS
        // `TransportPolicy.internetAllowedForSending`, nine gates.
        if (!com.oshi.messenger.service.TransportPreferences.internetAllowedForSending(appContext)) return false
        return try {
            val dataJson = JSONObject()
            data.forEach { (key, value) -> dataJson.put(key, value) }

            val json = JSONObject().apply {
                put("recipientPublicKey", recipientPublicKey)
                put("type", type)
                put("title", title)
                put("body", body)
                put("data", dataJson)
            }

            val url = "${currentUrl}${PUSH_API_PREFIX}${ENDPOINT_SEND_PUSH}"

            withContext(Dispatchers.IO) {
                try {
                    val requestBody = json.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url(url)
                        .post(requestBody)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("X-OSHI-Platform", "android")
                        .build()

                    val response = httpClient.newCall(request).execute()
                    when {
                        response.isSuccessful -> {
                            Log.d(TAG, "📲 Push sent to ${recipientPublicKey.take(8)}... type=$type")
                            true
                        }
                        response.code == 429 -> {
                            Log.w(TAG, "📲 Push rate limited for ${recipientPublicKey.take(8)}...")
                            false
                        }
                        else -> {
                            Log.w(TAG, "📲 Push failed: ${response.code} for ${recipientPublicKey.take(8)}...")
                            false
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "📲 Push error (non-fatal): ${e.message}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "📲 Push notification error: ${e.message}")
            false
        }
    }

    /**
     * Send high-priority FCM data message to wake a locked Android device for an incoming call.
     * Matches iOS sendVoIPPush but targets Android FCM with priority=high + data-only payload.
     * The push service must forward this as an FCM data message (not notification) so it wakes
     * the app even when the device is locked or Doze mode is active.
     * task 1.2.9
     */
    suspend fun sendFcmCallWake(
        recipientPublicKey: String,
        callerPublicKey: String,
        callerName: String,
        callId: String,
        isVideoCall: Boolean = false
    ): Boolean {
        return try {
            val json = JSONObject().apply {
                put("recipientPublicKey", recipientPublicKey)
                put("callerPublicKey", callerPublicKey)
                // __LEAK_FIX_2026_09_25__ no `callerName` on the Android wake: every
                // Android build falls back to a local / generic name when it is absent
                // (`data["callerName"] ?: …`), and FCM is Google's pipe.
                put("callId", callId)
                put("isVideoCall", isVideoCall)
                put("priority", "high")           // FCM high-priority flag
                put("contentAvailable", true)      // Wake app in background
                put("type", "incoming_call_wake")  // Data-only — no notification banner
            }

            val url = "${currentUrl}${PUSH_API_PREFIX}/send-call-wake"
            withContext(Dispatchers.IO) {
                try {
                    val body = json.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url(url)
                        .post(body)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("X-OSHI-Platform", "android")
                        .build()
                    val response = httpClient.newCall(request).execute()
                    val success = response.isSuccessful || response.code == 404
                    Log.d(TAG, "FCM call wake → ${recipientPublicKey.take(8)}: status=${response.code}")
                    success
                } catch (e: Exception) {
                    Log.w(TAG, "FCM call wake failed (non-fatal): ${e.message}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FCM call wake error: ${e.message}")
            false
        }
    }

    /**
     * Poll for incoming call signals (uses port 8083)
     */
    private var lastCallPollLogTime = 0L

    suspend fun pollCallSignals(): List<CallSignal> {
        // Check if we're in rate limit backoff
        if (isRateLimited && System.currentTimeMillis() < rateLimitEndTime) {
            return emptyList()
        }

        return try {
            // GET /signals/:publicKey?deviceId=… — matches VPS vps_voip_server.js.
            // 📱 MULTI-DEVICE: WITHOUT the deviceId the server takes its legacy
            // consume-on-read branch (vps_voip_server.js:56-59,
            // `pendingSignals.delete(publicKey)`) and the first device to poll
            // destroys the queue for every other device on the account. With it,
            // delivery is per-device on a 60 s TTL. iOS: VoiceCallManager.swift:4836.
            val base64urlKey = publicKeyToBase64url(myPublicKey)
            val deviceId = java.net.URLEncoder.encode(
                com.oshi.messenger.service.FCMService.getDeviceId(appContext), "UTF-8"
            )
            val endpoint = "/signals/$base64urlKey?deviceId=$deviceId"

            // Log poll attempt every 30s to confirm polling is active
            val now = System.currentTimeMillis()
            if (now - lastCallPollLogTime > 30000) {
                Log.d(TAG, "📞 pollCallSignals: polling $callServerUrl$endpoint")
                lastCallPollLogTime = now
            }

            val response = getFromCallServer(endpoint)
            if (response != null) {
                // Server returns array directly or wrapped in {signals: [...]}
                val signalsArray = if (response.has("signals")) {
                    response.optJSONArray("signals") ?: JSONArray()
                } else {
                    // Direct array response — parse from raw string
                    JSONArray()
                }
                val signals = mutableListOf<CallSignal>()

                for (i in 0 until signalsArray.length()) {
                    val sigJson = signalsArray.getJSONObject(i)
                    signals.add(CallSignal.fromJson(sigJson))
                }

                if (signals.isNotEmpty()) {
                    Log.d(TAG, "📞 pollCallSignals: received ${signals.size} signal(s): ${signals.map { it.type }}")
                }
                signals
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "📞 pollCallSignals error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Send audio data for VPS-relayed calls
     * Same format as iOS
     */
    suspend fun sendCallAudio(callId: String, audioData: ByteArray, recipientPublicKey: String = ""): Boolean {
        return try {
            val json = JSONObject().apply {
                put("callId", callId)
                put("sender", publicKeyToBase64url(myPublicKey))
                put("recipient", publicKeyToBase64url(recipientPublicKey))
                put("audio", Base64.encodeToString(audioData, Base64.NO_WRAP))
            }

            // POST /audio on port 8083 — matches VPS vps_voip_server.js
            val response = postJsonToCallServer(ENDPOINT_CALL_AUDIO, json)
            response != null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Send call audio failed: ${e.message}")
            false
        }
    }

    /**
     * Send video data for VPS-relayed calls (HTTP fallback).
     * Posts to /video endpoint with "video" JSON key so iOS can poll it from GET /video/{key}.
     * Same format as sendCallAudio but correct endpoint + field name.
     */
    // DIAG: Counter for video sends
    private var diagVideoSendCount = 0L

    suspend fun sendCallVideo(callId: String, videoData: ByteArray, recipientPublicKey: String = ""): Boolean {
        diagVideoSendCount++
        return try {
            val json = JSONObject().apply {
                put("callId", callId)
                put("sender", publicKeyToBase64url(myPublicKey))
                put("recipient", publicKeyToBase64url(recipientPublicKey))
                put("video", Base64.encodeToString(videoData, Base64.NO_WRAP))
            }

            // POST /video on port 8083 — matches VPS vps_voip_server.js video endpoint
            val url = "$callServerUrl/video"
            if (diagVideoSendCount <= 3 || diagVideoSendCount % 30 == 0L) {
                Log.w(TAG, "DIAG: sendCallVideo #$diagVideoSendCount — url=$url, dataSize=${videoData.size}, callId=${callId.take(8)}")
            }
            val response = postJsonToCallServer("/video", json)
            if (diagVideoSendCount <= 3) {
                Log.w(TAG, "DIAG: sendCallVideo #$diagVideoSendCount — response=${response != null}")
            }
            response != null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Send call video failed: ${e.message}")
            if (diagVideoSendCount <= 5) {
                Log.w(TAG, "DIAG: sendCallVideo #$diagVideoSendCount ERROR — ${e.message}")
            }
            false
        }
    }

    /**
     * Receive audio/video packets from VPS-relayed calls.
     * VPS returns JSON array: [{audio: "base64...", sender: "...", callId: "..."}, ...]
     * Returns list of raw encrypted packets (matching iOS pollForAudio format).
     */
    // DIAG: Counters for audio packet polling
    private var diagAudioPollCount = 0L

    suspend fun receiveCallAudioPackets(): List<ByteArray> {
        diagAudioPollCount++
        return try {
            val base64urlKey = publicKeyToBase64url(myPublicKey)
            val url = "$callServerUrl$ENDPOINT_CALL_AUDIO/$base64urlKey"

            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("X-OSHI-Platform", "android")
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseCode = response.code
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext emptyList()
                    if (diagAudioPollCount <= 3 || diagAudioPollCount % 100 == 0L) {
                        Log.w(TAG, "DIAG: receiveCallAudioPackets #$diagAudioPollCount — HTTP $responseCode, bodyLen=${body.length}")
                    }
                    if (body.isEmpty() || body == "[]") return@withContext emptyList()

                    val packets = mutableListOf<ByteArray>()
                    val jsonArray = JSONArray(body)
                    for (i in 0 until jsonArray.length()) {
                        val packet = jsonArray.getJSONObject(i)
                        val audioBase64 = packet.optString("audio", "")
                        if (audioBase64.isNotEmpty()) {
                            val decoded = Base64.decode(audioBase64, Base64.NO_WRAP)
                            if (decoded.size > 9) {
                                packets.add(decoded)
                            }
                        }
                    }
                    if (packets.isNotEmpty()) {
                        Log.d(TAG, "🔊 Received ${packets.size} audio packets from VPS")
                    }
                    packets
                } else {
                    Log.w(TAG, "🔊 Audio poll failed: HTTP ${response.code}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "🔊 Audio poll error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Receive video packets from VPS-relayed calls (HTTP polling fallback).
     * Matches iOS pollForVideo() — polls GET /video/{base64urlKey}
     * Returns list of raw encrypted video packets (with [0xF1][seq:8] header).
     */
    // DIAG: Counters for video packet polling
    private var diagVideoPollCount = 0L
    private var diagVideoPollPackets = 0L

    suspend fun receiveCallVideoPackets(): List<ByteArray> {
        diagVideoPollCount++
        return try {
            val base64urlKey = publicKeyToBase64url(myPublicKey)
            val url = "$callServerUrl/video/$base64urlKey"

            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("X-OSHI-Platform", "android")
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseCode = response.code
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext emptyList()
                    if (diagVideoPollCount <= 3 || diagVideoPollCount % 100 == 0L) {
                        Log.w(TAG, "DIAG: receiveCallVideoPackets #$diagVideoPollCount — HTTP $responseCode, bodyLen=${body.length}, url=$url")
                    }
                    if (body.isEmpty() || body == "[]") return@withContext emptyList()

                    val packets = mutableListOf<ByteArray>()
                    val jsonArray = JSONArray(body)
                    for (i in 0 until jsonArray.length()) {
                        val packet = jsonArray.getJSONObject(i)
                        val videoBase64 = packet.optString("video", "")
                        if (videoBase64.isNotEmpty()) {
                            val decoded = Base64.decode(videoBase64, Base64.NO_WRAP)
                            if (decoded.size > 9) {
                                packets.add(decoded)
                                diagVideoPollPackets++
                            }
                        }
                    }
                    if (packets.isNotEmpty()) {
                        Log.w(TAG, "DIAG: receiveCallVideoPackets GOT ${packets.size} packets (total=$diagVideoPollPackets)")
                        Log.d(TAG, "📹 Received ${packets.size} video packets from VPS")
                    }
                    packets
                } else {
                    if (diagVideoPollCount <= 5 || diagVideoPollCount % 100 == 0L) {
                        Log.w(TAG, "DIAG: receiveCallVideoPackets #$diagVideoPollCount — HTTP $responseCode FAIL, url=$url")
                    }
                    emptyList()
                }
            }
        } catch (e: Exception) {
            if (diagVideoPollCount <= 5) {
                Log.w(TAG, "DIAG: receiveCallVideoPackets ERROR #$diagVideoPollCount — ${e.message}")
            }
            emptyList()
        }
    }

    // ============================================================================
    // POLLING
    // ============================================================================

    /**
     * Start polling for messages
     */
    fun startMessagePolling(onMessage: (VPSMessage) -> Unit) {
        onMessageReceived = onMessage
        messagePollingJob?.cancel()

        messagePollingJob = scope.launch {
            while (isActive) {
                val messages = fetchMessages()
                if (messages.isNotEmpty()) {
                    // Real traffic resets the idle escalation: the next tick is back
                    // at PollBackoff.MESSAGE_BASE_MS however long the app had been quiet.
                    com.oshi.messenger.service.AppPollState.noteActivity()
                }
                messages.forEach { msg ->
                    onMessageReceived?.invoke(msg)
                }

                // 📻 COALESCED. The V2 relay pull, the /v2/config refresh and the push
                // retry drain used to be a SECOND `while (true)` on a 3 s tick of its
                // own (MessageRepository.kt:1174-1186), so the radio was woken twice on
                // two unrelated schedules to talk to the SAME host. Riding this tick
                // instead is one wakeup for both, and is what iOS does — its v2 pull is
                // driven from inside `checkPendingIPFSMessages()`, not from a timer of
                // its own. Failures are contained so a V2 outage can never stop the
                // legacy message poll.
                try {
                    onPollTick?.invoke()
                } catch (e: Exception) {
                    Log.w(TAG, "coalesced poll tick failed: ${e.message}")
                }

                // Sleep until the next tick, or wake immediately if the app calls
                // triggerImmediatePoll() — used when an FCM push arrives or the user
                // opens a chat from a notification, so we don't wait out the interval.
                //
                // The interval is no longer a constant: foreground+active is still 5 s,
                // foreground+idle escalates to 30 s, and BACKGROUNDED is 120 s because
                // FCM is the wake path there (iOS suspends this loop outright — it
                // declares no UIBackgroundModes at all).
                val waitMs = com.oshi.messenger.service.AppPollState.messagePollDelayMs()
                pollWakeup = kotlinx.coroutines.CompletableDeferred()
                kotlinx.coroutines.withTimeoutOrNull(waitMs) {
                    pollWakeup?.await()
                }
            }
        }

        Log.d(TAG, "📡 Started message polling")
    }

    private var pollWakeup: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /**
     * Extra work to run on the SAME wakeup as the message poll, instead of on a
     * timer of its own. Set by `MessageRepository` to carry the V2 relay pull, the
     * `/v2/config` refresh and the push-retry drain. Suspending, and awaited, so a
     * slow V2 pull delays the next message tick rather than overlapping it.
     */
    var onPollTick: (suspend () -> Unit)? = null

    /**
     * Wake the polling loop right now instead of waiting for the next tick.
     *
     * Also counts as activity, so an FCM-driven wake pulls the loop out of its idle
     * escalation for the next minute rather than firing once and dropping straight
     * back to a 30 s tick.
     */
    fun triggerImmediatePoll() {
        com.oshi.messenger.service.AppPollState.noteActivity()
        pollWakeup?.complete(Unit)
    }

    /**
     * Stop polling for messages
     */
    fun stopMessagePolling() {
        messagePollingJob?.cancel()
        messagePollingJob = null
        Log.d(TAG, "🛑 Stopped message polling")
    }

    /**
     * Start polling for call signals
     */
    fun startCallPolling(onSignal: (CallSignal) -> Unit) {
        onCallSignalReceived = onSignal
        callPollingJob?.cancel()

        callPollingJob = scope.launch {
            while (isActive) {
                val signals = pollCallSignals()
                if (signals.isNotEmpty()) {
                    // ANY signal — an offer for an incoming call, an answer, an ICE
                    // candidate, a hang-up — drags this loop back to
                    // PollBackoff.CALL_ACTIVE_MS for the next minute. This is what makes
                    // it safe to idle at 10 s / 60 s: the very first signal of a call
                    // arrives on a slow tick, and every one after it is at 3 s.
                    com.oshi.messenger.service.AppPollState.noteCallSignal()
                }
                signals.forEach { sig ->
                    onCallSignalReceived?.invoke(sig)
                }
                // 🔴 DELIBERATELY NEVER ZERO AND NEVER SUSPENDED. Backgrounding stops
                // the message poll (FCM wakes it); it must NOT stop this one, because
                // the failure mode is a missed call. It only ever slows down — 3 s
                // while a call is live, 10 s idle on screen (iOS's own idle figure for
                // the equivalent state machine), 60 s idle in the background, where
                // iOS polls not at all because the process is suspended.
                delay(com.oshi.messenger.service.AppPollState.callPollDelayMs())
            }
        }

        Log.d(TAG, "📞 Started call signal polling")
    }

    /**
     * Stop polling for call signals
     */
    fun stopCallPolling() {
        callPollingJob?.cancel()
        callPollingJob = null
        Log.d(TAG, "🛑 Stopped call signal polling")
    }

    // ============================================================================
    // CALL WEBSOCKET (iOS-compatible, lowest latency audio/video/signal delivery)
    // ============================================================================

    // Current call ID for WebSocket registration (set before connecting)
    var currentCallId: String = ""

    /**
     * Connect call WebSocket to VPS. Registers with public key so VPS can relay
     * audio/video/signal packets from iOS peers in real-time (instead of HTTP polling).
     * Must be called when a call starts.
     */
    fun connectCallWebSocket() {
        Log.w(TAG, "DIAG: connectCallWebSocket() — existing=${callWebSocket != null}, key=${myPublicKey.take(16)}, callId=${currentCallId.take(8)}")
        if (callWebSocket != null) {
            Log.d(TAG, "🔌 Call WebSocket already connected, skipping")
            return
        }
        if (myPublicKey.isEmpty()) {
            Log.e(TAG, "🔌 Cannot connect call WebSocket - no public key")
            return
        }

        // A call is being set up: pin the signal poll at PollBackoff.CALL_ACTIVE_MS
        // until the socket is torn down, independent of the signal-seen window.
        com.oshi.messenger.service.AppPollState.setCallSessionOpen(true)

        // Reset diagnostic counters for new call
        diagWsBinaryAudio = 0
        diagWsBinaryVideo = 0
        diagAudioPollCount = 0
        diagVideoPollCount = 0
        diagVideoPollPackets = 0
        diagVideoSendCount = 0

        val wsUrl = callWebSocketUrl
        Log.w(TAG, "DIAG: Connecting call WebSocket to $wsUrl (callId=${currentCallId.take(8)})")
        Log.d(TAG, "🔌 Connecting call WebSocket to $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("X-OSHI-Platform", "android")
            .build()

        callWebSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "🔌 Call WebSocket connected!")
                isCallWebSocketConnected = true
                onWebSocketStateChanged?.invoke(true)

                // Try binary registration first (matching iOS binary protocol)
                val binaryRegFrame = buildBinaryFrame(
                    type = 0x04.toByte(), // Register
                    recipientKey = "",
                    payload = ByteArray(0)
                )
                webSocket.send(ByteString.of(*binaryRegFrame))
                Log.d(TAG, "🔌 Sent binary register frame, waiting for ack...")

                // Fallback: if no binary ack within 2s, send JSON registration
                scope.launch {
                    delay(2000)
                    if (!serverSupportsBinary && isCallWebSocketConnected) {
                        Log.d(TAG, "🔌 No binary ack, falling back to JSON registration")
                        val registerMsg = JSONObject().apply {
                            put("type", "register")
                            put("publicKey", publicKeyToBase64url(myPublicKey))
                            put("callId", currentCallId)
                        }
                        callWebSocket?.send(registerMsg.toString())
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // JSON message handler (legacy/fallback)
                try {
                    val json = JSONObject(text)
                    val msgType = json.optString("type")
                    Log.w(TAG, "DIAG: WS onMessage(text) type=$msgType, textLen=${text.length}")
                    when (msgType) {
                        "registered" -> {
                            // __CALL_PUSH_AUTH_2026_09_23__ contract §4: a refused register
                            // answers success:false (+ reason) then closes 4403. Logged only —
                            // the close path does not reconnect, so no tighter loop than today.
                            if (json.has("success") && !json.optBoolean("success", true)) {
                                Log.e(TAG, "🔌 Call WebSocket register REFUSED: error=${json.optString("error")} reason=${json.optString("reason")}")
                            } else {
                                Log.d(TAG, "✅ Call WebSocket JSON registration confirmed (auth=${json.optString("auth", "?")})")
                            }
                        }
                        "audio" -> {
                            val audioBase64 = json.optString("audio", "")
                            if (audioBase64.isNotEmpty()) {
                                val decoded = Base64.decode(audioBase64, Base64.NO_WRAP)
                                Log.w(TAG, "DIAG: WS audio JSON — decoded=${decoded.size}B, type=0x${if (decoded.isNotEmpty()) "%02X".format(decoded[0]) else "??"}")
                                if (decoded.size > 9) {
                                    onCallAudioReceived?.invoke(decoded)
                                }
                            }
                        }
                        "video" -> {
                            val videoBase64 = json.optString("video", "")
                            if (videoBase64.isNotEmpty()) {
                                val decoded = Base64.decode(videoBase64, Base64.NO_WRAP)
                                Log.w(TAG, "DIAG: WS video JSON — decoded=${decoded.size}B, type=0x${if (decoded.isNotEmpty()) "%02X".format(decoded[0]) else "??"}")
                                if (decoded.size > 9) {
                                    onCallAudioReceived?.invoke(decoded)
                                }
                            }
                        }
                        "call_signal" -> {
                            Log.w(TAG, "DIAG: WS call_signal JSON — callId=${json.optString("callId", "").take(8)}, sender=${json.optString("sender", "").take(16)}")
                            val signal = CallSignal(
                                callId = json.optString("callId", ""),
                                senderPublicKey = json.optString("sender", ""),
                                type = CallSignalType.CALL_REQUEST,
                                payload = Base64.decode(json.optString("signal", ""), Base64.NO_WRAP),
                                timestamp = System.currentTimeMillis()
                            )
                            onCallSignalViaWs?.invoke(signal)
                        }
                        "pong" -> { /* keepalive response */ }
                        else -> {
                            Log.w(TAG, "DIAG: WS unknown JSON type=$msgType")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "🔌 Call WebSocket JSON parse error: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary message handler (new protocol — no JSON, no base64)
                try {
                    val data = bytes.toByteArray()
                    Log.v(TAG, "DIAG: WS onMessage(binary) size=${data.size}, firstByte=0x${if (data.isNotEmpty()) "%02X".format(data[0]) else "??"}")
                    // __CALL_PUSH_AUTH_2026_09_23__ binary register refusal = [0x04, 0x00].
                    if (data.size == 2 && data[0] == 0x04.toByte() && data[1] == 0x00.toByte()) {
                        Log.e(TAG, "🔌 Call WebSocket binary register REFUSED by server (auth)")
                        return
                    }
                    val parsed = parseBinaryFrame(data)
                    if (parsed != null) {
                        val typeHex = "0x%02X".format(parsed.type)
                        if (parsed.type.toInt() and 0xFF == 0x01 || parsed.type.toInt() and 0xFF == 0x02) {
                            // Audio or Video — log selectively
                            Log.v(TAG, "DIAG: WS binary frame type=$typeHex, sender=${parsed.sender.take(16)}, payloadSize=${parsed.payload.size}")
                        } else {
                            Log.w(TAG, "DIAG: WS binary frame type=$typeHex, sender=${parsed.sender.take(16)}, callId=${parsed.callId.take(8)}, payloadSize=${parsed.payload.size}")
                        }
                        handleBinaryMessage(parsed)
                    } else {
                        // Try as UTF-8 text fallback
                        val text = bytes.utf8()
                        if (text.startsWith("{")) {
                            Log.w(TAG, "DIAG: WS binary→text fallback, textLen=${text.length}")
                            onMessage(webSocket, text)
                        } else {
                            Log.w(TAG, "DIAG: WS binary UNPARSEABLE — size=${data.size}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "🔌 Call WebSocket binary parse error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "🔌 Call WebSocket failure: ${t.message}")
                isCallWebSocketConnected = false
                serverSupportsBinary = false
                callWebSocket = null
                onWebSocketStateChanged?.invoke(false)
                attemptCallWebSocketReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (code == 4403) Log.e(TAG, "🔌 Call WebSocket closed 4403 (register auth refused): $reason")
                Log.d(TAG, "🔌 Call WebSocket closed: $reason")
                isCallWebSocketConnected = false
                serverSupportsBinary = false
                callWebSocket = null
                onWebSocketStateChanged?.invoke(false)
            }
        })
    }

    // ============================================================================
    // BINARY WEBSOCKET PROTOCOL (matches iOS buildBinaryFrame/parseBinaryFrame)
    // ============================================================================

    /**
     * Build binary frame for client -> server
     * Format: [msgType(1)][recipientKeyLen(1)][recipientKey][senderKeyLen(1)][senderKey][callIdLen(1)][callId][payload]
     */
    private fun buildBinaryFrame(type: Byte, recipientKey: String, payload: ByteArray): ByteArray {
        val recipientBytes = if (recipientKey.isNotEmpty()) {
            publicKeyToBase64url(recipientKey).toByteArray(Charsets.UTF_8)
        } else {
            ByteArray(0)
        }
        val senderBytes = publicKeyToBase64url(myPublicKey).toByteArray(Charsets.UTF_8)
        val callIdBytes = currentCallId.toByteArray(Charsets.UTF_8)

        val frame = ByteArray(1 + 1 + recipientBytes.size + 1 + senderBytes.size + 1 + callIdBytes.size + payload.size)
        var offset = 0

        frame[offset++] = type
        frame[offset++] = recipientBytes.size.toByte()
        System.arraycopy(recipientBytes, 0, frame, offset, recipientBytes.size)
        offset += recipientBytes.size
        frame[offset++] = senderBytes.size.toByte()
        System.arraycopy(senderBytes, 0, frame, offset, senderBytes.size)
        offset += senderBytes.size
        frame[offset++] = callIdBytes.size.toByte()
        System.arraycopy(callIdBytes, 0, frame, offset, callIdBytes.size)
        offset += callIdBytes.size
        System.arraycopy(payload, 0, frame, offset, payload.size)

        return frame
    }

    /**
     * Parse binary frame from server -> client
     * Format: [msgType(1)][senderKeyLen(1)][senderKey][callIdLen(1)][callId][payload]
     */
    private fun parseBinaryFrame(data: ByteArray): BinaryFrame? {
        if (data.size < 3) return null
        var offset = 0

        val msgType = data[offset++]

        if (offset >= data.size) return null
        val senderKeyLen = data[offset++].toInt() and 0xFF
        if (offset + senderKeyLen > data.size) return null
        val senderKey = String(data, offset, senderKeyLen, Charsets.UTF_8)
        offset += senderKeyLen

        if (offset >= data.size) return null
        val callIdLen = data[offset++].toInt() and 0xFF
        if (offset + callIdLen > data.size) return null
        val callId = String(data, offset, callIdLen, Charsets.UTF_8)
        offset += callIdLen

        val payload = if (offset < data.size) {
            data.copyOfRange(offset, data.size)
        } else {
            ByteArray(0)
        }

        return BinaryFrame(msgType, senderKey, callId, payload)
    }

    /**
     * Handle parsed binary message from server
     */
    // DIAG: Counters for binary WS messages
    private var diagWsBinaryAudio = 0L
    private var diagWsBinaryVideo = 0L

    private fun handleBinaryMessage(frame: BinaryFrame) {
        when (frame.type.toInt() and 0xFF) {
            0x01 -> {
                diagWsBinaryAudio++
                // Audio packet — payload is the encrypted audio data
                if (frame.payload.size > 9) {
                    if (diagWsBinaryAudio <= 3 || diagWsBinaryAudio % 100 == 0L) {
                        val pktType = if (frame.payload.isNotEmpty()) "0x%02X".format(frame.payload[0]) else "?"
                        Log.w(TAG, "DIAG: WS binary AUDIO #$diagWsBinaryAudio — payloadSize=${frame.payload.size}, pktType=$pktType, callbackSet=${onCallAudioReceived != null}")
                    }
                    onCallAudioReceived?.invoke(frame.payload)
                }
            }
            0x02 -> {
                diagWsBinaryVideo++
                // Video packet — route through same handler (handleAudioData checks 0xF1)
                if (frame.payload.size > 9) {
                    if (diagWsBinaryVideo <= 5 || diagWsBinaryVideo % 30 == 0L) {
                        val pktType = if (frame.payload.isNotEmpty()) "0x%02X".format(frame.payload[0]) else "?"
                        Log.w(TAG, "DIAG: WS binary VIDEO #$diagWsBinaryVideo — payloadSize=${frame.payload.size}, pktType=$pktType, callbackSet=${onCallAudioReceived != null}")
                    }
                    onCallAudioReceived?.invoke(frame.payload)
                }
            }
            0x03 -> {
                // Signal packet
                val signal = CallSignal(
                    callId = frame.callId,
                    senderPublicKey = frame.sender,
                    type = CallSignalType.CALL_REQUEST,
                    payload = frame.payload,
                    timestamp = System.currentTimeMillis()
                )
                onCallSignalViaWs?.invoke(signal)
            }
            0x04 -> {
                // Register ack — server confirms binary protocol support
                serverSupportsBinary = true
                Log.d(TAG, "✅ Server supports binary WebSocket protocol!")
            }
            0x05 -> {
                // Pong (keepalive response)
            }
        }
    }

    /**
     * Binary frame data class
     */
    private data class BinaryFrame(
        val type: Byte,
        val sender: String,
        val callId: String,
        val payload: ByteArray
    )

    /**
     * Disconnect call WebSocket (call when call ends)
     */
    fun disconnectCallWebSocket() {
        callWebSocketReconnectJob?.cancel()
        callWebSocketReconnectJob = null
        callWebSocket?.close(1000, "Call ended")
        callWebSocket = null
        isCallWebSocketConnected = false
        serverSupportsBinary = false
        // Call is over: let the signal poll fall back to its idle interval. The
        // CALL_HOT_WINDOW_MS grace on the last-signal timestamp still keeps it fast
        // for another minute, which covers a teardown that races a trailing callEnd.
        com.oshi.messenger.service.AppPollState.setCallSessionOpen(false)
        // 🔧 FIX: Do NOT null onCallAudioReceived / onCallSignalViaWs here!
        // These callbacks are set once in EnhancedCallManager.initialize() and must
        // persist across calls. Nulling them caused all subsequent calls to silently
        // drop all WebSocket audio/video packets → "Waiting for video" forever.
        onWebSocketStateChanged = null
        Log.d(TAG, "🔌 Call WebSocket disconnected (callbacks preserved)")
    }

    /**
     * Send audio via WebSocket — binary when server supports it (32% bandwidth savings)
     */
    fun sendCallAudioViaWebSocket(callId: String, audioData: ByteArray, recipientPublicKey: String): Boolean {
        val ws = callWebSocket
        if (ws == null || !isCallWebSocketConnected) return false

        return try {
            if (serverSupportsBinary) {
                // Binary protocol: no JSON, no base64
                val frame = buildBinaryFrame(0x01.toByte(), recipientPublicKey, audioData)
                ws.send(ByteString.of(*frame))
            } else {
                // Legacy JSON protocol
                val msg = JSONObject().apply {
                    put("type", "audio")
                    put("recipient", publicKeyToBase64url(recipientPublicKey))
                    put("audio", Base64.encodeToString(audioData, Base64.NO_WRAP))
                    put("callId", callId)
                    put("timestamp", System.currentTimeMillis() / 1000.0)
                }
                ws.send(msg.toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "🔌 WebSocket audio send error: ${e.message}")
            isCallWebSocketConnected = false
            false
        }
    }

    /**
     * Send video via WebSocket — binary when server supports it
     */
    fun sendCallVideoViaWebSocket(callId: String, videoData: ByteArray, recipientPublicKey: String): Boolean {
        val ws = callWebSocket
        if (ws == null || !isCallWebSocketConnected) return false

        return try {
            if (serverSupportsBinary) {
                val frame = buildBinaryFrame(0x02.toByte(), recipientPublicKey, videoData)
                ws.send(ByteString.of(*frame))
            } else {
                val msg = JSONObject().apply {
                    put("type", "video")
                    put("recipient", publicKeyToBase64url(recipientPublicKey))
                    put("video", Base64.encodeToString(videoData, Base64.NO_WRAP))
                    put("callId", callId)
                    put("timestamp", System.currentTimeMillis() / 1000.0)
                }
                ws.send(msg.toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "🔌 WebSocket video send error: ${e.message}")
            isCallWebSocketConnected = false
            false
        }
    }

    private fun attemptCallWebSocketReconnect() {
        callWebSocketReconnectJob?.cancel()
        callWebSocketReconnectJob = scope.launch {
            delay(2000) // Wait 2 seconds before reconnect
            if (callWebSocket == null && myPublicKey.isNotEmpty()) {
                Log.d(TAG, "🔌 Attempting call WebSocket reconnect...")
                connectCallWebSocket()
            }
        }
    }

    // ============================================================================
    // 🔐 IPFS PROXY - Fetch content via VPS (hides user IP from IPFS gateways)
    // ============================================================================

    // Privacy setting - route IPFS downloads through VPS (default: true)
    private val useVPSProxyForIPFS = true

    /**
     * Fetch content from IPFS via VPS proxy (hides user IP from IPFS gateways)
     * This protects user privacy by having the VPS fetch on their behalf
     */
    suspend fun fetchFromIPFS(ipfsHash: String): ByteArray? {
        // Validate IPFS hash format
        val isValidHash = (ipfsHash.startsWith("Qm") && ipfsHash.length >= 46) ||
                          (ipfsHash.startsWith("bafy") && ipfsHash.length >= 59)

        if (!isValidHash) {
            Log.w(TAG, "⚠️ Invalid IPFS hash format: ${ipfsHash.take(30)}")
            return null
        }

        Log.d(TAG, "🔐 Fetching IPFS: ${ipfsHash.take(20)}...")

        // STEP 1: Try VPS proxy first (hides user IP)
        if (useVPSProxyForIPFS) {
            try {
                val proxyUrl = "$VPS_URL$IPFS_PROXY_ENDPOINT$ipfsHash"
                Log.d(TAG, "   Using VPS proxy (IP protected)")

                val request = Request.Builder()
                    .url(proxyUrl)
                    .get()
                    .build()

                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }

                if (response.isSuccessful) {
                    val data = response.body?.bytes()
                    if (data != null && data.isNotEmpty()) {
                        Log.d(TAG, "✅ Fetched ${data.size} bytes via VPS proxy (IP hidden)")
                        return data
                    }
                } else {
                    Log.w(TAG, "⚠️ VPS proxy returned ${response.code}, falling back to direct")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ VPS proxy failed: ${e.message}, falling back to direct")
            }
        }

        // STEP 2: Fallback to direct gateways (exposes IP - only if VPS fails)
        Log.w(TAG, "⚠️ Using direct IPFS gateways (IP may be exposed)")

        for (gateway in IPFS_GATEWAYS) {
            try {
                val url = "$gateway$ipfsHash"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute()
                }

                if (response.isSuccessful) {
                    val data = response.body?.bytes()
                    if (data != null && data.isNotEmpty()) {
                        Log.d(TAG, "✅ Fetched ${data.size} bytes from ${gateway.take(30)} (direct)")
                        return data
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "   Gateway failed: ${gateway.take(30)}")
                continue
            }
        }

        Log.e(TAG, "❌ All IPFS gateways failed for ${ipfsHash.take(20)}")
        return null
    }

    // ============================================================================
    // HTTP HELPERS
    // ============================================================================

    private suspend fun postJson(endpoint: String, json: JSONObject): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$currentUrl$endpoint"
                val body = json.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-OSHI-Platform", "android")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    response.body?.string()?.let { JSONObject(it) }
                } else {
                    Log.e(TAG, "HTTP ${response.code}: ${response.message}")
                    null
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network error: ${e.message}")
                null
            }
        }
    }

    /**
     * GET from call server (nginx /api/call/ → localhost:8083)
     */
    private var lastCallServerErrorLog = 0L

    private suspend fun getFromCallServer(endpoint: String): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$callServerUrl$endpoint"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("X-OSHI-Platform", "android")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.string()?.let { bodyStr ->
                        if (bodyStr.isNotBlank() && bodyStr != "[]" && bodyStr != "{}") {
                            Log.d(TAG, "📞 getFromCallServer: got response: ${bodyStr.take(200)}")
                        }
                        try { JSONObject(bodyStr) } catch (_: Exception) {
                            // Server may return array — wrap it
                            try {
                                JSONObject().put("signals", JSONArray(bodyStr))
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastCallServerErrorLog > 30000) {
                        Log.w(TAG, "📞 getFromCallServer: HTTP ${response.code} for $url")
                        lastCallServerErrorLog = now
                    }
                    null
                }
            } catch (e: IOException) {
                val now = System.currentTimeMillis()
                if (now - lastCallServerErrorLog > 30000) {
                    Log.w(TAG, "📞 getFromCallServer: IOException: ${e.message}")
                    lastCallServerErrorLog = now
                }
                null
            }
        }
    }

    /**
     * POST to call server (nginx /api/call/ → localhost:8083).
     *
     * __CALL_PUSH_AUTH_2026_09_23__ Signed by [com.oshi.messenger.network.auth.CallPushSigning]'s
     * interceptor on [httpClient], over the path WE request ("/api/call/signal") and the exact
     * body bytes sent. The old per-call `signCallServerRequest` fell back to UNSIGNED when
     * signing threw; the contract forbids that (unsigned = 403 once enforce is on), so the
     * interceptor fails the request instead and the caller's retry takes over.
     */
    private suspend fun postJsonToCallServer(endpoint: String, json: JSONObject): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$callServerUrl$endpoint"
                // Serialize ONCE. The signature covers a hash of the body bytes, so
                // re-serializing between hashing and sending would sign one byte
                // sequence and transmit another — JSONObject gives no ordering
                // guarantee, so the two would differ and every signature would fail.
                val payload = json.toString().toByteArray(Charsets.UTF_8)
                val body = payload.toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-OSHI-Platform", "android")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    response.body?.string()?.let { JSONObject(it) }
                } else {
                    // Don't log 404 for polling - it's expected when no signals
                    null
                }
            } catch (e: IOException) {
                null
            }
        }
    }

    /**
     * POST to call server with rate limit handling (for polling)
     */
    private suspend fun postJsonToCallServerWithRateLimit(endpoint: String, json: JSONObject): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$callServerUrl$endpoint"
                val body = json.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-OSHI-Platform", "android")
                    .build()

                val response = httpClient.newCall(request).execute()

                when {
                    response.isSuccessful -> {
                        isRateLimited = false
                        response.body?.string()?.let { JSONObject(it) }
                    }
                    response.code == 429 -> {
                        // Rate limited - apply backoff
                        Log.w(TAG, "⚠️ Call polling rate limited (429)")
                        isRateLimited = true
                        rateLimitEndTime = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                        null
                    }
                    else -> null // 404 is expected when no signals
                }
            } catch (e: IOException) {
                null
            }
        }
    }

    // ============================================================================
    // CLEANUP
    // ============================================================================

    fun cleanup() {
        stopMessagePolling()
        stopCallPolling()
        scope.cancel()
    }
}

// ============================================================================
// DATA CLASSES
// ============================================================================

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * VPS Message - Compatible with iOS format
 */
data class VPSMessage(
    val id: String,
    val senderPublicKey: String,
    val recipientPublicKey: String,
    val encryptedContent: ByteArray,
    val timestamp: Long,
    val type: String = "message"
) {
    companion object {
        /**
         * Parse message from JSON - supports BOTH iOS and Android field names
         * iOS uses: senderPublicKey, recipientPublicKey, encryptedContent
         * Legacy Android used: sender, recipient, content
         */
        fun fromJson(json: JSONObject): VPSMessage {
            // Support both iOS (senderPublicKey) and legacy Android (sender) field names
            val sender = json.optString("senderPublicKey").takeIf { it.isNotEmpty() }
                ?: json.optString("sender").takeIf { it.isNotEmpty() }
                ?: json.optString("senderAddress", "")

            val recipient = json.optString("recipientPublicKey").takeIf { it.isNotEmpty() }
                ?: json.optString("recipient").takeIf { it.isNotEmpty() }
                ?: json.optString("recipientAddress", "")

            val content = json.optString("encryptedContent").takeIf { it.isNotEmpty() }
                ?: json.optString("content", "")

            // Handle timestamp: iOS sends Apple epoch (seconds since 2001), Android sends Unix millis
            val rawTimestamp = json.optDouble("timestamp", 0.0)
            val timestamp = when {
                rawTimestamp == 0.0 -> System.currentTimeMillis()
                rawTimestamp < 1_000_000_000_000.0 -> {
                    // Seconds (possibly Apple epoch) — convert to Unix millis
                    if (rawTimestamp < 1_000_000_000.0) {
                        // Apple epoch: add 978307200 to get Unix, then × 1000 for millis
                        ((rawTimestamp + 978307200.0) * 1000.0).toLong()
                    } else {
                        // Unix seconds → millis
                        (rawTimestamp * 1000.0).toLong()
                    }
                }
                else -> rawTimestamp.toLong() // Already Unix millis
            }

            return VPSMessage(
                id = json.optString("id").takeIf { it.isNotEmpty() }
                    ?: java.util.UUID.randomUUID().toString(),
                senderPublicKey = sender,
                recipientPublicKey = recipient,
                encryptedContent = Base64.decode(content, Base64.NO_WRAP),
                timestamp = timestamp,
                type = json.optString("type", "message")
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VPSMessage
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Call Signal - Compatible with iOS format
 */
data class CallSignal(
    val callId: String,
    val senderPublicKey: String,
    val type: CallSignalType,
    val payload: ByteArray,
    val timestamp: Long,
    val privacyMode: String = "direct",  // "direct" (default, lower latency) or "relay" (IP hidden)
    /**
     * __CALL_VIDEO_SIGNAL_2026_09_23__ The envelope `type` to put on the wire instead of
     * [wireTypeOf] — e.g. `"keyframeRequest"` for an in-call `0x0B`, which the call server
     * then budgets on its own instead of guessing from the 37-byte sealed size. Receivers
     * never trust it: they decrypt and route on byte 0 (an unknown type is read as
     * CALL_REQUEST and resolved from the payload, by every shipped Android and desktop).
     */
    val wireType: String? = null,
) {
    companion object {
        /**
         * __CALL_VIDEO_SIGNAL_2026_09_23__ The `type` string the SERVER and iOS understand.
         * `name.lowercase()` put `call_end` / `call_reject` on the wire, and the server's
         * end-of-call logic (summary, callEnd push, relay-token revocation, TTL) and iOS's
         * cross-call guard only know the camelCase `callEnd` / `callDeclined`. Receivers
         * are unaffected: Android reads an unknown type as CALL_REQUEST and resolves the
         * real one from the decrypted byte 0 (`resolveSignalType`), iOS routes on byte 0.
         */
        fun wireTypeOf(type: CallSignalType): String = when (type) {
            CallSignalType.CALL_END -> "callEnd"
            CallSignalType.CALL_REJECT -> "callDeclined"
            else -> type.name.lowercase()
        }

        const val WIRE_TYPE_KEYFRAME_REQUEST = "keyframeRequest"

        /**
         * 🔧 CROSS-PLATFORM FIX: Support both iOS and Android field names
         * iOS sends: "sender" or "senderPublicKey"
         * Android sends: "sender"
         */
        fun fromJson(json: JSONObject): CallSignal {
            // Support both "sender" and "senderPublicKey" field names
            val sender = json.optString("sender").takeIf { it.isNotEmpty() }
                ?: json.optString("senderPublicKey").takeIf { it.isNotEmpty() }
                ?: json.optString("senderAddress", "")

            // 🔧 CROSS-PLATFORM FIX: iOS doesn't send "type" field — default to CALL_REQUEST
            val typeStr = json.optString("type", "").uppercase()
            val signalType = if (typeStr.isNotEmpty()) {
                try { CallSignalType.valueOf(typeStr) } catch (_: Exception) { CallSignalType.CALL_REQUEST }
            } else {
                CallSignalType.CALL_REQUEST
            }

            return CallSignal(
                callId = json.optString("callId", ""),
                senderPublicKey = sender,
                type = signalType,
                payload = Base64.decode(json.optString("payload", json.optString("signal", "")), Base64.NO_WRAP),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                privacyMode = json.optString("privacy_mode", "direct")
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CallSignal
        return callId == other.callId && type == other.type
    }

    override fun hashCode(): Int {
        var result = callId.hashCode()
        result = 31 * result + type.hashCode()
        return result
    }
}

enum class CallSignalType {
    OFFER,
    ANSWER,
    ICE_CANDIDATE,
    CALL_REQUEST,
    CALL_ACCEPT,
    CALL_REJECT,
    CALL_END,
    AUDIO_DATA
}

/**
 * __LEAK_FIX_2026_09_25__ What a call wake push says the caller is called: always the
 * neutral sentinel the server itself substitutes (`PUSH_CALLER_NAME=generic`,
 * `ServerPatches/plaintext_leaks/DEPLOY.md`). The argument is ignored on purpose, so no
 * caller can put a real name back on the wire by passing one.
 */
@Suppress("UNUSED_PARAMETER")
internal fun pushCallerNameSentinel(realName: String? = null): String = PUSH_CALLER_NAME_SENTINEL

/** iOS 1.0.43 `VoIPPushManager` requires a non-empty `callerName`; this is the neutral one. */
internal const val PUSH_CALLER_NAME_SENTINEL = "OSHI User"
