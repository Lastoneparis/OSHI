package com.oshi.messenger.service.p2p

import com.oshi.messenger.service.diag.CallDiag
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ICE2 — multi-pair kept-alive transport with fast failover.
 *
 * The previous transport ran hole-punch pings at 5 Hz until the first pong
 * won, then demoted to a 3 s keepalive on the single "active" pair. If that
 * pair silently rotted (carrier change, NAT rebinding, relay degrading), the
 * 10 s stale-inbound watchdog noticed way too late and the user heard cuts.
 *
 * ICE2 keeps EVERY candidate pair alive:
 *  - 2 Hz STUN-style ping to every remote candidate on every tick (detects
 *    mid-call degradation twice as fast as the original 1 Hz cadence;
 *    overhead is negligible — tiny packets).
 *  - Each pair tracks EWMA RTT (α = 0.125) and success rate over last 10 probes.
 *  - score = (1 / (RTT_ms + 1)) × successRate, with a 1.5× bias applied to
 *    direct (host/srflx) pairs so TURN-relay never wins on a tie. Highest
 *    effective score among live pairs is the active one.
 *  - Failover: if the active pair's successRate < 0.5 for ≥ 2 s OR EWMA RTT
 *    > 300 ms OR 3 consecutive probe misses, AND a different live pair's
 *    effective score exceeds 1.2× the active pair's effective score
 *    (hysteresis, prevents flapping), switch immediately.
 *  - 200 ms dual-send window after a switch: [sendPacket] writes to the new
 *    pair AND the old pair so the peer's jitter buffer sees an overlap rather
 *    than a gap.
 *  - TURN relay pair is kept probed as a permanent fallback even when direct
 *    pairs are healthy.
 *
 * Wire format, session-key handshake, AES-GCM, and the 0x01 candidate wire
 * version are untouched — the rewrite is internal-only. Public API matches
 * the previous shape exactly (start/stop/restart/addRemoteCandidates/
 * sendPacket/encodeCandidates/decodeCandidates plus state + the three on*
 * callbacks).
 */
class P2PTransport(private val parentScope: CoroutineScope) {

    companion object {
        private const val TAG = "P2PTransport"
        private const val LOG_PREFIX = "🧭 P2P:"

        const val PACKET_TYPE_ICE_EXCHANGE: Byte = 0x30
        const val PACKET_TYPE_HOLEPUNCH_PING: Byte = 0x31
        const val PACKET_TYPE_HOLEPUNCH_PONG: Byte = 0x32

        const val CANDIDATE_WIRE_VERSION: Byte = 0x01

        // ICE2 tunables.
        private const val PROBE_INTERVAL_MS = 500L       // 2 Hz keepalive (was 1 Hz)
        private const val FAILOVER_TICK_MS = 250L
        private const val SUCCESS_WINDOW = 10
        private const val EWMA_ALPHA = 0.125
        private const val RTT_FAILOVER_MS = 300.0
        private const val LOSS_SUSTAINED_MS = 2_000L
        private const val PROBE_FAIL_THRESHOLD = 3
        private const val PROBE_INFLIGHT_TIMEOUT_MS = 2_000L
        private const val LIVENESS_MAX_AGE_MS = 5_000L
        /** Hysteresis: only switch when new_score > SWITCH_HYSTERESIS × current_score. */
        private const val SWITCH_HYSTERESIS = 1.2
        /**
         * Direct-P2P bias: host/srflx pair scores are multiplied by this before
         * being compared to relay pairs. TURN adds a relay hop that is never as
         * good as direct, so equal nominal scores should prefer direct.
         */
        private const val DIRECT_BIAS = 1.5

        private const val STUN_TIMEOUT_MS = 2_000L
        private const val RECV_BUFFER_SIZE = 2048
        private const val DIRECT_PROBE_TIMEOUT_MS = 5_000L

        private const val TURN_HOST = "45.67.216.197"
        private const val TURN_PORT = 3478
        // TURN credentials supplied via BuildConfig from keystore.properties
        // (gitignored) so they're never committed in plaintext.
        private val TURN_USERNAME: String = com.oshi.messenger.BuildConfig.TURN_USERNAME
        private val TURN_PASSWORD: String = com.oshi.messenger.BuildConfig.TURN_PASSWORD
        private const val CHANNEL_NUMBER_BASE = 0x4000

        private const val DUAL_SEND_WINDOW_MS = 200L

        private fun defaultPriority(type: CandidateType, family: Int): Int = when (type) {
            CandidateType.HOST  -> if (family == 4) 126 shl 24 else 125 shl 24
            CandidateType.SRFLX -> if (family == 4) 100 shl 24 else  99 shl 24
            CandidateType.RELAY -> if (family == 4)   0 shl 24 else  -1
        }

        /**
         * Interface-name prefixes that carry a VPN / tunnel on Android.
         * `tun` covers VpnService's own device, `ppp` legacy PPTP/L2TP,
         * `ipsec` the IKEv2 built-in client, `wg` a WireGuard kernel device.
         */
        private val TUNNEL_IF_PREFIXES = listOf("tun", "ppp", "ipsec", "wg")

        fun isTunnelInterface(name: String): Boolean =
            TUNNEL_IF_PREFIXES.any { name.startsWith(it, ignoreCase = true) }

        /**
         * Which local addresses may be advertised to the peer as host candidates.
         *
         * WHY: while a VpnService is up, `NetworkInterface.getNetworkInterfaces()`
         * still hands the app the underlying wlan0/rmnet address alongside tun0 —
         * Android does not hide the physical link from apps. Advertising it gives
         * the peer the real LAN IP (and, when the VPN does not capture v6, a
         * globally routable IPv6) of someone who turned a VPN on precisely so that
         * would not happen. No Android bug is needed for this: it is our own ICE
         * gathering. So as soon as a tunnel carries a routable address, ONLY
         * tunnel addresses are advertised. Direct hole-punch over the tunnel
         * usually fails and the pair falls back to TURN — that is the intended
         * trade, and it is what the user asked for by enabling the VPN.
         */
        fun hostAddressesSafeToAdvertise(all: List<LocalAddress>): List<LocalAddress> {
            // 1. Never advertise a globally routable address. This is the load-bearing
            //    rule, and interface names cannot carry it: a real iPhone keeps ~15
            //    ipsec/utun interfaces up for Wi-Fi Calling and IMS even with no VPN,
            //    and iOS ALIASES the carrier's global IPv6 onto them — measured on an
            //    iPhone 17 Pro Max, pdp_ip3 and ipsec5 and ipsec9 all carried the same
            //    2a04:cec0:… address, so a name-based filter happily re-advertised it.
            //    A host candidate only ever helps a peer on the same link, so a global
            //    address buys no connectivity there while being the single most
            //    identifying thing we could hand over. Off-link reachability is what
            //    srflx and relay are for.
            val private = all.filterNot { isGloballyRoutable(it.ip, it.family) }
            // 2. With a tunnel up, keep only tunnel addresses, so the LAN address of
            //    someone on a VPN does not reach the peer (TunnelCrack's LocalNet
            //    shape). Direct hole-punch over the tunnel usually fails and the pair
            //    falls back to TURN — the intended trade.
            val tunnels = private.filter { isTunnelInterface(it.ifName) }
            val pool = if (tunnels.isEmpty()) private else tunnels
            // 3. The same address is aliased onto several interfaces; advertise once.
            return pool.distinctBy { it.ip }
        }

        /**
         * Is this address reachable from the public Internet — i.e. does handing it
         * to the peer identify the user's ISP, carrier or household?
         *
         * IPv4: everything outside the private/CGNAT/link-local/loopback blocks and
         * 192.0.0.0/24 (IETF protocol assignments — 464XLAT's 192.0.0.4/.6 live there
         * and appear on every iPhone).
         * IPv6: global unicast is 2000::/3. ULA (fc00::/7) and link-local are not.
         */
        fun isGloballyRoutable(ip: String, family: Int): Boolean {
            if (family == 6) {
                val head = ip.substringBefore(':').ifEmpty { return false }
                val group = head.toIntOrNull(16) ?: return false
                return (group and 0xE000) == 0x2000
            }
            val o = ip.split('.').mapNotNull { it.toIntOrNull() }
            if (o.size != 4) return false
            return when {
                o[0] == 10 -> false
                o[0] == 127 -> false
                o[0] == 172 && o[1] in 16..31 -> false
                o[0] == 192 && o[1] == 168 -> false
                o[0] == 192 && o[1] == 0 && o[2] == 0 -> false
                o[0] == 169 && o[1] == 254 -> false
                o[0] == 100 && o[1] in 64..127 -> false
                else -> true
            }
        }

        /**
         * What we are willing to hand the peer.
         *
         * WHY: "Always use relay server" (force-relay) used to change only which
         * pair carried media — gathering still emitted host + srflx candidates to
         * the peer, so the peer learned our addresses anyway and the toggle did
         * not do what its label promises. Under force-relay the peer gets the
         * relay candidate and nothing else.
         */
        fun publishableCandidates(
            all: List<P2PCandidate>,
            forceRelay: Boolean
        ): List<P2PCandidate> =
            if (forceRelay) all.filter { it.type == CandidateType.RELAY } else all
    }

    // ------------------------------------------------------------------
    // Public types (unchanged wire API)
    // ------------------------------------------------------------------

    enum class CandidateType(val wire: Byte) {
        HOST(0),
        SRFLX(1),
        RELAY(2);

        companion object {
            fun fromWire(b: Byte): CandidateType? = when (b.toInt()) {
                0 -> HOST
                1 -> SRFLX
                2 -> RELAY
                else -> null
            }
        }
    }

    /** One interface address, reduced to what candidate selection needs. */
    data class LocalAddress(val ifName: String, val ip: String, val family: Int)

    data class P2PCandidate(
        val type: CandidateType,
        val ip: String,
        val port: Int,
        val priority: Int
    ) {
        val family: Int
            get() = if (ip.contains(':')) 6 else 4
    }

    enum class State { IDLE, GATHERING, SIGNALING, PROBING, CONNECTED, RELAY, FAILED }

    // ------------------------------------------------------------------
    // Public callbacks (unchanged)
    // ------------------------------------------------------------------

    @Volatile var onLocalCandidatesReady: ((List<P2PCandidate>) -> Unit)? = null
    @Volatile var onPacketReceived: ((ByteArray) -> Unit)? = null
    @Volatile var onStateChanged: ((State) -> Unit)? = null
    @Volatile var onSelectedPair: ((local: P2PCandidate?, remote: P2PCandidate, rttMs: Long) -> Unit)? = null

    // ------------------------------------------------------------------
    // Public state (unchanged)
    // ------------------------------------------------------------------

    @Volatile var state: State = State.IDLE
        private set(value) {
            val changed = field != value
            field = value
            if (changed) onStateChanged?.invoke(value)
        }

    @Volatile var selectedRemote: P2PCandidate? = null
        private set

    @Volatile var localCandidates: List<P2PCandidate> = emptyList()
        private set

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + Dispatchers.IO + job)

    private val stunClient = StunClient()
    private val remoteCandidates: MutableSet<P2PCandidate> = ConcurrentHashMap.newKeySet()
    private val pairs = ConcurrentHashMap<String, PairState>()
    /** nonceHex → (sentMs, pairKey). Tracks outstanding probes for RTT + loss counting. */
    private val pingSendTimes = ConcurrentHashMap<String, Pair<Long, String>>()

    /**
     * Wall-clock ms of the last pong that answered one of OUR pings (nonce matched), on
     * any pair, direct or relayed. 0 = none this session. Liveness proof for a peer that
     * sends no media (muted) — see `PeerSilencePolicy.silenceMs`. Not a media-path
     * health signal: `EnhancedCallManager.p2pCanCarryAudio` deliberately ignores pongs.
     */
    @Volatile var lastCorrelatedPongAtMs: Long = 0L
        private set
    private val started = AtomicBoolean(false)

    /**
     * __TWO_MODE_CALL_PATH_2026_09_23__ STUN transactions sent FROM the media socket, keyed by
     * transaction-id hex. The srflx used to come from `StunClient.discoverReflexiveAddress()`,
     * which opens a THROWAWAY socket: on a port-randomising carrier NAT (every 5G CGNAT) the
     * advertised srflx port was never the media port, so the peer punched at a closed hole.
     * Measured B37DC742: coturn saw Android's Binding requests from 194.230.146.101:62852,
     * :62859, :63305 — three sockets, none of them the media socket. iOS fixed the same bug
     * on 2026-07-26 (`sendStunBindingFromMediaSocket`).
     */
    private val pendingStunTx = ConcurrentHashMap<String, Pair<ByteArray, CompletableDeferred<StunClient.ReflexiveAddress?>>>()

    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var probeJob: Job? = null
    private var failoverJob: Job? = null
    private var relayWatchdogJob: Job? = null

    @Volatile private var localPort: Int = 0

    /** Key ("ip:port") of the currently active pair. */
    @Volatile private var activeKey: String? = null

    /**
     * During a failover, the previous active pair stays warm for
     * [DUAL_SEND_WINDOW_MS] so [sendPacket] can write to both pairs and the
     * receiver's jitter buffer sees an overlap instead of a gap.
     */
    @Volatile private var previousActive: P2PCandidate? = null
    @Volatile private var dualSendUntilMs: Long = 0L

    /** Clock at which the active pair's successRate first dropped below 0.5. */
    @Volatile private var activeLossStartedAtMs: Long = 0L
    @Volatile private var activeConsecutiveMisses: Int = 0

    // TURN state
    @Volatile private var turnClient: TurnClient? = null
    @Volatile private var forceRelay: Boolean = false
    private val relayChannels = ConcurrentHashMap<String, Int>()
    private val nextChannel = java.util.concurrent.atomic.AtomicInteger(CHANNEL_NUMBER_BASE)
    @Volatile private var relayAllocationAttempted: Boolean = false

    /**
     * Optional per-call identifier embedded as bytes [1..9] (big-endian) of every
     * outbound ping. iOS's P2PTransport validates this against its own derived
     * call_id when deciding whether to answer an incoming ping. An unset (0L)
     * value is treated as a wildcard by iOS — the current default. Callers may
     * opt in via [setCallId] but no edit to EnhancedCallManager is required:
     * the wildcard path on iOS is what unblocks Android→iOS probing today.
     */
    @Volatile private var callId: Long = 0L

    /** Pairs we've already emitted DIAG_ICE_PING_TX / DIAG_ICE_PONG_RX for. */
    private val loggedPingTxPairs = ConcurrentHashMap.newKeySet<String>()
    private val loggedPongRxPairs = ConcurrentHashMap.newKeySet<String>()
    private val loggedPingRxSources = ConcurrentHashMap.newKeySet<String>()

    /** Tick counter for the periodic "still PROBING" diagnostic. */
    @Volatile private var probeTickCount: Long = 0L

    // ------------------------------------------------------------------
    // Lifecycle (public API unchanged)
    // ------------------------------------------------------------------

    /**
     * Optionally set the per-call id embedded in every outbound ping. Must be
     * invoked before [start] to affect the first probe round. Not required for
     * interop with iOS — iOS treats call_id=0 as a wildcard — but setting a
     * non-zero value enables strict cross-call isolation on the iOS side and
     * shows up in iOS's DIAG_ICE_PING_RX as `cidMatch=exact`.
     */
    fun setCallId(id: Long) {
        this.callId = id
    }

    fun start(forceRelay: Boolean = false) {
        if (!started.compareAndSet(false, true)) {
            Log.d(TAG, "$LOG_PREFIX start() ignored — already started")
            return
        }
        this.forceRelay = forceRelay
        state = State.GATHERING

        scope.launch {
            try {
                bindSocket()
                launchReceiveLoop()
                gatherAndEmitCandidates()
                state = State.SIGNALING
                // Eager TURN allocation — ONLY when coturn is enabled as a media path
                // (it is not: see P2PPathPolicy). Two modes: direct, else VPS relay.
                if (P2PPathPolicy.turnRelayMediaEnabled) {
                    scope.launch { allocateTurnAndEmitRelayCandidate() }
                }
                armRelayWatchdog()
            } catch (e: Exception) {
                Log.w(TAG, "$LOG_PREFIX start failed: ${e.message}")
                state = State.FAILED
            }
        }
    }

    fun addRemoteCandidate(candidate: P2PCandidate) {
        // __TWO_MODE_CALL_PATH_2026_09_23__ a peer's coturn relay address is not a path we
        // use: probing it only produced a TURN half-state (see P2PPathPolicy).
        if (!P2PPathPolicy.acceptsRemoteCandidate(candidate.type)) {
            Log.d(TAG, "$LOG_PREFIX ignoring remote relay candidate ${candidate.ip}:${candidate.port} (TURN not a media path)")
            return
        }
        val added = remoteCandidates.add(candidate)
        if (added) {
            Log.d(TAG, "$LOG_PREFIX + remote ${candidate.type} ${candidate.ip}:${candidate.port}")
        }
        val key = relayKey(candidate.ip, candidate.port)
        pairs.putIfAbsent(key, PairState(candidate, SUCCESS_WINDOW))

        if (candidate.type == CandidateType.RELAY) {
            scope.launch { bindTurnChannelFor(candidate) }
        }

        // Kick off probing as soon as we have at least one remote candidate. FAILED is
        // included: FAILED only means "the VPS relay carries the call"; candidates that land
        // late (slow signalling) must still be able to upgrade the call to direct P2P.
        if (state == State.SIGNALING || state == State.PROBING || state == State.FAILED) {
            ensureProbeLoops()
        }
    }

    fun addRemoteCandidates(candidates: List<P2PCandidate>) {
        candidates.forEach { addRemoteCandidate(it) }
    }

    /**
     * Send an application packet to the currently active pair. During the
     * 200 ms post-switch window, also writes to the previous active pair so
     * the peer's jitter buffer sees overlap rather than a gap.
     */
    /**
     * __RELAY_FIRST_UPGRADE_2026_09_23__ media-sized probe the call manager wants (read each
     * probe tick). null ⇒ [P2PPathPolicy.JUMBO_PROBE_BYTES].
     */
    @Volatile var mediaProbeBytesProvider: (() -> Int)? = null
    private val currentProbeBytes: Int
        get() = maxOf(P2PPathPolicy.PING_BYTES + 1, mediaProbeBytesProvider?.invoke() ?: P2PPathPolicy.JUMBO_PROBE_BYTES)

    @Volatile private var consentAtMs: Long = 0L
    @Volatile private var consentRttMs: Long = 0L

    /** True while the ACTIVE pair ponged within ~1 s (+RTT). Thread-safe. */
    val activePairConsentFresh: Boolean
        get() = P2PPathPolicy.consentFresh(consentAtMs, consentRttMs, System.currentTimeMillis())

    fun sendPacket(packet: ByteArray) {
        val remote = selectedRemote ?: return
        sendToPair(packet, remote)

        // Dual-send window: mirror the packet onto the old pair too.
        val prev = previousActive
        val until = dualSendUntilMs
        if (prev != null && until > 0L) {
            if (System.currentTimeMillis() < until) {
                sendToPair(packet, prev)
            } else {
                previousActive = null
                dualSendUntilMs = 0L
            }
        }
    }

    fun stop() {
        if (!started.getAndSet(false)) return
        try { probeJob?.cancel() } catch (_: Exception) {}
        try { failoverJob?.cancel() } catch (_: Exception) {}
        try { relayWatchdogJob?.cancel() } catch (_: Exception) {}
        try { receiveJob?.cancel() } catch (_: Exception) {}
        probeJob = null
        failoverJob = null
        relayWatchdogJob = null
        receiveJob = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { turnClient?.close() } catch (_: Exception) {}
        turnClient = null
        relayChannels.clear()
        nextChannel.set(CHANNEL_NUMBER_BASE)
        relayAllocationAttempted = false
        remoteCandidates.clear()
        pairs.clear()
        pingSendTimes.clear()
        selectedRemote = null
        activeKey = null
        previousActive = null
        dualSendUntilMs = 0L
        activeLossStartedAtMs = 0L
        activeConsecutiveMisses = 0
        localCandidates = emptyList()
        localPort = 0
        forceRelay = false
        callId = 0L
        loggedPingTxPairs.clear()
        loggedPongRxPairs.clear()
        loggedPingRxSources.clear()
        probeTickCount = 0L
        lastCorrelatedPongAtMs = 0L
        state = State.IDLE
    }

    fun restart() {
        if (!started.get()) {
            start()
            return
        }
        Log.d(TAG, "$LOG_PREFIX network change → ICE restart")
        try { probeJob?.cancel() } catch (_: Exception) {}
        try { failoverJob?.cancel() } catch (_: Exception) {}
        try { relayWatchdogJob?.cancel() } catch (_: Exception) {}
        probeJob = null
        failoverJob = null
        relayWatchdogJob = null
        selectedRemote = null
        activeKey = null
        previousActive = null
        dualSendUntilMs = 0L
        activeLossStartedAtMs = 0L
        activeConsecutiveMisses = 0
        pairs.clear()
        pingSendTimes.clear()
        try { turnClient?.close() } catch (_: Exception) {}
        turnClient = null
        relayChannels.clear()
        nextChannel.set(CHANNEL_NUMBER_BASE)
        relayAllocationAttempted = false
        loggedPingTxPairs.clear()
        loggedPongRxPairs.clear()
        loggedPingRxSources.clear()
        probeTickCount = 0L
        state = State.GATHERING

        scope.launch {
            try {
                if (socket == null || socket?.isClosed == true) {
                    bindSocket()
                    launchReceiveLoop()
                }
                gatherAndEmitCandidates()
                // Re-seed per-pair state from the remote candidates we already have.
                for (c in remoteCandidates) {
                    pairs.putIfAbsent(relayKey(c.ip, c.port), PairState(c, SUCCESS_WINDOW))
                }
                if (remoteCandidates.isNotEmpty()) {
                    state = State.SIGNALING
                    ensureProbeLoops()
                }
                armRelayWatchdog()
            } catch (e: Exception) {
                Log.w(TAG, "$LOG_PREFIX restart failed: ${e.message}")
                state = State.FAILED
            }
        }
    }

    // ------------------------------------------------------------------
    // Socket / gathering
    // ------------------------------------------------------------------

    private fun bindSocket() {
        val s = DatagramSocket(0).apply {
            soTimeout = 0
            reuseAddress = true
        }
        socket = s
        localPort = s.localPort
        Log.d(TAG, "$LOG_PREFIX socket bound local port=$localPort")
    }

    private fun launchReceiveLoop() {
        receiveJob = scope.launch {
            val s = socket ?: return@launch
            val buf = ByteArray(RECV_BUFFER_SIZE)
            val pkt = DatagramPacket(buf, buf.size)
            while (isActive && !s.isClosed) {
                try {
                    s.receive(pkt)
                } catch (e: java.net.SocketException) {
                    break
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "$LOG_PREFIX receive error: ${e.message}")
                    continue
                }
                val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                if (data.isEmpty()) continue
                dispatchInbound(data, pkt.address, pkt.port)
            }
        }
    }

    private fun dispatchInbound(data: ByteArray, fromAddr: InetAddress, fromPort: Int) {
        // STUN Binding Success for a request WE sent from this socket (srflx discovery).
        if (pendingStunTx.isNotEmpty() && P2PPathPolicy.isStunBindingSuccess(data)) {
            val tx = P2PPathPolicy.stunTransactionHex(data)
            val pending = if (tx != null) pendingStunTx[tx] else null
            if (pending != null) {
                pending.second.complete(stunClient.parseBindingResponse(data, data.size, pending.first))
                return
            }
        }
        when (data[0]) {
            PACKET_TYPE_HOLEPUNCH_PING -> handlePingReceived(data, fromAddr, fromPort)
            PACKET_TYPE_HOLEPUNCH_PONG -> handlePongReceived(data, fromAddr, fromPort)
            else -> onPacketReceived?.invoke(data)
        }
    }

    private fun handlePingReceived(ping: ByteArray, fromAddr: InetAddress, fromPort: Int) {
        if (ping.isEmpty()) return
        val hostAddr = fromAddr.hostAddress ?: return
        val pong = P2PPathPolicy.pongFor(ping) ?: return
        sendRaw(pong, hostAddr, fromPort)
        val srcKey = "$hostAddr:$fromPort"
        // RFC 5245 PEER-REFLEXIVE candidate (PRFLX). When a ping arrives from
        // a source IP:port we never explicitly signaled, the peer's NAT has
        // mapped its outbound traffic to a different external address than
        // the SRFLX they advertised. This happens on symmetric / port-randomising
        // mobile NATs (and is the EXACT failure mode for iPhone↔Android calls
        // here: iPhone's NAT issues a fresh ext_port per send, and Android's
        // configured remote = stale SRFLX → audio never reaches iPhone).
        // Add the observed source as a candidate, create its pair state, kick
        // probing. The selection algorithm then learns it's actually reachable
        // and promotes it as active. Without this, Android keeps blasting audio
        // at the dead SRFLX port forever.
        ensureRemoteCandidateForObservedSource(hostAddr, fromPort)
        if (loggedPingRxSources.add(srcKey)) {
            var peerCid = 0L
            if (ping.size >= 9) {
                for (i in 0 until 8) {
                    peerCid = (peerCid shl 8) or (ping[1 + i].toLong() and 0xFF)
                }
            }
            CallDiag.w(TAG, "DIAG_ICE_PING_RX | from=$srcKey | peerCid=${"%016x".format(peerCid)}")
        }
    }

    /**
     * Ensure we have a candidate + pair state for an observed source IP:port.
     * Idempotent — only the first ping/pong from a new (ip, port) actually
     * mutates state. Treats the source as a SRFLX peer-reflexive candidate
     * (we don't know the type for sure, but SRFLX gives it the right priority
     * tier and lets our scoring pick it once it goes live).
     */
    private fun ensureRemoteCandidateForObservedSource(ip: String, port: Int) {
        val matches = remoteCandidates.any { it.ip == ip && it.port == port }
        if (matches) return
        val cand = P2PCandidate(
            type = CandidateType.SRFLX,
            ip = ip,
            port = port,
            priority = defaultPriority(CandidateType.SRFLX, if (ip.contains(':')) 6 else 4)
        )
        remoteCandidates.add(cand)
        val key = relayKey(cand.ip, cand.port)
        pairs.putIfAbsent(key, PairState(cand, SUCCESS_WINDOW))
        if (state == State.SIGNALING || state == State.PROBING || state == State.FAILED) {
            ensureProbeLoops()
        }
        Log.w(TAG, "$LOG_PREFIX peer-reflexive candidate added: $ip:$port")
    }

    private fun handlePongReceived(pong: ByteArray, fromAddr: InetAddress, fromPort: Int) {
        val hostAddr = fromAddr.hostAddress ?: return
        handlePongCommon(pong, hostAddr, fromPort, viaRelay = false)
    }

    /**
     * Shared pong handler — updates per-pair RTT + success rate, promotes the
     * best live pair to active if we don't have one yet, and resets the
     * consecutive-miss counter on the active pair.
     */
    private fun handlePongCommon(pong: ByteArray, hostAddr: String, fromPort: Int, viaRelay: Boolean) {
        // Same peer-reflexive logic as handlePingReceived: a pong arriving
        // from a source we didn't signal means the peer's NAT mapping is
        // different from the candidate they advertised. Add the observed
        // source so we can score and select this newly-confirmed live path.
        if (!viaRelay) {
            ensureRemoteCandidateForObservedSource(hostAddr, fromPort)
        }
        val existing = remoteCandidates.firstOrNull { it.ip == hostAddr && it.port == fromPort }
        val match = existing ?: P2PCandidate(
            if (viaRelay) CandidateType.RELAY else CandidateType.SRFLX,
            hostAddr, fromPort, 0
        )
        val key = relayKey(match.ip, match.port)
        val pair = pairs.getOrPut(key) { PairState(match, SUCCESS_WINDOW) }

        // RTT: prefer our own sent-time recorded at ping time (via nonceHex)
        // since that's monotonic even if the peer's clock drifts.
        var rttMs = -1L
        if (pong.size >= 29) {
            val nonceHex = buildString {
                for (i in 0 until 12) {
                    append("%02x".format(pong[9 + i]))
                }
            }
            val sent = pingSendTimes.remove(nonceHex)
            if (sent != null) {
                // Echo of a nonce only OUR ping carried ⇒ the peer's transport is alive.
                lastCorrelatedPongAtMs = System.currentTimeMillis()
                rttMs = System.currentTimeMillis() - sent.first
            } else {
                // Fallback: the ts_ms field the sender embedded — same clock
                // since both sides are the same device here after a pong.
                var ts = 0L
                for (i in 0 until 8) {
                    ts = (ts shl 8) or (pong[21 + i].toLong() and 0xFF)
                }
                if (ts > 0) rttMs = System.currentTimeMillis() - ts
            }
        }
        if (rttMs < 0) rttMs = 0

        pair.recordHit(rttMs.toDouble(), EWMA_ALPHA)
        if (pong.size >= currentProbeBytes) {
            val firstJumbo = pair.lastJumboPongAtMs == 0L
            pair.lastJumboPongAtMs = System.currentTimeMillis()
            if (firstJumbo) CallDiag.w(TAG, "DIAG_ICE_MEDIA_VERIFIED | pair=$hostAddr:$fromPort | bytes=${pong.size} | rttMs=$rttMs")
        }

        if (loggedPongRxPairs.add(key)) {
            CallDiag.w(TAG, "DIAG_ICE_PONG_RX | pair=$hostAddr:$fromPort | via=${if (viaRelay) "relay" else "direct"} | rttMs=$rttMs")
        }

        if (key == activeKey) {
            activeConsecutiveMisses = 0
            consentRttMs = pair.ewmaRttMs.toLong().coerceAtLeast(0L)
            consentAtMs = System.currentTimeMillis()
        }

        // First successful pong across all pairs → promote the best live pair.
        if (activeKey == null) {
            val (bestKey, bestPair) = bestLivePair() ?: return
            promoteActive(
                bestPair.candidate,
                bestPair.ewmaRttMs.toLong().coerceAtLeast(0L),
                "probe_fail",
                oldScore = 0.0,
                newScore = effectiveScore(bestPair)
            )
        }
    }

    // ------------------------------------------------------------------
    // STUN + candidate gathering (unchanged)
    // ------------------------------------------------------------------

    private suspend fun gatherAndEmitCandidates() {
        val t0 = System.currentTimeMillis()

        val hostCands = hostCandidates()

        val (srflxV4, srflxV6) = withContext(Dispatchers.IO) {
            val v4Deferred = async {
                // __TWO_MODE_CALL_PATH_2026_09_23__ ON THE MEDIA SOCKET (see pendingStunTx).
                // No fallback to a throwaway socket: a srflx for the wrong port is worse
                // than none (peer-reflexive learning still finds the real mapping).
                withTimeoutOrNull(STUN_TIMEOUT_MS) { discoverSrflxOnMediaSocket() }
            }
            val v6Deferred = async {
                withTimeoutOrNull(STUN_TIMEOUT_MS) { null as StunClient.ReflexiveAddress? }
            }
            val results = awaitAll(v4Deferred, v6Deferred)
            results[0] to results[1]
        }

        val srflxCands = mutableListOf<P2PCandidate>()
        srflxV4?.let {
            srflxCands += P2PCandidate(
                CandidateType.SRFLX, it.ip, it.port,
                defaultPriority(CandidateType.SRFLX, 4)
            )
        }
        srflxV6?.let {
            srflxCands += P2PCandidate(
                CandidateType.SRFLX, it.ip, it.port,
                defaultPriority(CandidateType.SRFLX, 6)
            )
        }

        val all = (hostCands + srflxCands).sortedByDescending { it.priority }
        localCandidates = all

        val dt = System.currentTimeMillis() - t0
        val v4Label = srflxV4?.let { "${it.ip}:${it.port}" } ?: "-"
        val v6Label = srflxV6?.let { "[${it.ip}]:${it.port}" } ?: "-"
        Log.d(TAG, "$LOG_PREFIX srflx v4=$v4Label v6=$v6Label (gathered in ${dt}ms)")

        emitLocalCandidates(all)
    }

    /**
     * Single gate between what we gathered and what the peer is told. Every
     * emission goes through here so no future call site can bypass the
     * force-relay policy.
     */
    private fun emitLocalCandidates(all: List<P2PCandidate>) {
        val publishable = publishableCandidates(all, forceRelay)
        if (publishable.size != all.size) {
            Log.d(TAG, "$LOG_PREFIX forceRelay — withholding ${all.size - publishable.size} non-relay candidate(s) from peer")
        }
        if (publishable.isEmpty()) return
        onLocalCandidatesReady?.invoke(publishable)
    }

    private fun hostCandidates(): List<P2PCandidate> {
        val port = localPort
        if (port == 0) return emptyList()
        val addrs = mutableListOf<LocalAddress>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in ifs) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val ip = addr.hostAddress ?: continue
                    addrs += LocalAddress(
                        ifName = nif.name ?: "",
                        ip = ip.substringBefore('%'),
                        family = if (addr is Inet6Address) 6 else 4
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$LOG_PREFIX enumerate interfaces failed: ${e.message}")
        }
        val safe = hostAddressesSafeToAdvertise(addrs)
        if (safe.size != addrs.size) {
            Log.d(TAG, "$LOG_PREFIX tunnel up — withholding ${addrs.size - safe.size} physical host candidate(s)")
        }
        return safe.map {
            P2PCandidate(
                CandidateType.HOST, it.ip, port,
                defaultPriority(CandidateType.HOST, it.family)
            )
        }
    }

    // ------------------------------------------------------------------
    // ICE2 probing + failover loops
    // ------------------------------------------------------------------

    /** Lazily start the 1 Hz probe loop and the 250 ms failover ticker. */
    private fun ensureProbeLoops() {
        if (probeJob?.isActive != true) {
            state = State.PROBING
            probeJob = scope.launch {
                while (isActive) {
                    probeTick()
                    delay(PROBE_INTERVAL_MS)
                }
            }
        }
        if (failoverJob?.isActive != true) {
            failoverJob = scope.launch {
                while (isActive) {
                    delay(FAILOVER_TICK_MS)
                    failoverTick()
                }
            }
        }
    }

    /**
     * Fire one STUN-binding-style ping to EVERY remote candidate (direct and
     * relay). Any ping that was outstanding >2 s with no pong is counted as a
     * loss on its pair.
     */
    private fun probeTick() {
        val now = System.currentTimeMillis()

        // 1. Expire stale in-flight probes (no pong within 2 s → loss).
        val staleCutoff = now - PROBE_INFLIGHT_TIMEOUT_MS
        val toDrop = ArrayList<String>()
        for ((nonceHex, tsKey) in pingSendTimes) {
            if (tsKey.first < staleCutoff) {
                toDrop += nonceHex
                val pair = pairs[tsKey.second]
                if (pair != null) {
                    pair.recordMiss()
                    if (tsKey.second == activeKey) {
                        activeConsecutiveMisses++
                    }
                }
            }
        }
        for (k in toDrop) pingSendTimes.remove(k)

        // 2. Send a fresh ping to every remote candidate.
        val remotes = remoteCandidates.toList()
        for (cand in remotes) {
            if (!P2PPathPolicy.acceptsRemoteCandidate(cand.type)) continue
            val key = relayKey(cand.ip, cand.port)
            pairs.putIfAbsent(key, PairState(cand, SUCCESS_WINDOW))
            val ps = pairs[key]
            val jumbo = ps != null && P2PPathPolicy.shouldSendJumbo(
                ps.isLive, P2PPathPolicy.mediaVerified(ps.lastJumboPongAtMs, now), probeTickCount)
            val (ping, nonceHex) = buildPing(if (jumbo) currentProbeBytes else P2PPathPolicy.PING_BYTES)
            pingSendTimes[nonceHex] = now to key
            if (cand.type == CandidateType.RELAY) {
                val ch = relayChannels[key]
                val turn = turnClient
                if (ch != null && turn != null) {
                    turn.send(ping, viaChannel = ch)
                }
            } else {
                sendRaw(ping, cand.ip, cand.port)
            }
            if (loggedPingTxPairs.add(key)) {
                val cidHex = "%016x".format(callId)
                CallDiag.w(TAG, "DIAG_ICE_PING_TX | pair=${cand.ip}:${cand.port} | type=${cand.type} | cid=$cidHex | nonce=${nonceHex.take(8)}…")
            }
        }

        // 3. Every ~4 s while still stuck in PROBING, emit a summary so the
        // next call log shows *which* pairs have heard nothing back.
        probeTickCount++
        if (state == State.PROBING && probeTickCount % 8L == 0L) {
            val summary = pairs.values.joinToString(",") { p ->
                val live = if (p.isLive) "live" else "dead"
                val rtt = if (p.ewmaRttMs >= 0) "${p.ewmaRttMs.toInt()}ms" else "-"
                "${p.candidate.ip}:${p.candidate.port}[${p.candidate.type}/$live/$rtt/sr=${"%.2f".format(p.successRate)}]"
            }
            CallDiag.w(TAG, "DIAG_ICE_STILL_PROBING | tick=$probeTickCount | pairs=${pairs.size} | inflight=${pingSendTimes.size} | $summary")
        }
    }

    /**
     * Recompute the best live pair and decide if we need to switch off the
     * current active pair. Switch when any of:
     *   - activeConsecutiveMisses ≥ 3 (reason=probe_fail)
     *   - EWMA RTT > 300 ms (reason=rtt)
     *   - successRate < 0.5 sustained for ≥ 2 s (reason=loss)
     * AND a *different* live pair has a strictly higher score.
     */
    private fun failoverTick() {
        if (pairs.isEmpty()) return
        val now = System.currentTimeMillis()

        // Refresh liveness: a pair with no pong in 5 s is not live.
        for (p in pairs.values) {
            p.refreshLiveness(now)
        }

        // __TWO_MODE_CALL_PATH_2026_09_23__ the active pair must keep PROVING it carries a
        // media-sized datagram (VPN switched on mid-call ⇒ fragments dropped, small pongs
        // still fine). If it stops and no other verified pair exists, give the call back to
        // the VPS relay (state PROBING ⇒ p2pReady() false) instead of sending into a hole.
        val activeNow = activeKey?.let { pairs[it] }
        if (activeNow != null && !P2PPathPolicy.pairSelectable(activeNow.isLive, activeNow.lastJumboPongAtMs, now)) {
            val alt = bestLivePair()
            if (alt == null) {
                CallDiag.w(TAG, "DIAG_ICE_DEMOTE | pair=${activeNow.candidate.ip}:${activeNow.candidate.port} | live=${activeNow.isLive} | jumboAgeMs=${if (activeNow.lastJumboPongAtMs > 0) now - activeNow.lastJumboPongAtMs else -1} | → VPS relay")
                consentAtMs = 0L
                selectedRemote = null
                activeKey = null
                previousActive = null
                dualSendUntilMs = 0L
                activeLossStartedAtMs = 0L
                activeConsecutiveMisses = 0
                state = State.PROBING
                return
            }
            // A verified alternative exists: move now, don't wait for loss/RTT triggers
            // (the unverified active pair may still pong small pings perfectly).
            promoteActive(alt.second.candidate, alt.second.ewmaRttMs.toLong().coerceAtLeast(0L),
                "media_unverified", oldScore = effectiveScore(activeNow), newScore = effectiveScore(alt.second))
            return
        }

        val best = bestLivePair() ?: return
        val (bestKey, bestPair) = best

        if (activeKey == null) {
            promoteActive(
                bestPair.candidate,
                bestPair.ewmaRttMs.toLong().coerceAtLeast(0L),
                "probe_fail",
                oldScore = 0.0,
                newScore = effectiveScore(bestPair)
            )
            return
        }

        val activeKeyLocal = activeKey ?: return
        val active = pairs[activeKeyLocal] ?: return

        // Condition A: successRate < 0.5 sustained ≥ 2 s.
        if (active.successRate < 0.5) {
            if (activeLossStartedAtMs == 0L) activeLossStartedAtMs = now
        } else {
            activeLossStartedAtMs = 0L
        }
        val lossSustained = activeLossStartedAtMs != 0L &&
                (now - activeLossStartedAtMs) >= LOSS_SUSTAINED_MS

        // Condition B: RTT EWMA > 300 ms (only meaningful once we've had a hit).
        val rttBad = active.ewmaRttMs > RTT_FAILOVER_MS && active.successRate > 0.0

        // Condition C: 3 consecutive probe misses.
        val probeFail = activeConsecutiveMisses >= PROBE_FAIL_THRESHOLD

        if (lossSustained || rttBad || probeFail) {
            // Hysteresis: only switch when the candidate's *effective* score is
            // strictly better than 1.2× the active pair's effective score.
            // Prevents flapping on marginal RTT jitter.
            val activeEff = effectiveScore(active)
            val bestEff = effectiveScore(bestPair)
            if (bestKey != activeKeyLocal && bestEff > SWITCH_HYSTERESIS * activeEff) {
                val reason = when {
                    probeFail -> "probe_fail"
                    rttBad -> "rtt"
                    else -> "loss"
                }
                promoteActive(bestPair.candidate,
                    bestPair.ewmaRttMs.toLong().coerceAtLeast(0L),
                    reason,
                    oldScore = activeEff,
                    newScore = bestEff)
                return
            }

            // No healthier direct alternative. If TURN hasn't been tried yet
            // and all direct pairs look bad, allocate a relay fallback.
            if (P2PPathPolicy.turnRelayMediaEnabled && turnClient == null && !relayAllocationAttempted
                && pairs.values.count { it.isLive } <= 1) {
                scope.launch { allocateTurnAndEmitRelayCandidate() }
            }
        }
    }

    /**
     * Effective score for comparison: direct (host/srflx) pairs get a 1.5×
     * bias so they win ties against relay pairs (TURN always adds a hop).
     */
    private fun effectiveScore(p: PairState): Double {
        return if (p.candidate.type == CandidateType.RELAY) p.score else p.score * DIRECT_BIAS
    }

    /** Highest-scoring live pair (by *effective* score), or null if none are live yet. */
    private fun bestLivePair(): Pair<String, PairState>? {
        var bestKey: String? = null
        var best: PairState? = null
        var bestEff = Double.NEGATIVE_INFINITY
        val now = System.currentTimeMillis()
        for ((key, p) in pairs) {
            if (!P2PPathPolicy.pairSelectable(p.isLive, p.lastJumboPongAtMs, now)) continue
            if (!P2PPathPolicy.acceptsRemoteCandidate(p.candidate.type)) continue
            val eff = effectiveScore(p)
            if (best == null || eff > bestEff) {
                best = p
                bestKey = key
                bestEff = eff
            }
        }
        return if (bestKey != null && best != null) bestKey to best else null
    }

    /**
     * Switch the active pair. Opens the 200 ms dual-send window so
     * [sendPacket] can mirror packets onto both the old and new pair while
     * the receiver's jitter buffer absorbs the handoff. Emits DIAG_ICE_SWITCH.
     */
    private fun promoteActive(
        newRemote: P2PCandidate,
        rttMs: Long,
        reason: String,
        oldScore: Double = 0.0,
        newScore: Double = 0.0
    ) {
        val oldRemote = selectedRemote
        val newKey = relayKey(newRemote.ip, newRemote.port)
        if (newKey == activeKey) return

        if (oldRemote != null) {
            previousActive = oldRemote
            dualSendUntilMs = System.currentTimeMillis() + DUAL_SEND_WINDOW_MS
        } else {
            previousActive = null
            dualSendUntilMs = 0L
        }

        selectedRemote = newRemote
        activeKey = newKey
        activeLossStartedAtMs = 0L
        activeConsecutiveMisses = 0
        consentRttMs = rttMs.coerceAtLeast(0L)
        consentAtMs = pairs[newKey]?.lastPongAtMs?.takeIf { it > 0L } ?: System.currentTimeMillis()

        state = if (newRemote.type == CandidateType.RELAY) State.RELAY else State.CONNECTED

        val fromDesc = oldRemote?.let { pairDesc(it) } ?: "-"
        val toDesc = pairDesc(newRemote)
        val oldScoreStr = "%.4f".format(oldScore)
        val newScoreStr = "%.4f".format(newScore)
        val line = "DIAG_ICE_SWITCH | from=$fromDesc | to=$toDesc | reason=$reason | oldScore=$oldScoreStr | newScore=$newScoreStr"
        Log.d(TAG, line)

        onSelectedPair?.invoke(null, newRemote, rttMs)

        // Once we've promoted *something*, the relay watchdog's original job
        // (allocate TURN if no direct pair pinned) is done. Cancel it.
        try { relayWatchdogJob?.cancel() } catch (_: Exception) {}
        relayWatchdogJob = null
    }

    private fun pairDesc(remote: P2PCandidate): String {
        val t = when (remote.type) {
            CandidateType.HOST -> "host"
            CandidateType.SRFLX -> "srflx"
            CandidateType.RELAY -> "relay"
        }
        return ":$localPort→${remote.ip}:${remote.port} $t"
    }

    // ------------------------------------------------------------------
    // TURN relay integration
    // ------------------------------------------------------------------

    private fun relayKey(ip: String, port: Int): String = "$ip:$port"

    /**
     * Watchdog: after [DIRECT_PROBE_TIMEOUT_MS] without any live pair, request
     * a TURN allocation. When [forceRelay] is true, allocate immediately.
     */
    private fun armRelayWatchdog() {
        relayWatchdogJob?.cancel()
        relayWatchdogJob = scope.launch {
            if (!P2PPathPolicy.turnRelayMediaEnabled) {
                // Two modes: no verified direct pair within the budget ⇒ the call is on the
                // VPS relay (FAILED). Probing keeps running, so a pair that verifies later
                // still upgrades the call (promoteActive ⇒ CONNECTED).
                delay(DIRECT_PROBE_TIMEOUT_MS)
                if (selectedRemote == null && state != State.CONNECTED) {
                    Log.w(TAG, "$LOG_PREFIX no media-verified direct pair after ${DIRECT_PROBE_TIMEOUT_MS}ms — VPS relay carries the call (probing continues)")
                    state = State.FAILED
                }
                return@launch
            }
            if (!forceRelay) {
                delay(DIRECT_PROBE_TIMEOUT_MS)
                if (selectedRemote != null) return@launch
            }
            Log.d(TAG, "$LOG_PREFIX direct probe ${if (forceRelay) "SKIPPED (forceRelay)" else "timeout"} — allocating TURN")
            allocateTurnAndEmitRelayCandidate()
        }
    }

    /**
     * F6 — short-lived TURN credentials override. If set (non-null), takes
     * precedence over the legacy [TURN_USERNAME] / [TURN_PASSWORD] constants
     * for TURN allocation. Stored as a Pair(username, password). Setter is
     * synchronous and lock-free — last writer wins, picked up on the next
     * allocate call. EnhancedCallManager fetches HMAC creds from /turn-creds
     * and invokes [configureTurn] before [start].
     */
    @Volatile private var turnCredsOverride: Pair<String, String>? = null

    fun configureTurn(username: String, password: String) {
        turnCredsOverride = username to password
    }

    private suspend fun allocateTurnAndEmitRelayCandidate() {
        if (!P2PPathPolicy.turnRelayMediaEnabled) return
        if (turnClient != null) return
        if (relayAllocationAttempted) return
        relayAllocationAttempted = true
        val (turnUser, turnPass) = turnCredsOverride ?: (TURN_USERNAME to TURN_PASSWORD)
        // __NO_STATIC_TURN_2026_09_25__ The static credentials are no longer shipped (coturn
        // rejected them anyway). Without ephemeral creds yet, don't burn Allocates on a 401:
        // allow a later attempt once /voip/turn-creds has answered.
        if (turnUser.isBlank() || turnPass.isBlank()) { relayAllocationAttempted = false; return }
        val client = TurnClient(
            serverHost = TURN_HOST,
            serverPort = TURN_PORT,
            username = turnUser,
            password = turnPass,
            scope = scope
        )
        client.onRelayedPacket = { data, peerIp, peerPort ->
            dispatchRelayInbound(data, peerIp, peerPort)
        }
        turnClient = client

        var alloc: TurnClient.AllocatedRelay? = null
        repeat(3) { attempt ->
            if (alloc != null) return@repeat
            alloc = withTimeoutOrNull(3_000L) { client.allocate() }
            if (alloc == null) {
                Log.w(TAG, "$LOG_PREFIX TURN allocate attempt ${attempt + 1} failed")
                delay(500L * (attempt + 1))
            }
        }
        val a = alloc
        if (a == null) {
            Log.w(TAG, "$LOG_PREFIX TURN allocate failed 3× — giving up")
            if (selectedRemote == null) state = State.FAILED
            return
        }

        val relayCand = P2PCandidate(
            CandidateType.RELAY, a.relayedAddress, a.relayedPort,
            defaultPriority(CandidateType.RELAY, if (a.relayedAddress.contains(':')) 6 else 4)
        )
        val merged = (localCandidates + relayCand).distinctBy { "${it.type}|${it.ip}:${it.port}" }
            .sortedByDescending { it.priority }
        localCandidates = merged
        Log.d(TAG, "$LOG_PREFIX TURN relay candidate ready: ${relayCand.ip}:${relayCand.port}")
        emitLocalCandidates(merged)

        // Bind a channel for every remote relay candidate we already know.
        for (remote in remoteCandidates.filter { it.type == CandidateType.RELAY }) {
            bindTurnChannelFor(remote)
        }
    }

    private fun bindTurnChannelFor(remote: P2PCandidate) {
        val turn = turnClient ?: return
        val key = relayKey(remote.ip, remote.port)
        if (relayChannels.containsKey(key)) return
        val channel = nextChannel.getAndIncrement()
        relayChannels[key] = channel
        turn.bindChannel(remote.ip, remote.port, channel)
        Log.d(TAG, "$LOG_PREFIX ChannelBind request: channel=$channel peer=${remote.ip}:${remote.port}")
    }

    private fun dispatchRelayInbound(data: ByteArray, fromIp: String, fromPort: Int) {
        if (data.isEmpty()) return
        when (data[0]) {
            PACKET_TYPE_HOLEPUNCH_PING -> {
                val pong = data.copyOf()
                pong[0] = PACKET_TYPE_HOLEPUNCH_PONG
                val ch = relayChannels[relayKey(fromIp, fromPort)]
                val turn = turnClient
                if (ch != null && turn != null) turn.send(pong, viaChannel = ch)
                val srcKey = "$fromIp:$fromPort"
                if (loggedPingRxSources.add(srcKey)) {
                    var peerCid = 0L
                    if (data.size >= 9) {
                        for (i in 0 until 8) {
                            peerCid = (peerCid shl 8) or (data[1 + i].toLong() and 0xFF)
                        }
                    }
                    CallDiag.w(TAG, "DIAG_ICE_PING_RX | via=relay | from=$srcKey | peerCid=${"%016x".format(peerCid)}")
                }
            }
            PACKET_TYPE_HOLEPUNCH_PONG -> handlePongCommon(data, fromIp, fromPort, viaRelay = true)
            else -> onPacketReceived?.invoke(data)
        }
    }

    /** Route one app packet to a specific pair — direct UDP or TURN channel. */
    private fun sendToPair(data: ByteArray, remote: P2PCandidate) {
        if (remote.type == CandidateType.RELAY) {
            val key = relayKey(remote.ip, remote.port)
            val ch = relayChannels[key]
            val turn = turnClient
            if (ch != null && turn != null) {
                turn.send(data, viaChannel = ch)
                return
            }
            // Channel not yet bound — drop silently.
            return
        }
        sendRaw(data, remote.ip, remote.port)
    }

    private fun sendRaw(data: ByteArray, ip: String, port: Int) {
        val s = socket ?: return
        scope.launch {
            try {
                val addr = InetAddress.getByName(ip)
                val pkt = DatagramPacket(data, data.size, InetSocketAddress(addr, port))
                s.send(pkt)
            } catch (e: Exception) {
                // socket closed / bad address — ignore
            }
        }
    }

    // Ping layout: [0x31][call_id 8B BE][nonce 12B][ts_ms 8B BE]
    // call_id is 0 when [setCallId] has not been invoked. iOS accepts a
    // zero call_id as a wildcard match so the probe still gets a pong.
    private fun buildPing(size: Int = P2PPathPolicy.PING_BYTES): Pair<ByteArray, String> {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val out = P2PPathPolicy.buildPing(callId, nonce, System.currentTimeMillis(), size)
        val nonceHex = nonce.joinToString("") { "%02x".format(it) }
        return out to nonceHex
    }

    /** srflx discovered FROM the media socket; the reply is demuxed in [dispatchInbound]. */
    private suspend fun discoverSrflxOnMediaSocket(): StunClient.ReflexiveAddress? {
        val s = socket ?: return null
        val (request, txId) = stunClient.buildBindingRequest()
        val hex = txId.joinToString("") { "%02x".format(it) }
        val done = CompletableDeferred<StunClient.ReflexiveAddress?>()
        pendingStunTx[hex] = txId to done
        return try {
            val target = InetSocketAddress(InetAddress.getByName(StunClient.DEFAULT_HOST), StunClient.DEFAULT_PORT)
            coroutineScope {
                // Same transaction retransmitted at 0 / 300 / 900 ms (UDP to the VPS is lossy).
                val sender = launch {
                    for (gap in longArrayOf(0L, 300L, 600L)) {
                        delay(gap)
                        try { s.send(DatagramPacket(request, request.size, target)) } catch (_: Exception) {}
                    }
                }
                val r = done.await()
                sender.cancel()
                r
            }
        } finally {
            pendingStunTx.remove(hex)
        }
    }

    // ------------------------------------------------------------------
    // Candidate wire codec (unchanged — version 0x01)
    // ------------------------------------------------------------------

    fun encodeCandidates(candidates: List<P2PCandidate>): ByteArray {
        if (candidates.isEmpty()) return byteArrayOf(CANDIDATE_WIRE_VERSION, 0)
        val clipped = if (candidates.size > 255) candidates.take(255) else candidates
        val out = java.io.ByteArrayOutputStream()
        out.write(CANDIDATE_WIRE_VERSION.toInt() and 0xFF)
        out.write(clipped.size and 0xFF)
        for (c in clipped) {
            val addr = try { InetAddress.getByName(c.ip) } catch (_: Exception) { continue }
            val addrBytes = addr.address
            val family = if (addr is Inet6Address) 6 else 4
            if ((family == 4 && addrBytes.size != 4) || (family == 6 && addrBytes.size != 16)) continue
            out.write(c.type.wire.toInt() and 0xFF)
            out.write(family)
            out.write((c.port ushr 8) and 0xFF)
            out.write(c.port and 0xFF)
            out.write(addrBytes)
            out.write((c.priority ushr 24) and 0xFF)
            out.write((c.priority ushr 16) and 0xFF)
            out.write((c.priority ushr 8) and 0xFF)
            out.write(c.priority and 0xFF)
        }
        return out.toByteArray()
    }

    fun decodeCandidates(data: ByteArray): List<P2PCandidate> {
        if (data.size < 2) return emptyList()
        val version = data[0]
        if (version != CANDIDATE_WIRE_VERSION) {
            Log.w(TAG, "$LOG_PREFIX decodeCandidates: unknown version=0x${"%02x".format(version)}")
            return emptyList()
        }
        val n = data[1].toInt() and 0xFF
        val out = ArrayList<P2PCandidate>(n)
        var i = 2
        repeat(n) {
            if (i + 4 > data.size) return out
            val type = CandidateType.fromWire(data[i]) ?: return out
            val family = data[i + 1].toInt() and 0xFF
            val port = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            i += 4
            val ipLen = if (family == 4) 4 else if (family == 6) 16 else return out
            if (i + ipLen + 4 > data.size) return out
            val ip = try {
                InetAddress.getByAddress(data.copyOfRange(i, i + ipLen)).hostAddress ?: return out
            } catch (_: Exception) { return out }
            i += ipLen
            val priority = ((data[i].toInt() and 0xFF) shl 24) or
                    ((data[i + 1].toInt() and 0xFF) shl 16) or
                    ((data[i + 2].toInt() and 0xFF) shl 8) or
                    (data[i + 3].toInt() and 0xFF)
            i += 4
            val cleanIp = ip.substringBefore('%')
            out += P2PCandidate(type, cleanIp, port, priority)
        }
        return out
    }

    /** Test hook — blocks until all internal coroutines have finished cancelling. */
    internal fun stopAndJoinForTest() = runBlocking {
        try { probeJob?.cancelAndJoin() } catch (_: Exception) {}
        try { failoverJob?.cancelAndJoin() } catch (_: Exception) {}
        try { relayWatchdogJob?.cancelAndJoin() } catch (_: Exception) {}
        try { receiveJob?.cancelAndJoin() } catch (_: Exception) {}
        stop()
    }

    // ------------------------------------------------------------------
    // Per-pair probe scoring state
    // ------------------------------------------------------------------

    /**
     * EWMA RTT + rolling success rate over the last [windowSize] probes for
     * one candidate pair. All fields are mutated only from [scope] coroutines
     * (probe/failover jobs + receive loop), but reads (e.g. score) can happen
     * from any caller — we use `@Volatile` on the simple scalars.
     */
    private class PairState(
        val candidate: P2PCandidate,
        private val windowSize: Int
    ) {
        @Volatile var ewmaRttMs: Double = -1.0
        @Volatile var lastPongAtMs: Long = 0L
        @Volatile var isLive: Boolean = false
        /** Last pong to a JUMBO (media-sized) ping — see [P2PPathPolicy]. 0 = never. */
        @Volatile var lastJumboPongAtMs: Long = 0L

        private val window = BooleanArray(windowSize)
        private var windowIndex = 0
        private var windowFilled = 0

        val successRate: Double
            @Synchronized get() {
                if (windowFilled == 0) return 0.0
                var hits = 0
                for (i in 0 until windowFilled) {
                    if (window[i]) hits++
                }
                return hits.toDouble() / windowFilled.toDouble()
            }

        /** score = (1 / (RTT_ms + 1)) × successRate. 0 before first pong. */
        val score: Double
            get() {
                if (ewmaRttMs < 0) return 0.0
                return (1.0 / (ewmaRttMs + 1.0)) * successRate
            }

        @Synchronized
        fun recordHit(rttMs: Double, alpha: Double) {
            ewmaRttMs = if (ewmaRttMs < 0) rttMs else alpha * rttMs + (1.0 - alpha) * ewmaRttMs
            pushWindow(true)
            lastPongAtMs = System.currentTimeMillis()
            isLive = true
        }

        @Synchronized
        fun recordMiss() {
            pushWindow(false)
        }

        private fun pushWindow(hit: Boolean) {
            window[windowIndex] = hit
            windowIndex = (windowIndex + 1) % windowSize
            if (windowFilled < windowSize) windowFilled++
        }

        fun refreshLiveness(nowMs: Long) {
            if (lastPongAtMs > 0 && (nowMs - lastPongAtMs) > LIVENESS_MAX_AGE_MS) {
                isLive = false
            }
        }
    }
}
