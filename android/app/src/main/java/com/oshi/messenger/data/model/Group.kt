package com.oshi.messenger.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import java.util.UUID

/**
 * Group type — mirrors iOS GroupMessaging.swift `GroupType`.
 * - ADMIN_ONLY: only admins can invite/remove/rename and change wallpaper.
 * - COLLABORATIVE: any member can.
 * - PUBLIC: any member can; discoverable.
 */
enum class GroupType(val value: String) {
    ADMIN_ONLY("admin_only"),
    COLLABORATIVE("collaborative"),
    PUBLIC("public");

    // `displayName` and `description` used to live here as English literals. In an app
    // that ships 34 languages a name on a data-layer enum can only ever be English, and
    // the last reader (`GroupsViewModel.filterGroups`, which matched the search box
    // against it) meant typing "Public" found nothing in any other locale. The labels
    // are `R.string.group_type_*` and are resolved where a Context exists — the screen,
    // or `GroupTypeLabels` for the ViewModel.

    companion object {
        fun fromValue(value: String): GroupType = entries.find { it.value == value } ?: COLLABORATIVE
    }
}

/**
 * Group Entity - Compatible with iOS GroupManager
 */
@Entity(tableName = "groups")
data class Group(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val creatorPublicKey: String,
    val memberPublicKeys: List<String>,
    val adminPublicKeys: List<String>,
    /**
     * __EVICTED_LIST_NEVER_EXISTED_2026_11_04__ People removed from this group
     * by someone with the permission to do it.
     *
     * `GroupUpdateAuthorizer.authorizeMemberSyncRequest` has carried rule 3,
     * "a member we evicted may not re-add themselves", since it was written —
     * and the only caller passed `emptyList()`, so the rule could never fire.
     * Nothing anywhere stored who had been evicted. An evicted member whose
     * app kept sending `member_sync_request` was therefore re-added, removed
     * again by whoever evicted them, re-added on the next sync, and so on:
     * "le nombre de personnes dans le groupe bouge sans cesse sans que
     * personne ne touche à rien", and its twin report, "des personnes
     * disparaissent sans raison".
     *
     * Cleared for an identity that is deliberately re-added, so an eviction is
     * never a permanent ban an admin cannot undo.
     */
    val evictedMemberKeys: List<String> = emptyList(),
    val groupImage: ByteArray? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPublic: Boolean = false,
    val isMuted: Boolean = false,
    val encryptionKey: ByteArray? = null,  // Shared group key
    val pinnedMessageId: String? = null,   // Currently pinned message
    /**
     * Public key of whoever pinned [pinnedMessageId]. iOS `MessageGroup.pinnedBy`
     * (`GroupMessaging.swift:104`), encoded whenever `pinnedMessageId` is present.
     * iOS falls back to `adminPublicKey` when it is absent
     * (`GroupMessaging.swift:842,844`), so a null here is never fatal — it just means
     * the system message reads "the admin pinned this".
     */
    val pinnedBy: String? = null,
    /**
     * Emoji avatar, the fallback iOS renders when a group has no picture.
     * iOS `MessageGroup.avatar` (`GroupMessaging.swift:112`), CodingKey at `:259`,
     * decoded at `:219`. The field was simply MISSING from this model, so
     * `GroupManager.buildIosMessageGroupJson` — which already accepts and encodes it
     * (`GroupManager.kt:171,215`) — had nothing to pass and every outbound broadcast
     * omitted the key, clearing the avatar on every iPhone in the group (an incoming
     * update that omits `avatar` CLEARS it on iOS; only picture/wallpaper/isMuted are
     * preserved across a stripped update — `GroupMessaging.swift:800-820`).
     */
    val avatar: String? = null,
    // null on pre-migration rows; `effectiveGroupType` derives a value from isPublic in that case.
    val groupType: GroupType? = null,
    // iOS canChangeWallpaper parity — base64-encoded small image or null
    val wallpaperId: String? = null,
    val wallpaperImage: String? = null,
    /**
     * Per-group wallpaper synced across all members (overrides per-user wallpaper
     * when set). Mirrors iOS `MessageGroup.groupWallpaperData`. Compressed to
     * ≤ 500 KB before broadcast.
     */
    val groupWallpaperData: ByteArray? = null,
    val groupWallpaperUpdatedAt: Long? = null,
    val groupWallpaperUpdatedBy: String? = null
) {
    /** Resolved group type: honors explicit value, else derives from `isPublic` for legacy rows. */
    val effectiveGroupType: GroupType
        get() = groupType ?: if (isPublic) GroupType.PUBLIC else GroupType.COLLABORATIVE

    /** iOS parity: collaborative/public = any member; adminOnly = admins only. */
    fun canAddMembers(currentUserPublicKey: String): Boolean {
        val t = effectiveGroupType
        if (t == GroupType.COLLABORATIVE || t == GroupType.PUBLIC) return true
        return adminPublicKeys.contains(currentUserPublicKey)
    }
    fun canRemoveMembers(currentUserPublicKey: String): Boolean = canAddMembers(currentUserPublicKey)
    fun canChangeName(currentUserPublicKey: String): Boolean = canAddMembers(currentUserPublicKey)
    fun canChangeWallpaper(currentUserPublicKey: String): Boolean = canAddMembers(currentUserPublicKey)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Group
        return id == other.id
            && name == other.name
            && memberPublicKeys == other.memberPublicKeys
            && adminPublicKeys == other.adminPublicKeys
            && groupImage.contentEqualsOrEmpty(other.groupImage ?: ByteArray(0))
            && updatedAt == other.updatedAt
            // __GROUP_MUTE_SYNC_2026_09_24__ a mute toggle is a change: without this the StateFlow
            // conflated it away (menu label, Room mirror, devsync watcher never saw it).
            && isMuted == other.isMuted
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + memberPublicKeys.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }

    /**
     * 🔴 THIS USED TO BE CALLED `contentEquals`, AND IT CALLED ITSELF FOREVER.
     *
     * A private extension on `ByteArray?` named `contentEquals` SHADOWS the stdlib
     * `ByteArray.contentEquals` for every call inside this class — a local
     * declaration wins over an imported one, and a smart-cast `ByteArray` still
     * matches a `ByteArray?` receiver. So `this.contentEquals(other)` in the else
     * branch resolved to this very function: infinite recursion, StackOverflowError,
     * process gone.
     *
     * It could not fire while `groupImage` was null — the `if` returns first — so it
     * sat harmless from v1.0.3 (2026-04-07) until groups actually started carrying
     * avatars. Then ANY comparison of a group with a picture killed the app, and
     * `Group.equals` is called from `StateFlowImpl.updateState` on every groups
     * emission: receiving a group definition over V2 (`handleGroupCreated`) crashed
     * the process, on a background dispatcher, with no handler.
     *
     * The rename is the entire fix: with a name the stdlib does not own, the else
     * branch resolves to `kotlin.collections.contentEquals` as intended.
     */
    private fun ByteArray?.contentEqualsOrEmpty(other: ByteArray): Boolean =
        if (this == null) other.isEmpty() else this.contentEquals(other)
}

/**
 * Group Message - Compatible with iOS GroupMessaging
 */
@Entity(tableName = "group_messages")
data class GroupMessage(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val groupId: String,
    val senderPublicKey: String,
    val senderName: String,
    val content: String,
    val encryptedContent: ByteArray? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isFromMe: Boolean,
    val isRead: Boolean = false,
    val mediaType: MediaType? = null,
    val mediaData: ByteArray? = null,
    /**
     * Original file name of a media attachment. iOS `GroupMessage.mediaFileName`, carried
     * as `fileName` INSIDE the media JSON (`GroupMessaging.swift:2542`, rebuilt on retry at
     * `:2706`) and rendered by `GroupViews.swift:3087,:3304`.
     *
     * Android had no home for it, so a document's name travelled inside `content` as the
     * literal `"[DOCUMENT] name.pdf"` — and since the groups pass copies `content` into
     * `plaintextContent`, which is the ONLY key iOS reads a caption from
     * (`GroupViews.swift:2617`), every iPhone drew that literal as the document's caption.
     */
    val mediaFileName: String? = null,
    /**
     * Duration of a voice note, in **milliseconds**. Null for every other media type and
     * for any row written before this column existed.
     *
     * Same shape of bug as [mediaFileName], one field over: the duration used to travel
     * inside `content` as the literal `"[AUDIO] 0:05"`
     * (`GroupChatViewModel.sendVoiceMessage`), and since the groups pass copies `content`
     * into `plaintextContent` — the ONLY key iOS draws a caption from
     * (`GroupViews.swift:2617`) — every iPhone printed that string as the voice note's
     * caption.
     *
     * ⚠️ This is a LOCAL column, not a wire field. iOS's group media JSON has exactly
     * `type` / `mediaType` / `content` / `size` / `fileName`
     * (`GroupMessaging.swift:2534-2546`) and carries no duration in either direction; an
     * iPhone reads it back off the decoded audio. Android does the same for anything it
     * did not record itself — `VoiceNoteAudio.probeDurationSeconds`. Adding a key here
     * would be a one-sided invention, so there is none.
     */
    val mediaDurationMs: Long? = null,
    val replyToId: String? = null,
    val reactions: Map<String, String> = emptyMap(),  // publicKey -> emoji
    val isDeleted: Boolean = false,
    /**
     * The replacement body of an edited message. iOS `GroupMessage.editedContent`
     * (`GroupMessaging.swift:362`), set locally by `editGroupMessage` (`:1314`) and
     * remotely by the `.messageEdited` system-message branch (`:3300`).
     *
     * [content] is deliberately left at the ORIGINAL text, exactly as iOS keeps
     * `encryptedContent` untouched and only overwrites `plaintextContent`. Display
     * prefers this field when it is non-empty — `GroupViews.resolveText` puts it first
     * (`GroupViews.swift:2614-2615`) — so [displayContent] is the one read path.
     */
    val editedContent: String? = null,
    /**
     * When the edit landed, in Unix millis. iOS `GroupMessage.editedAt`
     * (`GroupMessaging.swift:361`); non-null is the whole test for the "Edited" marker
     * on the bubble (`GroupViews.swift:2761`).
     *
     * ⚠️ LOCAL column. On the wire this value is the *system message's own*
     * `timestamp`, which is an Apple-epoch Double like every other group-message
     * timestamp — iOS assigns `msgs[idx].editedAt = message.timestamp`
     * (`GroupMessaging.swift:3299`). There is no `editedAt` key in the edit envelope.
     */
    val editedAt: Long? = null,
    /**
     * A bot message's inline keyboard, stored as the RAW JSON text of the wire's
     * `replyMarkup` object — `{"inline_keyboard":[[{…}]]}`.
     *
     * Kept as text rather than decoded into columns for two reasons. It is a
     * pass-through: nothing on this device interprets a button except the renderer,
     * which parses it on demand through [BotReplyMarkupParser]. And it is a FOREIGN
     * shape — the bot platform owns it, and a button kind added server-side must not
     * need a Room migration on every phone before a bot can use it. The parser is what
     * decides which of those buttons this build knows how to draw.
     *
     * Null for every non-bot message and for every bot message that shipped no
     * keyboard, which is the overwhelming majority of rows.
     */
    val replyMarkup: String? = null,
    /**
     * __BOT_PUBLIC_ID_2026_09_16__ — the sending bot's PUBLIC id,
     * `sha256("oshi-bot-id:" + token)[:16]`, computed and published by the server on
     * every `bot_message` envelope.
     *
     * A one-way function of the bot's secret, so it NAMES a bot without being able to
     * act as one. That is what makes it safe to deliver to every member of a group, and
     * what lets `/api/bot/dispatch` accept it in place of the token as long as the
     * server also checks the caller is a member of a group the bot serves.
     *
     * It is the field that makes an inline button work for a bot the user does NOT own:
     * before it existed, only the bot's owner held anything the dispatch route would
     * accept. Nullable because a row stored before this shipped has none, and because
     * the legacy `bot:` envelope may still arrive without it.
     *
     * ⚠️ Not a credential and must never be treated as one — no Keychain, no redaction,
     * and nothing may derive a token from it.
     */
    val botId: String? = null,
    /**
     * __VIDEO_NOTE_2026_09_24__ A round "video note" (docs/VIDEO_NOTE_SPEC.md §2.B/§2.D).
     * Only true on a VIDEO row; its duration rides in [mediaDurationMs]. Room v20 column.
     */
    val isVideoNote: Boolean = false
) {
    /**
     * The text to draw for this message. iOS: `GroupViews.resolveText`
     * (`GroupViews.swift:2613-2615`) — "Edited content takes priority".
     */
    val displayContent: String
        get() = editedContent?.takeIf { it.isNotEmpty() } ?: content

    /** iOS draws the "Edited" marker off `editedAt != nil` (`GroupViews.swift:2761`). */
    val isEdited: Boolean
        get() = editedAt != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GroupMessage
        return id == other.id
            && reactions == other.reactions
            && content == other.content
            && isRead == other.isRead
            && isDeleted == other.isDeleted
            // An edit changes NEITHER `content` NOR any other field compared above, so
            // without these two an edited list is `==` to the un-edited one and the
            // `StateFlow` in `GroupChatViewModel` drops the emission — the bubble would
            // only update after some unrelated change forced a re-collect.
            && editedContent == other.editedContent
            && editedAt == other.editedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + reactions.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + (editedContent?.hashCode() ?: 0)
        result = 31 * result + (editedAt?.hashCode() ?: 0)
        return result
    }
}

/**
 * Group conversation for UI display
 */
data class GroupConversation(
    val group: Group,
    val lastMessage: GroupMessage?,
    val unreadCount: Int,
    val memberNames: Map<String, String>,  // publicKey -> name
    val typingMembers: List<String> = emptyList()
)

/**
 * Group member info
 */
data class GroupMember(
    val publicKey: String,
    val name: String,
    val profilePicture: ByteArray? = null,
    val isAdmin: Boolean = false,
    val joinedAt: Long = System.currentTimeMillis()
)
