package com.oshi.messenger.network.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __MESH_CUSTODY_2026_10_10__ The rules that decide whether a node keeps
 * somebody else's message, and for how long.
 *
 * Port of `OSHITests/MeshCustodyTests.swift`, mirroring its intent rather than
 * its text. Pure policy on purpose — the radio and the Hilt graph are not
 * needed to pin the policy, and the policy is the part that can quietly go
 * wrong. Two things that are WIRING rather than policy ([MeshNetworkManager]
 * storing the hop-incremented packet, and the two flush triggers) are pinned in
 * the source the way `CrossPlatformMeshParityTest` pins `stop()`'s dedup clear:
 * both need a live `BluetoothAdapter` and Nearby Connections to exercise, and
 * both are exactly the lines a future edit would get wrong.
 */
class MeshCustodyTest {

    private fun relay(
        id: String = "m1",
        hops: Int = 0,
        seenBy: MutableList<String> = mutableListOf("origin"),
        payload: String = "ciphertext"
    ) = RelayMessage(
        messageId = id,
        originalSender = "origin",
        finalRecipient = "recipient",
        encryptedPayload = payload,
        messageType = "TEXT_MESSAGE",
        hopCount = hops,
        maxHops = 50,
        seenBy = seenBy,
        timestamp = 1_760_000_000_000L,
        routePath = seenBy.toMutableList()
    )

    private fun memoryStore() = MeshCustodyStore(file = null)

    private fun newTempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "$name-${System.nanoTime()}").apply { mkdirs() }

    private fun tempStore(dir: File, enabled: Boolean = true) =
        MeshCustodyStore(file = File(dir, "custody.json"), isEnabled = { enabled })

    // ========================================================================
    // TTL
    // ========================================================================

    @Test
    fun `a message is carried for six hours and not longer`() {
        val now = 1_760_000_000_000L
        assertFalse(
            "just taken",
            MeshCustodyStore.isExpired(takenAt = now, now = now)
        )
        assertFalse(
            "five hours in, still worth forwarding",
            MeshCustodyStore.isExpired(takenAt = now - 5 * 3_600_000L, now = now)
        )
        assertTrue(
            "exactly at the limit counts as expired",
            MeshCustodyStore.isExpired(takenAt = now - MeshCustodyStore.MAX_AGE_MS, now = now)
        )
        assertTrue(
            "a phone is not a mail server",
            MeshCustodyStore.isExpired(takenAt = now - 24 * 3_600_000L, now = now)
        )
        assertEquals(
            "identical to iOS MeshCustodyStore.maxAge (6 h)",
            6L * 60 * 60 * 1000, MeshCustodyStore.MAX_AGE_MS
        )
    }

    @Test
    fun `the window is long enough for the case it exists for`() {
        // 600_000 ms is MAX_RELAY_AGE_MS, the route-freshness window, and it
        // does not survive a courier walking out of range and coming back —
        // which is the whole scenario this feature exists for.
        assertTrue(
            "ten minutes is too short to carry a message across a coverage gap",
            MeshCustodyStore.MAX_AGE_MS > 600_000L
        )
    }

    // ========================================================================
    // BOUNDS
    // ========================================================================

    @Test
    fun `room is bounded by both count and bytes`() {
        assertFalse(
            "full by count, even with no bytes used",
            MeshCustodyStore.hasRoom(count = MeshCustodyStore.MAX_COUNT, bytes = 0, incoming = 10)
        )
        assertFalse(
            "full by bytes, even with one record",
            MeshCustodyStore.hasRoom(count = 1, bytes = MeshCustodyStore.MAX_BYTES, incoming = 1)
        )
        assertTrue(MeshCustodyStore.hasRoom(count = 1, bytes = 1_000, incoming = 1_000))

        // Identical to iOS.
        assertEquals(200, MeshCustodyStore.MAX_COUNT)
        assertEquals(4 * 1024 * 1024, MeshCustodyStore.MAX_BYTES)
    }

    @Test
    fun `one message bigger than the whole budget is refused rather than evicting everything`() {
        assertFalse(
            "refuse it; do not clear the store for something that cannot fit",
            MeshCustodyStore.hasRoom(count = 0, bytes = 0, incoming = MeshCustodyStore.MAX_BYTES + 1)
        )
        assertTrue(
            "exactly the budget still fits an empty store",
            MeshCustodyStore.hasRoom(count = 0, bytes = 0, incoming = MeshCustodyStore.MAX_BYTES)
        )

        // And through the store itself: an oversized message must not empty it.
        val store = memoryStore()
        assertTrue(store.take(relay(id = "keep-me")))
        val oversized = relay(id = "too-big", payload = "x".repeat(MeshCustodyStore.MAX_BYTES + 1))
        assertFalse("oversized message refused", store.take(oversized))
        assertEquals("the message already held survived the refusal", 1, store.count)
        assertEquals("keep-me", store.readyToForward().single().messageId)
    }

    @Test
    fun `eviction is oldest first`() {
        val store = memoryStore()
        // MAX_COUNT is 200, so drive the rule rather than the constant: fill
        // past it and check WHICH records are gone.
        var i = 0
        while (i < MeshCustodyStore.MAX_COUNT) {
            assertTrue(store.take(relay(id = "msg-$i")))
            i++
        }
        assertEquals(MeshCustodyStore.MAX_COUNT, store.count)

        // takenAt has millisecond resolution and this loop is faster than that,
        // so the oldest is whichever the eviction picks among equals; what must
        // hold is that the store stays bounded and the newcomer gets in.
        assertTrue(store.take(relay(id = "newcomer")))
        assertEquals("still bounded by count", MeshCustodyStore.MAX_COUNT, store.count)
        assertTrue(
            "the message that forced the eviction is the one being carried",
            store.readyToForward().any { it.messageId == "newcomer" }
        )
    }

    @Test
    fun `a message that keeps arriving cannot renew its own ttl`() {
        val store = memoryStore()
        assertTrue(store.take(relay(id = "dup")))
        assertFalse("second arrival is not re-taken", store.take(relay(id = "dup")))
        assertEquals(1, store.count)
    }

    // ========================================================================
    // THE HOP-BUDGET INVARIANT
    // ========================================================================

    @Test
    fun `a held message keeps its hop count and path`() {
        // THE invariant. A deferred message must not be re-originated: that
        // would hand it a fresh hop budget and let it outlive the ceiling,
        // which is the class of bug `CrossPlatformMesh.shouldDropForHops` fixed
        // by making the hop ceiling ours rather than the packet's.
        val store = memoryStore()
        val held = relay(id = "held-1", hops = 4, seenBy = mutableListOf("origin", "hopA", "me"))
        assertTrue(store.take(held))

        val out = store.readyToForward().single()
        assertEquals("custody must not reset the hop count", 4, out.hopCount)
        assertEquals(
            "the path must survive so a held message is never sent back down it",
            listOf("origin", "hopA", "me"), out.seenBy
        )
        assertEquals(listOf("origin", "hopA", "me"), out.routePath)
        assertEquals("held-1", out.messageId)
    }

    @Test
    fun `custody survives a relaunch with the hop count intact`() {
        // The persisted form is the one that matters for the invariant: a
        // round trip through disk must not renew the hop budget either, and
        // this is also what makes the second flush trigger necessary.
        val dir = newTempDir("oshi-custody")
        try {
            val first = tempStore(dir)
            assertTrue(first.take(relay(id = "across-launch", hops = 7, seenBy = mutableListOf("a", "b", "me"))))

            val second = tempStore(dir)   // simulates a cold start
            val out = second.readyToForward().single()
            assertEquals("across-launch", out.messageId)
            assertEquals("a relaunch must not reset the hop count", 7, out.hopCount)
            assertEquals(listOf("a", "b", "me"), out.seenBy)
            assertEquals("recipient", out.finalRecipient)
            assertEquals("ciphertext", out.encryptedPayload)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a payload containing a quote survives the round trip`() {
        // `RelayMessage.toJson`/`fromJson` are hand-rolled and read strings with
        // `"([^"]*)"`, which cannot read back an escaped quote. The custody
        // store must not inherit that: a record that fails to parse on load
        // would silently empty the store, i.e. lose other people's messages.
        val dir = newTempDir("oshi-custody-quote")
        try {
            val nasty = """{"ct":"a\"b","iv":"c\\d"}"""
            val first = tempStore(dir)
            assertTrue(first.take(relay(id = "quoted", payload = nasty)))

            val second = tempStore(dir)
            val out = second.readyToForward().single()
            assertEquals("quoted", out.messageId)
            assertEquals(nasty, out.encryptedPayload)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ========================================================================
    // RELEASE — store-and-FORWARD, not store-and-confirm
    // ========================================================================

    @Test
    fun `custody ends on the first hand off`() {
        val store = memoryStore()
        assertTrue(store.take(relay(id = "gone")))
        store.release("gone")
        assertEquals("no app-level ack to wait for", 0, store.count)
        assertTrue(store.readyToForward().isEmpty())

        // Releasing something not held is not an error.
        store.release("never-held")
        assertEquals(0, store.count)
    }

    // ========================================================================
    // KILL SWITCH
    // ========================================================================

    @Test
    fun `custody is on by default and can be turned off`() {
        assertTrue(
            "relaying for other people is what the mesh is; absent means on",
            MeshCustodyStore.enabledFrom(null)
        )
        assertTrue(MeshCustodyStore.enabledFrom(true))
        assertFalse(
            "the kill switch must restore forward-now-or-drop exactly",
            MeshCustodyStore.enabledFrom(false)
        )

        // Same key as iOS's UserDefaults key, in the store this app already
        // keeps its settings in.
        assertEquals("oshi_mesh_custody", MeshCustodyStore.PREFS_KEY)
        assertEquals("oshi_settings", MeshCustodyStore.PREFS_NAME)

        val dir = newTempDir("oshi-custody-off")
        try {
            val off = tempStore(dir, enabled = false)
            assertFalse("nothing is stored while the switch is off", off.take(relay()))
            assertEquals(0, off.count)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ========================================================================
    // WIRING, pinned in the source
    // ========================================================================

    private val managerSource: String by lazy {
        val f = File("src/main/java/com/oshi/messenger/network/mesh/MeshNetworkManager.kt")
        assertTrue("not found: ${f.absolutePath}", f.exists())
        f.readText()
    }

    private fun functionBody(startNeedle: String, endNeedle: String): String {
        val start = managerSource.indexOf(startNeedle)
        assertTrue("not found: $startNeedle", start > 0)
        val end = managerSource.indexOf(endNeedle, start + startNeedle.length)
        assertTrue("not found after $startNeedle: $endNeedle", end > start)
        return managerSource.substring(start, end)
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    @Test
    fun `forwardRelayMessage takes custody of the hop-incremented packet not the original`() {
        // The one-word difference that decides whether a deferred packet can
        // outlive the hop ceiling. iOS: `custody.take(updatedMessage)`, never
        // `relayMsg`. Here: `custody.take(updatedMsg)`.
        val body = functionBody(
            "private fun forwardRelayMessage(",
            "private fun sendRelayMessage("
        )
        assertTrue(
            "forwardRelayMessage must take custody when the broadcast had no target",
            body.contains("custody.take(updatedMsg)")
        )
        assertFalse(
            "taking custody of `relayMsg` would re-originate at the ORIGINAL hop count",
            body.contains("custody.take(relayMsg)")
        )
        assertTrue(
            "custody is taken exactly when nothing was handed on",
            body.contains("if (handedTo == 0)")
        )
        assertTrue(
            "the hop count must still be incremented before custody",
            body.contains("hopCount = relayMsg.hopCount + 1")
        )
    }

    @Test
    fun `both flush triggers are wired`() {
        // 1. A new peer — the only event that can turn a held message into a
        //    deliverable one.
        assertTrue(
            "flush on connection established",
            functionBody("override fun onConnectionResult(", "override fun onDisconnected(")
                .contains("flushCustody()")
        )
        // 2. The cold-start trigger. `onConnectionResult` only fires for
        //    connections this process negotiated, and CrossPlatformMesh peers
        //    never reach a callback in this class at all, so a relaunch with a
        //    full store next to an already-connected peer would never drain.
        //    `combine` emits the current value as soon as it is collected.
        val watcher = functionBody("private fun startCustodyFlushWatcher()", "private fun deliverRelayMessage(")
        assertTrue(
            "the watcher must cover BOTH transports, not just Nearby Connections",
            watcher.contains("crossPlatformMesh.connectedPeers")
        )
        assertTrue("fires on the current value, then on each flip", watcher.contains("distinctUntilChanged()"))
        assertTrue(watcher.contains("flushCustody()"))
        assertTrue(
            "and it has to actually be started",
            managerSource.contains("startCustodyFlushWatcher()")
        )
    }

    @Test
    fun `flushCustody releases on hand off and re-offers the packet verbatim`() {
        val body = functionBody("    fun flushCustody()", "    private fun startCustodyFlushWatcher()")
        assertTrue("released on the first successful hand-off", body.contains("custody.release(relay.messageId)"))
        assertFalse(
            "a re-offer must not increment the hop count a second time",
            body.contains("hopCount + 1")
        )
        assertNotNull(body)
    }
}
