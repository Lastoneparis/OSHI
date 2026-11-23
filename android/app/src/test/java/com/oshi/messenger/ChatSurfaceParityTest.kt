package com.oshi.messenger

import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.ReactionEmojis
import com.oshi.messenger.service.MessageActionsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the 2026-08-23 chat-UI parity pass that are pure enough to test
 * off-device: the forward envelope (F-5 / F-18) and the reaction set (F-7).
 *
 * Every payload below is a hand-written literal. Nothing is produced by the code
 * under test.
 */
class ChatSurfaceParityTest {

    private val iosPrefix = "➡️FORWARDED➡️"
    private val androidPrefix = "➡️FORWARD➡️"

    // MARK: - F-5, the forwarded badge reads this

    @Test
    fun `iOS forward envelope is recognised and unwrapped`() {
        val raw = iosPrefix +
            """{"originalSenderName":"Ana","content":"see you at 6","isForwarded":true,"forwardCount":2}"""

        assertTrue(MessageActionsManager.isForwardEnvelope(raw))
        val parsed = MessageActionsManager.parseForwardEnvelope(raw)!!
        assertEquals("see you at 6", parsed.originalContent)
        assertEquals("Ana", parsed.originalSender)
        assertEquals(2, parsed.forwardCount)
    }

    @Test
    fun `an iOS forward with only isForwarded still parses, counting one hop`() {
        val raw = iosPrefix + """{"originalSenderName":"Bo","content":"hi","isForwarded":true}"""

        val parsed = MessageActionsManager.parseForwardEnvelope(raw)!!
        assertEquals("hi", parsed.originalContent)
        assertEquals(1, parsed.forwardCount)
    }

    @Test
    fun `the legacy Android envelope still parses - those messages are on devices today`() {
        val raw = androidPrefix +
            """{"originalContent":"old one","originalSender":"KEY==","originalTimestamp":1690000000000,"comment":"","mediaType":"IMAGE"}"""

        assertTrue(MessageActionsManager.isForwardEnvelope(raw))
        val parsed = MessageActionsManager.parseForwardEnvelope(raw)!!
        assertEquals("old one", parsed.originalContent)
        assertEquals("KEY==", parsed.originalSender)
        assertEquals(1690000000000L, parsed.originalTimestamp)
        assertEquals(MediaType.IMAGE, parsed.mediaType)
    }

    @Test
    fun `ordinary prose is not a forward`() {
        assertFalse(MessageActionsManager.isForwardEnvelope("just a message"))
        assertNull(MessageActionsManager.parseForwardEnvelope("just a message"))
        // A message that merely mentions the word must not draw the badge.
        assertNull(MessageActionsManager.parseForwardEnvelope("I forwarded it to her"))
    }

    @Test
    fun `a truncated forward payload yields null rather than a half-built badge`() {
        assertNull(MessageActionsManager.parseForwardEnvelope(iosPrefix + "{not json"))
        assertNull(MessageActionsManager.parseForwardEnvelope(androidPrefix + """{"originalContent":"x"}"""))
    }

    // MARK: - F-7, the reaction set

    @Test
    fun `ALL is iOS allCases, in iOS order`() {
        // MessageReactionManager.swift:20-49, read off the enum declaration order.
        val expected = listOf(
            "✅",                 // check
            "❌",                 // cross
            "😢",           // sad
            "🖕",           // middle finger
            "😘",           // kiss
            "👍",           // thumbs up
            "👎",           // thumbs down
            "💯",           // hundred
            "😂",           // laugh
            "❤️",           // heart
            "🔥",           // fire
            "😮",           // wow
            "👏",           // clap
            "🎉",           // party
            "🤔",           // think
            "👀",           // eyes
            "✝️",           // christian
            "☪️",           // muslim
            "✡️",           // jewish
            "☸️"            // buddhist
        )

        assertEquals(expected, ReactionEmojis.ALL)
    }

    @Test
    fun `the eight reactions iOS added on 2026-08-08 are now known`() {
        for (emoji in listOf(
            "😂", "❤️", "🔥", "😮",
            "👏", "🎉", "🤔", "👀"
        )) {
            assertTrue("unknown reaction: $emoji", ReactionEmojis.isKnown(emoji))
        }
    }

    @Test
    fun `LOVE sends a heart, not a hundred`() {
        assertEquals("❤️", ReactionEmojis.LOVE)
    }

    @Test
    fun `QUICK is untouched and still excludes the middle finger`() {
        // MessageReactionManager.swift:83-85, byte for byte.
        assertEquals(
            listOf("✅", "❌", "😢", "👍", "😘", "💯"),
            ReactionEmojis.QUICK
        )
        assertFalse(ReactionEmojis.QUICK.contains("🖕"))
    }

    @Test
    fun `an unknown reaction from a future client still renders raw`() {
        assertEquals("🦄", ReactionEmojis.displayEmoji("🦄"))
        assertFalse(ReactionEmojis.isKnown("🦄"))
    }
}
