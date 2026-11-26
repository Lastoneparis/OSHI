package com.oshi.messenger.network.auth

import com.oshi.messenger.network.v2.V2Signer
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException

/**
 * __CALL_PUSH_AUTH_2026_09_23__ Identity-bound signing of every call / push request,
 * per docs/CALL_PUSH_AUTH_CONTRACT.md (v1.0, STABLE).
 *
 * The scheme is the one `/v2/` already uses (and iOS uses for `/api/call/signal` and
 * `/push/`): three headers over `METHOD\nPATH\nlowerhex(SHA-256(body))\nTIMESTAMP_MS`,
 * Ed25519, standard padded base64. The key MUST be the Ed25519 key published with the
 * v2 prekey bundle, because the server looks up `X25519 identity -> signing key` in the
 * TOFU table that publication wrote; a valid signature by any other key is a forgery
 * (403). So there is no second key here: the caller hands in the same key V2Signer uses.
 *
 * Everything in this file is pure JVM (no android.*) so the contract's test vectors run
 * as plain unit tests.
 */
object CallPushAuth {

    const val HEADER_SIGNATURE = "x-oshi-signature"
    const val HEADER_TIMESTAMP = "x-oshi-timestamp"
    const val HEADER_SIGNING_PUBKEY = "x-oshi-signing-pubkey"

    /** SHA-256 of zero bytes: the body hash for GET and for the WebSocket upgrade. */
    const val EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /**
     * Paths (as the CLIENT requests them — nginx strips the prefix afterwards) that the
     * contract covers: call_server behind `/api/call/` and `/voip/`, push_service behind
     * `/push/`. The bare `/api/call` / `/voip` spellings are the WebSocket upgrade
     * without its trailing slash. `/v2/` is deliberately absent: V2 clients sign their
     * own requests, some with a non-body hash (`""` on verify-first blob routes), and a
     * second signature would overwrite theirs.
     */
    /**
     * __CALL_WAKE_2026_11_22__ Re-sign once when the server refused the signature (401/403)
     * AND the response just moved the clock correction by >= 5 s: the refusal was the
     * phone's clock, not the key. Never loops: the retry is not checked again.
     */
    fun shouldResignAfter(code: Int, offsetBeforeMs: Long, offsetAfterMs: Long): Boolean =
        (code == 401 || code == 403) && kotlin.math.abs(offsetAfterMs - offsetBeforeMs) >= ServerClock.SIGNING_THRESHOLD_MS

    fun covers(encodedPath: String): Boolean =
        encodedPath.startsWith("/api/call/") ||
            encodedPath.startsWith("/voip/") ||
            encodedPath.startsWith("/push/") ||
            encodedPath == "/api/call" ||
            encodedPath == "/voip"

    /** `METHOD\nPATH\nSHA256HEX(body)\nTIMESTAMP` — the one canonical string, shared with V2. */
    fun canonical(method: String, path: String, body: ByteArray, timestampMs: String): String =
        V2Signer.canonicalString(method.uppercase(), path, body, timestampMs)

    /**
     * The three headers for one request.
     *
     * @param path the path exactly as requested (`HttpUrl.encodedPath`), no query.
     * @param body the EXACT bytes that go on the wire (empty for GET / WS upgrade).
     * @param sign Ed25519 over the canonical bytes, by the v2-bound signing key.
     */
    fun headers(
        method: String,
        path: String,
        body: ByteArray,
        // __SERVER_CLOCK_EVERY_SIGNER_2026_11_23__ server time, never the raw local clock.
        timestampMs: Long = ServerClock.signingNowMs(),
        signingPublicKey: ByteArray,
        sign: (ByteArray) -> ByteArray,
    ): Map<String, String> {
        val ts = timestampMs.toString()
        val message = canonical(method, path, body, ts).toByteArray(Charsets.UTF_8)
        val b64 = java.util.Base64.getEncoder()
        return linkedMapOf(
            HEADER_SIGNATURE to b64.encodeToString(sign(message)),
            HEADER_TIMESTAMP to ts,
            HEADER_SIGNING_PUBKEY to b64.encodeToString(signingPublicKey),
        )
    }

    /** `OSHI-Android/<versionName>` — the server counts unsigned traffic per UA token. */
    fun userAgent(versionName: String): String = "OSHI-Android/$versionName"
}

/**
 * Signs every covered request that passes through the OkHttp client it is installed on,
 * WebSocket upgrades included (OkHttp runs application interceptors on the upgrade GET).
 *
 * One helper for all call sites is the contract's first checklist item, and doing it at
 * the interceptor also gives the second for free: the body is read ONCE into bytes, those
 * bytes are hashed, and the request is rebuilt to send exactly those bytes — so the signed
 * bytes and the transmitted bytes cannot diverge whatever the caller serialized.
 *
 * A request that already carries `x-oshi-signature` is left alone.
 *
 * If no signing key is available the request FAILS with an IOException rather than going
 * out unsigned (contract §1, "never fall back to unsigned"): every caller already treats
 * an IOException as a transient network failure and retries on its own schedule.
 */
class CallPushAuthInterceptor(
    private val userAgent: String,
    private val headersFor: (method: String, path: String, body: ByteArray) -> Map<String, String>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath
        if (!CallPushAuth.covers(path)) return chain.proceed(original)
        if (original.header(CallPushAuth.HEADER_SIGNATURE) != null) return observeClock(chain, original)
        val originalBody = original.body
        val bytes = if (originalBody == null) ByteArray(0) else {
            val buffer = Buffer()
            originalBody.writeTo(buffer)
            buffer.readByteArray()
        }
        val signed = try {
            headersFor(original.method, path, bytes)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("call/push request not sent: signing unavailable (${e.message})", e)
        }
        val offsetBefore = ServerClock.effectiveOffset(ServerClock.rawOffsetMs())
        val response = observeClock(chain, build(original, signed, bytes))
        // __CALL_WAKE_2026_11_22__ The first request of a process signs with the raw clock
        // (no server sample yet). On a phone > 30 s off it is refused ('expired' -> 401/403
        // for a bound identity, e.g. the FCM token registration at startup) — and THAT
        // answer's Date header is the sample that fixes the clock. Re-sign once with it.
        val offsetAfter = ServerClock.effectiveOffset(ServerClock.rawOffsetMs())
        if (!CallPushAuth.shouldResignAfter(response.code, offsetBefore, offsetAfter)) return response
        val again = try { headersFor(original.method, path, bytes) } catch (e: Exception) { return response }
        response.close()
        return observeClock(chain, build(original, again, bytes))
    }

    private fun build(original: Request, signed: Map<String, String>, bytes: ByteArray): Request {
        val builder: Request.Builder = original.newBuilder()
        signed.forEach { (name, value) -> builder.header(name, value) }
        builder.header("User-Agent", userAgent)
        val originalBody = original.body
        if (originalBody != null) {
            builder.method(original.method, bytes.toRequestBody(originalBody.contentType()))
        }
        return builder.build()
    }

    // __CALL_CLOCK_SKEW_2026_11_21__ every call/push server answer carries `Date`; learn
    // the phone's offset from it so polled signals and signatures use the server's clock.
    // __SERVER_CLOCK_EVERY_SIGNER_2026_11_23__ a 401/403 whose Date disagrees by >= 5 s
    // restarts the estimate from that Date (ServerClock.observeRefusal), so the one
    // re-sign above uses the server's clock instead of a median still full of old samples.
    private fun observeClock(chain: Interceptor.Chain, request: Request): Response {
        val sentAt = System.currentTimeMillis()
        val response = chain.proceed(request)
        ServerClock.observeResponse(response, sentAt, System.currentTimeMillis())
        return response
    }
}
