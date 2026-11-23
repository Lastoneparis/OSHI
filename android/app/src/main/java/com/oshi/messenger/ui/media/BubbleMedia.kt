package com.oshi.messenger.ui.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import coil.ImageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File
import java.nio.ByteBuffer

/**
 * The shared, bounded decoder for message-bubble media — audit **M-7** and **M-8**.
 *
 * ## What was wrong
 *
 * Every image bubble ran `BitmapFactory.decodeByteArray(data, 0, data.size)` with **no
 * `inSampleSize`**, into `remember(data)`. Three defects in one expression:
 *
 *  * **no downsampling** — a 1920×1440 JPEG becomes ~11 MB of `ARGB_8888` to fill a
 *    bubble capped at 250 dp, and there is no `largeHeap` in the manifest;
 *  * **no shared cache** — the bitmap lives in the composition, so leaving and
 *    re-entering the conversation decodes everything again;
 *  * **`remember(data)` keys on a `ByteArray`**, whose `equals` is IDENTITY. Room hands
 *    out a fresh array on every Flow emission for inline (≤1.5 MB) media, so an incoming
 *    message, a delivery receipt or a read flag re-decoded every visible photo.
 *
 * iOS solved exactly this and named the bug in the header of
 * `MediaAttachmentCache.swift:70-77` — "an 800 pt JPEG was re-decoded on the main thread
 * on every single body evaluation" — with `decodedImage(from:id:maxDimension:)` keyed
 * `"<messageId>@<maxDimension>"` (`:81-91`) over an `NSCache` bounded at 80 items /
 * 48 MB (`:143-144`).
 *
 * ## What this is
 *
 * Coil, which the media gallery already uses, with the same key shape and the same
 * budget — but as a **private** `ImageLoader`, not the process default, for two reasons:
 *
 *  1. **the disk cache is OFF.** Coil's default loader persists every decoded source to
 *     `cacheDir/image_cache`. For an end-to-end encrypted messenger that means writing
 *     plaintext photos to unencrypted app storage, where nothing in this app's own media
 *     lifecycle (view-once burn, `MediaAttachmentStore.purgeOrphans`, account delete)
 *     would ever find them. Memory only.
 *  2. configuring the default loader needs an `ImageLoaderFactory` on the Application
 *     class, which is another team's file this pass.
 */
object BubbleMedia {

    /** iOS `MediaAttachmentCache.swift:144` — `totalCostLimit = 48 MB`. */
    const val MEMORY_CACHE_BYTES: Int = 48 * 1024 * 1024

    /**
     * iOS's cache key, verbatim in shape: `"<messageId>@<maxDimension>"`
     * (`MediaAttachmentCache.swift:82`). Keying on the **message id** is the whole point —
     * a byte-array key is an identity key and never hits twice.
     */
    fun cacheKey(messageId: String, maxDimensionPx: Int): String = "$messageId@$maxDimensionPx"

    /**
     * A cache key for bytes that arrive WITHOUT a message id — the standalone media
     * viewer and the gallery are navigated to with a raw `ByteArray`.
     *
     * Cheap on purpose: length plus the first and last 16 bytes. It is a CACHE key, not a
     * digest — the cost of a collision is one wrong thumbnail, and hashing tens of
     * megabytes on the composition thread to avoid that would be the very jank this file
     * exists to remove. Prefer [cacheKey] with a real message id wherever one exists.
     */
    fun contentKey(data: ByteArray): String {
        val sb = StringBuilder("len").append(data.size).append(':')
        val head = minOf(16, data.size)
        for (i in 0 until head) sb.append("%02x".format(data[i]))
        sb.append(':')
        for (i in maxOf(0, data.size - 16) until data.size) sb.append("%02x".format(data[i]))
        return sb.toString()
    }

    @Volatile
    private var loader: ImageLoader? = null

    fun imageLoader(context: Context): ImageLoader {
        loader?.let { return it }
        return synchronized(this) {
            loader ?: ImageLoader.Builder(context.applicationContext)
                // __LOCAL_GIF_LIBRARY_2026_09_13__ Sans décodeur GIF, Coil rend la
                // PREMIÈRE IMAGE et rien d'autre — une bulle de GIF muette et un
                // sélecteur de 2736 vignettes figées. `ImageDecoderDecoder`
                // (`android.graphics.ImageDecoder`) à partir de l'API 28, `GifDecoder`
                // (giflib) en dessous: le premier n'existe pas sur API 26-27, qui sont
                // dans la cible puisque `minSdk = 26`.
                .components {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        add(coil.decode.ImageDecoderDecoder.Factory())
                    } else {
                        add(coil.decode.GifDecoder.Factory())
                    }
                }
                .memoryCache {
                    MemoryCache.Builder(context.applicationContext)
                        .maxSizeBytes(MEMORY_CACHE_BYTES)
                        .build()
                }
                // See the class header: never persist decrypted media to disk.
                .diskCachePolicy(CachePolicy.DISABLED)
                .respectCacheHeaders(false)
                .build()
                .also { loader = it }
        }
    }

    /**
     * A request for one bubble.
     *
     * `data` is wrapped in a [ByteBuffer] deliberately: Coil 2's default component
     * registry has a `ByteBufferFetcher` and **no `ByteArray` fetcher**, so a raw
     * `ByteArray` model resolves to nothing at all.
     *
     * The memory-cache key is set EXPLICITLY. Coil would otherwise derive it from the
     * model, and `HeapByteBuffer.toString()` is `"java.nio.HeapByteBuffer[pos=0 lim=N
     * cap=N]"` — identical for any two different photos of the same byte length, which
     * would serve one conversation's photo in another's bubble.
     */
    fun request(
        context: Context,
        messageId: String,
        data: ByteArray,
        maxDimensionPx: Int
    ): ImageRequest {
        val key = MemoryCache.Key(cacheKey(messageId, maxDimensionPx))
        return ImageRequest.Builder(context)
            .data(ByteBuffer.wrap(data))
            .size(maxDimensionPx)
            .memoryCacheKey(key)
            .placeholderMemoryCacheKey(key)
            .diskCachePolicy(CachePolicy.DISABLED)
            .allowHardware(true)
            .crossfade(false)
            .build()
    }

    /**
     * Drop every decoded size of one message. Called when the bytes behind an id are
     * destroyed or replaced (view-once burn, a reveal layer landing) — iOS does the same
     * three-step and calls out that missing the cache eviction leaves the old layer on
     * screen (`MessageManager+V2.swift:883-889`).
     */
    fun evict(context: Context, messageId: String, dimensions: IntArray) {
        val cache = imageLoader(context).memoryCache ?: return
        for (d in dimensions) cache.remove(MemoryCache.Key(cacheKey(messageId, d)))
    }
}

/**
 * Video-bubble thumbnails — audit **M-8**.
 *
 * ## What was wrong
 *
 * ```kotlin
 * val thumbnail = remember(videoData) {                       // ← runs IN COMPOSITION
 *     val tempFile = File(context.cacheDir, "thumb_${data.hashCode()}.mp4")
 *     if (!tempFile.exists()) FileOutputStream(tempFile).use { it.write(data) }
 *     MediaMetadataRetriever().apply { setDataSource(tempFile.absolutePath) }…
 * }
 * ```
 *
 *  * `remember { }` executes on the composition (main) thread, so a multi-megabyte
 *    `FileOutputStream.write` plus a full container parse blocked the frame — visible
 *    jank, an ANR on a large clip;
 *  * `data.hashCode()` on a `ByteArray` is the IDENTITY hash, so every new array instance
 *    (every Room emission, every process restart) minted a NEW file name,
 *    `tempFile.exists()` never hit, and another full copy of the video was written.
 *    Nothing ever deleted them — `MediaAttachmentStore.sweepTempFiles` only handles
 *    `.tmp` inside its own directory.
 *
 * ## What this is
 *
 * No temp file at all: the retriever reads the bytes from RAM through
 * [ByteArrayMediaDataSource], off the main thread, and the frame is cached by **message
 * id**. [sweepLegacyTempFiles] deletes the copies the old code already leaked.
 *
 * iOS generates video thumbnails once at attach time and stores them on the attachment
 * (`MediaManager.swift:231,437,456-458`), never on the render path.
 */
object VideoThumbnails {

    /** The 1 s mark, matching what the old in-composition code asked for. */
    private const val FRAME_AT_MICROS = 1_000_000L

    /** Thumbnails are small; 32 covers any reachable screenful of video bubbles. */
    const val MAX_ENTRIES = 32

    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    private fun cached(messageId: String): Bitmap? = cache[messageId]

    @Synchronized
    private fun store(messageId: String, bitmap: Bitmap) {
        cache[messageId] = bitmap
    }

    @Synchronized
    fun evict(messageId: String) {
        cache.remove(messageId)
    }

    @Synchronized
    fun cacheSize(): Int = cache.size

    /** Blocking — call from `Dispatchers.IO`. */
    fun thumbnail(messageId: String, data: ByteArray): Bitmap? {
        cached(messageId)?.let { return it }
        if (data.isEmpty()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ByteArrayMediaDataSource(data))
            val frame = retriever.getFrameAtTime(
                FRAME_AT_MICROS,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.frameAtTime
            frame?.also { store(messageId, it) }
        } catch (_: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) { }
        }
    }

    /**
     * The two names the identity-hash code produced: `thumb_<int>.mp4` (poster frames)
     * and `playback_<int>.mp4` (the ExoPlayer scratch copy), where `<int>` is
     * `ByteArray.hashCode()` — a fresh value per array instance, so one full copy of the
     * video per Room emission and per process restart, and nothing ever deleted them.
     *
     * ⚠️ The pattern is deliberately narrow: `-?\d+`. The REPLACEMENT playback file is
     * `playback_<32 hex>.mp4`, and a sweep that matched on the prefix alone would delete
     * the file the player is about to open.
     */
    private val LEGACY_TEMP = Regex("""^(thumb|playback)_-?\d+\.mp4$""")

    fun isLegacyTempFile(name: String): Boolean = LEGACY_TEMP.matches(name)

    /**
     * The stable name for the ExoPlayer scratch copy: keyed on the message id, hashed so
     * an id carrying a path separator or a non-ASCII byte cannot escape `cacheDir`.
     */
    fun playbackTempName(messageId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(messageId.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(32)
        for (i in 0 until 16) hex.append("%02x".format(digest[i]))
        return "playback_$hex.mp4"
    }

    /**
     * Delete the leaked `thumb_<int>.mp4` / `playback_<int>.mp4` copies. On an old install
     * this is typically the largest thing in `cacheDir`. Returns how many were removed.
     */
    fun sweepLegacyTempFiles(cacheDir: File): Int {
        val files = cacheDir.listFiles() ?: return 0
        var removed = 0
        for (f in files) {
            if (f.isFile && isLegacyTempFile(f.name)) {
                if (f.delete()) removed++
            }
        }
        return removed
    }
}
