package com.oshi.messenger.ui.theme

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.composed
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

// ─────────────────────────────────────────────────────────────────────────
// __OLED_GLASS_2026_11_16__ « Noir OLED » as crystal glass.
//
// The ground stays #000000 — an unlit pixel. What changes is everything that
// FLOATS on it: bars, sheets, dialogs, menus, cards, chips, the incoming
// bubble, call controls. On a pure black ground a flat grey panel reads as a
// hole punched in the screen; real glass reads by its EDGE (light catching the
// rim, brightest at the top where the light comes from) and by a faint sheen
// just inside that edge. So every glass layer is the same three things:
//
//   1. a near-black FILL, translucent where something can show through it
//      (a bar over a wallpaper, a sheet over a blurred screen) and opaque where
//      translucency would only shimmer (chat bubbles — no per-row blur, the
//      thread must scroll at 120 Hz);
//   2. a 1 dp HAIRLINE drawn as a vertical gradient, white 18 % at the top to
//      4 % at the bottom — the lit rim;
//   3. a top inner HIGHLIGHT, white 5 % fading to nothing within ~28 dp.
//
// Focus — the send button, the selected tab / chip, the FAB — gets a soft
// brand-violet GLOW, and nothing else does: one lit thing per screen region,
// or the glow stops meaning "this one".
//
// Blur is real (RenderEffect / cross-window blur, API 31+) only where it pays:
// the chat bars (a blurred second copy of the wallpaper, `FrostedChatBar`) and
// the dialog / sheet windows (blur-behind). Below API 31, or where the device
// has cross-window blur switched off (battery saver, some GPUs), the fills are
// simply more opaque — see [GlassTier.fill] — so legibility never depends on
// the blur.
//
// Every value here is held by `OledGlassTest`, including WCAG AA (4.5:1) for
// body and secondary text composited over each fill on the black ground.
// ─────────────────────────────────────────────────────────────────────────

object OledGlass {
    /** Lit top edge of the hairline. */
    const val HAIRLINE_TOP_ALPHA = 0.18f
    /** Bottom edge of the hairline — present, barely. */
    const val HAIRLINE_BOTTOM_ALPHA = 0.04f
    /** Inner sheen at the very top of a glass layer. */
    const val HIGHLIGHT_ALPHA = 0.05f
    /** How far down the sheen reaches before it is gone. */
    val HighlightDepth: Dp = 28.dp
    val HairlineWidth: Dp = 1.dp

    val HairlineTop = Color.White.copy(alpha = HAIRLINE_TOP_ALPHA)
    val HairlineBottom = Color.White.copy(alpha = HAIRLINE_BOTTOM_ALPHA)

    /** The brand violet the focus glow is drawn in. */
    val GlowColor = OSHIPurpleLight
    /** Peak alpha of the innermost glow ring; the rings fade outward from it. */
    const val GLOW_ALPHA = 0.30f
    /** How far the glow spreads past the element's edge. */
    val GlowSpread: Dp = 10.dp

    /** The opaque incoming-bubble glass (iOS `secondarySystemBackground`, dark). */
    val BubbleFill = Color(0xFF1C1C1E)

    /** Call / editor ground: pure black, the same as the app ground. */
    val Ground = Color.Black

    /**
     * Brush for the hairline. Built for a given height so the gradient spans
     * exactly the element, top to bottom.
     */
    fun hairlineBrush(height: Float, top: Float = HAIRLINE_TOP_ALPHA, bottom: Float = HAIRLINE_BOTTOM_ALPHA): Brush =
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = top),
            1f to Color.White.copy(alpha = bottom),
            startY = 0f,
            endY = height.coerceAtLeast(1f)
        )
}

/**
 * The glass layers, from the flattest to the most raised. Each has a fill for a
 * device that blurs what is behind it and a more opaque one for a device that
 * does not; a layer that never has anything behind it but black (cards, chips)
 * uses the same value for both.
 *
 * Fills are white-on-black alphas or near-black neutrals — never a purple-grey:
 * a tinted panel on OLED black is the "purple stain" the setting exists to stop.
 */
enum class GlassTier(val blurred: Color, val opaque: Color) {
    /** Top bar / composer / bottom nav over a wallpaper. */
    Bar(Color(0xFF0A0A0C).copy(alpha = 0.72f), Color(0xFF0A0A0C).copy(alpha = 0.90f)),
    /** Settings / mail cards, tiles, fields on the black ground. */
    Card(OledSurfaceVariant, OledSurfaceVariant),
    /** Chips, reaction bar, pill fields. */
    Chip(Color(0xFF1C1C1E).copy(alpha = 0.78f), Color(0xFF1C1C1E).copy(alpha = 0.92f)),
    /** Bottom sheets. */
    Sheet(Color(0xFF121214).copy(alpha = 0.86f), Color(0xFF121214).copy(alpha = 0.97f)),
    /** Menus and popups. */
    Menu(Color(0xFF19191C).copy(alpha = 0.90f), Color(0xFF19191C).copy(alpha = 0.98f)),
    /** Alert dialogs. */
    Dialog(Color(0xFF17171A).copy(alpha = 0.86f), Color(0xFF17171A).copy(alpha = 0.97f)),
    /** Call controls over a video or a black ground. */
    Control(Color(0xFF1C1C1E).copy(alpha = 0.62f), Color(0xFF1C1C1E).copy(alpha = 0.85f));

    fun fill(canBlur: Boolean): Color = if (canBlur) blurred else opaque
}

// ─────────────────────────────────────────────────────────────────────────
// __GLASS_ALL_THEMES_2026_11_21__ Glass in every theme.
//
// « Vous pensez que l'effet flou/transparent du mode extra sombre est possible
// en thème clair et sombre classique ? » — yes. The OLED glass above is ONE of
// three palettes; Settings → Appearance → « Effet verre » switches the look on
// or off in Light, Dark and Extra dark alike.
//
// The three layers stay the same (fill, rim, sheen, plus the violet focus
// glow); what changes per ground is how each one is made visible:
//
// * [GlassStyle.Oled]  — unchanged: near-black fills, white rim lit from the
//   top, the glass reads by its EDGE on the unlit ground.
// * [GlassStyle.Dark]  — smoked glass on the classic #0F0F0F / #1A1A1A ground:
//   neutral greys a step above the surface, translucent where something can
//   show through, the same lit white rim a touch stronger (a grey ground
//   swallows a faint rim that black would show).
// * [GlassStyle.Light] — frosted white: on a white ground a white rim is
//   invisible, so the light palette inverts the cue. The panes are white,
//   brighter than a faintly cool ground (`LightGlassColorScheme`), the rim is a
//   soft graphite hairline darker at the bottom (light from above), the sheen
//   is a white gloss, and a diffuse shadow ([glassLift]) lifts the pane off the
//   ground — frosted glass in daylight reads by its shadow, not its rim.
//
// Contrast: every fill × ink combination is held to WCAG AA (4.5:1) by
// `GlassThemesTest`, measured over the ground the layer actually sits on, with
// the sheen composited on top (the worst case).
//
// High contrast (Android 14 « Contrast » medium/high, or « High contrast
// text »): treated as "reduce transparency" — every layer takes its opaque
// fill and nothing is blurred; the rims stay, they are edges, not effects.
// ─────────────────────────────────────────────────────────────────────────

/** Which ground a glass palette is drawn for. */
enum class GlassStyle { Oled, Dark, Light }

/**
 * The tokens one ground needs. [fill] is what a [GlassTier] paints; the rest
 * is the rim, sheen, edge line, focus glow and (light only) the lift shadow.
 */
@Immutable
class GlassPalette(
    val style: GlassStyle,
    private val blurredFills: Map<GlassTier, Color>,
    private val opaqueFills: Map<GlassTier, Color>,
    /** Rim at the top edge of a layer. */
    val hairlineTop: Color,
    /** Rim at the bottom edge. */
    val hairlineBottom: Color,
    /** Inner sheen at the very top of a layer (fades out within [OledGlass.HighlightDepth]). */
    val highlight: Color,
    /** Peak colour of a one-edge line ([glassEdge]). */
    val edgeLine: Color,
    /** Focus glow colour. */
    val glowColor: Color,
    /** Multiplies the glow alpha a call site asks for. */
    val glowScale: Float,
    /** Diffuse drop shadow under a raised pane; transparent = none. */
    val lift: Color,
    /** Dialog button ink (replaces `primary` inside the button slots); null = keep the scheme's. */
    val buttonInk: Color?,
    /** Label colour on a filled button in [buttonInk]. */
    val onButtonInk: Color?,
    /** Outline of a focused glass text field. */
    val focusBorder: Color,
) {
    fun blurred(tier: GlassTier): Color = blurredFills.getValue(tier)
    fun opaque(tier: GlassTier): Color = opaqueFills.getValue(tier)
    fun fill(tier: GlassTier, canBlur: Boolean): Color = if (canBlur) blurred(tier) else opaque(tier)

    /** Rim brush spanning [height], top to bottom. */
    fun hairlineBrush(height: Float): Brush = Brush.verticalGradient(
        0f to hairlineTop,
        1f to hairlineBottom,
        startY = 0f,
        endY = height.coerceAtLeast(1f)
    )

    companion object {
        /** The OLED palette — exactly the values above ([OledGlass], [GlassTier]). */
        val Oled = GlassPalette(
            style = GlassStyle.Oled,
            blurredFills = GlassTier.values().associateWith { it.blurred },
            opaqueFills = GlassTier.values().associateWith { it.opaque },
            hairlineTop = OledGlass.HairlineTop,
            hairlineBottom = OledGlass.HairlineBottom,
            highlight = Color.White.copy(alpha = OledGlass.HIGHLIGHT_ALPHA),
            edgeLine = Color.White.copy(alpha = 0.14f),
            glowColor = OledGlass.GlowColor,
            glowScale = 1f,
            lift = Color.Transparent,
            buttonInk = Oshi.BrandInkDark,
            onButtonInk = GlassOnBrandInk,
            focusBorder = OSHIPurpleLight.copy(alpha = 0.55f),
        )

        /**
         * Smoked glass on the classic dark ground (#0F0F0F background, #1A1A1A
         * surface). Neutral greys only — a violet-grey pane is the "purple
         * stain" the OLED work removed, and it would be one here too.
         */
        val Dark = GlassPalette(
            style = GlassStyle.Dark,
            blurredFills = mapOf(
                GlassTier.Bar to Color(0xFF1E1E21).copy(alpha = 0.72f),
                GlassTier.Card to DarkGlassCard,
                GlassTier.Chip to Color(0xFF2E2E32).copy(alpha = 0.80f),
                GlassTier.Sheet to Color(0xFF1F1F22).copy(alpha = 0.88f),
                GlassTier.Menu to Color(0xFF2A2A2E).copy(alpha = 0.96f),
                GlassTier.Dialog to Color(0xFF242427).copy(alpha = 0.92f),
                GlassTier.Control to Color(0xFF2E2E32).copy(alpha = 0.62f),
            ),
            opaqueFills = mapOf(
                GlassTier.Bar to Color(0xFF1E1E21).copy(alpha = 0.92f),
                GlassTier.Card to DarkGlassCard,
                GlassTier.Chip to Color(0xFF2E2E32).copy(alpha = 0.94f),
                GlassTier.Sheet to Color(0xFF1F1F22).copy(alpha = 0.97f),
                GlassTier.Menu to Color(0xFF2A2A2E).copy(alpha = 0.98f),
                GlassTier.Dialog to Color(0xFF242427).copy(alpha = 0.97f),
                GlassTier.Control to Color(0xFF2E2E32).copy(alpha = 0.85f),
            ),
            hairlineTop = Color.White.copy(alpha = 0.20f),
            hairlineBottom = Color.White.copy(alpha = 0.05f),
            highlight = Color.White.copy(alpha = 0.06f),
            edgeLine = Color.White.copy(alpha = 0.13f),
            glowColor = OSHIPurpleLight,
            glowScale = 0.9f,
            lift = Color.Transparent,
            buttonInk = Oshi.BrandInkDark,
            onButtonInk = GlassOnBrandInk,
            focusBorder = OSHIPurpleLight.copy(alpha = 0.60f),
        )

        /**
         * Frosted white on the light ground. The panes are WHITE and the ground
         * under them is a faintly cool off-white, so a pane reads as lit glass;
         * the rim is graphite (a white rim on white is nothing) and darker at
         * the bottom, the sheen is a white gloss, and [lift] does the rest.
         */
        val Light = GlassPalette(
            style = GlassStyle.Light,
            blurredFills = mapOf(
                GlassTier.Bar to Color.White.copy(alpha = 0.70f),
                GlassTier.Card to LightGlassCard,
                GlassTier.Chip to Color.White.copy(alpha = 0.82f),
                GlassTier.Sheet to Color(0xFFFCFCFE).copy(alpha = 0.90f),
                GlassTier.Menu to Color.White.copy(alpha = 0.94f),
                GlassTier.Dialog to Color(0xFFFCFCFE).copy(alpha = 0.92f),
                GlassTier.Control to Color.White.copy(alpha = 0.66f),
            ),
            opaqueFills = mapOf(
                GlassTier.Bar to Color.White.copy(alpha = 0.94f),
                GlassTier.Card to LightGlassCard,
                GlassTier.Chip to Color.White.copy(alpha = 0.96f),
                GlassTier.Sheet to Color(0xFFFCFCFE).copy(alpha = 0.98f),
                GlassTier.Menu to Color.White.copy(alpha = 0.99f),
                GlassTier.Dialog to Color(0xFFFCFCFE).copy(alpha = 0.98f),
                GlassTier.Control to Color.White.copy(alpha = 0.88f),
            ),
            hairlineTop = LightGlassInk.copy(alpha = 0.06f),
            hairlineBottom = LightGlassInk.copy(alpha = 0.13f),
            highlight = Color.White.copy(alpha = 0.12f),
            edgeLine = LightGlassInk.copy(alpha = 0.10f),
            glowColor = OSHIPurple,
            glowScale = 0.55f,
            lift = LightGlassInk.copy(alpha = 0.07f),
            buttonInk = null,
            onButtonInk = null,
            focusBorder = OSHIPurple.copy(alpha = 0.55f),
        )

        fun of(style: GlassStyle): GlassPalette = when (style) {
            GlassStyle.Oled -> Oled
            GlassStyle.Dark -> Dark
            GlassStyle.Light -> Light
        }
    }
}

/** Dark-glass card: a neutral step above the #1A1A1A surface. */
val DarkGlassCard = Color(0xFF242427)

/** Light-glass card: white over the cool ground. */
val LightGlassCard = Color.White

/** The graphite the light palette draws its rim, edge and shadow in (a cool near-black, never pure black). */
val LightGlassInk = Color(0xFF1C1B29)

/**
 * The defaults of the « Effet verre » switch while the user has not touched
 * it. OLED keeps its glass (it shipped on by design on 16/11). Light and Dark
 * default ON as well — see the report in `GlassThemesTest`: every surface
 * clears AA, the bars only get translucent where the wallpaper is under them,
 * and a list row never blurs, so there is no cost for a user who never looks.
 */
object GlassDefaults {
    // Decision 2026-11-21: Light/Dark glass is OPT-IN (Settings → Appearance → Glass effect),
    // so an update never changes an existing user's look. Extra dark keeps it ON as shipped.
    const val OLED = true
    const val DARK = false
    const val LIGHT = false

    fun enabled(style: GlassStyle): Boolean = when (style) {
        GlassStyle.Oled -> OLED
        GlassStyle.Dark -> DARK
        GlassStyle.Light -> LIGHT
    }

    /** The stored preference (null = never touched) resolved for the ground in force. */
    fun resolve(pref: Boolean?, style: GlassStyle): Boolean = pref ?: enabled(style)

    /** The style a resolved theme draws glass in. */
    fun styleFor(dark: Boolean, oled: Boolean): GlassStyle = when {
        dark && oled -> GlassStyle.Oled
        dark -> GlassStyle.Dark
        else -> GlassStyle.Light
    }
}

/**
 * The user's « Effet verre » choice, provided by [OSHITheme]: null = not
 * touched (each ground uses [GlassDefaults]); true / false = on / off everywhere.
 */
val LocalGlassPref = staticCompositionLocalOf<Boolean?> { null }

/**
 * True when the system asks for more contrast (Android 14 Contrast medium /
 * high, or High contrast text). Glass then keeps its rims but drops every
 * translucency and blur. Provided by [OSHITheme].
 */
val LocalGlassReduceTransparency = staticCompositionLocalOf { false }

/**
 * The glass palette in force for the scheme in force, or null where glass is
 * off. Follows the scheme, not the app theme: a chat thread re-themed dark by
 * `WithChatScheme` inside a light app gets the dark palette.
 */
@Composable
@ReadOnlyComposable
fun currentGlass(): GlassPalette? {
    val style = GlassDefaults.styleFor(isDarkScheme(), LocalOledBlack.current)
    return if (GlassDefaults.resolve(LocalGlassPref.current, style)) GlassPalette.of(style) else null
}

/** API 31+ — where RenderEffect and cross-window blur exist at all. */
val glassCanBlurPlatform: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** True when glass may blur / be translucent here: API 31+ and no high-contrast request. */
@Composable
@ReadOnlyComposable
fun glassCanBlur(): Boolean = glassCanBlurPlatform && !LocalGlassReduceTransparency.current

/**
 * True when cross-window blur will actually be drawn: API 31+ AND the device
 * has it switched on (`WindowManager.isCrossWindowBlurEnabled` is false in
 * battery saver, on some GPUs and on software-rendered emulators) AND the user
 * has not asked for high contrast.
 */
@Composable
fun rememberCrossWindowBlur(): Boolean {
    val view = LocalView.current
    val reduce = LocalGlassReduceTransparency.current
    return remember(view, reduce) {
        if (!glassCanBlurPlatform || reduce) false else runCatching {
            val wm = view.context.getSystemService(WindowManager::class.java)
            wm?.isCrossWindowBlurEnabled == true
        }.getOrDefault(false)
    }
}

/** Glass fill for [tier] on this device, in the palette in force. */
@Composable
@ReadOnlyComposable
fun glassFill(tier: GlassTier): Color = glassFill(tier, glassCanBlur())

/** Glass fill for [tier] given whether the layer is actually blurred behind. */
@Composable
@ReadOnlyComposable
fun glassFill(tier: GlassTier, canBlur: Boolean): Color =
    (currentGlass() ?: GlassPalette.Oled).fill(tier, canBlur && !LocalGlassReduceTransparency.current)

/** The opaque (no blur behind) fill for [tier] in the palette in force. */
@Composable
@ReadOnlyComposable
fun glassOpaque(tier: GlassTier): Color = (currentGlass() ?: GlassPalette.Oled).opaque(tier)

/** True when glass should be drawn: the « Effet verre » switch resolved for the scheme in force. */
@Composable
@ReadOnlyComposable
fun glassOn(): Boolean = currentGlass() != null

/** True on the light palette — for the few call sites that must pick a light ink. */
@Composable
@ReadOnlyComposable
fun glassIsLight(): Boolean = currentGlass()?.style == GlassStyle.Light

/**
 * The lit rim + inner sheen, drawn OVER the element's content edge (after it),
 * clipped to [shape]. Cheap: one cached outline, one gradient, no layers, so it
 * is safe on every row of a list. Drawn in the palette in force (the OLED one
 * where glass is off and a caller draws it anyway).
 *
 * [topAlpha] / [bottomAlpha] are written against the OLED rim (0.18 / 0.04) and
 * scale the palette's rim by the same ratio.
 */
fun Modifier.glassHairline(
    shape: Shape,
    width: Dp = OledGlass.HairlineWidth,
    topAlpha: Float = OledGlass.HAIRLINE_TOP_ALPHA,
    bottomAlpha: Float = OledGlass.HAIRLINE_BOTTOM_ALPHA,
    highlight: Boolean = true,
    /**
     * The height of the VISIBLE element when the modified node is taller —
     * Material chips pad themselves to a 48 dp touch target around a 32 dp
     * capsule. The rim is then drawn centred at this height, on the capsule.
     */
    visibleHeight: Dp = Dp.Unspecified,
): Modifier = composed {
    val p = currentGlass() ?: GlassPalette.Oled
    val rimTop = p.hairlineTop.scaled(topAlpha / OledGlass.HAIRLINE_TOP_ALPHA)
    val rimBottom = p.hairlineBottom.scaled(bottomAlpha / OledGlass.HAIRLINE_BOTTOM_ALPHA)
    val sheenColor = p.highlight
    Modifier.drawWithCache {
        val h = if (visibleHeight == Dp.Unspecified) size.height else visibleHeight.toPx().coerceAtMost(size.height)
        val top = (size.height - h) / 2f
        val box = Size(size.width, h)
        val strokePx = width.toPx()
        val inset = strokePx / 2f
        val innerSize = Size(box.width - strokePx, box.height - strokePx)
        val strokeOutline = shape.createOutline(innerSize, layoutDirection, this)
        val fillOutline = shape.createOutline(box, layoutDirection, this)
        val rim = Brush.verticalGradient(0f to rimTop, 1f to rimBottom, startY = 0f, endY = box.height.coerceAtLeast(1f))
        val depth = OledGlass.HighlightDepth.toPx().coerceAtMost(box.height)
        val sheen = Brush.verticalGradient(
            0f to sheenColor,
            1f to sheenColor.copy(alpha = 0f),
            startY = 0f,
            endY = depth.coerceAtLeast(1f)
        )
        onDrawWithContent {
            drawContent()
            translate(0f, top) {
                if (highlight) drawOutline(fillOutline, brush = sheen)
                translate(inset, inset) {
                    drawOutline(strokeOutline, brush = rim, style = Stroke(width = strokePx))
                }
            }
        }
    }
}

private fun Color.scaled(factor: Float): Color = copy(alpha = (alpha * factor).coerceIn(0f, 1f))

/**
 * A full glass layer: [fill] clipped to [shape], then the hairline. Use
 * where the element has no fill of its own (or replace its fill with this).
 */
fun Modifier.glassSurface(shape: Shape, fill: Color): Modifier =
    this.background(fill, shape).glassHairline(shape)

/**
 * Light glass only: a soft, diffuse shadow under a raised pane, drawn BEHIND
 * the element (place it before the element's own fill in the chain). Stacked
 * outlines with falling alpha, offset down — the light comes from above — so
 * it looks the same on every API (no `BlurMaskFilter`, ignored by the hardware
 * canvas below API 28) and costs a handful of path fills. No-op on the dark
 * palettes: a shadow on a dark ground is invisible, and a glow would be wrong.
 */
fun Modifier.glassLift(shape: Shape, spread: Dp = 8.dp, drop: Dp = 2.dp): Modifier = composed {
    val lift = currentGlass()?.lift ?: Color.Transparent
    if (lift.alpha == 0f) Modifier else Modifier.drawBehind {
        val spreadPx = spread.toPx()
        val dropPx = drop.toPx()
        val rings = 6
        for (i in rings downTo 1) {
            val grow = spreadPx * i / rings
            val outline = shape.createOutline(Size(size.width + grow * 2, size.height + grow * 2), layoutDirection, this)
            val t = 1f - (i - 1f) / rings
            translate(-grow, -grow + dropPx * (0.5f + t / 2f)) {
                drawOutline(outline, color = lift.copy(alpha = lift.alpha * t * t / 1.6f))
            }
        }
    }
}

/**
 * A soft brand-violet halo around a focused element, drawn BEHIND it as a
 * stack of outlines growing outward with falling alpha. Concentric strokes
 * rather than `BlurMaskFilter` because the mask filter is ignored by the
 * hardware canvas below API 28 (minSdk is 26) and would draw a hard slab there;
 * this looks the same on every API and costs a handful of path strokes.
 *
 * [color] / [alpha] default to the palette in force (light: the deep brand
 * violet at about half the strength — on white a full-strength halo reads as a
 * smudge).
 */
fun Modifier.glassGlow(
    shape: Shape = CircleShape,
    color: Color = Color.Unspecified,
    alpha: Float = OledGlass.GLOW_ALPHA,
    spread: Dp = OledGlass.GlowSpread,
    /** See [glassHairline]'s `visibleHeight`. */
    visibleHeight: Dp = Dp.Unspecified,
): Modifier = composed {
    val p = currentGlass() ?: GlassPalette.Oled
    val c = if (color == Color.Unspecified) p.glowColor else color
    val a = alpha * p.glowScale
    Modifier.drawBehind {
        val spreadPx = spread.toPx()
        val h = if (visibleHeight == Dp.Unspecified) size.height else visibleHeight.toPx().coerceAtMost(size.height)
        val top = (size.height - h) / 2f
        val rings = 7
        for (i in rings downTo 1) {
            val grow = spreadPx * i / rings
            val outline = shape.createOutline(
                Size(size.width + grow * 2, h + grow * 2), layoutDirection, this
            )
            // Quadratic falloff: dense right at the edge, nothing at the rim.
            val t = 1f - (i - 1f) / rings
            translate(-grow, top - grow) {
                drawOutline(outline, color = c.copy(alpha = a * t * t / 2.2f))
            }
        }
    }
}

/** [glassGlow] only when [active]. */
fun Modifier.glassGlowIf(active: Boolean, shape: Shape = CircleShape): Modifier =
    if (active) glassGlow(shape) else this

/** Conditional modifier helper. */
inline fun Modifier.thenIf(condition: Boolean, block: Modifier.() -> Modifier): Modifier =
    if (condition) block() else this

/**
 * A hairline along ONE edge only — for a bar that meets the content on a single
 * side (top bar: its bottom edge; composer / bottom nav: its top edge).
 * A lit line across the full width, fading at both ends so it does not look
 * like a ruled divider. [alpha] is written against the OLED line (0.14) and
 * scales the palette's.
 */
fun Modifier.glassEdge(top: Boolean, alpha: Float = 0.14f): Modifier = composed {
    val p = currentGlass() ?: GlassPalette.Oled
    val lineColor = p.edgeLine.scaled(alpha / 0.14f)
    val sheenColor = p.highlight
    Modifier.drawWithCache {
        val line = Brush.horizontalGradient(
            0f to lineColor.copy(alpha = 0f),
            0.2f to lineColor,
            0.8f to lineColor,
            1f to lineColor.copy(alpha = 0f),
        )
        val sheen = Brush.verticalGradient(
            0f to if (top) sheenColor else sheenColor.copy(alpha = 0f),
            1f to sheenColor.copy(alpha = 0f),
            startY = 0f,
            endY = OledGlass.HighlightDepth.toPx().coerceAtMost(size.height).coerceAtLeast(1f)
        )
        val y = if (top) 0f else size.height - 1.dp.toPx()
        onDrawWithContent {
            if (top) drawRect(sheen)
            drawContent()
            drawRect(line, topLeft = Offset(0f, y), size = Size(size.width, 1.dp.toPx()))
        }
    }
}

/**
 * The OLED-glass variant of a colour scheme for a subtree that must sit on a
 * glass layer (menus): `surface` becomes the menu glass so Material's own
 * `Surface(color = surface)` inside the component picks it up.
 */
fun ColorScheme.withGlassSurface(fill: Color): ColorScheme = copy(surface = fill)

/**
 * Inside a dialog or a modal sheet's window: blur everything behind the window
 * (API 31+, where the device allows it). No-op elsewhere and on failure — the
 * opaque fills carry legibility on their own.
 *
 * Dialogs expose their window through [DialogWindowProvider]; M3 1.1's
 * `ModalBottomSheet` is a bare full-screen panel window, reached through the
 * root view's layout params.
 */
@Composable
fun GlassWindowBlurEffect(radius: Dp = 28.dp) {
    if (!glassCanBlurPlatform || LocalGlassReduceTransparency.current) return
    val view = LocalView.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { radius.roundToPx() }
    SideEffect { applyBlurBehind(view, px) }
}

private fun applyBlurBehind(view: View, radiusPx: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
            ?: (view.rootView?.let { root -> (root.parent as? DialogWindowProvider)?.window })
        if (dialogWindow != null) {
            val attrs = dialogWindow.attributes
            if (attrs.blurBehindRadius == radiusPx &&
                attrs.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0
            ) return
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            dialogWindow.attributes = dialogWindow.attributes.apply { blurBehindRadius = radiusPx }
            return
        }
        val root = view.rootView ?: return
        val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
        if (lp.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION) return // never the activity
        if (lp.blurBehindRadius == radiusPx && lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0) return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        lp.blurBehindRadius = radiusPx
        root.context.getSystemService(WindowManager::class.java)?.updateViewLayout(root, lp)
    }
}

/** Rectangle used by bars. */
val GlassBarShape: Shape = RectangleShape

/**
 * A pill text field as glass (the composer, search fields): the chip fill, no
 * Material outline at rest (the hairline is the edge), a brand-violet outline
 * while focused. Pair with `Modifier.glassHairline(shape)`.
 */
@Composable
fun glassTextFieldColors(): androidx.compose.material3.TextFieldColors {
    val fill = glassFill(GlassTier.Chip)
    return androidx.compose.material3.OutlinedTextFieldDefaults.colors(
        focusedContainerColor = fill,
        unfocusedContainerColor = fill,
        disabledContainerColor = fill,
        focusedBorderColor = (currentGlass() ?: GlassPalette.Oled).focusBorder,
        unfocusedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
    )
}

/**
 * __GLASS_ALL_THEMES_2026_11_21__ Glass on the screens that are dark in every
 * theme (the call screen, the photo editor, the bot web-app bar, the video-note
 * recorder) and on the incoming bubble: these keep the OLED treatment only. In
 * Light / Dark they are their own designed dark world (the call screen's violet
 * wash, the editor's graphite) and a theme palette would put white panes or
 * smoke where white ink sits.
 */
@Composable
@ReadOnlyComposable
fun oledGlassOn(): Boolean = currentGlass()?.style == GlassStyle.Oled
