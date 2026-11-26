package com.oshi.messenger

import com.oshi.messenger.data.local.atrest.AtRestKeyProvider
import com.oshi.messenger.network.v2.OSHICryptoV2
import com.oshi.messenger.network.v2.V2BlobClient
import com.oshi.messenger.network.v2.V2Reservation
import com.oshi.messenger.service.GroupPendingDeliveryStore
import com.oshi.messenger.service.GroupResendSchedule
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * __GROUP_RESEND_2026_11_25__ + __GROUP_BLOB_2026_11_25__
 *
 *  * the durable per-message queue of members a group send missed (restart, backoff, members who
 *    left, members with no bundle, status counts);
 *  * the shared-blob capability signal (the server's `recipients` echo);
 *  * the resumable download (chunks kept on disk across a failed attempt).
 */
class GroupResendTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "oshi-resend-${System.nanoTime()}").apply { mkdirs() }
    private val keys = AtRestKeyProvider { ByteArray(32) { (it * 13 + 7).toByte() } }
    private fun store() = GroupPendingDeliveryStore(root, keys)

    @After fun tearDown() { root.deleteRecursively() }

    private val members = (1..5).map { "member$it" }
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L

    @Test
    fun `a send that reached everybody leaves nothing pending`() {
        val s = store()
        s.begin("m1", "G", members, now = t0)
        assertNull(s.settle("m1", reached = members, noBundle = emptyList(), now = t0 + 1))
        assertTrue(s.all().isEmpty())
    }

    @Test
    fun `missed members stay pending and the status counts are honest`() {
        val s = store()
        s.begin("m1", "G", members, now = t0)
        val e = s.settle("m1", reached = members.take(3), noBundle = emptyList(), now = t0)!!
        assertEquals(setOf("member4", "member5"), e.pending)
        assertEquals(5, e.total)
        assertEquals(3, e.delivered)
    }

    @Test
    fun `members with no v2 bundle follow the must-update spec, not the queue`() {
        val s = store()
        s.begin("m1", "G", members, now = t0)
        val e = s.settle("m1", reached = members.take(3), noBundle = listOf("member4"), now = t0)!!
        assertEquals(setOf("member5"), e.pending)
        assertEquals(4, e.total)        // "Sent to 3 of 4"
        assertEquals(3, e.delivered)
        assertNull(s.settle("m1", reached = listOf("member5"), noBundle = emptyList(), now = t0 + min))
    }

    @Test
    fun `the queue survives a restart, including a send killed before it settled`() {
        store().begin("m1", "G", members, now = t0, wireContent = "💬REPLY💬{…}", commentOn = "P1", wakeOnly = setOf("member2"))
        val again = store()                       // new process
        val e = again.get("m1")!!
        assertEquals(members.toSet(), e.pending)
        assertEquals("💬REPLY💬{…}", e.wireContent)
        assertEquals("P1", e.commentOn)
        assertEquals(setOf("member2"), e.wakeOnly)
        // the first automatic attempt is due a minute after the send started
        assertTrue(again.due(now = t0 + 30_000).isEmpty())
        assertEquals(listOf("m1"), again.due(now = t0 + min).map { it.msgId })
    }

    @Test
    fun `the file on disk is sealed, not plaintext`() {
        store().begin("m1", "G", listOf("SECRET_MEMBER_KEY"), now = t0)
        val f = File(root, "group_pending_delivery.json")
        assertTrue(f.isFile)
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("SECRET_MEMBER_KEY"))
    }

    @Test
    fun `backoff is 1, 5, 30 min then every 3 h`() {
        assertEquals(1 * min, GroupResendSchedule.delayAfter(1))
        assertEquals(5 * min, GroupResendSchedule.delayAfter(2))
        assertEquals(30 * min, GroupResendSchedule.delayAfter(3))
        assertEquals(180 * min, GroupResendSchedule.delayAfter(4))
        assertEquals(180 * min, GroupResendSchedule.delayAfter(40))
        val s = store()
        s.begin("m1", "G", members, now = t0)
        var now = t0
        val waits = mutableListOf<Long>()
        repeat(5) {
            val e = s.settle("m1", reached = emptyList(), noBundle = emptyList(), now = now)!!
            waits += e.nextAt - now
            now = e.nextAt
        }
        assertEquals(listOf(1 * min, 5 * min, 30 * min, 180 * min, 180 * min), waits)
    }

    @Test
    fun `a relay Retry-After longer than the schedule wins`() {
        val s = store()
        s.begin("m1", "G", members, now = t0)
        val e = s.settle("m1", emptyList(), emptyList(), now = t0, retryAfterMs = 10 * min)!!
        assertEquals(t0 + 10 * min, e.nextAt)
    }

    @Test
    fun `after 7 days the automatic retries stop, Retry now still works`() {
        val s = store()
        s.begin("m1", "G", members, now = t0)
        val late = t0 + GroupResendSchedule.MAX_AGE_MS + 1
        val e = s.settle("m1", members.take(1), emptyList(), now = late)!!
        assertTrue(e.gaveUp)
        assertTrue(s.due(now = late + 365L * 24 * 60 * min).isEmpty())
        s.makeDue("m1", now = late + 5)
        assertEquals(listOf("m1"), s.due(now = late + 5).map { it.msgId })
    }

    @Test
    fun `members who left the group are no longer owed the message`() {
        val s = store()
        s.begin("m1", "G", members, now = t0)
        s.settle("m1", reached = members.take(3), noBundle = emptyList(), now = t0)
        assertTrue(s.dropDeparted("G", members.take(4)).isEmpty())
        assertEquals(setOf("member4"), s.get("m1")!!.pending)
        assertEquals(4, s.get("m1")!!.total)
        assertEquals(listOf("m1"), s.dropDeparted("G", members.take(3)))
        assertNull(s.get("m1"))
    }

    @Test
    fun `connectivity back makes every live entry due at once`() {
        val s = store()
        s.begin("m1", "G", members, now = t0)
        s.settle("m1", emptyList(), emptyList(), now = t0)
        s.settle("m1", emptyList(), emptyList(), now = t0 + min)   // next in 5 min
        assertTrue(s.due(now = t0 + 2 * min).isEmpty())
        s.makeDue(now = t0 + 2 * min)
        assertEquals(1, s.due(now = t0 + 2 * min).size)
    }

    @Test
    fun `identity spellings are compared canonically`() {
        val s = GroupPendingDeliveryStore(root, keys, canon = { it.replace('-', '+').replace('_', '/') })
        s.begin("m1", "G", listOf("ab+c/d", "zz"), now = t0)
        val e = s.settle("m1", reached = listOf("ab-c_d"), noBundle = emptyList(), now = t0)!!
        assertEquals(setOf("zz"), e.pending)
    }

    // ---- shared blob capability ----------------------------------------------------------

    @Test
    fun `only a server that echoed recipients is treated as storing the list`() {
        assertFalse(V2BlobClient.isShared(V2Reservation("b", 2, listOf(0)), 5))           // old server
        assertTrue(V2BlobClient.isShared(V2Reservation("b", 2, listOf(0), recipients = 5), 5))
        assertFalse(V2BlobClient.isShared(V2Reservation("b", 2, listOf(0), recipients = 0), 5))
    }

    @Test
    fun `group media is uploaded once, the per-member upload only for an old server`() {
        val src = File(repo(), "OSHI-Android/app/src/main/java/com/oshi/messenger/network/v2/V2MessageRouter.kt").readText()
        val fn = src.substringAfter("suspend fun sendGroupFile(").substringBefore("private suspend fun sendTextLocked(")
        assertTrue(fn.contains("blobClient.uploadGroupBlob("))
        // the per-member upload exists only behind the "server did not store the list" branch
        assertTrue(fn.contains("if (shared.shared || index == 0) shared.blobId"))
        assertEquals(1, Regex("""blobClient\.uploadBlob\(""").findAll(fn).count())
        // members whose bundle could not be fetched now are listed too: the resend hands them THIS blob
        assertTrue(fn.contains("members = reachable + unknown,"))
    }

    // ---- resumable download ----------------------------------------------------------------

    @Test
    fun `a download cut after two chunks resumes at the third, then cleans up`() {
        val data = ByteArray(5 * 1000 + 17) { (it * 31).toByte() }
        val enc = OSHICryptoV2.encryptFile(data, "v.mp4", "video/mp4", chunkSize = 1000)
        assertEquals(6, enc.chunks.size)
        val partial = File(root, "partial")
        val out = File(root, "out.bin")
        val fetched = mutableListOf<Int>()
        // attempt 1: the network dies at chunk 2
        val first = V2BlobClient.decryptResumable(partial, "blob-1", 6, enc.fileKey, enc.fileNonce, enc.manifest, out) { i ->
            if (i >= 2) null else enc.chunks[i].also { fetched += i }
        }
        assertNull(first)
        assertFalse(out.exists())
        assertEquals(2, com.oshi.messenger.network.v2.V2PartialChunks(partial, "blob-1").count())
        // attempt 2 (e.g. after an app restart): only chunks 2..5 go over the network
        fetched.clear()
        val second = V2BlobClient.decryptResumable(partial, "blob-1", 6, enc.fileKey, enc.fileNonce, enc.manifest, out) { i ->
            enc.chunks[i].also { fetched += i }
        }
        assertNotNull(second)
        assertEquals(listOf(2, 3, 4, 5), fetched)
        assertArrayEquals(data, out.readBytes())
        assertEquals(0, com.oshi.messenger.network.v2.V2PartialChunks(partial, "blob-1").count())
    }

    @Test
    fun `a corrupt cached chunk is dropped instead of being fed back forever`() {
        val data = ByteArray(2500) { it.toByte() }
        val enc = OSHICryptoV2.encryptFile(data, "d.bin", "application/octet-stream", chunkSize = 1000)
        val partial = File(root, "partial")
        com.oshi.messenger.network.v2.V2PartialChunks(partial, "blob-2").put(0, ByteArray(enc.chunks[0].size) { 1 })
        val out = File(root, "o2.bin")
        assertNull(V2BlobClient.decryptResumable(partial, "blob-2", enc.chunks.size, enc.fileKey, enc.fileNonce, enc.manifest, out) { enc.chunks[it] })
        assertEquals(0, com.oshi.messenger.network.v2.V2PartialChunks(partial, "blob-2").count())
        assertNotNull(V2BlobClient.decryptResumable(partial, "blob-2", enc.chunks.size, enc.fileKey, enc.fileNonce, enc.manifest, out) { enc.chunks[it] })
        assertArrayEquals(data, out.readBytes())
    }

    private fun repo(): File {
        var d: File? = File(".").absoluteFile
        while (d != null) { if (File(d, "OSHI-Android").isDirectory) return d; d = d.parentFile }
        error("repo root not found")
    }
}
