package com.oshi.messenger.ui.components

/**
 * Inline message markup, byte-compatible with iOS InlineMarkup.swift:
 *   *bold*  _italic_  ~strike~  `code`   (single-char markers, same line only).
 *
 * The boundary rules are what stop ordinary text from being mangled — a marker
 * only opens after a boundary (start/space/punctuation/symbol) and the run must
 * sit tight against its text — so `snake_case_name` is not italic and
 * `2 * 3 * 4 = 24` keeps every character. Ported 1:1 so Android styles EXACTLY
 * what iOS styles on the same message (no more, no less).
 */
object InlineMarkup {
    enum class Kind { BOLD, ITALIC, STRIKETHROUGH, CODE }
    data class Span(val kind: Kind, val location: Int, val length: Int)

    private val markers: Map<Char, Kind> = mapOf(
        '*' to Kind.BOLD,
        '_' to Kind.ITALIC,
        '~' to Kind.STRIKETHROUGH,
        '`' to Kind.CODE,
    )

    /** A letter/digit means the marker is part of a word; anything else is a boundary. */
    private fun isBoundary(c: Char?): Boolean {
        if (c == null) return true            // start or end of string
        if (c.isWhitespace()) return true
        return when (Character.getType(c)) {
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt() -> true
            else -> false
        }
    }

    /** Returns the text with markers removed and the runs to style (offsets into the returned text). */
    fun parse(input: String): Pair<String, List<Span>> {
        if (input.none { markers.containsKey(it) }) return input to emptyList()

        val out = StringBuilder()
        val spans = mutableListOf<Span>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            val kind = markers[c]
            if (kind == null) { out.append(c); i++; continue }

            // Opening marker must follow a boundary.
            val before = if (out.isNotEmpty()) out.last() else null
            if (!isBoundary(before)) { out.append(c); i++; continue }

            // Find a closing marker on the SAME line, with content between them,
            // followed by a boundary.
            var j = i + 1
            var closing = -1
            while (j < input.length) {
                val d = input[j]
                if (d == '\n' || d == '\r') break
                if (d == c) {
                    val after = if (j + 1 < input.length) input[j + 1] else null
                    if (j > i + 1 && isBoundary(after)) closing = j
                    break
                }
                j++
            }
            if (closing < 0) { out.append(c); i++; continue }

            val inner = input.substring(i + 1, closing)
            // The run must not start or end with whitespace (this is what keeps
            // `2 * 3 * 4` intact).
            if (inner.isEmpty() || inner.first().isWhitespace() || inner.last().isWhitespace()) {
                out.append(c); i++; continue
            }

            val location = out.length
            out.append(inner)
            spans.add(Span(kind, location, inner.length))
            i = closing + 1
        }
        return out.toString() to spans
    }

    /** True when the text has at least one run this parser would style. */
    fun hasFormatting(input: String): Boolean = parse(input).second.isNotEmpty()
}
