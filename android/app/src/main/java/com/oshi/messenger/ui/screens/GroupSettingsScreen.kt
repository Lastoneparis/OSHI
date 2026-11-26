package com.oshi.messenger.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oshi.messenger.R
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.GroupSettings
import com.oshi.messenger.data.model.GroupType
import com.oshi.messenger.network.v2.V2GroupHandleClient
import com.oshi.messenger.service.GroupAdminLog
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.ui.viewmodel.GroupsViewModel
import com.oshi.messenger.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Telegram-style group settings page (docs/GROUP_INFO_SETTINGS_SPEC.md §5).
 * __GROUP_SETTINGS_2026_11_20__
 *
 * Everything an admin changes here goes through `GroupManager`, which checks the permission
 * again, stores the setting and broadcasts the group definition; every receiver re-checks that
 * the sender is an admin before applying it. Members see the values read-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSettingsScreen(
    group: Group,
    groupsViewModel: GroupsViewModel,
    memberName: (String) -> String?,
    onDismiss: () -> Unit,
    onOpenChat: (String) -> Unit,
    onGroupGone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val allSettings by groupsViewModel.groupSettings.collectAsState()
    val autoTranslate by groupsViewModel.autoTranslateGroups.collectAsState()
    val settings = allSettings[group.id.uppercase()] ?: GroupSettings.DEFAULT
    val me = groupsViewModel.myKey
    val isAdmin = groupsViewModel.isGroupAdmin(group.id)
    val canEditDescription = isAdmin || group.canChangeName(me)
    fun name(k: String): String = if (k == me) context.getString(R.string.gs_you)
        else memberName(k) ?: "${k.take(6)}…${k.takeLast(4)}"
    fun toastResult(r: GroupManager.SettingsResult) {
        val msg = when (r) {
            GroupManager.SettingsResult.OK -> null
            GroupManager.SettingsResult.NOT_ALLOWED -> R.string.gs_admin_only_hint
            GroupManager.SettingsResult.TAKEN -> R.string.gs_handle_taken
            GroupManager.SettingsResult.INVALID -> R.string.gs_handle_invalid
            GroupManager.SettingsResult.NETWORK -> R.string.gs_handle_error
            GroupManager.SettingsResult.UNKNOWN_GROUP -> R.string.error
        }
        msg?.let { Toast.makeText(context, context.getString(it), Toast.LENGTH_SHORT).show() }
    }

    val admins = group.adminPublicKeys.ifEmpty { listOf(group.creatorPublicKey) }.filter { it.isNotEmpty() }.distinct()
    var editor by remember(group.id) { mutableStateOf<String?>(null) }
    var webBusy by remember(group.id) { mutableStateOf(false) }
    fun webToast(r: com.oshi.messenger.service.channelweb.ChannelWebPublisher.Result) {
        val msg = when (r) {
            com.oshi.messenger.service.channelweb.ChannelWebPublisher.Result.OK -> null
            com.oshi.messenger.service.channelweb.ChannelWebPublisher.Result.NOT_ALLOWED -> R.string.gs_admin_only_hint
            com.oshi.messenger.service.channelweb.ChannelWebPublisher.Result.NEEDS_PUBLIC_LINK -> R.string.cw_needs_link
            else -> R.string.cw_error
        }
        msg?.let { Toast.makeText(context, context.getString(it), Toast.LENGTH_LONG).show() }
    }
    var memberMenuFor by remember(group.id) { mutableStateOf<String?>(null) }
    var events by remember(group.id) { mutableStateOf(emptyList<GroupAdminLog.Event>()) }
    LaunchedEffect(group.id, group.updatedAt, settings) { events = groupsViewModel.adminLog(group.id) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag("group_settings"),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.gs_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cancel))
                        }
                    }
                )
            }
        ) { pad ->
            Column(
                Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp).padding(bottom = 32.dp)
            ) {
                // Header
                Text(group.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Text(
                    stringResource(R.string.gs_members_count, group.memberPublicKeys.size) +
                        if (settings.isPublic) " · oshi-messenger.com/g/${settings.handle}" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp
                )
                if (settings.dissolved) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.gs_dissolved_banner), color = MaterialTheme.colorScheme.error)
                }

                // Description
                Section(stringResource(R.string.gs_description)) {
                    SettingRow(
                        title = group.description.ifBlank { stringResource(R.string.gs_description_hint) },
                        muted = group.description.isBlank(),
                        enabled = canEditDescription,
                        tag = "gs_row_description"
                    ) { editor = "description" }
                }

                // Type + link
                Section(stringResource(R.string.gs_type)) {
                    SettingRow(
                        title = stringResource(R.string.gs_type),
                        value = stringResource(if (settings.isPublic) R.string.gs_type_public else R.string.gs_type_private),
                        enabled = isAdmin, tag = "gs_row_type"
                    ) { editor = "type" }
                    val link = if (settings.isPublic) GroupSettings.publicLink(settings.handle!!)
                        else if (isAdmin || group.effectiveGroupType != GroupType.ADMIN_ONLY) groupsViewModel.inviteLink(group.id) else null
                    if (link != null) {
                        Text(
                            stringResource(if (settings.isPublic) R.string.gs_public_link else R.string.gs_invite_link),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(link, fontSize = 13.sp, maxLines = 2, modifier = Modifier.testTag("gs_link"))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(link))
                                Toast.makeText(context, context.getString(R.string.gs_link_copied), Toast.LENGTH_SHORT).show()
                            }) { Text(stringResource(R.string.gs_copy_link)) }
                            TextButton(onClick = {
                                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                                context.startActivity(Intent.createChooser(i, null))
                            }) { Text(stringResource(R.string.gs_share_link)) }
                            if (isAdmin && !settings.isPublic) {
                                TextButton(onClick = { editor = "reset" }) { Text(stringResource(R.string.gs_reset_link)) }
                            }
                        }
                    }
                    Footer(stringResource(if (settings.isPublic) R.string.gs_type_public_footer else R.string.gs_type_private_footer))
                }

                // Permissions / posting / reactions / welcome / DMs
                Section(stringResource(R.string.gs_permissions)) {
                    SettingRow(
                        title = stringResource(R.string.gs_permissions),
                        value = stringResource(when (group.effectiveGroupType) {
                            GroupType.ADMIN_ONLY -> R.string.gs_perm_admins
                            GroupType.COLLABORATIVE -> R.string.gs_perm_all
                            GroupType.PUBLIC -> R.string.gs_perm_open
                        }),
                        enabled = isAdmin, tag = "gs_row_permissions"
                    ) { editor = "permissions" }
                    SettingRow(
                        title = stringResource(R.string.gs_who_can_post),
                        value = stringResource(if (settings.postPolicy == GroupSettings.PostPolicy.ADMINS) R.string.gs_post_admins else R.string.gs_post_everyone),
                        enabled = isAdmin, tag = "gs_row_post"
                    ) { editor = "post" }
                    // §2.12.6 row 5b — only meaningful in channel mode; the value is kept otherwise.
                    if (settings.postPolicy == GroupSettings.PostPolicy.ADMINS) {
                        SwitchRow(
                            stringResource(R.string.gs_comments), settings.comments, enabled = isAdmin, tag = "gs_switch_comments"
                        ) { on -> toastResult(groupsViewModel.updateGroupSettings(group.id) { it.copy(comments = on) }) }
                        Footer(stringResource(R.string.gs_comments_footer))
                    }
                    SettingRow(
                        title = stringResource(R.string.gs_reactions),
                        value = when (settings.reactions) {
                            GroupSettings.Reactions.ALL -> stringResource(R.string.gs_reactions_all)
                            GroupSettings.Reactions.NONE -> stringResource(R.string.gs_reactions_none)
                            GroupSettings.Reactions.SOME -> settings.allowedReactions.joinToString(" ")
                        },
                        enabled = isAdmin, tag = "gs_row_reactions"
                    ) { editor = "reactions" }
                    SettingRow(
                        title = stringResource(R.string.gs_welcome),
                        value = settings.welcome.ifBlank { stringResource(R.string.gs_welcome_off) },
                        enabled = isAdmin, tag = "gs_row_welcome"
                    ) { editor = "welcome" }
                    SwitchRow(
                        stringResource(R.string.gs_admin_dms), settings.adminDMs, enabled = isAdmin, tag = "gs_switch_dms"
                    ) { on -> toastResult(groupsViewModel.updateGroupSettings(group.id) { it.copy(adminDMs = on) }) }
                    if (!isAdmin) Footer(stringResource(R.string.gs_admin_only_hint))
                    if (settings.adminDMs && !isAdmin) {
                        SettingRow(title = stringResource(R.string.gs_message_admin), tag = "gs_row_message_admin") { editor = "dm" }
                    }
                }

                // __CHANNEL_WEB_2026_11_26__ Public web preview (PUBLIC_CHANNEL_WEB_SPEC §9.1):
                // channels only (admins post); needs the public handle; admins toggle it.
                if (settings.postPolicy == GroupSettings.PostPolicy.ADMINS && !settings.dissolved) {
                    Section(stringResource(R.string.cw_title)) {
                        if (!settings.isPublic) {
                            Footer(stringResource(R.string.cw_needs_link))
                            if (isAdmin) TextButton(onClick = { editor = "type" }, modifier = Modifier.testTag("cw_create_link")) {
                                Text(stringResource(R.string.cw_create_link))
                            }
                        } else {
                            val handle = settings.handle!!
                            SwitchRow(
                                stringResource(R.string.cw_toggle), settings.webPreview,
                                enabled = isAdmin && !webBusy, tag = "cw_switch"
                            ) { on -> editor = if (on) "web_on" else "web_off" }
                            Footer(stringResource(R.string.cw_footer, handle))
                            if (settings.webPreview) {
                                val webLink = com.oshi.messenger.service.channelweb.ChannelWebLinks.channelUrl(handle)
                                Text(webLink, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp).testTag("cw_link"))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        clipboard.setText(AnnotatedString(webLink))
                                        Toast.makeText(context, context.getString(R.string.gs_link_copied), Toast.LENGTH_SHORT).show()
                                    }) { Text(stringResource(R.string.gs_copy_link)) }
                                    TextButton(onClick = {
                                        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, webLink)
                                        context.startActivity(Intent.createChooser(i, null))
                                    }) { Text(stringResource(R.string.gs_share_link)) }
                                }
                                SwitchRow(
                                    stringResource(R.string.cw_indexing), settings.webIndexing,
                                    enabled = isAdmin && !webBusy, tag = "cw_switch_indexing"
                                ) { on ->
                                    webBusy = true
                                    scope.launch {
                                        webToast(groupsViewModel.setChannelWebIndexing(group.id, on))
                                        webBusy = false
                                    }
                                }
                                Footer(stringResource(R.string.cw_indexing_footer))
                            }
                        }
                    }
                }

                Section(stringResource(R.string.gs_auto_translate)) {
                    SwitchRow(stringResource(R.string.gs_auto_translate), group.id.uppercase() in autoTranslate, tag = "gs_switch_translate") {
                        groupsViewModel.setAutoTranslate(group.id, it)
                    }
                    Footer(stringResource(R.string.gs_auto_translate_footer))
                }

                // Administrators
                Section(stringResource(R.string.gs_admins)) {
                    admins.forEach { k -> MemberLine(name(k), if (k == group.creatorPublicKey) stringResource(R.string.group_chat_creator) else null) {
                        if (isAdmin && k != me) memberMenuFor = k
                    } }
                }
                // Members
                Section(stringResource(R.string.gs_members) + " (${group.memberPublicKeys.size})") {
                    group.memberPublicKeys.forEach { k ->
                        MemberLine(name(k), if (k in admins) stringResource(R.string.group_chat_admin) else null) {
                            if (isAdmin && k != me) memberMenuFor = k
                        }
                    }
                }
                if (isAdmin) {
                    Section(stringResource(R.string.gs_banned)) {
                        if (group.evictedMemberKeys.isEmpty()) Footer(stringResource(R.string.gs_banned_empty))
                        group.evictedMemberKeys.forEach { k ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(name(k), Modifier.weight(1f))
                                TextButton(onClick = { toastResult(groupsViewModel.unbanMember(group.id, k)) }) {
                                    Text(stringResource(R.string.gs_unban))
                                }
                            }
                        }
                    }
                    Section(stringResource(R.string.gs_recent_events)) {
                        if (events.isEmpty()) Footer(stringResource(R.string.gs_recent_events_empty))
                        events.asReversed().take(100).forEach { e -> EventLine(e, ::name) }
                    }
                }

                // Leave / delete
                Spacer(Modifier.height(24.dp))
                if (isAdmin) {
                    OutlinedButton(
                        onClick = { editor = "delete" },
                        modifier = Modifier.fillMaxWidth().testTag("gs_delete"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.gs_delete_group)) }
                } else {
                    OutlinedButton(
                        onClick = { groupsViewModel.leaveGroupNow(group.id); onGroupGone() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.gs_leave_group)) }
                }
            }
        }

        // ---- editors ----
        when (editor) {
            "description" -> TextEditorDialog(stringResource(R.string.gs_description), group.description, GroupSettings.MAX_DESCRIPTION,
                hint = stringResource(R.string.gs_description_hint), onDismiss = { editor = null }) {
                toastResult(groupsViewModel.setGroupDescription(group.id, it)); editor = null
            }
            "welcome" -> TextEditorDialog(stringResource(R.string.gs_welcome), settings.welcome, GroupSettings.MAX_WELCOME,
                hint = stringResource(R.string.gs_welcome_hint), onDismiss = { editor = null }) { text ->
                toastResult(groupsViewModel.updateGroupSettings(group.id) { it.copy(welcome = text.trim()) }); editor = null
            }
            "type" -> TypeEditorDialog(settings, onDismiss = { editor = null }) { handle, preview ->
                scope.launch {
                    val r = groupsViewModel.setGroupVisibility(group.id, handle, preview)
                    toastResult(r)
                    if (r == GroupManager.SettingsResult.OK) editor = null
                }
            }
            "permissions" -> ChoiceDialog(
                stringResource(R.string.gs_permissions),
                listOf(GroupType.ADMIN_ONLY to R.string.gs_perm_admins, GroupType.COLLABORATIVE to R.string.gs_perm_all, GroupType.PUBLIC to R.string.gs_perm_open),
                group.effectiveGroupType, onDismiss = { editor = null }
            ) { toastResult(groupsViewModel.setGroupPermissions(group.id, it)); editor = null }
            "post" -> ChoiceDialog(
                stringResource(R.string.gs_who_can_post),
                listOf(GroupSettings.PostPolicy.EVERYONE to R.string.gs_post_everyone, GroupSettings.PostPolicy.ADMINS to R.string.gs_post_admins),
                settings.postPolicy, onDismiss = { editor = null }
            ) { p -> toastResult(groupsViewModel.updateGroupSettings(group.id) { it.copy(postPolicy = p) }); editor = null }
            "reactions" -> ReactionsDialog(settings, onDismiss = { editor = null }) { mode, set ->
                toastResult(groupsViewModel.updateGroupSettings(group.id) { it.copy(reactions = mode, allowedReactions = if (mode == GroupSettings.Reactions.SOME) set else emptyList()) })
                editor = null
            }
            "web_on", "web_off" -> {
                val on = editor == "web_on"
                val handle = settings.handle.orEmpty()
                com.oshi.messenger.ui.theme.OshiAlertDialog(
                    onDismissRequest = { editor = null },
                    title = { Text(stringResource(if (on) R.string.cw_enable_title else R.string.cw_disable_title)) },
                    text = { Text(stringResource(if (on) R.string.cw_enable_confirm else R.string.cw_disable_confirm, handle)) },
                    confirmButton = {
                        TextButton(onClick = {
                            editor = null
                            webBusy = true
                            scope.launch {
                                webToast(groupsViewModel.setChannelWebPreview(group.id, on))
                                webBusy = false
                            }
                        }, modifier = Modifier.testTag("cw_confirm")) {
                            Text(
                                stringResource(if (on) R.string.cw_enable_action else R.string.cw_disable_action),
                                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    dismissButton = { TextButton(onClick = { editor = null }) { Text(stringResource(R.string.cancel)) } }
                )
            }
            "reset" -> com.oshi.messenger.ui.theme.OshiAlertDialog(
                onDismissRequest = { editor = null },
                text = { Text(stringResource(R.string.gs_reset_link_confirm)) },
                confirmButton = { TextButton(onClick = { toastResult(groupsViewModel.resetInviteLink(group.id)); editor = null }) { Text(stringResource(R.string.gs_reset_link)) } },
                dismissButton = { TextButton(onClick = { editor = null }) { Text(stringResource(R.string.cancel)) } }
            )
            "dm" -> com.oshi.messenger.ui.theme.OshiAlertDialog(
                onDismissRequest = { editor = null },
                title = { Text(stringResource(R.string.gs_message_admin)) },
                text = { Column { admins.filter { it != me }.forEach { k -> MemberLine(name(k), null) { editor = null; onOpenChat(k) } } } },
                confirmButton = { TextButton(onClick = { editor = null }) { Text(stringResource(R.string.cancel)) } }
            )
            "delete" -> com.oshi.messenger.ui.theme.OshiAlertDialog(
                onDismissRequest = { editor = null },
                title = { Text(stringResource(R.string.gs_delete_group)) },
                text = { Text(stringResource(R.string.gs_delete_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        editor = null
                        scope.launch { toastResult(groupsViewModel.dissolveGroup(group.id)); onGroupGone() }
                    }, modifier = Modifier.testTag("gs_delete_everyone")) { Text(stringResource(R.string.gs_delete_for_everyone), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { editor = null; groupsViewModel.leaveGroupNow(group.id); groupsViewModel.deleteGroupNow(group.id); onGroupGone() }) {
                            Text(stringResource(R.string.gs_delete_for_me))
                        }
                        TextButton(onClick = { editor = null }) { Text(stringResource(R.string.cancel)) }
                    }
                }
            )
        }
        memberMenuFor?.let { k ->
            val isMemberAdmin = k in group.adminPublicKeys
            com.oshi.messenger.ui.theme.OshiAlertDialog(
                onDismissRequest = { memberMenuFor = null },
                title = { Text(name(k)) },
                text = {
                    Column {
                        if (k != group.creatorPublicKey) {
                            TextButton(onClick = {
                                if (isMemberAdmin) groupsViewModel.demoteMember(group.id, k) else groupsViewModel.promoteMember(group.id, k)
                                memberMenuFor = null
                            }) { Text(stringResource(if (isMemberAdmin) R.string.gs_remove_admin else R.string.gs_make_admin)) }
                            if (k in group.memberPublicKeys) TextButton(onClick = {
                                groupsViewModel.removeMemberFromGroup(group.id, k); memberMenuFor = null
                            }) { Text(stringResource(R.string.gs_ban), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { memberMenuFor = null }) { Text(stringResource(R.string.cancel)) } }
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), content = content)
    }
}

@Composable
private fun Footer(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun SettingRow(title: String, value: String? = null, muted: Boolean = false, enabled: Boolean = true, tag: String = "", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f), color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
        if (value != null) Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 1,
            modifier = Modifier.widthIn(max = 180.dp))
        if (enabled) Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, tag: String = "", onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun MemberLine(name: String, badge: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f))
        if (badge != null) Text(badge, fontSize = 12.sp, color = Color(0xFFF5A623))
    }
}

@Composable
private fun EventLine(e: GroupAdminLog.Event, name: (String) -> String) {
    val actor = name(e.actor)
    val subj = e.subject?.let { if (e.kind == GroupAdminLog.RENAMED || e.kind == GroupAdminLog.HANDLE ||
        e.kind == GroupAdminLog.VISIBILITY || e.kind == GroupAdminLog.POST_POLICY || e.kind == GroupAdminLog.TYPE) it else name(it) } ?: ""
    val text = when (e.kind) {
        GroupAdminLog.RENAMED -> stringResource(R.string.gs_ev_renamed, actor, subj)
        GroupAdminLog.DESCRIPTION -> stringResource(R.string.gs_ev_description, actor)
        GroupAdminLog.MEMBER_ADDED -> stringResource(R.string.gs_ev_member_added, actor, subj)
        GroupAdminLog.MEMBER_REMOVED -> stringResource(R.string.gs_ev_member_removed, actor, subj)
        GroupAdminLog.MEMBER_LEFT -> stringResource(R.string.gs_ev_member_left, actor)
        GroupAdminLog.ADMIN_PROMOTED -> stringResource(R.string.gs_ev_admin_promoted, actor, subj)
        GroupAdminLog.ADMIN_DEMOTED -> stringResource(R.string.gs_ev_admin_demoted, actor, subj)
        GroupAdminLog.JOINED_LINK -> stringResource(R.string.gs_ev_joined_link, actor)
        GroupAdminLog.DISSOLVED -> stringResource(R.string.gs_ev_dissolved, actor)
        else -> stringResource(R.string.gs_ev_settings, actor, settingLabel(e.kind))
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(text, fontSize = 14.sp)
        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.at)), fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun settingLabel(kind: String): String = stringResource(when (kind) {
    GroupAdminLog.VISIBILITY, GroupAdminLog.HANDLE -> R.string.gs_type
    GroupAdminLog.INVITE_RESET -> R.string.gs_invite_link
    GroupAdminLog.REACTIONS -> R.string.gs_reactions
    GroupAdminLog.WELCOME -> R.string.gs_welcome
    GroupAdminLog.POST_POLICY -> R.string.gs_who_can_post
    GroupAdminLog.ADMIN_DMS -> R.string.gs_admin_dms
    GroupAdminLog.COMMENTS -> R.string.gs_comments
    GroupAdminLog.TYPE -> R.string.gs_permissions
    GroupAdminLog.PHOTO -> R.string.gs_title
    else -> R.string.gs_title
})

@Composable
private fun TextEditorDialog(title: String, initial: String, max: Int, hint: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    com.oshi.messenger.ui.theme.OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                OutlinedTextField(
                    value = text, onValueChange = { text = GroupSettings.truncate(it, max) },
                    placeholder = { Text(hint) }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag("gs_text_field"),
                    supportingText = { Text("${text.length}/$max") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, modifier = Modifier.testTag("gs_save")) { Text(stringResource(R.string.gs_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, Int>>, selected: T, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    com.oshi.messenger.ui.theme.OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (v, label) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(v) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = v == selected, onClick = { onPick(v) })
                        Text(stringResource(label))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun TypeEditorDialog(current: GroupSettings, onDismiss: () -> Unit, onSave: (handle: String?, preview: Boolean) -> Unit) {
    var public by remember { mutableStateOf(current.isPublic) }
    var handle by remember { mutableStateOf(current.handle.orEmpty()) }
    var preview by remember { mutableStateOf(current.showPreview) }
    var saving by remember { mutableStateOf(false) }
    val valid = GroupSettings.normalizeHandle(handle) != null
    com.oshi.messenger.ui.theme.OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gs_type)) },
        text = {
            Column {
                listOf(false to R.string.gs_type_private, true to R.string.gs_type_public).forEach { (v, label) ->
                    Row(Modifier.fillMaxWidth().clickable { public = v }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = public == v, onClick = { public = v }, modifier = Modifier.testTag(if (v) "gs_type_public" else "gs_type_private"))
                        Text(stringResource(label))
                    }
                }
                if (public) {
                    com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                        OutlinedTextField(
                            value = handle,
                            onValueChange = { handle = it.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' }.take(32) },
                            prefix = { Text("oshi-messenger.com/g/") },
                            singleLine = true,
                            isError = handle.isNotEmpty() && !valid,
                            supportingText = { Text(stringResource(if (handle.isNotEmpty() && !valid) R.string.gs_handle_invalid else R.string.gs_handle_rules)) },
                            modifier = Modifier.fillMaxWidth().testTag("gs_handle_field"),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.gs_show_preview), Modifier.weight(1f), fontSize = 14.sp)
                        Switch(checked = preview, onCheckedChange = { preview = it }, modifier = Modifier.testTag("gs_preview_switch"))
                    }
                    Text(stringResource(R.string.gs_type_public_footer), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.gs_type_private_footer), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (saving) Text(stringResource(R.string.gs_handle_saving), fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && (!public || valid),
                onClick = { saving = true; onSave(if (public) handle else null, preview) },
                modifier = Modifier.testTag("gs_type_save")
            ) { Text(stringResource(R.string.gs_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
    // A failed save leaves the dialog open; let the user try again.
    LaunchedEffect(saving) { if (saving) { kotlinx.coroutines.delay(15_000); saving = false } }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReactionsDialog(current: GroupSettings, onDismiss: () -> Unit, onSave: (GroupSettings.Reactions, List<String>) -> Unit) {
    var mode by remember { mutableStateOf(current.reactions) }
    var picked by remember { mutableStateOf(current.allowedReactions.ifEmpty { GroupSettings.DEFAULT_REACTION_CHOICES.take(3) }) }
    com.oshi.messenger.ui.theme.OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gs_reactions)) },
        text = {
            Column {
                listOf(GroupSettings.Reactions.ALL to R.string.gs_reactions_all, GroupSettings.Reactions.SOME to R.string.gs_reactions_some,
                    GroupSettings.Reactions.NONE to R.string.gs_reactions_none).forEach { (v, label) ->
                    Row(Modifier.fillMaxWidth().clickable { mode = v }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == v, onClick = { mode = v })
                        Text(stringResource(label))
                    }
                }
                if (mode == GroupSettings.Reactions.SOME) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GroupSettings.DEFAULT_REACTION_CHOICES.forEach { e ->
                            com.oshi.messenger.ui.theme.OshiFilterChip(selected = e in picked, onClick = {
                                picked = if (e in picked) picked - e else (picked + e).take(GroupSettings.MAX_ALLOWED_REACTIONS)
                            }, label = { Text(e, fontSize = 18.sp) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(mode, picked) }) { Text(stringResource(R.string.gs_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/**
 * Public link confirmation (spec §3.1 step 3): resolve, show what the admin chose to show,
 * and send nothing until the user taps Join.
 */
@Composable
fun PublicGroupJoinDialog(
    handle: String,
    resolve: suspend (String) -> MainViewModel.PublicGroupLookup,
    onJoin: suspend (V2GroupHandleClient.Resolved) -> Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(handle) { mutableStateOf<MainViewModel.PublicGroupLookup?>(null) }
    LaunchedEffect(handle) { state = resolve(handle) }
    val found = state as? MainViewModel.PublicGroupLookup.Found
    com.oshi.messenger.ui.theme.OshiAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.GroupAdd, null, modifier = Modifier.size(36.dp)) },
        title = { Text(stringResource(R.string.gs_join_title), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                when (val s = state) {
                    null -> Text(stringResource(R.string.gs_link_checking))
                    is MainViewModel.PublicGroupLookup.NotFound -> Text(stringResource(R.string.gs_link_not_found))
                    is MainViewModel.PublicGroupLookup.Network -> Text(stringResource(R.string.gs_handle_error))
                    is MainViewModel.PublicGroupLookup.Found -> {
                        Text(s.resolved.previewName ?: "@${s.resolved.handle}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text("oshi-messenger.com/g/${s.resolved.handle}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        s.resolved.previewDescription?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 14.sp) }
                        s.resolved.previewMemberCount?.let { Text(stringResource(R.string.gs_members_count, it), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        },
        confirmButton = {
            if (found != null) Button(onClick = {
                scope.launch {
                    val ok = onJoin(found.resolved)
                    Toast.makeText(context, context.getString(if (ok) R.string.gs_join_request_sent else R.string.gs_handle_error), Toast.LENGTH_LONG).show()
                    onDismiss()
                }
            }, modifier = Modifier.testTag("gs_join_confirm")) { Text(stringResource(R.string.gs_join_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
