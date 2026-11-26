package com.oshi.messenger.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.oshi.messenger.ui.MainActivity
import com.oshi.messenger.R
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oshi.messenger.ui.viewmodel.dataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.mail.MailUnread
import org.json.JSONObject
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import com.oshi.messenger.network.vps.VPSClient
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * FCMService - Firebase Cloud Messaging Service
 *
 * Handles incoming push notifications for:
 * - New messages when app is in background/closed
 * - Incoming voice/video calls
 * - Message delivery confirmations
 *
 * Same functionality as iOS PushNotificationManager with APNs
 */
@AndroidEntryPoint
class FCMService : FirebaseMessagingService() {

    /** Wake the IPFS poll loop the moment a message-type FCM arrives so the
     *  user doesn't see a notification for a message that hasn't been pulled
     *  yet. Without this the poll waits up to MESSAGE_POLL_INTERVAL (~5s) and
     *  the notification can land BEFORE the message lands in the local DB —
     *  user taps the notification, opens the chat, sees nothing. */
    @Inject lateinit var vpsClient: VPSClient
    @Inject lateinit var enhancedCallManager: EnhancedCallManager

    /**
     * 🚫 Block gate. iOS suppresses a push from a blocked contact BEFORE any
     * `UNNotificationRequest` is built — so no banner, no sound and, critically, no
     * badge increment (`PushNotificationManager.swift:98-102` for 1:1,
     * `:172-183` for groups). Android had no such gate: a blocked contact still
     * notified and still rang from the push alone.
     */
    @Inject lateinit var messageRepository: com.oshi.messenger.data.repository.MessageRepository

    /**
     * [D] V2 relay pull on push wake. iOS pulls the V2 queue on EVERY APNs wake —
     * `MessageManager.checkPendingIPFSMessages` fires `v2ConfigTick()` + `v2PullTick()`
     * before anything else (`OSHI/MessageManager.swift:2065-2069`). Android had zero V2
     * hooks in FCM (`grep v2 FCMService.kt` → 0 hits), so a V2 message sent while the
     * app was backgrounded only landed on the next foreground poll.
     */
    @Inject lateinit var v2MessageRouter: com.oshi.messenger.network.v2.V2MessageRouter
    @Inject lateinit var webSessionManager: com.oshi.messenger.network.web.WebSessionManager

    /**
     * __NOTIFICATION_HAS_NO_FACE_2026_10_27__ Read-only, for the sender's avatar on
     * a push banner. The contacts table is the only place that photo exists.
     */
    @Inject lateinit var database: com.oshi.messenger.data.local.AppDatabase

    companion object {
        private const val TAG = "FCMService"

        // Notification channels (like iOS notification categories)
        const val CHANNEL_MESSAGES = "oshi_messages"
        const val CHANNEL_CALLS = "oshi_calls"
        const val CHANNEL_SYSTEM = "oshi_system"

        // FCM token registration. __CALL_PUSH_AUTH_2026_09_23__ This used to POST to
        // /api/call/register_fcm, a route call_server.js does not have (404 on every
        // token refresh). The route lives on push_service (contract §3 row 8).
        private const val VPS_URL = "https://oshi-messenger.com/push"
        private const val REGISTER_FCM_ENDPOINT = "/register_fcm"

        // Push service endpoint for device registration (port 8084 via nginx /push/)
        // Must register here too so push service can send FCM notifications
        private const val PUSH_SERVICE_URL = "https://oshi-messenger.com/push"
        // __CALL_PUSH_AUTH_2026_09_23__ push_service has only /unregister-device
        // (contract §3 row 9). The old "/deregister-device" answered 404, so neither
        // Disconnect nor a token refresh ever removed this handset's registration.
        private const val DEREGISTER_DEVICE_ENDPOINT = "/unregister-device"

        /**
         * __LOCAL_BANNER_2026_09_23__ Tells push_service this build draws message
         * banners ITSELF from local data (group name from `groups_data`, sender name
         * from Room contacts), so it may receive message pushes as DATA-ONLY FCM with
         * opaque ids and no `notification` block. Without it the server keeps the
         * generic "OSHI / New message" block, which the system tray draws without ever
         * calling [onMessageReceived] while the app is in the background.
         */
        const val PUSH_CAPS = "local_banner"

        /**
         * One client for every push-service request, signed by the v2-bound Ed25519 key
         * (CALL_PUSH_AUTH_CONTRACT §3 rows 7-9).
         */
        private val pushHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .addInterceptor(com.oshi.messenger.network.auth.CallPushSigning.interceptor)
                .build()
        }

        // 📱 MULTI-DEVICE: SharedPreferences for stable per-install deviceId
        private const val OSHI_PREFS = "OshiPrefs"
        private const val DEVICE_ID_KEY = "device_id"

        /**
         * 📱 MULTI-DEVICE: Get or create stable per-install deviceId UUID.
         * Persists in OshiPrefs SharedPreferences so it survives app restarts.
         * Used by push_service.js to fan out call rings to all devices for an
         * identity and to dismiss siblings when one answers.
         */
        fun getDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences(OSHI_PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(DEVICE_ID_KEY, null)
            if (!existing.isNullOrEmpty()) return existing
            val newId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(DEVICE_ID_KEY, newId).apply()
            Log.d(TAG, "📱 Generated new deviceId: ${newId.take(8)}...")
            return newId
        }

        /**
         * __BLIND_PUSH_2026_11_09__ Unsigned client for the push GATEWAY's `/v1/seal`: it
         * must not carry the identity signature (that would bind the token to the account
         * on the very host meant not to know it).
         */
        private val sealHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }

        private const val BLIND_PUSH_ACTIVE_KEY = "blind_push_active"

        private fun blindPushState(context: Context) = object : com.oshi.messenger.network.push.BlindPushRegistrar.State {
            private val prefs = context.applicationContext.getSharedPreferences(OSHI_PREFS, Context.MODE_PRIVATE)
            override var active: Boolean
                get() = prefs.getBoolean(BLIND_PUSH_ACTIVE_KEY, false)
                set(v) { prefs.edit().putBoolean(BLIND_PUSH_ACTIVE_KEY, v).apply() }
        }

        /**
         * __BLIND_PUSH_2026_11_09__ The ONE place this handset registers for pushes.
         * Blind (handle) when the server advertises it, else exactly the legacy
         * `/register-device` body. Returns the outcome so [onNewToken] keeps its extra
         * legacy `/register_fcm` call on the legacy path only (that route stores the token).
         */
        internal fun registerForPushes(context: Context, publicKey: String, fcmToken: String): com.oshi.messenger.network.push.BlindPushRegistrar.Outcome =
            com.oshi.messenger.network.push.BlindPushRegistrar.register(
                pushClient = pushHttpClient,
                sealClient = sealHttpClient,
                pushBaseUrl = PUSH_SERVICE_URL,
                publicKey = publicKey,
                deviceId = getDeviceId(context),
                fcmToken = fcmToken,
                pushCaps = PUSH_CAPS,  // __LOCAL_BANNER_2026_09_23__
                state = blindPushState(context),
                log = { Log.d(TAG, it) },
            )

        /**
         * Register FCM token with push service from outside FCMService.
         * Called after identity setup when onNewToken may have fired before public key was available.
         */
        suspend fun registerTokenWithPushService(publicKey: String, fcmToken: String, context: Context) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val outcome = registerForPushes(context, publicKey, fcmToken)
                    Log.d(TAG, "Push registration (static, deviceId=${getDeviceId(context).take(8)}...): $outcome")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Push service registration error: ${e.message}", e)
                }
            }
        }

        /**
         * Revoke this handset's push registration.
         *
         * iOS runs the equivalent as the FIRST statement of
         * `IdentityManager.disconnectWallet()` (IdentityManager.swift:234-238 →
         * `VoIPPushManager.unregisterDevice()`, VoIPPushManager.swift:503-522),
         * and says why: "Without this, the VPS still sends push notifications to
         * the old key, causing phantom ringing on this device even after key
         * regeneration". Neither Android exit door — Disconnect nor Delete
         * account — did it, so a wiped phone kept ringing for the identity it no
         * longer had.
         *
         * Order is load-bearing: the server call needs both the public key and
         * the token, so it happens before `deleteToken()` and before the caller
         * clears `oshi_secure_keys`. Failures are logged, never thrown — a
         * disconnect must complete even with no network.
         */
        suspend fun unregisterThisDevice(context: Context, publicKey: String?) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val prefs = context.getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
                val token = prefs.getString("fcm_token", null) ?: currentFcmToken
                if (!publicKey.isNullOrEmpty() && !token.isNullOrEmpty()) {
                    try {
                        // __BLIND_PUSH_2026_11_09__ no token: /unregister-device keys on
                        // (publicKey, deviceId) only, and the token is what must not travel.
                        val json = JSONObject().apply {
                            put("publicKey", publicKey)
                            put("platform", "android")
                            put("deviceId", getDeviceId(context))
                        }
                        val request = Request.Builder()
                            .url("$PUSH_SERVICE_URL$DEREGISTER_DEVICE_ENDPOINT")
                            .post(json.toString().toRequestBody("application/json".toMediaType()))
                            .addHeader("Content-Type", "application/json")
                            .build()
                        pushHttpClient.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    Log.d(TAG, "✅ Device deregistered from push service")
                                } else {
                                    Log.w(TAG, "⚠️ Push deregistration failed: ${response.code}")
                                }
                            }
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Push deregistration error: ${e.message}")
                    }
                } else {
                    Log.d(TAG, "No public key or token — nothing to deregister server-side")
                }
                // Drop the token itself. Without this the same token is handed
                // back on the next `getToken()` and the old server mapping can be
                // resurrected by any component that re-registers.
                try {
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().deleteToken()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ deleteToken failed: ${e.message}")
                }
                currentFcmToken = null
                runCatching { prefs.edit().clear().apply() }
            }
        }

        // Saved FCM token for retrieval
        private var currentFcmToken: String? = null

        fun getFcmToken(): String? = currentFcmToken

        private const val CALL_NOTIFICATION_ID = 999
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient get() = pushHttpClient

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        Log.d(TAG, "📱 FCM Service created")
    }

    /**
     * Called when FCM token is generated or refreshed
     * Must register with VPS so server can send push notifications
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "🔑 New FCM token: ${token.take(20)}...")

        // Store old token before updating
        val prefs = applicationContext.getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
        val oldToken = prefs.getString("fcm_token", null)
        prefs.edit().putString("fcm_token", token).apply()

        currentFcmToken = token

        // Get public key from DataStore and register with VPS
        scope.launch {
            try {
                val publicKey = applicationContext.dataStore.data
                    .map { prefs -> prefs[stringPreferencesKey("public_key")] }
                    .first()

                if (publicKey != null) {
                    // Deregister old token first if it exists and differs
                    if (oldToken != null && oldToken != token) {
                        Log.d(TAG, "Deregistering old FCM token: ${oldToken.take(20)}...")
                        deregisterFcmTokenFromPushService(publicKey, oldToken)
                    }

                    // __BLIND_PUSH_2026_11_09__ handle when the server offers it; the
                    // legacy /register_fcm (which stores the token) only on the legacy path.
                    val outcome = registerForPushes(applicationContext, publicKey, token)
                    Log.d(TAG, "Push registration after token refresh: $outcome")
                    if (outcome == com.oshi.messenger.network.push.BlindPushRegistrar.Outcome.LEGACY_REGISTERED ||
                        outcome == com.oshi.messenger.network.push.BlindPushRegistrar.Outcome.FAILED) {
                        registerFcmTokenWithVPS(publicKey, token)
                    }
                } else {
                    Log.w(TAG, "⚠️ No public key yet, will register FCM token after identity setup")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read public key from DataStore: ${e.message}")
            }
        }
    }

    /**
     * Deregister old FCM token from push service so stale tokens don't receive notifications
     */
    private suspend fun deregisterFcmTokenFromPushService(publicKey: String, oldToken: String) {
        try {
            val deviceId = getDeviceId(applicationContext)
            // __BLIND_PUSH_2026_11_09__ the server keys on (publicKey, deviceId); the old
            // token is not sent.
            val json = JSONObject().apply {
                put("publicKey", publicKey)
                put("platform", "android")
                put("deviceId", deviceId)
            }

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$PUSH_SERVICE_URL$DEREGISTER_DEVICE_ENDPOINT")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute().also { it.close() }

            if (response.isSuccessful) {
                Log.d(TAG, "✅ Old FCM token deregistered from push service")
            } else {
                Log.w(TAG, "⚠️ Failed to deregister old FCM token: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error deregistering old FCM token: ${e.message}")
        }
    }

    /**
     * Register FCM token with VPS server
     * Server stores mapping: (publicKey, deviceId) -> fcmToken (multi-device aware)
     */
    private suspend fun registerFcmTokenWithVPS(publicKey: String, fcmToken: String) {
        try {
            val deviceId = getDeviceId(applicationContext)
            val json = JSONObject().apply {
                put("publicKey", publicKey)
                put("fcmToken", fcmToken)
                put("platform", "android")
                put("deviceId", deviceId)
                put("timestamp", System.currentTimeMillis())
            }

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$VPS_URL$REGISTER_FCM_ENDPOINT")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute().also { it.close() }

            if (response.isSuccessful) {
                Log.d(TAG, "✅ FCM token registered with VPS (deviceId=${deviceId.take(8)}...)")
            } else {
                Log.w(TAG, "⚠️ Failed to register FCM token: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error registering FCM token: ${e.message}")
        }
    }

    /**
     * Called when message is received from FCM
     * Handles both data messages and notification messages
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "📩 FCM message received from: ${remoteMessage.from}")

        // Check data payload (for custom handling)
        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            Log.d(TAG, "   Data payload: $data")
            handleDataMessage(data)
            // Skip notification payload display — handleDataMessage already shows
            // the notification with proper logic (muted groups, custom formatting).
            // When app is in background/killed, Android system tray handles display
            // automatically from the server-sent notification field.
            return
        }

        // Notification-only payload (no data) — show directly.
        //
        // This body is server-authored and had NO formatting of any kind, so a
        // relay that puts the message preview in the `notification` block (rather
        // than in `data`) printed whatever it was handed. It goes through the same
        // decision as every other body now: a silent control payload posts nothing.
        remoteMessage.notification?.let { notification ->
            Log.d(TAG, "   Notification: ${notification.title} - ${notification.body}")
            presentBody(notification.body ?: "") ?: run {
                Log.d(TAG, "🤫 notification-only payload is a silent control message — no banner")
                return
            }
            // __ONE_BANNER_PER_MESSAGE_2026_09_23__ never a server-authored text body.
            showNotification(
                title = notification.title ?: getString(R.string.app_name),
                body = MessageBanner.genericBody(this),
                channelId = CHANNEL_MESSAGES
            )
        }
    }

    /**
     * Handle data-only FCM messages
     * These allow custom processing even when app is in background
     */
    /**
     * 🚫 Blocking block-check, run before ANY notification is built or any call UI is
     * started. iOS returns early from `scheduleMessageNotification`
     * (`PushNotificationManager.swift:98-102`) / `scheduleGroupMessageNotification`
     * (`:179-183`) the same way.
     *
     * `runBlocking` is deliberate and matches what `showNotification` already does for
     * the DataStore read: FCM gives this callback a real thread and ~20 s of budget, and
     * the read is one indexed row (usually a hot in-memory set — see
     * `MessageRepository.refreshBlockedKeyCache`). Doing it asynchronously would mean
     * posting the notification first and cancelling it afterwards, which is exactly the
     * banner + sound the user blocked this contact to stop seeing.
     */
    private fun isBlocked(publicKey: String): Boolean {
        if (publicKey.isEmpty()) return false
        return try {
            runBlocking { messageRepository.isContactBlocked(publicKey) }
        } catch (e: Exception) {
            Log.w(TAG, "block check failed for ${publicKey.take(8)}: ${e.message}")
            false   // never drop a legitimate message because a DB read failed
        }
    }

    /**
     * 🚫 Contacts-only gate for the PUSH path — iOS `VoIPPushManager.swift:1325-1338`,
     * which rejects before ever reporting the call. Without this, an unknown caller
     * still lit the full-screen incoming UI straight off the FCM data message even with
     * "calls from contacts only" enabled on both phones.
     */
    private fun rejectsUnknownCaller(callerKey: String): Boolean {
        val contactsOnly = CallAdmissionPolicy.isContactsOnlyEnabled(applicationContext)
        if (!contactsOnly) return false
        val known = try {
            runBlocking {
                val db = com.oshi.messenger.data.local.AppDatabase.getInstance(applicationContext)
                val keys = db.contactDao().getAllContactsList().map { it.publicKey }
                CallAdmissionPolicy.isKnownContact(callerKey, keys)
            }
        } catch (e: Exception) {
            // Fail closed, exactly like iOS's `?? false` (VoIPPushManager.swift:1330).
            Log.w(TAG, "contacts-only lookup failed: ${e.message} — treating caller as unknown")
            false
        }
        return CallAdmissionPolicy.shouldRejectUnknownCaller(contactsOnly, known)
    }

    /**
     * The body a banner should carry for [raw], or **null when no banner may be
     * posted at all**.
     *
     * One line, but it is the whole of Job 1 on this surface, so it is worth
     * naming what it replaced. Every caller used to run
     * `MessageSanitizer.sanitizeForDisplay(raw).ifEmpty { "New message" }`, which
     * is broken twice:
     *
     *  1. `MessageSanitizer` is the retired fourth copy of the prefix catalog. It
     *     has no `💬REPLY💬` and no `📢GROUP_UPDATE📢` branch, and since both open
     *     with an emoji rather than `{`, its JSON catch-alls do not fire either —
     *     so it returned them **verbatim**. Replying to someone put
     *     `💬REPLY💬{"content":"…","replyTo":{…}}` on their lock screen. Its
     *     labels are hardcoded English in a 34-language app.
     *  2. `.ifEmpty { … }` inverted the one answer it got right: `""` means *this
     *     is protocol, never show it*, and the fallback turned that into a banner.
     *
     * [NotificationPresentation.decideCapped] asks the catalog on the **raw**
     * string — never on a sanitised one, which is how a sanitiser gets silently
     * negated — and distinguishes "say something generic" from "say nothing".
     */
    private fun presentBody(raw: String): String? {
        val decision = NotificationPresentation.decideCapped(
            raw = raw,
            labels = OSHIControlPayload.labels(applicationContext),
            attachmentBody = getString(R.string.notification_sent_attachment)
        )
        return when (decision) {
            is NotificationPresentation.Decision.Suppress -> null
            is NotificationPresentation.Decision.Show -> decision.body
        }
    }

    private fun handleDataMessage(data: Map<String, String>) {
        val type = data["type"] ?: "message"

        // [D] V2 relay pull on push wake. iOS fires the V2 pull on every APNs wake,
        // ahead of the legacy IPFS poll and outside its in-progress mutex
        // (`OSHI/MessageManager.swift:2065-2069`). The flag rides the data payload as
        // `"v2":"1"`; `poll()` is internally gated by `V2Config` and mutexed, so an
        // extra call is always safe.
        if (data["v2"] == "1") {
            Log.d(TAG, "🅥2 push carries v2=1 — pulling the V2 relay queue")
            scope.launch {
                try {
                    v2MessageRouter.poll()
                } catch (e: Exception) {
                    Log.w(TAG, "🅥2 push-wake poll failed: ${e.message}")
                }
            }
        }

        // __WEBSESSION_E2E_2026_09_23__ OSHI Web wake (`web_sync`, v2: `reason:"web_e2e"`, no
        // content): a browser is waiting for this phone — reconnect the relay socket.
        if (type == "web_sync") {
            try { webSessionManager.onWakePush() } catch (e: Exception) { Log.w(TAG, "web wake failed: ${e.message}") }
            return
        }

        when (type) {
            "message" -> {
                // iOS sends `senderPublicKey` (APNs key); Android-Android pushes use `senderKey`.
                // Accept whichever is present so the deep-link extras always carry the peer key.
                val senderKey = data["senderKey"] ?: data["senderPublicKey"] ?: ""
                // __LOCAL_BANNER_2026_09_23__ the server no longer carries names: resolve
                // the sender from the local contact book, never from the push.
                // __LEAK_FIX_2026_09_25__ local data ONLY — `data.senderName` is never read,
                // so a server that still forwards names changes nothing on screen.
                val senderName = localPeerName(senderKey)
                    ?: getString(R.string.app_name)
                val rawPreview = localizedPreview(data["preview"])
                val messageId = data["messageId"] ?: ""

                // 🚫 iOS PushNotificationManager.swift:98-102 — suppress before the
                // notification exists, so there is no banner, no sound and no badge.
                if (isBlocked(senderKey)) {
                    Log.d(TAG, "🚫 Notification suppressed: sender ${senderKey.take(10)}… is blocked")
                    return
                }

                // Wake the IPFS poll NOW. Otherwise the notification can fire
                // ~immediately while the next poll tick is still up to 5s away,
                // so the user opens the chat from the notification and sees an
                // empty thread until the next poll lands.
                //
                // This runs BEFORE the silent-payload gate below on purpose: a
                // read receipt or a reaction still has to be FETCHED (it is what
                // flips the delivery tick), it just must not be ANNOUNCED.
                try { vpsClient.triggerImmediatePoll() } catch (_: Exception) {}

                // __MUTE_A_CONVERSATION_2026_11_13__ (Anatole N8) Same rule as the
                // group mute below: AFTER the poll, so muting withholds the banner
                // and never the message.
                if (com.oshi.messenger.data.model.ConversationMutes.get(this).contains(senderKey)) {
                    Log.d(TAG, "🔕 Chat with ${senderKey.take(8)}… is muted — no banner")
                    return
                }

                // 🤫 SILENT CONTROL PAYLOAD — post nothing.
                //
                // The old line was
                //     MessageSanitizer.sanitizeForDisplay(rawPreview).ifEmpty { "New message" }
                // which took the sanitiser's one correct answer ("" = never show
                // this) and turned it into a banner. Every typing ping, read
                // receipt, delivery acknowledgement, reaction, key rotation and
                // multi-device sync envelope therefore buzzed the phone with
                // "New message" and nothing behind it. `presentBody` asks
                // `OSHIControlPayload.isSilent` on the RAW string and returns null,
                // and null means we never reach `showNotification` at all — no
                // banner, no sound, no shade entry.
                val preview = presentBody(rawPreview) ?: run {
                    Log.d(TAG, "🤫 push carries a silent control payload — no banner")
                    return
                }

                // 👀 The user is LOOKING at this thread. The poll triggered above
                // will deliver the message into the open chat and `ChatViewModel`'s
                // unread watcher marks it read on arrival — so a banner here would
                // be nothing but something to swipe away for a message already on
                // screen. `isDirectActive` is true only while the process is
                // foregrounded AND this exact thread is the one on screen
                // (`ActiveConversation`), which is the same precision the call
                // branches below get from `isAppForeground()`.
                if (senderKey.isNotEmpty() && ActiveConversation.isDirectActive(senderKey)) {
                    Log.d(TAG, "👀 Chat with ${senderKey.take(8)}… is open — skipping banner")
                    return
                }

                val extras = mutableMapOf<String, String>()
                if (senderKey.isNotEmpty()) extras["senderKey"] = senderKey
                if (messageId.isNotEmpty()) extras["messageId"] = messageId

                // __ONE_BANNER_PER_MESSAGE_2026_09_23__ The push never carries text (the
                // server is generic); `preview` only decided "silent or not" above. The
                // banner says the localized generic sentence, nothing else.
                showNotification(
                    title = senderName,
                    body = MessageBanner.genericBody(this),
                    channelId = CHANNEL_MESSAGES,
                    extras = extras
                )
            }

            // __GROUP_PUSH_TYPE_2026_09_23__ iOS clients up to build 144 push groups as
            // `type:"group"`; Desktop and newer iOS send `group_message`. Same banner.
            "group_message", "group" -> {
                val groupId = data["groupId"] ?: ""
                // __LOCAL_BANNER_2026_09_23__ group name and sender resolved ON THE DEVICE
                // from the opaque groupId / sender key (the push carries no name any more).
                // __LEAK_FIX_2026_09_25__ local data ONLY — `data.groupName` / `data.senderName`
                // are never read.
                val groupName = localGroupName(groupId)
                    ?: getString(R.string.notification_group_fallback)
                val senderName: String? = localPeerName(data["senderKey"] ?: data["senderPublicKey"] ?: "")
                val rawPreview = localizedPreview(data["preview"])
                val messageId = data["messageId"] ?: ""

                // Wake the poll NOW — the same call, for the same reason, as the
                // 1:1 branch above.
                //
                // __GROUP_PUSH_NEVER_FETCHED_2026_10_16__ This branch announced the
                // message and then fetched nothing. A group push therefore produced
                // a banner and an empty thread, and the body did not land until the
                // message loop next ran by itself — which, with the process
                // backgrounded and the device in Doze, is the next maintenance
                // window, not `MESSAGE_BACKGROUND_MS`. That is the "only the
                // notification, the message a quarter of an hour later" a reviewer
                // reported. The FCM wake is the one moment the network is reachable
                // in Doze, and it was being spent drawing a banner.
                //
                // It runs BEFORE the mute, blocked-sender, silent-payload and
                // open-thread gates below, all four of which `return`. Each is a
                // decision about whether to RING someone; none is a reason to leave
                // the message undelivered. Muting a group does not mean leaving it
                // empty. (The 1:1 branch polls after its blocked check; here the
                // poll drains the WHOLE queue, which in a group of nine holds the
                // other eight members' messages too, so gating it on one sender
                // would withhold theirs.)
                try { vpsClient.triggerImmediatePoll() } catch (_: Exception) {}

                // Check if group is muted
                if (isGroupMuted(groupId)) {
                    Log.d(TAG, "Group $groupName is muted, skipping notification")
                    return
                }

                // 🚫 Blocked SENDER inside a group. iOS gates this separately from the
                // group-level mute (`PushNotificationManager.swift:179-183`); note that
                // iOS's gate is `if let senderKey = senderPublicKey`, so an iPhone push
                // that omits the sender skips it entirely. Android accepts both spellings
                // of the key and applies the gate whenever either is present.
                val groupSenderKey = data["senderKey"] ?: data["senderPublicKey"] ?: ""
                if (isBlocked(groupSenderKey)) {
                    Log.d(TAG, "🚫 Group notification suppressed: sender ${groupSenderKey.take(10)}… is blocked")
                    return
                }

                // 🤫 Same gate as the 1:1 branch. A group is where the reported
                // reaction / call JSON surfaced most often (iOS says so in as many
                // words at `PushNotificationManager.swift:188-190`), and a group
                // key rotation or roster sync must not ring anyone.
                val preview = presentBody(rawPreview) ?: run {
                    Log.d(TAG, "🤫 group push carries a silent control payload — no banner")
                    return
                }

                // 👀 Same gate as the 1:1 branch — this group's thread is on screen,
                // `GroupChatViewModel` marks the arriving message read, so there is
                // nothing for a banner to announce.
                if (groupId.isNotEmpty() && ActiveConversation.isGroupActive(groupId)) {
                    Log.d(TAG, "👀 Group ${groupId.take(8)}… is open — skipping banner")
                    return
                }

                val extras = mutableMapOf<String, String>()
                if (groupId.isNotEmpty()) extras["groupId"] = groupId
                if (messageId.isNotEmpty()) extras["messageId"] = messageId

                // __ONE_BANNER_PER_MESSAGE_2026_09_23__ Title = group, sender alias
                // leads the GENERIC body (Android has no subtitle slot), never the text.
                val genericBody = MessageBanner.genericBody(this)
                showNotification(
                    title = groupName,
                    body = senderName?.let { getString(R.string.notification_group_body, it, genericBody) } ?: genericBody,
                    channelId = CHANNEL_MESSAGES,
                    extras = extras
                )
            }

            "call" -> {
                val callType = data["callType"] ?: "voice"
                val callId = data["callId"] ?: ""
                val callerKey = data["callerKey"] ?: data["senderPublicKey"] ?: ""
                // __LEAK_FIX_2026_09_25__ resolved on the device from the key (contact
                // alias > shared nickname > key prefix); `data.callerName` is ignored, so
                // the server can stop forwarding names to Google.
                val callerName = localCallerName(callerKey)

                // 🛟 MULTI-DEVICE FIX (mirrors iOS 1.0.9): drop FCM wake-ups
                // for callIds we already ended on this device. Without this,
                // a delayed FCM retry from the caller could re-ring after
                // the user has hung up locally.
                if (callId.isNotEmpty() && enhancedCallManager.isCallIdRecentlyEnded(callId)) {
                    Log.w(TAG, "🛟 FCM call drop — callId ${callId.take(8)} was just ended on this device")
                    return
                }

                // 🚫 Blocked caller — iOS VoIPPushManager.swift:1300-1310. iOS still has
                // to report+end the call because PushKit demands it; Android has no such
                // obligation for an FCM data message, so we simply never start the
                // ringer service.
                if (isBlocked(callerKey)) {
                    Log.w(TAG, "🚫 FCM call drop — caller ${callerKey.take(10)}… is blocked")
                    return
                }

                // 🚫 Contacts-only — iOS VoIPPushManager.swift:1325-1338, rejected BEFORE
                // the call is reported. This is the gate that stops a stranger lighting
                // the full-screen incoming UI straight from a push.
                if (rejectsUnknownCaller(callerKey)) {
                    Log.w(TAG, "🚫 FCM call rejected — caller not in contacts (contacts-only mode)")
                    return
                }

                val intent = Intent(this, IncomingCallService::class.java).apply {
                    action = IncomingCallService.ACTION_SHOW_INCOMING
                    putExtra(IncomingCallService.EXTRA_CALLER_NAME, callerName)
                    putExtra(IncomingCallService.EXTRA_CALLER_KEY, callerKey)
                    putExtra(IncomingCallService.EXTRA_CALL_ID, callId)
                    putExtra(IncomingCallService.EXTRA_IS_VIDEO, callType == "video")
                }
                val ctx = applicationContext
                val startRinger: () -> Unit = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
                    else ctx.startService(intent)
                }

                // When app is in foreground, the in-app IncomingCallOverlay handles the UI.
                // Starting IncomingCallService would post a duplicate heads-up + full-screen notif.
                // __CALL_WAKE_2026_11_22__ ...but only once the OFFER arrives, and when that copy
                // is lost nothing rang at all. Fetch it now; ring the system way if it still
                // has not shown up 3 s later.
                if (isAppForeground()) {
                    Log.d(TAG, "📞 App in foreground — in-app overlay first, system ringer as a fallback")
                    if (callId.isNotEmpty()) enhancedCallManager.onCallPushWhileForeground(callId, startRinger)
                    return
                }

                // 🛟 SINGLE-SURFACE FIX (mirrors iOS double-notification fix): the caller
                // sends BOTH an `incoming_call_wake` and a `call` push, and a WS/mesh
                // CALL_REQUEST can also arrive — each could start the full-screen ringer
                // for the same call. Raise it only for the FIRST ingress of this callId;
                // every later one coalesces onto the surface already up.
                if (callId.isNotEmpty() && !enhancedCallManager.tryPresentIncomingSurface(callId)) {
                    Log.d(TAG, "🛟 FCM call — incoming surface already up for ${callId.take(8)}, coalescing")
                    return
                }

                startRinger()
                // __CALL_WAKE_2026_11_22__ the caller's screen can now say "Ringing…"
                enhancedCallManager.reportRinging(callerKey, callId)
            }

            // High-priority FCM data-only wake for Android — task 1.2.9
            // Sent by caller before the call signal to wake the device from Doze/locked state
            "incoming_call_wake" -> {
                val callId = data["callId"] ?: ""
                val callerKey = data["callerPublicKey"] ?: data["callerKey"] ?: ""
                // __LEAK_FIX_2026_09_25__ local resolution, see the "call" branch.
                val callerName = localCallerName(callerKey)
                val isVideo = data["isVideoCall"]?.toBoolean() ?: false
                Log.d(TAG, "High-priority call wake received from ${callerKey.take(8)}")

                // 🛟 MULTI-DEVICE FIX (mirrors iOS 1.0.9): drop wake-up for
                // callIds we already ended on this device.
                if (callId.isNotEmpty() && enhancedCallManager.isCallIdRecentlyEnded(callId)) {
                    Log.w(TAG, "🛟 FCM wake drop — callId ${callId.take(8)} was just ended on this device")
                    return
                }

                // 🚫 Same two gates as the "call" branch — this path also ends in
                // startForegroundService(IncomingCallService), so leaving it ungated
                // would let a blocked or unknown caller ring through the back door.
                // iOS: VoIPPushManager.swift:1300-1310 and :1325-1338.
                if (isBlocked(callerKey)) {
                    Log.w(TAG, "🚫 FCM wake drop — caller ${callerKey.take(10)}… is blocked")
                    return
                }
                if (rejectsUnknownCaller(callerKey)) {
                    Log.w(TAG, "🚫 FCM wake rejected — caller not in contacts (contacts-only mode)")
                    return
                }

                // Acquire wake lock to prevent CPU sleep before the real call signal arrives
                val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                val wl = pm.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "OSHI::CallWake"
                )
                wl.acquire(10_000L) // 10s — enough time for the call signal to arrive

                if (isAppForeground()) {
                    Log.d(TAG, "📞 App in foreground — skipping wake heads-up (in-app overlay handles it)")
                } else if (callId.isNotEmpty() && !enhancedCallManager.tryPresentIncomingSurface(callId)) {
                    // 🛟 SINGLE-SURFACE FIX: a `call` push (or a WS/mesh signal) already
                    // raised the incoming surface for this callId — coalesce, don't ring twice.
                    Log.d(TAG, "🛟 FCM wake — incoming surface already up for ${callId.take(8)}, coalescing")
                } else {
                    val intent = Intent(this, IncomingCallService::class.java).apply {
                        action = IncomingCallService.ACTION_SHOW_INCOMING
                        putExtra(IncomingCallService.EXTRA_CALLER_NAME, callerName)
                        putExtra(IncomingCallService.EXTRA_CALLER_KEY, callerKey)
                        putExtra(IncomingCallService.EXTRA_CALL_ID, callId)
                        putExtra(IncomingCallService.EXTRA_IS_VIDEO, isVideo)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            }

            "read_receipt" -> {
                val messageId = data["messageId"] ?: return
                Log.d(TAG, "Read receipt for message: $messageId")
                // Update message status in local database
                scope.launch {
                    try {
                        val db = com.oshi.messenger.data.local.AppDatabase.getInstance(applicationContext)
                        db.messageDao().markAsReadById(messageId)
                        Log.d(TAG, "Marked message $messageId as read from push receipt")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to mark message read: ${e.message}")
                    }
                }
            }

            "typing" -> {
                // Silently handle typing indicator - no notification
                val senderKey = data["senderKey"] ?: return
                Log.d(TAG, "⌨️ Typing indicator from: ${senderKey.take(8)}...")
                // TODO: Update typing indicator in UI
            }

            // 📱 MULTI-DEVICE: Sibling device answered the call — dismiss our local
            // ringing UI / IncomingCallService so we stop ringing.
            "callAnsweredElsewhere" -> {
                val callId = data["callId"] ?: ""
                val answeredBy = data["answeredBy"] ?: ""
                val myDeviceId = getDeviceId(applicationContext)
                if (answeredBy == myDeviceId) {
                    Log.d(TAG, "📱 Ignoring own callAnsweredElsewhere echo (deviceId=${myDeviceId.take(8)})")
                    return
                }
                Log.d(TAG, "📱 callAnsweredElsewhere callId=${callId.take(12)}... answeredBy=${answeredBy.take(8)}... — dismissing local ring")

                // Dismiss the IncomingCallService foreground notification + ringtone
                val dismissIntent = Intent(this, IncomingCallService::class.java).apply {
                    action = IncomingCallService.ACTION_DISMISS
                    putExtra(IncomingCallService.EXTRA_CALL_ID, callId)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(dismissIntent)
                    } else {
                        startService(dismissIntent)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to dismiss IncomingCallService: ${e.message}")
                }

                // Cancel the call notification banner directly as a fallback
                try {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(CALL_NOTIFICATION_ID)
                } catch (_: Exception) {}

                // Broadcast for any in-process call manager to also clean up state
                val intent = Intent("com.oshi.messenger.CALL_ANSWERED_ELSEWHERE").apply {
                    putExtra("call_id", callId)
                    putExtra("answered_by", answeredBy)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }

            // 📧 New mail in the private mailbox. iOS taps the same payload:
            // `type == "mail"` + `data.messageId` (PushNotificationManager.swift mail
            // branch). Tapping the banner deep-links to that message; the badge on
            // the Email entry point tracks the unread count.
            "mail" -> {
                // Optimistic bump — the mailbox reconciles the exact number the next
                // time MailViewModel loads the inbox.
                MailUnread.increment()
                // __MAIL_UNREAD_REFRESH_2026_11_22__ …and reconciled right away: the push may
                // be the one survivor of several collapsed while the phone was offline.
                com.oshi.messenger.mail.MailUnreadSync.instance?.onMailPush()

                val mailMessageId = data["messageId"] ?: ""
                // The server may send a sender / subject preview; fall back to a
                // generic banner when it does not (or when previews are hidden).
                val sender = data["senderName"]?.takeIf { it.isNotBlank() }
                    ?: data["from"]?.takeIf { it.isNotBlank() }
                val subject = data["subject"]?.takeIf { it.isNotBlank() }
                    ?: data["preview"]?.takeIf { it.isNotBlank() }

                // If the Mail screen is on top there is nothing to announce; the list
                // refreshes itself. Reuse the same foreground/active gate the message
                // branches use where one exists — mail has no per-thread active flag,
                // so only the badge is bumped and the banner still posts.

                val extras = mutableMapOf<String, String>()
                extras["type"] = "mail"
                if (mailMessageId.isNotEmpty()) extras["mailMessageId"] = mailMessageId

                showNotification(
                    title = sender ?: getString(R.string.app_name),
                    body = subject ?: getString(R.string.notification_mail_new),
                    channelId = CHANNEL_MESSAGES,
                    extras = extras,
                    number = MailUnread.count.value,
                )
            }

            else -> {
                Log.w(TAG, "⚠️ Unknown FCM message type: $type")
            }
        }
    }

    /**
     * Show a notification for a new message
     */
    private fun showNotification(
        title: String,
        body: String,
        channelId: String,
        extras: Map<String, String> = emptyMap(),
        /** When > 0, shown as the notification/app-icon badge count (mail unread). */
        number: Int = 0
    ) {
        // Unique notification ID per conversation so different chats don't collide
        // and so each delivered notification keeps its own PendingIntent extras.
        // A mail banner keys on its message id so two different emails don't overwrite.
        //
        // __ONE_BANNER_PER_MESSAGE_2026_09_23__ Message banners use the SAME id as the
        // poll paths (`MessageRepository` / `GroupManager`), via [MessageBanner]: case- and
        // base64-spelling-insensitive, so the decrypted-message path replaces this banner
        // instead of stacking a second one — and skips it entirely if posted just now.
        val groupIdExtra = extras["groupId"]?.takeIf { it.isNotBlank() }
        val senderKeyExtra = (extras["senderKey"] ?: extras["senderPublicKey"])?.takeIf { it.isNotBlank() }
        val isMessageBanner = extras["type"] != "mail" && (groupIdExtra != null || senderKeyExtra != null)
        val notificationId = when {
            extras["type"] == "mail" -> (extras["mailMessageId"] ?: title).hashCode()
            groupIdExtra != null -> MessageBanner.groupNotificationId(groupIdExtra)
            senderKeyExtra != null -> MessageBanner.contactNotificationId(senderKeyExtra)
            else -> (extras["mailMessageId"] ?: title).hashCode()
        }
        if (isMessageBanner && MessageBanner.shownRecently(this, notificationId)) {
            Log.d(TAG, "🔕 banner for this conversation posted <${MessageBanner.RECENT_WINDOW_MS / 1000}s ago — not posting a second one")
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            extras.forEach { (key, value) -> putExtra(key, value) }
        }

        // Unique requestCode per conversation — without this, all PendingIntents share
        // one slot (requestCode=0 + FLAG_UPDATE_CURRENT), so tapping any notification
        // would route to whichever conversation was updated last.
        val pendingIntent = PendingIntent.getActivity(
            this, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        // Read the in-app notification settings (DataStore) that SettingsViewModel
        // writes. These were DEAD: `notifications_enabled` and `notification_preview`
        // persisted but nothing here consumed them (the old code only read a
        // SharedPreferences key `hide_notification_content` that the settings UI never
        // writes), so disabling notifications or the preview in-app did nothing. iOS
        // honours both. (Sound/vibrate are per-channel on Android O+ and controlled by
        // the OS channel settings, so they are intentionally left to the system.)
        val (notificationsEnabled, showPreview) = try {
            runBlocking {
                val p = applicationContext.dataStore.data.first()
                Pair(
                    p[booleanPreferencesKey("notifications_enabled")] ?: true,
                    // __ONE_BANNER_PER_MESSAGE_2026_09_23__ default OFF (was ON).
                    p[booleanPreferencesKey("notification_preview")] ?: false,
                )
            }
        } catch (_: Exception) {
            Pair(true, false) // never drop a notification because a pref read failed
        }
        if (!notificationsEnabled) return  // user turned message notifications off in-app

        // Keep the legacy SharedPreferences flag as an additional privacy override.
        val prefs = getSharedPreferences("oshi_settings", Context.MODE_PRIVATE)
        val hideNotificationContent = prefs.getBoolean("hide_notification_content", false)
        val hideContent = hideNotificationContent || !showPreview

        // Message banners already carry name + generic body (see callers): the name is
        // what the owner wants shown, so hiding only applies to mail (subject = content).
        // 🛡 __STRICT_MODE_2026_11_22__ strict: message banners lose the name too.
        val strictAnonymous = isMessageBanner && MessageBanner.anonymous(this)
        val displayTitle = if ((hideContent && !isMessageBanner) || strictAnonymous) getString(R.string.app_name) else title
        val displayBody = if ((hideContent && !isMessageBanner) || strictAnonymous) getString(R.string.notification_new_message) else body

        // __NOTIFICATION_HAS_NO_FACE_2026_10_27__ A sync DAO read: this method is not a
        // coroutine, and it already does a runBlocking DataStore read just above. A key
        // spelled differently in the push than locally simply misses and shows no face.
        val avatar = if (strictAnonymous) null else senderKeyExtra?.let { key ->
            runCatching {
                MessageBanner.avatar(database.contactDao().getContactByPublicKeySync(key)?.profilePicture)
            }.getOrNull()
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayTitle)
            .setContentText(displayBody)
            .setLargeIcon(avatar)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            // Unread count on the app-icon/notification badge (launchers that support
            // a numeric badge read this). 0 clears it.
            .setNumber(number)
            // Privacy: hide content on lock screen — show generic text instead
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.notification_new_message))
                    .build()
            )

        // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ Android's own on-device "smart reply"
        // and contextual actions read the banner. Not for a protected conversation.
        val advPrivacyOn = runCatching {
            AdvancedChatPrivacyStore.attach(this)
            (groupIdExtra?.let { AdvancedChatPrivacyStore.isOn(it) } ?: false) ||
                (senderKeyExtra?.let { AdvancedChatPrivacyStore.isOn(it) } ?: false)
        }.getOrDefault(false)
        val systemRepliesAllowed = com.oshi.messenger.data.model.AdvancedChatPrivacyPolicy
            .systemGeneratedRepliesAllowed(advPrivacyOn)
        if (!systemRepliesAllowed) notificationBuilder.setAllowSystemGeneratedContextualActions(false)

        // Quick reply and mark-as-read actions (inline reply from notification)
        val senderKey = extras["senderKey"] ?: extras["senderPublicKey"] ?: ""

        if (senderKey.isNotEmpty()) {
            val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT_REPLY)
                .setLabel(getString(R.string.notification_reply_hint))
                .build()

            val replyIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_REPLY
                putExtra(NotificationActionReceiver.EXTRA_SENDER_KEY, senderKey)
                putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }
            val replyPendingIntent = PendingIntent.getBroadcast(
                this, notificationId + 100, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val replyAction = NotificationCompat.Action.Builder(
                R.drawable.ic_notification, getString(R.string.notification_reply), replyPendingIntent
            )
                .addRemoteInput(remoteInput)
                // __LOCKED_MEANS_LOCKED_2026_10_28__ (N12, reported by a user: "permet de
                // répondre sans déverrouiller le téléphone")
                //
                // Without this the reply box on the lock screen is live: anyone holding
                // the phone can send a message AS THE OWNER, to a contact of theirs, over
                // an end-to-end encrypted channel that will authenticate it as genuine.
                // The recipient has no way to tell. That is a worse outcome than reading
                // the message, which `VISIBILITY_PRIVATE` already prevents by default.
                //
                // `setAuthenticationRequired` makes the system demand the device
                // credential before the action fires (API 31+; a no-op below that, where
                // the platform has no such gate). It does NOT hide the action — the reply
                // box still appears, so nothing is lost for the person who can unlock.
                .setAuthenticationRequired(true)
                .setAllowGeneratedReplies(systemRepliesAllowed)
                .build()
            notificationBuilder.addAction(replyAction)

            // Mark as read action
            val markReadIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_MARK_READ
                putExtra(NotificationActionReceiver.EXTRA_SENDER_KEY, senderKey)
                putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }
            val markReadPendingIntent = PendingIntent.getBroadcast(
                this, notificationId + 200, markReadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Same gate: marking someone's messages read from a locked phone acts as the
            // owner too — it sends a read receipt the sender will believe.
            notificationBuilder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    getString(R.string.notification_mark_read),
                    markReadPendingIntent,
                ).setAuthenticationRequired(true).build()
            )
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    /**
     * Show incoming call notification (high priority, full-screen intent).
     *
     * ⚠️ DEAD as of 2026-08-23: `grep -rn 'showCallNotification'` finds this
     * declaration and nothing else. The live path is the `"call"` branch of
     * [handleDataMessage], which starts `IncomingCallService` instead. Kept
     * rather than deleted because the call team owns that decision, but its
     * three English literals ("Incoming Voice Call", "Decline", "Accept") were
     * localized here so it cannot ship English if anyone re-wires it.
     */
    @Suppress("unused")
    private fun showCallNotification(
        callerName: String,
        isVideoCall: Boolean,
        callId: String
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("incoming_call", true)
                    com.oshi.messenger.service.IncomingCallLaunchToken.stamp(this)
            putExtra("caller_name", callerName)
            putExtra("is_video_call", isVideoCall)
            putExtra("call_id", callId)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val callTypeText = getString(
            if (isVideoCall) R.string.notification_incoming_video_call
            else R.string.notification_incoming_voice_call
        )
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(callerName)
            .setContentText(callTypeText)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setSound(ringtone)
            .setAutoCancel(false)
            .setOngoing(true)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_call_end,
                getString(R.string.call_decline),
                createDeclineCallIntent(callId)
            )
            .addAction(
                if (isVideoCall) R.drawable.ic_videocam else R.drawable.ic_call,
                getString(R.string.call_accept),
                createAcceptCallIntent(callId, isVideoCall)
            )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(CALL_NOTIFICATION_ID, notificationBuilder.build())
    }

    private fun createDeclineCallIntent(callId: String): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "ACTION_DECLINE_CALL"
            putExtra("call_id", callId)
        }
        return PendingIntent.getActivity(
            this, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createAcceptCallIntent(callId: String, isVideoCall: Boolean): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "ACTION_ACCEPT_CALL"
            putExtra("call_id", callId)
            putExtra("is_video_call", isVideoCall)
        }
        return PendingIntent.getActivity(
            this, 3, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Check if a group is muted by reading SharedPreferences directly.
     * This avoids needing GroupManager injection in the FCM service.
     */
    // __LOCAL_BANNER_2026_09_23__ ------------------------------------------------
    // push_service forces "OSHI" / "New message" on every non-call push (names and
    // content must not reach Google), and local_banner builds get ids only. These
    // helpers turn the opaque ids back into names from THIS device's data. No network.

    /** The server's fixed banner title, i.e. "no name was sent". */
    private fun isGenericServerTitle(s: String?): Boolean =
        s.isNullOrBlank() || s == "OSHI" || s == getString(R.string.app_name)

    /** Server's fixed English body (or none, on the ids-only path) → the localized one. */
    private fun localizedPreview(raw: String?): String =
        if (raw.isNullOrBlank() || raw == "New message") getString(R.string.notification_new_message) else raw

    /** Group name from the locally persisted groups (same store as [isGroupMuted]). */
    private fun localGroupName(groupId: String): String? {
        if (groupId.isBlank()) return null
        return try {
            // __LEAK_FIX_2026_09_25__ groups now live in the sealed store (was plaintext groups.xml).
            val json = GroupLocalStore.groupsJson(this) ?: return null
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .firstOrNull { it.optString("id").equals(groupId, ignoreCase = true) }
                ?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "local group name lookup failed: ${e.message}"); null
        }
    }

    /**
     * __LEAK_FIX_2026_09_25__ What a banner calls a peer: the contact alias the user
     * typed, else the nickname the peer shared with us (`PeerNicknameStore`), else null.
     * Same precedence as the chat list and `EnhancedCallManager`.
     */
    private fun localPeerName(publicKey: String): String? {
        if (publicKey.isBlank() || publicKey.startsWith("bot:")) return null
        val alias = localContactName(publicKey)
        val nick = runCatching {
            com.oshi.messenger.data.model.PeerNicknameStore.init(applicationContext)
            com.oshi.messenger.data.model.PeerNicknameStore.lookup(publicKey)
        }.getOrNull()
        if (alias == null && nick == null) return null
        return com.oshi.messenger.data.model.PeerNicknameStore.displayName(alias, publicKey)
    }

    /** __LEAK_FIX_2026_09_25__ Incoming-call name from local data only (key prefix for a stranger). */
    private fun localCallerName(callerKey: String): String =
        localPeerName(callerKey)
            ?: callerKey.takeIf { it.isNotBlank() && !it.startsWith("bot:") }?.let { it.take(8) + "…" }
            ?: getString(R.string.notification_unknown_caller)

    /** Contact alias from Room, tolerant of base64 vs base64url spellings of the key. */
    private fun localContactName(publicKey: String): String? {
        if (publicKey.isBlank() || publicKey.startsWith("bot:")) return null
        val std = publicKey.replace('-', '+').replace('_', '/').trimEnd('=')
        val candidates = linkedSetOf(publicKey, std, std + "=".repeat((4 - std.length % 4) % 4),
            std.replace('+', '-').replace('/', '_'))
        return try {
            runBlocking {
                val dao = com.oshi.messenger.data.local.AppDatabase.getInstance(applicationContext).contactDao()
                candidates.firstNotNullOfOrNull { k -> dao.getContactByPublicKey(k)?.alias?.trim()?.takeIf { it.isNotEmpty() } }
            }
        } catch (e: Exception) {
            Log.w(TAG, "local contact name lookup failed: ${e.message}"); null
        }
    }

    private fun isGroupMuted(groupId: String): Boolean {
        if (groupId.isEmpty()) return false
        try {
            // __LEAK_FIX_2026_09_25__ sealed store (was plaintext groups.xml).
            val groupsJson = GroupLocalStore.groupsJson(this) ?: return false
            val jsonArray = org.json.JSONArray(groupsJson)
            for (i in 0 until jsonArray.length()) {
                val group = jsonArray.getJSONObject(i)
                // Case-insensitive, like GroupManager.isGroupMuted: iOS sends upper-case
                // UUIDs, Android stores lower-case — an exact compare let a muted
                // group's push ring whenever an iPhone was the sender.
                if (group.optString("id").equals(groupId.trim(), ignoreCase = true)) {
                    return group.optBoolean("isMuted", false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check group mute status: ${e.message}")
        }
        return false
    }

    /**
     * Create notification channels (required for Android 8.0+)
     * Similar to iOS notification categories
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Channel names and descriptions are shown by the Android system
            // Settings app, in the device locale — a raw English literal here is
            // the most-seen unlocalized string in a 34-language app.
            // Messages channel
            val messagesChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                getString(R.string.channel_messages_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_messages_desc)
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(messagesChannel)

            // Calls channel (highest priority) with custom OSHI ringtone (res/raw/oshi_ringtone.ogg)
            val oshiRingtoneResId = resources.getIdentifier("oshi_ringtone", "raw", packageName)
            val oshiRingtoneUri = if (oshiRingtoneResId != 0) {
                android.net.Uri.parse("android.resource://${packageName}/$oshiRingtoneResId")
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
            val callsChannel = NotificationChannel(
                CHANNEL_CALLS,
                getString(R.string.channel_calls_name),
                NotificationManager.IMPORTANCE_MAX
            ).apply {
                description = getString(R.string.channel_calls_desc)
                enableVibration(true)
                enableLights(true)
                setBypassDnd(true)  // Important for calls
                setSound(oshiRingtoneUri, android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
            }
            notificationManager.createNotificationChannel(callsChannel)

            // System channel
            val systemChannel = NotificationChannel(
                CHANNEL_SYSTEM,
                getString(R.string.channel_system_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.channel_system_desc)
            }
            notificationManager.createNotificationChannel(systemChannel)

            Log.d(TAG, "📱 Notification channels created")
        }
    }

    private fun isAppForeground(): Boolean = try {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    } catch (_: Exception) { false }

}
