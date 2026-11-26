package com.oshi.messenger.ui.components

import com.oshi.messenger.util.MessageFormatting

/**
 * __TEXT_FORMATTING_2026_11_24__ Everything a message bubble styles, in one pass over
 * the raw text:
 *
 *  1. [MessageFormatting] — `**bold**` and `__underline__` (the composer's own markers);
 *  2. [InlineMarkup] — the older single-character iOS markup (`*b*` `_i_` `~s~` `` `c` ``),
 *     run on what step 1 left, and never inside a URL, so `https://x.com/a__b__c` stays
 *     exactly the link it is (before this, its `_b_` turned italic and the link broke).
 *
 * [text] is what the reader sees; every range is a half-open UTF-16 range into it.
 * Pure Kotlin so the JVM tests can pin it.
 */
object BodyMarkup {
    enum class Kind { BOLD, UNDERLINE, ITALIC, STRIKETHROUGH, CODE }
    data class Span(val kind: Kind, val start: Int, val end: Int)
    data class Result(val text: String, val spans: List<Span>)

    fun render(raw: String): Result {
        val formatted = MessageFormatting.parse(raw)
        val r1 = formatted.text
        val urls = MessageFormatting.urlRanges(r1)

        // InlineMarkup on each stretch outside a URL. `removedBefore[p]` = how many
        // marker characters InlineMarkup dropped before index p of r1.
        val out = StringBuilder(r1.length)
        val spans = ArrayList<Span>()
        val removedBefore = IntArray(r1.length + 1)
        var removed = 0
        var at = 0

        fun plain(from: Int, to: Int) {
            if (from >= to) return
            val segment = r1.substring(from, to)
            val (clean, inline) = InlineMarkup.parse(segment)
            if (inline.isEmpty()) {
                for (p in from until to) removedBefore[p] = removed
                out.append(segment)
                return
            }
            // InlineMarkup runs never nest: run k dropped one char before it and one
            // after it, so its opener sat at location + 2k in the segment.
            val drop = BooleanArray(segment.length)
            inline.forEachIndexed { k, s ->
                drop[s.location + 2 * k] = true
                drop[s.location + s.length + 2 * k + 1] = true
            }
            for (p in segment.indices) {
                removedBefore[from + p] = removed
                if (drop[p]) removed++
            }
            val base = out.length
            out.append(clean)
            for (s in inline) {
                val kind = when (s.kind) {
                    InlineMarkup.Kind.BOLD -> Kind.BOLD
                    InlineMarkup.Kind.ITALIC -> Kind.ITALIC
                    InlineMarkup.Kind.STRIKETHROUGH -> Kind.STRIKETHROUGH
                    InlineMarkup.Kind.CODE -> Kind.CODE
                }
                spans += Span(kind, base + s.location, base + s.location + s.length)
            }
        }

        for (u in urls) {
            plain(at, u.start)
            for (p in u.start until u.end) removedBefore[p] = removed
            out.append(r1, u.start, u.end)
            at = u.end
        }
        plain(at, r1.length)
        removedBefore[r1.length] = removed

        fun map(p: Int) = p - removedBefore[p.coerceIn(0, r1.length)]
        for (r in formatted.bold) spans += Span(Kind.BOLD, map(r.start), map(r.end))
        for (r in formatted.underline) spans += Span(Kind.UNDERLINE, map(r.start), map(r.end))

        return Result(out.toString(), spans.filter { it.start < it.end }.sortedBy { it.start })
    }
}
