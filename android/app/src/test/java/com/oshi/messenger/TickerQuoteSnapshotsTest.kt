package com.oshi.messenger

import com.oshi.messenger.util.TickerQuote
import com.oshi.messenger.util.TickerQuoteSnapshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * iOS parity: `TickerQuoteSnapshots.swift` (`__TICKER_FROZEN_2026_09_13__`). A
 * message's `@TICKER` price is captured once and never moves again.
 */
class TickerQuoteSnapshotsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun quote(price: Double) =
        TickerQuote("AAPL", price, 100.0, "USD", listOf(99.0, 100.0, price))

    private fun store(file: File = tmp.root.resolve("s.json"), clock: () -> Long = { 1L }) =
        TickerQuoteSnapshots(file, scope = null, now = clock)

    @Test fun `a captured price is returned for that message`() {
        val s = store()
        s.capture("m1", "AAPL", quote(150.0))
        assertEquals(150.0, s.snapshot("m1", "AAPL")!!.quote.price, 0.0)
    }

    @Test fun `the first capture wins - a later price never replaces it`() {
        val s = store()
        s.capture("m1", "AAPL", quote(150.0))
        s.capture("m1", "AAPL", quote(999.0))
        assertEquals(150.0, s.snapshot("m1", "AAPL")!!.price, 0.0)
    }

    @Test fun `snapshots are per message - another message gets its own price`() {
        val s = store()
        s.capture("m1", "AAPL", quote(150.0))
        assertNull(s.snapshot("m2", "AAPL"))
        s.capture("m2", "AAPL", quote(160.0))
        assertEquals(160.0, s.snapshot("m2", "AAPL")!!.price, 0.0)
        assertEquals(150.0, s.snapshot("m1", "AAPL")!!.price, 0.0)
    }

    @Test fun `the symbol key is case-insensitive like iOS`() {
        val s = store()
        s.capture("m1", "aapl", quote(150.0))
        assertNotNull(s.snapshot("m1", "AAPL"))
    }

    @Test fun `snapshots survive a restart`() {
        val f = tmp.root.resolve("persist.json")
        store(f).apply { capture("m1", "AAPL", quote(150.0)); saveNow() }
        val reopened = store(f)
        val snap = reopened.snapshot("m1", "AAPL")!!
        assertEquals(150.0, snap.price, 0.0)
        assertEquals(listOf(99.0, 100.0, 150.0), snap.series)
        assertEquals("USD", snap.currency)
    }

    @Test fun `an unreadable file gives an empty store, never a crash`() {
        val f = tmp.root.resolve("bad.json").apply { writeText("{not json") }
        assertEquals(0, store(f).count)
    }

    @Test fun `the store is bounded and evicts the oldest captures first`() {
        var t = 0L
        val s = store(clock = { t++ })
        repeat(TickerQuoteSnapshots.MAX_ENTRIES + 5) { s.capture("m$it", "AAPL", quote(1.0)) }
        assertEquals(TickerQuoteSnapshots.MAX_ENTRIES, s.count)
        assertNull(s.snapshot("m0", "AAPL"))
        assertNotNull(s.snapshot("m${TickerQuoteSnapshots.MAX_ENTRIES + 4}", "AAPL"))
    }
}
