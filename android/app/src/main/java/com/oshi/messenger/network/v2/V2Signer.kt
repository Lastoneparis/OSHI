package com.oshi.messenger.network.v2

import android.util.Base64
import com.oshi.messenger.network.encryption.CryptoManager
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Request signer for the OSHI V2 transport, byte-compatible with the iOS
 * `V2Client.makeRequest`/`signRequest` convention (V2Client.swift:753-795,
 * V2ClientModels.swift:373-378).
 *
 * Canonical string, three literal '\n', UTF-8:
 *     METHOD\nPATH\nBODYHASH\nTIMESTAMP
 *  - METHOD    upper-case HTTP verb
 *  - PATH      percent-encoded path, QUERY EXCLUDED; identity segments encoded
 *              allowing only alphanumerics (every + / = of a base64 key → %XX)
 *  - BODYHASH  lowercase-hex SHA-256 of the exact body-to-hash bytes
 *  - TIMESTAMP milliseconds since epoch, decimal string (server 30 s window)
 * Signature = Ed25519 over utf8(canonical), STANDARD padded base64 (not url).
 *
 * There are TWO identities:
 *  - signing key = Ed25519  → header x-oshi-signing-pubkey, signs the request
 *  - userKey     = X25519 identity (the contact address) → header x-oshi-user,
 *                  added only on blob/account/sync calls.
 */
@Singleton
class V2Signer @Inject constructor(
    private val cryptoManager: CryptoManager,
) {
    /**
     * Build the signed headers for a V2 request.
     *
     * @param method HTTP verb (e.g. "DELETE").
     * @param path   already percent-encoded path, no query (e.g. "/v2/account").
     *               Use [encodeIdentity] for identity-in-path routes.
     * @param bodyToHash the EXACT bytes to hash — see the four cases in the spec:
     *   JSON POST → the sent bytes; commit/GET → 0 bytes; verify-first
     *   blob/account (chunk PUT+GET, status, DELETE) → the 2-byte string `""`.
     *   Never re-serialize between hashing and sending.
     * @param withUserHeader add x-oshi-user (base64 X25519 identity) — true for
     *   blob/account/sync.
     */
    fun sign(
        method: String,
        path: String,
        bodyToHash: ByteArray,
        withUserHeader: Boolean = false,
    ): Map<String, String> {
        val timestamp = System.currentTimeMillis().toString()
        val canonical = canonicalString(method, path, bodyToHash, timestamp)
        val signature = Base64.encodeToString(
            cryptoManager.sign(canonical.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
        )
        val signingPubKey = Base64.encodeToString(cryptoManager.getSigningPublicKey(), Base64.NO_WRAP)
        val headers = linkedMapOf(
            "x-oshi-signature" to signature,
            "x-oshi-timestamp" to timestamp,
            "x-oshi-signing-pubkey" to signingPubKey,
        )
        if (withUserHeader) headers["x-oshi-user"] = userKey()
        return headers
    }

    /** The base64 X25519 identity public key (the contact address / relay from-to). */
    fun userKey(): String = cryptoManager.getPublicKeyString()

    /** The 2-byte body-to-hash used by the verify-first blob/account routes. */
    fun emptyJsonStringBody(): ByteArray = "\"\"".toByteArray(Charsets.UTF_8)

    /** Instance passthrough to [encodeIdentity]. */
    fun encodeIdentity(identity: String): String = Companion.encodeIdentity(identity)

    companion object {
        /**
         * Percent-encode an identity path segment exactly like iOS `encodeIdentity`
         * (V2Client.swift:734-735): only ASCII alphanumerics pass through, every other
         * byte (incl. the base64 `+ / =`) becomes upper-case %XX. Pure + tested — the
         * whole V2 auth breaks if this diverges from iOS on identity-in-path routes.
         */
        fun encodeIdentity(identity: String): String {
            val sb = StringBuilder(identity.length * 3)
            for (b in identity.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                if (c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%').append("%02X".format(c))
                }
            }
            return sb.toString()
        }

        /**
         * The exact bytes that get signed: `METHOD\nPATH\nSHA256HEX(body)\nTIMESTAMP`.
         *
         * Split out of [sign] so a plain JVM test can assert the STRING, not merely that
         * signing returned three headers. [sign] reads the clock and reaches into the
         * Keystore, so the only thing a test could otherwise check is that bytes came back
         * — and a canonical string with its last two fields transposed comes back just as
         * happily. The server (`oshi_auth.js:247`) builds this same string; every field is
         * load-bearing:
         *  - PATH excludes the query string (`oshi_auth.js:244` splits on '?').
         *  - BODYHASH is LOWERCASE hex of the EXACT bytes sent; a GET hashes ZERO bytes
         *    (sha256 = e3b0c442…), which is NOT the hash of "{}" nor of the 2-byte `""`
         *    the verify-first blob routes use.
         *  - TIMESTAMP is epoch MILLISECONDS as a decimal string, and it is LAST. Swapping
         *    it with BODYHASH yields a well-formed string and a 401 `invalid-signature`.
         */
        fun canonicalString(
            method: String,
            path: String,
            bodyToHash: ByteArray,
            timestamp: String,
        ): String = "$method\n$path\n${sha256Hex(bodyToHash)}\n$timestamp"

        /** Lowercase-hex SHA-256, matching the iOS BODYHASH format. */
        fun sha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(data)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) sb.append("%02x".format(b.toInt() and 0xFF))
            return sb.toString()
        }
    }
}
