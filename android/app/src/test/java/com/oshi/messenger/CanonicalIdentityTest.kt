package com.oshi.messenger

import com.oshi.messenger.data.repository.MessageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `MessageRepository.canonicalIdentity` — the STORAGE spelling of an identity key,
 * ported from iOS `MessageManager.canonicalIdentity(_:)`
 * (`OSHI/MessageManager.swift:6246-6261`) — and `revealLayerOf`, the INSERT-vs-UPDATE
 * decision behind the progressive-reveal collapse.
 *
 * Every fixture below is a LITERAL, produced outside this codebase (`base64.b64encode`
 * / `base64.urlsafe_b64encode` over a fixed 32-byte key in Python). Nothing here is
 * built by calling the function under test, so each assertion can actually fail:
 *
 *  - drop the re-pad and `urlUnpadded`/`standardUnpadded` stop reaching `standard`;
 *  - drop the decode check and the `lora!` pseudo-key comes back mangled;
 *  - drop the `-`/`_` fold and `urlPadded` stays base64url.
 */
class CanonicalIdentityTest {

    /**
     * base64 of the 32 bytes
     * `FB EF 3F FC 34 56 78 9A BC DE F0 11 22 33 44 55 66 77 88 99 AA BB CC DD EE FF 00 0F 3E FB EF 7E`.
     * Chosen because its standard encoding contains BOTH `+` and `/`, so the base64url
     * variant differs in both characters.
     */
    private val standard = "++8//DRWeJq83vARIjNEVWZ3iJmqu8zd7v8ADz77734="
    private val standardUnpadded = "++8//DRWeJq83vARIjNEVWZ3iJmqu8zd7v8ADz77734"
    private val urlPadded = "--8__DRWeJq83vARIjNEVWZ3iJmqu8zd7v8ADz77734="
    private val urlUnpadded = "--8__DRWeJq83vARIjNEVWZ3iJmqu8zd7v8ADz77734"

    // ---- canonicalIdentity: the four spellings of one key -------------------

    @Test
    fun `standard padded is already canonical and comes back untouched`() {
        assertEquals(standard, MessageRepository.canonicalIdentity(standard))
    }

    @Test
    fun `standard unpadded is re-padded`() {
        assertEquals(standard, MessageRepository.canonicalIdentity(standardUnpadded))
    }

    @Test
    fun `base64url padded folds to standard`() {
        assertEquals(standard, MessageRepository.canonicalIdentity(urlPadded))
    }

    @Test
    fun `base64url unpadded folds AND re-pads`() {
        assertEquals(standard, MessageRepository.canonicalIdentity(urlUnpadded))
    }

    @Test
    fun `all four spellings collapse to exactly one stored key`() {
        // The whole point: one identity, one row-key. Two spellings of one key means
        // messages addressed to nobody.
        val stored = listOf(standard, standardUnpadded, urlPadded, urlUnpadded)
            .map { MessageRepository.canonicalIdentity(it) }
            .toSet()
        assertEquals(setOf(standard), stored)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(standard, MessageRepository.canonicalIdentity("  $urlUnpadded\n"))
    }

    // ---- canonicalIdentity: it must never mangle a non-key -----------------

    @Test
    fun `a non-base64 pseudo-key is returned unchanged`() {
        // The unbound-radio conversation key. Length 11, so it does NOT take the fast
        // path; only the decode check saves it from becoming `lora!a1b2c3=`.
        val pseudo = "lora!a1b2c3"
        assertEquals(pseudo, MessageRepository.canonicalIdentity(pseudo))
    }

    @Test
    fun `a string that is not base64 at all survives the fold`() {
        val junk = "-not-a-key_at-all"
        assertEquals(junk, MessageRepository.canonicalIdentity(junk))
    }

    @Test
    fun `empty stays empty`() {
        assertEquals("", MessageRepository.canonicalIdentity(""))
    }

    @Test
    fun `it is idempotent`() {
        for (k in listOf(standard, standardUnpadded, urlPadded, urlUnpadded, "lora!a1b2c3", "")) {
            val once = MessageRepository.canonicalIdentity(k)
            assertEquals(once, MessageRepository.canonicalIdentity(once))
        }
    }

    @Test
    fun `it is NOT the same relation as normalizeBase64Key`() {
        // `normalizeBase64Key` strips the padding, which yields a string that is not
        // legal base64 and therefore cannot be STORED. Keeping the two apart is the
        // finding; if they ever converge, this fails.
        assertNotEquals(
            MessageRepository.normalizeBase64Key(urlUnpadded),
            MessageRepository.canonicalIdentity(urlUnpadded)
        )
        assertEquals(standardUnpadded, MessageRepository.normalizeBase64Key(urlUnpadded))
    }

    // ---- revealLayerOf: N envelopes in, ONE row out ------------------------

    /** A plain UUID, as both platforms mint message ids. */
    private val revealId = "9a1f0c2e-77b4-4d51-8c33-0f9a6d2b1e40"

    @Test
    fun `a five-layer ladder produces exactly one inserted row`() {
        // Literal ids, in arrival order — layer 0 under the bare id, then `#1`..`#4`
        // (iOS TimedReveal.swift:238). Written out rather than generated, so this
        // cannot pass by agreeing with a generator that is itself wrong.
        val envelopes = listOf(
            "9a1f0c2e-77b4-4d51-8c33-0f9a6d2b1e40",
            "9a1f0c2e-77b4-4d51-8c33-0f9a6d2b1e40#1",
            "9a1f0c2e-77b4-4d51-8c33-0f9a6d2b1e40#2",
            "9a1f0c2e-77b4-4d51-8c33-0f9a6d2b1e40#3",
            "9a1f0c2e-77b4-4d51-8c33-0f9a6d2b1e40#4",
        )
        val inserts = envelopes.filter { MessageRepository.revealLayerOf(it) == null }
        val updates = envelopes.mapNotNull { MessageRepository.revealLayerOf(it) }

        assertEquals(listOf(revealId), inserts)                 // one bubble
        assertEquals(4, updates.size)                           // four rewrites of it
        assertEquals(listOf(1, 2, 3, 4), updates.map { it.second })
        assertEquals(setOf(revealId), updates.map { it.first }.toSet())
    }

    @Test
    fun `layer zero is the bare id, never a hash-zero suffix`() {
        assertNull(MessageRepository.revealLayerOf(revealId))
        assertNull(MessageRepository.revealLayerOf("$revealId#0"))
    }

    @Test
    fun `an ordinary message id is not a layer`() {
        assertNull(MessageRepository.revealLayerOf("7c2b91d4-0e55-4a18-9f60-2ad3c8b71e09"))
    }

    @Test
    fun `malformed suffixes are not layers`() {
        assertNull(MessageRepository.revealLayerOf("$revealId#"))
        assertNull(MessageRepository.revealLayerOf("$revealId#abc"))
        assertNull(MessageRepository.revealLayerOf("$revealId#1x"))
        assertNull(MessageRepository.revealLayerOf("$revealId#-2"))
        assertNull(MessageRepository.revealLayerOf("#3"))          // no reveal id
        assertNull(MessageRepository.revealLayerOf(""))
    }

    @Test
    fun `the layer index is bounded by the ladder cap iOS uses`() {
        assertEquals(revealId to 63, MessageRepository.revealLayerOf("$revealId#63"))
        assertNull(MessageRepository.revealLayerOf("$revealId#64"))
        assertNull(MessageRepository.revealLayerOf("$revealId#900"))
    }

    @Test
    fun `only the LAST hash splits the id`() {
        assertEquals("a#b" to 2, MessageRepository.revealLayerOf("a#b#2"))
    }
}
