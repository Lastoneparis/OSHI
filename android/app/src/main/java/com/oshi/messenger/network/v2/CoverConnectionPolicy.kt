package com.oshi.messenger.network.v2

import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * __COVER_SHORT_CONNECTIONS_2026_11_19__ Survive a per-connection volume cap.
 *
 * Since June 2025 Russia's TSPU silently freezes TCP connections to foreign hosting
 * ranges once ~16 KB (later measured as ~25 packets, ~8 on some mobile networks) have
 * flowed from the server: no RST, the reply just stops (net4people/bbs#490; Cloudflare's
 * own status page confirms the 16 KB cap for its ranges). Contabo AS51167 — where
 * oshi-messenger.com lives — is in the reported set. The counter is PER CONNECTION: many
 * short connections pass, one long-lived one dies.
 *
 * A padded cover exchange is ~5.5 KB each way (4 KiB bucket + base64 + JSON), so a
 * keep-alive connection reused for polls dies on the 3rd exchange and every later call on
 * it waits for its read timeout. A fresh TLS connection per cover call carries one
 * handshake (~3-4 KB of certificates) + one exchange: under the cap for every text/poll.
 *
 * Policy: normal pooling (cheapest, fewest handshakes, what every app does) until a cover
 * exchange fails at the TRANSPORT level (timeout, reset, EOF — not an HTTP status the relay
 * chose to send). Then, for [WINDOW_MS], each cover call opens its own connection and
 * closes it afterwards. Any further transport failure extends the window. This costs one
 * extra handshake per call only on networks that already showed the symptom.
 *
 * What it does NOT fix: media chunks (≥16 KiB padded bucket) exceed the cap inside a single
 * request; they keep failing on such networks and wait in the outbox, as before.
 */
object CoverConnectionPolicy {
    const val WINDOW_MS: Long = 15 * 60_000L

    /**
     * __PULL_BUDGET_2026_11_19__ Pull page size (bytes of inner JSON) while the window is open:
     * the relay's reply JSON is base64'd into the cover frame (x4/3) plus ~200 bytes of record,
     * so 2400 B stays in the 4 KiB padding bucket (~5.5 KB on the wire, under a 16 KB cap even
     * with the TLS handshake on a fresh connection).
     */
    const val SMALL_PULL_BUDGET = 2400

    @Volatile private var freshUntil: Long = 0L
    @Volatile var clock: () -> Long = System::currentTimeMillis
        internal set

    fun freshConnections(): Boolean = clock() < freshUntil

    /** Called with the failure of one cover exchange. */
    fun onFailure(e: Throwable) {
        if (isTransportFailure(e)) freshUntil = clock() + WINDOW_MS
    }

    internal fun reset() { freshUntil = 0L }

    /** Our own "the relay answered with an HTTP status" errors are not a stalled link. */
    internal fun isTransportFailure(e: Throwable): Boolean {
        if (e !is IOException) return false
        val m = e.message.orEmpty()
        return !(m.startsWith("Obfuscated transport HTTP") || m.startsWith("Invalid obfuscated response") ||
            m.startsWith("Missing cover response") || m.startsWith("Cover response too large"))
    }

    /**
     * Shared pool with ZERO idle connections: OkHttp closes each connection as soon as its
     * call releases it, so no two cover exchanges share a TCP/TLS connection. Timeouts are
     * applied per call by the interceptor. No proxy / DNS / socket factory is set on any
     * V2 client today; certificate pinning is platform-wide (network_security_config.xml),
     * so this client is pinned exactly like the V2 clients.
     */
    val freshClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder().noSilentReplay()
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
    }
}
