package com.oshi.messenger.service.lora

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hand-rolled Meshtastic protobuf codec, executed on the Android runtime.
 *
 * The wire format is already asserted byte-for-byte against iOS in
 * `app/src/test/.../LoRaProtoTest.kt` — but only ever on the desktop JVM. The
 * bytes a radio receives are produced by ART on an arm64 phone, and the encoder
 * leans on exactly the operations where a runtime is allowed to differ from a
 * naive reading of the source: logical vs. arithmetic right shift on a `Long`
 * (`ushr`), `Byte`-to-`Int` sign extension, unsigned `UInt` conversion, and
 * `Float.toRawBits` / `fromBits`.
 *
 * **This asserts the same vectors as the JVM test on purpose.** Its value is not
 * a new expectation, it is a new *runtime*: nothing in this app had ever run
 * inside an Android runtime before this source set existed. It does not change
 * the wire format and must never be "fixed" by changing an expected byte —
 * a failure here means Android and iOS have diverged on the air.
 */
@RunWith(AndroidJUnit4::class)
class LoRaProtoInstrumentedTest {

    @Test
    fun varints_are_the_canonical_vectors() {
        assertArrayEquals(byteArrayOf(0x00), ProtoWriter.vint(0))
        assertArrayEquals(byteArrayOf(0x01), ProtoWriter.vint(1))
        assertArrayEquals(byteArrayOf(0x7F), ProtoWriter.vint(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), ProtoWriter.vint(128))
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), ProtoWriter.vint(300))
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F),
            ProtoWriter.vint(0xFFFFFFFL)
        )
    }

    /**
     * The whole reason every shift in the writer is `ushr`. On a runtime that
     * sign-extended, `-1` would loop forever instead of producing ten bytes.
     */
    @Test
    fun a_negative_long_is_ten_unsigned_bytes_not_an_infinite_loop() {
        val encoded = ProtoWriter.vint(-1L)
        assertEquals(10, encoded.size)
        assertEquals(0x01, encoded[9].toInt() and 0xFF)
        for (i in 0..8) assertEquals(0xFF, encoded[i].toInt() and 0xFF)
    }

    @Test
    fun fixed32_is_little_endian_in_both_directions() {
        val bytes = ProtoWriter().fixed32(3, 0x01020304).data
        // key = (3 << 3) | 5 = 0x1D, then the value low byte first.
        assertArrayEquals(byteArrayOf(0x1D, 0x04, 0x03, 0x02, 0x01), bytes)

        val field = LoRaProto.parseFields(bytes).single()
        assertEquals(3, field.number)
        assertEquals(5, field.wire)
        assertEquals(0x01020304, field.asInt32())
    }

    /** SNR rides as a raw float bit pattern; ART must round-trip it unchanged. */
    @Test
    fun a_float_bit_pattern_survives_the_round_trip() {
        val snr = -7.25f
        val bytes = ProtoWriter().fixed32(11, snr.toRawBits()).data
        assertEquals(snr, LoRaProto.parseFields(bytes).single().asFloat(), 0.0f)
    }

    /** Node numbers are unsigned 32-bit; the top bit must not become a sign. */
    @Test
    fun a_node_number_above_two_billion_reads_back_unsigned() {
        val nodeNum = 0xDEADBEEFu
        val bytes = ProtoWriter().fixed32(1, nodeNum).data
        assertEquals(
            nodeNum.toLong(),
            LoRaProto.parseFields(bytes).single().asUInt32().toLong()
        )
    }

    @Test
    fun a_mixed_message_round_trips_field_for_field() {
        val payload = byteArrayOf(0x6F, 0x73, 0x68, 0x69) // "oshi"
        val bytes = ProtoWriter()
            .varint(1, 300)
            .bytes(2, payload)
            .fixed32(3, 0x01020304)
            .data

        val fields = LoRaProto.parseFields(bytes)
        assertEquals(3, fields.size)
        assertEquals(300L, fields[0].varintValue)
        assertArrayEquals(payload, fields[1].payload)
        assertEquals(0x01020304, fields[2].asInt32())
    }

    /**
     * A radio that hands over half a frame must not take the connection down:
     * the parse stops and keeps what it read.
     */
    @Test
    fun a_truncated_frame_keeps_what_was_read() {
        val whole = ProtoWriter().varint(1, 300).bytes(2, byteArrayOf(1, 2, 3, 4)).data
        val truncated = whole.copyOfRange(0, whole.size - 2)
        val fields = LoRaProto.parseFields(truncated)
        assertEquals(1, fields.size)
        assertEquals(300L, fields[0].varintValue)
    }
}
