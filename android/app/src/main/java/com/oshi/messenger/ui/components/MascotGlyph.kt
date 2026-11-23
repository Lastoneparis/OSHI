package com.oshi.messenger.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.oshi.messenger.data.model.Mascot
import com.oshi.messenger.ui.media.BubbleMedia

/**
 * __O_BUBBLE_MASCOT_2026_11_16__ "Animated OSHI reactions" (Settings ›
 * Appearance), default ON. Off = every reaction is drawn as the system emoji
 * again. Its own SharedPreferences file (like [BubbleAppearancePrefs]) so the
 * DataStore owned by `SettingsViewModel` is untouched.
 *
 * Read from composition for every reaction glyph, so the value is cached in
 * memory after the first read and a snapshot counter makes every glyph
 * recompose when it is flipped.
 */
object OshiMascotPrefs {
    private const val FILE = "oshi_mascot"
    private const val KEY_ANIMATED_REACTIONS = "animated_reactions"

    private val version = mutableIntStateOf(0)

    @Volatile
    private var cached: Boolean? = null

    fun animatedReactions(context: Context): Boolean {
        version.intValue
        cached?.let { return it }
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ANIMATED_REACTIONS, true)
            .also { cached = it }
    }

    fun setAnimatedReactions(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ANIMATED_REACTIONS, enabled).apply()
        cached = enabled
        version.intValue++
    }
}

/** System "Remove animations" (animator duration scale 0): stills only. */
fun isSystemReduceMotion(context: Context): Boolean = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
} catch (_: Exception) {
    false
}

/**
 * The still frames (`static/<name>_128.png`), decoded ONCE per size and shared
 * by every chip on screen. A chat list can show dozens of reaction chips; each
 * one decoding its own PNG on bind, or worse running its own animated drawable,
 * is exactly the per-row cost a list cannot afford. Bitmaps are pre-scaled
 * (bilinear) to the drawn pixel size so the draw itself is a straight blit.
 */
object MascotStills {
    // 20 mascots × a couple of sizes (chip, picker, sheet) at ≤128 px.
    private val cache = LruCache<String, ImageBitmap>(64)

    fun get(context: Context, mascot: Mascot, targetPx: Int): ImageBitmap? {
        val px = targetPx.coerceIn(16, 256)
        val key = "${mascot.name}@$px"
        cache.get(key)?.let { return it }
        val full = runCatching {
            context.assets.open(mascot.staticAsset).use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null
        val scaled = if (full.width == px) full else Bitmap.createScaledBitmap(full, px, px, true)
        if (scaled !== full) full.recycle()
        return scaled.asImageBitmap().also { cache.put(key, it) }
    }
}

/**
 * One mascot at [size]. [animate] plays the looping WebP (API 28+,
 * `AnimatedImageDrawable` through the app's shared Coil loader, decoded at the
 * drawn size); otherwise — or under "Remove animations", or before the
 * animation is ready — the cached still is drawn, so the cell is never empty.
 *
 * Coil does not memory-cache animated drawables, which is why only the picker
 * cells animate; reaction chips in the thread use [animate] = false.
 */
@Composable
fun MascotGlyph(
    mascot: Mascot,
    size: Dp,
    animate: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = mascot.emoji,
) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val still = remember(mascot.name, px) { MascotStills.get(context, mascot, px) }
    val reduceMotion = remember { isSystemReduceMotion(context) }
    if (animate && !reduceMotion && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val request = remember(mascot.name, px) {
            ImageRequest.Builder(context)
                .data("file:///android_asset/${mascot.webpAsset}")
                .size(px)
                .crossfade(false)
                .build()
        }
        val stillPainter = remember(still) { still?.let { BitmapPainter(it) } }
        AsyncImage(
            model = request,
            imageLoader = BubbleMedia.imageLoader(context),
            placeholder = stillPainter,
            error = stillPainter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
    } else if (still != null) {
        Image(
            bitmap = still,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
    } else {
        Box(modifier.size(size))
    }
}
