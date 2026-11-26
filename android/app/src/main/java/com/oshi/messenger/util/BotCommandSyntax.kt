package com.oshi.messenger.util

/**
 * __BOT_COMMANDS_2026_11_24__ The grammar of a bot command, in one pure place.
 *
 * The server's `setMyCommands` (ServerPatches/bot_e2e/patched/bot_platform.js) accepts a
 * name only if it matches `^[a-z0-9_]{1,32}$` once its leading slash is removed, and
 * keeps at most 256 characters of description. The in-app editor applies the SAME rule
 * before saving, so a command an owner can save on the phone is always one the server
 * would accept — a list that is valid on the device and refused on the server would be a
 * bot whose menu silently stays empty for everyone else.
 *
 * Telegram's `/command@botname args` addressing is understood by [parse], so a command
 * typed for one bot in a group with several can be routed to that bot only.
 */
object BotCommandSyntax {

    const val MAX_NAME = 32
    const val MAX_DESCRIPTION = 256
    /** Same cap as the server's `setMyCommands`. */
    const val MAX_COMMANDS = 100

    private val NAME = Regex("^[a-z0-9_]{1,$MAX_NAME}$")

    /** A command as an owner configures it and as the menu lists it. */
    data class Spec(val name: String, val description: String) {
        /** With its slash, as it goes into the composer. */
        val command: String get() = "/$name"
    }

    /** What the user typed: `/name@bot args`. */
    data class Parsed(val name: String, val botMention: String?, val args: String)

    /**
     * What an owner typed in the "command" field, normalised: the slash is optional,
     * case is folded (Telegram's names are lower-case and so are the server's), and
     * surrounding blanks are dropped. Validity is [isValidName]'s job, not this one.
     */
    fun normalizeName(raw: String): String = raw.trim().removePrefix("/").trim().lowercase()

    fun isValidName(name: String): Boolean = NAME.matches(name)

    enum class EditError { EMPTY, INVALID, TOO_LONG, DUPLICATE, TOO_MANY, DESCRIPTION_TOO_LONG }

    /**
     * Why the editor must refuse this command, or null when it can be saved.
     *
     * @param existing the names already on the bot, WITHOUT the one being edited.
     */
    fun validate(rawName: String, description: String, existing: Collection<String>): EditError? {
        val name = normalizeName(rawName)
        return when {
            name.isEmpty() -> EditError.EMPTY
            name.length > MAX_NAME -> EditError.TOO_LONG
            !isValidName(name) -> EditError.INVALID
            existing.any { it.equals(name, ignoreCase = true) } -> EditError.DUPLICATE
            existing.size >= MAX_COMMANDS -> EditError.TOO_MANY
            description.trim().length > MAX_DESCRIPTION -> EditError.DESCRIPTION_TOO_LONG
            else -> null
        }
    }

    /**
     * `/start`, `/weather@meteo_bot Paris` → its parts; null for anything that is not a
     * command (no leading slash, a slash followed by a non-letter such as `/ ` or `//`,
     * or a malformed name). Mirrors `BotManager.looksLikeACommand`: `/` then a letter.
     */
    fun parse(text: String): Parsed? {
        val t = text.trim()
        if (t.length < 2 || t[0] != '/' || !t[1].isLetter()) return null
        val head = t.substring(1).takeWhile { !it.isWhitespace() }
        val args = t.substring(1 + head.length).trim()
        val at = head.indexOf('@')
        val name = (if (at >= 0) head.substring(0, at) else head).lowercase()
        val mention = if (at >= 0) head.substring(at + 1).takeIf { it.isNotEmpty() } else null
        if (!isValidName(name)) return null
        return Parsed(name, mention, args)
    }

    /**
     * Should a command typed as [text] reach a bot whose username is [botUsername] and
     * which advertises [commands]? Yes when it names one of them, and — if it was
     * addressed with `@bot` — only when that address is this bot.
     */
    fun isAddressedTo(text: String, botUsername: String?, commands: Collection<String>): Boolean {
        val p = parse(text) ?: return false
        if (commands.none { normalizeName(it) == p.name }) return false
        val mention = p.botMention ?: return true
        return botUsername != null && mention.equals(botUsername.removePrefix("@"), ignoreCase = true)
    }

    /**
     * Order for the `/` menu: names that START with what was typed first, then names
     * that merely CONTAIN it (Telegram's picker does both, so `/elp` still finds
     * `/help`), each tier keeping its input order. An empty query keeps everything.
     */
    fun <T> rank(query: String, items: List<T>, name: (T) -> String): List<T> {
        val q = query.trim().removePrefix("/").lowercase()
        if (q.isEmpty()) return items
        val starts = ArrayList<T>()
        val contains = ArrayList<T>()
        for (item in items) {
            val n = name(item).removePrefix("/").lowercase()
            when {
                n.startsWith(q) -> starts += item
                n.contains(q) -> contains += item
            }
        }
        return starts + contains
    }
}
