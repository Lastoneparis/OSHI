package com.oshi.messenger.util

/**
 * __TEXT_FORMATTING_2026_11_24__ Bold (`**text**`) and underline (`__text__`) in chat
 * messages, 1:1 and group. Shared spec with iOS (`MessageFormatting.swift`): the markers
 * travel INSIDE the message text, so there is no envelope change and an old client simply
 * shows the raw markers.
 *
 * Rules (the golden vectors in `MessageFormattingTest` are the contract, identical on iOS):
 *  1. A span opens at `**` / `__` and closes at the NEXT identical marker on the same line.
 *  2. Its content is non-empty and neither starts nor ends with a space.
 *  3. Content never crosses a line break.
 *  4. An opener with no valid closer is literal text (the scan moves on ONE char).
 *  5. A marker that touches a URL (`https://…`, `http://…`, `www.…`) is not a marker.
 *  6. Content is parsed again, so `**__x__**` and `__**x**__` are bold AND underlined.
 *
 * Ranges are UTF-16 offsets into [Result.text], half-open, as on iOS (NSString).
 *
 * The single-character iOS markup (`*bold*`, `_italic_`, …) is a different, older parser
 * ([com.oshi.messenger.ui.components.InlineMarkup]); bubbles run this one first and that
 * one on what is left (see `BodyMarkup`).
 */
object MessageFormatting {
    const val BOLD = "**"
    const val UNDERLINE = "__"

    enum class Style { BOLD, UNDERLINE }

    /** Half-open [start, end) in the rendered text. */
    data class Range(val start: Int, val end: Int)

    data class Result(val text: String, val bold: List<Range>, val underline: List<Range>)

    /**
     * URL tokens whose characters can never be markers. Trailing `*` / `_` are not part of
     * the URL, so `**https://x.com**` still makes the link bold.
     */
    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")

    fun urlRanges(text: String): List<Range> {
        if (!text.contains("://") && !text.contains("www.", ignoreCase = true)) return emptyList()
        return URL.findAll(text).mapNotNull { m ->
            var end = m.range.last + 1
            while (end > m.range.first && (text[end - 1] == '*' || text[end - 1] == '_')) end--
            if (end > m.range.first) Range(m.range.first, end) else null
        }.toList()
    }

    /** True when [text] contains at least one span that would render. */
    fun hasFormatting(text: String): Boolean {
        if (!text.contains(BOLD) && !text.contains(UNDERLINE)) return false
        val r = parse(text)
        return r.bold.isNotEmpty() || r.underline.isNotEmpty()
    }

    /** [text] as a plain-text surface shows it: the markers that formed a span removed. */
    fun strip(text: String): String =
        if (!text.contains(BOLD) && !text.contains(UNDERLINE)) text else parse(text).text

    fun parse(text: String): Result {
        if (!text.contains(BOLD) && !text.contains(UNDERLINE)) return Result(text, emptyList(), emptyList())
        val inUrl = BooleanArray(text.length)
        for (r in urlRanges(text)) for (i in r.start until r.end) inUrl[i] = true
        val out = StringBuilder(text.length)
        val bold = ArrayList<Range>()
        val underline = ArrayList<Range>()
        render(text, 0, text.length, inUrl, out, bold, underline)
        return Result(
            out.toString(),
            bold.sortedBy { it.start },
            underline.sortedBy { it.start },
        )
    }

    private fun markerAt(text: String, i: Int, end: Int, inUrl: BooleanArray): String? {
        if (i + 1 >= end) return null
        val a = text[i]
        if ((a != '*' && a != '_') || text[i + 1] != a) return null
        if (inUrl[i] || inUrl[i + 1]) return null
        return if (a == '*') BOLD else UNDERLINE
    }

    /** Index of the next [marker] at or after [from] (before [end]) on the same line, or -1. */
    private fun closerFrom(text: String, from: Int, end: Int, marker: String, inUrl: BooleanArray): Int {
        var j = from
        while (j + 1 < end) {
            val c = text[j]
            if (c == '\n' || c == '\r') return -1
            if (markerAt(text, j, end, inUrl) == marker) return j
            j++
        }
        return -1
    }

    private fun render(
        text: String,
        start: Int,
        end: Int,
        inUrl: BooleanArray,
        out: StringBuilder,
        bold: MutableList<Range>,
        underline: MutableList<Range>,
    ) {
        var i = start
        while (i < end) {
            val marker = markerAt(text, i, end, inUrl)
            if (marker != null) {
                val close = closerFrom(text, i + 2, end, marker, inUrl)
                if (close > i + 2) {
                    val first = text[i + 2]
                    val last = text[close - 1]
                    if (first != ' ' && last != ' ') {
                        val from = out.length
                        render(text, i + 2, close, inUrl, out, bold, underline)
                        val r = Range(from, out.length)
                        if (marker == BOLD) bold += r else underline += r
                        i = close + 2
                        continue
                    }
                }
            }
            out.append(text[i])
            i++
        }
    }

    /** A composer edit: the new text and the selection to put back. */
    data class Edit(val text: String, val selectionStart: Int, val selectionEnd: Int)

    /**
     * The composer's Bold / Underline action.
     *
     *  * No selection: insert the empty pair and put the cursor between (`**|**`).
     *  * A selection already wrapped in [marker] (just outside or just inside it): unwrap.
     *  * Otherwise wrap it, keeping the selection on the inner text. Spaces at the edges of
     *    the selection stay outside the markers (rule 2 would make `** a**` literal), and a
     *    selection over several lines is wrapped line by line (rule 3).
     */
    fun toggle(text: String, selStart: Int, selEnd: Int, marker: String): Edit {
        val s = minOf(selStart, selEnd).coerceIn(0, text.length)
        val e = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val m = marker.length
        if (s == e) {
            return Edit(text.substring(0, s) + marker + marker + text.substring(s), s + m, s + m)
        }
        // Unwrap: markers just outside the selection…
        if (s >= m && e + m <= text.length &&
            text.regionMatches(s - m, marker, 0, m) && text.regionMatches(e, marker, 0, m)
        ) {
            return Edit(text.substring(0, s - m) + text.substring(s, e) + text.substring(e + m), s - m, e - m)
        }
        // …or the selection includes them.
        if (e - s >= 2 * m + 1 &&
            text.regionMatches(s, marker, 0, m) && text.regionMatches(e - m, marker, 0, m)
        ) {
            return Edit(text.substring(0, s) + text.substring(s + m, e - m) + text.substring(e), s, e - 2 * m)
        }
        val selected = text.substring(s, e)
        if (selected.isBlank()) {
            return Edit(text.substring(0, e) + marker + marker + text.substring(e), e + m, e + m)
        }
        val lines = selected.split('\n')
        val wrapped = StringBuilder()
        for ((index, line) in lines.withIndex()) {
            if (index > 0) wrapped.append('\n')
            val body = line.trim(' ', '\t', '\r')
            if (body.isEmpty()) { wrapped.append(line); continue }
            val lead = line.indexOf(body)
            wrapped.append(line, 0, lead)
            wrapped.append(marker).append(body).append(marker)
            wrapped.append(line, lead + body.length, line.length)
        }
        val newText = text.substring(0, s) + wrapped + text.substring(e)
        if (lines.size == 1) {
            val lead = selected.indexOf(selected.trim(' ', '\t', '\r'))
            val inner = selected.trim(' ', '\t', '\r').length
            return Edit(newText, s + lead + m, s + lead + m + inner)
        }
        return Edit(newText, s, s + wrapped.length)
    }
}
