package com.oshi.messenger

import com.oshi.messenger.service.CallAdmissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two gates every incoming call has to clear, tested against the Swift original.
 *
 * Reference:
 *  - `shouldRejectUnknownCaller`  VoiceCallManager.swift:12639-12643
 *  - `isKnownContact`             VoiceCallManager.swift:12629-12637
 *  - push-path variant            VoIPPushManager.swift:1325-1338
 *
 * Only the pure decision functions are exercised — no Android runtime, no DAO — which is
 * exactly why they were extracted into `CallAdmissionPolicy` instead of being inlined at
 * the four ingress sites.
 */
class CallAdmissionPolicyTest {

    private val alice = "n1Rr7HpQ+2sKdF9xYzAbCdEfGhIjKlMnOpQrStUvWxY="
    private val bob = "ZZZZ7HpQ+2sKdF9xYzAbCdEfGhIjKlMnOpQrStUvWxY="

    // =====================================================================
    // shouldRejectUnknownCaller — VoiceCallManager.swift:12639-12643
    // =====================================================================

    /**
     * `guard contactsOnly else { return false }` (swift:12641). The setting defaults to
     * OFF (`UserDefaults.bool` on an unset key, swift:12640), and while it is off the
     * gate must be completely inert — a stranger can still ring you, which is the
     * shipped behaviour on both platforms.
     */
    @Test
    fun `gate is inert while contacts-only is off`() {
        assertFalse(CallAdmissionPolicy.shouldRejectUnknownCaller(contactsOnlyEnabled = false, isKnownContact = false))
        assertFalse(CallAdmissionPolicy.shouldRejectUnknownCaller(contactsOnlyEnabled = false, isKnownContact = true))
    }

    /** `return !isKnownContact` (swift:12642). */
    @Test
    fun `contacts-only rejects strangers and admits contacts`() {
        assertTrue(CallAdmissionPolicy.shouldRejectUnknownCaller(contactsOnlyEnabled = true, isKnownContact = false))
        assertFalse(CallAdmissionPolicy.shouldRejectUnknownCaller(contactsOnlyEnabled = true, isKnownContact = true))
    }

    // =====================================================================
    // isKnownContact — VoiceCallManager.swift:12629-12637
    // =====================================================================

    @Test
    fun `known contact is matched exactly`() {
        assertTrue(CallAdmissionPolicy.isKnownContact(alice, listOf(bob, alice)))
        assertFalse(CallAdmissionPolicy.isKnownContact(alice, listOf(bob)))
    }

    /**
     * `base64urlDecode` on BOTH sides before comparing (swift:12631-12634).
     *
     * This is the case that must not regress: iOS's own push path
     * (`VoIPPushManager.swift:1329`) compares raw strings and therefore rejects a
     * genuine contact whose stored key is spelled base64url. Android normalizes on
     * every ingress, so the same peer is recognised whichever spelling arrives.
     */
    @Test
    fun `base64url and padded spellings of the same key are the same contact`() {
        val standard = "n1Rr7HpQ+2sK/dF9xYzAbCdEfGhIjKlMnOpQrStUvWxY="
        val urlSafe = "n1Rr7HpQ-2sK_dF9xYzAbCdEfGhIjKlMnOpQrStUvWxY"
        assertTrue(CallAdmissionPolicy.isKnownContact(urlSafe, listOf(standard)))
        assertTrue(CallAdmissionPolicy.isKnownContact(standard, listOf(urlSafe)))
        assertTrue(CallAdmissionPolicy.isKnownContact("  $standard  ", listOf(urlSafe)))
    }

    /** Two genuinely different keys must not collapse into each other. */
    @Test
    fun `different keys never match after normalization`() {
        assertFalse(CallAdmissionPolicy.isKnownContact(alice, listOf(bob)))
        assertEquals(CallAdmissionPolicy.normalizeKey(alice), CallAdmissionPolicy.normalizeKey("$alice="))
    }

    /**
     * Fail closed. iOS's `guard let conversations = ... else { return false }`
     * (swift:12630) and `?? false` (swift:1330) both mean "unknown" when the contact
     * set cannot be read — so with contacts-only ON, an empty set rejects everyone
     * rather than admitting everyone.
     */
    @Test
    fun `empty contact set means unknown, not everyone`() {
        assertFalse(CallAdmissionPolicy.isKnownContact(alice, emptyList()))
        assertTrue(
            CallAdmissionPolicy.shouldRejectUnknownCaller(
                contactsOnlyEnabled = true,
                isKnownContact = CallAdmissionPolicy.isKnownContact(alice, emptyList())
            )
        )
    }

    /** An empty caller key is never "someone we know". */
    @Test
    fun `empty caller key is not a known contact`() {
        assertFalse(CallAdmissionPolicy.isKnownContact("", listOf(alice, bob)))
        assertFalse(CallAdmissionPolicy.isKnownContact("", listOf("")))
    }

    /** The iOS UserDefaults key is a wire-visible contract shared with the iPhone build. */
    @Test
    fun `settings key matches the iOS UserDefaults key verbatim`() {
        assertEquals("callsFromContactsOnly", CallAdmissionPolicy.KEY_CONTACTS_ONLY_CALLS)
        assertEquals("oshi_settings", CallAdmissionPolicy.PREFS_NAME)
    }
}
