package com.oshi.messenger

import com.oshi.messenger.util.TickerLinks
import com.oshi.messenger.util.TickerQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __QUOTES_MOVE_SERVER_SIDE_2026_11_02__ The whole `@` path, end to end, as a
 * CONTRACT between the client and `/api/quote`.
 *
 * Each case pairs what the user types with the symbol the client resolves it
 * to, and with a REAL response body captured from the live endpoint for that
 * exact symbol. Both halves matter:
 *
 *  * the resolved symbol is what goes in the URL, and
 *    `__COMMODITY_SYMBOLS_HAVE_AN_EQUALS_2026_11_02__` is here because the
 *    server's symbol filter once rejected `GC=F` and silently killed every
 *    commodity the moment quotes moved server-side;
 *  * the body is what `parseOshi` has to read, and crypto bodies carry no
 *    `series` while equity ones do.
 */
class TickerEndToEndContractTest {

    private fun resolved(typed: String) = TickerLinks.resolvedSymbol(typed)

    @Test
    fun `crypto resolves to a dash-USD symbol the server understands`() {
        assertEquals("BTC-USD", resolved("BTC"))
        assertEquals("ETH-USD", resolved("ETH"))
        assertEquals("XMR-USD", resolved("XMR"))
    }

    /**
     * Commodities resolve to Yahoo FUTURES symbols, which contain `=`. The
     * server must accept that character — it did not for several hours.
     */
    @Test
    fun `commodities resolve to futures symbols containing an equals sign`() {
        assertEquals("GC=F", resolved("GOLD"))
        assertEquals("SI=F", resolved("SILVER"))
        assertEquals("CL=F", resolved("OIL"))
        assertTrue("a futures symbol must contain '='", resolved("GOLD").contains("="))
    }

    /** A coin outside the client's alias table passes through untouched — and
     *  the server prices it anyway, because it reads real exchange markets. */
    @Test
    fun `an unaliased coin passes through unchanged`() {
        assertEquals("PEPE", resolved("PEPE"))
        assertEquals("SHIB", resolved("SHIB"))
    }

    @Test
    fun `a real crypto body parses, with no series`() {
        val body = """{"symbol":"BTC-USD","pair":"BTC/USD","price":77872.61,""" +
            """"previousClose":78009.14,"currency":"USD","quoteAsset":"USD",""" +
            """"source":"binance","kind":"crypto"}"""
        val q = TickerQuote.parseOshi(body, "BTC-USD")
        assertNotNull(q)
        assertEquals(77872.61, q!!.price, 0.001)
        assertEquals("USD", q.currency)
        assertTrue(q.series.isEmpty())
    }

    @Test
    fun `a real commodity body parses as an equity`() {
        val body = """{"symbol":"GC=F","price":3969.9,"previousClose":3950.0,""" +
            """"currency":"USD","series":[3950.0,3960.0,3969.9],"kind":"equity"}"""
        val q = TickerQuote.parseOshi(body, "GC=F")
        assertNotNull(q)
        assertEquals(3969.9, q!!.price, 0.001)
        assertEquals(3, q.series.size)
    }

    /** A non-USD listing must keep its own currency, not be shown in dollars. */
    @Test
    fun `a foreign listing keeps its currency`() {
        val jp = TickerQuote.parseOshi(
            """{"symbol":"7203.T","price":3051,"previousClose":3070,"currency":"JPY","kind":"equity"}""",
            "7203.T")
        assertEquals("JPY", jp!!.currency)
        val fr = TickerQuote.parseOshi(
            """{"symbol":"MC.PA","price":393.6,"previousClose":387.0,"currency":"EUR","kind":"equity"}""",
            "MC.PA")
        assertEquals("EUR", fr!!.currency)
    }
}
