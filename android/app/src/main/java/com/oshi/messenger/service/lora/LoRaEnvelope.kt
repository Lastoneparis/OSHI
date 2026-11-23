package com.oshi.messenger.service.lora

import java.io.ByteArrayOutputStream

/**
 * The OSHI envelope frame that rides Meshtastic PRIVATE_APP (portnum 256).
 *
 * THIS IS THE INTEROP-CRITICAL PART. An iPhone and an Android phone talking over
 * the same LoRa mesh agree on nothing except these bytes.
 *
 *     [0x4F 0x4D]  "OM" magic          MeshtasticManager.swift:521
 *     msgId        UInt32 LITTLE-ENDIAN MeshtasticManager.swift:522-523
 *     seq          UInt8               MeshtasticManager.swift:524
 *     total        UInt8               MeshtasticManager.swift:524
 *     chunk        up to 180 bytes     MeshtasticManager.swift:511-513, :171
 *
 * Header is therefore exactly 8 bytes, and the receiver requires
 * `payload.count > 8` — a frame with an empty chunk is rejected
 * (MeshtasticManager.swift:1063).
 *
 * Everything in this file is pure: no Android, no BLE, no coroutines. That is
 * what makes the byte layout testable on the JVM against a literal fixture, and
 * a framing that is not tested is a framing that stops agreeing with iOS.
 */
object LoRaEnvelope {

    /** MeshtasticManager.swift:521 — "OM". */
    const val MAGIC_0: Byte = 0x4F
    const val MAGIC_1: Byte = 0x4D

    /** magic(2) + msgId(4) + seq(1) + total(1). MeshtasticManager.swift:521-524 */
    const val HEADER_BYTES = 8

    /** Payload bytes per LoRa frame; the frame budget is ~237. MeshtasticManager.swift:171 */
    const val CHUNK_BYTES = 180

    /**
     * ~2 KB cap. The radio's TX queue is shallow (~16-32) and LongFast airtime is
     * ~1-2 s/frame, so a larger send overflows the queue and dies in reassembly.
     * MeshtasticManager.swift:514-519.
     */
    const val MAX_CHUNKS = 12

    /**
     * The RECEIVER is more permissive than the sender on purpose: it must cope
     * with whatever a peer (or a future build) put on the air, but a `total`
     * beyond this is a corrupt frame, not a big message.
     * MeshtasticManager.swift:1068.
     */
    const val MAX_TOTAL = 40

    /** Partials older than this are dropped. MeshtasticManager.swift:1073-1074 */
    const val REASSEMBLY_TTL_MS = 180_000L

    /** Send pacing: ~one frame every 2 s on LongFast. MeshtasticManager.swift:819. */
    const val SEND_SPACING_SECONDS = 2

    /** Frames a wire payload of [wireBytes] occupies — the same chunking [chunk] uses. */
    fun frameCount(wireBytes: Int): Int =
        maxOf(1, (maxOf(0, wireBytes) + CHUNK_BYTES - 1) / CHUNK_BYTES)

    /** Airtime for [frames] at [SEND_SPACING_SECONDS] per frame, in whole seconds. */
    fun airtimeSeconds(frames: Int): Int = maxOf(0, frames) * SEND_SPACING_SECONDS

    /**
     * Compose-time estimate from a raw media size, before the message is built.
     * [wireFactor] folds the JSON envelope + base64 expansion the encrypted
     * payload picks up; it is deliberately conservative so the figure shown is
     * never rosier than the real send. The returned `fits` mirrors the
     * [MAX_CHUNKS] ceiling but is only a prediction — the true refusal ([frames]
     * returning null) runs on the real wire bytes and remains the authority.
     *
     * @return Triple(frames, seconds, fits)
     */
    fun mediaEstimate(mediaBytes: Int, wireFactor: Double = 1.9): Triple<Int, Int, Boolean> {
        val wire = Math.ceil(maxOf(0, mediaBytes) * wireFactor).toInt()
        val frames = frameCount(wire)
        return Triple(frames, airtimeSeconds(frames), frames <= MAX_CHUNKS)
    }

    /** Split at exactly [CHUNK_BYTES]. MeshtasticManager.swift:511-513 */
    fun chunk(json: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < json.size) {
            val end = minOf(i + CHUNK_BYTES, json.size)
            out.add(json.copyOfRange(i, end))
            i = end
        }
        return out
    }

    /** One frame. MeshtasticManager.swift:520-525 */
    fun frame(msgId: UInt, seq: Int, total: Int, chunk: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(HEADER_BYTES + chunk.size)
        o.write(MAGIC_0.toInt())
        o.write(MAGIC_1.toInt())
        val id = msgId.toInt()
        o.write(id and 0xFF)                 // little-endian, :522-523
        o.write((id ushr 8) and 0xFF)
        o.write((id ushr 16) and 0xFF)
        o.write((id ushr 24) and 0xFF)
        o.write(seq and 0xFF)
        o.write(total and 0xFF)
        o.write(chunk)
        return o.toByteArray()
    }

    /**
     * Every frame for one message, or null when it needs more than [MAX_CHUNKS] —
     * the refusal at MeshtasticManager.swift:514-519. Null is "refused", which
     * the caller must surface to the user; iOS records it as `.tooLarge`.
     */
    fun frames(msgId: UInt, json: ByteArray): List<ByteArray>? {
        val chunks = chunk(json)
        if (chunks.size > MAX_CHUNKS) return null
        return chunks.mapIndexed { i, c -> frame(msgId, i, chunks.size, c) }
    }

    data class Header(val msgId: UInt, val seq: Int, val total: Int, val chunk: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Header) return false
            return msgId == other.msgId && seq == other.seq && total == other.total &&
                chunk.contentEquals(other.chunk)
        }

        override fun hashCode(): Int =
            (((msgId.hashCode() * 31 + seq) * 31 + total) * 31) + chunk.contentHashCode()
    }

    /**
     * Validate and split a received frame, or null when it is not ours / is
     * malformed. MeshtasticManager.swift:1062-1068.
     */
    fun parse(payload: ByteArray): Header? {
        // `> 8`, not `>= 8`: a frame with no chunk bytes carries nothing. :1063
        if (payload.size <= HEADER_BYTES) return null
        if (payload[0] != MAGIC_0 || payload[1] != MAGIC_1) return null
        val msgId = ((payload[2].toLong() and 0xFF) or
            ((payload[3].toLong() and 0xFF) shl 8) or
            ((payload[4].toLong() and 0xFF) shl 16) or
            ((payload[5].toLong() and 0xFF) shl 24)).toUInt()
        val seq = payload[6].toInt() and 0xFF
        val total = payload[7].toInt() and 0xFF
        // :1068 — total > 0, seq < total, total <= 40.
        if (total <= 0 || seq >= total || total > MAX_TOTAL) return null
        return Header(msgId, seq, total, payload.copyOfRange(HEADER_BYTES, payload.size))
    }
}

/**
 * Reassembles "OM" frames into whole envelopes.
 *
 * Port of `MeshtasticManager.handleOshiChunk` (MeshtasticManager.swift:1062-1080),
 * including the details that decide whether two platforms agree:
 *  • `total` is taken from the FIRST frame seen for a message id (:1070); a later
 *    frame claiming a different total does not re-open the entry.
 *  • a duplicate `seq` OVERWRITES and does not advance the count (:1071).
 *  • the GC sweep runs on every accept, AFTER the insert, so a fresh entry always
 *    survives its own arrival (:1074).
 *  • a complete set is removed before the parts are concatenated in order (:1077-1079).
 *
 * Not thread-safe by itself; the manager confines it to its own scope, as iOS
 * confines it to the main queue.
 */
class LoRaReassembler(private val ttlMs: Long = LoRaEnvelope.REASSEMBLY_TTL_MS) {

    private class Entry(val total: Int, val startedAtMs: Long) {
        val parts = HashMap<Int, ByteArray>()
    }

    private val pending = HashMap<UInt, Entry>()

    /** Partial messages currently held. Exposed for tests and diagnostics. */
    val pendingCount: Int get() = pending.size

    /**
     * @return the fully reassembled envelope bytes, or null while parts are
     *         still missing / the frame was rejected.
     */
    fun accept(payload: ByteArray, nowMs: Long): ByteArray? {
        val h = LoRaEnvelope.parse(payload) ?: return null

        val entry = pending.getOrPut(h.msgId) { Entry(h.total, nowMs) }
        entry.parts[h.seq] = h.chunk

        // GC stale partials (>3 min). :1073-1074
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value.startedAtMs >= ttlMs) it.remove()
        }

        if (entry.parts.size != entry.total) return null    // :1076
        pending.remove(h.msgId)                             // :1077

        val out = ByteArrayOutputStream()
        for (i in 0 until entry.total) {
            val part = entry.parts[i] ?: return null        // :1079 — a hole is not a message
            out.write(part)
        }
        return out.toByteArray()
    }

    fun clear() = pending.clear()
}
