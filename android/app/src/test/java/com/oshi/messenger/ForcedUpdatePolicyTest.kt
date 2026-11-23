package com.oshi.messenger

import com.oshi.messenger.update.ForcedUpdatePolicy
import com.oshi.messenger.update.ForcedUpdatePolicy.Decision
import com.oshi.messenger.update.ForcedUpdatePolicy.PlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * __FORCED_UPDATE_ANDROID_2026_10_13__
 *
 * The blocking update gate. Every one of these runs on the plain JVM, because
 * the whole decision was deliberately written as a pure function: a lock-out is
 * the one bug you cannot ship and then observe, since the users it hits are by
 * definition the users who can no longer open the app to complain.
 */
class ForcedUpdatePolicyTest {

    private val CURRENT = "1.6.38"

    // =====================================================================
    // Fail open. These are the assertions that matter most.
    // =====================================================================

    /** No floor published at all — the state of the live server today. */
    @Test
    fun noFloorsNeverBlocks() {
        assertEquals(
            Decision.NONE,
            ForcedUpdatePolicy.decide("1.6.30", null, null, PlayState.AVAILABLE)
        )
    }

    /**
     * The server is unreachable, so BOTH floors arrive as null. An ancient build
     * with an update sitting ready is still not blocked, because nothing
     * authoritative asked for it.
     */
    @Test
    fun unreachableServerNeverBlocks() {
        assertEquals(
            Decision.NONE,
            ForcedUpdatePolicy.decide("1.0.1", null, null, PlayState.AVAILABLE)
        )
    }

    /**
     * Below the blocking floor, but Play has nothing to install — no GMS, a
     * sideload, a region that has not got the release. Blocking here would
     * demand something the user cannot do, so it degrades to the banner.
     */
    @Test
    fun belowFloorWithoutPlayOffersInsteadOfBlocking() {
        assertEquals(
            Decision.OFFER,
            ForcedUpdatePolicy.decide("1.6.30", null, "1.6.38", PlayState.NOT_AVAILABLE)
        )
    }

    /** An unparseable floor is no floor, not a floor of zero. */
    @Test
    fun unparseableFloorIsNoGate() {
        assertNull(ForcedUpdatePolicy.parseBlockingVersion("""{"android_blocking_update_version":"latest"}"""))
        assertNull(ForcedUpdatePolicy.parseBlockingVersion("""{"android_blocking_update_version":"1.6.39 (80)"}"""))
        assertNull(ForcedUpdatePolicy.parseBlockingVersion("""{"android_blocking_update_version":""}"""))
        assertNull(ForcedUpdatePolicy.parseBlockingVersion("""{"android_blocking_update_version":null}"""))
        assertNull(ForcedUpdatePolicy.parseBlockingVersion("not json at all"))
        assertNull(ForcedUpdatePolicy.parseBlockingVersion(null))
        assertNull(ForcedUpdatePolicy.parseBlockingVersion(""))
    }

    /** The live config body, verbatim, must produce no blocking floor. */
    @Test
    fun theLiveConfigBodyBlocksNobody() {
        val live = """{"v2_enabled":true,"rollout_percent":100,"min_build":0,""" +
            """"devsync_enabled":true,"devsync_relay_enabled":true,""" +
            """"devsync_lan_enabled":true,"device_mailbox_enabled":true,""" +
            """"group_legacy_compat":true}"""
        assertNull(ForcedUpdatePolicy.parseBlockingVersion(live))
    }

    // =====================================================================
    // The two keys are genuinely separate
    // =====================================================================

    /**
     * The reason the blocking floor is its own key. A nag floor set to pull the
     * 1.6.36 users forward must NOT turn into a wall on the builds that carry
     * this code — one decision doing two different things to two populations is
     * exactly the trap.
     */
    @Test
    fun theNagFloorAloneCanNeverBlock() {
        assertEquals(
            Decision.OFFER,
            ForcedUpdatePolicy.decide("1.6.36", "1.6.38", null, PlayState.AVAILABLE)
        )
    }

    /** And the blocking key is not read out of the nag key's name. */
    @Test
    fun theNagKeyDoesNotFeedTheBlockingFloor() {
        assertNull(
            ForcedUpdatePolicy.parseBlockingVersion(
                """{"android_min_supported_version":"1.6.38"}"""
            )
        )
    }

    // =====================================================================
    // It does block when it is supposed to
    // =====================================================================

    @Test
    fun belowBlockingFloorWithPlayReadyBlocks() {
        assertEquals(
            Decision.BLOCK,
            ForcedUpdatePolicy.decide("1.6.36", "1.6.38", "1.6.38", PlayState.AVAILABLE)
        )
    }

    /** An interrupted immediate update is resumed whatever the floors say. */
    @Test
    fun interruptedUpdateIsAlwaysResumed() {
        assertEquals(
            Decision.BLOCK,
            ForcedUpdatePolicy.decide(CURRENT, null, null, PlayState.IN_PROGRESS)
        )
    }

    /** At the floor exactly is not below it. */
    @Test
    fun exactlyAtTheFloorIsNotBlocked() {
        assertEquals(
            Decision.NONE,
            ForcedUpdatePolicy.decide("1.6.38", "1.6.38", "1.6.38", PlayState.AVAILABLE)
        )
    }

    @Test
    fun aheadOfTheFloorIsNotBlocked() {
        assertEquals(
            Decision.NONE,
            ForcedUpdatePolicy.decide("1.6.39", "1.6.38", "1.6.38", PlayState.AVAILABLE)
        )
    }

    /**
     * The lexicographic trap, on the blocking path this time. `"1.6.9"` is
     * BELOW `"1.6.38"` numerically and above it as a string, so a string
     * compare would leave the most out-of-date users as the only ones unblocked.
     */
    @Test
    fun theStringCompareBugWouldInvertTheBlock() {
        assertEquals(
            Decision.BLOCK,
            ForcedUpdatePolicy.decide("1.6.9", null, "1.6.38", PlayState.AVAILABLE)
        )
        // and the proof that this is not what a naive compare does
        assert("1.6.9" > "1.6.38")
    }

    /** A real floor is read, parsed and kept. */
    @Test
    fun aRealFloorParses() {
        assertEquals(
            "1.6.38",
            ForcedUpdatePolicy.parseBlockingVersion(
                """{"v2_enabled":true,"android_blocking_update_version":"1.6.38"}"""
            )
        )
    }

    /** Our own version being unreadable must not block us. */
    @Test
    fun anUnreadableOwnVersionDoesNotBlock() {
        assertEquals(
            Decision.NONE,
            ForcedUpdatePolicy.decide("unknown", "1.6.38", "1.6.38", PlayState.AVAILABLE)
        )
    }
}
