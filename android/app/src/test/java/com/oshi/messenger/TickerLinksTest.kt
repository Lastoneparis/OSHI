package com.oshi.messenger

import com.oshi.messenger.util.TickerLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 📈 `@AAPL` → a tappable `$AAPL` pointing at TradingView.
 *
 * Port contract against `OSHI/TickerLinks.swift`. The dangerous half is NOT the
 * matching, it is the NOT-matching: this feature rewrites text a person typed,
 * so every false positive turns their word into a link to a stock chart. iOS
 * states the rule as "somebody called Max turned into a link to a stock chart is
 * a far worse failure than a ticker rendered as plain text", and most of the
 * cases below exist to pin that.
 */
class TickerLinksTest {

    private fun symbols(s: String) = TickerLinks.matches(s).map { it.symbol }

    // ---------------------------------------------------------------- matching

    @Test
    fun `a bare uppercase ticker is found`() {
        assertEquals(listOf("AAPL"), symbols("look at @AAPL today"))
    }

    @Test
    fun `it is shown with a dollar sigil, never the at sign`() {
        val m = TickerLinks.matches("@AAPL").single()
        assertEquals("\$AAPL", m.display)
    }

    @Test
    fun `an exchange prefix is captured and kept out of the display`() {
        val m = TickerLinks.matches("@NASDAQ:TSLA").single()
        assertEquals("TSLA", m.symbol)
        assertEquals("NASDAQ", m.exchange)
        assertEquals("\$TSLA", m.display)
    }

    /** The suffixed alternation must come first, or `@BRK.B` stops at `BRK`. */
    @Test
    fun `a suffixed symbol is not truncated at the dot`() {
        assertEquals(listOf("BRK.B"), symbols("@BRK.B"))
    }

    @Test
    fun `international and crypto forms are found`() {
        assertEquals(listOf("7203.T"), symbols("@7203.T"))
        assertEquals(listOf("0700.HK"), symbols("@0700.HK"))
        assertEquals(listOf("BTC-USD"), symbols("@BTC-USD"))
        assertEquals(listOf("GAZP.ME"), symbols("@GAZP.ME"))
    }

    @Test
    fun `several tickers come back in order of appearance`() {
        assertEquals(listOf("AAPL", "TSLA"), symbols("@AAPL beats @TSLA"))
    }

    // ------------------------------------------------------------ NOT matching

    @Test
    fun `an email local part is left alone`() {
        assertTrue(TickerLinks.matches("hugo@ACME").isEmpty())
    }

    /** Caught on iOS by a test, not by reading: the first pattern linked `@AB`. */
    @Test
    fun `a handle inside a URL path is left alone`() {
        assertTrue(TickerLinks.matches("x.com/i/@AB/status").isEmpty())
    }

    @Test
    fun `a repeated sigil is left alone`() {
        assertTrue(TickerLinks.matches("@@AAPL").isEmpty())
    }

    @Test
    fun `digits alone name nothing`() {
        assertTrue(TickerLinks.matches("@123").isEmpty())
        assertTrue(TickerLinks.matches("@A1B2").isEmpty())
    }

    @Test
    fun `a long shout is not a symbol`() {
        assertTrue(
            "no listed equity exceeds 5 letters without a suffix",
            TickerLinks.matches("@INCROYABLE").isEmpty()
        )
        assertTrue(TickerLinks.matches("@ABCDEFG").isEmpty())
    }

    @Test
    fun `a lowercase first name never becomes a stock`() {
        for (name in listOf("@marie", "@hugo", "@max", "@here", "@all", "@everyone")) {
            assertTrue("$name must stay plain text", TickerLinks.matches(name).isEmpty())
        }
    }

    @Test
    fun `text with no sigil at all yields nothing`() {
        assertTrue(TickerLinks.matches("OK ASAP USA AI PDF TV OSHI").isEmpty())
        assertFalse(TickerLinks.hasTicker("OK ASAP USA"))
    }

    // ------------------------------------------------- the lowercase whitelist

    @Test
    fun `a whitelisted lowercase commodity is accepted and upper-cased`() {
        assertEquals(listOf("GOLD"), symbols("price of @gold"))
        assertEquals("\$GOLD", TickerLinks.matches("@gold").single().display)
    }

    @Test
    fun `a whitelisted lowercase stock is accepted`() {
        assertEquals(listOf("AAPL"), symbols("@aapl"))
    }

    @Test
    fun `a lowercase word outside both tables is refused`() {
        assertTrue(TickerLinks.matches("@coin").isEmpty())
        assertTrue(TickerLinks.matches("@silverware").isEmpty())
    }

    @Test
    fun `hasTicker agrees with matches, lowercase aliases included`() {
        for (s in listOf("@AAPL", "@gold", "@aapl", "@BTC-USD", "plain text", "@marie")) {
            assertEquals(
                "hasTicker must not disagree with matches for '$s'",
                TickerLinks.matches(s).isNotEmpty(), TickerLinks.hasTicker(s)
            )
        }
    }

    // -------------------------------------------------------------------- URL

    @Test
    fun `the URL is the PUBLIC chart route, not a private layout id`() {
        val url = TickerLinks.matches("@AAPL").single().url
        assertTrue(url.startsWith("https://www.tradingview.com/chart/?symbol="))
        assertFalse("a private layout id 403s for every recipient", url.contains("dMgrF92p"))
    }

    /**
     * iOS percent-encodes with `.alphanumerics` ONLY, so `:`, `.` and `-` are all
     * escaped. `URLEncoder` disagrees on all three, which would send Android to a
     * different URL from the iPhone for every qualified or suffixed symbol.
     */
    @Test
    fun `the exchange separator is percent-encoded exactly as iOS does`() {
        assertEquals(
            "https://www.tradingview.com/chart/?symbol=NASDAQ%3ATSLA",
            TickerLinks.matches("@NASDAQ:TSLA").single().url
        )
    }

    @Test
    fun `dots and dashes are percent-encoded too`() {
        assertEquals(
            "https://www.tradingview.com/chart/?symbol=BRK%2EB",
            TickerLinks.matches("@BRK.B").single().url
        )
        assertEquals(
            "https://www.tradingview.com/chart/?symbol=BTC%2DUSD",
            TickerLinks.matches("@BTC-USD").single().url
        )
    }

    // ------------------------------------------------------- network symbol

    @Test
    fun `a commodity resolves to its futures contract, not the forex form`() {
        assertEquals("GC=F", TickerLinks.resolvedSymbol("gold"))
        assertEquals("SI=F", TickerLinks.resolvedSymbol("XAG"))
        assertEquals("CL=F", TickerLinks.resolvedSymbol("oil"))
    }

    @Test
    fun `an explicit pair is passed through untouched`() {
        assertEquals("BTC-USD", TickerLinks.resolvedSymbol("BTC-USD"))
    }

    @Test
    fun `a plain equity goes to the network unchanged`() {
        assertEquals("AAPL", TickerLinks.resolvedSymbol("AAPL"))
    }

    // ------------------------------------------------------------- positions

    @Test
    fun `the range covers the sigil so the caller can replace the whole token`() {
        val text = "buy @AAPL now"
        val m = TickerLinks.matches(text).single()
        assertEquals("@AAPL", text.substring(m.start, m.end))
    }
}
