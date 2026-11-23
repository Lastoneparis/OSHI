package com.oshi.messenger.service

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * ForwardAffinityStore — who this device forwards to most, so they are offered
 * first.
 *
 * WHY THIS EXISTS
 * ---------------
 * Requested: "the contact we use or forward the most on top of the view."
 * Nothing tracked it. The per-message `forwardCount` field that already exists
 * is a different quantity — how many hops a given message has travelled — and
 * says nothing about who this user sends things to.
 *
 * Contacts were ordered `ORDER BY alias ASC` straight from the DAO, so the
 * person you forward to twenty times a day sat wherever the alphabet put them.
 *
 * WHY COUNT AND RECENCY, NOT EITHER ALONE
 * ---------------------------------------
 * A pure count ossifies: whoever you forwarded to most in the first week stays
 * top forever, and a new frequent contact can never climb past them. Pure
 * recency thrashes: one forward to a stranger evicts the person you use daily.
 *
 * So the score is the count, and recency breaks ties — which keeps the ordering
 * stable for the handful of people a user actually forwards to, while letting a
 * genuinely new habit overtake an old one after a few uses rather than never.
 *
 * PRIVACY
 * -------
 * This is a local-only tally of counts and timestamps, kept in this app's
 * private preferences. It is never sent anywhere, and it holds no message
 * content — only "this key was a forward target, n times, last at t". It is
 * cleared with the app's data like everything else. Worth stating because a
 * ranked contact list IS a social graph, and a social graph is exactly what
 * this app exists not to leak.
 */
object ForwardAffinityStore {

    private const val PREFS = "oshi_forward_affinity"
    private const val KEY_DATA = "targets"

    /** Keep the tally bounded — see [record]. */
    private const val MAX_ENTRIES = 200

    data class Affinity(val count: Int, val lastUsedAt: Long)

    private val _entries = MutableStateFlow<Map<String, Affinity>>(emptyMap())
    val entries: StateFlow<Map<String, Affinity>> = _entries.asStateFlow()

    private var appContext: Context? = null

    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        _entries.value = load()
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(): Map<String, Affinity> {
        val raw = prefs()?.getString(KEY_DATA, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                for (key in json.keys()) {
                    val entry = json.optJSONObject(key) ?: continue
                    val count = entry.optInt("c", 0)
                    if (count <= 0) continue
                    put(key, Affinity(count, entry.optLong("t", 0L)))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun persist(map: Map<String, Affinity>) {
        val json = JSONObject()
        for ((key, affinity) in map) {
            json.put(key, JSONObject().put("c", affinity.count).put("t", affinity.lastUsedAt))
        }
        prefs()?.edit { putString(KEY_DATA, json.toString()) }
    }

    /**
     * Note that a message was forwarded to [targetId].
     *
     * Keys are normalised so the same contact does not accumulate two separate
     * tallies under two spellings of their key — the same reason the search
     * predicate normalises. Group ids pass through unchanged, which is harmless:
     * normalisation only rewrites characters base64url uses.
     */
    fun record(targetId: String, now: Long = System.currentTimeMillis()) {
        if (targetId.isBlank()) return
        val key = ForwardTargetSearch.normalizeKey(targetId)
        val current = _entries.value
        val existing = current[key]
        var updated = current + (key to Affinity((existing?.count ?: 0) + 1, now))

        // Bound the tally. Dropping the LEAST useful entries — lowest count,
        // then oldest — rather than the oldest outright, so a rarely-used
        // contact cannot evict a frequent one just by being touched later.
        if (updated.size > MAX_ENTRIES) {
            updated = updated.entries
                .sortedWith(compareByDescending<Map.Entry<String, Affinity>> { it.value.count }
                    .thenByDescending { it.value.lastUsedAt })
                .take(MAX_ENTRIES)
                .associate { it.key to it.value }
        }
        _entries.value = updated
        persist(updated)
    }

    /** The score for [targetId] — 0 when never forwarded to. */
    fun scoreFor(targetId: String, snapshot: Map<String, Affinity> = _entries.value): Affinity? =
        snapshot[ForwardTargetSearch.normalizeKey(targetId)]

    /**
     * Order [ids] most-forwarded first, falling back to the incoming order.
     *
     * The fallback matters: a user who has never forwarded anything must still
     * see a sensibly ordered list, which is the alphabetical order the DAO
     * already provides. This is a re-rank, not a replacement.
     */
    fun <T> rank(items: List<T>, snapshot: Map<String, Affinity> = _entries.value, idOf: (T) -> String): List<T> {
        if (snapshot.isEmpty()) return items
        return items.withIndex().sortedWith(
            compareByDescending<IndexedValue<T>> { scoreFor(idOf(it.value), snapshot)?.count ?: 0 }
                .thenByDescending { scoreFor(idOf(it.value), snapshot)?.lastUsedAt ?: 0L }
                .thenBy { it.index }        // stable: keeps the DAO's alphabetical order
        ).map { it.value }
    }
}
