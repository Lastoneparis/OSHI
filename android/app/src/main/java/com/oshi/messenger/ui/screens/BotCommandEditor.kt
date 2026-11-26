package com.oshi.messenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.service.BotManager
import com.oshi.messenger.ui.theme.OshiAlertDialog
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.viewmodel.BotPortalViewModel
import com.oshi.messenger.util.BotCommandSyntax

// ─────────────────────────────────────────────────────────────────────────────
// __BOT_COMMANDS_2026_11_24__ The owner's side of Telegram-style commands: the list
// people see when they type `/` (or tap "Commands") in the bot's groups, edited here —
// name, description, and what the bot answers. Stateless composables, rendered by the
// design shots as well as by BotDetailScreen.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun BotCommandsSection(
    rows: List<BotManager.CommandRow>,
    sync: BotPortalViewModel.CommandSync,
    isWebhook: Boolean,
    onAdd: () -> Unit,
    onEdit: (BotManager.CommandRow) -> Unit,
    onDelete: (BotManager.CommandRow) -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.bot_cmd_section),
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
            FilledTonalButton(onClick = onAdd, enabled = rows.size < BotCommandSyntax.MAX_COMMANDS) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.bot_cmd_add))
            }
        }
        Text(
            stringResource(R.string.bot_cmd_section_hint),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
        )
        OshiCard(shape = RoundedCornerShape(16.dp)) {
            if (rows.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAdd)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("/", fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        color = com.oshi.messenger.ui.theme.brandInk())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.bot_cmd_empty_owner),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                Column {
                    rows.forEachIndexed { i, row ->
                        if (i > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 64.dp)
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            )
                        }
                        CommandEditorRow(row, isWebhook, onEdit = { onEdit(row) }, onDelete = { onDelete(row) })
                    }
                }
            }
        }
        SyncLine(sync, onRetry)
    }
}

@Composable
private fun CommandEditorRow(row: BotManager.CommandRow, isWebhook: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .heightIn(min = 64.dp)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text("/", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.spec.command, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp, color = com.oshi.messenger.ui.theme.brandInk(), maxLines = 1)
            if (row.spec.description.isNotBlank()) {
                Text(row.spec.description, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // A webhook bot's answer comes from its server: nothing to preview per row.
            val reply = if (isWebhook) "" else row.reply
            if (reply.isNotBlank()) {
                Text("↳ $reply", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.accessibility_delete),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun SyncLine(sync: BotPortalViewModel.CommandSync, onRetry: () -> Unit) {
    val (icon, text, color) = when (sync) {
        BotPortalViewModel.CommandSync.LOCAL ->
            Triple(Icons.Default.PhoneAndroid, R.string.bot_cmd_sync_local, MaterialTheme.colorScheme.onSurfaceVariant)
        BotPortalViewModel.CommandSync.SYNCING ->
            Triple(Icons.Default.Sync, R.string.bot_cmd_sync_syncing, MaterialTheme.colorScheme.onSurfaceVariant)
        BotPortalViewModel.CommandSync.SYNCED ->
            Triple(Icons.Default.CloudDone, R.string.bot_cmd_sync_synced, MaterialTheme.colorScheme.primary)
        BotPortalViewModel.CommandSync.FAILED ->
            Triple(Icons.Default.CloudOff, R.string.bot_cmd_sync_failed, MaterialTheme.colorScheme.error)
    }
    Row(Modifier.padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(text), fontSize = 12.sp, color = color, modifier = Modifier.weight(1f))
        if (sync == BotPortalViewModel.CommandSync.FAILED) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

/** Add / edit one command. [onSave] returns the refusal to show, or null when saved. */
@Composable
fun BotCommandEditDialog(
    initial: BotManager.CommandRow?,
    isWebhook: Boolean,
    onSave: (name: String, description: String, reply: String, onResult: (BotCommandSyntax.EditError?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.spec?.name.orEmpty()) }
    var description by remember { mutableStateOf(initial?.spec?.description.orEmpty()) }
    var reply by remember { mutableStateOf(initial?.reply.orEmpty()) }
    var error by remember { mutableStateOf<BotCommandSyntax.EditError?>(null) }
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.bot_cmd_add_title else R.string.bot_cmd_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace(" ", "_"); error = null },
                    label = { Text(stringResource(R.string.bot_cmd_field_name)) },
                    prefix = { Text("/", fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    isError = error in NAME_ERRORS,
                    supportingText = {
                        val e = error
                        if (e != null && e in NAME_ERRORS) Text(stringResource(e.messageRes()))
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it; error = null },
                    label = { Text(stringResource(R.string.bot_cmd_field_description)) },
                    singleLine = false,
                    maxLines = 3,
                    isError = error == BotCommandSyntax.EditError.DESCRIPTION_TOO_LONG,
                    supportingText = {
                        if (error == BotCommandSyntax.EditError.DESCRIPTION_TOO_LONG) {
                            Text(stringResource(R.string.bot_cmd_err_description))
                        } else {
                            Text("${description.trim().length}/${BotCommandSyntax.MAX_DESCRIPTION}")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                if (isWebhook) {
                    Text(stringResource(R.string.bot_cmd_webhook_note), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    OutlinedTextField(
                        value = reply,
                        onValueChange = { reply = it },
                        label = { Text(stringResource(R.string.bot_cmd_field_reply)) },
                        supportingText = { Text(stringResource(R.string.bot_cmd_field_reply_hint)) },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
                val e = error
                if (e != null && e !in NAME_ERRORS && e != BotCommandSyntax.EditError.DESCRIPTION_TOO_LONG) {
                    Text(stringResource(e.messageRes()), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, description, reply) { error = it; if (it == null) onDismiss() } },
                enabled = name.isNotBlank() && (isWebhook || reply.isNotBlank())
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private val NAME_ERRORS = setOf(
    BotCommandSyntax.EditError.EMPTY,
    BotCommandSyntax.EditError.INVALID,
    BotCommandSyntax.EditError.TOO_LONG,
    BotCommandSyntax.EditError.DUPLICATE,
)

fun BotCommandSyntax.EditError.messageRes(): Int = when (this) {
    BotCommandSyntax.EditError.EMPTY -> R.string.bot_cmd_err_empty
    BotCommandSyntax.EditError.INVALID -> R.string.bot_cmd_err_invalid
    BotCommandSyntax.EditError.TOO_LONG -> R.string.bot_cmd_err_too_long
    BotCommandSyntax.EditError.DUPLICATE -> R.string.bot_cmd_err_duplicate
    BotCommandSyntax.EditError.TOO_MANY -> R.string.bot_cmd_err_too_many
    BotCommandSyntax.EditError.DESCRIPTION_TOO_LONG -> R.string.bot_cmd_err_description
}

/** Name, description and avatar. [onSave] returns the refusal, or null when saved. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BotProfileDialog(
    name: String,
    description: String,
    emoji: String,
    onSave: (String, String, String) -> BotPortalViewModel.ProfileError?,
    onDismiss: () -> Unit,
) {
    var n by remember { mutableStateOf(name) }
    var d by remember { mutableStateOf(description) }
    var e by remember { mutableStateOf(emoji) }
    var error by remember { mutableStateOf<BotPortalViewModel.ProfileError?>(null) }
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bot_profile_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.bot_profile_avatar), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BotPortalViewModel.AVATAR_CHOICES.forEach { choice ->
                        val selected = choice == e
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .then(
                                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    else Modifier
                                )
                                .clickable { e = choice },
                            contentAlignment = Alignment.Center
                        ) { Text(choice, fontSize = 22.sp) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = n,
                    onValueChange = { n = it; error = null },
                    label = { Text(stringResource(R.string.bot_profile_name)) },
                    singleLine = true,
                    isError = error == BotPortalViewModel.ProfileError.NAME_EMPTY || error == BotPortalViewModel.ProfileError.NAME_TOO_LONG,
                    supportingText = {
                        when (error) {
                            BotPortalViewModel.ProfileError.NAME_EMPTY -> Text(stringResource(R.string.bot_profile_err_name_empty))
                            BotPortalViewModel.ProfileError.NAME_TOO_LONG -> Text(stringResource(R.string.bot_profile_err_name_long))
                            else -> {}
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = d,
                    onValueChange = { d = it; error = null },
                    label = { Text(stringResource(R.string.bot_profile_description)) },
                    minLines = 2,
                    maxLines = 4,
                    isError = error == BotPortalViewModel.ProfileError.DESCRIPTION_TOO_LONG,
                    supportingText = {
                        if (error == BotPortalViewModel.ProfileError.DESCRIPTION_TOO_LONG) {
                            Text(stringResource(R.string.bot_profile_err_desc_long))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val r = onSave(n, d, e)
                if (r == null) onDismiss() else error = r
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** Where the bot runs, which groups hear it, and (webhook) how a server talks to it. */
@Composable
fun BotConnectCard(isWebhook: Boolean, groupNames: List<String>) {
    OshiCard(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isWebhook) Icons.Default.Link else Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.bot_connect_title), fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    modifier = Modifier.semantics { heading() })
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (isWebhook) R.string.bot_connect_webhook else R.string.bot_connect_local),
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (groupNames.isEmpty()) stringResource(R.string.bot_connect_no_groups)
                    else stringResource(R.string.bot_connect_groups, groupNames.joinToString(", ")),
                    fontSize = 13.sp,
                    color = if (groupNames.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isWebhook) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.bot_connect_token_warning),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
