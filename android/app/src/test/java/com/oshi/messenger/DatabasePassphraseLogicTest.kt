package com.oshi.messenger

import com.oshi.messenger.data.local.DatabasePassphrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Pure-JVM tests for the SQLCipher passphrase generator.
 *
 * The KeyStore-backed persistence (EncryptedSharedPreferences) and the
 * plaintext->encrypted migration require Android + SQLCipher's native library,
 * neither of which runs under the JVM/Robolectric setup on this build host —
 * those are covered by DatabaseEncryption.MIGRATION_TEST_PLAN (on-device). What
 * we CAN and DO assert here is the security-critical property of the passphrase
 * itself: it is 256 bits of fresh CSPRNG output, Base64-encoded, and never a
 * constant.
 */
class DatabasePassphraseLogicTest {

    @Test
    fun passphrase_is_256_bits_of_base64() {
        val pass = DatabasePassphrase.generatePassphrase()
        val raw = Base64.getDecoder().decode(pass)
        assertEquals("passphrase must be 32 bytes (256-bit)", 32, raw.size)
    }

    @Test
    fun passphrase_alphabet_is_sql_safe() {
        // Standard Base64 alphabet has no single quote, so the value binds/quotes
        // safely wherever SQLCipher takes it as text.
        val pass = DatabasePassphrase.generatePassphrase()
        assertTrue("no single quote in passphrase", !pass.contains('\''))
        assertTrue(
            "only base64 chars",
            pass.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' },
        )
    }

    @Test
    fun passphrase_is_random_each_call() {
        val a = DatabasePassphrase.generatePassphrase()
        val b = DatabasePassphrase.generatePassphrase()
        assertNotEquals("two generations must not collide", a, b)
    }

    @Test
    fun many_generations_are_unique() {
        val set = HashSet<String>()
        repeat(1000) { set.add(DatabasePassphrase.generatePassphrase()) }
        assertEquals("1000 passphrases must all be distinct", 1000, set.size)
    }
}
