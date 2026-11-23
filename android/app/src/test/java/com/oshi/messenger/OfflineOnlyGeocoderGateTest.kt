package com.oshi.messenger

import com.oshi.messenger.service.map.PlatformGeocoderGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "Offline maps only" gate in front of `android.location.Geocoder`.
 *
 * ## Why this is a privacy test, not a UX test
 *
 * `android.location.Geocoder` is a **Google network service** on a stock device.
 * `getFromLocationName(query, n)` sends the typed address to Google;
 * `getFromLocation(lat, lon, n)` sends the user's coordinates. "Offline maps
 * only" promises exactly that this does not happen. Every other consumer in the
 * map stack was gated on that promise and `ItineraryScreen` was not — recorded in
 * `parity/verify/2026-08-23-map.md` §5 as the highest-priority open item.
 *
 * ## What is proven here, and what is NOT
 *
 * PROVEN (JVM): the decision function never authorises a geocode while
 * offline-only is on, for any input; and no source file in the whole main source
 * set constructs a `Geocoder` outside the census below.
 *
 * NOT PROVEN: that no packet leaves the device. That needs a proxy on a live
 * emulator with the toggle flipped both ways, which was not available this round
 * (see the report). This is a **structural** proof.
 */
class OfflineOnlyGeocoderGateTest {

    // ───────────────────────────────────────────────────────────────────
    // The decision itself
    // ───────────────────────────────────────────────────────────────────

    /**
     * The whole privacy guarantee in one assertion, over a spread of inputs that
     * includes the shapes most likely to slip through a length check: leading and
     * trailing whitespace, unicode, digits, punctuation, and a very long string.
     */
    @Test
    fun `offline-only never authorises a geocode, for any query`() {
        val queries = listOf(
            "a", "ab", "Paris", "  Paris  ", "12 rue de Rivoli",
            "東京駅", "Кремль", "l'église", "saint-denis",
            "%", "'; DROP TABLE --", "\n\t", "?",
            "x".repeat(4096)
        )
        for (q in queries) {
            val d = PlatformGeocoderGate.decide(q, offlineOnly = true)
            assertNotEquals(
                "offline-only authorised a network geocode for <$q>",
                PlatformGeocoderGate.Decision.Geocode,
                d
            )
        }
    }

    @Test
    fun `a real query with offline-only ON is refused, and says so`() {
        assertEquals(
            PlatformGeocoderGate.Decision.BlockedOfflineOnly,
            PlatformGeocoderGate.decide("Gare de Lyon", offlineOnly = true)
        )
    }

    @Test
    fun `the same query with offline-only OFF is allowed through`() {
        assertEquals(
            PlatformGeocoderGate.Decision.Geocode,
            PlatformGeocoderGate.decide("Gare de Lyon", offlineOnly = false)
        )
    }

    /**
     * A one-character query is Idle in BOTH modes: the user must not be shown a
     * privacy warning for having typed a single letter, and a single letter was
     * never geocoded anyway. Whitespace does not buy length.
     */
    @Test
    fun `too-short queries are idle in both modes and whitespace does not count`() {
        for (offline in listOf(true, false)) {
            assertEquals(
                PlatformGeocoderGate.Decision.Idle,
                PlatformGeocoderGate.decide("", offlineOnly = offline)
            )
            assertEquals(
                PlatformGeocoderGate.Decision.Idle,
                PlatformGeocoderGate.decide("P", offlineOnly = offline)
            )
            assertEquals(
                "a padded single character is still one character",
                PlatformGeocoderGate.Decision.Idle,
                PlatformGeocoderGate.decide("   P   ", offlineOnly = offline)
            )
        }
        // …and the threshold really is 2, in both directions.
        assertEquals(2, PlatformGeocoderGate.MIN_QUERY_LENGTH)
        assertNotEquals(
            PlatformGeocoderGate.Decision.Idle,
            PlatformGeocoderGate.decide("Pa", offlineOnly = false)
        )
    }

    // ───────────────────────────────────────────────────────────────────
    // The census: every Geocoder in the app, and its verdict
    // ───────────────────────────────────────────────────────────────────

    /**
     * `ItineraryScreen` was found by reading it. The point of a census is that the
     * NEXT one is found by the build.
     *
     * This is deliberately not an allowlist that suppresses what it does not
     * recognise: the assertion is on the *whole discovered set*, and anything
     * unclassified is printed and fails.
     */
    private val knownGeocoderSites = mapOf(
        "src/main/java/com/oshi/messenger/ui/screens/ItineraryScreen.kt" to
            "GATED (this pass) — PlatformGeocoderGate refuses before construction",
        "src/main/java/com/oshi/messenger/network/location/LocationSharingManager.kt" to
            "UNGATED — reverse-geocodes the user's own coordinates. Outside this " +
            "pass's territory (network/location/**); reported, not fixed.",
        // Added by the check-in off-ramp pass (2026-08-24). The sheet has to
        // resolve the typed destination to coordinates — before this it armed
        // every session at (0.0, 0.0) — and that lookup is exactly the one the
        // "Offline maps only" switch promises never leaves the device. Found by
        // this census, which is the point of it.
        "src/main/java/com/oshi/messenger/ui/screens/CheckInScreen.kt" to
            "GATED — PlatformGeocoderGate refuses before construction",
        // __MAP_OPENER_2026_09_23__ Tapping an address in a chat with "OSHI Maps"
        // chosen places it with the Geocoder; refused (with a toast, no web
        // fallback) when "Offline maps only" is on.
        "src/main/java/com/oshi/messenger/ui/components/MapOpener.kt" to
            "GATED — PlatformGeocoderGate refuses before construction"
    )

    /** Files under `src/main/java` that mention `Geocoder(` at all. */
    private fun geocoderSites(): Map<String, String> {
        val root = File("src/main/java")
        assertTrue("main source root not found at ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it to it.readText() }
            .filter { (_, text) -> text.contains("Geocoder(") }
            .associate { (f, text) -> f.path.replace(File.separatorChar, '/') to text }
    }

    @Test
    fun `every Geocoder call site in the app is accounted for`() {
        val found = geocoderSites()
        assertTrue(
            "the scanner found no Geocoder at all — it is looking in the wrong place, " +
                "which would make every other assertion here vacuous",
            found.isNotEmpty()
        )

        val unclassified = found.keys - knownGeocoderSites.keys
        assertTrue(
            "NEW Geocoder call site(s) with no recorded verdict — a Geocoder is a " +
                "Google network service and must be gated on \"Offline maps only\":\n" +
                unclassified.joinToString("\n") { "  $it" },
            unclassified.isEmpty()
        )

        val vanished = knownGeocoderSites.keys - found.keys
        assertTrue(
            "recorded Geocoder site(s) no longer exist — update the census:\n" +
                vanished.joinToString("\n") { "  $it" },
            vanished.isEmpty()
        )
    }

    @Test
    fun `ItineraryScreen reaches the Geocoder only through the gate`() {
        val path = "src/main/java/com/oshi/messenger/ui/screens/ItineraryScreen.kt"
        val text = geocoderSites()[path]
            ?: throw AssertionError("$path no longer constructs a Geocoder — census stale")

        // A CALL, not a mention. Watching mutation B fail proved this matters:
        // stripping the gate entirely still left the words "PlatformGeocoderGate"
        // in a doc comment, and the looser check stayed green over an ungated
        // Geocoder. A test that a comment can satisfy is not a test.
        assertTrue(
            "ItineraryScreen constructs a Geocoder without calling PlatformGeocoderGate.decide",
            text.contains("PlatformGeocoderGate.decide(")
        )
        assertTrue(
            "the gate must be able to refuse — BlockedOfflineOnly is never handled",
            text.contains("Decision.BlockedOfflineOnly")
        )
        assertTrue(
            "a refusal that nothing renders is indistinguishable from a dead field",
            text.contains("itinerary_address_lookup_offline_only")
        )
    }

    /**
     * The check-in sheet's destination lookup, held to the same standard as
     * `ItineraryScreen`'s: a CALL to the gate, a handled refusal, and a rendered
     * explanation — not three mentions in a comment.
     */
    @Test
    fun `CheckInScreen reaches the Geocoder only through the gate`() {
        val path = "src/main/java/com/oshi/messenger/ui/screens/CheckInScreen.kt"
        val text = geocoderSites()[path]
            ?: throw AssertionError("$path no longer constructs a Geocoder — census stale")

        assertTrue(
            "CheckInScreen constructs a Geocoder without calling PlatformGeocoderGate.decide",
            text.contains("PlatformGeocoderGate.decide(")
        )
        assertTrue(
            "the gate must be able to refuse — BlockedOfflineOnly is never handled",
            text.contains("Decision.BlockedOfflineOnly")
        )
        assertTrue(
            "a refusal that nothing renders is indistinguishable from a dead field",
            text.contains("itinerary_address_lookup_offline_only")
        )
    }

    /**
     * Any file that is *not* recorded as UNGATED must be gated. Written as a
     * subset check rather than an equality so that fixing
     * `LocationSharingManager` (the one known hole, out of territory) turns this
     * test greener, never redder.
     */
    @Test
    fun `no site is ungated except the one recorded as such`() {
        val documentedUngated = knownGeocoderSites
            .filterValues { it.startsWith("UNGATED") }
            .keys

        val actuallyUngated = geocoderSites()
            // Again: a call. `contains("PlatformGeocoderGate")` alone is satisfied
            // by a doc comment — mutation B walked straight through it.
            .filterValues {
                !it.contains("PlatformGeocoderGate.decide(") && !it.contains("isOfflineOnly(")
            }
            .keys

        val undocumented = actuallyUngated - documentedUngated
        assertTrue(
            "Geocoder call site(s) that consult no offline-only gate:\n" +
                undocumented.joinToString("\n") { "  $it" },
            undocumented.isEmpty()
        )
    }
}
