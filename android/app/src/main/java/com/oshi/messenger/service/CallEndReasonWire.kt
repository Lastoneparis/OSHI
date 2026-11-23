package com.oshi.messenger.service

/**
 * The `reason` string carried in the body of a `callEnd` (0x04) packet.
 *
 * ⚠️ WIRE VOCABULARY. This is the one place that decides what Android puts on the
 * wire for an end reason, and it is **iOS's vocabulary, not Kotlin's**.
 *
 * iOS declares `enum CallEndReason: String` with snake_case raw values
 * (`OSHI/VoiceCallManager.swift:1828-1836`) and parses the body with
 * `CallEndReason(rawValue: reasonString)` at `:5313-5319` and `:6992-6997` —
 * an unrecognised string silently falls back to `.hungUp`.
 *
 * Android used to send `reason.name`, i.e. the Kotlin enum name (`HUNG_UP`,
 * `NO_ANSWER`, `CONNECTION_LOST`, …). **Not one of those strings is an iOS raw
 * value**, so every callEnd Android has ever sent was read on the iPhone as
 * `.hungUp`. What the user saw: an Android caller giving up on a ringing iPhone
 * produced `CXCallEndedReason.remoteEnded` instead of `.unanswered`
 * (`VoiceCallManager.swift:8052-8067`), so the call left **no missed-call row in
 * the iPhone's system Recents**, and `displayText` (`:1838-1847`) said
 * "Call ended" where it should have said "Connection lost".
 *
 * Nothing on Android reads this field — `handleCallEnded()` ends with
 * `PEER_ENDED` regardless — so no Android build, old or new, changes behaviour.
 * The change is receive-side-visible on iOS only, and only in the direction of
 * being correct.
 */
object CallEndReasonWire {

    /**
     * iOS's exact raw values. Kept as a set so the test can assert that every
     * mapping lands inside iOS's vocabulary rather than trusting the `when`.
     * `OSHI/VoiceCallManager.swift:1829-1835`.
     */
    val IOS_RAW_VALUES: Set<String> = setOf(
        "hung_up",
        "declined",
        "no_answer",
        "network_error",
        "peer_disconnected",
        "connection_lost",
        "answered_elsewhere"
    )

    /**
     * Android reason → iOS raw value.
     *
     * `PEER_ENDED` and `MISSED` have no iOS counterpart: iOS never had to name
     * them because the side that *received* the end never re-sends one. They map
     * onto the iOS reason that produces the same CallKit outcome — `hung_up` for
     * "the other end finished the call", `no_answer` for "nobody picked up".
     */
    fun toWire(reason: CallEndReason): String = when (reason) {
        CallEndReason.HUNG_UP -> "hung_up"
        CallEndReason.PEER_ENDED -> "hung_up"
        CallEndReason.DECLINED -> "declined"
        CallEndReason.NO_ANSWER -> "no_answer"
        CallEndReason.MISSED -> "no_answer"
        CallEndReason.ERROR -> "network_error"
        CallEndReason.CONNECTION_LOST -> "connection_lost"
        CallEndReason.ANSWERED_ELSEWHERE -> "answered_elsewhere"
    }
}
