package com.oshi.messenger.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
) {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val glass = com.oshi.messenger.ui.theme.glassOn()
    val scrimAlpha = if (canBlur) 0.70f else 0.88f
    val scrim = if (glass) com.oshi.messenger.ui.theme.GlassTier.Bar.fill(canBlur) else scrimColor.copy(alpha = scrimAlpha)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (glass) Modifier.glassEdge(top = edgeOnTop) else Modifier)
    ) {
        if (wallpaper != null) {
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
