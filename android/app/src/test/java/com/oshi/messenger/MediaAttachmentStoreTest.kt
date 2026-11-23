package com.oshi.messenger

import com.oshi.messenger.service.media.MediaAttachmentStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Read-after-write, delete and orphan-purge behaviour of the file-backed attachment
 * store — the class `MessageRepository` delegated to for two releases without it
 * existing, which is why every attachment over 1.5 MB rendered as an empty bubble.
 */
class MediaAttachmentStoreTest {

    private lateinit var root: File
    private lateinit var store: MediaAttachmentStore

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "oshi-media-test-${System.nanoTime()}")
        root.mkdirs()
        store = MediaAttachmentStore(root, com.oshi.messenger.data.local.atrest.AtRestKeyProvider { TEST_AT_REST_KEY })
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun bytes(n: Int, seed: Int = 0) = ByteArray(n) { ((it + seed) % 251).toByte() }

    @Test
    fun `read after write returns the exact bytes`() {
        val id = "0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0"
        val data = bytes(3 * 1024 * 1024)          // 3 MB — well over the inline cap
        assertTrue(store.save(id, data))
        assertTrue(store.has(id))
        assertEquals(data.size.toLong(), store.sizeOf(id))
        assertArrayEquals(data, store.load(id))
    }

    @Test
    fun `read survives a cold cache (bytes really are on disk)`() {
        val id = "cold-cache-id"
        val data = bytes(64 * 1024, seed = 7)
        store.save(id, data)

        // A brand-new store instance shares only the directory, not the LRU.
        val fresh = MediaAttachmentStore(root, com.oshi.messenger.data.local.atrest.AtRestKeyProvider { TEST_AT_REST_KEY })
        assertArrayEquals(data, fresh.load(id))
    }

    @Test
    fun `absent id reads as null rather than throwing`() {
        assertNull(store.load("never-written"))
        assertFalse(store.has("never-written"))
        assertEquals(-1L, store.sizeOf("never-written"))
    }

    @Test
    fun `ids that are not filesystem-safe round trip`() {
        // Inbound ids come from the peer and are not guaranteed to be UUIDs.
        val nasty = "../../etc/passwd:weird id/with slashes"
        val data = bytes(128)
        assertTrue(store.save(nasty, data))
        assertArrayEquals(data, store.load(nasty))
        // …and nothing escaped the directory.
        assertEquals(1, root.resolve(MediaAttachmentStore.DIR_NAME).listFiles()!!.size)
    }

    @Test
    fun `name encoding is reversible`() {
        for (id in listOf("a", "0F1E2D3C-4B5A", "../x", "émoji-🎬-id")) {
            val enc = MediaAttachmentStore.encodeName(id)
            assertEquals(id, MediaAttachmentStore.decodeName("$enc.dat"))
        }
        assertNull(MediaAttachmentStore.decodeName("not-hex.dat"))
        assertNull(MediaAttachmentStore.decodeName("abcd.txt"))
    }

    @Test
    fun `delete removes the file and the cache entry`() {
        val id = "to-delete"
        store.save(id, bytes(1024))
        store.delete(id)
        assertFalse(store.has(id))
        assertNull(store.load(id))
    }

    @Test
    fun `purgeOrphans drops only blobs whose row is gone`() {
        store.save("live-1", bytes(100))
        store.save("live-2", bytes(100))
        store.save("orphan-1", bytes(100))
        store.save("orphan-2", bytes(100))

        val removed = store.purgeOrphans(setOf("live-1", "live-2"))

        assertEquals(2, removed)
        assertTrue(store.has("live-1"))
        assertTrue(store.has("live-2"))
        assertFalse(store.has("orphan-1"))
        assertFalse(store.has("orphan-2"))
    }

    @Test
    fun `cache evicts by count but disk keeps everything`() {
        val n = MediaAttachmentStore.CACHE_COUNT_LIMIT + 10
        repeat(n) { store.save("id-$it", bytes(32, seed = it)) }
        assertTrue(store.cachedCount() <= MediaAttachmentStore.CACHE_COUNT_LIMIT)
        // Every blob is still readable — eviction is a memory decision, not a data one.
        repeat(n) { assertArrayEquals(bytes(32, seed = it), store.load("id-$it")) }
    }

    @Test
    fun `overwrite replaces rather than appends`() {
        val id = "overwrite-me"
        store.save(id, bytes(4096, seed = 1))
        store.save(id, bytes(1024, seed = 2))
        assertEquals(1024L, store.sizeOf(id))
        assertArrayEquals(bytes(1024, seed = 2), store.load(id))
    }
}

// __PLAINTEXT_LEFTOVERS_2026_09_24__ The store no longer writes plaintext without an
// at-rest key (there is no Keystore on the JVM), so these tests hand it a fixed one.
private val TEST_AT_REST_KEY = ByteArray(32) { (it * 13 + 7).toByte() }
