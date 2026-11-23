package com.oshi.messenger.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * (N15) "Avoir un système de glissement comme Instagram ou Google Messages
 * pour voir l'heure d'envoi sans qu'elle prenne de place sur l'écran, et le
 * témoin de couleur à côté pour savoir le type d'envoi."
 *
 * Gesture-only — it reports progress in [onProgressChanged] (0 at rest, up
 * to 1 at [SwipeToReplyDefaults.MAX_OFFSET_DP] of leftward drag — already
 * sign-flipped to match [RevealedTimestampChip]'s convention, so the call
 * site never has to remember which direction is positive) and renders
 * nothing itself. [RevealedTimestampChip] renders the
 * chip; the call site composes them as siblings in a `Box`. Splitting it
 * this way — rather than one modifier that both drags AND draws, as the
 * reply arrow does — is deliberate: that arrow is one vector icon drawable
 * straight into a [androidx.compose.ui.graphics.drawscope.DrawScope], but
 * this needs to lay out real text next to a dot, which a raw `drawBehind`
 * cannot host.
 *
 * Deliberately the mirror image of [swipeToReply]'s direction split: that
 * one only ever acts on `totalDx > 0` (rightward), this one only ever acts
 * on `totalDx < 0` (leftward). A single drag is one or the other, never
 * both, so the two gesture detectors attached to the same bubble never
 * contend for the same motion — whichever direction a drag takes, exactly
 * one of them passes its own direction check and proceeds past touch-slop;
 * the other returns before ever calling `horizontalDrag`, having consumed
 * nothing of that motion.
 */
fun Modifier.swipeToRevealTimestamp(
    enabled: Boolean = true,
    onProgressChanged: (Float) -> Unit
): Modifier = composed {
    if (!enabled) return@composed this

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val maxOffsetPx = with(density) { SwipeToReplyDefaults.MAX_OFFSET_DP.dp.toPx() }
    val minDistancePx = with(density) { SwipeToReplyDefaults.SWIPE_MIN_DISTANCE_DP.dp.toPx() }
    val offset = remember { Animatable(0f) }

    this.pointerInput(enabled, maxOffsetPx, minDistancePx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)

            var totalDx = 0f
            var totalDy = 0f
            val slopChange: PointerInputChange? =
                awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                    totalDx += over
                    totalDy += change.positionChange().y
                    if (SwipeToReplyDefaults.isHorizontalIntent(totalDx, totalDy) && totalDx < 0f) {
                        change.consume()
                    }
                }

            if (slopChange == null) return@awaitEachGesture
            if (!SwipeToReplyDefaults.isHorizontalIntent(totalDx, totalDy) || totalDx >= 0f) {
                return@awaitEachGesture
            }

            var raw = totalDx

            horizontalDrag(down.id) { change ->
                raw += change.positionChange().x
                // Mirror image of SwipeToReplyDefaults.offsetFor, leftward only.
                val effective = (raw + minDistancePx).coerceAtMost(0f)
                val target = (effective * SwipeToReplyDefaults.RUBBER_BAND_FACTOR).coerceAtLeast(-maxOffsetPx)
                scope.launch {
                    offset.snapTo(target)
                    onProgressChanged(-target / maxOffsetPx)
                }
                if (target < 0f) change.consume()
            }

            scope.launch {
                offset.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium))
                onProgressChanged(0f)
            }
        }
    }
}

/**
 * The revealed chip: time label + the transport dot, only as opaque as the
 * drag has gone — a glance mid-swipe sees it fading in, not snapping in.
 * [progress] is 0 at rest and 1 at full reveal (the negated, normalised
 * value [swipeToRevealTimestamp] reports).
 */
@Composable
fun RevealedTimestampChip(
    progress: Float,
    label: String,
    dotColor: Color,
    modifier: Modifier = Modifier
) {
    if (progress <= 0f) return
    Box(modifier = modifier.alpha(progress.coerceIn(0f, 1f)), contentAlignment = Alignment.CenterEnd) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(percent = 50))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(modifier = Modifier.size(7.dp).background(dotColor, CircleShape))
                Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
