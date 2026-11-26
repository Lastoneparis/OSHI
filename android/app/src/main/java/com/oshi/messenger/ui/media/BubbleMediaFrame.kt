package com.oshi.messenger.ui.media

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oshi.messenger.ui.theme.Oshi
import java.io.ByteArrayInputStream

/**
 * __BUBBLE_MEDIA_2026_11_21__ The Android glue around [MediaBubbleSizing]: reading a
 * picture's natural size cheaply, letting it reach the bubble's edges, and the
 * time pill a media-only bubble carries (BUBBLE_MEDIA_SPEC §1).
 */
object MediaNaturalSize {

    private val cache = LruCache<String, IntArray>(512)

    /** Already measured for this message, without touching any bytes. */
    fun cached(messageId: String): IntArray? = cache.get(messageId)

    /**
     * Width × height of the picture AS DISPLAYED — header-only decode
     * (`inJustDecodeBounds`, no pixels), with the EXIF rotation applied, because a
     * portrait phone photo is very often stored landscape + "rotate 90" and Coil
     * draws it upright. Null when the bytes are not a picture BitmapFactory knows.
     */
    fun read(messageId: String, data: ByteArray): IntArray? {
        cache.get(messageId)?.let { return it }
        if (data.isEmpty()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        var w = opts.outWidth
        var h = opts.outHeight
        if (w <= 0 || h <= 0) return null
        val rotated = runCatching {
            when (ExifInterface(ByteArrayInputStream(data))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> true
                else -> false
            }
        }.getOrDefault(false)
        if (rotated) { val t = w; w = h; h = t }
        return intArrayOf(w, h).also { cache.put(messageId, it) }
    }
}

/**
 * The display box for one bubble picture: [MediaBubbleSizing.displaySize] fed with the
 * picture's natural size (px → dp at this density) and the chat's width rule.
 * `natural` null (bytes not here yet) gives the placeholder box at the SAME width, so
 * the thread does not jump when the bytes land.
 */
@Composable
fun rememberBubbleMediaSize(natural: IntArray?, channel: Boolean): MediaBubbleSizing.Size {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val density = LocalDensity.current.density
    return remember(natural?.getOrNull(0), natural?.getOrNull(1), screenWidthDp, density, channel) {
        val maxW = MediaBubbleSizing.maxBubbleWidth(screenWidthDp, channel)
        if (natural == null) MediaBubbleSizing.displaySize(0f, 0f, maxW)
        else MediaBubbleSizing.displaySize(natural[0] / density, natural[1] / density, maxW)
    }
}

/**
 * Let a child reach past its parent's padding — the bubble's text padding must not
 * become a coloured frame around a photo. Measures the child [start] + [end] wider
 * (and [top] + [bottom] taller) than it reports, and places it shifted back, so the
 * bubble wraps the photo's own width and the photo touches the bubble's edges. The
 * bubble clips to its shape, so the photo takes the bubble's corners on the sides it
 * touches and stays square where it meets the name or the caption.
 */
fun Modifier.bleed(start: Dp = 0.dp, end: Dp = 0.dp, top: Dp = 0.dp, bottom: Dp = 0.dp): Modifier =
    layout { measurable, constraints ->
        val s = start.roundToPx()
        val e = end.roundToPx()
        val t = top.roundToPx()
        val b = bottom.roundToPx()
        val placeable = measurable.measure(
            constraints.copy(
                minWidth = constraints.minWidth + s + e,
                maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + s + e else constraints.maxWidth
            )
        )
        val w = (placeable.width - s - e).coerceAtLeast(0)
        val h = (placeable.height - t - b).coerceAtLeast(0)
        layout(w, h) { placeable.place(-s, -t) }
    }

/** The translucent dark pill a media-only bubble draws its time and ticks in. */
@Composable
fun BoxScope.MediaTimePill(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(6.dp)
            .background(Oshi.Bubble.mediaPillFill, RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) { content() }
}

/** A box exactly the display size, for the picture and its overlays. */
@Composable
fun MediaBox(size: MediaBubbleSizing.Size, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.size(size.widthDp.dp, size.heightDp.dp), content = content)
}
