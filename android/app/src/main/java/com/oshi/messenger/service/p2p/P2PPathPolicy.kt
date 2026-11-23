package com.oshi.messenger.service.p2p

/**
 * __TWO_MODE_CALL_PATH_2026_09_23__ The ONE path policy for a call's media, kept free of
 * android.* so it is JVM-testable. Mirrors iOS `P2PTransport` (same constants, same wire).
 *
 * A call rides exactly one of two modes:
 *   1. DIRECT peer-to-peer (host / srflx / peer-reflexive pair), when a pair is proven to
 *      carry a MEDIA-SIZED datagram both ways;
 *   2. otherwise the VPS call-server relay (UDP 8089 / WS / HTTP), which is what the call
 *      manager uses whenever the transport is not CONNECTED.
 *
 * coturn is NOT a third media path any more ([turnRelayMediaEnabled] = false): it sits on the
 * same host as the call server, so it bought no latency, and it added half-states of its own
 * (B37DC742, 2026-09-23: iPhone relay allocated, Android ChannelBound to it, iPhone never
 * permitted Android's relay ⇒ chConfirmed 0/0, nothing delivered). coturn keeps serving STUN
 * (srflx discovery on 3478) — that is the only traversal aid direct P2P needs.
 *
 * Media-sized validation: a 29-byte ping proves nothing about a ~2 KB audio datagram. On
 * 5G+VPN the tunnel drops IP fragments, so a pair can pong happily while every media frame
 * is black-holed — one side then sends on a path the other never receives. A pair may only
 * be selected once a JUMBO ping (padded to [JUMBO_PROBE_BYTES]) has come back as a pong;
 * both platforms' pong is a byte copy of the ping, so this works against old peers too.
 */
object P2PPathPolicy {
    /** coturn relay as a media path. Off by design; kept switchable for field diagnosis. */
    @Volatile
    @JvmStatic
    var turnRelayMediaEnabled: Boolean = false

    /** Size of a plain hole-punch ping: [0x31][call_id 8][nonce 12][ts_ms 8]. */
    const val PING_BYTES = 29

    /**
     * Media-sized probe. ≥ the largest P2P media datagram (48 kHz PCM frame ≈ 1.93 KB with
     * header) and < the 2048-byte receive buffers of both platforms (old builds included).
     */
    const val JUMBO_PROBE_BYTES = 2000

    /**
     * __RELAY_FIRST_UPGRADE_2026_09_23__ probe size once every media datagram is compact
     * (WB-ADPCM 0x18 = 200 B, AAC-ELD ≈ 370 B, video chunks ≤ 1151 B). 2000 B asked a 5G+VPN
     * path to carry IP FRAGMENTS it drops, refusing direct exactly where the real 200 B frames
     * would have passed. iOS `P2PTransport.compactProbeBytes`.
     */
    const val COMPACT_PROBE_BYTES = 1200

    /**
     * Consent freshness: the active pair must have ponged within this (+ RTT) or the call
     * manager puts the next frame on the (warm) VPS relay. Pings are 2 Hz ⇒ ~2 lost pongs.
     */
    const val CONSENT_STALE_MS = 1_000L

    fun consentFresh(lastActivePongAtMs: Long, rttMs: Long, nowMs: Long): Boolean =
        lastActivePongAtMs > 0L && nowMs - lastActivePongAtMs <= CONSENT_STALE_MS + rttMs.coerceAtLeast(0L)

    /** Probe size for the media actually on the wire: raw 48 kHz PCM needs the full 2000 B. */
    fun probeBytesFor(compactMedia: Boolean): Int = if (compactMedia) COMPACT_PROBE_BYTES else JUMBO_PROBE_BYTES

    /**
     * Make-before-break after relay → direct: compact frames keep riding the relay this long
     * (receiver dedups by (type, seq)), so the switch has no gap.
     */
    const val UPGRADE_OVERLAP_MS = 1_000L
    const val UPGRADE_OVERLAP_MAX_BYTES = 600

    fun upgradeOverlapActive(overlapUntilMs: Long, nowMs: Long, frameBytes: Int): Boolean =
        nowMs < overlapUntilMs && frameBytes <= UPGRADE_OVERLAP_MAX_BYTES

    /** A pair stays media-verified this long after its last jumbo pong. */
    const val JUMBO_MAX_AGE_MS = 6_000L

    /** Probe ticks are 500 ms: 1 Hz jumbo until verified, then every 2 s to catch an MTU change. */
    const val JUMBO_EVERY_TICKS_UNVERIFIED = 2L
    const val JUMBO_EVERY_TICKS_VERIFIED = 4L

    const val PING_TYPE: Byte = 0x31
    const val PONG_TYPE: Byte = 0x32

    fun acceptsRemoteCandidate(type: P2PTransport.CandidateType): Boolean =
        type != P2PTransport.CandidateType.RELAY || turnRelayMediaEnabled

    fun isJumbo(length: Int): Boolean = length >= JUMBO_PROBE_BYTES

    fun mediaVerified(lastJumboPongAtMs: Long, nowMs: Long): Boolean =
        lastJumboPongAtMs > 0L && nowMs - lastJumboPongAtMs <= JUMBO_MAX_AGE_MS

    /** Only a pair that already answers plain pings gets the (costlier) jumbo probe. */
    fun shouldSendJumbo(pairLive: Boolean, verified: Boolean, tick: Long): Boolean {
        if (!pairLive) return false
        val every = if (verified) JUMBO_EVERY_TICKS_VERIFIED else JUMBO_EVERY_TICKS_UNVERIFIED
        return tick % every == 0L
    }

    /** A pair may carry media only when it is live AND proved it carries a media-sized datagram. */
    fun pairSelectable(isLive: Boolean, lastJumboPongAtMs: Long, nowMs: Long): Boolean =
        isLive && mediaVerified(lastJumboPongAtMs, nowMs)

    /** [0x31][call_id 8B BE][nonce 12B][ts_ms 8B BE][zero padding up to size]. */
    fun buildPing(callId: Long, nonce: ByteArray, tsMs: Long, size: Int = PING_BYTES): ByteArray {
        require(nonce.size == 12) { "nonce must be 12 bytes" }
        val out = ByteArray(maxOf(size, PING_BYTES))
        out[0] = PING_TYPE
        for (i in 0 until 8) out[1 + i] = ((callId ushr (56 - i * 8)) and 0xFF).toByte()
        System.arraycopy(nonce, 0, out, 9, 12)
        for (i in 0 until 8) out[21 + i] = ((tsMs ushr (56 - i * 8)) and 0xFF).toByte()
        return out
    }

    /** Pong = byte copy of the ping with the type flipped (same as every shipped build). */
    fun pongFor(ping: ByteArray): ByteArray? {
        if (ping.size < PING_BYTES || ping[0] != PING_TYPE) return null
        val pong = ping.copyOf()
        pong[0] = PONG_TYPE
        return pong
    }

    /** STUN Binding Success Response header: type 0x0101 + magic cookie 0x2112A442. */
    fun isStunBindingSuccess(data: ByteArray): Boolean =
        data.size >= 20 && data[0] == 0x01.toByte() && data[1] == 0x01.toByte() &&
            data[4] == 0x21.toByte() && data[5] == 0x12.toByte() &&
            data[6] == 0xA4.toByte() && data[7] == 0x42.toByte()

    fun stunTransactionHex(data: ByteArray): String? {
        if (data.size < 20) return null
        return buildString { for (i in 8 until 20) append("%02x".format(data[i])) }
    }
}
