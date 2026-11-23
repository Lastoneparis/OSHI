package com.oshi.messenger.network.v2.devsync

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture

// ======================================================================================= LAN (§5.1)

/**
 * mDNS / DNS-SD for `_oshi-devsync._tcp` — NSD on Android, the hand-rolled responder on Desktop.
 * TXT carries `v=1` and `t=<hourly tag>`; instance names are 8 random hex characters.
 */
interface LanDiscovery {
    fun start(port: Int, txt: Map<String, String>, onFound: (host: String, port: Int, txt: Map<String, String>) -> Unit)
    fun updateTxt(txt: Map<String, String>)
    fun stop()

    companion object {
        const val SERVICE_TYPE = "_oshi-devsync._tcp"
        fun instanceName(): String = DevSyncCrypto.hex(DevSyncCrypto.randomBytes(4))
    }
}

/** A dedicated TCP listener (NOT the mesh's: that one drops own-key frames and has no encryption). */
class LanTransport(private val engine: DevSyncEngine, private val bind: InetAddress? = null) {
    private var server: ServerSocket? = null
    private var discovery: LanDiscovery? = null
    private var currentTag: String? = null
    private val lastDial = HashMap<String, Long>()
    @Volatile private var stopped = false

    /**
     * __DEVSYNC_LIVE_UPGRADE_2026_09_23__ LAN endpoints seen (mDNS or a manual dial), and which
     * device answered there once a session we dialled completed. They are re-dialled while a peer
     * is only reachable through the relay (the "upgrade probe", §5.3 step 4): mDNS reports a service
     * once, so a peer that became reachable later (Wi-Fi joined, TCP refused at first) is otherwise
     * never retried. Dropped after [MAX_FAILURES] consecutive failed dials.
     */
    private class Endpoint(val host: String, val port: Int, var peerId: String? = null, var failures: Int = 0)
    private val endpoints = LinkedHashMap<String, Endpoint>()

    val port: Int get() = server?.localPort ?: -1

    fun start(discovery: LanDiscovery?): Int {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bind, 0))
        server = ss
        Thread({ acceptLoop(ss) }, "oshi-devsync-lan-accept").apply { isDaemon = true; start() }
        this.discovery = discovery
        if (discovery != null) {
            currentTag = tagNow()
            discovery.start(ss.localPort, txt()) { host, port, txt -> engine.post { onFound(host, port, txt) } }
        }
        engine.log("LAN listener on port ${ss.localPort}")
        return ss.localPort
    }

    private fun tagNow() = DevSyncKeys.mdnsTag(engine.disc, engine.now() / 1000)
    private fun txt() = mapOf("v" to "1", "t" to (currentTag ?: tagNow()))

    private fun onFound(host: String, port: Int, txt: Map<String, String>) {
        if (txt["v"] != "1") return
        val tag = txt["t"] ?: return
        if (tag !in DevSyncKeys.acceptedTags(engine.disc, engine.now() / 1000)) return
        if (port == this.port && isLocalHost(host)) return
        val known = endpoints["$host:$port"]?.peerId
        // Already on LAN with the device behind this endpoint: a repeated announcement is no reason
        // to open a duplicate session (it would replace the live one every REDIAL_MS).
        if (known != null && engine.activeSession(known)?.link?.transport == DevSyncKeys.TRANSPORT_LAN) return
        dial(host, port)
    }

    /**
     * Re-dial the known endpoints of devices that are NOT on LAN right now (their session is on the
     * relay, or there is none). A LAN session that completes retires the relay one (BYE{lan}).
     */
    fun probeUpgrade() {
        for (e in endpoints.values.toList()) {
            val peer = e.peerId
            if (peer != null) {
                if (engine.registry.isRevoked(peer)) continue
                if (engine.activeSession(peer)?.link?.transport == DevSyncKeys.TRANSPORT_LAN) continue
            } else if (engine.linkedDevices().none { engine.activeSession(it.deviceId)?.link?.transport != DevSyncKeys.TRANSPORT_LAN }) {
                continue // every linked device is already on LAN: nothing an unknown endpoint could upgrade
            }
            dial(e.host, e.port)
        }
    }

    /** Called on the sync thread once a session we dialled at [label] proved which device it is. */
    internal fun onDialledSessionIdentified(label: String, peerId: String) {
        endpoints[label]?.let { it.peerId = peerId; it.failures = 0 }
    }

    private fun isLocalHost(host: String): Boolean = runCatching {
        val a = InetAddress.getByName(host)
        a.isLoopbackAddress || java.net.NetworkInterface.getByInetAddress(a) != null
    }.getOrDefault(false)

    /** Dial a LAN peer (also used directly by tests and by a manual "connect"). */
    fun dial(host: String, port: Int) {
        val key = "$host:$port"
        endpoints.getOrPut(key) { Endpoint(host, port) }
        val t = engine.now()
        if (t - (lastDial[key] ?: 0L) < REDIAL_MS) return
        lastDial[key] = t
        Thread({
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                engine.post { endpoints[key]?.failures = 0; LanLink(engine, sock, key).attach(initiator = true) }
            } catch (e: IOException) {
                engine.log("LAN dial failed: ${e.javaClass.simpleName}")
                engine.post {
                    val ep = endpoints[key]
                    if (ep != null && ++ep.failures >= MAX_FAILURES) endpoints.remove(key)
                }
            }
        }, "oshi-devsync-lan-dial").apply { isDaemon = true; start() }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!stopped) {
            val sock = try { ss.accept() } catch (e: IOException) { if (stopped) return else continue }
            val label = "${sock.inetAddress.hostAddress}:${sock.port}"
            engine.post { LanLink(engine, sock, label).attach(initiator = false) }
        }
    }

    /** Hourly tag rotation (§5.1). */
    fun maintain() {
        val tag = tagNow()
        if (tag != currentTag) {
            currentTag = tag
            discovery?.updateTxt(txt())
        }
    }

    fun rediscover() { lastDial.clear() }

    fun stop() {
        stopped = true
        runCatching { discovery?.stop() }
        runCatching { server?.close() }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val REDIAL_MS = 30_000L
        const val MAX_FAILURES = 3
    }
}

/** One TCP connection = one session. `u16be length || Noise message` framing. */
class LanLink(private val engine: DevSyncEngine, private val socket: Socket, override val label: String) : DevSyncLink {
    override val transport: String = DevSyncKeys.TRANSPORT_LAN
    private val outbox = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false
    private lateinit var session: DevSyncSession

    fun attach(initiator: Boolean) {
        socket.tcpNoDelay = true
        socket.soTimeout = 0
        session = engine.newSession(this, initiator, null)
        Thread({ readLoop() }, "oshi-devsync-lan-rx").apply { isDaemon = true; start() }
        Thread({ writeLoop() }, "oshi-devsync-lan-tx").apply { isDaemon = true; start() }
    }

    override fun send(kind: Int, noiseMessage: ByteArray) {
        if (!closed) outbox.add(DevSyncWire.lanFrame(noiseMessage))
    }

    private fun readLoop() {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            while (!closed) {
                val frame = DevSyncWire.readLanFrame(input)
                engine.post { session.onFrame(0, frame) }
            }
        } catch (e: IOException) {
            // EOF or reset
        }
        engine.post { session.close("eof", silent = true) }
    }

    private fun writeLoop() {
        try {
            val out = BufferedOutputStream(socket.getOutputStream())
            while (true) {
                val b = outbox.take()
                if (b === POISON) break
                out.write(b)
                if (outbox.isEmpty()) out.flush()
            }
            out.flush()
        } catch (e: Exception) {
            // socket gone
        }
        runCatching { socket.close() }
    }

    override fun close() {
        if (closed) return
        closed = true
        outbox.add(POISON)
        // Let the writer flush the last frames (a BYE) before the socket goes.
        engine.schedule(500) { runCatching { socket.close() } }
    }

    companion object { private val POISON = ByteArray(0) }
}

// ======================================================================================= relay (§5.2)

/** A connected relay WebSocket. [sendBinary] queues one binary message. */
interface RelaySocket {
    fun sendBinary(bytes: ByteArray): Boolean
    fun close()
}

interface RelayListener {
    fun onOpen()
    fun onText(text: String)
    fun onBinary(bytes: ByteArray)
    fun onClosed(code: Int, reason: String)
}

/**
 * Opens `GET /v2/devsync?device=<deviceId>` as a SIGNED WebSocket upgrade, account- AND device-signed
 * (__DEVSYNC_DEVICE_AUTH_2026_09_23__): build the headers with [DevSyncRelayAuth.upgradeHeaders]
 * (the account signature covers sha256(B) instead of an empty body; the device key `dsk` signs the
 * same B; a fresh nonce per attempt). Android: OkHttp; Desktop: java.net.http.WebSocket.
 * Close 4003 = this device was removed from / reset in the account's device registry.
 */
fun interface RelayConnector {
    fun connect(deviceIdHex: String, listener: RelayListener): RelaySocket

    companion object {
        const val PATH = "/v2/devsync"
    }
}

/**
 * The client side of the live pass-through relay: one WebSocket, many peer sessions, demultiplexed by
 * the 16-byte source id the relay writes in front of every frame. Presence frames drive who dials:
 * the SMALLER deviceId initiates, after giving LAN [DevSyncConfig.relayDelayMs] to find the peer.
 */
class RelayHub(private val engine: DevSyncEngine, private val connector: RelayConnector) {
    private var socket: RelaySocket? = null
    private var connected = false
    private val online = LinkedHashSet<String>()
    private val links = HashMap<String, RelayLink>()
    private var attempt = 0
    private var reconnect: ScheduledFuture<*>? = null
    private var stopped = false
    private val dialTimers = HashMap<String, ScheduledFuture<*>>()
    /** Monotonic socket generation: callbacks from a replaced socket are ignored. */
    private var generation = 0

    val isConnected: Boolean get() = connected
    fun onlinePeers(): Set<String> = online.toSet()

    fun start() = connect()

    private fun connect() {
        if (stopped) return
        val gen = ++generation
        try {
            socket = connector.connect(engine.deviceId, object : RelayListener {
                override fun onOpen() = engine.post { if (gen == generation) { connected = true; attempt = 0; engine.log("relay connected") } }
                override fun onText(text: String) = engine.post { if (gen == generation) onPresence(text) }
                override fun onBinary(bytes: ByteArray) = engine.post { if (gen == generation) onFrame(bytes) }
                override fun onClosed(code: Int, reason: String) = engine.post { if (gen == generation) onDisconnected(code, reason) }
            })
        } catch (e: Exception) {
            engine.log("relay connect failed: ${e.javaClass.simpleName}")
            onDisconnected(-1)
        }
    }

    private fun onDisconnected(code: Int, reason: String = "") {
        connected = false
        socket = null
        online.clear()
        for (l in links.values.toList()) l.session?.close("relay-down", silent = true)
        links.clear()
        if (stopped) return
        // §9: 2 s, 5 s, 15 s, 60 s, then every 5 min.
        val delay = BACKOFF_MS.getOrElse(attempt) { BACKOFF_MS.last() }
        attempt++
        reconnect?.cancel(false)
        reconnect = engine.schedule(delay) { connect() }
        engine.log("relay closed ($code $reason), retry in ${delay / 1000}s")
    }

    private fun onPresence(text: String) {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (o.optString("t")) {
            "peers" -> {
                online.clear()
                val a = o.optJSONArray("d")
                if (a != null) for (i in 0 until a.length()) a.optString(i).takeIf(DevSyncKeys::isDeviceIdHex)?.let(online::add)
                online.forEach { scheduleDial(it) }
            }
            "up" -> o.optString("d").takeIf(DevSyncKeys::isDeviceIdHex)?.let { online.add(it); scheduleDial(it) }
            "down" -> o.optString("d").let { id ->
                online.remove(id)
                links.remove(id)?.session?.close("peer-down", silent = true)
            }
            // The frame was DROPPED (§5.2): the Noise stream with that peer is broken. Stop until `up`.
            "nopeer" -> o.optString("d").let { id ->
                online.remove(id)
                links.remove(id)?.session?.close("nopeer", silent = true)
            }
        }
    }

    private fun scheduleDial(peer: String, delayMs: Long = engine.config.relayDelayMs) {
        // The SMALLER id initiates (Appendix C.1, fixtures README). __DEVSYNC_LIVE_UPGRADE_2026_09_23__
        // fixed: this used to read `peer >= deviceId -> return`, i.e. the LARGER id dialled, which an
        // implementation following the spec (iOS) would never answer with a dial of its own.
        if (peer <= engine.deviceId) return
        if (engine.registry.isRevoked(peer)) return
        dialTimers[peer]?.cancel(false)
        engine.schedule(delayMs) { dialTimers.remove(peer); dial(peer) }?.let { dialTimers[peer] = it }
    }

    /**
     * __DEVSYNC_LIVE_UPGRADE_2026_09_23__ The LAN session with [peer] just died: dial it through the
     * relay without the usual "give LAN 3 s" delay (LAN was just tried). No-op for the larger id.
     */
    fun dialSoon(peer: String) {
        if (!connected || peer !in online) return
        if (peer > engine.deviceId) engine.log("LAN with ${peer.take(8)} gone: dialling it through the relay")
        scheduleDial(peer, engine.config.relayFallbackDelayMs)
    }

    /** Initiate a relay session with [peer] unless one (on any transport) already exists. */
    fun dial(peer: String) {
        if (!connected || peer !in online) return
        if (engine.activeSession(peer) != null) return
        links[peer]?.session?.let { if (it.state != DevSyncSession.State.CLOSED) return }
        val link = RelayLink(this, peer)
        links[peer] = link
        link.session = engine.newSession(link, initiator = true, expectedPeerId = peer)
    }

    private fun onFrame(bytes: ByteArray) {
        val f = DevSyncWire.parseRelayFrame(bytes) ?: return
        val src = f.deviceIdHex
        if (src == engine.deviceId) return
        online.add(src)
        if (f.kind == DevSyncWire.KIND_MSG1) {
            // A fresh handshake from src replaces whatever relay session we had with it.
            links.remove(src)?.session?.close("replaced", silent = true)
            if (engine.registry.isRevoked(src)) return
            val link = RelayLink(this, src)
            links[src] = link
            link.session = engine.newSession(link, initiator = false, expectedPeerId = src)
            link.session!!.onFrame(f.kind, f.noise)
            return
        }
        links[src]?.session?.onFrame(f.kind, f.noise)
    }

    internal fun send(peer: String, kind: Int, noise: ByteArray) {
        val s = socket ?: return
        s.sendBinary(DevSyncWire.relayFrame(DevSyncCrypto.unhex(peer), kind, noise))
    }

    internal fun detach(link: RelayLink) {
        if (links[link.peer] === link) links.remove(link.peer)
    }

    fun onSessionClosed(s: DevSyncSession) {
        val l = s.link as? RelayLink ?: return
        detach(l)
    }

    /** Periodic: re-dial online peers that have no session (e.g. after the 1 h session cap). */
    fun maintain() {
        if (!connected) return
        for (p in online) if (engine.activeSession(p) == null && links[p] == null) scheduleDial(p)
    }

    fun stop() {
        stopped = true
        reconnect?.cancel(false)
        dialTimers.values.forEach { it.cancel(false) }
        runCatching { socket?.close() }
        socket = null
        connected = false
    }

    companion object {
        val BACKOFF_MS = longArrayOf(2_000, 5_000, 15_000, 60_000, 300_000)
    }
}

class RelayLink(private val hub: RelayHub, val peer: String) : DevSyncLink {
    override val transport: String = DevSyncKeys.TRANSPORT_RELAY
    override val label: String = "relay:" + peer.take(8)
    var session: DevSyncSession? = null
    override fun send(kind: Int, noiseMessage: ByteArray) = hub.send(peer, kind, noiseMessage)
    override fun close() = hub.detach(this)
}
