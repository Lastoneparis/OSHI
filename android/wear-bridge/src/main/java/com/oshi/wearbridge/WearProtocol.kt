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

    /**
     * __WEAR_POLISH_2026_11_25__ Watch -> phone ChannelClient path for a voice
     * message: a [WearVoiceFrame] (header request + the m4a bytes). A channel,
     * not a MessageClient message, because a 60 s note is bigger than the
     * ~100 KB a message may carry. The answer still comes back on [PATH_RESPONSE].
     */
    const val PATH_VOICE = "/oshi/wear/voice"

    // ---- feature negotiation -------------------------------------------
    //
    // The watch and the phone update separately (same Play listing, two
    // binaries, two review queues). A phone that does not know an op never
    // answers it, so the watch would show "phone not reachable" for a button
    // that simply is not supported yet. Instead the phone lists what it can do
    // in every LIST answer ([WearResponse.caps]) and the watch only offers
    // those. A phone that predates this field sends none: the watch then
    // behaves exactly like 1.7.3-wear.

    const val CAP_READ = "read"
    const val CAP_THUMB = "thumb"
    const val CAP_VOICE = "voice"
    const val CAP_NET = "net"

    /** What THIS build of the phone answers. */
    val PHONE_CAPS: List<String> = listOf(CAP_READ, CAP_THUMB, CAP_VOICE, CAP_NET)

    // ---- limits (enforced by both sides) -------------------------------

    /** MessageClient payloads above ~100 KB are refused; stay well under. */
    const val MAX_PAYLOAD_BYTES = 90_000
    const val MAX_CONVERSATIONS = 30
    const val MAX_THREAD_MESSAGES = 20
    const val MAX_SEND_CHARS = 2_000
    const val MAX_PREVIEW_CHARS = 120
    const val MAX_BUBBLE_CHARS = 600
    const val MAX_NAME_CHARS = 64

    /** A photo preview: longest side in px, and its JPEG size cap (memory only on the watch). */
    const val THUMB_PX = 200
    const val MAX_THUMB_BYTES = 40_000

    /** Voice message recorded on the watch: hard caps both sides enforce. */
    const val MAX_VOICE_MS = 60_000L
    const val MIN_VOICE_MS = 500L
    const val MAX_VOICE_BYTES = 1_000_000

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
    LIST("list"), THREAD("thread"), SEND("send"), REACT("react"),
    /** __WEAR_POLISH_2026_11_25__ The person opened this thread on the watch: mark it read (as the phone does). */
    READ("read"),
    /** A small JPEG preview of one photo message, fetched on demand, held in memory only. */
    THUMB("thumb"),
    /** A voice message recorded on the watch; arrives as a [WearVoiceFrame] over [WearProtocol.PATH_VOICE]. */
    VOICE("voice");

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

/** __WEAR_POLISH_2026_11_25__ Where one of MY messages stands, for the tick under the bubble. */
enum class WearDelivery(val wire: String) {
    SENDING("sending"), SENT("sent"), DELIVERED("delivered"), READ("read"), FAILED("failed");

    companion object {
        fun of(wire: String?): WearDelivery? = values().firstOrNull { it.wire == wire }
    }
}

/**
 * __WEAR_POLISH_2026_11_25__ How the PHONE is reaching people right now —
 * OSHI's own angle: internet, nearby mesh (Bluetooth / Wi-Fi), LoRa radio.
 * Counts only; nothing that names a peer.
 */
data class WearNet(
    val internet: Boolean,
    /** Nearby OSHI phones over Bluetooth / Wi-Fi mesh. */
    val meshPeers: Int = 0,
    /** A LoRa radio is connected to the phone. */
    val lora: Boolean = false,
) {
    /** No way out at all: messages wait on the phone. */
    val isOffline: Boolean get() = !internet && meshPeers <= 0 && !lora
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
    /** VOICE: the recording's length. */
    val durationMs: Long = 0L,
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
            WearOp.READ -> kind != null && cid != null
            WearOp.THUMB -> kind != null && cid != null && !messageId.isNullOrBlank() && messageId.length <= 128
            WearOp.VOICE -> kind != null && cid != null &&
                durationMs in WearProtocol.MIN_VOICE_MS..WearProtocol.MAX_VOICE_MS
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
        if (durationMs > 0) put("dur", durationMs)
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
                    durationMs = o.optLong("dur", 0L),
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
    /** __WEAR_POLISH_2026_11_25__ Pinned on the phone: the phone already sorted it first. */
    val pinned: Boolean = false,
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
    /** __WEAR_POLISH_2026_11_25__ Mine only: sent / delivered / read / failed. */
    val delivery: WearDelivery? = null,
    /** A preview can be asked for with [WearOp.THUMB] (a photo that is not view-once). */
    val hasThumb: Boolean = false,
    /** Voice / video length, seconds (0 = unknown). */
    val durationSec: Int = 0,
)

data class WearResponse(
    val rid: String,
    val status: WearStatus,
    val conversations: List<WearConversation> = emptyList(),
    val messages: List<WearMessage> = emptyList(),
    /** Thread title (THREAD) so the watch can draw it after a deep link. */
    val title: String? = null,
    /** __WEAR_POLISH_2026_11_25__ LIST: what this phone supports ([WearProtocol.PHONE_CAPS]). */
    val caps: List<String> = emptyList(),
    /** LIST: how the phone is connected. */
    val net: WearNet? = null,
    /** THUMB: the JPEG, at most [WearProtocol.MAX_THUMB_BYTES]. */
    val thumb: ByteArray? = null,
    /**
     * LIST: the phone's strict security mode is on (its banners say only
     * "OSHI · New message"). The always-visible surfaces — tile and watch-face
     * complication — then show counts only, no names, to match.
     */
    val strict: Boolean = false,
) {
    // ByteArray breaks data-class equality; compare its content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WearResponse) return false
        return rid == other.rid && status == other.status && conversations == other.conversations &&
            messages == other.messages && title == other.title && caps == other.caps && net == other.net &&
            strict == other.strict &&
            (thumb?.contentEquals(other.thumb ?: return false) ?: (other.thumb == null))
    }

    override fun hashCode(): Int =
        listOf(rid, status, conversations, messages, title, caps, net, strict, thumb?.contentHashCode()).hashCode()

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
            if (caps.isNotEmpty()) put("caps", JSONArray(caps))
            if (strict) put("strict", true)
            net?.let { n ->
                put("net", JSONObject().apply {
                    put("i", n.internet); put("m", n.meshPeers.coerceAtLeast(0)); put("l", n.lora)
                })
            }
            thumb?.takeIf { it.size <= WearProtocol.MAX_THUMB_BYTES }?.let {
                put("thumb", java.util.Base64.getEncoder().encodeToString(it))
            }
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
                        if (c.pinned) put("pin", true)
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
                        m.delivery?.let { put("st", it.wire) }
                        if (m.hasThumb) put("thumb", true)
                        if (m.durationSec > 0) put("dur", m.durationSec)
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
                            pinned = c.optBoolean("pin", false),
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
                            delivery = WearDelivery.of(m.optStringOrNull("st")),
                            hasThumb = m.optBoolean("thumb", false),
                            durationSec = m.optInt("dur", 0).coerceAtLeast(0),
                        )
                    }
                }.orEmpty()
                WearResponse(
                    rid = o.getString("rid"),
                    status = WearStatus.of(o.optString("status")),
                    conversations = convs,
                    messages = msgs,
                    title = o.optStringOrNull("title"),
                    strict = o.optBoolean("strict", false),
                    caps = o.optJSONArray("caps")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    net = o.optJSONObject("net")?.let { n ->
                        WearNet(n.optBoolean("i", false), n.optInt("m", 0).coerceAtLeast(0), n.optBoolean("l", false))
                    },
                    thumb = o.optStringOrNull("thumb")?.let { b64 ->
                        runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull()
                            ?.takeIf { it.size <= WearProtocol.MAX_THUMB_BYTES }
                    },
                )
            }.getOrNull()
        }
    }
}

/**
 * __WEAR_POLISH_2026_11_25__ A voice message on the wire (ChannelClient
 * stream on [WearProtocol.PATH_VOICE]):
 *
 *     [4-byte big-endian header length][header: a VOICE WearRequest, JSON][m4a bytes]
 *
 * Both sides cap everything before allocating: a header over 4 KB or audio
 * over [WearProtocol.MAX_VOICE_BYTES] is refused, and the audio must look
 * like an MP4/M4A container (`ftyp` box) — the only format the phone's voice
 * path and iOS play.
 */
object WearVoiceFrame {
    const val MAX_HEADER_BYTES = 4_096

    fun encode(header: WearRequest, audio: ByteArray): ByteArray {
        val h = header.encode()
        val out = java.io.ByteArrayOutputStream(4 + h.size + audio.size)
        out.write(byteArrayOf((h.size ushr 24).toByte(), (h.size ushr 16).toByte(), (h.size ushr 8).toByte(), h.size.toByte()))
        out.write(h)
        out.write(audio)
        return out.toByteArray()
    }

    /** Reads at most what the caps allow; null for anything malformed or oversized. */
    fun read(input: java.io.InputStream): Pair<WearRequest, ByteArray>? = runCatching {
        val len = ByteArray(4)
        if (input.readNBytesCompat(len) != 4) return null
        val n = ((len[0].toInt() and 0xFF) shl 24) or ((len[1].toInt() and 0xFF) shl 16) or
            ((len[2].toInt() and 0xFF) shl 8) or (len[3].toInt() and 0xFF)
        if (n <= 0 || n > MAX_HEADER_BYTES) return null
        val h = ByteArray(n)
        if (input.readNBytesCompat(h) != n) return null
        val req = WearRequest.decode(h)?.takeIf { it.op == WearOp.VOICE } ?: return null
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val r = input.read(chunk)
            if (r < 0) break
            if (buf.size() + r > WearProtocol.MAX_VOICE_BYTES) return null
            buf.write(chunk, 0, r)
        }
        val audio = buf.toByteArray()
        if (!looksLikeM4a(audio)) return null
        req to audio
    }.getOrNull()

    fun decode(bytes: ByteArray): Pair<WearRequest, ByteArray>? = read(bytes.inputStream())

    /** An ISO-BMFF file starts with a box whose type, at offset 4, is `ftyp`. */
    fun looksLikeM4a(b: ByteArray): Boolean =
        b.size > 12 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() &&
            b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()

    private fun java.io.InputStream.readNBytesCompat(dst: ByteArray): Int {
        var off = 0
        while (off < dst.size) {
            val r = read(dst, off, dst.size - off)
            if (r < 0) break
            off += r
        }
        return off
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
