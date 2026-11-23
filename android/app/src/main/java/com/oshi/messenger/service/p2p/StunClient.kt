package com.oshi.messenger.service.p2p

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

/**
 * StunClient — minimal RFC 5389 Binding Request / Response.
 *
 * Sends a UDP STUN Binding Request to our STUN/TURN endpoint (coturn on
 * 45.67.216.197:3478 replies to unauthenticated STUN on the same port) and
 * parses XOR-MAPPED-ADDRESS (or legacy MAPPED-ADDRESS) to learn this device's
 * public reflexive IPv4 address and port.
 *
 * Phase 1 only: discovery. Phase 2 wires the result into P2PTransport as an
 * srflx candidate for ICE exchange.
 */
class StunClient {

    companion object {
        private const val TAG = "StunClient"

        const val DEFAULT_HOST = "45.67.216.197"
        const val DEFAULT_PORT = 3478

        private const val MAGIC_COOKIE: Int = 0x2112A442.toInt()
        private const val BINDING_REQUEST_TYPE: Int = 0x0001
        private const val BINDING_SUCCESS_TYPE: Int = 0x0101

        private const val ATTR_MAPPED_ADDRESS: Int = 0x0001
        private const val ATTR_XOR_MAPPED_ADDRESS: Int = 0x0020

        private const val TIMEOUT_MS = 1500
        private const val STUN_HEADER_LEN = 20
    }

    data class ReflexiveAddress(val ip: String, val port: Int)

    /**
     * Discover the public (reflexive) IPv4 address and port of this device as
     * seen by the STUN server. Returns null on timeout / parse failure.
     *
     * Must be called from a coroutine — does blocking IO internally.
     */
    suspend fun discoverReflexiveAddress(
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT
    ): ReflexiveAddress? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = TIMEOUT_MS

            val (request, txId) = buildBindingRequest()
            val target = InetAddress.getByName(host)
            socket.send(DatagramPacket(request, request.size, target, port))

            val buf = ByteArray(512)
            val response = DatagramPacket(buf, buf.size)
            socket.receive(response)

            parseBindingResponse(buf, response.length, txId)
        } catch (e: Exception) {
            Log.w(TAG, "STUN discovery failed: ${e.message}")
            null
        } finally {
            socket?.close()
        }
    }

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    internal fun buildBindingRequest(): Pair<ByteArray, ByteArray> {
        val header = ByteArray(STUN_HEADER_LEN)
        // Message type (2B BE)
        header[0] = ((BINDING_REQUEST_TYPE ushr 8) and 0xFF).toByte()
        header[1] = (BINDING_REQUEST_TYPE and 0xFF).toByte()
        // Length = 0
        header[2] = 0
        header[3] = 0
        // Magic cookie
        header[4] = ((MAGIC_COOKIE ushr 24) and 0xFF).toByte()
        header[5] = ((MAGIC_COOKIE ushr 16) and 0xFF).toByte()
        header[6] = ((MAGIC_COOKIE ushr 8) and 0xFF).toByte()
        header[7] = (MAGIC_COOKIE and 0xFF).toByte()
        // Transaction ID: 12 random bytes
        val txId = ByteArray(12)
        SecureRandom().nextBytes(txId)
        System.arraycopy(txId, 0, header, 8, 12)
        return header to txId
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    internal fun parseBindingResponse(buf: ByteArray, len: Int, txId: ByteArray): ReflexiveAddress? {
        if (len < STUN_HEADER_LEN) return null

        val msgType = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        if (msgType != BINDING_SUCCESS_TYPE) return null

        val msgLen = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        val cookie = ((buf[4].toInt() and 0xFF) shl 24) or
                     ((buf[5].toInt() and 0xFF) shl 16) or
                     ((buf[6].toInt() and 0xFF) shl 8) or
                     (buf[7].toInt() and 0xFF)
        if (cookie != MAGIC_COOKIE) return null

        // Verify transaction id.
        for (i in 0 until 12) {
            if (buf[8 + i] != txId[i]) return null
        }

        var i = STUN_HEADER_LEN
        val end = minOf(STUN_HEADER_LEN + msgLen, len)
        while (i + 4 <= end) {
            val attrType = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            val attrLen = ((buf[i + 2].toInt() and 0xFF) shl 8) or (buf[i + 3].toInt() and 0xFF)
            val valueStart = i + 4
            val valueEnd = valueStart + attrLen
            if (valueEnd > end) break

            when (attrType) {
                ATTR_XOR_MAPPED_ADDRESS -> {
                    parseXorMappedAddress(buf, valueStart, attrLen, txId)?.let { return it }
                }
                ATTR_MAPPED_ADDRESS -> {
                    parseMappedAddress(buf, valueStart, attrLen)?.let { return it }
                }
            }

            // STUN attributes pad to 4-byte boundary.
            val padded = (attrLen + 3) and 3.inv()
            i = valueStart + padded
        }
        return null
    }

    private fun parseXorMappedAddress(buf: ByteArray, start: Int, length: Int, txId: ByteArray): ReflexiveAddress? {
        if (length < 8) return null
        val family = buf[start + 1].toInt() and 0xFF
        val xPort = ((buf[start + 2].toInt() and 0xFF) shl 8) or (buf[start + 3].toInt() and 0xFF)
        val port = xPort xor ((MAGIC_COOKIE ushr 16) and 0xFFFF)

        return when (family) {
            0x01 -> {
                val b0 = (buf[start + 4].toInt() and 0xFF) xor ((MAGIC_COOKIE ushr 24) and 0xFF)
                val b1 = (buf[start + 5].toInt() and 0xFF) xor ((MAGIC_COOKIE ushr 16) and 0xFF)
                val b2 = (buf[start + 6].toInt() and 0xFF) xor ((MAGIC_COOKIE ushr 8) and 0xFF)
                val b3 = (buf[start + 7].toInt() and 0xFF) xor (MAGIC_COOKIE and 0xFF)
                ReflexiveAddress("$b0.$b1.$b2.$b3", port)
            }
            0x02 -> {
                if (length < 20) return null
                // RFC 5389 §15.2: IPv6 XOR-MAPPED-ADDRESS — XOR with [magic cookie || txId].
                val xor = ByteArray(16)
                xor[0] = ((MAGIC_COOKIE ushr 24) and 0xFF).toByte()
                xor[1] = ((MAGIC_COOKIE ushr 16) and 0xFF).toByte()
                xor[2] = ((MAGIC_COOKIE ushr 8) and 0xFF).toByte()
                xor[3] = (MAGIC_COOKIE and 0xFF).toByte()
                System.arraycopy(txId, 0, xor, 4, 12)
                val raw = ByteArray(16)
                for (k in 0 until 16) raw[k] = (buf[start + 4 + k].toInt() xor xor[k].toInt()).toByte()
                try {
                    val ip = java.net.InetAddress.getByAddress(raw).hostAddress?.substringBefore('%')
                    ip?.let { ReflexiveAddress(it, port) }
                } catch (_: Exception) { null }
            }
            else -> null
        }
    }

    private fun parseMappedAddress(buf: ByteArray, start: Int, length: Int): ReflexiveAddress? {
        if (length < 8) return null
        val family = buf[start + 1].toInt() and 0xFF
        val port = ((buf[start + 2].toInt() and 0xFF) shl 8) or (buf[start + 3].toInt() and 0xFF)
        return if (family == 0x01) {
            val b0 = buf[start + 4].toInt() and 0xFF
            val b1 = buf[start + 5].toInt() and 0xFF
            val b2 = buf[start + 6].toInt() and 0xFF
            val b3 = buf[start + 7].toInt() and 0xFF
            ReflexiveAddress("$b0.$b1.$b2.$b3", port)
        } else {
            null
        }
    }
}
