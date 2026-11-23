package com.oshi.messenger

import com.oshi.messenger.data.model.OSHIControlPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The answered-call summary: its format, and the predicate that decides the fate of
 * a copy arriving from the peer.
 *
 * Reference: `OSHIControlPayload.swift:130` (`isAnsweredCallSummary`), `:143-148`
 * (`answeredCallSummary`), `MessageManager.swift:5810-5813` (the ingestion drop).
 *
 * What was broken: both ends emitted the summary AND transmitted it, with no
 * ingestion filter and no direction flip on read — so an Android↔Android call left
 * two bubbles per side, and the one that came from the peer showed the PEER's
 * direction ("outgoing call" on a call you received).
 */
class CallSummaryIngestionTest {

    @Test
    fun `answered summary is byte for byte the Swift format`() {
        assertEquals(
            "📱CALL_SUMMARY📱📞↗️call.outgoing|2:10",
            OSHIControlPayload.answeredCallSummary(wasOutgoing = true, duration = "2:10")
        )
        assertEquals(
            "📱CALL_SUMMARY📱📞↙️call.incoming|0:07",
            OSHIControlPayload.answeredCallSummary(wasOutgoing = false, duration = "0:07")
        )
    }

    /**
     * An answered call is observed by BOTH sides, so the peer's copy is a duplicate
     * and gets dropped at ingestion.
     */
    @Test
    fun `an answered summary is recognised in both directions`() {
        assertTrue(OSHIControlPayload.isAnsweredCallSummary(
            OSHIControlPayload.answeredCallSummary(wasOutgoing = true, duration = "2:10")))
        assertTrue(OSHIControlPayload.isAnsweredCallSummary(
            OSHIControlPayload.answeredCallSummary(wasOutgoing = false, duration = "2:10")))
    }

    /**
     * The `|` is the discriminant, on purpose. A missed / declined / unanswered call
     * may only ever have been seen by the caller, so its message is the sole trace
     * that the call happened and MUST pass ingestion. Those carry no duration.
     */
    @Test
    fun `calls that were never answered must pass ingestion`() {
        assertFalse(OSHIControlPayload.isAnsweredCallSummary("📱CALL_SUMMARY📱📞↗️call.no.answer"))
        assertFalse(OSHIControlPayload.isAnsweredCallSummary("📱CALL_SUMMARY📱📞↗️call.declined"))
        assertFalse(OSHIControlPayload.isAnsweredCallSummary("📱CALL_SUMMARY📱📞↙️call.declined"))
        assertFalse(OSHIControlPayload.isAnsweredCallSummary("📱MISSED_CALL📱❌call.missed"))
    }

    @Test
    fun `ordinary prose is never mistaken for a summary`() {
        assertFalse(OSHIControlPayload.isAnsweredCallSummary("call me back | later"))
        assertFalse(OSHIControlPayload.isAnsweredCallSummary(""))
    }

    /**
     * Direction is the WRITING device's own — that is the whole reason the summary is
     * a local record rather than a message. The two directions must not collide, or
     * dropping the peer's copy would lose information instead of a duplicate.
     */
    @Test
    fun `the two directions are distinguishable`() {
        val out = OSHIControlPayload.answeredCallSummary(wasOutgoing = true, duration = "1:00")
        val inc = OSHIControlPayload.answeredCallSummary(wasOutgoing = false, duration = "1:00")
        assertTrue(out.contains("call.outgoing"))
        assertTrue(inc.contains("call.incoming"))
        assertFalse(out == inc)
    }
}
