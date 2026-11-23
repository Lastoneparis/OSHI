package com.oshi.messenger.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class MailDisplayTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val now = ZonedDateTime.of(2026, 9, 24, 15, 0, 0, 0, paris)

    @Test fun `today shows a time, this year a day and month, older a short date`() {
        assertEquals("09:30", MailDisplay.listTime("2026-09-24T07:30:00Z", now, Locale.FRANCE))
        assertEquals("3 sept.", MailDisplay.listTime("2026-09-03T10:00:00Z", now, Locale.FRANCE))
        assertEquals("12/11/2025", MailDisplay.listTime("2025-11-12T10:00:00Z", now, Locale.FRANCE))
    }

    @Test fun `an unparseable timestamp falls back to its first ten characters`() {
        assertEquals("2026-09-24", MailDisplay.listTime("2026-09-24 garbage", now, Locale.US))
    }

    @Test fun `initial skips punctuation and falls back to a question mark`() {
        assertEquals("A", MailDisplay.initial("\"alice\" <a@x.io>"))
        assertEquals("?", MailDisplay.initial("  --  "))
    }

    @Test fun `avatar hue is stable per address and ignores the display name and case`() {
        val a = MailDisplay.avatarHue("Alice <Alice@X.io>")
        assertEquals(a, MailDisplay.avatarHue("alice@x.io"), 0f)
        assertTrue(a in 0f..360f)
    }

    @Test fun `reply-all keeps sender and other recipients, drops own addresses and duplicates`() {
        val to = "Me <me@oshi.email>, Bob <bob@x.io>, alias@oshi.email; bob@x.io"
        assertEquals(
            "alice@x.io, bob@x.io",
            MailDisplay.replyAllRecipients("Alice <alice@x.io>", to, setOf("me@oshi.email", "ALIAS@oshi.email")),
        )
    }

    @Test fun `subjects are prefixed once`() {
        assertEquals("Re: Hi", MailDisplay.replySubject("Hi"))
        assertEquals("RE: Hi", MailDisplay.replySubject("RE: Hi"))
        assertEquals("Fwd: Hi", MailDisplay.forwardSubject("Hi"))
        assertEquals("Fw: Hi", MailDisplay.forwardSubject("Fw: Hi"))
    }

    @Test fun `forward quotes the original headers and body`() {
        val body = MailDisplay.forwardBody("Alice <a@x.io>", "me@oshi.email", "Tue, 23 Sep 2026", "Plan", "See you")
        assertTrue(body.contains("---------- Forwarded message ----------"))
        assertTrue(body.contains("From: Alice <a@x.io>"))
        assertTrue(body.contains("Subject: Plan"))
        assertTrue(body.endsWith("See you"))
    }

    @Test fun `recipient splitting tolerates commas, semicolons and blanks`() {
        assertEquals(listOf("a@x.io", "b@x.io", "c@x.io"), MailDisplay.splitRecipients(" a@x.io, ,b@x.io;c@x.io, "))
    }

    @Test fun `sizes read like the old fmt`() {
        assertEquals("900 B", MailDisplay.size(900))
        assertEquals("2 KB", MailDisplay.size(2048))
        assertEquals("1.5 MB", MailDisplay.size(1_572_864))
    }
}
