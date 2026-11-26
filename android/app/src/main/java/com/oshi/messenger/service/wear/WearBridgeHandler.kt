package com.oshi.messenger.service.wear

import com.oshi.wearbridge.WearConvKind
import com.oshi.wearbridge.WearConversation
import com.oshi.wearbridge.WearMessage
import com.oshi.wearbridge.WearNet
import com.oshi.wearbridge.WearOp
import com.oshi.wearbridge.WearProtocol
import com.oshi.wearbridge.WearRequest
import com.oshi.wearbridge.WearResponse
import com.oshi.wearbridge.WearStatus
import com.oshi.wearbridge.WearVoiceFrame

/**
 * __WEAR_BRIDGE_2026_11_17__ What the phone needs to answer a watch. The real
 * implementation ([PhoneWearBackend]) reads the local database and calls the
 * app's existing send / reaction code; tests substitute a fake.
 */
interface WearBridgeBackend {
    /** Settings → Smartwatch. OFF by default. */
    suspend fun isEnabled(): Boolean

    /** The app lock is engaged ([WearLockPolicy]). */
    suspend fun isLocked(): Boolean

    /** An identity is loaded and the message pipeline initialised. */
    fun isReady(): Boolean

    suspend fun conversations(limit: Int): List<WearConversation>

    /** Null when the conversation does not exist. */
    suspend fun thread(kind: WearConvKind, id: String, limit: Int): Thread?

    /** Send [text] through the existing send path. */
    suspend fun send(kind: WearConvKind, id: String, text: String): WearStatus

    /** Toggle [emoji] on [messageId] through the existing reaction path. */
    suspend fun react(kind: WearConvKind, id: String, messageId: String, emoji: String): WearStatus

    // ---- __WEAR_POLISH_2026_11_25__ ---------------------------------------

    /** The person opened the thread on the watch: same as opening it on the phone. */
    suspend fun markRead(kind: WearConvKind, id: String): WearStatus

    /**
     * A small JPEG of one PHOTO message of this conversation, or null when
     * there is none to give (not a photo, view-once, missing, undecodable).
     */
    suspend fun thumbnail(kind: WearConvKind, id: String, messageId: String): ByteArray?

    /** Send [audio] (m4a, already checked) as a voice message through the existing path. */
    suspend fun sendVoice(kind: WearConvKind, id: String, audio: ByteArray, durationMs: Long): WearStatus

    /** How the phone is connected right now (counts only). */
    fun network(): WearNet?

    /** Strict security mode: glanceable surfaces on the watch drop names. */
    fun strictGlance(): Boolean = false

    data class Thread(val title: String, val canReply: Boolean, val messages: List<WearMessage>)
}

/**
 * Turns one request into one response. Order of the gates is the security
 * model, and is pinned by `WearBridgeHandlerTest`:
 *
 *  1. undecodable bytes → no answer at all (we cannot even name the request);
 *  2. bridge OFF → [WearStatus.DISABLED], before ANY database access;
 *  3. malformed / over limit / reaction outside the quick set → INVALID;
 *  4. app lock engaged → LOCKED;
 *  5. no identity / pipeline not initialised → UNAVAILABLE;
 *  6. only then the backend is asked.
 */
class WearBridgeHandler(private val backend: WearBridgeBackend) {

    suspend fun handle(bytes: ByteArray?): WearResponse? {
        val req = WearRequest.decode(bytes) ?: return null
        // A VOICE request only ever arrives as a channel frame with its audio; as a plain
        // message it passes the same gates (off is still DISABLED first) and is then INVALID.
        return answer(req, audio = null)
    }

    /**
     * __WEAR_POLISH_2026_11_25__ A voice message frame from the voice channel.
     * Undecodable, oversized or not-m4a → no answer (as for undecodable
     * bytes); otherwise the same gates, in the same order, as [handle].
     */
    suspend fun handleVoice(frame: Pair<WearRequest, ByteArray>?): WearResponse? {
        val (req, audio) = frame ?: return null
        if (req.op != WearOp.VOICE) return null
        return answer(req, audio)
    }

    suspend fun handleVoice(bytes: ByteArray): WearResponse? = handleVoice(WearVoiceFrame.decode(bytes))

    private suspend fun answer(req: WearRequest, audio: ByteArray?): WearResponse {
        if (!backend.isEnabled()) return WearResponse.status(req.rid, WearStatus.DISABLED)
        if (!req.isWellFormed()) return WearResponse.status(req.rid, WearStatus.INVALID)
        if (backend.isLocked()) return WearResponse.status(req.rid, WearStatus.LOCKED)
        if (!backend.isReady()) return WearResponse.status(req.rid, WearStatus.UNAVAILABLE)
        return try {
            when (req.op) {
                WearOp.LIST -> {
                    val limit = req.limit.takeIf { it > 0 }?.coerceAtMost(WearProtocol.MAX_CONVERSATIONS)
                        ?: WearProtocol.MAX_CONVERSATIONS
                    WearResponse(
                        req.rid, WearStatus.OK, conversations = backend.conversations(limit),
                        caps = WearProtocol.PHONE_CAPS,
                        net = runCatching { backend.network() }.getOrNull(),
                        strict = runCatching { backend.strictGlance() }.getOrDefault(true),
                    )
                }
                WearOp.THREAD -> {
                    val limit = req.limit.takeIf { it > 0 }?.coerceAtMost(WearProtocol.MAX_THREAD_MESSAGES)
                        ?: WearProtocol.MAX_THREAD_MESSAGES
                    val t = backend.thread(req.kind!!, req.cid!!, limit)
                        ?: return WearResponse.status(req.rid, WearStatus.NOT_FOUND)
                    WearResponse(req.rid, WearStatus.OK, messages = t.messages, title = t.title)
                }
                WearOp.SEND -> WearResponse.status(req.rid, backend.send(req.kind!!, req.cid!!, req.text!!.trim()))
                WearOp.REACT -> WearResponse.status(
                    req.rid, backend.react(req.kind!!, req.cid!!, req.messageId!!, req.emoji!!)
                )
                WearOp.READ -> WearResponse.status(req.rid, backend.markRead(req.kind!!, req.cid!!))
                WearOp.THUMB -> {
                    val jpeg = backend.thumbnail(req.kind!!, req.cid!!, req.messageId!!)
                        ?.takeIf { it.isNotEmpty() && it.size <= WearProtocol.MAX_THUMB_BYTES }
                        ?: return WearResponse.status(req.rid, WearStatus.NOT_FOUND)
                    WearResponse(req.rid, WearStatus.OK, thumb = jpeg)
                }
                WearOp.VOICE -> {
                    val bytes = audio ?: return WearResponse.status(req.rid, WearStatus.INVALID)
                    WearResponse.status(req.rid, backend.sendVoice(req.kind!!, req.cid!!, bytes, req.durationMs))
                }
            }
        } catch (e: Exception) {
            WearResponse.status(req.rid, WearStatus.ERROR)
        }
    }
}

/**
 * __WEAR_BRIDGE_2026_11_17__ Whether the phone's app lock forbids the watch
 * from reading. Pure, so every branch is a unit test.
 *
 * The phone's lock (MainActivity.checkAutoLockOnForeground) engages only when
 * biometric lock is ON and an auto-lock delay is set; it covers the UI once
 * the app has been in the background for that long. The watch must never be
 * a way around it, so it is LOCKED whenever the phone UI would be, and also
 * while the phone UI has not been opened since this process started (a
 * process woken by the watch has never seen the user pass the lock).
 */
object WearLockPolicy {
    fun isLocked(
        biometricEnabled: Boolean,
        autoLockTimeoutSeconds: Int,
        overlayShowing: Boolean,
        everForegrounded: Boolean,
        inForeground: Boolean,
        backgroundedAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (overlayShowing) return true
        if (!biometricEnabled || autoLockTimeoutSeconds <= 0) return false
        if (inForeground) return false
        if (!everForegrounded || backgroundedAtMs <= 0L) return true
        return nowMs - backgroundedAtMs >= autoLockTimeoutSeconds * 1000L
    }
}

/** Process-wide mirror of the phone UI's lock state, fed by MainActivity. */
object WearLockState {
    @Volatile var everForegrounded: Boolean = false
        private set
    @Volatile var inForeground: Boolean = false
        private set
    @Volatile var backgroundedAtMs: Long = 0L
        private set
    @Volatile var overlayShowing: Boolean = false

    fun onUiStarted() {
        everForegrounded = true
        inForeground = true
    }

    fun onUiStopped(nowMs: Long = System.currentTimeMillis()) {
        inForeground = false
        backgroundedAtMs = nowMs
    }

    fun isLocked(biometricEnabled: Boolean, autoLockTimeoutSeconds: Int, nowMs: Long = System.currentTimeMillis()) =
        WearLockPolicy.isLocked(
            biometricEnabled, autoLockTimeoutSeconds, overlayShowing,
            everForegrounded, inForeground, backgroundedAtMs, nowMs,
        )
}
