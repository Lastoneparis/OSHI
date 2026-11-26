package com.oshi.messenger.network.v2

/**
 * __BLOB_RANGE_2026_11_19__ Ranged chunk reads for networks that freeze a TCP connection
 * after ~16 KB server->client (Russia TSPU). Wire spec shared with iOS / desktop / the relay:
 * docs/CENSORSHIP_2026_THREAT_MODEL.md §7.
 */
object BlobRange {
    /**
     * Cover ON: the 206 cover record (3 short headers + base64 of 2688 B = 3584 chars) stays
     * under 4092 B, i.e. in the 4 KiB padding bucket (~5.5 KB on the wire). One byte more of
     * part can push the reply into the 16 KiB bucket (~22 KB on the wire) and freeze.
     */
    const val COVER_PART = 2688
    /** Cover OFF: raw octets; with a TLS handshake one part per connection stays < ~13 KB. */
    const val PLAIN_PART = 12288
    /** A 2 MiB + 16 B chunk in 2688-byte parts = 781 parts; never loop forever on a bad server. */
    const val MAX_PARTS = 1024

    /** Attempts per part before the chunk fails (progress is kept between attempts). */
    const val PART_ATTEMPTS = 5
    const val PART_RETRY_MS = 400L

    fun partLength(coverOn: Boolean): Int = if (coverOn) COVER_PART else PLAIN_PART

    sealed class Result {
        object Whole : Result()
        data class Part(val nextOffset: Long, val complete: Boolean) : Result()
        object Fail : Result()
    }

    private val RANGE = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+)\s*$""", RegexOption.IGNORE_CASE)

    /** Judge one reply to `?offset=[requested]`. */
    fun accept(code: Int, contentRange: String?, requested: Long, bodyBytes: Int): Result {
        if (code == 200) return Result.Whole
        if (code != 206 || contentRange == null) return Result.Fail
        val m = RANGE.matchEntire(contentRange) ?: return Result.Fail
        val start = m.groupValues[1].toLongOrNull() ?: return Result.Fail
        val end = m.groupValues[2].toLongOrNull() ?: return Result.Fail
        val total = m.groupValues[3].toLongOrNull() ?: return Result.Fail
        if (start != requested || end < start || end >= total || end - start + 1 != bodyBytes.toLong()) return Result.Fail
        return Result.Part(end + 1, end + 1 == total)
    }
}
