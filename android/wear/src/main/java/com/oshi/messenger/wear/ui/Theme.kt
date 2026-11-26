package com.oshi.messenger.wear.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.oshi.messenger.wear.R
import com.oshi.wearbridge.WearAvatar

/**
 * __WEAR_BRIDGE_2026_11_17__ The phone's « Noir OLED » crystal glass
 * (`ui/theme/Glass.kt`), on a round watch: pure black ground (an unlit pixel),
 * near-black glass layers read by their lit 1 dp rim (white 18 % at the top to
 * 4 % at the bottom) and a faint top sheen, and ONE soft brand-violet glow for
 * the thing in focus. Same literals as the phone.
 */
object OshiColors {
    val Ground = Color.Black
    val Purple = Color(0xFF5616EC)
    val PurpleLight = Color(0xFF6F3FFF)
    /** Brand ink that reads on black (phone `BrandInkDark`, AA on #000). */
    val BrandInk = Color(0xFFB899FF)
    /** Glass card fill (phone `OledSurfaceVariant` / GlassTier.Card). */
    val Card = Color(0xFF141414)
    /** Opaque incoming bubble (phone `OledGlass.BubbleFill`). */
    val Bubble = Color(0xFF1C1C1E)
    val TextPrimary = Color(0xFFF2F2F7)
    val TextSecondary = Color(0xFFA1A1AA)
    val Error = Color(0xFFFF6B6B)

    const val HAIRLINE_TOP = 0.18f
    const val HAIRLINE_BOTTOM = 0.04f
    const val SHEEN = 0.05f
    const val GLOW = 0.30f
}

private val OshiWearColors = ColorScheme(
    primary = OshiColors.BrandInk,
    primaryDim = OshiColors.PurpleLight,
    primaryContainer = OshiColors.Purple,
    onPrimary = Color(0xFF1B0B4D),
    onPrimaryContainer = Color.White,
    surfaceContainerLow = Color(0xFF0E0E10),
    surfaceContainer = OshiColors.Card,
    surfaceContainerHigh = OshiColors.Bubble,
    onSurface = OshiColors.TextPrimary,
    onSurfaceVariant = OshiColors.TextSecondary,
    background = OshiColors.Ground,
    onBackground = OshiColors.TextPrimary,
)

@Composable
fun OshiWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OshiWearColors, content = content)
}

/** The lit rim + inner sheen, over the content, clipped to [shape] (phone `glassHairline`). */
fun Modifier.glassHairline(shape: Shape, width: Dp = 1.dp): Modifier = this.drawWithCache {
    val strokePx = width.toPx()
    val inset = strokePx / 2f
    val stroke = shape.createOutline(Size(size.width - strokePx, size.height - strokePx), layoutDirection, this)
    val fill = shape.createOutline(size, layoutDirection, this)
    val rim = Brush.verticalGradient(
        0f to Color.White.copy(alpha = OshiColors.HAIRLINE_TOP),
        1f to Color.White.copy(alpha = OshiColors.HAIRLINE_BOTTOM),
        startY = 0f, endY = size.height.coerceAtLeast(1f),
    )
    val sheen = Brush.verticalGradient(
        0f to Color.White.copy(alpha = OshiColors.SHEEN), 1f to Color.Transparent,
        startY = 0f, endY = 28.dp.toPx().coerceAtMost(size.height).coerceAtLeast(1f),
    )
    onDrawWithContent {
        drawContent()
        drawOutline(fill, brush = sheen)
        translate(inset, inset) { drawOutline(stroke, brush = rim, style = Stroke(width = strokePx)) }
    }
}

/** A full glass layer: fill + rim. */
fun Modifier.glass(shape: Shape, fill: Color = OshiColors.Card): Modifier =
    this.clip(shape).background(fill, shape).glassHairline(shape)

/** The brand-violet halo of the one focused element (phone `glassGlow`). */
fun Modifier.glassGlow(shape: Shape = CircleShape, spread: Dp = 8.dp, color: Color = OshiColors.PurpleLight): Modifier =
    this.drawBehind {
        val spreadPx = spread.toPx()
        val rings = 6
        for (i in rings downTo 1) {
            val grow = spreadPx * i / rings
            val outline = shape.createOutline(Size(size.width + grow * 2, size.height + grow * 2), layoutDirection, this)
            val t = 1f - (i - 1f) / rings
            translate(-grow, -grow) { drawOutline(outline, color = color.copy(alpha = OshiColors.GLOW * t * t / 2.2f)) }
        }
    }

/** The outgoing bubble: the brand violet as a soft diagonal gradient. */
val OutgoingBrush = Brush.linearGradient(listOf(Color(0xFF7A4BFF), OshiColors.Purple))

/**
 * The picture-less avatar, exactly the phone's: the slot's diagonal gradient
 * plus the soft top-left light blob ([WearAvatar], shared spec).
 */
@Composable
fun Avatar(key: String, name: String?, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val entry = remember(key) { WearAvatar.entry(key) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .drawWithCache {
                val d = size.toPx()
                val linear = Brush.linearGradient(
                    listOf(Color(entry.start), Color(entry.end)), start = Offset.Zero, end = Offset(d, d),
                )
                val light = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = WearAvatar.HIGHLIGHT_ALPHA), Color.White.copy(alpha = 0f)),
                    center = Offset(d * WearAvatar.HIGHLIGHT_X, d * WearAvatar.HIGHLIGHT_Y),
                    radius = d * WearAvatar.HIGHLIGHT_RADIUS,
                )
                onDrawBehind { drawCircle(linear); drawCircle(light) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = remember(name, key) { WearAvatar.initials(name, key) },
            fontSize = (size.value * 0.38f).sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(entry.ink),
            maxLines = 1,
        )
    }
}

/** A group sender's name colour: same slot as their avatar. */
fun nameColor(key: String?): Color =
    if (key.isNullOrEmpty()) OshiColors.BrandInk else Color(WearAvatar.nameDark[WearAvatar.slot(key)])

/**
 * A reaction as the phone draws it: the OSHI "O" for ⭕, the O-bubble mascot
 * for the six quick emoji (static frame), the plain emoji otherwise.
 */
@Composable
fun ReactionGlyph(code: String, size: Dp, modifier: Modifier = Modifier) {
    val res = reactionDrawable(code)
    if (res != null) {
        Image(painterResource(res), contentDescription = code, modifier = modifier.size(size))
    } else {
        Text(code, fontSize = (size.value * 0.8f).sp, modifier = modifier)
    }
}

fun reactionDrawable(code: String): Int? = when (code.replace("️", "")) {
    "⭕" -> R.drawable.ic_reaction_oshi_o_dark
    "❤", "❤" -> R.drawable.mascot_love
    "😂", "🤣" -> R.drawable.mascot_laugh
    "😢", "😭" -> R.drawable.mascot_cry
    "👍" -> R.drawable.mascot_thumbs
    "😮", "😯", "😲" -> R.drawable.mascot_wow
    "🔥" -> R.drawable.mascot_fire
    else -> null
}

// ---- __WEAR_POLISH_2026_11_25__ ------------------------------------------

/** Amber for "messages wait on your phone" (no way out right now). */
val OfflineAmber = Color(0xFFFFC266)

/** A short time for a row or a bubble: "14:32" today, "Tue" this week, "12 Nov" before. */
fun shortTime(context: android.content.Context, ts: Long, now: Long = System.currentTimeMillis()): String {
    if (ts <= 0L) return ""
    val day = 24 * 60 * 60 * 1000L
    return when {
        sameDay(ts, now) -> android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(ts))
        now - ts < 6 * day -> android.text.format.DateUtils.formatDateTime(
            context, ts, android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY,
        )
        else -> android.text.format.DateUtils.formatDateTime(
            context, ts, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH or
                android.text.format.DateUtils.FORMAT_NO_YEAR,
        )
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

/**
 * The compact age for a list row, where width is scarce: "3m", "2h" today
 * (ICU narrow units, localized: "3 min" in French), then the short weekday /
 * date as [shortTime]. Never "3:04 PM", which squeezes the name on 192 dp.
 */
fun rowTime(context: android.content.Context, ts: Long, now: Long = System.currentTimeMillis()): String {
    if (ts <= 0L) return ""
    val age = (now - ts).coerceAtLeast(0L)
    if (!sameDay(ts, now) || age >= 12 * 60 * 60 * 1000L) return shortTime(context, ts, now)
    val fmt = android.icu.text.MeasureFormat.getInstance(
        context.resources.configuration.locales[0], android.icu.text.MeasureFormat.FormatWidth.NARROW,
    )
    val minutes = (age / 60_000L).coerceAtLeast(1L)
    return if (minutes < 60) fmt.format(android.icu.util.Measure(minutes, android.icu.util.MeasureUnit.MINUTE))
    else fmt.format(android.icu.util.Measure(minutes / 60, android.icu.util.MeasureUnit.HOUR))
}

/** "0:14". */
fun clock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** A small push-pin, drawn (no icon font on the watch). */
@Composable
fun PinGlyph(modifier: Modifier = Modifier, color: Color = OshiColors.BrandInk) {
    androidx.compose.foundation.Canvas(modifier.size(10.dp)) {
        val w = size.width
        drawCircle(color, radius = w * 0.28f, center = Offset(w * 0.62f, w * 0.38f))
        drawLine(color, Offset(w * 0.52f, w * 0.48f), Offset(w * 0.14f, w * 0.86f), strokeWidth = w * 0.14f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** A small microphone, drawn. */
@Composable
fun MicGlyph(size: Dp = 18.dp, color: Color = Color.White, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.1f
        drawRoundRect(
            color, topLeft = Offset(w * 0.35f, w * 0.06f), size = Size(w * 0.30f, w * 0.52f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.15f),
        )
        drawArc(
            color, startAngle = 0f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.22f, w * 0.22f), size = Size(w * 0.56f, w * 0.50f),
            style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        drawLine(color, Offset(w * 0.5f, w * 0.72f), Offset(w * 0.5f, w * 0.90f), strokeWidth = stroke)
        drawLine(color, Offset(w * 0.32f, w * 0.92f), Offset(w * 0.68f, w * 0.92f), strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
