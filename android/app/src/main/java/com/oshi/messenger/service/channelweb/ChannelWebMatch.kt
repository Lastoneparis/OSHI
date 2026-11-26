package com.oshi.messenger.service.channelweb

import com.oshi.messenger.network.v2.V2ChannelWebClient

/**
 * __CHANNEL_WEB_2026_11_26__ How a READER's app learns the public number `n` of a channel post.
 *
 * The admin who published a post knows its `n` (the publish answer, spec §3.4), and any
 * controller can fetch the whole `postId → n` map (§3.6). A reader can do neither: the
 * public feed (§4) never exposes `postId`. Options considered:
 *  - carry `n` in the E2E post: impossible, `n` is assigned by the server AFTER the post left;
 *  - send it in a follow-up E2E message: a new wire type every shipped build would have to
 *    tolerate, and one more message per post;
 *  - match the public feed against the local thread: chosen (same rule as iOS `ChannelWeb.swift`).
 *    The feed carries `createdAt`, which IS the post's send time (the timestamp the E2E message
 *    carries, ms). A post matches the local message sent within [EXACT_MS] of it (platforms round
 *    differently); failing that, one within [TEXT_WINDOW_MS] whose converted text is the web text.
 *
 * Nothing new crosses the wire, an iOS admin's posts resolve the same way, and a post that
 * was never published (sent before the preview was on) simply has no match. The feed is
 * fetched only when the reader taps a web action: reading a channel never contacts the server.
 */
object ChannelWebMatch {
    const val EXACT_MS = 1L
    const val TEXT_WINDOW_MS = 2_000L

    /** A local channel post as the matcher needs it. */
    data class Local(val id: String, val timestamp: Long, val rawText: String)

    /** messageId → n for every local post that has a public twin in [web]. */
    fun match(local: List<Local>, web: List<V2ChannelWebClient.Companion.PublicPost>): Map<String, Int> {
        val out = HashMap<String, Int>()
        val taken = HashSet<String>()
        for (p in web.sortedByDescending { it.n }) {
            val free = local.filter { it.id !in taken }
            val pick = free.filter { kotlin.math.abs(it.timestamp - p.createdAt) <= EXACT_MS }
                .minByOrNull { kotlin.math.abs(it.timestamp - p.createdAt) }
                ?: free.filter { kotlin.math.abs(it.timestamp - p.createdAt) <= TEXT_WINDOW_MS &&
                    ChannelWebText.forPublish(it.rawText) == p.text }
                    .minByOrNull { kotlin.math.abs(it.timestamp - p.createdAt) }
                ?: continue
            out[pick.id] = p.n
            taken += pick.id
        }
        return out
    }
}
