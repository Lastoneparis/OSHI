package com.oshi.messenger.util

/**
 * ⌨️ The `/` menu's built-in commands, and when to offer them.
 *
 * __CASHU_2026_10_25__ Written for one reason: `/pay` existed and nobody could
 * find it. A command you have to be told about is a command most people never
 * use, and for a payment feature that is the difference between shipped and
 * usable. iOS had a slash bar in groups only, fed exclusively by bot triggers;
 * Android had none at all.
 *
 * Pure text → list, like [TickerLinks] and [CashuPayCommand]: no Android, no
 * state, so the rule is covered by JVM tests rather than by typing into a
 * running app, and the desktop can share it unchanged.
 *
 * The opening rule is Telegram's, and iOS's `SlashCommands.query` already uses
 * it: the menu opens as soon as the message starts with `/` and closes the
 * moment a space is typed, because after the space you are writing ARGUMENTS,
 * not the name of a command.
 */
object SlashCommandCatalog {

    /**
     * @param command with the slash, exactly as it goes into the composer.
     * @param descriptionRes what it does, shown under the name.
     * @param argumentHintRes the placeholder after the name, or null if it takes none.
     * @param descriptionText a bot's own description, shown instead of [descriptionRes].
     * @param botLabel "🤖 Name" of the bot that answers this command; null for a built-in.
     */
    data class Entry(
        val command: String,
        val descriptionRes: Int?,
        val argumentHintRes: Int?,
        val descriptionText: String? = null,
        val botLabel: String? = null,
    )

    /** One bot's `command` triggers, as [botEntries] needs them. */
    data class BotCommandSource(
        val botName: String,
        val botEmoji: String,
        val description: String,
        val commandConditions: List<String>,
    )

    /**
     * The fragment typed after `/`, or null when the menu must NOT open.
     *
     * Three refusals that matter: `"hello /pay"` — the slash is not leading, it
     * is text; `"/pay cashuA…"` — a space was typed, we are arguing now; and a
     * `/` followed by a newline. A bare `"/"` DOES open the menu on the full
     * list: that is the discovery gesture, the one that answers "what can I
     * type here?".
     */
    fun query(text: String): String? {
        if (!text.startsWith("/")) return null
        val body = text.substring(1)
        if (body.contains(' ') || body.contains('\n')) return null
        return body.lowercase()
    }

    /**
     * What the menu shows for the composer's current text. An empty list means
     * NO BAR — never an empty bar, which floating above the keyboard is worse
     * than nothing at all.
     */
    fun matches(text: String, botCommands: List<Entry> = emptyList()): List<Entry> {
        val q = query(text) ?: return emptyList()
        val all = if (botCommands.isEmpty()) builtIns() else merged(botCommands)
        if (q.isEmpty()) return all
        return all.filter { it.command.removePrefix("/").lowercase().startsWith(q) }
    }

    /**
     * __BOT_IN_THE_PIPELINE_2026_11_09__ Port of iOS `SlashCommands.commands(from:)`
     * (SlashCommands.swift:87-109). A `command` trigger may have been typed with or
     * without its slash — the runtime accepts both — so it is normalised here or
     * the same command would be listed twice. A condition with a space cannot be
     * typed as one word in this menu and is left out, as on iOS.
     */
    fun botEntries(sources: List<BotCommandSource>): List<Entry> {
        val seen = HashSet<String>()
        val out = ArrayList<Entry>()
        for (src in sources) {
            for (raw in src.commandConditions) {
                val name = raw.trim().removePrefix("/")
                if (name.isEmpty() || name.contains(' ')) continue
                if (!seen.add(name.lowercase())) continue
                out += Entry(
                    command = "/$name",
                    descriptionRes = null,
                    argumentHintRes = null,
                    descriptionText = src.description,
                    botLabel = "${src.botEmoji} ${src.botName}".trim(),
                )
            }
        }
        return out
    }

    /** Built-ins win a name clash (iOS inserts them first), then everything sorted by command. */
    private fun merged(botCommands: List<Entry>): List<Entry> {
        val builtIns = builtIns()
        val taken = builtIns.map { it.command.lowercase() }.toSet()
        return (builtIns + botCommands.filter { it.command.lowercase() !in taken })
            .sortedBy { it.command.lowercase() }
    }

    /**
     * The commands OSHI itself answers, as opposed to a bot's triggers.
     *
     * Deliberately short. A menu that lists everything the app can do is a
     * second settings screen; this one exists so that a feature with no other
     * entry point can be found.
     */
    fun builtIns(): List<Entry> = listOf(
        Entry(
            command = CashuPayCommand.PREFIX,
            descriptionRes = com.oshi.messenger.R.string.cashu_pay_command_description,
            argumentHintRes = com.oshi.messenger.R.string.cashu_pay_command_hint,
        ),
    )

    /**
     * The composer's text after a command is picked.
     *
     * The trailing space is deliberate: every built-in takes an argument, and
     * without it the user has to type the space before they can continue. It
     * also closes the menu by [query]'s own rule — picking a command must not
     * leave the list hanging over the keyboard.
     */
    fun completion(entry: Entry): String = entry.command + " "
}
