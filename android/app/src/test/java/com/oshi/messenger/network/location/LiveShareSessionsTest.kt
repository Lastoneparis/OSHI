package com.oshi.messenger.network.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * __MULTI_LIVE_SHARE_2026_09_22__ Several live shares at once, one per
 * conversation. Before, starting a share with a second contact stopped the first.
 */
class LiveShareSessionsTest {

    private fun share(conv: String, session: String, end: Long = 10_000L) =
        ActiveLiveShare(conversationId = conv, sessionId = session, startTimeMillis = 0L, endTimeMillis = end)

    @Test
    fun `two conversations share at the same time`() {
        val s = LiveShareSessions()
        s.add(share("alice", "s1"))
        s.add(share("bob", "s2"))
        assertEquals(listOf("s1", "s2"), s.snapshot().map { it.sessionId })
        assertEquals("s2", s.forConversation("bob")?.sessionId)
    }

    @Test
    fun `stopping one share leaves the other running`() {
        val s = LiveShareSessions()
        s.add(share("alice", "s1"))
        s.add(share("bob", "s2"))
        assertEquals("s1", s.remove("s1")?.sessionId)
        assertEquals(listOf("bob"), s.snapshot().map { it.conversationId })
    }

    @Test
    fun `stopping twice is a no-op so onStop cannot fire twice`() {
        val s = LiveShareSessions()
        s.add(share("alice", "s1"))
        s.remove("s1")
        assertNull(s.remove("s1"))
    }

    @Test
    fun `extending moves only that share's end`() {
        val s = LiveShareSessions()
        s.add(share("alice", "s1", end = 1_000L))
        s.add(share("bob", "s2", end = 2_000L))
        s.extend("s1", 60_000L)
        assertEquals(61_000L, s.bySession("s1")?.endTimeMillis)
        assertEquals(2_000L, s.bySession("s2")?.endTimeMillis)
        assertEquals(61_000L, s.latestEndMillis())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a second share in the SAME conversation must replace, not stack`() {
        val s = LiveShareSessions()
        s.add(share("alice", "s1"))
        s.add(share("alice", "s2"))
    }

    @Test
    fun `expired lists only shares past their end`() {
        val s = LiveShareSessions()
        s.add(share("alice", "s1", end = 1_000L))
        s.add(share("bob", "s2", end = 5_000L))
        assertEquals(listOf("s1"), s.expired(now = 2_000L).map { it.sessionId })
    }

    @Test
    fun `remaining seconds never go negative`() {
        assertEquals(0L, share("a", "s", end = 1_000L).remainingSeconds(now = 5_000L))
        assertEquals(4L, share("a", "s", end = 5_000L).remainingSeconds(now = 1_000L))
    }
}
