package com.oshi.messenger.util

/**
 * __BOT_COMMANDS_2026_11_24__ The group-side rules of the bot command menu, pure.
 *
 * Two kinds of bots answer in a group:
 * - a LOCAL bot, run by its owner's phone (its commands come from that phone's triggers,
 *   see `BotManager.slashCommandsForGroup`);
 * - a PLATFORM bot, an external program behind the server's bot API. It posts as a
 *   `bot:` sender with a public `botId`, and — the group being end-to-end encrypted — it
 *   never sees what members type. A command for it must be DISPATCHED to the server
 *   (`POST /api/bot/dispatch`, kind `command`), or it simply never hears it.
 *
 * This object decides which platform bots are present (from the thread), what their menu
 * rows are, and which of them a sent command must be dispatched to.
 */
object BotCommandMenu {

    /** What a thread row tells us about its sender. */
    data class Sender(val senderPublicKey: String, val botId: String?, val senderName: String)

    /** __BOTS_PHASE1_2026_11_24__ [username] (`weatherbot`) once known: `/cmd@weatherbot` addresses it. */
    data class SeenBot(val botId: String, val name: String, val username: String? = null)

    /**
     * __BOTS_PHASE1_2026_11_24__ Bots added to the group through "Add a bot" are known
     * before they ever speak; merge them with those seen in the thread (by id, the added
     * entry wins because it carries the username).
     */
    fun withAdded(seen: List<SeenBot>, added: List<GroupBot>): List<SeenBot> {
        val byId = LinkedHashMap<String, SeenBot>()
        for (s in seen) byId[s.botId.lowercase()] = s
        for (a in added) byId[a.botId.lowercase()] = SeenBot(a.botId.lowercase(), a.name, a.username.takeIf { it.isNotEmpty() })
        return byId.values.toList()
    }

    /**
     * Platform bots that have spoken in this thread, newest name wins, in order of first
     * appearance. Bots this device runs itself ([ownedPublicIds]) are left out: they are
     * answered locally and already listed from their triggers.
     */
    fun seenPlatformBots(senders: List<Sender>, ownedPublicIds: Set<String>): List<SeenBot> {
        val names = LinkedHashMap<String, String>()
        for (s in senders) {
            val id = s.botId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: continue
            if (!s.senderPublicKey.startsWith("bot:")) continue
            if (id in ownedPublicIds) continue
            names[id] = s.senderName.trim().removePrefix("🤖").trim()
        }
        return names.map { (id, name) -> SeenBot(id, name) }
    }

    /** Menu rows for platform bots whose command lists are known. */
    fun remoteEntries(known: Map<SeenBot, List<BotCommandSyntax.Spec>>): List<SlashCommandCatalog.Entry> {
        val seen = HashSet<String>()
        val out = ArrayList<SlashCommandCatalog.Entry>()
        for ((bot, specs) in known) {
            for (spec in specs) {
                if (!seen.add(spec.name)) continue
                out += SlashCommandCatalog.Entry(
                    command = spec.command,
                    descriptionRes = null,
                    argumentHintRes = null,
                    descriptionText = spec.description,
                    botLabel = "🤖 ${bot.name}".trim(),
                    botEmoji = "🤖",
                    remoteBotId = bot.botId,
                )
            }
        }
        return out
    }

    /**
     * Local rows first (they are the bots of the people in the room), then platform rows
     * whose name is not already taken.
     */
    fun merge(local: List<SlashCommandCatalog.Entry>, remote: List<SlashCommandCatalog.Entry>): List<SlashCommandCatalog.Entry> {
        val taken = local.map { it.command.lowercase() }.toHashSet()
        return local + remote.filter { taken.add(it.command.lowercase()) }
    }

    /**
     * The platform bots a sent [text] must be dispatched to: those advertising the command,
     * narrowed to the one named in `/cmd@name` when the user addressed it. A text that is
     * not a command, or a command nobody advertises, goes nowhere — a bot is never sent
     * what was not explicitly addressed to it.
     */
    fun dispatchTargets(text: String, known: Map<SeenBot, List<BotCommandSyntax.Spec>>): List<String> =
        known.filter { (bot, specs) ->
            BotCommandSyntax.isAddressedTo(text, bot.username ?: bot.name.replace(' ', '_'), specs.map { it.name })
        }.map { it.key.botId }
}
