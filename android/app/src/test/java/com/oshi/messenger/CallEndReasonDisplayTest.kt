package com.oshi.messenger

import com.oshi.messenger.service.CallEndLabel
import com.oshi.messenger.service.CallEndReason
import com.oshi.messenger.service.CallEndReasonDisplay
import com.oshi.messenger.service.CallEndReasonWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Which sentence a finished call leaves on the screen.
 *
 * Reference: iOS `CallEndReason.displayText`, `OSHI/VoiceCallManager.swift:1838-1847`,
 * rendered at `VoiceCallView.swift:699-725` and `VideoCallView.swift:121-133`.
 *
 * What was broken: `CallState.ENDED` on Android was a bare token with no reason
 * attached, so a call that failed on a dead network, a call the peer declined, a
 * call that dropped after three minutes and a normal goodbye all reached the screen
 * as the same thing — and the screen simply vanished without saying any of it.
 */
class CallEndReasonDisplayTest {

    /**
     * The load-bearing test. [CallEndReasonWire.toWire] and
     * [CallEndReasonDisplay.labelFor] are two independent mappings written from the
     * same Swift enum: one says what Android puts on the WIRE for a reason, the other
     * says what Android puts on the SCREEN. iOS derives both from the same `case`, so
     * they must agree — and if either drifts, this fails.
     *
     * Concretely: `ERROR` must not send `network_error` while showing "Call ended",
     * and `MISSED` must not send `no_answer` while showing something else.
     */
    @Test
    fun `screen text agrees with the wire value for every reason`() {
        for (reason in CallEndReason.entries) {
            val wire = CallEndReasonWire.toWire(reason)
            val expected = CallEndReasonDisplay.LABEL_FOR_IOS_RAW_VALUE[wire]
            assertNotNull("no iOS sentence recorded for wire value '$wire'", expected)
            assertEquals(
                "$reason goes on the wire as '$wire' but shows a different sentence",
                expected,
                CallEndReasonDisplay.labelFor(reason)
            )
        }
    }

    /** iOS falls back to a plain "Call ended" when it has no reason recorded. */
    @Test
    fun `no reason reads as a plain call ended`() {
        assertEquals(CallEndLabel.ENDED, CallEndReasonDisplay.labelFor(null))
    }

    /**
     * The peer hanging up is `hung_up` on the wire and "Call ended" on screen: iOS
     * has no separate sentence for "they hung up on you".
     */
    @Test
    fun `a peer hangup reads the same as our own`() {
        assertEquals(
            CallEndReasonDisplay.labelFor(CallEndReason.HUNG_UP),
            CallEndReasonDisplay.labelFor(CallEndReason.PEER_ENDED)
        )
    }

    /** The callee's-eye view of the same event the caller calls NO_ANSWER. */
    @Test
    fun `missed reads as no answer`() {
        assertEquals(CallEndLabel.NO_ANSWER, CallEndReasonDisplay.labelFor(CallEndReason.MISSED))
    }

    /**
     * The three failure modes are three DIFFERENT sentences. This is the whole point
     * of the change: before it, all three were the same silent disappearance.
     */
    @Test
    fun `the failure modes are distinguishable from a normal hangup and from each other`() {
        val hangUp = CallEndReasonDisplay.labelFor(CallEndReason.HUNG_UP)
        val declined = CallEndReasonDisplay.labelFor(CallEndReason.DECLINED)
        val noAnswer = CallEndReasonDisplay.labelFor(CallEndReason.NO_ANSWER)
        val error = CallEndReasonDisplay.labelFor(CallEndReason.ERROR)
        val lost = CallEndReasonDisplay.labelFor(CallEndReason.CONNECTION_LOST)
        val elsewhere = CallEndReasonDisplay.labelFor(CallEndReason.ANSWERED_ELSEWHERE)
        assertEquals(
            "each of these must be its own sentence",
            6,
            setOf(hangUp, declined, noAnswer, error, lost, elsewhere).size
        )
    }

    // NOTE: there is deliberately no "every reason has a label" test here.
    // `labelFor` returns a non-null `CallEndLabel` and Kotlin refuses to compile a
    // non-exhaustive `when` over an enum, so such a test could never fail — it would
    // be a green tick that proves nothing. The coverage that matters is the wire
    // cross-check above, which iterates the same `entries` and asserts a VALUE.

    /**
     * `peer_disconnected` and `connection_lost` deliberately share a sentence — iOS
     * says "Connection lost" for both (`VoiceCallManager.swift:1844-1845`), because
     * the user does not care which of the two mechanisms noticed.
     */
    @Test
    fun `both iOS disconnect reasons share one sentence`() {
        assertEquals(
            CallEndReasonDisplay.LABEL_FOR_IOS_RAW_VALUE["peer_disconnected"],
            CallEndReasonDisplay.LABEL_FOR_IOS_RAW_VALUE["connection_lost"]
        )
    }

    /** The sentence table must cover iOS's whole vocabulary and nothing else. */
    @Test
    fun `the sentence table covers exactly iOS's raw values`() {
        assertEquals(
            CallEndReasonWire.IOS_RAW_VALUES,
            CallEndReasonDisplay.LABEL_FOR_IOS_RAW_VALUE.keys
        )
    }
}
