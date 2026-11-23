package com.oshi.messenger.data.model

/**
 * How long ago a contact was last seen — the shape, decided without any Android runtime.
 *
 * __LAST_SEEN_ON_THE_CARD_2026_10_28__ (N16, reported by a user) The Last Seen switch in
 * Settings was reachable and did something real — it gates whether we BROADCAST our own
 * presence — but a peer's last connection appeared NOWHERE in the app. Half a feature: you
 * could choose to share it and never see anyone else's.
 *
 * The thresholds are iOS's, read off `ContactPresenceManager.LastSeenText.formatLastSeen`
 * rather than chosen again here, because the two clients showing the same contact different
 * words for the same instant is exactly the kind of divergence nobody reports as a bug and
 * everybody notices.
 *
 * Pure, and returns a CASE rather than a string: a formatter that reached for resources
 * could not be tested without an emulator, and the interesting part — which bucket an
 * instant falls in, and what happens at each boundary — is the part worth testing.
 */
object PresenceFormat {

    sealed class Text {
        /** Never heard from, or the peer does not share it. Not "a long time ago". */
        object Unknown : Text()
        object Now : Text()
        data class MinutesAgo(val minutes: Int) : Text()
        data class HoursAgo(val hours: Int) : Text()
        /** Over a day: an absolute date and time, as iOS switches to. */
        data class At(val epochMillis: Long) : Text()
    }

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS

    /**
     * @param lastSeenMillis Unix millis, or null when nothing is known.
     *
     * **A non-positive value is UNKNOWN, not 1970.** `PeerNickname`'s wire note records
     * that Android once omitted `lastSeen` when the switch was off while iOS wrote 0, and
     * that every reader has always had to treat `<= 0` as absent. Doing it here means one
     * place decides it.
     *
     * A time in the FUTURE is also unknown rather than clamped to "now": it means the two
     * clocks disagree, and inventing "just now" from a skewed clock would state something
     * this client cannot know.
     */
    fun of(lastSeenMillis: Long?, nowMillis: Long): Text {
        val t = lastSeenMillis ?: return Text.Unknown
        if (t <= 0L) return Text.Unknown
        val delta = nowMillis - t
        if (delta < 0L) return Text.Unknown
        return when {
            delta < MINUTE_MS -> Text.Now
            delta < HOUR_MS -> Text.MinutesAgo((delta / MINUTE_MS).toInt())
            delta < DAY_MS -> Text.HoursAgo((delta / HOUR_MS).toInt())
            else -> Text.At(t)
        }
    }
}
