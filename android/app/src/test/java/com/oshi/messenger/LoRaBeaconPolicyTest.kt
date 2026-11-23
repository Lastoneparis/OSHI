package com.oshi.messenger

import com.oshi.messenger.service.lora.LoRaPositionBeacon
import com.oshi.messenger.service.lora.LoRaPositionBeacon.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __LORA_POSITION_BEACON_2026_10_28__ the POLICY of the position beacon.
 *
 * `LoRaPositionBeaconTest` covers the bytes. This covers when they are allowed to leave,
 * which is the half that cannot be tested any other way: every branch below is a rule about
 * a clock, a radio and scarce airtime, and the alternative to a pure function is standing
 * outdoors with two radios and a stopwatch once per branch.
 *
 * The rules are Meshtastic's device defaults, because this mesh is shared with its clients:
 * `position_broadcast_secs` = 900, smart-position suppression when stationary, and a
 * ceiling so a parked node still announces itself to a neighbour that joined later.
 */
class LoRaBeaconPolicyTest {

    private val GENEVA = 46.2044 to 6.1432
    private val NOW = 1_793_200_000_000L

    /** Everything open, nothing sent yet — the state right after the switch goes on. */
    private fun decide(
        enabled: Boolean = true,
        radioReady: Boolean = true,
        hasPermission: Boolean = true,
        lat: Double? = GENEVA.first,
        lon: Double? = GENEVA.second,
        nowMs: Long = NOW,
        lastSentAtMs: Long = 0L,
        lastLat: Double = 0.0,
        lastLon: Double = 0.0,
    ) = LoRaPositionBeacon.decide(
        enabled, radioReady, hasPermission, lat, lon, nowMs, lastSentAtMs, lastLat, lastLon,
    )

    // ---------------------------------------------------------------- the gates, in order

    @Test
    fun `the switch is checked before anything else`() {
        // Not a style point. If `enabled` were checked after the permission or the fix,
        // this would ask the phone for a fine location on behalf of a feature the user
        // turned off — something a privacy label has to declare and a reviewer will find.
        assertEquals(
            Decision.DISABLED,
            decide(enabled = false, radioReady = false, hasPermission = false, lat = null, lon = null),
        )
    }

    @Test
    fun `nothing beacons without a radio, a permission, or a fix`() {
        assertEquals(Decision.NO_RADIO, decide(radioReady = false))
        assertEquals(Decision.NO_PERMISSION, decide(hasPermission = false))
        assertEquals(Decision.NO_FIX, decide(lat = null, lon = null))
        assertEquals(Decision.NO_FIX, decide(lat = GENEVA.first, lon = null))
    }

    @Test
    fun `a zero-zero fix is refused, not sent`() {
        // 0,0 is what a receiver with no lock reports and what an un-primed mock provider
        // hands back. Beaconing it puts this node in the Gulf of Guinea on every map that
        // hears it, with nothing red anywhere — the same defect the receive path guards.
        assertEquals(Decision.NO_FIX, decide(lat = 0.0, lon = 0.0))
        assertNotEquals("0 longitude alone is Greenwich, a real place", Decision.NO_FIX,
            decide(lat = 51.4779, lon = 0.0))
    }

    @Test
    fun `an out-of-range coordinate is refused`() {
        assertEquals(Decision.NO_FIX, decide(lat = 91.0, lon = 6.0))
        assertEquals(Decision.NO_FIX, decide(lat = 46.0, lon = 181.0))
        assertEquals(Decision.NO_FIX, decide(lat = -90.1, lon = 6.0))
    }

    // ---------------------------------------------------------------- airtime

    @Test
    fun `the first beacon of a session goes out immediately`() {
        assertEquals(Decision.SEND, decide(lastSentAtMs = 0L))
    }

    @Test
    fun `inside the interval nothing goes out, however far we moved`() {
        // Moving does NOT shorten the interval. One LongFast frame holds the channel for a
        // good fraction of a second and everyone in range hears it, so a phone in a car
        // must not be able to flood the mesh.
        assertEquals(
            Decision.TOO_SOON,
            decide(
                nowMs = NOW, lastSentAtMs = NOW - LoRaPositionBeacon.INTERVAL_MS + 1,
                lastLat = 48.8566, lastLon = 2.3522,   // 400 km away
            ),
        )
    }

    @Test
    fun `past the interval a node that has not moved stays quiet`() {
        val lastSent = NOW - LoRaPositionBeacon.INTERVAL_MS - 1
        // 30 m away: inside MIN_MOVE_M, so it is the same place.
        assertEquals(
            Decision.STILL_HERE,
            decide(lastSentAtMs = lastSent, lastLat = 46.2041, lastLon = 6.1432),
        )
        // 1 km away: worth a frame.
        assertEquals(
            Decision.SEND,
            decide(lastSentAtMs = lastSent, lastLat = 46.2134, lastLon = 6.1432),
        )
    }

    @Test
    fun `the movement gate has a ceiling so a parked node is not invisible`() {
        // THE POINT OF MAX_SILENCE_MS. Smart suppression with no ceiling makes a node that
        // never moves disappear from the map of every neighbour that joined or restarted
        // since its last beacon — the map is built from what was heard, not from a registry.
        assertEquals(
            Decision.SEND,
            decide(
                lastSentAtMs = NOW - LoRaPositionBeacon.MAX_SILENCE_MS,
                lastLat = GENEVA.first, lastLon = GENEVA.second,   // did not move an inch
            ),
        )
        assertEquals(
            "one millisecond under the ceiling is still the movement rule",
            Decision.STILL_HERE,
            decide(
                lastSentAtMs = NOW - LoRaPositionBeacon.MAX_SILENCE_MS + 1,
                lastLat = GENEVA.first, lastLon = GENEVA.second,
            ),
        )
    }

    @Test
    fun `a clock that went backwards cannot mute the beacon`() {
        // An NTP step, a restored backup or a phone whose user changed the date by hand all
        // produce now < lastSent. Treating that as "too soon" would silence the node until
        // the clock caught up — potentially hours, with the switch showing ON.
        assertEquals(Decision.SEND, decide(nowMs = NOW, lastSentAtMs = NOW + 86_400_000L))
    }

    // ---------------------------------------------------------------- the constants

    @Test
    fun `the interval matches Meshtastic's own device default`() {
        assertEquals(
            "position_broadcast_secs defaults to 900 s in Meshtastic's config; a shorter " +
                "interval spends airtime that every other node on the channel shares",
            15 * 60 * 1000L, LoRaPositionBeacon.INTERVAL_MS,
        )
        assertTrue(
            "the ceiling must be LONGER than the interval or the movement gate is dead code",
            LoRaPositionBeacon.MAX_SILENCE_MS > LoRaPositionBeacon.INTERVAL_MS,
        )
        assertTrue(
            "the tick has to be shorter than the interval or a beacon is late by a tick",
            LoRaPositionBeacon.TICK_MS < LoRaPositionBeacon.INTERVAL_MS,
        )
    }
}

/**
 * __LORA_POSITION_BEACON_2026_10_28__ The SAME table of inputs on both platforms, reduced
 * to ONE digest.
 *
 * Why this and not a pair of tests with matching names: `golden-file-beats-shared-test-names`
 * is a lesson this repository has already paid for — two cores with identically named tests
 * produced "Donateur 310" on Android and "253" on iOS, and nothing was red. A test name is
 * a claim about intent; it says nothing about the value that came out.
 *
 * So the table below is the contract. Each row is formatted with its inputs AND the
 * decision they produced, the rows are joined, and the SHA-256 of the whole thing is
 * compared to one constant. `LoRaBeaconPolicyTests.swift` holds the same table, the same
 * formatting and the same constant, so:
 *
 *  - a rule that changes on one platform only ⇒ both digests move apart, one test goes red;
 *  - an INPUT edited on one platform only ⇒ same thing, because the inputs are hashed too;
 *  - a rule that changes on purpose ⇒ both tests go red together and the constant is
 *    updated once, deliberately, in two files in the same commit.
 *
 * `%.7f` with `Locale.ROOT` is not decoration: Kotlin's `String.format` follows the default
 * locale, so on a French machine the latitudes would be written with commas and this would
 * disagree with iOS for a reason that has nothing to do with the policy.
 */
class LoRaBeaconCrossPlatformTest {

    /** Kept identical, row for row, to `LoRaBeaconPolicyTests.vectors` on iOS. */
    private data class V(
        val enabled: Boolean, val ready: Boolean, val perm: Boolean,
        val lat: Double?, val lon: Double?,
        val now: Long, val lastSent: Long, val lastLat: Double, val lastLon: Double,
    )

    private val NOW = 1_793_200_000_000L
    private val I = LoRaPositionBeacon.INTERVAL_MS
    private val MAX = LoRaPositionBeacon.MAX_SILENCE_MS

    private fun vectors(): List<V> = listOf(
        // the gates, in order
        V(false, false, false, null, null, NOW, 0L, 0.0, 0.0),
        V(true, false, true, 46.2044, 6.1432, NOW, 0L, 0.0, 0.0),
        V(true, true, false, 46.2044, 6.1432, NOW, 0L, 0.0, 0.0),
        V(true, true, true, null, null, NOW, 0L, 0.0, 0.0),
        V(true, true, true, 0.0, 0.0, NOW, 0L, 0.0, 0.0),
        V(true, true, true, 91.0, 6.0, NOW, 0L, 0.0, 0.0),
        V(true, true, true, 46.0, 181.0, NOW, 0L, 0.0, 0.0),
        V(true, true, true, -90.0001, 6.0, NOW, 0L, 0.0, 0.0),
        // Greenwich: longitude 0 is a real place and must NOT be read as "no lock"
        V(true, true, true, 51.4779, 0.0, NOW, 0L, 0.0, 0.0),
        V(true, true, true, 46.2044, 6.1432, NOW, 0L, 0.0, 0.0),
        // the interval
        V(true, true, true, 46.2044, 6.1432, NOW, NOW - I + 1, 48.8566, 2.3522),
        V(true, true, true, 46.2044, 6.1432, NOW, NOW - I, 46.2044, 6.1432),
        // the movement gate, either side of 100 m at this latitude (1° ≈ 111.2 km)
        V(true, true, true, 46.2044, 6.1432, NOW, NOW - I - 1, 46.2044 - 0.00089, 6.1432),
        V(true, true, true, 46.2044, 6.1432, NOW, NOW - I - 1, 46.2044 - 0.00091, 6.1432),
        V(true, true, true, 46.2044, 6.1432, NOW, NOW - I - 1, 46.2134, 6.1432),
        // the ceiling
        V(true, true, true, 46.2044, 6.1432, NOW, NOW - MAX, 46.2044, 6.1432),
        V(true, true, true, 46.2044, 6.1432, NOW, NOW - MAX + 1, 46.2044, 6.1432),
        // a clock that went backwards
        V(true, true, true, 46.2044, 6.1432, NOW, NOW + 86_400_000L, 46.2044, 6.1432),
        // the switch wins over a live session
        V(false, true, true, 46.2044, 6.1432, NOW, NOW - 1, 46.2044, 6.1432),
        // southern and eastern signs, which an sfixed32 bug would get wrong
        V(true, true, true, -33.8688, 151.2093, NOW, NOW - I - 1, -33.8688, 151.2093),
        V(true, true, true, -33.8688, -70.6693, NOW, NOW - I - 1, -33.8688, 151.2093),
    )

    private fun d(v: Double?): String =
        if (v == null) "na" else String.format(java.util.Locale.ROOT, "%.7f", v)

    private fun b(v: Boolean) = if (v) "1" else "0"

    internal fun transcript(): String = vectors().joinToString("\n") { v ->
        val decision = LoRaPositionBeacon.decide(
            v.enabled, v.ready, v.perm, v.lat, v.lon, v.now, v.lastSent, v.lastLat, v.lastLon,
        )
        "${b(v.enabled)}${b(v.ready)}${b(v.perm)}|${d(v.lat)}|${d(v.lon)}|" +
            "${v.now}|${v.lastSent}|${d(v.lastLat)}|${d(v.lastLon)}=>${decision.name}"
    }

    private fun sha256(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `the policy digest matches the one iOS computes`() {
        val t = transcript()
        assertEquals("the table must not lose or gain rows silently", 21, t.lines().size)
        assertEquals(
            "Android and iOS disagree about the beacon policy, OR a rule changed on " +
                "purpose. In the second case update BOTH this constant and the one in " +
                "OSHITests/LoRaBeaconPolicyTests.swift, in the same commit.\n" + t,
            POLICY_DIGEST, sha256(t),
        )
    }

    companion object {
        /**
         * SHA-256 of the transcript above. The SAME literal appears in
         * `OSHITests/LoRaBeaconPolicyTests.swift`.
         */
        const val POLICY_DIGEST =
            "28bbeb82edc14c3c8aff1afa9792a18077b9a0b70e6ca88481a21fd7d6774f1d"
    }
}
