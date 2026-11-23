package com.oshi.messenger.ui

import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.ui.screens.ChatThreadItem
import com.oshi.messenger.ui.screens.ChatThreadItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-1. The 1:1 thread renders a FILTERED list with date headers interleaved, so
 * a message's index in `messages` is not its index in the LazyColumn. Three
 * jumps (reply quote, pinned banner, search) used the wrong one.
 *
 * Every expected key list below is hand-written. Nothing here is produced by
 * `ChatThreadItems.build`, so a build that silently agrees with itself still
 * fails these.
 */
class ChatThreadItemsTest {

    /**
     * Timestamps are `DDHH`: the day is the leading digits. Injecting this keeps
     * the mapping independent of the device timezone, which is the whole reason
     * `build` takes `dayOf` as a parameter.
     */
    private val dayOf: (Long) -> String = { ts -> (ts / 100L).toString() }

    private fun msg(id: String, ts: Long, content: String = "hello $id") = Message(
        id = id,
        senderPublicKey = "peer",
        recipientPublicKey = "me",
        content = content,
        timestamp = ts,
        isFromMe = false
    )

    /** (N17) A captionless photo — "[Image]" matches the bracketed-placeholder convention `isStackablePhoto` checks for. */
    private fun img(id: String, ts: Long, isFromMe: Boolean = false, caption: String? = null) = Message(
        id = id,
        senderPublicKey = "peer",
        recipientPublicKey = "me",
        content = caption ?: "[Image]",
        timestamp = ts,
        isFromMe = isFromMe,
        mediaType = MediaType.IMAGE
    )

    // The thread is drawn with reverseLayout = true: index 0 is the NEWEST row.
    // These fixtures are written newest-first, exactly as the screen receives them.

    @Test
    fun `three messages on one day produce three bubbles and a single trailing header`() {
        val messages = listOf(
            msg("c", 301),
            msg("b", 302),
            msg("a", 303)
        )

        val keys = ChatThreadItems.build(messages, dayOf).map { it.key }

        assertEquals(listOf("c", "b", "a", "date_header_a"), keys)
    }

    @Test
    fun `a day boundary inserts a header after the message that opens the newer day`() {
        val messages = listOf(
            msg("d4", 402),   // day 4
            msg("d4b", 401),  // day 4
            msg("d3", 302),   // day 3
            msg("d3b", 301)   // day 3, oldest
        )

        val keys = ChatThreadItems.build(messages, dayOf).map { it.key }

        assertEquals(
            listOf("d4", "d4b", "date_header_d4b", "d3", "d3b", "date_header_d3b"),
            keys
        )
    }

    @Test
    fun `silent payloads get no row and shift every position below them`() {
        val messages = listOf(
            msg("m3", 303),
            msg("receipt", 303, OSHIControlPayload.DELIVERY_RECEIPT + "m1"),
            msg("m2", 302),
            msg("typing", 302, OSHIControlPayload.TYPING_IOS + "{}"),
            msg("m1", 301)
        )

        val items = ChatThreadItems.build(messages, dayOf)

        assertEquals(listOf("m3", "m2", "m1", "date_header_m1"), items.map { it.key })
        // The naive index of m1 in `messages` is 4. Its real rendered row is 2.
        assertEquals(4, messages.indexOfFirst { it.id == "m1" })
        assertEquals(2, ChatThreadItems.positionOf(items, "m1"))
    }

    @Test
    fun `profile and wallpaper markers are hidden, call summaries and locations are not`() {
        val messages = listOf(
            msg("wall", 301, OSHIControlPayload.WALLPAPER_UPDATE + "{}"),
            msg("prof", 301, OSHIControlPayload.PROFILE_UPDATE + "{}"),
            msg("call", 301, OSHIControlPayload.CALL_SUMMARY + "{\"duration\":83}"),
            msg("loc", 301, OSHIControlPayload.LOCATION + "{\"latitude\":48.85}"),
            msg("checkin", 301, OSHIControlPayload.CHECK_IN + "{}")
        )

        val keys = ChatThreadItems.build(messages, dayOf).map { it.key }

        assertEquals(listOf("call", "loc", "checkin", "date_header_checkin"), keys)
    }

    @Test
    fun `date headers and hidden rows shift a target by their combined count`() {
        // Two hidden rows and two headers sit above `target` in render order.
        val messages = listOf(
            msg("n1", 502),                                              // day 5
            msg("hidden1", 502, OSHIControlPayload.READ_RECEIPT + "x"),  // no row
            msg("n2", 501),                                              // day 5
            msg("n3", 402),                                              // day 4
            msg("hidden2", 402, OSHIControlPayload.ACTION + "{}"),       // no row
            msg("target", 401),                                          // day 4
            msg("old", 301)                                              // day 3
        )

        val items = ChatThreadItems.build(messages, dayOf)

        assertEquals(
            listOf("n1", "n2", "date_header_n2", "n3", "target", "date_header_target", "old", "date_header_old"),
            items.map { it.key }
        )
        // Index in `messages` says 5. The rendered row is 4.
        assertEquals(5, messages.indexOfFirst { it.id == "target" })
        assertEquals(4, ChatThreadItems.positionOf(items, "target"))
        assertEquals(0, ChatThreadItems.positionOf(items, "n1"))
        assertEquals(6, ChatThreadItems.positionOf(items, "old"))
    }

    @Test
    fun `positionOf refuses an unrendered id rather than pointing somewhere`() {
        val messages = listOf(
            msg("m2", 302),
            msg("gone", 302, OSHIControlPayload.DELIVERY_RECEIPT + "m1"),
            msg("m1", 301)
        )

        val items = ChatThreadItems.build(messages, dayOf)

        assertEquals(-1, ChatThreadItems.positionOf(items, "gone"))
        assertEquals(-1, ChatThreadItems.positionOf(items, "never-existed"))
        assertEquals(-1, ChatThreadItems.positionOf(items, null))
        assertEquals(-1, ChatThreadItems.positionOf(items, ""))
    }

    @Test
    fun `a date header key never resolves as a message position`() {
        val items = ChatThreadItems.build(listOf(msg("a", 301)), dayOf)

        assertEquals(0, ChatThreadItems.positionOf(items, "a"))
        assertEquals(-1, ChatThreadItems.positionOf(items, "date_header_a"))
    }

    @Test
    fun `messageForKey resolves both a bubble key and its date-header twin`() {
        val a = msg("a", 402)
        val b = msg("b", 301)
        val items = ChatThreadItems.build(listOf(a, b), dayOf)

        assertSame(a, ChatThreadItems.messageForKey(items, "a"))
        assertSame(a, ChatThreadItems.messageForKey(items, "date_header_a"))
        assertSame(b, ChatThreadItems.messageForKey(items, "date_header_b"))
        assertNull(ChatThreadItems.messageForKey(items, "nope"))
        assertNull(ChatThreadItems.messageForKey(items, null))
    }

    @Test
    fun `an empty thread renders nothing`() {
        val items = ChatThreadItems.build(emptyList(), dayOf)

        assertEquals(emptyList<ChatThreadItem>(), items)
        assertEquals(-1, ChatThreadItems.positionOf(items, "anything"))
    }

    @Test
    fun `a thread of only silent payloads renders nothing at all`() {
        val messages = listOf(
            msg("r1", 301, OSHIControlPayload.READ_RECEIPT + "a"),
            msg("r2", 301, OSHIControlPayload.DELIVERY_RECEIPT + "b")
        )

        assertEquals(emptyList<ChatThreadItem>(), ChatThreadItems.build(messages, dayOf))
    }

    @Test
    fun `renderable keeps order and identity`() {
        val kept = msg("k", 301)
        val dropped = msg("d", 301, OSHIControlPayload.READ_RECEIPT + "k")

        val result = ChatThreadItems.renderable(listOf(kept, dropped))

        assertEquals(1, result.size)
        assertSame(kept, result[0])
    }

    // (N17) Photo stacks.

    @Test
    fun `two captionless photos from the same sender close in time become one stack, oldest first`() {
        val messages = listOf(
            img("p2", 302), // newest-first fixture order, like every other test here
            img("p1", 301)
        )

        val items = ChatThreadItems.build(messages, dayOf)

        // The stack, plus the trailing date header every single-day fixture
        // in this file ends with (see the very first test above).
        assertEquals(2, items.size)
        val stack = items[0] as ChatThreadItem.PhotoStack
        assertEquals(listOf("p1", "p2"), stack.messages.map { it.id }) // oldest first, reversed from fixture order
        assertEquals(ChatThreadItem.PHOTO_STACK_PREFIX + "p1", stack.key)
        assertEquals(ChatThreadItem.DateHeader("p1", 301), items[1])
    }

    @Test
    fun `a lone photo with no run stays a plain bubble`() {
        val messages = listOf(img("p1", 301))

        val items = ChatThreadItems.build(messages, dayOf)

        assertEquals(2, items.size) // bubble + trailing date header
        assertTrue(items[0] is ChatThreadItem.Bubble)
    }

    @Test
    fun `a captioned photo never joins a stack`() {
        val messages = listOf(
            img("p2", 302, caption = "look at this"),
            img("p1", 301)
        )

        val keys = ChatThreadItems.build(messages, dayOf).map { it.key }

        // Two separate bubbles, not a stack — "p2" has its own content.
        assertEquals(listOf("p2", "p1", "date_header_p1"), keys)
    }

    @Test
    fun `a text message between two photos breaks the run`() {
        val messages = listOf(
            img("p2", 303),
            msg("t", 302, "hi"),
            img("p1", 301)
        )

        val keys = ChatThreadItems.build(messages, dayOf).map { it.key }

        assertEquals(listOf("p2", "t", "p1", "date_header_p1"), keys)
    }

    @Test
    fun `positionOf and messageForKey resolve a message inside a stack`() {
        val messages = listOf(img("p2", 302), img("p1", 301))
        val items = ChatThreadItems.build(messages, dayOf)

        assertEquals(0, ChatThreadItems.positionOf(items, "p1"))
        assertEquals(0, ChatThreadItems.positionOf(items, "p2"))
        assertEquals("p1", ChatThreadItems.messageForKey(items, "p1")?.id)
    }

    // __OPEN_SCROLL_2026_11_16__ / __OPEN_AT_THE_FIRST_UNREAD__

    @Test
    fun `newestMessage is row 0, the newest photo of a stack, and null while nothing is rendered`() {
        assertNull(ChatThreadItems.newestMessage(emptyList()))

        val text = ChatThreadItems.build(listOf(msg("b", 302), msg("a", 301)), dayOf)
        assertEquals("b", ChatThreadItems.newestMessage(text)?.id)

        // Stack photos are kept oldest-first; the newest is the LAST of them.
        val stack = ChatThreadItems.build(listOf(img("p2", 302), img("p1", 301)), dayOf)
        assertEquals("p2", ChatThreadItems.newestMessage(stack)?.id)
    }

    @Test
    fun `open lands on the oldest unread row that is actually drawn`() {
        val silent = msg("s", 303, OSHIControlPayload.DELIVERY_RECEIPT + "m1")
        val items = ChatThreadItems.build(listOf(msg("c", 304), silent, msg("b", 302), msg("a", 301)), dayOf)

        // "s" is a silent payload with no row: skipped, the next unread is the anchor.
        assertEquals("c", ChatThreadItems.openUnreadAnchor(items, listOf("s", "c")))
        assertEquals("b", ChatThreadItems.openUnreadAnchor(items, listOf("b", "c")))
        // Nothing unread, or nothing unread that is drawn: land on the newest.
        assertNull(ChatThreadItems.openUnreadAnchor(items, emptyList()))
        assertNull(ChatThreadItems.openUnreadAnchor(items, listOf("s", "gone")))
    }

    @Test
    fun `a new message follows when it is yours or when the previous newest rows were on screen`() {
        val previous = listOf("m3", "m2", "m1")

        assertTrue(ChatThreadItems.shouldFollowNewest(true, previous, emptyList()))
        assertTrue(ChatThreadItems.shouldFollowNewest(false, previous, listOf("m2", "m1", "m0")))
        // A burst of four: the rows that were newest are still the ones on screen,
        // whatever index LazyColumn has shifted them to.
        assertTrue(ChatThreadItems.shouldFollowNewest(false, previous, listOf("m3")))
        // Reading history: none of the newest rows visible — never yanked.
        assertFalse(ChatThreadItems.shouldFollowNewest(false, previous, listOf("m40", "m41")))
        // Unknown history (screen restored): only your own send moves the list.
        assertFalse(ChatThreadItems.shouldFollowNewest(false, emptyList(), listOf("m3")))
    }
}
