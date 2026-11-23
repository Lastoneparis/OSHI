package com.oshi.messenger.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.OSHIPurple
import kotlinx.coroutines.launch

/**
 * ChatsTabScreen — merged Messages + Groups in a single tab.
 *
 * Uses a TabRow with HorizontalPager so user can swipe between
 * their 1-to-1 conversations and group conversations.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatsTabScreen(
    onConversationClick: (String) -> Unit,
    onGroupClick: (String) -> Unit,
    onNewMessage: () -> Unit,
    onSettingsClick: () -> Unit,
    onQRCodeClick: () -> Unit,
    onDiscoverGroups: () -> Unit,
    onNavigateToChat: (String) -> Unit,
    /** "Scan a friend's code" from the first-run empty state (MessagesListView.swift:1733). */
    onScanCode: (() -> Unit)? = null,
    /**
     * __FIRST_CONTACT_REMOTE_2026_10_10__ "Invite someone" from the first-run
     * empty state — the path that works when the other person is not here.
     */
    onInviteByLink: (() -> Unit)? = null,
    /** Opens the private mailbox. */
    onMailClick: (() -> Unit)? = null,
    /** __ANTENNA_IS_A_DOOR_2026_10_27__ The top-bar antenna opens "Nearby peers". */
    onNearbyClick: (() -> Unit)? = null,
    /** __NEW_IS_A_MENU_2026_11_13__ (N4) Call a contact from the "New" menu. */
    onCallContact: ((publicKey: String, video: Boolean) -> Unit)? = null
) {
    // __MERGED_CHATS_2026_11_18__ « Fusionner Messages et Groupes » (Settings ›
    // Appearance): one list, no tabs. Off — the default for everyone who already
    // had the app — renders the pager below exactly as before.
    val context = androidx.compose.ui.platform.LocalContext.current
    val merged by remember(context) { com.oshi.messenger.data.model.MergedChatsSetting.state(context) }.collectAsState()
    if (com.oshi.messenger.data.model.ChatsTabLayout.tabs(merged) == listOf(com.oshi.messenger.data.model.ChatsTabLayout.Tab.CHATS)) {
        ConversationsScreen(
            onConversationClick = onConversationClick,
            onNewMessage = onNewMessage,
            onSettingsClick = onSettingsClick,
            onQRCodeClick = onQRCodeClick,
            onScanCode = onScanCode,
            onInviteByLink = onInviteByLink,
            onMailClick = onMailClick,
            onGroupClick = onGroupClick,
            onNearbyClick = onNearbyClick,
            onCallContact = onCallContact,
            merged = true,
            onDiscoverGroups = onDiscoverGroups,
        )
        return
    }

    // Localized — was `listOf("Messages", "Groups")`, hardcoded English in a
    // 33-language app. iOS uses "tab_messages"/"tab_groups" (ChatsTabView.swift:27,33).
    val tabs = listOf(
        stringResource(R.string.tab_messages),
        stringResource(R.string.tab_groups)
    )
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab selector
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = OSHIPurple
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        coroutineScope.launch { pagerState.animateScrollToPage(index) }
                    },
                    text = {
                        Text(
                            title,
                            color = if (pagerState.currentPage == index) OSHIPurple
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }

        // Pager content
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> ConversationsScreen(
                    onConversationClick = onConversationClick,
                    onNewMessage = onNewMessage,
                    onSettingsClick = onSettingsClick,
                    onQRCodeClick = onQRCodeClick,
                    onScanCode = onScanCode,
                    onInviteByLink = onInviteByLink,
                    onMailClick = onMailClick,
                    onGroupClick = onGroupClick,
                    onNearbyClick = onNearbyClick,
                    onCallContact = onCallContact
                )
                1 -> GroupsScreen(
                    onGroupClick = onGroupClick,
                    onDiscoverGroups = onDiscoverGroups,
                    // No back arrow inside the merged tab: iOS's GroupsListView
                    // inside ChatsTabView has none either, and there is nothing to
                    // pop here. GroupsScreen renders the chevron only when onBack
                    // is non-null, so omitting it is the parity behaviour.
                    onNavigateToChat = onNavigateToChat
                )
            }
        }
    }
}
