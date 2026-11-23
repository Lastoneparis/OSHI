package com.oshi.messenger.network.v2

import org.json.JSONObject
import java.util.Base64

/**
 * The ratchet-message payload that carries a file's key material. The bytes live in
 * the /v2/blobs store; THIS travels inside the (E2E) ratchet message.
 *
 * Field-for-field port of iOS `struct V2FileKeyMessage` (V2MessageRouter.swift:44-74).
 * The names, the ordering and the optionality are the Swift `Codable` synthesis, so
 * they are the wire contract:
 *
 *   {"kind":"v2file","blobId":…,"fileKey":<b64 32>,"fileNonce":<b64 8>,
 *    "chunkCount":N,"manifest":<b64 ct‖tag>,"filename":…,"mime":…,
 *    "mediaType":"image|video|audio|document|contact"?,"isViewOnce":true?,
 *    "groupMessage":<b64 of the JSON GroupMessage, media stripped>?}
 *
 * Base64 is STANDARD + padded, matching Swift `Data.base64EncodedString()`
 * (never base64url). `java.util.Base64` is used rather than `android.util.Base64`
 * so the wire format is unit-testable on the JVM; both emit the same bytes and
 * minSdk is 26.
 *
 * RECEIVER RULE (iOS V2MessageRouter.swift:1057-1064): if the ratchet plaintext
 * JSON-decodes with `kind == "v2file"` it is a file; otherwise it is UTF-8 text.
 * iOS decodes into a struct whose non-optional fields must all be present, so
 * [parse] mirrors that and returns null when any of them is missing.
 */
data class V2FileKeyMessage(
    val blobId: String,
    val fileKey: ByteArray,     // 32 bytes
    val fileNonce: ByteArray,   // 8 bytes
    val chunkCount: Int,
    val manifest: ByteArray,    // ct‖tag of the sealed manifest JSON
    val filename: String,
    val mime: String,
    /** Raw value of iOS `MediaManager.MediaType` — image/video/audio/document/contact. */
    val mediaType: String? = null,
    /** iOS writes `true` or omits the key entirely (V2MessageRouter.swift:755). */
    val isViewOnce: Boolean? = null,
    // ---- Progressive / timed reveal (iOS V2MessageRouter.swift:80-91) ----------
    // Four OPTIONAL scalars on the exact pattern of `isViewOnce` above, declared
    // BETWEEN it and `groupMessage` because that is the Swift declaration order and
    // therefore the JSON key order. All four are omitted when no reveal is armed, so
    // today's traffic stays byte-identical.
    //
    // TOLERANT SCALARS, NEVER AN ENUM: an unknown KEY is ignored by both decoders, but
    // an unknown enum VALUE makes Swift's `init(from:)` throw and takes the whole
    // message with it (V2MessageRouter.swift:74-78).
    /** Message id of layer 0 — the bubble every later layer rewrites. */
    val revealId: String? = null,
    /** Rank of this layer; 0 = blurriest, sent immediately. */
    val revealLayerIndex: Int? = null,
    /** TOTAL layers as the encoder actually produced them — never guessed. */
    val revealLayerCount: Int? = null,
    /** Total reveal duration in MILLISECONDS (a week = 604 800 000, fits in an Int). */
    val revealTotalMs: Int? = null,
    // ---- __VIDEO_NOTE_2026_09_24__ Round video message (docs/VIDEO_NOTE_SPEC.md §2.A) ----
    // Two more tolerant optional scalars on the same pattern: `true` or ABSENT for the
    // flag, an integer millisecond count for the duration. Both omitted for any other
    // attachment, so every non-note payload stays byte-identical.
    /** `true` on a video note, otherwise null (never `false` on the wire). */
    val videoNote: Boolean? = null,
    /** Recorded duration in ms, clamped to 15000 on read; null when unknown. */
    val durationMs: Int? = null,
    /** GROUP MEDIA ONLY: base64 of the JSON GroupMessage with inline media stripped. */
    val groupMessage: String? = null,
    /**
     * __V2_CAPTION_2026_09_27__ ANDROID EXTENSION, not an iOS field. The text typed
     * over a photo or video. iOS's `V2FileKeyMessage` has no caption and its decoder
     * ignores unknown keys, so a shipped iPhone never sees this — the sender also posts
     * the caption as a follow-up text for it (`MessageRepository.captionFollowUpId`).
     * An Android receiver shows it on the media and drops that follow-up. Last key,
     * omitted when null, so every caption-less payload stays byte-identical.
     */
    val caption: String? = null,
) {
    /**
     * Encode in the Swift declaration order (V2MessageRouter.swift:44-74) with nil
     * optionals OMITTED, which is what `JSONEncoder` does by default.
     */
    fun toJson(): String {
        val sb = StringBuilder(256 + manifest.size * 2)
        sb.append("{\"kind\":\"").append(KIND).append('"')
        sb.append(",\"blobId\":\"").append(OSHICryptoV2.jsonEscape(blobId)).append('"')
        sb.append(",\"fileKey\":\"").append(b64(fileKey)).append('"')
        sb.append(",\"fileNonce\":\"").append(b64(fileNonce)).append('"')
        sb.append(",\"chunkCount\":").append(chunkCount)
        sb.append(",\"manifest\":\"").append(b64(manifest)).append('"')
        sb.append(",\"filename\":\"").append(OSHICryptoV2.jsonEscape(filename)).append('"')
        sb.append(",\"mime\":\"").append(OSHICryptoV2.jsonEscape(mime)).append('"')
        mediaType?.let { sb.append(",\"mediaType\":\"").append(OSHICryptoV2.jsonEscape(it)).append('"') }
        isViewOnce?.let { sb.append(",\"isViewOnce\":").append(it) }
        revealId?.let { sb.append(",\"revealId\":\"").append(OSHICryptoV2.jsonEscape(it)).append('"') }
        revealLayerIndex?.let { sb.append(",\"revealLayerIndex\":").append(it) }
        revealLayerCount?.let { sb.append(",\"revealLayerCount\":").append(it) }
        revealTotalMs?.let { sb.append(",\"revealTotalMs\":").append(it) }
        if (videoNote == true) sb.append(",\"videoNote\":true")
        durationMs?.let { sb.append(",\"durationMs\":").append(it) }
        groupMessage?.let { sb.append(",\"groupMessage\":\"").append(OSHICryptoV2.jsonEscape(it)).append('"') }
        caption?.let { sb.append(",\"caption\":\"").append(OSHICryptoV2.jsonEscape(it)).append('"') }
        sb.append('}')
        return sb.toString()
    }

    fun toBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is V2FileKeyMessage) return false
        return blobId == other.blobId &&
            fileKey.contentEquals(other.fileKey) &&
            fileNonce.contentEquals(other.fileNonce) &&
            chunkCount == other.chunkCount &&
            manifest.contentEquals(other.manifest) &&
            filename == other.filename &&
            mime == other.mime &&
            mediaType == other.mediaType &&
            isViewOnce == other.isViewOnce &&
            revealId == other.revealId &&
            revealLayerIndex == other.revealLayerIndex &&
            revealLayerCount == other.revealLayerCount &&
            revealTotalMs == other.revealTotalMs &&
            videoNote == other.videoNote &&
            durationMs == other.durationMs &&
            groupMessage == other.groupMessage &&
            caption == other.caption
    }

    override fun hashCode(): Int {
        var r = blobId.hashCode()
        r = 31 * r + fileKey.contentHashCode()
        r = 31 * r + fileNonce.contentHashCode()
        r = 31 * r + chunkCount
        r = 31 * r + manifest.contentHashCode()
        r = 31 * r + filename.hashCode()
        r = 31 * r + mime.hashCode()
        r = 31 * r + (mediaType?.hashCode() ?: 0)
        r = 31 * r + (isViewOnce?.hashCode() ?: 0)
        r = 31 * r + (revealId?.hashCode() ?: 0)
        r = 31 * r + (revealLayerIndex ?: 0)
        r = 31 * r + (revealLayerCount ?: 0)
        r = 31 * r + (revealTotalMs ?: 0)
        r = 31 * r + (videoNote?.hashCode() ?: 0)
        r = 31 * r + (durationMs ?: 0)
        r = 31 * r + (groupMessage?.hashCode() ?: 0)
        r = 31 * r + (caption?.hashCode() ?: 0)
        return r
    }

    /**
     * The four reveal scalars, VALIDATED — iOS `RevealWirePlan.init?(key:)`
     * (MessageManager+V2.swift:943-961). Null when no reveal is armed OR when the plan
     * is only half-present: a half-read schedule is worse than none (a `layerCount` of 1
     * divides by zero and yields a NaN countdown).
     */
    val revealPlan: V2RevealPlan?
        get() = V2RevealPlan.from(this)

    companion object {
        /** iOS `V2FileKeyMessage.kindValue` (V2MessageRouter.swift:73). */
        const val KIND = "v2file"

        private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

        private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

        /**
         * Parse a ratchet plaintext. Returns null when it is not a v2file payload —
         * the caller then treats the bytes as UTF-8 text, exactly like iOS's
         * `parse(_:env:)` (V2MessageRouter.swift:1057-1064).
         */
        fun parse(plaintext: ByteArray): V2FileKeyMessage? =
            parse(String(plaintext, Charsets.UTF_8))

        fun parse(text: String): V2FileKeyMessage? {
            val t = text.trimStart()
            // Cheap reject before allocating a JSONObject for every inbound text.
            if (!t.startsWith("{") || !t.contains("\"$KIND\"")) return null
            return try {
                val o = JSONObject(t)
                if (o.optString("kind") != KIND) return null
                V2FileKeyMessage(
                    blobId = o.getString("blobId"),
                    fileKey = unb64(o.getString("fileKey")),
                    fileNonce = unb64(o.getString("fileNonce")),
                    chunkCount = o.getInt("chunkCount"),
                    manifest = unb64(o.getString("manifest")),
                    filename = o.getString("filename"),
                    mime = o.getString("mime"),
                    mediaType = o.optStringOrNull("mediaType"),
                    isViewOnce = if (o.has("isViewOnce") && !o.isNull("isViewOnce")) {
                        o.optBoolean("isViewOnce")
                    } else null,
                    // Tolerant scalars: absent → null, never a throw, never an enum.
                    revealId = o.optStringOrNull("revealId"),
                    revealLayerIndex = o.optIntOrNull("revealLayerIndex"),
                    revealLayerCount = o.optIntOrNull("revealLayerCount"),
                    revealTotalMs = o.optIntOrNull("revealTotalMs"),
                    // Spec §1 rules 2 and 4: a malformed value degrades to "unknown",
                    // it never costs the message.
                    videoNote = com.oshi.messenger.service.media.VideoNote.flagFrom(o),
                    durationMs = com.oshi.messenger.service.media.VideoNote.durationFrom(o),
                    groupMessage = o.optStringOrNull("groupMessage"),
                    caption = o.optStringOrNull("caption"),
                )
            } catch (_: Exception) {
                null   // malformed → iOS's JSONDecoder would also fail → treat as text
            }
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

        /** Absent or unreadable → null. A garbage scalar must never sink the envelope. */
        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) {
                val v = optInt(key, Int.MIN_VALUE)
                if (v == Int.MIN_VALUE) null else v
            } else null
    }
}

/**
 * A reveal plan that passed the SAME validation iOS applies on receipt
 * (`RevealWirePlan.init?(key:)`, MessageManager+V2.swift:943-961). Built only from a
 * complete, in-range set of the four wire scalars.
 *
 * NO TIMESTAMP CROSSES THE WIRE, DELIBERATELY (MessageManager+V2.swift:970-973): the
 * DURATION travels, the time origin is the receiver's LOCAL arrival. A start date on
 * the wire would let a sender post-date a reveal and have the photo land sharp.
 */
data class V2RevealPlan(
    val revealId: String,
    val layerIndex: Int,
    val layerCount: Int,
    val totalMs: Int,
) {
    /** Total duration in seconds, as iOS's `totalSeconds` (MessageManager+V2.swift:947). */
    val totalSeconds: Double get() = totalMs / 1000.0

    /** The sharpest layer's rank. */
    val finalIndex: Int get() = layerCount - 1

    companion object {
        /** iOS `TimedRevealSchedule` bounds (TimedReveal.swift:147-209). */
        const val MIN_LAYERS = 2
        const val MAX_LAYERS = 64

        /** iOS caps a reveal at seven days (MessageManager+V2.swift:953). */
        const val MAX_TOTAL_MS = 7 * 24 * 3600 * 1000

        fun from(key: V2FileKeyMessage): V2RevealPlan? {
            val id = key.revealId?.takeIf { it.isNotEmpty() } ?: return null
            val index = key.revealLayerIndex ?: return null
            val count = key.revealLayerCount ?: return null
            val ms = key.revealTotalMs ?: return null
            if (index < 0 || count < MIN_LAYERS || count > MAX_LAYERS) return null
            if (ms <= 0 || ms > MAX_TOTAL_MS) return null
            if (index >= count) return null
            return V2RevealPlan(id, index, count, ms)
        }

        /**
         * The msgId layer [k] travels under: layer 0 IS the reveal id, later layers get
         * `"<revealId>#k"` (TimedReveal.swift:238) precisely so a queue that dedups by
         * id does not drop them.
         */
        fun layerMsgId(revealId: String, k: Int): String = if (k == 0) revealId else "$revealId#$k"
    }
}
