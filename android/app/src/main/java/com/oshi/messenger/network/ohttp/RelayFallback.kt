package com.oshi.messenger.network.ohttp

import com.oshi.messenger.network.v2.CoverConnectionPolicy
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * __RELAY_FALLBACK_2026_11_19__ Blocked-host fallback through OSHI's own OHTTP relay
 * (docs/CENSORSHIP_2026_THREAT_MODEL.md §9-10).
 *
 * When oshi-messenger.com itself is blocked (SNI / IP / DNS block: Iran, Turkmenistan,
 * Russia), every /v2 call fails at the transport level and nothing is delivered. The relays
 * [OhttpConfig.FALLBACK_RELAY_URLS] live on other IPs under other, unrelated hostnames;
 * they forward HPKE-sealed requests to the gateway, which replays them to /v2 unchanged (the
 * signature still verifies). So once the direct path has shown the block, eligible /v2
 * calls go through a relay for [WINDOW_MS]; then the next call tries direct again.
 *
 * __RELAY_LIST_CH1_2026_11_19__ Several relays, in order: a call tries the preferred one,
 * and on any relay-hop failure (transport, non-200, wrong type, bad decapsulation — never a
 * gateway key mismatch, which is the same behind every relay) the NEXT one, each at most
 * once per call. The one that answered becomes preferred, so a blocked first relay costs
 * one failed attempt, not one per call. A retry through another relay may duplicate a
 * request whose reply was lost: the v2 idempotency cache answers it (same rule as the
 * direct -> relay retry).
 *
 * Activation: a DNS failure or a TLS handshake failure (the SNI-reset signature) at once;
 * any other transport failure (refused, reset, EOF, timeout) when it is the 3rd in a row
 * without a success in between, within [STREAK_MS]. HTTP statuses never count. A transient
 * loss, or one dead route (OkHttp does not retry another address in the same call with
 * retryOnConnectionFailure=false, e.g. a broken IPv6 route; the next call skips it), does
 * not move traffic; a block does, within 1-2 calls.
 *
 * Privacy: strictly no worse than direct — the relay sees the client IP and ciphertext;
 * the gateway (OSHI) sees content but only the relay's address. OSHI runs both, so this
 * is circumvention, not OHTTP's operator separation. The user's own "Privacy relay"
 * setting, when ON, takes precedence and keeps its no-direct-fallback rule.
 */
object RelayFallback {
    const val WINDOW_MS: Long = 15 * 60_000L
    const val STREAK_MS: Long = 3 * 60_000L
    const val STREAK_TO_ACTIVATE = 3

    @Volatile private var activeUntil = 0L
    @Volatile private var lastFailureAt = 0L
    @Volatile private var streak = 0
    @Volatile var clock: () -> Long = System::currentTimeMillis
        internal set

    /** Test / harness override of the built-in relays (e.g. local relays over http). */
    @Volatile private var override: List<OhttpTransport>? = null
    @Volatile private var cached: List<OhttpTransport>? = null
    /** Index (into the configured list) of the relay that answered last. */
    @Volatile private var preferred = 0

    /** All configured relays, in the order this call should try them (preferred first). */
    fun transports(): List<OhttpTransport> {
        val all = override ?: cached ?: OhttpConfig.FALLBACK_RELAY_URLS
            .filter { OhttpConfig.isConfigured(it) }
            .mapNotNull { build(it, OhttpConfig.PINNED_KEYS_HEX) }
            .also { cached = it }
        if (all.size < 2) return all
        val p = preferred.coerceIn(0, all.size - 1)
        return all.subList(p, all.size) + all.subList(0, p)
    }

    /** The relay a call tries first, or null (feature off). */
    fun transport(): OhttpTransport? = transports().firstOrNull()

    fun overrideForTests(t: OhttpTransport?) = overrideAllForTests(t?.let { listOf(it) })
    fun overrideAllForTests(list: List<OhttpTransport>?) { override = list?.takeIf { it.isNotEmpty() }; preferred = 0; reset() }

    /** [t] answered: later calls try it first. */
    fun onRelaySuccess(t: OhttpTransport) {
        val all = override ?: cached ?: return
        val i = all.indexOf(t); if (i >= 0) preferred = i
    }

    fun active(): Boolean = clock() < activeUntil

    fun onDirectSuccess() { streak = 0 }

    /** Returns true when this failure (just) put the fallback in force. */
    fun onDirectFailure(e: Throwable): Boolean {
        if (!CoverConnectionPolicy.isTransportFailure(e)) return false
        val now = clock()
        streak = if (now - lastFailureAt <= STREAK_MS) streak + 1 else 1
        lastFailureAt = now
        // 3, not 2: netem loss10 (10 % of exchanges reset/truncated) flipped a healthy lossy
        // link to the relay within minutes with 2 (0.1^2 per pair of calls); 0.1^3 rarely.
        if (isDefinitiveBlock(e) || streak >= STREAK_TO_ACTIVATE) {
            activeUntil = now + WINDOW_MS
            return true
        }
        return false
    }

    /** Every relay failed (last error [e]); at transport level: extend nothing, but let the next call probe direct. */
    fun onRelayFailure(e: Throwable) {
        if (e is IOException && CoverConnectionPolicy.isTransportFailure(e) && isConnectPhase(e)) activeUntil = 0L
    }

    internal fun reset() { activeUntil = 0L; lastFailureAt = 0L; streak = 0 }

    internal fun isDefinitiveBlock(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) {
            if (c is UnknownHostException || c is SSLHandshakeException) return true
            c = c.cause
        }
        return false
    }

    internal fun isConnectPhase(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) {
            if (c is UnknownHostException || c is ConnectException || c is NoRouteToHostException || c is SSLHandshakeException) return true
            if (c is SocketTimeoutException && c.message.orEmpty().contains("connect", ignoreCase = true)) return true
            c = c.cause
        }
        return false
    }

    /** For the harness: a transport that treats [origin] (e.g. http://127.0.0.1:18094) as OSHI's origin. */
    fun localTransport(relayUrl: String, keysHex: String, origin: String): OhttpTransport? =
        build(relayUrl, keysHex)?.copy(directOrigin = origin.toHttpUrl())

    /** For the harness: several relays (comma-separated URLs), in order. */
    fun localTransports(relayUrls: String, keysHex: String, origin: String): List<OhttpTransport> =
        relayUrls.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { localTransport(it, keysHex, origin) }

    /** Same as OhttpSettings.build, without the Android settings class (JVM harness). */
    private fun build(relayUrl: String, keysHex: String): OhttpTransport? = runCatching {
        val cfg = Ohttp.selectFromKeysMedia(Ohttp.hex(keysHex)) ?: return null
        OhttpTransport(relayUrl.toHttpUrl(), cfg)
    }.getOrNull()
}
