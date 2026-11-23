package com.oshi.messenger.service

import com.oshi.messenger.data.model.CheckInSession
import com.oshi.messenger.data.model.CheckInStatus
import com.oshi.messenger.data.model.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The off-ramp, the escalation gate and the deadline that gate is measured against.
 *
 * ══ WHAT WAS BROKEN ══
 *
 * `stopCheckIn`, `confirmSafe` and `extendTime` had **zero call sites**: the warning
 * notification carried no buttons, `CheckInActiveBanner` was never composed, and
 * `showWarningAlert` was never collected. Every overdue check-in therefore ran the
 * full clock to `triggerEmergency()` and sent the user's position and battery level
 * to a contact under an "Emergency" banner. 23 real testers are about to use this,
 * and a false emergency reaching someone's family is the failure mode that matters.
 *
 * Everything asserted here is a pure decision — no Android runtime, no device. The
 * device-only half (does a notification button actually reach a dead process?) is
 * called out explicitly in the report; it cannot be asserted on the JVM and is not
 * pretended to be.
 */
class CheckInEscalationPolicyTest {

    private val T0 = 1_700_000_000_000L
    private val WINDOW = CheckInEscalationPolicy.EMERGENCY_AFTER_WARNING_MS

    // ── The invariant ────────────────────────────────────────────────────────

    /**
     * The single property this whole file exists for: **no reachable input escalates
     * without a delivered warning.** An emergency alert about someone who was never
     * asked anything is the harm the pilot cannot absorb.
     */
    @Test
    fun `never escalates without a delivered warning`() {
        val deadline = T0
        // Sweep from a minute before the deadline to two days after it.
        var offset = -60_000L
        while (offset <= 48L * 60 * 60 * 1000) {
            val decision = CheckInEscalationPolicy.decide(
                nowMs = deadline + offset,
                deadlineMs = deadline,
                warningDeliveredAtMs = null,
            )
            assertFalse(
                "escalated at deadline+${offset}ms with NO warning ever delivered",
                decision == CheckInEscalationPolicy.Decision.Escalate
            )
            offset += 37_000L  // a prime-ish step so boundaries are not all missed
        }
    }

    @Test
    fun `before the deadline nothing happens`() {
        assertEquals(
            CheckInEscalationPolicy.Decision.Wait,
            CheckInEscalationPolicy.decide(T0 - 1, T0, null)
        )
        assertEquals(
            "the deadline instant itself is not yet overdue",
            CheckInEscalationPolicy.Decision.Wait,
            CheckInEscalationPolicy.decide(T0, T0, null)
        )
    }

    @Test
    fun `overdue with no warning warns, however late`() {
        assertEquals(
            CheckInEscalationPolicy.Decision.Warn,
            CheckInEscalationPolicy.decide(T0 + 1, T0, null)
        )
        // The process-death case: the phone was frozen for three hours, then the
        // deadline alarm woke us. We ask first. Always.
        assertEquals(
            "a three-hour gap must still ASK before it tells a contact",
            CheckInEscalationPolicy.Decision.Warn,
            CheckInEscalationPolicy.decide(T0 + 3 * 60 * 60 * 1000, T0, null)
        )
    }

    @Test
    fun `warning delivered starts a fresh window`() {
        val warned = T0 + 1_000
        assertEquals(
            CheckInEscalationPolicy.Decision.Wait,
            CheckInEscalationPolicy.decide(warned + WINDOW - 1, T0, warned)
        )
        assertEquals(
            CheckInEscalationPolicy.Decision.Escalate,
            CheckInEscalationPolicy.decide(warned + WINDOW, T0, warned)
        )
    }

    @Test
    fun `a clock that moved backwards re-warns instead of escalating`() {
        val warned = T0 + 10 * 60 * 1000
        assertEquals(
            "elapsed time computed against a rewound clock must not send an emergency",
            CheckInEscalationPolicy.Decision.ClockUntrusted,
            CheckInEscalationPolicy.decide(warned - 1, T0, warned)
        )
    }

    /**
     * The escalation window is measured from **delivery**, not from the deadline —
     * so a warning that was only delivered late does not inherit the time the user
     * spent unprompted.
     */
    @Test
    fun `the window runs from delivery, not from the deadline`() {
        val warned = T0 + 2 * 60 * 60 * 1000  // delivered two hours after the ETA
        assertEquals(
            CheckInEscalationPolicy.Decision.Wait,
            CheckInEscalationPolicy.decide(warned + WINDOW - 1, T0, warned)
        )
    }
}

/**
 * The session state machine around the warning — where the second false-emergency
 * vector lived.
 */
class CheckInSessionOffRampTest {

    private fun session(
        etaOffsetMs: Long = 30 * 60 * 1000,
        lat: Double = 48.8566,
        lon: Double = 2.3522,
    ) = CheckInSession(
        contactPublicKey = "peer",
        destinationLatitude = lat,
        destinationLongitude = lon,
        destinationAddress = "Somewhere",
        transportMode = TransportMode.walking,
        estimatedArrivalTime = System.currentTimeMillis() + etaOffsetMs,
    )

    /**
     * 🔴 The bug: `extend()` used to leave `warningShownAt` set. So after the
     * "Are you okay?" prompt, pressing "+15 min" bought fifteen minutes and then —
     * because the escalation check saw "warning already shown, and shown more than
     * fifteen minutes ago" — fired the **emergency alert with no second prompt**.
     * Pressing the reassurance button armed a silent alarm.
     */
    @Test
    fun `extending after a warning re-arms the warning instead of arming an emergency`() {
        val s = session()
        s.markExpired()
        s.markWarningDelivered()
        assertNotNull(s.warningShownAt)
        assertEquals(CheckInStatus.expired, s.status)

        s.extend(15)

        assertNull(
            "an extension that keeps the old warning stamp escalates with no second prompt",
            s.warningShownAt
        )
        assertNull(s.warningSentToContactAt)
        assertEquals(CheckInStatus.active, s.status)

        // And the policy agrees: past the NEW deadline the next step is a warning.
        assertEquals(
            CheckInEscalationPolicy.Decision.Warn,
            CheckInEscalationPolicy.decide(
                nowMs = s.effectiveDeadline + 1,
                deadlineMs = s.effectiveDeadline,
                warningDeliveredAtMs = s.warningShownAt,
            )
        )
    }

    @Test
    fun `expiring tells the contact but does not start the escalation clock`() {
        val s = session()
        s.markExpired()
        assertEquals(CheckInStatus.expired, s.status)
        assertNotNull("the contact is told once", s.warningSentToContactAt)
        assertNull(
            "an undelivered prompt must not start the fifteen-minute clock",
            s.warningShownAt
        )
        assertEquals(
            CheckInEscalationPolicy.Decision.Warn,
            CheckInEscalationPolicy.decide(
                nowMs = s.effectiveDeadline + 60L * 60 * 1000,
                deadlineMs = s.effectiveDeadline,
                warningDeliveredAtMs = s.warningShownAt,
            )
        )
    }

    @Test
    fun `a clock change forgets the warning so the next check re-prompts`() {
        val s = session()
        s.markExpired()
        s.markWarningDelivered()
        s.forgetWarningForClockChange()
        assertNull(s.warningShownAt)
        assertNull(s.warningSentToContactAt)
    }

    /**
     * Null Island. Every Android session was armed at (0.0, 0.0) because the sheet
     * never geocoded the typed address, so the 100 m arrival test measured the
     * distance to a point in the Gulf of Guinea and no check-in ever ended by
     * arriving.
     */
    @Test
    fun `a session with no geocoded destination has no destination fix`() {
        assertFalse(session(lat = 0.0, lon = 0.0).hasDestinationFix)
        assertTrue(session().hasDestinationFix)
        assertTrue("a real coordinate on one axis only still counts",
            session(lat = 0.0, lon = 2.3522).hasDestinationFix)
    }

    @Test
    fun `extra time moves the deadline the escalation is measured against`() {
        val s = session(etaOffsetMs = 10 * 60 * 1000)
        val before = s.effectiveDeadline
        s.extend(15)
        assertEquals(before + 15 * 60 * 1000L, s.effectiveDeadline)
    }
}

/**
 * The mapping from a notification button to a manager call — the table that decides
 * what the user's press actually does.
 */
class CheckInActionMappingTest {

    @Test
    fun `each notification action maps to exactly one user action`() {
        assertEquals(
            CheckInUserAction.CONFIRM_SAFE,
            CheckInActionReceiver.userActionFor(CheckInActionReceiver.ACTION_SAFE)
        )
        assertEquals(
            CheckInUserAction.ARRIVED,
            CheckInActionReceiver.userActionFor(CheckInActionReceiver.ACTION_ARRIVED)
        )
        assertEquals(
            CheckInUserAction.EXTEND,
            CheckInActionReceiver.userActionFor(CheckInActionReceiver.ACTION_EXTEND)
        )
        assertEquals(
            CheckInUserAction.CANCEL,
            CheckInActionReceiver.userActionFor(CheckInActionReceiver.ACTION_CANCEL)
        )
    }

    @Test
    fun `the mapping is injective — no two buttons do the same thing`() {
        val actions = listOf(
            CheckInActionReceiver.ACTION_SAFE,
            CheckInActionReceiver.ACTION_ARRIVED,
            CheckInActionReceiver.ACTION_EXTEND,
            CheckInActionReceiver.ACTION_CANCEL,
        )
        val mapped = actions.map { CheckInActionReceiver.userActionFor(it) }
        assertEquals(
            "two notification buttons resolving to the same call means one of them lies",
            actions.size, mapped.toSet().size
        )
        assertEquals(
            "every CheckInUserAction must be reachable from a notification",
            CheckInUserAction.entries.toSet(), mapped.filterNotNull().toSet()
        )
    }

    @Test
    fun `the deadline alarm is not a user action`() {
        assertNull(
            CheckInActionReceiver.userActionFor(CheckInActionReceiver.ACTION_DEADLINE_TICK)
        )
        assertNull(CheckInActionReceiver.userActionFor(null))
        assertNull(CheckInActionReceiver.userActionFor("com.example.SOMETHING_ELSE"))
    }
}

/**
 * The deadline itself, which used to be `now + 30 minutes` with the comment
 * "For demo". Thirty minutes for a five-minute walk announces a real emergency
 * 25 minutes late; thirty minutes for an hour's drive sends a **false** one while
 * the user is still on the motorway.
 */
class CheckInEtaEstimatorTest {

    // Paris: Notre-Dame → Arc de Triomphe, ~4.63 km great-circle.
    private val NOTRE_DAME_LAT = 48.8530
    private val NOTRE_DAME_LON = 2.3499
    private val ARC_LAT = 48.8738
    private val ARC_LON = 2.2950

    @Test
    fun `great circle distance is right to within a few percent`() {
        val m = CheckInEtaEstimator.greatCircleMetres(
            NOTRE_DAME_LAT, NOTRE_DAME_LON, ARC_LAT, ARC_LON
        )
        // Independently computed: 4634 m. A ±3 % band catches a wrong Earth radius,
        // a degrees/radians slip and a swapped lat/lon, and nothing else.
        assertTrue("expected ~4.63 km, got ${m}m", m in 4_500.0..4_780.0)
    }

    @Test
    fun `zero distance is zero`() {
        assertEquals(
            0.0,
            CheckInEtaEstimator.greatCircleMetres(ARC_LAT, ARC_LON, ARC_LAT, ARC_LON),
            0.001
        )
    }

    @Test
    fun `walking is never faster than driving over the same distance`() {
        val d = 4_000.0
        assertTrue(
            CheckInEtaEstimator.estimateMinutes(d, TransportMode.walking) >
                CheckInEtaEstimator.estimateMinutes(d, TransportMode.driving)
        )
        assertTrue(
            CheckInEtaEstimator.estimateMinutes(d, TransportMode.transit) >=
                CheckInEtaEstimator.estimateMinutes(d, TransportMode.driving)
        )
    }

    @Test
    fun `the estimate grows with distance`() {
        var previous = 0
        listOf(500.0, 2_000.0, 8_000.0, 30_000.0).forEach { d ->
            val minutes = CheckInEtaEstimator.estimateMinutes(d, TransportMode.driving)
            assertTrue("estimate went backwards at ${d}m", minutes >= previous)
            previous = minutes
        }
    }

    @Test
    fun `a walk across the street still gets a floor, not a one-minute deadline`() {
        assertEquals(
            CheckInEtaEstimator.MIN_MINUTES,
            CheckInEtaEstimator.estimateMinutes(20.0, TransportMode.walking)
        )
    }

    @Test
    fun `a garbage coordinate cannot prevent a session from arming`() {
        assertEquals(
            CheckInEtaEstimator.MIN_MINUTES,
            CheckInEtaEstimator.estimateMinutes(Double.NaN, TransportMode.driving)
        )
        assertEquals(
            CheckInEtaEstimator.MIN_MINUTES,
            CheckInEtaEstimator.estimateMinutes(-1.0, TransportMode.driving)
        )
    }

    @Test
    fun `an absurd distance is clamped rather than producing a two-day deadline`() {
        assertEquals(
            CheckInEtaEstimator.MAX_MINUTES,
            CheckInEtaEstimator.estimateMinutes(5_000_000.0, TransportMode.walking)
        )
    }

    /**
     * Rounding DOWN would pre-select a deadline shorter than the journey, which is
     * the false-alarm direction. Every snap must be >= the estimate.
     */
    @Test
    fun `snapping to an offered duration always rounds up`() {
        (1..300).forEach { minutes ->
            val snapped = CheckInEtaEstimator.snapToOffered(minutes)
            assertTrue("snap($minutes) = $snapped is offered",
                snapped in CheckInEtaEstimator.OFFERED_MINUTES)
            if (minutes <= CheckInEtaEstimator.OFFERED_MINUTES.last()) {
                assertTrue(
                    "snap($minutes) = $snapped is SHORTER than the estimate — false alarm",
                    snapped >= minutes
                )
            }
        }
    }

    @Test
    fun `the offered durations are sorted and start at or above the floor`() {
        assertEquals(
            CheckInEtaEstimator.OFFERED_MINUTES.sorted(),
            CheckInEtaEstimator.OFFERED_MINUTES
        )
        assertTrue(
            CheckInEtaEstimator.OFFERED_MINUTES.first() >= CheckInEtaEstimator.MIN_MINUTES
        )
    }

    @Test
    fun `a real cross-city walk lands nowhere near the old hardcoded thirty minutes`() {
        // The point of the whole change: the fixed 30 minutes was wrong in both
        // directions, and the estimate is not.
        val walk = CheckInEtaEstimator.estimateMinutes(
            NOTRE_DAME_LAT, NOTRE_DAME_LON, ARC_LAT, ARC_LON, TransportMode.walking
        )
        assertTrue("a 4.6 km walk should be well over an hour, got $walk min", walk in 70..110)
        val drive = CheckInEtaEstimator.estimateMinutes(
            NOTRE_DAME_LAT, NOTRE_DAME_LON, ARC_LAT, ARC_LON, TransportMode.driving
        )
        assertTrue("a 4.6 km urban drive should be ~13 min, got $drive min", drive in 9..18)
        assertTrue(
            "the whole point: one fixed 30 minutes cannot serve both of these",
            walk > 30 && drive < 30
        )
    }
}
