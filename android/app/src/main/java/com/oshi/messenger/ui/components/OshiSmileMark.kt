package com.oshi.messenger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.data.model.OshiMascot
import com.oshi.messenger.data.model.OshiReaction
import com.oshi.messenger.data.model.ReactionEmojis
import com.oshi.messenger.ui.theme.OSHIPurple

/**
 * __OSHI_SMILE_REACTION_2026_11_09__ The OSHI mark — a thick ring with a small
 * speech-bubble tail at its lower-left — smiling. Drawn as vector geometry so it
 * is crisp at reaction-chip size (14sp) and in the picker (20sp) alike.
 *
 * Geometry: `OSHI/LoRaFirmware/tools/oshi_boot_logo.py` (ring r_out 13, tail
 * wedge (-10,7) (-3,10) (-8,20) in ring units). The hole is opened a little
 * (0.6 of the outer radius instead of 7/13) so a smile fits inside at 14sp.
 */
@Composable
fun OshiSmileMark(size: Dp, modifier: Modifier = Modifier, color: Color = OSHIPurple) {
    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = "OSHI" }
    ) {
        val s = this.size.minDimension
        val rOut = 0.36f * s
        val ringWidth = 0.40f * rOut
        val cx = 0.54f * s
        val cy = 0.40f * s
        val k = rOut / 13f

        // Tail first, so the ring's stroke overlaps its root cleanly.
        val tail = Path().apply {
            moveTo(cx - 10f * k, cy + 7f * k)
            lineTo(cx - 3f * k, cy + 10f * k)
            lineTo(cx - 8f * k, cy + 20f * k)
            close()
        }
        drawPath(tail, color)

        // Ring: a stroke centred between the outer and inner radius.
        drawCircle(
            color = color,
            radius = rOut - ringWidth / 2f,
            center = Offset(cx, cy),
            style = Stroke(width = ringWidth),
        )

        // Face inside the hole.
        val rIn = rOut - ringWidth
        val eyeR = 0.13f * rIn
        drawCircle(color, eyeR, Offset(cx - 0.38f * rIn, cy - 0.22f * rIn))
        drawCircle(color, eyeR, Offset(cx + 0.38f * rIn, cy - 0.22f * rIn))
        val smileR = 0.52f * rIn
        drawArc(
            color = color,
            startAngle = 25f,
            sweepAngle = 130f,
            useCenter = false,
            topLeft = Offset(cx - smileR, cy - smileR * 0.85f),
            size = Size(smileR * 2f, smileR * 2f),
            style = Stroke(width = 0.16f * rIn, cap = StrokeCap.Round),
        )
    }
}

/**
 * __OSHI_O_REACTION_2026_11_15__ The plain OSHI "O" (brand ring + tail), for the
 * reaction "⭕". A vector drawable rather than Canvas code so it is the exact
 * launcher/notification geometry; the variant is chosen from the surface it
 * sits on, not the system night mode, because the chat thread can pin its own
 * light/dark scheme over a wallpaper.
 */
@Composable
fun OshiOMark(size: Dp, modifier: Modifier = Modifier) {
    val onDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Image(
        painter = painterResource(
            if (onDark) R.drawable.ic_reaction_oshi_o_dark else R.drawable.ic_reaction_oshi_o
        ),
        contentDescription = "OSHI",
        modifier = modifier.size(size),
    )
}

/** Size of the box an emoji at [fontSize] occupies, honouring the user's font scale. */
@Composable
internal fun reactionGlyphBox(fontSize: TextUnit): Dp =
    with(LocalDensity.current) { (fontSize * OshiReactionMetrics.GLYPH_BOX_PER_SP).toDp() }

/** Pure numbers, so the sizing rule is testable without a device. */
object OshiReactionMetrics {
    /**
     * An emoji at N sp occupies roughly 1.2·N of line height; the drawables
     * fill 22/24 of their box, so the mark comes out the height of the glyph.
     */
    const val GLYPH_BOX_PER_SP = 1.2f

    /**
     * The mascot's canvas carries its hands and tail around the body, so at
     * the emoji's own box the face would read smaller than the emoji beside
     * it; drawn 1.3x larger the body matches an emoji's visual size.
     */
    const val MASCOT_SCALE = 1.3f
}

/**
 * One reaction as it is drawn on a chip, in the picker or in a preview: the
 * exact OSHI wire values become their marks ("⭕" → [OshiOMark], "⭕😊" →
 * [OshiSmileMark]); everything else is text, as before.
 *
 * The box is sized through the density, so at a large font scale the mark grows
 * with the emoji beside it (it used to take `fontSize.value.dp`, which ignores
 * the font scale and left the mark smaller than its neighbours).
 */
@Composable
fun ReactionGlyph(
    code: String,
    fontSize: TextUnit = 14.sp,
    modifier: Modifier = Modifier,
    animate: Boolean = false,
) {
    // __O_BUBBLE_MASCOT_2026_11_16__ A standard emoji that has an O-bubble
    // mascot (❤️, 😂, 🤣, 😢, ...) is DRAWN as the mascot unless "Animated OSHI
    // reactions" is off. The code itself (what was sent) is unchanged.
    val mascot = OshiMascot.rendered(
        code,
        OshiMascotPrefs.animatedReactions(androidx.compose.ui.platform.LocalContext.current),
    )
    when {
        mascot != null -> MascotGlyph(
            mascot = mascot,
            size = reactionGlyphBox(fontSize) * OshiReactionMetrics.MASCOT_SCALE,
            animate = animate,
            modifier = modifier,
        )
        OshiReaction.isOshiO(code) -> OshiOMark(size = reactionGlyphBox(fontSize), modifier = modifier)
        OshiReaction.isOshiSmile(code) -> OshiSmileMark(
            size = reactionGlyphBox(fontSize),
            modifier = modifier,
            // Same reason as the O's _dark drawable: #5616EC is ~1.9:1 on a dark chip.
            color = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) OSHI_MARK_ON_DARK else OSHIPurple,
        )
        else -> Text(text = ReactionEmojis.displayEmoji(code), fontSize = fontSize, modifier = modifier)
    }
}

/** The lifted brand purple used for the OSHI marks on a dark surface. */
internal val OSHI_MARK_ON_DARK = Color(0xFFA98BFF)
