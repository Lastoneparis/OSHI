package com.oshi.messenger.service

import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.util.CashuTokens
import org.json.JSONObject

/**
 * __LONG_TEXT_SPLIT_2026_11_23__ Cuts a text that is over the byte budget of its route into parts that
 * each fit, instead of refusing it. Port of iOS `MessageTextSplitter.splitForSend`
 * (`OSHI/MessageSendPolicy.swift`), with the same rules:
 *
 *  - a Cashu token is never split (two halves are two strings no wallet takes): refused;
 *  - control payloads and group media JSON are never split (they are not prose);
 *  - a REPLY quotes its original on the first part only; the rest go out as plain text;
 *  - a FORWARD keeps its attribution on every part (dropping it would relabel someone else's words);
 *  - wrapped parts are MEASURED after re-wrapping (JSON escaping inflates the body) and the body budget
 *    shrinks until every part fits;
 *  - cuts prefer a paragraph, then a line, then a sentence, then a word; only an unbroken run is cut
 *    mid-word, and never inside a code point, a surrogate pair or a grapheme cluster
 *    (combining marks, ZWJ emoji, skin tones, flags, variation selectors, Hangul jamo);
 *  - more than [MAX_PARTS] parts is refused.
 *
 * DIVERGENCE from iOS, on purpose: iOS has no sentence class (paragraph → line → word); Android adds
 * sentence between line and word. The parts are the same size; only where a part ends differs.
 * Also refused here where iOS would cut the raw string: a reply/forward envelope that does not parse
 * (cutting its JSON sends two undecodable halves), and the legacy Android reply prefix.
 *
 * Pure: no I/O, no Android API beyond `org.json`.
 */
object MessageTextSplitter {

    /** iOS `MessageTextSplitter.maxParts`. */
    const val MAX_PARTS = 50

    /** A boundary is taken only if the part it ends fills at least this share of the budget. iOS `minimumFill`. */
    private const val MINIMUM_FILL = 0.5

    /** The pre-2026 Android reply envelope (`MessageActionsManager.REPLY_PREFIX`); nothing produces it now. */
    private const val LEGACY_REPLY_PREFIX = "↩️REPLY↩️"

    sealed class Outcome {
        /** Fits as-is: send it unchanged. */
        object Single : Outcome()
        /** Send these, in this order, as separate messages. */
        data class Parts(val parts: List<String>) : Outcome()
        /** Cannot be sent within the budget, even split. The caller must tell the user. */
        data class TooLong(val partsNeeded: Int) : Outcome()
    }

    fun splitForSend(content: String, maxUTF8Bytes: Int): Outcome {
        val limit = maxUTF8Bytes
        if (MessageSendPolicy.utf8Size(content) <= limit) return Outcome.Single

        if (content.contains("cashu", ignoreCase = true) && CashuTokens.containsToken(content)) {
            return Outcome.TooLong(partsNeeded = 2)
        }

        if (content.startsWith(OSHIControlPayload.REPLY)) {
            val json = parseEnvelope(content, OSHIControlPayload.REPLY) ?: return refuseEnvelope()
            val replyTo = json.optJSONObject("replyTo") ?: return refuseEnvelope()
            val body = json.optString("content", "")
            return splitWrapped(body, limit) { index, part ->
                if (index == 0) {
                    OSHIControlPayload.REPLY + JSONObject().put("content", part).put("replyTo", replyTo).toString()
                } else {
                    part
                }
            }
        }

        if (content.startsWith(OSHIControlPayload.FORWARDED)) {
            val json = parseEnvelope(content, OSHIControlPayload.FORWARDED) ?: return refuseEnvelope()
            if (!json.has("content")) return refuseEnvelope()
            val body = json.optString("content", "")
            return splitWrapped(body, limit) { _, part ->
                val copy = JSONObject(json.toString())
                copy.put("content", part)
                OSHIControlPayload.FORWARDED + copy.toString()
            }
        }

        if (content.startsWith(LEGACY_REPLY_PREFIX)) return refuseEnvelope()

        if (!MessageSendPolicy.isUserProse(content)) return Outcome.Single
        return splitPlain(content, limit)
    }

    private fun refuseEnvelope() = Outcome.TooLong(partsNeeded = MAX_PARTS + 1)

    private fun parseEnvelope(content: String, prefix: String): JSONObject? =
        runCatching { JSONObject(content.removePrefix(prefix)) }.getOrNull()

    /** iOS `splitWrapped`: split the body, re-wrap each part, and verify the WRAPPED result fits. */
    private fun splitWrapped(body: String, limit: Int, wrap: (Int, String) -> String): Outcome {
        val emptyWrapperCost = MessageSendPolicy.utf8Size(wrap(0, ""))
        var bodyLimit = maxOf(32, limit - emptyWrapperCost)
        repeat(8) {
            if (bodyLimit <= 16) return@repeat
            when (val outcome = splitPlain(body, bodyLimit)) {
                is Outcome.Single -> {
                    // The body fits unsplit, but the wrapped original was over the limit (we only get here
                    // then): rebuild and measure it like every other case.
                    val single = wrap(0, body)
                    val over = MessageSendPolicy.utf8Size(single) - limit
                    if (over <= 0) return Outcome.Parts(listOf(single))
                    bodyLimit = maxOf(16, bodyLimit - over)
                }
                is Outcome.TooLong -> return outcome
                is Outcome.Parts -> {
                    val wrapped = outcome.parts.mapIndexed { i, p -> wrap(i, p) }
                    val worst = wrapped.maxOf { MessageSendPolicy.utf8Size(it) - limit }
                    if (worst <= 0) return Outcome.Parts(wrapped)
                    bodyLimit = maxOf(16, bodyLimit - worst)
                }
            }
        }
        // Could not make the wrapper fit: refusing beats parts the radio drops without telling anyone.
        return Outcome.TooLong(partsNeeded = MAX_PARTS + 1)
    }

    private fun splitPlain(text: String, limit: Int): Outcome {
        if (MessageSendPolicy.utf8Size(text) <= limit) return Outcome.Single
        val pieces = split(text, limit)
        if (pieces.size <= 1) return Outcome.Single
        if (pieces.size > MAX_PARTS) return Outcome.TooLong(partsNeeded = pieces.size)
        return Outcome.Parts(pieces)
    }

    // ─────────────────────────── The algorithm ───────────────────────────

    /**
     * Cut [text] into pieces each ≤ [maxUTF8Bytes] UTF-8 bytes (iOS `split`). Boundary preference: blank
     * line → line break → sentence end → whitespace → grapheme cluster (mid-word, last resort). A boundary
     * is taken only if it fills half the budget; if none does, the latest boundary of any class is used.
     * Whitespace at a cut is dropped. Whitespace-only input yields no pieces.
     */
    fun split(text: String, maxUTF8Bytes: Int): List<String> {
        val limit = maxUTF8Bytes
        if (limit <= 0) return listOf(text)
        if (MessageSendPolicy.utf8Size(text) <= limit) return listOf(text)

        val parts = mutableListOf<String>()
        var cursor = 0
        while (cursor < text.length) {
            if (utf8Size(text, cursor, text.length) <= limit) {
                val tail = text.substring(cursor).trim()
                if (tail.isNotEmpty()) parts += tail
                break
            }
            // Furthest grapheme boundary whose prefix still fits the budget.
            var hardEnd = cursor
            var used = 0
            var i = cursor
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val cost = utf8Len(cp)
                if (used + cost > limit) break
                used += cost
                i += Character.charCount(cp)
                if (isGraphemeBoundary(text, i)) hardEnd = i
            }
            // One grapheme bigger than the whole budget (absurdly small limit): emit it alone, never spin.
            if (hardEnd == cursor) hardEnd = nextGraphemeBoundary(text, cursor)

            val cut = boundary(text, cursor, hardEnd, limit)
            val piece = text.substring(cursor, cut).trim()
            if (piece.isNotEmpty()) parts += piece

            var next = cut
            while (next < text.length && text[next].isWhitespace() && isGraphemeBoundary(text, next + 1)) next++
            cursor = if (next > cursor) next else nextGraphemeBoundary(text, cursor)
        }
        return parts
    }

    /** Best cut in `start until hardEnd`, by the preference order on [split]; `hardEnd` when there is none. */
    private fun boundary(text: String, start: Int, hardEnd: Int, limit: Int): Int {
        if (hardEnd <= start) return hardEnd
        var lastParagraph = -1
        var lastLine = -1
        var lastSentence = -1
        var lastWord = -1
        var previousWasNewline = false
        var i = start
        while (i < hardEnd) {
            val c = text[i]
            val after = i + 1
            val ok = isGraphemeBoundary(text, after)
            if (c == '\n') {
                if (ok) {
                    if (previousWasNewline) lastParagraph = after
                    lastLine = after
                }
                previousWasNewline = true
            } else {
                if (ok && c.isWhitespace()) {
                    lastWord = after
                    if (endsSentence(text, start, i)) lastSentence = after
                } else if (ok && c in CJK_SENTENCE_ENDS) {
                    lastSentence = after
                }
                previousWasNewline = false
            }
            i = after
        }
        val floor = (limit * MINIMUM_FILL).toInt()
        val candidates = listOf(lastParagraph, lastLine, lastSentence, lastWord)
        for (c in candidates) {
            if (c > start && utf8Size(text, start, c) >= floor) return c
        }
        val best = candidates.filter { it > start }.maxOrNull()
        return best ?: hardEnd
    }

    /** True when the whitespace at [ws] follows a sentence end: `.`/`!`/`?`/`…`, then optional closers. */
    private fun endsSentence(text: String, start: Int, ws: Int): Boolean {
        var j = ws - 1
        while (j >= start && text[j] in SENTENCE_CLOSERS) j--
        return j >= start && text[j] in SENTENCE_ENDS
    }

    private const val SENTENCE_ENDS = ".!?…"
    private const val SENTENCE_CLOSERS = "\"')]»”’"
    private const val CJK_SENTENCE_ENDS = "。！？"

    // ─────────────────────────── Grapheme boundaries ───────────────────────────

    private fun utf8Len(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    private fun utf8Size(text: String, from: Int, to: Int): Int {
        var n = 0
        var i = from
        while (i < to) {
            val cp = text.codePointAt(i)
            n += utf8Len(cp)
            i += Character.charCount(cp)
        }
        return n
    }

    private fun nextGraphemeBoundary(text: String, from: Int): Int {
        var i = from + Character.charCount(text.codePointAt(from))
        while (i < text.length && !isGraphemeBoundary(text, i)) i += Character.charCount(text.codePointAt(i))
        return i
    }

    /**
     * May the text be cut between `text[index - 1]` and `text[index]`? The subset of UAX #29 that a chat
     * message meets: never inside a surrogate pair or CRLF; never before a combining/spacing mark, ZWJ,
     * variation selector, emoji modifier or tag; never after ZWJ; regional indicators in pairs; Hangul
     * jamo kept with their syllable. Deliberately not `java.text.BreakIterator`: the JDK's only follows
     * the extended rules from JDK 20, so a unit test and a phone could disagree on where an emoji ends.
     */
    internal fun isGraphemeBoundary(text: String, index: Int): Boolean {
        if (index <= 0 || index >= text.length) return true
        val prevChar = text[index - 1]
        val nextChar = text[index]
        if (Character.isHighSurrogate(prevChar) && Character.isLowSurrogate(nextChar)) return false
        if (prevChar == '\r' && nextChar == '\n') return false
        val next = text.codePointAt(index)
        val prev = text.codePointBefore(index)
        if (prev == ZWJ) return false
        if (next == ZWJ || isExtend(next)) return false
        if (isRegionalIndicator(prev) && isRegionalIndicator(next)) {
            // Cut only after an even number of indicators: each pair is one flag.
            var count = 0
            var j = index
            while (j > 0) {
                val cp = text.codePointBefore(j)
                if (!isRegionalIndicator(cp)) break
                count++
                j -= Character.charCount(cp)
            }
            return count % 2 == 0
        }
        if (isHangulL(prev) && (isHangulL(next) || isHangulV(next) || isHangulSyllable(next))) return false
        if ((isHangulV(prev) || isHangulLV(prev)) && (isHangulV(next) || isHangulT(next))) return false
        if ((isHangulT(prev) || isHangulLVT(prev)) && isHangulT(next)) return false
        return true
    }

    private const val ZWJ = 0x200D

    private fun isExtend(cp: Int): Boolean {
        when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> return true
        }
        return cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF ||   // variation selectors
            cp in 0x1F3FB..0x1F3FF ||                              // emoji skin-tone modifiers
            cp in 0xE0020..0xE007F                                 // tag characters (subdivision flags)
    }

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF
    private fun isHangulL(cp: Int) = cp in 0x1100..0x115F || cp in 0xA960..0xA97F
    private fun isHangulV(cp: Int) = cp in 0x1160..0x11A7 || cp in 0xD7B0..0xD7C6
    private fun isHangulT(cp: Int) = cp in 0x11A8..0x11FF || cp in 0xD7CB..0xD7FB
    private fun isHangulSyllable(cp: Int) = cp in 0xAC00..0xD7A3
    private fun isHangulLV(cp: Int) = isHangulSyllable(cp) && (cp - 0xAC00) % 28 == 0
    private fun isHangulLVT(cp: Int) = isHangulSyllable(cp) && (cp - 0xAC00) % 28 != 0
}
