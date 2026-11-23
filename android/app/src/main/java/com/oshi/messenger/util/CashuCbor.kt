package com.oshi.messenger.util

/**
 * 🪙 Just enough CBOR to read a Cashu V4 token, and not one byte more.
 *
 * Why hand-rolled rather than a library: the same reason `MeshtasticManager`
 * hand-rolls protobuf. A token arrives from a stranger — it is a bearer
 * instrument someone pasted into a chat — so the parser runs on hostile input on
 * every message that renders. A 200-line reader that only knows the five major
 * types NUT-00 uses, refuses indefinite-length items and bounds every allocation
 * is a smaller thing to be sure of than a general CBOR implementation, and it
 * adds no dependency to an app that ships its own crypto.
 *
 * Deliberately NOT supported, and refused rather than guessed:
 *   * indefinite-length strings, arrays and maps (CBOR's streaming form) — a
 *     token is a fixed structure and nothing legitimate emits them;
 *   * floats and negative integers — an `amount` is a count of satoshis;
 *   * non-text map keys — NUT-00 keys are all one-character text strings.
 *
 * Reference: RFC 8949 §3, and the V4 layout in
 * https://github.com/cashubtc/nuts/blob/main/00.md
 */
object CashuCbor {

    /** The subset of the CBOR data model a token can contain. */
    sealed class Value {
        data class UInt(val value: Long) : Value()
        class Bytes(val value: ByteArray) : Value()
        data class Text(val value: String) : Value()
        data class Arr(val items: List<Value>) : Value()
        data class Obj(val entries: Map<String, Value>) : Value()
        object Null : Value()
    }

    class MalformedCbor(message: String) : IllegalArgumentException(message)

    /**
     * Decode one CBOR item from [bytes].
     *
     * Trailing bytes are an error, not something to ignore: a token with
     * anything after the map is not a token we understand, and quietly
     * accepting the prefix is how a truncated or doctored payload gets rendered
     * as if it were whole.
     */
    fun decode(bytes: ByteArray): Value {
        val reader = Reader(bytes)
        val value = reader.readValue(depth = 0)
        if (reader.offset != bytes.size) {
            throw MalformedCbor("${bytes.size - reader.offset} trailing byte(s) after the CBOR item")
        }
        return value
    }

    /** Nesting a token never needs; a guard against a payload built to recurse. */
    private const val MAX_DEPTH = 8

    private class Reader(private val buf: ByteArray) {
        var offset = 0
            private set

        fun readValue(depth: Int): Value {
            if (depth > MAX_DEPTH) throw MalformedCbor("nested deeper than $MAX_DEPTH")
            val head = next().toInt() and 0xFF
            val major = head ushr 5
            val info = head and 0x1F
            return when (major) {
                0 -> Value.UInt(readLength(info))
                2 -> Value.Bytes(readRaw(readCount(info)))
                3 -> Value.Text(String(readRaw(readCount(info)), Charsets.UTF_8))
                4 -> {
                    val n = readCount(info)
                    Value.Arr(ArrayList<Value>(n).also { list ->
                        repeat(n) { list.add(readValue(depth + 1)) }
                    })
                }
                5 -> {
                    val n = readCount(info)
                    val map = LinkedHashMap<String, Value>(n.coerceAtMost(64))
                    repeat(n) {
                        val key = readValue(depth + 1)
                        val value = readValue(depth + 1)
                        // Unknown shapes are skipped, not fatal: NUT-00 tells
                        // receivers to ignore fields they do not know, and a
                        // future field with a non-text key must not break a
                        // token whose money part we can read perfectly well.
                        if (key is Value.Text) map[key.value] = value
                    }
                    Value.Obj(map)
                }
                6 -> readValue(depth + 1).also { readLength(info) } // tag: value is what matters
                7 -> when (info) {
                    20 -> Value.UInt(0)   // false
                    21 -> Value.UInt(1)   // true
                    22, 23 -> Value.Null  // null / undefined
                    else -> throw MalformedCbor("unsupported simple value $info")
                }
                else -> throw MalformedCbor("unsupported major type $major")
            }
        }

        private fun next(): Byte {
            if (offset >= buf.size) throw MalformedCbor("ran off the end of the buffer")
            return buf[offset++]
        }

        /** The argument of a head byte: inline, or 1, 2, 4 or 8 following bytes. */
        private fun readLength(info: Int): Long = when {
            info < 24 -> info.toLong()
            info == 24 -> (next().toLong() and 0xFF)
            info == 25 -> readBigEndian(2)
            info == 26 -> readBigEndian(4)
            info == 27 -> readBigEndian(8)
            info == 31 -> throw MalformedCbor("indefinite-length items are not accepted")
            else -> throw MalformedCbor("reserved additional information $info")
        }

        private fun readBigEndian(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 8) or (next().toLong() and 0xFF) }
            if (v < 0) throw MalformedCbor("length does not fit in a signed 64-bit value")
            return v
        }

        /**
         * A length used to size an allocation. Bounded by what is actually left
         * in the buffer, so a header claiming four billion elements costs
         * nothing rather than an OutOfMemoryError.
         */
        private fun readCount(info: Int): Int {
            val n = readLength(info)
            val remaining = (buf.size - offset).toLong()
            if (n > remaining) throw MalformedCbor("item claims $n entries, only $remaining byte(s) left")
            return n.toInt()
        }

        private fun readRaw(n: Int): ByteArray {
            if (offset + n > buf.size) throw MalformedCbor("string of $n byte(s) runs past the end")
            return buf.copyOfRange(offset, offset + n).also { offset += n }
        }
    }
}
