package com.oshi.messenger.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address book built LOCALLY from decrypted envelopes — see [MailContacts]. There is
 * deliberately no server-side contact store, so every address here must come only from
 * envelopes this test hands it directly, exactly like the ones opened during a session.
 */
class MailContactsTest {

    private fun message(from: String, to: String, cc: String = "", folder: String = "inbox") =
        OshiMailClient.Message(
            id = "id-$from-$to-$cc",
            receivedAt = "", bytes = 1, seen = true, folder = folder, deliveredTo = "",
            envelope = OshiMailClient.Envelope(from = from, to = to, subject = "", date = "", cc = cc),
        )

    @Test
    fun `harvests both sides of a conversation`() {
        val messages = listOf(message(from = "Jane Doe <jane@x.com>", to = "me@mail.oshi-messenger.com"))
        // Your own address is on every inbox message and is not a contact.
        val contacts = MailContacts.harvest(messages, own = setOf("me@mail.oshi-messenger.com"))
        assertEquals(listOf("jane@x.com" to "Jane Doe"), contacts)
    }

    @Test
    fun `harvests cc addresses too`() {
        val messages = listOf(message(from = "a@x.com", to = "b@x.com", cc = "c@x.com, d@x.com"))
        val contacts = MailContacts.harvest(messages).map { it.first }
        assertEquals(listOf("a@x.com", "b@x.com", "c@x.com", "d@x.com"), contacts)
    }

    @Test
    fun `deduplicates the same address across messages`() {
        val messages = listOf(
            message(from = "jane@x.com", to = "me@x.com"),
            message(from = "jane@x.com", to = "me@x.com"),
        )
        assertEquals(1, MailContacts.harvest(messages, own = setOf("me@x.com")).size)
    }

    @Test
    fun `keeps the friendliest spelling of a name`() {
        val messages = listOf(
            message(from = "jane@x.com", to = "me@x.com"),
            message(from = "Jane Doe <jane@x.com>", to = "me@x.com"),
        )
        val contacts = MailContacts.harvest(messages)
        assertEquals("Jane Doe", contacts.first { it.first == "jane@x.com" }.second)
    }

    @Test
    fun `ignores a sealed-for-another-device message`() {
        val sealed = OshiMailClient.Message(
            id = "s1", receivedAt = "", bytes = 1, seen = true, folder = "inbox",
            deliveredTo = "", envelope = null,
        )
        assertTrue(MailContacts.harvest(listOf(sealed)).isEmpty())
    }

    @Test
    fun `rejects a piece without an at-sign`() {
        val messages = listOf(message(from = "not-an-address", to = "b@x.com"))
        val contacts = MailContacts.harvest(messages).map { it.first }
        assertEquals(listOf("b@x.com"), contacts)
    }

    @Test
    fun `suggestions match on address or name and respect the limit`() {
        val contacts = listOf(
            "alice@x.com" to "Alice",
            "alicia@x.com" to "Alicia",
            "bob@x.com" to "Bob",
        )
        val hits = MailContacts.suggestions(contacts, "ali", limit = 1)
        assertEquals(1, hits.size)
        assertTrue(hits[0].first.startsWith("ali"))
    }

    @Test
    fun `an empty prefix suggests nothing`() {
        val contacts = listOf("alice@x.com" to "Alice")
        assertTrue(MailContacts.suggestions(contacts, "").isEmpty())
    }
}
