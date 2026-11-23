package com.oshi.messenger.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize
import com.oshi.messenger.ui.media.rememberReduceMotion

/**
 * __VIDEO_NOTE_2026_09_24__ A composer shortcut (camera / video note) that steps aside while
 * the user types (owner request: "when someone starts typing, slightly enlarge the box with
 * a great effect, hide the camera and video symbols").
 *
 * The icon shrinks horizontally + scales + fades on a soft spring, so the text field — the
 * row's weighted child — grows smoothly into the freed width instead of jumping; clearing
 * the field springs them back. With "Remove animations" on, the change is instant.
 * `expandFrom`/`shrinkTowards` use `Alignment.CenterHorizontally`, which is symmetric, so
 * the motion reads the same in RTL.
 */
@Composable
fun RowScope.ComposerShortcut(visible: Boolean, content: @Composable () -> Unit) {
    val reduceMotion = rememberReduceMotion()
    val sizeSpring = spring<IntSize>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
    val enter: EnterTransition = if (reduceMotion) EnterTransition.None else
        expandHorizontally(sizeSpring, expandFrom = Alignment.CenterHorizontally) +
            scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), initialScale = 0.4f) +
            fadeIn()
    val exit: ExitTransition = if (reduceMotion) ExitTransition.None else
        shrinkHorizontally(sizeSpring, shrinkTowards = Alignment.CenterHorizontally) +
            scaleOut(targetScale = 0.4f) +
            fadeOut()
    AnimatedVisibility(visible = visible, enter = enter, exit = exit) { content() }
}
