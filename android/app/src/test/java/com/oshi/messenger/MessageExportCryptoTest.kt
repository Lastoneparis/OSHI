package com.oshi.messenger

import com.oshi.messenger.network.v2.MessageExportCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * `.oshiexport` container. The known-answer vector is the one iOS CryptoKit
 * produces for the same inputs — if this test breaks, an export made on one
 * platform no longer opens on the other.
 */
class MessageExportCryptoTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private val priv = ByteArray(32) { (it + 1).toByte() }            // 0x01..0x20
    private val salt = ByteArray(32) { 0xAA.toByte() }
    private val nonce = ByteArray(12) { 0xBB.toByte() }
    private val plaintext =
        """{"version":1,"platform":"test","exportedAt":"2026-09-22T00:00:00Z","messages":[]}"""
            .toByteArray(Charsets.UTF_8)

    @Test
    fun knownAnswer_matchesIosCryptoKit() {
        val pub = MessageExportCrypto.publicKeyFor(priv)
        assertEquals("07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c", hex(pub))
        assertEquals(
            "6702997f760f73d1f44545bb52cbc46cffa1c498ac905fde28a2aab9be0fbb28",
            hex(MessageExportCrypto.deriveKey(priv, salt)),
        )
        assertEquals("aaa8fff703b50b22", hex(MessageExportCrypto.accountTag(pub)))
        val file = MessageExportCrypto.seal(plaintext, priv, salt, nonce)
        assertEquals(
            "4f5348494558503101aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa8fff703b50b22" +
                "bbbbbbbbbbbbbbbbbbbbbbbbb5256d95a7583fe347ae099e632f327a065babf433d21ae321c2cca3b3c87344c0dfd509ba" +
                "4005fea6781e0d0f94e4061ac4d6dd81801b98b91711d4b251588db789dcfa457f2c295642ecbe81c3de90fec87a1db76f" +
                "c2ce593f558f02762a7963",
            hex(file),
        )
        assertArrayEquals(plaintext, MessageExportCrypto.open(file, priv))
    }

    @Test
    fun roundTrip_withRandomSaltAndNonce() {
        val body = ByteArray(100_000) { (it * 31).toByte() }
        val a = MessageExportCrypto.seal(body, priv)
        val b = MessageExportCrypto.seal(body, priv)
        assertEquals(false, a.contentEquals(b)) // fresh salt + nonce every time
        assertArrayEquals(body, MessageExportCrypto.open(a, priv))
        assertArrayEquals(body, MessageExportCrypto.open(b, priv))
    }

    @Test
    fun otherAccount_isRejectedAsWrongAccount() {
        val file = MessageExportCrypto.seal(plaintext, priv)
        val other = ByteArray(32) { (0x40 + it).toByte() }
        try {
            MessageExportCrypto.open(file, other)
            fail("opened with another account's key")
        } catch (e: MessageExportCrypto.OpenError.WrongAccount) {
        }
    }

    @Test
    fun anyFlippedByte_isRejected() {
        val file = MessageExportCrypto.seal(plaintext, priv, salt, nonce)
        for (i in file.indices) {
            val bad = file.copyOf()
            bad[i] = (bad[i].toInt() xor 0x01).toByte()
            try {
                MessageExportCrypto.open(bad, priv)
                fail("flipped byte $i was accepted")
            } catch (e: MessageExportCrypto.OpenError) {
                val expected = when {
                    i < 8 -> MessageExportCrypto.OpenError.NotAnExport::class
                    i == 8 -> MessageExportCrypto.OpenError.UnsupportedVersion::class
                    i >= 9 + 32 && i < 49 -> MessageExportCrypto.OpenError.WrongAccount::class
                    else -> MessageExportCrypto.OpenError.Damaged::class // salt, nonce, body, tag
                }
                assertEquals("byte $i", expected, e::class)
            }
        }
    }

    @Test
    fun truncated_isRejected() {
        val file = MessageExportCrypto.seal(plaintext, priv)
        try {
            MessageExportCrypto.open(file.copyOf(file.size - 1), priv)
            fail("truncated file accepted")
        } catch (e: MessageExportCrypto.OpenError.Damaged) {
        }
        try {
            MessageExportCrypto.open(file.copyOf(40), priv)
            fail("header-only file accepted")
        } catch (e: MessageExportCrypto.OpenError.NotAnExport) {
        }
    }
}
