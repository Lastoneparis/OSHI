@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.oshi.messenger.ui.bots

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.R
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.ui.theme.OshiAlertDialog
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import com.oshi.messenger.util.BotAdminRights
import com.oshi.messenger.util.BotLink
import com.oshi.messenger.util.BotPublicProfile

/** __BOTS_PHASE1_2026_11_24__ The small "BOT" pill: profile, search rows, bot posts. */
@Composable
fun BotBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.botf_badge),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            color = com.oshi.messenger.ui.theme.brandInk(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
fun BotAvatar(emoji: String, size: androidx.compose.ui.unit.Dp = 72.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) { Text(emoji.ifBlank { "🤖" }, fontSize = (size.value * 0.48f).sp) }
}

fun shareBotLink(context: Context, profile: BotPublicProfile) {
    val link = BotLink.https(profile.username)
    val text = if (profile.about.isNotBlank()) "${profile.name} — ${profile.about}\n$link" else "${profile.name}\n$link"
    runCatching {
        context.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

fun copyBotLink(context: Context, username: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("OSHI bot", BotLink.https(username)))
    Toast.makeText(context, R.string.botf_link_copied, Toast.LENGTH_SHORT).show()
}

/**
 * The bot's public profile: what a link or a search result opens. Two actions, as in
 * Telegram: add it to one of MY groups, or to one of MY channels.
 */
@Composable
fun BotProfileContent(
    profile: BotPublicProfile,
    onAddToGroup: () -> Unit,
    onAddToChannel: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BotAvatar(profile.avatarEmoji, 84.dp)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                profile.name,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false).semantics { heading() },
            )
            Spacer(Modifier.width(8.dp))
            BotBadge()
        }
        Text(profile.handle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        if (profile.about.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(profile.about, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(if (profile.isRemote) R.string.botf_remote_hint else R.string.botf_local_hint),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onAddToGroup, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Default.Group, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.botf_add_to_group), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onAddToChannel, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Default.Campaign, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.botf_add_to_channel), fontSize = 16.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.botf_share_link))
            }
            TextButton(onClick = onCopyLink) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.botf_copy_link))
            }
        }
        if (profile.description.isNotBlank()) {
            Divider(Modifier.padding(vertical = 10.dp))
            Text(profile.description, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth())
        }
        if (profile.commands.isNotEmpty()) {
            Divider(Modifier.padding(vertical = 10.dp))
            Text(
                stringResource(R.string.botf_commands_title),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
            profile.commands.take(8).forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(c.command, fontFamily = FontFamily.Monospace, color = com.oshi.messenger.ui.theme.brandInk(), fontSize = 14.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(c.description, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    }
}

/** A sentence instead of a profile: unknown / not public, or unreachable. */
@Composable
fun BotProfileMessage(text: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BotAvatar("🤖", 64.dp)
        Spacer(Modifier.height(12.dp))
        Text(text, textAlign = TextAlign.Center, fontSize = 16.sp)
    }
}

/**
 * Pick one of my groups (or channels), choose what the bot may do, read the notice,
 * add. Stateless body; [AddBotToChatDialog] hosts it.
 */
@Composable
fun AddBotToChatContent(
    botName: String,
    isRemote: Boolean,
    channel: Boolean,
    chats: List<Group>,
    selected: Group?,
    onSelect: (Group) -> Unit,
    rights: BotAdminRights,
    onRights: (BotAdminRights) -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (chats.isEmpty()) {
            Text(stringResource(if (channel) R.string.botf_no_admin_channels else R.string.botf_no_admin_groups))
            return@Column
        }
        Text(stringResource(R.string.botf_pick_chat), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        chats.forEach { g ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { onSelect(g) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected?.id == g.id, onClick = { onSelect(g) })
                Text(g.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.botf_rights_title), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        RightRow(stringResource(R.string.botf_right_post), rights.canPost) { onRights(rights.copy(canPost = it)) }
        RightRow(stringResource(R.string.botf_right_edit), rights.canEdit) { onRights(rights.copy(canEdit = it)) }
        RightRow(stringResource(R.string.botf_right_delete), rights.canDelete) { onRights(rights.copy(canDelete = it)) }
        RightRow(stringResource(R.string.botf_right_pin), rights.canPin) { onRights(rights.copy(canPin = it)) }
        if (isRemote) {
            Spacer(Modifier.height(10.dp))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(12.dp)) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(if (channel) R.string.botf_remote_notice_channel else R.string.botf_remote_notice_group),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun RightRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
fun AddBotToChatDialog(
    profile: BotPublicProfile,
    channel: Boolean,
    chats: List<Group>,
    preselected: Group? = null,
    onConfirm: (Group, BotAdminRights) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = chats.filter { BotDirectoryViewModel.isChannel(it) == channel }
    var selected by remember(candidates) { mutableStateOf(preselected ?: candidates.singleOrNull()) }
    var rights by remember { mutableStateOf(BotAdminRights.DEFAULT) }
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (channel) R.string.botf_add_title_channel else R.string.botf_add_title_group, profile.name))
        },
        text = {
            AddBotToChatContent(profile.name, profile.isRemote, channel, candidates, selected, { selected = it }, rights, { rights = it })
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = { selected?.let { onConfirm(it, rights) } }) {
                Text(stringResource(R.string.botf_add_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Toast for an add outcome. */
fun toastAddOutcome(context: Context, outcome: BotDirectoryViewModel.AddOutcome) {
    val res = when (outcome) {
        BotDirectoryViewModel.AddOutcome.ADDED -> R.string.botf_added
        BotDirectoryViewModel.AddOutcome.NOT_ALLOWED -> R.string.botf_add_not_allowed
        BotDirectoryViewModel.AddOutcome.UNREACHABLE -> R.string.botf_add_unreachable
        BotDirectoryViewModel.AddOutcome.FAILED -> R.string.botf_add_failed
    }
    Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
}

/**
 * The sheet a bot link opens (`/bot/<username>`), or a search result: profile, then the
 * add dialog. [onOpenChat] opens the group/channel once the bot is in it.
 */
@Composable
fun BotProfileSheetHost(
    username: String?,
    botId: String? = null,
    onDismiss: () -> Unit,
    onOpenChat: (String) -> Unit = {},
    viewModel: BotDirectoryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    LaunchedEffect(username, botId) { viewModel.loadProfile(username, botId) }
    val state by viewModel.profile.collectAsState()
    val chats by viewModel.adminChats.collectAsState()
    var addingTo by remember { mutableStateOf<Boolean?>(null) } // null closed, true channel
    var lastTarget by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        viewModel.outcomes.collect { outcome ->
            toastAddOutcome(context, outcome)
            if (outcome == BotDirectoryViewModel.AddOutcome.ADDED) {
                lastTarget?.let(onOpenChat)
                onDismiss()
            }
        }
    }
    OshiModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            when (val s = state) {
                BotDirectoryViewModel.ProfileState.Loading ->
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                BotDirectoryViewModel.ProfileState.NotFound -> BotProfileMessage(stringResource(R.string.botf_not_found))
                BotDirectoryViewModel.ProfileState.Unreachable -> BotProfileMessage(stringResource(R.string.botf_unreachable))
                is BotDirectoryViewModel.ProfileState.Loaded -> {
                    BotProfileContent(
                        profile = s.profile,
                        onAddToGroup = { addingTo = false },
                        onAddToChannel = { addingTo = true },
                        onShare = { shareBotLink(context, s.profile) },
                        onCopyLink = { copyBotLink(context, s.profile.username) },
                    )
                    addingTo?.let { channel ->
                        AddBotToChatDialog(
                            profile = s.profile,
                            channel = channel,
                            chats = chats,
                            onConfirm = { group, rights ->
                                addingTo = null
                                lastTarget = group.id
                                viewModel.addToGroup(s.profile, group, rights)
                            },
                            onDismiss = { addingTo = null },
                        )
                    }
                }
            }
        }
    }
}

/** One search / "my bots" row. */
@Composable
fun BotListRow(profile: BotPublicProfile, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotAvatar(profile.avatarEmoji, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                BotBadge()
            }
            Text(
                if (profile.about.isNotBlank()) "${profile.handle} · ${profile.about}" else profile.handle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

/**
 * `@` search + my bots + "Create a bot". Stateless body (design shots render it).
 */
@Composable
fun BotSearchContent(
    query: String,
    onQuery: (String) -> Unit,
    search: BotDirectoryViewModel.SearchState,
    myBots: List<BotPublicProfile>,
    onPick: (BotPublicProfile) -> Unit,
    onCreate: (() -> Unit)?,
) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.botf_search_hint), maxLines = 1) },
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when (search) {
            BotDirectoryViewModel.SearchState.Idle -> Unit
            BotDirectoryViewModel.SearchState.Searching ->
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            BotDirectoryViewModel.SearchState.Unreachable -> SearchNote(stringResource(R.string.botf_unreachable))
            is BotDirectoryViewModel.SearchState.Results ->
                if (search.bots.isEmpty()) SearchNote(stringResource(R.string.botf_search_empty))
                else search.bots.forEach { BotListRow(it, onClick = { onPick(it) }) }
        }
        if (myBots.isNotEmpty() && search !is BotDirectoryViewModel.SearchState.Results) {
            Text(
                stringResource(R.string.botf_my_bots),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).semantics { heading() },
            )
            myBots.forEach { BotListRow(it, onClick = { onPick(it) }) }
        }
        if (onCreate != null) {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onCreate).heightIn(min = 56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.botf_create_bot), fontWeight = FontWeight.SemiBold, color = com.oshi.messenger.ui.theme.brandInk())
            }
        }
    }
    }
}

@Composable
private fun SearchNote(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
}

/**
 * From a group or channel's menu: "Add a bot". Search (or my bots) → profile → the add
 * dialog, pre-set on THIS chat.
 */
@Composable
fun AddBotSheet(
    group: Group,
    onDismiss: () -> Unit,
    onCreateBot: () -> Unit,
    viewModel: BotDirectoryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val query by viewModel.query.collectAsState()
    val search by viewModel.search.collectAsState()
    val myBots by viewModel.myBots.collectAsState()
    var picked by remember { mutableStateOf<BotPublicProfile?>(null) }
    LaunchedEffect(Unit) {
        viewModel.outcomes.collect { outcome ->
            toastAddOutcome(context, outcome)
            if (outcome == BotDirectoryViewModel.AddOutcome.ADDED) onDismiss()
        }
    }
    OshiModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            Text(
                stringResource(R.string.botf_add_bot),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() },
            )
            BotSearchContent(
                query = query,
                onQuery = viewModel::setQuery,
                search = search,
                myBots = myBots.map { viewModel.profileOf(it) },
                onPick = { picked = it },
                onCreate = onCreateBot,
            )
        }
    }
    picked?.let { profile ->
        AddBotToChatDialog(
            profile = profile,
            channel = BotDirectoryViewModel.isChannel(group),
            chats = listOf(group),
            preselected = group,
            onConfirm = { g, rights -> picked = null; viewModel.addToGroup(profile, g, rights) },
            onDismiss = { picked = null },
        )
    }
}
