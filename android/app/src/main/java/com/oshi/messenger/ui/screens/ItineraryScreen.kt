package com.oshi.messenger.ui.screens

import android.location.Geocoder
import com.oshi.messenger.ui.theme.OshiCard
import android.location.Location
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.R
import com.oshi.messenger.data.model.RouteMode
import com.oshi.messenger.service.map.PlatformGeocoderGate
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.viewmodel.MapViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * ItineraryScreen — Full itinerary planner with origin + destination.
 *
 * Matches iOS itinerary panel:
 * - Two input fields: origin (green dot, defaults to "My location") + destination (red pin)
 * - Both fields have autocomplete dropdown from Geocoder
 * - Swap button to reverse origin/destination
 * - "Use my location" GPS button on origin
 * - Car/Walk/Bike mode selector with real distance + time via OSRM
 * - "Go" button starts turn-by-turn navigation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItineraryScreen(
    onBack: () -> Unit,
    onStartNavigation: () -> Unit,
    viewModel: MapViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val routeMode by viewModel.routeMode.collectAsState()
    val activeRoute by viewModel.activeRoute.collectAsState()
    val userLocation by viewModel.userLocation.collectAsState()

    // Origin state
    var originText by remember { mutableStateOf("My location") }
    var originLat by remember { mutableStateOf<Double?>(null) }
    var originLon by remember { mutableStateOf<Double?>(null) }
    var originSuggestions by remember { mutableStateOf<List<GeoSuggestion>>(emptyList()) }
    var showOriginSuggestions by remember { mutableStateOf(false) }
    var originUsingGPS by remember { mutableStateOf(true) }

    // Destination state
    var destText by remember { mutableStateOf("") }
    var destLat by remember { mutableStateOf<Double?>(null) }
    var destLon by remember { mutableStateOf<Double?>(null) }
    var destSuggestions by remember { mutableStateOf<List<GeoSuggestion>>(emptyList()) }
    var showDestSuggestions by remember { mutableStateOf(false) }

    var isCalculating by remember { mutableStateOf(false) }

    // 🔒 P0 FIX (map audit 2026-08-23 §5, "the single highest-priority item I am
    // handing on"): `android.location.Geocoder` is a GOOGLE NETWORK SERVICE, not
    // an on-device library. With "Offline maps only" ON, every keystroke in these
    // two fields was still shipping the typed address to Google — the one thing
    // that switch exists to prevent. True while the gate is refusing a lookup.
    var addressLookupBlocked by remember { mutableStateOf(false) }

    // Use GPS location for origin
    LaunchedEffect(userLocation) {
        if (originUsingGPS) {
            userLocation?.let {
                originLat = it.latitude
                originLon = it.longitude
            }
        }
    }

    // (N8) A tap on an empty area of the map prefills the destination here —
    // consumed once so leaving and reopening Itinerary later doesn't replay
    // an old tap. No reverse geocoding: that would be another network call
    // for an address label alone, on a screen whose own geocoder gate (below)
    // exists specifically to respect "Offline maps only".
    val pendingDestination by viewModel.pendingItineraryDestination.collectAsState()
    LaunchedEffect(pendingDestination) {
        pendingDestination?.let { (lat, lon) ->
            destLat = lat
            destLon = lon
            destText = context.getString(R.string.itinerary_dropped_pin)
            viewModel.consumePendingItineraryDestination()
        }
    }

    // Geocode search function.
    //
    // Every exit path clears the caller's suggestion list. A dropdown left
    // standing from an earlier query is a stale value, and "silently showing a
    // stale value" is the second failure mode this fix is against.
    fun searchAddress(query: String, onResults: (List<GeoSuggestion>) -> Unit) {
        when (PlatformGeocoderGate.decide(context, query)) {
            PlatformGeocoderGate.Decision.Idle -> {
                addressLookupBlocked = false
                onResults(emptyList())
                return
            }
            // The Geocoder is NOT constructed on this path. That is the fix.
            PlatformGeocoderGate.Decision.BlockedOfflineOnly -> {
                addressLookupBlocked = true
                onResults(emptyList())
                return
            }
            PlatformGeocoderGate.Decision.Geocode -> {
                addressLookupBlocked = false
            }
        }
        coroutineScope.launch {
            val results = withContext(Dispatchers.IO) {
                try {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(query, 5)
                    addresses?.map { addr ->
                        GeoSuggestion(
                            title = addr.featureName ?: addr.getAddressLine(0)?.split(",")?.firstOrNull() ?: "Unknown",
                            subtitle = addr.getAddressLine(0) ?: "",
                            lat = addr.latitude,
                            lon = addr.longitude
                        )
                    } ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }
            onResults(results)
        }
    }

    // Calculate route when both points are set
    fun calculateRoute() {
        val oLat = if (originUsingGPS) userLocation?.latitude else originLat
        val oLon = if (originUsingGPS) userLocation?.longitude else originLon
        val dLat = destLat
        val dLon = destLon

        if (oLat != null && oLon != null && dLat != null && dLon != null) {
            isCalculating = true
            viewModel.calculateRouteFromTo(oLat, oLon, dLat, dLon)
            // Route calculation is async — observe activeRoute
        }
    }

    // Recalculate when route mode changes
    LaunchedEffect(routeMode) {
        if (destLat != null && destLon != null) {
            calculateRoute()
        }
    }

    // Stop calculating indicator when route arrives
    LaunchedEffect(activeRoute) {
        if (activeRoute != null) {
            isCalculating = false
        }
    }

    // 🔧 P1 FIX (device walkthrough #2, 2026-08-13): routing can now REFUSE
    // (MapViewModel.calculateRouteFromTo) instead of fabricating a straight line.
    // Without this the spinner would simply never stop, which is its own kind of
    // lie — so the refusal has to land somewhere the user can see.
    val routeError by viewModel.routeError.collectAsState()
    LaunchedEffect(routeError) {
        if (routeError != null) isCalculating = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.itinerary_plan_route), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.accessibility_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // ── Origin + Destination Fields ──
            OshiCard(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Origin field
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Green dot
                        Surface(
                            shape = CircleShape,
                            color = OSHIGreen,
                            modifier = Modifier.size(12.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(12.dp))

                        OutlinedTextField(
                            value = originText,
                            onValueChange = { newText ->
                                originText = newText
                                originUsingGPS = false
                                showOriginSuggestions = true
                                searchAddress(newText) { originSuggestions = it }
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.itinerary_origin)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = OSHIPurple,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        originUsingGPS = true
                                        originText = "My location"
                                        originLat = userLocation?.latitude
                                        originLon = userLocation?.longitude
                                        showOriginSuggestions = false
                                        if (destLat != null) calculateRoute()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.MyLocation,
                                        contentDescription = stringResource(R.string.itinerary_use_my_location),
                                        tint = if (originUsingGPS) OSHIPurple else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        )
                    }

                    // Origin suggestions dropdown
                    AnimatedVisibility(visible = showOriginSuggestions && originSuggestions.isNotEmpty()) {
                        Column(modifier = Modifier.padding(start = 24.dp)) {
                            originSuggestions.forEach { suggestion ->
                                SuggestionRow(suggestion) {
                                    originText = suggestion.title
                                    originLat = suggestion.lat
                                    originLon = suggestion.lon
                                    originUsingGPS = false
                                    showOriginSuggestions = false
                                    if (destLat != null) calculateRoute()
                                }
                            }
                        }
                    }

                    // Swap + divider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Divider(modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                // Swap origin and destination
                                val tmpText = originText
                                val tmpLat = originLat
                                val tmpLon = originLon
                                val tmpGPS = originUsingGPS

                                originText = destText
                                originLat = destLat
                                originLon = destLon
                                originUsingGPS = false

                                destText = if (tmpGPS) "My location" else tmpText
                                destLat = tmpLat ?: userLocation?.latitude
                                destLon = tmpLon ?: userLocation?.longitude

                                if (originLat != null && destLat != null) calculateRoute()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.SwapVert,
                                contentDescription = stringResource(R.string.itinerary_swap),
                                tint = OSHIPurple,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Divider(modifier = Modifier.weight(1f))
                    }

                    // Destination field
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Red pin
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = Color(0xFFE53935),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(11.dp))

                        OutlinedTextField(
                            value = destText,
                            onValueChange = { newText ->
                                destText = newText
                                showDestSuggestions = true
                                searchAddress(newText) { destSuggestions = it }
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.itinerary_destination)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = OSHIPurple,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            trailingIcon = {
                                if (destText.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            destText = ""
                                            destLat = null
                                            destLon = null
                                            showDestSuggestions = false
                                            viewModel.stopNavigation()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.accessibility_clear),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        )
                    }

                    // Destination suggestions dropdown
                    AnimatedVisibility(visible = showDestSuggestions && destSuggestions.isNotEmpty()) {
                        Column(modifier = Modifier.padding(start = 24.dp)) {
                            destSuggestions.forEach { suggestion ->
                                SuggestionRow(suggestion) {
                                    destText = suggestion.title
                                    destLat = suggestion.lat
                                    destLon = suggestion.lon
                                    showDestSuggestions = false
                                    calculateRoute()
                                }
                            }
                        }
                    }
                }
            }

            // ── Offline-only refusal for address lookup ──
            // Stated, not silent. Without this the field simply stops producing
            // suggestions, which is indistinguishable from a broken text box.
            if (addressLookupBlocked) {
                Spacer(modifier = Modifier.height(12.dp))
                OfflineAddressLookupNotice()
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Transport Mode Selector ──
            OshiCard(
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TransportModeButton(
                        icon = Icons.Default.DirectionsCar,
                        label = stringResource(R.string.itinerary_mode_drive),
                        selected = routeMode == RouteMode.DRIVING,
                        onClick = { viewModel.setRouteMode(RouteMode.DRIVING) }
                    )
                    TransportModeButton(
                        icon = Icons.Default.DirectionsWalk,
                        label = stringResource(R.string.itinerary_mode_walk),
                        selected = routeMode == RouteMode.WALKING,
                        onClick = { viewModel.setRouteMode(RouteMode.WALKING) }
                    )
                    TransportModeButton(
                        icon = Icons.Default.DirectionsBike,
                        label = stringResource(R.string.itinerary_mode_bike),
                        selected = routeMode == RouteMode.CYCLING,
                        onClick = { viewModel.setRouteMode(RouteMode.CYCLING) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Route Result ──
            if (isCalculating) {
                OshiCard(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = OSHIPurple,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.itinerary_calculating), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            activeRoute?.let { route ->
                // __ITINERARY_HAD_NO_MAP_2026_11_03__ Anatole: "lors du lancement
                // d'un itinéraire, la carte ne s'affiche pas et montre seulement
                // la direction."
                //
                // Exactly right, and not a rendering failure: this screen never
                // drew a map. It showed the origin and destination cards, the
                // distance, the duration and the list of manoeuvres — the
                // directions — and nothing else. `MapNavigationScreen` has the
                // map, but that is turn-by-turn, which is a different screen
                // reached a different way; planning a route landed here and here
                // had no picture of the route at all.
                RoutePreviewMap(route)

                OshiCard(
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        // Distance + Duration
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    route.distanceFormatted,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = OSHIPurple
                                )
                                Text(
                                    stringResource(R.string.itinerary_distance),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    route.durationFormatted,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = OSHIPurple
                                )
                                Text(
                                    stringResource(R.string.itinerary_duration),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // GO button
                        Button(
                            onClick = { onStartNavigation() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = OSHIGreen),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                Icons.Default.Navigation,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Go",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                        }

                        // Maneuvers preview
                        if (route.maneuvers.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Divider()
                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                stringResource(R.string.itinerary_steps, route.maneuvers.size),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            route.maneuvers.take(8).forEachIndexed { index, maneuver ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = OSHIPurple.copy(alpha = 0.1f),
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                "${index + 1}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = OSHIPurple
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        maneuver.instruction,
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (maneuver.distance > 0) {
                                        Text(
                                            formatRouteDistance(maneuver.distance),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (route.maneuvers.size > 8) {
                                Text(
                                    stringResource(R.string.itinerary_more_steps, route.maneuvers.size - 8),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Routing declined: no graph offline AND the online engine could not
            // route it either. Stating that is the whole point of the fix — the
            // previous build silently drew a 3-step 8991 km straight line here and
            // offered "Start Navigation" on top of it.
            val currentRouteError = routeError
            if (activeRoute == null && !isCalculating && currentRouteError != null) {
                Spacer(modifier = Modifier.height(40.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        currentRouteError,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.offline_map_routing_unavailable_body),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Placeholder when no route
            if (activeRoute == null && !isCalculating && currentRouteError == null) {
                Spacer(modifier = Modifier.height(40.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Directions,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.itinerary_enter_destination),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Helper composables ──

/**
 * Shown when [PlatformGeocoderGate] refuses an address lookup because "Offline
 * maps only" is on.
 *
 * Uses the M3 `errorContainer` / `onErrorContainer` pair rather than a brand
 * tint: the contrast bug "brand-coloured ink on a brand-tinted background" has
 * been found four times in this app's map screens already, and this pair is
 * guaranteed legible in both light and dark schemes.
 */
@Composable
private fun OfflineAddressLookupNotice() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    stringResource(R.string.itinerary_address_lookup_offline_only),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.itinerary_address_lookup_offline_only_body),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

data class GeoSuggestion(
    val title: String,
    val subtitle: String,
    val lat: Double,
    val lon: Double
)

@Composable
private fun SuggestionRow(suggestion: GeoSuggestion, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                suggestion.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                suggestion.subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TransportModeButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) OSHIPurple else Color.Transparent,
        modifier = Modifier.padding(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatRouteDistance(meters: Double): String {
    return if (meters >= 1000) {
        String.format("%.1f km", meters / 1000.0)
    } else {
        String.format("%.0f m", meters)
    }
}

/**
 * __ITINERARY_HAD_NO_MAP_2026_11_03__ The route, drawn.
 *
 * Deliberately the SAME osmdroid setup as `MapNavigationScreen`: the
 * first-party tile source rather than osmdroid's MAPNIK default (which 403s
 * this app), and the cache-first offline provider that honours "offline maps
 * only". Two maps in one app disagreeing about where tiles come from is how
 * one of them ends up blank.
 *
 * A preview, not a navigator: fixed height inside the scrolling column, no
 * follow-the-user behaviour, and `setMultiTouchControls` so the route can be
 * inspected without leaving the planning screen.
 */
@androidx.compose.runtime.Composable
private fun RoutePreviewMap(route: com.oshi.messenger.data.model.MapRoute) {
    if (route.points.isEmpty()) return
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp)),
        factory = { ctx ->
            org.osmdroid.views.MapView(ctx).apply {
                // (N2) The base layer chosen on the Map tab, here too.
                val layer = com.oshi.messenger.service.map.MapLayer.load(ctx)
                setTileSource(layer.source)
                runCatching {
                    val storage = com.oshi.messenger.service.MapStorageManager(ctx)
                    tileProvider =
                        com.oshi.messenger.service.map.OshiOfflineTileProvider.create(ctx, storage, layer)
                }
                setMultiTouchControls(true)
            }
        },
        update = { mapView ->
            mapView.overlays.clear()

            val geo = route.points.map { org.osmdroid.util.GeoPoint(it.lat, it.lon) }
            mapView.overlays.add(
                org.osmdroid.views.overlay.Polyline(mapView).apply {
                    setPoints(geo)
                    outlinePaint.strokeWidth = 12f
                    outlinePaint.color = android.graphics.Color.parseColor("#5616EC")
                }
            )
            mapView.overlays.add(
                org.osmdroid.views.overlay.Marker(mapView).apply {
                    position = geo.first()
                    setAnchor(
                        org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                        org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM
                    )
                }
            )
            if (geo.size >= 2) {
                mapView.overlays.add(
                    org.osmdroid.views.overlay.Marker(mapView).apply {
                        position = geo.last()
                        setAnchor(
                            org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                            org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM
                        )
                    }
                )
            }

            // Frame the WHOLE route rather than centring on one end: a preview
            // that opens zoomed to the origin shows the same blank street for
            // every itinerary and answers nothing.
            runCatching {
                val box = org.osmdroid.util.BoundingBox.fromGeoPointsSafe(geo)
                mapView.zoomToBoundingBox(box.increaseByScale(1.25f), false)
            }.onFailure {
                mapView.controller.setZoom(14.0)
                mapView.controller.setCenter(geo.first())
            }
            mapView.invalidate()
        }
    )
}
