package com.oshi.messenger

import com.oshi.messenger.data.model.BotTrigger
import com.oshi.messenger.data.model.OSHIBot
import com.oshi.messenger.network.bot.BotPlatformClient
import com.oshi.messenger.network.bot.PublicCommands
import com.oshi.messenger.service.BotManager
import com.oshi.messenger.ui.viewmodel.BotPortalViewModel
import com.oshi.messenger.util.BotCommandMenu
import com.oshi.messenger.util.BotCommandSyntax
import com.oshi.messenger.util.BotCommandSyntax.EditError
import com.oshi.messenger.util.SlashCommandCatalog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __BOT_COMMANDS_2026_11_24__ Telegram-style bot commands: the grammar, the `/` menu's
 * filtering and tap behaviour, the owner's editor rules, the platform-bot discovery and
 * dispatch targeting, and the parsing of the (not yet deployed) member command route.
 */
class BotCommandsTest {

    // ── Grammar ────────────────────────────────────────────────────────────

    @Test fun `names follow the server's setMyCommands rule`() {
        assertTrue(BotCommandSyntax.isValidName("start"))
        assertTrue(BotCommandSyntax.isValidName("get_weather2"))
        assertFalse(BotCommandSyntax.isValidName("Start"))
        assertFalse(BotCommandSyntax.isValidName("two words"))
        assertFalse(BotCommandSyntax.isValidName("météo"))
        assertFalse(BotCommandSyntax.isValidName(""))
        assertFalse(BotCommandSyntax.isValidName("a".repeat(33)))
        assertTrue(BotCommandSyntax.isValidName("a".repeat(32)))
    }

    @Test fun `owner input is normalised - slash optional, case folded, blanks trimmed`() {
        assertEquals("help", BotCommandSyntax.normalizeName("  /Help "))
        assertEquals("help", BotCommandSyntax.normalizeName("help"))
    }

    @Test fun `parse splits command, bot address and arguments`() {
        val p = BotCommandSyntax.parse("/weather@Meteo_bot  Paris 15e")!!
        assertEquals("weather", p.name)
        assertEquals("Meteo_bot", p.botMention)
        assertEquals("Paris 15e", p.args)
        assertEquals(BotCommandSyntax.Parsed("start", null, ""), BotCommandSyntax.parse("/START"))
    }

    @Test fun `parse refuses what is not a command`() {
        assertNull(BotCommandSyntax.parse("hello /start"))
        assertNull(BotCommandSyntax.parse("/"))
        assertNull(BotCommandSyntax.parse("/ start"))
        assertNull(BotCommandSyntax.parse("//comment"))
        assertNull(BotCommandSyntax.parse("/1abc"))
        assertNull(BotCommandSyntax.parse("/météo"))
    }

    @Test fun `addressing a command to one bot excludes the others`() {
        val cmds = listOf("weather", "start")
        assertTrue(BotCommandSyntax.isAddressedTo("/weather Paris", "meteo", cmds))
        assertTrue(BotCommandSyntax.isAddressedTo("/weather@METEO", "meteo", cmds))
        assertFalse(BotCommandSyntax.isAddressedTo("/weather@other", "meteo", cmds))
        assertFalse(BotCommandSyntax.isAddressedTo("/quiz", "meteo", cmds))
        assertFalse(BotCommandSyntax.isAddressedTo("weather", "meteo", cmds))
    }

    // ── Editor validation ──────────────────────────────────────────────────

    @Test fun `editor refuses what the server would refuse`() {
        assertEquals(EditError.EMPTY, BotCommandSyntax.validate(" / ", "", emptyList()))
        assertEquals(EditError.INVALID, BotCommandSyntax.validate("get-weather", "", emptyList()))
        assertEquals(EditError.TOO_LONG, BotCommandSyntax.validate("x".repeat(40), "", emptyList()))
        assertEquals(EditError.DUPLICATE, BotCommandSyntax.validate("/Start", "", listOf("start")))
        assertEquals(EditError.TOO_MANY, BotCommandSyntax.validate("new", "", (1..100).map { "c$it" }))
        assertEquals(EditError.DESCRIPTION_TOO_LONG, BotCommandSyntax.validate("ok", "d".repeat(257), emptyList()))
        assertNull(BotCommandSyntax.validate("/Weather", "Weather now", listOf("start")))
    }

    @Test fun `profile editor rules`() {
        assertEquals(BotPortalViewModel.ProfileError.NAME_EMPTY, BotPortalViewModel.validateProfile("  ", ""))
        assertEquals(BotPortalViewModel.ProfileError.NAME_TOO_LONG, BotPortalViewModel.validateProfile("n".repeat(65), ""))
        assertEquals(BotPortalViewModel.ProfileError.DESCRIPTION_TOO_LONG, BotPortalViewModel.validateProfile("Bot", "d".repeat(513)))
        assertNull(BotPortalViewModel.validateProfile(" Meteo ", "Weather for the group"))
    }

    @Test fun `only webhook bots push their command list to the server`() {
        fun bot(type: String) = OSHIBot(token = "t", name = "n", username = "u", type = type, ownerPublicKey = "k")
        assertTrue(BotPortalViewModel.syncsCommandsToServer(bot("webhook")))
        assertFalse(BotPortalViewModel.syncsCommandsToServer(bot("automation")))
        assertFalse(BotPortalViewModel.syncsCommandsToServer(bot("moderator")))
    }

    // ── Command rows + descriptions store ──────────────────────────────────

    @Test fun `command rows come from command triggers with their stored descriptions`() {
        val triggers = listOf(
            BotTrigger(id = "a", botId = "b", type = "command", condition = "/start", responseTemplate = "Hi"),
            BotTrigger(id = "k", botId = "b", type = "keyword", condition = "hello", responseTemplate = "Hey"),
            BotTrigger(id = "c", botId = "b", type = "command", condition = "Help", responseTemplate = "Use /start"),
            BotTrigger(id = "d", botId = "b", type = "command", condition = "/two words", responseTemplate = "x"),
        )
        val rows = BotManager.commandRows(triggers, mapOf("a" to "Say hello"))
        assertEquals(listOf("start", "help"), rows.map { it.spec.name })
        assertEquals("Say hello", rows[0].spec.description)
        assertEquals("", rows[1].spec.description)
        assertEquals("Use /start", rows[1].reply)
    }

    @Test fun `descriptions survive an encode-decode round trip and junk decodes to empty`() {
        val map = mapOf("t1" to "Say hello", "t2" to "Weather, \"now\"")
        assertEquals(map, BotManager.decodeDescriptions(BotManager.encodeDescriptions(map)))
        assertEquals(emptyMap<String, String>(), BotManager.decodeDescriptions("not json"))
        assertEquals(emptyMap<String, String>(), BotManager.decodeDescriptions(null))
        assertEquals(mapOf("t1" to "x"), BotManager.decodeDescriptions(BotManager.encodeDescriptions(mapOf("t1" to "x", "t2" to " "))))
    }

    // ── The `/` menu ───────────────────────────────────────────────────────

    private val meteo = SlashCommandCatalog.botEntries(
        listOf(
            SlashCommandCatalog.BotCommandSource(
                "Meteo", "🌦️", "Weather bot", listOf("/start", "/weather", "/forecast", "/help"),
                commandDescriptions = mapOf("weather" to "Weather now"),
            )
        )
    )

    @Test fun `bot rows carry their own description, falling back to the bot's`() {
        assertEquals("Weather now", meteo.single { it.command == "/weather" }.descriptionText)
        assertEquals("Weather bot", meteo.single { it.command == "/start" }.descriptionText)
        assertEquals("🌦️", meteo.first().botEmoji)
    }

    @Test fun `filtering puts prefix matches first, then names containing the query`() {
        assertEquals(listOf("/forecast"), SlashCommandCatalog.matches("/fo", meteo).map { it.command })
        // "/e": no name starts with e, several contain it.
        val e = SlashCommandCatalog.matches("/e", meteo).map { it.command }
        assertTrue(e.containsAll(listOf("/weather", "/forecast", "/help")))
        assertFalse("/start" in e)
        // prefix tier before infix tier
        assertEquals(listOf("/help", "/weather"), SlashCommandCatalog.matches("/he", meteo).map { it.command }.filter { it != "/pay" })
        assertTrue(SlashCommandCatalog.matches("/zzz", meteo).isEmpty())
        assertTrue("a space closes the menu", SlashCommandCatalog.matches("/weather ", meteo).isEmpty())
    }

    @Test fun `a bot command is sent on tap, a built-in with an argument is inserted`() {
        val weather = meteo.single { it.command == "/weather" }
        assertTrue(weather.sendsOnPick)
        assertEquals("/weather", SlashCommandCatalog.sendText(weather))
        val pay = SlashCommandCatalog.builtIns().single()
        assertFalse(pay.sendsOnPick)
        assertEquals("/pay ", SlashCommandCatalog.completion(pay))
        assertEquals(meteo, SlashCommandCatalog.botOnly(meteo + pay))
    }

    @Test fun `rank keeps input order within a tier`() {
        val ranked = BotCommandSyntax.rank("st", listOf("/test", "/start", "/stop", "/list")) { it }
        assertEquals(listOf("/start", "/stop", "/test", "/list"), ranked)
        assertEquals(listOf("/a", "/b"), BotCommandSyntax.rank("", listOf("/a", "/b")) { it })
    }

    // ── Platform bots in a group ───────────────────────────────────────────

    @Test fun `public id matches the server's botIdFor`() {
        // node -e "require('crypto').createHash('sha256').update('oshi-bot-id:abc').digest('hex').slice(0,16)"
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("oshi-bot-id:abc".toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        assertEquals(expected, BotPlatformClient.publicIdFor("abc"))
        assertEquals(16, BotPlatformClient.publicIdFor("anything").length)
    }

    @Test fun `platform bots are discovered from bot senders, owned ones left out`() {
        val seen = BotCommandMenu.seenPlatformBots(
            listOf(
                BotCommandMenu.Sender("alice-key", null, "Alice"),
                BotCommandMenu.Sender("bot:1234abcd", "AAA111", "Meteo"),
                BotCommandMenu.Sender("bot:1234abcd", "aaa111", "🤖 Meteo v2"),
                BotCommandMenu.Sender("bot:99999999", "mine0001", "Mine"),
                BotCommandMenu.Sender("bot:legacy", null, "Legacy"),
            ),
            ownedPublicIds = setOf("mine0001"),
        )
        assertEquals(listOf(BotCommandMenu.SeenBot("aaa111", "Meteo v2")), seen)
    }

    @Test fun `a sent command is dispatched only to the platform bots that advertise it`() {
        val meteoBot = BotCommandMenu.SeenBot("id1", "Meteo")
        val quizBot = BotCommandMenu.SeenBot("id2", "Quiz Bot")
        val known = mapOf(
            meteoBot to listOf(BotCommandSyntax.Spec("weather", ""), BotCommandSyntax.Spec("start", "")),
            quizBot to listOf(BotCommandSyntax.Spec("quiz", ""), BotCommandSyntax.Spec("start", "")),
        )
        assertEquals(listOf("id1"), BotCommandMenu.dispatchTargets("/weather Paris", known))
        assertEquals(listOf("id1", "id2"), BotCommandMenu.dispatchTargets("/start", known))
        assertEquals(listOf("id2"), BotCommandMenu.dispatchTargets("/start@Quiz_Bot", known))
        assertTrue(BotCommandMenu.dispatchTargets("hello", known).isEmpty())
        assertTrue(BotCommandMenu.dispatchTargets("/unknown", known).isEmpty())
    }

    @Test fun `local commands win a name clash with platform commands`() {
        val remote = BotCommandMenu.remoteEntries(
            mapOf(BotCommandMenu.SeenBot("id1", "Meteo") to listOf(BotCommandSyntax.Spec("help", "Remote help"), BotCommandSyntax.Spec("radar", "Rain radar")))
        )
        assertEquals("id1", remote.first().remoteBotId)
        val merged = BotCommandMenu.merge(meteo, remote)
        assertEquals(1, merged.count { it.command == "/help" })
        assertNull(merged.single { it.command == "/help" }.remoteBotId)
        assertEquals("id1", merged.single { it.command == "/radar" }.remoteBotId)
    }

    // ── Member command route (not deployed) ────────────────────────────────

    @Test fun `member route responses are told apart, including the route being absent`() {
        val ok = PublicCommands.parsePublicCommands(
            200, """{"success":true,"commands":[{"command":"/Start","description":" Hi "},{"command":"bad name"},{"command":"start"},{"command":"help"}]}"""
        )
        assertEquals(
            PublicCommands.Listed(listOf(BotCommandSyntax.Spec("start", "Hi"), BotCommandSyntax.Spec("help", ""))),
            ok
        )
        assertEquals(PublicCommands.EndpointMissing, PublicCommands.parsePublicCommands(404, "<!DOCTYPE html><pre>Cannot GET /api/bot/commands</pre>"))
        assertEquals(PublicCommands.UnknownBot, PublicCommands.parsePublicCommands(404, """{"success":false,"error":"unknown bot"}"""))
        assertEquals(PublicCommands.NotAMember, PublicCommands.parsePublicCommands(403, """{"success":false}"""))
        assertTrue(PublicCommands.parsePublicCommands(500, "") is PublicCommands.Failed)
        assertTrue(PublicCommands.parsePublicCommands(200, """{"success":false,"error":"x"}""") is PublicCommands.Failed)
        assertEquals(emptyList<BotCommandSyntax.Spec>(), PublicCommands.parseCommandArray(JSONObject("{}")))
    }
}
