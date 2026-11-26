package com.oshi.messenger.service.channelweb

import com.oshi.messenger.data.model.GroupSettings

/**
 * __CHANNEL_WEB_2026_11_26__ Links and embed code for public channel posts
 * (ServerVPS/v2/PUBLIC_CHANNEL_WEB_SPEC.md §4, §8, §9.7-8). Pure, JVM-tested.
 */
object ChannelWebLinks {
    const val BASE = "https://oshi-messenger.com"
    const val WIDGET_SRC = "$BASE/js/oshi-widget.js?1"

    private val N_RE = Regex("^[1-9][0-9]{0,9}$")

    /** `https://oshi-messenger.com/c/<handle>` */
    fun channelUrl(handle: String): String = "$BASE/c/$handle"

    /** `https://oshi-messenger.com/c/<handle>/<n>` (Telegram `t.me/<channel>/<n>`). */
    fun postUrl(handle: String, n: Int): String = "$BASE/c/$handle/$n"

    /** What the web page's "Open in OSHI" button opens (spec §7.2). */
    fun appLink(handle: String, n: Int?): String =
        if (n == null) "oshi://channel?h=$handle" else "oshi://channel?h=$handle&n=$n"

    /**
     * The §8 one-liner for one post. `data-dark="1"` forces the dark card; without it the
     * card follows the reader's system theme. Handle and n are validated by the caller's
     * types ([GroupSettings.normalizeHandle] is `[a-z0-9_]`), so nothing here needs escaping.
     */
    fun embedSnippet(handle: String, n: Int, dark: Boolean): String = buildString {
        append("<script async src=\"").append(WIDGET_SRC).append("\" ")
        append("data-oshi-post=\"").append(handle).append('/').append(n).append("\" ")
        append("data-width=\"100%\"")
        if (dark) append(" data-dark=\"1\"")
        append("></script>")
    }

    /** A post number as the server writes it (`^[1-9][0-9]{0,9}$`, ≤ Int.MAX), or null. */
    fun parseN(raw: String?): Int? {
        if (raw == null || !N_RE.matches(raw)) return null
        return raw.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    /** Valid lower-cased handle or null (same rule as the group link registry). */
    fun parseHandle(raw: String?): String? = GroupSettings.normalizeHandle(raw)
}
