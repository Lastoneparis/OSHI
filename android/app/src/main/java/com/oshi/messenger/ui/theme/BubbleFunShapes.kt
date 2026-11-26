package com.oshi.messenger.ui.theme

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.oshi.messenger.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Playful bubble silhouettes - comic burst, heart, car, balloon, cat, dog -
 * plus the six outgoing accents. Port of iOS `BubbleFunShapes.swift` and the
 * accent half of `BubbleStyleManager.swift`.
 *
 * THE RULE THAT MAKES THESE USABLE
 * --------------------------------
 * A bubble is a container for text someone has to READ. A literal car-shaped or
 * cat-shaped text container is unreadable: the glyphs get pushed into wheels and
 * ears, the line lengths jump around, and a long message either overflows the
 * silhouette or shrinks to nothing.
 *
 * So none of these shapes contains the text. Every one is a rectangular READING
 * CORE that behaves exactly like the ordinary bubble, plus decoration drawn
 * AROUND it. That is why each case declares [BubbleSilhouette.coreInsets] - the
 * extra room its decoration needs per edge - and why [bubbleSurface] applies
 * that padding BEFORE the background. No glyph can land in a wheel or an ear.
 *
 * Consequence worth stating: these are for SHORT messages. A 300-word paragraph
 * in a heart bubble is a very tall heart.
 *
 * The chosen style is LOCAL-ONLY. It never crosses the wire on either platform
 * (verified on iOS: `grep -rn 'oshi.bubble'` matches only the four UserDefaults
 * key declarations), so it can never desync a conversation.
 */

// ---------------------------------------------------------------------------
// Accent
// ---------------------------------------------------------------------------

/**
 * The palette an outgoing bubble can take. Every entry is a two-stop gradient
 * rather than a flat fill: a single colour across a large rounded shape reads as
 * a sticker, a short diagonal ramp catches the light like a surface. The stops
 * are close together on purpose - a wide ramp turns into a rainbow and fights
 * the text.
 *
 * CONTRAST. iOS ships these with white ink and documents, honestly, that four of
 * the six fail WCAG AA at stop 0 and `sunset` fails at both:
 *
 *     oshi 6.04/8.61 ok    graphite 9.57/15.07 ok
 *     ocean 3.62 FAIL      forest 2.54 FAIL     rose 3.38 FAIL
 *     sunset 2.88/3.94 FAIL at both
 *
 * Stop 0 is the top-leading corner, i.e. exactly where the first line of text
 * starts. Porting the values unchanged would port the defect, so the four
 * failing accents are DARKENED here until white clears 4.5:1 at both stops. The
 * hues are the same; only the luminance moved. `BubbleAccentContrastTest` is
 * what keeps that true.
 */
enum class BubbleAccent(
    val labelRes: Int,
    val stopStart: Color,
    val stopEnd: Color
) {
    // __CHAT_POLISH_2026_09_24__ #7B3AF5 -> #5616EC, same stops as iOS `.oshi`.
    OSHI(R.string.bubble_accent_oshi, Color(0xFF7B3AF5), Color(0xFF5616EC)),
    OCEAN(R.string.bubble_accent_ocean, Color(0.10f, 0.36f, 0.80f), Color(0.04f, 0.24f, 0.63f)),
    SUNSET(R.string.bubble_accent_sunset, Color(0.82f, 0.22f, 0.33f), Color(0.72f, 0.15f, 0.16f)),
    FOREST(R.string.bubble_accent_forest, Color(0.06f, 0.48f, 0.36f), Color(0.03f, 0.34f, 0.29f)),
    GRAPHITE(R.string.bubble_accent_graphite, Color(0.26f, 0.27f, 0.31f), Color(0.14f, 0.15f, 0.18f)),
    ROSE(R.string.bubble_accent_rose, Color(0.76f, 0.16f, 0.50f), Color(0.60f, 0.08f, 0.39f));

    /** Top-leading to bottom-trailing, matching iOS's `.topLeading -> .bottomTrailing`. */
    val brush: Brush get() = Brush.linearGradient(
        colors = listOf(stopStart, stopEnd),
        start = Offset.Zero,
        end = Offset.Infinite
    )

    /** A single representative colour, for small previews and tints. */
    val representative: Color get() = stopStart

    /**
     * Text on the outgoing bubble. Deliberately NOT configurable: a chat app
     * whose text disappears at a certain setting is broken, not customised
     * (BubbleStyleManager.swift:9-12).
     */
    val onAccent: Color get() = Color.White

    /**
     * __BUBBLE_MEDIA_2026_11_21__ What an outgoing bubble is ACTUALLY painted with.
     * [OSHI] — the default, i.e. nearly everyone — is tonal and follows the scheme
     * (Oshi.Bubble.outgoing*); the five opt-in accents keep their saturated ramp.
     * Paint and ink come from the same accent and the same `dark`, never separately.
     */
    fun isTonal(dark: Boolean): Boolean = this == OSHI

    fun surfaceBrush(dark: Boolean): Brush = if (this != OSHI) brush else Brush.linearGradient(
        colors = if (dark) listOf(Oshi.Bubble.outgoingDarkStart, Oshi.Bubble.outgoingDarkEnd)
        else listOf(Oshi.Bubble.outgoingLightStart, Oshi.Bubble.outgoingLightEnd),
        start = Offset.Zero,
        end = Offset.Infinite
    )

    /** The two stops of [surfaceBrush], for contrast checks and flat previews. */
    fun surfaceStops(dark: Boolean): Pair<Color, Color> = if (this != OSHI) stopStart to stopEnd
        else if (dark) Oshi.Bubble.outgoingDarkStart to Oshi.Bubble.outgoingDarkEnd
        else Oshi.Bubble.outgoingLightStart to Oshi.Bubble.outgoingLightEnd

    /** Body ink on [surfaceBrush]. */
    fun ink(dark: Boolean): Color = if (this == OSHI && !dark) Oshi.Bubble.onOutgoingLight else onAccent

    /** Timestamp / edited / tick ink on [surfaceBrush]. */
    fun metaInk(dark: Boolean): Color =
        if (this == OSHI) Oshi.Bubble.metaOnOutgoing(dark) else onAccent.copy(alpha = 0.8f)

    /** Links and mentions on [surfaceBrush]: brand violet on the pale ramp, the ink elsewhere. */
    fun linkInk(dark: Boolean): Color = if (this == OSHI && !dark) OSHIPurple else ink(dark)

    companion object {
        fun fromStored(raw: Int): BubbleAccent = entries.getOrNull(raw) ?: OSHI
    }
}

// ---------------------------------------------------------------------------
// Silhouette
// ---------------------------------------------------------------------------

/**
 * The decorative outline drawn around a bubble's reading core.
 *
 * [coreInsets] is the load-bearing part: the extra room the decoration needs on
 * each edge, declared next to the drawing rather than guessed at the call site.
 */
enum class BubbleSilhouette(
    val labelRes: Int,
    /** One emoji for the picker row. NOT a substitute for the name - it sits beside it. */
    val sample: String,
    val coreInsets: PaddingValues
) {
    COMIC(
        R.string.bubble_shape_comic, "💥",
        // The spikes reach `min(0.045 w, 0.11 h)` outward, at most 13.5.dp at the
        // 300.dp width cap; 16 leaves room for the corner a spike lands on.
        PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp)
    ),
    HEART(
        R.string.bubble_shape_heart, "❤️",
        // bottom 46: a heart tapers to a POINT, so the bottom corners of a wide
        // core sit in the taper. 30 was not enough at a two-line bubble and put
        // text outside the shape on both sides.
        // top 44: the lobes are circles of radius 0.25 w centred at 0.28 h, so on a
        // TALL bubble their upper boundary at the core's x sits well below 26.dp.
        PaddingValues(start = 18.dp, top = 44.dp, end = 18.dp, bottom = 64.dp)
    ),
    CAR(
        R.string.bubble_shape_car, "🚗",
        PaddingValues(start = 10.dp, top = 16.dp, end = 10.dp, bottom = 16.dp)
    ),
    BALLOON(
        R.string.bubble_shape_balloon, "🎈",
        // bottom 36: the knot and the string live in `tail`, which is bounded at
        // 0.115 w and w is capped at 300.dp, so 34.5.dp is the worst case.
        PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 36.dp)
    ),
    CAT(
        R.string.bubble_shape_cat, "🐱",
        PaddingValues(start = 10.dp, top = 22.dp, end = 10.dp, bottom = 12.dp)
    ),
    DOG(
        R.string.bubble_shape_dog, "🐶",
        // start/end 18: the head is inset by `earW * 0.45f` <= 0.0585 w <= 17.6.dp.
        // top 22: `headTop` <= 0.07 w <= 21.dp. bottom 22: the snout sits at
        // h - min(0.10 h, 0.05 w) and is at most 0.04 w tall.
        PaddingValues(start = 18.dp, top = 22.dp, end = 18.dp, bottom = 22.dp)
    );

    val shape: Shape get() = when (this) {
        COMIC -> ComicBurstShape
        HEART -> HeartShape
        CAR -> CarShape
        BALLOON -> BalloonShape
        CAT -> CatShape
        DOG -> DogShape
    }

    companion object {
        /**
         * Stored as `ordinal + 1`, so 0 - the value a never-written preference
         * reads back as - means "off" rather than silently selecting COMIC for
         * every existing user on the first launch after the update
         * (BubbleStyleManager.swift:180-186). An unknown stored value falls back
         * to "off", never to nothing rendered.
         */
        fun fromStored(raw: Int): BubbleSilhouette? =
            if (raw > 0) entries.getOrNull(raw - 1) else null

        fun toStored(value: BubbleSilhouette?): Int = (value?.ordinal ?: -1) + 1
    }
}

// ---------------------------------------------------------------------------
// The shapes. SwiftUI paths are in absolute rect coordinates, Compose's
// GenericShape gets a Size - same maths, different variable.
// ---------------------------------------------------------------------------

/**
 * Comic-book burst: the jagged "POW!" balloon. The spikes are generated on an
 * ellipse inscribed in the box and pulled in/out alternately, which is why the
 * text core is inset - the inner radius is what the message actually gets.
 */
private const val COMIC_SPIKES = 16

/**
 * 🔴 THE SPIKES USED TO BE CUT INWARD OUT OF AN ELLIPSE, AND THAT PUT TEXT ON THE
 * CHAT BACKGROUND. Vertices alternated between an inscribed ellipse and 0.88 of
 * it, so the shape's *inner* boundary was 0.88 of an ellipse — and an ellipse only
 * contains a rectangle inset by a FRACTION of its own size, while [coreInsets] is a
 * fixed dp. At a one-line bubble (300 x 44 dp) both top corners of the reading core
 * fell outside the burst. Found by `BubbleSilhouetteCoreInstrumentedTest`, which was
 * written for the balloon and caught this on its first run.
 *
 * Now the burst is built the way a comic burst actually works: a rounded-rect BASE
 * that contains the core by construction, with the spikes protruding OUTWARD from
 * it into the space [coreInsets] already reserves. [depth] is bounded on width as
 * well as height so the reserve cannot outgrow those fixed dp on a tall message.
 */
val ComicBurstShape: Shape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val depth = minOf(w * 0.045f, h * 0.11f)
    val l = depth; val t = depth; val r = w - depth; val b = h - depth
    val ew = r - l; val eh = b - t
    if (ew <= 0f || eh <= 0f) { addRect(Rect(0f, 0f, w, h)); return@GenericShape }
    val perimeter = 2f * (ew + eh)

    // Each edge is walked SEPARATELY and always begins at the true corner.
    // Walking one continuous perimeter instead let a corner fall between two
    // samples, and the chord across it cut the corner off — which put the reading
    // core outside the shape again on a tall bubble. The corners have to be
    // vertices, not points the polygon happens to pass near.
    val edges = listOf(
        floatArrayOf(l, t, 1f, 0f, ew, 0f, -1f),
        floatArrayOf(r, t, 0f, 1f, eh, 1f, 0f),
        floatArrayOf(r, b, -1f, 0f, ew, 0f, 1f),
        floatArrayOf(l, b, 0f, -1f, eh, -1f, 0f)
    )
    var first = true
    for (e in edges) {
        val sx = e[0]; val sy = e[1]; val dx = e[2]; val dy = e[3]
        val len = e[4]; val nx = e[5]; val ny = e[6]
        val n = maxOf(1, ((len / perimeter) * COMIC_SPIKES).toInt())
        if (first) { moveTo(sx, sy); first = false } else lineTo(sx, sy)
        for (k in 0 until n) {
            val mid = (k + 0.5f) / n * len
            lineTo(sx + dx * mid + nx * depth, sy + dy * mid + ny * depth)
            if (k < n - 1) {
                val next = (k + 1f) / n * len
                lineTo(sx + dx * next, sy + dy * next)
            }
        }
    }
    close()
}

/** Heart: two lobes and a point, sized so the core sits across the widest band. */
val HeartShape: Shape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val topY = h * 0.28f
    // The taper is bounded by WIDTH as well as height, for the reason on
    // [BalloonShape]: it used to be a flat 0.38 h, so on a tall message the point of
    // the heart reached far above a fixed-dp bottom inset and the core's bottom
    // corners ended up in the taper, outside the shape.
    val tail = minOf(h * 0.38f, w * 0.20f)
    moveTo(w / 2f, h)
    cubicTo(
        w / 2f - w * 0.42f, h - tail * 0.842f,
        0f, h - tail,
        0f, topY
    )
    // The two lobes.
    //
    // 🔴 THESE WERE `addArc`, WHICH STARTS A NEW SUB-CONTOUR. The outline was
    // therefore not one closed heart but a left cubic, two DETACHED arcs and a
    // right cubic: the right cubic happened to continue from the second arc's end,
    // so the right lobe joined up, while the left cubic's endpoint was left
    // dangling and `close()` cut straight across it. That notch is why the reading
    // core fell outside the heart on the LEFT only — an asymmetry in a shape that
    // is meant to be mirror-symmetric, caught by
    // `BubbleSilhouetteCoreInstrumentedTest`.
    //
    // `arcTo(..., forceMoveTo = false)` is the Compose reading of iOS's
    // `addArc(center:radius:180 -> 0)` inside a single path.
    arcTo(Rect(0f, topY - w * 0.25f, w * 0.5f, topY + w * 0.25f), 180f, 180f, false)
    arcTo(Rect(w * 0.5f, topY - w * 0.25f, w, topY + w * 0.25f), 180f, 180f, false)
    cubicTo(
        w, h - tail,
        w / 2f + w * 0.42f, h - tail * 0.842f,
        w / 2f, h
    )
    close()
}

/**
 * Car: a body with a cabin above and two wheels below. The reading core is the
 * body, which is why coreInsets reserves the top and bottom bands.
 */
val CarShape: Shape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val bodyTop = h * 0.30f
    val bodyBottom = h - h * 0.16f
    addRoundRect(
        RoundRect(Rect(0f, bodyTop, w, bodyBottom), CornerRadius(16f, 16f))
    )
    // Cabin: a smaller roof block centred on the body.
    addRoundRect(
        RoundRect(
            Rect(w * 0.20f, 0f, w * 0.80f, bodyTop * 1.35f),
            CornerRadius(12f, 12f)
        )
    )
    val r = min(h * 0.16f, w * 0.12f)
    for (cx in listOf(w * 0.24f, w - w * 0.24f)) {
        addOval(Rect(cx - r, bodyBottom - r * 0.55f, cx + r, bodyBottom + r * 1.45f))
    }
    close()
}

/**
 * Balloon: an egg body, a knot, and a string.
 *
 * 🔴 IT USED TO BE `addOval(0, 0, w, bodyBottom)`, AND THAT WAS TWO BUGS.
 *
 * **1. The text fell out of it.** An ellipse only contains a rectangle inset by a
 * fraction of its own size, and [coreInsets] is a fixed dp. At a realistic
 * one-line bubble (about 320 x 120) the reading core poked outside the oval at
 * ALL FOUR corners, so the last characters of a line sat on the chat background
 * with no bubble behind them.
 *
 * **2. It did not read as a balloon.** A full-width oval at 2.7:1 is a lens, not
 * a balloon, and the 0.06h knot was too small to say otherwise.
 *
 * Both are fixed by giving up on the body carrying the idea. The body is a
 * SUPERELLIPSE (k = 0.62, squarer than an ellipse's 0.5523) so it holds a wide
 * rectangle of text, tapered at the top and bottom so it still reads as an egg;
 * the BALLOON-ness comes from the knot and the curled string below it, which is
 * what survives at every aspect ratio.
 *
 * ⚠️ [tail] is bounded by WIDTH as well as height. Bubbles are capped at 300.dp
 * wide (`ChatScreen.widthIn(max = 300.dp)`) but a long message grows arbitrarily
 * TALL, so a purely height-derived reserve would outrun the fixed dp of
 * [coreInsets] and put the text back outside the shape — the exact bug above,
 * re-introduced from the other end.
 */
val BalloonShape: Shape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val tail = minOf(h * 0.26f, w * 0.115f)   // knot + string live here
    val bodyH = h - tail
    val k = 0.62f
    val tx0 = w * 0.05f
    val tx1 = w * 0.95f
    val bx0 = w * 0.13f
    val bx1 = w * 0.87f
    val wy = bodyH * 0.46f                    // widest point sits BELOW the middle

    moveTo(w * 0.5f, 0f)
    cubicTo(tx1, 0f, w, wy - wy * k, w, wy)
    cubicTo(w, wy + (bodyH - wy) * k, bx1 + (w - bx1) * k, bodyH, bx1, bodyH)
    cubicTo(w * 0.60f, bodyH + tail * 0.115f, w * 0.545f, bodyH + tail * 0.173f,
            w * 0.5f, bodyH + tail * 0.19f)
    cubicTo(w * 0.455f, bodyH + tail * 0.173f, w * 0.40f, bodyH + tail * 0.115f, bx0, bodyH)
    cubicTo(bx0 - bx0 * k, bodyH, 0f, wy + (bodyH - wy) * k, 0f, wy)
    cubicTo(0f, wy - wy * k, tx0, 0f, w * 0.5f, 0f)
    close()

    // Knot: a trapezoid, not a spike. A spike centred under a bubble reads as a
    // speech tail, which is the one thing this shape must NOT be mistaken for.
    val kw = minOf(w * 0.040f, tail * 0.50f)
    val ky = bodyH + tail * 0.173f
    val kb = ky + tail * 0.288f
    moveTo(w * 0.5f - kw, ky)
    lineTo(w * 0.5f + kw, ky)
    lineTo(w * 0.5f + kw * 0.55f, kb)
    lineTo(w * 0.5f - kw * 0.55f, kb)
    close()
}

/** Cat: rounded head with two triangular ears, entirely above the reading core. */
val CatShape: Shape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val earH = h * 0.18f
    addRoundRect(RoundRect(Rect(0f, earH, w, h), CornerRadius(22f, 22f)))
    for (cx in listOf(w * 0.22f, w - w * 0.22f)) {
        val half = w * 0.10f
        moveTo(cx - half, earH + 2f)
        lineTo(cx, 0f)
        lineTo(cx + half, earH + 2f)
        close()
    }
}

/**
 * Dog: a rounded head with two floppy ears hanging outside it, and a snout.
 *
 * 🔴 THE EARS USED TO BE SIZED ON WIDTH (`w * 0.13f`), WHICH IS WHY IT DID NOT
 * READ AS A DOG. On a wide one-line bubble that is a pair of tall thin bars
 * about a third of the frame long, protruding barely past a head whose own edge
 * started at `earW * 0.55f` — a rounded rectangle with two tabs. An ear is a
 * feature of a HEAD, so it is sized on the head's height here and only bounded
 * by width; it stays ear-shaped whether the message is one word or ten lines.
 *
 * The old file also claimed COMIC, HEART and DOG "read on their outline alone"
 * and gave the dog no markings at all. That is true of a heart and false of a
 * dog: without a snout this is a rectangle with ears. See [bubbleMarkings].
 *
 * ⚠️ Every reserve is bounded by WIDTH as well as height, for the reason spelled
 * out on [BalloonShape]: bubbles are capped at 300.dp wide but grow arbitrarily
 * tall, and a purely height-derived head inset would slide out from under the
 * fixed dp of [coreInsets] on a long message and leave text outside the head.
 */
val DogShape: Shape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val earW = minOf(h * 0.30f, w * 0.13f)
    val headTop = minOf(h * 0.16f, w * 0.07f)
    val headInset = earW * 0.45f              // <= 0.0585 w, inside the 18.dp start/end
    val radius = minOf(h * 0.22f, w * 0.09f)
    addRoundRect(
        RoundRect(
            Rect(headInset, headTop, w - headInset, h),
            CornerRadius(radius, radius)
        )
    )
    // Ears flush to the frame edges, overlapping the head so the join reads as
    // one silhouette rather than three stuck-together rectangles.
    val earTop = headTop + h * 0.02f
    val earH = h * 0.60f
    for (x in listOf(0f, w - earW)) {
        addRoundRect(
            RoundRect(
                Rect(x, earTop, x + earW, earTop + earH),
                CornerRadius(earW * 0.5f, earW * 0.5f)
            )
        )
    }
}

// ---------------------------------------------------------------------------
// Markings
// ---------------------------------------------------------------------------

/**
 * The details that turn a silhouette into a recognisable thing. Drawn as a
 * low-opacity overlay, never behind the text, and every mark is placed at a
 * fraction of the frame OUTSIDE the reading core (whiskers at 0.74-0.88 h,
 * headlights at 0.02/0.93 w). Nothing here can eat a tap: `drawWithContent`
 * paints, it does not consume input.
 */
fun Modifier.bubbleMarkings(silhouette: BubbleSilhouette, ink: Color): Modifier =
    this.drawWithContent {
        drawContent()
        val w = size.width
        val h = size.height
        when (silhouette) {
            BubbleSilhouette.CAT -> {
                // Whiskers, low and to the sides - clear of the text core.
                for (i in 0 until 3) {
                    val y = h * (0.74f + i * 0.07f)
                    drawLine(
                        color = ink.copy(alpha = 0.30f),
                        start = Offset(w * 0.06f, y),
                        end = Offset(w * 0.20f, y - h * 0.02f),
                        strokeWidth = 1.5f
                    )
                    drawLine(
                        color = ink.copy(alpha = 0.30f),
                        start = Offset(w * 0.94f, y),
                        end = Offset(w * 0.80f, y - h * 0.02f),
                        strokeWidth = 1.5f
                    )
                }
            }
            BubbleSilhouette.CAR -> {
                // Two headlights at the ends of the body.
                drawOval(
                    color = ink.copy(alpha = 0.40f),
                    topLeft = Offset(w * 0.02f, h * 0.52f),
                    size = Size(w * 0.05f, h * 0.10f)
                )
                drawOval(
                    color = ink.copy(alpha = 0.40f),
                    topLeft = Offset(w * 0.93f, h * 0.52f),
                    size = Size(w * 0.05f, h * 0.10f)
                )
            }
            BubbleSilhouette.BALLOON -> {
                // The string. It hangs BELOW the body, which means it is drawn over
                // the CHAT BACKGROUND and not over the bubble — so it is stroked in
                // the rim ink. The old one was 4% of the height (0.96h -> h), a stub
                // too short to read as anything, and the balloon leaned entirely on a
                // body outline that did not read as a balloon either.
                val tail = minOf(h * 0.26f, w * 0.115f)
                val knotBottom = (h - tail) + tail * 0.173f + tail * 0.288f
                val drop = h - knotBottom
                val path = Path().apply {
                    moveTo(w * 0.5f, knotBottom)
                    cubicTo(
                        w * 0.60f, knotBottom + drop * 0.30f,
                        w * 0.40f, knotBottom + drop * 0.70f,
                        w * 0.52f, h
                    )
                }
                drawPath(path, color = ink.copy(alpha = 0.55f), style = Stroke(width = 2f))
                // Specular highlight, upper-left. Smaller than the old one, which at
                // 0.14w x 0.10h read as a hole punched in the bubble.
                drawOval(
                    color = Color.White.copy(alpha = 0.22f),
                    topLeft = Offset(w * 0.17f, h * 0.14f),
                    size = Size(minOf(w * 0.075f, h * 0.20f), h * 0.13f)
                )
            }
            BubbleSilhouette.DOG -> {
                // A snout. Without it the silhouette is a rounded rectangle with two
                // ears, which is what the previous version shipped while claiming the
                // outline was enough.
                //
                // No EYES, deliberately: at a one-line aspect the only clear band above
                // the head is the chat background, and eyes drawn there float OFF the
                // bubble; anywhere lower and they land on the text. Ears plus snout is
                // the same amount of dog for none of that cost — the cat carries
                // whiskers only, for exactly this reason.
                val muzzleW = w * 0.12f
                val muzzleH = minOf(h * 0.085f, w * 0.04f)
                val cy = h - minOf(h * 0.10f, w * 0.05f)
                drawOval(
                    color = Color.White.copy(alpha = 0.20f),
                    topLeft = Offset(w * 0.5f - muzzleW / 2f, cy - muzzleH / 2f),
                    size = Size(muzzleW, muzzleH)
                )
                val nose = minOf(h * 0.028f, w * 0.014f)
                drawOval(
                    color = ink.copy(alpha = 0.55f),
                    topLeft = Offset(w * 0.5f - nose, cy - muzzleH * 0.35f - nose),
                    size = Size(nose * 2f, nose * 1.7f)
                )
            }
            // These read on their outline alone. Adding marks to a heart just
            // makes it a busy heart.
            BubbleSilhouette.COMIC, BubbleSilhouette.HEART -> Unit
        }
    }

// ---------------------------------------------------------------------------
// Applying it
// ---------------------------------------------------------------------------

/**
 * The standard bubble surface.
 *
 * ORDER IS THE WHOLE TRICK: `padding(coreInsets)` comes BEFORE `background`, so
 * the silhouette is drawn around a core the text already fits inside. Swapping
 * those two lines is exactly the bug this file's header exists to prevent
 * (iOS: BubbleStyleManager.swift:423-426).
 *
 * 🔴 THE RIM WAS BUILT AND NEVER BOUND (fixed 2026-08-23).
 * -------------------------------------------------------
 * [rim] arrived with the silhouette port and both chat screens dutifully
 * computed one — and it reached the screen only through [bubbleMarkings], which
 * runs ONLY when a silhouette is set. A silhouette is opt-in and stored as
 * "off" by default, so for every user who has never opened the appearance
 * picker the rim was computed, passed down, and dropped on the floor.
 *
 * That matters because the rim is not decoration. iOS calls it "the readability
 * fix" (`BubbleStyleManager.swift:307-323`): a received bubble is a pale surface
 * with no stroke and no shadow, and `ChatWallpaper` lets a user put an ARBITRARY
 * PHOTO behind it. Over a light photo the bubble's edge disappears and the text
 * floats on the picture. The default bubble — the one everybody actually has —
 * was the one case that never got it.
 *
 * A silhouette deliberately gets NO stroke: a hairline traced around a heart or
 * a car reads as an outline drawing, not as a lit edge, and iOS omits it there
 * too (`BubbleStyleManager.swift:420-425`).
 *
 * @param rim full-strength ink. The hairline is drawn at [rimAlpha]; the
 *   markings keep the full strength, because a whisker at 0.10 alpha is not a
 *   whisker. Use `Oshi.Bubble.rimInk` / `Oshi.Bubble.rimAlpha` for both.
 */
fun Modifier.bubbleSurface(
    silhouette: BubbleSilhouette?,
    brush: Brush,
    fallbackShape: Shape,
    rim: Color,
    rimAlpha: Float = 0.10f,
    elevation: Dp = Oshi.Elevation.low,
    /**
     * __CHAT_POLISH_2026_09_24__ Outgoing only: a faint top sheen (drawn in the
     * background layer, under the glyphs) and a lit upper edge, as on iOS
     * `BubbleSurface`. `ChatPaletteContrastTest` composites the sheen onto the
     * light stop before measuring white text on it.
     */
    gloss: Boolean = false,
    /**
     * __OLED_GLASS_2026_11_16__ OLED black, incoming bubble: the flat rim becomes
     * the glass rim (1 dp, white 18 % → 4 %) with a faint top sheen. No blur and
     * an opaque fill — per-row blur would cost every frame of a scroll.
     */
    glass: Boolean = false
): Modifier = if (silhouette == null) {
    this
        // `shadow` before `background`, and `clip = false`: the fill below is
        // opaque and covers the whole outline, so this shadows the bubble's
        // silhouette only and never blurs the glyphs sitting on it. iOS relies
        // on the same compositing rule (BubbleStyleManager.swift:398-404).
        .shadow(elevation = elevation, shape = fallbackShape, clip = false)
        .background(brush = brush, shape = fallbackShape)
        .then(
            if (gloss) Modifier
                .background(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = Oshi.Bubble.outgoingSheen),
                        0.5f to Color.White.copy(alpha = 0f)
                    ),
                    shape = fallbackShape
                )
                .border(
                    width = 0.75.dp,
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = Oshi.Bubble.outgoingHighlight),
                        0.5f to Color.White.copy(alpha = 0f)
                    ),
                    shape = fallbackShape
                )
            else Modifier
        )
        .then(
            if (glass) Modifier.glassHairline(fallbackShape)
            else Modifier.border(width = Oshi.Bubble.rimWidth, color = rim.copy(alpha = rimAlpha), shape = fallbackShape)
        )
} else {
    this
        .padding(silhouette.coreInsets)
        .background(brush = brush, shape = silhouette.shape)
        .bubbleMarkings(silhouette, rim)
}

// ---------------------------------------------------------------------------
// Preferences
// ---------------------------------------------------------------------------

/**
 * Where the three new bubble preferences live.
 *
 * A separate SharedPreferences file rather than the app's DataStore because the
 * DataStore keys are owned by `SettingsViewModel`, which another team holds. The
 * values are read straight from the composition (they are three ints, read once
 * per screen), so nothing else has to be threaded through.
 */
object BubbleAppearancePrefs {
    private const val FILE = "bubble_appearance"
    private const val KEY_ACCENT = "oshi.bubble.accent"
    private const val KEY_SILHOUETTE = "oshi.bubble.silhouette"
    private const val KEY_BOLD = "oshi.bubble.boldText"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun accent(context: Context): BubbleAccent =
        BubbleAccent.fromStored(prefs(context).getInt(KEY_ACCENT, 0))

    fun setAccent(context: Context, value: BubbleAccent) {
        prefs(context).edit().putInt(KEY_ACCENT, value.ordinal).apply()
    }

    fun silhouette(context: Context): BubbleSilhouette? =
        BubbleSilhouette.fromStored(prefs(context).getInt(KEY_SILHOUETTE, 0))

    fun setSilhouette(context: Context, value: BubbleSilhouette?) {
        prefs(context).edit().putInt(KEY_SILHOUETTE, BubbleSilhouette.toStored(value)).apply()
    }

    fun boldText(context: Context): Boolean = prefs(context).getBoolean(KEY_BOLD, false)

    fun setBoldText(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_BOLD, value).apply()
    }
}

/** Everything a bubble needs to paint itself, resolved once per chat screen. */
data class BubbleAppearance(
    val accent: BubbleAccent = BubbleAccent.OSHI,
    val silhouette: BubbleSilhouette? = null,
    val boldText: Boolean = false
)

val LocalBubbleAppearance = compositionLocalOf { BubbleAppearance() }
