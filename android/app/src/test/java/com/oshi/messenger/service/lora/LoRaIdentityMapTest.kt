package com.oshi.messenger.service.lora

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LoRaIdentityMapTest — the routing rules that decide which conversation an
 * inbound plain-text LoRa packet lands in.
 *
 * The rules under test are deliberate, not incidental (iOS
 * `LoRaNodeIdentityMap.swift:24-49`):
 *   • a raw TEXT_MESSAGE_APP packet carries NO identity, only a node number;
 *   • a node is bound to a public key ONLY when an OSHI envelope from it
 *     actually decrypted;
 *   • nothing proved → the `lora!<nodehex>` thread;
 *   • TWO different identities proved → back to `lora!`, permanently, because
 *     putting a stranger's message inside a friend's thread in an encrypted
 *     messenger is worse than an extra conversation.
 */
class LoRaIdentityMapTest {

    private lateinit var store: InMemoryLoRaIdentityStore
    private lateinit var map: LoRaNodeIdentityMap

    private val keyA = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    private val keyB = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
    private val nodeA = 0x1234ABCDL
    private val nodeB = 0x0000BEEFL

    private var savedAdopt = false

    @Before
    fun setUp() {
        savedAdopt = LoRaNodeIdentityMap.adoptRawTextIntoContactThread
        store = InMemoryLoRaIdentityStore()
        map = LoRaNodeIdentityMap(store)
    }

    @After
    fun tearDown() {
        LoRaNodeIdentityMap.adoptRawTextIntoContactThread = savedAdopt
    }

    // =====================================================================
    // Keys
    // =====================================================================

    /**
     * Fixed 8-digit hex, NOT a hash: a hash-derived key would address a
     * different slot after a relaunch, so the mapping would evaporate at the
     * exact moment it is supposed to be useful. iOS `:44-49`, `:111-113`.
     */
    @Test
    fun `node key is fixed width hex`() {
        assertEquals("1234abcd", LoRaNodeIdentityMap.nodeKey(0x1234ABCDL))
        assertEquals("0000beef", LoRaNodeIdentityMap.nodeKey(0xBEEFL))
        assertEquals("00000000", LoRaNodeIdentityMap.nodeKey(0L))
        assertEquals("ffffffff", LoRaNodeIdentityMap.nodeKey(0xFFFFFFFFL))
    }

    /** A node number that arrived sign-extended from an Int must key the same slot. */
    @Test
    fun `node key masks to 32 bits`() {
        val signExtended = (-1).toLong()          // 0xFFFFFFFFFFFFFFFF
        assertEquals("ffffffff", LoRaNodeIdentityMap.nodeKey(signExtended))
        assertEquals(
            LoRaNodeIdentityMap.nodeKey(0xFFFFFFFFL),
            LoRaNodeIdentityMap.nodeKey(signExtended)
        )
    }

    /** Existing "lora!…" conversations keep working precisely because this never changed. */
    @Test
    fun `fallback conversation key format is frozen`() {
        assertEquals("lora!1234abcd", LoRaNodeIdentityMap.fallbackConversationKey(0x1234ABCDL))
        assertEquals("lora!0000beef", LoRaNodeIdentityMap.fallbackConversationKey(0xBEEFL))
        assertTrue(LoRaNodeIdentityMap.isFallbackConversationKey("lora!0000beef"))
        assertFalse(LoRaNodeIdentityMap.isFallbackConversationKey(keyA))
        assertEquals(
            LoRaNodeIdentityMap.fallbackConversationKey(nodeA),
            map.fallbackConversationKey(nodeA)
        )
    }

    /** A synthetic key, an empty string or a fragment can never become a binding. iOS `:130-136`. */
    @Test
    fun `identity key sanity filter`() {
        assertTrue(LoRaNodeIdentityMap.looksLikeIdentityKey(keyA))
        assertFalse("empty", LoRaNodeIdentityMap.looksLikeIdentityKey(""))
        assertFalse("too short", LoRaNodeIdentityMap.looksLikeIdentityKey("abc"))
        assertFalse("15 chars is under the floor", LoRaNodeIdentityMap.looksLikeIdentityKey("a".repeat(15)))
        assertTrue("16 is the floor", LoRaNodeIdentityMap.looksLikeIdentityKey("a".repeat(16)))
        assertFalse("synthetic", LoRaNodeIdentityMap.looksLikeIdentityKey("lora!1234abcd"))
        assertFalse("bang anywhere", LoRaNodeIdentityMap.looksLikeIdentityKey("aaaaaaaa!aaaaaaaaaa"))
    }

    // =====================================================================
    // Nothing proved → the fallback thread
    // =====================================================================

    @Test
    fun `unproved node routes to the fallback thread`() {
        assertNull(map.publicKey(nodeA))
        assertEquals("lora!1234abcd", map.conversationKey(nodeA))
    }

    @Test
    fun `rejected keys never bind`() {
        assertEquals(LoRaNodeIdentityMap.LearnResult.REJECTED, map.bind(nodeA, ""))
        assertEquals(LoRaNodeIdentityMap.LearnResult.REJECTED, map.bind(nodeA, "short"))
        assertEquals(
            LoRaNodeIdentityMap.LearnResult.REJECTED,
            map.bind(nodeA, LoRaNodeIdentityMap.fallbackConversationKey(nodeA))
        )
        assertNull(map.binding(nodeA))
        assertEquals(0, map.count)
        assertEquals("lora!1234abcd", map.conversationKey(nodeA))
    }

    // =====================================================================
    // One proved identity → the contact thread (when adoption is on)
    // =====================================================================

    @Test
    fun `one proved identity binds`() {
        assertEquals(LoRaNodeIdentityMap.LearnResult.BOUND, map.bind(nodeA, keyA))
        assertEquals(keyA, map.publicKey(nodeA))
        assertEquals(1, map.count)
        assertFalse(map.binding(nodeA)!!.ambiguous)
        assertEquals(nodeA, map.node(keyA))
    }

    @Test
    fun `the same identity again changes nothing`() {
        map.bind(nodeA, keyA)
        assertEquals(LoRaNodeIdentityMap.LearnResult.UNCHANGED, map.bind(nodeA, keyA))
        assertEquals(keyA, map.publicKey(nodeA))
        assertEquals(1, map.count)
    }

    /**
     * base64url and padding variants of one key must not read as two identities
     * — that would lock a legitimate node out of routing. iOS `:289-293`.
     */
    @Test
    fun `key spelling variants are not a conflict`() {
        val standard = "ab+cd/efab+cd/efab+cd/ef="
        val urlSafe = "ab-cd_efab-cd_efab-cd_ef"
        assertEquals(LoRaNodeIdentityMap.LearnResult.BOUND, map.bind(nodeA, standard))
        assertEquals(LoRaNodeIdentityMap.LearnResult.UNCHANGED, map.bind(nodeA, urlSafe))
        assertFalse(map.binding(nodeA)!!.ambiguous)
        // Stored EXACTLY as it first arrived, so it matches contact keys byte
        // for byte elsewhere. Comparison is normalized; storage is not.
        assertEquals(standard, map.publicKey(nodeA))
    }

    /**
     * The routing switch. DEFAULT OFF and honest about why: a raw Meshtastic
     * text packet is UNAUTHENTICATED — anyone in range can transmit one claiming
     * a bound node number. iOS `:157-183`.
     */
    @Test
    fun `adoption flag gates the routing decision`() {
        map.bind(nodeA, keyA)

        LoRaNodeIdentityMap.adoptRawTextIntoContactThread = false
        assertEquals(
            "off: raw text still goes to its own lora! thread",
            "lora!1234abcd", map.conversationKey(nodeA)
        )

        LoRaNodeIdentityMap.adoptRawTextIntoContactThread = true
        assertEquals("on: the proved identity wins", keyA, map.conversationKey(nodeA))

        // …but only for a node that proved something.
        assertEquals("lora!0000beef", map.conversationKey(nodeB))
    }

    // =====================================================================
    // Two conflicting identities → back to the fallback, permanently
    // =====================================================================

    @Test
    fun `one node proving two identities becomes ambiguous`() {
        LoRaNodeIdentityMap.adoptRawTextIntoContactThread = true
        assertEquals(LoRaNodeIdentityMap.LearnResult.BOUND, map.bind(nodeA, keyA))
        assertEquals(LoRaNodeIdentityMap.LearnResult.CONFLICT, map.bind(nodeA, keyB))

        assertNull("ambiguous nodes route nowhere", map.publicKey(nodeA))
        assertTrue(map.binding(nodeA)!!.ambiguous)
        assertEquals("lora!1234abcd", map.conversationKey(nodeA))
        assertNull(map.node(keyA))
        assertNull(map.node(keyB))
    }

    /** Further evidence cannot un-ambiguate a node. iOS `:225-226`. */
    @Test
    fun `an ambiguous node stays ambiguous`() {
        map.bind(nodeA, keyA)
        map.bind(nodeA, keyB)
        assertEquals(LoRaNodeIdentityMap.LearnResult.IGNORED_AMBIGUOUS, map.bind(nodeA, keyA))
        assertEquals(LoRaNodeIdentityMap.LearnResult.IGNORED_AMBIGUOUS, map.bind(nodeA, keyB))
        assertNull(map.publicKey(nodeA))
    }

    /**
     * 🔧 The INVERSE direction, the one an attacker uses: a second node arriving
     * under an identity that is already bound. The old rule found nothing
     * suspicious about it and simply added a second valid route into a contact's
     * thread. BOTH ends are marked ambiguous. iOS `:235-256`.
     */
    @Test
    fun `a second node claiming a bound identity poisons both`() {
        LoRaNodeIdentityMap.adoptRawTextIntoContactThread = true
        assertEquals(LoRaNodeIdentityMap.LearnResult.BOUND, map.bind(nodeA, keyA))
        assertEquals(LoRaNodeIdentityMap.LearnResult.CONFLICT, map.bind(nodeB, keyA))

        assertNull("the victim's node stops routing too", map.publicKey(nodeA))
        assertNull("the impostor never routes", map.publicKey(nodeB))
        assertTrue(map.binding(nodeA)!!.ambiguous)
        assertTrue(map.binding(nodeB)!!.ambiguous)
        assertEquals("lora!1234abcd", map.conversationKey(nodeA))
        assertEquals("lora!0000beef", map.conversationKey(nodeB))
        assertNull(map.node(keyA))
    }

    @Test
    fun `two nodes with two different identities coexist`() {
        LoRaNodeIdentityMap.adoptRawTextIntoContactThread = true
        assertEquals(LoRaNodeIdentityMap.LearnResult.BOUND, map.bind(nodeA, keyA))
        assertEquals(LoRaNodeIdentityMap.LearnResult.BOUND, map.bind(nodeB, keyB))
        assertEquals(keyA, map.conversationKey(nodeA))
        assertEquals(keyB, map.conversationKey(nodeB))
        assertEquals(nodeA, map.node(keyA))
        assertEquals(nodeB, map.node(keyB))
    }

    /** The only way out of ambiguous, and the honest answer to "that isn't them". */
    @Test
    fun `forget clears ambiguity and allows a fresh binding`() {
        LoRaNodeIdentityMap.adoptRawTextIntoContactThread = true
        map.bind(nodeA, keyA)
        map.bind(nodeA, keyB)
        assertTrue(map.binding(nodeA)!!.ambiguous)

        map.forget(nodeA)
        assertNull(map.binding(nodeA))
        assertEquals(LoRaNodeIdentityMap.LearnResult.BOUND, map.bind(nodeA, keyB))
        assertEquals(keyB, map.conversationKey(nodeA))
    }

    @Test
    fun `removeAll empties the map and persists`() {
        map.bind(nodeA, keyA)
        map.bind(nodeB, keyB)
        assertEquals(2, map.count)
        map.removeAll()
        assertEquals(0, map.count)
        assertEquals(0, LoRaNodeIdentityMap(store).count)
    }

    // =====================================================================
    // Persistence
    // =====================================================================

    @Test
    fun `bindings survive a restart`() {
        LoRaNodeIdentityMap.adoptRawTextIntoContactThread = true
        map.bind(nodeA, keyA)
        map.bind(nodeB, keyB)

        // A brand new map over the same storage — the "next launch".
        val reloaded = LoRaNodeIdentityMap(store)
        assertEquals(2, reloaded.count)
        assertEquals(keyA, reloaded.publicKey(nodeA))
        assertEquals(keyB, reloaded.publicKey(nodeB))
        assertEquals(keyA, reloaded.conversationKey(nodeA))
        assertEquals(nodeA, reloaded.node(keyA))
    }

    @Test
    fun `ambiguity survives a restart`() {
        map.bind(nodeA, keyA)
        map.bind(nodeA, keyB)

        val reloaded = LoRaNodeIdentityMap(store)
        assertTrue(reloaded.binding(nodeA)!!.ambiguous)
        assertNull(reloaded.publicKey(nodeA))
        assertEquals("lora!1234abcd", reloaded.conversationKey(nodeA))
    }

    @Test
    fun `forget survives a restart`() {
        map.bind(nodeA, keyA)
        map.forget(nodeA)
        assertEquals(0, LoRaNodeIdentityMap(store).count)
    }

    /** UNCHANGED does not rewrite storage, but must not lose it either. */
    @Test
    fun `an unchanged bind keeps the persisted record`() {
        map.bind(nodeA, keyA)
        map.bind(nodeA, keyA)
        assertEquals(keyA, LoRaNodeIdentityMap(store).publicKey(nodeA))
    }

    @Test
    fun `a corrupt blob degrades to an empty map, not a crash`() {
        val broken = LoRaNodeIdentityMap(InMemoryLoRaIdentityStore("{not json"))
        assertEquals(0, broken.count)
        assertEquals("lora!1234abcd", broken.conversationKey(nodeA))
    }

    @Test
    fun `learnedAt is recorded`() {
        val before = System.currentTimeMillis()
        map.bind(nodeA, keyA)
        val at = map.binding(nodeA)!!.learnedAtMillis
        assertTrue("learnedAt must be a real timestamp", at >= before)
        assertNotEquals(0L, at)
        assertEquals(at, LoRaNodeIdentityMap(store).binding(nodeA)!!.learnedAtMillis)
    }

    // =====================================================================
    // Normalisation
    // =====================================================================

    @Test
    fun `normalizeKey folds base64url and padding`() {
        assertEquals("ab+cd/ef", LoRaNodeIdentityMap.normalizeKey("ab-cd_ef"))
        assertEquals("ab+cd/ef", LoRaNodeIdentityMap.normalizeKey("ab+cd/ef="))
        assertEquals("ab+cd/ef", LoRaNodeIdentityMap.normalizeKey("  ab+cd/ef==  "))
    }
}
