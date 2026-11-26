package com.oshi.messenger.network.bot

import com.oshi.messenger.util.BotCommandSyntax
import org.json.JSONObject

/**
 * __BOT_COMMANDS_2026_11_24__ Where a group MEMBER learns the commands of a platform bot
 * they do not own.
 *
 * The server's only command route today is `GET /api/bot/getMyCommands?token=…`, which
 * needs the bot's SECRET, i.e. only its owner can call it. A member — the person who
 * actually types `/` in the group — has nothing the route accepts, so a platform bot's
 * menu was empty for everybody but its author.
 *
 * ## Missing server endpoint (NOT deployed — this client is written against it)
 *
 * ```
 * GET /api/bot/commands?botId=<public id>&groupId=<group>&userPublicKey=<caller>
 * 200 {"success":true,"commands":[{"command":"start","description":"…"}]}
 * 403 {"success":false,"error":"not a member"}     // same isMember() check as /dispatch
 * 404 {"success":false,"error":"unknown bot"}      // JSON body: the bot is unknown
 * ```
 *
 * Same authorisation as `POST /api/bot/dispatch` with a `botId` (public id + caller key
 * + membership of a group the bot serves), and the same reader as `getMyCommands`
 * (`readCommands(rec)`). Until it exists the route answers Express's default HTML 404,
 * which [parsePublicCommands] reports as [PublicCommands.EndpointMissing] — the menu then
 * simply lists nothing for that bot, exactly as before.
 */
interface BotCommandDirectory {
    suspend fun publicCommands(botId: String, groupId: String, userPublicKey: String): PublicCommands
}

sealed interface PublicCommands {
    data class Listed(val commands: List<BotCommandSyntax.Spec>) : PublicCommands
    /** The server does not have the member route yet (HTML 404). */
    data object EndpointMissing : PublicCommands
    data object NotAMember : PublicCommands
    data object UnknownBot : PublicCommands
    data class Failed(val reason: String) : PublicCommands

    companion object {
        /**
         * Pure: HTTP status + body → result. A 404 WITH a JSON body is the route saying
         * "unknown bot"; a 404 without one is the route not existing.
         */
        @JvmStatic
        fun parsePublicCommands(code: Int, body: String): PublicCommands {
            val json = runCatching { JSONObject(body) }.getOrNull()
            return when {
                code == 404 && json == null -> EndpointMissing
                code == 404 -> UnknownBot
                code == 403 -> NotAMember
                code !in 200..299 -> Failed("HTTP $code")
                json == null -> Failed("unparseable response")
                !json.optBoolean("success", false) -> Failed(json.optString("error", "success=false"))
                else -> Listed(parseCommandArray(json))
            }
        }

        /** `commands` → specs: names normalised, invalid ones dropped, duplicates removed. */
        @JvmStatic
        fun parseCommandArray(json: JSONObject): List<BotCommandSyntax.Spec> {
            val array = json.optJSONArray("commands") ?: return emptyList()
            val seen = HashSet<String>()
            return (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val name = BotCommandSyntax.normalizeName(obj.optString("command", ""))
                if (!BotCommandSyntax.isValidName(name) || !seen.add(name)) null
                else BotCommandSyntax.Spec(name, obj.optString("description", "").trim())
            }
        }
    }
}
