package com.oshi.messenger.service.p2p

import com.oshi.messenger.service.CallKeyEncoding
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __TWO_MODE_CALL_PATH_2026_09_23__ Regressions from call B37DC742 (Android on 5G ⇄ iPhone on
 * 5G+VPN, 2026-09-23 13:27:55): the call never went direct nor via TURN.
 */
class TwoModeCallPathTest {

    @After fun resetFlag() { P2PPathPolicy.turnRelayMediaEnabled = false }

    // ── Root cause #1: the iPhone's key was mis-spelled by Android ────────────────────────

    /**
     * The REAL key of the iPhone in that call. The server hands Android the caller's key in
     * base64url (`…nhaGN_muFE3A`); the shipped `publicKeyToBase64url` decoded it with
     * `android.util.Base64`, which silently SKIPS `-`/`_`, re-encoded 31 bytes, and addressed
     * every ICE/accept/end signal and JSON audio frame to `…nhaGNmuFE3A` — a key nobody polls.
     * Found on the server: two `7rroH0wvfjy0->eAbMQUdW6Utu` legs in one CALL SUMMARY, and a
     * message queue named `eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGNmuFE3A==`.
     */
    @Test fun iphoneKeyFromTheCallKeepsItsUnderscore() {
        val std = "eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN/muFE3A="
        val url = "eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN_muFE3A"
        assertEquals(url, CallKeyEncoding.toBase64url(url))
        assertEquals(url, CallKeyEncoding.toBase64url(std))
        assertEquals(url, CallKeyEncoding.toBase64url("$url=="))
        // What the shipped build produced — must never come out again.
        assertTrue(CallKeyEncoding.toBase64url(url) != "eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGNmuFE3A")
    }

    @Test fun everyUrlSafeKeyRoundTripsTo32Bytes() {
        val rnd = java.util.Random(42)
        repeat(500) {
            val raw = ByteArray(32).also { rnd.nextBytes(it) }
            val url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
            val std = java.util.Base64.getEncoder().encodeToString(raw)
            assertEquals(url, CallKeyEncoding.toBase64url(url))
            assertEquals(url, CallKeyEncoding.toBase64url(std))
            assertArrayEquals(raw, java.util.Base64.getUrlDecoder().decode(CallKeyEncoding.toBase64url(url)))
        }
    }

    // ── Two modes: coturn is not a media path ─────────────────────────────────────────────

    @Test fun relayCandidatesAreNotAPathByDefault() {
        assertFalse(P2PPathPolicy.acceptsRemoteCandidate(P2PTransport.CandidateType.RELAY))
        assertTrue(P2PPathPolicy.acceptsRemoteCandidate(P2PTransport.CandidateType.HOST))
        assertTrue(P2PPathPolicy.acceptsRemoteCandidate(P2PTransport.CandidateType.SRFLX))
        P2PPathPolicy.turnRelayMediaEnabled = true
        assertTrue(P2PPathPolicy.acceptsRemoteCandidate(P2PTransport.CandidateType.RELAY))
    }

    // ── Media-sized validation (5G+VPN drops fragments, small pings still pass) ───────────

    @Test fun aPairThatOnlyPongsSmallPingsCannotCarryMedia() {
        val now = 1_000_000L
        assertFalse(P2PPathPolicy.pairSelectable(isLive = true, lastJumboPongAtMs = 0L, nowMs = now))
        assertTrue(P2PPathPolicy.pairSelectable(isLive = true, lastJumboPongAtMs = now - 500, nowMs = now))
        assertFalse(P2PPathPolicy.pairSelectable(isLive = false, lastJumboPongAtMs = now - 500, nowMs = now))
        // Jumbo pongs stopped (VPN switched on mid-call) ⇒ no longer selectable.
        assertFalse(P2PPathPolicy.pairSelectable(true, now - P2PPathPolicy.JUMBO_MAX_AGE_MS - 1, now))
    }

    @Test fun jumboProbeIsMediaSizedAndFitsEveryReceiveBuffer() {
        val ping = P2PPathPolicy.buildPing(0x0102030405060708L, ByteArray(12) { 7 }, 99L, P2PPathPolicy.JUMBO_PROBE_BYTES)
        assertEquals(P2PPathPolicy.JUMBO_PROBE_BYTES, ping.size)
        assertTrue(ping.size >= 1_930)   // 48 kHz PCM frame + header
        assertTrue(ping.size < 2_048)    // Android RECV_BUFFER_SIZE / iOS drain buffer
        val pong = P2PPathPolicy.pongFor(ping)
        assertNotNull(pong)
        assertEquals(ping.size, pong!!.size)              // echo keeps the size ⇒ both directions proven
        assertEquals(P2PPathPolicy.PONG_TYPE, pong[0])
        assertTrue(P2PPathPolicy.isJumbo(pong.size))
        assertFalse(P2PPathPolicy.isJumbo(P2PPathPolicy.PING_BYTES))
    }

    @Test fun plainPingWireIsUnchanged() {
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val ping = P2PPathPolicy.buildPing(1L, nonce, 0x0A0B0C0D0E0F1011L)
        assertEquals(29, ping.size)
        assertEquals(0x31.toByte(), ping[0])
        assertEquals(1.toByte(), ping[8])
        assertArrayEquals(nonce, ping.copyOfRange(9, 21))
        assertEquals(0x11.toByte(), ping[28])
        assertNull(P2PPathPolicy.pongFor(ByteArray(10) { 0x31 }))
    }

    @Test fun jumboCadence() {
        assertFalse(P2PPathPolicy.shouldSendJumbo(pairLive = false, verified = false, tick = 0))
        assertTrue(P2PPathPolicy.shouldSendJumbo(true, false, 0))
        assertFalse(P2PPathPolicy.shouldSendJumbo(true, false, 1))
        assertTrue(P2PPathPolicy.shouldSendJumbo(true, true, 4))
        assertFalse(P2PPathPolicy.shouldSendJumbo(true, true, 2))
    }

    // ── srflx on the media socket: STUN demux must not eat media ───────────────────────────

    @Test fun stunSuccessIsRecognisedAndMediaIsNot() {
        val stun = ByteArray(32).also {
            it[0] = 0x01; it[1] = 0x01; it[4] = 0x21; it[5] = 0x12; it[6] = 0xA4.toByte(); it[7] = 0x42
            for (i in 8 until 20) it[i] = i.toByte()
        }
        assertTrue(P2PPathPolicy.isStunBindingSuccess(stun))
        assertEquals("08090a0b0c0d0e0f10111213", P2PPathPolicy.stunTransactionHex(stun))
        val audio = ByteArray(200).also { it[0] = 0x01; it[1] = 0x01 }   // OSHI type 0x01 frame
        assertFalse(P2PPathPolicy.isStunBindingSuccess(audio))
        assertFalse(P2PPathPolicy.isStunBindingSuccess(ByteArray(8)))
    }

    @Test fun stunClientParsesAResponseBuiltForItsOwnTransaction() {
        val client = StunClient()
        val (req, tx) = client.buildBindingRequest()
        assertEquals(20, req.size)
        // XOR-MAPPED-ADDRESS 194.230.146.101:27617
        val port = 27617 xor 0x2112
        val ip = byteArrayOf(194.toByte(), 230.toByte(), 146.toByte(), 101)
        val cookie = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
        val resp = ByteArray(32)
        resp[0] = 0x01; resp[1] = 0x01; resp[3] = 12
        System.arraycopy(cookie, 0, resp, 4, 4)
        System.arraycopy(tx, 0, resp, 8, 12)
        resp[20] = 0x00; resp[21] = 0x20; resp[23] = 8
        resp[25] = 0x01
        resp[26] = (port ushr 8).toByte(); resp[27] = port.toByte()
        for (i in 0 until 4) resp[28 + i] = (ip[i].toInt() xor cookie[i].toInt()).toByte()
        assertTrue(P2PPathPolicy.isStunBindingSuccess(resp))
        val parsed = client.parseBindingResponse(resp, resp.size, tx)
        assertEquals("194.230.146.101", parsed?.ip)
        assertEquals(27617, parsed?.port)
    }
}
