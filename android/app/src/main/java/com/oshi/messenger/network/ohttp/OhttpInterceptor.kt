package com.oshi.messenger.network.ohttp

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/** Everything one encapsulated exchange needs. Stateless: safe from workers / FCM wakeups. */
data class OhttpTransport(
    val relayUrl: HttpUrl,
    val keyConfig: Ohttp.KeyConfig,
    val innerAuthority: String = OhttpConfig.INNER_AUTHORITY,
)

/**
 * Oblivious HTTP (RFC 9458) choke point for the v2 OkHttp clients — installed through
 * `CoverSettings.interceptor()`, which every `V2*Client` and `V2Config` already add.
 *
 * When [transport] returns non-null (setting ON and relay + pinned key configured), every
 * ELIGIBLE request is turned into BHTTP, HPKE-sealed to the pinned gateway key, POSTed
 * to the relay as `message/ohttp-req`, and the `message/ohttp-res` answer is opened and
 * returned as a synthetic [Response] carrying the INNER status/headers/body.
 *
 * Auth: the `x-oshi-*` signature headers were already computed by `V2Signer` over the
 * inner method/path/body; they are copied unchanged into the inner request, which the
 * gateway replays to v2 byte-for-byte — so the signature still verifies.
 *
 * NO SILENT FALLBACK: once active, an OHTTP failure (relay down, non-200, bad media type,
 * authentication failure, key mismatch) throws [IOException]; the caller's own retry /
 * queue handles it. We never send an eligible request direct.
 *
 * Scope — these go to [delegate] (direct, or the cover transport) BY DESIGN:
 *  - anything not `https://oshi-messenger.com/v2/...`.
 *
 * __OHTTP_MEDIA_2026_11_10__ `/v2/blobs*` (reserve, PUT chunk, status, commit, GET chunk,
 * DELETE) used to be excluded, so with the privacy relay ON every photo still revealed the
 * user's IP to the OSHI server. Media now goes through OHTTP like every other `/v2/…` call,
 * same no-silent-fallback rule (docs/MEDIA_PADDING_AND_OHTTP_MEDIA_SPEC.md §4). A chunk is
 * 2 MiB + 16 B: the gateway accepts 2 MiB + 64 KiB requests, and [MAX_RESPONSE] (16 MiB)
 * already holds a downloaded chunk.
 * When OHTTP is active it takes precedence over the cover transport for eligible calls.
 */
class OhttpInterceptor(
    private val transport: () -> OhttpTransport?,
    private val delegate: Interceptor,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val t = try { transport() } catch (e: IOException) { throw e } catch (e: Exception) {
            throw IOException("Privacy relay misconfigured", e)
        } ?: return delegate.intercept(chain)
        if (!isEligible(original, t)) return delegate.intercept(chain)
        try {
            val inner = toBhttp(original, t)
            val ctx = Ohttp.encapsulateRequest(t.keyConfig, Bhttp.encodeRequest(inner))
            val outer = Request.Builder()
                .url(t.relayUrl)
                .post(ctx.encapsulatedRequest.toRequestBody(Ohttp.REQUEST_MEDIA_TYPE.toMediaType()))
                .build() // deliberately NO other headers: nothing identifying reaches the relay
            chain.proceed(outer).use { resp ->
                val bytes = resp.body?.byteStream()?.use { it.readBounded(MAX_RESPONSE) } ?: ByteArray(0)
                val type = resp.header("Content-Type").orEmpty()
                if (isKeyMismatch(resp.code, type, bytes)) {
                    throw Ohttp.KeyMismatchException("Privacy relay: gateway rejected the pinned key config (key id ${t.keyConfig.keyId})")
                }
                if (resp.code != 200) throw IOException("Privacy relay HTTP ${resp.code}")
                if (!type.trim().startsWith(Ohttp.RESPONSE_MEDIA_TYPE, ignoreCase = true)) {
                    throw IOException("Privacy relay: unexpected content type '$type'")
                }
                val decoded = Bhttp.decodeResponse(Ohttp.decapsulateResponse(ctx, bytes))
                return toOkHttp(original, resp, decoded)
            }
        } catch (e: IOException) { throw e }
        catch (e: Exception) { throw IOException("Privacy relay exchange failed: ${e.message}", e) }
    }

    companion object {
        const val MAX_RESPONSE = 16 * 1024 * 1024
        private val HOP_BY_HOP = setOf("connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade", "te", "host", "content-length")

        fun isEligible(req: Request, t: OhttpTransport): Boolean {
            val url = req.url
            if (!url.isHttps || url.host != t.innerAuthority) return false
            val path = url.encodedPath
            if (!path.startsWith("/v2/")) return false
            if (req.header("Upgrade") != null) return false
            return true
        }

        fun authority(url: HttpUrl): String =
            if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"

        fun toBhttp(req: Request, t: OhttpTransport): Bhttp.Request {
            val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readByteArray() } ?: ByteArray(0)
            val headers = ArrayList<Pair<String, String>>()
            for (i in 0 until req.headers.size) {
                val name = req.headers.name(i).lowercase()
                if (name !in HOP_BY_HOP) headers += name to req.headers.value(i)
            }
            val ct = req.body?.contentType()
            if (ct != null && headers.none { it.first == "content-type" }) headers += "content-type" to ct.toString()
            val path = req.url.encodedPath + (req.url.encodedQuery?.let { "?$it" } ?: "")
            return Bhttp.Request(req.method, "https", authority(req.url), path, headers, body)
        }

        /** RFC 9458 §5.3: problem type `https://iana.org/assignments/http-problem-types#ohttp-key`. */
        fun isKeyMismatch(code: Int, contentType: String, body: ByteArray): Boolean {
            if (code !in 400..499) return false
            if (!contentType.contains("problem+json", ignoreCase = true)) return false
            return String(body, Charsets.UTF_8).contains("http-problem-types#ohttp-key")
        }

        fun toOkHttp(original: Request, outer: Response, inner: Bhttp.Response): Response {
            val mime = inner.header("content-type")
            val b = Response.Builder()
                .request(original)
                .protocol(outer.protocol)
                .code(inner.status)
                .message("OHTTP")
                .sentRequestAtMillis(outer.sentRequestAtMillis)
                .receivedResponseAtMillis(outer.receivedResponseAtMillis)
            for ((n, v) in inner.headers) if (n.lowercase() !in HOP_BY_HOP) b.addHeader(n, v)
            return b.body(inner.content.toResponseBody(mime?.toMediaTypeOrNull())).build()
        }

        private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
            while (true) {
                val n = read(buf); if (n < 0) break
                if (out.size() + n > limit) throw IOException("Privacy relay response too large")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
