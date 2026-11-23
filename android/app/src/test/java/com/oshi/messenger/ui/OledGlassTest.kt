package com.oshi.messenger.ui

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import com.oshi.messenger.ui.components.ChatWallpaper
import com.oshi.messenger.ui.components.OledWallpaperDim
import com.oshi.messenger.ui.theme.Contrast
import com.oshi.messenger.ui.theme.GlassTier
import com.oshi.messenger.ui.theme.OledGlass
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.theme.SplashChoice
import com.oshi.messenger.ui.theme.ThemeBoot
import com.oshi.messenger.ui.theme.oshiColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __OLED_GLASS_2026_11_16__ / __OLED_SPLASH_2026_11_16__
 *
 * The glass tokens (fills, rim, glow), their legibility, the wallpaper dimming
 * rule, and the cold-start splash choice + its synchronous persistence.
 */
class OledGlassTest {

    private val oled = oshiColorScheme(dark = true, oledBlack = true)

    private fun Color.overBlack(): Color = with(Contrast) { this@overBlack.over(Color.Black) }

    // ── glass tokens ───────────────────────────────────────────────────────

    @Test
    fun `hairline is white 18 percent at the top fading to 4 percent`() {
        assertEquals(0.18f, OledGlass.HAIRLINE_TOP_ALPHA, 0.001f)
        assertEquals(0.04f, OledGlass.HAIRLINE_BOTTOM_ALPHA, 0.001f)
        assertEquals(Color.White.copy(alpha = 0.18f), OledGlass.HairlineTop)
        assertEquals(Color.White.copy(alpha = 0.04f), OledGlass.HairlineBottom)
        assertEquals(1f, OledGlass.HairlineWidth.value, 0f)
        // The sheen is a hint, never a wash over the text under it.
        assertTrue(OledGlass.HIGHLIGHT_ALPHA <= 0.06f)
    }

    @Test
    fun `every glass fill is a near-black neutral on the black ground`() {
        for (tier in GlassTier.values()) {
            for (fill in listOf(tier.blurred, tier.opaque)) {
                val c = fill.overBlack()
                val spread = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)
                assertTrue("$tier is tinted ($c)", spread <= 4f / 255f)
                assertTrue("$tier is not near-black ($c)", Contrast.luminance(c) < 0.015)
            }
        }
    }

    @Test
    fun `without blur a glass fill is never more transparent than with it`() {
        for (tier in GlassTier.values()) {
            assertTrue("$tier", tier.opaque.alpha >= tier.blurred.alpha)
            assertEquals(tier.blurred, tier.fill(canBlur = true))
            assertEquals(tier.opaque, tier.fill(canBlur = false))
        }
        // Floating layers stay legible on their own below API 31.
        assertTrue(GlassTier.Bar.opaque.alpha >= 0.88f)
        assertTrue(GlassTier.Sheet.opaque.alpha >= 0.95f)
        assertTrue(GlassTier.Dialog.opaque.alpha >= 0.95f)
    }

    @Test
    fun `text on every glass layer clears WCAG AA`() {
        val inks = listOf(
            "onSurface" to oled.onSurface,
            "onSurfaceVariant" to oled.onSurfaceVariant,
            "metaInk" to Oshi.Chat.metaInk(dark = true),
        )
        for (tier in GlassTier.values()) {
            for (fill in listOf(tier.blurred, tier.opaque)) {
                val ground = fill.overBlack()
                // The rim and sheen sit on top of the fill; measure the worst case.
                val lit = with(Contrast) { Color.White.copy(alpha = OledGlass.HIGHLIGHT_ALPHA).over(ground) }
                for ((name, ink) in inks) {
                    val r = Contrast.ratio(ink, lit)
                    assertTrue("$name on $tier = $r", r >= 4.5)
                }
            }
        }
    }

    @Test
    fun `dialog button ink clears AA on the dialog glass`() {
        for (fill in listOf(GlassTier.Dialog.blurred, GlassTier.Dialog.opaque)) {
            val ground = fill.overBlack()
            assertTrue(Contrast.ratio(Oshi.BrandInkDark, ground) >= 4.5)
            // ...where the raw brand fails, which is why the dialog swaps it.
            assertTrue(Contrast.ratio(com.oshi.messenger.ui.theme.OSHIPurple, ground) < 4.5)
        }
        // A filled button on the swapped primary keeps AA too.
        assertTrue(Contrast.ratio(com.oshi.messenger.ui.theme.GlassOnBrandInk, Oshi.BrandInkDark) >= 4.5)
    }

    @Test
    fun `incoming bubble glass is the OLED received colour`() {
        assertEquals(Oshi.Bubble.receivedOled, OledGlass.BubbleFill)
        assertEquals(Color(0xFF1C1C1E), OledGlass.BubbleFill)
        assertTrue(Contrast.ratio(Color.White, OledGlass.BubbleFill) >= 7.0)
    }

    @Test
    fun `focus glow is brand violet and faint`() {
        val g = OledGlass.GlowColor
        assertTrue("glow must be violet", g.blue > g.green && g.red > g.green)
        assertTrue(OledGlass.GLOW_ALPHA in 0.15f..0.40f)
    }

    // ── wallpaper dimming ──────────────────────────────────────────────────

    @Test
    fun `a white wallpaper is dimmed to a grey but never erased`() {
        val a = OledWallpaperDim.scrimAlpha(1.0)
        assertEquals(OledWallpaperDim.MAX, a, 0.0001f)
        assertTrue(a < 1f)
    }

    @Test
    fun `a dark wallpaper is left alone`() {
        assertEquals(0f, OledWallpaperDim.scrimAlpha(0.01), 0f)
        assertEquals(0f, OledWallpaperDim.scrimAlpha(OledWallpaperDim.TARGET), 0.0001f)
        // A photo keeps at least the regular dark-mode dimming.
        assertEquals(0.35f, OledWallpaperDim.scrimAlpha(0.01, floor = 0.35f), 0f)
        assertEquals(0.35f, OledWallpaperDim.scrimAlpha(Double.NaN, floor = 0.35f), 0f)
    }

    @Test
    fun `a mid wallpaper lands exactly on the target mean luminance`() {
        for (l in listOf(0.1, 0.2, 0.3, 0.45)) {
            val a = OledWallpaperDim.scrimAlpha(l)
            assertEquals("L=$l", OledWallpaperDim.TARGET, l * (1 - a), 1e-6)
        }
    }

    @Test
    fun `flat wallpapers are measured from their own colours`() {
        assertEquals(1.0, OledWallpaperDim.flatLuminance(ChatWallpaper.SolidColor(Color.White)), 1e-6)
        assertEquals(0.0, OledWallpaperDim.flatLuminance(ChatWallpaper.SolidColor(Color.Black)), 1e-6)
        assertEquals(0.5, OledWallpaperDim.flatLuminance(ChatWallpaper.Gradient(listOf(Color.White, Color.Black), "bw")), 1e-6)
        assertTrue(OledWallpaperDim.flatLuminance(ChatWallpaper.Default) > 0.5)
    }

    // ── cold-start splash ──────────────────────────────────────────────────

    @Test
    fun `splash choice follows theme and OLED switch`() {
        assertEquals(SplashChoice.Default, ThemeBoot.splashChoice("system", oledBlack = false))
        assertEquals(SplashChoice.Default, ThemeBoot.splashChoice("dark", oledBlack = false))
        assertEquals(SplashChoice.Default, ThemeBoot.splashChoice("light", oledBlack = true))
        assertEquals(SplashChoice.OledNight, ThemeBoot.splashChoice("system", oledBlack = true))
        assertEquals(SplashChoice.OledNight, ThemeBoot.splashChoice(null, oledBlack = true))
        // Forced Dark is a Compose-level choice the system splash cannot see.
        assertEquals(SplashChoice.OledAlways, ThemeBoot.splashChoice("dark", oledBlack = true))
    }

    @Test
    fun `default splash resets to the manifest theme`() {
        assertEquals(0, ThemeBoot.splashThemeRes(SplashChoice.Default))
        assertTrue(ThemeBoot.splashThemeRes(SplashChoice.OledNight) != 0)
        assertTrue(ThemeBoot.splashThemeRes(SplashChoice.OledAlways) != 0)
        assertTrue(ThemeBoot.splashThemeRes(SplashChoice.OledNight) != ThemeBoot.splashThemeRes(SplashChoice.OledAlways))
    }

    @Test
    fun `window is black only when OLED is in force`() {
        assertTrue(ThemeBoot.windowIsOled("dark", oledBlack = true, systemDark = false))
        assertTrue(ThemeBoot.windowIsOled("system", oledBlack = true, systemDark = true))
        assertFalse(ThemeBoot.windowIsOled("system", oledBlack = true, systemDark = false))
        assertFalse(ThemeBoot.windowIsOled("light", oledBlack = true, systemDark = true))
        assertFalse(ThemeBoot.windowIsOled("dark", oledBlack = false, systemDark = true))
    }

    @Test
    fun `boot mirror persists both keys and survives a reread`() {
        val p = FakePrefs()
        assertNull(ThemeBoot.readTheme(p))
        assertFalse(ThemeBoot.readOled(p))

        assertTrue(ThemeBoot.write(p, "dark", oledBlack = true))
        assertEquals("dark", ThemeBoot.readTheme(p))
        assertTrue(ThemeBoot.readOled(p))
        assertEquals(1, p.commits)

        // Same values: no disk write (it runs on every DataStore emission).
        assertFalse(ThemeBoot.write(p, "dark", oledBlack = true))
        assertEquals(1, p.commits)

        assertTrue(ThemeBoot.write(p, "dark", oledBlack = false))
        assertFalse(ThemeBoot.readOled(p))
        assertEquals(2, p.commits)

        // An explicit OFF is recorded even when it equals the default.
        val fresh = FakePrefs()
        assertTrue(ThemeBoot.write(fresh, "system", oledBlack = false))
        assertTrue(fresh.contains("oled_black"))
    }

    @Test
    fun `splash themes are black where they must be and only there`() {
        val res = resDir()
        val night = File(res, "values-night-v31/themes.xml").readText()
        val day = File(res, "values-v31/themes.xml").readText()
        val oledNightNight = styleBody(night, "Theme.OSHI.SplashOledNight")
        val oledNightDay = styleBody(day, "Theme.OSHI.SplashOledNight")
        val alwaysDay = styleBody(day, "Theme.OSHI.SplashOledAlways")
        assertTrue(oledNightNight.contains("windowSplashScreenBackground\">@android:color/black"))
        assertFalse("System + OLED on a LIGHT phone keeps the brand splash", oledNightDay.contains("@android:color/black"))
        assertTrue(alwaysDay.contains("windowSplashScreenBackground\">@android:color/black"))
        // Declared at the base level so R.style resolves below API 31.
        val base = File(res, "values/themes.xml").readText()
        assertTrue(base.contains("Theme.OSHI.SplashOledNight"))
        assertTrue(base.contains("Theme.OSHI.SplashOledAlways"))
    }

    @Test
    fun `no screen bypasses the glass wrappers`() {
        val root = File(resDir().parentFile, "java")
        val raw = Regex("""(?<![A-Za-z_.])(AlertDialog|ModalBottomSheet|DropdownMenu|Card|FilterChip)\(""")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in setOf("GlassComponents.kt", "Glass.kt") }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val code = line.substringBefore("//")
                    if (raw.containsMatchIn(code) && !code.trimStart().startsWith("*")) "${f.name}:${i + 1}" else null
                }
            }.toList()
        assertTrue("raw Material calls: $offenders", offenders.isEmpty())
    }

    private fun styleBody(xml: String, name: String): String {
        val start = xml.indexOf("<style name=\"$name\"")
        assertTrue("$name missing", start >= 0)
        val selfClosing = xml.indexOf("/>", start)
        val close = xml.indexOf("</style>", start)
        val gt = xml.indexOf(">", start)
        return if (selfClosing in 0..gt) "" else xml.substring(start, close)
    }

    private fun resDir(): File {
        val candidates = listOf("src/main/res", "app/src/main/res", "OSHI-Android/app/src/main/res")
        return candidates.map { File(it) }.first { it.isDirectory }
    }

    /** Minimal in-memory SharedPreferences. */
    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, Any?>()
        var commits = 0
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as String? ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as Int? ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as Long? ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as Float? ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as Boolean? ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pending = HashMap<String, Any?>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { map.clear() }
            override fun commit(): Boolean { map.putAll(pending); commits++; return true }
            override fun apply() { commit() }
        }
    }
}
