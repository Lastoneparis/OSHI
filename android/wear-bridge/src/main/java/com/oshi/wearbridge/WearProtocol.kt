package com.oshi.wearbridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * __WEAR_BRIDGE_2026_11_17__ The phone <-> watch wire format.
 *
 * ONE source file compiled into BOTH `:app` (the phone answers) and `:wear`
 * (the watch asks) through `java.srcDir("../wear-bridge/src/main/java")`, so
 * the two sides cannot drift: there is no second copy to forget.
 *
 * SECURITY MODEL. The watch is a remote VIEW. It never holds an identity key,
 * never talks to an OSHI server and never encrypts anything: it sends a small
 * JSON request over the Wearable Data Layer `MessageClient` (which Play
 * services only delivers between apps with the SAME package name AND the SAME
 * signing certificate on paired nodes) and the phone answers from its local
 * database, or performs the send/react through its own existing send path.
 *
 * Pure Kotlin + org.json (part of the Android platform; a test dependency on
 * the JVM), no Android types, so both modules unit-test it on the JVM.
 */
object WearProtocol {

    const val VERSION = 1

    /** Watch -> phone. The phone's WearableListenerService filters on this prefix. */
    const val PATH_REQUEST = "/oshi/wear/req"

    /** Phone -> watch, answer to one request (matched by [WearRequest.rid]). */
    const val PATH_RESPONSE = "/oshi/wear/resp"

    /**
     * DataClient item carrying ONLY the unread count (a number, nothing that
     * identifies a person or a message). Deleted when the bridge is turned off.
     */
    const val PATH_UNREAD = "/oshi/wear/unread"
    const val KEY_UNREAD = "unread"

    /** Capability the phone app advertises (res/values/wear.xml) so the watch finds it. */
    const val CAPABILITY_PHONE = "oshi_phone_bridge"

    // ---- limits (enforced by both sides) -------------------------------

    /** MessageClient payloads above ~100 KB are refused; stay well under. */
    const val MAX_PAYLOAD_BYTES = 90_000
    const val MAX_CONVERSATIONS = 30
    const val MAX_THREAD_MESSAGES = 20
    const val MAX_SEND_CHARS = 2_000
    const val MAX_PREVIEW_CHARS = 120
    const val MAX_BUBBLE_CHARS = 600
    const val MAX_NAME_CHARS = 64

    /**
     * The quick reactions a watch may send, in display order: the OSHI "O"
     * then the six O-bubble mascots' WIRE emoji. These are the exact strings
     * of the phone's `OshiReaction.QUICK_ROW` (OSHI O = U+2B55, no variation
     * selector; ❤️ = U+2764 U+FE0F) — `WearQuickReactionsParityTest` in :app
     * fails if the two ever differ. The phone refuses anything else.
     */
    val QUICK_REACTIONS: List<String> = listOf(
        "⭕",        // ⭕ OSHI O
        "❤️",  // ❤️
        "😂",  // 😂
        "😢",  // 😢
        "👍",  // 👍
        "😮",  // 😮
        "🔥",  // 🔥
    )
}

enum class WearOp(val wire: String) {
    LIST("list"), THREAD("thread"), SEND("send"), REACT("react");

    companion object {
        fun of(wire: String?): WearOp? = values().firstOrNull { it.wire == wire }
    }
}

/** What happened to a request. Every status except OK means "show a message, no data". */
enum class WearStatus(val wire: String) {
    OK("ok"),
    /** "Smartwatch" is off in OSHI on the phone (the default). */
    DISABLED("disabled"),
    /** The app lock is engaged on the phone. */
    LOCKED("locked"),
    /** The phone app has no identity loaded yet (cold, not set up, wiped). */
    UNAVAILABLE("unavailable"),
    /** Malformed / over-limit / not allowed (e.g. a reaction outside the quick set). */
    INVALID("invalid"),
    /** The conversation or message no longer exists. */
    NOT_FOUND("not_found"),
    /** This conversation cannot be answered (read-only system thread). */
    READ_ONLY("read_only"),
    ERROR("error");

    companion object {
        fun of(wire: String?): WearStatus = values().firstOrNull { it.wire == wire } ?: ERROR
    }
}

enum class WearConvKind(val wire: String) {
    DIRECT("dm"), GROUP("group");

    companion object {
        fun of(wire: String?): WearConvKind? = values().firstOrNull { it.wire == wire }
    }
}

/** Media is never sent to the watch: only what KIND it was, drawn as a labelled placeholder. */
enum class WearMedia(val wire: String) {
    PHOTO("photo"), VIDEO("video"), VOICE("voice"), GIF("gif"),
    FILE("file"), LOCATION("location"), CONTACT("contact");

    companion object {
        fun of(wire: String?): WearMedia? = values().firstOrNull { it.wire == wire }
    }
}

data class WearRequest(
    val rid: String,
    val op: WearOp,
    val kind: WearConvKind? = null,
    /** Contact public key (DIRECT) or group id (GROUP). */
    val cid: String? = null,
    val text: String? = null,
    val messageId: String? = null,
    val emoji: String? = null,
    val limit: Int = 0,
) {
    /**
     * Shape check shared by both sides. The phone calls it before touching
     * anything; a request that fails it is answered [WearStatus.INVALID].
     */
    fun isWellFormed(): Boolean {
        if (rid.isBlank() || rid.length > 64) return false
        if (cid != null && (cid.isBlank() || cid.length > 256)) return false
        return when (op) {
            WearOp.LIST -> true
            WearOp.THREAD -> kind != null && cid != null
            WearOp.SEND -> kind != null && cid != null && !text.isNullOrBlank() &&
                text.length <= WearProtocol.MAX_SEND_CHARS
            WearOp.REACT -> kind != null && cid != null && !messageId.isNullOrBlank() &&
                messageId.length <= 128 && emoji in WearProtocol.QUICK_REACTIONS
        }
    }

    fun encode(): ByteArray = JSONObject().apply {
        put("v", WearProtocol.VERSION)
        put("rid", rid)
        put("op", op.wire)
        kind?.let { put("kind", it.wire) }
        cid?.let { put("cid", it) }
        text?.let { put("text", it) }
        messageId?.let { put("mid", it) }
        emoji?.let { put("emoji", it) }
        if (limit > 0) put("limit", limit)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        /** Null for anything that is not a request this version understands. */
        fun decode(bytes: ByteArray?): WearRequest? {
            if (bytes == null || bytes.isEmpty() || bytes.size > WearProtocol.MAX_PAYLOAD_BYTES) return null
            return runCatching {
                val o = JSONObject(String(bytes, Charsets.UTF_8))
                if (o.optInt("v", -1) != WearProtocol.VERSION) return null
                WearRequest(
                    rid = o.getString("rid"),
                    op = WearOp.of(o.optString("op")) ?: return null,
                    kind = WearConvKind.of(o.optStringOrNull("kind")),
                    cid = o.optStringOrNull("cid"),
                    text = o.optStringOrNull("text"),
                    messageId = o.optStringOrNull("mid"),
                    emoji = o.optStringOrNull("emoji"),
                    limit = o.optInt("limit", 0),
                )
            }.getOrNull()
        }
    }
}

data class WearConversation(
    val kind: WearConvKind,
    val id: String,
    val name: String,
    /** One line, already rendered by the phone ("📷 Photo", a call summary, the text). */
    val preview: String,
    val unread: Int,
    val timestamp: Long,
    /** The key the avatar colour is derived from ([WearAvatar.slot]). */
    val avatarKey: String,
    /** Last line was mine (drawn "You: ..."). */
    val lastFromMe: Boolean = false,
    val canReply: Boolean = true,
)

data class WearMessage(
    val id: String,
    val fromMe: Boolean,
    /** Sender display name; group threads only. */
    val sender: String? = null,
    val senderKey: String? = null,
    val text: String,
    val media: WearMedia? = null,
    val timestamp: Long,
    /** Distinct reactions on it, at most three, for the little chip under the bubble. */
    val reactions: List<String> = emptyList(),
    val myReaction: String? = null,
)

data class WearResponse(
    val rid: String,
    val status: WearStatus,
    val conversations: List<WearConversation> = emptyList(),
    val messages: List<WearMessage> = emptyList(),
    /** Thread title (THREAD) so the watch can draw it after a deep link. */
    val title: String? = null,
) {
    /**
     * Encodes, trimming the OLDEST thread messages / the LAST conversations
     * until the payload fits [WearProtocol.MAX_PAYLOAD_BYTES], so a thread of
     * long messages degrades to fewer bubbles instead of failing to send.
     */
    fun encode(): ByteArray {
        var convs = conversations.take(WearProtocol.MAX_CONVERSATIONS)
        var msgs = messages.takeLast(WearProtocol.MAX_THREAD_MESSAGES)
        while (true) {
            val bytes = encodeRaw(convs, msgs)
            if (bytes.size <= WearProtocol.MAX_PAYLOAD_BYTES || (convs.isEmpty() && msgs.isEmpty())) return bytes
            if (msgs.isNotEmpty()) msgs = msgs.drop(1) else convs = convs.dropLast(1)
        }
    }

    private fun encodeRaw(convs: List<WearConversation>, msgs: List<WearMessage>): ByteArray =
        JSONObject().apply {
            put("v", WearProtocol.VERSION)
            put("rid", rid)
            put("status", status.wire)
            title?.let { put("title", it.clip(WearProtocol.MAX_NAME_CHARS)) }
            if (convs.isNotEmpty()) put("convs", JSONArray().apply {
                convs.forEach { c ->
                    put(JSONObject().apply {
                        put("kind", c.kind.wire)
                        put("cid", c.id)
                        put("name", c.name.clip(WearProtocol.MAX_NAME_CHARS))
                        put("preview", c.preview.clip(WearProtocol.MAX_PREVIEW_CHARS))
                        put("unread", c.unread.coerceAtLeast(0))
                        put("ts", c.timestamp)
                        put("akey", c.avatarKey)
                        if (c.lastFromMe) put("mine", true)
                        if (!c.canReply) put("ro", true)
                    })
                }
            })
            if (msgs.isNotEmpty()) put("msgs", JSONArray().apply {
                msgs.forEach { m ->
                    put(JSONObject().apply {
                        put("mid", m.id)
                        put("mine", m.fromMe)
                        m.sender?.let { put("sender", it.clip(WearProtocol.MAX_NAME_CHARS)) }
                        m.senderKey?.let { put("skey", it) }
                        put("text", m.text.clip(WearProtocol.MAX_BUBBLE_CHARS))
                        m.media?.let { put("media", it.wire) }
                        put("ts", m.timestamp)
                        if (m.reactions.isNotEmpty()) put("reacts", JSONArray(m.reactions.take(3)))
                        m.myReaction?.let { put("myr", it) }
                    })
                }
            })
        }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun status(rid: String, status: WearStatus) = WearResponse(rid, status)

        fun decode(bytes: ByteArray?): WearResponse? {
            if (bytes == null || bytes.isEmpty() || bytes.size > WearProtocol.MAX_PAYLOAD_BYTES) return null
            return runCatching {
                val o = JSONObject(String(bytes, Charsets.UTF_8))
                if (o.optInt("v", -1) != WearProtocol.VERSION) return null
                val convs = o.optJSONArray("convs")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val c = a.getJSONObject(i)
                        WearConversation(
                            kind = WearConvKind.of(c.optString("kind")) ?: return@mapNotNull null,
                            id = c.getString("cid"),
                            name = c.optString("name"),
                            preview = c.optString("preview"),
                            unread = c.optInt("unread", 0).coerceAtLeast(0),
                            timestamp = c.optLong("ts", 0L),
                            avatarKey = c.optString("akey"),
                            lastFromMe = c.optBoolean("mine", false),
                            canReply = !c.optBoolean("ro", false),
                        )
                    }
                }.orEmpty()
                val msgs = o.optJSONArray("msgs")?.let { a ->
                    (0 until a.length()).map { i ->
                        val m = a.getJSONObject(i)
                        WearMessage(
                            id = m.getString("mid"),
                            fromMe = m.optBoolean("mine", false),
                            sender = m.optStringOrNull("sender"),
                            senderKey = m.optStringOrNull("skey"),
                            text = m.optString("text"),
                            media = WearMedia.of(m.optStringOrNull("media")),
                            timestamp = m.optLong("ts", 0L),
                            reactions = m.optJSONArray("reacts")?.let { r ->
                                (0 until r.length()).map { r.getString(it) }
                            }.orEmpty(),
                            myReaction = m.optStringOrNull("myr"),
                        )
                    }
                }.orEmpty()
                WearResponse(
                    rid = o.getString("rid"),
                    status = WearStatus.of(o.optString("status")),
                    conversations = convs,
                    messages = msgs,
                    title = o.optStringOrNull("title"),
                )
            }.getOrNull()
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

/** Clip to [max] UTF-16 units without splitting a surrogate pair; adds "…" when cut. */
internal fun String.clip(max: Int): String {
    if (length <= max) return this
    var end = max - 1
    if (end > 0 && Character.isHighSurrogate(this[end - 1])) end--
    return substring(0, end) + "…"
}
