package com.oshi.messenger.network.auth

import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.v2.V2Signer
import com.oshi.messenger.network.web.WebSessionManager
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * __SERVER_CLOCK_EVERY_SIGNER_2026_11_23__ The estimator's rules (median, RTT, outliers,
 * persistence, clamp, local clock jumps) and the proof that every signer reads it.
 *
 * Production case: an Android 1.6.99 phone made 75 push requests in 24 h, all refused as
 * "expired" because its clock was more than 30 s fast.
 */
class ServerClockEveryKindTest {

    @After fun reset() = ServerClock.resetForTest()

    private class FakeClocks(var wall: Long = 1_800_000_000_000L, var mono: Long = 10_000L) {
        fun advance(ms: Long) { wall += ms; mono += ms }
    }

    private class MemStore(var value: Long? = null) : ServerClock.Store {
        var saves = 0
        override fun load(): Long? = value
        override fun save(offsetMs: Long) { value = offsetMs; saves++ }
    }

    private fun estimator(c: FakeClocks, logs: MutableList<String> = mutableListOf()) =
        ServerClock.Estimator(wallMs = { c.wall }, monoMs = { c.mono }, log = { logs += it })

    /** One response whose server clock is `offset` ahead of local, with round trip `rtt`. */
    private fun ServerClock.Estimator.feed(c: FakeClocks, offset: Long, rtt: Long = 100, refusal: Boolean = false) {
        val sent = c.wall
        c.advance(rtt)
        val received = c.wall
        val serverAtMid = sent + rtt / 2 + offset
        val date = serverAtMid - 500 // the +500 centring of a truncated Date
        if (refusal) observeRefusal(date, sent, received) else observe(date, sent, received)
    }

    // ---- RTT -----------------------------------------------------------------

    @Test fun `the sample is taken at the midpoint of the round trip`() {
        // sent 1000, received 3000, server says 2000+60_000-500 → offset 60 s exactly
        assertEquals(60_000L, ServerClock.sampleOffset(61_500L, 1_000L, 3_000L))
    }

    @Test fun `a round trip over 5 s is ignored`() {
        val c = FakeClocks(); val e = estimator(c)
        e.feed(c, offset = 90_000, rtt = 5_001)
        assertEquals(0, e.sampleCount())
        assertEquals(0L, e.rawOffsetMs())
        e.feed(c, offset = 90_000, rtt = 4_900)
        assertTrue(abs(e.rawOffsetMs() - 90_000) <= 1)
    }

    // ---- outliers / median ---------------------------------------------------

    @Test fun `one outlier does not move the median`() {
        val c = FakeClocks(); val e = estimator(c)
        repeat(4) { e.feed(c, offset = 45_000) }
        e.feed(c, offset = -600_000)          // a proxy's stale Date
        assertTrue(abs(e.rawOffsetMs() - 45_000) <= 1)
    }

    @Test fun `the window keeps the last samples, so a real change wins after a few answers`() {
        val c = FakeClocks(); val e = estimator(c)
        repeat(ServerClock.WINDOW) { e.feed(c, offset = 45_000) }
        repeat(ServerClock.WINDOW / 2 + 1) { e.feed(c, offset = 2_000) }
        assertTrue(abs(e.rawOffsetMs() - 2_000) <= 1)
        assertEquals(ServerClock.WINDOW, e.sampleCount())
    }

    @Test fun `median of an even window is the mean of the middle two`() {
        assertEquals(15L, ServerClock.median(listOf(30, 10, 20, 0)))
        assertEquals(20L, ServerClock.median(listOf(30, 10, 20)))
    }

    @Test fun `a refusal whose Date disagrees restarts the estimate at once`() {
        val c = FakeClocks(); val e = estimator(c)
        repeat(ServerClock.WINDOW) { e.feed(c, offset = 0) }
        e.feed(c, offset = -95_000, refusal = true)
        assertTrue(abs(e.rawOffsetMs() + 95_000) <= 1)
        assertEquals(1, e.sampleCount())
    }

    @Test fun `a refusal that agrees with the estimate is an ordinary sample`() {
        val c = FakeClocks(); val e = estimator(c)
        repeat(3) { e.feed(c, offset = 40_000) }
        e.feed(c, offset = 41_000, refusal = true)
        assertEquals(4, e.sampleCount())
    }

    // ---- persistence -----------------------------------------------------------

    @Test fun `the persisted offset is used from the first request after a restart`() {
        val c = FakeClocks()
        val store = MemStore()
        estimator(c).apply { attach(store); repeat(3) { feed(c, offset = -80_000) } }
        assertTrue(abs(store.value!! + 80_000) <= 1)

        val restarted = estimator(c)
        restarted.attach(store)
        assertTrue(abs(restarted.rawOffsetMs() + 80_000) <= 1)
        assertEquals(0, restarted.sampleCount())
    }

    @Test fun `the first fresh sample replaces the persisted seed (re-learn quickly)`() {
        val c = FakeClocks()
        val e = estimator(c)
        e.attach(MemStore(-80_000))
        e.feed(c, offset = 0)   // the user has since fixed the clock
        assertTrue(abs(e.rawOffsetMs()) <= 1)
    }

    @Test fun `the store is not rewritten for sub-second jitter`() {
        val c = FakeClocks(); val store = MemStore(); val e = estimator(c)
        e.attach(store)
        repeat(6) { i -> e.feed(c, offset = 60_000L + (i % 3) * 100) }
        assertEquals(1, store.saves)
    }

    @Test fun `a broken store never breaks the clock`() {
        val c = FakeClocks(); val e = estimator(c)
        e.attach(object : ServerClock.Store {
            override fun load(): Long? = error("disk")
            override fun save(offsetMs: Long) = error("disk")
        })
        e.feed(c, offset = 50_000)
        assertTrue(abs(e.rawOffsetMs() - 50_000) <= 1)
    }

    // ---- clamp / logging -------------------------------------------------------

    @Test fun `the offset never goes beyond 7 days`() {
        val c = FakeClocks(); val e = estimator(c)
        e.feed(c, offset = 400L * 24 * 3600 * 1000)
        assertEquals(ServerClock.MAX_OFFSET_MS, e.rawOffsetMs())
        assertEquals(-ServerClock.MAX_OFFSET_MS, ServerClock.effectiveOffset(-30L * 24 * 3600 * 1000))
        val restored = estimator(c); restored.attach(MemStore(-90L * 24 * 3600 * 1000))
        assertEquals(-ServerClock.MAX_OFFSET_MS, restored.rawOffsetMs())
    }

    @Test fun `an offset above 30 s is logged, a small one is not`() {
        val c = FakeClocks(); val logs = mutableListOf<String>(); val e = estimator(c, logs)
        e.feed(c, offset = 20_000)
        assertTrue(logs.isEmpty())
        e.feed(c, offset = -45_000, refusal = true)
        assertEquals(1, logs.size)
        e.feed(c, offset = -45_000)  // same value again: no spam
        assertEquals(1, logs.size)
    }

    // ---- local clock jumps -----------------------------------------------------

    @Test fun `a local clock correction during the process is subtracted from the offset`() {
        val c = FakeClocks(); val e = estimator(c)
        repeat(3) { e.feed(c, offset = -120_000) }   // phone 2 min fast
        c.wall -= 120_000                            // NTP fixes it; monotonic unaffected
        assertTrue(abs(e.rawOffsetMs()) <= 1)
    }

    // ---- every signer reads ServerClock ------------------------------------------

    private val seed = ByteArray(32) { (it + 1).toByte() }
    private val priv = Ed25519PrivateKeyParameters(seed, 0)
    private val pub = priv.generatePublicKey().encoded
    private fun sign(msg: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, priv); update(msg, 0, msg.size); generateSignature()
    }

    /** Teach the global clock that the server is 2 min BEHIND this phone (phone fast). */
    private fun phoneTwoMinutesFast(): Long {
        val now = System.currentTimeMillis()
        ServerClock.observe(now - 120_000 - 500, now - 10, now + 10)
        return -120_000L
    }

    private fun assertServerTime(ts: Long, offset: Long) {
        val expected = System.currentTimeMillis() + offset
        assertTrue("timestamp $ts is not server time $expected", abs(ts - expected) < 2_000)
    }

    @Test fun `call and push signer (x-oshi-timestamp) uses ServerClock`() {
        val off = phoneTwoMinutesFast()
        val h = CallPushAuth.headers("POST", "/push/register-device", ByteArray(0), signingPublicKey = pub, sign = ::sign)
        assertServerTime(h.getValue(CallPushAuth.HEADER_TIMESTAMP).toLong(), off)
    }

    @Test fun `v2 signer (messages, blobs, keys, devices, mail, reports) uses ServerClock`() {
        val off = phoneTwoMinutesFast()
        val cm = CryptoManager().apply { setSigningKeyPair(seed, pub) }
        val h = V2Signer(cm).sign("GET", "/v2/messages/x", ByteArray(0))
        assertServerTime(h.getValue("x-oshi-timestamp").toLong(), off)
    }

    @Test fun `web relay mobile_auth timestamp uses ServerClock`() {
        val off = phoneTwoMinutesFast()
        assertServerTime(WebSessionManager.authTimestamp().toLong(), off)
    }

    @Test fun `a healthy clock signs with the local clock unchanged`() {
        val now = System.currentTimeMillis()
        ServerClock.observe(now + 1_200 - 500, now - 10, now + 10)
        assertEquals(1_000L, ServerClock.signingNowMs(1_000L))
    }

    // ---- interceptors: learn from any OSHI answer, re-sign once on a clock refusal ----

    private fun httpDate(ms: Long): String =
        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC))

    private fun answer(req: Request, code: Int, serverNow: Long) = Response.Builder()
        .request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
        .header("Date", httpDate(serverNow))
        .body("{}".toResponseBody("application/json".toMediaType())).build()

    @Test fun `call-push interceptor re-signs once with the refusal's Date`() {
        val seen = mutableListOf<Request>()
        val server = Interceptor { chain ->
            val r = chain.request(); seen += r
            val ts = r.header(CallPushAuth.HEADER_TIMESTAMP)!!.toLong()
            val serverNow = System.currentTimeMillis() - 120_000   // phone 2 min fast
            answer(r, if (abs(ts - serverNow) > 30_000) 403 else 200, serverNow)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(CallPushAuthInterceptor("t") { m, p, b -> CallPushAuth.headers(m, p, b, signingPublicKey = pub, sign = ::sign) })
            .addInterceptor(server).build()
        val resp = client.newCall(Request.Builder().url("https://oshi-messenger.com/push/send-push")
            .post("{}".toRequestBody("application/json".toMediaType())).build()).execute()
        assertEquals(200, resp.code)
        assertEquals(2, seen.size)
        // and the NEXT request is right the first time
        client.newCall(Request.Builder().url("https://oshi-messenger.com/api/call/relay-token")
            .post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use { assertEquals(200, it.code) }
        assertEquals(3, seen.size)
    }

    @Test fun `v2 sampling re-signs a refused request once, through the same delegate`() {
        val calls = mutableListOf<Request>()
        var resigned = 0
        val serverNow = { System.currentTimeMillis() - 120_000 }
        val delegate = Interceptor { chain ->
            val r = chain.request(); calls += r
            val ts = r.header("x-oshi-timestamp")!!.toLong()
            answer(r, if (abs(ts - serverNow()) > 30_000) 401 else 200, serverNow())
        }
        val fresh = { mapOf("x-oshi-signature" to "sig2", "x-oshi-timestamp" to ServerClock.signingNowMs().toString()) }
        V2Signer.remember("sig1") { resigned++; fresh() }
        val client = OkHttpClient.Builder().addInterceptor(ServerClock.sampling(delegate)).build()
        val req = Request.Builder().url("https://oshi-messenger.com/v2/messages")
            .header("x-oshi-signature", "sig1").header("x-oshi-timestamp", System.currentTimeMillis().toString()).build()
        client.newCall(req).execute().use { assertEquals(200, it.code) }
        assertEquals(2, calls.size)
        assertEquals(1, resigned)
        assertEquals("sig2", calls[1].header("x-oshi-signature"))
        assertNull(V2Signer.resignFor("sig1"))   // one use only
    }

    @Test fun `a device-mode request is not re-signed (its second signature shares the timestamp)`() {
        val calls = mutableListOf<Request>()
        val delegate = Interceptor { chain -> calls += chain.request(); answer(chain.request(), 401, System.currentTimeMillis() - 120_000) }
        V2Signer.remember("sigD") { mapOf("x-oshi-signature" to "x") }
        val client = OkHttpClient.Builder().addInterceptor(ServerClock.sampling(delegate)).build()
        client.newCall(Request.Builder().url("https://oshi-messenger.com/v2/messages")
            .header("x-oshi-signature", "sigD").header("x-oshi-device-signature", "d").build()).execute().use { assertEquals(401, it.code) }
        assertEquals(1, calls.size)
    }

    @Test fun `third-party Date headers are never sampled`() {
        val terminal = Interceptor { chain -> answer(chain.request(), 200, System.currentTimeMillis() - 3_600_000) }
        val client = OkHttpClient.Builder().addInterceptor(ServerClock.interceptor).addInterceptor(terminal).build()
        client.newCall(Request.Builder().url("https://tile.openstreetmap.org/1/1/1.png").build()).execute().close()
        assertEquals(0L, ServerClock.rawOffsetMs())
        client.newCall(Request.Builder().url("https://oshi-messenger.com/mail/v1/x").build()).execute().close()
        assertTrue(abs(ServerClock.rawOffsetMs() + 3_600_000) < 2_000)
    }
}
