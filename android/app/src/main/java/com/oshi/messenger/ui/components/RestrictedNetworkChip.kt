package com.oshi.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.util.RestrictedNetworkHintStore

/** __RESTRICTED_NETWORK_HINT_2026_11_18__ Was this received message sent through a restricted network? */
@Composable
fun rememberRestrictedNetworkFlag(messageId: String): Boolean {
    val context = LocalContext.current
    remember { RestrictedNetworkHintStore.load(context) }
    val ids by RestrictedNetworkHintStore.ids.collectAsState()
    return messageId in ids
}

/**
 * The discreet chip under an incoming bubble (or a revealed "Disguise as photo" text):
 * eye-slash + "Sent through a restricted network". Tap → what it means and what to do.
 */
@Composable
fun RestrictedNetworkChip(modifier: Modifier = Modifier) {
    var explain by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .clickable(role = Role.Button) { explain = true }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.VisibilityOff, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.rn_hint_chip), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (explain) {
        com.oshi.messenger.ui.theme.OshiAlertDialog(
            onDismissRequest = { explain = false },
            icon = { Icon(Icons.Default.VisibilityOff, contentDescription = null) },
            title = { Text(stringResource(R.string.rn_hint_explain_title)) },
            text = { Text(stringResource(R.string.rn_hint_explain_body)) },
            confirmButton = { TextButton(onClick = { explain = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}
