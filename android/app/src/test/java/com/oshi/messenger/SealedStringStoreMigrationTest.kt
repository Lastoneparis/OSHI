package com.oshi.messenger

import com.oshi.messenger.data.local.atrest.AtRestKeyProvider
import com.oshi.messenger.data.local.atrest.SealedFile
import com.oshi.messenger.data.local.atrest.SealedStringStore
import com.oshi.messenger.data.model.PeerNicknameStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * __LEAK_FIX_2026_09_25__ Plaintext SharedPreferences entries (assistant history, the
 * check-in session, peer nicknames) → one sealed `OSHISEA1` file each, migrated on
 * first read. What must hold: the data survives, the plaintext goes, and a Keystore
 * outage never destroys anything.
 */
class SealedStringStoreMigrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val withKey = AtRestKeyProvider { key }
    private val noKey = AtRestKeyProvider.NONE

    private val secret = """[{"id":"c1","title":"Mon rendez-vous","messages":[{"content":"J'ai un cancer, que faire ?"}]}]"""

    /** In-memory stand-in for a SharedPreferences entry. */
    private class FakeLegacy(var value: String?, var clearWorks: Boolean = true) : SealedStringStore.Legacy {
        var clears = 0
        override fun read(): String? = value
        override fun clear(): Boolean {
            clears++
            if (clearWorks) value = null
            return clearWorks
        }
    }

    private fun store(file: File, keys: AtRestKeyProvider, legacy: SealedStringStore.Legacy?) =
        SealedStringStore(file, "test-label", keys, legacy)

    @Test
    fun `legacy plaintext is migrated to a sealed file and removed`() {
        val f = File(tmp.root, "ai_chats.sealed")
        val legacy = FakeLegacy(secret)
        val s = store(f, withKey, legacy)

        assertEquals(secret, s.read())
        assertTrue("sealed file written", f.isFile)
        val raw = f.readBytes()
        assertTrue("file starts with the OSHISEA1 magic", SealedFile.isSealed(raw))
        assertFalse("no plaintext on disk", String(raw, Charsets.ISO_8859_1).contains("cancer"))
        assertNull("legacy entry removed after a verified migration", legacy.value)

        // Next launch: served from the sealed file, legacy untouched.
        assertEquals(secret, store(f, withKey, FakeLegacy(null)).read())
    }

    @Test
    fun `without a key nothing moves and nothing is lost`() {
        val f = File(tmp.root, "checkin_session.sealed")
        val legacy = FakeLegacy(secret)
        assertEquals(secret, store(f, noKey, legacy).read())
        assertFalse("no file created without a key", f.exists())
        assertEquals("legacy kept", secret, legacy.value)
        assertEquals(0, legacy.clears)

        // Key comes back on a later launch → migrated then.
        assertEquals(secret, store(f, withKey, legacy).read())
        assertTrue(SealedFile.isSealed(f.readBytes()))
        assertNull(legacy.value)
    }

    @Test
    fun `a legacy clear that does not stick keeps the data readable`() {
        val f = File(tmp.root, "x.sealed")
        val legacy = FakeLegacy(secret, clearWorks = false)
        assertEquals(secret, store(f, withKey, legacy).read())
        // The sealed copy exists and wins; the stubborn legacy copy is retried later.
        assertEquals(secret, store(f, withKey, legacy).read())
        assertTrue(legacy.clears >= 2)
    }

    @Test
    fun `write seals and drops a leftover legacy copy`() {
        val f = File(tmp.root, "w.sealed")
        val legacy = FakeLegacy("old")
        val s = store(f, withKey, legacy)
        s.write(secret)
        assertTrue(SealedFile.isSealed(f.readBytes()))
        assertNull(legacy.value)
        assertEquals(secret, s.read())
    }

    @Test
    fun `a sealed file is never overwritten or opened without the key`() {
        val f = File(tmp.root, "s.sealed")
        store(f, withKey, null).write(secret)
        val before = f.readBytes()

        val blind = store(f, noKey, null)
        assertNull("unreadable, not garbage", blind.read())
        try {
            blind.write("[]")
            fail("must refuse to replace a sealed file without the key")
        } catch (_: IOException) {
        }
        assertTrue("file untouched", before.contentEquals(f.readBytes()))
        assertEquals(secret, store(f, withKey, null).read())
    }

    @Test
    fun `a file written in the clear during an outage is sealed on the next keyed read`() {
        val f = File(tmp.root, "o.sealed")
        store(f, noKey, null).write(secret)          // outage: fail-safe plaintext
        assertFalse(SealedFile.isSealed(f.readBytes()))
        assertEquals(secret, store(f, withKey, null).read())
        assertTrue(SealedFile.isSealed(f.readBytes()))
    }

    @Test
    fun `clear removes the file and the legacy copy`() {
        val f = File(tmp.root, "c.sealed")
        val legacy = FakeLegacy("x")
        val s = store(f, withKey, legacy)
        s.write(secret)
        legacy.value = "residue"
        s.clear()
        assertFalse(f.exists())
        assertNull(legacy.value)
        assertNull(s.read())
    }

    @Test
    fun `peer nickname map round-trips through its sealed JSON form`() {
        val m = mapOf("AbCd+/==" to "Zoé", "eFgH" to "Bob \"the\" builder")
        assertEquals(m, PeerNicknameStore.decode(PeerNicknameStore.encode(m)))
        assertEquals(emptyMap<String, String>(), PeerNicknameStore.decode("not json"))
    }

    @Test
    fun `every sealed store is erased by delete-account`() {
        assertEquals(
            setOf(
                "ai_chats.sealed",
                "checkin_session.sealed",
                "peer_nicknames.sealed",
                // __PROFILE_BIO_2026_11_02__ (N4) contact key -> the bio that
                // person published. Sealed for the same reason as the
                // nicknames, and listed here for the same reason as all of
                // them: a sealed file that delete-account does not wipe is
                // just a slower leak.
                "peer_bios.sealed",
            ),
            SealedStringStore.ACCOUNT_FILES.toSet(),
        )
        val settings = File("src/main/java/com/oshi/messenger/ui/viewmodel/SettingsViewModel.kt").readText()
        assertTrue(settings.contains("SealedStringStore.wipeAll(context)"))
    }

    /** The prekey store must never write a NEW private key to the plain XML. */
    @Test
    fun `prekey store has no plaintext write path for new keys`() {
        val src = File("src/main/java/com/oshi/messenger/network/v2/V2PrekeyStore.kt").readText()
        val code = src.lineSequence().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertFalse(code.contains("if (secure == null) plain else"))
        assertTrue(code.contains("if (!newSecret && plain.contains(key))"))
        assertTrue(code.contains("putString(OPK_MAP, obj.toString(), newSecret = true)"))
    }
}
