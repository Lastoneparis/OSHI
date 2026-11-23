package com.oshi.messenger.service

import com.oshi.messenger.data.model.CheckInStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the decision table that gates arming a check-in, and the lifetime of the
 * `location` foreground service that hosts one.
 *
 * These are the two rules that, if broken, turn the safety feature into
 * decoration without any visible symptom:
 *   1. __NO_BACKGROUND_LOCATION_2026_10_24__ the app must never ask for
 *      ACCESS_BACKGROUND_LOCATION. Google Play rejected OSHI on 2026-10-24 for
 *      holding it without a prominent disclosure, so the manifest removes it —
 *      and a permission that is not declared is denied without a dialog, so a
 *      request for it would be an invisible dead end, not an error.
 *   2. The service must run for exactly `status == active` (or `expired`) and
 *      nothing wider — mirroring iOS, which flips
 *      `allowsBackgroundLocationUpdates` ON at OSHI/CheckInManager.swift:236 and
 *      OFF again at :256.
 */
class CheckInPermissionPolicyTest {

    /** The string Play looks for, spelled out so this test fails on a re-add. */
    private val BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"

    // MARK: - The permission that must never be asked for

    @Test
    fun `the request never contains background location`() {
        val asked = CheckInPermissionPolicy.foregroundPermissions()
        assertFalse(
            "ACCESS_BACKGROUND_LOCATION is not declared in the manifest (Play " +
                "rejection 2026-10-24); asking for it is a silent denial",
            asked.contains(BACKGROUND_LOCATION)
        )
        assertTrue(asked.contains(CheckInPermissionPolicy.PERMISSION_FINE_LOCATION))
        assertTrue(asked.contains(CheckInPermissionPolicy.PERMISSION_COARSE_LOCATION))
        assertEquals("fine + coarse, nothing else", 2, asked.size)
    }

    @Test
    fun `no decision can route the UI to a background request`() {
        // Every reachable combination, so a future case added to the enum cannot
        // quietly re-introduce a second step.
        val reachable = listOf(false, true).flatMap { granted ->
            listOf(false, true).map { asked ->
                CheckInPermissionPolicy.decide(
                    hasForegroundLocation = granted,
                    foregroundAlreadyRequested = asked,
                )
            }
        }
        assertEquals(
            setOf(
                CheckInArmingDecision.READY,
                CheckInArmingDecision.REQUEST_FOREGROUND,
                CheckInArmingDecision.BLOCKED_NO_LOCATION,
            ),
            reachable.toSet()
        )
        assertEquals(
            "the enum itself must offer no background outcome",
            3, CheckInArmingDecision.entries.size
        )
    }

    // MARK: - The one dialog

    @Test
    fun `asks for location when nothing is granted`() {
        assertEquals(
            CheckInArmingDecision.REQUEST_FOREGROUND,
            CheckInPermissionPolicy.decide(
                hasForegroundLocation = false,
                foregroundAlreadyRequested = false,
            )
        )
    }

    @Test
    fun `does not re-launch the dialog once it has come back refused`() {
        // Relaunching a dialog the platform has stopped surfacing would leave the
        // app waiting for a result that never changes.
        assertEquals(
            CheckInArmingDecision.BLOCKED_NO_LOCATION,
            CheckInPermissionPolicy.decide(
                hasForegroundLocation = false,
                foregroundAlreadyRequested = true,
            )
        )
    }

    // MARK: - Refusal is handled honestly, not silently

    @Test
    fun `location refusal blocks arming instead of arming a dead check-in`() {
        assertEquals(
            "a check-in with no position can neither detect arrival nor fill the " +
                "emergency alert, so it must not arm",
            CheckInArmingDecision.BLOCKED_NO_LOCATION,
            CheckInPermissionPolicy.decide(
                hasForegroundLocation = false,
                foregroundAlreadyRequested = true,
            )
        )
    }

    // MARK: - Ready states

    @Test
    fun `ready as soon as location is granted`() {
        assertEquals(
            CheckInArmingDecision.READY,
            CheckInPermissionPolicy.decide(
                hasForegroundLocation = true,
                foregroundAlreadyRequested = true,
            )
        )
        // And on the very first attempt, before any dialog: a user who granted
        // location for the map must not be asked again to arm a check-in.
        assertEquals(
            CheckInArmingDecision.READY,
            CheckInPermissionPolicy.decide(
                hasForegroundLocation = true,
                foregroundAlreadyRequested = false,
            )
        )
    }
}

/**
 * The foreground service must exist for exactly as long as the armed session —
 * a `location`-typed service left running after a journey ends is the Android
 * equivalent of the iOS "OSHI used your location in the background" complaint
 * that OSHI/CheckInManager.swift:60-74 was written to fix.
 */
class CheckInServicePolicyTest {

    @Test
    fun `service runs while a session is active`() {
        assertTrue(CheckInServicePolicy.shouldServiceRun(CheckInStatus.active))
    }

    @Test
    fun `service does not run with no session at all`() {
        assertFalse(CheckInServicePolicy.shouldServiceRun(null))
    }

    @Test
    fun `every terminal status tears the service down`() {
        val terminal = listOf(
            CheckInStatus.arrived,
            CheckInStatus.cancelled,
            CheckInStatus.emergency,
        )
        terminal.forEach { status ->
            assertFalse(
                "$status must not keep a location foreground service alive",
                CheckInServicePolicy.shouldServiceRun(status)
            )
            assertTrue(
                "$status must stop the service",
                CheckInServicePolicy.shouldStopServiceOn(status)
            )
        }
    }

    /**
     * 🔴 Changed 2026-08-24 (off-ramp pass). `expired` used to be listed as terminal
     * above, which was wrong in two ways at once:
     *
     *  * the emergency alert's payload is the user's LAST KNOWN POSITION, and it is
     *    this service that keeps the location stream that produces it alive. Killing
     *    it at `expired` means the alert that eventually fires carries a stale fix;
     *  * `expired` is the fifteen minutes in which the user can still press "I'm
     *    safe". Tearing the session's host down there removes the off-ramp at the
     *    exact moment it matters.
     *
     * iOS never stops monitoring at the warning either — only `stopCheckIn` and
     * `triggerEmergency` do (`OSHI/CheckInManager.swift:330-360`).
     */
    @Test
    fun `expired keeps the service alive because the user can still answer`() {
        assertTrue(
            "expired is the window where the user says 'I'm safe' — the service must survive it",
            CheckInServicePolicy.shouldServiceRun(CheckInStatus.expired)
        )
        assertFalse(CheckInServicePolicy.shouldStopServiceOn(CheckInStatus.expired))
    }

    @Test
    fun `no status outside active and expired keeps the service running`() {
        // Guards against a future CheckInStatus case being added and silently
        // inheriting "keep the background service up".
        val keepsRunning = CheckInStatus.entries.filter { CheckInServicePolicy.shouldServiceRun(it) }
        assertEquals(listOf(CheckInStatus.active, CheckInStatus.expired), keepsRunning)
    }
}
