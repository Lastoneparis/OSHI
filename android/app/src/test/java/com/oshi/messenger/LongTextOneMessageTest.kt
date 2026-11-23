package com.oshi.messenger

import com.oshi.messenger.service.MessageSendPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __LONG_TEXT_ONE_MESSAGE_2026_09_28__ — a pasted text of a few thousand
 * characters must go as ONE message with its paragraphs intact.
 */
class LongTextOneMessageTest {

    @Test
    fun `a paired radio only tightens the budget off-grid`() {
        assertFalse(MessageSendPolicy.loRaBudgetApplies(radioPaired = true, hasPhysicalPath = true))
        assertFalse(MessageSendPolicy.loRaBudgetApplies(radioPaired = false, hasPhysicalPath = true))
        assertFalse(MessageSendPolicy.loRaBudgetApplies(radioPaired = false, hasPhysicalPath = false))
        assertTrue(MessageSendPolicy.loRaBudgetApplies(radioPaired = true, hasPhysicalPath = false))
    }

    @Test
    fun `a 9000 character French paste with paragraphs is one message online with a radio paired`() {
        val para = "Voilà un paragraphe écrit à la main, avec des accents — et une ligne.\nPuis la suite, à la ligne.\n\n"
        val sb = StringBuilder()
        while (sb.length < 9_000) sb.append(para)
        val text = sb.toString().trim()
        val budget = MessageSendPolicy.loRaBudgetApplies(radioPaired = true, hasPhysicalPath = true)
        assertEquals(MessageSendPolicy.Outcome.WITHIN_BUDGET, MessageSendPolicy.check(text, budget))
        assertEquals("LF-only text is sent exactly as typed", text, MessageSendPolicy.normalizedForSend(text))
    }

    @Test
    fun `the standard budget stays far under the relay ciphertext cap`() {
        val relayCap = 256 * 1024 // ServerVPS/v2/relay_v2.js MAX_CIPHERTEXT_BYTES
        assertTrue(MessageSendPolicy.STANDARD_UTF8_BYTES * 1.83 * 2 < relayCap)
    }

    @Test
    fun `foreign line breaks become LF and CRLF collapses to one`() {
        assertEquals(
            "a\nb\nc\nd\ne\nf\ng",
            MessageSendPolicy.normalizeLineBreaks("a\r\nb\rc d e\u000Bf\u0085g")
        )
        assertEquals("p1\n\np2", MessageSendPolicy.normalizeLineBreaks("p1\r\n\r\np2"))
        val untouched = "  keep\n\n  spaces 👨‍👩‍👧 "
        assertEquals(untouched, MessageSendPolicy.normalizeLineBreaks(untouched))
    }

    @Test
    fun `control payloads and media json are never rewritten`() {
        val media = "{\"type\":\"media\",\"caption\":\"a\rb\"}"
        assertEquals(media, MessageSendPolicy.normalizedForSend(media))
        val receipt = "📖READ_RECEIPT📖{\"id\":\"x y\"}"
        assertEquals(receipt, MessageSendPolicy.normalizedForSend(receipt))
    }

    @Test
    fun `plain prose is normalised for send`() {
        assertEquals("l1\nl2\n\nl3", MessageSendPolicy.normalizedForSend("l1 l2\r\n\r\nl3"))
    }
}
