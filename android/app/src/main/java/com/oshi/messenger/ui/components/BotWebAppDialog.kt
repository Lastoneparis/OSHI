package com.oshi.messenger.ui.components

import com.oshi.messenger.ui.theme.glassEdge
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oshi.messenger.R

/**
 * The URL fragment key the mini-app bridge reads its session payload from.
 * `<url>#oshiWebAppData=<initData>` — the contract fixed by the bot platform.
 */
private const val INIT_DATA_FRAGMENT_KEY = "oshiWebAppData"

/** Name the `initData` accessor is bound under inside the page. */
private const val BRIDGE_OBJECT_NAME = "OshiWebAppBridge"

/**
 * Full-screen host for a bot mini-app.
 *
 * ## The session payload reaches the page three ways, on purpose
 *
 * 1. **The URL fragment** `#oshiWebAppData=…` — the documented contract, and the only
 *    one that survives the page navigating itself.
 * 2. **`window.oshiWebAppInitData`** — injected at `onPageStarted`, for a bridge that
 *    prefers a global to parsing `location.hash`.
 * 3. **`OshiWebAppBridge.getInitData()`** — a `@JavascriptInterface` binding, which is
 *    the only mechanism GUARANTEED to exist before the page's own scripts run; an
 *    `evaluateJavascript` at `onPageStarted` races them.
 *
 * (1) carries the value percent-encoded, as a fragment parameter must; (2) and (3)
 * carry it RAW, so a bridge that does not `decodeURIComponent` still has an exact copy.
 *
 * ## WebView hardening
 *
 * JavaScript is on — a mini-app is a web app, there is no version of this without it —
 * so everything that turns "JS runs" into "JS reads the phone" is off: no file access,
 * no content-provider access, and specifically no universal/file access FROM file URLs,
 * the pair that lets a `file://` document read any other file on the device. Mixed
 * content is refused, geolocation is refused, and navigation is confined to `https` so
 * the page cannot walk itself into a `file:`, `content:`, `intent:` or `javascript:`
 * URL. The bridge object exposes exactly one method, returning exactly the value the
 * page was already handed in its own URL.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BotWebAppDialog(
    botName: String,
    url: String,
    initData: String,
    onDismiss: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val fullUrl = remember(url, initData) { appendInitDataFragment(url, initData) }
    val bridge = remember(initData) { WebAppBridge(initData) }

    Dialog(
        onDismissRequest = {
            val view = webViewRef
            // System back walks the mini-app's own history first; only a page with
            // nowhere to go back to closes the sheet.
            if (view != null && view.canGoBack()) view.goBack() else onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        // __OLED_GLASS_2026_11_16__ OLED black: black ground, glass header bar.
        val glass = com.oshi.messenger.ui.theme.glassOn()
        Surface(color = if (glass) Color.Black else Color(0xFF12121F), modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header: whose mini-app this is, and the way out. Both are required —
                // a full-screen WebView with no attribution and no close control is
                // indistinguishable from the app itself having navigated somewhere.
                Surface(
                    color = if (glass) com.oshi.messenger.ui.theme.GlassTier.Bar.opaque else Color(0xFF1A1A2E),
                    modifier = Modifier.fillMaxWidth()
                        .then(if (glass) Modifier.glassEdge(top = false) else Modifier)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = null,
                            tint = Color(0xFF9BA4E8),
                            modifier = Modifier.size(16.dp)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        ) {
                            Text(
                                text = botName.ifBlank { stringResource(R.string.bot_webapp_default_title) },
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = Uri.parse(url).host.orEmpty(),
                                color = Color(0xFF9BA4E8),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.bot_webapp_close),
                                tint = Color(0xFF9BA4E8)
                            )
                        }
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(
                        color = Color(0xFF9BA4E8),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                harden(settings)
                                addJavascriptInterface(bridge, BRIDGE_OBJECT_NAME)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val scheme = request?.url?.scheme?.lowercase()
                                        // true = "we handled it" = do not load. Only
                                        // https ever loads here; nothing is forwarded to
                                        // the system either, because an arbitrary page
                                        // must not be able to fire an implicit Intent.
                                        return scheme != "https"
                                    }

                                    override fun onPageStarted(
                                        view: WebView?,
                                        pageUrl: String?,
                                        favicon: Bitmap?
                                    ) {
                                        super.onPageStarted(view, pageUrl, favicon)
                                        isLoading = true
                                        view?.evaluateJavascript(initDataGlobalScript(initData), null)
                                    }

                                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                        super.onPageFinished(view, pageUrl)
                                        isLoading = false
                                        // Belt to the onPageStarted brace: a page whose
                                        // own script ran before the injection still sees
                                        // the global once it settles.
                                        view?.evaluateJavascript(initDataGlobalScript(initData), null)
                                    }
                                }
                                loadUrl(fullUrl)
                                webViewRef = this
                            }
                        }
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { view ->
                // Stop the page BEFORE tearing the view down: a mini-app left running
                // in a detached WebView keeps timers, sockets and its JS bridge alive.
                view.stopLoading()
                view.loadUrl("about:blank")
                view.removeJavascriptInterface(BRIDGE_OBJECT_NAME)
                view.webViewClient = WebViewClient()
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            webViewRef = null
        }
    }
}

/**
 * The one object bound into the page. A single getter returning the value that is
 * already in the page's own URL, so the binding grants the page nothing it did not
 * have — it only makes the value readable before the first script runs, which the
 * fragment alone cannot guarantee for a bridge that initialises eagerly.
 */
private class WebAppBridge(private val initData: String) {
    @JavascriptInterface
    fun getInitData(): String = initData
}

/** `window.oshiWebAppInitData = "<raw initData>"`, safely quoted. */
private fun initDataGlobalScript(initData: String): String {
    val quoted = org.json.JSONObject.quote(initData)
    // Also declare what this host does NOT provide natively. The Mini App chrome
    // here is a title row and a close icon — no native main button, no native
    // popup — so oshi-webapp.js must keep its DOM fallbacks. Stated explicitly
    // rather than relying on the bridge's default: if a native main button is
    // ever added and this stays false, the user sees two buttons, which is a
    // visible bug instead of a silent one.
    return "(function(){try{window.oshiWebAppInitData=$quoted;" +
        "window.oshiWebAppCapabilities={mainButton:false,popups:false};}catch(e){}})();"
}

/**
 * `<url>#oshiWebAppData=<encoded initData>`, appended as an extra fragment parameter
 * when the URL already carries a fragment of its own.
 */
internal fun appendInitDataFragment(url: String, initData: String): String {
    if (initData.isEmpty()) return url
    val separator = if (url.contains('#')) "&" else "#"
    return "$url$separator$INIT_DATA_FRAGMENT_KEY=${Uri.encode(initData)}"
}

/** Everything that turns "JavaScript runs" into "JavaScript reads the device", off. */
private fun harden(settings: WebSettings) {
    settings.javaScriptEnabled = true          // a mini-app is a web app
    settings.domStorageEnabled = true          // and needs its own storage
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    @Suppress("DEPRECATION")
    settings.allowFileAccessFromFileURLs = false
    @Suppress("DEPRECATION")
    settings.allowUniversalAccessFromFileURLs = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.setGeolocationEnabled(false)
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    settings.mediaPlaybackRequiresUserGesture = true
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
}
