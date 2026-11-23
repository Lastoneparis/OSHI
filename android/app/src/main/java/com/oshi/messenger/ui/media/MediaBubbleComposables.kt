package com.oshi.messenger.ui.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Bubble target. A photo bubble is capped at 250 dp tall; 1080 px covers it on any density. */
const val BUBBLE_MAX_DIMENSION_PX = 1080

/** Full-screen target — iOS decodes at `maxDimension: 1920` (`MediaManager.swift:378`). */
const val FULLSCREEN_MAX_DIMENSION_PX = 1920

/**
 * The one way a message bubble should put bytes on screen.
 *
 * Downsampled, cached by **message id** (never by the byte array), memory-only. See
 * [BubbleMedia] for the three defects this replaces and the iOS code that names them.
 */
@Composable
fun BubbleAsyncImage(
    messageId: String,
    data: ByteArray,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    maxDimensionPx: Int = BUBBLE_MAX_DIMENSION_PX
) {
    val context = LocalContext.current
    // `remember` keys on the ID and the SIZE, not on the array: a new instance of the
    // same bytes must not rebuild the request, and rebuilding it would not matter
    // anyway — the memory-cache key is the id.
    val request = remember(messageId, maxDimensionPx, data.size) {
        BubbleMedia.request(context, messageId, data, maxDimensionPx)
    }
    AsyncImage(
        model = request,
        imageLoader = BubbleMedia.imageLoader(context),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier
    )
}

/**
 * The video bubble's poster frame. Decoded off the main thread, from RAM, cached by
 * message id — see [VideoThumbnails].
 */
@Composable
fun rememberVideoThumbnail(messageId: String, data: ByteArray?): State<android.graphics.Bitmap?> =
    produceState<android.graphics.Bitmap?>(null, messageId, data?.size) {
        val bytes = data
        value = if (bytes == null) null else withContext(Dispatchers.IO) {
            VideoThumbnails.thumbnail(messageId, bytes)
        }
    }

/**
 * Waveform + true duration for one voice note, computed once per message id and cached.
 *
 * Returns null until the analysis lands; the caller keeps rendering its bar until then.
 */
@Composable
fun rememberVoiceNoteAnalysis(
    messageId: String,
    data: ByteArray?,
    storedDurationMs: Long?,
    legacyBody: String?
): VoiceNoteAnalysis? {
    var analysis by remember(messageId) { mutableStateOf(VoiceNoteAnalysisCache.get(messageId)) }
    LaunchedEffect(messageId, data?.size, storedDurationMs) {
        if (analysis != null) return@LaunchedEffect
        val bytes = data ?: return@LaunchedEffect
        if (bytes.isEmpty()) return@LaunchedEffect
        analysis = withContext(Dispatchers.Default) {
            VoiceNoteAnalysisCache.get(messageId) ?: run {
                // The field, then the legacy body, then the container itself. The probe
                // is the ONLY source for an iPhone voice note — its body is empty because
                // the V2 file path carries no caption.
                val declared = VoiceNoteAudio.resolveDurationSeconds(storedDurationMs, legacyBody)
                val seconds = declared ?: VoiceNoteAudio.probeDurationSeconds(bytes) ?: 0f
                VoiceNoteAnalysis(
                    waveform = VoiceNoteAudio.generateWaveform(bytes),
                    durationSeconds = seconds
                ).also { VoiceNoteAnalysisCache.put(messageId, it) }
            }
        }
    }
    return analysis
}

/**
 * iOS `AudioWaveformView` (`AudioMessageView.swift:272-291`): one rounded bar per level,
 * 2 pt wide with 2 pt gaps, height `level * available`, bars before the playhead at full
 * colour and the rest at 30 %.
 *
 * Decoration — `accessibilityHidden` on iOS (`AudioMessageView.swift:90`), so no
 * content description here either.
 */
@Composable
fun VoiceWaveform(
    levels: FloatArray,
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    /**
     * __VOICE_SCRUB_2026_10_26__ Non-null makes the strip a scrubber: tap to play
     * from a point, drag to move the head. `startIfIdle` tells the caller which
     * one happened — a tap means "play from here", a drag only moves the head.
     *
     * Null keeps it pure decoration, which is what the record-preview sheet and
     * the mini player want.
     */
    onSeek: ((progress: Float, startIfIdle: Boolean) -> Unit)? = null
) {
    if (levels.isEmpty()) return
    val density = LocalDensity.current
    val barPx = with(density) { 2.dp.toPx() }
    val gapPx = with(density) { 2.dp.toPx() }
    val minBarPx = with(density) { 4.dp.toPx() }
    val knobPx = with(density) { 7.dp.toPx() }
    val knobDraggingPx = with(density) { 11.dp.toPx() }
    val dimmed = color.copy(alpha = 0.3f)

    // While the finger is down the head follows IT, not the player's 10 Hz
    // progress poll — otherwise every tick yanked it back under the finger.
    var scrub by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableStateOf(0f) }
    val shown = scrub ?: progress

    val gestures = if (onSeek == null) Modifier else Modifier
        .pointerInput(Unit) {
            detectTapGestures { offset ->
                if (width > 0f) onSeek(VoiceNoteAudio.seekProgress(offset.x, width), true)
            }
        }
        .pointerInput(Unit) {
            // A vertical drag is the conversation scrolling and must stay with
            // the list: `detectHorizontalDragGestures` only claims the pointer
            // once the movement is mostly horizontal, which is exactly the rule
            // the photo bubble learned the hard way on iOS
            // (__PHOTO_OPENS_ON_SCROLL_2026_08_24__).
            detectHorizontalDragGestures(
                onDragStart = { offset ->
                    if (width > 0f) scrub = VoiceNoteAudio.seekProgress(offset.x, width)
                },
                onDragEnd = {
                    val landed = scrub
                    scrub = null
                    // Releasing leaves the note as it was: paused stays paused.
                    if (landed != null) onSeek(landed, false)
                },
                onDragCancel = { scrub = null }
            ) { change, _ ->
                if (width <= 0f) return@detectHorizontalDragGestures
                val p = VoiceNoteAudio.seekProgress(change.position.x, width)
                scrub = p
                onSeek(p, false)
            }
        }

    // Measured, not read off the draw scope: writing state from inside a draw
    // lambda re-enters composition on every frame.
    Canvas(modifier = modifier.onSizeChanged { width = it.width.toFloat() }.then(gestures)) {
        val count = levels.size
        val step = barPx + gapPx
        // Draw at most what fits; the strip is fixed-width in every caller.
        val drawable = max(1, ((size.width + gapPx) / step).roundToInt().coerceAtMost(count))
        for (i in 0 until drawable) {
            val level = levels[i * count / drawable]
            val h = max(minBarPx, level * size.height)
            val x = i * step
            val passed = i.toFloat() / drawable <= shown
            drawRoundRect(
                color = if (passed) color else dimmed,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barPx, h),
                cornerRadius = CornerRadius(barPx / 2f, barPx / 2f)
            )
        }
        // The grab handle, only once the note is the one being heard or moved:
        // a waveform with nothing to aim at does not look draggable, which is
        // why nobody tried. Mirrors the iOS knob in `InlineVoiceNoteView`.
        if (onSeek != null && (shown > 0f || scrub != null)) {
            val r = (if (scrub != null) knobDraggingPx else knobPx) / 2f
            drawCircle(
                color = color,
                radius = r,
                center = Offset((shown.coerceIn(0f, 1f) * size.width), size.height / 2f)
            )
        }
    }
}
