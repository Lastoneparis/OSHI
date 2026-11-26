package com.oshi.messenger

import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.service.MessageSendPolicy
import com.oshi.messenger.service.MessageTextSplitter
import com.oshi.messenger.service.MessageTextSplitter.Outcome
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * __LONG_TEXT_SPLIT_2026_11_23__ Port of iOS `OSHITests/MessageSendPolicyTests.swift` (the
 * `MessageTextSplitter` cases), plus the Android-only sentence boundary. A splitter's bugs are
 * invisible in review: these pin byte budgets, grapheme safety, boundary preference, wrappers, and
 * what must never be split.
 */
class MessageTextSplitterTest {

    private fun bytes(s: String) = MessageSendPolicy.utf8Size(s)
    private fun squashed(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    private fun assertNoPartExceeds(parts: List<String>, limit: Int) {
        for ((i, p) in parts.withIndex()) assertTrue("part $i is ${bytes(p)} B > $limit", bytes(p) <= limit)
    }
    private fun graphemes(s: String): Int {
        var n = 0
        for (i in 1..s.length) if (MessageTextSplitter.isGraphemeBoundary(s, i)) n++
        return n
    }
    private fun parts(o: Outcome): List<String> = (o as? Outcome.Parts)?.parts ?: run { fail("expected parts, got $o"); emptyList() }

    // ── Grapheme safety ──

    @Test
    fun emojiAreNeverCutInHalf() {
        val family = "👨‍👩‍👧‍👦"
        assertEquals(1, graphemes(family))
        assertEquals(25, bytes(family))
        val text = family.repeat(200)
        val parts = MessageTextSplitter.split(text, 500)
        assertTrue(parts.size > 1)
        assertNoPartExceeds(parts, 500)
        assertEquals(text, parts.joinToString(""))
        for (p in parts) {
            assertFalse(p.contains('\uFFFD'))
            assertEquals("a family emoji was split", 0, bytes(p) % 25)
            assertTrue(p.startsWith(family))
        }
    }

    @Test
    fun modifiedEmojiAndFlagsSurvive() {
        val run = "👍🏽🇫🇷👩🏻‍🚀"
        assertEquals(3, graphemes(run))
        val text = run.repeat(120)
        val parts = MessageTextSplitter.split(text, 300)
        assertNoPartExceeds(parts, 300)
        assertEquals(text, parts.joinToString(""))
        assertEquals(360, parts.sumOf { graphemes(it) })
    }

    @Test
    fun flagsAreKeptInPairs() {
        val text = "🇫🇷🇩🇪🇯🇵".repeat(100)   // 6 regional indicators each, 4 bytes each
        val parts = MessageTextSplitter.split(text, 90)
        assertNoPartExceeds(parts, 90)
        assertEquals(text, parts.joinToString(""))
        for (p in parts) assertEquals("odd number of regional indicators", 0, p.codePointCount(0, p.length) % 2)
    }

    @Test
    fun combiningAccentsAreNeverOrphaned() {
        val e = "e\u0301"
        assertEquals(1, graphemes(e))
        val text = e.repeat(400)
        val parts = MessageTextSplitter.split(text, 100)
        assertNoPartExceeds(parts, 100)
        assertEquals(text, parts.joinToString(""))
        for (p in parts) assertFalse(p.first() == '\u0301')
    }

    @Test
    fun surrogatePairsAreNeverCut() {
        val text = "𝄞".repeat(300)   // U+1D11E, a surrogate pair, 4 UTF-8 bytes
        val parts = MessageTextSplitter.split(text, 101)
        assertNoPartExceeds(parts, 101)
        assertEquals(text, parts.joinToString(""))
        for (p in parts) {
            assertFalse(Character.isLowSurrogate(p.first()))
            assertFalse(Character.isHighSurrogate(p.last()))
        }
    }

    @Test
    fun cjkSplitsOnCharacterBoundaries() {
        val text = "日本語のテキストです".repeat(60)
        assertEquals(text.length * 3, bytes(text))
        val parts = MessageTextSplitter.split(text, 500)
        assertTrue(parts.size > 1)
        assertNoPartExceeds(parts, 500)
        assertEquals(text, parts.joinToString(""))
        for (p in parts) assertEquals(0, bytes(p) % 3)
    }

    @Test
    fun budgetIsBytesNotCharacters() {
        val text = "漢".repeat(200)
        assertEquals(600, bytes(text))
        assertEquals(2, MessageTextSplitter.split(text, 500).size)
    }

    // ── Boundary preference ──

    @Test
    fun prefersParagraphBoundary() {
        val a = "alpha ".repeat(40).trim()
        val b = "bravo ".repeat(40).trim()
        assertEquals(listOf(a, b), MessageTextSplitter.split("$a\n\n$b", 300))
    }

    @Test
    fun prefersLineBoundaryWhenNoParagraph() {
        val a = "one ".repeat(60).trim()
        val b = "two ".repeat(60).trim()
        assertEquals(listOf(a, b), MessageTextSplitter.split("$a\n$b", 300))
    }

    /** Android-only: with no line break, a sentence end beats a mere space. */
    @Test
    fun prefersSentenceBoundaryOverWord() {
        val a = "This is a sentence that goes on for a while. ".repeat(4).trim()   // ~180 B, ends with "."
        val b = "and then words keep coming without any full stop at all for quite some time"
        val parts = MessageTextSplitter.split("$a $b $b", 300)
        assertNoPartExceeds(parts, 300)
        assertTrue("first part should end on a sentence: ${parts[0]}", parts[0].endsWith("."))
    }

    @Test
    fun breaksOnWordsNotMidWord() {
        val words = (0 until 300).map { "word$it" }
        val text = words.joinToString(" ")
        val parts = MessageTextSplitter.split(text, 400)
        assertTrue(parts.size > 1)
        assertNoPartExceeds(parts, 400)
        assertEquals(squashed(text), squashed(parts.joinToString(" ")))
        assertEquals(words, parts.flatMap { it.split(" ") })
    }

    @Test
    fun unbrokenRunFallsBackToMidWordCut() {
        val text = "z".repeat(2000)
        val parts = MessageTextSplitter.split(text, 500)
        assertEquals(4, parts.size)
        assertNoPartExceeds(parts, 500)
        assertEquals(text, parts.joinToString(""))
    }

    @Test
    fun earlyBoundaryDoesNotStarveTheBudget() {
        val text = "Hi\n\n" + "long ".repeat(200)
        val parts = MessageTextSplitter.split(text, 400)
        assertNoPartExceeds(parts, 400)
        for (p in parts.dropLast(1)) assertTrue("part is only ${bytes(p)} B", bytes(p) > 40)
    }

    @Test
    fun shortTextIsNotTouched() {
        val text = "Hello 👋 how are you?"
        assertEquals(listOf(text), MessageTextSplitter.split(text, 500))
        assertEquals(Outcome.Single, MessageTextSplitter.splitForSend(text, 500))
    }

    @Test
    fun splitterAlwaysTerminates() {
        for (text in listOf("\n".repeat(500), " ".repeat(500), "👨‍👩‍👧‍👦".repeat(50), "a\n".repeat(400))) {
            assertNoPartExceeds(MessageTextSplitter.split(text, 60), 60)
        }
        assertEquals(Outcome.Single, MessageTextSplitter.splitForSend("\n".repeat(500), 60))
        assertFalse(MessageTextSplitter.split("👨‍👩‍👧‍👦".repeat(50), 60).isEmpty())
    }

    // ── What must never be split ──

    @Test
    fun controlPayloadsArePassedThroughUntouched() {
        val filler = "x".repeat(6000)
        val controls = OSHIControlPayload.prefixes.filter { !OSHIControlPayload.isEnvelope(it) }
        assertTrue(controls.size > 10)
        for (prefix in controls) {
            assertEquals(prefix, Outcome.Single, MessageTextSplitter.splitForSend(prefix + filler, 500))
        }
    }

    @Test
    fun cashuTokenIsRefusedRatherThanSplit() {
        val json = """{"token":[{"mint":"https://8333.space:3338","proofs":[{"amount":2,"secret":"407915bc212be61a77e3e6d2aeb4c727980bda51cd06a6afc29e2861768a7837","C":"02bc9097997d81afb2cc7346b5e4345a9346bd2a506eb7958598a72f0cf85163ea","id":"009a1f293253e41e"},{"amount":8,"secret":"fe15109314e61d7756b0f8ee0f23a624acaa3f4e042f61433c728c7057b931be","C":"029e8e5050b890a7d6c0968db16bc1d5d5fa040ea1de284f6ec69d61299f671059","id":"009a1f293253e41e"}]}],"unit":"sat","memo":"Thank you."}"""
        val token = "cashuA" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        assertTrue(com.oshi.messenger.util.CashuTokens.containsToken(token))
        assertTrue(bytes(token) > MessageSendPolicy.LORA_SAFE_UTF8_BYTES)
        assertTrue(MessageTextSplitter.splitForSend(token, MessageSendPolicy.LORA_SAFE_UTF8_BYTES) is Outcome.TooLong)
        assertTrue(MessageTextSplitter.splitForSend("Here you go: $token thanks", MessageSendPolicy.LORA_SAFE_UTF8_BYTES) is Outcome.TooLong)
        assertEquals(Outcome.Single, MessageTextSplitter.splitForSend(token, MessageSendPolicy.STANDARD_UTF8_BYTES))
    }

    @Test
    fun groupMediaJSONIsNotSplit() {
        val json = "{\"type\":\"media\",\"content\":\"" + "A".repeat(6000) + "\"}"
        assertEquals(Outcome.Single, MessageTextSplitter.splitForSend(json, 500))
    }

    // ── Reply / forward wrappers ──

    private fun replyWire(body: String): String {
        val replyTo = JSONObject().put("originalMessageId", "orig-1").put("originalSenderKey", "peer")
            .put("originalText", "the original").put("originalTimestamp", 785_000_000.5)
        return OSHIControlPayload.REPLY + JSONObject().put("content", body).put("replyTo", replyTo).toString()
    }

    private fun forwardWire(body: String, name: String = "Alice") = OSHIControlPayload.FORWARDED +
        JSONObject().put("originalSenderName", name).put("content", body).put("isForwarded", true).put("forwardCount", 2).toString()

    @Test
    fun replyWrapperSplitsTheBodyAndQuotesOnlyTheFirstPart() {
        val body = (0 until 300).joinToString(" ") { "word$it" }
        val parts = parts(MessageTextSplitter.splitForSend(replyWire(body), 600))
        assertTrue(parts.size > 1)
        assertTrue(parts[0].startsWith(OSHIControlPayload.REPLY))
        val first = JSONObject(parts[0].removePrefix(OSHIControlPayload.REPLY))
        assertEquals("orig-1", first.getJSONObject("replyTo").getString("originalMessageId"))
        for (p in parts.drop(1)) assertFalse("the quote is repeated", p.startsWith(OSHIControlPayload.REPLY))
        assertNoPartExceeds(parts, 600)
        val recovered = (listOf(first.getString("content")) + parts.drop(1)).joinToString(" ")
        assertEquals(squashed(body), squashed(recovered))
    }

    @Test
    fun forwardWrapperKeepsAttributionOnEveryPart() {
        val body = (0 until 300).joinToString(" ") { "word$it" }
        val parts = parts(MessageTextSplitter.splitForSend(forwardWire(body), 700))
        assertTrue(parts.size > 1)
        assertNoPartExceeds(parts, 700)
        val bodies = parts.map { p ->
            assertTrue(p.startsWith(OSHIControlPayload.FORWARDED))
            val f = JSONObject(p.removePrefix(OSHIControlPayload.FORWARDED))
            assertEquals("Alice", f.getString("originalSenderName"))
            assertTrue(f.getBoolean("isForwarded"))
            assertEquals(2, f.getInt("forwardCount"))
            f.getString("content")
        }
        assertEquals(squashed(body), squashed(bodies.joinToString(" ")))
    }

    @Test
    fun wrappedPartsFitTheBudgetEvenWhenJSONEscapingInflatesTheBody() {
        val wire = replyWire("a\n\"b\"\\c\t".repeat(300))
        assertTrue(bytes(wire) > 600)
        when (val o = MessageTextSplitter.splitForSend(wire, 600)) {
            is Outcome.Parts -> {
                assertNoPartExceeds(o.parts, 600)
                assertNotNull(JSONObject(o.parts[0].removePrefix(OSHIControlPayload.REPLY)).optJSONObject("replyTo"))
            }
            is Outcome.TooLong -> Unit
            Outcome.Single -> fail("an over-budget wrapped message was passed through unsplit")
        }
    }

    @Test
    fun forwardPartsFitTheBudgetWithEscapedBodies() {
        val wire = forwardWire("line \"quoted\"\n".repeat(300), name = "Alice \"A\"")
        when (val o = MessageTextSplitter.splitForSend(wire, 800)) {
            is Outcome.Parts -> {
                assertNoPartExceeds(o.parts, 800)
                for (p in o.parts) JSONObject(p.removePrefix(OSHIControlPayload.FORWARDED))
            }
            is Outcome.TooLong -> Unit
            Outcome.Single -> fail("an over-budget forward was passed through unsplit")
        }
    }

    @Test
    fun anEnvelopeThatDoesNotParseIsRefusedNotCut() {
        val broken = OSHIControlPayload.REPLY + "{not json " + "x".repeat(1000)
        assertTrue(MessageTextSplitter.splitForSend(broken, 500) is Outcome.TooLong)
    }

    // ── Refusal and the real budgets ──

    @Test
    fun absurdPasteIsRefusedRatherThanTurnedIntoAStorm() {
        val o = MessageTextSplitter.splitForSend("word ".repeat(200_000), 4000)
        assertTrue(o is Outcome.TooLong)
        assertTrue((o as Outcome.TooLong).partsNeeded > MessageTextSplitter.MAX_PARTS)
    }

    @Test
    fun aLongTextOverTheOshiMeshBudgetNowSplits() {
        val text = "Bonjour, ceci est un long message envoyé par la radio. ".repeat(150)   // ~8.5 KB
        val limit = MessageSendPolicy.maxUTF8Bytes(loRaPaired = true, oshiFirmware = true)
        val parts = parts(MessageTextSplitter.splitForSend(text, limit))
        assertNoPartExceeds(parts, limit)
        assertEquals(squashed(text), squashed(parts.joinToString(" ")))
    }

    @Test
    fun theStockLoRaBudgetSplitsAtFiveHundredBytes() {
        val text = "word ".repeat(300)   // 1,500 B
        val parts = parts(MessageTextSplitter.splitForSend(text, MessageSendPolicy.LORA_SAFE_UTF8_BYTES))
        assertNoPartExceeds(parts, MessageSendPolicy.LORA_SAFE_UTF8_BYTES)
        assertTrue(parts.size in 3..4)
    }

    @Test
    fun theFiftyPartCapIsInclusive() {
        val atCap = "z".repeat(100 * MessageTextSplitter.MAX_PARTS)
        assertEquals(MessageTextSplitter.MAX_PARTS, parts(MessageTextSplitter.splitForSend(atCap, 100)).size)
        assertTrue(MessageTextSplitter.splitForSend(atCap + "z", 100) is Outcome.TooLong)
    }
}
