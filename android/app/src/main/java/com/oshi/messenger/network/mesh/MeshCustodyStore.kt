package com.oshi.messenger.network.mesh

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * __MESH_CUSTODY_2026_10_10__ Store-and-forward AT A RELAY NODE.
 *
 * Port of `OSHI/MeshCustodyStore.swift`. The owner's ask, verbatim: "Nobody has
 * Internet. OSHI → BLE → BLE → BLE → store-and-forward → recipient."
 *
 * The multi-hop half of that already worked on both platforms. The
 * store-and-forward half did not exist on either: [MeshNetworkManager.forwardRelayMessage]
 * forwards a message NOW or drops it. Specifically, when the fall-through
 * broadcast's target list — `_connectedPeers` minus the endpoint it came from
 * minus everybody already in `seenBy` — comes back EMPTY, `forEach` runs zero
 * times, `sendPayload` is never called and the packet is discarded in silence.
 * (`broadcastRelayMessage` logged "Broadcast relay to 0 peer(s)" and even that
 * was a lie: `sentCount` is incremented inside `addOnSuccessListener`, so it is
 * read before any send has reported.) So the chain only ever worked when EVERY
 * hop was awake simultaneously — walk out of range for thirty seconds and the
 * message was gone.
 *
 * This is the missing middle term: a node that cannot pass a message on right
 * now takes custody of it and re-offers it when a peer appears.
 *
 * WHAT IT IS CARRYING, and the limits that follow. Held payloads are other
 * people's traffic. They are already ciphertext — a [RelayMessage] carries
 * `encryptedPayload`, and this node has no key for it — but it is still
 * somebody else's data on the user's phone, costing their storage, so every
 * bound here is deliberate: bounded count, bounded bytes, a TTL, oldest-first
 * eviction, and release the moment it has been handed on.
 *
 * It is store-and-FORWARD, not store-and-confirm. Custody ends when the message
 * has been passed to a peer that was not already in its path. There is no
 * app-level ack on the mesh to wait for (see the [MessageType] note on `ACK`:
 * iOS has no ACK concept on this transport at all), and inventing one here
 * would mean holding strangers' data indefinitely on the strength of a guess.
 *
 * THREAD SAFETY. Nearby Connections delivers on its own callback threads while
 * the manager's coroutine scope flushes, so every mutation of [held] is inside
 * `synchronized(lock)` — the same reason [MeshNetworkManager.markSeen] is.
 *
 * TESTABILITY. The bounds are `companion object` functions over plain numbers
 * and the instance takes a [File] plus a kill-switch lambda rather than a
 * [Context], so the whole policy is covered by a JVM unit test (no Robolectric
 * in this module, and a radio and a disk are not needed to pin a policy —
 * the policy is the part that can quietly go wrong). [forContext] is the
 * production wiring.
 */
class MeshCustodyStore(
    /** Where custody is persisted. `null` means memory-only (tests). */
    private val file: File?,
    /** Re-read on every [take] so flipping the kill switch takes effect at once. */
    private val isEnabled: () -> Boolean = { true }
) {

    companion object {
        private const val TAG = "MeshCustody"

        /**
         * Kill switch. Default ON: relaying for other people is what the mesh
         * IS, and holding a message for six hours is an extension of the
         * forwarding this app already does in real time and already gamifies
         * (`MeshBoosterManager`). Set `oshi_mesh_custody` to `false` in the
         * `oshi_settings` SharedPreferences to restore the previous
         * forward-now-or-drop behaviour exactly.
         *
         * `oshi_settings` + `oshi_mesh_custody` is the Android equivalent of
         * iOS's `UserDefaults` key of the same name: same key, same default,
         * same meaning, in the store this app already keeps its settings in.
         *
         * A user-visible toggle is a product decision, not one to invent here.
         */
        const val PREFS_NAME = "oshi_settings"
        const val PREFS_KEY = "oshi_mesh_custody"

        /**
         * How long a node will carry someone else's message.
         *
         * Six hours, not the ten minutes `MAX_RELAY_AGE_MS` uses for route
         * freshness: ten minutes does not survive the case this exists for — a
         * courier who walks out of range and comes back. And not a day: a phone
         * is not a mail server, and the longer the window the more of other
         * people's data sits on it. The VPS relay's own store-and-forward
         * (30 days) is the right place for a long wait; this is for the
         * off-grid gap. Identical to iOS `MeshCustodyStore.maxAge`.
         */
        const val MAX_AGE_MS = 6L * 60L * 60L * 1000L

        /** Bounds, enforced together. Oldest is evicted first. Identical to iOS. */
        const val MAX_COUNT = 200
        const val MAX_BYTES = 4 * 1024 * 1024

        private const val FILE_NAME = "oshi_mesh_custody.json"

        fun isExpired(takenAt: Long, now: Long): Boolean = now - takenAt >= MAX_AGE_MS

        /**
         * Whether a store holding [count] messages / [bytes] bytes has room for
         * one more of [incoming] bytes. A single message larger than the whole
         * budget is refused outright rather than evicting everything for it.
         */
        fun hasRoom(count: Int, bytes: Int, incoming: Int): Boolean =
            incoming <= MAX_BYTES && count < MAX_COUNT && bytes + incoming <= MAX_BYTES

        /**
         * The kill switch's rule, separated from SharedPreferences so it can be
         * tested: `null` is "nothing stored", which means ON — same as iOS,
         * where an absent `UserDefaults` object means enabled.
         */
        fun enabledFrom(stored: Boolean?): Boolean = stored ?: true

        /**
         * Production wiring.
         *
         * `filesDir`, not `cacheDir`: a message in custody must survive the
         * disk pressure `cacheDir` is purged under, which is exactly when a
         * crowded off-grid scene is likely to be happening. (iOS picks
         * Application Support over Caches for the same reason.) App-private
         * storage on Android is already covered by file-based encryption, which
         * is what iOS's `completeFileProtectionUntilFirstUserAuthentication`
         * buys there.
         */
        fun forContext(context: Context): MeshCustodyStore {
            val appContext = context.applicationContext
            return MeshCustodyStore(
                file = File(appContext.filesDir, FILE_NAME),
                isEnabled = {
                    try {
                        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        enabledFrom(
                            if (prefs.contains(PREFS_KEY)) prefs.getBoolean(PREFS_KEY, true) else null
                        )
                    } catch (e: Exception) {
                        // A store that cannot be read is not a reason to start
                        // dropping messages: default ON, as above.
                        true
                    }
                }
            )
        }
    }

    /**
     * One message this node is carrying for somebody else.
     *
     * [relay] is stored EXACTLY as `forwardRelayMessage` built it — `hopCount`
     * already incremented and this node already appended to `seenBy` and
     * `routePath`.
     *
     * That matters, and it is THE invariant of this file: re-originating a held
     * message at hop 0 would hand it a fresh hop budget and let a deferred
     * packet outlive the hop ceiling, which is the same class of bug
     * `CrossPlatformMesh.shouldDropForHops` fixed when it made the ceiling OURS
     * rather than the packet's. On iOS the difference is storing
     * `updatedMessage` instead of `relayMsg`; here it is storing `updatedMsg`
     * instead of `relayMsg`, and `MeshCustodyTest` pins both the round trip and
     * the call site.
     */
    data class Held(
        val relay: RelayMessage,
        val takenAt: Long,
        /**
         * Wire size at custody time, so the byte bound does not require
         * re-encoding every record on every check.
         */
        val byteCount: Int
    )

    private val lock = Any()
    private val held = LinkedHashMap<String, Held>()   // keyed by message id

    init {
        synchronized(lock) { held.putAll(loadFromDisk()) }
        prune()
    }

    // ========================================================================
    // CUSTODY
    // ========================================================================

    /**
     * Take custody of a message this node could not pass on.
     * Returns true when it was stored.
     */
    fun take(relay: RelayMessage): Boolean {
        if (!isEnabled()) return false

        val incoming = try {
            relay.toJson().toByteArray(Charsets.UTF_8).size
        } catch (e: Exception) {
            return false
        }
        if (incoming > MAX_BYTES) {
            log("📦 custody refused ${relay.messageId.take(8)}… — $incoming B exceeds the whole budget")
            return false
        }

        val snapshot: Map<String, Held>
        synchronized(lock) {
            // Already carrying it: keep the original custody time so a message
            // that keeps arriving cannot renew its own TTL forever.
            if (held.containsKey(relay.messageId)) return false

            // Make room, oldest first.
            while (!hasRoom(held.size, heldBytesLocked(), incoming)) {
                val oldest = held.entries.minByOrNull { it.value.takenAt }?.key ?: break
                held.remove(oldest)
            }
            if (!hasRoom(held.size, heldBytesLocked(), incoming)) return false

            held[relay.messageId] = Held(relay, System.currentTimeMillis(), incoming)
            snapshot = LinkedHashMap(held)
        }
        save(snapshot)
        log("📦 custody taken for ${relay.messageId.take(8)}… hop=${relay.hopCount} (carrying ${snapshot.size})")
        return true
    }

    /**
     * Messages still worth forwarding, oldest first so the longest-waiting
     * leaves first. Expired records are dropped as a side effect.
     */
    fun readyToForward(): List<RelayMessage> {
        prune()
        synchronized(lock) {
            return held.values.sortedBy { it.takenAt }.map { it.relay }
        }
    }

    /** Custody ends: the message has been handed to a peer not already in its path. */
    fun release(messageId: String) {
        val snapshot: Map<String, Held>
        synchronized(lock) {
            if (held.remove(messageId) == null) return
            snapshot = LinkedHashMap(held)
        }
        save(snapshot)
    }

    /** Drop everything past its TTL. */
    fun prune() {
        val snapshot: Map<String, Held>
        val dropped: Int
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val expired = held.filterValues { isExpired(it.takenAt, now) }.keys
            dropped = expired.size
            expired.forEach { held.remove(it) }
            if (dropped == 0) return
            snapshot = LinkedHashMap(held)
        }
        save(snapshot)
        log("📦 custody expired $dropped message(s)")
    }

    val count: Int get() = synchronized(lock) { held.size }

    val byteCount: Int get() = synchronized(lock) { heldBytesLocked() }

    /** Test seam: forget everything, on disk as well. */
    fun removeAllForTesting() {
        synchronized(lock) { held.clear() }
        file?.delete()
    }

    private fun heldBytesLocked(): Int = held.values.sumOf { it.byteCount }

    /**
     * `android.util.Log` is a stub in a JVM unit test — every method throws
     * "not mocked" unless the module turns on `returnDefaultValues`, which it
     * does not. Logging through here keeps the production log lines exactly as
     * they are while letting `MeshCustodyTest` drive the real [take] / [release]
     * / [prune] paths instead of only the pure bounds, with no Robolectric
     * dependency and no change to `build.gradle.kts`.
     */
    private fun log(message: String) {
        try {
            Log.i(TAG, message)
        } catch (e: Throwable) {
            // Unit test: no Android runtime. Never a reason to fail a relay.
        }
    }

    // ========================================================================
    // PERSISTENCE
    // ========================================================================

    /**
     * Custody survives a relaunch, which is the whole reason
     * [MeshNetworkManager]'s second flush trigger exists: on a cold start a
     * transport can come up with a peer ALREADY connected, so no
     * connection-established callback fires and nothing would drain the store.
     *
     * The record is written field by field through [JSONObject] rather than
     * through [RelayMessage.toJson], whose hand-rolled `"([^"]*)"` reader
     * cannot read back a payload containing an escaped quote. That is a wire
     * limitation this file must not inherit: a record that fails to parse on
     * load would silently empty the store.
     */
    private fun loadFromDisk(): Map<String, Held> {
        val f = file ?: return emptyMap()
        return try {
            if (!f.exists()) return emptyMap()
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val records = root.optJSONArray("held") ?: return emptyMap()
            val out = LinkedHashMap<String, Held>()
            var i = 0
            while (i < records.length()) {
                val o = records.optJSONObject(i)
                i++
                if (o == null) continue
                val relay = relayFrom(o.optJSONObject("relay") ?: continue)
                out[relay.messageId] = Held(
                    relay = relay,
                    takenAt = o.optLong("takenAt"),
                    byteCount = o.optInt("byteCount")
                )
            }
            out
        } catch (e: Exception) {
            log("📦 custody store unreadable, starting empty: ${e.message}")
            emptyMap()
        }
    }

    private fun save(all: Map<String, Held>) {
        val f = file ?: return
        try {
            val records = JSONArray()
            all.values.forEach { h ->
                records.put(
                    JSONObject()
                        .put("relay", relayTo(h.relay))
                        .put("takenAt", h.takenAt)
                        .put("byteCount", h.byteCount)
                )
            }
            val json = JSONObject().put("held", records).toString()
            // Written via a temp file and renamed so a process death mid-write
            // cannot leave a half record that fails to parse and silently
            // empties the store. (Android kills background processes far more
            // readily than iOS, which is why this matters more here than the
            // `.atomic` write it mirrors.)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            log("📦 custody store not written: ${e.message}")
        }
    }

    /** Visible for the persistence test; the mapping is the risky part. */
    internal fun relayTo(r: RelayMessage): JSONObject = JSONObject()
        .put("messageId", r.messageId)
        .put("originalSender", r.originalSender)
        .put("finalRecipient", r.finalRecipient)
        .put("encryptedPayload", r.encryptedPayload)
        .put("messageType", r.messageType)
        .put("hopCount", r.hopCount)
        .put("maxHops", r.maxHops)
        .put("seenBy", JSONArray(r.seenBy))
        .put("timestamp", r.timestamp)
        .put("routePath", JSONArray(r.routePath))

    internal fun relayFrom(o: JSONObject): RelayMessage = RelayMessage(
        messageId = o.optString("messageId"),
        originalSender = o.optString("originalSender"),
        finalRecipient = o.optString("finalRecipient"),
        encryptedPayload = o.optString("encryptedPayload"),
        messageType = o.optString("messageType"),
        hopCount = o.optInt("hopCount"),
        maxHops = o.optInt("maxHops"),
        seenBy = stringList(o.optJSONArray("seenBy")),
        timestamp = o.optLong("timestamp"),
        routePath = stringList(o.optJSONArray("routePath"))
    )

    private fun stringList(a: JSONArray?): MutableList<String> {
        val out = mutableListOf<String>()
        if (a == null) return out
        var i = 0
        while (i < a.length()) {
            out.add(a.optString(i))
            i++
        }
        return out
    }
}
