package com.oshi.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __LATE_ACCEPT_2026_11_23__ / __EGRESS_STALL_2026_11_23__
 * Late accept (call ea1fac0f, 22/11: accepted 1 s after the caller timed out, 358 frames to
 * nobody) and the "stop streaming to nobody" rule.
 */
class CallStreamGuardsTest {

    // ── Late accept: what the server's reply to callAccept means ─────────────────

    @Test fun `today's server reply is not an end`() {
        assertFalse(LateAcceptPolicy.acceptReplySaysEnded(200, """{"success":true,"delivery":"websocket+queued"}"""))
        assertFalse(LateAcceptPolicy.acceptReplySaysEnded(200, """{"success":true,"delivery":"queued"}"""))
    }

    @Test fun `410 Gone is an end whatever the body`() {
        assertTrue(LateAcceptPolicy.acceptReplySaysEnded(410, null))
        assertTrue(LateAcceptPolicy.acceptReplySaysEnded(410, "gone"))
    }

    @Test fun `explicit ended markers in a 2xx body`() {
        assertTrue(LateAcceptPolicy.acceptReplySaysEnded(200, """{"success":false,"callEnded":true}"""))
        assertTrue(LateAcceptPolicy.acceptReplySaysEnded(200, """{"ended":true}"""))
        assertTrue(LateAcceptPolicy.acceptReplySaysEnded(200, """{"state":"ended"}"""))
        assertTrue(LateAcceptPolicy.acceptReplySaysEnded(200, """{"callState":"Terminal"}"""))
        assertTrue(LateAcceptPolicy.acceptReplySaysEnded(202, """{"type":"callEnd","callId":"x"}"""))
        assertTrue(LateAcceptPolicy.acceptReplySaysEnded(200, """{"type":"callCancelled"}"""))
        assertTrue(LateAcceptPolicy.acceptReplySaysEnded(200, """{"type":"callTimeout"}"""))
    }

    @Test fun `non-JSON, errors and other states are not proof of an end`() {
        assertFalse(LateAcceptPolicy.acceptReplySaysEnded(200, "OK"))
        assertFalse(LateAcceptPolicy.acceptReplySaysEnded(200, ""))
        assertFalse(LateAcceptPolicy.acceptReplySaysEnded(200, null))
        assertFalse(LateAcceptPolicy.acceptReplySaysEnded(200, """{"state":"ringing"}"""))
        assertFalse(LateAcceptPolicy.acceptReplySaysEnded(200, """{"type":"callAccept"}"""))
        assertFalse(LateAcceptPolicy.acceptReplySaysEnded(200, """{"callEnded":false}"""))
        // A refused / failed POST is "not delivered", never "the call is over".
        assertFalse(LateAcceptPolicy.acceptReplySaysEnded(403, """{"callEnded":true}"""))
        assertFalse(LateAcceptPolicy.acceptReplySaysEnded(500, null))
        assertFalse(LateAcceptPolicy.acceptReplySaysEnded(404, null))
    }

    // ── Late accept: which callEnd may cut through the CONNECTING guard ─────────

    @Test fun `only an end naming this exact call counts`() {
        assertTrue(LateAcceptPolicy.isEndForThisCall("EA1FAC0F-1234", "ea1fac0f-1234"))
        assertTrue(LateAcceptPolicy.isEndForThisCall(" ea1fac0f-1234\n", "ea1fac0f-1234"))
        assertFalse(LateAcceptPolicy.isEndForThisCall("other-call", "ea1fac0f-1234"))
        assertFalse(LateAcceptPolicy.isEndForThisCall("", "ea1fac0f-1234"))
        assertFalse(LateAcceptPolicy.isEndForThisCall(null, "ea1fac0f-1234"))
        assertFalse(LateAcceptPolicy.isEndForThisCall("ea1fac0f-1234", null))
        assertFalse(LateAcceptPolicy.isEndForThisCall("", ""))
    }

    // ── Late accept: start media or not ─────────────────────────────────────────

    private fun verdict(
        server: Boolean = false, remote: Boolean = false, recent: Boolean = false,
        still: Boolean = true, ending: Boolean = false,
    ) = LateAcceptPolicy.verdict(server, remote, recent, still, ending)

    @Test fun `a live call proceeds`() {
        assertEquals(LateAcceptPolicy.Verdict.PROCEED, verdict())
    }

    @Test fun `any sign the call is over stops media before it starts`() {
        assertEquals(LateAcceptPolicy.Verdict.CALL_OVER, verdict(server = true))        // server said callEnd
        assertEquals(LateAcceptPolicy.Verdict.CALL_OVER, verdict(remote = true))        // callEnd arrived while connecting
        assertEquals(LateAcceptPolicy.Verdict.CALL_OVER, verdict(recent = true))        // already torn down
        assertEquals(LateAcceptPolicy.Verdict.CALL_OVER, verdict(still = false))        // another call took over
        assertEquals(LateAcceptPolicy.Verdict.CALL_OVER, verdict(ending = true))        // endCall already running
    }

    // ── Egress stall: is anything able to carry our frames? ─────────────────────

    private fun blocked(
        p2p: Boolean = false,
        udpReg: Boolean = false, udpRefused: Boolean = false, udpReply: Long? = null,
        ws: Boolean = false, wsRefused: Boolean = false, wsQueue: Long = 0L, http: Long? = null,
    ) = EgressStallPolicy.egressBlocked(p2p, udpReg, udpRefused, udpReply, ws, wsRefused, wsQueue, http)

    @Test fun `nothing up is blocked`() {
        assertTrue(blocked())
    }

    @Test fun `any one healthy carrier is not blocked`() {
        assertFalse(blocked(p2p = true))
        assertFalse(blocked(udpReg = true, udpReply = 1_000L))
        assertFalse(blocked(ws = true))
        assertFalse(blocked(ws = true, wsQueue = EgressStallPolicy.WS_QUEUE_CLOGGED_BYTES - 1))
    }

    @Test fun `the HTTP fallback carries only while it keeps accepting frames`() {
        assertFalse(blocked(http = 500L))
        assertFalse(blocked(http = EgressStallPolicy.HTTP_FRESH_MS - 1))
        assertTrue(blocked(http = EgressStallPolicy.HTTP_FRESH_MS))
        assertTrue(blocked(http = null))
    }

    @Test fun `a refused relay token is blocked unless the WebSocket carries`() {
        assertTrue(blocked(udpReg = true, udpRefused = true, udpReply = 500L))
        assertFalse(blocked(udpReg = true, udpRefused = true, udpReply = 500L, ws = true))
        assertTrue(blocked(udpRefused = true, ws = true, wsRefused = true))   // WS 4403 too
    }

    @Test fun `a WebSocket whose send queue keeps growing is buffering, not carrying`() {
        assertTrue(blocked(ws = true, wsQueue = EgressStallPolicy.WS_QUEUE_CLOGGED_BYTES))
        assertTrue(blocked(ws = true, wsQueue = 2_000_000L))
    }

    @Test fun `the relay is only silent after one and a half heartbeats`() {
        assertFalse(blocked(udpReg = true, udpReply = 10_500L))   // one lost heartbeat: still up
        assertFalse(blocked(udpReg = true, udpReply = EgressStallPolicy.UDP_SILENT_MS - 1))
        assertTrue(blocked(udpReg = true, udpReply = EgressStallPolicy.UDP_SILENT_MS))
        assertTrue(blocked(udpReg = true, udpReply = null))       // registered flag but never heard
        assertTrue(blocked(udpReg = false, udpReply = 100L))      // nacked: not registered
    }

    // ── Egress stall: when to hang up ───────────────────────────────────────────

    @Test fun `both clocks must reach N`() {
        val n = EgressStallPolicy.STALL_MS
        assertTrue(EgressStallPolicy.shouldEnd(n, n))
        assertFalse(EgressStallPolicy.shouldEnd(n - 1, 60_000L))   // egress back recently
        assertFalse(EgressStallPolicy.shouldEnd(60_000L, n - 1))   // peer still heard: a hiccup on our side
        assertFalse(EgressStallPolicy.shouldEnd(0L, 60_000L))      // muted peer on a working uplink
    }

    @Test fun `N is about eight seconds and below the silence backstop`() {
        assertEquals(8_000L, EgressStallPolicy.STALL_MS)
        assertTrue(EgressStallPolicy.STALL_MS < PeerSilencePolicy.LOST_SILENCE_MS)
        // Total network loss: relay silent window + N, still before the 45 s backstop.
        assertTrue(EgressStallPolicy.UDP_SILENT_MS + EgressStallPolicy.STALL_MS < PeerSilencePolicy.LOST_SILENCE_MS)
    }

    @Test fun `tracker measures an unbroken block and restarts on any carrier`() {
        val t = EgressStallPolicy.Tracker()
        assertEquals(0L, t.update(1_000L, blocked = false))
        assertEquals(0L, t.update(2_000L, blocked = true))
        assertEquals(5_000L, t.update(7_000L, blocked = true))
        assertEquals(0L, t.update(7_500L, blocked = false))        // WS reconnected: hiccup over
        assertEquals(0L, t.update(8_000L, blocked = true))
        assertEquals(8_000L, t.update(16_000L, blocked = true))
        assertTrue(EgressStallPolicy.shouldEnd(t.update(16_000L, true), 9_000L))
        t.reset()
        assertEquals(0L, t.update(20_000L, blocked = true))
    }

    @Test fun `a 5 second handover never hangs up`() {
        val t = EgressStallPolicy.Tracker()
        var now = 0L
        var lastInbound = 0L
        var ended = false
        // 30 s simulated at the stats loop's 500 ms tick: carriers drop at 10 s, back at 15 s,
        // peer audio stops with them and resumes with them.
        while (now <= 30_000L) {
            val down = now in 10_000L until 15_000L
            if (!down) lastInbound = now
            val ms = t.update(now, blocked = down)
            if (EgressStallPolicy.shouldEnd(ms, now - lastInbound)) ended = true
            now += 500L
        }
        assertFalse(ended)
    }

    @Test fun `a dead uplink with a silent peer hangs up after N`() {
        val t = EgressStallPolicy.Tracker()
        var now = 0L
        var endedAt = -1L
        val lastInbound = 0L
        while (now <= 30_000L && endedAt < 0) {
            if (EgressStallPolicy.shouldEnd(t.update(now, blocked = true), now - lastInbound)) endedAt = now
            now += 500L
        }
        assertEquals(EgressStallPolicy.STALL_MS, endedAt)
    }
}
