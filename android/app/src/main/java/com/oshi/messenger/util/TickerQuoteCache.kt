package com.oshi.messenger.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 📈 Fetches a quote, once, for everybody.
 *
 * Port of `OSHI/TickerQuoteFetcher`'s caching half. The privacy cost is the same
 * and worth stating plainly, because it is the reason the cache exists:
 *
 * The chip asks Yahoo the moment the message renders, so a third party learns
 * WHICH symbol is being discussed and WHEN, without anyone tapping anything.
 * What limits the leak, and nothing else:
 *   • no message content, no contact, no key is transmitted — only the symbol;
 *   • the cache is PROCESS-WIDE and lasts 60 s, so the same symbol in ten bubbles
 *     produces ONE request, not ten.
 *
 * If that trade stops being acceptable, the rollback is to stop calling [get]
 * from the chip and fetch on tap instead; everything else here is unchanged.
 */
object TickerQuoteCache {

    private const val TTL_MS = 60_000L
    private const val TAG = "TickerQuote"

    private data class Entry(val quote: TickerQuote, val at: Long)

    private val lock = Mutex()
    private val entries = HashMap<String, Entry>()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The last quote we have for [symbol], **whatever its age**. Never touches
     * the network.
     *
     * ⚠️ AGE IS DELIBERATELY IGNORED — this is the half of iOS's
     * `__TICKER_STICKY_2026_08_26__` Android was missing
     * (`TickerQuoteFetcher.swift:74-78`). This used to return null past the 60 s
     * TTL, which meant reopening a conversation more than a minute later — or
     * reopening it at all with no network — drew a bare `$SYMBOL` where a
     * perfectly good price was sitting in memory. iOS states the rule as: a
     * two-minute-old price shown while it refreshes beats an empty chip.
     * Freshness is [get]'s job, not this one's.
     */
    fun cached(symbol: String): TickerQuote? {
        val key = symbol.uppercase()
        return synchronized(entries) { entries[key] }?.quote
    }

    /** True only while the cached quote is still inside the TTL. */
    private fun isFresh(symbol: String): Boolean {
        val key = symbol.uppercase()
        val e = synchronized(entries) { entries[key] } ?: return false
        return System.currentTimeMillis() - e.at <= TTL_MS
    }

    /**
     * The quote for [symbol], from cache when fresh, otherwise from Yahoo.
     *
     * Idempotent: a fresh cache returns without a request, and a stale one is
     * replaced only if the refresh succeeds — so a network blip leaves the old
     * price on screen rather than blanking the chip. A caller that gets a
     * non-null answer here can always display it; a null one means "nothing was
     * ever known", not "the refresh failed" (see the last line).
     */
    suspend fun get(symbol: String): TickerQuote? {
        if (isFresh(symbol)) cached(symbol)?.let { return it }
        val key = symbol.uppercase()
        return withContext(Dispatchers.IO) {
            lock.withLock {
                // Another coroutine may have filled it while we waited for the lock.
                // Only a FRESH fill counts: a stale entry is exactly what we are
                // here to replace, and treating it as a hit would mean a symbol
                // whose first fetch went stale never refreshed again.
                if (isFresh(symbol)) cached(symbol)?.let { return@withLock it }
                val fetched = runCatching {
                    val req = Request.Builder()
                        .url(TickerQuote.url(symbol))
                        // Yahoo answers 403 to a request with no User-Agent.
                        .header("User-Agent", "Mozilla/5.0")
                        // Never let a quote sit in a shared cache beside message content.
                        .header("Cache-Control", "no-store")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) null
                        else resp.body?.string()?.let { TickerQuote.parseOshi(it, symbol) }
                    }
                }.getOrElse {
                    Log.w(TAG, "quote for $key failed: ${it.message}")
                    null
                }
                if (fetched != null) {
                    synchronized(entries) {
                        entries[key] = Entry(fetched, System.currentTimeMillis())
                        // Bounded: a thread full of distinct symbols must not grow
                        // this without limit.
                        if (entries.size > 128) {
                            entries.entries.sortedBy { it.value.at }.take(64)
                                .forEach { entries.remove(it.key) }
                        }
                    }
                }
                // A FAILED refresh falls back to whatever we last knew, however
                // old. `TickerQuoteFetcher.swift:93-96`: "un rafraîchissement qui
                // échoue laisse le dernier cours connu en place au lieu de le
                // remplacer par « indisponible »."
                fetched ?: cached(symbol)
            }
        }
    }
}
