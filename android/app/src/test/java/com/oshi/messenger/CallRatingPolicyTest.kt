package com.oshi.messenger

import com.oshi.messenger.service.CallRatingManager
import com.oshi.messenger.service.CallRatingPolicy
import com.oshi.messenger.service.CallRatingPolicy.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CALL_RATING_2026_09_22__ — when the post-call sheet is allowed to appear.
 *
 * Every gate gets a test that names it, so a failure says WHICH rule fired rather
 * than "it returned false". The ordering matters as much as the rules: the weekly
 * ceiling has to outrank "a bad call always asks", or one bad afternoon becomes a
 * dozen sheets.
 */
class CallRatingPolicyTest {

    private val now = 1_700_000_000_000L
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    private fun decide(
        durationSeconds: Long = 60,
        endedNormally: Boolean = true,
        alreadyRated: Boolean = false,
        lastAskAt: Long = 0,
        askTimes: List<Long> = emptyList(),
        goodCallCounter: Int = 0,
        wasFault: Boolean = false
    ) = CallRatingPolicy.decide(
        now = now,
        durationSeconds = durationSeconds,
        endedNormally = endedNormally,
        alreadyRated = alreadyRated,
        lastAskAt = lastAskAt,
        askTimes = askTimes,
        goodCallCounter = goodCallCounter,
        wasFault = wasFault
    )

    // ---------------------------------------------------------------- length

    @Test
    fun `a call too short to judge is never asked about`() {
        val d = decide(durationSeconds = 7, endedNormally = false)
        assertFalse(d.ask)
        assertEquals(Block.TOO_SHORT, d.blockedBy)
    }

    @Test
    fun `exactly the minimum is long enough`() {
        // Abnormal so the sampling gate does not muddy what is being asserted.
        assertTrue(decide(durationSeconds = 8, endedNormally = false).ask)
    }

    // ---------------------------------------------------------------- one per call

    @Test
    fun `a call already rated is never asked about twice`() {
        val d = decide(alreadyRated = true, endedNormally = false)
        assertFalse(d.ask)
        assertEquals(Block.ALREADY_RATED, d.blockedBy)
        assertNull("a repeat must not advance the counter", d.newGoodCallCounter)
    }

    // ---------------------------------------------------------------- abnormal ends

    @Test
    fun `a call that ended badly is always asked about`() {
        assertTrue(decide(endedNormally = false, goodCallCounter = 0).ask)
        assertTrue(decide(endedNormally = false, goodCallCounter = 1).ask)
    }

    @Test
    fun `a bad call does not consume the good-call counter`() {
        // That counter paces ORDINARY calls; a drop must not shift its phase.
        assertNull(decide(endedNormally = false).newGoodCallCounter)
    }

    // ---------------------------------------------------------------- sampling

    @Test
    fun `only every third good call is asked about`() {
        assertFalse(decide(goodCallCounter = 0).ask)   // becomes 1
        assertFalse(decide(goodCallCounter = 1).ask)   // becomes 2
        assertTrue(decide(goodCallCounter = 2).ask)    // becomes 3 -> ask
        assertFalse(decide(goodCallCounter = 3).ask)   // becomes 4
    }

    @Test
    fun `the counter advances even when we do not ask`() {
        // Otherwise it would be every third ASK, not every third CALL, and the
        // sheet would never come round at all.
        assertEquals(1, decide(goodCallCounter = 0).newGoodCallCounter)
        assertEquals(2, decide(goodCallCounter = 1).newGoodCallCounter)
        assertEquals(3, decide(goodCallCounter = 2).newGoodCallCounter)
    }

    @Test
    fun `a good call that is not sampled says so`() {
        assertEquals(Block.NOT_SAMPLED, decide(goodCallCounter = 0).blockedBy)
    }

    // ---------------------------------------------------------------- cooldown

    @Test
    fun `a good call inside the long cooldown is not asked about`() {
        val d = decide(goodCallCounter = 2, lastAskAt = now - 19 * hour)
        assertFalse(d.ask)
        assertEquals(Block.COOLDOWN, d.blockedBy)
    }

    @Test
    fun `a good call past the long cooldown is asked about`() {
        assertTrue(decide(goodCallCounter = 2, lastAskAt = now - 21 * hour).ask)
    }

    @Test
    fun `a bad call uses the shorter cooldown`() {
        // 4h: still blocked for a good call, allowed for a bad one.
        assertFalse(decide(goodCallCounter = 2, lastAskAt = now - 4 * hour).ask)
        assertTrue(decide(endedNormally = false, lastAskAt = now - 4 * hour).ask)
    }

    @Test
    fun `even a bad call respects the short cooldown`() {
        val d = decide(endedNormally = false, lastAskAt = now - 2 * hour)
        assertFalse(d.ask)
        assertEquals(Block.COOLDOWN, d.blockedBy)
    }

    // ---------------------------------------------------------------- weekly ceiling

    @Test
    fun `the weekly ceiling outranks a bad call`() {
        val five = (1..5).map { now - it * day }
        val d = decide(endedNormally = false, askTimes = five)
        assertFalse("a bad afternoon must not become a dozen sheets", d.ask)
        assertEquals(Block.WEEKLY_CEILING, d.blockedBy)
    }

    @Test
    fun `asks older than a week do not count towards the ceiling`() {
        val stale = (1..5).map { now - 8 * day - it * hour }
        assertTrue(decide(endedNormally = false, askTimes = stale).ask)
    }

    @Test
    fun `four asks this week still leaves room for one more`() {
        val four = (1..4).map { now - it * day }
        assertTrue(decide(endedNormally = false, askTimes = four).ask)
    }

    // ---------------------------------------------------------------- wire contract

    @Test
    fun `reason wire values match the iOS enum exactly`() {
        // These strings cross the network and land in one shared channel. If the
        // two clients disagree, the channel silently splits into two vocabularies.
        // Source of truth: OSHI/CallRatingManager.swift `CallRatingReason`.
        assertEquals(
            listOf("choppy", "couldNotHearThem", "delay", "dropped", "echo", "theyCouldNotHearMe"),
            CallRatingManager.Reason.values().map { it.wire }.sorted()
        )
    }

    @Test
    fun `every reason has a distinct wire value`() {
        val wires = CallRatingManager.Reason.values().map { it.wire }
        assertEquals(wires.size, wires.toSet().size)
    }

    // ------------------------------------------------- __TX_VOICE_EVIDENCE_2026_10_29__

    private fun evidence(
        txFrames: Long = 4257,
        rxFrames: Long = 4311,
        txVoicePercent: Int? = 63,
        txPeakPercent: Int? = 88,
        codec: String = "opus",
        peerPlatform: String = "ios"
    ) = CallRatingManager.Evidence(txFrames, rxFrames, txVoicePercent, txPeakPercent, codec, peerPlatform)

    @Test
    fun `the body carries the evidence the relay cannot see`() {
        val body = CallRatingManager.buildBody(
            callId = "97d98d39-0000-0000-0000-000000000000",
            senderKey = "pDXgKwfNbHxd",
            rating = 1,
            reasons = setOf(CallRatingManager.Reason.THEY_COULD_NOT_HEAR_ME),
            comment = "",
            appVersion = "1.6.55 (96)",
            evidence = evidence()
        )
        assertEquals(1, body.getInt("rating"))
        assertEquals("Android", body.getString("platform"))
        assertEquals(listOf("theyCouldNotHearMe"), listOf(body.getJSONArray("reasons").getString(0)))
        val e = body.getJSONObject("evidence")
        assertEquals(4257L, e.getLong("txFrames"))
        assertEquals(4311L, e.getLong("rxFrames"))
        assertEquals(63, e.getInt("txVoicePct"))
        assertEquals(88, e.getInt("txPeakPct"))
        assertEquals("opus", e.getString("codec"))
        assertEquals("ios", e.getString("peer"))
    }

    @Test
    fun `an unmeasured level is absent, not zero`() {
        // Zero would read as "the microphone was silent", which is the single
        // conclusion this field exists to support. It must not be reachable by
        // accident when nothing was measured at all.
        val e = CallRatingManager.buildBody(
            callId = "c", senderKey = "k", rating = 2, reasons = emptySet(), comment = "",
            appVersion = "1.6.55", evidence = evidence(txVoicePercent = null, txPeakPercent = null)
        ).getJSONObject("evidence")
        assertFalse(e.has("txVoicePct"))
        assertFalse(e.has("txPeakPct"))
        assertEquals(4257L, e.getLong("txFrames"))
    }

    @Test
    fun `a call with no evidence still rates`() {
        val body = CallRatingManager.buildBody(
            callId = "c", senderKey = "k", rating = 5, reasons = emptySet(), comment = "",
            appVersion = "1.6.55", evidence = null
        )
        assertFalse(body.has("evidence"))
        assertEquals(5, body.getInt("rating"))
    }

    @Test
    fun `an empty comment is omitted rather than sent blank`() {
        assertFalse(
            CallRatingManager.buildBody("c", "k", 3, emptySet(), "", "1.6.55", null).has("comment")
        )
        assertEquals(
            "robotic",
            CallRatingManager.buildBody("c", "k", 3, emptySet(), "robotic", "1.6.55", null)
                .getString("comment")
        )
    }

    // ------------------------------------ __SHORT_FAILURES..._2026_11_07__

    /**
     * The regression itself. A call that broke after 2 seconds is the single most
     * diagnostic thing a person can report, and the length floor used to silence
     * it — rule 1 sat above rule 4 and voided it. Measured on the relay over
     * 06-07/11: 8 of 17 calls were 0-4 s and exactly one rating came back.
     */
    @Test
    fun `a call that broke in two seconds is asked about`() {
        val d = decide(durationSeconds = 2, endedNormally = false, wasFault = true)
        assertTrue("a 2s fault must be askable", d.ask)
        assertNull(d.blockedBy)
    }

    /** Without the fault flag the floor still stands — this is a sampling rule. */
    @Test
    fun `a short call that merely ended is still too short`() {
        val d = decide(durationSeconds = 2, endedNormally = true, wasFault = false)
        assertFalse(d.ask)
        assertEquals(Block.TOO_SHORT, d.blockedBy)
    }

    /**
     * The reason `wasFault` exists instead of reusing `!endedNormally`: a declined
     * or answered-elsewhere call is abnormal and is NOT a fault. Asking about a
     * 3-second declined call is how a sheet gets trained into reflex dismissal.
     */
    @Test
    fun `a short abnormal call that was not a fault stays silent`() {
        val d = decide(durationSeconds = 3, endedNormally = false, wasFault = false)
        assertFalse("declined / answered-elsewhere must not raise a sheet", d.ask)
        assertEquals(Block.TOO_SHORT, d.blockedBy)
    }

    /** A fault skips the LENGTH floor and nothing else. The ceiling still outranks it. */
    @Test
    fun `a fault does not outrank the weekly ceiling`() {
        val d = decide(
            durationSeconds = 0, endedNormally = false, wasFault = true,
            askTimes = List(CallRatingPolicy.MAX_ASKS_PER_WEEK) { now - it * hour }
        )
        assertFalse(d.ask)
        assertEquals(Block.WEEKLY_CEILING, d.blockedBy)
    }

    /** Nor the cooldown: a bad afternoon is still not a dozen sheets. */
    @Test
    fun `a fault does not outrank the cooldown`() {
        val d = decide(
            durationSeconds = 0, endedNormally = false, wasFault = true,
            lastAskAt = now - hour
        )
        assertFalse(d.ask)
        assertEquals(Block.COOLDOWN, d.blockedBy)
    }

    /** And a fault never consumes the good-call sampling counter. */
    @Test
    fun `a fault leaves the good call counter alone`() {
        val d = decide(durationSeconds = 1, endedNormally = false, wasFault = true)
        assertTrue(d.ask)
        assertNull("the counter paces ordinary calls only", d.newGoodCallCounter)
    }
}
