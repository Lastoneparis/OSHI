package com.oshi.messenger.network.v2

import java.security.SecureRandom
import java.util.Locale
import java.util.Random

/**
 * Per-request OUTER presentation of a cover request: alias path, an
 * analytics-style query string, the optional header set and its order, and the
 * Content-Type spelling. Nothing here carries data — the signed V2 request is
 * entirely inside the padded frame — so every choice is free as long as the
 * relay still accepts it. Compatibility with the relay copies in the tree
 * (checked by `tools/covert-regression/cover_vectors.test.js`):
 *  - the router matches the alias on `req.url.split('?')[0]`, so a query is ignored;
 *  - `decode` only requires the Content-Type to start with `application/json`;
 *  - the cover handler reads no outer request header besides Content-Type.
 *
 * What this does and does not buy (docs/COVER_TRANSPORT_STATUS_2026-11-13.md):
 * under TLS a passive observer sees none of these strings, only the record
 * LENGTHS they produce. The point is that the request length is no longer one of
 * a handful of constants. Path, query and header names matter only to something
 * that terminates TLS, which certificate pinning on oshi-messenger.com refuses.
 * The hostname (SNI) is unchanged and remains the dominant identifier.
 */
object CoverPresentation {

    data class Outer(
        val path: String,
        /** Encoded query without the leading '?', or null for none. */
        val query: String?,
        /** Optional headers, in the order they are to be added. */
        val headers: List<Pair<String, String>>,
        val contentType: String,
    )

    internal val CONTENT_TYPES = listOf(
        "application/json",
        "application/json; charset=utf-8",
        "application/json;charset=UTF-8",
    )
    private val ACCEPTS = listOf("*/*", "application/json", "application/json, text/plain, */*")

    /** Template ids, exposed for the distribution test. */
    internal const val QUERY_TEMPLATES = 5

    private val secureRandom = SecureRandom()

    // Process-stable values: a real analytics SDK keeps its property/session id
    // for the life of the process; re-rolling it per request would itself stand out.
    private val sessionTid: String = randomToken(secureRandom, 10, "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789")
    private val sessionCid: String = "${1_000_000_000L + (secureRandom.nextLong() and Long.MAX_VALUE) % 9_000_000_000L}" +
        ".${System.currentTimeMillis() / 1000}"
    private val sessionSid: String = randomToken(secureRandom, 16, "0123456789abcdef")
    private val siteId: Int = 1 + secureRandom.nextInt(12)
    private val sequence = java.util.concurrent.atomic.AtomicInteger()

    fun next(
        random: Random = secureRandom,
        nowMs: Long = System.currentTimeMillis(),
        languageTag: String = Locale.getDefault().toLanguageTag(),
    ): Outer {
        val seq = sequence.incrementAndGet()
        val query = when (random.nextInt(QUERY_TEMPLATES)) {
            0 -> null
            1 -> "v=2&tid=G-$sessionTid&cid=$sessionCid&_s=${seq % 200}&_p=${1_000_000_000L + random.nextInt(Int.MAX_VALUE)}"
            2 -> "idsite=$siteId&rec=1&r=${100_000 + random.nextInt(900_000)}" +
                "&h=${(nowMs / 3_600_000) % 24}&m=${(nowMs / 60_000) % 60}&s=${(nowMs / 1000) % 60}"
            3 -> "_=$nowMs"
            else -> "ver=${1 + random.nextInt(3)}.${random.nextInt(10)}.${random.nextInt(21)}&sid=$sessionSid&seq=$seq"
        }
        val optional = mutableListOf<Pair<String, String>>()
        if (random.nextInt(10) < 7) optional += "Accept" to ACCEPTS[random.nextInt(ACCEPTS.size)]
        if (random.nextInt(10) < 6) optional += "Accept-Language" to acceptLanguage(languageTag, random)
        if (random.nextBoolean()) optional += "Cache-Control" to "no-cache"
        if (random.nextInt(10) < 3) optional += "Pragma" to "no-cache"
        optional.shuffle(random)
        return Outer(
            path = com.oshi.messenger.service.CoverCodec.selectEndpointPath(random),
            query = query,
            headers = optional,
            contentType = CONTENT_TYPES[random.nextInt(CONTENT_TYPES.size)],
        )
    }

    private fun acceptLanguage(tag: String, random: Random): String {
        val primary = tag.substringBefore('-').ifEmpty { "en" }
        return when (random.nextInt(3)) {
            0 -> tag
            1 -> if (primary == tag) tag else "$tag,$primary;q=0.9"
            else -> if (primary == "en") "$tag,en;q=0.9" else "$tag,$primary;q=0.9,en;q=0.8"
        }
    }

    private fun randomToken(random: Random, length: Int, alphabet: String): String =
        String(CharArray(length) { alphabet[random.nextInt(alphabet.length)] })
}
