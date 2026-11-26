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
data class V2Reservation(
    val blobId: String,
    val chunkSize: Int,
    val missing: List<Int>,
    /**
     * __GROUP_BLOB_2026_11_25__ `recipients: N` echoed by a server that stored the reservation's
     * recipient LIST (ServerPatches/group_blob_2026_11_25). Null = an older server, which only
     * authorised the single `recipientKey`.
     */
    val recipients: Int? = null,
)

/**
 * __GROUP_BLOB_2026_11_25__ Result of [V2BlobClient.uploadGroupBlob]. [shared] = every listed
 * member may fetch [blobId]; false = an older server, [blobId] serves ONLY the first member and
 * the caller uploads one blob per remaining member (the pre-2026-11-25 path).
 */
data class V2GroupUpload(val blobId: String, val shared: Boolean)

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

    /**
     * Reserve a blob for one recipient. Returns null on any non-2xx / transport error.
     *
     * __GROUP_BLOB_2026_11_25__ [recipientKeys] (a group's members) is sent alongside
     * [recipientKey]; a server that understands it echoes `recipients` ([V2Reservation.recipients]),
     * an older one ignores it and authorises [recipientKey] alone.
     */
    suspend fun reserve(recipientKey: String, totalSize: Int, chunkCount: Int, recipientKeys: List<String>? = null): V2Reservation? =
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
                    if (recipientKeys != null) put("recipientKeys", org.json.JSONArray(recipientKeys))
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
                        recipients = if (o.has("recipients")) o.optInt("recipients", -1).takeIf { it >= 0 } else null,
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
        // __BLOB_RANGE_2026_11_19__ On a network that already showed a per-connection
        // stall (CoverConnectionPolicy window), fetch the chunk in parts small enough for
        // one connection each. A whole-chunk read that dies at the transport level is
        // retried in parts right away (the stall is what opens the window).
        if (CoverConnectionPolicy.freshConnections()) return downloadChunkRanged(blobId, index)
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
        } catch (e: java.io.IOException) {
            Log.w(TAG, "downloadChunk[$index] failed: ${e.message}")
            if (CoverConnectionPolicy.isTransportFailure(e)) {
                CoverConnectionPolicy.onFailure(e)
                downloadChunkRanged(blobId, index)
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "downloadChunk[$index] failed: ${e.message}"); null
        }
    }

    /**
     * __BLOB_RANGE_2026_11_19__ `GET …/chunk/{i}?offset=&length=` until the whole chunk is
     * in (docs/CENSORSHIP_2026_THREAT_MODEL.md §7). 206 + Content-Range = a part; 200 = an
     * old server sent the whole chunk (used as is). Anything else fails the chunk exactly
     * like a whole-chunk failure. Each part is a fresh signed request (new timestamp).
     */
    internal fun downloadChunkRanged(blobId: String, index: Int): ByteArray? {
        val path = "/v2/blobs/$blobId/chunk/$index"
        val part = BlobRange.partLength(coverOn = coverSettings.mode() != null)
        val out = java.io.ByteArrayOutputStream()
        var offset = 0L
        var parts = 0
        var attempts = 0   // consecutive failed attempts at the CURRENT offset
        while (parts < BlobRange.MAX_PARTS) {
            // A ranged read has ~45 parts per 120 KB through cover: on a lossy link one lost
            // part must cost one more request, not the whole chunk (netem loss10: without
            // per-part retries 0.9^45 ≈ 1 % of photos arrived). Progress is kept.
            val outcome: BlobRange.Result? = try {
                val headers = signer.sign("GET", path, signer.emptyJsonStringBody(), withUserHeader = true)
                val req = Request.Builder().url("${BuildConfig.VPS_URL}$path?offset=$offset&length=$part").get()
                    .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code in 400..499 && resp.code != 408 && resp.code != 429) {
                        Log.w(TAG, "downloadChunk[$index] part @$offset HTTP ${resp.code}"); return null   // gone / forbidden / 416
                    }
                    val body = resp.body?.bytes() ?: ByteArray(0)
                    when (val r = BlobRange.accept(resp.code, resp.header("Content-Range"), offset, body.size)) {
                        BlobRange.Result.Whole -> return body          // old server: the whole chunk
                        is BlobRange.Result.Part -> { out.write(body); r }
                        BlobRange.Result.Fail -> null
                    }
                }
            } catch (e: Exception) {
                if (e is java.io.IOException) CoverConnectionPolicy.onFailure(e)
                Log.w(TAG, "downloadChunk[$index] part @$offset failed: ${e.message}"); null
            }
            if (outcome is BlobRange.Result.Part) {
                parts++; attempts = 0; offset = outcome.nextOffset
                if (outcome.complete) return out.toByteArray()
                continue
            }
            if (++attempts >= BlobRange.PART_ATTEMPTS) return null
            Thread.sleep(BlobRange.PART_RETRY_MS * attempts)
        }
        Log.w(TAG, "downloadChunk[$index] too many parts"); return null
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
        return fill(reservation, resumed, chunks)
    }

    /**
     * __GROUP_BLOB_2026_11_25__ ONE reservation + ONE upload for a whole group: [members] go in
     * `recipientKeys`, `members[0]` also in `recipientKey` (what an older server reads).
     *
     * [resumeBlobId] = a SHARED reservation a previous attempt made for these exact bytes (the
     * caller's digest-bound ledger); only the chunks `/status` still lists are sent.
     * [onReserved] runs the instant a fresh reservation returns, with `shared`.
     */
    suspend fun uploadGroupBlob(
        members: List<String>,
        chunks: List<ByteArray>,
        resumeBlobId: String? = null,
        onReserved: (suspend (String, Boolean) -> Unit)? = null,
    ): V2GroupUpload? {
        if (chunks.isEmpty() || members.isEmpty()) return null
        val totalSize = chunks.sumOf { it.size }
        val resumed = resumeBlobId?.let { id ->
            val st = status(id)
            when {
                st == null -> null
                st.committed -> V2Reservation(id, OSHICryptoV2.DEFAULT_CHUNK_SIZE, emptyList(), members.size)
                st.missing.any { it < 0 || it >= chunks.size } -> null
                else -> V2Reservation(id, OSHICryptoV2.DEFAULT_CHUNK_SIZE, st.missing, members.size)
            }
        }
        val reservation = resumed
            ?: reserve(members[0], totalSize, chunks.size, recipientKeys = members)
                ?.also { onReserved?.invoke(it.blobId, isShared(it, members.size)) }
            ?: return null
        val blobId = fill(reservation, resumed != null, chunks) ?: return null
        return V2GroupUpload(blobId, resumed != null || isShared(reservation, members.size))
    }

    /**
     * PUT the reservation's missing chunks, top up from `/status`, commit. Shared by the 1:1 and
     * the group upload. Returns the blobId or null.
     */
    private suspend fun fill(reservation: V2Reservation, resumed: Boolean, chunks: List<ByteArray>): String? {
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
        /** __GROUP_BLOB_2026_11_25__ where fetched chunks wait for a resumed attempt; null = no resume. */
        partialRoot: java.io.File? = null,
    ): Long? = withContext(Dispatchers.IO) {
        if (chunkCount < 1) return@withContext null
        decryptResumable(partialRoot, blobId, chunkCount, fileKey, fileNonce, manifest, out) { i ->
            downloadChunkBlocking(blobId, i)
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

        /**
         * The body of [downloadAndDecryptToFile], network-free so it is unit-tested: chunks come
         * from [partialRoot] when an earlier attempt fetched them, else from [fetch] (and are kept
         * there until the file opened). [out] is deleted on any failure; the partial chunks are
         * kept only when the failure was a chunk that could not be fetched.
         */
        internal fun decryptResumable(
            partialRoot: java.io.File?,
            blobId: String,
            chunkCount: Int,
            fileKey: ByteArray,
            fileNonce: ByteArray,
            manifest: ByteArray,
            out: java.io.File,
            fetch: (Int) -> ByteArray?,
        ): Long? {
            val partial = partialRoot?.let { V2PartialChunks(it, blobId) }
            if (partial != null && partial.count() > 0) {
                Log.i(TAG, "resuming download of ${blobId.take(12)} with ${partial.count()}/$chunkCount chunk(s) on disk")
            }
            return try {
                out.parentFile?.mkdirs()
                var written = 0L
                java.io.BufferedOutputStream(java.io.FileOutputStream(out)).use { sink ->
                    written = OSHICryptoV2Streaming.decryptToStream(
                        fileKey = fileKey,
                        fileNonce = fileNonce,
                        manifest = manifest,
                        out = sink,
                        expectedChunkCount = chunkCount,
                    ) { i -> partial?.get(i) ?: fetch(i)?.also { partial?.put(i, it) } }
                }
                partial?.clear()
                written
            } catch (e: Throwable) {
                Log.w(TAG, "streaming download of ${blobId.take(12)} failed: ${e.message}")
                out.delete()
                // A chunk that was unavailable keeps what is on disk for the next attempt; anything
                // else (a chunk or manifest that does not open) must not be fed back in.
                if (e !is java.io.IOException) partial?.clear()
                null
            }
        }

        /** __GROUP_BLOB_2026_11_25__ the server stored our recipient list (it echoed `recipients`). */
        internal fun isShared(r: V2Reservation, @Suppress("UNUSED_PARAMETER") members: Int): Boolean =
            r.recipients != null && r.recipients > 0

        private fun org.json.JSONArray?.toIntList(): List<Int> {
            if (this == null) return emptyList()
            return (0 until length()).map { optInt(it) }
        }
    }
}

/**
 * __GROUP_BLOB_2026_11_25__ RESUMABLE DOWNLOAD. Ciphertext chunks already fetched for
 * [blobId] are kept under [root]/<blobId>/<index> until the whole file opened, so a
 * download cut by a network loss or an app kill restarts at the first chunk it does not
 * have instead of chunk 0 (the relay ack is held, so the key message comes back).
 * The bytes are the server's opaque AEAD ciphertext; a corrupt one fails to open and the
 * whole directory is dropped.
 */
internal class V2PartialChunks(root: java.io.File, blobId: String) {
    val dir = java.io.File(root, blobId.filter { it.isLetterOrDigit() || it == '-' }.take(80))
    fun get(i: Int): ByteArray? = java.io.File(dir, i.toString()).takeIf { it.isFile && it.length() > 0 }
        ?.let { runCatching { it.readBytes() }.getOrNull() }
    fun put(i: Int, b: ByteArray) {
        runCatching {
            dir.mkdirs()
            val tmp = java.io.File(dir, "$i.tmp")
            tmp.writeBytes(b)
            if (!tmp.renameTo(java.io.File(dir, i.toString()))) tmp.delete()
        }
    }
    fun count(): Int = dir.listFiles { f -> !f.name.endsWith(".tmp") }?.size ?: 0
    fun clear() { runCatching { dir.deleteRecursively() } }

    companion object {
        /** Partial downloads nobody came back for in 7 days are dropped. */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        fun prune(root: java.io.File, now: Long = System.currentTimeMillis()) {
            root.listFiles()?.forEach { if (now - it.lastModified() > MAX_AGE_MS) it.deleteRecursively() }
        }
    }
}
