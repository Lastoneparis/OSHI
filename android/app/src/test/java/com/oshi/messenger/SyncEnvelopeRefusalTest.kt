package com.oshi.messenger

import com.oshi.messenger.service.LinkedDevice
import com.oshi.messenger.service.MultiDeviceSyncManager
import com.oshi.messenger.service.fanOutSync
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A crypto failure on the multi-device sync path must REFUSE the send, not downgrade it.
 *
 * Both senders in `MultiDeviceSyncManager` used to read
 *   `val content = buildSyncEnvelope(syncData) ?: "🔄SYNC🔄$syncData"`
 * so when `deriveSyncKey()` returned null — no private key exported, a keystore
 * failure — the AES-256-GCM envelope was silently replaced by the raw sync JSON:
 * contacts, message bodies, settings, handed to the transport with the one layer that
 * exists to protect them missing.
 *
 * These tests drive the real fan-out with a key derivation that fails.
 */
class SyncEnvelopeRefusalTest {

    private fun devices() = listOf(
        LinkedDevice("dev-a", "Pixel 8", "pubkeyA", 0L),
        LinkedDevice("dev-b", "Mac", "pubkeyB", 0L)
    )

    /** The payload carries data a leak would expose; the assertions hunt for it. */
    private fun payload() = JSONObject().apply {
        put("type", "messages")
        put("secret", "alice-said-something-private")
    }

    @Test
    fun `key derivation failure sends nothing at all`() = runBlocking {
        val sent = mutableListOf<Pair<String, String>>()

        val result = fanOutSync(
            payload = payload(),
            devices = devices(),
            buildEnvelope = { null },                 // deriveSyncKey() failed
            send = { device, content -> sent.add(device.publicKey to content) }
        )

        assertFalse("a sync that cannot be encrypted must report refusal", result)
        assertTrue("nothing may leave the device when encryption fails", sent.isEmpty())
    }

    @Test
    fun `a working envelope still reaches every linked device`() = runBlocking {
        val sent = mutableListOf<Pair<String, String>>()

        val result = fanOutSync(
            payload = payload(),
            devices = devices(),
            buildEnvelope = { p -> """{"type":"${p.optString("type")}","encryptedData":"BASE64"}""" },
            send = { device, content -> sent.add(device.publicKey to content) }
        )

        assertTrue(result)
        assertEquals(listOf("pubkeyA", "pubkeyB"), sent.map { it.first })
        sent.forEach { (_, content) ->
            assertTrue("the envelope is what goes out", content.contains("encryptedData"))
            assertFalse(
                "the plaintext payload must never appear on the wire",
                content.contains("alice-said-something-private")
            )
            assertFalse("no legacy 🔄SYNC🔄 downgrade", content.contains("🔄SYNC🔄"))
        }
    }

    /**
     * The iOS-shaped envelope has no prefix, and its first key is `type` — so every
     * receive chain's `startsWith("{\"type\":")` media branch claims it. `encryptedData`
     * is the discriminant. (Recognition seam for the MessageRepository handoff.)
     */
    @Test
    fun `the prefix-less sync envelope is told apart from a media payload`() {
        assertTrue(
            MultiDeviceSyncManager.isSyncEnvelope(
                """{"type":"messages","deviceId":"dev-a","encryptedData":"BASE64"}"""
            )
        )
        assertFalse(
            "a media payload starts the same way and must NOT be taken for sync",
            MultiDeviceSyncManager.isSyncEnvelope("""{"type":"image","data":"BASE64"}""")
        )
        assertFalse(MultiDeviceSyncManager.isSyncEnvelope("🔄SYNC🔄{\"type\":\"messages\"}"))
        assertFalse(MultiDeviceSyncManager.isSyncEnvelope("hello world"))
        assertFalse("malformed JSON must not throw", MultiDeviceSyncManager.isSyncEnvelope("{not json"))
    }

    @Test
    fun `refusal is decided before any device is touched, even with one device`() = runBlocking {
        var calls = 0
        val result = fanOutSync(
            payload = payload(),
            devices = listOf(LinkedDevice("dev-a", "Pixel 8", "pubkeyA", 0L)),
            buildEnvelope = { null },
            send = { _, _ -> calls++ }
        )
        assertFalse(result)
        assertEquals(0, calls)
    }
}
