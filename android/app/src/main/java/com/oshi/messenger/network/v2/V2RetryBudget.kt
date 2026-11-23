package com.oshi.messenger.network.v2

/**
 * Bounded retry budget for envelopes we could not decrypt (or whose media blob we
 * could not fetch). Port of iOS `V2SyncState.shouldRetryUndecryptable` /
 * `clearRetries` (MessageManager+V2.swift).
 *
 * WHY IT EXISTS — holding the relay ack unconditionally, which is what Android did
 * before, means ONE permanently-undecryptable envelope (peer reinstalled, our SPK
 * regenerated after a Keystore wipe, a forged header) pins `ackSeq` at
 * `lowestFailedSeq - 1` FOREVER. Every message behind it is re-pulled and never
 * delivered: the mailbox is wedged for the life of the install. Not holding at all
 * is the opposite failure — an out-of-order arrival or a momentary ratchet desync
 * loses a message the user was already pushed about. So retries are BOUNDED: hold
 * while budget remains, then let the ack pass so the queue drains.
 *
 * Semantics are iOS's EXACTLY: the counter is incremented on every call and the
 * call returns `n <= maxRetries`, so attempts 1..8 hold and the 9th gives up.
 *
 * __BUDGET_REFUND_DEADLOCK_2026_10_20__ A BOUNDED budget is not enough on its own,
 * because the bound is only honoured while the COUNTER SURVIVES. Measured on iOS
 * the same day, on a real mailbox: 7453 envelopes, oldest 13 days, not one ever
 * acknowledged.
 *
 *   The map is capped at [CAP]. Eviction used to be `takeLast(CAP)` over insertion
 *   order, and `put` on a key that is already present does NOT move it to the end.
 *   So with a backlog larger than the cap, the envelope that holds the ack — the
 *   oldest, inserted first — is the one evicted on every tick. Its counter comes
 *   back as 1, it never reaches [MAX_RETRIES], the ack is held FOREVER, and an ack
 *   that never moves is exactly what keeps the backlog above the cap. The loop
 *   feeds itself, and it wedges EVERY conversation, not just the broken peer's.
 *
 * Two changes close it, and only the second is the guarantee:
 *
 *  1. Eviction is by FIRST-FAILURE TIMESTAMP, not insertion order, and the entry
 *     touched by this very call always survives its own eviction. Deterministic,
 *     and it keeps what is actually in flight rather than what happened to be
 *     written last. This is an OPTIMISATION, nothing more.
 *  2. [Stall]: how long the ack CURSOR has not advanced — one number, never
 *     evictable. Past [MAX_ACK_HOLD_MS] nothing holds the ack any more.
 *
 * Why the ceiling cannot live on the envelope, which is what this port tried
 * first and what its own regression test rejected: an envelope evicted from the
 * cache comes back with a fresh counter AND a fresh timestamp, so its budget is
 * refunded either way. With a backlog larger than [CAP] that is the NORMAL case,
 * not the rare one. No per-envelope bound can hold inside capped state — the
 * property we want does not belong to the envelope, it belongs to the CURSOR.
 * (Same reasoning, same words, as iOS `V2SyncState.cursorStalledSince`.)
 *
 * This is also what unwedges a mailbox that is ALREADY frozen, whatever the
 * backlog and whatever the counters say.
 *
 * Storage, the stall clock and the wall clock are injected so the logic is
 * unit-testable on the JVM, without SharedPreferences and without waiting twenty
 * real minutes.
 */
class V2RetryBudget(
    private val store: Store,
    private val stall: Stall,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * One envelope's state: how many attempts it has cost, and WHEN it first failed.
     * The timestamp is what makes both eviction and the hard release deterministic.
     */
    data class Entry(val attempts: Int, val firstFailedAt: Long)

    /** Persistence seam: a msgId → [Entry] map. */
    interface Store {
        fun read(): Map<String, Entry>
        fun write(entries: Map<String, Entry>)
    }

    /**
     * Persistence seam for the one number that carries the guarantee: epoch millis
     * since which the ack cursor has NOT advanced, or 0 when it has never been set.
     * Deliberately separate from [Store] so it can never be caught by an eviction.
     */
    interface Stall {
        fun since(): Long
        fun set(millis: Long)
    }

    /**
     * Call this whenever the ack cursor ADVANCES: the mailbox is draining, so the
     * stall clock restarts. iOS does it in the `cursor` setter.
     */
    fun noteCursorMoved() {
        stall.set(clock())
    }

    /**
     * Has the cursor been stuck too long for us to hold anything back? Primes the
     * clock on first call, so a fresh install does not believe it has been stalled
     * since 1970.
     */
    private fun cursorStalledTooLong(now: Long): Boolean {
        val since = stall.since()
        if (since <= 0L) { stall.set(now); return false }
        return now - since > MAX_ACK_HOLD_MS
    }

    /** True while this envelope still deserves another attempt. */
    fun shouldRetry(msgId: String): Boolean {
        val now = clock()
        val r = LinkedHashMap(store.read())
        val previous = r[msgId]
        val firstFailedAt = previous?.firstFailedAt ?: now

        val n = (previous?.attempts ?: 0) + 1
        r[msgId] = Entry(n, firstFailedAt)

        // (1) Bound the map — this is SharedPreferences, not a database. Keep the
        // CAP most RECENT first-failures, and never evict the entry this call just
        // touched, or a backlog larger than the cap would refund its budget.
        if (r.size > CAP) {
            val keep = r.entries
                .sortedByDescending { it.value.firstFailedAt }
                .take(CAP)
                .associate { it.key to it.value }
            r.clear()
            r.putAll(keep)
            r[msgId] = Entry(n, firstFailedAt)
        }
        store.write(r)

        // The guarantee, checked on the CURSOR and not on the envelope.
        if (cursorStalledTooLong(now)) return false
        return n <= MAX_RETRIES
    }

    /** Called once an envelope finally decodes, so a recovered id starts clean. */
    fun clear(msgId: String) {
        val r = store.read()
        if (!r.containsKey(msgId)) return
        store.write(LinkedHashMap(r).apply { remove(msgId) })
    }

    companion object {
        /** iOS `V2SyncState.maxRetries`. */
        const val MAX_RETRIES = 8

        /** iOS `V2SyncState.seenCap`. */
        const val CAP = 500

        /** iOS `V2SyncState.maxAckHoldSeconds` (20 min), in milliseconds. */
        const val MAX_ACK_HOLD_MS = 20L * 60L * 1000L

        /** SharedPreferences key for [Stall] — iOS `oshi_v2_cursor_stalled_since`. */
        const val STALL_KEY = "v2_cursor_stalled_since"
    }
}
