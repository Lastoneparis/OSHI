package com.oshi.messenger.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oshi.messenger.R
import com.oshi.messenger.service.AudioPlayerManager
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.theme.brandInk
import dagger.hilt.android.EntryPointAccessors

/**
 * __VOICE_MINIPLAYER_2026_10_09__ The compact player that follows a playing voice note
 * out of its conversation.
 *
 * ## What it is for
 *
 * [AudioPlayerManager] is an application-scoped singleton, so a voice note has always
 * kept playing on Android when the user left the thread or switched tab (the iOS defect
 * this pairs with was a per-bubble player calling `stop()` from `onDisappear` — see that
 * class's header). What was missing is any way to *control* it from there: the pause
 * button, the waveform and the speed control all left the screen with the conversation,
 * and the only way back was to find the thread again.
 *
 * ## The rules it obeys, in the owner's words
 *
 * Minimalist, adapts to every view and screen, never in the way of the buttons
 * underneath, and it goes away by itself when the note ends or when the ✕ is tapped.
 *
 *  - **never in the way**: hosted once at the Compose root INSIDE the Scaffold's content
 *    area, so it floats above the bottom navigation rather than over it, and only the
 *    pill itself takes touches.
 *  - **not a duplicate**: hidden while the bubble that owns the note is on screen — that
 *    bubble already draws play/pause, a waveform and the speed control
 *    ([AudioPlayerManager.showsMiniPlayer], which is where that decision lives and is
 *    unit-tested).
 *  - **goes away by itself**: it is bound to `PLAYING`/`PAUSED`. A note that reaches its
 *    end moves to `STOPPED` and the pill leaves with no one having to dismiss it.
 *
 * Mirrors `OSHI/VoiceNoteMiniPlayerView.swift`: same controls, same order — play/pause,
 * title, scrub line, remaining time, replay, 1x/1.5x/2x, ✕.
 *
 * Self-contained like [ReviewDeliveryEffect]: it reaches the singleton itself, so hosting
 * it costs one line, and it renders nothing at all while no note is loaded.
 */
@Composable
fun VoiceNoteMiniPlayer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            VoiceNoteMiniPlayerEntryPoint::class.java
        ).audioPlayer()
    }

    val currentId by player.currentMessageId.collectAsState()
    val state by player.playbackState.collectAsState()
    val progress by player.playbackProgress.collectAsState()
    val position by player.currentPosition.collectAsState()
    val duration by player.playbackDuration.collectAsState()
    val speed by player.playbackSpeed.collectAsState()
    val visibleOwners by player.visibleOwners.collectAsState()
    val dismissed by player.miniPlayerDismissed.collectAsState()
    val title by player.currentTitle.collectAsState()

    val isPlaying = state == AudioPlayerManager.PlaybackState.PLAYING
    val visible = AudioPlayerManager.showsMiniPlayer(
        currentId = currentId,
        title = title,
        isLive = isPlaying || state == AudioPlayerManager.PlaybackState.PAUSED,
        visibleOwners = visibleOwners,
        dismissed = dismissed,
    )

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = Oshi.Radius.pillShape,
            // `surface` + a tonal step, not `surfaceContainerHigh`: the project is on
            // Compose BOM 2023.10.01 (material3 1.1.x), where the container roles do
            // not exist yet.
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                // Caps the width on a tablet or a desktop window, where a
                // full-width pill would read as a broken toolbar.
                .widthIn(max = 520.dp)
                .padding(horizontal = Oshi.Space.md, vertical = Oshi.Space.sm)
                .semantics { contentDescription = context.getString(R.string.audio_message) },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = Oshi.Space.sm, end = Oshi.Space.xs)
            ) {
                // ── Play / pause ───────────────────────────────────────────────
                IconButton(
                    onClick = { if (isPlaying) player.pause() else player.resume() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = stringResource(
                                if (isPlaying) R.string.chat_pause else R.string.chat_play
                            ),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.width(Oshi.Space.sm))

                // ── Title + scrub line ─────────────────────────────────────────
                //
                // A 3 dp line rather than a Slider: a Slider's thumb is as tall as
                // the whole pill and would fight the capsule for space.
                Box(modifier = Modifier.weight(1f, fill = true)) {
                    androidx.compose.foundation.layout.Column(
                        verticalArrangement = Arrangement.spacedBy(Oshi.Space.xs)
                    ) {
                        Text(
                            text = title ?: stringResource(R.string.audio_message),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        ScrubLine(
                            progress = progress,
                            onSeek = { player.seekTo(it) },
                        )
                    }
                }

                Spacer(Modifier.width(Oshi.Space.sm))

                // ── Remaining ──────────────────────────────────────────────────
                //
                // Fixed width: the label must not shuffle the layout every tenth of
                // a second as the digits change.
                Text(
                    text = remainingLabel(position, duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(38.dp)
                )

                // ── Replay ─────────────────────────────────────────────────────
                IconButton(onClick = { player.replay() }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Replay,
                        contentDescription = stringResource(R.string.audio_replay),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // ── Speed, 1x → 1.5x → 2x ──────────────────────────────────────
                //
                // Same singleton and the same persisted key as the in-bubble control,
                // so the rate the user picked in the thread is the rate the pill shows.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = 36.dp, height = 36.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { player.cyclePlaybackSpeed() }
                        }
                ) {
                    Text(
                        text = stringResource(speedLabelRes(speed)),
                        style = MaterialTheme.typography.labelSmall,
                        color = brandInk(),
                    )
                }

                // ── Close ──────────────────────────────────────────────────────
                IconButton(
                    onClick = { player.closeMiniPlayer() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/** The scrub line: a filled track, draggable and tappable along its whole width. */
@Composable
private fun ScrubLine(progress: Float, onSeek: (Float) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp),            // a 3 dp line in a finger-sized touch strip
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .pointerInput(widthPx) {
                    detectTapGestures { offset -> onSeek((offset.x / widthPx).coerceIn(0f, 1f)) }
                }
                .pointerInput(widthPx) {
                    detectHorizontalDragGestures { change, _ ->
                        onSeek((change.position.x / widthPx).coerceIn(0f, 1f))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(Oshi.Radius.pillShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(3.dp)
                    .clip(Oshi.Radius.pillShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/** `m:ss` still to go. iOS `VoiceNoteMiniPlayerView.remainingLabel`. */
internal fun remainingLabel(positionMs: Int, durationMs: Int): String {
    val left = ((durationMs - positionMs).coerceAtLeast(0) + 500) / 1000
    return "%d:%02d".format(left / 60, left % 60)
}

/** The existing in-bubble speed labels, so the two controls cannot disagree. */
internal fun speedLabelRes(speed: Float): Int = when {
    speed >= 2.0f -> R.string.chat_voice_speed_2x
    speed >= 1.5f -> R.string.chat_voice_speed_1_5x
    else -> R.string.chat_voice_speed_1x
}

/** Hilt entry point: the mini player reaches the singleton without a ViewModel. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface VoiceNoteMiniPlayerEntryPoint {
    fun audioPlayer(): AudioPlayerManager
}
