package com.oshi.messenger

import com.oshi.messenger.network.v2.V2Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the two pure, parity-critical pieces of the V2 signing seam against the
 * iOS convention (V2Client.swift). If either diverges, every identity-in-path V2
 * request (keys/messages/blobs) fails server auth.
 */
class V2SignerTest {

    @Test
    fun `encodeIdentity passes alphanumerics through untouched`() {
        assertEquals("abcXYZ0189", V2Signer.encodeIdentity("abcXYZ0189"))
    }

    @Test
    fun `encodeIdentity percent-encodes base64 punctuation like iOS`() {
        // Only alphanumerics survive; +, /, = of a base64 key must become %2B/%2F/%3D.
        assertEquals("a%2Bb%2Fc%3D", V2Signer.encodeIdentity("a+b/c="))
        // A realistic base64url-ish key mixes -, _ too — both non-alphanumeric.
        assertEquals("k%2Dv%5Fw", V2Signer.encodeIdentity("k-v_w"))
    }

    @Test
    fun `sha256Hex is lowercase hex and matches the empty-input constant`() {
        val emptyHash = V2Signer.sha256Hex(ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyHash)
        assertEquals(64, emptyHash.length)
        assertTrue(emptyHash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `the two-byte empty-JSON-string body hashes differently from zero bytes`() {
        // The load-bearing distinction: verify-first blob/account routes sign the
        // 2-byte string "" while GET keys/messages sign 0 bytes. SHA256("") must
        // NOT equal SHA256(<0 bytes>), or those routes would collide.
        val emptyJsonString = V2Signer.sha256Hex("\"\"".toByteArray(Charsets.UTF_8))
        val zeroBytes = V2Signer.sha256Hex(ByteArray(0))
        assertNotEquals(zeroBytes, emptyJsonString)
    }
}
