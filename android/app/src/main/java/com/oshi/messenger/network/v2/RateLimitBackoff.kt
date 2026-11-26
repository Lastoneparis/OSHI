package com.oshi.messenger.network.v2

import kotlinx.coroutines.delay

/**
 * __GROUP_FANOUT_2026_11_24__ How a relay send waits out a `429`.
 *
 * The relay charges one token per member envelope (`relay_v2.js` `rateAllow`). Before this, a
 * 429 got the same two quick retries (1 s, 2 s) as a 5xx and the device batch then fell to the
 * account path, which pays the SAME bucket: a member of a big group was skipped silently once
 * the bucket ran dry. Now a 429 waits what the relay says (`Retry-After`, seconds; sent from
 * ServerPatches/group_fanout_2026_11_24 on) or backs off 1, 2, 4… s when the header is absent
 * (relays before that patch), each wait capped at [MAX_SINGLE_WAIT_MS] and all of them at
 * [MAX_RATE_LIMIT_WAIT_MS]. 5xx / network keep the previous budget exactly.
 *
 * Pure policy + one generic loop, so the rule is unit-tested without a network.
 */
object RateLimitBackoff {
    /** 5xx / network: unchanged — 3 attempts, 1 s then 2 s. */
    const val MAX_TRANSIENT_ATTEMPTS = 3
    const val MAX_SINGLE_WAIT_MS = 30_000L
    const val MAX_RATE_LIMIT_WAIT_MS = 120_000L

    /** `Retry-After` in delta-seconds; an HTTP-date or garbage ⇒ null (use the backoff). */
    fun parseRetryAfterMs(header: String?): Long? =
        header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000L }

    /**
     * Delay before the next attempt, or null to stop.
     * @param rateLimitedSoFar how many 429s this send already got (0 for the first one).
     * @param transientSoFar how many 5xx / network failures this send already got.
     * @param waitedOn429Ms total already waited because of 429s.
     */
    fun nextDelayMs(status: Int, retryAfter: String?, rateLimitedSoFar: Int, transientSoFar: Int, waitedOn429Ms: Long): Long? {
        if (status == 429) {
            val backoff = 1000L shl rateLimitedSoFar.coerceAtMost(5)
            val d = (parseRetryAfterMs(retryAfter) ?: backoff).coerceIn(1000L, MAX_SINGLE_WAIT_MS)
            return if (waitedOn429Ms + d > MAX_RATE_LIMIT_WAIT_MS) null else d
        }
        if (status == -1 || status in 500..599) {
            return if (transientSoFar < MAX_TRANSIENT_ATTEMPTS - 1) 1000L shl transientSoFar else null
        }
        return null
    }

    /** Runs [call] until it is not retryable any more; returns the last answer. */
    suspend fun <A> retrying(
        call: suspend () -> A,
        status: (A) -> Int,
        retryAfter: (A) -> String?,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): A {
        var rateLimited = 0
        var transient = 0
        var waited = 0L
        while (true) {
            val a = call()
            val s = status(a)
            if (s in 200..299) return a
            val d = nextDelayMs(s, retryAfter(a), rateLimited, transient, waited) ?: return a
            if (s == 429) { rateLimited++; waited += d } else transient++
            sleep(d)
        }
    }
}
