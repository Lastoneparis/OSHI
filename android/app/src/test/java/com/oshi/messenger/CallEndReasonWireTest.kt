package com.oshi.messenger

import com.oshi.messenger.service.CallEndReason
import com.oshi.messenger.service.CallEndReasonWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `reason` string in the body of a callEnd (0x04) packet.
 *
 * What was broken: Android sent `reason.name` — the Kotlin enum name (`HUNG_UP`,
 * `NO_ANSWER`, `CONNECTION_LOST`, …). iOS parses this field with
 * `CallEndReason(rawValue:)` (`VoiceCallManager.swift:5313-5319`, `:6992-6997`) and
 * falls back to `.hungUp` on anything it does not recognise — and it recognised none
 * of them. So an Android caller giving up on a ringing iPhone produced
 * `CXCallEndedReason.remoteEnded` instead of `.unanswered`
 * (`VoiceCallManager.swift:8052-8067`) and left NO missed-call row in the iPhone's
 * system Recents.
 *
 * The iOS raw values are quoted from `VoiceCallManager.swift:1829-1835`.
 */
class CallEndReasonWireTest {

    /**
     * The point of the whole file. Independently transcribed from the Swift enum, NOT
     * read from [CallEndReasonWire.IOS_RAW_VALUES] — a test that builds its expectation
     * out of the thing under test proves nothing.
     */
    private val swiftRawValues = setOf(
        "hung_up",
        "declined",
        "no_answer",
        "network_error",
        "peer_disconnected",
        "connection_lost",
        "answered_elsewhere"
    )

    @Test
    fun `every Android reason maps to a string iOS can actually parse`() {
        for (reason in CallEndReason.entries) {
            val wire = CallEndReasonWire.toWire(reason)
            assertTrue(
                "CallEndReason.$reason maps to \"$wire\", which is not an iOS raw value " +
                    "— iOS would silently read it as .hungUp",
                wire in swiftRawValues
            )
        }
    }

    @Test
    fun `the constant in the source agrees with the Swift enum`() {
        assertEquals(swiftRawValues, CallEndReasonWire.IOS_RAW_VALUES)
    }

    @Test
    fun `the reasons that change what the iPhone records`() {
        // .unanswered on CallKit — this is the one that puts a missed call in Recents.
        assertEquals("no_answer", CallEndReasonWire.toWire(CallEndReason.NO_ANSWER))
        // .failed on CallKit.
        assertEquals("connection_lost", CallEndReasonWire.toWire(CallEndReason.CONNECTION_LOST))
        assertEquals("network_error", CallEndReasonWire.toWire(CallEndReason.ERROR))
        // .declinedElsewhere on CallKit.
        assertEquals("declined", CallEndReasonWire.toWire(CallEndReason.DECLINED))
        assertEquals("answered_elsewhere", CallEndReasonWire.toWire(CallEndReason.ANSWERED_ELSEWHERE))
    }

    @Test
    fun `the two reasons iOS never had to name land on the matching outcome`() {
        // "the other end finished the call"
        assertEquals("hung_up", CallEndReasonWire.toWire(CallEndReason.HUNG_UP))
        assertEquals("hung_up", CallEndReasonWire.toWire(CallEndReason.PEER_ENDED))
        // "nobody picked up" — the callee's-eye view of iOS's .noAnswer, which is what
        // the 55 s callee ring watchdog ends with.
        assertEquals("no_answer", CallEndReasonWire.toWire(CallEndReason.MISSED))
    }

    @Test
    fun `no reason leaks a Kotlin enum name onto the wire`() {
        for (reason in CallEndReason.entries) {
            val wire = CallEndReasonWire.toWire(reason)
            assertTrue(
                "CallEndReason.$reason still puts its own name on the wire: \"$wire\"",
                wire != reason.name
            )
            assertEquals(
                "iOS raw values are lower snake_case; \"$wire\" is not",
                wire.lowercase(),
                wire
            )
        }
    }
}
