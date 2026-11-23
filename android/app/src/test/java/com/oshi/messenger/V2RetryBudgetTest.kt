package com.oshi.messenger

import com.oshi.messenger.network.v2.V2RetryBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ack-hold budget parity with iOS `V2SyncState.shouldRetryUndecryptable`
 * (`maxRetries = 8`, `maxAckHoldSeconds = 20 min`).
 *
 * Before this existed, ONE permanently-undecryptable envelope pinned the relay
 * cursor at `lowestFailedSeq - 1` forever and every message behind it was
 * re-pulled and never delivered.
 */
class V2RetryBudgetTest {

    private class MemStore : V2RetryBudget.Store {
        var entries: Map<String, V2RetryBudget.Entry> = emptyMap()
        override fun read() = entries
        override fun write(entries: Map<String, V2RetryBudget.Entry>) { this.entries = entries }
    }

    private class MemStall : V2RetryBudget.Stall {
        var since: Long = 0L
        override fun since() = since
        override fun set(millis: Long) { since = millis }
    }

    /** A clock the test moves by hand, so the 20-minute ceiling is testable in milliseconds. */
    private class Clock(var now: Long = 1_700_000_000_000L) : () -> Long {
        override fun invoke() = now
    }

    @Test
    fun `holds for exactly eight attempts then gives up`() {
        val store = MemStore()
        val budget = V2RetryBudget(store, MemStall(), Clock())
        for (i in 1..V2RetryBudget.MAX_RETRIES) {
            assertTrue("attempt $i must still hold", budget.shouldRetry("m1"))
        }
        assertFalse(budget.shouldRetry("m1"))
        assertFalse(budget.shouldRetry("m1"))
        assertEquals(8, V2RetryBudget.MAX_RETRIES)
    }

    @Test
    fun `counters are per message id`() {
        val budget = V2RetryBudget(MemStore(), MemStall(), Clock())
        repeat(V2RetryBudget.MAX_RETRIES + 1) { budget.shouldRetry("spent") }
        assertFalse(budget.shouldRetry("spent"))
        assertTrue("a different envelope starts with a full budget", budget.shouldRetry("fresh"))
    }

    @Test
    fun `clear resets a recovered envelope`() {
        val store = MemStore()
        val budget = V2RetryBudget(store, MemStall(), Clock())
        repeat(5) { budget.shouldRetry("m2") }
        budget.clear("m2")
        assertTrue(store.entries.isEmpty())
        for (i in 1..V2RetryBudget.MAX_RETRIES) assertTrue(budget.shouldRetry("m2"))
        assertFalse(budget.shouldRetry("m2"))
    }

    @Test
    fun `clearing an unknown id is a no-op and does not rewrite the store`() {
        val store = MemStore()
        val budget = V2RetryBudget(store, MemStall(), Clock())
        budget.shouldRetry("a")
        val before = store.entries
        budget.clear("unknown")
        assertTrue(before === store.entries)
    }

    @Test
    fun `the map is bounded so a long-lived install cannot grow it forever`() {
        val store = MemStore()
        val clock = Clock()
        val budget = V2RetryBudget(store, MemStall(), clock)
        repeat(V2RetryBudget.CAP + 50) { clock.now += 1; budget.shouldRetry("msg-$it") }
        assertEquals(V2RetryBudget.CAP, store.entries.size)
        // Eviction keeps the most RECENT first-failures, so the newest id survives.
        assertTrue(store.entries.containsKey("msg-${V2RetryBudget.CAP + 49}"))
        assertFalse(store.entries.containsKey("msg-0"))
    }

    /**
     * __BUDGET_REFUND_DEADLOCK_2026_10_20__ THE regression test. The old code evicted
     * with `takeLast(CAP)` over insertion order and `put` does not reorder an existing
     * key, so the envelope holding the ack — the oldest — was dropped on every tick and
     * its counter came back as 1. It could never reach MAX_RETRIES, the ack was held
     * forever, and the backlog it caused is what kept the map over the cap.
     *
     * The old test passed because it never ran with a backlog LARGER than the cap.
     */
    @Test
    fun `the entry touched by this call survives its own eviction`() {
        val store = MemStore()
        val clock = Clock()
        val budget = V2RetryBudget(store, MemStall(), clock)

        // Fill well past the cap with newer failures, then touch the OLDEST id.
        repeat(V2RetryBudget.CAP * 2) { i -> clock.now += 1; budget.shouldRetry("tick-$i") }
        clock.now += 1
        budget.shouldRetry("oldest")
        assertTrue("the current envelope must never be evicted by its own call",
                   store.entries.containsKey("oldest"))
        assertEquals(V2RetryBudget.CAP, store.entries.size)
    }

    @Test
    fun `the hold is released once the cursor has not moved for twenty minutes`() {
        val clock = Clock()
        val budget = V2RetryBudget(MemStore(), MemStall(), clock)

        // First call primes the stall clock, so a fresh install is not born stalled.
        assertTrue(budget.shouldRetry("slow"))
        clock.now += V2RetryBudget.MAX_ACK_HOLD_MS - 1
        assertTrue(budget.shouldRetry("slow"))
        clock.now += 2
        assertFalse(budget.shouldRetry("slow"))
    }

    @Test
    fun `a draining mailbox keeps its full budget because the cursor keeps moving`() {
        val clock = Clock()
        val budget = V2RetryBudget(MemStore(), MemStall(), clock)

        // Hours pass, but the cursor advances on every poll: nothing is force-released,
        // so a slow-but-healthy mailbox still gets all 8 attempts per envelope.
        repeat(20) {
            clock.now += V2RetryBudget.MAX_ACK_HOLD_MS / 2
            budget.noteCursorMoved()
        }
        for (i in 1..V2RetryBudget.MAX_RETRIES) {
            assertTrue("attempt $i must still hold", budget.shouldRetry("healthy"))
            clock.now += 1
        }
        assertFalse(budget.shouldRetry("healthy"))
    }

    /**
     * The guarantee has to survive the very thing that broke the first attempt at it:
     * a backlog bigger than the cap, where every counter AND every per-envelope
     * timestamp is evicted and reborn on each tick. Only a number outside the evicted
     * state can still release the ack.
     */
    @Test
    fun `an already-frozen mailbox is released even though every counter is evicted`() {
        val store = MemStore()
        val clock = Clock()
        val budget = V2RetryBudget(store, MemStall(), clock)

        var verdict = true
        repeat(V2RetryBudget.MAX_RETRIES + 2) {
            verdict = budget.shouldRetry("stuck")
            repeat(V2RetryBudget.CAP * 2) { i -> clock.now += 1; budget.shouldRetry("tick-${clock.now}-$i") }
            clock.now += V2RetryBudget.MAX_ACK_HOLD_MS / 4
        }
        assertFalse("a frozen mailbox must unwedge itself", verdict)
    }
}
