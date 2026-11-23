package com.oshi.messenger.network.ohttp

/**
 * Build-time Oblivious HTTP configuration ("Privacy relay (beta)").
 *
 * Path: app → [RELAY_URL] (independent operator, sees our IP but only ciphertext)
 *           → [GATEWAY_URL] (OSHI, decrypts but sees only the relay's IP) → /v2/ API.
 *
 * Both [RELAY_URL] and [PINNED_KEYS_HEX] are PLACEHOLDERS: while either is blank the
 * feature is unavailable (the settings toggle is greyed out and nothing is routed).
 */
object OhttpConfig {
    /** OSHI's Oblivious Gateway Resource. The relay is configured to forward here. */
    const val GATEWAY_URL = "https://oshi-messenger.com/ohttp/gateway"

    /** Where the gateway publishes its `application/ohttp-keys` (we PIN instead of fetching). */
    const val KEYS_URL = "https://oshi-messenger.com/ohttp/config"

    /**
     * Oblivious Relay Resource run by a third party. TODO(coordinator): fill in once an
     * independent relay operator is contracted. Blank = feature unavailable.
     */
    const val RELAY_URL = ""

    /**
     * The gateway's key configuration(s), PINNED, in RFC 9540 `application/ohttp-keys`
     * form (2-byte length || RFC 9458 key config), hex. Pinning — instead of fetching
     * [KEYS_URL] through the relay — stops a malicious gateway from handing each user a
     * distinct key to re-identify them. Live gateway key, 2026-11-09: key_id 0x01,
     * X25519 / HKDF-SHA256 / AES-128-GCM (= `curl -s .../ohttp/config | xxd -p`).
     * Rotating the gateway key REQUIRES an app update (mismatch → 400 `#ohttp-key`).
     */
    const val PINNED_KEYS_HEX =
        "0029010020c93f9075d86e2e7dca66b2e3309d584486ef53f7d6dca5cbff3d4ccf87cda019000400010001"

    /** The only origin whose requests may be encapsulated (the inner `:authority`). */
    const val INNER_AUTHORITY = "oshi-messenger.com"

    fun isConfigured(relayUrl: String = RELAY_URL, keysHex: String = PINNED_KEYS_HEX): Boolean =
        relayUrl.isNotBlank() && keysHex.isNotBlank()
}
