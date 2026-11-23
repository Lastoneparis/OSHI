package com.oshi.messenger.ui.components.attach

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import com.oshi.messenger.service.MediaCompressionManager
import com.oshi.messenger.ui.components.PendingMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * __ATTACH_PANEL_2027_2026_11_16__ The device gallery for the attachment panel.
 *
 * Read in PAGES ([AttachmentPanelLogic.PAGE_SIZE]) from MediaStore's files table,
 * newest first, photos and videos together — no full scan of a 40 000-item library to
 * draw the first fifteen squares. Thumbnails come from the platform thumbnail cache
 * (`ContentResolver.loadThumbnail`, API 29+; the legacy `Thumbnails` tables below),
 * decoded off the main thread and kept in a small memory LRU. Nothing is written to
 * disk: this is an end-to-end encrypted messenger, and the panel must not leave a
 * plaintext copy of the user's gallery in its cache directory.
 */
object RecentMedia {

    private const val TAG = "RecentMedia"

    fun currentAccess(context: Context): GalleryAccess {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return AttachmentPanelLogic.access(
            sdk = Build.VERSION.SDK_INT,
            readImages = Build.VERSION.SDK_INT >= 33 && granted("android.permission.READ_MEDIA_IMAGES"),
            readVideo = Build.VERSION.SDK_INT >= 33 && granted("android.permission.READ_MEDIA_VIDEO"),
            userSelected = Build.VERSION.SDK_INT >= 34 && granted("android.permission.READ_MEDIA_VISUAL_USER_SELECTED"),
            legacyRead = Build.VERSION.SDK_INT < 33 && granted("android.permission.READ_EXTERNAL_STORAGE"),
        )
    }

    /** One page, newest first. Empty on any failure (a revoked grant mid-scroll included). */
    suspend fun page(context: Context, offset: Int, limit: Int = AttachmentPanelLogic.PAGE_SIZE): List<RecentMediaItem> =
        withContext(Dispatchers.IO) {
            runCatching { query(context.contentResolver, offset, limit) }
                .onFailure { Log.w(TAG, "gallery page $offset failed: ${it.javaClass.simpleName}") }
                .getOrDefault(emptyList())
        }

    private fun query(cr: ContentResolver, offset: Int, limit: Int): List<RecentMediaItem> {
        val files = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            "duration",
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val qa = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns._ID))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            }
            cr.query(files, projection, qa, null)
        } else {
            cr.query(files, projection, selection, args,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC, ${MediaStore.Files.FileColumns._ID} DESC LIMIT $limit OFFSET $offset")
        }
        val out = ArrayList<RecentMediaItem>(limit)
        cursor?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val iDur = c.getColumnIndex("duration")
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val isVideo = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += RecentMediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id).toString(),
                    isVideo = isVideo,
                    mimeType = c.getString(iMime),
                    durationMs = if (isVideo && iDur >= 0 && !c.isNull(iDur)) c.getLong(iDur) else 0L,
                    dateAddedSec = c.getLong(iDate),
                )
            }
        }
        return out
    }

    // ── Thumbnails ────────────────────────────────────────────────────────

    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cachedThumb(item: RecentMediaItem, px: Int): Bitmap? = thumbs.get("${item.id}@$px")

    suspend fun thumbnail(context: Context, item: RecentMediaItem, px: Int): Bitmap? {
        val key = "${item.id}@$px"
        thumbs.get(key)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                val cr = context.contentResolver
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cr.loadThumbnail(Uri.parse(item.uri), Size(px, px), null)
                } else if (item.isVideo) {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(cr, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Thumbnails.getThumbnail(cr, item.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                }
            }.getOrNull()
        }
        if (bmp != null) thumbs.put(key, bmp)
        return bmp
    }

    // ── Bytes to send ─────────────────────────────────────────────────────

    /**
     * URIs from the system photo picker (photos AND videos in one pick) as panel items,
     * so the one [load] below decides photo / video / GIF for both doors in. The kind is
     * the provider's MIME, never the extension.
     */
    fun fromPickerUris(context: Context, uris: List<Uri>): List<RecentMediaItem> = uris.mapIndexed { i, u ->
        val mime = runCatching { context.contentResolver.getType(u) }.getOrNull()
        RecentMediaItem(
            id = -1L - i,
            uri = u.toString(),
            isVideo = mime?.startsWith("video/") == true,
            mimeType = mime,
            durationMs = 0L,
            dateAddedSec = 0L,
        )
    }

    /** What came of loading a pick: the bytes that loaded, and whether any did not. */
    data class Loaded(val media: List<PendingMedia>, val tooLarge: Boolean, val failed: Boolean)

    /**
     * Read the picked items into memory for the send path, in the pick's order.
     *
     * A photo is downsampled to 1920 px and re-encoded as the gallery picker always did
     * — EXCEPT a GIF, which goes untouched (re-encoding keeps only its first frame).
     * A video is refused above [MediaCompressionManager.IPFS_MAX_SIZE] BEFORE it is read,
     * the same ceiling every other media path in the app uses, and an
     * [OutOfMemoryError] is caught (it is an Error, not an Exception).
     */
    suspend fun load(context: Context, items: List<RecentMediaItem>, viewOnce: Boolean): Loaded =
        withContext(Dispatchers.IO) {
            val out = ArrayList<PendingMedia>(items.size)
            var tooLarge = false
            var failed = false
            for (item in items) {
                val uri = Uri.parse(item.uri)
                try {
                    val size = runCatching {
                        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
                            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
                    }.getOrDefault(-1L)
                    if (size > MediaCompressionManager.IPFS_MAX_SIZE) { tooLarge = true; continue }
                    val bytes = when {
                        item.isVideo || item.isGif ->
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        else -> decodeSampledJpeg(context, uri)
                    }
                    when {
                        bytes == null || bytes.isEmpty() -> failed = true
                        bytes.size > MediaCompressionManager.IPFS_MAX_SIZE -> tooLarge = true
                        else -> out += PendingMedia(bytes = bytes, isVideo = item.isVideo, viewOnce = viewOnce)
                    }
                } catch (e: OutOfMemoryError) {
                    tooLarge = true
                } catch (e: Exception) {
                    failed = true
                }
            }
            Loaded(out, tooLarge, failed)
        }

    /** The chat screens' photo rule: ≤1920 px as-is, larger downsampled to a JPEG 85. */
    private fun decodeSampledJpeg(context: Context, uri: Uri): ByteArray? {
        val maxDim = 1920
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
        if (bounds.outWidth <= maxDim && bounds.outHeight <= maxDim) {
            return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
        val sampled = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val longest = maxOf(sampled.width, sampled.height)
        val bmp = if (longest > maxDim) {
            val k = maxDim.toFloat() / longest
            Bitmap.createScaledBitmap(sampled, (sampled.width * k).toInt().coerceAtLeast(1),
                (sampled.height * k).toInt().coerceAtLeast(1), true).also { if (it !== sampled) sampled.recycle() }
        } else sampled
        return java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bmp.recycle()
            out.toByteArray()
        }
    }
}
