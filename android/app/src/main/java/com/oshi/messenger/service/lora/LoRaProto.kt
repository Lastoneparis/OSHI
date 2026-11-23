package com.oshi.messenger.service.lora

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf codec — a byte-for-byte port of the hand-rolled one iOS uses.
 *
 * REFERENCE: OSHI/MeshtasticManager.swift:25-84 (`ProtoWriter`, `ProtoField`,
 * `parseProtoFields`). No protobuf dependency on either platform: the Meshtastic
 * surface OSHI touches is a handful of fields, and a generated stub would be a
 * multi-megabyte dependency plus a schema to keep in sync with iOS.
 *
 * THE RULE THAT MATTERS: protobuf OMITS DEFAULTS. A field left out of an encode
 * arrives at the node as ZERO, which is why `MeshtasticManager.setRegion` writes
 * every field of LoRaConfig explicitly (MeshtasticManager.swift:587-591: leaving
 * `use_preset` out sends `false`, switching the radio to manual bandwidth/spread
 * factor of 0 — a node that is configured, on the air, and unable to talk to
 * anyone). This writer emits exactly what it is told and nothing else; the
 * omit-defaults discipline lives at the call sites, as it does on iOS.
 *
 * All varint values are UNSIGNED 64-bit carried in a `Long`; every shift here is
 * logical (`ushr`) so the sign bit is never propagated.
 */
class ProtoWriter {

    private val out = ByteArrayOutputStream()

    /** The bytes written so far. */
    val data: ByteArray get() = out.toByteArray()

    /** Wire type 0. MeshtasticManager.swift:34-36 */
    fun varint(field: Int, value: Long): ProtoWriter {
        out.write(vint(key(field, 0)))
        out.write(vint(value))
        return this
    }

    /** Wire type 5, little-endian. MeshtasticManager.swift:37-40 */
    fun fixed32(field: Int, value: Int): ProtoWriter {
        out.write(vint(key(field, 5)))
        out.write(
            byteArrayOf(
                (value and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 24) and 0xFF).toByte(),
            )
        )
        return this
    }

    /** Wire type 5 taking an unsigned value. */
    fun fixed32(field: Int, value: UInt): ProtoWriter = fixed32(field, value.toInt())

    /** Wire type 2 (length-delimited). MeshtasticManager.swift:41-43 */
    fun bytes(field: Int, d: ByteArray): ProtoWriter {
        out.write(vint(key(field, 2)))
        out.write(vint(d.size.toLong()))
        out.write(d)
        return this
    }

    companion object {
        private fun key(field: Int, wire: Int): Long = ((field.toLong() shl 3) or wire.toLong())

        /**
         * Base-128 varint. MeshtasticManager.swift:29-33 — note the `repeat…while`,
         * so zero encodes as a single 0x00 byte rather than nothing at all.
         */
        fun vint(value: Long): ByteArray {
            var v = value
            val o = ByteArrayOutputStream()
            do {
                var b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v != 0L) b = b or 0x80
                o.write(b)
            } while (v != 0L)
            return o.toByteArray()
        }
    }
}

/** MeshtasticManager.swift:46 — one decoded field, whatever its wire type. */
data class ProtoField(
    val number: Int,
    val wire: Int,
    /** Wire 0 and wire 5 both land here; wire 5 is the little-endian value, zero-extended. */
    val varintValue: Long,
    /** Wire 1, 2 and 5 carry their raw bytes here. */
    val payload: ByteArray,
) {
    /** Float carried as a fixed32 bit pattern (SNR). MeshtasticManager.swift:928, 959 */
    fun asFloat(): Float = Float.fromBits(varintValue.toInt())

    /** Signed 32-bit reading of a fixed32 (latitude_i / longitude_i). MeshtasticManager.swift:923-924 */
    fun asInt32(): Int = varintValue.toInt()

    /** Unsigned 32-bit reading (node numbers, packet ids). */
    fun asUInt32(): UInt = varintValue.toUInt()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProtoField) return false
        return number == other.number && wire == other.wire &&
            varintValue == other.varintValue && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int =
        (((number * 31 + wire) * 31 + varintValue.hashCode()) * 31) + payload.contentHashCode()
}

/**
 * Port of `parseProtoFields` (MeshtasticManager.swift:48-84), including its
 * failure behaviour: a truncated field ends the parse and returns what was read
 * so far, and an unknown wire type stops the parse dead. Both are deliberate —
 * a radio that hands us half a frame must not take the connection down with it.
 */
object LoRaProto {

    fun parseFields(d: ByteArray): List<ProtoField> {
        val out = ArrayList<ProtoField>()
        var i = 0

        fun readVarint(): Long? {
            var v = 0L
            var shift = 0
            while (i < d.size) {
                val b = d[i].toInt() and 0xFF
                i += 1
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return v
                shift += 7
                if (shift > 63) return null   // :56
            }
            return null
        }

        while (i < d.size) {
            val key = readVarint() ?: break                      // :61
            val field = (key ushr 3).toInt()
            when ((key and 7L).toInt()) {
                0 -> {
                    val v = readVarint() ?: return out           // :65
                    out.add(ProtoField(field, 0, v, ByteArray(0)))
                }
                1 -> {                                          // :67-70 (64-bit)
                    if (d.size - i < 8) return out
                    val p = d.copyOfRange(i, i + 8); i += 8
                    out.add(ProtoField(field, 1, 0L, p))
                }
                2 -> {                                          // :71-74 (length-delimited)
                    val len = readVarint() ?: return out
                    if (len < 0 || d.size - i < len) return out
                    val p = d.copyOfRange(i, i + len.toInt()); i += len.toInt()
                    out.add(ProtoField(field, 2, 0L, p))
                }
                5 -> {                                          // :75-79 (fixed32, little-endian)
                    if (d.size - i < 4) return out
                    val p = d.copyOfRange(i, i + 4); i += 4
                    val v = ((p[0].toLong() and 0xFF)) or
                        ((p[1].toLong() and 0xFF) shl 8) or
                        ((p[2].toLong() and 0xFF) shl 16) or
                        ((p[3].toLong() and 0xFF) shl 24)
                    out.add(ProtoField(field, 5, v, p))
                }
                else -> return out                              // :80
            }
        }
        return out
    }

    /** First field with this number, or null. */
    fun field(d: ByteArray, number: Int, wire: Int? = null): ProtoField? =
        parseFields(d).firstOrNull { it.number == number && (wire == null || it.wire == wire) }
}
