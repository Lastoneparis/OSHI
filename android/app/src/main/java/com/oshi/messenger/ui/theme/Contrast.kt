package com.oshi.messenger.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * WCAG 2.1 relative luminance and contrast ratio, as PRODUCTION code.
 *
 * WHY THIS IS NOT IN A TEST FILE
 * ------------------------------
 * "Brand-coloured ink on a brand-tinted background" has now been found in this
 * app **four** separate times (iOS closed its own instances in `e790c77` and
 * `5221641`; the Android counterparts were closed as F-14; the reply-quote
 * sender name in a received bubble was still doing it, at **1.86:1** in dark
 * mode, when this file was written).
 *
 * It keeps coming back for a structural reason: the failure is invisible in
 * light mode. A 12 % brand fill over white is a pale lilac and the brand violet
 * on top of it is perfectly legible; the SAME two lines of code over the dark
 * scheme's near-black give a near-black ground with a ~0.086-luminance ink
 * sitting on it. Nobody reviewing the light screenshot sees anything wrong.
 *
 * The only defence that survives the next person editing a hue is an assertion
 * on a NUMBER. That needs the maths to exist somewhere both the app and the
 * tests can reach — a private copy inside one test class (which is where it
 * lived) can only guard the values that one class happens to look at.
 *
 * All three functions are pure and allocation-free, so they are also cheap
 * enough to call from a composable when a colour genuinely has to be chosen at
 * runtime (see [readableOn]).
 */
object Contrast {

    /** WCAG AA for body text and for any icon carrying meaning. */
    const val AA_NORMAL = 4.5

    /** WCAG AA for text at 18 pt+, or 14 pt+ bold. */
    const val AA_LARGE = 3.0

    /** WCAG AA for a non-text UI boundary — a rim, a divider, a focus ring. */
    const val AA_NON_TEXT = 3.0

    /**
     * sRGB channel linearisation. The 0.03928 knee and the 2.4 exponent are the
     * WCAG 2.1 definition verbatim; do not "simplify" this to `c^2.2`, which
     * disagrees by enough to flip a borderline pair.
     */
    private fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /**
     * Relative luminance, 0 (black) to 1 (white).
     *
     * Alpha is deliberately IGNORED: a translucent colour has no luminance of
     * its own, only the luminance of whatever it ends up composited over. Pass
     * the composited colour instead — [over] is there to produce it, and passing
     * a raw `brand.copy(alpha = 0.12f)` here is precisely the mistake that lets
     * a 12 % wash look like it has the brand's own contrast.
     */
    fun luminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    /**
     * Contrast ratio between two OPAQUE colours, 1.0 … 21.0. Symmetric, so the
     * order of the arguments does not matter.
     */
    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Composite `this` (which may be translucent) over an opaque [background],
     * giving the colour that is actually on the screen.
     *
     * This is the function whose absence causes the bug. `OSHIPurple.copy(alpha
     * = 0.12f)` is not a pale lilac — it is a pale lilac over white and a
     * near-black over #0F0F0F, and only one of those two can hold brand ink.
     */
    fun Color.over(background: Color): Color {
        val a = alpha
        if (a >= 1f) return this
        return Color(
            red = red * a + background.red * (1 - a),
            green = green * a + background.green * (1 - a),
            blue = blue * a + background.blue * (1 - a),
            alpha = 1f
        )
    }

    /**
     * The first candidate that clears [minimum] against [background], or — when
     * none does — whichever candidate is furthest from it.
     *
     * The fallback is the point: a helper that returns `null` or throws when
     * nothing fits gets a `?: brand` bolted on at the call site and the whole
     * check evaporates. Returning the best available means the worst case of a
     * badly chosen palette is "less contrast than we asked for", never "the
     * unreadable one we started with".
     */
    // `candidates` is a List, not a vararg: Color is a value class, and Kotlin
    // prohibits a vararg of one ("Prohibited vararg parameter type").
    fun readableOn(
        background: Color,
        candidates: List<Color>,
        minimum: Double = AA_NORMAL
    ): Color {
        require(candidates.isNotEmpty()) { "readableOn needs at least one candidate ink" }
        candidates.firstOrNull { ratio(it, background) >= minimum }?.let { return it }
        return candidates.maxByOrNull { ratio(it, background) }!!
    }
}
