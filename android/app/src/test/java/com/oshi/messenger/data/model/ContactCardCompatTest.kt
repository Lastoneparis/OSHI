package com.oshi.messenger.data.model

import com.oshi.messenger.service.WelcomeMessage
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CONTACT_CARD_COMPAT_2026_09_27__ "I received a kind of contact picture of 75
 * bytes but nothing I can click."
 *
 * The 75 bytes are the Android welcome card as `WelcomeMessage.contactCardJson`
 * used to build it — kotlinx drops the null `timestamp` — forwarded to an iPhone,
 * whose `ContactCard` (`OSHI/MediaManager.swift`) required that key. These tests
 * pin the reproduction and the two halves of the fix on this side: the welcome
 * card is now written with a timestamp, and every outbound CONTACT attachment is
 * canonicalised to the shape shipped iPhones decode.
 */
class ContactCardCompatTest {

    /** Exactly what 1.6.x wrote into the welcome row. */
    private val legacyWelcomeCard =
        """{"publicKey":"${WelcomeMessage.AUTHOR_PUBLIC_KEY}","alias":"${WelcomeMessage.AUTHOR_ALIAS}"}"""

    private val now = 1_790_000_000_000L

    @Test
    fun `the reported 75-byte attachment is the timestamp-less welcome card`() {
        assertEquals(75, legacyWelcomeCard.toByteArray(Charsets.UTF_8).size)
        assertFalse(JSONObject(legacyWelcomeCard).has("timestamp"))
    }

    @Test
    fun `the welcome card now carries a numeric timestamp`() {
        val json = JSONObject(WelcomeMessage.contactCardJson(now))
        assertEquals(WelcomeMessage.AUTHOR_PUBLIC_KEY, json.getString("publicKey"))
        assertEquals(WelcomeMessage.AUTHOR_ALIAS, json.getString("alias"))
        val ts = json.get("timestamp")
        assertTrue("timestamp must be a JSON number, got $ts", ts is Number)
        assertEquals(ContactCardWire.millisToAppleSeconds(now), (ts as Number).toDouble(), 0.001)
    }

    @Test
    fun `a legacy welcome card is re-encoded with a timestamp before it leaves`() {
        val out = String(
            ContactCardWire.canonicalizeForWire(legacyWelcomeCard.toByteArray(Charsets.UTF_8), now),
            Charsets.UTF_8
        )
        val json = JSONObject(out)
        assertEquals(WelcomeMessage.AUTHOR_PUBLIC_KEY, json.getString("publicKey"))
        assertEquals("Hugo", json.getString("alias"))
        assertEquals(ContactCardWire.millisToAppleSeconds(now), json.getDouble("timestamp"), 0.001)
        assertEquals(setOf("publicKey", "alias", "timestamp"), json.keySet())
    }

    @Test
    fun `an existing timestamp survives canonicalisation`() {
        val fromIos = """{"publicKey":"K","alias":"Claire","timestamp":788918400}"""
        val json = JSONObject(
            String(ContactCardWire.canonicalizeForWire(fromIos.toByteArray(), now), Charsets.UTF_8)
        )
        assertEquals(788_918_400.0, json.getDouble("timestamp"), 0.001)
        assertEquals("Claire", json.getString("alias"))
    }

    @Test
    fun `a vCard becomes the JSON card iOS reads`() {
        val vcard = "BEGIN:VCARD\nVERSION:3.0\nFN:Eve Adams\nNOTE:OSHI Public Key: VKEY\nEND:VCARD\n"
        val json = JSONObject(
            String(ContactCardWire.canonicalizeForWire(vcard.toByteArray(), now), Charsets.UTF_8)
        )
        assertEquals("VKEY", json.getString("publicKey"))
        assertEquals("Eve Adams", json.getString("alias"))
        assertTrue(json.has("timestamp"))
    }

    @Test
    fun `bytes nobody can read are sent untouched`() {
        val junk = byteArrayOf(0x00, 0x01, 0x7F, 0x42)
        assertArrayEquals(junk, ContactCardWire.canonicalizeForWire(junk, now))
        val noKey = """{"alias":"x"}""".toByteArray()
        assertArrayEquals(noKey, ContactCardWire.canonicalizeForWire(noKey, now))
    }

    @Test
    fun `the legacy card still renders on Android`() {
        val card = ContactCardWire.decode(legacyWelcomeCard)
        assertEquals(WelcomeMessage.AUTHOR_PUBLIC_KEY, card?.publicKey)
        assertEquals("Hugo", card?.alias)
    }
}
