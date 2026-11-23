package com.oshi.messenger.ui.screens

import android.content.ActivityNotFoundException
import com.oshi.messenger.ui.theme.OshiAlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.oshi.messenger.mail.MailDisplay
import com.oshi.messenger.mail.MailMime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/*
 * How the reader shows a message body and its attachments (2026-11-16).
 *
 * Until then the reader showed ONLY the plain text MailMime extracted: an HTML newsletter
 * was its text alternative, an inline (`cid:`) picture vanished without a trace, links were
 * not tappable, and an image attachment was a "save" row you could not look at.
 *
 * HTML now renders in a WebView locked down for hostile input: no JavaScript, no file or
 * content access, no mixed content, no third-party cookies, no navigation (every tap is
 * handed to [onLink]), and NO NETWORK unless the reader asked for it. Remote images are
 * the read receipt and IP leak of e-mail, so they stay off until "Load images" (this
 * message) or "Always from this sender" — the same default as Proton Mail and Apple's
 * Mail Privacy Protection.
 */

private const val PREFS = "mail_reader"
private const val KEY_SENDERS = "remote_images_senders"

/** "Always show images from this sender", stored as hashes (see MailDisplay.senderKey). */
internal object MailRemoteImagePrefs {
    fun allowed(context: Context, from: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SENDERS, emptySet()).orEmpty()
            .contains(MailDisplay.senderKey(from))

    fun allow(context: Context, from: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_SENDERS, emptySet()).orEmpty() + MailDisplay.senderKey(from)
        prefs.edit().putStringSet(KEY_SENDERS, set).apply()
    }
}

/** Plain-text body: per-line direction, tappable links, quoted replies and the signature set apart. */
@Composable
internal fun MailTextBody(text: String, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val blocks = remember(text) { MailDisplay.textBlocks(text) }
    val dark = MaterialTheme.colorScheme.background.let { (0.299 * it.red + 0.587 * it.green + 0.114 * it.blue) < 0.5 }
    // The dark theme's primary is a deep violet: lift it so links stay readable on black.
    val linkColor = if (dark) androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.primary, Color.White, 0.45f)
    else MaterialTheme.colorScheme.primary
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant
    val base = MaterialTheme.typography.bodyLarge.copy(
        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.15f,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Column(modifier) {
        blocks.forEach { block ->
            if (block.spaceBefore) Spacer(Modifier.height(12.dp))
            val style = when (block.kind) {
                MailDisplay.BlockKind.TEXT -> base
                else -> MaterialTheme.typography.bodyMedium.copy(color = subtle)
            }.copy(textDirection = if (block.rtl) TextDirection.Rtl else TextDirection.Ltr)
            val links = remember(block.text) { MailDisplay.links(block.text) }
            val annotated = remember(block.text, linkColor) {
                buildAnnotatedString {
                    append(block.text)
                    links.forEach { link ->
                        addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), link.start, link.end)
                        addStringAnnotation("URL", link.url, link.start, link.end)
                    }
                }
            }
            val content: @Composable (Modifier) -> Unit = { m ->
                ClickableText(annotated, m, style = style) { offset ->
                    annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { onLink(it.item) }
                }
            }
            if (block.kind == MailDisplay.BlockKind.QUOTE) {
                Row(Modifier.padding(start = ((block.quoteLevel - 1) * 10).dp).height(IntrinsicSize.Min)) {
                    Box(
                        Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Spacer(Modifier.width(10.dp))
                    content(Modifier.weight(1f))
                }
            } else {
                content(Modifier.fillMaxWidth())
            }
        }
    }
}

/** The HTML body in the locked-down WebView described at the top of this file. */
@Composable
internal fun MailHtmlBody(
    html: String,
    parts: List<MailMime.Attachment>,
    allowRemote: Boolean,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.let { (0.299 * it.red + 0.587 * it.green + 0.114 * it.blue) < 0.5 }
    val fontScale = LocalDensity.current.fontScale
    val doc = remember(html, allowRemote) { MailDisplay.readerHtml(html, allowRemote) }
    val byCid = remember(parts) { parts.filter { it.contentId != null }.associateBy { it.contentId!! } }
    val currentAllow by rememberUpdatedState(allowRemote)
    val currentOnLink by rememberUpdatedState(onLink)

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                settings.apply {
                    javaScriptEnabled = false
                    javaScriptCanOpenWindowsAutomatically = false
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    domStorageEnabled = false
                    setGeolocationEnabled(false)
                    setSupportMultipleWindows(false)
                    setSupportZoom(false)
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    // Follow the system font size, as the plain-text body does.
                    textZoom = (fontScale * 100).roundToInt()
                    if (Build.VERSION.SDK_INT >= 33) {
                        isAlgorithmicDarkeningAllowed = dark
                    } else if (Build.VERSION.SDK_INT >= 29) {
                        @Suppress("DEPRECATION")
                        forceDark = if (dark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
                    }
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                // A focusable WebView asks its scrolling parent to bring it into view when
                // the page loads, which scrolled the subject and sender off the top.
                isFocusable = false
                isFocusableInTouchMode = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        // Never navigate inside the reader; the tap goes to the caller.
                        request?.url?.toString()?.let { currentOnLink(it) }
                        return true
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Inside a scrolling Column the WebView is measured with an
                        // unbounded height; it only reports its content height once asked
                        // again after the page has laid out — without this it stayed 0 px.
                        view?.post { view.requestLayout() }
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url ?: return null
                        MailDisplay.cidOf(url.toString())?.let { id ->
                            val part = byCid[id] ?: return blocked()
                            return WebResourceResponse(part.mimeType, null, ByteArrayInputStream(part.bytes))
                        }
                        if (url.scheme == "data") return null
                        return if (currentAllow) null else blocked()
                    }
                }
            }
        },
        update = { view ->
            view.settings.blockNetworkLoads = !allowRemote
            val key = "$allowRemote|${doc.hashCode()}|${doc.length}"
            if (view.tag != key) {
                view.tag = key
                view.loadDataWithBaseURL(null, doc, "text/html", "utf-8", null)
            }
        },
    )
}

private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** Shown above an HTML body that would fetch something from the network. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteImagesBanner(onLoad: () -> Unit, onAlways: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.HideImage, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Images from the web are hidden. Loading them tells the sender that you opened this mail, and from which IP address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                // onSecondaryContainer, not primary: the dark theme's primary is a deep
                // violet that all but disappeared on this container.
                val colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                TextButton(onClick = onAlways, colors = colors) { Text("Always from this sender") }
                TextButton(onClick = onLoad, colors = colors) { Text("Load images", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
            }
        }
    }
}

/** Asks before leaving the app for a link whose target the HTML may have hidden behind other text. */
@Composable
internal fun OpenLinkDialog(url: String, onDismiss: () -> Unit, onOpen: () -> Unit) {
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open this link?") },
        text = { Text(url, maxLines = 6, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onOpen) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun openExternalLink(context: Context, url: String) {
    if (!MailDisplay.isOpenableLink(url)) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this link.", Toast.LENGTH_SHORT).show()
    }
}

// ---- image attachments ------------------------------------------------------------

/**
 * Decodes at most [maxPx] on the longer side. A 5000×3750 photo is 75 MB as a full
 * bitmap — more than a third of a typical app heap — so nothing here ever decodes one
 * at full size.
 */
internal fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val longest = maxOf(w, h)
            if (longest > maxPx) {
                val scale = maxPx.toFloat() / longest
                decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            }
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}.getOrNull()

@Composable
private fun rememberDecoded(attachment: MailMime.Attachment, maxPx: Int): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, attachment, maxPx) {
        value = withContext(Dispatchers.Default) { decodeSampled(attachment.bytes, maxPx)?.asImageBitmap() }
    }

/** Image attachments as a two-column grid of thumbnails; tap opens [ImageViewer]. */
@Composable
internal fun AttachmentImageGrid(images: List<MailMime.Attachment>, onOpen: (MailMime.Attachment) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        images.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { image ->
                    Box(Modifier.weight(1f).aspectRatio(if (images.size == 1) 4f / 3f else 1f)) {
                        ImageThumb(image, onOpen)
                    }
                }
                if (row.size == 1 && images.size > 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ImageThumb(image: MailMime.Attachment, onOpen: (MailMime.Attachment) -> Unit) {
    val bitmap by rememberDecoded(image, 720)
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = "View ${image.filename}") { onOpen(image) }
            .semantics { contentDescription = "Image ${image.filename}, ${MailDisplay.size(image.bytes.size.toLong())}" },
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Outlined.BrokenImage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        ) {
            Text(
                "${image.filename} · ${MailDisplay.size(image.bytes.size.toLong())}",
                color = Color.White, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Full-screen image with pinch-zoom, Share and Save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageViewer(image: MailMime.Attachment, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val maxPx = context.resources.displayMetrics.heightPixels.coerceIn(1024, 2560)
    val bitmap by rememberDecoded(image, maxPx)
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        EdgeToEdgeDialogWindow(lightBars = false)
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val b = bitmap
            if (b != null) {
                Image(
                    b, contentDescription = image.filename,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                if (scale == 1f) { offsetX = 0f; offsetY = 0f } else { offsetX += pan.x; offsetY += pan.y }
                            }
                        }
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
                )
            } else {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(image.filename, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(MailDisplay.size(image.bytes.size.toLong()), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { shareAttachment(context, image) }) { Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color.White) }
                IconButton(onClick = { saveAttachment(context, image) }) { Icon(Icons.Outlined.FileDownload, contentDescription = "Save to Downloads", tint = Color.White) }
            }
        }
    }
}

// ---- files: open, share, save ---------------------------------------------------------

/**
 * Decrypted attachments handed to another app go through the cache folder that the
 * FileProvider already exposes (`shared/`), in a `mail` subfolder that is emptied each
 * time a message is opened, so they do not pile up.
 */
private fun cacheFile(context: Context, attachment: MailMime.Attachment): File {
    val dir = File(context.cacheDir, "shared/mail").apply { mkdirs() }
    val safe = attachment.filename.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").ifBlank { "attachment" }.take(120)
    return File(dir, safe).apply { writeBytes(attachment.bytes) }
}

internal fun clearMailShareCache(context: Context) {
    runCatching { File(context.cacheDir, "shared/mail").listFiles()?.forEach { it.delete() } }
}

private fun uriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

private fun mimeOf(attachment: MailMime.Attachment): String =
    attachment.mimeType.takeIf { it.contains('/') && it != "application/octet-stream" }
        ?: android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(attachment.filename.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"

internal fun openAttachment(context: Context, attachment: MailMime.Attachment) {
    runCatching {
        val uri = uriFor(context, cacheFile(context, attachment))
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(attachment))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure {
        val msg = if (it is ActivityNotFoundException) "No app on this phone opens ${attachment.filename}. Save it instead."
        else "Could not open: ${it.message}"
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }
}

internal fun shareAttachment(context: Context, attachment: MailMime.Attachment) {
    runCatching {
        val uri = uriFor(context, cacheFile(context, attachment))
        val send = Intent(Intent.ACTION_SEND).setType(mimeOf(attachment))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, attachment.filename).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, "Could not share: ${it.message}", Toast.LENGTH_LONG).show() }
}

/** Writes a decrypted attachment where the user can find it, via MediaStore Downloads. */
internal fun saveAttachment(context: Context, attachment: MailMime.Attachment) {
    if (Build.VERSION.SDK_INT < 29) { shareAttachment(context, attachment); return }
    runCatching {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, attachment.filename)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeOf(attachment))
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("no Downloads folder")
        context.contentResolver.openOutputStream(uri)?.use { it.write(attachment.bytes) }
        Toast.makeText(context, "Saved to Downloads: ${attachment.filename}", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "Could not save: ${it.message}", Toast.LENGTH_LONG).show()
    }
}
