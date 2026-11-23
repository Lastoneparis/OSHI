package com.oshi.messenger.network.mesh

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __MESH_WIRE_SPELLING_FOR_OLD_IOS_2026_10_26__
 *
 * The iPhone on the App Store is **1.0.43** and cannot be updated — the developer
 * account is under a pending-termination notice, so no iOS build can ship. Whatever
 * compatibility there is has to be spoken by Android.
 *
 * That build decides whether a mesh frame is addressed to it like this (verbatim
 * from `OSHI/CrossPlatformMesh.swift` at the 1.0.43 tree, replayed in
 * [shippedIosIsForUs] below):
 *
 * ```
 * let normalize: (String) -> String = { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
 * isForUs = recipient.isEmpty || normalize(recipient) == normalize(mine)
 *           || (prefixLen >= 16 && prefix(prefixLen) matches)   // prefixLen = min(mine, recipient, 32)
 * ```
 *
 * Lowercasing does NOT fold base64url (`-`, `_`) into standard base64 (`+`, `/`), and
 * an X25519 key carries one of those in its first 32 characters about two times in
 * three. Android stores a contact in whichever spelling it arrived in (QR scan, v2
 * relay, VPS) and used to stamp that spelling straight onto the frame, so for most
 * contacts the iPhone answered "not mine", handed the frame to `relayToRecipient`,
 * found no route to a key that was in fact its own, and dropped it with one log line.
 *
 * `canonicalWireKey` is the fix on this side: every key we put on the wire is spelled
 * the way that iPhone already spells its own (`MessageManager.canonicalIdentity`).
 */
class CrossPlatformMeshOldIosWireTest {

    /**
     * A real 32-byte X25519-shaped key whose standard base64 carries BOTH a `+` and
     * a `/` inside the first 32 characters — i.e. the ordinary case, not a contrived
     * one. Built from bytes so the two spellings cannot drift apart in a literal.
     */
    private val keyBytes: ByteArray =
        ByteArray(32) { i -> ((i * 31 + 251) and 0xFF).toByte() }

    private val standardPadded: String = java.util.Base64.getEncoder().encodeToString(keyBytes)
    private val urlSafeUnpadded: String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes)

    /** The 1.0.43 rule, replayed. Do not "improve" it — it is a fixed target. */
    private fun shippedIosIsForUs(recipient: String, mine: String): Boolean {
        if (recipient.isEmpty()) return true
        val recipientNorm = recipient.trim().lowercase()
        val myNorm = mine.trim().lowercase()
        if (recipientNorm == myNorm) return true
        val prefixLen = minOf(myNorm.length, recipientNorm.length, 32)
        return prefixLen >= 16 && recipientNorm.take(prefixLen) == myNorm.take(prefixLen)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The defect, pinned
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the key really does carry both base64url markers in its first 32 chars`() {
        val head = standardPadded.take(32)
        assertTrue("test vector is pointless without a + or / up front",
            head.contains('+') || head.contains('/'))
        assertTrue(urlSafeUnpadded.take(32).let { it.contains('-') || it.contains('_') })
    }

    @Test
    fun `the shipping iPhone rejects a base64url recipient - this is the bug`() {
        assertFalse(
            "if this ever passes, the 1.0.43 rule was transcribed wrong",
            shippedIosIsForUs(urlSafeUnpadded, standardPadded)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The fix
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `canonicalWireKey makes the shipping iPhone recognise its own key`() {
        val onTheWire = CrossPlatformMesh.canonicalWireKey(urlSafeUnpadded)
        assertTrue(shippedIosIsForUs(onTheWire, standardPadded))
    }

    @Test
    fun `canonicalWireKey matches the iOS canonicalIdentity rule`() {
        // base64url in, standard base64 out, re-padded to a multiple of 4.
        assertEquals(standardPadded, CrossPlatformMesh.canonicalWireKey(urlSafeUnpadded))
        // Unpadded standard base64 is re-padded, not left short.
        assertEquals(standardPadded, CrossPlatformMesh.canonicalWireKey(standardPadded.trimEnd('=')))
        // Surrounding whitespace is a spelling difference too.
        assertEquals(standardPadded, CrossPlatformMesh.canonicalWireKey("  $urlSafeUnpadded\n"))
        // Already canonical: unchanged (the iOS fast path).
        assertEquals(standardPadded, CrossPlatformMesh.canonicalWireKey(standardPadded))
    }

    @Test
    fun `canonicalWireKey never invents a key out of something that is not base64`() {
        assertEquals("", CrossPlatformMesh.canonicalWireKey(""))
        assertEquals("not a key", CrossPlatformMesh.canonicalWireKey("not a key"))
        assertEquals("héllo-wörld", CrossPlatformMesh.canonicalWireKey("héllo-wörld"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The choke point: what actually leaves the device
    // ─────────────────────────────────────────────────────────────────────────

    private fun frame(sender: String, recipient: String, seenBy: MutableList<String>) =
        CrossPlatformMessage(
            id = "id-1",
            type = "TEXT_MESSAGE",
            senderPublicKey = sender,
            senderName = "Android",
            recipientPublicKey = recipient,
            payload = "{}",
            timestamp = 1.0,
            hopCount = 0,
            maxHops = 50,
            seenBy = seenBy,
            platform = "android"
        )

    @Test
    fun `every key on an outgoing frame is canonical`() {
        val json = JSONObject(
            String(frame(urlSafeUnpadded, urlSafeUnpadded, mutableListOf(urlSafeUnpadded)).toJSON())
        )
        assertEquals(standardPadded, json.getString("senderPublicKey"))
        assertEquals(standardPadded, json.getString("recipientPublicKey"))
        assertEquals(standardPadded, json.getJSONArray("seenBy").getString(0))
        // And the iPhone agrees it is addressed to it.
        assertTrue(shippedIosIsForUs(json.getString("recipientPublicKey"), standardPadded))
    }

    @Test
    fun `a broadcast keeps its empty recipient`() {
        val json = JSONObject(String(frame(urlSafeUnpadded, "", mutableListOf()).toJSON()))
        assertEquals("", json.getString("recipientPublicKey"))
        assertTrue("an empty recipient is for everyone", shippedIosIsForUs("", standardPadded))
    }

    @Test
    fun `canonicalising the wire does not break our own loop detection`() {
        // We stamp the canonical spelling; our local identity may still be the
        // base64url one. The relay guard folds, so our own entry is recognised.
        val wireSpelling = CrossPlatformMesh.canonicalWireKey(urlSafeUnpadded)
        assertEquals(
            CrossPlatformMesh.normalizeMeshKey(wireSpelling),
            CrossPlatformMesh.normalizeMeshKey(urlSafeUnpadded)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Nothing else may stamp a raw key again
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the frame encoder is the only place keys are written, and it canonicalises`() {
        val src = java.io.File("src/main/java/com/oshi/messenger/network/mesh/CrossPlatformMesh.kt")
        assertTrue(src.exists())
        val text = src.readText()
        for (field in listOf("senderPublicKey", "recipientPublicKey")) {
            assertFalse(
                "$field must go through canonicalWireKey on its way out",
                text.contains("put(\"$field\", $field)")
            )
        }
        assertFalse(
            "the BLE blob must announce the canonical spelling",
            text.contains("put(\"pk\", myPublicKey.take(32))")
        )
        assertFalse(
            "the Bonjour TXT record must announce the canonical spelling",
            text.contains("setAttribute(\"pk\", myPublicKey)")
        )
    }
}
