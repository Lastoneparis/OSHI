package com.oshi.messenger.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Message Reaction - Compatible with iOS MessageReactionManager
 */
@Entity(tableName = "message_reactions")
data class MessageReaction(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val messageId: String,
    val senderPublicKey: String,
    val emoji: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageReaction
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Available reaction emojis — matches iOS ReactionEmoji set.
 * Wire format is the raw emoji character (UTF-8), per iOS MessageReactionManager.swift.
 */
object ReactionEmojis {
    // iOS 12-emoji set (canonical)
    const val CHECK = "✅"
    const val CROSS = "❌"
    const val SAD = "\uD83D\uDE22"          // 😢
    const val MIDDLE_FINGER = "\uD83D\uDD95"  // 🖕
    const val KISS = "\uD83D\uDE18"          // 😘
    const val THUMBS_UP = "\uD83D\uDC4D"      // 👍
    const val THUMBS_DOWN = "\uD83D\uDC4E"    // 👎
    const val HUNDRED = "\uD83D\uDCAF"        // 💯
    const val CHRISTIAN = "✝️"
    const val MUSLIM = "☪️"
    const val JEWISH = "✡️"
    const val BUDDHIST = "☸️"

    // F-7: the eight iOS added on 2026-08-08 (MessageReactionManager.swift:32-39).
    // Android's picker was stuck on the pre-2026-08-08 set, so an Android user
    // could SEE a heart or a laugh from an iPhone (unknown codes render raw) and
    // had no way to send one back.
    const val LAUGH = "\uD83D\uDE02"         // laughing
    const val HEART = "\u2764\uFE0F"          // red heart
    const val FIRE = "\uD83D\uDD25"          // fire
    const val WOW = "\uD83D\uDE2E"           // open mouth
    const val CLAP = "\uD83D\uDC4F"          // clapping hands
    const val PARTY = "\uD83C\uDF89"         // party popper
    const val THINK = "\uD83E\uDD14"         // thinking face
    const val EYES = "\uD83D\uDC40"          // eyes

    // Backwards-compat aliases for prior Android code paths
    const val LIKE = THUMBS_UP
    // Was HUNDRED with the comment "no iOS heart". iOS has one now, so the alias
    // that says "love" sends a heart instead of silently sending 100.
    const val LOVE = HEART

    // Full set offered in the reaction picker. The order is iOS's `allCases`
    // (MessageReactionManager.swift:20-49): the eight new ones sit after HUNDRED
    // and before the four religious symbols.
    val ALL: List<String> = listOf(
        CHECK, CROSS, SAD, MIDDLE_FINGER, KISS, THUMBS_UP,
        THUMBS_DOWN, HUNDRED,
        LAUGH, HEART, FIRE, WOW, CLAP, PARTY, THINK, EYES,
        CHRISTIAN, MUSLIM, JEWISH, BUDDHIST
    )

    // Subset shown in the quick bar (matches iOS `quickReactions`)
    val QUICK: List<String> = listOf(CHECK, CROSS, SAD, THUMBS_UP, KISS, HUNDRED)

    /**
     * Safe render: if we ever receive an unknown reaction code from a future/mismatched client,
     * show the raw emoji character rather than crashing or dropping it silently.
     */
    fun displayEmoji(code: String): String = when {
        code.isEmpty() -> "\u25EF"          // ◯ neutral fallback
        ALL.contains(code) -> code
        else -> code                        // unknown but still a valid emoji/string — render raw
    }

    fun isKnown(code: String): Boolean = ALL.contains(code)
}

/**
 * __OSHI_SMILE_REACTION_2026_11_09__ The OSHI reaction: double-tap a bubble and
 * it is "reacted with the smiling O".
 *
 * WIRE VALUE — fixed, identical on iOS, Android and desktop: the two code
 * points U+2B55 (heavy large circle) U+1F60A (smiling face with smiling eyes),
 * UTF-8 `e2 ad 95 f0 9f 98 8a`. It travels through the EXISTING reaction
 * payload unchanged (`MessageReactionManager` for 1:1, `GroupManager` for
 * groups), so a client that predates it simply has a two-emoji string.
 *
 * New clients draw exactly this value as the OSHI mark ([isOshiSmile]); no
 * other value — not "⭕", not "😊", not the two separated — is ever drawn as it.
 *
 * Deliberately NOT added to [ReactionEmojis.ALL]/[ReactionEmojis.QUICK]: those
 * lists are pinned byte-for-byte to iOS's `ReactionEmoji` enum. The pickers
 * use [QUICK_ROW]/[PICKER_ALL], which lead with it.
 */
object OshiReaction {
    /** U+2B55 U+1F60A. Do not normalise, add a variation selector, or reorder. */
    const val OSHI_SMILE: String = "⭕😊"

    /** True only for the exact wire value. */
    fun isOshiSmile(code: String?): Boolean = code == OSHI_SMILE

    /**
     * __OSHI_O_REACTION_2026_11_15__ The plain OSHI "O" (the brand mark, no
     * face): the single code point U+2B55, UTF-8 `e2 ad 95`, NO variation
     * selector. Same wire value on iOS. Sent through the existing reaction
     * payload unchanged, so a client that predates it (iOS 1.0.43 on the
     * App Store, Android ≤ 1.6.96) shows a red "⭕" — still an O.
     *
     * Distinct from [OSHI_SMILE] ("⭕😊", shipped in 1.6.96 / iOS b203), which
     * keeps its own wire value and its own drawing: a reaction already on a
     * message must not change meaning when this client updates.
     */
    const val OSHI_O: String = "\u2B55"

    /** True only for the exact wire value "⭕" — not "⭕️" (FE0F), not "⭕😊". */
    fun isOshiO(code: String?): Boolean = code == OSHI_O

    /** Either OSHI mark: these are drawn, never shown as their emoji. */
    fun isOshiMark(code: String?): Boolean = isOshiO(code) || isOshiSmile(code)

    /**
     * The one-tap row in the long-press menu: the OSHI O first, then the six
     * O-bubble mascots ❤️ 😂 😢 👍 😮 🔥 (__O_BUBBLE_MASCOT_2026_11_16__,
     * __REACTION_SAFE_MAPPING_2026_11_16__: all known to iOS 1.0.43).
     * Each entry is the mascot's wire emoji from the single table
     * [OshiMascot.WIRE] — that is what is sent; the mascot is only how this
     * client draws it.
     *
     * Was `OSHI_O + ReactionEmojis.QUICK` (✅❌😢👍😘💯, iOS's quick bar); all
     * of those stay one tap further, in [PICKER_ALL].
     */
    val QUICK_ROW: List<String> =
        listOf(OSHI_O) + OshiMascot.QUICK_NAMES.map { OshiMascot.named(it)!!.wire }

    /**
     * The expanded picker: the OSHI O, the smiling OSHI O (still what a
     * double-tap sends), the 20 mascots, then every iOS reaction that has no
     * mascot (❌ 🖕 👎 👀 and the four religious symbols). An iOS emoji that
     * a mascot already draws (✅ 😘 💯 😂 ❤️ ... and 😢, drawn as the crying
     * mascot) is not offered twice.
     */
    val PICKER_ALL: List<String> = listOf(OSHI_O, OSHI_SMILE) +
        OshiMascot.ALL.map { it.wire } +
        ReactionEmojis.ALL.filter { OshiMascot.forEmoji(it) == null }

    /**
     * __REACTION_SAFE_MAPPING_2026_11_16__ The reactions iOS 1.0.43 (on sale)
     * draws as themselves: its closed `ReactionEmoji` set, i.e.
     * [ReactionEmojis.ALL]. Anything else it shows as 👍. Widen (or drop the
     * check) once iOS 1.0.47+ is widespread.
     */
    val OLD_IOS_KNOWN: Set<String> = ReactionEmojis.ALL.toSet()

    /**
     * True for a picker entry an iPhone on an older version may show as 👍 —
     * the full picker marks it "new". The OSHI marks are excluded: they have
     * their own fallback story (see [OSHI_O]).
     */
    fun mayShowAsThumbsOnOldIos(code: String): Boolean =
        !isOshiMark(code) && code !in OLD_IOS_KNOWN
}

/**
 * __OSHI_SMILE_REACTION_2026_11_09__ The toggle rule every reaction tap (chip,
 * picker, double-tap) follows, pure so it is testable: one reaction per person,
 * tapping the one you already have removes it, tapping another replaces it.
 *
 * Returns the reaction I hold AFTER the tap — `null` means "removed".
 */
object ReactionToggle {
    fun next(myCurrent: String?, tapped: String): String? =
        if (myCurrent == tapped) null else tapped
}

/**
 * Reaction summary for a message
 */
data class ReactionSummary(
    val messageId: String,
    val reactions: Map<String, List<String>>,  // emoji -> list of senderPublicKeys
    val myReaction: String? = null
) {
    val totalCount: Int get() = reactions.values.sumOf { it.size }

    fun getCount(emoji: String): Int = reactions[emoji]?.size ?: 0
}
