package com.oshi.messenger.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client-side search over already-decrypted envelopes — see [MailSearch]. A zero-access
 * mailbox cannot be searched server-side, so every one of these matches happens against
 * data this device already holds in memory.
 */
class MailSearchTest {

    private fun message(
        id: String = "m1",
        from: String = "",
        to: String = "",
        subject: String = "",
        deliveredTo: String = "",
        folder: String = "inbox",
    ) = OshiMailClient.Message(
        id = id,
        receivedAt = "2026-09-13T00:00:00Z",
        bytes = 100,
        seen = true,
        folder = folder,
        deliveredTo = deliveredTo,
        envelope = OshiMailClient.Envelope(from = from, to = to, subject = subject, date = ""),
    )

    @Test
    fun `blank query matches everything`() {
        assertTrue(MailSearch.matches(message(), "", deep = false, bodyCache = emptyMap()))
    }

    @Test
    fun `matches the subject case-insensitively`() {
        val m = message(subject = "Café — accentué")
        assertTrue(MailSearch.matches(m, "CAFÉ", deep = false, bodyCache = emptyMap()))
    }

    @Test
    fun `matches the sender`() {
        val m = message(from = "Jane Doe <jane@x.com>")
        assertTrue(MailSearch.matches(m, "jane@x.com", deep = false, bodyCache = emptyMap()))
    }

    @Test
    fun `matches the delivered-to alias`() {
        val m = message(deliveredTo = "shopping@mail.oshi-messenger.com")
        assertTrue(MailSearch.matches(m, "shopping", deep = false, bodyCache = emptyMap()))
    }

    @Test
    fun `a message sealed for another device still matches on deliveredTo`() {
        val sealed = OshiMailClient.Message(
            id = "m2", receivedAt = "", bytes = 1, seen = true, folder = "inbox",
            deliveredTo = "alpha", envelope = null,
        )
        assertTrue(MailSearch.matches(sealed, "alpha", deep = false, bodyCache = emptyMap()))
        assertFalse(MailSearch.matches(sealed, "nothing-here", deep = false, bodyCache = emptyMap()))
    }

    @Test
    fun `no match when the query is absent everywhere visible`() {
        val m = message(from = "a@x.com", to = "b@x.com", subject = "hello")
        assertFalse(MailSearch.matches(m, "zzz-not-present", deep = false, bodyCache = emptyMap()))
    }

    @Test
    fun `deep search off never looks inside a cached body`() {
        val m = message(id = "m3", subject = "unrelated")
        val bodies = mapOf("m3" to "the secret word is pineapple")
        assertFalse(MailSearch.matches(m, "pineapple", deep = false, bodyCache = bodies))
    }

    @Test
    fun `deep search on matches an already-decrypted body`() {
        val m = message(id = "m3", subject = "unrelated")
        val bodies = mapOf("m3" to "the secret word is pineapple")
        assertTrue(MailSearch.matches(m, "pineapple", deep = true, bodyCache = bodies))
    }

    @Test
    fun `deep search never matches a body that was never fetched`() {
        val m = message(id = "m4", subject = "unrelated")
        assertFalse(MailSearch.matches(m, "pineapple", deep = true, bodyCache = emptyMap()))
    }
}
