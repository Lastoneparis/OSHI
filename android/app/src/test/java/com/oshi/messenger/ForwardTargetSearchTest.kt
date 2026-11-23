package com.oshi.messenger

import com.oshi.messenger.service.ForwardTargetSearch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardTargetSearchTest {

    private val key = "AbCdEf0123456789+/xyzABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnop"

    @Test
    fun `empty query matches everything`() {
        assertTrue(ForwardTargetSearch.matches("", "Anatole", key))
        assertTrue(ForwardTargetSearch.matches("   ", "Anatole", key))
    }

    @Test
    fun `alias matches case insensitively and mid-word`() {
        assertTrue(ForwardTargetSearch.matches("nat", "Anatole", key))
        assertTrue(ForwardTargetSearch.matches("ANAT", "Anatole", key))
        assertTrue(ForwardTargetSearch.matches("anatole", "Anatole", key))
    }

    @Test
    fun `a full pasted key finds the contact`() {
        // The reported failure: the whole key in hand, zero results.
        assertTrue(ForwardTargetSearch.matches(key, "Anatole", key))
    }

    @Test
    fun `a key prefix finds the contact`() {
        assertTrue(ForwardTargetSearch.matches(key.take(8), "Anatole", key))
        assertTrue(ForwardTargetSearch.matches(key.take(20), "Anatole", key))
    }

    @Test
    fun `base64url spelling of the same key still matches`() {
        // The same key arrives spelled differently depending on transport.
        val urlSpelling = key.replace('+', '-').replace('/', '_') + "=="
        assertTrue(ForwardTargetSearch.matches(urlSpelling, "Anatole", key))
        assertTrue(ForwardTargetSearch.matches(urlSpelling.take(24), "Anatole", key))
    }

    @Test
    fun `key matching is case sensitive so it cannot surface the wrong person`() {
        // THE safety test. Base64 case is significant; a case-insensitive key
        // match could put a different contact under a typed prefix, and the next
        // tap forwards a private message to them.
        assertFalse(ForwardTargetSearch.matches(key.take(12).lowercase(), "Someone", key))
        assertFalse(ForwardTargetSearch.matches(key.take(12).uppercase(), "Someone", key))
    }

    @Test
    fun `key matching is prefix not substring`() {
        // A fragment from the middle of a base64 blob is coincidence.
        val middle = key.substring(20, 32)
        assertFalse(ForwardTargetSearch.matches(middle, "Someone", key))
    }

    @Test
    fun `a very short query is treated as an alias only`() {
        // "Ab" is a real prefix of the key, but two characters of base64 match
        // far too many people to be a useful key search.
        assertFalse(ForwardTargetSearch.matches("Ab", "Someone", key))
        // ...and still works as an alias search.
        assertTrue(ForwardTargetSearch.matches("Ab", "Abigail", key))
    }

    @Test
    fun `a group with no key is matched by name only`() {
        assertTrue(ForwardTargetSearch.matches("manif", "Manifestation", null))
        assertFalse(ForwardTargetSearch.matches(key, "Manifestation", null))
    }

    @Test
    fun `a non-matching query matches nothing`() {
        assertFalse(ForwardTargetSearch.matches("zzzz", "Anatole", key))
    }
}
