package com.oshi.messenger.ui.components

import androidx.compose.animation.AnimatedVisibility
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oshi.messenger.R
import com.oshi.messenger.service.AudioPlayerManager
import com.oshi.messenger.service.AudioRecorderManager
import com.oshi.messenger.service.audio.VoiceEffectProcessor.VoiceEffect
import com.oshi.messenger.ui.theme.OSHIOrange
import com.oshi.messenger.ui.theme.OSHIPurple
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * __VOICE_PREVIEW_2026_09_29__ The screen after a voice recording — Android twin of iOS
 * `AudioPreviewSheet` (ChatView.swift). Hear it, try a voice (the chip PLAYS the voice as
 * soon as it is rendered), switch on "view once" with the "1" button next to Send, send.
 *
 * Before this, Android applied the voice chosen on the recording bar inside the send
 * button's click handler — on the main thread, with no way to hear the result first —
 * and had no view-once for voice at all, while iOS could already RECEIVE one.
 */

/** Same accent per voice as iOS, so a voice looks the same on both phones. */
private fun VoiceEffect.accent(): Color = when (this) {
    VoiceEffect.NORMAL -> OSHIPurple
    VoiceEffect.ROBOT -> Color(0xFF22D3EE)
    VoiceEffect.ALIEN -> Color(0xFF34C759)
    VoiceEffect.DEEP -> Color(0xFF5856D6)
    VoiceEffect.CHIPMUNK -> OSHIOrange
    VoiceEffect.ECHO -> Color(0xFFFF2D78)
    VoiceEffect.REVERB -> Color(0xFFAF52DE)
}

private fun VoiceEffect.emoji(): String = when (this) {
    VoiceEffect.NORMAL -> "🎤"
    VoiceEffect.ROBOT -> "🤖"
    VoiceEffect.ALIEN -> "👽"
    VoiceEffect.DEEP -> "🐻"
    VoiceEffect.CHIPMUNK -> "🐿"
    VoiceEffect.ECHO -> "🔊"
    VoiceEffect.REVERB -> "⛪"
}

private fun formatSeconds(seconds: Float): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** A frosted surface: translucent fill + hairline, the Compose stand-in for iOS glass. */
@Composable
private fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape, tint: Color? = null): Modifier {
    val base = MaterialTheme.colorScheme.onSurface
    return this
        .clip(shape)
        .background((tint ?: base).copy(alpha = if (tint != null) 0.16f else 0.06f), shape)
        .border(1.dp, base.copy(alpha = 0.08f), shape)
}

@Composable
private fun PreviewWaveform(
    levels: List<Float>,
    progress: Float,
    tint: Color,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val idle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 1f, targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "b"
    )
    val scale = if (busy) breathe else 1f
    Canvas(modifier) {
        val n = levels.size.coerceAtLeast(1)
        val gap = 3.dp.toPx()
        val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(2f)
        levels.forEachIndexed { i, level ->
            val h = (level * size.height * 0.95f * scale).coerceAtLeast(4.dp.toPx())
            val x = i * (barW + gap)
            drawRoundRect(
                color = if (i.toFloat() / n < progress) tint else idle,
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceMessagePreviewSheet(
    voiceClip: AudioRecorderManager.VoiceClip,
    recorder: AudioRecorderManager,
    player: AudioPlayerManager,
    /** false (groups) hides the "1" button, like iOS. */
    allowViewOnce: Boolean,
    onSend: (bytes: ByteArray, viewOnce: Boolean) -> Unit,
    onDiscard: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // Rendered voices, so switching back and forth never re-renders.
    val rendered = remember { mutableStateMapOf<VoiceEffect, ByteArray>(VoiceEffect.NORMAL to voiceClip.bytes) }
    var chosen by remember { mutableStateOf(VoiceEffect.NORMAL) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<VoiceEffect?>(null) }
    var viewOnce by remember { mutableStateOf(false) }
    var renderJob by remember { mutableStateOf<Job?>(null) }

    val playbackState by player.playbackState.collectAsState()
    val currentId by player.currentMessageId.collectAsState()
    val progress by player.playbackProgress.collectAsState()
    val position by player.currentPosition.collectAsState()
    val speakerOn by player.isSpeakerOn.collectAsState()

    fun idFor(effect: VoiceEffect) = "voice-preview:${effect.name}"
    val playingThis = currentId == idFor(chosen) && playbackState == AudioPlayerManager.PlaybackState.PLAYING

    fun play(effect: VoiceEffect) {
        val bytes = rendered[effect] ?: return
        player.stop()
        player.play(idFor(effect), bytes)
    }

    fun select(effect: VoiceEffect) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        renderJob?.cancel()
        player.stop()
        chosen = effect
        failed = null
        if (rendered.containsKey(effect)) {
            busy = false
            play(effect)
            return
        }
        busy = true
        renderJob = scope.launch {
            val out = recorder.renderEffect(voiceClip.bytes, effect)
            if (out != null) rendered[effect] = out else failed = effect
            if (chosen == effect) {
                busy = false
                if (out != null) play(effect) else chosen = VoiceEffect.NORMAL
            }
        }
    }

    // The voice picked on the recording bar is where the preview starts.
    LaunchedEffect(voiceClip) {
        if (voiceClip.initialEffect != VoiceEffect.NORMAL) select(voiceClip.initialEffect)
    }
    DisposableEffect(Unit) {
        onDispose { renderJob?.cancel(); player.stop() }
    }

    val accent = chosen.accent()
    val accentAnim by animateColorAsState(accent, spring(), label = "accent")

    OshiModalBottomSheet(
        onDismissRequest = onDiscard,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).glass(CircleShape, accentAnim),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.GraphicEq, null, tint = accentAnim, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.group_chat_voice_message),
                        fontWeight = FontWeight.SemiBold, fontSize = 17.sp
                    )
                    val kb = (rendered[chosen] ?: voiceClip.bytes).size / 1000f
                    Text(
                        "${formatSeconds(voiceClip.durationSeconds)} · ${"%.1f".format(kb)} KB",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val speakerLabel = stringResource(R.string.speaker_on)
                val earpieceLabel = stringResource(R.string.speaker_off)
                Box(
                    Modifier
                        .size(40.dp)
                        .glass(CircleShape)
                        .clickable { player.toggleSpeaker() }
                        .semantics {
                            contentDescription = if (speakerOn) speakerLabel else earpieceLabel
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (speakerOn) Icons.Default.VolumeUp else Icons.Default.Hearing,
                        null, modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Player card
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(28.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(58.dp)
                        .shadow(12.dp, CircleShape, ambientColor = accentAnim, spotColor = accentAnim)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(accentAnim, accentAnim.copy(alpha = 0.7f))))
                        .clickable {
                            when {
                                busy -> { renderJob?.cancel(); busy = false; chosen = VoiceEffect.NORMAL }
                                playingThis -> player.pause()
                                currentId == idFor(chosen) &&
                                    playbackState == AudioPlayerManager.PlaybackState.PAUSED -> player.resume()
                                else -> play(chosen)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (busy) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                    } else {
                        Icon(
                            if (playingThis) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp)
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    val shownProgress = if (currentId == idFor(chosen)) progress else 0f
                    PreviewWaveform(
                        levels = voiceClip.waveform,
                        progress = shownProgress,
                        tint = accentAnim,
                        busy = busy,
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Text(
                            when {
                                busy -> stringResource(R.string.voice_preview_processing, chosen.displayName)
                                failed != null -> stringResource(R.string.voice_preview_failed, failed!!.displayName)
                                chosen == VoiceEffect.NORMAL -> stringResource(R.string.voice_preview_original)
                                else -> chosen.displayName
                            },
                            fontSize = 12.sp,
                            color = if (busy) accentAnim else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (playingThis) formatSeconds(position / 1000f) else formatSeconds(voiceClip.durationSeconds),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Voice chips
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.voice_preview_effect),
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(VoiceEffect.entries) { effect ->
                        val isSel = effect == chosen
                        val chipScale by animateFloatAsState(if (isSel) 1.05f else 1f, spring(), label = "chip")
                        Row(
                            Modifier
                                .scale(chipScale)
                                .clip(RoundedCornerShape(50))
                                .then(
                                    if (isSel) Modifier.background(
                                        Brush.linearGradient(listOf(effect.accent(), effect.accent().copy(alpha = 0.75f)))
                                    ) else Modifier.glass(RoundedCornerShape(50))
                                )
                                .clickable { select(effect) }
                                .semantics { selected = isSel }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(effect.emoji(), fontSize = 14.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                effect.displayName,
                                fontSize = 14.sp,
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                            if (isSel && busy) {
                                Spacer(Modifier.width(6.dp))
                                CircularProgressIndicator(
                                    color = Color.White, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            // View-once explanation
            AnimatedVisibility(viewOnce, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), OSHIOrange).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OneBadge(filled = true, size = 26)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(stringResource(R.string.media_view_once), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            stringResource(R.string.voice_preview_view_once_hint),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Actions
            val discardLabel = stringResource(R.string.cancel)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(54.dp)
                        .glass(CircleShape)
                        .clickable { player.stop(); onDiscard() }
                        .semantics { contentDescription = discardLabel },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                if (allowViewOnce) {
                    Box(
                        Modifier
                            .size(54.dp)
                            .glass(CircleShape, if (viewOnce) OSHIOrange else null)
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewOnce = !viewOnce
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        OneBadge(filled = viewOnce, size = 28)
                    }
                    Spacer(Modifier.width(14.dp))
                }
                val sendLabel = stringResource(R.string.chat_send)
                Row(
                    Modifier
                        .height(54.dp)
                        .shadow(12.dp, RoundedCornerShape(50), ambientColor = OSHIPurple, spotColor = OSHIPurple)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(OSHIPurple, OSHIPurple.copy(alpha = 0.78f))))
                        .clickable(enabled = !busy) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            player.stop()
                            // Only a voice that actually rendered can be sent under its name;
                            // `busy` blocks the button until it has.
                            onSend(rendered[chosen] ?: voiceClip.bytes, viewOnce && allowViewOnce)
                        }
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (busy) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(Icons.Default.ArrowUpward, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(sendLabel, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
            }
        }
    }
}

/** The "1 in a circle" view-once mark, drawn so it matches iOS `1.circle(.fill)`. */
@Composable
fun OneBadge(filled: Boolean, size: Int, color: Color = OSHIOrange) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (filled) drawCircle(color)
            else drawCircle(ink, style = Stroke(width = 2.dp.toPx()))
        }
        Text(
            "1",
            fontSize = (size * 0.55f).sp,
            fontWeight = FontWeight.Black,
            color = if (filled) Color.White else ink
        )
    }
}

// ============================================================================
// VIEW-ONCE VOICE — receive side (iOS `viewOnceVoicePill` + `ViewOnceAudioPlayer`)
// ============================================================================

/** Bubble for a view-once voice note: sender sees what it is, receiver taps to play once. */
@Composable
fun ViewOnceVoicePill(
    isFromMe: Boolean,
    opened: Boolean,
    onTap: () -> Unit,
) {
    Row(
        Modifier
            .width(250.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(OSHIOrange.copy(alpha = 0.12f))
            .clickable(enabled = !isFromMe && !opened, onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OneBadge(filled = !opened, size = 38, color = if (opened) Color.Gray else OSHIOrange)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.group_chat_voice_message), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                when {
                    opened -> stringResource(R.string.media_view_once_opened)
                    isFromMe -> stringResource(R.string.media_view_once)
                    else -> stringResource(R.string.media_view_once_tap)
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!isFromMe && !opened) {
            Icon(Icons.Default.PlayArrow, null, tint = OSHIOrange, modifier = Modifier.size(28.dp))
        }
    }
}

/**
 * Full-screen, one-shot player. Starts on open, closes itself at the end; [onConsumed]
 * runs exactly once, whether it ended or the person closed it early (a mis-tap still
 * burns it — the iPhone does the same).
 */
@Composable
fun ViewOnceVoiceDialog(
    messageId: String,
    audio: ByteArray,
    player: AudioPlayerManager,
    onConsumed: () -> Unit,
) {
    val id = "view-once:$messageId"
    var consumed by remember { mutableStateOf(false) }
    fun finish() {
        if (consumed) return
        consumed = true
        player.stop()
        onConsumed()
    }
    val state by player.playbackState.collectAsState()
    val current by player.currentMessageId.collectAsState()
    val progress by player.playbackProgress.collectAsState()
    val position by player.currentPosition.collectAsState()
    val duration by player.playbackDuration.collectAsState()

    LaunchedEffect(Unit) { player.stop(); player.play(id, audio) }
    LaunchedEffect(state, current) {
        if (current == id && (state == AudioPlayerManager.PlaybackState.STOPPED ||
                state == AudioPlayerManager.PlaybackState.ERROR)) finish()
    }

    Dialog(
        onDismissRequest = { finish() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
                Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Color.White.copy(alpha = 0.12f), style = Stroke(6.dp.toPx()))
                        drawArc(
                            brush = Brush.sweepGradient(listOf(OSHIOrange, Color(0xFFFF2D78), OSHIOrange)),
                            startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                            style = Stroke(6.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    Icon(Icons.Default.GraphicEq, null, tint = OSHIOrange, modifier = Modifier.size(58.dp))
                }
                val remaining = ((duration - position).coerceAtLeast(0)) / 1000f
                Text(formatSeconds(remaining), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OneBadge(filled = true, size = 18)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.voice_preview_listening), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
                TextButton(onClick = { finish() }) {
                    Text(stringResource(R.string.cancel), color = Color.White.copy(alpha = 0.8f))
                }
            }
        }
    }
}
