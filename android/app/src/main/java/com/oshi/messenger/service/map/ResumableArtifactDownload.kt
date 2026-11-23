package com.oshi.messenger.service.map

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * __MAPS_COMPLETE_2026_10_13__ Range-resumed download of a large map artifact.
 *
 * iOS has had this since the three offline-asset singletons were written: it
 * persists `URLSessionDownloadTask` resume data per region in
 * `OfflineDownloadResume.swift` and continues "from the exact byte offset where
 * they stopped, not from 0%". Android had nothing of the kind. Its road-graph
 * fetch opened a plain GET and began with
 *
 *     if (staged.exists()) staged.delete()
 *
 * so every interrupted attempt threw away its own progress, and the `catch`
 * deleted the partial file too. There was nothing left to resume from even in
 * principle.
 *
 * That is survivable for a 78 MB streets index. It makes the large countries
 * practically unobtainable: France is a 10.1 GB road graph, India 11.8 GB. On a
 * phone connection a 10 GB transfer that restarts from zero on every drop does
 * not finish.
 *
 * The server side was never the problem -- measured against the live host on
 * 2026-10-13: `Accept-Ranges: bytes`, a stable strong `ETag`, and a real
 * `206 Partial Content` with a correct `Content-Range` for `bytes=100-199`.
 *
 * Correctness notes, in the order they bite:
 *
 *  - A staged file is only resumed when the sidecar says it came from the SAME
 *    url. The road-graph path deliberately reuses one `roadgraph.db.gz.part`
 *    for two different urls (the regional artifact, then the country-level
 *    fallback), so without this check a retry would append the country's bytes
 *    onto the region's and produce a file that is corrupt rather than short.
 *  - `If-Range` carries the ETag we started with. If the artifact was rebuilt
 *    server-side in between, HTTP requires the server to answer 200 with the
 *    whole body instead of 206, and we truncate and start over. Publishing a
 *    new country replaces the file, so this is a real case, not a theoretical
 *    one.
 *  - A transient failure KEEPS the partial file. That is the entire point, and
 *    it is the line the old code had backwards.
 *  - The caller still verifies sha256 over the finished artifact
 *    ([MapArtifactVerifier]) and the gzip CRC-32/ISIZE on decompression, so a
 *    bad resume is caught and discarded rather than installed.
 */
object ResumableArtifactDownload {

    private const val TAG = "ResumableDownload"

    sealed class Outcome {
        /** [staged] now holds the complete body. */
        object Complete : Outcome()

        /** The server does not have it (404/410). Not retryable. */
        object NotAvailable : Outcome()

        /**
         * Transient failure. [partialKept] is true when bytes were preserved
         * and the next call will resume from them.
         */
        data class Failed(val partialKept: Boolean) : Outcome()

        /**
         * __MAP_SPACE_GUARD_2026_10_21__ Refused before a single byte, because
         * it provably will not fit.
         */
        data class InsufficientSpace(val neededBytes: Long, val availableBytes: Long) : Outcome()
    }

    /**
     * __MAP_SPACE_GUARD_2026_10_21__ Free space a map install refuses to
     * consume, so it cannot be the thing that makes the device unusable.
     * Same value as iOS `GzipStreamDecompressor.diskHeadroom`.
     */
    private const val DISK_HEADROOM_BYTES = 300L * 1024 * 1024

    /**
     * Observed gzip expansion for the road-graph and streets SQLite artifacts.
     * Same value as iOS `GzipStreamDecompressor.roadGraphExpansionRatio`
     * (measured there against the live `lu-roadgraph.db.gz`: 109,914,707 B
     * compressed to 290,451,456 B).
     */
    const val GZIP_EXPANSION_RATIO = 2.65

    private fun sidecar(staged: File) = File(staged.parentFile, staged.name + ".src")

    private fun readSidecar(staged: File): Pair<String, String?>? {
        val f = sidecar(staged)
        if (!f.exists()) return null
        return try {
            val lines = f.readLines()
            if (lines.isEmpty()) null else lines[0] to lines.getOrNull(1)?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeSidecar(staged: File, url: String, etag: String?) {
        try {
            sidecar(staged).writeText(url + "\n" + (etag ?: ""))
        } catch (e: Exception) {
            // Losing the sidecar only costs us the resume, never correctness:
            // the next attempt cannot prove the partial belongs to this url and
            // will restart from zero.
            Log.w(TAG, "could not write resume sidecar: ${e.message}")
        }
    }

    fun clear(staged: File) {
        staged.delete()
        sidecar(staged).delete()
    }

    /**
     * Download [url] into [staged], continuing from whatever is already there
     * when that is provably the same artifact.
     */
    fun fetch(
        client: OkHttpClient,
        url: String,
        staged: File,
        label: String,
        extraHeaders: Map<String, String> = emptyMap(),
        /**
         * __MAP_SPACE_GUARD_2026_10_21__ The artifact's compressed size from
         * the manifest, or 0 to skip the pre-flight space check. See the guard
         * below for why this parameter exists at all.
         */
        expectedBytes: Long = 0L,
        /**
         * How much the artifact grows when unpacked: [GZIP_EXPANSION_RATIO] for
         * a `.gz` that gets gunzipped, 0.0 for a file used as downloaded (POI
         * is a plain `.db`). The caller knows which it is; this object does not.
         */
        expansionRatio: Double = 0.0,
        onProgress: ((Float) -> Unit)? = null,
    ): Outcome {
        staged.parentFile?.mkdirs()

        var have = if (staged.exists()) staged.length() else 0L
        var resumeEtag: String? = null

        // ── Pre-flight space guard ──────────────────────────────────────────
        // __MAP_SPACE_GUARD_2026_10_21__ Android had NO free-space check on any
        // map download. The only `usableSpace` call in the whole of app/src/main
        // was the local LLM's (`LocalLLMManager.kt`), so tapping a country-scale
        // region wrote until the device filled up. iOS has refused this since
        // 2026-07-28 (`OfflineRoadGraphDownloader`), and the artifact that makes
        // it matter now exists: `us-roadgraph.db.gz` is 19.6 GiB compressed and
        // 45.3 GiB unpacked — ~65 GiB of free space to install one region.
        //
        // Worse than merely filling the disk: this function deliberately KEEPS
        // the partial on failure so the next attempt resumes. That is right for
        // a 200 MB region and ruinous for a 20 GB one — the device stays full,
        // and every retry fails the same way with nothing reclaimed.
        //
        // Refusing costs the user nothing; discovering it at 90% costs them the
        // bandwidth AND the free space.
        if (expectedBytes > 0L) {
            val remainingDownload = (expectedBytes - have).coerceAtLeast(0L)
            val afterUnpack = (expectedBytes.toDouble() * expansionRatio).toLong()
            val needed = remainingDownload + afterUnpack + DISK_HEADROOM_BYTES
            // `usableSpace` returns 0 when it cannot be determined; treat that
            // as "no opinion" rather than "no space", matching iOS's
            // `free > 0 && free < needed`.
            val available = (staged.parentFile ?: staged).usableSpace
            if (available in 1 until needed) {
                Log.i(
                    TAG,
                    "$label: refusing — needs ~$needed B " +
                        "(${remainingDownload} to download + $afterUnpack unpacked + headroom), " +
                        "$available B free"
                )
                return Outcome.InsufficientSpace(neededBytes = needed, availableBytes = available)
            }
        }

        if (have > 0L) {
            val prior = readSidecar(staged)
            if (prior == null || prior.first != url) {
                // Either we cannot prove the partial's origin, or it belongs to
                // a different url (the regional-vs-country fallback). Appending
                // would corrupt it.
                Log.i(TAG, "$label: discarding $have B partial — not provably from $url")
                clear(staged)
                have = 0L
            } else {
                resumeEtag = prior.second
                Log.i(TAG, "$label: resuming from $have B")
            }
        }

        val builder = Request.Builder().url(url)
        // The streets index is fetched with an explicit User-Agent; whatever the
        // caller sends on its probe must be sent on the download too, or the two
        // requests are not talking to the same server policy.
        for ((k, v) in extraHeaders) builder.header(k, v)
        if (have > 0L) {
            builder.header("Range", "bytes=$have-")
            // Without this, a server-side rebuild between attempts would be
            // stitched into the middle of the old download.
            resumeEtag?.let { builder.header("If-Range", it) }
        }

        return try {
            client.newCall(builder.build()).execute().use { resp ->
                when (resp.code) {
                    404, 410 -> {
                        Log.d(TAG, "$label: HTTP ${resp.code} for $url")
                        return Outcome.NotAvailable
                    }
                    416 -> {
                        // We already hold at least as many bytes as the server
                        // has. The partial is stale or over-long; start clean.
                        Log.w(TAG, "$label: 416 for bytes=$have- — restarting from 0")
                        clear(staged)
                        return Outcome.Failed(partialKept = false)
                    }
                }
                if (!resp.isSuccessful) {
                    Log.d(TAG, "$label: HTTP ${resp.code} for $url")
                    return Outcome.Failed(partialKept = have > 0L)
                }

                val body = resp.body ?: return Outcome.Failed(partialKept = have > 0L)

                // 206 continues the file; 200 means the server ignored the Range
                // (or If-Range failed) and is sending the whole body, so the
                // bytes on disk are not a prefix of it.
                val append = resp.code == 206
                if (!append && have > 0L) {
                    Log.i(TAG, "$label: server answered 200 to a ranged request — restarting")
                }

                writeSidecar(staged, url, resp.header("ETag"))

                val alreadyHave = if (append) have else 0L
                // On a 206 the body length is what REMAINS, so the artifact's
                // true size is the offset plus that. Reporting
                // bytesRead/contentLength instead would show a download
                // resuming at 90% as starting from 0% and climbing to 100% of
                // the last 10%, which reads as a restart to the user.
                val total = body.contentLength().let {
                    if (it > 0L) alreadyHave + it else -1L
                }

                FileOutputStream(staged, append).use { out ->
                    body.byteStream().use { input ->
                        if (onProgress == null) {
                            input.copyTo(out, 64 * 1024)
                        } else {
                            val buf = ByteArray(64 * 1024)
                            var written = alreadyHave
                            var lastReported = -1
                            while (true) {
                                val read = input.read(buf)
                                if (read == -1) break
                                out.write(buf, 0, read)
                                written += read
                                if (total > 0L) {
                                    // Only on a whole-percent change: a 10 GB
                                    // artifact is ~160k buffers, and emitting
                                    // each one floods the UI.
                                    val pct = (written * 100 / total).toInt()
                                    if (pct != lastReported) {
                                        lastReported = pct
                                        onProgress(written.toFloat() / total.toFloat())
                                    }
                                }
                            }
                        }
                    }
                }
                Outcome.Complete
            }
        } catch (e: IOException) {
            // The partial stays. This is the behaviour the old code inverted.
            val kept = staged.exists() && staged.length() > 0L
            Log.w(TAG, "$label: $url interrupted at ${staged.length()} B (kept=$kept): ${e.message}")
            Outcome.Failed(partialKept = kept)
        } catch (e: Exception) {
            val kept = staged.exists() && staged.length() > 0L
            Log.w(TAG, "$label: $url failed (kept=$kept): ${e.message}")
            Outcome.Failed(partialKept = kept)
        }
    }
}
