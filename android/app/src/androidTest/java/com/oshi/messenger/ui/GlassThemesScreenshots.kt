package com.oshi.messenger.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oshi.messenger.data.model.ChatListMerge
import com.oshi.messenger.data.model.Conversation
import com.oshi.messenger.data.model.DeliveryMethod
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.GroupConversation
import com.oshi.messenger.data.model.GroupMessage
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.ui.components.ChatWallpaper
import com.oshi.messenger.ui.components.ChatWallpaperBackground
import com.oshi.messenger.ui.components.FrostedChatBarBackdrop
import com.oshi.messenger.ui.components.OshiDynamicStyle
import com.oshi.messenger.ui.components.ReactionChip
import com.oshi.messenger.ui.components.SmartRepliesBar
import com.oshi.messenger.ui.screens.*
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.OSHITheme
import com.oshi.messenger.ui.theme.OshiAlertDialog
import com.oshi.messenger.ui.theme.glassGlow
import com.oshi.messenger.ui.theme.glassHairline
import com.oshi.messenger.ui.theme.glassLift
import com.oshi.messenger.ui.theme.glassOn
import com.oshi.messenger.ui.theme.glassTextFieldColors
import com.oshi.messenger.ui.theme.thenIf
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * __GLASS_ALL_THEMES_2026_11_21__ The « Effet verre » look in Light, Dark and
 * OLED black, on and off: the chat list (merged, with the real bottom bar), a
 * chat (frosted bars over the wallpaper, reactions, smart replies, composer), the
 * Appearance settings, a sheet and a dialog. FIXTURE data only. Whole-screen
 * captures (UiAutomation) so sheets and dialogs — their own windows, blurred
 * behind on API 31+ — are in the picture. PNGs land in the app's external files
 * dir (`glass-shots/`). Runs only with `-e glassShots true`.
 */
@RunWith(AndroidJUnit4::class)
class GlassThemesScreenshots {

    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun enabled() = InstrumentationRegistry.getArguments().getString("glassShots") == "true"

    private val now = System.currentTimeMillis()
    private val min = 60_000L

    private fun conv(key: String, alias: String, text: String, ago: Long, unread: Int = 0, fromMe: Boolean = false) =
        Conversation(
            participantPublicKey = key, participantAlias = alias, participantProfilePicture = null,
            lastMessage = Message(senderPublicKey = if (fromMe) "me" else key, recipientPublicKey = if (fromMe) key else "me",
                content = text, timestamp = now - ago, isFromMe = fromMe, isRead = true, isDelivered = true),
            unreadCount = unread, isOnline = unread > 0,
        )

    private fun group(id: String, name: String, sender: String, text: String, ago: Long, unread: Int = 0, members: Int = 5) =
        GroupConversation(
            group = Group(id = id, name = name, creatorPublicKey = "me", memberPublicKeys = List(members) { "m$it" },
                adminPublicKeys = listOf("me"), createdAt = now - 30 * 24 * 60 * min),
            lastMessage = GroupMessage(groupId = id, senderPublicKey = "s-$sender", senderName = sender, content = text,
                timestamp = now - ago, isFromMe = false),
            unreadCount = unread, memberNames = emptyMap(),
        )

    private val conversations = listOf(
        conv("MCowBQYDK2VwAyEAalice0000000000000000000001=", "Alice Martin", "That's exactly what I needed, thanks!", 3 * min, unread = 2),
        conv("MCowBQYDK2VwAyEAcharlie00000000000000000002=", "Charlie", "The privacy-first approach is the point", 55 * min, fromMe = true),
        conv("MCowBQYDK2VwAyEAdiana000000000000000000003=", "Diana Rossi", "See you tomorrow at 9", 26 * 60 * min),
        conv("MCowBQYDK2VwAyEAeric00000000000000000000004=", "Eric", "📍 Location shared", 3 * 24 * 60 * min, fromMe = true),
        conv("MCowBQYDK2VwAyEAfatima00000000000000000005=", "Fatima", "Les photos sont superbes 😍", 4 * 24 * 60 * min),
    )
    private val groups = listOf(
        group("8a6e0c2e-1f43-4b8a-9d56-3c1e2f0a9b11", "Book Club", "Marie", "Fitting choice for this month 📚", 12 * min, unread = 4),
        group("2b5d7e19-6c0a-4e3b-8f12-9a7c4d2e1f00", "Family", "Mum", "Count me in for Sunday lunch", 5 * 60 * min, members = 7),
        group("c4d3e2f1-0a9b-4c8d-a7e6-5f4e3d2c1b0a", "Climbing crew", "Sam", "Rain tomorrow, gym instead?", 2 * 24 * 60 * min),
    )

    private var current by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var darkNow by mutableStateOf(false)
    private var oledNow by mutableStateOf(false)
    private var glassNow by mutableStateOf<Boolean?>(null)
    private var hosted = false

    private fun shoot(name: String, dark: Boolean, oled: Boolean, glass: Boolean, settleMs: Long = 900, content: @Composable () -> Unit) {
        if (!hosted) {
            rule.setContent {
                OSHITheme(darkTheme = darkNow, oledBlack = oledNow, glass = glassNow) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { current?.invoke() }
                }
            }
            hosted = true
        }
        rule.runOnIdle { current = null }
        rule.waitForIdle()
        rule.runOnIdle { darkNow = dark; oledNow = oled; glassNow = glass; current = content }
        rule.waitForIdle()
        Thread.sleep(settleMs)
        rule.waitForIdle()
        val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "glass-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ── chat list ──────────────────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ChatList(repeat: Int = 1) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text("OSHI", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton({}) { Icon(Icons.Default.Email, null) }
                        IconButton({}) { Icon(Icons.Default.AccountCircle, null) }
                    },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {},
                    modifier = Modifier.thenIf(glassOn()) { glassGlow(RoundedCornerShape(16.dp)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    icon = { Icon(Icons.Default.Edit, null) },
                    text = { Text("New") },
                )
            },
            bottomBar = { com.oshi.messenger.ui.OSHIBottomNavigation(currentRoute = "messages", unreadCount = 6, onNavigate = {}) },
        ) { p ->
            MergedChatsBody(
                conversations = List(repeat) { r -> conversations.map { it.copy(participantPublicKey = it.participantPublicKey + r) } }.flatten(),
                groups = groups,
                pinned = setOf(ChatListMerge.pinKeyForGroup(groups[1].group.id)),
                muted = { false }, mentionedGroups = emptySet(), nearby = emptyList(),
                listState = rememberLazyListState(),
                onConversationClick = {}, onConversationLongClick = {}, onGroupClick = {}, onGroupLongClick = {},
                onDiscover = {}, onCreateGroup = {},
                emptyState = { _ -> },
                modifier = Modifier.padding(p),
            )
        }
    }

    // ── a chat ─────────────────────────────────────────────────────────────

    private fun msg(id: String, text: String, mine: Boolean, ago: Long, method: DeliveryMethod = DeliveryMethod.VPS) = Message(
        id = id, senderPublicKey = if (mine) "me" else "alice", recipientPublicKey = if (mine) "alice" else "me",
        content = text, timestamp = now - ago, isFromMe = mine, isRead = true, isDelivered = true, deliveryMethod = method,
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Chat() {
        val wallpaper = ChatWallpaper.OshiDynamic(OshiDynamicStyle.MESH.ordinal)
        val glass = glassOn()
        Box(Modifier.fillMaxSize()) {
            ChatWallpaperBackground(wallpaper, Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize()) {
                Box {
                    FrostedChatBarBackdrop(wallpaper = wallpaper, scrimColor = MaterialTheme.colorScheme.surface, modifier = Modifier.matchParentSize())
                    TopAppBar(
                        modifier = Modifier.statusBarsPadding(),
                        windowInsets = WindowInsets(0),
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        navigationIcon = { IconButton({}) { Icon(Icons.Default.ArrowBack, null) } },
                        title = {
                            Column {
                                Text("Alice Martin", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("online", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        actions = { IconButton({}) { Icon(Icons.Default.Call, null) }; IconButton({}) { Icon(Icons.Default.Videocam, null) } },
                    )
                }
                Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.Bottom) {
                    MessageBubble(message = msg("1", "Each user has a unique keypair. Messages are encrypted before they leave the phone.", false, 40 * min), isTail = true)
                    MessageBubble(message = msg("2", "That's impressive. Does it work offline too?", true, 35 * min), isTail = true)
                    MessageBubble(message = msg("3", "Yes — over Bluetooth mesh, and LoRa radio when there is no network at all.", false, 20 * min, DeliveryMethod.MESH), isTail = true)
                    Row(Modifier.padding(start = 16.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ReactionChip(code = "❤️", count = 2, isMine = true, onClick = {})
                        ReactionChip(code = "👍", count = 1, isMine = false, onClick = {})
                    }
                    MessageBubble(message = msg("4", "Sending one now 📡", true, 2 * min, DeliveryMethod.MESH), isTail = true)
                    SmartRepliesBar(replies = listOf("Got it!", "Thanks 🙏", "Talk later"), onReplySelected = {})
                }
                Box(Modifier.fillMaxWidth()) {
                    FrostedChatBarBackdrop(wallpaper = wallpaper, scrimColor = MaterialTheme.colorScheme.surface, modifier = Modifier.matchParentSize(), edgeOnTop = true)
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({}) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        OutlinedTextField(
                            value = "See you at the station",
                            onValueChange = {},
                            modifier = Modifier.weight(1f).thenIf(glass) { glassLift(RoundedCornerShape(24.dp), spread = 6.dp).glassHairline(RoundedCornerShape(24.dp)) },
                            shape = RoundedCornerShape(24.dp),
                            colors = if (glass) glassTextFieldColors() else OutlinedTextFieldDefaults.colors(),
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = {}, modifier = Modifier.thenIf(glass) { glassGlow() }) {
                            Icon(Icons.Default.Send, null)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }
        }
    }

    // ── settings ───────────────────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Settings(theme: String, oled: Boolean, glassChecked: Boolean) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = { TopAppBar(title = { Text("Appearance") }, navigationIcon = { IconButton({}) { Icon(Icons.Default.ArrowBack, null) } }) },
        ) { p ->
            LazyColumn(
                Modifier.fillMaxSize().padding(p).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                item {
                    SettingsSection(title = "Appearance") {
                        SettingsItem(icon = Icons.Default.Language, iconTint = OSHIPurple, title = "App language", subtitle = "System default")
                        Spacer(Modifier.height(8.dp))
                        ThemeSelector(currentTheme = theme, oledBlack = oled, onThemeSelected = {})
                        Spacer(Modifier.height(8.dp))
                        SettingsToggleItem(icon = Icons.Default.Contrast, iconTint = OSHIPurple,
                            title = androidx.compose.ui.res.stringResource(com.oshi.messenger.R.string.settings_theme_oled_black),
                            subtitle = androidx.compose.ui.res.stringResource(com.oshi.messenger.R.string.settings_theme_oled_black_desc),
                            checked = oled, subtitleMaxLines = 3, onCheckedChange = {})
                        Spacer(Modifier.height(8.dp))
                        SettingsToggleItem(icon = Icons.Default.BlurOn, iconTint = OSHIPurple,
                            title = androidx.compose.ui.res.stringResource(com.oshi.messenger.R.string.appearance_glass_effect),
                            subtitle = androidx.compose.ui.res.stringResource(com.oshi.messenger.R.string.appearance_glass_effect_footer),
                            checked = glassChecked, subtitleMaxLines = 3, onCheckedChange = {})
                    }
                }
                item {
                    SettingsSection(title = "Privacy") {
                        SettingsItem(icon = Icons.Default.Lock, iconTint = OSHIPurple, title = "App lock", subtitle = "Face or fingerprint after 1 minute")
                        SettingsItem(icon = Icons.Default.VisibilityOff, iconTint = OSHIPurple, title = "Disguise", subtitle = "Calculator icon")
                    }
                }
            }
        }
    }


    /**
     * Frame-time probe: `-e glassPerf <look tag>` keeps the chat list (×4 rows)
     * on screen and flings it up and down; the host reads
     * `dumpsys gfxinfo com.oshi.messenger` around the run.
     */
    @Test
    fun scrollProbe() {
        val tag = InstrumentationRegistry.getArguments().getString("glassPerf")
        assumeTrue(tag != null)
        val l = looks.first { it.tag == tag }
        rule.setContent {
            OSHITheme(darkTheme = l.dark, oledBlack = l.oled, glass = l.glass) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { ChatList(repeat = 4) }
            }
        }
        rule.waitForIdle()
        Thread.sleep(1500)
        val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(cmd: String): String = ua.executeShellCommand(cmd).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }
        }
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        shell("dumpsys gfxinfo $pkg reset")
        val list = rule.onAllNodes(androidx.compose.ui.test.hasScrollAction())[0]
        repeat(6) {
            list.performTouchInput { swipeUp(durationMillis = 250) }
            rule.waitForIdle()
            list.performTouchInput { swipeDown(durationMillis = 250) }
            rule.waitForIdle()
        }
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "glass-shots").apply { mkdirs() }
        File(dir, "perf-$tag.txt").writeText(shell("dumpsys gfxinfo $pkg"))
    }

    // ── run ────────────────────────────────────────────────────────────────

    private data class Look(val tag: String, val dark: Boolean, val oled: Boolean, val glass: Boolean, val theme: String)

    private val looks = listOf(
        Look("light-glass", dark = false, oled = false, glass = true, theme = "light"),
        Look("dark-glass", dark = true, oled = false, glass = true, theme = "dark"),
        Look("oled-glass", dark = true, oled = true, glass = true, theme = "dark"),
        Look("light-flat", dark = false, oled = false, glass = false, theme = "light"),
        Look("dark-flat", dark = true, oled = false, glass = false, theme = "dark"),
        Look("oled-flat", dark = true, oled = true, glass = false, theme = "dark"),
    )

    @Test
    fun captureGlassThemes() {
        assumeTrue(enabled())
        val only = InstrumentationRegistry.getArguments().getString("glassOnly")
        for (l in looks) {
            if (only != null && !l.tag.startsWith(only)) continue
            shoot("${l.tag}-1-chatlist", l.dark, l.oled, l.glass) { ChatList() }
            shoot("${l.tag}-2-chat", l.dark, l.oled, l.glass) { Chat() }
            shoot("${l.tag}-3-settings", l.dark, l.oled, l.glass) { Settings(l.theme, l.oled, l.glass) }
            shoot("${l.tag}-4-sheet", l.dark, l.oled, l.glass, settleMs = 1500) {
                ChatList()
                NewChatSheet(onDismiss = {}, onNewChat = {}, onNewGroup = {}, onDiscover = {}, onScanInvite = {}, onCall = {})
            }
            shoot("${l.tag}-5-dialog", l.dark, l.oled, l.glass, settleMs = 1200) {
                ChatList()
                OshiAlertDialog(
                    onDismissRequest = {},
                    confirmButton = { TextButton({}) { Text("Delete") } },
                    dismissButton = { TextButton({}) { Text("Cancel") } },
                    title = { Text("Delete this chat?") },
                    text = { Text("Messages are removed from this phone only. Alice keeps her copy.") },
                )
            }
        }
    }
}
