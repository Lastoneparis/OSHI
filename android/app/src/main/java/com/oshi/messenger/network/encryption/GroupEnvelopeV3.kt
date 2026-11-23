package com.oshi.messenger.network.encryption

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Group messages with no group key — the Android half of CV-002.
 *
 * ## What was wrong
 *
 * [com.oshi.messenger.service.GroupManager.deriveGroupKey] computes
 * `SHA256(groupId.uppercase() + "oshi-group-envelope-key")`, and iOS computes
 * exactly the same thing. That is a pure function of the group id and a constant
 * compiled into every copy of both apps: there is no secret, nothing a member
 * holds that a stranger does not. Group ids travel in cleartext in the
 * `group_update` objects pinned to public IPFS, so collecting the pins was enough
 * to derive the key and read the group.
 *
 * ## The format
 *
 * Byte-for-byte compatible with `OSHI/GroupEnvelopeV3.swift`. The body is
 * encrypted ONCE under a fresh random content key, and only that 32-byte key is
 * wrapped per member over that member's 1:1 ratchet:
 *
 * ```
 * { "v": 3,
 *   "salt":  base64(16 random bytes),
 *   "body":  base64(AES-GCM(contentKey, payload) ‖ tag),
 *   "nonce": base64(12-byte GCM nonce),
 *   "wraps": [ { "h": base64(SHA256(memberKey ‖ salt)[0..16]),
 *                "k": base64(utf8(JSON IOSRatchetMessage)) } ] }
 * ```
 *
 * One IPFS pin per message, exactly as before, so the offline/LoRa path is not
 * made more expensive. Because the wraps ride the 1:1 ratchet they inherit its
 * forward secrecy.
 *
 * ## Two deliberate choices
 *
 * **`java.util.Base64`, not `android.util.Base64`.** `minSdk` is 26, so it is
 * available, and it keeps this file runnable in a plain JVM unit test — the same
 * rule `GroupWireFormatTest` already states for the group wire format. Both
 * produce standard RFC 4648 with no line wrapping, so the bytes on the wire are
 * identical either way.
 *
 * **The ratchet is injected.** [WrapEncryptor] / [WrapDecryptor] keep this file
 * free of session storage, so the tests can drive both ends of a conversation in
 * one process instead of asserting against a stub that cannot desynchronise.
 */
object GroupEnvelopeV3 {

    /**
     * The wire version, **4** even though the object is named V3.
     *
     * "V3" is the third GROUP envelope design. The `v` field is a namespace
     * shared with the 1:1 IPFS envelope, where `v: 3` was already taken for
     * `{v, envelope, nonce, hint, from, senderPublicKey}` (see `VPSClient`).
     * Two shapes under one number only work while every decoder stays strict,
     * so the group envelope takes the next free number instead.
     */
    const val VERSION = 4

    /** Matches `PostQuantumKEM`-era sizing on iOS: AES-256-GCM, 96-bit nonce, 128-bit tag. */
    private const val CONTENT_KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val SALT_BYTES = 16
    private const val HINT_BYTES = 16

    /** Mirrors `GroupEnvelopeV3.Failure` in Swift, including why they are distinct. */
    sealed class Failure(message: String) : Exception(message) {
        /** No wrap addressed to us: not a member, or sent before we joined. */
        object NoWrapForUs : Failure("no wrap addressed to this member")

        /**
         * The wrap was found but the 1:1 ratchet could not open it. Almost always
         * session desync — the caller must heal, not retry the same bytes.
         */
        object WrapUndecryptable : Failure("1:1 ratchet could not open the wrap")

        object Malformed : Failure("envelope is malformed")
    }

    /** Encrypts [contentKeyB64] to [memberPublicKey], returning the ratchet-message JSON bytes. */
    fun interface WrapEncryptor {
        fun encrypt(memberPublicKey: String, contentKeyB64: String): ByteArray
    }

    /** Opens a ratchet-message JSON produced by the sender, returning its plaintext. */
    fun interface WrapDecryptor {
        fun decrypt(wrappedRatchetMessage: ByteArray): String
    }

    private val b64 = Base64.getEncoder()
    private val b64d = Base64.getDecoder()
    private val rng = SecureRandom()

    // ---------------------------------------------------------------- hints

    /**
     * `base64(SHA256(memberKey ‖ salt)[0..16])`.
     *
     * A wrap must be findable by its recipient without being readable by anyone
     * else. The salt is fresh per envelope, so the same member produces a
     * different hint in every message: a pin does not become a membership list,
     * and two pins cannot be linked to the same group. The old format leaked the
     * first 4 characters of the group id in the clear; this leaks the member
     * count and nothing else.
     */
    fun hint(memberPublicKey: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(memberPublicKey.toByteArray(Charsets.UTF_8))
        md.update(salt)
        return b64.encodeToString(md.digest().copyOf(HINT_BYTES))
    }

    // ---------------------------------------------------------------- seal

    /**
     * @param members every member the envelope must be readable by.
     * @return the envelope JSON, ready to pin, or to hand to the mesh.
     */
    fun seal(payload: ByteArray, members: List<String>, encryptWrap: WrapEncryptor): JSONObject {
        val contentKey = ByteArray(CONTENT_KEY_BYTES).also { rng.nextBytes(it) }
        val contentKeyB64 = b64.encodeToString(contentKey)

        val nonce = ByteArray(NONCE_BYTES).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(contentKey, "AES"),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        // Java appends the tag to the ciphertext, which is exactly the
        // `ciphertext ‖ tag` layout CryptoKit is given on the iOS side.
        val body = cipher.doFinal(payload)

        val salt = ByteArray(SALT_BYTES).also { rng.nextBytes(it) }

        // A member whose ratchet we cannot reach is SKIPPED, not fatal: one
        // unreachable member must not stop the message for everyone else.
        val wraps = JSONArray()
        for (member in members) {
            try {
                val wrapped = encryptWrap.encrypt(member, contentKeyB64)
                wraps.put(
                    JSONObject()
                        .put("h", hint(member, salt))
                        .put("k", b64.encodeToString(wrapped))
                )
            } catch (e: Exception) {
                // Logged by the caller; swallowing here keeps one bad session
                // from silencing the whole group.
            }
        }

        return JSONObject()
            .put("v", VERSION)
            .put("salt", b64.encodeToString(salt))
            .put("body", b64.encodeToString(body))
            .put("nonce", b64.encodeToString(nonce))
            .put("wraps", wraps)
    }

    // ---------------------------------------------------------------- open

    fun open(envelope: JSONObject, myPublicKey: String, decryptWrap: WrapDecryptor): ByteArray {
        if (envelope.optInt("v", -1) != VERSION) throw Failure.Malformed

        val salt = decodeOrNull(envelope.optString("salt")) ?: throw Failure.Malformed
        val body = decodeOrNull(envelope.optString("body")) ?: throw Failure.Malformed
        val nonce = decodeOrNull(envelope.optString("nonce")) ?: throw Failure.Malformed
        if (body.size < TAG_BYTES || nonce.size != NONCE_BYTES) throw Failure.Malformed

        val mine = hint(myPublicKey, salt)
        val wraps = envelope.optJSONArray("wraps") ?: throw Failure.Malformed
        var wrapped: ByteArray? = null
        for (i in 0 until wraps.length()) {
            val w = wraps.optJSONObject(i) ?: continue
            if (w.optString("h") == mine) {
                wrapped = decodeOrNull(w.optString("k"))
                break
            }
        }
        val wrapBytes = wrapped ?: throw Failure.NoWrapForUs

        val contentKeyB64 = try {
            decryptWrap.decrypt(wrapBytes)
        } catch (e: Exception) {
            // Deliberately one error: the caller's only correct response is to
            // heal the 1:1 session, whatever the ratchet's internal complaint was.
            throw Failure.WrapUndecryptable
        }
        val contentKey = decodeOrNull(contentKeyB64) ?: throw Failure.Malformed
        if (contentKey.size != CONTENT_KEY_BYTES) throw Failure.Malformed

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(contentKey, "AES"),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        return cipher.doFinal(body)
    }

    /**
     * The budget one message has on the radio: LoRa chunk (180) × 12 frames.
     * Duplicated as a constant so this file needs no transport dependency.
     */
    const val LORA_MESSAGE_CEILING = 180 * 12

    /**
     * Seal for a transport that refuses large messages.
     *
     * A combined envelope carries one wrap per member (~545 bytes each), so it
     * grows with the group while the radio ceiling does not: a group of 3 fits in
     * ~1798 bytes and a group of 5 needs ~2862 and is refused. Splitting the TEXT
     * cannot rescue that — above a handful of members the WRAPS alone exceed the
     * budget, whatever the body says.
     *
     * So when the combined envelope does not fit, this returns one envelope PER
     * RECIPIENT (body + that member's single wrap, ~705 bytes). It costs airtime,
     * and on a lossy radio it buys something back: losing one member's copy does
     * not cost everyone the message.
     *
     * Each copy has its own content key, so the bodies differ and the copies
     * cannot be correlated by ciphertext.
     */
    fun sealFitting(
        payload: ByteArray,
        members: List<String>,
        maxBytes: Int,
        encryptWrap: WrapEncryptor
    ): List<JSONObject> {
        val combined = seal(payload, members, encryptWrap)
        if (combined.getJSONArray("wraps").length() == 0) return emptyList()
        if (encodedSize(combined) <= maxBytes) return listOf(combined)

        val perRecipient = mutableListOf<JSONObject>()
        for (member in members) {
            val one = seal(payload, listOf(member), encryptWrap)
            if (one.getJSONArray("wraps").length() == 0) continue
            perRecipient.add(one)
        }
        return perRecipient
    }

    /** Bytes this envelope occupies once encoded — what the transport measures. */
    fun encodedSize(envelope: JSONObject): Int =
        envelope.toString().toByteArray(Charsets.UTF_8).size

    /** True when [json] is a v3 envelope rather than the legacy `{v,envelope,hint,nonce}` shape. */
    fun isV3(json: JSONObject): Boolean =
        json.optInt("v", -1) == VERSION && json.has("wraps")

    private fun decodeOrNull(s: String?): ByteArray? =
        if (s.isNullOrEmpty()) null else try { b64d.decode(s) } catch (e: Exception) { null }

    // ---------------------------------------------------- when it may be used

    /**
     * v3 is produced only when EVERY member can read it.
     *
     * iOS checks `envelope.v == currentVersion` with a strict equality, and so
     * does Android, so a client that predates this format DROPS a v3 envelope
     * rather than mis-decrypting it. Safe, but still a member who silently stops
     * receiving group messages — so one un-updated member keeps the whole group
     * on the old format until they catch up. Absent capability means "cannot",
     * never "probably".
     */
    fun canUse(members: List<String>, supportsV3: (String) -> Boolean): Boolean =
        members.isNotEmpty() && members.all(supportsV3)
}
