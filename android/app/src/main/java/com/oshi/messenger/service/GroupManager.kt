package com.oshi.messenger.service

import com.oshi.messenger.ui.viewmodel.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.oshi.messenger.R
import com.oshi.messenger.ui.MainActivity
import com.oshi.messenger.data.model.*
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.encryption.DoubleRatchet
import com.oshi.messenger.network.encryption.GroupEnvelopeV3
import com.oshi.messenger.network.encryption.PeerCapabilities
import com.oshi.messenger.network.mesh.CrossPlatformMesh
import com.oshi.messenger.network.vps.VPSClient
import com.oshi.messenger.network.v2.V2GroupHandleClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GroupManager - Compatible with iOS GroupManager.swift
 * Handles group creation, management, and messaging
 */
@Singleton
class GroupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    // CV-002: group envelope v3 wraps the content key over each member's 1:1
    // ratchet, so the group manager needs the ratchet the same way the message
    // repository does. Hilt already provides it as a singleton.
    private val doubleRatchet: DoubleRatchet,
    private val vpsClient: VPSClient,
    // Bot trigger callback — set by BotManager's init (__BOT_IN_THE_PIPELINE_2026_11_09__;
    // it was documented as set by MainViewModel and was set by nobody). `botAuthored` is
    // the wire's [BOT_AUTHOR_FIELD]: a bot reply must never fire another bot.
    var onGroupMessageForBots: ((groupId: String, content: String, senderPublicKey: String, botAuthored: Boolean) -> Unit)? = null,
    // Contact alias resolver — set by MessageRepository to resolve public keys to display names
    var contactAliasResolver: ((publicKey: String) -> String?)? = null,
    private val crossPlatformMesh: CrossPlatformMesh,
    // __GROUP_E2E_V2_2026_09_23__ the ONLY group transport (docs/GROUP_E2E_V2_SPEC.md §0).
    // Null only in JVM unit tests that construct the manager by hand.
    private val v2Router: dagger.Lazy<com.oshi.messenger.network.v2.V2MessageRouter>? = null,
    // __GROUP_SETTINGS_2026_11_20__ public link registry (GROUP_INFO_SETTINGS_SPEC §4).
    // __INTEROP_2026_11_21__ NO default on purpose: it had `= null`, AppModule never passed it,
    // and every production build resolved every public link to "This link no longer works"
    // and could not make a group Public (found by the first Android↔Desktop run). A caller
    // that wants no registry must now say `null` out loud.
    private val handleClientLazy: dagger.Lazy<V2GroupHandleClient>?
) {
    /**
     * Where a group attachment's bytes go when the message list is persisted.
     * Nested on the class rather than in the companion so it reads as
     * `GroupManager.GroupMediaSink` from every caller, tests included.
     */
    enum class GroupMediaSink {
        /** No attachment. */
        NONE,

        /** Small enough to base64 into the SharedPreferences document. */
        INLINE,

        /** Goes to the file store, keyed by message id. */
        FILE
    }

    companion object {
        private const val TAG = "GroupManager"
        // SECURITY (CSPN 2026-09): compile-time guard. Must stay false in release.
        private const val LOG_SENSITIVE = false
        private const val GROUP_MESSAGE_PREFIX = "\uD83D\uDC65GROUP_MESSAGE\uD83D\uDC65"  // 👥GROUP_MESSAGE👥
        const val GROUP_UPDATE_PREFIX = "\uD83D\uDCE2GROUP_UPDATE\uD83D\uDCE2"    // 📢GROUP_UPDATE📢
        // iOS location message prefix (from LocationMessage.messagePrefix in iOS)
        const val LOCATION_PREFIX = "\uD83D\uDCCDLOCATION\uD83D\uDCCD"  // 📍LOCATION📍
        // iOS reaction message prefix (from ReactionPayload.messagePrefix in iOS)
        private const val REACTION_PREFIX = "\uD83D\uDD25REACTION\uD83D\uDD25"  // 🔥REACTION🔥

        /**
         * Map iOS MediaType raw string values to Android MediaType enum.
         * iOS uses lowercase ("photo","video","audio","document","contact").
         * Android uses uppercase enum names (IMAGE, VIDEO, AUDIO, DOCUMENT).
         */
        // 🔧 FIX (2026-08-24 three-gaps): "contact" was listed in the doc comment
        // above as a value iOS sends (`OSHI/GroupMessaging.swift:406-412`) and the
        // `when` did not handle it, so every group contact card — from an iPhone or
        // from another Android — lost its type on the way in and rendered as a text
        // bubble full of JSON.
        //
        // 🔧 FIX (2026-09-12 iOS audit port): the table moved to
        // [MediaType.fromWireLabel] so it is learnt ONCE for every transport. That is
        // what brings iOS's incoming `.gif` in as an image here instead of null. The
        // contract is unchanged: an unknown value is still null, never an exception.
        fun mapIOSMediaType(iosValue: String): MediaType? = MediaType.fromWireLabel(iosValue)

        // =====================================================================
        // iOS WIRE FORMATS — pure functions, no Android APIs, JVM-unit-testable
        // =====================================================================

        /** Seconds between the Unix epoch and Apple's reference date (2001-01-01). */
        const val APPLE_EPOCH_OFFSET = 978307200.0

        /**
         * __GROUP_E2E_V2_2026_09_23__ The largest `📢GROUP_UPDATE📢` plaintext we send: the relay
         * caps an envelope at 256 KiB of BASE64 ciphertext (relay_v2.js MAX_CIPHERTEXT_BYTES),
         * i.e. ~196 KB of plaintext. GROUP_E2E_V2_SPEC §5.1.
         */
        const val MAX_STATE_PLAINTEXT_BYTES = 180_000

        /**
         * GROUP_E2E_V2_SPEC §3 steps 3-4 on a decoded v2 content payload: the GroupMessage must
         * belong to the envelope's group, and its author must be the envelope's authenticated
         * sender — for a SYSTEM command, `systemMessageData.actorPublicKey` must be. Pure.
         */
        fun v2ContentAuthentic(json: JSONObject, envelopeFrom: String, envelopeGroupId: String?): Boolean {
            val gid = json.optString("groupId", "")
            if (gid.isEmpty()) return false
            if (envelopeGroupId != null && !canonicalGroupId(gid).equals(canonicalGroupId(envelopeGroupId), ignoreCase = true)) return false
            val from = GroupUpdateAuthorizer.canonicalIdentity(envelopeFrom)
            val sender = json.optString("senderPublicKey", "")
            if (sender == SYSTEM_SENDER) {
                val actor = json.optJSONObject("systemMessageData")?.optString("actorPublicKey", "").orEmpty()
                return actor.isNotEmpty() && GroupUpdateAuthorizer.canonicalIdentity(actor) == from
            }
            return sender.isNotEmpty() && GroupUpdateAuthorizer.canonicalIdentity(sender) == from
        }

        /**
         * The GroupMessage JSON of the content channel (GROUP_E2E_V2_SPEC §2.2), pure so the golden
         * vectors can check it on the JVM. [encryptedContentB64] is base64 of the UTF-8 body ("" for
         * v2 media). The caption rides `plaintextContent`, media only.
         */
        fun wireGroupMessageJson(
            id: String,
            wireGroupId: String,
            senderPublicKey: String,
            appleTimestamp: Double,
            encryptedContentB64: String,
            iosMediaType: String?,
            mediaFileName: String?,
            body: String,
            includeBodyCompat: Boolean,
            /** __MENTIONS_2026_09_23__ [com.oshi.messenger.util.MentionWire.render] output, or null. */
            mentionsRendered: String? = null,
            /** __VIDEO_NOTE_2026_09_24__ docs/VIDEO_NOTE_SPEC.md §2.B/§2.D — top level, true-or-absent. */
            videoNote: Boolean = false,
            durationMs: Int? = null,
            /** __BOT_IN_THE_PIPELINE_2026_11_09__ name of the bot that wrote this, or null. */
            botAuthor: String? = null,
            /** __GROUP_COMMENTS_2026_11_21__ spec §2.12.2 — the post this comments on, or null. */
            commentOn: String? = null
        ): String = JSONObject().apply {
            put("id", id)
            put("groupId", wireGroupId)
            put("senderPublicKey", senderPublicKey)
            put("encryptedContent", encryptedContentB64)
            put("timestamp", appleTimestamp)
            put("isRead", false)
            put("messageChainIndex", 0)
            iosMediaType?.let { put("mediaType", it) }
            mediaFileName?.takeIf { it.isNotEmpty() && iosMediaType != null }?.let { put("mediaFileName", it) }
            if (iosMediaType != null && body.isNotEmpty()) put("plaintextContent", body)
            put("messageId", id)
            put("content", if (includeBodyCompat) body else "")
            put("senderName", "")
            // __MENTIONS_2026_09_23__ optional, last; every shipped decoder ignores it (MentionWire).
            mentionsRendered?.let { put(com.oshi.messenger.util.MentionWire.FIELD, JSONArray(it)) }
            com.oshi.messenger.service.media.VideoNote.putInto(this, videoNote && iosMediaType == "video", durationMs)
            // Optional, last, absent for a human message; iOS's Codable decoder ignores unknown keys.
            botAuthor?.takeIf { it.isNotBlank() }?.let { put(BOT_AUTHOR_FIELD, it) }
            commentOn?.takeIf { it.isNotBlank() }?.let { put(GroupComments.FIELD, it) }
        }.toString()

        /**
         * __BOT_IN_THE_PIPELINE_2026_11_09__ Marks a GroupMessage as a bot's reply.
         *
         * Why not iOS's `senderPublicKey: "bot:…"`: GROUP_E2E_V2_SPEC §3 makes the
         * author the ratchet's sender, never a JSON claim — [v2ContentAuthentic] here and
         * `GroupWireV2.check` on iOS both DROP a payload whose sender is not the envelope's
         * (`sender-mismatch`). Relaxing that for `bot:` would let any member post as a bot,
         * and a bot sender is what unlocks inline keyboards and `botId` dispatch. So the
         * reply travels under the owner's authenticated key with iOS's `[Name] text` body,
         * and this flag is only the loop guard: it can suppress a trigger, never grant one.
         */
        const val BOT_AUTHOR_FIELD = "botAuthor"

        /** Spec §5.3: reject only a definition OLDER than ours; unversioned on either side ⇒ accept. */
        fun stateVersionAccepts(local: Int?, incoming: Int?): Boolean =
            local == null || incoming == null || incoming >= local

        /**
         * MIME for a group attachment (iOS `MessageGroupManager.v2Mime`, GROUP enum spelling).
         *
         * __GROUP_GIF_2026_11_16__ [isGif]: the GROUP enum has no `gif` case on any
         * platform (iOS `GroupMessaging.swift` `MediaType`, desktop `GroupMessageWire`), so
         * a GIF travels as `photo` — exactly what iOS's own group composer does
         * (`GroupViews.swift` `convertToGroupMediaType(.gif) -> .photo`). Every group
         * receiver picks the bubble from `mediaType` and then SNIFFS the bytes
         * (iOS `LazyGroupPhotoView` → `MediaManager.isGIFData`, desktop `GifBubble`,
         * Android `BubbleMedia`'s GIF decoder). Only the MIME is made honest, `image/gif`
         * — still `image/…` for any reader that falls back to the prefix — so nothing
         * downstream that trusts the label can mistake it for a JPEG and re-encode it.
         */
        fun groupMime(iosMediaType: String, isGif: Boolean = false): String = when (iosMediaType) {
            "photo" -> if (isGif) "image/gif" else "image/jpeg"
            "video" -> "video/mp4"
            "audio" -> "audio/m4a"
            "contact" -> "text/vcard"
            else -> "application/octet-stream"
        }

        /**
         * __GROUP_GIF_2026_11_16__ The `mediaFileName` / blob filename of a group GIF:
         * the picker's name, forced to end in `.gif` (a peer that trusts the extension
         * must not be told it is a JPEG), path-free, and never blank.
         */
        fun groupGifFileName(picked: String?): String {
            val base = picked.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
            if (base.isEmpty() || base == ".gif") return "animation.gif"
            return if (base.endsWith(".gif", ignoreCase = true)) base else "$base.gif"
        }

        /** iOS `v2DefaultFileName`: cosmetic, the receiver renders from `mediaType`. */
        fun defaultGroupFileName(iosMediaType: String): String = when (iosMediaType) {
            "photo" -> "photo.jpg"
            "video" -> "video.mp4"
            "audio" -> "audio.m4a"
            "contact" -> "contact.vcf"
            else -> "file.dat"
        }

        /** iOS reply envelope prefix — MessageActionsManager.swift:97 `MessageWithReply.replyPrefix`. */
        const val REPLY_PREFIX = "💬REPLY💬"   // 💬REPLY💬

        /** iOS typing prefix — TypingIndicatorManager.swift:15 `TypingStatus.messagePrefix`. */
        const val TYPING_PREFIX = "⌨️TYPING⌨️"

        /** Unix millis → Apple reference-date seconds (what Swift's default `JSONEncoder` writes for `Date`). */
        fun appleEpoch(unixMillis: Long): Double = (unixMillis / 1000.0) - APPLE_EPOCH_OFFSET

        /**
         * Unix millis → the exact string Swift's `JSONEncoder(dateEncodingStrategy: .iso8601)`
         * produces (`.withInternetDateTime`, UTC, no fractional seconds).
         *
         * This matters: `broadcastFullGroupUpdate` (GroupMessaging.swift:2040-2042) encodes with
         * `.iso8601`, and `handleIncomingGroupUpdate` (GroupMessaging.swift:789-791) decodes with
         * `.iso8601`. `MessageGroup.init(from:)` reads `groupPictureUpdatedAt` /
         * `groupWallpaperUpdatedAt` through a bare `decodeIfPresent(Date.self,…)` (swift:203, :208)
         * — NOT wrapped in `try?` — so a numeric timestamp there throws and sinks the whole strict
         * decode into the minimal-JSON switch, whose `default:` (swift:992) drops everything.
         * Every date in this payload must therefore be an ISO-8601 string or be absent.
         */
        fun iso8601(unixMillis: Long): String {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            return fmt.format(Date(unixMillis))
        }

        /** Parse an ISO-8601 instant emitted by Swift's `.iso8601` strategy back to Unix millis. */
        fun parseIso8601(value: String): Long? {
            if (value.isEmpty()) return null
            for (pattern in arrayOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")) {
                try {
                    val fmt = java.text.SimpleDateFormat(pattern, Locale.US)
                    fmt.timeZone = TimeZone.getTimeZone("UTC")
                    return fmt.parse(value)?.time
                } catch (_: Exception) { }
            }
            return null
        }

        /**
         * iOS `MessageGroup.id` is a `UUID`, re-encoded by Swift as an UPPERCASE uuidString.
         * Uppercase only when the id really is a UUID so a non-UUID invite `gid` is not corrupted.
         */
        fun canonicalGroupId(groupId: String): String = try {
            UUID.fromString(groupId).toString().uppercase(Locale.US)
        } catch (_: Exception) { groupId }

        /** Android [GroupType] → the iOS `GroupType` raw value (GroupMessaging.swift:37-40). */
        fun iosGroupTypeRaw(type: GroupType): String = type.value

        /**
         * Encode the **full iOS `MessageGroup` JSON** — the only group-update shape iPhones act on.
         *
         * `handleIncomingGroupUpdate` (GroupMessaging.swift:786) tries this shape FIRST
         * (swift:789-791). On success iOS applies the name (:827), the whole member list with
         * per-member `isAdmin` (:818-826), the picture (:832), the wallpaper (:836), the pin
         * (:840-845), and — when the group is unknown and our key is in `members` — it CREATES the
         * group (:881-902). Every minimal `{"type":…}` shape Android used to send fell through to
         * the `default:` at :992 and was dropped.
         *
         * Field set + optionality mirror `MessageGroup.init(from:)` (GroupMessaging.swift:178-252)
         * and `CodingKeys` (:254-259) exactly:
         *  - required: `id`(UUID) `name` `type`(raw value) `adminPublicKey`
         *  - `createdAt`/`lastActivity`: ISO-8601 (swift:186-199 also accepts Apple-epoch Doubles)
         *  - `members`: `[{publicKey, alias?, joinedAt, isAdmin}]` (GroupMember, swift:274-309)
         *  - optional: `groupPictureData`/`groupWallpaperData` (base64 `Data`), their
         *    `…UpdatedAt`(ISO-8601)/`…UpdatedBy`, `isMuted`, `pinnedMessageId`, `pinnedBy`, `avatar`
         *
         * Blobs are passed in already base64-encoded so this function stays free of
         * `android.util.Base64` and can be exercised by a plain JVM unit test.
         */
        fun buildIosMessageGroupJson(
            groupId: String,
            name: String,
            typeRaw: String,
            adminPublicKey: String,
            memberKeys: List<String>,
            adminKeys: List<String>,
            createdAtMs: Long,
            lastActivityMs: Long,
            memberAliases: Map<String, String> = emptyMap(),
            groupPictureBase64: String? = null,
            groupPictureUpdatedBy: String? = null,
            groupPictureUpdatedAtMs: Long? = null,
            groupWallpaperBase64: String? = null,
            groupWallpaperUpdatedBy: String? = null,
            groupWallpaperUpdatedAtMs: Long? = null,
            pinnedMessageId: String? = null,
            pinnedBy: String? = null,
            avatar: String? = null,
            isMuted: Boolean = false,
            // __GROUP_E2E_V2_2026_09_23__ GROUP_E2E_V2_SPEC §5.1 — omitted when null, so every
            // existing byte-for-byte expectation is unchanged.
            stateVersion: Int? = null,
            description: String? = null,
            evictedMemberKeys: List<String>? = null,
            // __GROUP_SETTINGS_2026_11_20__ GROUP_INFO_SETTINGS_SPEC §2.1 / §2.2: `settings`
            // rides only when this device holds one; `description` is emitted even when empty
            // once the group has had one (`""` = cleared; absent = keep).
            settings: com.oshi.messenger.data.model.GroupSettings? = null,
            emitEmptyDescription: Boolean = false
        ): String {
            val members = JSONArray()
            val joinedAtIso = iso8601(createdAtMs)
            memberKeys.distinct().forEach { key ->
                members.put(JSONObject().apply {
                    put("publicKey", key)
                    memberAliases[key]?.takeIf { it.isNotEmpty() }?.let { put("alias", it) }
                    // GroupMember has no per-member join time on Android; iOS falls back to
                    // `createdAt` for the same reason (swift:234).
                    put("joinedAt", joinedAtIso)
                    put("isAdmin", adminKeys.contains(key))
                })
            }
            // Guard the three required strings: a `type` outside the iOS enum makes
            // `decode(GroupType.self)` (swift:183) throw and the whole payload gets dropped.
            val safeType = when (typeRaw) {
                "admin_only", "collaborative", "public" -> typeRaw
                else -> "collaborative"
            }
            return JSONObject().apply {
                put("id", canonicalGroupId(groupId))
                put("name", name)
                put("type", safeType)
                put("adminPublicKey", adminPublicKey)
                put("members", members)
                put("createdAt", iso8601(createdAtMs))
                put("lastActivity", iso8601(lastActivityMs))
                put("isMuted", isMuted)
                groupPictureBase64?.takeIf { it.isNotEmpty() }?.let {
                    put("groupPictureData", it)
                    groupPictureUpdatedBy?.takeIf { b -> b.isNotEmpty() }?.let { b -> put("groupPictureUpdatedBy", b) }
                    groupPictureUpdatedAtMs?.let { ms -> put("groupPictureUpdatedAt", iso8601(ms)) }
                }
                groupWallpaperBase64?.takeIf { it.isNotEmpty() }?.let {
                    put("groupWallpaperData", it)
                    groupWallpaperUpdatedBy?.takeIf { b -> b.isNotEmpty() }?.let { b -> put("groupWallpaperUpdatedBy", b) }
                    groupWallpaperUpdatedAtMs?.let { ms -> put("groupWallpaperUpdatedAt", iso8601(ms)) }
                }
                pinnedMessageId?.takeIf { it.isNotEmpty() }?.let {
                    put("pinnedMessageId", it)
                    pinnedBy?.takeIf { b -> b.isNotEmpty() }?.let { b -> put("pinnedBy", b) }
                }
                avatar?.takeIf { it.isNotEmpty() }?.let { put("avatar", it) }
                if (description != null && (description.isNotEmpty() || emitEmptyDescription)) put("description", description)
                settings?.let { put("settings", it.toJson()) }
                evictedMemberKeys?.takeIf { it.isNotEmpty() }?.let { put("evictedMemberKeys", JSONArray(it)) }
                stateVersion?.let { put("stateVersion", it) }
            }.toString()
        }

        /**
         * The media blob that rides in a group message's `encryptedContent`.
         *
         * Key set and order mirror iOS's own builder (`GroupMessaging.swift:2534-2546`):
         * `type` / `mediaType` / `content` / `size`, then `fileName` **only when there is
         * one**. iOS reads it back at `GroupViews.swift:3087` and `:3304` to label a
         * document; omitting the key leaves `mediaFileName` nil, which is what iOS does
         * for a photo.
         *
         * `caption` is Android's own key and iOS never reads it — the caption iOS draws
         * comes from the message's `plaintextContent` (`GroupViews.swift:2617`; the media
         * JSON resolves to nil at `:2641`). It is kept so Android→Android keeps the
         * caption attached to the blob rather than to the envelope.
         *
         * The blob arrives already base64-encoded so this stays free of
         * `android.util.Base64` and runs in a plain JVM unit test.
         */
        fun buildGroupMediaJson(
            iosMediaType: String,
            contentBase64: String,
            size: Int,
            caption: String? = null,
            fileName: String? = null,
            /** __VIDEO_NOTE_2026_09_24__ spec §2.C shape inside the legacy group blob. */
            videoNote: Boolean = false,
            durationMs: Int? = null
        ): String = JSONObject().apply {
            put("type", "media")
            put("mediaType", iosMediaType)
            put("content", contentBase64)
            put("size", size)
            caption?.takeIf { it.isNotEmpty() }?.let { put("caption", it) }
            fileName?.takeIf { it.isNotEmpty() }?.let { put("fileName", it) }
            com.oshi.messenger.service.media.VideoNote.putInto(this, videoNote && iosMediaType == "video", durationMs)
        }.toString()

        /** True when [json] is an iOS `MessageGroup` payload rather than a minimal `{"type":…}` update. */
        fun looksLikeIosMessageGroup(json: JSONObject): Boolean =
            json.has("name") && json.has("adminPublicKey") &&
                json.optString("type") in setOf("admin_only", "collaborative", "public")

        /** `{"type":"member_removed","groupId":…,"memberPublicKey":…}` — iOS handles it at GroupMessaging.swift:962. */
        fun buildMemberRemovedJson(groupId: String, memberPublicKey: String): String =
            JSONObject().apply {
                put("type", "member_removed")
                put("groupId", canonicalGroupId(groupId))
                put("memberPublicKey", memberPublicKey)
            }.toString()

        /**
         * __GROUP_COMPAT_IGNORE_2026_09_23__ The framed companions [updateGroup] sends AFTER the
         * definition: `group_renamed` on a rename (spec §5.2), the legacy Android-only
         * `{"type":"updated"}` only when the description changed (Android ≤ 1.6 has no other
         * description path). A pure rename therefore emits exactly the spec's `rename_minimal`.
         */
        fun updateCompanionFrames(
            groupId: String,
            name: String,
            description: String,
            renamed: Boolean,
            descriptionChanged: Boolean,
        ): List<String> = buildList {
            if (renamed) add("$GROUP_UPDATE_PREFIX${buildGroupRenamedJson(groupId, name)}")
            if (descriptionChanged) add("$GROUP_UPDATE_PREFIX${buildLegacyUpdatedJson(groupId, name, description)}")
        }

        /** Android's legacy `{"type":"updated",groupId,name,description}` (Android-only receivers). */
        fun buildLegacyUpdatedJson(groupId: String, name: String, description: String): String =
            JSONObject().apply {
                put("type", "updated")
                put("groupId", canonicalGroupId(groupId))
                put("name", name)
                put("description", description)
            }.toString()

        /** `{"type":"group_renamed","groupId":…,"name":…}` — iOS handles it at GroupMessaging.swift:972. */
        fun buildGroupRenamedJson(groupId: String, name: String): String =
            JSONObject().apply {
                put("type", "group_renamed")
                put("groupId", canonicalGroupId(groupId))
                put("name", name)
            }.toString()

        /**
         * Wrap a body in the iOS reply envelope: `💬REPLY💬{"content":…,"replyTo":{…}}`.
         *
         * `MessageWithReply` (MessageActionsManager.swift:96-129) is encoded with a **default**
         * `JSONEncoder`, so `ReplyMessage.originalTimestamp` (:19) is an Apple-epoch Double, not an
         * ISO string. `originalMediaType` (:20) uses the *group* MediaType spelling
         * ("photo"/"video"/"audio"/"document"/"location", GroupMessaging.swift:376-382).
         * iOS parses this inside a group envelope at GroupViews.swift:2481.
         */
        fun buildReplyEnvelope(
            content: String,
            originalMessageId: String,
            originalSenderKey: String,
            originalText: String,
            originalTimestampMs: Long,
            originalMediaType: String? = null
        ): String {
            val replyTo = JSONObject().apply {
                put("originalMessageId", originalMessageId)
                put("originalSenderKey", originalSenderKey)
                // iOS truncates the quoted preview at 180 chars before it goes on the wire
                // (GroupViews.swift:4673) so a reply never carries a 4000-char body.
                put("originalText", originalText.take(180))
                put("originalTimestamp", appleEpoch(originalTimestampMs))
                if (originalMediaType != null) put("originalMediaType", originalMediaType)
                else put("originalMediaType", JSONObject.NULL)
            }
            return REPLY_PREFIX + JSONObject().apply {
                put("content", content)
                put("replyTo", replyTo)
            }.toString()
        }

        /** Body + quoted-message id parsed out of a `💬REPLY💬` envelope, or null when absent/broken. */
        data class ParsedReply(val content: String, val replyToId: String?)

        /** Inverse of [buildReplyEnvelope]. Returns null when [raw] is not a reply envelope. */
        fun parseReplyEnvelope(raw: String): ParsedReply? {
            if (!raw.startsWith(REPLY_PREFIX)) return null
            return try {
                val json = JSONObject(raw.removePrefix(REPLY_PREFIX))
                val body = json.optString("content", "")
                val replyId = json.optJSONObject("replyTo")
                    ?.optString("originalMessageId", "")
                    ?.takeIf { it.isNotEmpty() }
                ParsedReply(body, replyId)
            } catch (_: Exception) { null }
        }

        /**
         * `⌨️TYPING⌨️{…}` payload for a group.
         *
         * `TypingStatus.init(from:)` (TypingIndicatorManager.swift:36-43) decodes
         * `senderPublicKey`, `isTyping` and `timestamp` **non-optionally** — a payload missing any
         * of them throws and `fromMessageString` returns nil. `timestamp` is a `Date` read by a
         * default `JSONDecoder`, i.e. an Apple-epoch Double. The only live iOS ingress is the
         * group-message envelope (GroupMessaging.swift:2905-2912), never the 1:1 channel.
         */
        fun buildGroupTypingPayload(
            senderPublicKey: String,
            isTyping: Boolean,
            timestampMs: Long,
            groupId: String,
            senderName: String? = null
        ): String = TYPING_PREFIX + JSONObject().apply {
            put("senderPublicKey", senderPublicKey)
            put("isTyping", isTyping)
            put("timestamp", appleEpoch(timestampMs))
            put("groupId", canonicalGroupId(groupId))
            senderName?.takeIf { it.isNotEmpty() }?.let { put("senderName", it) }
        }.toString()

        /**
         * Resolved body of an inbound group message, with the media JSON given priority.
         *
         * 🐞 The bug this replaces: the old chain was
         * `content` → `plaintextContent` → base64-decoded `encryptedContent`. iOS puts the media
         * **caption** in `plaintextContent` (GroupMessaging.swift:2368) and the media JSON in
         * `encryptedContent` (:2323-2336), so a captioned photo resolved to the caption, the
         * `startsWith("{")` media branch never fired, and the image bytes were discarded forever.
         * An UNcaptioned photo has `plaintextContent = nil`, fell through to `encryptedContent`,
         * and worked — which is exactly why this survived testing.
         *
         * @param decodedEncryptedContent `encryptedContent` already base64-decoded to a UTF-8 string.
         */
        fun resolveIncomingBody(
            contentField: String,
            plaintextContentField: String,
            decodedEncryptedContent: String
        ): String {
            // The media/protocol JSON always wins, wherever it turned up.
            val mediaJson = listOf(decodedEncryptedContent, contentField, plaintextContentField)
                .firstOrNull { it.trimStart().startsWith("{") && it.contains("\"type\":\"media\"") }
            if (mediaJson != null) return mediaJson
            return contentField.takeIf { it.isNotEmpty() }
                ?: plaintextContentField.takeIf { it.isNotEmpty() }
                ?: decodedEncryptedContent
        }

        // =====================================================================
        // MESSAGE-ID MATCHING — the group counterpart of the ~20 `groupId`
        // `equals(…, ignoreCase = true)` sites already in this file.
        //
        // iOS's `GroupMessage.id` is a `UUID` (GroupMessaging.swift:342), not a String.
        // An Android-minted **lowercase** id parsed by iOS (`UUID(uuidString:)`, which is
        // case-insensitive, GroupMessaging.swift:726) is re-rendered by `uuidString` in
        // **UPPERCASE**. Every iOS group reaction targets `m.id.uuidString`
        // (GroupViews.swift:4417, :6128) and every iOS render reads it (:2758, :3247,
        // :3273, :6083) — always uppercase.
        //
        // Android compared with a plain `==`, so a reaction / read receipt / deletion /
        // reply-quote aimed at an Android-originated group message was **silently
        // dropped in both directions**.
        //
        // Ids are compared case-insensitively and **stored verbatim** — never case-folded
        // at rest, because the stored id is what Android echoes back to iOS on its own
        // outbound payloads.
        //
        // These are pure functions so the whole matching route is JVM-unit-testable
        // (`GroupReactionCaseTest`) with no device and no Android runtime.
        // =====================================================================

        /** Case-insensitive message lookup. Returns -1 when absent, like `indexOfFirst`. */
        fun indexOfMessageId(messages: List<GroupMessage>, messageId: String): Int =
            messages.indexOfFirst { it.id.equals(messageId, ignoreCase = true) }

        /** Case-insensitive membership test against a set of wire ids. */
        fun idInSet(id: String, ids: Set<String>): Boolean =
            ids.any { it.equals(id, ignoreCase = true) }

        /** A `🔥REACTION🔥{…}` payload, decoded. */
        data class ParsedGroupReaction(
            val messageId: String,
            val emoji: String,
            val senderPublicKey: String,
            val isRemoval: Boolean
        )

        /**
         * Decode a group `🔥REACTION🔥{…}` body. `senderPublicKey` falls back to the
         * transport-level sender when the payload omits it. Returns null when [raw] is
         * not a reaction envelope or carries no usable messageId/emoji.
         *
         * iOS's `ReactionEmoji` is a String-raw-value enum, so `JSONEncoder` emits
         * `"emoji":"👍"` — a **bare string**, never `{"rawValue":…}`. The object shape is
         * still tolerated here, exactly as the 1:1 path does
         * (`MessageReactionManager.kt` `handleIncomingReaction`), so the two Android
         * reaction decoders agree.
         */
        fun parseGroupReaction(raw: String, transportSenderKey: String = ""): ParsedGroupReaction? {
            if (!raw.startsWith(REACTION_PREFIX)) return null
            return try {
                val json = JSONObject(raw.removePrefix(REACTION_PREFIX))
                val messageId = json.optString("messageId", "")
                val emoji = (json.opt("emoji") as? JSONObject)
                    ?.optString("rawValue", "")
                    ?: json.optString("emoji", "")
                if (messageId.isEmpty() || emoji.isEmpty()) return null
                // __GROUP_E2E_V2_2026_09_23__ GROUP_E2E_V2_SPEC §2.3: the reactor is the
                // AUTHENTICATED transport sender whenever one is known; the JSON field is only a
                // fallback (legacy paths that carry no sender). Otherwise any member could
                // react — or remove a reaction — in someone else's name.
                val sender = transportSenderKey.takeIf { it.isNotEmpty() }
                    ?: json.optString("senderPublicKey", "")
                // iOS emits `action: "add" | "remove"`; the lenient tier also accepts `isAdding`.
                val isRemoval = when {
                    json.has("action") -> json.optString("action", "add") == "remove"
                    json.has("isAdding") -> !json.optBoolean("isAdding", true)
                    else -> false
                }
                ParsedGroupReaction(messageId, emoji, sender, isRemoval)
            } catch (_: Exception) { null }
        }

        /**
         * Apply a reaction to [messages]. Returns the new list, or **null** when the
         * target id is not present (so callers can skip the state write entirely).
         */
        fun applyReactionToMessages(
            messages: List<GroupMessage>,
            messageId: String,
            senderPublicKey: String,
            emoji: String,
            isRemoval: Boolean
        ): List<GroupMessage>? {
            val index = indexOfMessageId(messages, messageId)
            if (index < 0) return null
            val target = messages[index]
            val newReactions = target.reactions.toMutableMap()
            if (isRemoval) newReactions.remove(senderPublicKey)
            else newReactions[senderPublicKey] = emoji
            return messages.toMutableList().also {
                it[index] = target.copy(reactions = newReactions)
            }
        }

        /** Tombstone one message. Returns null when the target id is absent. */
        fun applyDeletionToMessages(
            messages: List<GroupMessage>,
            messageId: String,
            /** Remote author of the delete; null for our own local delete. See [isAuthorOf]. */
            actor: String? = null,
            /** __GROUP_COMMENTS_2026_11_21__ §2.12.4 rule D: target is a comment AND actor is an admin. */
            actorIsAdminOnComment: Boolean = false
        ): List<GroupMessage>? {
            val index = indexOfMessageId(messages, messageId)
            if (index < 0) return null
            if (actor != null && !GroupComments.mayDelete(isAuthorOf(messages[index], actor), actorIsAdminOnComment, actorIsAdminOnComment)) return null
            // Already tombstoned AND already emptied ⇒ nothing to do. A delete now arrives
            // twice on a peer that understands both broadcast legs (the legacy
            // `📢GROUP_UPDATE📢` and the `.messageDeleted` system message), and without
            // this the second leg rewrites state and re-saves to disk on every delete.
            // The `content` half of the test is deliberate: a row restored from a database
            // written before `isDeleted` was persisted can come back flagged but not
            // emptied, and that one still needs clearing.
            if (messages[index].isDeleted && messages[index].content.isEmpty()) return null
            return messages.toMutableList().also {
                it[index] = it[index].copy(isDeleted = true, content = "")
            }
        }

        /**
         * 🔒 __GROUP_EDIT_DELETE_AUTHOR_2026_11_20__ A remote edit / delete-for-everyone
         * applies only to a message the ACTOR wrote. Group message ids are visible to every
         * member, so without this any member could rewrite another member's message (shown
         * as "edited" under the victim's name) or retract it. The actor is the authenticated
         * sender of the command; the target's author is what we stored at receipt.
         */
        fun isAuthorOf(target: GroupMessage, actor: String): Boolean =
            GroupUpdateAuthorizer.canonicalIdentity(target.senderPublicKey) ==
                GroupUpdateAuthorizer.canonicalIdentity(actor)

        /** Mark every id in [ids] read. Returns null when nothing changed. */
        fun applyReadReceiptToMessages(
            messages: List<GroupMessage>,
            ids: Set<String>
        ): List<GroupMessage>? {
            val out = messages.toMutableList()
            var changed = false
            for (i in out.indices) {
                if (idInSet(out[i].id, ids) && !out[i].isRead) {
                    out[i] = out[i].copy(isRead = true)
                    changed = true
                }
            }
            return if (changed) out else null
        }

        // =====================================================================
        // GROUP MESSAGE EDIT  (iOS `.messageEdited`)
        // =====================================================================

        /** iOS uses the literal sender `"SYSTEM"` for system messages (`GroupMessaging.swift:1334`). */
        const val SYSTEM_SENDER = "SYSTEM"

        /** `GroupSystemMessageType.messageEdited` raw value (`GroupMessaging.swift:3923`). */
        const val SYSTEM_TYPE_MESSAGE_EDITED = "message_edited"

        /** `GroupSystemMessageType.messageDeleted` raw value (`GroupMessaging.swift:3922`). */
        const val SYSTEM_TYPE_MESSAGE_DELETED = "message_deleted"

        /**
         * How long after sending a message it may still be edited.
         *
         * iOS: `guard Date().timeIntervalSince(messages[index].timestamp) <= 600`
         * (`GroupMessaging.swift:1307`) — 600 seconds. The 1:1 surface uses the same
         * number (`ChatScreen.kt` `canEdit`), so all three agree.
         */
        const val GROUP_EDIT_WINDOW_MS = 600_000L

        /**
         * May [message] be edited by [myPublicKey] right now?
         *
         * Mirrors iOS's two guards in `editGroupMessage` (`GroupMessaging.swift:1303-1310`)
         * — own message, inside the 10-minute window — plus the two the overlay applies
         * before it even offers the row (`GroupViews.swift:4390`): text only, and not a
         * tombstone. Media is excluded because the edit envelope carries a `String`
         * `editedContent` and nothing else; "editing" a photo would silently drop the
         * photo on the peer.
         *
         * Pure, so the predicate the menu uses and the predicate the sender enforces are
         * the same function and cannot drift.
         */
        fun canEditGroupMessage(
            message: GroupMessage,
            myPublicKey: String,
            nowMillis: Long
        ): Boolean =
            myPublicKey.isNotEmpty() &&
                message.senderPublicKey == myPublicKey &&
                message.mediaType == null &&
                !message.isDeleted &&
                // `0..` and not `0 until`: iOS's guard is `<= 600`, INCLUSIVE
                // (`GroupMessaging.swift:1307`). The lower bound rejects a message
                // stamped in the future — a clock correction must not reopen the window.
                (nowMillis - message.timestamp) in 0L..GROUP_EDIT_WINDOW_MS

        // =====================================================================
        // GROUP LIVE LOCATION  (__GROUP_LIVE_LOCATION_2026_09_27__)
        // =====================================================================
        //
        // What shipped iPhones do, read off `GroupMessaging.swift`:
        //  • SENDING: `sendSingleMessage` (:2605-2616) applies an `isUpdate` location to
        //    its OWN bubble and `return`s before the relay — an iPhone never puts a
        //    group live-location update or stop on the wire. Receivers only ever get
        //    the initial share (`isLive`, `expiresAt`).
        //  • RECEIVING: `receiveGroupMessage` has no location branch, so every
        //    `📍LOCATION📍` that arrives as a NEW message is a new bubble + banner. It
        //    DOES apply a `.messageEdited` system message (:3336-3350) by overwriting
        //    `plaintextContent` of the target — and the group location card is drawn
        //    from `plaintextContent` (`GroupViews.swift:2629`). No bubble, no banner,
        //    no 10-minute window on receive.
        //
        // So the least-bad wire for Android is: the initial share as an ordinary
        // message, then every update / the stop as an EDIT of that message. A shipped
        // iPhone moves the pin in place and shows "ended" on stop (cost: iOS may draw
        // its "Edited" marker under the card). Android receivers apply the same edit.
        // Older Android builds that still post each tick as a new message are merged
        // on receive by [liveLocationMergeTarget].

        /**
         * The index of the message a live-location update/stop from [senderKey] should
         * replace, or -1. Only an `isUpdate` payload with a `sessionId` merges; the
         * initial share (or a one-shot location) is always a new bubble.
         */
        fun liveLocationMergeTarget(
            messages: List<GroupMessage>,
            senderKey: String,
            content: String
        ): Int {
            val incoming = com.oshi.messenger.network.location.LocationMessage.parseOrNull(content)
                ?: return -1
            val sessionId = incoming.sessionId?.takeIf { it.isNotEmpty() } ?: return -1
            if (!incoming.isUpdate) return -1
            return messages.indexOfLast { m ->
                !m.isDeleted &&
                    m.senderPublicKey.equals(senderKey, ignoreCase = true) &&
                    com.oshi.messenger.network.location.LocationMessage
                        .parseOrNull(m.displayContent)?.sessionId == sessionId
            }
        }

        /** A decoded `.messageEdited` system message. */
        data class ParsedGroupEdit(
            val editedMessageId: String,
            val newContent: String,
            val editedAt: Long
        )

        /**
         * Decode an inbound `.messageEdited` system message.
         *
         * The envelope is an ordinary group-message JSON whose `systemMessageType` is
         * `"message_edited"` and whose `systemMessageData` carries `editedMessageId` +
         * `editedContent` (`GroupMessaging.swift:1330-1342`). iOS stamps the target's
         * `editedAt` with the SYSTEM MESSAGE's own `timestamp` (`:3299`), so that is what
         * [editedAt] resolves to; [fallbackEditedAt] is used when the envelope carries no
         * usable timestamp.
         *
         * Returns null for anything that is not a complete edit command, so an envelope
         * missing either field is ignored rather than blanking a message.
         */
        fun parseGroupEditSystemMessage(
            json: JSONObject,
            fallbackEditedAt: Long
        ): ParsedGroupEdit? {
            if (json.optString("systemMessageType", "") != SYSTEM_TYPE_MESSAGE_EDITED) return null
            val data = json.optJSONObject("systemMessageData") ?: return null
            val targetId = data.optString("editedMessageId", "")
            if (targetId.isEmpty()) return null
            // `has` rather than `optString(…).isEmpty()`: an edit to the empty string is a
            // legal edit, and iOS's `let newContent = …?.editedContent` accepts "".
            if (!data.has("editedContent") || data.isNull("editedContent")) return null
            val newContent = data.optString("editedContent", "")
            return ParsedGroupEdit(targetId, newContent, editedAtFromEnvelope(json, fallbackEditedAt))
        }

        /**
         * The edit time an envelope implies, in Unix millis.
         *
         * Group-message timestamps cross the wire as an **Apple-epoch Double** (seconds
         * since 2001-01-01) because iOS decodes a `GroupMessage` with a bare
         * `JSONDecoder()`, i.e. `.deferredToDate` — see `sendMessageToMembers`. An
         * Android peer's own envelope uses the same encoding. Unix millis are still
         * accepted by magnitude for any legacy sender, exactly as
         * `handleIncomingGroupMessage` does for ordinary messages.
         *
         * ⚠️ Not ISO-8601. That rule belongs to the group **definition** payload
         * (`buildIosMessageGroupJson`), which iOS decodes with `.iso8601`.
         */
        private fun editedAtFromEnvelope(json: JSONObject, fallback: Long): Long {
            val raw = json.optDouble("timestamp", 0.0)
            if (raw <= 0.0 || raw.isNaN()) return fallback
            return if (raw >= 1_000_000_000_000.0) raw.toLong()
            else ((raw + APPLE_EPOCH_OFFSET) * 1000.0).toLong()
        }

        /**
         * Apply an edit to [messages]. Returns the new list, or **null** when the target
         * id is absent — the same contract as [applyReactionToMessages], so the caller
         * skips the state write and the disk write on a miss.
         *
         * `content` is left untouched: iOS keeps the original in `encryptedContent` and
         * only ever overwrites `plaintextContent`/`editedContent`
         * (`GroupMessaging.swift:1312-1315`). `GroupMessage.displayContent` is the read
         * path that prefers the edit.
         *
         * Matching is case-insensitive: an iPhone targets `messageId.uuidString`, which is
         * UPPERCASE, while Android mints lowercase ids.
         */
        fun applyEditToMessages(
            messages: List<GroupMessage>,
            messageId: String,
            newContent: String,
            editedAt: Long,
            /** Remote author of the edit; null for our own local edit. See [isAuthorOf]. */
            actor: String? = null
        ): List<GroupMessage>? {
            val index = indexOfMessageId(messages, messageId)
            if (index < 0) return null
            val target = messages[index]
            if (actor != null && !isAuthorOf(target, actor)) return null
            // A tombstoned message cannot be resurrected by an edit — iOS removes the row
            // outright on delete-for-everyone, so an edit arriving after one has nothing
            // to land on there either.
            if (target.isDeleted) return null
            if (target.editedContent == newContent && target.editedAt == editedAt) return null
            return messages.toMutableList().also {
                it[index] = target.copy(editedContent = newContent, editedAt = editedAt)
            }
        }

        /**
         * Build the `.messageEdited` system message an iPhone actually applies.
         *
         * This is an ordinary **group-message** envelope, not a `📢GROUP_UPDATE📢`:
         * iOS's Android-minimal-JSON switch (`GroupMessaging.swift:1070-1155`) has cases
         * only for `member_added`, `member_sync_request`, `member_removed`,
         * `group_renamed` and `sync_request` — anything else falls through to
         * `default:` (`:1153`) and is dropped. The edit is applied instead by
         * `receiveGroupMessage`'s `systemMessageType == .messageEdited` branch
         * (`:3292-3307`), which is reached only through a payload that survives the
         * **strict** `JSONDecoder().decode(GroupMessage.self)` — `parseCrossPlatformGroupMessage`
         * path 1 (`:716`) and the IPFS envelope path (`MessageManager.swift:3712`).
         * Its lenient path 2 (`:721-787`) hand-maps fields and does NOT carry
         * `systemMessageType`/`systemMessageData` at all, so a payload that only *nearly*
         * decodes silently degrades into an empty visible bubble.
         *
         * Therefore every field `GroupMessage` declares non-optional must be present and
         * of the right JSON type (`GroupMessaging.swift:341-384`):
         *  - `id` / `groupId`: UUID strings
         *  - `senderPublicKey`: String — `"SYSTEM"`, which is also what iOS's stale-identity
         *    gate and its own sender both special-case (`:3143`, `:1333`)
         *  - `encryptedContent`: base64 `Data` — iOS sends `Data()`, i.e. `""`
         *  - `timestamp`: **Apple-epoch Double** (bare `JSONDecoder` ⇒ `.deferredToDate`)
         *  - `isRead`: Bool, `messageChainIndex`: Int
         * and `systemMessageData` must carry `actorPublicKey`, the one non-optional field
         * of `GroupSystemMessageData` (`:3962-3968`).
         *
         * `editedMessageId` is uppercased through [canonicalGroupId] because iOS writes
         * `messageId.uuidString`; the value only ever reaches
         * `UUID(uuidString:)`, which is case-insensitive, so this is convention rather
         * than correctness — the same convention every other id on this wire follows.
         *
         * No unknown enum values: `systemMessageType` is exactly the
         * `GroupSystemMessageType` raw value `"message_edited"`, and no `mediaType` key is
         * emitted at all (an absent optional decodes as nil; a wrong one throws).
         */
        fun buildGroupEditSystemMessageJson(
            groupId: String,
            systemMessageId: String,
            editedMessageId: String,
            newContent: String,
            actorPublicKey: String,
            timestampMillis: Long
        ): String = JSONObject().apply {
            put("id", canonicalGroupId(systemMessageId))
            put("groupId", groupId)
            put("senderPublicKey", SYSTEM_SENDER)
            put("encryptedContent", "")          // iOS: `encryptedContent: Data()`
            put("timestamp", appleEpoch(timestampMillis))
            put("isRead", true)                  // iOS: `isRead: true` (:1336)
            put("messageChainIndex", 0)
            put("systemMessageType", SYSTEM_TYPE_MESSAGE_EDITED)
            put("systemMessageData", JSONObject().apply {
                put("actorPublicKey", actorPublicKey)
                put("editedMessageId", canonicalGroupId(editedMessageId))
                put("editedContent", newContent)
            })
        }.toString()

        /**
         * Decode an inbound `.messageDeleted` system message — the delete-for-everyone
         * an iPhone sends (`GroupMessaging.swift:2407-2427`, applied at `:3273-3290`).
         *
         * Returns the id of the message being retracted, or null for anything that is not
         * a complete delete command. `systemMessageData.deletedMessageId` is the only
         * field that carries meaning here; unlike an edit there is no content to carry.
         */
        fun parseGroupDeleteSystemMessage(json: JSONObject): String? {
            if (json.optString("systemMessageType", "") != SYSTEM_TYPE_MESSAGE_DELETED) return null
            val data = json.optJSONObject("systemMessageData") ?: return null
            val targetId = data.optString("deletedMessageId", "")
            return targetId.ifEmpty { null }
        }

        /**
         * Build the `.messageDeleted` system message an iPhone actually applies.
         *
         * The sibling of [buildGroupEditSystemMessageJson], and it exists for the same
         * reason: before this, Android broadcast a delete as
         * `{"type":"message_deleted"}` inside a `📢GROUP_UPDATE📢`, and iOS's
         * Android-minimal-JSON switch has no such case — it fell to `default:`
         * (`GroupMessaging.swift:1153`) and was dropped, so **no Android delete has ever
         * reached an iPhone**. iOS's own delete is a system message, so this is the shape
         * that works.
         *
         * Every field `GroupMessage` declares non-optional is present with the same JSON
         * types the edit envelope uses — see [buildGroupEditSystemMessageJson] for why
         * each one matters. The only differences are `systemMessageType` and the
         * `systemMessageData` key: iOS reads `deletedMessageId` here (`:3275`), never
         * `editedMessageId`.
         */
        fun buildGroupDeleteSystemMessageJson(
            groupId: String,
            systemMessageId: String,
            deletedMessageId: String,
            actorPublicKey: String,
            timestampMillis: Long
        ): String = JSONObject().apply {
            put("id", canonicalGroupId(systemMessageId))
            put("groupId", groupId)
            put("senderPublicKey", SYSTEM_SENDER)
            put("encryptedContent", "")          // iOS: `encryptedContent: Data()`
            put("timestamp", appleEpoch(timestampMillis))
            put("isRead", true)                  // iOS: `isRead: true` (:2415)
            put("messageChainIndex", 0)
            put("systemMessageType", SYSTEM_TYPE_MESSAGE_DELETED)
            put("systemMessageData", JSONObject().apply {
                put("actorPublicKey", actorPublicKey)
                put("deletedMessageId", canonicalGroupId(deletedMessageId))
            })
        }.toString()

        // =====================================================================
        // NOTIFICATION GATE
        // =====================================================================

        /**
         * Should the **poll**-path group banner be raised?
         *
         * `onScreen` is supplied by [ActiveConversation.isGroupActive] — the very
         * registry the push path already consults (`FCMService.kt:650-658`) and the 1:1
         * poll path uses (`MessageRepository.showMessageNotification`). It is a parameter
         * only so this decision is exercisable from a JVM unit test; there is exactly one
         * registry and this function does not re-implement it.
         *
         * Before this gate existed, a message arriving for the group the user was reading
         * raised a heads-up notification the user then had to dismiss — with the message
         * already on screen and already being marked read.
         */
        fun shouldRaiseGroupBanner(isMuted: Boolean, onScreen: Boolean, mentionsMe: Boolean = false): Boolean =
            // __MENTIONS_2026_09_23__ an admitted `@you` breaks through mute, never through "on screen".
            (!isMuted || mentionsMe) && !onScreen

        /**
         * __GROUP_REACTION_NOTIFICATION_2026_11_16__ Does an inbound group reaction
         * deserve a banner? Only when someone ELSE adds a reaction to a message I
         * sent, and only when it is new: the same reaction arriving again (a resync,
         * a second transport) does not ring twice. Mute / on-screen / blocked are
         * checked by the caller, exactly as for a group message.
         */
        fun shouldNotifyGroupReaction(
            myKey: String,
            reactorKey: String,
            target: GroupMessage?,
            emoji: String,
            isRemoval: Boolean,
            previousEmoji: String?,
        ): Boolean {
            if (isRemoval || target == null || emoji.isEmpty() || myKey.isEmpty()) return false
            if (sameMentionKey(reactorKey, myKey)) return false
            val targetIsMine = target.isFromMe || sameMentionKey(target.senderPublicKey, myKey)
            if (!targetIsMine) return false
            return previousEmoji != emoji
        }

        /** __MENTIONS_2026_09_23__ identity comparison for mention keys (base64 / base64url spellings). */
        fun sameMentionKey(a: String, b: String): Boolean =
            GroupUpdateAuthorizer.canonicalIdentity(a) == GroupUpdateAuthorizer.canonicalIdentity(b)

        // =====================================================================
        // MEDIA ROUTING  (M-3)
        // =====================================================================

        /**
         * The persistence route for one attachment.
         *
         * The whole `group_messages_data` document is read into memory and re-serialised
         * on every debounced save, so base64 of a multi-megabyte video in there is what
         * made group saves janky — hence a cap far below the Room one.
         */
        fun groupMediaSink(sizeBytes: Int?, inlineCapBytes: Int): GroupMediaSink = when {
            sizeBytes == null -> GroupMediaSink.NONE
            sizeBytes > inlineCapBytes -> GroupMediaSink.FILE
            else -> GroupMediaSink.INLINE
        }

        /**
         * May this blob ride on a Room row?
         *
         * Android's CursorWindow is 2 MB; a larger blob on the row makes every later
         * `SELECT *` that touches it throw `SQLiteBlobTooBigException` — not at write
         * time, but at read time, in whatever screen happens to run the query. Same cap
         * and same reasoning as `MessageRepository.MAX_INLINE_MEDIA_BYTES`; the bytes
         * live in the file store and the row keeps only the id.
         */
        fun fitsOnRoomRow(sizeBytes: Int?, capBytes: Int): Boolean =
            sizeBytes == null || sizeBytes <= capBytes

        /**
         * The root the group attachment store is built on — **not** `filesDir` itself.
         *
         * `MessageRepository.purgeOrphanAttachments` (`:824`, called at `:977`)
         * enumerates `filesDir/media` and deletes every blob whose id is not a row in the
         * 1:1 MESSAGES table. Group ids are not in that table, so pointing this at
         * `filesDir` would hand the 1:1 sweep a licence to delete every group photo on
         * the device. Same store class, same lifecycle, disjoint namespaces.
         *
         * A function rather than a literal at the construction site so the invariant is
         * exercised by `GroupMediaRoutingTest` against a real temp directory.
         */
        fun groupMediaRoot(filesDir: File): File = File(filesDir, "groups")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // __GROUP_RESEND_2026_11_25__ declared ABOVE init (InitOrderTest): init starts the loop.
    private val pendingDelivery: GroupPendingDeliveryStore by lazy {
        GroupPendingDeliveryStore(context.filesDir, canon = GroupUpdateAuthorizer::canonicalIdentity)
    }

    private val _pendingDeliveries = MutableStateFlow<Map<String, GroupPendingDeliveryStore.Entry>>(emptyMap())
    /** msgId → members still owed that message. Absent = everybody got it (normal Sent). */
    val pendingDeliveries: StateFlow<Map<String, GroupPendingDeliveryStore.Entry>> = _pendingDeliveries.asStateFlow()

    private fun publishPendingDelivery() {
        _pendingDeliveries.value = try { pendingDelivery.all() } catch (_: Throwable) { emptyMap() }
    }

    private val resendMutex = kotlinx.coroutines.sync.Mutex()
    /** A large group's first send can outlast the first retry slot: never run two at once. */
    private val sendsInFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val resendKick = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)


    // State
    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    // __GROUP_SETTINGS_2026_11_20__ declared above `init` (InitOrderTest).
    private val extrasLock = Any()
    @Volatile private var extrasCache: MutableMap<String, JSONObject>? = null
    /**
     * __CHANNEL_WEB_2026_11_26__ Public channel web preview (PUBLIC_CHANNEL_WEB_SPEC.md §9).
     * Set by `ChannelWebPublisher.start()`; every call is fire-and-forget (it only enqueues),
     * so the E2E send never waits on, nor fails because of, the web copy.
     */
    interface ChannelWebHooks {
        fun onOwnPost(groupId: String, message: GroupMessage)
        fun onOwnPostEdited(groupId: String, messageId: String, newContent: String)
        fun onPostDeletedForEveryone(groupId: String, messageId: String)
    }
    @Volatile var channelWebHooks: ChannelWebHooks? = null

    private val _groupSettings = MutableStateFlow<Map<String, com.oshi.messenger.data.model.GroupSettings>>(emptyMap())
    /** UPPER-case group id → settings. */
    val groupSettings: StateFlow<Map<String, com.oshi.messenger.data.model.GroupSettings>> = _groupSettings.asStateFlow()
    private val _autoTranslate = MutableStateFlow<Set<String>>(emptySet())
    val autoTranslateGroups: StateFlow<Set<String>> = _autoTranslate.asStateFlow()
    private val _adminLog = MutableStateFlow<Map<String, List<GroupAdminLog.Event>>>(emptyMap())
    @Volatile private var adminLogLoaded = false
    private val joinAccepts = java.util.concurrent.ConcurrentHashMap<String, MutableList<Long>>()

    // __GROUP_COMMENTS_2026_11_21__ (spec §2.12) lower-case comment id → post id. A LOCAL
    // index, persisted beside each row in the sealed MESSAGES document (`commentOn`), never a
    // Room column (GroupMessage is an @Entity: a new column needs a migration).
    private val _commentLinks = MutableStateFlow<Map<String, String>>(emptyMap())
    val commentLinks: StateFlow<Map<String, String>> = _commentLinks.asStateFlow()
    private val commentOrphans = GroupComments.OrphanBuffer()
    private val commentReceiveRate = GroupComments.RateWindow()
    private val commentSendRate = GroupComments.RateWindow()

    private val _groupMessages = MutableStateFlow<Map<String, List<GroupMessage>>>(emptyMap())
    val groupMessages: StateFlow<Map<String, List<GroupMessage>>> = _groupMessages.asStateFlow()

    private val _groupConversations = MutableStateFlow<List<GroupConversation>>(emptyList())
    val groupConversations: StateFlow<List<GroupConversation>> = _groupConversations.asStateFlow()

    // Discovered groups from cross-platform mesh (iOS groups)
    data class DiscoveredGroup(
        val groupId: String,
        val groupName: String,
        val adminPublicKey: String,
        val memberCount: Int,
        val avatar: String?,
        val timestamp: Long
    )
    private val _discoveredGroups = MutableStateFlow<List<DiscoveredGroup>>(emptyList())
    val discoveredGroups: StateFlow<List<DiscoveredGroup>> = _discoveredGroups.asStateFlow()

    /**
     * Drop everything discovered so far, so a retry on the Discover screen starts
     * from a clean slate instead of showing groups whose broadcaster has left.
     * Ads are re-received within one broadcast interval if the group is still up.
     */
    fun clearDiscoveredGroups() {
        _discoveredGroups.value = emptyList()
    }

    // Local storage
    // __LEAK_FIX_2026_09_25__ was `getSharedPreferences("groups")`: the last 300 messages of
    // every group and every group key in a CLEARTEXT XML (audit finding A). Now sealed files
    // under the Keystore-wrapped at-rest key; the first read migrates and wipes the XML
    // (GroupLocalStore). Same JSON documents, so the loaders below are unchanged.
    private val localStore = GroupLocalStore.get(context)

    /**
     * __LEAK_FIX_2026_09_25__ Documents whose sealed file exists but could not be opened at
     * load time (Keystore not ready). While a document is here, its in-memory copy is NOT the
     * truth, so it is never saved over the file; [retryBlockedLoads] reloads it later.
     */
    private val blockedDocs = java.util.concurrent.ConcurrentHashMap.newKeySet<GroupLocalStore.Doc>()

    /** One writer thread: every save is a whole-document snapshot, written in call order. */
    private val persistExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "oshi-group-store").apply { isDaemon = true }
    }

    /** Queue [value] for [doc] (snapshot taken by the caller, written off the caller's thread). */
    private fun persist(doc: GroupLocalStore.Doc, value: String) {
        if (doc in blockedDocs) {
            Log.w(TAG, "🔒 ${doc.name} not saved: the sealed copy is not readable yet")
            return
        }
        persistExecutor.execute {
            try { localStore.write(doc, value) } catch (e: Exception) {
                Log.e(TAG, "Failed to persist ${doc.name}: ${e.message}")
            }
        }
    }

    /** Read [doc]; a present-but-unreadable sealed file blocks saves of it (see [blockedDocs]). */
    private fun readDoc(doc: GroupLocalStore.Doc): String? {
        val value = localStore.read(doc)
        if (value == null && localStore.isUnreadable(doc)) {
            if (blockedDocs.add(doc)) {
                Log.e(TAG, "🔒 ${doc.name} is sealed but unreadable (at-rest key unavailable) — retrying")
                retryBlockedLoads()
            }
        } else {
            blockedDocs.remove(doc)
        }
        return value
    }

    private fun retryBlockedLoads() {
        scope.launch {
            var attempt = 0
            while (blockedDocs.isNotEmpty() && attempt < 30) {
                delay(com.oshi.messenger.data.local.atrest.AtRestKey.RETRY_AFTER_MS + 1_000L)
                attempt++
                if (GroupLocalStore.Doc.GROUPS in blockedDocs || GroupLocalStore.Doc.MESSAGES in blockedDocs) {
                    // loadGroups() also reloads the messages. Anything that arrived in the
                    // meantime lives only in memory and is merged back in (never dropped).
                    val arrivedGroups = _groups.value
                    val arrivedMessages = _groupMessages.value
                    val messagesWereBlocked = GroupLocalStore.Doc.MESSAGES in blockedDocs
                    loadGroups()
                    if (messagesWereBlocked) loadMessages()
                    if (GroupLocalStore.Doc.GROUPS !in blockedDocs) {
                        val known = _groups.value.map { it.id.lowercase(Locale.US) }.toSet()
                        val extra = arrivedGroups.filter { it.id.lowercase(Locale.US) !in known }
                        if (extra.isNotEmpty()) { _groups.value = _groups.value + extra; saveGroups() }
                    }
                    if (GroupLocalStore.Doc.MESSAGES !in blockedDocs && arrivedMessages.isNotEmpty()) {
                        val merged = _groupMessages.value.toMutableMap()
                        for ((gid, list) in arrivedMessages) {
                            val have = merged[gid].orEmpty()
                            val ids = have.map { it.id.lowercase(Locale.US) }.toSet()
                            val add = list.filter { it.id.lowercase(Locale.US) !in ids }
                            if (add.isNotEmpty()) merged[gid] = (have + add).sortedBy { it.timestamp }
                        }
                        _groupMessages.value = merged
                        saveMessages()
                    }
                    updateGroupConversations()
                }
                if (GroupLocalStore.Doc.LEFT_IDS in blockedDocs) {
                    val pending = tombstoneCache
                    tombstoneCache = null
                    val loaded = loadTombstonedGroupIds()
                    if (pending != null && GroupLocalStore.Doc.LEFT_IDS !in blockedDocs && !loaded.containsAll(pending)) {
                        tombstoneCache = (loaded + pending).toMutableSet()
                        persist(GroupLocalStore.Doc.LEFT_IDS, JSONArray(tombstoneCache!!.toList()).toString())
                    }
                }
                if (GroupLocalStore.Doc.DEVSYNC_LEFT in blockedDocs) { devSyncLeftCache = null; devSyncLeftMap() }
                if (GroupLocalStore.Doc.MENTIONS in blockedDocs) {
                    val raw = readDoc(GroupLocalStore.Doc.MENTIONS)
                    if (raw != null && GroupLocalStore.Doc.MENTIONS !in blockedDocs) {
                        // Disk entries the in-memory index does not have yet are added back.
                        runCatching {
                            val o = JSONObject(raw)
                            for (k in o.keys()) if (mentionIndex.get(k).isEmpty()) {
                                mentionIndex.put(k, com.oshi.messenger.util.MentionWire.parse(o.opt(k)))
                            }
                        }
                        persist(GroupLocalStore.Doc.MENTIONS, mentionIndex.serialize())
                        _mentionsVersion.value = _mentionsVersion.value + 1
                    }
                }
            }
        }
    }

    // =========================================================================
    // GROUP MEDIA — file-backed, on the SAME store the 1:1 surface uses (M-3)
    // =========================================================================

    /**
     * The file store for group attachments.
     *
     * 🔧 M-3. Groups used to run a **second, parallel** media store: a private
     * `filesDir/group_media/<messageId>.media` written by an inline `saveMediaToFile`,
     * with no atomic write, no LRU, no cost budget and no orphan sweep, while 1:1 media
     * went through [MediaAttachmentStore] — a faithful port of iOS's
     * `MediaManager`/`FileStorage`/`MediaAttachmentCache` lifecycle. Two stores meant two
     * sets of bugs; the group one had a torn-write window (`file.writeBytes` straight onto
     * the destination) and leaked every blob of every deleted group forever.
     *
     * iOS has exactly one attachment lifecycle for both surfaces —
     * `Documents/Attachments/<id>.dat` (`MediaManager.swift:762-771`) — and this is now
     * the same class, with the same atomic temp-then-rename write, the same
     * `countLimit = 60` / `totalCostLimit = 96 MB` LRU (`MediaAttachmentCache.swift:37-38`)
     * and the same [MediaAttachmentStore.purgeOrphans] sweep.
     *
     * ## Why a separate ROOT rather than a shared directory
     *
     * `MessageRepository.purgeOrphanAttachments` (`:824`, called at `:977`) enumerates
     * `filesDir/media` and deletes every blob whose id is not a row in the **1:1 messages
     * table**. Group ids are not in that table. Sharing one directory would therefore hand
     * the 1:1 sweep a licence to delete every group photo on the device — the precise
     * shape of bug this pass exists to remove. `MediaAttachmentStore` takes a plain root,
     * so groups get `filesDir/groups/media` and the two sweeps cannot reach each other's
     * files. Same class, same lifecycle, disjoint namespaces.
     */
    private val mediaStore = com.oshi.messenger.service.media.MediaAttachmentStore(
        groupMediaRoot(context.filesDir)
    )

    /**
     * Above this, a blob goes to the file store instead of into the SharedPreferences
     * JSON. Unchanged from the pre-M-3 threshold: the whole `group_messages_data` document
     * is read into memory and re-serialised on every save, so base64 of a multi-megabyte
     * video in there is what made saves janky.
     */
    private val maxInlineGroupMediaBytes = 100_000

    /**
     * Read a blob from the LEGACY directory, `filesDir/group_media/<id>.media`.
     *
     * Rows written before M-3 carry that absolute path as `mediaPath`. Nothing writes
     * there any more, but every such row must keep rendering, so the loader still resolves
     * a `mediaPath` when [mediaStore] has nothing for that id; the next save lifts the
     * blob into the new store. Dropping this leg would silently empty every group photo
     * bubble that predates this change.
     */
    private fun loadMediaFromFile(path: String): ByteArray? = try {
        File(path).takeIf { it.exists() }?.readBytes()
    } catch (_: Exception) { null }

    // =========================================================================
    // ROOM MIRROR — the single writer of `groups` / `group_messages`
    // =========================================================================

    /**
     * The Room tables `groups` and `group_messages`, kept in step with this manager's
     * state.
     *
     * ## Why this exists (the `GroupRepository` decision)
     *
     * `data/repository/GroupRepository.kt` was a **second, half-finished group
     * implementation**: its `sendGroupMessage` wrote these tables and then hand-rolled a
     * `{"groupId":…,"messageId":…}` envelope no platform parses, its `createGroup` minted
     * a group nobody was told about (`notifyGroupCreated` was a `TODO` that only logged),
     * and by this pass it had **zero consumers** anywhere in the tree — the media gallery,
     * its last one, was repointed at `GroupManager.groupMessages` on 2026-08-24. So the
     * class was deleted.
     *
     * The **tables** could not be deleted with it, because they have four live READERS
     * and only that dead class ever wrote them:
     *
     *  | reader | what it shows | state before this change |
     *  |---|---|---|
     *  | `network/web/WebSessionManager.kt:533,641` | the web bridge's group list | always EMPTY |
     *  | `network/web/WebSessionManager.kt:644,683` | a group's message history in the browser | always EMPTY |
     *  | `data/repository/MessageRepository.kt:1157` | `onRequestMedia` for a group attachment | always `not_found` |
     *  | `ui/viewmodel/GroupChatViewModel.kt:450` | group FTS search | always fell back to in-memory |
     *
     * All four are in files held by other teams, so repointing them was not an option —
     * and would have been the wrong fix anyway: a table with readers and no writer is the
     * defect, not the table. Giving it its one real writer fixes all four at once and
     * changes no schema, so there is **no destructive migration** in this decision.
     *
     * SharedPreferences stays the source of truth the UI renders from; Room is a durable
     * mirror and search index. That is deliberate — flipping the source of truth is a far
     * larger change than this pass, and every existing install's history lives in prefs.
     */
    private val groupDao: com.oshi.messenger.data.local.GroupDao by lazy {
        // The very same singleton Hilt hands everyone else: `AppModule.provideAppDatabase`
        // is `AppDatabase.getInstance(context)`. Resolved lazily so constructing
        // `GroupManager` never opens the database on whatever thread built it.
        com.oshi.messenger.data.local.AppDatabase.getInstance(context).groupDao()
    }

    /**
     * A blob this large cannot ride on a Room row: Android's CursorWindow is 2 MB and
     * throws `SQLiteBlobTooBigException` on any later `SELECT *` that touches the row.
     * Same cap and same reasoning as `MessageRepository.MAX_INLINE_MEDIA_BYTES`; the bytes
     * themselves are in [mediaStore], keyed by the message id.
     */
    private val maxRoomBlobBytes = 1_500_000

    /** Snapshot of what has already been written to Room, so each tick upserts only deltas. */
    private var mirroredMessages: Map<String, GroupMessage> = emptyMap()
    private var mirroredGroups: Map<String, Group> = emptyMap()

    private fun startRoomMirror() {
        scope.launch {
            _groups.collect { groups ->
                try {
                    val byId = groups.associateBy { it.id }
                    for ((id, g) in byId) {
                        if (mirroredGroups[id] == g) continue
                        groupDao.insertGroup(
                            g.copy(
                                groupImage = g.groupImage?.takeIf { fitsOnRoomRow(it.size, maxRoomBlobBytes) },
                                groupWallpaperData = g.groupWallpaperData?.takeIf { fitsOnRoomRow(it.size, maxRoomBlobBytes) }
                            )
                        )
                    }
                    for (goneId in mirroredGroups.keys - byId.keys) {
                        // Rows for a group that left this device: `deleteGroup` already
                        // removes it here, but a leave/remove that lands over the wire
                        // takes this path instead.
                        try { groupDao.deleteGroup(goneId) } catch (_: Exception) {}
                    }
                    mirroredGroups = byId
                } catch (e: Exception) {
                    Log.w(TAG, "Room group mirror failed: ${e.message}")
                }
            }
        }
        scope.launch {
            _groupMessages.collect { all ->
                try {
                    val flat = all.values.flatten()
                    var wrote = 0
                    for (m in flat) {
                        if (mirroredMessages[m.id] == m) continue
                        groupDao.insertGroupMessage(
                            // The row carries the id, never the bytes, for anything the
                            // cursor could not load back.
                            if (fitsOnRoomRow(m.mediaData?.size, maxRoomBlobBytes)) m else m.copy(mediaData = null)
                        )
                        wrote++
                    }
                    if (wrote > 0) Log.d(TAG, "🪞 Mirrored $wrote group message(s) into Room")
                    mirroredMessages = flat.associateBy { it.id }
                } catch (e: Exception) {
                    Log.w(TAG, "Room group-message mirror failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Delete group blobs whose message no longer exists anywhere.
     *
     * iOS sweeps attachments the same way (`PinataCleanupService.swift:23,262,305`), and
     * the 1:1 surface does it in `MessageRepository.purgeOrphanAttachments`. Groups had
     * NO sweep at all: `filesDir/group_media` grew forever, including every blob of every
     * group the user had left.
     *
     * The live set is the union of what is in memory and what Room holds, never memory
     * alone: `saveMessagesNow` keeps only the newest [MAX_MESSAGES_PER_GROUP_PERSIST]
     * messages per group in SharedPreferences, so a memory-only sweep would delete the
     * attachments of every older message the Room mirror still lists.
     */
    private fun purgeOrphanGroupMedia() {
        scope.launch {
            try {
                // Off the startup path, and long after [startRoomMirror]'s first tick has
                // written the rows this sweep asks about. A sweep that ran before the
                // mirror would see an empty table and read every id as orphaned.
                delay(60_000)
                var removed = mediaStore.sweepTempFiles()
                val stored = mediaStore.listStoredIds()
                if (stored.isEmpty()) return@launch
                val live = HashSet<String>()
                _groupMessages.value.values.forEach { list -> list.forEach { live.add(it.id) } }
                for (id in stored) {
                    if (id in live) continue
                    // Ask Room before deleting. A DB error must never read as
                    // "this message is gone" — that is how a sweep eats live data.
                    val row = try { groupDao.getMessageById(id) } catch (e: Exception) {
                        Log.w(TAG, "Group media sweep aborted: ${e.message}")
                        return@launch
                    }
                    if (row == null) { mediaStore.delete(id); removed++ }
                }
                if (removed > 0) Log.d(TAG, "🧹 Purged $removed orphaned group media file(s)")
            } catch (e: Exception) {
                Log.w(TAG, "Group media sweep failed: ${e.message}")
            }
        }
    }

    init {
        // __LEGACY_COMPAT_2026_09_23__ persisted compat flag / peer caps / v2 proofs.
        com.oshi.messenger.network.v2.GroupLegacyCompat.attach(context)
        loadGroups()
        loadMessages()
        // AFTER the two loaders: the mirror's first tick must see real state, and the
        // sweep must never run against an empty in-memory map.
        startRoomMirror()
        purgeOrphanGroupMedia()
        startPendingDeliveryLoop()   // __GROUP_RESEND_2026_11_25__
        setupCrossPlatformGroupDiscovery()
        setupPeerConnectSync()
        // __GROUP_SETTINGS_2026_11_20__ keep public handles alive (60-day server expiry, spec §4.2).
        if (handleClientLazy != null) scope.launch {
            delay(30_000L)
            runCatching { refreshPublicHandlesIfDue() }
        }
    }

    /**
     * Listen for PublicGroupAd broadcasts from iOS peers via CrossPlatformMesh
     */
    private fun setupCrossPlatformGroupDiscovery() {
        crossPlatformMesh.addGroupAdCallback { adJson ->
            try {
                val json = JSONObject(adJson)
                val groupId = json.getString("groupId")
                val groupName = json.getString("groupName")
                val adminPublicKey = json.getString("adminPublicKey")
                val memberCount = json.optInt("memberCount", 1)
                val avatar = json.optString("avatar", null)
                val timestamp = json.optLong("timestamp", System.currentTimeMillis())

                // Skip groups we already have (case-insensitive: iOS UUIDs are uppercase)
                if (_groups.value.any { it.id.equals(groupId, ignoreCase = true) }) return@addGroupAdCallback

                val discovered = DiscoveredGroup(groupId, groupName, adminPublicKey, memberCount, avatar, timestamp)
                val current = _discoveredGroups.value.toMutableList()
                // Update if exists, add if new
                val idx = current.indexOfFirst { it.groupId == groupId }
                if (idx >= 0) {
                    current[idx] = discovered
                } else {
                    current.add(discovered)
                }
                _discoveredGroups.value = current
                Log.d(TAG, "📡 Discovered cross-platform group: $groupName (from ${adminPublicKey.take(8)}...)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse PublicGroupAd: ${e.message}")
            }
        }

        // Start periodic broadcast of our public groups via cross-platform mesh
        scope.launch {
            delay(5_000) // Wait for mesh to initialize
            while (isActive) {
                broadcastPublicGroupsViaMesh()
                // 30 s on screen, matching iOS; ×PollBackoff.BACKGROUND_AUX_MULTIPLIER
                // when backgrounded. Not suspended outright — a peer that walks into
                // range while the app is in someone's pocket is exactly the case this
                // broadcast exists for.
                delay(com.oshi.messenger.service.AppPollState.auxSyncDelayMs(30_000L))
            }
        }
    }

    /**
     * When a new CrossPlatformMesh peer connects, immediately send a sync request to them.
     * This ensures that when Android and iOS come within range, groups sync automatically.
     */
    private fun setupPeerConnectSync() {
        scope.launch {
            var previousPeers = setOf<String>()
            crossPlatformMesh.connectedPeers.collect { peers ->
                val currentKeys = peers.map { it.publicKey }.toSet()
                val newPeerKeys = currentKeys - previousPeers
                previousPeers = currentKeys

                if (newPeerKeys.isNotEmpty()) {
                    val myPublicKey = cryptoManager.getPublicKeyString()
                    val requestJson = JSONObject().apply {
                        put("type", "sync_request")
                        put("requesterKey", myPublicKey)
                    }
                    val content = "$GROUP_UPDATE_PREFIX$requestJson"
                    Log.d(TAG, "🔄 New peer(s) connected: ${newPeerKeys.size} — sending group sync request")
                    // __GROUP_E2E_V2_2026_09_23__ v2 only: the mesh frame was CLEARTEXT to every
                    // relay and the VPS leg was the legacy IPFS queue (GROUP_E2E_V2_SPEC §0).
                    sendGroupUpdateRaw(content, newPeerKeys.toList())
                    // Re-announce public group membership so iOS adds Android to members list
                    // and queues future group messages for Android's key.
                    reannounceGroupMembership()
                }
            }
        }
    }

    /**
     * Broadcast all public groups via CrossPlatformMesh to iOS peers
     */
    private fun broadcastPublicGroupsViaMesh() {
        val publicGroups = _groups.value.filter { it.isPublic }
        if (publicGroups.isEmpty()) return

        val myPublicKey = cryptoManager.getPublicKeyString()
        for (group in publicGroups) {
            try {
                val adJson = JSONObject().apply {
                    put("groupId", group.id)
                    put("groupName", group.name)
                    put("adminPublicKey", group.creatorPublicKey)
                    put("memberCount", group.memberPublicKeys.size)
                    put("avatar", JSONObject.NULL)
                    put("timestamp", (System.currentTimeMillis() / 1000.0) - 978307200.0)
                }
                crossPlatformMesh.broadcastToAllPeers(adJson.toString(), "PUBLIC_GROUP_AD")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast group ad: ${e.message}")
            }
        }
    }

    // =========================================================================
    // GROUP CRUD
    // =========================================================================

    /**
     * Create a new group
     */
    fun createGroup(
        name: String,
        description: String = "",
        memberPublicKeys: List<String>,
        isPublic: Boolean = false,
        groupType: com.oshi.messenger.data.model.GroupType? = null,
        /**
         * Emoji avatar (iOS `MessageGroup.avatar`, GroupMessaging.swift:123, taken by
         * `createGroup` at :1177). The picker in `GroupsScreen` has always produced one and this
         * function had nowhere to put it, so every group was created with `avatar = null` — audit
         * G-4. `GroupsViewModel.createGroup` must pass `state.selectedAvatar` here.
         */
        avatar: String? = null
    ): Group {
        val myPublicKey = cryptoManager.getPublicKeyString()

        // Generate group encryption key
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(256)
        val groupKey = keyGenerator.generateKey().encoded

        // Derive groupType from isPublic when not explicitly provided (backwards compat)
        val resolvedType = groupType
            ?: if (isPublic) com.oshi.messenger.data.model.GroupType.PUBLIC
               else com.oshi.messenger.data.model.GroupType.COLLABORATIVE

        val group = Group(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            creatorPublicKey = myPublicKey,
            memberPublicKeys = (memberPublicKeys + myPublicKey).distinct(),
            adminPublicKeys = listOf(myPublicKey),
            createdAt = System.currentTimeMillis(),
            isPublic = isPublic,
            encryptionKey = groupKey,
            groupType = resolvedType,
            avatar = avatar
        )

        // Save locally
        val currentGroups = _groups.value.toMutableList()
        currentGroups.add(group)
        _groups.value = currentGroups
        saveGroups()
        updateGroupConversations()

        // Notify members. The full iOS `MessageGroup` JSON is what makes the group appear on an
        // iPhone at all (GroupMessaging.swift:881-902 creates unknown groups whose `members`
        // contains the receiver); `notifyGroupCreated` stays for Android↔Android, where the
        // encryptionKey travels.
        scope.launch {
            broadcastFullGroupUpdate(group)
            notifyGroupCreated(group)
        }

        // Broadcast public groups via cross-platform mesh immediately
        if (isPublic) {
            broadcastPublicGroupsViaMesh()
        }

        Log.d(TAG, "Created group: ${group.name} with ${group.memberPublicKeys.size} members")
        return group
    }

    /**
     * Update group info (rename / description / picture).
     *
     * Permission mirrors iOS `canChangeName` (GroupMessaging.swift:120): any member in a
     * collaborative or public group, admins only in an `admin_only` group.
     *
     * @return true when the change was applied.
     */
    fun updateGroup(
        groupId: String,
        name: String? = null,
        description: String? = null,
        groupImage: ByteArray? = null
    ): Boolean {
        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index < 0) return false

        val group = currentGroups[index]
        val myPublicKey = cryptoManager.getPublicKeyString()
        if (name != null && !group.canChangeName(myPublicKey)) {
            Log.w(TAG, "updateGroup: no rename permission for ${myPublicKey.take(8)} on '${group.name}'")
            return false
        }
        val renamed = name != null && name.isNotBlank() && name != group.name
        val descriptionChanged = description != null && description != group.description
        currentGroups[index] = group.copy(
            name = name?.takeIf { it.isNotBlank() } ?: group.name,
            description = description ?: group.description,
            groupImage = groupImage ?: group.groupImage,
            updatedAt = System.currentTimeMillis()
        )
        _groups.value = currentGroups
        saveGroups()
        updateGroupConversations()
        val updated = currentGroups[index]
        if (renamed) postSystemMessage(updated.id, "Group renamed to \"${updated.name}\"")

        scope.launch {
            // Full MessageGroup: the only shape an iPhone applies (name at swift:827).
            broadcastFullGroupUpdate(updated, includeBlobs = groupImage != null)
            // __GROUP_COMPAT_IGNORE_2026_09_23__ companions per GROUP_E2E_V2_SPEC §5.2: a rename
            // gets `group_renamed` (vector rename_minimal) and nothing else. The legacy
            // `{"type":"updated"}` went out on EVERY rename and Desktop logged it as
            // REJECTED_MALFORMED; it is now sent only when the description changed, because
            // Android ≤ 1.6 peers read a description from nowhere else.
            val recipients = updated.memberPublicKeys.filter { it != myPublicKey }
            for (frame in updateCompanionFrames(updated.id, updated.name, updated.description, renamed, descriptionChanged)) {
                sendGroupUpdateRaw(frame, recipients)
            }
        }
        return true
    }

    /**
     * Set the group picture: persists locally and broadcasts to all members so iOS
     * sees the new image (via `broadcastFullGroupUpdate`-style envelope).
     *
     * iOS parity: collaborative/public → any member; admin_only → admin only
     * (mirrors `Group.canChangeWallpaper`).
     */
    fun setGroupPicture(groupId: String, imageData: ByteArray?) {
        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index < 0) {
            Log.w(TAG, "setGroupPicture: group not found: $groupId")
            return
        }
        val myPublicKey = cryptoManager.getPublicKeyString()
        val existing = currentGroups[index]
        if (!existing.canChangeWallpaper(myPublicKey)) {
            Log.w(TAG, "setGroupPicture: no permission for ${myPublicKey.take(8)} on group ${existing.name}")
            return
        }
        currentGroups[index] = existing.copy(
            groupImage = imageData,
            updatedAt = System.currentTimeMillis()
        )
        _groups.value = currentGroups
        saveGroups()
        updateGroupConversations()
        postSystemMessage(groupId, "Group picture updated")
        scope.launch {
            // iOS reads the picture only out of a full `MessageGroup` broadcast with the blob
            // attached (swift:2016-2017 → `groupPictureData`); `group_picture_changed` hits its
            // `default:` and is dropped, so it is kept purely for Android↔Android.
            broadcastFullGroupUpdate(
                currentGroups[index],
                includeBlobs = true,
                pictureUpdatedBy = myPublicKey
            )
            broadcastGroupPictureUpdate(currentGroups[index])
        }
    }

    /**
     * Set the per-group wallpaper (synced to all members). Compresses to ≤ 500 KB
     * using `MediaCompressionManager` semantics, then broadcasts via the same
     * envelope path used for `member_added`.
     *
     * iOS parity: same permission model as `setGroupPicture`.
     */
    fun setGroupWallpaper(groupId: String, imageData: ByteArray?) {
        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index < 0) {
            Log.w(TAG, "setGroupWallpaper: group not found: $groupId")
            return
        }
        val myPublicKey = cryptoManager.getPublicKeyString()
        val existing = currentGroups[index]
        if (!existing.canChangeWallpaper(myPublicKey)) {
            Log.w(TAG, "setGroupWallpaper: no permission for ${myPublicKey.take(8)} on group ${existing.name}")
            return
        }
        val now = System.currentTimeMillis()
        currentGroups[index] = existing.copy(
            groupWallpaperData = imageData,
            groupWallpaperUpdatedAt = now,
            groupWallpaperUpdatedBy = myPublicKey,
            updatedAt = now
        )
        _groups.value = currentGroups
        saveGroups()
        updateGroupConversations()
        postSystemMessage(groupId, "Group wallpaper updated")
        scope.launch {
            // Same story as the picture: iOS only ever reads `groupWallpaperData` out of a full
            // `MessageGroup` (swift:836, :1990), never from `wallpaper_changed`.
            broadcastFullGroupUpdate(currentGroups[index], includeBlobs = true)
            broadcastWallpaperChanged(currentGroups[index])
        }
    }

    /**
     * Broadcast a "wallpaper_changed" envelope to all members. iOS handles this
     * via the GroupSystemMessageType.wallpaperChanged path (same wire format as
     * member_added):
     *   {"type":"wallpaper_changed","groupId":...,"wallpaperData":<base64>,
     *    "updatedBy":...,"updatedAt":<unix-ms>}
     */
    private suspend fun broadcastWallpaperChanged(group: Group) {
        val myPublicKey = cryptoManager.getPublicKeyString()
        val payload = JSONObject().apply {
            put("type", "wallpaper_changed")
            put("groupId", group.id)
            put("updatedBy", myPublicKey)
            put("updatedAt", group.groupWallpaperUpdatedAt ?: System.currentTimeMillis())
            group.groupWallpaperData?.let {
                put("wallpaperData", Base64.encodeToString(it, Base64.NO_WRAP))
            }
        }
        val content = "$GROUP_UPDATE_PREFIX$payload"
        // __GROUP_E2E_V2_2026_09_23__ v2 only (GROUP_E2E_V2_SPEC §0); oversize ⇒ not sent (§5.1).
        sendGroupUpdateRaw(content, group.memberPublicKeys.filter { it != myPublicKey })
        Log.d(TAG, "🎨 Broadcast wallpaper_changed for group ${group.name} to ${group.memberPublicKeys.size - 1} member(s)")
    }

    /**
     * Broadcast a "group_picture_changed" envelope to all members. Mirrors the
     * iOS `broadcastGroupPictureUpdate` path. The picture bytes are sent inline
     * because iOS strips them out of the lightweight broadcast.
     */
    private suspend fun broadcastGroupPictureUpdate(group: Group) {
        val myPublicKey = cryptoManager.getPublicKeyString()
        val payload = JSONObject().apply {
            put("type", "group_picture_changed")
            put("groupId", group.id)
            put("updatedBy", myPublicKey)
            put("updatedAt", group.updatedAt)
            group.groupImage?.let {
                put("groupImage", Base64.encodeToString(it, Base64.NO_WRAP))
            }
        }
        val content = "$GROUP_UPDATE_PREFIX$payload"
        // __GROUP_E2E_V2_2026_09_23__ v2 only (GROUP_E2E_V2_SPEC §0); oversize ⇒ not sent (§5.1).
        sendGroupUpdateRaw(content, group.memberPublicKeys.filter { it != myPublicKey })
        Log.d(TAG, "🖼️ Broadcast group_picture_changed for group ${group.name}")
    }

    // =========================================================================
    // FULL iOS `MessageGroup` BROADCAST  (GroupMessaging.swift:2024)
    // =========================================================================

    /**
     * Deliver one `📢GROUP_UPDATE📢<json>` body to every recipient over mesh + VPS.
     * iOS ingests the prefix at MessageManager.swift:5525-5538 and hands the JSON to
     * `handleIncomingGroupUpdate` (GroupMessaging.swift:786).
     */
    private suspend fun sendGroupUpdateRaw(content: String, recipients: List<String>) {
        val sentV2 = sendStateV2(content, recipients)
        // __LEGACY_COMPAT_2026_09_23__ transition window only (server flag + compiled sunset): the
        // SAME `📢GROUP_UPDATE📢` frame also rides the legacy 1:1 IPFS path — what Android ≤ 1.6.25
        // and iOS ≤ 143 read — to the members not proven v2-group-capable. Never the mesh.
        // __LEAK_FIX_2026_09_25__ …and not to a member v2 reached whose build routes v2 state.
        sendStateLegacyCompat(content, recipients, sentV2)
    }

    /** __LEGACY_COMPAT_2026_09_23__ legacy 1:1 copy of a state frame (as Android 1.6.25 sent it). */
    private suspend fun sendStateLegacyCompat(content: String, recipients: List<String>, sentV2: Set<String>) {
        val myPublicKey = cryptoManager.getPublicKeyString()
        val compat = com.oshi.messenger.network.v2.GroupLegacyCompat
        val legacy = compat.stillNeedLegacy(
            compat.legacyRecipientsNow(recipients.filter { it != myPublicKey }), sentV2, ::peerReadsGroupV3)
        if (legacy.isEmpty()) return
        var ok = 0
        for (peer in legacy) {
            // Encrypted with the legacy 1:1 crypto; the queue's fallback push is the server's
            // generic banner (no name, no content).
            // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ This is GROUP traffic, so
            // it must not be judged by the member's own 1:1 switch: someone who
            // took a private conversation off the relay did not ask for a group
            // to stop syncing. The group is not in scope this deep in the state
            // path, and "" matches no conversation, so the call reads exactly as
            // it did before. When the group switch reaches the UI, the group id
            // threads through here and this becomes a real question.
            if (try { vpsClient.sendMessage(peer, content, conversationId = "") } catch (_: Exception) { false }) ok++
        }
        Log.d(TAG, "🕰️ [compat] group state → $ok/${legacy.size} legacy member(s)")
    }

    /** Serialize [group] as the iOS `MessageGroup` JSON, prefixed for the 1:1 group-update channel. */
    internal fun buildFullGroupUpdateContent(
        group: Group,
        includeBlobs: Boolean,
        memberKeysOverride: List<String>? = null,
        pictureUpdatedBy: String? = null,
        stateVersion: Int? = stateVersionOf(group.id)
    ): String {
        val memberKeys = memberKeysOverride ?: group.memberPublicKeys
        val adminKey = group.creatorPublicKey.takeIf { it.isNotEmpty() }
            ?: group.adminPublicKeys.firstOrNull()
            ?: ""
        val aliases = memberKeys.mapNotNull { key ->
            runCatching { contactAliasResolver?.invoke(key) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { key to it }
        }.toMap()
        val json = buildIosMessageGroupJson(
            groupId = group.id,
            name = group.name,
            typeRaw = iosGroupTypeRaw(group.effectiveGroupType),
            adminPublicKey = adminKey,
            memberKeys = memberKeys,
            adminKeys = group.adminPublicKeys,
            createdAtMs = group.createdAt,
            lastActivityMs = group.updatedAt,
            memberAliases = aliases,
            // iOS strips the blobs unless `includePicture` is set (swift:2031-2038) — a full
            // broadcast on every rename would otherwise ship 500 KB to every member.
            groupPictureBase64 = if (includeBlobs) {
                group.groupImage?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            } else null,
            groupPictureUpdatedBy = pictureUpdatedBy,
            groupPictureUpdatedAtMs = if (includeBlobs && group.groupImage != null) group.updatedAt else null,
            groupWallpaperBase64 = if (includeBlobs) {
                group.groupWallpaperData?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            } else null,
            groupWallpaperUpdatedBy = group.groupWallpaperUpdatedBy,
            groupWallpaperUpdatedAtMs = group.groupWallpaperUpdatedAt,
            pinnedMessageId = group.pinnedMessageId,
            // 📌 Who pinned it. iOS falls back to `adminPublicKey` when this is absent
            // (GroupMessaging.swift:842), so send the same fallback rather than an empty
            // key — a pin from an Android member otherwise reads as the admin's on every
            // iPhone.
            pinnedBy = group.pinnedBy ?: adminKey.takeIf { it.isNotEmpty() },
            // 🎨 Emoji avatar. iOS CLEARS `avatar` when an incoming update omits the key
            // (only picture/wallpaper/isMuted survive a stripped update —
            // GroupMessaging.swift:800-820), so it must ride every broadcast, blobs or not.
            avatar = group.avatar,
            // `isMuted` is a personal preference on both platforms and iOS deliberately restores
            // its own value over anything we send (swift:810) — never push ours.
            isMuted = false,
            stateVersion = stateVersion,
            description = group.description,
            // __EVICTION_TOMBSTONE_WAS_DEVICE_LOCAL_2026_11_04__ Without this the
            // tombstone only ever existed on the device that performed the eviction:
            // every other member kept re-adding the evicted key and pushing it back,
            // which is the oscillation itself. iOS has shipped the field on the wire
            // since `__GROUP_EVICTION_TOMBSTONE_2026_09_10__` and unions it on receipt
            // (`GroupMessaging.swift:1097-1102`); Android encoded it nowhere.
            evictedMemberKeys = group.evictedMemberKeys,
            settings = settingsOf(group.id),
            emitEmptyDescription = isDescriptionTouched(group.id)
        )
        return "$GROUP_UPDATE_PREFIX$json"
    }

    /**
     * Broadcast the whole group state in the iOS `MessageGroup` shape.
     *
     * This is the single channel iPhones actually act on: name, member list, per-member
     * `isAdmin`, picture, wallpaper and pin all ride here, and an unknown group whose `members`
     * contains the receiver is CREATED (GroupMessaging.swift:881-902). Every Android-native
     * `{"type":"created"|"updated"|"admin_promoted"|…}` body is dropped by iOS's `default:`
     * (swift:992), so those are still emitted alongside — but only for Android↔Android.
     *
     * @param memberKeysOverride member list to advertise (used when leaving: the roster minus self).
     * @param recipientsOverride who to notify (used when leaving: the roster INCLUDING the members
     *        we just dropped ourselves from).
     */
    internal suspend fun broadcastFullGroupUpdate(
        group: Group,
        includeBlobs: Boolean = false,
        memberKeysOverride: List<String>? = null,
        recipientsOverride: List<String>? = null,
        pictureUpdatedBy: String? = null,
        // __GROUP_E2E_V2_2026_09_23__ a definition that CHANGES state carries local+1 (spec §5.3);
        // an answer to a sync request re-sends the current version.
        bumpVersion: Boolean = true
    ) {
        val myPublicKey = cryptoManager.getPublicKeyString()
        val recipients = (recipientsOverride ?: group.memberPublicKeys)
            .filter { it != myPublicKey && it.isNotEmpty() }
            .distinct()
        if (recipients.isEmpty()) {
            Log.d(TAG, "⏭️ No recipients for full group update of '${group.name}'")
            return
        }
        val version = if (bumpVersion) bumpStateVersion(group.id) else stateVersionOf(group.id)
        var content = buildFullGroupUpdateContent(group, includeBlobs, memberKeysOverride, pictureUpdatedBy, version)
        if (includeBlobs && content.toByteArray(Charsets.UTF_8).size > MAX_STATE_PLAINTEXT_BYTES) {
            // GROUP_E2E_V2_SPEC §5.1: over the relay budget ⇒ the definition goes WITHOUT the
            // blob (receivers keep their current picture) — never an IPFS fallback.
            Log.w(TAG, "👥 picture/wallpaper too large for one envelope — definition sent without it")
            content = buildFullGroupUpdateContent(group, false, memberKeysOverride, pictureUpdatedBy, version)
        }
        sendGroupUpdateRaw(content, recipients)
        Log.d(
            TAG,
            "📡 Broadcast full MessageGroup for '${group.name}' (${content.length} chars, blobs=$includeBlobs) to ${recipients.size} member(s)"
        )
    }

    /**
     * Add member to group
     */
    fun addMember(groupId: String, memberPublicKey: String): Boolean {
        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index < 0) return false
        val group = currentGroups[index]
        if (group.memberPublicKeys.contains(memberPublicKey)) return false
        if (memberPublicKey.isBlank()) return false

        // iOS `canAddMembers` (GroupMessaging.swift:116-118).
        val myPublicKey = cryptoManager.getPublicKeyString()
        if (!group.canAddMembers(myPublicKey)) {
            Log.w(TAG, "addMember: no permission for ${myPublicKey.take(8)} on '${group.name}'")
            return false
        }

        // __ROSTER_COMPARED_RAW_KEYS_2026_11_07__ Appending blind could add a
        // SECOND copy of someone already in the roster under the other base64
        // spelling. The stored spelling of an existing entry is left untouched —
        // only the decision to append is made canonically.
        val adding = GroupUpdateAuthorizer.canonicalIdentity(memberPublicKey)
        val alreadyThere = group.memberPublicKeys
            .any { GroupUpdateAuthorizer.canonicalIdentity(it) == adding }
        currentGroups[index] = group.copy(
            memberPublicKeys = if (alreadyThere) group.memberPublicKeys
                               else group.memberPublicKeys + memberPublicKey,
            // __EVICTED_LIST_NEVER_EXISTED_2026_11_04__ Adding somebody back on
            // purpose lifts the eviction, so it is a decision and not a ban.
            evictedMemberKeys = group.evictedMemberKeys.filter { it != memberPublicKey },
            updatedAt = System.currentTimeMillis()
        )
        _groups.value = currentGroups
        saveGroups()
        updateGroupConversations()

        // Post system join notification
        val shortKey = memberPublicKey.take(8) + "..."
        postSystemMessage(currentGroups[index].id, "$shortKey joined the group")

        scope.launch {
            // `scope` is `Dispatchers.IO + SupervisorJob()` and there is NO
            // CoroutineExceptionHandler anywhere in this file. A SupervisorJob stops
            // sibling cancellation; it does NOT consume the exception, so an uncaught
            // throw here is process death with no telemetry — which is how the
            // encryptionKey NPE reached a real user. The member is already added and
            // saved by this point, so a failed broadcast must degrade to "peers find out
            // on the next sync", never to a crash.
            try {
                // Full roster to everyone (incl. the newcomer) — iOS diffs it and posts a
                // `memberJoined` system row (swift:821-823) and creates the group for the newcomer.
                broadcastFullGroupUpdate(currentGroups[index])
                // Android-native path: carries the shared encryptionKey to the new member.
                sendGroupKeyToMember(currentGroups[index], memberPublicKey)
            } catch (e: Exception) {
                Log.e(TAG, "addMember: broadcast failed for ${memberPublicKey.take(8)}…: ${e.message}", e)
            }
        }
        return true
    }

    /**
     * Remove member from group.
     *
     * @param isLeaveFlow set by [leaveGroup]; suppresses the broadcast so the caller can send the
     *        departure notice with the pre-removal recipient list.
     */
    fun removeMember(groupId: String, memberPublicKey: String, isLeaveFlow: Boolean = false): Boolean {
        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index < 0) return false

        val group = currentGroups[index]
        val myPublicKey = cryptoManager.getPublicKeyString()
        val isLeaving = memberPublicKey == myPublicKey
        // Kicking someone else needs the iOS `canRemoveMembers` permission (swift:119); leaving
        // is always allowed.
        if (!isLeaving && !group.canRemoveMembers(myPublicKey)) {
            Log.w(TAG, "removeMember: no permission for ${myPublicKey.take(8)} on '${group.name}'")
            return false
        }

        val priorRecipients = group.memberPublicKeys
        // __ROSTER_COMPARED_RAW_KEYS_2026_11_07__ (candidate cause of Anatole's N9,
        // "iOS users disappear from the member list and come back")
        //
        // These two filters used a RAW `!=` while every authorisation check in
        // this file compares through `canonicalIdentity`. `normalizeKey` maps
        // `-`→`+`, `_`→`/` and drops `=`, so the SAME identity spelled base64url
        // and base64 is one person to the authorizer and two different strings
        // here. A removal naming the other spelling matched nothing and the
        // member stayed; `addMember` below could likewise append a second copy
        // of someone already present.
        //
        // That is a cross-SPELLING bug, which in practice means a cross-PLATFORM
        // one: iOS sends standard base64, Android canonicalises. A roster that
        // gains a duplicate from one source and is replaced wholesale by
        // another is exactly a membership list that changes without anyone
        // touching it.
        val removing = GroupUpdateAuthorizer.canonicalIdentity(memberPublicKey)
        currentGroups[index] = group.copy(
            memberPublicKeys = group.memberPublicKeys
                .filter { GroupUpdateAuthorizer.canonicalIdentity(it) != removing },
            adminPublicKeys = group.adminPublicKeys
                .filter { GroupUpdateAuthorizer.canonicalIdentity(it) != removing },
            // __EVICTED_LIST_NEVER_EXISTED_2026_11_04__ Remember the eviction,
            // or their next member_sync_request puts them straight back.
            // Leaving is NOT an eviction: someone who walks out must be able to
            // be invited again, and must not be recorded as thrown out.
            evictedMemberKeys = if (isLeaving) group.evictedMemberKeys
                                else (group.evictedMemberKeys + memberPublicKey).distinct(),
            updatedAt = System.currentTimeMillis()
        )
        _groups.value = currentGroups
        // __GROUP_SETTINGS_2026_11_20__ recent events (spec §2.8)
        recordAdminEvents(group.id, GroupAdminLog.diff(group, currentGroups[index], null, null, cryptoManager.getPublicKeyString(), System.currentTimeMillis()))
        saveGroups()
        updateGroupConversations()

        // Post system leave notification
        val shortKey = if (isLeaving) "You" else memberPublicKey.take(8) + "..."
        postSystemMessage(currentGroups[index].id, "$shortKey left the group")

        if (!isLeaveFlow) {
            val updated = currentGroups[index]
            scope.launch {
                // iOS drops `key_rotation` at its `default:` — before this, a member removed on
                // Android stayed on every iPhone roster forever. Both shapes below ARE parsed by
                // iOS: the full roster (diffed into a `memberRemoved` row, swift:824) and the
                // explicit `member_removed` case (swift:962).
                broadcastFullGroupUpdate(updated, recipientsOverride = priorRecipients)
                sendGroupUpdateRaw(
                    "$GROUP_UPDATE_PREFIX${buildMemberRemovedJson(updated.id, memberPublicKey)}",
                    priorRecipients.filter { it != myPublicKey }
                )
                rotateGroupKey(updated)
            }
        }
        return true
    }

    /**
     * Leave group.
     *
     * The departure notice MUST go out before the local wipe and MUST be addressed to the roster
     * as it stood *including* us — mirrors iOS `leaveGroup` (GroupMessaging.swift:3545-3557),
     * which calls `broadcastFullGroupUpdate` before it touches local state.
     */
    fun leaveGroup(groupId: String) {
        val myPublicKey = cryptoManager.getPublicKeyString()
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) }
        if (group == null) {
            Log.w(TAG, "leaveGroup: unknown group $groupId")
            return
        }
        val priorRecipients = group.memberPublicKeys.filter { it != myPublicKey }
        val remainingMembers = group.memberPublicKeys.filter { it != myPublicKey }
        val departureContent = buildFullGroupUpdateContent(
            group.copy(
                adminPublicKeys = group.adminPublicKeys.filter { it != myPublicKey },
                updatedAt = System.currentTimeMillis()
            ),
            includeBlobs = false,
            memberKeysOverride = remainingMembers
        )
        val memberRemovedContent =
            "$GROUP_UPDATE_PREFIX${buildMemberRemovedJson(group.id, myPublicKey)}"

        scope.launch {
            sendGroupUpdateRaw(departureContent, priorRecipients)
            sendGroupUpdateRaw(memberRemovedContent, priorRecipients)
            Log.d(TAG, "👋 Announced departure from '${group.name}' to ${priorRecipients.size} member(s)")
        }

        removeMember(groupId, myPublicKey, isLeaveFlow = true)

        // Remove group from local list if no longer a member
        val currentGroups = _groups.value.toMutableList()
        currentGroups.removeAll {
            it.id.equals(groupId, ignoreCase = true) && !it.memberPublicKeys.contains(myPublicKey)
        }
        _groups.value = currentGroups
        // Refuse every later re-share of this id (audit G-3) — a peer that auto-adds us back into
        // the roster it broadcasts would otherwise walk us straight back into the group.
        tombstoneGroup(group.id)
        // __DEVSYNC_LEAVE_2026_09_23__ own-device sync: the leave travels as GROUPS{left, leftAt}.
        recordDevSyncLeave(group.id, System.currentTimeMillis(), group.name, group.encryptionKey)
        // iOS wipes the thread on leave (GroupMessaging.swift:3833-3835); Android kept it, so a
        // group later re-created under the same id came back with the old history (audit G-16).
        val remainingMessages = _groupMessages.value.toMutableMap()
        if (remainingMessages.keys.removeAll { it.equals(group.id, ignoreCase = true) }) {
            _groupMessages.value = remainingMessages
            saveMessagesNow()
        }
        saveGroups()
        updateGroupConversations()
    }

    /**
     * Pin a message in the group (admin only). Broadcasts a system notice.
     */
    fun pinMessage(groupId: String, messageId: String) {
        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index >= 0) {
            currentGroups[index] = currentGroups[index].copy(
                pinnedMessageId = messageId,
                updatedAt = System.currentTimeMillis()
            )
            _groups.value = currentGroups
            saveGroups()
            postSystemMessage(groupId, "A message was pinned")
            // iOS carries the pin inside the full group JSON (`pinnedMessageId`, swift:840-845)
            // and broadcasts it from pinMessage (swift:1115).
            scope.launch { broadcastFullGroupUpdate(currentGroups[index]) }
        }
    }

    /**
     * Unpin the pinned message in the group.
     */
    fun unpinMessage(groupId: String) {
        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index >= 0) {
            currentGroups[index] = currentGroups[index].copy(
                pinnedMessageId = null,
                updatedAt = System.currentTimeMillis()
            )
            _groups.value = currentGroups
            saveGroups()
            scope.launch { broadcastFullGroupUpdate(currentGroups[index]) }
        }
    }

    /**
     * Delete group (admin only)
     */
    fun deleteGroup(groupId: String) {
        val currentGroups = _groups.value.toMutableList()
        // G-13: iOS emits UPPERCASE UUIDs; an Android-created group is stored lowercase.
        val group = currentGroups.find { it.id.equals(groupId, ignoreCase = true) } ?: return

        val myPublicKey = cryptoManager.getPublicKeyString()
        if (!group.adminPublicKeys.contains(myPublicKey)) {
            Log.w(TAG, "Cannot delete group: not an admin")
            return
        }

        // Notify all members
        scope.launch {
            notifyGroupDeleted(group)
        }

        currentGroups.removeAll { it.id.equals(groupId, ignoreCase = true) }
        _groups.value = currentGroups
        // A deleted group must not be resurrected by a member who has not applied the delete yet.
        tombstoneGroup(group.id)
        saveGroups()
        updateGroupConversations()
    }

    /**
     * Join a discovered public group (from mesh broadcast)
     */
    fun joinDiscoveredGroup(discovered: DiscoveredGroup) {
        val myPublicKey = cryptoManager.getPublicKeyString()
        clearGroupTombstone(discovered.groupId)

        // Create local group entry — derive encryption key from groupId so we can
        // decrypt group messages immediately (matches iOS deriveGroupKey behavior)
        val groupKey = deriveGroupKey(discovered.groupId)
        val group = Group(
            id = discovered.groupId,
            name = discovered.groupName,
            creatorPublicKey = discovered.adminPublicKey,
            memberPublicKeys = listOf(discovered.adminPublicKey, myPublicKey).distinct(),
            adminPublicKeys = listOf(discovered.adminPublicKey),
            isPublic = true,
            encryptionKey = groupKey
        )

        val currentGroups = _groups.value.toMutableList()
        if (currentGroups.none { it.id.equals(discovered.groupId, ignoreCase = true) }) {
            currentGroups.add(group)
            _groups.value = currentGroups
            saveGroups()
            updateGroupConversations()

            // Remove from discovered list
            _discoveredGroups.value = _discoveredGroups.value.filter { it.groupId != discovered.groupId }

            // Notify admin that we joined via BOTH mesh and group envelope
            scope.launch {
                val joinJson = JSONObject().apply {
                    put("type", "member_added")
                    put("groupId", discovered.groupId)
                    put("memberPublicKey", myPublicKey)
                }
                val joinPayload = "$GROUP_UPDATE_PREFIX$joinJson"

                // __GROUP_E2E_V2_2026_09_23__ v2 only (GROUP_E2E_V2_SPEC §0, §5.2): the mesh frame
                // was cleartext and the "join envelope" was an IPFS pin under the groupId key.
                val syncReq = JSONObject().apply {
                    put("type", "member_sync_request")
                    put("groupId", discovered.groupId)
                    put("requesterPublicKey", myPublicKey)
                }
                sendGroupUpdateRaw(joinPayload, listOf(discovered.adminPublicKey))
                sendGroupUpdateRaw("$GROUP_UPDATE_PREFIX$syncReq", listOf(discovered.adminPublicKey))
            }

            Log.d(TAG, "Joined discovered group: ${discovered.groupName}")
        }
    }

    /**
     * Join a group via QR / deep-link invite payload (oshi://group/join?gid=…&name=…&inviter=…).
     * Mirrors iOS `MessageGroupManager.joinGroupFromInvite`.
     *
     *   • If already a member locally → no-op (returns true).
     *   • If we have the group but aren't a member → re-add ourselves and broadcast.
     *   • If unknown → create a stub Group and request a full sync from the inviter.
     *
     * @return true on success.
     */
    fun joinGroupFromInvite(gid: String, inviterKey: String?, name: String?, inviteToken: String? = null): Boolean {
        val myPublicKey = cryptoManager.getPublicKeyString()
        if (myPublicKey.isEmpty()) {
            Log.e(TAG, "joinGroupFromInvite: no public key")
            return false
        }

        // A deliberate re-join is the one thing that lifts the left-group tombstone (audit G-3).
        clearGroupTombstone(gid)

        val currentGroups = _groups.value.toMutableList()
        val existingIdx = currentGroups.indexOfFirst { it.id.equals(gid, ignoreCase = true) }

        // Case 1 & 2: known group locally
        if (existingIdx >= 0) {
            val existing = currentGroups[existingIdx]
            if (existing.memberPublicKeys.contains(myPublicKey)) {
                Log.d(TAG, "joinGroupFromInvite: already a member of '${existing.name}'")
                return true
            }
            currentGroups[existingIdx] = existing.copy(
                memberPublicKeys = existing.memberPublicKeys + myPublicKey,
                updatedAt = System.currentTimeMillis()
            )
            _groups.value = currentGroups
            saveGroups()
            updateGroupConversations()
            postSystemMessage(existing.id, "You joined the group")
            // Notify members (admin + others)
            scope.launch {
                broadcastMemberAddedNotification(existing.id, myPublicKey)
            }
            return true
        }

        // Case 3: stub group from invite metadata, request full sync from inviter
        val admin = inviterKey?.takeIf { it.isNotEmpty() } ?: myPublicKey
        val groupKey = deriveGroupKey(gid)
        val stub = Group(
            id = gid,
            name = name ?: "Group",
            description = "",
            creatorPublicKey = admin,
            memberPublicKeys = listOf(admin, myPublicKey).distinct(),
            adminPublicKeys = listOf(admin),
            isPublic = false,
            encryptionKey = groupKey,
            groupType = GroupType.COLLABORATIVE
        )
        currentGroups.add(stub)
        _groups.value = currentGroups
        saveGroups()
        updateGroupConversations()
        postSystemMessage(gid, "You joined the group")
        markJoined(gid)   // __GROUP_SETTINGS_2026_11_20__ welcome message on the definition (§2.5)

        // Send join notification + sync_request to the inviter
        scope.launch {
            val joinJson = JSONObject().apply {
                put("type", "member_added")
                put("groupId", gid)
                put("memberPublicKey", myPublicKey)
            }
            val joinPayload = "$GROUP_UPDATE_PREFIX$joinJson"
            val syncReq = JSONObject().apply {
                put("type", "member_sync_request")
                put("groupId", gid)
                put("requesterPublicKey", myPublicKey)
                // __GROUP_SETTINGS_2026_11_20__ reset-able invite link (spec §3.2); old builds ignore it
                inviteToken?.let { put("inviteToken", it) }
            }
            val syncPayload = "$GROUP_UPDATE_PREFIX$syncReq"

            // __GROUP_E2E_V2_2026_09_23__ v2 only (GROUP_E2E_V2_SPEC §0): no cleartext mesh frame,
            // no legacy IPFS queue, no groupId-keyed "join envelope".
            if (admin != myPublicKey) {
                sendGroupUpdateRaw(joinPayload, listOf(admin))
                sendGroupUpdateRaw(syncPayload, listOf(admin))
            }
        }

        Log.d(TAG, "✅ joinGroupFromInvite: stub created for '${stub.name}' ($gid), sync requested from $admin")
        return true
    }

    /** Broadcast a "member_added" group_update to every existing member. */
    private suspend fun broadcastMemberAddedNotification(groupId: String, memberPublicKey: String) {
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return
        val payload = JSONObject().apply {
            put("type", "member_added")
            put("groupId", groupId)
            put("memberPublicKey", memberPublicKey)
        }
        val content = "$GROUP_UPDATE_PREFIX$payload"
        // __GROUP_E2E_V2_2026_09_23__ v2 only.
        sendGroupUpdateRaw(content, group.memberPublicKeys.filter { it != memberPublicKey })
    }

    // =========================================================================
    // MESSAGING
    // =========================================================================

    /**
     * Send message to group
     */
    /**
     * Send a message to a group.
     *
     * [content] may already be a `💬REPLY💬{…}` envelope built by the composer — exactly how iOS
     * does it (GroupViews.swift:4701 wraps the body before handing it to the group manager). The
     * envelope travels verbatim on the wire; locally we store the unwrapped body plus
     * `replyToId`, so the bubble renders a quote instead of raw JSON.
     */
    fun sendGroupMessage(
        groupId: String,
        content: String,
        mediaType: MediaType? = null,
        mediaData: ByteArray? = null,
        mediaFileName: String? = null,
        mediaDurationMs: Long? = null,
        /** __MENTIONS_2026_09_23__ members picked in the composer; kept only while their token is in the text. */
        mentions: List<com.oshi.messenger.util.MentionWire.Mention> = emptyList(),
        /** __VIDEO_NOTE_2026_09_24__ round video message; its duration is [mediaDurationMs]. */
        isVideoNote: Boolean = false
    ) {
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return
        val myPublicKey = cryptoManager.getPublicKeyString()

        val parsedReply = parseReplyEnvelope(content)
        val message = GroupMessage(
            id = UUID.randomUUID().toString(),
            groupId = groupId,
            senderPublicKey = myPublicKey,
            senderName = "Me",  // Will be resolved by recipient
            content = parsedReply?.content ?: content,
            timestamp = System.currentTimeMillis(),
            isFromMe = true,
            mediaType = mediaType,
            // __CONTACT_CARD_COMPAT_2026_09_27__ A forwarded card (the old
            // timestamp-less welcome card above all) leaves in the shape iOS decodes.
            mediaData = if (mediaType == MediaType.CONTACT && mediaData != null) {
                com.oshi.messenger.data.model.ContactCardWire
                    .canonicalizeForWire(mediaData, System.currentTimeMillis())
            } else mediaData,
            mediaFileName = mediaFileName,
            mediaDurationMs = mediaDurationMs,
            replyToId = parsedReply?.replyToId,
            isVideoNote = isVideoNote && mediaType == MediaType.VIDEO
        )
        // __MENTIONS_2026_09_23__ before the row is published, so the bubble draws them at once.
        recordMentions(
            message.id,
            com.oshi.messenger.util.MentionWire.stillPresent(
                com.oshi.messenger.util.MessageFormatting.strip(parsedReply?.content ?: content), mentions) // __TEXT_FORMATTING_2026_11_24__
                .filter { m -> group.memberPublicKeys.any { sameMentionKey(it, m.publicKey) } }
        )

        // Save locally
        addMessageToGroup(groupId, message)
        updateGroupConversations()
        saveMessages()
        // __CHANNEL_WEB_2026_11_26__ public web copy (enqueue only; decides eligibility itself).
        channelWebHooks?.let { h -> runCatching { h.onOwnPost(group.id, message) } }

        // Send to all group members — with the reply envelope intact.
        val wireMessage = if (parsedReply != null) message.copy(content = content) else message
        scope.launch {
            sendMessageToMembers(group, wireMessage)
        }

        // __BOT_IN_THE_PIPELINE_2026_11_09__ The outbound half: the owner's own
        // `/help` reaches their bots (iOS MessageGroupManager.sendMessage,
        // GroupMessaging.swift:2684). Bot replies leave through [sendBotMessage],
        // which does not come back through here.
        onGroupMessageForBots?.invoke(group.id, parsedReply?.content ?: content, myPublicKey, false)
    }

    /**
     * __BOT_IN_THE_PIPELINE_2026_11_09__ A LOCAL bot's reply, from its owner's device,
     * over the normal end-to-end encrypted group send (iOS `BotManager.emit` →
     * `MessageGroupManager.sendMessage`, BotManager.swift:627-632).
     *
     * Locally the row is labelled `bot:<id8>` so the owner sees a bot bubble, exactly
     * as on an iPhone. On the wire [sendMessageToMembers] always signs with OUR key —
     * see [BOT_AUTHOR_FIELD] for why — so every member, iOS or Android, sees the
     * owner's bubble reading `[BotName] reply`. Returns false when the group is unknown.
     */
    fun sendBotMessage(groupId: String, botSenderKey: String, botName: String, content: String): Boolean {
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return false
        val message = GroupMessage(
            id = UUID.randomUUID().toString(),
            groupId = group.id,
            senderPublicKey = botSenderKey,
            senderName = botName,
            content = content,
            timestamp = System.currentTimeMillis(),
            isFromMe = false,
            isRead = true
        )
        addMessageToGroup(group.id, message)
        updateGroupConversations()
        saveMessages()
        scope.launch {
            try {
                sendMessageToMembers(group, message, botAuthor = botName)
            } catch (e: Exception) {
                Log.w(TAG, "🤖 bot reply send failed: ${e.message}")
            }
        }
        return true
    }

    /**
     * Send a group typing ping through the **group message envelope**, which is the only ingress
     * iOS has: `receiveGroupMessage` sniffs `⌨️TYPING⌨️` off the decoded body and routes it to
     * `TypingIndicatorManager.receiveGroupTypingStatus` (GroupMessaging.swift:2905-2912).
     * `TypingStatus.init(from:)` (TypingIndicatorManager.swift:36-43) requires `senderPublicKey`,
     * `isTyping` and `timestamp` non-optionally — the 1:1 ping Android used to send carried none
     * of them, and iOS has no live 1:1 typing receiver anyway.
     *
     * Never persisted on either side (Android drops it as a system payload in
     * `handleIncomingGroupMessage`).
     */
    /**
     * 📊 Cast a ballot into a group.
     *
     * `ephemeral = true`, like the typing ping above: a ballot is silent in
     * [com.oshi.messenger.data.model.OSHIControlPayload], so it must not be
     * stored as a group message — it would sit in the thread as an invisible
     * row and leave the groups list showing a blank last message.
     */
    fun sendGroupPollBallot(groupId: String, content: String) {
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return
        val myPublicKey = cryptoManager.getPublicKeyString()
        if (myPublicKey.isEmpty()) return
        val ballot = GroupMessage(
            id = UUID.randomUUID().toString(),
            groupId = group.id,
            senderPublicKey = myPublicKey,
            senderName = "Me",
            content = content,
            timestamp = System.currentTimeMillis(),
            isFromMe = true
        )
        scope.launch {
            try {
                sendMessageToMembers(group, ballot, ephemeral = true)
            } catch (e: Exception) {
                Log.w(TAG, "📊 group ballot failed: ${e.message}")
            }
        }
    }

    /**
     * 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ Turn advanced chat privacy on or off for a
     * group. Local state first, then an ordinary (stored) group message so the
     * thread shows "You turned on …" and every member applies it through
     * `handleIncomingGroupMessage`. Any member may do this, admins or not.
     *
     * Ungated by member version, like the poll and OFFLINE_ONLY: no capability
     * proves a member reads this sentinel, so an iOS 1.0.43 member sees raw text.
     */
    fun setAdvancedChatPrivacy(groupId: String, on: Boolean) {
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return
        AdvancedChatPrivacyStore.attach(context)
        AdvancedChatPrivacyReaders.attach(context)
        val change = com.oshi.messenger.data.model.AdvancedChatPrivacyChange.create(on)
        AdvancedChatPrivacyStore.setLocal(change, group.id)
        val message = GroupMessage(
            id = UUID.randomUUID().toString(),
            groupId = group.id,
            senderPublicKey = cryptoManager.getPublicKeyString(),
            senderName = "Me",
            content = change.toMessageString(),
            timestamp = System.currentTimeMillis(),
            isFromMe = true,
            isRead = true,
        )
        addMessageToGroup(group.id, message)
        updateGroupConversations()
        saveMessages()
        // Fan-out to the members who read the sentinel ONLY (bundle bit 16, or they
        // sent us one): the others would see raw JSON. Push is already off for it
        // (`OSHIControlPayload.neverNotifies`), so nobody outside the set is woken.
        val readers = advancedPrivacyReaders(group)
        Log.d(TAG, "🛡 group ${group.id.take(8)} advanced privacy on=$on → ${readers.size} reader(s), " +
            "${advancedPrivacyNonReaders(group).size} on an older version")
        if (readers.isEmpty()) return
        scope.launch {
            try {
                sendMessageToMembers(group, message, onlyMembers = readers.toSet())
            } catch (e: Exception) {
                Log.w(TAG, "🛡 advanced-privacy fan-out failed: ${e.message}")
            }
        }
    }

    /** Other human members whose build reads `🛡ADV_PRIVACY🛡`. */
    private fun advancedPrivacyReaders(group: Group): List<String> =
        com.oshi.messenger.data.model.AdvancedChatPrivacyPolicy.readers(groupRecipientKeys(group, null)) {
            AdvancedChatPrivacyReaders.peerReadsNow(it)
        }

    /** Other human members who will not apply the switch until they update (group info footer). */
    fun advancedPrivacyNonReaders(groupId: String): List<String> {
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return emptyList()
        return advancedPrivacyNonReaders(group)
    }

    private fun advancedPrivacyNonReaders(group: Group): List<String> {
        AdvancedChatPrivacyReaders.attach(context)
        return groupRecipientKeys(group, null).filterNot { AdvancedChatPrivacyReaders.peerReadsNow(it) }
    }

    fun sendGroupTypingStatus(groupId: String, isTyping: Boolean) {
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return
        val myPublicKey = cryptoManager.getPublicKeyString()
        if (myPublicKey.isEmpty()) return
        // 2 s debounce, same as iOS `sendDebounce` (TypingIndicatorManager.swift:96). A "stopped"
        // ping always goes out so the peer's indicator clears promptly.
        val now = System.currentTimeMillis()
        if (isTyping) {
            val last = lastTypingPingAt[group.id] ?: 0L
            if (now - last < 2_000L) return
            lastTypingPingAt[group.id] = now
        } else {
            lastTypingPingAt.remove(group.id)
        }
        val senderName = runCatching { contactAliasResolver?.invoke(myPublicKey) }.getOrNull()
        val payload = buildGroupTypingPayload(
            senderPublicKey = myPublicKey,
            isTyping = isTyping,
            timestampMs = System.currentTimeMillis(),
            groupId = group.id,
            senderName = senderName
        )
        val ping = GroupMessage(
            id = UUID.randomUUID().toString(),
            groupId = group.id,
            senderPublicKey = myPublicKey,
            senderName = "Me",
            content = payload,
            timestamp = System.currentTimeMillis(),
            isFromMe = true
        )
        scope.launch {
            try {
                sendMessageToMembers(group, ping, ephemeral = true)
            } catch (e: Exception) {
                Log.w(TAG, "Group typing ping failed: ${e.message}")
            }
        }
    }

    /** groupId → last outbound "typing" ping, for the 2 s debounce. */
    private val lastTypingPingAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Handle incoming group message
     */
    fun handleIncomingGroupMessage(content: String, senderPublicKey: String) {
        if (!content.startsWith(GROUP_MESSAGE_PREFIX)) return

        // 🪦 Stale identity gate — drop group messages from a sender whose
        // key has been marked rotated. Bypass for "bot:" senders (those
        // keys are not user identities). Runs BEFORE parse so we don't
        // even allocate the JSON object for known-stale senders, and
        // BEFORE the auto-add-as-member logic that some downstream paths
        // perform — otherwise a rotated old key would silently re-attach
        // to the group's member list.
        if (!senderPublicKey.startsWith("bot:") &&
            StaleIdentityStore.get(context).isStale(senderPublicKey)) {
            Log.w(TAG, "🪦 Stale identity sender — dropping group message: ${senderPublicKey.take(12)}…")
            return
        }

        try {
            val jsonStr = content.removePrefix(GROUP_MESSAGE_PREFIX)
            val json = JSONObject(jsonStr)

            // 🔧 FIX: Normalize groupId to match the canonical ID stored in _groups.value.
            // iOS UUID strings are always uppercase; Android-created groups are lowercase.
            // Use case-insensitive lookup to find the matching group and use its stored id.
            val rawGroupId = json.getString("groupId")
            val groupId = _groups.value.find { it.id.equals(rawGroupId, ignoreCase = true) }?.id ?: rawGroupId
            // Support both Android "messageId" and iOS "id" field names
            val messageId = json.optString("messageId").takeIf { it.isNotEmpty() }
                ?: json.optString("id").takeIf { it.isNotEmpty() }
                ?: UUID.randomUUID().toString()
            // Support Android "content", iOS "plaintextContent", and iOS "encryptedContent" (base64).
            //
            // 🔴 P0 (2026-08-13): the media JSON must be resolved BEFORE the caption.
            // iOS carries the caption in `plaintextContent` (GroupMessaging.swift:2368) and the
            // media blob in `encryptedContent` (:2323-2336). The old ordered fallback let the
            // caption win, so the `{`-prefixed media branch below never fired and the image bytes
            // were dropped on the floor. See `resolveIncomingBody`.
            val decodedEncryptedContent = try {
                val encData = json.optString("encryptedContent", "")
                if (encData.isNotEmpty()) {
                    // iOS JSONEncoder encodes Data as base64 — decode it to get the original UTF-8 text
                    try {
                        String(Base64.decode(encData, Base64.DEFAULT), Charsets.UTF_8)
                    } catch (_: Exception) {
                        // If base64 decode fails, try using the raw string (might already be plaintext)
                        encData
                    }
                } else ""
            } catch (_: Exception) { "" }
            val contentField = json.optString("content", "")
            val plaintextField = json.optString("plaintextContent", "")
            var messageContent = resolveIncomingBody(contentField, plaintextField, decodedEncryptedContent)

            // 💬 Reply envelope — unwrap before anything else looks at the body, exactly like
            // iOS does on the group path (GroupViews.swift:2481 `MessageWithReply.fromMessageString`).
            var inboundReplyToId: String? = null
            parseReplyEnvelope(messageContent)?.let { parsed ->
                inboundReplyToId = parsed.replyToId
                messageContent = parsed.content
            }
            if (LOG_SENSITIVE) Log.d(TAG, "📩 Group message parsed: groupId=${groupId.take(8)}, id=${messageId.take(8)}, sender=${senderPublicKey.take(8)}, content=${messageContent.take(60)}")
            // Resolve sender display name: JSON field → contact alias → short public key
            val rawSenderName = json.optString("senderName", "")
            val senderName = when {
                rawSenderName.isNotEmpty() && rawSenderName != "Unknown" -> rawSenderName
                senderPublicKey.startsWith("bot:") -> senderPublicKey  // bot: shown as-is
                else -> contactAliasResolver?.invoke(senderPublicKey)
                    ?: senderPublicKey.take(8)  // fallback: first 8 chars of public key
            }
            // Support both Unix millis (Android) and Apple epoch seconds (iOS)
            val rawTimestamp = json.optDouble("timestamp", 0.0)
            val timestamp = if (rawTimestamp < 1_000_000_000_000.0) {
                // Likely Apple epoch seconds → convert to Unix millis
                ((rawTimestamp + 978307200.0) * 1000.0).toLong()
            } else {
                rawTimestamp.toLong()  // Already Unix millis
            }

            // __BLOCKED_MEMBER_DROP_2026_09_24__ Owner decision: a member I blocked is HIDDEN in
            // groups (WhatsApp-like), the same on iOS, Android and Desktop: their content is
            // dropped here, before anything is stored — no timeline row, no unread, no preview,
            // no banner, no reaction. Dropped rather than stored-and-filtered: this is the one
            // group-content funnel (V2 text/file land here too), so one gate covers every
            // surface; the cost is that unblocking does not bring the missed messages back.
            // System envelopes (systemMessageType: edits/deletes) and roster/key updates (other
            // prefixes, other paths) still go through.
            if (BlockedMemberGate.drops(senderPublicKey, json.optString("systemMessageType", ""), CallAdmissionPolicy::isBlockedCached)) {
                Log.d(TAG, "🚫 Dropping group content from a blocked member ${senderPublicKey.take(8)}… in ${groupId.take(8)}")
                return
            }

            // ── 📊 Poll ballot ────────────────────────────────────────────────
            //
            // The voter is [senderPublicKey] — the key that opened the envelope,
            // so a member cannot vote in another member's name. Never stored:
            // a ballot is silent in the catalog and has no bubble.
            if (com.oshi.messenger.data.model.PollVotePayload.isVoteMessage(messageContent)) {
                val vote = com.oshi.messenger.data.model.PollVotePayload.fromMessageString(messageContent)
                if (vote != null) {
                    // __ATTACH_BEFORE_THE_FIRST_BALLOT_2026_10_23__ `attach` is
                    // what opens the sealed file; without it `record` keeps the
                    // ballot in memory and `scheduleSave` has nowhere to write,
                    // so a vote that arrived before the user ever opened the
                    // poll was lost on the next launch. iOS has no equivalent
                    // hole: `PollStore.shared` loads in its initialiser.
                    com.oshi.messenger.service.PollStore.attach(context)
                    com.oshi.messenger.service.PollStore.record(vote, senderPublicKey)
                    Log.d(TAG, "📊 group ballot on poll ${vote.pollId.take(8)} from ${senderPublicKey.take(8)}")
                } else {
                    Log.w(TAG, "📊 unreadable group ballot: ${messageContent.take(60)}")
                }
                return  // Never store as visible message
            }

            // ── 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ Advanced chat privacy ──────
            //
            // Any member may switch it (WhatsApp parity), so this sits ABOVE the
            // channel-mode post filter: the state applies even in an admins-only
            // group. The author is [senderPublicKey] (the key that opened the
            // envelope). Stored as a READ row and never a banner; an unreadable
            // payload (future `v`) is dropped so it can never print as JSON.
            if (com.oshi.messenger.data.model.AdvancedChatPrivacyChange.isPayload(messageContent)) {
                AdvancedChatPrivacyReaders.note(senderPublicKey)
                val change = com.oshi.messenger.data.model.AdvancedChatPrivacyChange.fromMessageString(messageContent)
                if (change == null) {
                    Log.w(TAG, "🛡 unreadable advanced-privacy payload in ${groupId.take(8)} — dropped")
                    return
                }
                val store = AdvancedChatPrivacyStore
                store.attach(context)
                val nowSeconds = System.currentTimeMillis() / 1000L
                val applied = store.applyIncoming(change, groupId, nowSeconds)
                Log.d(TAG, "🛡 group ${groupId.take(8)} advanced privacy on=${change.on} by ${senderPublicKey.take(8)} (applied=$applied)")
                if (_groupMessages.value[groupId]?.any { it.id == messageId } == true) return
                val atMillis = com.oshi.messenger.data.model.AdvancedChatPrivacyChange
                    .clampedAt(change.atUnixSeconds, nowSeconds) * 1000L
                addMessageToGroup(
                    groupId,
                    GroupMessage(
                        id = messageId,
                        groupId = groupId,
                        senderPublicKey = senderPublicKey,
                        senderName = senderName,
                        content = messageContent,
                        timestamp = minOf(atMillis, System.currentTimeMillis()).takeIf { it > 0 } ?: timestamp,
                        isFromMe = false,
                        isRead = true,
                    )
                )
                updateGroupConversations()
                saveMessages()
                return
            }

            // ── iOS Reaction message: "🔥REACTION🔥{JSON}" ──────────────────────
            if (messageContent.startsWith(REACTION_PREFIX)) {
                val parsed = parseGroupReaction(messageContent, senderPublicKey)
                // __GROUP_SETTINGS_2026_11_20__ reactions allowed (spec §2.3): an `add` the
                // group does not allow is dropped; `remove` always passes.
                if (parsed != null && !parsed.isRemoval &&
                    settingsOf(groupId)?.isReactionAllowed(parsed.emoji) == false) {
                    Log.d(TAG, "🚫 reaction '${parsed.emoji}' not allowed in ${groupId.take(8)} — dropped")
                    return
                }
                if (parsed != null) {
                    applyReactionLocally(
                        groupId, parsed.messageId, parsed.senderPublicKey, parsed.emoji, parsed.isRemoval
                    )
                    Log.d(TAG, "🔥 Applied iOS reaction '${parsed.emoji}' on message ${parsed.messageId.take(8)} (removal=${parsed.isRemoval})")
                } else {
                    Log.w(TAG, "Failed to parse iOS reaction: ${messageContent.take(60)}")
                }
                return  // Never store as visible message
            }

            // ── ✏️ iOS "edit for everyone": a `.messageEdited` SYSTEM message ────
            // iOS emits an ordinary group-message envelope whose `systemMessageType` is
            // `message_edited` and whose `systemMessageData` carries `editedMessageId` +
            // `editedContent` (`GroupMessaging.swift:1330-1342`), and applies it at
            // `:3292-3307`. It is a COMMAND, never a visible bubble — hence the `return`,
            // matching iOS's own `return` at `:3307`. This branch sits beside the
            // reaction branch and above the system-payload filter for the same reason:
            // everything below assumes a body worth storing.
            parseGroupEditSystemMessage(json, timestamp)?.let { edit ->
                val applied = applyMessageEditLocally(
                    groupId, edit.editedMessageId, edit.newContent, edit.editedAt, actor = senderPublicKey
                )
                Log.d(
                    TAG,
                    if (applied) "✏️ Applied remote edit to ${edit.editedMessageId.take(8)}"
                    else "✏️ Remote edit for unknown/unchanged message ${edit.editedMessageId.take(8)}"
                )
                return
            }

            // ── 🗑️ iOS "delete for everyone": a `.messageDeleted` SYSTEM message ──
            // The mirror image of the edit branch above. iOS sends the retraction as an
            // ordinary group-message envelope whose `systemMessageType` is
            // `message_deleted` and whose `systemMessageData` carries `deletedMessageId`
            // (`GroupMessaging.swift:2407-2427`), and applies an inbound one at `:3273-3290`.
            // Android had no branch for it, so **an iPhone's delete-for-everyone never
            // reached an Android phone** — the envelope carries an empty body and fell
            // through to the filters below.
            //
            // Android tombstones where iOS removes the row outright. That is the existing
            // Android delete semantic (`applyDeletionToMessages` sets `isDeleted`), it is
            // what the local delete already does, and it renders as the standard
            // "message deleted" placeholder rather than a hole in the thread.
            parseGroupDeleteSystemMessage(json)?.let { deletedMessageId ->
                applyMessageDeletionLocally(groupId, deletedMessageId, actor = senderPublicKey)
                Log.d(TAG, "🗑️ Applied remote delete-for-everyone to ${deletedMessageId.take(8)}")
                return  // A command, never a visible bubble — iOS returns at :3289.
            }

            // ── Filter out iOS protocol/system payloads ──────────────────────────
            val isSystemPayload = messageContent.startsWith("\uD83D\uDD17LINKS_VISIBILITY\uD83D\uDD17") ||
                messageContent.startsWith("\uD83D\uDD27ACTION\uD83D\uDD27") ||
                messageContent.startsWith("⌨️TYPING⌨️") ||
                messageContent.startsWith("{\"type\":\"member_sync") ||
                messageContent.startsWith("{\"type\":\"sync_request") ||
                messageContent.startsWith("{\"type\":\"group_update")
            if (isSystemPayload) {
                Log.d(TAG, "⏭️ Skipping system payload: ${messageContent.take(40)}")
                return
            }
            // __GROUP_SETTINGS_2026_11_20__ channel mode (spec §2.4): content from a non-admin
            // is dropped when only admins may post. Edits/deletes/reactions/ballots returned above.
            // __GROUP_COMMENTS_2026_11_21__ spec §2.12.4: the one narrow exception — a comment
            // under an admin's post, when the admins turned comments on.
            var commentOfPost: String? = null
            val commentOn = GroupComments.read(json)
            if (commentOn != null) {
                val senderRateKey = gk(groupId) + "|" + GroupUpdateAuthorizer.canonicalIdentity(senderPublicKey)
                val (decision, parentMsg) = GroupComments.evaluate(
                    json = json, body = messageContent, sender = senderPublicKey,
                    settings = settingsOf(groupId),
                    stored = _groupMessages.value[groupId] ?: emptyList(),
                    isAdmin = { k -> isAdminOf(groupId, k) },
                    isEvicted = { k -> isEvictedFrom(groupId, k) },
                    isComment = ::isCommentId,
                    recentFromSender = commentReceiveRate.count(senderRateKey, System.currentTimeMillis()),
                )
                when (decision.outcome) {
                    GroupComments.Outcome.ACCEPT -> commentOfPost = parentMsg!!.id
                    GroupComments.Outcome.HOLD -> {
                        commentOrphans.hold(GroupComments.Orphan(groupId, commentOn, content, senderPublicKey, System.currentTimeMillis()))
                        Log.d(TAG, "💬 comment ${messageId.take(8)} held: post ${commentOn.take(8)} not here yet")
                        return
                    }
                    GroupComments.Outcome.DROP -> {
                        Log.d(TAG, "🚫 comment ${messageId.take(8)} from ${senderPublicKey.take(8)} dropped: ${decision.reason}")
                        return
                    }
                    GroupComments.Outcome.ORDINARY -> Unit
                }
            }
            if (commentOfPost == null && !isAllowedToPost(groupId, senderPublicKey)) {
                Log.d(TAG, "🚫 ${senderPublicKey.take(8)} may not post in ${groupId.take(8)} (admins only) — dropped")
                return
            }
            // Protocol envelopes accidentally stored as content (must have ciphertext/encryptedContent
            // but NOT be an iOS media JSON which also starts with "{")
            val looksLikeProtocolEnvelope = messageContent.trimStart().startsWith("{") &&
                (messageContent.contains("\"ciphertext\"") ||
                 (messageContent.contains("\"encryptedContent\"") && messageContent.contains("\"senderPublicKey\"")))
            if (looksLikeProtocolEnvelope) {
                Log.d(TAG, "⏭️ Skipping protocol envelope stored as group message: ${messageContent.take(60)}")
                return
            }

            // ── Parse iOS media JSON embedded in encryptedContent ─────────────────
            // iOS sends media as: encryptedContent = UTF-8({type:"media",mediaType:"photo",content:"base64",size:N})
            // The mediaType field in GroupMessage JSON is the iOS MediaType raw value ("photo","video","audio","document")
            var finalContent = messageContent
            var finalMediaType: MediaType? = null
            var finalMediaData: ByteArray? = null
            var finalMediaFileName: String? = null
            var innerNoteFlag: Boolean? = null
            var innerNoteDuration: Int? = null

            // iOS media embedded in encryptedContent as JSON blob
            if (messageContent.trimStart().startsWith("{") && messageContent.contains("\"type\":\"media\"")) {
                try {
                    val mediaJson = JSONObject(messageContent)
                    val iosMediaType = mediaJson.optString("mediaType", "")
                    val mediaContentB64 = mediaJson.optString("content", "")
                    // Caption channel differs per platform: Android puts it in the media JSON's
                    // `caption`, iOS in the message's `plaintextContent` (GroupMessaging.swift:2368).
                    // Read BOTH so neither side loses the words. A caption may itself be a reply
                    // envelope when the sender quoted something while attaching media.
                    val rawCaption = mediaJson.optString("caption", "").takeIf { it.isNotEmpty() }
                        ?: plaintextField.takeIf { it.isNotEmpty() && it != messageContent }
                        ?: contentField.takeIf { it.isNotEmpty() && it != messageContent }
                        ?: ""
                    finalContent = parseReplyEnvelope(rawCaption)?.let { parsed ->
                        if (inboundReplyToId == null) inboundReplyToId = parsed.replyToId
                        parsed.content
                    } ?: rawCaption
                    finalMediaType = mapIOSMediaType(iosMediaType)
                    // iOS `GroupMessage.mediaFileName` (GroupMessaging.swift:2542). Absent for a
                    // photo on both sides; present for a document, which is the only bubble that
                    // renders it.
                    finalMediaFileName = mediaJson.optString("fileName", "").takeIf { it.isNotEmpty() }
                    // __VIDEO_NOTE_2026_09_24__ spec §2.C shape inside the legacy blob.
                    innerNoteFlag = com.oshi.messenger.service.media.VideoNote.flagFrom(mediaJson)
                    innerNoteDuration = com.oshi.messenger.service.media.VideoNote.durationFrom(mediaJson)
                    if (mediaContentB64.isNotEmpty()) {
                        finalMediaData = Base64.decode(mediaContentB64, Base64.DEFAULT)
                    }
                    // 🔧 An unknown label must not cost the attachment. The bytes are
                    // already here and decoded; `mediaType = null` would draw them as a
                    // text bubble full of base64 and there is no second delivery. Same
                    // fallback iOS took after the 2026-09-12 audit: ask the bytes.
                    if (finalMediaType == null) {
                        finalMediaType = MediaType.sniff(finalMediaData)
                        if (finalMediaType != null) {
                            Log.w(TAG, "⚠️ Unknown group media type '$iosMediaType' — sniffed as ${finalMediaType?.name}")
                        }
                    }
                    Log.d(TAG, "📸 Parsed iOS media message: type=$iosMediaType → $finalMediaType, size=${finalMediaData?.size ?: 0}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse iOS media JSON: ${e.message}")
                }
            }

            // iOS location prefix: "📍LOCATION📍{...JSON...}"
            if (finalMediaType == null && messageContent.startsWith(LOCATION_PREFIX)) {
                finalContent = messageContent  // Keep full string; UI will parse the prefix
                finalMediaType = MediaType.LOCATION
                Log.d(TAG, "📍 Parsed iOS location message")
            }

            // Map iOS MediaType string from GroupMessage JSON field (for non-embedded media)
            if (finalMediaType == null) {
                val iosMediaTypeStr = json.optString("mediaType", "")
                finalMediaType = if (iosMediaTypeStr.isNotEmpty()) mapIOSMediaType(iosMediaTypeStr) else null
                // Fallback: check Android-style mediaData field
                if (finalMediaData == null) {
                    val mediaDataB64 = json.optString("mediaData", "")
                    if (mediaDataB64.isNotEmpty()) {
                        finalMediaData = try { Base64.decode(mediaDataB64, Base64.NO_WRAP) } catch (_: Exception) { null }
                    }
                }
            }

            // __GROUP_DOC_NAME_2026_09_27__ An iPhone's V2 group file has no media JSON
            // (its `encryptedContent` is emptied) and carries the name at the TOP level
            // of the GroupMessage (GroupMessaging.swift:2853-2865) — the read above never
            // saw it, so every iPhone group document landed nameless.
            if (finalMediaFileName == null) {
                finalMediaFileName = json.optString("mediaFileName", "")
                    .takeIf { it.isNotBlank() }
                    ?.let { com.oshi.messenger.util.AttachmentFileName.sanitize(it) ?: it }
            }

            // __GROUP_LIVE_LOCATION_2026_09_27__ A live-location update or stop that
            // arrives as a NEW message (older Android builds post every ~10 s tick
            // that way) updates the sender's existing live tile in place instead of
            // stacking a bubble per tick. See [liveLocationMergeTarget].
            if (finalContent.startsWith(LOCATION_PREFIX)) {
                val current = _groupMessages.value[groupId] ?: emptyList()
                val target = liveLocationMergeTarget(current, senderPublicKey, finalContent)
                if (target >= 0) {
                    applyMessageEditLocally(groupId, current[target].id, finalContent, timestamp, actor = senderPublicKey)
                    Log.d(TAG, "📍 Merged live-location update into ${current[target].id.take(8)}")
                    return
                }
            }

            // ── Deduplicate ───────────────────────────────────────────────────────
            val existingMessages = _groupMessages.value[groupId] ?: emptyList()
            // Case-insensitive: the same message re-broadcast by an iPhone arrives with an
            // UPPERCASE uuidString. A `==` here fails OPEN (a duplicate bubble), not closed.
            if (existingMessages.indexOfMessageId(messageId) >= 0) return

            // 🤖 Inline keyboard — the bot platform's `replyMarkup`, carried verbatim.
            // Only a bot may attach one: a keyboard on a message from a human sender
            // would let any group member draw a button that dispatches to a bot on
            // another member's behalf, so the gate is on the SENDER, not on the field.
            // Stored as raw text; `BotReplyMarkupParser` is the only thing that reads it
            // and it validates at render time.
            val isBotSender = senderPublicKey.startsWith("bot:")
            val inboundReplyMarkup = if (isBotSender) {
                json.optJSONObject("replyMarkup")?.toString()
            } else null
            // __BOT_PUBLIC_ID_2026_09_16__ Same sender gate as the keyboard, for the
            // same reason: this id is what a button authorises a dispatch with, so a
            // human sender must not be able to attach one and make a member's phone
            // dispatch as though the message came from a bot.
            val inboundBotId = if (isBotSender) {
                json.optString("botId", "").takeIf { it.isNotEmpty() }
            } else null

            // __MENTIONS_2026_09_23__ keep only current members whose `@name` is visible in the text.
            val mentionGroup = _groups.value.find { it.id.equals(groupId, ignoreCase = true) }
            val admittedMentions = if (mentionGroup == null) emptyList() else com.oshi.messenger.util.MentionWire.admit(
                body = com.oshi.messenger.util.MessageFormatting.strip(messageContent), // __TEXT_FORMATTING_2026_11_24__ `__@bob__`
                mentions = com.oshi.messenger.util.MentionWire.parse(json.opt(com.oshi.messenger.util.MentionWire.FIELD)),
                isMember = { k -> mentionGroup.memberPublicKeys.any { sameMentionKey(it, k) } },
                sameKey = ::sameMentionKey
            )
            recordMentions(messageId, admittedMentions)
            val mentionsMe = com.oshi.messenger.util.MentionWire.mentions(
                cryptoManager.getPublicKeyString(), admittedMentions, ::sameMentionKey
            )
            if (mentionsMe) markMentioned(groupId)

            val noteMerge = com.oshi.messenger.service.media.VideoNote.merge(
                fileKeyFlag = com.oshi.messenger.service.media.VideoNote.flagFrom(json),
                fileKeyDurationMs = com.oshi.messenger.service.media.VideoNote.durationFrom(json),
                innerFlag = innerNoteFlag, innerDurationMs = innerNoteDuration,
            )
            val message = GroupMessage(
                id = messageId,
                groupId = groupId,
                senderPublicKey = senderPublicKey,
                senderName = senderName,
                content = if (commentOfPost != null) GroupComments.clamp(finalContent) else finalContent,
                timestamp = timestamp,
                isFromMe = false,
                // A comment never counts as unread in the group (§2.12.5).
                isRead = commentOfPost != null,
                mediaType = finalMediaType,
                mediaData = finalMediaData,
                mediaFileName = finalMediaFileName,
                // __VIDEO_NOTE_2026_09_24__ spec §1 rule 5: note if the GroupMessage JSON
                // (top level — where `dispatchV2GroupFile` also folds the file-key flags)
                // OR the legacy media blob says so; rule 1: only a VIDEO row.
                mediaDurationMs = noteMerge.second?.toLong()?.takeIf { noteMerge.first && finalMediaType == MediaType.VIDEO },
                replyToId = inboundReplyToId,
                replyMarkup = inboundReplyMarkup,
                botId = inboundBotId,
                isVideoNote = noteMerge.first && finalMediaType == MediaType.VIDEO
            )

            if (commentOfPost != null) {
                // Index first, so the timeline never draws it as a post for one frame.
                recordCommentLink(message.id, commentOfPost)
                commentReceiveRate.record(gk(groupId) + "|" + GroupUpdateAuthorizer.canonicalIdentity(senderPublicKey), System.currentTimeMillis())
            }
            addMessageToGroup(groupId, message)
            updateGroupConversations()
            saveMessages()

            if (commentOfPost != null) {
                notifyCommentIfForMe(groupId, message, mentionsMe)
                return
            }
            // A post that comments were waiting for (§2.12.4 orphans): run them again.
            releaseCommentOrphans(groupId, messageId)

            // __GROUP_SELF_HEAL_2026_10_01__ We decrypted this message (its content key was
            // unwrapped over our 1:1 ratchet, which survives an app-data wipe) but we have no
            // local definition for the group. Ask the sender — the one member we can identify —
            // for the group definition; their member_sync_request answer recreates the group and
            // the message then shows. Throttled per group. Mirrors iOS receiveGroupMessage.
            if (_groups.value.none { it.id.equals(groupId, ignoreCase = true) } &&
                !senderPublicKey.startsWith("bot:") && senderPublicKey != "SYSTEM" &&
                tombstoneCache?.contains(groupId) != true &&
                shouldSelfHealRequest(groupId)) {
                scope.launch {
                    val myPk = cryptoManager.getPublicKeyString()
                    val syncReq = JSONObject().apply {
                        put("type", "member_sync_request")
                        put("groupId", groupId)
                        put("requesterPublicKey", myPk)
                    }
                    Log.d(TAG, "🩹 [self-heal] unknown group ${groupId.take(8)} — requesting definition from sender ${senderPublicKey.take(8)}")
                    sendGroupUpdateRaw("$GROUP_UPDATE_PREFIX$syncReq", listOf(senderPublicKey))
                }
            }

            // __BOT_IN_THE_PIPELINE_2026_11_09__ The inbound half (iOS
            // GroupMessaging.swift:3714-3728): another member's `/help` reaches the
            // bots this device owns. The caption, not the media JSON, is the input.
            // Our own messages are skipped as iOS does (they already ran on send).
            if (senderPublicKey != cryptoManager.getPublicKeyString()) {
                onGroupMessageForBots?.invoke(
                    groupId, finalContent, senderPublicKey,
                    json.optString(BOT_AUTHOR_FIELD, "").isNotEmpty()
                )
            }

            // Show local notification if group is not muted
            val groupForNotif = _groups.value.find { it.id.equals(groupId, ignoreCase = true) }
            // The mute check stays HERE as an early-out — it is cheaper than running the
            // presentation catalog for a group that will never ring — and is repeated
            // inside `showGroupMessageNotification` through `shouldRaiseGroupBanner`,
            // which is where the on-screen gate lives and where both conditions are
            // unit-tested together.
            // __MENTIONS_2026_09_23__ an admitted `@you` rings even in a muted group.
            // __BLOCKED_MEMBER_BANNER_2026_09_23__ A member I blocked is still shown in
            // the group (iOS parity) but never rings: the FCM path already drops their
            // push by sender key; this local banner was the one path that still rang.
            if (groupForNotif != null && (!groupForNotif.isMuted || mentionsMe) &&
                !com.oshi.messenger.data.repository.MessageRepository.isSenderBlockedCached(senderPublicKey)) {
                // H-2 — the group half of H-1, and iOS calls this path out by name
                // (`PushNotificationManager.swift:188-190`: "this is the GROUP
                // banner, which is where the reported reaction/call JSON surfaced
                // most often"). Same two defects as the 1:1 path:
                // `MessageSanitizer` has no `💬REPLY💬` and no `📢GROUP_UPDATE📢`
                // branch and hands both back verbatim, and the empty-string fallback
                // after it turned the catalog's "never show this" into a banner.
                // Asked on the RAW content, through the one catalog.
                val decision = NotificationPresentation.decideCapped(
                    raw = messageContent,
                    labels = OSHIControlPayload.labels(context),
                    attachmentBody = context.getString(R.string.notification_sent_attachment)
                )
                if (decision is NotificationPresentation.Decision.Show) {
                    showGroupMessageNotification(
                        groupId, groupForNotif.name, senderName, decision.body,
                        isMuted = groupForNotif.isMuted && !mentionsMe
                    )
                }
                // Suppress ⇒ no call at all: a group key rotation, a roster sync or
                // a read-receipt batch must not ring anyone.
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse group message: ${e.message}")
        }
    }

    /**
     * Handle group update notification
     */
    fun handleGroupUpdate(content: String, senderPublicKey: String) {
        if (!content.startsWith(GROUP_UPDATE_PREFIX)) return

        try {
            val jsonStr = content.removePrefix(GROUP_UPDATE_PREFIX)
            val json = JSONObject(jsonStr)

            // 📢 Full iOS `MessageGroup` payload — here `type` is the GROUP type
            // ("admin_only"/"collaborative"/"public"), not an update kind, so it must be routed
            // before the `when` below (which would otherwise silently match nothing).
            if (looksLikeIosMessageGroup(json)) {
                applyFullMessageGroupJson(json, senderPublicKey)
                return
            }

            val updateType = json.getString("type")
            when (updateType) {
                "created" -> handleGroupCreated(json, senderPublicKey)
                "updated" -> handleGroupInfoUpdated(json, senderPublicKey)
                "member_added" -> handleMemberAdded(json, senderPublicKey)
                "member_removed" -> handleMemberRemoved(json, senderPublicKey)
                "deleted" -> handleGroupDeletedNotification(json, senderPublicKey)
                "key_rotation" -> handleKeyRotation(json, senderPublicKey)
                "read_receipt" -> handleReadReceipt(json)
                "read_receipt_batch" -> handleReadReceiptBatch(json)
                "reaction" -> handleReactionUpdate(json)
                "message_deleted" -> handleMessageDeleted(json, senderPublicKey)
                "admin_promoted" -> handleAdminPromoted(json, senderPublicKey)
                "admin_demoted" -> handleAdminDemoted(json, senderPublicKey)
                "sync_request" -> handleGroupSyncRequest(json, senderPublicKey)
                // An iPhone asking us for the roster (iOS emits this at swift:1722). Previously
                // unhandled, so the iPhone got silence. Mirror iOS's own handler (swift:941):
                // auto-add the requester, then reply with the full group.
                "member_sync_request" -> handleMemberSyncRequest(json, senderPublicKey)
                // __GROUP_SETTINGS_2026_11_20__ public-link join (GROUP_INFO_SETTINGS_SPEC §3.1)
                "join_request" -> handleJoinRequest(json, senderPublicKey)
                "wallpaper_changed" -> handleWallpaperChanged(json, senderPublicKey)
                "group_picture_changed" -> handleGroupPictureChanged(json, senderPublicKey)
                "group_renamed" -> {
                    val gId = json.optString("groupId", "")
                    val newName = json.optString("name", "")
                    if (gId.isNotEmpty() && newName.isNotEmpty()) {
                        applyiOSGroupUpdateBroadcast(gId, newName, null, senderPublicKey = senderPublicKey)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse group update: ${e.message}")
        }
    }

    /**
     * Apply a full iOS `MessageGroup` payload that arrived on the `📢GROUP_UPDATE📢` channel.
     *
     * Same field set as `MessageGroup.init(from:)` (GroupMessaging.swift:178-252). The IPFS
     * variant of this payload is unwrapped by `VPSClient.handleiOSGroupUpdateBroadcast` and
     * arrives through [applyiOSGroupUpdateBroadcast] instead.
     */
    private fun applyFullMessageGroupJson(groupJson: JSONObject, senderPublicKey: String? = null) {
        val groupId = groupJson.optString("id", "")
        if (groupId.isEmpty()) return
        // __GROUP_E2E_V2_2026_09_23__ GROUP_E2E_V2_SPEC §5.3: a definition older than ours is a
        // replay. Compared only when BOTH sides carry a version.
        val incomingVersion = if (groupJson.has("stateVersion") && !groupJson.isNull("stateVersion"))
            groupJson.optInt("stateVersion") else null
        if (!stateVersionAccepts(stateVersionOf(groupId), incomingVersion)) {
            Log.w(TAG, "🛑 stale group definition v$incomingVersion < v${stateVersionOf(groupId)} for ${groupId.take(8)} — ignored")
            return
        }
        val name = groupJson.optString("name", "").ifEmpty { null }
        val membersArray = groupJson.optJSONArray("members")
        val membersJson = membersArray?.toString()

        // The unknown-group membership guard now lives in applyiOSGroupUpdateBroadcast itself, so
        // the VPS/IPFS route (which calls that function directly) is covered too — audit G-3.
        val pictureBytes = groupJson.optString("groupPictureData", "").let { b64 ->
            if (b64.isNotEmpty()) try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { null } else null
        }
        val wallpaperBytes = groupJson.optString("groupWallpaperData", "").let { b64 ->
            if (b64.isNotEmpty()) try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { null } else null
        }
        applyiOSGroupUpdateBroadcast(
            groupId = groupId,
            groupName = name,
            membersJson = membersJson,
            groupImageBytes = pictureBytes,
            adminPublicKey = groupJson.optString("adminPublicKey", "").ifEmpty { null },
            groupType = groupJson.optString("type", "").ifEmpty { null },
            groupWallpaperBytes = wallpaperBytes,
            groupWallpaperUpdatedBy = groupJson.optString("groupWallpaperUpdatedBy", "").ifEmpty { null },
            groupWallpaperUpdatedAtMs = parseIso8601(groupJson.optString("groupWallpaperUpdatedAt", "")),
            pinnedMessageId = groupJson.optString("pinnedMessageId", "").ifEmpty { null },
            hasPinnedField = groupJson.has("pinnedMessageId"),
            pinnedBy = groupJson.optString("pinnedBy", "").ifEmpty { null },
            avatar = groupJson.optString("avatar", "").ifEmpty { null },
            hasAvatarField = groupJson.has("avatar"),
            // iOS drops `createdAt` on the floor here today; without it the group is stamped
            // with `now` and jumps to the top of the list on every sync (audit G-14).
            createdAtMs = parseIso8601(groupJson.optString("createdAt", "")),
            // The union happens in `applyiOSGroupUpdateBroadcast`; this only carries it.
            incomingEvictedKeys = groupJson.optJSONArray("evictedMemberKeys")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i, "").takeIf { k -> k.isNotEmpty() } }
            },
            senderPublicKey = senderPublicKey,
            // __GROUP_SETTINGS_2026_11_20__ the full definition's description was never applied
            // here (only the Android-minimal `updated` kind did), so an iOS/Desktop description
            // never reached Android. Absent = keep (spec §2.2).
            incomingDescription = if (groupJson.has("description") && !groupJson.isNull("description"))
                groupJson.optString("description") else null,
            incomingSettings = com.oshi.messenger.data.model.GroupSettings.parseOrNull(groupJson.optJSONObject("settings"))
        )
        // Adopt the version only from an authenticated sender who is (now) a member: a
        // stranger must not be able to push our counter up and freeze the group.
        if (incomingVersion != null && senderPublicKey != null) {
            val g = _groups.value.find { it.id.equals(groupId, ignoreCase = true) }
            val senderCanon = GroupUpdateAuthorizer.canonicalIdentity(senderPublicKey)
            if (g != null && g.memberPublicKeys.any { GroupUpdateAuthorizer.canonicalIdentity(it) == senderCanon } &&
                // __GROUP_TYPE_TAKEOVER_2026_11_20__ and never a jump of more than 1024.
                GroupUpdateAuthorizer.isPlausibleVersionStep(stateVersionOf(groupId), incomingVersion) &&
                // __GROUP_TOMBSTONE_AUTHZ_2026_11_20__ and only from someone who may change the roster.
                GroupUpdateAuthorizer.mayAdoptStateVersion(
                    GroupUpdateAuthorizer.authorize(
                        currentMemberKeys = g.memberPublicKeys,
                        currentAdminKeys = g.adminPublicKeys,
                        currentType = g.effectiveGroupType,
                        sender = senderPublicKey
                    )
                )
            ) {
                adoptStateVersion(groupId, incomingVersion)
            }
        }
        // __GROUP_MEMBER_NAMES_2026_09_23__ keep the definition's member aliases (spec §5.1) as
        // the LAST fallback for the group info screen — only for a group we hold, from a member.
        if (senderPublicKey != null) {
            val g = _groups.value.find { it.id.equals(groupId, ignoreCase = true) }
            val senderCanon = GroupUpdateAuthorizer.canonicalIdentity(senderPublicKey)
            if (g != null && g.memberPublicKeys.any { GroupUpdateAuthorizer.canonicalIdentity(it) == senderCanon }) {
                runCatching {
                    com.oshi.messenger.data.model.GroupMemberAliasStore.record(
                        context, groupId,
                        com.oshi.messenger.data.model.GroupMemberNames.aliasesFromMembersJson(membersJson),
                    )
                }
            }
        }
    }

    /**
     * `member_sync_request` — reply with the full roster, IF the request is
     * authorised to ask.
     *
     * __MEMBER_SYNC_AUTHZ_2026_11_02__ What this used to do: take
     * `requesterPublicKey` from the BODY, fall back to the sender, and if that
     * key was not in the group, add it and send back the whole roster. The
     * comment said "a requester holding the group is legitimate" — but nothing
     * checked that they held it, that the key was theirs, or that we were
     * allowed to add anyone. iOS's twin
     * (`GroupMessaging.swift:1336-1371`) made all four checks; Android made
     * none, so a contact who learned a group id could insert ANY public key
     * into this device's copy of that group and read the roster back.
     *
     * The decision now lives in
     * [GroupUpdateAuthorizer.authorizeMemberSyncRequest], where it is unit
     * tested, because this is authorisation and not plumbing.
     *
     * __EVICTED_LIST_NEVER_EXISTED_2026_11_04__ The note that used to sit here
     * said the Android `Group` had no `evictedMemberKeys`, so this rule was
     * handed an empty list and never fired -- "enforced for whoever adds the
     * field". `Group.evictedMemberKeys` exists now and is passed below, so the
     * rule is live. It turned out to be far from "the weaker of the four": it
     * is the one behind the oscillating member count.
     */
    private fun handleMemberSyncRequest(json: JSONObject, senderPublicKey: String) {
        val groupId = json.optString("groupId", "")
        if (groupId.isEmpty()) return
        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index < 0) return
        val me = cryptoManager.getPublicKeyString() ?: ""

        val decision = GroupUpdateAuthorizer.authorizeMemberSyncRequest(
            sender = senderPublicKey,
            requesterFromBody = json.optString("requesterPublicKey", "").takeIf { it.isNotEmpty() },
            currentMemberKeys = currentGroups[index].memberPublicKeys,
            evictedMemberKeys = currentGroups[index].evictedMemberKeys,
            weMayAddMembers = me.isNotEmpty() && currentGroups[index].canAddMembers(me),
        ).let { base ->
            // __GROUP_SETTINGS_2026_11_20__ a reset invite link (spec §3.2/§3.3)
            GroupUpdateAuthorizer.authorizeInviteToken(
                base, settingsOf(currentGroups[index].id),
                json.optString("inviteToken", "").ifEmpty { null }
            )
        }
        if (!decision.answer) {
            Log.w(TAG, "🛑 member_sync_request from ${senderPublicKey.take(8)} ignored (${decision.reason})")
            return
        }
        if (decision.addRequester) {
            currentGroups[index] = currentGroups[index].copy(
                memberPublicKeys = currentGroups[index].memberPublicKeys + senderPublicKey,
                updatedAt = System.currentTimeMillis()
            )
            _groups.value = currentGroups
            saveGroups()
            updateGroupConversations()
            Log.d(TAG, "🔧 Added sync requester ${senderPublicKey.take(8)} to '${currentGroups[index].name}' (${decision.reason})")
        }
        val group = currentGroups[index]
        scope.launch { broadcastFullGroupUpdate(group, bumpVersion = false) }
    }

    // =========================================================================
    // GROUP SYNC (cross-device / cross-platform)
    // =========================================================================

    /**
     * Send a sync_request to each peer so they reply with any groups we're a member of.
     * Called on startup after contacts are loaded (with a delay for VPS to stabilize).
     */
    fun requestGroupSync(peers: List<String> = emptyList()) {
        scope.launch {
            val myPublicKey = cryptoManager.getPublicKeyString()
            val requestJson = JSONObject().apply {
                put("type", "sync_request")
                put("requesterKey", myPublicKey)
            }
            val content = "$GROUP_UPDATE_PREFIX$requestJson"

            // __GROUP_E2E_V2_2026_09_23__ v2 only, to the peers we were given. The mesh
            // broadcast sent this frame in CLEARTEXT to every connected device.
            if (peers.isNotEmpty()) sendGroupUpdateRaw(content, peers)
        }
    }

    /**
     * Request a full state sync for a specific group from its members.
     * Used when opening a group chat — iOS members will respond with broadcastFullGroupUpdate.
     */
    fun requestGroupStateSync(groupId: String) {
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return
        val myPublicKey = cryptoManager.getPublicKeyString()
        val requestJson = JSONObject().apply {
            put("type", "sync_request")
            put("requesterKey", myPublicKey)
            put("groupId", groupId)
        }
        val content = "$GROUP_UPDATE_PREFIX$requestJson"
        scope.launch {
            sendGroupUpdateRaw(content, group.memberPublicKeys.filter { it != myPublicKey })
        }
    }

    /**
     * Handle an incoming sync_request: reply with all groups where requesterKey is a member.
     * Works for both Android↔Android and iOS→Android initiated syncs.
     */
    private fun handleGroupSyncRequest(json: JSONObject, senderPublicKey: String) {
        val requesterKey = json.optString("requesterKey", senderPublicKey)
            .takeIf { it.isNotEmpty() } ?: senderPublicKey
        scope.launch {
            val groupsForRequester = _groups.value.filter { group ->
                group.memberPublicKeys.contains(requesterKey)
            }
            Log.d(TAG, "🔄 Sync request from ${requesterKey.take(8)}...: found ${groupsForRequester.size} group(s)")
            groupsForRequester.forEach { group ->
                val updateJson = JSONObject().apply {
                    put("type", "created")
                    put("groupId", group.id)
                    put("name", group.name)
                    put("description", group.description)
                    put("creatorPublicKey", group.creatorPublicKey)
                    put("members", JSONArray(group.memberPublicKeys))
                    put("admins", JSONArray(group.adminPublicKeys))
                    put("isPublic", group.isPublic)
                    put("groupType", group.effectiveGroupType.value)
                    group.encryptionKey?.let { key ->
                        put("encryptionKey", android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP))
                    }
                    group.groupImage?.let { img ->
                        put("groupImage", android.util.Base64.encodeToString(img, android.util.Base64.NO_WRAP))
                    }
                }
                val content = "$GROUP_UPDATE_PREFIX$updateJson"
                try {
                    // Full iOS `MessageGroup` first — an iPhone ignores `created` (`default:`
                    // swift:992) and only ever learns the roster from this shape.
                    // __GROUP_E2E_V2_2026_09_23__ v2 only; an ANSWER carries the current
                    // version unchanged (GROUP_E2E_V2_SPEC §5.2).
                    sendGroupUpdateRaw(buildFullGroupUpdateContent(group, includeBlobs = false), listOf(requesterKey))
                    sendGroupUpdateRaw(content, listOf(requesterKey))
                    Log.d(TAG, "🔄 Sent group '${group.name}' to ${requesterKey.take(8)}...")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send sync response for '${group.name}': ${e.message}")
                }
            }
        }
    }

    /**
     * Re-announce membership in all public groups to iOS peers.
     * Call this after mesh connects or at startup to ensure iOS has Android in its members list.
     * iOS responds to member_sync_request by adding the requester and sending broadcastFullGroupUpdate.
     */
    fun reannounceGroupMembership() {
        val myPublicKey = cryptoManager.getPublicKeyString()
        val publicGroups = _groups.value.filter { it.isPublic }
        if (publicGroups.isEmpty()) return

        scope.launch {
            publicGroups.forEach { group ->
                // Send member_sync_request via mesh (if admin is reachable)
                // iOS handles this by auto-adding requester + sending full group update back
                val adminKey = group.creatorPublicKey.takeIf { it.isNotEmpty() }
                    ?: group.adminPublicKeys.firstOrNull()
                    ?: return@forEach

                // __GROUP_E2E_V2_2026_09_23__ v2 only (was: cleartext mesh frame + IPFS pin).
                // Still paced by mesh reachability, as before, so this does not become a
                // request per public group on every poll.
                if (adminKey != myPublicKey && crossPlatformMesh.isPeerReachable(adminKey)) {
                    val syncReq = JSONObject().apply {
                        put("type", "member_sync_request")
                        put("groupId", group.id)
                        put("requesterPublicKey", myPublicKey)
                    }
                    sendGroupUpdateRaw("$GROUP_UPDATE_PREFIX$syncReq", listOf(adminKey))
                }
            }
        }
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    /** Post a local-only system notification message (join/leave/promote etc.) */
    private fun postSystemMessage(groupId: String, text: String) {
        val message = GroupMessage(
            id = UUID.randomUUID().toString(),
            groupId = groupId,
            senderPublicKey = "system",
            senderName = "system",
            content = text,
            timestamp = System.currentTimeMillis(),
            isFromMe = false
        )
        addMessageToGroup(groupId, message)
        saveMessages()
    }

    /**
     * Retroactively resolve sender names for all stored messages that have empty/unknown names.
     * Call this after contactAliasResolver is set (at MessageRepository init time).
     */
    fun refreshAllSenderNames() {
        val resolver = contactAliasResolver ?: return
        val current = _groupMessages.value.toMutableMap()
        var anyChanged = false
        current.forEach { (groupId, messages) ->
            val updated = messages.map { msg ->
                if (!msg.isFromMe
                    && !msg.senderPublicKey.startsWith("bot:")
                    && msg.senderPublicKey != "system") {
                    val alias = runCatching { resolver(msg.senderPublicKey) }.getOrNull()
                    // Use alias if available; fall back to existing senderName or short key
                    val name = alias
                        ?: if (msg.senderName.isNotEmpty() && msg.senderName != "Unknown") msg.senderName
                           else msg.senderPublicKey.take(8)
                    if (name != msg.senderName) {
                        anyChanged = true
                        msg.copy(senderName = name)
                    } else msg
                } else msg
            }
            current[groupId] = updated
        }
        if (anyChanged) {
            _groupMessages.value = current
            saveMessages()
            updateGroupConversations()
        }
    }

    /**
     * Mark all messages in a group as read (called when user opens the conversation).
     *
     * ✅ PERF: offload to the IO scope. With thousands of unread messages on entry, the
     * O(n) `copy(isRead = true)` loop + map reallocation took 100-300 ms on the main
     * thread, blocking the first frame of GroupChatScreen. The on-screen UI doesn't need
     * the new isRead values rendered synchronously (no per-message read indicator), so we
     * can let the StateFlow update arrive on the next composition.
     */
    fun markGroupMessagesAsRead(groupId: String) {
        clearMentioned(groupId)   // __MENTIONS_2026_09_23__
        // (N10, Anatole 13/11) The group's banner goes when the group is read —
        // before the early return below, so an already-read group still clears
        // a leftover banner. Opening the APP no longer clears the shade
        // (MainActivity), so this is now the only thing that does for groups.
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(MessageBanner.groupNotificationId(groupId))
        }
        // Cheap pre-check on the caller's thread: if there's nothing unread, do nothing.
        // Avoids paying the dispatch + scope launch cost on the common "open already-read
        // chat" path.
        val current = _groupMessages.value[groupId] ?: return
        val anyUnread = current.any { !it.isRead && !it.isFromMe }
        if (!anyUnread) return

        scope.launch {
            val currentMessages = _groupMessages.value.toMutableMap()
            val groupMsgs = currentMessages[groupId]?.toMutableList() ?: return@launch
            var changed = false
            // 📑 __GROUP_RECEIPTS_2026_11_02__ (N8) collected here, sent below
            // only if something actually flipped, so re-opening a read thread
            // sends nothing.
            val newlyRead = mutableListOf<Pair<String, String>>()
            for (i in groupMsgs.indices) {
                if (!groupMsgs[i].isRead && !groupMsgs[i].isFromMe) {
                    newlyRead += groupMsgs[i].id to groupMsgs[i].senderPublicKey
                    groupMsgs[i] = groupMsgs[i].copy(isRead = true)
                    changed = true
                }
            }
            for ((msgId, sender) in newlyRead) {
                sendGroupReceipt(GroupReceipts.Kind.READ, groupId, msgId, sender)
            }
            if (changed) {
                currentMessages[groupId] = groupMsgs
                _groupMessages.value = currentMessages
                saveMessages()
                updateGroupConversations()
            }
        }
    }

    private fun addMessageToGroup(groupId: String, message: GroupMessage) {
        val currentMessages = _groupMessages.value.toMutableMap()
        val groupMsgs = currentMessages[groupId]?.toMutableList() ?: mutableListOf()
        groupMsgs.add(message)
        groupMsgs.sortBy { it.timestamp }
        currentMessages[groupId] = groupMsgs
        _groupMessages.value = currentMessages

        // 📑 __GROUP_RECEIPTS_2026_11_02__ (N8) Sent at the point the message is
        // STORED, not when it decrypted, so "received" means the user can see
        // it rather than that bytes arrived.
        if (!message.isFromMe) {
            sendGroupReceipt(GroupReceipts.Kind.RECEIVED, groupId, message.id, message.senderPublicKey)
        }
    }

    /**
     * @param ephemeral true for pings that must never wake a phone with a banner
     *        (typing). Suppresses the push fan-out at the end of this function.
     */
    private suspend fun sendMessageToMembers(
        group: Group,
        message: GroupMessage,
        ephemeral: Boolean = false,
        /** __BOT_IN_THE_PIPELINE_2026_11_09__ [BOT_AUTHOR_FIELD] value; null for a human message. */
        botAuthor: String? = null,
        /** __GROUP_COMMENTS_2026_11_21__ post id for a comment (text only, §2.12.2). */
        commentOn: String? = null,
        /** Comment push rule (§2.12.3): wake only these members; null = the usual [ephemeral] rule. */
        wakeOnly: Set<String>? = null,
        /** __GROUP_RESEND_2026_11_25__ resend to these members only (a pending entry's list). */
        onlyMembers: Set<String>? = null
    ) {
        val myPublicKey = cryptoManager.getPublicKeyString()
        // __GROUP_RESEND_2026_11_25__ a message stored in the thread is tracked until every
        // recipient got it (typing / ballot pings and reactions are not rows, not tracked).
        val tracked = !ephemeral && isStoredGroupMessage(group.id, message.id)
        val outcome = DeliveryOutcome()
        if (tracked && onlyMembers == null) {
            val stored = findStoredGroupMessage(group.id, message.id)
            pendingDelivery.begin(
                msgId = message.id, groupId = group.id,
                recipients = groupRecipientKeys(group, null),
                wireContent = message.content.takeIf { stored != null && it != stored.content },
                botAuthor = botAuthor, commentOn = commentOn, wakeOnly = wakeOnly,
            )
            publishPendingDelivery()
        }
        if (tracked) sendsInFlight.add(message.id)
        try {
            sendMessageToMembersInner(group, message, ephemeral, botAuthor, commentOn, wakeOnly, onlyMembers, outcome)
        } finally {
            if (tracked) {
                sendsInFlight.remove(message.id)
                pendingDelivery.settle(message.id, outcome.reached, outcome.noBundle)
                publishPendingDelivery()
            }
        }
    }

    private suspend fun sendMessageToMembersInner(
        group: Group,
        message: GroupMessage,
        ephemeral: Boolean,
        botAuthor: String?,
        commentOn: String?,
        wakeOnly: Set<String>?,
        onlyMembers: Set<String>?,
        outcome: DeliveryOutcome
    ) {
        val myPublicKey = cryptoManager.getPublicKeyString()
        val appleTimestamp = (message.timestamp / 1000.0) - 978307200.0

        // Build iOS-compatible GroupMessage JSON (must match iOS GroupMessage Codable struct)
        // iOS media encoding: if mediaData present, encryptedContent = UTF-8({type:"media",mediaType:"photo",...})
        // iOS location encoding: encryptedContent = UTF-8("📍LOCATION📍{JSON}")
        // __MEDIA_SEND_OOM_2026_11_11__ LAZY. Media goes out through the v2 blob store
        // (below); this inline copy — base64 of the whole file, 2.7x its size as a String —
        // is read only by the legacy-compat envelope for old builds and by the text path.
        // Built eagerly, a 26 MB group document KILLED the app (FATAL OutOfMemoryError in
        // Base64.encodeToString, release 1.6.93, 192 MB heap) before anything was sent.
        val encryptedContentBytes: ByteArray by lazy { when {
            message.mediaData != null && message.mediaType != null && message.mediaType != MediaType.LOCATION -> {
                // Encode as iOS media JSON blob inside encryptedContent
                val iosMediaType = when (message.mediaType) {
                    MediaType.IMAGE -> "photo"
                    MediaType.VIDEO -> "video"
                    MediaType.AUDIO -> "audio"
                    MediaType.DOCUMENT -> "document"
                    else -> message.mediaType.name.lowercase()
                }
                buildGroupMediaJson(
                    iosMediaType = iosMediaType,
                    contentBase64 = Base64.encodeToString(message.mediaData, Base64.NO_WRAP),
                    size = message.mediaData.size,
                    caption = message.content,
                    fileName = message.mediaFileName,
                    videoNote = message.isVideoNote,
                    durationMs = noteWireDuration(message)
                ).toByteArray(Charsets.UTF_8)
            }
            else -> message.content.toByteArray(Charsets.UTF_8)
        } }
        val iosMediaTypeString: String? = when (message.mediaType) {
            MediaType.IMAGE -> "photo"
            MediaType.VIDEO -> "video"
            MediaType.AUDIO -> "audio"
            MediaType.DOCUMENT -> "document"
            MediaType.LOCATION -> null  // location is in encryptedContent text, not a separate type
            // 🔧 FIX (2026-08-24 three-gaps): was `null` ("contact card not yet
            // propagated"), which stripped the type off the envelope and left every
            // iPhone with a media blob it could not classify. iOS's GROUP enum has had
            // this case all along — `enum MediaType { photo, video, audio, document,
            // contact }` (`OSHI/GroupMessaging.swift:406-412`), rendered at
            // `GroupViews.swift:3201`. The raw value is the case name, `"contact"`,
            // which is also what the `else` branch of the media-JSON `when` above
            // already produced for the body — the two now agree.
            MediaType.CONTACT -> "contact"
            null -> null
        }

        // __GROUP_E2E_V2_2026_09_23__ reactions and typing never wake anyone and get no self
        // copy (GROUP_E2E_V2_SPEC §2.5).
        val silent = ephemeral || message.content.startsWith(REACTION_PREFIX)
        val wireGroupId = canonicalGroupId(group.id)

        // MEDIA over the v2 blob store (spec §4): the bytes are NOT inlined; the GroupMessage
        // rides the key message with `encryptedContent` empty. Location is text, not media.
        if (message.mediaData != null && iosMediaTypeString != null) {
            val stripped = buildWireGroupMessageJson(
                message = message, wireGroupId = wireGroupId, senderPublicKey = myPublicKey,
                appleTimestamp = appleTimestamp, encryptedContentBytes = ByteArray(0),
                iosMediaTypeString = iosMediaTypeString, includeBodyCompat = false
            )
            val handled = deliverGroupMedia(group, message, stripped, iosMediaTypeString, onlyMembers, outcome)
            // __LEGACY_COMPAT_2026_09_23__ old builds read media only INLINE in the legacy envelope
            // (exactly what Android 1.6.25 sent). Transition window only.
            deliverLegacyGroupEnvelopeCompat(
                group = group, messageId = message.id,
                groupMessageJsonProvider = { buildWireGroupMessageJson(
                    message = message, wireGroupId = wireGroupId, senderPublicKey = myPublicKey,
                    appleTimestamp = appleTimestamp, encryptedContentBytes = encryptedContentBytes,
                    iosMediaTypeString = iosMediaTypeString, includeBodyCompat = true
                ) },
                appleTimestamp = appleTimestamp, iosMediaTypeString = iosMediaTypeString,
                hasMedia = true, push = !silent, v2Handled = handled
            )
            return
        }

        val groupMessageJson = buildWireGroupMessageJson(
            message = message, wireGroupId = wireGroupId, senderPublicKey = myPublicKey,
            appleTimestamp = appleTimestamp, encryptedContentBytes = encryptedContentBytes,
            iosMediaTypeString = iosMediaTypeString, includeBodyCompat = true,
            botAuthor = botAuthor, commentOn = commentOn
        )
        deliverGroupEnvelope(
            group = group,
            messageId = message.id,
            groupMessageJson = groupMessageJson,
            appleTimestamp = appleTimestamp,
            iosMediaTypeString = iosMediaTypeString,
            hasMedia = message.mediaData != null || message.mediaType != null,
            // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ a settings notice wakes nobody.
            push = !silent && wakeOnly == null && !OSHIControlPayload.neverNotifies(message.content),
            wakeOnly = wakeOnly,
            onlyMembers = onlyMembers,
            outcome = outcome
        )
    }

    /**
     * The iOS `GroupMessage` JSON for the wire (GROUP_E2E_V2_SPEC §2.2). [includeBodyCompat]
     * adds Android's `content` key; it is left out of a media message, whose bytes travel in the
     * blob and whose caption travels in `plaintextContent`.
     */
    private fun buildWireGroupMessageJson(
        message: GroupMessage,
        wireGroupId: String,
        senderPublicKey: String,
        appleTimestamp: Double,
        encryptedContentBytes: ByteArray,
        iosMediaTypeString: String?,
        includeBodyCompat: Boolean,
        botAuthor: String? = null,
        commentOn: String? = null
    ): String = wireGroupMessageJson(
        id = message.id,
        wireGroupId = wireGroupId,
        senderPublicKey = senderPublicKey,
        appleTimestamp = appleTimestamp,
        encryptedContentB64 = Base64.encodeToString(encryptedContentBytes, Base64.NO_WRAP),
        iosMediaType = iosMediaTypeString,
        mediaFileName = message.mediaFileName,
        body = message.content,
        includeBodyCompat = includeBodyCompat,
        mentionsRendered = com.oshi.messenger.util.MentionWire.render(mentionsFor(message.id)),
        videoNote = message.isVideoNote,
        durationMs = noteWireDuration(message),
        botAuthor = botAuthor,
        commentOn = commentOn
    )

    /** __VIDEO_NOTE_2026_09_24__ the wire duration of a note row, clamped; null otherwise. */
    private fun noteWireDuration(message: GroupMessage): Int? =
        if (message.isVideoNote) com.oshi.messenger.service.media.VideoNote.wireDuration(message.mediaDurationMs ?: 0L) else null

    // __PUSH_PLAINTEXT_PREVIEW_2026_09_02__ `GroupPush(senderName, preview)` a
    // disparu: ses DEUX champs étaient du contenu en clair destiné au serveur de
    // poussée. Le booléen qui le remplace garde la seule décision qui restait —
    // « faut-il réveiller quelqu'un ? » — et rien à recopier dans le payload.

    // =========================================================================
    // __GROUP_E2E_V2_2026_09_23__ THE GROUP TRANSPORT — v2 pairwise ratchet ONLY
    // (docs/GROUP_E2E_V2_SPEC.md). What this replaced, and why it is gone:
    //   • one AES-GCM envelope under SHA256(upper(groupId) ‖ "oshi-group-envelope-key"),
    //     pinned on IPFS and queued for every member: the key is built from PUBLIC data, so
    //     the server (and anyone holding a CID + the group id) read every group message;
    //   • the same envelope relayed over CrossPlatformMesh to reachable members;
    //   • `📢GROUP_UPDATE📢` over the legacy IPFS 1:1 queue and as a CLEARTEXT mesh frame.
    // A member with no v2 bundle now receives NOTHING and is listed in [unreachableMembers]
    // so the group screen can say "must update" (spec §6). Legacy formats stay readable on
    // the receive side, read only.
    // =========================================================================

    private val _unreachableMembers = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** groupId (as stored) → members the last send could not reach at all (no v2 bundle). */
    val unreachableMembers: StateFlow<Map<String, Set<String>>> = _unreachableMembers.asStateFlow()

    private fun noteReachability(groupId: String, handled: Set<String>, unreachable: Set<String>) {
        val current = _unreachableMembers.value[groupId].orEmpty()
        val next = (current - handled) + unreachable
        if (next == current) return
        _unreachableMembers.value = _unreachableMembers.value.toMutableMap().apply {
            if (next.isEmpty()) remove(groupId) else put(groupId, next)
        }
        if (unreachable.isNotEmpty()) {
            Log.w(TAG, "👥 ${unreachable.size} member(s) of ${groupId.take(8)} have no v2 bundle — must update (nothing sent to them)")
        }
    }

    /**
     * STATE channel (spec §1, §5): one v2 1:1 envelope per recipient carrying the
     * `📢GROUP_UPDATE📢` string. Returns the recipients the relay accepted.
     */
    private suspend fun sendStateV2(content: String, recipients: List<String>): Set<String> {
        val router = v2Router?.get()
        if (router == null) {
            Log.e(TAG, "👥 no v2 transport — group update NOT sent (no legacy fallback)")
            return emptySet()
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STATE_PLAINTEXT_BYTES) {
            Log.w(TAG, "👥 group update of ${bytes.size} B is over the ${MAX_STATE_PLAINTEXT_BYTES} B relay budget — not sent")
            return emptySet()
        }
        val myPublicKey = cryptoManager.getPublicKeyString()
        val sent = LinkedHashSet<String>()
        for (peer in recipients.distinct()) {
            if (peer.isBlank() || peer == myPublicKey || peer.startsWith("bot:")) continue
            val ok = try {
                router.sendText(peer, bytes, UUID.randomUUID().toString())
            } catch (e: Exception) {
                Log.w(TAG, "👥 group update to ${peer.take(8)} failed: ${e.message}"); false
            }
            if (ok) sent += peer
        }
        return sent
    }

    /**
     * 📑 __GROUP_RECEIPTS_2026_11_02__ (N8) Tell the SENDER, and only the
     * sender, what we did with their group message.
     *
     * Addressed to one peer as an ordinary 1:1 control message, so it costs
     * one message rather than one per member, and tells nobody else when we
     * read something.
     *
     * READ obeys the read-receipts setting; RECEIVED does not. "Received" says
     * a device took bytes, which the sender's own relay already implies;
     * "read" says a person looked, which is the part somebody might
     * reasonably want to withhold.
     */
    private fun sendGroupReceipt(kind: GroupReceipts.Kind, groupId: String, msgId: String, sender: String) {
        if (sender.isBlank() || sender == "SYSTEM" || sender.startsWith("bot:")) return
        if (sender == cryptoManager.getPublicKeyString()) return
        val payload = GroupReceipts.encode(groupId, msgId, kind)
        scope.launch {
            // Checked INSIDE the coroutine: the setting lives in DataStore, whose
            // read suspends. An earlier draft read it from SharedPreferences on
            // the caller's thread — which compiles, and always returns the
            // default, so the switch would have looked wired and done nothing.
            if (kind == GroupReceipts.Kind.READ && !readReceiptsEnabled()) return@launch
            // __GROUP_RECEIPT_IOS_1043_2026_11_13__ Never to a build that would show
            // the sentinel as a chat bubble (iOS 1.0.43, the one on sale). See
            // `GroupReceipts.peerReadsReceipts`.
            if (!GroupReceipts.peerReadsReceiptsNow(context, sender)) {
                Log.d(TAG, "📑 receipt to ${sender.take(8)}… withheld: peer not known to read receipts")
                return@launch
            }
            val router = v2Router?.get() ?: return@launch
            try {
                router.sendText(sender, payload.toByteArray(Charsets.UTF_8), UUID.randomUUID().toString())
            } catch (e: Exception) {
                // A lost receipt costs a blank row in a sheet, never a message.
                Log.d(TAG, "📑 receipt to ${sender.take(8)}… not sent: ${e.message}")
            }
        }
    }

    /**
     * The same switch the 1:1 read receipt obeys — the DataStore key written by
     * `SettingsViewModel`, not a SharedPreferences file of the same name.
     *
     * Default true on any failure, matching `MessageRepository.isEnabled` and
     * iOS: a privacy toggle that fails CLOSED would silently stop receipts for
     * someone who never turned it off.
     */
    private suspend fun readReceiptsEnabled(): Boolean =
        try {
            context.dataStore.data.first()[
                androidx.datastore.preferences.core.booleanPreferencesKey("read_receipts_enabled")
            ] ?: true
        } catch (_: Exception) { true }

    /** Wake the members v2 delivered to: generic banner, no name, no preview (spec §2.5). */
    private suspend fun wakeMembers(members: Collection<String>, groupId: String, messageId: String) {
        val myPublicKey = cryptoManager.getPublicKeyString()
        for (memberKey in members) {
            try {
                vpsClient.sendPushNotification(
                    recipientPublicKey = memberKey,
                    type = "group_message",
                    title = "OSHI",
                    body = "You have a new group message",
                    data = mapOf(
                        "type" to "group_message",
                        "groupId" to groupId,
                        "senderPublicKey" to myPublicKey,
                        "senderKey" to myPublicKey,
                        "messageId" to messageId,
                        "v2" to "1"
                    )
                )
            } catch (e2: Exception) {
                Log.w(TAG, "📲 Push to ${memberKey.take(8)} failed: ${e2.message}")
            }
        }
    }

    /**
     * CONTENT channel (spec §2): base64(JSON GroupMessage) fanned out as `type:"group"` v2
     * envelopes, one per member (per member device in device mode). The signature is kept so
     * every caller (messages, reactions, typing, edits, deletes) rides the same path.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun deliverGroupEnvelope(
        group: Group,
        messageId: String,
        groupMessageJson: String,
        appleTimestamp: Double,
        iosMediaTypeString: String?,
        hasMedia: Boolean,
        push: Boolean,
        /** __GROUP_COMMENTS_2026_11_21__ wake only these members (self copy kept); see [sendComment]. */
        wakeOnly: Set<String>? = null,
        onlyMembers: Set<String>? = null,
        outcome: DeliveryOutcome? = null
    ) {
        val recipientKeys = groupRecipientKeys(group, onlyMembers)
        if (recipientKeys.isEmpty()) return

        // 📡 LOCAL MESH FIRST — see __GROUP_MESH_IS_A_TRANSPORT_2026_10_16__ in
        // iOS GroupMessaging.swift for the full story. Short version: a reviewer
        // sat in a room of nine members a metre apart and watched every message
        // go out over the internet, because group send on this branch is v2 plus
        // the legacy pin and neither touches the radio. The receive half already
        // exists on both platforms (`processGroupEnvelopeFromMesh` here, which
        // branches on `v == GroupEnvelopeV3.VERSION && has("wraps")`), so only
        // this send half was missing.
        //
        // It runs BEFORE the `router == null` return below on purpose: that
        // return is exactly the "no v2 transport" case, and refusing to use the
        // radio there is what left an offline group with no delivery at all.
        val meshReached = meshSendGroupEnvelope(group, messageId, groupMessageJson,
            appleTimestamp, iosMediaTypeString, hasMedia, recipientKeys)

        // __MESH_DELIVERY_COUNTS_2026_11_02__ A member the RADIO just delivered to
        // has demonstrably received the message, so they must come off the "must
        // update" list immediately — before the v2 attempt, which may not reach
        // them at all and whose result is the only thing that used to clear it.
        // [noteReachability] is sticky by design (a name stays until something
        // proves delivery), and nothing was ever telling it about the mesh: in a
        // room where everyone is in Bluetooth range and the relay is slow, every
        // member stayed branded "must update" indefinitely while all of them were
        // reading the messages fine.
        if (meshReached.isNotEmpty()) noteReachability(group.id, meshReached, emptySet())
        outcome?.reached?.addAll(meshReached)

        val router = v2Router?.get()
        if (router == null) {
            if (meshReached.isNotEmpty()) {
                Log.i(TAG, "👥 no v2 transport, but the local mesh took it for ${meshReached.size} member(s)")
            } else {
                Log.e(TAG, "👥 no v2 transport — group message NOT sent (no legacy fallback)")
            }
            return
        }
        // __LEGACY_COMPAT_2026_09_23__ the legacy copy goes out even when the v2 send throws.
        var v2Handled: Set<String> = emptySet()
        try {
            v2Handled = deliverGroupEnvelopeV2(router, group, messageId, groupMessageJson, appleTimestamp, recipientKeys, push, wakeOnly, outcome)
        } finally {
            deliverLegacyGroupEnvelopeCompat(group, messageId, { groupMessageJson }, appleTimestamp,
                iosMediaTypeString, hasMedia, push, v2Handled)
        }
    }

    /** The v2 half of [deliverGroupEnvelope] (unchanged behaviour). Returns the members reached. */
    private suspend fun deliverGroupEnvelopeV2(
        router: com.oshi.messenger.network.v2.V2MessageRouter,
        group: Group,
        messageId: String,
        groupMessageJson: String,
        appleTimestamp: Double,
        recipientKeys: List<String>,
        push: Boolean,
        wakeOnly: Set<String>? = null,
        outcome: DeliveryOutcome? = null
    ): Set<String> {
        val plaintext = Base64.encodeToString(groupMessageJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            .toByteArray(Charsets.UTF_8)
        val tsMs = ((appleTimestamp + APPLE_EPOCH_OFFSET) * 1000.0).toLong()
        val result = try {
            router.sendGroupText(
                groupId = canonicalGroupId(group.id),
                members = recipientKeys,
                plaintext = plaintext,
                msgId = messageId,
                originalTimestampMs = tsMs,
                selfCopy = push || wakeOnly != null
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ v2 group send failed: ${e.message}")
            return emptySet()
        }
        noteReachability(group.id, result.handled, result.unreachable)
        outcome?.reached?.addAll(result.handled); outcome?.noBundle?.addAll(result.unreachable)   // __GROUP_RESEND_2026_11_25__
        if (result.rateLimited.isNotEmpty()) rateLimitedByMessage[messageId] = result.rateLimited   // __GROUP_FANOUT_2026_11_24__
        Log.d(TAG, "✅ Group message ${messageId.take(8)} → ${result.handled.size}/${recipientKeys.size} member(s) over v2")
        if (push && result.handled.isNotEmpty()) wakeMembers(result.handled, group.id, messageId)
        if (!push && !wakeOnly.isNullOrEmpty()) {
            val wanted = wakeOnly.map { GroupUpdateAuthorizer.canonicalIdentity(it) }.toSet()
            val targets = result.handled.filter { GroupUpdateAuthorizer.canonicalIdentity(it) in wanted }.toSet()
            if (targets.isNotEmpty()) wakeMembers(targets, group.id, messageId)
        }
        return result.handled
    }

    /**
     * __LEGACY_COMPAT_2026_09_23__ The pre-v2 group transport, restored ONLY for the transition
     * window (`/v2/config.group_legacy_compat` AND before GroupLegacyCompat.SUNSET_MS) and ONLY
     * for members not proven v2-group-capable. Byte-for-byte what Android 1.6.25 sent (legacy
     * `v:2` envelope under the groupId-derived key, pinned once, queued per member) — that is the
     * only format iOS ≤ 143 / Android ≤ 1.6.25 open. No mesh relay. The wake-up push is the same
     * generic one (no group name, no sender name, no content), and a member v2 already woke gets
     * neither a second push nor the queue's fallback push.
     */
    /**
     * __GROUP_FANOUT_2026_11_24__ messageId → members the v2 send left out because the relay kept
     * answering 429 (see [com.oshi.messenger.network.v2.V2MessageRouter.GroupSendResult.rateLimited]).
     * Written by the v2 half, consumed by [deliverLegacyGroupEnvelopeCompat] for the same message.
     */
    private val rateLimitedByMessage = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    // =========================================================================
    // __GROUP_RESEND_2026_11_25__ automatic resend to the members a send missed
    // =========================================================================

    /** Who one fan-out reached (v2 or the local mesh) and who has no v2 bundle at all. */
    internal class DeliveryOutcome {
        val reached: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())
        val noBundle: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())
    }

    private fun groupRecipientKeys(group: Group, onlyMembers: Set<String>?): List<String> {
        val myPublicKey = cryptoManager.getPublicKeyString()
        val all = group.memberPublicKeys.filter { it != myPublicKey && !it.startsWith("bot:") }
        if (onlyMembers == null) return all
        val wanted = onlyMembers.map { GroupUpdateAuthorizer.canonicalIdentity(it) }.toSet()
        return all.filter { GroupUpdateAuthorizer.canonicalIdentity(it) in wanted }
    }

    private fun findStoredGroupMessage(groupId: String, msgId: String): GroupMessage? =
        _groupMessages.value.entries.firstOrNull { it.key.equals(groupId, ignoreCase = true) }
            ?.value?.firstOrNull { it.id == msgId }

    private fun isStoredGroupMessage(groupId: String, msgId: String): Boolean =
        findStoredGroupMessage(groupId, msgId) != null

    /** "Retry now" from the bubble's details. */
    fun retryPendingDelivery(msgId: String) {
        pendingDelivery.makeDue(msgId)
        publishPendingDelivery()
        resendKick.trySend(Unit)
    }

    /**
     * Runs for the life of the process: wakes every 30 s (and at once on "Retry now" or when a
     * network comes back), resends every due entry to its pending members only.
     */
    private fun startPendingDeliveryLoop() {
        publishPendingDelivery()
        try {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            cm?.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    if (pendingDelivery.all().isEmpty()) return
                    pendingDelivery.makeDue()
                    resendKick.trySend(Unit)
                }
            })
        } catch (e: Throwable) {
            Log.w(TAG, "pending delivery: no network callback (${e.message}), timer only")
        }
        scope.launch {
            delay(10_000L)   // let the transports come up after a cold start
            while (isActive) {
                try { runDuePendingDeliveries() } catch (e: Exception) { Log.w(TAG, "pending delivery pass failed: ${e.message}") }
                withTimeoutOrNull(30_000L) { resendKick.receive() }
            }
        }
    }

    private suspend fun runDuePendingDeliveries() = resendMutex.withLock {
        for (entry in pendingDelivery.due()) {
            if (entry.msgId in sendsInFlight) continue
            val group = _groups.value.find { it.id.equals(entry.groupId, ignoreCase = true) }
            if (group == null) { pendingDelivery.remove(entry.msgId); continue }
            // Members who left are not owed the message.
            pendingDelivery.dropDeparted(group.id, group.memberPublicKeys)
            val current = pendingDelivery.get(entry.msgId) ?: continue
            val stored = findStoredGroupMessage(group.id, entry.msgId)
            if (stored == null || stored.isDeleted) { pendingDelivery.remove(entry.msgId); continue }
            val withMedia = if (stored.mediaType != null && stored.mediaData == null) {
                loadMediaBytes(stored.id)?.let { stored.copy(mediaData = it) } ?: stored
            } else stored
            val wire = current.wireContent?.let { withMedia.copy(content = it) } ?: withMedia
            Log.i(TAG, "↻ group ${group.id.take(8)} msg ${entry.msgId.take(8)}: resend to ${current.pending.size}/${current.total} (attempt ${current.attempts + 1})")
            sendMessageToMembers(group, wire, botAuthor = current.botAuthor, commentOn = current.commentOn,
                wakeOnly = current.wakeOnly, onlyMembers = current.pending)
        }
        publishPendingDelivery()
    }

    private suspend fun deliverLegacyGroupEnvelopeCompat(
        group: Group,
        messageId: String,
        /** Built only when a legacy member needs it — for media it is the whole file inline. */
        groupMessageJsonProvider: () -> String,
        appleTimestamp: Double,
        iosMediaTypeString: String?,
        hasMedia: Boolean,
        push: Boolean,
        v2Handled: Set<String>
    ) {
        val myPublicKey = cryptoManager.getPublicKeyString()
        val recipientKeys = group.memberPublicKeys.filter { it != myPublicKey && !it.startsWith("bot:") }
        val compat = com.oshi.messenger.network.v2.GroupLegacyCompat
        // __LEAK_FIX_2026_09_25__ (audit B) a member v2 REACHED whose build reads v2 groups
        // (advertises group v3 ⇒ built after 2026-09-04) already has this message: no readable
        // duplicate for it. See GroupLegacyCompat.stillNeedLegacy.
        // __GROUP_FANOUT_2026_11_24__ never the legacy path because the relay said 429.
        val legacy = compat.withoutRateLimited(
            compat.stillNeedLegacy(compat.legacyRecipientsNow(recipientKeys), v2Handled, ::peerReadsGroupV3),
            rateLimitedByMessage.remove(messageId).orEmpty())
        if (legacy.isEmpty()) return
        // __MEDIA_SEND_OOM_2026_11_11__ The inline copy of a large file may not fit in the
        // heap. The v2 blob send has already gone out; losing the old-build copy beats
        // killing the app (OutOfMemoryError is an Error, the catch below never saw it).
        val groupMessageJson = try {
            groupMessageJsonProvider()
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "👥 legacy-compat copy of ${messageId.take(8)} too large for memory — skipped for ${legacy.size} old-build member(s)")
            return
        }
        try {
            val payload = JSONObject().apply {
                put("type", "group_message")
                put("id", messageId)
                put("groupId", group.id)
                put("senderKey", myPublicKey)
                put("recipientKeys", JSONArray(recipientKeys))
                put("timestamp", appleTimestamp)
                put("content", Base64.encodeToString(groupMessageJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                put("hasMedia", hasMedia)
                put("mediaType", iosMediaTypeString ?: JSONObject.NULL)
                put("chainIndex", 0)
            }
            // __LEAK_FIX_2026_09_25__ (audit B) no group key when every remaining target reads v4:
            // one body under a fresh content key, the key wrapped per target over the 1:1
            // ratchet (what Android 1.6.1x-1.6.25 sent itself). The groupId-keyed form is kept
            // byte for byte for a target on a build older than 2026-09-04 only.
            val envelope = if (compat.legacyEnvelopeFor(legacy, ::peerReadsGroupV3) ==
                com.oshi.messenger.network.v2.GroupLegacyCompat.LegacyEnvelope.V4_WRAPPED
            ) {
                val sealed = GroupEnvelopeV3.seal(payload.toString().toByteArray(Charsets.UTF_8), legacy) { member, contentKeyB64 ->
                    val session = doubleRatchet.getOrCreateSession(
                        theirPublicKey = member,
                        ourPublicKey = myPublicKey,
                        sharedSecret = cryptoManager.deriveSharedSecret(member),
                        ratcheting = true
                    )
                    doubleRatchet.encrypt(session, contentKeyB64).toJSON().toString().toByteArray(Charsets.UTF_8)
                }
                if (sealed.getJSONArray("wraps").length() == 0) {
                    Log.e(TAG, "🕰️ [compat] v4: no legacy member could be wrapped — not pinning")
                    return
                }
                sealed
            } else {
                val envelopeKey = deriveGroupEnvelopeKey(deriveGroupKey(group.id))
                val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(envelopeKey, "AES"), GCMParameterSpec(128, nonce))
                val ct = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
                JSONObject().apply {
                    put("v", 2)
                    put("envelope", Base64.encodeToString(ct, Base64.NO_WRAP))
                    put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
                    put("hint", group.id.take(4))
                }
            }
            val ipfsHash = vpsClient.uploadToIPFS(envelope.toString())
            if (ipfsHash == null) {
                Log.e(TAG, "🕰️ [compat] legacy group envelope upload failed")
                return
            }
            var queued = 0
            for (memberKey in legacy) {
                val alreadyWoken = memberKey in v2Handled
                // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Judged as the GROUP,
                // not as the member's 1:1 — see `sendStateLegacyCompat`. Here the
                // group IS in scope, so the question can be asked properly.
                if (try { vpsClient.notifyVPSQueue(memberKey, ipfsHash, suppressPush = alreadyWoken || !push, conversationId = group.id) } catch (_: Exception) { false }) queued++
            }
            Log.d(TAG, "🕰️ [compat] group message ${messageId.take(8)} → $queued/${legacy.size} legacy member(s)")
            if (!push) return
            for (memberKey in legacy) {
                if (memberKey in v2Handled) continue
                try {
                    vpsClient.sendPushNotification(
                        recipientPublicKey = memberKey,
                        type = "group_message",
                        title = "OSHI",
                        body = "You have a new group message",
                        data = mapOf(
                            "type" to "group_message",
                            "groupId" to group.id,
                            "senderPublicKey" to myPublicKey,
                            "senderKey" to myPublicKey,
                            "messageId" to messageId
                        )
                    )
                } catch (e2: Exception) {
                    Log.w(TAG, "📲 [compat] push to ${memberKey.take(8)} failed: ${e2.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "🕰️ [compat] legacy group send failed: ${e.message}")
        } catch (e: OutOfMemoryError) {
            // __MEDIA_SEND_OOM_2026_11_11__ the envelope re-encodes the inline file twice.
            Log.e(TAG, "🕰️ [compat] legacy group copy too large for memory — skipped")
        }
    }

    /**
     * __LEAK_FIX_2026_09_25__ This peer's build reads v2 groups AND the v4 wrapped envelope:
     * it advertised `groupEnvelopeV3` in its profile (every build since 2026-09-04 does, and
     * all of them route v2 `type:"group"` + v2 `📢GROUP_UPDATE📢`). Unknown ⇒ false (legacy).
     */
    /**
     * Hand this group message to every mesh-reachable member, sealed per member,
     * and return the members a live link took it for.
     *
     * Only members that advertise group v3 are included. A reachable member on an
     * older build is left to the server path: wrapping the body under the
     * groupId-derived key instead would hand a stranger on the same mesh a key it
     * can derive from a public group id, which is CV-002 exactly.
     *
     * Never throws into the caller — the server paths must be unaffected by a
     * radio that is not there.
     */
    private fun meshSendGroupEnvelope(
        group: Group,
        messageId: String,
        groupMessageJson: String,
        appleTimestamp: Double,
        iosMediaTypeString: String?,
        hasMedia: Boolean,
        recipientKeys: List<String>
    ): Set<String> {
        val myPublicKey = cryptoManager.getPublicKeyString()
        val targets = recipientKeys.filter {
            crossPlatformMesh.isPeerReachable(it) && peerReadsGroupV3(it)
        }
        if (targets.isEmpty()) return emptySet()

        // Inline media rides `content` on this path, so a photo would put megabytes
        // of base64 on a local TCP link that also carries live calls. Above the cap
        // the relay and the blob store keep the message; the radio carries text,
        // which is what an offline group needs first. Must match the iOS cap.
        val meshInlineCap = 2 * 1024 * 1024

        return try {
            val payload = JSONObject().apply {
                put("type", "group_message")
                put("id", messageId)
                put("groupId", group.id)
                put("senderKey", myPublicKey)
                put("recipientKeys", JSONArray(recipientKeys))
                put("timestamp", appleTimestamp)
                put("content", Base64.encodeToString(groupMessageJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                put("hasMedia", hasMedia)
                put("mediaType", iosMediaTypeString ?: JSONObject.NULL)
                put("chainIndex", 0)
            }
            val payloadBytes = payload.toString().toByteArray(Charsets.UTF_8)
            if (payloadBytes.size > meshInlineCap) {
                Log.d(TAG, "📡 group mesh: ${payloadBytes.size} B over the $meshInlineCap B inline cap — server only")
                return emptySet()
            }
            val sealed = GroupEnvelopeV3.seal(payloadBytes, targets) { member, contentKeyB64 ->
                val session = doubleRatchet.getOrCreateSession(
                    theirPublicKey = member,
                    ourPublicKey = myPublicKey,
                    sharedSecret = cryptoManager.deriveSharedSecret(member),
                    ratcheting = true
                )
                doubleRatchet.encrypt(session, contentKeyB64).toJSON().toString().toByteArray(Charsets.UTF_8)
            }
            if (sealed.getJSONArray("wraps").length() == 0) {
                Log.w(TAG, "📡 group mesh: no reachable member could be wrapped")
                return emptySet()
            }
            val envelopeStr = sealed.toString()
            val reached = mutableSetOf<String>()
            for (member in targets) {
                try {
                    crossPlatformMesh.sendMessage(member, envelopeStr, "GROUP_MESSAGE")
                    reached.add(member)
                } catch (e: Exception) {
                    Log.w(TAG, "📡 group mesh send failed for ${member.take(8)}: ${e.message}")
                }
            }
            if (reached.isNotEmpty()) {
                Log.d(TAG, "📡 Group message delivered over the local mesh to ${reached.size}/${targets.size} member(s) — before any server")
            }
            reached
        } catch (e: Exception) {
            Log.w(TAG, "📡 group mesh seal failed: ${e.javaClass.simpleName}: ${e.message}")
            emptySet()
        }
    }

    private fun peerReadsGroupV3(peer: String): Boolean =
        PeerCapabilities.supportsGroupV3(context, peer) ||
            PeerCapabilities.supportsGroupV3(context, GroupUpdateAuthorizer.canonicalIdentity(peer))

    /** Group MEDIA over the v2 blob store (spec §4). Over the cap ⇒ nobody, never inline. */
    private suspend fun deliverGroupMedia(
        group: Group, message: GroupMessage, strippedGroupMessageJson: String, iosMediaType: String,
        onlyMembers: Set<String>? = null, outcome: DeliveryOutcome? = null
    ): Set<String> {
        val router = v2Router?.get()
        val bytes = message.mediaData
        if (router == null || bytes == null) {
            Log.e(TAG, "👥 no v2 transport — group media NOT sent (no legacy fallback)")
            return emptySet()
        }
        val recipientKeys = groupRecipientKeys(group, onlyMembers)
        if (recipientKeys.isEmpty()) return emptySet()
        val result = try {
            router.sendGroupFile(
                groupId = canonicalGroupId(group.id),
                members = recipientKeys,
                data = bytes,
                filename = message.mediaFileName?.takeIf { it.isNotEmpty() } ?: defaultGroupFileName(iosMediaType),
                mime = groupMime(iosMediaType, isGif = com.oshi.messenger.service.media.GifBytes.isGif(bytes)),
                mediaType = iosMediaType,
                groupMessageB64 = Base64.encodeToString(strippedGroupMessageJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                msgId = message.id,
                videoNote = message.isVideoNote,
                durationMs = noteWireDuration(message)
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ v2 group media send failed: ${e.message}")
            return emptySet()
        }
        noteReachability(group.id, result.handled, result.unreachable)
        outcome?.reached?.addAll(result.handled); outcome?.noBundle?.addAll(result.unreachable)   // __GROUP_RESEND_2026_11_25__
        if (result.rateLimited.isNotEmpty()) rateLimitedByMessage[message.id] = result.rateLimited   // __GROUP_FANOUT_2026_11_24__
        Log.d(TAG, "✅ Group media ${message.id.take(8)} → ${result.handled.size}/${recipientKeys.size} member(s) over v2 blobs")
        if (result.handled.isNotEmpty()) wakeMembers(result.handled, group.id, message.id)
        return result.handled
    }

    /**
     * Derive group key from group ID (iOS compatible)
     * SHA256(groupId_utf8 + "oshi-group-envelope-key"_utf8)
     */
    internal fun deriveGroupKey(groupId: String): ByteArray {
        // 🔧 CRITICAL: Normalize to uppercase — iOS always uses UUID.uuidString (uppercase).
        // Without this, Android-created groups (lowercase UUID) produce mismatched keys.
        val canonicalId = groupId.uppercase()
        val md = MessageDigest.getInstance("SHA-256")
        md.update(canonicalId.toByteArray(Charsets.UTF_8))
        md.update("oshi-group-envelope-key".toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    /**
     * Derive envelope encryption key from group key (iOS compatible)
     * SHA256(groupKey + "oshi-group-envelope-v2"_utf8 + "envelope-key"_utf8)
     */
    private fun deriveGroupEnvelopeKey(groupKey: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(groupKey)
        md.update("oshi-group-envelope-v2".toByteArray(Charsets.UTF_8))
        md.update("envelope-key".toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    private suspend fun notifyGroupCreated(group: Group) {
        val myPublicKey = cryptoManager.getPublicKeyString()
        val updateJson = JSONObject().apply {
            put("type", "created")
            put("groupId", group.id)
            put("name", group.name)
            put("description", group.description)
            put("creatorPublicKey", group.creatorPublicKey)
            put("members", JSONArray(group.memberPublicKeys))
            put("admins", JSONArray(group.adminPublicKeys))
            put("isPublic", group.isPublic)
            put("groupType", group.effectiveGroupType.value)
            // 🔴 NPE FIX. This was `group.encryptionKey` handed straight to
            // `Base64.encodeToString`, whose parameter is a Java PLATFORM TYPE
            // (`byte[]!`) — so Kotlin accepts a `ByteArray?` with no warning and no
            // compile error, then dereferences it at runtime.
            //
            // `Group.encryptionKey` is null for every group that came from an iPhone:
            // `applyiOSGroupUpdateBroadcast` leaves it null on purpose because the key is
            // always DERIVED from the group id. Every other reader in this file already
            // calls `deriveGroupKey`; these two were never converted.
            put(
                "encryptionKey",
                android.util.Base64.encodeToString(
                    group.encryptionKey ?: deriveGroupKey(group.id),
                    android.util.Base64.NO_WRAP
                )
            )
        }

        val content = "$GROUP_UPDATE_PREFIX${updateJson}"

        group.memberPublicKeys
            .filter { it != myPublicKey }
            .forEach { memberKey ->
                sendGroupUpdateRaw(content, listOf(memberKey))
            }
    }

    private suspend fun notifyGroupDeleted(group: Group) {
        val myPublicKey = cryptoManager.getPublicKeyString()
        val updateJson = JSONObject().apply {
            put("type", "deleted")
            put("groupId", group.id)
        }

        val content = "$GROUP_UPDATE_PREFIX${updateJson}"

        group.memberPublicKeys
            .filter { it != myPublicKey }
            .forEach { memberKey ->
                sendGroupUpdateRaw(content, listOf(memberKey))
            }
    }

    private suspend fun sendGroupKeyToMember(group: Group, memberPublicKey: String) {
        val updateJson = JSONObject().apply {
            put("type", "member_added")
            put("groupId", group.id)
            put("name", group.name)
            put("members", JSONArray(group.memberPublicKeys))
            // 🔴 NPE FIX. This was `group.encryptionKey` handed straight to
            // `Base64.encodeToString`, whose parameter is a Java PLATFORM TYPE
            // (`byte[]!`) — so Kotlin accepts a `ByteArray?` with no warning and no
            // compile error, then dereferences it at runtime.
            //
            // `Group.encryptionKey` is null for every group that came from an iPhone:
            // `applyiOSGroupUpdateBroadcast` leaves it null on purpose because the key is
            // always DERIVED from the group id. Every other reader in this file already
            // calls `deriveGroupKey`; these two were never converted.
            put(
                "encryptionKey",
                android.util.Base64.encodeToString(
                    group.encryptionKey ?: deriveGroupKey(group.id),
                    android.util.Base64.NO_WRAP
                )
            )
        }

        val content = "$GROUP_UPDATE_PREFIX${updateJson}"
        sendGroupUpdateRaw(content, listOf(memberPublicKey))
    }

    private suspend fun rotateGroupKey(group: Group) {
        // Generate new group key
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(256)
        val newKey = keyGenerator.generateKey().encoded

        // Update group
        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(group.id, ignoreCase = true) }
        if (index >= 0) {
            currentGroups[index] = group.copy(encryptionKey = newKey)
            _groups.value = currentGroups
            saveGroups()

            // Distribute new key to remaining members
            val updateJson = JSONObject().apply {
                put("type", "key_rotation")
                put("groupId", group.id)
                put("encryptionKey", android.util.Base64.encodeToString(newKey, android.util.Base64.NO_WRAP))
            }

            val content = "$GROUP_UPDATE_PREFIX${updateJson}"
            val myPublicKey = cryptoManager.getPublicKeyString()

            group.memberPublicKeys
                .filter { it != myPublicKey }
                .forEach { memberKey ->
                    sendGroupUpdateRaw(content, listOf(memberKey))
                }
        }
    }

    private fun handleGroupCreated(json: JSONObject, senderPublicKey: String) {
        val groupId = json.getString("groupId")
        val name = json.getString("name")
        val description = json.optString("description", "")
        val creatorPublicKey = json.getString("creatorPublicKey")
        val membersArray = json.getJSONArray("members")
        val adminsArray = json.getJSONArray("admins")
        val isPublic = json.optBoolean("isPublic", false)
        // Keep the permission model alive across the Android-native `created`/`member_added`
        // path too — `groupType` drives Group.canAddMembers/canChangeName/canChangeWallpaper.
        val resolvedType = json.optString("groupType", "").takeIf { it.isNotEmpty() }
            ?.let { GroupType.fromValue(it) }
            ?: if (isPublic) GroupType.PUBLIC else GroupType.COLLABORATIVE
        val encryptionKeyStr = json.optString("encryptionKey", "")
        val groupImageStr = json.optString("groupImage", "")

        val members = mutableListOf<String>()
        for (i in 0 until membersArray.length()) {
            members.add(membersArray.getString(i))
        }

        val admins = mutableListOf<String>()
        for (i in 0 until adminsArray.length()) {
            admins.add(adminsArray.getString(i))
        }

        val encryptionKey = if (encryptionKeyStr.isNotEmpty()) {
            android.util.Base64.decode(encryptionKeyStr, android.util.Base64.NO_WRAP)
        } else null

        val groupImage = if (groupImageStr.isNotEmpty()) {
            try { android.util.Base64.decode(groupImageStr, android.util.Base64.NO_WRAP) } catch (e: Exception) { null }
        } else null

        val currentGroups = _groups.value.toMutableList()
        // 🔧 Use case-insensitive match: iOS sends uppercase UUIDs, Android-created groups are lowercase
        val existingIndex = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        var newlyCreated = false

        if (existingIndex >= 0) {
            // Update existing group: sync name, description, members, admins, picture
            val existing = currentGroups[existingIndex]
            // 🔴 P0 (audit G-2) — same authorizer as the full-`MessageGroup` path. This legacy
            // `created` shape rewrote name/members/admins with no check at all.
            val decision = GroupUpdateAuthorizer.authorize(
                currentMemberKeys = existing.memberPublicKeys,
                currentAdminKeys = existing.adminPublicKeys,
                currentType = existing.effectiveGroupType,
                sender = senderPublicKey
            )
            if (!decision.apply) {
                Log.w(TAG, "🛑 'created' ignored (${decision.reason}) for '${existing.name}'")
                return
            }
            val mergedMembers = if (decision.keepMembership) existing.memberPublicKeys
                else GroupUpdateAuthorizer.keepAdminsInRoster(decision, members, existing.memberPublicKeys, existing.adminPublicKeys)
            currentGroups[existingIndex] = existing.copy(
                name = if (decision.keepName) existing.name else name,
                // __GROUP_TYPE_TAKEOVER_2026_11_20__ settings and type only from someone
                // who may change them (a plain member of an admin-only group may not).
                description = if (GroupUpdateAuthorizer.mayChangeGroupSettings(decision)) description else existing.description,
                memberPublicKeys = mergedMembers,
                adminPublicKeys = when {
                    // Roster taken but admin set protected → re-stamp from OUR copy, so a member
                    // the update introduces cannot arrive pre-promoted (swift:911-921).
                    decision.keepAdminSet && !decision.keepMembership ->
                        GroupUpdateAuthorizer.restampAdminSet(existing.adminPublicKeys, mergedMembers)
                    decision.keepAdminSet -> existing.adminPublicKeys
                    else -> admins
                },
                isPublic = if (GroupUpdateAuthorizer.mayChangeGroupSettings(decision)) isPublic else existing.isPublic,
                groupType = existing.groupType
                    ?: resolvedType.takeIf { GroupUpdateAuthorizer.mayChangeGroupType(decision) },
                groupImage = (if (GroupUpdateAuthorizer.mayChangeGroupSettings(decision)) groupImage else null)
                    ?: existing.groupImage,
                encryptionKey = (if (!decision.keepMembership) encryptionKey else null) ?: existing.encryptionKey,
                updatedAt = System.currentTimeMillis()
            )
            Log.d(TAG, "🔄 Updated existing group '${name}' (${groupId.take(8)}, ${decision.reason})")
        } else {
            // 🔴 P0 (audit G-3) — iOS's unknown-group guard (GroupMessaging.swift:1044-1048),
            // which this path never had: a `created` from anyone re-materialised any group.
            val myPublicKey = cryptoManager.getPublicKeyString()
            if (!GroupUpdateAuthorizer.isSelfInRoster(myPublicKey, members)) {
                Log.d(TAG, "⏭️ Ignoring 'created' — not a member of '$name' (${groupId.take(8)})")
                return
            }
            if (isTombstonedGroup(groupId)) {
                Log.d(TAG, "⏭️ Ignoring 'created' — '$name' (${groupId.take(8)}) was left on this device")
                return
            }
            // New group — add it
            val group = Group(
                id = groupId,
                name = name,
                description = description,
                creatorPublicKey = creatorPublicKey,
                memberPublicKeys = members,
                adminPublicKeys = admins,
                isPublic = isPublic,
                groupType = resolvedType,
                groupImage = groupImage,
                encryptionKey = encryptionKey
            )
            currentGroups.add(group)
            Log.d(TAG, "✅ Added new group '${name}' (${groupId.take(8)})")
            // G-8 mitigation: this legacy shape carries no `groupType` when it comes from iOS
            // (`handleGroupSyncRequest`, GroupMessaging.swift:1876-1885), so an admin-only group
            // lands here as COLLABORATIVE. Ask for the authoritative full `MessageGroup`, which
            // does carry `type`, right after we materialise it.
            newlyCreated = true
        }

        _groups.value = currentGroups
        saveGroups()
        updateGroupConversations()
        if (newlyCreated) requestGroupStateSync(groupId)
    }

    private fun handleGroupInfoUpdated(json: JSONObject, senderPublicKey: String? = null) {
        val groupId = json.getString("groupId")
        val name = json.optString("name")
        val description = json.optString("description")
        val groupImageStr = json.optString("groupImage", "")
        val groupImage = if (groupImageStr.isNotEmpty()) {
            try { android.util.Base64.decode(groupImageStr, android.util.Base64.NO_WRAP) } catch (e: Exception) { null }
        } else null

        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index >= 0) {
            val group = currentGroups[index]
            val decision = GroupUpdateAuthorizer.authorize(
                currentMemberKeys = group.memberPublicKeys,
                currentAdminKeys = group.adminPublicKeys,
                currentType = group.effectiveGroupType,
                sender = senderPublicKey
            )
            if (!decision.apply) {
                Log.w(TAG, "🛑 'updated' ignored (${decision.reason}) for '${group.name}'")
                return
            }
            currentGroups[index] = group.copy(
                name = if (name.isNotEmpty() && !decision.keepName) name else group.name,
                description = if (description.isNotEmpty()) description else group.description,
                groupImage = groupImage ?: group.groupImage,
                updatedAt = System.currentTimeMillis()
            )
            _groups.value = currentGroups
            saveGroups()
            updateGroupConversations()
        }
    }

    /**
     * `member_added` — someone joined a group we already have.
     *
     * 🔴 P0 (audit G-1). This used to be `handleGroupCreated(json, "")`, and the two payloads do
     * not share a schema: iOS's `broadcastMemberJoinedNotification` (GroupMessaging.swift:1985-1996)
     * emits exactly `{"type","groupId","memberPublicKey"}`, while `handleGroupCreated` opens with
     * `getString("name")` / `getString("creatorPublicKey")` / `getJSONArray("members")` /
     * `getJSONArray("admins")`. Every iPhone that joined an Android-run group therefore threw a
     * `JSONException` that the catch in `handleGroupUpdate` swallowed, and was never added.
     *
     * Two shapes arrive here and both are handled:
     *  - iOS / `broadcastMemberAddedNotification`: a single `memberPublicKey`.
     *  - Android `sendGroupKeyToMember` (:2290): `name` + a full `members` array + `encryptionKey`.
     */
    private fun handleMemberAdded(json: JSONObject, senderPublicKey: String? = null) {
        val groupId = json.optString("groupId", "")
        if (groupId.isEmpty()) return

        val addedKey = json.optString("memberPublicKey", "").takeIf { it.isNotEmpty() }
        val rosterArray = json.optJSONArray("members")
        val roster = (0 until (rosterArray?.length() ?: 0)).mapNotNull { i ->
            rosterArray?.optString(i, "")?.takeIf { it.isNotEmpty() }
        }

        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index < 0) {
            // We do not have the group. Only the Android shape can materialise it, and only when
            // the roster it carries actually contains us (G-3).
            val name = json.optString("name", "")
            val myPublicKey = cryptoManager.getPublicKeyString()
            if (name.isEmpty() || !GroupUpdateAuthorizer.isSelfInRoster(myPublicKey, roster) ||
                isTombstonedGroup(groupId)) {
                Log.d(TAG, "⚠️ member_added for unknown group ${groupId.take(8)}")
                return
            }
            val encryptionKey = json.optString("encryptionKey", "").takeIf { it.isNotEmpty() }?.let {
                try { Base64.decode(it, Base64.NO_WRAP) } catch (_: Exception) { null }
            }
            currentGroups.add(
                Group(
                    id = groupId,
                    name = name,
                    creatorPublicKey = senderPublicKey ?: "",
                    memberPublicKeys = roster,
                    adminPublicKeys = listOfNotNull(senderPublicKey?.takeIf { it.isNotEmpty() }),
                    encryptionKey = encryptionKey
                )
            )
            _groups.value = currentGroups
            saveGroups()
            updateGroupConversations()
            requestGroupStateSync(groupId)
            Log.d(TAG, "✅ Joined group '$name' (${groupId.take(8)}) via member_added")
            return
        }

        val group = currentGroups[index]
        // A self-announced join needs no standing — it is signed by the person it names, and it is
        // how a new member gets into the roster at all. iOS accepts it unconditionally
        // (GroupMessaging.swift:1080-1100) and says why at :1112-1116 for `member_sync_request`:
        // "they have the group, so they're legitimate". Adding SOMEONE ELSE is a membership write.
        val isSelfAnnouncedJoin = addedKey != null && !senderPublicKey.isNullOrEmpty() &&
            GroupUpdateAuthorizer.canonicalIdentity(addedKey) ==
                GroupUpdateAuthorizer.canonicalIdentity(senderPublicKey)
        if (!isSelfAnnouncedJoin) {
            val decision = GroupUpdateAuthorizer.authorize(
                currentMemberKeys = group.memberPublicKeys,
                currentAdminKeys = group.adminPublicKeys,
                currentType = group.effectiveGroupType,
                sender = senderPublicKey
            )
            if (!decision.apply || decision.keepMembership) {
                Log.w(TAG, "🛑 member_added ignored (${decision.reason}) for '${group.name}'")
                return
            }
        }

        // __EVICTED_MEMBER_WALKED_BACK_IN_2026_11_04__ (Anatole) "le nombre de
        // personnes dans le groupe bouge sans cesse sans que personne ne touche a
        // rien". `isSelfAnnouncedJoin` above skips authorisation ON PURPOSE -- it is
        // how a genuine new member gets into the roster at all -- but that made it
        // the one door an EVICTED member could keep walking back through: evict,
        // their app announces itself, accepted unconditionally, evict again,
        // announce again, for ever. No standing check can tell a new member from an
        // evicted one; only the tombstone can. So it is enforced HERE, after the
        // standing check and before the append, exactly where iOS puts it
        // (`GroupMessaging.swift:1325`).
        val announced = (roster + listOfNotNull(addedKey)).distinct()
        val allowed = GroupUpdateAuthorizer.withoutEvicted(announced, group.evictedMemberKeys)
        (announced - allowed.toSet()).forEach {
            Log.w(TAG, "🛑 member_added refused for ${it.take(8)}: removed from '${group.name}' (tombstone)")
        }
        val toAdd = allowed.filter { candidate ->
            group.memberPublicKeys.none {
                GroupUpdateAuthorizer.canonicalIdentity(it) ==
                    GroupUpdateAuthorizer.canonicalIdentity(candidate)
            }
        }
        if (toAdd.isEmpty()) return

        currentGroups[index] = group.copy(
            memberPublicKeys = group.memberPublicKeys + toAdd,
            updatedAt = System.currentTimeMillis()
        )
        _groups.value = currentGroups
        saveGroups()
        updateGroupConversations()
        toAdd.forEach { key ->
            val who = contactAliasResolver?.invoke(key) ?: key.take(8)
            postSystemMessage(group.id, "$who joined the group")
        }
        Log.d(TAG, "✅ member_added: ${toAdd.size} member(s) joined '${group.name}'")
    }

    private fun handleMemberRemoved(json: JSONObject, senderPublicKey: String? = null) {
        val groupId = json.getString("groupId")
        val removedMember = json.getString("memberPublicKey")

        val currentGroups = _groups.value.toMutableList()
        // G-13: iOS emits UPPERCASE UUIDs, so `==` dropped every member_removed for an
        // Android-created group.
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index >= 0) {
            val group = currentGroups[index]
            val removedCanonical = GroupUpdateAuthorizer.canonicalIdentity(removedMember)
            // Leaving is always your own to do — a departure notice is signed by the person it
            // names, so it needs no admin standing. Removing SOMEONE ELSE is a membership write.
            val isSelfDeparture = senderPublicKey != null &&
                GroupUpdateAuthorizer.canonicalIdentity(senderPublicKey) == removedCanonical
            if (!isSelfDeparture) {
                val decision = GroupUpdateAuthorizer.authorize(
                    currentMemberKeys = group.memberPublicKeys,
                    currentAdminKeys = group.adminPublicKeys,
                    currentType = group.effectiveGroupType,
                    sender = senderPublicKey
                )
                if (!decision.apply || decision.keepMembership) {
                    Log.w(TAG, "🛑 member_removed ignored (${decision.reason}) for '${group.name}'")
                    return
                }
            }
            currentGroups[index] = group.copy(
                memberPublicKeys = group.memberPublicKeys.filter {
                    GroupUpdateAuthorizer.canonicalIdentity(it) != removedCanonical
                },
                adminPublicKeys = group.adminPublicKeys.filter {
                    GroupUpdateAuthorizer.canonicalIdentity(it) != removedCanonical
                },
                updatedAt = System.currentTimeMillis()
            )
            _groups.value = currentGroups
            saveGroups()
            updateGroupConversations()
        }
    }

    private fun handleGroupDeletedNotification(json: JSONObject, senderPublicKey: String? = null) {
        val groupId = json.getString("groupId")
        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index < 0) return
        // Deleting the group for everyone is an admin act; without this any member could wipe it
        // off every other Android device.
        val group = currentGroups[index]
        val senderIsAdmin = senderPublicKey != null && group.adminPublicKeys.any {
            GroupUpdateAuthorizer.canonicalIdentity(it) ==
                GroupUpdateAuthorizer.canonicalIdentity(senderPublicKey)
        }
        if (!senderIsAdmin) {
            Log.w(TAG, "🛑 'deleted' ignored — ${senderPublicKey?.take(8) ?: "unauthenticated"} is not an admin of '${group.name}'")
            return
        }
        // __GROUP_SETTINGS_2026_11_20__ the definition with `settings.dissolved` already made the
        // group read-only with a banner (spec §2.9); the user deletes the chat themselves.
        if (settingsOf(group.id)?.dissolved == true) return
        currentGroups.removeAt(index)
        _groups.value = currentGroups
        saveGroups()
        updateGroupConversations()
    }

    /**
     * Handle incoming `wallpaper_changed` envelope (sent by iOS via wallpaperChanged
     * system message OR by Android via `broadcastWallpaperChanged`).
     */
    private fun handleWallpaperChanged(json: JSONObject, senderPublicKey: String? = null) {
        val groupId = json.optString("groupId", "")
        if (groupId.isEmpty()) return
        val wallpaperBase64 = json.optString("wallpaperData", "")
        val wallpaperBytes = if (wallpaperBase64.isNotEmpty()) {
            try { Base64.decode(wallpaperBase64, Base64.NO_WRAP) } catch (_: Exception) { null }
        } else null
        val updatedBy = json.optString("updatedBy", "").ifEmpty { null }
        val updatedAt = json.optLong("updatedAt", System.currentTimeMillis())

        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index < 0) {
            Log.w(TAG, "🎨 wallpaper_changed: group not found: ${groupId.take(8)}")
            return
        }
        val existing = currentGroups[index]
        if (!authorizeCosmeticUpdate(existing, senderPublicKey, "wallpaper_changed")) return
        currentGroups[index] = existing.copy(
            groupWallpaperData = wallpaperBytes,
            groupWallpaperUpdatedAt = updatedAt,
            groupWallpaperUpdatedBy = updatedBy ?: existing.groupWallpaperUpdatedBy,
            updatedAt = System.currentTimeMillis()
        )
        _groups.value = currentGroups
        saveGroups()
        updateGroupConversations()
        Log.d(TAG, "🎨 Applied wallpaper_changed for ${existing.name} (${wallpaperBytes?.size ?: 0} bytes, by ${updatedBy?.take(8)})")
    }

    /**
     * Handle incoming `group_picture_changed` envelope.
     */
    /**
     * Membership-only gate for the cosmetic envelopes. iOS applies picture/wallpaper/pin from any
     * standing member (`authorizeGroupUpdate` returns `apply: true` for them,
     * GroupMessaging.swift:895-903) and from nobody else.
     */
    private fun authorizeCosmeticUpdate(group: Group, senderPublicKey: String?, what: String): Boolean {
        val decision = GroupUpdateAuthorizer.authorize(
            currentMemberKeys = group.memberPublicKeys,
            currentAdminKeys = group.adminPublicKeys,
            currentType = group.effectiveGroupType,
            sender = senderPublicKey
        )
        if (!decision.apply) {
            Log.w(TAG, "🛑 $what ignored (${decision.reason}) for '${group.name}'")
            return false
        }
        return true
    }

    private fun handleGroupPictureChanged(json: JSONObject, senderPublicKey: String? = null) {
        val groupId = json.optString("groupId", "")
        if (groupId.isEmpty()) return
        val pictureBase64 = json.optString("groupImage", "")
        val pictureBytes = if (pictureBase64.isNotEmpty()) {
            try { Base64.decode(pictureBase64, Base64.NO_WRAP) } catch (_: Exception) { null }
        } else null

        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index < 0) {
            Log.w(TAG, "🖼️ group_picture_changed: group not found: ${groupId.take(8)}")
            return
        }
        val existing = currentGroups[index]
        if (!authorizeCosmeticUpdate(existing, senderPublicKey, "group_picture_changed")) return
        currentGroups[index] = existing.copy(
            groupImage = pictureBytes,
            updatedAt = System.currentTimeMillis()
        )
        _groups.value = currentGroups
        saveGroups()
        updateGroupConversations()
        Log.d(TAG, "🖼️ Applied group_picture_changed for ${existing.name} (${pictureBytes?.size ?: 0} bytes)")
    }

    private fun handleKeyRotation(json: JSONObject, senderPublicKey: String? = null) {
        val groupId = json.getString("groupId")
        val encryptionKeyStr = json.getString("encryptionKey")
        val encryptionKey = android.util.Base64.decode(encryptionKeyStr, android.util.Base64.NO_WRAP)

        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index >= 0) {
            val group = currentGroups[index]
            // Swapping the group key silently cuts every member off the thread. Only a standing
            // member may do it; `apply == false` is the sender-not-a-member / replay case.
            val decision = GroupUpdateAuthorizer.authorize(
                currentMemberKeys = group.memberPublicKeys,
                currentAdminKeys = group.adminPublicKeys,
                currentType = group.effectiveGroupType,
                sender = senderPublicKey
            )
            if (!decision.apply) {
                Log.w(TAG, "🛑 key_rotation ignored (${decision.reason}) for '${group.name}'")
                return
            }
            currentGroups[index] = currentGroups[index].copy(encryptionKey = encryptionKey)
            _groups.value = currentGroups
            saveGroups()
        }
    }

    private fun updateGroupConversations() {
        val conversations = _groups.value.map { group ->
            val links = _commentLinks.value
            // __GROUP_COMMENTS_2026_11_21__ comments are not timeline rows (§2.12.4e).
            val messages = (_groupMessages.value[group.id] ?: emptyList())
                .let { l -> if (links.isEmpty()) l else l.filter { it.id.lowercase(Locale.US) !in links } }
            val lastMessage = messages.maxByOrNull { it.timestamp }
            val unreadCount = messages.count { !it.isRead && !it.isFromMe }

            GroupConversation(
                group = group,
                lastMessage = lastMessage,
                unreadCount = unreadCount,
                memberNames = emptyMap()  // Would be populated from contacts
            )
        }.sortedByDescending { it.lastMessage?.timestamp ?: it.group.createdAt }

        _groupConversations.value = conversations
    }

    // =========================================================================
    // PERSISTENCE
    // =========================================================================

    private fun loadGroups() {
        try {
            // __LEAK_FIX_2026_09_25__ sealed store (migrates groups.xml on first read).
            val groupsJson = readDoc(GroupLocalStore.Doc.GROUPS) ?: return
            val jsonArray = JSONArray(groupsJson)
            val loadedGroups = mutableListOf<Group>()

            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                val membersArray = json.getJSONArray("members")
                val adminsArray = json.getJSONArray("admins")

                val members = mutableListOf<String>()
                for (j in 0 until membersArray.length()) {
                    members.add(membersArray.getString(j))
                }

                val admins = mutableListOf<String>()
                for (j in 0 until adminsArray.length()) {
                    admins.add(adminsArray.getString(j))
                }

                // Restore encryption key from base64
                val encryptionKey = json.optString("encryptionKey", "").let { keyStr ->
                    if (keyStr.isNotEmpty()) Base64.decode(keyStr, Base64.NO_WRAP) else null
                }

                // Restore group image from base64
                val groupImage = json.optString("groupImage", "").let { imgStr ->
                    if (imgStr.isNotEmpty()) Base64.decode(imgStr, Base64.NO_WRAP) else null
                }
                // Restore per-group wallpaper from base64
                val groupWallpaperData = json.optString("groupWallpaperData", "").let { wpStr ->
                    if (wpStr.isNotEmpty()) try { Base64.decode(wpStr, Base64.NO_WRAP) } catch (_: Exception) { null } else null
                }
                val groupWallpaperUpdatedBy = json.optString("groupWallpaperUpdatedBy", "").ifEmpty { null }
                val groupWallpaperUpdatedAt: Long? = if (json.has("groupWallpaperUpdatedAt")) json.optLong("groupWallpaperUpdatedAt", 0L).takeIf { it > 0 } else null
                val pinnedMessageId = json.optString("pinnedMessageId", "").ifEmpty { null }

                val group = Group(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    description = json.optString("description", ""),
                    creatorPublicKey = json.getString("creator"),
                    memberPublicKeys = members,
                    adminPublicKeys = admins,
                    evictedMemberKeys = json.optJSONArray("evicted")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotEmpty) }
                    } ?: emptyList(),
                    createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                    isPublic = json.optBoolean("isPublic", false),
                    isMuted = json.optBoolean("isMuted", false),
                    groupType = json.optString("groupType", "").takeIf { it.isNotEmpty() }
                        ?.let { GroupType.fromValue(it) },
                    encryptionKey = encryptionKey,
                    groupImage = groupImage,
                    groupWallpaperData = groupWallpaperData,
                    groupWallpaperUpdatedBy = groupWallpaperUpdatedBy,
                    groupWallpaperUpdatedAt = groupWallpaperUpdatedAt,
                    pinnedMessageId = pinnedMessageId,
                    pinnedBy = json.optString("pinnedBy", "").ifEmpty { null },
                    avatar = json.optString("avatar", "").ifEmpty { null }
                )
                loadedGroups.add(group)
            }

            _groups.value = loadedGroups
            loadMessages()
            updateGroupConversations()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load groups: ${e.message}")
        }
    }

    fun applyiOSGroupUpdateBroadcast(
        groupId: String,
        groupName: String?,
        membersJson: String?,
        groupImageBytes: ByteArray? = null,
        adminPublicKey: String? = null,
        groupType: String? = null,   // iOS GroupType raw value: "public", "admin_only", "collaborative"
        groupWallpaperBytes: ByteArray? = null,
        groupWallpaperUpdatedBy: String? = null,
        groupWallpaperUpdatedAtMs: Long? = null,
        // 📌 iOS carries the pin inside the same payload (`pinnedMessageId`, swift:840-845).
        // `hasPinnedField` distinguishes "unpinned" (key present, null) from "not carried".
        pinnedMessageId: String? = null,
        hasPinnedField: Boolean = false,
        // 📌 Who pinned it (iOS `pinnedBy`, swift:104). Absent → iOS itself falls back
        // to `adminPublicKey` (swift:842), so we do the same below.
        pinnedBy: String? = null,
        // 🎨 Emoji avatar (iOS `avatar`, swift:112). `hasAvatarField` distinguishes
        // "cleared" from "not carried" — an iOS update that omits the key CLEARS it
        // locally on iOS (swift:800-820), and we mirror that only when the field really
        // travelled, so an Android-native minimal update can't wipe it by omission.
        avatar: String? = null,
        hasAvatarField: Boolean = false,
        /** iOS `MessageGroup.createdAt` (swift:199). Null → keep whatever we already have. */
        createdAtMs: Long? = null,
        /**
         * Keys the SENDER has recorded as evicted. Unioned with ours below and never
         * subtracted: a stale or hostile definition that "forgets" an eviction must not
         * re-open the door, and a parallel session that has not seen the removal yet must
         * not undo it. iOS `applyGroupUpdate` (`GroupMessaging.swift:1091-1109`).
         */
        incomingEvictedKeys: List<String>? = null,
        /**
         * Who delivered this update, when the transport knows. Null = unauthenticated transport
         * (the VPS/IPFS route never carries a sender), which is treated exactly as iOS treats it:
         * everything applies EXCEPT the admin set. Defaulted so the existing `VPSClient`
         * call site keeps compiling and lands in the unauthenticated branch by construction.
         */
        senderPublicKey: String? = null,
        /** `description` when the key travelled (null = absent = keep). */
        incomingDescription: String? = null,
        /** Parsed `settings` when the key travelled (null = absent = keep). */
        incomingSettings: com.oshi.messenger.data.model.GroupSettings? = null
    ) {
        val currentGroups = _groups.value.toMutableList()

        // Helper: parse member public keys from iOS members JSON array
        fun parseMemberKeys(): List<String> {
            if (membersJson.isNullOrEmpty()) return emptyList()
            return try {
                val arr = JSONArray(membersJson)
                (0 until arr.length()).mapNotNull { i ->
                    arr.getJSONObject(i).optString("publicKey", "").takeIf { it.isNotEmpty() }
                }
            } catch (_: Exception) { emptyList() }
        }

        // Helper: parse admin public keys from iOS members JSON (isAdmin=true)
        fun parseAdminKeys(): List<String> {
            if (membersJson.isNullOrEmpty()) return emptyList()
            return try {
                val arr = JSONArray(membersJson)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.getJSONObject(i)
                    if (obj.optBoolean("isAdmin", false))
                        obj.optString("publicKey", "").takeIf { it.isNotEmpty() }
                    else null
                }
            } catch (_: Exception) { emptyList() }
        }

        // Try case-insensitive match (iOS sends uppercase UUID, Android may store lowercase)
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index < 0) {
            // 🔧 FIX: Group not found — CREATE it from the iOS broadcast data instead of discarding.
            // This is critical for groups created on iOS: without this, Android never learns about
            // the group and cannot decrypt IPFS-queued messages (no candidate key in groupKeyProvider).
            val name = groupName ?: run {
                Log.w(TAG, "📢 applyiOSGroupUpdateBroadcast: group $groupId not found and no name — cannot create")
                return
            }
            val memberKeys = parseMemberKeys()
            val adminKeys = parseAdminKeys()
            val resolvedType = groupType?.let { GroupType.fromValue(it) }
            val isPublic = resolvedType == GroupType.PUBLIC
            val myPublicKey = cryptoManager.getPublicKeyString()

            // 🔴 P0 (audit G-3) — iOS's unknown-group guard (GroupMessaging.swift:1044-1048).
            // This used to live only in `applyFullMessageGroupJson`, so the VPS/IPFS route, which
            // calls this function directly, re-created every group the user had just left — and
            // the roster it re-created still had them in it, which put them back on every iPhone.
            if (!GroupUpdateAuthorizer.isSelfInRoster(myPublicKey, memberKeys)) {
                Log.d(TAG, "⏭️ Ignoring group update — not a member of '$name' (${groupId.take(8)})")
                return
            }
            if (isTombstonedGroup(groupId)) {
                Log.d(TAG, "⏭️ Ignoring group update — '$name' (${groupId.take(8)}) was left on this device")
                return
            }
            // A definition that both lists us in the roster AND names us as evicted is a
            // definition written before our removal (or by a device that never saw it).
            // Re-creating the group from it would put us back into a group we were removed
            // from; iOS treats the same pair as a removal (`GroupMessaging.swift:1218-1226`).
            val incomingEvictedCanon = (incomingEvictedKeys ?: emptyList())
                .map { GroupUpdateAuthorizer.canonicalIdentity(it) }.toSet()
            if (!myPublicKey.isNullOrEmpty() &&
                GroupUpdateAuthorizer.canonicalIdentity(myPublicKey) in incomingEvictedCanon) {
                Log.d(TAG, "⏭️ Ignoring group update — we are named as removed from '$name' (${groupId.take(8)})")
                return
            }

            val newGroup = Group(
                id = groupId,
                name = name,
                creatorPublicKey = adminPublicKey ?: adminKeys.firstOrNull() ?: "",
                createdAt = createdAtMs ?: System.currentTimeMillis(),
                // Non-empty by construction: the roster guard above only passes when it contains us.
                memberPublicKeys = GroupUpdateAuthorizer.withoutEvicted(
                    memberKeys, incomingEvictedKeys ?: emptyList()
                ),
                adminPublicKeys = adminKeys.ifEmpty {
                    listOfNotNull(adminPublicKey).filter { it.isNotEmpty() }
                },
                isPublic = isPublic,
                groupImage = groupImageBytes,
                // 🔴 P1: storing only `isPublic` left `groupType` null, and
                // `Group.effectiveGroupType` then falls back to COLLABORATIVE — so an iOS
                // `admin_only` group let ANY Android member change its picture/wallpaper/name.
                groupType = resolvedType,
                groupWallpaperData = groupWallpaperBytes,
                groupWallpaperUpdatedBy = groupWallpaperUpdatedBy,
                groupWallpaperUpdatedAt = groupWallpaperUpdatedAtMs,
                pinnedMessageId = pinnedMessageId,
                // iOS's own fallback (GroupMessaging.swift:842) applied at ingest, so the
                // attribution is right even if the sender omitted `pinnedBy`.
                pinnedBy = pinnedBy ?: adminPublicKey?.takeIf { pinnedMessageId != null },
                avatar = avatar,
                // Carried in from birth: a group we are only now learning about may already
                // have evictions behind it, and starting empty would let the evicted key
                // back in on the very next `member_added`.
                evictedMemberKeys = incomingEvictedKeys ?: emptyList(),
                description = incomingDescription?.let {
                    com.oshi.messenger.data.model.GroupSettings.truncate(it, com.oshi.messenger.data.model.GroupSettings.MAX_DESCRIPTION)
                } ?: ""
                // encryptionKey intentionally null: key is always derived from groupId via deriveGroupKey()
            )
            currentGroups.add(newGroup)
            _groups.value = currentGroups
            saveGroups()
            updateGroupConversations()
            // __GROUP_SETTINGS_2026_11_20__ a group we did not know = we just joined it. Settings
            // are taken only from a sender the definition itself names as admin (there is no
            // "our copy" yet), then the welcome message is shown once (spec §2.5).
            run {
                val senderCanon = senderPublicKey?.let { GroupUpdateAuthorizer.canonicalIdentity(it) }
                val senderIsAdmin = senderCanon != null && (adminKeys.ifEmpty { listOfNotNull(adminPublicKey) })
                    .any { GroupUpdateAuthorizer.canonicalIdentity(it) == senderCanon }
                if (incomingDescription != null) markDescriptionTouched(groupId)
                GroupSettingsMerge.merge(null, incomingSettings, senderIsAdmin)?.let { putSettings(groupId, it) }
                markJoined(groupId)
                maybeShowWelcome(groupId)
            }
            Log.d(TAG, "📢 ✅ Created new group from iOS broadcast: '$name' (${groupId.take(8)}, isPublic=$isPublic, members=${newGroup.memberPublicKeys.size})")
            return
        }

        val group = currentGroups[index]
        var updated = group

        // 🔴 P0 (audit G-2) — iOS `authorizeGroupUpdate` (GroupMessaging.swift:862-905), called at
        // :946. Everything below used to be written unconditionally, so one delivered payload
        // could rename the group, kick the roster, or self-promote to admin on every Android
        // member. A failed check downgrades what may be written; it does not drop the payload,
        // because picture/wallpaper/pin legitimately arrive from ordinary members.
        val decision = GroupUpdateAuthorizer.authorize(
            currentMemberKeys = group.memberPublicKeys,
            currentAdminKeys = group.adminPublicKeys,
            currentType = group.effectiveGroupType,
            sender = senderPublicKey
        )
        if (!decision.apply) {
            Log.w(TAG, "🛑 Group update ignored (${decision.reason}) for '${group.name}' from ${senderPublicKey?.take(8) ?: "unknown"}")
            return
        }
        if (decision.keepAdminSet || decision.keepMembership || decision.keepName) {
            Log.d(TAG, "🔒 Group update downgraded (${decision.reason}) for '${group.name}': " +
                "admin=${if (decision.keepAdminSet) "kept" else "taken"} " +
                "members=${if (decision.keepMembership) "kept" else "taken"} " +
                "name=${if (decision.keepName) "kept" else "taken"}")
        }

        if (!decision.keepName && !groupName.isNullOrEmpty()) {
            updated = updated.copy(name = groupName)
            Log.d(TAG, "📢 Group renamed: '${group.name}' → '$groupName'")
        }
        val memberKeys = parseMemberKeys()
        val adminKeys = parseAdminKeys()
        if (!decision.keepMembership && memberKeys.isNotEmpty()) {
            updated = updated.copy(
                memberPublicKeys = GroupUpdateAuthorizer.keepAdminsInRoster(
                    decision, memberKeys, group.memberPublicKeys, group.adminPublicKeys
                )
            )
        }
        // __EVICTION_TOMBSTONE_WAS_DEVICE_LOCAL_2026_11_04__ UNION, never the incoming
        // value alone -- tombstones must not shrink (iOS `GroupMessaging.swift:1091-1109`).
        // Then drop every evicted key from the roster we just accepted, because the roster
        // in a definition written before the eviction still lists them, and taking it whole
        // is how an evicted member reappeared on a device that had never evicted anyone.
        run {
            val union = GroupUpdateAuthorizer.mergeEvictedIfAuthorized(decision, updated.evictedMemberKeys, incomingEvictedKeys, group.adminPublicKeys)
            if (union.isNotEmpty()) {
                val keptMembers = GroupUpdateAuthorizer.withoutEvicted(updated.memberPublicKeys, union)
                if (keptMembers.size != updated.memberPublicKeys.size) {
                    Log.d(TAG, "🪦 ${updated.memberPublicKeys.size - keptMembers.size} evicted member(s) " +
                        "dropped from the incoming roster of '${updated.name}'")
                }
                updated = updated.copy(evictedMemberKeys = union, memberPublicKeys = keptMembers)
            }
        }
        if (decision.keepAdminSet) {
            // iOS `applyGroupUpdate` (swift:911-921): re-stamp the admin flags from OUR copy,
            // keyed by member, so a member the update introduces cannot arrive pre-promoted.
            // Only when the roster was actually replaced — otherwise our admin list stands as is
            // (Android keeps admins in a list of their own, and it may legitimately name someone
            // who is not in `memberPublicKeys`).
            if (!decision.keepMembership && memberKeys.isNotEmpty()) {
                updated = updated.copy(
                    adminPublicKeys = GroupUpdateAuthorizer.restampAdminSet(
                        group.adminPublicKeys, updated.memberPublicKeys
                    )
                )
            }
        } else if (adminKeys.isNotEmpty()) {
            updated = updated.copy(adminPublicKeys = adminKeys)
        }
        // __GROUP_TYPE_TAKEOVER_2026_11_20__ settings only from someone who may change them.
        val maySettings = GroupUpdateAuthorizer.mayChangeGroupSettings(decision)
        if (maySettings && groupImageBytes != null && groupImageBytes.isNotEmpty()) {
            updated = updated.copy(groupImage = groupImageBytes)
            Log.d(TAG, "📢 Group picture updated (${groupImageBytes.size} bytes)")
        }
        if (maySettings && groupWallpaperBytes != null && groupWallpaperBytes.isNotEmpty()) {
            updated = updated.copy(
                groupWallpaperData = groupWallpaperBytes,
                groupWallpaperUpdatedBy = groupWallpaperUpdatedBy ?: updated.groupWallpaperUpdatedBy,
                groupWallpaperUpdatedAt = groupWallpaperUpdatedAtMs ?: updated.groupWallpaperUpdatedAt
            )
            Log.d(TAG, "📢 Group wallpaper updated (${groupWallpaperBytes.size} bytes)")
        }
        // `adminPublicKey` is the group owner straight off the payload — appending it
        // unconditionally was the cheapest self-promotion of all.
        if (!decision.keepAdminSet &&
            !adminPublicKey.isNullOrEmpty() && !updated.adminPublicKeys.contains(adminPublicKey)) {
            updated = updated.copy(adminPublicKeys = updated.adminPublicKeys + adminPublicKey)
        }
        if (groupType != null && GroupUpdateAuthorizer.mayChangeGroupType(decision)) {
            // 🔴 P1 fix: apply the FULL permission model, not just `isPublic`. Without
            // `groupType`, `effectiveGroupType` degrades an `admin_only` iOS group to
            // COLLABORATIVE and every Android member gains admin powers over it.
            val resolved = GroupType.fromValue(groupType)
            updated = updated.copy(
                groupType = resolved,
                isPublic = resolved == GroupType.PUBLIC
            )
            Log.d(TAG, "📢 Group type applied: $groupType → $resolved")
        }
        if (maySettings && hasPinnedField && pinnedMessageId != updated.pinnedMessageId) {
            updated = updated.copy(
                pinnedMessageId = pinnedMessageId,
                // iOS attributes an incoming pin to `pinnedBy ?? adminPublicKey`
                // (GroupMessaging.swift:842) and an unpin to the OLD `pinnedBy`
                // (`:844`) — so a cleared pin keeps no stale attribution.
                pinnedBy = if (pinnedMessageId == null) null
                    else pinnedBy ?: adminPublicKey ?: updated.adminPublicKeys.firstOrNull()
            )
            Log.d(TAG, "📌 Pin synced: ${pinnedMessageId?.take(8) ?: "(cleared)"} by ${pinnedBy?.take(8) ?: "(admin)"}")
        }
        if (maySettings && hasAvatarField && avatar != updated.avatar) {
            updated = updated.copy(avatar = avatar)
            Log.d(TAG, "🎨 Group avatar synced: ${avatar ?: "(cleared)"}")
        }
        // __GROUP_SETTINGS_2026_11_20__ description (shipped rename rule) and settings (admin only).
        if (maySettings && incomingDescription != null) {
            updated = updated.copy(description = com.oshi.messenger.data.model.GroupSettings.truncate(
                incomingDescription, com.oshi.messenger.data.model.GroupSettings.MAX_DESCRIPTION))
            markDescriptionTouched(group.id)
        }
        val settingsBefore = settingsOf(group.id)
        val settingsAfter = GroupSettingsMerge.merge(
            settingsBefore, incomingSettings, GroupUpdateAuthorizer.mayChangeGroupType(decision)
        )
        if (incomingSettings != null && !GroupUpdateAuthorizer.mayChangeGroupType(decision)) {
            Log.w(TAG, "🛑 settings ignored (not an admin: ${decision.reason}) for '${group.name}'")
        }
        if (settingsAfter != settingsBefore) putSettings(group.id, settingsAfter)
        // A stub from an invite link becomes the real group here (spec §2.5).
        if (settingsAfter?.welcome?.isNotBlank() == true) maybeShowWelcome(group.id)
        recordAdminEvents(group.id, GroupAdminLog.diff(
            group, updated, settingsBefore, settingsAfter,
            actor = senderPublicKey ?: "", at = System.currentTimeMillis()
        ))

        // Always update timestamp to force StateFlow emission even if content is "same"
        currentGroups[index] = updated.copy(updatedAt = System.currentTimeMillis())
        _groups.value = currentGroups
        saveGroups()
        updateGroupConversations()
    }


    // =========================================================================
    // GROUP SETTINGS  (docs/GROUP_INFO_SETTINGS_SPEC.md)  __GROUP_SETTINGS_2026_11_20__
    // =========================================================================
    //
    // Side store, sealed like everything else in `GroupLocalStore`, keyed by UPPER-case
    // group id. Not a Room column on purpose (see GroupSettings). Per group:
    //   settings (wire object), descSet, autoTranslate, welcomeShown, joinedAt.

    private fun gk(groupId: String) = groupId.uppercase(Locale.US)

    private fun extras(): MutableMap<String, JSONObject> {
        extrasCache?.let { return it }
        synchronized(extrasLock) {
            extrasCache?.let { return it }
            val map = mutableMapOf<String, JSONObject>()
            runCatching {
                readDoc(GroupLocalStore.Doc.GROUP_EXTRAS)?.let { raw ->
                    val o = JSONObject(raw)
                    for (k in o.keys()) o.optJSONObject(k)?.let { map[k] = it }
                }
            }
            extrasCache = map
            _groupSettings.value = map.mapNotNull { (k, v) ->
                com.oshi.messenger.data.model.GroupSettings.parseOrNull(v.optJSONObject("settings"))?.let { k to it }
            }.toMap()
            _autoTranslate.value = map.filterValues { it.optBoolean("autoTranslate") }.keys
            return map
        }
    }

    private fun editExtras(groupId: String, edit: (JSONObject) -> Unit) {
        synchronized(extrasLock) {
            val map = extras()
            val o = map.getOrPut(gk(groupId)) { JSONObject() }
            edit(o)
            val snapshot = JSONObject().apply { map.forEach { (k, v) -> put(k, v) } }.toString()
            persist(GroupLocalStore.Doc.GROUP_EXTRAS, snapshot)
        }
    }

    fun settingsOf(groupId: String): com.oshi.messenger.data.model.GroupSettings? {
        extras()
        return _groupSettings.value[gk(groupId)]
    }

    private fun putSettings(groupId: String, settings: com.oshi.messenger.data.model.GroupSettings?) {
        editExtras(groupId) { o -> if (settings == null) o.remove("settings") else o.put("settings", settings.toJson()) }
        _groupSettings.value = _groupSettings.value.toMutableMap().apply {
            if (settings == null) remove(gk(groupId)) else put(gk(groupId), settings)
        }
    }

    private fun isDescriptionTouched(groupId: String): Boolean =
        extras()[gk(groupId)]?.optBoolean("descSet") == true

    private fun markDescriptionTouched(groupId: String) {
        if (!isDescriptionTouched(groupId)) editExtras(groupId) { it.put("descSet", true) }
    }

    fun isAutoTranslate(groupId: String): Boolean { extras(); return gk(groupId) in _autoTranslate.value }

    fun setAutoTranslate(groupId: String, on: Boolean) {
        editExtras(groupId) { it.put("autoTranslate", on) }
        _autoTranslate.value = if (on) _autoTranslate.value + gk(groupId) else _autoTranslate.value - gk(groupId)
    }

    private fun markJoined(groupId: String) = editExtras(groupId) { it.put("joinedAt", System.currentTimeMillis()) }

    /** §2.5: once per group, only within a day of joining on this device. */
    private fun maybeShowWelcome(groupId: String) {
        val e = extras()[gk(groupId)] ?: return
        if (e.optBoolean("welcomeShown")) return
        val joinedAt = e.optLong("joinedAt", 0L)
        if (joinedAt == 0L || System.currentTimeMillis() - joinedAt > 24 * 3600 * 1000L) return
        val welcome = settingsOf(groupId)?.welcome?.takeIf { it.isNotBlank() } ?: return
        editExtras(groupId) { it.put("welcomeShown", true) }
        postSystemMessage(groupId, context.getString(com.oshi.messenger.R.string.gs_welcome_row, welcome))
    }

    // ---- comments (spec §2.12) ----------------------------------------------

    fun isCommentId(messageId: String): Boolean = _commentLinks.value.containsKey(messageId.lowercase(Locale.US))

    /** The post a stored comment belongs to, or null when [messageId] is not a comment. */
    fun commentPostOf(messageId: String): String? = _commentLinks.value[messageId.lowercase(Locale.US)]

    private fun recordCommentLink(commentId: String, postId: String) {
        while (true) {
            val cur = _commentLinks.value
            if (_commentLinks.compareAndSet(cur, cur + (commentId.lowercase(Locale.US) to postId))) break
        }
    }

    private fun isEvictedFrom(groupId: String, key: String): Boolean {
        val g = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return false
        val c = GroupUpdateAuthorizer.canonicalIdentity(key)
        return g.evictedMemberKeys.any { GroupUpdateAuthorizer.canonicalIdentity(it) == c }
    }

    private fun releaseCommentOrphans(groupId: String, postId: String) {
        val held = commentOrphans.release(groupId, postId, System.currentTimeMillis())
        if (held.isEmpty()) return
        Log.d(TAG, "💬 replaying ${held.size} comment(s) that arrived before post ${postId.take(8)}")
        for (o in held) handleIncomingGroupMessage(o.rawContent, o.sender)
    }

    /** §2.12.5: a comment rings only when it answers MY comment or mentions me. */
    private fun notifyCommentIfForMe(groupId: String, comment: GroupMessage, mentionsMe: Boolean) {
        val me = cryptoManager.getPublicKeyString()
        val target = comment.replyToId?.let { rid ->
            _groupMessages.value[groupId]?.let { l -> l.indexOfMessageId(rid).takeIf { it >= 0 }?.let { l[it] } }
        }
        val repliesToMine = target != null && isCommentId(target.id) &&
            (target.isFromMe || GroupUpdateAuthorizer.canonicalIdentity(target.senderPublicKey) == GroupUpdateAuthorizer.canonicalIdentity(me))
        if (!repliesToMine && !mentionsMe) return
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return
        if (group.isMuted && !mentionsMe) return
        if (com.oshi.messenger.data.repository.MessageRepository.isSenderBlockedCached(comment.senderPublicKey)) return
        showGroupMessageNotification(
            groupId, group.name, comment.senderName,
            context.getString(R.string.cm_notif_reply, comment.senderName) + ": " + comment.content.take(120),
            isMuted = false
        )
    }

    enum class CommentSendResult { SENT, INACTIVE, NO_POST, TOO_FAST, EMPTY }

    /**
     * Send a comment under [postId] (§2.12.3). [replyTo] = the comment being answered, or null
     * for a top-level comment. Text only.
     */
    fun sendComment(groupId: String, postId: String, text: String, replyTo: GroupMessage? = null): CommentSendResult {
        val body = GroupComments.clamp(text.trim())
        if (body.isEmpty()) return CommentSendResult.EMPTY
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return CommentSendResult.NO_POST
        if (settingsOf(group.id)?.commentsActive != true) return CommentSendResult.INACTIVE
        val stored = _groupMessages.value[group.id] ?: emptyList()
        val post = stored.indexOfMessageId(postId).takeIf { it >= 0 }?.let { stored[it] }
        if (post == null || post.isDeleted || isCommentId(post.id) || !isAdminOf(group.id, post.senderPublicKey)) {
            return CommentSendResult.NO_POST
        }
        val quoted = replyTo?.takeIf { isCommentId(it.id) && commentPostOf(it.id).equals(post.id, ignoreCase = true) } ?: post
        if (!commentSendRate.tryAcquire(gk(group.id), GroupComments.SEND_PER_MINUTE, System.currentTimeMillis())) {
            return CommentSendResult.TOO_FAST
        }
        val me = cryptoManager.getPublicKeyString()
        val envelope = buildReplyEnvelope(
            content = body,
            originalMessageId = canonicalGroupId(quoted.id),
            originalSenderKey = quoted.senderPublicKey,
            originalText = quoted.displayContent,
            originalTimestampMs = quoted.timestamp,
            originalMediaType = when (quoted.mediaType) {
                MediaType.IMAGE -> "photo"; MediaType.VIDEO -> "video"; MediaType.AUDIO -> "audio"
                MediaType.DOCUMENT -> "document"; MediaType.LOCATION -> "location"; else -> null
            }
        )
        val message = GroupMessage(
            id = UUID.randomUUID().toString(),
            groupId = group.id,
            senderPublicKey = me,
            senderName = "Me",
            content = body,
            timestamp = System.currentTimeMillis(),
            isFromMe = true,
            isRead = true,
            replyToId = quoted.id,
        )
        recordCommentLink(message.id, post.id)
        addMessageToGroup(group.id, message)
        updateGroupConversations()
        saveMessages()
        // Push: only the author of the comment we answer (never ourselves); top-level = nobody.
        val wake = if (quoted !== post && !quoted.isFromMe &&
            GroupUpdateAuthorizer.canonicalIdentity(quoted.senderPublicKey) != GroupUpdateAuthorizer.canonicalIdentity(me)
        ) setOf(quoted.senderPublicKey) else emptySet()
        scope.launch {
            try {
                sendMessageToMembers(group, message.copy(content = envelope),
                    commentOn = canonicalGroupId(post.id), wakeOnly = wake)
            } catch (e: Exception) {
                Log.w(TAG, "💬 comment send failed: ${e.message}")
            }
        }
        return CommentSendResult.SENT
    }

    fun isAdminOf(groupId: String, key: String?): Boolean {
        if (key.isNullOrEmpty()) return false
        val g = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return false
        val c = GroupUpdateAuthorizer.canonicalIdentity(key)
        return g.adminPublicKeys.any { GroupUpdateAuthorizer.canonicalIdentity(it) == c } ||
            (g.creatorPublicKey.isNotEmpty() && GroupUpdateAuthorizer.canonicalIdentity(g.creatorPublicKey) == c &&
                g.adminPublicKeys.isEmpty())
    }

    /** §2.4 — bots are governed by their own admission, not by the post policy. */
    fun isAllowedToPost(groupId: String, sender: String): Boolean {
        if (sender.startsWith("bot:")) return true
        val s = settingsOf(groupId) ?: return true
        if (s.dissolved) return false
        return s.mayPost(isAdminOf(groupId, sender))
    }

    // ---- admin log ----------------------------------------------------------

    fun adminLogOf(groupId: String): List<GroupAdminLog.Event> {
        loadAdminLog()
        return GroupAdminLog.prune(_adminLog.value[gk(groupId)].orEmpty(), System.currentTimeMillis())
    }

    private fun loadAdminLog() {
        if (adminLogLoaded) return
        synchronized(extrasLock) {
            if (adminLogLoaded) return
            _adminLog.value = GroupAdminLog.decode(readDoc(GroupLocalStore.Doc.GROUP_ADMIN_LOG))
            adminLogLoaded = true
        }
    }

    private fun recordAdminEvents(groupId: String, events: List<GroupAdminLog.Event>) {
        if (events.isEmpty()) return
        loadAdminLog()
        synchronized(extrasLock) {
            val now = System.currentTimeMillis()
            val all = _adminLog.value.toMutableMap()
            all[gk(groupId)] = GroupAdminLog.append(all[gk(groupId)].orEmpty(), events, now)
            // prune every group on write so a quiet group's old entries do not linger
            for (k in all.keys.toList()) all[k] = GroupAdminLog.prune(all[k].orEmpty(), now)
            _adminLog.value = all
            persist(GroupLocalStore.Doc.GROUP_ADMIN_LOG, GroupAdminLog.encode(all))
        }
    }

    // ---- local (admin) actions ---------------------------------------------

    enum class SettingsResult { OK, NOT_ALLOWED, INVALID, TAKEN, NETWORK, UNKNOWN_GROUP }

    private fun groupById(groupId: String): Group? = _groups.value.find { it.id.equals(groupId, ignoreCase = true) }

    private fun replaceGroup(updated: Group) {
        val list = _groups.value.toMutableList()
        val i = list.indexOfFirst { it.id.equals(updated.id, ignoreCase = true) }
        if (i < 0) return
        list[i] = updated
        _groups.value = list
        saveGroups()
        updateGroupConversations()
    }

    private fun me(): String = cryptoManager.getPublicKeyString()

    /** Apply a settings change made on THIS device by an admin, log it, broadcast the definition. */
    fun updateSettings(
        groupId: String,
        transform: (com.oshi.messenger.data.model.GroupSettings) -> com.oshi.messenger.data.model.GroupSettings,
    ): SettingsResult {
        val g = groupById(groupId) ?: return SettingsResult.UNKNOWN_GROUP
        if (!isAdminOf(g.id, me())) return SettingsResult.NOT_ALLOWED
        val before = settingsOf(g.id)
        val after = transform(before ?: com.oshi.messenger.data.model.GroupSettings.DEFAULT).let {
            // the one sticky field never goes back
            if (before?.dissolved == true) it.copy(dissolved = true) else it
        }.let {
            // __CHANNEL_WEB_2026_11_26__ the web preview exists only for a public channel
            // (spec §1); a group that stops being one drops the flag (the publisher purges).
            if (it.webPreview && !it.webEligible) it.copy(webPreview = false, webIndexing = false) else it
        }
        if (after == before) return SettingsResult.OK
        putSettings(g.id, after)
        recordAdminEvents(g.id, GroupAdminLog.diff(g, g, before, after, me(), System.currentTimeMillis()))
        val bumped = g.copy(updatedAt = System.currentTimeMillis())
        replaceGroup(bumped)
        scope.launch { runCatching { broadcastFullGroupUpdate(bumped) } }
        return SettingsResult.OK
    }

    /** Description — shipped rename rule (spec §2.2). Empty clears and propagates as `""`. */
    fun setDescription(groupId: String, text: String): SettingsResult {
        val g = groupById(groupId) ?: return SettingsResult.UNKNOWN_GROUP
        if (!g.canChangeName(me()) && !isAdminOf(g.id, me())) return SettingsResult.NOT_ALLOWED
        val clean = com.oshi.messenger.data.model.GroupSettings.truncate(text.trim(), com.oshi.messenger.data.model.GroupSettings.MAX_DESCRIPTION)
        if (clean == g.description) return SettingsResult.OK
        markDescriptionTouched(g.id)
        val updated = g.copy(description = clean, updatedAt = System.currentTimeMillis())
        replaceGroup(updated)
        recordAdminEvents(g.id, GroupAdminLog.diff(g, updated, null, null, me(), System.currentTimeMillis()))
        scope.launch { runCatching { broadcastFullGroupUpdate(updated) } }
        return SettingsResult.OK
    }

    /** Permission model (existing `type`), admin only — receive side already enforces it. */
    fun setGroupType(groupId: String, type: GroupType): SettingsResult {
        val g = groupById(groupId) ?: return SettingsResult.UNKNOWN_GROUP
        if (!isAdminOf(g.id, me())) return SettingsResult.NOT_ALLOWED
        if (g.effectiveGroupType == type) return SettingsResult.OK
        val updated = g.copy(groupType = type, isPublic = type == GroupType.PUBLIC, updatedAt = System.currentTimeMillis())
        replaceGroup(updated)
        recordAdminEvents(g.id, GroupAdminLog.diff(g, updated, null, null, me(), System.currentTimeMillis()))
        scope.launch { runCatching { broadcastFullGroupUpdate(updated) } }
        return SettingsResult.OK
    }

    /** Admins first: creator, then the others (spec §4.3 `joinKeys` order). */
    private fun adminsOrdered(g: Group): List<String> {
        val admins = g.adminPublicKeys.ifEmpty { listOf(g.creatorPublicKey) }.filter { it.isNotEmpty() }
        return (admins.filter { it == g.creatorPublicKey } + admins.filter { it != g.creatorPublicKey }).distinct()
    }

    /**
     * Make the group Public under [rawHandle] (or Private when null). The server is asked
     * FIRST; a handle it refused is never broadcast (spec §4.3).
     */
    suspend fun setVisibility(groupId: String, rawHandle: String?, showPreview: Boolean): SettingsResult {
        val g = groupById(groupId) ?: return SettingsResult.UNKNOWN_GROUP
        if (!isAdminOf(g.id, me())) return SettingsResult.NOT_ALLOWED
        val current = settingsOf(g.id) ?: com.oshi.messenger.data.model.GroupSettings.DEFAULT
        val client = handleClient ?: run {
            Log.w(TAG, "🔗 setVisibility: no registry client")
            return SettingsResult.NETWORK
        }
        Log.d(TAG, "🔗 setVisibility ${g.id.take(8)} handle=${rawHandle ?: "(private)"}")
        if (rawHandle == null) {
            current.handle?.let { old -> client.unregister(old) }
            return updateSettings(g.id) { it.copy(visibility = com.oshi.messenger.data.model.GroupSettings.Visibility.PRIVATE, handle = null, showPreview = false) }
        }
        val handle = com.oshi.messenger.data.model.GroupSettings.normalizeHandle(rawHandle) ?: return SettingsResult.INVALID
        val r = client.register(
            handle = handle,
            groupRef = com.oshi.messenger.data.model.GroupSettings.groupRef(g.id),
            joinKeys = adminsOrdered(g).take(8),
            controllers = adminsOrdered(g).take(16),
            previewName = if (showPreview) g.name else null,
            previewDescription = if (showPreview) g.description.ifBlank { null } else null,
            previewMemberCount = if (showPreview) g.memberPublicKeys.size else null,
        )
        return when (r) {
            V2GroupHandleClient.Result.OK -> {
                editExtras(g.id) { it.put("handleRefreshedAt", System.currentTimeMillis()) }
                updateSettings(g.id) {
                    it.copy(visibility = com.oshi.messenger.data.model.GroupSettings.Visibility.PUBLIC, handle = handle, showPreview = showPreview)
                }
            }
            V2GroupHandleClient.Result.TAKEN -> SettingsResult.TAKEN
            V2GroupHandleClient.Result.INVALID -> SettingsResult.INVALID
            V2GroupHandleClient.Result.NETWORK -> SettingsResult.NETWORK
        }
    }

    /** Spec §3.2 — a fresh token; links without it stop working on new-build admins. */
    fun resetInviteLink(groupId: String): SettingsResult =
        updateSettings(groupId) { it.copy(inviteToken = com.oshi.messenger.data.model.GroupSettings.newInviteToken()) }

    /** Private invite link (spec §3.2), `t` only once the link was reset. */
    fun inviteLinkFor(groupId: String): String? {
        val g = groupById(groupId) ?: return null
        val base = android.net.Uri.Builder().scheme("https").authority("oshi-messenger.com").path("/group")
            .appendQueryParameter("gid", g.id.uppercase(Locale.US))
            .appendQueryParameter("name", g.name)
            .appendQueryParameter("inviter", me())
        settingsOf(g.id)?.inviteToken?.let { base.appendQueryParameter("t", it) }
        return base.build().toString()
    }

    /** Spec §2.7 — lifts the tombstone on THIS device only (tombstones are a union). */
    fun unbanMember(groupId: String, key: String): SettingsResult {
        val g = groupById(groupId) ?: return SettingsResult.UNKNOWN_GROUP
        if (!isAdminOf(g.id, me())) return SettingsResult.NOT_ALLOWED
        val c = GroupUpdateAuthorizer.canonicalIdentity(key)
        replaceGroup(g.copy(evictedMemberKeys = g.evictedMemberKeys.filter { GroupUpdateAuthorizer.canonicalIdentity(it) != c }))
        return SettingsResult.OK
    }

    /** Spec §2.9 "Delete for everyone". */
    suspend fun dissolveGroup(groupId: String): SettingsResult {
        val g = groupById(groupId) ?: return SettingsResult.UNKNOWN_GROUP
        if (!isAdminOf(g.id, me())) return SettingsResult.NOT_ALLOWED
        val handle = settingsOf(g.id)?.handle
        val r = updateSettings(g.id) { it.copy(dissolved = true) }
        if (r != SettingsResult.OK) return r
        val dissolved = groupById(g.id) ?: g
        runCatching { broadcastFullGroupUpdate(dissolved) }
        val companion = JSONObject().apply { put("type", "deleted"); put("groupId", g.id) }
        runCatching { sendGroupUpdateRaw("$GROUP_UPDATE_PREFIX$companion", g.memberPublicKeys.filter { it != me() }) }
        if (handle != null) handleClient?.unregister(handle)
        deleteGroup(g.id)
        return SettingsResult.OK
    }

    /**
     * Joiner side of a public link (spec §3.1 step 4): one `join_request` to each admin the
     * registry named. No stub group — the joiner does not know the group id.
     */
    suspend fun sendJoinRequest(handle: String, joinKeys: List<String>): Boolean {
        val h = com.oshi.messenger.data.model.GroupSettings.normalizeHandle(handle) ?: return false
        val mine = me()
        if (mine.isEmpty()) return false
        val body = JSONObject().apply {
            put("type", "join_request"); put("handle", h); put("requesterPublicKey", mine)
        }
        val targets = joinKeys.filter { it.isNotBlank() && it != mine }.distinct().take(8)
        if (targets.isEmpty()) return false
        pendingPublicJoins[h] = System.currentTimeMillis()
        return runCatching { sendGroupUpdateRaw("$GROUP_UPDATE_PREFIX$body", targets); true }.getOrDefault(false)
    }
    private val pendingPublicJoins = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Admin side of a public link (spec §3.3). */
    private fun handleJoinRequest(json: JSONObject, senderPublicKey: String) {
        val handle = com.oshi.messenger.data.model.GroupSettings.normalizeHandle(json.optString("handle", "")) ?: return
        extras()
        val gid = _groupSettings.value.entries.firstOrNull { it.value.isPublic && it.value.handle == handle }?.key
        val g = gid?.let { groupById(it) }
        val now = System.currentTimeMillis()
        val recent = g?.let { joinAccepts[gk(it.id)]?.count { t -> now - t < 3600_000L } } ?: 0
        val d = GroupUpdateAuthorizer.authorizeJoinRequest(
            sender = senderPublicKey,
            requesterFromBody = json.optString("requesterPublicKey", "").ifEmpty { null },
            requestHandle = handle,
            settings = g?.let { settingsOf(it.id) },
            currentMemberKeys = g?.memberPublicKeys.orEmpty(),
            evictedMemberKeys = g?.evictedMemberKeys.orEmpty(),
            weAreAdmin = g != null && isAdminOf(g.id, me()),
            recentAccepts = recent,
        )
        if (!d.accept || g == null) {
            Log.w(TAG, "🛑 join_request for @$handle from ${senderPublicKey.take(8)} refused (${d.reason})")
            return
        }
        if (d.alreadyMember) {
            scope.launch { runCatching { broadcastFullGroupUpdate(g, bumpVersion = false, recipientsOverride = listOf(senderPublicKey)) } }
            return
        }
        joinAccepts.getOrPut(gk(g.id)) { java.util.Collections.synchronizedList(mutableListOf()) }.apply {
            add(now); removeAll { now - it >= 3600_000L }
        }
        val updated = g.copy(
            memberPublicKeys = g.memberPublicKeys + senderPublicKey,
            updatedAt = now
        )
        replaceGroup(updated)
        recordAdminEvents(g.id, listOf(GroupAdminLog.Event(now, senderPublicKey, GroupAdminLog.JOINED_LINK, senderPublicKey)))
        Log.d(TAG, "👥 @$handle: ${senderPublicKey.take(8)} joined '${g.name}' via the public link")
        scope.launch { runCatching { broadcastFullGroupUpdate(updated) } }
    }

    /** Re-PUT the public handle at most weekly and whenever the admin set changed (spec §4.3). */
    fun refreshPublicHandlesIfDue() {
        val client = handleClient ?: return
        extras()
        for ((gid, s) in _groupSettings.value) {
            val handle = s.handle ?: continue
            if (!s.isPublic || s.dissolved) continue
            val g = groupById(gid) ?: continue
            if (!isAdminOf(g.id, me())) continue
            val e = extras()[gk(gid)]
            val adminsSig = adminsOrdered(g).joinToString(",")
            val due = System.currentTimeMillis() - (e?.optLong("handleRefreshedAt") ?: 0L) > 7 * 24 * 3600_000L ||
                e?.optString("handleAdmins") != adminsSig
            if (!due) continue
            scope.launch {
                val r = client.register(handle, com.oshi.messenger.data.model.GroupSettings.groupRef(g.id),
                    adminsOrdered(g).take(8), adminsOrdered(g).take(16),
                    if (s.showPreview) g.name else null,
                    if (s.showPreview) g.description.ifBlank { null } else null,
                    if (s.showPreview) g.memberPublicKeys.size else null)
                if (r == V2GroupHandleClient.Result.OK) editExtras(gid) {
                    it.put("handleRefreshedAt", System.currentTimeMillis()); it.put("handleAdmins", adminsSig)
                }
            }
        }
    }

    private val handleClient: V2GroupHandleClient? get() = handleClientLazy?.get()

    /** Resolve a public link (joiner side). null = unknown handle; throws on network errors. */
    suspend fun resolvePublicHandle(handle: String): V2GroupHandleClient.Resolved? {
        val h = com.oshi.messenger.data.model.GroupSettings.normalizeHandle(handle) ?: return null
        return handleClient?.resolve(h)
    }

    // =========================================================================
    // LEFT-GROUP TOMBSTONES  (audit G-3)
    // =========================================================================
    //
    // Refusing to materialise a group whose roster does not contain us closes the common case,
    // but not all of it: `handleGroupSyncRequest` on iOS (GroupMessaging.swift:1846-1862) shares
    // every group with any known contact and AUTO-ADDS the requester, so the roster that comes
    // back after we leave does contain us again. A tombstone is the only thing that survives
    // that. Cleared the moment the user deliberately re-joins or re-creates the id.

    // __LEAK_FIX_2026_09_25__ sealed store + in-memory cache: this is read on every inbound
    // group definition, and each read of a sealed file is a decrypt.
    @Volatile private var tombstoneCache: MutableSet<String>? = null

    // __GROUP_SELF_HEAL_2026_10_01__ per-group throttle for the orphan-path definition request
    private val lastSelfHealRequest = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun shouldSelfHealRequest(groupId: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastSelfHealRequest[groupId]
        if (last != null && now - last < 45_000L) return false
        lastSelfHealRequest[groupId] = now
        return true
    }

    @Synchronized
    private fun loadTombstonedGroupIds(): MutableSet<String> {
        tombstoneCache?.let { return it.toMutableSet() }
        val loaded = try {
            val raw = readDoc(GroupLocalStore.Doc.LEFT_IDS)
            if (raw == null) mutableSetOf() else {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotEmpty() } }
                    .toMutableSet()
            }
        } catch (_: Exception) { mutableSetOf() }
        tombstoneCache = loaded
        return loaded.toMutableSet()
    }

    @Synchronized
    private fun storeTombstones(ids: Set<String>) {
        tombstoneCache = ids.toMutableSet()
        persist(GroupLocalStore.Doc.LEFT_IDS, JSONArray(ids.toList()).toString())
    }

    private fun isTombstonedGroup(groupId: String): Boolean =
        loadTombstonedGroupIds().contains(canonicalGroupId(groupId))

    private fun tombstoneGroup(groupId: String) {
        val ids = loadTombstonedGroupIds()
        if (!ids.add(canonicalGroupId(groupId))) return
        storeTombstones(ids)
    }

    private fun clearGroupTombstone(groupId: String) {
        // __DEVSYNC_REJOIN_2026_09_23__ every caller is a deliberate (re)join: stamp it, so the
        // account's other devices see a join newer than their `left` (design §8.4 rejoin rule).
        recordDevSyncJoin(groupId, System.currentTimeMillis())
        liftGroupTombstone(groupId)
    }

    private fun liftGroupTombstone(groupId: String) {
        clearDevSyncLeave(groupId) // __DEVSYNC_LEAVE_2026_09_23__ a deliberate re-join lifts the synced leave too
        val ids = loadTombstonedGroupIds()
        if (!ids.remove(canonicalGroupId(groupId))) return
        storeTombstones(ids)
    }

    private fun saveGroups() {
        try {
            val jsonArray = JSONArray()
            _groups.value.forEach { group ->
                val json = JSONObject().apply {
                    put("id", group.id)
                    put("name", group.name)
                    put("description", group.description)
                    put("creator", group.creatorPublicKey)
                    put("members", JSONArray(group.memberPublicKeys))
                    put("admins", JSONArray(group.adminPublicKeys))
                    put("evicted", JSONArray(group.evictedMemberKeys))
                    put("createdAt", group.createdAt)
                    put("updatedAt", group.updatedAt)
                    put("isPublic", group.isPublic)
                    put("isMuted", group.isMuted)
                    // 🔴 P1: without this the resolved iOS group type was lost on every restart
                    // and `effectiveGroupType` fell back to COLLABORATIVE, silently re-opening
                    // an `admin_only` group's permissions.
                    group.groupType?.let { put("groupType", it.value) }
                    // Persist encryption key as base64
                    group.encryptionKey?.let {
                        put("encryptionKey", Base64.encodeToString(it, Base64.NO_WRAP))
                    }
                    // Persist group image as base64
                    group.groupImage?.let {
                        put("groupImage", Base64.encodeToString(it, Base64.NO_WRAP))
                    }
                    // Persist per-group wallpaper (≤500KB) and metadata
                    group.groupWallpaperData?.let {
                        put("groupWallpaperData", Base64.encodeToString(it, Base64.NO_WRAP))
                    }
                    group.groupWallpaperUpdatedBy?.let { put("groupWallpaperUpdatedBy", it) }
                    group.groupWallpaperUpdatedAt?.let { put("groupWallpaperUpdatedAt", it) }
                    group.pinnedMessageId?.let { put("pinnedMessageId", it) }
                    // 📌 / 🎨 iOS `MessageGroup.pinnedBy` + `avatar`
                    // (GroupMessaging.swift:104,112). Persisted or they'd be lost on
                    // restart and the next outbound broadcast would clear them on every
                    // iPhone in the group.
                    group.pinnedBy?.let { put("pinnedBy", it) }
                    group.avatar?.let { put("avatar", it) }
                }
                jsonArray.put(json)
            }
            persist(GroupLocalStore.Doc.GROUPS, jsonArray.toString()) // __LEAK_FIX_2026_09_25__
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save groups: ${e.message}")
        }
    }

    // OOM guard: we serialize the entire messages map into SharedPreferences on every incoming
    // message. Without a cap, the string grows until JSONStringer allocates multi-MB and OOMs
    // (crashed at 18 MB in the 00:45 log). With a cap, SharedPrefs stays under ~1 MB per group.
    // Older messages remain in the Room `group_messages` table via the repository.
    // __INIT_ORDER_2026_09_28__ A getter, not a field: read by code reachable from `init` (declared thousands of
    // lines above), where a plain `val` would still be 0 and trim every group to nothing.
    private val MAX_MESSAGES_PER_GROUP_PERSIST: Int get() = 300
    // Debounce: handleIncomingGroupMessage spams saveMessages; serializing everything every tick
    // is O(N) per call and O(N²) amortized. Coalesce to at most one write every 2s.
    @Volatile private var pendingSaveMessagesAt: Long = 0L
    private var saveMessagesJob: kotlinx.coroutines.Job? = null

    private fun saveMessages() {
        val now = System.currentTimeMillis()
        pendingSaveMessagesAt = now
        if (saveMessagesJob?.isActive == true) return
        saveMessagesJob = scope.launch(Dispatchers.IO) {
            delay(2000)  // debounce window
            saveMessagesNow()
        }
    }

    /**
     * __GROUP_MEDIA_MEMORY_2026_11_11__ A stored attachment's bytes, read from disk without
     * entering the store's LRU (a 60 MB video must not stay cached after the viewer closes).
     */
    fun loadMediaBytes(messageId: String): ByteArray? =
        try { mediaStore.openStream(messageId)?.use { it.readBytes() } } catch (e: Exception) { null }

    /** Same, streamed into [out] (document save). False when nothing is stored. */
    fun copyMediaTo(messageId: String, out: java.io.OutputStream): Boolean {
        val input = mediaStore.openStream(messageId) ?: return false
        input.use { it.copyTo(out, 64 * 1024) }
        return true
    }

    private fun saveMessagesNow() {
        // __GROUP_MEDIA_MEMORY_2026_11_11__ ids whose bytes are now safely on disk and too
        // large to keep on the heap: dropped from the in-memory map once persisted.
        val offloaded = HashSet<String>()
        try {
            val allMessages = _groupMessages.value
            val commentLinksSnapshot = _commentLinks.value
            val jsonObject = JSONObject()
            allMessages.forEach { (groupId, messages) ->
                val messagesArray = JSONArray()
                // Persist only the most recent N per group; full history stays in Room.
                val trimmed = if (messages.size > MAX_MESSAGES_PER_GROUP_PERSIST)
                    messages.takeLast(MAX_MESSAGES_PER_GROUP_PERSIST) else messages
                trimmed.forEach { msg ->
                    val msgJson = JSONObject().apply {
                        put("id", msg.id)
                        put("groupId", msg.groupId)
                        put("senderPublicKey", msg.senderPublicKey)
                        put("senderName", msg.senderName)
                        put("content", msg.content)
                        put("timestamp", msg.timestamp)
                        put("isFromMe", msg.isFromMe)
                        put("isRead", msg.isRead)
                        msg.mediaType?.let { put("mediaType", it.name) }
                        msg.mediaData?.let { data ->
                            if (groupMediaSink(data.size, maxInlineGroupMediaBytes) == GroupMediaSink.FILE) {
                                // M-3: large media goes to the shared file store, keyed by
                                // the message id — no path on the row, so a relocated
                                // filesDir (backup restore, app-data move) cannot orphan it
                                // the way the old absolute `mediaPath` did.
                                // `saveMessagesNow` rewrites the WHOLE document on a 2 s
                                // debounce, so an unconditional write here would re-flush
                                // every video in every group on every reaction. Same bytes
                                // already on disk ⇒ nothing to do.
                                val keep = GroupMediaMemory.keepInMemory(msg.mediaType, msg.isVideoNote, data.size.toLong())
                                if (mediaStore.sizeOf(msg.id) == data.size.toLong()) {
                                    put("mediaStored", true)
                                    if (!keep) offloaded += msg.id
                                } else if (mediaStore.save(msg.id, data)) {
                                    put("mediaStored", true)
                                    if (!keep) offloaded += msg.id
                                } else {
                                    // Losing the bytes is bad; a 20 MB base64 string in the
                                    // prefs XML is worse — it is read back on every launch.
                                    Log.e(TAG, "❌ Could not persist ${data.size / 1024} KB group attachment for ${msg.id.take(8)}")
                                }
                            } else {
                                put("mediaData", Base64.encodeToString(data, Base64.NO_WRAP))
                            }
                        }
                        msg.mediaFileName?.let { put("mediaFileName", it) }
                        msg.mediaDurationMs?.let { put("mediaDurationMs", it) }
                        if (msg.isVideoNote) put("isVideoNote", true)
                        msg.replyToId?.let { put("replyToId", it) }
                        // __GROUP_COMMENTS_2026_11_21__ the comment → post link lives only here.
                        commentLinksSnapshot[msg.id.lowercase(Locale.US)]?.let { put(GroupComments.FIELD, it) }
                        // ✏️ An edit lives ONLY here between launches — `content` keeps the
                        // original, exactly as iOS keeps `encryptedContent` untouched. Omit
                        // these and an edited bubble reverts to its original text on the
                        // next cold start.
                        msg.editedContent?.let { put("editedContent", it) }
                        msg.editedAt?.let { put("editedAt", it) }
                        // 🤖 The bot's inline keyboard lives ONLY here between launches
                        // (this prefs document is what the group thread renders from —
                        // Room is the archive, not the read path). Omit it and every
                        // button under a bot message disappears on the next cold start.
                        msg.replyMarkup?.let { put("replyMarkup", it) }
                        // __BOT_PUBLIC_ID_2026_09_16__ Persisted WITH the keyboard, not
                        // instead of it: a keyboard that survives a cold start without
                        // its bot id is a row of buttons that can no longer dispatch.
                        msg.botId?.let { put("botId", it) }
                        if (msg.isDeleted) put("isDeleted", true)
                        if (msg.reactions.isNotEmpty()) {
                            val reactionsJson = JSONObject()
                            msg.reactions.forEach { (key, emoji) -> reactionsJson.put(key, emoji) }
                            put("reactions", reactionsJson)
                        }
                    }
                    messagesArray.put(msgJson)
                }
                jsonObject.put(groupId, messagesArray)
            }
            persist(GroupLocalStore.Doc.MESSAGES, jsonObject.toString()) // __LEAK_FIX_2026_09_25__
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save messages: ${e.message}")
            return
        }
        if (offloaded.isNotEmpty()) {
            while (true) {
                val cur = _groupMessages.value
                val next = cur.mapValues { (_, list) ->
                    list.map { if (it.id in offloaded && it.mediaData != null) it.copy(mediaData = null) else it }
                }
                if (_groupMessages.compareAndSet(cur, next)) break
            }
            Log.d(TAG, "💾 ${offloaded.size} large group attachment(s) moved off the heap")
        }
    }

    /**
     * 🔴 ONE UNREADABLE MESSAGE USED TO ERASE EVERY GROUP'S HISTORY.
     *
     * The whole read sat under a single try/catch. Any row that failed to parse —
     * a `getString("id")` on an object written by a future build, a truncated
     * document, one bad `getJSONObject` — aborted the load, left `_groupMessages`
     * EMPTY, and then [saveMessagesNow] (a 2 s debounce, fired by the next
     * reaction, receipt or arrival) wrote that emptiness back over the prefs. The
     * failure was silent, total and permanent: one row, all groups, gone.
     *
     * That is the same defect the iOS audit found in the 1:1 store on 2026-09-12,
     * and the same remedy: parse each message on its own, keep every one that
     * reads, and skip only the ones that do not. A per-row guard cannot save a
     * broken row, but it stops a broken row taking the archive with it.
     */
    private fun loadMessages() {
        try {
            val messagesJson = readDoc(GroupLocalStore.Doc.MESSAGES) ?: return // __LEAK_FIX_2026_09_25__
            val jsonObject = JSONObject(messagesJson)
            val allMessages = mutableMapOf<String, List<GroupMessage>>()
            val links = HashMap<String, String>()
            var skipped = 0

            jsonObject.keys().forEach { groupId ->
                val messagesArray = jsonObject.optJSONArray(groupId) ?: return@forEach
                val messages = mutableListOf<GroupMessage>()
                for (i in 0 until messagesArray.length()) {
                    val msgJson = try {
                        messagesArray.getJSONObject(i)
                    } catch (e: Exception) {
                        skipped++
                        continue
                    }
                    val message = try {
                        GroupMessage(
                            id = msgJson.getString("id"),
                            groupId = msgJson.getString("groupId"),
                            senderPublicKey = msgJson.getString("senderPublicKey"),
                            senderName = msgJson.optString("senderName", ""),
                            content = msgJson.optString("content", ""),
                            timestamp = msgJson.optLong("timestamp", System.currentTimeMillis()),
                            isFromMe = msgJson.optBoolean("isFromMe", false),
                            isRead = msgJson.optBoolean("isRead", false),
                            mediaType = msgJson.optString("mediaType", "").let { typeStr ->
                                if (typeStr.isNotEmpty()) try { MediaType.valueOf(typeStr) } catch (_: Exception) { null } else null
                            },
                            mediaData = run {
                                val id = msgJson.getString("id")
                                val inline = msgJson.optString("mediaData", "").let { if (it.isNotEmpty()) try { Base64.decode(it, Base64.NO_WRAP) } catch (_: Exception) { null } else null }
                                // M-3 read order: inline → the shared file store → the LEGACY
                                // absolute `mediaPath`. The legacy leg is what keeps every blob
                                // written before M-3 rendering; it is read-only and disappears
                                // from a row the first time that row is saved again.
                                // __GROUP_MEDIA_MEMORY_2026_11_11__ a large document/video stays on
                                // disk; the bubble reads it on tap (`loadMediaBytes`/`copyMediaTo`).
                                val storedSize = if (inline == null) mediaStore.sizeOf(id) else -1L
                                when {
                                    inline != null -> inline
                                    storedSize >= 0 -> if (GroupMediaMemory.keepInMemory(
                                            msgJson.optString("mediaType", "").let { t ->
                                                try { MediaType.valueOf(t) } catch (_: Exception) { null }
                                            },
                                            msgJson.optBoolean("isVideoNote", false),
                                            storedSize
                                        )) mediaStore.load(id) else null
                                    else -> msgJson.optString("mediaPath", "").let { path -> if (path.isNotEmpty()) loadMediaFromFile(path) else null }
                                }
                            },
                            mediaFileName = msgJson.optString("mediaFileName", "").takeIf { it.isNotEmpty() },
                            mediaDurationMs = msgJson.optLong("mediaDurationMs", 0L).takeIf { it > 0L },
                            isVideoNote = msgJson.optBoolean("isVideoNote", false),
                            replyToId = if (msgJson.has("replyToId")) msgJson.getString("replyToId") else null,
                            reactions = if (msgJson.has("reactions")) {
                                val reactionsJson = msgJson.getJSONObject("reactions")
                                reactionsJson.keys().asSequence().associateWith { k -> reactionsJson.getString(k) }
                            } else emptyMap(),
                            // ✏️ An edit survives a cold start only if it is read back here.
                            editedContent = if (msgJson.has("editedContent") && !msgJson.isNull("editedContent"))
                                msgJson.getString("editedContent") else null,
                            editedAt = msgJson.optLong("editedAt", 0L).takeIf { it > 0L },
                            isDeleted = msgJson.optBoolean("isDeleted", false),
                            // 🤖 Read back verbatim; the renderer re-validates it.
                            replyMarkup = msgJson.optString("replyMarkup", "").takeIf { it.isNotEmpty() },
                            botId = msgJson.optString("botId", "").takeIf { it.isNotEmpty() }
                        )
                    } catch (e: Exception) {
                        // This row only. The archive is not forfeit for it.
                        skipped++
                        continue
                    }
                    messages.add(message)
                    GroupComments.read(msgJson)?.let { links[message.id.lowercase(Locale.US)] = it }
                }
                allMessages[groupId] = messages.sortedBy { it.timestamp }
            }

            _commentLinks.value = links
            _groupMessages.value = allMessages
            if (skipped > 0) Log.w(TAG, "⚠️ Skipped $skipped unreadable group message(s); the rest were loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load messages: ${e.message}")
        }
    }

    /**
     * Show local notification for incoming group message
     */
    private fun showGroupMessageNotification(
        groupId: String,
        groupName: String,
        senderName: String,
        preview: String,
        isMuted: Boolean,
        silent: Boolean = false,
    ) {
        // 👀 The user is READING this group right now. A banner here is pure friction:
        // the message is already on screen and already being marked read by
        // `GroupChatViewModel`'s arrival watcher, so the notification would only be
        // something to dismiss.
        //
        // This is the POLL path. The push path was gated on 2026-08-24
        // (`FCMService.kt:650-658`) and the 1:1 poll path with it
        // (`MessageRepository.showMessageNotification`), but `GroupManager.kt` was
        // outside that pass's territory, so a group message pulled by the poller still
        // raised a banner over its own open chat.
        //
        // Same registry, not a second one: [ActiveConversation] is true only while the
        // app is foregrounded AND this exact group is the thread on screen. Both checks
        // go through the one pure decision, [shouldRaiseGroupBanner].
        if (!shouldRaiseGroupBanner(isMuted, ActiveConversation.isGroupActive(groupId))) {
            Log.d(TAG, "🔕 No group banner for ${groupId.take(8)}… (muted=$isMuted, onScreen=${ActiveConversation.isGroupActive(groupId)})")
            return
        }
        // __ONE_BANNER_PER_MESSAGE_2026_09_23__ Same id as the FCM banner for this group
        // (case-insensitive), and no second post if that banner went up moments ago.
        val bannerId = MessageBanner.groupNotificationId(groupId)
        // __PREVIEW_ONE_IN_TWO_2026_11_24__ see MessageBanner.localPostDecision: the FCM
        // banner (generic) is updated quietly with the text instead of hiding it.
        val post = MessageBanner.localPostDecision(
            shownRecently = MessageBanner.shownRecently(context, bannerId),
            carriesText = !MessageBanner.anonymous(context)
                && MessageBanner.body(context, preview) != MessageBanner.genericBody(context)
        )
        if (post == MessageBanner.LocalPost.SKIP) {
            Log.d(TAG, "🔕 group banner for ${groupId.take(8)}… already up — not posting a second one")
            return
        }
        val quiet = silent || post == MessageBanner.LocalPost.UPDATE_QUIETLY
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("groupId", groupId)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, groupId.hashCode(), intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val notification = NotificationCompat.Builder(context, FCMService.CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                // 🛡 __STRICT_MODE_2026_11_22__ strict: no group name, no sender alias.
                .setContentTitle(if (MessageBanner.anonymous(context)) MessageBanner.anonymousTitle(context) else groupName)
                // Sender alias + generic body; the text only with previews explicitly ON.
                .setContentText(
                    if (MessageBanner.anonymous(context)) MessageBanner.genericBody(context)
                    else context.getString(R.string.notification_group_body, senderName, MessageBanner.body(context, preview))
                )
                .setAutoCancel(true)
                .setSound(if (quiet) null else soundUri)
                .setSilent(quiet)
                .setOnlyAlertOnce(post == MessageBanner.LocalPost.UPDATE_QUIETLY)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                // 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ no system "smart" actions on a protected chat's banner.
                .setAllowSystemGeneratedContextualActions(
                    com.oshi.messenger.data.model.AdvancedChatPrivacyPolicy.systemGeneratedRepliesAllowed(
                        com.oshi.messenger.service.AdvancedChatPrivacyStore.isOn(groupId)
                    )
                )
                // __BOT_E2E_2026_09_23__ Lock screen: generic public version, as FCMService.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, FCMService.CHANNEL_MESSAGES)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(context.getString(R.string.app_name))
                        .setContentText(context.getString(R.string.notification_new_message))
                        .build()
                )
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(bannerId, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show group notification: ${e.message}")
        }
    }

    /**
     * Toggle mute for a group
     */
    fun toggleGroupMute(groupId: String): Boolean {
        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index >= 0) {
            val group = currentGroups[index]
            val newMuted = !group.isMuted
            // __GROUP_MUTE_SYNC_2026_09_24__ the moment of the user's choice travels to the
            // account's other devices (devsync GROUPS muted/mutedAt, later wins).
            val prev = mutedAtOf(group.id) ?: Long.MIN_VALUE
            muteAtPrefs.edit().putLong(canonicalGroupId(group.id), maxOf(System.currentTimeMillis(), prev + 1)).apply()
            currentGroups[index] = group.copy(isMuted = newMuted)
            _groups.value = currentGroups
            saveGroups()
            updateGroupConversations()
            Log.d(TAG, "Group ${group.name} muted=$newMuted")
            return newMuted
        }
        return false
    }

    /**
     * Check if a group is muted
     */
    fun isGroupMuted(groupId: String): Boolean {
        return _groups.value.find { it.id.equals(groupId, ignoreCase = true) }?.isMuted == true
    }

    /**
     * Get group by ID
     */
    fun getGroupById(groupId: String): Group? {
        return _groups.value.find { it.id.equals(groupId, ignoreCase = true) }
    }

    /**
     * Get messages flow for a specific group
     */
    fun getGroupMessagesFlow(groupId: String): StateFlow<List<GroupMessage>> {
        return _groupMessages.asStateFlow().let { allMessages ->
            MutableStateFlow(allMessages.value[groupId] ?: emptyList())
        }
    }

    // =========================================================================
    // READ RECEIPTS
    // =========================================================================

    // Batched read receipt queue: groupId -> pending messageIds — task 1.3.5
    private val pendingReadReceipts = mutableMapOf<String, MutableSet<String>>()
    private val readReceiptFlushJobs = mutableMapOf<String, Job>()

    /**
     * Queue a read receipt for batching — flushed every 2s per group.
     * Avoids per-message network overhead matching iOS behavior (task 1.3.5).
     */
    fun broadcastReadReceipt(groupId: String, messageId: String) {
        synchronized(pendingReadReceipts) {
            pendingReadReceipts.getOrPut(groupId) { mutableSetOf() }.add(messageId)
        }
        readReceiptFlushJobs[groupId]?.cancel()
        readReceiptFlushJobs[groupId] = scope.launch {
            delay(2_000L)
            flushReadReceipts(groupId)
        }
    }

    private suspend fun flushReadReceipts(groupId: String) {
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return
        val myPublicKey = cryptoManager.getPublicKeyString()
        val messageIds = synchronized(pendingReadReceipts) {
            val ids = pendingReadReceipts[groupId]?.toList() ?: return
            pendingReadReceipts.remove(groupId)
            ids
        }
        if (messageIds.isEmpty()) return
        try {
            val updateJson = JSONObject().apply {
                put("type", "read_receipt_batch")
                put("groupId", groupId)
                put("messageIds", JSONArray(messageIds))
                put("senderPublicKey", myPublicKey)
                put("timestamp", System.currentTimeMillis())
            }
            sendGroupUpdateToMembers(group, updateJson)
            Log.d(TAG, "Flushed ${messageIds.size} read receipts for group $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush read receipts: ${e.message}")
        }
    }

    // =========================================================================
    // ID NORMALISATION — iOS renders every UUID UPPERCASE, Android lowercase.
    //
    // `GroupMessage.id` on iOS is a `UUID` (GroupMessaging.swift:342), so an
    // Android-minted lowercase id parsed by iOS (`UUID(uuidString:)`, case-insensitive)
    // comes back out of `uuidString` UPPERCASED. Every iOS group reaction / receipt /
    // deletion therefore targets the UPPERCASE form of an id Android stored lowercase.
    // A plain `==` drops it silently in both directions.
    //
    // This file already applies `equals(…, ignoreCase = true)` at ~20 sites for
    // `groupId` for exactly this reason; these helpers extend the same idiom to
    // `messageId`. Ids are compared case-insensitively and **stored verbatim** — never
    // case-folded at rest, because the stored id is what Android echoes back to iOS.
    // =========================================================================

    /** Case-insensitive message lookup, delegating to the pure companion helper. */
    private fun List<GroupMessage>.indexOfMessageId(messageId: String): Int =
        indexOfMessageId(this, messageId)

    /**
     * Resolve the wire `groupId` to the key actually used in `_groupMessages`.
     * `handleIncomingGroupMessage` already does this against `_groups`; the
     * GROUP_UPDATE handlers did not, so an UPPERCASE iOS groupId missed the map
     * outright — before the messageId comparison ever ran.
     *
     * Note this is the *inverse* of the companion's `canonicalGroupId`, which uppercases
     * an id for the **wire**. This one maps a wire id back to the **local** storage key.
     */
    private fun localGroupKey(rawGroupId: String): String =
        _groups.value.find { it.id.equals(rawGroupId, ignoreCase = true) }?.id
            ?: _groupMessages.value.keys.firstOrNull { it.equals(rawGroupId, ignoreCase = true) }
            ?: rawGroupId

    /**
     * Batched group read receipt. The sender (flushReadReceipts) emits
     * type="read_receipt_batch" with a messageIds ARRAY, but there was no matching
     * dispatch case and the singular handler read a "messageId" — so group read
     * receipts were dropped even Android→Android. Mark every id in the batch read.
     */
    private fun handleReadReceiptBatch(json: JSONObject) {
        val rawGroupId = json.optString("groupId", "")
        val idsArray = json.optJSONArray("messageIds") ?: return
        if (rawGroupId.isEmpty() || idsArray.length() == 0) return
        val groupId = localGroupKey(rawGroupId)
        val ids = HashSet<String>()
        for (k in 0 until idsArray.length()) ids.add(idsArray.optString(k))

        val currentMessages = _groupMessages.value.toMutableMap()
        val groupMsgs = currentMessages[groupId] ?: return
        val updated = applyReadReceiptToMessages(groupMsgs, ids)
        if (updated != null) {
            currentMessages[groupId] = updated
            _groupMessages.value = currentMessages
            saveMessages()
            updateGroupConversations()
            Log.d(TAG, "Batch read receipt applied for ${ids.size} messages in group $groupId")
        }
    }

    private fun handleReadReceipt(json: JSONObject) {
        val groupId = localGroupKey(json.getString("groupId"))
        val messageId = json.getString("messageId")

        val currentMessages = _groupMessages.value.toMutableMap()
        val groupMsgs = currentMessages[groupId]?.toMutableList() ?: return
        val index = groupMsgs.indexOfMessageId(messageId)
        if (index >= 0) {
            groupMsgs[index] = groupMsgs[index].copy(isRead = true)
            currentMessages[groupId] = groupMsgs
            _groupMessages.value = currentMessages
            saveMessages()
            updateGroupConversations()
            Log.d(TAG, "Read receipt applied for message $messageId in group $groupId")
        }
    }

    // =========================================================================
    // REACTIONS
    // =========================================================================

    /**
     * Broadcast a reaction (add or remove) for a group message to all members.
     * Creates a GROUP_UPDATE with action "reaction" and sends it
     * using the same envelope encryption as other group messages.
     */
    fun broadcastReaction(groupId: String, messageId: String, emoji: String, isRemoval: Boolean) {
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return
        val myPublicKey = cryptoManager.getPublicKeyString()

        // Apply locally first
        applyReactionLocally(groupId, messageId, myPublicKey, emoji, isRemoval)

        scope.launch {
            try {
                // Send as iOS-compatible group message: "🔥REACTION🔥{JSON}"
                // iOS parses this in MessageReactionManager.fromMessageString() and processes it
                // Android parses this in handleIncomingGroupMessage() and calls applyReactionLocally()
                val appleTimestamp = (System.currentTimeMillis() / 1000.0) - 978307200.0
                val senderName = contactAliasResolver?.invoke(myPublicKey) ?: myPublicKey.take(8)
                val reactionJson = JSONObject().apply {
                    // __GROUP_REACTION_ID_2026_09_27__ iOS stores the reaction under this
                    // exact string and looks it up with `message.id.uuidString` —
                    // UPPERCASE (GroupViews.swift:2845). An Android-minted id is lowercase,
                    // so every reaction on an Android-authored message was invisible on
                    // iPhones. Same convention as edit and delete. Android matches
                    // case-insensitively, so Android peers are unaffected.
                    put("messageId", canonicalGroupId(messageId))
                    put("emoji", emoji)
                    put("senderPublicKey", myPublicKey)
                    put("senderName", senderName)
                    put("timestamp", appleTimestamp)
                    put("action", if (isRemoval) "remove" else "add")
                }
                val reactionContent = "$REACTION_PREFIX$reactionJson"
                val reactionMessage = GroupMessage(
                    id = UUID.randomUUID().toString(),
                    groupId = groupId,
                    senderPublicKey = myPublicKey,
                    senderName = "Me",
                    content = reactionContent,
                    timestamp = System.currentTimeMillis(),
                    isFromMe = true
                )
                sendMessageToMembers(group, reactionMessage)
                Log.d(TAG, "🔥 Broadcast reaction '$emoji' (removal=$isRemoval) for message $messageId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast reaction: ${e.message}")
            }
        }
    }

    private fun handleReactionUpdate(json: JSONObject) {
        val groupId = json.getString("groupId")
        val messageId = json.getString("messageId")
        val emoji = json.getString("emoji")
        val senderPublicKey = json.getString("senderPublicKey")
        val isRemoval = json.optBoolean("isRemoval", false)

        applyReactionLocally(groupId, messageId, senderPublicKey, emoji, isRemoval)
        Log.d(TAG, "Reaction update applied: '$emoji' from ${senderPublicKey.take(8)} on message $messageId (removal=$isRemoval)")
    }

    private fun applyReactionLocally(rawGroupId: String, messageId: String, senderPublicKey: String, emoji: String, isRemoval: Boolean) {
        val groupId = localGroupKey(rawGroupId)
        val currentMessages = _groupMessages.value.toMutableMap()
        val groupMsgs = currentMessages[groupId] ?: return
        // Case-insensitive match: an iOS reaction targets `m.id.uuidString`, always UPPERCASE.
        val before = indexOfMessageId(groupMsgs, messageId).takeIf { it >= 0 }?.let { groupMsgs[it] }
        val updated = applyReactionToMessages(groupMsgs, messageId, senderPublicKey, emoji, isRemoval)
        if (updated != null) {
            currentMessages[groupId] = updated
            _groupMessages.value = currentMessages
            saveMessages()
            updateGroupConversations()
            // __GROUP_REACTION_NOTIFICATION_2026_11_16__ the 1:1 thread already rings
            // for "X reacted to your message" (MessageRepository, onIncomingReaction);
            // a group never did.
            runCatching {
                maybeNotifyGroupReaction(groupId, before, senderPublicKey, emoji, isRemoval)
            }.onFailure { Log.w(TAG, "group reaction notification: ${it.message}") }
        } else {
            Log.w(TAG, "Reaction target $messageId not found in group $groupId")
        }
    }

    private fun maybeNotifyGroupReaction(
        groupId: String,
        target: GroupMessage?,
        reactorKey: String,
        emoji: String,
        isRemoval: Boolean,
    ) {
        val myKey = cryptoManager.getPublicKeyString()
        val previous = target?.reactions?.entries
            ?.firstOrNull { sameMentionKey(it.key, reactorKey) }?.value
        if (!shouldNotifyGroupReaction(myKey, reactorKey, target, emoji, isRemoval, previous)) return
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return
        if (group.isMuted) return
        if (com.oshi.messenger.data.repository.MessageRepository.isSenderBlockedCached(reactorKey)) return
        val reactorName = runCatching { contactAliasResolver?.invoke(reactorKey) }.getOrNull()
            // A non-contact member: the name they last sent under in this group.
            ?: _groupMessages.value[groupId]?.lastOrNull {
                sameMentionKey(it.senderPublicKey, reactorKey) && it.senderName.isNotBlank() &&
                    it.senderName != "Unknown"
            }?.senderName
            ?: reactorKey.take(8)
        showGroupMessageNotification(
            groupId, group.name, reactorName,
            emoji + " " + context.getString(R.string.reaction_notification),
            isMuted = false,
            // Silent, like the 1:1 reaction banner: received reactions never buzz.
            silent = true
        )
    }

    // =========================================================================
    // MESSAGE DELETION BROADCAST
    // =========================================================================

    /**
     * Broadcast a message deletion to all group members.
     * Marks the message as deleted locally and sends a GROUP_UPDATE
     * with action "message_deleted" to all members.
     */
    fun broadcastMessageDeletion(groupId: String, messageId: String) {
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return
        val myPublicKey = cryptoManager.getPublicKeyString()

        // Mark as deleted locally
        applyMessageDeletionLocally(groupId, messageId)
        channelWebHooks?.let { h -> runCatching { h.onPostDeletedForEveryone(group.id, messageId) } } // __CHANNEL_WEB_2026_11_26__

        scope.launch {
            try {
                val updateJson = JSONObject().apply {
                    put("type", "message_deleted")
                    put("groupId", groupId)
                    put("messageId", messageId)
                    put("senderPublicKey", myPublicKey)
                    put("timestamp", System.currentTimeMillis())
                }

                // Legacy leg, kept deliberately: Android peers on 1.6.8 and earlier only
                // understand the `📢GROUP_UPDATE📢` form. Dropping it would break
                // Android→older-Android delete for everyone still on the old build.
                // Both legs are idempotent — `applyDeletionToMessages` returns null once
                // the row is already tombstoned — so a peer that understands both applies
                // the first and no-ops on the second.
                sendGroupUpdateToMembers(group, updateJson)

                // iOS leg: the shape an iPhone actually applies. iOS's minimal-JSON switch
                // has no `message_deleted` case, so the GROUP_UPDATE above hits `default:`
                // (`GroupMessaging.swift:1153`) and is dropped. A `.messageDeleted` SYSTEM
                // message rides the identical group envelope as any other group message.
                val systemMessageId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                deliverGroupEnvelope(
                    group = group,
                    messageId = systemMessageId,
                    groupMessageJson = buildGroupDeleteSystemMessageJson(
                        groupId = group.id,
                        systemMessageId = systemMessageId,
                        deletedMessageId = messageId,
                        actorPublicKey = myPublicKey,
                        timestampMillis = now
                    ),
                    appleTimestamp = appleEpoch(now),
                    iosMediaTypeString = null,
                    hasMedia = false,
                    // Silent: a retraction must never ring. iOS's own delete broadcast
                    // posts to the mesh only (`:2436-2447`).
                    push = false
                )
                Log.d(TAG, "🗑️ Broadcast message deletion for ${messageId.take(8)} in group ${groupId.take(8)}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast message deletion: ${e.message}")
            }
        }
    }

    private fun handleMessageDeleted(json: JSONObject, senderPublicKey: String) {
        val groupId = json.getString("groupId")
        val messageId = json.getString("messageId")

        applyMessageDeletionLocally(groupId, messageId, actor = senderPublicKey)
        Log.d(TAG, "Message $messageId deleted in group $groupId")
    }

    // =========================================================================
    // MESSAGE EDIT  (iOS `editGroupMessageForEveryone`, GroupMessaging.swift:1320)
    // =========================================================================

    /**
     * Edit one of my own group messages and tell everyone.
     *
     * Reachability, end to end: long-press an own text bubble in `GroupChatScreen`
     * → the Edit row of `GroupMessageActionsDialog` (offered only when
     * [canEditGroupMessage] says so) → `GroupEditMessageDialog` → `Save`
     * → `GroupChatViewModel.editMessage(messageId, newText)` → **here**.
     *
     * Order matches iOS `editGroupMessageForEveryone` (`GroupMessaging.swift:1320-1325`):
     * apply locally first so the bubble updates immediately even if the send fails, then
     * broadcast the `.messageEdited` system message.
     *
     * The window and ownership are re-checked here, not just in the menu: the dialog can
     * sit open past the 10-minute mark, and iOS enforces the same guard on its own sender
     * (`:1304-1310`).
     *
     * @return true when the edit was applied locally (and therefore broadcast).
     */
    fun broadcastMessageEdit(groupId: String, messageId: String, newContent: String): Boolean {
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return false
        val myPublicKey = cryptoManager.getPublicKeyString()
        if (myPublicKey.isEmpty()) return false

        val localKey = localGroupKey(groupId)
        val existing = _groupMessages.value[localKey] ?: return false
        val index = indexOfMessageId(existing, messageId)
        if (index < 0) return false
        val now = System.currentTimeMillis()
        if (!canEditGroupMessage(existing[index], myPublicKey, now)) {
            Log.w(TAG, "✏️ Refusing edit of ${messageId.take(8)} — not editable (own/text/<10 min)")
            return false
        }

        if (!applyMessageEditLocally(groupId, messageId, newContent, now)) return false
        channelWebHooks?.let { h -> runCatching { h.onOwnPostEdited(group.id, messageId, newContent) } } // __CHANNEL_WEB_2026_11_26__

        // ONE id for the envelope and for the system message it carries — iOS's delivery
        // receipt keys on `payload.id` while its dedupe keys on the GroupMessage's `id`,
        // and two different values there make a replay look like a new command.
        val systemMessageId = UUID.randomUUID().toString()
        scope.launch {
            try {
                deliverGroupEnvelope(
                    group = group,
                    messageId = systemMessageId,
                    groupMessageJson = buildGroupEditSystemMessageJson(
                        groupId = group.id,
                        systemMessageId = systemMessageId,
                        editedMessageId = messageId,
                        newContent = newContent,
                        actorPublicKey = myPublicKey,
                        timestampMillis = now
                    ),
                    appleTimestamp = appleEpoch(now),
                    iosMediaTypeString = null,
                    hasMedia = false,
                    // A silent command, not a message: iOS's own edit broadcast raises no
                    // banner either (`GroupMessaging.swift:1345-1360` posts to the mesh only).
                    push = false
                )
                Log.d(TAG, "✏️ Broadcast edit for ${messageId.take(8)} in group ${group.id.take(8)}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast message edit: ${e.message}")
            }
        }
        return true
    }

    /**
     * __GROUP_LIVE_LOCATION_2026_09_27__ Send a location share to a group.
     *
     * The initial share (and any one-shot location) is an ordinary group message. An
     * update or the stop of a running share is sent as an EDIT of that first message
     * — the only form a shipped iPhone applies in place (see the companion notes above
     * [liveLocationMergeTarget]). It bypasses [canEditGroupMessage] on purpose: a live
     * share runs for up to hours, far past the 10-minute text-edit window, and iOS does
     * not check the window on receive. No push: a position tick must never ring.
     *
     * When the first message is not found locally (deleted, or the app was restarted
     * with a different store), the update falls back to a new message, which is what
     * every build did before.
     */
    fun sendGroupLocation(groupId: String, content: String) {
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) }
        val myPublicKey = cryptoManager.getPublicKeyString()
        val localKey = localGroupKey(groupId)
        val existing = _groupMessages.value[localKey] ?: emptyList()
        val target = if (group != null && myPublicKey.isNotEmpty()) {
            liveLocationMergeTarget(existing, myPublicKey, content)
        } else -1
        if (target < 0 || group == null) {
            sendGroupMessage(groupId, content)
            return
        }
        val targetId = existing[target].id
        val now = System.currentTimeMillis()
        applyMessageEditLocally(groupId, targetId, content, now)
        val systemMessageId = UUID.randomUUID().toString()
        scope.launch {
            try {
                deliverGroupEnvelope(
                    group = group,
                    messageId = systemMessageId,
                    groupMessageJson = buildGroupEditSystemMessageJson(
                        groupId = group.id,
                        systemMessageId = systemMessageId,
                        editedMessageId = targetId,
                        newContent = content,
                        actorPublicKey = myPublicKey,
                        timestampMillis = now
                    ),
                    appleTimestamp = appleEpoch(now),
                    iosMediaTypeString = null,
                    hasMedia = false,
                    push = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send live-location update: ${e.message}")
            }
        }
    }

    /**
     * Write an edit into local state. Returns false when the target is absent or the edit
     * is a no-op, in which case nothing is persisted and no UI emission is forced.
     */
    private fun applyMessageEditLocally(
        rawGroupId: String,
        messageId: String,
        newContent: String,
        editedAt: Long,
        actor: String? = null
    ): Boolean {
        val groupId = localGroupKey(rawGroupId)
        val currentMessages = _groupMessages.value.toMutableMap()
        val groupMsgs = currentMessages[groupId] ?: return false
        val updated = applyEditToMessages(groupMsgs, messageId, newContent, editedAt, actor) ?: return false
        currentMessages[groupId] = updated
        _groupMessages.value = currentMessages
        saveMessages()
        updateGroupConversations()
        return true
    }

    /**
     * __BOTS_PHASE1_2026_11_24__ A bot acted on its own post (edit / delete / pin /
     * unpin, server S5). See [BotPostUpdate] for the authorship rule. Local only: every
     * member receives the same action from the server, so nothing is re-broadcast.
     */
    fun applyBotPostUpdate(update: BotPostUpdate): Boolean {
        val groupId = localGroupKey(update.groupId)
        val currentMessages = _groupMessages.value.toMutableMap()
        val groupMsgs = currentMessages[groupId] ?: return false
        when (update.action) {
            BotPostUpdate.Action.EDIT, BotPostUpdate.Action.DELETE -> {
                val updated = BotPostUpdate.applyTo(groupMsgs, update) ?: return false
                currentMessages[groupId] = updated
                _groupMessages.value = currentMessages
                saveMessages()
                updateGroupConversations()
                return true
            }
            BotPostUpdate.Action.PIN, BotPostUpdate.Action.UNPIN -> {
                val target = BotPostUpdate.target(groupMsgs, update) ?: return false
                val groups = _groups.value.toMutableList()
                val index = groups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
                if (index < 0) return false
                val g = groups[index]
                groups[index] = if (update.action == BotPostUpdate.Action.PIN) {
                    g.copy(pinnedMessageId = target.id, pinnedBy = target.senderPublicKey, updatedAt = System.currentTimeMillis())
                } else {
                    if (!g.pinnedMessageId.equals(target.id, ignoreCase = true)) return false
                    g.copy(pinnedMessageId = null, updatedAt = System.currentTimeMillis())
                }
                _groups.value = groups
                saveGroups()
                return true
            }
        }
    }

    /** __BOTS_PHASE1_2026_11_24__ S7 client obligation: bot items only for groups I am in. */
    fun isInGroup(groupId: String): Boolean = _groups.value.any { it.id.equals(groupId.trim(), ignoreCase = true) }

    private fun applyMessageDeletionLocally(rawGroupId: String, messageId: String, actor: String? = null) {
        val groupId = localGroupKey(rawGroupId)
        val currentMessages = _groupMessages.value.toMutableMap()
        val groupMsgs = currentMessages[groupId] ?: return
        // Case-insensitive match: an iOS delete-for-everyone targets the UPPERCASE uuidString.
        val adminOnComment = actor != null && isCommentId(messageId) && isAdminOf(groupId, actor)
        val updated = applyDeletionToMessages(groupMsgs, messageId, actor, adminOnComment)
        if (updated != null) {
            currentMessages[groupId] = updated
            _groupMessages.value = currentMessages
            saveMessages()
            updateGroupConversations()
        }
    }

    // =========================================================================
    // ADMIN PROMOTE / DEMOTE
    // =========================================================================

    /**
     * Promote a group member to admin.
     * Adds the member to adminPublicKeys, saves locally, and broadcasts
     * a GROUP_UPDATE with action "admin_promoted" to all members.
     */
    fun promoteToAdmin(groupId: String, memberPublicKey: String) {
        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index < 0) return

        val group = currentGroups[index]
        val myPublicKey = cryptoManager.getPublicKeyString()

        // Only admins can promote
        if (!group.adminPublicKeys.contains(myPublicKey)) {
            Log.w(TAG, "Cannot promote: not an admin")
            return
        }

        // Already an admin
        if (group.adminPublicKeys.contains(memberPublicKey)) {
            Log.w(TAG, "Member is already an admin")
            return
        }

        // Must be a group member
        if (!group.memberPublicKeys.contains(memberPublicKey)) {
            Log.w(TAG, "Cannot promote: not a group member")
            return
        }

        currentGroups[index] = group.copy(
            adminPublicKeys = group.adminPublicKeys + memberPublicKey,
            updatedAt = System.currentTimeMillis()
        )
        _groups.value = currentGroups
        // __GROUP_SETTINGS_2026_11_20__ recent events (spec §2.8)
        recordAdminEvents(group.id, GroupAdminLog.diff(group, currentGroups[index], null, null, cryptoManager.getPublicKeyString(), System.currentTimeMillis()))
        saveGroups()
        updateGroupConversations()

        scope.launch {
            try {
                val updateJson = JSONObject().apply {
                    put("type", "admin_promoted")
                    put("groupId", groupId)
                    put("memberPublicKey", memberPublicKey)
                    put("promotedBy", myPublicKey)
                    put("timestamp", System.currentTimeMillis())
                }

                // iOS drops `admin_promoted` (`default:` swift:992). The per-member `isAdmin`
                // flags only reach an iPhone inside the full `MessageGroup` roster (swift:818-826).
                broadcastFullGroupUpdate(currentGroups[index])
                sendGroupUpdateToMembers(currentGroups[index], updateJson)
                Log.d(TAG, "Promoted ${memberPublicKey.take(8)} to admin in group $groupId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast admin promotion: ${e.message}")
            }
        }
    }

    /**
     * Demote an admin back to regular member.
     * Cannot demote the group creator.
     * Removes from adminPublicKeys, saves locally, and broadcasts
     * a GROUP_UPDATE with action "admin_demoted" to all members.
     */
    fun demoteFromAdmin(groupId: String, memberPublicKey: String) {
        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index < 0) return

        val group = currentGroups[index]
        val myPublicKey = cryptoManager.getPublicKeyString()

        // Only admins can demote
        if (!group.adminPublicKeys.contains(myPublicKey)) {
            Log.w(TAG, "Cannot demote: not an admin")
            return
        }

        // Cannot demote the creator
        if (memberPublicKey == group.creatorPublicKey) {
            Log.w(TAG, "Cannot demote the group creator")
            return
        }

        // Not an admin
        if (!group.adminPublicKeys.contains(memberPublicKey)) {
            Log.w(TAG, "Member is not an admin")
            return
        }

        currentGroups[index] = group.copy(
            adminPublicKeys = group.adminPublicKeys.filter { it != memberPublicKey },
            updatedAt = System.currentTimeMillis()
        )
        _groups.value = currentGroups
        // __GROUP_SETTINGS_2026_11_20__ recent events (spec §2.8)
        recordAdminEvents(group.id, GroupAdminLog.diff(group, currentGroups[index], null, null, cryptoManager.getPublicKeyString(), System.currentTimeMillis()))
        saveGroups()
        updateGroupConversations()

        scope.launch {
            try {
                val updateJson = JSONObject().apply {
                    put("type", "admin_demoted")
                    put("groupId", groupId)
                    put("memberPublicKey", memberPublicKey)
                    put("demotedBy", myPublicKey)
                    put("timestamp", System.currentTimeMillis())
                }

                // Same as promote: only the full roster carries `isAdmin` to iOS.
                broadcastFullGroupUpdate(currentGroups[index])
                sendGroupUpdateToMembers(currentGroups[index], updateJson)
                Log.d(TAG, "Demoted ${memberPublicKey.take(8)} from admin in group $groupId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast admin demotion: ${e.message}")
            }
        }
    }

    /**
     * An admin-set write. iOS never takes one from a non-admin (`authorizeGroupUpdate`,
     * GroupMessaging.swift:889-893); this handler used to take one from anybody.
     */
    private fun handleAdminPromoted(json: JSONObject, senderPublicKey: String? = null) {
        val groupId = json.getString("groupId")
        val memberPublicKey = json.getString("memberPublicKey")

        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index >= 0) {
            val group = currentGroups[index]
            val decision = GroupUpdateAuthorizer.authorize(
                currentMemberKeys = group.memberPublicKeys,
                currentAdminKeys = group.adminPublicKeys,
                currentType = group.effectiveGroupType,
                sender = senderPublicKey
            )
            if (!decision.apply || decision.keepAdminSet) {
                Log.w(TAG, "🛑 admin_promoted ignored (${decision.reason}) for '${group.name}'")
                return
            }
            if (!group.adminPublicKeys.contains(memberPublicKey)) {
                currentGroups[index] = group.copy(
                    adminPublicKeys = group.adminPublicKeys + memberPublicKey,
                    updatedAt = System.currentTimeMillis()
                )
                _groups.value = currentGroups
                saveGroups()
                updateGroupConversations()
                Log.d(TAG, "Admin promoted: ${memberPublicKey.take(8)} in group $groupId")
            }
        }
    }

    private fun handleAdminDemoted(json: JSONObject, senderPublicKey: String? = null) {
        val groupId = json.getString("groupId")
        val memberPublicKey = json.getString("memberPublicKey")

        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (index >= 0) {
            val group = currentGroups[index]
            val decision = GroupUpdateAuthorizer.authorize(
                currentMemberKeys = group.memberPublicKeys,
                currentAdminKeys = group.adminPublicKeys,
                currentType = group.effectiveGroupType,
                sender = senderPublicKey
            )
            if (!decision.apply || decision.keepAdminSet) {
                Log.w(TAG, "🛑 admin_demoted ignored (${decision.reason}) for '${group.name}'")
                return
            }
            currentGroups[index] = group.copy(
                adminPublicKeys = group.adminPublicKeys.filter { it != memberPublicKey },
                updatedAt = System.currentTimeMillis()
            )
            _groups.value = currentGroups
            saveGroups()
            updateGroupConversations()
            Log.d(TAG, "Admin demoted: ${memberPublicKey.take(8)} in group $groupId")
        }
    }

    // =========================================================================
    // SHARED GROUP UPDATE ENVELOPE SENDER
    // =========================================================================

    /**
     * Send a GROUP_UPDATE JSON to all group members (excluding self)
     * using the same AES-256-GCM envelope encryption as group messages.
     */
    private suspend fun sendGroupUpdateToMembers(group: Group, updateJson: JSONObject) {
        val myPublicKey = cryptoManager.getPublicKeyString()
        val recipientKeys = group.memberPublicKeys.filter { it != myPublicKey }
        if (recipientKeys.isEmpty()) return
        // __GROUP_E2E_V2_2026_09_23__ v2 only. This used to build a groupId-keyed AES envelope
        // it never sent, then deliver the JSON over the legacy IPFS queue (GROUP_E2E_V2_SPEC §0).
        sendGroupUpdateRaw("$GROUP_UPDATE_PREFIX${updateJson}", recipientKeys)
    }

    /**
     * __GROUP_E2E_V2_2026_09_23__ "sent from my other device" for a GROUP (GROUP_E2E_V2_SPEC §7.2):
     * [contentPlaintext] is the exact content-channel plaintext (base64 GroupMessage). Inserted as an
     * OUTGOING message: no notification, no receipt, no command processing, dedup on id.
     */
    fun ingestOwnGroupSentCopy(groupId: String, contentPlaintext: String, fallbackTs: Long) {
        val json = try {
            JSONObject(String(Base64.decode(contentPlaintext.trim(), Base64.DEFAULT), Charsets.UTF_8))
        } catch (_: Exception) { return }
        val myPublicKey = cryptoManager.getPublicKeyString()
        if (!v2ContentAuthentic(json, myPublicKey, groupId)) return
        if (json.optString("senderPublicKey") == SYSTEM_SENDER) return
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return
        val id = json.optString("id").ifEmpty { return }
        val decoded = try {
            String(Base64.decode(json.optString("encryptedContent", ""), Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) { "" }
        var body = resolveIncomingBody(json.optString("content", ""), json.optString("plaintextContent", ""), decoded)
        if (body.startsWith(REACTION_PREFIX) || body.startsWith(TYPING_PREFIX) || body.isEmpty()) return
        var replyTo: String? = null
        parseReplyEnvelope(body)?.let { replyTo = it.replyToId; body = it.content }
        val existing = _groupMessages.value[group.id].orEmpty()
        if (indexOfMessageId(existing, id) >= 0) return
        val raw = json.optDouble("timestamp", Double.NaN)
        val ts = if (raw.isNaN()) fallbackTs else ((raw + APPLE_EPOCH_OFFSET) * 1000.0).toLong()
        addMessageToGroup(group.id, GroupMessage(
            id = id, groupId = group.id, senderPublicKey = myPublicKey, senderName = "Me",
            content = body, timestamp = ts, isFromMe = true, isRead = true, replyToId = replyTo
        ))
        saveMessages()
        updateGroupConversations()
    }

    // =========================================================================================
    // __GROUP_E2E_V2_2026_09_23__ group `stateVersion` (GROUP_E2E_V2_SPEC §5.3, audit G-10).
    // Kept beside the Room row (no schema change): canonical groupId → Int. It is also the
    // devsync `epoch` of the group record (spec §5.3 "Devsync mapping").
    // =========================================================================================

    // =========================================================================================
    // __MENTIONS_2026_09_23__ `@name` side table (util/GroupMentionIndex) + "mentioned you" set.
    // =========================================================================================

    // __LEAK_FIX_2026_09_25__ was the plaintext `group_mentions.xml` (member key → display name
    // per message); now the sealed store, migrated on first read.
    private val mentionIndex by lazy { com.oshi.messenger.util.GroupMentionIndex.deserialize(readDoc(GroupLocalStore.Doc.MENTIONS)) }
    private val _mentionsVersion = MutableStateFlow(0)
    /** Bumps whenever mentions are recorded, so a bubble can re-read [mentionsFor]. */
    val mentionsVersion: StateFlow<Int> = _mentionsVersion.asStateFlow()
    private val _mentionedGroups = MutableStateFlow<Set<String>>(emptySet())
    /** Groups (canonical, uppercase) holding an unread message that mentions us. */
    val mentionedGroups: StateFlow<Set<String>> = _mentionedGroups.asStateFlow()

    /** The admitted mentions of [messageId], or empty. */
    fun mentionsFor(messageId: String): List<com.oshi.messenger.util.MentionWire.Mention> = mentionIndex.get(messageId)

    private fun recordMentions(messageId: String, mentions: List<com.oshi.messenger.util.MentionWire.Mention>) {
        if (mentions.isEmpty()) return
        mentionIndex.put(messageId, mentions)
        runCatching { persist(GroupLocalStore.Doc.MENTIONS, mentionIndex.serialize()) }
        _mentionsVersion.value = _mentionsVersion.value + 1
    }

    private fun markMentioned(groupId: String) {
        _mentionedGroups.value = _mentionedGroups.value + groupId.uppercase(Locale.US)
    }

    private fun clearMentioned(groupId: String) {
        val k = groupId.uppercase(Locale.US)
        if (k in _mentionedGroups.value) _mentionedGroups.value = _mentionedGroups.value - k
    }

    /**
     * Members the `@` picker offers in [groupId]: everyone but us, named by our contact alias,
     * else the name they last sent under, else the first 5 key characters (iOS's fallback).
     */
    fun mentionCandidates(groupId: String): List<com.oshi.messenger.util.MentionWire.Mention> {
        val group = _groups.value.find { it.id.equals(groupId, ignoreCase = true) } ?: return emptyList()
        val me = cryptoManager.getPublicKeyString()
        val lastNames = _groupMessages.value[group.id].orEmpty()
            .filter { !it.isFromMe && it.senderName.isNotBlank() }
            .associate { it.senderPublicKey to it.senderName }
        // __MENTIONS_FELL_BACK_TO_THE_KEY_2026_11_07__ (Anatole N2: "only a few
        // people have their name, the rest show their key")
        //
        // This had exactly two sources — your own contact alias, and the
        // senderName off a message that person had sent IN THIS GROUP and that
        // we still store. A member who is not in your contacts and has not
        // spoken (or whose messages have aged out of the window) had no name at
        // all, so every one of them rendered as `key.take(5)` — five characters
        // of base64.
        //
        // The names were already on the device. `GroupMemberAliasStore` has
        // recorded the `members[].alias` of every incoming group definition
        // since 23/09 (`__GROUP_MEMBER_NAMES_2026_09_23__`, written ~3000 lines
        // up in this same file), and `GroupMemberNames.resolve` is the complete
        // four-source resolver the group INFO screen uses. The mention picker
        // reached past both for a poorer lookup of its own.
        //
        // Added as a FALLBACK rather than a reordering: every name that
        // resolved before still resolves the same way, so this can only turn a
        // key into a name, never one name into a different one.
        val definitionAliases = runCatching {
            com.oshi.messenger.data.model.GroupMemberAliasStore.init(context)
            com.oshi.messenger.data.model.GroupMemberAliasStore.forGroup(group.id)
        }.getOrNull().orEmpty()

        return group.memberPublicKeys.filter { !sameMentionKey(it, me) }.map { key ->
            val alias = runCatching { contactAliasResolver?.invoke(key) }.getOrNull()
            val fromDefinition = definitionAliases[
                com.oshi.messenger.data.model.PeerNickname.normalizeKey(key)
            ]
            val name = listOfNotNull(alias, lastNames[key], fromDefinition, key.take(5))
                .map { it.replace("@", "").replace('\n', ' ').trim() }
                .firstOrNull { com.oshi.messenger.util.MentionWire.nameOk(it) } ?: key.take(5)
            com.oshi.messenger.util.MentionWire.Mention(key, name)
        }
    }

    private val stateVersionPrefs by lazy {
        context.getSharedPreferences("group_state_versions", Context.MODE_PRIVATE)
    }

    /** The version of our copy of [groupId], or null when we have never seen or sent one. */
    fun stateVersionOf(groupId: String): Int? {
        val k = canonicalGroupId(groupId)
        return if (stateVersionPrefs.contains(k)) stateVersionPrefs.getInt(k, 0) else null
    }

    /** local = local + 1, stored BEFORE the definition leaves (iOS `broadcastFullGroupUpdate`). */
    @Synchronized
    private fun bumpStateVersion(groupId: String): Int {
        val next = (stateVersionOf(groupId) ?: 0) + 1
        stateVersionPrefs.edit().putInt(canonicalGroupId(groupId), next).apply()
        return next
    }

    /** local = max(local, [version]). */
    @Synchronized
    fun adoptStateVersion(groupId: String, version: Int) {
        val current = stateVersionOf(groupId)
        if (current == null || version > current) {
            stateVersionPrefs.edit().putInt(canonicalGroupId(groupId), version).apply()
        }
    }

    // =========================================================================================
    // __DEVSYNC_DIRECT_2026_09_22__ Own-device sync (docs/OSHI_DEVICE_SYNC_DIRECT.md §7-§8).
    //
    // Group state and group history that arrive from ANOTHER DEVICE OF THIS ACCOUNT, written
    // through the import path: no broadcast to the members, no notification, no receipt, no bot
    // trigger, no key rotation. The in-memory state is the source of truth here (Room is its
    // mirror, `startRoomMirror`), so these write memory and let the mirror follow.
    // =========================================================================================

    /**
     * __GROUP_MUTE_SYNC_2026_09_24__ When this account last changed [groupId]'s mute here (or the
     * merged time of a synced one), ms. Null = never toggled on this install (legacy: a mute is
     * exported untimestamped, no mute not at all). Prefs, not Room: no schema migration.
     */
    private val muteAtPrefs by lazy { context.getSharedPreferences("oshi_group_muted_at", Context.MODE_PRIVATE) }

    fun mutedAtOf(groupId: String): Long? =
        canonicalGroupId(groupId).let { k -> if (muteAtPrefs.contains(k)) muteAtPrefs.getLong(k, 0L) else null }

    /** A mute merged from another of the account's devices: applied with ITS time (never "now"). */
    fun devSyncApplyMute(groupId: String, muted: Boolean, atMs: Long?) {
        atMs?.let { muteAtPrefs.edit().putLong(canonicalGroupId(groupId), it).apply() }
        val current = _groups.value.toMutableList()
        val i = current.indexOfFirst { it.id.equals(groupId, ignoreCase = true) }
        if (i < 0 || current[i].isMuted == muted) return
        current[i] = current[i].copy(isMuted = muted)
        _groups.value = current
        saveGroups()
        updateGroupConversations()
    }

    /** Insert or replace a group definition that came from one of this account's own devices. */
    fun devSyncUpsertGroup(group: Group) {
        val current = _groups.value.toMutableList()
        val i = current.indexOfFirst { it.id.equals(group.id, ignoreCase = true) }
        if (i >= 0) {
            val old = current[i]
            // Local-only fields stay local (mute, wallpaper); the picture arrives by reference.
            current[i] = group.copy(
                id = old.id,
                isMuted = old.isMuted,
                groupImage = group.groupImage ?: old.groupImage,
                wallpaperId = old.wallpaperId,
                wallpaperImage = old.wallpaperImage,
                groupWallpaperData = old.groupWallpaperData,
                groupWallpaperUpdatedAt = old.groupWallpaperUpdatedAt,
                groupWallpaperUpdatedBy = old.groupWallpaperUpdatedBy,
            )
        } else {
            current.add(group)
        }
        _groups.value = current
        saveGroups()
        updateGroupConversations()
    }

    /** The recent window of [groupId]'s messages held in memory (Room holds the full history). */
    fun devSyncMessagesInMemory(groupId: String): List<GroupMessage> =
        _groupMessages.value.entries.firstOrNull { it.key.equals(groupId, ignoreCase = true) }?.value ?: emptyList()

    /** Insert new and replace merged messages of an EXISTING group. Returns false if the group is unknown. */
    fun devSyncUpsertMessages(groupId: String, messages: List<GroupMessage>): Boolean {
        val key = _groups.value.firstOrNull { it.id.equals(groupId, ignoreCase = true) }?.id ?: return false
        val current = _groupMessages.value.toMutableMap()
        val list = (current.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value ?: emptyList()).toMutableList()
        for (m in messages) {
            val i = indexOfMessageId(list, m.id)
            // __BLOCKED_MEMBER_DROP_2026_09_24__ a blocked member's words do not come back through
            // another of the account's devices (an older build there may still have stored them).
            if (i < 0 && !m.isFromMe && BlockedMemberGate.drops(m.senderPublicKey, "", CallAdmissionPolicy::isBlockedCached)) continue
            if (i >= 0) list[i] = m.copy(id = list[i].id, groupId = key) else list.add(m.copy(groupId = key))
        }
        list.sortBy { it.timestamp }
        current[key] = list
        _groupMessages.value = current
        updateGroupConversations()
        saveMessages()
        return true
    }

    // __DEVSYNC_LEAVE_2026_09_23__ Group leave across the account's own devices (design §8.4:
    // `left` is sticky, `leftAt` = the earliest leave). A leave made on THIS device is recorded by
    // [leaveGroup]; a leave that arrives from another device is applied by [devSyncLeaveGroup],
    // which does the local half of [leaveGroup] only: the departure was already announced to the
    // members (and to any server) by the device where the user tapped Leave.

    /** A leave this device knows about (its own or one synced from another device). */
    data class DevSyncLeave(val groupId: String, val leftAtMs: Long, val name: String, val legacyKey: ByteArray?)

    // __LEAK_FIX_2026_09_25__ was the plaintext `oshi_devsync_left_groups.xml` (name + legacy key
    // of every group left). Same shape — gid → entry JSON string — kept as ONE sealed document.
    @Volatile private var devSyncLeftCache: MutableMap<String, String>? = null

    @Synchronized
    private fun devSyncLeftMap(): MutableMap<String, String> {
        devSyncLeftCache?.let { return it }
        val m = LinkedHashMap<String, String>()
        runCatching {
            readDoc(GroupLocalStore.Doc.DEVSYNC_LEFT)?.let { raw ->
                val o = JSONObject(raw)
                for (k in o.keys()) o.optString(k, "").takeIf { it.isNotEmpty() }?.let { m[k] = it }
            }
        }
        devSyncLeftCache = m
        return m
    }

    @Synchronized
    private fun storeDevSyncLeft(m: Map<String, String>) {
        devSyncLeftCache = LinkedHashMap(m)
        persist(GroupLocalStore.Doc.DEVSYNC_LEFT, GroupLocalStore.entriesToJson(m))
    }

    fun devSyncLeaves(): List<DevSyncLeave> = synchronized(this) { devSyncLeftMap().toMap() }.mapNotNull { (gid, raw) ->
        runCatching {
            val o = JSONObject(raw)
            DevSyncLeave(gid, o.getLong("leftAt"), o.optString("name", ""),
                o.optString("key", "").takeIf { it.isNotEmpty() }?.let { java.util.Base64.getDecoder().decode(it) })
        }.getOrNull()
    }

    /** Keeps the LATEST leftAt (§8.4 rejoin rule, __DEVSYNC_REJOIN_2026_09_23__); name/key only fill gaps. */
    @Synchronized
    private fun recordDevSyncLeave(groupId: String, leftAtMs: Long, name: String, key: ByteArray?) {
        val gid = canonicalGroupId(groupId)
        val prev = devSyncLeaves().firstOrNull { it.groupId == gid }
        val o = JSONObject()
            .put("leftAt", if (prev == null) leftAtMs else maxOf(prev.leftAtMs, leftAtMs))
            .put("name", prev?.name?.takeIf { it.isNotEmpty() } ?: name)
        (prev?.legacyKey ?: key)?.let { o.put("key", java.util.Base64.getEncoder().encodeToString(it)) }
        storeDevSyncLeft(devSyncLeftMap().also { it[gid] = o.toString() })
    }

    private fun clearDevSyncLeave(groupId: String) {
        val gid = canonicalGroupId(groupId)
        synchronized(this) {
            val m = devSyncLeftMap()
            if (m.remove(gid) != null) storeDevSyncLeft(m)
        }
    }

    // __DEVSYNC_REJOIN_2026_09_23__ design §8.4 rejoin rule: `joinedAt` = the last deliberate
    // (re)join of this account (here: [clearGroupTombstone]; or adopted from another device). A leave
    // older than it is history, so a rejoin is not undone by a device still holding `left`.
    private val devSyncJoinedPrefs by lazy { context.getSharedPreferences("oshi_devsync_joined_groups", Context.MODE_PRIVATE) }

    /** The last (re)join of [groupId] known to this account, or null. */
    fun devSyncJoinedAt(groupId: String): Long? =
        devSyncJoinedPrefs.getLong(canonicalGroupId(groupId), -1L).takeIf { it >= 0 }

    /** Keeps the LATEST joinedAt. */
    @Synchronized
    private fun recordDevSyncJoin(groupId: String, joinedAtMs: Long) {
        val gid = canonicalGroupId(groupId)
        val prev = devSyncJoinedPrefs.getLong(gid, -1L)
        if (joinedAtMs > prev) devSyncJoinedPrefs.edit().putLong(gid, joinedAtMs).apply()
    }

    /**
     * A synced group record carries [joinedAtMs] (merged: the latest join on any device). Recorded
     * (max), and when [left] is false and this device's own synced leave is OLDER than that join,
     * the user rejoined on another device: the leave and the left-group tombstone are lifted,
     * silently (no join notice: the rejoining device sent it). A tombstone with no synced leave
     * (left before sync tracked leaves, or deleted as admin) is never lifted from here.
     * Returns true when a leave was lifted.
     */
    fun devSyncAdoptJoin(groupId: String, joinedAtMs: Long, left: Boolean): Boolean {
        recordDevSyncJoin(groupId, joinedAtMs)
        if (left) return false
        val leave = devSyncLeaves().firstOrNull { it.groupId == canonicalGroupId(groupId) } ?: return false
        if (leave.leftAtMs >= joinedAtMs) return false
        liftGroupTombstone(groupId)
        return true
    }

    /** True when a synced group definition must NOT (re)create this group here: left or deleted on this device. */
    fun devSyncRefusesGroup(groupId: String): Boolean =
        isTombstonedGroup(groupId) && _groups.value.none { it.id.equals(groupId, ignoreCase = true) }

    /**
     * The group was left on another device of this account: leave it here too, SILENTLY — no
     * departure notice, no member_removed, no key rotation, no system row. Idempotent.
     */
    fun devSyncLeaveGroup(groupId: String, leftAtMs: Long, name: String?, legacyKey: ByteArray?) {
        val group = _groups.value.firstOrNull { it.id.equals(groupId, ignoreCase = true) }
        recordDevSyncLeave(groupId, leftAtMs, group?.name ?: name.orEmpty(), group?.encryptionKey ?: legacyKey)
        tombstoneGroup(groupId)
        if (group == null) return
        _groups.value = _groups.value.filterNot { it.id.equals(groupId, ignoreCase = true) }
        val remainingMessages = _groupMessages.value.toMutableMap()
        if (remainingMessages.keys.removeAll { it.equals(groupId, ignoreCase = true) }) {
            _groupMessages.value = remainingMessages
            saveMessagesNow()
        }
        saveGroups()
        updateGroupConversations()
        Log.d(TAG, "👋 Left '${group.name}' (left on another of this account's devices)")
    }

    /** Mark the incoming messages of [groupId] sent at or before [readUpToMs] as read. No receipt is sent. */
    fun devSyncMarkReadUpTo(groupId: String, readUpToMs: Long): Int {
        val key = _groups.value.firstOrNull { it.id.equals(groupId, ignoreCase = true) }?.id ?: return 0
        val current = _groupMessages.value.toMutableMap()
        val entry = current.entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: return 0
        var n = 0
        val list = entry.value.map { m ->
            if (!m.isRead && !m.isFromMe && m.timestamp <= readUpToMs) { n++; m.copy(isRead = true) } else m
        }
        if (n == 0) return 0
        current[entry.key] = list
        _groupMessages.value = current
        saveMessages()
        updateGroupConversations()
        return n
    }

    fun cleanup() {
        scope.cancel()
    }
}

/**
 * __BLOCKED_MEMBER_DROP_2026_09_24__ Is this group envelope content from a member I blocked
 * (dropped at receive)? Bots and the system sender are never "members I blocked"; an envelope
 * that carries a `systemMessageType` (edit / delete command) is not content.
 */
internal object BlockedMemberGate {
    fun drops(sender: String, systemMessageType: String, isBlocked: (String) -> Boolean): Boolean {
        if (sender.isBlank() || sender.startsWith("bot:")) return false
        if (sender.equals("system", ignoreCase = true)) return false
        if (systemMessageType.isNotEmpty()) return false
        return isBlocked(sender)
    }
}
