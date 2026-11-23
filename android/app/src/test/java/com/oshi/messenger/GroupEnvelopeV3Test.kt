package com.oshi.messenger

import com.oshi.messenger.network.encryption.GroupEnvelopeV3
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CV-002 on Android — the group key that contained no secret.
 *
 * The reference is the Swift source, not the Android code:
 *   - `GroupEnvelopeV3.seal` / `.open` / `.hint`   OSHI/GroupEnvelopeV3.swift
 *
 * The most important test here is [hintMatchesTheCrossPlatformVector]. Everything
 * else could pass on both platforms while the two disagreed about a single byte,
 * and the symptom would be an Android member who silently never receives a group
 * message — the exact failure the old `deriveGroupKey` comment ("iOS compatible,
 * normalize to uppercase") exists because someone already hit once.
 *
 * The vector was produced by a THIRD implementation (Python's hashlib), not by
 * either app, so it cannot inherit a shared mistake.
 *
 * No Android runtime: `android.util.Base64` is deliberately kept out of
 * `GroupEnvelopeV3`, the same rule `GroupWireFormatTest` states.
 */
class GroupEnvelopeV3Test {

    private val b64 = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    private val alice = "ALICEaliceALICEaliceALICEaliceALICEalice1a="
    private val bob = "BOBbobBOBbobBOBbobBOBbobBOBbobBOBbobBOBbo2b="
    private val carol = "CAROLcarolCAROLcarolCAROLcarolCAROLcarol3c="
    private val mallory = "MALLORYmalloryMALLORYmalloryMALLORYmall4m="

    // ------------------------------------------------------------ interop

    /**
     * Locks the hint to bytes computed OUTSIDE both apps. If iOS and Android ever
     * drift on the input encoding, the salt order, or the truncation length, this
     * fails here rather than as an Android member who receives nothing.
     */
    @Test
    fun hintMatchesTheCrossPlatformVector() {
        val member = "MEMBER_TEST_KEY_aaaaaaaaaaaaaaaaaaaaaaaa="
        val salt = ByteArray(16) { 0x2a }
        assertEquals(
            "l'indice de destinataire a divergé d'iOS — un membre Android ne recevrait plus rien",
            "+7zbjg1FmAn8coemimzAzg==",
            GroupEnvelopeV3.hint(member, salt)
        )
    }

    /** The salt is base64 of exactly 16 bytes, as iOS writes it. */
    @Test
    fun saltIsSixteenBytes() {
        val env = seal("x".toByteArray(), listOf(bob))
        assertEquals(16, b64d.decode(env.getString("salt")).size)
        assertEquals(12, b64d.decode(env.getString("nonce")).size)
        assertEquals(GroupEnvelopeV3.VERSION, env.getInt("v"))
    }

    // -------------------------------------------------- a fake, honest ratchet
    //
    // A deterministic stand-in for the 1:1 ratchet: enough to prove the envelope
    // logic, and it CANNOT decrypt what it did not encrypt, so the desync test
    // below is real rather than staged.

    private class FakeRatchet(private val secret: ByteArray) {
        private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret), "AES")
        fun encrypt(plaintext: String): ByteArray {
            val nonce = ByteArray(12) { 7 }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            return c.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        }
        fun decrypt(bytes: ByteArray): String {
            val nonce = ByteArray(12) { 7 }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            return String(c.doFinal(bytes), Charsets.UTF_8)
        }
    }

    private fun ratchet(member: String) = FakeRatchet("session:$member".toByteArray())

    private fun seal(payload: ByteArray, members: List<String>): JSONObject =
        GroupEnvelopeV3.seal(payload, members) { member, contentKeyB64 ->
            ratchet(member).encrypt(contentKeyB64)
        }

    private fun open(env: JSONObject, member: String, using: FakeRatchet = ratchet(member)): ByteArray =
        GroupEnvelopeV3.open(env, member) { using.decrypt(it) }

    // ------------------------------------------------------------ it works

    @Test
    fun everyMemberCanReadTheMessage() {
        val payload = "le message du groupe".toByteArray()
        val env = seal(payload, listOf(bob, carol))
        assertEquals(2, env.getJSONArray("wraps").length())
        assertArrayEquals(payload, open(env, bob))
        assertArrayEquals(payload, open(env, carol))
    }

    /**
     * The cost argument, pinned so it cannot regress into per-member fan-out:
     * the body is stored once and a member adds only a wrap. This is what keeps
     * one IPFS pin per message, and what keeps the envelope inside the LoRa
     * budget for small groups.
     */
    @Test
    fun addingAMemberAddsAWrapNotACopyOfTheBody() {
        val big = ByteArray(4096) { 'A'.code.toByte() }
        val one = seal(big, listOf(bob))
        val two = seal(big, listOf(bob, carol))
        assertEquals(
            "le corps est recopié par membre — on est revenu au fan-out",
            one.getString("body").length,
            two.getString("body").length
        )
    }

    // ------------------------------------------ what must no longer be possible

    /** The heart of CV-002: knowing the group id used to BE the key. */
    @Test
    fun theOldDerivedGroupKeyOpensNothing() {
        val groupId = "8B1D4C2E-0000-4000-8000-000000000001"
        val env = seal("secret".toByteArray(), listOf(bob))

        val md = MessageDigest.getInstance("SHA-256")
        md.update(groupId.uppercase().toByteArray(Charsets.UTF_8))
        md.update("oshi-group-envelope-key".toByteArray(Charsets.UTF_8))
        val legacyKey = md.digest()

        val body = b64d.decode(env.getString("body"))
        val nonce = b64d.decode(env.getString("nonce"))
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(legacyKey, "AES"), GCMParameterSpec(128, nonce))
            c.doFinal(body)
            fail("l'ancienne clé dérivée du groupId ouvre encore le message")
        } catch (expected: Exception) {
            // Correct: the derived key is worthless now.
        }

        // And the group id must not travel in the envelope, nor a prefix of it.
        val json = env.toString()
        assertFalse("le groupId voyage en clair", json.contains(groupId))
        assertFalse(json.lowercase().contains(groupId.take(4).lowercase()))
    }

    @Test
    fun aNonMemberFindsNoWrap() {
        val env = seal("hello".toByteArray(), listOf(bob))
        try {
            open(env, mallory)
            fail("un non-membre a pu ouvrir l'enveloppe")
        } catch (e: GroupEnvelopeV3.Failure.NoWrapForUs) {
            // expected
        }
    }

    @Test
    fun hintsAreUnlinkableAcrossMessages() {
        val first = seal("un".toByteArray(), listOf(bob))
        val second = seal("deux".toByteArray(), listOf(bob))
        assertNotEquals(first.getString("salt"), second.getString("salt"))
        assertNotEquals(
            "le même membre produit le même indice deux fois: les épingles sont liables",
            first.getJSONArray("wraps").getJSONObject(0).getString("h"),
            second.getJSONArray("wraps").getJSONObject(0).getString("h")
        )
    }

    @Test
    fun aTamperedBodyIsRejected() {
        val env = seal("intact".toByteArray(), listOf(bob))
        val body = b64d.decode(env.getString("body"))
        body[0] = (body[0].toInt() xor 1).toByte()
        env.put("body", b64.encodeToString(body))
        try {
            open(env, bob)
            fail("un corps altéré a été accepté")
        } catch (expected: Exception) {
            // any failure is fine; silently returning plaintext is not
        }
    }

    // ------------------------------------------------------------ desync

    /**
     * A member whose 1:1 ratchet has drifted must fail in a way the caller can
     * act on — never a silent empty message.
     */
    @Test
    fun aDesyncedMemberSurfacesAsWrapUndecryptable() {
        val env = seal("coucou".toByteArray(), listOf(bob))
        val wrongSession = FakeRatchet("a completely different session".toByteArray())
        try {
            open(env, bob, using = wrongSession)
            fail("un ratchet désynchronisé a quand même ouvert le message")
        } catch (e: GroupEnvelopeV3.Failure.WrapUndecryptable) {
            // expected — the caller heals the 1:1 session
        }
    }

    @Test
    fun aMemberWithNoSessionIsSkippedRatherThanFailingTheSend() {
        val env = GroupEnvelopeV3.seal("quand même".toByteArray(), listOf(bob, carol)) { member, key ->
            if (member == carol) throw IllegalStateException("no session")
            ratchet(member).encrypt(key)
        }
        assertEquals(1, env.getJSONArray("wraps").length())
        assertArrayEquals("quand même".toByteArray(), open(env, bob))
    }

    // ------------------------------------------------------------ the gate

    @Test
    fun aGroupStaysOnTheOldFormatUntilEveryMemberCanReadV3() {
        val capable = setOf(bob)
        assertFalse(
            "un seul membre non mis à jour doit garder TOUT le groupe sur l'ancien format",
            GroupEnvelopeV3.canUse(listOf(bob, carol)) { it in capable }
        )
        assertTrue(GroupEnvelopeV3.canUse(listOf(bob)) { it in capable })
        assertFalse(GroupEnvelopeV3.canUse(emptyList()) { true })
    }

    /**
     * The wire version must NOT be 3.
     *
     * `v: 3` is already the 1:1 IPFS envelope
     * (`{v, envelope, nonce, hint, from, senderPublicKey}` — see `VPSClient` and
     * iOS `uploadToIPFS`), and both shapes share one `v` namespace. They are
     * currently told apart only because every decoder happens to be strict,
     * which stops being true the day someone adds a field or decodes leniently.
     */
    @Test
    fun theWireVersionDoesNotCollideWithTheOneToOneEnvelope() {
        assertEquals(4, GroupEnvelopeV3.VERSION)
        assertEquals(4, seal("x".toByteArray(), listOf(bob)).getInt("v"))
    }

    /** A 1:1 v3 envelope must never be mistaken for a group one. */
    @Test
    fun aOneToOneV3EnvelopeIsNotSeenAsAGroupEnvelope() {
        val oneToOne = JSONObject()
            .put("v", 3).put("envelope", "abc").put("nonce", "def")
            .put("hint", "ABCD").put("from", "12345678")
            .put("senderPublicKey", "AAAA")
        assertFalse(GroupEnvelopeV3.isV3(oneToOne))
    }

    @Test
    fun v3IsDistinguishableFromTheLegacyEnvelope() {
        val legacy = JSONObject()
            .put("v", 2).put("envelope", "x").put("hint", "ABCD").put("nonce", "y")
        assertFalse(GroupEnvelopeV3.isV3(legacy))
        assertTrue(GroupEnvelopeV3.isV3(seal("x".toByteArray(), listOf(bob))))
    }
}
