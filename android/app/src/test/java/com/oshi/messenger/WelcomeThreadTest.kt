package com.oshi.messenger

import com.oshi.messenger.service.WelcomeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __WELCOME_IS_A_REAL_CONVERSATION_2026_11_08__
 *
 * "Be able to send a message straight from the Hugo Créateur conversation
 * instead of adding the contact first."
 *
 * The greeting came from `oshi.welcome`, a label rather than a key. Replying
 * fed it to `deriveSharedSecret`, which decoded 8 bytes and threw — and that is
 * what Play's reviewer photographed on 2026-09-16 when they rejected the app
 * for broken functionality. Android's answer was to make the thread read-only.
 * iOS instead pointed it at the author's real account on 2026-10-16, which is
 * what a new user actually needs: their only conversation, answerable.
 *
 * These tests exist because the obvious regression here re-creates a PLAY
 * REJECTION, not a cosmetic bug.
 */
class WelcomeThreadTest {

    /** THE test: the greeting must come from something that can receive a reply. */
    @Test
    fun `the greeting comes from a real key, not a label`() {
        assertEquals(
            "the welcome sender must be the author's real key",
            WelcomeMessage.AUTHOR_PUBLIC_KEY,
            WelcomeMessage.SYSTEM_SENDER_KEY,
        )
        assertNotEquals(
            "the welcome sender is back on the label that threw in deriveSharedSecret",
            "oshi.welcome",
            WelcomeMessage.SYSTEM_SENDER_KEY,
        )
    }

    /**
     * The key has to survive base64 decoding to 32 bytes — that is the exact
     * property `oshi.welcome` lacked, and the reason every reply failed.
     */
    @Test
    fun `the welcome sender decodes to a 32 byte key`() {
        val decoded = java.util.Base64.getDecoder().decode(WelcomeMessage.SYSTEM_SENDER_KEY)
        assertEquals(
            "a sender that is not 32 bytes throws in deriveSharedSecret — the 2026-09-16 rejection",
            32, decoded.size,
        )
    }

    /** The legacy label is kept, because the migration needs something to match. */
    @Test
    fun `the legacy label is retained for migration`() {
        assertEquals("oshi.welcome", WelcomeMessage.LEGACY_SYSTEM_SENDER_KEY)
    }

    /**
     * BOTH columns, which is the lesson iOS paid for: the rows that survive are
     * the user's own replies, where the legacy string is the RECIPIENT. Re-key
     * only the sender and the thread stays split and unanswerable.
     */
    @Test
    fun `the migration re-keys both directions`() {
        val dao = SourceText.read("src/main/java/com/oshi/messenger/data/local/AppDatabase.kt")
        assertTrue(
            "no sender-side migration",
            dao.contains("UPDATE messages SET senderPublicKey = :realKey WHERE senderPublicKey = :legacyKey"),
        )
        assertTrue(
            "no RECIPIENT-side migration — the user's own replies would be left behind",
            dao.contains("UPDATE messages SET recipientPublicKey = :realKey WHERE recipientPublicKey = :legacyKey"),
        )
    }

    /**
     * And it must run BEFORE the `shouldSeed` early return. Everyone who needs
     * migrating has already been seeded, so a migration placed after that return
     * would reach nobody at all.
     */
    @Test
    fun `the migration runs before the seeding early return`() {
        val seeder = SourceText.read("src/main/java/com/oshi/messenger/service/WelcomeMessageSeeder.kt")
        val migrate = seeder.indexOf("migrateLegacyWelcomeThread(app)")
        val guard = seeder.indexOf("if (!WelcomeMessage.shouldSeed(")
        assertTrue("the migration call is gone", migrate > 0)
        assertTrue("the shouldSeed guard is gone", guard > 0)
        assertTrue(
            "the migration runs AFTER the early return, so it reaches nobody who needs it",
            migrate < guard,
        )
    }

    /** The composer must not be locked for the real thread. */
    @Test
    fun `only the legacy label is refused, not the real key`() {
        val vm = SourceText.read("src/main/java/com/oshi/messenger/ui/viewmodel/ChatViewModel.kt")
        assertTrue(
            "the send guard still refuses the real key — the thread stays unanswerable",
            vm.contains("if (pk != WelcomeMessage.LEGACY_SYSTEM_SENDER_KEY) return false"),
        )
        assertTrue(
            "isSystemThread must key off the LEGACY label only",
            vm.contains("it == WelcomeMessage.LEGACY_SYSTEM_SENDER_KEY"),
        )
    }

    /** Honesty: a working composer, with a line saying who is on the other end. */
    @Test
    fun `the thread says who answers`() {
        val chat = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/ChatScreen.kt")
        assertTrue(
            "the creator notice is gone — the greeting would read as written by a person",
            chat.contains("R.string.chat_welcome_creator_notice"),
        )
        assertTrue("the notice is not bound to the welcome thread", chat.contains("isWelcomeThread"))
    }
}
