package com.oshi.messenger

import com.oshi.messenger.data.model.ProfileBioWire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __PROFILE_BIO_2026_11_02__ (N4) the bio and accent colour on the profile
 * payload.
 *
 * The payload is read by every already-installed build, so the rules that keep
 * it additive are pinned here: a missing key means "no change", a present and
 * empty one means "cleared", and neither may ever become the other.
 */
class ProfileBioWireTest {

    @Test
    fun `absent means no change, empty means cleared`() {
        assertTrue(ProfileBioWire.read(JSONObject()) is ProfileBioWire.Read.Absent)
        assertTrue(ProfileBioWire.read(JSONObject().put("bio", "")) is ProfileBioWire.Read.Clear)
        assertTrue(ProfileBioWire.read(JSONObject().put("bio", "   ")) is ProfileBioWire.Read.Clear)
    }

    @Test
    fun `round trip with and without an expiry`() {
        val future = System.currentTimeMillis() + 3_600_000
        val withExpiry = ProfileBioWire.stamp(JSONObject(), "Back on Tuesday", future, null)
        val r1 = ProfileBioWire.read(withExpiry) as ProfileBioWire.Read.Set
        assertEquals("Back on Tuesday", r1.text)
        assertEquals(future, r1.until)

        val permanent = ProfileBioWire.stamp(JSONObject(), "Signal: no.", null, null)
        val r2 = ProfileBioWire.read(permanent) as ProfileBioWire.Read.Set
        assertNull("absent expiry means permanent", r2.until)
    }

    /**
     * An expiry already in the past is not written at all. Sending one would
     * ask the peer to hide the text the instant they receive it, which is the
     * same as not sending it — but harder to reason about.
     */
    @Test
    fun `an expiry already past is not stamped`() {
        val past = System.currentTimeMillis() - 1000
        val j = ProfileBioWire.stamp(JSONObject(), "hello", past, null)
        assertTrue(j.has("bio"))
        assertFalse(j.has("bioUntil"))
    }

    /** A peer must not be able to push the card apart with whitespace. */
    @Test
    fun `newlines and control characters collapse`() {
        val nasty = "line one\n\n\n\nline two\u0000​   end"
        assertEquals("line one line two end", ProfileBioWire.sanitize(nasty))
    }

    @Test
    fun `length is capped in code points, so an emoji costs one`() {
        val emoji = "😀".repeat(200)
        val out = ProfileBioWire.sanitize(emoji)!!
        assertEquals(ProfileBioWire.MAX_CODE_POINTS, out.codePointCount(0, out.length))
    }

    /**
     * Nearly-a-colour is refused, not coerced: this string is parsed into a
     * colour on the receiving device.
     */
    @Test
    fun `only a full six-digit hex colour is accepted`() {
        assertEquals("#5616EC", ProfileBioWire.sanitizeColor("#5616ec"))
        assertNull(ProfileBioWire.sanitizeColor("#5616E"))
        assertNull(ProfileBioWire.sanitizeColor("5616EC"))
        assertNull(ProfileBioWire.sanitizeColor("#5616ECFF"))
        assertNull(ProfileBioWire.sanitizeColor("red"))
        assertNull(ProfileBioWire.sanitizeColor(null))
        assertNull(ProfileBioWire.sanitizeColor("#GGGGGG"))
    }

    @Test
    fun `visibility follows the expiry`() {
        val now = 1_790_000_000_000L
        assertTrue("permanent", ProfileBioWire.isVisible(null, now))
        assertTrue("not yet expired", ProfileBioWire.isVisible(now + 1, now))
        assertFalse("expired", ProfileBioWire.isVisible(now - 1, now))
    }

    /** Nothing to say, nothing written: the payload stays byte-identical. */
    @Test
    fun `stamping nothing adds no keys`() {
        val j = ProfileBioWire.stamp(JSONObject(), null, null, null)
        assertEquals(0, j.length())
    }
}
