package com.oshi.messenger

import com.oshi.messenger.service.CallEndReason
import com.oshi.messenger.service.CallSummaryFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which summary a finished call leaves in the thread.
 *
 * What was broken: Android keyed the whole decision on the END REASON. iOS keys it on
 * the DURATION — `VoiceCallManager.swift:8216` `if wasInCall && duration > 0` writes
 * the answered form, `:8241` writes the missed form, and only the second branch looks
 * at the reason at all (`ChatView.swift:2477-2500`).
 *
 * So a three-minute conversation that ended on a network drop was logged as
 * `📞↗️call.no.answer` — "no answer" for a call that had been answered — and the ↗️
 * outgoing arrow was hardcoded, so the person who RECEIVED it saw it filed as one they
 * had placed.
 */
class CallSummaryFormatTest {

    private fun summary(reason: CallEndReason, outgoing: Boolean, seconds: Long) =
        CallSummaryFormat.summaryFor(reason, outgoing, seconds)

    // ── The regression this file exists for ────────────────────────────────

    @Test
    fun `a connected call that DROPS is an answered call, with its real duration`() {
        assertEquals(
            "📱CALL_SUMMARY📱📞↙️call.incoming|3:07",
            summary(CallEndReason.CONNECTION_LOST, outgoing = false, seconds = 187)
        )
        assertEquals(
            "📱CALL_SUMMARY📱📞↗️call.outgoing|3:07",
            summary(CallEndReason.CONNECTION_LOST, outgoing = true, seconds = 187)
        )
    }

    @Test
    fun `a dropped INCOMING call is never labelled outgoing`() {
        // The old code hardcoded ↗️ for ERROR and CONNECTION_LOST regardless of
        // direction, so the receiver saw a call they had answered filed as one they
        // had placed.
        for (reason in listOf(CallEndReason.ERROR, CallEndReason.CONNECTION_LOST)) {
            val s = summary(reason, outgoing = false, seconds = 42)
            assertNotNull(s)
            assertEquals(
                "$reason on an INCOMING call rendered the outgoing arrow: $s",
                false,
                s!!.contains("↗️")
            )
        }
    }

    @Test
    fun `every reason with a duration produces the answered form`() {
        for (reason in CallEndReason.entries) {
            if (reason == CallEndReason.ANSWERED_ELSEWHERE) continue
            val s = summary(reason, outgoing = true, seconds = 65)
            assertEquals(
                "$reason with duration>0 must be the answered form",
                "📱CALL_SUMMARY📱📞↗️call.outgoing|1:05",
                s
            )
        }
    }

    // ── The never-answered branch: iOS ChatView.swift:2477-2500, arm for arm ──

    @Test
    fun `unanswered outgoing is no answer, unanswered incoming is missed`() {
        assertEquals(
            "📱CALL_SUMMARY📱📞↗️call.no.answer",
            summary(CallEndReason.NO_ANSWER, outgoing = true, seconds = 0)
        )
        assertEquals(
            "📱MISSED_CALL📱❌call.missed",
            summary(CallEndReason.NO_ANSWER, outgoing = false, seconds = 0)
        )
    }

    @Test
    fun `a peer that hangs up before we answer is a MISSED call, not a hangup`() {
        // iOS: `case "hung_up": content = "📱MISSED_CALL📱❌call.missed"` for !wasOutgoing.
        assertEquals(
            "📱MISSED_CALL📱❌call.missed",
            summary(CallEndReason.PEER_ENDED, outgoing = false, seconds = 0)
        )
        assertEquals(
            "📱MISSED_CALL📱❌call.missed",
            summary(CallEndReason.MISSED, outgoing = false, seconds = 0)
        )
    }

    @Test
    fun `decline keeps its own key and its own direction on both sides`() {
        assertEquals(
            "📱CALL_SUMMARY📱📞↗️call.declined",
            summary(CallEndReason.DECLINED, outgoing = true, seconds = 0)
        )
        assertEquals(
            "📱CALL_SUMMARY📱📞↙️call.declined",
            summary(CallEndReason.DECLINED, outgoing = false, seconds = 0)
        )
    }

    // ── Answered elsewhere writes nothing at all ──────────────────────────

    @Test
    fun `answered elsewhere writes nothing — the device that answered writes it`() {
        assertNull(summary(CallEndReason.ANSWERED_ELSEWHERE, outgoing = false, seconds = 0))
        assertNull(summary(CallEndReason.ANSWERED_ELSEWHERE, outgoing = false, seconds = 120))
        assertNull(summary(CallEndReason.ANSWERED_ELSEWHERE, outgoing = true, seconds = 120))
    }

    // ── Duration formatting ───────────────────────────────────────────────

    @Test
    fun `seconds are zero padded to two digits, minutes are not`() {
        assertEquals("📱CALL_SUMMARY📱📞↗️call.outgoing|0:01", summary(CallEndReason.HUNG_UP, true, 1))
        assertEquals("📱CALL_SUMMARY📱📞↗️call.outgoing|0:59", summary(CallEndReason.HUNG_UP, true, 59))
        assertEquals("📱CALL_SUMMARY📱📞↗️call.outgoing|1:00", summary(CallEndReason.HUNG_UP, true, 60))
        assertEquals("📱CALL_SUMMARY📱📞↗️call.outgoing|10:00", summary(CallEndReason.HUNG_UP, true, 600))
    }

    @Test
    fun `every never-answered summary is one the peer will not swallow at ingestion`() {
        // The ingestion filter drops an ANSWERED summary from the peer, keyed on the
        // `|` before the duration (OSHIControlPayload.isAnsweredCallSummary,
        // MessageManager.swift:5810-5813). A missed / declined / no-answer message may
        // be the ONLY trace of the call, so it must not carry that discriminant.
        for (reason in CallEndReason.entries) {
            for (outgoing in listOf(true, false)) {
                val s = summary(reason, outgoing, seconds = 0) ?: continue
                assertEquals(
                    "$reason/outgoing=$outgoing produced a never-answered summary that " +
                        "the peer's ingestion filter would drop: $s",
                    false,
                    s.contains("|")
                )
            }
        }
    }
}
