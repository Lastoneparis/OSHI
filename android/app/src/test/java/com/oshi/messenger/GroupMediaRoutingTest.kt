package com.oshi.messenger

import com.oshi.messenger.service.GroupManager
import com.oshi.messenger.service.GroupManager.GroupMediaSink
import com.oshi.messenger.service.media.MediaAttachmentStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M-3 — group media routing, and the Room mirror's blob guard.
 *
 * Groups used to run a **second, parallel** media store: an inline `saveMediaToFile`
 * writing `filesDir/group_media/<id>.media` with `file.writeBytes` straight onto the
 * destination (a torn-write window), no LRU, no cost budget, and no orphan sweep — while
 * 1:1 media went through [MediaAttachmentStore], a port of iOS's
 * `MediaManager`/`FileStorage`/`MediaAttachmentCache` lifecycle. iOS has ONE attachment
 * lifecycle for both surfaces (`OSHI/MediaManager.swift:762-771`).
 *
 * The routing decisions are pure so both caps are pinned by a test rather than by a
 * literal buried in a 4 000-line file, and the directory-isolation invariant — the thing
 * that makes a shared store class safe here — is exercised against a real temp directory.
 */
class GroupMediaRoutingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // =====================================================================
    // Where the bytes are persisted
    // =====================================================================

    @Test
    fun `no attachment routes nowhere`() {
        assertEquals(GroupMediaSink.NONE, GroupManager.groupMediaSink(null, 100_000))
    }

    @Test
    fun `small media stays inline in the prefs document`() {
        assertEquals(GroupMediaSink.INLINE, GroupManager.groupMediaSink(0, 100_000))
        assertEquals(GroupMediaSink.INLINE, GroupManager.groupMediaSink(99_999, 100_000))
        // The cap is inclusive — exactly the cap still rides inline.
        assertEquals(GroupMediaSink.INLINE, GroupManager.groupMediaSink(100_000, 100_000))
    }

    @Test
    fun `anything above the cap goes to the file store`() {
        // The whole `group_messages_data` document is re-serialised on every debounced
        // save; base64 of a video in there is what made group saves janky.
        assertEquals(GroupMediaSink.FILE, GroupManager.groupMediaSink(100_001, 100_000))
        assertEquals(GroupMediaSink.FILE, GroupManager.groupMediaSink(40 * 1024 * 1024, 100_000))
    }

    // =====================================================================
    // What may ride on a Room row
    // =====================================================================

    @Test
    fun `a blob at or under the CursorWindow cap rides on the row`() {
        assertTrue(GroupManager.fitsOnRoomRow(null, 1_500_000))
        assertTrue(GroupManager.fitsOnRoomRow(0, 1_500_000))
        assertTrue(GroupManager.fitsOnRoomRow(1_500_000, 1_500_000))
    }

    @Test
    fun `an oversize blob must be stripped before the row is written`() {
        // Android's CursorWindow is 2 MB. Writing a larger blob succeeds and then makes
        // every later `SELECT *` that touches the row throw SQLiteBlobTooBigException —
        // in whatever screen happens to run the query, not at write time.
        assertFalse(GroupManager.fitsOnRoomRow(1_500_001, 1_500_000))
        assertFalse(GroupManager.fitsOnRoomRow(40 * 1024 * 1024, 1_500_000))
    }

    // =====================================================================
    // The store itself, with a real directory
    // =====================================================================

    @Test
    fun `group and direct stores live in disjoint directories`() {
        // This is the whole reason groups get their own ROOT rather than sharing
        // `filesDir/media`: `MessageRepository.purgeOrphanAttachments` enumerates that
        // directory and deletes every id that is not a row in the 1:1 MESSAGES table.
        // Group ids are not in that table, so a shared directory would hand the 1:1 sweep
        // a licence to delete every group photo on the device.
        val filesDir = tmp.newFolder("files")
        val direct = MediaAttachmentStore(filesDir, com.oshi.messenger.data.local.atrest.AtRestKeyProvider { TEST_AT_REST_KEY })                                  // MessageRepository's store
        val groups = MediaAttachmentStore(GroupManager.groupMediaRoot(filesDir), com.oshi.messenger.data.local.atrest.AtRestKeyProvider { TEST_AT_REST_KEY })     // GroupManager's, same call

        val sharedId = "9a0c1f2e-3b4d-5e6f-7a8b-9c0d1e2f3a4b"
        assertTrue(direct.save(sharedId, byteArrayOf(1, 2, 3)))
        assertTrue(groups.save(sharedId, byteArrayOf(9, 9, 9, 9)))

        assertFalse(
            "the two stores must not resolve to the same directory",
            direct.directory.canonicalPath == groups.directory.canonicalPath
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), direct.load(sharedId))
        assertArrayEquals(byteArrayOf(9, 9, 9, 9), groups.load(sharedId))

        // The 1:1 sweep, run with an empty live set — exactly what it would see for a
        // group id — must not touch the group blob.
        direct.purgeOrphans(emptySet())
        assertNull("the 1:1 sweep removed its own blob", direct.load(sharedId))
        assertArrayEquals(
            "the 1:1 sweep must not be able to reach group media",
            byteArrayOf(9, 9, 9, 9),
            groups.load(sharedId)
        )
    }

    @Test
    fun `a group blob survives a store restart, which the prefs document no longer carries`() {
        val filesDir = tmp.newFolder("files2")
        val id = "11111111-2222-3333-4444-555555555555"
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        assertTrue(MediaAttachmentStore(GroupManager.groupMediaRoot(filesDir), com.oshi.messenger.data.local.atrest.AtRestKeyProvider { TEST_AT_REST_KEY }).save(id, bytes))
        // A brand-new instance, i.e. a fresh process with a cold cache: the row carries
        // only the id, so this read is the whole persistence story for group media.
        assertArrayEquals(bytes, MediaAttachmentStore(GroupManager.groupMediaRoot(filesDir), com.oshi.messenger.data.local.atrest.AtRestKeyProvider { TEST_AT_REST_KEY }).load(id))
    }
}

// __PLAINTEXT_LEFTOVERS_2026_09_24__ The store no longer writes plaintext without an
// at-rest key (there is no Keystore on the JVM), so these tests hand it a fixed one.
private val TEST_AT_REST_KEY = ByteArray(32) { (it * 13 + 7).toByte() }
