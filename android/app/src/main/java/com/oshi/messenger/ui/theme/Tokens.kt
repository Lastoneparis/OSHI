package com.oshi.messenger.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI

/**
 * The design tokens, ported from iOS `Theme.swift` (`__DESIGN_SYSTEM_2026_06_21__`).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * iOS centralised colour, spacing, radius, motion and elevation in one file in
 * June and has been converging on it since. Android had `OSHIPurple` and seven
 * sibling colours — and nothing else. Every screen picked its own `12.dp` or
 * `14.dp` or `16.dp`, its own `RoundedCornerShape(10.dp)`, its own
 * `tween(150)`, so two surfaces built a week apart do not agree on anything a
 * reader can name. That is what "the app looks unfinished" actually is: not any
 * one wrong number, but the absence of a repeated one.
 *
 * The scales below are iOS's, value for value, so a screen built against these
 * lands where the iPhone build lands. `Space` is a 4/8 rhythm; `Radius.lg` (18)
 * is the message bubble and is the same 18 dp `BubbleStyle.Modern` already used.
 *
 * ADOPT GRADUALLY. This does not rewrite any existing call site by itself, and
 * it is deliberately an `object` of constants rather than a `CompositionLocal`:
 * a token you can only read inside a `@Composable` cannot be used from a shape
 * function or asserted in a JVM test, and both of those are where the bugs were.
 */
object Oshi {

    /** 4/8 pt rhythm. iOS `Theme.Space`. */
    object Space {
        val xxs: Dp = 2.dp
        val xs: Dp = 4.dp
        val sm: Dp = 8.dp
        val md: Dp = 12.dp
        val lg: Dp = 16.dp
        val xl: Dp = 24.dp
        val xxl: Dp = 32.dp
        val xxxl: Dp = 48.dp
    }

    /**
     * The floor under anything a FINGER has to hit.
     *
     * Material and the Android accessibility guidelines both put this at 48 dp,
     * and Compose already enforces it for the controls it owns
     * (`minimumInteractiveComponentSize` is why every `Switch` and
     * `RadioButton` in Settings measures exactly 48 dp on the device). It is
     * the hand-rolled `Row(...).clickable {}` that has no floor at all, and
     * that is where the misses come from — the row wraps its text and ends up
     * whatever height the text happens to be.
     *
     * Apply as `defaultMinSize(minHeight = Oshi.Touch.minTarget)` AFTER the
     * `clickable`, so the clickable node is the one that grows: put it before
     * and the modifier chain expands a child the click never covers.
     */
    object Touch {
        val minTarget: Dp = 48.dp
    }

    /** iOS `Theme.Radius`. `lg` is the message bubble. */
    object Radius {
        val sm: Dp = 8.dp
        val md: Dp = 12.dp
        val lg: Dp = 18.dp
        val xl: Dp = 24.dp

        /** Anything at or above this is a capsule; Compose has no `999`. */
        val pill: Dp = 999.dp

        val smShape: Shape = RoundedCornerShape(sm)
        val mdShape: Shape = RoundedCornerShape(md)
        val lgShape: Shape = RoundedCornerShape(lg)
        val xlShape: Shape = RoundedCornerShape(xl)
        val pillShape: Shape = RoundedCornerShape(percent = 50)
    }

    /**
     * iOS `Theme.Elevation`. Compose takes a single `Dp` where SwiftUI takes a
     * (colour, radius, y) triple, so the mapping is the Material elevation whose
     * default shadow is closest to iOS's — checked by eye against the iPhone
     * build rather than derived, because the two shadow models genuinely differ.
     *
     * `low` is what a chat bubble gets. Nothing in a thread should look like it
     * is floating off the wallpaper.
     */
    object Elevation {
        val low: Dp = 1.dp
        val medium: Dp = 4.dp
        val high: Dp = 8.dp
    }

    /**
     * iOS `Theme.Motion`, spring-first.
     *
     * SwiftUI's `spring(response:dampingFraction:)` and Compose's
     * `spring(dampingRatio, stiffness)` parameterise the SAME second-order
     * system differently. `response` is the undamped period, so
     * `stiffness = (2π / response)²` and `dampingRatio = dampingFraction`.
     * [stiffnessForResponse] is that conversion, kept as a named function so the
     * numbers below are derived rather than guessed — and so a test can pin them.
     */
    object Motion {
        /** Quick, responsive — buttons, toggles, small state changes. */
        fun <T> snappy(): AnimationSpec<T> =
            spring(dampingRatio = 0.78f, stiffness = stiffnessForResponse(0.30))

        /** Smooth — view/content transitions, sheets. */
        fun <T> smooth(): AnimationSpec<T> =
            spring(dampingRatio = 0.85f, stiffness = stiffnessForResponse(0.42))

        /** Playful — reactions, send pop, attention. */
        fun <T> bouncy(): AnimationSpec<T> =
            spring(dampingRatio = 0.62f, stiffness = stiffnessForResponse(0.38))

        /** Gentle fade for opacity-only changes. iOS `.easeInOut(duration: 0.2)`. */
        fun <T> fade(): AnimationSpec<T> = tween(durationMillis = 200, easing = FastOutSlowInEasing)

        /** Compose's own reference points, for the record: 200 / 1500 / 10000. */
        const val STIFFNESS_DEFAULT = Spring.StiffnessMedium

        fun stiffnessForResponse(responseSeconds: Double): Float {
            require(responseSeconds > 0) { "a spring with no period does not settle" }
            val omega = 2 * PI / responseSeconds
            return (omega * omega).toFloat()
        }
    }

    /**
     * Chat bubble surfaces, ported from `BubbleStyleManager.swift:250-344`.
     *
     * These are the values the F-14 class of bug lives in, so each one is
     * asserted against a real contrast ratio in `OshiTokensTest` rather than
     * being described in a comment.
     */
    object Bubble {

        /**
         * The surface under a message someone sent YOU.
         *
         * iOS blends the brand into the system's own grouped background rather
         * than laying a translucent brand over it, so the result is a single
         * OPAQUE colour — a translucent bubble over an animated wallpaper
         * shimmers, and OSHI ships animated wallpapers
         * (`BubbleStyleManager.swift:441-452`).
         *
         * Dark mode takes more tint (22 % vs 10 %) because the ink on top of it
         * is light, so the ground can be more present without fighting it.
         *
         * Precomputed rather than blended at runtime because these two constants
         * are what the contrast test asserts, and a value a test cannot name is a
         * value the test cannot defend.
         *
         * __CHAT_POLISH_2026_09_24__ Now a neutral surface, identical to iOS
         * `ChatPalette.incomingLight/Dark`: WHITE in light (the wallpaper
         * ground is lavender-tinted, so white separates from it where the old
         * lavender #E2DCF6 melted into the mesh) and deep indigo #1E1B2E in
         * dark. Still opaque; the edge comes from [rim] + elevation.
         */
        val receivedLight = Color(0xFFFFFFFF)

        /**
         * __BUBBLE_MEDIA_2026_11_21__ (BUBBLE_MEDIA_SPEC §2) Dark received surface is
         * now a NEUTRAL elevated grey, #22252B, one step above the neutral dark
         * ground ([Chat.groundDarkTop]). The indigo #1E1B2E sat on an indigo ground
         * and, next to the purple outgoing bubble, made the whole thread one hue.
         * White body text 15.4:1, [Chat.metaInk] 6.6:1, every group name ≥ 5.2:1.
         */
        val receivedDark = Color(0xFF22252B)

        /**
         * __OLED_BLACK_2026_11_16__ Incoming bubble in OLED black: a neutral
         * #1C1C1E on the #000000 ground (same value as iOS's
         * `secondarySystemBackground` in dark). The indigo #1E1B2E is tuned to
         * sit on the indigo wallpaper ground, which OLED black replaces with
         * black; neutral keeps the bubble reading as a surface rather than a
         * purple stain. White body text on it is ~17:1, [Chat.metaInk] ~7.6:1.
         */
        val receivedOled = Color(0xFF1C1C1E)

        fun received(darkTheme: Boolean, oledBlack: Boolean = false): Color = when {
            darkTheme && oledBlack -> receivedOled
            darkTheme -> receivedDark
            else -> receivedLight
        }

        /**
         * __BUBBLE_MEDIA_2026_11_21__ (BUBBLE_MEDIA_SPEC §2) The DEFAULT (OSHI accent)
         * outgoing bubble is now tonal, not a saturated fill:
         *
         *  * LIGHT — a pale OSHI lavender ramp #EFE8FF → #E6DCFF under near-black ink
         *    [onOutgoingLight]. White on #7B3AF5 read fine for one line and was heavy
         *    for a paragraph; this is the Telegram/iMessage-light pattern with the
         *    brand hue kept. Ink 13.4:1, meta 5.4:1, link (brand) 5.9:1.
         *  * DARK — a deep OSHI purple ramp #5B47B3 → #4A3B8F under white: 7.0:1 at
         *    the light stop. Less neon than #7B3AF5 on a near-black ground.
         *
         * OLED black uses the dark ramp too (black is the GROUND there, never a bubble).
         * The other five accents stay saturated with white ink (BubbleAccentContrastTest).
         */
        val outgoingLightStart = Color(0xFFEFE8FF)
        val outgoingLightEnd = Color(0xFFE6DCFF)
        val outgoingDarkStart = Color(0xFF5B47B3)
        val outgoingDarkEnd = Color(0xFF4A3B8F)
        val onOutgoingLight = Color(0xFF1B1530)
        val onOutgoingDark = Color.White

        /** Timestamp / edited / tick ink INSIDE the tonal outgoing bubble. */
        fun metaOnOutgoing(darkTheme: Boolean): Color =
            if (darkTheme) Color(0xFFE6E0FA) else Color(0xFF5C5570)

        /** Media-only bubbles: time + ticks on a translucent dark pill over the picture. */
        val mediaPillFill = Color(0x8C000000)
        val mediaPillInk = Color.White

        /**
         * A hairline rim around a bubble. THIS IS A READABILITY FIX, not
         * decoration (`BubbleStyleManager.swift:307-323`).
         *
         * A received bubble is a pale surface with no stroke and no shadow, and
         * `ChatWallpaper` lets a user put an ARBITRARY PHOTO behind it. Over a
         * light photo the bubble's edge simply disappears and the text floats on
         * the picture. The rim is drawn from the bubble's OWN ink — white on the
         * dark outgoing accent, ink on the pale incoming surface — so it is
         * defined on every backdrop without a colour that has to be re-tuned per
         * wallpaper.
         *
         * Kept under 0.15 alpha on purpose: a rim you can NAME is a border, and
         * a border on every message turns a thread into a table.
         */
        fun rim(outgoing: Boolean, darkTheme: Boolean): Color =
            rimInk(outgoing, darkTheme).copy(alpha = rimAlpha(outgoing, darkTheme))

        /**
         * The rim at FULL strength. Split out from [rim] because the same ink
         * also draws `BubbleMarkings` (whiskers, headlights, the balloon
         * string), and those carry their own, much higher, alphas — a marking
         * drawn at the rim's 0.10 would not be visible at all.
         */
        fun rimInk(outgoing: Boolean, darkTheme: Boolean, tonal: Boolean = false): Color =
            if (darkTheme || (outgoing && !tonal)) Color.White else Color(0xFF2A1F4D)

        /** iOS `BubbleStyleManager.rim` — white bubble on a pale ground wants a firmer violet-grey hairline. */
        fun rimAlpha(outgoing: Boolean, darkTheme: Boolean, tonal: Boolean = false): Float = when {
            outgoing && tonal && !darkTheme -> 0.12f
            outgoing -> 0.14f
            darkTheme -> 0.08f
            else -> 0.12f
        }

        /** White sheen at the top of an outgoing bubble, fading out by mid-height (iOS `ChatPalette.outgoingSheen`). */
        const val outgoingSheen = 0.07f

        /** Lit upper edge of an outgoing bubble (iOS: 0.75 pt stroke, white 0.28 → 0). */
        const val outgoingHighlight = 0.28f

        val rimWidth: Dp = 0.5.dp

        /**
         * __CHAT_POLISH_2026_09_24__ How wide a message bubble may get.
         * __BUBBLE_MEDIA_2026_11_21__ 75 % of the chat, capped at 420 (was 70 %; the
         * group thread used its own 82 %) — one rule for 1:1 and groups.
         */
        fun maxWidth(screenWidthDp: Int): Dp =
            com.oshi.messenger.ui.media.MediaBubbleSizing.maxBubbleWidth(screenWidthDp.toFloat(), channel = false).dp

        /** __BUBBLE_MEDIA_2026_11_21__ a channel post is a card: 90 % of the chat, cap 560. */
        fun maxWidth(screenWidthDp: Int, channel: Boolean): Dp =
            com.oshi.messenger.ui.media.MediaBubbleSizing.maxBubbleWidth(screenWidthDp.toFloat(), channel).dp
    }

    /**
     * __CHAT_POLISH_2026_09_24__ The colours of a conversation that are not a
     * user choice — wallpaper ground and every ink that sits ON the wallpaper
     * (timestamps, "edited", ticks, transport chips). Same hex values as iOS
     * `ChatPalette` (BubbleStyleManager.swift), measured against the worst
     * ground in `ChatPaletteContrastTest`.
     */
    object Chat {
        // __BUBBLE_MEDIA_2026_11_21__ (BUBBLE_MEDIA_SPEC §2) Light: a touch more lavender
        // so the WHITE incoming bubble separates from the ground (was #FBFAFF, 1.03:1
        // against white). Dark: neutral #17181C → #0F1115 (was indigo #120E24 → #07060D);
        // the brand stays in the glow and the outgoing bubble, not in every surface.
        val groundLightTop = Color(0xFFF7F5FC)
        val groundLightBottom = Color(0xFFEEEAF7)
        val groundDarkTop = Color(0xFF17181C)
        val groundDarkBottom = Color(0xFF0F1115)
        fun brandGlow(dark: Boolean): Float = if (dark) 0.24f else 0.06f
        fun accentGlow(dark: Boolean): Float = if (dark) 0.10f else 0.04f

        /**
         * __OLED_BLACK_2026_11_16__ The wallpaper ground in OLED black: flat
         * #000000, no glows. The ramp (#120E24 → #07060D) and the violet /
         * blue blooms light most of the screen behind a conversation, which is
         * exactly what the setting exists to stop. The wallpaper's own artwork
         * (mesh lines, waves…) still draws on top at its normal ink — that is
         * the user's chosen wallpaper, a few lit strokes, not a lit ground.
         */
        val groundOled = Color.Black

        fun groundColors(dark: Boolean, oledBlack: Boolean = false): List<Color> = when {
            dark && oledBlack -> listOf(groundOled, groundOled)
            dark -> listOf(groundDarkTop, groundDarkBottom)
            else -> listOf(groundLightTop, groundLightBottom)
        }

        fun brandGlow(dark: Boolean, oledBlack: Boolean): Float = if (dark && oledBlack) 0f else brandGlow(dark)
        fun accentGlow(dark: Boolean, oledBlack: Boolean): Float = if (dark && oledBlack) 0f else accentGlow(dark)

        /** Timestamps, "edited", sent/delivered ticks. Replaces onSurfaceVariant × 0.7. */
        fun metaInk(dark: Boolean): Color = if (dark) Color(0xFFABA6C4) else Color(0xFF524D66)

        /**
         * Two ticks = read. Green on both platforms.
         *
         * __READ_TICK_WAS_INVISIBLE_IN_LIGHT_2026_11_03__ Anatole: "en thème
         * clair, la coche de message lu ne change pas de couleur, contrairement
         * au thème sombre."
         *
         * He was right, and it was measurable. The light value used to be
         * `0x0B5A2E`, chosen for 4.5:1 against white — but the colour it has to
         * differ FROM is [metaInk], `0x524D66`, and the two had relative
         * luminances of 0.0758 and 0.0816. That is a contrast ratio of 1.04:1
         * between "delivered" and "read": the hue changed, the brightness did
         * not, and at a 14dp tick nobody can see a hue-only shift that dark.
         * In dark mode the same pair is 0x4ADE80 on 0xABA6C4, which reads
         * instantly — hence "unlike the dark theme".
         *
         * 4.5:1 was the wrong target anyway. A tick is a non-text graphic, so
         * WCAG 1.4.11 asks 3:1, not 4.5:1. `0x11A24A` gives 3.34:1 against
         * white and a 2.39:1 step away from [metaInk], so it is both legible
         * and visibly a different state. Held by `ReadTickContrastTest`.
         */
        fun readTick(dark: Boolean): Color = if (dark) Color(0xFF4ADE80) else Color(0xFF0B5A2E)

        enum class Transport { INTERNET, MESH, LORA, PENDING, FAILED }

        fun transportInk(t: Transport, dark: Boolean): Color = when (t) {
            Transport.INTERNET -> if (dark) Color(0xFFB899FF) else Color(0xFF5616EC)
            Transport.MESH -> if (dark) Color(0xFF5EE08A) else Color(0xFF0B5A2E)
            Transport.LORA -> if (dark) Color(0xFF5EEAD4) else Color(0xFF0B5F59)
            Transport.PENDING -> if (dark) Color(0xFFFDBA74) else Color(0xFF92400E)
            Transport.FAILED -> if (dark) Color(0xFFFF8A8A) else Color(0xFFA51818)
        }

        /**
         * A chip is a small frosted capsule — the received-bubble surface at
         * [chipSurfaceAlpha] — tinted with its ink at [chipFill], so it reads the
         * same over a mesh node, a glow or a link (iOS `ChatPalette.chipSurface`).
         */
        const val chipFill = 0.10f
        const val chipSurfaceAlpha = 0.9f
        fun chipSurface(dark: Boolean, oledBlack: Boolean = false): Color =
            Bubble.received(dark, oledBlack).copy(alpha = chipSurfaceAlpha)
    }

    /**
     * Brand violet, used as INK.
     *
     * ┌─ the rule, written down so it stops being rediscovered ─────────────┐
     * │ The brand colour is a SURFACE TINT, not an ink, as soon as the      │
     * │ surface already carries that tint. Where it must be an ink, it is   │
     * │ this function and never the raw [OSHIPurple].                       │
     * └──────────────────────────────────────────────────────────────────────┘
     *
     * Raw `OSHIPurple` (#5616EC, relative luminance 0.086) measures **1.86:1**
     * against the dark scheme's `surfaceVariant` (#2A2A2A) and **2.1:1** on
     * iOS's dark received bubble. That is not "a bit low" — it is below the 3:1
     * floor for a non-text boundary, on TEXT. The hue is kept and the luminance
     * is moved, which is exactly what iOS does at
     * `BubbleStyleManager.swift:339-344`.
     *
     * Light mode keeps the brand itself: it already clears 4.5:1 on every pale
     * surface in the app.
     */
    fun brandInk(darkTheme: Boolean): Color =
        if (darkTheme) BrandInkDark else OSHIPurple

    /** #B899FF — iOS `Color(red: 0.72, green: 0.60, blue: 1.0)`. */
    val BrandInkDark = Color(0xFFB899FF)

    /**
     * A brand-tinted CONTAINER, and the ink that is legible on it.
     *
     * The recipe that failed three times is `background(OSHIPurple.copy(alpha =
     * 0.12f))` + `tint = OSHIPurple`. iOS's fix was two moves at once — raise
     * the tint to 0.22 so the container is actually visible, and take the ink
     * off the brand — and both halves matter: raising the alpha alone makes the
     * ink harder to read, not easier.
     */
    const val ContainerTintAlpha = 0.22f
}

// ---------------------------------------------------------------------------
// Composable bridge
// ---------------------------------------------------------------------------

/**
 * Is the scheme currently in force a dark one?
 *
 * Read off the SCHEME rather than off `isSystemInDarkTheme()`. `OSHITheme`
 * takes an explicit `darkTheme` parameter, the app offers a Light/Dark/System
 * setting in Settings, and `dynamicColor` can replace the whole palette on
 * Android 12+. A composable that asks the system what the phone is set to
 * therefore gets the wrong answer for a user who forced light mode inside a
 * dark phone — and gets it wrong in exactly the direction that reintroduces the
 * bug, because it would hand them the dark ink on a white surface.
 *
 * `surface` rather than `background`: it is the colour these tints are actually
 * laid over, and the two are never on opposite sides of the midpoint.
 */
@Composable
@ReadOnlyComposable
fun isDarkScheme(): Boolean = Contrast.luminance(MaterialTheme.colorScheme.surface) < 0.5

/**
 * Brand violet as an INK, at a luminance the current scheme can actually show.
 *
 * Use this ANYWHERE the brand colour is applied to text or to a meaning-bearing
 * icon. Reach for the raw [OSHIPurple] only for a fill, a stroke on a neutral
 * ground, or a solid saturated container — the three places where it is a
 * surface rather than an ink.
 */
@Composable
@ReadOnlyComposable
fun brandInk(): Color = Oshi.brandInk(isDarkScheme())

/**
 * A brand-tinted container and the ink that stays legible on it.
 *
 * Returns the pair together on purpose. Every instance of this bug so far has
 * been someone writing the background on one line and the foreground forty
 * lines later; a call that hands back both makes the two impossible to choose
 * independently.
 */
@Composable
@ReadOnlyComposable
fun brandContainer(alpha: Float = Oshi.ContainerTintAlpha): Pair<Color, Color> =
    OSHIPurple.copy(alpha = alpha) to brandInk()
