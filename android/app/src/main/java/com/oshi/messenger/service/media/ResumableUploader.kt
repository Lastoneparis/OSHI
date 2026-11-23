package com.oshi.messenger.service.media

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ResumableUploader — chunked, resumable upload for large encrypted payloads.
 *
 * Verbatim port of `OSHI/ResumableUploader.swift`. Every constant and every wire
 * detail below cites the Swift line it comes from; the server is live and FROZEN, so
 * the protocol is not negotiable.
 *
 * ## Why
 *
 * The front nginx caps a single POST at 50 MB (`ResumableUploader.swift:9`). Android's
 * only upload path today is one foreground POST (`VPSClient.uploadToIPFS`), so any raw
 * file above ~35 MB (base64 +33 % then JSON-wrapped) 413s and the send fails outright,
 * with no resume of any kind. This uploads in ≤8 MB chunks that each slip under the
 * wall, appends them by byte offset, and — on interruption — asks the server how many
 * bytes it already holds and continues from exactly there.
 *
 * The finalized content id is the SAME CIDv0 the single-POST blob store produces
 * (`ResumableUploader.swift:13-15`), so recipients fetch through the existing
 * `/api/ipfs/proxy/{cid}` path with no changes.
 *
 * ## Protocol (ResumableUploader.swift:116-173)
 *
 * | step     | request                                                   | success                     |
 * |----------|-----------------------------------------------------------|-----------------------------|
 * | init     | `POST  {base}/init` JSON `{"size":n,"name":tag}`           | 200 `{"uploadId":"…"}`      |
 * | offset   | `GET   {base}/offset/{id}`                                 | 200 `{"offset":n}`          |
 * | chunk    | `PATCH {base}/chunk/{id}` octet-stream, `upload-offset: n` | 200 `{"offset":newOffset}`  |
 * |          |                                                            | 409 `{"offset":serverOffset}` → resync, never restart |
 * | finalize | `POST  {base}/finalize/{id}`                               | 200 `{"IpfsHash":"Qm…"}`    |
 *
 * ## Testability
 *
 * The HTTP surface is behind [Transport] and the resume map behind [ResumeStore], so
 * the whole state machine (fresh upload, resume from a server offset, 409 resync,
 * finalize, resume-record cleanup) runs in a JVM unit test with no device and no
 * network. [OkHttpTransport] and [PrefsResumeStore] are the production wiring.
 */
class ResumableUploader(
    private val transport: Transport,
    private val resumeStore: ResumeStore,
    /** iOS `ResumableUploader.swift:46` — `chunkSize = 8 * 1024 * 1024`. */
    private val chunkSize: Int = CHUNK_SIZE
) {

    companion object {
        /**
         * Payloads at or above this size MUST use the chunked path.
         * iOS `ResumableUploader.swift:40` — `kResumableUploadThreshold = 12 * 1024 * 1024`.
         * (The comment at `:31-39` explains why it was lowered from 45 MB: a ~41 MB
         * one-shot POST routinely stalled on a slow Tor circuit and surfaced as
         * "tap to retry".)
         */
        const val THRESHOLD_BYTES = 12 * 1024 * 1024

        /** iOS `ResumableUploader.swift:46` — 8 MB, well under the 50 MB nginx wall. */
        const val CHUNK_SIZE = 8 * 1024 * 1024

        /** iOS `ResumableUploader.swift:69` — `payload.count <= 300 * 1024 * 1024`. */
        const val MAX_PAYLOAD_BYTES = 300L * 1024 * 1024

        /** iOS `ResumableUploader.swift:45`. `VPS_URL` on Android already carries the host. */
        const val PATH_PREFIX = "/api/upload"

        /** iOS `ResumableUploader.swift:55` — `timeoutIntervalForRequest = 120`. */
        const val REQUEST_TIMEOUT_SECONDS = 120L

        /** iOS `ResumableUploader.swift:179` — `oshi_resumable_uploads.json`. */
        const val RESUME_PREFS_NAME = "oshi_resumable_uploads"

        /** Should this payload take the chunked path? iOS routes on this at `MessageManager.swift:4467`. */
        fun shouldUseResumable(payloadSize: Int): Boolean = payloadSize > THRESHOLD_BYTES
    }

    // =====================================================================
    // Seams
    // =====================================================================

    /** Outcome of a single `PATCH /chunk/{id}`. iOS `ResumableUploader.swift:143`. */
    sealed class ChunkResult {
        data class Accepted(val newOffset: Int) : ChunkResult()
        data class Resync(val serverOffset: Int) : ChunkResult()
    }

    /** A raw HTTP response: status plus (optionally) a parsed JSON body. */
    data class HttpResult(val code: Int, val json: JSONObject?)

    interface Transport {
        fun post(path: String, jsonBody: String?): HttpResult
        fun get(path: String): HttpResult
        fun patch(path: String, headers: Map<String, String>, body: ByteArray): HttpResult
    }

    /** `contentTag -> uploadId`. iOS `ResumableUploader.swift:51,177-191`. */
    interface ResumeStore {
        fun get(contentTag: String): String?
        fun put(contentTag: String, uploadId: String)
        fun remove(contentTag: String)
    }

    class ResumableException(val kind: Kind, val statusCode: Int = -1) :
        Exception("Resumable upload failed: $kind (HTTP $statusCode)") {
        /** iOS `ResumableUploader.swift:20-27` — `enum ResumableError`. */
        enum class Kind { INIT_FAILED, CHUNK_FAILED, FINALIZE_FAILED, INCOMPLETE, BAD_RESPONSE, PAYLOAD_TOO_LARGE }
    }

    // =====================================================================
    // Public API
    // =====================================================================

    /**
     * Upload [payload] in resumable chunks and return the CIDv0 content hash.
     *
     * @param contentTag stable, deterministic id for this exact content — reusing it
     *   is what lets an interrupted upload resume instead of restarting
     *   (`ResumableUploader.swift:64-65`).
     * @param progress reports 0…1 by bytes ACKED by the server, not bytes written
     *   (`:87,102,109`).
     */
    fun upload(payload: ByteArray, contentTag: String, progress: (Double) -> Unit = {}): String {
        // iOS :69
        if (payload.size > MAX_PAYLOAD_BYTES) {
            throw ResumableException(ResumableException.Kind.PAYLOAD_TOO_LARGE)
        }
        val total = payload.size

        // iOS :74-84 — resume an existing server-side partial when we have one for
        // this content, otherwise initialise a fresh upload.
        var uploadId: String? = resumeStore.get(contentTag)
        var offset = 0
        val existing = uploadId
        val serverOffset = if (existing != null) queryOffsetOrNull(existing) else null
        if (existing != null && serverOffset != null) {
            offset = serverOffset
        } else {
            uploadId = initUpload(size = total, name = contentTag)
            resumeStore.put(contentTag, uploadId)
            offset = 0
        }
        val id = uploadId ?: throw ResumableException(ResumableException.Kind.BAD_RESPONSE)

        // A server offset beyond our payload means the tag was reused for different
        // content — the id is poisoned, so start over rather than finalize garbage.
        if (offset > total) {
            resumeStore.remove(contentTag)
            val fresh = initUpload(size = total, name = contentTag)
            resumeStore.put(contentTag, fresh)
            return uploadLoop(payload, contentTag, fresh, 0, total, progress)
        }

        return uploadLoop(payload, contentTag, id, offset, total, progress)
    }

    private fun uploadLoop(
        payload: ByteArray,
        contentTag: String,
        id: String,
        startOffset: Int,
        total: Int,
        progress: (Double) -> Unit
    ): String {
        var offset = startOffset
        progress(if (total > 0) offset.toDouble() / total else 0.0)

        // iOS :91-103 — stream chunks from `offset` to the end. A 409 resyncs to the
        // server's real offset and continues; never a full restart.
        var guardCounter = 0
        while (offset < total) {
            val end = minOf(offset + chunkSize, total)
            val chunk = payload.copyOfRange(offset, end)
            when (val r = putChunk(id, offset, chunk)) {
                is ChunkResult.Accepted -> offset = r.newOffset
                is ChunkResult.Resync -> offset = r.serverOffset
            }
            progress(if (total > 0) offset.toDouble() / total else 1.0)
            // A server that keeps answering with the same offset would spin forever.
            // iOS relies on URLSession's own timeouts; be explicit here.
            if (++guardCounter > (total / chunkSize + 1) * 4 + 8) {
                throw ResumableException(ResumableException.Kind.INCOMPLETE)
            }
        }

        val cid = finalize(id)
        // iOS :106-108 — success: drop the resume record, the content now lives under its CID.
        resumeStore.remove(contentTag)
        progress(1.0)
        return cid
    }

    // =====================================================================
    // Protocol steps — iOS ResumableUploader.swift:116-173
    // =====================================================================

    /** iOS `:116-129`. */
    internal fun initUpload(size: Int, name: String): String {
        val body = JSONObject().apply {
            put("size", size)
            put("name", name)
        }.toString()
        val res = transport.post("$PATH_PREFIX/init", body)
        val id = res.json?.optString("uploadId")?.takeIf { it.isNotEmpty() }
        if (res.code != 200 || id == null) {
            throw ResumableException(ResumableException.Kind.INIT_FAILED, res.code)
        }
        return id
    }

    /** iOS `:131-141`. Returns null when the server has no such upload (id expired / GC'd). */
    internal fun queryOffsetOrNull(id: String): Int? {
        return try {
            val res = transport.get("$PATH_PREFIX/offset/$id")
            if (res.code != 200) return null
            val j = res.json ?: return null
            if (!j.has("offset")) return null
            j.optInt("offset", -1).takeIf { it >= 0 }
        } catch (e: Throwable) {
            null
        }
    }

    /** iOS `:145-160`. */
    internal fun putChunk(id: String, offset: Int, chunk: ByteArray): ChunkResult {
        val res = transport.patch(
            "$PATH_PREFIX/chunk/$id",
            mapOf(
                "Content-Type" to "application/octet-stream",
                // iOS :149 — header name is lowercase `upload-offset`.
                "upload-offset" to offset.toString()
            ),
            chunk
        )
        val j = res.json
        if (res.code == 200 && j != null && j.has("offset")) {
            return ChunkResult.Accepted(j.optInt("offset"))
        }
        if (res.code == 409 && j != null && j.has("offset")) {
            return ChunkResult.Resync(j.optInt("offset"))
        }
        throw ResumableException(ResumableException.Kind.CHUNK_FAILED, res.code)
    }

    /** iOS `:162-173`. Reads `IpfsHash` — the same key the single-POST pin returns. */
    internal fun finalize(id: String): String {
        val res = transport.post("$PATH_PREFIX/finalize/$id", null)
        val cid = res.json?.optString("IpfsHash")?.takeIf { it.isNotEmpty() }
        if (res.code != 200 || cid == null) {
            throw ResumableException(ResumableException.Kind.FINALIZE_FAILED, res.code)
        }
        return cid
    }
}

// =========================================================================
// Production wiring
// =========================================================================

/** OkHttp-backed [ResumableUploader.Transport] against `baseUrl` (e.g. `https://oshi-messenger.com`). */
class OkHttpTransport(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(ResumableUploader.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(ResumableUploader.REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) : ResumableUploader.Transport {

    private fun exec(req: Request): ResumableUploader.HttpResult {
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string()
            val json = try { if (raw.isNullOrEmpty()) null else JSONObject(raw) } catch (_: Throwable) { null }
            return ResumableUploader.HttpResult(resp.code, json)
        }
    }

    override fun post(path: String, jsonBody: String?): ResumableUploader.HttpResult {
        val body = (jsonBody ?: "").toRequestBody("application/json".toMediaType())
        return exec(Request.Builder().url(baseUrl.trimEnd('/') + path).post(body).build())
    }

    override fun get(path: String): ResumableUploader.HttpResult =
        exec(Request.Builder().url(baseUrl.trimEnd('/') + path).get().build())

    override fun patch(
        path: String,
        headers: Map<String, String>,
        body: ByteArray
    ): ResumableUploader.HttpResult {
        val b = Request.Builder().url(baseUrl.trimEnd('/') + path)
            .patch(body.toRequestBody("application/octet-stream".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        return exec(b.build())
    }
}

/**
 * SharedPreferences-backed resume map.
 * iOS persists the same map to `Application Support/oshi_resumable_uploads.json`
 * (`ResumableUploader.swift:177-191`) so a relaunch mid-upload still resumes.
 */
class PrefsResumeStore(private val prefs: android.content.SharedPreferences) :
    ResumableUploader.ResumeStore {
    override fun get(contentTag: String): String? = prefs.getString(contentTag, null)
    override fun put(contentTag: String, uploadId: String) {
        prefs.edit().putString(contentTag, uploadId).apply()
    }
    override fun remove(contentTag: String) {
        prefs.edit().remove(contentTag).apply()
    }
}

/** In-memory store — used by tests and as a safe fallback when prefs are unavailable. */
class InMemoryResumeStore : ResumableUploader.ResumeStore {
    private val map = HashMap<String, String>()
    override fun get(contentTag: String): String? = map[contentTag]
    override fun put(contentTag: String, uploadId: String) { map[contentTag] = uploadId }
    override fun remove(contentTag: String) { map.remove(contentTag) }
}

/**
 * One-line construction for the production call site.
 *
 * Intended wiring (owned by another agent — `network/vps/VPSClient.kt`), inside
 * `sendMessageViaIPFS` right before `uploadToIPFS(envelopeJson.toString())`:
 *
 * ```kotlin
 * val payload = envelopeJson.toString().toByteArray(Charsets.UTF_8)
 * val ipfsHash = if (ResumableUploader.shouldUseResumable(payload.size)) {
 *     withContext(Dispatchers.IO) {
 *         ResumableUploaders.create(appContext, currentUrl)
 *             .upload(payload, contentTag = messageId) { /* 0…1 progress */ }
 *     }
 * } else {
 *     uploadToIPFS(envelopeJson.toString())
 * }
 * ```
 *
 * `upload` is blocking and throws [ResumableUploader.ResumableException]; call it on
 * `Dispatchers.IO` and let the existing `try/catch` around `sendMessageViaIPFS` turn a
 * failure into the same `null` the single POST already returns.
 *
 * `contentTag` MUST be stable for the same content across retries — the message id is
 * the natural choice — or a retry starts a new server-side upload instead of resuming
 * (`ResumableUploader.swift:64-65`).
 */
object ResumableUploaders {
    fun create(context: android.content.Context, baseUrl: String): ResumableUploader =
        ResumableUploader(
            transport = OkHttpTransport(baseUrl),
            resumeStore = PrefsResumeStore(
                context.getSharedPreferences(
                    ResumableUploader.RESUME_PREFS_NAME,
                    android.content.Context.MODE_PRIVATE
                )
            )
        )
}
