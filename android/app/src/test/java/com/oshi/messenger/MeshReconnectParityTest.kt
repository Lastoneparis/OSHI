package com.oshi.messenger

import com.oshi.messenger.network.mesh.MeshReconnectPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __NOTHING_EVER_DIALLED_A_PEER_TWICE_2026_11_07__
 *
 * The reconnect policy exists twice — `MeshReconnectPolicy.kt` here and
 * `enum MeshReconnectPolicy` in `OSHI/CrossPlatformMesh.swift` — because the
 * two platforms cannot share code. Two phones in the same room running
 * different backoff ladders is the kind of asymmetry that produces a bug
 * report nobody can reproduce, so the numbers are read out of the Swift source
 * and compared.
 *
 * Same idea as `PollParityTest`, which reads `OSHI/PollMessage.swift`.
 */
class MeshReconnectParityTest {

    private val swift: String by lazy {
        val f = listOf(File("../OSHI/CrossPlatformMesh.swift"), File("../../OSHI/CrossPlatformMesh.swift"))
            .firstOrNull { it.exists() }
        assertTrue("OSHI/CrossPlatformMesh.swift not found from ${File(".").absolutePath}", f != null)
        f!!.readText()
    }

    private fun swiftInt(name: String): Int {
        val m = Regex("""static let $name:\s*Int\s*=\s*([0-9_]+)""").find(swift)
        assertTrue("`static let $name: Int` missing from OSHI/CrossPlatformMesh.swift", m != null)
        return m!!.groupValues[1].replace("_", "").toInt()
    }

    @Test
    fun theSweepRunsAtTheSameCadenceOnBothPlatforms() {
        assertEquals(
            "a sweep interval that differs between iOS and Android makes the same two phones " +
                "behave differently in the same room",
            MeshReconnectPolicy.SWEEP_INTERVAL_MS.toInt(), swiftInt("sweepIntervalMs"),
        )
    }

    @Test
    fun theDiscoveryRestartFloorIsTheSameOnBothPlatforms() {
        assertEquals(
            MeshReconnectPolicy.REDISCOVER_MIN_INTERVAL_MS.toInt(), swiftInt("rediscoverMinIntervalMs"),
        )
    }

    @Test
    fun theBackoffLaddersAreIdentical() {
        val m = Regex("""static let backoffMs:\s*\[Int]\s*=\s*\[([^]]*)]""").find(swift)
        assertTrue("`static let backoffMs: [Int]` missing from OSHI/CrossPlatformMesh.swift", m != null)
        val ios = m!!.groupValues[1]
            .split(",")
            .map { it.trim().replace("_", "") }
            .filter { it.isNotEmpty() }
            .map { it.toInt() }
        val android = MeshReconnectPolicy.BACKOFF_MS.map { it.toInt() }
        assertEquals("the two ladders must climb the same way", android, ios)
    }

    @Test
    fun bothPoliciesOfferTheSameFourOutcomes() {
        // A platform missing `rediscover` would silently never ask discovery
        // again for a peer seen over BLE with no address — the case that needs
        // it most.
        for (case in listOf("alreadyConnected", "dial", "rediscover", "wait")) {
            assertTrue(
                "OSHI/CrossPlatformMesh.swift must keep `case $case` in MeshReconnectPolicy.Attempt",
                Regex("""case\s+$case\b""").containsMatchIn(swift),
            )
        }
        assertEquals(
            setOf("ALREADY_CONNECTED", "DIAL", "REDISCOVER", "WAIT"),
            MeshReconnectPolicy.Attempt.values().map { it.name }.toSet(),
        )
    }

    @Test
    fun bothSidesRankADialAddressTheSameWay() {
        // The iOS copy lives in Swift and is tested there; what this pins is
        // that it EXISTS and is the thing the resolver calls, so a future
        // "prefer IPv4, break" cannot come back on one platform only.
        assertTrue(
            "OSHI/CrossPlatformMesh.swift must keep MeshReconnectPolicy.pickDialAddress",
            swift.contains("static func pickDialAddress("),
        )
        assertTrue(
            "and netServiceDidResolveAddress must use it",
            swift.contains("MeshReconnectPolicy.pickDialAddress(candidates)"),
        )
    }
}
