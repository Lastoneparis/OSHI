package com.oshi.messenger.service.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** __CALL_TX_SILENT_DROP_2026_11_10__ call 608d4c6a: voice captured, 2 frames sent in 56 s. */
class TxHealthTest {
    @Test fun `a failing encoder is rebuilt twice, then Opus is dropped for the call`() {
        val h = TxHealth()
        val actions = (1..30).map { h.opusFailed(IllegalStateException("bad state")) }
        assertEquals(TxHealth.Action.RESET_ENCODER, actions[9])
        assertEquals(TxHealth.Action.RESET_ENCODER, actions[19])
        assertEquals(TxHealth.Action.DISABLE_OPUS, actions[29])
        assertEquals(27, actions.count { it == TxHealth.Action.NONE })
        assertTrue(h.opusDisabled)
        assertEquals(30L, h.dropped)
        assertTrue(h.lastError!!.startsWith("opus IllegalStateException"))
    }

    @Test fun `an occasional failure between good frames never triggers recovery`() {
        val h = TxHealth()
        repeat(100) {
            assertEquals(TxHealth.Action.NONE, h.opusFailed())
            h.opusOk()
        }
        assertEquals(0, h.opusResets)
    }

    @Test fun `drops are counted per stage and reset per call`() {
        val h = TxHealth()
        h.noKey(); h.noKey(); h.error(RuntimeException("x"))
        assertEquals(3L, h.dropped)
        assertEquals("RuntimeException: x", h.lastError)
        h.reset()
        assertEquals(0L, h.dropped)
    }
}
