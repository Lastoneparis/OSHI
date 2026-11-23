package com.oshi.messenger.network.v2.devsync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * __DEVSYNC_DIRECT_2026_09_22__ Two engines of one account on this JVM, over loopback LAN (TCP +
 * Noise XXpsk0): pairing with the 6-digit code, both-direction diff, merge, media, and the credit
 * window. (Desktop's `DevSyncEndToEndTest` adds the real node relay and interrupted-media resume.)
 */
class DevSyncLoopbackTest {

    private val engines = ArrayList<DevSyncEngine>()
    private val dirs = ArrayList<java.io.File>()

    @After
    fun tearDown() {
        engines.forEach { runCatching { it.stop() } }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun key(n: Int) = Base64.getEncoder().encodeToString(DevSyncCrypto.sha256(byteArrayOf(n.toByte())))

    private fun engine(name: String, account: ByteArray, store: DevSyncStore): DevSyncEngine {
        val dir = kotlin.io.path.createTempDirectory("devsync-android").toFile().also { dirs += it }
        val e = DevSyncEngine(
            DevSyncConfig(name, "android", "test", dir, windowRecords = 8, windowBytes = 128 * 1024),
            account, { DevSyncCrypto.randomBytes(32) }, store, InMemoryStateStore(),
        )
        engines += e
        return e
    }

    private fun waitUntil(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out: $what")
    }

    @Test
    fun twoDevices_pairAndConverge_overLoopbackLan() {
        val bob = key(1)
        val me = key(2)
        val d = SyncConversation.direct(bob)
        val base = 1_758_000_000_000L
        fun m(id: String, i: Int, out: Boolean, status: String = if (out) "sent" else "delivered") =
            SyncMessage(id, d.id, out, if (out) me else bob, timestampMs = base + i * 60_000L, text = "t$i", status = status, read = true)

        val phone = InMemoryDevSyncStore()
        val img = ByteArray(150_000) { (it % 251).toByte() }
        val sha = phone.addMedia(img)
        phone.add(d, *(0 until 300).map { m("P-$it", it, it % 3 == 0) }.toTypedArray())
        phone.add(d, m("IMG", 400, false).copy(text = null, media = SyncMedia("image", "a.jpg", "image/jpeg", img.size.toLong(), sha)))
        phone.add(d, m("BOTH", 500, true, "delivered"))

        val tablet = InMemoryDevSyncStore()
        tablet.add(d, m("both", 500, true, "read"), *(0 until 20).map { m("T-$it", 600 + it, false) }.toTypedArray())

        val account = DevSyncCrypto.randomBytes(32)
        val a = engine("phone", account, phone)
        val b = engine("tablet", account, tablet)
        // Design §15.3 hooks: the phone's mailbox `dsk` rides in HELLO; its signed approval in APPROVAL.
        a.helloExtras = { org.json.JSONObject().put("dsk", "PHONE-DSK") }
        a.approvalExtras = { peerId, _ -> org.json.JSONObject().put("mailbox", org.json.JSONObject().put("by", "phone").put("for", peerId)) }
        val seenDsk = java.util.concurrent.atomic.AtomicReference<String?>()
        val seenMailbox = java.util.concurrent.atomic.AtomicReference<org.json.JSONObject?>()
        b.onPeerHello = { _, hello -> seenDsk.set(hello.optString("dsk", "")) }
        b.onApprovalReceived = { _, body -> seenMailbox.set(body.optJSONObject("mailbox")) }
        // __DEVSYNC_DEVICE_AUTH_2026_09_23__ first-class HELLO `dsk`: the tablet's reaches the phone's hook.
        val tabletDsk = DevSyncRelayAuth.b64(DevSyncRelayAuth.ed25519Public(DevSyncCrypto.randomBytes(32)))
        b.deviceDsk = { tabletDsk }
        val seenPeerDsk = java.util.concurrent.atomic.AtomicReference<Pair<String, String>?>()
        a.onPeerDsk = { peer, dsk -> seenPeerDsk.set(peer to dsk) }
        a.start(lanEnabled = true); b.start(lanEnabled = true)
        waitUntil("listeners") { (a.lan?.port ?: -1) > 0 && (b.lan?.port ?: -1) > 0 }
        a.lan!!.dial("127.0.0.1", b.lan!!.port)
        waitUntil("prompt on both") { a.pendingApprovals().isNotEmpty() && b.pendingApprovals().isNotEmpty() }
        assertEquals(a.pendingApprovals().first().code, b.pendingApprovals().first().code)
        a.approve(b.deviceId, true)

        waitUntil("converged") { phone.count() == 322 && tablet.count() == 322 && tablet.media.containsKey(sha) }
        val digests = { s: InMemoryDevSyncStore -> s.conversations().associate { c -> c.id to SyncCodec.digest(s.messages(c.id).map { it.id to SyncCodec.rev(it) }) } }
        assertEquals(digests(phone), digests(tablet))
        assertEquals("read", phone.get(d.id, "BOTH")!!.status)
        assertTrue(img.contentEquals(tablet.media[sha]))
        for (i in a.sessionsInfo() + b.sessionsInfo()) assertTrue(i.stats.maxUnackedObserved <= 8)
        assertEquals("PHONE-DSK", seenDsk.get())
        assertEquals(b.deviceId to tabletDsk, seenPeerDsk.get())
        assertEquals(b.deviceId, seenMailbox.get()?.optString("for"))
    }
}
