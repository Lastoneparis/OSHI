package com.oshi.messenger

import com.oshi.messenger.service.BotManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bot template variables must be the iOS set (`BotManager.swift:333-336`).
 * Before this pass Android and iOS had ZERO overlap, so a template authored on
 * one platform rendered its own placeholders as literal text on the other.
 */
class BotTemplateParityTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `ios variables are substituted`() {
        val out = BotManager.renderTemplate(
            "{{bot_name}} says: {{input}} @{{timestamp}}",
            botName = "Helper", input = "ping", senderKey = "SENDERKEY", nowMillis = now
        )
        assertEquals("Helper says: ping @1700000000", out)
    }

    @Test
    fun `date variable is replaced and leaves no placeholder behind`() {
        val out = BotManager.renderTemplate("today is {{date}}", "B", "", "", now)
        assertFalse(out.contains("{{date}}"))
        assertTrue(out.startsWith("today is "))
    }

    @Test
    fun `legacy android variables still work as aliases`() {
        val out = BotManager.renderTemplate(
            "{{message}} from {{sender}} / {{member}}",
            botName = "B", input = "hi", senderKey = "ABCDEFGHIJKL", nowMillis = now
        )
        assertEquals("hi from ABCDEFGH / ABCDEFGH", out)
    }

    @Test
    fun `an ios authored template no longer renders literally on android`() {
        val iosTemplate = "Hi! I am {{bot_name}}. You said: {{input}}"
        val out = BotManager.renderTemplate(iosTemplate, "Bot", "hello", "K", now)
        assertFalse(out.contains("{{input}}"))
        assertFalse(out.contains("{{bot_name}}"))
        assertEquals("Hi! I am Bot. You said: hello", out)
    }

    @Test
    fun `unknown placeholders are left untouched rather than blanked`() {
        val out = BotManager.renderTemplate("{{nope}}", "B", "x", "k", now)
        assertEquals("{{nope}}", out)
    }
}
