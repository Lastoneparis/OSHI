package com.oshi.messenger.ui.screens

import androidx.compose.foundation.background
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiAlertDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.oshi.messenger.R
import com.oshi.messenger.data.model.*
import com.oshi.messenger.ui.viewmodel.BotPortalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotDetailScreen(
    botId: String,
    onBack: () -> Unit,
    viewModel: BotPortalViewModel = hiltViewModel()
) {
    val detailState by viewModel.botDetailState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    // __BOT_COMMANDS_2026_11_24__ owner configuration: profile + command list.
    var showProfileDialog by remember { mutableStateOf(false) }
    var editingCommand by remember { mutableStateOf<com.oshi.messenger.service.BotManager.CommandRow?>(null) }
    var addingCommand by remember { mutableStateOf(false) }

    LaunchedEffect(botId) {
        viewModel.loadBotDetail(botId)
    }

    val bot = detailState.bot

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(bot?.name ?: stringResource(R.string.bot_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.key_backup_hide))
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = Color.Red
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (bot == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val isWebhook = bot.type == "webhook"
        if (showProfileDialog) {
            BotProfileDialog(
                name = bot.name,
                description = bot.description,
                emoji = bot.avatarEmoji,
                onSave = { n, d, e -> viewModel.updateBotProfile(n, d, e) },
                onDismiss = { showProfileDialog = false }
            )
        }
        if (addingCommand || editingCommand != null) {
            val initial = editingCommand
            BotCommandEditDialog(
                initial = initial,
                isWebhook = isWebhook,
                onSave = { n, d, r, done -> viewModel.saveCommand(initial?.triggerId, n, d, r, done) },
                onDismiss = { addingCommand = false; editingCommand = null }
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            item {
                OshiCard(shape = RoundedCornerShape(16.dp)) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(bot.avatarEmoji, fontSize = 30.sp)
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                bot.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                            Text(
                                "@${bot.username}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (bot.description.isNotBlank()) {
                                Text(
                                    bot.description,
                                    fontSize = 13.sp,
                                    maxLines = 3,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            val botType = try {
                                BotType.valueOf(bot.type.uppercase())
                            } catch (_: Exception) {
                                BotType.AUTOMATION
                            }
                            Surface(
                                color = Color(0xFF5616EC).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "${botType.emoji} ${botType.displayName}",
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(
                                        horizontal = 8.dp,
                                        vertical = 2.dp
                                    ),
                                    color = Color(0xFF5616EC)
                                )
                            }
                        }
                        IconButton(onClick = { showProfileDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.bot_profile_edit),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // __BOTS_PHASE1_2026_11_24__ public profile: @username, about, public, link + QR, token.
            item { com.oshi.messenger.ui.bots.BotPublicProfileCard(localBotId = bot.id, onTokenChanged = { viewModel.loadBotDetail(botId) }) }

            // __BOT_COMMANDS_2026_11_24__ The `/` menu, as the owner configures it.
            item {
                BotCommandsSection(
                    rows = detailState.commands,
                    sync = detailState.commandSync,
                    isWebhook = isWebhook,
                    onAdd = { addingCommand = true },
                    onEdit = { editingCommand = it },
                    onDelete = { viewModel.deleteCommand(it.triggerId) },
                    onRetry = { viewModel.retryCommandSync() }
                )
            }

            // Where it runs and who hears it.
            item { BotConnectCard(isWebhook = isWebhook, groupNames = detailState.groupNames) }

            // Stats
            item {
                StatCard(
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.bot_detail_messages_sent),
                    value = "${bot.messagesSent}"
                )
            }

            // Token section with copy
            item {
                OshiCard(shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.bot_detail_bot_token), fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            color = Color(0xFF5616EC))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            bot.token,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val tokenContext = androidx.compose.ui.platform.LocalContext.current
                        var tokenCopied by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = {
                            // __CLIPBOARD_HYGIENE_2026_10_01__ the bot token is a credential ⇒ sensitive clip, cleared after 60 s.
                            com.oshi.messenger.security.SensitiveClipboard.copy(tokenContext, bot.token)
                            tokenCopied = true
                        }) {
                            Icon(
                                if (tokenCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (tokenCopied) stringResource(R.string.key_backup_copied_clipboard) else stringResource(R.string.bot_detail_copy_token))
                        }
                        LaunchedEffect(tokenCopied) {
                            if (tokenCopied) {
                                kotlinx.coroutines.delay(2000)
                                tokenCopied = false
                            }
                        }
                    }
                }
            }

            // Post as Bot test section
            item {
                OshiCard(shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.bot_detail_post_as_bot), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.bot_detail_send_test_desc),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = detailState.testMessage,
                            onValueChange = { viewModel.updateTestMessage(it) },
                            placeholder = { Text(stringResource(R.string.bot_detail_test_placeholder)) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.sendTestMessage() },
                            enabled = detailState.testMessage.isNotBlank() && !detailState.isSending,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5616EC))
                        ) {
                            if (detailState.isSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.bot_detail_send_test))
                            }
                        }
                    }
                }
            }

            // Enable/Disable
            item {
                OshiCard(shape = RoundedCornerShape(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.bot_detail_enabled), fontWeight = FontWeight.Medium)
                        Switch(
                            checked = bot.isEnabled,
                            onCheckedChange = { viewModel.toggleBotEnabled(bot.id) }
                        )
                    }
                }
            }

            // Triggers other than commands (commands have their own editor above).
            val otherTriggers = detailState.triggers.filter { it.type != "command" }
            if (otherTriggers.isNotEmpty()) item {
                Text(
                    stringResource(R.string.bot_detail_triggers),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
            }

            // Trigger items
            items(otherTriggers) { trigger ->
                OshiCard(shape = RoundedCornerShape(12.dp)) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                trigger.type.uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF5616EC)
                            )
                            Text(
                                trigger.condition,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                trigger.responseTemplate,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        IconButton(onClick = { viewModel.deleteTrigger(trigger.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.accessibility_delete),
                                tint = Color.Red.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // Script section
            if (bot.scriptContent?.isNotBlank() == true) {
                item {
                    Text(
                        stringResource(R.string.bot_detail_script),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                }
                item {
                    OshiCard(shape = RoundedCornerShape(12.dp)) {
                        Text(
                            bot.scriptContent,
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Webhook URL
            if (bot.type == "webhook") {
                item {
                    OutlinedTextField(
                        value = bot.webhookURL ?: "",
                        onValueChange = {},
                        label = { Text(stringResource(R.string.bot_detail_webhook_url)) },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true
                    )
                }
            }

            // Permissions section header
            item {
                Text(
                    stringResource(R.string.bot_detail_permissions),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
            }

            // Permissions card (editable) — task 1.6.6
            item {
                val perms = BotPermissions.fromJson(bot.permissions)
                OshiCard(shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        PermissionRow(stringResource(R.string.bot_detail_send_messages), perms.canSendMessages) {
                            viewModel.updateBotPermission(bot.id, "canSendMessages", it)
                        }
                        PermissionRow(stringResource(R.string.bot_detail_read_messages), perms.canReadMessages) {
                            viewModel.updateBotPermission(bot.id, "canReadMessages", it)
                        }
                        PermissionRow(stringResource(R.string.bot_detail_send_media), perms.canSendMedia) {
                            viewModel.updateBotPermission(bot.id, "canSendMedia", it)
                        }
                        PermissionRow(stringResource(R.string.bot_detail_pin_messages), perms.canPinMessages) {
                            viewModel.updateBotPermission(bot.id, "canPinMessages", it)
                        }
                        PermissionRow(stringResource(R.string.bot_detail_manage_members), perms.canManageMembers) {
                            viewModel.updateBotPermission(bot.id, "canManageMembers", it)
                        }
                    }
                }
            }

            // Register with Server button
            item {
                Button(
                    onClick = { viewModel.registerBotWithServer(bot.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF5616EC)
                    )
                ) {
                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.bot_detail_register_server))
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        OshiAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.bot_detail_delete_title)) },
            text = {
                Text(stringResource(R.string.bot_detail_delete_confirm, bot?.name ?: ""))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteBot(bot?.id ?: "")
                        onBack()
                    }
                ) {
                    Text(stringResource(R.string.delete), color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun StatCard(modifier: Modifier, label: String, value: String) {
    OshiCard(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionRow(label: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp)
        Switch(
            checked = enabled,
            onCheckedChange = onToggle
        )
    }
}
