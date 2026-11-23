package com.oshi.messenger.network.bot

import android.content.Context
import com.oshi.messenger.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * __BOT_E2E_2026_09_23__ The seal kind of the LATEST bot message received per group
 * (BOT_SEAL_SPEC.md §3), so the group thread can say honestly how its bot messages
 * are protected. SharedPreferences keyed by lowercase groupId; a label only, no content.
 */
object BotSealStatus {
    private const val PREFS = "bot_seal_status"

    private val _kinds = MutableStateFlow<Map<String, BotSealedEnvelope.SealKind>>(emptyMap())
    /** Live updates for an open thread (keys = lowercase groupId). */
    val kinds: StateFlow<Map<String, BotSealedEnvelope.SealKind>> = _kinds.asStateFlow()

    fun record(context: Context, groupId: String, kind: BotSealedEnvelope.SealKind) {
        val key = groupId.lowercase()
        if (key.isEmpty()) return
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(key, kind.label).apply()
        } catch (_: Exception) {
        }
        _kinds.update { it + (key to kind) }
    }

    fun get(context: Context, groupId: String): BotSealedEnvelope.SealKind? {
        val key = groupId.lowercase()
        _kinds.value[key]?.let { return it }
        return try {
            BotSealedEnvelope.SealKind.fromLabel(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
            )
        } catch (_: Exception) {
            null
        }
    }

    /** The §3 caption for [kind]. */
    fun captionRes(kind: BotSealedEnvelope.SealKind): Int = when (kind) {
        BotSealedEnvelope.SealKind.SERVER -> R.string.bot_seal_caption_server
        BotSealedEnvelope.SealKind.BOT -> R.string.bot_seal_caption_bot
        BotSealedEnvelope.SealKind.NONE -> R.string.bot_seal_caption_none
    }
}
