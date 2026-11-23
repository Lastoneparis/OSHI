package com.oshi.messenger.ui.components

import com.oshi.messenger.ui.components.bubbleClickable
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import com.oshi.messenger.ui.theme.OshiAlertDialog
import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.oshi.messenger.service.map.OshiStaticMapRenderer
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oshi.messenger.R
import com.oshi.messenger.network.location.LiveDuration
import com.oshi.messenger.network.location.LocationMessage
import com.oshi.messenger.network.location.LocationSharingManager
import com.oshi.messenger.ui.viewmodel.dataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.oshi.messenger.ui.theme.brandInk
import com.oshi.messenger.ui.theme.isDarkScheme
import com.oshi.messenger.ui.theme.Oshi

// Brand color matching iOS
private val BrandColor = Color(0xFF5616EC)

/**
 * The colour of the viewer's own dot. iOS uses `Color.blue` for it
 * (`OSHI/LocationSharingView.swift:757-770`) precisely because it is NOT the
 * brand purple that marks the shared point — the two must never be mistaken for
 * each other.
 */
private val MeDotColor = Color(0xFF007AFF)

/** One "● name" chip of the two-dot legend under a location preview. */
@Composable
private fun LocationLegendChip(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(color, CircleShape)
                .border(1.dp, Color.White, CircleShape)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Location Share Sheet - Bottom sheet for selecting location sharing options
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LocationShareSheet(
    locationManager: LocationSharingManager,
    conversationId: String,
    onShareLocation: (LocationMessage) -> Unit,
    onDismiss: () -> Unit,
    // Sharing your position is one of the few messenger actions you really do not want
    // to send to the wrong person, so the sheet says who will receive it —
    // iOS `LocationSharingView.swift:41,96`.
    recipientName: String = ""
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(locationManager.hasLocationPermission()) }
    var isLoadingLocation by remember { mutableStateOf(false) }
    var currentAddress by remember { mutableStateOf<String?>(null) }
    var isStartingLiveShare by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // __MULTI_LIVE_SHARE_2026_09_22__ Several shares can run at once; this sheet
    // only cares about the one in THIS conversation.
    val activeLiveShares by locationManager.activeLiveShares.collectAsState()
    val shareHere = activeLiveShares.firstOrNull { it.conversationId == conversationId }
    val liveShareEndTime = shareHere?.endTimeMillis
    val isSharingLiveHere = shareHere != null

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasPermission) {
            isLoadingLocation = true
            locationManager.getCurrentLocation { result ->
                isLoadingLocation = false
                result.onSuccess { location ->
                    locationManager.getAddress(location) { address ->
                        currentAddress = address
                    }
                }
            }
        }
    }

    // Request permission and get location on appear
    LaunchedEffect(Unit) {
        if (hasPermission) {
            isLoadingLocation = true
            locationManager.getCurrentLocation { result ->
                isLoadingLocation = false
                result.onSuccess { location ->
                    locationManager.getAddress(location) { address ->
                        currentAddress = address
                    }
                }
            }
        } else {
            // Auto-request permission when sheet opens
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Helper: send initial live location and start sharing
    fun startLiveLocationSharing(location: android.location.Location, duration: LiveDuration) {
        isStartingLiveShare = false
        val sessionId = java.util.UUID.randomUUID().toString()
        val message = LocationMessage.live(
            location = location,
            duration = duration,
            address = currentAddress,
            sessionId = sessionId
        )
        onShareLocation(message)

        locationManager.startLiveSharing(
            durationMinutes = duration.minutes,
            conversationId = conversationId,
            sessionId = sessionId,
            // Manual Stop and natural expiry both land here, so the peer is told the
            // session is over either way — iOS `LocationSharingView.swift:460-463`.
            onStop = {
                onShareLocation(
                    LocationMessage.stopped(
                        sessionId = sessionId,
                        lastLocation = locationManager.currentLocation.value
                    )
                )
                LocationSharingManager.clearTrackedLiveLocation(sessionId)
            }
        ) { updateMessage ->
            onShareLocation(updateMessage)
        }

        onDismiss()
    }

    // One tap on a duration pill = one live share. There is no second sheet.
    fun selectDuration(duration: LiveDuration) {
        val location = locationManager.currentLocation.value
        if (location != null) {
            startLiveLocationSharing(location, duration)
        } else {
            // Location not ready -- fetch with 10s timeout
            isStartingLiveShare = true
            coroutineScope.launch {
                val loc = withTimeoutOrNull(10_000L) {
                    suspendCoroutine<android.location.Location?> { cont ->
                        locationManager.getCurrentLocation { result ->
                            cont.resume(result.getOrNull())
                        }
                    }
                }
                if (loc != null) {
                    startLiveLocationSharing(loc, duration)
                } else {
                    isStartingLiveShare = false
                    android.widget.Toast.makeText(
                        context, context.getString(R.string.location_unable),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    OshiModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // 🔴 SHORT-WINDOW FIX (2026-08-23). ~440 dp of fixed content — a 180 dp
        // map preview, the info rows, a 52 dp primary button — in a
        // non-scrolling sheet with no bottom inset. On a landscape phone the
        // Share button is under the gesture bar or past it.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Title
            Text(
                text = if (recipientName.isNotBlank()) recipientName
                       else stringResource(R.string.location_share),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Map preview or permission request
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                when {
                    !hasPermission -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.LocationOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = brandInk()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            // Brand violet on `surfaceVariant` measures ~1.23:1 in dark
                            // mode. iOS leaves this headline at `.primary` and tints only
                            // the icon (`OSHI/LocationSharingView.swift:344-345`).
                            Text(
                                stringResource(R.string.location_enable),
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.location_enable_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandColor)
                            ) {
                                Text(stringResource(R.string.location_allow))
                            }
                        }
                    }
                    isLoadingLocation -> {
                        CircularProgressIndicator(color = BrandColor)
                    }
                    else -> {
                        val currentLocation = locationManager.currentLocation.collectAsState()
                        currentLocation.value?.let { location ->
                            // Static map preview using OpenStreetMap tiles
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                // Rendered from OSHI's own tile cache / first-party
                                // tile proxy — see OshiStaticMapRenderer for why the
                                // previous staticmap.openstreetmap.de <img> is gone.
                                OshiMapPreview(
                                    lat = location.latitude,
                                    lon = location.longitude,
                                    contentDescription = stringResource(R.string.accessibility_map_preview),
                                    modifier = Modifier.fillMaxSize()
                                )
                                // Address overlay at bottom
                                currentAddress?.let { address ->
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .background(Color.Black.copy(alpha = 0.5f))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            address,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when {
                isSharingLiveHere -> {
                    // A share already running in THIS conversation takes the place of the
                    // pills, with its end time and a Stop button — iOS
                    // `LocationSharingView.swift:305-334`. Previously the sheet showed the
                    // same two rows and tapping live again silently killed the session.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .background(BrandColor, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.location_sharing_live),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        liveShareEndTime?.let { endMillis ->
                            val endText = remember(endMillis) {
                                java.text.DateFormat
                                    .getTimeInstance(java.text.DateFormat.SHORT)
                                    .format(java.util.Date(endMillis))
                            }
                            Text(
                                stringResource(R.string.location_expires_in, endText),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = {
                                locationManager.stopLiveSharingForConversation(conversationId)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.location_stop_sharing))
                        }
                    }
                }
                else -> {
                    // The primary action looks primary: a full-width filled brand button
                    // instead of one of two identical list rows
                    // (iOS `LocationSharingView.swift:214-236`).
                    Button(
                        onClick = {
                            val location = locationManager.currentLocation.value
                            if (location != null) {
                                onShareLocation(LocationMessage.oneTime(location, currentAddress))
                                onDismiss()
                            } else {
                                // Location not available yet -- request it now
                                isLoadingLocation = true
                                locationManager.getCurrentLocation { result ->
                                    isLoadingLocation = false
                                    result.onSuccess { loc ->
                                        onShareLocation(LocationMessage.oneTime(loc, currentAddress))
                                        onDismiss()
                                    }
                                    result.onFailure {
                                        android.widget.Toast.makeText(
                                            context, context.getString(R.string.location_unable),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        },
                        enabled = hasPermission && !isLoadingLocation && !isStartingLiveShare,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandColor)
                    ) {
                        if (isLoadingLocation) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(Icons.Default.LocationOn, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.location_share_current),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Live section: six durations laid flat. Choosing "15 minutes" is ONE
                    // gesture; the nested duration sheet is gone
                    // (iOS `LocationSharingView.swift:263-295`).
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.MyLocation,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = brandInk()
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                stringResource(R.string.location_share_live),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.location_live_duration),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        if (isStartingLiveShare) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = BrandColor)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        stringResource(R.string.location_updating),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LiveDuration.values().forEach { duration ->
                                    val label = when (duration) {
                                        LiveDuration.TWO_MINUTES -> stringResource(R.string.location_2_minutes)
                                        LiveDuration.FIVE_MINUTES -> stringResource(R.string.location_5_minutes)
                                        LiveDuration.FIFTEEN_MINUTES -> stringResource(R.string.location_15_minutes)
                                        LiveDuration.THIRTY_MINUTES -> stringResource(R.string.location_30_minutes)
                                        LiveDuration.ONE_HOUR -> stringResource(R.string.location_1_hour)
                                        LiveDuration.EIGHT_HOURS -> stringResource(R.string.location_8_hours)
                                    }
                                    // Label stays at onSurface: brand-on-brand-tint measures
                                    // ~1.2:1 in dark mode. The brand is the capsule tint and
                                    // its stroke, not the text
                                    // (iOS `LocationSharingView.swift:283-291`).
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(BrandColor.copy(alpha = 0.22f))
                                            .border(1.dp, BrandColor.copy(alpha = 0.45f), CircleShape)
                                            .clickable(enabled = hasPermission) { selectDuration(duration) }
                                            .padding(horizontal = 16.dp, vertical = 11.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * The signed-in user's own profile photo, as raw bytes, or null when they have
 * not set one.
 *
 * iOS shows YOUR face on YOUR own shared point rather than an anonymous dot
 * (`OSHI/LocationSharingView.swift:634-641`, `ownProfilePhoto`). Android stored
 * the same picture but nothing on this screen ever read it.
 *
 * Reads the DataStore key `SettingsViewModel` writes (`profile_image_uri`)
 * directly rather than pulling in the whole settings ViewModel: a chat bubble
 * has no business owning a settings screen's lifecycle, and the file is loaded
 * once per URI change, off the main thread.
 */
@Composable
fun rememberOwnProfilePhotoBytes(): State<ByteArray?> {
    val context = LocalContext.current
    val key = stringPreferencesKey("profile_image_uri")
    val uriString by context.dataStore.data
        .map { prefs -> prefs[key] }
        .collectAsState(initial = null)

    val bytes = remember { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(uriString) {
        val uri = uriString?.takeIf { it.isNotBlank() }
        bytes.value = if (uri == null) {
            null
        } else {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
                }.getOrNull()
            }
        }
    }
    return bytes
}

/**
 * The user's chosen map app, as a Compose state. Defaults to `"oshi"`, matching iOS
 * (`OSHI/LocationSharingView.swift:1536-1557`).
 */
@Composable
fun rememberMapOpenPreference(): State<String> {
    val context = LocalContext.current
    val key = stringPreferencesKey("map_open_preference")
    return context.dataStore.data
        .map { prefs -> prefs[key] ?: "oshi" }
        .collectAsState(initial = "oshi")
}

/**
 * Open a coordinate in whichever map app the user picked.
 *
 * Shared so the group bubble honours the preference too: it used to hardcode
 * `com.google.android.apps.maps`, so a user who chose OSHI's own map or Waze still
 * landed in Google Maps from a group. iOS routes both bubble kinds through the same
 * preference (`OSHI/LocationSharingView.swift:1536-1557`, `OSHI/GroupViews.swift:8053`).
 *
 * @param onOshi what "oshi" means to the caller — usually opening the in-app map.
 *   When null, falls back to a bare `geo:` intent.
 */
fun openLocationInPreferredApp(
    context: android.content.Context,
    preference: String,
    lat: Double,
    lon: Double,
    label: String?,
    onOshi: (() -> Unit)? = null
) {
    // Delegates to the single opener (see MapOpener). With "oshi" and no
    // `onOshi`, this used to fire a bare `geo:` intent — i.e. Google Maps.
    MapOpener.open(context, preference, lat, lon, label, onOshi)
}

/**
 * Location Message Bubble - Display component for location messages in chat
 */
@Composable
fun LocationMessageBubble(
    locationMessage: LocationMessage,
    isFromCurrentUser: Boolean,
    locationManager: LocationSharingManager,
    conversationId: String?,
    contactName: String? = null,
    contactProfilePicture: ByteArray? = null,
    onSendLocation: ((LocationMessage) -> Unit)? = null,
    onOpenInMap: ((lat: Double, lon: Double, name: String?, contactName: String?, contactPicture: ByteArray?, isLive: Boolean, sessionId: String?) -> Unit)? = null
) {
    val context = LocalContext.current
    var remainingTimeString by remember { mutableStateOf<String?>(null) }
    val activeLiveShares by locationManager.activeLiveShares.collectAsState()
    var showExtendOptions by remember { mutableStateOf(false) }

    // Declared here rather than further down: the preview's camera and the
    // live-follow effect below both need it.
    val isMyLiveShare = isFromCurrentUser &&
            locationMessage.isLive &&
            locationMessage.sessionId != null &&
            activeLiveShares.any { it.sessionId == locationMessage.sessionId }

    val mapPreference by rememberMapOpenPreference()

    // ── Who is who on the preview (iOS LocationSharingView.swift:696-800) ──
    //
    // Two identities, two colours, two shapes:
    //   • the SHARED point wears a face — the contact's photo when this is their
    //     share, the user's own when it is theirs, initials when there is no
    //     photo at all;
    //   • the VIEWER gets a separate blue dot labelled "Me", drawn only when
    //     looking at someone else's share (on your own share, the shared point
    //     IS you and a second dot would be the same person twice).
    //
    // Before this, the bubble drew one red Material pin and nothing else, which
    // is the entire content of the owner's first complaint: there was no second
    // point, so there was nothing to tell apart.
    val ownPhotoBytes by rememberOwnProfilePhotoBytes()
    val myLocation by locationManager.currentLocation.collectAsState()
    val meLabel = stringResource(R.string.sender_name_me)

    // Seed the viewer's own position when opening someone else's share, the way
    // iOS does on `.onAppear` (`swift:1099-1122`) — no button press required.
    // The manager publishes every subsequent fix into `currentLocation`, so a
    // live share keeps both dots current without a timer of our own.
    LaunchedEffect(isFromCurrentUser, locationMessage.sessionId) {
        if (!isFromCurrentUser && myLocation == null && locationManager.hasLocationPermission()) {
            locationManager.getCurrentLocation { /* published via currentLocation */ }
        }
    }

    val previewAvatar = if (isFromCurrentUser) ownPhotoBytes else contactProfilePicture
    // A received share ALWAYS gets an identity disc, never a bare dot: with a
    // photo it shows the face, without one the first two characters of the name,
    // and with neither a "?" disc. The disc itself is the signal — it is what
    // distinguishes "the other person" from the viewer's plain blue dot — so
    // falling back to no disc at all would put us back where we started for
    // exactly the contacts most likely to have no profile picture.
    val previewInitialsFrom = if (isFromCurrentUser) {
        null
    } else {
        contactName?.takeIf { it.isNotBlank() } ?: "?"
    }
    val previewMyLat = if (isFromCurrentUser) null else myLocation?.latitude
    val previewMyLon = if (isFromCurrentUser) null else myLocation?.longitude

    // ── A live share MOVES ──
    //
    // The preview used to be pinned to the coordinate baked into the original
    // message, so a "live" share showed the street the sender was standing on
    // when they pressed the button and never moved again. iOS resolves an
    // `effectiveCoordinate` on every frame (`swift:673-686`): the manager's own
    // fix while it is YOUR live share, the latest received update while it is
    // theirs. `MessageRepository` already records every received update into
    // `LocationSharingManager`'s tracker (that is what the Map tab polls) — the
    // bubble simply never read it.
    var effectiveLat by remember(locationMessage.sessionId, locationMessage.latitude) {
        mutableStateOf(locationMessage.latitude)
    }
    var effectiveLon by remember(locationMessage.sessionId, locationMessage.longitude) {
        mutableStateOf(locationMessage.longitude)
    }
    LaunchedEffect(locationMessage.sessionId, isMyLiveShare, locationMessage.isLive) {
        if (!locationMessage.isLive) return@LaunchedEffect
        val sessionId = locationMessage.sessionId
        while (!locationMessage.isExpired) {
            if (isMyLiveShare) {
                locationManager.currentLocation.value?.let {
                    effectiveLat = it.latitude
                    effectiveLon = it.longitude
                }
            } else if (sessionId != null) {
                LocationSharingManager.getTrackedLiveCoordinate(sessionId)?.let { (la, lo) ->
                    effectiveLat = la
                    effectiveLon = lo
                }
            }
            // Same cadence the Map tab polls at (`MapScreen.kt:129-140`), so the
            // two surfaces never disagree about where someone is.
            delay(5000)
        }
    }

    fun openLocationByPreference(lat: Double, lon: Double, label: String?) {
        openLocationInPreferredApp(
            context = context,
            preference = mapPreference,
            lat = lat,
            lon = lon,
            label = label,
            onOshi = onOpenInMap?.let { open ->
                {
                    open(
                        lat, lon, label,
                        if (!isFromCurrentUser) contactName else null,
                        if (!isFromCurrentUser) contactProfilePicture else null,
                        locationMessage.isLive && !locationMessage.isExpired,
                        locationMessage.sessionId
                    )
                }
            }
        )
    }

    // Pulsing animation for live indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Update remaining time
    LaunchedEffect(locationMessage) {
        while (locationMessage.isLive && !locationMessage.isExpired) {
            val remaining = if (isMyLiveShare) {
                locationManager.getRemainingTimeSeconds(locationMessage.sessionId ?: "")
            } else {
                locationMessage.remainingTimeSeconds ?: 0
            }

            if (remaining > 0) {
                val minutes = remaining / 60
                val seconds = remaining % 60
                remainingTimeString = when {
                    minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
                    minutes > 0 -> "${minutes}m ${seconds}s"
                    else -> "${seconds}s"
                }
            } else {
                remainingTimeString = null
            }
            delay(1000)
        }
        remainingTimeString = null
    }

    Column(
        modifier = Modifier.width(280.dp)
    ) {
        // Map preview with static map image
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .bubbleClickable {
                    openLocationByPreference(locationMessage.latitude, locationMessage.longitude, locationMessage.address)
                },
            contentAlignment = Alignment.Center
        ) {
            // Rendered locally from cached OSM tiles (see OshiStaticMapRenderer).
            OshiMapPreview(
                lat = effectiveLat,
                lon = effectiveLon,
                contentDescription = stringResource(R.string.accessibility_location_map),
                modifier = Modifier.fillMaxSize(),
                markerAvatar = previewAvatar,
                markerInitialsFrom = previewInitialsFrom,
                markerIsLive = locationMessage.isLive && !locationMessage.isExpired,
                myLat = previewMyLat,
                myLon = previewMyLon,
                myLabel = meLabel
            )

            // Live indicator badge
            if (locationMessage.isLive && !locationMessage.isExpired) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .scale(pulseScale)
                                .background(BrandColor, CircleShape)
                        )
                        Text(
                            stringResource(R.string.location_live),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Info bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(10.dp)
        ) {
            // Status row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                when {
                    locationMessage.isLive && !locationMessage.isExpired -> {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(BrandColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.location_sharing_live),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = brandInk()
                        )
                    }
                    locationMessage.isLive && locationMessage.isExpired -> {
                        Icon(
                            Icons.Default.TimerOff,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.location_expired),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = brandInk()
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.location_shared_location),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Remaining time
                remainingTimeString?.let { timeString ->
                    Box(
                        modifier = Modifier
                            .background(
                                Color(0xFFFFA500).copy(alpha = 0.15f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            timeString,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFFFA500)
                        )
                    }
                }
            }

            // ── Legend: which dot is which ──
            //
            // The map is a bitmap, so its colours carry no text and a screen
            // reader gets one contentDescription for the whole picture. Two
            // chips naming the two dots make the distinction readable in
            // greyscale, spoken aloud, and without knowing the convention —
            // which is what the owner asked for ("I don't understand where I am
            // and where the other person is"). Shown only when there ARE two
            // points; on your own share there is one person on the map.
            //
            // No new translation keys: `sender_name_me` and `map_marker_contact`
            // already ship in all 34 languages.
            if (previewMyLat != null && previewMyLon != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LocationLegendChip(
                        color = BrandColor,
                        label = contactName?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.map_marker_contact)
                    )
                    LocationLegendChip(
                        color = MeDotColor,
                        label = meLabel
                    )
                }
            }

            // Address
            locationMessage.address?.let { address ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons
            when {
                isMyLiveShare -> {
                    // Sender: Stop button
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            // The `stopped` message is sent by the manager's onStop
                            // hook, which also covers natural expiry; sending it here
                            // too would emit it twice.
                            onClick = { locationMessage.sessionId?.let { locationManager.stopLiveSharing(sessionId = it) } },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.location_stop), fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = { showExtendOptions = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.location_extend), fontSize = 12.sp)
                        }
                    }
                }
                !isFromCurrentUser && locationMessage.isLive && !locationMessage.isExpired -> {
                    // Receiver: Share my location button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(
                            onClick = {
                                // Start sharing receiver's location with same remaining time
                                val remainingSeconds = locationMessage.remainingTimeSeconds ?: (15 * 60L)
                                locationManager.currentLocation.value?.let { location ->
                                    val sessionId = java.util.UUID.randomUUID().toString()
                                    val message = LocationMessage.liveWithCustomDuration(
                                        location = location,
                                        durationSeconds = remainingSeconds,
                                        sessionId = sessionId
                                    )
                                    onSendLocation?.invoke(message)

                                    conversationId?.let { convId ->
                                        locationManager.startLiveSharingWithCustomDuration(
                                            durationSeconds = remainingSeconds,
                                            conversationId = convId,
                                            sessionId = sessionId,
                                            onStop = {
                                                onSendLocation?.invoke(
                                                    LocationMessage.stopped(
                                                        sessionId = sessionId,
                                                        lastLocation = locationManager.currentLocation.value
                                                    )
                                                )
                                                LocationSharingManager.clearTrackedLiveLocation(sessionId)
                                            }
                                        ) { updateMessage ->
                                            onSendLocation?.invoke(updateMessage)
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandColor),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.location_share_mine), fontSize = 12.sp)
                        }

                        TextButton(
                            onClick = {
                                openLocationByPreference(locationMessage.latitude, locationMessage.longitude, locationMessage.address)
                            }
                        ) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.location_open), fontSize = 12.sp)
                        }
                    }
                }
                else -> {
                    // Default: Open in Maps
                    TextButton(
                        onClick = {
                            openLocationByPreference(locationMessage.latitude, locationMessage.longitude, locationMessage.address)
                        }
                    ) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.location_open_in_maps), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // iOS offers three amounts in a confirmationDialog
    // (`OSHI/LocationSharingView.swift:1157-1168`); Android used to add exactly
    // 15 minutes with no choice and no confirmation.
    if (showExtendOptions) {
        OshiAlertDialog(
            onDismissRequest = { showExtendOptions = false },
            title = { Text(stringResource(R.string.location_extend_title)) },
            text = {
                Column {
                    listOf(
                        R.string.location_extend_15min to 15,
                        R.string.location_extend_30min to 30,
                        R.string.location_extend_1hour to 60
                    ).forEach { (labelRes, minutes) ->
                        TextButton(
                            onClick = {
                                locationMessage.sessionId?.let { locationManager.extendLiveSharing(it, minutes) }
                                showExtendOptions = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(labelRes),
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Start
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExtendOptions = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * Live Location Banner - Shown at top of chat when sharing
 */
@Composable
fun LiveLocationBanner(
    locationManager: LocationSharingManager,
    /** The conversation whose share this banner shows; null = the most recent share. */
    conversationId: String? = null,
    onStop: () -> Unit
) {
    val activeLiveShares by locationManager.activeLiveShares.collectAsState()
    val share = if (conversationId != null) activeLiveShares.firstOrNull { it.conversationId == conversationId }
                else activeLiveShares.lastOrNull()
    val isLiveSharing = share != null
    var remainingTimeString by remember { mutableStateOf("") }

    // Update remaining time
    LaunchedEffect(share?.sessionId) {
        while (share != null) {
            val remaining = locationManager.getRemainingTimeSeconds(share.sessionId)
            val minutes = remaining / 60
            val seconds = remaining % 60
            remainingTimeString = "%d:%02d".format(minutes, seconds)
            delay(1000)
        }
    }

    if (isLiveSharing) {
        Surface(
            color = BrandColor.copy(alpha = 0.1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pulsing indicator
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(BrandColor, CircleShape)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.location_sharing_live),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        stringResource(R.string.location_expires_in, remainingTimeString),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(
                    onClick = onStop,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.location_stop))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Map preview
// ═══════════════════════════════════════════════════════════════════════

/**
 * The location preview shown in the share sheet and in every received location
 * bubble.
 *
 * WHAT IT REPLACES
 * ----------------
 * Both call sites used to be an `AsyncImage` of
 * `https://staticmap.openstreetmap.de/staticmap.php?center=<LAT>,<LON>&…&markers=<LAT>,<LON>,…`.
 * `host staticmap.openstreetmap.de` is **NXDOMAIN** — the domain does not
 * resolve — so every preview in the app has rendered as a blank grey box. Had it
 * resolved it would have shipped the shared coordinates, in a URL query string,
 * to an uncontrolled third party.
 *
 * WHAT IT DOES INSTEAD
 * --------------------
 * [OshiStaticMapRenderer] composites the picture from OSM slippy tiles taken
 * from the caches the offline map stack already fills, falling back only to
 * OSHI's own first-party tile proxy. No new host, no coordinates on the wire
 * (a tile index at z=15 is ~1.2 km wide), and a downloaded region renders with
 * no network at all. The pin is drawn locally on top, so the exact point never
 * leaves the device.
 *
 * When nothing can be rendered (no cached tiles, offline) it says so rather than
 * showing an unexplained grey rectangle.
 *
 * PUBLIC on purpose: `GroupChatScreen.kt:2963-2965` still builds the same dead
 * `staticmap.openstreetmap.de` URL for the GROUP location bubble. That file is
 * owned by another agent; dropping this composable in there is a one-line fix.
 */
@Composable
fun OshiMapPreview(
    lat: Double,
    lon: Double,
    contentDescription: String,
    modifier: Modifier = Modifier,
    /**
     * Profile photo to draw on the shared point — the contact's when you are
     * looking at their share, your own when you are looking at yours. Falls back
     * to [markerInitialsFrom] when absent, exactly as iOS does
     * (`OSHI/OfflineMapView.swift:2455-2480`).
     */
    markerAvatar: ByteArray? = null,
    /** Name the fallback initials are taken from. */
    markerInitialsFrom: String? = null,
    /** Draw the live ring around the shared point. */
    markerIsLive: Boolean = false,
    /**
     * The viewer's own coordinate. When present the preview frames BOTH points
     * and draws a second, visually distinct dot — the whole answer to "where am
     * I and where are they".
     */
    myLat: Double? = null,
    myLon: Double? = null,
    /** Localized word drawn inside the viewer's dot; iOS writes "Me" there. */
    myLabel: String? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    // The preview is one bitmap whose camera depends on every input below, so
    // the cached image must be dropped whenever any of them moves — a live share
    // that walks two streets over with a stale `remember(lat, lon)` key would
    // keep showing the old corner.
    //
    // The avatar enters the key by CONTENT, not by identity: `ByteArray` uses
    // reference equality, so a repository that hands out an equal-but-new array
    // on each emission would re-decode and re-composite the whole preview on
    // every recomposition of the chat list.
    val renderKey = arrayOf<Any?>(
        lat, lon, myLat, myLon,
        markerAvatar?.contentHashCode(), markerInitialsFrom, markerIsLive, myLabel
    )
    var bitmap by remember(*renderKey) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var attempted by remember(*renderKey) { mutableStateOf(false) }

    LaunchedEffect(*renderKey, sizePx) {
        if (sizePx.width <= 0 || sizePx.height <= 0) return@LaunchedEffect
        val avatar = markerAvatar?.let {
            runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
        }
        val framing = if (myLat != null && myLon != null) {
            OshiStaticMapRenderer.fitTwoPoints(lat, lon, myLat, myLon, sizePx.width, sizePx.height)
        } else {
            OshiStaticMapRenderer.fitOnePoint(lat, lon, sizePx.width)
        }
        val markers = buildList {
            add(
                OshiStaticMapRenderer.MarkerSpec(
                    lat = lat,
                    lon = lon,
                    style = OshiStaticMapRenderer.MarkerStyle.SHARED,
                    avatar = avatar,
                    initials = markerInitialsFrom?.takeIf { it.isNotBlank() }
                        ?.let { OshiStaticMapRenderer.initialsFor(it) },
                    isLive = markerIsLive,
                )
            )
            if (myLat != null && myLon != null) {
                add(
                    OshiStaticMapRenderer.MarkerSpec(
                        lat = myLat,
                        lon = myLon,
                        style = OshiStaticMapRenderer.MarkerStyle.ME,
                        label = myLabel,
                    )
                )
            }
        }
        bitmap = OshiStaticMapRenderer.renderWithMarkers(
            context = context,
            framing = framing,
            widthPx = sizePx.width,
            heightPx = sizePx.height,
            markers = markers,
            density = density,
        )
        avatar?.recycle()
        attempted = true
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { sizePx = it },
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        if (bitmap == null && attempted) {
            Text(
                text = stringResource(R.string.location_preview_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        // The markers used to be ONE red Material pin, overlaid dead-centre by
        // Compose. That could only ever mark the point the bitmap was centred
        // on, so a second coordinate was impossible to draw and the viewer had
        // no dot at all. They are now composited into the bitmap by
        // [OshiStaticMapRenderer.renderWithMarkers], which knows the camera and
        // can therefore place any coordinate — still entirely client-side, so
        // the precise point is never part of any request.
    }
}
