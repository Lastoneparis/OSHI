package com.oshi.messenger

import com.oshi.messenger.data.model.PeerNickname
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __SHARED_NICKNAME_2026_09_22__ The peer's own nickname on `📸PROFILE_UPDATE📸`
 * (`"displayName"`, same key as iOS/Desktop): wire compatibility, sanitising, and the
 * one display rule — user alias > shared nickname > placeholder/key prefix.
 */
class PeerNicknameTest {

    private val key = "AbCdEfGh+/IjKlMnOpQrStUvWxYz0123456789abcdefg="

    // ---------------------------------------------------------------- wire

    @Test fun `stamp emits displayName and read gets it back`() {
        val json = PeerNickname.stamp(JSONObject().put("type", "profile_update"), "  Hugo  ")
        assertEquals("Hugo", json.getString("displayName"))
        val parsed = PeerNickname.read(JSONObject(json.toString()))
        assertEquals(PeerNickname.Read.Set("Hugo"), parsed)
    }

    @Test fun `no nickname means the key is omitted`() {
        assertFalse(PeerNickname.stamp(JSONObject(), null).has("displayName"))
        assertFalse(PeerNickname.stamp(JSONObject(), "   ").has("displayName"))
    }

    @Test fun `an old payload without the field reads as Absent, not Clear`() {
        val old = JSONObject("""{"type":"profile_update","profileImageData":null,"lastSeen":1.7E9,"version":1,"groupEnvelopeV3":true,"ratchetV3":true}""")
        assertSame(PeerNickname.Read.Absent, PeerNickname.read(old))
    }

    @Test fun `present but empty or null reads as Clear`() {
        assertSame(PeerNickname.Read.Clear, PeerNickname.read(JSONObject("""{"displayName":""}""")))
        assertSame(PeerNickname.Read.Clear, PeerNickname.read(JSONObject("""{"displayName":null}""")))
        assertSame(PeerNickname.Read.Clear, PeerNickname.read(JSONObject("""{"displayName":"‮\n "}""")))
    }

    @Test fun `old parser still reads the other fields of a stamped payload`() {
        val json = PeerNickname.stamp(
            JSONObject().put("type", "profile_update").put("lastSeen", 12.5).put("version", 1),
            "Alice"
        )
        val back = JSONObject(json.toString())
        assertEquals("profile_update", back.optString("type"))
        assertEquals(12.5, back.optDouble("lastSeen", 0.0), 0.0)
        assertEquals(1, back.optInt("version", 0))
    }

    // ---------------------------------------------------------------- sanitize

    @Test fun `bidi overrides, isolates, marks, newlines and controls are stripped`() {
        assertEquals("Alice evil", PeerNickname.sanitize("Ali‮ce⁦ ev‏il⁩"))
        assertEquals("AB", PeerNickname.sanitize("A\nB"))
        assertEquals("AB", PeerNickname.sanitize("A\r\t\u0000\u0007  B"))
        assertEquals("AB", PeerNickname.sanitize("A‪‫‬‭⁧⁨‎B"))
    }

    @Test fun `whitespace-only, empty and null become null`() {
        assertNull(PeerNickname.sanitize(null))
        assertNull(PeerNickname.sanitize(""))
        assertNull(PeerNickname.sanitize("   \n\t "))
    }

    @Test fun `capped at 48 code points`() {
        val long = "x".repeat(60)
        assertEquals(48, PeerNickname.sanitize(long)!!.length)
    }

    @Test fun `cap never splits a surrogate pair`() {
        // 47 ASCII + an emoji (2 UTF-16 units) = 48 code points: kept whole.
        val s = "a".repeat(47) + "😀" + "tail"
        val out = PeerNickname.sanitize(s)!!
        assertEquals(48, out.codePointCount(0, out.length))
        assertTrue(out.endsWith("😀"))
        // 48 emoji + more: 96 UTF-16 units, no lone surrogate at the end.
        val emojis = PeerNickname.sanitize("😀".repeat(50))!!
        assertEquals(48, emojis.codePointCount(0, emojis.length))
        assertFalse(Character.isHighSurrogate(emojis.last()))
    }

    @Test fun `cap then trims a trailing space`() {
        assertEquals("a".repeat(47), PeerNickname.sanitize("a".repeat(47) + " b"))
    }

    // ---------------------------------------------------------------- resolve

    @Test fun `user alias beats shared nickname`() {
        assertEquals("Mom", PeerNickname.resolve("Mom", "Sarah", key))
    }

    @Test fun `shared nickname beats the placeholder alias`() {
        val placeholder = key.take(8) + "..."
        assertTrue(PeerNickname.isAutoAlias(placeholder, key))
        assertEquals("Sarah", PeerNickname.resolve(placeholder, "Sarah", key))
    }

    @Test fun `placeholder shapes are all detected`() {
        assertTrue(PeerNickname.isAutoAlias("", key))
        assertTrue(PeerNickname.isAutoAlias("   ", key))
        assertTrue(PeerNickname.isAutoAlias(key.take(8) + "…", key))
        assertTrue(PeerNickname.isAutoAlias(key.take(12), key))
        // Placeholder written from the base64url spelling of the same key.
        val url = key.replace('+', '-').replace('/', '_').trimEnd('=')
        assertTrue(PeerNickname.isAutoAlias(url.take(12) + "...", key))
    }

    @Test fun `a real alias is never treated as a placeholder`() {
        assertFalse(PeerNickname.isAutoAlias("Mom", key))
        assertFalse(PeerNickname.isAutoAlias("Ab", key))          // short prefix by chance
        assertFalse(PeerNickname.isAutoAlias("Bob...", key))
    }

    @Test fun `no alias, no nickname keeps the old fallback`() {
        val placeholder = key.take(8) + "..."
        assertEquals(placeholder, PeerNickname.resolve(placeholder, null, key))
        assertEquals(key.take(8) + "...", PeerNickname.resolve("", null, key))
        assertEquals(key.take(8) + "...", PeerNickname.resolve(null, null, key))
    }

    @Test fun `a nickname is sanitised again at display time`() {
        assertEquals("Sarah", PeerNickname.resolve(null, "Sa‮rah", key))
    }

    // ---------------------------------------------------------------- store (pure)

    @Test fun `apply stores under the normalized key and lookup folds spellings`() {
        val url = key.replace('+', '-').replace('/', '_').trimEnd('=')
        val m1 = PeerNickname.apply(emptyMap(), url, PeerNickname.Read.Set("Sarah"))
        assertEquals("Sarah", PeerNickname.lookup(m1, key))
        assertEquals("Sarah", PeerNickname.lookup(m1, url))
    }

    @Test fun `absent keeps, clear removes, identical set is a no-op`() {
        val m1 = PeerNickname.apply(emptyMap(), key, PeerNickname.Read.Set("Sarah"))
        assertSame(m1, PeerNickname.apply(m1, key, PeerNickname.Read.Absent))
        assertSame(m1, PeerNickname.apply(m1, key, PeerNickname.Read.Set("Sarah")))
        val m2 = PeerNickname.apply(m1, key, PeerNickname.Read.Clear)
        assertNull(PeerNickname.lookup(m2, key))
    }
}
