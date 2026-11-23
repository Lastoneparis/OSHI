package com.oshi.messenger

import com.oshi.messenger.network.version.AppVersions
import com.oshi.messenger.network.version.ProfileVersionWire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `📸PROFILE_UPDATE📸` now carries the sender's `versionName`. Almost the entire
 * installed base is on a build that has never heard of the field, so the only
 * question that matters is whether those builds still parse the payload.
 *
 * [OldProfileParser] below is the parser as it stood BEFORE this change — the same
 * `opt*` calls, in the same order, reading the same keys and nothing else. Every
 * test here runs it over the output of the REAL [ProfileVersionWire.stamp], not
 * over a hand-written string that could quietly drift from what the app sends.
 */
class ProfileVersionWireTest {

    /**
     * What every already-installed Android build does with this payload.
     *
     * Copied from `MessageRepository.handleProfileUpdate` as it was before the
     * `appVersion` field existed: `JSONObject` + `opt*`, absent read as "cannot".
     * The iOS equivalent is `JSONDecoder` into the `ProfileUpdatePayload` struct,
     * which likewise ignores keys it has no property for.
     */
    private data class OldProfile(
        val type: String,
        val profileImageData: String?,
        val lastSeen: Double,
        val version: Int,
        val groupEnvelopeV3: Boolean,
        val ratchetV3: Boolean
    )

    private object OldProfileParser {
        /** Throws exactly where the old parser would have thrown, and nowhere else. */
        fun parse(jsonString: String): OldProfile {
            val json = JSONObject(jsonString)
            return OldProfile(
                type = json.optString("type"),
                profileImageData = json.optString("profileImageData", null)
                    ?.takeIf { it.isNotEmpty() && it != "null" },
                lastSeen = json.optDouble("lastSeen", 0.0),
                version = json.optInt("version", 0),
                groupEnvelopeV3 = json.optBoolean("groupEnvelopeV3", false),
                ratchetV3 = json.optBoolean("ratchetV3", false)
            )
        }
    }

    /** The payload the app builds today, minus the version stamp. */
    private fun basePayload(): JSONObject = JSONObject().apply {
        put("type", "profile_update")
        put("profileImageData", "AAECAwQ=")
        put("lastSeen", 1_757_000_000.0)
        put("version", 1)
        put("groupEnvelopeV3", true)
        put("ratchetV3", true)
    }

    // =========================================================================
    // The old parser is unaffected
    // =========================================================================

    @Test
    fun `an old parser reads a stamped payload exactly as it reads an unstamped one`() {
        val before = OldProfileParser.parse(basePayload().toString())
        val after = OldProfileParser.parse(
            ProfileVersionWire.stamp(basePayload(), "1.6.14").toString()
        )
        assertEquals(
            "adding appVersion changed what an already-installed build sees",
            before, after
        )
        assertEquals("profile_update", after.type)
        assertEquals("AAECAwQ=", after.profileImageData)
        assertEquals(1, after.version)
        assertTrue(after.groupEnvelopeV3)
        assertTrue(after.ratchetV3)
    }

    @Test
    fun `an old parser survives a stamped payload with a null avatar`() {
        val payload = basePayload().apply { put("profileImageData", JSONObject.NULL) }
        val parsed = OldProfileParser.parse(
            ProfileVersionWire.stamp(payload, "1.6.14").toString()
        )
        assertNull(parsed.profileImageData)
        assertTrue(parsed.ratchetV3)
    }

    @Test
    fun `an old parser survives a stamped payload with no lastSeen`() {
        // `lastSeen` is omitted when the Last Seen setting is off.
        val payload = basePayload().apply { remove("lastSeen") }
        val parsed = OldProfileParser.parse(
            ProfileVersionWire.stamp(payload, "1.6.14").toString()
        )
        assertEquals(0.0, parsed.lastSeen, 0.0)
        assertEquals("profile_update", parsed.type)
    }

    @Test
    fun `the stamp adds exactly its two keys and rewrites none`() {
        val plain = basePayload()
        val stamped = ProfileVersionWire.stamp(basePayload(), "1.6.15")
        assertEquals(plain.length() + 2, stamped.length())
        for (key in plain.keys()) {
            assertEquals(
                "the stamp altered an existing key: $key",
                plain.get(key).toString(), stamped.get(key).toString()
            )
        }
        assertEquals("appVersion", ProfileVersionWire.KEY)
        assertEquals("appPlatform", ProfileVersionWire.KEY_PLATFORM)
        assertEquals("android", stamped.getString(ProfileVersionWire.KEY_PLATFORM))
    }

    /**
     * The platform tag rides with the version and only with the version, so the
     * two can never be read out of step.
     */
    @Test
    fun `the platform is stamped beside the version and nowhere without it`() {
        val stamped = ProfileVersionWire.stamp(basePayload(), "1.6.15")
        assertEquals("1.6.15", ProfileVersionWire.read(stamped))
        assertEquals("android", ProfileVersionWire.readPlatform(stamped))

        val unstamped = ProfileVersionWire.stamp(basePayload(), "  ")
        assertFalse(unstamped.has(ProfileVersionWire.KEY))
        assertFalse(unstamped.has(ProfileVersionWire.KEY_PLATFORM))
    }

    /**
     * An iOS peer sends `appVersion` on the 1.0.x line. Read plainly against the
     * Android floor of 1.6.14 that is "older" — which would be a permanent, false,
     * user-visible label on every iPhone. The tag is what stops it.
     */
    @Test
    fun `an iOS payload is read and judged on the iOS ladder`() {
        // What every iOS build up to 1.0.43 sends — the installed base — a
        // version with no platform key. Not judged, which is the safe direction.
        val fromIOS = basePayload().apply { put("appVersion", "1.0.43") }
        assertEquals("1.0.43", ProfileVersionWire.read(fromIOS))
        assertNull(ProfileVersionWire.readPlatform(fromIOS))
        assertFalse(
            AppVersions.peerPredatesKeySchedule(
                ProfileVersionWire.read(fromIOS),
                ProfileVersionWire.readPlatform(fromIOS)
            )
        )
        // And once iOS mirrors the tag, the notice finally does its job: 1.0.43
        // genuinely predates the new key schedule on the iOS ladder, so it is
        // flagged — against 1.0.44, never against this build's own 1.6.14.
        val tagged = basePayload().apply {
            put("appVersion", "1.0.43")
            put("appPlatform", "ios")
        }
        assertTrue(
            "a tagged iOS 1.0.43 predates the wire change and must be told",
            AppVersions.peerPredatesKeySchedule(
                ProfileVersionWire.read(tagged),
                ProfileVersionWire.readPlatform(tagged)
            )
        )

        // The other side of the same coin: an up-to-date iPhone stays silent.
        val current = basePayload().apply {
            put("appVersion", "1.0.44")
            put("appPlatform", "ios")
        }
        assertFalse(
            AppVersions.peerPredatesKeySchedule(
                ProfileVersionWire.read(current),
                ProfileVersionWire.readPlatform(current)
            )
        )
    }

    /** An old parser is no more disturbed by two new keys than by one. */
    @Test
    fun `an old parser reads a version-and-platform payload unchanged`() {
        val before = OldProfileParser.parse(basePayload().toString())
        val after = OldProfileParser.parse(
            ProfileVersionWire.stamp(basePayload(), "1.6.15").toString()
        )
        assertEquals(before, after)
    }

    // =========================================================================
    // The new parser round-trips
    // =========================================================================

    @Test
    fun `a new parser reads back what a new sender stamped`() {
        val wire = ProfileVersionWire.stamp(basePayload(), "1.6.14").toString()
        assertEquals("1.6.14", ProfileVersionWire.read(JSONObject(wire)))
    }

    @Test
    fun `a new parser reads no version out of an old sender's payload`() {
        // This is the common case for the next few weeks: everyone else is old.
        assertNull(ProfileVersionWire.read(basePayload()))
        // And "no version" must never be read as "old".
        assertFalse(
            AppVersions.peerPredatesKeySchedule(
                ProfileVersionWire.read(basePayload()),
                ProfileVersionWire.readPlatform(basePayload())
            )
        )
    }

    @Test
    fun `a blank or null version on the wire reads as absent`() {
        assertNull(ProfileVersionWire.read(basePayload().apply { put("appVersion", "") }))
        assertNull(ProfileVersionWire.read(basePayload().apply { put("appVersion", "   ") }))
        assertNull(
            ProfileVersionWire.read(basePayload().apply { put("appVersion", JSONObject.NULL) })
        )
    }

    @Test
    fun `a blank version is not stamped at all`() {
        val stamped = ProfileVersionWire.stamp(basePayload(), "   ")
        assertFalse(stamped.has("appVersion"))
        assertFalse(stamped.has("appPlatform"))
    }

    @Test
    fun `a peer on the previous release round-trips to the banner decision`() {
        // End to end: an old peer stamps 1.0.9, we read it, and the numeric
        // compare — not a string compare — decides.
        val fromPeer = ProfileVersionWire.stamp(basePayload(), "1.6.9").toString()
        val reported = ProfileVersionWire.read(JSONObject(fromPeer))
        assertEquals("1.6.9", reported)
        assertTrue(
            AppVersions.peerPredatesKeySchedule(
                reported, ProfileVersionWire.readPlatform(JSONObject(fromPeer))
            )
        )

        val fromCurrentPeer = ProfileVersionWire.stamp(
            basePayload(), AppVersions.MIN_KEY_SCHEDULE_VERSION
        ).toString()
        assertFalse(
            AppVersions.peerPredatesKeySchedule(
                ProfileVersionWire.read(JSONObject(fromCurrentPeer)),
                ProfileVersionWire.readPlatform(JSONObject(fromCurrentPeer))
            )
        )
    }

    @Test
    fun `read never throws on a hostile payload`() {
        assertNull(ProfileVersionWire.read(JSONObject()))
        // A number, an object and an array all stringify rather than throw; whatever
        // comes out then has to survive AppVersions, which refuses what it cannot
        // parse. The contract asserted here is only "no exception, no crash".
        ProfileVersionWire.read(JSONObject().put("appVersion", 5))
        ProfileVersionWire.read(JSONObject().put("appVersion", JSONObject()))
        assertFalse(
            AppVersions.peerPredatesKeySchedule(
                ProfileVersionWire.read(JSONObject().put("appVersion", JSONObject())),
                ProfileVersionWire.PLATFORM_ANDROID
            )
        )
    }
}
