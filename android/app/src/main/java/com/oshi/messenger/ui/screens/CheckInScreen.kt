package com.oshi.messenger.ui.screens

import android.Manifest
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import com.oshi.messenger.ui.theme.OshiAlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.oshi.messenger.R
import com.oshi.messenger.data.model.*
import com.oshi.messenger.service.CheckInArmingDecision
import com.oshi.messenger.service.CheckInEtaEstimator
import com.oshi.messenger.service.CheckInManager
import com.oshi.messenger.service.CheckInPermissionPolicy
import com.oshi.messenger.service.CheckInUserAction
import com.oshi.messenger.service.map.PlatformGeocoderGate
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIRed
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Check-In Sheet - Destination picker and session configuration
 * iOS-compatible implementation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInSheet(
    contactPublicKey: String,
    contactName: String,
    onDismiss: () -> Unit,
    onCheckInStarted: (CheckInMessage) -> Unit
) {
    val context = LocalContext.current
    val checkInManager = remember { CheckInManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    // State
    var destinationAddress by remember { mutableStateOf("") }
    var destinationLatitude by remember { mutableStateOf(0.0) }
    var destinationLongitude by remember { mutableStateOf(0.0) }
    var selectedTransportMode by remember { mutableStateOf(TransportMode.driving) }
    var extraTimeMinutes by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }

    // ---------------------------------------------------------------------
    // 🔴 P0 FIX (2026-08-24 feature check): destination and ETA were both FAKE.
    //
    // `destinationLatitude/Longitude` were declared `0.0` and never assigned — the
    // "Tap to select on map" line under the address field was a `TODO`, not a
    // control — so every session was armed at Null Island and the arrival test in
    // `CheckInManager.checkProgress` could never fire. And the deadline the whole
    // feature is measured against was `System.currentTimeMillis() + 30 * 60 * 1000`
    // with the comment "For demo". Thirty minutes for a five-minute walk means a
    // genuine emergency is announced 25 minutes late; thirty minutes for an hour's
    // drive means a FALSE emergency reaches a contact while the user is still on the
    // motorway.
    //
    // Both are now collected: the address is geocoded (gated by
    // `PlatformGeocoderGate`, exactly as `ItineraryScreen` does, so "Offline maps
    // only" is still honoured), and the trip duration is a chip row pre-selected
    // from `CheckInEtaEstimator` and adjustable by the user. iOS's equivalent is
    // `MKDirections` plus a refusal to start without a route
    // (`OSHI/CheckInView.swift:473-474`).
    // ---------------------------------------------------------------------
    var destSuggestions by remember { mutableStateOf<List<GeoSuggestion>>(emptyList()) }
    var showDestSuggestions by remember { mutableStateOf(false) }
    var addressLookupBlocked by remember { mutableStateOf(false) }
    var tripMinutes by remember { mutableStateOf(30) }
    var userLatitude by remember { mutableStateOf<Double?>(null) }
    var userLongitude by remember { mutableStateOf<Double?>(null) }

    val hasDestinationFix = destinationLatitude != 0.0 || destinationLongitude != 0.0
    val estimatedArrivalTime = remember(tripMinutes) {
        System.currentTimeMillis() + tripMinutes * 60_000L
    }

    val scrollState = rememberScrollState()

    // ---------------------------------------------------------------------
    // Location permission, asked IN CONTEXT at the moment the user arms a
    // check-in — never at launch. iOS does the same thing: the only
    // `requestAlwaysAuthorization()` call in the app is inside
    // `startLocationMonitoring` (OSHI/CheckInManager.swift:230), i.e. after the
    // user has committed to a journey and the ask can explain itself.
    //
    // __NO_BACKGROUND_LOCATION_2026_10_24__ ONE dialog, not two. The second step
    // used to ask for ACCESS_BACKGROUND_LOCATION; Google Play rejected the app on
    // 2026-10-24 for holding that permission without a prominent disclosure, so
    // the app no longer declares it and no longer asks. The journey is kept alive
    // by CheckInLocationService (foreground service typed `location`), which needs
    // only this grant. What that costs the user is said out loud, twice: on the
    // arming screen below and on the ongoing notification.
    // The decision table lives in CheckInPermissionPolicy (unit-tested).
    // ---------------------------------------------------------------------
    var foregroundRequested by remember { mutableStateOf(false) }
    var showLocationRequired by remember { mutableStateOf(false) }

    fun hasForegroundLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // Where the user is starting from — needed to turn a destination into a
    // duration. Read once, best-effort: a missing position only costs the
    // pre-selected chip, never the ability to arm a check-in.
    LaunchedEffect(Unit) {
        if (!hasForegroundLocation()) return@LaunchedEffect
        runCatching {
            val client = com.google.android.gms.location.LocationServices
                .getFusedLocationProviderClient(context)
            @Suppress("MissingPermission")
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    userLatitude = loc.latitude
                    userLongitude = loc.longitude
                }
            }
        }
    }

    // Re-estimate whenever the destination or the mode changes. Never silently
    // OVERWRITES a smaller number than the estimate: `snapToOffered` rounds up, and
    // the user can still shorten it deliberately.
    LaunchedEffect(destinationLatitude, destinationLongitude, selectedTransportMode) {
        val fromLat = userLatitude
        val fromLon = userLongitude
        if (fromLat != null && fromLon != null &&
            (destinationLatitude != 0.0 || destinationLongitude != 0.0)
        ) {
            tripMinutes = CheckInEtaEstimator.snapToOffered(
                CheckInEtaEstimator.estimateMinutes(
                    fromLat, fromLon, destinationLatitude, destinationLongitude,
                    selectedTransportMode
                )
            )
        }
    }

    /**
     * Geocode the typed address. Same shape as `ItineraryScreen.searchAddress`,
     * including the `PlatformGeocoderGate` refusal: `android.location.Geocoder` is a
     * Google network service, and "Offline maps only" promises the typed destination
     * never leaves the device.
     */
    fun searchDestination(query: String) {
        when (PlatformGeocoderGate.decide(context, query)) {
            PlatformGeocoderGate.Decision.Idle -> {
                addressLookupBlocked = false
                destSuggestions = emptyList()
                return
            }
            PlatformGeocoderGate.Decision.BlockedOfflineOnly -> {
                addressLookupBlocked = true
                destSuggestions = emptyList()
                return
            }
            PlatformGeocoderGate.Decision.Geocode -> addressLookupBlocked = false
        }
        scope.launch {
            val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val geocoder = android.location.Geocoder(context, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(query, 5)?.map { addr ->
                        GeoSuggestion(
                            title = addr.featureName
                                ?: addr.getAddressLine(0)?.split(",")?.firstOrNull()
                                ?: query,
                            subtitle = addr.getAddressLine(0) ?: "",
                            lat = addr.latitude,
                            lon = addr.longitude
                        )
                    } ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }
            destSuggestions = results
        }
    }

    val armSession: () -> Unit = {
        showLocationRequired = false
        isLoading = true
        scope.launch {
            // The deadline the emergency escalation is measured against. `tripMinutes`
            // is the user's own answer, pre-filled from `CheckInEtaEstimator` when a
            // destination fix and a current position are both available.
            val eta = System.currentTimeMillis() + tripMinutes * 60_000L

            checkInManager.startCheckIn(
                contactPublicKey = contactPublicKey,
                destinationLatitude = destinationLatitude,
                destinationLongitude = destinationLongitude,
                destinationAddress = destinationAddress,
                transportMode = selectedTransportMode,
                estimatedArrivalTimeMs = eta,
                extraTimeMinutes = extraTimeMinutes,
                onMessageSend = { message -> onCheckInStarted(message) }
            )

            isLoading = false
            onDismiss()
        }
        Unit
    }

    // The ordinary while-in-use dialog — the only location dialog OSHI shows.
    val foregroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        foregroundRequested = true
        // Coarse alone is enough to arm: arrival is a 100 m radius test.
        if (result.values.none { it } && !hasForegroundLocation()) {
            showLocationRequired = true
        } else {
            armSession()
        }
    }

    val openAppSettings: () -> Unit = {
        showLocationRequired = false
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        Unit
    }

    /** Entry point for the "Start Check-In" button. */
    val onStartClicked: () -> Unit = {
        when (
            CheckInPermissionPolicy.decide(
                hasForegroundLocation = hasForegroundLocation(),
                foregroundAlreadyRequested = foregroundRequested,
            )
        ) {
            CheckInArmingDecision.READY -> armSession()
            CheckInArmingDecision.REQUEST_FOREGROUND ->
                foregroundLocationLauncher.launch(
                    CheckInPermissionPolicy.foregroundPermissions().toTypedArray()
                )
            CheckInArmingDecision.BLOCKED_NO_LOCATION -> showLocationRequired = true
        }
    }

    // --- Permission dialogs -------------------------------------------------

    if (showLocationRequired) {
        OshiAlertDialog(
            onDismissRequest = { showLocationRequired = false },
            title = { Text(stringResource(R.string.checkin_location_required_title)) },
            text = { Text(stringResource(R.string.checkin_location_required_body)) },
            confirmButton = {
                TextButton(onClick = openAppSettings) {
                    Text(stringResource(R.string.checkin_location_required_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocationRequired = false }) {
                    Text(stringResource(R.string.checkin_permission_dismiss))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Header
        TopAppBar(
            title = {
                Text(
                    stringResource(R.string.checkin_title),
                    fontWeight = FontWeight.Bold
                )
            },
            navigationIcon = {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        Column(
            // weight(1f), not fillMaxSize(): below the TopAppBar, fillMaxSize made
            // the scroll viewport a full parent-height TALL, i.e. an app-bar's
            // height past the bottom of the screen — which is exactly where the
            // "Start Check-In" button ends up (seen on the 1.6.28 emulator).
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Safety message
            OshiCard(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Column {
                        Text(
                            stringResource(R.string.checkin_safety_title),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Text(
                            stringResource(R.string.checkin_safety_desc, contactName),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Destination input
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.checkin_where),
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
                OutlinedTextField(
                    value = destinationAddress,
                    onValueChange = { typed ->
                        destinationAddress = typed
                        // Typing invalidates a previously picked place: keeping the
                        // old coordinates would silently watch for arrival at a
                        // destination the user has just replaced.
                        destinationLatitude = 0.0
                        destinationLongitude = 0.0
                        showDestSuggestions = true
                        searchDestination(typed)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.checkin_dest_placeholder)) },
                    leadingIcon = {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                    },
                    trailingIcon = {
                        if (hasDestinationFix) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                if (showDestSuggestions && destSuggestions.isNotEmpty()) {
                    OshiCard(shape = RoundedCornerShape(12.dp)) {
                        Column {
                            destSuggestions.forEach { suggestion ->
                                Surface(
                                    onClick = {
                                        destinationAddress =
                                            suggestion.subtitle.ifBlank { suggestion.title }
                                        destinationLatitude = suggestion.lat
                                        destinationLongitude = suggestion.lon
                                        destSuggestions = emptyList()
                                        showDestSuggestions = false
                                    },
                                    color = Color.Transparent,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(suggestion.title, fontSize = 14.sp)
                                        if (suggestion.subtitle.isNotBlank()) {
                                            Text(
                                                suggestion.subtitle,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // What the destination is FOR, said plainly. The line that used to
                // sit here read "Tap to select on map" and nothing was tappable.
                Text(
                    stringResource(
                        if (hasDestinationFix) R.string.checkin_dest_hint
                        else R.string.checkin_dest_no_fix
                    ),
                    fontSize = 12.sp,
                    color = if (hasDestinationFix) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )

                if (addressLookupBlocked) {
                    Text(
                        stringResource(R.string.itinerary_address_lookup_offline_only),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            // Transport mode selector
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.checkin_how),
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TransportMode.entries.forEach { mode ->
                        TransportModeChip(
                            mode = mode,
                            isSelected = selectedTransportMode == mode,
                            onClick = { selectedTransportMode = mode },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Trip duration — the actual deadline. Pre-selected from
            // CheckInEtaEstimator when a destination fix and a current position are
            // both known; always adjustable, because the estimator has no road graph
            // and the user knows their own journey.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.checkin_duration),
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CheckInEtaEstimator.OFFERED_MINUTES.forEach { minutes ->
                        DurationChip(
                            minutes = minutes,
                            isSelected = tripMinutes == minutes,
                            onClick = { tripMinutes = minutes }
                        )
                    }
                }
            }

            // Extra time picker
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.checkin_extra_time),
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf(0, 15, 30, 60).forEach { minutes ->
                        ExtraTimeChip(
                            minutes = minutes,
                            isSelected = extraTimeMinutes == minutes,
                            onClick = { extraTimeMinutes = minutes },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ETA preview — a real arrival time now, not a placeholder.
            if (destinationAddress.isNotEmpty()) {
                OshiCard(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.checkin_eta),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                formatTime(estimatedArrivalTime + extraTimeMinutes * 60_000L),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                        Icon(
                            Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // __NO_BACKGROUND_LOCATION_2026_10_24__ Said BEFORE the user commits,
            // not discovered afterwards. OSHI holds no background-location grant
            // (see AndroidManifest.xml), so an armed check-in lives exactly as long
            // as the app's own process: the foreground service keeps positions
            // coming with the screen off, and a swipe-away ends it. The same
            // sentence is repeated on the ongoing notification.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.checkin_foreground_only_note),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Start Check-In button
            Button(
                onClick = {
                    if (destinationAddress.isNotEmpty()) {
                        // Permissions are resolved first: arming a check-in that
                        // cannot read location would ship a safety feature that
                        // silently never fires.
                        onStartClicked()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = destinationAddress.isNotEmpty() && !isLoading,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.checkin_start),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun TransportModeChip(
    mode: TransportMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                mode.emoji,
                fontSize = 24.sp
            )
            Text(
                stringResource(mode.localizedNameRes),
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExtraTimeChip(
    minutes: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            if (minutes == 0) stringResource(R.string.checkin_none) else stringResource(R.string.checkin_extra_min, minutes),
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DurationChip(
    minutes: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            stringResource(R.string.checkin_duration_min, minutes),
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatTime(timestampMs: Long): String {
    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    return formatter.format(Date(timestampMs))
}

/**
 * Active Check-In Banner - Shows in chat when check-in is active
 */
@Composable
fun CheckInActiveBanner(
    session: CheckInSession,
    onArrivedClick: () -> Unit,
    onExtendClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OshiCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.checkin_active),
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                stringResource(R.string.checkin_to, session.destinationAddress),
                fontSize = 14.sp
            )

            Text(
                stringResource(R.string.checkin_eta_time, session.formattedRemainingTime),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onArrivedClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.checkin_arrived), fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onExtendClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.checkin_extend), fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onStopClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.map_nav_stop), fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * The in-app half of the off-ramp: a banner while a check-in is open, and the
 * "Are you okay?" dialog when it goes overdue.
 *
 * 🔴 P0 FIX (2026-08-24 feature check). [CheckInActiveBanner] existed and was
 * **never composed**; `CheckInManager.showWarningAlert` was published and **never
 * collected**. Between them that left the user with no in-app way to say anything
 * about their own journey, which is half of why every overdue check-in escalated.
 *
 * Everything here reads the process-wide singleton rather than a ViewModel, and
 * every button goes through [CheckInManager.applyUserAction] — the same method the
 * notification actions reach from a dead process. One implementation, three
 * surfaces, no drift.
 *
 * Drop this into any screen that should carry the off-ramp; it renders nothing when
 * there is no open session.
 *
 * @param showBanner false on hosts that only want the dialog (the app-wide host in
 *   `MainActivity` shows the dialog everywhere, while the banner is inline in the
 *   chat, where iOS puts it).
 */
@Composable
fun CheckInOffRamp(
    modifier: Modifier = Modifier,
    showBanner: Boolean = true,
) {
    val context = LocalContext.current
    val manager = remember { CheckInManager.getInstance(context) }
    val session by manager.activeSession.collectAsState()
    val showWarning by manager.showWarningAlert.collectAsState()

    val current = session
    if (current == null || (current.status != CheckInStatus.active && current.status != CheckInStatus.expired)) {
        return
    }

    if (showBanner) {
        CheckInActiveBanner(
            session = current,
            onArrivedClick = { manager.applyUserAction(CheckInUserAction.ARRIVED) },
            onExtendClick = { manager.applyUserAction(CheckInUserAction.EXTEND, 15) },
            onStopClick = { manager.applyUserAction(CheckInUserAction.CANCEL) },
            modifier = modifier
        )
    }

    if (showWarning) {
        CheckInWarningDialog(
            onSafe = { manager.applyUserAction(CheckInUserAction.CONFIRM_SAFE) },
            onExtend = { manager.applyUserAction(CheckInUserAction.EXTEND, 30) },
            onArrived = { manager.applyUserAction(CheckInUserAction.ARRIVED) },
        )
    }
}

/**
 * What a tap on a check-in notification opens: the session's controls, from
 * anywhere in the app.
 *
 * `CheckInManager.EXTRA_OPEN_CHECKIN` was set on the ongoing service notification
 * and on all three alerts, and never read — so tapping "Are you okay?" opened OSHI
 * on whatever screen it happened to be showing, with no way to answer. This sheet is
 * what that extra now leads to.
 *
 * If the session ended between the notification being posted and the tap landing,
 * nothing is shown and the sheet closes itself: a control for a journey that is over
 * is worse than no control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInOffRampSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { CheckInManager.getInstance(context) }
    val session by manager.activeSession.collectAsState()

    val current = session
    if (current == null || (current.status != CheckInStatus.active && current.status != CheckInStatus.expired)) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    OshiModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CheckInActiveBanner(
                session = current,
                onArrivedClick = {
                    manager.applyUserAction(CheckInUserAction.ARRIVED)
                    onDismiss()
                },
                onExtendClick = { manager.applyUserAction(CheckInUserAction.EXTEND, 15) },
                onStopClick = {
                    manager.applyUserAction(CheckInUserAction.CANCEL)
                    onDismiss()
                },
            )
        }
    }
}

/**
 * "Are you okay?" — the in-app twin of the warning notification's three buttons.
 *
 * Ported from iOS `CheckInWarningAlert` (`OSHI/CheckInView.swift:879-902`), which
 * offers the same three: I'm Safe (+15), Extend Time (+30), I've Arrived.
 *
 * Deliberately **not** dismissible by tapping outside or pressing back. This dialog
 * is the last thing standing between a delayed user and an emergency alert to their
 * contact; a stray tap that closes it without an answer would leave the user
 * believing they had responded. The only ways out are the three answers.
 */
@Composable
fun CheckInWarningDialog(
    onSafe: () -> Unit,
    onExtend: () -> Unit,
    onArrived: () -> Unit,
) {
    OshiAlertDialog(
        onDismissRequest = { /* answer required — see KDoc */ },
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text(stringResource(R.string.checkin_warning_title)) },
        text = { Text(stringResource(R.string.checkin_warning_message)) },
        // Three actions in one slot: Material's AlertDialog has only confirm and
        // dismiss, and dropping one of iOS's three answers to fit would remove an
        // off-ramp rather than a decoration.
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onSafe) {
                    Text(stringResource(R.string.checkin_warning_action_safe))
                }
                TextButton(onClick = onExtend) {
                    Text(stringResource(R.string.checkin_extend))
                }
                TextButton(onClick = onArrived) {
                    Text(stringResource(R.string.checkin_arrived))
                }
            }
        }
    )
}

/**
 * Check-In Message Bubble - Displays check-in messages in chat
 */
@Composable
fun CheckInMessageBubble(
    message: CheckInMessage,
    isFromCurrentUser: Boolean,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when (message.type) {
        CheckInMessageType.started -> MaterialTheme.colorScheme.primaryContainer
        CheckInMessageType.arrived -> OSHIGreen.copy(alpha = 0.2f)
        CheckInMessageType.warning -> Color(0xFFFFEB3B).copy(alpha = 0.3f)
        CheckInMessageType.emergency -> OSHIRed.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val icon = when (message.type) {
        CheckInMessageType.started -> Icons.Default.Shield
        CheckInMessageType.arrived -> Icons.Default.CheckCircle
        CheckInMessageType.warning -> Icons.Default.Warning
        CheckInMessageType.emergency -> Icons.Default.Error
        else -> Icons.Default.Info
    }

    val iconTint = when (message.type) {
        CheckInMessageType.started -> MaterialTheme.colorScheme.primary
        CheckInMessageType.arrived -> OSHIGreen
        CheckInMessageType.warning -> Color(0xFFFFA000)
        CheckInMessageType.emergency -> OSHIRed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    OshiCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(32.dp)
            )

            Text(
                stringResource(message.displayTitleRes),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )

            message.destinationAddress?.let { address ->
                Text(
                    stringResource(R.string.checkin_heading_to, address),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }

            message.transportMode?.let { mode ->
                Text(
                    stringResource(R.string.checkin_mode, mode.emoji, stringResource(mode.localizedNameRes)),
                    fontSize = 13.sp
                )
            }

            message.estimatedArrival?.let { eta ->
                Text(
                    stringResource(R.string.checkin_eta_time, formatTime(eta)),
                    fontSize = 13.sp
                )
            }

            message.emergencyData?.let { data ->
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    stringResource(R.string.checkin_emergency_data),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = OSHIRed
                )
                Text(
                    stringResource(R.string.checkin_battery, data.formattedBattery),
                    fontSize = 12.sp
                )
                Text(
                    stringResource(R.string.checkin_device, data.deviceName),
                    fontSize = 12.sp
                )
                Text(
                    stringResource(R.string.checkin_last_location, data.lastKnownLatitude.toString(), data.lastKnownLongitude.toString()),
                    fontSize = 12.sp
                )
            }

            Text(
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(message.timestamp)),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
