package com.oshi.messenger.util

/**
 * 🪙 `/pay <token> [public]` — put a Cashu token in this conversation.
 *
 * Pure text → decision, like [TickerLinks] and `CheckInPermissionPolicy`: no
 * Android, no network, no state, so every rule below is covered by a JVM test
 * rather than only being reachable by typing into a running app.
 *
 * Two refusals carry the whole feature, and both are copied from how this
 * command behaves elsewhere in the space rather than invented here:
 *
 *  1. **Nothing that does not decode goes on the wire.** A typo in a pasted
 *     token is money thrown away — the sender believes they paid, the
 *     recipient sees a string that no wallet will take, and neither of them
 *     learns why. So the token is decoded, strictly, before anything is sent.
 *  2. **A bearer token into a group needs the word `public`.** Everyone who can
 *     read the thread can spend it, and the first to redeem wins. Typing
 *     `/pay <token>` in a group therefore does not send: it explains, and asks
 *     for `/pay <token> public`. One extra word is a cheap price for not
 *     giving a stranger your money by reflex.
 */
object CashuPayCommand {

    /** What the composer should do with what the user typed. */
    sealed class Outcome {
        /** `/pay` alone — show how to use it. */
        object Usage : Outcome()

        /** The argument is not a Cashu token at all. */
        object NotAToken : Outcome()

        /** It looks like one but does not decode, or has no amount. */
        object Invalid : Outcome()

        /** Send it. [summary] is "10 sat", for the confirmation line. */
        data class Send(val token: CashuTokens.Token, val summary: String) : Outcome()

        /**
         * A group thread, and the user has not written `public`. Nothing is
         * sent. [summary] is shown so they know what they were about to expose.
         */
        data class NeedsPublicConfirm(val summary: String) : Outcome()
    }

    const val PREFIX = "/pay"

    /** True when [text] is this command, so the composer can route it. */
    fun handles(text: String): Boolean {
        val t = text.trim()
        return t == PREFIX || t.startsWith("$PREFIX ")
    }

    /**
     * @param isGroup a thread more than one other person can read. OSHI's
     *        groups are the equivalent of the public channel this command
     *        guards against elsewhere.
     */
    fun parse(text: String, isGroup: Boolean): Outcome {
        val args = text.trim().removePrefix(PREFIX).trim()
        if (args.isEmpty()) return Outcome.Usage

        val parts = args.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        // The confirmation word is a trailing argument, so a token is never
        // mistaken for it and `public` is never mistaken for a token.
        val confirmedPublic = parts.size > 1 && parts.last().lowercase() == "public"
        if (confirmedPublic) parts.removeAt(parts.size - 1)

        if (parts.size != 1) return Outcome.NotAToken
        CashuTokens.bareToken(parts[0]) ?: return Outcome.NotAToken
        val token = CashuTokens.decode(parts[0], strict = true) ?: return Outcome.Invalid

        val summary = "${token.totalAmount} ${token.unit ?: "sat"}"
        if (isGroup && !confirmedPublic) return Outcome.NeedsPublicConfirm(summary)
        return Outcome.Send(token, summary)
    }

    /**
     * __CASHU_SEND_READY_2026_11_09__ What the Send-bitcoin sheet makes of
     * whatever was pasted into it. The SAME rules as `/pay` — it literally is
     * `/pay <text>` — so the sheet can never accept a string the command would
     * refuse. A 1:1 decision: the sheet states the group risk itself.
     */
    fun parsePasted(text: String): Outcome {
        val t = text.trim()
        if (t.isEmpty()) return Outcome.Usage
        return parse("$PREFIX $t", isGroup = false)
    }

    /** The first Cashu token in [text] (a clipboard with a sentence around it, a `cashu:` link), or null. */
    fun firstToken(text: String): String? = CashuTokens.find(text).firstOrNull()?.token?.raw
}
