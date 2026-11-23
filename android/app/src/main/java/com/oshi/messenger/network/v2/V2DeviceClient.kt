package com.oshi.messenger.network.v2

import android.util.Base64
import android.util.Log
import com.oshi.messenger.BuildConfig
import com.oshi.messenger.network.encryption.CryptoManager
import com.oshi.messenger.network.v2.devicemailbox.DeviceHttpOutcome
import com.oshi.messenger.network.v2.devicemailbox.DeviceKeys
import com.oshi.messenger.network.v2.devicemailbox.DeviceListCache
import com.oshi.messenger.network.v2.devicemailbox.DeviceListMismatch
import com.oshi.messenger.network.v2.devicemailbox.RegisteredDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * __PER_DEVICE_MAILBOX_2026_09_23__ Every HTTP route of the per-device mailbox (CLIENT_SPEC §2-§3,
 * design Appendix B), in one place so the legacy clients stay byte-for-byte what they were.
 *
 * Device-mode requests carry the usual ACCOUNT signature (required, never graced) PLUS
 * `x-oshi-device` / `x-oshi-device-signature`, whose canonical string reuses the account
 * signature's `x-oshi-timestamp`. The cover transport ([CoverInterceptor]) already forwards every
 * `x-oshi-*` header into the inner request, so both extra headers survive it unchanged.
 */
@Singleton
class V2DeviceClient @Inject constructor(
    private val coverSettings: CoverSettings,
    private val signer: V2Signer,
    private val cryptoManager: CryptoManager,
) {
    private val client = OkHttpClient.Builder()
        .addInterceptor(coverSettings.interceptor())
        .noSilentReplay()   // __SIGNED_NO_SILENT_REPLAY_2026_11_18__ (CoverInterceptor.kt)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Base URL, overridable for the local-server harness only. */
    @Volatile var baseUrl: String = BuildConfig.VPS_URL

    private val json = "application/json; charset=utf-8".toMediaType()
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    /** Raw HTTP answer: status + body text. `status == -1` = network failure. */
    data class Answer(val status: Int, val body: String?) {
        val outcome: DeviceHttpOutcome get() = if (status < 0) DeviceHttpOutcome.Failed(status, "network") else DeviceHttpOutcome.classify(status, body)
    }

    private fun headers(method: String, path: String, body: ByteArray, withUser: Boolean, device: DeviceKeys?): Map<String, String> {
        val h = LinkedHashMap(signer.sign(method, path, body, withUserHeader = withUser))
        if (device != null) h.putAll(device.requestHeaders(method, path, body, h.getValue("x-oshi-timestamp")))
        return h
    }

    private suspend fun call(method: String, path: String, query: String? = null, body: ByteArray? = null,
                             withUser: Boolean = false, device: DeviceKeys? = null): Answer = withContext(Dispatchers.IO) {
        try {
            val bytes = body ?: ByteArray(0)
            val h = headers(method, path, bytes, withUser, device)
            val b = Request.Builder().url(baseUrl + path + (query?.let { "?$it" } ?: ""))
            if (method == "GET") b.get() else b.method(method, bytes.toRequestBody(json))
            h.forEach { (k, v) -> b.header(k, v) }
            client.newCall(b.build()).execute().use { r -> Answer(r.code, r.body?.string()) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "$method $path failed: ${e.javaClass.simpleName}")
            Answer(-1, null)
        }
    }

    // ------------------------------------------------------------------ registry (/v2/devices)

    suspend fun listDevices(): Pair<Answer, List<RegisteredDevice>> {
        val a = call("GET", "/v2/devices", withUser = true)
        val list = if (a.status in 200..299) runCatching { RegisteredDevice.listFrom(JSONObject(a.body.orEmpty())) }.getOrDefault(emptyList()) else emptyList()
        return a to list
    }

    suspend fun register(body: JSONObject): Answer =
        call("POST", "/v2/devices/register", body = body.toString().toByteArray(Charsets.UTF_8), withUser = true)

    /** Any registered device may remove any other; [by] signs. */
    suspend fun unregister(by: DeviceKeys, deviceId: String): Answer =
        call("POST", "/v2/devices/unregister", body = JSONObject().put("deviceId", deviceId).toString().toByteArray(Charsets.UTF_8),
            withUser = true, device = by)

    /** Recovery only (no device key survives): account-signed, every device then gets 410. */
    suspend fun reset(): Answer =
        call("POST", "/v2/devices/reset", body = JSONObject().put("confirm", "RESET-ALL-DEVICES").toString().toByteArray(Charsets.UTF_8), withUser = true)

    // ------------------------------------------------------------------ per-device bundles (/v2/keys)

    /**
     * `POST /v2/keys/publish` WITH `deviceId`, account- and device-signed. Same body as the account
     * publish plus `deviceId`; the one-time pre-keys are a pool of their own (fresh ids), so an OPK
     * popped from the account pool can never also be popped from this one.
     */
    suspend fun publishDeviceBundle(device: DeviceKeys, spk: V2PrekeyStore.SignedPreKey, oneTime: List<Pair<String, ByteArray>>, caps: Int = 0): Pair<Answer, Int?> {
        val userKey = cryptoManager.getPublicKeyString()
        val body = JSONObject().apply {
            put("userKey", userKey)
            put("identityKey", userKey)
            put("signingKey", b64(cryptoManager.getSigningPublicKey()))
            put("signedPreKey", JSONObject().apply {
                put("keyId", spk.keyId)
                put("key", b64(spk.pair.pub))
                put("signature", b64(cryptoManager.sign(spk.pair.pub)))
            })
            put("oneTimePreKeys", JSONArray().apply {
                for ((id, pub) in oneTime) put(JSONObject().put("keyId", id).put("key", b64(pub)))
            })
            put("caps", caps)
            put("deviceId", device.deviceId)
        }
        // __MAILBOX_BOOTSTRAP_2026_09_23__ same "\\/" fix as V2KeysClient.publish: the /v2/keys route
        // re-serialises the parsed body before hashing, so escaped slashes broke both signatures.
        val a = call("POST", "/v2/keys/publish", body = body.toString().replace("\\/", "/").toByteArray(Charsets.UTF_8), device = device)
        val remaining = if (a.status in 200..299) runCatching { JSONObject(a.body.orEmpty()).optInt("remaining") }.getOrNull() else null
        return a to remaining
    }

    /** `GET /v2/keys/<peer>/devices` → device ids holding a bundle (null on failure). */
    suspend fun bundledDevices(peerUserKey: String): List<String>? {
        val path = "/v2/keys/${signer.encodeIdentity(peerUserKey)}/devices"
        val a = call("GET", path)
        if (a.status !in 200..299) return null
        // __ACCOUNT_CAPS_IN_DEVICE_LIST_2026_11_10__ what this list proves about media padding.
        V2PaddingPeers.learnFromDeviceList(peerUserKey, a.body)
        return DeviceListCache.parse(a.body)
    }

    suspend fun deviceOneTimeCount(me: String, deviceId: String): Int? {
        val a = call("GET", "/v2/keys/${signer.encodeIdentity(me)}/count", query = "device=$deviceId")
        return if (a.status in 200..299) runCatching { JSONObject(a.body.orEmpty()).optInt("remaining", 0) }.getOrNull() else null
    }

    /**
     * `GET /v2/keys/<peer>?device=<id>`, verified exactly like [V2KeysClient.fetchBundle]: the SPK
     * signature against the peer's signing key, and TOFU (identityKey == address). Pops one OPK.
     */
    suspend fun fetchDeviceBundle(peerUserKey: String, deviceId: String): FetchedBundle? {
        val a = call("GET", "/v2/keys/${signer.encodeIdentity(peerUserKey)}", query = "device=$deviceId")
        if (a.status !in 200..299) return null
        return try {
            val j = JSONObject(a.body.orEmpty())
            if (j.has("deviceId") && j.optString("deviceId") != deviceId) return null
            val identityKey = unb64(j.getString("identityKey"))
            val signingKey = unb64(j.getString("signingKey"))
            val spk = j.getJSONObject("signedPreKey")
            val spkPub = unb64(spk.getString("key"))
            if (!cryptoManager.verify(spkPub, unb64(spk.getString("signature")), signingKey)) {
                Log.w(TAG, "device SPK signature invalid"); return null
            }
            if (b64(identityKey) != peerUserKey) { Log.w(TAG, "device bundle identity != address"); return null }
            val opk = j.optJSONObject("oneTimePreKey")
            // __V2_PADDING_2026_11_09__ the (peer, device) session slot this bundle builds.
            V2PaddingPeers.noteBundle(peerUserKey, deviceId, if (j.has("caps")) j.optInt("caps", 0) else null)
            FetchedBundle(identityKey, signingKey, spk.optString("keyId"), spkPub, opk?.optString("keyId"), opk?.getString("key")?.let { unb64(it) })
        } catch (e: Exception) { Log.w(TAG, "device bundle parse failed: ${e.message}"); null }
    }

    /**
     * The SPK of the ACCOUNT bundle currently live for [me] (`GET /v2/keys/<me>`), to decide
     * `holdsAccountBundle`. Pops one account OPK, so callers do this rarely (once per registration
     * and at most daily). Returns "" when no account bundle exists (404), null on failure.
     */
    suspend fun liveAccountSpk(me: String): String? {
        val a = call("GET", "/v2/keys/${signer.encodeIdentity(me)}")
        if (a.status == 404) return ""
        if (a.status !in 200..299) return null
        return runCatching { JSONObject(a.body.orEmpty()).getJSONObject("signedPreKey").getString("key") }.getOrNull()
    }

    // ------------------------------------------------------------------ device-mode relay (/v2/messages)

    sealed interface PullResult {
        data class Ok(val pull: V2Pull) : PullResult
        data class Refused(val outcome: DeviceHttpOutcome) : PullResult
    }

    suspend fun pull(me: String, afterSeq: Long, device: DeviceKeys): PullResult {
        val a = call("GET", "/v2/messages/${signer.encodeIdentity(me)}", query = "after=$afterSeq", device = device)
        if (a.status !in 200..299) return PullResult.Refused(a.outcome)
        return try {
            val j = JSONObject(a.body.orEmpty())
            val arr = j.optJSONArray("messages") ?: JSONArray()
            PullResult.Ok(V2Pull((0 until arr.length()).map { V2Envelope.fromJson(arr.getJSONObject(it)) }, j.optLong("maxSeq", afterSeq)))
        } catch (e: Exception) { PullResult.Refused(DeviceHttpOutcome.Failed(a.status, "parse")) }
    }

    suspend fun ack(me: String, upToSeq: Long, device: DeviceKeys): DeviceHttpOutcome {
        val body = JSONObject().put("upToSeq", upToSeq).toString().toByteArray(Charsets.UTF_8)
        return call("POST", "/v2/messages/${signer.encodeIdentity(me)}/ack", body = body, device = device).outcome
    }

    sealed interface BatchResult {
        data class Accepted(val msgIds: List<String>) : BatchResult
        data class Mismatch(val info: DeviceListMismatch) : BatchResult
        /** `409 device-mailbox-disabled` / `unknown-fromDevice`: fall back to the legacy path. */
        data class Refused(val status: Int, val error: String?) : BatchResult
    }

    /**
     * `POST /v2/messages {envelopes:[…]}` — one batch for every copy of one message (all `from = me`).
     * Retries 429/5xx like [V2MessagesClient.send]: the ciphertexts are fixed, only the request
     * authentication is refreshed, and the relay deduplicates a batch whose reply was lost.
     */
    suspend fun sendBatch(envelopes: List<V2Envelope>): BatchResult {
        val body = JSONObject().put("envelopes", JSONArray().apply { envelopes.forEach { put(it.toJson()) } })
            .toString().toByteArray(Charsets.UTF_8)
        var last = Answer(-1, null)
        for (attempt in 0..2) {
            last = call("POST", "/v2/messages", body = body)
            if (last.status in 200..299) {
                val acc = runCatching { JSONObject(last.body.orEmpty()).optJSONArray("accepted") }.getOrNull() ?: JSONArray()
                return BatchResult.Accepted((0 until acc.length()).map { acc.getString(it) })
            }
            DeviceListMismatch.parse(last.body)?.let { return BatchResult.Mismatch(it) }
            if (last.status != 429 && last.status !in 500..599 && last.status != -1) break
            if (attempt < 2) delay(1000L shl attempt)
        }
        return BatchResult.Refused(last.status, runCatching { JSONObject(last.body ?: "{}").optString("error") }.getOrNull())
    }

    private companion object { const val TAG = "V2DeviceClient" }
}
