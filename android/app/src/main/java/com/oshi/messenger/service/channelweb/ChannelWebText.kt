package com.oshi.messenger.service.channelweb

import com.oshi.messenger.ui.components.BodyMarkup

/**
 * __CHANNEL_WEB_2026_11_26__ App message text → the web copy's text
 * (PUBLIC_CHANNEL_WEB_SPEC.md §5). Pure, JVM-tested.
 *
 * The app has two markup layers ([BodyMarkup]): `**bold**` / `__underline__`, and the older
 * single-character iOS markup `*bold*` `_italic_` `~strike~` `` `code` ``. The web has only
 * `**bold**` and `_italic_` / `*italic*`. A literal copy would be wrong in both directions —
 * the app's `*x*` is BOLD but the web's is italic, and `__x__` means nothing on the web — so
 * the text is rendered with the app's own parser and the styles are written back in the web
 * syntax:
 *
 *  - bold (either layer) → `**x**`
 *  - italic → `_x_`, or `*x*` when `_` would sit inside a word; dropped when neither marker
 *    can open there (mid-word)
 *  - underline, strikethrough, code → plain text (no web equivalent; same rule as iOS
 *    `ChannelWeb.swift`, so a post reads the same on the web whoever published it)
 *
 * Whitespace at the edge of a styled run is moved outside the markers (the web requires them
 * to hug text). Links are left as typed; the server autolinks them.
 */
object ChannelWebText {
    /** Spec §6: text limit in code points. */
    const val MAX_CODE_POINTS = 4096

    fun toWeb(raw: String): String {
        if (raw.isEmpty()) return raw
        val r = BodyMarkup.render(raw)
        val t = r.text
        if (r.spans.isEmpty()) return t
        val bold = BooleanArray(t.length)
        val ital = BooleanArray(t.length)
        for (s in r.spans) {
            val a = s.start.coerceIn(0, t.length)
            val b = s.end.coerceIn(0, t.length)
            when (s.kind) {
                BodyMarkup.Kind.BOLD -> for (i in a until b) bold[i] = true
                BodyMarkup.Kind.ITALIC -> for (i in a until b) ital[i] = true
                else -> Unit
            }
        }
        val out = StringBuilder(t.length + 16)
        var i = 0
        while (i < t.length) {
            var j = i
            while (j < t.length && bold[j] == bold[i] && t[j] != '\n') j++
            if (j == i) { out.append(t[i]); i++; continue }  // a newline
            if (bold[i]) emitBold(t, i, j, ital, out) else emitItalics(t, i, j, ital, out, insideBold = false)
            i = j
        }
        return out.toString()
    }

    private fun emitBold(t: String, from: Int, to: Int, ital: BooleanArray, out: StringBuilder) {
        var a = from
        var b = to
        while (a < b && t[a].isWhitespace()) out.append(t[a++])
        val trailing = StringBuilder()
        while (b > a && t[b - 1].isWhitespace()) b--
        for (k in b until to) trailing.append(t[k])
        if (a < b) {
            out.append("**")
            emitItalics(t, a, b, ital, out, insideBold = true)
            out.append("**")
        }
        out.append(trailing)
    }

    /** [from, to) is on one line and has one bold value; writes its italic sub-runs. */
    private fun emitItalics(t: String, from: Int, to: Int, ital: BooleanArray, out: StringBuilder, insideBold: Boolean) {
        var i = from
        while (i < to) {
            var j = i
            while (j < to && ital[j] == ital[i]) j++
            if (!ital[i]) {
                out.append(t, i, j)
            } else {
                var a = i
                var b = j
                while (a < b && t[a].isWhitespace()) out.append(t[a++])
                while (b > a && t[b - 1].isWhitespace()) b--
                if (a < b) {
                    // The character the web parser sees on each side of the marker.
                    val before: Char? = if (a == from && insideBold) null else out.lastOrNull()
                    val after: Char? = if (b == to && insideBold) null else t.getOrNull(b)
                    val inner = t.substring(a, b)
                    when {
                        canOpen(before, '_') && canClose(after, '_') -> out.append('_').append(inner).append('_')
                        canOpen(before, '*') && canClose(after, '*') && !inner.contains('*') ->
                            out.append('*').append(inner).append('*')
                        else -> out.append(inner)
                    }
                }
                out.append(t, b, j)
            }
            i = j
        }
    }

    // Web rule: `_x_` needs a non-(letter|digit|_) on both sides; `*x*` the same with `*`.
    private fun canOpen(c: Char?, marker: Char): Boolean = c == null || !(c.isLetterOrDigit() || c == marker)
    private fun canClose(c: Char?, marker: Char): Boolean = c == null || !(c.isLetterOrDigit() || c == marker)

    /** Mirror of the server's `cleanPostText` (channel_posts.js), for local comparisons. */
    fun clean(s: String): String = s.replace(Regex("\r\n?"), "\n")
        .replace(Regex("[\u0000-\u0009\u000B-\u001F\u007F  ‪-‮⁦-⁩]"), "")
        .replace(Regex("\n{4,}"), "\n\n\n")
        .trim()

    /** At most [max] code points; a cut text ends with `…`. Never splits a surrogate pair. */
    fun limit(s: String, max: Int = MAX_CODE_POINTS): String {
        val cps = s.codePointCount(0, s.length)
        if (cps <= max) return s
        val end = s.offsetByCodePoints(0, max - 1)
        return s.substring(0, end).trimEnd() + "…"
    }

    /** The exact text a post publishes: converted, cleaned like the server, within the limit. */
    fun forPublish(raw: String): String = limit(clean(toWeb(raw)))
}
