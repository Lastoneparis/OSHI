package com.oshi.messenger.ui

import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oshi.messenger.ui.theme.Contrast
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.theme.ThemeSelection
import com.oshi.messenger.ui.theme.oshiColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __OLED_BLACK_2026_11_16__ « Noir OLED » / "OLED black".
 *
 * A modifier of the dark theme: Theme stays System / Light / Dark, and the
 * switch applies whenever the RESOLVED theme is dark (forced Dark, or System
 * on a dark phone). Default OFF.
 */
class OledBlackThemeTest {

    // ── selection ──────────────────────────────────────────────────────────

    @Test
    fun `theme preference resolves dark as before`() {
        assertTrue(ThemeSelection.resolveDark("dark", systemDark = false))
        assertFalse(ThemeSelection.resolveDark("light", systemDark = true))
        assertTrue(ThemeSelection.resolveDark("system", systemDark = true))
        assertFalse(ThemeSelection.resolveDark("system", systemDark = false))
        // Unknown / absent values follow the phone, as the old inline `when` did.
        assertTrue(ThemeSelection.resolveDark(null, systemDark = true))
        assertFalse(ThemeSelection.resolveDark("bogus", systemDark = false))
    }

    @Test
    fun `OLED black applies only when the resolved theme is dark`() {
        // Forced dark + switch on.
        assertTrue(ThemeSelection.resolveOled(ThemeSelection.resolveDark("dark", false), true))
        // System on a dark phone + switch on.
        assertTrue(ThemeSelection.resolveOled(ThemeSelection.resolveDark("system", true), true))
        // System on a light phone: inert, even with the switch on.
        assertFalse(ThemeSelection.resolveOled(ThemeSelection.resolveDark("system", false), true))
        // Forced light: inert.
        assertFalse(ThemeSelection.resolveOled(ThemeSelection.resolveDark("light", true), true))
        // Switch off: never.
        assertFalse(ThemeSelection.resolveOled(true, false))
    }

    @Test
    fun `light scheme ignores the OLED flag`() {
        assertEquals(oshiColorScheme(dark = false), oshiColorScheme(dark = false, oledBlack = true))
    }

    // ── the scheme itself ──────────────────────────────────────────────────

    private val oled = oshiColorScheme(dark = true, oledBlack = true)
    private val dark = oshiColorScheme(dark = true, oledBlack = false)

    @Test
    fun `background and surface are true black`() {
        assertEquals(Color.Black, oled.background)
        assertEquals(Color.Black, oled.surface)
        // …and the regular dark scheme is untouched.
        assertNotEquals(Color.Black, dark.background)
        assertNotEquals(Color.Black, dark.surface)
    }

    @Test
    fun `brand accents are kept`() {
        assertEquals(OSHIPurple, oled.primary)
        assertEquals(dark.primary, oled.primary)
        assertEquals(dark.secondary, oled.secondary)
        assertEquals(dark.error, oled.error)
    }

    private fun channels(c: Color) = Triple(c.red, c.green, c.blue)
    private fun isNeutral(c: Color): Boolean {
        val (r, g, b) = channels(c)
        return kotlin.math.abs(r - g) < 0.01f && kotlin.math.abs(g - b) < 0.01f
    }
    private fun maxChannel(c: Color) = maxOf(c.red, c.green, c.blue)

    @Test
    fun `flat surfaces stay black and elevated ones stay neutral near-black`() {
        // M3 lifts a container whose colour IS `surface` by `surfaceTint`. At
        // elevation 0 (top bars, the bottom bar, the Scaffold) nothing moves.
        assertEquals(Color.Black, oled.surfaceColorAtElevation(0.dp))
        // Sheets (1 dp), composer (2 dp), menus (3 dp), dialogs (6 dp): visibly
        // off black so a layer has an edge, but neutral — no brand-purple grey —
        // and never past #1A1A1A.
        for (e in listOf(1, 2, 3, 6, 8)) {
            val c = oled.surfaceColorAtElevation(e.dp)
            assertTrue("elevation $e dp is neutral: $c", isNeutral(c))
            assertTrue("elevation $e dp is lifted off black: $c", maxChannel(c) >= 0x0A / 255f - 0.002f)
            assertTrue("elevation $e dp stays near-black: $c", maxChannel(c) <= 0x1A / 255f)
        }
        // The dialog (6 dp) must be distinguishable from the sheet (1 dp).
        assertTrue(
            maxChannel(oled.surfaceColorAtElevation(6.dp)) > maxChannel(oled.surfaceColorAtElevation(1.dp))
        )
    }

    @Test
    fun `cards are near-black, not the dark scheme grey`() {
        assertTrue(isNeutral(oled.surfaceVariant))
        assertTrue(maxChannel(oled.surfaceVariant) <= 0x1A / 255f)
        assertTrue(maxChannel(oled.surfaceVariant) < maxChannel(dark.surfaceVariant))
    }

    @Test
    fun `text keeps WCAG AA contrast on every OLED ground`() {
        val grounds = listOf(
            oled.background, oled.surface, oled.surfaceVariant,
            oled.surfaceColorAtElevation(6.dp), Oshi.Bubble.receivedOled,
        )
        for (g in grounds) {
            assertTrue("onSurface on $g", Contrast.ratio(oled.onSurface, g) >= 4.5)
            assertTrue("onSurfaceVariant on $g", Contrast.ratio(oled.onSurfaceVariant, g) >= 4.5)
            assertTrue("metaInk on $g", Contrast.ratio(Oshi.Chat.metaInk(true), g) >= 4.5)
        }
    }

    // ── chat surfaces ──────────────────────────────────────────────────────

    @Test
    fun `incoming bubble is a readable dark grey on black`() {
        val bubble = Oshi.Bubble.received(darkTheme = true, oledBlack = true)
        assertEquals(Color(0xFF1C1C1E), bubble)
        // A bubble that is not distinguishable from the ground is not a bubble.
        assertTrue(Contrast.ratio(bubble, Color.Black) >= 1.2)
        assertTrue(Contrast.ratio(Color.White, bubble) >= 7.0)
        // Unchanged outside OLED.
        assertEquals(Oshi.Bubble.receivedDark, Oshi.Bubble.received(true))
        assertEquals(Oshi.Bubble.receivedLight, Oshi.Bubble.received(false, oledBlack = true))
    }

    @Test
    fun `chat ground is flat black with no glow in OLED`() {
        assertEquals(listOf(Color.Black, Color.Black), Oshi.Chat.groundColors(dark = true, oledBlack = true))
        assertEquals(0f, Oshi.Chat.brandGlow(dark = true, oledBlack = true))
        assertEquals(0f, Oshi.Chat.accentGlow(dark = true, oledBlack = true))
        // Regular dark keeps its indigo ramp and glows.
        assertEquals(
            listOf(Oshi.Chat.groundDarkTop, Oshi.Chat.groundDarkBottom),
            Oshi.Chat.groundColors(dark = true, oledBlack = false)
        )
        assertEquals(Oshi.Chat.brandGlow(true), Oshi.Chat.brandGlow(dark = true, oledBlack = false))
    }

    // ── persistence + strings ──────────────────────────────────────────────

    private fun mainRoot(): File {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        return candidates.first { it.isDirectory }
    }

    @Test
    fun `setting is persisted under its own key and defaults OFF`() {
        val vm = File(mainRoot(), "java/com/oshi/messenger/ui/viewmodel/SettingsViewModel.kt").readText()
        assertTrue(vm.contains("booleanPreferencesKey(\"oled_black\")"))
        assertTrue(vm.contains("preferences[KEY_OLED_BLACK] ?: false"))
        assertTrue(vm.contains("MutableStateFlow(false)\n    val oledBlack"))
    }

    @Test
    fun `every shipped locale names the setting`() {
        val res = File(mainRoot(), "res")
        val files = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .map { File(it, "strings.xml") }
            .filter { it.isFile && it.readText().contains("name=\"settings_theme_dark\"") }
        assertTrue("found the locales", files.size > 30)
        for (f in files) {
            val text = f.readText()
            assertTrue("${f.parentFile.name} has the title", text.contains("name=\"settings_theme_oled_black\""))
            assertTrue("${f.parentFile.name} has the subtitle", text.contains("name=\"settings_theme_oled_black_desc\""))
        }
        val fr = File(res, "values-fr/strings.xml").readText()
        assertTrue(fr.contains(">Noir OLED<"))
        val en = File(res, "values/strings.xml").readText()
        assertTrue(en.contains(">OLED black<"))
    }
}
