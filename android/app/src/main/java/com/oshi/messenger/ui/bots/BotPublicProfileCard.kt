package com.oshi.messenger.ui.bots

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.R
import com.oshi.messenger.service.BotManager
import com.oshi.messenger.ui.theme.OshiAlertDialog
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.util.BotLink
import com.oshi.messenger.util.BotUsername

/** __BOTS_PHASE1_2026_11_24__ "My bots" › a bot › public profile, link, QR, token. */
@Composable
fun BotPublicProfileCard(
    localBotId: String,
    onTokenChanged: () -> Unit = {},
    viewModel: BotPublicProfileViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    LaunchedEffect(localBotId) { viewModel.load(localBotId) }
    val ui by viewModel.ui.collectAsState()
    var showQr by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            val res = when (e) {
                BotPublicProfileViewModel.Event.SAVED -> R.string.botf_saved
                BotPublicProfileViewModel.Event.SAVED_LOCALLY -> R.string.botf_profile_sync_failed
                BotPublicProfileViewModel.Event.REVOKED -> R.string.botf_revoked
                BotPublicProfileViewModel.Event.REVOKE_FAILED -> R.string.botf_revoke_failed
            }
            Toast.makeText(context, res, Toast.LENGTH_LONG).show()
            if (e == BotPublicProfileViewModel.Event.REVOKED) onTokenChanged()
        }
    }
    val bot = ui.bot ?: return
    BotPublicProfileCardContent(
        ui = ui,
        isServerBot = BotManager.isServerBot(bot),
        canSave = viewModel.canSave,
        onUsername = viewModel::setUsername,
        onAbout = viewModel::setAbout,
        onPublic = viewModel::setPublic,
        onSave = viewModel::save,
        onShare = {
            shareBotLink(context, com.oshi.messenger.util.BotPublicProfile(botId = "", username = ui.username, name = bot.name, about = ui.about))
        },
        onCopy = { copyBotLink(context, ui.username) },
        onQr = { showQr = true },
        onRevoke = { confirmRevoke = true },
    )
    if (showQr) {
        val link = BotLink.https(ui.username)
        val qr = remember(link) { com.oshi.messenger.ui.screens.generateQrBitmapShared(link) }
        OshiAlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text("@${ui.username}") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
                        qr?.let {
                            Image(it.asImageBitmap(), contentDescription = stringResource(R.string.botf_qr), modifier = Modifier.size(240.dp).padding(12.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(link, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text(stringResource(R.string.close)) } },
        )
    }
    if (confirmRevoke) {
        OshiAlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text(stringResource(R.string.botf_revoke_confirm_title)) },
            text = { Text(stringResource(R.string.botf_revoke_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmRevoke = false; viewModel.revokeToken() }) {
                    Text(stringResource(R.string.botf_revoke), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun BotPublicProfileCardContent(
    ui: BotPublicProfileViewModel.Ui,
    isServerBot: Boolean,
    canSave: Boolean,
    onUsername: (String) -> Unit,
    onAbout: (String) -> Unit,
    onPublic: (Boolean) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onQr: () -> Unit,
    onRevoke: () -> Unit,
) {
    OshiCard(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.botf_public_profile), fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
            UsernameField(ui.username, ui.usernameStatus, onUsername)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = ui.about,
                onValueChange = onAbout,
                label = { Text(stringResource(R.string.botf_about_label)) },
                supportingText = { Text("${ui.about.length}/${BotUsername.MAX_ABOUT}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.botf_public_toggle), fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.botf_public_help), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = ui.isPublic, onCheckedChange = onPublic, enabled = isServerBot)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSave, enabled = canSave, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text(stringResource(R.string.botf_save))
            }
            if (BotUsername.isValid(ui.username)) {
                Spacer(Modifier.height(8.dp))
                Text(BotLink.https(ui.username), fontSize = 13.sp, color = com.oshi.messenger.ui.theme.brandInk())
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onShare) {
                        Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.botf_share_link))
                    }
                    TextButton(onClick = onCopy) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.botf_copy_link))
                    }
                    TextButton(onClick = onQr) {
                        Icon(Icons.Default.QrCode, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.botf_qr))
                    }
                }
            }
            if (isServerBot) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onRevoke, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Key, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.botf_revoke), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
