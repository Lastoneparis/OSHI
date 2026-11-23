package com.oshi.messenger

import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.service.GroupReceipts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __GROUP_RECEIPTS_2026_11_02__ (N8) who received and who read one group
 * message.
 *
 * Port contract against `OSHITests/GroupReceiptsTests.swift`. The wire format
 * is what the two platforms agree on, so the JSON keys and the kind codes are
 * pinned here as well as there — a change on one side alone would make a
 * receipt from an iPhone unreadable on Android and show nothing, silently.
 *
 * The store itself needs a `Context`, so it is covered by the instrumented
 * side; everything here is the pure logic, which is where the rules live.
 */
class GroupReceiptsTest {

    private val g = "11111111-2222-3333-4444-555555555555"

    // ---------------------------------------------------------------- wire

    @Test
    fun `round trip`() {
        val wire = GroupReceipts.encode(g, "ABC-123", GroupReceipts.Kind.READ, 1_790_000_000_000L)
        assertTrue(wire.startsWith(GroupReceipts.PREFIX))
        val p = GroupReceipts.parse(wire)
        assertNotNull(p)
        assertEquals(g, p!!.groupId)
        assertEquals("ABC-123", p.msgId)
        assertEquals(GroupReceipts.Kind.READ, p.kind)
        assertEquals(1_790_000_000_000L, p.at)
    }

    /**
     * The exact bytes iOS puts on the wire. If this breaks, an iPhone's
     * receipt stops being understood here and the sheet quietly shows nothing.
     */
    @Test
    fun `parses the iOS payload verbatim`() {
        val fromIOS = GroupReceipts.PREFIX +
            """{"g":"$g","m":"D1F2-AAAA","k":"d","t":1790000000}"""
        val p = GroupReceipts.parse(fromIOS)
        assertNotNull(p)
        assertEquals(GroupReceipts.Kind.RECEIVED, p!!.kind)
        assertEquals("D1F2-AAAA", p.msgId)
        assertEquals(1_790_000_000_000L, p.at)
    }

    /**
     * The sentinel must be in the catalog. An unregistered one renders in the
     * thread as a line of JSON — the failure the catalog exists to prevent.
     */
    @Test
    fun `sentinel is registered as a silent control payload`() {
        val entry = OSHIControlPayload.catalog.firstOrNull { it.prefix == GroupReceipts.PREFIX }
        assertNotNull("📑GROUP_RECEIPT📑 must be in OSHIControlPayload.catalog", entry)
        assertEquals(OSHIControlPayload.Kind.SILENT, entry!!.kind)
    }

    @Test
    fun `rejects foreign and malformed payloads`() {
        assertNull(GroupReceipts.parse(null))
        assertNull(GroupReceipts.parse("hello"))
        assertNull(GroupReceipts.parse("📖READ_RECEIPT📖"))
        assertNull(GroupReceipts.parse(GroupReceipts.PREFIX + "not json"))
        assertNull(GroupReceipts.parse(GroupReceipts.PREFIX + """{"g":"$g","k":"r"}"""))
        // An unknown kind must not silently count as "read".
        assertNull(GroupReceipts.parse(GroupReceipts.PREFIX + """{"g":"$g","m":"x","k":"zz"}"""))
    }

    /** A remote-supplied map key: an absurd one is refused, not stored. */
    @Test
    fun `rejects an overlong message id`() {
        val huge = "A".repeat(500)
        assertNull(GroupReceipts.parse(GroupReceipts.PREFIX + """{"g":"$g","m":"$huge","k":"d"}"""))
    }

    // ---------------------------------------------------------------- rows

    /**
     * A member nobody has heard from MUST appear. Omitting them would read as
     * everyone having received the message, which is the opposite of true.
     */
    @Test
    fun `rows include silent members and exclude the sender`() {
        val states = mapOf(
            "READER" to GroupReceipts.MemberState(receivedAt = 1L, readAt = 2L),
            "GOT_IT" to GroupReceipts.MemberState(receivedAt = 1L, readAt = null),
        )
        val rows = GroupReceipts.rows(listOf("ME", "READER", "GOT_IT", "SILENT"), "ME", states)
        assertEquals(3, rows.size)
        assertFalse(rows.any { it.memberKey == "ME" })
        assertEquals(listOf("READER", "GOT_IT", "SILENT"), rows.map { it.memberKey })
        assertTrue(rows[0].hasRead)
        assertTrue(rows[1].hasReceived)
        assertFalse(rows[1].hasRead)
        assertFalse(rows[2].hasReceived)
    }

    /** Same ordering rule as iOS: read, then delivered, then unknown, then by key. */
    @Test
    fun `ordering is stable for members in the same state`() {
        val rows = GroupReceipts.rows(listOf("B", "A", "C"), "ME", emptyMap())
        assertEquals(listOf("A", "B", "C"), rows.map { it.memberKey })
    }
}
