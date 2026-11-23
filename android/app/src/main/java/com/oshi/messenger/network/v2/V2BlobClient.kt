package com.oshi.messenger.network.v2

import android.util.Log
import com.oshi.messenger.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** `POST /v2/blobs` response — iOS `V2.ReserveResult` (V2ClientModels.swift:227-231). */
data class V2Reservation(val blobId: String, val chunkSize: Int, val missing: List<Int>)

/** `GET /v2/blobs/{id}/status` response — iOS `V2.BlobStatus` (V2ClientModels.swift:234-239). */
data class V2BlobStatus(val blobId: String, val missing: List<Int>, val committed: Boolean, val expiresAt: Double)

/**
 * OSHI V2 blob store (`/v2/blobs`), byte-compatible with iOS `V2Client`
 * (V2Client.swift:197-360, upload orchestration :444-495, download :589-624).
 *
 * BODY-HASH DISCIPLINE (the part that silently 401s if you get it wrong) — the
 * blob handlers verify the signature BEFORE reading the streamed body, so
 * `oshi_auth` hashes the literal two bytes `""` for every route except commit,
 * which goes through the JSON body reader and therefore hashes the (empty) body:
 *
 *   reserve    POST   /v2/blobs                  → hash the exact JSON bytes sent
 *   upload     PUT    /v2/blobs/{id}/chunk/{i}   → hash `""`   (V2Client.swift:259)
 *   status     GET    /v2/blobs/{id}/status      → hash `""`   (V2Client.swift:326)
 *   commit     POST   /v2/blobs/{id}/commit      → hash 0 bytes (V2Client.swift:299-303)
 *   download   GET    /v2/blobs/{id}/chunk/{i}   → hash `""`   (V2Client.swift:340)
 *   delete     DELETE /v2/blobs/{id}             → hash `""`   (V2Client.swift:353)
 *
 * Every route carries `x-oshi-user` (the server authorises owner-or-recipient), and
 * `blobId` is NOT percent-encoded (V2Client.swift:779-781).
 */
@Singleton
class V2BlobClient @Inject constructor(
    private val coverSettings: CoverSettings,
    private val signer: V2Signer,
) {
    private val client = OkHttpClient.Builder()
        .addInterceptor(coverSettings.interceptor())
        .noSilentReplay()   // __SIGNED_NO_SILENT_REPLAY_2026_11_18__ (CoverInterceptor.kt)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Reserve a blob for one recipient. Returns null on any non-2xx / transport error. */
    suspend fun reserve(recipientKey: String, totalSize: Int, chunkCount: Int): V2Reservation? =
        withContext(Dispatchers.IO) {
            if (totalSize <= 0 || chunkCount < 1) return@withContext null
            if (totalSize > MAX_BLOB_BYTES) {
                Log.w(TAG, "reserve refused: $totalSize B over the $MAX_BLOB_BYTES B ciphertext cap")
                return@withContext null
            }
            try {
                val body = JSONObject().apply {
                    put("ownerKey", signer.userKey())
                    put("recipientKey", recipientKey)
                    put("totalSize", totalSize)
                    put("chunkCount", chunkCount)
                }.toString().toByteArray(Charsets.UTF_8)
                val path = "/v2/blobs"
                // JSON POST → the body hash is the EXACT bytes sent, and reserve is the
                // ONE blob route iOS does NOT give `x-oshi-user`: the owner travels in
                // the body as `ownerKey` (V2Client.swift:214-227 — no withUserHeader).
                val headers = signer.sign("POST", path, body, withUserHeader = false)
                val req = Request.Builder()
                    .url("${BuildConfig.VPS_URL}$path")
                    .post(body.toRequestBody(JSON))
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code !in 200..299) {
                        Log.w(TAG, "reserve HTTP ${resp.code}"); return@withContext null
                    }
                    val o = JSONObject(resp.body?.string().orEmpty())
                    V2Reservation(
                        blobId = o.getString("blobId"),
                        chunkSize = o.optInt("chunkSize", OSHICryptoV2.DEFAULT_CHUNK_SIZE),
                        missing = o.optJSONArray("missing").toIntList(),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "reserve failed: ${e.message}"); null
            }
        }

    /** PUT one chunk. Idempotent server-side, so a retry after an ambiguous drop is safe. */
    suspend fun uploadChunk(blobId: String, index: Int, data: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val path = "/v2/blobs/$blobId/chunk/$index"
                // Verified BEFORE the streamed body is read ⇒ sign over `""`, not the bytes.
                val headers = signer.sign("PUT", path, signer.emptyJsonStringBody(), withUserHeader = true)
                val req = Request.Builder()
                    .url("${BuildConfig.VPS_URL}$path")
                    .put(data.toRequestBody(OCTET))
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .build()
                client.newCall(req).execute().use { it.code in 200..299 }
            } catch (e: Exception) {
                Log.w(TAG, "uploadChunk[$index] failed: ${e.message}"); false
            }
        }

    /** Non-destructive status: which chunk indices the server is still missing. */
    suspend fun status(blobId: String): V2BlobStatus? = withContext(Dispatchers.IO) {
        try {
            val path = "/v2/blobs/$blobId/status"
            val headers = signer.sign("GET", path, signer.emptyJsonStringBody(), withUserHeader = true)
            val req = Request.Builder().url("${BuildConfig.VPS_URL}$path").get()
                .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) return@withContext null
                val o = JSONObject(resp.body?.string().orEmpty())
                V2BlobStatus(
                    blobId = o.optString("blobId", blobId),
                    missing = o.optJSONArray("missing").toIntList(),
                    committed = o.optBoolean("committed", false),
                    expiresAt = o.optDouble("expiresAt", 0.0),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "status failed: ${e.message}"); null
        }
    }

    /** Finalize. 409 means the server still has gaps — the caller re-tops-up and retries. */
    suspend fun commit(blobId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val path = "/v2/blobs/$blobId/commit"
            // Commit DOES go through the JSON body reader ⇒ hash the actual (empty) body.
            val headers = signer.sign("POST", path, ByteArray(0), withUserHeader = true)
            val req = Request.Builder()
                .url("${BuildConfig.VPS_URL}$path")
                .post(ByteArray(0).toRequestBody(JSON))
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute().use { it.code in 200..299 }
        } catch (e: Exception) {
            Log.w(TAG, "commit failed: ${e.message}"); false
        }
    }

    /** Download one chunk's opaque `ct‖tag`. */
    suspend fun downloadChunk(blobId: String, index: Int): ByteArray? = withContext(Dispatchers.IO) {
        downloadChunkBlocking(blobId, index)
    }

    /**
     * Blocking body of [downloadChunk]. Kept separate so the streaming download can
     * drive it from inside a plain loop without a `runBlocking` bridge; OkHttp's
     * `execute()` is synchronous either way. ALWAYS call on an IO thread.
     */
    private fun downloadChunkBlocking(blobId: String, index: Int): ByteArray? {
        return try {
            val path = "/v2/blobs/$blobId/chunk/$index"
            val headers = signer.sign("GET", path, signer.emptyJsonStringBody(), withUserHeader = true)
            val req = Request.Builder().url("${BuildConfig.VPS_URL}$path").get()
                .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) {
                    Log.w(TAG, "downloadChunk[$index] HTTP ${resp.code}"); return null
                }
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "downloadChunk[$index] failed: ${e.message}"); null
        }
    }

    /** Delete-after-delivery. 404 counts as success — the blob is already gone. */
    suspend fun delete(blobId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val path = "/v2/blobs/$blobId"
            val headers = signer.sign("DELETE", path, signer.emptyJsonStringBody(), withUserHeader = true)
            val req = Request.Builder().url("${BuildConfig.VPS_URL}$path").delete()
                .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { it.code in 200..299 || it.code == 404 }
        } catch (e: Exception) {
            Log.w(TAG, "delete failed: ${e.message}"); false
        }
    }

    // ---- Orchestration ------------------------------------------------------

    /**
     * reserve → PUT every missing chunk → re-check status and top up → commit.
     * Mirrors iOS `uploadBlob` (V2Client.swift:444-495), including the [V2-C2]
     * status top-up before commit so a dropped PUT surfaces here instead of as a
     * 409 (or, worse, a committed-but-incomplete file). Returns the blobId, or
     * null if any step failed — the caller then falls back to the legacy path.
     *
     * @param resumeBlobId a reservation a PREVIOUS attempt made for these exact bytes
     *   (see `service/media/V2SendResumeStore.kt` — the digest binding there is what
     *   makes reusing the key/nonce safe). Only the indices `/status` still reports
     *   missing are sent. Null → today's behaviour, a fresh reserve every time.
     * @param onReserved invoked with a FRESH blobId the instant `reserve` returns —
     *   before the first chunk goes out — so the ledger survives a process that dies
     *   mid-transfer (iOS `V2MessageRouter.swift:1385-1400`).
     */
    suspend fun uploadBlob(
        recipientKey: String,
        chunks: List<ByteArray>,
        resumeBlobId: String? = null,
        onReserved: (suspend (String) -> Unit)? = null,
    ): String? {
        if (chunks.isEmpty()) return null
        val totalSize = chunks.sumOf { it.size }
        // RESUME (iOS V2MessageRouter.swift:775-802). A prior attempt's reservation is
        // reused when the server still holds it and still reports the same chunkCount;
        // the caller has already proved the plaintext is unchanged (the digest binding
        // in V2SendResumeStore), so re-encryption is byte-identical and the gaps can
        // simply be topped up. Anything unexpected falls through to a fresh reserve —
        // "no resume" is always a valid answer, a wrong resume is not.
        val reservation = resumeBlobId?.let { id ->
            val st = status(id)
            when {
                st == null -> null
                st.committed -> V2Reservation(id, OSHICryptoV2.DEFAULT_CHUNK_SIZE, emptyList())
                st.missing.any { it < 0 || it >= chunks.size } -> {
                    Log.w(TAG, "resume $id lists a chunk outside 0..<${chunks.size} — starting fresh"); null
                }
                else -> V2Reservation(id, OSHICryptoV2.DEFAULT_CHUNK_SIZE, st.missing)
            }
        } ?: reserve(recipientKey, totalSize, chunks.size)?.also { onReserved?.invoke(it.blobId) }
            ?: return null
        // A resumed reservation with NOTHING missing must not be re-uploaded whole.
        val resumed = resumeBlobId != null && reservation.blobId == resumeBlobId
        suspend fun put(indices: List<Int>): Boolean {
            for (n in indices) {
                if (n < 0 || n >= chunks.size) {
                    Log.w(TAG, "server wants chunk $n, out of range 0..<${chunks.size}"); return false
                }
                if (!uploadChunk(reservation.blobId, n, chunks[n])) return false
            }
            return true
        }
        // A fresh reserve lists every index as missing; a resumed one lists the gaps —
        // and an empty gap list on a RESUMED blob means "all present", not "send it all".
        val first = when {
            reservation.missing.isNotEmpty() -> reservation.missing
            resumed -> emptyList()
            else -> chunks.indices.toList()
        }
        if (!put(first)) return null
        val st = status(reservation.blobId)
        if (st != null && st.missing.isNotEmpty() && !put(st.missing)) return null
        return if (commit(reservation.blobId)) reservation.blobId else null
    }

    /**
     * Fetch every chunk IN ORDER — iOS `downloadBlob` (V2Client.swift:589-624).
     * Order is load-bearing: the per-chunk AEAD nonce is `fileNonce ‖ u32be(i)`, so
     * a scrambled reassembly reads as "corrupt media" rather than as a bug here.
     * Returns null if any chunk is missing, so the caller can hold the relay ack.
     */
    suspend fun downloadBlob(blobId: String, chunkCount: Int): List<ByteArray>? {
        if (chunkCount < 1) return null
        val out = ArrayList<ByteArray>(chunkCount)
        for (i in 0 until chunkCount) {
            out.add(downloadChunk(blobId, i) ?: return null)
        }
        return out
    }

    /**
     * STREAMING download — the memory-bounded counterpart of [downloadBlob].
     * iOS `downloadAndDecryptFileToDisk` (MessageManager+V2.swift:715-722) on top of
     * the streaming crypt at `OSHICryptoV2.swift:693-760`.
     *
     * [downloadBlob] holds every ciphertext chunk in a `List<ByteArray>` and
     * [OSHICryptoV2.decryptFile] then allocates the whole plaintext again, so the peak
     * is ~2× the file size. Above the 200 MB ceiling this app declares, with no
     * `android:largeHeap`, a large inbound video is an OOM crash loop. Here each chunk
     * is fetched, opened and appended to [out] in turn: peak heap is ONE 2 MiB chunk
     * plus its plaintext, whatever the file size.
     *
     * The bytes written are byte-identical to what [OSHICryptoV2.decryptFile] returns —
     * this is an internal restructuring, not a protocol change. [out] is deleted on any
     * failure so a partial file can never be mistaken for media.
     *
     * @return the plaintext size, or null when a chunk was unavailable or the blob
     *   failed to open (the caller then holds the relay ack, exactly as before).
     */
    suspend fun downloadAndDecryptToFile(
        blobId: String,
        chunkCount: Int,
        fileKey: ByteArray,
        fileNonce: ByteArray,
        manifest: ByteArray,
        out: java.io.File,
    ): Long? = withContext(Dispatchers.IO) {
        if (chunkCount < 1) return@withContext null
        try {
            out.parentFile?.mkdirs()
            var written = 0L
            java.io.BufferedOutputStream(java.io.FileOutputStream(out)).use { sink ->
                written = OSHICryptoV2Streaming.decryptToStream(
                    fileKey = fileKey,
                    fileNonce = fileNonce,
                    manifest = manifest,
                    out = sink,
                    expectedChunkCount = chunkCount,
                ) { i -> downloadChunkBlocking(blobId, i) }
            }
            written
        } catch (e: Throwable) {
            Log.w(TAG, "streaming download of ${blobId.take(12)} failed: ${e.message}")
            out.delete()
            null
        }
    }

    companion object {
        private const val TAG = "V2BlobClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()

        /** iOS `V2.maxPlaintextBytes` (V2ClientModels.swift:248). */
        const val MAX_PLAINTEXT_BYTES = 200 * 1024 * 1024

        /** iOS `V2.maxBlobBytes` = 200 MB + 1 MiB AEAD slack (V2ClientModels.swift:255). */
        const val MAX_BLOB_BYTES = 200 * 1024 * 1024 + 1024 * 1024

        private fun org.json.JSONArray?.toIntList(): List<Int> {
            if (this == null) return emptyList()
            return (0 until length()).map { optInt(it) }
        }
    }
}
