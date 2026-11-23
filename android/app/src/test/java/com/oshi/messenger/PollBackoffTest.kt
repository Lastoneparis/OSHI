package com.oshi.messenger

import com.oshi.messenger.service.AppPollState
import com.oshi.messenger.service.PollBackoff
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The poll schedule that replaced seven free-running `while (true)` loops.
 *
 * The property that actually matters here is not "the numbers went down" — it is that
 * the ONE loop which must never stop, never stops. A missed call is a worse defect
 * than a warm phone, so [callPollIsNeverSuspendedInAnyState] and
 * [aLiveCallPinsTheFastCallInterval] are the load-bearing tests.
 */
class PollBackoffTest {

    @After
    fun tearDown() {
        AppPollState.resetForTest()
        AppPollState.clock = { System.currentTimeMillis() }
    }

    // ── The call poll: the thing we deliberately did NOT suspend ───────────────

    @Test
    fun callPollIsNeverSuspendedInAnyState() {
        for (foreground in listOf(true, false)) {
            for (callActive in listOf(true, false)) {
                val d = PollBackoff.callDelayMs(foreground, callActive)
                assertTrue(
                    "call poll must never stop (fg=$foreground call=$callActive got $d)",
                    d in 1..60_000L
                )
            }
        }
    }

    @Test
    fun aLiveCallPinsTheFastCallInterval() {
        // Backgrounded is the dangerous case: the message poll goes to 120 s there,
        // and the call poll must NOT follow it.
        assertEquals(PollBackoff.CALL_ACTIVE_MS, PollBackoff.callDelayMs(foreground = false, callActive = true))
        assertEquals(PollBackoff.CALL_ACTIVE_MS, PollBackoff.callDelayMs(foreground = true, callActive = true))
        assertEquals(3_000L, PollBackoff.CALL_ACTIVE_MS)
    }

    @Test
    fun callPollIdlesSlowerOnScreenThanItUsedToButFasterThanInBackground() {
        assertEquals(10_000L, PollBackoff.callDelayMs(foreground = true, callActive = false))
        assertEquals(60_000L, PollBackoff.callDelayMs(foreground = false, callActive = false))
    }

    @Test
    fun anObservedSignalDragsTheLoopBackToFastForAFullMinute() {
        var now = 1_000_000L
        AppPollState.resetForTest(now)
        AppPollState.clock = { now }
        AppPollState.onBackground()

        assertEquals(60_000L, AppPollState.callPollDelayMs())   // idle backstop

        AppPollState.noteCallSignal()                            // an offer arrives
        assertTrue(AppPollState.isCallActive())
        assertEquals(3_000L, AppPollState.callPollDelayMs())

        now += AppPollState.CALL_HOT_WINDOW_MS - 1
        assertEquals("still hot just inside the window", 3_000L, AppPollState.callPollDelayMs())

        now += 2
        assertFalse(AppPollState.isCallActive())
        assertEquals(60_000L, AppPollState.callPollDelayMs())
    }

    @Test
    fun anOpenCallSessionOutlastsTheSignalWindow() {
        var now = 1_000_000L
        AppPollState.resetForTest(now)
        AppPollState.clock = { now }
        AppPollState.setCallSessionOpen(true)

        now += AppPollState.CALL_HOT_WINDOW_MS * 10          // a ten-minute call
        assertTrue("a connected call must stay fast however long it runs", AppPollState.isCallActive())
        assertEquals(3_000L, AppPollState.callPollDelayMs())

        AppPollState.setCallSessionOpen(false)
        now += AppPollState.CALL_HOT_WINDOW_MS + 1
        assertFalse(AppPollState.isCallActive())
    }

    // ── The message poll: suspended-equivalent on background, escalating on idle ─

    @Test
    fun messagePollKeepsTheOldIntervalWhileForegroundAndBusy() {
        assertEquals(5_000L, PollBackoff.messageDelayMs(foreground = true, callActive = false, idleMs = 0))
        assertEquals(5_000L, PollBackoff.messageDelayMs(true, false, PollBackoff.IDLE_STEP_1_MS - 1))
    }

    @Test
    fun messagePollEscalatesWhileForegroundAndIdleAndTheLadderIsMonotonic() {
        val ladder = listOf(0L, 90_000L, 400_000L, 1_800_000L)
            .map { PollBackoff.messageDelayMs(true, false, it) }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L), ladder)
        assertEquals(ladder, ladder.sorted())
    }

    @Test
    fun messagePollBacksRightOffWhenBackgrounded() {
        // Whatever the idle state, background is 120 s — FCM is the wake path there.
        for (idle in listOf(0L, 90_000L, 10_000_000L)) {
            assertEquals(120_000L, PollBackoff.messageDelayMs(foreground = false, callActive = false, idleMs = idle))
        }
    }

    @Test
    fun aCallInProgressOverridesTheBackgroundBackoffForMessagesToo() {
        // Call setup rides the same host; a live call is not "nothing happening".
        assertEquals(
            PollBackoff.MESSAGE_BASE_MS,
            PollBackoff.messageDelayMs(foreground = false, callActive = true, idleMs = 10_000_000L)
        )
    }

    @Test
    fun activityResetsTheEscalation() {
        var now = 1_000_000L
        AppPollState.resetForTest(now)
        AppPollState.clock = { now }

        now += PollBackoff.IDLE_STEP_3_MS + 1
        assertEquals(30_000L, AppPollState.messagePollDelayMs())

        AppPollState.noteActivity()                 // a message landed / a push woke us
        assertEquals(5_000L, AppPollState.messagePollDelayMs())
    }

    @Test
    fun returningToForegroundCountsAsActivity() {
        var now = 1_000_000L
        AppPollState.resetForTest(now)
        AppPollState.clock = { now }
        AppPollState.onBackground()
        now += PollBackoff.IDLE_STEP_3_MS * 4
        assertEquals(120_000L, AppPollState.messagePollDelayMs())

        AppPollState.onForeground()
        assertTrue(AppPollState.isForeground)
        assertEquals("foregrounding must not land straight on the 30 s idle rung", 5_000L, AppPollState.messagePollDelayMs())
    }

    // ── Housekeeping loops ────────────────────────────────────────────────────

    @Test
    fun auxLoopsSlowDownInBackgroundButNeverStop() {
        assertEquals(30_000L, PollBackoff.auxDelayMs(foreground = true, baseMs = 30_000L))
        assertEquals(120_000L, PollBackoff.auxDelayMs(foreground = false, baseMs = 30_000L))
        assertTrue(PollBackoff.auxDelayMs(false, 30_000L) > 0)
    }

    // ── The whole point, expressed as a number ────────────────────────────────

    @Test
    fun backgroundIdleWakeupsPerMinuteCollapse() {
        fun perMin(ms: Long) = 60_000.0 / ms
        // Before: message 5 s + call 3 s + v2 3 s + multi-device 30 s + group 30 s.
        val before = perMin(5_000) + perMin(3_000) + perMin(3_000) + perMin(30_000) + perMin(30_000)
        // After, backgrounded and idle: message and v2 share ONE 120 s tick, the call
        // poll drops to its 60 s backstop, housekeeping to 120 s.
        val after = perMin(PollBackoff.MESSAGE_BACKGROUND_MS) +
            perMin(PollBackoff.CALL_IDLE_BACKGROUND_MS) +
            perMin(PollBackoff.auxDelayMs(false, 30_000L)) * 2
        assertTrue("before=$before after=$after", after < before / 10.0)
    }
}
