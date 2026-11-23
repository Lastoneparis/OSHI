package com.oshi.messenger.ui.screens

import com.oshi.messenger.ui.theme.avatarFill
import com.oshi.messenger.ui.theme.GlassTier
import com.oshi.messenger.ui.theme.glassHairline
import com.oshi.messenger.ui.theme.glassGlow
import com.oshi.messenger.ui.theme.glassOn
import com.oshi.messenger.ui.theme.thenIf
import android.graphics.BitmapFactory
import com.oshi.messenger.ui.theme.OshiDropdownMenu
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import com.oshi.messenger.ui.theme.OSHIPurple
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import com.oshi.messenger.R
import com.oshi.messenger.data.model.CheckInMessage
import com.oshi.messenger.data.model.Conversation
import com.oshi.messenger.data.model.OSHIControlPayload
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import com.oshi.messenger.ui.viewmodel.ConversationsViewModel
import com.oshi.messenger.ui.components.ChatSheetItem
import com.oshi.messenger.ui.viewmodel.LiveShareEntry
import com.oshi.messenger.ui.viewmodel.MeshBadgeState
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onConversationClick: (String) -> Unit,
    onNewMessage: () -> Unit,
    onSettingsClick: () -> Unit,
    onQRCodeClick: (() -> Unit)? = null,
    /** "Scan a friend's code" from the first-run empty state (MessagesListView.swift:1733). */
    onScanCode: (() -> Unit)? = null,
    /** __FIRST_CONTACT_REMOTE_2026_10_10__ Share my invite link. */
    onInviteByLink: (() -> Unit)? = null,
    /** Opens the private mailbox. Optional so existing call sites keep compiling. */
    onMailClick: (() -> Unit)? = null,
    /** Opens a group — the live-share counter needs it for a share started in a group. */
    onGroupClick: ((String) -> Unit)? = null,
    /**
     * __ANTENNA_IS_A_DOOR_2026_10_27__ Owner report N6. The top-bar antenna
     * reported the mesh state and did nothing at all when tapped, although it is
     * the one thing on screen someone presses when they wonder who is nearby.
     * It now opens "Nearby peers" (`PeersScreen`). Optional so the existing call
     * sites keep compiling.
     */
    onNearbyClick: (() -> Unit)? = null,
    /**
     * __NEW_IS_A_MENU_2026_11_13__ (Anatole N4) Place a call to a contact straight
     * from the "New" button's menu. Null hides the two call entries.
     */
    onCallContact: ((publicKey: String, video: Boolean) -> Unit)? = null,
    /**
     * __MERGED_CHATS_2026_11_18__ « Fusionner Messages et Groupes » (Settings ›
     * Appearance). True: this screen lists the groups too, with filter chips, and
     * its compose button opens the "New…" sheet (chat, group, public channel,
     * invite QR, call). False — the default — is the screen exactly as it was.
     */
    merged: Boolean = false,
    /** Merged mode: "Discover public channels" (the Groups tab's globe). */
    onDiscoverGroups: (() -> Unit)? = null,
    viewModel: ConversationsViewModel = hiltViewModel(),
    // (N7) only used for the top-bar avatar button — same source as the
    // Account category it opens into (ProfileEditScreen.kt:68).
    profileViewModel: com.oshi.messenger.ui.viewmodel.ProfileEditViewModel = hiltViewModel()
) {
    val myProfilePicture by profileViewModel.profilePicture.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    // __PIN_A_CONVERSATION_2026_10_28__ (N18)
    val pinnedConversations by viewModel.pinnedConversations.collectAsState()
    // __MUTE_A_CONVERSATION_2026_11_13__ (Anatole N8)
    val mutedConversations by viewModel.mutedConversations.collectAsState()
    // __ROW_SHEET_2026_11_13__ (Anatole N8) the row whose long-press sheet is open.
    var sheetFor by remember { mutableStateOf<Conversation?>(null) }
    // __NEW_IS_A_MENU_2026_11_13__ (Anatole N4)
    var newMenuOpen by rememberSaveable { mutableStateOf(false) }
    var pickContactForCall by remember { mutableStateOf<Boolean?>(null) } // null = closed, else video?
    androidx.activity.compose.BackHandler(enabled = newMenuOpen) { newMenuOpen = false }
    // __MERGED_CHATS_2026_11_18__ merged mode only — see MergedChats.kt.
    var newSheetOpen by rememberSaveable { mutableStateOf(false) }
    var groupSheetFor by remember { mutableStateOf<com.oshi.messenger.data.model.GroupConversation?>(null) }
    var confirmLeave by remember { mutableStateOf<com.oshi.messenger.data.model.GroupConversation?>(null) }
    val groupsViewModel: com.oshi.messenger.ui.viewmodel.GroupsViewModel? =
        if (merged) hiltViewModel() else null
    val meshBadge by viewModel.meshBadge.collectAsState()
    val liveShares by viewModel.liveShares.collectAsState()
    val openLiveShare: (LiveShareEntry) -> Unit = { entry ->
        if (entry.isGroup) onGroupClick?.invoke(entry.share.conversationId)
        else onConversationClick(entry.share.conversationId)
    }

    // __ROW_JITTER_2026_09_22__ « la liste bouge toute seule ». LazyColumn keeps
    // the first VISIBLE key anchored: when a new message moves another
    // conversation to the top, the list silently scrolls to keep the old first
    // row in view and the new one ends up hidden above the viewport — the list
    // appears to shift by itself. If the user was at the very top, stay there.
    //
    // __LIST_ANCHOR_ON_RETURN_2026_09_23__ Second half, measured on an emulator:
    // the first version only acted while this screen stayed composed. Going into
    // a chat and back RECREATES it, and the restored LazyListState re-anchors by
    // key on its first measure — after sending one message, the three newest
    // conversations sat hidden above the top edge with a half-cut row showing.
    // So "the user was at the top" is saved across navigation, it is updated
    // only when a USER scroll settles, and any other movement of the list while
    // it holds is undone.
    val listState = rememberLazyListState()
    var wasAtTop by rememberSaveable { mutableStateOf(true) }

    // __UPDATES_BUTTON_2026_11_02__ (N10)
    var showUpdatesSheet by remember { mutableStateOf(false) }
    // __IN_APP_GUIDES_2026_11_02__
    var showGuides by remember { mutableStateOf(false) }
    // The top-bar QR button duplicated the FAB's own "show my code / scan"
    // flow (onQRCodeClick, also wired below as onShowMyCode) — this slot now
    // opens bubble appearance instead, which previously had no home outside
    // Settings and an active chat's overflow menu.
    var showBubbleAppearanceSheet by remember { mutableStateOf(false) }
    if (showGuides) {
        com.oshi.messenger.ui.components.GuidesSheet(
            initialGuide = com.oshi.messenger.ui.components.Guide.ADD_CONTACT,
            onDismiss = { showGuides = false },
        )
    }
    if (showUpdatesSheet) {
        com.oshi.messenger.ui.components.UpdatesSheet(onDismiss = { showUpdatesSheet = false })
    }
    if (showBubbleAppearanceSheet) {
        BubbleAppearanceSheet(
            onDismiss = { showBubbleAppearanceSheet = false },
            onChanged = { }
        )
    }
    // (N9) Filters by alias only — a public key is not something anyone
    // searches by, and the raw list has nothing else displayed to match on.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredConversations = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) conversations
        else conversations.filter { it.participantAlias.contains(searchQuery, ignoreCase = true) }
    }
    LaunchedEffect(listState) {
        var userScrolled = false
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress
            )
        }.collect { (index, offset, scrolling) ->
            when {
                scrolling -> userScrolled = true
                userScrolled -> {
                    userScrolled = false
                    wasAtTop = index == 0 && offset == 0
                }
                wasAtTop && (index != 0 || offset != 0) -> listState.scrollToItem(0)
            }
        }
    }

    // __PANIC_WIPE_2026_11_03__ Three taps on the wordmark erase this phone.
    //
    // No menu entry and no settings row, on purpose: a panic switch you have to
    // go and find is not one, and a labelled button reading "erase everything"
    // is a button somebody can be made to press. The wordmark is where bitchat
    // puts it, so the muscle memory transfers.
    // (N6, 13/11) ...but it is ARMED only by Settings → Security, off by default:
    // `SettingsViewModel.panicWipe()` checks `PanicGesture.isArmed` and does
    // nothing otherwise. The taps are still counted here, so the wordmark looks
    // and behaves the same whether or not the switch is on.
    val panicViewModel: com.oshi.messenger.ui.viewmodel.SettingsViewModel = hiltViewModel()
    var panicTaps by remember { mutableStateOf(emptyList<Long>()) }

    Scaffold(
        // (N10) Scaffold's own default container colour is
        // `colorScheme.background`, while the top bar and bottom nav both
        // use `colorScheme.surface` — two different values in this theme
        // (Theme.kt: dark #0F0F0F vs #1A1A1A, light #FAFAFA vs white). A
        // conversation row has no background of its own, so this body area
        // IS what shows behind every row, and it was one shade off from the
        // bars immediately above and below it.
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "OSHI",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(
                                // No ripple and no indication: the gesture must
                                // leave no trace that it exists on a screen
                                // somebody else is looking at.
                                interactionSource = remember {
                                    androidx.compose.foundation.interaction.MutableInteractionSource()
                                },
                                indication = null
                            ) {
                                val now = System.currentTimeMillis()
                                val taps =
                                    com.oshi.messenger.util.PanicGesture.retained(panicTaps, now) + now
                                if (com.oshi.messenger.util.PanicGesture.shouldTrigger(taps)) {
                                    panicTaps = emptyList()
                                    panicViewModel.panicWipe()
                                } else {
                                    panicTaps = taps
                                }
                            }
                        )
                        // __TOOLBAR_DRIFT_2026_09_22__ The mesh/LoRa antenna, always
                        // drawn: grey off-grid, green connected (iOS MeshStatusBadge).
                        MeshStatusBadge(meshBadge, onClick = onNearbyClick)
                    }
                },
                actions = {
                    LiveShareCounter(liveShares, onOpen = openLiveShare)
                    IconButton(onClick = { showBubbleAppearanceSheet = true }) {
                        Icon(Icons.Default.Palette, contentDescription = stringResource(R.string.settings_bubble_appearance))
                    }
                    onMailClick?.let { openMail ->
                        // Unread-inbox badge on the mailbox entry point — mirrors the
                        // per-conversation unread Badge below. Count is process-wide
                        // (MailUnread), fed by MailViewModel and bumped by mail pushes.
                        val mailUnread by com.oshi.messenger.mail.MailUnread.count.collectAsState()
                        IconButton(onClick = openMail) {
                            BadgedBox(
                                badge = {
                                    if (mailUnread > 0) {
                                        Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                            Text(
                                                text = if (mailUnread > 99) "99+" else mailUnread.toString(),
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Email, contentDescription = stringResource(R.string.accessibility_mail))
                            }
                        }
                    }
                    // __UPDATES_BUTTON_2026_11_02__ (N10) "a button, next to the mail
                    // button, to be informed when updates come out". Beside Mail, as
                    // asked, and before Settings so it is not the last thing on a row
                    // the thumb has to cross.
                    IconButton(onClick = { showUpdatesSheet = true }) {
                        Icon(
                            Icons.Default.Campaign,
                            contentDescription = stringResource(R.string.accessibility_updates)
                        )
                    }
                    // (N7) duplicated the bottom nav's own Settings tab — now the
                    // user's own avatar, opening straight to Settings → Account.
                    IconButton(onClick = onSettingsClick) {
                        val myAvatar = com.oshi.messenger.ui.components.rememberAvatarBitmap(myProfilePicture)
                        if (myAvatar != null) {
                            Image(
                                bitmap = myAvatar.asImageBitmap(),
                                contentDescription = stringResource(R.string.accessibility_settings),
                                modifier = Modifier.size(28.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.AccountCircle, contentDescription = stringResource(R.string.accessibility_settings))
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            // __THE_PENCIL_SAID_NOTHING_2026_11_08__ A bare pencil was the ONLY
            // way into "new message" — which is also the only place that shows
            // your own QR code and scans someone else's. People could not find
            // how to add anyone, and reported the app as having no way to do it.
            // The screen behind this was complete the whole time; the door had
            // no sign on it.
            //
            // An extended FAB carries the word, which is the Material answer to
            // exactly this ("icon-only harms discoverability") and costs one
            // line. The icon stays, so the shape people already learned survives.
            //
            // (N19) Collapses to icon-only WHILE actively scrolling the list —
            // never on a plain timeout, and never on an empty list. A timeout
            // would undo __THE_PENCIL_SAID_NOTHING_2026_11_08__ above the
            // moment someone pauses to read a conversation, and an empty list
            // is exactly the one screen where this button must stay fully
            // labelled (nothing else on it explains how to add anyone).
            // __NEW_IS_A_MENU_2026_11_13__ (Anatole N4) "New" opens a menu that
            // unfolds from the button — new contact, voice call, video call — instead
            // of going straight to the new-message screen. Built by hand: the M3
            // FloatingActionButtonMenu needs a Compose BOM far newer than the one
            // this module pins (material3 1.1.2).
            if (merged) {
                // __MERGED_CHATS_2026_11_18__ The same labelled "New" button; it
                // opens the "New…" sheet, which also carries the group actions.
                ExtendedFloatingActionButton(
                    onClick = { newSheetOpen = true },
                    expanded = conversations.isEmpty() || !listState.isScrollInProgress,
                    modifier = Modifier.thenIf(glassOn()) { glassGlow(RoundedCornerShape(16.dp)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    text = { Text(stringResource(R.string.new_message_fab)) },
                )
            } else
            NewActionsFab(
                expanded = newMenuOpen,
                labelled = conversations.isEmpty() || !listState.isScrollInProgress,
                showCalls = onCallContact != null,
                onToggle = { newMenuOpen = !newMenuOpen },
                onNewContact = { newMenuOpen = false; onNewMessage() },
                onCall = { video -> newMenuOpen = false; pickContactForCall = video },
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
        if (merged && groupsViewModel != null) {
            val groupsState by groupsViewModel.uiState.collectAsState()
            val mentioned by groupsViewModel.mentionedGroups.collectAsState()
            val nearby by groupsViewModel.discoveredGroups.collectAsState()
            // The Groups tab synced on open; merged, this list is where groups are opened.
            LaunchedEffect(Unit) { groupsViewModel.requestGroupSync() }
            MergedChatsBody(
                conversations = conversations,
                groups = groupsState.groups,
                pinned = pinnedConversations,
                muted = { viewModel.isMuted(it, mutedConversations) },
                mentionedGroups = mentioned,
                nearby = nearby,
                listState = listState,
                onConversationClick = onConversationClick,
                onConversationLongClick = { sheetFor = it },
                onGroupClick = { onGroupClick?.invoke(it) },
                onGroupLongClick = { groupSheetFor = it },
                onDiscover = { onDiscoverGroups?.invoke() },
                onCreateGroup = { groupsViewModel.showCreateGroup() },
                onSyncGroups = { groupsViewModel.requestGroupSync() },
                emptyState = { footer ->
                    EmptyConversationsView(
                        modifier = Modifier.fillMaxSize(),
                        onShowMyCode = onQRCodeClick,
                        onScanCode = onScanCode,
                        onInviteByLink = onInviteByLink,
                        onHowToAdd = { showGuides = true },
                        footer = footer,
                    )
                },
                modifier = Modifier.padding(padding),
            )
        } else
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // (N9) Between the top bar and the list — hidden entirely with
            // an empty conversation list, since there is nothing yet to
            // search.
            if (conversations.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        // __OLED_GLASS_2026_11_16__ OLED black: a glass pill.
                        .thenIf(glassOn()) { glassHairline(RoundedCornerShape(percent = 50)) },
                    colors = if (glassOn()) com.oshi.messenger.ui.theme.glassTextFieldColors()
                        else OutlinedTextFieldDefaults.colors(),
                    // __SEARCH_ON_ONE_LINE_2026_11_13__ (Anatole N1) "Rechercher des
                    // discussions" wrapped onto two lines on a small phone and made
                    // the field look broken. One word, from the app-wide `search`
                    // string (translated in every locale), held to one line.
                    placeholder = {
                        Text(
                            stringResource(R.string.search),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.accessibility_clear_search))
                            }
                        }
                    },
                    singleLine = true,
                    // A pill: 50 % corners stay fully round whatever height the
                    // font scale gives the field (a fixed 24 dp radius on a 56 dp
                    // field was only a rounded rectangle).
                    shape = RoundedCornerShape(percent = 50)
                )
            }
            if (conversations.isEmpty()) {
                EmptyConversationsView(
                    modifier = Modifier.fillMaxSize(),
                    onShowMyCode = onQRCodeClick,
                    onScanCode = onScanCode,
                    onInviteByLink = onInviteByLink,
                    onHowToAdd = { showGuides = true }
                )
            } else if (filteredConversations.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.conversations_search_no_results),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // __RELATIVE_TIMES_FROZE_2026_11_09__ one clock for the list,
                // ticking + refreshed on resume (see ListClock.kt).
                val listNow by com.oshi.messenger.ui.components.rememberListClock()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredConversations, key = { it.participantPublicKey }) { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            now = listNow,
                            // __PIN_A_CONVERSATION_2026_10_28__ (N18)
                            isPinned = com.oshi.messenger.data.model.ConversationPinOrder
                                .isPinned(conversation.participantPublicKey, pinnedConversations),
                            isMuted = viewModel.isMuted(conversation.participantPublicKey, mutedConversations),
                            onClick = { onConversationClick(conversation.participantPublicKey) },
                            onLongClick = { sheetFor = conversation },
                        )
                    }
                }
            }
        }
        // (N4) Dims the list while the menu is open; a tap outside closes it.
        if (newMenuOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        },
                        indication = null
                    ) { newMenuOpen = false }
            )
        }
        }
    }

    // __ROW_SHEET_2026_11_13__ (Anatole N8) Long-press on a row: the same kind of
    // sheet as the one inside a conversation, with the three things asked for.
    // Each entry ACTS — pin and mute flip persisted state the list and the
    // notification gates read; "Open contact" opens the chat with its card up.
    sheetFor?.let { conv ->
        val key = conv.participantPublicKey
        val pinned = com.oshi.messenger.data.model.ConversationPinOrder.isPinned(key, pinnedConversations)
        val muted = viewModel.isMuted(key, mutedConversations)
        val isRadioThread = com.oshi.messenger.service.lora.LoRaChannelThread.isChannelKey(key)
        com.oshi.messenger.ui.components.ChatOverflowSheet(onDismissRequest = { sheetFor = null }) {
            Text(
                text = conv.participantAlias,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            ChatSheetItem(
                text = { Text(stringResource(if (pinned) R.string.conversation_unpin else R.string.conversation_pin)) },
                onClick = { sheetFor = null; viewModel.togglePinned(key) },
                leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) }
            )
            if (!isRadioThread) ChatSheetItem(
                text = { Text(stringResource(if (muted) R.string.group_chat_unmute else R.string.group_chat_mute)) },
                onClick = { sheetFor = null; viewModel.toggleMuted(key) },
                leadingIcon = {
                    Icon(
                        if (muted) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                        contentDescription = null
                    )
                }
            )
            if (!isRadioThread) ChatSheetItem(
                text = { Text(stringResource(R.string.conversation_open_contact)) },
                onClick = {
                    sheetFor = null
                    com.oshi.messenger.ui.components.PendingContactCard.request(key)
                    onConversationClick(key)
                },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
            )
        }
    }

    // (N4) Who to call: the contacts already in the list, minus radio rooms and
    // unsigned radio senders, which cannot be called.
    pickContactForCall?.let { video ->
        val callable = remember(conversations) {
            conversations.filter {
                !com.oshi.messenger.service.lora.LoRaChannelThread.isChannelKey(it.participantPublicKey) &&
                    !it.participantPublicKey.startsWith("lora!")
            }
        }
        com.oshi.messenger.ui.components.ChatOverflowSheet(onDismissRequest = { pickContactForCall = null }) {
            Text(
                text = stringResource(if (video) R.string.fab_pick_contact_video else R.string.fab_pick_contact_voice),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            if (callable.isEmpty()) {
                Text(
                    text = stringResource(R.string.fab_pick_contact_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
            callable.forEach { c ->
                ChatSheetItem(
                    text = { Text(c.participantAlias, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        pickContactForCall = null
                        onCallContact?.invoke(c.participantPublicKey, video)
                    },
                    leadingIcon = {
                        com.oshi.messenger.ui.theme.ContactAvatar(
                            publicKey = c.participantPublicKey,
                            name = c.participantAlias,
                            size = 24.dp,
                        )
                    }
                )
            }
        }
    }

    // ── __MERGED_CHATS_2026_11_18__ merged mode: the "New…" sheet, the group
    // row's sheet, and the Groups tab's own create / info sheets. ──────────────
    if (merged && groupsViewModel != null) {
        val context = LocalContext.current
        val notAnInvite = stringResource(R.string.chats_merged_scan_not_invite)
        // "Scan an invite": a group invite or a contact link goes through the
        // app's own deep-link handling (MainActivity.onNewIntent → handleDeepLink),
        // exactly as if the link had been tapped — same join dialog, same checks.
        val inviteScanner = androidx.activity.compose.rememberLauncherForActivityResult(
            com.journeyapps.barcodescanner.ScanContract()
        ) { result ->
            val scanned = result.contents?.trim().orEmpty()
            if (scanned.isEmpty()) return@rememberLauncherForActivityResult
            val link = com.oshi.messenger.ui.DeepLinkParser.parse(scanned)
            if (link is com.oshi.messenger.ui.OshiDeepLink.NotOurs || link is com.oshi.messenger.ui.OshiDeepLink.Unhandled) {
                android.widget.Toast.makeText(context, notAnInvite, android.widget.Toast.LENGTH_SHORT).show()
            } else {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(scanned))
                            .setPackage(context.packageName)
                    )
                }
            }
        }
        if (newSheetOpen) {
            NewChatSheet(
                onDismiss = { newSheetOpen = false },
                onNewChat = { newSheetOpen = false; onNewMessage() },
                onNewGroup = { newSheetOpen = false; groupsViewModel.showCreateGroup() },
                onDiscover = { newSheetOpen = false; onDiscoverGroups?.invoke() },
                onScanInvite = {
                    newSheetOpen = false
                    inviteScanner.launch(
                        com.journeyapps.barcodescanner.ScanOptions().apply {
                            setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                            setPrompt(context.getString(R.string.new_scan_prompt))
                            setBeepEnabled(true)
                            setOrientationLocked(false)
                            setBarcodeImageEnabled(false)
                        }
                    )
                },
                onCall = if (onCallContact != null) { video -> newSheetOpen = false; pickContactForCall = video } else null,
            )
        }
        groupSheetFor?.let { gc ->
            val pinKey = com.oshi.messenger.data.model.ChatListMerge.pinKeyForGroup(gc.group.id)
            GroupRowSheet(
                conversation = gc,
                pinned = com.oshi.messenger.data.model.ConversationPinOrder.isPinned(pinKey, pinnedConversations),
                onDismiss = { groupSheetFor = null },
                onTogglePin = { groupSheetFor = null; viewModel.togglePinned(pinKey) },
                onToggleMute = { groupSheetFor = null; groupsViewModel.toggleGroupMute(gc.group.id) },
                onInfo = { groupSheetFor = null; groupsViewModel.selectGroup(gc.group) },
                onLeave = { groupSheetFor = null; confirmLeave = gc },
            )
        }
        confirmLeave?.let { gc ->
            com.oshi.messenger.ui.theme.OshiAlertDialog(
                onDismissRequest = { confirmLeave = null },
                title = { Text(stringResource(R.string.groups_leave_title)) },
                text = { Text(stringResource(R.string.groups_leave_message)) },
                confirmButton = {
                    TextButton(onClick = { confirmLeave = null; groupsViewModel.leaveGroup(gc.group.id) }) {
                        Text(stringResource(R.string.groups_leave_action), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmLeave = null }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
        GroupsSheetsHost(viewModel = groupsViewModel, onNavigateToChat = onConversationClick)
    }
}

/**
 * __NEW_IS_A_MENU_2026_11_13__ (Anatole N4) The "New" button and the menu that
 * unfolds above it. Collapsed it is exactly the old extended FAB (labelled, and
 * icon-only while the list scrolls); open, it turns into a close button and three
 * labelled small buttons rise from it, nearest first.
 */
@Composable
private fun NewActionsFab(
    expanded: Boolean,
    labelled: Boolean,
    showCalls: Boolean,
    onToggle: () -> Unit,
    onNewContact: () -> Unit,
    onCall: (video: Boolean) -> Unit,
) {
    Column(horizontalAlignment = Alignment.End) {
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.fadeIn() +
                androidx.compose.animation.expandVertically(expandFrom = Alignment.Bottom),
            exit = androidx.compose.animation.fadeOut() +
                androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                if (showCalls) {
                    FabMenuItem(stringResource(R.string.call_video), Icons.Default.Videocam) { onCall(true) }
                    FabMenuItem(stringResource(R.string.call_voice), Icons.Default.Call) { onCall(false) }
                }
                FabMenuItem(stringResource(R.string.fab_new_contact), Icons.Default.PersonAdd, onNewContact)
            }
        }
        ExtendedFloatingActionButton(
            onClick = onToggle,
            expanded = labelled && !expanded,
            // __OLED_GLASS_2026_11_16__ OLED black: the FAB is the screen's one lit thing.
            modifier = Modifier.thenIf(glassOn()) { glassGlow(RoundedCornerShape(16.dp)) },
            containerColor = MaterialTheme.colorScheme.primary,
            icon = {
                Icon(
                    if (expanded) Icons.Default.Close else Icons.Default.Edit,
                    contentDescription = if (expanded) stringResource(R.string.close) else null,
                )
            },
            text = { Text(stringResource(R.string.new_message_fab)) },
        )
    }
}

/** One labelled entry of [NewActionsFab]: a label chip beside a small button, one target. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FabMenuItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = label }
    ) {
        val glass = glassOn()
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(percent = 50),
            color = if (glass) GlassTier.Menu.opaque else MaterialTheme.colorScheme.surface,
            tonalElevation = if (glass) 0.dp else 3.dp,
            shadowElevation = 3.dp,
            modifier = Modifier.thenIf(glass) { glassHairline(RoundedCornerShape(percent = 50)) },
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(icon, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ConversationItem(
    conversation: Conversation,
    /** __RELATIVE_TIMES_FROZE_2026_11_09__ the list's shared clock. */
    now: Long = System.currentTimeMillis(),
    /** __PIN_A_CONVERSATION_2026_10_28__ (N18) kept at the top of the list. */
    isPinned: Boolean = false,
    /** __MUTE_A_CONVERSATION_2026_11_13__ (N8) shows a bell-off beside the time. */
    isMuted: Boolean = false,
    onClick: () -> Unit,
    /** __ROW_SHEET_2026_11_13__ (N8) opens the row's sheet (pin, mute, open contact). */
    onLongClick: (() -> Unit)? = null,
) {
    // 📸 Try to decode profile picture from contact (received from them).
    // PERF: BitmapFactory.decodeByteArray on Main blocks the frame and was the
    // #1 jank/ANR source while scrolling the conversation list. We dispatch the
    // decode to Dispatchers.Default and present null until it arrives. The
    // remember key ensures we re-decode only when the underlying bytes change.
    // Localized copy for every control-payload preview, resolved once per row.
    val context = LocalContext.current
    val controlLabels = remember(context) { OSHIControlPayload.labels(context) }

    // __GROUP_PHOTO_FLICKERED_2026_11_04__ The comment above used to claim the
    // `remember` key "ensures we re-decode only when the underlying bytes change".
    // It did not: the key was the ByteArray, compared by reference. Every profile
    // picture that arrived again — the same bytes in a new array — blanked the
    // avatar and re-decoded it. Fixed with the group photo it was flickering
    // alongside, because it is one bug in three places.
    val profileBitmap = com.oshi.messenger.ui.components.rememberAvatarBitmap(
        conversation.participantProfilePicture
    )

    // 💓 __ALIVE_CHECK_IN_2026_11_04__ The liveness state of this conversation.
    //
    // Collected in the ROW, like the last-reaction store below and for the same
    // reason: a `StateFlow` on a singleton is the same object whoever collects it,
    // and the row is where the value is read. The status is recomputed from
    // timestamps on each frame the clock tick causes, so a countdown is never
    // stale and never drifts.
    val aliveEntries by com.oshi.messenger.service.AliveCheckInStore.entries.collectAsState()
    val aliveEntry = remember(aliveEntries, conversation.participantPublicKey) {
        com.oshi.messenger.service.AliveCheckInStore.entryFor(conversation.participantPublicKey)
    }
    val aliveCountdown = com.oshi.messenger.ui.components.aliveRowCountdown(aliveEntry)
    val aliveStatus = if (aliveEntry == null) {
        com.oshi.messenger.data.model.AliveCheckInPolicy.Status.NEUTRAL
    } else {
        aliveEntry.rowStatus(System.currentTimeMillis())
    }

    // F-12 (iOS 6f2237d + d844b93). This was a Material3 `ListItem`, whose
    // three-slot form has an 88 dp minimum, with a 56 dp avatar and a one-line
    // preview. iOS measured its own row down from 92.6 pt to 55.4 pt by shrinking
    // the avatar to 44, dropping the row to two text lines, moving the unread pill
    // to the END OF THE PREVIEW LINE instead of giving it a column, and removing
    // the trailing button entirely (MessagesListView.swift:1091-1123, :744).
    // The preview is ONE line on both platforms — see
    // __PREVIEW_IS_ONE_LINE_2026_11_02__ on the `maxLines` below.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // __ROW_SHEET_2026_11_13__ (Anatole N8) Long-press opens a sheet with Pin,
            // Mute and Open contact. It used to toggle the pin straight away, which
            // gave no feedback beyond the row jumping, and no way to reach anything else.
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.chat_more_options),
            )
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // BRAND RING + NAME-UNDER-AVATAR (2026-11-09): product decision, ported
        // 1:1 from `ConversationRow` (MessagesListView.swift) for platform parity
        // — an `OSHIPurple` ring around the avatar echoes OSHI's own app-icon
        // mark, drawn 3dp OUTSIDE the existing 44dp avatar rather than shrinking
        // it, and the alias moves from beside the avatar to a one-line caption
        // underneath. Pin, alive marker, verified badge, timestamp, preview and
        // unread badge all stay on the right, only re-flowed.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(50.dp),
            contentAlignment = Alignment.Center
        ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(2.dp, OSHIPurple, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val pb = profileBitmap
            if (pb != null) {
                Image(
                    bitmap = pb.asImageBitmap(),
                    contentDescription = stringResource(R.string.accessibility_profile_avatar),
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                // __AVATAR_COLOURS_WERE_ONE_COLOUR_2026_11_05__
                //
                // This was `MaterialTheme.colorScheme.primaryContainer` — the
                // SAME colour for every contact, so a list of twenty people
                // without a photo was twenty identical circles. The iOS report
                // was "it seems to have 2 main colours"; checking here for the
                // same bug found one colour, which is worse.
                //
                // `AvatarIdentity` is shared with iOS down to the hash, so a
                // contact is the same colour on both platforms — see the
                // cross-platform vectors in `AvatarIdentityTest`. White initials
                // are safe on every gradient by construction (AA at 5.2:1), so
                // `onPrimaryContainer` is no longer the right foreground: it
                // follows the theme and would go dark-on-dark here.
                // __AVATAR_PALETTE_V2_2026_11_17__ gradient + light blob, and the
                // slot's own ink (white on mid tones, deep on pastels).
                val avatarEntry = remember(conversation.participantPublicKey) {
                    com.oshi.messenger.ui.theme.AvatarIdentity.contactEntry(
                        conversation.participantPublicKey
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .then(Modifier.avatarFill(avatarEntry)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = conversation.participantAlias.take(2).uppercase(),
                        fontWeight = FontWeight.Bold,
                        color = avatarEntry.ink
                    )
                }
            }
        }

            // Online indicator. 13 dp on iOS (MessagesListView.swift:1123) — rides
            // the ring's outer edge now, not the bare photo's.
            if (conversation.isOnline) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

            // __NAME_BACK_ABOVE_PREVIEW_2026_11_12__ The 10 sp caption under the
            // avatar (58 dp wide) was unreadable — Anatole, 12/11: "le nom des
            // contacts ... illisible ... remis au dessus de l'aperçu comme
            // auparavant". The name is back on the first text line, 15 sp.
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // __PIN_A_CONVERSATION_2026_10_28__ (N18) Before the name, carrying no
                // weight, so a pinned row is identifiable without the name losing width.
                // Without a marker "pinned" is only inferable from POSITION, which is
                // indistinguishable from "most recent" — the one thing the list already
                // meant.
                if (isPinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = stringResource(R.string.conversation_pin),
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                // 💓 __ALIVE_CHECK_IN_2026_11_04__ Before the name and carrying no
                // weight, exactly like the pin above: the name yields the width, so a
                // long alias can never push the marker off the row.
                if (aliveStatus != com.oshi.messenger.data.model.AliveCheckInPolicy.Status.NEUTRAL) {
                    com.oshi.messenger.ui.components.AliveRowMarker(aliveStatus)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = conversation.participantAlias,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                // Safety-number badge. Small and primary-tinted: a trust signal, not
                // decoration — it must not outrank the unread dot.
                if (conversation.isVerified) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = stringResource(R.string.encryption_verified),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                if (isMuted) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.NotificationsOff,
                        contentDescription = stringResource(R.string.conversation_muted),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatConversationTimestamp(conversation.lastMessage.timestamp, now),
                    fontSize = 12.sp,
                    color = if (conversation.unreadCount > 0)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // A message that never left the device must not claim to have been
                // sent on ANY surface, so the failed state wins here exactly as it
                // does in the bubble (device walkthrough #2, 2026-08-13).
                if (conversation.lastMessage.isFromMe) {
                    if (conversation.lastMessage.isFailed) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = stringResource(R.string.message_status_failed),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Icon(
                            if (conversation.lastMessage.isRead || conversation.lastMessage.isDelivered)
                                Icons.Default.DoneAll
                            else
                                Icons.Default.Done,
                            contentDescription = when {
                                conversation.lastMessage.isRead -> stringResource(R.string.message_status_read)
                                conversation.lastMessage.isDelivered -> stringResource(R.string.message_status_delivered)
                                else -> stringResource(R.string.message_status_sent)
                            },
                            modifier = Modifier.size(16.dp),
                            tint = when {
                                conversation.lastMessage.isRead -> Color(0xFF4FC3F7)
                                conversation.lastMessage.isDelivered -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }

                // __REACTION_WAS_SILENT_2026_11_04__ (Anatole) A reaction to one
                // of our messages shows here too, but ONLY while it is the most
                // recent thing that happened: a reaction to something said
                // yesterday must not outrank what has been said since.
                //
                // Collected in the ROW rather than the screen: the row is where
                // it is read, and a StateFlow on a singleton is the same object
                // either way.
                val lastReactions by
                    com.oshi.messenger.service.LastReactionStore.entries.collectAsState()
                val reactionEmoji = com.oshi.messenger.service.LastReactionStore.previewEmoji(
                    lastReactions[conversation.participantPublicKey],
                    conversation.lastMessage.timestamp
                )
                // __OSHI_O_REACTION_2026_11_15__ an OSHI reaction ("⭕" or "⭕😊") is
                // drawn inline as its mark, not shown as the emoji it travels as.
                val reactionLabel = stringResource(R.string.reaction_notification)
                Text(
                    // __VIDEO_NOTE_2026_09_24__ spec §4: "Video message", not "Video".
                    text = if (!conversation.hasMessages) {
                        // __SAVED_CONTACT_SHOWS_AT_ONCE_2026_11_13__ (N18)
                        androidx.compose.ui.text.AnnotatedString(
                            stringResource(R.string.conversation_saved_contact_preview)
                        )
                    } else if (reactionEmoji != null) {
                        com.oshi.messenger.ui.components.reactionPreviewText(reactionEmoji, reactionLabel)
                    } else if (conversation.lastMessage.isVideoNote) {
                        androidx.compose.ui.text.AnnotatedString("🎥 " + stringResource(R.string.video_note_preview))
                    } else androidx.compose.ui.text.AnnotatedString(
                        formatPreviewText(conversation.lastMessage.content, controlLabels)
                    ),
                    inlineContent = if (reactionEmoji != null && conversation.hasMessages)
                        com.oshi.messenger.ui.components.reactionPreviewInlineContent(reactionEmoji, 13.sp)
                    else emptyMap(),
                    fontSize = 13.sp,
                    // __PREVIEW_IS_ONE_LINE_2026_11_02__ parity with iOS, which
                    // dropped to one line on the same day and for the same reason:
                    // a two-line preview makes the row three lines tall for a
                    // chatty conversation and one or two for a quiet one, so the
                    // list height jumps between neighbours.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                // 💓 __ALIVE_CHECK_IN_2026_11_04__ The countdown, after the weighted
                // preview and before the unread pill. That order is the ranking: an
                // unread message is what the row is for, and a check-in state must
                // not outrank it.
                if (aliveCountdown != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = aliveCountdown,
                        fontSize = 11.sp,
                        maxLines = 1,
                        color = if (aliveStatus == com.oshi.messenger.data.model.AliveCheckInPolicy.Status.OVERDUE) {
                            com.oshi.messenger.ui.components.AliveCheckInColors.overdue
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                if (conversation.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                        Text(
                            text = conversation.unreadCount.toString(),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

/**
 * First-run state for the conversations list.
 *
 * Ported from iOS `EmptyStateView` (`MessagesListView.swift:1664`, presented at
 * `:109`): hero mark, "You're all set!", the no-phone-number subtitle, three
 * numbered steps in a raised card, then a primary "Show my code" and a secondary
 * "Scan a friend's code" (`:1717` / `:1733`).
 *
 * Android previously shipped a grey `Forum` glyph plus two hardcoded English
 * sentences and NO call to action — on the very first screen a new user sees.
 * The buttons are optional so the composable degrades to the narrative-only form
 * if a caller has no navigation to offer.
 */
@Composable
internal fun EmptyConversationsView(
    modifier: Modifier = Modifier,
    onShowMyCode: (() -> Unit)? = null,
    onScanCode: (() -> Unit)? = null,
    /** __FIRST_CONTACT_REMOTE_2026_10_10__ The action for "they are not here". */
    onInviteByLink: (() -> Unit)? = null,
    /**
     * __IN_APP_GUIDES_2026_11_02__ "How do I add someone?" belongs HERE, on the
     * screen of somebody who has nobody yet — not only buried in Settings,
     * which is where it was and which is why people kept asking.
     */
    onHowToAdd: (() -> Unit)? = null,
    /** __MERGED_CHATS_2026_11_18__ merged mode: the Discover card, under everything else. */
    footer: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // iOS uses the "EmptyStateArt" asset (two bubbles + a padlock). Android has
        // no equivalent drawable, so the same idea is composed from brand-tinted
        // vector marks rather than shipping an untranslated placeholder image.
        // Same F-14 recipe: the tint is the surface, the ink is onSurface.
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(OSHIPurple.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Forum,
                contentDescription = null,
                modifier = Modifier.size(58.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(34.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(6.dp),
                tint = OSHIPurple
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.onboarding_start_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.onboarding_start_subtitle),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(22.dp))

        // The three concrete first steps (iOS OSHIFirstStepRow, MessagesListView.swift:1780).
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                FirstStepRow(1, Icons.Default.QrCode, stringResource(R.string.onboarding_start_step1))
                FirstStepRow(2, Icons.Default.Verified, stringResource(R.string.onboarding_start_step2))
                FirstStepRow(3, Icons.Default.Lock, stringResource(R.string.onboarding_start_step3))
            }
        }

        // __FIRST_CONTACT_REMOTE_2026_10_10__ Grouped by SITUATION, remote first.
        //
        // It used to be two buttons, both of which need the other phone in the
        // room. Someone adding their first contact from their own kitchen had
        // nothing to tap. Sending a link also works in BOTH situations, so it
        // takes the primary slot; the QR pair stays, labelled for side by side.
        onInviteByLink?.let { action ->
            Button(
                onClick = action,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(OSHIPurple, Color(0xFFF0478F))
                            ),
                            RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Send, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.onboarding_invite_link),
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = stringResource(R.string.onboarding_invite_link_sub),
                            color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // __IN_APP_GUIDES_2026_11_02__ The question this screen provokes,
        // answered on this screen.
        onHowToAdd?.let { action ->
            TextButton(onClick = action) {
                Icon(
                    Icons.Default.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.guide_add_contact_title))
            }
        }

        if (onShowMyCode != null || onScanCode != null) {
            Text(
                text = stringResource(R.string.onboarding_together_header),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (onShowMyCode != null || onScanCode != null) {
            Spacer(modifier = Modifier.height(22.dp))

            onShowMyCode?.let { action ->
                Button(
                    onClick = action,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
                ) {
                    // iOS paints this button with a brand→pink gradient
                    // (MessagesListView.swift:1723).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(OSHIPurple, Color(0xFFF0478F))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.QrCode, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.onboarding_start_show_code),
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            onScanCode?.let { action ->
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = action,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    // F-14 (iOS e790c77, MessagesListView.swift:1869-1876): brand ink
                    // on a brand-tinted surface disappears in dark mode - a 12% brand
                    // fill over near-black stays near-black. Raise the tint, ink in
                    // onSurface.
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OSHIPurple.copy(alpha = 0.22f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.onboarding_start_scan_code),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (footer != null) {
            Spacer(modifier = Modifier.height(20.dp))
            footer()
            // Scrolls clear of the "New" button.
            Spacer(modifier = Modifier.height(88.dp))
        }
    }
}

/** One numbered step in the first-run guide — iOS `OSHIFirstStepRow`. */
@Composable
private fun FirstStepRow(number: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(OSHIPurple.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = OSHIPurple
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One human-readable line for a conversation row.
 *
 * Mirrors `MessagesListView.swift:1122-1207`: a handful of named branches that
 * are RICHER than the generic catalog summary, then everything else — reply and
 * forward envelopes included — goes through `OSHIControlPayload`.
 *
 * Before this, the function held its own ~18-prefix list with no `💬REPLY💬` /
 * `➡️FORWARDED➡️` branch, and because the emoji precedes the `{` none of the
 * JSON catch-alls fired either: replying to anyone printed the whole envelope
 * (`💬REPLY💬{"content":"hi","replyTo":{…}}`) under their name in your own list.
 */
private fun formatPreviewText(
    content: String,
    labels: OSHIControlPayload.Labels = OSHIControlPayload.Labels()
): String {
    // --- Branches richer than the catalog (iOS keeps these too) ---

    // Check-In: iOS shows the message TYPE ("Arrived Safely", "Emergency
    // Alert"), not a flat "Check-In" (MessagesListView.swift:1140-1146).
    if (content.startsWith(OSHIControlPayload.CHECK_IN)) {
        val checkIn = CheckInMessage.fromMessageString(content)
        // 🔧 FIX (2026-08-24 three-gaps): `checkIn.displayTitle` was an English
        // literal, so this row said "Emergency Alert" in all 34 locales. The names
        // now come from the same localized `Labels` every other branch here uses.
        val typeName = checkIn?.type?.let { labels.checkInTypes[it] }
        return "🛡️ " + (typeName ?: labels.checkIn)
    }
    // Connecting… — a call SIGNAL is silent in the catalog, but the list has
    // always surfaced it (MessagesListView.swift:1135). The literal used to be
    // "📞 Connecting..." — English on a row in all 34 locales.
    if (content.startsWith(OSHIControlPayload.CALL_SIGNAL)) return "📞 " + labels.connecting
    // Android tells audio and document apart; the catalog's MEDIA||| summary
    // does too, so this stays only for the `parts.size < 2` fallback shape.
    if (content.startsWith(OSHIControlPayload.MEDIA)) {
        val parts = content.split("|||")
        return if (parts.size >= 2) {
            when (parts[1].lowercase()) {
                "image", "photo" -> "📷 " + labels.mediaImage
                "video" -> "🎥 " + labels.mediaVideo
                "audio", "voice" -> "🎤 " + labels.mediaAudio
                "document" -> "📄 " + labels.mediaDocument
                else -> "📎 " + labels.mediaFile
            }
        } else "📎 " + labels.mediaFile
    }

    // --- THE catalog: every sentinel, every envelope, AND the non-sentinel
    //     safety nets, one source of truth ---
    //
    // The nets used to live right here, as a `when` block whose placeholders were
    // three English literals ("💬 Message", "🔒 Encrypted message"). That made
    // this the ONLY surface in the app that had them: the groups list called bare
    // `previewText` and printed an unclaimed payload verbatim, and the
    // notification path had nothing at all. They now live in the catalog as
    // `listPreview`, localized, so every row and every banner gets the same
    // answer — which is the entire reason this file stopped keeping its own
    // 18-prefix list in the first place.
    return OSHIControlPayload.listPreview(content, labels)
}

/**
 * __RELATIVE_TIMES_FROZE_2026_11_09__ [now] is a PARAMETER (the list's shared
 * clock), not read here: a label computed from a clock nobody invalidates was
 * frozen at "Just now" until something else recomposed the row.
 */
internal fun formatConversationTimestamp(timestamp: Long, now: Long): String {
    // Normalize timestamp - if it looks like seconds instead of milliseconds, convert
    val normalizedTs = if (timestamp in 1..1_000_000_000_000L) timestamp * 1000 else timestamp

    val diff = now - normalizedTs

    // Check if it was yesterday
    val messageDate = Calendar.getInstance().apply { timeInMillis = normalizedTs }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val yesterday = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -1) }
    val isToday = messageDate.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        messageDate.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val isYesterday = messageDate.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        messageDate.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)

    return when {
        diff < 0 -> "Just now"  // Future timestamps treated as "Just now"
        diff < 60_000 -> "Just now"
        isToday && diff < 3600_000 -> "${diff / 60_000}m ago"
        isToday && diff < 86400_000 -> "${diff / 3600_000}h ago"
        isYesterday -> "Yesterday"
        diff < 604800_000 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(normalizedTs))
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(normalizedTs))
    }
}

/**
 * The top-bar antenna: nearby mesh phones and, when a Meshtastic radio is
 * connected, "LoRa N" = other nodes heard in the last 2 h. Always present so a
 * peer flap never adds or removes anything from the bar; its width only changes
 * when a count's digits do. iOS `MeshStatusBadge` (MessagesListView.swift).
 */
@Composable
private fun MeshStatusBadge(state: MeshBadgeState, onClick: (() -> Unit)? = null) {
    val tint = if (state.connected) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant
    // Named so a screen reader announces the destination, not just "double tap".
    val openLabel = stringResource(R.string.peers_title_nearby)
    // __SEARCHING_IS_NOT_ALWAYS_TRUE_2026_10_28__ (N15) "Searching for peers…" is TRUE
    // with the radios on and nobody in range, and a LIE with Bluetooth off — and the two
    // used to read identically. When something is blocking the scan, say that instead.
    val blockedText = when (state.blocker) {
        com.oshi.messenger.data.model.MeshReadiness.Reason.NEARBY_PERMISSION ->
            stringResource(R.string.mesh_blocked_permission)
        com.oshi.messenger.data.model.MeshReadiness.Reason.BLUETOOTH_OFF ->
            stringResource(R.string.mesh_blocked_bluetooth)
        com.oshi.messenger.data.model.MeshReadiness.Reason.WIFI_OFF ->
            stringResource(R.string.mesh_blocked_wifi)
        null -> null
    }
    val description = buildList {
        if (state.meshPeers > 0) add(stringResource(R.string.mesh_peers_nearby, state.meshPeers))
        state.loraOnline?.let { add("LoRa: $it") }
        if (isEmpty()) add(blockedText ?: stringResource(R.string.mesh_status_searching))
    }.joinToString(". ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (onClick == null) Modifier
                else Modifier.clickable(onClickLabel = openLabel) { onClick() }
            )
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        // __BLUETOOTH_GLYPH_2026_11_15__ "蓝牙连接为何不显示蓝牙图标？" — phones met over
        // Bluetooth were drawn with the same antenna as LoRa, so a Bluetooth link never
        // looked like one. Nearby phones now get the Bluetooth glyph; the antenna stays
        // for the radio (LoRa) part and for the idle/searching state.
        Icon(
            if (state.meshPeers > 0) Icons.Default.BluetoothConnected
            else Icons.Default.SettingsInputAntenna,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = tint
        )
        if (state.meshPeers > 0) {
            Spacer(modifier = Modifier.width(3.dp))
            Text(state.meshPeers.toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = tint)
        } else if (blockedText != null) {
            // Visible, not only announced: the person who cannot see why the mesh is empty
            // is exactly the person who will not open an accessibility inspector. Tapping
            // the badge already opens Nearby peers, which is where it gets fixed.
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                blockedText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.loraOnline?.let { count ->
            Spacer(modifier = Modifier.width(5.dp))
            if (state.meshPeers > 0) {
                Icon(
                    Icons.Default.SettingsInputAntenna,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = tint
                )
                Spacer(modifier = Modifier.width(2.dp))
            }
            Text("LoRa $count", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tint)
        }
    }
}

/**
 * __LIVE_SHARE_COUNTER_2026_09_22__ Green pill with the number of MY running live
 * location shares. One share: a tap opens that conversation. Several: a menu,
 * one row per contact with its end time. Hidden when nothing is shared.
 */
@Composable
private fun LiveShareCounter(shares: List<LiveShareEntry>, onOpen: (LiveShareEntry) -> Unit) {
    if (shares.isEmpty()) return
    var menuOpen by remember { mutableStateOf(false) }
    val label = stringResource(R.string.location_sharing_live) + ", " + shares.size
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF34C759))
                .clickable {
                    if (shares.size == 1) onOpen(shares.first()) else menuOpen = true
                }
                .padding(horizontal = 9.dp, vertical = 4.dp)
                .semantics(mergeDescendants = true) { contentDescription = label }
        ) {
            Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
            Spacer(modifier = Modifier.width(3.dp))
            Text(shares.size.toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        OshiDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val timeFormat = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
            shares.forEach { entry ->
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                    text = {
                        Column {
                            Text(
                                entry.displayName ?: (entry.share.conversationId.take(8) + "…"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                stringResource(
                                    R.string.location_expires_in,
                                    timeFormat.format(Date(entry.share.endTimeMillis))
                                ),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        menuOpen = false
                        onOpen(entry)
                    }
                )
            }
        }
    }
}
