package com.oshi.messenger.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Message Entity - Compatible with iOS SecureMessage
 */
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val senderPublicKey: String,
    val recipientPublicKey: String,
    val content: String,          // Decrypted content
    val encryptedContent: ByteArray? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isFromMe: Boolean,
    val isRead: Boolean = false,
    val isDelivered: Boolean = false,
    val deliveryMethod: DeliveryMethod = DeliveryMethod.VPS,
    val mediaType: MediaType? = null,
    val mediaData: ByteArray? = null,
    val replyToId: String? = null,
    val isDeleted: Boolean = false,
    // Edit & delete-for-everyone support (iOS compatible)
    val editedAt: Long? = null,
    val isDeletedForEveryone: Boolean = false,
    val editedContent: String? = null,
    // View-once media support (iOS compatible)
    val isViewOnce: Boolean = false,
    val viewOnceOpened: Boolean = false,
    val viewOnceOpenedAt: Long? = null,
    // Failed send status (for retry UI)
    val isFailed: Boolean = false,
    /**
     * __VIDEO_NOTE_2026_09_24__ A round "video note" (docs/VIDEO_NOTE_SPEC.md). Only ever
     * true on a VIDEO row; the bytes are an ordinary MP4. Room v20 column.
     */
    val isVideoNote: Boolean = false,
    /** Recorded duration of a video note in ms (≤ 15000), null when unknown. Room v20. */
    val mediaDurationMs: Long? = null
) {
    /**
     * 🔴 THIS COMPARED THE ID AND NOTHING ELSE, AND THAT DROPPED UI UPDATES.
     *
     * `ChatViewModel.messages` is a `StateFlow`, and `StateFlowImpl` conflates on
     * `equals`; `List.equals` is element-wise. So a list in which only a message's
     * `isDelivered`, `isRead`, `editedContent`, `isDeletedForEveryone`,
     * `viewOnceOpened` or `isFailed` had changed compared EQUAL to the previous
     * list — the emission was dropped and the thread never redrew, until some
     * unrelated change (usually the next incoming message) forced a re-collect.
     *
     * That is exactly what a user sees as "the delivery tick appears late", "the
     * read receipt never shows", "my edit didn't apply until they replied", and a
     * failed send that keeps looking sent.
     *
     * The same defect was already found and fixed for `GroupMessage`
     * (`Group.kt:228-239`); the 1:1 half was never done.
     *
     * `encryptedContent` and `mediaData` are deliberately NOT compared: they are
     * large `ByteArray`s and a content-equality check on them would run on every
     * emission for every visible row. Every field that the UI actually RENDERS is
     * here; the two that are not are ones no read path can change without also
     * changing one that is.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Message
        return id == other.id
            && content == other.content
            && isRead == other.isRead
            && isDelivered == other.isDelivered
            && isDeleted == other.isDeleted
            && isDeletedForEveryone == other.isDeletedForEveryone
            && isFailed == other.isFailed
            && editedContent == other.editedContent
            && editedAt == other.editedAt
            && viewOnceOpened == other.viewOnceOpened
            && viewOnceOpenedAt == other.viewOnceOpenedAt
            && deliveryMethod == other.deliveryMethod
            && mediaType == other.mediaType
            && isVideoNote == other.isVideoNote
            && mediaDurationMs == other.mediaDurationMs
    }

    /**
     * Stays keyed on the id alone, deliberately.
     *
     * `hashCode` must be stable for a message's lifetime — Compose `LazyColumn`
     * keys and any Set/Map membership depend on it — while `equals` has to notice
     * the mutable fields above. Two objects that are `equals` still share a hash,
     * which is the contract; the reverse is not required.
     */
    override fun hashCode(): Int = id.hashCode()
}

/**
 * How a message actually travelled. iOS `MessageManager.swift:7966-7981`.
 *
 * ⚠️ NO ROOM MIGRATION IS NEEDED to add a case here, and that is not luck:
 * `Converters.fromDeliveryMethod` stores `value.name` as TEXT
 * (`data/local/AppDatabase.kt:160-164`), so the COLUMN does not change shape and
 * Room's schema hash is unaffected. Existing rows keep their existing strings.
 * The one direction that would break is a DOWNGRADE — `valueOf("LORA")` on an
 * older build throws — and Android does not downgrade installs.
 */
enum class DeliveryMethod {
    VPS,        // Via server
    MESH,       // Direct P2P mesh
    TOR,        // Via Tor
    IPFS,       // Via IPFS
    LORA,       // Meshtastic LoRa radio (MessageManager.swift:7966-7981, `.lora`)

    /**
     * The row was GENERATED ON THIS DEVICE and never travelled — the welcome
     * conversation (`service/WelcomeMessage.kt`), and any future locally seeded
     * content.
     *
     * iOS expresses this as `deliveryMethod: nil`
     * (`WelcomeMessageManager.swift:117`) and draws no badge. Android's column is
     * non-null with a default of [VPS], so without this case a message that never
     * touched a network renders a blue "Via Internet" pill — a transport claim
     * about a message that had no transport. [LOCAL] renders nothing, which is
     * the same thing iOS's nil does.
     */
    LOCAL,

    /**
     * __LORA_PLAINTEXT_IS_NOT_THE_SAME_AS_LORA_2026_11_04__ A raw Meshtastic
     * text packet: carried by the radio, NOT by OSHI, and NOT authenticated.
     *
     * [LORA] means an OSHI envelope that happened to travel by radio — the
     * ratchet proved who sent it, exactly as it would over the internet. This
     * one is the other lane: portnum 1, plaintext, on a channel whose key is
     * public. Its `from` field is an unsigned integer anyone in radio range can
     * forge, including into a node number this device has legitimately bound to
     * a contact.
     *
     * The distinction has to be STORED, not derived at render time, because the
     * only moment we know it is when the packet arrives. Without it, filing
     * interop text into a contact's end-to-end-encrypted thread would draw
     * unverified plaintext identically to a message the ratchet authenticated —
     * which is precisely why `LoRaNodeIdentityMap.adoptRawTextIntoContactThread`
     * stayed off until this existed.
     */
    LORA_PLAINTEXT
}

enum class MediaType {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    LOCATION,
    CONTACT;

    companion object {
        /**
         * 🔴 THE ONE PLACE A WIRE `mediaType` LABEL BECOMES A [MediaType], AND IT
         * NEVER THROWS.
         *
         * Every transport spells the label differently — iOS `MediaManager.MediaType`
         * sends lowercase `image/video/audio/document/contact`, iOS group messages send
         * `photo` (`GroupMessaging.swift:406-412`), Android's own web bridge and sync
         * archive send the uppercase enum NAME, and the notification catalog has always
         * treated `voice` as audio. Four call sites each had their own `when`, and each
         * one knew a different subset.
         *
         * The reason it is one function now is INTEROP, not tidiness: iOS is adding a
         * `.gif` case, so Android is about to start receiving a label its enum does not
         * have. A label this table does not know returns **null** — never an exception —
         * and the caller is expected to fall back to [sniff] and then to [DOCUMENT]
         * rather than drop the message. Losing the *label* must never cost the user the
         * *attachment*.
         *
         * `gif` maps to [IMAGE] deliberately: Android has no GIF case and inventing one
         * would need a Room migration, a bubble renderer and an outbound spelling iOS
         * has not shipped yet. An animated GIF shown as a still image is a degraded
         * render; a GIF that never arrives is a lost message.
         */
        fun fromWireLabel(raw: String?): MediaType? = when (raw?.trim()?.lowercase()) {
            "image", "photo", "gif" -> IMAGE
            "video" -> VIDEO
            "audio", "voice" -> AUDIO
            "document", "file" -> DOCUMENT
            "location" -> LOCATION
            "contact" -> CONTACT
            else -> null
        }

        /**
         * Classify an attachment from its own first bytes, for when the label is
         * missing or unknown — iOS's fallback since the 2026-09-12 audit.
         *
         * Only the signatures that answer "is this something the image bubble can
         * draw?" are here; anything else returns null and the caller lands on
         * [DOCUMENT], which renders and can be saved. Byte 4 of a GIF is the version
         * (`GIF87a` / `GIF89a`), so matching the four-byte `GIF8` prefix covers both.
         */
        fun sniff(bytes: ByteArray?): MediaType? {
            val b = bytes ?: return null
            if (b.size < 4) return null
            fun at(i: Int): Int = b[i].toInt() and 0xFF
            fun startsWith(vararg sig: Int): Boolean =
                b.size >= sig.size && sig.indices.all { at(it) == sig[it] }
            return when {
                startsWith(0x47, 0x49, 0x46, 0x38) -> IMAGE                 // GIF87a / GIF89a
                startsWith(0x89, 0x50, 0x4E, 0x47) -> IMAGE                 // PNG
                startsWith(0xFF, 0xD8, 0xFF) -> IMAGE                       // JPEG
                // RIFF....WEBP — the container tag sits at offset 8.
                startsWith(0x52, 0x49, 0x46, 0x46) && b.size >= 12 &&
                    at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> IMAGE
                startsWith(0x25, 0x50, 0x44, 0x46) -> DOCUMENT              // %PDF
                else -> null
            }
        }
    }
}

/**
 * ContactPayload - decoded body of a CONTACT-typed media message.
 *
 * 🔴 This is the READ model. Do NOT serialize it to build an outbound card:
 * [ContactCardWire.encode] is the single writer, because iOS's `ContactCard`
 * (`OSHI/MediaManager.swift:913-917`) declares `timestamp` **non-optional** and
 * kotlinx omits any property that equals its declared default — a `timestamp`
 * with a default would disappear from the wire exactly when it mattered and give
 * every iPhone back the `keyNotFound` this field exists to prevent.
 *
 * Field-for-field against iOS: `publicKey` (required both ways), `alias`
 * (optional both ways), `timestamp` (Apple reference-date **seconds**, because
 * iOS decodes with a bare `JSONDecoder` = `.deferredToDate`; nullable here only
 * so a card from an older Android build still decodes). `avatarUrl` is
 * Android-only and never emitted — iOS's synthesized `Codable` would ignore it.
 */
@kotlinx.serialization.Serializable
data class ContactPayload(
    val publicKey: String,     // base64-encoded OSHI public key
    val alias: String? = null,
    val avatarUrl: String? = null,
    val timestamp: Double? = null
)

/**
 * Contact Entity - Compatible with iOS contact aliases
 */
@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey
    val publicKey: String,
    val alias: String,
    val profilePicture: ByteArray? = null,
    val isVerified: Boolean = false,
    val isBlocked: Boolean = false,
    val lastSeen: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * 🔴 THIS COMPARED THE KEY AND NOTHING ELSE — WHICH SILENTLY UNDID THE AVATAR FIX.
     *
     * Contacts reach the UI through a `StateFlow`, and `StateFlowImpl` conflates on
     * `equals`. With only `publicKey` compared, a contact whose ALIAS, AVATAR or
     * BLOCK STATE had just changed was `==` to its previous self, so the emission was
     * dropped and nothing redrew.
     *
     * That is the second half of the "an iPhone's avatar never appears on Android"
     * bug. The first half was the write going to the wrong row; fixing that made the
     * bytes land in the database — and this would still have stopped them ever being
     * drawn. A profile-picture fix that stops at the DAO is not a fix.
     *
     * `profilePicture` is compared by CONTENT, which is affordable because inbound
     * avatars are now capped at 256 KB and `contentEquals` short-circuits on length
     * first, so the common "unchanged" case is a size comparison.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Contact
        if (publicKey != other.publicKey) return false
        if (alias != other.alias) return false
        if (isVerified != other.isVerified) return false
        if (isBlocked != other.isBlocked) return false
        if (lastSeen != other.lastSeen) return false
        val a = profilePicture
        val b = other.profilePicture
        return when {
            a == null && b == null -> true
            a == null || b == null -> false
            else -> a.contentEquals(b)
        }
    }

    /**
     * Keyed on the public key alone, deliberately — see the note on [Message.hashCode].
     * A contact's identity is its key; the mutable fields above must not move its hash.
     */
    override fun hashCode(): Int = publicKey.hashCode()
}

/**
 * Conversation - for UI display
 */
data class Conversation(
    val participantPublicKey: String,
    val participantAlias: String,
    val participantProfilePicture: ByteArray?,
    val lastMessage: Message,
    val unreadCount: Int,
    val isTyping: Boolean = false,
    val isOnline: Boolean = false,
    /** Mirrors `Contact.isVerified` so the list can draw the badge without a second read. */
    val isVerified: Boolean = false,
    /**
     * __SAVED_CONTACT_SHOWS_AT_ONCE_2026_11_13__ False for a saved contact nobody has
     * written to yet: [lastMessage] is then a stand-in, not a row in the database.
     */
    val hasMessages: Boolean = true
)

/**
 * Call History Entity
 */
@Entity(tableName = "call_history")
data class CallHistoryEntry(
    @PrimaryKey
    val callId: String,
    val peerPublicKey: String,
    val peerName: String,
    val isOutgoing: Boolean,
    val isVideoCall: Boolean,
    val startTime: Long,
    val endTime: Long? = null,
    val duration: Long = 0,  // in seconds
    val status: CallStatus
)

enum class CallStatus {
    COMPLETED,
    MISSED,
    DECLINED,
    NO_ANSWER,
    FAILED
}
