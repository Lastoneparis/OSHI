package com.oshi.messenger.ui.components

import com.oshi.messenger.ui.theme.glassHairline
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * (N12, Anatole 13/11) Call-screen buttons in the Google Phone / Material 3
 * Expressive manner: big, rounded, a little thicker than a plain icon button,
 * and a toggle that CHANGES SHAPE when it is on.
 *
 * - Off: a full pill (corner = half the height), translucent white.
 * - On: the corners spring in to a rounded square, filled white — the shape
 *   change is the Expressive cue, so state is legible without relying on
 *   colour alone.
 * - Hang-up / answer: a wide pill rather than a circle, the way the Phone app
 *   draws them, so the destructive action is the largest target on screen.
 *
 * Hand-rolled with `animateDpAsState` rather than the M3 Expressive
 * `ToggleButton`/shape-morph APIs: those are still experimental in the BOM this
 * module pins, and the effect needed here is two corner radii and a spring.
 */
object ExpressiveCallDefaults {
    val ToggleWidth: Dp = 80.dp
    val ToggleHeight: Dp = 64.dp
    val ToggleCornerOff: Dp = 32.dp   // = ToggleHeight / 2 → pill
    val ToggleCornerOn: Dp = 20.dp    // rounded square
    val ToggleIcon: Dp = 28.dp

    val HangUpWidth: Dp = 128.dp
    val HangUpHeight: Dp = 72.dp
    val HangUpIcon: Dp = 32.dp

    val AnswerWidth: Dp = 104.dp
    val AnswerHeight: Dp = 72.dp
}

@Composable
fun ExpressiveCallToggle(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = ExpressiveCallDefaults.ToggleWidth,
    height: Dp = ExpressiveCallDefaults.ToggleHeight,
    activeContent: Color = Color(0xFF5616EC),
    showLabel: Boolean = true,
) {
    val corner by animateDpAsState(
        targetValue = if (isActive) ExpressiveCallDefaults.ToggleCornerOn else height / 2,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "callToggleCorner"
    )
    // __OLED_GLASS_2026_11_16__ OLED black: an idle control is a pane of dark
    // glass with a lit rim instead of a white 18 % wash (which reads as grey
    // fog on a black ground). Active stays solid white — the state must shout.
    val glass = com.oshi.messenger.ui.theme.glassOn()
    val idle = if (glass) com.oshi.messenger.ui.theme.glassFill(com.oshi.messenger.ui.theme.GlassTier.Control)
        else Color.White.copy(alpha = 0.18f)
    val container by animateColorAsState(
        if (isActive) Color.White else idle,
        label = "callToggleContainer"
    )
    val content by animateColorAsState(
        if (isActive) activeContent else Color.White,
        label = "callToggleContent"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(corner),
            color = container,
            contentColor = content,
            // No extra state semantics: every caller already swaps the label
            // itself (Mute ↔ Unmute, Speaker ↔ Speaker on), localized.
            modifier = Modifier
                .size(width = width, height = height)
                .then(
                    if (glass && !isActive) Modifier.glassHairline(RoundedCornerShape(corner))
                    else Modifier
                )
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(ExpressiveCallDefaults.ToggleIcon))
            }
        }
        if (showLabel) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
    }
}

/** The wide pill for hang-up / decline / accept. */
@Composable
fun ExpressiveCallPill(
    icon: ImageVector,
    contentDescription: String,
    containerColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = ExpressiveCallDefaults.HangUpWidth,
    height: Dp = ExpressiveCallDefaults.HangUpHeight,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(height / 2),
        color = containerColor,
        contentColor = Color.White,
        shadowElevation = 4.dp,
        modifier = modifier.size(width = width, height = height)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(ExpressiveCallDefaults.HangUpIcon))
        }
    }
}
