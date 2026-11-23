package com.oshi.messenger

import com.oshi.messenger.network.v2.RatchetHealSignal
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire compatibility with iOS `RatchetHealSignal.swift`, pinned by literal values:
 * the two markers, the two id prefixes, the bare-marker equality rule and the
 * mandatory newline. If any of these drift, an iPhone's cure lands in an Android
 * chat as a bubble again — the exact defect this port removes.
 */
class RatchetHealSignalTest {

    @Test
    fun markersAndPrefixesMatchIOSByteForByte() {
        assertEquals("__RATCHET_HEAL__v1", RatchetHealSignal.PAYLOAD_MARKER)
        assertEquals("__RATCHET_RESENT__v1", RatchetHealSignal.Resent.MARKER)
        assertEquals("heal-", RatchetHealSignal.MSG_ID_PREFIX)
        assertEquals("heal-v2-", RatchetHealSignal.CAPABLE_MSG_ID_PREFIX)
    }

    @Test
    fun emptyListEncodesToTheExactV1Payload() {
        assertEquals(RatchetHealSignal.PAYLOAD_MARKER, RatchetHealSignal.encode())
        assertEquals(RatchetHealSignal.PAYLOAD_MARKER, RatchetHealSignal.encode(emptyList()))
    }

    @Test
    fun bareV1FormIsRecognisedAndTrimmed() {
        assertEquals(RatchetHealSignal.Heal(emptyList()), RatchetHealSignal.parse("__RATCHET_HEAL__v1"))
        assertEquals(RatchetHealSignal.Heal(emptyList()), RatchetHealSignal.parse("  __RATCHET_HEAL__v1\n"))
    }

    @Test
    fun resendListSurvivesTheRoundTrip() {
        val ids = listOf("A1", "B2", "C3")
        val wire = RatchetHealSignal.encode(ids)
        assertTrue(wire.startsWith("__RATCHET_HEAL__v1\n{"))
        assertEquals(RatchetHealSignal.Heal(ids), RatchetHealSignal.parse(wire))
    }

    @Test
    fun theIOSListFormWithPaddingParses() {
        // iOS pads the JSON body to a fixed size with a "p" field — must be transparent.
        val ios = "__RATCHET_HEAL__v1\n" + JSONObject().put("v", 2)
            .put("resend", org.json.JSONArray(listOf("X"))).put("p", "0".repeat(1400)).toString()
        assertEquals(RatchetHealSignal.Heal(listOf("X")), RatchetHealSignal.parse(ios))
    }

    @Test
    fun markerFollowedByProseIsNotAHealPayload() {
        assertNull(RatchetHealSignal.parse("__RATCHET_HEAL__v1 is what I typed"))
        assertNull(RatchetHealSignal.parse("__RATCHET_HEAL__v1\nnot json"))
        assertNull(RatchetHealSignal.parse("hello"))
        assertFalse(RatchetHealSignal.isHealPayload("__RATCHET_HEAL__v2"))
    }

    @Test
    fun futureVersionWithoutResendStillReadsAsAHeal() {
        assertEquals(RatchetHealSignal.Heal(emptyList()),
            RatchetHealSignal.parse("__RATCHET_HEAL__v1\n{\"v\":9,\"future\":true}"))
    }

    @Test
    fun resendListIsCappedOnRead() {
        val many = (1..100).map { "id$it" }
        assertEquals(RatchetHealSignal.MAX_RESEND_PER_REQUEST, RatchetHealSignal.parse(RatchetHealSignal.encode(many))!!.resend.size)
    }

    @Test
    fun msgIdsCarryThePrefixAndStayUnique() {
        val a = RatchetHealSignal.makeMsgId(); val b = RatchetHealSignal.makeMsgId()
        assertTrue(RatchetHealSignal.isHealMsgId(a))
        assertNotEquals(a, b)
        // __RESEND_LIST_ANDROID_2026_10_12__ Android ANNOUNCES now. This is load-bearing:
        // iOS withholds the resend list from a peer that does not announce
        // (MessageManager+V2.swift:2364), so while this was false an Android phone was
        // never told which of its messages it could ask for back.
        assertTrue(RatchetHealSignal.announcesListSupport(a))
        assertTrue(RatchetHealSignal.isHealMsgId(a))                 // still a heal to a v1 reader
        assertTrue(RatchetHealSignal.announcesListSupport("heal-v2-ABC"))
        assertFalse(RatchetHealSignal.announcesListSupport("heal-ABC"))   // the OLD spelling
        assertFalse(RatchetHealSignal.isHealMsgId("ABC-heal-"))
    }

    @Test
    fun everyEnrichedPayloadHasTheSameLengthWhateverTheCount() {
        // __HEAL_LENGTH_LEAK__ The envelope is identifiable in cleartext by its `heal-` id and
        // AES-GCM adds no padding, so an unpadded body would tell the relay HOW MANY messages
        // a correspondent could not read — the size of the outage. Replay the old behaviour to
        // prove the padding is what removes the signal.
        val one = RatchetHealSignal.encode(listOf("A".repeat(36)))
        val many = RatchetHealSignal.encode((1..RatchetHealSignal.MAX_RESEND_PER_REQUEST).map { "id-$it" })
        assertEquals(one.toByteArray(Charsets.UTF_8).size, many.toByteArray(Charsets.UTF_8).size)
        assertTrue(one.toByteArray(Charsets.UTF_8).size >= RatchetHealSignal.PAD_TARGET_BYTES - 16)
        // The old, unpadded form leaked the count — kept here so a regression is visible.
        val unpaddedOne = "__RATCHET_HEAL__v1\n" + JSONObject().put("v", 2)
            .put("resend", org.json.JSONArray(listOf("A".repeat(36)))).toString()
        val unpaddedMany = "__RATCHET_HEAL__v1\n" + JSONObject().put("v", 2)
            .put("resend", org.json.JSONArray((1..32).map { "id-$it" })).toString()
        assertNotEquals(unpaddedOne.length, unpaddedMany.length)
    }

    @Test
    fun paddingIsTransparentToTheReader() {
        val ids = listOf("one", "two")
        assertEquals(RatchetHealSignal.Heal(ids), RatchetHealSignal.parse(RatchetHealSignal.encode(ids)))
        // …and the bare form is NEVER padded: a v1 reader recognises it by EQUALITY.
        assertEquals(RatchetHealSignal.PAYLOAD_MARKER, RatchetHealSignal.encode(emptyList()))
    }

    @Test
    fun resendPolicyBrakes() {
        val p = RatchetHealSignal.Policy
        assertEquals(2, p.MAX_RESEND_ATTEMPTS_PER_MSG_ID)
        assertEquals(64, p.MAX_RESENDS_PER_PEER_PER_HOUR)
        assertEquals(7L * 24 * 3600 * 1000, p.MAX_RESEND_AGE_MS)
        assertTrue(p.mayResend(attemptsSoFar = 0, messageAgeMs = 1_000L, peerBudgetLeft = 1))
        assertFalse(p.mayResend(attemptsSoFar = 2, messageAgeMs = 1_000L, peerBudgetLeft = 64))
        assertFalse(p.mayResend(attemptsSoFar = 0, messageAgeMs = p.MAX_RESEND_AGE_MS + 1, peerBudgetLeft = 64))
        assertFalse(p.mayResend(attemptsSoFar = 0, messageAgeMs = 1_000L, peerBudgetLeft = 0))
    }

    @Test
    fun resentWrapperRoundTripsAndRejectsGarbage() {
        val wire = RatchetHealSignal.Resent.encode("orig-1", "the body")
        assertEquals("orig-1" to "the body", RatchetHealSignal.Resent.parse(wire))
        assertNull(RatchetHealSignal.Resent.parse("__RATCHET_RESENT__v1\n{\"b\":\"no id\"}"))
        assertNull(RatchetHealSignal.Resent.parse("__RATCHET_RESENT__v1 {\"id\":\"x\"}"))
        assertNull(RatchetHealSignal.Resent.parse("plain text"))
    }

    @Test
    fun policyCooldownAndTieBreak() {
        val p = RatchetHealSignal.Policy
        assertTrue(p.maySend(null, 1_000L))
        assertFalse(p.maySend(1_000L, 1_000L + p.COOLDOWN_MS - 1))
        assertTrue(p.maySend(1_000L, 1_000L + p.COOLDOWN_MS))
        assertTrue(p.isHealInitiator("AAA", "BBB"))
        assertFalse(p.isHealInitiator("BBB", "AAA"))
        assertFalse(p.isHealInitiator("AAA", "AAA"))
    }
}
