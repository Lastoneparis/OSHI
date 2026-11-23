package com.oshi.messenger.ui

import androidx.compose.ui.graphics.Color
import com.oshi.messenger.service.WallpaperManager
import com.oshi.messenger.ui.components.ChatWallpaper
import com.oshi.messenger.ui.components.OshiDynamicStyle
import com.oshi.messenger.ui.components.WallpaperTone
import com.oshi.messenger.ui.components.WallpaperToneResolver
import com.oshi.messenger.ui.components.inkFor
import com.oshi.messenger.ui.components.toSyncBytes
import com.oshi.messenger.ui.theme.BubbleAccent
import com.oshi.messenger.ui.theme.BubbleStyle
import com.oshi.messenger.ui.theme.Contrast
import com.oshi.messenger.ui.theme.Contrast.over
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.theme.oshiColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CHAT_POLISH_2026_09_24__ Every ink a conversation draws, measured from the
 * actual tokens against the worst ground it can land on. Mirrors iOS
 * `ChatWallpaperAppearanceTests` (same hex values, same grounds, same floors).
 *
 * "Worst ground" = both ends of the wallpaper ramp, each under the full brand
 * glow, each under a mesh HUB node (accent at `ink × 1.5`) — the darkest pixel
 * (light) / brightest pixel (dark) the backdrop can put under a glyph.
 */
class ChatPaletteContrastTest {

    private val accent = Color(0xFF2E52F5)

    private fun grounds(dark: Boolean): List<Color> {
        val ink = inkFor(dark)
        val ends = if (dark) listOf(Oshi.Chat.groundDarkTop, Oshi.Chat.groundDarkBottom)
        else listOf(Oshi.Chat.groundLightTop, Oshi.Chat.groundLightBottom)
        return ends.flatMap { base ->
            val glow = OSHIPurple.copy(alpha = Oshi.Chat.brandGlow(dark)).over(base)
            val node = accent.copy(alpha = ink * 1.5f).over(glow)
            listOf(base, glow, node)
        }
    }

    private fun worst(ink: Color, dark: Boolean, chip: Boolean = false): Double =
        grounds(dark).minOf { g ->
            val bg = if (chip) ink.copy(alpha = Oshi.Chat.chipFill).over(Oshi.Chat.chipSurface(dark).over(g)) else g
            Contrast.ratio(ink, bg)
        }

    @Test
    fun `timestamp ink clears AA on every ground in both schemes`() {
        for (dark in listOf(false, true)) {
            val r = worst(Oshi.Chat.metaInk(dark), dark)
            assertTrue("timestamp ink dark=$dark: $r", r >= 4.5)
        }
    }

    @Test
    fun `the old in-bubble meta ink was below AA - the defect this replaces`() {
        // onSurfaceVariant at 50 % on the old lavender received bubble.
        val old = oshiColorScheme(false).onSurfaceVariant.copy(alpha = 0.5f).over(Color(0xFFE2DCF6))
        assertTrue(Contrast.ratio(old, Color(0xFFE2DCF6)) < 4.5)
    }

    @Test
    fun `read tick and every transport chip clear AA in both schemes`() {
        for (dark in listOf(false, true)) {
            val tick = worst(Oshi.Chat.readTick(dark), dark)
            assertTrue("read tick dark=$dark: $tick", tick >= 4.5)
            for (t in Oshi.Chat.Transport.entries) {
                val r = worst(Oshi.Chat.transportInk(t, dark), dark, chip = true)
                assertTrue("chip $t dark=$dark: $r", r >= 4.5)
            }
        }
    }

    @Test
    fun `incoming body text and links clear AA in both schemes`() {
        for (dark in listOf(false, true)) {
            val fill = Oshi.Bubble.received(dark)
            val body = Contrast.ratio(oshiColorScheme(dark).onSurface, fill)
            assertTrue("incoming body dark=$dark: $body", body >= 7.0)
            val link = Contrast.ratio(Oshi.brandInk(dark), fill)
            assertTrue("incoming link dark=$dark: $link", link >= 4.5)
        }
    }

    @Test
    fun `white on the OSHI gradient clears AA with the top sheen composited`() {
        val a = BubbleAccent.OSHI
        val lit = Color.White.copy(alpha = Oshi.Bubble.outgoingSheen).over(a.stopStart)
        for ((name, c) in listOf("light stop + sheen" to lit, "light stop" to a.stopStart, "dark stop" to a.stopEnd)) {
            val r = Contrast.ratio(Color.White, c)
            assertTrue("white on $name: $r", r >= 4.5)
        }
    }

    @Test
    fun `light tone on a dark phone draws the light inks`() {
        val mesh = ChatWallpaper.OshiDynamic(OshiDynamicStyle.MESH.ordinal)
        val r = WallpaperToneResolver.resolve(mesh, WallpaperTone.LIGHT, systemDark = true)
        assertTrue(!r.contentDark)
        assertTrue(worst(Oshi.Chat.metaInk(r.contentDark), r.contentDark) >= 4.5)
    }

    @Test
    fun `tokens are the same hex values as iOS ChatPalette`() {
        assertEquals(Color(0xFFFFFFFF), Oshi.Bubble.receivedLight)
        assertEquals(Color(0xFF1E1B2E), Oshi.Bubble.receivedDark)
        assertEquals(Color(0xFF7B3AF5), BubbleAccent.OSHI.stopStart)
        assertEquals(Color(0xFF5616EC), BubbleAccent.OSHI.stopEnd)
        assertEquals(18f, BubbleStyle.Modern.radius.value)
        assertEquals(0.11f, inkFor(false))
        assertEquals(0.22f, inkFor(true))
    }

    @Test
    fun `a never-set global wallpaper is Mesh, encoded exactly as iOS's default`() {
        val fallback = WallpaperManager.FALLBACK_GLOBAL
        assertEquals(ChatWallpaper.OshiDynamic(OshiDynamicStyle.MESH.ordinal), fallback)
        assertEquals("""{"oshiDynamic":{"_0":1}}""", String(fallback.toSyncBytes(), Charsets.UTF_8))
    }
}
