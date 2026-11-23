package com.oshi.messenger.network.bot

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * __BOT_E2E_2026_09_23__ Recipient side of "bot-seal-v1"
 * (`ServerPatches/bot_e2e/BOT_SEAL_SPEC.md` §2, reference `patched/bot_seal.js`).
 *
 * The server seals a bot message to every member's identity X25519 key and queues only
 * the ciphertext; the outer JSON carries ids and a placeholder, never user content.
 * Pure Kotlin (BouncyCastle X25519 + HKDF, javax AES-GCM, java.util.Base64) so it runs
 * unchanged in JVM unit tests. Never logs.
 */
object BotSealedEnvelope {

    const val VERSION = "bot-seal-v1"
    private const val WRAP_LEN = 32 + 12 + 32 + 16

    /** Who sealed an envelope, for the honest caption in the UI (§3). */
    enum class SealKind(val label: String) {
        /** Sealed by the OSHI server on arrival (`sealedBy == "server"` / `e2e == false`). */
        SERVER("server"),
        /** Sealed by the bot itself; the server never saw the plaintext. */
        BOT("bot"),
        /** Legacy plaintext envelope. */
        NONE("none");

        companion object {
            fun fromLabel(s: String?): SealKind? = values().firstOrNull { it.label == s }
        }
    }

    class OpenException(val reason: String) : Exception(reason)

    /** §1 detection: `v == "bot-seal-v1"` and `sealed` is an object. */
    fun isSealed(json: JSONObject): Boolean =
        json.optString("v", "") == VERSION && json.optJSONObject("sealed") != null

    /** `kid = lowercase hex(SHA-256(pub))[0..16]`. */
    fun kid(pub: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(pub)
            .joinToString("") { "%02x".format(it) }
            .substring(0, 16)

    /**
     * Seal kind of an (opened) inner JSON, falling back on the outer `e2e` flag.
     * Legacy (unsealed) envelopes are [SealKind.NONE].
     */
    fun sealKind(outer: JSONObject, inner: JSONObject?): SealKind {
        if (!isSealed(outer)) return SealKind.NONE
        return when (inner?.optString("sealedBy", "")) {
            "bot" -> SealKind.BOT
            "server" -> SealKind.SERVER
            else -> if (outer.optBoolean("e2e", false)) SealKind.BOT else SealKind.SERVER
        }
    }

    /**
     * Open a sealed outer envelope (§2). [messageId] is the OUTER `messageId` string
     * exactly as received (it is bound into both AADs). Throws [OpenException] with a
     * content-free reason on any failure: no wrap for this key, bad tag, bad JSON,
     * inner id mismatch.
     */
    fun open(
        outer: JSONObject,
        messageId: String,
        identityPrivateKey: ByteArray,
        identityPublicKey: ByteArray? = null
    ): JSONObject {
        if (!isSealed(outer)) throw OpenException("not $VERSION")
        if (identityPrivateKey.size != 32) throw OpenException("bad private key")
        val sealed = outer.getJSONObject("sealed")
        if (sealed.optString("v", "") != VERSION) throw OpenException("sealed.v")

        val priv = X25519PrivateKeyParameters(identityPrivateKey, 0)
        val pub = identityPublicKey ?: priv.generatePublicKey().encoded
        val kid = kid(pub)

        val wraps = sealed.optJSONObject("wraps") ?: throw OpenException("no wraps")
        val wB64 = wraps.optString(kid, "")
        if (wB64.isEmpty()) throw OpenException("no wrap for this key")
        val w = b64(wB64) ?: throw OpenException("wrap base64")
        if (w.size != WRAP_LEN) throw OpenException("bad wrap length")
        val epk = w.copyOfRange(0, 32)

        val shared = ByteArray(32)
        try {
            val agreement = X25519Agreement()
            agreement.init(priv)
            agreement.calculateAgreement(X25519PublicKeyParameters(epk, 0), shared, 0)
        } catch (e: Exception) {
            throw OpenException("x25519")
        }
        val k = ByteArray(32)
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(shared, epk + pub, VERSION.toByteArray(Charsets.UTF_8)))
            generateBytes(k, 0, 32)
        }
        shared.fill(0)

        val contentKey = gcmOpen(k, w.copyOfRange(32, w.size), "$VERSION|wrap|$messageId|$kid")
            ?: throw OpenException("wrap tag")
        k.fill(0)
        if (contentKey.size != 32) throw OpenException("bad content key")

        val body = b64(sealed.optString("body", "")) ?: throw OpenException("body base64")
        val pt = gcmOpen(contentKey, body, "$VERSION|body|$messageId")
            ?: throw OpenException("body tag")
        contentKey.fill(0)

        val inner = try {
            JSONObject(String(pt, Charsets.UTF_8))
        } catch (e: Exception) {
            throw OpenException("inner json")
        }

        // §2.7 — the inner ids must match the outer ones (case-insensitive).
        if (!inner.optString("messageId", "").equals(messageId, ignoreCase = true)) {
            throw OpenException("messageId mismatch")
        }
        if (outer.optString("type", "bot_message") == "bot_message" &&
            !inner.optString("groupId", "").equals(outer.optString("groupId", ""), ignoreCase = true)
        ) {
            throw OpenException("groupId mismatch")
        }
        return inner
    }

    private fun b64(s: String): ByteArray? =
        if (s.isEmpty()) null else try { java.util.Base64.getDecoder().decode(s) } catch (e: Exception) { null }

    /** `iv(12) || ct || tag(16)`; null when the tag does not verify. */
    private fun gcmOpen(key: ByteArray, blob: ByteArray, aad: String): ByteArray? {
        if (blob.size < 28) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
            c.updateAAD(aad.toByteArray(Charsets.UTF_8))
            c.doFinal(blob, 12, blob.size - 12)
        } catch (e: Exception) {
            null
        }
    }
}
