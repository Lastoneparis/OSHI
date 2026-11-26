package com.oshi.messenger

import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.GroupType
import com.oshi.messenger.network.bot.BotApiResult
import com.oshi.messenger.network.bot.BotDirectoryApi
import com.oshi.messenger.network.bot.BotProfileUpdate
import com.oshi.messenger.network.bot.RevokedToken
import com.oshi.messenger.service.BotPostUpdate
import com.oshi.messenger.ui.DeepLinkParser
import com.oshi.messenger.ui.OshiDeepLink
import com.oshi.messenger.ui.bots.BotDirectoryViewModel
import com.oshi.messenger.ui.bots.BotFatherViewModel
import com.oshi.messenger.util.BotAdminRights
import com.oshi.messenger.util.BotCommandArgs
import com.oshi.messenger.util.BotCommandMenu
import com.oshi.messenger.util.BotCommandSyntax
import com.oshi.messenger.util.BotKind
import com.oshi.messenger.util.BotLink
import com.oshi.messenger.util.BotPublicProfile
import com.oshi.messenger.util.BotSnippets
import com.oshi.messenger.util.BotUsername
import com.oshi.messenger.util.GroupBot
import com.oshi.messenger.util.SlashCommandCatalog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * __BOTS_PHASE1_2026_11_24__ BotFather, bots inside groups and channels
 * (bots-phase1-contract, owner re-scope + server amendments S1–S8).
 * Robolectric only because a few rules read `org.json` (Android stubs on the plain JVM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BotsPhase1Test {

    // ── usernames ─────────────────────────────────────────────────────────

    @Test fun `contract regex - valid usernames`() {
        for (u in listOf("weatherbot", "meteo_bot", "a12bot", "x".repeat(29) + "bot")) assertTrue(u, BotUsername.isValid(u))
        for (u in listOf("bot", "abbot", "Weatherbot", "1weatherbot", "weather", "we-ather_bot", "x".repeat(30) + "bot")) {
            assertFalse(u, BotUsername.isValid(u))
        }
    }

    @Test fun `live validation names what is wrong`() {
        assertEquals(BotUsername.Problem.EMPTY, BotUsername.problem("  @ "))
        assertEquals(BotUsername.Problem.MUST_START_WITH_LETTER, BotUsername.problem("1abcbot"))
        assertEquals(BotUsername.Problem.BAD_CHARACTERS, BotUsername.problem("ab-cbot"))
        assertEquals(BotUsername.Problem.MUST_END_WITH_BOT, BotUsername.problem("weather"))
        assertEquals(BotUsername.Problem.TOO_SHORT, BotUsername.problem("abbot"))
        assertEquals(BotUsername.Problem.TOO_LONG, BotUsername.problem("a".repeat(33)))
        assertNull(BotUsername.problem("@Weather_Bot"))
    }

    @Test fun `a proposal from the name is always valid`() {
        for (n in listOf("Météo Paris", "A", "123", "Quiz Bot", "  ", "😀 Fun", "x".repeat(80))) {
            val u = BotUsername.fromName(n)
            assertTrue("$n -> $u", BotUsername.isValid(u))
        }
        assertEquals("meteo_parisbot", BotUsername.fromName("Météo Paris"))
    }

    @Test fun `suggestions when taken are valid and different`() {
        val s = BotUsername.suggestions("weatherbot")
        assertEquals(3, s.size)
        s.forEach { assertTrue(it, BotUsername.isValid(it)); assertTrue(it != "weatherbot") }
        BotUsername.suggestions("x".repeat(29) + "bot").forEach { assertTrue(it, BotUsername.isValid(it)) }
    }

    // ── links ─────────────────────────────────────────────────────────────

    @Test fun `bot links build and parse both ways`() {
        assertEquals("https://oshi-messenger.com/bot/weatherbot", BotLink.https("@WeatherBot"))
        assertEquals("oshi://bot/weatherbot", BotLink.custom("weatherbot"))
        assertEquals(OshiDeepLink.OpenBot("weatherbot"), DeepLinkParser.parse("https://oshi-messenger.com/bot/WeatherBot"))
        assertEquals(OshiDeepLink.OpenBot("weatherbot"), DeepLinkParser.parse("https://oshi-messenger.com/bot/weatherbot?start=abc"))
        assertEquals(OshiDeepLink.OpenBot("weatherbot"), DeepLinkParser.parse("oshi://bot/weatherbot"))
        assertTrue(DeepLinkParser.parse("https://oshi-messenger.com/bot/") is OshiDeepLink.Unhandled)
        // existing routes untouched
        assertTrue(DeepLinkParser.parse("https://oshi-messenger.com/g/myclub") is OshiDeepLink.PublicGroup)
        assertTrue(BotLink.isValidPayload("ab_c-1") && !BotLink.isValidPayload("a b"))
    }

    @Test fun `manifest routes bot links to the app without touching the other app links`() {
        val m = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map { java.io.File(it) }.first { it.exists() }.readText()
        assertTrue(m.contains("""android:host="oshi-messenger.com" android:pathPrefix="/bot/""""))
        val filter = m.substringAfter("<intent-filter android:autoVerify=\"true\">").substringBefore("</intent-filter>")
        assertTrue("bot link inside the autoVerify filter", filter.contains("/bot/"))
        assertTrue(filter.contains("/group") && filter.contains("/g/") && filter.contains("/add"))
    }

    // ── profile + API classification ──────────────────────────────────────

    @Test fun `public profile parses and refuses what it cannot trust`() {
        val p = BotPublicProfile.fromJson(JSONObject("""{"botId":"abc123","username":"WeatherBot","name":"Météo",
            "about":"${"x".repeat(300)}","commands":[{"command":"/Start","description":"go"},{"command":"bad name"}],
            "hosting":"remote","public":true}"""))!!
        assertEquals("weatherbot", p.username)
        assertEquals(120, p.about.length)
        assertEquals(listOf("start"), p.commands.map { it.name })
        assertTrue(p.isRemote)
        assertNull(BotPublicProfile.fromJson(JSONObject("""{"botId":"x","username":"nope"}""")))
        assertNull(BotPublicProfile.fromJson(JSONObject("""{"username":"weatherbot"}""")))
    }

    @Test fun `classify keeps an undeployed route apart from an unknown bot`() {
        assertEquals(BotApiResult.Unavailable, BotApiResult.classify(404, "<html>Cannot GET</html>") { Unit })
        assertEquals(BotApiResult.NotFound, BotApiResult.classify(404, """{"success":false,"error":"not_found"}""") { Unit })
        assertEquals(BotApiResult.UsernameTaken, BotApiResult.classify(409, """{"error":"username_taken"}""") { Unit })
        assertEquals(BotApiResult.Invalid("username_invalid"), BotApiResult.classify(400, """{"error":"username_invalid"}""") { Unit })
        assertEquals(BotApiResult.Unauthorized, BotApiResult.classify(401, """{"error":"signature_required"}""") { Unit })
        assertEquals(BotApiResult.Ok(true), BotApiResult.classify(200, """{"success":true,"available":true}""") { it.optBoolean("available") })
        assertTrue(BotApiResult.classify(200, """{"success":false}""") { Unit } is BotApiResult.Failed)
    }

    // ── a fake directory, as the screens use it while the server is not deployed ──

    private class FakeDirectory(val taken: Set<String>) : BotDirectoryApi {
        val added = mutableListOf<Pair<String, BotAdminRights>>()
        override suspend fun publicProfile(username: String?, botId: String?) =
            if (username == "weatherbot") BotApiResult.Ok(BotPublicProfile("id1", "weatherbot", "Météo")) else BotApiResult.NotFound
        override suspend fun search(query: String) =
            BotApiResult.Ok(listOf(BotPublicProfile("id1", "weatherbot", "Météo")).filter { it.username.startsWith(query.removePrefix("@")) })
        override suspend fun setMyProfile(token: String, update: BotProfileUpdate): BotApiResult<Unit> =
            if (update.username in taken) BotApiResult.UsernameTaken else BotApiResult.Ok(Unit)
        override suspend fun usernameAvailable(username: String, token: String?) = BotApiResult.Ok(username !in taken)
        override suspend fun revokeToken(token: String) = BotApiResult.Ok(RevokedToken("f".repeat(32), "id1"))
        override suspend fun addToGroup(botId: String, groupId: String, isChannel: Boolean, memberKeys: List<String>, rights: BotAdminRights): BotApiResult<Unit> {
            added += groupId to rights; return BotApiResult.Ok(Unit)
        }
        override suspend fun removeFromGroup(botId: String, groupId: String) = BotApiResult.Ok(Unit)
    }

    @Test fun `username availability maps to the BotFather status line`() = runBlocking {
        val api = FakeDirectory(setOf("weatherbot"))
        val taken = BotFatherViewModel.availabilityStatus("weatherbot", api.usernameAvailable("weatherbot"))
        assertTrue(taken is BotFatherViewModel.UsernameStatus.Taken)
        assertEquals(3, (taken as BotFatherViewModel.UsernameStatus.Taken).suggestions.size)
        assertEquals(BotFatherViewModel.UsernameStatus.Available,
            BotFatherViewModel.availabilityStatus("meteo_bot", api.usernameAvailable("meteo_bot")))
        assertEquals(BotFatherViewModel.UsernameStatus.Unchecked,
            BotFatherViewModel.availabilityStatus("meteo_bot", BotApiResult.Unavailable))
        assertTrue(BotFatherViewModel.statusFor("weatherbot", api.setMyProfile("t", BotProfileUpdate(username = "weatherbot")))
            is BotFatherViewModel.UsernameStatus.Taken)
    }

    @Test fun `profile update only sends what changed`() {
        val j = BotProfileUpdate(about = "hi", public = true).toJson("tok")
        assertEquals("tok", j.getString("token"))
        assertFalse(j.has("username") || j.has("name") || j.has("description"))
        assertTrue(j.getBoolean("public"))
    }

    // ── BotFather commands step ───────────────────────────────────────────

    @Test fun `suggestions include start and help plus the kind's template, minus what is added`() {
        val s = BotFatherViewModel.suggestions(BotKind.POLL, listOf(BotCommandSyntax.Spec("start", "")))
        assertEquals(listOf("help", "poll", "results"), s.map { it.name })
        assertTrue(s.all { BotCommandSyntax.isValidName(it.name) })
        BotKind.entries.forEach { k -> k.commands.forEach { assertTrue(BotCommandSyntax.isValidName(it.first)) } }
    }

    @Test fun `snippets carry the real base url and the token`() {
        val base = "https://oshi-messenger.com"
        assertTrue(BotSnippets.curlGetUpdates(base, "TOK").contains("$base/api/bot/getUpdates?token=TOK"))
        assertTrue(BotSnippets.curlSend(base, "TOK").contains("\"token\":\"TOK\""))
        assertTrue(BotSnippets.python(base, "TOK").contains("API = \"$base/api/bot\""))
    }

    // ── rights, group bots ────────────────────────────────────────────────

    @Test fun `admin rights and group bots round-trip with the contract's wire names`() {
        val r = BotAdminRights(canPost = true, canEdit = false, canDelete = true, canPin = true)
        val j = r.toJson()
        assertEquals(setOf("canPost", "canEdit", "canDelete", "canPin"), j.keys().asSequence().toSet())
        assertEquals(r, BotAdminRights.fromJson(j))
        val b = GroupBot("id1", "weatherbot", "Météo", "🌦️", r, listOf(BotCommandSyntax.Spec("radar", "Rain")))
        assertEquals(listOf(b), GroupBot.listFromJson(GroupBot.listToJson(listOf(b))))
        assertEquals(emptyList<GroupBot>(), GroupBot.listFromJson("garbage"))
    }

    @Test fun `only groups and channels I administer are offered`() {
        fun g(id: String, admins: List<String>, type: GroupType? = null) =
            Group(id = id, name = id, creatorPublicKey = "x", memberPublicKeys = admins + "m", adminPublicKeys = admins, groupType = type)
        val me = "bWVfa2V5X2Jhc2U2NA=="
        val chats = BotDirectoryViewModel.adminChats(listOf(g("b", listOf(me)), g("a", listOf("other")), g("c", listOf(me), GroupType.ADMIN_ONLY)), me)
        assertEquals(listOf("b", "c"), chats.map { it.id })
        assertTrue(BotDirectoryViewModel.isChannel(chats[1]))
    }

    // ── group commands to remote bots ─────────────────────────────────────

    @Test fun `cmd at username reaches only that bot, once its username is known`() {
        val meteo = BotCommandMenu.SeenBot("id1", "Météo", "weatherbot")
        val quiz = BotCommandMenu.SeenBot("id2", "Quiz")
        val known = mapOf(meteo to listOf(BotCommandSyntax.Spec("help", "")), quiz to listOf(BotCommandSyntax.Spec("help", "")))
        assertEquals(listOf("id1", "id2"), BotCommandMenu.dispatchTargets("/help", known))
        assertEquals(listOf("id1"), BotCommandMenu.dispatchTargets("/help@weatherbot", known))
        assertEquals(emptyList<String>(), BotCommandMenu.dispatchTargets("/help@otherbot", known))
    }

    @Test fun `bots added to a group are known before they speak`() {
        val seen = listOf(BotCommandMenu.SeenBot("id1", "Old name"))
        val added = listOf(GroupBot("ID1", "weatherbot", "Météo", "🌦️", BotAdminRights.DEFAULT), GroupBot("id2", "quizbot", "Quiz", "🎲", BotAdminRights.DEFAULT))
        val merged = BotCommandMenu.withAdded(seen, added)
        assertEquals(listOf("id1", "id2"), merged.map { it.botId })
        assertEquals("weatherbot", merged[0].username)
    }

    // ── picker behaviour (contract §4.4) ──────────────────────────────────

    private fun entry(cmd: String, desc: String) = SlashCommandCatalog.Entry(
        command = cmd, descriptionRes = null, argumentHintRes = null, descriptionText = desc, botLabel = "🤖 Bot", botEmoji = "🤖")

    @Test fun `a command whose description has an arg placeholder inserts instead of sending`() {
        assertTrue(entry("/help", "All commands").sendsOnPick)
        assertFalse(entry("/weather", "<city> Weather now").sendsOnPick)
        assertTrue(BotCommandArgs.takesArguments("Remind <when> <what>"))
        assertFalse(BotCommandArgs.takesArguments("a < b and c > d"))
        assertFalse(BotCommandArgs.takesArguments(null))
    }

    @Test fun `hardware keys move, pick, insert and close`() {
        val m = listOf(entry("/a", ""), entry("/b", ""), entry("/c", "<x>"))
        assertEquals(SlashCommandCatalog.KeyAction.Move(1), SlashCommandCatalog.onKey(SlashCommandCatalog.Key.DOWN, m, 0))
        assertEquals(SlashCommandCatalog.KeyAction.Move(2), SlashCommandCatalog.onKey(SlashCommandCatalog.Key.UP, m, 0))
        assertEquals(SlashCommandCatalog.KeyAction.Move(0), SlashCommandCatalog.onKey(SlashCommandCatalog.Key.DOWN, m, 2))
        assertEquals(SlashCommandCatalog.KeyAction.Pick(m[1]), SlashCommandCatalog.onKey(SlashCommandCatalog.Key.ENTER, m, 1))
        assertEquals(SlashCommandCatalog.KeyAction.Insert(m[0]), SlashCommandCatalog.onKey(SlashCommandCatalog.Key.TAB, m, 0))
        assertEquals(SlashCommandCatalog.KeyAction.Close, SlashCommandCatalog.onKey(SlashCommandCatalog.Key.ESCAPE, m, 0))
        assertEquals(SlashCommandCatalog.KeyAction.None, SlashCommandCatalog.onKey(SlashCommandCatalog.Key.DOWN, emptyList(), 0))
        assertEquals("/a ", SlashCommandCatalog.completion(m[0]))
    }

    // ── in-place actions on a bot's post (S5) ─────────────────────────────

    private fun botPost(id: String, botId: String?, sender: String = "bot:abcd1234") = GroupMessage(
        id = id, groupId = "g1", senderPublicKey = sender, senderName = "Vote", content = "Yes 0 · No 0",
        isFromMe = false, replyMarkup = """{"inline_keyboard":[[{"text":"Yes","callback_data":"y"}]]}""", botId = botId)

    private fun action(json: String, outer: String? = "bot1") = BotPostUpdate.parse(JSONObject(json), outer)

    @Test fun `an edit rewrites the bot's own post in place, keyboard kept unless named`() {
        val thread = listOf(botPost("m1", "bot1"))
        val edit = action("""{"type":"bot_message_action","action":"edit","messageId":"e1","targetMessageId":"M1","groupId":"g1","botId":"bot1","content":"Yes 1 · No 0"}""")!!
        val after = BotPostUpdate.applyTo(thread, edit)!!
        assertEquals("Yes 1 · No 0", after[0].content)
        assertEquals(thread[0].replyMarkup, after[0].replyMarkup)
        assertEquals("m1", after[0].id)

        val clear = action("""{"type":"bot_message_action","action":"edit","targetMessageId":"m1","groupId":"g1","replyMarkup":null}""")!!
        assertTrue(clear.clearMarkup)
        assertNull(BotPostUpdate.applyTo(thread, clear)!![0].replyMarkup)
    }

    @Test fun `delete tombstones, and nobody can act on someone else's message`() {
        val thread = listOf(botPost("m1", "bot1"), botPost("m2", "bot2", "bot:ffff0000"), botPost("m3", null, "alicekey"))
        val del = action("""{"type":"bot_message_action","action":"delete","targetMessageId":"m1","groupId":"g1"}""")!!
        val after = BotPostUpdate.applyTo(thread, del)!!
        assertTrue(after[0].isDeleted); assertEquals("", after[0].content); assertNull(after[0].replyMarkup)
        assertNull("already deleted", BotPostUpdate.applyTo(after, del))
        // another bot's post, a person's post, an unknown id
        for (t in listOf("m2", "m3", "zz")) {
            assertNull(t, BotPostUpdate.applyTo(thread, action("""{"type":"bot_message_action","action":"delete","targetMessageId":"$t","groupId":"g1"}""")!!))
        }
    }

    @Test fun `malformed actions are refused`() {
        assertNull(action("""{"type":"bot_message_action","action":"explode","targetMessageId":"m1","groupId":"g1"}"""))
        assertNull(action("""{"type":"bot_message_action","action":"edit","targetMessageId":"m1","groupId":"g1"}"""))
        assertNull(action("""{"type":"bot_message_action","action":"delete","groupId":"g1"}"""))
        assertNull(action("""{"type":"bot_message_action","action":"delete","targetMessageId":"m1","groupId":"g1"}""", outer = null))
        assertNull(action("""{"type":"bot_message","action":"delete","targetMessageId":"m1","groupId":"g1"}"""))
        val pin = action("""{"type":"bot_message_action","action":"pin","targetMessageId":"m1","groupId":"g1"}""")!!
        assertEquals(BotPostUpdate.Action.PIN, pin.action)
        assertNull("pin does not rewrite the thread", BotPostUpdate.applyTo(listOf(botPost("m1", "bot1")), pin))
        assertNotNull(BotPostUpdate.target(listOf(botPost("m1", "bot1")), pin))
    }

    // ── "Create a bot": both kinds stay reachable ─────────────────────────

    @Test fun `create a bot offers the on-phone wizard and BotFather, each on a real route`() {
        val routes = com.oshi.messenger.ui.bots.BotHosting.entries.associateWith { com.oshi.messenger.ui.bots.BotCreateEntry.route(it) }
        assertEquals("botFather", routes[com.oshi.messenger.ui.bots.BotHosting.SERVER])
        assertEquals("botPortal?createLocal=true", routes[com.oshi.messenger.ui.bots.BotHosting.LOCAL])
        // Both destinations are declared in the NavHost, and the portal opens the local wizard on that flag.
        val src = listOf("src/main/java", "app/src/main/java").map { java.io.File(it) }.first { it.isDirectory }
        val main = java.io.File(src, "com/oshi/messenger/ui/MainActivity.kt").readText()
        assertTrue(main.contains("composable(\"botFather\")"))
        assertTrue(main.contains("route = \"botPortal?createLocal={createLocal}\""))
        val portal = java.io.File(src, "com/oshi/messenger/ui/screens/BotPortalScreen.kt").readText()
        assertTrue("local choice opens the existing wizard", portal.contains("BotHosting.LOCAL -> showCreateSheet = true"))
        assertTrue("server choice opens BotFather", portal.contains("BotHosting.SERVER -> onCreateBot?.invoke()"))
        assertTrue(portal.contains("mutableStateOf(openLocalWizard)") && portal.contains("BotCreateScreen("))
    }
}
