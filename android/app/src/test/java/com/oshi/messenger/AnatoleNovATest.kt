package com.oshi.messenger

import com.oshi.messenger.data.model.ConversationKeySet
import com.oshi.messenger.data.model.SavedContactThreads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anatole, 13/11, batch A — the parts that are rules rather than layout.
 *
 *  - N8: a one-to-one mute is a persisted key set, matched in any base64 spelling.
 *  - N18: a saved contact without a message is listed; a deleted conversation and
 *    a radio key are not.
 *  - N13: the Chats tab is reached by popping, never by a restoring navigate
 *    (source guard: the NavController rule cannot run without Android).
 *  - N8: the row's long-press opens the sheet and every entry acts.
 */
class AnatoleNovATest {

    private fun memorySet(initial: Set<String> = emptySet()): Pair<ConversationKeySet, () -> Set<String>> {
        var stored = initial
        val set = ConversationKeySet(load = { stored }, persist = { stored = it })
        return set to { stored }
    }

    // ── N8 mute store ────────────────────────────────────────────────────

    @Test
    fun `toggle mutes then unmutes and persists each time`() {
        val (set, stored) = memorySet()
        assertTrue(set.toggle("abc+/def="))
        assertTrue(set.contains("abc+/def="))
        assertEquals(setOf("abc+/def"), stored())
        assertFalse(set.toggle("abc+/def="))
        assertFalse(set.contains("abc+/def="))
        assertEquals(emptySet<String>(), stored())
    }

    @Test
    fun `a key muted in standard base64 matches its base64url spelling`() {
        val (set, _) = memorySet()
        set.set("ab+cd/ef==", true)
        assertTrue(set.contains("ab-cd_ef"))
        assertTrue(set.contains(" ab+cd/ef= "))
    }

    @Test
    fun `blank keys are never members`() {
        val (set, stored) = memorySet()
        assertFalse(set.set("   ", true))
        assertFalse(set.contains(""))
        assertFalse(set.contains(null))
        assertEquals(emptySet<String>(), stored())
    }

    @Test
    fun `loaded keys are normalised`() {
        val (set, _) = memorySet(setOf("xy-z_=="))
        assertTrue(set.contains("xy+z/"))
    }

    // ── N18 saved contacts ───────────────────────────────────────────────

    @Test
    fun `a saved contact with no message is listed`() {
        val saved = setOf(ConversationKeySet.normalize("Key+One/="))
        assertTrue(SavedContactThreads.showsEmptyThread("Key-One_", saved, isRadioKey = false))
    }

    @Test
    fun `a contact that was never saved, or whose thread was deleted, is not`() {
        val (set, stored) = memorySet()
        set.set("Key+One/=", true)
        set.set("Key+One/=", false) // deleteConversation
        assertFalse(SavedContactThreads.showsEmptyThread("Key+One/=", stored(), isRadioKey = false))
        assertFalse(SavedContactThreads.showsEmptyThread("Other", stored(), isRadioKey = false))
    }

    @Test
    fun `a radio key is never listed empty, saved or not`() {
        val saved = setOf("lora!1234abcd")
        assertFalse(SavedContactThreads.showsEmptyThread("lora!1234abcd", saved, isRadioKey = true))
    }

    // ── source guards ────────────────────────────────────────────────────

    private val mainActivity by lazy {
        SourceText.stripComments(SourceText.read("src/main/java/com/oshi/messenger/ui/MainActivity.kt"))
    }
    private val conversations by lazy {
        SourceText.stripComments(SourceText.read("src/main/java/com/oshi/messenger/ui/screens/ConversationsScreen.kt"))
    }

    @Test
    fun `the Chats tab pops back to the list instead of restoring a saved stack`() {
        val body = mainActivity.substringAfter("fun androidx.navigation.NavController.selectTab(")
            .substringBefore("\n}\n")
        val chatsBranch = body.substringAfter("if (route == ROUTE_MESSAGES)").substringBefore("return")
        assertTrue("Chats must pop", chatsBranch.contains("popBackStack(ROUTE_MESSAGES"))
        assertFalse("Chats must never restoreState", chatsBranch.contains("restoreState"))
        assertTrue("both the bar and the rail go through selectTab",
            mainActivity.contains("navController.selectTab(route)"))
    }

    @Test
    fun `the conversation row long-press opens the sheet, and its entries act`() {
        assertTrue(conversations.contains("onLongClick = { sheetFor = conversation }"))
        assertTrue(conversations.contains("viewModel.togglePinned(key)"))
        assertTrue(conversations.contains("viewModel.toggleMuted(key)"))
        assertTrue(conversations.contains("PendingContactCard.request(key)"))
        // ...and the chat actually takes the request.
        val chat = SourceText.read("src/main/java/com/oshi/messenger/ui/screens/ChatScreen.kt")
        assertTrue(chat.contains("PendingContactCard.consume(publicKey)"))
    }

    @Test
    fun `the search field placeholder is the one-word search string`() {
        assertTrue(conversations.contains("stringResource(R.string.search)"))
        assertFalse(conversations.contains("conversations_search_placeholder"))
    }
}
