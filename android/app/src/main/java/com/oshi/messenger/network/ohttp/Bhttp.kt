package com.oshi.messenger.network.ohttp

import java.io.ByteArrayOutputStream

/**
 * Binary HTTP, KNOWN-LENGTH form only (RFC 9292 §3), plus the QUIC variable-length
 * integers it is built on (RFC 9000 §16). This is the inner message carried inside an
 * Oblivious HTTP request/response (RFC 9458, media types `message/bhttp`).
 *
 * Decoding accepts the RFC 9292 §3.8 truncation (trailing empty sections omitted) and
 * trailing zero padding; it skips informational (1xx) responses (§3.5).
 */
object Bhttp {
    const val FRAMING_KNOWN_REQUEST = 0L
    const val FRAMING_KNOWN_RESPONSE = 1L
    const val MAX_VARINT = (1L shl 62) - 1

    data class Request(
        val method: String,
        val scheme: String,
        val authority: String,
        val path: String,
        /** Field lines in order; names are lower-cased on encode (RFC 9292 §3.6). */
        val headers: List<Pair<String, String>> = emptyList(),
        val content: ByteArray = ByteArray(0),
        val trailers: List<Pair<String, String>> = emptyList(),
    )

    data class Response(
        val status: Int,
        val headers: List<Pair<String, String>> = emptyList(),
        val content: ByteArray = ByteArray(0),
        val trailers: List<Pair<String, String>> = emptyList(),
    ) {
        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, true) }?.second
    }

    class FormatException(message: String) : IllegalArgumentException(message)

    // ---- varints -------------------------------------------------------------------

    fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        require(value in 0..MAX_VARINT) { "varint out of range: $value" }
        when {
            value <= 63 -> out.write(value.toInt())
            value <= 16383 -> { out.write(0x40 or (value shr 8).toInt()); out.write(value.toInt() and 0xff) }
            value <= 1073741823 -> {
                out.write(0x80 or (value shr 24).toInt())
                for (s in intArrayOf(16, 8, 0)) out.write((value shr s).toInt() and 0xff)
            }
            else -> {
                out.write(0xc0 or (value shr 56).toInt())
                for (s in intArrayOf(48, 40, 32, 24, 16, 8, 0)) out.write((value shr s).toInt() and 0xff)
            }
        }
    }

    fun encodeVarint(value: Long): ByteArray = ByteArrayOutputStream().also { writeVarint(it, value) }.toByteArray()

    /** Cursor over a byte array; every read is bounds-checked. */
    class Reader(private val buf: ByteArray, private var pos: Int = 0) {
        val remaining get() = buf.size - pos
        fun atEnd() = pos >= buf.size
        fun byte(): Int { if (pos >= buf.size) throw FormatException("truncated"); return buf[pos++].toInt() and 0xff }
        fun varint(): Long {
            val first = byte()
            val len = 1 shl (first shr 6)
            var v = (first and 0x3f).toLong()
            repeat(len - 1) { v = (v shl 8) or byte().toLong() }
            return v
        }
        fun bytes(n: Long): ByteArray {
            if (n < 0 || n > remaining) throw FormatException("length $n exceeds remaining $remaining")
            val out = buf.copyOfRange(pos, pos + n.toInt()); pos += n.toInt(); return out
        }
        fun lengthPrefixed(): ByteArray = bytes(varint())
        /** RFC 9292 §3.8: a missing section at the very end means "empty". */
        fun lengthPrefixedOrEmpty(): ByteArray = if (atEnd()) ByteArray(0) else lengthPrefixed()
        fun restIsPadding(): Boolean { for (i in pos until buf.size) if (buf[i].toInt() != 0) return false; return true }
    }

    // ---- encode --------------------------------------------------------------------

    private fun ByteArrayOutputStream.lp(bytes: ByteArray) { writeVarint(this, bytes.size.toLong()); write(bytes) }

    private fun fieldSection(fields: List<Pair<String, String>>): ByteArray {
        val s = ByteArrayOutputStream()
        for ((name, value) in fields) {
            s.lp(name.lowercase().toByteArray(Charsets.US_ASCII))
            s.lp(value.toByteArray(Charsets.UTF_8))
        }
        return s.toByteArray()
    }

    fun encodeRequest(r: Request): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, FRAMING_KNOWN_REQUEST)
        out.lp(r.method.toByteArray(Charsets.US_ASCII))
        out.lp(r.scheme.toByteArray(Charsets.US_ASCII))
        out.lp(r.authority.toByteArray(Charsets.US_ASCII))
        out.lp(r.path.toByteArray(Charsets.US_ASCII))
        out.lp(fieldSection(r.headers))
        out.lp(r.content)
        out.lp(fieldSection(r.trailers))
        return out.toByteArray()
    }

    fun encodeResponse(r: Response): ByteArray {
        require(r.status in 200..599) { "final status must be 2xx-5xx" }
        val out = ByteArrayOutputStream()
        writeVarint(out, FRAMING_KNOWN_RESPONSE)
        writeVarint(out, r.status.toLong())
        out.lp(fieldSection(r.headers))
        out.lp(r.content)
        out.lp(fieldSection(r.trailers))
        return out.toByteArray()
    }

    // ---- decode --------------------------------------------------------------------

    private fun parseFields(section: ByteArray): List<Pair<String, String>> {
        val r = Reader(section)
        val out = ArrayList<Pair<String, String>>()
        while (!r.atEnd()) {
            val name = String(r.lengthPrefixed(), Charsets.US_ASCII)
            val value = String(r.lengthPrefixed(), Charsets.UTF_8)
            out += name to value
        }
        return out
    }

    private fun checkTail(r: Reader) { if (!r.restIsPadding()) throw FormatException("trailing non-zero bytes") }

    fun decodeRequest(bytes: ByteArray): Request {
        val r = Reader(bytes)
        val framing = r.varint()
        if (framing != FRAMING_KNOWN_REQUEST) throw FormatException("not a known-length request (framing $framing)")
        val method = String(r.lengthPrefixed(), Charsets.US_ASCII)
        val scheme = String(r.lengthPrefixed(), Charsets.US_ASCII)
        val authority = String(r.lengthPrefixed(), Charsets.US_ASCII)
        val path = String(r.lengthPrefixed(), Charsets.US_ASCII)
        val headers = parseFields(r.lengthPrefixedOrEmpty())
        val content = r.lengthPrefixedOrEmpty()
        val trailers = parseFields(r.lengthPrefixedOrEmpty())
        checkTail(r)
        return Request(method, scheme, authority, path, headers, content, trailers)
    }

    fun decodeResponse(bytes: ByteArray): Response {
        val r = Reader(bytes)
        val framing = r.varint()
        if (framing != FRAMING_KNOWN_RESPONSE) throw FormatException("not a known-length response (framing $framing)")
        var status: Long
        while (true) {
            status = r.varint()
            if (status in 100..199) { r.lengthPrefixed(); continue } // informational: skip its header section
            break
        }
        if (status !in 200..599) throw FormatException("invalid final status $status")
        val headers = parseFields(r.lengthPrefixedOrEmpty())
        val content = r.lengthPrefixedOrEmpty()
        val trailers = parseFields(r.lengthPrefixedOrEmpty())
        checkTail(r)
        return Response(status.toInt(), headers, content, trailers)
    }
}
