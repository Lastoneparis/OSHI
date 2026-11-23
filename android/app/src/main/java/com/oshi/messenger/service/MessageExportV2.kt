package com.oshi.messenger.service

import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.Message
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * __EXPORT_V2_INTEROP_2026_09_22__ The platform-neutral payload (version 2) inside the
 * `.oshiexport` container. Spec: `docs/OSHI_EXPORT_FORMAT.md`; shared fixture:
 * `docs/fixtures/export_v2_sample.json`, parsed by `MessageExportV2Test` here, by OSHI-Desktop's
 * `MessageExportV2Test` and by the iOS storage self-test.
 *
 * Version 1 carried each platform's native objects, so an iPhone export could not be imported
 * on Android (nor the reverse). Version 2 is one documented shape every writer emits and every
 * reader maps to its own model.
 *
 * PURE JVM on purpose (java.util.Base64, org.json, java.time — no android.*): the codec and the
 * Room-entity mapping are unit-tested on the JVM. The I/O (DAOs, media store, key) stays in
 * [MessageExportManager].
 *
 * The codec is a line-for-line port of OSHI-Desktop `store/MessageExportV2.kt` (not shared
 * source: the desktop's shared-file list lives in a build script other work is editing). The
 * shared fixture is what keeps the two from drifting.
 */
object ExportV2 {

    const val VERSION = 2

    const val KIND_DIRECT = "direct"
    const val KIND_GROUP = "group"
    /** Desktop-only threads (bots, radio): a phone skips them (counted as unknown kind). */
    const val KIND_DESKTOP_LOCAL = "desktop.local"

    /** Total raw media bytes one export may carry (same budget on all three platforms). */
    const val MEDIA_BUDGET_BYTES: Long = 48L * 1024 * 1024

    data class Contact(val publicKey: String, val alias: String?, val nickname: String? = null)

    data class Conversation(
        val id: String,
        val kind: String,
        val peerPublicKey: String? = null,
        val groupId: String? = null,
        val groupName: String? = null,
        /** Only for [KIND_DESKTOP_LOCAL]: the desktop's own conversation id. */
        val nativeId: String? = null,
    )

    data class Media(
        val type: String,
        val fileName: String? = null,
        val mime: String? = null,
        val data: ByteArray? = null,
        /** Why [data] is absent: `viewOnce`, `sizeBudget` or `unavailable`. */
        val omitted: String? = null,
    )

    data class Msg(
        val id: String,
        val conversation: String,
        val outgoing: Boolean,
        val senderPublicKey: String,
        val senderName: String? = null,
        val timestampMs: Long,
        val text: String?,
        val status: String,
        val read: Boolean,
        val editedText: String? = null,
        val editedAtMs: Long? = null,
        val deleted: Boolean = false,
        val replyToId: String? = null,
        val viewOnce: Boolean = false,
        val media: Media? = null,
        val reactions: Map<String, List<String>> = emptyMap(),
    )

    data class Payload(
        val platform: String,
        val exportedAtMs: Long,
        val accountPublicKey: String?,
        val contacts: List<Contact>,
        val conversations: List<Conversation>,
        val messages: List<Msg>,
    )

    /** A parsed payload plus what was left out, so an import can say so. */
    data class Parsed(
        val payload: Payload,
        /** Messages that were malformed or referenced an undeclared conversation. */
        val invalid: Int,
        /** Messages in a conversation kind this reader does not know. */
        val skippedUnknownKind: Int,
    )

    // ------------------------------------------------------------------ helpers

    private val ISO_MS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun isoMs(ms: Long): String = ISO_MS.format(Instant.ofEpochMilli(ms))

    /** ISO-8601 with or without fractional seconds, `Z` or an offset. */
    fun parseIso(s: String): Long =
        runCatching { Instant.parse(s).toEpochMilli() }.getOrElse { OffsetDateTime.parse(s).toInstant().toEpochMilli() }

    /**
     * Standard, padded base64 of a 32-byte key — the one spelling every platform compares on.
     * A base64url or unpadded spelling of the same bytes maps to it; anything that is not a
     * 32-byte base64 value is returned unchanged.
     */
    fun canonicalKey(key: String): String {
        val t = key.trim()
        val bytes = runCatching { Base64.getDecoder().decode(t) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(t.trimEnd('=')) }.getOrNull()
            ?: return key
        return if (bytes.size == 32) Base64.getEncoder().encodeToString(bytes) else key
    }

    fun isGroupId(s: String): Boolean = runCatching { UUID.fromString(s); true }.getOrDefault(false)

    fun canonicalGroupId(s: String): String =
        runCatching { UUID.fromString(s).toString().uppercase(java.util.Locale.US) }.getOrDefault(s)

    fun directConversationId(peer: String) = "direct:" + canonicalKey(peer)
    fun groupConversationId(groupId: String) = "group:" + canonicalGroupId(groupId)

    /** MIME for a canonical media type, refined by the file extension when there is one. */
    fun guessMime(type: String, fileName: String?): String {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "vcf" -> "text/vcard"
            else -> when (type) {
                "image" -> "image/jpeg"
                "gif" -> "image/gif"
                "video" -> "video/mp4"
                "audio" -> "audio/mp4"
                "contact" -> "application/json"
                "location" -> "application/json"
                else -> "application/octet-stream"
            }
        }
    }

    // ------------------------------------------------------------------ encode

    fun encode(p: Payload): ByteArray {
        val root = JSONObject()
            .put("version", VERSION)
            .put("platform", p.platform)
            .put("exportedAt", isoMs(p.exportedAtMs))
        p.accountPublicKey?.let { root.put("account", JSONObject().put("publicKey", it)) }
        root.put("contacts", JSONArray().apply {
            p.contacts.forEach { c ->
                put(JSONObject().put("publicKey", c.publicKey).apply {
                    c.alias?.let { put("alias", it) }
                    c.nickname?.let { put("nickname", it) }
                })
            }
        })
        root.put("conversations", JSONArray().apply {
            p.conversations.forEach { c ->
                put(JSONObject().put("id", c.id).put("kind", c.kind).apply {
                    c.peerPublicKey?.let { put("peerPublicKey", it) }
                    c.groupId?.let { put("groupId", it) }
                    c.groupName?.let { put("groupName", it) }
                    c.nativeId?.let { put("nativeId", it) }
                })
            }
        })
        val b64 = Base64.getEncoder()
        root.put("messages", JSONArray().apply {
            p.messages.forEach { m ->
                put(JSONObject().apply {
                    put("id", m.id)
                    put("conversation", m.conversation)
                    put("direction", if (m.outgoing) "out" else "in")
                    put("senderPublicKey", m.senderPublicKey)
                    m.senderName?.let { put("senderName", it) }
                    put("timestamp", isoMs(m.timestampMs))
                    m.text?.let { put("text", it) }
                    put("status", m.status)
                    put("read", m.read)
                    if (m.editedText != null) {
                        put("edited", JSONObject().put("text", m.editedText).apply {
                            m.editedAtMs?.let { put("at", isoMs(it)) }
                        })
                    }
                    if (m.deleted) put("deleted", true)
                    m.replyToId?.let { put("replyToId", it) }
                    if (m.viewOnce) put("viewOnce", true)
                    m.media?.let { md ->
                        put("media", JSONObject().put("type", md.type).apply {
                            md.fileName?.let { put("fileName", it) }
                            md.mime?.let { put("mime", it) }
                            md.data?.let { put("dataBase64", b64.encodeToString(it)) }
                            md.omitted?.let { put("omitted", it) }
                        })
                    }
                    if (m.reactions.isNotEmpty()) {
                        put("reactions", JSONObject().apply {
                            m.reactions.toSortedMap().forEach { (emoji, keys) -> put(emoji, JSONArray(keys.sorted())) }
                        })
                    }
                })
            }
        })
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ parse

    /**
     * Parse a version-2 payload. Throws only when the document as a whole is not a v2 payload;
     * a single bad message is counted in [Parsed.invalid] and skipped.
     */
    fun parse(root: JSONObject): Parsed {
        require(root.optInt("version", -1) == VERSION) { "not a version 2 payload" }
        val contacts = ArrayList<Contact>()
        root.optJSONArray("contacts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pk = o.optStr("publicKey") ?: continue
                contacts += Contact(canonicalKey(pk), o.optStr("alias"), o.optStr("nickname"))
            }
        }
        val conversations = LinkedHashMap<String, Conversation>()
        root.optJSONArray("conversations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optStr("id") ?: continue
                val kind = o.optStr("kind") ?: continue
                conversations[id] = Conversation(
                    id = id,
                    kind = kind,
                    peerPublicKey = o.optStr("peerPublicKey")?.let(::canonicalKey),
                    groupId = o.optStr("groupId"),
                    groupName = o.optStr("groupName"),
                    nativeId = o.optStr("nativeId"),
                )
            }
        }
        val known = setOf(KIND_DIRECT, KIND_GROUP)
        val messages = ArrayList<Msg>()
        var invalid = 0
        var skipped = 0
        val b64 = Base64.getDecoder()
        val arr = root.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) { invalid++; continue }
            val parsed = runCatching {
                val conv = conversations[o.getString("conversation")] ?: return@runCatching null
                if (conv.kind !in known) return@runCatching conv
                if (conv.kind == KIND_DIRECT && conv.peerPublicKey == null) return@runCatching null
                if (conv.kind == KIND_GROUP && conv.groupId == null) return@runCatching null
                val direction = o.getString("direction")
                require(direction == "in" || direction == "out")
                val edited = o.optJSONObject("edited")
                val media = o.optJSONObject("media")?.let { md ->
                    Media(
                        type = md.getString("type"),
                        fileName = md.optStr("fileName"),
                        mime = md.optStr("mime"),
                        data = md.optStr("dataBase64")?.let { b64.decode(it) },
                        omitted = md.optStr("omitted"),
                    )
                }
                val reactions = o.optJSONObject("reactions")?.let { r ->
                    r.keys().asSequence().associateWith { e ->
                        val a = r.getJSONArray(e)
                        (0 until a.length()).map { canonicalKey(a.getString(it)) }
                    }
                } ?: emptyMap()
                Msg(
                    id = o.getString("id").also { require(it.isNotBlank()) },
                    conversation = conv.id,
                    outgoing = direction == "out",
                    senderPublicKey = canonicalKey(o.getString("senderPublicKey")),
                    senderName = o.optStr("senderName"),
                    timestampMs = parseIso(o.getString("timestamp")),
                    text = o.optStr("text"),
                    status = o.optStr("status") ?: if (direction == "out") "sent" else "delivered",
                    read = o.optBoolean("read", false),
                    editedText = edited?.optStr("text"),
                    editedAtMs = edited?.optStr("at")?.let(::parseIso),
                    deleted = o.optBoolean("deleted", false),
                    replyToId = o.optStr("replyToId"),
                    viewOnce = o.optBoolean("viewOnce", false),
                    media = media,
                    reactions = reactions,
                )
            }.getOrNull()
            when (parsed) {
                is Msg -> messages += parsed
                is Conversation -> skipped++
                else -> invalid++
            }
        }
        return Parsed(
            Payload(
                platform = root.optString("platform", ""),
                exportedAtMs = root.optStr("exportedAt")?.let { runCatching { parseIso(it) }.getOrNull() } ?: 0L,
                accountPublicKey = root.optJSONObject("account")?.optStr("publicKey")?.let(::canonicalKey),
                contacts = contacts,
                conversations = conversations.values.toList(),
                messages = messages,
            ),
            invalid,
            skipped,
        )
    }

    private fun JSONObject.optStr(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
}

/** Room entities ⇄ [ExportV2]. No I/O: media bytes are passed in and handed back. */
object AndroidExportV2 {

    /** Control payloads that are not conversation (never exported, never imported). */
    val CONTROL_PREFIXES = listOf(
        "\uD83D\uDCF8PROFILE_UPDATE\uD83D\uDCF8", "\uD83D\uDCF8PROFILE_REQUEST\uD83D\uDCF8",
        "\uD83D\uDCD6READ_RECEIPT\uD83D\uDCD6", "\uD83D\uDCECDELIVERY_RECEIPT\uD83D\uDCEC",
        "\uD83D\uDD17LINKS_VISIBILITY\uD83D\uDD17", "\uD83C\uDFA8WALLPAPER_UPDATE\uD83C\uDFA8",
    )

    fun isControl(content: String?): Boolean = content != null && CONTROL_PREFIXES.any { content.startsWith(it) }

    private val PLACEHOLDER = Regex("^\\[[A-Z_]+\\]$")

    fun typeFor(t: MediaType): String = when (t) {
        MediaType.IMAGE -> "image"
        MediaType.VIDEO -> "video"
        MediaType.AUDIO -> "audio"
        MediaType.DOCUMENT -> "document"
        MediaType.LOCATION -> "location"
        MediaType.CONTACT -> "contact"
    }

    /** A canonical v2 type → Android, never null: unknown labels fall back to the bytes, then DOCUMENT. */
    fun mediaTypeFor(type: String, bytes: ByteArray?): MediaType =
        MediaType.fromWireLabel(type) ?: MediaType.sniff(bytes) ?: MediaType.DOCUMENT

    /** Build the v2 media object. [bytes] null + [omitted] says why. */
    fun media(type: MediaType, fileName: String?, bytes: ByteArray?, omitted: String?): ExportV2.Media {
        val t = typeFor(type)
        return ExportV2.Media(t, fileName, ExportV2.guessMime(t, fileName), bytes, if (bytes == null) omitted ?: "unavailable" else null)
    }

    private fun status(fromMe: Boolean, isFailed: Boolean, isRead: Boolean, isDelivered: Boolean): String = when {
        !fromMe -> "delivered"
        isFailed -> "failed"
        isRead -> "read"
        isDelivered -> "delivered"
        else -> "sent"
    }

    /** Media body text: a placeholder like `[IMAGE]` or a document's name is not a caption. */
    private fun splitMediaContent(type: MediaType?, content: String): Pair<String?, String?> {
        if (type == null) return content to null
        if (type == MediaType.DOCUMENT) return null to content.ifBlank { null }
        if (content.isBlank() || PLACEHOLDER.matches(content.trim())) return null to null
        return content to null
    }

    fun direct(m: Message, media: ExportV2.Media?): Pair<ExportV2.Conversation, ExportV2.Msg> {
        val peer = ExportV2.canonicalKey(if (m.isFromMe) m.recipientPublicKey else m.senderPublicKey)
        val conv = ExportV2.Conversation(ExportV2.directConversationId(peer), ExportV2.KIND_DIRECT, peerPublicKey = peer)
        val (text, fileName) = splitMediaContent(m.mediaType, m.content)
        val md = media?.let { if (it.fileName == null && fileName != null) it.copy(fileName = fileName, mime = ExportV2.guessMime(it.type, fileName)) else it }
        return conv to ExportV2.Msg(
            id = m.id,
            conversation = conv.id,
            outgoing = m.isFromMe,
            senderPublicKey = ExportV2.canonicalKey(m.senderPublicKey),
            timestampMs = m.timestamp,
            text = if (m.isDeletedForEveryone) null else text,
            status = status(m.isFromMe, m.isFailed, m.isRead, m.isDelivered),
            read = m.isRead,
            editedText = m.editedContent,
            editedAtMs = m.editedAt,
            deleted = m.isDeletedForEveryone,
            replyToId = m.replyToId,
            viewOnce = m.isViewOnce,
            media = md,
        )
    }

    fun group(m: GroupMessage, groupName: String?, media: ExportV2.Media?): Pair<ExportV2.Conversation, ExportV2.Msg> {
        val gid = ExportV2.canonicalGroupId(m.groupId)
        val conv = ExportV2.Conversation(ExportV2.groupConversationId(gid), ExportV2.KIND_GROUP, groupId = gid, groupName = groupName)
        val (text, fileName) = splitMediaContent(m.mediaType, m.content)
        val name = m.mediaFileName ?: fileName
        val md = media?.let { if (it.fileName == null && name != null) it.copy(fileName = name, mime = ExportV2.guessMime(it.type, name)) else it }
        val reactions = HashMap<String, MutableList<String>>()
        m.reactions.forEach { (pk, emoji) -> reactions.getOrPut(emoji) { ArrayList() }.add(ExportV2.canonicalKey(pk)) }
        return conv to ExportV2.Msg(
            id = m.id,
            conversation = conv.id,
            outgoing = m.isFromMe,
            senderPublicKey = ExportV2.canonicalKey(m.senderPublicKey),
            senderName = m.senderName.ifBlank { null },
            timestampMs = m.timestamp,
            text = if (m.isDeleted) null else text,
            status = if (m.isFromMe) "sent" else "delivered",
            read = m.isRead,
            editedText = m.editedContent,
            editedAtMs = m.editedAt,
            deleted = m.isDeleted,
            replyToId = m.replyToId,
            media = md,
            reactions = reactions,
        )
    }

    /**
     * v2 → a 1:1 [Message]. Imported history is history: never unread (opening the chat must
     * not emit read receipts for it). [mediaBytes] is what the caller keeps inline (null when it
     * went to the media store instead).
     */
    fun toDirect(m: ExportV2.Msg, peer: String, selfKey: String, mediaBytes: ByteArray?): Message {
        val md = m.media
        val type = md?.let { mediaTypeFor(it.type, it.data) }
        val content = when {
            m.deleted -> ""
            m.text != null -> m.text
            type == MediaType.DOCUMENT -> md?.fileName ?: ""
            else -> ""
        }
        return Message(
            id = m.id,
            senderPublicKey = if (m.outgoing) selfKey else m.senderPublicKey,
            recipientPublicKey = if (m.outgoing) peer else selfKey,
            content = content,
            timestamp = m.timestampMs,
            isFromMe = m.outgoing,
            isRead = true,
            isDelivered = !m.outgoing || m.status == "delivered" || m.status == "read",
            mediaType = type,
            mediaData = mediaBytes,
            replyToId = m.replyToId,
            editedAt = m.editedAtMs,
            editedContent = m.editedText,
            isDeletedForEveryone = m.deleted,
            isViewOnce = m.viewOnce,
            // A view-once whose bytes were not exported is, here, already spent.
            viewOnceOpened = m.viewOnce && md?.data == null,
            isFailed = m.status == "failed",
        )
    }

    fun toGroup(m: ExportV2.Msg, localGroupId: String, selfKey: String, senderNameFallback: String?, mediaBytes: ByteArray?): GroupMessage {
        val md = m.media
        val type = md?.let { mediaTypeFor(it.type, it.data) }
        val reactions = LinkedHashMap<String, String>()
        m.reactions.forEach { (emoji, keys) -> keys.forEach { reactions[it] = emoji } }
        return GroupMessage(
            id = m.id,
            groupId = localGroupId,
            senderPublicKey = if (m.outgoing) selfKey else m.senderPublicKey,
            senderName = m.senderName ?: senderNameFallback ?: "",
            content = if (m.deleted) "" else m.text ?: "",
            timestamp = m.timestampMs,
            isFromMe = m.outgoing,
            isRead = true,
            mediaType = type,
            mediaData = mediaBytes,
            mediaFileName = md?.fileName,
            replyToId = m.replyToId,
            reactions = reactions,
            isDeleted = m.deleted,
            editedContent = m.editedText,
            editedAt = m.editedAtMs,
        )
    }
}
