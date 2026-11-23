package com.oshi.messenger.network.ohttp

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * LIVE check against the real gateway through a relay — skipped unless OHTTP_IT_RELAY is
 * set (e.g. `OHTTP_IT_RELAY=http://127.0.0.1:8787/` with the throwaway test relay).
 * Read-only: GET /v2/config only. Never sends messages or writes data.
 * Optional OHTTP_IT_KEYS = hex of `application/ohttp-keys` (default: fetch /ohttp/config).
 */
class OhttpLiveIntegrationTest {
    @Test fun readOnlyV2ConfigThroughRelayAndGateway() {
        val relay = System.getenv("OHTTP_IT_RELAY").orEmpty()
        assumeTrue("OHTTP_IT_RELAY not set", relay.isNotBlank())
        val plain = OkHttpClient()
        val keysHex = System.getenv("OHTTP_IT_KEYS")?.takeIf { it.isNotBlank() }
            ?: plain.newCall(Request.Builder().url(OhttpConfig.KEYS_URL).build()).execute().use {
                Ohttp.toHex(it.body!!.bytes())
            }
        val transport = OhttpTransport(relay.toHttpUrl(), Ohttp.selectFromKeysMedia(Ohttp.hex(keysHex))!!)
        var direct = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(OhttpInterceptor({ transport }, Interceptor { direct++; it.proceed(it.request()) }))
            .build()
        client.newCall(Request.Builder().url("https://oshi-messenger.com/v2/config").get().build()).execute().use { resp ->
            val body = resp.body!!.string()
            println("OHTTP live: ${resp.code} ${resp.header("Content-Type")} $body")
            assertEquals(200, resp.code)
            assertTrue(JSONObject(body).length() > 0)
        }
        assertEquals("must not go direct", 0, direct)
    }
}
