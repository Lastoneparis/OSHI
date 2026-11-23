package com.oshi.messenger.data.local.atrest

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The ONE place decrypted attachment bytes may touch disk: `cacheDir/oshi_plain/`.
 *
 * ## Why decrypted temp files rather than a streaming DataSource
 *
 * The consumers that need a path or a URI are not one API:
 *  * ExoPlayer (`Uri.fromFile`) — could take a custom `DataSource`;
 *  * `PdfRenderer` — needs a seekable `ParcelFileDescriptor`, i.e. a real file;
 *  * sharing / "open with" via `FileProvider` — the OTHER app reads the URI, possibly
 *    after we return, possibly after our process is gone; a DataSource cannot serve it.
 * A single mechanism that works for all three is a short-lived plaintext copy in a
 * private cache directory that is (a) deleted by the caller as soon as it is done,
 * (b) swept wholesale at the next process start, and (c) never inside `files/`, so
 * it is not mistaken for, or migrated as, a stored attachment. The window during
 * which plaintext exists is the viewing session — the same window during which the
 * bytes are decrypted in RAM anyway.
 *
 * Also sweeps the fixed-name plaintext copies the UI wrote straight into `cacheDir`
 * before this existed (`share_media.*`, `media_viewer_*.{mp4,pdf}`, `playback_<hex>.mp4`,
 * `OSHI_<ms>.jpg`) and stale V2 download scratch files (`v2blobs/blob_*.bin`).
 */
object PlaintextScratch {
    const val DIR_NAME = "oshi_plain"

    /** Files a share target may still be reading are kept this long by [sweepLegacy]. */
    const val LEGACY_MIN_AGE_MS = 10 * 60 * 1000L

    /** V2 download scratch is deleted in a `finally`; anything this old is a crash leftover. */
    const val V2_SCRATCH_MIN_AGE_MS = 60 * 60 * 1000L

    private val LEGACY_NAMES = listOf(
        Regex("""^share_media\.(jpg|mp4|bin)$"""),
        Regex("""^media_viewer_video\.mp4$"""),
        Regex("""^media_viewer_doc\.pdf$"""),
        Regex("""^playback_[0-9a-f]{32}\.mp4$"""),
        Regex("""^OSHI_\d+\.jpg$"""),
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ Crash leftovers of writers that delete in a
        // `finally` (transcode, thumbnail, voice-effect codec, mail PDF preview) and the
        // camera capture targets, which the result callbacks now delete once read.
        Regex("""^oshi_(photo|video)_-?\d+\.(jpg|mp4)$"""),
        Regex("""^(temp_video|input|output)_[0-9a-f-]{36}\.mp4$"""),
        Regex("""^temp_(decode|encode)_[0-9a-f-]{36}\.m4a$"""),
        Regex("""^temp_duration\.m4a$"""),
        Regex("""^mail-drive-preview-?\d+\.pdf$"""),
    )

    /** Capture targets handed to the camera app via FileProvider (see [deleteCapture]). */
    private val CAPTURE_NAME = Regex("""^oshi_(photo|video)_-?\d+\.(jpg|mp4)$""")

    /**
     * Pre-2026-09-24 voice-note dirs: the recorder kept every SENT note in
     * `voice_messages/`, the player kept up to 20 decrypted notes in `voice_playback/`
     * (and never cleared them across restarts). The recorder now deletes its file once
     * read, and the player plays from memory, so both dirs only hold leftovers.
     */
    private val LEGACY_DIRS = listOf("voice_messages", "voice_playback")

    /**
     * Decrypted documents / media handed to ANOTHER app (open-with, share). The file
     * must outlive our call — the receiver reads it whenever it likes — so it is purged
     * at the next share and at the next launch, like `call_diagnostics/`.
     */
    const val SHARE_DIR_NAME = "oshi_share"

    fun dir(cacheDir: File): File = File(cacheDir, DIR_NAME)

    /** A fresh, uniquely named empty file in the scratch dir. Caller deletes it. */
    fun newFile(cacheDir: File, suffix: String = ".bin"): File {
        val d = dir(cacheDir)
        if (!d.isDirectory && !d.mkdirs()) throw IOException("cannot create scratch dir")
        val safeSuffix = suffix.replace(Regex("[^A-Za-z0-9._-]"), "_").take(16)
        return File.createTempFile("plain_", safeSuffix, d)
    }

    /** Copy [input] (closed afterwards) into a new scratch file. Deletes it on failure. */
    fun write(cacheDir: File, input: InputStream, suffix: String = ".bin"): File {
        input.use { src ->
            val f = newFile(cacheDir, suffix)
            try {
                f.outputStream().use { src.copyTo(it, 64 * 1024) }
                return f
            } catch (e: Throwable) {
                // A tampered chunk surfaces here mid-copy: drop the partial plaintext.
                f.delete()
                throw e
            }
        }
    }

    fun write(cacheDir: File, bytes: ByteArray, suffix: String = ".bin"): File =
        write(cacheDir, bytes.inputStream(), suffix)

    /**
     * Startup sweep. Everything in the scratch dir goes (nothing in a new process can
     * be using it); legacy fixed names older than [LEGACY_MIN_AGE_MS] go; V2 download
     * scratch older than [V2_SCRATCH_MIN_AGE_MS] goes. Returns the count removed.
     */
    fun sweepAtStartup(cacheDir: File, nowMs: Long = System.currentTimeMillis()): Int {
        var removed = 0
        dir(cacheDir).listFiles()?.forEach { if (it.isFile && it.delete()) removed++ }
        removed += sweepLegacy(cacheDir, nowMs)
        File(cacheDir, "v2blobs").listFiles()?.forEach {
            if (it.isFile && nowMs - it.lastModified() > V2_SCRATCH_MIN_AGE_MS && it.delete()) removed++
        }
        // A share target may still be reading its file right after a restart.
        (LEGACY_DIRS + SHARE_DIR_NAME).forEach { name ->
            File(cacheDir, name).listFiles()?.forEach {
                if (it.isFile && nowMs - it.lastModified() > LEGACY_MIN_AGE_MS && it.delete()) removed++
            }
        }
        return removed
    }

    /**
     * A fresh file for an outbound share / open-with, named [displayName] (sanitized)
     * because the receiving app shows it. Everything shared before is deleted first:
     * one decrypted export on disk at a time.
     */
    fun newShareFile(cacheDir: File, displayName: String): File {
        val d = File(cacheDir, SHARE_DIR_NAME)
        d.listFiles()?.forEach { it.delete() }
        if (!d.isDirectory && !d.mkdirs()) throw IOException("cannot create share dir")
        val safe = displayName.ifEmpty { "file" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        return File(d, safe)
    }

    /**
     * Delete a camera capture target once its bytes have been read into memory (the
     * send path works on the in-memory copy). [fileName] is the FileProvider URI's last
     * segment; anything that is not one of our capture names is ignored, so a crafted
     * URI can never make this delete something else.
     */
    fun deleteCapture(cacheDir: File, fileName: String?): Boolean {
        if (fileName == null || !CAPTURE_NAME.matches(fileName)) return false
        return File(cacheDir, fileName).delete()
    }

    fun isLegacyScratchName(name: String): Boolean = LEGACY_NAMES.any { it.matches(name) }

    fun sweepLegacy(cacheDir: File, nowMs: Long = System.currentTimeMillis()): Int {
        var removed = 0
        cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && isLegacyScratchName(f.name) &&
                nowMs - f.lastModified() > LEGACY_MIN_AGE_MS && f.delete()
            ) removed++
        }
        return removed
    }
}
