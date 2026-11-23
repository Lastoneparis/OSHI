package com.oshi.messenger.service

/**
 * What the call-ended overlay says, as a platform-free token.
 *
 * Deliberately **not** a string-resource id: the mapping below is the parity
 * decision (which Android reasons collapse onto which iOS sentence) and it has to
 * be provable on the JVM, where `R` is a generated stub. The screens turn a label
 * into a resource; this file decides which label.
 *
 * iOS's sentences come from `CallEndReason.displayText`,
 * `OSHI/VoiceCallManager.swift:1838-1847`:
 *
 * | iOS raw value        | iOS displayText              |
 * |----------------------|------------------------------|
 * | `hung_up`            | Call ended                   |
 * | `declined`           | Call declined                |
 * | `no_answer`          | No answer                    |
 * | `network_error`      | Network error                |
 * | `peer_disconnected`  | Connection lost              |
 * | `connection_lost`    | Connection lost              |
 * | `answered_elsewhere` | Answered on another device   |
 *
 * Note `peer_disconnected` and `connection_lost` share a sentence on iOS — the
 * user does not care which of the two mechanisms noticed.
 */
enum class CallEndLabel {
    /** "Call ended" — a normal hang-up on either end. */
    ENDED,
    /** "Call declined". */
    DECLINED,
    /** "No answer". */
    NO_ANSWER,
    /** "Network error" — the call never came up. */
    NETWORK_ERROR,
    /** "Connection lost" — it came up and then died. */
    CONNECTION_LOST,
    /** "Answered on another device". */
    ANSWERED_ELSEWHERE
}

/**
 * Android [CallEndReason] → the sentence iOS would have shown.
 *
 * Until this existed, **every** way a call could finish reached the screen as the
 * same bare `CallState.ENDED` and the call screen simply vanished. A call that
 * never connected because the network was down, a call the peer declined, a call
 * that dropped after three minutes and a normal goodbye were indistinguishable —
 * iOS names all four (`VoiceCallView.swift:699-725`, `VideoCallView.swift:121-133`).
 */
object CallEndReasonDisplay {

    /**
     * `null` — no reason recorded — reads as a plain "Call ended", which is what
     * iOS's own `lastCallEndReason == nil` fallback shows (`VoiceCallView.swift:710`).
     */
    fun labelFor(reason: CallEndReason?): CallEndLabel = when (reason) {
        null -> CallEndLabel.ENDED
        CallEndReason.HUNG_UP -> CallEndLabel.ENDED
        // The PEER hung up. iOS receives that as `hung_up` and says "Call ended";
        // there is no separate sentence for "they hung up on you".
        CallEndReason.PEER_ENDED -> CallEndLabel.ENDED
        CallEndReason.DECLINED -> CallEndLabel.DECLINED
        CallEndReason.NO_ANSWER -> CallEndLabel.NO_ANSWER
        // Callee's-eye view of the same event as NO_ANSWER; `CallEndReasonWire`
        // already puts both on the wire as iOS's `no_answer`.
        CallEndReason.MISSED -> CallEndLabel.NO_ANSWER
        CallEndReason.ERROR -> CallEndLabel.NETWORK_ERROR
        CallEndReason.CONNECTION_LOST -> CallEndLabel.CONNECTION_LOST
        CallEndReason.ANSWERED_ELSEWHERE -> CallEndLabel.ANSWERED_ELSEWHERE
    }

    /**
     * The iOS sentence each label stands for, keyed by the iOS raw value that
     * produces it. Used by the test to cross-check [labelFor] against
     * [CallEndReasonWire.toWire] — two mappings written independently from the same
     * Swift source, which is the only way either one can be shown to be right
     * without a device.
     */
    val LABEL_FOR_IOS_RAW_VALUE: Map<String, CallEndLabel> = mapOf(
        "hung_up" to CallEndLabel.ENDED,
        "declined" to CallEndLabel.DECLINED,
        "no_answer" to CallEndLabel.NO_ANSWER,
        "network_error" to CallEndLabel.NETWORK_ERROR,
        "peer_disconnected" to CallEndLabel.CONNECTION_LOST,
        "connection_lost" to CallEndLabel.CONNECTION_LOST,
        "answered_elsewhere" to CallEndLabel.ANSWERED_ELSEWHERE
    )
}
