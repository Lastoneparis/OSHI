package com.oshi.messenger

import com.oshi.messenger.network.v2.V2Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * Locks the EXACT BYTES that Android signs for the linked-device routes
 * (`/api/web/sessions`, `/revoke`, `/revoke-all`) and every other V2 route.
 *
 * Why this test exists rather than "the signer returns three headers": the headers come
 * back populated no matter what string went into Ed25519. A canonical string with its last
 * two fields transposed, or with the query string left on the path, or with upper-case hex,
 * produces a perfectly well-formed `x-oshi-signature` that the server rejects as
 * `invalid-signature`. The only way to catch that before it reaches production is to assert
 * the bytes.
 *
 * INDEPENDENCE OF THE EXPECTED VALUES — the point of the whole exercise. Nothing here is
 * derived by calling [V2Signer] twice. Every constant below was produced OUTSIDE this
 * codebase with `printf … | shasum -a 256`:
 *
 *   printf ''                                             -> e3b0c442…52b855   (zero bytes)
 *   printf '{"sessionId":"web-7f3a","publicKey":"Ab+/c="}' -> 1d110b9e…91ab3c
 *   printf '""'                                           -> 12ae32cb…82e126
 *   printf '{}'                                            -> 44136fa3…aaff8a
 *   printf 'GET\n/api/web/sessions\ne3b0…b855\n1757808000000' -> 53de7c75…a9049f  (100 bytes)
 *
 * The last one is the load-bearing assertion: it hashes the WHOLE canonical string the
 * production helper built, so a wrong separator, a wrong field order or a stray character
 * anywhere in the 100 bytes changes it. It is the same construction the server performs at
 * `ServerVPS/v2/oshi_auth.js:247`.
 */
class V2SignerCanonicalStringTest {

    private companion object {
        /** A fixed instant, so the string under test is fully determined. */
        const val TS = "1757808000000"

        /** `shasum -a 256` of zero bytes — what EVERY GET must hash. */
        const val SHA_ZERO_BYTES = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        /** `shasum -a 256` of the revoke body below. */
        const val SHA_REVOKE_BODY = "1d110b9e757bd0060075549171540e94af0de1dd92485518882c5abb6d91ab3c"

        /** `shasum -a 256` of the 2-byte string `""` (the verify-first blob convention). */
        const val SHA_EMPTY_JSON_STRING = "12ae32cb1ec02d01eda3581b127c1fee3b0dc53572ed6baf239721a03d82e126"

        /** `shasum -a 256` of `{}` — what a naive "empty JSON object" body would hash to. */
        const val SHA_EMPTY_JSON_OBJECT = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"

        /** `shasum -a 256` of the complete canonical string for the session-list GET. */
        const val SHA_OF_WHOLE_GET_CANONICAL =
            "53de7c75c6e456620931df622516b6fe9e436d75bd7e9a94f00efae95da9049f"

        const val REVOKE_BODY = """{"sessionId":"web-7f3a","publicKey":"Ab+/c="}"""
    }

    /**
     * Deliberately NOT [V2Signer.sha256Hex]: this test must not validate the helper with the
     * helper. Straight JDK digest plus a local hex formatter.
     */
    private fun sha256HexIndependently(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val out = CharArray(digest.size * 2)
        val table = "0123456789abcdef"
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            out[i * 2] = table[v ushr 4]
            out[i * 2 + 1] = table[v and 0x0F]
        }
        return String(out)
    }

    @Test
    fun `a GET signs over the sha256 of ZERO bytes, not of empty JSON`() {
        val canonical = V2Signer.canonicalString("GET", "/api/web/sessions", ByteArray(0), TS)

        assertEquals(
            "GET\n/api/web/sessions\n$SHA_ZERO_BYTES\n$TS",
            canonical
        )
        // The two near-misses that would each look plausible and each 401:
        assertNotEquals(
            "a GET must not hash the 2-byte string \"\"",
            "GET\n/api/web/sessions\n$SHA_EMPTY_JSON_STRING\n$TS",
            canonical
        )
        assertNotEquals(
            "a GET must not hash {} — which is what an express body-parser default yields",
            "GET\n/api/web/sessions\n$SHA_EMPTY_JSON_OBJECT\n$TS",
            canonical
        )
    }

    @Test
    fun `the exact signed bytes of the session-list GET match an externally computed digest`() {
        val canonical = V2Signer.canonicalString("GET", "/api/web/sessions", ByteArray(0), TS)
        val bytes = canonical.toByteArray(Charsets.UTF_8)

        // Length and separator positions, so a swapped pair of fields of equal length could
        // not slip through the digest check by coincidence.
        assertEquals("canonical string length", 100, bytes.size)
        assertEquals("exactly three separators", 3, canonical.count { it == '\n' })

        // Externally computed with shasum -a 256 over the same 100 bytes.
        assertEquals(SHA_OF_WHOLE_GET_CANONICAL, sha256HexIndependently(canonical))

        // And the bytes themselves, spelled out field by field.
        val expected = buildString {
            append("GET"); append('\n')
            append("/api/web/sessions"); append('\n')
            append(SHA_ZERO_BYTES); append('\n')
            append(TS)
        }.toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun `the revoke POST signs the exact body bytes that are sent`() {
        val body = REVOKE_BODY.toByteArray(Charsets.UTF_8)
        val canonical = V2Signer.canonicalString("POST", "/api/web/sessions/revoke", body, TS)

        assertEquals(
            "POST\n/api/web/sessions/revoke\n$SHA_REVOKE_BODY\n$TS",
            canonical
        )
        // A revoke-all differs only by path, and the signature must therefore differ too —
        // otherwise a captured single-revoke signature would authorise wiping every device.
        assertNotEquals(
            V2Signer.canonicalString("POST", "/api/web/sessions/revoke-all", body, TS),
            canonical
        )
    }

    @Test
    fun `the query string is excluded from the signed path`() {
        // The real GET URL carries ?publicKey=<base64>. The server signs
        // (req.originalUrl || req.url).split('?')[0] — oshi_auth.js:244 — so the client must
        // sign the bare path. Signing the full URL is the single most likely way to get this
        // wrong, because the URL is what the caller has in hand.
        val signed = V2Signer.canonicalString("GET", "/api/web/sessions", ByteArray(0), TS)
        val withQuery = V2Signer.canonicalString(
            "GET", "/api/web/sessions?publicKey=Ab%2Bc%3D", ByteArray(0), TS
        )
        assertNotEquals(withQuery, signed)
        assertEquals("GET\n/api/web/sessions\n$SHA_ZERO_BYTES\n$TS", signed)
    }

    @Test
    fun `TIMESTAMP is the LAST field and BODYHASH the third`() {
        // The mutation this test was built to catch: swapping the last two fields. Both are
        // ASCII-safe decimal/hex, so the resulting string is well formed and the 401 that
        // follows reads like clock skew rather than a field-order bug.
        val canonical = V2Signer.canonicalString("POST", "/v2/sync/ABC", "x".toByteArray(), TS)
        val fields = canonical.split("\n")
        assertEquals(4, fields.size)
        assertEquals("POST", fields[0])
        assertEquals("/v2/sync/ABC", fields[1])
        assertEquals("third field is the 64-char lowercase body hash", 64, fields[2].length)
        assertEquals(fields[2], fields[2].lowercase())
        assertEquals("fourth and last field is the millisecond timestamp", TS, fields[3])

        // Explicitly reject the transposed form.
        assertNotEquals("POST\n/v2/sync/ABC\n$TS\n${fields[2]}", canonical)
    }

    @Test
    fun `the hash is lowercase hex — upper case is a silent 401`() {
        val canonical = V2Signer.canonicalString("GET", "/v2/sync/ABC/head", ByteArray(0), TS)
        assertEquals("GET\n/v2/sync/ABC/head\n$SHA_ZERO_BYTES\n$TS", canonical)
        assertNotEquals(
            "GET\n/v2/sync/ABC/head\n${SHA_ZERO_BYTES.uppercase()}\n$TS",
            canonical
        )
    }
}
