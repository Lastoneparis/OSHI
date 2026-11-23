package com.oshi.messenger.network.v2.devsync

import org.json.JSONArray
import org.json.JSONObject

/**
 * The local linked-device list (design §3, §4.2–§4.4). Local only — there is no server list.
 *
 *  - [link] = a local approval (user tapped Allow, or the fresh-device rule).
 *  - [revoke] = "Remove device": deleted locally, sticky, propagated in DEVICES.
 *  - [mergeDevicesRecord] = what an already-linked peer tells us (§4.3 step 4): new devices it
 *    approved are added (their dk must hash to their id), revocations are sticky, a revoked device
 *    never comes back through DEVICES and self is never added.
 */
class LinkedDeviceRegistry(private val state: DevSyncStateStore, private val selfId: String) {

    private val linked = LinkedHashMap<String, LinkedDevice>()
    private val revoked = LinkedHashSet<String>()

    init { load() }

    @Synchronized fun linked(): List<LinkedDevice> = linked.values.toList()
    @Synchronized fun revoked(): Set<String> = revoked.toSet()
    @Synchronized fun get(deviceId: String): LinkedDevice? = linked[deviceId]
    @Synchronized fun isLinked(deviceId: String) = linked.containsKey(deviceId)
    @Synchronized fun isRevoked(deviceId: String) = deviceId in revoked
    @Synchronized fun isEmpty() = linked.isEmpty()

    @Synchronized
    fun link(device: LinkedDevice) {
        require(device.isConsistent()) { "device key does not match its id" }
        if (device.deviceId == selfId) return
        revoked.remove(device.deviceId)
        linked[device.deviceId] = device
        persist()
    }

    @Synchronized
    fun revoke(deviceId: String) {
        linked.remove(deviceId)
        revoked.add(deviceId)
        persist()
    }

    @Synchronized
    fun touch(deviceId: String, name: String, platform: String, syncedAtMs: Long) {
        val d = linked[deviceId] ?: return
        linked[deviceId] = d.copy(name = name.ifBlank { d.name }, platform = platform, lastSyncAtMs = syncedAtMs)
        persist()
    }

    /** Returns true when the list changed. */
    @Synchronized
    fun mergeDevicesRecord(record: DevicesRecord, from: String): Boolean {
        var changed = false
        for (id in record.revoked) {
            if (id == selfId) continue
            if (revoked.add(id)) changed = true
            if (linked.remove(id) != null) changed = true
        }
        for (e in record.linked) {
            if (e.deviceId == selfId || e.deviceId in revoked || linked.containsKey(e.deviceId)) continue
            if (!e.isConsistent()) continue
            linked[e.deviceId] = e.copy(approvedBy = from, lastSyncAtMs = null)
            changed = true
        }
        if (changed) persist()
        return changed
    }

    /** What we tell a linked peer: every OTHER linked device, and every revocation. */
    @Synchronized
    fun devicesRecordFor(peerId: String, self: LinkedDevice?): DevicesRecord {
        val list = linked.values.filter { it.deviceId != peerId }.toMutableList()
        if (self != null) list += self
        return DevicesRecord(list, revoked.toList())
    }

    private fun load() {
        val raw = state.read(FILE) ?: return
        runCatching {
            val o = JSONObject(raw)
            o.optJSONArray("linked")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let(LinkedDevice::fromJson)?.let { linked[it.deviceId] = it }
            }
            o.optJSONArray("revoked")?.let { a -> for (i in 0 until a.length()) revoked.add(a.getString(i)) }
        }
    }

    private fun persist() {
        val o = JSONObject()
            .put("v", 1)
            .put("linked", JSONArray().apply { linked.values.forEach { put(it.toStorage()) } })
            .put("revoked", JSONArray(revoked.toList()))
        state.write(FILE, o.toString())
    }

    companion object {
        const val FILE = "devsync-devices"
    }
}
