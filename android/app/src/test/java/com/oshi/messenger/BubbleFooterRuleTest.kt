package com.oshi.messenger

import com.oshi.messenger.data.model.BubbleFooterRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N10 — folding a burst of messages into one run, with iOS's exemptions intact.
 *
 * Each exemption below describes something a reader LOSES if the footer folds, which is why
 * they are tested one by one rather than as "some statuses keep it".
 */
class BubbleFooterRuleTest {

    private val t0 = 1_761_600_000_000L
    private val me = "AAAA"
    private val them = "BBBB"

    // ---------------------------------------------------------------- runs

    @Test
    fun `the first message of a thread starts a run`() {
        assertFalse(BubbleFooterRule.grouped(null, null, me, t0))
    }

    @Test
    fun `two messages from one sender close together are one run`() {
        assertTrue(BubbleFooterRule.grouped(me, t0, me, t0 + 60_000))
        assertTrue("the boundary itself groups", BubbleFooterRule.grouped(me, t0, me, t0 + 120_000))
    }

    @Test
    fun `past the gap they are two runs`() {
        assertFalse(BubbleFooterRule.grouped(me, t0, me, t0 + 120_001))
    }

    @Test
    fun `a different sender always breaks the run`() {
        assertFalse(BubbleFooterRule.grouped(them, t0, me, t0 + 1_000))
    }

    /**
     * A thread can hold two messages whose stored timestamps disagree about order. A plain
     * subtraction would go NEGATIVE and read as "0 ms apart, definitely grouped".
     */
    @Test
    fun `an out-of-order pair is measured on the absolute gap`() {
        assertTrue(BubbleFooterRule.grouped(me, t0 + 60_000, me, t0))
        assertFalse(BubbleFooterRule.grouped(me, t0 + 200_000, me, t0))
    }

    // ---------------------------------------------------------------- tails

    @Test
    fun `the last message in the list is always a tail`() {
        assertTrue(BubbleFooterRule.isTail(null, null, me, t0, isFiltering = false))
    }

    @Test
    fun `a message followed by one of its own run is not a tail`() {
        assertFalse(BubbleFooterRule.isTail(me, t0 + 30_000, me, t0, isFiltering = false))
    }

    @Test
    fun `a message followed by the other person is a tail`() {
        assertTrue(BubbleFooterRule.isTail(them, t0 + 30_000, me, t0, isFiltering = false))
    }

    /**
     * In a filtered list two hits can be adjacent on screen and a thousand messages apart
     * in the thread. Grouping them would take the timestamp off the earlier one — and in
     * search results the timestamp is how you find the hit.
     */
    @Test
    fun `while filtering every message is its own tail`() {
        assertTrue(BubbleFooterRule.isTail(me, t0 + 1_000, me, t0, isFiltering = true))
    }

    // ---------------------------------------------------------------- the exemptions

    @Test
    fun `an interior bubble normally folds its footer away`() {
        assertFalse(BubbleFooterRule.showsFooter(isTail = false, isFailed = false, isPending = false, isEdited = false))
    }

    @Test
    fun `the tail of a run always shows it`() {
        assertTrue(BubbleFooterRule.showsFooter(isTail = true, isFailed = false, isPending = false, isEdited = false))
    }

    /** The footer carries tap-to-retry. Folding it hides the only way to resend. */
    @Test
    fun `a failed message keeps its footer wherever it sits`() {
        assertTrue(BubbleFooterRule.showsFooter(isTail = false, isFailed = true, isPending = false, isEdited = false))
    }

    /** The one status a reader needs while it is still true. */
    @Test
    fun `a pending message keeps its footer`() {
        assertTrue(BubbleFooterRule.showsFooter(isTail = false, isFailed = false, isPending = true, isEdited = false))
    }

    /** A message must go on saying it was edited wherever it sits. */
    @Test
    fun `an edited message keeps its footer`() {
        assertTrue(BubbleFooterRule.showsFooter(isTail = false, isFailed = false, isPending = false, isEdited = true))
    }
}
