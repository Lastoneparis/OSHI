package com.oshi.messenger.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Swipe-right-to-reply parameters, ported number-for-number from iOS.
 *
 * **Reference**: `OSHI/GroupViews.swift:8615-8666`, `struct SwipeToReply: ViewModifier`,
 * attached at `ChatView.swift:1798` (1:1) and `GroupViews.swift:8607` (groups). Android
 * had NO swipe-to-reply on either surface: a sweep of `ui/` for
 * `detectHorizontalDragGestures` / `draggable` / `anchoredDraggable` / `swipeable` /
 * `SwipeToDismissBox` returned two hits, both the voice-recorder slide-to-cancel.
 *
 * | Behaviour | iOS | here |
 * |---|---|---|
 * | Activation distance | `DragGesture(minimumDistance: 18)` (`:8645`) | [SWIPE_MIN_DISTANCE_DP] |
 * | Horizontal-intent guard | `abs(dx) > abs(dy) * 1.5` (`:8650`) | [HORIZONTAL_INTENT_RATIO] |
 * | Direction | right only, `guard translation.width > 0` (`:8654`) — same for sent and received bubbles, deliberately | negative deltas clamp to 0 |
 * | Rubber-band | `min(width * 0.55, 72)` (`:8655`) | [RUBBER_BAND_FACTOR] / [MAX_OFFSET_DP] |
 * | Commit threshold | **56 pt** (`:8629`) | [COMMIT_THRESHOLD_DP] |
 * | Haptic | fired **ONCE**, at the moment `dx` crosses the threshold, not on release (`:8656-8659`) | `crossed` latch |
 * | Affordance | leading arrow, `opacity = min(abs(dx)/56, 1) * 0.9`, `offset(x: -28)`, `allowsHitTesting(false)` (`:8636-8643`) | drawn behind, never hit-tested |
 * | Release | spring back to 0; fire `onReply()` only if `dx >= 56` (`:8662-8665`) | same |
 *
 * The pure functions here exist so that table is unit-testable without a device
 * (`SwipeToReplyTest`): a threshold that silently drifts is a feel regression nobody
 * notices in review.
 */
object SwipeToReplyDefaults {
    /** iOS `DragGesture(minimumDistance: 18)` — `GroupViews.swift:8645`. */
    const val SWIPE_MIN_DISTANCE_DP = 18f

    /** iOS `abs(dx) > abs(dy) * 1.5` — `GroupViews.swift:8650`. */
    const val HORIZONTAL_INTENT_RATIO = 1.5f

    /** iOS `translation.width * 0.55` — `GroupViews.swift:8655`. */
    const val RUBBER_BAND_FACTOR = 0.55f

    /** iOS `min(…, 72)` — `GroupViews.swift:8655`. */
    const val MAX_OFFSET_DP = 72f

    /** iOS `private let threshold: CGFloat = 56` — `GroupViews.swift:8629`. */
    const val COMMIT_THRESHOLD_DP = 56f

    /** iOS arrow `.offset(x: -28)` — `GroupViews.swift:8641`. */
    const val ARROW_OFFSET_DP = 28f

    /** iOS arrow size, and the `* 0.9` cap on its opacity ramp — `GroupViews.swift:8636-8639`. */
    const val ARROW_SIZE_DP = 20f
    const val ARROW_MAX_ALPHA = 0.9f

    /**
     * Rubber-banded, capped horizontal offset for a raw drag translation.
     *
     * Right-only: a leftward drag yields 0, matching iOS's
     * `guard g.translation.width > 0` rather than mirroring the gesture. Sent and
     * received bubbles swipe the SAME direction on iOS — that is deliberate, not an
     * oversight to "fix".
     */
    fun offsetFor(rawTranslation: Float, maxOffset: Float): Float {
        if (rawTranslation <= 0f) return 0f
        return minOf(rawTranslation * RUBBER_BAND_FACTOR, maxOffset)
    }

    /**
     * Arrow opacity for a given offset: `min(abs(dx) / threshold, 1) * 0.9`
     * (`GroupViews.swift:8639`).
     */
    fun arrowAlphaFor(offset: Float, threshold: Float): Float =
        minOf(abs(offset) / threshold, 1f) * ARROW_MAX_ALPHA

    /**
     * Is this motion a horizontal swipe rather than a list scroll?
     * iOS: `abs(g.translation.width) > abs(g.translation.height) * 1.5`.
     */
    fun isHorizontalIntent(dx: Float, dy: Float): Boolean =
        abs(dx) > abs(dy) * HORIZONTAL_INTENT_RATIO

    /** Does releasing at [offset] commit the reply? iOS fires only at `dx >= 56`. */
    fun commits(offset: Float, threshold: Float): Boolean = offset >= threshold

    /**
     * Should the haptic fire on this frame?
     *
     * The once-on-crossing rule, isolated so it is testable. iOS fires `Haptics.tap()`
     * at the MOMENT `dx` crosses the threshold (`GroupViews.swift:8656-8659`), not on
     * release and not every frame — a haptic that repeats while the finger is held past
     * the threshold feels like the phone is malfunctioning.
     *
     * True only on the rising edge: the finger has to drop back under the threshold
     * before another crossing can fire.
     */
    fun shouldFireHaptic(previousOffset: Float, offset: Float, threshold: Float): Boolean =
        offset >= threshold && previousOffset < threshold
}

/**
 * Attach swipe-right-to-reply to a message bubble.
 *
 * ### Why not `detectHorizontalDragGestures`
 *
 * SwiftUI's `.simultaneousGesture` lets the list keep scrolling while the drag is
 * recognised. Compose has no free equivalent: a naive `pointerInput` that consumes from
 * the first event steals the parent `LazyColumn`'s vertical scroll. This uses
 * `awaitHorizontalTouchSlopOrCancellation` — the primitive `detectHorizontalDragGestures`
 * is itself built on — which yields the pointer to the parent until horizontal slop is
 * exceeded, and additionally refuses the gesture when the first motion past slop is more
 * vertical than horizontal, so a fast flick up or down scrolls the list rather than
 * dragging a bubble sideways.
 *
 * @param enabled false disables the gesture while keeping the modifier chain stable.
 * @param onReply invoked once, on release, only when the drag passed the 56 dp threshold.
 */
fun Modifier.swipeToReply(
    enabled: Boolean = true,
    onReply: () -> Unit
): Modifier = composed {
    if (!enabled) return@composed this

    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScopeCompat()

    val maxOffsetPx = with(density) { SwipeToReplyDefaults.MAX_OFFSET_DP.dp.toPx() }
    val thresholdPx = with(density) { SwipeToReplyDefaults.COMMIT_THRESHOLD_DP.dp.toPx() }
    val minDistancePx = with(density) { SwipeToReplyDefaults.SWIPE_MIN_DISTANCE_DP.dp.toPx() }
    val arrowOffsetPx = with(density) { SwipeToReplyDefaults.ARROW_OFFSET_DP.dp.toPx() }
    val arrowSizePx = with(density) { SwipeToReplyDefaults.ARROW_SIZE_DP.dp.toPx() }

    val offset = androidx.compose.runtime.remember { Animatable(0f) }
    val arrowPainter = rememberVectorPainter(Icons.Filled.Reply)
    val arrowTint: Color = MaterialTheme.colorScheme.onSurfaceVariant

    this
        // The affordance is drawn BEHIND the bubble and is never hit-tested, matching
        // iOS's `.allowsHitTesting(false)` (`GroupViews.swift:8643`).
        .drawBehind {
            val dx = offset.value
            if (dx <= 0f) return@drawBehind
            val alpha = SwipeToReplyDefaults.arrowAlphaFor(dx, thresholdPx)
            if (alpha <= 0f) return@drawBehind
            val left = (dx - arrowOffsetPx - arrowSizePx).coerceAtLeast(0f)
            val top = (size.height - arrowSizePx) / 2f
            translate(left = left, top = top) {
                with(arrowPainter) {
                    draw(
                        size = Size(arrowSizePx, arrowSizePx),
                        alpha = alpha,
                        colorFilter = ColorFilter.tint(arrowTint)
                    )
                }
            }
        }
        .graphicsLayer { translationX = offset.value }
        .pointerInput(enabled, thresholdPx, maxOffsetPx, minDistancePx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)

                var totalDx = 0f
                var totalDy = 0f
                val slopChange: PointerInputChange? =
                    awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        totalDx += over
                        totalDy += change.positionChange().y
                        if (SwipeToReplyDefaults.isHorizontalIntent(totalDx, totalDy) && totalDx > 0f) {
                            change.consume()
                        }
                    }

                if (slopChange == null) return@awaitEachGesture
                if (!SwipeToReplyDefaults.isHorizontalIntent(totalDx, totalDy) || totalDx <= 0f) {
                    return@awaitEachGesture
                }

                var raw = totalDx
                var previousOffset = 0f

                horizontalDrag(down.id) { change ->
                    raw += change.positionChange().x
                    // iOS's minimumDistance: 18 — the bubble does not move before then.
                    val effective = (raw - minDistancePx).coerceAtLeast(0f)
                    val target = SwipeToReplyDefaults.offsetFor(effective, maxOffsetPx)

                    if (SwipeToReplyDefaults.shouldFireHaptic(previousOffset, target, thresholdPx)) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    previousOffset = target

                    scope.launch { offset.snapTo(target) }
                    if (target > 0f) change.consume()
                }

                // Read the last COMPUTED target, not `offset.value`: the Animatable is
                // written from a launched coroutine, so on release it may still be a frame
                // behind the finger — and a commit that depends on that race would drop
                // replies intermittently.
                val committed = SwipeToReplyDefaults.commits(previousOffset, thresholdPx)
                scope.launch {
                    // iOS: spring(response: 0.28, dampingFraction: 0.75).
                    offset.animateTo(
                        0f,
                        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
                    )
                }
                if (committed) onReply()
            }
        }
}

/** Small indirection so the import list stays readable inside `composed { }`. */
@androidx.compose.runtime.Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()
