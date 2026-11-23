package com.oshi.messenger.network.v2.devsync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A transport-neutral byte pipe for ONE session: LAN = one TCP connection, relay = one peer on the
 * shared WebSocket. [send] must not reorder messages; it may queue.
 */
interface DevSyncLink {
    val transport: String
    /** Human-readable endpoint for logs (never logged with content). */
    val label: String
    fun send(kind: Int, noiseMessage: ByteArray)
    fun close()
}

/**
 * One device-to-device session (design §4.1, §6). Every method runs on the engine's sync thread.
 *
 * Lifecycle: HANDSHAKE → (PAIRING →) SYNC → CLOSED.
 */
class DevSyncSession internal constructor(
    private val engine: DevSyncEngine,
    val link: DevSyncLink,
    val initiator: Boolean,
    /** Relay: the peer's routing id, known before the handshake. LAN: null. */
    private val expectedPeerId: String?,
    ephemeralPriv: ByteArray? = null,
) {
    enum class State { HANDSHAKE, PAIRING, SYNC, CLOSED }

    var state = State.HANDSHAKE
        private set
    var peerId: String? = null
        private set
    var peerHello: Hello? = null
        private set
    var peerStatic: ByteArray? = null
        private set
    /** Six-digit comparison code (§4.3), available once the handshake completes. */
    var sasCode: String? = null
        private set
    /** The deviceId of whichever side sent message 1 — the input to the duplicate rule (§5.1). */
    var initiatorId: String? = null
        private set
    val startedAtMs = engine.now()
    var lastActivityMs = startedAtMs
        private set
    var closeReason: String? = null
        private set

    private val prologue: ByteArray = if (link.transport == DevSyncKeys.TRANSPORT_LAN) {
        DevSyncKeys.prologue(DevSyncKeys.TRANSPORT_LAN)
    } else {
        DevSyncKeys.prologue(link.transport, DevSyncCrypto.unhex(engine.deviceId), DevSyncCrypto.unhex(expectedPeerId!!))
    }
    private val hs = NoiseHandshake(initiator, engine.deviceKeyPriv(), engine.psk(), prologue, ephemeralPriv)
    private var sendCipher: TransportCipher? = null
    private var recvCipher: TransportCipher? = null
    private var handshakeTimer: ScheduledFuture<*>? = null

    // ------------------------------------------------------------ flow control (§6.1)
    private var nextSeq = 1L
    private var lastRecvSeq = 0L
    private var recvSinceAck = 0
    private var ackTimer: ScheduledFuture<*>? = null
    /** [liveBody] = the whole LIVE body, set on the LAST fragment of a LIVE record (handover). */
    private class Out(val type: Int, val flags: Int, val body: ByteArray, val liveBody: ByteArray? = null)
    private val outQueue = ArrayDeque<Out>()
    private val unacked = ArrayDeque<LongArray>() // [seq, size]
    private var unackedBytes = 0L
    /** LIVE bodies sent but not yet acknowledged, keyed by the seq of their last fragment. */
    private val liveUnacked = ArrayDeque<Pair<Long, ByteArray>>()

    // ------------------------------------------------------------ LAN liveness (Appendix C.14)
    private var lastSentMs = startedAtMs
    private var keepaliveTimer: ScheduledFuture<*>? = null
    /** The peer sends keepalives (it sent a redundant ACK): its silence then means the LAN is dead. */
    var peerKeepsAlive = false
        private set
    private var lastAckUpTo = -1L
    private val reassembler = DevSyncWire.Reassembler()
    var relayBytesSent = 0L
        private set

    // ------------------------------------------------------------ pairing
    private var locallyApproved = false
    private var peerApproved = false
    private var pairingTimer: ScheduledFuture<*>? = null

    // ------------------------------------------------------------ diff (§6.2)
    private var pendingRequests = 0
    private var summaryReceived = false
    var pullComplete = false
        private set
    private val localIdCache = HashMap<String, Map<String, String>>() // conv -> (idKey -> rev)
    private val remoteConvs = HashMap<String, SyncConversation>()
    // __DEVSYNC_LIVE_UPGRADE_2026_09_23__ near-instant mirroring (§9)
    /** conv -> newest timestamp covered by our last SUMMARY (or LIVE push) to this peer. */
    private val advertisedNewest = HashMap<String, Long>()
    /** conv -> ids the peer gave us that are newer than [advertisedNewest] (never pushed back). */
    private val knownToPeer = HashMap<String, HashSet<String>>()
    private var lastSummaryFp: String? = null
    private var summaryDeferred = false
    private var groupsFp: String? = null
    private var contactsFp: String? = null
    private var profileFp: String? = null
    private val readFp = HashMap<String, String>()
    val stats = SessionStats()

    // ------------------------------------------------------------ media (§6.1, §6.2)
    private val mediaQueue = ArrayDeque<String>()
    private val mediaFailedThisSession = HashSet<String>()
    private var mediaInFlight: MediaFetch? = null
    var mediaComplete = false
        private set
    private class MediaFetch(val sha: String, val file: File, var expectOffset: Long, var chunksLeft: Int, var retried: Boolean)

    data class SessionStats(
        var inserted: Int = 0, var updated: Int = 0, var skippedNoGroup: Int = 0, var invalid: Int = 0,
        var mediaFetched: Int = 0, var mediaBytes: Long = 0, var recordsIn: Int = 0, var recordsOut: Int = 0,
        var maxUnackedObserved: Int = 0,
    )

    // ================================================================== handshake

    fun start() {
        handshakeTimer = engine.schedule(DevSyncEngine.HANDSHAKE_TIMEOUT_MS) { if (state == State.HANDSHAKE) close("handshake-timeout", silent = true) }
        if (initiator) {
            val m1 = hs.writeMessage1(MSG1_PAYLOAD)
            initiatorId = engine.deviceId
            link.send(DevSyncWire.KIND_MSG1, m1)
        }
    }

    /** [kind] 0 = infer from the handshake state (LAN). */
    fun onFrame(kind: Int, noise: ByteArray) {
        if (state == State.CLOSED) return
        lastActivityMs = engine.now()
        try {
            if (sendCipher == null) onHandshake(kind, noise) else {
                if (kind != 0 && kind != DevSyncWire.KIND_TRANSPORT) throw NoiseException("handshake message on an established session")
                onTransport(noise)
            }
        } catch (e: NoiseException) {
            engine.log("session ${link.label}: ${e.message}")
            close("protocol", silent = true)
        } catch (e: Exception) {
            engine.log("session ${link.label}: ${e.javaClass.simpleName}: ${e.message}")
            close("error", silent = true)
        }
    }

    private fun onHandshake(kind: Int, msg: ByteArray) {
        if (initiator) {
            if (kind != 0 && kind != DevSyncWire.KIND_MSG2) throw NoiseException("unexpected handshake kind $kind")
            val hello = Hello.decode(hs.readMessage2(msg)) ?: throw NoiseException("bad HELLO")
            verifyPeer(hs.remoteStatic!!, hello)
            val m3 = hs.writeMessage3(engine.hello().encode())
            link.send(DevSyncWire.KIND_MSG3, m3)
            established()
        } else if (!hsReadMsg1) {
            if (kind != 0 && kind != DevSyncWire.KIND_MSG1) throw NoiseException("unexpected handshake kind $kind")
            hs.readMessage1(msg) // payload {"v":1}: nothing to act on, forward compatible
            hsReadMsg1 = true
            link.send(DevSyncWire.KIND_MSG2, hs.writeMessage2(engine.hello().encode()))
        } else {
            if (kind != 0 && kind != DevSyncWire.KIND_MSG3) throw NoiseException("unexpected handshake kind $kind")
            val hello = Hello.decode(hs.readMessage3(msg)) ?: throw NoiseException("bad HELLO")
            verifyPeer(hs.remoteStatic!!, hello)
            initiatorId = hello.deviceId
            established()
        }
    }
    private var hsReadMsg1 = false

    /** §4.1: deviceId == H(remote static); on the relay it must also equal the routing id. */
    private fun verifyPeer(remoteStatic: ByteArray, hello: Hello) {
        val id = DevSyncKeys.deviceIdHex(remoteStatic)
        if (id != hello.deviceId) throw NoiseException("HELLO deviceId does not match the Noise static key")
        if (expectedPeerId != null && id != expectedPeerId) throw NoiseException("routing id does not match the Noise static key")
        if (id == engine.deviceId) throw NoiseException("connected to ourselves")
        peerId = id
        peerHello = hello
        peerStatic = remoteStatic
        hello.dsk?.let { d -> engine.onPeerDsk?.let { f -> runCatching { f(id, d) } } }
        hello.raw?.let { raw -> engine.onPeerHello?.let { f -> runCatching { f(id, raw) } } }
    }

    private fun established() {
        handshakeTimer?.cancel(false)
        val (s, r) = hs.split(engine.config.rekeyMessages, engine.config.rekeyBytes)
        sendCipher = s
        recvCipher = r
        sasCode = DevSyncKeys.sasCode(hs.handshakeHash)
        engine.onSessionEstablished(this)
    }

    /** Called by the engine once duplicates are resolved. */
    internal fun authorize() {
        if (state == State.CLOSED) return
        val id = peerId!!
        when {
            engine.registry.isRevoked(id) -> {
                sendRecord(DevSyncWire.BYE, JSONObject().put("reason", "revoked"))
                close("revoked")
            }
            engine.registry.isLinked(id) -> beginSync()
            else -> {
                state = State.PAIRING
                pairingTimer = engine.schedule(DevSyncEngine.PAIRING_TIMEOUT_MS) {
                    if (state == State.PAIRING) close("pairing-timeout")
                }
                engine.onPairingNeeded(this)
            }
        }
    }

    // ================================================================== pairing (§4.3)

    /** The local user decided (or the fresh-device rule applied). */
    internal fun localDecision(allow: Boolean) {
        if (state != State.PAIRING) return
        if (!allow) {
            sendRecord(DevSyncWire.APPROVAL, JSONObject().put("approved", false))
            sendRecord(DevSyncWire.BYE, JSONObject().put("reason", "policy"))
            close("denied")
            return
        }
        locallyApproved = true
        sendRecord(DevSyncWire.APPROVAL, approvalBody())
        maybeFinishPairing()
    }

    private var approvalEchoed = false

    private fun approvalBody(): JSONObject {
        val body = JSONObject().put("approved", true)
        // Design §15.3: the approver also signs the new device into the per-device mailbox
        // (`APPROVAL.mailbox`). The core only carries it; the mailbox client produces/consumes it.
        engine.approvalExtras?.let { f ->
            runCatching { f(peerId!!, peerStatic!!) }.getOrNull()?.let { extra ->
                val it = extra.keys()
                while (it.hasNext()) { val k = it.next(); if (k != "approved") body.put(k, extra.get(k)) }
            }
        }
        return body
    }

    private fun onApproval(body: JSONObject) {
        if (state == State.SYNC && body.optBoolean("approved", false) && !approvalEchoed) {
            // We already had the peer linked but it did not have us (it reinstalled, or it was
            // restored): it just approved us, and waits for OUR approval. Give it, and resend the
            // session-start records it dropped while it was still pairing.
            approvalEchoed = true
            sendRecord(DevSyncWire.APPROVAL, approvalBody())
            sendStartRecords()
            return
        }
        if (state != State.PAIRING) return
        if (!body.optBoolean("approved", false)) {
            engine.onPairingRefusedByPeer(this)
            close("refused-by-peer")
            return
        }
        peerApproved = true
        engine.onApprovalReceived?.let { f -> runCatching { f(peerId!!, body) } }
        if (!locallyApproved && engine.acceptsApprovalFromUnknown()) {
            // Fresh device (nothing linked yet): being approved by a device of the account IS the
            // consent the user gave by opening "link this device" (§4.3 step 3).
            engine.linkPeer(this, approvedBy = peerId)
            localDecision(true)
            return
        }
        engine.onPeerApprovedAwaitingLocal(this)
        maybeFinishPairing()
    }

    private fun maybeFinishPairing() {
        if (locallyApproved && peerApproved) {
            pairingTimer?.cancel(false)
            beginSync()
        }
    }

    // ================================================================== sync (§6.2)

    private fun beginSync() {
        state = State.SYNC
        engine.onSyncStarted(this)
        sendStartRecords()
        if (link.transport == DevSyncKeys.TRANSPORT_LAN) {
            // Announce keepalive support at once (two ACKs in a row = a redundant one), so a busy
            // session that never idles long enough for a keepalive is still watched.
            sendAck(); sendAck()
            scheduleKeepalive()
        }
    }

    // ================================================================== LAN liveness (Appendix C.14)

    /**
     * __DEVSYNC_LIVE_UPGRADE_2026_09_23__ An idle LAN session sends a REDUNDANT `ACK{upTo}` (same
     * value as the last one) every [DevSyncConfig.lanKeepaliveMs]: no new record type, harmless to
     * any receiver (cumulative ACK). A peer seen sending one is keepalive-capable; if such a peer is
     * then silent for [DevSyncConfig.lanIdleTimeoutMs] the TCP path is dead (Wi-Fi dropped, NAT,
     * sleep) and the session closes, which falls back to the relay (§5.3).
     */
    private fun scheduleKeepalive() {
        keepaliveTimer?.cancel(false)
        val period = engine.config.lanKeepaliveMs
        keepaliveTimer = engine.schedule(minOf(period, engine.config.lanIdleTimeoutMs / 3).coerceAtLeast(10)) { keepaliveTick() }
    }

    private fun keepaliveTick() {
        if (state == State.CLOSED) return
        val t = engine.now()
        if (peerKeepsAlive && t - lastActivityMs >= engine.config.lanIdleTimeoutMs) {
            engine.log("session ${link.label}: LAN silent for ${(t - lastActivityMs) / 1000}s, closing")
            close("idle", silent = true)
            return
        }
        if (t - lastSentMs >= engine.config.lanKeepaliveMs - 5) sendAck()
        scheduleKeepalive()
    }

    /** A keepalive-capable LAN peer that has been silent for two keepalive periods (see [keepaliveTick]). */
    internal fun lanStale(): Boolean =
        link.transport == DevSyncKeys.TRANSPORT_LAN && state != State.CLOSED && peerKeepsAlive &&
            engine.now() - lastActivityMs > 2 * engine.config.lanKeepaliveMs

    // ================================================================== transport handover (Appendix C.11)

    /**
     * The LIVE records this session queued or sent without an ACK: when a LAN session replaces it
     * (or it replaces a dead LAN), they are re-sent on the survivor, so a message mirrored during
     * the switch is not left waiting for the next diff. Everything else in flight is diff traffic
     * the new session recomputes from the stores; partial media resumes from the partial file.
     */
    internal fun takeInFlightLive(): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        liveUnacked.forEach { out += it.second }
        outQueue.forEach { o -> o.liveBody?.let { out += it } }
        liveUnacked.clear()
        return out
    }

    internal fun adoptLive(bodies: List<ByteArray>) {
        if (state != State.SYNC) return
        for (b in bodies) enqueue(DevSyncWire.LIVE, b)
        pump()
    }

    /** DEVICES, GROUPS, CONTACTS, PROFILE, READ_STATE, then SUMMARY (groups before any group message). */
    private fun sendStartRecords() {
        val peer = peerId!!
        sendRecord(DevSyncWire.DEVICES, engine.registry.devicesRecordFor(peer, engine.selfEntry()).toJson())
        groupsFp = null; contactsFp = null; profileFp = null; readFp.clear()
        sendChangedState()
        sendSummary()
        queuePendingMedia()
    }

    /**
     * Re-run the diff on an open session. Default = "Sync now" (everything re-read, SUMMARY always
     * sent). __DEVSYNC_LIVE_UPGRADE_2026_09_23__ The live path passes the changed conversations
     * (null = any), [deferWhilePulling] and [skipUnchanged]:
     *  1. messages newer than what our last SUMMARY covered are pushed at once as `LIVE` (one hop);
     *  2. GROUPS / CONTACTS / PROFILE / READ_STATE are re-sent when they changed since last sent;
     *  3. SUMMARY is re-sent (unless identical to the last one) so the peer pulls edits, statuses,
     *     reactions and anything the push did not cover. While OUR pull is still running, 2-3 wait
     *     for its end (applying a big pull must not restart the diff every burst).
     */
    internal fun resync(convs: Collection<String>? = null, deferWhilePulling: Boolean = false, skipUnchanged: Boolean = false) {
        if (state != State.SYNC) return
        if (convs == null) invalidate() else convs.forEach { invalidate(it) }
        pushNewSinceSummary(convs)
        if (deferWhilePulling && summaryReceived && !pullComplete) { summaryDeferred = true; return }
        sendChangedState()
        sendSummary(skipUnchanged)
    }

    /** State records whose content changed since this session last sent them (idempotent merges). */
    private fun sendChangedState() {
        val groups = engine.store.groups()
        val gfp = fingerprint(groups.map { it.toJson() })
        if (gfp != groupsFp) {
            if (groups.isNotEmpty() || groupsFp != null) sendJsonBatches(DevSyncWire.GROUPS, "groups", groups.map { it.toJson() })
            groupsFp = gfp
        }
        val contacts = engine.store.contacts()
        val cfp = fingerprint(contacts.map { it.toJson() })
        if (cfp != contactsFp) {
            if (contacts.isNotEmpty() || contactsFp != null) sendJsonBatches(DevSyncWire.CONTACTS, "contacts", contacts.map { it.toJson() })
            contactsFp = cfp
        }
        engine.store.profile()?.toJson()?.let { p ->
            val pfp = fingerprint(listOf(p))
            if (pfp != profileFp) { sendRecord(DevSyncWire.PROFILE, p); profileFp = pfp }
        }
        readMemo = null
        for (rs in engine.store.readStates()) {
            val o = rs.toJson()
            val fp = o.toString()
            if (readFp[rs.conv] != fp) { sendRecord(DevSyncWire.READ_STATE, o); readFp[rs.conv] = fp }
        }
    }

    private fun fingerprint(items: List<JSONObject>): String =
        DevSyncCrypto.hex(DevSyncCrypto.sha256(items.joinToString("\n") { it.toString() }.toByteArray(Charsets.UTF_8)))

    private fun sendSummary(skipUnchanged: Boolean = false) {
        val arr = JSONArray()
        val newest = HashMap<String, Long>()
        for (c in allowedConversations()) {
            val msgs = msgs(c.id)
            if (msgs.isEmpty()) continue
            val n = msgs.maxOf { it.timestampMs }
            newest[c.id] = n
            arr.put(JSONObject().put("id", c.id).put("kind", c.kind).put("count", msgs.size)
                .put("newest", SyncJson.iso(n))
                .put("digest", SyncCodec.digest(msgs.map { it.id to SyncCodec.rev(it) }))
                .apply { c.peerPublicKey?.let { put("peerPublicKey", it) }; c.groupId?.let { put("groupId", it) }; c.nativeId?.let { put("nativeId", it) } })
        }
        val body = JSONObject().put("conversations", arr)
        val fp = fingerprint(listOf(body))
        for ((conv, n) in newest) {
            if (n >= (advertisedNewest[conv] ?: Long.MIN_VALUE)) { advertisedNewest[conv] = n; knownToPeer.remove(conv) }
        }
        if (skipUnchanged && fp == lastSummaryFp) return
        lastSummaryFp = fp
        sendRecord(DevSyncWire.SUMMARY, body)
    }

    /**
     * One-hop mirroring: messages newer than the newest this peer was told about (and that did not
     * come FROM this peer) go out as `LIVE` now. Older arrivals, edits, statuses and reactions travel
     * through the SUMMARY diff. A large backlog (> [LIVE_PUSH_MAX]) is left to the diff.
     */
    private fun pushNewSinceSummary(convs: Collection<String>?) {
        for (c in allowedConversations()) {
            if (convs != null && c.id !in convs) continue
            val adv = advertisedNewest[c.id] ?: Long.MIN_VALUE
            val known = knownToPeer[c.id]
            val fresh = msgs(c.id).filter { it.timestampMs > adv && (known == null || it.idKey !in known) }
            if (fresh.isEmpty() || fresh.size > LIVE_PUSH_MAX) continue
            sendMessages(DevSyncWire.LIVE, c, fresh.sortedBy { it.timestampMs }, replyToWant = false)
            advertisedNewest[c.id] = fresh.maxOf { it.timestampMs }
        }
    }

    private fun allowedConversations(): List<SyncConversation> =
        engine.store.conversations().filter { conversationAllowed(it.kind) }

    private fun conversationAllowed(kind: String): Boolean = when (kind) {
        SyncConversation.KIND_DIRECT, SyncConversation.KIND_GROUP -> true
        // §7: desktop-only threads travel only desktop <-> desktop.
        SyncConversation.KIND_DESKTOP_LOCAL -> engine.config.platform == "desktop" && peerHello?.platform == "desktop"
        else -> false
    }

    /** Messages of a conversation, cached for the session and invalidated by every local write. */
    private val msgCache = HashMap<String, List<SyncMessage>>()
    private fun msgs(conv: String): List<SyncMessage> = msgCache.getOrPut(conv) { engine.store.messages(conv) }
    private fun invalidate(conv: String? = null) {
        if (conv == null) { msgCache.clear(); localIdCache.clear() } else { msgCache.remove(conv); localIdCache.remove(conv) }
    }

    private fun localRevs(conv: String): Map<String, String> = localIdCache.getOrPut(conv) {
        msgs(conv).associate { it.idKey to SyncCodec.rev(it) }
    }

    private fun localBuckets(conv: String): Map<String, Pair<Int, String>> {
        val byDay = msgs(conv).groupBy { SyncJson.utcDay(it.timestampMs) }
        return byDay.mapValues { (_, ms) -> ms.size to SyncCodec.digest(ms.map { it.id to SyncCodec.rev(it) }) }
    }

    private fun onSummary(body: JSONObject) {
        summaryReceived = true
        pullComplete = false
        val local = HashMap<String, String>()
        for (c in allowedConversations()) {
            val msgs = msgs(c.id)
            if (msgs.isNotEmpty()) local[c.id] = SyncCodec.digest(msgs.map { it.id to SyncCodec.rev(it) })
        }
        val convs = body.optJSONArray("conversations") ?: JSONArray()
        val rows = (0 until convs.length()).mapNotNull { convs.optJSONObject(it) }
            .sortedByDescending { it.optString("newest", "") }
        for (o in rows) {
            val conv = SyncCodec.conversationFromJson(o) ?: continue
            if (!conversationAllowed(conv.kind)) continue
            remoteConvs[conv.id] = conv
            if (local[conv.id] == o.optString("digest")) continue
            sendRecord(DevSyncWire.BUCKETS_REQ, JSONObject().put("conv", conv.id))
            pendingRequests++
        }
        checkPullDone()
    }

    private fun onBucketsReq(body: JSONObject) {
        val conv = body.optString("conv")
        val arr = JSONArray()
        if (isLocalConversationAllowed(conv)) {
            for ((day, v) in localBuckets(conv).toSortedMap(compareByDescending { it })) {
                arr.put(JSONObject().put("day", day).put("count", v.first).put("digest", v.second))
            }
        }
        sendRecord(DevSyncWire.BUCKETS, JSONObject().put("conv", conv).put("buckets", arr))
    }

    private fun isLocalConversationAllowed(conv: String): Boolean =
        allowedConversations().any { it.id == conv }

    private fun onBuckets(body: JSONObject) {
        pendingRequests = maxOf(0, pendingRequests - 1)
        val conv = body.optString("conv")
        val local = localBuckets(conv)
        val arr = body.optJSONArray("buckets") ?: JSONArray()
        val rows = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.sortedByDescending { it.optString("day") }
        for (b in rows) {
            val day = b.optString("day")
            if (local[day]?.second == b.optString("digest")) continue
            sendRecord(DevSyncWire.IDS_REQ, JSONObject().put("conv", conv).put("day", day))
            pendingRequests++
        }
        checkPullDone()
    }

    private fun onIdsReq(body: JSONObject) {
        val conv = body.optString("conv")
        val day = body.optString("day")
        val items = JSONArray()
        if (isLocalConversationAllowed(conv)) {
            msgs(conv).filter { SyncJson.utcDay(it.timestampMs) == day }
                .sortedWith { a, b -> CanonicalJson.UTF8_ORDER.compare(a.idKey, b.idKey) }
                .forEach { items.put(JSONArray().put(it.id).put(SyncCodec.rev(it))) }
        }
        sendRecord(DevSyncWire.IDS, JSONObject().put("conv", conv).put("day", day).put("items", items))
    }

    private fun onIds(body: JSONObject) {
        pendingRequests = maxOf(0, pendingRequests - 1)
        val conv = body.optString("conv")
        val have = localRevs(conv)
        val items = body.optJSONArray("items") ?: JSONArray()
        val want = ArrayList<String>()
        for (i in 0 until items.length()) {
            val pair = items.optJSONArray(i) ?: continue
            val id = pair.optString(0, "")
            if (id.isEmpty()) continue
            val rev = pair.optString(1, "")
            val mine = have[SyncJson.asciiLower(id)]
            if (mine == null || mine != rev) want += id
        }
        for (chunk in want.chunked(MAX_WANT)) {
            sendRecord(DevSyncWire.WANT, JSONObject().put("conv", conv).put("ids", JSONArray(chunk)))
            pendingRequests++
        }
        checkPullDone()
    }

    private fun onWant(body: JSONObject) {
        val conv = body.optString("conv", "")
        val idsArr = body.optJSONArray("ids") ?: JSONArray()
        val ids = (0 until minOf(idsArr.length(), MAX_WANT)).map { idsArr.optString(it, "") }.filter { it.isNotEmpty() }
        val allowed = allowedConversations()
        // `conv` is recommended; the design's original `{ids}` form (no conv) is still answered.
        val targets = if (conv.isNotEmpty()) allowed.filter { it.id == conv } else allowed
        val found = targets.map { it to engine.store.messagesByIds(it.id, ids) }.filter { it.second.isNotEmpty() }
        if (found.isEmpty()) {
            sendMessages(DevSyncWire.MESSAGES, targets.firstOrNull(), emptyList(), replyToWant = true)
            return
        }
        found.forEachIndexed { i, (c, msgs) -> sendMessages(DevSyncWire.MESSAGES, c, msgs, replyToWant = true, moreAfter = i < found.size - 1) }
    }

    /** MESSAGES replies are flushed at ~48 KiB; all but the last carry `more:true`. */
    private fun sendMessages(type: Int, conv: SyncConversation?, msgs: List<SyncMessage>, replyToWant: Boolean, moreAfter: Boolean = false) {
        if (conv == null || msgs.isEmpty()) {
            if (replyToWant) sendRecord(type, SyncCodec.encodeBatch(conv?.let { listOf(it) } ?: emptyList(), emptyList()))
            return
        }
        val batches = ArrayList<List<SyncMessage>>()
        var cur = ArrayList<SyncMessage>()
        var curBytes = 0
        for (m in msgs) {
            val size = SyncCodec.messageToJson(m).toString().length
            if (cur.isNotEmpty() && curBytes + size > BATCH_TARGET) { batches += cur; cur = ArrayList(); curBytes = 0 }
            cur += m
            curBytes += size
        }
        if (cur.isNotEmpty()) batches += cur
        batches.forEachIndexed { i, b ->
            val o = SyncCodec.encodeBatch(listOf(conv), b)
            if (replyToWant && (i < batches.size - 1 || moreAfter)) o.put("more", true)
            sendRecord(type, o)
        }
    }

    private fun onMessages(body: JSONObject, live: Boolean) {
        applyBatch(body)
        if (!live && !body.optBoolean("more", false)) {
            pendingRequests = maxOf(0, pendingRequests - 1)
            checkPullDone()
        } else if (live) {
            pumpMedia()
        }
    }

    private fun applyBatch(body: JSONObject) {
        val batch = SyncCodec.decodeBatch(body)
        stats.invalid += batch.invalid
        for ((convId, msgs) in batch.messages.groupBy { it.conversation }) {
            val conv = batch.conversations[convId] ?: continue
            if (!conversationAllowed(conv.kind)) continue
            val localById = engine.store.messagesByIds(convId, msgs.map { it.id }).associateBy { it.idKey }
            val inserts = ArrayList<SyncMessage>()
            val updates = ArrayList<SyncMessage>()
            val adv = advertisedNewest[convId] ?: Long.MIN_VALUE
            for (m in msgs) if (m.timestampMs > adv) knownToPeer.getOrPut(convId) { HashSet() }.add(m.idKey)
            for (m in msgs) {
                val local = localById[m.idKey]
                if (local == null) inserts += m else {
                    val merged = SyncMerge.message(local, m)
                    if (SyncCodec.rev(merged) != SyncCodec.rev(local)) updates += merged
                }
            }
            if (inserts.isEmpty() && updates.isEmpty()) continue
            val r = engine.store.apply(conv, inserts, updates)
            stats.inserted += r.inserted
            stats.updated += r.updated
            stats.skippedNoGroup += r.skippedNoGroup
            invalidate(convId)
            if (r.skippedNoGroup == 0) {
                for (m in inserts) {
                    val md = m.media ?: continue
                    val sha = md.sha256 ?: continue
                    if (md.omitted != null || m.viewOnce || m.deleted) continue
                    engine.pendingMedia.add(sha, md.size ?: -1, convId, m.id, m.timestampMs)
                }
            }
            engine.onApplied(this, conv, r)
        }
        queuePendingMedia()
    }

    private fun checkPullDone() {
        if (!summaryReceived || pendingRequests > 0 || pullComplete) return
        pullComplete = true
        engine.onPullComplete(this)
        pumpMedia()
        if (summaryDeferred && state == State.SYNC) {
            summaryDeferred = false
            invalidate()
            sendChangedState()
            sendSummary(skipUnchanged = true)
        }
    }

    // ================================================================== contacts / groups / profile / read

    private fun onGroups(body: JSONObject) {
        val arr = body.optJSONArray("groups") ?: return
        val local = engine.store.groups().associateBy { it.groupId }
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i)?.let(SyncGroup::fromJson) ?: continue
            val mine = local[g.groupId]
            val merged = SyncMerge.group(mine, g)
            if (mine == null || merged.canonicalJson() != mine.canonical().canonicalJson()) engine.store.putGroup(merged)
            // The group picture travels by reference, like message media.
            val av = merged.avatar
            if (av != null && av.sha256 != mine?.avatar?.sha256 && engine.store.openMedia(av.sha256) == null) {
                engine.pendingMedia.add(av.sha256, av.size, merged.conversationId, DevSyncStore.AVATAR_REF, merged.updatedAtMs)
            }
        }
        invalidate()
        // __DEVSYNC_MEDIA_NOW_2026_09_23__ a new picture that arrives mid-session (C.15 re-send) is
        // requested on THIS session now, not at the next session start (the queue used to be filled
        // only by sendStartRecords / applyBatch). Before the pull completes it just waits in the queue.
        queueNewPendingMedia()
    }

    private fun onContacts(body: JSONObject) {
        val arr = body.optJSONArray("contacts") ?: return
        val local = engine.store.contacts().associateBy { it.publicKey }
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i)?.let(SyncContact::fromJson) ?: continue
            val mine = local[c.publicKey]
            val merged = SyncMerge.contact(mine ?: SyncContact(c.publicKey), c)
            if (merged != mine && (mine != null || merged != SyncContact(c.publicKey))) engine.store.putContact(merged)
        }
    }

    private fun onProfile(body: JSONObject) {
        val p = SyncProfile.fromJson(body) ?: return
        val mine = engine.store.profile()
        val merged = SyncMerge.profile(mine, p)
        if (merged != null && merged !== mine) engine.store.putProfile(merged)
        // __DEVSYNC_MEDIA_NOW_2026_09_23__ putProfile may have queued the new profile photo
        // (store → engine.pendingMedia): fetch it on this session now.
        queueNewPendingMedia()
    }

    /**
     * __DEVSYNC_READ_FRESH_2026_09_23__ The peer sends one READ_STATE per conversation: the local
     * states are read ONCE per burst and kept here ([sendChangedState] drops the memo), instead of a
     * store-side TTL cache that made a local read made within 3 s of the previous pass wait for the
     * 30 s sweep before it was mirrored.
     */
    private var readMemo: HashMap<String, SyncReadState>? = null

    private fun onReadState(body: JSONObject) {
        val r = SyncReadState.fromJson(body) ?: return
        val memo = readMemo ?: HashMap<String, SyncReadState>().also { m -> engine.store.readStates().forEach { m[it.conv] = it }; readMemo = m }
        val mine = memo[r.conv]
        val merged = if (mine == null) r else SyncMerge.readState(mine, r)
        if (merged != mine) { engine.store.putReadState(merged); memo[r.conv] = merged }
    }

    private fun onDevices(body: JSONObject) {
        if (state != State.SYNC) return
        if (engine.registry.mergeDevicesRecord(DevicesRecord.fromJson(body), peerId!!)) engine.onDevicesChanged()
    }

    // ================================================================== media

    private fun mediaAllowed(): Boolean = engine.mediaAllowedWith(peerHello, link.transport)

    private fun queuePendingMedia() {
        if (state != State.SYNC) return
        for (sha in engine.pendingMedia.shasNewestFirst()) {
            if (sha !in mediaQueue && sha !in mediaFailedThisSession && mediaInFlight?.sha != sha) mediaQueue.add(sha)
        }
        mediaComplete = false
        if (pullComplete) pumpMedia()
    }

    /** [queuePendingMedia] only when something new is pending (no spurious onMediaComplete). */
    private fun queueNewPendingMedia() {
        if (state != State.SYNC) return
        val fresh = engine.pendingMedia.shasNewestFirst().any {
            it !in mediaQueue && it !in mediaFailedThisSession && mediaInFlight?.sha != it
        }
        if (fresh) queuePendingMedia()
    }

    private fun pumpMedia() {
        if (state != State.SYNC || mediaInFlight != null) return
        if (!mediaAllowed()) { mediaQueue.clear(); finishMedia(); return }
        while (true) {
            val sha = mediaQueue.pollFirst() ?: run { finishMedia(); return }
            if (!engine.pendingMedia.contains(sha)) continue
            val file = engine.partialFile(sha)
            val offset = if (file.isFile) file.length() else 0L
            mediaInFlight = MediaFetch(sha, file, offset, MEDIA_CHUNKS_PER_GET, sha in retriedOnce)
            sendRecord(DevSyncWire.MEDIA_GET, JSONObject().put("sha256", sha).put("offset", offset).put("count", MEDIA_CHUNKS_PER_GET))
            return
        }
    }

    private fun finishMedia() {
        if (!mediaComplete) {
            mediaComplete = true
            engine.onMediaComplete(this)
        }
    }

    private fun onMediaGet(body: JSONObject) {
        val sha = body.optString("sha256").lowercase(java.util.Locale.ROOT)
        val offset = body.optLong("offset", 0L)
        val count = body.optInt("count", MEDIA_CHUNKS_PER_GET).coerceIn(1, 64)
        if (!engine.mediaAllowedWith(peerHello, link.transport)) return mediaErr(sha, "policy")
        val src = engine.store.openMedia(sha) ?: return mediaErr(sha, "notfound")
        val size = src.size
        if (offset < 0 || offset > size) return mediaErr(sha, "notfound")
        val shaBytes = runCatching { DevSyncCrypto.unhex(sha) }.getOrNull()?.takeIf { it.size == 32 } ?: return mediaErr(sha, "notfound")
        src.open().use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val s = input.skip(offset - skipped)
                if (s <= 0) { if (input.read() < 0) break else skipped++ } else skipped += s
            }
            var pos = offset
            var sent = 0
            val buf = ByteArray(DevSyncWire.MEDIA_CHUNK_DATA)
            if (size == 0L || pos == size) {
                sendBinary(DevSyncWire.MEDIA_CHUNK, DevSyncWire.encodeMediaChunk(shaBytes, pos, true, ByteArray(0)))
                return
            }
            while (sent < count && pos < size) {
                var n = 0
                val want = minOf(buf.size.toLong(), size - pos).toInt()
                while (n < want) {
                    val r = input.read(buf, n, want - n)
                    if (r < 0) break
                    n += r
                }
                if (n <= 0) break
                val last = pos + n >= size
                sendBinary(DevSyncWire.MEDIA_CHUNK, DevSyncWire.encodeMediaChunk(shaBytes, pos, last, buf.copyOf(n)))
                pos += n
                sent++
            }
        }
    }

    private fun mediaErr(sha: String, reason: String) {
        sendRecord(DevSyncWire.MEDIA_ERR, JSONObject().put("sha256", sha).put("reason", reason))
    }

    private fun onMediaChunk(body: ByteArray) {
        val c = DevSyncWire.decodeMediaChunk(body)
        val f = mediaInFlight ?: return
        if (DevSyncCrypto.hex(c.sha256) != f.sha) return
        if (c.offset != f.expectOffset) {
            // Out of step (e.g. a stale partial): restart this file from the partial's real size.
            mediaInFlight = null
            mediaQueue.addFirst(f.sha)
            pumpMedia()
            return
        }
        f.file.parentFile?.mkdirs()
        RandomAccessFile(f.file, "rw").use { raf ->
            raf.setLength(c.offset)
            raf.seek(c.offset)
            raf.write(c.data)
        }
        f.expectOffset += c.data.size
        stats.mediaBytes += c.data.size
        f.chunksLeft--
        if (c.last) {
            completeMedia(f)
        } else if (f.chunksLeft <= 0) {
            f.chunksLeft = MEDIA_CHUNKS_PER_GET
            sendRecord(DevSyncWire.MEDIA_GET, JSONObject().put("sha256", f.sha).put("offset", f.expectOffset).put("count", MEDIA_CHUNKS_PER_GET))
        }
    }

    private fun completeMedia(f: MediaFetch) {
        mediaInFlight = null
        val actual = DevSyncCrypto.hex(sha256File(f.file))
        if (actual != f.sha) {
            f.file.delete()
            if (!f.retried) {
                // §6.2: verified at the end; on a mismatch the file is discarded and restarted once.
                mediaQueue.addFirst(f.sha)
                retriedOnce.add(f.sha)
            } else {
                mediaFailedThisSession.add(f.sha)
            }
            pumpMedia()
            return
        }
        for (ref in engine.pendingMedia.refs(f.sha)) {
            engine.store.attachMedia(ref.conv, ref.id, f.sha, f.file)
        }
        engine.pendingMedia.remove(f.sha)
        f.file.delete()
        stats.mediaFetched++
        engine.onMediaFetched(this, f.sha)
        pumpMedia()
    }
    private val retriedOnce = HashSet<String>()

    private fun onMediaErr(body: JSONObject) {
        val sha = body.optString("sha256")
        val f = mediaInFlight
        if (f != null && f.sha == sha) {
            mediaInFlight = null
            mediaFailedThisSession.add(sha)
            if (body.optString("reason") == "viewOnce") engine.pendingMedia.remove(sha)
            pumpMedia()
        }
    }

    private fun sha256File(file: File): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }

    // ================================================================== LIVE (§9)

    internal fun sendLive(conv: SyncConversation, msgs: List<SyncMessage>) {
        if (state != State.SYNC || !conversationAllowed(conv.kind)) return
        invalidate(conv.id)
        sendMessages(DevSyncWire.LIVE, conv, msgs, replyToWant = false)
    }

    // ================================================================== records in

    private fun onTransport(noise: ByteArray) {
        val plain = recvCipher!!.decrypt(noise)
        val r = DevSyncWire.decodeRecord(plain)
        if (DevSyncWire.isControl(r.type)) {
            if (r.seq != 0L) throw NoiseException("control record with a sequence number")
        } else {
            if (r.seq != lastRecvSeq + 1) throw NoiseException("record out of sequence")
            lastRecvSeq = r.seq
            stats.recordsIn++
            recvSinceAck++
            if (recvSinceAck >= ACK_EVERY) sendAck() else if (ackTimer == null) {
                ackTimer = engine.schedule(ACK_DELAY_MS) { ackTimer = null; if (recvSinceAck > 0) sendAck() }
            }
        }
        val body = reassembler.push(r) ?: return
        dispatch(r.type, body)
    }

    private fun dispatch(type: Int, body: ByteArray) {
        when (type) {
            DevSyncWire.ACK -> onAck(json(body))
            DevSyncWire.BYE -> {
                val reason = json(body).optString("reason", "done")
                if (reason == "revoked" && state == State.SYNC) engine.onRevokedByPeer(this)
                close("bye:$reason", silent = true)
            }
            DevSyncWire.APPROVAL -> onApproval(json(body))
            else -> {
                // Everything else requires a completed pairing. One exception: DEVICES is only ever
                // sent by a peer that already has US linked, so while we are pairing it counts as
                // that peer's approval (a device that lost its local list re-links without a prompt
                // on the side that still trusts it; a non-fresh device still asks its user).
                if (state == State.PAIRING && type == DevSyncWire.DEVICES) {
                    onApproval(JSONObject().put("approved", true))
                    return
                }
                if (state != State.SYNC) return
                when (type) {
                    DevSyncWire.DEVICES -> onDevices(json(body))
                    DevSyncWire.SUMMARY_REQ -> sendSummary()
                    DevSyncWire.SUMMARY -> onSummary(json(body))
                    DevSyncWire.BUCKETS_REQ -> onBucketsReq(json(body))
                    DevSyncWire.BUCKETS -> onBuckets(json(body))
                    DevSyncWire.IDS_REQ -> onIdsReq(json(body))
                    DevSyncWire.IDS -> onIds(json(body))
                    DevSyncWire.WANT -> onWant(json(body))
                    DevSyncWire.MESSAGES -> onMessages(json(body), live = false)
                    DevSyncWire.LIVE -> onMessages(json(body), live = true)
                    DevSyncWire.CONTACTS -> onContacts(json(body))
                    DevSyncWire.GROUPS -> onGroups(json(body))
                    DevSyncWire.PROFILE -> onProfile(json(body))
                    DevSyncWire.READ_STATE -> onReadState(json(body))
                    DevSyncWire.MEDIA_GET -> onMediaGet(json(body))
                    DevSyncWire.MEDIA_CHUNK -> onMediaChunk(body)
                    DevSyncWire.MEDIA_ERR -> onMediaErr(json(body))
                    else -> Unit // unknown record types are ignored (forward compatibility)
                }
            }
        }
    }

    private fun json(body: ByteArray): JSONObject =
        try { JSONObject(String(body, Charsets.UTF_8)) } catch (e: Exception) { throw NoiseException("record body is not JSON") }

    // ================================================================== records out

    private fun sendJsonBatches(type: Int, key: String, items: List<JSONObject>) {
        var arr = JSONArray()
        var size = 0
        for (o in items) {
            val s = o.toString().length
            if (arr.length() > 0 && size + s > BATCH_TARGET) {
                sendRecord(type, JSONObject().put(key, arr)); arr = JSONArray(); size = 0
            }
            arr.put(o); size += s
        }
        if (arr.length() > 0) sendRecord(type, JSONObject().put(key, arr))
    }

    internal fun sendRecord(type: Int, body: JSONObject) = sendBinary(type, body.toString().toByteArray(Charsets.UTF_8))

    private fun sendBinary(type: Int, body: ByteArray) {
        if (state == State.CLOSED || sendCipher == null) return
        if (DevSyncWire.isControl(type)) {
            transmit(DevSyncWire.encodeRecord(type, 0, 0, body))
            return
        }
        enqueue(type, body)
        pump()
    }

    private fun enqueue(type: Int, body: ByteArray) {
        val parts = DevSyncWire.fragment(body)
        parts.forEachIndexed { i, (flags, part) ->
            outQueue.add(Out(type, flags, part, if (type == DevSyncWire.LIVE && i == parts.size - 1) body else null))
        }
    }

    private fun pump() {
        while (outQueue.isNotEmpty() && state != State.CLOSED) {
            val next = outQueue.peekFirst()!!
            val size = DevSyncWire.RECORD_HEADER + next.body.size
            if (unacked.size >= engine.config.windowRecords || (unacked.isNotEmpty() && unackedBytes + size > engine.config.windowBytes)) return
            outQueue.pollFirst()
            val seq = nextSeq++
            unacked.add(longArrayOf(seq, size.toLong()))
            unackedBytes += size
            stats.recordsOut++
            if (unacked.size > stats.maxUnackedObserved) stats.maxUnackedObserved = unacked.size
            next.liveBody?.let { liveUnacked.add(seq to it) }
            transmit(DevSyncWire.encodeRecord(next.type, next.flags, seq, next.body))
        }
        if (outQueue.isEmpty()) onOutboundDrained()
    }

    private fun transmit(record: ByteArray) {
        lastSentMs = engine.now()
        val ct = sendCipher!!.encrypt(record)
        if (link.transport == DevSyncKeys.TRANSPORT_RELAY) {
            relayBytesSent += ct.size
            if (relayBytesSent > engine.config.relaySessionCapBytes && state == State.SYNC) {
                // §10: per-session soft cap over the relay; resume next time.
                link.send(DevSyncWire.KIND_TRANSPORT, ct)
                val bye = sendCipher!!.encrypt(DevSyncWire.encodeRecord(DevSyncWire.BYE, 0, 0, JSONObject().put("reason", "policy").toString().toByteArray()))
                link.send(DevSyncWire.KIND_TRANSPORT, bye)
                close("relay-cap", silent = true)
                return
            }
        }
        link.send(DevSyncWire.KIND_TRANSPORT, ct)
    }

    private fun sendAck() {
        ackTimer?.cancel(false)
        ackTimer = null
        recvSinceAck = 0
        sendBinary(DevSyncWire.ACK, JSONObject().put("upTo", lastRecvSeq).toString().toByteArray(Charsets.UTF_8))
    }

    private fun onAck(body: JSONObject) {
        val upTo = body.optLong("upTo", 0L)
        // A normal ACK always advances; the same value twice is a keepalive (Appendix C.14).
        if (upTo == lastAckUpTo || (lastAckUpTo < 0 && upTo == 0L)) peerKeepsAlive = true
        lastAckUpTo = upTo
        while (unacked.isNotEmpty() && unacked.peekFirst()!![0] <= upTo) {
            unackedBytes -= unacked.pollFirst()!![1]
        }
        while (liveUnacked.isNotEmpty() && liveUnacked.peekFirst()!!.first <= upTo) liveUnacked.pollFirst()
        pump()
    }

    private var closeAfterDrain: String? = null
    private fun onOutboundDrained() {
        val reason = closeAfterDrain ?: return
        if (unacked.isNotEmpty()) return
        closeAfterDrain = null
        sendRecord(DevSyncWire.BYE, JSONObject().put("reason", reason))
        close(reason)
    }

    /** BYE once everything queued has been sent and acknowledged. */
    internal fun closeGracefully(reason: String) {
        if (state == State.CLOSED) return
        if (sendCipher == null || state != State.SYNC) { close(reason); return }
        closeAfterDrain = reason
        if (outQueue.isEmpty() && unacked.isEmpty()) onOutboundDrained()
    }

    val outboundIdle: Boolean get() = outQueue.isEmpty() && unacked.isEmpty()

    // ================================================================== close

    fun close(reason: String, silent: Boolean = false) {
        if (state == State.CLOSED) return
        state = State.CLOSED
        closeReason = reason
        handshakeTimer?.cancel(false)
        ackTimer?.cancel(false)
        pairingTimer?.cancel(false)
        keepaliveTimer?.cancel(false)
        // Keep the queued LIVE bodies for a handover ([takeInFlightLive] after a BYE{lan}).
        outQueue.forEach { o -> o.liveBody?.let { liveUnacked.add(Long.MAX_VALUE to it) } }
        outQueue.clear()
        runCatching { link.close() }
        engine.onSessionClosed(this, reason)
    }

    companion object {
        val MSG1_PAYLOAD: ByteArray = "{\"v\":1}".toByteArray(Charsets.UTF_8)
        const val MAX_WANT = 256
        const val BATCH_TARGET = 48 * 1024
        const val ACK_EVERY = 8
        const val ACK_DELAY_MS = 250L
        const val MEDIA_CHUNKS_PER_GET = 16
        /** Above this many new messages in one conversation the one-hop push yields to the diff. */
        const val LIVE_PUSH_MAX = 256
    }
}
