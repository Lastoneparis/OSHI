package com.oshi.messenger.service

/**
 * When an overdue check-in may escalate to an emergency alert — and when it may not.
 *
 * ══ WHY THIS IS A SEPARATE, PURE OBJECT ══
 *
 * Escalation is the one branch in this feature that reaches a **third party**: it
 * ships the user's last position, battery level and device name to their contact,
 * with an "Emergency" banner attached. Getting it wrong is expensive in both
 * directions and the two harms are not symmetric in the same way at every moment:
 *
 *   * **firing when it should not** — a false emergency to someone's contact,
 *     which in the pilot means 23 testers' friends and family being told they are
 *     in danger because a bus was late and a phone was in a pocket;
 *   * **never firing** — the case the whole feature exists for.
 *
 * A decision that consequential must be assertable without an Android device, so it
 * lives here as a pure function over a clock reading and two timestamps.
 *
 * ══ THE POLICY, AND WHY ══
 *
 * 1. **Before the deadline: [Decision.Wait].** Nothing to say.
 *
 * 2. **Past the deadline with no warning yet DELIVERED: [Decision.Warn], always,
 *    however late.** This is the branch that runs after a process kill, after a
 *    reboot, after battery-saver froze the app for three hours. The naive
 *    alternative — "the deadline passed 3 h ago, therefore also the 15-minute
 *    response window, therefore emergency" — would send a real alert to a real
 *    contact for someone who was never asked anything. We ask first. **Always.**
 *    The cost of this choice is that a genuine emergency is announced to the
 *    contact 15 minutes later than it might have been; the `warning` message the
 *    contact receives at this same moment ("hasn't arrived yet") means they are
 *    not in the dark during those 15 minutes.
 *
 * 3. **Warning delivered, window not yet elapsed: [Decision.Wait].**
 *
 * 4. **Warning delivered, window elapsed: [Decision.Escalate].** Note the word
 *    *delivered*. `warningDeliveredAtMs` is stamped only when
 *    `NotificationManagerCompat.notify` was actually reached — not when we merely
 *    *decided* to warn. The previous code stamped it unconditionally, so on a phone
 *    where `POST_NOTIFICATIONS` was denied the user got **no prompt at all** and an
 *    emergency alert exactly 15 minutes later. That is the precise shape of the
 *    false-emergency bug this pilot cannot afford.
 *
 *    A process kill *between* delivery and escalation does **not** make this
 *    ambiguous, and that is deliberate: on Android the warning notification lives in
 *    the system tray, owned by the system, and survives the app's death. The user
 *    had a persistent, tappable "I'm safe" the entire time — see
 *    [CheckInActionReceiver], which answers it from a cold process.
 *
 * 5. **Clock moved backwards ([Decision.ClockUntrusted]).** If `now` is before the
 *    moment we recorded delivering the warning, the wall clock has been changed
 *    (manual change, network time correction, dual-boot). The elapsed-time
 *    arithmetic on which escalation rests is then meaningless, so we refuse to use
 *    it: the caller clears the stamp and re-warns, which restarts the window. Worst
 *    case that costs one extra 15-minute cycle; the alternative is an emergency
 *    derived from a subtraction we know to be wrong.
 *
 * There is no branch that escalates without a delivered warning. That is the
 * invariant `CheckInEscalationPolicyTest` asserts over the whole input space.
 */
object CheckInEscalationPolicy {

    /** Grace between the warning actually reaching the user and the emergency alert. */
    const val EMERGENCY_AFTER_WARNING_MS = 15 * 60 * 1000L

    sealed interface Decision {
        /** Deadline not reached, or the response window is still open. */
        object Wait : Decision

        /** Post (or re-post) the "Are you okay?" prompt and tell the contact. */
        object Warn : Decision

        /** The user was asked, had the full window, and did not answer. */
        object Escalate : Decision

        /** The wall clock is not trustworthy — re-warn instead of escalating. */
        object ClockUntrusted : Decision
    }

    /**
     * @param nowMs wall clock now.
     * @param deadlineMs the session's effective deadline (ETA + extra time).
     * @param warningDeliveredAtMs when the warning notification was **actually
     *   delivered**, or null if it never was (never merely "when we tried").
     */
    fun decide(
        nowMs: Long,
        deadlineMs: Long,
        warningDeliveredAtMs: Long?,
        emergencyAfterWarningMs: Long = EMERGENCY_AFTER_WARNING_MS,
    ): Decision {
        if (nowMs <= deadlineMs) return Decision.Wait
        if (warningDeliveredAtMs == null) return Decision.Warn
        if (nowMs < warningDeliveredAtMs) return Decision.ClockUntrusted
        return if (nowMs - warningDeliveredAtMs >= emergencyAfterWarningMs) {
            Decision.Escalate
        } else {
            Decision.Wait
        }
    }
}
