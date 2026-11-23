package com.oshi.messenger

import com.oshi.messenger.util.TickerQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __QUOTES_MOVE_SERVER_SIDE_2026_11_02__ `/api/quote`, the body the OSHI
 * server returns now that the phone no longer calls Yahoo itself.
 *
 * Port contract against the four `parseOshi` cases in
 * `OSHITests/TickerLinkTests.swift`. Same deliberate divergence as the Yahoo
 * parser beside this one: iOS throws on a body it cannot read, Kotlin returns
 * null. Both exist so that an unreadable body never becomes a quote of 0.00
 * rendered as a real price next to someone's sentence.
 *
 * The bodies below are REAL responses, copied from the live endpoint on the
 * day it shipped, not invented shapes.
 */
class TickerQuoteOshiEndpointTest {

    /**
     * __CRYPTO_SPARKLINE_2026_11_02__ Crypto now carries 24 hourly closes, so a
     * `@BTC` chip draws the same curve an equity does. Captured from the live
     * endpoint the day it shipped.
     */
    @Test
    fun `crypto body with the hourly series`() {
        val body = """{"symbol":"BTC","pair":"BTC/USD","price":76809.52,"previousClose":77916.78,""" +
            """"series":[77885.54,78262.72,78092.15,77994.1,77800.0,77650.3,77500.2,77410.9,""" +
            """77350.4,77290.1,77210.8,77150.6,77090.3,77020.7,76980.2,76940.5,76900.1,""" +
            """76870.4,76850.9,76830.2,76815.6,76805.3,76800.1,76809.52],""" +
            """"currency":"USD","quoteAsset":"USD","source":"binance","kind":"crypto"}"""
        val q = TickerQuote.parseOshi(body, "BTC")
        assertNotNull(q)
        assertEquals(76809.52, q!!.price, 0.001)
        assertEquals(24, q.series.size)
        // The clients hide a curve below this; 24 must clear it or the sparkline
        // ships and still never draws.
        assertTrue(q.series.size >= TickerQuote.MIN_SPARKLINE_POINTS)
        assertEquals("USD", q.currency)
        assertFalse(q.isUp)
    }

    /**
     * An exchange with no OHLCV still prices the asset: the series is fetched in
     * its own try/catch server-side, because a sparkline that failed must not
     * turn a good quote into no quote.
     */
    @Test
    fun `crypto body with no series still prices itself`() {
        val body = """{"symbol":"BTC","pair":"BTC/USDT","price":77749.77,""" +
            """"previousClose":77935.91,"currency":"USD","source":"binance","kind":"crypto"}"""
        val q = TickerQuote.parseOshi(body, "BTC")
        assertNotNull(q)
        assertEquals("BTC", q!!.symbol)
        assertEquals(77749.77, q.price, 0.001)
        assertEquals(77935.91, q.previousClose, 0.001)
        assertEquals("USD", q.currency)
        assertTrue(q.series.isEmpty())
        assertFalse(q.isUp)   // 24h open above last
    }

    /** An equity does carry a series, from the server-side Yahoo call. */
    @Test
    fun `equity body with series`() {
        val body = """{"symbol":"AAPL","price":344.63,"previousClose":348.91,""" +
            """"currency":"USD","series":[348.01,347.89,344.63],"kind":"equity"}"""
        val q = TickerQuote.parseOshi(body, "AAPL")
        assertNotNull(q)
        assertEquals(344.63, q!!.price, 0.001)
        assertEquals(3, q.series.size)
        assertFalse(q.isUp)
    }

    /**
     * A EUR pair must keep EUR. Rendering a euro price with a dollar sign is
     * the exact bug the old crypto alias table was written to avoid, and it
     * would come straight back if the currency were assumed.
     */
    @Test
    fun `non-USD pair keeps its currency`() {
        val body = """{"symbol":"ETH-EUR","pair":"ETH/EUR","price":2034.26,""" +
            """"previousClose":2016.6,"currency":"EUR","kind":"crypto"}"""
        val q = TickerQuote.parseOshi(body, "ETH-EUR")
        assertNotNull(q)
        assertEquals("EUR", q!!.currency)
        assertTrue(q.isUp)
    }

    /** The endpoint's own error shape must not become a zero-priced chip. */
    @Test
    fun `error body is null, not a zero price`() {
        val body = """{"error":"unknown-symbol","symbol":"NOTACOIN"}"""
        assertNull(TickerQuote.parseOshi(body, "NOTACOIN"))
    }

    /** Garbage in, null out — never an exception into the message list. */
    @Test
    fun `unreadable body is null`() {
        assertNull(TickerQuote.parseOshi("not json at all", "BTC"))
        assertNull(TickerQuote.parseOshi("", "BTC"))
    }
}
