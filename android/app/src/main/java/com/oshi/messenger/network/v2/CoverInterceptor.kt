package com.oshi.messenger.network.v2

import com.oshi.messenger.service.CoverCodec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

/**
 * __SIGNED_NO_SILENT_REPLAY_2026_11_18__ The transport settings every signed /v2
 * client uses: no redirect (a captive portal's 302 is never followed), and NO
 * transport-level retry. OkHttp's default `retryOnConnectionFailure(true)` silently
 * re-sends a request whose connection dropped — byte for byte, SAME signature and
 * timestamp — which the relay logs as a replay (and, under `enforce`, rejects).
 * Every client already retries at the APP level, where each attempt calls
 * `V2Signer.sign` again (fresh timestamp); this removes the hidden second layer.
 */
fun OkHttpClient.Builder.noSilentReplay(): OkHttpClient.Builder =
    followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)

/** No retry or direct-transport fallback here: failed cover transport stays failed. */
class CoverInterceptor(private val mode: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val selected = mode() ?: return chain.proceed(original)
        if (!original.url.encodedPath.startsWith("/v2/")) return chain.proceed(original)
        try {
            val body = Buffer().also { original.body?.writeTo(it) }.readByteArray()
            val headers = JSONObject()
            original.headers.names().forEach { key ->
                if (key.startsWith("x-oshi-", true) || key.equals("content-type", true)) headers.put(key.lowercase(), original.header(key))
            }
            original.body?.contentType()?.let { headers.put("content-type", it.toString()) }
            val path = original.url.encodedPath + (original.url.encodedQuery?.let { "?$it" } ?: "")
            val record = JSONObject().put("v", 1).put("method", original.method).put("path", path)
                .put("headers", headers).put("body", Base64.getEncoder().encodeToString(body))
            val carrier = CoverCodec.encode(record, selected)
            // Per-request outer presentation (path alias, analytics-style query,
            // optional header set and order, Content-Type spelling). See
            // CoverPresentation for why each choice is relay-compatible.
            val look = CoverPresentation.next()
            val outer = okhttp3.Request.Builder()
                .url(original.url.newBuilder().encodedPath(look.path).encodedQuery(look.query).fragment(null).build())
                .apply { look.headers.forEach { (name, value) -> header(name, value) } }
                // __COVER_NO_GZIP_2026_11_18__ (audit finding 5) Ask for an UNcompressed
                // reply. Left alone, OkHttp adds `Accept-Encoding: gzip`, the relay /
                // nginx gzips the cover reply, and the compressed size then separates an
                // empty poll from a full one (padding is random, the JSON/base64 around
                // the record is not). The server patch also refuses to compress these
                // routes; this is the client half of that defence in depth.
                .header("Accept-Encoding", "identity")
                .post(carrier.body.toRequestBody(look.contentType.toMediaType()))
                .apply { carrier.cookie?.let { header("Cookie", it) } }.build()
            // __COVER_SLOW_LINK_2026_11_18__ Padding makes a cover request up to ~3x the
            // bytes of the request it carries (a 120 KB photo chunk goes out as ~350 KB),
            // and the reply waits for all of it. On a 64 kbit/s link the fixed 30/60 s
            // timeouts of the V2 clients expired before the relay could answer (netem
            // "edge": every media upload timed out at least once). Give the read and
            // write timeouts a budget proportional to the bytes actually sent.
            val budgetMs = coverTimeoutMs(carrier.body.size)
            val timed = chain.withReadTimeout(maxOf(chain.readTimeoutMillis(), budgetMs), java.util.concurrent.TimeUnit.MILLISECONDS)
                .withWriteTimeout(maxOf(chain.writeTimeoutMillis(), budgetMs), java.util.concurrent.TimeUnit.MILLISECONDS)
            timed.proceed(outer).use { response ->
                if (!response.isSuccessful) throw IOException("Obfuscated transport HTTP ${response.code}")
                val source = response.body ?: throw IOException("Missing cover response")
                // __COVER_MIDDLEBOX_2026_11_18__ The relay never compresses a cover reply
                // and always labels it application/json, but a middlebox in front of it
                // may do either (tools/covert-regression/netem: "gzip" and "rewrite"
                // conditions — every Android send was re-sent until its retries ran out,
                // and no message was received, while iOS, whose URLSession inflates gzip
                // itself, kept working). We asked for `identity`, so OkHttp does not
                // inflate for us: do it here, bounded. The outer Content-Type is not
                // trusted either way — the closed JSON schema in CoverCodec.decode is
                // the check — so a relabelled reply is read as JSON.
                val gzipped = response.header("Content-Encoding").orEmpty().trim().equals("gzip", ignoreCase = true)
                val bytes = (if (gzipped) java.util.zip.GZIPInputStream(source.byteStream()) else source.byteStream())
                    .use { it.readBytesBounded(CoverCodec.MAX * 4) }
                val decoded = CoverCodec.decode(bytes, "application/json")
                val status = decoded.getInt("status")
                require(status in 200..599)
                val innerHeaders = decoded.getJSONObject("headers")
                val mime = innerHeaders.optString("content-type", "application/octet-stream")
                val payload = Base64.getDecoder().decode(decoded.getString("body"))
                val result = response.newBuilder().request(original).code(status).message("V2 response")
                    .removeHeader("Content-Length").removeHeader("Content-Encoding").header("Content-Type", mime)
                    .body(payload.toResponseBody(mime.toMediaType()))
                innerHeaders.optString("retry-after").takeIf { it.isNotEmpty() }?.let { result.header("Retry-After", it) }
                return result.build()
            }
        } catch (e: IOException) { throw e }
        catch (e: Exception) { throw IOException("Invalid obfuscated response", e) }
    }
}
/**
 * Read/write timeout for a cover request whose outer body is [outerBytes]: 15 s plus
 * one second per 4 KiB (i.e. still finishing on a ~32 kbit/s link), at most 10 min.
 * Never shorter than the client's own timeout (the caller takes the max).
 */
internal fun coverTimeoutMs(outerBytes: Int): Int = minOf(600_000L, 15_000L + outerBytes.toLong() * 1000 / 4096).toInt()

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break; if (out.size() + n > limit) throw IOException("Cover response too large"); out.write(buffer, 0, n) }
    return out.toByteArray()
}
