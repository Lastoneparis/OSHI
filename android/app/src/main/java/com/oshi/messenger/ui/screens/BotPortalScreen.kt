package com.oshi.messenger.ui.screens

import androidx.compose.foundation.background
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.oshi.messenger.R
import com.oshi.messenger.data.model.BotType
import com.oshi.messenger.data.model.Group
import com.oshi.messenger.data.model.OSHIBot
import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.ui.viewmodel.BotPortalViewModel
import com.oshi.messenger.ui.theme.OSHIGreen

/**
 * Localised label for a Portal tab.
 *
 * Exhaustive `when` on purpose: adding a fourth `PortalTab` will not compile
 * until it is given a string resource, which is the guarantee the previous
 * `enum.name` formatting could never offer.
 */
@androidx.annotation.StringRes
internal fun BotPortalViewModel.PortalTab.labelRes(): Int = when (this) {
    BotPortalViewModel.PortalTab.CHANNELS -> R.string.portal_tab_channels
    BotPortalViewModel.PortalTab.BOTS -> R.string.portal_tab_bots
    BotPortalViewModel.PortalTab.APPS -> R.string.portal_tab_apps
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotPortalScreen(
    onNavigateToBotDetail: (String) -> Unit,
    onNavigateToGroupChat: (String) -> Unit,
    onNavigateToCreateChannel: () -> Unit,
    onNavigateToDiscoverChannels: () -> Unit = {},
    onNavigateToChat: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: BotPortalViewModel = hiltViewModel()
) {
    val portalState by viewModel.portalState.collectAsState()
    var showCreateSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bot_portal_title)) },
                navigationIcon = {
                    // iOS reaches PortalView through MoreTabView's navigation stack,
                    // so it always has a back chevron. Android pushes it from
                    // Settings and had NO back affordance at all.
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = stringResource(R.string.accessibility_back)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (portalState.selectedTab == BotPortalViewModel.PortalTab.BOTS) {
                FloatingActionButton(
                    onClick = { showCreateSheet = true },
                    containerColor = Color(0xFF5616EC)
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.bot_portal_create_bot), tint = Color.White)
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Tab Row
            TabRow(
                selectedTabIndex = portalState.selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                BotPortalViewModel.PortalTab.entries.forEach { tab ->
                    Tab(
                        selected = portalState.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        // 🔧 These labels were derived from the Kotlin ENUM NAMES
                        // (`tab.name.lowercase().replaceFirstChar { it.uppercase() }`),
                        // so the Portal read "Channels / Bots / Apps" in all 34
                        // locales — and `replaceFirstChar` uses the DEFAULT locale,
                        // so a Turkish device rendered "Bots" with a dotless İ/ı.
                        text = { Text(stringResource(tab.labelRes())) }
                    )
                }
            }

            // Search Bar
            OutlinedTextField(
                value = portalState.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholder = { Text(stringResource(R.string.bot_portal_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )

            // Tab Content
            when (portalState.selectedTab) {
                BotPortalViewModel.PortalTab.CHANNELS -> ChannelsTab(
                    myChannels = portalState.myChannels,
                    discoveredChannels = portalState.discoveredChannels,
                    onChannelClick = onNavigateToGroupChat,
                    onJoinChannel = { viewModel.joinDiscoveredChannel(it) },
                    onCreateChannel = onNavigateToCreateChannel
                )
                BotPortalViewModel.PortalTab.BOTS -> BotsTab(
                    bots = portalState.filteredBots,
                    onBotClick = { onNavigateToBotDetail(it.id) },
                    onToggleBot = { viewModel.toggleBotEnabled(it) }
                )
                BotPortalViewModel.PortalTab.APPS -> AppsTab(
                    searchQuery = portalState.searchQuery,
                    onOpenPublicChannels = onNavigateToDiscoverChannels,
                    onOpenChatWithPeer = onNavigateToChat
                )
            }
        }
    }

    if (showCreateSheet) {
        BotCreateScreen(
            onDismiss = { showCreateSheet = false },
            viewModel = viewModel
        )
    }
}

@Composable
private fun ChannelsTab(
    myChannels: List<Group>,
    discoveredChannels: List<GroupManager.DiscoveredGroup>,
    onChannelClick: (String) -> Unit,
    onJoinChannel: (GroupManager.DiscoveredGroup) -> Unit,
    onCreateChannel: () -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // My Channels section
        if (myChannels.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.bot_portal_my_channels),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(myChannels) { channel ->
                ChannelRow(
                    name = channel.name,
                    memberCount = channel.memberPublicKeys.size,
                    isJoined = true,
                    onClick = { onChannelClick(channel.id) }
                )
            }
        }

        // Discovered Channels section
        if (discoveredChannels.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.bot_portal_discovered_channels),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            items(discoveredChannels) { discovered ->
                ChannelRow(
                    name = discovered.groupName,
                    memberCount = discovered.memberCount,
                    isJoined = false,
                    onClick = { onJoinChannel(discovered) }
                )
            }
        }

        // Empty state
        if (myChannels.isEmpty() && discoveredChannels.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Explore,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.bot_portal_no_channels), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.bot_portal_discover_channels),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Create Channel button
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onCreateChannel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5616EC))
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.bot_portal_create_channel))
            }
        }
    }
}

@Composable
private fun ChannelRow(
    name: String,
    memberCount: Int,
    isJoined: Boolean,
    onClick: () -> Unit
) {
    OshiCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF5616EC).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Campaign,
                    contentDescription = null,
                    tint = Color(0xFF5616EC),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    stringResource(R.string.bot_portal_members, memberCount),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isJoined) {
                Surface(
                    color = OSHIGreen.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        stringResource(R.string.bot_portal_joined),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = OSHIGreen
                    )
                }
            } else {
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5616EC)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(stringResource(R.string.bot_portal_join), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun BotsTab(
    bots: List<OSHIBot>,
    onBotClick: (OSHIBot) -> Unit,
    onToggleBot: (String) -> Unit
) {
    if (bots.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("\uD83E\uDD16", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.bot_portal_no_bots), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.bot_portal_create_first_bot),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(bots) { bot ->
                BotCard(
                    bot = bot,
                    onClick = { onBotClick(bot) },
                    onToggle = { onToggleBot(bot.id) }
                )
            }
        }
    }
}

@Composable
private fun BotCard(bot: OSHIBot, onClick: () -> Unit, onToggle: () -> Unit) {
    OshiCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Emoji Avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(bot.avatarEmoji, fontSize = 24.sp)
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(bot.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    "@${bot.username}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val botType = try {
                        BotType.valueOf(bot.type.uppercase())
                    } catch (_: Exception) {
                        BotType.AUTOMATION
                    }
                    Surface(
                        color = Color(0xFF5616EC).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "${botType.emoji} ${botType.displayName}",
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = Color(0xFF5616EC)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.bot_portal_msgs, bot.messagesSent),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Switch(
                checked = bot.isEnabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// APPS TAB — the five mesh apps iOS ships in PortalView.appsTab
// (BotViews.swift:494-609). Was a "Coming soon" placeholder.
//
// Every one of these is a thin read-only view over mesh state on iOS too, so
// PeersViewModel's existing flows cover all of them — no manager or repository
// file had to change.
// ════════════════════════════════════════════════════════════════════════════

private enum class MeshAppId { RADAR, CHANNELS, FILE_SHARE, RELAY, NETWORK_MAP }

private data class MeshApp(
    val id: MeshAppId,
    val name: String,
    val desc: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color
)

@Composable
private fun AppsTab(
    searchQuery: String,
    onOpenPublicChannels: () -> Unit,
    onOpenChatWithPeer: (String) -> Unit,
    peersViewModel: com.oshi.messenger.ui.viewmodel.PeersViewModel = hiltViewModel()
) {
    val connectedPeers by peersViewModel.connectedPeers.collectAsState()
    val discoveredPeers by peersViewModel.discoveredPeers.collectAsState()
    val isAdvertising by peersViewModel.isAdvertising.collectAsState()
    val isBrowsing by peersViewModel.isBrowsing.collectAsState()
    // iOS reads a single `meshAvailable` flag; Android's nearest equivalent is
    // "we are advertising or browsing" (PeersViewModel has no meshAvailable).
    val meshActive = isAdvertising || isBrowsing

    var openApp by remember { mutableStateOf<MeshAppId?>(null) }

    val apps = listOf(
        MeshApp(MeshAppId.RADAR, stringResource(R.string.portal_app_radar),
            stringResource(R.string.portal_app_radar_desc), Icons.Default.Radar, Color(0xFF007AFF)),
        MeshApp(MeshAppId.CHANNELS, stringResource(R.string.portal_app_public_channels),
            stringResource(R.string.portal_app_public_channels_desc), Icons.Default.Campaign, Color(0xFF5616EC)),
        MeshApp(MeshAppId.FILE_SHARE, stringResource(R.string.portal_app_file_share),
            stringResource(R.string.portal_app_file_share_desc), Icons.Default.SwapVert, Color(0xFFFF9500)),
        MeshApp(MeshAppId.RELAY, stringResource(R.string.portal_app_relay),
            stringResource(R.string.portal_app_relay_desc), Icons.Default.Sync, Color(0xFFAF52DE)),
        MeshApp(MeshAppId.NETWORK_MAP, stringResource(R.string.portal_app_network_map),
            stringResource(R.string.portal_app_network_map_desc), Icons.Default.Hub, Color(0xFF30B0C7)),
    )

    val filtered = apps.filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }

    LazyColumn(
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.portal_apps_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.portal_apps_description),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Mesh status banner (iOS BotViews.swift:524)
        item {
            MeshStatusBanner(isActive = meshActive, peerCount = connectedPeers.size)
        }

        items(filtered) { app ->
            MeshAppRow(
                name = app.name,
                desc = app.desc,
                icon = app.icon,
                color = app.color,
                onClick = {
                    if (app.id == MeshAppId.CHANNELS) onOpenPublicChannels() else openApp = app.id
                }
            )
        }
    }

    when (openApp) {
        MeshAppId.RADAR -> MeshAppSheet(stringResource(R.string.portal_app_radar), { openApp = null }) {
            MeshRadarContent(connectedPeers, discoveredPeers, meshActive)
        }
        MeshAppId.FILE_SHARE -> MeshAppSheet(stringResource(R.string.portal_app_file_share), { openApp = null }) {
            MeshFileShareContent(connectedPeers) { publicKey ->
                openApp = null
                onOpenChatWithPeer(publicKey)
            }
        }
        MeshAppId.RELAY -> MeshAppSheet(stringResource(R.string.portal_app_relay), { openApp = null }) {
            MeshRelayContent(connectedPeers.size, meshActive)
        }
        MeshAppId.NETWORK_MAP -> MeshAppSheet(stringResource(R.string.portal_app_network_map), { openApp = null }) {
            MeshNetworkMapContent(connectedPeers, discoveredPeers.size, meshActive)
        }
        MeshAppId.CHANNELS, null -> Unit
    }
}

@Composable
private fun MeshStatusBanner(isActive: Boolean, peerCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (isActive) OSHIGreen.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isActive) Icons.Default.CellTower else Icons.Default.PortableWifiOff,
                contentDescription = null,
                tint = if (isActive) OSHIGreen else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(if (isActive) R.string.portal_mesh_active else R.string.portal_mesh_inactive),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    if (isActive) stringResource(R.string.portal_peers_connected, peerCount)
                    else stringResource(R.string.portal_mesh_inactive_hint),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) OSHIGreen
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
            )
        }
    }
}

@Composable
private fun MeshAppRow(
    name: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    desc,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Common chrome for a mesh app — iOS pushes these onto the nav stack. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeshAppSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    OshiModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/** iOS `MeshRadarView` — BotViews.swift:672. */
@Composable
private fun MeshRadarContent(
    connectedPeers: List<com.oshi.messenger.network.mesh.MeshPeer>,
    discoveredPeers: List<com.oshi.messenger.network.mesh.MeshPeer>,
    meshActive: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (meshActive) Icons.Default.CellTower else Icons.Default.PortableWifiOff,
            contentDescription = null,
            tint = if (meshActive) OSHIGreen else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                stringResource(if (meshActive) R.string.portal_radar_mesh_on else R.string.portal_radar_mesh_off),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(
                    R.string.portal_radar_peer_summary,
                    connectedPeers.size,
                    discoveredPeers.size
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (connectedPeers.isEmpty() && discoveredPeers.isEmpty()) {
        Text(
            stringResource(R.string.portal_radar_no_peers),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 24.dp)
        )
        return
    }

    if (connectedPeers.isNotEmpty()) {
        Text(stringResource(R.string.portal_radar_connected), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        connectedPeers.forEach { peer -> PeerDotRow(peer.displayName, OSHIGreen) }
    }
    if (discoveredPeers.isNotEmpty()) {
        Text(stringResource(R.string.portal_radar_discovered), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        discoveredPeers.forEach { peer -> PeerDotRow(peer.displayName, Color(0xFFFF9500)) }
    }
}

@Composable
private fun PeerDotRow(name: String, dotColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Spacer(modifier = Modifier.width(10.dp))
        Text(name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** iOS `MeshFileShareView` — BotViews.swift:757. */
@Composable
private fun MeshFileShareContent(
    connectedPeers: List<com.oshi.messenger.network.mesh.MeshPeer>,
    onSendTo: (publicKey: String) -> Unit
) {
    Text(
        stringResource(R.string.portal_fileshare_info),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (connectedPeers.isEmpty()) {
        Text(
            stringResource(R.string.portal_fileshare_no_peers),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 24.dp)
        )
        return
    }
    connectedPeers.forEach { peer ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(OSHIGreen))
            Spacer(modifier = Modifier.width(10.dp))
            Text(peer.displayName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // iOS's Send button only flips a `showFilePicker` flag with no picker
            // wired behind it (BotViews.swift:790) — a dead control. Route to the
            // peer's chat instead, where the attachment picker actually works.
            TextButton(
                onClick = { onSendTo(peer.publicKey) },
                enabled = peer.publicKey.isNotBlank()
            ) {
                Text(stringResource(R.string.portal_fileshare_send))
            }
        }
    }
}

/** iOS `MeshRelayView` — BotViews.swift:825 (informational, no counters on iOS either). */
@Composable
private fun MeshRelayContent(connectedCount: Int, meshActive: Boolean) {
    Text(stringResource(R.string.portal_relay_title), fontWeight = FontWeight.SemiBold)
    Text(
        stringResource(R.string.portal_relay_desc),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(stringResource(R.string.portal_relay_how), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    listOf(
        R.string.portal_relay_step1,
        R.string.portal_relay_step2,
        R.string.portal_relay_step3
    ).forEachIndexed { index, res ->
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.size(20.dp).clip(CircleShape).background(Color(0xFF5616EC)),
                contentAlignment = Alignment.Center
            ) {
                Text("${index + 1}", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(stringResource(res), fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(stringResource(R.string.portal_relay_status), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    StatRow(stringResource(R.string.portal_netmap_connected), connectedCount.toString())
    StatRow(
        stringResource(R.string.portal_relay_status),
        stringResource(if (meshActive) R.string.portal_relay_active else R.string.portal_relay_inactive)
    )
}

/** iOS `MeshNetworkMapView` — BotViews.swift:879 (radial topology + 3 stat rows). */
@Composable
private fun MeshNetworkMapContent(
    connectedPeers: List<com.oshi.messenger.network.mesh.MeshPeer>,
    discoveredCount: Int,
    meshActive: Boolean
) {
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier.fillMaxWidth().height(260.dp),
        contentAlignment = Alignment.Center
    ) {
        // Peers laid out on a circle of radius 100dp, angle = i/total*2π − π/2,
        // exactly the iOS layout (BotViews.swift:914).
        val total = connectedPeers.size
        connectedPeers.forEachIndexed { index, peer ->
            val angle = (index.toFloat() / total) * 2f * Math.PI.toFloat() - (Math.PI.toFloat() / 2f)
            val dx = (100 * kotlin.math.cos(angle)).dp
            val dy = (100 * kotlin.math.sin(angle)).dp
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.offset(x = dx, y = dy)
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(OSHIGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Smartphone, contentDescription = null, tint = Color.White,
                        modifier = Modifier.size(20.dp))
                }
                Text(peer.displayName.take(8), fontSize = 10.sp, maxLines = 1)
            }
        }
        // Centre node = you
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF5616EC)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Smartphone, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(26.dp))
            }
            Text(stringResource(R.string.portal_netmap_you), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
    Surface(shape = RoundedCornerShape(12.dp), color = surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatRow(stringResource(R.string.portal_netmap_connected), connectedPeers.size.toString())
            StatRow(stringResource(R.string.portal_netmap_discovered), discoveredCount.toString())
            StatRow(
                stringResource(R.string.portal_netmap_active),
                if (meshActive) "✓" else "✕"
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
