package com.oshi.messenger.service.lora

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The app side of OSHI Mesh firmware (github.com/Lastoneparis/oshi-mesh-firmware) — Kotlin twin of
 * OSHI/OshiMeshRadio.swift, byte-for-byte. On a radio running OSHI Mesh the FIRMWARE fragments,
 * acknowledges, repairs and, when the destination is silent, parks a message in flash until that node
 * is heard again; the phone hands it a whole message and reads back what became of it.
 *
 * Wire: every frame is a PRIVATE_APP payload starting "OS" (the older chunking is "OM"), little-endian.
 *   DATA   "OS" 0x11 msgId u32 | origin u32 | dest u32 | idx u8 | count u8 | flags u8 | ≤182 bytes
 *   STATUS "OS" 0x16 msgId u32 | state u8 | node u32     (radio → its own phone only)
 *   BEACON "OS" 0x15 caps u8 | version u16 | freeKb u8   (also the capability probe)
 *   RADIO  "OS" 0x18 noise i8 | chUtil u8 | airTx u8 | rxGood u32 | rxBad u32 | txGood u32 | txDrop u16
 *          (radio → its own phone only, every 30 s while a phone is attached; 20 bytes, longer = newer
 *          fields appended. noise = average idle RSSI in dBm, -128 when the radio has no sample yet;
 *          chUtil = % of the last minute the channel was busy; airTx = % of the last hour we transmitted;
 *          counters are cumulative since boot. Feeds LoRaJammingDetector. See docs/omp/OMP-v1.md §4.7.)
 * Every fragment but the last is exactly 182 bytes; the firmware rejects anything else.
 * On a STOCK radio the phone speaks OMP itself: the same DATA frames, stamped with its own node number and
 * broadcast on the private OSHI channel, so OSHI Mesh nodes, stock relays and the MeshCore bridge all carry
 * one wire format. The older "OM" chunking is still received.
 */
object OshiMeshProtocol {
    const val MAGIC0: Byte = 0x4F
    const val MAGIC1: Byte = 0x53
    const val VERSION = 1
    const val DATA_HEADER = 18
    const val MAX_FRAG_DATA = 182
    const val MAX_FRAGS = 64
    const val MAX_MESSAGE = MAX_FRAG_DATA * MAX_FRAGS
    const val FLAG_CUSTODY_OK = 1
    const val FLAG_VIA_CUSTODY = 2

    enum class FrameType(val raw: Int) { DATA(1), SACK(2), CUSTODY(3), RECEIPT(4), BEACON(5), STATUS(6), PULL(7), RADIO(8) }

    enum class State(val raw: Int) {
        QUEUED(1), SENT(2), DELIVERED(3), IN_CUSTODY(4), FAILED(5), UPLINKED(6), REJECTED(7);
        companion object { fun of(raw: Int) = values().firstOrNull { it.raw == raw } }
    }

    /** SHA-256("OSHI Mesh channel v1"), identical to OSHI_CHANNEL_PSK in the firmware and to iOS. */
    const val CHANNEL_NAME = "OSHI"
    val CHANNEL_PSK: ByteArray = intArrayOf(
        0x29, 0x43, 0x6d, 0x19, 0xaf, 0x55, 0xbd, 0x4e, 0x20, 0x24, 0x73, 0x23, 0xc8, 0xff, 0x45, 0x57,
        0x86, 0xf0, 0x03, 0x11, 0x98, 0x17, 0xe1, 0x6c, 0xf7, 0xdf, 0x30, 0x03, 0x68, 0x4c, 0xb9, 0x50,
    ).map { it.toByte() }.toByteArray()

    data class DataFrame(
        val msgId: UInt, val origin: UInt, val dest: UInt, val idx: Int, val count: Int, val flags: Int,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?) = other is DataFrame && msgId == other.msgId && origin == other.origin &&
            dest == other.dest && idx == other.idx && count == other.count && flags == other.flags &&
            payload.contentEquals(other.payload)
        override fun hashCode() = (msgId.toInt() * 31 + idx) * 31 + payload.contentHashCode()
    }

    data class Status(val msgId: UInt, val state: State, val node: UInt)
    data class Beacon(val caps: Int, val version: Int, val custodyFreeKb: Int)

    /** __ANTI_JAMMING_PHASE1_2026_11_13__ The RADIO frame: what the radio measures about its channel. */
    data class RadioStats(
        /** dBm, or null when the radio has no idle-RSSI sample yet (sent as -128). */
        val noiseFloorDbm: Int?,
        val channelUtilPct: Int,
        val airUtilTxPct: Int,
        val rxGood: Long,
        val rxBad: Long,
        val txGood: Long,
        val txDrop: Int,
    )

    const val RADIO_LEN = 20
    const val RADIO_NOISE_UNKNOWN = -128

    private fun prefix(t: FrameType) = byteArrayOf(MAGIC0, MAGIC1, ((VERSION shl 4) or t.raw).toByte())
    private fun le32(v: UInt) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun u32(b: ByteArray, o: Int): UInt =
        (b[o].toUInt() and 0xFFu) or ((b[o + 1].toUInt() and 0xFFu) shl 8) or
            ((b[o + 2].toUInt() and 0xFFu) shl 16) or ((b[o + 3].toUInt() and 0xFFu) shl 24)

    fun typeOf(frame: ByteArray): FrameType? {
        if (frame.size < 3 || frame[0] != MAGIC0 || frame[1] != MAGIC1 || (frame[2].toInt() and 0xFF) shr 4 != VERSION) return null
        val t = frame[2].toInt() and 0x0F
        return FrameType.values().firstOrNull { it.raw == t }
    }

    /**
     * The message as DATA fragments, or null when it is larger than OMP carries (~11.6 KB).
     * [origin] 0 lets an OSHI Mesh radio stamp itself; a phone doing OMP itself (stock Meshtastic radio,
     * MeshCore radio) puts its own node number there, because nothing downstream will.
     */
    fun dataFrames(msgId: UInt, dest: UInt, body: ByteArray, flags: Int = 0, origin: UInt = 0u): List<ByteArray>? {
        val count = maxOf(1, (body.size + MAX_FRAG_DATA - 1) / MAX_FRAG_DATA)
        if (count > MAX_FRAGS) return null
        return (0 until count).map { i ->
            val lo = i * MAX_FRAG_DATA
            val hi = minOf(body.size, lo + MAX_FRAG_DATA)
            prefix(FrameType.DATA) + le32(msgId) + le32(origin) + le32(dest) +
                byteArrayOf(i.toByte(), count.toByte(), flags.toByte()) +
                (if (lo < hi) body.copyOfRange(lo, hi) else ByteArray(0))
        }
    }

    fun beaconProbe(): ByteArray = prefix(FrameType.BEACON) + byteArrayOf(0, 0, 0, 0)

    fun parseData(f: ByteArray): DataFrame? {
        if (typeOf(f) != FrameType.DATA || f.size < DATA_HEADER || f.size > DATA_HEADER + MAX_FRAG_DATA) return null
        val idx = f[15].toInt() and 0xFF
        val count = f[16].toInt() and 0xFF
        if (count == 0 || count > MAX_FRAGS || idx >= count) return null
        return DataFrame(u32(f, 3), u32(f, 7), u32(f, 11), idx, count, f[17].toInt() and 0xFF,
            f.copyOfRange(DATA_HEADER, f.size))
    }

    fun parseStatus(f: ByteArray): Status? {
        if (typeOf(f) != FrameType.STATUS || f.size < 12) return null
        val st = State.of(f[7].toInt() and 0xFF) ?: return null
        return Status(u32(f, 3), st, u32(f, 8))
    }

    fun parseBeacon(f: ByteArray): Beacon? {
        if (typeOf(f) != FrameType.BEACON || f.size < 7) return null
        return Beacon(f[3].toInt() and 0xFF, (f[4].toInt() and 0xFF) or ((f[5].toInt() and 0xFF) shl 8), f[6].toInt() and 0xFF)
    }

    fun parseRadio(f: ByteArray): RadioStats? {
        if (typeOf(f) != FrameType.RADIO || f.size < RADIO_LEN) return null
        val noise = f[3].toInt()   // signed
        return RadioStats(
            noiseFloorDbm = noise.takeIf { it != RADIO_NOISE_UNKNOWN },
            channelUtilPct = (f[4].toInt() and 0xFF).coerceAtMost(100),
            airUtilTxPct = (f[5].toInt() and 0xFF).coerceAtMost(100),
            rxGood = u32(f, 6).toLong(),
            rxBad = u32(f, 10).toLong(),
            txGood = u32(f, 14).toLong(),
            txDrop = (f[18].toInt() and 0xFF) or ((f[19].toInt() and 0xFF) shl 8),
        )
    }

    /** The firmware's encoder, mirrored so tests (and the iOS port) can pin the layout. */
    fun radioFrame(s: RadioStats): ByteArray =
        prefix(FrameType.RADIO) + byteArrayOf(
            (s.noiseFloorDbm ?: RADIO_NOISE_UNKNOWN).coerceIn(-128, 127).toByte(),
            s.channelUtilPct.coerceIn(0, 100).toByte(),
            s.airUtilTxPct.coerceIn(0, 100).toByte(),
        ) + le32(s.rxGood.toUInt()) + le32(s.rxBad.toUInt()) + le32(s.txGood.toUInt()) +
            byteArrayOf(s.txDrop.toByte(), (s.txDrop shr 8).toByte())

    /**
     * 0x01 raw, 0x02 deflate. RAW deflate (RFC 1951, `nowrap`), because that is what iOS's
     * `NSData.compressed(using: .zlib)` actually emits — a zlib-wrapped stream would not inflate there.
     */
    fun encodeBody(json: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(json); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        val z = out.toByteArray()
        return if (z.size < json.size) byteArrayOf(2) + z else byteArrayOf(1) + json
    }

    fun decodeBody(body: ByteArray): ByteArray? {
        if (body.isEmpty()) return null
        val rest = body.copyOfRange(1, body.size)
        return when (body[0].toInt()) {
            1 -> rest
            2 -> runCatching {
                val inf = Inflater(true)
                inf.setInput(rest)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(1024)
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    out.write(buf, 0, n)
                }
                inf.end()
                if (out.size() > MAX_MESSAGE * 8) null else out.toByteArray()
            }.getOrNull()
            else -> null
        }
    }
}

/** Reassembles DATA frames from the radio; a partial message never surfaces and expires. */
class OmpReassembler(private val ttlMs: Long = 300_000, private val maxEntries: Int = 8) {
    private class Entry(val count: Int, val parts: MutableMap<Int, ByteArray>, val started: Long)
    private val entries = LinkedHashMap<String, Entry>()

    fun accept(f: OshiMeshProtocol.DataFrame, nowMs: Long = System.currentTimeMillis()): ByteArray? {
        entries.entries.removeAll { nowMs - it.value.started >= ttlMs }
        val key = "${f.origin}:${f.msgId}"
        val e = entries[key] ?: Entry(f.count, HashMap(), nowMs).also {
            if (entries.size >= maxEntries) entries.remove(entries.keys.first())
            entries[key] = it
        }
        if (e.count != f.count) return null
        e.parts[f.idx] = f.payload
        if (e.parts.size != e.count) return null
        entries.remove(key)
        val out = ByteArrayOutputStream()
        for (i in 0 until e.count) out.write(e.parts[i] ?: return null)
        return out.toByteArray()
    }
}
