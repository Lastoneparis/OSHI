package com.oshi.messenger.ui

import com.oshi.messenger.ui.theme.glassEdge
import com.oshi.messenger.ui.theme.glassGlow
import android.Manifest
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import com.oshi.messenger.ui.theme.OshiAlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import com.oshi.messenger.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Brush
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.lifecycle.lifecycleScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.oshi.messenger.ui.screens.*
import com.oshi.messenger.ui.screens.MapScreen
import com.oshi.messenger.ui.screens.MapRegionDownloadScreen
import com.oshi.messenger.ui.screens.MapNavigationScreen
import com.oshi.messenger.ui.screens.ChatsTabScreen
import com.oshi.messenger.ui.screens.ItineraryScreen
import com.oshi.messenger.ui.screens.AIChatScreen
import com.oshi.messenger.ui.viewmodel.MapViewModel
import com.oshi.messenger.data.model.MapPOI
import com.oshi.messenger.service.map.MapEntryFocus
import com.oshi.messenger.service.map.SharedLocationFocus
import com.oshi.messenger.data.model.POICategory
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.oshi.messenger.ui.screens.IncomingCallOverlay
import com.oshi.messenger.ui.screens.BotPortalScreen
import com.oshi.messenger.ui.screens.BotCreateScreen
import com.oshi.messenger.ui.screens.BotDetailScreen
import com.oshi.messenger.ui.screens.SafetyNumberScreen
import com.oshi.messenger.ui.theme.BubbleStyle
import com.oshi.messenger.ui.theme.LocalBubbleStyle
import com.oshi.messenger.ui.theme.OSHITheme
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.rememberWindowSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.oshi.messenger.ui.viewmodel.MainViewModel
import com.oshi.messenger.ui.components.ReviewDeliveryEffect
import com.oshi.messenger.util.InviteLink
import android.content.Intent
import com.oshi.messenger.service.CallState
import com.oshi.messenger.service.MessageSyncService
import com.oshi.messenger.service.ScreenshotProtectionManager
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.oshi.messenger.ui.viewmodel.dataStore
import javax.inject.Inject
import com.oshi.messenger.network.mesh.MeshPermissionRequests

/** Encode a public key for safe use in navigation routes (no +, /, = characters) */
private fun encodeKeyForNav(publicKey: String): String {
    return android.util.Base64.encodeToString(
        publicKey.toByteArray(Charsets.UTF_8),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
    )
}

/** Decode a navigation-safe key back to the original public key */
private fun decodeKeyFromNav(encoded: String): String {
    return String(
        android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING),
        Charsets.UTF_8
    )
}

// ════════════════════════════════════════════════════════════════════════════
// SCREEN TRANSITIONS
//
// Every destination used to be declared bare, which means navigation-compose
// applied its own default — `fadeIn/fadeOut(tween(700))` (NavHost.kt, 2.7.5).
// Seven hundred milliseconds of cross-fade is both the wrong SHAPE (a chat is
// pushed onto a stack, it does not dissolve into place) and long enough to feel
// like lag on every conversation open.
//
// Two shapes, matching what the iOS build does:
//   • Tab switches stay a cross-fade — the tabs are siblings, nothing is
//     "deeper" than anything else — but at 180 ms instead of 700.
//   • Pushed detail screens (a chat, a group chat, the safety number) slide in
//     from the trailing edge while the screen behind them drifts a quarter of
//     the way out and dims. Popping runs the same motion in reverse.
//
// Durations are deliberately short. `NAV_PUSH_MS` is the whole slide; anything
// past ~300 ms reads as sluggish rather than smooth, and a transition that
// makes the app feel slow is worse than no transition at all.
// ════════════════════════════════════════════════════════════════════════════

private const val NAV_PUSH_MS = 280
private const val NAV_FADE_MS = 180

/**
 * How far the screen BEHIND a push travels, as a divisor of its own width.
 * Moving it the full width would look like two screens racing each other; a
 * quarter reads as parallax, i.e. as depth.
 */
private const val NAV_PARALLAX_DIVISOR = 4

private typealias NavEnter = AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
private typealias NavExit = AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition

/**
 * Whether the platform has been asked to stop animating.
 *
 * The Android analogue of iOS's `accessibilityReduceMotion` is the system
 * animator duration scale being switched off (Developer options → "Animator
 * duration scale: off", and what "Remove animations" in Accessibility sets).
 * [LaunchScreen] already reads exactly this; the nav graph now honours it too,
 * instead of sliding screens at a user who turned animation off system-wide.
 */
private fun isReduceMotionOn(context: android.content.Context): Boolean = try {
    android.provider.Settings.Global.getFloat(
        context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    ) == 0f
} catch (_: Exception) {
    false
}

/** New screen arrives from the trailing edge. */
private fun navPushEnter(reduceMotion: Boolean): NavEnter = {
    if (reduceMotion) EnterTransition.None
    else slideInHorizontally(animationSpec = tween(NAV_PUSH_MS)) { fullWidth -> fullWidth } +
        fadeIn(animationSpec = tween(NAV_PUSH_MS))
}

/** Screen being covered drifts out under the new one. */
private fun navPushExit(reduceMotion: Boolean): NavExit = {
    if (reduceMotion) ExitTransition.None
    else slideOutHorizontally(animationSpec = tween(NAV_PUSH_MS)) { fullWidth ->
        -fullWidth / NAV_PARALLAX_DIVISOR
    } + fadeOut(animationSpec = tween(NAV_PUSH_MS))
}

/** Popping back: the screen underneath slides back in from where it drifted to. */
private fun navPopEnter(reduceMotion: Boolean): NavEnter = {
    if (reduceMotion) EnterTransition.None
    else slideInHorizontally(animationSpec = tween(NAV_PUSH_MS)) { fullWidth ->
        -fullWidth / NAV_PARALLAX_DIVISOR
    } + fadeIn(animationSpec = tween(NAV_PUSH_MS))
}

/** Popping back: the screen being dismissed leaves the way it came in. */
private fun navPopExit(reduceMotion: Boolean): NavExit = {
    if (reduceMotion) ExitTransition.None
    else slideOutHorizontally(animationSpec = tween(NAV_PUSH_MS)) { fullWidth -> fullWidth } +
        fadeOut(animationSpec = tween(NAV_PUSH_MS))
}

/** Sibling-to-sibling (tab bar): cross-fade, no direction implied. */
private fun navSiblingEnter(reduceMotion: Boolean): NavEnter = {
    if (reduceMotion) EnterTransition.None else fadeIn(animationSpec = tween(NAV_FADE_MS))
}

private fun navSiblingExit(reduceMotion: Boolean): NavExit = {
    if (reduceMotion) ExitTransition.None else fadeOut(animationSpec = tween(NAV_FADE_MS))
}

// ════════════════════════════════════════════════════════════════════════════
// DEEP LINK PARSING
//
// Port of iOS SharedContentHandler.handleURL (SharedContentHandler.swift:51-160).
// Deliberately written with ZERO Android dependencies (no android.net.Uri) so it
// runs — and is tested — on the plain JVM. See DeepLinkParserTest.
// ════════════════════════════════════════════════════════════════════════════

/** Outcome of parsing an OSHI deep link / universal link. */
sealed class OshiDeepLink {
    /**
     * `oshi://add?key=…` or `https://oshi-messenger.{com,org}/add?key=…`.
     *
     * (N1) `name` is the sender's own display name, carried the same way a
     * group invite already carries one (`&name=`, see [GroupInvite]) — so
     * the add-contact prompt can default to something more useful than the
     * raw key. Absent on a link generated before this, or on any link typed
     * in by hand; null, not blank, so a caller can tell "no name offered"
     * apart from "an empty name was offered".
     */
    data class AddContact(val publicKey: String, val name: String? = null) : OshiDeepLink()

    /** `oshi://chat?key=…` or `https://oshi-messenger.{com,org}/chat?key=…` */
    data class OpenChat(val publicKey: String) : OshiDeepLink()

    /**
     * `…/group?gid=…&name=…&inviter=…` (or the legacy `…/group/<UUID>` form).
     * The gid/name/inviter extraction + UUID validation stays in
     * [MainActivity.handleGroupInviteUri], which already shipped and works.
     */
    object GroupInvite : OshiDeepLink()

    /**
     * `oshi://share` — on iOS this pokes the app to drain the Share Extension's
     * App Group hand-off. Android has a real `ACTION_SEND` intent filter instead,
     * so there is nothing to drain; recognised and ignored.
     */
    object Share : OshiDeepLink()

    /** Our host/scheme, but a route we don't serve or a route missing its payload. */
    data class Unhandled(val route: String) : OshiDeepLink()

    /** Not an OSHI link at all — different scheme or a host we don't own. */
    object NotOurs : OshiDeepLink()
}

object DeepLinkParser {

    /**
     * Every host iOS accepts — `OSHI.entitlements:13-17` declares
     * `applinks:oshi-messenger.com` + `applinks:oshi-messenger.org`, and
     * `SharedContentHandler.swift:64-65` accepts both plus their `www.` forms.
     * The app's own generated links use **.org** (NewMessageScreen.kt:814,
     * QRCodeScreen.kt:188, iOS NewMessageView.swift:706).
     */
    val ACCEPTED_HOSTS: Set<String> = setOf(
        "oshi-messenger.com", "www.oshi-messenger.com",
        "oshi-messenger.org", "www.oshi-messenger.org"
    )

    fun parse(raw: String?): OshiDeepLink {
        if (raw.isNullOrBlank()) return OshiDeepLink.NotOurs

        val schemeSep = raw.indexOf("://")
        if (schemeSep <= 0) return OshiDeepLink.NotOurs
        val scheme = raw.substring(0, schemeSep).lowercase()

        // Strip the fragment, then split authority / path / query.
        val rest = raw.substring(schemeSep + 3).substringBefore('#')
        val beforeQuery = rest.substringBefore('?')
        val query = if (rest.contains('?')) rest.substringAfter('?') else ""
        val authority = beforeQuery.substringBefore('/')
        val path = beforeQuery.removePrefix(authority)
        // userinfo@host:port → host
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()

        val route: String = when (scheme) {
            // Custom scheme: the ROUTE is the host (iOS: `route = url.host`).
            "oshi" -> host
            // Universal link: the route is the first path segment
            // (iOS: `url.pathComponents.filter { $0 != "/" }.first`).
            "https" -> {
                if (host !in ACCEPTED_HOSTS) return OshiDeepLink.NotOurs
                path.split('/').firstOrNull { it.isNotEmpty() }.orEmpty()
            }
            else -> return OshiDeepLink.NotOurs
        }
        if (route.isEmpty()) return OshiDeepLink.NotOurs

        return when (route) {
            "share" -> OshiDeepLink.Share
            "add" -> {
                val key = firstQueryValue(query, "key", "k")
                if (key.isNullOrBlank()) OshiDeepLink.Unhandled(route)
                else OshiDeepLink.AddContact(key, firstQueryValue(query, "name")?.ifBlank { null })
            }
            "chat" -> {
                val key = firstQueryValue(query, "key", "k")
                if (key.isNullOrBlank()) OshiDeepLink.Unhandled(route)
                else OshiDeepLink.OpenChat(key)
            }
            "group" -> OshiDeepLink.GroupInvite
            else -> OshiDeepLink.Unhandled(route)
        }
    }

    /**
     * First query item whose name is any of [names], percent-decoded.
     *
     * Matches iOS `URLComponents.queryItems` semantics: percent-escapes are
     * decoded but a literal `+` stays a `+`. That matters — public keys are
     * base64 and `+` is a base64 character, so the usual
     * `URLDecoder.decode` (which turns `+` into a space) would silently
     * corrupt every key that happened to be sent unencoded.
     */
    internal fun firstQueryValue(query: String, vararg names: String): String? {
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val name = if (eq < 0) pair else pair.substring(0, eq)
            if (percentDecode(name) !in names) continue
            val value = if (eq < 0) "" else pair.substring(eq + 1)
            return percentDecode(value)
        }
        return null
    }

    /** Percent-decode without the `+` → space substitution. */
    internal fun percentDecode(value: String): String = try {
        java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        value
    }
}

/**
 * MainActivity - iOS-style Bottom Navigation
 *
 * Matches iOS ContentView.swift with 5 bottom tabs:
 * 1. Chats (messages + groups)
 * 2. AI (offline AI assistant)
 * 3. New Message (center + button, prominent)
 * 4. Map (offline maps)
 * 5. Settings (gear)
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var screenshotProtection: ScreenshotProtectionManager

    /** True when the current route is a video call screen */
    var isOnVideoCall = false
        internal set

    /**
     * Auto-lock state — set true when backgrounded longer than the configured timeout
     * while biometric is enabled. UI overlays a lock screen and suspends reveal until
     * BiometricPrompt succeeds. Mirrors iOS "autoLockEnabled" behavior.
     */
    internal val autoLockTriggered = androidx.compose.runtime.mutableStateOf(false)
    private var backgroundedAtMs: Long = 0L

    /** Pending navigation route from notification tap — consumed once by Compose */
    private var pendingNavRoute by androidx.compose.runtime.mutableStateOf<String?>(null)

    /**
     * The user tapped a check-in notification (the ongoing journey status, the
     * "Are you okay?" prompt, or an arrival/emergency alert).
     *
     * `CheckInManager.EXTRA_OPEN_CHECKIN` was written by two different notification
     * builders and **read by nobody**: tapping any of them opened the app on
     * whatever screen it was last on, with no check-in controls anywhere. This is
     * the read.
     */
    private var pendingOpenCheckIn by androidx.compose.runtime.mutableStateOf(false)

    /**
     * __RETURN_TO_CALL_2026_10_28__ (peerKey, isVideo) set when the ongoing-call
     * notification is tapped, consumed once by the NavHost. N19: the reporter walked into
     * a chat during a call and could not get back to hang up.
     */
    private var pendingReturnToCall by androidx.compose.runtime.mutableStateOf<Pair<String, Boolean>?>(null)

    /** Pending group invite (from oshi://group/join?gid=…&name=…&inviter=… or universal link) */
    internal var pendingGroupInvite by androidx.compose.runtime.mutableStateOf<GroupInviteData?>(null)

    /** Pending invalid invite (shows error dialog when true) */
    internal var pendingInvalidInvite by androidx.compose.runtime.mutableStateOf(false)

    /**
     * Pending "add contact" from an `…/add?key=…` deep link — shows
     * [AddContactFromLinkDialog]. Mirrors iOS `AddContactFromLinkView`
     * (SharedContentHandler.swift:593 ← SecureWeb3MessengerApp.swift:137).
     */
    internal var pendingAddContact by androidx.compose.runtime.mutableStateOf<String?>(null)

    /** (N1) The name offered by an `…/add?key=…&name=…` link, if any. */
    internal var pendingAddContactName by androidx.compose.runtime.mutableStateOf<String?>(null)

    /**
     * Pending inbound share (Android share sheet → OSHI). Mirrors iOS's Share
     * Extension + `SharePickerView` (SharedContentHandler.swift:280).
     */
    internal var pendingShare by androidx.compose.runtime.mutableStateOf<SharedContent?>(null)
    /** STEGO-v1: a single shared image, checked for a hidden message for us before the share picker. */
    internal var pendingStegoCheck by androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null)

    data class GroupInviteData(val gid: String, val name: String?, val inviter: String?)

    /**
     * Content handed to OSHI by another app through the system share sheet.
     * Field-for-field the Android equivalent of iOS
     * `SharedContentHandler.SharedContent` (SharedContentHandler.swift:37-43).
     */
    data class SharedContent(
        val text: String? = null,
        val streams: List<android.net.Uri> = emptyList(),
        val mimeType: String? = null
    ) {
        val isEmpty: Boolean get() = text.isNullOrBlank() && streams.isEmpty()
    }

    @Inject lateinit var messageRepository: com.oshi.messenger.data.repository.MessageRepository

    /** This phone's position for the LoRa node distances (iOS reads `LocationSharingManager.shared.currentLocation`). */
    @Inject lateinit var locationSharingManager: com.oshi.messenger.network.location.LocationSharingManager

    /** Extract a navigation route from a notification intent's extras OR a deep-link URI. */
    private fun handleNavIntent(intent: Intent?) {
        if (intent == null) return

        // 0. Inbound share from another app's share sheet (ACTION_SEND /
        //    ACTION_SEND_MULTIPLE). iOS's equivalent is the OSHIShare Share
        //    Extension writing to the App Group; on Android the payload is
        //    right here in the intent.
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            handleShareIntent(intent)
            return
        }

        // 0.4 __RETURN_TO_CALL_2026_10_28__ the ongoing-call notification was tapped.
        //     First, because a running call outranks anything else this intent could
        //     carry: the user is trying to get back to it to hang up.
        if (intent.getBooleanExtra(com.oshi.messenger.service.CallService.EXTRA_RETURN_TO_CALL, false)) {
            val key = intent.getStringExtra(com.oshi.messenger.service.CallService.EXTRA_PEER_KEY).orEmpty()
            if (key.isNotEmpty()) {
                pendingReturnToCall = key to intent.getBooleanExtra(
                    com.oshi.messenger.service.CallService.EXTRA_IS_VIDEO, false
                )
            }
            return
        }

        // 0.5 Check-in notification tap. Checked BEFORE the sender/group extras
        //     because a check-in alert carries neither, and before the deep-link
        //     branch because it carries no URI either — under the old order it fell
        //     off the end of this function and was silently dropped.
        if (intent.getBooleanExtra(com.oshi.messenger.service.CheckInManager.EXTRA_OPEN_CHECKIN, false)) {
            pendingOpenCheckIn = true
            return
        }

        // 0.7 Mail notification tap. Opens the mailbox, on the tapped message when the
        //     push carried its id (iOS routes the same `type == "mail"` + messageId).
        //     Checked here because a mail push carries neither sender nor group extras.
        if (intent.getStringExtra("type") == "mail") {
            val mailMessageId = intent.getStringExtra("mailMessageId")
            pendingNavRoute = if (!mailMessageId.isNullOrEmpty()) "mail?messageId=$mailMessageId" else "mail"
            return
        }

        // 1. Notification extras (FCM / APNs)
        val groupId = intent.getStringExtra("groupId")
        val senderKey = intent.getStringExtra("senderKey")
            ?: intent.getStringExtra("senderPublicKey")
        if (!groupId.isNullOrEmpty()) {
            pendingNavRoute = "groupChat/$groupId"
            return
        }
        if (!senderKey.isNullOrEmpty()) {
            pendingNavRoute = "chat/${encodeKeyForNav(senderKey)}"
            return
        }

        // 2. Deep-link URI (oshi://… or https://oshi-messenger.com/…)
        val data = intent.data ?: return
        handleDeepLink(data)
    }

    /**
     * Parse an OSHI deep link / universal link and route it.
     * Parsing itself lives in [DeepLinkParser] (pure JVM, unit-tested).
     *
     * Supported formats — the full set iOS supports
     * (SharedContentHandler.swift:51-160):
     *   • oshi://add?key=<pubkey>            / https://oshi-messenger.{com,org}/add?key=…
     *   • oshi://chat?key=<pubkey>           / https://oshi-messenger.{com,org}/chat?key=…
     *   • oshi://group/join?gid=<UUID>&name=<urlencoded>&inviter=<pubkey>
     *   • https://oshi-messenger.{com,org}/group?gid=<UUID>&name=…&inviter=…
     *   • oshi://group/<UUID>                       (legacy)
     *   • https://oshi-messenger.{com,org}/group/<UUID>   (legacy)
     *   • oshi://share                       (iOS share-extension hand-off — n/a on Android)
     * `key` also accepts the short alias `k`.
     */
    private fun handleDeepLink(uri: android.net.Uri) {
        Log.d("MainActivity", "📥 Deep link: $uri")
        when (val link = DeepLinkParser.parse(uri.toString())) {
            is OshiDeepLink.AddContact -> {
                Log.d("MainActivity", "🔑 Deep link: add contact ${link.publicKey.take(12)}…")
                pendingAddContact = link.publicKey
                pendingAddContactName = link.name
            }
            is OshiDeepLink.OpenChat -> {
                // iOS leaves this as a TODO (SharedContentHandler.swift:99); Android
                // has a real chat route, so honour the link instead of dropping it.
                Log.d("MainActivity", "💬 Deep link: open chat ${link.publicKey.take(12)}…")
                pendingNavRoute = "chat/${encodeKeyForNav(link.publicKey)}"
            }
            is OshiDeepLink.GroupInvite -> handleGroupInviteUri(uri)
            is OshiDeepLink.Share ->
                Log.d("MainActivity", "Deep link: share route — Android uses ACTION_SEND, ignoring")
            is OshiDeepLink.Unhandled ->
                Log.d("MainActivity", "Unhandled deep-link route: ${link.route}")
            is OshiDeepLink.NotOurs ->
                Log.d("MainActivity", "Not an OSHI link: $uri")
        }
    }

    private fun handleGroupInviteUri(uri: android.net.Uri) {
        // Try query-param format first (new): ?gid=…&name=…&inviter=…
        var gid = uri.getQueryParameter("gid")
        val name = uri.getQueryParameter("name")
        val inviter = uri.getQueryParameter("inviter")

        // Fall back to legacy path-based format: /group/<UUID> or oshi://group/<UUID>
        if (gid.isNullOrBlank()) {
            val pathSegments = uri.pathSegments
            gid = when {
                uri.scheme == "oshi" ->
                    pathSegments.firstOrNull()?.takeIf { it != "join" }
                else ->
                    if (pathSegments.size >= 2 && pathSegments[1] != "join") pathSegments[1] else null
            }
        }

        val uuidValid = try {
            !gid.isNullOrBlank() && java.util.UUID.fromString(gid).toString().equals(gid, ignoreCase = true)
        } catch (_: Exception) { false }

        if (!uuidValid) {
            Log.w("MainActivity", "Invalid group invite link (bad gid): $uri")
            pendingInvalidInvite = true
            return
        }

        pendingGroupInvite = GroupInviteData(gid = gid!!, name = name, inviter = inviter)
        Log.d("MainActivity", "👥 Pending group invite: gid=$gid name=$name inviter=${inviter?.take(12)}")
    }

    /**
     * Drain an inbound `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent into
     * [pendingShare], which raises the recipient picker.
     *
     * Accepts the same content classes as the iOS Share Extension
     * (OSHIShare/Info.plist:27-39): images (≤10), movies (≤5), a web URL,
     * plain text and arbitrary files (≤10).
     */
    private fun handleShareIntent(intent: Intent) {
        @Suppress("DEPRECATION")
        val streams: List<android.net.Uri> = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            else ->
                listOfNotNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
        }
        // EXTRA_TEXT arrives as a String from most apps and as a CharSequence
        // (styled text) from a few — read both or Chrome's "share link" is lost.
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)

        val content = SharedContent(
            text = (text ?: subject)?.takeIf { it.isNotBlank() },
            // iOS caps at 10 images / 5 movies / 10 files; take the same ceiling
            // rather than letting a 200-photo selection queue 200 sends.
            streams = streams.take(10),
            mimeType = intent.type
        )
        if (content.isEmpty) {
            Log.w("MainActivity", "Share intent carried no text and no stream — ignoring")
            return
        }
        Log.d("MainActivity", "📤 Inbound share: ${content.streams.size} stream(s), text=${content.text != null}, type=${content.mimeType}")
        pendingShare = content
        // "Disguise as photo" reveal: a single shared image may carry a hidden
        // message for us. Checked in the background; only a hit replaces the share
        // picker with the reveal dialog — any other image is shared as before.
        pendingStegoCheck = content.streams.singleOrNull()?.takeIf { content.text == null }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The flag has to be re-evaluated per intent, not once per process: an activity
        // that answered a call keeps showing over the keyguard for every later launch
        // otherwise, which is the shape of the bug this came from.
        applyLockScreenVisibility(intent)
        // DEBUG builds only (no-op in release): store-shot capture override.
        screenshotProtection.applyDebugCaptureOverride(intent)
        handleNavIntent(intent)
    }

    /**
     * __LOCKED_MEANS_LOCKED_2026_10_28__ Whether this activity may be shown over the
     * lock screen — true ONLY while answering an incoming call.
     *
     * N12 was reported, fixed, and reported again. The first fix was
     * `setAuthenticationRequired(true)` on the notification's reply and mark-read
     * actions (FCMService), which is correct and insufficient: it gates the ACTIONS,
     * while this gated nothing. `onCreate` called `setShowWhenLocked(true)` and
     * `setTurnScreenOn(true)` UNCONDITIONALLY, for every launch of MainActivity. So
     * tapping any message notification on a locked phone opened the whole app over the
     * keyguard — the conversation readable, the composer usable — and no notification
     * flag could have prevented that, because the reply was no longer happening in a
     * notification.
     *
     * The comment it carried ("required when accepting via the full-screen intent
     * notification while the phone is locked. Mirrors WhatsApp / Telegram behavior")
     * described the CALL case accurately and was applied to every case. WhatsApp shows
     * its call screen over the keyguard; it does not show your chats there.
     *
     * The incoming-call launch is distinguishable: `IncomingCallService` puts
     * `incoming_call` on both the content and full-screen intents. Everything else
     * clears the flags, so a process that answered a call does not stay privileged for
     * the rest of its life.
     */
    private fun applyLockScreenVisibility(intent: Intent?) {
        val forIncomingCall = intent?.getBooleanExtra("incoming_call", false) == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(forIncomingCall)
            setTurnScreenOn(forIncomingCall)
        } else {
            @Suppress("DEPRECATION")
            val flags = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            if (forIncomingCall) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    /** True when currently in PiP mode */
    var isInPipMode = false
        private set

    /** Mute state for PiP actions — updated by VideoCallViewModel observers */
    var pipIsMuted = false

    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_PIP_MUTE = "com.oshi.messenger.pip.MUTE"
        const val ACTION_PIP_END = "com.oshi.messenger.pip.END"
        const val PIP_MUTE_REQUEST = 1001
        const val PIP_END_REQUEST = 1002

        /**
         * Upper bound on how long the system splash may be held waiting for
         * Compose's first frame. See [holdSystemSplashUntilComposeDraws].
         */
        private const val SPLASH_HOLD_TIMEOUT_MS = 2000L

        /**
         * Minimum time the branded Compose launch screen stays up once it has been
         * handed the window. Sized to its own intro animation — iOS's equivalent
         * runs a 0.3 s fade plus 0.15 s-staggered dots
         * (OSHI/LaunchScreenView.swift:155-179).
         */
        private const val LAUNCH_MIN_VISIBLE_MS = 900L

        /**
         * API 31+: if the splash exit listener has not run this long after our
         * first frame, there was no OS splash (a launch without one): start
         * the intro anyway. Observed hand-over: ~150 ms on a loaded emulator.
         */
        private const val SPLASH_HANDOVER_FALLBACK_MS = 600L
    }

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PIP_MUTE -> {
                    // Toggle mute via EnhancedCallManager — we'll send a broadcast the ViewModel listens to
                    sendBroadcast(Intent("com.oshi.messenger.call.TOGGLE_MUTE"))
                }
                ACTION_PIP_END -> {
                    sendBroadcast(Intent("com.oshi.messenger.call.END_CALL"))
                }
            }
        }
    }

    // Notification permission launcher (only permission requested at startup)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("MainActivity", "POST_NOTIFICATIONS permission granted: $granted")
    }

    /**
     * __FORCED_UPDATE_ANDROID_2026_10_13__ Play's IMMEDIATE update flow.
     *
     * Registered as a property, not inside `onCreate`, because
     * `registerForActivityResult` has to run before the activity reaches STARTED
     * or it throws — and a crash in the thing that enforces updates would be a
     * particularly stupid way to lose the users it exists to keep current.
     */
    private val immediateUpdateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        com.oshi.messenger.update.ForcedUpdateManager.onFlowResult(result.resultCode)
    }

    /**
     * Refresh the server floors, then let [ForcedUpdateManager] decide.
     *
     * The order matters: `MinimumVersionGate.refresh()` is what populates
     * `blockingVersion`, and on a cold start it has never run, so asking Play
     * first would always read a null floor and never block. The refresh is
     * TTL'd at 6 h internally, so this is one request a session at most.
     *
     * Everything in here fails open. A thrown exception, a dead network, a
     * server that has never heard of the key: all of them leave the app
     * untouched and usable offline, which is the owner's standing contract on
     * version gates.
     */
    private fun checkForcedUpdate() {
        lifecycleScope.launch {
            runCatching {
                com.oshi.messenger.network.version.MinimumVersionGate.refresh()
                com.oshi.messenger.update.ForcedUpdateManager.check(
                    this@MainActivity,
                    immediateUpdateLauncher
                )
            }.onFailure {
                Log.d("MainActivity", "forced-update check skipped: ${it.javaClass.simpleName}")
            }
        }
    }

    /**
     * 🔧 P1 FIX (device walkthrough #2, 2026-08-13) — the ported Compose launch
     * screen never actually rendered.
     *
     * `values-v31/themes.xml` declares an Android-12 system splash
     * (`windowSplashScreenAnimatedIcon`). That splash owns the **entire** launch
     * window: it stays up until the activity draws its first frame and is then
     * torn down. `MainViewModel.isLoading` (DataStore) flipped false long before
     * that first frame, so across four cold launches — 10-frame bursts at 10×
     * animator scale — `LaunchScreen.kt` was never on screen. Pixel-sampling the
     * wordmark bounds `uiautomator` itself reported returned pure background.
     * ~264 lines of faithfully ported gradient / rings / wordmark / dots were dead
     * code at runtime; users saw a bare white "O" on `#140526` and nothing else.
     *
     * This is the hand-over the system-splash contract expects. `androidx.core:
     * core-splashscreen`'s `setKeepOnScreenCondition` is exactly a pre-draw
     * listener that vetoes the first draw; that artifact is not in this machine's
     * offline Gradle cache and the build must run `--offline`, so the same
     * mechanism is implemented directly against the platform API. It behaves
     * identically and works on every API level, including the pre-31 path where
     * `Theme.OSHI.Splash` provides the window background.
     *
     * Sequence:
     *   1. onCreate vetoes drawing → the system splash stays up.
     *   2. Compose composes + measures + lays out (pre-draw runs AFTER layout, so
     *      the launch screen is fully laid out behind the splash).
     *   3. The launch screen's `LaunchedEffect` clears [keepSystemSplash] → the
     *      next draw pass paints the Compose launch screen, and the system splash
     *      dismisses onto an identical `#140526` background — no flash.
     *
     * [SPLASH_HOLD_TIMEOUT_MS] is a dead-man's switch: if composition ever stalls,
     * the veto is lifted anyway rather than freezing the user on the splash
     * forever. Vetoing the first draw indefinitely is the one way this can go
     * badly wrong, so it is bounded.
     */
    @Volatile
    private var keepSystemSplash: Boolean = true

    /**
     * __SPLASH_HANDOVER_2026_11_16__ True once nothing of the OS splash can be
     * drawn over the launch screen any more. Until then [LaunchScreen] shows
     * exactly what the splash shows (the logo on the flat splash ground) and
     * its intro clock does not run.
     *
     * Why (emulator-5554, API 36, screenrecord frame by frame): on API 31+,
     * with an exit listener set, the system does not simply drop its splash
     * when our first frame is drawn. It COPIES the splash into our window
     * (a `SplashScreenView` added to our DecorView) and only then calls the
     * listener, which removes it. Our first frame can reach the screen before
     * that copy does, so the screen went: in-app frame (logo + wordmark + glow,
     * crossfade already under way) → splash logo again for 2–3 frames (the
     * copy) → mascot. Holding the copy 1 s longer (probe build) held the
     * logo-only frames exactly 1 s longer. And the intro clock started at
     * composition, while the splash still covered us, so the logo → mascot
     * crossfade was mostly over by the time anything was visible.
     */
    private val launchHandedOver = mutableStateOf(false)

    private fun markLaunchHandedOver() {
        if (!launchHandedOver.value) launchHandedOver.value = true
    }

    /** The mirror's appearance, read once in onCreate (see ThemeBoot.kt). */
    private var bootTheme: String? = null
    private var bootOled: Boolean = false

    private fun holdSystemSplashUntilComposeDraws() {
        val content = findViewById<android.view.View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (keepSystemSplash) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        // The exit listener normally ends the hand-over. A launch
                        // with no OS splash never calls it: don't wait forever.
                        content.postDelayed({ markLaunchHandedOver() }, SPLASH_HANDOVER_FALLBACK_MS)
                    } else {
                        // API 26–30: no OS splash to copy. The starting window
                        // (Theme.OSHI.Launcher) goes as this frame lands.
                        markLaunchHandedOver()
                    }
                    return true
                }
            }
        )
        // Dead-man's switch — never hold the splash longer than this.
        content.postDelayed({
            if (keepSystemSplash) {
                Log.w(TAG, "⏱️ Splash hold timed out; releasing first draw")
                keepSystemSplash = false
                content.invalidate()
            }
        }, SPLASH_HOLD_TIMEOUT_MS)
    }

    /** Called from composition once the Compose launch screen is laid out. */
    private fun releaseSystemSplash() {
        if (!keepSystemSplash) return
        keepSystemSplash = false
        findViewById<android.view.View>(android.R.id.content)?.invalidate()
    }

    // `consumeWindowInsets` (the app-wide double-inset fix on the NavHost) is
    // still ExperimentalLayoutApi in the Compose BOM this module pins.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        holdSystemSplashUntilComposeDraws()
        // __OLED_SPLASH_2026_11_16__ OLED black: black window from the first frame
        // (no #121212 flash between splash and Compose) and a black system splash
        // from the next cold start. Read from the synchronous mirror — DataStore
        // has not answered yet. See ThemeBoot.kt.
        bootTheme = com.oshi.messenger.ui.theme.ThemeBoot.readTheme(this)
        bootOled = com.oshi.messenger.ui.theme.ThemeBoot.readOled(this)
        com.oshi.messenger.ui.theme.ThemeBoot.apply(this, bootTheme, bootOled)
        val splashGroundBlack = com.oshi.messenger.ui.theme.ThemeBoot.splashGroundIsBlack(
            android.os.Build.VERSION.SDK_INT,
            bootTheme,
            bootOled,
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        )
        // __O_BUBBLE_MASCOT_2026_11_16__ The Compose launch screen draws the
        // splash's logo on exactly the same pixels (LaunchMetrics.MASCOT_DP),
        // so the OS splash is removed at once instead of fading: its default
        // fade-out ran over the logo → mascot crossfade and dimmed both.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // __SPLASH_HANDOVER_2026_11_16__ The view handed over here is the
            // system's COPY of its splash, already drawn over our window; the
            // launch screen still shows the identical logo, so removing it
            // changes no pixel. Only now may the intro start.
            splashScreen.setOnExitAnimationListener { view ->
                view.remove()
                markLaunchHandedOver()
            }
        }

        // __LOCKED_MEANS_LOCKED_2026_10_28__ N12, reported twice. ONLY an incoming
        // call may appear over the lock screen.
        applyLockScreenVisibility(intent)

        // Request only notification permission at startup (all others are lazy)
        requestNotificationPermission()

        // __FORCED_UPDATE_ANDROID_2026_10_13__ Ask the server whether this build
        // is below the blocking floor, and hand the answer to Play.
        checkForcedUpdate()

        // Android 14+ requires explicit user grant for USE_FULL_SCREEN_INTENT
        // on non-default-dialer apps. Without it, our incoming-call notification
        // won't actually pop full-screen — it just shows a heads-up banner.
        checkFullScreenIntentPermission()

        // Start foreground sync service to keep process alive for background message polling.
        //
        // 🔴 Guarded: 4 crash reports / 3 users in the 30 days to 2026-10-16 were
        // ForegroundServiceStartNotAllowedException thrown right here, in onCreate.
        // An activity's onCreate does not guarantee the app counts as foreground —
        // a cold start from a notification trampoline or a restored task can run it
        // while the process is still background-ranked, and then this call throws.
        // Keeping the process warm is an optimisation; it is never worth taking the
        // launch down with it.
        // __BACKGROUND_PRESENCE_2026_11_10__ only when someone needs the process
        // kept alive after the app is closed (no Google push, or the user opted in);
        // otherwise this service is nothing but a permanent status-bar logo.
        com.oshi.messenger.service.BackgroundPresence.startSync(this)

        // Apply screenshot protection (matches iOS ScreenshotBlocker). ON unless
        // the user explicitly turned it off; a DEBUG build may opt out via
        // ScreenshotProtectionPolicy.DEBUG_ALLOW_CAPTURE_EXTRA (ignored in release).
        screenshotProtection.applyDebugCaptureOverride(intent)
        screenshotProtection.registerActivity(this)

        // (N10, Anatole 13/11) Opening the app no longer clears every message
        // notification. A banner goes when IT is tapped (`setAutoCancel(true)` on
        // every message builder) or when ITS conversation is on screen
        // (`ChatViewModel.onChatVisible`, `GroupManager.markGroupMessagesAsRead`).
        // Wiping the whole shade here threw away notices for conversations the
        // user had not opened yet — they opened the app for one chat and lost
        // the reminder for the other three. The panic wipe still clears the
        // shade (`SettingsViewModel.panicWipe`), which is where clearing it
        // wholesale is the point.

        // Handle notification tap from cold start
        handleNavIntent(intent)

        setContent {
            // 🆕 Read the user's appearance preference from SettingsViewModel and propagate
            // it to OSHITheme. The previous wiring ignored the preference and always followed
            // the OS theme — toggling Settings → Appearance had no effect.
            val settingsVm: com.oshi.messenger.ui.viewmodel.SettingsViewModel = hiltViewModel()
            val appearanceLoaded by settingsVm.appearanceLoaded.collectAsState()
            // __SPLASH_HANDOVER_2026_11_16__ Until DataStore answers, use the
            // synchronous mirror, not the defaults ("system", OLED off): the
            // launch screen's ground must not flip purple → black mid-intro.
            val liveTheme by settingsVm.theme.collectAsState()
            val themePref = if (appearanceLoaded) liveTheme else (bootTheme ?: liveTheme)
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val resolvedDark = com.oshi.messenger.ui.theme.ThemeSelection.resolveDark(themePref, systemDark)
            // __OLED_BLACK_2026_11_16__ "OLED black" modifies the dark scheme only;
            // OSHITheme ignores it while the resolved theme is light.
            val liveOled by settingsVm.oledBlack.collectAsState()
            val oledBlackPref = if (appearanceLoaded) liveOled else bootOled
            androidx.compose.runtime.LaunchedEffect(appearanceLoaded, themePref, oledBlackPref, systemDark) {
                if (appearanceLoaded) {
                    com.oshi.messenger.ui.theme.ThemeBoot.apply(this@MainActivity, themePref, oledBlackPref)
                }
            }
            // Bubble style preference: drives ChatScreen / GroupChatScreen
            // message-bubble corner geometry through LocalBubbleStyle.
            // Settings → Style de bulles writes "Modern" / "Classic" / "Minimal".
            val bubbleStylePref by settingsVm.bubbleStyle.collectAsState()
            val resolvedBubbleStyle = BubbleStyle.fromPref(bubbleStylePref)
            // Font Size setting (Settings → Font Size slider, 0.8–1.4) was DEAD:
            // persisted to DataStore but read by nothing, so the slider did nothing.
            // Apply it by scaling LocalDensity's fontScale, so ALL text (which uses
            // sp) scales on top of the OS font-scale, matching iOS's in-app text size.
            val fontScalePref by settingsVm.fontSize.collectAsState()
            // __KEYBOARD_PRIVACY_2026_10_01__ Incognito keyboard for every text field
            // of the app (one hook instead of ~100 fields): asks the keyboard not to
            // learn what is typed before OSHI encrypts it. See IncognitoTextInput.kt.
            com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
            OSHITheme(darkTheme = resolvedDark, oledBlack = oledBlackPref) {
                val baseDensity = androidx.compose.ui.platform.LocalDensity.current
                val scaledDensity = androidx.compose.ui.unit.Density(
                    density = baseDensity.density,
                    fontScale = baseDensity.fontScale * fontScalePref.coerceIn(0.8f, 1.4f),
                )
                CompositionLocalProvider(
                    LocalBubbleStyle provides resolvedBubbleStyle,
                    androidx.compose.ui.platform.LocalDensity provides scaledDensity,
                ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // __CALL_RATING_2026_09_22__ "How was the call quality?" after
                    // a finished call. Hosted once at the root: it renders in its
                    // own dialog window, so it survives whichever screen the call
                    // returned the person to.
                    com.oshi.messenger.ui.components.CallRatingHost()

                    // __SCREENSHOT_NOTICE_2026_11_18__ "Screenshots are blocked in OSHI…"
                    // pill after a blocked screenshot (API 34+, rate-limited). Own window.
                    com.oshi.messenger.ui.components.ScreenCaptureNoticeHost()

                    // __DEVICE_INTEGRITY_2026_10_01__ "OSHI detected signs that this
                    // phone may be rooted or modified" — once per app version, informs
                    // only (no key wiped, nothing blocked). Root-hosted for the same
                    // reason as CallRatingHost: its own dialog window.
                    com.oshi.messenger.security.DeviceIntegrityHost()

                    // ── Cold-launch hand-over (see holdSystemSplashUntilComposeDraws) ──
                    // Runs on the FIRST composition, before any branch below, so the
                    // release happens whichever screen we end up on.
                    //
                    // `minimumLaunchElapsed` is what makes the ported screen actually
                    // *visible* rather than a single frame: without it, a warm
                    // DataStore resolves `isLoading` before the first draw and the
                    // branded screen is replaced in the same frame it appears —
                    // which is the exact bug this fixes, just moved 16 ms later.
                    // LAUNCH_MIN_VISIBLE_MS is sized to the launch screen's own
                    // intro (iOS LaunchScreenView.swift:168-179: a 0.3 s fade plus
                    // 0.15 s-staggered dots), and iOS pays a comparable cost at every
                    // cold launch for its LaunchMotionView
                    // (SecureWeb3MessengerApp.swift:91-98).
                    var minimumLaunchElapsed by remember { mutableStateOf(false) }
                    val handedOver by launchHandedOver
                    LaunchedEffect(Unit) {
                        releaseSystemSplash()
                        // Counted from the hand-over, not from composition: the
                        // intro (INTRO_MS) only starts then, and must not be cut.
                        snapshotFlow { launchHandedOver.value }.first { it }
                        kotlinx.coroutines.delay(LAUNCH_MIN_VISIBLE_MS)
                        minimumLaunchElapsed = true
                    }

                    // Auto-lock overlay: when the activity returns from background past timeout, cover the UI
                    // until BiometricPrompt succeeds. We render the launch screen as a safe opaque cover.
                    val locked by autoLockTriggered
                    if (locked) {
                        // A still cover: no mascot intro behind the biometric prompt.
                        OSHILaunchScreen(playIntro = false)
                        return@Surface
                    }

                    val navController = rememberNavController()
                    val viewModel: MainViewModel = hiltViewModel()
                    val isLoading by viewModel.isLoading.collectAsState()
                    val isOnboarded by viewModel.isOnboarded.collectAsState()
                    val unreadCount by viewModel.totalUnreadCount.collectAsState()
                    val callState by viewModel.incomingCallState.collectAsState()
                    val incomingCall by viewModel.incomingCall.collectAsState()

                    // Show the branded launch screen while DataStore loads AND for at
                    // least LAUNCH_MIN_VISIBLE_MS, so it is genuinely seen rather
                    // than composed-and-discarded inside one frame.
                    if (isLoading || !minimumLaunchElapsed) {
                        com.oshi.messenger.ui.screens.LaunchScreen(
                            handedOver = handedOver,
                            splashGroundBlack = splashGroundBlack,
                        )
                        return@Surface
                    }

                    // Navigate to conversation from notification tap.
                    // Watch BOTH the route AND loading state — if route arrives before
                    // loading finishes (cold start), wait until ready then navigate.
                    val pendingRoute = pendingNavRoute
                    LaunchedEffect(pendingRoute, isOnboarded, isLoading) {
                        if (pendingRoute != null && isOnboarded && !isLoading) {
                            // Small delay to ensure NavHost is fully composed
                            kotlinx.coroutines.delay(300)
                            pendingNavRoute = null
                            navController.navigate(pendingRoute) {
                                launchSingleTop = true
                            }
                        }
                    }

                    // Navigate to call screen on incoming call
                    LaunchedEffect(callState, incomingCall) {
                        val call = incomingCall
                        if (callState == CallState.RINGING && call != null && !call.isOutgoing) {
                            val encodedKey = encodeKeyForNav(call.peerPublicKey)
                            val isVideo = call.isVideoCall
                            val route = "incomingCall/$encodedKey/$isVideo"
                            // Only navigate if not already on a call screen
                            val current = navController.currentDestination?.route
                            if (current?.startsWith("call/") != true && current?.startsWith("incomingCall/") != true) {
                                navController.navigate(route)
                            }
                        }
                    }

                    // Contact identity handed to the Map tab when a location
                    // bubble is tapped: who shared it, their photo, and whether
                    // the share is still live.
                    //
                    // This used to be FOUR `mutableStateOf` values that the map
                    // destination read into locals and then, inside its own
                    // `LaunchedEffect`, set back to null. Because the read
                    // happened in composition, nulling them invalidated the very
                    // scope that had read them: the map recomposed one frame
                    // later with `contactName = null`, `contactPicture = null`,
                    // and `MapScreen`'s `remember(contactName, contactPicture)`
                    // avatar bitmap became null — so the contact marker was
                    // added and then immediately removed. That is the second
                    // complaint, exactly: "on share position we don't see the
                    // profile picture of the contact".
                    //
                    // A plain holder is not Compose state, so taking the payload
                    // out of it cannot invalidate anything. The destination
                    // takes it ONCE per back-stack entry via `remember(entry)`
                    // and keeps it for the whole visit.
                    val sharedLocationHandoff = remember {
                        java.util.concurrent.atomic.AtomicReference<SharedLocationHandoff?>(null)
                    }

                    // "Open locations in" = OSHI Maps, from a surface that has no
                    // navController of its own (group bubble, check-in, address or
                    // map link in a message): open the Map destination focused on
                    // the place, exactly like the 1:1 bubble does. See MapOpener.
                    LaunchedEffect(navController) {
                        com.oshi.messenger.ui.components.MapOpener.oshiRequests.collect { req ->
                            val encodedName = android.net.Uri.encode(req.label ?: "")
                            navController.navigate("map?lat=${req.lat}&lon=${req.lon}&name=$encodedName")
                        }
                    }

                    // Read ONCE per composition: honoured by every nav transition
                    // below (see `isReduceMotionOn`). Read here rather than inside
                    // the transition lambdas because those are plain lambdas, not
                    // @Composable, so they cannot reach LocalContext themselves.
                    val navMotionContext = androidx.compose.ui.platform.LocalContext.current
                    val reduceMotion = remember(navMotionContext) {
                        isReduceMotionOn(navMotionContext)
                    }

                    // Check if we're on a main tab screen (show bottom bar)
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route
                    val showBottomBar = shouldShowBottomBar(currentRoute)

                    // Track video call route for PiP support
                    val activity = this@MainActivity
                    LaunchedEffect(currentRoute) {
                        val isVideoRoute = currentRoute?.let { route ->
                            (route.startsWith("call/") && route.endsWith("/true")) ||
                            (route.startsWith("incomingCall/") && route.endsWith("/true"))
                        } ?: false
                        activity.isOnVideoCall = isVideoRoute
                    }

                    // 🆕 Group invite prompt (from oshi:// or universal link)
                    val invite = pendingGroupInvite
                    val invalidInvite = pendingInvalidInvite
                    if (invite != null && isOnboarded && !isLoading) {
                        GroupInviteJoinDialog(
                            invite = invite,
                            onJoin = {
                                viewModel.joinGroupFromInvite(invite.gid, invite.inviter, invite.name)
                                pendingGroupInvite = null
                                navController.navigate("groupChat/${invite.gid}") { launchSingleTop = true }
                            },
                            onDismiss = { pendingGroupInvite = null }
                        )
                    }
                    // 🆕 Add-contact prompt (from …/add?key=… deep link).
                    // Mirrors iOS AddContactFromLinkView (SharedContentHandler.swift:593).
                    val addKey = pendingAddContact
                    if (addKey != null && isOnboarded && !isLoading) {
                        val myKey by viewModel.publicKey.collectAsState()
                        AddContactFromLinkDialog(
                            publicKey = addKey,
                            myPublicKey = myKey,
                            // (N1) prefill with the name the link offered, if any —
                            // still a plain editable field, not a locked-in value.
                            initialAlias = pendingAddContactName.orEmpty(),
                            onDismiss = { pendingAddContact = null; pendingAddContactName = null },
                            onAdd = { alias ->
                                val repo = messageRepository
                                lifecycleScope.launch {
                                    runCatching {
                                        if (repo.getContact(addKey).first() == null) {
                                            repo.addContact(addKey, alias.ifBlank { addKey.take(8) + "…" })
                                        } else {
                                            if (alias.isNotBlank()) repo.updateContactAlias(addKey, alias)
                                            // (N18) An existing row with no messages (a group
                                            // member, a deleted thread) is listed once the
                                            // user explicitly adds it from a link.
                                            repo.saveContactIfMissing(addKey)
                                        }
                                    }
                                }
                                pendingAddContact = null
                                pendingAddContactName = null
                                navController.navigate("chat/${encodeKeyForNav(addKey)}") {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    // STEGO-v1 reveal on inbound share (see handleShareIntent).
                    val stegoUri = pendingStegoCheck
                    var stegoFound by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf<com.oshi.messenger.service.stego.PhotoDisguise.Outcome.Found?>(null)
                    }
                    if (stegoUri != null && isOnboarded && !isLoading) {
                        val disguiseVm: com.oshi.messenger.ui.screens.PhotoDisguiseViewModel = hiltViewModel()
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        androidx.compose.runtime.LaunchedEffect(stegoUri) {
                            disguiseVm.revealIfForMe(ctx, stegoUri) { found -> pendingShare = null; stegoFound = found }
                            pendingStegoCheck = null
                        }
                    }
                    stegoFound?.let { found ->
                        com.oshi.messenger.ui.screens.RevealResultDialog(
                            outcome = found, checking = false,
                            onOpenChat = { key ->
                                navController.navigate("chat/${encodeKeyForNav(key)}") { launchSingleTop = true }
                            },
                            onDismiss = { stegoFound = null },
                        )
                    }

                    // 🆕 Inbound share target (Android share sheet → OSHI).
                    // Mirrors iOS SharePickerView (SharedContentHandler.swift:280).
                    val share = pendingShare
                    if (share != null && isOnboarded && !isLoading) {
                        ShareTargetSheet(
                            shared = share,
                            onDismiss = { pendingShare = null },
                            onSentToChat = { publicKey ->
                                pendingShare = null
                                if (publicKey != null) {
                                    navController.navigate("chat/${encodeKeyForNav(publicKey)}") {
                                        launchSingleTop = true
                                    }
                                }
                            }
                        )
                    }

                    if (invalidInvite) {
                        OshiAlertDialog(
                            onDismissRequest = { pendingInvalidInvite = false },
                            icon = { Icon(Icons.Default.Warning, null, tint = androidx.compose.ui.graphics.Color(0xFFFF9800)) },
                            title = { Text(stringResource(R.string.group_invite_invalid_title)) },
                            text = { Text(stringResource(R.string.group_invite_invalid_message)) },
                            confirmButton = {
                                TextButton(onClick = { pendingInvalidInvite = false }) {
                                    Text(stringResource(R.string.ok))
                                }
                            }
                        )
                    }

                    // Backup prompt after identity creation
                    val needsBackupPrompt by viewModel.needsBackupPrompt.collectAsState()
                    if (needsBackupPrompt) {
                        OshiAlertDialog(
                            onDismissRequest = { viewModel.dismissBackupPrompt() },
                            icon = {
                                Icon(
                                    Icons.Default.Key,
                                    contentDescription = null,
                                    tint = com.oshi.messenger.ui.theme.OSHIPurple,
                                    modifier = Modifier.size(32.dp)
                                )
                            },
                            title = {
                                Text(
                                    text = stringResource(R.string.backup_prompt_title),
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            text = {
                                Text(stringResource(R.string.backup_prompt_message))
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        viewModel.dismissBackupPrompt()
                                        navController.navigate("keyBackup")
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = com.oshi.messenger.ui.theme.OSHIPurple
                                    )
                                ) {
                                    Text(stringResource(R.string.backup_prompt_now))
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { viewModel.dismissBackupPrompt() }
                                ) {
                                    Text(stringResource(R.string.backup_prompt_later))
                                }
                            }
                        )
                    }

                    // ── Adaptive shell ────────────────────────────────────
                    // Below 600 dp wide the five destinations live in a bottom
                    // bar, exactly as before. At 600 dp and up — a tablet, an
                    // unfolded foldable, a free-form window, or a phone turned
                    // landscape — they move to a left rail.
                    //
                    // This is not cosmetic: from targetSdk 36 Android ignores
                    // `android:screenOrientation` on displays ≥ 600 dp, so the
                    // landscape layout is no longer opt-in.
                    val windowSize = rememberWindowSize()
                    val showRail = isOnboarded && showBottomBar && windowSize.useNavigationRail
                    val showBottomBarNow = isOnboarded && showBottomBar && !windowSize.useNavigationRail
                    val onTabSelected: (String) -> Unit = { route ->
                        navController.selectTab(route)
                    }

                    Row(modifier = Modifier.fillMaxSize()) {
                    if (showRail) {
                        OSHINavigationRail(
                            currentRoute = currentRoute,
                            unreadCount = unreadCount,
                            onNavigate = onTabSelected
                        )
                    }
                    Scaffold(
                        // __STATUS_BAR_SAME_COLOUR_2026_11_13__ (Anatole N7) From
                        // Android 15 the status bar is transparent and what shows
                        // through it is THIS container (the NavHost below is padded
                        // out of that strip). Its default is `background`, one
                        // shade off every top bar (`surface`) — the visible band
                        // above the title in both themes.
                        containerColor = MaterialTheme.colorScheme.surface,
                        // `enableEdgeToEdge()` puts the window in SHORT_EDGES
                        // cutout mode, so in landscape the notch eats into the
                        // content area — systemBars alone does not cover it.
                        // When the rail is up it has already taken the start
                        // side (and told nobody, being a sibling rather than a
                        // child), so the Scaffold must not claim it twice.
                        // `ime` is deliberately excluded on both paths: chat
                        // and AI screens do their own `imePadding()`, and
                        // hoisting it to the Scaffold would move the whole
                        // screen instead of just the composer.
                        contentWindowInsets = WindowInsets.systemBars
                            .union(WindowInsets.displayCutout)
                            .only(
                                if (showRail) {
                                    WindowInsetsSides.Top + WindowInsetsSides.End +
                                        WindowInsetsSides.Bottom
                                } else {
                                    WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical
                                }
                            ),
                        bottomBar = {
                            if (showBottomBarNow) {
                                OSHIBottomNavigation(
                                    currentRoute = currentRoute,
                                    unreadCount = unreadCount,
                                    onNavigate = onTabSelected
                                )
                            }
                        }
                    ) { padding ->
                        // Only apply bottom padding when the bottom bar is visible;
                        // chat/call screens fill the full screen without the gap.
                        //
                        // Start/end are carried through too. They used to be
                        // dropped, which was harmless while the Scaffold only
                        // claimed `systemBars` and the app only ever ran
                        // portrait: there is no horizontal system bar there.
                        // Now that the app rotates and the insets include the
                        // display cutout, dropping them would put the first
                        // character of every message under the notch.
                        val layoutDirection = LocalLayoutDirection.current
                        val hostPadding = PaddingValues(
                            start = padding.calculateStartPadding(layoutDirection),
                            end = padding.calculateEndPadding(layoutDirection),
                            top = padding.calculateTopPadding(),
                            bottom = if (showBottomBarNow) padding.calculateBottomPadding() else 0.dp
                        )
                        // Cap the reading width on large windows. A 1280 dp-wide
                        // conversation list or settings form is a line length no
                        // one can track back to the next row; past the cap the
                        // pane is centred and the rest becomes gutter. Map,
                        // navigation and call surfaces opt out — they want every
                        // pixel. On a compact window `contentMaxWidth` is
                        // `Dp.Unspecified`, so this is a no-op on phones.
                        val paneMaxWidth = if (isFullBleedRoute(currentRoute)) {
                            androidx.compose.ui.unit.Dp.Unspecified
                        } else {
                            windowSize.contentMaxWidth
                        }
                        val paneModifier = if (paneMaxWidth == androidx.compose.ui.unit.Dp.Unspecified) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier.widthIn(max = paneMaxWidth).fillMaxSize()
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(hostPadding)
                                .consumeWindowInsets(hostPadding),
                            contentAlignment = Alignment.TopCenter
                        ) {
                        NavHost(
                            navController = navController,
                            startDestination = if (isOnboarded) ROUTE_MESSAGES else "onboarding",
                            // 🔧 APP-WIDE DOUBLE STATUS-BAR INSET (fixed here, once).
                            //
                            // This Scaffold offsets the NavHost by the status-bar
                            // height, and then all 22 inner Scaffolds offset their
                            // TopAppBar by the SAME inset again, because a plain
                            // `Modifier.padding` moves pixels without telling anyone
                            // the inset has been dealt with. Every screen carried a
                            // dead band above its title, ~190 px on Chats where the
                            // TabRow sits between the two paddings (device capture
                            // 2026-08-13, `w01-current.png`).
                            //
                            // `consumeWindowInsets` publishes what we already applied,
                            // so the descendants' `windowInsetsPadding` (which is what
                            // TopAppBar uses internally — verified in material3 1.1.2
                            // `AppBarKt$SingleRowTopAppBar$2`) resolves to zero instead
                            // of padding a second time. One fix, all 22 screens; the
                            // alternative was editing every screen and re-introducing
                            // the band with the next one added.
                            //
                            // Both modifiers now live on the Box that wraps this
                            // NavHost (it also centres the pane on wide windows);
                            // an ancestor works exactly the same for the
                            // descendants that read the inset.
                            modifier = paneModifier,
                            // Graph-wide default. Replaces navigation-compose's own
                            // 700 ms cross-fade; the pushed detail screens below
                            // override it with a horizontal slide.
                            enterTransition = navSiblingEnter(reduceMotion),
                            exitTransition = navSiblingExit(reduceMotion),
                            popEnterTransition = navSiblingEnter(reduceMotion),
                            popExitTransition = navSiblingExit(reduceMotion)
                        ) {
                            // Onboarding
                            composable("onboarding") {
                                OnboardingScreen(
                                    onComplete = {
                                        navController.navigate("messages") {
                                            popUpTo("onboarding") { inclusive = true }
                                        }
                                    }
                                )
                            }

                            // ═══════════════════════════════════════════════════════════
                            // MAIN TABS (Bottom Navigation)
                            // ═══════════════════════════════════════════════════════════

                            // Tab 1: Chats (Messages + Groups merged)
                            composable("messages") {
                                ChatsTabScreen(
                                    onConversationClick = { publicKey ->
                                        navController.navigate("chat/${encodeKeyForNav(publicKey)}")
                                    },
                                    onGroupClick = { groupId ->
                                        navController.navigate("groupChat/$groupId")
                                    },
                                    onNewMessage = {
                                        navController.navigate("newMessage")
                                    },
                                    onMailClick = {
                                        navController.navigate("mail")
                                    },
                                    // (N7) this is the top-bar button, not the bottom
                                    // nav's own Settings tab — it now carries the
                                    // user's own avatar and opens straight to the
                                    // Account category, rather than duplicating the
                                    // bottom nav's generic Settings entry point.
                                    onSettingsClick = {
                                        navController.navigate("settings?account=true")
                                    },
                                    onQRCodeClick = {
                                        navController.navigate("qrCode")
                                    },
                                    // __ANTENNA_IS_A_DOOR_2026_10_27__
                                    onNearbyClick = {
                                        navController.navigate("peers")
                                    },
                                    onDiscoverGroups = {
                                        navController.navigate("discoverGroups")
                                    },
                                    onNavigateToChat = { publicKey ->
                                        navController.navigate("chat/${encodeKeyForNav(publicKey)}")
                                    },
                                    // First-run empty state's secondary CTA — opens
                                    // New Message straight on the camera, matching
                                    // iOS's "Scan a friend's code" (MessagesListView.swift:1733).
                                    onScanCode = {
                                        navController.navigate("$ROUTE_NEW_MESSAGE?scan=true")
                                    },
                                    // __FIRST_CONTACT_REMOTE_2026_10_10__ The path
                                    // that works when the other person is somewhere
                                    // else — which the first-run screen had none of.
                                    // The system chooser on purpose: it lists the
                                    // apps this person already uses (Messages,
                                    // WhatsApp, Mail) instead of asking them to
                                    // learn ours.
                                    onInviteByLink = {
                                        shareMyInviteLink(navMotionContext, viewModel.publicKey.value, viewModel.displayName.value)
                                    },
                                    // __NEW_IS_A_MENU_2026_11_13__ (Anatole N4) The same
                                    // route and the same "blocked by them" refusal as the
                                    // chat's own call buttons (ChatScreen `placeCall`).
                                    onCallContact = { key, isVideo ->
                                        if (com.oshi.messenger.service.BlockedByPeerStore.get(navMotionContext).isBlockedBy(key)) {
                                            android.widget.Toast.makeText(
                                                navMotionContext,
                                                navMotionContext.getString(R.string.call_error_unavailable),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            navController.navigate("call/${encodeKeyForNav(key)}/$isVideo")
                                        }
                                    }
                                )
                            }

                            // Groups route (kept for deep links / navigation from other screens)
                            composable("groups") {
                                GroupsScreen(
                                    onGroupClick = { groupId ->
                                        navController.navigate("groupChat/$groupId")
                                    },
                                    onDiscoverGroups = {
                                        navController.navigate("discoverGroups")
                                    },
                                    onBack = { navController.popBackStack() },
                                    onNavigateToChat = { publicKey ->
                                        navController.navigate("chat/${encodeKeyForNav(publicKey)}")
                                    }
                                )
                            }

                            // Tab 3: New Message.
                            // `?scan=true` opens straight on the camera — used by the
                            // conversations empty state's "Scan a friend's code" CTA.
                            // The bottom bar still navigates to bare "newMessage", which
                            // matches this route because the argument is optional.
                            composable(
                                route = "$ROUTE_NEW_MESSAGE?scan={scan}",
                                arguments = listOf(
                                    navArgument("scan") { type = NavType.StringType; defaultValue = "false" }
                                )
                            ) { backStackEntry ->
                                NewMessageScreen(
                                    onBack = { navController.navigate(ROUTE_MESSAGES) },
                                    onContactSelected = { publicKey ->
                                        navController.navigate("chat/${encodeKeyForNav(publicKey)}") {
                                            popUpTo(ROUTE_MESSAGES)
                                        }
                                    },
                                    onSent = { navController.navigate(ROUTE_MESSAGES) },
                                    autoStartScan = backStackEntry.arguments?.getString("scan") == "true"
                                )
                            }

                            // Tab 4: Nearby Peers — requests mesh permissions lazily
                            composable("peers") {
                                MeshPermissionGate {
                                    PeersScreen(
                                        // `peers` is NOT a bottom-nav tab (see the nav items at
                                        // OSHIBottomNavigation) — it is PUSHED from Settings, so
                                        // its back arrow must actually pop. It used to be `{ }`,
                                        // i.e. a visible control that did nothing.
                                        onNavigateBack = { navController.popBackStack() }
                                    )
                                }
                            }

                            // Tab 5: Portal (Bots, Channels, Apps) — matches iOS PortalView
                            composable("botPortal") {
                                BotPortalScreen(
                                    onNavigateToBotDetail = { botId -> navController.navigate("botDetail/$botId") },
                                    onNavigateToGroupChat = { groupId -> navController.navigate("groupChat/$groupId") },
                                    onNavigateToCreateChannel = { navController.navigate("groups") },
                                    onNavigateToDiscoverChannels = { navController.navigate("discoverGroups") },
                                    onNavigateToChat = { publicKey ->
                                        navController.navigate("chat/${encodeKeyForNav(publicKey)}")
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            // Tab 6: Map (Offline Maps)
                            composable(
                                route = "map?lat={lat}&lon={lon}&name={name}",
                                arguments = listOf(
                                    navArgument("lat") { type = NavType.StringType; defaultValue = "" },
                                    navArgument("lon") { type = NavType.StringType; defaultValue = "" },
                                    navArgument("name") { type = NavType.StringType; defaultValue = "" }
                                )
                            ) { backStackEntry ->
                                val mapVM: MapViewModel = hiltViewModel()
                                // Was the literal English string "Shared
                                // Location", in an app that ships 34 languages.
                                val sharedLocationFallbackName =
                                    stringResource(R.string.map_marker_shared_location)

                                // Taken ONCE per back-stack entry, from a plain
                                // holder — see `sharedLocationHandoff` above for
                                // why this must not be Compose state.
                                val handoff = remember(backStackEntry) {
                                    sharedLocationHandoff.getAndSet(null)
                                }

                                // What this entry should do with its lat/lon
                                // arguments — the ONE place every way into the
                                // Map tab converges, and therefore the only
                                // place that can retire a stale focus for every
                                // exit path at once. Pure decision, unit-tested
                                // in SharedLocationFocusTest.
                                val focus = remember(backStackEntry) {
                                    SharedLocationFocus.decide(
                                        latArg = backStackEntry.arguments?.getString("lat"),
                                        lonArg = backStackEntry.arguments?.getString("lon"),
                                        nameArg = backStackEntry.arguments?.getString("name"),
                                        alreadyConsumed = backStackEntry.savedStateHandle
                                            .get<Boolean>(SharedLocationFocus.CONSUMED_KEY) == true
                                    )
                                }
                                val shared = focus as? MapEntryFocus.FocusShared

                                LaunchedEffect(backStackEntry, focus) {
                                    when (focus) {
                                        is MapEntryFocus.FocusShared -> {
                                            mapVM.focusSharedLocation(
                                                MapPOI(
                                                    id = SharedLocationFocus.poiId(focus.lat, focus.lon),
                                                    name = focus.label ?: sharedLocationFallbackName,
                                                    category = POICategory.OTHER,
                                                    lat = focus.lat,
                                                    lon = focus.lon,
                                                    address = focus.label ?: "",
                                                    regionId = "chat"
                                                )
                                            )
                                            // Latch the entry. `SavedStateHandle`
                                            // is saved and restored WITH the
                                            // entry, so the restored entry that
                                            // still carries lat/lon also carries
                                            // the flag that disarms them.
                                            backStackEntry.savedStateHandle[SharedLocationFocus.CONSUMED_KEY] = true
                                        }
                                        MapEntryFocus.ClearStaleFocus -> mapVM.clearSharedLocationFocus()
                                    }
                                }

                                MapScreen(
                                    // Search is now an in-place ModalBottomSheet
                                    // — no navigation route needed (matches iOS).
                                    onNavigateToRegions = {
                                        navController.navigate("mapRegions")
                                    },
                                    onNavigateToNavigation = {
                                        navController.navigate("mapNavigation")
                                    },
                                    onNavigateToItinerary = {
                                        navController.navigate("itinerary")
                                    },
                                    onNavigateToAIChat = {
                                        navController.navigate("aiChat")
                                    },
                                    // __MESSAGE_THE_NODE_FROM_THE_MAP_2026_11_07__
                                    onOpenChat = { key ->
                                        navController.navigate("chat/${encodeKeyForNav(key)}")
                                    },
                                    viewModel = mapVM,
                                    contactName = handoff?.contactName,
                                    contactProfilePicture = handoff?.contactPicture,
                                    contactLat = shared?.lat,
                                    contactLon = shared?.lon,
                                    isLiveLocation = handoff?.isLive == true,
                                    liveSessionId = handoff?.sessionId
                                )
                            }

                            // Tab 7: Settings
                            // The private mailbox. Without this route MailScreen
                            // compiles and ships but nothing reaches it, which is the
                            // same as it not existing.
                            // `?messageId=…` deep-links straight onto one message from a
                            // mail-notification tap. The bottom-bar / top-bar entry points
                            // still navigate to bare "mail", which matches because the
                            // argument is optional (same shape as newMessage?scan=…).
                            composable(
                                route = "mail?messageId={messageId}",
                                arguments = listOf(
                                    navArgument("messageId") {
                                        type = NavType.StringType; nullable = true; defaultValue = null
                                    }
                                )
                            ) { entry ->
                                com.oshi.messenger.ui.screens.MailScreen(
                                    // __MAILBOX_HAD_NO_WAY_OUT_2026_11_06__ The bottom
                                    // bar is hidden on this route and the top bar had no
                                    // navigation icon, so the system back gesture was the
                                    // mailbox's only exit.
                                    onBack = { navController.popBackStack() },
                                    openMessageId = entry.arguments?.getString("messageId")
                                )
                            }

                            composable(
                                route = "settings?account={account}",
                                arguments = listOf(
                                    navArgument("account") { type = NavType.BoolType; defaultValue = false }
                                )
                            ) { entry ->
                                SettingsScreen(
                                    // __SETTINGS_FREEZE_2026_11_09__ The activity's MainViewModel, NOT
                                    // the default hiltViewModel() — inside a NavHost destination that
                                    // default is scoped to the back-stack entry, so every open built a
                                    // SECOND MainViewModel whose init re-ran, on the main thread,
                                    // EncryptedSharedPreferences/MasterKey (StrongBox) creation,
                                    // restoreKeys() and initializeServices() (VPS/mesh/call init,
                                    // cross-platform mesh reconfigure, FCM registration) — the
                                    // "Settings freezes for a second" report.
                                    mainViewModel = viewModel,
                                    // (N7) the conversations screen's own-avatar button
                                    // navigates to "settings?account=true" to land
                                    // directly on the Account category.
                                    openAccountOnStart = entry.arguments?.getBoolean("account") ?: false,
                                    onBack = { navController.popBackStack() },
                                    onQRCodeClick = { navController.navigate("qrCode") },
                                    onWebSessionClick = { navController.navigate("webSession") },
                                    onBlockedContactsClick = { navController.navigate("blockedContacts") },
                                    onReportedItemsClick = { navController.navigate("reportedItems") },
                                    onGroupsClick = { navController.navigate("groups") },
                                    onKeyBackupClick = { navController.navigate("keyBackup") },
                                    onScheduledCallsClick = { navController.navigate("scheduledCalls") },
                                    onScheduledMessagesClick = { navController.navigate("scheduledMessages") },
                                    onNearbyClick = { navController.navigate("peers") },
                                    onPortalClick = { navController.navigate("botPortal") },
                                    onProfileLinksClick = { navController.navigate("profileLinks") },
                                    onLoRaSettingsClick = { navController.navigate("loraSettings") },
                                    onLinkedDevicesClick = { navController.navigate("linkedDevices") },
                                    onLogout = {
                                        navController.navigate("onboarding") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                )
                            }

                            // Profile Links Editor
                            composable("profileLinks") {
                                ProfileLinksEditorScreen(
                                    onBack = { navController.popBackStack() },
                                    onSaved = { viewModel.syncProfileLinksNow() }
                                )
                            }

                            // 📡 Long-range radio (Meshtastic LoRa).
                            //
                            // Both screens self-install the radio through
                            // `LoRaViewModel.Factory`, so there is no start-up
                            // wiring here — opening either one is what builds the
                            // adapter over `MeshtasticManager`. Without these two
                            // routes the whole LoRa UI was unreachable code:
                            // finished, tested, and with nothing able to open it.
                            composable("loraSettings") {
                                // __LORA_NODE_KM_2026_09_23__ Without this the screen never had a
                                // position, so every node fell back to the "~" RSSI band and no
                                // real km was ever shown. iOS uses the app's current fix
                                // (`MeshtasticSettingsView.swift:281-285`); ask for one fix on open
                                // when permission is already granted — never prompt from here.
                                val here by locationSharingManager.currentLocation.collectAsState()
                                LaunchedEffect(Unit) {
                                    if (here == null && locationSharingManager.hasLocationPermission()) {
                                        locationSharingManager.getCurrentLocation { /* published via currentLocation */ }
                                    }
                                }
                                LoRaSettingsScreen(
                                    onBack = { navController.popBackStack() },
                                    onOpenNodeSetup = { navController.navigate("loraNodeSetup") },
                                    deviceLocation = here?.let { it.latitude to it.longitude }
                                )
                            }
                            composable("loraNodeSetup") {
                                LoRaNodeSetupScreen(
                                    onBack = { navController.popBackStack() },
                                    // __LORA_CHANNEL_IS_A_ROOM_2026_11_05__ A channel key
                                    // (`lorachan!manif` / `lorachan#3`) routes exactly like
                                    // a contact key: it is the conversation id the receive
                                    // path files broadcasts under, so the existing chat
                                    // route opens the right room with no new destination.
                                    onOpenChannel = { channelKey ->
                                        navController.navigate("chat/${encodeKeyForNav(channelKey)}")
                                    },
                                )
                            }

                            // ═══════════════════════════════════════════════════════════
                            // DETAIL SCREENS (No Bottom Navigation)
                            // ═══════════════════════════════════════════════════════════

                            // Map Search route removed — search is now a
                            // ModalBottomSheet inside MapScreen so the map
                            // stays visible behind it (iOS parity).

                            // Map Region Downloads
                            composable("mapRegions") {
                                MapRegionDownloadScreen(
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            // Map Navigation (turn-by-turn) — shares MapViewModel with map tab
                            composable("mapNavigation") {
                                val mapEntry = remember(navController) {
                                    navController.getBackStackEntry("map?lat={lat}&lon={lon}&name={name}")
                                }
                                val sharedMapVM: MapViewModel = hiltViewModel(mapEntry)
                                MapNavigationScreen(
                                    onBack = { navController.popBackStack() },
                                    viewModel = sharedMapVM
                                )
                            }

                            // Itinerary Planner — shares MapViewModel with map tab
                            composable("itinerary") {
                                val mapEntry = remember(navController) {
                                    navController.getBackStackEntry("map?lat={lat}&lon={lon}&name={name}")
                                }
                                val sharedMapVM: MapViewModel = hiltViewModel(mapEntry)
                                ItineraryScreen(
                                    onBack = { navController.popBackStack() },
                                    onStartNavigation = {
                                        navController.navigate("mapNavigation")
                                    },
                                    viewModel = sharedMapVM
                                )
                            }

                            // AI Chat Assistant (full conversational AI)
                            composable("aiChat") {
                                AIChatScreen(
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            // Chat Detail — pushed, so it slides in from the
                            // trailing edge and back out the same way.
                            composable(
                                route = "chat/{publicKey}",
                                enterTransition = navPushEnter(reduceMotion),
                                exitTransition = navPushExit(reduceMotion),
                                popEnterTransition = navPopEnter(reduceMotion),
                                popExitTransition = navPopExit(reduceMotion)
                            ) { backStackEntry ->
                                val publicKey = decodeKeyFromNav(backStackEntry.arguments?.getString("publicKey") ?: "")
                                ChatScreen(
                                    publicKey = publicKey,
                                    onBack = { navController.popBackStack() },
                                    onCallClick = { isVideo ->
                                        navController.navigate("call/${encodeKeyForNav(publicKey)}/$isVideo")
                                    },
                                    onNavigateToMap = { navController.navigate("map") },
                                    onOpenLocationInMap = { lat, lon, name, cName, cPicture, isLive, sessionId ->
                                        val encodedName = android.net.Uri.encode(name ?: "")
                                        // Hand the contact identity over in a
                                        // plain holder, not Compose state: the
                                        // destination takes it once and keeps
                                        // it, instead of reading it and then
                                        // nulling it out from under itself.
                                        sharedLocationHandoff.set(
                                            SharedLocationHandoff(
                                                contactName = cName,
                                                contactPicture = cPicture,
                                                isLive = isLive,
                                                sessionId = sessionId
                                            )
                                        )
                                        navController.navigate("map?lat=$lat&lon=$lon&name=$encodedName")
                                    },
                                    onNavigateToAIChat = { navController.navigate("aiChat") },
                                    onNavigateToChat = { targetPublicKey ->
                                        // Tapped an in-app contact card — open / create that chat.
                                        // Replace current chat in the back stack to avoid stack growth
                                        // when chaining multiple cards.
                                        navController.navigate("chat/${encodeKeyForNav(targetPublicKey)}") {
                                            popUpTo("chat/${encodeKeyForNav(publicKey)}") { inclusive = true }
                                        }
                                    },
                                    // 🔐 ChatScreen hands back the RAW peer key; escaping
                                    // it for the graph is this side's job, exactly as it
                                    // already is for `onNavigateToChat` above.
                                    onOpenSafetyNumber = { theirPublicKey ->
                                        navController.navigate(
                                            "safetyNumber/${encodeKeyForNav(theirPublicKey)}"
                                        ) { launchSingleTop = true }
                                    }
                                )
                            }

                            // 🔐 Safety number / key verification for one peer.
                            //
                            // `SafetyNumberManager` shipped with no way in — this is
                            // the route that reaches it, pushed from the chat's
                            // encryption chip and its overflow menu.
                            //
                            // ⚠️ ENCODING. The argument is a public key, and this
                            // app's keys are base64: they contain `+`, `/` and `=`,
                            // every one of which changes the meaning of a URL path
                            // segment (a `/` alone splits the segment in two and the
                            // route stops matching at all). So the SAME convention as
                            // `chat/{publicKey}` applies, and for the same reason —
                            // callers pass `encodeKeyForNav(rawKey)`, this side calls
                            // `decodeKeyFromNav` and hands the screen the raw key
                            // back. `encodeKeyForNav` is URL-safe base64 of the key's
                            // UTF-8 bytes, unpadded, so what lands in the path is
                            // `[A-Za-z0-9_-]` only.
                            //
                            // Percent-escaping (`Uri.encode`) was the alternative and
                            // is the wrong one here: navigation-compose decodes the
                            // path segment before matching, so a `%2F` still becomes
                            // a `/` and still splits the route.
                            composable(
                                route = "safetyNumber/{theirPublicKey}",
                                // Declared explicitly (same as the `newMessage`
                                // route above) so the type is pinned rather than
                                // inferred. No `defaultValue`: unlike `?scan={scan}`
                                // this is a PATH segment, so it is always present —
                                // without it the route simply does not match.
                                arguments = listOf(
                                    navArgument("theirPublicKey") { type = NavType.StringType }
                                ),
                                enterTransition = navPushEnter(reduceMotion),
                                exitTransition = navPushExit(reduceMotion),
                                popEnterTransition = navPopEnter(reduceMotion),
                                popExitTransition = navPopExit(reduceMotion)
                            ) { backStackEntry ->
                                val rawArg = backStackEntry.arguments?.getString("theirPublicKey") ?: ""
                                // Belt and braces: `Base64.decode` THROWS on input
                                // that is not URL-safe base64, and an uncaught throw
                                // in a composable takes the whole activity down. If
                                // some future caller forgets `encodeKeyForNav`, show
                                // a screen that says "not verified" rather than crash.
                                val theirPublicKey = remember(rawArg) {
                                    runCatching { decodeKeyFromNav(rawArg) }.getOrDefault(rawArg)
                                }
                                SafetyNumberScreen(
                                    theirPublicKey = theirPublicKey,
                                    onBack = { navController.popBackStack() },
                                    // Activity-scoped, see __SETTINGS_FREEZE_2026_11_09__.
                                    mainViewModel = viewModel
                                )
                            }

                            // Call Screen (outgoing) — requests RECORD_AUDIO (+ CAMERA for video) lazily
                            composable("call/{publicKey}/{isVideo}") { backStackEntry ->
                                val publicKey = decodeKeyFromNav(backStackEntry.arguments?.getString("publicKey") ?: "")
                                val encodedKey = encodeKeyForNav(publicKey)
                                val isVideo = backStackEntry.arguments?.getString("isVideo")?.toBoolean() ?: false
                                CallPermissionGate(
                                    isVideo = isVideo,
                                    onDenied = { navController.popBackStack() }
                                ) {
                                    if (isVideo) {
                                        VideoCallScreen(
                                            publicKey = publicKey,
                                            onEnd = { navController.popBackStack() },
                                            onBackToVoice = {
                                                navController.navigate("call/$encodedKey/false") {
                                                    popUpTo("call/$encodedKey/true") { inclusive = true }
                                                }
                                            }
                                        )
                                    } else {
                                        VoiceCallScreen(
                                            publicKey = publicKey,
                                            isIncoming = false,
                                            onEnd = { navController.popBackStack() },
                                            onVideoCall = {
                                                navController.navigate("call/$encodedKey/true") {
                                                    popUpTo("call/$encodedKey/false") { inclusive = true }
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            // Incoming Call Screen — requests RECORD_AUDIO (+ CAMERA for video) lazily
                            composable("incomingCall/{publicKey}/{isVideo}") { backStackEntry ->
                                val publicKey = decodeKeyFromNav(backStackEntry.arguments?.getString("publicKey") ?: "")
                                val encodedKey = encodeKeyForNav(publicKey)
                                val isVideo = backStackEntry.arguments?.getString("isVideo")?.toBoolean() ?: false
                                CallPermissionGate(
                                    isVideo = isVideo,
                                    onDenied = { navController.popBackStack() }
                                ) {
                                    if (isVideo) {
                                        VideoCallScreen(
                                            publicKey = publicKey,
                                            isIncoming = true,
                                            onEnd = { navController.popBackStack() },
                                            // 📹 Mid-call revert (peer or local user tapped "stop video")
                                            // — call is STILL ALIVE, audio path untouched. Mirror the
                                            // outgoing-leg pattern: navigate to the voice variant of the
                                            // SAME call, popping the video destination off the back
                                            // stack so a system Back gesture doesn't return us here.
                                            // Previously this called popBackStack() which dropped the
                                            // user out of the call screen entirely while the audio call
                                            // continued silently in the background — bad UX.
                                            onBackToVoice = {
                                                navController.navigate("incomingCall/$encodedKey/false") {
                                                    popUpTo("incomingCall/$encodedKey/true") { inclusive = true }
                                                }
                                            }
                                        )
                                    } else {
                                        VoiceCallScreen(
                                            publicKey = publicKey,
                                            isIncoming = true,
                                            onEnd = { navController.popBackStack() },
                                            // 📹 If the peer upgrades us to video mid-call (we accepted
                                            // their 0x0E request), navigate to the video variant of the
                                            // SAME call so VideoCallScreen mounts and opens the camera.
                                            // The previous popBackStack() dropped us out of the call.
                                            onVideoCall = {
                                                navController.navigate("incomingCall/$encodedKey/true") {
                                                    popUpTo("incomingCall/$encodedKey/false") { inclusive = true }
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            // QR Code
                            composable("qrCode") {
                                QRCodeScreen(
                                    onBack = { navController.popBackStack() },
                                    // Activity-scoped, see __SETTINGS_FREEZE_2026_11_09__.
                                    viewModel = viewModel
                                )
                            }

                            // Web Session
                            composable("webSession") {
                                WebSessionScreen(
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            // Blocked Contacts
                            // __DEVSYNC_DIRECT_2026_09_22__ Settings → Linked devices (direct own-device sync).
                            composable("linkedDevices") {
                                com.oshi.messenger.ui.screens.LinkedDevicesScreen(
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable("blockedContacts") {
                                BlockedContactsScreen(
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            // Reported Items — iOS SettingsView.swift:1010-1017
                            composable("reportedItems") {
                                ReportedItemsScreen(
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            // Group Chat
                            composable(
                                route = "groupChat/{groupId}",
                                // Same push as a 1:1 chat — the two conversation
                                // surfaces must not move differently.
                                enterTransition = navPushEnter(reduceMotion),
                                exitTransition = navPushExit(reduceMotion),
                                popEnterTransition = navPopEnter(reduceMotion),
                                popExitTransition = navPopExit(reduceMotion)
                            ) { backStackEntry ->
                                val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
                                GroupChatScreen(
                                    groupId = groupId,
                                    onBack = { navController.popBackStack() },
                                    onGroupInfo = { /* Show group info */ },
                                    onNavigateToChat = { publicKey ->
                                        navController.navigate("chat/${encodeKeyForNav(publicKey)}")
                                    },
                                    onNavigateToMap = { navController.navigate("map") },
                                    onNavigateToAIChat = { navController.navigate("aiChat") }
                                )
                            }

                            // Discover Groups
                            composable("discoverGroups") {
                                DiscoverGroupsScreen(
                                    onBack = { navController.popBackStack() },
                                    onGroupJoined = { groupId ->
                                        navController.navigate("groupChat/$groupId") {
                                            popUpTo("groups")
                                        }
                                    }
                                )
                            }

                            // Key Backup & Restore
                            composable("keyBackup") {
                                KeyBackupScreen(
                                    onBack = { navController.popBackStack() },
                                    // Activity-scoped, see __SETTINGS_FREEZE_2026_11_09__.
                                    viewModel = viewModel
                                )
                            }

                            // Profile Edit
                            composable("profileEdit") {
                                ProfileEditScreen(
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            // Bot Detail
                            composable("botDetail/{botId}") { backStackEntry ->
                                val botId = backStackEntry.arguments?.getString("botId") ?: ""
                                BotDetailScreen(
                                    botId = botId,
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            // Scheduled Calls (task 3.1.6)
                            composable("scheduledCalls") {
                                ScheduledCallScreen(
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            // Scheduled Messages (tasks 3.2.1-3.2.6)
                            composable("scheduledMessages") {
                                ScheduledMessageScreen(
                                    onBack = { navController.popBackStack() }
                                )
                            }

                        }
                        }  // Box: centred, width-capped pane

                        // ── App-wide check-in off-ramp ──
                        //
                        // The "Are you okay?" dialog must be answerable from
                        // WHEREVER the user is — the prompt fires fifteen minutes
                        // after an ETA, and there is no reason the user is sitting
                        // in the chat they armed the check-in from. Hosted here, at
                        // the root, it is reachable on all 22 screens; the inline
                        // banner stays in ChatScreen, where iOS puts it.
                        //
                        // `showBanner = false`: at the root there is no sensible
                        // place for an inline card, and a bottom sheet is opened
                        // instead when the user TAPS a check-in notification.
                        CheckInOffRamp(showBanner = false)

                    // The one place an armed review request is handed to Play
                    // (iOS: RootView.deliverArmedReviewRequest). Draws nothing.
                    ReviewDeliveryEffect(
                        isOnboarded = isOnboarded,
                        locked = locked,
                        callState = callState,
                        currentRoute = currentRoute,
                    )

                        // __VOICE_MINIPLAYER_2026_10_09__ The floating voice-note
                        // player. Hosted here for the same reason as the check-in
                        // off-ramp: a note that keeps playing after the user leaves
                        // the conversation has to stay controllable from wherever
                        // they went, and `AudioPlayerManager` is a singleton so the
                        // audio outlives every screen.
                        //
                        // `padding.calculateBottomPadding()` is the bottom bar when
                        // one is up and the system inset when none is, so the pill
                        // never sits on top of the navigation buttons on either
                        // layout. Renders nothing while no note is loaded.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = padding.calculateBottomPadding()),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            com.oshi.messenger.ui.components.VoiceNoteMiniPlayer()
                        }

                        if (pendingOpenCheckIn) {
                            CheckInOffRampSheet(onDismiss = { pendingOpenCheckIn = false })
                        }

                        // __RETURN_TO_CALL_2026_10_28__ consumed once, then cleared, so a
                        // recomposition does not navigate again.
                        pendingReturnToCall?.let { (key, isVideo) ->
                            androidx.compose.runtime.LaunchedEffect(key, isVideo) {
                                navController.navigate("call/${encodeKeyForNav(key)}/$isVideo") {
                                    launchSingleTop = true
                                }
                                pendingReturnToCall = null
                            }
                        }
                    }
                    }  // Row: navigation rail + content
                }
                } // CompositionLocalProvider(LocalBubbleStyle)
            }
            } // IncognitoKeyboardScope
        }
    }

    /**
     * Enter PiP mode when user presses home during a video call.
     * Matches iOS AVPictureInPictureController behavior.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isOnVideoCall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val muteIntent = PendingIntent.getBroadcast(
                    this, PIP_MUTE_REQUEST,
                    Intent(ACTION_PIP_MUTE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val endIntent = PendingIntent.getBroadcast(
                    this, PIP_END_REQUEST,
                    Intent(ACTION_PIP_END),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val muteAction = RemoteAction(
                    Icon.createWithResource(this, if (pipIsMuted) R.drawable.ic_mic_off else R.drawable.ic_call),
                    if (pipIsMuted) getString(R.string.call_unmute) else getString(R.string.call_mute),
                    if (pipIsMuted) getString(R.string.call_unmute) else getString(R.string.call_mute),
                    muteIntent
                )
                val endAction = RemoteAction(
                    Icon.createWithResource(this, R.drawable.ic_call_end),
                    "End",
                    "End call",
                    endIntent
                )
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(9, 16))
                    .setActions(listOf(muteAction, endAction))
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to enter PiP: ${e.message}")
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        Log.d("MainActivity", "PiP mode: $isInPictureInPictureMode")
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ACTION_PIP_MUTE)
            addAction(ACTION_PIP_END)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipActionReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        // Show OSHI logo cover in task switcher (recents) — matches iOS behavior
        com.oshi.messenger.service.PrivacyCoverManager.showCover(this)
    }

    override fun onResume() {
        super.onResume()
        // The Play in-app review flow needs an Activity. Its only trigger is
        // MessageRepository, which holds an @ApplicationContext — so the flow
        // was never launchable. See AppReviewManager.
        com.oshi.messenger.util.AppReviewManager.setCurrentActivity(this)
        // Remove the cover when app returns to foreground
        com.oshi.messenger.service.PrivacyCoverManager.hideCover(this)
        // Auto-lock check: if app was backgrounded longer than configured timeout, require biometric
        checkAutoLockOnForeground()
        // __FORCED_UPDATE_ANDROID_2026_10_13__ Google requires the host app to
        // resume an interrupted IMMEDIATE update on foreground; without this a
        // user who backgrounded Play's screen mid-download is left on a
        // half-applied update with nothing to finish it.
        checkForcedUpdate()
        // (N10, Anatole 13/11) No `cancelAll()` on resume any more. The launcher
        // dot is driven by active notifications, and it now goes away the honest
        // way: each banner is cancelled when its conversation is read
        // (`MessageRepository.dismissMessageNotification`, the group banner in
        // `GroupManager.markGroupMessagesAsRead`), so a dot that remains is a
        // conversation that is still unread, not a stale count.
    }

    // __CLIPBOARD_HYGIENE_2026_10_01__ Android 10+ only lets the FOCUSED app read the
    // clipboard, so a sensitive copy whose 60 s ran out while OSHI was in the
    // background (or before the process was killed: the pending entry is persisted)
    // is checked here, from its description only, and cleared on the way back in.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) com.oshi.messenger.security.SensitiveClipboard.onAppFocused(this)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(pipActionReceiver) } catch (_: Exception) {}
        // Record when we went background so onResume can compute elapsed time for auto-lock
        backgroundedAtMs = System.currentTimeMillis()
        com.oshi.messenger.service.wear.WearLockState.onUiStopped(backgroundedAtMs)
    }

    /** The auto-lock cover, mirrored for the watch bridge (__WEAR_BRIDGE_2026_11_17__). */
    private fun setAutoLockOverlay(on: Boolean) {
        autoLockTriggered.value = on
        com.oshi.messenger.service.wear.WearLockState.overlayShowing = on
    }

    /**
     * If the user has biometric + auto-lock enabled AND enough time has elapsed since
     * backgrounding, mark the UI as locked and present BiometricPrompt to unlock.
     * No-op when disabled or when the activity is freshly launched.
     */
    private fun checkAutoLockOnForeground() {
        // __WEAR_BRIDGE_2026_11_17__ The watch reads the phone's lock state from
        // WearLockState: the UI counts as "started" only once this check has
        // decided, so there is no window in which a watch request could slip in
        // between onResume and the overlay going up.
        if (backgroundedAtMs == 0L) {
            com.oshi.messenger.service.wear.WearLockState.onUiStarted()
            return
        }
        val elapsedMs = System.currentTimeMillis() - backgroundedAtMs
        backgroundedAtMs = 0L
        kotlinx.coroutines.MainScope().launch {
            val prefs = this@MainActivity.dataStore.data.first()
            val biometricEnabled = prefs[com.oshi.messenger.ui.viewmodel.SettingsViewModel.KEY_BIOMETRIC_ENABLED] ?: false
            val timeoutSec = prefs[com.oshi.messenger.ui.viewmodel.SettingsViewModel.KEY_AUTO_LOCK_TIMEOUT_SECONDS] ?: 0
            val shouldLock = biometricEnabled && timeoutSec > 0 && elapsedMs >= timeoutSec * 1000L
            if (shouldLock) {
                setAutoLockOverlay(true)
                promptBiometricUnlock()
            }
            com.oshi.messenger.service.wear.WearLockState.onUiStarted()
        }
    }

    private fun promptBiometricUnlock() {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                setAutoLockOverlay(false)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // If hardware missing or permanently locked out, fail-open so user isn't stranded.
                if (errorCode == androidx.biometric.BiometricPrompt.ERROR_HW_NOT_PRESENT
                    || errorCode == androidx.biometric.BiometricPrompt.ERROR_HW_UNAVAILABLE
                    || errorCode == androidx.biometric.BiometricPrompt.ERROR_NO_BIOMETRICS) {
                    setAutoLockOverlay(false)
                }
                // Otherwise keep overlay; user can retry. USER_CANCELED keeps lock on.
            }
        }
        val prompt = androidx.biometric.BiometricPrompt(this, executor, callback)
        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.settings_auto_lock_unlock_title))
            .setSubtitle(getString(R.string.settings_auto_lock_unlock_subtitle))
            .setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to show BiometricPrompt: ${e.message}")
            setAutoLockOverlay(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        com.oshi.messenger.util.AppReviewManager.setCurrentActivity(null)
        screenshotProtection.unregisterActivity(this)
    }

    /**
     * On Android 14+, USE_FULL_SCREEN_INTENT requires the user to grant the
     * special permission "Allow full-screen notifications" for messengers (i.e.,
     * apps that aren't the default phone dialer). Without it, the incoming-call
     * notification won't pop full-screen on lock screen — it'll just show a
     * heads-up banner. We open the system Settings page once so the user can
     * grant it. We never re-prompt automatically (avoids spamming).
     */
    /**
     * Used to be: auto-launched phone Settings on cold start to request the
     * full-screen-intent grant. That was startling for users (they tapped
     * "Plus d'info" or just opened OSHI and were teleported to phone Settings).
     *
     * Now: no automatic redirect. The Settings → Calls section exposes a
     * "Allow full-screen call notifications" entry the user can tap if they
     * want lock-screen ringing.
     */
    private fun checkFullScreenIntentPermission() {
        // Intentional no-op. See javadoc above.
    }

    /**
     * Request only POST_NOTIFICATIONS permission at startup (Android 13+).
     * All other permissions (camera, microphone, location, Bluetooth, etc.)
     * are requested lazily when the user first taps the corresponding feature.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "Requesting POST_NOTIFICATIONS permission")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Log.d("MainActivity", "POST_NOTIFICATIONS already granted")
            }
        }
    }
}

/**
 * Branded launch/splash screen. The implementation lives in
 * [com.oshi.messenger.ui.screens.LaunchScreen], a 1:1 port of iOS
 * OSHI/LaunchScreenView.swift (gradient, grid, glow, counter-rotating rings,
 * the app mark, wordmark and localized subtitle, staggered dots, reduce-motion).
 * This wrapper keeps the existing call sites (auto-lock cover + initial load).
 */
@Composable
fun OSHILaunchScreen(playIntro: Boolean = true) {
    com.oshi.messenger.ui.screens.LaunchScreen(playIntro = playIntro)
}



/**
 * iOS-style Bottom Navigation Bar
 * Matches iOS TabView with 5 items
 */
/**
 * Group invite join dialog. Shown when the user opens an
 *   oshi://group/join?gid=…&name=…&inviter=…
 * (or universal-link equivalent) deep link.
 */
@Composable
fun GroupInviteJoinDialog(
    invite: MainActivity.GroupInviteData,
    onJoin: () -> Unit,
    onDismiss: () -> Unit
) {
    OshiAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.GroupAdd,
                contentDescription = null,
                tint = OSHIPurple,
                modifier = Modifier.size(36.dp)
            )
        },
        title = { Text(stringResource(R.string.group_invite_title), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (!invite.name.isNullOrBlank()) {
                    Text(
                        invite.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    stringResource(R.string.group_invite_id_format, invite.gid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!invite.inviter.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.group_invite_from, invite.inviter.take(12) + "…"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onJoin,
                colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple)
            ) {
                Text(stringResource(R.string.group_invite_join_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Add-contact prompt for `…/add?key=<pubkey>` deep links.
 * Port of iOS `AddContactFromLinkView` (SharedContentHandler.swift:593):
 * shows the public key, takes an optional alias, then opens the chat.
 */
@Composable
fun AddContactFromLinkDialog(
    publicKey: String,
    myPublicKey: String,
    // (N1) the name offered by the link, if any — still a plain editable
    // field, so it's easy to correct, not a value forced on the contact.
    initialAlias: String = "",
    onDismiss: () -> Unit,
    onAdd: (alias: String) -> Unit
) {
    var alias by remember(publicKey) { mutableStateOf(initialAlias) }
    // Same two guards as iOS (SharedContentHandler.swift:794, :801).
    val isOwnKey = publicKey == myPublicKey && myPublicKey.isNotBlank()
    val isTooShort = publicKey.length < 20
    val error = when {
        isOwnKey -> stringResource(R.string.deeplink_own_key_error)
        isTooShort -> stringResource(R.string.deeplink_invalid_key)
        else -> null
    }

    OshiAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.PersonAdd,
                contentDescription = null,
                tint = OSHIPurple,
                modifier = Modifier.size(36.dp)
            )
        },
        title = { Text(stringResource(R.string.deeplink_new_contact), fontWeight = FontWeight.Bold) },
        text = {
            // Scrollable: description + a 44-character key over three lines + the
            // name field do not fit the body of an AlertDialog on a phone in
            // landscape, and AlertDialog clips rather than scrolls. Observed on a
            // 411 dp-tall window: the "Contact name" field was sliced in half and
            // could not be reached.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.deeplink_add_contact_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.deeplink_public_key),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    publicKey,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                    // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                    // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                    com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                        OutlinedTextField(
                            value = alias,
                            onValueChange = { alias = it },
                            label = { Text(stringResource(R.string.deeplink_contact_name)) },
                            placeholder = { Text(stringResource(R.string.deeplink_name_placeholder)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (error == null) {
                Button(
                    onClick = { onAdd(alias.trim()) },
                    colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple)
                ) {
                    Text(stringResource(R.string.deeplink_add_and_chat))
                }
            } else {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple)
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        },
        dismissButton = {
            if (error == null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}

/**
 * Recipient picker for content shared INTO OSHI from another app.
 * Port of iOS `SharePickerView` (SharedContentHandler.swift:280): preview the
 * payload, pick a contact or group, send, done.
 *
 * Reuses the existing `ForwardPickerViewModel` (contacts + groups + send), so
 * no repository or manager file had to change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetSheet(
    shared: MainActivity.SharedContent,
    onDismiss: () -> Unit,
    onSentToChat: (publicKey: String?) -> Unit,
    viewModel: com.oshi.messenger.ui.viewmodel.ForwardPickerViewModel = hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val targets by viewModel.targets.collectAsState()
    var query by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val filtered = remember(targets, query) {
        if (query.isBlank()) targets
        else targets.filter { it.name.contains(query, ignoreCase = true) }
    }

    OshiModalBottomSheet(
        onDismissRequest = { if (!isSending) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.share_target_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            // Payload summary — iOS shows one row per content class.
            val summary = buildList {
                shared.text?.let { add(stringResource(R.string.share_target_text)) }
                if (shared.streams.isNotEmpty()) {
                    add(stringResource(R.string.share_target_attachments, shared.streams.size))
                }
            }.joinToString(" · ")
            if (summary.isNotBlank()) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(summary, style = MaterialTheme.typography.labelMedium)
                        shared.text?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            if (targets.isEmpty()) {
                Text(
                    stringResource(R.string.share_target_no_contacts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // __KEYBOARD_PRIVACY_2026_10_01__ dialog/sheet = own window with the stock (learning)
                // text input; re-apply the incognito keyboard here (see IncognitoKeyboardScope).
                com.oshi.messenger.ui.keyboard.IncognitoKeyboardScope {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.share_target_search)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (isSending) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = OSHIPurple
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.share_target_sending))
                    }
                }

                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    items(filtered, key = { it.id }) { target ->
                        androidx.compose.material3.ListItem(
                            headlineContent = { Text(target.name) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        if (target.isGroup) R.string.share_target_group
                                        else R.string.share_target_contact
                                    ),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            leadingContent = {
                                Icon(
                                    if (target.isGroup) Icons.Default.Group else Icons.Default.Person,
                                    contentDescription = null,
                                    tint = OSHIPurple
                                )
                            },
                            modifier = Modifier.then(
                                if (isSending) Modifier
                                else Modifier.clickable {
                                    isSending = true
                                    scope.launch {
                                        val ok = sendSharedContent(context, viewModel, target, shared)
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(
                                                if (ok) R.string.share_target_sent
                                                else R.string.share_target_failed
                                            ),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        isSending = false
                                        onSentToChat(if (ok && !target.isGroup) target.id else null)
                                    }
                                }
                            )
                        )
                    }
                }
            }

            TextButton(onClick = { if (!isSending) onDismiss() }) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

/**
 * Read every shared stream off the ContentResolver and hand it to
 * `ForwardPickerViewModel.forward`.
 *
 * Send order matches iOS `SharePickerView.sendContent`
 * (SharedContentHandler.swift:512-566): attachments first, each as its own
 * message, then the shared text as a separate message of its own.
 *
 * Returns false when nothing could be sent, so the caller can say so instead of
 * silently swallowing the share.
 */
private suspend fun sendSharedContent(
    context: Context,
    viewModel: com.oshi.messenger.ui.viewmodel.ForwardPickerViewModel,
    target: com.oshi.messenger.ui.viewmodel.ForwardPickerViewModel.ForwardTarget,
    shared: MainActivity.SharedContent
): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    var sentAny = false

    for (uri in shared.streams) {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: shared.mimeType
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            Log.w("MainActivity", "Shared stream unreadable, skipping: $uri")
            continue
        }
        val type = mediaTypeFor(mime)
        val name = displayNameOf(context, uri)
        // Documents carry their filename in the caption — that is the app's own
        // wire convention (ChatViewModel.sendDocumentMessage), and without it a
        // shared PDF arrives nameless.
        val caption = if (type == com.oshi.messenger.data.model.MediaType.DOCUMENT) {
            "[DOCUMENT] $name"
        } else ""
        // …and in the transport's OWN filename field, which is the only one an
        // iPhone reads (`MessageManager+V2.swift:1017`). The caption above is an
        // Android-local convention; it never becomes a name on the other platform.
        viewModel.forward(target, caption, type, bytes, name)
        sentAny = true
    }

    val text = shared.text.orEmpty()
    if (text.isNotBlank()) {
        viewModel.forward(target, text, null, null)
        sentAny = true
    }
    sentAny
}

/** Best-effort display name for a content:// stream, for the document caption. */
private fun displayNameOf(context: Context, uri: android.net.Uri): String {
    runCatching {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
}

/** Map an Android MIME type onto OSHI's MediaType, mirroring the iOS share extension's classes. */
private fun mediaTypeFor(mime: String?): com.oshi.messenger.data.model.MediaType = when {
    mime == null -> com.oshi.messenger.data.model.MediaType.DOCUMENT
    mime.startsWith("image/") -> com.oshi.messenger.data.model.MediaType.IMAGE
    mime.startsWith("video/") -> com.oshi.messenger.data.model.MediaType.VIDEO
    mime.startsWith("audio/") -> com.oshi.messenger.data.model.MediaType.AUDIO
    else -> com.oshi.messenger.data.model.MediaType.DOCUMENT
}

// ═══════════════════════════════════════════════════════════════════════════
// Bottom-navigation routing — ONE source of truth
// ═══════════════════════════════════════════════════════════════════════════
//
// `OSHIBottomNavigation`'s item list and the "is the bar visible?" list used to
// be two hand-maintained literals, and they drifted: `aiChat` was one of the
// five tabs but was absent from the visibility list. Tapping **AI** therefore
// removed global navigation from a ROOT destination — Map and "+" then hit
// nothing and the user was stranded until they pressed system Back (proven on
// device 2026-08-13: the AI, Map-tap and "+"-tap captures are pixel-identical).
// Both consumers now derive from `BOTTOM_NAV_ROUTES`, so the class of bug is
// gone, not just this instance.
//
// iOS has the same five primary tabs (ContentView.swift:335-383) and never
// hides its TabView on one of them.

/**
 * Who shared the location the Map tab is about to show.
 *
 * Deliberately NOT the coordinate: the coordinate travels as a nav argument
 * (and is therefore restored with the entry), while this identity travels
 * once, with the tap. Keeping them apart is what lets the focus be retired
 * without the avatar being wiped a frame after it appears.
 */
internal data class SharedLocationHandoff(
    val contactName: String?,
    val contactPicture: ByteArray?,
    val isLive: Boolean,
    val sessionId: String?,
) {
    // `ByteArray` uses identity equality, which would make two handoffs holding
    // the same photo unequal and the data class's generated `equals` a lie.
    // Compared by content so the class means what it says.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SharedLocationHandoff) return false
        return contactName == other.contactName &&
            contactPicture.contentEquals(other.contactPicture) &&
            isLive == other.isLive &&
            sessionId == other.sessionId
    }

    override fun hashCode(): Int {
        var result = contactName?.hashCode() ?: 0
        result = 31 * result + (contactPicture?.contentHashCode() ?: 0)
        result = 31 * result + isLive.hashCode()
        result = 31 * result + (sessionId?.hashCode() ?: 0)
        return result
    }
}

internal const val ROUTE_MESSAGES = "messages"
internal const val ROUTE_AI_CHAT = "aiChat"
internal const val ROUTE_NEW_MESSAGE = "newMessage"
internal const val ROUTE_MAP = "map"
internal const val ROUTE_SETTINGS = "settings"

/**
 * The four bottom-tab destinations, in bar order.
 *
 * __TAB_BAR_FOUR_2026_10_26__ There were five, and a user asked for four:
 * « serait-il possible de passer à 4 icônes sachant que celle-ci est en double.
 * J'ai pu observer que sur les téléphones plus petits l'écriture était souvent
 * coupée en bas. »
 *
 * He is right on both counts. The centre tab was `newMessage`, and
 * `ConversationsScreen` — the Chats tab itself — already carries a compose
 * FloatingActionButton wired to the SAME destination
 * (`ConversationsScreen.kt:154`, `onClick = onNewMessage`). So the bar spent a
 * fifth of its width on a second copy of a button sitting a few dp above it.
 *
 * And that fifth is what truncated the labels. On a 360 dp phone five items
 * leave ~72 dp each, minus the item's own padding: not enough for
 * « Discussions » or « Einstellungen », which came out clipped. Four leave
 * ~90 dp, which fits them.
 *
 * `newMessage` is NOT gone — it is still a registered destination, still opened
 * by the FAB and by the QR deep link (`newMessage?scan=true`), and it is listed
 * in [BOTTOM_BAR_SECONDARY_ROUTES] so the bar stays visible while it is open,
 * exactly as it did when it was a tab.
 *
 * iOS deliberately keeps five: its Chats tab has no compose button anywhere, so
 * there the centre tab is the only way in, not a duplicate.
 */
internal val BOTTOM_NAV_ROUTES = listOf(
    ROUTE_MESSAGES, ROUTE_AI_CHAT, ROUTE_MAP, ROUTE_SETTINGS
)

/**
 * Switch to the bottom-bar tab [route].
 *
 * __CHATS_TAB_LEAVES_SETTINGS_2026_11_13__ (Anatole N13: "pouvoir quitter les
 * réglages en appuyant directement sur l'onglet Discussions"). Every tab used the
 * same recipe — `popUpTo(start) { saveState = true }` + `restoreState = true` —
 * and for the Chats tab, which IS the start destination, that recipe undoes
 * itself. A non-inclusive `popUpTo(start)` with `saveState` files the popped stack
 * under the START destination's id as well as its own (navigation-runtime 2.7.5,
 * `NavController.executePopOperations`, the `!inclusive` branch), and `navigate`
 * then finds that id in the saved map and restores it
 * (`navigate` → `restoreStateInternal`). Tapping Chats from Settings popped
 * Settings and put it straight back.
 *
 * So the start tab is reached by POPPING back to it — still saving what is
 * popped, so the Settings tab reopens where it was left — and never by a
 * restoring navigate. The other tabs keep the standard recipe.
 */
internal fun androidx.navigation.NavController.selectTab(route: String) {
    if (route == ROUTE_MESSAGES) {
        val popped = popBackStack(ROUTE_MESSAGES, inclusive = false, saveState = true)
        if (!popped && currentDestination?.route != ROUTE_MESSAGES) {
            // Not on the stack at all (should not happen once onboarded): go there.
            navigate(ROUTE_MESSAGES) { launchSingleTop = true }
        }
        return
    }
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Pushed screens that deliberately keep the bar so the user can leave them
 * without hunting for the back arrow. They are NOT tabs — none of them is ever
 * `selected` — which is why they live in their own list rather than being mixed
 * into [BOTTOM_NAV_ROUTES].
 */
internal val BOTTOM_BAR_SECONDARY_ROUTES = listOf(
    "groups", "peers", "botPortal",
    // __TAB_BAR_FOUR_2026_10_26__ Was a tab until the bar went to four. Listed
    // here so opening it from the Chats FAB still keeps the bar, instead of
    // dropping the user onto a screen with one way out.
    ROUTE_NEW_MESSAGE
)

/**
 * Whether the global bottom bar belongs on [route].
 *
 * Query arguments are stripped first: the Map tab is registered as
 * `map?lat={lat}&lon={lon}&name={name}`, so a literal comparison against `"map"`
 * silently fails for the only Map route that exists.
 */
internal fun shouldShowBottomBar(route: String?): Boolean {
    val base = route?.substringBefore('?') ?: return false
    return base in BOTTOM_NAV_ROUTES || base in BOTTOM_BAR_SECONDARY_ROUTES
}

/**
 * Destinations that must keep every pixel of a wide window instead of being
 * centred inside a reading-width gutter: the map, turn-by-turn navigation and
 * the call surfaces. Everything else is a list or a form and reads better
 * capped — see `OshiWindowSize.contentMaxWidth`.
 *
 * Matched by prefix because three of the four carry path arguments
 * (`call/{publicKey}/{isVideo}`) and the Map tab carries query arguments
 * (`map?lat=…`), so a literal equality test would miss every real route — the
 * same trap [shouldShowBottomBar] documents above.
 */
internal val FULL_BLEED_ROUTES = listOf(ROUTE_MAP, "mapNavigation", "call/", "incomingCall/")

internal fun isFullBleedRoute(route: String?): Boolean {
    val base = route?.substringBefore('?') ?: return false
    // A prefix entry has to end at a path separator. A bare `startsWith("map")`
    // also swallows `mapRegions` and `mapSearch`, which are ordinary lists and
    // want the reading-width cap like every other list.
    return FULL_BLEED_ROUTES.any { base == it || (it.endsWith('/') && base.startsWith(it)) }
}

/**
 * The five primary destinations, resolved from [BOTTOM_NAV_ROUTES].
 *
 * Labels are looked up per route so the items list can never drift from
 * BOTTOM_NAV_ROUTES — the drift is exactly what removed the bar on the AI tab.
 * Shared by the bottom bar (compact windows) and the navigation rail (wide or
 * short ones) so the two can never disagree about what the tabs are.
 */
@Composable
internal fun rememberNavItems(): List<BottomNavItem> {
    val labels = mapOf(
        ROUTE_MESSAGES to stringResource(R.string.tab_chats),
        ROUTE_AI_CHAT to stringResource(R.string.tab_ai),
        ROUTE_NEW_MESSAGE to stringResource(R.string.tab_new),
        ROUTE_MAP to stringResource(R.string.tab_map),
        ROUTE_SETTINGS to stringResource(R.string.tab_settings)
    )
    val icons = mapOf(
        ROUTE_MESSAGES to Icons.Default.Chat,
        ROUTE_AI_CHAT to Icons.Default.Psychology,
        ROUTE_NEW_MESSAGE to Icons.Default.Add,
        ROUTE_MAP to Icons.Default.Map,
        ROUTE_SETTINGS to Icons.Default.Settings
    )
    return BOTTOM_NAV_ROUTES.map { route ->
        BottomNavItem(route, labels.getValue(route), icons.getValue(route))
    }
}

/** Whether [item] is the destination [currentRoute] belongs to. */
internal fun isNavItemSelected(item: BottomNavItem, currentRoute: String?): Boolean =
    // `destination.route` is the registered PATTERN, not the navigated URL —
    // since the Settings route gained a query arg (`settings?account={account}`,
    // N7), `currentRoute` for that tab is never the bare "settings" anymore.
    // Compare on the base path, same as shouldShowBottomBar/isFullBleedRoute
    // already do, or the Settings tab stops highlighting as selected.
    currentRoute?.substringBefore('?') == item.route ||
        (item.route == ROUTE_MAP && currentRoute?.startsWith(ROUTE_MAP) == true)

/**
 * 🔴 __TAB_BAR_UNREADABLE_2026_11_12__ (Anatole, 12/11: « la barre de navigation en
 * bas est toujours illisible »). The selected tab showed NO icon: Material3's
 * NavigationBarItem/NavigationRailItem draw the pill with
 * `indicatorColor.copy(alpha = animationProgress)`, which REPLACES our 15 % alpha
 * with 1.0 — a solid #5616EC pill under a #5616EC icon. And in dark mode the
 * #5616EC label on the #1A1A1A bar is ~2.3:1. Captured on the emulator (1.6.94).
 *
 * So the pill is pre-composited to an OPAQUE tint over the bar, and the accent is
 * a light lavender on a dark bar (≈9:1) and OSHI purple on a light one (≈7:1).
 */
@Composable
private fun navSelectionColors(): Pair<Color, Color> {
    val surface = MaterialTheme.colorScheme.surface
    val dark = surface.luminance() < 0.5f
    val accent = if (dark) Color(0xFFC4B1FF) else OSHIPurple
    val indicator = OSHIPurple.copy(alpha = if (dark) 0.35f else 0.14f).compositeOver(surface)
    return accent to indicator
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OSHIBottomNavigation(
    currentRoute: String?,
    unreadCount: Int = 0,
    onNavigate: (String) -> Unit
) {
    val items = rememberNavItems()
    val (navAccent, navIndicator) = navSelectionColors()

    // __TAB_BAR_FOUR_2026_10_26__ « sur les téléphones plus petits l'écriture
    // était souvent coupée en bas ». Four tabs instead of five fixed the
    // sideways clipping; this fixes the other half, which is vertical.
    //
    // Material3's NavigationBar is a FIXED 80 dp tall whatever the text does, so
    // at the large font scales people actually run (Android goes to 2.0, and
    // these are OEM skins that ship at 1.15 by default) the icon and the label
    // stop fitting and the label is cut off at the bottom of the bar. Capping
    // the scale for the bar only keeps the whole item inside those 80 dp while
    // every other screen still honours the user's choice in full.
    val density = LocalDensity.current
    val barDensity = remember(density.density, density.fontScale) {
        Density(density.density, density.fontScale.coerceAtMost(1.3f))
    }

    CompositionLocalProvider(LocalDensity provides barDensity) {
    // __OLED_GLASS_2026_11_16__ OLED black: a glass bar — near-black fill, a lit
    // hairline along the top edge — and a soft violet glow on the selected tab.
    val navGlass = com.oshi.messenger.ui.theme.glassOn()
    NavigationBar(
        modifier = if (navGlass) Modifier.glassEdge(top = true) else Modifier,
        containerColor = if (navGlass) com.oshi.messenger.ui.theme.GlassTier.Bar.opaque else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // (N10) 0, not 8. `Surface` tints its container when — and only when —
        // the colour it is given IS `colorScheme.surface`
        // (ColorSchemeKt.applyTonalElevation compares against exactly that, and
        // then returns `surfaceColorAtElevation`). This bar passes
        // `colorScheme.surface`, so an 8 dp tonal elevation left it a shade
        // lighter than the conversation list and the top bar, which both sit at
        // 0 — the last piece of "la même couleur partout sur la première page".
        // Setting the Scaffold's own containerColor to surface was necessary and
        // not sufficient: it fixed the body and the bar kept its tint.
        tonalElevation = 0.dp
    ) {
        items.forEach { item ->
            val selected = isNavItemSelected(item, currentRoute)
            // Only the Chats tab carries a badge. There used to be a `"peers" ->
            // peersCount` branch here, but `peers` is not — and never was — one of
            // `items`, so the branch could not run. The live peer count already has
            // a home on the Nearby row inside Settings (SettingsScreen.kt:732),
            // which is where iOS puts it too (MoreTabView.swift:18-43).
            val badgeCount = if (item.route == ROUTE_MESSAGES) unreadCount else 0

            NavigationBarItem(
                icon = {
                    // __TAB_BAR_FOUR_2026_10_26__ The raised purple "+" square
                    // that used to be drawn here went with the tab: `newMessage`
                    // is no longer in `items`, so this branch could never run
                    // again. Its twin in [OSHINavigationRail] went too.
                    if (badgeCount > 0) {
                        // Brand purple badge for all tabs (matching iOS)
                        val badgeColor = OSHIPurple
                        BadgedBox(
                            badge = {
                                Badge(
                                    containerColor = badgeColor,
                                    contentColor = Color.White
                                ) {
                                    Text(
                                        text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                modifier = Modifier
                                .then(if (navGlass && selected) Modifier.glassGlow(CircleShape, alpha = 0.26f, spread = 9.dp) else Modifier)
                                .size(26.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            modifier = Modifier
                                .then(if (navGlass && selected) Modifier.glassGlow(CircleShape, alpha = 0.26f, spread = 9.dp) else Modifier)
                                .size(26.dp)
                        )
                    }
                },
                label = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                // Stated on the item, not only on the icon: a label truncated to
                // "Discussi…" must still be announced in full.
                modifier = Modifier.semantics { contentDescription = item.label },
                selected = selected,
                onClick = { onNavigate(item.route) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = navAccent,
                    selectedTextColor = navAccent,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Was the same as the bar's own containerColor — the
                    // selection pill was invisible in both themes.
                    indicatorColor = navIndicator
                )
            )
        }
    }
    }
}

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

/**
 * Left-hand navigation rail — the wide-window form of [OSHIBottomNavigation].
 * Same five destinations, same badge, same brand colours; only the axis
 * changes.
 *
 * It exists because Android 16 (API 36) stops honouring
 * `android:screenOrientation="portrait"` on any display with a smallest width
 * of 600 dp or more, so a tablet or unfolded foldable now lands in landscape
 * whatever the manifest says. A 5-item bottom bar across 1280 dp is a row of
 * stranded icons; and a phone in landscape is Expanded-width too, where moving
 * the tabs sideways also buys back the ~80 dp of height a bottom bar costs out
 * of the ~360 dp the message list has left.
 *
 * Insets are applied here rather than by the Scaffold: the rail sits OUTSIDE
 * the Scaffold (they are siblings in a Row), so the Scaffold never learns that
 * the start inset has been dealt with. The rail therefore takes the start and
 * vertical safe-drawing insets, and the caller hands the Scaffold the
 * remaining sides — see MainActivity's `contentWindowInsets`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OSHINavigationRail(
    currentRoute: String?,
    unreadCount: Int = 0,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit
) {
    val items = rememberNavItems()
    val (navAccent, navIndicator) = navSelectionColors()

    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        windowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Start + WindowInsetsSides.Vertical
        ),
        header = { Spacer(Modifier.height(8.dp)) }
    ) {
        Spacer(Modifier.weight(1f))
        items.forEach { item ->
            val selected = isNavItemSelected(item, currentRoute)
            val badgeCount = if (item.route == ROUTE_MESSAGES) unreadCount else 0

            NavigationRailItem(
                icon = {
                    // __TAB_BAR_FOUR_2026_10_26__ The raised "+" square is gone
                    // with the tab — see [BOTTOM_NAV_ROUTES].
                    if (badgeCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge(
                                    containerColor = OSHIPurple,
                                    contentColor = Color.White
                                ) {
                                    Text(
                                        text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                },
                // Labelled for the same reason as the bottom bar above.
                label = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.semantics { contentDescription = item.label },
                selected = selected,
                onClick = { onNavigate(item.route) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = navAccent,
                    selectedTextColor = navAccent,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Same fix as the bottom bar above — the indicator must
                    // not match the rail's own containerColor.
                    indicatorColor = navIndicator
                )
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/**
 * Permission gate for call screens.
 * Requests RECORD_AUDIO (voice) or RECORD_AUDIO + CAMERA (video) lazily
 * when the user navigates to a call screen. If denied, navigates back.
 */
@Composable
fun CallPermissionGate(
    isVideo: Boolean,
    onDenied: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var permissionsGranted by remember { mutableStateOf(false) }
    var permissionsRequested by remember { mutableStateOf(false) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            permissionsGranted = true
        } else {
            // Audio is essential for calls — if denied, go back
            val audioDenied = permissions[Manifest.permission.RECORD_AUDIO] == false
            if (audioDenied) {
                onDenied()
            } else {
                // Camera denied for video call — still allow (audio-only fallback)
                permissionsGranted = true
            }
        }
    }

    LaunchedEffect(Unit) {
        val neededPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (isVideo && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CAMERA)
        }

        if (neededPermissions.isEmpty()) {
            permissionsGranted = true
        } else if (!permissionsRequested) {
            permissionsRequested = true
            permissionLauncher.launch(neededPermissions.toTypedArray())
        }
    }

    if (permissionsGranted) {
        content()
    } else {
        // Show a brief loading state while permission dialog is visible
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}

/**
 * Permission gate for mesh networking (Peers screen).
 * Requests BLE + location + nearby WiFi permissions lazily
 * when the user first navigates to the Peers tab.
 */
@Composable
fun MeshPermissionGate(
    content: @Composable () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var permissionsHandled by remember { mutableStateOf(false) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Mesh works best-effort even with partial permissions
        permissionsHandled = true
    }

    LaunchedEffect(Unit) {
        // __MESH_PERMANENT_DENIAL_2026_10_12__ ONE permission set, shared with the
        // PeersScreen card, and every ask recorded.
        //
        // This used to ask for BLUETOOTH_SCAN / _ADVERTISE / _CONNECT *plus*
        // ACCESS_FINE_LOCATION *plus* NEARBY_WIFI_DEVICES — five dialogs on entry,
        // two of them for permissions the BLE lanes do not use on API 31+ — and
        // then threw the result away. A user who waved those away had spent their
        // refusals before the card ever appeared, and because the card tracked
        // denials in session-scoped state it kept offering an "Allow" button that
        // the system will not show a dialog for. Hence the Play report of
        // 12/10/2026: "the Bluetooth permission feature… nothing happens".
        val needed = MeshPermissionRequests.missingForBleLanes(context)
        if (needed.isEmpty()) {
            permissionsHandled = true
        } else {
            MeshPermissionRequests.recordAsked(context, needed)
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // Always show content — mesh works best-effort with whatever permissions are granted
    content()
}


/**
 * __FIRST_CONTACT_REMOTE_2026_10_10__ Hand my invite link to the system chooser.
 *
 * The first-run screen offered only "Show my code" and "Scan a friend's code",
 * and both need the other phone in the room — so someone adding their first
 * contact from their own kitchen had nothing to tap. This is that missing path.
 *
 * The chooser rather than any in-app flow on purpose: it lists the apps this
 * person already uses, which for the audience that gets lost here is the whole
 * point. Shares TEXT only, matching iOS — attaching the URL as a separate item
 * makes some targets show a link preview and drop the sentence that explains
 * what to do with it.
 */
private fun shareMyInviteLink(context: Context, myPublicKey: String, myDisplayName: String) {
    val name = myDisplayName.trim()
    val opener = if (name.isEmpty()) {
        context.getString(R.string.invite_body_opener_anonymous)
    } else {
        context.getString(R.string.invite_body_opener, name)
    }
    val text = InviteLink.message(
        myPublicKey = myPublicKey,
        opener = opener,
        hint = context.getString(R.string.invite_body_hint),
        myName = name
    )
    if (text == null) {
        // No identity yet: better to do nothing than to send a link with no key.
        Log.w("MainActivity", "invite link requested with no public key")
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, text)
        type = "text/plain"
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.invite_share_my_link))
    )
}
