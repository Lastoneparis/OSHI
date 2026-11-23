package com.oshi.messenger.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oshi.messenger.data.model.Contact
import com.oshi.messenger.data.repository.MessageRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 📸 Why an iPhone's avatar never appeared on Android.
 *
 * Contact rows are stored under `MessageRepository.canonicalIdentity(key)` — that is
 * what `ensureContactExists` inserts. The key on an inbound `📸PROFILE_UPDATE📸` is the
 * RAW wire key. For a peer whose key is base64url (`-`/`_`) or unpadded the two are
 * different strings, so
 *
 *     UPDATE contacts SET profilePicture = ? WHERE publicKey = <raw>
 *
 * matched **no rows** — and because the DAO returned `Unit`, the caller logged
 * "📸 Updated profile picture" over a write that never happened. The request went out,
 * iOS answered, Android decoded the bytes and dropped them, and the contact kept its
 * two-letter fallback for ever.
 *
 * These run against a real Room database because the defect IS the SQL match; a fake
 * DAO keyed on a map would have compared the same strings and passed.
 */
@RunWith(AndroidJUnit4::class)
class ProfilePictureSyncInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ContactDao

    /** An iOS-style key in base64url form — what arrives on the wire. */
    private val wireKey = "eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN_muFE3A"

    /** The same identity, canonicalised — what the row is actually keyed on. */
    private val storedKey = MessageRepository.canonicalIdentity(wireKey)

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dao = db.contactDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun theWireKeyAndTheStoredKeyAreGenuinelyDifferentStrings() {
        assertNotEquals(
            "if these were equal this whole class would be vacuous",
            wireKey, storedKey
        )
        assertEquals("eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN/muFE3A=", storedKey)
    }

    @Test
    fun updatingWithTheRawWireKeyMatchesNoRow() = runBlocking {
        dao.insert(Contact(publicKey = storedKey, alias = "Hugo Moriceau"))

        val rows = dao.updateProfilePicture(wireKey, png)

        assertEquals("the raw wire key must not match the canonically stored row", 0, rows)
        assertNull(
            "and the picture must genuinely not be there",
            dao.getContactByPublicKey(storedKey)?.profilePicture
        )
    }

    @Test
    fun updatingWithTheCanonicalKeyLands() = runBlocking {
        dao.insert(Contact(publicKey = storedKey, alias = "Hugo Moriceau"))

        val rows = dao.updateProfilePicture(MessageRepository.canonicalIdentity(wireKey), png)

        assertEquals(1, rows)
        assertArrayEquals(png, dao.getContactByPublicKey(storedKey)?.profilePicture)
    }

    /**
     * The order `writeProfilePicture` uses: canonical, then raw. A row that really was
     * stored under a non-canonical key (an older build) must still be reachable.
     */
    @Test
    fun aRowStoredUnderTheRawKeyIsStillReachableByTheFallback() = runBlocking {
        dao.insert(Contact(publicKey = wireKey, alias = "legacy row"))

        val canonicalFirst = dao.updateProfilePicture(storedKey, png)
        assertEquals("canonical must miss a legacy raw-keyed row", 0, canonicalFirst)

        val rawFallback = dao.updateProfilePicture(wireKey, png)
        assertEquals("the raw fallback is what saves it", 1, rawFallback)
        assertArrayEquals(png, dao.getContactByPublicKey(wireKey)?.profilePicture)
    }

    @Test
    fun lastSeenHasTheSameKeyingHazard() = runBlocking {
        dao.insert(Contact(publicKey = storedKey, alias = "Hugo Moriceau"))

        assertEquals(0, dao.updateLastSeen(wireKey, 1_756_000_000_000L))
        assertEquals(1, dao.updateLastSeen(storedKey, 1_756_000_000_000L))
        assertEquals(1_756_000_000_000L, dao.getContactByPublicKey(storedKey)?.lastSeen)
    }

    // =====================================================================
    // THE FIX ITSELF — `MessageRepository.writeProfilePictureTo`, the function
    // the inbound 📸PROFILE_UPDATE📸 handler actually calls. Driven directly,
    // against a real Room database, so this is the shipped code and not a copy
    // of its rule.
    // =====================================================================

    @Test
    fun theFixLandsAnAvatarSentWithTheRawWireKey() = runBlocking {
        dao.insert(Contact(publicKey = storedKey, alias = "Hugo Moriceau"))

        val rows = MessageRepository.writeProfilePictureTo(dao, wireKey, png)

        assertEquals("canonical-first must find the stored row", 1, rows)
        assertArrayEquals(
            "this is the write that used to vanish",
            png, dao.getContactByPublicKey(storedKey)?.profilePicture
        )
    }

    @Test
    fun theFixStillReachesALegacyRowStoredUnderTheRawKey() = runBlocking {
        dao.insert(Contact(publicKey = wireKey, alias = "written by an older build"))

        val rows = MessageRepository.writeProfilePictureTo(dao, wireKey, png)

        assertEquals(1, rows)
        assertArrayEquals(png, dao.getContactByPublicKey(wireKey)?.profilePicture)
    }

    @Test
    fun theFixCreatesTheContactWhenTheAvatarArrivesFirst() = runBlocking {
        assertNull("precondition: nobody there yet", dao.getContactByPublicKey(storedKey))

        val rows = MessageRepository.writeProfilePictureTo(dao, wireKey, png)

        assertEquals(1, rows)
        assertArrayEquals(
            "a profile that arrives before the first stored message must not be dropped",
            png, dao.getContactByPublicKey(storedKey)?.profilePicture
        )
    }

    @Test
    fun theFixDoesNotDuplicateAContactItAlreadyFound() = runBlocking {
        dao.insert(Contact(publicKey = storedKey, alias = "Hugo Moriceau"))

        MessageRepository.writeProfilePictureTo(dao, wireKey, png)

        assertEquals(1, dao.getAllContactsList().size)
        assertEquals("the real alias must survive", "Hugo Moriceau",
            dao.getContactByPublicKey(storedKey)?.alias)
    }

    // =====================================================================
    // THE SWEEP — the half that explains an avatar set AFTER the last message.
    // =====================================================================

    @Test
    fun theSweepAsksOnlyContactsWithNoAvatar() {
        val withPic = Contact(publicKey = "AAAA", alias = "has one", profilePicture = png)
        val without = Contact(publicKey = "BBBB", alias = "has none")
        val targets = MessageRepository.profileSweepTargets(listOf(withPic, without))
        assertEquals(listOf("BBBB"), targets.map { it.publicKey })
    }

    @Test
    fun theSweepIsCappedSoALargeAddressBookIsNotOneBurst() {
        val many = (1..40).map { Contact(publicKey = "K$it", alias = "n$it") }
        val targets = MessageRepository.profileSweepTargets(many)
        assertTrue("a sweep must not fan out to the whole address book", targets.size <= 8)
        assertTrue("but it must ask somebody", targets.isNotEmpty())
    }

    @Test
    fun theSweepEmptiesItselfAsAvatarsArrive() {
        val allHave = (1..5).map {
            Contact(publicKey = "K$it", alias = "n$it", profilePicture = png)
        }
        assertTrue(
            "once everyone has an avatar the sweep must stop asking",
            MessageRepository.profileSweepTargets(allHave).isEmpty()
        )
    }
}
