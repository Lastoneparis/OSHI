package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import com.oshi.messenger.data.local.atrest.SealedStringStore
import com.oshi.messenger.data.model.AdvancedChatPrivacyChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * __ADV_CHAT_PRIVACY_2026_11_26__ Which conversations have advanced chat privacy
 * on. Keyed by the canonical contact key for a 1:1, by the group id for a group.
 *
 * Sealed at rest ([SealedStringStore]) like [OfflineOnlyStore]: the list of
 * conversations someone chose to protect is itself sensitive. No Room entity, so
 * no migration.
 */
object AdvancedChatPrivacyStore {

    private const val TAG = "AdvChatPrivacyStore"
    private const val FILE_NAME = "advanced_chat_privacy.json"
    private const val LABEL = "advanced chat privacy"

    data class State(val on: Boolean, val changedAtUnixSeconds: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    /**
     * Protected conversations currently on screen. Non-empty ⇒ the window must be
     * FLAG_SECURE; [ScreenshotProtectionManager] reads [secureWindowRequested].
     */
    private val _onScreen = MutableStateFlow<Set<String>>(emptySet())
    val onScreen: StateFlow<Set<String>> = _onScreen.asStateFlow()
    val secureWindowRequested: Boolean get() = _onScreen.value.isNotEmpty()

    private var store: SealedStringStore? = null
    private var saveJob: Job? = null

    /**
     * Group ids are UUIDs that iOS spells upper-case and Android sometimes lower:
     * fold them like [GroupManager.canonicalGroupId]. Contact keys fold their
     * base64 spellings like [OfflineOnlyStore] does.
     */
    internal fun canonical(id: String): String = try {
        java.util.UUID.fromString(id.trim()).toString().uppercase(java.util.Locale.US)
    } catch (_: Exception) {
        CallAdmissionPolicy.normalizeKey(id)
    }

    @Synchronized
    fun attach(context: Context) {
        if (store != null) return
        store = SealedStringStore.inFiles(context.applicationContext, FILE_NAME, LABEL)
        load()
    }

    // ── Reading ──

    fun isOn(conversationId: String): Boolean =
        conversationId.isNotEmpty() && (_states.value[canonical(conversationId)]?.on ?: false)

    fun stateOf(conversationId: String): State? = _states.value[canonical(conversationId)]

    // ── Writing ──

    /** We switched it. Always wins locally: it is the newest thing that happened. */
    fun setLocal(change: AdvancedChatPrivacyChange, conversationId: String) {
        val key = canonical(conversationId)
        _states.value = _states.value + (key to State(change.on, change.atUnixSeconds))
        scheduleSave()
    }

    /**
     * A participant switched it. Last-writer-wins by `at`, with their clock
     * bounded by ours. Returns true when the state actually changed hands to
     * this change (newer than what we held).
     */
    fun applyIncoming(
        change: AdvancedChatPrivacyChange,
        conversationId: String,
        nowUnixSeconds: Long = System.currentTimeMillis() / 1000L,
    ): Boolean {
        val key = canonical(conversationId)
        val at = AdvancedChatPrivacyChange.clampedAt(change.atUnixSeconds, nowUnixSeconds)
        val existing = _states.value[key]
        // Delivery is not ordered (mesh, relay and V2 can all carry the same
        // change), so an older one arriving late is ordinary. Ties keep what we
        // have, except that a tie resolves to ON.
        if (existing != null) {
            if (at < existing.changedAtUnixSeconds) return false
            // Equal `at` (two people toggling in the same second): ON wins, as on
            // iOS — of the two outcomes, the protecting one cannot surprise anyone.
            if (at == existing.changedAtUnixSeconds && (existing.on == change.on || existing.on)) return false
        }
        _states.value = _states.value + (key to State(change.on, at))
        scheduleSave()
        return true
    }

    // ── Window guard ──

    fun enterScreen(conversationId: String) {
        _onScreen.value = _onScreen.value + canonical(conversationId)
    }

    fun leaveScreen(conversationId: String) {
        _onScreen.value = _onScreen.value - canonical(conversationId)
    }

    /** Tests only. */
    internal fun resetForTesting() {
        _states.value = emptyMap()
        _onScreen.value = emptySet()
    }

    // ── Plumbing ──

    private fun scheduleSave() {
        if (store == null) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            save()
        }
    }

    private fun save() {
        val s = store ?: return
        try {
            val root = JSONObject()
            for ((id, st) in _states.value) {
                root.put(id, JSONObject().put("on", st.on).put("at", st.changedAtUnixSeconds))
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
                out[id] = State(o.optBoolean("on", true), o.optLong("at", 0L))
            }
            // Anything set before the file finished loading is newer: keep it.
            _states.value = out + _states.value
        } catch (e: Exception) {
            Log.w(TAG, "could not read the state: ${e.javaClass.simpleName}")
        }
    }
}
