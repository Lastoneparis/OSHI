package com.oshi.messenger.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oshi.messenger.R
import com.oshi.messenger.data.model.ChatListMerge
import com.oshi.messenger.data.model.Conversation
import com.oshi.messenger.data.model.GroupConversation
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.ui.components.ChatSheetItem
import com.oshi.messenger.ui.components.ChatSheetDivider
import com.oshi.messenger.ui.theme.GlassTier
import com.oshi.messenger.ui.theme.glassOpaque
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import com.oshi.messenger.ui.theme.avatarFill
import com.oshi.messenger.ui.theme.glassGlow
import com.oshi.messenger.ui.theme.glassHairline
import com.oshi.messenger.ui.theme.glassLift
import com.oshi.messenger.ui.theme.glassOn
import com.oshi.messenger.ui.theme.glassTextFieldColors
import com.oshi.messenger.ui.theme.thenIf

// ═══════════════════════════════════════════════════════════════════════════
// __MERGED_CHATS_2026_11_18__ « Fusionner Messages et Groupes »
//
// Everything the merged Chats list draws that the two old tabs did not:
// the filter chips, a group row cut to the 1:1 row's exact geometry, the
// "New…" sheet that brings the Groups tab's actions under the one compose
// button, the Discover teaser and the "group nearby" card. The ordering and
// the chips' rules live in `ChatListMerge` (data/model), where they are tested.
//
// The 1:1 rows, the long-press sheet of a 1:1 row, the top bar, the panic
// wordmark and the call picker are ConversationsScreen's own, reused as they
// are: merged mode is a second BODY for that screen, not a second screen.
// ═══════════════════════════════════════════════════════════════════════════

/** The body of ConversationsScreen when the option is on. */
@Composable
internal fun MergedChatsBody(
    conversations: List<Conversation>,
    groups: List<GroupConversation>,
    pinned: Set<String>,
    muted: (String) -> Boolean,
    mentionedGroups: Set<String>,
    nearby: List<GroupManager.DiscoveredGroup>,
    listState: LazyListState,
    onConversationClick: (String) -> Unit,
    onConversationLongClick: (Conversation) -> Unit,
    onGroupClick: (String) -> Unit,
    onGroupLongClick: (GroupConversation) -> Unit,
    onDiscover: () -> Unit,
    onCreateGroup: () -> Unit,
    /** The Groups tab's "sync groups from peers" button; shown beside the chips on the Groups chip. */
    onSyncGroups: (() -> Unit)? = null,
    emptyState: @Composable (footer: @Composable () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    /** The chip selected on first composition (screenshot tests); the user's pick is kept after. */
    initialFilter: ChatListMerge.Filter = ChatListMerge.Filter.ALL,
    /** The Groups sub-filter on first composition (screenshot tests). */
    initialGroupType: com.oshi.messenger.data.model.GroupType? = null,
) {
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    var query by rememberSaveable { mutableStateOf("") }
    // __MERGED_GROUP_SUBFILTER_2026_11_18__ The Groups tab's type filter (Admin-only /
    // Collaborative / Public), offered under the Groups chip. Saved by name: an enum
    // is Serializable, but a null-able name survives every Saver.
    var groupTypeName by rememberSaveable { mutableStateOf<String?>(initialGroupType?.name) }
    val groupType = groupTypeName?.let { n -> com.oshi.messenger.data.model.GroupType.entries.firstOrNull { it.name == n } }
    // Per session, on purpose: a card dismissed today should come back on the
    // next launch only if there is still something new to say.
    var teaserDismissed by rememberSaveable { mutableStateOf(false) }
    var nearbyDismissed by rememberSaveable { mutableStateOf(emptyList<String>()) }

    val isRadio: (String) -> Boolean = {
        com.oshi.messenger.service.lora.LoRaChannelThread.isChannelKey(it) ||
            com.oshi.messenger.service.lora.MeshCoreThread.isChannelKey(it)
    }
    val items = remember(conversations, groups) { ChatListMerge.itemsOf(conversations, groups, isRadio) }
    val counts = remember(items) { ChatListMerge.counts(items) }
    val shown = remember(items, pinned, filter, query, groupType) { ChatListMerge.arrange(items, pinned, filter, query, groupType) }
    val byConversation = remember(conversations) { conversations.associateBy { ChatListMerge.CONVERSATION_PREFIX + it.participantPublicKey } }
    val byGroup = remember(groups) { groups.associateBy { ChatListMerge.GROUP_PREFIX + it.group.id } }
    val joinedIds = remember(groups) { groups.map { it.group.id.lowercase() }.toSet() }
    val nearbyNew = remember(nearby, joinedIds, nearbyDismissed) {
        nearby.filter { it.groupId.lowercase() !in joinedIds && it.groupId !in nearbyDismissed }
            .sortedByDescending { it.timestamp }
    }

    val discoverTeaser: @Composable () -> Unit = {
        DiscoverTeaserCard(
            onDiscover = onDiscover,
            onCreate = onCreateGroup,
            onDismiss = { teaserDismissed = true },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            // First run: the existing "add your first contact" screen, with the
            // Discover card at its foot — the one thing a newcomer can do alone.
            emptyState { if (!teaserDismissed) discoverTeaser() }
            return@Column
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .thenIf(glassOn()) { glassLift(RoundedCornerShape(percent = 50), spread = 6.dp).glassHairline(RoundedCornerShape(percent = 50)) },
            colors = if (glassOn()) glassTextFieldColors() else OutlinedTextFieldDefaults.colors(),
            placeholder = { Text(stringResource(R.string.search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.accessibility_clear_search))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(percent = 50),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                MergedFilterChips(selected = filter, counts = counts, onSelect = { filter = it })
            }
            if (onSyncGroups != null && filter == ChatListMerge.Filter.GROUPS) {
                IconButton(onClick = onSyncGroups) {
                    Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.accessibility_sync_groups))
                }
            }
        }
        if (filter == ChatListMerge.Filter.GROUPS) {
            FilterChipsRow(
                selectedFilter = groupType,
                onFilterSelected = { groupTypeName = it?.name },
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        val listNow by com.oshi.messenger.ui.components.rememberListClock()
        val showTeaser = !teaserDismissed && groups.isEmpty() && query.isBlank() &&
            (filter == ChatListMerge.Filter.ALL || filter == ChatListMerge.Filter.GROUPS)
        val showNearby = nearbyNew.isNotEmpty() && query.isBlank() &&
            (filter == ChatListMerge.Filter.ALL || filter == ChatListMerge.Filter.GROUPS)

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (showNearby) {
                item(key = "nearby") {
                    NearbyGroupsCard(
                        groups = nearbyNew,
                        onOpen = onDiscover,
                        onDismiss = { nearbyDismissed = nearbyDismissed + nearbyNew.map { it.groupId } },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = when {
                            query.isNotBlank() -> stringResource(R.string.conversations_search_no_results)
                            filter == ChatListMerge.Filter.UNREAD -> stringResource(R.string.chats_merged_empty_unread)
                            filter == ChatListMerge.Filter.GROUPS -> stringResource(R.string.groups_no_groups_yet)
                            else -> stringResource(R.string.chats_merged_empty_people)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            items(shown, key = { it.key }) { item ->
                val group = byGroup[item.key]
                if (group != null) {
                    MergedGroupRow(
                        conversation = group,
                        now = listNow,
                        isPinned = com.oshi.messenger.data.model.ConversationPinOrder.isPinned(item.pinKey, pinned),
                        mentionedYou = group.group.id.uppercase(java.util.Locale.US) in mentionedGroups,
                        onClick = { onGroupClick(group.group.id) },
                        onLongClick = { onGroupLongClick(group) },
                    )
                } else {
                    val conversation = byConversation[item.key] ?: return@items
                    ConversationItem(
                        conversation = conversation,
                        now = listNow,
                        isPinned = com.oshi.messenger.data.model.ConversationPinOrder.isPinned(item.pinKey, pinned),
                        isMuted = muted(conversation.participantPublicKey),
                        onClick = { onConversationClick(conversation.participantPublicKey) },
                        onLongClick = { onConversationLongClick(conversation) },
                    )
                }
            }
            if (showTeaser) {
                item(key = "discover-teaser") { discoverTeaser() }
            }
            // Clears the FAB, so the last row can always be scrolled into view.
            item(key = "fab-clearance") { Spacer(Modifier.height(88.dp)) }
        }
    }
}

// ─── Filter chips ───────────────────────────────────────────────────────────

@Composable
internal fun MergedFilterChips(
    selected: ChatListMerge.Filter,
    counts: ChatListMerge.Counts,
    onSelect: (ChatListMerge.Filter) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(start = 12.dp, end = 24.dp, top = 2.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ChatListMerge.Filter.entries.toList(), key = { it.name }) { f ->
            val (label, count) = when (f) {
                ChatListMerge.Filter.ALL -> stringResource(R.string.chats_merged_filter_all) to 0
                ChatListMerge.Filter.UNREAD -> stringResource(R.string.chats_merged_filter_unread) to counts.unread
                ChatListMerge.Filter.GROUPS -> stringResource(R.string.chats_merged_filter_groups) to counts.groups
                ChatListMerge.Filter.PEOPLE -> stringResource(R.string.chats_merged_filter_people) to counts.people
            }
            MergedChip(label = label, count = count, selected = f == selected, onClick = { onSelect(f) })
        }
    }
}

@Composable
private fun MergedChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val glass = glassOn()
    val shape = RoundedCornerShape(percent = 50)
    val container = when {
        selected -> MaterialTheme.colorScheme.primary
        glass -> glassOpaque(GlassTier.Chip)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val ink = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val a11y = if (count > 0) "$label, $count" else label
    Row(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .thenIf(glass && selected) { glassGlow(shape, alpha = 0.22f, spread = 6.dp) }
            .thenIf(glass && !selected) { glassLift(shape, spread = 4.dp, drop = 1.dp).glassHairline(shape, highlight = false) }
            .clip(shape)
            .background(container)
            // One target per chip, announced as a selectable tab with its count.
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = a11y }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = ink, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f) else MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (count > 99) "99+" else count.toString(),
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ─── Group row, at the 1:1 row's geometry ──────────────────────────────────

/**
 * A group in the merged list. Same pitch, avatar size, ring and type scale as
 * `ConversationItem`, so the two kinds read as one list; what says "group" is
 * the small glyph on the avatar (the watch apps' convention) and, on the
 * preview line, the sender's name in front of the message.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun MergedGroupRow(
    conversation: GroupConversation,
    now: Long,
    isPinned: Boolean,
    mentionedYou: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val group = conversation.group
    val context = LocalContext.current
    val controlLabels = remember(context) { OSHIControlPayload.labels(context) }
    val bitmap = com.oshi.messenger.ui.components.rememberAvatarBitmap(group.groupImage)
    val entry = remember(group.id) { com.oshi.messenger.ui.theme.AvatarIdentity.groupEntry(group.id) }
    val preview = groupRowPreview(conversation, controlLabels, stringResource(R.string.video_note_preview))
    val sender = conversation.lastMessage?.takeIf { !it.isFromMe && it.senderPublicKey != "system" }
        ?.let { m -> conversation.memberNames[m.senderPublicKey]?.takeIf { it.isNotBlank() } ?: m.senderName.takeIf { it.isNotBlank() } }
        ?.substringBefore(' ')
    val groupLabel = stringResource(R.string.chats_merged_group_a11y)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.chat_more_options),
            )
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().border(2.dp, OSHIPurple, CircleShape))
            Box(Modifier.size(44.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.group_chat_group_avatar),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.fillMaxSize().avatarFill(entry), contentAlignment = Alignment.Center) {
                        val emoji = group.avatar?.takeIf { it.isNotBlank() && it != "👥" }
                        Text(
                            text = emoji ?: group.name.take(2).uppercase(),
                            fontWeight = FontWeight.Bold,
                            fontSize = if (emoji != null) 20.sp else 15.sp,
                            color = entry.ink,
                        )
                    }
                }
            }
            // The group glyph: says "group" without a word, on every row, at a glance.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(OSHIPurple)
                    .semantics { contentDescription = groupLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Groups, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (isPinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = stringResource(R.string.conversation_pin),
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = group.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (group.isMuted) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.NotificationsOff,
                        contentDescription = stringResource(R.string.group_chat_unmute),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    // The 1:1 rows' format, so the two kinds read the same in one list.
                    text = formatConversationTimestamp(conversation.lastMessage?.timestamp ?: group.createdAt, now),
                    fontSize = 12.sp,
                    color = if (conversation.unreadCount > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        preview == null -> stringResource(R.string.groups_members_count, group.memberPublicKeys.size)
                        sender != null -> "$sender: $preview"
                        else -> preview
                    },
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (mentionedYou) {
                    Spacer(Modifier.width(8.dp))
                    com.oshi.messenger.ui.components.MentionBadge(tint = OSHIPurple)
                }
                if (conversation.unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                        Text(conversation.unreadCount.toString(), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}

// ─── Cards: Discover teaser, group nearby ──────────────────────────────────

@Composable
internal fun DiscoverTeaserCard(
    onDiscover: () -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = glassOn()
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .thenIf(glass) { glassHairline(shape) }
            .clip(shape)
            .background(
                if (glass) Brush.linearGradient(listOf(OSHIPurple.copy(alpha = 0.30f), Color(0xFF0B0B10)))
                else Brush.linearGradient(listOf(OSHIPurple.copy(alpha = 0.16f), Color(0xFFF0478F).copy(alpha = 0.10f)))
            )
            .padding(18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(listOf(OSHIPurple, Color(0xFFF0478F)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Public, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.chats_merged_teaser_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.chats_merged_teaser_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onDiscover,
                    shape = RoundedCornerShape(percent = 50),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f).thenIf(glass) { glassGlow(RoundedCornerShape(percent = 50)) },
                ) {
                    Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.chats_merged_teaser_cta), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onCreate,
                    shape = RoundedCornerShape(percent = 50),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.chats_merged_new_group), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * "Group nearby" — public groups heard on the mesh that the user has not
 * joined. A card IN the list (dismissible, tappable → Discover), not a banner
 * floating over whatever pane is open.
 */
@Composable
internal fun NearbyGroupsCard(
    groups: List<GroupManager.DiscoveredGroup>,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = groups.first()
    val glass = glassOn()
    val shape = RoundedCornerShape(18.dp)
    val members = stringResource(R.string.groups_members_count, first.memberCount)
    val more = if (groups.size > 1) " +${groups.size - 1}" else ""
    Row(
        modifier = modifier
            .fillMaxWidth()
            .thenIf(glass) { glassHairline(shape) }
            .clip(shape)
            .background(if (glass) glassOpaque(GlassTier.Card) else OSHIPurple.copy(alpha = 0.10f))
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(OSHIPurple),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.SettingsInputAntenna, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(
                "${stringResource(R.string.chats_merged_nearby_title)} · $members",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                first.groupName + more,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onOpen,
            shape = RoundedCornerShape(percent = 50),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            modifier = Modifier.heightIn(min = 36.dp),
        ) { Text(stringResource(R.string.groups_join), maxLines = 1) }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

// ─── The "New…" sheet ──────────────────────────────────────────────────────

/**
 * One compose button, one sheet, every way to start something: a chat, a
 * group, a public channel, an invite QR — and, kept from the old menu, a call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewChatSheet(
    onDismiss: () -> Unit,
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit,
    onDiscover: () -> Unit,
    onScanInvite: () -> Unit,
    onCall: ((video: Boolean) -> Unit)?,
) {
    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.chats_merged_new_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp).semantics { heading() },
            )
            NewSheetRow(Icons.Default.PersonAdd, stringResource(R.string.chats_merged_new_chat),
                stringResource(R.string.chats_merged_new_chat_desc), listOf(OSHIPurple, Color(0xFF7B3FF2)), onNewChat)
            NewSheetRow(Icons.Default.GroupAdd, stringResource(R.string.chats_merged_new_group),
                stringResource(R.string.chats_merged_new_group_desc), listOf(Color(0xFF7B3FF2), Color(0xFFF0478F)), onNewGroup)
            NewSheetRow(Icons.Default.Public, stringResource(R.string.chats_merged_discover),
                stringResource(R.string.chats_merged_discover_desc), listOf(Color(0xFF0A9F6E), Color(0xFF2BC4A0)), onDiscover)
            NewSheetRow(Icons.Default.QrCodeScanner, stringResource(R.string.chats_merged_scan_invite),
                stringResource(R.string.chats_merged_scan_invite_desc), listOf(Color(0xFF2F6FED), Color(0xFF4FC3F7)), onScanInvite)
            if (onCall != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.chats_merged_calls_header),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = { onCall(false) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.call_voice), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FilledTonalButton(onClick = { onCall(true) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.call_video), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewSheetRow(icon: ImageVector, title: String, description: String, tile: List<Color>, onClick: () -> Unit) {
    val glass = glassOn()
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .thenIf(glass) { glassHairline(shape, highlight = false) }
            .clip(shape)
            .background(if (glass) glassOpaque(GlassTier.Card) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(tile)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ─── Long-press sheet of a group row ───────────────────────────────────────

@Composable
internal fun GroupRowSheet(
    conversation: GroupConversation,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onInfo: () -> Unit,
    onLeave: () -> Unit,
) {
    val group = conversation.group
    com.oshi.messenger.ui.components.ChatOverflowSheet(onDismissRequest = onDismiss) {
        Text(
            text = group.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        ChatSheetItem(
            text = { Text(stringResource(if (pinned) R.string.conversation_unpin else R.string.conversation_pin)) },
            onClick = onTogglePin,
            leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
        )
        ChatSheetItem(
            text = { Text(stringResource(if (group.isMuted) R.string.group_chat_unmute else R.string.group_chat_mute)) },
            onClick = onToggleMute,
            leadingIcon = {
                Icon(if (group.isMuted) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff, contentDescription = null)
            },
        )
        ChatSheetItem(
            text = { Text(stringResource(R.string.group_info)) },
            onClick = onInfo,
            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
        )
        ChatSheetDivider()
        ChatSheetItem(
            text = { Text(stringResource(R.string.groups_leave_group)) },
            onClick = onLeave,
            leadingIcon = { Icon(Icons.Default.ExitToApp, contentDescription = null) },
            destructive = true,
        )
    }
}
