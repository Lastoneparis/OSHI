package com.oshi.messenger.ui.screens

import android.Manifest
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiDropdownMenu
import com.oshi.messenger.ui.theme.OshiModalBottomSheet
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.R
import com.oshi.messenger.data.model.MapPOI
import com.oshi.messenger.data.model.POICategory
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.theme.brandInk
import com.oshi.messenger.ui.viewmodel.MapViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/**
 * MapScreen — full-screen map with search bar, GPS button, POI pins, and route overlay.
 *
 * Uses OSMDroid (OpenStreetMap) for offline-capable map rendering.
 * Matches iOS MapScreen feature-for-feature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onNavigateToSearch: () -> Unit = {},
    onNavigateToRegions: () -> Unit = {},
    onNavigateToNavigation: () -> Unit = {},
    onNavigateToItinerary: () -> Unit = {},
    onNavigateToAIChat: () -> Unit = {},
    /**
     * __MESSAGE_THE_NODE_FROM_THE_MAP_2026_11_07__ Open the conversation for a
     * conversation key. Takes a KEY rather than a node number because the card
     * resolves which thread a node belongs to — a proved radio routes to its
     * contact's encrypted thread, an unproved one to the cleartext `lora!` one.
     */
    onOpenChat: (String) -> Unit = {},
    viewModel: MapViewModel = hiltViewModel(),
    // Contact shared location — show avatar on map
    contactName: String? = null,
    contactProfilePicture: ByteArray? = null,
    contactLat: Double? = null,
    contactLon: Double? = null,
    isLiveLocation: Boolean = false,
    liveSessionId: String? = null
) {
    val context = LocalContext.current
    val userLocation by viewModel.userLocation.collectAsState()
    val visiblePOIs by viewModel.visiblePOIs.collectAsState()
    // __THE_MAP_DECIDED_WHAT_YOU_SAW_2026_11_07__
    val showPOIs by viewModel.showPOIs.collectAsState()
    val selectedPOI by viewModel.selectedPOI.collectAsState()
    // __LORA_NODE_TAP_2026_11_05__ The radio whose card is open, if any.
    //
    // Local to the screen, not on the view model: it is a property of this visit
    // and nothing outside the map needs to read it. A node is NOT folded into
    // `selectedPOI` either — the POI sheet is built from a category, an address
    // and a Navigate button, and a radio has none of those. Faking one would put
    // "Unknown category" on screen and offer directions to a thing that moves.
    var selectedNode by remember { mutableStateOf<com.oshi.messenger.service.lora.LoRaMeshNode?>(null) }

    // (N8) The point a tap on empty map landed on, offered as a route rather
    // than acted on. A `MutableState` held by reference, not a `by` delegate:
    // the writer is `MapEventsOverlay`'s callback inside `AndroidView`'s
    // `factory`, which is not a composable scope and so cannot use the
    // delegate — it captures this object once and keeps writing to it, which
    // `remember` makes safe because it hands back the same object for the
    // life of the screen.
    val droppedPin = remember { mutableStateOf<GeoPoint?>(null) }
    val activeRoute by viewModel.activeRoute.collectAsState()
    val mapCenterLat by viewModel.mapCenterLat.collectAsState()
    val mapCenterLon by viewModel.mapCenterLon.collectAsState()
    val mapZoom by viewModel.mapZoom.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val hasInitialLocation by viewModel.hasInitialLocation.collectAsState()
    val followUserMode by viewModel.followUserMode.collectAsState()
    val userBearing by viewModel.userBearing.collectAsState()
    // Markers dropped after a category tap or natural-language category query
    // (Bug 2). Cleared by clearSearch.
    val categoryMarkers by viewModel.categoryMarkers.collectAsState()

    // __LORA_NODES_ON_THE_MAP_2026_10_28__ radios heard over LoRa, collected as state so
    // a position arriving on the air REDRAWS the map. Read from the manager's own
    // StateFlow rather than mirrored into the view model: there is one node table and a
    // second copy of it would drift from the peers list that shows the same radios.
    val loRaNodes by com.oshi.messenger.service.lora.MeshtasticManager
        .getInstance(LocalContext.current.applicationContext)
        .nodes.collectAsState()

    // Compass: only run the rotation-vector listener while follow mode is on.
    CompassHeadingProvider(enabled = followUserMode) { degrees ->
        viewModel.setUserBearing(degrees)
    }

    // Request location permission lazily when the Map tab opens
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            viewModel.requestUserLocation()
        }
    }

    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // OSMDroid configuration
    LaunchedEffect(Unit) {
        // A bare package name is not a valid tile-client identity: it carries no
        // way to contact the operator, so OSM answered every request with the
        // 403 "App is not following the tile usage policy" placeholder and the
        // whole Map tab rendered as hazard stripes. iOS sends a contactable UA —
        // OSHI/OfflineFirstTileOverlay.swift:168 — and we now match it, while
        // pointing at OSHI's own tile proxy rather than OSM's volunteer servers.
        Configuration.getInstance().userAgentValue =
            com.oshi.messenger.service.map.OshiTileSource.USER_AGENT
    }

    // Contact location for live updates
    var currentContactLat by remember { mutableStateOf(contactLat) }
    var currentContactLon by remember { mutableStateOf(contactLon) }

    // Live location polling
    LaunchedEffect(isLiveLocation, liveSessionId) {
        if (isLiveLocation && liveSessionId != null) {
            while (true) {
                kotlinx.coroutines.delay(5000) // Poll every 5 seconds
                val updated = viewModel.getLatestLiveCoordinate(liveSessionId)
                if (updated != null) {
                    currentContactLat = updated.first
                    currentContactLon = updated.second
                }
            }
        }
    }

    // Pre-render contact avatar bitmap
    val contactAvatarBitmap = remember(contactName, contactProfilePicture) {
        if (contactName != null || contactProfilePicture != null) {
            createContactAvatarBitmap(contactName ?: "?", contactProfilePicture, isLiveLocation)
        } else null
    }

    // Pre-resolved localized strings for non-Composable AndroidView callbacks.
    val youLocationLabel = stringResource(R.string.map_marker_your_location)
    val youCurrentPositionLabel = stringResource(R.string.map_marker_current_position)
    val contactFallbackLabel = stringResource(R.string.map_marker_contact)
    val liveLocationLabel = stringResource(R.string.map_marker_live_location)
    val sharedLocationLabel = stringResource(R.string.map_marker_shared_location)

    // Local UI state — search bottom sheet visibility (matches iOS .sheet)
    var showSearchSheet by remember { mutableStateOf(false) }

    // (N2, Anatole 13/11) Base layer: standard / terrain / satellite. Persisted,
    // so the navigation and itinerary maps open on the same one (MapLayer.load).
    var mapLayer by remember {
        mutableStateOf(com.oshi.messenger.service.map.MapLayer.load(context))
    }
    var showLayerMenu by remember { mutableStateOf(false) }
    val offlineOnly = remember {
        com.oshi.messenger.service.map.OshiOfflineTileProvider.isOfflineOnly(context)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Map View ──
        var mapViewRef by remember { mutableStateOf<MapView?>(null) }

        // Animate to user location when it first becomes available
        var didCenterOnUser by remember { mutableStateOf(false) }
        LaunchedEffect(hasInitialLocation, mapViewRef) {
            if (hasInitialLocation && !didCenterOnUser && mapViewRef != null) {
                didCenterOnUser = true
                // __MAP_OPENER_2026_09_23__ Opened ON a place (shared location,
                // check-in, address, map link): the GPS fix lands a moment later
                // and used to yank the camera back to the user, leaving the place
                // off-screen. The place wins; the locate button still recentres.
                if (selectedPOI == null) {
                    mapViewRef?.controller?.animateTo(GeoPoint(mapCenterLat, mapCenterLon))
                    mapViewRef?.controller?.setZoom(15.0)
                }
            }
        }

        // Pan + zoom to the selected POI exactly once per change. Lives here
        // (not inside AndroidView.update) so it doesn't race with follow-mode
        // recenter on every recompose. Matches iOS `mapView.setRegion` behavior.
        LaunchedEffect(selectedPOI, mapViewRef) {
            val poi = selectedPOI
            val map = mapViewRef
            if (poi != null && map != null) {
                map.controller.animateTo(GeoPoint(poi.lat, poi.lon))
                map.controller.setZoom(17.0)
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    // First-party proxy, not TileSourceFactory.MAPNIK (= OSM's
                    // volunteer servers). Mirrors iOS OfflineFirstTileOverlay.swift:167.
                    // (N2) STANDARD is still OshiTileSource.SOURCE; see MapLayer.
                    setTileSource(mapLayer.source)
                    // Cache-first tile provider: serves PNG tiles from the
                    // downloaded region cache before hitting the network.
                    // Honors the "Offline maps only" SharedPreference.
                    runCatching {
                        val storage = com.oshi.messenger.service.MapStorageManager(ctx)
                        tileProvider = com.oshi.messenger.service.map.OshiOfflineTileProvider.create(ctx, storage, mapLayer)
                    }
                    setMultiTouchControls(true)
                    // Limit max zoom to prevent black tiles — z=19 is OSM max, allow slight overzoom
                    maxZoomLevel = mapMaxZoom(mapLayer)
                    minZoomLevel = 3.0
                    isTilesScaledToDpi = true  // Better tile rendering at high DPI
                    controller.setZoom(mapZoom.toDouble())
                    controller.setCenter(GeoPoint(mapCenterLat, mapCenterLon))
                    // (N8) Added once, here in `factory` rather than every
                    // `update` — and added BEFORE any marker, so markers
                    // (added afterward, in `update`) sit on top of it in
                    // osmdroid's touch-dispatch order and keep first claim on
                    // their own taps. A tap that reaches this overlay is, by
                    // construction, a tap that hit nothing else: an empty
                    // area of the map.
                    //
                    // N8 asked to "propose a route and ask the mode of
                    // transport". It OFFERS: the tap raises the card below,
                    // and the card's button is what leaves for Itinerary
                    // (where the mode picker already lives,
                    // TransportModeButton). Navigating on the tap itself —
                    // which is what this first did — makes every stray tap
                    // on a map eject you from it, and a map is a surface
                    // people tap constantly without meaning anything by it.
                    // "Proposer" is the whole difference.
                    overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                            droppedPin.value = p
                            return true
                        }
                        override fun longPressHelper(p: GeoPoint): Boolean = false
                    }))
                    mapViewRef = this
                }
            },
            update = { mapView ->
                // ── GPS Follow mode ──
                // When enabled: keep map centered on user and rotate so heading is "up".
                // When disabled: leave orientation alone and reset to north-up.
                if (followUserMode) {
                    userLocation?.let { loc ->
                        mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                    }
                    // OSMDroid uses CCW degrees, sensor azimuth is CW from north → negate.
                    userBearing?.let { bearing ->
                        mapView.mapOrientation = -bearing
                    }
                } else if (mapView.mapOrientation != 0f) {
                    mapView.mapOrientation = 0f
                }

                // Update POI markers
                mapView.overlays.removeAll { it is Marker }
                // __RINGS_PILED_UP_2026_11_08__ The LoRa coverage rings are
                // `Polygon`s, and `Polygon` does NOT extend `Polyline`
                // (osmdroid 6.1.18: both extend `PolyOverlayWithIW`), so the
                // `removeAll { it is Polyline }` further down never touched
                // them. Every pass through this `update` added one more ring
                // per node on top of the last: translucent fills stacking into
                // an opaque disc, and an overlay list that only ever grew.
                // Cleared here, beside the markers they belong to, because
                // they are rebuilt in the same pass.
                mapView.overlays.removeAll { it is Polygon }

                // User location marker
                userLocation?.let { loc ->
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(loc.latitude, loc.longitude)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = youLocationLabel
                        snippet = youCurrentPositionLabel
                    }
                    mapView.overlays.add(marker)
                }

                // Contact shared location marker (with profile picture)
                val cLat = currentContactLat
                val cLon = currentContactLon
                if (cLat != null && cLon != null && contactAvatarBitmap != null) {
                    val contactMarker = Marker(mapView).apply {
                        position = GeoPoint(cLat, cLon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = contactName ?: contactFallbackLabel
                        snippet = if (isLiveLocation) liveLocationLabel else sharedLocationLabel
                        icon = BitmapDrawable(mapView.context.resources, contactAvatarBitmap)
                    }
                    mapView.overlays.add(contactMarker)
                }

                // Selected POI marker (from search or location message).
                // The pan/zoom is handled by a dedicated LaunchedEffect(selectedPOI)
                // outside the AndroidView update block — doing it here would race
                // with follow-mode and the initial-center effect on every recompose.
                selectedPOI?.let { poi ->
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(poi.lat, poi.lon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = poi.name
                        snippet = mapView.context.getString(poi.category.displayNameRes)
                        setOnMarkerClickListener { _, _ ->
                            viewModel.selectPOI(poi)
                            true
                        }
                    }
                    mapView.overlays.add(marker)
                }

                // __LORA_NODES_ON_THE_MAP_2026_10_28__ every radio we have heard a
                // POSITION from, as a pin — what Meshtastic shows and this map did not.
                //
                // The data was already here: `MeshtasticManager.nodes` carries
                // `latitude`/`longitude` filled in from POSITION_APP, and the LoRa screens
                // showed them as a distance. Nothing put them on the map, so a mesh of
                // radios around you was a list of names and metres instead of a picture.
                //
                // `hasPosition` is the gate, not a null check spelled out again: a node
                // with no GPS (a Heltec V3 has none) must be skipped, never drawn at 0,0.
                // Drawn BEFORE the POIs so a shop does not hide a radio.
                for (node in loRaNodes) {
                    if (!node.hasPosition) continue
                    val lat = node.latitude ?: continue
                    val lon = node.longitude ?: continue

                    // __LORA_REACH_RINGS_2026_11_06__ The estimated reach of a
                    // radio we hear DIRECTLY, drawn before its pin so the pin
                    // stays on top and keeps its tap.
                    //
                    // `LoRaCoverage` returns null for a relayed node, and that
                    // is the important case: when `hopsAway > 0` the RSSI we
                    // measured belongs to the LAST HOP, not to this node, so a
                    // ring of that size drawn here would be a confident circle
                    // built from a measurement of somebody else. No ring is the
                    // honest answer. See that file, and the shared test vectors
                    // it pins with iOS.
                    //
                    // Soft fill, dashed edge, no solid stroke: a crisp circle
                    // asserts "inside this line you are reachable", and radio
                    // coverage is not a circle. Someone standing just inside it
                    // in a dead zone would have trusted a line we invented.
                    com.oshi.messenger.service.lora.LoRaCoverage
                        .radiusMeters(node.rssi, node.hopsAway)?.let { radiusM ->
                            val opacity = com.oshi.messenger.service.lora.LoRaCoverage
                                .fillOpacity(node.rssi, node.hopsAway)
                            val ring = Polygon(mapView).apply {
                                points = Polygon.pointsAsCircle(GeoPoint(lat, lon), radiusM)
                                fillPaint.color = android.graphics.Color.argb(
                                    (opacity * 255).toInt().coerceIn(0, 255),
                                    0x7B, 0x3F, 0xF2   // OSHI brand violet
                                )
                                outlinePaint.color = android.graphics.Color.argb(
                                    115, 0x7B, 0x3F, 0xF2
                                )
                                outlinePaint.strokeWidth = 2f
                                outlinePaint.pathEffect =
                                    android.graphics.DashPathEffect(floatArrayOf(10f, 14f), 0f)
                                // The ring is scenery, not a control: swallowing
                                // a tap here would steal it from the pin and
                                // from any POI underneath.
                                setOnClickListener { _, _, _ -> false }
                            }
                            mapView.overlays.add(ring)
                        }

                    val marker = Marker(mapView).apply {
                        position = GeoPoint(lat, lon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = node.longName.ifEmpty {
                            node.shortName.ifEmpty { "!%08x".format(node.num.toLong()) }
                        }
                        snippet = mapView.context.getString(R.string.lora_node_on_map)
                        // __LORA_NODE_TAP_2026_11_05__ The POI markers below have had a
                        // click listener all along; this one did not, so a radio fell
                        // back to osmdroid's default bubble — a name and nothing else.
                        // The map could say a radio was THERE and not one useful thing
                        // about it: not when it was last heard, not how well, not
                        // whether it is one hop away or being relayed.
                        //
                        // `true` consumes the tap so the default info window does not
                        // also open behind the card.
                        setOnMarkerClickListener { _, _ ->
                            viewModel.selectPOI(null)
                            selectedNode = node
                            true
                        }
                    }
                    mapView.overlays.add(marker)
                }

                // POI markers
                for (poi in if (showPOIs) visiblePOIs else emptyList()) {
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(poi.lat, poi.lon)
                        // Centre-anchored: a badge marks its point,
                        // a pin hangs above it.
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = poi.name
                        snippet = mapView.context.getString(poi.category.displayNameRes)
                        // __POI_PINS_WERE_OSMDROID_DEFAULTS_2026_11_07__ (Anatole N4:
                        // "an enormous number of points appear, forming a line". His
                        // screenshot settles it: no LoRa attached, and the cluster
                        // follows him — because these are loaded AROUND his position.)
                        //
                        // No `icon` was set, so every one took osmdroid's DEFAULT
                        // marker: a full-size green pin with a white pointer. Fifty of
                        // those, on data that follows roads, is the line he
                        // photographed. The category-search results below already draw
                        // a small tinted badge; using the same bitmap here makes an
                        // ambient layer read as ambient instead of shouting over the
                        // map. Same tap target, same sheet.
                        icon = BitmapDrawable(
                            mapView.context.resources,
                            createCategoryMarkerBitmap(poi.category)
                        )
                        setOnMarkerClickListener { _, _ ->
                            viewModel.selectPOI(poi)
                            true
                        }
                    }
                    mapView.overlays.add(marker)
                }

                // Category-search markers (Bug 2): one circular badge per result,
                // tinted with the brand color. Tapping behaves like any other POI
                // — selectPOI opens the bottom sheet with name / distance / Navigate.
                for (poi in categoryMarkers) {
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(poi.lat, poi.lon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = poi.name
                        snippet = mapView.context.getString(poi.category.displayNameRes)
                        icon = BitmapDrawable(
                            mapView.context.resources,
                            createCategoryMarkerBitmap(poi.category)
                        )
                        setOnMarkerClickListener { _, _ ->
                            viewModel.selectPOI(poi)
                            true
                        }
                    }
                    mapView.overlays.add(marker)
                }

                // Route polyline
                activeRoute?.let { route ->
                    mapView.overlays.removeAll { it is Polyline }
                    val polyline = Polyline().apply {
                        route.points.forEach { pt ->
                            addPoint(GeoPoint(pt.lat, pt.lon))
                        }
                        outlinePaint.color = android.graphics.Color.parseColor("#5616EC")
                        outlinePaint.strokeWidth = 8f
                    }
                    mapView.overlays.add(polyline)
                }

                mapView.invalidate()
            }
        )

        // ── Search Bar Overlay ──
        // 🔧 P2 FIX (device walkthrough #2, 2026-08-13): the Map screen applied
        // ZERO status-bar inset — the only screen in the app with the opposite
        // defect to the double-inset bug fixed elsewhere — so the search pill
        // collided with the clock. The map canvas itself deliberately stays
        // edge-to-edge (tiles behind the status bar are correct); only the
        // floating chrome moves down.
        OshiCard(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .shadow(8.dp, RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showSearchSheet = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = stringResource(R.string.map_search_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (searchQuery.isNotEmpty()) searchQuery else stringResource(R.string.map_search_bar_placeholder),
                    color = if (searchQuery.isNotEmpty())
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // (N2) Base layer picker, beside the POI toggle for the same
                // reason: it is a property of what you are looking at.
                Box {
                    IconButton(
                        onClick = { showLayerMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = stringResource(R.string.map_layer_title),
                            tint = if (mapLayer != com.oshi.messenger.service.map.MapLayer.STANDARD) brandInk()
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    MapLayerMenu(
                        expanded = showLayerMenu,
                        current = mapLayer,
                        offlineOnly = offlineOnly,
                        onDismiss = { showLayerMenu = false },
                        onPick = { picked ->
                            showLayerMenu = false
                            if (picked != mapLayer) {
                                mapLayer = picked
                                com.oshi.messenger.service.map.MapLayer.save(context, picked)
                                mapViewRef?.let { applyMapLayer(it, picked) }
                            }
                        }
                    )
                }
                // __THE_MAP_DECIDED_WHAT_YOU_SAW_2026_11_07__ Beside the other
                // map controls rather than buried in settings: it is a property
                // of what you are looking at, and you decide it while looking.
                IconButton(
                    onClick = { viewModel.toggleShowPOIs() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (showPOIs) Icons.Default.Place else Icons.Default.LocationOff,
                        contentDescription = stringResource(
                            if (showPOIs) R.string.map_pois_hide else R.string.map_pois_show
                        ),
                        tint = if (showPOIs) brandInk()
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(
                    onClick = { onNavigateToRegions() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = stringResource(R.string.offline_maps_title),
                        // brandInk(), not OSHIPurple: raw #5616EC on the dark
                        // scheme's `surface` (#1A1A1A) is 1.86:1 — below the 3:1
                        // floor for a meaning-bearing icon.
                        tint = brandInk(),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // (N2) The publisher's credit, required by both third-party layers'
        // licences, while one of them is on screen.
        if (mapLayer != com.oshi.messenger.service.map.MapLayer.STANDARD) {
            Text(
                text = mapLayer.attribution,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 2,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 8.dp, end = 80.dp, bottom = 8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        // ── Right-side buttons (GPS + Itinerary + AI Chat) ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // GPS Follow toggle button — auto-center + auto-rotate to heading
            FloatingActionButton(
                onClick = { viewModel.toggleFollowUserMode() },
                // Active: solid brand container with white ink (7.7:1).
                // Inactive: brandInk on surface, which the dark scheme can show.
                containerColor = if (followUserMode) OSHIPurple else MaterialTheme.colorScheme.surface,
                contentColor = if (followUserMode) Color.White else brandInk(),
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Icon(
                    Icons.Default.Explore,
                    contentDescription = stringResource(
                        if (followUserMode) R.string.map_action_stop_following else R.string.map_action_follow_location
                    ),
                    modifier = Modifier.size(24.dp)
                )
            }

            // GPS button
            FloatingActionButton(
                onClick = {
                    viewModel.centerOnLocation()
                    mapViewRef?.let { map ->
                        val loc = userLocation
                        if (loc != null) {
                            map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                            map.controller.setZoom(15.0)
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = brandInk(),
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Icon(
                    Icons.Default.MyLocation,
                    contentDescription = stringResource(R.string.map_action_my_location),
                    modifier = Modifier.size(24.dp)
                )
            }

            // Itinerary button
            FloatingActionButton(
                onClick = { onNavigateToItinerary() },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = brandInk(),
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Icon(
                    Icons.Default.Directions,
                    contentDescription = stringResource(R.string.itinerary_plan_route),
                    modifier = Modifier.size(24.dp)
                )
            }

            // AI Chat button
            FloatingActionButton(
                onClick = { onNavigateToAIChat() },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = brandInk(),
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = stringResource(R.string.map_action_ai_assistant),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // ── Selected POI Bottom Sheet ──
        AnimatedVisibility(
            visible = selectedPOI != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            selectedPOI?.let { poi ->
                POIDetailCard(
                    poi = poi,
                    distanceMeters = userLocation?.let { loc ->
                        val r = FloatArray(1)
                        android.location.Location.distanceBetween(
                            loc.latitude, loc.longitude, poi.lat, poi.lon, r
                        )
                        r[0].toDouble()
                    },
                    onClose = { viewModel.selectPOI(null) },
                    onNavigate = {
                        viewModel.calculateRoute(poi.lat, poi.lon)
                        onNavigateToNavigation()
                    }
                )
            }
        }

        // ── (N8) Dropped-pin card ──
        //
        // Suppressed while a POI card is up: both live at BottomCenter, and a
        // tap that opened a POI is not a tap on empty map anyway.
        AnimatedVisibility(
            visible = droppedPin.value != null && selectedPOI == null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            droppedPin.value?.let { pin ->
                OshiCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.itinerary_dropped_pin),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                // Plain decimal degrees: no reverse geocoding,
                                // which would be a network call for a label on a
                                // screen that honours "offline maps only".
                                // Locale.US deliberately, not the device locale: decimal
                                // degrees with a comma separator in a pair already
                                // separated by a comma ("48,85, 2,35") cannot be read.
                                String.format(java.util.Locale.US, "%.5f, %.5f", pin.latitude, pin.longitude),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            viewModel.requestItineraryTo(pin.latitude, pin.longitude)
                            droppedPin.value = null
                            onNavigateToItinerary()
                        }) {
                            Text(stringResource(R.string.itinerary_plan_route))
                        }
                        IconButton(onClick = { droppedPin.value = null }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.accessibility_close)
                            )
                        }
                    }
                }
            }
        }

        // ── __LORA_NODE_TAP_2026_11_05__ LoRa node card ──
        selectedNode?.let { node ->
            val nodeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            OshiModalBottomSheet(
                onDismissRequest = { selectedNode = null },
                sheetState = nodeSheetState,
            ) {
                LoRaNodeMapCard(
                    node = node,
                    myLocation = viewModel.userLocation.value,
                    onMessage = { key ->
                        selectedNode = null
                        onOpenChat(key)
                    },
                )
            }
        }

        // ── Search Bottom Sheet (overlays map; matches iOS .sheet) ──
        if (showSearchSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
            OshiModalBottomSheet(
                onDismissRequest = { showSearchSheet = false },
                sheetState = sheetState
            ) {
                MapSearchSheet(
                    viewModel = viewModel,
                    onPOISelected = { poi ->
                        viewModel.selectPOI(poi)
                        showSearchSheet = false
                    },
                    onCategorySelected = { category ->
                        viewModel.toggleCategoryMarkers(category)
                    }
                )
            }
        }

        // ── OpenStreetMap attribution ──
        // The tiles are OSM data under the ODbL, which REQUIRES a visible
        // "© OpenStreetMap contributors" credit wherever they are shown.
        // osmdroid does not draw one by default, and iOS omits it too (a
        // repo-wide grep of OSHI/*.swift for "OpenStreetMap" finds nothing)
        // — that is an iOS bug to fix, not a design decision to copy.
        //
        // Deliberately NOT a translatable resource: the ODbL asks for this
        // exact attribution text, and translating the project name would stop
        // it being the credit the licence names.
        Text(
            text = "© OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 8.dp, bottom = 4.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )

        // ── Routing refusal ──
        // `MapViewModel` deliberately refuses to invent a route when it has no
        // road graph and no OSRM (`MapViewModel.kt:1632-1637`) — it used to
        // fabricate a 3-point straight line across 8 991 km instead. It sets
        // `routeError`, but this screen never read it, so tapping "Navigate"
        // on a POI in one of the 79 regions with no roadgraph did nothing at
        // all and then pushed an empty navigation screen. An honest refusal
        // that nobody displays is indistinguishable from a dead button.
        val routeError by viewModel.routeError.collectAsState()
        routeError?.let { message ->
            if (selectedPOI == null && activeRoute == null) {
                OshiCard(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                message,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                stringResource(R.string.offline_map_routing_unavailable_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                            )
                        }
                        IconButton(onClick = { viewModel.acknowledgeRouteError() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.close),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        // ── Route info bar ──
        activeRoute?.let { route ->
            if (selectedPOI == null) {
                OshiCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.BottomCenter),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = OSHIPurple)
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
                                route.durationFormatted,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                route.distanceFormatted,
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 14.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { onNavigateToNavigation() },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color.White.copy(alpha = 0.2f),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.map_nav_start))
                            }
                            IconButton(onClick = { viewModel.stopNavigation() }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel), tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Selected-POI card.
 *
 * Ported to iOS's shape (`OSHI/OfflineMapView.swift:807-979`): a bottom-anchored
 * panel with only its TOP corners rounded (`:2967-2985` `RoundedCornerShape`),
 * an upward shadow, a decorative drag handle, and a 46 pt brand icon tile
 * carrying a white glyph (`:823-854`). Android previously drew a floating
 * all-corners card inset 16 dp on every side, which reads as a dialog rather
 * than as a sheet rising out of the map.
 *
 * The category badge is the reason this function was touched first. It was
 * `color = OSHIPurple` on `OSHIPurple.copy(alpha = 0.1f)` — the exact
 * brand-ink-on-brand-tint recipe this codebase has now hit four times. In dark
 * mode #5616EC over a 10 % wash of itself on #1A1A1A measures ≈2.1:1, well
 * under the 4.5:1 floor, and it is invisible in a light-mode review. It now
 * uses the sanctioned pair: [Oshi.ContainerTintAlpha] for the container and
 * [brandInk] for the ink, which lightens the violet in dark schemes instead of
 * keeping a luminance the scheme cannot show.
 *
 * iOS ships this same bug on its own Share button (`:958-961`); it is
 * deliberately not copied.
 */
@Composable
private fun POIDetailCard(
    poi: MapPOI,
    distanceMeters: Double? = null,
    onClose: () -> Unit,
    onNavigate: () -> Unit
) {
    val ink = brandInk()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        // Top corners only — iOS `cornerRadius(20, corners: [.topLeft, .topRight])`.
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        // Upward shadow: iOS `.shadow(.black.opacity(0.15), radius: 12, y: -5)`.
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Drag handle — 36 × 5, iOS `:814-818`. Decorative on both platforms.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 0.dp)
            ) {
                // ── Header: 46 dp brand tile + title/category + close ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(OSHIPurple),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Place,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            poi.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                // 0.22 container tint + brandInk: the pair that
                                // survives both schemes (Tokens.kt Oshi).
                                color = OSHIPurple.copy(alpha = Oshi.ContainerTintAlpha),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    stringResource(poi.category.displayNameRes),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    color = ink,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            if (distanceMeters != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    formatDistance(distanceMeters),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                if (poi.address.isNotBlank()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            poi.address,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onNavigate,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OSHIPurple,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.map_navigate),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (poi.phone.isNotBlank()) {
                        OutlinedButton(
                            onClick = { /* Open dialer */ },
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ink)
                        ) {
                            Icon(
                                Icons.Default.Phone,
                                contentDescription = stringResource(R.string.map_action_call),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Distance for on-map labels.
 *
 * `String.format` without an explicit Locale formats with the JVM default,
 * which is what the rest of this screen wants, but the *unit* must come from
 * resources — this app ships 34 languages and "km"/"m" are not universal
 * (`км`, `キロ`, `كم`). The two keys are `map_distance_m` / `map_distance_km`.
 */
@Composable
private fun formatDistance(meters: Double): String =
    if (meters < 1000) {
        stringResource(R.string.map_distance_m, meters.toInt())
    } else {
        stringResource(
            R.string.map_distance_km,
            String.format(java.util.Locale.getDefault(), "%.1f", meters / 1000.0)
        )
    }

// ── Compass heading via SensorManager.TYPE_ROTATION_VECTOR ──

/**
 * Subscribes to the rotation-vector sensor while [enabled] is true and emits
 * smoothed azimuth degrees (0-360, 0 = north) via [onHeading].
 *
 * Uses a low-pass filter to dampen jitter, with wraparound handling at 0/360
 * so the smoothed value never sweeps the long way around (e.g. 350° → 10°
 * smooths through 0°, not through 180°).
 *
 * No permission required — TYPE_ROTATION_VECTOR is a "soft" sensor fused
 * from accelerometer + magnetometer + gyroscope.
 */
@Composable
private fun CompassHeadingProvider(
    enabled: Boolean,
    onHeading: (Float) -> Unit
) {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onHeading)

    DisposableEffect(enabled) {
        if (!enabled) {
            return@DisposableEffect onDispose { }
        }

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        if (sensorManager == null || rotationSensor == null) {
            return@DisposableEffect onDispose { }
        }

        val rotationMatrix = FloatArray(9)
        val orientationValues = FloatArray(3)
        var smoothed: Float? = null
        val alpha = 0.15f

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationValues)
                // azimuth is radians, -π..π; convert to 0..360 degrees CW from north
                val rawDeg = ((Math.toDegrees(orientationValues[0].toDouble()).toFloat()) + 360f) % 360f

                val prev = smoothed
                val next: Float = if (prev == null) {
                    rawDeg
                } else {
                    // Shortest-arc delta on a circle (handles 0/360 wraparound)
                    var delta = rawDeg - prev
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    ((prev + alpha * delta) + 360f) % 360f
                }
                smoothed = next
                callback(next)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
        }

        sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }
}

// ── Category Marker Bitmap (small circular badge with emoji) ──

/**
 * Build a small circular badge bitmap for a category POI marker. Emoji is chosen
 * per category. Used by category-search overlay (Bug 2 fix).
 */
private fun createCategoryMarkerBitmap(category: POICategory): Bitmap {
    val size = 64 // px
    val border = 4f
    val totalSize = size + (border * 2).toInt()

    val bitmap = Bitmap.createBitmap(totalSize, totalSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = totalSize / 2f
    val cy = totalSize / 2f

    // White outer border
    paint.color = android.graphics.Color.WHITE
    paint.style = Paint.Style.FILL
    canvas.drawCircle(cx, cy, size / 2f + border, paint)

    // Brand-colored fill
    paint.color = android.graphics.Color.parseColor("#5616EC")
    canvas.drawCircle(cx, cy, size / 2f, paint)

    // Emoji glyph centered
    val emoji = categoryEmoji(category)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = size * 0.55f
        textAlign = Paint.Align.CENTER
    }
    val textBounds = Rect()
    textPaint.getTextBounds(emoji, 0, emoji.length, textBounds)
    canvas.drawText(emoji, cx, cy + textBounds.height() / 2f, textPaint)

    return bitmap
}

private fun categoryEmoji(category: POICategory): String = when (category) {
    POICategory.RESTAURANT -> "\uD83C\uDF74" // fork & knife
    POICategory.CAFE -> "\u2615"
    POICategory.BAR -> "\uD83C\uDF7B"
    POICategory.HOTEL -> "\uD83C\uDFE8"
    POICategory.HOSPITAL -> "\uD83C\uDFE5"
    POICategory.PHARMACY -> "\uD83D\uDC8A"
    POICategory.GAS_STATION -> "\u26FD"
    POICategory.ATM -> "\uD83D\uDCB3"
    POICategory.BANK -> "\uD83C\uDFE6"
    POICategory.SUPERMARKET -> "\uD83D\uDED2"
    POICategory.PARKING -> "\uD83C\uDD7F\uFE0F"
    POICategory.POLICE -> "\uD83D\uDC6E"
    POICategory.POST_OFFICE -> "\u2709\uFE0F"
    POICategory.SCHOOL -> "\uD83C\uDFEB"
    POICategory.MUSEUM -> "\uD83C\uDFDB\uFE0F"
    POICategory.PARK -> "\uD83C\uDF33"
    POICategory.BEACH -> "\uD83C\uDFD6\uFE0F"
    POICategory.AIRPORT -> "\u2708\uFE0F"
    POICategory.TRAIN_STATION -> "\uD83D\uDE86"
    POICategory.BUS_STATION -> "\uD83D\uDE8C"
    POICategory.EV_CHARGING -> "\uD83D\uDD0C"
    POICategory.EMBASSY -> "\uD83C\uDFDB\uFE0F"
    POICategory.PLACE_OF_WORSHIP -> "\u26EA"
    POICategory.UNIVERSITY -> "\uD83C\uDF93"      // graduation cap
    POICategory.GYM -> "\uD83C\uDFCB\uFE0F"       // weightlifter — 3 242 rows in fr-idf
    POICategory.TOILET -> "\uD83D\uDEBB"           // restroom — 2 710 rows
    POICategory.CINEMA -> "\uD83C\uDFAC"           // clapper — 948 rows
    POICategory.OTHER -> "\uD83D\uDCCD"
}

// ── Contact Avatar Bitmap for map marker ──

private fun createContactAvatarBitmap(
    name: String,
    profilePicture: ByteArray?,
    isLive: Boolean
): Bitmap {
    val size = 120 // px
    val border = 8f
    val totalSize = size + (border * 2).toInt() + 24 // extra for pointer triangle

    val bitmap = Bitmap.createBitmap(totalSize, totalSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    val cx = totalSize / 2f
    val cy = (size + border * 2) / 2f

    // White border circle
    paint.color = android.graphics.Color.WHITE
    paint.style = Paint.Style.FILL
    canvas.drawCircle(cx, cy, size / 2f + border, paint)

    // Live location: green border
    if (isLive) {
        paint.color = android.graphics.Color.parseColor("#4CAF50")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        canvas.drawCircle(cx, cy, size / 2f + border - 2f, paint)
        paint.style = Paint.Style.FILL
    }

    // Clip circle for profile picture
    canvas.save()
    val clipPath = android.graphics.Path()
    clipPath.addCircle(cx, cy, size / 2f, android.graphics.Path.Direction.CW)
    canvas.clipPath(clipPath)

    if (profilePicture != null && profilePicture.isNotEmpty()) {
        // Draw profile picture
        val profileBmp = BitmapFactory.decodeByteArray(profilePicture, 0, profilePicture.size)
        if (profileBmp != null) {
            val src = Rect(0, 0, profileBmp.width, profileBmp.height)
            val dst = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
            canvas.drawBitmap(profileBmp, src, dst, paint)
            profileBmp.recycle()
        }
    } else {
        // Gradient background with initials
        val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val gradient = LinearGradient(
            cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f,
            android.graphics.Color.parseColor("#5616EC"),
            android.graphics.Color.parseColor("#3F51B5"),
            Shader.TileMode.CLAMP
        )
        gradientPaint.shader = gradient
        canvas.drawCircle(cx, cy, size / 2f, gradientPaint)

        // Draw initials
        val initials = name.take(2).uppercase()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = size * 0.38f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val textBounds = Rect()
        textPaint.getTextBounds(initials, 0, initials.length, textBounds)
        canvas.drawText(initials, cx, cy + textBounds.height() / 2f, textPaint)
    }

    canvas.restore()

    // Triangle pointer at bottom
    paint.color = android.graphics.Color.WHITE
    paint.style = Paint.Style.FILL
    val triTop = cy + size / 2f + border - 2f
    val path = android.graphics.Path()
    path.moveTo(cx - 10f, triTop)
    path.lineTo(cx, triTop + 18f)
    path.lineTo(cx + 10f, triTop)
    path.close()
    canvas.drawPath(path, paint)

    return bitmap
}

/**
 * __LORA_NODE_TAP_2026_11_05__ What a radio's pin shows when you tap it.
 *
 * Until now it showed osmdroid's default bubble: the node's name, and nothing
 * else. The POI markers beside it had a click listener and this one did not, so
 * the map could tell you a radio was THERE and not one useful thing about it.
 *
 * ## Why these fields and no others
 *
 * Meshtastic's node view is the reference, and what it shows are the facts that
 * change what you would do next:
 *
 *  * **last heard**, first and in words. It is the most important number on the
 *    card: a node heard two minutes ago is someone you can reach, and the same
 *    node nine hours ago is a pin on a map. The dot is green only while the node
 *    is recent enough to act on.
 *  * **signal as bars**, with the dBm underneath for whoever wants it. "-78 dBm"
 *    means nothing to the person holding the phone and four bars do — the same
 *    reasoning as [LoRaFormat.signalBars], whose buckets this reuses rather than
 *    inventing a second set.
 *  * **hops** — direct, or relayed by somebody else. That is the difference
 *    between "in range" and "this is reaching me through a stranger".
 *  * **distance**, only when BOTH sides have a fix, and never "0 km" when one of
 *    them does not.
 *
 * Battery is deliberately absent: [LoRaMeshNode] does not carry one, and a field
 * invented for visual balance is a field that lies. Mirrors `LoRaNodeMapCard` in
 * `OSHI/OfflineMapView.swift`.
 */
@Composable
private fun LoRaNodeMapCard(
    node: com.oshi.messenger.service.lora.LoRaMeshNode,
    myLocation: android.location.Location?,
    onMessage: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val name = node.longName.trim().ifEmpty {
        node.shortName.trim().ifEmpty { "!%08x".format(node.num.toLong()) }
    }
    val nodeId = "!%08x".format(node.num.toLong())
    val sinceMs = System.currentTimeMillis() - node.lastHeard
    val isFresh = node.lastHeard > 0L && sinceMs < 15 * 60 * 1000L
    val heard = when {
        node.lastHeard <= 0L -> stringResource(R.string.lora_node_card_never_heard)
        sinceMs < 60_000 -> stringResource(R.string.lora_node_card_heard_now)
        sinceMs < 3_600_000 -> stringResource(R.string.lora_node_card_heard_min, (sinceMs / 60_000).toInt())
        sinceMs < 86_400_000 -> stringResource(R.string.lora_node_card_heard_hour, (sinceMs / 3_600_000).toInt())
        else -> stringResource(R.string.lora_node_card_heard_day, (sinceMs / 86_400_000).toInt())
    }
    val distance: String? = remember(node, myLocation) {
        val lat = node.latitude; val lon = node.longitude
        if (myLocation == null || lat == null || lon == null) null
        else {
            val out = FloatArray(1)
            android.location.Location.distanceBetween(myLocation.latitude, myLocation.longitude, lat, lon, out)
            val m = out[0]
            if (m < 1000) context.getString(R.string.lora_node_card_metres, m.toInt())
            else context.getString(R.string.lora_node_card_km, m / 1000f)
        }
    }

    // Scrolls, and `GroupChatSheetReachabilityTest` is why — it caught this sheet
    // the first time it ran. The card grows with what the node reports (hops and
    // distance appear only when known) and a landscape phone leaves a sheet very
    // little height, so the stats row is exactly what gets stranded off the end.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(com.oshi.messenger.ui.theme.OSHIOrange),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.SettingsInputAntenna,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(
                    nodeId,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Freshness first: it decides whether anything below it matters.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (isFresh) com.oshi.messenger.ui.theme.OSHIGreen
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                heard,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isFresh) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isFresh) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column {
                SignalBars(bars = com.oshi.messenger.ui.viewmodel.LoRaFormat.signalBars(node.rssi ?: Int.MIN_VALUE))
                Spacer(Modifier.height(4.dp))
                Text(
                    node.rssi?.let { stringResource(R.string.lora_node_card_dbm, it) }
                        ?: stringResource(R.string.lora_node_card_not_heard),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            node.hopsAway?.let { hops ->
                NodeStat(
                    value = if (hops == 0) stringResource(R.string.lora_node_card_direct)
                            else stringResource(R.string.lora_node_card_hops, hops),
                    caption = if (hops == 0) stringResource(R.string.lora_node_card_in_range)
                              else stringResource(R.string.lora_node_card_relayed),
                )
            }
            distance?.let { NodeStat(value = it, caption = stringResource(R.string.lora_node_card_from_you)) }
        }

        // __MESSAGE_THE_NODE_FROM_THE_MAP_2026_11_07__
        //
        // The card told you a radio's name, signal, hop count and distance, and
        // then offered no action at all. You could see a node two streets away
        // and had no way to say anything to it without leaving the map.
        //
        // WHERE THIS OPENS. `LoRaIdentityBridge.conversationKey` — not the
        // fallback key: when the radio has PROVED an OSHI identity this routes
        // to that contact's real thread and the message is ratchet-encrypted
        // like any other. Only an unproved radio lands in the cleartext `lora!`
        // thread, which the chat already labels as interop.
        //
        // HOPS come free: a unicast is addressed to the node number and
        // Meshtastic routes it through the mesh, so a relayed node is reachable
        // exactly like a direct one — which is why this is offered whatever
        // `hopsAway` says.
        //
        // Offered with no radio attached too: the thread and its history are
        // worth reading either way, and the send path already refuses with
        // "no radio attached" rather than failing silently.
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                onMessage(
                    com.oshi.messenger.service.lora.LoRaIdentityBridge
                        .conversationKey(node.num.toUInt())
                )
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(Icons.Default.Send, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.lora_node_message))
        }
    }
}

@Composable
private fun NodeStat(value: String, caption: String) {
    Column {
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


// ── (N2, Anatole 13/11) Map layers ──────────────────────────────────────────

/** STANDARD keeps its old one-step overzoom; the others stop where their tiles stop. */
private fun mapMaxZoom(layer: com.oshi.messenger.service.map.MapLayer): Double =
    if (layer == com.oshi.messenger.service.map.MapLayer.STANDARD) 20.0 else layer.maxZoom.toDouble()

/**
 * Swap the base layer on a live map. A new provider rather than a new source on
 * the old one: the provider's modules carry the layer (cache folder, URL, which
 * headers to send). `MapView.setTileProvider` detaches the previous provider.
 */
private fun applyMapLayer(map: MapView, layer: com.oshi.messenger.service.map.MapLayer) {
    runCatching {
        val storage = com.oshi.messenger.service.MapStorageManager(map.context)
        map.tileProvider = com.oshi.messenger.service.map.OshiOfflineTileProvider.create(map.context, storage, layer)
    }
    map.setTileSource(layer.source)
    map.maxZoomLevel = mapMaxZoom(layer)
    if (map.zoomLevelDouble > map.maxZoomLevel) map.controller.setZoom(map.maxZoomLevel)
    map.invalidate()
}

@Composable
private fun MapLayerMenu(
    expanded: Boolean,
    current: com.oshi.messenger.service.map.MapLayer,
    offlineOnly: Boolean,
    onDismiss: () -> Unit,
    onPick: (com.oshi.messenger.service.map.MapLayer) -> Unit,
) {
    OshiDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 320.dp)
    ) {
        Text(
            stringResource(R.string.map_layer_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        for (layer in com.oshi.messenger.service.map.MapLayer.entries) {
            val title = stringResource(
                when (layer) {
                    com.oshi.messenger.service.map.MapLayer.STANDARD -> R.string.map_layer_standard
                    com.oshi.messenger.service.map.MapLayer.TERRAIN -> R.string.map_layer_terrain
                    com.oshi.messenger.service.map.MapLayer.SATELLITE -> R.string.map_layer_satellite
                }
            )
            val host = layer.thirdPartyHost
            val subtitle = if (host == null) stringResource(R.string.map_layer_standard_desc)
                           else stringResource(R.string.map_layer_third_party_desc, host)
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        when (layer) {
                            com.oshi.messenger.service.map.MapLayer.STANDARD -> Icons.Default.Map
                            com.oshi.messenger.service.map.MapLayer.TERRAIN -> Icons.Default.Terrain
                            com.oshi.messenger.service.map.MapLayer.SATELLITE -> Icons.Default.SatelliteAlt
                        },
                        contentDescription = null,
                        tint = if (layer == current) brandInk() else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (layer == current) Icon(Icons.Default.Check, contentDescription = null, tint = brandInk())
                },
                text = {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(title, fontWeight = if (layer == current) FontWeight.SemiBold else FontWeight.Normal)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (offlineOnly && host != null) {
                            Text(
                                stringResource(R.string.map_layer_offline_only_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                onClick = { onPick(layer) }
            )
        }
    }
}
