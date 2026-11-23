package com.oshi.messenger.service.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** __RELAY_FIRST_UPGRADE_2026_09_23__ — mirrors iOS `WbAdpcmCodecTests.testDirectPathPolicyConstants`. */
class RelayFirstUpgradeTest {

    @Test
    fun compactMediaIsVerifiedWithAnUnfragmentedProbe() {
        assertEquals(1200, P2PPathPolicy.probeBytesFor(compactMedia = true))
        assertEquals(P2PPathPolicy.JUMBO_PROBE_BYTES, P2PPathPolicy.probeBytesFor(compactMedia = false))
        // A 1200 B pong proves a 200 B WB-ADPCM frame and a ≤1151 B video chunk.
        assertTrue(P2PPathPolicy.COMPACT_PROBE_BYTES >= 1151)
    }

    @Test
    fun directFallsBackAfterAboutOneSecondOfLostPongs() {
        val now = 100_000L
        assertTrue(P2PPathPolicy.consentFresh(now - 400, rttMs = 60, nowMs = now))
        assertTrue(P2PPathPolicy.consentFresh(now - 1_050, rttMs = 60, nowMs = now))
        assertFalse(P2PPathPolicy.consentFresh(now - 1_100, rttMs = 60, nowMs = now))
        assertFalse(P2PPathPolicy.consentFresh(0L, rttMs = 0, nowMs = now))
    }

    @Test
    fun upgradeOverlapIsShortAndOnlyForCompactFrames() {
        val now = 50_000L
        val until = now + P2PPathPolicy.UPGRADE_OVERLAP_MS
        assertTrue(P2PPathPolicy.upgradeOverlapActive(until, now + 500, 200))
        assertFalse(P2PPathPolicy.upgradeOverlapActive(until, now + 1_001, 200))
        assertFalse("raw PCM is never duplicated", P2PPathPolicy.upgradeOverlapActive(until, now + 10, 1957))
    }
}
