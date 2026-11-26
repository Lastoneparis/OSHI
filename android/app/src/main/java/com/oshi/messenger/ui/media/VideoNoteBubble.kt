package com.oshi.messenger.ui.media

import android.graphics.Matrix
import android.provider.Settings
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.oshi.messenger.R
import com.oshi.messenger.service.media.VideoNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * __VIDEO_NOTE_2026_09_24__ Brand colours of the OSHI "O": the ring around every note,
 * in the recorder and in the chat, is the logo's circle.
 */
object VideoNoteBrand {
    val Purple = Color(0xFF5616EC)
    val Violet = Color(0xFF9933FF)
    val Blue = Color(0xFF3B82F6)

    val ring: Brush = Brush.sweepGradient(listOf(Purple, Violet, Blue, Purple))
}

/** True when the user turned animations off (Settings › Accessibility › Remove animations). */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** Silent on-screen loop, or one pass with sound after a tap. */
enum class VideoNoteMode { IDLE, PREVIEW, SOUND }

/**
 * One note TALKS at a time, like a voice note: starting one sends the others back to
 * their silent loop. Any number may loop silently.
 */
object VideoNotePlayback {
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current
    fun claim(id: String) { _current.value = id }
    fun release(id: String) { if (_current.value == id) _current.value = null }
}

/**
 * A received or sent round video note (docs/VIDEO_NOTE_SPEC.md §4): a ~200 dp circle,
 * first frame inside, a small duration capsule at the bottom.
 *
 * __VIDEO_NOTE_AUTOLOOP_2026_09_29__ (same as iOS): while the bubble is on screen it LOOPS
 * SILENTLY — the audio track is disabled and no audio focus is taken, so the user's music
 * keeps playing. Tap restarts it from the beginning WITH sound and the brand ring shows the
 * position; tap again pauses. At the end, or when another note starts talking, it falls
 * back to the silent loop. Off with "Remove animations" and in Battery Saver.
 * Long-press is the message menu, exactly like any other media.
 *
 * Playback is from MEMORY (`ByteArrayDataSource`): the decrypted MP4 never touches disk,
 * so there is no plaintext copy to clean up after it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoNoteBubble(
    messageId: String,
    videoData: ByteArray?,
    durationMs: Long?,
    modifier: Modifier = Modifier,
    diameter: Dp = 200.dp,
    isLoading: Boolean = false,
    onRetry: (() -> Unit)? = null,
    onLongPress: () -> Unit = {},
    onDoubleTap: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val reduceMotion = rememberReduceMotion()
    val current by VideoNotePlayback.current.collectAsState()
    var player by remember(messageId) { mutableStateOf<ExoPlayer?>(null) }
    var isPlaying by remember(messageId) { mutableStateOf(false) }
    var mode by remember(messageId) { mutableStateOf(VideoNoteMode.IDLE) }
    val autoplay = remember(reduceMotion) {
        val saver = runCatching {
            context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        }.getOrDefault(false)
        !reduceMotion && !saver
    }
    var positionMs by remember(messageId) { mutableLongStateOf(0L) }
    var knownDuration by remember(messageId, durationMs) { mutableLongStateOf(durationMs ?: 0L) }
    val thumbnail by rememberVideoThumbnail(messageId, videoData)

    // Duration unknown on the wire (spec §1 rule 2) ⇒ read it from the media.
    LaunchedEffect(messageId, videoData, durationMs) {
        if ((durationMs ?: 0L) <= 0L && videoData != null) {
            knownDuration = withContext(Dispatchers.IO) { probeDurationMs(videoData) }
        }
    }

    fun stop() {
        player?.let { p -> runCatching { p.release() } }
        player = null
        isPlaying = false
        positionMs = 0L
        mode = VideoNoteMode.IDLE
        VideoNotePlayback.release(messageId)
    }

    /** Silent loop (no audio track, no focus) or one pass with sound (focus taken). */
    fun setSound(exo: ExoPlayer, on: Boolean) {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !on)
            .build()
        exo.volume = if (on) 1f else 0f
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ on
        )
        exo.repeatMode = if (on) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
    }

    fun backToPreviewOrStop() {
        val exo = player
        VideoNotePlayback.release(messageId)
        positionMs = 0L
        if (!autoplay || exo == null) { stop(); return }
        setSound(exo, false)
        exo.seekTo(0)
        exo.playWhenReady = true
        mode = VideoNoteMode.PREVIEW
    }

    fun buildPlayer(bytes: ByteArray): ExoPlayer {
        val exo = ExoPlayer.Builder(context).build()
        val source = ProgressiveMediaSource.Factory { ByteArrayDataSource(bytes) }
            .createMediaSource(MediaItem.fromUri("oshi-note://$messageId"))
        exo.setMediaSource(source)
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && knownDuration <= 0L) {
                    knownDuration = exo.duration.coerceAtLeast(0L)
                }
                // End of the pass WITH sound (the silent loop repeats and never ends)
                // → back to the loop. Handled on the next loop turn, never from
                // inside the player's own callback.
                if (state == Player.STATE_ENDED) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (player === exo && mode == VideoNoteMode.SOUND) backToPreviewOrStop()
                    }
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        })
        exo.prepare()
        return exo
    }

    fun startPreview() {
        if (mode != VideoNoteMode.IDLE || !autoplay) return
        val bytes = videoData ?: return
        val exo = player ?: buildPlayer(bytes).also { player = it }
        setSound(exo, false)
        exo.playWhenReady = true
        mode = VideoNoteMode.PREVIEW
    }

    fun playWithSound() {
        val bytes = videoData ?: return
        val exo = player ?: buildPlayer(bytes).also { player = it }
        VideoNotePlayback.claim(messageId)
        setSound(exo, true)
        exo.seekTo(0)
        positionMs = 0L
        exo.playWhenReady = true
        mode = VideoNoteMode.SOUND
    }

    // Another note started talking: this one goes quiet and keeps looping.
    LaunchedEffect(current) {
        if (current != messageId && mode == VideoNoteMode.SOUND) backToPreviewOrStop()
    }
    DisposableEffect(messageId) { onDispose { stop() } }
    // On screen (the lazy list composes it) and loaded: loop silently.
    LaunchedEffect(messageId, videoData != null, autoplay) { startPreview() }

    fun toggle() {
        val p = player
        when {
            mode != VideoNoteMode.SOUND || p == null -> playWithSound()
            p.isPlaying -> p.pause()
            else -> { VideoNotePlayback.claim(messageId); p.play() }
        }
    }

    // Progress ring: sampled once per frame while playing.
    LaunchedEffect(player, isPlaying, mode) {
        val p = player ?: return@LaunchedEffect
        if (mode != VideoNoteMode.SOUND) return@LaunchedEffect
        while (isActive && isPlaying) {
            withFrameMillis { }
            positionMs = p.currentPosition.coerceAtLeast(0L)
        }
    }

    val total = knownDuration.coerceAtLeast(1L)
    val withSound = mode == VideoNoteMode.SOUND
    val soundPlaying = withSound && isPlaying
    val progress = if (withSound) (positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
    val pressedScale by animateFloatAsState(
        targetValue = if (soundPlaying && !reduceMotion) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "note_scale"
    )
    val label = stringResource(R.string.video_note_a11y, VideoNote.formatClock(knownDuration))
    val stateLabel = stringResource(if (soundPlaying) R.string.video_note_pause else R.string.video_note_play)

    Box(
        modifier = modifier
            .size(diameter)
            .scale(pressedScale)
            .semantics(mergeDescendants = true) { contentDescription = "$label. $stateLabel" }
            .combinedClickable(
                role = Role.Button,
                onClickLabel = stateLabel,
                onClick = {
                    if (videoData != null) toggle() else onRetry?.invoke()
                },
                onDoubleClick = onDoubleTap,
                onLongClick = onLongPress
            ),
        contentAlignment = Alignment.Center
    ) {
        // The picture: first frame at rest, the live texture while a player exists.
        Box(
            modifier = Modifier
                .padding(4.dp)
                .fillMaxSize()
                .clip(CircleShape)
                // __OLED_GLASS_2026_11_16__ OLED black: a neutral glass disc, not indigo.
                .background(if (com.oshi.messenger.ui.theme.oledGlassOn()) com.oshi.messenger.ui.theme.OledGlass.BubbleFill else Color(0xFF1B1530)),
            contentAlignment = Alignment.Center
        ) {
            thumbnail?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            val p = player
            if (p != null) {
                AndroidView(
                    factory = { ctx -> TextureView(ctx) },
                    update = { tv -> attachCenterCrop(p, tv) },
                    modifier = Modifier.fillMaxSize()
                )
            }
            when {
                isLoading -> CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                videoData == null -> Icon(
                    Icons.Default.Refresh, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
                mode != VideoNoteMode.PREVIEW && !soundPlaying -> Surface(color = Color.Black.copy(alpha = 0.45f), shape = CircleShape, modifier = Modifier.size(52.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp)
                        )
                    }
                }
                else -> {}
            }
        }

        // The brand ring: a faint full track, the gradient arc = playback position.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                brush = VideoNoteBrand.ring, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, alpha = if (withSound) 0.25f else 0.9f,
                style = Stroke(width = stroke)
            )
            if (withSound) {
                drawArc(
                    brush = VideoNoteBrand.ring, startAngle = -90f, sweepAngle = 360f * progress,
                    useCenter = false, topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(width = stroke * 1.6f, cap = StrokeCap.Round)
                )
            }
        }

        // Duration capsule: total at rest, remaining while playing.
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                // Muted glyph on the silent loop = "tap for sound"; speaker while it talks.
                if (mode == VideoNoteMode.PREVIEW || soundPlaying) {
                    Icon(
                        if (soundPlaying) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp)
                    )
                }
                Text(
                    text = VideoNote.formatClock(
                        if (withSound) (knownDuration - positionMs).coerceAtLeast(0L) else knownDuration
                    ),
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * Route [player]'s video into [view] with a CENTRE-CROP transform, so a note that is not
 * perfectly square (another client, an older encoder) fills the circle instead of being
 * squashed. For the 480 × 480 the spec requires the matrix is the identity.
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun attachCenterCrop(player: ExoPlayer, view: TextureView) {
    if (view.tag === player) return
    view.tag = player
    player.setVideoTextureView(view)
    fun apply(size: VideoSize) {
        val vw = view.width.toFloat(); val vh = view.height.toFloat()
        if (vw <= 0f || vh <= 0f || size.width <= 0 || size.height <= 0) return
        val videoAspect = size.width * size.pixelWidthHeightRatio / size.height
        val viewAspect = vw / vh
        val m = Matrix()
        if (videoAspect > viewAspect) m.setScale(videoAspect / viewAspect, 1f, vw / 2, vh / 2)
        else m.setScale(1f, viewAspect / videoAspect, vw / 2, vh / 2)
        view.setTransform(m)
    }
    player.addListener(object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) = apply(videoSize)
    })
    view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply(player.videoSize) }
}

/** Container duration of in-memory MP4 bytes, 0 when unreadable. Blocking. */
fun probeDurationMs(bytes: ByteArray): Long {
    val r = android.media.MediaMetadataRetriever()
    return try {
        r.setDataSource(ByteArrayMediaDataSource(bytes))
        r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (_: Throwable) {
        0L
    } finally {
        runCatching { r.release() }
    }
}
