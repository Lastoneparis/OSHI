package com.oshi.messenger.util

import org.json.JSONObject
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

/**
 * 📈 A quote for a `@TICKER`, and how to turn Yahoo's answer into one.
 *
 * Port of `OSHI/TickerQuoteFetcher.swift`. The URL, the User-Agent and the field
 * precedence are the same, so both platforms show the same number for the same
 * symbol at the same moment.
 *
 * The parse is split out from the network exactly as iOS does it, so it can be
 * tested against a captured payload without touching Yahoo.
 */
data class TickerQuote(
    val symbol: String,
    val price: Double,
    val previousClose: Double,
    val currency: String,
    /** Intraday closes, oldest first — enough to draw a sparkline. */
    val series: List<Double>
) {
    val change: Double get() = price - previousClose
    val changePercent: Double get() =
        if (previousClose == 0.0) 0.0 else change / previousClose * 100.0
    val isUp: Boolean get() = change >= 0

    /**
     * The price with its CURRENCY, in the user's locale.
     *
     * ⚠️ `GBp`/`GBX` is the trap. Yahoo quotes Shell as `3386.5 GBp` — that is
     * PENCE, i.e. £33.86. Rendering "£3,386.50" would be wrong by a factor of 100,
     * so pence are converted to pounds first (`TickerChipView.swift` documents the
     * same conversion and names the same symbol).
     *
     * Decimal places belong to the CURRENCY, not to a constant: JPY, KRW and CLP
     * have none, USD/EUR/CHF have two. Hard-coding 2 would print "¥1,234.00" for a
     * yen price. Sub-unit prices (most crypto) get more digits, because two would
     * round a real price to 0.00.
     *
     * ⚠️ `setCurrency` DOES NOT carry the digit count on the JVM. `java.text`
     * documents it explicitly: setting the currency "does not update the minimum or
     * maximum number of fraction digits". Android's DecimalFormat is ICU-backed and
     * *does* update them, so relying on it worked on a handset and printed
     * "¥1,234.00" under a JVM unit test — a divergence that made the behaviour
     * untestable off-device. The digits are now set from the currency by hand, which
     * is what both runtimes then agree on.
     */
    fun formattedPrice(locale: Locale = Locale.getDefault()): String {
        var value = price
        var code = currency.uppercase(Locale.US)
        if (currency == "GBp" || code == "GBX") {
            value /= 100.0
            code = "GBP"
        }
        val f = NumberFormat.getCurrencyInstance(locale)
        runCatching {
            val c = Currency.getInstance(code)
            f.currency = c
            // -1 means "no minor unit defined" (e.g. XAU): leave the locale default.
            if (c.defaultFractionDigits >= 0) {
                f.maximumFractionDigits = c.defaultFractionDigits
                f.minimumFractionDigits = c.defaultFractionDigits
            }
        }
        if (abs(value) < 1.0 && value != 0.0) {
            // A sub-unit price needs more than the currency's own convention, or
            // it rounds to zero.
            f.maximumFractionDigits = 6
            f.minimumFractionDigits = 2
        }
        return runCatching { f.format(value) }.getOrElse {
            "%.2f %s".format(locale, value, code)
        }
    }

    /** `▲1.24%` / `▼0.80%`, matching iOS's `"%@%.2f%%"`. */
    fun formattedChange(locale: Locale = Locale.getDefault()): String =
        "%s%.2f%%".format(locale, if (isUp) "▲" else "▼", abs(changePercent))

    companion object {
        /** iOS draws a sparkline only above this; two points is a line implying a trend nobody measured. */
        const val MIN_SPARKLINE_POINTS = 8

        /**
         * Yahoo's chart endpoint for [symbol]. Percent-encoding allows alphanumerics
         * plus `-` and `.`, matching iOS — `BRK.B` and `BTC-USD` must survive intact
         * or the request 404s.
         */
        fun url(symbol: String): String {
            val resolved = TickerLinks.resolvedSymbol(symbol)
            val sb = StringBuilder()
            for (b in resolved.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt().toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.') sb.append(c)
                else sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
            }
            // __QUOTES_MOVE_SERVER_SIDE_2026_11_02__ The phone asks OSHI, not
            // Yahoo. Fetching a ticker straight from the device told a third
            // party which symbol someone was discussing and when — the same
            // leak the GIPHY proxy already closes for stickers. Crypto now
            // resolves through ccxt against real exchanges, so `@PEPE` and
            // `@ARB` answer where the old hardcoded twelve-symbol table left
            // them as unknown Yahoo symbols; equities still come from Yahoo,
            // but server-side.
            return "https://oshi-messenger.com/api/quote?symbol=$sb"
        }

        /**
         * Parse `/api/quote`. Flat, because the server has already done the
         * unwrapping [parse] does for Yahoo's nested chart payload.
         *
         * Null rather than throwing, for the same reason as [parse]: a chip
         * that cannot price itself shows nothing, it does not take the thread
         * down. [parse] is kept and still tested — the server speaks Yahoo's
         * dialect for equities.
         */
        fun parseOshi(body: String, symbol: String): TickerQuote? = runCatching {
            val root = JSONObject(body)
            val price = root.optDoubleOrNull("price") ?: return@runCatching null
            val prev = root.optDoubleOrNull("previousClose") ?: price
            val series = mutableListOf<Double>()
            root.optJSONArray("series")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.optDouble(i)
                    if (!v.isNaN()) series.add(v)
                }
            }
            TickerQuote(
                symbol = root.optString("symbol", symbol),
                price = price,
                previousClose = prev,
                currency = root.optString("currency", "USD"),
                series = series
            )
        }.getOrNull()

        /**
         * Parse Yahoo's payload. Returns null rather than throwing: a chip that
         * cannot price itself shows nothing, it does not take the thread down.
         */
        fun parse(body: String, symbol: String): TickerQuote? = runCatching {
            val root = JSONObject(body)
            val first = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val meta = first.getJSONObject("meta")

            val price = meta.optDoubleOrNull("regularMarketPrice")
                ?: meta.optDoubleOrNull("previousClose")
                ?: return null

            // `chartPreviousClose` matches the REQUESTED range; `previousClose` is the
            // daily figure. Prefer the former, as iOS does, or the percentage moves
            // against a baseline the series never had.
            val prev = meta.optDoubleOrNull("chartPreviousClose")
                ?: meta.optDoubleOrNull("previousClose")
                ?: price

            val series = mutableListOf<Double>()
            first.optJSONObject("indicators")
                ?.optJSONArray("quote")?.optJSONObject(0)
                ?.optJSONArray("close")?.let { closes ->
                    for (i in 0 until closes.length()) {
                        // Yahoo emits null for gaps. Dropping them is right for a
                        // sparkline; interpolating would draw a price never traded.
                        if (!closes.isNull(i)) series += closes.optDouble(i)
                    }
                }

            TickerQuote(
                symbol = meta.optString("symbol", symbol),
                price = price,
                previousClose = prev,
                currency = meta.optString("currency", "USD"),
                series = series
            )
        }.getOrNull()

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
    }
}
