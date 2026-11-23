package com.oshi.wearbridge

/**
 * __WEAR_BRIDGE_2026_11_17__ The default-avatar palette, as pure data, for the
 * watch.
 *
 * The SAME spec as the phone's `ui/theme/AvatarIdentity.kt` (v2: sixteen
 * two-tone gradients + ink, slot = first byte of SHA-256(UTF-8(normalised
 * key))) and the shared `docs/avatar-palette.json`. Kept free of Compose
 * types so it can live in this shared source set: the watch draws with it,
 * `WearAvatarTest` (:wear) re-reads the JSON spec and its vectors, and
 * `WearAvatarParityTest` (:app) fails if these literals and the phone's
 * `AvatarIdentity.entries` ever disagree — a contact cannot change colour
 * between the wrist and the phone.
 *
 * The phone sends the watch the already-canonical key ([WearConversation.avatarKey]:
 * a contact's padded base64 public key, a group's lowercased id), so this only
 * has to apply the spec's `normalise` step, exactly like the phone does.
 */
object WearAvatar {

    /** ARGB literals, spec order. Do not reorder: the index IS the identity. */
    data class Entry(val start: Long, val end: Long, val ink: Long)

    val entries: List<Entry> = listOf(
        Entry(0xFFCB6C30, 0xFFB63924, 0xFFFFFFFF),  //  0 coral
        Entry(0xFFFDD7AC, 0xFFF6A97A, 0xFF743B1F),  //  1 apricot
        Entry(0xFFF8E9A3, 0xFFF1BF5B, 0xFF6B4716),  //  2 honey
        Entry(0xFFD8EFB4, 0xFF9AD387, 0xFF31552A),  //  3 pistachio
        Entry(0xFF43963C, 0xFF027048, 0xFFFFFFFF),  //  4 fern
        Entry(0xFFC2F5E0, 0xFF7ED8C6, 0xFF07564F),  //  5 mint
        Entry(0xFF179696, 0xFF066674, 0xFFFFFFFF),  //  6 teal
        Entry(0xFFC2ECFE, 0xFF87C6F2, 0xFF264B7A),  //  7 sky
        Entry(0xFF3E8CC9, 0xFF2953A5, 0xFFFFFFFF),  //  8 ocean
        Entry(0xFF7B7FC9, 0xFF5E45AE, 0xFFFFFFFF),  //  9 periwinkle
        Entry(0xFFE3DCFF, 0xFFC9AFF4, 0xFF553781),  // 10 lavender
        Entry(0xFFB76BC3, 0xFF7D32A3, 0xFFFFFFFF),  // 11 grape
        Entry(0xFFC96598, 0xFF9E3082, 0xFFFFFFFF),  // 12 orchid
        Entry(0xFFFEDAE1, 0xFFF0A9C4, 0xFF7E314D),  // 13 blush
        Entry(0xFFDB6371, 0xFFA92552, 0xFFFFFFFF),  // 14 raspberry
        Entry(0xFF768997, 0xFF47536A, 0xFFFFFFFF),  // 15 slate
    )

    /**
     * Spec `nameDark`: a group sender's name colour on a dark bubble, the SAME
     * slot as their avatar (the watch is always dark). Phone: `AvatarNameColors.dark`.
     */
    val nameDark: List<Long> = listOf(
        0xFFE87750, 0xFFCB884F, 0xFFB5932E, 0xFF79A35B, 0xFF43AB60, 0xFF32A98F, 0xFF13A7B0, 0xFF45A0CF,
        0xFF5699EC, 0xFF948AF1, 0xFFA48AD4, 0xFFC27CD9, 0xFFDC74B3, 0xFFD27E9B, 0xFFE97188, 0xFF8499B1,
    )

    /** Spec `highlight`, fractions of the diameter. */
    const val HIGHLIGHT_X = 0.30f
    const val HIGHLIGHT_Y = 0.20f
    const val HIGHLIGHT_RADIUS = 0.80f
    const val HIGHLIGHT_ALPHA = 0.24f

    private val UUID_RE = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

    /** Spec `selection.normalise`: a UUID is lowercased, anything else verbatim. */
    fun normalizedKey(key: String): String {
        val trimmed = key.trim()
        return if (UUID_RE.matches(trimmed)) trimmed.lowercase(java.util.Locale.ROOT) else key
    }

    /** First byte of SHA-256(UTF-8(normalised key)), 0..255. */
    fun digestByte(key: String): Int =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(normalizedKey(key).toByteArray(Charsets.UTF_8))[0].toInt() and 0xff

    fun slot(key: String): Int = digestByte(key) % entries.size

    fun entry(key: String): Entry = entries[slot(key)]

    /**
     * The phone's `avatarInitials` rule (and iOS `AvatarIdentity.initials`):
     * first letter of the first two words, else the first [count] letters,
     * else the key. Identical for every name made of words; two deliberate
     * refinements for the small disc on a watch: it counts CODE POINTS, so an
     * emoji is never cut in half into a broken glyph, and a word that starts
     * with no letter or digit ("Famille 🏡") is not a word for this purpose.
     */
    fun initials(name: String?, key: String, count: Int = 2): String {
        fun String.firstCodePoints(n: Int): String {
            if (isEmpty()) return ""
            val end = offsetByCodePoints(0, minOf(n, codePointCount(0, length)))
            return substring(0, end)
        }
        fun String.startsWithLetterOrDigit() = isNotEmpty() && Character.isLetterOrDigit(codePointAt(0))
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isNotEmpty()) {
            val words = trimmed.split(" ").filter { it.isNotBlank() }
            val parts = words.filter { it.startsWithLetterOrDigit() }.ifEmpty { words }
            if (count >= 2 && parts.size >= 2) {
                return (parts[0].firstCodePoints(1) + parts[1].firstCodePoints(1)).uppercase()
            }
            return parts.joinToString(" ").firstCodePoints(count).uppercase()
        }
        return key.firstCodePoints(count).uppercase()
    }
}
