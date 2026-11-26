package com.oshi.messenger.network.web

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.oshi.messenger.R
import com.oshi.messenger.data.local.AppDatabase
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.v2.V2Signer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

data class WebSession(
    val sessionId: String,
    val browserName: String,
    val browserOS: String,
    val connectedAt: Long,
    val lastActive: Long,
    val isActive: Boolean,
    // __WEBSESSION_E2E_2026_09_23__ `GET /api/web/sessions` now returns `wsv` and `online`.
    // wsv == 2 → end-to-end encrypted (keys on THIS phone); 1/null → the old plaintext relay.
    val wsv: Int? = null,
    val online: Boolean? = null,
    /** The 6-digit code the browser shows for a v2 session (local only, never from the server). */
    val sas: String? = null,
) {
    val isEndToEnd: Boolean get() = wsv == 2
}

sealed class WebConnectionState {
    object Disconnected : WebConnectionState()
    object Connecting : WebConnectionState()
    data class Connected(val session: WebSession) : WebConnectionState()
    data class Error(val message: String) : WebConnectionState()
}

/**
 * OSHI Web relay client — the phone half of the WhatsApp-Web-style companion.
 *
 * Reference implementation: iOS `WebSessionManager.swift`. Every wire shape in
 * this file is a port of a specific line there and is cited inline. The two
 * things that made OSHI Web one-way on Android before this pass:
 *
 *  1. the auth envelope was `{"type":"auth","public_key":…}` where the relay
 *     (and iOS, `WebSessionManager.swift:266`) speak
 *     `{"type":"mobile_auth","publicKey":…}` — so the socket was never
 *     authenticated and no session was ever bound to the phone;
 *  2. none of the ten inbound request types the browser sends were handled, so
 *     `request_conversations` / `request_messages` / `request_groups` /
 *     `request_group_messages` all fell through to "Unknown message type" and
 *     the browser showed an empty account, and `message_from_web` / `web_message`
 *     (a message typed in the browser) was dropped on the floor.
 *
 * A third, quieter bug: `_connectionState` only became `Connected` inside
 * `handleSessionEstablished`, which fires on a `session_established` frame the
 * relay never sends. Every outbound push in this file gates on that state, so
 * `notifyNewMessage`, `pushGroupSync`, `pushCallState`, … were all no-ops too.
 * The state now flips on socket open, exactly like iOS sets `isConnected = true`
 * at the end of `connectWebSocket()` (`WebSessionManager.swift:272`).
 */
@Singleton
class WebSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val v2Signer: V2Signer
) {
    companion object {
        private const val TAG = "WebSessionManager"
        private const val WEB_SERVER_URL = "wss://oshi-messenger.com/ws"
        private const val HTTP_BASE_URL = "https://oshi-messenger.com"

        /** Its own channel + stable id for the linked-device security alert. */
        private const val CHANNEL_SECURITY = "oshi_security"
        private const val SECURITY_NOTIF_ID = 90210
        private const val PING_INTERVAL = 30000L // 30 seconds
        private const val RECONNECT_DELAY_MS = 5000L // iOS: WebSessionManager.swift:580

        /** iOS sends full base64 payloads up to 5 MB inline, a URL above it (swift:673). */
        const val INLINE_MEDIA_LIMIT = 5_000_000

        /**
         * (N21) sync_conversations is never split across frames (unlike
         * sync_messages), and the relay hard-refuses any single frame whose
         * ciphertext exceeds [com.oshi.messenger.network.web.WebE2E.MAX_CT_B64]
         * (512 KiB) — well under what a contact-heavy account's avatars could
         * reach. Budget for ALL avatars combined in one sync, comfortably below
         * that ceiling once the text fields and base64/ciphertext overhead are
         * accounted for; whichever conversations don't fit just sync without a
         * photo, exactly as they did before this field existed.
         */
        private const val CONVERSATION_AVATAR_BUDGET_BYTES = 220 * 1024

        /**
         * (N20) How much of a conversation's last message travels as its
         * preview.
         *
         * `lastMessage` carried the FULL body, and the browser draws it in
         * `.convo-preview`, which is `white-space:nowrap; overflow:hidden;
         * text-overflow:ellipsis` — one clipped line, ~40 characters of it on a
         * 375 px sidebar. Everything past that was paid for and never seen.
         *
         * It is also the only UNBOUNDED term in a frame that is never split:
         * `sendToWebSocket` splits `messages_sync` and `group_messages_sync`
         * and nothing else, and [sealAndSend] DROPS any frame over
         * `WebE2E.MAX_CT_B64` with a log line the browser never receives. So a
         * long enough message, or enough conversations, and the web client gets
         * no conversation list at all — an empty sidebar beside a chat pane
         * that cannot be opened, while the status label reads a green "Phone
         * connected". That is Anatole's N20 ("un bug empêche la moitié de
         * l'écran de fonctionner avec un texte en vert") without needing any
         * other explanation.
         */
        private const val CONVERSATION_PREVIEW_MAX_CHARS = 160

        /**
         * (N20) Ceiling for the whole `conversations_sync` payload, under
         * `WebE2E.MAX_CT_B64` (512 KiB) with room for base64 + ciphertext
         * overhead on top of the JSON.
         *
         * Past it, conversations are dropped from the OLDEST end rather than
         * the frame being dropped whole. A browser showing the 300 most recent
         * conversations is usable; a browser showing none is the bug.
         */
        private const val CONVERSATIONS_FRAME_BUDGET_BYTES = 300 * 1024

        /**
         * (N20) Bring one `conversations_sync` payload under the relay's frame
         * ceiling, in two passes, given [rows] MOST-RECENT-FIRST.
         *
         * 1. Photos: keep avatars while they fit
         *    [CONVERSATION_AVATAR_BUDGET_BYTES]; a conversation past it syncs
         *    without its picture, exactly as before that field existed.
         * 2. Rows: keep whole conversations while they fit
         *    [CONVERSATIONS_FRAME_BUDGET_BYTES], and drop from the OLDEST end.
         *
         * The order matters: a photo is worth less than the row it is attached
         * to, so photos go first and no row is ever dropped for a picture that
         * could have been dropped instead.
         *
         * Pure and companion-level so the rule can be measured without a
         * socket, a relay or a database — see `WebConversationBudgetTest`.
         */
        fun fitConversationsInOneFrame(rows: List<JSONObject>): List<JSONObject> {
            var avatarBudget = CONVERSATION_AVATAR_BUDGET_BYTES
            for (conv in rows) {
                val pic = if (conv.has("profilePictureBase64")) conv.getString("profilePictureBase64") else continue
                if (pic.length > avatarBudget) {
                    conv.remove("profilePictureBase64")
                } else {
                    avatarBudget -= pic.length
                }
            }
            var used = 0
            val kept = ArrayList<JSONObject>(rows.size)
            for (conv in rows) {
                val size = conv.toString().length + 1
                if (used + size > CONVERSATIONS_FRAME_BUDGET_BYTES) {
                    Log.w(TAG, "conversations_sync over budget — syncing the newest ${kept.size} of ${rows.size}")
                    break
                }
                used += size
                kept.add(conv)
            }
            return kept
        }

        /**
         * Message timestamps for the web companion.
         *
         * iOS hoists an `ISO8601DateFormatter` with `.withFractionalSeconds`
         * (`WebSessionManager.swift:627-631`) precisely because a bare ISO-8601
         * formatter TRUNCATES TO THE SECOND: several messages sent in the same
         * second all serialise to the same string and the browser has nothing
         * left to order them by. Android used to send raw epoch millis, which the
         * web client parses as a different type entirely.
         */
        private val WEB_TS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        @JvmStatic
        fun webTimestamp(epochMillis: Long): String = WEB_TS.format(Instant.ofEpochMilli(epochMillis))

        /**
         * The exact string the `mobile_auth` frame signs. It MUST be byte-identical
         * to the server verifier (`web_session_server.js verifyMobileAuth`) and to
         * iOS (`WebSessionManager.swift`): the field separator is the two literal
         * characters backslash-n — NOT a newline — so all three sides agree.
         */
        /**
         * __SERVER_CLOCK_EVERY_SIGNER_2026_11_23__ The `mobile_auth` timestamp is checked
         * for freshness by the relay: server time, like every other signed timestamp.
         */
        @JvmStatic
        fun authTimestamp(): String =
            com.oshi.messenger.network.auth.ServerClock.signingNowMs().toString()

        @JvmStatic
        fun canonicalMobileAuth(publicKey: String, timestamp: String, nonce: String): String =
            "OSHI-MOBILE-AUTH-V1\\n1\\n$publicKey\\n$timestamp\\n$nonce\\n/ws"

        /**
         * The signed `mobile_auth` envelope (iOS parity, `WebSessionManager.swift`).
         * `sign` receives the canonical UTF-8 bytes and returns the raw 64-byte
         * Ed25519 signature; `signingPublicKeyB64` is the raw 32-byte Ed25519 public
         * key, base64. The relay rejects any frame whose signature does not verify
         * against `signingPublicKey`, so an unsigned or spoofed frame never binds the
         * socket.
         */
        @JvmStatic
        fun buildSignedAuthEnvelope(
            publicKey: String,
            signingPublicKeyB64: String,
            timestamp: String,
            nonce: String,
            wsv: Int? = null,
            sign: (ByteArray) -> ByteArray
        ): JSONObject {
            val canonical = canonicalMobileAuth(publicKey, timestamp, nonce)
            val signatureB64 = Base64.getEncoder()
                .encodeToString(sign(canonical.toByteArray(Charsets.UTF_8)))
            return JSONObject().apply {
                put("type", "mobile_auth")
                put("protocolVersion", 1)
                put("publicKey", publicKey)
                put("timestamp", timestamp)
                put("nonce", nonce)
                put("path", "/ws")
                put("signingPublicKey", signingPublicKeyB64)
                put("signature", signatureB64)
                // __WEBSESSION_E2E_2026_09_23__ outside the signed canonical (unchanged); the
                // server only upgrades a SIGNED auth to v2.
                if (wsv != null) put("wsv", wsv)
            }
        }

        /** iOS `syncConversationsToWeb` row shape (`WebSessionManager.swift:635-646`). */
        @JvmStatic
        fun buildConversationJson(
            publicKey: String,
            contactName: String,
            lastMessage: String,
            lastMessageEpochMillis: Long,
            unreadCount: Int,
            profilePictureBase64: String? = null
        ): JSONObject = JSONObject().apply {
            put("publicKey", publicKey)
            put("contactName", contactName)
            put("lastMessage", lastMessage)
            put("lastMessageTime", webTimestamp(lastMessageEpochMillis))
            put("unreadCount", unreadCount)
            // iOS adds `alias` as a backward-compat duplicate of contactName (swift:644).
            put("alias", contactName)
            // (N21) Groups have sent `groupPictureBase64` in sync_groups since
            // this channel existed; 1:1 contacts never sent the equivalent
            // here, only on a live profile_picture_update push — which only
            // fires on a CHANGE, never on the initial sync. A contact whose
            // photo was already set before the web session connects stayed
            // blank until they happened to update it again. web.html already
            // reads `profilePictures[c.publicKey]` when drawing this list
            // (see its own comment at that call site) — it was only ever
            // missing a writer for the initial snapshot.
            if (profilePictureBase64 != null) put("profilePictureBase64", profilePictureBase64)
        }

        /** iOS `syncMessagesToWeb` / `notifyNewMessage` row shape (swift:655-687, 965-992). */
        @JvmStatic
        fun buildMessageJson(
            id: String,
            content: String,
            epochMillis: Long,
            isFromMe: Boolean,
            senderPublicKey: String,
            recipientPublicKey: String,
            status: String,
            mediaType: String? = null,
            fileName: String? = null,
            mediaSize: Int? = null,
            mediaBase64: String? = null,
            inlineLimit: Int = INLINE_MEDIA_LIMIT
        ): JSONObject = JSONObject().apply {
            put("id", id)
            put("content", content)
            put("timestamp", webTimestamp(epochMillis))
            put("isFromMe", isFromMe)
            put("senderPublicKey", senderPublicKey)
            put("recipientPublicKey", recipientPublicKey)
            put("status", status)
            if (mediaType != null) {
                put("mediaType", mediaType)
                if (fileName != null) put("fileName", fileName)
                if (mediaSize != null) {
                    put("mediaSize", mediaSize)
                    if (mediaSize <= inlineLimit && mediaBase64 != null) {
                        put("mediaData", mediaBase64)
                        // iOS keeps a small-image thumbnail duplicate (swift:677-679).
                        if (mediaType == "image" && mediaSize < 100_000) put("thumbnailData", mediaBase64)
                    } else {
                        put("mediaUrl", "/api/web/media/$id")
                    }
                }
            }
        }

        /** iOS `mapDeliveryStatus` (`WebSessionManager.swift:928-937`). */
        @JvmStatic
        fun mapDeliveryStatus(isRead: Boolean, isDelivered: Boolean, isFailed: Boolean, isFromMe: Boolean): String =
            when {
                isRead -> "read"
                isFailed -> "failed"
                isDelivered -> "delivered"
                isFromMe -> "sent"
                else -> "delivered"
            }

        /** iOS `mapMediaType` (`WebSessionManager.swift:939-947`). */
        @JvmStatic
        fun mapMediaType(name: String?): String? = when (name?.uppercase()) {
            "IMAGE" -> "image"
            "VIDEO" -> "video"
            "AUDIO" -> "audio"
            "DOCUMENT" -> "file"
            "CONTACT" -> "contact"
            "LOCATION" -> "location"
            else -> null
        }

        /**
         * Any numeric `timestamp` / `lastMessageTime` in an outbound payload is
         * rewritten to the iOS ISO-8601-with-millis form. Callers outside this
         * class (MessageRepository builds the group JSON) still emit epoch
         * millis; the browser is written against the iOS shape, so normalise at
         * the edge rather than trusting every caller.
         */
        @JvmStatic
        fun normalizeTimestamps(obj: JSONObject): JSONObject {
            for (key in listOf("timestamp", "lastMessageTime")) {
                val v = obj.opt(key)
                if (v is Number) obj.put(key, webTimestamp(v.toLong()))
            }
            return obj
        }

        /**
         * Markers that exist ONLY between this app and its linked browser. They are
         * not on the peer-to-peer wire, so [OSHIControlPayload.catalog] does not and
         * must not know them — this is the whole remainder of the old list.
         */
        private val WEB_ONLY_PREFIXES = listOf("🔄SYNC🔄", "🔗LINK🔗")

        /**
         * The one catalog entry this surface filters that the catalog does NOT call
         * silent. `📸PROFILE_UPDATE📸` is `Kind.RENDERED` because the chat draws it
         * as the system notice "You updated your profile" — but the payload behind
         * it is a JSON blob carrying the sender's profile picture, and the browser
         * is sent `content` RAW (see [notifyNewMessage]). The old hand-written list
         * filtered it and dropping that would have been a silent regression, so it
         * is kept explicitly rather than by accident.
         */
        private val WEB_FILTERED_RENDERED = listOf(OSHIControlPayload.PROFILE_UPDATE)

        /**
         * Sentinel/control messages never belong in a conversation preview, in the
         * message list mirrored to a linked browser, or in a `new_message` push to
         * it. iOS filters upstream (`WebSessionManager.swift` has no list of its own
         * at all — `MessageManager.conversations` is already filtered).
         *
         * H-3. This used to be a hand-written `CONTROL_PREFIXES` list of TEN
         * entries — the SIXTH partial copy of a catalog that exists precisely to
         * end partial copies. It was missing `📞CALL_SIGNAL📞` (SDP and ICE
         * candidates), `📢GROUP_UPDATE📢` (the whole roster and admin set) and
         * `__SESSION_RESET__`, all three `Kind.SILENT`, so a linked browser was
         * mirrored those payloads body and all, and they surfaced as the
         * conversation's "latest message" in the web sidebar (`buildConversations`).
         *
         * It also spelled its emoji prefixes by hand, so it had none of the
         * catalog's U+FE0F-stripped variant handling
         * (`OSHIControlPayload.kt:126-153`) and silently missed the bare-codepoint
         * half of `⌨️TYPING⌨️` / `✍️TYPING✍️` traffic — which is the specific way
         * the four retired Android lists failed, and why four bubble renderers were
         * once unreachable.
         *
         * Note this is now STRICTLY wider than the old list in a second way:
         * `OSHIControlPayload.isSilent` also catches the sentinel-less group sync
         * JSON (`{"type":"sync_request",…}`).
         */
        @JvmStatic
        fun isControlMessage(content: String): Boolean =
            OSHIControlPayload.isSilent(content) ||
                WEB_FILTERED_RENDERED.any { it == OSHIControlPayload.matchedPrefix(content) } ||
                WEB_ONLY_PREFIXES.any { content.startsWith(it) }
    }
    /** Where the linked-device baseline lives. See [LinkedDeviceWatch]. */
    private val linkPrefs by lazy {
        context.getSharedPreferences("oshi_linked_devices", Context.MODE_PRIVATE)
    }

    /**
     * What the last session check amounted to.
     *
     * `Linked` is the case a user must be shown without opening any screen. Exposed as
     * state rather than a one-shot event so a screen opened after the fact still sees it.
     */
    private val _lastLinkCheck =
        MutableStateFlow<LinkedDeviceWatch.Outcome>(LinkedDeviceWatch.Outcome.Unchanged)
    val lastLinkCheck: StateFlow<LinkedDeviceWatch.Outcome> = _lastLinkCheck.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastRefresh = MutableStateFlow<Long?>(null)
    val lastRefresh: StateFlow<Long?> = _lastRefresh.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .addInterceptor(com.oshi.messenger.network.auth.ServerClock.interceptor)
        .pingInterval(PING_INTERVAL, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null

    private val _connectionState = MutableStateFlow<WebConnectionState>(WebConnectionState.Disconnected)
    val connectionState: StateFlow<WebConnectionState> = _connectionState.asStateFlow()

    private val _activeSessions = MutableStateFlow<List<WebSession>>(emptyList())
    val activeSessions: StateFlow<List<WebSession>> = _activeSessions.asStateFlow()

    private val _qrCodeData = MutableStateFlow<String?>(null)
    val qrCodeData: StateFlow<String?> = _qrCodeData.asStateFlow()

    private val database by lazy { AppDatabase.getInstance(context) }

    // =========================================================================
    // __WEBSESSION_E2E_2026_09_23__ OSHI Web v2 — end-to-end (CLIENT_SPEC §2-§5)
    // =========================================================================

    /** Per-session keys (`k_b2p`, `k_p2b`, SAS), Keystore-wrapped. Never leave this phone. */
    private val e2eStore by lazy { WebE2EKeyStore(context) }
    /** One channel per v2 session for the life of ONE socket (a new socket = new send epochs). */
    private val channels = java.util.concurrent.ConcurrentHashMap<String, WebE2E.Channel>()
    /** v2 sessions whose browser is connected right now (`e2e_peers` / `e2e_peer`). */
    private val onlineSids: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** True when THIS socket authenticated with `wsv:2` (and so never sends v1 plaintext). */
    @Volatile private var socketV2 = false
    @Volatile private var v1RevokeDone = false
    /** `scope:messageId` → sid of the browser that asked for it through `media_get`. */
    private val pendingMediaRequests = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val uploads = WebE2E.UploadAssembler()
    /** Media frames awaiting their `e2e_status` (at most 16 in flight, §4.4 "Pacing"). */
    private val mediaWindow = MediaWindow(16)
    private val SID_RE = Regex("^[0-9a-f]{48}$")

    /** v2 inline-media rule: `mediaData` only up to 256 KiB, else a `mediaUrl` the page resolves via `media_get`. */
    private fun inlineLimit(): Int = if (socketV2) WebE2E.INLINE_MEDIA_LIMIT_V2 else INLINE_MEDIA_LIMIT

    /** True when at least one linked browser uses the end-to-end protocol. */
    fun hasEndToEndSessions(): Boolean = e2eStore.hasAny()

    private fun channelFor(sid: String): WebE2E.Channel? {
        channels[sid]?.let { return it }
        val k = e2eStore.keys(sid) ?: return null
        return WebE2E.Channel(sid, sendKey = k.second, recvKey = k.first).also { channels[sid] = it }
    }

    private fun forgetE2ESession(sid: String) {
        e2eStore.remove(sid)
        channels.remove(sid)
        onlineSids.remove(sid)
        pendingMediaRequests.values.removeAll { it == sid }
    }

    /** Seal one inner object for [sid] and send it. Returns the frame's `ep:seq`, or null. */
    private fun sealAndSend(sid: String, inner: JSONObject): String? {
        val ch = channelFor(sid) ?: return null
        val frame = ch.seal(inner)
        if (frame.getString("ct").length > WebE2E.MAX_CT_B64) {
            Log.w(TAG, "e2e frame for ${inner.optString("t")} over the relay limit — dropped")
            return null
        }
        val ok = webSocket?.send(frame.toString()) == true
        return if (ok) frame.getString("ep") + ":" + frame.getInt("seq") else null
    }

    /** A `b2p` frame: open it with that session's key, then dispatch the inner command. */
    private fun handleE2EFrame(frame: JSONObject) {
        val sid = frame.optString("sid")
        if (frame.optString("dir") != "b2p") return
        val ch = channelFor(sid) ?: run { Log.w(TAG, "e2e frame for an unknown session — dropped"); return }
        val inner = try { ch.open(frame) } catch (e: WebE2E.FrameException) {
            Log.w(TAG, "e2e frame refused: ${e.message}")   // never displayed
            return
        }
        onlineSids.add(sid)
        dispatchInner(sid, inner)
    }

    /** Page → phone catalogue (§4.4). Anything else inside the channel is ignored. */
    private fun dispatchInner(sid: String, inner: JSONObject) {
        when (inner.optString("t")) {
            "request_conversations" -> syncConversationsToWeb(sid)
            "request_messages" -> inner.optString("contactPublicKey").takeIf { it.isNotEmpty() }?.let { syncMessagesToWeb(it, sid) }
            "request_groups" -> syncGroupsToWeb(sid)
            "request_group_messages" -> inner.optString("groupId").takeIf { it.isNotEmpty() }?.let { syncGroupMessagesToWeb(it, sid) }
            "send_message" -> {
                val content = inner.optString("content")
                val recipient = inner.optString("recipientPublicKey")
                val clientMsgId = inner.optString("clientMsgId")
                if (content.isEmpty() || recipient.isEmpty()) return
                scope.launch {
                    val ok = try {
                        onWebSendMessageResult?.invoke(content, recipient)
                            ?: onWebSendMessage?.let { it(content, recipient); true } ?: false
                    } catch (e: Exception) { false }
                    sendToWebSocket(JSONObject().put("type", "send_result").put("clientMsgId", clientMsgId)
                        .put("status", if (ok) "sent" else "failed"), sid)
                }
            }
            "send_file_chunk" -> {
                val done = uploads.feed(inner) ?: return
                scope.launch {
                    val ok = try { onWebSendFile?.invoke(done) ?: false } catch (e: Exception) { false }
                    sendToWebSocket(JSONObject().put("type", "send_result").put("clientMsgId", done.uploadId)
                        .put("status", if (ok) "sent" else "failed"), sid)
                }
            }
            "media_get" -> {
                val mid = inner.optString("messageId").takeIf { it.isNotEmpty() } ?: return
                val mediaScope = inner.optString("scope").ifEmpty { "private" }
                pendingMediaRequests["$mediaScope:$mid"] = sid
                val cb = onRequestMedia
                if (cb == null) sendMediaChunks(sid, mid, mediaScope, null, null, null, "not_found")
                else cb(mid, mediaScope)
            }
            // Same field shapes as v1: reuse the v1 handlers verbatim.
            "web_call_initiate", "web_call_accept", "web_call_reject", "web_create_group", "web_add_group_member" -> {
                val d = JSONObject(inner.toString())
                d.put("type", d.optString("t")); d.remove("t"); d.remove("_ts")
                handleMessage(d.toString())
            }
            else -> Log.w(TAG, "e2e: ignored inner type ${inner.optString("t")}")
        }
    }

    private fun handleE2EStatus(json: JSONObject) {
        val key = json.optString("ep") + ":" + json.optInt("seq")
        val state = json.optString("state")
        mediaWindow.settle(key, state)
        if (state == "rejected") Log.w(TAG, "e2e frame rejected: ${json.optString("reason")}")
    }

    /**
     * Stream one attachment to the browser that asked, as `media_chunk` frames, keeping at most 16
     * unacknowledged. `queued` = the tab closed: stop, the page asks again when it is back.
     */
    private fun sendMediaChunks(sid: String, messageId: String, mediaScope: String, contentType: String?,
                                fileName: String?, dataBase64: String?, error: String?) {
        scope.launch {
            val bytes = if (error == null) dataBase64?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() } else null
            if (bytes == null || bytes.isEmpty()) {
                sendToWebSocket(JSONObject().put("type", "media_error").put("messageId", messageId)
                    .put("scope", mediaScope).put("error", error ?: "not_found"), sid)
                return@launch
            }
            mediaWindow.begin()
            for (chunk in WebE2E.mediaChunks(messageId, mediaScope, contentType, fileName, bytes)) {
                if (!mediaWindow.acquire()) { Log.w(TAG, "media stream stalled — stopping"); return@launch }
                val key = sealAndSend(sid, chunk)
                if (key == null) { mediaWindow.release(); return@launch }
                if (!mediaWindow.track(key)) return@launch     // browser went away (queued): stop
            }
        }
    }

    /**
     * §5: on the first v2 connect after the update, revoke this account's remaining v1 sessions —
     * a v2 socket can no longer serve them, and they must not linger as plaintext pairings.
     */
    private fun revokeV1SessionsOnce() {
        if (v1RevokeDone) return
        v1RevokeDone = true
        scope.launch {
            fetchActiveSessions()
            for (s in _activeSessions.value) if (s.wsv != 2) revokeSession(s.sessionId)
        }
    }

    /** `web_sync` wake push (`reason:"web_e2e"`, no content): connect, then deliver. */
    fun onWakePush() {
        if (_connectionState.value is WebConnectionState.Connected) return
        scope.launch { fetchActiveSessions() }
    }

    /**
     * Web → phone text with a verdict for `send_result` (v2). Wired by MessageRepository; when
     * absent the fire-and-forget [onWebSendMessage] is used and reported as sent.
     */
    var onWebSendMessageResult: (suspend (content: String, recipientPublicKey: String) -> Boolean)? = null

    /** Web → phone file (`send_file_chunk` reassembled). Returns true once handed to the send path. */
    var onWebSendFile: (suspend (WebE2E.UploadAssembler.Done) -> Boolean)? = null

    /** A 16-slot window of media frames awaiting `e2e_status`. */
    private class MediaWindow(private val size: Int) {
        private val sem = kotlinx.coroutines.sync.Semaphore(size)
        private val waiting = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        @Volatile private var stopped = false

        fun begin() { stopped = false }
        suspend fun acquire(): Boolean = withTimeoutOrNull(20_000) { sem.acquire(); true } ?: false
        fun release() { runCatching { sem.release() } }
        /** Returns false when the stream must stop (the page went offline). */
        fun track(key: String): Boolean { waiting[key] = true; return !stopped }
        fun settle(key: String, state: String) {
            if (waiting.remove(key) == null) return
            release()
            if (state == "queued" || state == "dropped") stopped = true
        }
        fun drain() { val n = waiting.size; waiting.clear(); repeat(n) { release() } }
    }

    private fun myPublicKey(): String =
        runCatching { Base64.getEncoder().encodeToString(cryptoManager.getPublicKey()) }.getOrDefault("")

    // =========================================================================
    // DEAD PAIRING PATH — kept only so the (unused) WebSessionViewModel compiles
    // =========================================================================

    // ⚰️ `generatePairingQR()` DELETED (2026-08-13 cross-file integration, item H),
    // together with its only caller `WebSessionViewModel.generateQRCode()`.
    // The `oshi-web:<base64(json)>` payload it used to emit is consumed by NO web
    // client: the real pairing direction is the BROWSER showing a QR that the phone
    // scans, then [validateQRCode] → POST /api/web/qr-validate, which is what iOS does
    // (`OSHI/WebSessionManager.swift:155-203`). It had already been reduced to a
    // deprecated no-op returning null.

    // =========================================================================
    // CONNECTION
    // =========================================================================

    fun connectToWeb(sessionToken: String? = null) {
        if (_connectionState.value is WebConnectionState.Connected) {
            Log.d(TAG, "Already connected to web relay")
            return
        }
        if (webSocket != null) return

        _connectionState.value = WebConnectionState.Connecting
        // __WEBSESSION_E2E_2026_09_23__ a phone holding keys for at least one v2 session speaks v2
        // on this socket (and then never sends v1 plaintext). New socket → new send epochs.
        socketV2 = e2eStore.hasAny()
        channels.clear()
        onlineSids.clear()

        val request = Request.Builder()
            .url(WEB_SERVER_URL)
            .apply {
                sessionToken?.let { addHeader("X-Session-Token", it) }
                addHeader("X-Device-Type", "android")
            }
            .build()

        webSocket = client.newWebSocket(request, createWebSocketListener())
    }

    private fun createWebSocketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected")
            startPingJob()
            sendAuthentication()
            // iOS flips isConnected right after sending mobile_auth
            // (WebSessionManager.swift:272) — it does NOT wait for a frame the
            // relay never sends. Every push below gates on this state.
            _connectionState.value = WebConnectionState.Connected(
                WebSession(
                    sessionId = "relay",
                    browserName = "OSHI Web",
                    browserOS = "",
                    connectedAt = System.currentTimeMillis(),
                    lastActive = System.currentTimeMillis(),
                    isActive = true
                )
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // The relay is a text protocol; a binary frame is not something iOS
            // handles either (`receiveMessage` only unwraps `.string`, swift:318).
            Log.w(TAG, "Ignoring unexpected binary frame (${bytes.size} bytes)")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            cleanup()
            _connectionState.value = WebConnectionState.Disconnected
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket error", t)
            cleanup()
            _connectionState.value = WebConnectionState.Error(t.message ?: "Connection failed")
            scheduleReconnect()
        }
    }

    /** iOS `handleWebSocketDisconnect` retries after 5 s while sessions exist (swift:575-582). */
    private fun scheduleReconnect() {
        if (_activeSessions.value.isEmpty()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            connectToWeb()
        }
    }

    private fun sendAuthentication() {
        val pk = myPublicKey()
        if (pk.isEmpty()) {
            Log.w(TAG, "No public key — cannot authenticate the web relay socket")
            return
        }
        val timestamp = authTimestamp()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        try {
            val signingPublicKey = Base64.getEncoder().encodeToString(cryptoManager.getSigningPublicKey())
            val frame = buildSignedAuthEnvelope(pk, signingPublicKey, timestamp, nonce,
                wsv = if (socketV2) 2 else null) { bytes ->
                cryptoManager.sign(bytes)
            }
            webSocket?.send(frame.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Cannot sign web relay authentication", e)
            disconnect()
        }
    }

    // =========================================================================
    // INBOUND — full port of iOS handleWebSocketMessage (swift:325-503)
    // =========================================================================

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (val type = json.optString("type")) {
                // --- swift:331 ---------------------------------------------------
                "mobile_auth_success" -> {
                    if (socketV2 && json.optInt("wsv", 1) == 2) {
                        // v2: the initial sync runs per browser on `e2e_peers` / `e2e_peer`.
                        Log.d(TAG, "✅ Mobile auth successful (end-to-end)")
                        revokeV1SessionsOnce()
                    } else {
                        socketV2 = false
                        Log.d(TAG, "✅ Mobile auth successful — syncing data to web")
                        performInitialSync()
                    }
                }

                // --- __WEBSESSION_E2E_2026_09_23__ v2 events (CLIENT_SPEC §4.2) -----------
                "e2e" -> handleE2EFrame(json)
                "e2e_peers" -> {
                    val arr = json.optJSONArray("sids")
                    val sids = (0 until (arr?.length() ?: 0)).map { arr!!.optString(it) }
                    onlineSids.clear(); onlineSids.addAll(sids.filter { e2eStore.has(it) })
                    for (sid in onlineSids) performInitialSync(sid)
                }
                "e2e_peer" -> {
                    val sid = json.optString("sid")
                    if (json.optBoolean("online", false) && e2eStore.has(sid)) {
                        onlineSids.add(sid); performInitialSync(sid)
                    } else onlineSids.remove(sid)
                }
                "e2e_session_ended" -> {
                    val sid = json.optString("sid")
                    forgetE2ESession(sid)
                    scope.launch { fetchActiveSessions() }
                }
                "e2e_status" -> handleE2EStatus(json)
                "mobile_auth_failed" -> Log.w(TAG, "Mobile auth refused: ${json.optString("error")}")

                // --- swift:342 ---------------------------------------------------
                "request_conversations" -> syncConversationsToWeb()

                // --- swift:345: tolerates both key spellings ----------------------
                "request_messages" -> {
                    val contact = json.optString("contactPublicKey").ifEmpty { json.optString("contact") }
                    if (contact.isNotEmpty()) syncMessagesToWeb(contact)
                }

                // --- swift:351: {payload:{content, recipientPublicKey}} ------------
                "message_from_web" -> {
                    val payload = json.optJSONObject("payload")
                    val content = payload?.optString("content").orEmpty()
                    val recipient = payload?.optString("recipientPublicKey").orEmpty()
                    if (content.isNotEmpty() && recipient.isNotEmpty()) {
                        handleWebMessage(content, recipient)
                    }
                }

                // --- swift:359: legacy flat form ----------------------------------
                "web_message" -> {
                    val content = json.optString("content")
                    val recipient = json.optString("recipientPublicKey")
                    if (content.isNotEmpty() && recipient.isNotEmpty()) {
                        handleWebMessage(content, recipient)
                    }
                }

                // --- swift:365 ---------------------------------------------------
                "session_revoked" -> scope.launch { fetchActiveSessions() }

                // --- swift:368 ---------------------------------------------------
                "pending_delivered" ->
                    Log.d(TAG, "📤 Server delivered ${json.optInt("count", 0)} pending messages")

                // --- swift:373: informational for web clients, no-op on mobile -----
                "mobile_status" -> Unit

                // --- swift:378 ---------------------------------------------------
                "request_groups" -> syncGroupsToWeb()

                // --- swift:380 ---------------------------------------------------
                "request_group_messages" -> {
                    val gid = json.optString("groupId")
                    if (gid.isNotEmpty()) syncGroupMessagesToWeb(gid)
                }

                // --- swift:386 ---------------------------------------------------
                "web_create_group" -> {
                    val name = json.optString("name").takeIf { it.isNotEmpty() } ?: return
                    // `groupType`, because the envelope `type` is already taken (swift:388).
                    val gType = json.optString("groupType").ifEmpty { "collaborative" }
                    val arr = json.optJSONArray("members")
                    val members = mutableListOf<String>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val pk = arr.optString(i)
                            if (pk.isNotEmpty()) members.add(pk)
                        }
                    }
                    onWebCreateGroup?.invoke(name, gType, members)
                }

                // --- swift:417 ---------------------------------------------------
                "web_add_group_member" -> {
                    val gid = json.optString("groupId").takeIf { it.isNotEmpty() } ?: return
                    val pk = json.optString("memberPublicKey").takeIf { it.isNotEmpty() } ?: return
                    val alias = json.optString("alias").takeIf { it.isNotEmpty() }
                    onWebAddGroupMember?.invoke(gid, pk, alias)
                }

                // --- swift:432 ---------------------------------------------------
                "web_call_initiate" -> {
                    val payload = json.optJSONObject("payload")
                    val peer = json.optString("peerPublicKey").takeIf { it.isNotEmpty() }
                        ?: payload?.optString("peerPublicKey")?.takeIf { it.isNotEmpty() }
                    val isVideo = json.optBoolean("isVideo", false) ||
                        (payload?.optBoolean("isVideo") == true)
                    onWebCallControl?.invoke("initiate", peer, isVideo)
                }
                "web_call_accept" -> onWebCallControl?.invoke("accept", null, false)
                "web_call_reject" -> onWebCallControl?.invoke("reject", null, false)

                // --- swift:497 ---------------------------------------------------
                "request_media" -> {
                    val mid = json.optString("messageId").takeIf { it.isNotEmpty() }
                    val mediaScope = json.optString("scope").ifEmpty { "private" }
                    if (mid != null) onRequestMedia?.invoke(mid, mediaScope)
                }

                // Relay housekeeping. iOS gets these for free from
                // URLSessionWebSocketTask's own ping; OkHttp's pingInterval covers
                // the frame level, this covers an application-level ping.
                "ping" -> sendPong()
                "error" -> {
                    val msg = json.optString("message", "Unknown error")
                    Log.e(TAG, "Web relay error: $msg")
                }

                else -> Log.w(TAG, "Unknown message type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message", e)
        }
    }

    /**
     * A message typed in the browser. iOS routes it straight into
     * `MessageManager.sendMessage` (`WebSessionManager.swift:949-954`); on
     * Android the only component that can encrypt-and-send is MessageRepository,
     * so it arrives through this callback.
     */
    private fun handleWebMessage(content: String, recipient: String) {
        val cb = onWebSendMessage
        if (cb == null) {
            Log.e(TAG, "message_from_web dropped — onWebSendMessage is not wired")
            return
        }
        cb(content, recipient)
    }

    // =========================================================================
    // OUTBOUND SYNC — iOS performInitialSync / syncConversationsToWeb / …
    // =========================================================================

    /** iOS `performInitialSync` (swift:593-616): conversations, groups, then top-5 chats. */
    fun performInitialSync(toSid: String? = null) {
        scope.launch {
            syncConversationsToWeb(toSid)
            syncGroupsToWeb(toSid)
            try {
                val recent = buildConversations().take(5)
                for (c in recent) syncMessagesToWeb(c.getString("publicKey"), toSid)
                val groups = database.groupDao().getAllGroups().first().take(5)
                for (g in groups) syncGroupMessagesToWeb(g.id, toSid)
                Log.d(TAG, "📤 Initial sync completed (${recent.size} chats, ${groups.size} groups)")
            } catch (e: Exception) {
                Log.w(TAG, "Initial sync partial: ${e.message}")
            }
        }
    }

    /**
     * Conversation list. iOS reads `MessageManager.conversations`; Android reads
     * the same underlying data straight from Room so this works with no other
     * component wired up. Control/sentinel envelopes never become a preview.
     *
     * ## Who renders a payload that IS mirrored — the browser, not this file
     *
     * `lastMessage` (and `content` in [syncMessagesToWeb] / [notifyNewMessage] /
     * [syncGroupMessagesToWeb]) is sent RAW for everything [isControlMessage] lets
     * through, so a location, a call summary and a reply envelope reach the browser
     * as sentinel + JSON. That is **deliberate parity**: iOS sends
     * `conv.lastMessage.plaintextContent` and `last.plaintextContent` equally raw
     * (`OSHI/WebSessionManager.swift:639,731`) and has no catalog call anywhere in
     * the file. The web client is therefore the side that owns
     * `OSHIControlPayload` rendering, and this is the only place in the Android
     * tree that records it — rewriting the field here would ALSO change what the
     * browser receives on the wire, which is not this pass's to change. If a
     * browser build is ever found printing the sentinel, the fix belongs in the web
     * client's own catalog port, or the field gets a second, explicitly-named
     * `preview` key rather than a redefinition of `content`.
     */
    private suspend fun buildConversations(): List<JSONObject> {
        val contacts = database.contactDao().getAllContactsList()
        val out = mutableListOf<Pair<Long, JSONObject>>()
        for (c in contacts) {
            val msgs = database.messageDao().getMessagesForContact(c.publicKey).first()
            if (msgs.isEmpty()) continue
            val latest = msgs.firstOrNull { !isControlMessage(it.content) } ?: continue
            val unread = msgs.count { !it.isRead && !it.isFromMe }
            out += latest.timestamp to buildConversationJson(
                publicKey = c.publicKey,
                contactName = c.alias.ifEmpty { c.publicKey.take(8) },
                // (N20) one clipped line in the browser; see
                // CONVERSATION_PREVIEW_MAX_CHARS for why the full body being
                // sent here could cost the entire list.
                lastMessage = latest.content.take(CONVERSATION_PREVIEW_MAX_CHARS),
                lastMessageEpochMillis = latest.timestamp,
                unreadCount = unread,
                profilePictureBase64 = c.profilePicture?.let { Base64.getEncoder().encodeToString(it) }
            )
        }
        val sorted = out.sortedByDescending { it.first }.map { it.second }
        // (N21) Unlike sync_messages/sync_group_messages, sendToWebSocket never
        // splits sync_conversations across frames — and the relay HARD-REFUSES
        // any single frame whose ciphertext exceeds WebE2E.MAX_CT_B64 (512 KiB).
        // A contact-heavy account where many contacts have a photo could push
        // one unbudgeted frame over that line, and the relay would drop the
        // WHOLE sync (every conversation, not just the pictures) — worse than
        // the blank-avatar status quo this is fixing. Keep only as many
        // pictures, most-recent-first, as fit a safe budget; the rest still
        // sync with every field except the photo, exactly as before this change.
        return fitConversationsInOneFrame(sorted)
    }

    fun syncConversationsToWeb(toSid: String? = null) {
        scope.launch {
            try {
                val conversations = buildConversations()
                val arr = JSONArray().apply { conversations.forEach { put(it) } }
                sendToWebSocket(JSONObject().apply {
                    put("type", "sync_conversations")
                    put("conversations", arr)
                }, toSid)
                Log.d(TAG, "📤 Synced ${conversations.size} conversations to web")
            } catch (e: Exception) {
                Log.e(TAG, "syncConversationsToWeb failed: ${e.message}")
            }
        }
    }

    fun syncMessagesToWeb(contactKey: String, toSid: String? = null) {
        scope.launch {
            try {
                val me = myPublicKey()
                val msgs = database.messageDao().getMessagesForContact(contactKey).first()
                    .sortedBy { it.timestamp }
                val arr = JSONArray()
                for (m in msgs) {
                    if (isControlMessage(m.content)) continue
                    val media = m.mediaData
                    arr.put(
                        buildMessageJson(
                            id = m.id,
                            content = m.content,
                            epochMillis = m.timestamp,
                            isFromMe = m.isFromMe || m.senderPublicKey == me,
                            senderPublicKey = m.senderPublicKey,
                            recipientPublicKey = m.recipientPublicKey,
                            status = mapDeliveryStatus(m.isRead, m.isDelivered, m.isFailed, m.isFromMe),
                            mediaType = mapMediaType(m.mediaType?.name),
                            fileName = null,
                            mediaSize = media?.size,
                            mediaBase64 = media?.takeIf { it.size <= inlineLimit() }
                                ?.let { Base64.getEncoder().encodeToString(it) },
                            inlineLimit = inlineLimit()
                        )
                    )
                }
                sendToWebSocket(JSONObject().apply {
                    put("type", "sync_messages")
                    put("contactPublicKey", contactKey)
                    put("messages", arr)
                }, toSid)
                Log.d(TAG, "📤 Synced ${arr.length()} messages for ${contactKey.take(8)}… to web")
            } catch (e: Exception) {
                Log.e(TAG, "syncMessagesToWeb failed: ${e.message}")
            }
        }
    }

    /** iOS `performGroupsSyncOnMain` (swift:703-737). */
    fun syncGroupsToWeb(toSid: String? = null) {
        scope.launch {
            try {
                val groups = database.groupDao().getAllGroups().first()
                val arr = JSONArray()
                for (g in groups) {
                    val msgs = database.groupDao().getGroupMessages(g.id).first()
                    val obj = JSONObject().apply {
                        put("id", g.id)
                        put("name", g.name)
                        put("groupType", g.effectiveGroupType.value)
                        put("adminPublicKey", g.adminPublicKeys.firstOrNull() ?: g.creatorPublicKey)
                        put("members", JSONArray().apply {
                            g.memberPublicKeys.forEach { pk ->
                                put(JSONObject().apply {
                                    put("publicKey", pk)
                                    put("alias", "")
                                    put("isAdmin", g.adminPublicKeys.contains(pk))
                                })
                            }
                        })
                        put("lastMessageTime", webTimestamp(g.updatedAt))
                        put("unreadCount", msgs.count { !it.isRead && !it.isFromMe })
                        put("isMuted", g.isMuted)
                        g.groupImage?.let { put("groupPictureBase64", Base64.getEncoder().encodeToString(it)) }
                        g.groupWallpaperData?.let { put("groupWallpaperBase64", Base64.getEncoder().encodeToString(it)) }
                        msgs.lastOrNull()?.let { put("lastMessage", it.content) }
                    }
                    arr.put(obj)
                }
                sendToWebSocket(JSONObject().apply {
                    put("type", "sync_groups")
                    put("groups", arr)
                }, toSid)
                Log.d(TAG, "📤 Synced ${arr.length()} groups to web")
            } catch (e: Exception) {
                Log.e(TAG, "syncGroupsToWeb failed: ${e.message}")
            }
        }
    }

    /** iOS `performGroupMessagesSyncOnMain` (swift:745-779). */
    fun syncGroupMessagesToWeb(groupId: String, toSid: String? = null) {
        scope.launch {
            try {
                val msgs = database.groupDao().getGroupMessages(groupId).first()
                val arr = JSONArray()
                for (m in msgs) {
                    val obj = JSONObject().apply {
                        put("id", m.id)
                        put("groupId", m.groupId)
                        put("content", m.content)
                        put("timestamp", webTimestamp(m.timestamp))
                        put("senderPublicKey", m.senderPublicKey)
                        put("isFromMe", m.isFromMe)
                        put("isRead", m.isRead)
                        mapMediaType(m.mediaType?.name)?.let { put("mediaType", it) }
                        m.mediaData?.let { d ->
                            put("mediaSize", d.size)
                            if (d.size <= inlineLimit()) {
                                put("mediaData", Base64.getEncoder().encodeToString(d))
                            } else {
                                put("mediaUrl", "/api/web/group-media/${m.id}")
                            }
                        }
                    }
                    arr.put(obj)
                }
                sendToWebSocket(JSONObject().apply {
                    put("type", "sync_group_messages")
                    put("groupId", groupId)
                    put("messages", arr)
                }, toSid)
                Log.d(TAG, "📤 Synced ${arr.length()} group messages for $groupId")
            } catch (e: Exception) {
                Log.e(TAG, "syncGroupMessagesToWeb failed: ${e.message}")
            }
        }
    }

    /**
     * Notify web session of a new message (received or sent).
     * iOS `notifyNewMessage` (`WebSessionManager.swift:956-1000`).
     */
    fun notifyNewMessage(
        messageId: String,
        content: String,
        senderPublicKey: String,
        recipientPublicKey: String,
        isFromMe: Boolean,
        timestamp: Long,
        mediaType: String? = null,    // "image"|"video"|"audio"|"file"|"contact"
        mediaData: ByteArray? = null,
        fileName: String? = null
    ) {
        if (_connectionState.value !is WebConnectionState.Connected) return
        if (isControlMessage(content)) return

        val contactPublicKey = if (isFromMe) recipientPublicKey else senderPublicKey
        val msgData = buildMessageJson(
            id = messageId,
            content = content,
            epochMillis = timestamp,
            isFromMe = isFromMe,
            senderPublicKey = senderPublicKey,
            recipientPublicKey = recipientPublicKey,
            status = if (isFromMe) "sent" else "delivered",
            mediaType = mediaType,
            fileName = fileName,
            mediaSize = mediaData?.size,
            mediaBase64 = mediaData?.takeIf { it.size <= inlineLimit() }
                ?.let { Base64.getEncoder().encodeToString(it) },
            inlineLimit = inlineLimit()
        )

        sendToWebSocket(JSONObject().apply {
            put("type", "new_message")
            put("contactPublicKey", contactPublicKey)
            put("message", msgData)
        })
    }

    // ============================================
    // GROUP SYNC (caller-supplied JSON, from MessageRepository)
    // ============================================

    /** Push the entire group list to the web session. Wire format matches iOS `syncGroupsToWeb`. */
    fun pushGroupSync(groups: List<JSONObject>) {
        if (_connectionState.value !is WebConnectionState.Connected) return
        val arr = JSONArray()
        for (g in groups) arr.put(normalizeTimestamps(g))
        sendToWebSocket(JSONObject().apply {
            put("type", "sync_groups")
            put("groups", arr)
        })
        Log.d(TAG, "Sent sync_groups to web (${groups.size})")
    }

    /** Push messages of a specific group. */
    fun pushGroupMessages(groupId: String, messages: List<JSONObject>) {
        if (_connectionState.value !is WebConnectionState.Connected) return
        val arr = JSONArray()
        for (m in messages) arr.put(normalizeTimestamps(m))
        sendToWebSocket(JSONObject().apply {
            put("type", "sync_group_messages")
            put("groupId", groupId)
            put("messages", arr)
        })
        Log.d(TAG, "Sent sync_group_messages for $groupId (${messages.size})")
    }

    /** Notify web of a single new group message. iOS `notifyNewGroupMessage` (swift:782-811). */
    fun notifyGroupNewMessage(
        messageId: String,
        groupId: String,
        senderPublicKey: String,
        content: String,
        timestamp: Long,
        isFromMe: Boolean,
        isRead: Boolean,
        mediaType: String? = null,
        mediaData: ByteArray? = null,
        fileName: String? = null
    ) {
        if (_connectionState.value !is WebConnectionState.Connected) return

        val msg = JSONObject().apply {
            put("id", messageId)
            put("groupId", groupId)
            put("senderPublicKey", senderPublicKey)
            put("content", content)
            put("timestamp", webTimestamp(timestamp))
            put("isFromMe", isFromMe)
            put("isRead", isRead)
            mediaType?.let { put("mediaType", it) }
            fileName?.let { put("fileName", it) }
            mediaData?.let { d ->
                put("mediaSize", d.size)
                if (d.size <= inlineLimit()) {
                    put("mediaData", Base64.getEncoder().encodeToString(d))
                } else {
                    put("mediaUrl", "/api/web/group-media/$messageId")
                }
            }
        }
        sendToWebSocket(JSONObject().apply {
            put("type", "new_group_message")
            put("groupId", groupId)
            put("message", msg)
        })
    }

    // ============================================
    // PROFILE PIC / WALLPAPER PUSH
    // ============================================

    fun pushProfilePicture(imageBytes: ByteArray?) {
        if (_connectionState.value !is WebConnectionState.Connected) return
        sendToWebSocket(JSONObject().apply {
            put("type", "profile_picture_update")
            put("publicKey", myPublicKey())
            put("imageData", imageBytes?.let { Base64.getEncoder().encodeToString(it) } ?: "")
        })
    }

    fun pushWallpaper(conversationKey: String, wallpaperData: ByteArray?, wallpaperType: String) {
        if (_connectionState.value !is WebConnectionState.Connected) return
        sendToWebSocket(JSONObject().apply {
            put("type", "wallpaper_update")
            put("conversationKey", conversationKey)
            put("wallpaperData", wallpaperData?.let { Base64.getEncoder().encodeToString(it) } ?: "")
            put("wallpaperType", wallpaperType)
        })
    }

    fun pushGroupPicture(groupId: String, imageBytes: ByteArray?) {
        if (_connectionState.value !is WebConnectionState.Connected) return
        sendToWebSocket(JSONObject().apply {
            put("type", "group_picture_update")
            put("groupId", groupId)
            put("imageData", imageBytes?.let { Base64.getEncoder().encodeToString(it) } ?: "")
        })
    }

    fun pushGroupWallpaper(groupId: String, wallpaperData: ByteArray?, wallpaperType: String = "image") {
        if (_connectionState.value !is WebConnectionState.Connected) return
        sendToWebSocket(JSONObject().apply {
            put("type", "group_wallpaper_update")
            put("groupId", groupId)
            put("wallpaperData", wallpaperData?.let { Base64.getEncoder().encodeToString(it) } ?: "")
            put("wallpaperType", wallpaperType)
        })
    }

    // ============================================
    // CALLBACKS — web → mobile actions this class cannot perform itself
    // ============================================

    /** Web requested a call action. Wired in MessageRepository. */
    var onWebCallControl: ((action: String, peerPublicKey: String?, isVideo: Boolean) -> Unit)? = null

    /** Web requested a new group. `groupType` is the raw enum string. */
    var onWebCreateGroup: ((name: String, groupType: String, memberPublicKeys: List<String>) -> Unit)? = null

    /** Web requested a member be added to an existing group. */
    var onWebAddGroupMember: ((groupId: String, memberPublicKey: String, alias: String?) -> Unit)? = null

    /** Server requested a >5 MB media payload sent out-of-band. */
    var onRequestMedia: ((messageId: String, scope: String) -> Unit)? = null

    /**
     * A message typed in the BROWSER, to be encrypted and sent by this phone.
     * MUST be wired by MessageRepository (see the report accompanying this
     * change) or `message_from_web` / `web_message` are dropped and OSHI Web
     * stays read-only.
     */
    var onWebSendMessage: ((content: String, recipientPublicKey: String) -> Unit)? = null

    /** Push a media-response back to the relay so it can stream to the requesting web client. */
    fun sendMediaResponse(
        messageId: String,
        scope: String,
        contentType: String?,
        fileName: String?,
        dataBase64: String?,
        error: String? = null
    ) {
        // __WEBSESSION_E2E_2026_09_23__ a v2 browser asked through `media_get`: answer it, and only it,
        // with `media_chunk` frames (192 KiB, `off`, `last`) or one `media_error`.
        val v2Sid = pendingMediaRequests.remove("$scope:$messageId")
        if (v2Sid != null) {
            sendMediaChunks(v2Sid, messageId, scope, contentType, fileName, dataBase64, error)
            return
        }
        if (socketV2) return  // never plaintext media from a v2 socket
        sendToWebSocket(JSONObject().apply {
            put("type", "media_response")
            put("messageId", messageId)
            put("scope", scope)
            if (error != null) put("error", error)
            if (contentType != null) put("contentType", contentType)
            if (fileName != null) put("fileName", fileName)
            if (dataBase64 != null) put("data", dataBase64)
        })
    }

    /** Push current call state to all linked web sessions. iOS `pushCallState` (swift:906-926). */
    fun pushCallState(
        callId: String,
        state: String, // "ringing"|"connecting"|"connected"|"ended"|"failed"|"declined"
        peerPublicKey: String,
        peerName: String? = null,
        isVideo: Boolean = false,
        direction: String = "incoming",
        errorMessage: String? = null
    ) {
        if (_connectionState.value !is WebConnectionState.Connected) return
        sendToWebSocket(JSONObject().apply {
            put("type", "call_state_update")
            put("callId", callId)
            put("state", state)
            put("peerPublicKey", peerPublicKey)
            put("isVideo", isVideo)
            put("direction", direction)
            put("timestamp", webTimestamp(System.currentTimeMillis()))
            peerName?.let { put("peerName", it) }
            errorMessage?.let { put("error", it) }
        })
    }

    // Terminate specific session
    fun terminateSession(sessionId: String) {
        scope.launch { revokeSession(sessionId) }
    }

    // Terminate all web sessions
    fun terminateAllSessions() {
        scope.launch { revokeAllSessions() }
    }

    // ============================================
    // QR PAIRING (Android scans the QR shown by oshi-messenger.com/web)
    // Mirrors iOS WebSessionManager.validateQRCode (swift:155-203)
    // ============================================
    /**
     * @property code what the screen should say (localised there, never from this English text).
     * @property wsv 2 = the new session is end-to-end encrypted; 1 = the old plaintext relay.
     * @property sas the 6-digit code the browser shows (v2 only).
     */
    data class ValidateResult(
        val success: Boolean,
        val message: String,
        val code: ResultCode = if (success) ResultCode.LINKED_V1 else ResultCode.FAILED,
        val wsv: Int = 1,
        val sas: String? = null,
    )

    enum class ResultCode { LINKED_E2E, LINKED_V1, RELOAD_AND_RESCAN, INVALID_QR, FAILED, NETWORK }

    /**
     * __WEBSESSION_E2E_2026_09_23__ (CLIENT_SPEC §2).
     *  - `oshi://web-auth?…&v=2&k=…&s=…` → v2: fresh X25519 key, confirm tag, keys stored per sessionId.
     *  - a QR that claims v2 with wrong key lengths → rejected.
     *  - an old QR (no `v=2`): paired as v1 exactly as before, WHILE the server still accepts v1
     *    (`OSHI_WEBSESSION_V1=on`; with it off the server answers 426 → "reload and scan again") and
     *    only when this phone has no v2 session (a v2 socket cannot carry a v1 session). The result
     *    then says, truthfully, that the link is NOT end-to-end encrypted.
     */
    suspend fun validateQRCode(qrData: String): ValidateResult = withContext(Dispatchers.IO) {
        val parsed = WebE2E.parseQr(qrData.trim())
        val v2 = parsed as? WebE2E.Qr.V2
        val token: String? = when {
            v2 != null -> v2.token
            parsed is WebE2E.Qr.V1 -> parsed.token
            qrData.startsWith("oshi://web-auth") -> null            // claimed v2 with bad keys, or no token
            qrData.startsWith("oshi:web:") -> qrData.removePrefix("oshi:web:")
            qrData.length == 32 || qrData.length == 64 -> qrData
            else -> null
        }
        if (token.isNullOrBlank()) {
            return@withContext ValidateResult(false, "Invalid QR code format", ResultCode.INVALID_QR)
        }
        if (v2 == null && e2eStore.hasAny()) {
            // This phone already runs end-to-end sessions; its socket cannot carry a plaintext one.
            return@withContext ValidateResult(false, "Reload the OSHI Web page and scan again", ResultCode.RELOAD_AND_RESCAN)
        }
        val pairing = v2?.let { runCatching { WebE2E.pair(it) }.getOrNull() }
            ?: if (v2 != null) return@withContext ValidateResult(false, "Invalid QR code format", ResultCode.INVALID_QR) else null

        try {
            val publicKey = myPublicKey()
            val deviceName = android.os.Build.MODEL ?: "Android"
            val payload = JSONObject().apply {
                put("qrToken", token)
                put("publicKey", publicKey)
                put("deviceName", deviceName)
                if (pairing != null) {
                    put("wsv", 2)
                    put("phoneKey", pairing.phoneKeyB64)
                    put("confirm", pairing.confirmB64)
                }
            }
            val bodyStr = payload.toString()
            val body = bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("$HTTP_BASE_URL/api/web/qr-validate")
                .post(body)
                .signed("POST", "/api/web/qr-validate", bodyStr.toByteArray(Charsets.UTF_8))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (resp.code == 426) {
                    return@withContext ValidateResult(false, "Reload the OSHI Web page and scan again", ResultCode.RELOAD_AND_RESCAN)
                }
                if (resp.isSuccessful && (json?.optBoolean("success", true) != false)) {
                    val sid = json?.optString("sessionId").orEmpty()
                    if (pairing != null) {
                        // The server must confirm v2; a v1 answer to a v2 request is a downgrade.
                        if (json?.optInt("wsv", 1) != 2 || !SID_RE.matches(sid)) {
                            Log.w(TAG, "qr-validate answered without wsv:2 — refusing the downgrade")
                            if (SID_RE.matches(sid)) revokeSession(sid)
                            return@withContext ValidateResult(false, "Reload the OSHI Web page and scan again", ResultCode.RELOAD_AND_RESCAN)
                        }
                        e2eStore.put(sid, pairing.keys.b2p, pairing.keys.p2b, pairing.sas)
                        // (Re)open the relay socket in v2 mode: a v1 socket cannot carry this session.
                        if (!socketV2) disconnect()
                        fetchActiveSessions()
                        if (_connectionState.value !is WebConnectionState.Connected) connectToWeb()
                        return@withContext ValidateResult(true, "Web session linked (end-to-end encrypted)", ResultCode.LINKED_E2E, 2, pairing.sas)
                    }
                    fetchActiveSessions()
                    if (_connectionState.value !is WebConnectionState.Connected) connectToWeb()
                    return@withContext ValidateResult(true, "Web session linked (not end-to-end encrypted)", ResultCode.LINKED_V1, 1)
                }
                val err = json?.optString("error").orEmpty()
                if (err == "update_required") {
                    return@withContext ValidateResult(false, "Reload the OSHI Web page and scan again", ResultCode.RELOAD_AND_RESCAN)
                }
                return@withContext ValidateResult(false, err.ifEmpty { "QR code expired or invalid" })
            }
        } catch (e: Exception) {
            Log.e(TAG, "validateQRCode error", e)
            return@withContext ValidateResult(false, "Network error", ResultCode.NETWORK)
        }
    }

    /** Fetch the list of active web sessions for the current public key. */
    suspend fun fetchActiveSessions() = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            val publicKey = myPublicKey()
            val encoded = java.net.URLEncoder.encode(publicKey, "UTF-8")
            val req = Request.Builder()
                .url("$HTTP_BASE_URL/api/web/sessions?publicKey=$encoded")
                .get()
                .signed("GET", "/api/web/sessions", ByteArray(0))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext
                val text = resp.body?.string().orEmpty()
                val root = JSONObject(text)
                val arr = root.optJSONArray("sessions") ?: return@withContext
                val list = mutableListOf<WebSession>()
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val sid = s.optString("sessionId", s.optString("session_id"))
                    val wsv = if (s.has("wsv")) s.optInt("wsv", 1) else null
                    list.add(
                        WebSession(
                            sessionId = sid,
                            browserName = s.optString("browserName", s.optString("browser", "Browser")),
                            browserOS = s.optString("platformName", s.optString("os", "Computer")),
                            connectedAt = s.optLong("connectedAt", s.optLong("connected_at", 0L)),
                            lastActive = s.optLong("lastActive", s.optLong("last_active", 0L)),
                            isActive = s.optBoolean("isActive", s.optBoolean("is_active", true)),
                            // A session is shown as end-to-end ONLY if the server says v2 AND this
                            // phone holds its keys — never on the server's word alone.
                            wsv = if (wsv == 2 && !e2eStore.has(sid)) 1 else wsv,
                            online = if (s.has("online")) s.optBoolean("online") else null,
                            sas = e2eStore.sas(sid),
                        )
                    )
                }
                // Keys of sessions the server no longer lists (ended while we were away) go.
                e2eStore.retainOnly(list.map { it.sessionId }.toSet())
                // A device must not be able to join this account silently. The list
                // has always been here; what was missing is being TOLD, which is the
                // whole of the linked-device attack (see LinkedDeviceWatch).
                val ids = list.map { it.sessionId }
                _lastLinkCheck.value =
                    LinkedDeviceWatch.classify(ids, LinkedDeviceWatch.loadKnown(linkPrefs))
                LinkedDeviceWatch.saveKnown(linkPrefs, ids)
                (_lastLinkCheck.value as? LinkedDeviceWatch.Outcome.Linked)?.let {
                    Log.w(TAG, "\uD83D\uDD14 ${it.sessionIds.size} device(s) linked since the last check")
                    // Detection alone is not a defence \u2014 the user must actually be TOLD, or the
                    // covert companion-device method works unopposed. Surface it as a real
                    // system notification (this fetch may itself have been triggered by the
                    // server's pairing wake-push, so the phone can warn even from the background).
                    notifyLinkedDevice(it.sessionIds.size)
                }
                _activeSessions.value = list
                _lastRefresh.value = System.currentTimeMillis()
                // Auto-open relay WS while at least one session is linked (swift:221-225)
                if (list.isNotEmpty() && _connectionState.value !is WebConnectionState.Connected) {
                    connectToWeb()
                } else if (list.isEmpty() && _connectionState.value is WebConnectionState.Connected) {
                    disconnect()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchActiveSessions error", e)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Attach the Ed25519 auth headers that prove control of this account's key, via [V2Signer]
     * (the same canonical `METHOD\nPATH\nSHA256(body)\nTIMESTAMP` the server verifies).
     *
     * Without them the server's pairing gate (on `qr-validate`) and owner gate (on the session
     * routes) cannot tell this phone from an attacker binding a device to an address it merely
     * names — the covert companion-device attack. `path` MUST exclude the query string, and
     * `bodyToHash` MUST be the exact bytes sent (0 bytes for a GET). Signing is best-effort:
     * if keys aren't loaded we send unsigned, which under the server's default (count) mode
     * still works and is simply recorded — parity with iOS `WebSessionManager.signRequest`.
     */
    private fun Request.Builder.signed(method: String, path: String, bodyToHash: ByteArray): Request.Builder {
        return try {
            v2Signer.sign(method, path, bodyToHash).forEach { (k, v) -> header(k, v) }
            this
        } catch (e: Exception) {
            Log.w(TAG, "web request signing failed for $path", e)
            this
        }
    }

    /**
     * Warn the user, as a real system notification, that a device was linked to their account.
     *
     * This is the surfacing half of [LinkedDeviceWatch] — the parity of iOS
     * `NotificationManager.sendLinkedDeviceAlert`. Without it the watch only wrote a log line
     * the user never sees, which is exactly the covert window the companion-device method
     * relies on. High-importance so it is heads-up; its own channel so the user can tune it
     * without silencing message notifications.
     */
    private fun notifyLinkedDevice(count: Int) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                nm.getNotificationChannel(CHANNEL_SECURITY) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_SECURITY,
                        "Security alerts",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = "Warnings when a device is linked to your account" }
                )
            }
            val body = if (count > 1)
                context.getString(R.string.linked_device_alert_body_plural, count)
            else
                context.getString(R.string.linked_device_alert_body)
            val notif = NotificationCompat.Builder(context, CHANNEL_SECURITY)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.linked_device_alert_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(SECURITY_NOTIF_ID, notif)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted (Android 13+): the in-app lastLinkCheck flow still
            // surfaces it on the linked-devices screen; nothing to do here but not crash.
            Log.w(TAG, "linked-device alert suppressed (no notification permission)", e)
        } catch (e: Exception) {
            Log.e(TAG, "linked-device alert failed", e)
        }
    }

    /** Revoke a single session via REST. */
    suspend fun revokeSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("sessionId", sessionId)
                put("publicKey", myPublicKey())
            }
            val bodyStr = payload.toString()
            val body = bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("$HTTP_BASE_URL/api/web/sessions/revoke")
                .post(body)
                .signed("POST", "/api/web/sessions/revoke", bodyStr.toByteArray(Charsets.UTF_8))
                .build()
            client.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful
                if (ok) { forgetE2ESession(sessionId); fetchActiveSessions() }
                ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "revokeSession error", e)
            false
        }
    }

    /** Revoke all sessions via REST. */
    suspend fun revokeAllSessions(): Int = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply { put("publicKey", myPublicKey()) }
            val bodyStr = payload.toString()
            val body = bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("$HTTP_BASE_URL/api/web/sessions/revoke-all")
                .post(body)
                .signed("POST", "/api/web/sessions/revoke-all", bodyStr.toByteArray(Charsets.UTF_8))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext 0
                val text = resp.body?.string().orEmpty()
                // Server/iOS return "revokedCount" (WebSessionManager.swift:1011). The old
                // "count" key never exists, so revoke-all always reported 0 revoked.
                val count = runCatching {
                    val json = JSONObject(text)
                    if (json.has("revokedCount")) json.optInt("revokedCount", 0)
                    else json.optInt("count", 0)
                }.getOrDefault(0)
                e2eStore.clear(); channels.clear(); onlineSids.clear()
                fetchActiveSessions()
                count
            }
        } catch (e: Exception) {
            Log.e(TAG, "revokeAllSessions error", e)
            0
        }
    }

    /**
     * The single outbound funnel. v1 socket: the dict as today. v2 socket (§4.3): for each target
     * browser, `{t: type, …}` sealed with that session's `k_p2b`; the v1 plaintext types are NEVER
     * sent from a v2 socket. [toSid] = answer one browser; null = every online v2 browser.
     */
    private fun sendToWebSocket(data: JSONObject, toSid: String? = null) {
        if (!socketV2) {
            webSocket?.send(data.toString())
            return
        }
        val targets = toSid?.let { listOf(it) } ?: onlineSids.toList()
        if (targets.isEmpty()) return
        val inner = WebE2E.toInner(data)
        val parts = when (inner.optString("t")) {
            "messages_sync", "group_messages_sync" -> WebE2E.splitList(inner, "messages")
            else -> listOf(inner)
        }
        for (sid in targets) for (part in parts) sealAndSend(sid, JSONObject(part.toString()))
    }

    /**
     * 🔋 DELIBERATELY LEFT AT 30 s (2026-08-23 battery pass).
     *
     * This is a WebSocket keepalive, not a poll: it only exists between
     * [startPingJob] and the socket closing, i.e. only while the user actually has a
     * browser paired, and stretching it would drop the very session it is holding
     * open. It contributes nothing to the at-rest drain — with no web session there is
     * no socket and no job.
     */
    private fun startPingJob() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(PING_INTERVAL)
                sendPing()
            }
        }
    }

    // Relay housekeeping, never content: sent raw even on a v2 socket.
    private fun sendPing() {
        webSocket?.send(JSONObject().apply {
            put("type", "ping")
            put("timestamp", System.currentTimeMillis())
        }.toString())
    }

    private fun sendPong() {
        webSocket?.send(JSONObject().apply {
            put("type", "pong")
            put("timestamp", System.currentTimeMillis())
        }.toString())
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "User disconnected")
        cleanup()
        _connectionState.value = WebConnectionState.Disconnected
    }

    private fun cleanup() {
        pingJob?.cancel()
        pingJob = null
        webSocket = null
        _qrCodeData.value = null
        channels.clear()
        onlineSids.clear()
        mediaWindow.drain()
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
