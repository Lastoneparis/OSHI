package com.oshi.messenger.mail

/**
 * Client-side search over already-decrypted envelopes.
 *
 * A zero-access mailbox cannot be searched server-side — the server never holds anything
 * to search. Matching runs only against what this device has already opened: the envelope
 * (from/to/subject) for every message, and — with "deep" search — the bodies this session
 * has already decrypted by opening them. Turning a search into a bulk download would leak,
 * through timing and byte counts, exactly what "zero access" is meant to hide, so deep
 * search only ever looks at what is already in [bodyCache].
 */
object MailSearch {

    fun matches(
        message: OshiMailClient.Message,
        query: String,
        deep: Boolean,
        bodyCache: Map<String, String>,
    ): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        val e = message.envelope
        val haystack = listOfNotNull(e?.from, e?.to, e?.subject, message.deliveredTo)
            .joinToString(" ")
            .lowercase()
        if (haystack.contains(q)) return true
        if (deep) {
            val body = bodyCache[message.id]
            if (body != null && body.lowercase().contains(q)) return true
        }
        return false
    }
}
