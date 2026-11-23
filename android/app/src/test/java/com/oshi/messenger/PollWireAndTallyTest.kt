package com.oshi.messenger

import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.data.model.PollBallot
import com.oshi.messenger.data.model.PollMessage
import com.oshi.messenger.data.model.PollVotePayload
import com.oshi.messenger.service.PollStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __POLLS_2026_10_23__ The poll wire format and the tally.
 *
 * The golden payloads below are the CONTRACT with iOS. They are written out in
 * full rather than generated, because a round-trip test passes happily while
 * both sides agree on the wrong thing — which is exactly how the
 * cross-platform timestamp bug survived: each platform decoded its own encoder.
 *
 * iOS checks the same two strings in `scratchpad`-style harness form; any
 * change to a key name here has to change there in the same commit.
 */
class PollWireAndTallyTest {

    private val goldenPoll =
        "📊POLL📊{\"v\":1,\"id\":\"P1\",\"q\":\"Pizza or sushi?\"," +
            "\"opts\":[\"Pizza\",\"Sushi\",\"Either\"]," +
            "\"multi\":false,\"anon\":false,\"at\":1761177600,\"by\":\"KEYA\"}"

    private val goldenVote =
        "📊POLL_VOTE📊{\"v\":1,\"poll\":\"P1\",\"picks\":[1],\"at\":1761177660}"

    @Test
    fun `the sentinels are in the catalog with the right kind`() {
        assertEquals(OSHIControlPayload.POLL, OSHIControlPayload.matchedPrefix(goldenPoll))
        assertEquals(OSHIControlPayload.POLL_VOTE, OSHIControlPayload.matchedPrefix(goldenVote))
        assertTrue(OSHIControlPayload.isControl(goldenPoll))
        assertTrue(OSHIControlPayload.isControl(goldenVote))
        // A poll is worth a notification; a ballot is not.
        assertFalse(OSHIControlPayload.suppressesPush(goldenPoll))
        assertTrue(OSHIControlPayload.suppressesPush(goldenVote))
        // And a ballot must never be drawn as a line in the thread.
        assertTrue(OSHIControlPayload.isSilent(goldenVote))
        assertFalse(OSHIControlPayload.isSilent(goldenPoll))
    }

    @Test
    fun `an iOS poll payload decodes field by field`() {
        val p = PollMessage.fromMessageString(goldenPoll)
        assertNotNull(p)
        p!!
        assertEquals("P1", p.id)
        assertEquals("Pizza or sushi?", p.question)
        assertEquals(listOf("Pizza", "Sushi", "Either"), p.options)
        assertFalse(p.allowsMultipleAnswers)
        assertFalse(p.isAnonymous)
        assertEquals("KEYA", p.creatorPublicKey)
        // Unix seconds, NOT Apple's 2001 epoch. 1761177600 is 2025-10-23; read
        // on the Apple epoch it would land in 2056.
        assertEquals(1761177600.0, p.createdAtUnix, 0.5)
        assertNull(p.closesAtUnix)
    }

    @Test
    fun `an iOS ballot payload decodes`() {
        val v = PollVotePayload.fromMessageString(goldenVote)
        assertNotNull(v)
        assertEquals("P1", v!!.pollId)
        assertEquals(listOf(1), v.picks)
        assertEquals(1761177660.0, v.atUnix, 0.5)
    }

    @Test
    fun `what we write is what iOS reads - exact key names`() {
        val sent = PollMessage(
            id = "P2", question = "Q", options = listOf("a", "b"),
            allowsMultipleAnswers = true, isAnonymous = true,
            createdAtUnix = 100.0, closesAtUnix = 200.0, creatorPublicKey = "K",
        ).toMessageString()
        assertTrue(sent.startsWith(OSHIControlPayload.POLL))
        val o = JSONObject(sent.removePrefix(OSHIControlPayload.POLL))
        assertEquals(
            setOf("v", "id", "q", "opts", "multi", "anon", "at", "closes", "by"),
            o.keys().asSequence().toSet(),
        )
        val ballot = PollVotePayload("P2", listOf(0, 1), 300.0).toMessageString()
        val b = JSONObject(ballot.removePrefix(OSHIControlPayload.POLL_VOTE))
        assertEquals(setOf("v", "poll", "picks", "at"), b.keys().asSequence().toSet())
        // No voter field, ever: it would let one member vote as another.
        assertFalse(b.has("voter"))
        assertFalse(b.has("by"))
    }

    @Test
    fun `the prefix survives the U+FE0F-less spelling`() {
        // Not applicable to 📊 (it carries no variation selector), but the body
        // extractor must still cut at the right length for it.
        assertEquals(
            "{\"v\":1,\"poll\":\"P1\",\"picks\":[1],\"at\":1761177660}",
            OSHIControlPayload.body(goldenVote, OSHIControlPayload.POLL_VOTE),
        )
        assertNull(OSHIControlPayload.body(goldenVote, OSHIControlPayload.POLL))
    }

    @Test
    fun `sanitize is the single definition of sendable`() {
        assertNull(PollMessage.sanitize("  ", listOf("a", "b")))
        assertNull(PollMessage.sanitize("q", listOf("a")))
        assertNull(PollMessage.sanitize("q", listOf("a", " ")))
        assertNull(PollMessage.sanitize("q", List(13) { "o$it" }))
        val ok = PollMessage.sanitize(" q ", listOf(" a ", "b", "  "))
        assertEquals("q" to listOf("a", "b"), ok)
    }

    // ── the tally ───────────────────────────────────────────────────────────

    private fun poll(multi: Boolean = false, anon: Boolean = false, id: String) = PollMessage(
        id = id, question = "Q", options = listOf("A", "B", "C"),
        allowsMultipleAnswers = multi, isAnonymous = anon, creatorPublicKey = "me",
    )

    @Test
    fun `a single-answer poll counts one voter once`() {
        val p = poll(id = "T1")
        PollStore.record(p.id, "v1", PollBallot(listOf(0), 10.0))
        PollStore.record(p.id, "v2", PollBallot(listOf(0), 11.0))
        PollStore.record(p.id, "v3", PollBallot(listOf(1), 12.0))
        val r = PollStore.results(p, "v3")
        assertEquals(listOf(2, 1, 0), r.counts)
        assertEquals(3, r.voterCount)
        assertEquals(setOf(1), r.myPicks)
        assertEquals(67, r.percent(0))
        assertEquals(33, r.percent(1))
        assertEquals(0, r.percent(2))
    }

    @Test
    fun `a later ballot replaces an earlier one and an older one is ignored`() {
        val p = poll(id = "T2")
        PollStore.record(p.id, "v1", PollBallot(listOf(0), 10.0))
        assertTrue(PollStore.record(p.id, "v1", PollBallot(listOf(2), 20.0)))
        // Out of order delivery: a vote relayed through a third phone, older
        // than the correction that superseded it.
        assertFalse(PollStore.record(p.id, "v1", PollBallot(listOf(1), 15.0)))
        // And the exact same ballot twice is a duplicate, which mesh produces
        // routinely.
        assertFalse(PollStore.record(p.id, "v1", PollBallot(listOf(2), 20.0)))
        val r = PollStore.results(p, "v1")
        assertEquals(listOf(0, 0, 1), r.counts)
        assertEquals(1, r.voterCount)
    }

    @Test
    fun `an empty ballot is a retraction, not a missing vote`() {
        val p = poll(id = "T3")
        PollStore.record(p.id, "v1", PollBallot(listOf(0), 10.0))
        PollStore.record(p.id, "v1", PollBallot(emptyList(), 11.0))
        val r = PollStore.results(p, "v1")
        assertEquals(listOf(0, 0, 0), r.counts)
        assertEquals(0, r.voterCount)
        assertTrue(r.myPicks.isEmpty())
    }

    @Test
    fun `multiple answers may exceed 100 percent and still count one voter`() {
        val p = poll(multi = true, id = "T4")
        PollStore.record(p.id, "v1", PollBallot(listOf(0, 1, 2), 10.0))
        PollStore.record(p.id, "v2", PollBallot(listOf(0), 11.0))
        val r = PollStore.results(p, "v1")
        assertEquals(listOf(2, 1, 1), r.counts)
        assertEquals(2, r.voterCount)
        assertEquals(4, r.totalPicksForTest())
        assertEquals(100, r.percent(0))
        assertEquals(50, r.percent(1))
    }

    @Test
    fun `a single-answer poll keeps the first pick of a multi ballot`() {
        val p = poll(id = "T5")
        PollStore.record(p.id, "v1", PollBallot(listOf(2, 0), 10.0))
        val r = PollStore.results(p, "v1")
        assertEquals(listOf(1, 0, 0), r.counts)   // 0 is the lowest index, taken first
        assertEquals(1, r.voterCount)
    }

    @Test
    fun `out of range picks are dropped at tally time`() {
        val p = poll(id = "T6")
        // A ballot can arrive BEFORE the poll it belongs to, so there is nothing
        // to validate it against at record time.
        PollStore.record(p.id, "v1", PollBallot(listOf(99), 10.0))
        PollStore.record(p.id, "v2", PollBallot(listOf(1, 99), 11.0))
        val r = PollStore.results(p, "v1")
        assertEquals(listOf(0, 1, 0), r.counts)
        assertEquals(1, r.voterCount)
    }

    @Test
    fun `one voter is one key whatever the spelling`() {
        val p = poll(id = "T7")
        // base64 vs base64url, padded vs not — the same person.
        PollStore.record(p.id, "ab+cd/ef=", PollBallot(listOf(0), 10.0))
        PollStore.record(p.id, "ab-cd_ef", PollBallot(listOf(1), 11.0))
        val r = PollStore.results(p, "ab+cd/ef=")
        assertEquals(1, r.voterCount)
        assertEquals(listOf(0, 1, 0), r.counts)
        assertEquals(setOf(1), r.myPicks)
    }

    @Test
    fun `an anonymous poll never collects the names`() {
        val p = poll(anon = true, id = "T8")
        PollStore.record(p.id, "v1", PollBallot(listOf(0), 10.0))
        val r = PollStore.results(p, "v1")
        assertEquals(1, r.voterCount)
        assertTrue(r.votersByOption.all { it.isEmpty() })
        // Named polls DO, in a deterministic order.
        val named = poll(id = "T9")
        PollStore.record(named.id, "bbb", PollBallot(listOf(0), 20.0))
        PollStore.record(named.id, "aaa", PollBallot(listOf(0), 10.0))
        assertEquals(listOf("aaa", "bbb"), PollStore.results(named, "aaa").votersByOption[0])
    }

    @Test
    fun `a share of a vote never rounds down to zero percent`() {
        val p = PollMessage(
            id = "T10", question = "Q", options = listOf("A", "B"),
            allowsMultipleAnswers = false, isAnonymous = false, creatorPublicKey = "me",
        )
        repeat(150) { PollStore.record(p.id, "v$it", PollBallot(listOf(0), 10.0 + it)) }
        PollStore.record(p.id, "late", PollBallot(listOf(1), 999.0))
        val r = PollStore.results(p, "late")
        assertEquals(151, r.voterCount)
        assertEquals(1, r.percent(1))   // 0.66 % — a bar with a body cannot say 0 %
    }
}

private fun com.oshi.messenger.data.model.PollResults.totalPicksForTest(): Int = counts.sum()
