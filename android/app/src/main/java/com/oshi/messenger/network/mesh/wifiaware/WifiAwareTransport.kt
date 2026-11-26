package com.oshi.messenger.network.mesh.wifiaware

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import com.oshi.messenger.network.mesh.MeshDiag
import com.oshi.messenger.network.mesh.MeshPermissionPolicy
import com.oshi.messenger.network.mesh.wifiaware.WifiAwareArbiter.Lifecycle
import com.oshi.messenger.network.mesh.wifiaware.WifiAwareProtocol.Discovery
import com.oshi.messenger.network.mesh.wifiaware.WifiAwareProtocol.Framing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * __WIFI_AWARE_2026_11_25__ Wi-Fi Aware (NAN) as one more offline lane for the
 * mesh (GitHub issue Lastoneparis/OSHI#4).
 *
 * WHAT IT IS. A phone-to-phone Wi-Fi link that needs no router, no hotspot and
 * no Wi-Fi Direct group: both phones publish and subscribe the service
 * [WifiAwareProtocol.SERVICE_NAME], the one with the smaller random token asks for
 * a data path, and a TCP socket over that path carries the exact same
 * `MeshMessage` / `RelayMessage` JSON Nearby Connections carries — whose payload
 * is the already end-to-end encrypted OSHI envelope. [com.oshi.messenger.network.mesh.MeshNetworkManager]
 * lists a verified peer as `ConnectionType.WIFI_AWARE` and routes to it like any
 * other mesh peer, so every send path (text, media, calls, groups, relay,
 * custody) picks it up without being touched.
 *
 * WHAT IT MUST NOT DO (owner's requirement: don't break current connections):
 *  • It never touches infrastructure Wi-Fi, the hotspot, Wi-Fi Direct, Bluetooth
 *    or LoRa. It only READS the P2P and hotspot state to stay out of their way
 *    ([WifiAwareArbiter]).
 *  • Internet traffic never goes over Aware: an Aware network has no INTERNET
 *    capability and never becomes the default network, the process is never
 *    bound to it, and only this lane's own sockets are ([Network.bindSocket]).
 *  • When Aware goes away (Wi-Fi off, chip taken by a hotspot, location off
 *    below 13) everything is closed quietly, no dialog, and the peer drops out
 *    of the mesh list so the send path falls back to the other lanes. Messages
 *    handed to the socket but never acknowledged by the peer are reported in
 *    [Listener.onPeerDisconnected] so the caller can resend or flag them.
 *
 * Nothing here has run on two phones yet: the framing, handshake, arbiter and
 * state machine are unit-tested; the radio calls are compiled, not proven.
 *
 * API levels: every public entry point returns early unless [isSupported]
 * (FEATURE_WIFI_AWARE and API 29+), which is why the class suppresses NewApi
 * instead of pushing `@RequiresApi` onto every mesh caller.
 */
@SuppressLint("NewApi")
class WifiAwareTransport(
    context: Context,
    /** `DH(myPrivate, peerPublicKey)`, or null when it cannot be computed. */
    private val sharedSecret: (peerPublicKey: String) -> ByteArray?,
    private val listener: Listener,
) {
    interface Listener {
        fun onPeerConnected(publicKey: String, displayName: String)
        /** [unackedMessageIds]: handed to this link, never acknowledged by the peer. */
        fun onPeerDisconnected(publicKey: String, unackedMessageIds: List<String>)
        fun onEnvelope(publicKey: String, json: ByteArray)
    }

    companion object {
        private const val TAG = "WifiAware"
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val IDLE_TIMEOUT_MS = 20_000
        private const val PING_INTERVAL_MS = 5_000L
        private const val DATA_PATH_TIMEOUT_MS = 30_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val PENDING_HELLO_TIMEOUT_MS = 30_000L
        private const val SWEEP_INTERVAL_MS = 30_000L

        /** Hidden in the SDK, stable since API 14: `WifiManager.WIFI_AP_STATE_CHANGED_ACTION`. */
        private const val ACTION_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_AP_STATE = "wifi_state"
        private const val AP_STATE_ENABLING = 12
        private const val AP_STATE_ENABLED = 13
    }

    private val appContext = context.applicationContext
    private val random = SecureRandom()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handlerThread = HandlerThread("oshi-wifi-aware")
    private val handler: Handler by lazy { handlerThread.start(); Handler(handlerThread.looper) }

    private val awareManager: WifiAwareManager? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            appContext.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        else null
    }
    private val connectivity: ConnectivityManager? by lazy {
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    // ── state confined to [handler] ──
    private var state = Lifecycle.State.OFF
    private var meshActive = false
    private var myPublicKey = ""
    private var myName = ""
    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var myToken = ByteArray(WifiAwareProtocol.TOKEN_LENGTH)
    private var attachFailures = 0
    private var retryScheduled = false
    private var sweepScheduled = false
    private var receiver: BroadcastReceiver? = null
    private var nextDiscoveryMessageId = 1
    /** Bumped on every attach and detach; callbacks of an older attach are ignored. */
    private var generation = 0
    private var reevaluateScheduled = false
    /** Publishers our subscription has seen, by token hex — re-tried by [sweep]. */
    private val discovered = HashMap<String, Pair<PeerHandle, Discovery.Message>>()
    /** Initiator side: HELLO sent, waiting for READY. Value: (peer, passphrase). */
    private val awaitingReady = HashMap<String, Pair<PeerHandle, String>>()
    private val paths = HashMap<String, DataPath>()
    private var serverSocket: ServerSocket? = null

    @Volatile private var p2pGroupActive = false
    @Volatile private var hotspotActive = false

    // ── state shared with IO threads ──
    private val links = ConcurrentHashMap<String, Link>()
    private val expectedPeerAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _running = MutableStateFlow(false)
    /** True while attached (publishing and subscribing). */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _peerKeys = MutableStateFlow<Set<String>>(emptySet())
    /** Public keys of peers whose handshake verified and whose socket is open. */
    val peerKeys: StateFlow<Set<String>> = _peerKeys.asStateFlow()

    // ============================================================================
    // PUBLIC
    // ============================================================================

    fun isSupported(): Boolean = WifiAwarePrefs.isSupported(appContext)

    /** The mesh came up (or its identity changed). Idempotent. */
    fun start(publicKey: String, displayName: String) {
        if (!isSupported() || publicKey.isEmpty()) return
        handler.post {
            val identityChanged = myPublicKey.isNotEmpty() && myPublicKey != publicKey
            myPublicKey = publicKey
            myName = displayName
            meshActive = true
            registerReceiver()
            if (identityChanged && state != Lifecycle.State.OFF) {
                apply(Lifecycle.Event.FORBIDDEN)
            }
            evaluate()
        }
    }

    /** The mesh was switched off. Closes everything; never touches another radio. */
    fun stop() {
        if (!isSupported()) return
        handler.post {
            meshActive = false
            evaluate()
            unregisterReceiver()
        }
    }

    /** Re-read switch / permissions / radio state (settings toggled, permission granted…). */
    fun refresh() {
        if (!isSupported()) return
        handler.post { evaluate() }
    }

    fun isConnected(publicKey: String): Boolean = links[publicKey]?.closed == false

    /**
     * Queue one envelope for [publicKey]. False when there is no verified open
     * link — the caller then uses another lane. True means QUEUED, not
     * delivered; an envelope never acknowledged comes back through
     * [Listener.onPeerDisconnected] with its [messageId].
     */
    fun send(publicKey: String, json: ByteArray, messageId: String?): Boolean {
        val link = links[publicKey] ?: return false
        if (link.closed || json.size > Framing.MAX_PAYLOAD - 8) return false
        val seq = link.seq.incrementAndGet()
        if (messageId != null) link.unacked[seq] = messageId
        val ok = link.outbox.trySend(Framing.Frame(Framing.TYPE_ENVELOPE, Framing.encodeEnvelope(seq, json))).isSuccess
        if (!ok) link.unacked.remove(seq)
        return ok
    }

    // ============================================================================
    // DECISION
    // ============================================================================

    private fun permissionsGranted(): Boolean =
        MeshPermissionPolicy.wifiAwarePermissions(Build.VERSION.SDK_INT).all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }

    @SuppressLint("NewApi")
    private fun inputs(): WifiAwareArbiter.Inputs {
        val sdk = Build.VERSION.SDK_INT
        val mgr = awareManager
        val resources = if (sdk >= Build.VERSION_CODES.S && mgr != null)
            try { mgr.availableAwareResources } catch (e: Exception) { null } else null
        return WifiAwareArbiter.Inputs(
            featurePresent = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE),
            sdkInt = sdk,
            userEnabled = WifiAwarePrefs.isEnabled(appContext),
            meshActive = meshActive,
            permissionsGranted = permissionsGranted(),
            awareAvailable = try { mgr?.isAvailable == true } catch (e: Exception) { false },
            wifiDirectGroupActive = p2pGroupActive,
            hotspotActive = hotspotActive,
            availablePublishSessions = resources?.availablePublishSessionsCount,
            availableSubscribeSessions = resources?.availableSubscribeSessionsCount,
            currentlyRunning = state == Lifecycle.State.RUNNING || state == Lifecycle.State.ATTACHING,
        )
    }

    private fun evaluate() {
        val decision = WifiAwareArbiter.decide(inputs())
        val event = when (decision) {
            WifiAwareArbiter.Decision.Run -> Lifecycle.Event.ALLOWED
            is WifiAwareArbiter.Decision.Off -> Lifecycle.Event.FORBIDDEN
            is WifiAwareArbiter.Decision.Wait ->
                if (decision.reason == WifiAwareArbiter.Reason.UNAVAILABLE) Lifecycle.Event.UNAVAILABLE
                else Lifecycle.Event.BUSY
        }
        if (decision != WifiAwareArbiter.Decision.Run) Log.d(TAG, "decision: $decision")
        apply(event)
        // Resources and permissions have no broadcast: while waiting, look again
        // now and then. Cheap (no radio call), and stops once Off or running.
        if (decision is WifiAwareArbiter.Decision.Wait) scheduleReevaluate()
    }

    private fun scheduleReevaluate() {
        if (reevaluateScheduled) return
        reevaluateScheduled = true
        handler.postDelayed({ reevaluateScheduled = false; if (meshActive) evaluate() }, 60_000L)
    }

    private fun apply(event: Lifecycle.Event) {
        val step = Lifecycle.next(state, event)
        if (step.state != state) Log.d(TAG, "lifecycle ${state} --$event--> ${step.state} (${step.action})")
        state = step.state
        when (step.action) {
            Lifecycle.Action.NONE -> Unit
            Lifecycle.Action.ATTACH -> attach()
            Lifecycle.Action.DETACH -> detach()
            Lifecycle.Action.SCHEDULE_RETRY -> scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (retryScheduled) return
        retryScheduled = true
        val wait = WifiAwareArbiter.backoffMs(attachFailures)
        handler.postDelayed({ retryScheduled = false; evaluate() }, wait)
    }

    // ============================================================================
    // RADIO STATE (read only)
    // ============================================================================

    private fun registerReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED -> Unit
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val info = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        p2pGroupActive = info?.groupFormed == true
                    }
                    ACTION_AP_STATE -> {
                        val s = intent.getIntExtra(EXTRA_AP_STATE, -1)
                        hotspotActive = s == AP_STATE_ENABLING || s == AP_STATE_ENABLED
                    }
                }
                evaluate()
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(ACTION_AP_STATE)
        }
        try {
            // Delivered on [handler], so every state change is serialised with
            // the Aware callbacks. Sticky P2P / hotspot broadcasts arrive right
            // away and seed [p2pGroupActive] / [hotspotActive].
            ContextCompat.registerReceiver(appContext, r, filter, null, handler, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        } catch (e: Exception) {
            Log.w(TAG, "cannot watch radio state (${e.message}) — staying off")
        }
    }

    private fun unregisterReceiver() {
        receiver?.let { try { appContext.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
    }

    // ============================================================================
    // ATTACH / DETACH
    // ============================================================================

    @SuppressLint("MissingPermission")
    private fun attach() {
        val mgr = awareManager ?: run { apply(Lifecycle.Event.ATTACH_FAILED); return }
        val gen = ++generation
        try {
            mgr.attach(object : AttachCallback() {
                override fun onAttached(s: WifiAwareSession) {
                    if (gen != generation) { try { s.close() } catch (_: Exception) {}; return }
                    onAttached(s)
                }
                override fun onAttachFailed() {
                    if (gen != generation) return
                    attachFailures++
                    Log.w(TAG, "attach failed ($attachFailures)")
                    apply(Lifecycle.Event.ATTACH_FAILED)
                }
                override fun onAwareSessionTerminated() {
                    if (gen != generation) return
                    Log.d(TAG, "session terminated by the system")
                    session = null
                    apply(Lifecycle.Event.UNAVAILABLE)
                }
            }, handler)
        } catch (e: Exception) {
            // SecurityException (permission revoked between check and call) etc.
            Log.w(TAG, "attach threw: ${e.message}")
            attachFailures++
            apply(Lifecycle.Event.ATTACH_FAILED)
        }
    }

    @SuppressLint("MissingPermission")
    private fun onAttached(s: WifiAwareSession) {
        val wasAttaching = state == Lifecycle.State.ATTACHING
        session = s
        apply(Lifecycle.Event.ATTACHED)
        if (!wasAttaching) return // the reducer answered DETACH; [detach] closed [s]
        attachFailures = 0
        random.nextBytes(myToken)
        val announce = Discovery.encodeAnnounce(myToken, Discovery.identityHint(myPublicKey, myToken))
        val gen = generation
        try {
            s.publish(
                PublishConfig.Builder()
                    .setServiceName(WifiAwareProtocol.SERVICE_NAME)
                    .setServiceSpecificInfo(announce)
                    .build(),
                publishCallback(gen), handler
            )
            s.subscribe(
                SubscribeConfig.Builder()
                    .setServiceName(WifiAwareProtocol.SERVICE_NAME)
                    .build(),
                subscribeCallback(gen), handler
            )
        } catch (e: Exception) {
            Log.w(TAG, "publish/subscribe threw: ${e.message}")
            apply(Lifecycle.Event.UNAVAILABLE)
            return
        }
        _running.value = true
        MeshDiag.log("wifi-aware: attached, publishing + subscribing")
        scheduleSweep()
    }

    /** Close everything this lane opened, quietly. Peers fall back to the other lanes. */
    private fun detach() {
        generation++
        _running.value = false
        links.values.toList().forEach { closeLink(it, "detach") }
        paths.values.toList().forEach { releasePath(it) }
        paths.clear()
        awaitingReady.clear()
        discovered.clear()
        expectedPeerAddresses.clear()
        try { publishSession?.close() } catch (_: Exception) {}
        try { subscribeSession?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        publishSession = null
        subscribeSession = null
        session = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    // ============================================================================
    // DISCOVERY
    // ============================================================================

    private fun publishCallback(gen: Int) = object : DiscoverySessionCallback() {
        override fun onPublishStarted(s: PublishDiscoverySession) {
            if (gen != generation || state != Lifecycle.State.RUNNING) { s.close(); return }
            publishSession = s
        }
        override fun onSessionConfigFailed() {
            if (gen != generation) return
            Log.w(TAG, "publish config failed"); apply(Lifecycle.Event.UNAVAILABLE)
        }
        override fun onSessionTerminated() {
            if (gen != generation) return
            publishSession = null; apply(Lifecycle.Event.UNAVAILABLE)
        }
        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            if (gen == generation) onHello(peerHandle, message)
        }
    }

    private fun subscribeCallback(gen: Int) = object : DiscoverySessionCallback() {
        override fun onSubscribeStarted(s: SubscribeDiscoverySession) {
            if (gen != generation || state != Lifecycle.State.RUNNING) { s.close(); return }
            subscribeSession = s
        }
        override fun onSessionConfigFailed() {
            if (gen != generation) return
            Log.w(TAG, "subscribe config failed"); apply(Lifecycle.Event.UNAVAILABLE)
        }
        override fun onSessionTerminated() {
            if (gen != generation) return
            subscribeSession = null; apply(Lifecycle.Event.UNAVAILABLE)
        }
        override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: MutableList<ByteArray>?) {
            if (gen != generation) return
            val m = Discovery.decode(serviceSpecificInfo) ?: return
            if (m.type != Discovery.TYPE_ANNOUNCE) return
            discovered[m.token.toHex()] = peerHandle to m
            maybeInitiate(peerHandle, m)
        }
        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            if (gen == generation) onReady(peerHandle, message)
        }
    }

    private fun scheduleSweep() {
        if (sweepScheduled) return
        sweepScheduled = true
        handler.postDelayed({
            sweepScheduled = false
            if (state != Lifecycle.State.RUNNING) return@postDelayed
            // A peer whose link dropped, or whose HELLO was lost, while it is still
            // in range: discovery reports a publisher once, so try again from here.
            discovered.values.toList().forEach { (handle, m) -> maybeInitiate(handle, m) }
            scheduleSweep()
        }, SWEEP_INTERVAL_MS)
    }

    @SuppressLint("NewApi")
    private fun availableDataPaths(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            try { awareManager?.availableAwareResources?.availableDataPathsCount } catch (e: Exception) { null }
        else null

    /** Initiator: we hold the smaller token, so we ask for the data path. */
    private fun maybeInitiate(peerHandle: PeerHandle, m: Discovery.Message) {
        val sub = subscribeSession ?: return
        if (state != Lifecycle.State.RUNNING) return
        if (!Discovery.shouldInitiate(myToken, m.token)) return
        val key = m.token.toHex()
        if (paths.containsKey(key) || awaitingReady.containsKey(key)) return
        // Already connected to this person over Aware (their previous token).
        if (links.keys.any { Discovery.hintMatches(it, m.token, m.hint) }) return
        if (!WifiAwareArbiter.mayOpenDataPath(Build.VERSION.SDK_INT, availableDataPaths(), paths.size)) return
        val salt = ByteArray(WifiAwareProtocol.SALT_LENGTH).also(random::nextBytes)
        awaitingReady[key] = peerHandle to Discovery.passphrase(myToken, m.token, salt)
        try {
            sub.sendMessage(peerHandle, nextDiscoveryMessageId++,
                Discovery.encodeHello(myToken, Discovery.identityHint(myPublicKey, myToken), salt))
        } catch (e: Exception) {
            awaitingReady.remove(key)
            return
        }
        handler.postDelayed({ awaitingReady.remove(key) }, PENDING_HELLO_TIMEOUT_MS)
    }

    /** Responder: a HELLO from the peer that won the election. */
    private fun onHello(peerHandle: PeerHandle, message: ByteArray) {
        val pub = publishSession ?: return
        if (state != Lifecycle.State.RUNNING) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val m = Discovery.decode(message) ?: return
        if (m.type != Discovery.TYPE_HELLO || m.salt == null) return
        // The election says WE initiate with this peer: ignore a confused HELLO.
        if (Discovery.shouldInitiate(myToken, m.token)) return
        val key = m.token.toHex()
        val ready = Discovery.encodeReady(myToken, Discovery.identityHint(myPublicKey, myToken))
        if (paths.containsKey(key)) {
            // The initiator retried (lost READY): just say ready again.
            try { pub.sendMessage(peerHandle, nextDiscoveryMessageId++, ready) } catch (_: Exception) {}
            return
        }
        if (links.keys.any { Discovery.hintMatches(it, m.token, m.hint) }) return
        if (!WifiAwareArbiter.mayOpenDataPath(Build.VERSION.SDK_INT, availableDataPaths(), paths.size)) return
        val server = ensureServerSocket() ?: return
        val passphrase = Discovery.passphrase(m.token, myToken, m.salt)
        val spec = try {
            WifiAwareNetworkSpecifier.Builder(pub, peerHandle)
                .setPskPassphrase(passphrase)
                .setPort(server.localPort)
                .setTransportProtocol(6) // TCP
                .build()
        } catch (e: Exception) { Log.w(TAG, "responder specifier: ${e.message}"); return }
        if (!requestPath(key, spec, initiator = false)) return
        try { pub.sendMessage(peerHandle, nextDiscoveryMessageId++, ready) } catch (_: Exception) {}
    }

    /** Initiator: the responder has asked for its side of the path — ask for ours. */
    private fun onReady(peerHandle: PeerHandle, message: ByteArray) {
        val sub = subscribeSession ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val m = Discovery.decode(message) ?: return
        if (m.type != Discovery.TYPE_READY) return
        val key = m.token.toHex()
        val (handle, passphrase) = awaitingReady.remove(key) ?: return
        if (paths.containsKey(key)) return
        val spec = try {
            WifiAwareNetworkSpecifier.Builder(sub, handle)
                .setPskPassphrase(passphrase)
                .build()
        } catch (e: Exception) { Log.w(TAG, "initiator specifier: ${e.message}"); return }
        requestPath(key, spec, initiator = true)
    }

    // ============================================================================
    // DATA PATH
    // ============================================================================

    private inner class DataPath(val key: String, val initiator: Boolean) {
        lateinit var callback: ConnectivityManager.NetworkCallback
        @Volatile var peerAddress: String? = null
        @Volatile var connectStarted = false
        @Volatile var released = false
    }

    private fun requestPath(key: String, spec: WifiAwareNetworkSpecifier, initiator: Boolean): Boolean {
        val cm = connectivity ?: return false
        val path = DataPath(key, initiator)
        // Only TRANSPORT_WIFI_AWARE + this specifier: the request can match
        // nothing but this one peer-to-peer link. It is never the default
        // network and carries no INTERNET capability.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()
        path.callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                onPathCapabilities(path, network, caps)
            }
            override fun onUnavailable() { handler.post { dropPath(path) } }
            override fun onLost(network: Network) { handler.post { dropPath(path) } }
        }
        return try {
            cm.requestNetwork(request, path.callback, handler, DATA_PATH_TIMEOUT_MS)
            paths[key] = path
            true
        } catch (e: Exception) {
            // TooManyRequestsException, SecurityException…: stay quiet, other lanes carry on.
            Log.w(TAG, "requestNetwork refused: ${e.message}")
            false
        }
    }

    private fun onPathCapabilities(path: DataPath, network: Network, caps: NetworkCapabilities) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || path.released) return
        val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
        val peerIp: Inet6Address = info.peerIpv6Addr ?: return
        val address = WifiAwareArbiter.normaliseAddress(peerIp.hostAddress ?: return)
        path.peerAddress = address
        expectedPeerAddresses.add(address)
        if (!path.initiator || path.connectStarted) return
        val port = info.port
        if (port <= 0) return
        path.connectStarted = true
        scope.launch {
            val socket = Socket()
            try {
                // THE ONE BINDING: this socket, and nothing else, goes over Aware.
                network.bindSocket(socket)
                socket.connect(InetSocketAddress(peerIp, port), CONNECT_TIMEOUT_MS)
                runLink(socket, path)
            } catch (e: Exception) {
                Log.w(TAG, "connect over Aware failed: ${e.message}")
                try { socket.close() } catch (_: Exception) {}
                handler.post { dropPath(path) }
            }
        }
    }

    private fun dropPath(path: DataPath) {
        releasePath(path)
        if (paths[path.key] === path) paths.remove(path.key)
        // A link still running over this path dies with it; closeLink reports the peer gone.
        links.values.filter { it.path === path }.forEach { closeLink(it, "path lost") }
    }

    private fun releasePath(path: DataPath) {
        if (path.released) return
        path.released = true
        path.peerAddress?.let { expectedPeerAddresses.remove(it) }
        // Unregistering is what tears this one data path down — and only it.
        try { connectivity?.unregisterNetworkCallback(path.callback) } catch (_: Exception) {}
    }

    private fun ensureServerSocket(): ServerSocket? {
        serverSocket?.let { if (!it.isClosed) return it }
        return try {
            val s = ServerSocket(0)
            serverSocket = s
            scope.launch { acceptLoop(s) }
            s
        } catch (e: IOException) {
            Log.w(TAG, "server socket: ${e.message}")
            null
        }
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (e: IOException) { break }
            val remote = socket.inetAddress
            val ifName = (remote as? Inet6Address)?.scopedInterface?.name
            if (!WifiAwareArbiter.acceptInbound(ifName, remote?.hostAddress ?: "", expectedPeerAddresses)) {
                try { socket.close() } catch (_: Exception) {}
                continue
            }
            val addr = WifiAwareArbiter.normaliseAddress(remote?.hostAddress ?: "")
            scope.launch {
                val path = paths.values.firstOrNull { it.peerAddress == addr }
                runLink(socket, path)
            }
        }
    }

    // ============================================================================
    // LINK
    // ============================================================================

    private inner class Link(val socket: Socket, val path: DataPath?) {
        @Volatile var publicKey = ""
        val outbox = Channel<Framing.Frame>(Channel.UNLIMITED)
        val seq = AtomicLong(0)
        val unacked = ConcurrentHashMap<Long, String>()
        @Volatile var closed = false
    }

    private suspend fun runLink(socket: Socket, path: DataPath?) {
        val link = Link(socket, path)
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val nonce = ByteArray(WifiAwareProtocol.NONCE_LENGTH).also(random::nextBytes)
            val handshake = WifiAwareProtocol.Handshake(myPublicKey, myName, nonce, sharedSecret)
            handshake.start().let { Framing.write(output, it.type, it.payload) }
            val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
            var verified: WifiAwareProtocol.Handshake.Result.Verified? = null
            while (verified == null) {
                if (System.currentTimeMillis() > deadline) throw IOException("handshake timeout")
                val frame = Framing.read(input, Framing.MAX_HANDSHAKE_PAYLOAD) ?: throw IOException("closed in handshake")
                when (val r = handshake.onFrame(frame)) {
                    is WifiAwareProtocol.Handshake.Result.Continue -> r.send.forEach { Framing.write(output, it.type, it.payload) }
                    is WifiAwareProtocol.Handshake.Result.Verified -> { r.send.forEach { Framing.write(output, it.type, it.payload) }; verified = r }
                    is WifiAwareProtocol.Handshake.Result.Failed -> throw IOException("handshake: ${r.reason}")
                }
            }
            link.publicKey = verified.peerPublicKey
            // One link per person: a second path to somebody already connected is
            // closed, never the live one.
            if (links.putIfAbsent(link.publicKey, link) != null) {
                Log.d(TAG, "duplicate link to ${link.publicKey.take(12)}… — keeping the live one")
                link.closed = true
                try { socket.close() } catch (_: Exception) {}
                path?.let { p -> handler.post { dropPath(p) } }
                return
            }
            _peerKeys.value = links.keys.toSet()
            MeshDiag.log("wifi-aware: peer verified ${link.publicKey.take(12)}…")
            listener.onPeerConnected(link.publicKey, verified.peerName)

            socket.soTimeout = IDLE_TIMEOUT_MS
            val writer = scope.launch {
                try {
                    for (frame in link.outbox) Framing.write(output, frame.type, frame.payload)
                } catch (e: Exception) {
                    try { socket.close() } catch (_: Exception) {}
                }
            }
            val pinger = scope.launch {
                while (isActive && !link.closed) {
                    delay(PING_INTERVAL_MS)
                    link.outbox.trySend(Framing.Frame(Framing.TYPE_PING, ByteArray(0)))
                }
            }
            try {
                loop@ while (true) {
                    val frame = Framing.read(input) ?: break
                    when (frame.type) {
                        Framing.TYPE_ENVELOPE -> {
                            val (seq, json) = Framing.decodeEnvelope(frame.payload) ?: break@loop
                            try { listener.onEnvelope(link.publicKey, json) } catch (e: Exception) {
                                Log.w(TAG, "envelope handler threw: ${e.message}")
                            }
                            link.outbox.trySend(Framing.Frame(Framing.TYPE_ACK, Framing.encodeSeq(seq)))
                        }
                        Framing.TYPE_ACK -> Framing.decodeSeq(frame.payload)?.let { link.unacked.remove(it) }
                        Framing.TYPE_PING -> Unit
                        Framing.TYPE_BYE -> break@loop
                        else -> break@loop // IDENT / PROOF after authentication
                    }
                }
            } finally {
                writer.cancel()
                pinger.cancel()
            }
        } catch (e: Exception) {
            Log.d(TAG, "link ended: ${e.message}")
        } finally {
            closeLink(link, "ended")
        }
    }

    private fun closeLink(link: Link, why: String) {
        synchronized(link) {
            if (link.closed && links[link.publicKey] !== link) return
            link.closed = true
        }
        link.outbox.close()
        try { link.socket.close() } catch (_: Exception) {}
        if (link.publicKey.isNotEmpty() && links.remove(link.publicKey, link)) {
            _peerKeys.value = links.keys.toSet()
            val unacked = link.unacked.entries.sortedBy { it.key }.map { it.value }
            link.unacked.clear()
            Log.d(TAG, "peer ${link.publicKey.take(12)}… gone ($why), ${unacked.size} unacknowledged")
            try { listener.onPeerDisconnected(link.publicKey, unacked) } catch (_: Exception) {}
        }
        // Registered or not (a failed handshake), the data path under it goes too.
        link.path?.let { p -> handler.post { dropPath(p) } }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
