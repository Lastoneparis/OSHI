package com.oshi.messenger

import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.data.repository.MessageRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __PUSH_SPAM_2026_09_17__ Which payloads are allowed to wake the peer's phone.
 *
 * Reported from production: an Android peer was firing APNs alerts at an iPhone for
 * traffic the user never sent. The cause was two lists for one decision — the
 * catalogue in `OSHIControlPayload` (which the renderer uses, and which matches iOS
 * entry for entry) and a hand-written eight-prefix list in `MessageRepository`
 * (which the PUSH used). The second had drifted, and four classes of traffic were
 * getting through it:
 *
 *   • `🔧ACTION🔧` — every edit, delete, pin, unpin. Fixing a typo rang the peer.
 *   • `📢GROUP_UPDATE📢`
 *   • `__SESSION_RESET__`
 *   • the bare group-sync JSON — and this is the one that reads as spam, because it
 *     is PERIODIC: it fires with no user action at all.
 *
 * These cases are asserted against the shared decision, so re-introducing a private
 * list somewhere cannot quietly re-open the hole. Every one of the four fails on the
 * code that shipped in 1.6.21 and earlier.
 */
class PushSuppressionCatalogTest {

    private fun suppressed(text: String) = MessageRepository.isSilentControlContent(text)

    @Test
    fun `the four payloads that were spamming iOS no longer wake the peer`() {
        val silent = mapOf(
            "edit/delete/pin/unpin" to "🔧ACTION🔧{\"actionType\":\"editMessage\"}",
            "group update" to "📢GROUP_UPDATE📢{\"groupId\":\"g1\"}",
            "session reset" to "__SESSION_RESET__",
            "periodic member sync" to "{\"type\":\"member_sync\",\"groupId\":\"g1\"}",
            "periodic sync request" to "{\"type\":\"sync_request\",\"groupId\":\"g1\"}",
            "group update JSON" to "{\"type\":\"group_update\",\"groupId\":\"g1\"}",
            "sync with a space after the colon" to "{\"type\": \"member_sync\"}",
        )
        for ((what, payload) in silent) {
            assertTrue("$what must NOT push: $payload", suppressed(payload))
        }
    }

    @Test
    fun `the payloads that were already silent stay silent`() {
        for (p in listOf(
            "📬DELIVERY_RECEIPT📬x", "📖READ_RECEIPT📖x", "📸PROFILE_UPDATE📸x",
            "📸PROFILE_REQUEST📸x", "🔗LINKS_VISIBILITY🔗x", "🎨WALLPAPER_UPDATE🎨x",
            "⌨️TYPING⌨️", "✍️TYPING✍️",
        )) {
            assertTrue("$p must NOT push", suppressed(p))
        }
    }

    /**
     * The one silent payload that MUST still be allowed through. A call signal is
     * invisible in the thread but it is how the phone rings — suppressing its push
     * would turn every call into a missed call, which is a worse bug than the spam.
     */
    @Test
    fun `a call signal still rings`() {
        assertFalse(
            "📞CALL_SIGNAL📞 must still wake the peer — this is how a call rings",
            suppressed("📞CALL_SIGNAL📞{\"type\":\"offer\"}"),
        )
    }

    @Test
    fun `ordinary prose still notifies`() {
        for (p in listOf(
            "on se voit à 18h ?",
            "{\"type\":\"media\",\"mediaType\":\"image\"}",   // a real photo
            "we should add a 📍LOCATION📍 marker",            // merely MENTIONS a sentinel
        )) {
            assertFalse("ordinary content must still push: $p", suppressed(p))
        }
    }

    /**
     * The decision must come from the catalogue, so the renderer and the push can
     * never disagree about what a payload IS. Anything the catalogue calls silent
     * (except the call signal) has to be push-suppressed too.
     */
    @Test
    fun `catalog and push decision agree`() {
        for (p in listOf(
            "🔧ACTION🔧x", "📢GROUP_UPDATE📢x", "__SESSION_RESET__x",
            "📬DELIVERY_RECEIPT📬x", "📖READ_RECEIPT📖x", "⌨️TYPING⌨️",
        )) {
            assertTrue("catalogue says silent: $p", OSHIControlPayload.isSilent(p))
            assertTrue("so the push must be suppressed too: $p", suppressed(p))
        }
    }
}
