package com.oshi.messenger.service

import com.oshi.messenger.data.model.OSHIControlPayload

/**
 * Which summary a finished call leaves in the thread.
 *
 * The rule is iOS's, and the important half of it is that **duration decides the
 * shape, not the end reason**:
 *
 * - `VoiceCallManager.swift:8216` — `if wasInCall && duration > 0` → post `CallEnded`,
 *   which `MessageManager.handleCallEndedWriteSummary` (`:5622-5681`) turns into the
 *   answered form `📱CALL_SUMMARY📱📞↗️call.outgoing|M:SS`.
 * - `VoiceCallManager.swift:8241` — `else if wasConnecting || wasRinging ||
 *   (wasInCall && duration == 0)` → post `MissedCall`, and only THAT branch looks at
 *   the reason at all (`ChatView.swift:2477-2500`).
 *
 * Android used to key the whole thing on the reason, so a three-minute conversation
 * that ended on CONNECTION_LOST or ERROR was filed as `📞↗️call.no.answer` — "no
 * answer" for a call that had been answered — with the ↗️ outgoing arrow hardcoded,
 * so the person who *received* it saw it recorded as one they had placed.
 *
 * Pure and separate from [EnhancedCallManager] so it can be tested without a device.
 */
object CallSummaryFormat {

    /** `null` when no summary should be written at all. */
    fun summaryFor(
        reason: CallEndReason,
        isOutgoing: Boolean,
        durationSeconds: Long
    ): String? {
        // 📱 Answered on another device of ours: THAT device writes the summary.
        // iOS emits nothing here either (ChatView.swift:2482-2499 has only
        // no.answer / declined / missed).
        if (reason == CallEndReason.ANSWERED_ELSEWHERE) return null

        if (durationSeconds > 0) {
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            return OSHIControlPayload.answeredCallSummary(
                wasOutgoing = isOutgoing,
                duration = "$minutes:${"%02d".format(seconds)}"
            )
        }

        // Never answered. iOS ChatView.swift:2477-2500, arm for arm.
        return when (reason) {
            CallEndReason.DECLINED ->
                if (isOutgoing) "📱CALL_SUMMARY📱📞↗️call.declined"
                else "📱CALL_SUMMARY📱📞↙️call.declined"
            // no_answer / hung_up / network_error / connection_lost all land on iOS's
            // `default:` arm: "no answer" for the caller, "missed" for the callee.
            else ->
                if (isOutgoing) "📱CALL_SUMMARY📱📞↗️call.no.answer"
                else "📱MISSED_CALL📱❌call.missed"
        }
    }
}
