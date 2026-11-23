package com.oshi.messenger.mail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Cross-platform proof for the zero-access mailbox.
 *
 * The FROZEN VECTOR below was produced by the server's `crypto_mail.js seal()` and is
 * checked in on purpose: mail that Android cannot open is mail that is gone, because the
 * server holds no readable copy by design. A change to the scheme on any platform has to
 * break this test rather than silently ship.
 */
class MailCryptoTest {

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

    // Sealed by crypto_mail.js on the server.
    private val vectorPriv = "0BcexBZW4k4XN+deE4U2FiPcH6qvShf1ki25zLG1TVk="
    private val vectorPub = "yXY5YvOc+hMjk5xTEoAa7n2lfc7EZgD4vjf/6mSfmBk="
    private val vectorBlob = "OrgjhOlYUdrTPgGg3pG8k31LUwNglZjM2KFzcEO4h1nNgnKQwQ26dzzH0lxt9Awf3Ac7tUomVRT/" +
        "L2p6ZUYN0JHdScJJvO1gY+HdhXGEmxEtowhz6kwZ8Mo22+5KlbFxSe49bVL8hftWrk09qPcDmBsJ" +
        "r8SAV4Bnwi0rjMZ6++ajL4tZFmdzUQekqTwt"
    private val vectorPlain = "RnJvbTogSsOpcsO0bWUgPGpAeC5jb20+DQpTdWJqZWN0OiBDYWbDqSDigJQgYWNjZW50dcOp" +
        "IOKckw0KDQppbnRlcm9wIGNhbmFyeSA0NzEx"

    @Test
    fun `opens a blob sealed by the server`() {
        val opened = MailCrypto.open(b64(vectorBlob), b64(vectorPriv))
        assertArrayEquals(b64(vectorPlain), opened)
        val text = String(opened, Charsets.UTF_8)
        assertTrue("non-ASCII must survive", text.contains("Café — accentué ✓"))
        assertTrue(text.contains("interop canary 4711"))
    }

    @Test
    fun `the vector's private key really is the vector's public key`() {
        assertArrayEquals(b64(vectorPub), MailCrypto.publicKeyFor(b64(vectorPriv)))
    }

    @Test
    fun `a blob this device is not the recipient of does not open`() {
        val (otherPriv, _) = MailCrypto.generateMailKeyPair()
        assertThrows(MailCrypto.SealedBlobError::class.java) {
            MailCrypto.open(b64(vectorBlob), otherPriv)
        }
    }

    @Test
    fun `a tampered blob does not open`() {
        val blob = b64(vectorBlob)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0xFF).toByte()
        assertThrows(MailCrypto.SealedBlobError::class.java) {
            MailCrypto.open(blob, b64(vectorPriv))
        }
    }

    @Test
    fun `seal then open round-trips`() {
        val (priv, pub) = MailCrypto.generateMailKeyPair()
        val message = "drive file — accentué ✓".toByteArray(Charsets.UTF_8)
        val sealed = MailCrypto.seal(message, pub)
        assertArrayEquals(message, MailCrypto.open(sealed, priv))
    }

    @Test
    fun `sealing does not leave the plaintext in the blob`() {
        val (_, pub) = MailCrypto.generateMailKeyPair()
        val secret = "SALARY-IS-42"
        val sealed = MailCrypto.seal(secret.toByteArray(Charsets.UTF_8), pub)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains(secret))
    }

    @Test
    fun `the same plaintext seals to different bytes each time`() {
        val (_, pub) = MailCrypto.generateMailKeyPair()
        val a = MailCrypto.seal("same".toByteArray(), pub)
        val b = MailCrypto.seal("same".toByteArray(), pub)
        assertFalse("a fixed ephemeral key would leak message equality", a.contentEquals(b))
    }

    @Test
    fun `overhead is exactly the wire header`() {
        val (_, pub) = MailCrypto.generateMailKeyPair()
        val payload = ByteArray(100)
        assertEquals(100 + MailCrypto.SEAL_OVERHEAD, MailCrypto.seal(payload, pub).size)
    }
}
