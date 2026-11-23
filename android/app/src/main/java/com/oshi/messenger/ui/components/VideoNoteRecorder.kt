package com.oshi.messenger.ui.components

import com.oshi.messenger.ui.theme.glassHairline
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.oshi.messenger.R
import com.oshi.messenger.data.local.atrest.PlaintextScratch
import com.oshi.messenger.service.media.VideoNote
import com.oshi.messenger.service.media.VideoNoteEncoder
import com.oshi.messenger.ui.media.VideoNoteBrand
import com.oshi.messenger.ui.media.attachCenterCrop
import com.oshi.messenger.ui.media.rememberReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "VideoNoteRecorder"

/** The recorder's own states; a sealed ladder so the UI can never show two at once. */
private sealed interface NoteStage {
    data object Idle : NoteStage
    data class Recording(val startedAt: Long) : NoteStage
    data object Encoding : NoteStage
    data class Review(val file: File, val durationMs: Long) : NoteStage
}

/**
 * __VIDEO_NOTE_2026_09_24__ The compact round recorder (owner request: "a round video like
 * the OSHI logo, not a full-screen camera").
 *
 *  * a circular front-camera preview inside the brand ring (the OSHI "O");
 *  * tap the shutter to start, tap again to stop — or HOLD it and release to stop;
 *  * the ring fills over 15 s and the recording stops by itself at exactly 15.0 s;
 *  * then the note loops in the circle with Delete / Retake / Send.
 *
 * Camera: CameraX `Preview` + `VideoCapture<Recorder>` at SD, front camera by default,
 * flip while idle. The capture goes to [PlaintextScratch] (`cacheDir/oshi_plain`, swept at
 * every start) and is deleted as soon as [VideoNoteEncoder] has produced the square MP4;
 * that one is deleted as soon as its bytes are handed to [onSend] or the note is dropped.
 *
 * Permissions reuse the app's existing CAMERA / RECORD_AUDIO manifest entries through the
 * standard runtime request; refusing the microphone still records a silent note.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoNoteRecorder(
    onDismiss: () -> Unit,
    onSend: (bytes: ByteArray, durationMs: Long) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()

    var hasCamera by remember { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }
    var hasMic by remember { mutableStateOf(granted(context, Manifest.permission.RECORD_AUDIO)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasCamera = result[Manifest.permission.CAMERA] ?: hasCamera
        hasMic = result[Manifest.permission.RECORD_AUDIO] ?: hasMic
        if (!hasCamera) {
            Toast.makeText(context, context.getString(R.string.video_note_camera_denied), Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }
    LaunchedEffect(Unit) {
        val missing = buildList {
            if (!hasCamera) add(Manifest.permission.CAMERA)
            if (!hasMic) add(Manifest.permission.RECORD_AUDIO)
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    var stage by remember { mutableStateOf<NoteStage>(NoteStage.Idle) }
    var useFront by remember { mutableStateOf(true) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var captureFile by remember { mutableStateOf<File?>(null) }
    var discardOnFinalize by remember { mutableStateOf(false) }
    // Which lens the bound camera actually is (a device without a front camera falls
    // back to the back one), and which lens the current capture was made with.
    var boundFront by remember { mutableStateOf(true) }
    var recordedWithFront by remember { mutableStateOf(true) }

    val previewView = remember {
        PreviewView(context).apply {
            // TextureView-backed so Compose can clip it to a circle (a SurfaceView cannot).
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Bind / rebind the camera whenever the lens changes (never while recording).
    DisposableEffect(hasCamera, useFront) {
        var provider: ProcessCameraProvider? = null
        if (hasCamera) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val p = future.get()
                    provider = p
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val recorder = Recorder.Builder()
                        .setQualitySelector(
                            QualitySelector.from(
                                Quality.SD,
                                FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                            )
                        )
                        .build()
                    val capture = VideoCapture.withOutput(recorder)
                    val front = useFront && p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                    val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    boundFront = front
                    p.unbindAll()
                    p.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                    videoCapture = capture
                } catch (e: Exception) {
                    Log.w(TAG, "camera bind failed: ${e.message}")
                    Toast.makeText(context, context.getString(R.string.error_camera_unavailable), Toast.LENGTH_SHORT).show()
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            runCatching { provider?.unbindAll() }
            videoCapture = null
        }
    }

    fun deleteQuietly(f: File?) { if (f != null) runCatching { f.delete() } }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val capture = videoCapture ?: return
        if (stage !is NoteStage.Idle) return
        val file = try { PlaintextScratch.newFile(context.cacheDir, ".mp4") } catch (e: Exception) { return }
        captureFile = file
        discardOnFinalize = false
        val pending = capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            .let { if (granted(context, Manifest.permission.RECORD_AUDIO)) it.withAudioEnabled() else it }
        elapsedMs = 0L
        recordedWithFront = boundFront
        stage = NoteStage.Recording(SystemClock.elapsedRealtime())
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                val recorded = elapsedMs
                val src = captureFile
                val failed = event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED &&
                    event.error != VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                if (discardOnFinalize || failed || !VideoNote.isKeepable(recorded) || src == null) {
                    if (failed) Log.w(TAG, "recording finalize error ${event.error}: ${event.cause?.message}")
                    if (!discardOnFinalize && !failed && src != null) {
                        Toast.makeText(context, context.getString(R.string.video_note_too_short), Toast.LENGTH_SHORT).show()
                    }
                    deleteQuietly(src)
                    captureFile = null
                    stage = NoteStage.Idle
                    return@start
                }
                stage = NoteStage.Encoding
                scope.launch {
                    val out = try { PlaintextScratch.newFile(context.cacheDir, ".mp4") } catch (_: Exception) { null }
                    val result = out?.let { VideoNoteEncoder.encode(context, src, it, mirror = recordedWithFront) }
                    // The raw capture is never needed past the encode.
                    deleteQuietly(src)
                    captureFile = null
                    stage = if (result != null && VideoNote.isSendableDuration(result.durationMs)) {
                        NoteStage.Review(result.file, result.durationMs.coerceAtMost(VideoNote.MAX_DURATION_MS))
                    } else {
                        deleteQuietly(result?.file ?: out)
                        Toast.makeText(context, context.getString(R.string.chat_failed_load_video), Toast.LENGTH_SHORT).show()
                        NoteStage.Idle
                    }
                }
            }
        }
    }

    fun stopRecording(discard: Boolean = false) {
        discardOnFinalize = discard
        recording?.stop()
    }

    // The clock + the HARD STOP at exactly 15.0 s.
    val rec = stage as? NoteStage.Recording
    LaunchedEffect(rec) {
        val r = rec ?: return@LaunchedEffect
        while (isActive) {
            withFrameMillis { }
            elapsedMs = SystemClock.elapsedRealtime() - r.startedAt
            if (VideoNote.mustAutoStop(elapsedMs)) {
                elapsedMs = VideoNote.MAX_DURATION_MS
                stopRecording()
                break
            }
        }
    }

    fun close() {
        when (val s = stage) {
            is NoteStage.Recording -> stopRecording(discard = true)
            is NoteStage.Review -> deleteQuietly(s.file)
            else -> {}
        }
        onDismiss()
    }

    // Leaving by any route: nothing plaintext stays behind.
    DisposableEffect(Unit) {
        onDispose {
            discardOnFinalize = true
            runCatching { recording?.stop() }
            deleteQuietly(captureFile)
            (stage as? NoteStage.Review)?.let { deleteQuietly(it.file) }
        }
    }

    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        // __OLED_GLASS_2026_11_16__ OLED black: a black ground, glass disc.
        val glassRecorder = com.oshi.messenger.ui.theme.glassOn()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (glassRecorder) Color.Black else Color.Black.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = ::close,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.video_note_close), tint = Color.White)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when (stage) {
                        is NoteStage.Review -> stringResource(R.string.video_note_review_title)
                        is NoteStage.Encoding -> stringResource(R.string.video_note_preparing)
                        else -> stringResource(R.string.video_note_title)
                    },
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(20.dp))

                // ── The circle ────────────────────────────────────────────────
                val ringProgress = if (stage is NoteStage.Recording) VideoNote.progress(elapsedMs) else 0f
                val circleScale by animateFloatAsState(
                    targetValue = if (stage is NoteStage.Recording && !reduceMotion) 1.03f else 1f,
                    animationSpec = if (reduceMotion) tween(0) else spring(dampingRatio = 0.55f, stiffness = 250f),
                    label = "note_circle"
                )
                Box(
                    modifier = Modifier.size(272.dp).scale(circleScale),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(256.dp)
                            .clip(CircleShape)
                            .background(if (glassRecorder) com.oshi.messenger.ui.theme.OledGlass.BubbleFill else Color(0xFF1B1530))
                            .then(if (glassRecorder) Modifier.glassHairline(CircleShape) else Modifier),
                        contentAlignment = Alignment.Center
                    ) {
                        when (val s = stage) {
                            is NoteStage.Review -> ReviewLoop(s.file)
                            else -> if (hasCamera) {
                                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                            }
                        }
                        if (stage is NoteStage.Encoding) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 6.dp.toPx()
                        val inset = stroke / 2
                        val arc = Size(size.width - stroke, size.height - stroke)
                        drawArc(
                            brush = VideoNoteBrand.ring, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                            topLeft = Offset(inset, inset), size = arc,
                            alpha = if (stage is NoteStage.Recording) 0.22f else 0.95f,
                            style = Stroke(width = stroke)
                        )
                        if (stage is NoteStage.Recording) {
                            drawArc(
                                brush = VideoNoteBrand.ring, startAngle = -90f, sweepAngle = 360f * ringProgress,
                                useCenter = false, topLeft = Offset(inset, inset), size = arc,
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = when (val s = stage) {
                        is NoteStage.Recording -> "${VideoNote.formatClock(elapsedMs)} / ${VideoNote.formatClock(VideoNote.MAX_DURATION_MS)}"
                        is NoteStage.Review -> VideoNote.formatClock(s.durationMs)
                        is NoteStage.Encoding -> ""
                        NoteStage.Idle -> stringResource(R.string.video_note_hint)
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(28.dp))

                // ── Controls ─────────────────────────────────────────────────
                AnimatedContent(
                    targetState = stage is NoteStage.Review,
                    transitionSpec = {
                        if (reduceMotion) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        else (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith fadeOut()
                    },
                    label = "note_controls"
                ) { reviewing ->
                    if (reviewing) {
                        val review = stage as? NoteStage.Review
                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                            RoundAction(Icons.Default.Delete, stringResource(R.string.video_note_discard)) {
                                review?.let { deleteQuietly(it.file) }
                                onDismiss()
                            }
                            RoundAction(Icons.Default.Refresh, stringResource(R.string.video_note_retake)) {
                                review?.let { deleteQuietly(it.file) }
                                stage = NoteStage.Idle
                            }
                            FilledIconButton(
                                onClick = {
                                    val r = review ?: return@FilledIconButton
                                    scope.launch {
                                        val bytes = withContext(Dispatchers.IO) {
                                            try { r.file.readBytes() } finally { r.file.delete() }
                                        }
                                        stage = NoteStage.Idle
                                        onSend(bytes, r.durationMs)
                                        onDismiss()
                                    }
                                },
                                modifier = Modifier.size(64.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = VideoNoteBrand.Purple)
                            ) {
                                Icon(Icons.Default.Send, contentDescription = stringResource(R.string.video_note_send), tint = Color.White)
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(56.dp))
                            ShutterButton(
                                recording = stage is NoteStage.Recording,
                                enabled = videoCapture != null && (stage is NoteStage.Idle || stage is NoteStage.Recording),
                                reduceMotion = reduceMotion,
                                onStart = ::startRecording,
                                onStop = { stopRecording() }
                            )
                            if (stage is NoteStage.Idle) {
                                RoundAction(Icons.Default.FlipCameraAndroid, stringResource(R.string.video_note_flip)) {
                                    useFront = !useFront
                                }
                            } else Spacer(Modifier.width(56.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Tap = start/stop; hold ≥ 400 ms = record while held. TalkBack gets a plain click. */
@Composable
private fun ShutterButton(
    recording: Boolean,
    enabled: Boolean,
    reduceMotion: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val label = stringResource(if (recording) R.string.video_note_stop else R.string.video_note_record)
    val pulse = if (recording && !reduceMotion) {
        val t = rememberInfiniteTransition(label = "shutter")
        t.animateFloat(1f, 0.82f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse").value
    } else 1f
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick(label) { if (!enabled) false else { if (recording) onStop() else onStart(); true } }
            }
            .pointerInput(recording, enabled) {
                detectTapGestures(onPress = {
                    if (!enabled) return@detectTapGestures
                    if (!recording) {
                        val t0 = SystemClock.elapsedRealtime()
                        onStart()
                        val released = tryAwaitRelease()
                        // Held: release stops (and a drag-away cancel still stops, keeping the clip).
                        if (released && SystemClock.elapsedRealtime() - t0 > 400) onStop()
                    } else {
                        tryAwaitRelease()
                        onStop()
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (recording) 34.dp else 60.dp)
                .scale(pulse)
                .clip(if (recording) androidx.compose.foundation.shape.RoundedCornerShape(8.dp) else CircleShape)
                .background(if (recording) Color(0xFFEF4444) else VideoNoteBrand.Purple)
        )
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))
    ) {
        Icon(icon, contentDescription = label, tint = Color.White)
    }
}

/** The encoded note looping inside the circle, with sound, before it is sent. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ReviewLoop(file: File) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx -> android.view.TextureView(ctx) },
        update = { tv -> attachCenterCrop(player, tv) },
        modifier = Modifier.fillMaxSize()
    )
}

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
