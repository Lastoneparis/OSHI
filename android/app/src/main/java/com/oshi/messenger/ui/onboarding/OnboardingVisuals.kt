package com.oshi.messenger.ui.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.GlassAccessibility
import com.oshi.messenger.ui.theme.GlassDefaults
import com.oshi.messenger.ui.theme.GlassPalette
import com.oshi.messenger.ui.theme.GlassStyle
import com.oshi.messenger.ui.theme.LocalGlassReduceTransparency
import com.oshi.messenger.ui.theme.LocalOledBlack
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.theme.isDarkScheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────
// __ONBOARDING_2027__ The visual system of the first-launch flow.
//
// Built on what the app already has rather than beside it:
// - the GLASS palette of the ground in force ([GlassPalette]: OLED / Dark /
//   Light) for every pane's rim, sheen and lift — onboarding always draws its
//   panes as glass, it is the brand moment, but with the same tokens Settings ›
//   Appearance › Glass uses, and opaque under High contrast / reduce
//   transparency exactly like the rest of the app;
// - the O-bubble MASCOT (the 16/11 pack, re-rendered at 384 px from its vector
//   source so it is sharp at hero size — the 128 px reaction stills are not);
// - [OnboardingIcons], one stroke weight, tinted with a per-step gradient.
//
// Motion is transform-only (graphicsLayer) so it never recomposes or relayouts,
// and all of it switches off under the system "Remove animations" setting.
// ─────────────────────────────────────────────────────────────────────────

/**
 * One hue per step. `ink` is held to ≥ 3:1 (WCAG non-text) against the pane
 * it is drawn on in light mode; `inkDark` against the dark / OLED panes.
 * `glow` tints the aurora and the orb halo only (decoration, no contrast duty).
 */
enum class ObAccent(val ink: Color, val inkDark: Color, val glow: Color) {
    Violet(Color(0xFF5616EC), Color(0xFFC4B5FD), Color(0xFF7C4DFF)),
    Teal(Color(0xFF0F766E), Color(0xFF5EEAD4), Color(0xFF14B8A6)),
    Pink(Color(0xFFBE185D), Color(0xFFF9A8D4), Color(0xFFEC4899)),
    Amber(Color(0xFFB45309), Color(0xFFFCD34D), Color(0xFFF59E0B)),
    Green(Color(0xFF15803D), Color(0xFF86EFAC), Color(0xFF22C55E)),
    Blue(Color(0xFF1D4ED8), Color(0xFF93C5FD), Color(0xFF3B82F6)),
    Red(Color(0xFFB91C1C), Color(0xFFFCA5A5), Color(0xFFEF4444));

    fun ink(dark: Boolean): Color = if (dark) inkDark else ink
}

/** Accent of each step. */
fun OnboardingPage.accent(): ObAccent = when (this) {
    OnboardingPage.PRIVATE -> ObAccent.Violet
    OnboardingPage.OFFLINE -> ObAccent.Teal
    OnboardingPage.ANONYMOUS -> ObAccent.Pink
    OnboardingPage.TOGETHER -> ObAccent.Amber
    OnboardingPage.RULES -> ObAccent.Green
    OnboardingPage.NOTIFICATIONS -> ObAccent.Blue
    OnboardingPage.IDENTITY -> ObAccent.Violet
}

/** The mascot pose of each step (identity reacts to typing, see the screen). */
@DrawableRes
fun OnboardingPage.mascot(): Int = when (this) {
    OnboardingPage.PRIVATE -> R.drawable.onboarding_mascot_wave
    OnboardingPage.OFFLINE -> R.drawable.onboarding_mascot_cool
    OnboardingPage.ANONYMOUS -> R.drawable.onboarding_mascot_peek
    OnboardingPage.TOGETHER -> R.drawable.onboarding_mascot_party
    OnboardingPage.RULES -> R.drawable.onboarding_mascot_ok
    OnboardingPage.NOTIFICATIONS -> R.drawable.onboarding_mascot_wow
    OnboardingPage.IDENTITY -> R.drawable.onboarding_mascot_neutral
}

/** Colours for the flow, resolved once per composition from the scheme in force. */
@Immutable
class ObColors(
    val dark: Boolean,
    val oled: Boolean,
    val ground: Color,
    val title: Color,
    val body: Color,
    val pane: Color,
    val glass: GlassPalette,
    /** Strength of the aurora behind everything (0 = none). */
    val aurora: Float,
    val dotIdle: Color,
) {
    /** Two-stop gradient for an icon of [accent]: brand violet into the accent. */
    fun iconGradient(accent: ObAccent): List<Color> =
        if (accent == ObAccent.Violet) listOf(Oshi.brandInk(dark), ObAccent.Blue.ink(dark))
        else listOf(accent.ink(dark), lerp(accent.ink(dark), Oshi.brandInk(dark), 0.45f))
}

@Composable
fun rememberObColors(): ObColors {
    val dark = isDarkScheme()
    val oled = LocalOledBlack.current && dark
    val opaque = LocalGlassReduceTransparency.current || GlassAccessibility.rememberHighContrast()
    val scheme = MaterialTheme.colorScheme
    return remember(dark, oled, opaque, scheme.background, scheme.onSurface) {
        val glass = GlassPalette.of(GlassDefaults.styleFor(dark, oled))
        val pane = when (glass.style) {
            GlassStyle.Light -> Color.White.copy(alpha = if (opaque) 1f else 0.78f)
            GlassStyle.Dark -> Color(0xFF26262A).copy(alpha = if (opaque) 1f else 0.78f)
            GlassStyle.Oled -> Color(0xFF121216).copy(alpha = if (opaque) 1f else 0.82f)
        }
        ObColors(
            dark = dark,
            oled = oled,
            ground = scheme.background,
            title = scheme.onSurface,
            body = scheme.onSurfaceVariant,
            pane = pane,
            glass = glass,
            aurora = when {
                opaque -> 0f
                oled -> 0.20f
                dark -> 0.26f
                else -> 0.20f
            },
            dotIdle = scheme.onSurface.copy(alpha = if (dark) 0.28f else 0.22f),
        )
    }
}

/**
 * The ground: two soft colour fields (the step's hue and the brand violet)
 * that slide against each other as the pager moves. [shift] is the signed
 * pager position in pages; the fields move a fraction of it — depth, not
 * motion sickness.
 */
@Composable
fun AuroraBackground(accent: Color, shift: () -> Float, colors: ObColors, modifier: Modifier = Modifier) {
    val dir = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    // The host already padded us below the status bar; paint up under it too
    // (raw inset, not the consumed one) so the bar shares the ground's tint.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topPx = WindowInsets.statusBars.getTop(density).toFloat()
    Canvas(modifier.fillMaxSize()) {
        translate(0f, -topPx) { drawGround(colors, accent, shift() * dir, Size(size.width, size.height + topPx)) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGround(colors: ObColors, accent: Color, s: Float, size: Size) {
    run {
        drawRect(colors.ground, size = size)
        if (colors.aurora <= 0f) return
        val w = size.width
        val h = size.height
        val r1 = w * 0.95f
        val c1 = Offset(w * (0.16f - 0.06f * s), h * 0.20f)
        drawCircle(
            Brush.radialGradient(listOf(accent.copy(alpha = colors.aurora), Color.Transparent), c1, r1),
            radius = r1, center = c1
        )
        val r2 = w * 0.80f
        val c2 = Offset(w * (0.92f - 0.10f * s), h * 0.58f)
        drawCircle(
            Brush.radialGradient(listOf(OSHIPurple.copy(alpha = colors.aurora * 0.75f), Color.Transparent), c2, r2),
            radius = r2, center = c2
        )
    }
}

/** Rim + sheen of a glass pane, drawn over [shape]'s outline. */
private fun Modifier.obGlassRim(shape: Shape, colors: ObColors): Modifier = drawWithContent {
    drawContent()
    val outline = shape.createOutline(size, layoutDirection, this)
    val hair = 1.dp.toPx()
    drawOutline(
        outline,
        brush = Brush.verticalGradient(listOf(colors.glass.hairlineTop.boost(1.6f), colors.glass.hairlineBottom.boost(1.6f))),
        style = Stroke(hair)
    )
    val sheenH = (size.height * 0.45f).coerceAtMost(40.dp.toPx())
    drawOutline(
        outline,
        brush = Brush.verticalGradient(
            0f to colors.glass.highlight.boost(1.4f),
            sheenH / size.height.coerceAtLeast(1f) to Color.Transparent,
        )
    )
}

private fun Color.boost(f: Float): Color = copy(alpha = (alpha * f).coerceIn(0f, 1f))

/** A frosted pane: fill, faint accent wash from the top corner, rim, sheen, lift. */
fun Modifier.obGlassPane(shape: Shape, colors: ObColors, accent: Color? = null): Modifier {
    var m = this
    if (colors.glass.lift.alpha > 0f) {
        val lift = colors.glass.lift
        m = m.drawWithContent {
            val rings = 6
            for (i in rings downTo 1) {
                val t = 1f - (i - 1f) / rings
                val grow = 10.dp.toPx() * i / rings
                val o = shape.createOutline(Size(size.width + grow * 2, size.height + grow * 2), layoutDirection, this)
                translate(-grow, -grow + 3.dp.toPx()) {
                    drawOutline(o, color = lift.copy(alpha = lift.alpha * t * t / 1.4f))
                }
            }
            drawContent()
        }
    }
    m = m.clip(shape).background(colors.pane)
    if (accent != null) {
        m = m.background(
            Brush.linearGradient(
                listOf(accent.copy(alpha = if (colors.dark) 0.16f else 0.10f), Color.Transparent),
                start = Offset.Zero, end = Offset(600f, 600f)
            )
        )
    }
    return m.obGlassRim(shape, colors)
}

/** An icon painted with a gradient (drawn white, then the gradient kept where it is). */
@Composable
fun GradientIcon(
    icon: ImageVector,
    gradient: List<Color>,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = Color.White,
        modifier = modifier
            .size(size)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.linearGradient(gradient, start = Offset.Zero, end = Offset(this.size.width, this.size.height)),
                    blendMode = BlendMode.SrcIn
                )
            }
    )
}

/** A gentle 0..1 loop for idle motion, or a constant 0 when motion is off. */
@Composable
fun rememberIdleLoop(animate: Boolean, periodMs: Int = 3600): State<Float> {
    if (!animate) return remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val t = rememberInfiniteTransition(label = "ob-idle")
    return t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = "ob-idle-t"
    )
}

/**
 * The mascot in a glass orb with a soft halo of the step's hue. Decorative:
 * hidden from TalkBack (the title next to it says what the step is).
 */
@Composable
fun MascotOrb(
    @DrawableRes mascot: Int,
    accent: ObAccent,
    colors: ObColors,
    size: Dp,
    idle: State<Float>,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val glow = accent.glow
    Box(modifier.size(size).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().graphicsLayer { scaleX = 1.45f; scaleY = 1.45f }) {
            drawCircle(
                Brush.radialGradient(
                    listOf(glow.copy(alpha = if (colors.dark) 0.42f else 0.30f), glow.copy(alpha = 0f)),
                    center, this.size.minDimension / 2f
                )
            )
        }
        Box(Modifier.matchParentSize().obGlassPane(CircleShape, colors, glow))
        Crossfade(
            targetState = mascot,
            animationSpec = tween(if (animate) 260 else 0),
            label = "ob-mascot"
        ) { res ->
            Image(
                painter = painterResource(res),
                contentDescription = null,
                modifier = Modifier
                    .size(size * 0.80f)
                    .graphicsLayer {
                        val a = idle.value * 2f * PI.toFloat()
                        translationY = sin(a) * 5.dp.toPx()
                        rotationZ = sin(a + 1.3f) * 2.2f
                    }
            )
        }
    }
}

/** A small glass tile holding one gradient icon — the "satellites" round the orb. */
@Composable
fun GlassIconTile(
    icon: ImageVector,
    accent: ObAccent,
    colors: ObColors,
    size: Dp = 52.dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(size * 0.32f)
    Box(modifier.size(size).obGlassPane(shape, colors, accent.glow), contentAlignment = Alignment.Center) {
        GradientIcon(icon, colors.iconGradient(accent), size * 0.5f)
    }
}

/**
 * Pager position as elongating dots. [position] is the fractional page.
 * One TalkBack node: "Page 2 of 5".
 */
@Composable
fun PagerDots(count: Int, position: () -> Float, accent: Color, colors: ObColors, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val p = position()
        repeat(count) { i ->
            val d = (1f - abs(p - i)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .height(8.dp)
                    .width(8.dp + 20.dp * d)
                    .clip(CircleShape)
                    .background(lerp(colors.dotIdle, accent, d))
            )
        }
    }
}

/** The primary action: a full-width gradient capsule, ≥ 56 dp, white label (≥ 4.5:1 on both stops). */
@Composable
fun ObPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    trailing: ImageVector? = OnboardingIcons.ArrowForward,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (enabled || loading) Brush.horizontalGradient(listOf(OSHIPurple, Color(0xFF6B2BF2)))
    else Brush.horizontalGradient(listOf(scheme.onSurface.copy(alpha = 0.12f), scheme.onSurface.copy(alpha = 0.12f)))
    val fg = if (enabled || loading) Color.White else scheme.onSurface.copy(alpha = 0.45f)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = text,
                color = fg,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            if (trailing != null && !loading) {
                Spacer(Modifier.width(10.dp))
                Icon(trailing, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** A glass card holding a list. */
@Composable
fun ObGlassCard(colors: ObColors, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .obGlassPane(RoundedCornerShape(24.dp), colors)
            .padding(vertical = 8.dp),
        content = content
    )
}

/** Places [content] at a polar offset from the centre of a hero box (fractions of its size). */
fun Modifier.heroAt(boxSize: Dp, fx: Float, fy: Float): Modifier =
    this.offset(x = boxSize * fx, y = boxSize * fy)

/** Box helper so call sites read as a stage. */
@Composable
fun HeroStage(size: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.size(size), contentAlignment = Alignment.Center, content = content)
}

// ─────────────────────────────────────────────────────────────────────────
// __ONBOARDING_POLISH_2026_11_24__ Three small pieces that make the setup
// steps read as part of the same flow as the slides, with no new copy:
// a step meter, a preview of the notification being asked for, and a live
// preview of the contact card the chosen name will produce.
// ─────────────────────────────────────────────────────────────────────────

/**
 * "Where am I": one capsule per step, the done ones filled with the brand ink,
 * the current one elongated. One TalkBack node ([label], e.g. "Page 6 of 7").
 */
@Composable
fun StepMeter(index: Int, count: Int, colors: ObColors, label: String, modifier: Modifier = Modifier) {
    val ink = com.oshi.messenger.ui.theme.brandInk()
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .height(6.dp)
                    .width(if (i == index) 22.dp else 10.dp)
                    .clip(CircleShape)
                    .background(if (i <= index) ink.copy(alpha = if (i == index) 1f else 0.55f) else colors.dotIdle)
            )
        }
    }
}

/**
 * What the permission is FOR, drawn rather than described: an OSHI notification
 * as it will land (mascot, app name, two soft message lines). Decorative — the
 * title and body next to it carry the meaning for TalkBack.
 */
@Composable
fun NotificationPreview(colors: ObColors, idle: State<Float>, animate: Boolean, modifier: Modifier = Modifier) {
    val accent = ObAccent.Blue
    Row(
        modifier
            .widthIn(max = 340.dp)
            .fillMaxWidth()
            .clearAndSetSemantics { }
            .graphicsLayer {
                if (animate) translationY = sin(idle.value * 2f * PI.toFloat()) * 3.dp.toPx()
            }
            .obGlassPane(RoundedCornerShape(22.dp), colors, accent.glow)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(OSHIPurple, Color(0xFF6B2BF2)))),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.onboarding_mascot_wave),
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("OSHI", color = colors.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                GradientIcon(OnboardingIcons.Bell, colors.iconGradient(accent), 14.dp)
            }
            Spacer(Modifier.height(6.dp))
            SkeletonLine(colors, 0.92f)
            Spacer(Modifier.height(5.dp))
            SkeletonLine(colors, 0.58f)
        }
    }
}

@Composable
private fun SkeletonLine(colors: ObColors, fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(8.dp)
            .clip(CircleShape)
            .background(colors.body.copy(alpha = if (colors.dark) 0.30f else 0.22f))
    )
}

/** First user-perceived character of [name], upper-cased (an emoji or accented letter stays whole). */
fun avatarInitial(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return null
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(trimmed)
    val end = it.next().takeIf { e -> e > 0 } ?: 1
    return trimmed.substring(0, end).uppercase()
}

/**
 * The contact card the typed name becomes: an avatar with its initial on the
 * brand gradient, the name (or the field's label, dimmed, while empty) and the
 * key that will be generated. Updates as the user types. Decorative for
 * TalkBack — the text field itself announces the name.
 */
@Composable
fun IdentityPreview(name: String, placeholder: String, colors: ObColors, modifier: Modifier = Modifier) {
    val trimmed = name.trim()
    val initial = avatarInitial(name)
    Row(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics { }
            .obGlassPane(RoundedCornerShape(22.dp), colors, ObAccent.Violet.glow)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(OSHIPurple, ObAccent.Blue.glow))),
            contentAlignment = Alignment.Center
        ) {
            if (initial != null) {
                Text(initial, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            } else {
                Icon(OnboardingIcons.People, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                trimmed.ifEmpty { placeholder },
                color = if (trimmed.isEmpty()) colors.body.copy(alpha = 0.7f) else colors.title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradientIcon(OnboardingIcons.Key, colors.iconGradient(ObAccent.Violet), 14.dp)
                Spacer(Modifier.width(6.dp))
                Text("•••• •••• ••••", color = colors.body, fontSize = 12.sp, letterSpacing = 1.sp, maxLines = 1)
            }
        }
        GradientIcon(OnboardingIcons.ShieldCheck, colors.iconGradient(ObAccent.Green), 22.dp)
    }
}
