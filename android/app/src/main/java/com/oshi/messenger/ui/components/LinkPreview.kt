package com.oshi.messenger.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import com.oshi.messenger.ui.theme.OshiCard
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Patterns
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.oshi.messenger.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.regex.Pattern
import com.oshi.messenger.ui.theme.brandInk
import com.oshi.messenger.ui.theme.isDarkScheme
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.util.TickerLinks
import androidx.compose.material3.LocalTextStyle

/**
 * LinkPreview - WhatsApp-style link preview component
 * Fetches OpenGraph metadata and displays preview card
 */

data class LinkPreviewData(
    val url: String,
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val siteName: String?
)

/**
 * 🔒 PRIVACY (2026-08-25). Every fetch below is gated on [LinkPreviewCache].
 *
 * This composable used to hold its result in `remember`, which a `LazyColumn`
 * throws away the instant the bubble scrolls off screen. Scrolling the same link
 * back into view therefore re-ran the page GET **and** the image GET — a new,
 * direct, unproxied HTTP request from the reader's device to a third-party host,
 * carrying their IP and the time they were reading, on every scroll. iOS fixed
 * exactly this with a three-tier cache (`OSHI/LinkPreview.swift:52-106`,
 * cache-hit short-circuit at `:126-132`); this is its port. See the class note on
 * [LinkPreviewCache] for the tiers, the 7-day TTL and the one divergence
 * (negative caching).
 */
/**
 * __LEAK_FIX_2026_09_25__ [autoFetch] = false for a RECEIVED message. A sender who
 * controls the linked host learned the recipient's IP address and the moment they
 * read the message, because the recipient's phone fetched the page (and its image)
 * on its own as soon as the bubble was drawn. For an incoming link we now only show
 * what the local cache already holds; otherwise a small "tap to load preview" row,
 * and nothing leaves the device until the reader asks. Outgoing links (the sender's
 * own device) keep the automatic preview.
 */
@Composable
fun LinkPreviewCard(
    url: String,
    modifier: Modifier = Modifier,
    autoFetch: Boolean = true,
) {
    var previewData by remember { mutableStateOf<LinkPreviewData?>(null) }
    var previewImage by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    // __LEAK_FIX_2026_09_25__ incoming link: no network until the reader taps.
    var userRequested by remember(url) { mutableStateOf(false) }
    var awaitingTap by remember(url) { mutableStateOf(false) }

    val uriHandler = LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val cache = remember(context) { LinkPreviewCache.shared(context) }

    LaunchedEffect(url, userRequested) {
        isLoading = true
        awaitingTap = false
        previewData = null
        previewImage = null
        try {
            // TIER 1+2 — memory, then disk. A hit ends the effect here: no socket is
            // opened at all, which is the whole point.
            val hit = withContext(Dispatchers.IO) { cache.lookup(url) }
            if (hit != null && !(hit.isNegative && userRequested)) {
                if (!hit.isNegative) {
                    previewData = hit.data
                    previewImage = hit.image?.let { withContext(Dispatchers.IO) { decodeImage(it) } }
                }
                isLoading = false
                return@LaunchedEffect
            }

            // __LEAK_FIX_2026_09_25__ Received link, nothing cached: stop here.
            if (!autoFetch && !userRequested) {
                awaitingTap = true
                isLoading = false
                return@LaunchedEffect
            }

            // TIER 3 — the network, once per URL per TTL.
            val fetched = fetchLinkPreview(url)
            if (fetched == null) {
                // Remember the failure too, briefly: a host that is down or blocks us
                // would otherwise be re-asked on every single scroll.
                withContext(Dispatchers.IO) { cache.storeFailure(url) }
                isLoading = false
                return@LaunchedEffect
            }
            previewData = fetched
            // Store the metadata BEFORE the image, exactly as iOS does
            // (`LinkPreview.swift:186-189`): if the image download fails or the page
            // has none, the expensive HTML fetch is still saved.
            withContext(Dispatchers.IO) { cache.store(url, fetched, null) }

            fetched.imageUrl?.let { imageUrl ->
                val bytes = fetchImageBytes(imageUrl)
                if (bytes != null) {
                    previewImage = withContext(Dispatchers.IO) { decodeImage(bytes) }
                    // Re-store WITH the image, replacing the metadata-only entry, so
                    // the next open needs no network at all (iOS `:227-231`).
                    withContext(Dispatchers.IO) { cache.store(url, fetched, bytes) }
                }
            }
        } catch (e: Exception) {
            // Silently fail - just don't show preview
        }
        isLoading = false
    }

    if (isLoading) {
        LinkPreviewLoading(modifier)
    } else if (awaitingTap) {
        LinkPreviewTapToLoad(url = url, onTap = { userRequested = true }, modifier = modifier)
    } else if (previewData != null) {
        LinkPreviewContent(
            data = previewData!!,
            image = previewImage,
            // Place links (Google/Apple Maps, geo:) follow "Open locations in" — MapOpener.
            onClick = { if (!MapOpener.handleLink(context, url)) uriHandler.openUri(url) },
            modifier = modifier
        )
    }
}

/** __LEAK_FIX_2026_09_25__ The placeholder of an incoming link: host only, fetched on tap. */
@Composable
private fun LinkPreviewTapToLoad(url: String, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val host = remember(url) {
        runCatching { java.net.URI(if (url.contains("://")) url else "https://$url").host }.getOrNull()
            ?.removePrefix("www.")
            ?: url
    }
    OshiCard(
        modifier = modifier
            .width(280.dp)
            .bubbleClickable(onClick = onTap),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = host,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.link_preview_tap_to_load),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LinkPreviewLoading(modifier: Modifier = Modifier) {
    OshiCard(
        modifier = modifier.width(280.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
            Text(
                text = stringResource(R.string.link_preview_loading),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LinkPreviewContent(
    data: LinkPreviewData,
    image: Bitmap?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OshiCard(
        modifier = modifier
            .width(280.dp)
            .bubbleClickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            // Image (if available)
            image?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.accessibility_link_preview_image),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            // Content - Fixed layout to prevent text cutoff
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Site name
                data.siteName?.let { siteName ->
                    Text(
                        text = siteName.uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Title
                data.title?.let { title ->
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp
                    )
                }

                // Description - with proper layout
                data.description?.let { description ->
                    Text(
                        text = description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp
                    )
                }

                // URL
                Text(
                    text = data.url,
                    fontSize = 10.sp,
                    color = brandInk(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Fetch OpenGraph metadata from URL
 */
private suspend fun fetchLinkPreview(urlString: String): LinkPreviewData? = withContext(Dispatchers.IO) {
    try {
        val url = URL(urlString)
        val connection = url.openConnection()
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) OSHI/1.0")

        val html = connection.getInputStream().bufferedReader().use { it.readText() }

        val title = extractMeta(html, "og:title") ?: extractTitle(html)
        val description = extractMeta(html, "og:description") ?: extractMeta(html, "description")
        val imageUrl = extractMeta(html, "og:image")?.let { makeAbsoluteUrl(it, urlString) }
        val siteName = extractMeta(html, "og:site_name") ?: extractDomain(urlString)

        LinkPreviewData(
            url = urlString,
            title = title,
            description = description,
            imageUrl = imageUrl,
            siteName = siteName
        )
    } catch (e: Exception) {
        null
    }
}

/**
 * Extract meta tag content
 */
private fun extractMeta(html: String, property: String): String? {
    // Try og: property first
    val ogPattern = Pattern.compile(
        "<meta\\s+(?:property|name)=\"$property\"\\s+content=\"([^\"]+)\"",
        Pattern.CASE_INSENSITIVE
    )
    val ogMatcher = ogPattern.matcher(html)
    if (ogMatcher.find()) {
        return ogMatcher.group(1)?.decodeHtmlEntities()
    }

    // Try alternate order
    val altPattern = Pattern.compile(
        "<meta\\s+content=\"([^\"]+)\"\\s+(?:property|name)=\"$property\"",
        Pattern.CASE_INSENSITIVE
    )
    val altMatcher = altPattern.matcher(html)
    if (altMatcher.find()) {
        return altMatcher.group(1)?.decodeHtmlEntities()
    }

    return null
}

/**
 * Extract title from HTML
 */
private fun extractTitle(html: String): String? {
    val pattern = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE)
    val matcher = pattern.matcher(html)
    return if (matcher.find()) {
        matcher.group(1)?.decodeHtmlEntities()
    } else null
}

/**
 * Extract domain from URL
 */
private fun extractDomain(urlString: String): String? {
    return try {
        val url = URL(urlString)
        val host = url.host
        val parts = host.split(".")
        if (parts.size >= 2) {
            parts[parts.size - 2].replaceFirstChar { it.uppercase() }
        } else {
            host.replaceFirstChar { it.uppercase() }
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Make URL absolute if relative
 */
private fun makeAbsoluteUrl(urlString: String, baseUrl: String): String {
    return when {
        urlString.startsWith("http") -> urlString
        urlString.startsWith("//") -> "https:$urlString"
        urlString.startsWith("/") -> {
            val base = URL(baseUrl)
            "${base.protocol}://${base.host}$urlString"
        }
        else -> urlString
    }
}

/**
 * Decode HTML entities
 */
private fun String.decodeHtmlEntities(): String {
    return this
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
}

/**
 * Fetch the OG image from a URL as RAW BYTES.
 *
 * Bytes, not a `Bitmap`: the bytes are what gets cached (a `Bitmap` is neither
 * serialisable to disk nor cheap to hold), and they are what iOS caches too — its
 * `previewImage` is a `Data` (`LinkPreview.swift:110`), decoded only at draw time.
 *
 * The 5 s timeouts stay. iOS uses a bare `URLSession.shared.dataTask` here and
 * inherits the 60 s default (`LinkPreview.swift:207`); Android is the
 * better-behaved side and this is deliberately NOT "fixed" toward iOS.
 */
private suspend fun fetchImageBytes(urlString: String): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val url = URL(urlString)
        val connection = url.openConnection()
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.getInputStream().use { input -> input.readBytes() }
    } catch (e: Exception) {
        null
    }
}

/** Decode cached or freshly fetched image bytes. Never throws. */
private fun decodeImage(bytes: ByteArray): Bitmap? =
    try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

/**
 * Detect first URL in text
 */
fun String.detectFirstUrl(): String? {
    val matcher = Patterns.WEB_URL.matcher(this)
    return if (matcher.find()) {
        val url = matcher.group()
        when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("www.") -> "https://$url"
            else -> "https://$url"
        }
    } else null
}

/**
 * Text composable with clickable URLs.
 * Detects URLs in text and makes them tappable (opens in browser).
 */
@Composable
fun LinkifyText(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    /**
     * Message text at semibold. Some people simply read a chat more easily that
     * way, and it is otherwise only reachable through the system-wide
     * accessibility setting (iOS BubbleStyleManager.swift:170-174, :285).
     * Null leaves the weight to the inherited style. The body no longer resets to
     * TextStyle.Default — see the note on `style` below — so callers now get the
     * app's typography instead of the platform default.
     */
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null
) {
    // __LONG_TEXT_ONE_MESSAGE_2026_09_28__ Android lays out paragraphs on LF
    // only; a CR / U+2028 / U+2029 line break from an older iOS or Desktop
    // sender rendered as one run-on block. Normalised once, here, so address
    // detection and the annotated string see the same indices.
    val shown = remember(text) { com.oshi.messenger.service.MessageSendPolicy.normalizeLineBreaks(text) }
    val uriHandler = LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val linkColor = androidx.compose.ui.graphics.Color(0xFF64B5F6) // Light blue for links

    // Street addresses become tappable too (iOS ClickableTextView does the same
    // with NSDataDetector) and open with the app chosen in "Open locations in".
    // Detected off the main thread by the system TextClassifier; until it
    // answers — or where it isn't available — the text simply has no address link.
    val addressRanges by produceState(initialValue = emptyList<IntRange>(), shown) {
        value = withContext(Dispatchers.Default) {
            MapOpener.detectAddresses(context, InlineMarkup.parse(shown).first)
        }
    }

    val annotatedString = remember(shown, addressRanges) {
        buildLinkAnnotatedString(shown, linkColor, color, addressRanges)
    }

    // __LONG_PRESS_ANYWHERE_ON_THE_BUBBLE_2026_11_09__ was `ClickableText`,
    // whose `detectTapGestures` consumes EVERY pointer-down on the paragraph —
    // so the bubble's long-press and double-tap were dead over the message
    // text (see `BubbleGestures.kt`). This detector consumes nothing but the
    // UP of a short tap that lands on a link; a press anywhere else, a long
    // press (link or not), and a double-tap all reach the bubble.
    val layout = remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val linkAt: (androidx.compose.ui.geometry.Offset) -> String? = { pos ->
        layout.value?.let { l ->
            val offset = l.getOffsetForPosition(pos)
            annotatedString.getStringAnnotations("URL", offset, offset).firstOrNull()?.item
        }
    }
    androidx.compose.foundation.text.BasicText(
        text = annotatedString,
        modifier = modifier.pointerInput(annotatedString) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                val url = linkAt(down.position)
                if (com.oshi.messenger.ui.components.BubbleGestureRule.textConsumesTap(
                        hitsLink = url != null,
                        pressDurationMs = up.uptimeMillis - down.uptimeMillis,
                        longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis,
                    )
                ) {
                    up.consume()
                    try {
                        if (!MapOpener.handleLink(context, url!!)) uriHandler.openUri(url)
                    } catch (_: Exception) { }
                }
            }
        },
        onTextLayout = { layout.value = it },
        // 🔤 INHERIT THE THEME, do not reset it.
        //
        // This was `TextStyle.Default.copy(...)`. `TextStyle.Default` carries
        // fontFamily = null and fontSize = Unspecified, and ClickableText's explicit
        // `style` bypasses LocalTextStyle entirely — so every 1:1 and group message
        // body rendered in the platform default face at ClickableText's own 14sp,
        // ignoring the app's typography completely.
        //
        // Two consequences, both invisible in review: the type scale defined in
        // Theme.kt could never reach the screen users spend all their time on, and
        // message text was already a size SMALLER than every other Text in the app
        // (M3 provides LocalTextStyle = bodyLarge, 16sp).
        //
        // Taking LocalTextStyle.current makes this behave like any other Text: it
        // picks up the theme by default and still honours a caller's ProvideTextStyle.
        style = LocalTextStyle.current.copy(fontWeight = fontWeight),
        maxLines = maxLines,
        overflow = overflow,
    )
}

/**
 * One stretch of the message that is replaced by a tappable run: a URL, or a
 * stock ticker rewritten from `@AAPL` to `$AAPL`.
 *
 * [start]/[end] are indices into the CLEANED text; [shown] is what the reader
 * sees, and it is NOT required to be the same length — `@NASDAQ:TSLA` (12 chars)
 * renders as `$TSLA` (5). That length change is the whole reason
 * [buildLinkAnnotatedString] carries an offset map.
 */
private data class LinkToken(
    val start: Int,
    val end: Int,
    val shown: String,
    val url: String,
    val underline: Boolean
)

private fun buildLinkAnnotatedString(
    text: String,
    linkColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    addressRanges: List<IntRange> = emptyList()
): androidx.compose.ui.text.AnnotatedString {
    // Strip iOS-compatible inline markup (*bold* _italic_ ~strike~ `code`) and get
    // the runs to style; run link detection on the CLEANED text so URLs and markup
    // coexist. Previously the raw markers rendered literally — an iPhone's *bold*
    // showed as "*bold*" on Android.
    val (clean, markupSpans) = InlineMarkup.parse(text)
    val builder = androidx.compose.ui.text.AnnotatedString.Builder()

    // 📈 TICKERS IN THE BUBBLE ITSELF (`OSHI/ClickableTextView.swift:143-155`).
    //
    // Android already drew a chip UNDER the bubble but left the sentence itself
    // saying `@AAPL` — so the same message read as a mention on Android and as a
    // price link on iPhone. The sigil the sender typed is an implementation
    // detail; `$AAPL` is the contract.
    //
    // Tickers are collected FIRST and URLs are then dropped where they collide,
    // because `TickerLinks` is the stricter matcher: its negative look-behind is
    // what keeps `hugo@ACME` and `x.com/i/@AB/status` out, and letting
    // `Patterns.WEB_URL` win a tie could split a token it half-recognised.
    val tokens = mutableListOf<LinkToken>()
    for (t in TickerLinks.matches(clean)) {
        // No underline, bold instead: iOS styles the chip `.stronglyEmphasized`
        // with the link colour and leaves underlining to real URLs, so the two
        // kinds of tappable text stay distinguishable.
        tokens += LinkToken(t.start, t.end, t.display, t.url, underline = false)
    }

    val matcher = Patterns.WEB_URL.matcher(clean)
    while (matcher.find()) {
        val start = matcher.start()
        val end = matcher.end()
        val urlText = matcher.group() ?: continue
        if (tokens.any { start < it.end && it.start < end }) continue
        val fullUrl = when {
            urlText.startsWith("http://") || urlText.startsWith("https://") -> urlText
            urlText.startsWith("www.") -> "https://$urlText"
            else -> "https://$urlText"
        }
        tokens += LinkToken(start, end, urlText, fullUrl, underline = true)
    }
    // Detected street addresses (ranges are into `clean`), where no URL/ticker sits.
    for (r in addressRanges) {
        val start = r.first
        val end = r.last + 1
        if (start < 0 || end > clean.length || start >= end) continue
        if (tokens.any { start < it.end && it.start < end }) continue
        val shown = clean.substring(start, end)
        tokens += LinkToken(start, end, shown, MapOpener.addressLink(shown), underline = true)
    }
    tokens.sortBy { it.start }

    // `offsets[i]` = where clean index `i` ended up in what we appended. Needed
    // because a ticker changes the length of the string, which would otherwise
    // slide every later markup run — iOS sidesteps this by editing back to front
    // on an AttributedString; a forward-only Builder needs the map instead.
    val offsets = IntArray(clean.length + 1)
    var lastEnd = 0

    for (token in tokens) {
        if (token.start < lastEnd) continue   // defensive: overlaps were filtered

        // Plain run before the token, 1:1 with `clean`.
        val shift = builder.length - lastEnd
        for (i in lastEnd..token.start) offsets[i] = i + shift
        if (token.start > lastEnd) {
            builder.pushStyle(androidx.compose.ui.text.SpanStyle(color = textColor))
            builder.append(clean.substring(lastEnd, token.start))
            builder.pop()
        }

        val outStart = builder.length
        builder.pushStyle(
            androidx.compose.ui.text.SpanStyle(
                color = linkColor,
                fontWeight = if (token.underline) null else androidx.compose.ui.text.font.FontWeight.Bold,
                textDecoration = if (token.underline)
                    androidx.compose.ui.text.style.TextDecoration.Underline else null
            )
        )
        builder.pushStringAnnotation("URL", token.url)
        builder.append(token.shown)
        builder.pop() // annotation
        builder.pop() // style

        // Everything the token covered now points at its start; the index just
        // past it points just past what replaced it.
        for (i in token.start until token.end) offsets[i] = outStart
        offsets[token.end] = builder.length
        lastEnd = token.end
    }

    // Add remaining text
    val tailShift = builder.length - lastEnd
    for (i in lastEnd..clean.length) offsets[i] = i + tailShift
    if (lastEnd < clean.length) {
        builder.pushStyle(androidx.compose.ui.text.SpanStyle(color = textColor))
        builder.append(clean.substring(lastEnd))
        builder.pop()
    }

    // Overlay the markup runs. Offsets are in `clean` coordinates and must be
    // translated through `offsets` — before tickers existed they could be used
    // raw, because nothing changed the length.
    for (span in markupSpans) {
        val style = when (span.kind) {
            InlineMarkup.Kind.BOLD ->
                androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            InlineMarkup.Kind.ITALIC ->
                androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            InlineMarkup.Kind.STRIKETHROUGH ->
                androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
            InlineMarkup.Kind.CODE ->
                androidx.compose.ui.text.SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        val from = span.location.coerceIn(0, clean.length)
        val to = (span.location + span.length).coerceIn(0, clean.length)
        if (from >= to) continue
        val outFrom = offsets[from]
        val outTo = offsets[to]
        if (outFrom < outTo) builder.addStyle(style, outFrom, outTo)
    }

    return builder.toAnnotatedString()
}
