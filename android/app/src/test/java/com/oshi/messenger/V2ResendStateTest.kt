package com.oshi.messenger

import com.oshi.messenger.network.v2.RatchetHealSignal
import com.oshi.messenger.network.v2.V2ResendBudget
import com.oshi.messenger.network.v2.V2ResendDecision
import com.oshi.messenger.network.v2.V2ResendPolicy
import com.oshi.messenger.network.v2.V2ResendState
import com.oshi.messenger.network.v2.V2UnreadableStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __RESEND_LIST_ANDROID_2026_10_12__ The two ledgers and the decision that let Android ASK
 * for the messages it gave up on and SERVE what a peer asks of it.
 *
 * What this protects, in one sentence: after eight failed attempts `V2MessageRouter.poll`
 * lets the ack pass and the relay drops the envelope, so the peer is the only one who still
 * holds that message — and a notification has already rung for it.
 */
class V2ResendStateTest {

    private class MemStore : V2ResendState.Store {
        var json: String? = null
        override fun read(): String? = json
        override fun write(json: String) { this.json = json }
    }

    private val now = 1_760_000_000_000L

    // ── The ledger of what we could not read ────────────────────────────────────

    @Test
    fun marksAndReturnsWhatWeGaveUpOn() {
        val s = V2UnreadableStore(MemStore())
        s.mark("peerA", "m1", now - 1_000)
        s.mark("peerA", "m2", now - 2_000)
        s.mark("peerB", "m3", now - 3_000)
        assertEquals(listOf("m1", "m2"), s.drain("peerA", now))
        assertEquals(listOf("m3"), s.drain("peerB", now))
        assertEquals(emptyList<String>(), s.drain("nobody", now))
    }

    @Test
    fun mostRecentFirstBecauseThatIsWhatIsStillWorthAsking() {
        val s = V2UnreadableStore(MemStore())
        s.mark("p", "old", now - 50_000)
        s.mark("p", "new", now - 1_000)
        s.mark("p", "mid", now - 10_000)
        assertEquals(listOf("new", "mid", "old"), s.drain("p", now))
    }

    @Test
    fun anythingPastTheResendAgeIsNotAskedForAgain() {
        val s = V2UnreadableStore(MemStore())
        s.mark("p", "fresh", now - 1_000)
        s.mark("p", "stale", now - RatchetHealSignal.Policy.MAX_RESEND_AGE_MS - 1)
        assertEquals(listOf("fresh"), s.drain("p", now))
    }

    @Test
    fun drainDoesNotEmptyTheLedger() {
        // An id is forgotten only after an acknowledgement actually LEFT. Draining here
        // would mean a failed send takes the list with it — the user loses the messages
        // AND the trigger that would have recovered them.
        val s = V2UnreadableStore(MemStore())
        s.mark("p", "m1", now)
        assertEquals(listOf("m1"), s.drain("p", now))
        assertEquals(listOf("m1"), s.drain("p", now))
        s.clear("m1")
        assertEquals(emptyList<String>(), s.drain("p", now))
    }

    @Test
    fun aMessageThatFinallyArrivedIsNeverAskedForAgain() {
        val s = V2UnreadableStore(MemStore())
        s.mark("p1", "m", now)
        s.mark("p2", "other", now)
        s.clear("m")                       // what the router does beside retryBudget.clear
        assertEquals(emptyList<String>(), s.drain("p1", now))
        assertEquals(listOf("other"), s.drain("p2", now))
    }

    @Test
    fun theLedgerIsBoundedPerPeerAndKeepsTheRecentOnes() {
        val s = V2UnreadableStore(MemStore())
        repeat(V2UnreadableStore.PER_PEER_CAP + 20) { i -> s.mark("p", "m$i", now - (1_000L * (200 - i))) }
        val kept = s.drain("p", now)
        assertEquals(RatchetHealSignal.MAX_RESEND_PER_REQUEST, kept.size)   // request cap
        assertTrue(kept.contains("m${V2UnreadableStore.PER_PEER_CAP + 19}"))
        assertFalse(kept.contains("m0"))
    }

    @Test
    fun anEnvelopeStampedInSecondsIsStillAskedFor() {
        // An envelope's `ts` arrives in SECONDS from some senders — `normalizeTimestamp` in
        // MessageRepository exists for exactly that. Stored raw it would read as 1970, every
        // id would be judged stale, and the list would always go out empty: the whole
        // mechanism silently doing nothing. Replay the raw form to prove the guard is real.
        val s = V2UnreadableStore(MemStore())
        s.mark("p", "secs", (now - 60_000) / 1000, now)
        assertEquals(listOf("secs"), s.drain("p", now))
        assertEquals(now, V2UnreadableStore.normalizeMs(0L, now))           // no stamp ⇒ ask
        assertEquals(now, V2UnreadableStore.normalizeMs(now, now))          // already ms
        assertEquals(now - 1_000, V2UnreadableStore.normalizeMs((now - 1_000) / 1000, now))
        // The old behaviour, kept so the regression is visible:
        val raw = (now - 60_000) / 1000
        assertTrue(raw < now - RatchetHealSignal.Policy.MAX_RESEND_AGE_MS)
    }

    @Test
    fun theLedgerSurvivesAProcessRestart() {
        val store = MemStore()
        V2UnreadableStore(store).mark("p", "m1", now)
        assertEquals(listOf("m1"), V2UnreadableStore(store).drain("p", now))
    }

    // ── The brakes on SERVING a request ─────────────────────────────────────────

    @Test
    fun attemptsPerMessageArePersistedBecauseTheCycleOutlivesTheProcess() {
        val store = MemStore()
        val b = V2ResendBudget(store)
        assertEquals(0, b.attempts("m"))
        b.noteResent("m")
        assertEquals(1, V2ResendBudget(store).attempts("m"))
        b.noteResent("m")
        assertEquals(2, b.attempts("m"))
        assertFalse(RatchetHealSignal.Policy.mayResend(b.attempts("m"), 1_000L, 64))
    }

    @Test
    fun theHourlyBudgetBoundsAChattyPeerAndThenRolls() {
        val b = V2ResendBudget(MemStore())
        val max = RatchetHealSignal.Policy.MAX_RESENDS_PER_PEER_PER_HOUR
        assertEquals(max, b.peerBudgetLeft("p", now))
        repeat(max) { b.noteSpent("p", now) }
        assertEquals(0, b.peerBudgetLeft("p", now))
        assertEquals(max, b.peerBudgetLeft("p", now + 3_600_000L))   // window rolled
        assertEquals(max, b.peerBudgetLeft("other", now))            // per peer, not global
    }

    // ── The decision ───────────────────────────────────────────────────────────

    private fun decide(
        found: Boolean = true,
        isFromMe: Boolean = true,
        senderMatchesMe: Boolean = true,
        recipientMatchesPeer: Boolean = true,
        deleted: Boolean = false,
        media: Boolean = false,
        ageMs: Long = 60_000L,
        attempts: Int = 0,
        budgetLeft: Int = 64,
    ) = V2ResendPolicy.decide(found, isFromMe, senderMatchesMe, recipientMatchesPeer,
        deleted, media, ageMs, attempts, budgetLeft)

    @Test
    fun aMessageWeSentToThisPeerIsResent() {
        assertEquals(V2ResendDecision.SEND, decide())
    }

    @Test
    fun ownershipIsASecurityGuardNotAnOptimisation() {
        // The ids are a value the PEER chooses. Without these three tests, guessing or
        // replaying an id gets a message meant for somebody else re-sent to the asker.
        assertEquals(V2ResendDecision.NOTMINE, decide(isFromMe = false))
        assertEquals(V2ResendDecision.NOTMINE, decide(senderMatchesMe = false))
        assertEquals(V2ResendDecision.NOTMINE, decide(recipientMatchesPeer = false))
    }

    @Test
    fun whatIsNeverResent() {
        assertEquals(V2ResendDecision.NOTFOUND, decide(found = false))
        assertEquals(V2ResendDecision.DELETED, decide(deleted = true))
        assertEquals(V2ResendDecision.MEDIA, decide(media = true))
        assertEquals(V2ResendDecision.AGE, decide(ageMs = RatchetHealSignal.Policy.MAX_RESEND_AGE_MS + 1))
        assertEquals(V2ResendDecision.BUDGET, decide(attempts = 2))
        assertEquals(V2ResendDecision.BUDGET, decide(budgetLeft = 0))
    }

    @Test
    fun theOwnershipGuardIsCheckedBeforeEverythingElse() {
        // A row that is not ours must read NOTMINE even when it would also be refused for
        // another reason — otherwise a log line would admit the row exists.
        assertEquals(V2ResendDecision.NOTMINE, decide(isFromMe = false, media = true, deleted = true))
    }
}
