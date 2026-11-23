package com.oshi.messenger.service.p2p

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 3 — RFC 5766 TURN client over UDP.
 *
 * Minimal long-term-credentials TURN client that:
 *  - Allocates a relayed transport address via [Allocate Request] (0x0003)
 *  - Parses the 401 Unauthorized challenge → retries with MESSAGE-INTEGRITY
 *    computed from long-term key `MD5(username:realm:password)`
 *  - Parses XOR-RELAYED-ADDRESS (RFC 5389 §15.2, IPv4 + IPv6) and LIFETIME
 *  - Refreshes the allocation periodically (before `lifetime - 60 s`)
 *  - Binds peer channels via [ChannelBind Request] (0x0009) (channels
 *    0x4000–0x7FFE), rebinding every ~10 min
 *  - Wraps outbound app data in ChannelData frames (RFC 5766 §11.4) and unwraps
 *    inbound ones, dispatching the stripped payload via [onRelayedPacket]
 *
 * Out of scope for this phase:
 *  - Send/Data indications (§10)
 *  - CreatePermission (we rely exclusively on ChannelBind which RFC 5766
 *    §11.2 requires an implicit permission for)
 *  - Short-term credentials / REALM-less servers
 *
 * Pure Kotlin stdlib + `java.security.MessageDigest` (MD5 for long-term key)
 * and `javax.crypto.Mac` (HMAC-SHA1 for MESSAGE-INTEGRITY).
 */
class TurnClient(
    private val serverHost: String,
    private val serverPort: Int,
    private val username: String,
    private val password: String,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "TurnClient"
        private const val LOG_PREFIX = "🧭 P2P/TURN:"

        // STUN/TURN magic cookie (RFC 5389 §6).
        private const val MAGIC_COOKIE: Int = 0x2112A442.toInt()

        // STUN header length (20) used for both requests and responses.
        private const val STUN_HEADER_LEN = 20

        // Message methods (12-bit).
        private const val METHOD_BINDING: Int    = 0x001
        private const val METHOD_ALLOCATE: Int   = 0x003
        private const val METHOD_REFRESH: Int    = 0x004
        private const val METHOD_CHANNEL_BIND: Int = 0x009

        // Message classes (2-bit).
        private const val CLASS_REQUEST: Int  = 0x0
        private const val CLASS_INDICATION: Int = 0x1
        private const val CLASS_SUCCESS: Int = 0x2
        private const val CLASS_ERROR: Int   = 0x3

        // Attributes.
        private const val ATTR_MAPPED_ADDRESS: Int       = 0x0001
        private const val ATTR_USERNAME: Int             = 0x0006
        private const val ATTR_MESSAGE_INTEGRITY: Int    = 0x0008
        private const val ATTR_ERROR_CODE: Int           = 0x0009
        private const val ATTR_CHANNEL_NUMBER: Int       = 0x000C
        private const val ATTR_LIFETIME: Int             = 0x000D
        private const val ATTR_XOR_PEER_ADDRESS: Int     = 0x0012
        private const val ATTR_XOR_RELAYED_ADDRESS: Int  = 0x0016
        private const val ATTR_REQUESTED_TRANSPORT: Int  = 0x0019
        private const val ATTR_REALM: Int                = 0x0014
        private const val ATTR_NONCE: Int                = 0x0015
        private const val ATTR_XOR_MAPPED_ADDRESS: Int   = 0x0020

        private const val TRANSPORT_UDP: Int = 17 // IANA protocol number

        private const val CHANNEL_RANGE_MIN: Int = 0x4000
        private const val CHANNEL_RANGE_MAX: Int = 0x7FFE

        private const val RECV_BUFFER_SIZE = 2048
        private const val RESPONSE_TIMEOUT_MS = 3_000L
        private const val CHANNEL_REBIND_INTERVAL_MS = 10L * 60L * 1000L // 10 min
        private const val DEFAULT_LIFETIME_SECONDS = 600
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Fires for every inbound ChannelData frame after the 4-byte header is stripped. */
    @Volatile var onRelayedPacket: ((data: ByteArray, fromAddr: String, fromPort: Int) -> Unit)? = null

    data class AllocatedRelay(
        val relayedAddress: String,
        val relayedPort: Int,
        val lifetimeSeconds: Int
    )

    /**
     * Send an Allocate request (unauthenticated first, then retry with
     * MESSAGE-INTEGRITY using the 401 challenge). Returns null on any failure.
     *
     * Must be called from a coroutine; performs blocking IO internally.
     */
    suspend fun allocate(): AllocatedRelay? {
        if (allocated.get()) {
            return AllocatedRelay(relayedIp ?: "", relayedPort, lifetimeSeconds)
        }
        try {
            if (socket == null) {
                socket = DatagramSocket().apply { reuseAddress = true }
                launchReceiveLoop()
            }

            // 1st attempt — no credentials; expect 401 + REALM + NONCE.
            val first = sendAllocateRequest(realm = null, nonce = null)
            if (first == null) {
                Log.w(TAG, "$LOG_PREFIX allocate: no response to initial request")
                return null
            }
            // If server accepted unauthenticated (unlikely for coturn), parse success.
            if (first.classBits == CLASS_SUCCESS && first.method == METHOD_ALLOCATE) {
                return handleAllocateSuccess(first)
            }
            if (first.classBits != CLASS_ERROR) {
                Log.w(TAG, "$LOG_PREFIX allocate: unexpected class=${first.classBits}")
                return null
            }

            val realm = first.realm
            val nonce = first.nonce
            if (realm == null || nonce == null) {
                Log.w(TAG, "$LOG_PREFIX allocate: 401 missing REALM/NONCE")
                return null
            }
            longTermKey = md5("$username:$realm:$password".toByteArray(Charsets.UTF_8))
            this.realm = realm
            this.nonce = nonce

            // 2nd attempt — authenticated.
            val second = sendAllocateRequest(realm = realm, nonce = nonce) ?: return null
            if (second.classBits != CLASS_SUCCESS) {
                Log.w(TAG, "$LOG_PREFIX allocate: auth attempt error code=${second.errorCode}")
                // If 438 (Stale Nonce), caller can retry once (not implemented here).
                return null
            }
            return handleAllocateSuccess(second)
        } catch (e: Exception) {
            Log.w(TAG, "$LOG_PREFIX allocate exception: ${e.message}")
            return null
        }
    }

    /**
     * Bind a peer address to a channel. Fires a ChannelBind request and marks
     * [channels] accordingly. Safe to call repeatedly (will rebind on expiry
     * automatically).
     */
    fun bindChannel(peerAddr: String, peerPort: Int, channelNumber: Int) {
        if (channelNumber < CHANNEL_RANGE_MIN || channelNumber > CHANNEL_RANGE_MAX) {
            Log.w(TAG, "$LOG_PREFIX bindChannel: out-of-range channel=$channelNumber")
            return
        }
        channels[channelNumber] = ChannelBinding(peerAddr, peerPort, bound = false)
        scope.launch(Dispatchers.IO) {
            doChannelBind(channelNumber, peerAddr, peerPort)
        }
    }

    /**
     * Send application data through a previously bound channel as a
     * ChannelData frame. Silently drops if the channel is not yet bound.
     */
    fun send(data: ByteArray, viaChannel: Int) {
        val s = socket ?: return
        if (channels[viaChannel]?.bound != true) return
        val header = ByteArray(4)
        header[0] = ((viaChannel ushr 8) and 0xFF).toByte()
        header[1] = (viaChannel and 0xFF).toByte()
        header[2] = ((data.size ushr 8) and 0xFF).toByte()
        header[3] = (data.size and 0xFF).toByte()
        val frame = header + data
        scope.launch(Dispatchers.IO) {
            try {
                val target = InetSocketAddress(InetAddress.getByName(serverHost), serverPort)
                s.send(DatagramPacket(frame, frame.size, target))
            } catch (_: Exception) { /* silent — higher layer handles restart */ }
        }
    }

    /** Cancel refresh/rebind jobs, close socket, reset state. */
    fun close() {
        if (!allocated.getAndSet(false) && socket == null) return
        try { refreshJob?.cancel() } catch (_: Exception) {}
        try { rebindJob?.cancel() } catch (_: Exception) {}
        try { receiveJob?.cancel() } catch (_: Exception) {}
        refreshJob = null
        rebindJob = null
        receiveJob = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        channels.clear()
        pending.clear()
        relayedIp = null
        relayedPort = 0
        lifetimeSeconds = DEFAULT_LIFETIME_SECONDS
    }

    // ------------------------------------------------------------------
    // Internal types
    // ------------------------------------------------------------------

    private data class ChannelBinding(
        val peerAddr: String,
        val peerPort: Int,
        @Volatile var bound: Boolean
    )

    private data class ParsedMessage(
        val method: Int,
        val classBits: Int,
        val txId: ByteArray,
        val xorMapped: Pair<String, Int>? = null,
        val xorRelayed: Pair<String, Int>? = null,
        val lifetime: Int? = null,
        val realm: String? = null,
        val nonce: String? = null,
        val errorCode: Int? = null,
        val channelNumber: Int? = null
    )

    // ------------------------------------------------------------------
    // Internal state
    // ------------------------------------------------------------------

    @Volatile private var socket: DatagramSocket? = null
    private val allocated = AtomicBoolean(false)

    @Volatile private var relayedIp: String? = null
    @Volatile private var relayedPort: Int = 0
    @Volatile private var lifetimeSeconds: Int = DEFAULT_LIFETIME_SECONDS

    @Volatile private var longTermKey: ByteArray? = null
    @Volatile private var realm: String? = null
    @Volatile private var nonce: String? = null

    private var receiveJob: Job? = null
    private var refreshJob: Job? = null
    private var rebindJob: Job? = null

    private val channels = ConcurrentHashMap<Int, ChannelBinding>()

    /** Transaction-id → CompletableDeferred<ParsedMessage>. Hex-encoded key. */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ParsedMessage>>()

    // ------------------------------------------------------------------
    // Socket + receive
    // ------------------------------------------------------------------

    private fun launchReceiveLoop() {
        receiveJob = scope.launch(Dispatchers.IO) {
            val s = socket ?: return@launch
            val buf = ByteArray(RECV_BUFFER_SIZE)
            val pkt = DatagramPacket(buf, buf.size)
            while (isActive && !s.isClosed) {
                try {
                    s.receive(pkt)
                } catch (_: java.net.SocketException) { break }
                catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "$LOG_PREFIX recv error: ${e.message}")
                    continue
                }
                if (pkt.length <= 0) continue
                val first = buf[pkt.offset].toInt() and 0xC0
                // RFC 5766 §11: top 2 bits of ChannelData = "01" (i.e. 0x40..0x7F).
                // STUN method top 2 bits = "00" (i.e. 0x00..0x3F).
                when (first) {
                    0x00 -> handleStunInbound(buf, pkt.offset, pkt.length)
                    0x40 -> handleChannelData(buf, pkt.offset, pkt.length)
                    else -> { /* drop */ }
                }
            }
        }
    }

    private fun handleStunInbound(buf: ByteArray, off: Int, len: Int) {
        val parsed = parseStunMessage(buf, off, len) ?: return
        val key = txHex(parsed.txId)
        pending.remove(key)?.complete(parsed)
    }

    private fun handleChannelData(buf: ByteArray, off: Int, len: Int) {
        if (len < 4) return
        val channel = ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
        val dataLen = ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)
        if (4 + dataLen > len) return
        val binding = channels[channel] ?: return
        val payload = ByteArray(dataLen).also { System.arraycopy(buf, off + 4, it, 0, dataLen) }
        onRelayedPacket?.invoke(payload, binding.peerAddr, binding.peerPort)
    }

    // ------------------------------------------------------------------
    // Allocate / Refresh / ChannelBind request builders
    // ------------------------------------------------------------------

    private suspend fun sendAllocateRequest(realm: String?, nonce: String?): ParsedMessage? {
        val txId = randomTxId()
        val attrs = ByteArrayOutputStream()

        // REQUESTED-TRANSPORT (0x0019) — 4 bytes: transport(1) + RFFU(3).
        val rt = ByteArray(4)
        rt[0] = (TRANSPORT_UDP and 0xFF).toByte()
        appendAttribute(attrs, ATTR_REQUESTED_TRANSPORT, rt)

        // LIFETIME (0x000D) — 4 bytes BE.
        appendAttribute(attrs, ATTR_LIFETIME, u32(DEFAULT_LIFETIME_SECONDS))

        if (realm != null && nonce != null) {
            appendStringAttribute(attrs, ATTR_USERNAME, username)
            appendStringAttribute(attrs, ATTR_REALM, realm)
            appendStringAttribute(attrs, ATTR_NONCE, nonce)
        }

        val body = attrs.toByteArray()
        val pkt = buildStunMessage(METHOD_ALLOCATE, CLASS_REQUEST, txId, body,
            includeMessageIntegrity = (realm != null && nonce != null),
            longTermKey = longTermKey)

        return sendAndWait(txId, pkt)
    }

    private suspend fun sendRefreshRequest(lifetime: Int): ParsedMessage? {
        val txId = randomTxId()
        val attrs = ByteArrayOutputStream()
        appendAttribute(attrs, ATTR_LIFETIME, u32(lifetime))
        val curRealm = this.realm
        val curNonce = this.nonce
        if (curRealm != null && curNonce != null) {
            appendStringAttribute(attrs, ATTR_USERNAME, username)
            appendStringAttribute(attrs, ATTR_REALM, curRealm)
            appendStringAttribute(attrs, ATTR_NONCE, curNonce)
        }
        val body = attrs.toByteArray()
        val pkt = buildStunMessage(METHOD_REFRESH, CLASS_REQUEST, txId, body,
            includeMessageIntegrity = (curRealm != null && curNonce != null),
            longTermKey = longTermKey)
        return sendAndWait(txId, pkt)
    }

    private suspend fun doChannelBind(channel: Int, peerAddr: String, peerPort: Int) {
        val txId = randomTxId()
        val attrs = ByteArrayOutputStream()

        // CHANNEL-NUMBER (0x000C) — 4 bytes: number(2 BE) + RFFU(2).
        val cn = ByteArray(4)
        cn[0] = ((channel ushr 8) and 0xFF).toByte()
        cn[1] = (channel and 0xFF).toByte()
        appendAttribute(attrs, ATTR_CHANNEL_NUMBER, cn)

        // XOR-PEER-ADDRESS (0x0012) — XOR with magic cookie + txId (for v6).
        val peerAttr = encodeXorAddress(peerAddr, peerPort, txId)
        if (peerAttr != null) appendAttribute(attrs, ATTR_XOR_PEER_ADDRESS, peerAttr)

        val curRealm = this.realm
        val curNonce = this.nonce
        if (curRealm != null && curNonce != null) {
            appendStringAttribute(attrs, ATTR_USERNAME, username)
            appendStringAttribute(attrs, ATTR_REALM, curRealm)
            appendStringAttribute(attrs, ATTR_NONCE, curNonce)
        }

        val body = attrs.toByteArray()
        val pkt = buildStunMessage(METHOD_CHANNEL_BIND, CLASS_REQUEST, txId, body,
            includeMessageIntegrity = (curRealm != null && curNonce != null),
            longTermKey = longTermKey)

        val resp = sendAndWait(txId, pkt)
        if (resp == null) {
            Log.w(TAG, "$LOG_PREFIX ChannelBind timeout channel=$channel peer=$peerAddr:$peerPort")
            return
        }
        if (resp.classBits == CLASS_SUCCESS && resp.method == METHOD_CHANNEL_BIND) {
            channels[channel]?.bound = true
            Log.d(TAG, "$LOG_PREFIX ChannelBind OK channel=$channel peer=$peerAddr:$peerPort")
        } else {
            Log.w(TAG, "$LOG_PREFIX ChannelBind error class=${resp.classBits} code=${resp.errorCode}")
        }
    }

    private suspend fun sendAndWait(txId: ByteArray, pkt: ByteArray): ParsedMessage? {
        val s = socket ?: return null
        val key = txHex(txId)
        val deferred = CompletableDeferred<ParsedMessage>()
        pending[key] = deferred
        try {
            val target = InetSocketAddress(InetAddress.getByName(serverHost), serverPort)
            s.send(DatagramPacket(pkt, pkt.size, target))
        } catch (e: Exception) {
            pending.remove(key)
            Log.w(TAG, "$LOG_PREFIX send failed: ${e.message}")
            return null
        }
        return withTimeoutOrNull(RESPONSE_TIMEOUT_MS) { deferred.await() }.also {
            pending.remove(key)
        }
    }

    private fun handleAllocateSuccess(msg: ParsedMessage): AllocatedRelay? {
        val (ip, port) = msg.xorRelayed ?: run {
            Log.w(TAG, "$LOG_PREFIX allocate success but no XOR-RELAYED-ADDRESS")
            return null
        }
        relayedIp = ip
        relayedPort = port
        lifetimeSeconds = msg.lifetime ?: DEFAULT_LIFETIME_SECONDS
        allocated.set(true)
        Log.d(TAG, "$LOG_PREFIX ✅ Allocated relay=$ip:$port lifetime=${lifetimeSeconds}s")

        // Start refresh loop.
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.IO) {
            while (isActive && allocated.get()) {
                val sleepSec = maxOf(lifetimeSeconds - 60, 30)
                delay(sleepSec * 1000L)
                if (!allocated.get()) break
                val resp = sendRefreshRequest(DEFAULT_LIFETIME_SECONDS)
                if (resp == null || resp.classBits != CLASS_SUCCESS) {
                    Log.w(TAG, "$LOG_PREFIX refresh failed — giving up")
                    break
                }
                lifetimeSeconds = resp.lifetime ?: DEFAULT_LIFETIME_SECONDS
                Log.d(TAG, "$LOG_PREFIX refreshed lifetime=${lifetimeSeconds}s")
            }
        }

        // Rebind channels every 10 min.
        rebindJob?.cancel()
        rebindJob = scope.launch(Dispatchers.IO) {
            while (isActive && allocated.get()) {
                delay(CHANNEL_REBIND_INTERVAL_MS)
                for ((ch, binding) in channels) {
                    doChannelBind(ch, binding.peerAddr, binding.peerPort)
                }
            }
        }

        return AllocatedRelay(ip, port, lifetimeSeconds)
    }

    // ------------------------------------------------------------------
    // STUN message build + parse
    // ------------------------------------------------------------------

    private fun buildStunMessage(
        method: Int,
        classBits: Int,
        txId: ByteArray,
        body: ByteArray,
        includeMessageIntegrity: Boolean,
        longTermKey: ByteArray?
    ): ByteArray {
        // Message type (14 bits): combine method (12) + class (2).
        // Layout: M11 M10 M9 M8 M7 C1 M6 M5 M4 C0 M3 M2 M1 M0  (RFC 5389 §6)
        val mt = messageType(method, classBits)

        if (!includeMessageIntegrity || longTermKey == null) {
            val total = STUN_HEADER_LEN + body.size
            val out = ByteArray(total)
            writeHeader(out, mt, body.size, txId)
            System.arraycopy(body, 0, out, STUN_HEADER_LEN, body.size)
            return out
        }

        // With MESSAGE-INTEGRITY (20 bytes HMAC-SHA1 over the message up to but
        // not including the attribute itself). The Length field in the header
        // must already cover MESSAGE-INTEGRITY for the HMAC input, per RFC 5389.
        val miAttrLen = 20
        val miFullLen = 4 + miAttrLen // header + value
        val bodyLenIncludingMI = body.size + miFullLen

        // Build partial message (header + body) with Length field set to the
        // total length INCLUDING the MI attribute we are about to append.
        val partial = ByteArray(STUN_HEADER_LEN + body.size)
        writeHeader(partial, mt, bodyLenIncludingMI, txId)
        System.arraycopy(body, 0, partial, STUN_HEADER_LEN, body.size)

        // HMAC-SHA1 over `partial`.
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(longTermKey, "HmacSHA1"))
        val integrity = mac.doFinal(partial)

        val out = ByteArray(STUN_HEADER_LEN + body.size + miFullLen)
        System.arraycopy(partial, 0, out, 0, partial.size)
        var i = partial.size
        out[i]     = ((ATTR_MESSAGE_INTEGRITY ushr 8) and 0xFF).toByte()
        out[i + 1] = (ATTR_MESSAGE_INTEGRITY and 0xFF).toByte()
        out[i + 2] = ((miAttrLen ushr 8) and 0xFF).toByte()
        out[i + 3] = (miAttrLen and 0xFF).toByte()
        i += 4
        System.arraycopy(integrity, 0, out, i, miAttrLen)
        return out
    }

    private fun writeHeader(out: ByteArray, msgType: Int, bodyLen: Int, txId: ByteArray) {
        out[0] = ((msgType ushr 8) and 0xFF).toByte()
        out[1] = (msgType and 0xFF).toByte()
        out[2] = ((bodyLen ushr 8) and 0xFF).toByte()
        out[3] = (bodyLen and 0xFF).toByte()
        out[4] = ((MAGIC_COOKIE ushr 24) and 0xFF).toByte()
        out[5] = ((MAGIC_COOKIE ushr 16) and 0xFF).toByte()
        out[6] = ((MAGIC_COOKIE ushr 8) and 0xFF).toByte()
        out[7] = (MAGIC_COOKIE and 0xFF).toByte()
        System.arraycopy(txId, 0, out, 8, 12)
    }

    private fun messageType(method: Int, classBits: Int): Int {
        // Shuffle method bits around the two class bits (positions 4 and 8).
        val m = method and 0x0FFF
        val c = classBits and 0x3
        val a = (m and 0x000F)              // M0..M3
        val b = ((m ushr 4) and 0x0007)     // M4..M6
        val d = ((m ushr 7) and 0x001F)     // M7..M11
        val c0 = c and 0x1
        val c1 = (c ushr 1) and 0x1
        return (d shl 9) or (c1 shl 8) or (b shl 5) or (c0 shl 4) or a
    }

    private fun parseStunMessage(buf: ByteArray, off: Int, len: Int): ParsedMessage? {
        if (len < STUN_HEADER_LEN) return null
        val mt = ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
        val bodyLen = ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)
        val cookie = ((buf[off + 4].toInt() and 0xFF) shl 24) or
                     ((buf[off + 5].toInt() and 0xFF) shl 16) or
                     ((buf[off + 6].toInt() and 0xFF) shl 8) or
                     (buf[off + 7].toInt() and 0xFF)
        if (cookie != MAGIC_COOKIE) return null
        val txId = ByteArray(12).also { System.arraycopy(buf, off + 8, it, 0, 12) }

        val method = decodeMethod(mt)
        val classBits = decodeClass(mt)

        var xorMapped: Pair<String, Int>? = null
        var xorRelayed: Pair<String, Int>? = null
        var lifetime: Int? = null
        var realmStr: String? = null
        var nonceStr: String? = null
        var errorCode: Int? = null
        var channelNumber: Int? = null

        var i = off + STUN_HEADER_LEN
        val end = minOf(off + STUN_HEADER_LEN + bodyLen, off + len)
        while (i + 4 <= end) {
            val at = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            val al = ((buf[i + 2].toInt() and 0xFF) shl 8) or (buf[i + 3].toInt() and 0xFF)
            val vs = i + 4
            val ve = vs + al
            if (ve > end) break

            when (at) {
                ATTR_XOR_MAPPED_ADDRESS   -> xorMapped  = parseXorAddress(buf, vs, al, txId)
                ATTR_XOR_RELAYED_ADDRESS  -> xorRelayed = parseXorAddress(buf, vs, al, txId)
                ATTR_MAPPED_ADDRESS       -> { /* ignored — prefer XOR form */ }
                ATTR_LIFETIME             -> {
                    if (al >= 4) {
                        lifetime = ((buf[vs].toInt() and 0xFF) shl 24) or
                                   ((buf[vs + 1].toInt() and 0xFF) shl 16) or
                                   ((buf[vs + 2].toInt() and 0xFF) shl 8) or
                                   (buf[vs + 3].toInt() and 0xFF)
                    }
                }
                ATTR_REALM                -> realmStr = String(buf, vs, al, Charsets.UTF_8)
                ATTR_NONCE                -> nonceStr = String(buf, vs, al, Charsets.UTF_8)
                ATTR_ERROR_CODE           -> {
                    // class * 100 + number; bytes 0..1 reserved, byte 2 = class, byte 3 = number.
                    if (al >= 4) {
                        val cls = buf[vs + 2].toInt() and 0x07
                        val num = buf[vs + 3].toInt() and 0xFF
                        errorCode = cls * 100 + num
                    }
                }
                ATTR_CHANNEL_NUMBER       -> {
                    if (al >= 2) {
                        channelNumber = ((buf[vs].toInt() and 0xFF) shl 8) or (buf[vs + 1].toInt() and 0xFF)
                    }
                }
            }
            // Pad to 4.
            val padded = (al + 3) and 3.inv()
            i = vs + padded
        }

        return ParsedMessage(
            method = method,
            classBits = classBits,
            txId = txId,
            xorMapped = xorMapped,
            xorRelayed = xorRelayed,
            lifetime = lifetime,
            realm = realmStr,
            nonce = nonceStr,
            errorCode = errorCode,
            channelNumber = channelNumber
        )
    }

    private fun decodeMethod(mt: Int): Int {
        val a = mt and 0x000F
        val b = (mt ushr 5) and 0x0007
        val d = (mt ushr 9) and 0x001F
        return (d shl 7) or (b shl 4) or a
    }

    private fun decodeClass(mt: Int): Int {
        val c0 = (mt ushr 4) and 0x1
        val c1 = (mt ushr 8) and 0x1
        return (c1 shl 1) or c0
    }

    // XOR address (RFC 5389 §15.2) — supports both v4 and v6 families.
    private fun parseXorAddress(buf: ByteArray, start: Int, length: Int, txId: ByteArray): Pair<String, Int>? {
        if (length < 8) return null
        val family = buf[start + 1].toInt() and 0xFF
        val xPort = ((buf[start + 2].toInt() and 0xFF) shl 8) or (buf[start + 3].toInt() and 0xFF)
        val port = xPort xor ((MAGIC_COOKIE ushr 16) and 0xFFFF)

        return when (family) {
            0x01 -> {
                if (length < 8) return null
                val b0 = (buf[start + 4].toInt() and 0xFF) xor ((MAGIC_COOKIE ushr 24) and 0xFF)
                val b1 = (buf[start + 5].toInt() and 0xFF) xor ((MAGIC_COOKIE ushr 16) and 0xFF)
                val b2 = (buf[start + 6].toInt() and 0xFF) xor ((MAGIC_COOKIE ushr 8) and 0xFF)
                val b3 = (buf[start + 7].toInt() and 0xFF) xor (MAGIC_COOKIE and 0xFF)
                "$b0.$b1.$b2.$b3" to port
            }
            0x02 -> {
                if (length < 20) return null
                // XOR with [MAGIC_COOKIE || txId] (16 bytes).
                val xor = ByteArray(16)
                xor[0] = ((MAGIC_COOKIE ushr 24) and 0xFF).toByte()
                xor[1] = ((MAGIC_COOKIE ushr 16) and 0xFF).toByte()
                xor[2] = ((MAGIC_COOKIE ushr 8) and 0xFF).toByte()
                xor[3] = (MAGIC_COOKIE and 0xFF).toByte()
                System.arraycopy(txId, 0, xor, 4, 12)
                val raw = ByteArray(16)
                for (k in 0 until 16) raw[k] = (buf[start + 4 + k].toInt() xor xor[k].toInt()).toByte()
                try {
                    InetAddress.getByAddress(raw).hostAddress?.let { it.substringBefore('%') to port }
                } catch (_: Exception) { null }
            }
            else -> null
        }
    }

    private fun encodeXorAddress(ip: String, port: Int, txId: ByteArray): ByteArray? {
        val addr = try { InetAddress.getByName(ip) } catch (_: Exception) { return null }
        val addrBytes = addr.address
        val family = if (addr is Inet6Address) 0x02 else 0x01
        val xPort = port xor ((MAGIC_COOKIE ushr 16) and 0xFFFF)
        return if (family == 0x01) {
            if (addrBytes.size != 4) return null
            val out = ByteArray(8)
            out[0] = 0
            out[1] = family.toByte()
            out[2] = ((xPort ushr 8) and 0xFF).toByte()
            out[3] = (xPort and 0xFF).toByte()
            out[4] = (addrBytes[0].toInt() xor ((MAGIC_COOKIE ushr 24) and 0xFF)).toByte()
            out[5] = (addrBytes[1].toInt() xor ((MAGIC_COOKIE ushr 16) and 0xFF)).toByte()
            out[6] = (addrBytes[2].toInt() xor ((MAGIC_COOKIE ushr 8) and 0xFF)).toByte()
            out[7] = (addrBytes[3].toInt() xor (MAGIC_COOKIE and 0xFF)).toByte()
            out
        } else {
            if (addrBytes.size != 16) return null
            val out = ByteArray(20)
            out[0] = 0
            out[1] = family.toByte()
            out[2] = ((xPort ushr 8) and 0xFF).toByte()
            out[3] = (xPort and 0xFF).toByte()
            val xor = ByteArray(16)
            xor[0] = ((MAGIC_COOKIE ushr 24) and 0xFF).toByte()
            xor[1] = ((MAGIC_COOKIE ushr 16) and 0xFF).toByte()
            xor[2] = ((MAGIC_COOKIE ushr 8) and 0xFF).toByte()
            xor[3] = (MAGIC_COOKIE and 0xFF).toByte()
            System.arraycopy(txId, 0, xor, 4, 12)
            for (k in 0 until 16) out[4 + k] = (addrBytes[k].toInt() xor xor[k].toInt()).toByte()
            out
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun appendAttribute(out: ByteArrayOutputStream, type: Int, value: ByteArray) {
        out.write((type ushr 8) and 0xFF)
        out.write(type and 0xFF)
        out.write((value.size ushr 8) and 0xFF)
        out.write(value.size and 0xFF)
        out.write(value)
        val pad = ((value.size + 3) and 3.inv()) - value.size
        repeat(pad) { out.write(0) }
    }

    private fun appendStringAttribute(out: ByteArrayOutputStream, type: Int, s: String) {
        appendAttribute(out, type, s.toByteArray(Charsets.UTF_8))
    }

    private fun u32(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte()
    )

    private fun randomTxId(): ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) }

    private fun txHex(txId: ByteArray): String = txId.joinToString("") { "%02x".format(it) }

    private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)
}
