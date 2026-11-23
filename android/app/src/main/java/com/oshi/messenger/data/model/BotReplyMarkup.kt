package com.oshi.messenger.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * One button of a bot's inline keyboard — the Telegram shape the OSHI bot platform
 * adopted:
 *
 * ```
 * { "text": "Yes", "callback_data": "vote:yes" }
 * { "text": "Docs", "url": "https://…" }
 * { "text": "Open", "web_app": { "url": "https://…" } }
 * ```
 *
 * Exactly one of [callbackData] / [url] / [webAppUrl] is non-null — that invariant is
 * established by [BotReplyMarkupParser] and is what makes [kind] total. A button that
 * arrived with none of them, or with two, never becomes a `BotInlineButton` at all.
 */
data class BotInlineButton(
    val text: String,
    val callbackData: String? = null,
    val url: String? = null,
    val webAppUrl: String? = null
) {
    enum class Kind { CALLBACK, URL, WEB_APP }

    val kind: Kind
        get() = when {
            callbackData != null -> Kind.CALLBACK
            url != null -> Kind.URL
            else -> Kind.WEB_APP
        }

    /**
     * Stable per-message identity for this button, used to key the in-flight/loading
     * state. Two buttons in one keyboard can share a label, so the payload is part of
     * the key.
     */
    val actionKey: String
        get() = when (kind) {
            Kind.CALLBACK -> "cb:$callbackData"
            Kind.URL -> "url:$url"
            Kind.WEB_APP -> "wa:$webAppUrl"
        }
}

/** A bot message's inline keyboard: rows of buttons, already clamped and validated. */
data class BotReplyMarkup(val rows: List<List<BotInlineButton>>) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

/**
 * Defensive reader for the `replyMarkup` field of an inbound bot message.
 *
 * The server validates what it emits, but a client that trusts a server's validation
 * is one malformed row away from crashing in a `LazyColumn` item — i.e. taking the
 * user's whole thread down, on a phone, with no way back except clearing data. So
 * NOTHING here throws and nothing here trusts a type: every value is read with
 * `opt*`, every list is clamped, and a button that does not satisfy the contract is
 * dropped rather than rendered half-formed.
 *
 * The limits are the platform's own (10 rows, 8 buttons per row); they are re-applied
 * here because "the server enforces it" is a statement about today's server.
 */
object BotReplyMarkupParser {

    /** Platform limit: at most 10 rows in one keyboard. */
    const val MAX_ROWS = 10

    /** Platform limit: at most 8 buttons in one row. */
    const val MAX_BUTTONS_PER_ROW = 8

    /** A label longer than this is truncated — a button is not a paragraph. */
    private const val MAX_TEXT_LENGTH = 96

    /** Telegram's own callback_data ceiling (bytes). Anything longer is dropped. */
    private const val MAX_CALLBACK_DATA_BYTES = 64

    /** No `javascript:`, no `intent:`, no `file:` — a bot hands out web links only. */
    private val ALLOWED_URL_SCHEMES = setOf("http", "https")

    /** A mini-app is loaded into a WebView with JS on, so it must be TLS. */
    private val ALLOWED_WEB_APP_SCHEMES = setOf("https")

    /**
     * Parse the raw JSON text of a `replyMarkup` object (as persisted on
     * [GroupMessage.replyMarkup]). Returns null for null/blank/garbage input and for a
     * keyboard from which no button survived validation.
     */
    fun parse(raw: String?): BotReplyMarkup? {
        if (raw.isNullOrBlank()) return null
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        return fromJson(json)
    }

    /** Same as [parse], for a `replyMarkup` already decoded into a [JSONObject]. */
    fun fromJson(markup: JSONObject?): BotReplyMarkup? {
        if (markup == null) return null
        val keyboard = markup.optJSONArray("inline_keyboard") ?: return null
        val rows = mutableListOf<List<BotInlineButton>>()
        val rowCount = minOf(keyboard.length(), MAX_ROWS)
        for (r in 0 until rowCount) {
            val rawRow = keyboard.optJSONArray(r) ?: continue
            val row = parseRow(rawRow)
            if (row.isNotEmpty()) rows.add(row)
        }
        return if (rows.isEmpty()) null else BotReplyMarkup(rows)
    }

    private fun parseRow(rawRow: JSONArray): List<BotInlineButton> {
        val buttons = mutableListOf<BotInlineButton>()
        val count = minOf(rawRow.length(), MAX_BUTTONS_PER_ROW)
        for (b in 0 until count) {
            val obj = rawRow.optJSONObject(b) ?: continue
            parseButton(obj)?.let { buttons.add(it) }
        }
        return buttons
    }

    private fun parseButton(obj: JSONObject): BotInlineButton? {
        val text = obj.optString("text", "").trim()
        if (text.isEmpty()) return null
        val label = if (text.length > MAX_TEXT_LENGTH) text.take(MAX_TEXT_LENGTH) else text

        val callbackData = obj.optString("callback_data", "")
            .takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= MAX_CALLBACK_DATA_BYTES }
        val url = obj.optString("url", "").takeIf { isAllowed(it, ALLOWED_URL_SCHEMES) }
        // `web_app` is an OBJECT in the Telegram shape. A string there is not a
        // mini-app button we understand, so it is not one we render.
        val webAppUrl = obj.optJSONObject("web_app")
            ?.optString("url", "")
            ?.takeIf { isAllowed(it, ALLOWED_WEB_APP_SCHEMES) }

        // "EXACTLY ONE of callback_data/url/web_app" — a button carrying two of them is
        // ambiguous about what a tap means, and guessing is how a tap sends a user's
        // group id to the wrong place. Drop it.
        val present = listOfNotNull(callbackData, url, webAppUrl)
        if (present.size != 1) return null

        return BotInlineButton(
            text = label,
            callbackData = callbackData,
            url = url,
            webAppUrl = webAppUrl
        )
    }

    /**
     * True when [value] parses as an absolute URL whose scheme is in [schemes].
     *
     * Deliberately `java.net.URI` and not `android.net.Uri`: this keeps the parser a
     * plain-JVM object that a unit test can exercise without Robolectric, and
     * `android.net.Uri.parse` never fails anyway — it would return a Uri with a null
     * scheme rather than reject the string, which is precisely the check we want.
     */
    private fun isAllowed(value: String, schemes: Set<String>): Boolean {
        if (value.isEmpty()) return false
        val scheme = try {
            java.net.URI(value).scheme
        } catch (_: Exception) {
            return false
        } ?: return false
        return scheme.lowercase() in schemes
    }
}

/**
 * The `bot_callback_answer` queue item — a bot's reply to a callback query, shown as a
 * transient snackbar or, when [showAlert] is set, as a modal the user must dismiss.
 *
 * It is NOT a message: it is never stored, never appears in the thread, and is dropped
 * if nothing is listening when it arrives.
 */
data class BotCallbackAnswer(
    val callbackQueryId: String,
    val text: String,
    val showAlert: Boolean,
    val botName: String,
    val timestamp: Long
)
