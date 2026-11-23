package com.oshi.messenger.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oshi.messenger.R
import com.oshi.messenger.network.mesh.BleRadioPolicy
import com.oshi.messenger.network.mesh.ConnectionType
import com.oshi.messenger.network.mesh.CrossPlatformMesh
import com.oshi.messenger.network.mesh.MeshNetworkManager
import com.oshi.messenger.network.mesh.MeshPeer
import com.oshi.messenger.network.mesh.MeshState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PeersViewModel - Manages mesh network peer discovery and connection state
 *
 * Integrates BOTH:
 * - MeshNetworkManager (Android-Android via Nearby Connections)
 * - CrossPlatformMesh (iOS-Android via BLE/NSD/TCP)
 *
 * Shows combined peers from both networks.
 */
@HiltViewModel
class PeersViewModel @Inject constructor(
    private val meshManager: MeshNetworkManager,
    private val crossPlatformMesh: CrossPlatformMesh
) : ViewModel() {

    // ============================================================================
    // STATE
    // ============================================================================

    // Mesh state (IDLE, ADVERTISING, DISCOVERING, CONNECTED)
    val meshState: StateFlow<MeshState> = meshManager.meshState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MeshState.IDLE)

    // Connected peers - merge Nearby Connections + CrossPlatformMesh
    val connectedPeers: StateFlow<List<MeshPeer>> = combine(
        meshManager.connectedPeers,
        crossPlatformMesh.connectedPeers
    ) { nearbyPeers, crossPlatformPeers ->
        val cpMapped = crossPlatformPeers
            .filter { it.publicKey.length >= 32 }  // Filter out incomplete BLE discoveries
            .map { peer ->
                MeshPeer(
                    id = "cp_${peer.publicKey}",
                    publicKey = peer.publicKey,
                    displayName = peer.displayName,
                    connectionType = ConnectionType.CROSS_PLATFORM,
                    lastSeen = System.currentTimeMillis()
                )
            }
        // Deduplicate by publicKey (same device may appear in both Nearby + CrossPlatform)
        (nearbyPeers + cpMapped).distinctBy { it.publicKey }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Discovered/Nearby peers (filtered to exclude connected ones)
    // Merge Nearby discovered + CrossPlatformMesh discovered
    val discoveredPeers: StateFlow<List<MeshPeer>> = combine(
        meshManager.discoveredPeers,
        meshManager.connectedPeers,
        crossPlatformMesh.crossPlatformPeers,
        crossPlatformMesh.connectedPeers
    ) { discovered, nearbyConnected, cpDiscovered, cpConnected ->
        // Build set of all connected public keys AND display names (secondary dedup)
        val connectedKeys = nearbyConnected.map { it.publicKey }.toSet() +
                cpConnected.map { it.publicKey }.toSet()
        val connectedNames = nearbyConnected.map { it.displayName.lowercase() }.toSet() +
                cpConnected.map { it.displayName.lowercase() }.toSet()

        // Filter Nearby discovered (exclude already connected by key, ID, or name)
        val nearbyFiltered = discovered.filter {
            it.publicKey !in connectedKeys &&
            it.id !in nearbyConnected.map { c -> c.id }.toSet() &&
            (it.publicKey.isEmpty() || it.displayName.lowercase() !in connectedNames)
        }

        // Map CrossPlatform discovered (exclude already connected + incomplete + duplicates from Nearby)
        val nearbyDiscoveredKeys = discovered.map { it.publicKey }.toSet()
        val cpFiltered = cpDiscovered
            .filter {
                it.publicKey.length >= 32 &&
                it.publicKey !in connectedKeys &&
                it.publicKey !in nearbyDiscoveredKeys &&
                it.displayName.lowercase() !in connectedNames
            }
            .map { peer ->
                MeshPeer(
                    id = "cp_${peer.publicKey}",
                    publicKey = peer.publicKey,
                    displayName = peer.displayName,
                    connectionType = ConnectionType.CROSS_PLATFORM,
                    lastSeen = System.currentTimeMillis()
                )
            }

        (nearbyFiltered + cpFiltered).distinctBy { it.publicKey }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Manually disconnected peers (for "Recent" section)
    private val _disconnectedPeers = MutableStateFlow<Set<String>>(emptySet())
    val disconnectedPeers: StateFlow<Set<String>> = _disconnectedPeers.asStateFlow()

    // UI State
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isBrowsing = MutableStateFlow(false)
    val isBrowsing: StateFlow<Boolean> = _isBrowsing.asStateFlow()

    private val _autoReconnectEnabled = MutableStateFlow(true)
    val autoReconnectEnabled: StateFlow<Boolean> = _autoReconnectEnabled.asStateFlow()

    private val _showMeshInfo = MutableStateFlow(false)
    val showMeshInfo: StateFlow<Boolean> = _showMeshInfo.asStateFlow()

    /**
     * One-shot answers to a press on Connecter, for the screen's snackbar.
     *
     * The button's whole problem was silence: it ran no code and said nothing,
     * which is indistinguishable from a button that ran code and failed. Now
     * every press says which of the three things happened.
     */
    private val _connectFeedback = MutableSharedFlow<ConnectFeedback>(extraBufferCapacity = 4)
    val connectFeedback: SharedFlow<ConnectFeedback> = _connectFeedback.asSharedFlow()

    // ============================================================================
    // DERIVED STATE
    // ============================================================================

    /**
     * The BLE permissions that are missing, read from [CrossPlatformMesh] and
     * **not** from [MeshNetworkManager.missingMeshPermissions].
     *
     * The two lists differ on purpose. `MeshNetworkManager`'s covers Nearby
     * Connections and Wi-Fi Direct as well, so a denied `NEARBY_WIFI_DEVICES`
     * — which never carries a byte of iOS traffic — used to paint "Permission
     * needed" over a BLE link that was up and talking to an iPhone. It was also
     * recomputed only inside `startAdvertising` / `startDiscovery`, i.e. once at
     * launch: granting the permission on this very screen did not clear it, so
     * the card kept saying "Permission needed" after the user had said yes.
     * `CrossPlatformMesh.blePermissionsMissing` is recomputed on every 30 s tick
     * and on every `retryBleIfPermitted()`, which [onScreenResumed] calls the
     * moment the permission dialog closes.
     */
    val missingBlePermissions: StateFlow<List<String>> = crossPlatformMesh.blePermissionsMissing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Mesh status — considers BOTH Nearby Connections AND CrossPlatformMesh.
     *
     * `cpRunning` alone used to be enough to claim SEARCHING. It is not: `start`
     * sets `CrossPlatformMesh._isRunning = true` unconditionally, *after* the
     * BLE bring-up has already returned at its permission guard. A phone that
     * had never been granted BLUETOOTH_SCAN — the default, since the only place
     * the app requests it is the gate on this very screen — therefore showed
     * "Searching…" while advertising nothing and scanning nothing, for as long
     * as the user cared to watch.
     *
     * [MeshStatus.PERMISSION_REQUIRED] is deliberately ranked ABOVE searching
     * but BELOW peers actually found: if something did connect (over NSD/TCP on
     * a shared Wi-Fi, which needs no Bluetooth at all) that is the truth worth
     * showing.
     */
    val meshStatus: StateFlow<MeshStatus> = combine(
        meshState,
        connectedPeers,
        discoveredPeers,
        crossPlatformMesh.isRunning,
        crossPlatformMesh.blePermissionsMissing
    ) { state, connected, discovered, cpRunning, missing ->
        statusFor(
            connectedCount = connected.size,
            discoveredCount = discovered.size,
            state = state,
            crossPlatformRunning = cpRunning,
            missingPermissions = missing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MeshStatus.OFFLINE)

    /**
     * Title and subtitle as **resource ids plus arguments**, resolved by the
     * screen.
     *
     * They used to be English string literals built here: "Searching...",
     * "Permission needed", "Not visible to nearby devices — Bluetooth
     * permission needed". The app ships in 34 locales; the status card was the
     * one part of the Peers screen that was English in all of them, and it is
     * the part that carries the only explanation a user gets for a mesh that is
     * not working. Keeping the decision here (and pure) means it stays testable
     * without a Context; the ids are asserted directly.
     */
    val statusCopy: StateFlow<MeshStatusCopy> = combine(
        combine(meshStatus, connectedPeers, crossPlatformMesh.isRunning) { s, c, r -> Triple(s, c, r) },
        combine(
            crossPlatformMesh.bleAdvertising,
            crossPlatformMesh.bleScanning,
            crossPlatformMesh.bluetoothEnabled,
            crossPlatformMesh.bleFault,
        ) { adv, scan, btOn, fault -> RadioSnapshot(adv, scan, btOn, fault) },
        meshState,
    ) { (status, connected, cpRunning), radio, state ->
        val cpCount = connected.count { it.connectionType == ConnectionType.CROSS_PLATFORM }
        meshStatusCopy(
            status = status,
            state = state,
            connectedTotal = connected.size,
            connectedCrossPlatform = cpCount,
            connectedNearby = connected.size - cpCount,
            crossPlatformRunning = cpRunning,
            bluetoothEnabled = radio.bluetoothEnabled,
            advertising = radio.advertising,
            scanning = radio.scanning,
            fault = radio.fault,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OFFLINE_COPY)

    private data class RadioSnapshot(
        val advertising: Boolean,
        val scanning: Boolean,
        val bluetoothEnabled: Boolean,
        val fault: BleRadioPolicy.RadioFault,
    )

    /**
     * 🔧 P0 FIX (device walkthrough #2, 2026-08-13).
     *
     * `crossPlatformMesh.diagnostics` used to be the fourth input here and was
     * shown verbatim in the Nearby Peers status card:
     *
     *     v2 BT:ON Perm:✓ IP:10.0.2.15 TCP:34601 BLE[0/0] NSD[-D:0] Peers:0/0
     *
     * — a raw engineering telemetry line that put the **device's local IP address
     * on screen** in a privacy product. iOS's `PeersView.swift` shows nothing of
     * the kind. The flow is no longer read here at all; it is now debug-only and
     * goes to logcat (`CrossPlatformMesh.scheduleDiagnosticsUpdate`), so the
     * developer aid survives without shipping to users.
     */
    val connectedCount: StateFlow<Int> = connectedPeers
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val nearbyCount: StateFlow<Int> = discoveredPeers
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // ============================================================================
    // INITIALIZATION
    // ============================================================================

    init {
        // Monitor mesh state AND cross-platform mesh to update advertising/browsing flags
        viewModelScope.launch {
            combine(meshState, crossPlatformMesh.isRunning) { state, cpRunning ->
                Pair(state, cpRunning)
            }.collect { (state, cpRunning) ->
                _isAdvertising.value = state == MeshState.ADVERTISING || state == MeshState.CONNECTED || cpRunning
                _isBrowsing.value = state == MeshState.DISCOVERING || state == MeshState.CONNECTED || cpRunning
            }
        }

        // Auto-reconnect when peers are discovered
        viewModelScope.launch {
            discoveredPeers.collect { peers ->
                if (_autoReconnectEnabled.value) {
                    peers.forEach { peer ->
                        if (peer.id !in _disconnectedPeers.value) {
                            // Cross-platform peers are NOT invited from here.
                            // `CrossPlatformMesh`'s own reconnect sweep owns
                            // them, with a backoff; this flow re-emits on every
                            // state change, and [connectToPeer] is the *user
                            // asked* path, which deliberately ignores backoff.
                            // Routing automatic re-emissions through it would
                            // dial a dead address several times a second.
                            if (peer.connectionType != ConnectionType.CROSS_PLATFORM) {
                                connectToPeer(peer)
                            }
                        }
                    }
                }
            }
        }

        // Remove from disconnected list when reconnected
        viewModelScope.launch {
            connectedPeers.collect { connected ->
                val connectedIds = connected.map { it.id }.toSet()
                _disconnectedPeers.value = _disconnectedPeers.value - connectedIds
            }
        }
    }

    // ============================================================================
    // ACTIONS
    // ============================================================================

    /**
     * Start mesh network (advertising + discovery) for both networks
     */
    fun startMesh() {
        meshManager.startMesh()  // Also starts CrossPlatformMesh internally
        // This screen is the ONLY place in the app that ever asks for
        // BLUETOOTH_SCAN / _ADVERTISE / _CONNECT (`MeshPermissionGate`,
        // MainActivity.kt:2491, wired to `composable("peers")` at
        // MainActivity.kt:1085). By the time the user answers that dialog the
        // mesh has usually been "running" for minutes with BLE dead, and
        // `CrossPlatformMesh.scheduleBLERetry`'s 30-second window closed long
        // ago. Without this call the fresh grant is answered by nobody and BLE
        // stays down until the next cold start.
        crossPlatformMesh.retryBleIfPermitted()
        _isAdvertising.value = true
        _isBrowsing.value = true
    }

    /**
     * The Peers screen came back to the foreground.
     *
     * This is the moment a permission dialog closes — the Activity resumes as
     * the system dialog goes away — so it is the only cheap hook that catches
     * BOTH outcomes: a grant (bring BLE up now instead of waiting up to 30 s for
     * the next tick, which is long enough for a user to conclude the feature is
     * broken and leave) and a denial (recompute `blePermissionsMissing` so the
     * card explains itself instead of falling back to "Searching…"). It also
     * catches a return from Android's app-settings page, where a permanently
     * denied permission has to be granted.
     */
    fun onScreenResumed() {
        crossPlatformMesh.retryBleIfPermitted()
    }

    /**
     * Stop mesh network (keeps CrossPlatformMesh alive for iOS discovery)
     */
    fun stopMesh() {
        meshManager.stopAll()  // Full shutdown including CrossPlatformMesh
        _isAdvertising.value = false
        _isBrowsing.value = false
    }

    /**
     * Toggle advertising visibility
     */
    fun toggleAdvertising() {
        if (_isAdvertising.value) {
            meshManager.stopMesh()
            _isAdvertising.value = false
        } else {
            meshManager.startAdvertising()
            _isAdvertising.value = true
        }
    }

    /**
     * Toggle scanning/browsing
     */
    fun toggleBrowsing() {
        if (_isBrowsing.value) {
            meshManager.stopMesh()
            _isBrowsing.value = false
        } else {
            meshManager.startDiscovery()
            _isBrowsing.value = true
        }
    }

    /**
     * Toggle auto-reconnect
     */
    fun toggleAutoReconnect() {
        _autoReconnectEnabled.value = !_autoReconnectEnabled.value
    }

    /**
     * Connect to a discovered peer, because the user pressed Connecter.
     *
     * __THE_CONNECT_BUTTON_WAS_A_COMMENT_2026_11_07__ The cross-platform branch
     * of this method used to be two lines of comment — "handles connections via
     * NSD/TCP automatically", "no additional action needed". It did handle them
     * automatically, once: `CrossPlatformMesh.connectToPeer` was reachable only
     * from the NSD resolve callback and the BLE characteristic read, both of
     * which fire once per peer per browse. So the button under an iPhone that
     * had failed its single dial — the exact screenshot this was reported with,
     * 0 connected and 1 nearby — ran no code at all, and neither did Refresh
     * (see [refresh]). See [MeshReconnectPolicy] for the full diagnosis.
     */
    fun connectToPeer(peer: MeshPeer) {
        // Remove from disconnected list if present
        _disconnectedPeers.value = _disconnectedPeers.value - peer.id

        viewModelScope.launch {
            if (peer.connectionType == ConnectionType.CROSS_PLATFORM) {
                val outcome = crossPlatformMesh.connectNow(peer.publicKey)
                _connectFeedback.emit(
                    ConnectFeedback(
                        messageRes = when (outcome) {
                            CrossPlatformMesh.ConnectRequest.ALREADY_CONNECTED -> R.string.peers_connect_already
                            CrossPlatformMesh.ConnectRequest.DIALING -> R.string.peers_connect_dialing
                            CrossPlatformMesh.ConnectRequest.SEARCHING,
                            CrossPlatformMesh.ConnectRequest.NOT_DISCOVERED -> R.string.peers_connect_searching
                        },
                        peerName = peer.displayName,
                    )
                )
            } else {
                // Trigger Nearby Connections discovery refresh
                meshManager.startDiscovery()
            }
        }
    }

    /**
     * Disconnect from a connected peer
     */
    fun disconnectPeer(peer: MeshPeer) {
        _disconnectedPeers.value = _disconnectedPeers.value + peer.id
    }

    /**
     * Reconnect to a previously disconnected peer
     */
    fun reconnectPeer(peerId: String) {
        _disconnectedPeers.value = _disconnectedPeers.value - peerId
        viewModelScope.launch {
            meshManager.startDiscovery()
        }
    }

    /**
     * Refresh peer list (pull-to-refresh)
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true

            // Restart discovery on both networks.
            //
            // `meshManager.stopMesh()` documents that it deliberately leaves
            // CrossPlatformMesh running, so the `startMesh()` below reaches
            // `CrossPlatformMesh.start()` and is answered with "already
            // running — IGNORED". For the iOS lane this button has therefore
            // always been inert; `restartDiscovery` is the part that actually
            // makes an already-registered peer announce itself again.
            meshManager.stopMesh()
            delay(500)
            meshManager.startMesh()  // Nearby Connections / Wi-Fi Direct
            crossPlatformMesh.retryBleIfPermitted()
            crossPlatformMesh.restartDiscovery(force = true)

            delay(2000) // Allow time for discovery
            _isRefreshing.value = false
        }
    }

    /**
     * Show mesh info dialog
     */
    fun showMeshInfo() {
        _showMeshInfo.value = true
    }

    /**
     * Hide mesh info dialog
     */
    fun hideMeshInfo() {
        _showMeshInfo.value = false
    }

    /**
     * Get mesh statistics
     */
    fun getMeshStats(): MeshStats {
        val stats = meshManager.getMeshStats()
        return MeshStats(
            connectedPeers = stats.connectedPeers,
            discoveredPeers = stats.discoveredPeers,
            knownRoutes = stats.knownRoutes,
            // Not user-facing (nothing renders MeshStats today) and no longer
            // a "~10km via relay" claim: BLE hands over an ip and a port and
            // nothing else, so the reach of this mesh is the reach of the LAN.
            maxRange = "same Wi-Fi network"
        )
    }

    // ============================================================================
    // CLEANUP
    // ============================================================================

    override fun onCleared() {
        super.onCleared()
        // Don't stop mesh on screen leave - let it run in background
    }
}

// ============================================================================
// DATA CLASSES
// ============================================================================

/**
 * What to tell the user after they pressed Connecter on one peer.
 *
 * @param messageRes a format string taking the peer's display name
 */
data class ConnectFeedback(
    @StringRes val messageRes: Int,
    val peerName: String,
)

/**
 * Mesh connection status (matches iOS PeerStatus)
 */
enum class MeshStatus {
    SEARCHING,
    CONNECTED,
    PEERS_FOUND,

    /**
     * The mesh is "started" but holds no Bluetooth grant, so it is advertising
     * nothing and scanning nothing. Distinct from [OFFLINE] (nothing started)
     * and, critically, from [SEARCHING] (started and actually looking) — the
     * three were collapsed into "Searching…" and that is why a mesh that had
     * never carried a message looked healthy.
     */
    PERMISSION_REQUIRED,

    OFFLINE
}

/**
 * The status-card decision, pulled out of the `combine` so it can be tested
 * without a ViewModel, a Hilt graph, a Bluetooth adapter or a second phone.
 *
 * Kept `internal` and in this file so it sits next to the enum it returns.
 */
internal fun statusFor(
    connectedCount: Int,
    discoveredCount: Int,
    state: MeshState,
    crossPlatformRunning: Boolean,
    missingPermissions: List<String>,
): MeshStatus = when {
    connectedCount > 0 -> MeshStatus.CONNECTED
    discoveredCount > 0 -> MeshStatus.PEERS_FOUND
    missingPermissions.isNotEmpty() -> MeshStatus.PERMISSION_REQUIRED
    state == MeshState.ADVERTISING || state == MeshState.DISCOVERING || crossPlatformRunning ->
        MeshStatus.SEARCHING
    else -> MeshStatus.OFFLINE
}

/**
 * What the status card says, as resource ids the screen resolves.
 *
 * [subtitleArgs] is spread into `stringResource(subtitleRes, *args)`; an empty
 * list means the string takes no arguments.
 */
data class MeshStatusCopy(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val subtitleArgs: List<Any> = emptyList(),
)

internal val OFFLINE_COPY = MeshStatusCopy(
    titleRes = R.string.mesh_status_offline,
    subtitleRes = R.string.peers_subtitle_disabled,
)

/**
 * The status card's copy, decided in one pure place so all of it is testable —
 * including the cases that need a radio, a denied dialog or a second phone to
 * reach on a device.
 *
 * Order matters and encodes what is worth saying first:
 *
 *  1. **Something is actually connected.** Always the truth worth showing, even
 *     over a missing permission: NSD/TCP on a shared Wi-Fi needs no Bluetooth
 *     at all.
 *  2. **Bluetooth is switched off.** A permission prompt would be the wrong ask.
 *  3. **A permission is missing**, named by the half that is down — being
 *     invisible to an iPhone (no advertise) and being blind to one (no scan)
 *     are different problems with different consequences.
 *  4. **The radio refused us** in a way the user can act on (another app owns
 *     the advertiser; the device cannot do BLE at all).
 *  5. Otherwise: searching — and the copy says *on this Wi-Fi network*, because
 *     that is the real requirement. BLE carries no message data on either
 *     platform; both characteristics are read-only and hand over an `ip` and a
 *     `port`, and every byte of mesh traffic is TCP/IP. Two phones sitting
 *     together on cellular cannot mesh, and "Scanning via BLE & Bonjour…" left
 *     that user with no way to know why nothing ever happened.
 */
internal fun meshStatusCopy(
    status: MeshStatus,
    state: MeshState,
    connectedTotal: Int,
    connectedCrossPlatform: Int,
    connectedNearby: Int,
    crossPlatformRunning: Boolean,
    bluetoothEnabled: Boolean,
    advertising: Boolean,
    scanning: Boolean,
    fault: BleRadioPolicy.RadioFault,
): MeshStatusCopy {
    val titleRes = when (status) {
        MeshStatus.SEARCHING -> R.string.peers_status_searching
        MeshStatus.CONNECTED -> R.string.mesh_status_connected
        MeshStatus.PEERS_FOUND -> R.string.peers_status_peers_found
        MeshStatus.PERMISSION_REQUIRED -> R.string.peers_status_permission_required
        MeshStatus.OFFLINE -> R.string.mesh_status_offline
    }

    if (connectedTotal > 0) {
        return when {
            connectedCrossPlatform > 0 && connectedNearby > 0 -> MeshStatusCopy(
                titleRes, R.string.peers_subtitle_connected_mixed,
                listOf(connectedTotal, connectedNearby, connectedCrossPlatform),
            )
            connectedCrossPlatform > 0 -> MeshStatusCopy(
                titleRes, R.string.peers_subtitle_connected_cross, listOf(connectedTotal),
            )
            else -> MeshStatusCopy(
                titleRes, R.string.peers_subtitle_connected, listOf(connectedTotal),
            )
        }
    }

    if (crossPlatformRunning && !bluetoothEnabled) {
        return MeshStatusCopy(titleRes, R.string.peers_subtitle_bluetooth_off)
    }

    if (status == MeshStatus.PERMISSION_REQUIRED) {
        return MeshStatusCopy(
            titleRes,
            when {
                !advertising && !scanning -> R.string.peers_subtitle_permission_both
                // MUTE: we can see the iPhone; it can never see us. iOS learns an
                // Android peer only by reading GATT characteristic 0542, and that
                // characteristic is served only while we advertise.
                !advertising -> R.string.peers_subtitle_permission_advertise
                else -> R.string.peers_subtitle_permission_scan
            },
        )
    }

    if (fault.userVisible) {
        return MeshStatusCopy(
            titleRes,
            when (fault) {
                BleRadioPolicy.RadioFault.UNSUPPORTED -> R.string.peers_subtitle_ble_unsupported
                else -> R.string.peers_subtitle_ble_busy
            },
        )
    }

    return when {
        crossPlatformRunning && !advertising && !scanning ->
            MeshStatusCopy(titleRes, R.string.peers_subtitle_ble_down)
        crossPlatformRunning ->
            MeshStatusCopy(titleRes, R.string.peers_subtitle_searching_lan)
        state == MeshState.IDLE ->
            MeshStatusCopy(titleRes, R.string.peers_subtitle_disabled)
        else ->
            MeshStatusCopy(titleRes, R.string.peers_subtitle_looking)
    }
}

/**
 * Peer connection status for UI
 */
enum class PeerStatus {
    CONNECTED,
    NEARBY,
    DISCONNECTED
}

/**
 * Mesh network statistics
 */
data class MeshStats(
    val connectedPeers: Int,
    val discoveredPeers: Int,
    val knownRoutes: Int,
    val maxRange: String
)
