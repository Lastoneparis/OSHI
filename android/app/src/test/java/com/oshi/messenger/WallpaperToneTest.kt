package com.oshi.messenger

import androidx.compose.ui.graphics.Color
import com.oshi.messenger.ui.components.ChatWallpaper
import com.oshi.messenger.ui.components.OshiDynamicStyle
import com.oshi.messenger.ui.components.WallpaperTone
import com.oshi.messenger.ui.components.WallpaperToneResolver
import com.oshi.messenger.ui.components.toSyncBytes
import com.oshi.messenger.ui.theme.Contrast
import com.oshi.messenger.ui.theme.Contrast.over
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.theme.oshiColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __WALLPAPER_TONE_2026_09_24__ Wallpaper tone × system appearance → the
 * scheme the wallpaper AND the thread render in. Mirrors iOS
 * `ChatWallpaperAppearanceTests`. The owner's case: dark phone + "Light" ⇒
 * white-with-violet mesh AND light-mode bubbles on it.
 */
class WallpaperToneTest {

    private val mesh = ChatWallpaper.OshiDynamic(OshiDynamicStyle.MESH.ordinal)

    @Test
    fun `automatic follows the system`() {
        for (systemDark in listOf(false, true)) {
            val r = WallpaperToneResolver.resolve(mesh, WallpaperTone.AUTOMATIC, systemDark)
            assertEquals(systemDark, r.wallpaperDark)
            assertEquals(systemDark, r.contentDark)
            assertTrue(r.toneApplies)
        }
    }

    @Test
    fun `light tone keeps the white mesh in dark mode and lightens the thread`() {
        val r = WallpaperToneResolver.resolve(mesh, WallpaperTone.LIGHT, systemDark = true)
        assertFalse("white mesh must survive dark mode", r.wallpaperDark)
        assertFalse("bubbles on a light wallpaper use light-mode colours", r.contentDark)
    }

    @Test
    fun `dark tone keeps the black mesh in light mode`() {
        val r = WallpaperToneResolver.resolve(mesh, WallpaperTone.DARK, systemDark = false)
        assertTrue(r.wallpaperDark)
        assertTrue(r.contentDark)
    }

    @Test
    fun `full matrix - content always matches the wallpaper for every OSHI backdrop`() {
        for (style in OshiDynamicStyle.entries) {
            val wp = ChatWallpaper.OshiDynamic(style.ordinal)
            for (tone in WallpaperTone.entries) for (systemDark in listOf(false, true)) {
                val r = WallpaperToneResolver.resolve(wp, tone, systemDark)
                val expected = when (tone) {
                    WallpaperTone.AUTOMATIC -> systemDark
                    WallpaperTone.LIGHT -> false
                    WallpaperTone.DARK -> true
                }
                assertEquals("$style $tone $systemDark", expected, r.wallpaperDark)
                assertEquals("$style $tone $systemDark", r.wallpaperDark, r.contentDark)
            }
        }
    }

    @Test
    fun `fixed colour wallpapers ignore the tone`() {
        val fixed = listOf(
            ChatWallpaper.Default,
            ChatWallpaper.SolidColor(Color(0xFF121212)),
            ChatWallpaper.Gradient(listOf(Color.Red, Color.Blue), "x"),
            ChatWallpaper.EmojiPattern("💬", Color.White),
            ChatWallpaper.ImageWallpaper(byteArrayOf(1, 2, 3))
        )
        for (wp in fixed) for (tone in WallpaperTone.entries) for (systemDark in listOf(false, true)) {
            val r = WallpaperToneResolver.resolve(wp, tone, systemDark)
            assertEquals("unchanged behaviour for $wp", systemDark, r.contentDark)
            assertFalse(r.toneApplies)
        }
    }

    @Test
    fun `stored values round-trip and unknown falls back to automatic`() {
        for (t in WallpaperTone.entries) assertEquals(t, WallpaperTone.fromStored(t.stored))
        assertEquals(WallpaperTone.AUTOMATIC, WallpaperTone.fromStored(null))
        assertEquals(WallpaperTone.AUTOMATIC, WallpaperTone.fromStored("sepia"))
    }

    @Test
    fun `tone never reaches the wire format`() {
        val json = String(mesh.toSyncBytes(), Charsets.UTF_8)
        assertEquals("""{"oshiDynamic":{"_0":1}}""", json)
    }

    // ── Contrast of the thread on the light wallpaper, on a dark phone ──

    /** White ground with the mesh's strongest mark (accent node, ink 0.13 × 1.5). */
    private val lightGround = Color(0xFF2E52F5).copy(alpha = 0.13f * 1.5f).over(Color.White)

    @Test
    fun `received bubble text on the light tone is at least 4_5`() {
        val light = oshiColorScheme(dark = false)
        val ratio = Contrast.ratio(light.onSurface, Oshi.Bubble.received(darkTheme = false))
        assertTrue("received text: $ratio", ratio >= 4.5)
    }

    @Test
    fun `sent bubble text is at least 4_5`() {
        val ratio = Contrast.ratio(Color.White, OSHIPurple)
        assertTrue("sent text: $ratio", ratio >= 4.5)
    }

    @Test
    fun `light-mode ink reads on the white mesh, dark-mode ink would not`() {
        val good = Contrast.ratio(oshiColorScheme(dark = false).onSurface, lightGround)
        val bad = Contrast.ratio(oshiColorScheme(dark = true).onSurface, lightGround)
        assertTrue("light ink on white mesh: $good", good >= 4.5)
        assertTrue("dark-mode ink on the white mesh is what the tone must avoid: $bad", bad < 1.5)
    }
}
