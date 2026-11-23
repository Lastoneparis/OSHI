package com.oshi.messenger.util

import com.oshi.messenger.util.CashuJson.array
import com.oshi.messenger.util.CashuJson.long
import com.oshi.messenger.util.CashuJson.string
import java.util.Base64

/**
 * 🪙 Cashu e-cash tokens pasted into a conversation: recognise, read, preview.
 *
 * ══ WHAT THIS IS, AND FIRMLY WHAT IT IS NOT ══
 *
 * A Cashu token is a **bearer instrument**: a string that IS the money. Whoever
 * holds the string can spend it, exactly like a banknote left on a table. That
 * single fact decides the whole design.
 *
 * This file reads a token and nothing else. It does not talk to a mint, it does
 * not hold a balance, it does not sign, swap or melt, and OSHI never takes
 * custody of anyone's funds. Redemption is handed to a wallet the user already
 * trusts, through the `cashu:` URI the spec defines for exactly that. The
 * consequence is worth stating plainly because it is the point: there is no
 * money inside OSHI to lose, to regulate, or to seize.
 *
 * That also keeps us out of the path the payments master plan was blocked on —
 * `MASTERPLAN_MESH_PAYMENTS.md` PART B put every wallet phase behind a legal
 * entity, a Stripe Connect platform application and an Apple organization
 * account, because holding user balances in the EU is regulated e-money. Not
 * holding them needs none of that.
 *
 * Everything here is pure text → data. No network, no state, no disk, so it is
 * safe to run on every message as it renders — the same contract as
 * [TickerLinks].
 *
 * Format reference: https://github.com/cashubtc/nuts/blob/main/00.md
 */
object CashuTokens {

    /** One proof: a single denomination, already signed by the mint. */
    data class Proof(
        val amount: Long,
        val secret: String,
        /** The unblinded signature `C`, lower-case hex. */
        val signature: String,
        /** Keyset id, lower-case hex. Short form is 8 bytes, full form 33. */
        val keysetId: String,
    )

    /** The proofs that came from one mint. */
    data class Entry(val mint: String, val proofs: List<Proof>)

    data class Token(
        val entries: List<Entry>,
        /** `sat`, `msat`, `usd`… absent in old V3 tokens, where `sat` was assumed. */
        val unit: String?,
        val memo: String?,
        /** `A` for the deprecated V3 form, `B` for V4. */
        val version: Char,
        /** The exact text that was recognised, so the UI re-emits it byte for byte. */
        val raw: String,
    ) {
        val totalAmount: Long get() = entries.sumOf { e -> e.proofs.sumOf { it.amount } }
        val mints: List<String> get() = entries.map { it.mint }.distinct()
        val proofCount: Int get() = entries.sumOf { it.proofs.size }

        /**
         * The URI a wallet app registers for. Handing this to the system is the
         * whole of "redeem": the wallet the user already trusts takes it from
         * there, and OSHI never sees a key or a balance.
         */
        val redeemUri: String get() = "cashu:$raw"
    }


    /**
     * Upper bound on a token we will even look at. Real tokens are a few KB;
     * anything far larger is someone spending our CPU for us, and base64 of a
     * megabyte decodes before any structure check could reject it.
     */
    const val MAX_TOKEN_LENGTH = 60_000

    /** Below this there is no room for a prefix and a payload. */
    private const val MIN_TOKEN_LENGTH = 12

    /**
     * Sanity cap on an amount: the order of all the satoshis that will ever
     * exist. A token claiming more is not a payment, and a bubble that renders
     * it as one is a lie with a number in it.
     */
    private const val MAX_AMOUNT = 2_100_000_000_000_000L

    /**
     * Pull the bare `cashuA…`/`cashuB…` out of whatever the user handed us: a
     * `cashu:` or `cashu://` URI, possibly percent-encoded by whatever app it
     * was copied from.
     *
     * Deliberately MORE permissive than [TOKEN_REGEX] about the payload
     * alphabet — `+`, `/` and `.` are accepted here. The difference is who is
     * asking: this is for input a user explicitly typed after `/pay`, where
     * being strict just makes a real token fail for an invisible reason, while
     * the regex scans running prose, where a loose alphabet swallows sentences.
     */
    fun bareToken(raw: String): String? {
        var token = raw.trim()
        val lower = token.lowercase()
        token = when {
            lower.startsWith("cashu://") -> token.substring(8)
            lower.startsWith("cashu:") -> token.substring(6)
            else -> token
        }
        if (token.contains('%')) {
            token = runCatching { java.net.URLDecoder.decode(token, "UTF-8") }.getOrElse { return null }
        }
        if (token.length < MIN_TOKEN_LENGTH || token.length > MAX_TOKEN_LENGTH) return null
        if (!token.startsWith("cashuA") && !token.startsWith("cashuB")) return null
        if (!token.all { it.isLetterOrDigit() || it in "-_+/=." }) return null
        return token
    }

    // A token is `cashu` + a version letter + base64url. The character class is
    // deliberately base64url ONLY (no `+`, no `/`): a bare `+` inside running
    // prose would otherwise swallow the rest of a sentence into the match.
    private val TOKEN_REGEX = Regex("""(?:cashu://|cashu:)?cashu([AB])([A-Za-z0-9_=.-]{20,})""")

    /** Where a token sits in the original string, so the bubble can style it. */
    data class Match(val token: Token, val start: Int, val end: Int)

    /**
     * Every token in [text], in order, skipping anything that does not decode.
     *
     * A string that merely looks like a token is not reported: the bubble must
     * never offer to redeem something that cannot be redeemed, and a false
     * positive in a chat about Cashu is otherwise guaranteed.
     */
    fun find(text: String): List<Match> =
        TOKEN_REGEX.findAll(text).mapNotNull { m ->
            decode(m.value)?.let { Match(it, m.range.first, m.range.last + 1) }
        }.toList()

    /** True when [text] carries at least one token that actually decodes. */
    fun containsToken(text: String): Boolean = find(text).isNotEmpty()

    /**
     * Decode one serialized token, or null if it is not one.
     *
     * Null rather than an exception: this runs while a message list scrolls, and
     * whatever a stranger pasted is not an exceptional condition.
     */
    @JvmOverloads
    fun decode(serialized: String, strict: Boolean = false): Token? {
        val trimmed = bareToken(serialized) ?: return null
        val version = trimmed[5]
        val body = trimmed.substring(6)
        val bytes = base64UrlDecode(body) ?: return null
        return try {
            val token = when (version) {
                'A' -> decodeV3(bytes, trimmed)
                'B' -> decodeV4(bytes, trimmed)
                else -> null
            } ?: return null
            // An amount past every satoshi that will ever exist is a display
            // attack, not a payment; and `strict` (what /pay uses before it
            // puts a token on the wire) additionally refuses a token whose
            // amount is zero or unreadable.
            if (token.totalAmount < 0 || token.totalAmount > MAX_AMOUNT) return null
            if (strict && token.totalAmount <= 0) return null
            token
        } catch (_: Exception) {
            // Malformed CBOR, malformed JSON, a field of the wrong type: all the
            // same answer to the caller — this is not a token we can preview.
            null
        }
    }

    /**
     * Base64url, tolerating what wallets actually emit.
     *
     * NUT-00 says base64url with optional padding. Some senders paste standard
     * base64 instead, and `+`/`/` would be rejected outright; normalising costs
     * two replacements and spares the user a token that "does not work" for a
     * reason nobody can see.
     */
    private fun base64UrlDecode(body: String): ByteArray? = try {
        val normalized = body.replace('+', '-').replace('/', '_').trimEnd('=')
        Base64.getUrlDecoder().decode(normalized)
    } catch (_: IllegalArgumentException) {
        null
    }

    // ── V3: base64url of JSON ────────────────────────────────────────────────

    private fun decodeV3(bytes: ByteArray, raw: String): Token? {
        val root = CashuJson.parse(String(bytes, Charsets.UTF_8))
        val entries = root.array("token").mapNotNull { element ->
            val mint = element.string("mint")?.trimEnd('/') ?: return@mapNotNull null
            val proofs = element.array("proofs").mapNotNull { p ->
                Proof(
                    amount = p.long("amount") ?: return@mapNotNull null,
                    secret = p.string("secret") ?: return@mapNotNull null,
                    signature = p.string("C")?.lowercase() ?: return@mapNotNull null,
                    keysetId = p.string("id")?.lowercase() ?: return@mapNotNull null,
                )
            }
            if (proofs.isEmpty()) null else Entry(mint, proofs)
        }
        if (entries.isEmpty()) return null
        return Token(
            entries = entries,
            unit = root.string("unit"),
            memo = root.string("memo"),
            version = 'A',
            raw = raw,
        )
    }

    // ── V4: base64url of CBOR ────────────────────────────────────────────────

    private fun decodeV4(bytes: ByteArray, raw: String): Token? {
        val root = CashuCbor.decode(bytes) as? CashuCbor.Value.Obj ?: return null
        val mint = (root.entries["m"] as? CashuCbor.Value.Text)?.value?.trimEnd('/') ?: return null
        val groups = (root.entries["t"] as? CashuCbor.Value.Arr)?.items ?: return null

        val proofs = groups.flatMap { group ->
            val g = group as? CashuCbor.Value.Obj ?: return@flatMap emptyList()
            val keysetId = (g.entries["i"] as? CashuCbor.Value.Bytes)?.value?.toHex()
                ?: return@flatMap emptyList()
            ((g.entries["p"] as? CashuCbor.Value.Arr)?.items ?: emptyList()).mapNotNull { p ->
                val po = p as? CashuCbor.Value.Obj ?: return@mapNotNull null
                val amount = (po.entries["a"] as? CashuCbor.Value.UInt)?.value ?: return@mapNotNull null
                val secret = (po.entries["s"] as? CashuCbor.Value.Text)?.value ?: return@mapNotNull null
                val signature = (po.entries["c"] as? CashuCbor.Value.Bytes)?.value?.toHex()
                    ?: return@mapNotNull null
                Proof(amount, secret, signature, keysetId)
            }
        }
        if (proofs.isEmpty()) return null
        // V4 carries one mint for the whole token, so there is exactly one entry.
        return Token(
            entries = listOf(Entry(mint, proofs)),
            unit = (root.entries["u"] as? CashuCbor.Value.Text)?.value,
            memo = (root.entries["d"] as? CashuCbor.Value.Text)?.value,
            version = 'B',
            raw = raw,
        )
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return out.toString()
    }
}
