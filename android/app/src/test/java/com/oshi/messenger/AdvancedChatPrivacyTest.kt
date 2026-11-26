package com.oshi.messenger

import com.oshi.messenger.data.model.AdvancedChatPrivacyChange
import com.oshi.messenger.data.model.AdvancedChatPrivacyPolicy
import com.oshi.messenger.data.model.Message
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.network.v2.GroupLegacyCompat
import com.oshi.messenger.service.AdvancedChatPrivacyReaders
import com.oshi.messenger.service.AdvancedChatPrivacyStore
import com.oshi.messenger.service.NotificationPresentation
import com.oshi.messenger.ui.screens.ChatThreadItems
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * __ADV_CHAT_PRIVACY_2026_11_26__ The wire (shared with iOS), last-writer-wins,
 * the future clamp, the catalog's answers, and each enforcement decision.
 */
class AdvancedChatPrivacyTest {

    private val now = 1_764_000_000L
    private val alice = "q83vEjRWeJASNFZ4kBI0VniQEjRWeJASNFZ4kBI0Vng="   // standard base64
    private val aliceUrl = "q83vEjRWeJASNFZ4kBI0VniQEjRWeJASNFZ4kBI0Vng"  // same key, unpadded
    private val group = "6f1c2a9e-3b4d-4e5f-8a7b-9c0d1e2f3a4b"

    @Before
    fun reset() {
        AdvancedChatPrivacyStore.resetForTesting()
        AdvancedChatPrivacyReaders.resetForTesting()
    }

    // ── wire ──

    @Test
    fun theWireSpellingIsTheBareShieldAgreedWithIOS() {
        val wire = AdvancedChatPrivacyChange(on = true, atUnixSeconds = 1_761_220_000L).toMessageString()
        assertTrue(wire, wire.startsWith("🛡ADV_PRIVACY🛡{"))
        assertFalse("no U+FE0F on the wire", wire.contains("️"))
        val json = JSONObject(wire.removePrefix(OSHIControlPayload.ADV_PRIVACY_WIRE))
        assertEquals(setOf("v", "on", "at"), json.keys().asSequence().toSet())
        assertEquals(1, json.getInt("v"))
        assertEquals(true, json.getBoolean("on"))
        // An integer, so a Swift `Int` field decodes it as well as a `Double` one.
        assertTrue(json.get("at") is Number)
        assertFalse(json.get("at").toString(), json.get("at").toString().contains("."))
        assertEquals(1_761_220_000L, json.getLong("at"))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        for (on in listOf(true, false)) {
            val sent = AdvancedChatPrivacyChange.create(on, atMillis = 1_700_000_123_456L)
            assertEquals(1_700_000_123L, sent.atUnixSeconds)
            val back = AdvancedChatPrivacyChange.fromMessageString(sent.toMessageString())
            assertEquals(sent, back)
        }
    }

    @Test
    fun decodesTheEmojiPickerSpellingAndAFractionalAt() {
        val withSelector = "🛡️ADV_PRIVACY🛡️{\"v\":1,\"on\":false,\"at\":1761220000.75}"
        val c = AdvancedChatPrivacyChange.fromMessageString(withSelector)
        assertNotNull(c)
        assertEquals(false, c!!.on)
        assertEquals(1_761_220_000L, c.atUnixSeconds)
        assertTrue(AdvancedChatPrivacyChange.isPayload(withSelector))
    }

    @Test
    fun rejectsUnknownVersionsGarbageAndProse() {
        assertNull(AdvancedChatPrivacyChange.fromMessageString(OSHIControlPayload.ADV_PRIVACY_WIRE + "{\"v\":2,\"on\":true,\"at\":1}"))
        assertNull(AdvancedChatPrivacyChange.fromMessageString(OSHIControlPayload.ADV_PRIVACY_WIRE + "{\"v\":1,\"on\":true}"))
        assertNull(AdvancedChatPrivacyChange.fromMessageString(OSHIControlPayload.ADV_PRIVACY_WIRE + "not json"))
        assertNull(AdvancedChatPrivacyChange.fromMessageString("hello 🛡ADV_PRIVACY🛡{\"v\":1,\"on\":true,\"at\":1}"))
        assertFalse(AdvancedChatPrivacyChange.isPayload("hello"))
    }

    @Test
    fun anUnreadableFlagReadsAsOn() {
        val c = AdvancedChatPrivacyChange.fromMessageString(OSHIControlPayload.ADV_PRIVACY_WIRE + "{\"v\":1,\"at\":5}")
        assertEquals(true, c?.on)
    }

    // ── last writer wins ──

    @Test
    fun newerWinsOlderAndEqualAreIgnored() {
        val s = AdvancedChatPrivacyStore
        assertTrue(s.applyIncoming(AdvancedChatPrivacyChange(on = true, atUnixSeconds = now - 100), alice, now))
        assertTrue(s.isOn(alice))
        // A late, older "off" (mesh delivery is unordered) does not undo it.
        assertFalse(s.applyIncoming(AdvancedChatPrivacyChange(on = false, atUnixSeconds = now - 200), alice, now))
        assertTrue(s.isOn(alice))
        // The same change twice is a no-op.
        assertFalse(s.applyIncoming(AdvancedChatPrivacyChange(on = false, atUnixSeconds = now - 100), alice, now))
        assertTrue(s.isOn(alice))
        // A newer "off" wins.
        assertTrue(s.applyIncoming(AdvancedChatPrivacyChange(on = false, atUnixSeconds = now - 50), alice, now))
        assertFalse(s.isOn(alice))
    }

    @Test
    fun ourOwnSwitchIsRecordedAndAnOlderPeerChangeCannotOverrideIt() {
        val s = AdvancedChatPrivacyStore
        s.setLocal(AdvancedChatPrivacyChange(on = true, atUnixSeconds = now), alice)
        assertTrue(s.isOn(alice))
        assertFalse(s.applyIncoming(AdvancedChatPrivacyChange(on = false, atUnixSeconds = now - 1), alice, now))
        assertTrue(s.isOn(alice))
    }

    @Test
    fun keySpellingsAndGroupIdCaseFoldToOneConversation() {
        val s = AdvancedChatPrivacyStore
        s.applyIncoming(AdvancedChatPrivacyChange(on = true, atUnixSeconds = now), aliceUrl, now)
        assertTrue(s.isOn(alice))
        s.applyIncoming(AdvancedChatPrivacyChange(on = true, atUnixSeconds = now), group.uppercase(), now)
        assertTrue(s.isOn(group.lowercase()))
        assertFalse(s.isOn("someone-else"))
        assertFalse(s.isOn(""))
    }

    @Test
    fun anEqualTimestampResolvesToOn() {
        val s = AdvancedChatPrivacyStore
        // off then on in the same second → ON
        s.applyIncoming(AdvancedChatPrivacyChange(on = false, atUnixSeconds = now), alice, now)
        assertTrue(s.applyIncoming(AdvancedChatPrivacyChange(on = true, atUnixSeconds = now), alice, now))
        assertTrue(s.isOn(alice))
        // on then off in the same second → stays ON
        s.applyIncoming(AdvancedChatPrivacyChange(on = true, atUnixSeconds = now + 10), group, now + 10)
        assertFalse(s.applyIncoming(AdvancedChatPrivacyChange(on = false, atUnixSeconds = now + 10), group, now + 10))
        assertTrue(s.isOn(group))
    }

    // ── who may receive the payload ──

    @Test
    fun thisBuildAdvertisesBit16AndTheBitIsTheSameAsIOS() {
        assertEquals(16, GroupLegacyCompat.CAP_ADVANCED_PRIVACY)
        assertEquals(GroupLegacyCompat.CAP_ADVANCED_PRIVACY, AdvancedChatPrivacyPolicy.CAP_ADVANCED_PRIVACY)
        assertTrue((GroupLegacyCompat.MY_CAPS and 16) != 0)
        // The four older bits are untouched.
        assertEquals(15, GroupLegacyCompat.MY_CAPS and 15)
    }

    @Test
    fun aPeerReadsThePayloadOnlyByCapOrByProof() {
        assertTrue(AdvancedChatPrivacyPolicy.peerReads(accountCaps = 16, sentUsOne = false))
        assertTrue(AdvancedChatPrivacyPolicy.peerReads(accountCaps = 31, sentUsOne = false))
        assertTrue(AdvancedChatPrivacyPolicy.peerReads(accountCaps = null, sentUsOne = true))
        // An Android 1.7.4 / desktop bundle (15 / 14) and an unknown bundle do not.
        assertFalse(AdvancedChatPrivacyPolicy.peerReads(accountCaps = 15, sentUsOne = false))
        assertFalse(AdvancedChatPrivacyPolicy.peerReads(accountCaps = 14, sentUsOne = false))
        assertFalse(AdvancedChatPrivacyPolicy.peerReads(accountCaps = null, sentUsOne = false))
    }

    @Test
    fun havingSentUsOneIsRememberedUnderEitherKeySpelling() {
        val r = AdvancedChatPrivacyReaders
        assertFalse(r.contains(alice))
        assertFalse(r.peerReadsNow(alice))
        r.note(aliceUrl)
        assertTrue(r.contains(alice))
        assertTrue(r.peerReadsNow(alice))
        assertFalse(r.peerReadsNow("bob"))
    }

    @Test
    fun aGroupFanOutKeepsOnlyTheReaders() {
        val members = listOf("a", "b", "c", "d")
        val reads = setOf("b", "d")
        assertEquals(listOf("b", "d"), AdvancedChatPrivacyPolicy.readers(members) { it in reads })
        assertEquals(emptyList<String>(), AdvancedChatPrivacyPolicy.readers(members) { false })
    }

    // ── future clamp ──

    @Test
    fun aFutureTimestampIsClampedToNowPlusFiveMinutes() {
        assertEquals(now + 300, AdvancedChatPrivacyChange.clampedAt(now + 1_000_000_000L, now))
        assertEquals(now - 5, AdvancedChatPrivacyChange.clampedAt(now - 5, now))

        val s = AdvancedChatPrivacyStore
        // A peer claiming the year 3000 for "off" …
        s.applyIncoming(AdvancedChatPrivacyChange(on = false, atUnixSeconds = 32_503_680_000L), alice, now)
        assertEquals(now + 300, s.stateOf(alice)!!.changedAtUnixSeconds)
        // … cannot lock the conversation: a change six minutes later still wins.
        assertTrue(s.applyIncoming(AdvancedChatPrivacyChange(on = true, atUnixSeconds = now + 360), alice, now + 360))
        assertTrue(s.isOn(alice))
    }

    // ── catalog ──

    @Test
    fun theCatalogRendersItButNeverNotifies() {
        val on = AdvancedChatPrivacyChange(on = true, atUnixSeconds = now).toMessageString()
        val off = AdvancedChatPrivacyChange(on = false, atUnixSeconds = now).toMessageString()
        for (raw in listOf(on, off)) {
            assertEquals(OSHIControlPayload.ADV_PRIVACY, OSHIControlPayload.matchedPrefix(raw))
            assertTrue("isControl ⇒ excluded from the AI context builder", OSHIControlPayload.isControl(raw))
            assertFalse("rendered, not silent", OSHIControlPayload.isSilent(raw))
            assertTrue("no push to the peer", OSHIControlPayload.suppressesPush(raw))
            assertTrue(OSHIControlPayload.neverNotifies(raw))
            assertEquals(NotificationPresentation.Decision.Suppress, NotificationPresentation.decide(raw))
            assertFalse("never raw JSON on a row", OSHIControlPayload.listPreview(raw).contains("{"))
        }
        assertEquals("🛡 Advanced chat privacy turned on", OSHIControlPayload.displaySummary(on))
        assertEquals("🛡 Advanced chat privacy turned off", OSHIControlPayload.displaySummary(off))
        // Prose that merely mentions it is prose.
        assertFalse(OSHIControlPayload.neverNotifies("about 🛡ADV_PRIVACY🛡 later"))
    }

    @Test
    fun theThreadKeepsTheNoticeRow() {
        val row = Message(
            senderPublicKey = alice, recipientPublicKey = "me",
            content = AdvancedChatPrivacyChange(on = true, atUnixSeconds = now).toMessageString(),
            isFromMe = false, isRead = true
        )
        assertEquals(listOf(row), ChatThreadItems.renderable(listOf(row)))
    }

    // ── enforcement decisions ──

    @Test
    fun aiContextIsEmptyForAProtectedConversation() {
        val readable = listOf(false to "hi", true to "hello", false to "how are you")
        val s = AdvancedChatPrivacyStore
        s.setLocal(AdvancedChatPrivacyChange(on = true, atUnixSeconds = now), alice)
        assertEquals(emptyList<Pair<Boolean, String>>(), AdvancedChatPrivacyPolicy.aiReadable(s.isOn(alice), readable))
        assertFalse(AdvancedChatPrivacyPolicy.aiAllowed(s.isOn(alice)))
        // An unprotected conversation is untouched.
        assertEquals(readable, AdvancedChatPrivacyPolicy.aiReadable(s.isOn("bob"), readable))
        assertTrue(AdvancedChatPrivacyPolicy.aiAllowed(s.isOn("bob")))
    }

    @Test
    fun exportIsDisabledForAProtectedConversationOnly() {
        val s = AdvancedChatPrivacyStore
        s.applyIncoming(AdvancedChatPrivacyChange(on = true, atUnixSeconds = now), group, now)
        assertFalse(AdvancedChatPrivacyPolicy.exportAllowed(s.isOn(group)))
        assertTrue(AdvancedChatPrivacyPolicy.exportAllowed(s.isOn(alice)))
        // Turned back off ⇒ exportable again.
        s.applyIncoming(AdvancedChatPrivacyChange(on = false, atUnixSeconds = now + 1), group, now + 1)
        assertTrue(AdvancedChatPrivacyPolicy.exportAllowed(s.isOn(group)))
    }

    @Test
    fun savingOthersMediaIsBlockedButNotOurOwn() {
        assertFalse(AdvancedChatPrivacyPolicy.mediaSaveAllowed(privacyOn = true, isFromMe = false))
        assertTrue(AdvancedChatPrivacyPolicy.mediaSaveAllowed(privacyOn = true, isFromMe = true))
        assertTrue(AdvancedChatPrivacyPolicy.mediaSaveAllowed(privacyOn = false, isFromMe = false))
    }

    @Test
    fun aProtectedChatOnScreenRequestsASecureWindow() {
        val s = AdvancedChatPrivacyStore
        assertFalse(s.secureWindowRequested)
        s.enterScreen(aliceUrl)
        assertTrue(s.secureWindowRequested)
        assertTrue(AdvancedChatPrivacyPolicy.forcesSecureWindow(true))
        s.leaveScreen(alice)
        assertFalse("leaving by either spelling releases it", s.secureWindowRequested)
        assertFalse(AdvancedChatPrivacyPolicy.systemGeneratedRepliesAllowed(true))
    }
}
