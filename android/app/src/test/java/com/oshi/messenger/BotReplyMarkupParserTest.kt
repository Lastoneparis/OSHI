package com.oshi.messenger

import com.oshi.messenger.data.model.BotInlineButton
import com.oshi.messenger.data.model.BotReplyMarkupParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inbound `replyMarkup` is written by a SERVER, and a client that renders whatever a
 * server sends is one malformed row away from crashing inside a `LazyColumn` item — i.e.
 * taking the whole thread down on a user's phone, with clearing app data as the only way
 * back. So the contract this asserts is not "the happy path parses"; it is **nothing
 * throws and nothing half-formed survives**.
 *
 * Every fixture here is hand-written JSON text, not a structure built from the parser's
 * own vocabulary: a test that assembles its input through the code under test can only
 * ever exercise the shapes that code already produces, which is precisely the wrong
 * coverage for a reader of foreign data.
 */
class BotReplyMarkupParserTest {

    // ── The shape that is supposed to work ───────────────────────────────────

    @Test
    fun `a well formed keyboard yields one button of each kind`() {
        val markup = BotReplyMarkupParser.parse(
            """
            {"inline_keyboard":[
              [{"text":"Yes","callback_data":"vote:yes"},{"text":"No","callback_data":"vote:no"}],
              [{"text":"Docs","url":"https://example.com/docs"}],
              [{"text":"Open","web_app":{"url":"https://example.com/app"}}]
            ]}
            """.trimIndent()
        )

        assertNotNull(markup)
        assertEquals(3, markup!!.rows.size)
        assertEquals(2, markup.rows[0].size)
        assertEquals(BotInlineButton.Kind.CALLBACK, markup.rows[0][0].kind)
        assertEquals("vote:yes", markup.rows[0][0].callbackData)
        assertEquals(BotInlineButton.Kind.URL, markup.rows[1][0].kind)
        assertEquals("https://example.com/docs", markup.rows[1][0].url)
        assertEquals(BotInlineButton.Kind.WEB_APP, markup.rows[2][0].kind)
        assertEquals("https://example.com/app", markup.rows[2][0].webAppUrl)
    }

    @Test
    fun `two buttons sharing a label still have distinct action keys`() {
        val markup = BotReplyMarkupParser.parse(
            """{"inline_keyboard":[[{"text":"Pick","callback_data":"a"},{"text":"Pick","callback_data":"b"}]]}"""
        )!!
        val keys = markup.rows[0].map { it.actionKey }.toSet()
        assertEquals(
            "a keyboard with two identically-labelled buttons must not key its loading state on the label",
            2, keys.size
        )
    }

    // ── Garbage in, null out, never a throw ──────────────────────────────────

    @Test
    fun `nothing that is not a keyboard ever throws`() {
        val notKeyboards = listOf(
            null,
            "",
            "   ",
            "not json at all",
            "[]",                                           // array where an object belongs
            "[1,2,3]",
            "{}",                                           // object with no inline_keyboard
            """{"inline_keyboard":null}""",
            """{"inline_keyboard":"buttons"}""",             // string where rows belong
            """{"inline_keyboard":42}""",
            """{"inline_keyboard":{"0":[]}}""",              // object where an array belongs
            """{"inline_keyboard":[{"text":"x"}]}""",        // a row that is an object
            """{"inline_keyboard":[["just a string"]]}""",   // a button that is a string
            """{"inline_keyboard":[[42]]}""",
            """{"inline_keyboard":[[null]]}""",
            """{"inline_keyboard":[[]]}""",                  // an empty row
            """{"inline_keyboard":[]}""",                    // no rows at all
            "{\"inline_keyboard\":[[{\"text\":\"x\",\"callback_data\":\"y\"}",  // truncated
        )
        for (raw in notKeyboards) {
            val result = BotReplyMarkupParser.parse(raw)
            assertNull("expected no keyboard from: $raw", result)
        }
    }

    // ── "EXACTLY ONE of callback_data / url / web_app" ───────────────────────

    @Test
    fun `a button carrying two actions is dropped rather than guessed at`() {
        val markup = BotReplyMarkupParser.parse(
            """{"inline_keyboard":[[{"text":"Ambiguous","callback_data":"x","url":"https://example.com"}]]}"""
        )
        assertNull("a button that means two things must not be rendered as either", markup)
    }

    @Test
    fun `a button carrying no action is dropped`() {
        assertNull(BotReplyMarkupParser.parse("""{"inline_keyboard":[[{"text":"Inert"}]]}"""))
    }

    @Test
    fun `a button with no visible label is dropped`() {
        assertNull(BotReplyMarkupParser.parse("""{"inline_keyboard":[[{"text":"   ","callback_data":"x"}]]}"""))
        assertNull(BotReplyMarkupParser.parse("""{"inline_keyboard":[[{"callback_data":"x"}]]}"""))
    }

    // ── Schemes ──────────────────────────────────────────────────────────────

    @Test
    fun `only http and https survive as a url button`() {
        val hostile = listOf(
            "javascript:alert(1)",
            "file:///etc/passwd",
            "content://com.oshi.messenger.fileprovider/x",
            "intent://scan/#Intent;scheme=zxing;end",
            "market://details?id=x",
            "/relative/path",
            "example.com"
        )
        for (url in hostile) {
            val json = """{"inline_keyboard":[[{"text":"Tap","url":${quote(url)}}]]}"""
            assertNull("a `$url` button must never be rendered", BotReplyMarkupParser.parse(json))
        }
        assertNotNull(
            BotReplyMarkupParser.parse("""{"inline_keyboard":[[{"text":"Tap","url":"http://example.com"}]]}""")
        )
    }

    @Test
    fun `a mini app must be https and must be an object`() {
        // Plain http: the page runs with JavaScript on, so TLS is not a nicety.
        assertNull(
            BotReplyMarkupParser.parse("""{"inline_keyboard":[[{"text":"App","web_app":{"url":"http://example.com"}}]]}""")
        )
        // `web_app` as a bare string is not the shape we understand.
        assertNull(
            BotReplyMarkupParser.parse("""{"inline_keyboard":[[{"text":"App","web_app":"https://example.com"}]]}""")
        )
        // …and the object without a url is not one either.
        assertNull(
            BotReplyMarkupParser.parse("""{"inline_keyboard":[[{"text":"App","web_app":{}}]]}""")
        )
    }

    // ── Clamping ─────────────────────────────────────────────────────────────

    @Test
    fun `an oversized keyboard is clamped instead of rendered whole`() {
        val row = (1..12).joinToString(",") { """{"text":"b$it","callback_data":"c$it"}""" }
        val rows = (1..14).joinToString(",") { "[$row]" }
        val markup = BotReplyMarkupParser.parse("""{"inline_keyboard":[$rows]}""")!!

        assertEquals(BotReplyMarkupParser.MAX_ROWS, markup.rows.size)
        assertTrue(markup.rows.all { it.size == BotReplyMarkupParser.MAX_BUTTONS_PER_ROW })
    }

    @Test
    fun `an over long callback payload is dropped, and only that button`() {
        val tooLong = "x".repeat(65)
        val markup = BotReplyMarkupParser.parse(
            """{"inline_keyboard":[[{"text":"Bad","callback_data":"$tooLong"},{"text":"Good","callback_data":"ok"}]]}"""
        )!!
        assertEquals(1, markup.rows[0].size)
        assertEquals("Good", markup.rows[0][0].text)
    }

    @Test
    fun `a row of only invalid buttons disappears without taking its neighbours`() {
        val markup = BotReplyMarkupParser.parse(
            """
            {"inline_keyboard":[
              [{"text":"Keep","callback_data":"1"}],
              [{"text":"Drop"},{"nope":true}],
              [{"text":"Keep too","callback_data":"2"}]
            ]}
            """.trimIndent()
        )!!
        assertEquals(2, markup.rows.size)
        assertEquals("Keep", markup.rows[0][0].text)
        assertEquals("Keep too", markup.rows[1][0].text)
    }

    @Test
    fun `an essay of a label is truncated rather than allowed to push the row apart`() {
        val essay = "word ".repeat(200)
        val markup = BotReplyMarkupParser.parse(
            """{"inline_keyboard":[[{"text":"$essay","callback_data":"x"}]]}"""
        )!!
        assertTrue(
            "a button label of ${markup.rows[0][0].text.length} chars was not clamped",
            markup.rows[0][0].text.length <= 96
        )
    }

    private fun quote(value: String) = org.json.JSONObject.quote(value)
}

/**
 * __BOT_PUBLIC_ID_2026_09_16__ Which credential a bot action goes out with.
 *
 * The whole point of the `botId` route is that a button works for a bot the user does
 * NOT own, so the case that must not regress is "no token, but a bot id" — that is the
 * one that used to produce an inert button.
 */
class BotPlatformAuthChoiceTest {

    @Test
    fun `an owned token wins over the public id`() {
        val auth = com.oshi.messenger.network.bot.BotPlatformClient
            .chooseAuth(ownedToken = "secret-token", botId = "0123456789abcdef")
        assertEquals(
            "an owner must keep authenticating as the owner — botId authorises only through " +
                "group membership, which an owner may not currently have",
            com.oshi.messenger.network.bot.BotPlatformClient.BotAuth.ByToken("secret-token"),
            auth
        )
    }

    @Test
    fun `a bot the user does not own still acts, via its public id`() {
        val auth = com.oshi.messenger.network.bot.BotPlatformClient
            .chooseAuth(ownedToken = null, botId = "0123456789abcdef")
        assertEquals(
            com.oshi.messenger.network.bot.BotPlatformClient.BotAuth.ByBotId("0123456789abcdef"),
            auth
        )
    }

    @Test
    fun `with neither, nothing is invented`() {
        assertNull(com.oshi.messenger.network.bot.BotPlatformClient.chooseAuth(null, null))
        assertNull(com.oshi.messenger.network.bot.BotPlatformClient.chooseAuth("", ""))
        // An empty token must not shadow a usable id.
        assertEquals(
            com.oshi.messenger.network.bot.BotPlatformClient.BotAuth.ByBotId("abc"),
            com.oshi.messenger.network.bot.BotPlatformClient.chooseAuth("", "abc")
        )
    }
}
