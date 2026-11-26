package com.oshi.messenger.network.mesh

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.*
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.mesh.wifiaware.WifiAwareArbiter
import com.oshi.messenger.network.mesh.wifiaware.WifiAwareTransport
import com.oshi.messenger.service.AppPollState
import com.oshi.messenger.service.MeshNetworkService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MeshNetworkManager - Cross-Platform Mesh Networking
 *
 * Compatible with iOS OSHI MeshNetworkManager using:
 * 1. Google Nearby Connections API (primary)
 * 2. WiFi Direct for Android-Android P2P
 * 3. Multi-hop relay for extended range (up to 10km)
 *
 * Protocol compatibility with iOS:
 * - Same message format (JSON)
 * - Same encryption (AES-256-GCM via CryptoManager)
 * - Same relay protocol for multi-hop routing
 *
 * NOTE: iOS and Android use different mesh APIs (MultipeerConnectivity vs Nearby Connections)
 * Direct mesh between iOS-Android is NOT possible. Use VPS relay for cross-platform.
 */
@Singleton
class MeshNetworkManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val crossPlatformMesh: CrossPlatformMesh
) {
    companion object {
        private const val TAG = "MeshNetwork"

        // Service identifiers
        private const val SERVICE_ID = "com.oshi.mesh"
        private const val SERVICE_TYPE_CHAT = "_mesh-chat._tcp"
        private const val SERVICE_TYPE_P2P = "_p2p-message._tcp"

        // Connection timeouts
        private const val CONNECTION_TIMEOUT = 30000L
        private const val DISCOVERY_TIMEOUT = 60000L

        // WiFi Direct port
        private const val WIFI_DIRECT_PORT = 8888

        // Relay settings (match iOS)
        private const val MAX_HOPS = 50  // ~10km at 200m per hop
        private const val MAX_RELAY_AGE_MS = 600_000L  // 10 minutes
        private const val MAX_CACHE_SIZE = 5000

        /** __WIFI_AWARE_2026_11_25__ Peer ids of the Wi-Fi Aware lane: "aware:<publicKey>". */
        const val AWARE_PEER_PREFIX = "aware:"
        fun isAwarePeerId(id: String): Boolean = id.startsWith(AWARE_PEER_PREFIX)
    }

    // State
    private val _meshState = MutableStateFlow(MeshState.IDLE)
    val meshState: StateFlow<MeshState> = _meshState.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<MeshPeer>>(emptyList())
    val connectedPeers: StateFlow<List<MeshPeer>> = _connectedPeers.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<MeshPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<MeshPeer>> = _discoveredPeers.asStateFlow()

    /**
     * Runtime permissions the mesh asked for and does not have, recomputed on
     * every bring-up attempt. Empty means nothing is missing.
     *
     * Before this existed, [startAdvertising] and [startDiscovery] returned at
     * their permission guard having written one `Log.e` to logcat and touched no
     * state at all. `_meshState` stayed `IDLE`, `CrossPlatformMesh._isRunning`
     * was `true` regardless, and the Peers screen reported "Searching…". A
     * denied permission was indistinguishable from a working mesh that had not
     * found anybody yet — which is the whole reason nobody noticed that the
     * mesh had never once carried a message.
     */
    private val _missingMeshPermissions = MutableStateFlow<List<String>>(emptyList())
    val missingMeshPermissions: StateFlow<List<String>> = _missingMeshPermissions.asStateFlow()

    // Nearby Connections
    private val connectionsClient by lazy { Nearby.getConnectionsClient(context) }

    // WiFi Direct
    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var wifiDirectReceiver: BroadcastReceiver? = null

    // Sockets for data transfer
    private var serverSocket: ServerSocket? = null
    private val clientSockets = mutableMapOf<String, Socket>()

    // Callbacks
    private val messageCallbacks = mutableListOf<(MeshMessage) -> Unit>()
    private val connectionCallbacks = mutableListOf<(MeshPeer, Boolean) -> Unit>()

    // Coroutine scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Our identity
    private var myPublicKey: String = ""
    private var myDisplayName: String = "OSHI User"

    // Relay system for extended range (10km via multi-hop)
    /**
     * Duplicate-suppression cache for relayed ids — see [markSeen].
     *
     * ⚠️ `mutableSetOf` IS LOAD-BEARING: it is a `LinkedHashSet`, so the trim in
     * [markSeen] iterates in INSERTION order and therefore drops the OLDEST ids. A
     * plain `HashSet` here would evict at random and the mesh would re-deliver
     * messages the user has already read.
     */
    private val seenMessageIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val routingTable = mutableMapOf<String, RouteInfo>()  // publicKey -> route
    private val relayCallbacks = mutableListOf<(RelayMessage) -> Unit>()

    /**
     * __MESH_CUSTODY_2026_10_10__ Messages this node could not pass on yet.
     *
     * Distinct from a sender's own outbox: that re-originates at hop 0 when the
     * recipient turns up directly, this one carries somebody else's packet
     * onward with its hop count and its path intact. See [MeshCustodyStore].
     *
     * `by lazy` so the disk read happens on first touch (a relay or a flush,
     * both off the main thread) rather than inside Hilt's construction of this
     * singleton.
     */
    private val custody: MeshCustodyStore by lazy { MeshCustodyStore.forContext(context) }

    /** The watcher behind the second flush trigger — see [startCustodyFlushWatcher]. */
    private var custodyFlushJob: Job? = null

    // ============================================================================
    // WI-FI AWARE (__WIFI_AWARE_2026_11_25__, GitHub Lastoneparis/OSHI#4)
    // ============================================================================
    //
    // One more offline lane, purely additive. A peer that passed the Aware
    // handshake (proof of possession of its key — see WifiAwareProtocol) is listed
    // in [connectedPeers] as ConnectionType.WIFI_AWARE with id "aware:<key>", and
    // every existing send path reaches it through [sendMessageToPublicKey] /
    // [sendMessage] without knowing it exists. The arbitration that keeps it out
    // of the way of Wi-Fi, hotspot, Wi-Fi Direct, Bluetooth and LoRa lives in
    // WifiAwareArbiter.

    private val undeliveredCallbacks = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    /**
     * Called with each message id that was handed to a Wi-Fi Aware link and never
     * acknowledged by the peer before the link closed. The send path has already
     * marked such a message delivered (as it does for every mesh peer), so this is
     * the only way it can learn otherwise.
     */
    fun addUndeliveredCallback(callback: (messageId: String) -> Unit) {
        undeliveredCallbacks.add(callback)
    }

    private val awareListener = object : WifiAwareTransport.Listener {
        override fun onPeerConnected(publicKey: String, displayName: String) {
            val peer = MeshPeer(
                id = AWARE_PEER_PREFIX + publicKey,
                publicKey = publicKey,
                displayName = displayName.ifBlank { publicKey.take(8) },
                connectionType = ConnectionType.WIFI_AWARE
            )
            // Aware first, so a person reachable over several lanes is found on
            // the fastest one by `find { it.publicKey == … }`.
            _connectedPeers.update { list -> listOf(peer) + list.filter { it.id != peer.id } }
            connectionCallbacks.forEach { it(peer, true) }
            scope.launch { flushCustody() }
        }

        override fun onPeerDisconnected(publicKey: String, unackedMessageIds: List<String>) {
            val id = AWARE_PEER_PREFIX + publicKey
            val peer = _connectedPeers.value.find { it.id == id }
            _connectedPeers.update { list -> list.filter { it.id != id } }
            peer?.let { p -> connectionCallbacks.forEach { cb -> cb(p, false) } }
            unackedMessageIds.forEach { mid -> undeliveredCallbacks.forEach { cb -> cb(mid) } }
        }

        override fun onEnvelope(publicKey: String, json: ByteArray) {
            handleReceivedData(AWARE_PEER_PREFIX + publicKey, json)
        }
    }

    private val wifiAware: WifiAwareTransport by lazy {
        WifiAwareTransport(
            context,
            sharedSecret = { peerKey ->
                try { if (cryptoManager.hasKeyPair()) cryptoManager.deriveSharedSecret(peerKey) else null }
                catch (e: Exception) { null }
            },
            listener = awareListener
        )
    }

    /** True when this phone can run Wi-Fi Aware at all (settings switch hidden otherwise). */
    fun isWifiAwareSupported(): Boolean = wifiAware.isSupported()

    /** Re-read the Wi-Fi Aware switch, permissions and radio state. Cheap; safe to call often. */
    fun refreshWifiAware() {
        if (myPublicKey.isNotEmpty() && _meshStarted) wifiAware.start(myPublicKey, myDisplayName)
        wifiAware.refresh()
    }

    /** startMesh() ran and stopAll() did not — the lifecycle Wi-Fi Aware follows. */
    @Volatile private var _meshStarted = false

    /**
     * Hand [bytes] to whichever lane [peerId] belongs to. An Aware peer whose link
     * is gone falls back to a Nearby endpoint of the same person, if there is one.
     */
    private fun sendBytesToPeer(peerId: String, bytes: ByteArray, messageId: String?, onNearbyFailure: (() -> Unit)? = null) {
        if (isAwarePeerId(peerId)) {
            val key = peerId.removePrefix(AWARE_PEER_PREFIX)
            if (wifiAware.send(key, bytes, messageId)) {
                Log.d(TAG, "📤 Sent via Wi-Fi Aware to ${key.take(16)}…")
                return
            }
            val nearby = _connectedPeers.value.find { it.publicKey == key && !isAwarePeerId(it.id) }
            if (nearby == null) {
                Log.w(TAG, "⚠️ Wi-Fi Aware link to ${key.take(16)}… gone and no other mesh lane")
                // Reported a moment later: the caller marks the row delivered right
                // after this returns, and the "not delivered" verdict must land last.
                messageId?.let { mid ->
                    scope.launch {
                        delay(1_500)
                        undeliveredCallbacks.forEach { cb -> cb(mid) }
                    }
                }
                return
            }
            sendBytesToPeer(nearby.id, bytes, messageId, onNearbyFailure)
            return
        }
        connectionsClient.sendPayload(peerId, Payload.fromBytes(bytes))
            .addOnSuccessListener { Log.d(TAG, "📤 Message sent to: $peerId") }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Failed to send to $peerId: ${e.message}")
                onNearbyFailure?.invoke()
            }
    }

    // ============================================================================
    // INITIALIZATION
    // ============================================================================

    fun initialize(publicKey: String, displayName: String) {
        myPublicKey = publicKey
        myDisplayName = displayName
        Log.d(TAG, "🚀 MeshNetworkManager initialized")
        Log.d(TAG, "   Public key: ${publicKey.take(16)}...")
        Log.d(TAG, "   Display name: $displayName")
    }

    /**
     * Start mesh network with credentials (for MeshBooster)
     */
    fun start(publicKey: String, displayName: String) {
        initialize(publicKey, displayName)
        startMesh()
    }

    /**
     * Stop mesh network (alias for stopMesh, for MeshBooster)
     */
    fun stop() {
        stopMesh()
    }

    // ============================================================================
    // NEARBY CONNECTIONS (Best for iOS compatibility)
    // ============================================================================

    /**
     * Start advertising our presence to nearby devices
     * Uses Nearby Connections API which can work with iOS via custom protocols
     */
    fun startAdvertising() {
        if (!refreshMissingPermissions()) {
            Log.e(TAG, "❌ Missing required permissions for mesh networking: ${_missingMeshPermissions.value}")
            // CrossPlatformMesh is the ONLY lane that reaches an iPhone, and it
            // needs a different (smaller) permission set than Nearby Connections
            // does. Returning here without starting it meant one missing
            // NEARBY_WIFI_DEVICES — irrelevant to iOS — also took out iOS
            // interop. Start it regardless; it has its own guard.
            ensureCrossPlatformMeshRunning()
            return
        }

        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER) // Many-to-many connections
            .build()

        connectionsClient.startAdvertising(
            myDisplayName,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.d(TAG, "📡 Started advertising: $myDisplayName")
            _meshState.value = MeshState.ADVERTISING
        }.addOnFailureListener { e ->
            Log.e(TAG, "❌ Failed to start advertising: ${e.message}")
        }

        // Ensure CrossPlatformMesh is also running
        ensureCrossPlatformMeshRunning()
    }

    /**
     * Start discovering nearby devices
     */
    fun startDiscovery() {
        if (!refreshMissingPermissions()) {
            Log.e(TAG, "❌ Missing required permissions for mesh networking: ${_missingMeshPermissions.value}")
            ensureCrossPlatformMeshRunning()
            return
        }

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            Log.d(TAG, "🔍 Started discovery")
            _meshState.value = MeshState.DISCOVERING
        }.addOnFailureListener { e ->
            Log.e(TAG, "❌ Failed to start discovery: ${e.message}")
        }

        // Ensure CrossPlatformMesh is also running
        ensureCrossPlatformMeshRunning()
    }

    /**
     * Ensure CrossPlatformMesh is running (idempotent)
     */
    private fun ensureCrossPlatformMeshRunning() {
        if (!crossPlatformMesh.isRunning.value && myPublicKey.isNotEmpty()) {
            Log.d(TAG, "🍎↔🤖 CrossPlatformMesh was stopped, restarting...")
            startCrossPlatformMesh()
        }
    }

    /**
     * Start both advertising and discovery (full mesh mode)
     */
    fun startMesh() {
        Log.d(TAG, "🌐 Starting full mesh mode...")
        startAdvertising()

        // Delay discovery slightly to avoid conflicts
        scope.launch {
            delay(500)
            startDiscovery()
        }

        // Also start WiFi Direct for Android-Android connections
        startWifiDirect()

        // __WIFI_AWARE_2026_11_25__ Wi-Fi Aware: starts only if supported, switched
        // on, permitted, and the radio is not busy with Wi-Fi Direct / hotspot.
        _meshStarted = true
        if (myPublicKey.isNotEmpty()) wifiAware.start(myPublicKey, myDisplayName)

        // Start CrossPlatformMesh for iOS ↔ Android direct communication
        startCrossPlatformMesh()

        // __MESH_CUSTODY_2026_10_10__ Drain anything this node is still
        // carrying for other people — including across a relaunch.
        startCustodyFlushWatcher()
    }

    /**
     * Start CrossPlatformMesh for iOS ↔ Android communication
     * Uses BLE + Bonjour/NSD + TCP to bridge with iOS devices
     */
    private fun startCrossPlatformMesh() {
        if (myPublicKey.isEmpty()) {
            Log.w(TAG, "Cannot start CrossPlatformMesh: no public key")
            // __MESH_DIAGNOSTICS_SURVIVE_RELEASE_2026_10_23__ The quietest way
            // the mesh never happens: no identity yet, so nothing is advertised
            // and nothing explains why.
            MeshDiag.fail("mesh not started: this device has no public key yet")
            return
        }

        Log.d(TAG, "🍎↔🤖 Starting CrossPlatformMesh bridge...")

        // Start cross-platform mesh with our identity
        MeshDiag.log("mesh: starting cross-platform bridge")
        crossPlatformMesh.start(myPublicKey, myDisplayName)

        // __ANDROID_ANSWERS_READS_IN_BACKGROUND_2026_10_23__ The BLE read that
        // hands an iPhone our ip and port is answered by this process; keep it
        // alive and unfrozen for as long as the mesh is meant to be reachable.
        MeshNetworkService.hold(context, AppPollState.isForeground)

        // 🔧 FIX: Do NOT bridge regular messages here (MessageRepository handles them directly).
        // But DO bridge RELAY messages from CrossPlatformMesh → Nearby Connections
        // so that iOS messages can reach Android peers via multi-hop relay.
        crossPlatformMesh.addMessageCallback { cpMessage ->
            if (cpMessage.type == "RELAY" && cpMessage.recipientPublicKey != myPublicKey) {
                // Cross-platform relay: forward from iOS mesh to Android Nearby Connections
                val directPeer = _connectedPeers.value.find { it.publicKey == cpMessage.recipientPublicKey }
                if (directPeer != null) {
                    Log.d(TAG, "🍎→🤖 Cross-relay: forwarding iOS relay to Nearby peer ${cpMessage.recipientPublicKey.take(16)}...")
                    val meshMessage = MeshMessage(
                        id = cpMessage.id,
                        type = MessageType.RELAY,
                        senderPublicKey = cpMessage.senderPublicKey,
                        senderName = cpMessage.senderName,
                        payload = cpMessage.payload,
                        timestamp = cpMessage.timestamp.toLong()
                    )
                    sendMessage(directPeer.id, meshMessage)
                }
            }
        }

        Log.d(TAG, "🍎↔🤖 CrossPlatformMesh started — messages handled by MessageRepository directly")
        Log.d(TAG, "🍎↔🤖 Relay bridge: CrossPlatformMesh ↔ Nearby Connections active")

        Log.d(TAG, "✅ CrossPlatformMesh bridge started")
    }

    /**
     * Send message via CrossPlatformMesh (for iOS recipients)
     */
    fun sendViaCrossPlatform(recipientPublicKey: String, message: MeshMessage) {
        if (!crossPlatformMesh.isRunning.value) {
            Log.w(TAG, "CrossPlatformMesh not running")
            return
        }

        crossPlatformMesh.sendMessage(recipientPublicKey, message.payload, message.type.name)
    }

    /**
     * Check if a peer is reachable via CrossPlatformMesh (iOS peer)
     */
    fun isIOSPeerReachable(publicKey: String): Boolean {
        return crossPlatformMesh.isPeerReachable(publicKey)
    }

    /**
     * Get iOS peers discovered via CrossPlatformMesh
     */
    fun getIOSPeers(): List<CrossPlatformPeer> {
        return crossPlatformMesh.getIOSPeers()
    }

    /**
     * Stop all mesh networking
     */
    fun stopMesh() {
        Log.d(TAG, "🛑 Stopping Nearby Connections mesh (keeping CrossPlatformMesh alive)...")

        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()

        stopWifiDirect()

        // NOTE: Do NOT stop CrossPlatformMesh here — it has independent lifecycle
        // and should keep running for iOS ↔ Android discovery
        //
        // __WIFI_AWARE_2026_11_25__ Same for Wi-Fi Aware: pull-to-refresh calls
        // stopMesh()+startMesh(), and a live Aware link must not be torn down for
        // a refresh. It stops with the mesh switch (stopAll), not here.

        _meshState.value = MeshState.IDLE
        _connectedPeers.value = _connectedPeers.value.filter { it.connectionType == ConnectionType.WIFI_AWARE }
        _discoveredPeers.value = emptyList()
    }

    /**
     * Stop everything including CrossPlatformMesh (full shutdown)
     */
    fun stopAll() {
        Log.d(TAG, "🛑 Stopping ALL mesh networking...")
        stopMesh()
        crossPlatformMesh.stop()
        _meshStarted = false
        wifiAware.stop()
        // Nothing left to answer a read with — give the process back.
        MeshNetworkService.release(context)
    }

    // ============================================================================
    // WIFI DIRECT (Android-Android P2P)
    // ============================================================================

    private fun startWifiDirect() {
        if (wifiP2pManager != null) return

        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        wifiP2pChannel = wifiP2pManager?.initialize(context, context.mainLooper, null)

        // Register broadcast receiver
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        wifiDirectReceiver = WifiDirectReceiver()
        context.registerReceiver(wifiDirectReceiver, intentFilter)

        // Start peer discovery
        //
        // __WIFI_AWARE_2026_11_25__ Not while Wi-Fi Aware is running: on chips that
        // cannot run P2P and Aware together the scan could cost the live Aware
        // links, and nothing reads this scan's results. A scan already running is
        // never stopped. See WifiAwareArbiter rule 4.
        if (!WifiAwareArbiter.mayStartWifiDirectDiscovery(awareRunning = wifiAware.running.value)) {
            Log.d(TAG, "📶 WiFi Direct discovery skipped: Wi-Fi Aware is running")
        } else if (hasRequiredPermissions()) {
            wifiP2pManager?.discoverPeers(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "📶 WiFi Direct discovery started")
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "❌ WiFi Direct discovery failed: $reason")
                }
            })
        }
    }

    private fun stopWifiDirect() {
        wifiDirectReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Receiver already unregistered")
            }
        }
        wifiDirectReceiver = null
        wifiP2pManager = null
        wifiP2pChannel = null
    }

    // ============================================================================
    // CONNECTION CALLBACKS
    // ============================================================================

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "📲 Connection initiated from: ${info.endpointName}")

            // Auto-accept connections (in production, add verification)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ Accepted connection from: ${info.endpointName}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Failed to accept connection: ${e.message}")
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d(TAG, "✅ Connected to: $endpointId")

                    // Add to connected peers
                    val peer = MeshPeer(
                        id = endpointId,
                        publicKey = "", // Will be exchanged
                        displayName = endpointId,
                        connectionType = ConnectionType.NEARBY
                    )
                    _connectedPeers.value = _connectedPeers.value + peer

                    // Exchange identity
                    sendIdentityExchange(endpointId)

                    connectionCallbacks.forEach { it(peer, true) }

                    // __MESH_CUSTODY_2026_10_10__ A new peer is the only event
                    // that can turn a held message into a deliverable one.
                    // Delayed like iOS's 0.5 s so the identity exchange above
                    // has a chance to land first: until it does the peer's
                    // `publicKey` is "", which no `seenBy` contains, so it
                    // would be offered a message it may already have carried.
                    // Costs one duplicate that its own `markSeen` swallows —
                    // never a lost message.
                    scope.launch {
                        delay(500)
                        flushCustody()
                    }
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.w(TAG, "⚠️ Connection rejected: $endpointId")
                }
                else -> {
                    Log.e(TAG, "❌ Connection failed: ${result.status}")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "🔌 Disconnected from: $endpointId")

            val peer = _connectedPeers.value.find { it.id == endpointId }
            _connectedPeers.value = _connectedPeers.value.filter { it.id != endpointId }

            peer?.let { connectionCallbacks.forEach { cb -> cb(it, false) } }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "🔍 Found endpoint: ${info.endpointName} ($endpointId)")

            val peer = MeshPeer(
                id = endpointId,
                publicKey = "",
                displayName = info.endpointName,
                connectionType = ConnectionType.NEARBY
            )

            if (_discoveredPeers.value.none { it.id == endpointId }) {
                _discoveredPeers.value = _discoveredPeers.value + peer
            }

            // Auto-connect
            requestConnection(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "📴 Lost endpoint: $endpointId")
            _discoveredPeers.value = _discoveredPeers.value.filter { it.id != endpointId }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    payload.asBytes()?.let { data ->
                        handleReceivedData(endpointId, data)
                    }
                }
                else -> Log.w(TAG, "Unsupported payload type: ${payload.type}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Track transfer progress if needed
        }
    }

    // ============================================================================
    // MESSAGE HANDLING (iOS Compatible Format)
    // ============================================================================

    /**
     * Send a message to a peer
     * Uses same JSON format as iOS for compatibility
     */
    fun sendMessage(toPeerId: String, message: MeshMessage) {
        val jsonData = message.toJson().toByteArray()

        // Nearby Connections (or Wi-Fi Aware for an "aware:" peer); on a Nearby
        // failure, fall back to the WiFi Direct socket as before.
        sendBytesToPeer(toPeerId, jsonData, messageId = message.id) {
            sendViaWifiDirect(toPeerId, jsonData)
        }
    }

    /**
     * Send message to a peer by public key
     */
    fun sendMessageToPublicKey(publicKey: String, message: MeshMessage) {
        // __WIFI_AWARE_2026_11_25__ Prefer a live Wi-Fi Aware link, else any lane.
        val peer = _connectedPeers.value.find { it.publicKey == publicKey && it.connectionType == ConnectionType.WIFI_AWARE && wifiAware.isConnected(publicKey) }
            ?: _connectedPeers.value.find { it.publicKey == publicKey && it.connectionType != ConnectionType.WIFI_AWARE }
            ?: _connectedPeers.value.find { it.publicKey == publicKey }
        if (peer != null) {
            sendMessage(peer.id, message)
        } else {
            Log.w(TAG, "⚠️ No connected peer with public key: ${publicKey.take(16)}...")
        }
    }

    /**
     * Broadcast a message to all connected peers
     */
    fun broadcastMessage(message: MeshMessage) {
        _connectedPeers.value.forEach { peer ->
            sendMessage(peer.id, message)
        }
    }

    private fun handleReceivedData(fromEndpointId: String, data: ByteArray) {
        try {
            val jsonString = String(data, Charsets.UTF_8)

            // Check if this is a relay message
            if (jsonString.contains("\"hopCount\"") && jsonString.contains("\"maxHops\"")) {
                handleRelayMessage(fromEndpointId, jsonString)
                return
            }

            val message = MeshMessage.fromJson(jsonString)

            Log.d(TAG, "📥 Received message from $fromEndpointId: ${message.type}")

            when (message.type) {
                MessageType.IDENTITY_EXCHANGE -> handleIdentityExchange(fromEndpointId, message)
                MessageType.TEXT_MESSAGE -> messageCallbacks.forEach { it(message) }
                MessageType.CALL_SIGNAL -> messageCallbacks.forEach { it(message) }
                MessageType.CALL_AUDIO -> messageCallbacks.forEach { it(message) }
                MessageType.MEDIA_MESSAGE -> messageCallbacks.forEach { it(message) }
                MessageType.ACK -> handleAck(message)
                MessageType.RELAY -> handleRelayMessage(fromEndpointId, jsonString)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse message: ${e.message}")
        }
    }

    // ============================================================================
    // MULTI-HOP RELAY SYSTEM (10km range via relay nodes)
    // ============================================================================

    /**
     * Send a message with relay support
     * If recipient is not directly connected, relay through other peers
     * Also tries CrossPlatformMesh for iOS recipients
     */
    fun sendMessageWithRelay(recipientPublicKey: String, message: MeshMessage) {
        // Check if recipient is directly connected via Nearby Connections
        val directPeer = _connectedPeers.value.find { it.publicKey == recipientPublicKey }

        if (directPeer != null) {
            // Direct send via Nearby Connections
            Log.d(TAG, "📤 Direct send to: ${recipientPublicKey.take(16)}...")
            sendMessage(directPeer.id, message)
            return
        }

        // Check if recipient is reachable via CrossPlatformMesh (iOS peer)
        if (crossPlatformMesh.isPeerReachable(recipientPublicKey)) {
            Log.d(TAG, "🍎 Sending via CrossPlatformMesh to iOS peer: ${recipientPublicKey.take(16)}...")
            sendViaCrossPlatform(recipientPublicKey, message)
            return
        }

        // Check routing table for known route
        val route = routingTable[recipientPublicKey]
        if (route != null && System.currentTimeMillis() - route.lastSeen < MAX_RELAY_AGE_MS) {
            Log.d(TAG, "📍 Using cached route via: ${route.nextHop.take(16)}...")
            val nextPeer = _connectedPeers.value.find { it.publicKey == route.nextHop }
            if (nextPeer != null) {
                val relayMsg = createRelayMessage(message, recipientPublicKey, 0)
                sendRelayMessage(nextPeer.id, relayMsg)
                return
            }
        }

        // Try broadcasting via both networks (Nearby + CrossPlatform)
        Log.d(TAG, "📡 Broadcasting relay message to both networks")
        val relayMsg = createRelayMessage(message, recipientPublicKey, 0)
        broadcastRelayMessage(relayMsg, excludeEndpoint = null)

        // Also broadcast via CrossPlatformMesh
        if (crossPlatformMesh.isRunning.value) {
            crossPlatformMesh.sendMessage(recipientPublicKey, message.payload, message.type.name)
        }
    }

    /**
     * Create a relay message for multi-hop routing
     */
    private fun createRelayMessage(message: MeshMessage, finalRecipient: String, currentHops: Int): RelayMessage {
        return RelayMessage(
            messageId = message.id,
            originalSender = myPublicKey,
            finalRecipient = finalRecipient,
            encryptedPayload = message.payload,
            messageType = message.type.name,
            hopCount = currentHops,
            maxHops = MAX_HOPS,
            seenBy = mutableListOf(myPublicKey),
            timestamp = System.currentTimeMillis(),
            routePath = mutableListOf(myPublicKey)
        )
    }

    /**
     * Claim [id] for delivery, or report it as already seen. True means "this caller
     * owns it, carry on".
     *
     * 🔴 THE CHECK, THE INSERT AND THE TRIM ALL HAVE TO BE ONE LOCKED OPERATION.
     * None of them was. Nearby Connections delivers on its own callback threads
     * while the coroutine scope relays, so `contains` could pass twice for the same
     * id before either `add` landed (a duplicate bubble), and `take` — which
     * ITERATES — could run while another thread inserted, which is a
     * `ConcurrentModificationException` thrown inside inbound delivery. The set is a
     * `LinkedHashSet` behind a synchronized wrapper, so the trim still evicts the
     * oldest half; it just no longer races.
     */
    private fun markSeen(id: String): Boolean {
        synchronized(seenMessageIds) {
            if (!seenMessageIds.add(id)) return false
            if (seenMessageIds.size > MAX_CACHE_SIZE) {
                val toRemove = seenMessageIds.take(MAX_CACHE_SIZE / 2)
                seenMessageIds.removeAll(toRemove.toSet())
            }
        }
        return true
    }

    /**
     * Handle received relay message
     */
    private fun handleRelayMessage(fromEndpointId: String, jsonString: String) {
        try {
            val relayMsg = RelayMessage.fromJson(jsonString)

            // Check if already seen (prevent loops)
            if (!markSeen(relayMsg.messageId)) {
                Log.d(TAG, "⏭️ Already seen relay message: ${relayMsg.messageId.take(8)}...")
                return
            }

            // Update routing table (learn route back to sender)
            val fromPeer = _connectedPeers.value.find { it.id == fromEndpointId }
            if (fromPeer != null && fromPeer.publicKey.isNotEmpty()) {
                updateRoutingTable(relayMsg.originalSender, fromPeer.publicKey, relayMsg.hopCount)
            }

            Log.d(TAG, "📥 Relay message (hop ${relayMsg.hopCount}/${relayMsg.maxHops})")
            Log.d(TAG, "   From: ${relayMsg.originalSender.take(16)}...")
            Log.d(TAG, "   To: ${relayMsg.finalRecipient.take(16)}...")

            // Check if message is for us
            if (relayMsg.finalRecipient == myPublicKey) {
                Log.d(TAG, "🎯 Relay message is for us!")
                deliverRelayMessage(relayMsg)
                return
            }

            // Check hop limit
            if (relayMsg.hopCount >= relayMsg.maxHops) {
                Log.w(TAG, "🚫 Relay message exceeded max hops (${relayMsg.maxHops})")
                return
            }

            // Check if we've already relayed this
            if (relayMsg.seenBy.contains(myPublicKey)) {
                Log.d(TAG, "⏭️ Already relayed this message")
                return
            }

            // Forward the relay
            forwardRelayMessage(relayMsg, fromEndpointId)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to handle relay message: ${e.message}")
        }
    }

    /**
     * Forward a relay message to other peers
     */
    private fun forwardRelayMessage(relayMsg: RelayMessage, fromEndpointId: String) {
        val updatedMsg = relayMsg.copy(
            hopCount = relayMsg.hopCount + 1,
            seenBy = (relayMsg.seenBy + myPublicKey).toMutableList(),
            routePath = (relayMsg.routePath + myPublicKey).toMutableList()
        )

        // Check if we have a direct route to recipient
        val directPeer = _connectedPeers.value.find { it.publicKey == relayMsg.finalRecipient }
        if (directPeer != null) {
            Log.d(TAG, "🎯 Found direct route to recipient!")
            sendRelayMessage(directPeer.id, updatedMsg)
            return
        }

        // Check if recipient is reachable via CrossPlatformMesh (iOS peer)
        if (crossPlatformMesh.isRunning.value && crossPlatformMesh.isPeerReachable(relayMsg.finalRecipient)) {
            Log.d(TAG, "🍎 Relay forwarding via CrossPlatformMesh to: ${relayMsg.finalRecipient.take(16)}...")
            crossPlatformMesh.sendMessage(relayMsg.finalRecipient, relayMsg.encryptedPayload, relayMsg.messageType)
            return
        }

        // Check routing table
        val route = routingTable[relayMsg.finalRecipient]
        if (route != null && System.currentTimeMillis() - route.lastSeen < MAX_RELAY_AGE_MS) {
            val nextPeer = _connectedPeers.value.find { it.publicKey == route.nextHop }
            if (nextPeer != null && nextPeer.id != fromEndpointId) {
                Log.d(TAG, "📍 Forwarding via cached route")
                sendRelayMessage(nextPeer.id, updatedMsg)
                return
            }
        }

        // Broadcast to all except sender (Nearby Connections)
        Log.d(TAG, "📡 Broadcasting relay (hop ${updatedMsg.hopCount})")
        val handedTo = broadcastRelayMessage(updatedMsg, excludeEndpoint = fromEndpointId)

        // Also broadcast via CrossPlatformMesh for cross-platform relay
        if (crossPlatformMesh.isRunning.value) {
            Log.d(TAG, "🍎↔🤖 Cross-relay broadcast via CrossPlatformMesh")
            crossPlatformMesh.sendMessage(relayMsg.finalRecipient, relayMsg.encryptedPayload, relayMsg.messageType)
        }

        // __MESH_CUSTODY_2026_10_10__ Nowhere to send it RIGHT NOW is not the
        // same as nowhere to send it.
        //
        // `handedTo == 0` means the broadcast's target list was empty — every
        // connected peer was either the endpoint this arrived on or already in
        // `seenBy`, i.e. this node is the current edge of the mesh. Until now
        // that dropped the packet silently, which is why the off-grid chain
        // only worked when every hop happened to be awake at once. Hold it and
        // re-offer it when a peer appears — see [flushCustody].
        //
        // `updatedMsg`, not `relayMsg`: THE invariant. The hop count is already
        // incremented and this node is already in `seenBy`/`routePath`, so what
        // gets carried is exactly the relay that was attempted, not a fresh
        // origination with a renewed hop budget.
        //
        // The CrossPlatformMesh attempt just above does NOT count as a
        // hand-off: `isPeerReachable(finalRecipient)` already returned false
        // earlier in this function, and `sendOrRelay` drops (DIAG_XMESH_TX_NO_PATH)
        // rather than flooding when it has no path, so counting it would be
        // counting a drop as a delivery. Erring the other way — holding a
        // message that did get through — costs one duplicate that the next
        // node's `markSeen` swallows.
        if (handedTo == 0) {
            custody.take(updatedMsg)
        }
    }

    /**
     * Send relay message to a specific peer
     */
    private fun sendRelayMessage(endpointId: String, relayMsg: RelayMessage) {
        val jsonData = relayMsg.toJson().toByteArray()
        if (isAwarePeerId(endpointId)) {
            sendBytesToPeer(endpointId, jsonData, messageId = null)
            return
        }
        val payload = Payload.fromBytes(jsonData)

        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener {
                Log.d(TAG, "📤 Relay sent to: $endpointId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Relay send failed: ${e.message}")
            }
    }

    /**
     * Broadcast relay message to all connected peers.
     *
     * __MESH_CUSTODY_2026_10_10__ Returns the number of peers the payload was
     * ADDRESSED to, computed synchronously. The old `sentCount` was incremented
     * inside `addOnSuccessListener` and logged immediately, so it was read
     * before any send had reported and was always 0 — useless as a log and
     * unusable as the "did this go anywhere" signal that [forwardRelayMessage]
     * now needs to decide whether to take custody.
     */
    private fun broadcastRelayMessage(relayMsg: RelayMessage, excludeEndpoint: String?): Int {
        val jsonData = relayMsg.toJson().toByteArray()
        val payload = Payload.fromBytes(jsonData)

        val targets = _connectedPeers.value
            .filter { it.id != excludeEndpoint && !relayMsg.seenBy.contains(it.publicKey) }
        targets.forEach { peer ->
            if (isAwarePeerId(peer.id)) sendBytesToPeer(peer.id, jsonData, messageId = null)
            else connectionsClient.sendPayload(peer.id, payload)
        }

        Log.d(TAG, "📡 Broadcast relay to ${targets.size} peer(s)")
        return targets.size
    }

    /**
     * __MESH_CUSTODY_2026_10_10__ Re-offer everything in custody to peers that
     * are not already in the message's path.
     *
     * Targets are filtered on `seenBy` rather than just "not the peer it came
     * from": a held message may have travelled several hops, and sending it
     * back to a node already in its path is guaranteed waste — that node's own
     * [markSeen] guard would drop it on arrival.
     *
     * Custody ends on the first successful hand-off. There is no app-level ack
     * on the mesh to wait for, so holding on past that would mean keeping other
     * people's data on the strength of a guess.
     */
    fun flushCustody() {
        val pending = custody.readyToForward()
        if (pending.isEmpty()) return

        var handedOn = 0
        pending.forEach { relay ->
            // `relay` is already the hop-incremented packet that was attempted,
            // so it is re-offered verbatim: no second increment, no new path
            // entry, no renewed hop budget.
            val handedTo = broadcastRelayMessage(relay, excludeEndpoint = null)
            if (handedTo > 0) {
                custody.release(relay.messageId)
                handedOn++
            }
        }
        if (handedOn > 0) {
            Log.i(TAG, "📦 custody handed on $handedOn message(s); ${custody.count} still held")
        }
    }

    /**
     * __MESH_CUSTODY_2026_10_10__ The second flush trigger, and it is not
     * redundant with the one on connection-established.
     *
     * iOS flushes on an explicit false→true flip of `meshAvailable` inside its
     * 10-second health check, because custody survives a relaunch (it is on
     * disk) and on a cold start the mesh can come up with a peer ALREADY
     * connected — no `.connected` transition fires for a session established
     * during startup, so nothing would drain the store until the next time a
     * peer happened to join.
     *
     * Android has no health-check timer and no `meshAvailable` flag, and it has
     * TWO ways to end up with peers but no arrival callback:
     *
     *  1. `ConnectionLifecycleCallback.onConnectionResult` only fires for
     *     connections this process negotiated. The custody store is loaded from
     *     disk when this singleton is first touched, and `MainViewModel` calls
     *     `startMesh()` on every launch — so a relaunch with a full store and a
     *     peer that attaches during startup has nobody to tell it to drain.
     *  2. `stopMesh()` clears `_connectedPeers` but deliberately leaves
     *     `CrossPlatformMesh` running with its peers intact, and
     *     CrossPlatformMesh peers never reach a callback in this class at all.
     *     The BLE lane that actually reaches an iPhone could therefore gain a
     *     peer with nothing flushing.
     *
     * Collecting a `combine` of both peer lists covers all of it in one event:
     * `combine` emits the CURRENT value as soon as it is collected, so a store
     * loaded next to peers that are already connected flushes immediately, and
     * `distinctUntilChanged` then turns every later empty→non-empty transition
     * into exactly one flush.
     */
    private fun startCustodyFlushWatcher() {
        if (custodyFlushJob?.isActive == true) return
        custodyFlushJob = scope.launch {
            combine(_connectedPeers, crossPlatformMesh.connectedPeers) { nearby, cross ->
                nearby.isNotEmpty() || cross.isNotEmpty()
            }
                .distinctUntilChanged()
                .collect { reachable ->
                    if (reachable) {
                        Log.d(TAG, "📦 custody flush: a peer is reachable")
                        flushCustody()
                    }
                }
        }
    }

    /**
     * Deliver a relay message that's for us
     */
    private fun deliverRelayMessage(relayMsg: RelayMessage) {
        // Convert back to MeshMessage
        val message = MeshMessage(
            id = relayMsg.messageId,
            type = try { MessageType.valueOf(relayMsg.messageType) } catch (e: Exception) { MessageType.TEXT_MESSAGE },
            senderPublicKey = relayMsg.originalSender,
            senderName = "Via Relay (${relayMsg.hopCount} hops)",
            payload = relayMsg.encryptedPayload,
            timestamp = relayMsg.timestamp
        )

        // Notify callbacks
        messageCallbacks.forEach { it(message) }
        relayCallbacks.forEach { it(relayMsg) }

        Log.d(TAG, "✅ Delivered relay message from ${relayMsg.originalSender.take(16)}... (${relayMsg.hopCount} hops)")
    }

    /**
     * Update routing table with learned route
     */
    private fun updateRoutingTable(destination: String, nextHop: String, hops: Int) {
        val existing = routingTable[destination]
        val newRoute = RouteInfo(nextHop, hops, System.currentTimeMillis())

        if (existing == null || hops < existing.hopCount ||
            System.currentTimeMillis() - existing.lastSeen > 60_000) {
            routingTable[destination] = newRoute
            Log.d(TAG, "🗺️ Route updated: ${destination.take(16)}... via ${nextHop.take(16)}... ($hops hops)")
        }
    }

    /**
     * Add callback for relay messages
     */
    fun addRelayCallback(callback: (RelayMessage) -> Unit) {
        relayCallbacks.add(callback)
    }

    /**
     * Get mesh statistics (includes both Nearby Connections and CrossPlatformMesh)
     */
    fun getMeshStats(): MeshStats {
        val crossPlatformPeers = crossPlatformMesh.connectedPeers.value.size
        val iosPeers = crossPlatformMesh.getIOSPeers().size

        return MeshStats(
            connectedPeers = _connectedPeers.value.size + crossPlatformPeers,
            discoveredPeers = _discoveredPeers.value.size + crossPlatformMesh.crossPlatformPeers.value.size,
            knownRoutes = routingTable.size,
            cachedMessageIds = seenMessageIds.size,
            maxHops = MAX_HOPS,
            iosPeers = iosPeers,
            crossPlatformEnabled = crossPlatformMesh.isRunning.value
        )
    }

    private fun sendIdentityExchange(endpointId: String) {
        val message = MeshMessage(
            id = java.util.UUID.randomUUID().toString(),
            type = MessageType.IDENTITY_EXCHANGE,
            senderPublicKey = myPublicKey,
            senderName = myDisplayName,
            payload = """{"publicKey": "$myPublicKey", "name": "$myDisplayName"}""",
            timestamp = System.currentTimeMillis()
        )
        sendMessage(endpointId, message)
    }

    private fun handleIdentityExchange(endpointId: String, message: MeshMessage) {
        Log.d(TAG, "🤝 Identity exchange from: ${message.senderName}")

        // __WIFI_AWARE_2026_11_25__ A Wi-Fi Aware peer's key was PROVEN by the
        // handshake; an unauthenticated JSON claim must never overwrite it.
        if (isAwarePeerId(endpointId)) return

        // Update peer info
        _connectedPeers.value = _connectedPeers.value.map { peer ->
            if (peer.id == endpointId) {
                peer.copy(
                    publicKey = message.senderPublicKey,
                    displayName = message.senderName
                )
            } else peer
        }
    }

    private fun handleAck(message: MeshMessage) {
        Log.d(TAG, "✓ ACK received for message: ${message.id}")
    }

    private fun requestConnection(endpointId: String, name: String) {
        connectionsClient.requestConnection(
            myDisplayName,
            endpointId,
            connectionLifecycleCallback
        ).addOnSuccessListener {
            Log.d(TAG, "📲 Connection requested to: $name")
        }.addOnFailureListener { e ->
            Log.e(TAG, "❌ Connection request failed: ${e.message}")
        }
    }

    // ============================================================================
    // WIFI DIRECT DATA TRANSFER
    // ============================================================================

    private fun sendViaWifiDirect(peerId: String, data: ByteArray) {
        scope.launch {
            try {
                val socket = clientSockets[peerId] ?: return@launch
                val outputStream = DataOutputStream(socket.getOutputStream())
                outputStream.writeInt(data.size)
                outputStream.write(data)
                outputStream.flush()
                Log.d(TAG, "📤 Sent via WiFi Direct to: $peerId")
            } catch (e: Exception) {
                Log.e(TAG, "❌ WiFi Direct send failed: ${e.message}")
            }
        }
    }

    // ============================================================================
    // CALLBACKS REGISTRATION
    // ============================================================================

    fun addMessageCallback(callback: (MeshMessage) -> Unit) {
        messageCallbacks.add(callback)
    }

    fun removeMessageCallback(callback: (MeshMessage) -> Unit) {
        messageCallbacks.remove(callback)
    }

    fun addConnectionCallback(callback: (MeshPeer, Boolean) -> Unit) {
        connectionCallbacks.add(callback)
    }

    // ============================================================================
    // PERMISSIONS
    // ============================================================================

    /**
     * Recompute [missingMeshPermissions] against what is granted right now and
     * return true when nothing is missing.
     *
     * The permission list comes from [MeshPermissionPolicy] rather than being
     * spelled out again here, so the set the app REQUESTS and the set it
     * REPORTS MISSING cannot drift apart — a permission present in one list and
     * absent from the other is a mesh that is never asked for and never
     * reported. `MeshPermissionPolicyTest` pins each policy constant against the
     * matching `android.Manifest.permission` constant so a typo in either is a
     * failing test rather than a permanently-missing grant.
     */
    private fun refreshMissingPermissions(): Boolean {
        val granted = MeshPermissionPolicy.allRequestable(Build.VERSION.SDK_INT)
            .filter { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            .toSet()
        val missing = MeshPermissionPolicy.missing(Build.VERSION.SDK_INT, granted)
        _missingMeshPermissions.value = missing
        return missing.isEmpty()
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // ============================================================================
    // WIFI DIRECT RECEIVER
    // ============================================================================

    inner class WifiDirectReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "📶 WiFi Direct state: ${if (enabled) "ENABLED" else "DISABLED"}")
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d(TAG, "📶 WiFi Direct peers changed")
                    // Request updated peer list
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    Log.d(TAG, "📶 WiFi Direct connection changed")
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    Log.d(TAG, "📶 This device changed")
                }
            }
        }
    }

    // ============================================================================
    // CLEANUP
    // ============================================================================

    fun cleanup() {
        stopMesh()
        _meshStarted = false
        wifiAware.stop()
        scope.cancel()
        serverSocket?.close()
        clientSockets.values.forEach { it.close() }
        clientSockets.clear()

        // Cleanup CrossPlatformMesh
        crossPlatformMesh.cleanup()
    }
}

// ============================================================================
// DATA CLASSES
// ============================================================================

enum class MeshState {
    IDLE,
    ADVERTISING,
    DISCOVERING,
    CONNECTED
}

enum class ConnectionType {
    NEARBY,
    WIFI_DIRECT,
    BLUETOOTH,
    CROSS_PLATFORM,  // iOS ↔ Android via BLE/NSD/TCP
    /** __WIFI_AWARE_2026_11_25__ Android ↔ Android over a Wi-Fi Aware (NAN) data path. */
    WIFI_AWARE
}

data class MeshPeer(
    val id: String,
    val publicKey: String,
    val displayName: String,
    val connectionType: ConnectionType,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * Any of these can be forwarded onto the cross-platform transport verbatim
 * (`MeshNetworkManager.kt:280`, `:592` pass `message.type.name`), and iOS's dispatcher
 * (`OSHI/CrossPlatformMesh.swift:678-691`) has a case for all of them EXCEPT [ACK]:
 * iOS has no ACK concept on this transport, so one forwarded to an iPhone lands in
 * `default:` and is logged and dropped. Android→iOS mesh messages are simply never
 * acked, which is symmetric and already how it works — the enum just makes it look
 * otherwise.
 */
enum class MessageType {
    IDENTITY_EXCHANGE,
    TEXT_MESSAGE,
    MEDIA_MESSAGE,
    CALL_SIGNAL,
    CALL_AUDIO,
    ACK,
    RELAY
}

/**
 * MeshMessage - Compatible with iOS format
 */
data class MeshMessage(
    val id: String,
    val type: MessageType,
    val senderPublicKey: String,
    val senderName: String,
    val payload: String,
    val timestamp: Long,
    val encrypted: Boolean = true
) {
    fun toJson(): String {
        return """
        {
            "id": "$id",
            "type": "${type.name}",
            "senderPublicKey": "$senderPublicKey",
            "senderName": "$senderName",
            "payload": ${payload.replace("\\", "\\\\").replace("\"", "\\\"")},
            "timestamp": $timestamp,
            "encrypted": $encrypted
        }
        """.trimIndent()
    }

    companion object {
        fun fromJson(json: String): MeshMessage {
            // Simple JSON parsing (use Gson in production)
            val id = json.extractJsonString("id")
            val type = MessageType.valueOf(json.extractJsonString("type"))
            val senderPublicKey = json.extractJsonString("senderPublicKey")
            val senderName = json.extractJsonString("senderName")
            val payload = json.extractJsonString("payload")
            val timestamp = json.extractJsonLong("timestamp")
            val encrypted = json.extractJsonBoolean("encrypted")

            return MeshMessage(id, type, senderPublicKey, senderName, payload, timestamp, encrypted)
        }
    }
}

// JSON extraction helpers
private fun String.extractJsonString(key: String): String {
    val regex = """"$key"\s*:\s*"([^"]*)"""".toRegex()
    return regex.find(this)?.groupValues?.get(1) ?: ""
}

private fun String.extractJsonLong(key: String): Long {
    val regex = """"$key"\s*:\s*(\d+)""".toRegex()
    return regex.find(this)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
}

private fun String.extractJsonBoolean(key: String): Boolean {
    val regex = """"$key"\s*:\s*(true|false)""".toRegex()
    return regex.find(this)?.groupValues?.get(1)?.toBoolean() ?: false
}

// ============================================================================
// RELAY DATA CLASSES (iOS Compatible)
// ============================================================================

/**
 * RelayMessage - Multi-hop message for extended range
 * Compatible with iOS RelayMessage format
 */
data class RelayMessage(
    val messageId: String,
    val originalSender: String,
    val finalRecipient: String,
    val encryptedPayload: String,
    val messageType: String,
    var hopCount: Int,
    val maxHops: Int,
    val seenBy: MutableList<String>,
    val timestamp: Long,
    val routePath: MutableList<String>
) {
    fun toJson(): String {
        val seenByJson = seenBy.joinToString(",") { "\"$it\"" }
        val routePathJson = routePath.joinToString(",") { "\"$it\"" }
        return """
        {
            "messageId": "$messageId",
            "originalSender": "$originalSender",
            "finalRecipient": "$finalRecipient",
            "encryptedPayload": "${encryptedPayload.replace("\\", "\\\\").replace("\"", "\\\"")}",
            "messageType": "$messageType",
            "hopCount": $hopCount,
            "maxHops": $maxHops,
            "seenBy": [$seenByJson],
            "timestamp": $timestamp,
            "routePath": [$routePathJson]
        }
        """.trimIndent()
    }

    companion object {
        fun fromJson(json: String): RelayMessage {
            val messageId = json.extractJsonString("messageId")
            val originalSender = json.extractJsonString("originalSender")
            val finalRecipient = json.extractJsonString("finalRecipient")
            val encryptedPayload = json.extractJsonString("encryptedPayload")
            val messageType = json.extractJsonString("messageType")
            val hopCount = json.extractJsonLong("hopCount").toInt()
            val maxHops = json.extractJsonLong("maxHops").toInt()
            val timestamp = json.extractJsonLong("timestamp")

            // Parse arrays
            val seenByRegex = """"seenBy"\s*:\s*\[(.*?)\]""".toRegex()
            val seenByMatch = seenByRegex.find(json)?.groupValues?.get(1) ?: ""
            val seenBy = seenByMatch.split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
                .toMutableList()

            val routePathRegex = """"routePath"\s*:\s*\[(.*?)\]""".toRegex()
            val routePathMatch = routePathRegex.find(json)?.groupValues?.get(1) ?: ""
            val routePath = routePathMatch.split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotEmpty() }
                .toMutableList()

            return RelayMessage(
                messageId, originalSender, finalRecipient, encryptedPayload,
                messageType, hopCount, maxHops, seenBy, timestamp, routePath
            )
        }
    }
}

// RouteInfo is defined in CrossPlatformMesh.kt (same package)

/**
 * Mesh network statistics (includes both Nearby Connections and CrossPlatformMesh)
 */
data class MeshStats(
    val connectedPeers: Int,
    val discoveredPeers: Int,
    val knownRoutes: Int,
    val cachedMessageIds: Int,
    val maxHops: Int,
    val iosPeers: Int = 0,
    val crossPlatformEnabled: Boolean = false
)
