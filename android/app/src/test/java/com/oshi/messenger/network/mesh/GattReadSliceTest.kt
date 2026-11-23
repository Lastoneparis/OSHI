package com.oshi.messenger.network.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The GATT long-read contract for characteristic 0542 — the one an iPhone reads
 * to learn this phone's public key, IP and TCP port.
 *
 * A client reading a value longer than ATT_MTU−1 issues successive READ_BLOB
 * requests and stops only on a response SHORTER than the MTU. So the response at
 * `offset == size` must be EMPTY. Answering it with the whole payload again
 * hands the client a duplicated JSON tail, its parser throws, and the peer is
 * dropped — on iOS without so much as a log line
 * (OSHI/CrossPlatformMesh.swift:1431).
 *
 * iOS's own server gets this right at OSHI/CrossPlatformMesh.swift:1520-1524.
 * These tests hold Android to the same behaviour.
 */
class GattReadSliceTest {

    private val payload = """{"pk":"abc","name":"Hugo","port":34601,"platform":"android","ip":"10.0.0.7"}"""
        .toByteArray(Charsets.UTF_8)

    @Test
    fun `offset zero returns the whole payload`() {
        assertArrayEquals(payload, CrossPlatformMesh.gattReadSlice(payload, 0))
    }

    @Test
    fun `a mid-payload offset returns only the remainder`() {
        val slice = CrossPlatformMesh.gattReadSlice(payload, 10)
        assertArrayEquals(payload.copyOfRange(10, payload.size), slice)
        assertEquals(payload.size - 10, slice.size)
    }

    @Test
    fun `the terminating read at exactly the end returns EMPTY`() {
        // This is the case the old `else` branch answered with the full payload.
        assertEquals(
            "offset == size must terminate the read, not restart it",
            0, CrossPlatformMesh.gattReadSlice(payload, payload.size).size,
        )
    }

    @Test
    fun `an offset past the end returns EMPTY rather than the payload again`() {
        assertEquals(0, CrossPlatformMesh.gattReadSlice(payload, payload.size + 1).size)
        assertEquals(0, CrossPlatformMesh.gattReadSlice(payload, payload.size + 500).size)
    }

    @Test
    fun `reassembling every blob reproduces the payload exactly once`() {
        // Walk the payload the way a real GATT client does, at a deliberately
        // small MTU so several blobs are needed, and assert the concatenation is
        // the payload — not the payload plus a duplicated copy.
        val mtuPayload = 20
        val assembled = mutableListOf<Byte>()
        var offset = 0
        var guard = 0
        while (guard++ < 100) {
            val chunk = CrossPlatformMesh.gattReadSlice(payload, offset).take(mtuPayload)
            if (chunk.isEmpty()) break
            assembled += chunk
            offset += chunk.size
        }
        assertArrayEquals(
            "blob reassembly must yield the payload exactly once",
            payload, assembled.toByteArray(),
        )
    }

    @Test
    fun `an empty payload terminates immediately`() {
        assertEquals(0, CrossPlatformMesh.gattReadSlice(ByteArray(0), 0).size)
        assertEquals(0, CrossPlatformMesh.gattReadSlice(ByteArray(0), 1).size)
    }

    @Test
    fun `a negative offset is treated as the start`() {
        assertArrayEquals(payload, CrossPlatformMesh.gattReadSlice(payload, -1))
    }
}
