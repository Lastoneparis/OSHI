package com.oshi.messenger.ui

import com.oshi.messenger.ui.viewmodel.NewMessageViewModel
import com.oshi.messenger.ui.viewmodel.RecipientKey
import com.oshi.messenger.ui.viewmodel.RecipientKey.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The New Message screen's recipient rule.
 *
 * The three strings named REPORTED_* below are the ones a real device showed a
 * green "Valid address" for under the old `length >= 20` rule, each of which
 * then failed at send. They are the reason this file exists, so they are
 * asserted by name and with the reason the user is shown.
 */
class RecipientKeyTest {

    /** A real 32-byte X25519 key in the spelling `CryptoManager` emits. */
    private val theirs = "hTiEnKV10MM9IfHxzvlJ59WZj2y4wM/LeERb/kcs4AE="

    /** The same key, base64url. iOS's VoIP push and the VPS paths hand this out. */
    private val theirsUrl = "hTiEnKV10MM9IfHxzvlJ59WZj2y4wM_LeERb_kcs4AE"

    private val mine = "turSEKag/stcMtIZHzaHSxYHSJIVoT1mWvRi7bWqX7w="
    private val mineUrl = "turSEKag_stcMtIZHzaHSxYHSJIVoT1mWvRi7bWqX7w="

    // ── The three strings that were accepted on a real device ────────────────

    /** Autocomplete dropped a word and two spaces into the middle of the paste. */
    private val reportedWithSpaces =
        "hTiEnKV10M You hTiEnKV10MM9IfHxzvlJ59WZj2y4wM/LeERb/kcs4AE="

    /** One character typed after the `=` that ends the key. */
    private val reportedTrailingChar =
        "hTiEnKV10MM9IfHxzvlJ59WZj2y4wM/LeERb/kcs4AE=f"

    /** Standard-alphabet key whose padding was eaten somewhere in the copy chain. */
    private val reportedUnpadded =
        "hTiEnKV10MM9IfHxzvlJ59WZj2y4wM/LeERb/kcs4AE"

    private fun problem(address: String, myKey: String = mine): Problem {
        val result = RecipientKey.validate(address, myKey)
        assertTrue("expected $address to be refused, got $result", result is RecipientKey.Result.Invalid)
        return (result as RecipientKey.Result.Invalid).problem
    }

    private fun canonical(address: String, myKey: String = mine): String {
        val result = RecipientKey.validate(address, myKey)
        assertTrue("expected $address to be accepted, got $result", result is RecipientKey.Result.Valid)
        return (result as RecipientKey.Result.Valid).canonicalKey
    }

    @Test
    fun `a key with a stray word and spaces in it is refused, and says so`() {
        assertEquals(Problem.CONTAINS_WHITESPACE, problem(reportedWithSpaces))
    }

    @Test
    fun `a character after the padding is refused, and says so`() {
        assertEquals(Problem.TRAILING_CHARACTERS, problem(reportedTrailingChar))
    }

    @Test
    fun `a standard-base64 key with its padding stripped is refused, and says so`() {
        assertEquals(Problem.MISSING_PADDING, problem(reportedUnpadded))
    }

    @Test
    fun `the key those three were mangled from is accepted`() {
        assertEquals(theirs, canonical(theirs))
    }

    // ── 32 bytes is the rule ─────────────────────────────────────────────────

    @Test
    fun `only a 32-byte key is accepted`() {
        // 31 bytes and 33 bytes, both perfectly well-formed base64.
        val thirtyOne = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHg=="
        val thirtyThree = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8g"
        assertEquals(32, java.util.Base64.getDecoder().decode(theirs).size)
        assertEquals(Problem.TOO_SHORT, problem(thirtyOne))
        assertEquals(Problem.TOO_LONG, problem(thirtyThree))
    }

    @Test
    fun `the old twenty-character rule no longer lets anything through`() {
        // Every one of these passed `trimmed.length >= 20 && trimmed != myPublicKey`.
        assertEquals(Problem.TOO_SHORT, problem("a".repeat(20)))
        assertEquals(Problem.TOO_SHORT, problem("MCowBQYDK2VuAyEAmykey"))
        assertEquals(Problem.TOO_LONG, problem("MCowBQYDK2VuAyEAmykeymykeymykeymykeymykey0123456789="))
        assertEquals(Problem.CONTAINS_WHITESPACE, problem("this is not a key at all, obviously"))
    }

    @Test
    fun `a right-length string that is not base64 is refused`() {
        assertEquals(Problem.NOT_A_KEY, problem("!".repeat(43)))
        // 43 characters, but '#' is in neither alphabet.
        assertEquals(Problem.NOT_A_KEY, problem(theirs.dropLast(2) + "#="))
    }

    // ── Two spellings of one key ─────────────────────────────────────────────

    @Test
    fun `base64url is accepted and folded onto the spelling the app stores`() {
        assertEquals(theirs, canonical(theirsUrl))
        assertEquals(theirs, canonical("$theirsUrl="))
    }

    @Test
    fun `whitespace around a pasted key is still tolerated`() {
        assertEquals(theirs, canonical("  $theirs\n"))
        assertEquals(theirs, canonical("\t$theirsUrl "))
    }

    @Test
    fun `the two alphabets may not be mixed`() {
        val mixed = "hTiEnKV10MM9IfHxzvlJ59WZj2y4wM_LeERb/kcs4AE="
        assertEquals(43, mixed.dropLast(1).length)
        assertEquals(Problem.NOT_A_KEY, problem(mixed))
    }

    @Test
    fun `a non-canonical encoding of the same 32 bytes is refused`() {
        // base64 ignores the two spare bits in the 43rd character, so this
        // decodes to exactly the same key as `theirs` — and would give that key
        // a second spelling. It is not the spelling any producer emits.
        val sameBytesOtherSpelling = theirs.dropLast(2) + "F="
        assertEquals(
            java.util.Base64.getDecoder().decode(theirs).toList(),
            java.util.Base64.getDecoder().decode(sameBytesOtherSpelling).toList()
        )
        assertEquals(Problem.NOT_A_KEY, problem(sameBytesOtherSpelling))
    }

    // ── Our own key ──────────────────────────────────────────────────────────

    @Test
    fun `my own key is never a valid recipient, in either spelling`() {
        assertEquals(Problem.OWN_KEY, problem(mine))
        assertEquals(Problem.OWN_KEY, problem(" $mine "))
        // The old string compare missed this one entirely.
        assertEquals(Problem.OWN_KEY, problem(mineUrl))
        assertEquals(Problem.OWN_KEY, problem(mineUrl.dropLast(1)))
        // …and the reverse: my key stored base64url, theirs pasted as standard.
        assertEquals(Problem.OWN_KEY, problem(mine, myKey = mineUrl))
    }

    @Test
    fun `an empty field is empty, not invalid`() {
        assertEquals(Problem.EMPTY, problem(""))
        assertEquals(Problem.EMPTY, problem("   \n "))
    }

    @Test
    fun `a device with no key yet still refuses its own empty string`() {
        // getPublicKeyString() returns "" before a key pair exists; that must not
        // make every paste look like our own key, nor crash the canonical compare.
        assertEquals(theirs, canonical(theirs, myKey = ""))
        assertEquals(Problem.EMPTY, problem("", myKey = ""))
    }

    // ── canonicalize / the boolean the rest of the app still calls ───────────

    @Test
    fun `canonicalize accepts every spelling of a key and nothing else`() {
        assertEquals(theirs, RecipientKey.canonicalize(theirs))
        assertEquals(theirs, RecipientKey.canonicalize(theirsUrl))
        assertEquals(theirs, RecipientKey.canonicalize("  $theirs "))
        assertNull(RecipientKey.canonicalize(reportedWithSpaces))
        assertNull(RecipientKey.canonicalize(reportedTrailingChar))
        assertNull(RecipientKey.canonicalize(""))
        // canonicalize does not know whose key it is; only validate() does.
        assertEquals(mine, RecipientKey.canonicalize(mineUrl))
    }

    @Test
    fun `canonicalRecipientOrNull hands back the key to send to`() {
        assertEquals(theirs, RecipientKey.canonicalRecipientOrNull(theirsUrl, mine))
        assertNull(RecipientKey.canonicalRecipientOrNull(reportedUnpadded, mine))
        assertNull(RecipientKey.canonicalRecipientOrNull(mineUrl, mine))
    }

    @Test
    fun `the ViewModel boolean agrees with the validator`() {
        assertTrue(NewMessageViewModel.isValidRecipient(theirs, mine))
        assertTrue(NewMessageViewModel.isValidRecipient(theirsUrl, mine))
        assertFalse(NewMessageViewModel.isValidRecipient(reportedWithSpaces, mine))
        assertFalse(NewMessageViewModel.isValidRecipient(reportedTrailingChar, mine))
        assertFalse(NewMessageViewModel.isValidRecipient(reportedUnpadded, mine))
        assertFalse(NewMessageViewModel.isValidRecipient(mine, mine))
        assertFalse(NewMessageViewModel.isValidRecipient("a".repeat(20), mine))
    }

    @Test
    fun `the declared lengths describe the canonical key`() {
        assertEquals(RecipientKey.CANONICAL_LENGTH, theirs.length)
        assertEquals(RecipientKey.BODY_LENGTH, theirs.dropLast(1).length)
        assertEquals(RecipientKey.KEY_BYTES, java.util.Base64.getDecoder().decode(theirs).size)
    }
}
