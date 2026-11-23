package com.oshi.messenger

import com.oshi.messenger.ui.viewmodel.LoRaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __LORA_NODE_LIST_2026_11_04__ "Be sure we can see different nodes."
 *
 * There was no node list in the app at all — `nodes.size` as a number on the
 * setup screen, and pins on the map. So the radios around you were a count and a
 * picture, never a list you could read: no signal, no last-heard, no hops, no
 * distance. This formatter is the part of that list worth testing, because the
 * rest is layout.
 *
 * Buckets, not exact figures, and the tests say why: the gap between "30 s ago"
 * and "50 s ago" carries nothing a person can act on, while the gap between
 * "now" and "yesterday" is the difference between a radio that is there and one
 * that is gone.
 */
class LoRaLastHeardTest {

    private val now = 1_762_000_000_000L   // a fixed "now"; nothing here reads the clock

    private fun bucket(ageMs: Long) = LoRaFormat.lastHeardBucket(now - ageMs, now)

    @Test
    fun `a node heard seconds ago is simply here`() {
        assertEquals(LoRaFormat.LastHeard.Now, bucket(0))
        assertEquals(LoRaFormat.LastHeard.Now, bucket(30_000))
        assertEquals(LoRaFormat.LastHeard.Now, bucket(89_000))
    }

    @Test
    fun `minutes, hours and days each get their own bucket`() {
        assertEquals(LoRaFormat.LastHeard.Minutes(5), bucket(5 * 60_000L))
        assertEquals(LoRaFormat.LastHeard.Minutes(59), bucket(59 * 60_000L))
        assertEquals(LoRaFormat.LastHeard.Hours(1), bucket(60 * 60_000L))
        assertEquals(LoRaFormat.LastHeard.Hours(23), bucket(23 * 3_600_000L))
        assertEquals(LoRaFormat.LastHeard.Days(1), bucket(24 * 3_600_000L))
        assertEquals(LoRaFormat.LastHeard.Days(3), bucket(3 * 86_400_000L))
    }

    @Test
    fun `a node never heard from has no label at all`() {
        // The one that matters: `lastHeard` is 0 for a node we know about from a
        // NodeInfo but have never had a packet from. Formatting that as a time
        // prints "56 years ago", which is what epoch zero is.
        assertNull(LoRaFormat.lastHeardBucket(0L, now))
        assertNull(LoRaFormat.lastHeardBucket(-1L, now))
    }

    @Test
    fun `a node whose clock runs ahead is here, not in the far past`() {
        // Mesh nodes keep their own time and plenty have no RTC at all, so a
        // `lastHeard` in the future is ordinary. Subtracting gives a negative
        // age, and without a guard that falls through every band to "days ago"
        // for a radio that is sitting on the desk transmitting.
        assertEquals(LoRaFormat.LastHeard.Now, LoRaFormat.lastHeardBucket(now + 60_000L, now))
        assertEquals(LoRaFormat.LastHeard.Now, LoRaFormat.lastHeardBucket(now + 86_400_000L, now))
    }

    @Test
    fun `a day boundary never rounds down to zero days`() {
        // `Days(0)` would render as "0 d ago". Anything past 24 h is at least one.
        val b = LoRaFormat.lastHeardBucket(now - 24 * 3_600_000L - 1, now)
        assertEquals(LoRaFormat.LastHeard.Days(1), b)
    }

    // ---------------------------------------------------------------- presence

    @Test
    fun `presence follows the five minute window`() {
        assertTrue(LoRaFormat.isPresent(now - 60_000L, now))
        assertTrue(LoRaFormat.isPresent(now - (LoRaFormat.PRESENT_WINDOW_MS - 1), now))
        assertFalse(LoRaFormat.isPresent(now - LoRaFormat.PRESENT_WINDOW_MS, now))
        assertFalse(LoRaFormat.isPresent(now - 86_400_000L, now))
    }

    @Test
    fun `a node never heard from is not present`() {
        // Guards the tempting `now - 0 < WINDOW` arithmetic, which is false here
        // only by luck of the epoch being far away — state it so it stays true.
        assertFalse(LoRaFormat.isPresent(0L, now))
    }

    // ------------------------------------------------- the data already existed

    @Test
    fun `signal bars and estimated range were already here`() {
        // Worth pinning: the list needed no new radio data. `signalBars` and
        // `estimatedRangeKm` have existed all along, which is why the missing
        // piece was only ever the screen.
        assertEquals(4, LoRaFormat.signalBars(-55))
        assertEquals(1, LoRaFormat.signalBars(-110))
        val range = LoRaFormat.estimatedRangeKm(-80)
        assertTrue("a measurable RSSI must produce a range", range != null)
        assertTrue("and it must be a range, not a point", range!!.second > range.first)
    }
}
