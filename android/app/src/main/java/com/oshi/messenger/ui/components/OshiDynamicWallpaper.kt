package com.oshi.messenger.ui.components

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.theme.isDarkScheme
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * 🌌 The six animated OSHI wallpapers, ported from `OSHI/OSHIWallpapers.swift`.
 *
 * Android shipped NONE of these. iOS's own default is
 * `.oshiDynamic(constellation)` — Mesh — so every iPhone on the shipped default
 * that synced a wallpaper handed its Android peer flat beige, silently, because
 * the decoder had no case for it.
 *
 * Everything is vector-drawn, never a bitmap (`OSHIWallpapers.swift:5-9`), so
 * these cost no assets and scale to any screen.
 *
 * **Two traps carried over from the iOS file, both learned there the hard way:**
 *  1. Do NOT rasterise the wallpaper subtree into a bounds-sized buffer. iOS's
 *     `.drawingGroup()` produced an unpainted strip down one edge, twice
 *     (`OSHIWallpapers.swift:76-89`).
 *  2. Do NOT call a random generator inside the draw closure. It re-runs on
 *     every frame and the backdrop shimmers. Node layout comes from a fixed-seed
 *     LCG and per-cell variation from a positional hash, so the picture is
 *     identical on every launch and every device (`:277-290`).
 *
 * Bubbles need no change to sit on these: they are already opaque, blended
 * rather than translucent, precisely because "a translucent bubble over an
 * animated wallpaper shimmers, and OSHI ships animated wallpapers"
 * (`ui/theme/Tokens.kt:150-165`).
 */

/** `Theme.brand`, `OSHI/Theme.swift:18`. */
private val BRAND = Color(0xFF5616EC)

/** The wallpapers' secondary accent, `OSHIWallpapers.swift:63`. */
private val ACCENT = Color(0xFF2E52F5)

/** Aurora's third bloom, `OSHIWallpapers.swift:127-140`. */
private val PINK = Color(0xFFD9408F)

/**
 * The six styles, in iOS's catalogue order — the ORDINAL IS THE WIRE VALUE, so
 * inserting one in the middle would repaint every synced conversation.
 * `OSHIWallpapers.swift:25-45`.
 */
enum class OshiDynamicStyle(val displayName: String, val periodMs: Int) {
    AURORA("Aurora", 40_000),
    MESH("Mesh", 60_000),
    SIGNAL("Signal", 26_000),
    CONTOURS("Contours", 70_000),
    CIPHER("Cipher", 50_000),
    WAVES("Waves", 34_000);

    companion object {
        /** iOS's app-wide default (`ChatWallpaperManager.swift:31`). */
        val DEFAULT = MESH
        fun fromId(id: Int): OshiDynamicStyle? = entries.getOrNull(id)
    }
}

/**
 * Master opacity. `ink = 0.26` dark / `0.13` light (`OSHIWallpapers.swift:68`) —
 * the artwork is a texture behind a conversation, not a picture to look at.
 */
internal fun inkFor(dark: Boolean): Float = if (dark) 0.22f else 0.11f   // __CHAT_POLISH_2026_09_24__ was 0.26 / 0.13, = iOS

@Composable
fun OshiDynamicWallpaperView(
    style: OshiDynamicStyle,
    modifier: Modifier = Modifier,
    animated: Boolean = true
) {
    val dark = isDarkScheme()
    val oled = com.oshi.messenger.ui.theme.isOledScheme()
    val ink = inkFor(dark)
    val density = LocalDensity.current.density
    // __CHAT_POLISH_2026_09_24__ Battery saver and "Remove animations"
    // (animator duration scale 0 — Android's Reduce Motion) freeze the
    // backdrop on its first frame, as Low Power / Reduce Motion do on iOS.
    val context = LocalContext.current
    val moves = animated && remember(context) { wallpaperMayAnimate(context) }

    // `phase` runs 0 -> 1 and restarts, exactly the shape iOS drives with
    // `.linear(duration:).repeatForever(autoreverses: false)` (`:92-97`).
    // ⚠️ THE `.value` IS READ INSIDE THE DRAW LAMBDA, NOT HERE.
    //
    // Reading it in the composable body records the animation in THIS composable's
    // recompose scope, so the whole function re-runs 60-120x a second — new modifier
    // chain, new Canvas lambda, a fresh `isDarkScheme()` — when all that actually
    // needs to change is the drawing. Holding the `State` and dereferencing it inside
    // `Canvas { }` confines the invalidation to the draw scope, which is the entire
    // point of a draw-only animation.
    val phaseState: State<Float> = if (moves) {
        val t = rememberInfiniteTransition(label = "oshiWallpaper")
        t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(style.periodMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase"
        )
    } else remember { mutableFloatStateOf(0f) }

    // Allocated ONCE, not per frame. Cipher was building a `Paint` and calling
    // `Typeface.create` inside its draw closure — at 120 Hz that is 120 of each per
    // second, for a value that never changes.
    val glyphPaint = remember(density) {
        Paint().apply {
            isAntiAlias = true
            textSize = 12f * density
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            // The shared ground: vertical ramp + two static glows (iOS
            // `OSHIWallpaperGround`). Deep indigo with a violet glow in dark
            // instead of flat black; lavender-white in light so white
            // incoming bubbles separate from it.
            drawGround(dark, oled)
            val phase = phaseState.value
            when (style) {
                OshiDynamicStyle.AURORA -> drawAurora(phase, ink)
                OshiDynamicStyle.MESH -> drawMesh(phase, ink, density)
                OshiDynamicStyle.SIGNAL -> drawSignal(phase, ink, density)
                OshiDynamicStyle.CONTOURS -> drawContours(phase, ink, density)
                OshiDynamicStyle.CIPHER -> drawCipher(phase, ink, density, glyphPaint)
                OshiDynamicStyle.WAVES -> drawWaves(phase, ink, density)
            }
        }
    }
}

private const val TAU = (Math.PI * 2).toFloat()

/** False under battery saver or with animations removed system-wide. */
private fun wallpaperMayAnimate(context: Context): Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    if (power?.isPowerSaveMode == true) return false
    val scale = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (_: Exception) { 1f }
    return scale != 0f
}

/** Ramp + brand glow (top-end) + blue glow (bottom-start). `Oshi.Chat`. */
private fun DrawScope.drawGround(dark: Boolean, oled: Boolean) {
    val w = size.width; val h = size.height
    // __OLED_BLACK_2026_11_16__ flat black and no blooms in OLED black.
    drawRect(Brush.verticalGradient(Oshi.Chat.groundColors(dark, oled)))
    val d = max(w, h)
    bloom(BRAND, Oshi.Chat.brandGlow(dark, oled), Offset(w * 0.92f, h * 0.04f), d * 0.75f)
    bloom(ACCENT, Oshi.Chat.accentGlow(dark, oled), Offset(w * 0.05f, h * 0.96f), d * 0.65f)
}

/** A soft radial bloom. `OSHIWallpapers.swift:272-275`. */
private fun DrawScope.bloom(color: Color, alpha: Float, center: Offset, radius: Float) {
    if (radius <= 0f || alpha <= 0f) return
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha.coerceIn(0f, 1f)), Color.Transparent),
            center = center,
            radius = radius
        )
    )
}

/** 3 stacked blooms drifting on `t = sin(phase·2π)`. `OSHIWallpapers.swift:127-140`. */
private fun DrawScope.drawAurora(phase: Float, ink: Float) {
    val w = size.width; val h = size.height
    val d = max(w, h)
    val t = sin(phase * TAU)
    val c = Offset(w / 2f, h / 2f)
    bloom(BRAND, ink * 1.6f, Offset(c.x + t * d * 0.10f, c.y + t * d * 0.06f), d * 0.9f)
    bloom(ACCENT, ink * 1.2f, Offset(c.x + t * d * 0.12f, c.y - t * d * 0.05f), d * 0.8f)
    bloom(PINK, ink * 0.8f, Offset(c.x + t * d * 0.06f, c.y + t * d * 0.10f), d * 0.7f)
}

/**
 * 46 nodes from a fixed-seed LCG, linked when closer than `0.30 · width`.
 *
 * The seed is what makes this a WALLPAPER and not a lava lamp: the same layout
 * on every launch and every device (`OSHIWallpapers.swift:277-290`).
 */
private fun DrawScope.drawMesh(phase: Float, ink: Float, density: Float) {
    val w = size.width; val h = size.height
    if (w <= 0f || h <= 0f) return
    // __CHAT_POLISH_2026_09_24__ The SAME generator output as iOS
    // (`(seed >> 33) % 10_000 / 10_000`), so an iPhone and an Android phone
    // showing Mesh draw the same picture. `ushr` on a Long is UInt64's `>>`,
    // and the multiply-add wraps identically in two's complement.
    var state = 0x5EEDL
    fun unit(): Float {
        state = state * 6364136223846793005L + 1442695040888963407L
        return ((state ushr 33) % 10_000L).toFloat() / 10_000f
    }
    val nodes = ArrayList<Offset>(46)
    repeat(46) { nodes += Offset(unit() * w, unit() * h) }

    val drift = sin(phase * TAU) * 6f * density
    val linkMax = w * 0.30f
    for (i in nodes.indices) {
        for (j in i + 1 until nodes.size) {
            val a = nodes[i]; val b = nodes[j]
            val dist = hypot(a.x - b.x, a.y - b.y)
            if (dist >= linkMax) continue
            // Links fade with length (iOS: three buckets): short links are the
            // strongest, long diagonals barely there — depth, and less ink
            // crossing under the text.
            val f = dist / linkMax
            val (alphaK, widthK) = when {
                f < 0.45f -> 0.62f to 1f
                f < 0.75f -> 0.45f to 0.85f
                else -> 0.28f to 0.7f
            }
            // Opposite directions on the two ends, so links breathe.
            drawLine(
                color = BRAND.copy(alpha = (ink * alphaK).coerceIn(0f, 1f)),
                start = Offset(a.x + drift, a.y),
                end = Offset(b.x - drift, b.y),
                strokeWidth = widthK * density
            )
        }
    }
    for (i in nodes.indices) {
        val n = nodes[i]
        val hub = i % 5 == 0
        val r = (if (hub) 3.0f else 1.7f) * density
        val dx = if (i % 2 == 0) drift else -drift
        val c = Offset(n.x + dx, n.y)
        if (hub) {
            // A soft halo around the hubs: a lit node, not a dot.
            val halo = r * 3.2f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(ACCENT.copy(alpha = (ink * 0.55f).coerceIn(0f, 1f)), Color.Transparent),
                    center = c, radius = halo
                ),
                radius = halo,
                center = c
            )
        }
        drawCircle(
            color = ACCENT.copy(alpha = (ink * if (hub) 1.5f else 1.2f).coerceIn(0f, 1f)),
            radius = r,
            center = c
        )
    }
}

/** 5 concentric rings expanding outward and fading. `OSHIWallpapers.swift:175-190`. */
private fun DrawScope.drawSignal(phase: Float, ink: Float, density: Float) {
    val w = size.width; val h = size.height
    val d = max(w, h)
    val c = Offset(w / 2f, h / 2f)
    for (i in 0 until 5) {
        val local = ((phase + i / 5f) % 1f + 1f) % 1f
        val diameter = d * (0.15f + local * 1.15f)
        val alpha = (ink * (1f - local) * 1.4f).coerceIn(0f, 1f)
        if (alpha <= 0.001f) continue
        drawCircle(
            color = BRAND.copy(alpha = alpha),
            radius = diameter / 2f,
            center = c,
            style = Stroke(width = 1.4f * density)
        )
    }
}

/** 14 sine contour bands, every third one heavier. `OSHIWallpapers.swift:193-213`. */
private fun DrawScope.drawContours(phase: Float, ink: Float, density: Float) {
    val w = size.width; val h = size.height
    val shift = sin(phase * TAU) * 10f * density
    val step = 6f * density
    for (b in 0 until 14) {
        val baseY = h * (b / 13f)
        val amp = (16f + (b * 7 % 22)) * density
        val wl = (120f + (b * 31 % 90)) * density
        val heavy = b % 3 == 0
        val color = BRAND.copy(alpha = (ink * if (heavy) 0.8f else 0.45f).coerceIn(0f, 1f))
        val width = (if (heavy) 1.2f else 0.8f) * density
        var x = 0f
        var prev: Offset? = null
        while (x <= w) {
            val y = baseY + sin((x + shift * ((b % 3) + 1)) / wl) * amp
            val p = Offset(x, y)
            prev?.let { drawLine(color, it, p, strokeWidth = width) }
            prev = p
            x += step
        }
    }
}

/** A sparse hex glyph field drifting upward. `OSHIWallpapers.swift:217-240`. */
private fun DrawScope.drawCipher(phase: Float, ink: Float, density: Float, paint: Paint) {
    val w = size.width; val h = size.height
    val cell = 26f * density
    if (cell <= 0f) return
    val glyphs = "0123456789ABCDEF"
    val rise = phase * cell * 2f
    val rows = (h / cell).toInt() + 3
    val cols = (w / cell).toInt() + 2
    val canvas = drawContext.canvas.nativeCanvas
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val seed = (r * 31 + c * 17) % 100
            if (seed % 7 != 0) continue
            val glyph = glyphs[((r * 13 + c * 7) % 16 + 16) % 16]
            val alpha = (ink * if (seed % 2 == 0) 1.1f else 0.6f).coerceIn(0f, 1f)
            paint.color = BRAND.copy(alpha = alpha).toArgb()
            val y = ((r * cell - rise) % (h + cell) + (h + cell)) % (h + cell)
            canvas.drawText(glyph.toString(), c * cell, y, paint)
        }
    }
}

/** 7 long LoRa wavefronts scrolling horizontally. `OSHIWallpapers.swift:243-268`. */
private fun DrawScope.drawWaves(phase: Float, ink: Float, density: Float) {
    val w = size.width; val h = size.height
    if (w <= 0f) return
    val shift = phase * w
    val step = 8f * density
    val brush = Brush.horizontalGradient(
        listOf(
            BRAND.copy(alpha = (ink * 0.2f).coerceIn(0f, 1f)),
            ACCENT.copy(alpha = ink.coerceIn(0f, 1f)),
            BRAND.copy(alpha = (ink * 0.2f).coerceIn(0f, 1f))
        )
    )
    for (i in 0 until 7) {
        val baseY = h * (0.18f + i * 0.12f)
        val amp = (20f + i * 5f) * density
        val wl = w / (1.1f + i * 0.25f)
        var x = 0f
        var prev: Offset? = null
        while (x <= w) {
            val y = baseY + sin((x + shift * (0.4f + i * 0.1f)) / wl * TAU) * amp
            val p = Offset(x, y)
            prev?.let { drawLine(brush, it, p, strokeWidth = 1.6f * density) }
            prev = p
            x += step
        }
    }
}

/** A still frame, for a picker tile. iOS passes `animated: false` (`:53`). */
@Composable
fun OshiDynamicWallpaperThumbnail(style: OshiDynamicStyle, modifier: Modifier = Modifier) {
    OshiDynamicWallpaperView(style = style, modifier = modifier, animated = false)
}
