package com.oshi.messenger.service

import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * __CALL_MEDIA_AUTH_2026_09_23__ docs/CALL_MEDIA_RELAY_AUTH_CONTRACT.md (v1.0) — the pure,
 * JVM-testable half of the relay authentication: key spelling, the token, the 44-byte ORA1
 * trailer and the register nack codes. No android.* here on purpose (unit-tested against §7).
 */
object CallKeyEncoding {
    /**
     * ONE spelling for a 32-byte identity key on the relay: base64url, unpadded. Used by
     * [UDPRelayClient] (UDP `sender`/`recipient`) AND by VPSClient (token `identity`/`peer`,
     * WS/HTTP paths), so the token's identity is byte-for-byte the UDP frame's sender
     * (contract §9 "Key spelling"). Accepts hex, standard base64 or base64url input.
     */
    fun toBase64url(key: String): String {
        val bytes = decodeKey(key) ?: return key.replace('+', '-').replace('/', '_').replace("=", "")
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun decodeKey(key: String): ByteArray? {
        if (key.isEmpty()) return null
        if (key.matches(Regex("^[0-9a-fA-F]+$")) && key.length % 2 == 0) {
            return ByteArray(key.length / 2) { key.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        val std = key.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        val padded = std + "=".repeat((4 - std.length % 4) % 4)
        return runCatching { java.util.Base64.getDecoder().decode(padded) }.getOrNull()
    }
}

/**
 * A relay token (contract §2). [macKey] is a secret: memory only, never logged, never persisted
 * — [toString] is overridden so an accidental log line cannot print it.
 */
class RelayToken(
    val tokenId: ByteArray,
    val macKey: ByteArray,
    val expiresAtMs: Long,
    val identity: String,
    val peer: String,
    val callId: String,
) {
    init {
        require(tokenId.size == 16) { "tokenId must be 16 bytes" }
        require(macKey.size == 32) { "macKey must be 32 bytes" }
    }

    /** Contract §2 "Refresh": switch to a new token once within 10 min of expiry. */
    fun needsRefresh(nowMs: Long): Boolean = nowMs >= expiresAtMs - REFRESH_MARGIN_MS

    override fun toString(): String = "RelayToken(callId=$callId, expiresAt=$expiresAtMs)"

    companion object {
        const val REFRESH_MARGIN_MS = 10 * 60 * 1000L

        /** Parses the 200 body `{"tokenId","macKey","expiresAt",...}`; null when malformed. */
        fun fromResponse(body: String, identity: String, peer: String, callId: String): RelayToken? =
            runCatching {
                val j = JSONObject(body)
                val dec = java.util.Base64.getUrlDecoder()
                fun b64u(s: String) = dec.decode(s.trim().replace('+', '-').replace('/', '_').trimEnd('='))
                RelayToken(
                    tokenId = b64u(j.getString("tokenId")),
                    macKey = b64u(j.getString("macKey")),
                    expiresAtMs = j.getLong("expiresAt"),
                    identity = identity, peer = peer, callId = callId,
                )
            }.getOrNull()
    }
}

object RelayAuth {
    val MAGIC = byteArrayOf(0x4f, 0x52, 0x41, 0x31)              // "ORA1"
    private val MAC_LABEL = "OSHI-UDP-REG-1".toByteArray(Charsets.US_ASCII)
    const val TRAILER_SIZE = 44
    const val MAC_SIZE = 16

    // Register replies (contract §5). Ack = exactly [04 01]; nack = [04 00 code].
    const val NACK_UNKNOWN_TOKEN = 1
    const val NACK_BAD_MAC = 2
    const val NACK_REPLAY = 3
    const val NACK_MISMATCH = 4
    const val NACK_KEY_HELD = 5
    const val NACK_TOKENLESS_REFUSED = 6

    /**
     * Append the trailer to a frame whose payload is empty: `header ‖ ORA1 ‖ tokenId ‖ counter BE8
     * ‖ mac16`, mac = HMAC-SHA256(macKey, "OSHI-UDP-REG-1" ‖ every byte before the mac)[0..16).
     */
    fun sealRegister(header: ByteArray, tokenId: ByteArray, macKey: ByteArray, counter: Long): ByteArray {
        val out = ByteArray(header.size + TRAILER_SIZE)
        System.arraycopy(header, 0, out, 0, header.size)
        var o = header.size
        System.arraycopy(MAGIC, 0, out, o, 4); o += 4
        System.arraycopy(tokenId, 0, out, o, 16); o += 16
        for (i in 0..7) out[o + i] = (counter ushr (56 - i * 8)).toByte()
        o += 8
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update(MAC_LABEL)
        mac.update(out, 0, o)
        System.arraycopy(mac.doFinal().copyOf(MAC_SIZE), 0, out, o, MAC_SIZE)
        return out
    }

    /**
     * The exact `POST /relay-token` body bytes, fixed field order (contract §7 T1). Built by
     * hand rather than with JSONObject, whose key order differs between Android and the JVM.
     * Keys are base64url and callId is `[A-Za-z0-9._:-]`, so nothing needs escaping; anything
     * else is escaped anyway rather than trusted.
     */
    fun relayTokenBody(identity: String, peer: String, callId: String): ByteArray =
        "{\"identity\":${JSONObject.quote(identity)},\"peer\":${JSONObject.quote(peer)},\"callId\":${JSONObject.quote(callId)}}"
            .toByteArray(Charsets.UTF_8)

    /** True for the register ack: exactly `04 01`. */
    fun isAck(data: ByteArray): Boolean = data.size == 2 && data[0] == 0x04.toByte() && data[1] == 0x01.toByte()

    /** The nack code of a 3-byte `04 00 code`, else null. */
    fun nackCode(data: ByteArray): Int? =
        if (data.size == 3 && data[0] == 0x04.toByte() && data[1] == 0x00.toByte()) data[2].toInt() and 0xFF else null
}
