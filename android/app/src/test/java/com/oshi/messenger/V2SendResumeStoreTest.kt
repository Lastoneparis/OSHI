package com.oshi.messenger

import com.oshi.messenger.service.media.V2SendResumeStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The send-resume ledger (M-4) — iOS `V2SendResumeStore`
 * (`V2MessageRouter.swift:1290-1457`).
 *
 * The tests that matter here are the ones about REFUSING to resume: handing the same
 * `fileKey`/`fileNonce` back for DIFFERENT plaintext is an AES-GCM (key, nonce) reuse,
 * which is a total break. Every path that could do that must yield null instead.
 */
class V2SendResumeStoreTest {

    private lateinit var root: File
    private lateinit var store: V2SendResumeStore

    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(8) { (it + 1).toByte() }
    private val plaintext = "the video bytes".toByteArray()
    private val digest = V2SendResumeStore.digest(plaintext)

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "v2resume_${System.nanoTime()}").apply { mkdirs() }
        store = V2SendResumeStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a record round-trips through the file and keeps the key material intact`() {
        store.put("m1", "peerA", digest, "blob-1", key, nonce, chunkCount = 7)

        // A NEW instance: this is the process-death case the ledger exists for.
        val r = V2SendResumeStore(root).record("m1", "peerA", digest)
        assertNotNull(r)
        assertEquals("blob-1", r!!.blobId)
        assertEquals(7, r.chunkCount)
        assertArrayEquals(key, r.fileKey)
        assertArrayEquals(nonce, r.fileNonce)
    }

    @Test
    fun `a different plaintext under the same id is refused and the record dropped`() {
        store.put("m1", "peerA", digest, "blob-1", key, nonce, chunkCount = 7)
        val other = V2SendResumeStore.digest("different bytes".toByteArray())
        assertNull("key material must never touch a different plaintext", store.record("m1", "peerA", other))
        // …and it is gone, so a later correct lookup cannot resurrect it either.
        assertNull(store.record("m1", "peerA", digest))
        assertEquals(0, store.size())
    }

    @Test
    fun `a record for another recipient is never handed over`() {
        store.put("m1", "peerA", digest, "blob-1", key, nonce, chunkCount = 7)
        assertNull(store.record("m1", "peerB", digest))
        assertNotNull("peerA's own record survives", store.record("m1", "peerA", digest))
    }

    @Test
    fun `a record past 24 h is expired, not resumed`() {
        val old = System.currentTimeMillis() - V2SendResumeStore.MAX_AGE_MS - 1
        store.put("m1", "peerA", digest, "blob-1", key, nonce, chunkCount = 7, now = old)
        assertNull(store.record("m1", "peerA", digest))
        assertEquals(0, store.size())
    }

    @Test
    fun `one message fanned out to several members keeps one record per member`() {
        // A group send reserves ONE BLOB PER MEMBER (V2MessageRouter.swift:1353).
        store.put("m1", "peerA", digest, "blob-A", key, nonce, chunkCount = 3)
        store.put("m1", "peerB", digest, "blob-B", key, nonce, chunkCount = 3)
        assertEquals(2, store.size())
        assertEquals("blob-A", store.record("m1", "peerA", digest)!!.blobId)
        assertEquals("blob-B", store.record("m1", "peerB", digest)!!.blobId)
        assertEquals("m1#peerA", V2SendResumeStore.fanoutId("m1", "peerA"))
    }

    @Test
    fun `remove clears exactly one record`() {
        store.put("m1", "peerA", digest, "blob-A", key, nonce, chunkCount = 3)
        store.put("m2", "peerA", digest, "blob-B", key, nonce, chunkCount = 3)
        store.remove("m1", "peerA")
        assertNull(store.record("m1", "peerA", digest))
        assertNotNull(store.record("m2", "peerA", digest))
    }

    @Test
    fun `prune drops only the expired records`() {
        val t0 = System.currentTimeMillis()
        // Both writes happen while the first record is still inside its 24 h window,
        // so neither is swept by `put`'s own prune; only the explicit sweep drops it.
        store.put("old", "peerA", digest, "b1", key, nonce, chunkCount = 1, now = t0 - V2SendResumeStore.MAX_AGE_MS - 1)
        store.put("new", "peerA", digest, "b2", key, nonce, chunkCount = 1, now = t0 - 1000)
        assertEquals(2, store.size())
        assertEquals(1, store.prune(t0))
        assertEquals(1, store.size())
        assertNotNull(store.record("new", "peerA", digest))
    }

    @Test
    fun `a write sweeps records that expired in the meantime`() {
        // The ledger cannot grow without bound across a long-lived install: every put
        // drops what has aged out, which is why the test above has to pin its clock.
        store.put("old", "peerA", digest, "b1", key, nonce, chunkCount = 1,
            now = System.currentTimeMillis() - V2SendResumeStore.MAX_AGE_MS - 1)
        store.put("new", "peerA", digest, "b2", key, nonce, chunkCount = 1)
        assertEquals(1, store.size())
        assertNull(store.record("old", "peerA", digest))
    }

    @Test
    fun `a corrupt ledger degrades to no-resume instead of throwing`() {
        store.put("m1", "peerA", digest, "blob-1", key, nonce, chunkCount = 7)
        File(root, "v2_send_resume.json").writeText("{not json at all")
        assertNull(V2SendResumeStore(root).record("m1", "peerA", digest))
    }

    @Test
    fun `an absent ledger is simply empty`() {
        assertNull(store.record("nope", "peerA", digest))
        assertEquals(0, store.size())
    }

    @Test
    fun `the digest is a plain lower-case SHA-256 of the plaintext`() {
        assertEquals(64, digest.length)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            V2SendResumeStore.digest(ByteArray(0)),
        )
    }

    // __MEDIA_PADDING_2026_11_10__ the padding decision is part of what binds a resume: the same
    // key/nonce over the padded tail and over the unpadded one would be GCM nonce reuse.

    @Test
    fun `a padded reservation is never resumed unpadded, nor the reverse`() {
        store.put("m1", "peerA", digest, "blob-1", key, nonce, chunkCount = 1, padded = true)
        assertNotNull(V2SendResumeStore(root).record("m1", "peerA", digest, padded = true))
        assertNull("padded record, unpadded attempt", store.record("m1", "peerA", digest, padded = false))
        assertNull("…and the record is dropped", store.record("m1", "peerA", digest, padded = true))

        store.put("m2", "peerA", digest, "blob-2", key, nonce, chunkCount = 1)
        assertNull("unpadded record, padded attempt", store.record("m2", "peerA", digest, padded = true))
        assertNull(store.record("m2", "peerA", digest))
    }

    @Test
    fun `a record written before media padding reads as unpadded`() {
        File(root, "v2_send_resume.json").writeText(
            """[{"id":"m1#peerA","recipientKey":"peerA","plaintextSha256":"$digest","blobId":"b","fileKey":"${"00".repeat(32)}","fileNonce":"${"01".repeat(8)}","chunkCount":1,"createdAt":${System.currentTimeMillis()}}]"""
        )
        val r = V2SendResumeStore(root).record("m1", "peerA", digest)
        assertNotNull(r)
        assertEquals(false, r!!.padded)
        assertNull(V2SendResumeStore(root).record("m1", "peerA", digest, padded = true))
    }
}
