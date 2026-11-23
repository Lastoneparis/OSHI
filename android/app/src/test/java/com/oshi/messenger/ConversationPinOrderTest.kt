package com.oshi.messenger

import com.oshi.messenger.data.model.ConversationPinOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N18 — pinned conversations rise, and nothing else moves. */
class ConversationPinOrderTest {

    private data class Row(val id: String)

    private fun order(ids: List<String>, pinned: Set<String>): List<String> =
        ConversationPinOrder.apply(ids.map { Row(it) }, pinned) { it.id }.map { it.id }

    @Test
    fun `with nothing pinned the list is untouched`() {
        assertEquals(listOf("a", "b", "c"), order(listOf("a", "b", "c"), emptySet()))
    }

    @Test
    fun `a pinned conversation rises to the top`() {
        assertEquals(listOf("c", "a", "b"), order(listOf("a", "b", "c"), setOf("c")))
    }

    /**
     * THE PROPERTY A HAND-ROLLED SORT LOSES. The repository already ordered this list by
     * recency; pinning must not reshuffle what it did not pin, in either half.
     */
    @Test
    fun `recency order survives inside both halves`() {
        assertEquals(
            listOf("b", "d", "a", "c", "e"),
            order(listOf("a", "b", "c", "d", "e"), setOf("b", "d")),
        )
    }

    /** Someone pins a thread, deletes it, and the id lingers. It must leave no hole. */
    @Test
    fun `a pin for a conversation that no longer exists is inert`() {
        assertEquals(listOf("a", "b"), order(listOf("a", "b"), setOf("gone")))
    }

    @Test
    fun `pinning everything changes nothing`() {
        assertEquals(listOf("a", "b", "c"), order(listOf("a", "b", "c"), setOf("a", "b", "c")))
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(emptyList<String>(), order(emptyList(), setOf("a")))
    }

    // ---------------------------------------------------------------- toggling

    @Test
    fun `toggling adds then removes`() {
        val once = ConversationPinOrder.toggled("a", emptySet())
        assertEquals(setOf("a"), once)
        assertEquals(emptySet<String>(), ConversationPinOrder.toggled("a", once))
    }

    @Test
    fun `toggling one pin leaves the others alone`() {
        assertEquals(setOf("a"), ConversationPinOrder.toggled("b", setOf("a", "b")))
    }

    /** A new set, never a mutated one: a store and a flow that disagree is a pin that
     *  works until the app restarts. */
    @Test
    fun `toggling does not mutate the input`() {
        val original = setOf("a")
        ConversationPinOrder.toggled("b", original)
        assertEquals(setOf("a"), original)
    }

    @Test
    fun `isPinned is the one place membership is decided`() {
        assertTrue(ConversationPinOrder.isPinned("a", setOf("a", "b")))
        assertFalse(ConversationPinOrder.isPinned("c", setOf("a", "b")))
    }
}
