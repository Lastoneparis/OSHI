package com.oshi.messenger.wear

import com.oshi.wearbridge.WearAvatar
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch's avatar palette against the ONE cross-platform spec,
 * `docs/avatar-palette.json` (also read by the phone's AvatarIdentityTest,
 * iOS and desktop): same literals, same identifier -> slot vectors.
 */
class WearAvatarTest {

    private val spec: JSONObject by lazy {
        val file = listOf("../../docs/avatar-palette.json", "../docs/avatar-palette.json", "docs/avatar-palette.json")
            .map { java.io.File(it) }.firstOrNull { it.exists() }
        assertTrue("docs/avatar-palette.json not found from ${java.io.File(".").absolutePath}", file != null)
        JSONObject(file!!.readText())
    }

    private fun argb(s: String): Long = 0xFF000000L or s.removePrefix("#").toLong(16)

    @Test
    fun `palette literals match the shared spec`() {
        val p = spec.getJSONArray("palette")
        assertEquals(p.length(), WearAvatar.entries.size)
        for (i in 0 until p.length()) {
            val e = p.getJSONObject(i)
            val idx = e.getInt("index")
            val mine = WearAvatar.entries[idx]
            assertEquals("start $idx", argb(e.getString("start")), mine.start)
            assertEquals("end $idx", argb(e.getString("end")), mine.end)
            assertEquals("ink $idx", argb(e.getString("ink")), mine.ink)
            assertEquals("nameDark $idx", argb(e.getString("nameDark")), WearAvatar.nameDark[idx])
        }
        val h = spec.getJSONObject("highlight")
        assertEquals(h.getDouble("centerX"), WearAvatar.HIGHLIGHT_X.toDouble(), 1e-6)
        assertEquals(h.getDouble("centerY"), WearAvatar.HIGHLIGHT_Y.toDouble(), 1e-6)
        assertEquals(h.getDouble("radius"), WearAvatar.HIGHLIGHT_RADIUS.toDouble(), 1e-6)
        assertEquals(h.getDouble("alphaAtCenter"), WearAvatar.HIGHLIGHT_ALPHA.toDouble(), 1e-6)
    }

    @Test
    fun `shared vectors give the spec slots`() {
        val v = spec.getJSONArray("vectors")
        assertTrue(v.length() >= 10)
        for (i in 0 until v.length()) {
            val o = v.getJSONObject(i)
            assertEquals("slot for '${o.getString("id")}'", o.getInt("slot"), WearAvatar.slot(o.getString("id")))
        }
    }

    @Test
    fun `a group id is the same slot in either case and a key is never folded`() {
        val upper = "7F3C2A10-5B9E-4C1D-8E2F-A6B7C8D9E0F1"
        assertEquals(WearAvatar.slot(upper), WearAvatar.slot(upper.lowercase()))
        val key = "BHq2Y7qkzL0m1yQe0kq9XW3sV1d6c7m3pPq9yZ4b8tE="
        assertEquals(key, WearAvatar.normalizedKey(key))
    }

    @Test
    fun `initials follow the phone rule`() {
        assertEquals("LM", WearAvatar.initials("Léa Martin", "k"))
        assertEquals("NO", WearAvatar.initials("Noor", "k"))
        assertEquals("AB", WearAvatar.initials("  ", "abc"))
        assertEquals("CC", WearAvatar.initials("Climbing crew 🧗", "k"))
        // A word with no letter is skipped; an emoji is never cut in half.
        assertEquals("FA", WearAvatar.initials("Famille 🏡", "k"))
        assertEquals("🏡", WearAvatar.initials("🏡", "k", count = 1))
    }
}
