@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.oshi.messenger.ui.bots

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.R
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.ui.theme.OshiFilterChip
import com.oshi.messenger.ui.viewmodel.BotPortalViewModel
import com.oshi.messenger.util.BotCommandSyntax
import com.oshi.messenger.util.BotKind
import com.oshi.messenger.util.BotSnippets
import com.oshi.messenger.util.BotUsername

/** Every action the BotFather steps can take, so the content stays stateless. */
data class BotFatherActions(
    val onName: (String) -> Unit = {},
    val onUsername: (String) -> Unit = {},
    val onEmoji: (String) -> Unit = {},
    val onAbout: (String) -> Unit = {},
    val onDescription: (String) -> Unit = {},
    val onKind: (BotKind) -> Unit = {},
    val onAddCommand: (BotCommandSyntax.Spec) -> Unit = {},
    val onRemoveCommand: (String) -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onAddTo: (Group) -> Unit = {},
)

/** __BOTS_PHASE1_2026_11_24__ BotFather, step by step. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotFatherScreen(
    onClose: () -> Unit,
    onFinished: (localBotId: String?) -> Unit,
    viewModel: BotFatherViewModel = hiltViewModel(),
    directory: BotDirectoryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsState()
    val chats by directory.adminChats.collectAsState()
    var addingTo by remember { mutableStateOf<Group?>(null) }
    LaunchedEffect(Unit) { directory.outcomes.collect { toastAddOutcome(context, it) } }
    LaunchedEffect(ui.failed) { if (ui.failed) Toast.makeText(context, R.string.botf_create_failed, Toast.LENGTH_SHORT).show() }

    androidx.activity.compose.BackHandler(enabled = ui.step > 1 && ui.step < BotFatherViewModel.STEPS) { viewModel.back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.botf_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (ui.step in 2 until BotFatherViewModel.STEPS) viewModel.back() else onClose() }) {
                        Icon(
                            if (ui.step == 1) Icons.Default.Close else Icons.Default.ArrowBack,
                            contentDescription = stringResource(if (ui.step == 1) R.string.close else R.string.botf_back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (ui.step == BotFatherViewModel.STEPS) {
                    Button(onClick = { onFinished(ui.bot?.id) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                        Text(stringResource(R.string.botf_finish), fontSize = 16.sp)
                    }
                } else {
                    Button(
                        onClick = viewModel::next,
                        enabled = ui.canContinue,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (ui.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Text(stringResource(R.string.botf_next), fontSize = 16.sp)
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            BotFatherContent(
                ui = ui,
                chats = chats,
                actions = BotFatherActions(
                    onName = viewModel::setName,
                    onUsername = viewModel::setUsername,
                    onEmoji = viewModel::setEmoji,
                    onAbout = viewModel::setAbout,
                    onDescription = viewModel::setDescription,
                    onKind = viewModel::setKind,
                    onAddCommand = viewModel::addCommand,
                    onRemoveCommand = viewModel::removeCommand,
                    onCopy = { copyText(context, it) },
                    onAddTo = { addingTo = it },
                ),
            )
        }
    }
    val bot = ui.bot
    val target = addingTo
    if (bot != null && target != null) {
        AddBotToChatDialog(
            profile = directory.profileOf(bot),
            channel = BotDirectoryViewModel.isChannel(target),
            chats = listOf(target),
            preselected = target,
            onConfirm = { g, rights -> addingTo = null; directory.addToGroup(directory.profileOf(bot), g, rights) },
            onDismiss = { addingTo = null },
        )
    }
}

/** The token and every snippet carry a credential: sensitive clip, cleared after a while. */
private fun copyText(context: Context, text: String) {
    com.oshi.messenger.security.SensitiveClipboard.copy(context, text)
    Toast.makeText(context, R.string.botf_copied, Toast.LENGTH_SHORT).show()
}

/** The current step, stateless (design shots render every step). */
@Composable
fun BotFatherContent(ui: BotFatherViewModel.Ui, chats: List<Group>, actions: BotFatherActions) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        Text(
            stringResource(R.string.botf_step_of, ui.step, BotFatherViewModel.STEPS),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = ui.step / BotFatherViewModel.STEPS.toFloat(), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(20.dp))
        when (ui.step) {
            1 -> StepName(ui, actions)
            2 -> StepUsername(ui, actions)
            3 -> StepAbout(ui, actions)
            4 -> StepCommands(ui, actions)
            5 -> StepToken(ui, actions)
            else -> StepAddTo(ui, chats, actions)
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

@Composable
private fun StepTitle(title: String, help: String) {
    Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
    Text(help, fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun StepName(ui: BotFatherViewModel.Ui, a: BotFatherActions) {
    StepTitle(stringResource(R.string.botf_name_title), stringResource(R.string.botf_name_help))
    OutlinedTextField(
        value = ui.name,
        onValueChange = a.onName,
        label = { Text(stringResource(R.string.botf_name_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
fun usernameProblemText(p: BotUsername.Problem): String = stringResource(
    when (p) {
        BotUsername.Problem.EMPTY -> R.string.botf_username_err_empty
        BotUsername.Problem.TOO_SHORT -> R.string.botf_username_err_short
        BotUsername.Problem.TOO_LONG -> R.string.botf_username_err_long
        BotUsername.Problem.MUST_START_WITH_LETTER -> R.string.botf_username_err_start
        BotUsername.Problem.BAD_CHARACTERS -> R.string.botf_username_err_chars
        BotUsername.Problem.MUST_END_WITH_BOT -> R.string.botf_username_err_bot
    }
)

/** Username field + its live status line; shared by BotFather and the bot editor. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsernameField(username: String, status: BotFatherViewModel.UsernameStatus, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = username,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.botf_username_label)) },
        prefix = { Text("@") },
        singleLine = true,
        isError = status is BotFatherViewModel.UsernameStatus.Invalid || status is BotFatherViewModel.UsernameStatus.Taken,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    )
    Spacer(Modifier.height(8.dp))
    val green = Color(0xFF2E9E5B)
    when (status) {
        BotFatherViewModel.UsernameStatus.Idle -> Unit
        BotFatherViewModel.UsernameStatus.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.botf_username_checking), fontSize = 13.sp)
        }
        BotFatherViewModel.UsernameStatus.Available -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = green, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.botf_username_available, username), color = green, fontSize = 13.sp)
        }
        BotFatherViewModel.UsernameStatus.Unchecked -> Text(stringResource(R.string.botf_username_offline), fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        is BotFatherViewModel.UsernameStatus.Invalid -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp)); Text(usernameProblemText(status.problem), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        is BotFatherViewModel.UsernameStatus.Taken -> Column {
            Text(stringResource(R.string.botf_username_taken, username), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                status.suggestions.forEach { s ->
                    OshiFilterChip(selected = false, onClick = { onChange(s) }, label = { Text("@$s") })
                }
            }
        }
    }
}

@Composable
private fun StepUsername(ui: BotFatherViewModel.Ui, a: BotFatherActions) {
    StepTitle(stringResource(R.string.botf_username_title), stringResource(R.string.botf_username_help))
    UsernameField(ui.username, ui.usernameStatus, a.onUsername)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepAbout(ui: BotFatherViewModel.Ui, a: BotFatherActions) {
    StepTitle(stringResource(R.string.botf_about_title), stringResource(R.string.botf_about_help))
    Text(stringResource(R.string.botf_avatar), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BotPortalViewModel.AVATAR_CHOICES.forEach { e ->
            val selected = e == ui.emoji
            Box(
                Modifier.size(46.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                    .clickable(role = Role.RadioButton) { a.onEmoji(e) },
                contentAlignment = Alignment.Center,
            ) { Text(e, fontSize = 22.sp) }
        }
    }
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = ui.about,
        onValueChange = a.onAbout,
        label = { Text(stringResource(R.string.botf_about_label)) },
        supportingText = { Text("${ui.about.length}/${BotUsername.MAX_ABOUT}") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    )
    OutlinedTextField(
        value = ui.description,
        onValueChange = a.onDescription,
        label = { Text(stringResource(R.string.botf_description_label)) },
        supportingText = { Text("${ui.description.length}/${BotUsername.MAX_DESCRIPTION}") },
        minLines = 3,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
fun kindLabel(kind: BotKind): String = stringResource(
    when (kind) {
        BotKind.POLL -> R.string.botf_kind_poll
        BotKind.WELCOME -> R.string.botf_kind_welcome
        BotKind.REMINDERS -> R.string.botf_kind_reminders
        BotKind.FEED -> R.string.botf_kind_feed
        BotKind.CUSTOM -> R.string.botf_kind_custom
    }
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepCommands(ui: BotFatherViewModel.Ui, a: BotFatherActions) {
    StepTitle(stringResource(R.string.botf_commands_step_title), stringResource(R.string.botf_commands_help))
    Text(stringResource(R.string.botf_kind_label), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BotKind.entries.forEach { k ->
            OshiFilterChip(selected = ui.kind == k, onClick = { a.onKind(k) }, label = { Text("${k.emoji} ${kindLabel(k)}") })
        }
    }
    val suggestions = BotFatherViewModel.suggestions(ui.kind, ui.commands)
    if (suggestions.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.botf_suggested), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            suggestions.forEach { s ->
                OshiFilterChip(
                    selected = false,
                    onClick = { a.onAddCommand(s) },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text(s.command, fontFamily = FontFamily.Monospace) },
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.botf_your_commands), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    if (ui.commands.isEmpty()) {
        Text(stringResource(R.string.botf_no_commands), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp))
    }
    ui.commands.forEach { c ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.command, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = com.oshi.messenger.ui.theme.brandInk())
                if (c.description.isNotBlank()) Text(c.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { a.onRemoveCommand(c.name) }) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.botf_remove_command, c.command))
            }
        }
    }
}

@Composable
private fun CodeBlock(label: String, code: String, onCopy: (String) -> Unit) {
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { onCopy(code) }) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.botf_copy))
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(code, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun StepToken(ui: BotFatherViewModel.Ui, a: BotFatherActions) {
    val token = ui.bot?.token.orEmpty()
    StepTitle(stringResource(R.string.botf_token_title), stringResource(R.string.botf_token_help))
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(token, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { a.onCopy(token) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.botf_copy_token))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(12.dp)) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.botf_token_warning), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
    Spacer(Modifier.height(20.dp))
    Text(stringResource(R.string.botf_connect_title), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
    val base = BuildConfig.VPS_URL
    CodeBlock("curl · getUpdates", BotSnippets.curlGetUpdates(base, token), a.onCopy)
    CodeBlock("curl · send", BotSnippets.curlSend(base, token), a.onCopy)
    CodeBlock("Python", BotSnippets.python(base, token), a.onCopy)
}

@Composable
private fun StepAddTo(ui: BotFatherViewModel.Ui, chats: List<Group>, a: BotFatherActions) {
    StepTitle(stringResource(R.string.botf_add_where_title), stringResource(R.string.botf_add_where_help))
    if (chats.isEmpty()) {
        Text(stringResource(R.string.botf_no_admin_groups), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    chats.forEach { g ->
        val channel = BotDirectoryViewModel.isChannel(g)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (channel) Icons.Default.Campaign else Icons.Default.Group, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(g.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            OutlinedButton(onClick = { a.onAddTo(g) }) {
                Text(stringResource(if (channel) R.string.botf_add_to_channel else R.string.botf_add_to_group))
            }
        }
    }
}
