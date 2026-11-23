package com.oshi.messenger.service.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** __LORA_ONLINE_COUNT_2026_09_22__ The "LoRa N" number on the Messages top bar. */
class LoRaOnlineCountTest {

    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    @Test
    fun `counts other nodes heard within two hours, never our own radio`() {
        val nodes = listOf(
            LoRaMeshNode(num = 1u, lastHeard = now),               // our radio
            LoRaMeshNode(num = 2u, lastHeard = now - hour),         // online
            LoRaMeshNode(num = 3u, lastHeard = now - 3 * hour),     // stale
            LoRaMeshNode(num = 4u, lastHeard = 0L),                 // never heard
        )
        assertEquals(1, LoRaOnline.count(nodes, myNodeNum = 1u, now = now))
    }

    @Test
    fun `NodeInfo last_heard is read from field 5`() {
        val secs = 1_750_000_000
        val d = ProtoWriter().varint(1, 9).fixed32(5, secs).data
        assertEquals(secs * 1000L, LoRaPacketParser.parseNodeInfo(d)!!.lastHeardMillis)
    }

    @Test
    fun `a 1970 last_heard from a radio without a clock is ignored`() {
        val d = ProtoWriter().varint(1, 9).fixed32(5, 42).data
        assertNull(LoRaPacketParser.parseNodeInfo(d)!!.lastHeardMillis)
    }
}
