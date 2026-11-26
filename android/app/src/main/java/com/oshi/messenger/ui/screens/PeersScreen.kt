package com.oshi.messenger.ui.screens

import androidx.compose.animation.core.*
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.oshi.messenger.R
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.oshi.messenger.network.mesh.ConnectionType
import com.oshi.messenger.network.mesh.MeshPeer
import com.oshi.messenger.network.mesh.MeshPermissionPolicy
import com.oshi.messenger.network.mesh.MeshPermissionRequests
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIOrange
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.viewmodel.MeshStatus
import com.oshi.messenger.ui.viewmodel.MeshStatusCopy
import com.oshi.messenger.ui.viewmodel.PeerStatus
import com.oshi.messenger.ui.viewmodel.PeersViewModel

/**
 * PeersScreen - Mesh Network Peer Discovery and Management
 *
 * Matches iOS PeersView.swift functionality:
 * 1. Status header with animated indicator (Searching, Connected, Offline)
 * 2. Peer count badges (connected vs discovered)
 * 3. Peer list with sections:
 *    - Connected peers (green indicator)
 *    - Nearby/Discovered peers (orange indicator)
 *    - Recent/Disconnected peers (gray)
 * 4. Peer row items with avatar, name, status badge, public key preview
 * 5. Connect/Invite functionality
 * 6. Empty state with instructions
 * 7. Pull-to-refresh for scanning
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersScreen(
    onNavigateBack: () -> Unit,
    viewModel: PeersViewModel = hiltViewModel()
) {
    val meshStatus by viewModel.meshStatus.collectAsState()
    val statusCopy by viewModel.statusCopy.collectAsState()
    val connectedPeers by viewModel.connectedPeers.collectAsState()
    val discoveredPeers by viewModel.discoveredPeers.collectAsState()
    val disconnectedPeers by viewModel.disconnectedPeers.collectAsState()
    val connectedCount by viewModel.connectedCount.collectAsState()
    val nearbyCount by viewModel.nearbyCount.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isAdvertising by viewModel.isAdvertising.collectAsState()
    val isBrowsing by viewModel.isBrowsing.collectAsState()
    val autoReconnectEnabled by viewModel.autoReconnectEnabled.collectAsState()
    val showMeshInfo by viewModel.showMeshInfo.collectAsState()
    val missingBlePermissions by viewModel.missingBlePermissions.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // __MESH_PERMANENT_DENIAL_2026_10_12__ Whether the Bluetooth permissions are
    // still obtainable by ASKING, re-read every time this screen resumes.
    //
    // This was `var deniedInSession by remember { mutableStateOf(false) }` —
    // session- AND composition-scoped. A refusal from a previous launch, or from
    // before the user last left this screen, was forgotten, so the card went back
    // to offering "Allow" and its button called a launcher that the system will
    // not show a dialog for. Tapping it did nothing, every time. Reported on
    // Google Play 12/10/2026: "the Bluetooth permission feature… nothing happens".
    //
    // `shouldShowRequestPermissionRationale` is false BOTH for a permission never
    // asked for and for one refused twice, so it cannot answer this alone — hence
    // the persisted "we have asked for this" set.
    val activity = context as? Activity
    var permissionEpoch by remember { mutableIntStateOf(0) }

    val mustOpenSettings = remember(permissionEpoch) {
        val stillMissing = missingMeshRuntimePermissions(context)
        MeshPermissionPolicy.mustOpenAppSettings(
            stillMissing = stillMissing,
            everAsked = MeshPermissionRequests.everAsked(context),
            shouldShowRationale = stillMissing.filterTo(mutableSetOf()) { perm ->
                activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // An EMPTY result map means no dialog was shown at all, which is the
        // permanently-denied case — see MeshPermissionPolicy.resultIsRefusal.
        // The Wi-Fi Aware ride-along is not what this card is about: judge the
        // Bluetooth answer alone (an empty map still counts as a refusal).
        if (MeshPermissionPolicy.resultIsRefusal(grants.filterKeys { it != MeshPermissionPolicy.NEARBY_WIFI_DEVICES })) permissionEpoch++
        // Bring BLE up straight away rather than waiting up to 30 s for the next
        // ensureBleRunning() tick — the window in which a user decides the
        // feature does not work.
        viewModel.onScreenResumed()
    }

    // The Activity resumes when a permission dialog closes and when the user
    // comes back from app settings, so this catches a grant made anywhere.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onScreenResumed()
                // Re-read the verdict: the user may have just granted from app settings.
                permissionEpoch++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // __THE_CONNECT_BUTTON_WAS_A_COMMENT_2026_11_07__ Connecter used to run no
    // code and show nothing, which looks exactly like code that ran and failed.
    // Every press now answers.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.connectFeedback.collect { feedback ->
            snackbarHostState.showSnackbar(
                message = context.getString(feedback.messageRes, feedback.peerName),
                duration = SnackbarDuration.Short,
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.peers_title_nearby)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.accessibility_back))
                    }
                },
                actions = {
                    // Refresh button
                    IconButton(onClick = { viewModel.refresh() }) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = OSHIPurple
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.accessibility_refresh))
                        }
                    }
                    IconButton(onClick = { viewModel.showMeshInfo() }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.accessibility_info),
                            tint = OSHIPurple
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Status Header
                StatusHeader(
                    meshStatus = meshStatus,
                    statusCopy = statusCopy,
                    connectedCount = connectedCount,
                    nearbyCount = nearbyCount,
                    isActive = isAdvertising || isBrowsing
                )

                // The mesh cannot work without this, and until now the only
                // sign of a denial was the status card. Give it a button.
                if (meshStatus == MeshStatus.PERMISSION_REQUIRED || missingBlePermissions.isNotEmpty()) {
                    MeshPermissionCard(
                        permanentlyDenied = mustOpenSettings,
                        onRequest = {
                            val needed = missingMeshRuntimePermissions(context)
                            if (needed.isEmpty()) viewModel.onScreenResumed()
                            else {
                                // Record the ask BEFORE launching: this is the fact
                                // `shouldShowRequestPermissionRationale` cannot supply.
                                // __WIFI_AWARE_2026_11_25__ + NEARBY_WIFI_DEVICES on 13+,
                                // same "Nearby devices" dialog — no extra prompt.
                                val ask = (needed + MeshPermissionRequests.wifiAwareRideAlong(context)).distinct()
                                MeshPermissionRequests.recordAsked(context, ask)
                                permissionLauncher.launch(ask.toTypedArray())
                            }
                        },
                        onOpenSettings = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                    )
                }

                // Quick Controls Row
                QuickControlsRow(
                    isAdvertising = isAdvertising,
                    isBrowsing = isBrowsing,
                    autoReconnectEnabled = autoReconnectEnabled,
                    onToggleAdvertising = { viewModel.toggleAdvertising() },
                    onToggleBrowsing = { viewModel.toggleBrowsing() },
                    onToggleAutoReconnect = { viewModel.toggleAutoReconnect() }
                )

                // Peers List Section
                PeersListSection(
                    connectedPeers = connectedPeers,
                    discoveredPeers = discoveredPeers,
                    disconnectedPeerIds = disconnectedPeers,
                    isBrowsing = isBrowsing,
                    onConnectPeer = { viewModel.connectToPeer(it) },
                    onDisconnectPeer = { viewModel.disconnectPeer(it) },
                    onReconnectPeer = { viewModel.reconnectPeer(it) },
                    onStartMesh = { viewModel.startMesh() },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    // Mesh Info Bottom Sheet
    if (showMeshInfo) {
        MeshInfoSheet(
            onDismiss = { viewModel.hideMeshInfo() }
        )
    }
}

// ============================================================================
// PERMISSION CARD
// ============================================================================

/**
 * The Bluetooth runtime permissions this screen still needs, in ask order.
 *
 * Sourced from [MeshPermissionPolicy] rather than spelled out again, so the set
 * the screen REQUESTS and the set `CrossPlatformMesh` REPORTS MISSING cannot
 * drift: a permission in one list and not the other is one that is never asked
 * for AND never reported — invisible in both directions, which is exactly how
 * the mesh came to ship without ever being armed.
 *
 * Deliberately the BLE lanes only. `ACCESS_FINE_LOCATION` and
 * `NEARBY_WIFI_DEVICES` belong to Nearby Connections and Wi-Fi Direct and never
 * carry a byte of iOS traffic; bundling them here turns one "Allow" into a
 * three-dialog interrogation for permissions the iOS lane does not use.
 */
private fun missingMeshRuntimePermissions(context: android.content.Context): List<String> =
    MeshPermissionRequests.missingForBleLanes(context)


/**
 * Says what is missing, why it matters, and offers the one control that fixes
 * it.
 *
 * Until this existed the Peers screen had **zero** references to permissions —
 * a phone holding no Bluetooth grant rendered "Searching…" indefinitely, with
 * nothing on screen a user could press. On the owner's own Galaxy A17 every one
 * of BLUETOOTH_SCAN / _ADVERTISE / _CONNECT / NEARBY_WIFI_DEVICES /
 * ACCESS_FINE_LOCATION read `granted=false` because the app had never asked.
 */
@Composable
private fun MeshPermissionCard(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.BluetoothDisabled,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.peers_permission_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = stringResource(
                    if (permanentlyDenied) R.string.peers_permission_card_denied_body
                    else R.string.peers_permission_card_body
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // A shared LAN is the real requirement, not proximity — say so here
            // rather than letting a user next to a friend on cellular conclude
            // the whole feature is broken.
            Text(
                text = stringResource(R.string.peers_same_wifi_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = if (permanentlyDenied) onOpenSettings else onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = stringResource(
                        if (permanentlyDenied) R.string.peers_permission_card_settings
                        else R.string.peers_permission_card_action
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ============================================================================
// STATUS HEADER
// ============================================================================

@Composable
private fun StatusHeader(
    meshStatus: MeshStatus,
    statusCopy: MeshStatusCopy,
    connectedCount: Int,
    nearbyCount: Int,
    isActive: Boolean
) {
    // Pulsing animation for scanning indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOut),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        // __STATUS_TEXT_WAS_A_RIBBON_2026_10_27__ Reporter's screenshot, Android
        // 1.6.54: "Recherche..." and the Bluetooth hint rendered ONE OR TWO
        // CHARACTERS PER LINE, a vertical ribbon roughly 1 400 px tall.
        //
        // Cause: this used to be a single Row holding the icon, a text Column
        // with `weight(1f)`, and an UNWEIGHTED Row of the two stat badges.
        // Compose measures unweighted children FIRST, at their full intrinsic
        // width, and gives the weighted child whatever is left. "Connecte" and
        // "A proximite" are not short, and this app multiplies the system font
        // scale by its own preference (MainActivity: `baseDensity.fontScale *
        // fontScalePref`, up to 1.4x), so on a phone already set to large text
        // the two badges took almost the whole row and the status column was
        // starved to about three characters.
        //
        // It also explains the SECOND symptom in the same screenshot, which
        // looked unrelated: the parent Column is not scrollable and gives
        // `PeersListSection` `weight(1f)` — i.e. the remainder. A header that
        // tall pushed the quick-controls row to the bottom edge, where only a
        // sliver of each button was drawn, and left the peers list no height at
        // all. One starved text column hid half the screen.
        //
        // Stacked rather than re-tuned: the badges get their own row, so no
        // amount of font scale, and no longer translation, can starve the text.
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Animated Status Icon
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center
            ) {
                // Pulse effect when active
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .scale(pulseScale)
                            .alpha(pulseAlpha)
                            .clip(CircleShape)
                            .background(OSHIPurple.copy(alpha = 0.3f))
                    )
                }

                // Main icon circle
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = if (isActive) {
                                    listOf(OSHIPurple, OSHIPurple.copy(alpha = 0.7f))
                                } else {
                                    listOf(Color.Gray, Color.Gray.copy(alpha = 0.7f))
                                }
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CellTower,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Status Text
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(statusCopy.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    // Spread rather than a hand-built string: the connected
                    // subtitles carry counts, and a locale is free to reorder
                    // them (%2$d before %1$d) — which concatenation cannot do.
                    text = stringResource(
                        statusCopy.subtitleRes,
                        *statusCopy.subtitleArgs.toTypedArray()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

        }

            // Stats Badges, on their own row. Each takes an equal share, so a
            // long label wraps inside its own half instead of eating the row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatBadge(
                    count = connectedCount,
                    label = stringResource(R.string.mesh_status_connected),
                    color = OSHIGreen,
                    modifier = Modifier.weight(1f)
                )
                StatBadge(
                    count = nearbyCount,
                    label = stringResource(R.string.peers_nearby),
                    color = OSHIOrange,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatBadge(
    count: Int,
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============================================================================
// QUICK CONTROLS ROW
// ============================================================================

@Composable
private fun QuickControlsRow(
    isAdvertising: Boolean,
    isBrowsing: Boolean,
    autoReconnectEnabled: Boolean,
    onToggleAdvertising: () -> Unit,
    onToggleBrowsing: () -> Unit,
    onToggleAutoReconnect: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CompactControlButton(
            icon = Icons.Default.Visibility,
            title = stringResource(R.string.peers_control_visible),
            isActive = isAdvertising,
            color = OSHIGreen,
            onClick = onToggleAdvertising,
            modifier = Modifier.weight(1f)
        )
        CompactControlButton(
            icon = Icons.Default.Search,
            title = stringResource(R.string.peers_control_scan),
            isActive = isBrowsing,
            color = OSHIOrange,
            onClick = onToggleBrowsing,
            modifier = Modifier.weight(1f)
        )
        CompactControlButton(
            icon = Icons.Default.Autorenew,
            title = stringResource(R.string.peers_control_auto),
            isActive = autoReconnectEnabled,
            color = OSHIPurple,
            onClick = onToggleAutoReconnect,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CompactControlButton(
    icon: ImageVector,
    title: String,
    isActive: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) color else color.copy(alpha = 0.1f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                icon,
                contentDescription = title,
                modifier = Modifier.size(18.dp),
                tint = if (isActive) Color.White else color
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ============================================================================
// PEERS LIST SECTION
// ============================================================================

@Composable
private fun PeersListSection(
    connectedPeers: List<MeshPeer>,
    discoveredPeers: List<MeshPeer>,
    disconnectedPeerIds: Set<String>,
    isBrowsing: Boolean,
    onConnectPeer: (MeshPeer) -> Unit,
    onDisconnectPeer: (MeshPeer) -> Unit,
    onReconnectPeer: (String) -> Unit,
    onStartMesh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column {
            // Section Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.People,
                        contentDescription = null,
                        tint = OSHIPurple,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.peers_section_devices),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (isBrowsing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = OSHIPurple
                    )
                }
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            // Peers List or Empty State
            if (connectedPeers.isEmpty() && discoveredPeers.isEmpty() && disconnectedPeerIds.isEmpty()) {
                EmptyPeersView(
                    isBrowsing = isBrowsing,
                    onStartMesh = onStartMesh
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Connected Peers
                    items(connectedPeers, key = { "connected_${it.id}" }) { peer ->
                        PeerRow(
                            peer = peer,
                            status = PeerStatus.CONNECTED,
                            onAction = { onDisconnectPeer(peer) }
                        )
                        if (peer != connectedPeers.last() ||
                            discoveredPeers.isNotEmpty() ||
                            disconnectedPeerIds.isNotEmpty()
                        ) {
                            Divider(
                                modifier = Modifier.padding(start = 56.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            )
                        }
                    }

                    // Discovered/Nearby Peers
                    items(discoveredPeers, key = { "discovered_${it.id}" }) { peer ->
                        PeerRow(
                            peer = peer,
                            status = PeerStatus.NEARBY,
                            onAction = { onConnectPeer(peer) }
                        )
                        if (peer != discoveredPeers.last() || disconnectedPeerIds.isNotEmpty()) {
                            Divider(
                                modifier = Modifier.padding(start = 56.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            )
                        }
                    }

                    // Disconnected Peers
                    items(disconnectedPeerIds.toList(), key = { "disconnected_$it" }) { peerId ->
                        DisconnectedPeerRow(
                            peerId = peerId,
                            onReconnect = { onReconnectPeer(peerId) }
                        )
                        if (peerId != disconnectedPeerIds.last()) {
                            Divider(
                                modifier = Modifier.padding(start = 56.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// PEER ROW
// ============================================================================

@Composable
private fun PeerRow(
    peer: MeshPeer,
    status: PeerStatus,
    onAction: () -> Unit
) {
    val statusColor = when (status) {
        PeerStatus.CONNECTED -> OSHIGreen
        PeerStatus.NEARBY -> OSHIOrange
        PeerStatus.DISCONNECTED -> Color.Gray
    }

    val statusText = when (status) {
        PeerStatus.CONNECTED -> stringResource(R.string.mesh_status_connected)
        PeerStatus.NEARBY -> stringResource(R.string.peers_nearby)
        PeerStatus.DISCONNECTED -> stringResource(R.string.call_state_disconnected)
    }

    val actionText = when (status) {
        PeerStatus.CONNECTED -> stringResource(R.string.settings_disconnect)
        PeerStatus.NEARBY -> stringResource(R.string.peers_action_connect)
        PeerStatus.DISCONNECTED -> stringResource(R.string.peers_action_reconnect)
    }

    val actionColor = when (status) {
        PeerStatus.CONNECTED -> MaterialTheme.colorScheme.error
        else -> OSHIPurple
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with status indicator
        Box {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(statusColor, statusColor.copy(alpha = 0.6f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peer.displayName.take(2).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Online indicator for connected peers
            if (status == PeerStatus.CONNECTED) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(OSHIGreen)
                    )
                }
            }
        }

        // Peer Info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = peer.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
                // Show platform badge for cross-platform peers
                if (peer.connectionType == ConnectionType.CROSS_PLATFORM) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF007AFF).copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = stringResource(R.string.peers_tag_ios),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = Color(0xFF007AFF)
                        )
                    }
                }
                // __WIFI_AWARE_2026_11_25__ Same badge, for the Wi-Fi Aware lane.
                if (peer.connectionType == ConnectionType.WIFI_AWARE) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = OSHIGreen.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = stringResource(R.string.peers_tag_wifi_aware),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = OSHIGreen
                        )
                    }
                }
                // Connection type badge for Nearby Connections peers
                if (peer.connectionType == ConnectionType.NEARBY) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = OSHIGreen.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = stringResource(R.string.peers_nearby),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = OSHIGreen
                        )
                    }
                }
            }
        }

        // Action Button
        Surface(
            modifier = Modifier.clickable(onClick = onAction),
            shape = RoundedCornerShape(6.dp),
            color = actionColor.copy(alpha = 0.1f)
        ) {
            Text(
                text = actionText,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = actionColor
            )
        }
    }
}

@Composable
private fun DisconnectedPeerRow(
    peerId: String,
    onReconnect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color.Gray, Color.Gray.copy(alpha = 0.6f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = peerId.take(2).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // Info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = peerId,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.call_state_disconnected),
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }

        // Reconnect Button
        Surface(
            modifier = Modifier.clickable(onClick = onReconnect),
            shape = RoundedCornerShape(6.dp),
            color = OSHIPurple.copy(alpha = 0.1f)
        ) {
            Text(
                text = stringResource(R.string.peers_action_reconnect),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = OSHIPurple
            )
        }
    }
}

// ============================================================================
// EMPTY STATE
// ============================================================================

@Composable
private fun EmptyPeersView(
    isBrowsing: Boolean,
    onStartMesh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.PortableWifiOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )

        Text(
            text = stringResource(R.string.peers_none_found_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium
        )

        Text(
            text = stringResource(R.string.peers_none_found_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        // The single most common reason this list is empty: the two phones are
        // not on the same Wi-Fi. BLE only hands over an ip and a port; the
        // messages themselves are TCP/IP, so proximity alone buys nothing.
        Text(
            text = stringResource(R.string.peers_same_wifi_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        if (!isBrowsing) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onStartMesh,
                colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.peers_start_scanning),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ============================================================================
// MESH INFO BOTTOM SHEET
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeshInfoSheet(
    onDismiss: () -> Unit
) {
    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // __INFO_SHEET_COULD_NOT_SCROLL_2026_10_27__ Owner report N8: "impossible
        // de defiler sur les differents menus de Pairs a proximite". This Column
        // holds a title, three sections, nine text items and the Done button, and
        // a ModalBottomSheet does NOT scroll its content for you. On any phone the
        // Tips section and the button it sits above were simply off the bottom of
        // the screen, with no way to reach them: not a layout nicety, a dead end.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Title
            Text(
                text = stringResource(R.string.peers_mesh_info_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            // About Section
            InfoSection(title = stringResource(R.string.peers_mesh_info_about)) {
                InfoItem(
                    title = stringResource(R.string.peers_mesh_info_what_title),
                    description = stringResource(R.string.peers_mesh_info_what_desc)
                )
            }

            // Features Section
            InfoSection(title = stringResource(R.string.peers_mesh_info_features)) {
                // Was "Up to ~10km via multi-hop relay (200m per hop, max 50
                // hops)" — a radio-mesh claim for a transport that carries no
                // message data over the radio at all.
                InfoItem(
                    title = stringResource(R.string.peers_info_reach_title),
                    description = stringResource(R.string.peers_info_reach_desc)
                )
                InfoItem(
                    title = stringResource(R.string.peers_mesh_info_connection_title),
                    description = stringResource(R.string.peers_info_connection_desc)
                )
                InfoItem(
                    title = stringResource(R.string.peers_mesh_info_encryption_title),
                    description = stringResource(R.string.peers_mesh_info_encryption_desc)
                )
            }

            // Tips Section
            InfoSection(title = stringResource(R.string.peers_mesh_info_tips)) {
                TipItem(number = 1, text = stringResource(R.string.peers_info_tip1))
                TipItem(number = 2, text = stringResource(R.string.peers_info_tip2))
                TipItem(number = 3, text = stringResource(R.string.peers_info_tip3))
            }

            // Done Button
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.peers_done))
            }
        }
    }
}

@Composable
private fun InfoSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun InfoItem(
    title: String,
    description: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TipItem(
    number: Int,
    text: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(OSHIPurple),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}
