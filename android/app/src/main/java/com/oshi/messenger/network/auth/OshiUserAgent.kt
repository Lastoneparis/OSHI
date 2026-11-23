package com.oshi.messenger.network.auth

import com.oshi.messenger.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * __LEAK_FIX_2026_09_25__ `User-Agent: OSHI-Android/<versionName>` on requests to OUR
 * servers (oshi-messenger.com and its subdomains) — the plain `okhttp/x` default told the
 * server nothing, so it could not measure which builds still make unsigned or plaintext
 * calls before switching enforcement on (`ServerPatches/plaintext_leaks/DEPLOY.md` §6-7).
 *
 * Deliberately scoped to our own host: third parties (map tiles, GIFs, quotes, previews)
 * keep whatever they were sent before, so this does not make OSHI users easier to single
 * out elsewhere. Same string as the call/push signer ([CallPushAuth.userAgent]).
 */
object OshiUserAgent {
    const val OWN_HOST = "oshi-messenger.com"

    val value: String by lazy { CallPushAuth.userAgent(BuildConfig.VERSION_NAME) }

    /** True for oshi-messenger.com and *.oshi-messenger.com. */
    fun isOwnHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h == OWN_HOST || h.endsWith(".$OWN_HOST")
    }

    val interceptor: Interceptor = Interceptor { chain ->
        val req = chain.request()
        if (!isOwnHost(req.url.host)) chain.proceed(req)
        else chain.proceed(req.newBuilder().header("User-Agent", value).build())
    }
}
