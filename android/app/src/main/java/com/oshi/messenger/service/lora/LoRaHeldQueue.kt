package com.oshi.messenger.service.lora

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * __LORA_HELD_DURABLE_2026_11_18__ Envelopes waiting for the paired radio: out of Bluetooth range,
 * firmware probe still pending, channel jammed, or "internet sending off" with the radio skipped.
 *
 * THE BUGS THIS REPLACES (audit 18/11, `MeshtasticManager.kt:485,1042-1060`):
 *  - an `ArrayDeque` in memory only: a force-quit or a process death lost every held message;
 *  - capped at 20 with `removeFirst()`: the 21st held message silently pushed out the oldest;
 *  - touched from Dispatchers.IO (send path), the GATT callback and the jamming tick with no lock,
 *    and flushed as `toList()` then `clear()`: anything added between the two was gone.
 *
 * Now: one lock around every read and write; persisted to a small file on every change (the bytes
 * are the radio wire JSON, i.e. `meshWireSafe()` ciphertext, never plaintext); an item leaves only
 * when it was SENT, when the caller says it is no longer wanted, or when it is reported back as
 * undeliverable — eviction past [capacity] returns the evicted ids so the caller marks those rows
 * failed on screen instead of losing them in silence.
 *
 * Pure JVM (no Android types) so `LoRaHeldQueueTest` drives it directly, threads included.
 */
class LoRaHeldQueue(
    private val store: Store? = null,
    val capacity: Int = DEFAULT_CAPACITY,
) {
    /** Where the queue survives the process. [read] returns null when nothing was ever written. */
    interface Store {
        fun read(): String?
        fun write(text: String)
    }

    /** A file next to the app's other private state, written atomically (temp + rename). */
    class FileStore(private val file: File) : Store {
        override fun read(): String? = runCatching { if (file.exists()) file.readText() else null }.getOrNull()
        override fun write(text: String) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }

    class Held(
        val id: String,
        val recipientPublicKey: String,
        val wireJson: ByteArray,
        val heldAtMs: Long,
    )

    private val lock = Any()
    /** Insertion order = send order; keyed by message id so a retry of the same message is one entry. */
    private val items = LinkedHashMap<String, Held>()

    init {
        load()
    }

    /**
     * Hold [wireJson] for message [id]. Returns the ids EVICTED to stay within [capacity] (oldest
     * first) — never silently dropped: the caller must surface each as not delivered.
     */
    fun add(id: String, recipientPublicKey: String, wireJson: ByteArray, nowMs: Long): List<String> =
        synchronized(lock) {
            val existing = items.remove(id)
            items[id] = Held(id, recipientPublicKey, wireJson, existing?.heldAtMs ?: nowMs)
            val evicted = ArrayList<String>()
            while (items.size > capacity) {
                val oldest = items.keys.first()
                items.remove(oldest)
                evicted += oldest
            }
            persistLocked()
            evicted
        }

    /** A copy, in send order. Iterating it never races a concurrent [add] or [remove]. */
    fun snapshot(): List<Held> = synchronized(lock) { items.values.toList() }

    fun remove(id: String): Boolean = synchronized(lock) {
        val removed = items.remove(id) != null
        if (removed) persistLocked()
        removed
    }

    fun contains(id: String): Boolean = synchronized(lock) { items.containsKey(id) }

    val size: Int get() = synchronized(lock) { items.size }

    fun isEmpty(): Boolean = size == 0

    private fun persistLocked() {
        val s = store ?: return
        val arr = JSONArray()
        for (h in items.values) {
            arr.put(JSONObject().apply {
                put("id", h.id)
                put("to", h.recipientPublicKey)
                put("at", h.heldAtMs)
                put("wire", Base64.getEncoder().encodeToString(h.wireJson))
            })
        }
        s.write(arr.toString())
    }

    private fun load() {
        val text = store?.read() ?: return
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return
        synchronized(lock) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val wire = runCatching { Base64.getDecoder().decode(o.optString("wire", "")) }.getOrNull()
                if (id.isEmpty() || wire == null || wire.isEmpty()) continue
                items[id] = Held(id, o.optString("to", ""), wire, o.optLong("at", 0L))
            }
        }
    }

    companion object {
        /**
         * Far above anything a person types while their radio is away, and small enough that the file
         * stays a few hundred KB at worst. Reaching it is reported, never silent (see [add]).
         */
        const val DEFAULT_CAPACITY = 200
        const val FILE_NAME = "lora_held_queue.json"
    }
}

/**
 * __LORA_PROBE_BEFORE_FLUSH_2026_11_18__ When a message may go to the radio NOW, and when what the
 * radio holds may go out. Pure, so `LoRaHeldQueueTest` pins the ordering without a radio.
 *
 * THE BUG: on config-complete the app sent the OSHI Mesh probe and flushed the held messages in the
 * same breath (`MeshtasticManager.kt:1442-1443`, iOS `:1783-1784`). The probe answer had not arrived,
 * so `firmwareFamily` still said MESHTASTIC and every held message took the phone-side 12-frame stock
 * path — refused as too large past ~2 KB, or sent the slow way on an OSHI Mesh radio.
 */
object LoRaRadioGate {
    /**
     * The firmware question is settled: the radio answered the probe (OSHI Mesh), or the probe had
     * its chance and nothing answered (stock).
     */
    fun firmwareKnown(probeDone: Boolean, answeredOshi: Boolean): Boolean = probeDone || answeredOshi

    /** Why a message for a READY radio is held instead of sent, or null to send now. */
    fun holdReason(probeDone: Boolean, answeredOshi: Boolean, excludedForJamming: Boolean): String? = when {
        excludedForJamming -> "radio channel looks jammed"
        !firmwareKnown(probeDone, answeredOshi) -> "waiting for the radio's firmware probe"
        else -> null
    }

    fun mayFlush(ready: Boolean, probeDone: Boolean, answeredOshi: Boolean, excludedForJamming: Boolean): Boolean =
        ready && !excludedForJamming && firmwareKnown(probeDone, answeredOshi)

    /**
     * Capacity of the path a held envelope will take. OSHI Mesh firmware: the whole OMP body, 64 x 182.
     * Otherwise the phone's own stock path, [LoRaEnvelope.MAX_CHUNKS] frames. [ompBody] is
     * `OshiMeshProtocol.encodeBody(wireJson)` (deflated when that is smaller).
     */
    fun fits(ompBody: ByteArray, oshiFirmware: Boolean): Boolean {
        val frames = (ompBody.size + OshiMeshProtocol.MAX_FRAG_DATA - 1) / OshiMeshProtocol.MAX_FRAG_DATA
        return if (oshiFirmware) frames <= OshiMeshProtocol.MAX_FRAGS else frames <= LoRaEnvelope.MAX_CHUNKS
    }
}
