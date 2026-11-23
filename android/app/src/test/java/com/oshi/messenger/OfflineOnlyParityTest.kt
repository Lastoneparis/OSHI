package com.oshi.messenger

import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.data.model.OfflineOnlyPolicy
import com.oshi.messenger.data.model.OfflineOnlyRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __OFFLINE_ONLY_CONVERSATION_2026_10_23__ Keeps the per-conversation
 * offline-only switch aligned with `OSHI/OfflineOnlyConversation.swift`.
 *
 * Opens BOTH sources, for the reason the mesh parity test exists: a constant
 * quoted in a comment does not fail when the other platform changes it. The
 * wire here is small, which is exactly why it would rot unnoticed.
 */
class OfflineOnlyParityTest {

    private fun swift(name: String): String {
        val f = listOf(File("../OSHI/$name"), File("../../OSHI/$name"))
            .firstOrNull { it.exists() }
        assertTrue("OSHI/$name not found from ${File(".").absolutePath}", f != null)
        return f!!.readText()
    }

    private val swiftSource by lazy { swift("OfflineOnlyConversation.swift") }

    // ── the wire ──

    @Test
    fun theSentinelIsTheSameStringOnBothPlatforms() {
        assertEquals("🔒OFFLINE_ONLY🔒", OSHIControlPayload.OFFLINE_ONLY)
        assertTrue(
            "the Swift side must declare the same sentinel",
            swift("OSHIControlPayload.swift").contains("\"🔒OFFLINE_ONLY🔒\"")
        )
    }

    @Test
    fun theJsonKeysAreTheSame() {
        val json = OfflineOnlyRequest.create(on = true, atMillis = 1_761_220_000_000L).toJson()
        for (k in listOf("\"v\"", "\"on\"", "\"at\"")) {
            assertTrue("$k missing from the Android payload: $json", json.contains(k))
        }
        // The Swift CodingKeys must map onto exactly these three.
        for (k in listOf("case version = \"v\"", "case on", "case atUnix = \"at\"")) {
            assertTrue("$k missing from OfflineOnlyConversation.swift", swiftSource.contains(k))
        }
    }

    @Test
    fun theVersionIsTheSame() {
        val ios = Regex("""currentVersion\s*=\s*(\d+)""").find(swiftSource)?.groupValues?.get(1)?.toInt()
        assertEquals("the wire version diverged", ios, OfflineOnlyRequest.CURRENT_VERSION)
    }

    @Test
    fun timestampsAreUnixSecondsNotAppleEpoch() {
        val req = OfflineOnlyRequest.create(on = true, atMillis = 1_761_220_000_000L)
        assertEquals(1_761_220_000.0, req.atUnixSeconds, 0.001)
        // Apple's own Date encoding is seconds since 2001; the Swift side has to
        // go through timeIntervalSince1970 explicitly or the two disagree by
        // 978_307_200 seconds.
        assertTrue(
            "the Swift side must use timeIntervalSince1970 explicitly",
            swiftSource.contains("timeIntervalSince1970")
        )
    }

    @Test
    fun aRoundTripSurvives() {
        val sent = OfflineOnlyRequest.create(on = false, atMillis = 1_700_000_000_000L)
        val back = OfflineOnlyRequest.fromMessageString(sent.toMessageString())
        assertEquals(sent.on, back?.on)
        assertEquals(sent.atUnixSeconds, back?.atUnixSeconds!!, 0.001)
        assertEquals(sent.version, back.version)
    }

    @Test
    fun aFutureVersionIsRefusedRatherThanGuessedAt() {
        val wire = OSHIControlPayload.OFFLINE_ONLY + """{"v":99,"on":true,"at":1.0}"""
        assertNull("a newer wire version must not be half-understood",
                   OfflineOnlyRequest.fromMessageString(wire))
    }

    /**
     * The restrictive default, on both sides. A corrupt payload that reads as
     * "the internet is allowed again" would quietly undo a restriction the user
     * asked for; one that reads as "still restricted" costs at worst a message
     * that waits.
     */
    @Test
    fun anUnreadableFlagReadsAsStillRestricted() {
        val wire = OSHIControlPayload.OFFLINE_ONLY + """{"v":1,"at":1.0}"""
        assertEquals(true, OfflineOnlyRequest.fromMessageString(wire)?.on)
        assertTrue(
            "the Swift summary must default to restricted too",
            swift("OSHIControlPayload.swift").contains("var on = true")
        )
    }

    // ── the decision ──

    @Test
    fun theTwoSwitchesCompose() {
        // A per-conversation switch may RESTRICT, never widen.
        assertTrue(OfflineOnlyPolicy.internetAllowed(globalInternetAllowed = true,
                                                     conversationIsOfflineOnly = false))
        assertFalse(OfflineOnlyPolicy.internetAllowed(globalInternetAllowed = true,
                                                      conversationIsOfflineOnly = true))
        assertFalse(OfflineOnlyPolicy.internetAllowed(globalInternetAllowed = false,
                                                      conversationIsOfflineOnly = false))
        assertFalse(OfflineOnlyPolicy.internetAllowed(globalInternetAllowed = false,
                                                      conversationIsOfflineOnly = true))
        assertTrue(
            "the Swift rule must be the same conjunction",
            swiftSource.contains("globalInternetAllowed && !conversationIsOfflineOnly")
        )
    }

    @Test
    fun theConfirmationCountCannotExceedTheMembers() {
        assertEquals(Pair(2, 4), OfflineOnlyPolicy.confirmationSummary(2, 4))
        assertEquals(Pair(4, 4), OfflineOnlyPolicy.confirmationSummary(9, 4))
        assertEquals(Pair(0, 0), OfflineOnlyPolicy.confirmationSummary(3, -1))
    }

    // ── the acknowledgement ──

    /**
     * An echo that looks like a request is an infinite loop: it carries a fresh
     * timestamp, so the other side reads it as a NEW request, clears its
     * confirmations and asks everybody again. The `ack` flag is what stops that,
     * so both platforms have to agree it exists.
     */
    @Test
    fun anAcknowledgementIsMarkedAsOneOnBothPlatforms() {
        val ack = OfflineOnlyRequest.create(on = true, atMillis = 1_761_220_000_000L).acknowledgement()
        assertTrue("the ack flag must be on the wire", ack.toJson().contains("\"ack\""))
        assertTrue(ack.isAck)
        assertTrue(
            "OfflineOnlyConversation.swift must carry the same flag",
            swiftSource.contains("case isAck = \"ack\"")
        )
        assertTrue(
            "and the Swift side must be able to build one",
            swiftSource.contains("func acknowledgement()")
        )
    }

    /** An ack answers one specific change, so it keeps that change's stamp. */
    @Test
    fun anAcknowledgementKeepsTheRequestItAnswers() {
        val req = OfflineOnlyRequest.create(on = true, atMillis = 1_761_220_000_000L)
        val ack = req.acknowledgement()
        assertEquals(req.on, ack.on)
        assertEquals(req.atUnixSeconds, ack.atUnixSeconds, 0.001)
        assertFalse("a request is not an ack", req.isAck)
    }

    @Test
    fun anAcknowledgementSurvivesTheRoundTrip() {
        val ack = OfflineOnlyRequest.create(on = false, atMillis = 1_700_000_000_000L).acknowledgement()
        val back = OfflineOnlyRequest.fromMessageString(ack.toMessageString())
        assertEquals(true, back?.isAck)
        assertEquals(false, back?.on)
    }

    /**
     * Payloads written before acks existed have no flag at all. Absent must read
     * as "this is a request": at worst one confirmation goes uncounted, whereas
     * reading an old request as an ack would leave a conversation restricted
     * with nobody ever asked.
     */
    @Test
    fun anOlderPayloadWithoutTheFlagIsARequest() {
        val wire = OSHIControlPayload.OFFLINE_ONLY + """{"v":1,"on":true,"at":1.0}"""
        assertEquals(false, OfflineOnlyRequest.fromMessageString(wire)?.isAck)
        assertTrue(
            "the Swift decoder must default the same way",
            swiftSource.contains("isAck = (try? c.decodeIfPresent(Bool.self, forKey: .isAck)) as? Bool ?? false")
        )
    }

    /**
     * The matching rule, pinned on both sides: an ack counts only for the change
     * it actually answers. Matching on `on` alone would let an ack from before a
     * quick off-then-on be counted for the new request.
     */
    @Test
    fun bothPlatformsMatchAnAckToOneSpecificChange() {
        for (needle in listOf("answersCurrentRequest", "< 1.0")) {
            assertTrue("$needle missing from OfflineOnlyConversation.swift",
                       swiftSource.contains(needle))
        }
    }

}
