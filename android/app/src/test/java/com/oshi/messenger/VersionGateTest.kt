package com.oshi.messenger

import com.oshi.messenger.network.version.AppVersions
import com.oshi.messenger.network.version.MinimumVersionGate
import com.oshi.messenger.network.version.ProfileVersionWire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version gate, and the one bug it exists to prevent.
 *
 * Every assertion here runs on the plain JVM: `AppVersions` and the parsing half
 * of `MinimumVersionGate` were written as pure functions with no `Context` and no
 * network precisely so the fail-open promise is provable without a device.
 */
class VersionGateTest {

    // =========================================================================
    // Numeric comparison — the "1.0.9 is newer than 1.0.43" bug
    // =========================================================================

    /**
     * The exact regression this project has actually shipped: a lexicographic
     * compare puts `"1.0.9"` ABOVE `"1.0.43"` because `'9' > '4'`, so a floor of
     * 1.0.43 leaves every 1.0.9 install alone — the very installs it is for.
     */
    @Test
    fun `1_0_9 is older than 1_0_43`() {
        assertEquals(-1, AppVersions.compareOrNull("1.0.9", "1.0.43"))
        assertEquals(1, AppVersions.compareOrNull("1.0.43", "1.0.9"))
        assertTrue(AppVersions.isOlderThan("1.0.9", "1.0.43"))
        assertFalse(AppVersions.isOlderThan("1.0.43", "1.0.9"))
        // And the string compare that would have been wrong, spelled out, so the
        // test says WHY it exists:
        assertTrue("the naive compare really does invert here", "1.0.9" > "1.0.43")
    }

    @Test
    fun `two digit components compare numerically at every position`() {
        assertTrue(AppVersions.isOlderThan("1.6.9", "1.6.13"))
        assertTrue(AppVersions.isOlderThan("1.9.0", "1.13.0"))
        assertTrue(AppVersions.isOlderThan("9.0.0", "13.0.0"))
        assertFalse(AppVersions.isOlderThan("1.6.13", "1.6.9"))
        assertFalse(AppVersions.isOlderThan("1.13.0", "1.9.0"))
    }

    @Test
    fun `equal versions are neither older nor newer`() {
        assertEquals(0, AppVersions.compareOrNull("1.6.14", "1.6.14"))
        assertFalse(AppVersions.isOlderThan("1.6.14", "1.6.14"))
    }

    @Test
    fun `a missing trailing component is zero`() {
        assertEquals(0, AppVersions.compareOrNull("1.6", "1.6.0"))
        assertEquals(0, AppVersions.compareOrNull("1", "1.0.0.0"))
        assertEquals(-1, AppVersions.compareOrNull("1.6", "1.6.1"))
        assertEquals(1, AppVersions.compareOrNull("1.6.1", "1.6"))
    }

    @Test
    fun `a suffix on a numeric component does not change the number`() {
        assertEquals(0, AppVersions.compareOrNull("1.6.14-beta", "1.6.14"))
        assertEquals(-1, AppVersions.compareOrNull("1.6.13-rc1", "1.6.14"))
    }

    // =========================================================================
    // Malformed input — never a decision
    // =========================================================================

    @Test
    fun `malformed versions are unjudgeable, not old`() {
        val junk = listOf(
            null, "", "   ", "abc", "v1.6.14", "1..3", ".", "..",
            "1.x.3", "-1.2.3", "latest", "1.6.14 (52)", "1.6.14px", "\n", "🙂"
        )
        for (bad in junk) {
            assertNull("parse should refuse <$bad>", AppVersions.parse(bad))
            assertNull("compare should refuse <$bad>", AppVersions.compareOrNull(bad, "1.6.14"))
            assertNull("compare should refuse <$bad>", AppVersions.compareOrNull("1.6.14", bad))
            // The fail-open consequence: an unreadable version is never "old".
            assertFalse("<$bad> must not be judged old", AppVersions.isOlderThan(bad, "1.6.14"))
            assertFalse(
                "a floor of <$bad> must gate nobody",
                AppVersions.isOlderThan("1.0.0", bad)
            )
        }
    }

    @Test
    fun `an absurdly long component is clamped rather than thrown`() {
        val parsed = AppVersions.parse("1.99999999999999999999.0")
        assertNotNull(parsed)
        assertEquals(Int.MAX_VALUE, parsed!![1])
    }

    private val android = ProfileVersionWire.PLATFORM_ANDROID

    @Test
    fun `a peer that reported no version is never called old`() {
        assertFalse(AppVersions.peerPredatesKeySchedule(null, android))
        assertFalse(AppVersions.peerPredatesKeySchedule("", android))
        assertFalse(AppVersions.peerPredatesKeySchedule("who knows", android))
    }

    @Test
    fun `an android peer below the key schedule floor is flagged, one at or above is not`() {
        assertTrue(AppVersions.peerPredatesKeySchedule("1.6.13", android))
        assertTrue(AppVersions.peerPredatesKeySchedule("1.6.9", android))
        assertFalse(
            AppVersions.peerPredatesKeySchedule(AppVersions.MIN_KEY_SCHEDULE_VERSION, android)
        )
        assertFalse(AppVersions.peerPredatesKeySchedule("1.7.0", android))
        assertFalse(AppVersions.peerPredatesKeySchedule("2.0.0", android))
    }

    /**
     * The bug the iOS half of this work found by reading the Kotlin half.
     *
     * iOS numbers this app 1.0.x — its own threshold for the SAME protocol change
     * is 1.0.44 (`OSHI/PeerVersionCompat.swift`). Compared against the ANDROID
     * floor of 1.6.14, every iPhone on earth is "older", forever, on a banner whose
     * whole job is to be believed. There is no number that is correct on two
     * independent version lines, so the payload carries the platform — and each
     * ladder is then measured against its OWN floor.
     */
    @Test
    fun `an iOS peer is judged against the iOS floor, not the android one`() {
        val ios = ProfileVersionWire.PLATFORM_IOS

        // Genuinely behind the wire change, and now sayable.
        for (v in listOf("1.0.43", "1.0.9", "1.0.0", "0.9.0")) {
            assertTrue(
                "an iOS peer on $v predates the new key schedule and must be told",
                AppVersions.peerPredatesKeySchedule(v, ios)
            )
        }

        // From 1.0.44, the iPhone speaks the new schedule. Never flagged.
        for (v in listOf("1.0.44", "1.0.45", "1.1.0", "2.0.0")) {
            assertFalse(
                "an iOS peer on $v is up to date and must stay silent",
                AppVersions.peerPredatesKeySchedule(v, ios)
            )
        }

        // And the ladders must never be crossed: 1.5.99 is below the ANDROID
        // floor but far above the iOS one, so the tag alone decides.
        assertFalse(AppVersions.peerPredatesKeySchedule("1.5.99", ios))
        assertTrue(
            AppVersions.peerPredatesKeySchedule("1.5.99", ProfileVersionWire.PLATFORM_ANDROID)
        )
    }

    /** The two floors name ONE wire change, one number per ladder. */
    @Test
    fun `the two floors name the same wire change`() {
        assertEquals("1.6.14", AppVersions.MIN_KEY_SCHEDULE_VERSION)
        assertEquals(
            "must stay equal to RatchetInterop.minimumVersion in PeerVersionCompat.swift",
            "1.0.44", AppVersions.MIN_KEY_SCHEDULE_VERSION_IOS
        )
    }

    @Test
    fun `a peer that did not say which platform is never judged`() {
        // Includes Android 1.6.14 itself, which shipped appVersion without
        // appPlatform: one release of silence, which is the safe direction.
        for (p in listOf(null, "", "   ", "unknown", "Windows")) {
            assertFalse(
                "platform <$p> must not be judged",
                AppVersions.peerPredatesKeySchedule("1.6.13", p)
            )
        }
    }

    @Test
    fun `the platform tag is matched exactly and lowercased on the way in`() {
        assertTrue(AppVersions.peerPredatesKeySchedule("1.6.13", "android"))
        // `readPlatform` lowercases, so anything reaching this function is already
        // normalised; a raw mixed-case value is deliberately NOT matched here.
        assertFalse(AppVersions.peerPredatesKeySchedule("1.6.13", "Android"))
        assertEquals(
            "android",
            ProfileVersionWire.readPlatform(JSONObject().put("appPlatform", "ANDROID"))
        )
    }

    // =========================================================================
    // Server responses — every failure means NO gate
    // =========================================================================

    /**
     * Server unreachable is modelled the way the production code models it: the
     * fetch returns `null` and `parseMinimumVersion` is handed nothing. A timeout,
     * a DNS failure, a TLS error and airplane mode are the same code path.
     */
    @Test
    fun `server unreachable produces no gate`() {
        assertNull(MinimumVersionGate.parseMinimumVersion(null))
        assertFalse(MinimumVersionGate.shouldPrompt("1.0.0", null))
    }

    /** A 500 never reaches the parser at all — but if it did, its body is HTML. */
    @Test
    fun `a 500 body produces no gate`() {
        val body = "<html><head><title>500 Internal Server Error</title></head></html>"
        assertNull(MinimumVersionGate.parseMinimumVersion(body))
        assertFalse(MinimumVersionGate.shouldPrompt("1.0.0", MinimumVersionGate.parseMinimumVersion(body)))
    }

    @Test
    fun `garbage JSON produces no gate`() {
        val garbage = listOf(
            "", "   ", "not json at all", "{", "{\"min_supported_version\":",
            "[1,2,3]", "null", "{}", "\t"
        )
        for (body in garbage) {
            val floor = MinimumVersionGate.parseMinimumVersion(body)
            assertNull("<$body> must not produce a floor", floor)
            assertFalse(MinimumVersionGate.shouldPrompt("1.0.0", floor))
        }
    }

    @Test
    fun `well formed JSON without the field produces no gate`() {
        val body = JSONObject()
            .put("v2_enabled", true)
            .put("rollout_percent", 100)
            .put("min_build", 40)
            .toString()
        assertNull(MinimumVersionGate.parseMinimumVersion(body))
        assertFalse(MinimumVersionGate.shouldPrompt("1.0.0", MinimumVersionGate.parseMinimumVersion(body)))
    }

    @Test
    fun `a field present but unparseable produces no gate`() {
        for (bad in listOf("", "latest", "v2", JSONObject.NULL)) {
            val body = JSONObject().put("min_supported_version", bad).toString()
            assertNull("<$bad> must not produce a floor", MinimumVersionGate.parseMinimumVersion(body))
        }
        // Wrong TYPE entirely: a number, an object, an array.
        assertNull(MinimumVersionGate.parseMinimumVersion("{\"min_supported_version\":{}}"))
        assertNull(MinimumVersionGate.parseMinimumVersion("{\"min_supported_version\":[]}"))
    }

    @Test
    fun `a well formed floor is read and applied numerically`() {
        val body = JSONObject().put("min_supported_version", "1.0.43").toString()
        assertEquals("1.0.43", MinimumVersionGate.parseMinimumVersion(body))
        // The bug again, this time end to end through the gate.
        assertTrue(MinimumVersionGate.shouldPrompt("1.0.9", "1.0.43"))
        assertFalse(MinimumVersionGate.shouldPrompt("1.0.43", "1.0.43"))
        assertFalse(MinimumVersionGate.shouldPrompt("1.6.14", "1.0.43"))
    }

    @Test
    fun `the android specific floor wins over the shared one`() {
        val body = JSONObject()
            .put("min_supported_version", "9.9.9")
            .put("android_min_supported_version", "1.6.14")
            .toString()
        assertEquals("1.6.14", MinimumVersionGate.parseMinimumVersion(body))
    }

    @Test
    fun `the shared floor is used when the android one is blank`() {
        val body = JSONObject()
            .put("min_supported_version", "1.6.14")
            .put("android_min_supported_version", "")
            .toString()
        assertEquals("1.6.14", MinimumVersionGate.parseMinimumVersion(body))
    }

    @Test
    fun `an unreadable own version is never prompted`() {
        assertFalse(MinimumVersionGate.shouldPrompt(null, "1.6.14"))
        assertFalse(MinimumVersionGate.shouldPrompt("", "1.6.14"))
        assertFalse(MinimumVersionGate.shouldPrompt("unknown", "1.6.14"))
    }

    /** The endpoint is the app's own, off `BuildConfig.VPS_URL`; no host here. */
    @Test
    fun `the config path is the existing v2 config endpoint`() {
        assertEquals("/v2/config", MinimumVersionGate.CONFIG_PATH)
    }
}
