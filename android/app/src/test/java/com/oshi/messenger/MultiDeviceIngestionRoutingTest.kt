package com.oshi.messenger

import com.oshi.messenger.service.MultiDeviceSyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `handleSyncData` / `handleLinkRequest` were public with ZERO call sites in the app:
 * every `🔄SYNC🔄` envelope and every `link_request` / `link_accept` / `link_reject` /
 * `unlink` fell past all four `MessageRepository` prefix chains into the plain-text tail.
 * Multi-device sync and device linking were receive-dead on Android — the sender half was
 * fixed in round 2 and still did nothing.
 *
 * Two halves here, because either alone passes against the bug:
 *  1. the recognition seam ([MultiDeviceSyncManager.isSyncPayload] / [isLinkPayload])
 *     tells a sync envelope from a media payload, which is the whole ordering trap;
 *  2. all FOUR ingestion chains actually call the handlers, and call them ABOVE their
 *     media branch — the envelope is bare JSON whose first key is `type`, so a branch
 *     placed after `startsWith("{\"type\":")` routes sync into `handleMediaContent`.
 *
 * iOS has no such lane (its sync rides the dedicated `/api/sync` REST routes,
 * `MultiDeviceSyncManager.swift:220-769`), so there is no wire format to preserve here.
 */
class MultiDeviceIngestionRoutingTest {

    // =====================================================================
    // 1. Recognition — a sync envelope vs a media payload
    // =====================================================================

    /** Hand-written, NOT produced by buildSyncEnvelope: the wire shape is the assertion. */
    private val envelope =
        """{"type":"messages","deviceId":"dev-a","encryptedData":"Zm9vYmFy"}"""
    private val legacyPrefixed = "🔄SYNC🔄{\"type\":\"contacts\",\"contacts\":[]}"
    private val linkRequest = "🔗LINK🔗{\"action\":\"link_request\",\"code\":\"482913\"}"

    @Test
    fun `both sync spellings are recognised and nothing else is`() {
        assertTrue("iOS-shaped envelope", MultiDeviceSyncManager.isSyncPayload(envelope))
        assertTrue("legacy prefix still readable", MultiDeviceSyncManager.isSyncPayload(legacyPrefixed))

        assertFalse(
            "an image payload starts the same way and must reach handleMediaContent",
            MultiDeviceSyncManager.isSyncPayload("""{"type":"image","data":"Zm9vYmFy"}""")
        )
        assertFalse(
            "the iOS VPS media wrapper too",
            MultiDeviceSyncManager.isSyncPayload(
                """{"type":"media","mediaType":"image","content":"Zm9vYmFy","fileName":"a.jpg"}"""
            )
        )
        assertFalse(MultiDeviceSyncManager.isSyncPayload(linkRequest))
        assertFalse(MultiDeviceSyncManager.isSyncPayload("hey, are you around?"))
        assertFalse("malformed JSON must not throw", MultiDeviceSyncManager.isSyncPayload("{\"type\":"))
    }

    @Test
    fun `the link handshake is not confused with profile links visibility`() {
        assertTrue(MultiDeviceSyncManager.isLinkPayload(linkRequest))
        assertTrue(
            MultiDeviceSyncManager.isLinkPayload("🔗LINK🔗{\"action\":\"unlink\",\"deviceId\":\"dev-b\"}")
        )
        // Both start with 🔗 and both travel the same chain; they diverge on the
        // character after LINK. A prefix that matched here would swallow the other feature.
        assertFalse(
            MultiDeviceSyncManager.isLinkPayload("🔗LINKS_VISIBILITY🔗{\"visible\":true}")
        )
        assertFalse(MultiDeviceSyncManager.isLinkPayload(envelope))
        assertFalse(MultiDeviceSyncManager.isLinkPayload("check out this link"))
    }

    // =====================================================================
    // 2. Wiring — the four chains
    // =====================================================================

    private val repository: String by lazy {
        val f = File("src/main/java/com/oshi/messenger/data/repository/MessageRepository.kt")
        assertTrue("not found: ${f.absolutePath}", f.exists())
        f.readText()
    }

    /** The text of one member function, up to the next member declaration. */
    private fun bodyOf(declaration: String): String {
        val start = repository.indexOf(declaration)
        assertTrue("declaration not found: $declaration", start >= 0)
        val rest = repository.substring(start + declaration.length)
        val end = Regex("""\n    (?:private |internal |public )?(?:suspend )?fun """)
            .find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private fun assertRoutesSyncAboveMedia(declaration: String) {
        val body = bodyOf(declaration)
        val link = body.indexOf("multiDeviceSyncManager.handleLinkRequest")
        val sync = body.indexOf("multiDeviceSyncManager.handleSyncData")
        val media = body.indexOf("handleMediaContent(")

        assertTrue("$declaration never reaches handleLinkRequest", link >= 0)
        assertTrue("$declaration never reaches handleSyncData", sync >= 0)
        assertTrue("$declaration has no media branch to order against", media >= 0)
        assertTrue(
            "$declaration routes sync BELOW the media branch — the envelope is bare " +
                "JSON starting {\"type\": and handleMediaContent claims it first",
            sync < media
        )
        assertTrue("$declaration routes the link handshake below the media branch", link < media)
        assertTrue(
            "$declaration must recognise sync through MultiDeviceSyncManager, not a " +
                "private prefix list of its own",
            body.contains("MultiDeviceSyncManager.isSyncPayload")
        )
        assertTrue(
            "$declaration must recognise the link handshake through MultiDeviceSyncManager",
            body.contains("MultiDeviceSyncManager.isLinkPayload")
        )
    }

    @Test
    fun `every ingestion chain routes sync and link above its media branch`() {
        val chains = listOf(
            "private suspend fun handleVPSMessage(vpsMessage: VPSMessage) {",
            "private suspend fun handleMeshMessage(meshMessage: MeshMessage) {",
            "private suspend fun dispatchV2Inbound(",
            "private suspend fun routeDecryptedMeshMessage(decrypted: String, cpMessage: CrossPlatformMessage) {"
        )
        chains.forEach { assertRoutesSyncAboveMedia(it) }

        // Four chains, two handlers each: a chain silently dropped from the list above
        // would leave its lane receive-dead exactly as before.
        assertEquals(
            "handleSyncData must be reached from all four chains",
            4,
            Regex("multiDeviceSyncManager\\.handleSyncData").findAll(repository).count()
        )
        assertEquals(
            "handleLinkRequest must be reached from all four chains",
            4,
            Regex("multiDeviceSyncManager\\.handleLinkRequest").findAll(repository).count()
        )
    }
}
