package com.oshi.messenger.ui

import androidx.compose.ui.graphics.Color
import com.oshi.messenger.ui.theme.Contrast
import com.oshi.messenger.ui.theme.GlassAccessibility
import com.oshi.messenger.ui.theme.GlassDefaults
import com.oshi.messenger.ui.theme.GlassPalette
import com.oshi.messenger.ui.theme.GlassStyle
import com.oshi.messenger.ui.theme.GlassTier
import com.oshi.messenger.ui.theme.LightGlassGround
import com.oshi.messenger.ui.theme.LightGlassSurface
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.OledGlass
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.theme.oshiColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __GLASS_ALL_THEMES_2026_11_21__ « Effet verre » in Light, Dark and OLED black:
 * the palettes, their legibility over every ground each layer can sit on, the
 * defaults, the light ground, the high-contrast rule and the strings.
 */
class GlassThemesTest {

    private fun Color.over(bg: Color): Color = with(Contrast) { this@over.over(bg) }

    private val light = oshiColorScheme(dark = false)
    private val lightGlass = oshiColorScheme(dark = false, glass = true)
    private val dark = oshiColorScheme(dark = true)
    private val oled = oshiColorScheme(dark = true, oledBlack = true)

    /** A modal's scrim is black 32 %: what is under a sheet / dialog, at its brightest and darkest. */
    private val scrimmedWhite = Color.Black.copy(alpha = 0.32f).over(Color.White)
    private val scrimmedBlack = Color.Black

    /**
     * Every ground a layer of [tier] can be drawn over, per palette. Bars sit
     * over the list / top surface or over the chat wallpaper (whose tone picks
     * the palette: a light palette's bar is over a light wallpaper — the
     * darkest one the tone resolver still calls light is mid-grey); sheets and
     * dialogs over scrimmed content of any colour; chips over the ground, a
     * card, a bubble or a sheet.
     */
    private fun grounds(style: GlassStyle, tier: GlassTier): List<Pair<String, Color>> = when (style) {
        GlassStyle.Oled -> listOf("black" to Color.Black)
        GlassStyle.Dark -> when (tier) {
            GlassTier.Bar -> listOf("background" to dark.background, "surface" to dark.surface, "dark wallpaper" to Color(0xFF2A2440))
            GlassTier.Card -> listOf("background" to dark.background, "surface" to dark.surface)
            GlassTier.Chip -> listOf("surface" to dark.surface, "card" to dark.surfaceVariant, "bubble" to Oshi.Bubble.received(true))
            GlassTier.Sheet, GlassTier.Dialog, GlassTier.Menu -> listOf("scrimmed black" to scrimmedBlack, "scrimmed white" to scrimmedWhite)
            GlassTier.Control -> emptyList() // dark-world screens keep OLED glass only (oledGlassOn)
        }
        GlassStyle.Light -> when (tier) {
            GlassTier.Bar -> listOf("ground" to LightGlassGround, "surface" to LightGlassSurface, "white" to Color.White, "mid wallpaper" to Color(0xFF9A9A9A))
            GlassTier.Card -> listOf("ground" to LightGlassGround, "surface" to LightGlassSurface)
            GlassTier.Chip -> listOf("ground" to LightGlassGround, "surface" to LightGlassSurface, "bubble" to Oshi.Bubble.received(false), "lavender wallpaper" to Color(0xFFE2DCF6))
            GlassTier.Sheet, GlassTier.Dialog, GlassTier.Menu -> listOf("scrimmed black" to scrimmedBlack, "scrimmed white" to scrimmedWhite)
            GlassTier.Control -> emptyList()
        }
    }

    private fun inks(style: GlassStyle): List<Pair<String, Color>> = when (style) {
        GlassStyle.Light -> listOf("onSurface" to lightGlass.onSurface, "onSurfaceVariant" to lightGlass.onSurfaceVariant, "brand" to OSHIPurple)
        GlassStyle.Dark -> listOf("onSurface" to dark.onSurface, "onSurfaceVariant" to dark.onSurfaceVariant, "brandInk" to Oshi.BrandInkDark)
        GlassStyle.Oled -> listOf("onSurface" to oled.onSurface, "onSurfaceVariant" to oled.onSurfaceVariant, "brandInk" to Oshi.BrandInkDark)
    }

    // ── the OLED look is untouched ─────────────────────────────────────────

    @Test
    fun `the OLED palette is exactly the shipped OLED glass`() {
        val p = GlassPalette.Oled
        for (t in GlassTier.values()) {
            assertEquals(t.blurred, p.blurred(t))
            assertEquals(t.opaque, p.opaque(t))
        }
        assertEquals(OledGlass.HairlineTop, p.hairlineTop)
        assertEquals(OledGlass.HairlineBottom, p.hairlineBottom)
        assertEquals(Color.White.copy(alpha = OledGlass.HIGHLIGHT_ALPHA), p.highlight)
        assertEquals(OledGlass.GlowColor, p.glowColor)
        assertEquals(1f, p.glowScale, 0f)
        assertEquals(0f, p.lift.alpha, 0f)
        assertEquals(Oshi.BrandInkDark, p.buttonInk)
    }

    // ── legibility ─────────────────────────────────────────────────────────

    @Test
    fun `text on every glass layer of every palette clears WCAG AA over every ground`() {
        val report = StringBuilder()
        var worst = Double.MAX_VALUE
        for (style in GlassStyle.values()) {
            val p = GlassPalette.of(style)
            for (tier in GlassTier.values()) {
                for ((gName, ground) in grounds(style, tier)) {
                    for (fill in listOf(p.blurred(tier), p.opaque(tier))) {
                        val base = fill.over(ground)
                        // Worst case: the sheen composited on top of the fill.
                        val lit = p.highlight.over(base)
                        for (bg in listOf(base, lit)) for ((iName, ink) in inks(style)) {
                            val r = Contrast.ratio(ink, bg)
                            worst = minOf(worst, r)
                            assertTrue("$style $tier over $gName: $iName = ${"%.2f".format(r)}", r >= 4.5)
                        }
                    }
                }
            }
        }
        report.append("worst ratio = ").append("%.2f".format(worst))
        println(report)
    }

    @Test
    fun `the light ground keeps every ink at AA`() {
        for (bg in listOf(lightGlass.background, lightGlass.surface, lightGlass.surfaceVariant)) {
            assertTrue(Contrast.ratio(lightGlass.onSurface, bg) >= 7.0)
            assertTrue(Contrast.ratio(lightGlass.onSurfaceVariant, bg) >= 4.5)
            assertTrue(Contrast.ratio(OSHIPurple, bg) >= 4.5)
            assertTrue(Contrast.ratio(Oshi.Chat.metaInk(dark = false), bg) >= 4.5)
        }
    }

    // ── what makes each palette read as glass ──────────────────────────────

    @Test
    fun `light panes are white and brighter than the ground they float on`() {
        val p = GlassPalette.Light
        assertTrue(Contrast.luminance(lightGlass.background) < Contrast.luminance(p.opaque(GlassTier.Card).over(lightGlass.background)))
        assertTrue(Contrast.luminance(lightGlass.surface) < Contrast.luminance(p.opaque(GlassTier.Chip).over(lightGlass.surface)))
        // A faintly cool ground, never a coloured one.
        val g = LightGlassGround
        assertTrue(g.blue >= g.red && g.blue >= g.green)
        assertTrue((maxOf(g.red, g.green, g.blue) - minOf(g.red, g.green, g.blue)) <= 8f / 255f)
        assertTrue(Contrast.luminance(g) > 0.85)
        // Light glass reads by its shadow: the lift is on; the rim is dark and heavier at the bottom.
        assertTrue(p.lift.alpha > 0f)
        assertTrue(p.hairlineBottom.alpha > p.hairlineTop.alpha)
        assertTrue(Contrast.luminance(p.hairlineTop.copy(alpha = 1f)) < 0.05)
    }

    @Test
    fun `dark glass is smoked neutral grey, never violet, a step above the surface`() {
        val p = GlassPalette.Dark
        for (tier in GlassTier.values()) for (fill in listOf(p.blurred(tier), p.opaque(tier))) {
            val c = fill.over(dark.surface)
            val spread = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)
            assertTrue("$tier is tinted ($c)", spread <= 6f / 255f)
            assertTrue("$tier is not above the surface", Contrast.luminance(c) >= Contrast.luminance(dark.surface) - 0.002)
        }
        // A grey ground swallows a faint rim: the dark rim is at least the OLED one.
        assertTrue(p.hairlineTop.alpha >= OledGlass.HAIRLINE_TOP_ALPHA)
        assertEquals(0f, p.lift.alpha, 0f)
    }

    @Test
    fun `without blur a glass fill is never more transparent than with it, in every palette`() {
        for (style in GlassStyle.values()) {
            val p = GlassPalette.of(style)
            for (tier in GlassTier.values()) {
                assertTrue("$style $tier", p.opaque(tier).alpha >= p.blurred(tier).alpha)
                assertEquals(p.blurred(tier), p.fill(tier, canBlur = true))
                assertEquals(p.opaque(tier), p.fill(tier, canBlur = false))
            }
            assertTrue(p.opaque(GlassTier.Bar).alpha >= 0.88f)
            assertTrue(p.opaque(GlassTier.Sheet).alpha >= 0.95f)
            assertTrue(p.opaque(GlassTier.Dialog).alpha >= 0.95f)
        }
    }

    @Test
    fun `dialog buttons keep AA on the dialog glass`() {
        // Light keeps the brand violet; dark grounds swap to the readable brand ink.
        assertEquals(null, GlassPalette.Light.buttonInk)
        for (bg in listOf(scrimmedBlack, scrimmedWhite)) {
            assertTrue(Contrast.ratio(OSHIPurple, GlassPalette.Light.blurred(GlassTier.Dialog).over(bg)) >= 4.5)
            assertTrue(Contrast.ratio(Oshi.BrandInkDark, GlassPalette.Dark.blurred(GlassTier.Dialog).over(bg)) >= 4.5)
        }
    }

    // ── defaults, switch and scheme ────────────────────────────────────────

    @Test
    fun `defaults and the stored choice`() {
        assertTrue(GlassDefaults.enabled(GlassStyle.Oled))
        // Never touched: each ground's default.
        for (s in GlassStyle.values()) assertEquals(GlassDefaults.enabled(s), GlassDefaults.resolve(null, s))
        // Touched: the same answer in every theme.
        for (s in GlassStyle.values()) {
            assertTrue(GlassDefaults.resolve(true, s))
            assertFalse(GlassDefaults.resolve(false, s))
        }
        assertEquals(GlassStyle.Oled, GlassDefaults.styleFor(dark = true, oled = true))
        assertEquals(GlassStyle.Dark, GlassDefaults.styleFor(dark = true, oled = false))
        assertEquals(GlassStyle.Light, GlassDefaults.styleFor(dark = false, oled = true))
        assertEquals(GlassStyle.Light, GlassDefaults.styleFor(dark = false, oled = false))
    }

    @Test
    fun `only light changes its scheme under glass, and only the ground and panes`() {
        assertEquals(dark, oshiColorScheme(dark = true, glass = true))
        assertEquals(oled, oshiColorScheme(dark = true, oledBlack = true, glass = true))
        assertNotEquals(light, lightGlass)
        assertEquals(light.onSurface, lightGlass.onSurface)
        assertEquals(light.primary, lightGlass.primary)
        assertEquals(LightGlassGround, lightGlass.background)
        assertEquals(LightGlassSurface, lightGlass.surface)
        assertEquals(Color.White, lightGlass.surfaceVariant)
        // Still a light scheme for every isDarkScheme() reader.
        assertTrue(Contrast.luminance(lightGlass.surface) > 0.5)
    }

    @Test
    fun `high contrast turns glass opaque`() {
        assertFalse(GlassAccessibility.wantsOpaque(contrast = 0f, highTextContrast = false))
        assertFalse(GlassAccessibility.wantsOpaque(contrast = -0.5f, highTextContrast = false))
        assertTrue(GlassAccessibility.wantsOpaque(contrast = 0.5f, highTextContrast = false))
        assertTrue(GlassAccessibility.wantsOpaque(contrast = 1f, highTextContrast = false))
        assertTrue(GlassAccessibility.wantsOpaque(contrast = 0f, highTextContrast = true))
    }

    // ── strings ────────────────────────────────────────────────────────────

    @Test
    fun `the switch is translated in every shipped language`() {
        val res = listOf("src/main/res", "app/src/main/res", "OSHI-Android/app/src/main/res").map { File(it) }.first { it.isDirectory }
        val dirs = res.listFiles()!!.filter { it.isDirectory && File(it, "strings.xml").isFile }
        val base = File(res, "values/strings.xml").readText()
        val baseTitle = Regex("""name="appearance_glass_effect">([^<]+)<""").find(base)!!.groupValues[1]
        for (d in dirs) {
            val xml = File(d, "strings.xml").readText()
            val title = Regex("""name="appearance_glass_effect">([^<]+)<""").find(xml)
            val footer = Regex("""name="appearance_glass_effect_footer">([^<]+)<""").find(xml)
            assertTrue("${d.name} title", title != null && title.groupValues[1].isNotBlank())
            assertTrue("${d.name} footer", footer != null && footer.groupValues[1].isNotBlank())
            if (d.name != "values") assertNotEquals("${d.name} is English", baseTitle, title!!.groupValues[1])
        }
    }
}
