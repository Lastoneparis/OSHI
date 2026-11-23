package com.oshi.messenger.service.lora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "OM" envelope: the only bytes an iPhone and an Android phone must agree on
 * over LoRa.
 *
 * Every layout assertion below is checked TWICE: once against a literal fixture
 * written by hand in the iOS layout (MeshtasticManager.swift:520-525), and once
 * by round-tripping our own encoder through our own parser. A round-trip alone
 * proves only that we agree with ourselves.
 */
class LoRaEnvelopeTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // ───────────────────────── layout ─────────────────────────

    @Test
    fun `frame matches the literal iOS layout`() {
        // "OM" ‖ msgId LITTLE-endian ‖ seq ‖ total ‖ chunk. :521-525
        val fixture = bytes(0x4F, 0x4D, 0xEF, 0xBE, 0xAD, 0xDE, 0x01, 0x03, 0x41, 0x42)
        val produced = LoRaEnvelope.frame(0xDEADBEEFu, seq = 1, total = 3, chunk = bytes(0x41, 0x42))
        assertArrayEquals(fixture, produced)
    }

    @Test
    fun `parses the literal iOS fixture back`() {
        val fixture = bytes(0x4F, 0x4D, 0xEF, 0xBE, 0xAD, 0xDE, 0x01, 0x03, 0x41, 0x42)
        val h = LoRaEnvelope.parse(fixture)!!
        assertEquals(0xDEADBEEFu, h.msgId)
        assertEquals(1, h.seq)
        assertEquals(3, h.total)
        assertArrayEquals(bytes(0x41, 0x42), h.chunk)
    }

    @Test
    fun `msgId is little-endian, not big-endian`() {
        // The single most likely silent break between two platforms.
        val f = LoRaEnvelope.frame(0x01020304u, 0, 1, bytes(0xFF))
        assertArrayEquals(bytes(0x04, 0x03, 0x02, 0x01), f.copyOfRange(2, 6))
    }

    @Test
    fun `header is exactly eight bytes`() {
        assertEquals(8, LoRaEnvelope.HEADER_BYTES)
        assertEquals(9, LoRaEnvelope.frame(1u, 0, 1, bytes(0x00)).size)
    }

    // ───────────────────────── chunk boundaries ─────────────────────────

    @Test
    fun `chunk boundary sits at exactly 180 bytes`() {
        assertEquals(1, LoRaEnvelope.chunk(ByteArray(180)).size)
        assertEquals(2, LoRaEnvelope.chunk(ByteArray(181)).size)
        assertEquals(180, LoRaEnvelope.chunk(ByteArray(181))[0].size)
        assertEquals(1, LoRaEnvelope.chunk(ByteArray(181))[1].size)
    }

    @Test
    fun `2160 bytes is twelve chunks and is accepted`() {
        // 12 × 180 — the exact ceiling MessageSendPolicy budgets against.
        val frames = LoRaEnvelope.frames(1u, ByteArray(2160))
        assertNotNull(frames)
        assertEquals(12, frames!!.size)
        frames.forEachIndexed { i, f ->
            assertEquals(188, f.size)          // 8 header + 180 payload
            assertEquals(i, f[6].toInt())
            assertEquals(12, f[7].toInt())
        }
    }

    @Test
    fun `2161 bytes is thirteen chunks and is REFUSED`() {
        // :514-519 — the radio's TX queue is shallow and LongFast airtime is
        // ~1-2 s/frame; a larger send overflows the queue and dies in reassembly.
        assertEquals(13, LoRaEnvelope.chunk(ByteArray(2161)).size)
        assertNull(LoRaEnvelope.frames(1u, ByteArray(2161)))
    }

    @Test
    fun `single-byte payload still produces one frame`() {
        assertEquals(1, LoRaEnvelope.frames(1u, ByteArray(1))!!.size)
    }

    // ───────────────────────── frame validation ─────────────────────────

    @Test
    fun `rejects a frame with no chunk bytes`() {
        // :1063 — `payload.count > 8`, not >=. A header alone carries nothing.
        assertNull(LoRaEnvelope.parse(bytes(0x4F, 0x4D, 0x01, 0, 0, 0, 0x00, 0x01)))
    }

    @Test
    fun `rejects wrong magic`() {
        assertNull(LoRaEnvelope.parse(bytes(0x4F, 0x4E, 0x01, 0, 0, 0, 0x00, 0x01, 0xAA)))
        assertNull(LoRaEnvelope.parse(bytes(0x00, 0x4D, 0x01, 0, 0, 0, 0x00, 0x01, 0xAA)))
    }

    @Test
    fun `rejects total zero, seq out of range and total over forty`() {
        // :1068
        assertNull(LoRaEnvelope.parse(bytes(0x4F, 0x4D, 1, 0, 0, 0, 0x00, 0x00, 0xAA)))  // total 0
        assertNull(LoRaEnvelope.parse(bytes(0x4F, 0x4D, 1, 0, 0, 0, 0x02, 0x02, 0xAA)))  // seq == total
        assertNull(LoRaEnvelope.parse(bytes(0x4F, 0x4D, 1, 0, 0, 0, 0x00, 41, 0xAA)))    // total 41
        assertNotNull(LoRaEnvelope.parse(bytes(0x4F, 0x4D, 1, 0, 0, 0, 0x00, 40, 0xAA))) // total 40 is fine
    }

    @Test
    fun `receiver accepts more chunks than the sender will ever produce`() {
        // Sender caps at 12 (:514), receiver at 40 (:1068). Deliberately
        // asymmetric: we must cope with whatever a peer put on the air.
        assertTrue(LoRaEnvelope.MAX_TOTAL > LoRaEnvelope.MAX_CHUNKS)
    }

    // ───────────────────────── reassembly ─────────────────────────

    private fun framesFor(msgId: UInt, payload: ByteArray) = LoRaEnvelope.frames(msgId, payload)!!

    @Test
    fun `reassembles in order`() {
        val payload = ByteArray(400) { (it % 251).toByte() }
        val r = LoRaReassembler()
        val f = framesFor(7u, payload)
        assertEquals(3, f.size)
        assertNull(r.accept(f[0], 0))
        assertNull(r.accept(f[1], 10))
        assertArrayEquals(payload, r.accept(f[2], 20))
        assertEquals(0, r.pendingCount)
    }

    @Test
    fun `reassembles OUT of order`() {
        // LoRa has no ordering guarantee and a relayed frame routinely overtakes.
        val payload = ByteArray(400) { (it % 251).toByte() }
        val r = LoRaReassembler()
        val f = framesFor(7u, payload)
        assertNull(r.accept(f[2], 0))
        assertNull(r.accept(f[0], 1))
        assertArrayEquals(payload, r.accept(f[1], 2))
    }

    @Test
    fun `a duplicate frame does not complete a message early`() {
        // :1071 — parts[seq] OVERWRITES, so the count does not advance.
        val payload = ByteArray(400)
        val r = LoRaReassembler()
        val f = framesFor(7u, payload)
        assertNull(r.accept(f[0], 0))
        assertNull(r.accept(f[0], 1))
        assertNull(r.accept(f[0], 2))
        assertNull(r.accept(f[1], 3))
        assertArrayEquals(payload, r.accept(f[2], 4))
    }

    @Test
    fun `a missing part never completes`() {
        val r = LoRaReassembler()
        val f = framesFor(7u, ByteArray(400))
        assertNull(r.accept(f[0], 0))
        assertNull(r.accept(f[2], 1))
        assertEquals(1, r.pendingCount)
    }

    @Test
    fun `two messages interleave without contaminating each other`() {
        val a = ByteArray(300) { 0x41 }
        val b = ByteArray(300) { 0x42 }
        val r = LoRaReassembler()
        val fa = framesFor(1u, a)
        val fb = framesFor(2u, b)
        assertNull(r.accept(fa[0], 0))
        assertNull(r.accept(fb[0], 1))
        assertArrayEquals(b, r.accept(fb[1], 2))
        assertArrayEquals(a, r.accept(fa[1], 3))
    }

    @Test
    fun `partials older than 180 seconds are collected`() {
        // :1073-1074
        val r = LoRaReassembler()
        val f = framesFor(7u, ByteArray(400))
        assertNull(r.accept(f[0], 0))
        assertEquals(1, r.pendingCount)
        // Any later frame sweeps the map. A frame for a different message at
        // t = 180 s evicts the stale partial…
        val other = framesFor(8u, ByteArray(400))
        assertNull(r.accept(other[0], LoRaEnvelope.REASSEMBLY_TTL_MS))
        assertEquals(1, r.pendingCount)   // only the new one survives
        // …so the rest of the original message can no longer complete it.
        assertNull(r.accept(f[1], LoRaEnvelope.REASSEMBLY_TTL_MS + 1))
        assertNull(r.accept(f[2], LoRaEnvelope.REASSEMBLY_TTL_MS + 2))
    }

    @Test
    fun `a partial just under the TTL survives`() {
        val payload = ByteArray(400)
        val r = LoRaReassembler()
        val f = framesFor(7u, payload)
        assertNull(r.accept(f[0], 0))
        assertNull(r.accept(f[1], LoRaEnvelope.REASSEMBLY_TTL_MS - 1))
        assertArrayEquals(payload, r.accept(f[2], LoRaEnvelope.REASSEMBLY_TTL_MS - 1))
    }

    @Test
    fun `a rejected frame is not held`() {
        val r = LoRaReassembler()
        assertNull(r.accept(bytes(0x00, 0x00, 1, 2, 3, 4, 0, 1, 0xAA), 0))
        assertEquals(0, r.pendingCount)
    }

    @Test
    fun `a full twelve-frame message survives the round trip`() {
        val payload = ByteArray(2160) { (it % 256).toByte() }
        val r = LoRaReassembler()
        val f = framesFor(0xCAFEBABEu, payload)
        f.dropLast(1).forEachIndexed { i, frame -> assertNull(r.accept(frame, i.toLong())) }
        assertArrayEquals(payload, r.accept(f.last(), 99))
    }
}
