package com.oshi.messenger.service.lora

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * OSHI messages over a MeshCore radio — Kotlin twin of the codec half of OSHI/MeshCoreRadio.swift,
 * byte-for-byte. Pure JVM (no android.*), so the desktop app carries an identical copy.
 *
 * A LoRa radio listens to one network at a time, and MeshCore is not Meshtastic. So OSHI rides MeshCore
 * the way oshi-meshcore-bridge does:
 *
 *   OMP DATA frame (OshiMeshProtocol.kt)     the same frames as on Meshtastic, origin = our id
 *     → "OB" envelope, split to ≤160 B        [OshiBridgeWire] == oshi_bridge/wire.py
 *     → MeshCore group-channel datagram       CMD_SEND_CHANNEL_DATA, data type 0xFF4F,
 *                                             channel "#oshi-bridge" (secret = SHA-256(name)[0:16])
 *
 * Companion protocol: MeshCore examples/companion_radio/MyMesh.cpp (BLE Nordic UART, one frame per
 * write/notify; USB serial / TCP wrap each frame in '<'/'>' + LE16 length). Requests are strictly one
 * at a time; PUSH codes ≥ 0x80 are unsolicited.
 */
object MeshCore {
    const val SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    const val RX_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"   // app → radio (write)
    const val TX_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"   // radio → app (notify)

    const val CMD_APP_START = 1
    const val CMD_SYNC_NEXT_MESSAGE = 10
    const val CMD_GET_CHANNEL = 31
    const val CMD_SET_CHANNEL = 32
    const val CMD_SEND_CHANNEL_DATA = 62

    const val RESP_OK = 0
    const val RESP_ERR = 1
    const val RESP_SELF_INFO = 5
    const val RESP_NO_MORE_MESSAGES = 10
    const val RESP_CHANNEL_INFO = 18
    const val RESP_CHANNEL_DATA_RECV = 27
    const val PUSH_MSG_WAITING = 0x83

    /** MAX_FRAME_SIZE (176) - 9 in the current firmware. */
    const val MAX_CHANNEL_DATA = 167
    const val CHANNEL_SLOTS = 8
    const val PATH_FLOOD = 0xFF

    fun appStart(appName: String = "OSHI"): ByteArray =
        byteArrayOf(CMD_APP_START.toByte()) + ByteArray(7) + appName.toByteArray(Charsets.UTF_8)

    fun getChannel(idx: Int): ByteArray = byteArrayOf(CMD_GET_CHANNEL.toByte(), idx.toByte())

    /** idx, name (32, NUL-padded, ≤31 bytes), secret (16). The firmware refuses a 32-byte secret. */
    fun setChannel(idx: Int, name: String, secret: ByteArray): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8).let { it.copyOfRange(0, minOf(31, it.size)) }
        return byteArrayOf(CMD_SET_CHANNEL.toByte(), idx.toByte()) + n + ByteArray(32 - n.size) +
            secret.copyOfRange(0, minOf(16, secret.size))
    }

    fun sendChannelData(idx: Int, dataType: Int, payload: ByteArray): ByteArray =
        byteArrayOf(
            CMD_SEND_CHANNEL_DATA.toByte(), idx.toByte(), PATH_FLOOD.toByte(),
            (dataType and 0xFF).toByte(), ((dataType shr 8) and 0xFF).toByte(),
        ) + payload

    val syncNextMessage: ByteArray get() = byteArrayOf(CMD_SYNC_NEXT_MESSAGE.toByte())

    /** RESP_CODE_SELF_INFO: code, adv type, tx power, max tx power, public key (32), ... */
    fun selfPublicKey(f: ByteArray): ByteArray? {
        if (f.size < 36 || (f[0].toInt() and 0xFF) != RESP_SELF_INFO) return null
        return f.copyOfRange(4, 36)
    }

    data class ChannelInfo(val idx: Int, val name: String, val secret: ByteArray) {
        override fun equals(other: Any?) =
            other is ChannelInfo && idx == other.idx && name == other.name && secret.contentEquals(other.secret)
        override fun hashCode() = (idx * 31 + name.hashCode()) * 31 + secret.contentHashCode()
    }

    /** RESP_CODE_CHANNEL_INFO: code, idx, name (32, NUL-padded), secret (16). */
    fun parseChannelInfo(f: ByteArray): ChannelInfo? {
        if (f.size < 50 || (f[0].toInt() and 0xFF) != RESP_CHANNEL_INFO) return null
        var end = 2
        while (end < 34 && f[end].toInt() != 0) end++
        return ChannelInfo(f[1].toInt() and 0xFF, String(f, 2, end - 2, Charsets.UTF_8), f.copyOfRange(34, 50))
    }

    data class ChannelData(val channelIdx: Int, val dataType: Int, val payload: ByteArray) {
        override fun equals(other: Any?) = other is ChannelData && channelIdx == other.channelIdx &&
            dataType == other.dataType && payload.contentEquals(other.payload)
        override fun hashCode() = (channelIdx * 31 + dataType) * 31 + payload.contentHashCode()
    }

    /** RESP_CODE_CHANNEL_DATA_RECV: code, snr×4, 0, 0, channel idx, path len, data type (LE16), len, data. */
    fun parseChannelData(f: ByteArray): ChannelData? {
        if (f.size < 9 || (f[0].toInt() and 0xFF) != RESP_CHANNEL_DATA_RECV) return null
        val len = f[8].toInt() and 0xFF
        if (f.size < 9 + len) return null
        return ChannelData(
            f[4].toInt() and 0xFF,
            (f[6].toInt() and 0xFF) or ((f[7].toInt() and 0xFF) shl 8),
            f.copyOfRange(9, 9 + len),
        )
    }

    /**
     * USB serial (and the WiFi companion's TCP stream) wrap every companion frame:
     * app → radio '<' (0x3C) + len LE16 + payload, radio → app '>' (0x3E) + len LE16 + payload.
     * BLE needs none of this — one write or notification is one frame.
     */
    object Serial {
        const val TO_RADIO = 0x3C
        const val FROM_RADIO = 0x3E
        const val HEADER_BYTES = 3
        /** Larger than any companion frame; a length beyond it means we are not on a header. */
        const val MAX_FRAME_BYTES = 256

        fun encode(payload: ByteArray, marker: Int = TO_RADIO): ByteArray =
            byteArrayOf(marker.toByte(), (payload.size and 0xFF).toByte(), ((payload.size shr 8) and 0xFF).toByte()) +
                payload

        class Decoded(val payload: ByteArray, val consumed: Int)

        /**
         * The first complete frame in [buffer], or null when more bytes are needed. Bytes before a
         * plausible header are skipped (and counted in `consumed`) so a stream that starts mid-frame resyncs.
         */
        fun decode(buffer: ByteArray, marker: Int = FROM_RADIO): Decoded? {
            var i = 0
            while (i + HEADER_BYTES <= buffer.size) {
                if ((buffer[i].toInt() and 0xFF) != marker) { i++; continue }
                val len = (buffer[i + 1].toInt() and 0xFF) or ((buffer[i + 2].toInt() and 0xFF) shl 8)
                if (len == 0 || len > MAX_FRAME_BYTES) { i++; continue }
                if (i + HEADER_BYTES + len > buffer.size) return null
                return Decoded(buffer.copyOfRange(i + HEADER_BYTES, i + HEADER_BYTES + len), i + HEADER_BYTES + len)
            }
            return null
        }
    }
}

/** The "OB" envelope of oshi-meshcore-bridge (oshi_bridge/wire.py). */
object OshiBridgeWire {
    const val MARKER0: Byte = 0x4F   // 'O'
    const val MARKER1: Byte = 0x42   // 'B'
    const val VERSION = 1
    const val HEADER_LEN = 9
    const val MAX_DATAGRAM = 160          // conservative vs MeshCore's 167
    const val DATA_TYPE = 0xFF4F          // MeshCore's unregistered dev range
    const val CHANNEL_NAME = "#oshi-bridge"
    val CHANNEL_SECRET: ByteArray =
        MessageDigest.getInstance("SHA-256").digest(CHANNEL_NAME.toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)

    class Part(val sender: UInt, val seq: Int, val part: Int, val total: Int, val chunk: ByteArray)

    /**
     * [sender] is the bridge field: whoever put the frame on MeshCore (a bridge's Meshtastic node number,
     * or our own MeshCore-derived id). Receivers ignore their own. Null beyond 15 parts.
     */
    fun split(frame: ByteArray, sender: UInt, seq: Int, maxDatagram: Int = MAX_DATAGRAM): List<ByteArray>? {
        val room = maxDatagram - HEADER_LEN
        val chunks = if (frame.isEmpty()) listOf(ByteArray(0))
            else (frame.indices step room).map { frame.copyOfRange(it, minOf(frame.size, it + room)) }
        if (chunks.size > 15) return null
        val head = byteArrayOf(
            MARKER0, MARKER1, (VERSION shl 4).toByte(),
            sender.toByte(), (sender shr 8).toByte(), (sender shr 16).toByte(), (sender shr 24).toByte(),
            seq.toByte(),
        )
        return chunks.mapIndexed { i, c -> head + byteArrayOf(((i shl 4) or chunks.size).toByte()) + c }
    }

    fun parse(d: ByteArray): Part? {
        if (d.size < HEADER_LEN || d[0] != MARKER0 || d[1] != MARKER1 || (d[2].toInt() and 0xFF) shr 4 != VERSION) return null
        val sender = (d[3].toUInt() and 0xFFu) or ((d[4].toUInt() and 0xFFu) shl 8) or
            ((d[5].toUInt() and 0xFFu) shl 16) or ((d[6].toUInt() and 0xFFu) shl 24)
        val part = (d[8].toInt() and 0xFF) shr 4
        val total = d[8].toInt() and 0x0F
        if (total == 0 || part >= total) return null
        return Part(sender, d[7].toInt() and 0xFF, part, total, d.copyOfRange(HEADER_LEN, d.size))
    }

    /** Multi-part envelopes, keyed by (sender, seq); incomplete sets expire. */
    class Joiner(private val timeoutMs: Long = 90_000, private val maxPending: Int = 128) {
        private class Pending(val total: Int, val first: Long, val chunks: HashMap<Int, ByteArray> = HashMap())
        private val pending = HashMap<String, Pending>()

        @Synchronized
        fun push(p: Part, nowMs: Long = System.currentTimeMillis()): ByteArray? {
            if (p.total == 1) return p.chunk
            pending.entries.removeAll { nowMs - it.value.first > timeoutMs }
            val key = "${p.sender}:${p.seq}"
            var e = pending[key]
            if (e == null || e.total != p.total) {
                if (pending.size >= maxPending) pending.entries.minByOrNull { it.value.first }?.let { pending.remove(it.key) }
                e = Pending(p.total, nowMs)
            }
            e.chunks[p.part] = p.chunk
            if (e.chunks.size != e.total) { pending[key] = e; return null }
            pending.remove(key)
            val out = ByteArrayOutputStream()
            for (i in 0 until e.total) out.write(e.chunks[i] ?: ByteArray(0))
            return out.toByteArray()
        }
    }
}

/**
 * The companion-protocol conversation with one MeshCore radio, transport-free: bytes in, commands out.
 * The BLE manager (Android) and the TCP link (desktop) only move bytes; setup, channel choice and the
 * receive path live here once, so both behave like iOS MeshCoreManager.
 *
 * Setup: APP_START → SELF_INFO (our id = first 4 bytes of the radio's public key, LE) → GET_CHANNEL 0..7.
 * "#oshi-bridge" found → ready. Otherwise the FIRST EMPTY slot > 0 is claimed with SET_CHANNEL; a channel
 * the owner configured is never overwritten (their MeshCore app would silently lose it). No empty slot →
 * [Event.NoFreeChannel] and the radio stays unusable for OSHI.
 */
class MeshCoreSession(private val appName: String = "OSHI") {
    enum class State { OFF, CONFIGURING, READY }

    sealed class Event {
        object Ready : Event()
        object NoFreeChannel : Event()
        /** An OMP DATA frame another sender put on "#oshi-bridge". */
        class Frame(val frame: ByteArray, val parsed: OshiMeshProtocol.DataFrame) : Event()
    }

    var state = State.OFF; private set
    /** The slot our "#oshi-bridge" channel lives in, or null while unknown / when every slot is taken. */
    var channelIdx: Int? = null; private set
    /** Our OMP origin / envelope sender on MeshCore. A collision with a Meshtastic node number only costs a reassembly key. */
    var nodeId: UInt = 0u; private set
    val isReady: Boolean get() = state == State.READY && channelIdx != null

    private val commands = ArrayDeque<ByteArray>()
    private var awaiting = false
    private var probing: Int? = null
    private var firstEmptySlot: Int? = null
    private var seq = 0
    private val joiner = OshiBridgeWire.Joiner()

    @Synchronized
    fun reset() {
        state = State.OFF; channelIdx = null; nodeId = 0u
        commands.clear(); awaiting = false; probing = null; firstEmptySlot = null
    }

    /** The link is up (BLE notifications on / socket open): introduce ourselves. */
    @Synchronized
    fun start() {
        reset()
        state = State.CONFIGURING
        commands.addLast(MeshCore.appStart(appName))
    }

    @Synchronized
    fun enqueue(cmd: ByteArray) { commands.addLast(cmd) }

    /** The next command to write, or null while one is still waiting for its reply. */
    @Synchronized
    fun nextCommand(): ByteArray? {
        if (awaiting || commands.isEmpty()) return null
        awaiting = true
        return commands.removeFirst()
    }

    /** A write that failed will never be answered: free the queue instead of wedging it. */
    @Synchronized
    fun writeFailed() { awaiting = false }

    /** One OMP frame as companion commands: "OB"-wrapped, split, SEND_CHANNEL_DATA each. Empty when not ready. */
    @Synchronized
    fun channelDatagrams(frame: ByteArray): List<ByteArray> {
        val idx = channelIdx ?: return emptyList()
        if (!isReady) return emptyList()
        seq = (seq + 1) and 0xFF
        val parts = OshiBridgeWire.split(frame, nodeId, seq) ?: return emptyList()
        return parts.map { MeshCore.sendChannelData(idx, OshiBridgeWire.DATA_TYPE, it) }
    }

    /** One frame from the radio. */
    @Synchronized
    fun onFrame(f: ByteArray, nowMs: Long = System.currentTimeMillis()): List<Event> {
        if (f.isEmpty()) return emptyList()
        val code = f[0].toInt() and 0xFF
        if (code >= 0x80) {
            if (code == MeshCore.PUSH_MSG_WAITING) commands.addLast(MeshCore.syncNextMessage)
            return emptyList()
        }
        awaiting = false
        val events = ArrayList<Event>(1)
        when {
            code == MeshCore.RESP_SELF_INFO -> {
                MeshCore.selfPublicKey(f)?.let { k ->
                    nodeId = (k[0].toUInt() and 0xFFu) or ((k[1].toUInt() and 0xFFu) shl 8) or
                        ((k[2].toUInt() and 0xFFu) shl 16) or ((k[3].toUInt() and 0xFFu) shl 24)
                }
                probing = 0
                commands.addLast(MeshCore.getChannel(0))
            }
            code == MeshCore.RESP_CHANNEL_INFO ->
                MeshCore.parseChannelInfo(f)?.let { channelProbed(it, events) }
            code == MeshCore.RESP_ERR && probing != null -> {
                // A slot the firmware does not have ends the scan like an unknown one.
                probing = MeshCore.CHANNEL_SLOTS
                channelProbed(null, events)
            }
            code == MeshCore.RESP_OK && state == State.CONFIGURING && channelIdx != null -> becomeReady(events)
            code == MeshCore.RESP_CHANNEL_DATA_RECV -> {
                MeshCore.parseChannelData(f)?.let { receive(it, nowMs)?.let(events::add) }
                commands.addLast(MeshCore.syncNextMessage)
            }
            code == MeshCore.RESP_NO_MORE_MESSAGES -> Unit
            // Contact / channel text that is not ours: keep the radio's queue moving.
            code == 7 || code == 8 || code == 16 || code == 17 -> commands.addLast(MeshCore.syncNextMessage)
        }
        return events
    }

    private fun channelProbed(info: MeshCore.ChannelInfo?, events: MutableList<Event>) {
        if (info != null) {
            if (info.name == OshiBridgeWire.CHANNEL_NAME && info.secret.contentEquals(OshiBridgeWire.CHANNEL_SECRET)) {
                probing = null
                channelIdx = info.idx
                becomeReady(events)
                return
            }
            if (info.name.isEmpty() && info.idx > 0 && firstEmptySlot == null) firstEmptySlot = info.idx
            val next = info.idx + 1
            if (next < MeshCore.CHANNEL_SLOTS) { probing = next; commands.addLast(MeshCore.getChannel(next)); return }
        }
        probing = null
        val slot = firstEmptySlot ?: run { events.add(Event.NoFreeChannel); return }
        channelIdx = slot
        commands.addLast(MeshCore.setChannel(slot, OshiBridgeWire.CHANNEL_NAME, OshiBridgeWire.CHANNEL_SECRET))
    }

    private fun becomeReady(events: MutableList<Event>) {
        if (state == State.READY) return
        state = State.READY
        commands.addLast(MeshCore.syncNextMessage)   // anything the radio queued while we were away
        events.add(Event.Ready)
    }

    private fun receive(d: MeshCore.ChannelData, nowMs: Long): Event? {
        if (d.channelIdx != channelIdx || d.dataType != OshiBridgeWire.DATA_TYPE) return null
        val part = OshiBridgeWire.parse(d.payload) ?: return null
        if (part.sender == nodeId) return null
        val frame = joiner.push(part, nowMs) ?: return null
        if (OshiMeshProtocol.typeOf(frame) != OshiMeshProtocol.FrameType.DATA) return null
        val f = OshiMeshProtocol.parseData(frame) ?: return null
        return Event.Frame(frame, f)
    }
}

/**
 * The dedup of the phone-as-bridge (iOS LoRaCrossBridge): when a device holds a Meshtastic/OSHI radio AND a
 * MeshCore radio, every broadcast OMP DATA frame heard on one side is repeated on the other, once.
 *
 * Keyed on msgId:idx:count, NOT on origin: an OSHI Mesh radio re-stamps the origin of what its phone hands
 * it but keeps the msgId, so an origin key would let two bridging phones ping-pong a frame. Only broadcast
 * DATA: unicast OMP belongs to the firmware's SACK/receipt machinery, which repeated fragments would confuse.
 */
class CrossBridgeDedup(private val ttlMs: Long = 30 * 60_000L, private val maxSeen: Int = 2048) {
    private val seen = HashMap<String, Long>()

    @Synchronized
    fun firstTime(f: OshiMeshProtocol.DataFrame, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (f.dest != BROADCAST) return false
        val key = "${f.msgId}:${f.idx}:${f.count}"
        seen[key]?.let { if (nowMs - it < ttlMs) return false }
        if (seen.size >= maxSeen) seen.entries.removeAll { nowMs - it.value >= ttlMs }
        if (seen.size >= maxSeen) seen.entries.minByOrNull { it.value }?.let { seen.remove(it.key) }
        seen[key] = nowMs
        return true
    }

    companion object {
        const val BROADCAST: UInt = 0xFFFFFFFFu
    }
}
