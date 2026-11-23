package com.oshi.messenger.mail

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Pure presentation rules for the mail screens — no Compose, no Android — so the list,
 * reader and composer agree with each other and can be tested on the JVM.
 *
 * Nothing here touches the wire: reply, reply-all and forward only PREFILL the composer,
 * which sends through the same `MailViewModel.send` path as a blank message.
 */
object MailDisplay {

    /**
     * The list's time column, the way mail apps show it: a time for today, a day and
     * month this year, a short date before that. [receivedAt] is the server's ISO-8601
     * instant; anything unparseable falls back to its first ten characters (the old
     * behaviour), never to a crash.
     */
    fun listTime(receivedAt: String, now: ZonedDateTime = ZonedDateTime.now(), locale: Locale = Locale.getDefault()): String {
        val at = parse(receivedAt, now.zone) ?: return receivedAt.take(10)
        return when {
            at.toLocalDate() == now.toLocalDate() ->
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(at)
            at.year == now.year ->
                DateTimeFormatter.ofPattern("d MMM", locale).format(at)
            else ->
                DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(at)
        }
    }

    private fun parse(iso: String, zone: ZoneId): ZonedDateTime? =
        runCatching { Instant.parse(iso).atZone(zone) }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(iso).withZoneSameInstant(zone) }.getOrNull()

    /** Longest list snippet a client builds itself (drafts); the server caps its own at the same. */
    const val SNIPPET_MAX = 140

    /**
     * The grey line under the subject. The sealed envelope's [envelopeSnippet] (computed by
     * the server at delivery) wins; mail delivered before that existed falls back to what
     * the list has always shown — a body opened on this device in this session.
     */
    fun listSnippet(envelopeSnippet: String?, openedBody: String?): String? =
        envelopeSnippet?.takeIf { it.isNotBlank() }
            ?: openedBody?.lines()?.drop(2)?.joinToString(" ")?.takeIf { it.isNotBlank() }

    /** Whitespace-collapsed, length-capped preview of text the user typed (draft envelopes). */
    fun snippetOf(text: String, max: Int = SNIPPET_MAX): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.codePointCount(0, flat.length) <= max) return flat
        val cut = flat.substring(0, flat.offsetByCodePoints(0, max))
        val space = cut.lastIndexOf(' ')
        return (if (space >= max * 0.6) cut.substring(0, space) else cut).trimEnd(' ', ',', ';', ':', '.') + "\u2026"
    }

    /** Spoken form of the row's attachment markers, for TalkBack. */
    fun attachmentDescription(count: Int, hasImages: Boolean): String? = when {
        count <= 0 && !hasImages -> null
        count <= 0 -> "Contains images"
        count == 1 -> if (hasImages) "1 attachment, contains images" else "1 attachment"
        else -> if (hasImages) "$count attachments, contains images" else "$count attachments"
    }

    /** One letter for the avatar: the first letter or digit of the display name, else "?". */
    fun initial(name: String): String =
        name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

    /** A stable hue (0..360) per address, so a sender keeps the same avatar colour. */
    fun avatarHue(address: String): Float {
        val h = MailMime.emailAddress(address).lowercase().hashCode()
        return ((h % 360 + 360) % 360).toFloat()
    }

    /** Splits a "To" line into addresses, tolerating commas, semicolons and blanks. */
    fun splitRecipients(to: String): List<String> =
        to.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

    fun replySubject(subject: String): String =
        if (subject.startsWith("Re:", ignoreCase = true)) subject else "Re: $subject"

    fun forwardSubject(subject: String): String =
        if (subject.startsWith("Fwd:", ignoreCase = true) || subject.startsWith("Fw:", ignoreCase = true)) subject
        else "Fwd: $subject"

    /**
     * Reply-all goes to the sender plus everyone else on the To line, minus this
     * mailbox's own addresses (primary and aliases) and duplicates.
     */
    fun replyAllRecipients(from: String, to: String, own: Set<String>): String {
        val ownLower = own.map { it.lowercase() }.toSet()
        val seen = LinkedHashSet<String>()
        (listOf(from) + splitRecipients(to)).forEach { raw ->
            val address = MailMime.emailAddress(raw)
            if (address.isNotEmpty() && address.lowercase() !in ownLower) seen += address
        }
        return seen.joinToString(", ")
    }

    /** The quoted block a forward starts with — plain text, like the body it quotes. */
    fun forwardBody(from: String, to: String, date: String, subject: String, body: String): String =
        buildString {
            append("\n\n---------- Forwarded message ----------\n")
            append("From: ").append(from).append('\n')
            if (date.isNotBlank()) append("Date: ").append(date).append('\n')
            append("Subject: ").append(subject).append('\n')
            if (to.isNotBlank()) append("To: ").append(to).append('\n')
            append('\n').append(body)
        }

    // ---- reader: plain-text bodies ---------------------------------------------

    enum class BlockKind { TEXT, QUOTE, SIGNATURE }

    /**
     * A run of consecutive lines that render the same way. [rtl] is the paragraph
     * direction of the run's first strong character.
     *
     * Direction is decided PER LINE: a mail that says hello in French, then Arabic, then
     * Hebrew has three paragraph directions. Rendered as one Text, the whole body took the
     * direction of its first letter, so the Arabic and Hebrew lines were laid out
     * left-to-right with their wrapped tails stuck on the wrong side.
     */
    data class TextBlock(
        val kind: BlockKind,
        val text: String,
        val rtl: Boolean,
        val quoteLevel: Int = 0,
        /** A blank line separated this block from the one before it. */
        val spaceBefore: Boolean = false,
    )

    fun textBlocks(body: String): List<TextBlock> {
        val lines = body.replace("\r\n", "\n").split('\n')
        // "-- " on its own line starts the signature (RFC 3676); use the LAST one, a quoted
        // reply can carry an earlier signature of its own.
        val sigAt = lines.indexOfLast { it == "-- " || it == "--" }
        val out = mutableListOf<TextBlock>()
        val current = StringBuilder()
        var kind = BlockKind.TEXT
        var level = 0
        var rtl = false
        var started = false
        var gap = false        // blank line(s) seen since the last text line
        var blockGap = false   // the gap in front of the block being built

        fun flush() {
            if (started) out += TextBlock(kind, current.toString().trimEnd('\n'), rtl, level, blockGap && out.isNotEmpty())
            current.clear()
            started = false
        }

        lines.forEachIndexed { index, rawLine ->
            if (index == sigAt) { flush(); gap = true; return@forEachIndexed }
            var line = rawLine
            var lineLevel = 0
            if (sigAt < 0 || index < sigAt) {
                while (true) {
                    val t = line.trimStart(' ')
                    if (!t.startsWith(">")) break
                    lineLevel++
                    line = t.substring(1).removePrefix(" ")
                }
            }
            val lineKind = when {
                sigAt in 0..index -> BlockKind.SIGNATURE
                lineLevel > 0 -> BlockKind.QUOTE
                else -> BlockKind.TEXT
            }
            if (line.isBlank()) { gap = true; return@forEachIndexed }
            val strong = firstStrongIsRtl(line)
            if (started && (lineKind != kind || lineLevel != level || (strong != null && strong != rtl))) flush()
            if (!started) {
                kind = lineKind; level = lineLevel
                rtl = strong ?: rtl
                blockGap = gap
                started = true
            } else if (gap) {
                current.append('\n') // keep the paragraph break inside the block
            }
            current.append(line).append('\n')
            gap = false
        }
        flush()
        return out
    }

    /** true = RTL, false = LTR, null = no strong character (digits, punctuation, blank). */
    fun firstStrongIsRtl(text: String): Boolean? {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when (Character.getDirectionality(cp)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            }
            i += Character.charCount(cp)
        }
        return null
    }

    data class Link(val start: Int, val end: Int, val url: String)

    private val LINK = Regex(
        """(?i)\b(?:https?://|www\.)[^\s<>"'`]+|[A-Z0-9._%+-]+@[A-Z0-9-]+(?:\.[A-Z0-9-]+)+""",
    )

    /** Web addresses and e-mail addresses in plain text, as tappable ranges. */
    fun links(text: String): List<Link> = LINK.findAll(text).mapNotNull { m ->
        var value = m.value
        // Sentence punctuation after a URL is not part of it; a ")" is, if it closes one.
        while (value.isNotEmpty() && value.last() in ".,;:!?'\"]>}" ||
            (value.endsWith(")") && value.count { it == '(' } < value.count { it == ')' })
        ) value = value.dropLast(1)
        if (value.isEmpty()) return@mapNotNull null
        val url = when {
            value.contains('@') && !value.contains("://") && !value.startsWith("www.", true) -> "mailto:$value"
            value.startsWith("www.", ignoreCase = true) -> "https://$value"
            else -> value
        }
        Link(m.range.first, m.range.first + value.length, url)
    }.toList()

    /** Only these leave the reader; `javascript:`, `file:`, `intent:`, `content:` never do. */
    fun isOpenableLink(url: String): Boolean =
        url.substringBefore(':').lowercase() in setOf("http", "https", "mailto", "tel")

    // ---- reader: HTML bodies ----------------------------------------------------

    /** Inline (`cid:`) images are served to the WebView from this host, never fetched. */
    const val CID_HOST = "cid.oshi.invalid"

    fun cidUrl(contentId: String): String = "https://$CID_HOST/" + java.net.URLEncoder.encode(contentId, "UTF-8")

    fun cidOf(url: String): String? {
        val prefix = "https://$CID_HOST/"
        if (!url.startsWith(prefix)) return null
        return runCatching { java.net.URLDecoder.decode(url.removePrefix(prefix), "UTF-8") }.getOrNull()
    }

    private val REMOTE = Regex(
        """(?i)(?:\b(?:src|background|poster|srcset|data-src)\s*=\s*["']?\s*(?:https?:)?//)|(?:url\(\s*["']?\s*(?:https?:)?//)|(?:<link\b[^>]*\bhref\s*=\s*["']?\s*(?:https?:)?//)""",
    )

    /**
     * Whether showing [html] as written would fetch something from the network — an
     * image, a background, a stylesheet. Each such fetch tells the sender that, when and
     * from which IP address the mail was read; a 1×1 "tracking pixel" exists for nothing
     * else.
     */
    fun hasRemoteContent(html: String): Boolean = REMOTE.containsMatchIn(html)

    /** The `cid:` ids an HTML body references, so the reader does not list them twice. */
    fun referencedCids(html: String): Set<String> =
        Regex("""(?i)cid:([^"'()\s>]+)""").findAll(html).map { it.groupValues[1] }.toSet()

    /**
     * What a newsletter needs to read on a phone: its own width capped at the screen,
     * images scaled down instead of overflowing, long words and URLs wrapped, `pre`
     * wrapped. Placed BEFORE the message's own styles, so only the `!important` rules
     * win over them.
     */
    private const val READER_CSS =
        "html{-webkit-text-size-adjust:100%;text-size-adjust:100%}" +
            "body{margin:0;padding:12px 16px;font-family:sans-serif;line-height:1.45;overflow-wrap:break-word;word-wrap:break-word}" +
            "img{max-width:100%!important;height:auto!important}" +
            "table{max-width:100%!important}" +
            "td,th{overflow-wrap:break-word;word-break:break-word}" +
            "pre,code{white-space:pre-wrap!important;overflow-wrap:anywhere}" +
            "blockquote{margin:8px 0 8px 4px;padding-left:12px;border-left:3px solid #9ca3af}" +
            "a{overflow-wrap:anywhere}"

    /**
     * The HTML the reader's WebView loads. Scripts, frames, plug-ins, forms, `<base>`,
     * `<meta http-equiv>` (refresh, redirects) are removed outright — JavaScript is off
     * in the WebView anyway, this is the second lock. `cid:` references point at
     * [CID_HOST], which the WebView serves from the message's own parts. Unless
     * [allowRemote], external stylesheets are dropped too (the WebView also refuses
     * every other network load).
     */
    fun readerHtml(html: String, allowRemote: Boolean): String {
        var h = html
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script\\s*>"), "")
            .replace(Regex("(?i)</?(script|iframe|frame|frameset|object|embed|applet|form|base)\\b[^>]*>"), "")
            .replace(Regex("(?i)<meta\\b[^>]*\\bhttp-equiv\\b[^>]*>"), "")
            .replace(Regex("(?i)<meta\\b[^>]*\\bname\\s*=\\s*[\"']?viewport[^>]*>"), "")
        if (!allowRemote) h = h.replace(Regex("(?i)<link\\b[^>]*>"), "")
        h = fluidWidths(h)
        h = Regex("""(?i)(?<=["'=(\s])cid:([^"'()\s>]+)""").replace(h) { cidUrl(it.groupValues[1]) }

        val head = "<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<style>$READER_CSS</style>"
        Regex("(?i)<head\\b[^>]*>").find(h)?.let { return h.substring(0, it.range.last + 1) + head + h.substring(it.range.last + 1) }
        Regex("(?i)<html\\b[^>]*>").find(h)?.let { return h.substring(0, it.range.last + 1) + "<head>$head</head>" + h.substring(it.range.last + 1) }
        return "<!DOCTYPE html><html><head>$head</head><body>$h</body></html>"
    }

    /**
     * Newsletters are built as a fixed 600 px table (`width="600"`, `style="width:600px"`,
     * `.wrapper{width:600px}`). A table's specified width is also its MINIMUM width, so no
     * `max-width` can shrink it, and on a 411 dp phone the right third was cut off. Fixed
     * widths of 300 px and more become "100%, but no wider than designed"; `min-width`s
     * that large are dropped. Images keep their attributes — `img{max-width:100%}` scales
     * them.
     */
    internal fun fluidWidths(html: String): String {
        var h = Regex("""(?i)(?<![-\w])width\s*:\s*(\d{3,4})(?:\.\d+)?px""").replace(html) { m ->
            val n = m.groupValues[1].toInt()
            if (n >= 300) "width:100%;max-width:${n}px" else m.value
        }
        h = Regex("""(?i)min-width\s*:\s*(\d{3,4})(?:\.\d+)?px""").replace(h) { m ->
            if (m.groupValues[1].toInt() >= 300) "min-width:0" else m.value
        }
        h = Regex("""(?i)<(table|td|th|div|center)\b[^>]*>""").replace(h) { tag ->
            Regex("""(?i)(\swidth\s*=\s*)(["']?)(\d{3,4})(?:px)?\2""").replace(tag.value) { a ->
                if (a.groupValues[3].toInt() >= 300) "${a.groupValues[1]}\"100%\"" else a.value
            }
        }
        return h
    }

    /**
     * How "always show images from this sender" is remembered: a SHA-256 of the
     * lower-cased address, so the preference file is not a plaintext list of who
     * writes to this mailbox.
     */
    fun senderKey(from: String): String {
        val address = MailMime.emailAddress(from).trim().lowercase()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(address.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Human size for attachment chips and the quota line. */
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    }
}
