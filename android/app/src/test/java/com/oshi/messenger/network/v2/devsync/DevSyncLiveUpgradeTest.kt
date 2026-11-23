package com.oshi.messenger.network.v2.devsync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * __DEVSYNC_LIVE_UPGRADE_2026_09_23__ The two "known limits" of the first cut, fixed:
 *  1. transport switch DURING a session: relay -> LAN upgrade (Appendix C.11, BYE{lan}) with the
 *     in-flight LIVE records carried over and the media transfer resumed from its partial file;
 *     LAN drop -> relay fallback (EOF, and a half-open LAN detected by the keepalive, C.14);
 *  2. near-instant mirroring: a local change reaches the peer in well under a second (debounced
 *     one-hop LIVE push + SUMMARY), not at the next 5 s poll, and a burst is batched.
 * In-process relay ([FakeRelay]) with the real framing and presence frames; loopback LAN.
 */
class DevSyncLiveUpgradeTest {

    private val engines = CopyOnWriteArrayList<DevSyncEngine>()
    private val dirs = ArrayList<java.io.File>()
    private val closers = ArrayList<() -> Unit>()

    @After
    fun tearDown() {
        engines.forEach { runCatching { it.stop() } }
        closers.forEach { runCatching { it() } }
        dirs.forEach { it.deleteRecursively() }
    }

    private val bob = Base64.getEncoder().encodeToString(DevSyncCrypto.sha256(byteArrayOf(1)))
    private val me = Base64.getEncoder().encodeToString(DevSyncCrypto.sha256(byteArrayOf(2)))
    private val d = SyncConversation.direct(bob)
    private val base = 1_758_000_000_000L

    private fun m(id: String, i: Int, out: Boolean = i % 2 == 0, status: String = if (out) "sent" else "delivered", media: SyncMedia? = null) =
        SyncMessage(id, d.id, out, if (out) me else bob, timestampMs = base + i * 60_000L, text = if (media == null) "t$i" else null, status = status, read = true, media = media)

    /** Two device keys, the first one with the SMALLER deviceId (it is the side that dials the relay). */
    private fun orderedKeys(): Pair<ByteArray, ByteArray> {
        val k1 = DevSyncCrypto.randomBytes(32)
        val k2 = DevSyncCrypto.randomBytes(32)
        val id = { k: ByteArray -> DevSyncKeys.deviceIdHex(DevSyncCrypto.x25519Public(k)) }
        return if (id(k1) < id(k2)) k1 to k2 else k2 to k1
    }

    private val logs = CopyOnWriteArrayList<String>()

    private fun engine(name: String, account: ByteArray, key: ByteArray, store: DevSyncStore, tweak: (DevSyncConfig) -> DevSyncConfig = { it }): DevSyncEngine {
        val dir = kotlin.io.path.createTempDirectory("devsync-live").toFile().also { dirs += it }
        val cfg = tweak(DevSyncConfig(name, "android", "test", dir, relayDelayMs = 50))
        val e = DevSyncEngine(cfg, account, { key }, store, InMemoryStateStore()) { logs += "$name $it"; if (System.getenv("DEVSYNC_VERBOSE") != null) println("$name $it") }
        engines += e
        return e
    }

    private fun waitUntil(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out: $what\n" + logs.takeLast(40).joinToString("\n") + "\n" + engines.joinToString("\n") { e -> e.config.deviceName + " " + e.sessionsInfo().map { "${it.transport}/${it.state}" } })
    }

    private fun syncSession(e: DevSyncEngine, transport: String): SessionInfo? =
        e.sessionsInfo().firstOrNull { it.transport == transport && it.state == DevSyncSession.State.SYNC }

    private fun onlyTransport(e: DevSyncEngine): String? =
        e.sessionsInfo().filter { it.state != DevSyncSession.State.CLOSED }.map { it.transport }.singleOrNull()

    // ------------------------------------------------------------------ fakes

    /** mDNS stand-in: records what the transport advertises; the test decides when a peer is "found". */
    private class FakeDiscovery : LanDiscovery {
        @Volatile var port = 0
        @Volatile var txt: Map<String, String> = emptyMap()
        @Volatile var onFound: ((String, Int, Map<String, String>) -> Unit)? = null
        override fun start(port: Int, txt: Map<String, String>, onFound: (String, Int, Map<String, String>) -> Unit) {
            this.port = port; this.txt = txt; this.onFound = onFound
        }
        override fun updateTxt(txt: Map<String, String>) { this.txt = txt }
        override fun stop() {}
        /** [other] appeared on the LAN. */
        fun sees(other: FakeDiscovery) = onFound!!("127.0.0.1", other.port, other.txt)
    }

    /** The pass-through relay (§5.2) in-process: FIFO, presence frames, `nopeer`, and a stall switch. */
    private class FakeRelay {
        private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-relay").apply { isDaemon = true } }
        private val clients = ConcurrentHashMap<String, RelayListener>()
        @Volatile var frameDelayMs = 0L
        /** true = frames are silently lost (a stalled relay path). */
        @Volatile var dropping = false
        val forwarded = AtomicInteger()

        fun connector() = RelayConnector { id, l ->
            exec.execute {
                clients[id] = l
                l.onOpen()
                l.onText(JSONObject().put("t", "peers").put("d", JSONArray(clients.keys.filter { it != id })).toString())
                for ((o, ol) in clients) if (o != id) ol.onText(JSONObject().put("t", "up").put("d", id).toString())
            }
            object : RelaySocket {
                override fun sendBinary(bytes: ByteArray): Boolean { exec.execute { deliver(id, bytes) }; return true }
                override fun close() {
                    exec.execute {
                        if (clients.remove(id) != null) for ((_, ol) in clients) ol.onText(JSONObject().put("t", "down").put("d", id).toString())
                    }
                }
            }
        }

        private fun deliver(src: String, bytes: ByteArray) {
            if (dropping) return
            if (frameDelayMs > 0) Thread.sleep(frameDelayMs)
            val dest = DevSyncCrypto.hex(bytes.copyOfRange(0, 16))
            val l = clients[dest]
            if (l == null) { clients[src]?.onText(JSONObject().put("t", "nopeer").put("d", dest).toString()); return }
            forwarded.incrementAndGet()
            l.onBinary(DevSyncCrypto.unhex(src) + bytes.copyOfRange(16, bytes.size))
        }

        fun shutdown() = exec.shutdownNow()
    }

    /** A TCP forwarder we can black-hole WITHOUT closing (a half-open LAN path: Wi-Fi gone, no RST). */
    private class BlackholeProxy(private val targetPort: Int) {
        private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", 0)) }
        @Volatile var blackhole = false
        private val sockets = CopyOnWriteArrayList<Socket>()
        val port: Int get() = server.localPort

        init {
            Thread({
                while (!server.isClosed) {
                    val inbound = try { server.accept() } catch (e: IOException) { return@Thread }
                    val outbound = Socket("127.0.0.1", targetPort)
                    sockets += inbound; sockets += outbound
                    pipe(inbound, outbound); pipe(outbound, inbound)
                }
            }, "proxy-accept").apply { isDaemon = true; start() }
        }

        private fun pipe(from: Socket, to: Socket) = Thread({
            val buf = ByteArray(16 * 1024)
            try {
                val i = from.getInputStream(); val o = to.getOutputStream()
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    if (!blackhole) { o.write(buf, 0, n); o.flush() }
                }
            } catch (e: IOException) {
            }
        }, "proxy-pipe").apply { isDaemon = true; start() }

        fun close() { runCatching { server.close() }; sockets.forEach { runCatching { it.close() } } }
    }

    // ------------------------------------------------------------------ 1. relay -> LAN -> relay

    @Test
    fun relaySession_upgradesToLanMidMedia_carriesLive_resumesMedia_thenFallsBackWhenLanDrops() {
        val phone = InMemoryDevSyncStore()
        val img = ByteArray(1_500_000) { (it * 7 % 253).toByte() }
        val sha = phone.addMedia(img)
        phone.add(d, *(0 until 20).map { m("P-$it", it) }.toTypedArray())
        phone.add(d, m("IMG", 30, out = false, media = SyncMedia("image", "a.jpg", "image/jpeg", img.size.toLong(), sha)))
        val tablet = InMemoryDevSyncStore()
        tablet.add(d, m("T-1", 40, out = false))

        val relay = FakeRelay().also { r -> closers += { r.shutdown() } }
        relay.frameDelayMs = 15 // ~26 media chunks: the transfer lasts long enough to switch mid-way
        val account = DevSyncCrypto.randomBytes(32)
        val (ka, kb) = orderedKeys()
        val a = engine("phone", account, ka, phone)
        val b = engine("tablet", account, kb, tablet)
        val discA = FakeDiscovery(); val discB = FakeDiscovery()
        a.start(lanDiscovery = discA, relayConnector = relay.connector())
        b.start(lanDiscovery = discB, relayConnector = relay.connector())

        // Paired and syncing over the RELAY (LAN not discovered yet).
        waitUntil("pairing prompts over the relay") { a.pendingApprovals().isNotEmpty() && b.pendingApprovals().isNotEmpty() }
        assertEquals("relay", a.pendingApprovals().first().transport)
        a.approve(b.deviceId, true)
        waitUntil("media transfer started over the relay") { b.partialFile(sha).length() > 0 }
        val partialAtSwitch = b.partialFile(sha).length()

        // The relay path stalls; a message is mirrored (LIVE) meanwhile: it is in flight, unacknowledged.
        relay.dropping = true
        val live = m("LIVE-1", 50, out = true)
        phone.add(d, live)
        a.onLocalMessages(d, listOf(live))
        Thread.sleep(100)

        // The tablet appears on the LAN: the phone dials it, the LAN session wins, the relay one retires.
        discA.sees(discB)
        waitUntil("both on LAN only") { onlyTransport(a) == "lan" && onlyTransport(b) == "lan" }
        waitUntil("media completed over LAN, live message delivered") {
            tablet.media[sha]?.contentEquals(img) == true && tablet.get(d.id, "LIVE-1") != null
        }
        assertTrue("the transfer had started over the relay", partialAtSwitch > 0)
        val lanStats = syncSession(b, "lan")!!.stats
        assertTrue("media RESUMED from the partial file (${lanStats.mediaBytes} < ${img.size})", lanStats.mediaBytes in 1 until img.size.toLong())
        assertTrue("the in-flight LIVE record moved to the LAN session: $logs", logs.any { it.startsWith("phone") && it.contains("relay -> lan") && !it.contains("(0 live") })
        waitUntil("converged") { phone.count() == tablet.count() }

        // LAN drops (network change on the phone): back to the relay at once, not at the next sweep.
        relay.dropping = false
        a.disconnectAll("dropped")
        waitUntil("both back on the relay", 5_000) { syncSession(a, "relay") != null && syncSession(b, "relay") != null && onlyTransport(b) == "relay" }
        val later = m("AFTER-DROP", 60, out = false)
        tablet.add(d, later)
        b.onLocalStateChanged()
        waitUntil("new message crosses over the relay", 3_000) { phone.get(d.id, "AFTER-DROP") != null }
    }

    // ------------------------------------------------------------------ 1b. half-open LAN

    @Test
    fun halfOpenLan_isDetectedByKeepalive_andTheRelayTakesOver() {
        val phone = InMemoryDevSyncStore().apply { add(d, m("P-1", 1)) }
        val tablet = InMemoryDevSyncStore().apply { add(d, m("T-1", 2)) }
        val relay = FakeRelay().also { r -> closers += { r.shutdown() } }
        val account = DevSyncCrypto.randomBytes(32)
        val (ka, kb) = orderedKeys()
        // The phone gives up on a silent LAN after 600 ms; the tablet would wait 20 s. When the phone's
        // relay session reaches the tablet, the tablet's LAN session is "stale" (C.14) and loses.
        // relayDelayMs 5 s: the first session is the LAN one (the relay only dials when there is none).
        val a = engine("phone", account, ka, phone) { it.copy(lanKeepaliveMs = 100, lanIdleTimeoutMs = 600, relayDelayMs = 5_000) }
        val b = engine("tablet", account, kb, tablet) { it.copy(lanKeepaliveMs = 100, lanIdleTimeoutMs = 20_000, relayDelayMs = 5_000) }
        a.start(lanEnabled = true, relayConnector = relay.connector())
        b.start(lanEnabled = true, relayConnector = relay.connector())
        waitUntil("listeners") { (a.lan?.port ?: -1) > 0 && (b.lan?.port ?: -1) > 0 }
        val proxy = BlackholeProxy(b.lan!!.port).also { p -> closers += { p.close() } }
        a.lan!!.dial("127.0.0.1", proxy.port)
        waitUntil("pairing prompts") { a.pendingApprovals().isNotEmpty() && b.pendingApprovals().isNotEmpty() }
        assertEquals("lan", a.pendingApprovals().first().transport)
        a.approve(b.deviceId, true)
        waitUntil("converged over LAN") { phone.count() == 2 && tablet.count() == 2 && onlyTransport(a) == "lan" }
        Thread.sleep(400) // keepalives exchanged: both sides now know the other keeps alive

        proxy.blackhole = true
        waitUntil("relay took over on both sides", 8_000) {
            onlyTransport(a) == "relay" && onlyTransport(b) == "relay" && syncSession(b, "relay") != null
        }
        assertTrue(logs.any { it.startsWith("phone") && it.contains("LAN silent") })
        assertTrue("the tablet retired its stale LAN session for the relay: $logs", logs.any { it.startsWith("tablet") && it.contains("lan -> relay") })
        tablet.add(d, m("T-2", 3))
        b.onLocalStateChanged()
        waitUntil("sync continues over the relay", 3_000) { phone.get(d.id, "T-2") != null }
    }

    // ------------------------------------------------------------------ 2. latency

    @Test
    fun localChange_reachesThePeerInWellUnderASecond_burstsAreBatched_andThereIsNoEchoLoop() {
        val phone = InMemoryDevSyncStore().apply { add(d, *(0 until 50).map { m("P-$it", it) }.toTypedArray()) }
        val tablet = InMemoryDevSyncStore()
        val account = DevSyncCrypto.randomBytes(32)
        val (ka, kb) = orderedKeys()
        val a = engine("phone", account, ka, phone)
        val b = engine("tablet", account, kb, tablet)
        a.start(lanEnabled = true); b.start(lanEnabled = true)
        waitUntil("listeners") { (a.lan?.port ?: -1) > 0 && (b.lan?.port ?: -1) > 0 }
        a.lan!!.dial("127.0.0.1", b.lan!!.port)
        waitUntil("prompt") { a.pendingApprovals().isNotEmpty() }
        a.approve(b.deviceId, true)
        waitUntil("converged") { tablet.count() == 50 && syncSession(a, "lan")?.pullComplete == true && syncSession(b, "lan")?.pullComplete == true }
        Thread.sleep(300)

        // One new message, reported the way the platforms report it (no payload, just "something changed").
        val t0 = System.nanoTime()
        phone.add(d, m("NEW-1", 100, out = true))
        a.onLocalStateChanged()
        waitUntil("new message mirrored", 3_000) { tablet.get(d.id, "NEW-1") != null }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("devsync live: new message mirrored over LAN in $ms ms")
        assertTrue("mirrored in ${ms} ms (the old poll was 5 s)", ms < 1_000)

        // A status change on an OLD message (rev change, not a new id) travels through the SUMMARY diff.
        val t1 = System.nanoTime()
        phone.add(d, m("P-0", 0, out = true, status = "read"))
        a.onLocalChanged(listOf(d.id))
        waitUntil("status mirrored", 3_000) { tablet.get(d.id, "P-0")?.status == "read" }
        assertTrue("status mirrored in ${(System.nanoTime() - t1) / 1_000_000} ms", (System.nanoTime() - t1) / 1_000_000 < 1_000)

        // A contact alias (state record) too.
        phone.putContact(SyncContact(bob, alias = "Bobby", aliasAtMs = base))
        a.onLocalStateChanged()
        waitUntil("alias mirrored", 3_000) { tablet.contacts().firstOrNull { it.publicKey == bob }?.alias == "Bobby" }

        // A burst of 40 messages, each reported separately, goes out in a handful of records.
        Thread.sleep(300)
        val before = syncSession(a, "lan")!!.stats.recordsOut
        for (i in 0 until 40) { phone.add(d, m("BURST-$i", 200 + i, out = true)); a.onLocalStateChanged(); Thread.sleep(3) }
        waitUntil("burst mirrored", 3_000) { (0 until 40).all { tablet.get(d.id, "BURST-$it") != null } }
        Thread.sleep(400)
        val sent = syncSession(a, "lan")!!.stats.recordsOut - before
        assertTrue("burst batched: $sent records for 40 changes", sent < 15)

        // The tablet applying them does not bounce anything back and forth: both go quiet.
        b.onLocalStateChanged() // as its own store-change hook would, after applying
        Thread.sleep(600)
        val qa = syncSession(a, "lan")!!.stats.recordsOut; val qb = syncSession(b, "lan")!!.stats.recordsOut
        Thread.sleep(800)
        assertEquals("no echo loop (phone)", qa, syncSession(a, "lan")!!.stats.recordsOut)
        assertEquals("no echo loop (tablet)", qb, syncSession(b, "lan")!!.stats.recordsOut)
        val digests = { s: InMemoryDevSyncStore -> s.conversations().associate { c -> c.id to SyncCodec.digest(s.messages(c.id).map { it.id to SyncCodec.rev(it) }) } }
        assertEquals(digests(phone), digests(tablet))
    }

    // ------------------------------------------------------------------ 3. media referenced mid-session

    /**
     * __DEVSYNC_MEDIA_NOW_2026_09_23__ A group picture / profile photo whose reference arrives in a
     * GROUPS / PROFILE record re-sent mid-session (C.15) is fetched on that session at once; it used
     * to wait for the next session (the pending-media queue was only refilled at session start).
     * Also: a local read made right after a flush is mirrored at the next flush, not at the sweep.
     */
    @Test
    fun pictureChangedMidSession_isFetchedOnTheOpenSession_andReadStateIsNotHeldBack() {
        val gid = "11111111-2222-3333-4444-555555555555"
        val g0 = SyncGroup(groupId = gid, name = "Team", nameAtMs = base, members = listOf(me, bob), updatedAtMs = base)
        val phone = InMemoryDevSyncStore(listOf(g0)).apply { add(d, m("P-1", 1)) }
        val tabletStore = InMemoryDevSyncStore(listOf(g0))
        lateinit var b: DevSyncEngine
        // Like the platform stores: a newer profile photo is queued by reference from putProfile.
        val tablet = object : DevSyncStore by tabletStore {
            override fun putProfile(profile: SyncProfile) {
                tabletStore.putProfile(profile)
                profile.extra.optJSONObject("photo")?.let { ph ->
                    b.pendingMedia.add(ph.getString("sha256"), ph.optLong("size", -1), "profile", "#photo", profile.updatedAtMs)
                }
            }
        }
        val account = DevSyncCrypto.randomBytes(32)
        val (ka, kb) = orderedKeys()
        val a = engine("phone", account, ka, phone)
        b = engine("tablet", account, kb, tablet)
        a.start(lanEnabled = true); b.start(lanEnabled = true)
        waitUntil("listeners") { (a.lan?.port ?: -1) > 0 && (b.lan?.port ?: -1) > 0 }
        a.lan!!.dial("127.0.0.1", b.lan!!.port)
        waitUntil("prompt") { a.pendingApprovals().isNotEmpty() }
        a.approve(b.deviceId, true)
        waitUntil("converged") { tabletStore.count() == 1 && syncSession(b, "lan")?.let { it.pullComplete && it.mediaComplete } == true }
        Thread.sleep(300)

        // The user sets a group picture on the phone, mid-session.
        val pic = ByteArray(150_000) { (it * 31).toByte() }
        val picSha = phone.addMedia(pic)
        phone.putGroup(g0.copy(avatar = SyncAvatar(picSha, pic.size.toLong(), "image/jpeg"), avatarAtMs = base + 1, updatedAtMs = base + 1))
        a.onLocalStateChanged()
        waitUntil("group picture fetched on the open session", 3_000) {
            tabletStore.attached.any { it.first == "group:${gid.uppercase()}" && it.second == DevSyncStore.AVATAR_REF && it.third == picSha }
        }

        // Then a new profile photo.
        val photo = ByteArray(70_000) { (it * 7).toByte() }
        val photoSha = phone.addMedia(photo)
        phone.putProfile(SyncProfile("Hugo", base + 2, JSONObject().put("photo", JSONObject().put("sha256", photoSha).put("size", photo.size).put("mime", "image/jpeg"))))
        a.onLocalStateChanged()
        waitUntil("profile photo fetched on the open session", 3_000) {
            tabletStore.attached.any { it.second == "#photo" && it.third == photoSha }
        }
        assertEquals("one session did it all", 1, a.sessionsInfo().count { it.state == DevSyncSession.State.SYNC })

        // Read state: two local reads 100 ms apart both reach the peer promptly.
        phone.putReadState(SyncReadState(d.id, base + 60_000, base + 3))
        a.onLocalStateChanged()
        waitUntil("first read mirrored", 2_000) { tabletStore.readStates().any { it.conv == d.id && it.readUpToMs == base + 60_000 } }
        phone.putReadState(SyncReadState(d.id, base + 120_000, base + 4))
        a.onLocalStateChanged()
        waitUntil("second read mirrored (no stale cache)", 2_000) { tabletStore.readStates().any { it.conv == d.id && it.readUpToMs == base + 120_000 } }
    }
}
