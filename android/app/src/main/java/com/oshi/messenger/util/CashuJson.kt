package com.oshi.messenger.util

/**
 * 🪙 Just enough JSON to read a Cashu V3 token, and not one byte more.
 *
 * Why not a library: the same two reasons as [CashuCbor]. It parses a bearer
 * instrument a stranger pasted, on every message that renders, so a small
 * reader with bounded allocation and no surprises is a smaller thing to be
 * sure of — and it leaves [CashuTokens] with **zero dependencies**, which is
 * what lets the Windows/Linux desktop client compile the very same file
 * instead of carrying its own copy that drifts.
 *
 * Supports exactly what NUT-00's V3 payload contains: objects, arrays,
 * strings, numbers, booleans and null. Refuses anything else, and refuses
 * trailing content after the top-level value.
 */
object CashuJson {

    sealed class Value {
        data class Str(val value: String) : Value()
        data class Num(val value: Double) : Value()
        data class Bool(val value: Boolean) : Value()
        data class Arr(val items: List<Value>) : Value()
        data class Obj(val entries: Map<String, Value>) : Value()
        object Null : Value()
    }

    class MalformedJson(message: String) : IllegalArgumentException(message)

    /** Nesting a token never needs. */
    private const val MAX_DEPTH = 16

    fun parse(text: String): Value {
        val r = Reader(text)
        val v = r.value(0)
        r.skipSpace()
        if (!r.atEnd) throw MalformedJson("trailing content after the JSON value")
        return v
    }

    /** `obj["token"]` as an array, or an empty list. Readable call sites. */
    fun Value.array(key: String): List<Value> =
        ((this as? Value.Obj)?.entries?.get(key) as? Value.Arr)?.items ?: emptyList()

    fun Value.string(key: String): String? =
        ((this as? Value.Obj)?.entries?.get(key) as? Value.Str)?.value

    /** A JSON number is a Double; an amount is a count, so it comes back as Long. */
    fun Value.long(key: String): Long? =
        ((this as? Value.Obj)?.entries?.get(key) as? Value.Num)?.value
            ?.takeIf { it.isFinite() && it >= Long.MIN_VALUE.toDouble() && it <= Long.MAX_VALUE.toDouble() }
            ?.toLong()

    private class Reader(private val s: String) {
        private var i = 0
        val atEnd: Boolean get() = i >= s.length

        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(depth: Int): Value {
            if (depth > MAX_DEPTH) throw MalformedJson("nested deeper than $MAX_DEPTH")
            skipSpace()
            if (atEnd) throw MalformedJson("ran off the end")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> Value.Str(string())
                't' -> literal("true").let { Value.Bool(true) }
                'f' -> literal("false").let { Value.Bool(false) }
                'n' -> literal("null").let { Value.Null }
                else -> number()
            }
        }

        private fun literal(word: String) {
            if (!s.startsWith(word, i)) throw MalformedJson("expected $word")
            i += word.length
        }

        private fun obj(depth: Int): Value {
            i++ // {
            val out = LinkedHashMap<String, Value>()
            skipSpace()
            if (!atEnd && s[i] == '}') { i++; return Value.Obj(out) }
            while (true) {
                skipSpace()
                if (atEnd || s[i] != '"') throw MalformedJson("expected a key")
                val k = string()
                skipSpace()
                if (atEnd || s[i] != ':') throw MalformedJson("expected ':'")
                i++
                out[k] = value(depth + 1)
                skipSpace()
                if (atEnd) throw MalformedJson("unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return Value.Obj(out) }
                    else -> throw MalformedJson("expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): Value {
            i++ // [
            val out = ArrayList<Value>()
            skipSpace()
            if (!atEnd && s[i] == ']') { i++; return Value.Arr(out) }
            while (true) {
                out.add(value(depth + 1))
                skipSpace()
                if (atEnd) throw MalformedJson("unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return Value.Arr(out) }
                    else -> throw MalformedJson("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd) throw MalformedJson("unterminated string")
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        if (atEnd) throw MalformedJson("unterminated escape")
                        when (val e = s[i]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) throw MalformedJson("short \\u escape")
                                sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw MalformedJson("unknown escape \\$e")
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        private fun number(): Value {
            val start = i
            if (!atEnd && s[i] == '-') i++
            while (!atEnd && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val raw = s.substring(start, i)
            val d = raw.toDoubleOrNull() ?: throw MalformedJson("bad number '$raw'")
            return Value.Num(d)
        }
    }
}
