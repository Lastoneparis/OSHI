package com.oshi.messenger.ui.screens

import android.location.Location
import com.oshi.messenger.ui.theme.OshiFilterChip
import com.oshi.messenger.ui.theme.OshiCard
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.oshi.messenger.R
import com.oshi.messenger.data.model.MapRoute
import com.oshi.messenger.data.model.RouteManeuver
import com.oshi.messenger.data.model.RouteMode
import com.oshi.messenger.service.LiveNavigationEngine
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.brandInk
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.viewmodel.MapViewModel
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

/**
 * MapNavigationScreen — turn-by-turn navigation overlay.
 *
 * Shows the active route on a map with maneuver instructions,
 * distance/time remaining, and route mode selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapNavigationScreen(
    onBack: () -> Unit,
    viewModel: MapViewModel = hiltViewModel()
) {
    val activeRoute by viewModel.activeRoute.collectAsState()
    val routeMode by viewModel.routeMode.collectAsState()
    val userLocation by viewModel.userLocation.collectAsState()
    val isNavigating by viewModel.isNavigating.collectAsState()
    val vmManeuverIndex by viewModel.currentManeuverIndex.collectAsState()

    val route = activeRoute
    val context = LocalContext.current

    // ── Live navigation engine ──
    // Recreated whenever the route identity changes; dropped once navigation
    // is stopped. All live state (current step, distance to next, ETA,
    // off-route, arrival) flows from this engine — the view layer is
    // otherwise stateless.
    val engine: LiveNavigationEngine? = remember(route?.distance, route?.maneuvers?.size, isNavigating) {
        if (route != null && isNavigating) LiveNavigationEngine(route) else null
    }

    // Optional TTS — wired only when the platform provides it. No-op otherwise.
    val tts = remember { TextToSpeechHolder(context) }
    DisposableEffect(Unit) {
        tts.init()
        onDispose { tts.shutdown() }
    }

    // Feed engine on every location update.
    LaunchedEffect(userLocation, engine) {
        val loc = userLocation
        if (engine != null && loc != null) engine.updateLocation(loc)
    }

    // Observe live engine state — falls back to ViewModel's static index
    // when engine is null (e.g. before navigation starts).
    val liveCurrentStep = engine?.currentStepIndex?.collectAsState()?.value
    val liveDistanceToNext = engine?.distanceToNextManeuver?.collectAsState()?.value
    val liveRemainingDist = engine?.remainingDistance?.collectAsState()?.value
    val liveEtaSec = engine?.etaSeconds?.collectAsState()?.value
    val liveOffRoute = engine?.isOffRoute?.collectAsState()?.value ?: false
    val liveArrived = engine?.hasArrived?.collectAsState()?.value ?: false
    val voicePrompt = engine?.voicePrompt?.collectAsState()?.value

    LaunchedEffect(voicePrompt) {
        val text = voicePrompt
        if (!text.isNullOrEmpty()) {
            tts.speak(text)
            engine?.consumeVoicePrompt()
        }
    }

    val currentManeuverIndex = liveCurrentStep ?: vmManeuverIndex

    if (route == null) {
        // No active route — show placeholder
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.map_nav_title), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.key_backup_hide))
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Navigation,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.map_nav_no_route),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.map_nav_select_dest),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    // Pre-resolve strings for non-composable AndroidView context
    val startLabel = stringResource(R.string.map_nav_start_marker)
    val destLabel = stringResource(R.string.map_nav_destination)
    val youLabel = stringResource(R.string.map_nav_you)

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    // ── Camera follow ──
    // While navigating, auto-pan to the user's current location at every
    // tick. Hook is cheap; OSMDroid coalesces near-duplicate animateTo.
    LaunchedEffect(userLocation, isNavigating) {
        val mv = mapViewRef
        val loc = userLocation
        if (isNavigating && mv != null && loc != null) {
            mv.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
            // Bias zoom in slightly when navigating for car-GPS feel.
            if (mv.zoomLevelDouble < 17.0) mv.controller.setZoom(17.0)
        }
    }

    // ── One-shot snap to user when navigation starts ──
    // The follow effect above only fires on userLocation changes — if the
    // user is stationary when they tap "Start", the camera stays on the
    // route preview (often centered on the destination during the preview
    // step) instead of jumping to the user's current position. This effect
    // explicitly snaps to user-location the instant `isNavigating` flips
    // on, so the GPS view "starts from the point of starting" the way a
    // real car GPS does.
    LaunchedEffect(isNavigating) {
        if (isNavigating) {
            val mv = mapViewRef ?: return@LaunchedEffect
            val target = userLocation?.let { GeoPoint(it.latitude, it.longitude) }
                ?: route?.points?.firstOrNull()?.let { GeoPoint(it.lat, it.lon) }
            if (target != null) {
                mv.controller.setCenter(target)
                mv.controller.setZoom(17.0)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Map with route ──
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    // First-party proxy, not osmdroid's MAPNIK default (= OSM's
                    // volunteer servers, which 403 this app). iOS: OfflineFirstTileOverlay.swift:167.
                    // (N2) The base layer chosen on the Map tab, here too.
                    val layer = com.oshi.messenger.service.map.MapLayer.load(ctx)
                    setTileSource(layer.source)
                    // Cache-first tile provider: serves PNG tiles from the
                    // downloaded region cache before hitting the network.
                    // Honors the "Offline maps only" SharedPreference.
                    runCatching {
                        val storage = com.oshi.messenger.service.MapStorageManager(ctx)
                        tileProvider = com.oshi.messenger.service.map.OshiOfflineTileProvider.create(ctx, storage, layer)
                    }
                    setMultiTouchControls(true)
                    controller.setZoom(14.0)

                    // Center on route START point (origin)
                    if (route.points.isNotEmpty()) {
                        val start = route.points.first()
                        controller.setCenter(GeoPoint(start.lat, start.lon))
                    }
                    mapViewRef = this
                }
            },
            update = { mapView ->
                mapView.overlays.clear()

                // Route polyline
                val polyline = Polyline().apply {
                    route.points.forEach { pt ->
                        addPoint(GeoPoint(pt.lat, pt.lon))
                    }
                    outlinePaint.color = android.graphics.Color.parseColor("#5616EC")
                    outlinePaint.strokeWidth = 10f
                    outlinePaint.isAntiAlias = true
                }
                mapView.overlays.add(polyline)

                // Start marker
                if (route.points.isNotEmpty()) {
                    val startMarker = Marker(mapView).apply {
                        position = GeoPoint(route.points.first().lat, route.points.first().lon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = startLabel
                    }
                    mapView.overlays.add(startMarker)
                }

                // End marker
                if (route.points.size >= 2) {
                    val endMarker = Marker(mapView).apply {
                        position = GeoPoint(route.points.last().lat, route.points.last().lon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = destLabel
                    }
                    mapView.overlays.add(endMarker)
                }

                // User location marker
                userLocation?.let { loc ->
                    val userMarker = Marker(mapView).apply {
                        position = GeoPoint(loc.latitude, loc.longitude)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = youLabel
                    }
                    mapView.overlays.add(userMarker)
                }

                mapView.invalidate()
            }
        )

        // ── Top bar: Back + Route mode selector ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button
            FloatingActionButton(
                onClick = {
                    viewModel.stopNavigation()
                    onBack()
                },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.key_backup_hide), modifier = Modifier.size(22.dp))
            }

            // Route mode selector
            OshiCard(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Row(modifier = Modifier.padding(4.dp)) {
                    RouteModeChip(
                        icon = Icons.Default.DirectionsCar,
                        label = stringResource(R.string.map_nav_drive),
                        selected = routeMode == RouteMode.DRIVING,
                        onClick = { viewModel.setRouteMode(RouteMode.DRIVING) }
                    )
                    RouteModeChip(
                        icon = Icons.Default.DirectionsWalk,
                        label = stringResource(R.string.map_nav_walk),
                        selected = routeMode == RouteMode.WALKING,
                        onClick = { viewModel.setRouteMode(RouteMode.WALKING) }
                    )
                    RouteModeChip(
                        icon = Icons.Default.DirectionsBike,
                        label = stringResource(R.string.map_nav_bike),
                        selected = routeMode == RouteMode.CYCLING,
                        onClick = { viewModel.setRouteMode(RouteMode.CYCLING) }
                    )
                }
            }
        }

        // ── Live maneuver banner (navigation mode) ──
        // The "next" maneuver is the one *after* the current step, since the
        // current step is treated as completed once we've advanced to it.
        if (isNavigating && route.maneuvers.isNotEmpty()) {
            val targetIdx = (currentManeuverIndex + 1).coerceAtMost(route.maneuvers.size - 1)
            val target = route.maneuvers.getOrNull(targetIdx)
            // Pre-resolve all banner strings — used inside `when` arms which
            // can't directly call stringResource within an inline lambda chain.
            val arrivedTitle = stringResource(R.string.map_nav_arrived)
            val arrivedMessage = stringResource(R.string.map_nav_arrived_message)
            val offRouteTitle = stringResource(R.string.map_nav_off_route)
            val recalculatingMessage = stringResource(R.string.map_nav_recalculating)
            val nowLabel = stringResource(R.string.map_nav_now)

            val (bannerColor, bannerIcon, bannerText, bannerSub) = when {
                liveArrived -> Quad(OSHIGreen, Icons.Default.Flag, arrivedTitle, arrivedMessage)
                liveOffRoute -> Quad(Color(0xFFE08A00), Icons.Default.Warning, offRouteTitle, recalculatingMessage)
                target != null -> {
                    val distance = liveDistanceToNext
                    val instruction = target.instruction
                    val sub = if (distance != null && distance >= 50.0) {
                        stringResource(R.string.map_nav_in_distance, LiveNavigationEngine.formatDistance(distance))
                    } else nowLabel
                    Quad(OSHIPurple, maneuverIcon(target.type), instruction, sub)
                }
                else -> Quad(OSHIPurple, Icons.Default.ArrowUpward, "", "")
            }
            OshiCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 72.dp)
                    .align(Alignment.TopCenter),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = bannerColor),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        bannerIcon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            bannerSub,
                            color = Color.White.copy(alpha = 0.85f),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            bannerText,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }
                }
            }
        }

        // ── Bottom panel: route summary + start/stop button ──
        OshiCard(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 20.dp, bottom = 24.dp)
            ) {
                // Route summary — uses live remaining values from the engine
                // when navigation is active, so the user sees decreasing
                // numbers as they progress.
                val displayDistance = if (isNavigating && liveRemainingDist != null)
                    LiveNavigationEngine.formatDistance(liveRemainingDist) else route.distanceFormatted
                val displayDuration = if (isNavigating && liveEtaSec != null) {
                    val mins = (liveEtaSec / 60).toInt()
                    if (mins >= 60) "${mins / 60}h ${mins % 60}min" else "${mins.coerceAtLeast(1)} min"
                } else route.durationFormatted

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RouteStat(
                        label = stringResource(R.string.map_nav_distance),
                        value = displayDistance
                    )
                    RouteStat(
                        label = stringResource(R.string.map_nav_duration),
                        value = displayDuration
                    )
                    RouteStat(
                        label = stringResource(R.string.map_nav_mode),
                        value = when (routeMode) {
                            RouteMode.DRIVING -> stringResource(R.string.map_nav_mode_driving)
                            RouteMode.WALKING -> stringResource(R.string.map_nav_mode_walking)
                            RouteMode.CYCLING -> stringResource(R.string.map_nav_mode_cycling)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Start / Stop navigation button
                if (isNavigating) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { viewModel.nextManeuver() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = OSHIPurple)
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.map_nav_next_step))
                        }
                        OutlinedButton(
                            onClick = { viewModel.stopNavigation() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.map_nav_stop))
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { viewModel.startNavigation() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = OSHIGreen)
                        ) {
                            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.map_nav_start), fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.stopNavigation()
                                onBack()
                            }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // Maneuver list — shown both before and during navigation. While
                // navigating, the current step is highlighted, past steps are
                // dimmed, and upcoming steps are subdued.
                if (route.maneuvers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.map_nav_steps),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    route.maneuvers.forEachIndexed { index, maneuver ->
                        val isCurrent = isNavigating && index == currentManeuverIndex
                        val isPast = isNavigating && index < currentManeuverIndex
                        val rowAlpha = when {
                            isPast -> 0.35f
                            isCurrent -> 1f
                            else -> 0.85f
                        }
                        // 0.12 wash + brand ink measured ~2.2:1 in dark; raise the
                        // container to Oshi.ContainerTintAlpha and take the ink off brand.
                        val rowBg = if (isCurrent) OSHIPurple.copy(alpha = Oshi.ContainerTintAlpha) else Color.Transparent
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBg, RoundedCornerShape(8.dp))
                                .padding(vertical = 6.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                maneuverIcon(maneuver.type),
                                contentDescription = null,
                                tint = if (isCurrent) brandInk() else brandInk().copy(alpha = rowAlpha),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    maneuver.instruction,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = rowAlpha)
                                )
                            }
                            if (maneuver.distance > 0) {
                                Text(
                                    formatDistance(maneuver.distance),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = rowAlpha)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteModeChip(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    OshiFilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        leadingIcon = {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        },
        modifier = Modifier.padding(horizontal = 2.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = OSHIPurple,
            selectedLabelColor = Color.White,
            selectedLeadingIconColor = Color.White
        )
    )
}

@Composable
private fun RouteStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = OSHIPurple
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun maneuverIcon(type: String): ImageVector = when {
    type.contains("left") -> Icons.Default.ArrowBack
    type.contains("right") -> Icons.Default.ArrowForward
    type.contains("arrive") -> Icons.Default.Flag
    type.contains("depart") -> Icons.Default.NearMe
    type.contains("u-turn") -> Icons.Default.Undo
    else -> Icons.Default.ArrowUpward
}

private fun formatDistance(meters: Double): String {
    return if (meters >= 1000) {
        String.format("%.1f km", meters / 1000.0)
    } else {
        String.format("%.0f m", meters)
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Helper types for the live navigation banner.
// ─────────────────────────────────────────────────────────────────────────

/** Tiny tuple type used to bundle the banner's color/icon/text/sub for one render. */
private data class Quad(
    val color: Color,
    val icon: ImageVector,
    val text: String,
    val sub: String
)

/**
 * Lifecycle-aware wrapper around [android.speech.tts.TextToSpeech] so we can
 * announce voice prompts when navigating without crashing on platforms /
 * test environments where TTS isn't available.
 */
private class TextToSpeechHolder(private val context: android.content.Context) {
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ready: Boolean = false

    fun init() {
        try {
            tts = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    ready = true
                    runCatching { tts?.language = Locale.getDefault() }
                }
            }
        } catch (_: Throwable) {
            // TTS unavailable — voice prompts silently disabled.
            ready = false
        }
    }

    fun speak(text: String) {
        if (!ready) return
        runCatching {
            tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "oshi-nav")
        }
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        ready = false
    }
}
