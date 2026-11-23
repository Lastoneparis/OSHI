package com.oshi.messenger.mail

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Turns the decrypted bytes of a mail into something the UI can show.
 *
 * On a zero-access mailbox the server never holds the plaintext, so it cannot parse mail
 * for the client — this work has to happen on the device. Mirrors MailMessageParser.swift
 * and mime.js, including the two bugs those found:
 *
 *  - a header folded across lines is ONE header and must be unfolded before matching;
 *  - the CRLF before a MIME boundary belongs to the delimiter, not to the part.
 *
 * The walk works on the raw BYTES and decodes each part straight from its byte range.
 * (__MAIL_PHOTO_WAS_ZERO_BYTES_2026_11_16__ The first version turned the whole message
 * into one ISO-8859-1 String, split it, copied each part and filtered the base64 through
 * a StringBuilder: a 24 MB message with a 17 MB photo needed ~200 MB of heap, hit the
 * 192 MB limit, and `runCatching` swallowed the OutOfMemoryError — the photo was listed
 * as "IMG_4021.JPG · 0 B" and "saving" it wrote an empty file.)
 */
object MailMime {

    /**
     * One non-body part. [contentId] is the part's `Content-ID` without the angle
     * brackets, which an HTML body references as `cid:…`; [inline] says the sender meant
     * it to be shown in the body rather than listed.
     */
    data class Attachment(
        val filename: String,
        val mimeType: String,
        val bytes: ByteArray,
        val contentId: String? = null,
        val inline: Boolean = false,
    ) {
        val isImage: Boolean get() = mimeType.startsWith("image/", ignoreCase = true) ||
            filename.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
    }

    data class Mail(
        val headers: Map<String, String>,
        val text: String,
        val html: String?,
        val attachments: List<Attachment>,
    ) {
        val subject: String get() = headers["subject"].orEmpty()
        val from: String get() = headers["from"].orEmpty()
        val to: String get() = headers["to"].orEmpty()
        val date: String get() = headers["date"].orEmpty()
    }

    internal val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")

    private val LATIN1 = Charsets.ISO_8859_1

    fun parse(raw: ByteArray): Mail {
        val (headEnd, bodyStart) = splitHeaders(raw, 0, raw.size)
        val headers = parseHeaders(String(raw, 0, headEnd, LATIN1))
        val acc = Acc()
        leaf(raw, bodyStart, raw.size, headers, acc, topLevel = true)
        var plain = acc.text.toString()
        val html = acc.html?.toString()
        if (plain.isBlank() && html != null) plain = stripHtml(html)
        return Mail(headers, plain.trim(), html, acc.attachments)
    }

    private class Acc {
        val text = StringBuilder()
        var html: StringBuilder? = null
        val attachments = mutableListOf<Attachment>()
    }

    /** Routes one part (or the whole message, when [topLevel]) by its own headers. */
    private fun leaf(raw: ByteArray, start: Int, end: Int, headers: Map<String, String>, acc: Acc, topLevel: Boolean = false) {
        val type = headers["content-type"] ?: "text/plain"
        val mime = type.substringBefore(';').trim().lowercase().ifEmpty { "text/plain" }
        val typeParams = params(type)
        val encoding = headers["content-transfer-encoding"]
        val disposition = headers["content-disposition"].orEmpty()
        val dispParams = params(disposition)
        val dispKind = disposition.substringBefore(';').trim().lowercase()
        val filename = (dispParams["filename"] ?: typeParams["name"])?.let { decodeEncodedWords(it).trim() }?.ifEmpty { null }
        val contentId = headers["content-id"]?.trim()?.removePrefix("<")?.removeSuffix(">")?.trim()?.ifEmpty { null }

        if (mime.startsWith("multipart/")) {
            typeParams["boundary"]?.let { walk(raw, start, end, it, acc) }
            return
        }
        val isBodyText = (mime == "text/plain" || mime == "text/html") &&
            dispKind != "attachment" && (filename == null || dispKind == "inline")
        if (isBodyText) {
            val text = String(decodeBytes(raw, start, end, encoding), charsetFor(typeParams["charset"]))
            if (mime == "text/html") {
                val sb = acc.html ?: StringBuilder().also { acc.html = it }
                sb.append(text)
            } else {
                // Several text parts in one message (a list footer, text around an inline
                // image) are all part of the body; the old walker kept only the first.
                if (acc.text.isNotEmpty() && text.isNotBlank()) acc.text.append("\n\n")
                acc.text.append(text)
            }
            return
        }
        if (topLevel && mime.startsWith("text/") && filename == null) {
            // A top-level text/enriched, text/x-something: show it rather than lose it.
            acc.text.append(String(decodeBytes(raw, start, end, encoding), charsetFor(typeParams["charset"])))
            return
        }
        acc.attachments += Attachment(
            filename = filename ?: defaultName(mime, acc.attachments.size + 1),
            mimeType = mime,
            bytes = decodeBytes(raw, start, end, encoding),
            contentId = contentId,
            inline = dispKind == "inline" || (dispKind.isEmpty() && contentId != null),
        )
    }

    /** A name for a part that came without one — "invite.ics" reads better than "attachment". */
    private fun defaultName(mime: String, index: Int): String = when (mime) {
        "text/calendar" -> "invite.ics"
        "message/rfc822" -> "message-$index.eml"
        "application/pdf" -> "document-$index.pdf"
        else -> if (mime.startsWith("image/")) {
            "image-$index." + when (val sub = mime.substringAfter('/')) { "jpeg" -> "jpg"; "svg+xml" -> "svg"; else -> sub }
        } else "attachment-$index"
    }

    private fun walk(raw: ByteArray, start: Int, end: Int, boundary: String, acc: Acc) {
        val delimiter = "--$boundary".toByteArray(LATIN1)
        var pos = findDelimiter(raw, delimiter, start, end)
        while (pos >= 0) {
            val afterDelim = pos + delimiter.size
            // "--boundary--" closes the multipart.
            if (afterDelim + 1 < end && raw[afterDelim] == '-'.code.toByte() && raw[afterDelim + 1] == '-'.code.toByte()) return
            val lineEnd = indexOf(raw, '\n'.code.toByte(), afterDelim, end)
            if (lineEnd < 0) return
            val partStart = lineEnd + 1
            val next = findDelimiter(raw, delimiter, partStart, end)
            var partEnd = if (next >= 0) next else end
            // The CRLF before a boundary belongs to the DELIMITER; keeping it appends a
            // blank line to every multipart body.
            if (next >= 0) {
                if (partEnd > partStart && raw[partEnd - 1] == '\n'.code.toByte()) partEnd--
                if (partEnd > partStart && raw[partEnd - 1] == '\r'.code.toByte()) partEnd--
            }
            if (partEnd > partStart) {
                val (headEnd, bodyStart) = splitHeaders(raw, partStart, partEnd)
                val headers = parseHeaders(String(raw, partStart, headEnd - partStart, LATIN1))
                leaf(raw, bodyStart, partEnd, headers, acc)
            }
            pos = next
        }
    }

    /** A delimiter only counts at the start of a line (or of the range). */
    private fun findDelimiter(raw: ByteArray, delimiter: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (true) {
            val at = indexOf(raw, delimiter, i, end)
            if (at < 0) return -1
            if (at == from || raw[at - 1] == '\n'.code.toByte()) return at
            i = at + 1
        }
    }

    private fun indexOf(raw: ByteArray, b: Byte, from: Int, end: Int): Int {
        for (i in from until end) if (raw[i] == b) return i
        return -1
    }

    private fun indexOf(raw: ByteArray, pattern: ByteArray, from: Int, end: Int): Int {
        if (pattern.isEmpty()) return from
        val first = pattern[0]
        var i = from
        val last = end - pattern.size
        outer@ while (i <= last) {
            if (raw[i] != first) { i++; continue }
            for (j in 1 until pattern.size) if (raw[i + j] != pattern[j]) { i++; continue@outer }
            return i
        }
        return -1
    }

    /** (end of the header block, start of the body); no blank line means no body. */
    private fun splitHeaders(raw: ByteArray, start: Int, end: Int): Pair<Int, Int> {
        // A part with no headers at all starts directly with its blank line.
        if (start < end && raw[start] == '\n'.code.toByte()) return start to start + 1
        if (start + 1 < end && raw[start] == '\r'.code.toByte() && raw[start + 1] == '\n'.code.toByte()) return start to start + 2
        val crlf = indexOf(raw, "\r\n\r\n".toByteArray(LATIN1), start, end)
        val lf = indexOf(raw, "\n\n".toByteArray(LATIN1), start, end)
        return when {
            crlf >= 0 && (lf < 0 || crlf <= lf) -> crlf to crlf + 4
            lf >= 0 -> lf to lf + 2
            else -> end to end
        }
    }

    private fun parseHeaders(block: String): Map<String, String> {
        // Unfold first, or every long Subject and To comes back truncated at the fold.
        val unfolded = block.replace("\r\n", "\n").replace(Regex("\n[ \t]+"), " ")
        val out = LinkedHashMap<String, String>()
        for (line in unfolded.split("\n")) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            if (key in out) continue // the first occurrence wins, as in every mail client
            out[key] = decodeEncodedWords(recoverUtf8(line.substring(colon + 1).trim()))
        }
        return out
    }

    /**
     * The message is read as one-char-per-byte (ISO-8859-1) so attachments survive, which
     * means a header carrying RAW UTF-8 — "Boutique Lumière" written with no RFC 2047
     * encoded-word, which plenty of real senders do — arrives here as Latin-1 mojibake
     * ("Boutique LumiÃ¨re"). Re-decode it strictly as UTF-8 and keep the result only if it
     * really was UTF-8; a genuine Latin-1 header fails the strict decode and is left alone.
     */
    private fun recoverUtf8(value: String): String {
        if (value.none { it.code in 0x80..0xFF }) return value
        return try {
            val bytes = ByteArray(value.length)
            for (i in value.indices) {
                val code = value[i].code
                if (code > 0xFF) return value // already decoded text, leave it
                bytes[i] = code.toByte()
            }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            value
        }
    }

    /**
     * The `; name=value` parameters of a structured header, lower-cased names, with
     * RFC 2231 applied: `filename*=UTF-8''r%C3%A9sum%C3%A9.pdf` and the continuation form
     * `filename*0*=…; filename*1*=…`. That is how Gmail, Apple Mail and Thunderbird send
     * every non-ASCII attachment name; matching only `filename=` turned each of them into
     * a file called "attachment".
     */
    internal fun params(header: String?): Map<String, String> {
        if (header.isNullOrEmpty()) return emptyMap()
        val plain = LinkedHashMap<String, String>()
        val sections = sortedMapOf<String, java.util.TreeMap<Int, Pair<String, Boolean>>>()
        val extended = LinkedHashMap<String, String>()
        for (piece in splitParams(header).drop(1)) {
            val eq = piece.indexOf('=')
            if (eq <= 0) continue
            val rawName = piece.substring(0, eq).trim().lowercase()
            var value = piece.substring(eq + 1).trim()
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                value = value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            }
            val m = Regex("""^([^*]+)\*(\d+)(\*?)$""").find(rawName)
            when {
                m != null -> sections.getOrPut(m.groupValues[1]) { java.util.TreeMap() }[m.groupValues[2].toInt()] =
                    value to m.groupValues[3].isNotEmpty()
                rawName.endsWith("*") -> extended[rawName.dropLast(1)] = decode2231(value, null).second
                else -> plain.putIfAbsent(rawName, value)
            }
        }
        for ((name, parts) in sections) {
            var charset: String? = null
            val sb = StringBuilder()
            val bytes = java.io.ByteArrayOutputStream()
            fun flush() { if (bytes.size() > 0) { sb.append(String(bytes.toByteArray(), charsetFor(charset))); bytes.reset() } }
            for ((index, part) in parts) {
                val (value, encoded) = part
                if (encoded) {
                    var v = value
                    if (index == 0) {
                        val (cs, rest) = splitCharset(value)
                        charset = cs; v = rest
                    }
                    bytes.write(percentDecode(v))
                } else {
                    flush(); sb.append(value)
                }
            }
            flush()
            extended[name] = sb.toString()
        }
        return plain + extended
    }

    /** Splits on `;` outside double quotes. */
    private fun splitParams(header: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < header.length) {
            val c = header[i]
            when {
                c == '\\' && quoted && i + 1 < header.length -> { sb.append(c).append(header[i + 1]); i++ }
                c == '"' -> { quoted = !quoted; sb.append(c) }
                c == ';' && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    private fun splitCharset(value: String): Pair<String?, String> {
        val first = value.indexOf('\'')
        val second = if (first >= 0) value.indexOf('\'', first + 1) else -1
        return if (first >= 0 && second > first) value.substring(0, first).ifEmpty { null } to value.substring(second + 1)
        else null to value
    }

    private fun decode2231(value: String, charset: String?): Pair<String?, String> {
        val (cs, rest) = splitCharset(value)
        val use = cs ?: charset
        return use to String(percentDecode(rest), charsetFor(use))
    }

    private fun percentDecode(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { out.write(v); i += 3; continue }
            }
            out.write(c.code and 0xFF)
            i++
        }
        return out.toByteArray()
    }

    /** RFC 2047 encoded-words — how every non-ASCII subject and filename travels. */
    fun decodeEncodedWords(value: String): String {
        if (!value.contains("=?")) return value
        // Whitespace BETWEEN two encoded-words is padding, not content.
        val collapsed = value.replace(Regex("""(=\?[^?]+\?[BbQq]\?[^?]*\?=)\s+(?==\?)"""), "$1")
        return Regex("""=\?([^?]+)\?([BbQq])\?([^?]*)\?=""").replace(collapsed) { m ->
            val (charset, enc, text) = m.destructured
            val ascii = text.toByteArray(LATIN1)
            val bytes = if (enc.uppercase() == "B") decodeBase64(ascii, 0, ascii.size)
            else text.replace('_', ' ').toByteArray(LATIN1).let { decodeQuotedPrintable(it, 0, it.size) }
            // Any IANA charset, not just utf-8 and latin-1.
            String(bytes, charsetFor(charset))
        }
    }

    private val B64 = IntArray(256) { -1 }.also { t ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c -> t[c.code] = i }
        // URL-safe alphabet too: a few mailers emit it.
        t['-'.code] = 62; t['_'.code] = 63
    }

    /**
     * Base64 straight from a byte range: skips line breaks and anything else outside the
     * alphabet, stops at padding, and allocates the output exactly once.
     */
    private fun decodeBase64(raw: ByteArray, start: Int, end: Int): ByteArray {
        var count = 0
        for (i in start until end) {
            val b = raw[i].toInt() and 0xFF
            if (b == '='.code) break
            if (B64[b] >= 0) count++
        }
        val out = ByteArray(count * 6 / 8)
        var acc = 0
        var bits = 0
        var n = 0
        for (i in start until end) {
            val b = raw[i].toInt() and 0xFF
            if (b == '='.code) break
            val v = B64[b]
            if (v < 0) continue
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (n < out.size) out[n++] = (acc shr bits).toByte()
            }
            acc = acc and 0xFFFF
        }
        return out
    }

    private fun hex(b: Int): Int = when (b) {
        in '0'.code..'9'.code -> b - '0'.code
        in 'A'.code..'F'.code -> b - 'A'.code + 10
        in 'a'.code..'f'.code -> b - 'a'.code + 10
        else -> -1
    }

    private fun decodeQuotedPrintable(raw: ByteArray, start: Int, end: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(end - start)
        var i = start
        while (i < end) {
            val b = raw[i].toInt() and 0xFF
            if (b == '='.code) {
                // Soft line break: "=\r\n" or "=\n".
                if (i + 1 < end && raw[i + 1] == '\n'.code.toByte()) { i += 2; continue }
                if (i + 2 < end && raw[i + 1] == '\r'.code.toByte() && raw[i + 2] == '\n'.code.toByte()) { i += 3; continue }
                if (i + 2 < end) {
                    val h = hex(raw[i + 1].toInt() and 0xFF)
                    val l = hex(raw[i + 2].toInt() and 0xFF)
                    if (h >= 0 && l >= 0) { out.write(h * 16 + l); i += 3; continue }
                }
            }
            out.write(b)
            i++
        }
        return out.toByteArray()
    }

    /**
     * Resolve a declared charset name to a real [java.nio.charset.Charset].
     *
     * A hand-written table of "the charsets we expect" was wrong twice: a substring test
     * for "8859-1" also matches iso-8859-15, so its euro sign (0xA4 there) came out as the
     * currency sign ¤; and any label outside the table was not decoded at all, so a
     * windows-1252 subject — what Outlook emits by default — was shown to the user as its
     * raw "=?windows-1252?Q?…?=" source. The JVM already maps the whole IANA registry.
     *
     * One deliberate exception, the one every browser makes (WHATWG Encoding): a message
     * LABELLED iso-8859-1 or us-ascii is decoded as windows-1252. Windows mailers label
     * cp1252 text "iso-8859-1" all the time, and decoding it literally turns the euro
     * sign (0x80) and curly quotes (0x93/0x94) into invisible control characters; the two
     * agree on every printable Latin-1 character, so nothing correct is lost.
     */
    private fun charsetFor(name: String?): java.nio.charset.Charset {
        // RFC 2231 permits a language suffix on the charset ("utf-8*fr").
        val cs = name?.trim()?.trim('"', '\'')?.substringBefore('*').orEmpty()
        if (cs.isEmpty()) return Charsets.UTF_8
        if (cs.lowercase() in LATIN1_LABELS) return windows1252
        return try {
            if (java.nio.charset.Charset.isSupported(cs)) java.nio.charset.Charset.forName(cs) else Charsets.UTF_8
        } catch (e: IllegalArgumentException) {
            // Unknown or malformed label: UTF-8 is the best guess for modern mail and
            // degrades to readable ASCII for anything else. Never lose the message.
            Charsets.UTF_8
        }
    }

    private val LATIN1_LABELS = setOf("iso-8859-1", "iso8859-1", "iso_8859-1", "latin1", "l1", "us-ascii", "ascii", "cp819", "ibm819")
    private val windows1252: java.nio.charset.Charset by lazy {
        runCatching { java.nio.charset.Charset.forName("windows-1252") }.getOrDefault(LATIN1)
    }

    private fun decodeBytes(raw: ByteArray, start: Int, end: Int, encoding: String?): ByteArray =
        when (encoding?.trim()?.lowercase()) {
            "base64" -> decodeBase64(raw, start, end)
            "quoted-printable" -> decodeQuotedPrintable(raw, start, end)
            else -> raw.copyOfRange(start, end)
        }

    private val NAMED_ENTITIES = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "laquo" to "«", "raquo" to "»",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "middot" to "·", "bull" to "•",
        "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€", "pound" to "£", "yen" to "¥",
        "rarr" to "→", "larr" to "←", "times" to "×", "deg" to "°",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "euml" to "ë", "agrave" to "à", "acirc" to "â",
        "aacute" to "á", "auml" to "ä", "ccedil" to "ç", "icirc" to "î", "iuml" to "ï", "iacute" to "í",
        "ocirc" to "ô", "ouml" to "ö", "oacute" to "ó", "ugrave" to "ù", "ucirc" to "û", "uuml" to "ü",
        "uacute" to "ú", "ntilde" to "ñ", "szlig" to "ß", "Eacute" to "É", "Agrave" to "À", "Ccedil" to "Ç",
        "zwnj" to "", "zwj" to "‍", "shy" to "", "thinsp" to " ", "ensp" to " ", "emsp" to " ",
    )

    /** `&eacute;`, `&#8217;`, `&#x2019;` → the character; unknown names stay as written. */
    internal fun decodeEntities(s: String): String =
        Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint) ?: m.value
                else -> NAMED_ENTITIES[e] ?: NAMED_ENTITIES[e.lowercase()] ?: m.value
            }
        }

    private fun codePoint(cp: Int): String =
        if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else ""

    /**
     * HTML to readable plain text — for the list snippet, deep search, forwarding, and
     * the body of an HTML-only message when no HTML view is available.
     */
    fun stripHtml(html: String): String = html
        .replace(Regex("<!--[\\s\\S]*?-->"), " ")
        // `\b` matters: without it `<head` also matched `<header>` and ate its content.
        .replace(Regex("<(script|style|head|title)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE), " ")
        // Source line breaks and indentation are not text; only tags make lines.
        .replace(Regex("[ \\t\\r\\n]+"), " ")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        // Every BLOCK element ends a line, not just </p>. Handling only paragraphs ran a
        // heading straight into the text after it ("Merci !Votre commande…").
        .replace(
            Regex(
                "</(p|div|h[1-6]|li|tr|blockquote|section|article|header|footer|ul|ol|table|pre)>",
                RegexOption.IGNORE_CASE,
            ),
            "\n\n",
        )
        .replace(Regex("<(li|tr)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .let(::decodeEntities)
        // Preheader padding (zero-width joiners, U+034F, soft hyphens) is invisible filler.
        .replace(Regex("[\\u200B\\u200C\\u034F\\u00AD\\uFEFF]"), "")
        .replace(' ', ' ')
        .lines().joinToString("\n") { it.trim().replace(Regex(" {2,}"), " ") }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** "Jane Doe <jane@x.com>" -> "Jane Doe"; a bare address stays as it is. */
    fun displayName(address: String): String {
        val t = address.trim()
        val lt = t.indexOf('<')
        if (lt > 0) {
            val name = t.substring(0, lt).trim().trim('"', '\'')
            if (name.isNotEmpty()) return name
        }
        return emailAddress(t)
    }

    fun emailAddress(address: String): String =
        Regex("<([^>]+)>").find(address)?.groupValues?.get(1) ?: address.trim()
}
