package com.oshi.messenger.util

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 📈 The price of a `@TICKER` is FROZEN on the message that carries it.
 *
 * Port of `OSHI/TickerQuoteSnapshots.swift` (`__TICKER_FROZEN_2026_09_13__`). Until
 * now the Android chip refetched on every render through [TickerQuoteCache]'s 60 s
 * TTL, so:
 *  1. a message from three days ago showed TODAY's price — the bubble said "look at
 *     @TSLA" next to a number that was never the one being discussed;
 *  2. every reopening of an old conversation told Yahoo again WHICH symbol is read
 *     and WHEN.
 *
 * THE RULE (iOS's): the first display of a message captures the quote and writes it
 * here; every later display reads that copy and never touches the network. For a
 * message we just sent, "first display" is the moment of sending.
 *
 * NOT A WIRE CHANGE. Nothing leaves the device: the message stays the text `@AAPL`
 * and each device keeps ITS snapshot, taken when it first saw the message — exactly
 * as iOS does, so the two platforms stay compatible with every older client.
 *
 * Same bounds as iOS: key `messageId|SYMBOL`, written ONCE, at most 3000 entries
 * (oldest `capturedAt` evicted — they simply reload once), saves coalesced 2 s, and a
 * TOLERANT load: an unreadable file gives an empty store, never a crash.
 */
class TickerQuoteSnapshots internal constructor(
    private val storeFile: File,
    private val scope: CoroutineScope? = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis
) {

    data class Snapshot(
        val symbol: String,
        val price: Double,
        val previousClose: Double,
        val currency: String,
        val series: List<Double>,
        /** When this price was taken — not when the message was written. */
        val capturedAt: Long
    ) {
        /** The same shape the network returns, so the chip has ONE render path. */
        val quote: TickerQuote
            get() = TickerQuote(symbol, price, previousClose, currency, series)
    }

    private val entries = HashMap<String, Snapshot>()
    @Volatile private var saveScheduled = false

    init { load() }

    private fun key(messageId: String, symbol: String) = "$messageId|${symbol.uppercase()}"

    fun snapshot(messageId: String, symbol: String): Snapshot? =
        synchronized(entries) { entries[key(messageId, symbol)] }

    /**
     * Writes ONCE. A second call for the same message is ignored — that is what
     * guarantees the number never moves again, even if two bubbles on screen
     * trigger the capture at the same time.
     */
    fun capture(messageId: String, symbol: String, quote: TickerQuote) {
        val k = key(messageId, symbol)
        synchronized(entries) {
            if (entries.containsKey(k)) return
            entries[k] = Snapshot(
                quote.symbol, quote.price, quote.previousClose,
                quote.currency, quote.series, now()
            )
            evictIfNeeded()
        }
        scheduleSave()
    }

    val count: Int get() = synchronized(entries) { entries.size }

    private fun evictIfNeeded() {
        if (entries.size <= MAX_ENTRIES) return
        entries.entries.sortedBy { it.value.capturedAt }
            .take(entries.size - MAX_ENTRIES)
            .map { it.key }
            .forEach { entries.remove(it) }
    }

    private fun scheduleSave() {
        // No scope = a test store: saving is explicit ([saveNow]), so a test that
        // fills 3000 entries does not rewrite the file 3000 times.
        val s = scope ?: return
        if (saveScheduled) return
        saveScheduled = true
        s.launch {
            delay(SAVE_DELAY_MS)
            saveScheduled = false
            saveNow()
        }
    }

    internal fun saveNow() {
        val json = JSONObject()
        synchronized(entries) {
            for ((k, v) in entries) {
                json.put(k, JSONObject().apply {
                    put("symbol", v.symbol)
                    put("price", v.price)
                    put("previousClose", v.previousClose)
                    put("currency", v.currency)
                    put("series", JSONArray(v.series))
                    put("capturedAt", v.capturedAt)
                })
            }
        }
        runCatching {
            val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(storeFile)) {
                storeFile.writeText(json.toString())
                tmp.delete()
            }
        }
    }

    private fun load() {
        val decoded = runCatching {
            if (!storeFile.exists()) return
            val root = JSONObject(storeFile.readText())
            val out = HashMap<String, Snapshot>()
            for (k in root.keys()) {
                val o = root.optJSONObject(k) ?: continue
                val arr = o.optJSONArray("series") ?: JSONArray()
                out[k] = Snapshot(
                    symbol = o.getString("symbol"),
                    price = o.getDouble("price"),
                    previousClose = o.getDouble("previousClose"),
                    currency = o.optString("currency", ""),
                    series = List(arr.length()) { arr.getDouble(it) },
                    capturedAt = o.optLong("capturedAt", 0L)
                )
            }
            out
        }.getOrNull() ?: return
        synchronized(entries) { entries.putAll(decoded) }
    }

    companion object {
        /** iOS `maxEntries`. */
        const val MAX_ENTRIES = 3000
        private const val SAVE_DELAY_MS = 2_000L
        private const val FILE_NAME = "ticker_quote_snapshots.json"

        @Volatile private var shared: TickerQuoteSnapshots? = null

        fun get(context: Context): TickerQuoteSnapshots =
            shared ?: synchronized(this) {
                shared ?: TickerQuoteSnapshots(
                    File(context.applicationContext.filesDir, FILE_NAME)
                ).also { shared = it }
            }
    }
}
