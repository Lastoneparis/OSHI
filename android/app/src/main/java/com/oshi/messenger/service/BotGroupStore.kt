package com.oshi.messenger.service

import android.content.Context
import com.oshi.messenger.util.BotAdminRights
import com.oshi.messenger.util.GroupBot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * __BOTS_PHASE1_2026_11_24__ What this device knows about bots in groups and about the
 * public profile of the bots it OWNS, without touching the Room schema.
 *
 * - groupId → the bots added to it (with the admin rights chosen at add time). A bot is
 *   not a member of the end-to-end group: it never holds the group key. It is reached
 *   through the bot platform (commands and button taps only), which is what the notice
 *   at add time says.
 * - own bot id → `{about, public}` (the rest of the profile lives on `OSHIBot`).
 *
 * SharedPreferences, like the command descriptions in [BotManager]; the panic wipe
 * clears the whole `shared_prefs` directory.
 */
@Singleton
class BotGroupStore @Inject constructor(@ApplicationContext context: Context) {

    /**
     * [botId]: the STABLE public id (server S3). It equals `publicIdFor(token)` until the
     * first token revoke, after which it can no longer be derived — so it is stored.
     */
    data class OwnProfileExtras(val about: String = "", val isPublic: Boolean = false, val botId: String? = null)

    private val prefs = context.getSharedPreferences("oshi_bot_groups", Context.MODE_PRIVATE)

    private val _groups = MutableStateFlow(load())
    /** groupId (lower case) → bots. */
    val groups: StateFlow<Map<String, List<GroupBot>>> = _groups.asStateFlow()

    private fun load(): Map<String, List<GroupBot>> = runCatching {
        val o = JSONObject(prefs.getString("groups", null) ?: "{}")
        o.keys().asSequence().associateWith { GroupBot.listFromJson(o.optJSONArray(it)?.toString()) }
    }.getOrDefault(emptyMap())

    private fun save(map: Map<String, List<GroupBot>>) {
        _groups.value = map
        val o = JSONObject()
        map.forEach { (k, v) -> if (v.isNotEmpty()) o.put(k, org.json.JSONArray(GroupBot.listToJson(v))) }
        runCatching { prefs.edit().putString("groups", o.toString()).apply() }
    }

    fun botsIn(groupId: String): List<GroupBot> = _groups.value[groupId.lowercase()].orEmpty()

    /** Add or replace (same botId) a bot in [groupId]. */
    fun put(groupId: String, bot: GroupBot) {
        val key = groupId.lowercase()
        val list = botsIn(key).filterNot { it.botId.equals(bot.botId, ignoreCase = true) } + bot
        save(_groups.value + (key to list))
    }

    fun remove(groupId: String, botId: String) {
        val key = groupId.lowercase()
        save(_groups.value + (key to botsIn(key).filterNot { it.botId.equals(botId, ignoreCase = true) }))
    }

    /** The rights a bot was given in [groupId]; the default when it was never set. */
    fun rights(groupId: String, botId: String): BotAdminRights =
        botsIn(groupId).firstOrNull { it.botId.equals(botId, ignoreCase = true) }?.rights ?: BotAdminRights.DEFAULT

    fun ownExtras(localBotId: String): OwnProfileExtras = runCatching {
        val o = JSONObject(prefs.getString("own_$localBotId", null) ?: return@runCatching OwnProfileExtras())
        OwnProfileExtras(o.optString("about", ""), o.optBoolean("public", false),
            o.optString("botId", "").takeIf { it.isNotEmpty() })
    }.getOrDefault(OwnProfileExtras())

    fun setOwnExtras(localBotId: String, extras: OwnProfileExtras) {
        runCatching {
            prefs.edit().putString("own_$localBotId", JSONObject().put("about", extras.about).put("public", extras.isPublic)
                .apply { extras.botId?.let { put("botId", it) } }.toString()).apply()
        }
    }

    /** The stable public id of one of MY bots (never re-derived after a revoke). */
    fun botIdFor(bot: com.oshi.messenger.data.model.OSHIBot): String =
        ownExtras(bot.id).botId ?: com.oshi.messenger.network.bot.BotPlatformClient.publicIdFor(bot.token)

    fun forgetOwn(localBotId: String) {
        runCatching { prefs.edit().remove("own_$localBotId").apply() }
    }
}
