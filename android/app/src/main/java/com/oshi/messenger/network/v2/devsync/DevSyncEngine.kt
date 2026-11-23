package com.oshi.messenger.network.v2.devsync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class DevSyncNet(val metered: Boolean = false, val lowPower: Boolean = false)

data class DevSyncConfig(
    val deviceName: String,
    /** `ios` / `android` / `desktop`. */
    val platform: String,
    val appVersion: String,
    /** Where partial media downloads live: `<cacheDir>/devsync/partial/<sha256>`. */
    val cacheDir: File,
    val historySource: Boolean = false,
    /**
     * What this device lets the peer send it, and what it sends: `always` / `unmetered` / `never`.
     * Owner decision 2026-09-22: media sync is allowed on cellular, so the default is `always`.
     */
    val mediaPolicy: String = Hello.MEDIA_ALWAYS,
    val network: () -> DevSyncNet = { DevSyncNet() },
    val windowRecords: Int = 32,
    val windowBytes: Long = 2L * 1024 * 1024,
    val rekeyMessages: Long = TransportCipher.REKEY_MESSAGES,
    val rekeyBytes: Long = TransportCipher.REKEY_BYTES,
    val relaySessionCapBytes: Long = 1L shl 30,
    val sessionMaxMs: Long = 60 * 60 * 1000L,
    /** Give LAN this long to find a peer before dialling it through the relay (§5.3). */
    val relayDelayMs: Long = 3_000L,
    val clock: () -> Long = System::currentTimeMillis,
    /**
     * __DEVSYNC_LIVE_UPGRADE_2026_09_23__ Near-instant mirroring (§9): a local change re-runs the
     * diff on every open session after this quiet period (a burst is batched)…
     */
    val liveDebounceMs: Long = 150L,
    /** …but never later than this after the FIRST change of a burst (a steady stream still flushes). */
    val liveMaxDelayMs: Long = 600L,
    /** LAN keepalive (§5.3, Appendix C.14): an idle LAN session sends a redundant ACK this often. */
    val lanKeepaliveMs: Long = 10_000L,
    /** A LAN session whose peer keeps alive but sent nothing for this long is dead: fall back to relay. */
    val lanIdleTimeoutMs: Long = 30_000L,
    /** Delay before re-dialling the relay after a LAN session died (the peer's LAN side dies too). */
    val relayFallbackDelayMs: Long = 250L,
)

/** What the UI shows for a device waiting to be approved (§4.3). */
data class PairingRequest(
    val peerId: String,
    val name: String,
    val platform: String,
    val code: String,
    val transport: String,
    /** True when THIS device has no linked device yet (it shows "waiting for approval"). */
    val thisDeviceIsNew: Boolean,
    /** The other device already approved; approving here completes the link. */
    val peerApproved: Boolean = false,
)

data class SessionInfo(
    val peerId: String?,
    val peerName: String?,
    val transport: String,
    val state: DevSyncSession.State,
    val pullComplete: Boolean,
    val mediaComplete: Boolean,
    val stats: DevSyncSession.SessionStats,
)

interface DevSyncListener {
    fun onDevicesChanged() {}
    fun onPairingRequest(request: PairingRequest) {}
    fun onPairingResolved(peerId: String, linked: Boolean) {}
    fun onSessionChanged(info: SessionInfo) {}
    fun onApplied(peerId: String, conversationId: String, result: ApplyResult) {}
    fun onSynced(peerId: String, stats: DevSyncSession.SessionStats) {}
    /** "A device using your account tried to sync" (at most once per hour per device, §4.3 step 5). */
    fun onAlert(kind: String, deviceName: String) {}
}

/**
 * Own-device sync ("direct strict", docs/OSHI_DEVICE_SYNC_DIRECT.md). PURE JVM: the same engine
 * runs on Android and Desktop; the platforms supply a [DevSyncStore], a [DeviceKeyProvider], a
 * [DevSyncStateStore], and optionally a [LanDiscovery] and a [RelayConnector].
 *
 * Threading: ONE sync thread owns every session and every store call. Transports post to it.
 */
class DevSyncEngine(
    val config: DevSyncConfig,
    accountPriv: ByteArray,
    deviceKeys: DeviceKeyProvider,
    val store: DevSyncStore,
    private val stateStore: DevSyncStateStore,
    private val logger: (String) -> Unit = {},
) {
    private val psk = DevSyncKeys.psk(accountPriv)
    val disc: ByteArray = DevSyncKeys.disc(accountPriv)
    private val dkPriv = deviceKeys.loadOrCreate().also { require(it.size == 32) }
    val deviceKeyPub: ByteArray = DevSyncCrypto.x25519Public(dkPriv)
    val deviceId: String = DevSyncKeys.deviceIdHex(deviceKeyPub)
    val registry = LinkedDeviceRegistry(stateStore, deviceId)
    val pendingMedia = PendingMedia(stateStore)

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "oshi-devsync").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArrayList<DevSyncListener>()
    private val sessions = ArrayList<DevSyncSession>()
    private val activeByPeer = HashMap<String, DevSyncSession>()
    private val pending = LinkedHashMap<String, PairingRequest>()
    private val deniedUntil = HashMap<String, Long>()
    private val lastAlertAt = HashMap<String, Long>()
    @Volatile private var running = false
    private var sweeper: ScheduledFuture<*>? = null

    /**
     * Extra fields merged into our `APPROVAL{approved:true}` for a peer (design §15.3: the signed
     * per-device mailbox approval `mailbox:{by, ts, sig}`). Args: peer deviceId, peer DK public key.
     */
    @Volatile var approvalExtras: ((String, ByteArray) -> JSONObject?)? = null
    /** Called with the full `APPROVAL{approved:true,…}` body a peer sent us (e.g. to read `mailbox`). */
    @Volatile var onApprovalReceived: ((String, JSONObject) -> Unit)? = null
    /**
     * __DEVSYNC_DEVICE_AUTH_2026_09_23__ Our public `dsk` (std base64) for HELLO — the same Ed25519 key
     * that signs the relay upgrade and the per-device mailbox. Null = not sent.
     */
    @Volatile var deviceDsk: (() -> String?)? = null
    /** Called with a peer's HELLO `dsk` once the handshake proved its deviceId (before any approval). */
    @Volatile var onPeerDsk: ((peerId: String, dsk: String) -> Unit)? = null

    /** Extra fields for OUR HELLO (additive fields of other layers, design §15.3). */
    @Volatile var helloExtras: (() -> JSONObject?)? = null
    /**
     * Called with a peer's full HELLO once the handshake proved its deviceId (before any approval):
     * lets the mailbox layer note the peer's `dsk` so an approval can be signed when the user allows.
     */
    @Volatile var onPeerHello: ((String, JSONObject) -> Unit)? = null

    var lan: LanTransport? = null
        private set
    var relay: RelayHub? = null
        private set

    internal fun psk() = psk
    internal fun deviceKeyPriv() = dkPriv
    fun now(): Long = config.clock()
    fun log(line: String) = logger("[devsync] $line")

    fun addListener(l: DevSyncListener) { listeners.add(l) }
    fun removeListener(l: DevSyncListener) { listeners.remove(l) }

    // ================================================================== lifecycle

    /** Start whichever transports are given. Either may be null (flag off, or tests). */
    fun start(lanDiscovery: LanDiscovery? = null, relayConnector: RelayConnector? = null, lanEnabled: Boolean = lanDiscovery != null) {
        if (running) return
        running = true
        post {
            if (lanEnabled) {
                val t = LanTransport(this)
                lan = t
                runCatching { t.start(lanDiscovery) }.onFailure { log("LAN listener failed: ${it.javaClass.simpleName}") }
            }
            if (relayConnector != null) {
                relay = RelayHub(this, relayConnector).also { it.start() }
            }
            sweeper = executor.scheduleWithFixedDelay({ runCatching { sweep() } }, SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        val done = CountDownLatch(1)
        post {
            sweeper?.cancel(false)
            for (s in sessions.toList()) {
                if (s.state == DevSyncSession.State.SYNC) s.sendRecord(DevSyncWire.BYE, JSONObject().put("reason", "done"))
                s.close("stopped", silent = true)
            }
            relay?.stop(); relay = null
            lan?.stop(); lan = null
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
        executor.shutdown()
    }

    val isRunning: Boolean get() = running

    fun post(block: () -> Unit) {
        if (executor.isShutdown) return
        executor.execute {
            try { block() } catch (e: Throwable) { log("sync thread: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    /** Null once the engine is stopped (an engine is single-use: create a new one to restart). */
    fun schedule(delayMs: Long, block: () -> Unit): ScheduledFuture<*>? = try {
        executor.schedule({ try { block() } catch (e: Throwable) { log("timer: ${e.javaClass.simpleName}: ${e.message}") } }, delayMs, TimeUnit.MILLISECONDS)
    } catch (e: java.util.concurrent.RejectedExecutionException) {
        null
    }

    /** Run [block] on the sync thread and wait for its result (UI snapshots, tests). */
    fun <T> call(block: () -> T): T {
        val f = executor.submit<T> { block() }
        return f.get(10, TimeUnit.SECONDS)
    }

    // ================================================================== public API

    fun hello(): Hello {
        val net = config.network()
        return Hello(
            deviceId = deviceId, name = config.deviceName, platform = config.platform, app = config.appVersion,
            historySource = config.historySource, metered = net.metered, lowPower = net.lowPower, media = config.mediaPolicy,
            dsk = deviceDsk?.let { f -> runCatching { f() }.getOrNull() }?.takeIf(Hello::isDsk),
            extra = helloExtras?.let { f -> runCatching { f() }.getOrNull() },
        )
    }

    fun selfEntry(): LinkedDevice =
        LinkedDevice(deviceId, DevSyncCrypto.hex(deviceKeyPub), config.deviceName, config.platform, now())

    fun linkedDevices(): List<LinkedDevice> = registry.linked()
    fun pendingApprovals(): List<PairingRequest> = call { pending.values.toList() }
    fun sessionsInfo(): List<SessionInfo> = call { sessions.map { info(it) } }

    /** The user compared the six digits and tapped Allow (or Deny) for [peerId]. */
    fun approve(peerId: String, allow: Boolean) = post {
        val s = activeByPeer[peerId]?.takeIf { it.state == DevSyncSession.State.PAIRING } ?: return@post
        pending.remove(peerId)
        if (allow) {
            linkPeer(s, approvedBy = deviceId)
            s.localDecision(true)
        } else {
            deniedUntil[peerId] = now() + DENY_QUIET_MS
            s.localDecision(false)
            listeners.forEach { it.onPairingResolved(peerId, false) }
        }
    }

    /** "Remove device" (§4.4): local, sticky, propagated in DEVICES to the other linked devices. */
    fun revoke(peerId: String) = post {
        registry.revoke(peerId)
        activeByPeer[peerId]?.let { s ->
            s.sendRecord(DevSyncWire.BYE, JSONObject().put("reason", "revoked"))
            s.close("revoked")
        }
        // Tell the others now rather than at their next session.
        for (s in activeByPeer.values) if (s.state == DevSyncSession.State.SYNC) {
            s.sendRecord(DevSyncWire.DEVICES, registry.devicesRecordFor(s.peerId!!, selfEntry()).toJson())
        }
        onDevicesChanged()
    }

    /** "Sync now": re-run the diff on open sessions and dial whatever can be dialled. */
    fun syncNow() = post {
        for (s in activeByPeer.values) s.resync()
        relay?.maintain()
        lan?.rediscover()
        lan?.probeUpgrade()
    }

    /**
     * A message was created or received locally while sessions may be open (§9 LIVE). Mirrored at
     * once to every linked device currently in session. Offline peers get it at the next diff.
     */
    fun onLocalMessages(conversation: SyncConversation, messages: List<SyncMessage>) = post {
        if (messages.isEmpty()) return@post
        for (s in activeByPeer.values) s.sendLive(conversation, messages)
    }

    /** Drop every open session without a goodbye (network change, app backgrounded, tests). */
    fun disconnectAll(reason: String = "dropped") = post {
        for (s in sessions.toList()) s.close(reason, silent = true)
    }

    /**
     * Local messages / groups / contacts / profile / read state changed: mirror them to open sessions.
     * Debounced ([DevSyncConfig.liveDebounceMs], capped at [DevSyncConfig.liveMaxDelayMs]), cheap and
     * idempotent; call it from EVERY write path you can hook (no need to say what changed).
     */
    fun onLocalStateChanged() = onLocalChanged(null)

    /**
     * __DEVSYNC_LIVE_UPGRADE_2026_09_23__ Same as [onLocalStateChanged], narrowed to the sync
     * conversation ids that changed (`SyncConversation.id`); null = anything may have changed.
     * On flush every SYNC session pushes the new messages as `LIVE` (one hop) and re-sends its
     * SUMMARY / changed state records (the peer pulls edits, statuses, reactions). A session that is
     * still pulling defers the SUMMARY until its pull completes (so applying a big pull does not
     * restart the diff every 150 ms).
     */
    fun onLocalChanged(conversationIds: Collection<String>?) {
        val ids = conversationIds?.toList()
        post { markDirty(ids) }
    }

    private var liveTimer: ScheduledFuture<*>? = null
    private var liveFirstDirtyNanos = 0L
    private var liveDirtyAll = false
    private val liveDirtyConvs = HashSet<String>()

    private fun markDirty(ids: List<String>?) {
        if (ids == null) liveDirtyAll = true else liveDirtyConvs.addAll(ids)
        val t = System.nanoTime()
        if (liveTimer == null) liveFirstDirtyNanos = t
        liveTimer?.cancel(false)
        val left = config.liveMaxDelayMs - (t - liveFirstDirtyNanos) / 1_000_000
        liveTimer = schedule(minOf(config.liveDebounceMs, maxOf(0L, left))) { flushLive() }
    }

    private fun flushLive() {
        liveTimer = null
        val convs = if (liveDirtyAll) null else liveDirtyConvs.toList()
        liveDirtyAll = false
        liveDirtyConvs.clear()
        for (s in activeByPeer.values.toList()) s.resync(convs, deferWhilePulling = true, skipUnchanged = true)
    }

    // ================================================================== session plumbing

    internal fun newSession(link: DevSyncLink, initiator: Boolean, expectedPeerId: String?, ephemeralPriv: ByteArray? = null): DevSyncSession {
        val s = DevSyncSession(this, link, initiator, expectedPeerId, ephemeralPriv)
        sessions.add(s)
        s.start()
        return s
    }

    fun activeSession(peerId: String): DevSyncSession? = activeByPeer[peerId]?.takeIf { it.state != DevSyncSession.State.CLOSED }

    internal fun onSessionEstablished(s: DevSyncSession) {
        val peer = s.peerId!!
        if (s.initiator && s.link is LanLink) lan?.onDialledSessionIdentified(s.link.label, peer)
        val existing = activeByPeer[peer]
        // __DEVSYNC_LIVE_UPGRADE_2026_09_23__ what the retired session had in flight (LIVE records
        // not yet acknowledged) moves to the survivor instead of waiting for the next diff.
        var carried: List<ByteArray> = emptyList()
        if (existing != null && existing !== s && existing.state != DevSyncSession.State.CLOSED) {
            if (keepFirst(existing, s)) {
                log("session with ${peer.take(8)}: keeping ${existing.link.transport}, closing the new ${s.link.transport} one (duplicate)")
                s.sendRecord(DevSyncWire.BYE, JSONObject().put("reason", "duplicate"))
                s.close("duplicate", silent = true)
                return
            }
            val sLan = s.link.transport == DevSyncKeys.TRANSPORT_LAN
            val eLan = existing.link.transport == DevSyncKeys.TRANSPORT_LAN
            val reason = when {
                sLan && !eLan -> "lan"                  // Appendix C.11: LAN beats relay (upgrade)
                eLan && !sLan -> "timeout"              // a dead LAN session lost to the relay (fallback)
                else -> "duplicate"
            }
            carried = carried + existing.takeInFlightLive()
            existing.sendRecord(DevSyncWire.BYE, JSONObject().put("reason", reason))
            existing.close(reason, silent = true)
            if (reason != "duplicate") log("session with ${peer.take(8)} moved ${existing.link.transport} -> ${s.link.transport} (${carried.size} live record(s) carried)")
        }
        activeByPeer[peer] = s
        carried = takeHandover(peer) + carried
        s.authorize()
        if (carried.isNotEmpty()) s.adoptLive(carried)
        notifySession(s)
        if (s.link.transport != DevSyncKeys.TRANSPORT_LAN) lan?.probeUpgrade()
    }

    /** LIVE bodies of a session that the peer retired (BYE{lan}) before our replacement existed. */
    private val handover = HashMap<String, Pair<Long, List<ByteArray>>>()

    internal fun stashHandover(peer: String, bodies: List<ByteArray>) {
        if (bodies.isEmpty()) return
        val prev = handover[peer]?.second ?: emptyList()
        handover[peer] = System.nanoTime() to (prev + bodies)
    }

    private fun takeHandover(peer: String): List<ByteArray> {
        val (at, bodies) = handover.remove(peer) ?: return emptyList()
        return if ((System.nanoTime() - at) / 1_000_000 <= HANDOVER_TTL_MS) bodies else emptyList()
    }

    /**
     * Two sessions with the same peer (§5.1, §5.3): LAN beats relay; otherwise keep the one
     * initiated by the lexicographically smaller deviceId; same initiator = the newer one wins
     * (the older is a stale reconnect).
     */
    private fun keepFirst(a: DevSyncSession, b: DevSyncSession): Boolean {
        // A LAN session whose (keepalive-capable) peer went silent is not a LAN session any more:
        // the peer dialled the relay because ITS side of the LAN died (Appendix C.14).
        val aLan = a.link.transport == DevSyncKeys.TRANSPORT_LAN && !a.lanStale()
        val bLan = b.link.transport == DevSyncKeys.TRANSPORT_LAN && !b.lanStale()
        if (aLan != bLan) return aLan
        if (a.initiatorId == b.initiatorId) return false
        val smaller = minOf(deviceId, a.peerId!!)
        return a.initiatorId == smaller
    }

    internal fun onPairingNeeded(s: DevSyncSession) {
        val peer = s.peerId!!
        val hello = s.peerHello!!
        if ((deniedUntil[peer] ?: 0L) > now()) { s.close("denied-recently", silent = true); return }
        val fresh = registry.isEmpty()
        val req = PairingRequest(peer, hello.name, hello.platform, s.sasCode!!, s.link.transport, fresh)
        pending[peer] = req
        listeners.forEach { it.onPairingRequest(req) }
        if (!fresh) {
            val last = lastAlertAt[peer] ?: 0L
            if (now() - last >= ALERT_QUIET_MS) {
                lastAlertAt[peer] = now()
                listeners.forEach { it.onAlert("unapproved-device", hello.name) }
            }
        }
        notifySession(s)
    }

    internal fun acceptsApprovalFromUnknown(): Boolean = registry.isEmpty()

    internal fun linkPeer(s: DevSyncSession, approvedBy: String?) {
        val hello = s.peerHello!!
        registry.link(LinkedDevice(s.peerId!!, DevSyncCrypto.hex(s.peerStatic!!), hello.name, hello.platform, now(), approvedBy))
        pending.remove(s.peerId!!)
        listeners.forEach { it.onPairingResolved(s.peerId!!, true); it.onDevicesChanged() }
        listeners.forEach { it.onAlert("device-linked", hello.name) }
    }

    internal fun onPeerApprovedAwaitingLocal(s: DevSyncSession) {
        val peer = s.peerId!!
        pending[peer]?.let { pending[peer] = it.copy(peerApproved = true); listeners.forEach { l -> l.onPairingRequest(pending.getValue(peer)) } }
    }

    internal fun onPairingRefusedByPeer(s: DevSyncSession) {
        pending.remove(s.peerId!!)
        listeners.forEach { it.onPairingResolved(s.peerId!!, false) }
    }

    internal fun onSyncStarted(s: DevSyncSession) {
        pending.remove(s.peerId!!)
        notifySession(s)
    }

    internal fun onApplied(s: DevSyncSession, conv: SyncConversation, r: ApplyResult) {
        listeners.forEach { it.onApplied(s.peerId!!, conv.id, r) }
    }

    internal fun onPullComplete(s: DevSyncSession) { notifySession(s); maybeSynced(s) }
    internal fun onMediaComplete(s: DevSyncSession) { notifySession(s); maybeSynced(s) }
    internal fun onMediaFetched(s: DevSyncSession, sha: String) { notifySession(s) }

    private fun maybeSynced(s: DevSyncSession) {
        if (!s.pullComplete || !s.mediaComplete) return
        val hello = s.peerHello ?: return
        registry.touch(s.peerId!!, hello.name, hello.platform, now())
        listeners.forEach { it.onSynced(s.peerId!!, s.stats) }
    }

    internal fun onRevokedByPeer(s: DevSyncSession) {
        listeners.forEach { it.onAlert("revoked-by-peer", s.peerHello?.name ?: "") }
    }

    internal fun onDevicesChanged() = listeners.forEach { it.onDevicesChanged() }

    internal fun onSessionClosed(s: DevSyncSession, reason: String) {
        sessions.remove(s)
        val peer = s.peerId
        if (peer != null && activeByPeer[peer] === s) {
            activeByPeer.remove(peer)
            if (pending.remove(peer) != null) listeners.forEach { it.onPairingResolved(peer, registry.isLinked(peer)) }
        }
        notifySession(s)
        relay?.onSessionClosed(s)
        if (peer == null || !running) return
        // __DEVSYNC_LIVE_UPGRADE_2026_09_23__ the peer moved this session to LAN (BYE{lan}): keep what
        // was in flight for the LAN session that is about to be established.
        if (reason == "bye:lan") stashHandover(peer, s.takeInFlightLive())
        // LAN dropped (EOF, keepalive silence, error): fall back to the relay at once instead of at the
        // next sweep. Only the smaller id dials (the other side's LAN dies too and it waits for us).
        if (s.link.transport == DevSyncKeys.TRANSPORT_LAN && reason !in NO_FALLBACK &&
            registry.isLinked(peer) && activeSession(peer) == null) {
            relay?.dialSoon(peer)
        }
    }

    private fun notifySession(s: DevSyncSession) {
        val i = info(s)
        listeners.forEach { it.onSessionChanged(i) }
    }

    private fun info(s: DevSyncSession) =
        SessionInfo(s.peerId, s.peerHello?.name, s.link.transport, s.state, s.pullComplete, s.mediaComplete, s.stats.copy())

    // ================================================================== policy

    /** §10: media only if both sides allow it on the transport in use. */
    fun mediaAllowedWith(peer: Hello?, transport: String): Boolean {
        val net = config.network()
        val lanSession = transport == DevSyncKeys.TRANSPORT_LAN
        if (net.lowPower) return false
        when (config.mediaPolicy) {
            Hello.MEDIA_NEVER -> return false
            Hello.MEDIA_UNMETERED -> if (net.metered && !lanSession) return false
        }
        if (peer == null) return true
        if (peer.lowPower) return false
        return when (peer.media) {
            Hello.MEDIA_NEVER -> false
            Hello.MEDIA_UNMETERED -> !peer.metered || lanSession
            else -> true
        }
    }

    fun partialFile(sha: String): File = File(File(config.cacheDir, "devsync/partial"), sha)

    // ================================================================== housekeeping

    private fun sweep() {
        val t = now()
        for (s in sessions.toList()) {
            if (s.state != DevSyncSession.State.CLOSED && t - s.startedAtMs > config.sessionMaxMs) {
                // §4.1: 1 h session cap, then a fresh handshake (bounded forward-secrecy window).
                s.closeGracefully("timeout")
            }
        }
        lan?.maintain()
        relay?.maintain()
        // Safety net for changes no write path reported (§9): re-diff open sessions; a SUMMARY
        // identical to the last one sent is not re-sent.
        for (s in activeByPeer.values.toList()) s.resync(null, deferWhilePulling = false, skipUnchanged = true)
        // Still on the relay with a peer that may now be on the LAN: try its known LAN endpoints.
        if (activeByPeer.values.any { it.link.transport != DevSyncKeys.TRANSPORT_LAN }) lan?.probeUpgrade()
    }

    companion object {
        /** Close reasons after which a LAN session is NOT replaced by a relay session. */
        private val NO_FALLBACK = setOf("stopped", "revoked", "bye:revoked", "denied", "denied-recently", "refused-by-peer", "pairing-timeout", "duplicate", "bye:duplicate")
        const val HANDOVER_TTL_MS = 15_000L
        const val HANDSHAKE_TIMEOUT_MS = 20_000L
        const val PAIRING_TIMEOUT_MS = 5 * 60_000L
        const val DENY_QUIET_MS = 60 * 60_000L
        const val ALERT_QUIET_MS = 60 * 60_000L
        const val SWEEP_MS = 30_000L
    }
}

/**
 * Media referenced by synced messages whose bytes have not arrived yet. Persistent, so a transfer
 * interrupted today resumes at the next session from `<cache>/devsync/partial/<sha256>` (§6.2).
 */
class PendingMedia(private val state: DevSyncStateStore) {
    data class Ref(val conv: String, val id: String)
    private data class Entry(val size: Long, var ts: Long, val refs: LinkedHashSet<Ref>)
    private val map = LinkedHashMap<String, Entry>()

    init {
        runCatching {
            val o = JSONObject(state.read(FILE) ?: "{}")
            val it = o.keys()
            while (it.hasNext()) {
                val sha = it.next()
                val e = o.getJSONObject(sha)
                val refs = LinkedHashSet<Ref>()
                val a = e.optJSONArray("refs") ?: JSONArray()
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { r -> refs.add(Ref(r.getString("conv"), r.getString("id"))) }
                map[sha] = Entry(e.optLong("size", -1), e.optLong("ts", 0), refs)
            }
        }
    }

    @Synchronized fun add(sha: String, size: Long, conv: String, id: String, ts: Long) {
        val e = map.getOrPut(sha) { Entry(size, ts, LinkedHashSet()) }
        e.refs.add(Ref(conv, id))
        if (ts > e.ts) e.ts = ts
        persist()
    }

    @Synchronized fun contains(sha: String) = map.containsKey(sha)
    @Synchronized fun refs(sha: String): List<Ref> = map[sha]?.refs?.toList() ?: emptyList()
    @Synchronized fun remove(sha: String) { if (map.remove(sha) != null) persist() }
    @Synchronized fun shasNewestFirst(): List<String> = map.entries.sortedByDescending { it.value.ts }.map { it.key }
    @Synchronized fun size() = map.size

    private fun persist() {
        val o = JSONObject()
        for ((sha, e) in map) {
            o.put(sha, JSONObject().put("size", e.size).put("ts", e.ts)
                .put("refs", JSONArray().apply { e.refs.forEach { put(JSONObject().put("conv", it.conv).put("id", it.id)) } }))
        }
        state.write(FILE, o.toString())
    }

    companion object { const val FILE = "devsync-media" }
}
