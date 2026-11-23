package com.oshi.messenger.network.v2.devsync

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * The sync payload = the `docs/OSHI_EXPORT_FORMAT.md` version-2 objects (design §7), plus the
 * additive media extension `media.size` / `media.sha256` (bytes never travel inline: they go
 * through MEDIA_GET / MEDIA_CHUNK).
 *
 * This is a platform-neutral model on purpose: the Android Room mapping lives in
 * `service/MessageExportV2.kt` (`AndroidExportV2`) and the Desktop one in
 * `store/MessageExportV2.kt`; the platform adapters translate those to and from these types.
 */
object SyncJson {

    private val ISO_MS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun iso(ms: Long): String = ISO_MS.format(Instant.ofEpochMilli(ms))

    /** ISO-8601 with or without fractional seconds, `Z` or an offset. */
    fun parseIso(s: String): Long =
        runCatching { Instant.parse(s).toEpochMilli() }.getOrElse { OffsetDateTime.parse(s).toInstant().toEpochMilli() }

    fun utcDay(ms: Long): String = iso(ms).substring(0, 10)

    /** Standard padded base64 of a 32-byte key (export §3.1); anything else is returned unchanged. */
    fun canonicalKey(key: String): String {
        val t = key.trim()
        val bytes = runCatching { Base64.getDecoder().decode(t) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(t.trimEnd('=')) }.getOrNull()
            ?: return key
        return if (bytes.size == 32) Base64.getEncoder().encodeToString(bytes) else key
    }

    fun canonicalGroupId(s: String): String =
        runCatching { UUID.fromString(s).toString().uppercase(Locale.ROOT) }.getOrDefault(s.uppercase(Locale.ROOT))

    fun directConversationId(peer: String) = "direct:" + canonicalKey(peer)
    fun groupConversationId(groupId: String) = "group:" + canonicalGroupId(groupId)
    fun desktopConversationId(nativeId: String) = "desktop:$nativeId"

    /** ASCII-only lowercasing (ids are compared case-insensitively, never locale-dependently). */
    fun asciiLower(s: String): String {
        var i = 0
        while (i < s.length && s[i] !in 'A'..'Z') i++
        if (i == s.length) return s
        val c = s.toCharArray()
        for (j in i until c.size) if (c[j] in 'A'..'Z') c[j] = c[j] + 32
        return String(c)
    }

    internal fun JSONObject.optStr(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

    internal fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    internal fun JSONObject.optIsoOrNull(key: String): Long? =
        optStr(key)?.let { runCatching { parseIso(it) }.getOrNull() }
}

/**
 * Canonical JSON (for `rev` only): object keys sorted by UTF-8 bytes, no whitespace, strings escape
 * only `"`, `\` and U+0000..U+001F (as `\u00xx`, lowercase hex); everything else is raw UTF-8.
 * Values: Map<String, *>, List<*>, String, Boolean, Int/Long, null.
 */
object CanonicalJson {

    /** Code-point order == UTF-8 byte order (unlike String.compareTo, which is UTF-16 order). */
    val UTF8_ORDER: Comparator<String> = Comparator { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return@Comparator ca.compareTo(cb)
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        (a.length - i).compareTo(b.length - j)
    }

    fun write(v: Any?): String = StringBuilder().also { append(it, v) }.toString()

    private fun append(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long -> sb.append(v.toString())
            is String -> str(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                val keys = v.keys.map { it as String }.sortedWith(UTF8_ORDER)
                keys.forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    str(sb, k)
                    sb.append(':')
                    append(sb, v[k])
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); append(sb, x) }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("not canonical-JSON: ${v.javaClass}")
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch.code < 0x20 -> sb.append("\\u00").append(HEX[ch.code ushr 4]).append(HEX[ch.code and 0xF])
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

data class SyncMedia(
    val type: String,
    val fileName: String? = null,
    val mime: String? = null,
    /** Plaintext size in bytes (sync extension). */
    val size: Long? = null,
    /** Lowercase hex SHA-256 of the plaintext bytes (sync extension). */
    val sha256: String? = null,
    /** `viewOnce` / `unavailable` (export meaning). */
    val omitted: String? = null,
)

data class SyncEdited(val text: String, val atMs: Long? = null)

data class SyncMessage(
    val id: String,
    val conversation: String,
    val outgoing: Boolean,
    val senderPublicKey: String,
    val senderName: String? = null,
    val timestampMs: Long,
    val text: String? = null,
    val status: String,
    val read: Boolean,
    val edited: SyncEdited? = null,
    val deleted: Boolean = false,
    val replyToId: String? = null,
    val viewOnce: Boolean = false,
    val media: SyncMedia? = null,
    val reactions: Map<String, List<String>> = emptyMap(),
) {
    val idKey: String get() = SyncJson.asciiLower(id)
}

data class SyncConversation(
    val id: String,
    val kind: String,
    val peerPublicKey: String? = null,
    val groupId: String? = null,
    val groupName: String? = null,
    val nativeId: String? = null,
) {
    companion object {
        const val KIND_DIRECT = "direct"
        const val KIND_GROUP = "group"
        const val KIND_DESKTOP_LOCAL = "desktop.local"

        fun direct(peer: String) = SyncJson.canonicalKey(peer).let {
            SyncConversation(SyncJson.directConversationId(it), KIND_DIRECT, peerPublicKey = it)
        }

        fun group(groupId: String, name: String? = null) = SyncJson.canonicalGroupId(groupId).let {
            SyncConversation(SyncJson.groupConversationId(it), KIND_GROUP, groupId = it, groupName = name)
        }

        fun desktopLocal(nativeId: String) =
            SyncConversation(SyncJson.desktopConversationId(nativeId), KIND_DESKTOP_LOCAL, nativeId = nativeId)
    }
}

/** Message codec (export v2 §3 + media extension) and the `rev` / digest functions (§6.2). */
object SyncCodec {

    private val STATUSES = setOf("pending", "sent", "delivered", "read", "failed")

    /** Unknown statuses fall back as in the export (§3.3): `delivered` for in, `sent` for out. */
    fun normaliseStatus(status: String?, outgoing: Boolean): String =
        status?.takeIf { it in STATUSES } ?: if (outgoing) "sent" else "delivered"

    fun conversationToJson(c: SyncConversation): JSONObject = JSONObject().put("id", c.id).put("kind", c.kind).apply {
        c.peerPublicKey?.let { put("peerPublicKey", it) }
        c.groupId?.let { put("groupId", it) }
        c.groupName?.let { put("groupName", it) }
        c.nativeId?.let { put("nativeId", it) }
    }

    fun conversationFromJson(o: JSONObject): SyncConversation? {
        with(SyncJson) {
            val kind = o.optStr("kind") ?: return null
            return when (kind) {
                SyncConversation.KIND_DIRECT -> o.optStr("peerPublicKey")?.let { SyncConversation.direct(it) }
                SyncConversation.KIND_GROUP -> o.optStr("groupId")?.let { SyncConversation.group(it, o.optStr("groupName")) }
                SyncConversation.KIND_DESKTOP_LOCAL -> o.optStr("nativeId")?.let { SyncConversation.desktopLocal(it) }
                else -> null // unknown kind: skipped
            }
        }
    }

    fun messageToJson(m: SyncMessage): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("conversation", m.conversation)
        put("direction", if (m.outgoing) "out" else "in")
        put("senderPublicKey", m.senderPublicKey)
        m.senderName?.let { put("senderName", it) }
        put("timestamp", SyncJson.iso(m.timestampMs))
        if (!m.deleted) m.text?.let { put("text", it) }
        put("status", m.status)
        put("read", m.read)
        if (!m.deleted) m.edited?.let { e ->
            put("edited", JSONObject().put("text", e.text).apply { e.atMs?.let { put("at", SyncJson.iso(it)) } })
        }
        if (m.deleted) put("deleted", true)
        m.replyToId?.let { put("replyToId", it) }
        if (m.viewOnce) put("viewOnce", true)
        m.media?.let { md ->
            put("media", JSONObject().put("type", md.type).apply {
                md.fileName?.let { put("fileName", it) }
                md.mime?.let { put("mime", it) }
                md.size?.let { put("size", it) }
                md.sha256?.let { put("sha256", it) }
                md.omitted?.let { put("omitted", it) }
            })
        }
        if (!m.deleted && m.reactions.isNotEmpty()) {
            put("reactions", JSONObject().apply {
                for ((emoji, keys) in m.reactions) if (keys.isNotEmpty()) put(emoji, JSONArray(keys.sortedWith(CanonicalJson.UTF8_ORDER)))
            })
        }
    }

    /**
     * Parse one v2 message against its (already parsed) conversation. Returns null for anything
     * malformed — the caller counts it as invalid, never fatal (export §3.1).
     */
    fun messageFromJson(o: JSONObject, conv: SyncConversation): SyncMessage? = runCatching {
        with(SyncJson) {
            val direction = o.getString("direction")
            require(direction == "in" || direction == "out")
            val outgoing = direction == "out"
            val edited = o.optJSONObject("edited")?.let { e ->
                val t = e.optStr("text") ?: ""
                SyncEdited(t, e.optIsoOrNull("at"))
            }
            val media = o.optJSONObject("media")?.let { md ->
                SyncMedia(
                    type = md.getString("type"),
                    fileName = md.optStr("fileName"),
                    mime = md.optStr("mime"),
                    size = md.optLongOrNull("size"),
                    sha256 = md.optStr("sha256")?.lowercase(Locale.ROOT)?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } },
                    omitted = md.optStr("omitted"),
                )
            }
            val reactions = o.optJSONObject("reactions")?.let { r ->
                val out = LinkedHashMap<String, List<String>>()
                val it = r.keys()
                while (it.hasNext()) {
                    val emoji = it.next()
                    val arr = r.optJSONArray(emoji) ?: continue
                    out[emoji] = (0 until arr.length()).map { canonicalKey(arr.getString(it)) }.distinct()
                }
                out
            } ?: emptyMap()
            val deleted = o.optBoolean("deleted", false)
            SyncMessage(
                id = o.getString("id").also { require(it.isNotBlank()) },
                conversation = conv.id,
                outgoing = outgoing,
                senderPublicKey = canonicalKey(o.getString("senderPublicKey")),
                senderName = o.optStr("senderName"),
                timestampMs = parseIso(o.getString("timestamp")),
                text = if (deleted) null else o.optStr("text"),
                status = normaliseStatus(o.optStr("status"), outgoing),
                read = o.optBoolean("read", false),
                edited = if (deleted) null else edited,
                deleted = deleted,
                replyToId = o.optStr("replyToId"),
                viewOnce = o.optBoolean("viewOnce", false),
                media = media,
                reactions = if (deleted) emptyMap() else reactions,
            )
        }
    }.getOrNull()

    /** A `{conversations:[…], messages:[…]}` body (MESSAGES / LIVE). */
    fun encodeBatch(convs: Collection<SyncConversation>, msgs: Collection<SyncMessage>): JSONObject =
        JSONObject()
            .put("conversations", JSONArray().apply { convs.forEach { put(conversationToJson(it)) } })
            .put("messages", JSONArray().apply { msgs.forEach { put(messageToJson(it)) } })

    data class Batch(
        val conversations: Map<String, SyncConversation>,
        val messages: List<SyncMessage>,
        val invalid: Int,
        val unknownKind: Int,
    )

    fun decodeBatch(o: JSONObject): Batch {
        val convs = LinkedHashMap<String, SyncConversation>()
        val declared = HashMap<String, String>() // the file-local id -> canonical id
        var unknownKinds = HashSet<String>()
        o.optJSONArray("conversations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val localId = c.optString("id", "")
                val parsed = conversationFromJson(c)
                if (parsed == null) { if (localId.isNotEmpty()) unknownKinds.add(localId); continue }
                convs[parsed.id] = parsed
                if (localId.isNotEmpty()) declared[localId] = parsed.id
            }
        }
        val msgs = ArrayList<SyncMessage>()
        var invalid = 0
        var unknown = 0
        val arr = o.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i)
            if (m == null) { invalid++; continue }
            val cid = m.optString("conversation", "")
            if (cid in unknownKinds) { unknown++; continue }
            val conv = declared[cid]?.let { convs[it] }
            if (conv == null) { invalid++; continue }
            val parsed = messageFromJson(m, conv)
            if (parsed == null) invalid++ else msgs += parsed
        }
        return Batch(convs, msgs, invalid, unknown)
    }

    // ------------------------------------------------------------------ rev and digests

    /** The mutable fields, normalised (see docs/fixtures/devsync/README.md, "rev"). */
    fun revObject(m: SyncMessage): Map<String, Any?> {
        val deleted = m.deleted
        val edited: Map<String, Any?>? = if (deleted) null else m.edited?.let { e ->
            val o = LinkedHashMap<String, Any?>()
            o["text"] = e.text
            e.atMs?.let { o["at"] = SyncJson.iso(it) }
            o
        }
        val reactions = LinkedHashMap<String, Any?>()
        if (!deleted) {
            for ((emoji, keys) in m.reactions) {
                val ks = keys.distinct().sortedWith(CanonicalJson.UTF8_ORDER)
                if (ks.isNotEmpty()) reactions[emoji] = ks
            }
        }
        return linkedMapOf(
            "deleted" to deleted,
            "edited" to edited,
            "reactions" to reactions,
            "read" to m.read,
            "status" to if (m.outgoing) m.status else null,
        )
    }

    fun canonicalRev(m: SyncMessage): String = CanonicalJson.write(revObject(m))

    fun rev(m: SyncMessage): String =
        DevSyncCrypto.hex(DevSyncCrypto.sha256(canonicalRev(m).toByteArray(Charsets.UTF_8))).substring(0, 16)

    /** digest = hex(SHA-256(join("\n", sort_utf8(asciiLower(id) ":" rev)))). */
    fun digest(pairs: Iterable<Pair<String, String>>): String {
        val lines = pairs.map { SyncJson.asciiLower(it.first) + ":" + it.second }.sortedWith(CanonicalJson.UTF8_ORDER)
        return DevSyncCrypto.hex(DevSyncCrypto.sha256(lines.joinToString("\n").toByteArray(Charsets.UTF_8)))
    }
}
