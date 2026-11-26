package com.oshi.messenger.data.model

import org.json.JSONObject

/**
 * __OFFLINE_ONLY_CONVERSATION_2026_10_23__ "This conversation never touches the
 * internet."
 *
 * A per-conversation switch — one contact, or one group — that takes the relay
 * away from everything OSHI sends there. What is left is the local mesh
 * (Bluetooth / Wi-Fi between nearby phones) and the LoRa radio.
 *
 * ## What this can and cannot promise
 *
 * It can promise one thing completely: nothing THIS device sends in that
 * conversation goes over the relay. That is local, enforced on the send path,
 * and it holds whatever anyone else does.
 *
 * It cannot promise the same of the others. There is no way to make another
 * phone refuse the internet — a peer on an older build does not know the
 * request exists. So the switch is enforced locally and REQUESTED of the rest,
 * and the UI says how many confirmed. A number smaller than the member count is
 * the honest state of the conversation, not a bug to hide: claiming "everyone
 * is offline-only" while a silent member still relays would be a privacy
 * promise the app cannot keep.
 *
 * ## Wire
 *
 * `🔒OFFLINE_ONLY🔒{"v":1,"on":true,"at":1761220000}` — declared once in
 * [OSHIControlPayload.catalog]. Timestamps are explicit Unix SECONDS, because
 * Apple's own `Date` encoding is seconds since 2001 and this JSON crosses
 * platforms.
 *
 * iOS: `OSHI/OfflineOnlyConversation.swift`.
 */
data class OfflineOnlyRequest(
    val version: Int = CURRENT_VERSION,
    /**
     * Turning it on, or back off. Both directions travel: a conversation that
     * silently stayed restricted after someone lifted it would strand messages.
     */
    val on: Boolean,
    val atUnixSeconds: Double,
    /**
     * An ANSWER to someone else's request, not a request of our own.
     *
     * Without this the two sides ping-pong for ever: an echo carrying a fresh
     * timestamp reads as a new request, which clears the confirmations, which
     * asks everyone again. An ack is never echoed, and it carries the `at` of
     * the request it answers so a stale one cannot be counted for a later
     * change.
     */
    val isAck: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("v", version)
        .put("on", on)
        .put("at", atUnixSeconds)
        .put("ack", isAck)
        .toString()

    /** The answer to send back for this request. */
    fun acknowledgement(): OfflineOnlyRequest = copy(isAck = true)

    fun toMessageString(): String = OSHIControlPayload.OFFLINE_ONLY + toJson()

    companion object {
        const val CURRENT_VERSION = 1

        /** __OFFLINE_ONLY_FUTURE_CLAMP_2026_11_20__ how far ahead of our clock a peer's `at` may be. */
        const val MAX_FUTURE_SKEW_SECONDS = 300.0

        /**
         * 🔒 __OFFLINE_ONLY_FUTURE_CLAMP_2026_11_20__ The `at` of an incoming request is the
         * PEER's clock and decides which change wins (newest). Unbounded, a contact could
         * send `on:true, at:<year 3000>` and no later change — theirs or ours — would ever
         * be newer: the conversation stays off the relay for good. Clamp to now + 5 min.
         */
        fun clampedAt(atUnixSeconds: Double, nowUnixSeconds: Double): Double =
            if (atUnixSeconds.isNaN()) 0.0 else minOf(atUnixSeconds, nowUnixSeconds + MAX_FUTURE_SKEW_SECONDS)

        fun create(on: Boolean, atMillis: Long = System.currentTimeMillis()) =
            OfflineOnlyRequest(on = on, atUnixSeconds = atMillis / 1000.0)

        /**
         * Does this text carry the offline-only sentinel at all? True for a
         * request AND for an answer — telling those apart is
         * [OfflineOnlyRequest.isAck], after parsing. Mirrors Swift's
         * `OfflineOnlyRequest.isRequest`.
         */
        fun isRequest(text: String): Boolean =
            OSHIControlPayload.body(text, OSHIControlPayload.OFFLINE_ONLY) != null

        fun fromMessageString(text: String): OfflineOnlyRequest? {
            val body = OSHIControlPayload.body(text, OSHIControlPayload.OFFLINE_ONLY) ?: return null
            return try {
                val o = JSONObject(body)
                val v = o.optInt("v", 0)
                if (v <= 0 || v > CURRENT_VERSION) return null
                OfflineOnlyRequest(
                    version = v,
                    // Same restrictive default as the summary: an unreadable
                    // flag must not read as "the internet is allowed again".
                    on = o.optBoolean("on", true),
                    atUnixSeconds = o.optDouble("at", 0.0),
                    // Absent on a plain request, and on every payload written
                    // before acks existed. Absent means "this is a request",
                    // which is the safe reading: at worst one confirmation goes
                    // uncounted.
                    isAck = o.optBoolean("ack", false),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Whether the relay may carry something, for one conversation.
 *
 * Pure on purpose: the send path consults this from several places, and a rule
 * that needs a store, a network and a group manager to answer is a rule nobody
 * can test. Mirrors `OfflineOnlyPolicy` in Swift.
 */
object OfflineOnlyPolicy {

    /**
     * The app-wide switch and the per-conversation one compose the only way
     * they can: either saying no means no. A per-conversation switch may
     * RESTRICT, never widen — so turning one conversation back to normal cannot
     * quietly re-enable the internet for someone who turned it off everywhere.
     */
    fun internetAllowed(globalInternetAllowed: Boolean, conversationIsOfflineOnly: Boolean): Boolean =
        globalInternetAllowed && !conversationIsOfflineOnly

    /**
     * What the UI shows under the switch for a group. [confirmed] counts OTHER
     * members who echoed the request; we are never in it, because our own
     * compliance is not in question.
     */
    fun confirmationSummary(confirmed: Int, otherMemberCount: Int): Pair<Int, Int> {
        val total = maxOf(0, otherMemberCount)
        return Pair(maxOf(0, minOf(confirmed, total)), total)
    }
}
