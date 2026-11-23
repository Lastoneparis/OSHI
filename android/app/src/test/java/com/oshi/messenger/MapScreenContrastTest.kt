package com.oshi.messenger

import androidx.compose.ui.graphics.Color
import com.oshi.messenger.ui.theme.Contrast
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.Oshi
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brand-ink-on-brand-tint, pinned as a NUMBER for the map screens.
 *
 * `ui/theme/Contrast.kt` exists because this exact recipe —
 * `background(OSHIPurple.copy(alpha = 0.1f))` + `tint = OSHIPurple` — has been
 * found in this app four separate times, and it is invisible in a light-mode
 * review: over white the 10 % wash is pale lilac and brand violet on it reads
 * fine, while over the dark scheme's near-black the same two lines give a
 * near-black ground under a 0.086-luminance ink.
 *
 * The map screens were the fourth site and had never adopted the sanctioned
 * pair. Fixed 2026-08-23 in:
 *
 * * `MapScreen.kt` — POI card category badge, and four map-control tints
 * * `MapSearchScreen.kt` — two result-row icon tiles, category-grid icons
 * * `MapNavigationScreen.kt` — the current-maneuver row
 *
 * These assertions are on the composited colours the user actually sees, not
 * on the raw tokens, because a translucent colour has no contrast of its own.
 */
class MapScreenContrastTest {

    /** The dark scheme's `surface` — `Theme.kt:119`. */
    private val darkSurface = Color(0xFF1A1A1A)

    /** The dark scheme's `surfaceVariant` — `Theme.kt:121`. */
    private val darkSurfaceVariant = Color(0xFF2A2A2A)

    /** The light scheme's surface. */
    private val lightSurface = Color(0xFFFFFFFF)

    private fun ratioOnTint(ink: Color, tintAlpha: Float, ground: Color): Double {
        // `over` is a MEMBER extension of the Contrast object, so it is only in
        // scope inside `with(Contrast)` — it cannot be imported.
        val container = with(Contrast) { OSHIPurple.copy(alpha = tintAlpha).over(ground) }
        return Contrast.ratio(ink, container)
    }

    // ───────────────────────────────────────────────────────────────────
    // The bug, still measurable — so the numbers below mean something
    // ───────────────────────────────────────────────────────────────────

    /**
     * If this ever starts passing, the palette moved and every threshold in
     * this file needs re-deriving. It is here so the "fixed" assertions cannot
     * quietly become vacuous.
     */
    @Test
    fun `the old recipe really does fail in dark mode`() {
        val bad = ratioOnTint(OSHIPurple, 0.1f, darkSurface)
        assertTrue(
            "expected the old 10%% wash + raw brand ink to fail AA, measured $bad",
            bad < Contrast.AA_NORMAL
        )
        assertTrue(
            "expected it to fail even the 3:1 non-text floor, measured $bad",
            bad < Contrast.AA_NON_TEXT
        )
    }

    /** Raw brand as an icon tint directly on the dark surface — the FAB case. */
    @Test
    fun `raw brand ink on the dark surface fails the icon floor`() {
        val bad = Contrast.ratio(OSHIPurple, darkSurface)
        assertTrue(
            "expected raw OSHIPurple on #1A1A1A to fail 3:1, measured $bad",
            bad < Contrast.AA_NON_TEXT
        )
    }

    // ───────────────────────────────────────────────────────────────────
    // The fix, measured
    // ───────────────────────────────────────────────────────────────────

    /**
     * POI card category badge and the search-result icon tiles: brand container
     * at [Oshi.ContainerTintAlpha] with `brandInk` on top, in BOTH schemes.
     */
    @Test
    fun `the sanctioned container and ink pair clears AA in both schemes`() {
        val dark = ratioOnTint(Oshi.brandInk(true), Oshi.ContainerTintAlpha, darkSurface)
        assertTrue(
            "dark scheme badge measured $dark, need >= ${Contrast.AA_NORMAL}",
            dark >= Contrast.AA_NORMAL
        )

        val light = ratioOnTint(Oshi.brandInk(false), Oshi.ContainerTintAlpha, lightSurface)
        assertTrue(
            "light scheme badge measured $light, need >= ${Contrast.AA_NORMAL}",
            light >= Contrast.AA_NORMAL
        )
    }

    /**
     * The map-control buttons and the search pill's download icon: brand ink
     * straight onto `surface`, no container. Icons carry meaning, so the floor
     * is 3:1.
     */
    @Test
    fun `brand ink as a bare icon tint clears the icon floor in both schemes`() {
        val dark = Contrast.ratio(Oshi.brandInk(true), darkSurface)
        assertTrue(
            "dark control tint measured $dark, need >= ${Contrast.AA_NON_TEXT}",
            dark >= Contrast.AA_NON_TEXT
        )

        val light = Contrast.ratio(Oshi.brandInk(false), lightSurface)
        assertTrue(
            "light control tint measured $light, need >= ${Contrast.AA_NON_TEXT}",
            light >= Contrast.AA_NON_TEXT
        )
    }

    /** The category-grid chips sit on `surfaceVariant`, not on `surface`. */
    @Test
    fun `brand ink clears the icon floor on surfaceVariant too`() {
        val dark = Contrast.ratio(Oshi.brandInk(true), darkSurfaceVariant)
        assertTrue(
            "dark grid icon measured $dark, need >= ${Contrast.AA_NON_TEXT}",
            dark >= Contrast.AA_NON_TEXT
        )
    }

    /**
     * The active states are solid brand with white ink — unchanged by this
     * pass, but asserted so a future palette move cannot break the half that
     * currently works while attention is on the half that did not.
     */
    @Test
    fun `white on solid brand still clears AA`() {
        val r = Contrast.ratio(Color.White, OSHIPurple)
        assertTrue("white on brand measured $r", r >= Contrast.AA_NORMAL)
    }

    /**
     * Raising the container alpha ALONE is not the fix — it makes the ink
     * harder to read, not easier. Recorded so nobody tries half of it.
     */
    @Test
    fun `raising the tint alone does not rescue the raw brand ink`() {
        val r = ratioOnTint(OSHIPurple, Oshi.ContainerTintAlpha, darkSurface)
        assertTrue(
            "0.22 tint with raw brand ink measured $r; it must still fail",
            r < Contrast.AA_NORMAL
        )
    }
}
