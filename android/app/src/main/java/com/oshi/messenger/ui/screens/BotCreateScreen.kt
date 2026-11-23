package com.oshi.messenger.ui.screens

import androidx.compose.foundation.clickable
import com.oshi.messenger.ui.theme.OshiFilterChip
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.data.model.*
import com.oshi.messenger.ui.viewmodel.BotPortalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotCreateScreen(
    onDismiss: () -> Unit,
    viewModel: BotPortalViewModel
) {
    val createState by viewModel.createBotState.collectAsState()

    OshiModalBottomSheet(
        onDismissRequest = {
            viewModel.dismissCreateBot()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text = when (createState.step) {
                    0 -> stringResource(R.string.bot_create_header)
                    1 -> stringResource(R.string.bot_create_choose_type)
                    2 -> stringResource(R.string.bot_create_add_triggers)
                    3 -> stringResource(R.string.bot_create_review)
                    4 -> stringResource(R.string.bot_create_created)
                    else -> stringResource(R.string.bot_create_header)
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Step indicator
            LinearProgressIndicator(
                progress = (createState.step + 1) / 5f,
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF5616EC)
            )
            Spacer(modifier = Modifier.height(16.dp))

            when (createState.step) {
                0 -> StepBasicInfo(createState, viewModel)
                1 -> StepTypeSelection(createState, viewModel)
                2 -> StepTriggers(createState, viewModel)
                3 -> StepReview(createState, viewModel)
                4 -> StepDone(createState)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Navigation buttons
            if (createState.step == 4) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = {
                            viewModel.dismissCreateBot()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5616EC))
                    ) {
                        Text(stringResource(R.string.done))
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (createState.step > 0) {
                        OutlinedButton(onClick = { viewModel.previousStep() }) {
                            Text(stringResource(R.string.bot_create_back))
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    if (createState.step < 3) {
                        Button(
                            onClick = { viewModel.nextStep() },
                            enabled = createState.name.isNotBlank() && createState.username.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5616EC))
                        ) {
                            Text(stringResource(R.string.bot_create_next))
                        }
                    } else {
                        Button(
                            onClick = {
                                viewModel.createBot()
                            },
                            enabled = !createState.isCreating,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5616EC))
                        ) {
                            if (createState.isCreating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.bot_create_header))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepBasicInfo(
    state: BotPortalViewModel.CreateBotState,
    viewModel: BotPortalViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
        // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
        com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
            OutlinedTextField(
                value = state.name,
                onValueChange = { viewModel.updateBotName(it) },
                label = { Text(stringResource(R.string.bot_create_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
        // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
        com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
            OutlinedTextField(
                value = state.username,
                onValueChange = { viewModel.updateBotUsername(it) },
                label = { Text(stringResource(R.string.bot_create_username)) },
                prefix = { Text("@") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
        // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
        com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
            OutlinedTextField(
                value = state.description,
                onValueChange = { viewModel.updateBotDescription(it) },
                label = { Text(stringResource(R.string.bot_create_description)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
        }
    }
}

@Composable
private fun StepTypeSelection(
    state: BotPortalViewModel.CreateBotState,
    viewModel: BotPortalViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bot_create_type), fontWeight = FontWeight.SemiBold)
        BotType.entries.forEach { type ->
            OshiCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.selectBotType(type) },
                colors = CardDefaults.cardColors(
                    containerColor = if (state.selectedType == type)
                        Color(0xFF5616EC).copy(alpha = 0.1f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(type.emoji, fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(type.displayName, fontWeight = FontWeight.Medium)
                        Text(
                            type.description,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(stringResource(R.string.bot_create_templates), fontWeight = FontWeight.SemiBold)
        BotTemplate.TEMPLATES.filter {
            state.selectedType == BotType.AUTOMATION || it.type == state.selectedType
        }.take(4).forEach { template ->
            OshiCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.selectTemplate(template) },
                colors = CardDefaults.cardColors(
                    containerColor = if (state.selectedTemplate?.id == template.id)
                        Color(0xFF5616EC).copy(alpha = 0.1f)
                    else MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(template.emoji, fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(template.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(
                            template.description,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepTriggers(
    state: BotPortalViewModel.CreateBotState,
    viewModel: BotPortalViewModel
) {
    var triggerType by remember { mutableStateOf("keyword") }
    var triggerCondition by remember { mutableStateOf("") }
    var triggerResponse by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bot_create_triggers_count, state.triggers.size), fontWeight = FontWeight.SemiBold)

        // Existing triggers
        state.triggers.forEach { trigger ->
            OshiCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
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
                        Text(trigger.condition, fontSize = 13.sp)
                    }
                    IconButton(onClick = { viewModel.removeTrigger(trigger.id) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.accessibility_remove_trigger),
                            tint = Color.Red
                        )
                    }
                }
            }
        }

        // Add trigger form
        OshiCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.bot_create_add_trigger), fontWeight = FontWeight.Medium)

                // Type selector
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("keyword", "command", "regex").forEach { type ->
                        OshiFilterChip(
                            selected = triggerType == type,
                            onClick = { triggerType = type },
                            label = {
                                Text(
                                    type.replaceFirstChar { it.uppercase() },
                                    fontSize = 12.sp
                                )
                            }
                        )
                    }
                }

                // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                    OutlinedTextField(
                        value = triggerCondition,
                        onValueChange = { triggerCondition = it },
                        label = { Text(stringResource(R.string.bot_create_condition)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = {
                            Text(if (triggerType == "command") stringResource(R.string.bot_create_condition_command_placeholder) else stringResource(R.string.bot_create_condition_keyword_placeholder))
                        }
                    )
                }
                // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                    OutlinedTextField(
                        value = triggerResponse,
                        onValueChange = { triggerResponse = it },
                        label = { Text(stringResource(R.string.bot_create_response)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        placeholder = {
                            Text(stringResource(R.string.bot_create_response_placeholder))
                        }
                    )
                }
                Button(
                    onClick = {
                        if (triggerCondition.isNotBlank() && triggerResponse.isNotBlank()) {
                            viewModel.addTrigger(
                                BotTrigger(
                                    botId = "",
                                    type = triggerType,
                                    condition = triggerCondition,
                                    responseTemplate = triggerResponse
                                )
                            )
                            triggerCondition = ""
                            triggerResponse = ""
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5616EC))
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.accessibility_add_trigger),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.bot_create_add))
                }
            }
        }
    }
}

@Composable
private fun StepReview(
    state: BotPortalViewModel.CreateBotState,
    viewModel: BotPortalViewModel
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OshiCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.bot_create_summary), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.bot_create_summary_name, state.name))
                    Text(stringResource(R.string.bot_create_summary_username, state.username))
                    Text(stringResource(R.string.bot_create_summary_type, state.selectedType.displayName))
                    Text(stringResource(R.string.bot_create_summary_triggers, state.triggers.size))
                    if (state.description.isNotBlank()) {
                        Text(stringResource(R.string.bot_create_summary_description, state.description))
                    }
                }
            }
        }
        item {
            // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
            // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
            com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                OutlinedTextField(
                    value = state.webhookURL,
                    onValueChange = { viewModel.updateWebhookURL(it) },
                    label = { Text(stringResource(R.string.bot_create_webhook_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }
        item {
            // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
            // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
            com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                OutlinedTextField(
                    value = state.scriptContent,
                    onValueChange = { viewModel.updateScriptContent(it) },
                    label = { Text(stringResource(R.string.bot_create_script)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                )
            }
        }
    }
}

@Composable
private fun StepDone(state: BotPortalViewModel.CreateBotState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bot = state.createdBot
    var tokenCopied by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("\u2705", fontSize = 48.sp)
        Text(stringResource(R.string.bot_create_bot_ready), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)

        if (bot != null) {
            OshiCard(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.bot_token), fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        color = Color(0xFF5616EC))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(bot.token, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        // __CLIPBOARD_HYGIENE_2026_10_01__ the bot token is a credential ⇒ sensitive clip, cleared after 60 s.
                        com.oshi.messenger.security.SensitiveClipboard.copy(context, bot.token)
                        tokenCopied = true
                    }) {
                        Icon(if (tokenCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.accessibility_copy_token), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (tokenCopied) stringResource(R.string.copied) else stringResource(R.string.bot_create_copy_token))
                    }
                }
            }

            OshiCard(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.bot_create_quick_start), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.bot_create_send_via_api), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "POST https://oshi-messenger.com/api/bot/send\n" +
                            "{\"token\": \"${bot.token.take(8)}...\",\n" +
                            " \"groupId\": \"GROUP_ID\",\n" +
                            " \"content\": \"Hello!\"}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(tokenCopied) {
        if (tokenCopied) {
            kotlinx.coroutines.delay(2000)
            tokenCopied = false
        }
    }
}
