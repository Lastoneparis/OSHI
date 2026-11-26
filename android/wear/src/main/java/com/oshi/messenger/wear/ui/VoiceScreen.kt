package com.oshi.messenger.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Text
import com.oshi.messenger.wear.R
import com.oshi.messenger.wear.data.Reason
import com.oshi.messenger.wear.data.SendState
import com.oshi.messenger.wear.data.VoiceRecorder
import com.oshi.messenger.wear.data.WearSession
import com.oshi.wearbridge.WearConvKind
import com.oshi.wearbridge.WearProtocol
import kotlinx.coroutines.delay

/** What the voice screen is doing. */
internal enum class VoicePhase { ASKING, DENIED, RECORDING, TOO_SHORT, FAILED_MIC }

/**
 * __WEAR_POLISH_2026_11_25__ Record a voice message on the wrist, as WhatsApp
 * and Google Messages do: it starts recording as soon as the screen opens
 * (one tap from the reply screen), a ring fills toward the 60 s cap, tap the
 * big button to SEND, the small one to throw it away. The watch encrypts
 * nothing: the m4a goes to the phone, which sends it through its own
 * voice-message path, end-to-end encrypted. The recording file lives only
 * while recording and is deleted at once ([VoiceRecorder]); leaving the
 * screen (wrist down, swipe back) discards it.
 */
@Composable
fun VoiceScreen(session: WearSession, kind: WearConvKind, id: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val sendState by session.send.collectAsState()
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    var phase by remember {
        mutableStateOf(
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                VoicePhase.RECORDING else VoicePhase.ASKING
        )
    }
    val haptics = LocalHapticFeedback.current

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        session.awaitingInput = false
        phase = if (granted) VoicePhase.RECORDING else VoicePhase.DENIED
    }
    LaunchedEffect(Unit) {
        if (phase == VoicePhase.ASKING) {
            // The system dialog stops this activity; it is still the person replying.
            session.awaitingInput = true
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Never record behind the person's back: leaving the screen throws the note away.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && !session.awaitingInput && recorder.isRecording) {
                recorder.cancel()
                onDone()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); recorder.cancel(); session.resetSend() }
    }

    // Screen stays on while the mic is open (a dimmed screen would hide the timer).
    val view = LocalView.current
    DisposableEffect(phase) {
        view.keepScreenOn = phase == VoicePhase.RECORDING
        onDispose { view.keepScreenOn = false }
    }

    var elapsed by remember { mutableLongStateOf(0L) }
    val send = {
        val note = recorder.stop()
        if (note == null) {
            phase = VoicePhase.TOO_SHORT
        } else {
            session.sendVoice(kind, id, note.first, note.second)
        }
    }
    LaunchedEffect(phase) {
        if (phase != VoicePhase.RECORDING) return@LaunchedEffect
        if (!recorder.isRecording && !recorder.start()) { phase = VoicePhase.FAILED_MIC; return@LaunchedEffect }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        while (recorder.isRecording) {
            elapsed = recorder.elapsedMs()
            if (elapsed >= WearProtocol.MAX_VOICE_MS - 250) { send(); break }
            delay(100)
        }
    }
    LaunchedEffect(sendState) {
        when (sendState) {
            is SendState.Sent -> { haptics.performHapticFeedback(HapticFeedbackType.Confirm); delay(700); onDone() }
            is SendState.Failed -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            else -> Unit
        }
    }

    when (val s = sendState) {
        is SendState.Sending -> { Loading(); return }
        is SendState.Sent -> { Confirmation(stringResource(R.string.sent)); return }
        is SendState.Failed -> {
            if (s.reason == Reason.ERROR || s.reason == Reason.NO_PHONE) {
                VoiceMessagePanel(stringResource(R.string.voice_not_sent), retry = null, onClose = onDone)
            } else {
                StatusPanel(s.reason) { session.resetSend(); onDone() }
            }
            return
        }
        else -> Unit
    }

    when (phase) {
        VoicePhase.ASKING -> Loading()
        VoicePhase.DENIED -> VoiceMessagePanel(stringResource(R.string.voice_permission), retry = {
            session.awaitingInput = true
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }, onClose = onDone)
        VoicePhase.FAILED_MIC -> VoiceMessagePanel(stringResource(R.string.voice_mic_busy), retry = { phase = VoicePhase.RECORDING }, onClose = onDone)
        VoicePhase.TOO_SHORT -> VoiceMessagePanel(stringResource(R.string.voice_too_short), retry = { elapsed = 0; phase = VoicePhase.RECORDING }, onClose = onDone)
        VoicePhase.RECORDING -> RecordingFace(
            elapsedMs = elapsed,
            title = session.conversation(kind, id)?.name.orEmpty(),
            onSend = send,
            onCancel = { recorder.cancel(); onDone() },
        )
    }
}

/** The recording face: a ring filling toward 60 s around one big send button; cancel below. */
@Composable
internal fun RecordingFace(elapsedMs: Long, title: String, onSend: () -> Unit, onCancel: () -> Unit) {
    val progress = (elapsedMs.toFloat() / WearProtocol.MAX_VOICE_MS).coerceIn(0f, 1f)
    val sendLabel = stringResource(R.string.voice_send)
    // No clock on this face: the recording timer IS the time that matters here, and the
    // system clock would sit on top of it on a small screen at the largest font.
    androidx.wear.compose.material3.ScreenScaffold(timeText = {}) { RecordingFaceContent(progress, elapsedMs, title, sendLabel, onSend, onCancel) }
}

@Composable
private fun RecordingFaceContent(
    progress: Float, elapsedMs: Long, title: String, sendLabel: String, onSend: () -> Unit, onCancel: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The 60 s budget as an arc along the bezel, the Wear way of showing a countdown.
        Canvas(Modifier.fillMaxSize().padding(3.dp)) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.10f), -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(OshiColors.PurpleLight, -90f, 360f * progress, false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(OshiColors.Error))
                Spacer(Modifier.size(5.dp))
                Text(
                    clock((elapsedMs / 1000).toInt()), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = OshiColors.TextPrimary,
                )
            }
            if (title.isNotBlank()) {
                Text(
                    title, fontSize = 11.sp, color = OshiColors.TextSecondary, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 36.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .size(64.dp)
                    .glassGlow(CircleShape, spread = 8.dp)
                    .glass(CircleShape, OshiColors.Purple)
                    .clickable(role = Role.Button, onClick = onSend)
                    .semantics { contentDescription = sendLabel },
                contentAlignment = Alignment.Center,
            ) {
                // Send arrow.
                Canvas(Modifier.size(26.dp)) {
                    val w = size.width
                    val st = Stroke(w * 0.13f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.5f, w * 0.86f); lineTo(w * 0.5f, w * 0.16f)
                        moveTo(w * 0.2f, w * 0.44f); lineTo(w * 0.5f, w * 0.14f); lineTo(w * 0.8f, w * 0.44f)
                    }
                    drawPath(p, Color.White, style = st)
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .size(width = 96.dp, height = 48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.voice_cancel), fontSize = 13.sp, color = OshiColors.TextSecondary) }
        }
    }
}

/** One sentence and what to do about it, scrollable with its indicator (quality guideline). */
@Composable
private fun VoiceMessagePanel(text: String, retry: (() -> Unit)?, onClose: () -> Unit) {
    val listState = androidx.wear.compose.foundation.lazy.rememberScalingLazyListState()
    androidx.wear.compose.material3.ScreenScaffold(scrollState = listState) { padding ->
        androidx.wear.compose.foundation.lazy.ScalingLazyColumn(
            state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize(),
        ) {
            item { MicGlyph(28.dp, OshiColors.BrandInk) }
            item {
                Text(
                    text, fontSize = 13.sp, textAlign = TextAlign.Center, color = OshiColors.TextPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (retry != null) {
                item {
                    Button(
                        onClick = retry,
                        colors = ButtonDefaults.buttonColors(containerColor = OshiColors.Purple, contentColor = Color.White),
                    ) { Text(stringResource(R.string.retry)) }
                }
            }
            item {
                Button(onClick = onClose, colors = ButtonDefaults.filledTonalButtonColors()) {
                    Text(stringResource(R.string.voice_cancel))
                }
            }
        }
    }
}
