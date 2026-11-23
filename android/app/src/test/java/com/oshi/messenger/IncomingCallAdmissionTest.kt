package com.oshi.messenger

import com.oshi.messenger.service.IncomingCallAdmission
import com.oshi.messenger.service.IncomingCallAdmission.Decision
import com.oshi.messenger.service.IncomingCallAdmission.State
import org.junit.Assert.assertEquals
import org.junit.Test

/** __STALE_RING_2026_09_23__ — the offer-admission rule that replaced "drop everything while not IDLE". */
class IncomingCallAdmissionTest {

    private val callee = 55_000L
    private val caller = 45_000L
    private val t0 = 1_000_000L

    private fun decide(
        state: State,
        outgoing: Boolean = false,
        peer: String? = "alice",
        callId: String? = "call-a",
        startedAt: Long? = t0,
        now: Long = t0 + 5_000,
        newPeer: String = "bob",
        newCallId: String? = "call-b",
    ) = IncomingCallAdmission.decide(
        state, outgoing, peer, callId, startedAt, now, newPeer, newCallId, callee, caller
    )

    @Test fun idleAccepts() = assertEquals(Decision.ACCEPT, decide(State.IDLE, peer = null, callId = null))

    // The reported bug: a ring from sgnp90… that outlived its caller refused every later call.
    @Test fun staleIncomingRingIsReplaced() =
        assertEquals(Decision.REPLACE_STALE, decide(State.RINGING, now = t0 + callee + 1))

    @Test fun staleRingIsReplacedEvenBySameCallerWithNoCallId() =
        assertEquals(Decision.REPLACE_STALE, decide(State.RINGING, now = t0 + callee + 1, newPeer = "alice", newCallId = ""))

    @Test fun ringAtExactlyTheTimeoutIsNotYetStale() =
        assertEquals(Decision.BUSY, decide(State.RINGING, now = t0 + callee))

    @Test fun freshRingFromAnotherCallerIsBusy() = assertEquals(Decision.BUSY, decide(State.RINGING))

    @Test fun sameCallerRedialWithNewCallIdReplacesOwnOldRing() =
        assertEquals(Decision.REPLACE_STALE, decide(State.RINGING, newPeer = "alice", newCallId = "call-a2"))

    @Test fun reDeliveryOfTheSameCallIsDuplicate() =
        assertEquals(Decision.DUPLICATE, decide(State.RINGING, newPeer = "alice", newCallId = "call-a"))

    @Test fun sameCallerWithoutCallIdIsDuplicateNotReplace() =
        assertEquals(Decision.DUPLICATE, decide(State.RINGING, newPeer = "alice", newCallId = ""))

    // (c) a genuine concurrent call is declined as busy, as iOS does.
    @Test fun inCallWithSomeoneElseIsBusy() = assertEquals(Decision.BUSY, decide(State.IN_CALL, now = t0 + 600_000))

    @Test fun connectingWithSomeoneElseIsBusy() = assertEquals(Decision.BUSY, decide(State.CONNECTING))

    @Test fun inCallOfferFromOwnPeerIsDuplicate() =
        assertEquals(Decision.DUPLICATE, decide(State.IN_CALL, newPeer = "alice", newCallId = "x"))

    @Test fun longCallIsNeverTreatedAsStale() =
        assertEquals(Decision.BUSY, decide(State.IN_CALL, now = t0 + 3_600_000))

    @Test fun outgoingRingPlusOtherCallerIsBusy() = assertEquals(Decision.BUSY, decide(State.RINGING, outgoing = true))

    @Test fun outgoingRingPlusSamePeerIsLeftToGlareHandling() =
        assertEquals(Decision.DUPLICATE, decide(State.RINGING, outgoing = true, newPeer = "alice", newCallId = "call-z"))

    @Test fun staleOutgoingRingUsesCallerTimeout() {
        assertEquals(Decision.BUSY, decide(State.RINGING, outgoing = true, now = t0 + caller))
        assertEquals(Decision.REPLACE_STALE, decide(State.RINGING, outgoing = true, now = t0 + caller + 1))
    }

    @Test fun teardownIsLeftAlone() = assertEquals(Decision.TEARDOWN, decide(State.ENDED))

    @Test fun ringingWithNoCallObjectIsALeakAndIsReplaced() =
        assertEquals(Decision.REPLACE_STALE, decide(State.RINGING, peer = null, callId = null, startedAt = null))
}
