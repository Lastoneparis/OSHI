package com.oshi.messenger.data.model

/**
 * __O_BUBBLE_MASCOT_2026_11_16__ The O-bubble mascot: the OSHI ring + speech
 * tail with a face in the counter, in 20 looping animations (asset pack
 * `~/oshi_mascot/pack`, copied to `assets/OshiMascot/`).
 *
 * RENDERING ONLY. A reaction's WIRE value stays the standard emoji ("❤️",
 * "😂", ...): this table only decides that the app DRAWS the mascot for that
 * emoji (and for its aliases, e.g. 🤣 → laugh). Nothing here is ever sent, so
 * an older Android build, iOS or the desktop keeps receiving and showing a
 * normal emoji. Pure Kotlin, no Android types, so the mapping is unit-tested
 * on the JVM against the pack's own `manifest.json`.
 *
 * The table mirrors `assets/OshiMascot/manifest.json` (the pack's source of
 * truth); `OshiMascotTest` fails if the two drift.
 */
data class Mascot(
    /** File stem: `webp/<name>.webp`, `gif/<name>.gif`, `static/<name>_128.png`. */
    val name: String,
    /** The canonical emoji, exactly as sent on the wire when this mascot is picked. */
    val emoji: String,
    /** Other emoji drawn as this mascot (never sent by the picker). */
    val aliases: List<String>,
    /** English label from the manifest; also the GIF picker's search words. */
    val label: String,
) {
    val webpAsset: String get() = "${OshiMascot.ASSET_DIR}/webp/$name.webp"
    val gifAsset: String get() = "${OshiMascot.ASSET_DIR}/gif/$name.gif"
    val staticAsset: String get() = "${OshiMascot.ASSET_DIR}/static/${name}_128.png"

    /**
     * The emoji SENT when this mascot is picked — from [OshiMascot.WIRE], the
     * one table to edit to change what goes on the wire.
     */
    val wire: String get() = OshiMascot.WIRE.getValue(name)

    /** File name a sent mascot GIF carries (`oshi_love.gif`). */
    val gifFileName: String get() = "oshi_$name.gif"
}

object OshiMascot {
    const val ASSET_DIR = "OshiMascot"

    /** All 20, in the order the full reaction picker and the GIF picker show them. */
    val ALL: List<Mascot> = listOf(
        Mascot("love", "❤️", listOf("❤", "♥️", "💜", "🩷", "🧡", "💛", "💚", "💙", "🤍", "🖤"), "love"),
        Mascot("laugh", "😂", listOf("🤣"), "laughing to tears"),
        Mascot("cry", "😭", listOf("😢"), "loud crying"),
        Mascot("peek", "🫣", listOf("🙈"), "peeking"),
        Mascot("tearsmile", "🥲", emptyList(), "smiling with a tear"),
        Mascot("thumbs", "👍", emptyList(), "thumbs up"),
        Mascot("pray", "🙏", emptyList(), "thank you / please"),
        Mascot("fire", "🔥", emptyList(), "on fire"),
        Mascot("wow", "😮", listOf("😯", "😲", "😱"), "wow"),
        Mascot("angry", "😡", listOf("😠", "🤬"), "angry"),
        Mascot("party", "🎉", listOf("🥳", "🎊"), "party"),
        Mascot("wave", "👋", listOf("🙋"), "hi / wave"),
        Mascot("hearteyes", "😍", listOf("🥰"), "heart eyes"),
        Mascot("thinking", "🤔", listOf("🧐"), "thinking"),
        Mascot("clap", "👏", listOf("🙌"), "clapping"),
        Mascot("kiss", "😘", listOf("😗", "😚", "😙"), "blowing a kiss"),
        Mascot("sleep", "😴", listOf("💤", "😪"), "sleeping"),
        Mascot("cool", "😎", emptyList(), "cool"),
        Mascot("ok", "✅", listOf("✔️", "☑️", "👌"), "done / OK"),
        Mascot("hundred", "💯", emptyList(), "hundred"),
    )

    /**
     * WIRE TABLE — mascot → the emoji that is SENT when it is picked (quick bar
     * and full picker). This is the ONLY place the wire value is decided;
     * [OshiReaction.QUICK_ROW]/[OshiReaction.PICKER_ALL] read it.
     *
     * __REACTION_SAFE_MAPPING_2026_11_16__ (user decision, 1.6.98) "safe
     * mapping": iOS ≤ 1.0.46 (1.0.43 is the one on sale) has a CLOSED reaction
     * set ([ReactionEmojis.ALL]) and shows 👍 for any emoji it does not know.
     * So the cry mascot SENDS 😢 (known to every client) instead of its
     * manifest canonical 😭; this client still draws the cry mascot for 😢
     * (alias) and for a received 😭 (canonical). The other eight unknown to old
     * iOS (🫣 🥲 🙏 😡 👋 😍 😴 😎) keep their own emoji, are left out of the
     * quick bar ([QUICK_NAMES]) and carry the "new" hint in the full picker
     * ([OshiReaction.mayShowAsThumbsOnOldIos]).
     *
     * REVERT when iOS 1.0.47+ is widespread: `"cry" to "😭"` here, the old
     * [QUICK_NAMES], and the hint disappears by itself once
     * [OshiReaction.OLD_IOS_KNOWN] is widened. `OshiMascotTest` pins all three.
     */
    val WIRE: Map<String, String> = linkedMapOf(
        "love" to "\u2764\uFE0F",
        "laugh" to "😂",
        "cry" to "😢",          // safe mapping: 😭 is unknown to iOS 1.0.43 (→ 👍); 😢 is known
        "peek" to "🫣",         // iOS 1.0.43: unknown → 👍
        "tearsmile" to "🥲",    // iOS 1.0.43: unknown → 👍
        "thumbs" to "👍",
        "pray" to "🙏",         // iOS 1.0.43: unknown → 👍
        "fire" to "🔥",
        "wow" to "😮",
        "angry" to "😡",        // iOS 1.0.43: unknown → 👍
        "party" to "🎉",
        "wave" to "👋",         // iOS 1.0.43: unknown → 👍
        "hearteyes" to "😍",    // iOS 1.0.43: unknown → 👍
        "thinking" to "🤔",
        "clap" to "👏",
        "kiss" to "😘",
        "sleep" to "😴",        // iOS 1.0.43: unknown → 👍
        "cool" to "😎",         // iOS 1.0.43: unknown → 👍
        "ok" to "\u2705",
        "hundred" to "💯",
    )

    /**
     * The six mascots of the long-press quick bar (after the OSHI "O"):
     * ❤️ 😂 😢(cry) 👍 😮 🔥 — every one's wire value is known to every client,
     * iOS 1.0.43 included (__REACTION_SAFE_MAPPING_2026_11_16__). Was
     * love, laugh, cry, peek, tearsmile, thumbs (🫣 and 🥲 show as 👍 on old iOS).
     */
    val QUICK_NAMES: List<String> = listOf("love", "laugh", "cry", "thumbs", "wow", "fire")

    private val byName: Map<String, Mascot> = ALL.associateBy { it.name }

    /** normalised emoji (canonical AND aliases) → mascot. */
    private val byKey: Map<String, Mascot> = buildMap {
        // Aliases first, canonicals last: a canonical always wins a clash.
        ALL.forEach { m -> m.aliases.forEach { put(normalize(it), m) } }
        ALL.forEach { m -> put(normalize(m.emoji), m) }
    }

    fun named(name: String): Mascot? = byName[name]

    /**
     * The mascot drawn for a received/sent reaction [code], or null (system
     * emoji). Variation selector U+FE0F and skin-tone modifiers U+1F3FB..1F3FF
     * are ignored, so "❤" / "❤️" and "👍🏽" / "👍" draw the same mascot. Any
     * other sequence (ZWJ "❤️‍🔥", two emoji, the OSHI marks "⭕" / "⭕😊")
     * matches nothing.
     */
    fun forEmoji(code: String?): Mascot? {
        if (code.isNullOrEmpty()) return null
        return byKey[normalize(code)]
    }

    /** Strip U+FE0F and the five Fitzpatrick modifiers. */
    fun normalize(code: String): String {
        val sb = StringBuilder(code.length)
        var i = 0
        while (i < code.length) {
            val cp = code.codePointAt(i)
            if (cp != 0xFE0F && cp !in 0x1F3FB..0x1F3FF) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /**
     * What the reaction UI draws for [code]: the mascot when the setting is on
     * and the emoji has one, else null — the caller then draws the emoji. The
     * setting is a parameter so the fallback rule is testable without a device.
     */
    fun rendered(code: String?, animatedReactionsEnabled: Boolean): Mascot? =
        if (animatedReactionsEnabled) forEmoji(code) else null

    /** Search for the GIF picker's OSHI section: name, label, emoji or alias. */
    fun search(query: String): List<Mascot> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return ALL
        val asEmoji = forEmoji(query.trim())
        // "oshi" / "mascot" (or a prefix of them, 2+ letters) lists the whole set.
        val wholeSet = listOf("oshi", "mascot").any { it.startsWith(q) && q.length >= 2 || q.contains(it) }
        if (wholeSet) return ALL
        return ALL.filter { m ->
            m == asEmoji || m.name.contains(q) || m.label.lowercase().contains(q)
        }
    }
}
