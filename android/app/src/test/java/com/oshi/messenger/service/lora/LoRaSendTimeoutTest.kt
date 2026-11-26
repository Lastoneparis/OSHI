package com.oshi.messenger.service.lora

import com.oshi.messenger.service.lora.OshiMeshProtocol.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __LORA_SEND_TIMEOUT_2026_11_23__ An OMP message with no terminal status used to stay "sent" for ever.
 * These pin the two deadlines and, above all, what must NOT time out: a message legitimately held by
 * the airtime budget, and one in custody.
 */
class LoRaSendTimeoutTest {

    private val min = 60_000L
    private val t0 = 1_000_000L

    @Test
    fun `no status at all fails after the handover deadline, frames included`() {
        val t = LoRaSendTimeout()
        t.onHandover("m", frames = 44, broadcast = false, nowMs = t0)
        val deadline = t0 + LoRaSendTimeout.NO_STATUS_MS + 44 * LoRaSendTimeout.PER_FRAME_MS
        assertTrue(t.expired(deadline - 1).isEmpty())
        assertEquals(listOf("m"), t.expired(deadline))
        assertFalse("reported once", t.hasPending())
        assertTrue(t.expired(deadline + 10 * min).isEmpty())
    }

    @Test
    fun `a QUEUED message waits out the airtime budget without failing`() {
        val t = LoRaSendTimeout()
        t.onHandover("m", frames = 10, broadcast = false, nowMs = t0)
        t.onStatus("m", State.QUEUED, t0 + 5_000)
        // A full hour of spent airtime budget, and then some: still waiting, not failed.
        assertTrue(t.expired(t0 + 90 * min).isEmpty())
        assertTrue(t.isTracked("m"))
        assertEquals(listOf("m"), t.expired(t0 + 5_000 + LoRaSendTimeout.PROGRESS_MS))
    }

    @Test
    fun `SENT on a unicast restarts the progress clock`() {
        val t = LoRaSendTimeout()
        t.onHandover("m", 10, broadcast = false, nowMs = t0)
        t.onStatus("m", State.QUEUED, t0)
        t.onStatus("m", State.SENT, t0 + 60 * min)
        assertTrue(t.expired(t0 + 150 * min).isEmpty())
        assertEquals(listOf("m"), t.expired(t0 + 60 * min + LoRaSendTimeout.PROGRESS_MS))
    }

    @Test
    fun `SENT ends a broadcast, which never gets a DELIVERED`() {
        val t = LoRaSendTimeout()
        t.onHandover("b", 3, broadcast = true, nowMs = t0)
        t.onStatus("b", State.QUEUED, t0)
        t.onStatus("b", State.SENT, t0 + min)
        assertFalse(t.hasPending())
        assertTrue(t.expired(t0 + 24 * 60 * min).isEmpty())
    }

    @Test
    fun `custody never times out`() {
        val t = LoRaSendTimeout()
        t.onHandover("m", 10, broadcast = false, nowMs = t0)
        t.onStatus("m", State.QUEUED, t0)
        t.onStatus("m", State.SENT, t0 + min)
        t.onStatus("m", State.IN_CUSTODY, t0 + 10 * min)
        assertFalse(t.isTracked("m"))
        assertTrue(t.expired(t0 + 72 * 60 * min).isEmpty())
    }

    @Test
    fun `every terminal state ends tracking`() {
        for (s in listOf(State.DELIVERED, State.IN_CUSTODY, State.UPLINKED, State.FAILED, State.REJECTED)) {
            val t = LoRaSendTimeout()
            t.onHandover("m", 1, broadcast = false, nowMs = t0)
            t.onStatus("m", s, t0 + 1)
            assertFalse(s.name, t.hasPending())
        }
    }

    @Test
    fun `a status for an untracked message is ignored`() {
        val t = LoRaSendTimeout()
        t.onStatus("x", State.QUEUED, t0)
        assertFalse(t.hasPending())
    }

    @Test
    fun `a resend restarts the clock and forget stops it`() {
        val t = LoRaSendTimeout()
        t.onHandover("m", 0, broadcast = false, nowMs = t0)
        t.onHandover("m", 0, broadcast = false, nowMs = t0 + 2 * min)
        assertTrue(t.expired(t0 + LoRaSendTimeout.NO_STATUS_MS).isEmpty())
        assertEquals(listOf("m"), t.expired(t0 + 2 * min + LoRaSendTimeout.NO_STATUS_MS))
        t.onHandover("n", 0, broadcast = false, nowMs = t0)
        t.forget("n")
        assertTrue(t.expired(t0 + 999 * min).isEmpty())
    }

    @Test
    fun `only overdue messages expire`() {
        val t = LoRaSendTimeout()
        t.onHandover("a", 0, broadcast = false, nowMs = t0)
        t.onHandover("b", 0, broadcast = false, nowMs = t0)
        t.onStatus("b", State.QUEUED, t0 + 1)
        assertEquals(listOf("a"), t.expired(t0 + LoRaSendTimeout.NO_STATUS_MS))
        assertTrue(t.isTracked("b"))
    }

    /** The deadlines must stay beyond what the firmware itself can legitimately take. */
    @Test
    fun `deadlines stay beyond the firmware's own windows`() {
        // Rolling 60-minute airtime window + 5 repair rounds of 25 s + 3 s x 64 fragments.
        val firmwareWorst = 60 * min + 5 * (25_000L + 3_000L * 64)
        assertTrue(LoRaSendTimeout.PROGRESS_MS > firmwareWorst)
        assertEquals(LoRaAirtimeWait.FORGET_AFTER_MS, LoRaSendTimeout.PROGRESS_MS)
        assertEquals(MeshtasticManager.SEND_SPACING_MS, LoRaSendTimeout.PER_FRAME_MS)
    }
}
