package com.oshi.messenger.data.model

import android.content.Context
import com.oshi.messenger.R
import org.json.JSONObject

/**
 * THE list of emoji-sentinel control payloads, and the only place that decides
 * what one of them looks like on screen.
 *
 * Straight port of `OSHI/OSHIControlPayload.swift` (the shipping iOS app), which
 * exists because iOS had SIX partial copies of the prefix list and "add the
 * missing prefix" therefore fixed exactly one surface. Android had FOUR:
 *
 *     ChatScreen.kt:1406          12 prefixes  (bubble filter)
 *     ChatScreen.kt:2383-2405      6 prefixes  (bubble type detection)
 *     ConversationsScreen.kt:311  ~18 prefixes (list preview)
 *     MessageSanitizer.kt:16-115  ~22 prefixes (notifications, FCM)
 *
 * The rule from now on: a new control message is declared ONCE, in [catalog].
 *
 * The three questions a surface can ask (Swift:34-44):
 *   [isControl]     "is this a sentinel payload at all?"
 *   [isSilent]      "must this never be shown, anywhere?"
 *   [previewText]   "what one line do I put on screen?" — prose comes back
 *                   unchanged, so it is always safe to route text through it.
 *
 * And the fourth, which only a LIST ROW or a NOTIFICATION may ask:
 *   [listPreview]   same, but an unclaimed machine payload comes back as a
 *                   named placeholder instead of as itself. A bubble must NOT
 *                   use it — a user who pastes JSON has to see their JSON.
 *
 * [displaySummary] is the raw form of the last one: null when the text is
 * ordinary prose, "" when it must be hidden, a human sentence otherwise.
 *
 * Localization: iOS resolves its strings through `.localized`; Kotlin has no
 * ambient locale in a pure object, so every user-facing string arrives in
 * [Labels]. Call [labels] with a `Context` from UI code; unit tests use the
 * English defaults.
 */
object OSHIControlPayload {

    // MARK: - How a payload presents itself (Swift:57-66)

    enum class Kind {
        /** Pure protocol. Not a message. Never render it, never count it. */
        SILENT,

        /** A real user-facing event that travels as a sentinel payload. */
        RENDERED,

        /** A user message wrapped in an envelope (reply / forward). */
        ENVELOPE
    }

    data class Entry(val prefix: String, val kind: Kind)

    // MARK: - Named accessors (Swift:114-123)

    const val CALL_SIGNAL = "📞CALL_SIGNAL📞"            // 📞CALL_SIGNAL📞
    const val DELIVERY_RECEIPT = "📬DELIVERY_RECEIPT📬"  // 📬DELIVERY_RECEIPT📬
    const val READ_RECEIPT = "📖READ_RECEIPT📖"          // 📖READ_RECEIPT📖
    const val TYPING_IOS = "⌨️TYPING⌨️"                  // ⌨️TYPING⌨️
    const val TYPING_ANDROID = "✍️TYPING✍️"              // ✍️TYPING✍️
    const val LINKS_VISIBILITY = "🔗LINKS_VISIBILITY🔗"  // 🔗LINKS_VISIBILITY🔗
    const val PROFILE_REQUEST = "📸PROFILE_REQUEST📸"    // 📸PROFILE_REQUEST📸
    const val ACTION = "🔧ACTION🔧"                      // 🔧ACTION🔧
    const val GROUP_UPDATE = "📢GROUP_UPDATE📢"          // 📢GROUP_UPDATE📢
    const val SESSION_RESET = "__SESSION_RESET__"
    // __GROUP_RECEIPTS_2026_11_02__ (N8) per-message, per-member "received"
    // and "read" for a GROUP message, addressed to the sender alone.
    const val GROUP_RECEIPT = "\uD83D\uDCD1GROUP_RECEIPT\uD83D\uDCD1"   // 📑GROUP_RECEIPT📑

    const val CALL_SUMMARY = "📱CALL_SUMMARY📱"          // 📱CALL_SUMMARY📱
    const val MISSED_CALL = "📱MISSED_CALL📱"            // 📱MISSED_CALL📱
    const val LOCATION = "📍LOCATION📍"                  // 📍LOCATION📍
    const val SCHEDULED_CALL = "📅SCHEDULED_CALL📅"      // 📅SCHEDULED_CALL📅
    const val CHECK_IN = "🛡️CHECK_IN🛡️"      // 🛡️CHECK_IN🛡️
    const val CHECKIN = "🛡️CHECKIN🛡️"        // 🛡️CHECKIN🛡️
    /**
     * __ALIVE_CHECK_IN_2026_11_04__ "I'm alive." A recurring liveness
     * confirmation, scoped to one conversation.
     *
     * RENDERED, not silent, and that is the point: a confirmation the reader
     * cannot find afterwards is a confirmation they have to take on trust from
     * a coloured dot. The thread is the record — who confirmed, when, and when
     * the schedule changed. The body is the JSON of
     * [com.oshi.messenger.data.model.AliveCheckInEvent].
     *
     * A separate sentinel from `🛡️CHECK_IN🛡️` on purpose: that one is a
     * journey with an ETA and a location, this one is a heartbeat with neither.
     * See `AliveCheckIn.kt`. iOS: `OSHI/AliveCheckIn.swift`.
     */
    const val ALIVE_CHECK_IN = "💓ALIVE💓"                // 💓ALIVE💓

    const val PROFILE_UPDATE = "📸PROFILE_UPDATE📸"      // 📸PROFILE_UPDATE📸
    const val WALLPAPER_UPDATE = "🎨WALLPAPER_UPDATE🎨"  // 🎨WALLPAPER_UPDATE🎨
    const val REACTION = "🔥REACTION🔥"                  // 🔥REACTION🔥
    const val REACTION_LEGACY = "👍 REACTION👍"          // "👍 REACTION👍"
    const val KEY_ROTATION = "🔑KEY_ROTATION🔑"          // 🔑KEY_ROTATION🔑

    /**
     * __BLOCKED_NOTICE_2026_09_18__ "Nothing you send here arrives any more."
     *
     * Sent BY the blocker's device, TO the blocked key, when something addressed
     * to a blocked contact comes in. It carries no body: the whole meaning is
     * the sentinel, and the blocked side renders it in its OWN language.
     *
     * This is the visible half of a product decision. Blocking used to be
     * entirely silent — the sender watched a message leave and never learned it
     * had been refused — so the wire now needs a way to say it. The rate limit
     * lives at the send site rather than here (`MessageRepository`
     * .noticeBlockedSender): once per peer per day, so a blocked spammer cannot
     * turn their own traffic into a heartbeat telling them when the person who
     * blocked them has their phone in hand.
     */
    const val BLOCKED_NOTICE = "🚫BLOCKED🚫"              // 🚫BLOCKED🚫

    /**
     * __POLLS_2026_10_23__ A poll and a ballot.
     *
     * The poll RENDERS — it is a bubble, and it is worth a notification. The
     * ballot is SILENT: it draws nothing and wakes nobody, because twenty
     * members voting would otherwise be twenty banners. The body of each is the
     * JSON of [PollMessage] / [PollVotePayload].
     *
     * The voter is NOT in the JSON: it is the sender of the decrypted envelope,
     * authenticated by the ratchet. A "voter" field on the wire would let one
     * member vote in another's name. iOS: `OSHIControlPayload.swift`.
     */
    const val POLL = "📊POLL📊"                          // 📊POLL📊
    const val POLL_VOTE = "📊POLL_VOTE📊"                // 📊POLL_VOTE📊

    /**
     * __OFFLINE_ONLY_CONVERSATION_2026_10_23__ "This conversation never touches
     * the internet."
     *
     * RENDERED, not silent: a change to how a conversation is routed is exactly
     * what a reader should be able to see happen, and when, and by whom. The body
     * is the JSON of [OfflineOnlyRequest] — `{"v":1,"on":true,"at":<unix s>}`.
     *
     * It is a REQUEST, not an instruction. Nothing can stop another phone using
     * the relay; what this buys is that every peer which understands it stops
     * too, and that the UI can say how many did. iOS:
     * `OfflineOnlyConversation.swift`.
     */
    const val OFFLINE_ONLY = "🔒OFFLINE_ONLY🔒"          // 🔒OFFLINE_ONLY🔒

    /**
     * __ADV_CHAT_PRIVACY_2026_11_26__ "Advanced chat privacy" switched on or off
     * in this conversation. Body: [AdvancedChatPrivacyChange] JSON.
     *
     * Two spellings, one entry. The WIRE spelling (what we send, agreed with iOS)
     * is the bare shield `🛡ADV_PRIVACY🛡`. The CATALOG key carries U+FE0F, so the
     * derived U+FE0F-stripped twin (see `variants`) matches the bare wire form AND
     * an emoji-picker spelling with the selector still matches — a Swift literal
     * typed from a keyboard usually has it.
     *
     * RENDERED (a centred notice naming who switched it) but never a banner and
     * never unread — see [renderedButNotWorthWaking] and the receive handlers.
     */
    const val ADV_PRIVACY = "🛡️ADV_PRIVACY🛡️"   // 🛡️ADV_PRIVACY🛡️
    const val ADV_PRIVACY_WIRE = "🛡ADV_PRIVACY🛡"         // 🛡ADV_PRIVACY🛡
    const val MEDIA = "MEDIA|||"

    /**
     * Local-only caption marker written by `ChatViewModel.sendContactCard` /
     * `GroupChatViewModel.sendContactCard`. It NEVER travels: the recipient reads
     * the card out of the media bytes ([com.oshi.messenger.data.model.ContactCardWire]),
     * not out of this string. It exists so the conversation row can name the
     * shared person instead of printing a bracket.
     */
    const val CONTACT_CAPTION_PREFIX = "[CONTACT] "

    const val REPLY = "💬REPLY💬"                        // 💬REPLY💬
    const val FORWARDED = "➡️FORWARDED➡️"                // ➡️FORWARDED➡️

    // MARK: - THE catalog (single source of truth, Swift:82-109)

    /**
     * Every sentinel on the wire. **Add a new control message here and nowhere
     * else.** Order and membership mirror Swift:82-109 exactly.
     *
     * Duplicate spellings are deliberate: iOS and Android disagree on
     * `CHECK_IN`/`CHECKIN` and on the reaction marker, and both forms are live
     * on the wire today. Dropping either one un-fixes a device.
     */
    val catalog: List<Entry> = listOf(
        Entry(CALL_SIGNAL, Kind.SILENT),
        Entry(DELIVERY_RECEIPT, Kind.SILENT),
        Entry(READ_RECEIPT, Kind.SILENT),
        Entry(TYPING_IOS, Kind.SILENT),
        Entry(TYPING_ANDROID, Kind.SILENT),
        Entry(LINKS_VISIBILITY, Kind.SILENT),
        Entry(PROFILE_REQUEST, Kind.SILENT),
        Entry(ACTION, Kind.SILENT),
        Entry(GROUP_UPDATE, Kind.SILENT),
        Entry(POLL_VOTE, Kind.SILENT),
        Entry(SESSION_RESET, Kind.SILENT),
        Entry(GROUP_RECEIPT, Kind.SILENT),

        Entry(CALL_SUMMARY, Kind.RENDERED),
        Entry(MISSED_CALL, Kind.RENDERED),
        Entry(LOCATION, Kind.RENDERED),
        Entry(SCHEDULED_CALL, Kind.RENDERED),
        Entry(CHECK_IN, Kind.RENDERED),
        Entry(CHECKIN, Kind.RENDERED),
        Entry(ALIVE_CHECK_IN, Kind.RENDERED),
        Entry(PROFILE_UPDATE, Kind.RENDERED),
        Entry(WALLPAPER_UPDATE, Kind.RENDERED),
        Entry(REACTION, Kind.RENDERED),
        Entry(REACTION_LEGACY, Kind.RENDERED),
        Entry(KEY_ROTATION, Kind.RENDERED),
        Entry(BLOCKED_NOTICE, Kind.RENDERED),
        Entry(POLL, Kind.RENDERED),
        Entry(OFFLINE_ONLY, Kind.RENDERED),
        Entry(ADV_PRIVACY, Kind.RENDERED),
        Entry(MEDIA, Kind.RENDERED),

        Entry(REPLY, Kind.ENVELOPE),
        Entry(FORWARDED, Kind.ENVELOPE)
    )

    /** Flat list, longest-first, for callers that need the raw strings. */
    val prefixes: List<String> = catalog.map { it.prefix }.sortedByDescending { it.length }

    // MARK: - Matching (Swift:132-176)

    /**
     * U+FE0F (VARIATION SELECTOR-16) is present in `🛡️`, `⌨️`, `✍️` and `➡️` as
     * written above and ABSENT from payloads built by older iOS builds and by
     * some Android paths, which emit the bare codepoint. Kotlin's [startsWith]
     * compares UTF-16 units with no canonical equivalence at all, so a single
     * spelling silently fails to match half the traffic.
     *
     * Rather than duplicate every entry in [catalog] (the exact mistake this
     * file exists to stop), the variants are DERIVED: each catalog prefix
     * contributes itself plus its U+FE0F-stripped twin, both resolving back to
     * the same catalog entry.
     */
    private data class Variant(val spelling: String, val prefix: String, val kind: Kind)

    private val variants: List<Variant> = buildList {
        for (entry in catalog) {
            add(Variant(entry.prefix, entry.prefix, entry.kind))
            val stripped = entry.prefix.replace("\uFE0F", "")
            if (stripped != entry.prefix) {
                add(Variant(stripped, entry.prefix, entry.kind))
            }
        }
    }.sortedByDescending { it.spelling.length }

    /**
     * The catalog prefix [text] starts with, in its canonical spelling, or null.
     *
     * Strictly anchored at the start: a message that merely CONTAINS a sentinel
     * ("we should add a 📍LOCATION📍 marker") is ordinary prose and is left alone.
     */
    fun matchedPrefix(text: String): String? =
        variants.firstOrNull { text.startsWith(it.spelling) }?.prefix

    private fun kindOf(text: String): Kind? =
        variants.firstOrNull { text.startsWith(it.spelling) }?.kind

    /** True when [text] is any sentinel payload — envelopes included. */
    fun isControl(text: String): Boolean = kindOf(text) != null || isSilentJSON(text)

    /** True when [text] must never reach the user in any form: drop the message. */
    fun isSilent(text: String): Boolean = isSilentJSON(text) || kindOf(text) == Kind.SILENT

    /** True when [text] is a reply / forward envelope around a user message. */
    fun isEnvelope(text: String): Boolean = kindOf(text) == Kind.ENVELOPE

    /** A call signal is silent in the thread but must still be allowed to RING. */
    private val silentButStillRings = setOf(CALL_SIGNAL)

    /**
     * Payloads that DO get a row in the thread but are not worth waking anyone for:
     * a new avatar, a new wallpaper. Port of iOS `renderedButNotWorthWaking`
     * (`OSHIControlPayload.swift:257-259`).
     */
    private val renderedButNotWorthWaking =
        setOf(PROFILE_UPDATE, WALLPAPER_UPDATE, BLOCKED_NOTICE, ADV_PRIVACY)

    /**
     * __ADV_CHAT_PRIVACY_2026_11_26__ Rendered in the thread, but NEVER a local
     * banner either (not just "no push"): a settings change is not a message, and
     * a banner reading "X turned on advanced chat privacy" on the lock screen
     * would itself be a leak of the conversation's existence.
     */
    private val renderedButNeverNotifies = setOf(ADV_PRIVACY)

    /** True when [text] gets a row in the thread but must never raise a banner nor count as unread. */
    fun neverNotifies(text: String): Boolean = matchedPrefix(text) in renderedButNeverNotifies

    /**
     * Sending this payload must wake NOBODY — the peer picks it up on its next poll.
     * The data still goes out; only the NOTIFICATION is suppressed.
     *
     * __PUSH_SPAM_2026_09_17__ Android had its own hand-written list of eight
     * prefixes in `MessageRepository`, which had drifted from this catalogue. Four
     * classes of traffic were therefore pushing an APNs alert at every iPhone peer:
     *
     *   • `ACTION` — every edit, delete, pin and unpin, so correcting a typo rang
     *     the other phone with "You have a new encrypted message";
     *   • `GROUP_UPDATE` and `SESSION_RESET`;
     *   • and the bare group-sync JSON (`member_sync` / `sync_request` /
     *     `group_update`), which is PERIODIC — that is the one that reads as spam,
     *     because it fires with no user action at all.
     *
     * Deciding it HERE, off the same catalogue the renderer uses, is the point: a
     * second list is what drifted. Mirrors iOS `suppressesPush`
     * (`OSHIControlPayload.swift:264-269`) exactly, including the U+FE0F-stripped
     * spellings that `matchedPrefix` already knows about.
     */
    fun suppressesPush(text: String): Boolean {
        val prefix = matchedPrefix(text) ?: return isSilentJSON(text)
        if (prefix in silentButStillRings) return false
        if (prefix in renderedButNotWorthWaking) return true
        return kindOf(text) == Kind.SILENT
    }

    /**
     * The body after the sentinel, using the variant actually present at the
     * head of [text] (which may be the U+FE0F-stripped spelling, one char
     * shorter than the canonical one). Non-sentinel text comes back unchanged.
     */
    fun stripPrefix(text: String): String {
        val variant = variants.firstOrNull { text.startsWith(it.spelling) } ?: return text
        return text.substring(variant.spelling.length)
    }

    /**
     * The body of ONE named sentinel, or null when [text] is not that payload.
     *
     * [stripPrefix] answers "whatever this is, give me the body"; this answers
     * "is this a poll, and if so give me its JSON". Goes through the variant
     * table, so the U+FE0F-stripped spelling is cut at the right length — a
     * `removePrefix` with the canonical constant would leave one stray
     * character at the head of the JSON and the parse would fail for an
     * invisible reason.
     */
    fun body(text: String, prefix: String): String? {
        if (matchedPrefix(text) != prefix) return null
        return stripPrefix(text)
    }

    /** Group sync traffic rides as a bare JSON object with no sentinel (Swift:192-200). */
    private fun isSilentJSON(text: String): Boolean {
        if (!text.startsWith("{")) return false
        return text.contains("\"type\":\"member_sync") ||
            text.contains("\"type\":\"sync_request") ||
            text.contains("\"type\":\"group_update") ||
            text.contains("\"type\": \"member_sync") ||
            text.contains("\"type\": \"sync_request") ||
            text.contains("\"type\": \"group_update")
    }

    /**
     * "Is this decrypted 1:1 plaintext a legacy inline MEDIA envelope?"
     *
     * 🔴 P0 (2026-08-24 photo/link sweep). The receive chain used to answer this
     * with `decrypted.startsWith("{\"type\":")` — four copies of it, at
     * `MessageRepository.kt:2610/2923/3117/4436`. That is a bet on JSON KEY ORDER,
     * and the iPhone loses it four times out of five.
     *
     * iOS builds the envelope as a Swift `[String: Any]` and serialises it with
     * `JSONSerialization.data(withJSONObject:)` and no `.sortedKeys`
     * (`OSHI/MessageManager.swift:5286-5300`). Dictionary iteration order is the
     * per-process seeded hash order, so the SAME code emits
     * `{"type":"media",…}` on one launch and `{"isViewOnce":false,…}` or
     * `{"fileName":"photo.jpg",…}` on the next. Measured on this machine with a
     * byte-for-byte replica of that dictionary: `type` came first in **2 of 10**
     * process launches.
     *
     * When it did not, the photo fell past the media arm into the text arm and was
     * stored as a plain message. The bytes are then gone for good — the row has no
     * `mediaType` and no `mediaData`, and [mediaJSONSummary] paints the bubble a
     * tidy "📷 Photo" so nothing on screen even looks broken.
     *
     * iOS's mirror of this question has never been order-dependent: `isMediaMessage`
     * (`OSHI/MessageManager.swift:7058-7105`) does `contains` over a pattern list and
     * then re-parses the whole object. Android's own GROUP path is fine for the same
     * reason (`GroupManager.kt:1643` uses `contains`). The 1:1 path was the outlier.
     *
     * Deliberately STRICTER than iOS's version: iOS returns true on the mere
     * presence of a `mediaType` key, but a false positive here is not a text
     * bubble — `handleMediaContent` returns early without inserting anything, so
     * the message would VANISH. A payload key (`content` or `data`) carrying a
     * non-empty string is therefore required as well, which every real shape has:
     *
     *   * iOS VPS/IPFS wrapper — `{"type":"media","mediaType":"image","content":…}`
     *     (`MessageManager.swift:5286-5292`)
     *   * Android direct       — `{"type":"IMAGE","data":…}`
     *     (`MessageRepository.kt:2222`)
     *   * iOS cross-platform mesh — `{"mediaType":"image","data":…}`
     *     (`CrossPlatformMesh.swift:387-404`)
     *
     * Sync / link / receipt envelopes are all matched EARLIER in every caller and
     * carry no `content`/`data` string, so none of them can be claimed here.
     */
    fun isMediaEnvelope(text: String): Boolean {
        val t = text.trimStart()
        if (!t.startsWith("{")) return false
        // Cheap reject before allocating a JSONObject over a multi-megabyte base64
        // body for every inbound message that merely happens to start with `{`.
        if (!t.contains("\"content\"") && !t.contains("\"data\"")) return false
        val obj = try { JSONObject(t) } catch (_: Exception) { return false }
        val payload = obj.optString("content", "").ifEmpty { obj.optString("data", "") }
        if (payload.isEmpty()) return false
        val type = obj.optString("type", "")
        if (type.equals("media", ignoreCase = true)) return true
        if (type.uppercase() in ANDROID_DIRECT_MEDIA_TYPES) return true
        return obj.optString("mediaType", "").isNotEmpty()
    }

    /**
     * The uppercase `MediaType` names Android puts in the top-level `type` field of
     * its own direct envelope (`MessageRepository.kt:2222`, `type` = `MediaType.name`).
     * `LOCATION` is excluded: it travels under the `📍LOCATION📍` sentinel and is
     * matched before this ever runs.
     */
    private val ANDROID_DIRECT_MEDIA_TYPES =
        setOf("IMAGE", "VIDEO", "AUDIO", "DOCUMENT", "CONTACT")

    // MARK: - Display (Swift:204-245)

    /**
     * What to put on screen for [text].
     *
     *  * `null` — ordinary user prose, render it as-is.
     *  * `""`   — a silent payload, render nothing.
     *  * else   — a short human sentence, already localized via [labels].
     *
     * Reply and forward envelopes resolve to the body the user actually typed.
     */
    fun displaySummary(text: String, labels: Labels = Labels()): String? {
        if (isSilentJSON(text)) return ""
        val prefix = matchedPrefix(text)
        val kind = kindOf(text)
        if (prefix == null || kind == null) {
            // Bare media JSON is not sentinel-prefixed but is still not prose.
            return mediaJSONSummary(text, labels)
        }

        return when (kind) {
            Kind.SILENT -> ""
            Kind.ENVELOPE -> {
                // The envelope carries the real message in `content`. Recurse, so
                // a reply to a call summary quotes "Outgoing call • 1:23" and not
                // the JSON of a JSON.
                val body = envelopeBody(text) ?: return ""
                displaySummary(body, labels) ?: body
            }
            Kind.RENDERED -> summary(prefix, stripPrefix(text), labels)
        }
    }

    /**
     * One line of human-readable text for ANY message string. Plain prose is
     * returned unchanged, so this is always safe to wrap around something that
     * may or may not be a control payload.
     */
    fun previewText(text: String, labels: Labels = Labels()): String =
        displaySummary(text, labels) ?: text

    /**
     * [previewText] for a surface that shows ONE LINE ABOUT a message rather than
     * the message itself: a conversation-list row, a group-list row, a
     * notification body.
     *
     * The difference is the last resort. [previewText] hands unknown text back
     * unchanged, which is right inside a bubble — a user who pastes JSON must see
     * their JSON — and wrong on a row, where "unknown text" also covers every
     * machine payload no catalog entry claims: a ratchet envelope, a raw media
     * envelope from a client whose field names drifted, a `v2file` key message
     * whose required fields moved (`V2MessageRouter.kt:506-518` falls back to
     * "treat the plaintext as UTF-8 text", so a shape change lands the JSON in
     * `Message.content`).
     *
     * These nets are NOT new: they lived as literals inside
     * `ConversationsScreen.formatPreviewText`, which is why the groups list and
     * every notification had none. Hoisting them here is the whole point of the
     * catalog — and it makes them localizable, which as literals they were not.
     *
     * Ordinary prose still comes back unchanged: the guards below all require an
     * opening `{`, and the generic one additionally requires the string to close
     * with `}` and to be long enough that a person did not simply type it.
     */
    fun listPreview(text: String, labels: Labels = Labels()): String {
        // __A_PHOTO_WITH_NO_CAPTION_WAS_A_BLANK_ROW_2026_11_04__ A row must never
        // render EMPTY, and this has to be the FIRST question asked.
        //
        // Not the last: `displaySummary` answers "" (non-null) for a silent
        // payload, so a check at the bottom never runs for an empty body. That is
        // the right answer for a surface that may legitimately show nothing, and
        // the wrong one here, where a message that reached the list exists and so
        // there is always something true to say about it. "Nothing at all" is the
        // single answer that reads as a broken app rather than as a message.
        //
        // The case was a captionless photo stored with `content = ""`, fixed at
        // its source in `MessageRepository.inboundMediaDisplayText`. This net does
        // not depend on knowing which hole it catches: any future path that stores
        // an empty body lands here instead of on a user's screen.
        if (text.isBlank()) return "💬 " + labels.opaqueMessage

        displaySummary(text, labels)?.let { return it }

        val trimmed = text.trim()

        // Bracket placeholders written by MessageRepository when a caption is
        // empty. Without this the row read literally "[IMAGE]".
        when (trimmed) {
            "[IMAGE]" -> return "📷 " + labels.mediaImage
            "[VIDEO]" -> return "🎥 " + labels.mediaVideo
            "[AUDIO]" -> return "🎤 " + labels.mediaAudio
            "[DOCUMENT]" -> return "📄 " + labels.mediaDocument
            "[CONTACT]" -> return "👤 " + labels.mediaFile
        }

        // A shared contact card carries the shared person's NAME in its caption
        // (`ChatViewModel.sendContactCard`). Showing that name beats showing the
        // generic word, and beats the English literal "[CONTACT] Alice" this row
        // printed before. iOS draws the same row as `"👤 " + "media_file".localized`
        // (`OSHI/GroupViews.swift:523`), which is what the bare marker above maps to.
        if (trimmed.startsWith(CONTACT_CAPTION_PREFIX)) {
            val name = trimmed.removePrefix(CONTACT_CAPTION_PREFIX).trim()
            return "👤 " + name.ifEmpty { labels.mediaFile }
        }

        // Ciphertext that arrived as a bare marker rather than as JSON.
        if (trimmed.startsWith("[Encrypted") || trimmed.contains("ratchet sync")) {
            return "🔒 " + labels.encryptedMessage
        }

        if (!trimmed.startsWith("{")) return text

        // A cross-platform media envelope that was not routed to the media
        // handler (a sender-side field-name mismatch, or a stray pass-through).
        if (trimmed.startsWith("{\"type\":") || trimmed.startsWith("{\"mediaType\":")) {
            val lower = trimmed.lowercase()
            return when {
                "\"image\"" in lower || "\"photo\"" in lower -> "📷 " + labels.mediaImage
                "\"video\"" in lower -> "🎥 " + labels.mediaVideo
                "\"audio\"" in lower || "\"voice\"" in lower -> "🎤 " + labels.mediaAudio
                "\"document\"" in lower -> "📄 " + labels.mediaDocument
                else -> "📎 " + labels.mediaFile
            }
        }

        // Ciphertext that reached a display surface. Both needles are unquoted,
        // verbatim from the `ConversationsScreen` block this replaced — they are
        // only ever tested against a string that already opens with `{`.
        if (trimmed.contains("ciphertext") || trimmed.contains("header")) {
            return "🔒 " + labels.encryptedMessage
        }

        // An AI proposal / action envelope. Lower length bar than the generic net
        // below because the key names identify it positively.
        if (trimmed.length > 20 && (
                trimmed.contains("\"action\"") ||
                trimmed.contains("\"reply\"") ||
                trimmed.contains("\"suggestion\"") ||
                trimmed.contains("\"proposal\"")
            )
        ) {
            return "💬 " + labels.opaqueMessage
        }

        // Anything else machine-shaped. `length > 40` and a closing brace are the
        // thresholds `ConversationsScreen` already used, kept verbatim so this is
        // a move and not a behaviour change.
        if (trimmed.endsWith("}") && trimmed.length > 40) {
            return "💬 " + labels.opaqueMessage
        }

        return text
    }

    /**
     * Non-sentinel prose that is nonetheless not safe on a row. Split out of
     * [listPreview] so a caller that must NOT rewrite prose (a bubble) can still
     * ask the question.
     */
    fun isOpaquePayload(text: String, labels: Labels = Labels()): Boolean =
        listPreview(text, labels) != text

    /**
     * Like [previewText], but hands reply / forward envelopes back untouched —
     * the bubble renderers decode the envelope THEMSELVES to draw the quote box
     * (Swift:276-279).
     */
    fun displayTextPreservingEnvelopes(text: String, labels: Labels = Labels()): String =
        if (isEnvelope(text)) text else previewText(text, labels)

    // MARK: - Per-payload summaries (Swift:283-311)

    private fun summary(prefix: String, body: String, labels: Labels): String = when (prefix) {
        CALL_SUMMARY -> callSummary(body, missed = false, labels = labels)
        MISSED_CALL -> callSummary(body, missed = true, labels = labels)
        LOCATION -> locationSummary(body, labels)
        SCHEDULED_CALL -> "📅 " + labels.scheduledCall
        CHECK_IN, CHECKIN -> "🛡️ " + labels.checkIn
        // __ALIVE_CHECK_IN_2026_11_04__ Which of the three events it was, because
        // "a check-in happened" is the one reading that tells the reader nothing.
        // An unreadable body falls back to the plain confirmation: a row that
        // said "stopped" on a corrupt payload would claim a schedule ended when
        // it has not.
        ALIVE_CHECK_IN -> "💓 " + aliveCheckInLabel(body, labels)
        PROFILE_UPDATE -> "📸 " + labels.profileUpdated
        WALLPAPER_UPDATE -> "🎨 " + labels.wallpaperChanged
        REACTION, REACTION_LEGACY -> "🔥 " + labels.reaction
        KEY_ROTATION -> "🔑 " + labels.keyRotation
        BLOCKED_NOTICE -> "🚫 " + labels.blockedByContact
        // The QUESTION, not the word "poll": that is what makes a conversation
        // row and a banner useful. Falls back to the title when the JSON is
        // unreadable.
        POLL -> "📊 " + (pollQuestion(body) ?: labels.poll)
        // __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Which WAY it was switched is
        // the whole content of this line. An unreadable body falls back to the
        // restrictive reading: claiming a conversation is open when we cannot
        // tell is the error that costs something.
        OFFLINE_ONLY -> "🔒 " + (if (offlineOnlyIsOn(body)) labels.offlineOnlyOn else labels.offlineOnlyOff)
        // __ADV_CHAT_PRIVACY_2026_11_26__ The row / list line. The thread itself
        // draws a richer notice that names who switched it.
        ADV_PRIVACY -> "🛡 " + (if (offlineOnlyIsOn(body)) labels.advPrivacyOn else labels.advPrivacyOff)
        MEDIA -> mediaMarkerSummary(body, labels)
        // Unreachable while every RENDERED entry is handled above; a new catalog
        // entry that forgets its branch degrades to "hide it" rather than to
        // "print the JSON", which is the failure that matters.
        else -> ""
    }

    /**
     * __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Defaults to TRUE when the body is
     * unreadable. A corrupt payload that reads as "the internet is allowed
     * again" would quietly undo a restriction the user asked for; one that reads
     * as "still restricted" costs at worst a message that waits.
     */
    /**
     * __ALIVE_CHECK_IN_2026_11_04__ The one line a row or a banner shows for an
     * alive check-in. Reads the `t` field of
     * [com.oshi.messenger.data.model.AliveCheckInEvent]'s JSON directly rather
     * than parsing the whole event: this runs once per visible row, and the
     * other three fields have no bearing on the sentence.
     */
    private fun aliveCheckInLabel(body: String, labels: Labels): String = try {
        when (JSONObject(body).optString("t")) {
            "on" -> labels.aliveCheckInStarted
            "off" -> labels.aliveCheckInStopped
            else -> labels.aliveCheckInConfirmed
        }
    } catch (_: Exception) {
        labels.aliveCheckInConfirmed
    }

    private fun offlineOnlyIsOn(body: String): Boolean = try {
        JSONObject(body).optBoolean("on", true)
    } catch (_: Exception) {
        true
    }

    private fun pollQuestion(body: String): String? = try {
        JSONObject(body).optString("q").trim().ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    // MARK: - Answered-call summary: producer and filter, side by side (Swift:120-148)

    /**
     * The summary of an ANSWERED call, as it is written into the thread.
     *
     * The direction is the WRITING device's own — that is the whole point of writing
     * it locally. The peer's copy carries THEIR direction and is never flipped on
     * read, so a call you received shows up as "outgoing call" if you keep it.
     */
    fun answeredCallSummary(wasOutgoing: Boolean, duration: String): String {
        val icon = if (wasOutgoing) "📞↗️" else "📞↙️"
        val key = if (wasOutgoing) "call.outgoing" else "call.incoming"
        return "$CALL_SUMMARY$icon$key|$duration"
    }

    /**
     * True for the summary of an ANSWERED call — and only that one (Swift:130).
     *
     * The `|` before the duration is the discriminant, and it is deliberate. The two
     * categories are treated in OPPOSITE ways on receipt:
     *
     *  - an answered call is observed by BOTH sides, so the peer's copy is a
     *    duplicate and must be dropped at ingestion — otherwise the thread shows two
     *    bubbles, one of them with the direction reversed;
     *  - a missed / declined / unanswered call may only ever have been seen by the
     *    caller, so its message is the sole trace it happened and must pass. Those
     *    carry no duration, hence no `|`.
     */
    fun isAnsweredCallSummary(text: String): Boolean =
        text.startsWith(CALL_SUMMARY) && text.contains("|")

    /**
     * Call rows travel in two shapes and BOTH have shipped (Swift:313-320):
     *
     *     "📞↗️call.outgoing|1:23"                 icon + key + duration
     *     "❌call.missed"                          icon + key, no duration
     *     {"direction":"outgoing","duration":83}   JSON, from other clients
     *
     * The KEY is sent, not the translated text, so each device renders the call
     * in its OWN language — that is why this cannot be "just show the body".
     */
    private fun callSummary(body: String, missed: Boolean, labels: Labels): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) return callSummaryFromJSON(trimmed, missed, labels)

        val icons = listOf("📞↗️", "📞↙️", "❌", "📞",
                           "📞↗", "📞↙")
        var icon = ""
        var rest = trimmed
        for (candidate in icons) {
            if (rest.startsWith(candidate)) {
                icon = candidate
                rest = rest.substring(candidate.length)
                break
            }
        }
        if (icon.isEmpty()) icon = if (missed) "❌" else "📞"

        val bar = rest.indexOf('|')
        val key: String
        val duration: String?
        if (bar >= 0) {
            key = rest.substring(0, bar)
            duration = rest.substring(bar + 1).takeIf { it.isNotEmpty() }
        } else {
            key = rest
            duration = null
        }

        // A key is `call.something`; anything else is either already-localized
        // text from an old build or garbage and must not be looked up.
        val label = when {
            key.startsWith("call.") && !key.contains(" ") -> localizeCallKey(key, labels)
            missed -> labels.callMissed
            else -> labels.callDefault
        }

        return if (duration != null) "$icon $label • $duration" else "$icon $label"
    }

    private fun callSummaryFromJSON(json: String, missed: Boolean, labels: Labels): String {
        val obj = try { JSONObject(json) } catch (_: Exception) { null }
            ?: return (if (missed) "❌ " else "📞 ") +
                (if (missed) labels.callMissed else labels.callDefault)

        val wasMissed = if (obj.has("missed") && !obj.isNull("missed")) obj.optBoolean("missed") else missed
        val direction = (obj.optString("direction").ifEmpty { obj.optString("type") }).lowercase()
        val outgoing = direction.contains("out")
        val icon = when {
            wasMissed -> "❌"
            outgoing -> "📞↗️"
            else -> "📞↙️"
        }
        val label = when {
            wasMissed -> labels.callMissed
            outgoing -> labels.callOutgoing
            else -> labels.callIncoming
        }
        val seconds = obj.optDouble("duration", 0.0)
        return if (seconds > 0) "$icon $label • ${formatDuration(seconds)}" else "$icon $label"
    }

    /** The `call.*` keys iOS puts on the wire (Swift:353-359 + CallSummaryBubble). */
    fun localizeCallKey(key: String, labels: Labels = Labels()): String = when (key) {
        "call.outgoing" -> labels.callOutgoing
        "call.incoming" -> labels.callIncoming
        "call.missed" -> labels.callMissed
        "call.no.answer" -> labels.callNoAnswer
        "call.declined" -> labels.callDeclined
        "call.contact.default" -> labels.callDefault
        // iOS's NSLocalizedString echoes an unknown key back verbatim; match it
        // rather than inventing a label.
        else -> key
    }

    /** mm:ss / h:mm:ss — no localization, digits only (Swift:395-400). */
    fun formatDuration(seconds: Double): String {
        val safe = if (seconds.isFinite()) seconds else 0.0
        val total = maxOf(0.0, safe).toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val locale = java.util.Locale.US
        return if (h > 0) String.format(locale, "%d:%02d:%02d", h, m, s)
        else String.format(locale, "%d:%02d", m, s)
    }

    private fun locationSummary(body: String, labels: Labels): String {
        try {
            val obj = JSONObject(body)
            // ⏹️ `isStopped` FIRST. The stop signal keeps `isLive = true` on the wire
            // (it is the same session, now ended — `LocationMessage.stopped`), so a
            // bare `isLive` test announces the END of a share as "Sharing live
            // location", in the conversation row and, since the stop now raises a
            // banner rather than being silenced, on the lock screen. The neutral
            // label is the honest one here; there is no "stopped sharing" string in
            // the catalog to reach for, and inventing an English literal in a
            // 34-language app is the bug this file exists to stop.
            if (obj.optBoolean("isLive", false) && !obj.optBoolean("isStopped", false)) {
                return "📍 " + labels.locationLive
            }
            val address = obj.optString("address").trim()
            if (address.isNotEmpty()) {
                val short = address.split(",").firstOrNull() ?: address
                return "📍 " + short.take(40)
            }
        } catch (_: Exception) {
            // fall through
        }
        return "📍 " + labels.locationShared
    }

    private fun mediaMarkerSummary(body: String, labels: Labels): String {
        // Wire form is "MEDIA|||<type>|||<payload>"; `body` is everything after
        // the marker, so the type is the first field.
        return when (body.split("|||").firstOrNull()?.lowercase() ?: "") {
            "image", "photo" -> "📷 " + labels.mediaImage
            "video" -> "🎥 " + labels.mediaVideo
            // Android knows audio and document apart; iOS collapses both to
            // "📎 File" (Swift:429). Keeping the richer Android labels — that is
            // an iOS-side gap, not an Android divergence.
            "audio", "voice" -> "🎤 " + labels.mediaAudio
            "document" -> "📄 " + labels.mediaDocument
            else -> "📎 " + labels.mediaFile
        }
    }

    /** Group media rides as a bare JSON object with `"type":"media"` (Swift:435-451). */
    private fun mediaJSONSummary(text: String, labels: Labels): String? {
        if (!text.startsWith("{")) return null
        v2FileSummary(text, labels)?.let { return it }
        if (!text.contains("\"type\":\"media\"") && !text.contains("\"type\": \"media\"")) return null
        val obj = try { JSONObject(text) } catch (_: Exception) { null } ?: return null
        val mediaType = obj.optString("mediaType").lowercase()
        val caption = obj.optString("caption").trim()
        val label = when (mediaType) {
            "photo", "image" -> "📷 " + labels.mediaImage
            "video" -> "🎥 " + labels.mediaVideo
            "audio", "voice" -> "🎤 " + labels.mediaAudio
            "document" -> "📄 " + labels.mediaDocument
            else -> "📎 " + labels.mediaFile
        }
        return if (caption.isEmpty()) label else "$label $caption"
    }

    /**
     * The V2 file-key message — `{"kind":"v2file","blobId":…,"fileKey":…}` — and
     * the progressive-reveal envelope, which is the same object with four extra
     * scalars (`network/v2/V2FileKeyMessage.kt:50-58`).
     *
     * It carries no sentinel prefix, so nothing above this line claims it. That
     * matters because the receive path is deliberately tolerant: iOS's rule, which
     * `V2MessageRouter.kt:506-518` mirrors, is *"if the plaintext decodes as
     * `kind == v2file` it is a file; **otherwise it is UTF-8 text**"*. So the day
     * a required field moves — a new iOS build, a truncated blob manifest —
     * `V2FileKeyMessage.parse` returns null, the router falls through to
     * `V2Inbound.Text`, and the whole key message is stored as `Message.content`
     * and printed at every surface, key material included.
     *
     * `kind` is the discriminant iOS itself uses, so matching on it alone (not on
     * the required fields) is what keeps a half-parsed payload from leaking: the
     * looser test is the safe one here, which is the opposite of the parse rule.
     */
    private fun v2FileSummary(text: String, labels: Labels): String? {
        if (!text.contains("\"kind\":\"v2file\"") && !text.contains("\"kind\": \"v2file\"")) return null
        val obj = try { JSONObject(text) } catch (_: Exception) { null }
        val mediaType = obj?.optString("mediaType")?.lowercase().orEmpty()
        return when (mediaType) {
            "image", "photo" -> "📷 " + labels.mediaImage
            "video" -> "🎥 " + labels.mediaVideo
            "audio", "voice" -> "🎤 " + labels.mediaAudio
            "document" -> "📄 " + labels.mediaDocument
            else -> "📎 " + labels.mediaFile
        }
    }

    // MARK: - Envelopes (Swift:455-466)

    /**
     * The user body inside a reply / forward envelope, or null when the JSON is
     * unreadable — in which case the caller must show NOTHING rather than the
     * broken JSON.
     */
    fun envelopeBody(text: String): String? {
        if (!isEnvelope(text)) return null
        return try {
            val obj = JSONObject(stripPrefix(text))
            if (obj.has("content") && !obj.isNull("content")) obj.getString("content") else null
        } catch (_: Exception) {
            null
        }
    }

    // MARK: - Localized labels

    /**
     * Every user-facing string the catalog can produce. Defaults are the English
     * copy iOS ships in `en.lproj/Localizable.strings`, so unit tests and any
     * non-UI caller get iOS-identical text without a Context.
     */
    data class Labels(
        val callOutgoing: String = "Outgoing call",
        val callIncoming: String = "Incoming call",
        val callMissed: String = "Missed call",
        val callNoAnswer: String = "No answer",
        val callDeclined: String = "Call declined",
        val callDefault: String = "Call",
        val locationLive: String = "Sharing live location",
        val locationShared: String = "Location",
        val scheduledCall: String = "Scheduled Call",
        val checkIn: String = "Check-In",
        /**
         * The NAME of each check-in type, for the conversation row.
         *
         * iOS shows the type ("Arrived Safely", "Emergency Alert"), not a flat
         * "Check-In" (`OSHI/MessagesListView.swift:1140-1146`); Android showed the
         * type too, but from seven English literals hardcoded on
         * `CheckInMessage.displayTitle`. A map rather than seven fields because the
         * row asks exactly one question — "what do I call this type?" — and a
         * missing key must fall back to [checkIn] rather than fail to compile a
         * caller that never had a `Context`.
         */
        val checkInTypes: Map<CheckInMessageType, String> = emptyMap(),
        /**
         * __ALIVE_CHECK_IN_2026_11_04__ Three lines, one per event, and none of
         * them names a person: the row already says who sent it, and a label
         * reading "they are alive" would be this app asserting something it
         * cannot know. "Confirmed they are alive" is the honest form — someone
         * pressed a button.
         */
        val aliveCheckInStarted: String = "Safety check-in started",
        val aliveCheckInConfirmed: String = "Confirmed alive",
        val aliveCheckInStopped: String = "Safety check-in stopped",
        val profileUpdated: String = "You updated your profile",
        val wallpaperChanged: String = "You changed the chat wallpaper",
        val reaction: String = "Reacted to a message",
        val keyRotation: String = "Key Rotation",
        val poll: String = "Poll",
        /**
         * __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Two lines, because the switch
         * has two directions and a one-way label would read as a lie when someone
         * lifts it.
         */
        val offlineOnlyOn: String = "This conversation is now offline only",
        val offlineOnlyOff: String = "This conversation can use the internet again",
        /** __ADV_CHAT_PRIVACY_2026_11_26__ One line each way, no name (the row says who). */
        val advPrivacyOn: String = "Advanced chat privacy turned on",
        val advPrivacyOff: String = "Advanced chat privacy turned off",
        /** "You", for naming this device's owner in a poll's voter list. */
        val you: String = "You",
        /**
         * __BLOCKED_NOTICE_2026_09_18__ Shown to the person who was BLOCKED, in
         * their own thread and their own language. It names the CONSEQUENCE
         * ("nothing you send arrives") rather than the other person's act,
         * because the consequence is the useful part and it is the only part
         * this device can state as fact: a notice proves that one device refused
         * one message, nothing more.
         */
        val blockedByContact: String = "You can no longer send messages to this contact",
        val mediaImage: String = "Photo",
        val mediaVideo: String = "Video",
        val mediaAudio: String = "Voice message",
        val mediaDocument: String = "Document",
        val mediaFile: String = "File",
        /** A call SIGNAL surfaced on a row — silent in the catalog, shown by the list. */
        val connecting: String = "Connecting…",
        /** Ciphertext that reached a preview surface ([listPreview]). */
        val encryptedMessage: String = "Encrypted message",
        /** Any other machine payload we cannot name ([listPreview]). */
        val opaqueMessage: String = "Message"
    )

    /**
     * [translated] unless it still carries a format specifier, in which case
     * [fallback] (the English default).
     *
     * A label in this catalog takes **no arguments**. A translation that contains
     * `%@` (the iOS spelling), `%s` or `%1$s` is therefore not a translation of
     * this string at all — it is a line lifted from a different, parameterised
     * key, and `getString` with no varargs prints the specifier verbatim at the
     * user. This is not hypothetical: see the Hebrew `notification_reaction`
     * above, which the resource merge already warns about
     * (*"Multiple substitutions specified in non-positional format"*).
     *
     * The same reasoning as [localizeCallKey]'s: a value that does not have the
     * shape this code expects must not be used, and the honest fallback is the
     * English default rather than a string with `%@` in it. A wrong-language
     * label is a bug; a label reading `%@` is a broken app.
     */
    internal fun noFormatSpecifiers(translated: String, fallback: String): String =
        if (translated.contains("%@") || Regex("%\\d*\\$?[sd@]").containsMatchIn(translated)) {
            fallback
        } else {
            translated
        }

    /**
     * Localized labels from the app's resources.
     *
     * Every field is now resource-backed. `callDefault`, `reaction` and
     * `mediaFile` used to have no `strings.xml` entry and so stayed on the
     * English default in all 34 locales even when the caller passed a Context —
     * the surface pass added `call_generic`, `reaction_summary` and `media_file`
     * (`res/values/strings_parity_surface.xml`) and wired them here. A missing
     * resource is a compile error, so this list cannot silently regress.
     */
    fun labels(context: Context): Labels = Labels(
        callOutgoing = context.getString(R.string.call_summary_outgoing),
        callIncoming = context.getString(R.string.call_summary_incoming),
        callMissed = context.getString(R.string.call_missed),
        callNoAnswer = context.getString(R.string.call_no_answer),
        callDeclined = context.getString(R.string.call_declined),
        locationLive = context.getString(R.string.location_sharing_live),
        locationShared = context.getString(R.string.location_shared_location),
        scheduledCall = context.getString(R.string.event_call_scheduled),
        checkIn = context.getString(R.string.checkin_title),
        checkInTypes = CheckInMessageType.entries.associateWith {
            context.getString(it.titleRes)
        },
        aliveCheckInStarted = context.getString(R.string.alive_summary_started),
        aliveCheckInConfirmed = context.getString(R.string.alive_summary_confirmed),
        aliveCheckInStopped = context.getString(R.string.alive_summary_stopped),
        profileUpdated = context.getString(R.string.system_profile_updated),
        wallpaperChanged = context.getString(R.string.system_wallpaper_changed),
        keyRotation = context.getString(R.string.key_rotation_title),
        poll = context.getString(R.string.poll_title),
        // __OFFLINE_ONLY_CONVERSATION_2026_10_23__
        offlineOnlyOn = context.getString(R.string.offline_only_summary_on),
        offlineOnlyOff = context.getString(R.string.offline_only_summary_off),
        // __ADV_CHAT_PRIVACY_2026_11_26__
        advPrivacyOn = context.getString(R.string.adv_privacy_summary_on),
        advPrivacyOff = context.getString(R.string.adv_privacy_summary_off),
        you = context.getString(R.string.you),
        blockedByContact = context.getString(R.string.chat_blocked_by_contact),
        mediaImage = context.getString(R.string.chat_photo),
        mediaVideo = context.getString(R.string.chat_video),
        mediaAudio = context.getString(R.string.group_chat_voice_message),
        mediaDocument = context.getString(R.string.chat_document_title),
        callDefault = context.getString(R.string.call_generic),
        // `notification_reaction` is the right key in 33 locales, and WRONG in
        // Hebrew: `values-iw/strings_parity_chat.xml:7` holds
        // `%@ הגיב/ה %@ להודעתך` — a two-argument iOS format string lifted from a
        // different key, for a label that takes no arguments. `getString` with no
        // varargs hands the `%@` straight through, so wiring this key (nothing
        // consumed it before) would have printed `🔥 %@ הגיב/ה %@ להודעתך` on
        // every Hebrew reaction row and banner. See [noFormatSpecifiers].
        reaction = noFormatSpecifiers(
            context.getString(R.string.notification_reaction),
            Labels().reaction
        ),
        mediaFile = context.getString(R.string.media_file),
        connecting = context.getString(R.string.call_connecting),
        encryptedMessage = context.getString(R.string.preview_encrypted_message),
        opaqueMessage = context.getString(R.string.preview_opaque_message)
    )
}
