package com.oshi.messenger.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oshi.messenger.ui.theme.glassEdge

/**
 * The top bar and the composer, drawn so the chat wallpaper shows through them.
 *
 * __FROSTED_BARS_2026_11_02__ (N12, second half) Asked for as "the bars at the
 * top and the bottom with a blurred look". The first half of N12 — one
 * wallpaper applied to every conversation — already shipped.
 *
 * THE PART THAT IS NOT OBVIOUS: Compose has no backdrop blur. `Modifier.blur`
 * blurs an element's OWN content, not what is painted behind it, so putting it
 * on the bar would blur the title and the buttons and leave the background
 * crisp — the opposite of the effect. What works is to paint the SAME wallpaper
 * again inside the bar's bounds and blur that copy. That is exact here, and
 * only here, because the wallpaper is a fixed fill (a colour, a gradient, a
 * pattern, a still image) rather than scrolling content: a second copy is
 * indistinguishable from the real backdrop.
 *
 * AND IT MUST STILL WORK ON ANDROID 8. `Modifier.blur` is a NO-OP below API 31
 * — this codebase already records that, in the view-once photo placeholder,
 * where relying on it would have shown the photo in full on every older phone.
 * `minSdk` here is 26. So the blur is an ENHANCEMENT layered on top of a scrim
 * that does the legibility work by itself: on Android 12+ the wallpaper behind
 * the bar is genuinely soft, and below that it is a clean translucent wash.
 * Neither version can leave text sitting on an arbitrary photo unreadable,
 * which is the failure that would matter.
 *
 * [scrimColor] should be the surface colour the bar would otherwise have used.
 *
 * THE SCRIM IS NOT ONE NUMBER, and that is the point. A single alpha cannot
 * serve both cases: thin enough to read as frosted glass on a phone that
 * blurs, and opaque enough to keep a title legible over a user's own photo on
 * one that does not. So it follows the blur — 0.70 where the wallpaper behind
 * is genuinely soft (API 31+), 0.88 where it is a sharp photo. Real frosted
 * glass gets its legibility from the blur; without the blur, only opacity is
 * left to do that work.
 */
@Composable
fun FrostedChatBarBackdrop(
    wallpaper: ChatWallpaper?,
    scrimColor: Color,
    modifier: Modifier = Modifier,
    blurRadius: Int = 24,
    /**
     * __OLED_GLASS_2026_11_16__ Which edge meets the thread: `false` for the
     * top bar (its bottom edge), `true` for the composer (its top edge). Only
     * read under OLED black, where the bar becomes glass: a near-black
     * translucent fill over the blurred wallpaper copy, a lit hairline along
     * that one edge and, for the composer, a faint sheen just under it.
     */
    edgeOnTop: Boolean = false,
    /**
     * __CHAT_BACKDROP_BLUR_2026_11_21__ True when the thread under the bar is
     * frosted LIVE by [frostedBackdropSource] (the messages scrolling under the
     * bar, not just the wallpaper). The bar then paints only its tint: the
     * blurred copy of the wallpaper would hide the live blur underneath it.
     * False keeps the original behaviour exactly (blurred or sharp wallpaper
     * copy + a more opaque tint), which is also what hides messages scrolling
     * under the bar on a device that cannot blur them.
     */
    live: Boolean = false,
) {
    // __GLASS_ALL_THEMES_2026_11_21__ high contrast = no blur, opaque fill.
    val canBlur = com.oshi.messenger.ui.theme.glassCanBlur()
    val glass = com.oshi.messenger.ui.theme.glassOn()
    val blurredBehind = live || canBlur
    val scrimAlpha = when {
        blurredBehind -> 0.70f
        // Nothing frosted and no wallpaper copy to hide the thread: the bar is
        // the plain opaque surface it always was.
        wallpaper == null -> 1f
        else -> 0.88f
    }
    val scrim = if (glass) com.oshi.messenger.ui.theme.glassFill(com.oshi.messenger.ui.theme.GlassTier.Bar, blurredBehind)
        .let { if (!blurredBehind && wallpaper == null) it.copy(alpha = 1f) else it }
        else scrimColor.copy(alpha = scrimAlpha)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (glass) Modifier.glassEdge(top = edgeOnTop) else Modifier)
    ) {
        if (wallpaper != null && !live) {
            // The second copy of the backdrop. Blurred where the platform can;
            // harmless where it cannot, because the scrim below carries the
            // contrast on its own.
            val blurred = if (canBlur) Modifier.blur(blurRadius.dp) else Modifier
            ChatWallpaperBackground(
                wallpaper = wallpaper,
                modifier = Modifier
                    .fillMaxSize()
                    .then(blurred)
            )
        }
        // Always drawn, wallpaper or not: it is what guarantees the bar reads as
        // a bar and the text stays legible.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrim)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// __CHAT_BACKDROP_BLUR_2026_11_21__ The bars frost the THREAD, not a copy.
//
// « mettre des fonds d'écran et un effet flou dans les discussions derrière le
// nom d'un contact » — the top bar (name, avatar, calls) and the composer show
// the wallpaper AND the messages scrolling under them, softened, as iMessage
// and Telegram do. Until now the thread stopped at the bars and the bars drew a
// blurred SECOND COPY of the wallpaper (above): exact for a fixed fill, blind
// to the messages.
//
// How, on Compose 1.5 (no GraphicsLayer.record): the thread's container records
// its own drawing into a `RenderNode` (`content`) once per frame — that is a
// display list, the same work the frame does anyway — and draws it. Then, for
// each bar band only, a second RenderNode with a `RenderEffect` blur draws
// `content` again, clipped to the band. The GPU blurs two bar-sized rectangles,
// never the whole screen, and the child layers (the LazyColumn, each bubble)
// are shared by reference, so a scroll updates the frost without re-running
// any of this code.
//
// Where it does NOT run, the bars fall back to [FrostedChatBarBackdrop] with
// `live = false` — the tinted bar over a copy of the wallpaper, unchanged:
//   * below API 31 (no RenderEffect);
//   * the system asks for more contrast (Glass.kt treats it as "reduce
//     transparency");
//   * battery saver is on (observed live), or the device is a low-RAM one;
//   * the canvas is not hardware-accelerated (screenshot tests, Robolectric).
// ─────────────────────────────────────────────────────────────────────────

/**
 * True when the chat bars may frost the live thread on this device right now.
 * Re-evaluated when battery saver toggles.
 */
@androidx.compose.runtime.Composable
fun rememberLiveChatBackdrop(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    if (!com.oshi.messenger.ui.theme.glassCanBlur()) return false
    val context = androidx.compose.ui.platform.LocalContext.current
    val lowRam = androidx.compose.runtime.remember(context) {
        runCatching {
            context.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice == true
        }.getOrDefault(false)
    }
    if (lowRam) return false
    val powerSave = rememberPowerSaveMode()
    return !powerSave
}

@androidx.compose.runtime.Composable
private fun rememberPowerSaveMode(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pm = androidx.compose.runtime.remember(context) {
        context.getSystemService(android.os.PowerManager::class.java)
    }
    val state = androidx.compose.runtime.remember(pm) {
        androidx.compose.runtime.mutableStateOf(pm?.isPowerSaveMode == true)
    }
    androidx.compose.runtime.DisposableEffect(context, pm) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                state.value = pm?.isPowerSaveMode == true
            }
        }
        val filter = android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state.value
}

/**
 * Put on the container that draws the wallpaper and the thread, which must
 * extend UNDER both bars. Frosts the band `[0, topPx)` and the band
 * `[height - bottomPx, height - bottomCoveredPx)` — the last part being what
 * the keyboard hides anyway, so it is never blurred for nothing.
 *
 * The three lambdas are read at draw time: a change of bar height (a banner
 * appearing, the keyboard sliding) redraws, it does not recompose.
 */
fun Modifier.frostedBackdropSource(
    enabled: Boolean,
    topPx: () -> Float,
    bottomPx: () -> Float,
    bottomCoveredPx: () -> Float = { 0f },
    blurRadius: androidx.compose.ui.unit.Dp = 24.dp,
): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    return this.composed {
        val nodes = androidx.compose.runtime.remember { BackdropNodes() }
        Modifier.drawWithContent {
            nodes.draw(this, topPx(), bottomPx(), bottomCoveredPx(), blurRadius.toPx())
        }
    }
}

/** The three RenderNodes of one frosted thread. API 31+ only (RenderEffect). */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
private class BackdropNodes {
    private val content = android.graphics.RenderNode("oshiChatThread")
    private val top = android.graphics.RenderNode("oshiChatTopFrost")
    private val bottom = android.graphics.RenderNode("oshiChatBottomFrost")
    private var radius = -1f

    fun draw(
        scope: androidx.compose.ui.graphics.drawscope.ContentDrawScope,
        topPx: Float,
        bottomPx: Float,
        bottomCoveredPx: Float,
        radiusPx: Float,
    ) = with(scope) {
        val composeCanvas = drawContext.canvas
        val native = composeCanvas.nativeCanvas
        val w = size.width.toInt()
        val h = size.height.toInt()
        val topBand = kotlin.math.ceil(topPx.coerceIn(0f, size.height)).toInt()
        val bottomStart = (h - kotlin.math.ceil(bottomPx.coerceIn(0f, size.height)).toInt()).coerceAtLeast(topBand)
        val bottomEnd = (h - bottomCoveredPx.coerceAtLeast(0f).toInt()).coerceIn(bottomStart, h)
        if (!native.isHardwareAccelerated || w <= 0 || h <= 0 || (topBand <= 0 && bottomEnd <= bottomStart)) {
            drawContent()
            return@with
        }

        // 1. The thread, recorded once into `content`, then drawn as usual.
        content.setPosition(0, 0, w, h)
        val recording = content.beginRecording(w, h)
        val previous = ComposeCanvasSwap.swap(composeCanvas, recording)
        if (previous == null) {
            content.endRecording()
            drawContent()
            return@with
        }
        try {
            drawContent()
        } finally {
            ComposeCanvasSwap.restore(composeCanvas, previous)
            content.endRecording()
        }
        native.drawRenderNode(content)

        // 2. The bands, each a blurred view of `content`, drawn over it.
        if (radiusPx != radius) {
            radius = radiusPx
            val effect = android.graphics.RenderEffect.createBlurEffect(
                radiusPx, radiusPx, android.graphics.Shader.TileMode.CLAMP
            )
            top.setRenderEffect(effect)
            bottom.setRenderEffect(effect)
        }
        if (topBand > 0) {
            top.setPosition(0, 0, w, topBand)
            val c = top.beginRecording(w, topBand)
            c.drawRenderNode(content)
            top.endRecording()
            native.drawRenderNode(top)
        }
        if (bottomEnd > bottomStart) {
            bottom.setPosition(0, bottomStart, w, bottomEnd)
            val c = bottom.beginRecording(w, bottomEnd - bottomStart)
            c.translate(0f, -bottomStart.toFloat())
            c.drawRenderNode(content)
            bottom.endRecording()
            native.drawRenderNode(bottom)
        }
    }
}

/**
 * __CHAT_BACKDROP_BLUR_2026_11_21__ « avoir le fond sur tout l'écran » — the
 * app's root Scaffold pads every destination out of the status-bar strip (and
 * consumes that inset), so a chat could never paint behind the clock: the strip
 * stayed the root's flat `surface`. A chat screen is handed that padding as
 * [by] and lays itself out [by] taller, shifted up by the same amount, so its
 * wallpaper and its frosted top band run to the top edge of the glass. The
 * screen then puts the same height back above its top bar (the inset is
 * consumed, so the bar would not add it on its own).
 *
 * During a navigation transition the host may clip to its bounds for a few
 * frames; at rest nothing clips it.
 */
fun Modifier.extendUp(by: androidx.compose.ui.unit.Dp): Modifier =
    if (by <= 0.dp) this else this.then(
        Modifier.layout { measurable, constraints ->
            val extra = by.roundToPx()
            val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight + extra else constraints.maxHeight
            val placeable = measurable.measure(
                constraints.copy(minHeight = (constraints.minHeight + extra).coerceAtMost(maxH), maxHeight = maxH)
            )
            layout(placeable.width, (placeable.height - extra).coerceAtLeast(0)) {
                placeable.place(0, -extra)
            }
        }
    )
