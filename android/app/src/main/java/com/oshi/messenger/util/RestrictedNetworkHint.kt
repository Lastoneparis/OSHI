package com.oshi.messenger.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * __RESTRICTED_NETWORK_HINT_2026_11_18__ RN-HINT v1 — docs/RESTRICTED_NETWORK_HINT.md.
 *
 * "This message was sent through a restricted network", carried INSIDE the E2E text as
 * an invisible suffix (three default-ignorable format characters). Old clients render it
 * as nothing; new clients strip it at ingestion and show a chip on the bubble.
 * Pure (org.json only) so the shared vectors run on the JVM.
 */
object RestrictedNetworkHint {
    /** U+2063 U+2064 U+2063 — UTF-8 `e2 81 a3 e2 81 a4 e2 81 a3`. */
    const val MARKER = "⁣⁤⁣"
    const val REPLY_PREFIX = "💬REPLY💬"

    /** Sender rule (spec §3): cover transport ON at send time AND the sender allows the hint. */
    fun shouldMark(coverOn: Boolean, hintSettingOn: Boolean): Boolean = coverOn && hintSettingOn

    /**
     * The plaintext to put on the wire for a user-typed 1:1 [content]: plain text gets the
     * marker at its end; a `💬REPLY💬` envelope gets it at the end of its `content` value.
     * Anything else (control payloads, forwards, broken envelopes) is returned unchanged.
     */
    fun mark(content: String, isControl: (String) -> Boolean = { false }): String {
        if (content.startsWith(REPLY_PREFIX)) {
            val json = runCatching { JSONObject(content.removePrefix(REPLY_PREFIX)) }.getOrNull() ?: return content
            val body = json.optString("content", "")
            if (!json.has("content") || body.endsWith(MARKER)) return content
            json.put("content", body + MARKER)
            return REPLY_PREFIX + json.toString()
        }
        if (content.isEmpty() || isControl(content) || content.endsWith(MARKER)) return content
        return content + MARKER
    }

    data class Inbound(val text: String, val flagged: Boolean)

    /**
     * Receiver rule (spec §4): test the DECODED text (plain, or a reply envelope's
     * `content`), strip exactly one trailing marker, report whether it was there.
     */
    fun strip(plaintext: String): Inbound {
        if (plaintext.startsWith(REPLY_PREFIX)) {
            val json = runCatching { JSONObject(plaintext.removePrefix(REPLY_PREFIX)) }.getOrNull()
                ?: return Inbound(plaintext, false)
            val body = json.optString("content", "")
            if (!body.endsWith(MARKER)) return Inbound(plaintext, false)
            json.put("content", body.removeSuffix(MARKER))
            return Inbound(REPLY_PREFIX + json.toString(), true)
        }
        if (!plaintext.endsWith(MARKER)) return Inbound(plaintext, false)
        return Inbound(plaintext.removeSuffix(MARKER), true)
    }
}

/** The sender switch "Let contacts know I'm on a restricted network" (default ON). */
object RestrictedNetworkHintSetting {
    private const val PREFS = "oshi_rn_hint"
    private const val KEY = "enabled"
    private val flow = MutableStateFlow<Boolean?>(null)

    fun state(context: Context): StateFlow<Boolean?> {
        if (flow.value == null) flow.value = isOn(context)
        return flow.asStateFlow()
    }

    fun isOn(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        flow.value = on
    }
}

/**
 * Received message ids that carried the hint — local only (no Room column, no
 * migration), newest [MAX] kept. Read by the incoming bubble.
 */
object RestrictedNetworkHintStore {
    private const val PREFS = "oshi_rn_hint_ids"
    private const val KEY = "ids"
    internal const val MAX = 2_000
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        _ids.value = decode(raw)
    }

    @Synchronized fun add(context: Context, messageId: String) {
        load(context)
        if (messageId in _ids.value) return
        val next = capped(_ids.value + messageId)
        _ids.value = next
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encode(next)).apply()
    }

    /** Insertion-ordered, newest last; keeps the newest [MAX]. */
    internal fun capped(ids: Set<String>): Set<String> =
        if (ids.size <= MAX) ids else LinkedHashSet(ids.toList().takeLast(MAX))

    internal fun encode(ids: Set<String>): String = ids.joinToString("\n")
    internal fun decode(raw: String?): Set<String> =
        raw?.split('\n')?.filter { it.isNotBlank() }?.toCollection(LinkedHashSet()) ?: emptySet()
}
