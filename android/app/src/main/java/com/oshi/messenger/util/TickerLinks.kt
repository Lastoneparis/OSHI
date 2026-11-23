package com.oshi.messenger.util

import java.util.Locale

/**
 * 📈 Turn `@AAPL` in a message into a TradingView chart link.
 *
 * Port of `OSHI/TickerLinks.swift`, character for character where it matters:
 * the two regular expressions, the alias tables and the URL are the SAME, so a
 * message rendered on an iPhone and on Android links to the same chart.
 *
 * **THE SIGIL IS THE WHOLE DESIGN** (`TickerLinks.swift:4-11`). Detecting bare
 * uppercase words as tickers is unusable in a messenger: "OK", "ASAP", "USA",
 * "AI", "PDF", "TV", "OSHI" itself — ordinary conversation is full of them, and
 * every false positive turns a word someone typed into a link to a stock chart.
 * Requiring `@` means the sender opts in, once, per ticker.
 *
 * Everything here is pure text → ranges. No network, no state, so it is safe to
 * run on every message as it renders.
 */
object TickerLinks {

    /** A ticker found in a message, and where it sits in the ORIGINAL string. */
    data class Match(
        /** The symbol without the sigil, upper-cased. `AAPL`. */
        val symbol: String,
        /** Exchange prefix if the author gave one (`NASDAQ` in `@NASDAQ:TSLA`). */
        val exchange: String?,
        /** Index of the `@` in the original string. */
        val start: Int,
        /** Index one past the last character of the token. */
        val end: Int
    ) {
        /** What is shown in the bubble: the symbol, no sigil, no exchange. */
        val display: String get() = "$$symbol"

        /**
         * TradingView URL for this match.
         *
         * TradingView needs an exchange to resolve a symbol. When the author did
         * not give one the prefix is omitted entirely rather than guessed:
         * TradingView then resolves the symbol itself, which is right more often
         * than defaulting every ticker to NASDAQ and sending someone to a chart
         * for a different company on a different exchange.
         *
         * ⚠️ NOT `/chart/dMgrF92p/` — that layout id is PRIVATE and returns HTTP
         * 403 "Chart Not Found" for anyone who is not its owner
         * (`TickerLinks.swift:41-45`, verified there by request).
         */
        val url: String
            get() {
                val raw = exchange?.let { "$it:$symbol" } ?: symbol
                return "https://www.tradingview.com/chart/?symbol=" + percentEncodeAlphanumeric(raw)
            }
    }

    /**
     * iOS builds this with `addingPercentEncoding(withAllowedCharacters: .alphanumerics)`,
     * so `:`, `.` and `-` are ALL encoded. `java.net.URLEncoder` does not agree —
     * it leaves `-`, `_`, `.` and `*` alone and turns a space into `+` — so
     * using it would produce a different URL from the iPhone for every
     * exchange-qualified or suffixed symbol.
     */
    private fun percentEncodeAlphanumeric(s: String): String {
        val out = StringBuilder(s.length + 8)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9') {
                out.append(c)
            } else {
                out.append('%').append(String.format("%02X", b.toInt() and 0xFF))
            }
        }
        return out.toString()
    }

    /**
     * Two forms, and only one admits digits:
     *  - BARE     `@AAPL`, `@BRK` — 1-6 LETTERS, nothing else;
     *  - SUFFIXED `@BRK.B`, `@7203.T`, `@0700.HK`, `@BTC-USD` — 1-8 letters or
     *    digits, then `.` or `-`, then 1-3 letters of venue or pair.
     * Both accept an optional venue prefix (`@NASDAQ:TSLA`).
     *
     * The order of the alternation matters: the suffixed form FIRST, otherwise
     * `@BRK.B` would stop at `BRK` (`TickerLinks.swift:92-94`).
     *
     * The negative look-behind carries the whole safety of this feature:
     * `[A-Za-z0-9_/.@]` before the sigil means an email local part
     * (`hugo@ACME`), a URL path segment (`x.com/i/@AB/status`) and a repeated
     * sigil are all left alone. The `/` in the look-AHEAD matters for the same
     * reason — a ticker is never followed by a path separator.
     */
    private val PATTERN = Regex(
        "(?<![A-Za-z0-9_/.@])@(?:([A-Z]{2,8}):)?([A-Z0-9]{1,8}[.-][A-Z]{1,3}|[A-Z]{1,6})(?![A-Za-z0-9_/])"
    )

    /**
     * `@gold` used to do NOTHING.
     *
     * [PATTERN] accepts only CAPITALS, and that is deliberate: case is what
     * separates a ticker from a mention of a person (`@hugo`). Making the
     * pattern case-insensitive would turn every first name into a link to a
     * stock chart — exactly the disaster this file exists to avoid.
     *
     * But "or", "gold", "silver", "oil" are WORDS: nobody shouts them in
     * capitals. Hence a second, narrow pass: a lowercase word becomes a ticker
     * only if it is in the VERIFIED alias table. `@gold` passes; `@here`,
     * `@all` and `@marie` do not — they are in no table.
     */
    private val ALIAS_PATTERN = Regex("(?<![A-Za-z0-9_/.@])@([A-Za-z]{2,8})(?![A-Za-z0-9_/])")

    /**
     * Commodities quote as futures on Yahoo, suffix `=F`. `XAUUSD=X` and
     * `XAGUSD=X` — the forex form, the obvious one — answer 404: tested on iOS,
     * not assumed (`TickerQuoteFetcher.swift:137-139`).
     */
    private val CRYPTO_ALIASES: Map<String, String> = mapOf(
        "BTC" to "BTC-USD", "ETH" to "ETH-USD", "SOL" to "SOL-USD",
        "DOGE" to "DOGE-USD", "XRP" to "XRP-USD", "ADA" to "ADA-USD",
        "BNB" to "BNB-USD", "LTC" to "LTC-USD", "DOT" to "DOT-USD",
        "AVAX" to "AVAX-USD", "LINK" to "LINK-USD", "XMR" to "XMR-USD",
        "GOLD" to "GC=F", "XAU" to "GC=F", "OR" to "GC=F",
        "SILVER" to "SI=F", "XAG" to "SI=F", "ARGENT" to "SI=F",
        "OIL" to "CL=F", "WTI" to "CL=F", "PETROL" to "CL=F",
        "BRENT" to "BZ=F", "GAS" to "NG=F",
        "COPPER" to "HG=F", "CUIVRE" to "HG=F",
        "PLAT" to "PL=F", "PLATINE" to "PL=F"
    )

    /**
     * Lowercase STOCKS: `@aapl`.
     *
     * Why a LIST and not "any 2-5 letter run": this file holds that "somebody
     * called Max turned into a link to a stock chart is a far worse failure than
     * a ticker rendered as plain text". Opening lowercase wide would do exactly
     * that — `@marie` in a one-to-one conversation, where there is no mention to
     * resolve, would become `$MARIE`. A whitelist makes that impossible by
     * construction: a first name is not in it.
     *
     * Two rules to get in, and the second costs entries:
     *  1. the symbol was QUERIED and returns a quote;
     *  2. it must not be a common word in English or French.
     * It is (2) that excludes `COIN` (Coinbase, but "coin" in French).
     */
    private val LOWERCASE_STOCKS: Set<String> = setOf(
        "AAPL", "TSLA", "MSFT", "NVDA", "GOOGL", "GOOG", "AMZN", "NFLX",
        "AMD", "INTC", "MSTR", "PLTR", "SPY", "QQQ", "JPM", "BAC",
        "BABA", "RIVN", "PYPL", "ABNB", "ROKU", "ORCL", "UBER", "NIO",
        "TSM", "ASML", "SMCI", "QCOM", "CSCO", "ADBE", "IBM", "GME",
        "AMC", "VOO", "VTI", "LCID", "SOFI", "HOOD", "RDDT", "AVGO"
    )

    /** Does this word name a known value? The only two lists that allow lowercase. */
    fun isKnownAlias(symbol: String): Boolean {
        val up = symbol.uppercase(Locale.US)
        return CRYPTO_ALIASES.containsKey(up) || LOWERCASE_STOCKS.contains(up)
    }

    /** The symbol as it goes to the network. `TickerQuoteFetcher.swift:149-153`. */
    fun resolvedSymbol(symbol: String): String {
        val up = symbol.uppercase(Locale.US)
        if (up.contains("-")) return up      // already explicit: `@BTC-USD`
        return CRYPTO_ALIASES[up] ?: up
    }

    /** Every ticker in [text], in order of appearance. */
    fun matches(text: String): List<Match> {
        if (!text.contains('@')) return emptyList()   // cheap bail for the common case
        val found = mutableListOf<Match>()
        for (m in PATTERN.findAll(text)) {
            val symbol = m.groups[2]?.value ?: continue
            found += Match(
                symbol = symbol,
                exchange = m.groups[1]?.value,
                start = m.range.first,
                end = m.range.last + 1
            )
        }
        for (m in ALIAS_PATTERN.findAll(text)) {
            val word = m.groups[1]?.value ?: continue
            if (!isKnownAlias(word)) continue
            val start = m.range.first
            val end = m.range.last + 1
            // Skip anything the main pattern already covers.
            if (found.any { start < it.end && it.start < end }) continue
            // `Match.symbol` is documented as UPPERCASE: `@gold` shows `$GOLD`
            // and goes to the network through the same table as `@GOLD`.
            found += Match(word.uppercase(Locale.US), null, start, end)
        }
        return found.sortedBy { it.start }
    }

    /**
     * True when the text carries at least one ticker. Must stay in agreement
     * with [matches], lowercase aliases included — otherwise the chip for
     * `@gold` depends on who is asking.
     */
    fun hasTicker(text: String): Boolean =
        text.contains('@') && matches(text).isNotEmpty()
}
