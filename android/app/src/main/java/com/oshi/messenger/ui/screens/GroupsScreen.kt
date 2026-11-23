package com.oshi.messenger.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.data.model.Conversation
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.GroupConversation
import com.oshi.messenger.data.model.GroupMember
import com.oshi.messenger.data.model.GroupType
import com.oshi.messenger.data.model.MediaType
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.ui.theme.*
import com.oshi.messenger.ui.viewmodel.CreateGroupState
import com.oshi.messenger.ui.viewmodel.DiscoveryPhase
import com.oshi.messenger.ui.viewmodel.GroupsViewModel
import com.oshi.messenger.ui.viewmodel.RecipientKey
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.oshi.messenger.R

// ============================================================================
// BRAND COLOR (matching iOS #5616EC)
// ============================================================================
private val GroupBrandColor = Color(0xFF5616EC)
private val GroupBrandColorLight = Color(0xFF7B3FF2)

// ============================================================================
// GROUPS SCREEN - Main Entry Point
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    onGroupClick: (String) -> Unit,
    onDiscoverGroups: () -> Unit = {},
    onBack: (() -> Unit)? = null,   // iOS's GroupsListView inside ChatsTabView has no back chevron
    onNavigateToChat: (String) -> Unit = {},
    viewModel: GroupsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    // Trigger group sync from peers when this screen opens
    LaunchedEffect(Unit) {
        viewModel.requestGroupSync()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.groups_title_header),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.accessibility_back_button))
                        }
                    }
                },
                actions = {
                    // Discover public groups — icon-only to free header width.
                    IconButton(onClick = onDiscoverGroups) {
                        Icon(
                            Icons.Default.Public,
                            contentDescription = stringResource(R.string.groups_discover),
                            tint = GroupBrandColor
                        )
                    }

                    // Sync groups from peers
                    IconButton(onClick = { viewModel.requestGroupSync() }) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = stringResource(R.string.accessibility_sync_groups),
                            tint = if (uiState.isLoading) GroupBrandColor.copy(alpha = 0.4f) else GroupBrandColor
                        )
                    }

                    // Create group button
                    IconButton(onClick = { viewModel.showCreateGroup() }) {
                        Icon(
                            Icons.Default.AddCircle,
                            contentDescription = stringResource(R.string.group_create),
                            tint = GroupBrandColor
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Bar
            SearchBar(
                query = uiState.searchQuery,
                onQueryChange = { viewModel.updateSearchQuery(it) },
                onClear = {
                    viewModel.clearSearch()
                    focusManager.clearFocus()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Filter Chips
            FilterChipsRow(
                selectedFilter = uiState.selectedFilter,
                onFilterSelected = { viewModel.setTypeFilter(it) },
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Groups List or Empty State
            if (uiState.filteredGroups.isEmpty() && uiState.groups.isEmpty()) {
                EmptyGroupsView(
                    onCreateGroup = { viewModel.showCreateGroup() },
                    modifier = Modifier.fillMaxSize()
                )
            } else if (uiState.filteredGroups.isEmpty()) {
                // No results for current filter/search
                NoResultsView(modifier = Modifier.fillMaxSize())
            } else {
                GroupsList(
                    groups = uiState.filteredGroups,
                    onGroupClick = onGroupClick,
                    onGroupInfo = { viewModel.selectGroup(it) },
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    GroupsSheetsHost(viewModel = viewModel, onNavigateToChat = onNavigateToChat)
}

/**
 * __MERGED_CHATS_2026_11_18__ The Groups tab's create sheet, group-info sheet and
 * member-actions dialog, lifted out of [GroupsScreen] unchanged so the merged
 * chat list (ConversationsScreen, merged mode) opens the very same ones from the
 * very same [GroupsViewModel] state — one create flow, one info sheet, whichever
 * list the user is on.
 */
@Composable
internal fun GroupsSheetsHost(
    viewModel: GroupsViewModel,
    onNavigateToChat: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val createGroupState by viewModel.createGroupState.collectAsState()
    val context = LocalContext.current
    var showMemberActions by remember { mutableStateOf(false) }
    var selectedMemberKey by remember { mutableStateOf<String?>(null) }

    // Create Group Bottom Sheet
    if (uiState.showCreateGroupSheet) {
        val contacts by viewModel.contacts.collectAsState()
        CreateGroupSheet(
            state = createGroupState,
            contacts = contacts,
            myPublicKey = viewModel.myPublicKey,
            onDismiss = { viewModel.dismissCreateGroup() },
            onGroupNameChange = { viewModel.updateGroupName(it) },
            onGroupTypeSelect = { viewModel.selectGroupType(it) },
            onAvatarSelect = { viewModel.selectAvatar(it) },
            onMemberKeyChange = { viewModel.updateNewMemberKey(it) },
            onAddMember = { viewModel.addMember() },
            onAddFromContacts = { viewModel.addMembersFromContacts(it) },
            onRemoveMember = { viewModel.removeMember(it) },
            onCreateGroup = { viewModel.createGroup() },
            canCreate = viewModel.canCreateGroup(),
            onClearError = { viewModel.clearCreateError() }
        )
    }

    // Group Info Bottom Sheet
    if (uiState.showGroupInfoSheet && uiState.selectedGroup != null) {
        GroupInfoSheet(
            group = uiState.selectedGroup!!,
            isAdmin = viewModel.isAdmin(uiState.selectedGroup!!),
            unreadCount = viewModel.getUnreadCount(uiState.selectedGroup!!.id),
            onDismiss = { viewModel.dismissGroupInfo() },
            onLeaveGroup = { viewModel.leaveGroup(it) },
            onDeleteGroup = { viewModel.deleteGroup(it) },
            onMemberClick = { memberKey ->
                selectedMemberKey = memberKey
                showMemberActions = true
            },
            viewModel = viewModel
        )
    }

    // Member Actions Dialog
    if (showMemberActions && selectedMemberKey != null) {
        val clipboardManager = LocalClipboardManager.current
        GroupMemberActionsDialog(
            onDismiss = {
                showMemberActions = false
                selectedMemberKey = null
            },
            onReplyInPrivate = {
                val key = selectedMemberKey!!
                showMemberActions = false
                selectedMemberKey = null
                viewModel.dismissGroupInfo()
                onNavigateToChat(key)
            },
            onAddToContacts = {
                val key = selectedMemberKey!!
                viewModel.addContact(key)
                android.widget.Toast.makeText(context, context.getString(R.string.group_add_to_contacts), android.widget.Toast.LENGTH_SHORT).show()
                showMemberActions = false
                selectedMemberKey = null
            },
            onCopyKey = {
                val key = selectedMemberKey!!
                clipboardManager.setText(AnnotatedString(key))
                android.widget.Toast.makeText(context, context.getString(R.string.groups_key_copied), android.widget.Toast.LENGTH_SHORT).show()
                showMemberActions = false
                selectedMemberKey = null
            }
        )
    }
}

// ============================================================================
// SEARCH BAR
// ============================================================================

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current

    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    stringResource(R.string.groups_search_placeholder),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.weight(1f)
        )

        AnimatedVisibility(visible = query.isNotEmpty()) {
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.cancel), color = GroupBrandColor)
            }
        }
    }
}

// ============================================================================
// FILTER CHIPS ROW
// ============================================================================

// __MERGED_GROUP_SUBFILTER_2026_11_18__ internal: the merged chat list shows this
// same row under its "Groups" chip (MergedChats.kt).
@Composable
internal fun FilterChipsRow(
    selectedFilter: GroupType?,
    onFilterSelected: (GroupType?) -> Unit,
    modifier: Modifier = Modifier
) {
    // 🔧 P2 FIX (device walkthrough #2, 2026-08-13): the last filter chip
    // ("Public") ran into the right screen edge with no end padding — the row
    // scrolls, but symmetric `horizontal = 16.dp` content padding leaves the
    // final chip flush against the bezel at rest, reading as clipped. Asymmetric
    // padding gives the tail room without shifting the leading chip.
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // "All" chip
        item {
            ModernFilterChip(
                title = stringResource(R.string.groups_filter_all),
                icon = null,
                color = GroupBrandColor,
                isSelected = selectedFilter == null,
                onClick = { onFilterSelected(null) }
            )
        }

        // Type-specific chips
        items(GroupType.entries.toTypedArray()) { type ->
            ModernFilterChip(
                title = stringResource(groupTypeLabel(type)),
                icon = when (type) {
                    GroupType.ADMIN_ONLY -> Icons.Default.Shield
                    GroupType.COLLABORATIVE -> Icons.Default.Group
                    GroupType.PUBLIC -> Icons.Default.Public
                },
                color = when (type) {
                    GroupType.ADMIN_ONLY -> OSHIRed
                    GroupType.COLLABORATIVE -> GroupBrandColor
                    GroupType.PUBLIC -> OSHIGreen
                },
                isSelected = selectedFilter == type,
                onClick = { onFilterSelected(type) }
            )
        }
    }
}

@Composable
private fun ModernFilterChip(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) color else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = title,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp
            )
        }
    }
}

// ============================================================================
// GROUPS LIST
// ============================================================================

@Composable
private fun GroupsList(
    groups: List<GroupConversation>,
    onGroupClick: (String) -> Unit,
    onGroupInfo: (Group) -> Unit,
    viewModel: GroupsViewModel,
    modifier: Modifier = Modifier
) {
    // __RELATIVE_TIMES_FROZE_2026_11_09__ one clock for the list (ListClock.kt).
    val listNow by com.oshi.messenger.ui.components.rememberListClock()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(groups, key = { it.group.id }) { conversation ->
            ModernGroupRowItem(
                conversation = conversation,
                now = listNow,
                onClick = { onGroupClick(conversation.group.id) },
                onInfoClick = { onGroupInfo(conversation.group) },
                viewModel = viewModel
            )
        }
    }
}

// ============================================================================
// MODERN GROUP ROW ITEM - Matching iOS ModernGroupRowView
// ============================================================================

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ModernGroupRowItem(
    conversation: GroupConversation,
    /** __RELATIVE_TIMES_FROZE_2026_11_09__ the list's shared clock. */
    now: Long = System.currentTimeMillis(),
    onClick: () -> Unit,
    onInfoClick: () -> Unit,
    viewModel: GroupsViewModel
) {
    val group = conversation.group
    val groupType = getGroupTypeFromGroup(group)
    val avatarEntry = remember(group.id) { viewModel.getAvatarEntry(group.id) }
    // __MENTIONS_2026_09_23__ "@" pill: an unread message here mentions us.
    val mentionedGroups by viewModel.mentionedGroups.collectAsState()
    val mentionedYou = group.id.uppercase(java.util.Locale.US) in mentionedGroups
    val context = LocalContext.current
    val controlLabels = remember(context) { OSHIControlPayload.labels(context) }

    // __GROUP_PHOTO_FLICKERED_2026_11_04__ Same wrong key as the in-chat avatar had,
    // and worse: this decoded synchronously inside `remember`, so the reference churn
    // blocked the frame on the main thread for every visible row.
    val groupBitmap = com.oshi.messenger.ui.components.rememberAvatarBitmap(group.groupImage)

    // F-12 (iOS 6f2237d + d844b93): the 24 dp info button used to sit INSIDE the
    // row - the very element iOS removed to get the row height down. It moved to a
    // long-press, which is where iOS put its own (a context menu,
    // MessagesListView.swift:718-745).
    //
    // BRAND RING + NAME-UNDER-AVATAR (2026-11-09): same product decision as
    // the 1:1 list (ConversationsScreen.kt) — a `GroupBrandColor` ring (this
    // file's own OSHI-purple alias, already used below) echoes OSHI's
    // app-icon mark, drawn 6dp outside the existing 54dp avatar rather than
    // shrinking it. The group name moves from beside the avatar to a
    // one-line caption underneath; the type badge, timestamp, preview/type
    // line and unread pill all stay, only re-flowed now that the name no
    // longer needs the top line.
    //
    // __GROUP_INFO_WAS_A_LONG_PRESS_ONLY_2026_11_04__ (Anatole) And a long-press is
    // not an affordance: nothing on screen said it existed, so the only route to
    // renaming a group, changing its photo or managing its members was one a user
    // had to be told about. Reported in exactly those words - "we have to press
    // and hold the group to edit, instead of tapping it and having the button".
    //
    // The button comes back, and F-12 still holds, because F-12 was about ROW
    // HEIGHT: this row is 54 dp of avatar plus 4 dp of padding either side, and the
    // control below is capped at 32 dp. It cannot drive the height it was removed
    // for. The long-press stays as well - it is muscle memory for anyone who
    // learned it, and removing a working gesture to add a visible one trades one
    // complaint for another.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onInfoClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Avatar with brand ring and type badge
            Box(
                modifier = Modifier.size(60.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(2.dp, GroupBrandColor, CircleShape)
                )

                // Group Avatar
                if (groupBitmap != null) {
                    Image(
                        bitmap = groupBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.group_chat_group_avatar),
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .shadow(3.dp, CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // Gradient avatar with initials
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            // Shadow BEFORE the fill: after it, the elevated layer's
                            // shadow was composited OVER the disc and showed through
                            // the pastel entries as a grey octagon.
                            .shadow(4.dp, CircleShape, clip = false)
                            .clip(CircleShape)
                            .avatarFill(avatarEntry),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = group.name.take(2).uppercase(),
                            color = avatarEntry.ink,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp
                        )
                    }
                }

                // Type badge — now rides the ring's outer edge instead of the
                // bare avatar's.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(groupType.badgeColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = groupType.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }

            // __NAME_BACK_ABOVE_PREVIEW_2026_11_12__ name back above the preview
            // (Anatole: the caption under the avatar was unreadable).
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Content
        Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
            // Name + timestamp row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = group.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = formatGroupTimestamp(conversation.lastMessage?.timestamp ?: group.createdAt, now),
                    fontSize = 12.sp,
                    color = if (conversation.unreadCount > 0)
                        GroupBrandColor
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // F-3: the preview used to be `lastMessage.content` raw, so a shared
            // location showed the sentinel and its whole JSON body under the group
            // name. Everything goes through the catalog now, exactly as the 1:1
            // list does. iOS: GroupViews.swift:505-532 - a system row previews as
            // nothing, mediaType is answered BEFORE the text (in a group the blob
            // travels in encryptedContent), and the rest goes through previewText.
            val preview = groupRowPreview(conversation, controlLabels, context.getString(R.string.video_note_preview))

            // F-12: the middle line is the LAST MESSAGE. The type/members line only
            // comes back when the group has nothing to show yet, and the unread pill
            // is the trailing element of that line rather than a column of its own.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (preview != null) {
                    Text(
                        text = preview,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(groupTypeLabel(groupType)),
                            fontSize = 12.sp,
                            color = groupType.badgeColor
                        )
                        Text(
                            text = " \u2022 ",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.groups_members_count, group.memberPublicKeys.size),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (mentionedYou) {
                    Spacer(modifier = Modifier.width(8.dp))
                    com.oshi.messenger.ui.components.MentionBadge(tint = com.oshi.messenger.ui.theme.OSHIPurple)
                }
                if (conversation.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    UnreadBadge(count = conversation.unreadCount)
                }
            }
        }

        // __GROUP_INFO_WAS_A_LONG_PRESS_ONLY_2026_11_04__ The visible way in. See
        // the note above the `Row` for why this does not undo F-12.
        IconButton(
            onClick = onInfoClick,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.group_info),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * __MERGED_CHATS_2026_11_18__ The group row's preview line, shared by the Groups
 * tab and the merged chat list so both say the same thing about the same group.
 * Moved verbatim from [ModernGroupRowItem] (F-3 — see the notes inside).
 */
internal fun groupRowPreview(
    conversation: GroupConversation,
    controlLabels: OSHIControlPayload.Labels,
    videoNoteLabel: String,
): String? {
    return conversation.lastMessage?.let { lastMessage ->
        when {
            lastMessage.senderPublicKey == "system" -> null
            // __VIDEO_NOTE_2026_09_24__ spec §4: "Video message", not "Video".
            lastMessage.isVideoNote && lastMessage.mediaType == MediaType.VIDEO ->
                videoNoteLabel
            lastMessage.mediaType != null -> when (lastMessage.mediaType) {
                MediaType.IMAGE -> controlLabels.mediaImage
                MediaType.VIDEO -> controlLabels.mediaVideo
                MediaType.AUDIO -> controlLabels.mediaAudio
                MediaType.DOCUMENT -> controlLabels.mediaDocument
                MediaType.LOCATION -> controlLabels.locationShared
                else -> controlLabels.mediaFile
            }
            // `listPreview`, not `previewText`: the latter hands anything
            // the catalog does not claim straight back, which on a ROW
            // means a ratchet envelope, a drifted media JSON or a
            // half-parsed `v2file` key message prints as itself under the
            // group name. The 1:1 list had these nets inline; the catalog
            // owns them now, so both lists answer the same way.
            else -> OSHIControlPayload.listPreview(lastMessage.content, controlLabels)
                .takeIf { it.isNotBlank() }
        }
    }
}

/**
 * F-17: `GroupType.displayName` / `.description` (`data/model/Group.kt:19-31`)
 * and `GroupTypeUi.displayName` below are English string literals, and neither
 * enum has a `Context`. The screen does, so the label is resolved here. iOS
 * resolves the same names through localized keys and reduces the compact row's
 * badge to a colour pastille (GroupViews.swift:540-545).
 */
private fun groupTypeLabel(type: GroupType): Int = when (type) {
    GroupType.ADMIN_ONLY -> R.string.group_type_admin_only
    GroupType.COLLABORATIVE -> R.string.group_type_collaborative
    GroupType.PUBLIC -> R.string.group_type_public
}

private fun groupTypeLabel(type: GroupTypeUi): Int = when (type) {
    GroupTypeUi.ADMIN_ONLY -> R.string.group_type_admin_only
    GroupTypeUi.COLLABORATIVE -> R.string.group_type_collaborative
    GroupTypeUi.PUBLIC -> R.string.group_type_public
}

private fun groupTypeDescription(type: GroupType): Int = when (type) {
    GroupType.ADMIN_ONLY -> R.string.group_type_admin_only_desc
    GroupType.COLLABORATIVE -> R.string.group_type_collaborative_desc
    GroupType.PUBLIC -> R.string.group_type_public_desc
}

// ============================================================================
// UNREAD BADGE - Red gradient matching iOS
// ============================================================================

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(GroupBrandColor, GroupBrandColorLight)
                )
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = count.toString(),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ============================================================================
// EMPTY GROUPS VIEW - Matching iOS ModernEmptyGroupsView
// ============================================================================

@Composable
private fun EmptyGroupsView(
    onCreateGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon with gradient background
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            GroupBrandColor.copy(alpha = 0.1f),
                            GroupBrandColor.copy(alpha = 0.05f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Group,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = GroupBrandColor
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.groups_no_groups_yet),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.groups_no_groups_desc),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Create button with gradient
        Button(
            onClick = onCreateGroup,
            colors = ButtonDefaults.buttonColors(containerColor = GroupBrandColor),
            shape = RoundedCornerShape(25.dp),
            contentPadding = PaddingValues(horizontal = 30.dp, vertical = 14.dp)
        ) {
            Icon(Icons.Default.AddCircle, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.group_create), fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.groups_discover_nearby),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NoResultsView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.SearchOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.groups_no_results),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============================================================================
// CREATE GROUP SHEET
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateGroupSheet(
    state: CreateGroupState,
    contacts: List<Conversation> = emptyList(),
    myPublicKey: String = "",
    onDismiss: () -> Unit,
    onGroupNameChange: (String) -> Unit,
    onGroupTypeSelect: (GroupType) -> Unit,
    onAvatarSelect: (String) -> Unit,
    onMemberKeyChange: (String) -> Unit,
    onAddMember: () -> Unit,
    onAddFromContacts: (List<String>) -> Unit = {},
    onRemoveMember: (String) -> Unit,
    onCreateGroup: () -> Unit,
    canCreate: Boolean,
    onClearError: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusManager = LocalFocusManager.current
    val sheetContext = LocalContext.current

    // In-process QR scanner via ZXing-android-embedded. Mirrors the QR
    // scanning pattern used by NewMessageScreen + KeyBackupScreen elsewhere
    // in the app — without this, the previous code launched
    // `Intent("com.google.zxing.client.android.SCAN")`, which relies on the
    // standalone "Barcode Scanner" app most users no longer have, and fell
    // through to a Toast saying "QR Scanner not available". Result: from
    // the user's perspective the scan icon did nothing.
    val groupQrScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned ->
            // Strip the same URL prefixes the rest of the app accepts (oshi:,
            // oshi://add?key=, https://oshi-messenger.{org,com}/add?key=…).
            val key = when {
                scanned.startsWith("oshi://add?key=") ->
                    java.net.URLDecoder.decode(scanned.removePrefix("oshi://add?key="), "UTF-8")
                scanned.startsWith("oshi:") -> scanned.removePrefix("oshi:")
                scanned.startsWith("https://oshi-messenger.org/add?key=") ->
                    java.net.URLDecoder.decode(scanned.removePrefix("https://oshi-messenger.org/add?key="), "UTF-8")
                scanned.startsWith("https://oshi-messenger.com/add?key=") ->
                    java.net.URLDecoder.decode(scanned.removePrefix("https://oshi-messenger.com/add?key="), "UTF-8")
                else -> scanned
            }.trim()
            // __GROUP_CREATE_SCROLL_2026_10_12__ `key.length >= 20` was the old
            // gate, the same rule `RecipientKey` was written to replace: it
            // passes any 20-character string and hands the raw scan straight on
            // in whichever base64 spelling the QR happened to carry. Canonicalise
            // here, exactly as `NewMessageScreen` does at :164, so a scanned key
            // is stored in the one spelling the rest of the app compares on.
            val scannedKey = RecipientKey.canonicalRecipientOrNull(key, myPublicKey)
            if (scannedKey != null) {
                onMemberKeyChange(scannedKey)
                onAddMember()
            } else {
                android.widget.Toast.makeText(
                    sheetContext,
                    sheetContext.getString(R.string.new_invalid_qr),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Show error snackbar
    state.error?.let { error ->
        LaunchedEffect(error) {
            // Auto-clear error after 3 seconds
            kotlinx.coroutines.delay(3000)
            onClearError()
        }
    }

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // 🔧 P2 FIX (2026-08-13): bottom safe-area inset — the "Member public key"
        // field and the row below it were clipped by the gesture bar.
        //
        // __GROUP_CREATE_SCROLL_2026_10_12__ …and that inset was treating the
        // symptom. This body DID NOT SCROLL AT ALL, which is why the field was
        // off the bottom in the first place: it is a plain Column of fixed-height
        // content — header 48 + name 81 + three type options 226 + sixteen
        // avatars in three rows 148 + labels and spacers ≈ 814 dp — inside a
        // ModalBottomSheet, which does not scroll its slot. A 360 × 780 dp phone
        // (the HUAWEI nova 5T the Play review came from) leaves ~740 dp, so the
        // member field and the added-members list below it sat under the fold
        // with no gesture that could reach them: swiping the sheet dismisses it.
        //
        // That BLOCKED PRIVATE GROUP CREATION outright, and only private groups:
        // `canCreateGroup` exempts PUBLIC from needing a member
        // (GroupsViewModel:489-493) and the member section is hidden for it, so
        // a public group created fine while admin-only and collaborative could
        // never get their first member and "Create" stayed grey for ever.
        // Reported as « le bug qui bloque la création de groupes privés ».
        //
        // The identical shape was already found and fixed in GroupInfoSheet in
        // this same file on 2026-08-13 (see its comment) — this sheet was
        // missed. The nested contact-picker LazyColumn keeps its
        // `heightIn(max = 200.dp)`, which is what stops an infinite-height
        // measure now that the parent scrolls.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }

                Text(
                    stringResource(R.string.groups_new_group),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )

                TextButton(
                    onClick = {
                        focusManager.clearFocus()
                        onCreateGroup()
                    },
                    enabled = canCreate && !state.isCreating
                ) {
                    if (state.isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            stringResource(R.string.groups_create_button),
                            color = if (canCreate) GroupBrandColor else Color.Gray
                        )
                    }
                }
            }

            // 🔧 P1 FIX (device walkthrough #2, 2026-08-13): "Create" stayed greyed
            // out with a valid name AND a selected type and NOTHING on screen said
            // why. It only enables once ≥ 1 member is added
            // (GroupsViewModel.canCreateGroup:409-418 — public groups are exempt).
            // A disabled control with no stated reason reads as a broken app, so
            // the requirement is now spelled out the moment it is the only thing
            // standing in the way.
            val needsMember = state.groupName.isNotBlank() &&
                state.selectedGroupType != GroupType.PUBLIC &&
                state.addedMembers.isEmpty()
            AnimatedVisibility(visible = needsMember) {
                Text(
                    text = stringResource(R.string.group_create_needs_member),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Error message
            AnimatedVisibility(visible = state.error != null) {
                Surface(
                    color = OSHIRed.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Text(
                        text = state.error ?: "",
                        color = OSHIRed,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Group Name Section
            Text(
                stringResource(R.string.groups_label_group_name),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
            // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
            com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                OutlinedTextField(
                    value = state.groupName,
                    onValueChange = onGroupNameChange,
                    placeholder = { Text(stringResource(R.string.groups_enter_group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GroupBrandColor
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Group Type Section
            Text(
                stringResource(R.string.groups_label_group_type),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            GroupType.entries.forEach { type ->
                GroupTypeOption(
                    type = type,
                    isSelected = state.selectedGroupType == type,
                    onSelect = { onGroupTypeSelect(type) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Avatar Section
            Text(
                stringResource(R.string.groups_label_avatar),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 🔧 4 of the 16 avatars used to be UNREACHABLE. This was a
            // `LazyVerticalGrid(Fixed(6))` pinned to `Modifier.height(120.dp)`,
            // which fits two rows; `AVATAR_OPTIONS` has 16 entries, so 💼 🎵 📚 🏆
            // sat in a clipped third row (uiautomator measured it at 84 px instead
            // of 126 px). A plain chunked Column self-sizes to however many rows
            // the list needs, so adding a 17th avatar can never hide one again —
            // and it drops a lazy container nested in a scrolling parent.
            val avatarSelectionColor = when (state.selectedGroupType) {
                GroupType.ADMIN_ONLY -> OSHIRed
                GroupType.COLLABORATIVE -> GroupBrandColor
                GroupType.PUBLIC -> OSHIGreen
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupsViewModel.AVATAR_OPTIONS.chunked(6).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { avatar ->
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                AvatarOption(
                                    avatar = avatar,
                                    isSelected = state.selectedAvatar == avatar,
                                    selectedColor = avatarSelectionColor,
                                    onSelect = { onAvatarSelect(avatar) }
                                )
                            }
                        }
                        // Keep the last row's cells the same width as the full rows.
                        repeat(6 - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // Member Addition Section (only for non-public groups)
            if (state.selectedGroupType != GroupType.PUBLIC) {
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    if (state.selectedGroupType == GroupType.ADMIN_ONLY)
                        stringResource(R.string.groups_add_members_readonly)
                    else
                        stringResource(R.string.groups_add_members_label),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Pick from Contacts button
                var showContactPicker by remember { mutableStateOf(false) }
                val addedKeys = state.addedMembers.map { it.publicKey }.toSet()
                val availableContacts = contacts.filter {
                    it.participantPublicKey != myPublicKey && it.participantPublicKey !in addedKeys
                }

                if (availableContacts.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showContactPicker = !showContactPicker },
                        shape = RoundedCornerShape(8.dp),
                        color = GroupBrandColor.copy(alpha = 0.1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.PersonAdd,
                                contentDescription = null,
                                tint = GroupBrandColor
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.groups_pick_from_contacts, availableContacts.size),
                                color = GroupBrandColor,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Icon(
                                if (showContactPicker) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = GroupBrandColor
                            )
                        }
                    }

                    if (showContactPicker) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val selectedContacts = remember { mutableStateListOf<String>() }
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(availableContacts) { contact ->
                                val isSelected = contact.participantPublicKey in selectedContacts
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (isSelected) selectedContacts.remove(contact.participantPublicKey)
                                            else selectedContacts.add(contact.participantPublicKey)
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isSelected) GroupBrandColor else Color.Gray,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            contact.participantAlias.ifEmpty { contact.participantPublicKey.take(16) + "..." },
                                            fontWeight = FontWeight.Medium
                                        )
                                        if (contact.participantAlias.isNotEmpty()) {
                                            Text(
                                                contact.participantPublicKey.take(12) + "...",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (selectedContacts.isNotEmpty()) {
                            Button(
                                onClick = {
                                    onAddFromContacts(selectedContacts.toList())
                                    selectedContacts.clear()
                                    showContactPicker = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GroupBrandColor),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.groups_add_contacts, selectedContacts.size))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                    // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                    com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                        OutlinedTextField(
                            value = state.newMemberKey,
                            onValueChange = onMemberKeyChange,
                            placeholder = { Text(stringResource(R.string.groups_member_public_key)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GroupBrandColor
                            )
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // QR Scanner button — uses the in-process ZXing scanner.
                    IconButton(
                        onClick = {
                            val options = ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setPrompt(sheetContext.getString(R.string.new_scan_prompt))
                                setBeepEnabled(true)
                                setOrientationLocked(false)
                                setBarcodeImageEnabled(false)
                            }
                            groupQrScanLauncher.launch(options)
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = GroupBrandColor
                        )
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.new_tab_scan))
                    }

                    // Add button
                    IconButton(
                        onClick = onAddMember,
                        enabled = state.newMemberKey.isNotBlank(),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = if (state.newMemberKey.isNotBlank())
                                state.selectedGroupType.let {
                                    when (it) {
                                        GroupType.ADMIN_ONLY -> OSHIRed
                                        GroupType.COLLABORATIVE -> GroupBrandColor
                                        GroupType.PUBLIC -> OSHIGreen
                                    }
                                }
                            else
                                Color.Gray
                        )
                    ) {
                        Icon(Icons.Default.AddCircle, contentDescription = stringResource(R.string.accessibility_add_member))
                    }
                }

                // Added members list
                if (state.addedMembers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        stringResource(R.string.groups_added_members, state.addedMembers.size),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    state.addedMembers.forEach { member ->
                        MemberListItem(
                            member = member,
                            typeColor = state.selectedGroupType.let {
                                when (it) {
                                    GroupType.ADMIN_ONLY -> OSHIRed
                                    GroupType.COLLABORATIVE -> GroupBrandColor
                                    GroupType.PUBLIC -> OSHIGreen
                                }
                            },
                            onRemove = { onRemoveMember(member.publicKey) }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            } else {
                // Public group info
                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    color = OSHIGreen.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = OSHIGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.groups_public_info),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupTypeOption(
    type: GroupType,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val color = when (type) {
        GroupType.ADMIN_ONLY -> OSHIRed
        GroupType.COLLABORATIVE -> GroupBrandColor
        GroupType.PUBLIC -> OSHIGreen
    }

    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected)
            color.copy(alpha = 0.1f)
        else
            MaterialTheme.colorScheme.surfaceVariant,
        border = if (isSelected)
            androidx.compose.foundation.BorderStroke(1.dp, color)
        else
            null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (type) {
                    GroupType.ADMIN_ONLY -> Icons.Default.Shield
                    GroupType.COLLABORATIVE -> Icons.Default.Group
                    GroupType.PUBLIC -> Icons.Default.Public
                },
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(groupTypeLabel(type)),
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    text = stringResource(groupTypeDescription(type)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = color
                )
            }
        }
    }
}

@Composable
private fun AvatarOption(
    avatar: String,
    isSelected: Boolean,
    selectedColor: Color,
    onSelect: () -> Unit
) {
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected)
            selectedColor.copy(alpha = 0.2f)
        else
            Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = avatar,
                fontSize = 24.sp
            )
        }
    }
}

@Composable
private fun MemberListItem(
    member: GroupMember,
    typeColor: Color,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            tint = typeColor,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = member.name,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                Icons.Default.RemoveCircle,
                contentDescription = stringResource(R.string.group_remove_member),
                tint = OSHIRed,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ============================================================================
// GROUP INFO SHEET
// ============================================================================

/**
 * __MEMBER_LIST_WOULD_NOT_UNROLL_2026_11_04__ How much of a group's roster the
 * info sheet shows, and how much is behind the expander.
 *
 * Pure and separate from the composable for one reason: the bug Anatole
 * reported was a control that did nothing, and the repair introduces the one
 * thing that can silently be wrong about it — an off-by-one between the number
 * of rows drawn and the number the "+N more" line claims. Those two have to be
 * derived from the same place, and a place a test can reach.
 */
internal object GroupMemberListPolicy {

    /** How many rows are drawn before the expander is used. */
    const val COLLAPSED_LIMIT = 5

    fun visible(members: List<String>, expanded: Boolean): List<String> =
        if (expanded) members else members.take(COLLAPSED_LIMIT)

    /**
     * How many are NOT drawn while collapsed. Zero means the expander must not
     * appear at all: "+0 more members" is a control that promises nothing, and
     * for a group of exactly five it is what a naive `size > 5` check would
     * still print one member later.
     */
    fun hiddenCount(members: List<String>): Int =
        (members.size - COLLAPSED_LIMIT).coerceAtLeast(0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupInfoSheet(
    group: Group,
    isAdmin: Boolean,
    unreadCount: Int,
    onDismiss: () -> Unit,
    onLeaveGroup: (String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onMemberClick: (String) -> Unit,
    viewModel: GroupsViewModel
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showInviteQrSheet by remember { mutableStateOf(false) }
    // Inline rename — iOS GroupInfoSheet has an editable title (GroupViews.swift:622-671).
    var isEditingName by remember(group.id) { mutableStateOf(false) }
    var nameDraft by remember(group.id, group.name) { mutableStateOf(group.name) }
    // Add member to an existing group — iOS AddMemberView (GroupViews.swift:6456).
    var showAddMember by remember(group.id) { mutableStateOf(false) }
    var newMemberKey by remember(group.id) { mutableStateOf("") }

    // Local fallback compressor used when MediaCompressionManager isn't injected here:
    // decode → scale to ≤ 1280px longest side → JPEG q=70 → drop quality until ≤ 500 KB.
    fun compressGroupImageBytes(raw: ByteArray): ByteArray {
        val maxBytes = 500 * 1024
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, boundsOpts)
            val origW = boundsOpts.outWidth.coerceAtLeast(1)
            val origH = boundsOpts.outHeight.coerceAtLeast(1)

            val maxDim = 1280
            var inSampleSize = 1
            var halfW = origW / 2; var halfH = origH / 2
            while (halfW / inSampleSize >= maxDim && halfH / inSampleSize >= maxDim) {
                inSampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            val sampled = BitmapFactory.decodeByteArray(raw, 0, raw.size, decodeOpts) ?: return raw
            val longest = maxOf(sampled.width, sampled.height)
            val scaled: Bitmap = if (longest > maxDim) {
                val s = maxDim.toFloat() / longest
                val sw = (sampled.width * s).toInt().coerceAtLeast(1)
                val sh = (sampled.height * s).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(sampled, sw, sh, true).also {
                    if (it !== sampled) sampled.recycle()
                }
            } else sampled

            var quality = 70
            var out: ByteArray
            do {
                val baos = java.io.ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                out = baos.toByteArray()
                quality -= 10
            } while (out.size > maxBytes && quality >= 20)
            scaled.recycle()
            out
        } catch (e: Exception) {
            raw
        } catch (e: OutOfMemoryError) {
            raw
        }
    }

    // PhotoPicker launchers — wired on first use so onClick can simply launch().
    val groupPicturePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: android.net.Uri? ->
        uri?.let {
            try {
                val raw = context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() }
                if (raw != null) {
                    val compressed = compressGroupImageBytes(raw)
                    viewModel.setGroupPicture(group.id, compressed)
                    Toast.makeText(
                        context,
                        context.getString(R.string.group_picture_changed_system),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_failed_load_image),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val groupWallpaperPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: android.net.Uri? ->
        uri?.let {
            try {
                val raw = context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() }
                if (raw != null) {
                    val compressed = compressGroupImageBytes(raw)
                    viewModel.setGroupWallpaper(group.id, compressed)
                    Toast.makeText(
                        context,
                        context.getString(R.string.group_wallpaper_changed_system),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_failed_load_image),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val groupType = getGroupTypeFromGroup(group)
    val avatarEntry = remember(group.id) { viewModel.getAvatarEntry(group.id) }
    val myKey = viewModel.myPublicKey
    // iOS parity: collaborative/public → any member can change picture/wallpaper;
    // admin_only → only admins. Mirrors Group.canChangeWallpaper(myKey).
    val canChangeMedia = group.canChangeWallpaper(myKey)
    // iOS `canChangeName` / `canAddMembers` / `canRemoveMembers` (GroupMessaging.swift:116-123):
    // any member in collaborative/public groups, admins only in admin_only groups.
    val canChangeName = group.canChangeName(myKey)
    val canManageMembers = group.canAddMembers(myKey)
    val inviteUrl = remember(group.id, group.name, myKey) {
        // https://oshi-messenger.com/group?gid=<UUID>&name=<urlencoded>&inviter=<pubkey>
        val name = java.net.URLEncoder.encode(group.name, "UTF-8")
        val inviter = java.net.URLEncoder.encode(myKey, "UTF-8")
        "https://oshi-messenger.com/group?gid=${group.id}&name=$name&inviter=$inviter"
    }

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // 🔧 P1 FIX (device walkthrough #2, 2026-08-13): the Group Info sheet body
        // DID NOT SCROLL — swiping did nothing — so the Members list was
        // permanently clipped (rows rendered 79 px of 126 px) and the control below
        // it was unreachable. It is a plain Column of fixed-height content taller
        // than the sheet. `verticalScroll` makes the body reachable and
        // `navigationBarsPadding` stops the gesture bar eating the last row.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header with Done button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.done), color = GroupBrandColor, fontWeight = FontWeight.SemiBold)
                }
            }

            // Group Avatar
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .avatarFill(avatarEntry),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = group.name.take(2).uppercase(),
                    color = avatarEntry.ink,
                    fontWeight = FontWeight.Bold,
                    fontSize = 36.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Group Name — editable in place, mirroring iOS's GroupInfoSheet title field
            // (GroupViews.swift:622-671). The commit path emits the full iOS `MessageGroup`
            // JSON plus `group_renamed`, both of which iPhones apply.
            if (isEditingName) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                    // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                    com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it.take(60) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.group_name)) }
                        )
                    }
                    IconButton(onClick = {
                        val trimmed = nameDraft.trim()
                        if (trimmed.isNotEmpty() && trimmed != group.name) {
                            val ok = viewModel.renameGroup(group.id, trimmed)
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (ok) R.string.group_system_name_changed
                                    else R.string.error
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        isEditingName = false
                    }) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = OSHIGreen)
                    }
                    IconButton(onClick = {
                        nameDraft = group.name
                        isEditingName = false
                    }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = group.name,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (canChangeName) {
                        IconButton(
                            onClick = {
                                nameDraft = group.name
                                isEditingName = true
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit),
                                tint = GroupBrandColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Type and member count
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = groupType.icon,
                    contentDescription = null,
                    tint = groupType.badgeColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(groupTypeLabel(groupType)),
                    color = groupType.badgeColor,
                    fontSize = 14.sp
                )
                Text(
                    text = "\u2022",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.groups_members_count, group.memberPublicKeys.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Quick Actions — Picture / Wallpaper available to anyone with permission
            // (any member in collaborative/public groups; admin only in admin_only groups).
            // Delete is admin-only. Invite QR is available to all members.
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isAdmin) Icons.Default.Star else Icons.Default.Group,
                            contentDescription = null,
                            tint = if (isAdmin) OSHIOrange else GroupBrandColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (isAdmin) R.string.groups_admin_actions
                                else R.string.groups_quick_actions
                            ),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Invite — open QR sheet (everyone can invite)
                        Surface(
                            onClick = { showInviteQrSheet = true },
                            color = GroupBrandColor.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.QrCode2, contentDescription = null, tint = GroupBrandColor)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.group_invite_button),
                                    fontSize = 11.sp,
                                    color = GroupBrandColor
                                )
                            }
                        }

                        // Change Picture — gated by canChangeMedia (mirrors iOS canChangeGroupPicture)
                        if (canChangeMedia) {
                            Surface(
                                onClick = {
                                    groupPicturePickerLauncher.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                                color = GroupBrandColor.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        Icons.Default.AddAPhoto,
                                        contentDescription = null,
                                        tint = GroupBrandColor
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        stringResource(R.string.group_picture_change),
                                        fontSize = 11.sp,
                                        color = GroupBrandColor
                                    )
                                }
                            }
                        }
                    }

                    if (canChangeMedia || isAdmin) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Wallpaper — gated by canChangeMedia (synced to all members)
                            if (canChangeMedia) {
                                Surface(
                                    onClick = {
                                        groupWallpaperPickerLauncher.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    },
                                    color = GroupBrandColor.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            Icons.Default.Wallpaper,
                                            contentDescription = null,
                                            tint = GroupBrandColor
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            stringResource(R.string.group_wallpaper_change),
                                            fontSize = 11.sp,
                                            color = GroupBrandColor
                                        )
                                    }
                                }
                            }

                            // Delete Group — admin only
                            if (isAdmin) {
                                Surface(
                                    onClick = { showDeleteConfirm = true },
                                    color = OSHIRed.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = OSHIRed
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            stringResource(R.string.group_delete),
                                            fontSize = 11.sp,
                                            color = OSHIRed
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Group ID Section
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Tag,
                            contentDescription = null,
                            tint = GroupBrandColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.groups_group_id), fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = group.id,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Copy button
                    Surface(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(group.id))
                            copied = true
                        },
                        color = if (copied)
                            OSHIGreen.copy(alpha = 0.1f)
                        else
                            GroupBrandColor.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = if (copied) OSHIGreen else GroupBrandColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (copied) "Copied!" else "Copy ID",
                                color = if (copied) OSHIGreen else GroupBrandColor,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Members Section
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Group,
                                contentDescription = null,
                                tint = GroupBrandColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.groups_members_label), fontWeight = FontWeight.SemiBold)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${group.memberPublicKeys.size}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                            // Add member to an EXISTING group — iOS exposes the same action from
                            // the ⋯ menu (AddMemberView, GroupViews.swift:6456). Before this,
                            // Android group membership was frozen at creation.
                            if (canManageMembers) {
                                IconButton(
                                    onClick = { showAddMember = !showAddMember },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.PersonAdd,
                                        contentDescription = stringResource(R.string.group_add_members),
                                        tint = GroupBrandColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (showAddMember && canManageMembers) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                            // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                            com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                                OutlinedTextField(
                                    value = newMemberKey,
                                    onValueChange = { newMemberKey = it },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    label = { Text(stringResource(R.string.groups_member_public_key)) }
                                )
                            }
                            IconButton(onClick = {
                                val key = newMemberKey.trim()
                                if (key.isNotEmpty()) {
                                    val ok = viewModel.addMemberToGroup(group.id, key)
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            if (ok) R.string.group_add_members else R.string.error
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    if (ok) {
                                        newMemberKey = ""
                                        showAddMember = false
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = OSHIGreen)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // __GROUP_MEMBER_NAMES_2026_09_23__ names, not addresses (contact alias >
                    // shared nickname > definition alias > address).
                    val memberNameInputs by viewModel.memberNameInputs.collectAsState()

                    // __MEMBER_LIST_WOULD_NOT_UNROLL_2026_11_04__ (Anatole) The list
                    // showed five members and then the line "+4 autres membres", which
                    // was a bare `Text`: no `clickable`, no state, nothing behind it.
                    // It reads as a control — it is placed where one goes and it names
                    // a quantity you would want to see — and it did nothing at all, so
                    // the other four members were unreachable from a sheet whose whole
                    // subject is the membership.
                    //
                    // Keyed on `group.id`, so opening the sheet for a different group
                    // does not inherit the previous one's expansion.
                    var showAllMembers by remember(group.id) { mutableStateOf(false) }
                    val visibleMembers =
                        GroupMemberListPolicy.visible(group.memberPublicKeys, showAllMembers)
                    visibleMembers.forEachIndexed { index, publicKey ->
                        val isCreator = publicKey == group.creatorPublicKey
                        val isAdminMember = group.adminPublicKeys.contains(publicKey)

                        val myKey = viewModel.myPublicKey
                        val isMe = publicKey == myKey

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .then(
                                    if (!isMe) Modifier.clickable { onMemberClick(publicKey) }
                                    else Modifier
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Member avatar — __AVATAR_COLOUR_LIST_EQUALS_CHAT_2026_11_09__
                            // was one brand gradient for every member; now the
                            // member's own colour, the one the 1:1 list gives them.
                            com.oshi.messenger.ui.theme.ContactAvatar(
                                publicKey = publicKey,
                                size = 40.dp,
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isMe) stringResource(R.string.groups_you)
                                        else viewModel.memberDisplayName(group.id, publicKey, memberNameInputs)
                                            ?: formatAddress(publicKey),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )

                                if (isCreator || isAdminMember) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Star,
                                            contentDescription = null,
                                            tint = OSHIOrange,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            if (isCreator) stringResource(R.string.groups_creator) else stringResource(R.string.group_admin),
                                            fontSize = 11.sp,
                                            color = OSHIOrange
                                        )
                                    }
                                }
                            }

                            // Remove (kick) — iOS `removeMember` (GroupMessaging.swift:1309),
                            // gated by `canRemoveMembers` (swift:119). The creator is never
                            // removable, matching iOS's protected-creator rule (swift:1495).
                            if (!isMe && canManageMembers && !isCreator) {
                                IconButton(
                                    onClick = {
                                        val ok = viewModel.removeMemberFromGroup(group.id, publicKey)
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                if (ok) R.string.group_remove_member else R.string.error
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.PersonRemove,
                                        contentDescription = stringResource(R.string.group_remove_member),
                                        tint = OSHIRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // Chevron for non-self members
                            if (!isMe) {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // __MEMBER_LIST_WOULD_NOT_UNROLL_2026_11_04__ The expander. A row
                    // rather than a line of text, with a chevron that turns over, so it
                    // looks like what it is in both states — and 44 dp tall, because a
                    // 12 sp line of text is well under the touch-target floor and this
                    // one is the only way to the rest of the list.
                    val hiddenMembers = GroupMemberListPolicy.hiddenCount(group.memberPublicKeys)
                    if (hiddenMembers > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showAllMembers = !showAllMembers }
                                .heightIn(min = 44.dp)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                if (showAllMembers) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = GroupBrandColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                if (showAllMembers) {
                                    stringResource(R.string.groups_show_fewer_members)
                                } else {
                                    stringResource(R.string.groups_more_members, hiddenMembers)
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = GroupBrandColor
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Leave Group Button (for non-admins or if there are other admins)
            if (!isAdmin || group.adminPublicKeys.size > 1) {
                Button(
                    onClick = { showLeaveConfirm = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OSHIRed.copy(alpha = 0.1f),
                        contentColor = OSHIRed
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ExitToApp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.groups_leave_group), fontWeight = FontWeight.SemiBold)
                }
            }

            // Share Button
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val shareText = context.getString(R.string.group_invite_share_text, group.name) +
                        "\n\n" + inviteUrl
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, shareText)
                        type = "text/plain"
                    }
                    val shareIntent = Intent.createChooser(sendIntent, context.getString(R.string.groups_share_group))
                    context.startActivity(shareIntent)
                },
                colors = ButtonDefaults.buttonColors(containerColor = GroupBrandColor),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.groups_share_group), fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirm) {
        OshiAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.groups_delete_title)) },
            text = { Text(stringResource(R.string.groups_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteGroup(group.id)
                    }
                ) {
                    Text(stringResource(R.string.delete), color = OSHIRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Leave Confirmation Dialog
    if (showLeaveConfirm) {
        OshiAlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(stringResource(R.string.groups_leave_title)) },
            text = { Text(stringResource(R.string.groups_leave_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirm = false
                        onLeaveGroup(group.id)
                    }
                ) {
                    Text(stringResource(R.string.groups_leave_action), color = OSHIRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 🆕 Invite QR sheet — shows QR + Share button. Encodes the universal-link
    // form so any OSHI user (iOS or Android) can scan and join.
    if (showInviteQrSheet) {
        GroupInviteQrSheet(
            groupName = group.name,
            inviteUrl = inviteUrl,
            onDismiss = { showInviteQrSheet = false }
        )
    }
}

/**
 * Bottom sheet that displays a QR code for the current group invite link
 * (https://oshi-messenger.com/group?gid=…&name=…&inviter=…) plus a Share button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupInviteQrSheet(
    groupName: String,
    inviteUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val qrBitmap = remember(inviteUrl) { generateInviteQr(inviteUrl) }

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // __GROUP_CREATE_SCROLL_2026_10_12__ Third sheet in this file, same
        // treatment as the other two. ~480 dp of fixed content (a 220 dp QR plus
        // its surround, title, name, hint and the Share button) fits a portrait
        // sheet, but not a landscape one, where it stranded the Share button with
        // no gesture to reach it. `navigationBarsPadding` for the same reason it
        // is on GroupInfoSheet: the button is the last row.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.group_invite_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                groupName,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (qrBitmap != null) {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(8.dp)
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.group_invite_title),
                        modifier = Modifier
                            .size(220.dp)
                            .padding(12.dp)
                    )
                }
            } else {
                CircularProgressIndicator()
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                stringResource(R.string.group_invite_scan_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    val text = context.getString(R.string.group_invite_share_text, groupName) +
                        "\n\n" + inviteUrl
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, text)
                        type = "text/plain"
                    }
                    context.startActivity(
                        Intent.createChooser(sendIntent, context.getString(R.string.groups_share_group))
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = GroupBrandColor),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.group_invite_share_link), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Generate a QR-code Bitmap for the given content using ZXing.
 * Mirrors QRCodeScreen.generateQRCode() but kept local to avoid coupling.
 */
private fun generateInviteQr(content: String): android.graphics.Bitmap? {
    return try {
        val writer = com.google.zxing.qrcode.QRCodeWriter()
        val matrix = writer.encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
        val w = matrix.width
        val h = matrix.height
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until w) {
            for (y in 0 until h) {
                bmp.setPixel(
                    x, y,
                    if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                )
            }
        }
        bmp
    } catch (e: Exception) {
        null
    }
}

// ============================================================================
// HELPER FUNCTIONS & EXTENSIONS
// ============================================================================

/** __RELATIVE_TIMES_FROZE_2026_11_09__ [now] comes from the list's shared clock. */
internal fun formatGroupTimestamp(timestamp: Long, now: Long): String {
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Now"
        diff < 3600_000 -> "${diff / 60_000}m"
        diff < 86400_000 -> "${diff / 3600_000}h"
        diff < 604800_000 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun formatAddress(address: String): String {
    return if (address.length > 12) {
        "${address.take(6)}...${address.takeLast(4)}"
    } else {
        address
    }
}

private fun getGroupTypeFromGroup(group: Group): GroupTypeUi = when (group.effectiveGroupType) {
    GroupType.PUBLIC -> GroupTypeUi.PUBLIC
    GroupType.ADMIN_ONLY -> GroupTypeUi.ADMIN_ONLY
    GroupType.COLLABORATIVE -> GroupTypeUi.COLLABORATIVE
}

/**
 * UI representation of GroupType with icon and color
 */
private enum class GroupTypeUi(
    val displayName: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val badgeColor: Color
) {
    ADMIN_ONLY("Admin Only", Icons.Default.Shield, OSHIRed),
    COLLABORATIVE("Collaborative", Icons.Default.Group, GroupBrandColor),
    PUBLIC("Public", Icons.Default.Public, OSHIGreen)
}

// Extension to convert GroupType to GroupTypeUi
private val GroupType.badgeColor: Color
    get() = when (this) {
        GroupType.ADMIN_ONLY -> OSHIRed
        GroupType.COLLABORATIVE -> GroupBrandColor
        GroupType.PUBLIC -> OSHIGreen
    }

private val GroupType.icon: androidx.compose.ui.graphics.vector.ImageVector
    get() = when (this) {
        GroupType.ADMIN_ONLY -> Icons.Default.Shield
        GroupType.COLLABORATIVE -> Icons.Default.Group
        GroupType.PUBLIC -> Icons.Default.Public
    }

// ============================================================================
// DISCOVER GROUPS SCREEN
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverGroupsScreen(
    onBack: () -> Unit,
    onGroupJoined: (String) -> Unit,
    viewModel: GroupsViewModel = hiltViewModel()
) {
    val discoveredGroups by viewModel.discoveredGroups.collectAsState()
    val phase by viewModel.discoveryPhase.collectAsState()
    var joiningGroupId by remember { mutableStateOf<String?>(null) }

    // Discovery is passive, so the screen owns the scan WINDOW: start it on entry,
    // restart it on retry. Without this the spinner had no terminal state at all.
    LaunchedEffect(Unit) { viewModel.startGroupDiscovery() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.groups_discover), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.accessibility_back))
                    }
                },
                actions = {
                    if (phase != DiscoveryPhase.SCANNING) {
                        IconButton(onClick = { viewModel.startGroupDiscovery(clearResults = true) }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.discover_groups_retry)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (phase != DiscoveryPhase.RESULTS) {
            DiscoverGroupsTerminalState(
                phase = phase,
                onRetry = { viewModel.startGroupDiscovery(clearResults = true) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        // Was `"${n} group(s) found nearby"` — hardcoded English AND
                        // an "(s)" that no locale can pluralise.
                        text = pluralStringResource(
                            R.plurals.discover_groups_found,
                            discoveredGroups.size,
                            discoveredGroups.size
                        ),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                items(discoveredGroups) { discovered ->
                    DiscoveredGroupCard(
                        group = discovered,
                        isJoining = joiningGroupId == discovered.groupId,
                        onJoin = {
                            joiningGroupId = discovered.groupId
                            viewModel.joinDiscoveredGroup(discovered)
                            onGroupJoined(discovered.groupId)
                        }
                    )
                }
            }
        }
    }
}

/**
 * The three non-result states of Discover Groups.
 *
 * `SCANNING` keeps the old spinner copy; `EMPTY` and `ERROR` are new — before
 * this the screen had no terminal state whatsoever and spun indefinitely
 * (confirmed on device, unchanged after 30 s).
 */
@Composable
private fun DiscoverGroupsTerminalState(
    phase: DiscoveryPhase,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (phase) {
            DiscoveryPhase.SCANNING -> {
                CircularProgressIndicator(
                    color = GroupBrandColor,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.discover_groups_scanning_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.discover_groups_scanning_body),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.discover_groups_scanning_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            DiscoveryPhase.EMPTY, DiscoveryPhase.ERROR -> {
                val isError = phase == DiscoveryPhase.ERROR
                Icon(
                    imageVector = if (isError) Icons.Default.WifiOff else Icons.Default.SearchOff,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = stringResource(
                        if (isError) R.string.discover_groups_error_title
                        else R.string.discover_groups_none_title
                    ),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (isError) R.string.discover_groups_error_body
                        else R.string.discover_groups_none_body
                    ),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = GroupBrandColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.discover_groups_retry))
                }
            }

            DiscoveryPhase.RESULTS -> Unit // handled by the caller's list branch
        }
    }
}

@Composable
private fun DiscoveredGroupCard(
    group: com.oshi.messenger.service.GroupManager.DiscoveredGroup,
    isJoining: Boolean,
    onJoin: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(OSHIGreen, OSHIGreen.copy(alpha = 0.6f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = group.avatar ?: group.groupName.take(2).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (group.avatar != null) 24.sp else 18.sp
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.groupName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = null,
                        tint = OSHIGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = stringResource(R.string.groups_visibility_public),
                        fontSize = 12.sp,
                        color = OSHIGreen
                    )
                    Text(
                        text = "\u2022",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${group.memberCount} members",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Join button
            Button(
                onClick = onJoin,
                enabled = !isJoining,
                colors = ButtonDefaults.buttonColors(containerColor = OSHIGreen),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (isJoining) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Text(stringResource(R.string.groups_join), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}


// ============================================================================
// MEMBER ACTIONS DIALOG
// ============================================================================

@Composable
private fun GroupMemberActionsDialog(
    onDismiss: () -> Unit,
    onReplyInPrivate: () -> Unit,
    onAddToContacts: () -> Unit,
    onCopyKey: () -> Unit
) {
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_member_actions)) },
        text = {
            Column {
                TextButton(onClick = onReplyInPrivate, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Chat, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.group_reply_in_private))
                    }
                }
                TextButton(onClick = onAddToContacts, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.group_add_to_contacts))
                    }
                }
                TextButton(onClick = onCopyKey, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.copy))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
