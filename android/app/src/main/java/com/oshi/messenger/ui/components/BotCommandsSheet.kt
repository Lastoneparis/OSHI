package com.oshi.messenger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.ui.viewmodel.GroupChatViewModel
import com.oshi.messenger.util.SlashCommandCatalog

/**
 * __BOT_COMMANDS_2026_11_24__ Telegram's "Menu" button for a conversation with bots.
 *
 * Typing `/` already opened the command list, but only for people who knew to type it.
 * This pill sits above the composer whenever the conversation has a bot, and opens the
 * same list as a sheet, grouped by bot, with friendly empty states (no bot / a bot with
 * no commands yet — and for the bot's OWNER, a button straight to its settings).
 */
@Composable
fun BotCommandsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.bot_cmd_menu)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.heightIn(min = 36.dp),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotCommandsSheet(
    bots: List<GroupChatViewModel.BotHere>,
    commands: List<SlashCommandCatalog.Entry>,
    onSend: (SlashCommandCatalog.Entry) -> Unit,
    onSetUp: (String) -> Unit,
    onDismiss: () -> Unit,
    /** __BOTS_PHASE1_2026_11_24__ put `/name ` in the composer (arrow, or a `<arg>` command). */
    onInsert: (String) -> Unit = {},
) {
    com.oshi.messenger.ui.theme.OshiModalBottomSheet(onDismissRequest = onDismiss) {
        BotCommandsSheetContent(bots, commands, onSend, onSetUp, onInsert)
    }
}

/** The sheet's body, separate so it can be rendered without a window (design shots). */
@Composable
fun BotCommandsSheetContent(
    bots: List<GroupChatViewModel.BotHere>,
    commands: List<SlashCommandCatalog.Entry>,
    onSend: (SlashCommandCatalog.Entry) -> Unit,
    onSetUp: (String) -> Unit,
    onInsert: (String) -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
    ) {
        Text(
            stringResource(R.string.bot_cmd_sheet_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .semantics { heading() },
        )
        if (bots.isEmpty()) {
            EmptyBlock(
                title = stringResource(R.string.bot_cmd_empty_none_title),
                body = stringResource(R.string.bot_cmd_empty_none_body),
            )
            return@Column
        }
        Text(
            stringResource(R.string.bot_cmd_tap_to_send),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            bots.forEach { bot ->
                val label = "${bot.emoji} ${bot.name}".trim()
                val own = commands.filter { it.botLabel == label }
                item(key = "h-$label") { BotHeader(bot) }
                if (own.isEmpty()) {
                    item(key = "e-$label") {
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
                            Text(
                                stringResource(R.string.bot_cmd_empty_bot),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            bot.localBotId?.let { id ->
                                Spacer(Modifier.height(8.dp))
                                FilledTonalButton(onClick = { onSetUp(id) }) {
                                    Text(stringResource(R.string.bot_cmd_setup))
                                }
                            }
                        }
                    }
                } else {
                    own.forEach { entry ->
                        item(key = "c-$label-${entry.command}") {
                            val sends = entry.sendsOnPick
                            BotCommandRow(
                                entry = entry.copy(botLabel = null),
                                onClick = {
                                    if (sends) onSend(entry) else onInsert(SlashCommandCatalog.completion(entry))
                                },
                                sends = sends,
                                onInsert = if (sends) ({ onInsert(SlashCommandCatalog.completion(entry)) }) else null,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BotHeader(bot: GroupChatViewModel.BotHere) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CommandBadge(bot.emoji, size = 30.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            bot.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (bot.commandCount > 0) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .padding(horizontal = 2.dp),
            ) {
                Text(
                    "${bot.commandCount}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyBlock(title: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .then(Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            body,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
