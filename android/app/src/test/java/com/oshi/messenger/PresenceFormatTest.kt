package com.oshi.messenger

import com.oshi.messenger.data.model.PresenceFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * N16 — the buckets a last-seen falls into, and the two values that must NOT become a date.
 *
 * The thresholds are iOS's; a divergence here shows up as the same contact described
 * differently on two devices, which nobody reports and everybody notices.
 */
class PresenceFormatTest {

    private val now = 1_761_600_000_000L
    private fun at(agoMs: Long) = PresenceFormat.of(now - agoMs, now)

    @Test
    fun `nothing known is unknown, not a very old date`() {
        assertEquals(PresenceFormat.Text.Unknown, PresenceFormat.of(null, now))
    }

    /**
     * `PeerNickname`'s wire note: Android once omitted `lastSeen` while iOS wrote 0, and
     * every reader has had to treat `<= 0` as absent. Rendering 0 would say 1970.
     */
    @Test
    fun `zero and negative are absent, never 1970`() {
        assertEquals(PresenceFormat.Text.Unknown, PresenceFormat.of(0L, now))
        assertEquals(PresenceFormat.Text.Unknown, PresenceFormat.of(-1L, now))
    }

    /** Two clocks disagreeing is not evidence that someone is here right now. */
    @Test
    fun `a time in the future is unknown rather than just now`() {
        assertEquals(PresenceFormat.Text.Unknown, PresenceFormat.of(now + 60_000L, now))
    }

    @Test
    fun `under a minute is now`() {
        assertEquals(PresenceFormat.Text.Now, at(0))
        assertEquals(PresenceFormat.Text.Now, at(59_999))
    }

    @Test
    fun `the minute boundary flips to minutes`() {
        assertEquals(PresenceFormat.Text.MinutesAgo(1), at(60_000))
        assertEquals(PresenceFormat.Text.MinutesAgo(59), at(59 * 60_000L + 59_000))
    }

    @Test
    fun `the hour boundary flips to hours`() {
        assertEquals(PresenceFormat.Text.HoursAgo(1), at(60 * 60_000L))
        assertEquals(PresenceFormat.Text.HoursAgo(23), at(23 * 3_600_000L + 59 * 60_000L))
    }

    /** Past a day iOS stops counting and shows the date; so does this. */
    @Test
    fun `a day or more becomes an absolute instant`() {
        val twoDays = now - 2 * 24 * 3_600_000L
        assertEquals(PresenceFormat.Text.At(twoDays), PresenceFormat.of(twoDays, now))
        assertEquals(PresenceFormat.Text.At(now - 24 * 3_600_000L), at(24 * 3_600_000L))
    }

    /** Minutes and hours truncate, as iOS's Int() cast does — never round up. */
    @Test
    fun `the count truncates rather than rounding`() {
        assertEquals(PresenceFormat.Text.MinutesAgo(2), at(2 * 60_000L + 59_000))
        assertEquals(PresenceFormat.Text.HoursAgo(3), at(3 * 3_600_000L + 59 * 60_000L))
    }
}
