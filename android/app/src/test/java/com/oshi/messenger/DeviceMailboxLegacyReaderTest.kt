package com.oshi.messenger

import com.oshi.messenger.network.v2.devicemailbox.FanoutPlanner
import com.oshi.messenger.network.v2.devicemailbox.PeerReadView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * __LEGACY_READER_2026_09_28__ "Incoming messages arrive; what I send never reaches the recipient."
 *
 * Production (1.6.26 → 1.6.32, `/v2/config device_mailbox_enabled:true`): an Android install that
 * registered in device mode (every first device does, automatically) sent 1:1 and group messages as
 * PER-DEVICE copies to whatever `GET /v2/keys/<peer>/devices` listed. The relay shows an install that
 * is NOT registered only account envelopes (`relay_v2.js visibleTo`: no deviceId ⇒ `isAccountItem`).
 * So when the peer's live install reads the legacy view while its account still lists a bundled
 * device — a reinstalled phone (the old deviceId stays for 30 days and the new install needs an
 * approval from it), or an iPhone on a build without device mode next to a registered Mac — every
 * copy went to a device nobody reads. The relay accepted it (sender shows ✓), the peer never saw it,
 * and the peer's own messages (account envelopes) kept arriving.
 *
 * Two halves, because either alone passes against the bug:
 *  1. the wire evidence: an envelope with neither `toDevice` nor `fromDevice` comes from an install
 *     on the account path (device-mode installs always write with `fromDevice`);
 *  2. the router actually RECORDS that evidence on a decrypted envelope and CONSULTS it before fanning
 *     out, falling back to the account envelope (visible to every view of the peer's account).
 * Part 2 is asserted on the source, like `V2EnvelopeTimestampWiringTest`: `poll`/`sendFannedOut`
 * need the Hilt graph and `android.util.Base64`. Red on a263a82c (1.6.32, versionCode 73).
 */
class DeviceMailboxLegacyReaderTest {

    // ------------------------------------------------------------------ 1. evidence

    @Test
    fun `an envelope with neither device id comes from a legacy-view install`() {
        assertTrue(PeerReadView.isLegacyWriter(toDevice = null, fromDevice = null))
    }

    @Test
    fun `a device copy or a device-mode sender is not legacy`() {
        val d = "0123456789abcdef0123456789abcdef"
        assertFalse(PeerReadView.isLegacyWriter(toDevice = d, fromDevice = d))
        assertFalse(PeerReadView.isLegacyWriter(toDevice = d, fromDevice = null))   // §3.4 row 2
        assertFalse(PeerReadView.isLegacyWriter(toDevice = null, fromDevice = d))
    }

    /**
     * The hole itself, in the planner's terms: a peer that lists a device gets NO account envelope,
     * so a legacy-view install of that peer — which sees account envelopes only — gets nothing.
     * The router's fallback is exactly "plan as if the peer had no device".
     */
    @Test
    fun `a peer listing a device gets per-device copies only, the account fallback gives the account envelope`() {
        val me = "me"; val peer = "peer"; val myDev = "aa".repeat(16); val staleDev = "bb".repeat(16)
        val deviceMode = FanoutPlanner.plan(peer, me, myDev, peerDevices = listOf(staleDev), ownDevices = listOf(myDev))
        assertEquals(listOf(staleDev), deviceMode.peerDevices)    // non-empty ⇒ sendFannedOut builds no "account" envelope
        val fallback = FanoutPlanner.plan(peer, me, myDev, peerDevices = emptyList(), ownDevices = listOf(myDev))
        assertTrue(fallback.peerDevices.isEmpty())                // empty ⇒ one account envelope, seen by every view
    }

    // ------------------------------------------------------------------ 2. wiring

    private fun moduleFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            val nested = File(dir, "app/$relative")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        fail("could not locate $relative from ${File(".").absolutePath}")
        error("unreachable")
    }

    private val router by lazy {
        moduleFile("src/main/java/com/oshi/messenger/network/v2/V2MessageRouter.kt").readText()
    }

    private fun body(startMarker: String): String {
        val i = router.indexOf(startMarker)
        assertTrue("`$startMarker` not found in V2MessageRouter.kt", i >= 0)
        val next = router.indexOf("\n    suspend fun ", i + startMarker.length)
            .let { if (it < 0) router.length else it }
        val nextPrivate = router.indexOf("\n    private suspend fun ", i + startMarker.length)
            .let { if (it < 0) router.length else it }
        return router.substring(i, minOf(next, nextPrivate))
    }

    @Test
    fun `sendFannedOut starts on the account envelope for a peer that reads the legacy view`() {
        val fanout = body("private suspend fun sendFannedOut(")
        assertTrue(
            "sendFannedOut must initialise accountFallback from peerReadsLegacy(peer) — otherwise a peer " +
                "whose live install reads the account view only ever gets per-device copies it cannot see",
            Regex("""var accountFallback\s*=\s*!isSelfWrite\s*&&\s*peerReadsLegacy\(peer\)""").containsMatchIn(fanout)
        )
    }

    @Test
    fun `poll records the peer's write mode after a successful decrypt, in device mode only`() {
        val poll = body("suspend fun poll()")
        val noOp = poll.indexOf("if (pt == null) continue")
        val note = poll.indexOf("notePeerWriteMode(env.from, env.toDevice, env.fromDevice")
        val deliver = poll.indexOf("onMessageDelivered?.invoke(")
        assertTrue("poll must record the peer's write mode", note >= 0)
        assertTrue("only an envelope that DECRYPTED may count (after the pt == null check)", noOp in 0 until note)
        assertTrue("recorded before delivery", deliver < 0 || note < deliver)
        assertTrue(
            "only while this install is in device mode (then device-mode peers always send fromDevice)",
            Regex("""if \(deviceKeys != null\) notePeerWriteMode\(""").containsMatchIn(poll)
        )
    }

    @Test
    fun `the recorded mode is the PeerReadView rule and is persisted`() {
        val note = body("private fun notePeerWriteMode(")
        assertTrue(note.contains("PeerReadView.isLegacyWriter(toDevice, fromDevice)"))
        assertTrue("persisted, so the first send after a restart still takes the account path",
            note.contains("LEGACY_READER_KEY"))
    }
}
