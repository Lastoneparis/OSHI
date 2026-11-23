package com.oshi.messenger.ui.theme

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
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

/** API 31+ — where RenderEffect and cross-window blur exist at all. */
val glassCanBlurPlatform: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * True when cross-window blur will actually be drawn: API 31+ AND the device
 * has it switched on (`WindowManager.isCrossWindowBlurEnabled` is false in
 * battery saver, on some GPUs and on software-rendered emulators).
 */
@Composable
fun rememberCrossWindowBlur(): Boolean {
    val view = LocalView.current
    return remember(view) {
        if (!glassCanBlurPlatform) false else runCatching {
            val wm = view.context.getSystemService(WindowManager::class.java)
            wm?.isCrossWindowBlurEnabled == true
        }.getOrDefault(false)
    }
}

/** Glass fill for [tier] on this device. */
@Composable
@ReadOnlyComposable
fun glassFill(tier: GlassTier): Color = tier.fill(glassCanBlurPlatform)

/** True when glass should be drawn: OLED black in force (and dark). */
@Composable
@ReadOnlyComposable
fun glassOn(): Boolean = isOledScheme()

/**
 * The lit rim + inner sheen, drawn OVER the element's content edge (after it),
 * clipped to [shape]. Cheap: one cached outline, one gradient, no layers, so it
 * is safe on every row of a list.
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
): Modifier = this.drawWithCache {
    val h = if (visibleHeight == Dp.Unspecified) size.height else visibleHeight.toPx().coerceAtMost(size.height)
    val top = (size.height - h) / 2f
    val box = Size(size.width, h)
    val strokePx = width.toPx()
    val inset = strokePx / 2f
    val innerSize = Size(box.width - strokePx, box.height - strokePx)
    val strokeOutline = shape.createOutline(innerSize, layoutDirection, this)
    val fillOutline = shape.createOutline(box, layoutDirection, this)
    val rim = OledGlass.hairlineBrush(box.height, topAlpha, bottomAlpha)
    val depth = OledGlass.HighlightDepth.toPx().coerceAtMost(box.height)
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = OledGlass.HIGHLIGHT_ALPHA),
        1f to Color.Transparent,
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

/**
 * A full glass layer: [tier]'s fill clipped to [shape], then the hairline. Use
 * where the element has no fill of its own (or replace its fill with this).
 */
fun Modifier.glassSurface(shape: Shape, fill: Color): Modifier =
    this.background(fill, shape).glassHairline(shape)

/**
 * A soft brand-violet halo around a focused element, drawn BEHIND it as a
 * stack of outlines growing outward with falling alpha. Concentric strokes
 * rather than `BlurMaskFilter` because the mask filter is ignored by the
 * hardware canvas below API 28 (minSdk is 26) and would draw a hard slab there;
 * this looks the same on every API and costs a handful of path strokes.
 */
fun Modifier.glassGlow(
    shape: Shape = CircleShape,
    color: Color = OledGlass.GlowColor,
    alpha: Float = OledGlass.GLOW_ALPHA,
    spread: Dp = OledGlass.GlowSpread,
    /** See [glassHairline]'s `visibleHeight`. */
    visibleHeight: Dp = Dp.Unspecified,
): Modifier = this.drawBehind {
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
            drawOutline(outline, color = color.copy(alpha = alpha * t * t / 2.2f))
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
 * like a ruled divider.
 */
fun Modifier.glassEdge(top: Boolean, alpha: Float = 0.14f): Modifier = this.drawWithCache {
    val line = Brush.horizontalGradient(
        0f to Color.White.copy(alpha = 0f),
        0.2f to Color.White.copy(alpha = alpha),
        0.8f to Color.White.copy(alpha = alpha),
        1f to Color.White.copy(alpha = 0f),
    )
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (top) OledGlass.HIGHLIGHT_ALPHA else 0f),
        1f to Color.Transparent,
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
    if (!glassCanBlurPlatform) return
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
        focusedBorderColor = OSHIPurpleLight.copy(alpha = 0.55f),
        unfocusedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
    )
}
