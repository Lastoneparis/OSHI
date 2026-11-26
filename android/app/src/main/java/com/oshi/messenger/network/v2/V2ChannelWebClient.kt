package com.oshi.messenger.network.v2

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * __CHANNEL_WEB_2026_11_26__ Public channel web preview client — `/v2/c/<handle>…`
 * (ServerVPS/v2/PUBLIC_CHANNEL_WEB_SPEC.md §2-4).
 *
 * Every write and the admin status are signed exactly like `/v2/g/` ([V2GroupHandleClient]):
 * `METHOD\nPATH\nsha256hex(body)\nTIMESTAMP` with `x-oshi-user`. Each call signs afresh, so a
 * retry always carries a new timestamp (spec §2: a replayed signature is refused).
 *
 * No Android type in here: the JVM tests drive it against the real Node server.
 */
class V2ChannelWebClient(
    private val http: OkHttpClient,
    private val baseUrl: () -> String,
    private val signer: RequestSigner,
) {
    /** Signed headers for one request (production: [V2Signer.sign] with `withUserHeader`). */
    fun interface RequestSigner {
        fun sign(method: String, path: String, body: ByteArray): Map<String, String>
    }

    /** What a call came to, already sorted into what the caller must do (spec §6). */
    sealed class Outcome {
        data class Ok(val json: JSONObject) : Outcome()
        /** Network error, 401, 403, 429, 5xx, 507: retry later with a fresh signature. */
        data class Retry(val status: Int, val code: String?) : Outcome()
        /** 400 `unknown-media`: re-upload that image, then publish again. */
        data class UnknownMedia(val mediaId: String?) : Outcome()
        /** 409 `web-preview-off`. */
        object PreviewOff : Outcome()
        /** 404 `unknown-handle` / `unknown-post`. */
        data class NotFound(val code: String?) : Outcome()
        /** Any other 4xx: the request itself is wrong; retrying cannot help. */
        data class Rejected(val status: Int, val code: String?) : Outcome()

        val isOk get() = this is Ok
    }

    fun enable(handle: String, body: String): Outcome = send("PUT", "/v2/c/$handle", body.toByteArray(Charsets.UTF_8), JSON)
    fun disable(handle: String): Outcome = send("DELETE", "/v2/c/$handle", ByteArray(0), null)
    fun upload(handle: String, image: ByteArray): Outcome = send("POST", "/v2/c/$handle/media", image, OCTET)
    fun publish(handle: String, postId: String, body: String): Outcome =
        send("PUT", "/v2/c/$handle/posts/$postId", body.toByteArray(Charsets.UTF_8), JSON)
    fun deletePost(handle: String, postId: String): Outcome = send("DELETE", "/v2/c/$handle/posts/$postId", ByteArray(0), null)
    fun adminStatus(handle: String): Outcome = send("GET", "/v2/c/$handle/admin", ByteArray(0), null)

    /** Public feed (no auth). */
    fun feed(handle: String, before: Int? = null, limit: Int = 50): Outcome {
        val q = buildString {
            append("?limit=").append(limit.coerceIn(1, 50))
            if (before != null) append("&before=").append(before)
        }
        return get("/v2/c/$handle$q")
    }

    /** One public post (no auth). */
    fun post(handle: String, n: Int): Outcome = get("/v2/c/$handle/$n")

    private fun get(pathAndQuery: String): Outcome = try {
        http.newCall(Request.Builder().url(baseUrl() + pathAndQuery).get().build()).execute().use { r ->
            classify(r.code, r.body?.string().orEmpty())
        }
    } catch (e: IOException) {
        Outcome.Retry(0, e.javaClass.simpleName)
    }

    private fun send(method: String, path: String, body: ByteArray, type: okhttp3.MediaType?): Outcome = try {
        val headers = signer.sign(method, path, body)
        val rb = when (method) {
            "GET" -> null
            "DELETE" -> if (body.isEmpty()) null else body.toRequestBody(type)
            else -> body.toRequestBody(type)
        }
        val b = Request.Builder().url(baseUrl() + path)
        when (method) {
            "GET" -> b.get()
            "DELETE" -> if (rb == null) b.delete() else b.delete(rb)
            else -> b.method(method, rb)
        }
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { r -> classify(r.code, r.body?.string().orEmpty()) }
    } catch (e: IOException) {
        Outcome.Retry(0, e.javaClass.simpleName)
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()

        /** Pure, tested: HTTP status + body → [Outcome] (spec §6 table). */
        fun classify(status: Int, body: String): Outcome {
            val o = runCatching { JSONObject(body) }.getOrNull()
            val code = o?.optString("error")?.ifEmpty { null }
            return when {
                status == 200 -> Outcome.Ok(o ?: JSONObject())
                status == 400 && code == "unknown-media" -> Outcome.UnknownMedia(o?.optString("mediaId")?.ifEmpty { null })
                status == 409 -> Outcome.PreviewOff
                status == 404 -> Outcome.NotFound(code)
                status == 401 || status == 403 || status == 408 || status == 429 || status == 507 || status >= 500 ->
                    Outcome.Retry(status, code)
                else -> Outcome.Rejected(status, code)
            }
        }

        /**
         * Minimal JSON string literal: `"` `\\` and control characters escaped, everything else
         * (non-ASCII, `/`) written as is. Hand-written on purpose: org.json orders keys by hash
         * on the JVM and escapes `/` on Android, so the same post would hash differently per
         * runtime; this way the body is byte-for-byte the spec §10 vector everywhere.
         */
        fun jsonString(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (c in s) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == '\u2028' || c == '\u2029' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
            return sb.append('"').toString()
        }

        /** Spec §3.1 body (key order as in the §10 vector). Absent fields stay unchanged on the server. */
        fun buildEnableBody(
            title: String?,
            description: String?,
            searchIndexing: Boolean?,
            avatarMediaId: String? = null,
            clearAvatar: Boolean = false,
        ): String = buildString {
            append("{\"kind\":\"channel\"")
            if (title != null) append(",\"title\":").append(jsonString(title))
            if (description != null) append(",\"description\":").append(jsonString(description))
            if (searchIndexing != null) append(",\"searchIndexing\":").append(searchIndexing)
            if (avatarMediaId != null) append(",\"avatarMediaId\":").append(jsonString(avatarMediaId))
            else if (clearAvatar) append(",\"avatarMediaId\":null")
            append('}')
        }

        /** Spec §3.4 body, key order as in the §10 vector. */
        fun buildPublishBody(text: String, mediaIds: List<String>, createdAt: Long): String = buildString {
            append("{\"text\":").append(jsonString(text))
            append(",\"media\":[").append(mediaIds.joinToString(",") { jsonString(it) }).append(']')
            append(",\"createdAt\":").append(createdAt)
            append('}')
        }

        data class AdminStatus(val enabled: Boolean, val searchIndexing: Boolean, val posts: List<Pair<String, Int>>)

        /** Spec §3.6 answer; null when malformed. */
        fun parseAdmin(o: JSONObject): AdminStatus? {
            if (!o.optBoolean("ok")) return null
            val arr = o.optJSONArray("posts") ?: JSONArray()
            val posts = (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = p.optString("postId").ifEmpty { null } ?: return@mapNotNull null
                val n = p.optInt("n", 0).takeIf { it > 0 } ?: return@mapNotNull null
                id to n
            }
            return AdminStatus(o.optBoolean("enabled"), o.optBoolean("searchIndexing"), posts)
        }

        /** A public post as the feed / post JSON describes it (spec §4). */
        data class PublicPost(val n: Int, val text: String, val createdAt: Long, val mediaCount: Int)

        fun parsePublicPost(p: JSONObject): PublicPost? {
            val n = p.optInt("n", 0).takeIf { it > 0 } ?: return null
            return PublicPost(n, p.optString("text"), p.optLong("createdAt", 0L), p.optJSONArray("media")?.length() ?: 0)
        }

        /** Feed JSON → (posts, nextBefore). */
        fun parseFeed(o: JSONObject): Pair<List<PublicPost>, Int?> {
            val arr = o.optJSONArray("posts") ?: JSONArray()
            val posts = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parsePublicPost) }
            val next = if (o.isNull("nextBefore")) null else o.optInt("nextBefore", 0).takeIf { it > 0 }
            return posts to next
        }
    }
}
