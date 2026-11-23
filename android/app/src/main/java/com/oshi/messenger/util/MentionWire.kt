package com.oshi.messenger.util

import com.oshi.messenger.network.v2.OSHICryptoV2
import org.json.JSONArray
import org.json.JSONObject

/**
 * `@name` person mentions in a group message — __MENTIONS_2026_09_23__.
 *
 * ============================================================ WHY IT IS SHAPED LIKE THIS
 *
 * No shipped phone sends mentions. iOS has `GroupMentions.swift` (a name matcher plus a
 * `@[publicKey]` wire token) but nothing in the app calls it — only `OSHITests` does — and
 * the `@[key]` token would print as a raw base64 key on every phone already installed.
 * So the design is DEGRADE FIRST:
 *
 *  - **The body is prose.** The sender inserts `@Alice` into the text itself. An iPhone on
 *    1.0.43 or an Android on 1.6.25 shows exactly that: a readable `@Alice`, never a key.
 *  - **The targets ride an OPTIONAL extra key** of the existing GroupMessage JSON:
 *
 *    ```
 *    "mentions":[{"publicKey":"<base64 X25519>","name":"Alice"}]
 *    ```
 *
 *    iOS `GroupMessage` is synthesized `Codable` with explicit `CodingKeys`
 *    (`OSHI/GroupMessaging.swift:341,:377` at the 1.0.43 release commit 4befbea0), and
 *    `JSONDecoder` ignores keys that are not in `CodingKeys` — the same reason the
 *    `messageId` / `content` / `senderName` Android compatibility keys this client already
 *    sends are inert there. Android reads the group JSON with `org.json` `optString`
 *    (`GroupManager.handleIncomingGroupMessage`), which never looks at a key it does not ask
 *    for. Neither decoder rejects the message; both render the body as text.
 *  - **No ranges on the wire.** The body can be wrapped in a `💬REPLY💬` envelope and edited
 *    later, so an offset would point at the wrong characters on one side or the other.
 *    Each entry names the token (`"@" + name`) and the receiver finds it in the text it
 *    actually displays.
 *
 * ============================================================ WHAT A RECEIVER TRUSTS
 *
 * A mention is a ping that can break through a muted group, so [admit] keeps an entry
 * only when (1) the key is a CURRENT member of the group, (2) the token `@name` really
 * appears in the body at a word boundary — a hidden mention that notifies someone without
 * showing it is refused — and (3) the name is short and printable. At most
 * [MAX_MENTIONS] survive, one per key.
 *
 * OSHI-Desktop carries a byte-identical copy of this codec (`group/MentionWire.kt`); the
 * golden vector `docs/fixtures/mentions/mentions_v1.json` pins [render] in both test trees.
 */
object MentionWire {

    /** The GroupMessage JSON key. */
    const val FIELD = "mentions"

    const val MAX_MENTIONS = 20
    const val MAX_NAME_CHARS = 64

    data class Mention(val publicKey: String, val name: String)

    /** A token found in displayed text: [start] is the `@`, [endExclusive] ends the name. */
    data class Span(val start: Int, val endExclusive: Int, val publicKey: String, val name: String)

    /**
     * The exact `mentions` value bytes: `[{"publicKey":"…","name":"…"}, …]`, keys in that
     * order, escaped by the shared [OSHICryptoV2.jsonEscape]. Empty list → null (omit the key).
     */
    fun render(mentions: List<Mention>): String? {
        if (mentions.isEmpty()) return null
        return mentions.joinToString(",", "[", "]") {
            "{\"publicKey\":\"" + OSHICryptoV2.jsonEscape(it.publicKey) +
                "\",\"name\":\"" + OSHICryptoV2.jsonEscape(it.name) + "\"}"
        }
    }

    /** Lenient parse of the wire value (any JSON array); malformed entries are skipped. */
    fun parse(value: Any?): List<Mention> {
        val arr = when (value) {
            is JSONArray -> value
            is String -> runCatching { JSONArray(value) }.getOrNull()
            else -> null
        } ?: return emptyList()
        val out = ArrayList<Mention>()
        for (i in 0 until minOf(arr.length(), MAX_MENTIONS * 2)) {
            val o = arr.opt(i) as? JSONObject ?: continue
            val key = (o.opt("publicKey") as? String)?.trim().orEmpty()
            val name = (o.opt("name") as? String)?.trim().orEmpty()
            if (key.isEmpty() || !nameOk(name)) continue
            out += Mention(key, name)
        }
        return out
    }

    fun nameOk(name: String): Boolean =
        name.isNotEmpty() && name.length <= MAX_NAME_CHARS &&
            name.none { it == '\n' || it == '\r' || it == '@' || Character.isISOControl(it) }

    /**
     * The entries a RECEIVER keeps (see the class doc). [isMember] must answer for the
     * group's current roster; [sameKey] compares identities (base64 vs base64url spelling).
     */
    fun admit(
        body: String,
        mentions: List<Mention>,
        isMember: (String) -> Boolean,
        sameKey: (String, String) -> Boolean = { a, b -> a == b },
    ): List<Mention> {
        val out = ArrayList<Mention>()
        for (m in mentions) {
            if (out.size >= MAX_MENTIONS) break
            if (!nameOk(m.name) || !isMember(m.publicKey)) continue
            if (out.any { sameKey(it.publicKey, m.publicKey) }) continue
            if (spans(body, listOf(m)).isEmpty()) continue
            out += m
        }
        return out
    }

    /** True when [me] is among the admitted [mentions]. */
    fun mentions(me: String, mentions: List<Mention>, sameKey: (String, String) -> Boolean = { a, b -> a == b }): Boolean =
        mentions.any { sameKey(it.publicKey, me) }

    /**
     * Every `@name` token of [mentions] in [text], longest name first, non-overlapping,
     * sorted by position. Boundaries follow iOS `GroupMentions`: the `@` must not follow
     * `[A-Za-z0-9_/.@&]` (email addresses, URL paths), and the name must not run on into a
     * letter or digit (`@Al` never matches inside `@Alice`). Case-insensitive.
     */
    fun spans(text: String, mentions: List<Mention>): List<Span> {
        if (mentions.isEmpty() || !text.contains('@')) return emptyList()
        val taken = BooleanArray(text.length)
        val out = ArrayList<Span>()
        for (m in mentions.sortedByDescending { it.name.length }) {
            val token = "@" + m.name
            var from = 0
            while (true) {
                val at = text.indexOf(token, from, ignoreCase = true)
                if (at < 0) break
                val end = at + token.length
                from = at + 1
                val before = if (at == 0) null else text[at - 1]
                if (before != null && (before.isLetterOrDigit() || before in "_/.@&")) continue
                val after = if (end >= text.length) null else text[end]
                if (after != null && (after.isLetterOrDigit() || after == '_')) continue
                if ((at until end).any { taken[it] }) continue
                for (i in at until end) taken[i] = true
                out += Span(at, end, m.publicKey, m.name)
            }
        }
        return out.sortedBy { it.start }
    }

    /**
     * Composer helper: the `@query` being typed at [cursor], or null. Returns the index of
     * the `@` and the query after it (may be empty). Same boundary rule as [spans].
     */
    fun activeQuery(text: String, cursor: Int): Pair<Int, String>? {
        val c = cursor.coerceIn(0, text.length)
        var i = c - 1
        while (i >= 0) {
            val ch = text[i]
            if (ch == '@') {
                val before = if (i == 0) null else text[i - 1]
                if (before != null && (before.isLetterOrDigit() || before in "_/.@&")) return null
                val q = text.substring(i + 1, c)
                return if (q.length <= MAX_NAME_CHARS && q.none { it == '\n' }) i to q else null
            }
            if (ch == '\n' || c - i > MAX_NAME_CHARS + 1) return null
            i--
        }
        return null
    }

    /**
     * Mentions still present in [text] at send time — the user may have deleted a picked
     * token before sending. Keeps picker order, one per key.
     */
    fun stillPresent(text: String, picked: List<Mention>): List<Mention> =
        picked.distinctBy { it.publicKey }.filter { spans(text, listOf(it)).isNotEmpty() }.take(MAX_MENTIONS)
}
