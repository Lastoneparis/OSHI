package com.oshi.messenger.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * __DB_KEYSTORE_HICCUP_2026_11_11__ The file-level half of "a Keystore refusal
 * must never cost the user their database". The SQLCipher half (restore + merge)
 * needs the native library and was exercised on the emulator.
 */
class DatabaseSetAsideTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun db(dir: File, content: ByteArray = ByteArray(64) { 7 }): File =
        File(dir, DatabaseEncryption.DB_NAME).apply { writeBytes(content) }

    @Test fun plaintextHeaderIsRecognisedAndEncryptedIsNot() {
        val dir = tmp.newFolder()
        val plain = db(dir, "SQLite format 3\u0000".toByteArray() + ByteArray(100))
        assertTrue(DatabaseEncryption.hasPlaintextHeader(plain))
        val enc = File(dir, "enc").apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        assertFalse(DatabaseEncryption.hasPlaintextHeader(enc))
        assertFalse(DatabaseEncryption.hasPlaintextHeader(File(dir, "missing")))
    }

    @Test fun setAsideMovesTheFileAndItsWalInsteadOfDeleting() {
        val dir = tmp.newFolder()
        val main = db(dir)
        File(dir, DatabaseEncryption.DB_NAME + "-wal").writeText("wal")
        assertTrue(DatabaseEncryption.moveSet(main, DatabaseEncryption.KEYLESS_PREFIX))
        assertFalse(main.exists())
        val names = dir.list()!!.sorted()
        assertEquals(2, names.size)
        assertTrue(names.all { it.startsWith(DatabaseEncryption.KEYLESS_PREFIX + ".") })
        assertTrue(names.any { it.endsWith("-wal") })
    }

    @Test fun repeatedFailuresKeepTheOldestSet() {
        val dir = tmp.newFolder()
        val first = ByteArray(64) { 1 }
        db(dir, first)
        DatabaseEncryption.moveSet(File(dir, DatabaseEncryption.DB_NAME), DatabaseEncryption.QUARANTINE_PREFIX)
        val oldest = dir.listFiles()!!.single()
        repeat(5) {
            Thread.sleep(3)
            db(dir, ByteArray(64) { 9 })
            DatabaseEncryption.moveSet(File(dir, DatabaseEncryption.DB_NAME), DatabaseEncryption.QUARANTINE_PREFIX)
        }
        val left = dir.listFiles()!!.filter { !it.name.contains('-') }
        assertEquals(3, left.size)
        assertTrue(oldest.exists())
        assertTrue(oldest.readBytes().contentEquals(first))
    }
}
