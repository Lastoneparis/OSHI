package com.oshi.messenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.OSHIOrange

/**
 * __MESHCORE_INTEROP_2026_11_25__ The MeshCore pieces of a chat thread, drawn from [MeshCoreUi] decisions so
 * they can be rendered on their own (DesignShotsTest) as well as inside ChatScreen.
 */

/** What a MeshCore thread's bubbles need from the radio: ids not yet on the air, and whether it is connected. */
internal data class MeshCoreBubbleState(val pending: Set<String> = emptySet(), val radioUp: Boolean = false)

/** Provided by ChatScreen in a MeshCore thread (and by the design shots); empty elsewhere. */
internal val LocalMeshCoreBubbles = androidx.compose.runtime.staticCompositionLocalOf { MeshCoreBubbleState() }

/**
 * The MeshCore accent: OSHI purple on light, a lighter lilac on dark, where the brand purple on near-black is
 * too faint for small text (seen in the design shots: "Pair", tags, initials).
 */
@Composable
internal fun meshCoreAccent(): androidx.compose.ui.graphics.Color =
    if (com.oshi.messenger.ui.theme.isDarkScheme()) androidx.compose.ui.graphics.Color(0xFFB9A2FF) else com.oshi.messenger.ui.theme.OSHIPurple

/** "MeshCore · not OSHI end-to-end encrypted" under the thread title: a fact, in the quiet subtitle ink. */
@Composable
internal fun MeshCoreThreadSubtitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Hub, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(3.dp))
        Text(
            stringResource(R.string.meshcore_not_e2e),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The line right above the composer of a MeshCore thread. Connected: what kind of conversation this is, in one
 * calm sentence, plus the character counter once the text nears one radio message. Not connected: says so, and
 * offers to connect (what was typed is kept as "not delivered", never lost).
 */
@Composable
internal fun MeshCoreComposerNote(
    note: Int,
    count: MeshCoreUi.ComposerCount?,
    radioConnected: Boolean,
    onConnect: () -> Unit,
) {
    if (!radioConnected) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(OSHIOrange.copy(alpha = 0.14f))
                .padding(start = 16.dp, end = 4.dp),
        ) {
            Icon(Icons.Default.LinkOff, null, Modifier.size(16.dp), tint = OSHIOrange)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.meshcore_radio_offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            TextButton(onClick = onConnect) {
                Text(stringResource(R.string.meshcore_connect), fontWeight = FontWeight.SemiBold, color = meshCoreAccent())
            }
        }
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Icon(Icons.Default.Lock, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(note),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (count != null && count.visible) MeshCoreCounter(count)
    }
}

/** "128/133", or "2 messages" once the text will be split, in the warning ink. */
@Composable
internal fun MeshCoreCounter(count: MeshCoreUi.ComposerCount) {
    val over = count.over
    Text(
        text = when {
            count.tooLong -> stringResource(R.string.meshcore_too_long, com.oshi.messenger.service.lora.MeshCoreText.MAX_PARTS)
            over -> stringResource(R.string.meshcore_parts, count.parts)
            else -> "${count.chars}/${count.limit}"
        },
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = when {
            count.tooLong -> MaterialTheme.colorScheme.error
            over -> OSHIOrange
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
    )
}
