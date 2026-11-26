package com.oshi.messenger.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/**
 * VPS UDP audio relay client (port 8089).
 *
 * Wire format (matches OSHI/UDPRelayClient.swift and the WebSocket binary
 * protocol in VPSClient.buildBinaryFrame):
 *     [type(1)][recipientKeyLen(1)][recipientKey][senderKeyLen(1)][senderKey][callIdLen(1)][callId][payload]
 *     types: 0x01 = audio, 0x02 = video, 0x04 = register, 0x05 = ping
 *
 * Public keys and the callId travel as base64url UTF-8 strings — same
 * encoding the existing WebSocket transport uses (publicKeyToBase64url in
 * VPSClient.kt). base64url(32-byte X25519) is ~43 chars, callId (UUID) is
 * 36 chars, both well under the 255-byte single-byte length cap the
 * protocol fixes.
 *
 * The server expires UDP endpoints after 30 s of inactivity, so we re-send
 * the register packet every 10 s while the call is up. Inbound bytes pipe
 * into [onAudioPacket] / [onVideoPacket] (the same callbacks that the WS
 * transport ultimately feeds into handleAudioData / video receivers).
 *
 * __CALL_MEDIA_AUTH_2026_09_23__ (docs/CALL_MEDIA_RELAY_AUTH_CONTRACT.md v1.0): every
 * register 0x04 carries the 44-byte ORA1 trailer of the call's relay token; `04 01` is the
 * only thing that marks us registered; `04 00 code` nacks are handled per §5; once a token
 * was held or a nack seen, a token-less register is never sent again.
 */
class UDPRelayClient {

    companion object {
        private const val TAG = "UDPRelayClient"

        const val DEFAULT_HOST = "45.67.216.197"
        const val DEFAULT_PORT = 8089

        /** Server expires endpoints after 30 s — register every 10 s. */
        private const val HEARTBEAT_INTERVAL_MS = 10_000L

        /** Receive buffer ceiling — well above any audio/video frame we send. */
        private const val RX_BUFFER_BYTES = 4096

        // Wire-format type bytes from VPS_CONFIGURATION.md.
        private const val TYPE_AUDIO: Byte = 0x01
        private const val TYPE_VIDEO: Byte = 0x02
        private const val TYPE_REGISTER: Byte = 0x04
        private const val TYPE_PING: Byte = 0x05

        /** Token refetches allowed per call for nack 01/04 (the endpoint is 30/min per identity). */
        private const val MAX_TOKEN_REFETCHES = 4

        /**
         * Parse a server → client frame `[type][senderKeyLen][senderKey][callIdLen][callId][payload]`.
         * Null when truncated. The sender and callId are the ones the relay stamped: the
         * relay only forwards between two registered holders of this call's token
         * (__CALL_MEDIA_AUTH_2026_09_23__), so they are attested, not peer-claimed.
         */
        fun parseInbound(data: ByteArray): InboundFrame? {
            if (data.size < 3) return null
            var offset = 1
            val senderKeyLen = data[offset].toInt() and 0xFF
            offset += 1
            if (offset + senderKeyLen > data.size) return null
            val sender = String(data, offset, senderKeyLen, Charsets.UTF_8)
            offset += senderKeyLen
            if (offset >= data.size) return null
            val callIdLen = data[offset].toInt() and 0xFF
            offset += 1
            if (offset + callIdLen > data.size) return null
            val callId = String(data, offset, callIdLen, Charsets.UTF_8)
            offset += callIdLen
            val payload = if (offset < data.size) data.copyOfRange(offset, data.size) else ByteArray(0)
            return InboundFrame(data[0], sender, callId, payload)
        }
    }

    /** Relay-stamped origin of an inbound frame. */
    data class RelayOrigin(val sender: String, val callId: String)

    class InboundFrame(val type: Byte, val sender: String, val callId: String, val payload: ByteArray)

    /**
     * Inbound audio-channel payload plus the relay-stamped [RelayOrigin] of its frame.
     * __BARE_CALLEND_ATTESTED_2026_11_21__ the origin is what lets a legacy bare `0x04`
     * hang-up be tied to THIS call and THIS peer (see [CallSignalGuards.mayEndFromBareMediaCallEnd]).
     */
    @Volatile var onAudioPacket: ((ByteArray, RelayOrigin) -> Unit)? = null
    @Volatile var onVideoPacket: ((ByteArray) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var socket: DatagramSocket? = null
    private var serverEndpoint: InetSocketAddress? = null
    private var rxJob: Job? = null
    private var heartbeatJob: Job? = null

    /**
     * Supplies the call's relay token. `forceRefresh=false` = the one already fetched (or
     * still in flight) for this call; `true` = ask the server for a new one (nack 01/02/04,
     * or expiry within 10 min). Null = none available.
     */
    @Volatile var tokenProvider: (suspend (forceRefresh: Boolean) -> RelayToken?)? = null

    private val registerMutex = Mutex()
    @Volatile private var token: RelayToken? = null
    /** Trailer counter of [token]; starts at 1 per token, strictly increasing (§3). */
    private var trailerCounter = 0L
    /** False once a token was held or a nack was seen: never fall back to token-less (§5). */
    @Volatile private var tokenlessAllowed = true
    /** Gave up authenticating (bad MAC twice): stop registering, callers fall back to WS. */
    @Volatile private var authGaveUp = false
    private var badMacRefetched = false
    private var tokenRefetches = 0
    private var lastNackActionAtMs = 0L

    @Volatile private var senderKeyB64u: String = ""
    @Volatile private var peerKeyB64u: String = ""
    @Volatile private var callId: String = ""
    @Volatile private var started: Boolean = false

    /** Epoch ms of the most recent inbound packet from the server. */
    private val lastReplyAtMs = AtomicLong(0L)
    /** True once the server ACKED (`04 01`) a register; cleared by any nack. */
    @Volatile private var registered: Boolean = false

    val isRegistered: Boolean get() = registered
    /** __EGRESS_STALL_2026_11_23__ The relay refused this call's token for good (see [onNack]). */
    val isAuthRefused: Boolean get() = authGaveUp
    val lastServerReplyAtMs: Long get() = lastReplyAtMs.get()

    /**
     * Open the UDP socket, send the first register packet, start the receive
     * loop and the 10 s heartbeat. Idempotent: a second call with the same
     * call-in-progress is a no-op until [stop] runs.
     *
     * [mySenderKey] and [peerKey] are the standard-base64 public-key strings
     * stored locally; we convert to base64url internally so the server routes
     * by the exact same key string the WS transport uses.
     */
    fun start(
        mySenderKey: String,
        peerKey: String,
        callId: String,
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT
    ) {
        if (started) return
        started = true
        senderKeyB64u = CallKeyEncoding.toBase64url(mySenderKey)
        peerKeyB64u = CallKeyEncoding.toBase64url(peerKey)
        this.callId = callId
        registered = false
        token = null
        trailerCounter = 0L
        tokenlessAllowed = true
        authGaveUp = false
        badMacRefetched = false
        tokenRefetches = 0
        lastReplyAtMs.set(0L)

        scope.launch {
            try {
                val sock = DatagramSocket().apply {
                    reuseAddress = true
                    soTimeout = 0  // Block forever in receive(); we cancel via socket.close()
                }
                socket = sock
                serverEndpoint = InetSocketAddress(InetAddress.getByName(host), port)
                Log.d(TAG, "🛰️ UDP RELAY socket open → $host:$port (callId=$callId)")

                // Kick the receive loop first so we can capture the register ack.
                startReceiveLoop(sock)
                // Initial register (waits for the call's relay token, fetched by the manager
                // as soon as callId+peer are known — see sendRegisterBlocking), then a 10 s heartbeat.
                sendRegisterBlocking(sock)
                startHeartbeat(sock)
            } catch (t: Throwable) {
                Log.w(TAG, "🛰️ UDP RELAY start failed: ${t.message}")
                started = false
            }
        }
    }

    /** Stop heartbeat, close the socket, cancel coroutines. Safe to call repeatedly. */
    fun stop() {
        if (!started && socket == null) return
        started = false
        registered = false
        token = null  // macKey lives for the call only
        try { heartbeatJob?.cancel() } catch (_: Throwable) {}
        try { rxJob?.cancel() } catch (_: Throwable) {}
        heartbeatJob = null
        rxJob = null
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        serverEndpoint = null
    }

    /**
     * Wrap [packet] with the 8089 binary header (type 0x01) and send. Drops
     * the frame if the socket isn't ready. Non-blocking; the actual send
     * runs on Dispatchers.IO.
     */
    fun sendAudio(packet: ByteArray) {
        sendTyped(TYPE_AUDIO, packet)
    }

    /** Same as [sendAudio] but with the 0x02 video type byte. */
    fun sendVideo(packet: ByteArray) {
        sendTyped(TYPE_VIDEO, packet)
    }

    private fun sendTyped(type: Byte, payload: ByteArray) {
        val sock = socket ?: return
        val target = serverEndpoint ?: return
        val frame = buildFrame(type, payload)
        scope.launch {
            try {
                sock.send(DatagramPacket(frame, frame.size, target))
            } catch (_: Throwable) {
                // Socket closed or transient error; drop the frame.
            }
        }
    }

    /** Re-register now (network change / local port change — contract §3 cadence). */
    fun reRegisterNow() {
        val sock = socket ?: return
        scope.launch { sendRegisterBlocking(sock) }
    }

    /** Install a (new) token; the trailer counter restarts at 1 for it. */
    private fun adoptToken(t: RelayToken?) {
        if (t == null) return
        synchronized(this) {
            if (token !== t) {
                token = t
                trailerCounter = 0L
            }
            tokenlessAllowed = false
        }
    }

    /** Unit tests only: set the frame identity and token without opening a socket. */
    internal fun primeForTest(mySenderKey: String, peerKey: String, callId: String, t: RelayToken?) {
        senderKeyB64u = CallKeyEncoding.toBase64url(mySenderKey)
        peerKeyB64u = CallKeyEncoding.toBase64url(peerKey)
        this.callId = callId
        adoptToken(t)
    }

    /** The register datagram to send now, or null when none may be sent. */
    internal fun buildRegisterFrame(): ByteArray? = synchronized(this) {
        if (authGaveUp) return null
        val header = buildFrame(TYPE_REGISTER, ByteArray(0))
        val t = token ?: return if (tokenlessAllowed) header else null
        trailerCounter += 1
        RelayAuth.sealRegister(header, t.tokenId, t.macKey, trailerCounter)
    }

    private suspend fun sendRegisterBlocking(sock: DatagramSocket) {
        registerMutex.withLock {
            val target = serverEndpoint ?: return
            // Contract §2 refresh: a call outliving expiresAt-10 min switches token.
            val current = token
            if (current != null && current.needsRefresh(System.currentTimeMillis())) {
                adoptToken(runCatching { tokenProvider?.invoke(true) }.getOrNull())
            }
            if (token == null) {
                // No token yet (first fetch failed or still pending): try again. If there is
                // still none, a token-less register goes out only if no nack was ever seen.
                adoptToken(runCatching { tokenProvider?.invoke(false) }.getOrNull())
            }
            val frame = buildRegisterFrame() ?: return
            try {
                sock.send(DatagramPacket(frame, frame.size, target))
                // NOT registered yet: only the `04 01` ack says so (contract §9).
            } catch (t: Throwable) {
                Log.w(TAG, "🛰️ UDP RELAY register send failed: ${t.message}")
            }
        }
    }

    /** Contract §5: what to do with `04 00 code`. Runs off the receive loop. */
    private fun onNack(code: Int) {
        registered = false
        tokenlessAllowed = false
        val sock = socket ?: return
        scope.launch {
            val now = System.currentTimeMillis()
            // Nacks are rate-limited server-side (10/10 s); act at most once a second.
            if (now - lastNackActionAtMs < 1_000L) return@launch
            lastNackActionAtMs = now
            Log.w(TAG, "🛰️ UDP RELAY register NACK code=$code (callId=${callId.take(8)})")
            when (code) {
                RelayAuth.NACK_UNKNOWN_TOKEN, RelayAuth.NACK_MISMATCH -> {
                    if (tokenRefetches >= MAX_TOKEN_REFETCHES) {
                        Log.w(TAG, "🛰️ UDP RELAY token refetch budget spent — relay left to WS")
                        authGaveUp = true
                        return@launch
                    }
                    tokenRefetches++
                    adoptToken(runCatching { tokenProvider?.invoke(true) }.getOrNull())
                }
                RelayAuth.NACK_BAD_MAC -> {
                    if (badMacRefetched) {
                        Log.e(TAG, "🛰️ UDP RELAY bad MAC twice — giving up on :8089, WS carries")
                        authGaveUp = true
                        return@launch
                    }
                    badMacRefetched = true
                    adoptToken(runCatching { tokenProvider?.invoke(true) }.getOrNull())
                }
                RelayAuth.NACK_REPLAY -> synchronized(this@UDPRelayClient) { trailerCounter += 1 }
                RelayAuth.NACK_KEY_HELD, RelayAuth.NACK_TOKENLESS_REFUSED -> {
                    if (token == null) adoptToken(runCatching { tokenProvider?.invoke(false) }.getOrNull())
                }
                else -> return@launch
            }
            if (socket === sock && !sock.isClosed) sendRegisterBlocking(sock)
        }
    }

    private fun startHeartbeat(sock: DatagramSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && socket === sock && !sock.isClosed) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!isActive || socket !== sock || sock.isClosed) break
                sendRegisterBlocking(sock)
            }
        }
    }

    private fun startReceiveLoop(sock: DatagramSocket) {
        rxJob?.cancel()
        rxJob = scope.launch {
            val buf = ByteArray(RX_BUFFER_BYTES)
            while (isActive && socket === sock && !sock.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (_: Throwable) {
                    // Socket closed, exit loop.
                    break
                }
                val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                if (data.isEmpty()) continue
                // A nack is not a sign of a working relay: it must not keep udpRelayUsable() fresh.
                val nack = RelayAuth.nackCode(data)
                if (nack != null) { onNack(nack); continue }
                lastReplyAtMs.set(System.currentTimeMillis())
                handleInbound(data)
            }
        }
    }

    /**
     * Inbound frame layout (server → client) is:
     *   [type(1)][senderKeyLen(1)][senderKey][callIdLen(1)][callId][payload]
     * Same as the WS binary RX format (see VPSClient.parseBinaryFrame).
     * For the register-ack the server sends two bytes: [0x04][0x01].
     */
    private fun handleInbound(data: ByteArray) {
        if (data.isEmpty()) return
        // Register ack: 2 bytes total, no further fields. Marks the session
        // as alive for the demote-on-stale logic in EnhancedCallManager.
        if (data[0] == TYPE_REGISTER && data.size == 2) {
            if (RelayAuth.isAck(data)) registered = true
            return
        }
        val frame = parseInbound(data) ?: return
        when (frame.type) {
            TYPE_AUDIO -> onAudioPacket?.invoke(frame.payload, RelayOrigin(frame.sender, frame.callId))
            TYPE_VIDEO -> onVideoPacket?.invoke(frame.payload)
            TYPE_PING -> { /* server liveness ping; recency already updated */ }
            else -> { /* unknown — drop */ }
        }
    }

    private fun buildFrame(type: Byte, payload: ByteArray): ByteArray {
        val recipBytes = peerKeyB64u.toByteArray(Charsets.UTF_8)
        val senderBytes = senderKeyB64u.toByteArray(Charsets.UTF_8)
        val callIdBytes = callId.toByteArray(Charsets.UTF_8)
        // The wire spec uses single-byte lengths (max 255). All three fields
        // are well under that, but truncate defensively to avoid emitting a
        // malformed frame if something upstream goes wrong.
        val recipLen = minOf(recipBytes.size, 255)
        val senderLen = minOf(senderBytes.size, 255)
        val callIdLen = minOf(callIdBytes.size, 255)

        val out = ByteArray(1 + 1 + recipLen + 1 + senderLen + 1 + callIdLen + payload.size)
        var o = 0
        out[o++] = type
        out[o++] = recipLen.toByte()
        System.arraycopy(recipBytes, 0, out, o, recipLen); o += recipLen
        out[o++] = senderLen.toByte()
        System.arraycopy(senderBytes, 0, out, o, senderLen); o += senderLen
        out[o++] = callIdLen.toByte()
        System.arraycopy(callIdBytes, 0, out, o, callIdLen); o += callIdLen
        System.arraycopy(payload, 0, out, o, payload.size)
        return out
    }
}
