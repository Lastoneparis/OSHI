package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import com.oshi.messenger.data.local.atrest.SealedStringStore
import com.oshi.messenger.data.model.OfflineOnlyRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Which conversations are restricted to
 * the mesh and the radio, and who has confirmed.
 *
 * Sealed at rest ([SealedStringStore]) for the same reason the ballots are: the
 * list of people you have chosen to speak to only off-grid is user content, and
 * plain preferences ride along in backups in cleartext.
 *
 * iOS: `OSHI/OfflineOnlyConversation.swift` (`OfflineOnlyStore`).
 */
object OfflineOnlyStore {

    private const val TAG = "OfflineOnlyStore"
    private const val FILE_NAME = "offline_only_conversations.json"
    private const val LABEL = "offline only conversations"

    data class State(
        val on: Boolean,
        val changedAtUnixSeconds: Double,
        /** Canonical keys of the OTHER participants who echoed. Never us. */
        val confirmedBy: Set<String>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    private var store: SealedStringStore? = null
    private var saveJob: Job? = null

    /**
     * Fold the base64 spellings so one conversation cannot end up with two
     * entries that disagree. Group ids are uuid strings and pass through.
     */
    private fun canonical(id: String): String = CallAdmissionPolicy.normalizeKey(id)

    /** Idempotent; safe from every view model and from the send path. */
    @Synchronized
    fun attach(context: Context) {
        if (store != null) return
        store = SealedStringStore.inFiles(context.applicationContext, FILE_NAME, LABEL)
        load()
    }

    // ── Reading ──

    fun isOfflineOnly(conversationId: String): Boolean =
        _states.value[canonical(conversationId)]?.on ?: false

    fun confirmedCount(conversationId: String): Int =
        _states.value[canonical(conversationId)]?.confirmedBy?.size ?: 0

    // ── Writing ──

    /**
     * We turned it on or off. Clears the confirmations: a new request has to be
     * answered again, and carrying the old set forward would show a group as
     * agreed when nobody has seen the latest change.
     */
    fun set(on: Boolean, conversationId: String, atMillis: Long = System.currentTimeMillis()) {
        val key = canonical(conversationId)
        _states.value = _states.value + (key to State(on, atMillis / 1000.0, emptySet()))
        scheduleSave()
    }

    /**
     * A participant echoed the request back. Only counted while the
     * conversation is actually restricted: a stale confirmation arriving after
     * someone lifted the restriction must not resurrect it, nor inflate a later
     * one's count.
     */
    /**
     * Does this acknowledgement answer the request we are actually waiting on?
     *
     * Matching on [OfflineOnlyRequest.on] alone would count an ack for a
     * previous, opposite state after a quick off-then-on. The timestamp pins it
     * to one specific change; the tolerance is there because the value makes a
     * round trip through JSON and back on another platform.
     */
    fun answersCurrentRequest(req: OfflineOnlyRequest, conversationId: String): Boolean {
        val s = _states.value[canonical(conversationId)] ?: return false
        if (!s.on || req.on != s.on) return false
        return kotlin.math.abs(req.atUnixSeconds - s.changedAtUnixSeconds) < 1.0
    }

    fun noteConfirmation(fromPublicKey: String, conversationId: String) {
        val key = canonical(conversationId)
        val s = _states.value[key] ?: return
        if (!s.on) return
        val who = canonical(fromPublicKey)
        if (s.confirmedBy.contains(who)) return
        _states.value = _states.value + (key to s.copy(confirmedBy = s.confirmedBy + who))
        scheduleSave()
    }

    /**
     * Someone else asked for this conversation to be restricted. We honour it
     * locally — that is the point of the request — and the caller echoes a
     * confirmation so their side can show us as agreed.
     */
    fun applyIncoming(req: OfflineOnlyRequest, conversationId: String) {
        val key = canonical(conversationId)
        val existing = _states.value[key]
        // __OFFLINE_ONLY_FUTURE_CLAMP_2026_11_20__ the peer's clock, bounded by ours.
        val at = OfflineOnlyRequest.clampedAt(req.atUnixSeconds, System.currentTimeMillis() / 1000.0)
        // Mesh delivery is not ordered, so an older request arriving late is
        // ordinary rather than an error.
        if (existing != null && existing.changedAtUnixSeconds > at) return
        _states.value = _states.value + (key to State(req.on, at, emptySet()))
        scheduleSave()
    }

    // ── Plumbing ──

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            save()
        }
    }

    private fun save() {
        val s = store ?: return
        try {
            val root = JSONObject()
            for ((id, st) in _states.value) {
                val who = JSONArray()
                st.confirmedBy.forEach { who.put(it) }
                root.put(
                    id,
                    JSONObject()
                        .put("on", st.on)
                        .put("at", st.changedAtUnixSeconds)
                        .put("by", who)
                )
            }
            s.write(JSONObject().put("states", root).toString())
        } catch (e: Exception) {
            Log.w(TAG, "could not seal the state: ${e.javaClass.simpleName}")
        }
    }

    private fun load() {
        val raw = store?.read() ?: return
        try {
            val root = JSONObject(raw).optJSONObject("states") ?: return
            val out = HashMap<String, State>()
            for (id in root.keys()) {
                val o = root.optJSONObject(id) ?: continue
                val who = o.optJSONArray("by") ?: JSONArray()
                val set = HashSet<String>()
                for (i in 0 until who.length()) set.add(who.optString(i))
                out[id] = State(o.optBoolean("on", true), o.optDouble("at", 0.0), set)
            }
            _states.value = out
            Log.d(TAG, "loaded ${out.size} conversation(s)")
        } catch (e: Exception) {
            Log.w(TAG, "could not read the state: ${e.javaClass.simpleName}")
        }
    }
}
