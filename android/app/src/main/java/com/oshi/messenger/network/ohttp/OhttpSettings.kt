package com.oshi.messenger.network.ohttp

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oshi.messenger.ui.viewmodel.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Privacy relay (beta)" — persisted in the app DataStore, OFF by default, effective only
 * when [OhttpConfig] has both a relay URL and a pinned key config.
 */
@Singleton
class OhttpSettings @Inject constructor(@ApplicationContext private val context: Context) {

    fun isEnabled(): Boolean = runBlocking { context.dataStore.data.first()[KEY_ENABLED] == true }

    /**
     * __OHTTP_RELAY_ADDRESS_2026_11_10__ The relay actually used: the address the user
     * entered (Settings → Privacy → Relay address) or, when blank, the one built in
     * ([OhttpConfig.RELAY_URL], still empty until an operator is contracted). Lets the
     * feature be switched on without an app update, and lets a user in a country that
     * blocks one relay point the app at another — a relay never sees content (the key
     * is pinned to OSHI's gateway), only the user's IP.
     */
    fun relayUrl(): String = runBlocking {
        context.dataStore.data.first()[KEY_RELAY_URL]?.takeIf { it.isNotBlank() } ?: OhttpConfig.RELAY_URL
    }

    fun isAvailable(): Boolean = OhttpConfig.isConfigured(relayUrl())

    /**
     * Non-null exactly when eligible v2 calls MUST go through OHTTP. A configured-but-
     * unparsable pinned key throws (the call fails) — never a quiet direct send.
     */
    fun transport(): OhttpTransport? {
        if (!isEnabled()) return null
        val relay = relayUrl()
        if (!OhttpConfig.isConfigured(relay)) return null
        cached?.let { (url, t) -> if (url == relay) return t }
        val t = build(relay, OhttpConfig.PINNED_KEYS_HEX)
            ?: throw IOException("Privacy relay: relay address or pinned key config is invalid")
        cached = relay to t
        return t
    }

    @Volatile private var cached: Pair<String, OhttpTransport>? = null

    companion object {
        val KEY_ENABLED = booleanPreferencesKey("ohttp_relay_enabled")
        val KEY_RELAY_URL = stringPreferencesKey("ohttp_relay_url")

        /**
         * A relay address the user may save, normalised, or null when unacceptable:
         * https only (the relay hop must be encrypted too), a real host, and NOT OSHI's
         * own domain — a relay run by the party that also decrypts hides nothing.
         */
        fun normalizeRelayUrl(input: String): String? {
            val t = input.trim()
            if (t.isEmpty()) return null
            val url = runCatching { t.toHttpUrl() }.getOrNull() ?: return null
            if (!url.isHttps) return null
            val host = url.host.lowercase()
            if (host == OhttpConfig.INNER_AUTHORITY || host.endsWith("." + OhttpConfig.INNER_AUTHORITY)) return null
            if (!host.contains('.')) return null
            return url.toString()
        }

        fun build(relayUrl: String, keysHex: String): OhttpTransport? = runCatching {
            val cfg = Ohttp.selectFromKeysMedia(Ohttp.hex(keysHex)) ?: return null
            OhttpTransport(relayUrl.toHttpUrl(), cfg)
        }.getOrNull()
    }
}
