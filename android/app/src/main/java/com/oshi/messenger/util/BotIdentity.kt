package com.oshi.messenger.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * __BOTS_PHASE1_2026_11_24__ Bots, Telegram-style: BotFather in the app, bots that act
 * INSIDE groups and channels (bots-phase1-contract, "OWNER RE-SCOPE").
 *
 * Pure (no Android): usernames, links, the public profile, admin rights, the creation
 * templates and the developer snippets. JVM-tested in `BotIdentityTest`.
 */

/** Contract §1: `{ botId, username, name, description, about, avatarEmoji, commands, … }`. */
data class BotPublicProfile(
    val botId: String,
    val username: String,
    val name: String,
    val description: String = "",
    val about: String = "",
    val avatarEmoji: String = "🤖",
    val commands: List<BotCommandSyntax.Spec> = emptyList(),
    val verified: Boolean = false,
    val hosting: String = "remote",
    val public: Boolean = true,
) {
    val handle: String get() = "@$username"
    val isRemote: Boolean get() = hosting != "local"

    companion object {
        /**
         * One profile object; null when `botId` or a valid `username` is missing. Never
         * throws, never trusts a type, clamps text to the contract's limits.
         */
        @JvmStatic
        fun fromJson(o: JSONObject?): BotPublicProfile? {
            if (o == null) return null
            val botId = o.optString("botId", "").trim()
            val username = BotUsername.normalize(o.optString("username", ""))
            if (botId.isEmpty() || !BotUsername.isValid(username)) return null
            return BotPublicProfile(
                botId = botId,
                username = username,
                name = o.optString("name", "").trim().ifEmpty { username }.take(64),
                description = o.optString("description", "").trim().take(BotUsername.MAX_DESCRIPTION),
                about = o.optString("about", "").trim().take(BotUsername.MAX_ABOUT),
                avatarEmoji = o.optString("avatarEmoji", "").trim().ifEmpty { "🤖" }.take(16),
                commands = com.oshi.messenger.network.bot.PublicCommands.parseCommandArray(o),
                verified = o.optBoolean("verified", false),
                hosting = o.optString("hosting", "remote").ifEmpty { "remote" },
                public = o.optBoolean("public", true),
            )
        }
    }
}

/**
 * Contract §1: `^[a-z][a-z0-9_]{2,28}bot$` — lower case, a letter first, ends with `bot`.
 * [problem] names WHAT is wrong, for BotFather's live validation.
 */
object BotUsername {
    val REGEX = Regex("^[a-z][a-z0-9_]{2,28}bot$")
    const val MAX_LENGTH = 32
    const val MIN_LENGTH = 6
    const val MAX_ABOUT = 120
    const val MAX_DESCRIPTION = 512

    enum class Problem { EMPTY, TOO_SHORT, TOO_LONG, MUST_START_WITH_LETTER, BAD_CHARACTERS, MUST_END_WITH_BOT }

    /** `@WeatherBot ` → `weatherbot`. */
    fun normalize(raw: String): String = raw.trim().removePrefix("@").trim().lowercase()

    fun isValid(username: String): Boolean = REGEX.matches(username)

    /** Why [raw] (after [normalize]) is not a username, or null when it is one. */
    fun problem(raw: String): Problem? {
        val u = normalize(raw)
        return when {
            u.isEmpty() -> Problem.EMPTY
            u.length > MAX_LENGTH -> Problem.TOO_LONG
            u[0] !in 'a'..'z' -> Problem.MUST_START_WITH_LETTER
            u.any { it !in 'a'..'z' && it !in '0'..'9' && it != '_' } -> Problem.BAD_CHARACTERS
            !u.endsWith("bot") -> Problem.MUST_END_WITH_BOT
            u.length < MIN_LENGTH -> Problem.TOO_SHORT
            !isValid(u) -> Problem.BAD_CHARACTERS
            else -> null
        }
    }

    /**
     * A first proposal from the bot's display name: `Météo Paris` → `meteo_parisbot`.
     * Accents folded, everything else that cannot be in a username becomes `_`.
     */
    fun fromName(name: String): String {
        val folded = java.text.Normalizer.normalize(name.trim().lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        var base = folded.map { c -> if (c in 'a'..'z' || c in '0'..'9') c else '_' }.joinToString("")
            .replace(Regex("_+"), "_").trim('_')
        base = base.removeSuffix("bot").trimEnd('_')
        base = base.dropWhile { it !in 'a'..'z' }
        if (base.length < 3) base = (base + "my").take(3).padEnd(3, 'o')
        return (base.take(MAX_LENGTH - 3) + "bot")
    }

    /**
     * Free alternatives to offer when [taken] is in use (contract: "suggestions if
     * taken"). All valid, all different from [taken], in a stable order.
     */
    fun suggestions(taken: String, count: Int = 3): List<String> {
        val u = normalize(taken)
        val stem = u.removeSuffix("bot").trimEnd('_').ifEmpty { "my" }
        val out = LinkedHashSet<String>()
        val candidates = listOf("${stem}_bot", "${stem}_osbot", "${stem}2bot", "${stem}_hqbot", "the_${stem}bot", "${stem}3bot")
        for (c in candidates) {
            val trimmed = if (c.length > MAX_LENGTH) c.take(MAX_LENGTH - 3).trimEnd('_') + "bot" else c
            if (isValid(trimmed) && trimmed != u) out += trimmed
            if (out.size >= count) break
        }
        return out.toList()
    }
}

/** Contract §2: links to a bot. A link opens the bot's PROFILE sheet (Add to group / channel). */
object BotLink {
    const val HOST = "oshi-messenger.com"
    private val START_PAYLOAD = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun isValidPayload(payload: String?): Boolean = payload != null && START_PAYLOAD.matches(payload)

    /** `https://oshi-messenger.com/bot/<username>`. */
    fun https(username: String): String = "https://$HOST/bot/${BotUsername.normalize(username)}"

    /** `oshi://bot/<username>`. */
    fun custom(username: String): String = "oshi://bot/${BotUsername.normalize(username)}"
}

/**
 * Contract §4.4, per command row: a tap SENDS, unless the command needs arguments —
 * which a bot says with a `<placeholder>` in its description (`<city> weather now`).
 * Then the tap inserts `/weather ` instead.
 */
object BotCommandArgs {
    private val PLACEHOLDER = Regex("<[^<>\\s][^<>]{0,31}>")

    fun takesArguments(description: String?): Boolean =
        description != null && PLACEHOLDER.containsMatchIn(description)
}

/**
 * What a bot may do in ONE group or channel, chosen by whoever adds it (Telegram's admin
 * rights for bots). Wire names are the contract's: `{canPost, canEdit, canDelete, canPin}`.
 */
data class BotAdminRights(
    val canPost: Boolean = true,
    val canEdit: Boolean = true,
    val canDelete: Boolean = false,
    val canPin: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("canPost", canPost).put("canEdit", canEdit).put("canDelete", canDelete).put("canPin", canPin)

    companion object {
        /** A sensible default: a bot can post and keep its own posts up to date. */
        val DEFAULT = BotAdminRights()

        fun fromJson(o: JSONObject?): BotAdminRights = if (o == null) DEFAULT else BotAdminRights(
            canPost = o.optBoolean("canPost", true),
            canEdit = o.optBoolean("canEdit", true),
            canDelete = o.optBoolean("canDelete", false),
            canPin = o.optBoolean("canPin", false),
        )
    }
}

/**
 * BotFather step 4: the kinds of bot a person usually wants, each with the commands it
 * starts with. `/start` and `/help` are offered to every kind as one-tap suggestions.
 */
enum class BotKind(val emoji: String, val commands: List<Pair<String, String>>) {
    POLL("🗳️", listOf("poll" to "<question> Start a vote", "results" to "Show the results")),
    WELCOME("👋", listOf("rules" to "Read the rules", "welcome" to "Say hello to newcomers")),
    REMINDERS("⏰", listOf("remind" to "<when> <what> Set a reminder", "reminders" to "List reminders")),
    FEED("📰", listOf("subscribe" to "<url> Follow a feed", "feeds" to "List followed feeds")),
    CUSTOM("🔗", emptyList());

    companion object {
        val SUGGESTED = listOf("start" to "Start the bot", "help" to "What I can do")
    }
}

/** BotFather step 5: ready-to-copy snippets against the real API base URL. */
object BotSnippets {
    fun curlGetUpdates(base: String, token: String): String =
        "curl \"$base/api/bot/getUpdates?token=$token&timeout=25\""

    fun curlSend(base: String, token: String): String =
        "curl -X POST \"$base/api/bot/send\" \\\n" +
            "  -H 'Content-Type: application/json' \\\n" +
            "  -d '{\"token\":\"$token\",\"groupId\":\"<GROUP_ID>\",\"content\":\"Hello!\"," +
            "\"replyMarkup\":{\"inline_keyboard\":[[{\"text\":\"👍\",\"callback_data\":\"up\"}]]}}'"

    fun python(base: String, token: String): String = """
        |import requests, time
        |
        |API = "$base/api/bot"
        |TOKEN = "$token"  # keep it secret
        |offset = 0
        |while True:
        |    r = requests.get(f"{API}/getUpdates", params={"token": TOKEN, "offset": offset, "timeout": 25}).json()
        |    for u in r.get("updates", r.get("result", [])):
        |        offset = u["update_id"] + 1
        |        msg = u.get("message") or {}
        |        if msg.get("text", "").startswith("/start"):
        |            requests.post(f"{API}/send", json={
        |                "token": TOKEN, "groupId": msg.get("chat", {}).get("id"),
        |                "content": "Hello from my bot!"})
        |    time.sleep(1)
        """.trimMargin()
}

/** The bots added to one group, as this device knows them (`BotGroupStore`). */
data class GroupBot(
    val botId: String,
    val username: String,
    val name: String,
    val avatarEmoji: String,
    val rights: BotAdminRights,
    val commands: List<BotCommandSyntax.Spec> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("botId", botId).put("username", username).put("name", name).put("avatarEmoji", avatarEmoji)
        .put("rights", rights.toJson())
        .put("commands", JSONArray().apply { commands.forEach { put(JSONObject().put("command", it.name).put("description", it.description)) } })

    companion object {
        fun fromJson(o: JSONObject?): GroupBot? {
            if (o == null) return null
            val id = o.optString("botId", "").trim().ifEmpty { return null }
            return GroupBot(
                botId = id,
                username = BotUsername.normalize(o.optString("username", "")),
                name = o.optString("name", "").ifEmpty { "Bot" },
                avatarEmoji = o.optString("avatarEmoji", "🤖").ifEmpty { "🤖" },
                rights = BotAdminRights.fromJson(o.optJSONObject("rights")),
                commands = com.oshi.messenger.network.bot.PublicCommands.parseCommandArray(o),
            )
        }

        fun listFromJson(raw: String?): List<GroupBot> = runCatching {
            val a = JSONArray(raw ?: "[]")
            (0 until a.length()).mapNotNull { fromJson(a.optJSONObject(it)) }
        }.getOrDefault(emptyList())

        fun listToJson(list: List<GroupBot>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }
}
