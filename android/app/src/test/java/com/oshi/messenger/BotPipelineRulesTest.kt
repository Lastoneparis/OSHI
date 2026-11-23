package com.oshi.messenger

import com.oshi.messenger.service.BotManager
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.util.SlashCommandCatalog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __BOT_IN_THE_PIPELINE_2026_11_09__ The pure rules that decide whether a bot runs,
 * whether it may speak, and how its reply is marked — ported from
 * `OSHI/BotManager.swift` (`shouldEvaluateTriggers`, `shouldFireTrigger`,
 * `authorizeEmit`, `looksLikeACommand`).
 */
class BotPipelineRulesTest {

    // ── Loop guard ─────────────────────────────────────────────────────────

    @Test fun `a human message is evaluated`() {
        assertTrue(BotManager.shouldEvaluateTriggers("AbCdEf==", "/help", botAuthored = false))
    }

    @Test fun `a bot sender never fires a trigger`() {
        assertFalse(BotManager.shouldEvaluateTriggers("bot:1a2b3c4d", "/help", botAuthored = false))
    }

    @Test fun `a reply flagged botAuthor never fires a trigger even under a human key`() {
        // Android bot replies travel under the owner's authenticated key.
        assertFalse(BotManager.shouldEvaluateTriggers("AbCdEf==", "[Echo] Echo: echo", botAuthored = true))
    }

    @Test fun `blank text and control payloads are not prose`() {
        assertFalse(BotManager.shouldEvaluateTriggers("k", "   ", botAuthored = false))
        assertFalse(BotManager.shouldEvaluateTriggers("k", "⌨️TYPING⌨️{\"isTyping\":true}", botAuthored = false))
    }

    // ── Owner-only ─────────────────────────────────────────────────────────

    @Test fun `only the owner device runs a bot`() {
        assertTrue(BotManager.isOwnedBy("AbC+dE/f", "AbC+dE/f"))
        // Same key in base64url without padding is the same identity.
        assertTrue(BotManager.isOwnedBy("AbC-dE_f==", "AbC+dE/f"))
        assertFalse(BotManager.isOwnedBy("SomeoneElse", "AbC+dE/f"))
    }

    @Test fun `no identity yet runs nothing, a legacy blank owner stays runnable`() {
        assertFalse(BotManager.isOwnedBy("AbC", ""))
        assertTrue(BotManager.isOwnedBy("", "AbC"))
    }

    // ── Trigger matching (iOS shouldFireTrigger) ───────────────────────────

    @Test fun `keyword is a case-insensitive contains`() {
        assertTrue(BotManager.triggerMatches("keyword", "echo", "please ECHO this"))
        assertFalse(BotManager.triggerMatches("keyword", "echo", "nothing here"))
        assertFalse(BotManager.triggerMatches("keyword", "", "anything"))
    }

    @Test fun `command matches with or without the stored slash, case-insensitively, at the start`() {
        assertTrue(BotManager.triggerMatches("command", "/help", "/help"))
        assertTrue(BotManager.triggerMatches("command", "help", "/help me"))
        assertTrue(BotManager.triggerMatches("command", "/Help", "  /HELP"))
        assertFalse(BotManager.triggerMatches("command", "/help", "can you /help"))
        assertFalse(BotManager.triggerMatches("command", "/", "/anything"))
    }

    @Test fun `regex is case-insensitive and a broken pattern matches nothing`() {
        assertTrue(BotManager.triggerMatches("regex", "^weather\\s+\\w+", "Weather Paris"))
        assertFalse(BotManager.triggerMatches("regex", "([", "(["))
    }

    @Test fun `member events match their markers and schedule never matches a message`() {
        assertTrue(BotManager.triggerMatches("member_join", "[MEMBER_JOIN]", "x [MEMBER_JOIN] y"))
        assertTrue(BotManager.triggerMatches("member_leave", "", "[MEMBER_LEAVE]"))
        assertFalse(BotManager.triggerMatches("schedule", "3600", "3600"))
    }

    // ── Scopes and rate limit (iOS authorizeEmit) ──────────────────────────

    @Test fun `no write scope means no reply`() {
        val d = BotManager.authorizeEmit(false, false, false, "hi", emptyList(), 1_000_000L)
        assertFalse(d.allow); assertEquals("write-scope-none", d.reason)
    }

    @Test fun `media needs the media scope`() {
        assertFalse(BotManager.authorizeEmit(true, false, true, "x", emptyList(), 0L).allow)
        assertTrue(BotManager.authorizeEmit(true, true, true, "x", emptyList(), 0L).allow)
    }

    @Test fun `six replies a minute, then refused, then allowed again once the window slides`() {
        val now = 10_000_000L
        val five = List(5) { now - it * 1_000L }
        assertTrue(BotManager.authorizeEmit(true, false, false, "x", five, now).allow)
        val six = five + (now - 5_000L)
        assertEquals("rate-limited", BotManager.authorizeEmit(true, false, false, "x", six, now).reason)
        // Every one of those six is older than 60 s a minute later.
        assertTrue(BotManager.authorizeEmit(true, false, false, "x", six, now + 61_000L).allow)
    }

    @Test fun `a long reply is truncated, not refused`() {
        val d = BotManager.authorizeEmit(true, false, false, "a".repeat(2500), emptyList(), 0L)
        assertTrue(d.allow); assertEquals("truncated", d.reason)
        assertEquals(BotManager.MAX_REPLY_CHARS, d.content.length)
    }

    // ── Wire shape ─────────────────────────────────────────────────────────

    @Test fun `bot label and body match iOS emit`() {
        assertEquals("bot:1a2b3c4d", BotManager.botSenderKey("1a2b3c4d-0000-4000-8000-000000000000"))
        assertEquals("[Echo Bot] hi", BotManager.botWireContent("Echo Bot", "hi"))
    }

    @Test fun `botAuthor rides the wire only on a bot reply, and the sender stays the authenticated one`() {
        fun wire(botAuthor: String?) = JSONObject(
            GroupManager.wireGroupMessageJson(
                id = "m1", wireGroupId = "G", senderPublicKey = "OWNERKEY", appleTimestamp = 1.0,
                encryptedContentB64 = "", iosMediaType = null, mediaFileName = null,
                body = "[Echo] hi", includeBodyCompat = true, botAuthor = botAuthor
            )
        )
        val human = wire(null)
        assertFalse(human.has(GroupManager.BOT_AUTHOR_FIELD))
        val bot = wire("Echo")
        assertEquals("Echo", bot.getString(GroupManager.BOT_AUTHOR_FIELD))
        assertEquals("OWNERKEY", bot.getString("senderPublicKey"))
        // A v2 receiver still accepts it: author == envelope sender (GROUP_E2E_V2_SPEC §3).
        assertTrue(GroupManager.v2ContentAuthentic(bot, "OWNERKEY", "G"))
    }

    // ── No-bot hint (iOS looksLikeACommand) ────────────────────────────────

    @Test fun `only a leading slash followed by a letter reads as a command`() {
        assertTrue(BotManager.looksLikeACommand("/help"))
        assertTrue(BotManager.looksLikeACommand("  /weather paris"))
        assertFalse(BotManager.looksLikeACommand("/"))
        assertFalse(BotManager.looksLikeACommand("/ 2"))
        assertFalse(BotManager.looksLikeACommand("/123"))
        assertFalse(BotManager.looksLikeACommand("and/or"))
    }

    // ── Schedule ───────────────────────────────────────────────────────────

    @Test fun `a numeric schedule is seconds with a 60 s floor, a cron string runs nothing`() {
        assertEquals(3_600_000L, BotManager.scheduleIntervalMs("3600"))
        assertEquals(60_000L, BotManager.scheduleIntervalMs("5"))
        assertEquals(3_600_000L, BotManager.scheduleIntervalMs("every_hour"))
        assertNull(BotManager.scheduleIntervalMs("0 9 * * *"))
        assertNull(BotManager.scheduleIntervalMs("-1"))
    }

    // ── Slash menu ─────────────────────────────────────────────────────────

    @Test fun `group bot commands join the slash menu beside pay, normalised and deduplicated`() {
        val entries = SlashCommandCatalog.botEntries(
            listOf(
                SlashCommandCatalog.BotCommandSource("FAQ", "❓", "Answers", listOf("/help", "about", "HELP", "two words")),
                SlashCommandCatalog.BotCommandSource("Other", "🤖", "", listOf("/Help", "/pay")),
            )
        )
        assertEquals(listOf("/help", "/about", "/pay"), entries.map { it.command })
        val all = SlashCommandCatalog.matches("/", entries)
        // `/pay` is the built-in: the bot's `/pay` must not shadow it or appear twice.
        assertEquals(listOf("/about", "/help", "/pay"), all.map { it.command })
        assertNull(all.single { it.command == "/pay" }.botLabel)
        assertEquals("❓ FAQ", all.single { it.command == "/help" }.botLabel)
        assertEquals(listOf("/help"), SlashCommandCatalog.matches("/HE", entries).map { it.command })
    }

    @Test fun `without bots the menu is exactly the built-ins, as before`() {
        assertEquals(SlashCommandCatalog.builtIns(), SlashCommandCatalog.matches("/", emptyList()))
    }
}
