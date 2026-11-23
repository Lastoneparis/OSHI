package com.oshi.messenger

import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.data.repository.MessageRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEAM PHOTO-LINK, 2026-08-24 — the two wire faults that lost a 1:1 photo.
 *
 * ## 1. The receive gate bet on JSON KEY ORDER, and the iPhone loses that bet
 *
 * iOS builds its legacy media envelope as a Swift `[String: Any]` and serialises it
 * with `JSONSerialization.data(withJSONObject:)` and no `.sortedKeys`
 * (`OSHI/MessageManager.swift:5286-5300`). Swift dictionary iteration order is the
 * per-process seeded hash order, so the SAME iOS build emits `{"type":"media",…}`
 * on one launch and `{"isViewOnce":false,…}` or `{"fileName":"photo.jpg",…}` on the
 * next. A byte-for-byte replica of that dictionary compiled with `swiftc` put
 * `type` first in **2 launches out of 10**.
 *
 * Android's four receive gates asked `decrypted.startsWith("{\"type\":")`
 * (`MessageRepository.kt` mesh / VPS / V2 / cross-platform). On the other 8 launches
 * the photo fell past the media arm into the text arm, was stored as a plain row
 * with no `mediaType` and no `mediaData`, and the bytes were gone for good.
 *
 * iOS's mirror of the same question was never order-dependent — `isMediaMessage`
 * (`OSHI/MessageManager.swift:7058-7105`) does `contains` over a pattern list and
 * then re-parses the whole object — and Android's own GROUP path is fine for the
 * same reason (`GroupManager.kt:1643`). The 1:1 path was the outlier.
 *
 * The permutation cases below are the point of this file: they fail on EVERY
 * ordering the old gate could not see, so a regression to a prefix test cannot pass.
 *
 * ## 2. The outbound legacy caption was escaped for `"` and `\n` and nothing else
 *
 * A backslash in a caption produced invalid JSON. iOS's `isMediaMessage` still
 * matched on the `"type":"IMAGE"` substring, `parseAndStoreMedia` then failed at
 * `JSONSerialization` (`MessageManager.swift:7268-7271`) and stored the base64 photo
 * as a text bubble (`:6116`); Android's own `handleMediaContent` threw and dropped
 * the message outright.
 */
class InlineMediaEnvelopeRoutingTest {

    // ---------------------------------------------------------------- helpers

    /** A short stand-in for the photo; the routing question is about keys, not size. */
    private val b64 = "QUFBQUFBQUFBQUFBQUFBQQ=="

    /**
     * The five key/value pairs iOS puts in `mediaJson` at
     * `OSHI/MessageManager.swift:5286-5292`, plus the optional `fileName` at `:5298`.
     */
    private val iosWrapperPairs = listOf(
        "\"type\":\"media\"",
        "\"mediaType\":\"image\"",
        "\"content\":\"$b64\"",
        "\"size\":16",
        "\"isViewOnce\":false",
        "\"fileName\":\"photo.jpg\"",
    )

    /** Every ordering `JSONSerialization` could hand us for that dictionary. */
    private fun permutations(items: List<String>): List<List<String>> =
        if (items.size <= 1) listOf(items)
        else items.flatMap { head ->
            permutations(items - head).map { listOf(head) + it }
        }

    private fun obj(pairs: List<String>) = pairs.joinToString(",", "{", "}")

    // ------------------------------------------------- 1. the routing gate

    @Test
    fun `iOS media wrapper is recognised in every key order JSONSerialization can emit`() {
        val orders = permutations(iosWrapperPairs)
        // 6! = 720 — the whole space, not a sample.
        assertEquals(720, orders.size)
        val missed = orders.map { obj(it) }.filterNot { OSHIControlPayload.isMediaEnvelope(it) }
        assertTrue(
            "these orderings were not recognised as media: ${missed.take(3)}",
            missed.isEmpty()
        )
    }

    @Test
    fun `the orderings that do not start with type are exactly the ones the old prefix gate lost`() {
        val orders = permutations(iosWrapperPairs).map { obj(it) }
        val lostByOldGate = orders.filterNot { it.startsWith("{\"type\":") }
        // 5/6 of the space: 600 of 720. That is the share of iOS launches on which a
        // photo silently became a text row.
        assertEquals(600, lostByOldGate.size)
        for (o in lostByOldGate) {
            assertTrue("regression: still order-dependent", OSHIControlPayload.isMediaEnvelope(o))
        }
    }

    @Test
    fun `android direct envelope is recognised, whatever the media type`() {
        for (t in listOf("IMAGE", "VIDEO", "AUDIO", "DOCUMENT", "CONTACT")) {
            val direct = """{"caption":"hi","data":"$b64","isViewOnce":false,"type":"$t"}"""
            assertTrue("$t not recognised", OSHIControlPayload.isMediaEnvelope(direct))
        }
    }

    @Test
    fun `iOS cross-platform mesh envelope is recognised - it has no type key at all`() {
        // `CrossPlatformMesh.sendMedia` / `MeshNetworkManager.swift:415-438`:
        // required keys are `data` + `mediaType`, and `type` is only an alias.
        val mesh =
            """{"data":"$b64","mediaType":"image","fileName":"a.jpg","fileSize":16,"caption":"","isViewOnce":false}"""
        assertTrue(OSHIControlPayload.isMediaEnvelope(mesh))
    }

    // ------------------------------------------------- 2. what must NOT be claimed
    //
    // A false positive here is not a text bubble: `handleMediaContent` returns early
    // without inserting anything, so the message would VANISH. Every one of these is
    // something the receive chain really carries.

    @Test
    fun `prose and non-media payloads are refused`() {
        val refused = listOf(
            "hello, look at https://example.com",
            "",
            "   ",
            "{",
            "{not json at all",
            // Ratchet ciphertext that reached a display surface.
            """{"ciphertext":"$b64","header":"$b64","signature":"x"}""",
            // Multi-device sync — matched earlier in every caller, but must not be
            // claimable here either.
            """{"type":"sync_request","groupId":"g1"}""",
            """{"type":"member_sync","groupId":"g1"}""",
            // A media-shaped object with NO payload key.
            """{"type":"media","mediaType":"image","size":16}""",
            // A payload key that is present but empty.
            """{"type":"media","mediaType":"image","content":""}""",
            // A `type` this app does not use, with no `mediaType` to fall back on.
            """{"type":"proposal","data":"$b64"}""",
            // A user who pastes JSON must still see their JSON.
            """{"data":"my notes","note":"grocery list"}""",
        )
        for (r in refused) {
            assertFalse("wrongly claimed as media: $r", OSHIControlPayload.isMediaEnvelope(r))
        }
    }

    @Test
    fun `leading whitespace does not defeat the gate`() {
        val padded = "  \n{\"isViewOnce\":false,\"content\":\"$b64\",\"type\":\"media\"}"
        assertTrue(OSHIControlPayload.isMediaEnvelope(padded))
    }

    // ------------------------------------------------- 3. the outbound caption

    @Test
    fun `a caption containing a backslash still produces parseable JSON`() {
        val caption = """C:\Users\photos — \_(tsu)_/"""
        val wire = MessageRepository.legacyMediaEnvelope("IMAGE", caption, b64, false)

        // This is the exact operation `JSONSerialization.jsonObject(with:)` performs on
        // iOS (`MessageManager.swift:7268`) and `JSONObject(decrypted)` performs in
        // Android's own `handleMediaContent`. Before the fix it threw.
        val parsed = JSONObject(wire)
        assertEquals(caption, parsed.getString("caption"))
        assertEquals("IMAGE", parsed.getString("type"))
        assertEquals(b64, parsed.getString("data"))
        assertEquals(false, parsed.getBoolean("isViewOnce"))
    }

    @Test
    fun `quotes newlines tabs and control characters all survive the caption round trip`() {
        val caption = "say \"hi\"\n\tand\r\u0007done"
        val parsed = JSONObject(MessageRepository.legacyMediaEnvelope("VIDEO", caption, b64, true))
        assertEquals(caption, parsed.getString("caption"))
        assertEquals(true, parsed.getBoolean("isViewOnce"))
    }

    @Test
    fun `a null caption is an empty string, not the literal null`() {
        val parsed = JSONObject(MessageRepository.legacyMediaEnvelope("IMAGE", null, b64, false))
        assertEquals("", parsed.getString("caption"))
    }

    @Test
    fun `the legacy envelope is a shape iOS accepts and this gate recognises`() {
        // Closing the loop: what Android emits must satisfy BOTH iOS's
        // `parseAndStoreMedia` contract (bytes under `data`, uppercase enum name in
        // `type` — MessageManager.swift:7274,7288) and our own inbound gate, so an
        // Android→Android send is not routed differently from an Android→iOS one.
        val wire = MessageRepository.legacyMediaEnvelope("IMAGE", "beach", b64, false)
        assertTrue(OSHIControlPayload.isMediaEnvelope(wire))
        val parsed = JSONObject(wire)
        assertTrue(parsed.getString("type").uppercase() in setOf("IMAGE", "VIDEO", "AUDIO", "DOCUMENT"))
        assertTrue(parsed.optString("data").isNotEmpty())
    }
}
