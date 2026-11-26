package com.oshi.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oshi.messenger.ui.theme.GlassTier
import com.oshi.messenger.ui.theme.glassFill
import com.oshi.messenger.ui.theme.glassGlow
import com.oshi.messenger.ui.theme.glassHairline
import com.oshi.messenger.ui.theme.glassLift
import com.oshi.messenger.ui.theme.thenIf

/**
 * __COMPOSER_PILL_2026_11_23__ The chat composer drawn like the Google app's search bar
 * (user request, 23/11: "une barre similaire pour les conversations"): ONE rounded pill
 * that holds "+", the text, and the camera, with the mic / send button as a separate
 * circle of the same height beside it.
 *
 * Before, "+", the field (its own outlined capsule) and the camera were three loose
 * items on the bar. Now the pill is the field: the inner [OutlinedTextField] is made
 * borderless and transparent with [composerPillFieldColors], and the pill carries the
 * fill and the outline — which turns brand-coloured while the field has focus, the cue
 * the outlined field used to give.
 *
 * Glass themes keep their treatment (lift + hairline + glass fill) on the pill instead of
 * on the field. The shortcuts inside still step aside while typing ([ComposerShortcut]).
 */
object ComposerPillDefaults {
    val Height: Dp = 56.dp // = OutlinedTextField min height, so pill and button line up
    val Shape = RoundedCornerShape(28.dp)
}

@Composable
fun ComposerPill(
    glass: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = ComposerPillDefaults.Shape
    var focused by remember { mutableStateOf(false) }
    val fill = if (glass) glassFill(GlassTier.Chip) else MaterialTheme.colorScheme.surfaceVariant
    val outline = when {
        focused -> MaterialTheme.colorScheme.primary
        glass -> Color.Transparent
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    }
    Row(
        modifier = modifier
            .heightIn(min = ComposerPillDefaults.Height)
            .thenIf(glass) { glassLift(shape, spread = 6.dp).glassHairline(shape) }
            .clip(shape)
            .background(fill)
            .border(1.dp, outline, shape)
            .onFocusChanged { focused = it.hasFocus }
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The text field inside [ComposerPill]: no border, no fill of its own. */
@Composable
fun composerPillFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.primary,
)

/**
 * The round button beside the pill: mic when the field is empty, send when it is not.
 * [primary] = the brand-filled send; otherwise a quiet tonal circle like the Google bar's
 * voice button.
 */
@Composable
fun ComposerRoundButton(
    onClick: () -> Unit,
    primary: Boolean,
    glass: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val container = when {
        primary -> MaterialTheme.colorScheme.primary
        glass -> glassFill(GlassTier.Chip)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val ink = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        contentColor = ink,
        border = if (!primary && !glass) androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        ) else null,
        modifier = modifier
            .size(ComposerPillDefaults.Height)
            .thenIf(glass && primary) { glassGlow() }
            .thenIf(glass && !primary) { glassHairline(CircleShape) },
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
