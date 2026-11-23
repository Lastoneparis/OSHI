package com.oshi.messenger.network.v2.devsync

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Framing of docs/OSHI_DEVICE_SYNC_DIRECT.md §5–§6. Golden vectors: docs/fixtures/devsync/framing.json.
 *
 *  - LAN (TCP): `u16be length || Noise message` (≤ 65 535).
 *  - Relay (one WebSocket binary message): `[16-byte deviceId][u8 kind][Noise message]`.
 *    Client→relay carries the DESTINATION id, relay→client the SOURCE id (the relay rewrites it).
 *    kind 1/2/3 = handshake message 1/2/3, 4 = transport. (The kind byte lets a responder tell a
 *    peer's fresh message 1 from a transport message of a stale session; LAN needs no kind: one
 *    TCP connection is one session.)
 *  - Record (the plaintext of one transport message): `u8 type | u8 flags | u32be seq | body`.
 *    flags bit0 = CONTINUED: the body continues in the next record of the same type.
 */
object DevSyncWire {

    const val KIND_MSG1 = 1
    const val KIND_MSG2 = 2
    const val KIND_MSG3 = 3
    const val KIND_TRANSPORT = 4

    const val RECORD_HEADER = 6
    const val MAX_RECORD = Noise.MAX_MESSAGE - Noise.TAG              // 65 519
    const val MAX_BODY = MAX_RECORD - RECORD_HEADER                    // 65 513
    const val MEDIA_CHUNK_DATA = 60 * 1024
    const val MEDIA_CHUNK_HEADER = 32 + 8 + 1
    /** Reassembly cap for a CONTINUED record chain. */
    const val MAX_REASSEMBLED = 8 * 1024 * 1024

    const val FLAG_CONTINUED = 0x01

    // Record types (§6).
    const val HELLO = 0x01
    const val ACK = 0x02
    const val DEVICES = 0x03
    const val APPROVAL = 0x04
    const val SUMMARY_REQ = 0x10
    const val SUMMARY = 0x11
    const val BUCKETS_REQ = 0x12
    const val BUCKETS = 0x13
    const val IDS_REQ = 0x14
    const val IDS = 0x15
    const val WANT = 0x16
    const val MESSAGES = 0x17
    const val CONTACTS = 0x18
    const val PROFILE = 0x19
    const val READ_STATE = 0x1A
    const val LIVE = 0x1B
    const val GROUPS = 0x1C
    const val MEDIA_GET = 0x20
    const val MEDIA_CHUNK = 0x21
    const val MEDIA_ERR = 0x22
    const val BYE = 0x7F

    /** Records outside the credit window: never counted, never acknowledged. */
    fun isControl(type: Int) = type == ACK || type == BYE

    data class Record(val type: Int, val flags: Int, val seq: Long, val body: ByteArray) {
        val continued: Boolean get() = (flags and FLAG_CONTINUED) != 0
        override fun equals(other: Any?) = other is Record && type == other.type && flags == other.flags &&
            seq == other.seq && body.contentEquals(other.body)
        override fun hashCode() = ((type * 31 + flags) * 31 + seq.hashCode()) * 31 + body.contentHashCode()
    }

    fun encodeRecord(type: Int, flags: Int, seq: Long, body: ByteArray): ByteArray {
        require(type in 0..255 && flags in 0..255 && seq in 0..0xFFFFFFFFL)
        require(body.size <= MAX_BODY) { "record body too large" }
        val out = ByteArray(RECORD_HEADER + body.size)
        out[0] = type.toByte()
        out[1] = flags.toByte()
        putU32(out, 2, seq)
        System.arraycopy(body, 0, out, RECORD_HEADER, body.size)
        return out
    }

    fun decodeRecord(b: ByteArray): Record {
        if (b.size < RECORD_HEADER) throw NoiseException("record shorter than its header")
        return Record(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF, getU32(b, 2), b.copyOfRange(RECORD_HEADER, b.size))
    }

    /** Split a body that does not fit one record into CONTINUED fragments. */
    fun fragment(body: ByteArray, maxBody: Int = MAX_BODY): List<Pair<Int, ByteArray>> {
        if (body.size <= maxBody) return listOf(0 to body)
        val out = ArrayList<Pair<Int, ByteArray>>()
        var off = 0
        while (off < body.size) {
            val n = minOf(maxBody, body.size - off)
            val last = off + n == body.size
            out += (if (last) 0 else FLAG_CONTINUED) to body.copyOfRange(off, off + n)
            off += n
        }
        return out
    }

    /** Reassembles CONTINUED chains. Returns the full body when a chain completes, else null. */
    class Reassembler {
        private var type = -1
        private val buf = ByteArrayOutputStream()

        fun push(r: Record): ByteArray? {
            if (type != -1 && r.type != type) throw NoiseException("interleaved fragmented record")
            if (!r.continued && type == -1) return r.body
            type = r.type
            buf.write(r.body)
            if (buf.size() > MAX_REASSEMBLED) throw NoiseException("reassembled record too large")
            if (r.continued) return null
            val all = buf.toByteArray()
            buf.reset()
            type = -1
            return all
        }
    }

    // ------------------------------------------------------------------ LAN

    fun lanFrame(noise: ByteArray): ByteArray {
        require(noise.size <= Noise.MAX_MESSAGE)
        val out = ByteArray(2 + noise.size)
        out[0] = (noise.size ushr 8).toByte()
        out[1] = noise.size.toByte()
        System.arraycopy(noise, 0, out, 2, noise.size)
        return out
    }

    fun writeLanFrame(out: OutputStream, noise: ByteArray) {
        out.write(lanFrame(noise))
    }

    /** Reads one LAN frame; throws [EOFException] at a clean end of stream. */
    fun readLanFrame(input: InputStream): ByteArray {
        val din = if (input is DataInputStream) input else DataInputStream(input)
        val hi = din.read()
        if (hi < 0) throw EOFException()
        val lo = din.read()
        if (lo < 0) throw EOFException()
        val len = (hi shl 8) or lo
        val buf = ByteArray(len)
        din.readFully(buf)
        return buf
    }

    // ------------------------------------------------------------------ relay

    fun relayFrame(deviceId: ByteArray, kind: Int, noise: ByteArray): ByteArray {
        require(deviceId.size == DevSyncKeys.DEVICE_ID_BYTES)
        require(kind in 1..4 && noise.size <= Noise.MAX_MESSAGE)
        val out = ByteArray(DevSyncKeys.DEVICE_ID_BYTES + 1 + noise.size)
        System.arraycopy(deviceId, 0, out, 0, DevSyncKeys.DEVICE_ID_BYTES)
        out[DevSyncKeys.DEVICE_ID_BYTES] = kind.toByte()
        System.arraycopy(noise, 0, out, DevSyncKeys.DEVICE_ID_BYTES + 1, noise.size)
        return out
    }

    data class RelayFrame(val deviceId: ByteArray, val kind: Int, val noise: ByteArray) {
        val deviceIdHex: String get() = DevSyncCrypto.hex(deviceId)
    }

    fun parseRelayFrame(b: ByteArray): RelayFrame? {
        if (b.size < DevSyncKeys.DEVICE_ID_BYTES + 1) return null
        val kind = b[DevSyncKeys.DEVICE_ID_BYTES].toInt() and 0xFF
        if (kind !in 1..4) return null
        return RelayFrame(
            b.copyOfRange(0, DevSyncKeys.DEVICE_ID_BYTES), kind,
            b.copyOfRange(DevSyncKeys.DEVICE_ID_BYTES + 1, b.size),
        )
    }

    // ------------------------------------------------------------------ media chunk

    data class MediaChunk(val sha256: ByteArray, val offset: Long, val last: Boolean, val data: ByteArray)

    fun encodeMediaChunk(sha256: ByteArray, offset: Long, last: Boolean, data: ByteArray): ByteArray {
        require(sha256.size == 32 && data.size <= MEDIA_CHUNK_DATA && offset >= 0)
        val out = ByteArray(MEDIA_CHUNK_HEADER + data.size)
        System.arraycopy(sha256, 0, out, 0, 32)
        for (i in 0 until 8) out[32 + i] = (offset ushr (56 - 8 * i)).toByte()
        out[40] = if (last) 1 else 0
        System.arraycopy(data, 0, out, MEDIA_CHUNK_HEADER, data.size)
        return out
    }

    fun decodeMediaChunk(b: ByteArray): MediaChunk {
        if (b.size < MEDIA_CHUNK_HEADER) throw NoiseException("media chunk too short")
        var off = 0L
        for (i in 0 until 8) off = (off shl 8) or (b[32 + i].toLong() and 0xFF)
        return MediaChunk(b.copyOfRange(0, 32), off, b[40].toInt() != 0, b.copyOfRange(MEDIA_CHUNK_HEADER, b.size))
    }

    // ------------------------------------------------------------------ helpers

    private fun putU32(b: ByteArray, at: Int, v: Long) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    private fun getU32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
}
