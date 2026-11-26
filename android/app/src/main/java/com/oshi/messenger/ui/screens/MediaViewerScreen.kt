package com.oshi.messenger.ui.screens

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.oshi.messenger.R
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    mediaData: ByteArray,
    mediaType: String, // "IMAGE", "VIDEO", "DOCUMENT"
    caption: String = "",
    onBack: () -> Unit,
    /** 🛡 __ADV_CHAT_PRIVACY_2026_11_26__ false hides "Save to gallery" (protected chat). */
    allowSave: Boolean = true
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(caption.ifEmpty { stringResource(R.string.media_viewer_title) }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.accessibility_back))
                    }
                },
                actions = {
                    IconButton(onClick = { shareMedia(context, mediaData, mediaType, caption) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.accessibility_share))
                    }
                    if (allowSave) IconButton(onClick = { saveToGallery(context, mediaData, mediaType) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_notification),
                            contentDescription = stringResource(R.string.save_to_gallery)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            when (mediaType) {
                "IMAGE" -> {
                    // AUDIT M-7. This was a SYNCHRONOUS, full-resolution
                    // `BitmapFactory.decodeByteArray` inside `remember { }`, i.e. on the
                    // composition thread — the viewer opened on a frozen frame. Now
                    // downsampled to 1920 px (iOS `MediaManager.swift:378`) off Main,
                    // through the shared memory-only cache.
                    //
                    // The viewer is navigated to with raw bytes and has no message id, so
                    // the key is derived from the content — see `BubbleMedia.contentKey`.
                    run {
                        com.oshi.messenger.ui.media.BubbleAsyncImage(
                            messageId = com.oshi.messenger.ui.media.BubbleMedia.contentKey(mediaData),
                            data = mediaData,
                            contentDescription = caption,
                            maxDimensionPx = com.oshi.messenger.ui.media.FULLSCREEN_MAX_DIMENSION_PX,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                )
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(0.5f, 5f)
                                        offset = Offset(
                                            offset.x + pan.x,
                                            offset.y + pan.y
                                        )
                                    }
                                }
                        )
                    }
                }
                "VIDEO" -> {
                    VideoPlayer(mediaData = mediaData)
                }
                else -> {
                    DocumentViewer(
                        mediaData = mediaData,
                        caption = caption,
                        onShare = { shareMedia(context, mediaData, "DOCUMENT", caption) }
                    )
                }
            }
        }
    }
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPlayer(mediaData: ByteArray) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Write bytes to a stable temp file for ExoPlayer to read
    // Deleted on dispose below; a unique name under oshi_plain/ so the startup sweep
    // removes it after a crash (at-rest, 2026-09-24).
    val videoFile = remember(mediaData) {
        com.oshi.messenger.data.local.atrest.PlaintextScratch.write(context.cacheDir, mediaData, ".mp4")
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(videoFile)))
            prepare()
            playWhenReady = true
        }
    }

    // Pause on lifecycle STOP, release on dispose
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
            try { videoFile.delete() } catch (_: Exception) {}
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun DocumentViewer(
    mediaData: ByteArray,
    caption: String,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    val isPdf = remember(mediaData, caption) { isPdfData(mediaData, caption) }

    if (isPdf) {
        PdfViewer(mediaData = mediaData)
    } else {
        NonPdfDocumentView(mediaData = mediaData, caption = caption, onShare = onShare)
    }
}

@Composable
private fun PdfViewer(mediaData: ByteArray) {
    val context = LocalContext.current
    val pages = remember(mediaData) { renderPdfPages(context, mediaData) }

    if (pages.isEmpty()) {
        Text(
            stringResource(R.string.media_pdf_render_failed),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(8.dp)
    ) {
        items(pages) { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.accessibility_pdf_page),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun NonPdfDocumentView(
    mediaData: ByteArray,
    caption: String,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    var openError by remember { mutableStateOf<String?>(null) }

    // Try to resolve the mime from filename extension
    val mimeType = remember(caption) { guessMimeType(caption) }

    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = caption.ifEmpty { "Document" },
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = openError ?: "Tap to open with another app",
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val result = openDocumentWithExternalApp(context, mediaData, caption, mimeType)
                openError = if (result) null else context.getString(R.string.media_share_failed)
            }) {
                Icon(Icons.Default.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.media_open))
            }
            OutlinedButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.media_share_button))
            }
        }
    }
}

private fun isPdfData(data: ByteArray, filename: String): Boolean {
    if (filename.lowercase().endsWith(".pdf")) return true
    // Magic header: %PDF
    if (data.size < 4) return false
    return data[0] == 0x25.toByte() &&
        data[1] == 0x50.toByte() &&
        data[2] == 0x44.toByte() &&
        data[3] == 0x46.toByte()
}

private fun renderPdfPages(context: Context, data: ByteArray): List<Bitmap> {
    val file = runCatching {
        com.oshi.messenger.data.local.atrest.PlaintextScratch.newFile(context.cacheDir, ".pdf")
    }.getOrNull() ?: return emptyList()
    return try {
        file.writeBytes(data)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val result = ArrayList<Bitmap>(renderer.pageCount)
        try {
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                // Render at a reasonable density (targeting ~2x display density for sharpness).
                val density = context.resources.displayMetrics.density
                val scale = density.coerceAtLeast(1f)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                result.add(bitmap)
            }
        } finally {
            renderer.close()
            pfd.close()
        }
        result
    } catch (e: Exception) {
        emptyList()
    } finally {
        // Pages are already rasterised: the plaintext PDF copy is not needed any more
        // (at-rest encryption, 2026-09-22).
        runCatching { file.delete() }
    }
}

private fun openDocumentWithExternalApp(
    context: Context,
    data: ByteArray,
    filename: String,
    mimeType: String
): Boolean {
    return try {
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ The decrypted document used to sit in cacheDir
        // under its original name forever. The external viewer reads it after we return, so
        // it lives in the share dir: purged at the next share / open and at the next launch.
        val file = com.oshi.messenger.data.local.atrest.PlaintextScratch
            .newShareFile(context.cacheDir, filename.ifEmpty { "document" })
        file.writeBytes(data)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }
}

private fun guessMimeType(filename: String): String {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}

/**
 * __SHARE_REAL_NAME_2026_09_27__ The name a shared attachment leaves under. For a
 * DOCUMENT the viewer's `caption` IS its name (the title bar and the PDF sniff already
 * treat it so); it used to be shared as `share_media.bin`, so the receiving app got
 * neither the name nor an extension to open it with. Photos and videos keep the
 * synthetic name — they have no user-chosen one.
 */
internal fun shareFileName(mediaType: String, caption: String): String {
    val synthetic = when (mediaType) {
        "IMAGE" -> "share_media.jpg"
        "VIDEO" -> "share_media.mp4"
        else -> "share_media.bin"
    }
    if (mediaType == "IMAGE" || mediaType == "VIDEO") return synthetic
    val name = com.oshi.messenger.util.AttachmentFileName.sanitize(
        caption.removePrefix("[DOCUMENT]").trim()
    )
    return name ?: synthetic
}

/** A mime for [fileName]'s extension, so the share sheet offers apps that can open it. */
internal fun shareMimeType(mediaType: String, fileName: String): String = when (mediaType) {
    "IMAGE" -> "image/jpeg"
    "VIDEO" -> "video/mp4"
    else -> when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "vcf", "vcard" -> "text/vcard"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "zip" -> "application/zip"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/octet-stream"
    }
}

private fun shareMedia(context: Context, mediaData: ByteArray, mediaType: String, caption: String = "") {
    try {
        val fileName = shareFileName(mediaType, caption)
        val mimeType = shareMimeType(mediaType, fileName)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, saveMediaToCache(context, mediaData, fileName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.media_share_button)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.media_share_failed), Toast.LENGTH_SHORT).show()
    }
}

private fun saveToGallery(context: Context, mediaData: ByteArray, mediaType: String) {
    try {
        val mimeType = when (mediaType) {
            "IMAGE" -> "image/jpeg"
            "VIDEO" -> "video/mp4"
            else -> return
        }
        val extension = when (mediaType) {
            "IMAGE" -> "jpg"
            "VIDEO" -> "mp4"
            else -> return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "OSHI_${System.currentTimeMillis()}.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OSHI")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { stream ->
                stream.write(mediaData)
            }
        }
        Toast.makeText(context, context.getString(R.string.saved_to_gallery), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.failed_to_save), Toast.LENGTH_SHORT).show()
    }
}

private fun saveMediaToCache(context: Context, mediaData: ByteArray, fileName: String): android.net.Uri? {
    // Share dir (purged at the next share and at launch), not a fixed name in cacheDir.
    val file = com.oshi.messenger.data.local.atrest.PlaintextScratch
        .newShareFile(context.cacheDir, fileName)
    file.writeBytes(mediaData)
    return androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
}
