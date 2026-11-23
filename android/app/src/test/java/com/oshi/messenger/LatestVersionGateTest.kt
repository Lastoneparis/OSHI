package com.oshi.messenger

import com.oshi.messenger.network.version.MinimumVersionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __UPDATE_LATEST_VERSION_2026_11_03__ (Anatole A7 / N10)
 *
 * "Le nouveau bouton ne prévient pas lorsqu'une nouvelle mise à jour sort."
 *
 * The sheet was not broken; it was answering a different question. `/v2/config`
 * publishes `android_min_supported_version` — the floor below which we stop
 * supporting a build — and nothing else. The floor is 1.6.38 and production is
 * 1.6.66, so every user in between asked "is there an update?" and was told
 * "you are up to date", which was a true answer about the floor and a false
 * answer about the world.
 *
 * The distinction these tests protect is between "no, you are current" and
 * "we do not know": a server that has not published the new field must leave
 * the sheet silent rather than reassuring.
 */
class LatestVersionGateTest {

    private fun body(vararg pairs: Pair<String, String?>): String =
        "{" + pairs.joinToString(",") { (k, v) ->
            "\"$k\":" + if (v == null) "null" else "\"$v\""
        } + "}"

    // ------------------------------------------------------------ parsing

    @Test
    fun `a published latest version is read`() {
        assertEquals(
            "1.6.66",
            MinimumVersionGate.parseLatestVersion(body("android_latest_version" to "1.6.66"))
        )
    }

    @Test
    fun `a server that does not publish the field yields null`() {
        // The state of production at the time of writing: the key does not
        // exist. Null must reach the UI as "unknown", never as "current".
        assertNull(MinimumVersionGate.parseLatestVersion(body("android_min_supported_version" to "1.6.38")))
    }

    @Test
    fun `a JSON null and an unparseable value are both no answer`() {
        assertNull(MinimumVersionGate.parseLatestVersion(body("android_latest_version" to null)))
        assertNull(MinimumVersionGate.parseLatestVersion(body("android_latest_version" to "soon")))
        assertNull(MinimumVersionGate.parseLatestVersion(body("android_latest_version" to "")))
    }

    @Test
    fun `junk never throws`() {
        assertNull(MinimumVersionGate.parseLatestVersion(null))
        assertNull(MinimumVersionGate.parseLatestVersion(""))
        assertNull(MinimumVersionGate.parseLatestVersion("not json at all"))
        assertNull(MinimumVersionGate.parseLatestVersion("[1,2,3]"))
    }

    // --------------------------------------------------------- the verdict

    @Test
    fun `an older build is told something newer exists`() {
        assertTrue(MinimumVersionGate.newerAvailable("1.6.38", "1.6.66"))
        assertTrue(MinimumVersionGate.newerAvailable("1.6.65", "1.6.66"))
    }

    @Test
    fun `the current build is not nagged`() {
        assertFalse(MinimumVersionGate.newerAvailable("1.6.66", "1.6.66"))
    }

    @Test
    fun `a build ahead of the published one is not nagged`() {
        // Internal-track and local builds run ahead of production routinely.
        assertFalse(MinimumVersionGate.newerAvailable("1.6.67", "1.6.66"))
    }

    @Test
    fun `silence is not reassurance`() {
        // The whole point. With no published latest version the verdict is
        // false — the sheet says nothing new rather than claiming currency it
        // cannot possibly know.
        assertFalse(MinimumVersionGate.newerAvailable("1.6.38", null))
        assertFalse(MinimumVersionGate.newerAvailable(null, "1.6.66"))
    }

    @Test
    fun `the floor and the latest version are independent questions`() {
        // This is the bug in one assertion: below the floor is a strict subset
        // of out-of-date, and the sheet used to test only the subset.
        val b = body("android_min_supported_version" to "1.6.38",
                     "android_latest_version" to "1.6.66")
        val floor = MinimumVersionGate.parseMinimumVersion(b)
        val latest = MinimumVersionGate.parseLatestVersion(b)
        assertFalse("1.6.50 is above the floor", MinimumVersionGate.shouldPrompt("1.6.50", floor))
        assertTrue("…and still out of date", MinimumVersionGate.newerAvailable("1.6.50", latest))
    }
}
