package com.oshi.messenger

import com.oshi.messenger.network.auth.ServerClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CALL_CLOCK_SKEW_2026_11_21__ — production case: an Android 1.6.99 phone whose every
 * signed request was "expired" polled three offers and rang for none of them.
 */
class ServerClockTest {

    @After fun reset() = ServerClock.resetForTest()

    /** The exact rule in EnhancedCallManager.handleCallSignal. */
    private fun droppedAsStale(signalTs: Long, localNow: Long) =
        signalTs > 0 && localNow - signalTs > 60_000

    @Test fun `a phone 90 s fast used to drop a polled offer that is 2 s old`() {
        val server = 1_800_000_000_000L
        val local = server + 90_000           // phone clock 90 s ahead
        val offerStampedByServer = server - 2_000
        // Before: raw server timestamp vs local clock => "92 s old" => dropped.
        assertTrue(droppedAsStale(offerStampedByServer, local))
        // After: the poll response's Date taught us the offset.
        ServerClock.observe(serverDateMs = server - 500, sentAtMs = local - 50, receivedAtMs = local + 50)
        val rebased = ServerClock.serverToLocal(offerStampedByServer)
        assertFalse(droppedAsStale(rebased, local))
        assertEquals(local - 2_000, rebased)
    }

    @Test fun `a genuinely old signal stays stale after rebasing`() {
        val server = 1_800_000_000_000L
        val local = server + 90_000
        ServerClock.observe(server - 500, local - 50, local + 50)
        assertTrue(droppedAsStale(ServerClock.serverToLocal(server - 61_500), local))
    }

    @Test fun `no sample means no change`() {
        assertEquals(123_456L, ServerClock.serverToLocal(123_456L))
        assertEquals(1_000L, ServerClock.signingNowMs(1_000L))
    }

    @Test fun `zero or missing timestamps are left alone`() {
        ServerClock.observe(10_000_000L, 1_000L, 1_100L)
        assertEquals(0L, ServerClock.serverToLocal(0L))
    }

    @Test fun `signing is untouched for a healthy clock and corrected for a skewed one`() {
        assertEquals(0L, ServerClock.effectiveOffset(4_999L))
        assertEquals(0L, ServerClock.effectiveOffset(-4_999L))
        assertEquals(5_000L, ServerClock.effectiveOffset(5_000L))
        assertEquals(-90_000L, ServerClock.effectiveOffset(-90_000L))
        val server = 1_800_000_000_000L
        val local = server + 90_000
        ServerClock.observe(server - 500, local - 50, local + 50)
        // within the server's +-30 s window again
        assertTrue(kotlin.math.abs(ServerClock.signingNowMs(local) - server) < 1_000)
    }

    @Test fun `samples from slow or inverted round trips are discarded`() {
        assertNull(ServerClock.sampleOffset(5_000_000L, 1_000L, 1_000L + ServerClock.MAX_SAMPLE_RTT_MS + 1))
        assertNull(ServerClock.sampleOffset(5_000_000L, 2_000L, 1_000L))
        assertNull(ServerClock.sampleOffset(0L, 1_000L, 1_100L))
    }

    @Test fun `Date truncation is centred`() {
        // server true time 10_000_700 at the local midpoint 10_000_700 -> Date says 10_000_000
        assertEquals(-200L, ServerClock.sampleOffset(10_000_000L, 10_000_600L, 10_000_800L))
    }
}
