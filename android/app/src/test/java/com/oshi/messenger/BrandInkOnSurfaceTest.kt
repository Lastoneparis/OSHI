package com.oshi.messenger

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __CONTAINER_AND_ITS_INK_2026_11_08__
 *
 * N16 put call/video buttons on the contact card. I wrote them as plain
 * `OutlinedButton`s, whose default content colour is `colorScheme.primary` —
 * which in the dark scheme is the raw brand violet `OSHIPurple` (#5616EC) —
 * sitting on the sheet's own `#1A1A1A` surface. That measures about 2.25:1.
 * The same mistake on the iOS twin was `brandColor` over
 * `brandColor.opacity(0.12)`, which `Oshi.ContainerTintAlpha`'s own doc calls
 * "the recipe that failed three times" and `BubbleStyleManager.linkColour`
 * had already measured at ~2.1:1.
 *
 * `Oshi.brandInk(dark)` is the pair built for this. These assertions say what
 * the doc comments only described: the raw brand FAILS on a dark ground and
 * the dark ink PASSES, so nobody can quietly swap the ink back.
 *
 * Deliberately a measurement of the colour VALUES rather than of the composed
 * button: a Compose UI test for this would only run on the debug variant and
 * would not have caught the constant being changed.
 */
class BrandInkOnSurfaceTest {

    private fun lin(c: Double) = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    private fun lum(t: Triple<Double, Double, Double>) =
        0.2126 * lin(t.first) + 0.7152 * lin(t.second) + 0.0722 * lin(t.third)
    private fun hex(v: Long) = Triple((v shr 16 and 255) / 255.0, (v shr 8 and 255) / 255.0, (v and 255) / 255.0)
    private fun ratio(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>): Double {
        val la = lum(a); val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** `Oshi.BrandInkDark`. */
    private val brandInkDark = hex(0xB899FF)

    /** `OSHIPurple`, which is also `DarkColorScheme.primary`. */
    private val brandRaw = hex(0x5616EC)

    /** `DarkColorScheme.surface` — what a bottom sheet and the nav bar both paint. */
    private val darkSurface = hex(0x1A1A1A)

    /** `LightColorScheme`'s surface is white; the brand already clears it. */
    private val lightSurface = hex(0xFFFFFF)

    @Test
    fun `the dark brand ink is legible on the dark surface`() {
        val r = ratio(brandInkDark, darkSurface)
        assertTrue("brandInk(dark) on #1A1A1A is %.2f:1, must be >= 4.5".format(r), r >= 4.5)
    }

    @Test
    fun `the raw brand is NOT legible on the dark surface`() {
        // The negative control. If this ever starts passing, the dark surface
        // or the brand moved and the rule above needs re-deriving rather than
        // trusting — a guard whose premise has silently become false is worse
        // than no guard.
        val r = ratio(brandRaw, darkSurface)
        assertTrue("raw OSHIPurple on #1A1A1A is %.2f:1 — expected it to FAIL 4.5".format(r), r < 4.5)
    }

    @Test
    fun `the light brand ink is legible on the light surface`() {
        val r = ratio(brandRaw, lightSurface)
        assertTrue("OSHIPurple on white is %.2f:1, must be >= 4.5".format(r), r >= 4.5)
    }

    @Test
    fun `the brand-tinted container keeps its ink legible in both appearances`() {
        // The container the ink actually sits on is the surface with the brand
        // blended over it at `Oshi.ContainerTintAlpha` (0.22) — which is the
        // other half of the fix, and the half that makes raising the alpha safe.
        fun blend(fg: Triple<Double, Double, Double>, bg: Triple<Double, Double, Double>, a: Double) =
            Triple(
                a * fg.first + (1 - a) * bg.first,
                a * fg.second + (1 - a) * bg.second,
                a * fg.third + (1 - a) * bg.third,
            )
        val alpha = 0.22
        val onDark = ratio(brandInkDark, blend(brandRaw, darkSurface, alpha))
        val onLight = ratio(brandRaw, blend(brandRaw, lightSurface, alpha))
        assertTrue("dark: ink on tinted container is %.2f:1".format(onDark), onDark >= 4.5)
        assertTrue("light: ink on tinted container is %.2f:1".format(onLight), onLight >= 4.5)
    }
}
