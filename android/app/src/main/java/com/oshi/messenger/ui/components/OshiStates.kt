package com.oshi.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.OSHIRed
import com.oshi.messenger.ui.theme.Oshi

/**
 * The two states an app is judged on and usually does not have: nothing here
 * yet, and something went wrong.
 *
 * WHY THEY LIVE IN ONE FILE
 * -------------------------
 * The audit found the 1:1 chat thread with a fully designed empty state — a
 * glyph in a tinted disc, a `titleMedium` semibold line, a `bodyMedium`
 * subtitle — and the GROUP thread, three metres away in the same app, with a
 * 64 dp icon at 50 % alpha and two paragraphs of default body text. Neither is
 * wrong on its own; together they say the app was assembled rather than
 * designed. The forward picker and the "no search results" state had no design
 * at all — one centred `Text`.
 *
 * The same happened to errors. `KeyBackupScreen` renders a failure as
 * `Text(error, color = OSHIRed)` and a success as `Text(msg, color = OSHIGreen)`
 * — the SAME shape, **distinguished by hue alone**, which is exactly what a
 * red/green colour-blind user cannot do, and what a monochrome screenshot
 * cannot show either. [OshiInlineMessage] gives each tone its own GLYPH as well
 * as its own colour, so the distinction survives both.
 */

/**
 * "There is nothing here yet."
 *
 * @param icon the one glyph. Drawn on `onSurface`, not on the brand: the disc
 *   behind it already carries the brand tint, and brand ink on a brand tint is
 *   the bug this app has now shipped four times (see `Contrast.kt`).
 * @param action optional. Include it only when there is genuinely something the
 *   user can do from here — an empty state with a button that just explains the
 *   emptiness again is worse than no button.
 */
@Composable
fun OshiEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp),
    actionLabel: String? = null,
    action: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(OSHIPurple.copy(alpha = Oshi.ContainerTintAlpha)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                // The title says what the icon says. A screen reader that
                // announces both reads the same thing twice.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.height(Oshi.Space.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (action != null && !actionLabel.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(Oshi.Space.sm))
            // A TextButton is 48 dp tall by default in Material 3, so the touch
            // target is already the minimum without a modifier that could be
            // dropped in a later edit.
            TextButton(onClick = action) { Text(actionLabel) }
        }
    }
}

/** What an [OshiInlineMessage] is telling you. Each tone carries its own glyph. */
enum class InlineTone { Error, Success, Info }

/**
 * A designed inline result row — an error, a confirmation, a note.
 *
 * Replaces the `Text(error, color = OSHIRed)` shape. Three things it adds, all
 * load-bearing:
 *
 * 1. **A glyph per tone.** Colour alone is not a distinction (WCAG 1.4.1). It is
 *    also not a distinction on a greyscale screenshot, which is how half of
 *    this app's bugs get reported.
 * 2. **A tinted container**, so the row reads as a discrete result rather than
 *    as another paragraph of the form it interrupts.
 * 3. **`liveRegion`-style semantics**: the row is announced when it appears,
 *    because a failure the user cannot see (they are looking at the keyboard)
 *    and cannot hear is a failure they will retry blindly.
 *
 * @param message ALREADY LOCALISED. This composable takes a `String`, not a
 *   resource id, only so a formatted message can be passed; every caller must
 *   still come from `stringResource`.
 */
@Composable
fun OshiInlineMessage(
    message: String,
    tone: InlineTone,
    modifier: Modifier = Modifier
) {
    if (message.isBlank()) return
    val accent = when (tone) {
        InlineTone.Error -> OSHIRed
        InlineTone.Success -> OSHIGreen
        InlineTone.Info -> MaterialTheme.colorScheme.primary
    }
    val glyph = when (tone) {
        InlineTone.Error -> Icons.Default.ErrorOutline
        InlineTone.Success -> Icons.Default.CheckCircle
        InlineTone.Info -> Icons.Default.Info
    }
    Surface(
        // 0.12 of a SATURATED red/green over either scheme still reads as a
        // container, and the ink on it is `onSurface`, never the accent — the
        // accent is the glyph and the edge, which is where a tint may be a tint.
        color = accent.copy(alpha = 0.12f),
        shape = Oshi.Radius.mdShape,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = message }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Oshi.Space.md, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                glyph,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .size(18.dp)
                    // Optically align the glyph with the first line of text
                    // rather than with the box.
                    .padding(top = 1.dp)
            )
            Spacer(modifier = Modifier.width(Oshi.Space.sm))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                // Not `accent`: red text on a red wash is the same failure as
                // brand on brand, and this row exists to be read.
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    // The Surface already announces the message; letting the
                    // Text announce it as well reads it twice.
                    .clearAndSetSemantics { }
            )
        }
    }
}

/**
 * A loading state that says what it is waiting for.
 *
 * The audit found nine bare spinners in this territory — including the one on
 * the QR screen, which is the whole screen for as long as the bitmap takes and
 * says nothing. A spinner with no label is indistinguishable from a hang, and
 * the user's response to a hang is to leave.
 */
@Composable
fun OshiLoading(
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = tint,
            strokeWidth = 3.dp,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(Oshi.Space.md))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
