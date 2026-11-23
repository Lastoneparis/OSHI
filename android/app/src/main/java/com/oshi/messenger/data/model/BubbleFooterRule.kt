package com.oshi.messenger.data.model

/**
 * Does this bubble show its footer — the time, the transport badge and the ticks?
 *
 * __BUBBLE_FOOTER_FOLLOWS_THE_RUN_2026_10_28__ (N10, reported by a user: "coller deux
 * messages quand ils sont écrits à la suite comme sur les groupes […] Comme sur iOS")
 *
 * Android already grouped consecutive messages into runs — `ChatThreadItems.build`
 * computes `isTail` and the bubble uses it for the tailed corner. What it never did was
 * tell the FOOTER, so every bubble in a burst carried its own "20:22 · Via Mesh". iOS folds
 * those into the last bubble of the run, which is the difference he is pointing at.
 *
 * The predicate is iOS's, from `ChatView.swift:5315`, including every exemption — this is a
 * port, not a second design:
 *
 *  • **failed** keeps its footer wherever it sits, because the footer carries the
 *    tap-to-retry control. Folding it away mid-run would leave a message that cannot be
 *    sent and cannot be retried — a way to LOSE a message, not a tidier row.
 *  • **pending** keeps it: it is the one status a reader needs while it is still true, and
 *    by the time it settles the row folds itself.
 *  • **edited** keeps it: a message must go on saying so wherever it sits.
 *
 * What is hidden is only the timestamp of an interior message, and on both platforms that
 * stays reachable — iOS by a swipe-left reveal, here by a long-press.
 */
object BubbleFooterRule {

    /** Two bubbles belong to one run: same sender, and close in time. */
    const val RUN_GAP_MS = 120_000L

    /**
     * iOS `ChatView.messagesAreGrouped`. Null previous means this starts a run.
     *
     * The gap is compared on ABSOLUTE difference rather than `next - prev`, because a
     * thread can hold two messages whose stored timestamps disagree about order — the
     * clock note in `a-clock-cannot-order-a-thread` is about exactly that — and a negative
     * difference must not read as "0 ms apart, definitely grouped".
     */
    fun grouped(
        previousSenderKey: String?,
        previousTimestampMs: Long?,
        senderKey: String,
        timestampMs: Long,
    ): Boolean {
        if (previousSenderKey == null || previousTimestampMs == null) return false
        if (previousSenderKey != senderKey) return false
        return kotlin.math.abs(timestampMs - previousTimestampMs) <= RUN_GAP_MS
    }

    /**
     * Is this the LAST bubble of its run, and therefore the one wearing the tail?
     *
     * @param isFiltering a search or date filter is active. Then every message is its own
     *   tail — iOS's rule and its reason: the list is filtered, so two hits from one sender
     *   ninety seconds apart but a thousand messages apart in the real thread become
     *   ADJACENT. Grouping them would take the timestamp off the earlier one, and in search
     *   results the timestamp is how you locate a hit. Runs are a property of the
     *   CONVERSATION, and a filtered list is not one.
     */
    fun isTail(
        nextSenderKey: String?,
        nextTimestampMs: Long?,
        senderKey: String,
        timestampMs: Long,
        isFiltering: Boolean,
    ): Boolean {
        if (isFiltering) return true
        if (nextSenderKey == null || nextTimestampMs == null) return true
        return !grouped(senderKey, timestampMs, nextSenderKey, nextTimestampMs)
    }

    /**
     * iOS `showsFooter` (`ChatView.swift:5315`), exemption for exemption.
     */
    fun showsFooter(isTail: Boolean, isFailed: Boolean, isPending: Boolean, isEdited: Boolean): Boolean =
        isTail || isFailed || isPending || isEdited
}
