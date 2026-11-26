package com.oshi.messenger.network.auth

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import kotlin.math.abs

/**
 * __CALL_CLOCK_SKEW_2026_11_21__ The offset between this phone's wall clock and the
 * OSHI server's, learned from the `Date` header of OSHI server responses.
 *
 * ## Why this exists (production evidence, 21/11/2026)
 *
 * An Android 1.6.99 phone (`k#063ffd`) signed EVERY request with an `x-oshi-timestamp`
 * the server judged "expired" (outside ±30 s) — its clock was off. Two things broke:
 *
 *  1. **It never rang.** Polled call signals carry the SERVER's `Date.now()` as
 *     `timestamp`, and `EnhancedCallManager.handleCallSignal` dropped anything older
 *     than 60 s by the LOCAL clock. A phone more than a minute fast therefore threw away
 *     every offer it polled — three calls to it in eight minutes (19c9e521, 8d20960d,
 *     7b4df590) were fetched by its poll and never produced a ring, an ICE candidate or
 *     a decline, while all of its own outgoing calls connected (signals reaching it over
 *     the WebSocket are stamped with the local clock and so were never "stale").
 *  2. **`/relay-token` refused it** (`REFUSED expired`, enforced today), so it fell back
 *     to token-less UDP registration — the shape the relay refuses as `key-held` and the
 *     one that stops working entirely when media auth goes to enforce.
 *
 * The server is the authority on both freshness checks, so the client simply reads the
 * server's clock: rebase server timestamps into local time, and sign with server time.
 *
 * ## __SERVER_CLOCK_EVERY_SIGNER_2026_11_23__
 *
 * 23/11: the same 1.6.99 phone made 75 push requests in 24 h, every one refused as
 * skewed. One sample from a call/push answer was not enough: the first request of every
 * process signed with the raw clock, `/v2/` answers taught nothing, and one slow sample
 * could move the estimate. Now:
 *  - the estimate is the MEDIAN of the last [WINDOW] samples (one outlier moves nothing);
 *  - a sample is the server `Date` against the MIDPOINT of the round trip, and a round
 *    trip slower than [MAX_SAMPLE_RTT_MS] is dropped;
 *  - every OSHI client feeds it ([interceptor], [sampling]), not just call/push;
 *  - the estimate is persisted ([Store]) so a restart signs right from its first
 *    request, but the persisted value is only a seed: the first fresh sample replaces it;
 *  - a 401/403 whose `Date` disagrees with the estimate by ≥ [SIGNING_THRESHOLD_MS]
 *    replaces the window ([observeRefusal]) so the one re-sign uses it;
 *  - a jump of the local wall clock within the process (NTP fix, manual change) is
 *    detected against the monotonic clock and subtracted from the estimate;
 *  - the offset is clamped to ±[MAX_OFFSET_MS] (7 days) and logged above
 *    [LOG_THRESHOLD_MS].
 *
 * Only SIGNED timestamps use it. Message timestamps the user sees and ordering logic
 * keep the local clock.
 *
 * Pure JVM ([Estimator] is a plain class with injected clocks) so every rule is a test.
 */
object ServerClock {

    /**
     * Below this the correction is not applied to SIGNING. `Date` has one-second
     * resolution and a sample carries up to RTT/2 of error; a healthy NTP phone must keep
     * signing with its own clock exactly as before. Well inside the server's ±30 s.
     */
    const val SIGNING_THRESHOLD_MS = 5_000L

    /** A sample from a round trip slower than this is too imprecise to keep. */
    const val MAX_SAMPLE_RTT_MS = 5_000L

    /** The offset is never allowed beyond ±7 days (a garbage `Date`, a dead RTC). */
    const val MAX_OFFSET_MS = 7L * 24 * 3600 * 1000

    /** Above this the server would refuse our signatures without the correction: log it. */
    const val LOG_THRESHOLD_MS = 30_000L

    /** Samples kept for the median. */
    const val WINDOW = 7

    /** A wall-vs-monotonic disagreement above this is a local clock jump, not drift. */
    const val JUMP_THRESHOLD_MS = 2_000L

    /** Where the estimate survives a restart. Android: SharedPreferences (ServerClockStore). */
    interface Store {
        fun load(): Long?
        fun save(offsetMs: Long)
    }

    /**
     * The estimator. One instance backs [ServerClock]; tests build their own with fake
     * clocks. Thread-safe (every entry point is synchronized; the work is a few longs).
     */
    class Estimator(
        private val wallMs: () -> Long = System::currentTimeMillis,
        private val monoMs: () -> Long = { System.nanoTime() / 1_000_000 },
        private val log: (String) -> Unit = {},
    ) {
        private val samples = ArrayDeque<Long>()
        private var seed: Long? = null
        private var store: Store? = null
        private var lastSaved: Long? = null
        private var lastLogged: Long? = null
        private var anchorWall = 0L
        private var anchorMono = 0L
        private var anchored = false

        /** Load the persisted estimate as a seed (replaced by the first fresh sample). */
        @Synchronized
        fun attach(store: Store) {
            this.store = store
            val loaded = runCatching { store.load() }.getOrNull()
            if (loaded != null && samples.isEmpty()) {
                seed = clamp(loaded)
                lastSaved = loaded
                anchor()
            }
        }

        /** Record one ordinary (2xx/3xx) response. */
        @Synchronized
        fun observe(serverDateMs: Long, sentAtMs: Long, receivedAtMs: Long) {
            val s = sampleOffset(serverDateMs, sentAtMs, receivedAtMs) ?: return
            correctForJump()
            samples.addLast(clamp(s))
            while (samples.size > WINDOW) samples.removeFirst()
            seed = null
            anchor()
            changed()
        }

        /**
         * Record a 401/403. If its `Date` disagrees with the estimate by ≥ 5 s the refusal
         * was (very likely) our clock: the window restarts from this sample, so the caller's
         * one re-sign uses it. Otherwise it is an ordinary sample.
         */
        @Synchronized
        fun observeRefusal(serverDateMs: Long, sentAtMs: Long, receivedAtMs: Long) {
            val s = sampleOffset(serverDateMs, sentAtMs, receivedAtMs) ?: return
            if (abs(clamp(s) - current()) >= SIGNING_THRESHOLD_MS) {
                samples.clear()
                seed = null
            }
            observe(serverDateMs, sentAtMs, receivedAtMs)
        }

        /** server − local, in ms. 0 until a sample (or a persisted seed) exists. */
        @Synchronized
        fun rawOffsetMs(): Long {
            correctForJump()
            return current()
        }

        @Synchronized
        internal fun sampleCount(): Int = samples.size

        @Synchronized
        internal fun reset() {
            samples.clear(); seed = null; store = null; lastSaved = null; lastLogged = null; anchored = false
        }

        private fun current(): Long = if (samples.isEmpty()) seed ?: 0L else median(samples.toList())

        private fun anchor() {
            anchorWall = wallMs(); anchorMono = monoMs(); anchored = true
        }

        /** The local wall clock moved by `jump` relative to real time: so did the offset, the other way. */
        private fun correctForJump() {
            if (!anchored) return
            val jump = (wallMs() - anchorWall) - (monoMs() - anchorMono)
            if (abs(jump) <= JUMP_THRESHOLD_MS) return
            val shifted = samples.map { clamp(it - jump) }
            samples.clear(); samples.addAll(shifted)
            seed = seed?.let { clamp(it - jump) }
            log("ServerClock: local clock jumped by ${jump} ms; estimate now ${current()} ms")
            anchor()
            changed()
        }

        private fun changed() {
            val now = current()
            if (abs(now) > LOG_THRESHOLD_MS && lastLogged.let { it == null || abs(it - now) >= SIGNING_THRESHOLD_MS }) {
                log("ServerClock: this phone's clock is ${-now} ms off the OSHI server; signing with server time")
                lastLogged = now
            } else if (abs(now) <= LOG_THRESHOLD_MS) lastLogged = null
            val st = store ?: return
            if (lastSaved.let { it == null || abs(it - now) >= 1_000L }) {
                runCatching { st.save(now) }
                lastSaved = now
            }
        }
    }

    @Volatile
    private var estimator = Estimator()

    /** Called once at startup (OSHIApplication via CallPushSigning.init). */
    fun install(store: Store, log: (String) -> Unit) {
        val e = Estimator(log = log)
        e.attach(store)
        estimator = e
    }

    /**
     * Offset implied by one response. `Date` is truncated to the second, so +500 ms
     * centres it; the local instant is the midpoint of the round trip.
     * Returns null when the sample is unusable (no Date, inverted or > 5 s round trip).
     */
    fun sampleOffset(serverDateMs: Long, sentAtMs: Long, receivedAtMs: Long): Long? {
        if (serverDateMs <= 0L || sentAtMs <= 0L || receivedAtMs < sentAtMs) return null
        if (receivedAtMs - sentAtMs > MAX_SAMPLE_RTT_MS) return null
        val localMid = sentAtMs + (receivedAtMs - sentAtMs) / 2
        return serverDateMs + 500L - localMid
    }

    fun clamp(offsetMs: Long): Long = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)

    fun median(values: List<Long>): Long {
        require(values.isNotEmpty())
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /** The correction signing applies: none for a sane clock, the full offset otherwise. */
    fun effectiveOffset(rawOffsetMs: Long): Long =
        if (abs(rawOffsetMs) >= SIGNING_THRESHOLD_MS) clamp(rawOffsetMs) else 0L

    /** A server-clock instant expressed on the local clock. */
    fun serverToLocal(serverTimestampMs: Long, rawOffsetMs: Long): Long =
        if (serverTimestampMs <= 0L) serverTimestampMs else serverTimestampMs - rawOffsetMs

    /** Record one ordinary response's `Date`. Safe to call from any thread. */
    fun observe(serverDateMs: Long, sentAtMs: Long, receivedAtMs: Long) =
        estimator.observe(serverDateMs, sentAtMs, receivedAtMs)

    /** Record a 401/403's `Date` (see [Estimator.observeRefusal]). */
    fun observeRefusal(serverDateMs: Long, sentAtMs: Long, receivedAtMs: Long) =
        estimator.observeRefusal(serverDateMs, sentAtMs, receivedAtMs)

    /** Feed one response, whatever its code: 1xx-3xx sample, 401/403 refusal, rest ignored. */
    fun observeResponse(response: Response, sentAtMs: Long, receivedAtMs: Long) {
        try {
            val date = response.headers.getDate("Date")?.time ?: return
            when (response.code) {
                in 100..399 -> observe(date, sentAtMs, receivedAtMs)
                401, 403 -> observeRefusal(date, sentAtMs, receivedAtMs)
            }
        } catch (_: Exception) { /* a clock sample must never fail a request */ }
    }

    /** Current raw estimate (server − local); 0 before the first sample. */
    fun rawOffsetMs(): Long = estimator.rawOffsetMs()

    /**
     * The time to put in EVERY signed timestamp (`x-oshi-timestamp`, the web relay's
     * `mobile_auth`). Equals the local clock unless it is ≥ 5 s off.
     */
    fun signingNowMs(localNowMs: Long = System.currentTimeMillis()): Long =
        localNowMs + effectiveOffset(rawOffsetMs())

    /** A server-stamped signal timestamp on the local clock (unchanged before any sample). */
    fun serverToLocal(serverTimestampMs: Long): Long = serverToLocal(serverTimestampMs, rawOffsetMs())

    /** True for OSHI's own hosts: only their `Date` is trusted. */
    fun isOshiHost(request: Request): Boolean = OshiUserAgent.isOwnHost(request.url.host)

    /**
     * Learns from every OSHI response passing through a client that has no signer
     * interceptor of its own (mail, reports, web relay socket, devsync socket). It also
     * re-sends ONCE a [com.oshi.messenger.network.v2.V2Signer]-signed request refused
     * because of the clock (see [sampling]).
     */
    val interceptor: Interceptor = Interceptor { chain -> sampleAndResign(chain, null) }

    /**
     * Wraps the cover/OHTTP interceptor every `/v2/` client installs, so the sample
     * measures the round trip as the app sees it and the re-sign goes out through the
     * same cover transport.
     */
    fun sampling(delegate: Interceptor): Interceptor = Interceptor { chain -> sampleAndResign(chain, delegate) }

    private fun sampleAndResign(chain: Interceptor.Chain, delegate: Interceptor?): Response {
        val request = chain.request()
        if (!isOshiHost(request)) return if (delegate != null) delegate.intercept(chain) else chain.proceed(request)
        val before = effectiveOffset(rawOffsetMs())
        val response = timed(chain, request, delegate)
        val after = effectiveOffset(rawOffsetMs())
        if (!CallPushAuth.shouldResignAfter(response.code, before, after)) return response
        val signature = request.header(CallPushAuth.HEADER_SIGNATURE) ?: return response
        // A device-mode request carries a second signature over the SAME timestamp: re-signing
        // only the account half would break it. Let the caller's own retry handle those.
        if (request.header("x-oshi-device-signature") != null) return response
        val fresh = try {
            com.oshi.messenger.network.v2.V2Signer.resignFor(signature)
        } catch (_: Exception) { null } ?: return response
        val again = request.newBuilder().apply { fresh.forEach { (k, v) -> header(k, v) } }.build()
        response.close()
        return timed(chain, again, delegate)
    }

    private fun timed(chain: Interceptor.Chain, request: Request, delegate: Interceptor?): Response {
        val sentAt = System.currentTimeMillis()
        val response = when {
            delegate == null -> chain.proceed(request)
            request === chain.request() -> delegate.intercept(chain)
            else -> delegate.intercept(RequestOverride(chain, request))
        }
        observeResponse(response, sentAt, System.currentTimeMillis())
        return response
    }

    /** The same chain, but [request] in place of the original (for the delegate's re-send). */
    private class RequestOverride(private val chain: Interceptor.Chain, private val request: Request) :
        Interceptor.Chain by chain {
        override fun request(): Request = request
    }

    /** Test hook. */
    internal fun resetForTest() {
        estimator = Estimator()
    }
}
