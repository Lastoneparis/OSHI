package com.oshi.messenger.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * __BLOCKED_NOTIF_2026_09_23__ A block that survives the contact row.
 *
 * A block used to live ONLY in `contacts.isBlocked`. `ContactDao.setBlocked` is an
 * UPDATE, so blocking someone with no contact row stored nothing, and any path that
 * deletes the row (duplicate merge, cleanup, a deleted conversation re-created without
 * the flag) silently UNBLOCKED them. The person then notified again, and the Blocked
 * screen — which lists `isBlocked` rows — could not even show them to unblock. iOS keeps
 * its list apart from contacts (`BlockedContactsManager`), and so does this.
 *
 * The contact flag is still written, so every existing reader keeps working; this list
 * is the source of truth for "is this key blocked" and for the Blocked screen.
 */
object BlockedKeys {

    data class Entry(val publicKey: String, val name: String?, val blockedAt: Long)

    fun normalize(key: String): String =
        key.trim().replace('-', '+').replace('_', '/').replace("=", "")

    fun contains(entries: List<Entry>, key: String): Boolean {
        val n = normalize(key)
        return n.isNotEmpty() && entries.any { normalize(it.publicKey) == n }
    }

    fun add(entries: List<Entry>, key: String, name: String?, now: Long): List<Entry> {
        if (key.isBlank()) return entries
        if (contains(entries, key)) {
            // Keep the first block date; learn a name if we did not have one.
            return entries.map {
                if (normalize(it.publicKey) == normalize(key) && it.name.isNullOrBlank() && !name.isNullOrBlank())
                    it.copy(name = name) else it
            }
        }
        return entries + Entry(key.trim(), name?.takeIf { it.isNotBlank() }, now)
    }

    fun remove(entries: List<Entry>, key: String): List<Entry> {
        val n = normalize(key)
        return entries.filterNot { normalize(it.publicKey) == n }
    }

    /**
     * What the Blocked screen shows: every entry of the list, named from the contact
     * row when one still exists, plus any legacy `isBlocked` row not yet in the list.
     */
    fun merge(entries: List<Entry>, blockedRows: List<Pair<String, String>>): List<Entry> {
        val out = entries.toMutableList()
        for ((key, alias) in blockedRows) {
            if (!contains(out, key)) out += Entry(key, alias, 0L)
        }
        val aliasByKey = blockedRows.associate { normalize(it.first) to it.second }
        return out.map { e ->
            val alias = aliasByKey[normalize(e.publicKey)]
            if (!alias.isNullOrBlank()) e.copy(name = alias) else e
        }.sortedByDescending { it.blockedAt }
    }

    fun encode(entries: List<Entry>): String = JSONArray().apply {
        entries.forEach { e ->
            put(JSONObject().put("k", e.publicKey).put("n", e.name ?: JSONObject.NULL).put("t", e.blockedAt))
        }
    }.toString()

    /**
     * __BLOCK_SYNC_LWW_2026_09_24__ insert or move an entry to EXACTLY [at] (a merged devsync
     * record carries the time of the block on the device where it was made). Every spelling of
     * the key collapses to one entry. `at = 0` = untimestamped (legacy).
     */
    fun put(entries: List<Entry>, key: String, name: String?, at: Long): List<Entry> {
        if (key.isBlank()) return entries
        val old = entries.firstOrNull { normalize(it.publicKey) == normalize(key) }
        return remove(entries, key) + Entry(old?.publicKey ?: key.trim(), name?.takeIf { it.isNotBlank() } ?: old?.name, at)
    }

    /** Unblock tombstones: normalized key -> (key as spelled, unblockedAt). */
    fun encodeTombstones(t: Map<String, Pair<String, Long>>): String = JSONArray().apply {
        t.values.forEach { (k, at) -> put(JSONObject().put("k", k).put("t", at)) }
    }.toString()

    fun decodeTombstones(json: String?): Map<String, Pair<String, Long>> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val k = o.optString("k").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                normalize(k) to (k to o.optLong("t"))
            }.toMap()
        } catch (_: Exception) { emptyMap() }
    }

    fun decode(json: String?): List<Entry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val k = o.optString("k").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Entry(k, if (o.isNull("n")) null else o.optString("n"), o.optLong("t"))
            }
        } catch (_: Exception) {
            emptyList()   // a corrupt file must not crash the app; the contact flags remain
        }
    }
}

/** SharedPreferences-backed [BlockedKeys] list, observable for the Blocked screen. */
class BlockedKeysStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(BlockedKeys.decode(prefs.getString(KEY, null)))
    val entries: StateFlow<List<BlockedKeys.Entry>> = _entries.asStateFlow()

    /**
     * __BLOCK_SYNC_LWW_2026_09_24__ When each key was last UNBLOCKED (a tombstone), so devsync can
     * export an unblock as `blocked:false` + `blockedAt` and an older block from another device
     * can never re-block (design §8.5, merge.json `stale_block_does_not_undo_newer_unblock`).
     */
    @Volatile private var tombstones: Map<String, Pair<String, Long>> =
        BlockedKeys.decodeTombstones(prefs.getString(KEY_UNBLOCKED, null))

    /** Bumped on every change: devsync pushes the CONTACTS record when it moves. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun isBlocked(key: String): Boolean = BlockedKeys.contains(_entries.value, key)

    fun entry(key: String): BlockedKeys.Entry? =
        _entries.value.firstOrNull { BlockedKeys.normalize(it.publicKey) == BlockedKeys.normalize(key) }

    /** The last unblock time of [key], null if never unblocked (or blocked again since). */
    fun unblockedAt(key: String): Long? = tombstones[BlockedKeys.normalize(key)]?.second

    /** Every tombstoned key as spelled when it was unblocked. */
    fun unblockedKeys(): List<String> = tombstones.values.map { it.first }

    /**
     * The user blocked [key] here. A restamp is always later than the previous unblock
     * (clock skew between two taps must not let the older fact win the LWW merge).
     */
    @Synchronized fun block(key: String, name: String?) {
        val at = maxOf(System.currentTimeMillis(), (unblockedAt(key) ?: 0L) + 1)
        dropTombstone(key)
        save(BlockedKeys.add(_entries.value, key, name, at))
    }

    /** Devsync: blocked at exactly [at] on the device where the user did it (0 = legacy). */
    @Synchronized fun blockAt(key: String, name: String?, at: Long) {
        dropTombstone(key)
        save(BlockedKeys.put(_entries.value, key, name, at))
    }

    @Synchronized fun unblock(key: String) =
        unblockAt(key, maxOf(System.currentTimeMillis(), (entry(key)?.blockedAt ?: 0L) + 1))

    /** Removes every spelling of [key]; [at] = the unblock time (null = no tombstone, legacy). */
    @Synchronized fun unblockAt(key: String, at: Long?) {
        if (at != null && key.isNotBlank()) {
            tombstones = tombstones + (BlockedKeys.normalize(key) to (key.trim() to at))
            prefs.edit().putString(KEY_UNBLOCKED, BlockedKeys.encodeTombstones(tombstones)).apply()
        }
        save(BlockedKeys.remove(_entries.value, key))
    }

    private fun dropTombstone(key: String) {
        val n = BlockedKeys.normalize(key)
        if (tombstones.containsKey(n)) {
            tombstones = tombstones - n
            prefs.edit().putString(KEY_UNBLOCKED, BlockedKeys.encodeTombstones(tombstones)).apply()
        }
    }

    private fun save(list: List<BlockedKeys.Entry>) {
        _entries.value = list
        prefs.edit().putString(KEY, BlockedKeys.encode(list)).apply()
        _version.value = _version.value + 1
    }

    companion object {
        private const val PREFS = "oshi_blocked_keys"
        private const val KEY = "entries"
        private const val KEY_UNBLOCKED = "unblocked"
        @Volatile private var instance: BlockedKeysStore? = null
        /** The store if anything has opened it yet (call paths without a Context). */
        fun current(): BlockedKeysStore? = instance
        fun get(context: Context): BlockedKeysStore =
            instance ?: synchronized(this) { instance ?: BlockedKeysStore(context).also { instance = it } }
    }
}
