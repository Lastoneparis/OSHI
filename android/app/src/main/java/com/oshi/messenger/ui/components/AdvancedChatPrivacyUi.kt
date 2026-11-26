package com.oshi.messenger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.oshi.messenger.R
import com.oshi.messenger.service.AdvancedChatPrivacyStore
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import com.oshi.messenger.ui.theme.brandInk

/**
 * __ADV_CHAT_PRIVACY_2026_11_26__ Everything "advanced chat privacy" draws, for
 * the 1:1 and the group screens alike.
 */

/**
 * Whether the conversation on screen has advanced chat privacy on. Provided by
 * ChatScreen / GroupChatScreen so deep bubble code (the full-screen viewer's
 * Download button) can ask without threading a parameter through every layer.
 */
val LocalAdvancedChatPrivacy = compositionLocalOf { false }

/** Observed, so the screen flips the moment a peer's change arrives. */
@Composable
fun rememberAdvancedChatPrivacy(conversationId: String): Boolean {
    val states by AdvancedChatPrivacyStore.states.collectAsState()
    return remember(states, conversationId) { AdvancedChatPrivacyStore.isOn(conversationId) }
}

/**
 * Forces FLAG_SECURE (via [AdvancedChatPrivacyStore.onScreen] →
 * `ScreenshotProtectionManager`) while a protected conversation is composed.
 */
@Composable
fun AdvancedChatPrivacyWindowGuard(conversationId: String, on: Boolean) {
    DisposableEffect(conversationId, on) {
        if (on && conversationId.isNotEmpty()) AdvancedChatPrivacyStore.enterScreen(conversationId)
        onDispose {
            if (on && conversationId.isNotEmpty()) AdvancedChatPrivacyStore.leaveScreen(conversationId)
        }
    }
}

/** The menu / info-sheet toggle: title, a subtitle saying what it does, a switch, "Learn more". */
@Composable
fun AdvancedChatPrivacyToggle(
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showInfo by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onToggle(!on) }
            .testTag("adv_privacy_toggle"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Shield,
            contentDescription = null,
            tint = if (on) brandInk() else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.adv_privacy_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.adv_privacy_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.adv_privacy_learn_more),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = brandInk(),
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable { showInfo = true },
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = on, onCheckedChange = onToggle)
    }
    if (showInfo) AdvancedChatPrivacyInfoSheet(onDismiss = { showInfo = false })
}

/**
 * The centred line in the thread. [actorName] null means the change was ours
 * ("You turned on …").
 */
@Composable
fun AdvancedChatPrivacyNotice(on: Boolean, actorName: String?) {
    var showInfo by remember { mutableStateOf(false) }
    val text = when {
        actorName == null && on -> stringResource(R.string.adv_privacy_notice_on_you)
        actorName == null -> stringResource(R.string.adv_privacy_notice_off_you)
        on -> stringResource(R.string.adv_privacy_notice_on, actorName)
        else -> stringResource(R.string.adv_privacy_notice_off, actorName)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 6.dp)
            .testTag("adv_privacy_notice"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.adv_privacy_learn_more),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = brandInk(),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable { showInfo = true },
                )
            }
        }
    }
    if (showInfo) AdvancedChatPrivacyInfoSheet(onDismiss = { showInfo = false })
}

/** The short explanation behind every "Learn more". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedChatPrivacyInfoSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    OshiModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .testTag("adv_privacy_info_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = brandInk(), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.adv_privacy_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(stringResource(R.string.adv_privacy_sheet_intro), style = MaterialTheme.typography.bodyMedium)
            for (res in listOf(
                R.string.adv_privacy_sheet_point_save,
                R.string.adv_privacy_sheet_point_export,
                R.string.adv_privacy_sheet_point_ai,
                R.string.adv_privacy_sheet_point_screen,
            )) {
                Row {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(res), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                stringResource(R.string.adv_privacy_sheet_limits),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.done), color = brandInk(), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
