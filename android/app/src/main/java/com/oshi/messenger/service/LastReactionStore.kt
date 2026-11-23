package com.oshi.messenger.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * __REACTION_WAS_SILENT_2026_11_04__ (Anatole) The most recent reaction to one
 * of OUR messages, per conversation, so the conversation list can show it.
 *
 * WHY A STORE AND NOT A MESSAGE ROW
 *
 * The list draws `conversation.lastMessage.content`. Reactions are not stored
 * as messages — they are state attached to a message that already exists — so
 * the list had nothing to show and the reaction was invisible there however
 * recent it was. The obvious shortcut, inserting a synthetic message row, was
 * rejected: it would appear in the thread itself, count towards unread, sync
 * to other devices and export in the transcript, which is four wrong things to
 * fix one right one.
 *
 * So the reaction is kept beside the conversation rather than inside it, and
 * the list prefers it only when it is NEWER than the last real message. A
 * reaction to something said yesterday must not outrank what was said since.
 *
 * Deliberately only reactions to our OWN messages reach here: being told in
 * the conversation list that somebody reacted to their own message, or to a
 * third party's in a group, is noise.
 *
 * Persisted because the list is the first thing shown after a cold start, and
 * a preview that vanishes on relaunch reads as a bug.
 */
object LastReactionStore {

    private const val TAG = "LastReactionStore"
    private const val PREFS = "oshi_last_reaction_v1"
    private const val KEY = "entries"

    /** Capped: this is a preview cache, not a history. */
    private const val MAX_ENTRIES = 200

    data class Entry(val emoji: String, val atMillis: Long)

    private val _entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val entries: StateFlow<Map<String, Entry>> = _entries.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    /** Call once, from Application. Safe to call again. */
    fun attach(context: Context) {
        val app = context.applicationContext
        appContext = app
        runCatching {
            val raw = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return@runCatching
            val json = JSONObject(raw)
            val loaded = buildMap<String, Entry> {
                for (k in json.keys()) {
                    val o = json.optJSONObject(k) ?: continue
                    val emoji = o.optString("e").takeIf { it.isNotEmpty() } ?: continue
                    put(k, Entry(emoji, o.optLong("t")))
                }
            }
            _entries.value = loaded
        }.onFailure { Log.w(TAG, "could not read last reactions: ${it.message}") }
    }

    /** Record that [emoji] was put on one of our messages in [conversationKey]. */
    fun record(conversationKey: String, emoji: String, atMillis: Long) {
        if (conversationKey.isEmpty() || emoji.isEmpty()) return
        val updated = _entries.value.toMutableMap()
        updated[conversationKey] = Entry(emoji, atMillis)
        _entries.value =
            if (updated.size <= MAX_ENTRIES) updated
            // Oldest out first. A preview cache that grows without bound is a
            // leak nobody notices because each entry is tiny.
            else updated.entries.sortedByDescending { it.value.atMillis }
                .take(MAX_ENTRIES).associate { it.key to it.value }
        persist()
    }

    /** Dropped when the conversation is cleared, so a deleted thread leaves nothing behind. */
    fun forget(conversationKey: String) {
        if (!_entries.value.containsKey(conversationKey)) return
        _entries.value = _entries.value - conversationKey
        persist()
    }

    fun clear() {
        _entries.value = emptyMap()
        persist()
    }

    /**
     * The emoji to show for this conversation, or null.
     *
     * Pure given its inputs so the rule — newer than the last message, or
     * nothing — is testable without a Context.
     */
    fun previewEmoji(
        entry: Entry?,
        lastMessageAtMillis: Long
    ): String? = entry?.takeIf { it.atMillis > lastMessageAtMillis }?.emoji

    private fun persist() {
        val app = appContext ?: return
        runCatching {
            val json = JSONObject()
            for ((k, v) in _entries.value) {
                json.put(k, JSONObject().put("e", v.emoji).put("t", v.atMillis))
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, json.toString()).apply()
        }.onFailure { Log.w(TAG, "could not persist last reactions: ${it.message}") }
    }
}
