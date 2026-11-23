package com.oshi.messenger.network.push

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * __BLIND_PUSH_2026_11_09__ Register this handset's FCM token as an opaque HANDLE.
 *
 * The push service used to store `identity -> FCM token`. When it advertises blind push
 * (`GET /push/capabilities` → `blindPush`), the token goes to the push GATEWAY instead
 * (`POST <sealUrl>`, unsigned, no identity in the request), which returns an opaque
 * handle sealed under a key only the gateway holds; `/push/register-device` then gets
 * `fcmHandle` in place of `fcmToken`, and the server drops the token it had.
 *
 * Compatibility, in both directions:
 *  * A server without the feature (today's prod) answers 404 on `/capabilities` → the
 *    legacy registration, byte for byte what 1.6.88 sends.
 *  * A server that stops advertising, or answers a handle registration WITHOUT
 *    `blindPush.accepted` (old server, feature switched off) → legacy registration in
 *    the same attempt, so the phone is never left without pushes.
 *  * Once blind push has been accepted, a transient failure (gateway down, network) does
 *    NOT fall back to sending the token: the handle already registered keeps working and
 *    the next registration (next launch / token refresh) tries again.
 *
 * The seal request goes through [sealClient], a client WITHOUT the CallPushSigning
 * interceptor: a signature would tell the gateway which identity owns the token.
 */
object BlindPushRegistrar {

    sealed class Caps {
        data class Supported(val sealUrl: String) : Caps()
        object Unsupported : Caps()
        object Unknown : Caps()
    }

    enum class Outcome { BLIND_ACCEPTED, LEGACY_REGISTERED, RETRY_LATER, FAILED }

    const val HANDLE_PREFIX = "ph1."
    private val JSON = "application/json".toMediaType()

    /** `{"blindPush":{"v":1,"sealUrl":"..."}}` → Supported; `{"blindPush":null}` → Unsupported. */
    fun parseCaps(httpCode: Int, body: String?, pushBaseUrl: String): Caps {
        if (httpCode == 404) return Caps.Unsupported
        if (httpCode !in 200..299 || body == null) return Caps.Unknown
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return Caps.Unknown
        val bp = o.optJSONObject("blindPush") ?: return Caps.Unsupported
        if (bp.optInt("v", 0) != 1) return Caps.Unsupported
        val url = resolveSealUrl(bp.optString("sealUrl", ""), pushBaseUrl) ?: return Caps.Unsupported
        return Caps.Supported(url)
    }

    /**
     * Relative → same origin as the push base URL. Absolute → must be https (a plain-http
     * base is accepted only when the push base itself is plain http: tests, local dev).
     */
    fun resolveSealUrl(sealUrl: String, pushBaseUrl: String): String? {
        if (sealUrl.isBlank()) return null
        val base = java.net.URI(pushBaseUrl)
        if (sealUrl.startsWith("https://")) return sealUrl
        if (sealUrl.startsWith("http://")) return if (base.scheme == "http") sealUrl else null
        if (!sealUrl.startsWith("/")) return null
        val port = if (base.port == -1) "" else ":${base.port}"
        return "${base.scheme}://${base.host}$port$sealUrl"
    }

    fun shouldUseBlind(caps: Caps, cachedActive: Boolean): Boolean =
        caps is Caps.Supported || (caps is Caps.Unknown && cachedActive)

    fun sealRequestBody(fcmToken: String): String =
        JSONObject().put("tokens", JSONArray().put(JSONObject()
            .put("token", fcmToken).put("platform", "fcm").put("kind", "message"))).toString()

    /** The FCM handle from a `/v1/seal` reply, or null if the reply is not one. */
    fun parseSealResponse(body: String?): String? {
        val arr = runCatching { JSONObject(body ?: "").getJSONArray("handles") }.getOrNull() ?: return null
        for (i in 0 until arr.length()) {
            val h = arr.optJSONObject(i) ?: continue
            val v = h.optString("handle", "")
            if (h.optString("platform") == "fcm" && v.startsWith(HANDLE_PREFIX) && v.length < 4096) return v
        }
        return null
    }

    /** register-device body. Exactly one of [fcmToken] / [fcmHandle] is set. */
    fun registerBody(publicKey: String, deviceId: String?, pushCaps: String, fcmToken: String?, fcmHandle: String?): String =
        JSONObject().apply {
            put("publicKey", publicKey)
            if (fcmHandle != null) put("fcmHandle", fcmHandle) else put("fcmToken", fcmToken)
            put("platform", "android")
            put("pushCaps", pushCaps)
            if (deviceId != null) put("deviceId", deviceId)
        }.toString()

    /** blindPush.accepted from a register-device reply. */
    fun accepted(body: String?): Boolean =
        runCatching { JSONObject(body ?: "").optJSONObject("blindPush")?.optBoolean("accepted", false) == true }.getOrDefault(false)

    /** True when the server says it supports blind push (so a failure is transient). */
    fun serverSupports(body: String?): Boolean =
        runCatching { JSONObject(body ?: "").optJSONObject("blindPush")?.optBoolean("supported", false) == true }.getOrDefault(false)

    /** Persisted "this server accepted our handle" flag. */
    interface State {
        var active: Boolean
    }

    /**
     * One registration attempt. [onLegacy] is invoked (in addition to the legacy
     * `/register-device`) whenever the token is registered the old way, so the caller can
     * keep its other legacy side effects (Android's `/register_fcm`) on that path only.
     */
    fun register(
        pushClient: OkHttpClient,
        sealClient: OkHttpClient,
        pushBaseUrl: String,
        publicKey: String,
        deviceId: String?,
        fcmToken: String,
        pushCaps: String,
        state: State,
        onLegacy: () -> Unit = {},
        log: (String) -> Unit = {},
    ): Outcome {
        val caps = try {
            pushClient.newCall(Request.Builder().url("$pushBaseUrl/capabilities").get().build()).execute().use {
                parseCaps(it.code, it.body?.string(), pushBaseUrl)
            }
        } catch (e: Exception) { Caps.Unknown }

        fun legacy(): Outcome {
            val ok = post(pushClient, "$pushBaseUrl/register-device", registerBody(publicKey, deviceId, pushCaps, fcmToken, null))?.first?.let { it in 200..299 } == true
            onLegacy()
            log("blind-push: legacy registration ${if (ok) "ok" else "failed"} (caps=${caps::class.simpleName})")
            return if (ok) Outcome.LEGACY_REGISTERED else Outcome.FAILED
        }

        if (caps is Caps.Unsupported) state.active = false
        if (!shouldUseBlind(caps, state.active)) return legacy()

        val sealUrl = (caps as? Caps.Supported)?.sealUrl
            ?: resolveSealUrl("/push-gateway/v1/seal", pushBaseUrl)!!
        val handle = post(sealClient, sealUrl, sealRequestBody(fcmToken))?.let { (code, body) ->
            if (code in 200..299) parseSealResponse(body) else null
        }
        if (handle == null) {
            // Already blind: the registered handle still works, do not hand the token out.
            return if (state.active) { log("blind-push: seal failed, retry later"); Outcome.RETRY_LATER } else legacy()
        }
        val reg = post(pushClient, "$pushBaseUrl/register-device", registerBody(publicKey, deviceId, pushCaps, null, handle))
        return when {
            reg == null -> { log("blind-push: register network error, retry later"); if (state.active) Outcome.RETRY_LATER else legacy() }
            reg.first in 200..299 && accepted(reg.second) -> { state.active = true; log("blind-push: handle accepted"); Outcome.BLIND_ACCEPTED }
            reg.first in 200..299 || !serverSupports(reg.second) -> { state.active = false; legacy() }
            else -> { log("blind-push: register refused ${reg.first}, retry later"); if (state.active) Outcome.RETRY_LATER else legacy() }
        }
    }

    private fun post(client: OkHttpClient, url: String, json: String): Pair<Int, String?>? = try {
        client.newCall(Request.Builder().url(url).post(json.toRequestBody(JSON)).build()).execute().use { it.code to it.body?.string() }
    } catch (e: Exception) { null }
}
