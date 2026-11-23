package com.oshi.messenger.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oshi.messenger.data.model.ChatListMerge
import com.oshi.messenger.data.model.Conversation
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.GroupConversation
import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.ui.screens.*
import com.oshi.messenger.ui.theme.OSHITheme
import com.oshi.messenger.ui.theme.glassGlow
import com.oshi.messenger.ui.theme.glassOn
import com.oshi.messenger.ui.theme.thenIf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * __MERGED_CHATS_2026_11_18__ Screenshots of the merged chat list with FIXTURE
 * data (no account, no network): the list in light / dark / OLED, the Groups
 * chip, the "New…" sheet, the first-run Discover card, and — for comparison —
 * the option OFF (the two tabs, from the same row component). Whole-screen
 * captures (UiAutomation) so sheets, which live in their own window, are in
 * the picture. PNGs land in the app's external files dir (`merged-shots/`).
 *
 * Only runs when asked: `-e mergedShots true`.
 */
@RunWith(AndroidJUnit4::class)
class MergedChatsScreenshots {

    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun enabled() = InstrumentationRegistry.getArguments().getString("mergedShots") == "true"

    private val now = System.currentTimeMillis()
    private val min = 60_000L

    private fun conv(key: String, alias: String, text: String, ago: Long, unread: Int = 0, fromMe: Boolean = false, read: Boolean = true) =
        Conversation(
            participantPublicKey = key, participantAlias = alias, participantProfilePicture = null,
            lastMessage = Message(senderPublicKey = if (fromMe) "me" else key, recipientPublicKey = if (fromMe) key else "me",
                content = text, timestamp = now - ago, isFromMe = fromMe, isRead = read, isDelivered = true),
            unreadCount = unread, isOnline = unread > 0,
        )

    private fun group(id: String, name: String, sender: String, text: String, ago: Long, unread: Int = 0, members: Int = 5, muted: Boolean = false) =
        GroupConversation(
            group = Group(id = id, name = name, creatorPublicKey = "me", memberPublicKeys = List(members) { "m$it" },
                adminPublicKeys = listOf("me"), createdAt = now - 30 * 24 * 60 * min, isMuted = muted),
            lastMessage = GroupMessage(groupId = id, senderPublicKey = "s-$sender", senderName = sender, content = text,
                timestamp = now - ago, isFromMe = false),
            unreadCount = unread, memberNames = emptyMap(),
        )

    private val conversations = listOf(
        conv("MCowBQYDK2VwAyEAalice0000000000000000000001=", "Alice Martin", "That's exactly what I needed, thanks!", 3 * min, unread = 2),
        conv("MCowBQYDK2VwAyEAcharlie00000000000000000002=", "Charlie", "The privacy-first approach is the point", 55 * min, fromMe = true),
        conv("lorachan!0", "LongFast", "Anyone near the station? Signal is strong here", 2 * 60 * min, unread = 1),
        conv("MCowBQYDK2VwAyEAdiana000000000000000000003=", "Diana Rossi", "See you tomorrow at 9", 26 * 60 * min),
        conv("MCowBQYDK2VwAyEAeric00000000000000000000004=", "Eric", "📍 Location shared", 3 * 24 * 60 * min, fromMe = true),
    )
    private val groups = listOf(
        group("8a6e0c2e-1f43-4b8a-9d56-3c1e2f0a9b11", "Book Club", "Marie", "Fitting choice for this month 📚", 12 * min, unread = 4),
        group("2b5d7e19-6c0a-4e3b-8f12-9a7c4d2e1f00", "Family", "Mum", "Count me in for Sunday lunch", 5 * 60 * min, members = 7, muted = true),
        group("c4d3e2f1-0a9b-4c8d-a7e6-5f4e3d2c1b0a", "Climbing crew", "Sam", "Rain tomorrow, gym instead?", 2 * 24 * 60 * min),
    )
    private val nearby = listOf(
        GroupManager.DiscoveredGroup("11111111-2222-4333-8444-555555555555", "Lisbon Night Market", "admin", 23, null, now - 4 * min),
    )

    private var current by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var darkNow by mutableStateOf(false)
    private var oledNow by mutableStateOf(false)
    private var hosted = false

    private fun shoot(name: String, dark: Boolean = false, oled: Boolean = false, settleMs: Long = 900, content: @Composable () -> Unit) {
        if (!hosted) {
            rule.setContent {
                OSHITheme(darkTheme = darkNow, oledBlack = oledNow) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { current?.invoke() }
                }
            }
            hosted = true
        }
        rule.runOnIdle { darkNow = dark; oledNow = oled; current = content }
        snap(name, settleMs)
    }

    /** Capture what is on screen now, without recomposing (keeps a scroll position). */
    private fun snap(name: String, settleMs: Long = 900) {
        rule.waitForIdle()
        Thread.sleep(settleMs)   // sheet animations, image decode
        rule.waitForIdle()
        val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "merged-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Chrome(content: @Composable (PaddingValues) -> Unit) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text("OSHI", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton({}) { Icon(Icons.Default.Palette, null) }
                        IconButton({}) { Icon(Icons.Default.Email, null) }
                        IconButton({}) { Icon(Icons.Default.Campaign, null) }
                        IconButton({}) { Icon(Icons.Default.AccountCircle, null) }
                    },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {},
                    modifier = Modifier.thenIf(glassOn()) { glassGlow(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    icon = { Icon(Icons.Default.Edit, null) },
                    text = { Text("New") },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(true, {}, { Icon(Icons.Default.Chat, null) }, label = { Text("Messages") })
                    NavigationBarItem(false, {}, { Icon(Icons.Default.AutoAwesome, null) }, label = { Text("AI") })
                    NavigationBarItem(false, {}, { Icon(Icons.Default.Map, null) }, label = { Text("Map") })
                    NavigationBarItem(false, {}, { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                }
            },
            content = content,
        )
    }

    @Composable
    private fun Merged(
        convs: List<Conversation> = conversations,
        grps: List<GroupConversation> = groups,
        near: List<GroupManager.DiscoveredGroup> = nearby,
        filter: ChatListMerge.Filter = ChatListMerge.Filter.ALL,
        groupType: com.oshi.messenger.data.model.GroupType? = null,
    ) {
        Chrome { p ->
            MergedChatsBody(
                conversations = convs, groups = grps,
                pinned = setOf(ChatListMerge.pinKeyForGroup(groups[1].group.id)),
                muted = { false }, mentionedGroups = emptySet(), nearby = near,
                listState = rememberLazyListState(),
                onConversationClick = {}, onConversationLongClick = {}, onGroupClick = {}, onGroupLongClick = {},
                onDiscover = {}, onCreateGroup = {},
                emptyState = { footer ->
                    EmptyConversationsView(Modifier.fillMaxSize(), onShowMyCode = {}, onScanCode = {}, onInviteByLink = {}, onHowToAdd = {}, footer = footer)
                },
                modifier = Modifier.padding(p),
                initialFilter = filter,
                initialGroupType = groupType,
            )
        }
    }

    /** The option OFF: the two tabs over the Messages list, from the same row component. */
    @Composable
    private fun Classic() {
        Chrome { p ->
            Column(Modifier.padding(p)) {
                TabRow(selectedTabIndex = 0, containerColor = MaterialTheme.colorScheme.surface) {
                    Tab(true, {}, text = { Text("Messages") })
                    Tab(false, {}, text = { Text("Groups") })
                }
                LazyColumn {
                    items(conversations) { c -> ConversationItem(conversation = c, now = now, onClick = {}) }
                }
            }
        }
    }

    @Test
    fun captureMergedChats() {
        if (!enabled()) return
        shoot("merged-off-light") { Classic() }
        shoot("merged-on-light") { Merged() }
        shoot("merged-on-dark", dark = true) { Merged() }
        shoot("merged-on-oled", dark = true, oled = true) { Merged() }
        shoot("merged-filter-groups-light") { Merged(filter = ChatListMerge.Filter.GROUPS) }
        // __MERGED_GROUP_SUBFILTER_2026_11_18__
        shoot("merged-filter-groups-collab-dark", dark = true) {
            Merged(filter = ChatListMerge.Filter.GROUPS, groupType = com.oshi.messenger.data.model.GroupType.COLLABORATIVE)
        }
        // __COVER_WAITING_CLOCK_2026_11_18__ a parked cover message next to a sent one.
        val parkedId = "cover-parked-shot"
        val store = object : com.oshi.messenger.service.CoverOutbox.IdStore {
            var ids = emptySet<String>()
            override fun read() = ids
            override fun write(ids: Set<String>) { this.ids = ids }
        }
        val parkScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        com.oshi.messenger.service.CoverOutbox.parkWith(store, parkScope, parkedId,
            attempt = { kotlinx.coroutines.awaitCancellation() }, onDelivered = {}, onGaveUp = {})
        for ((name, dark) in listOf("cover-waiting-light" to false, "cover-waiting-dark" to true)) {
            shoot(name, dark = dark) {
                Column(Modifier.fillMaxSize().padding(top = 80.dp, start = 8.dp, end = 8.dp)) {
                    val now = System.currentTimeMillis()
                    MessageBubble(message = Message(id = "cover-sent-shot", senderPublicKey = "me", recipientPublicKey = "peer",
                        content = "Sent before the block", timestamp = now - 120_000, isFromMe = true, isRead = false, isDelivered = true))
                    Spacer(Modifier.height(8.dp))
                    MessageBubble(message = Message(id = parkedId, senderPublicKey = "me", recipientPublicKey = "peer",
                        content = "Written while the network blocks OSHI", timestamp = now - 60_000, isFromMe = true, isRead = false, isDelivered = false))
                }
            }
        }
        parkScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        // __RESTRICTED_NETWORK_HINT_2026_11_18__ an incoming message that carried RN-HINT.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        com.oshi.messenger.util.RestrictedNetworkHintStore.add(ctx, "rn-hint-shot")
        for ((name, dark) in listOf("restricted-hint-light" to false, "restricted-hint-dark" to true)) {
            shoot(name, dark = dark) {
                Column(Modifier.fillMaxSize().padding(top = 80.dp, start = 8.dp, end = 8.dp)) {
                    val now = System.currentTimeMillis()
                    MessageBubble(message = Message(id = "rn-hint-shot", senderPublicKey = "peer", recipientPublicKey = "me",
                        content = "Internet is filtered here, writing from the café", timestamp = now - 90_000, isFromMe = false, isRead = true))
                    Spacer(Modifier.height(8.dp))
                    MessageBubble(message = Message(id = "rn-hint-reply", senderPublicKey = "me", recipientPublicKey = "peer",
                        content = "Got it, I'll keep it short", timestamp = now - 30_000, isFromMe = true, isRead = true, isDelivered = true))
                }
            }
        }
        // __SCREENSHOT_NOTICE_2026_11_18__ the pill as it appears over a blocked screenshot
        // (rendered here, in a test window without FLAG_SECURE; on a real OSHI window it
        // cannot be captured, by design).
        for ((name, dark) in listOf("screenshot-blocked-light" to false, "screenshot-blocked-dark" to true)) {
            shoot(name, dark = dark) {
                Box(Modifier.fillMaxSize()) {
                    Merged()
                    com.oshi.messenger.ui.components.ScreenCaptureNoticePill(
                        Modifier.align(androidx.compose.ui.Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp))
                }
            }
        }
        shoot("merged-filter-unread-oled", dark = true, oled = true) { Merged(filter = ChatListMerge.Filter.UNREAD) }
        shoot("merged-discover-empty-light") { Merged(convs = emptyList(), grps = emptyList(), near = emptyList()) }
        rule.onAllNodes(hasScrollAction())[0].performTouchInput { swipeUp(); }
        rule.onAllNodes(hasScrollAction())[0].performTouchInput { swipeUp(); }
        snap("merged-discover-empty-scrolled-light")
        shoot("merged-discover-teaser-dark", dark = true) { Merged(grps = emptyList(), near = emptyList()) }
        shoot("merged-new-sheet-light", settleMs = 1500) {
            Merged()
            NewChatSheet(onDismiss = {}, onNewChat = {}, onNewGroup = {}, onDiscover = {}, onScanInvite = {}, onCall = {})
        }
        shoot("merged-new-sheet-oled", dark = true, oled = true, settleMs = 1500) {
            Merged()
            NewChatSheet(onDismiss = {}, onNewChat = {}, onNewGroup = {}, onDiscover = {}, onScanInvite = {}, onCall = {})
        }
        shoot("merged-group-sheet-dark", dark = true, settleMs = 1500) {
            Merged()
            GroupRowSheet(groups[0], pinned = false, onDismiss = {}, onTogglePin = {}, onToggleMute = {}, onInfo = {}, onLeave = {})
        }
    }
}
