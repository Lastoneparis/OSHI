package com.oshi.messenger.ui.screens

import androidx.compose.animation.animateContentSize
import com.oshi.messenger.ui.theme.OshiCard
import com.oshi.messenger.ui.theme.OshiAlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.oshi.messenger.data.model.MapRegion
import com.oshi.messenger.data.model.RegionDownloadProgress
import com.oshi.messenger.data.model.RegionDownloadState
import com.oshi.messenger.data.model.RoutingDownloadProgress
import com.oshi.messenger.data.model.RoutingDownloadState
import com.oshi.messenger.data.model.StreetDownloadProgress
import com.oshi.messenger.data.model.StreetDownloadState
import com.oshi.messenger.service.map.PoiCoverage
import androidx.compose.ui.res.stringResource
import com.oshi.messenger.R
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.OSHIGreen
import com.oshi.messenger.ui.theme.OSHIRed
import com.oshi.messenger.ui.viewmodel.MapViewModel

/**
 * MapRegionDownloadScreen — browse, download, and manage offline map regions.
 * Regions grouped by continent with download/delete controls and progress indicators.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapRegionDownloadScreen(
    onBack: () -> Unit,
    viewModel: MapViewModel = hiltViewModel()
) {
    val regionsByContinent by viewModel.regionsByContinent.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val routingProgress by viewModel.routingDownloadProgress.collectAsState()
    val streetProgress by viewModel.streetDownloadProgress.collectAsState()
    val isLoading by viewModel.isLoadingManifest.collectAsState()
    val totalStorage by viewModel.totalStorageUsed.collectAsState()
    var deleteConfirmRegion by remember { mutableStateOf<MapRegion?>(null) }
    // __MAP_PICKER_SEARCH_2026_10_14__ iOS has had a region filter since the
    // screen was written (MapRegionDownloadView.swift:186); Android never did,
    // so the only way to reach a country was to scroll the whole manifest. That
    // is 259 regions today and grows every time the pipeline adds one.
    var searchQuery by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.offline_maps_title), fontWeight = FontWeight.Bold)
                        val storageMB = totalStorage / (1024.0 * 1024.0)
                        Text(
                            if (storageMB >= 1024)
                                stringResource(R.string.map_regions_storage_used_format_gb, storageMB / 1024.0)
                            else
                                stringResource(R.string.map_regions_storage_used_format, String.format("%.0f MB", storageMB)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.map_regions_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshManifest() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.map_regions_refresh))
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading && regionsByContinent.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = OSHIPurple)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.offline_maps_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            // Regrouped here rather than in the view model because `continentKey`
            // lives in this file. Two reasons it must be normalised:
            //  * MapViewModel groups by the RAW string, so "south-america" and
            //    "south_america" became two sections with the SAME title.
            //  * the filter has to apply before grouping, or an empty continent
            //    still renders its header while the search matches nothing in it.
            val grouped = remember(regionsByContinent, searchQuery) {
                val q = searchQuery.trim().lowercase()
                regionsByContinent.values.asSequence().flatten()
                    .filter { regionMatchesQuery(it, q) }
                    .groupBy { r -> continentKey(r.continent) }
                    .toSortedMap()
            }
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // Fixed above the list, not scrolled with it: with the list this
                // long, a filter you have to scroll back up to reach is no filter.
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                // Header info banner
                item {
                    OshiCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = OSHIPurple.copy(alpha = 0.08f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    tint = OSHIPurple,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    stringResource(R.string.map_regions_banner_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.map_regions_banner_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            // 🔧 P2 FIX (device walkthrough #2, 2026-08-13): a fixed
                            // Row clipped its last chip at the right screen edge —
                            // "Streets" wrapped to two lines and ran off. Four
                            // localised labels never fit a phone width at every
                            // locale + font scale, so the row scrolls instead, with
                            // end padding so the last chip is never flush to the edge.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FeatureChip(icon = Icons.Default.Map, label = stringResource(R.string.map_regions_banner_feature_tiles))
                                FeatureChip(icon = Icons.Default.Search, label = stringResource(R.string.map_regions_banner_feature_poi))
                                FeatureChip(icon = Icons.Default.Navigation, label = stringResource(R.string.map_regions_banner_feature_routing))
                                FeatureChip(icon = Icons.Default.Signpost, label = stringResource(R.string.map_regions_banner_feature_streets))
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.map_regions_banner_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                if (grouped.isEmpty() && searchQuery.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.search_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                        )
                    }
                }

                // Continents
                grouped.forEach { (continent, regions) ->
                    // Split into full countries (short ID, no dash) and cities/regions (ID with dash)
                    // __REGION_ALIAS_2026_10_21__ Sorted by the name actually
                    // shown. Sorting on `name` while displaying `displayName`
                    // puts a localized list in English alphabetical order,
                    // which reads as a random shuffle to the user.
                    val collator = java.text.Collator.getInstance()
                    val countries = regions.filter { !it.id.contains("-") }
                        .sortedWith { a, b -> collator.compare(a.displayName, b.displayName) }
                    val cities = regions.filter { it.id.contains("-") }
                        .sortedWith { a, b -> collator.compare(a.displayName, b.displayName) }

                    item {
                        Text(
                            text = continentEmoji(continent) + " " + continentDisplayName(continent),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Full countries section
                    if (countries.isNotEmpty()) {
                        item {
                            Text(
                                text = "\uD83C\uDF0D " + stringResource(R.string.map_regions_subsection_full_countries),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                                color = OSHIPurple
                            )
                        }
                        items(countries, key = { it.id }) { region ->
                            val progress = downloadProgress[region.id]
                            val state = progress?.state ?: viewModel.getRegionState(region.id)
                            val rProgress = routingProgress[region.id]
                            val rState = rProgress?.state ?: viewModel.getRoutingState(region.id)
                            val hasRouting = viewModel.hasRoutingAvailable(region.id)
                            val routingSizeFormatted = viewModel.getRoutingSizeFormatted(region.id)
                            val sProgress = streetProgress[region.id]
                            val sState = sProgress?.state ?: viewModel.getStreetState(region.id)
                            val streetSizeFormatted = viewModel.getStreetIndexSizeFormatted(region.id)
                            RegionItem(
                                region = region,
                                state = state,
                                progress = progress,
                                onDownload = { viewModel.downloadRegion(region) },
                                onCancel = { viewModel.cancelRegionDownload(region.id) },
                                onDelete = { deleteConfirmRegion = region },
                                routingState = rState,
                                routingProgress = rProgress,
                                hasRoutingAvailable = hasRouting,
                                routingSizeFormatted = routingSizeFormatted,
                                onDownloadRouting = { viewModel.downloadRouting(region.id) },
                                onDeleteRouting = { viewModel.deleteRouting(region.id) },
                                streetState = sState,
                                streetProgress = sProgress,
                                streetSizeFormatted = streetSizeFormatted,
                                onDownloadStreetIndex = { viewModel.downloadStreetIndex(region.id) },
                                onDeleteStreetIndex = { viewModel.deleteStreetIndex(region.id) }
                            )
                        }
                    }

                    // Cities/regions section
                    if (cities.isNotEmpty()) {
                        item {
                            Text(
                                text = "\uD83C\uDFD9 " + stringResource(R.string.map_regions_subsection_cities_regions),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(cities, key = { it.id }) { region ->
                            val progress = downloadProgress[region.id]
                            val state = progress?.state ?: viewModel.getRegionState(region.id)
                            val rProgress = routingProgress[region.id]
                            val rState = rProgress?.state ?: viewModel.getRoutingState(region.id)
                            val hasRouting = viewModel.hasRoutingAvailable(region.id)
                            val routingSizeFormatted = viewModel.getRoutingSizeFormatted(region.id)
                            val sProgress = streetProgress[region.id]
                            val sState = sProgress?.state ?: viewModel.getStreetState(region.id)
                            val streetSizeFormatted = viewModel.getStreetIndexSizeFormatted(region.id)
                            RegionItem(
                                region = region,
                                state = state,
                                progress = progress,
                                onDownload = { viewModel.downloadRegion(region) },
                                onCancel = { viewModel.cancelRegionDownload(region.id) },
                                onDelete = { deleteConfirmRegion = region },
                                routingState = rState,
                                routingProgress = rProgress,
                                hasRoutingAvailable = hasRouting,
                                routingSizeFormatted = routingSizeFormatted,
                                onDownloadRouting = { viewModel.downloadRouting(region.id) },
                                onDeleteRouting = { viewModel.deleteRouting(region.id) },
                                streetState = sState,
                                streetProgress = sProgress,
                                streetSizeFormatted = streetSizeFormatted,
                                onDownloadStreetIndex = { viewModel.downloadStreetIndex(region.id) },
                                onDeleteStreetIndex = { viewModel.deleteStreetIndex(region.id) }
                            )
                        }
                    }
                }
            }
            }
        }

        // Delete confirmation dialog
        deleteConfirmRegion?.let { region ->
            OshiAlertDialog(
                onDismissRequest = { deleteConfirmRegion = null },
                title = { Text(stringResource(R.string.offline_maps_delete_region)) },
                text = { Text(stringResource(R.string.map_regions_delete_confirm_message_with_name, region.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteRegion(region.id)
                            viewModel.deleteRouting(region.id)
                            viewModel.deleteStreetIndex(region.id)
                            deleteConfirmRegion = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = OSHIRed)
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteConfirmRegion = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun RegionItem(
    region: MapRegion,
    state: RegionDownloadState,
    progress: RegionDownloadProgress?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    routingState: RoutingDownloadState = RoutingDownloadState.NOT_DOWNLOADED,
    routingProgress: RoutingDownloadProgress? = null,
    hasRoutingAvailable: Boolean = false,
    routingSizeFormatted: String = "",
    onDownloadRouting: () -> Unit = {},
    onDeleteRouting: () -> Unit = {},
    streetState: StreetDownloadState = StreetDownloadState.NOT_DOWNLOADED,
    streetProgress: StreetDownloadProgress? = null,
    streetSizeFormatted: String = "",
    onDownloadStreetIndex: () -> Unit = {},
    onDeleteStreetIndex: () -> Unit = {}
) {
    OshiCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Region info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        region.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            region.country,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            " \u2022 ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 🔧 P1 FIX (2026-08-13): show "Size unavailable" instead of a
                        // confident "0 MB" when the manifest reports no measurable
                        // artifact for the region. See MapModels.MapRegion.fileSize
                        // for why the figure itself changed.
                        Text(
                            if (region.hasKnownSize) region.fileSizeFormatted
                            else stringResource(R.string.offline_map_size_unknown),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // "+5.2 GB shared" — this transparency suffix was ported from
                        // iOS (`MapModels.swift:48-62`) and then never rendered, so a
                        // sub-region hid the country artifact it pulls in.
                        val shared = region.sharedSizeFormatted
                        if (shared.isNotEmpty()) {
                            Text(
                                " $shared",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 🔧 P0 FIX (map audit 2026-08-23 §2.2b): 22 of the 254 live
                    // regions carry NO POI artifact — `us`, `jp`, `br`, `ca`,
                    // `ru`, `id` and 16 US/BR/JP metros. `OfflineMapManager`
                    // correctly skips the download (there is nothing to fetch),
                    // the region then reports SUCCESS, and place search inside it
                    // returns nothing for ever with no explanation. Saying so on
                    // the card is the fix: the limitation is now known BEFORE the
                    // download, not discovered as a permanent silent hole after.
                    if (!PoiCoverage.hasPoiArtifact(region)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.LayersClear,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        stringResource(R.string.map_region_no_poi_search),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        stringResource(R.string.map_region_no_poi_search_body),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }

                    // Download progress bar
                    if (state == RegionDownloadState.DOWNLOADING && progress != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = progress.progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = OSHIPurple,
                            trackColor = OSHIPurple.copy(alpha = 0.15f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "${(progress.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = OSHIPurple
                        )
                    }

                    // Error message
                    if (state == RegionDownloadState.ERROR && progress?.error != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            progress.error ?: stringResource(R.string.map_regions_download_failed),
                            style = MaterialTheme.typography.labelSmall,
                            color = OSHIRed
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Action button for map tiles
                when (state) {
                    RegionDownloadState.NOT_DOWNLOADED -> {
                        IconButton(onClick = onDownload) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = stringResource(R.string.map_regions_routing_prompt_download),
                                tint = OSHIPurple,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    RegionDownloadState.DOWNLOADING -> {
                        // Tap to cancel — without this the user is stuck waiting
                        // for a country-sized fetch (~145k tiles for France) with
                        // no escape from the progress spinner.
                        IconButton(onClick = onCancel) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = OSHIPurple,
                                    strokeWidth = 3.dp
                                )
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cancel),
                                    modifier = Modifier.size(14.dp),
                                    tint = OSHIPurple
                                )
                            }
                        }
                    }
                    RegionDownloadState.DOWNLOADED -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = stringResource(R.string.downloaded_regions),
                                tint = OSHIGreen,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = OSHIRed.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    RegionDownloadState.UPDATE_AVAILABLE -> {
                        IconButton(onClick = onDownload) {
                            Icon(
                                Icons.Default.SystemUpdate,
                                contentDescription = stringResource(R.string.map_update),
                                tint = OSHIPurple,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    RegionDownloadState.ERROR -> {
                        IconButton(onClick = onDownload) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.map_retry),
                                tint = OSHIRed,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }

            // ── Routing download section (shown when map is downloaded and routing is available) ──
            if (state == RegionDownloadState.DOWNLOADED && hasRoutingAvailable) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Navigation,
                        contentDescription = null,
                        tint = when (routingState) {
                            RoutingDownloadState.DOWNLOADED -> OSHIGreen
                            RoutingDownloadState.DOWNLOADING -> OSHIPurple
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.map_regions_routing_description),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        when (routingState) {
                            RoutingDownloadState.NOT_DOWNLOADED -> {
                                Text(
                                    stringResource(R.string.map_regions_routing_download_button) + " (" + routingSizeFormatted + ")",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 14.sp
                                )
                            }
                            RoutingDownloadState.DOWNLOADING -> {
                                val pct = ((routingProgress?.progress ?: 0f) * 100).toInt()
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = routingProgress?.progress ?: 0f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp),
                                    color = OSHIPurple,
                                    trackColor = OSHIPurple.copy(alpha = 0.15f)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "$pct%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OSHIPurple
                                )
                            }
                            RoutingDownloadState.DOWNLOADED -> {
                                Text(
                                    stringResource(R.string.map_regions_routing_ready),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OSHIGreen
                                )
                            }
                            RoutingDownloadState.ERROR -> {
                                Text(
                                    routingProgress?.error ?: stringResource(R.string.map_regions_download_failed),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OSHIRed
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    when (routingState) {
                        RoutingDownloadState.NOT_DOWNLOADED -> {
                            IconButton(onClick = onDownloadRouting, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.CloudDownload,
                                    contentDescription = stringResource(R.string.map_regions_routing_download_button),
                                    tint = OSHIPurple,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        RoutingDownloadState.DOWNLOADING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = OSHIPurple,
                                strokeWidth = 2.dp
                            )
                        }
                        RoutingDownloadState.DOWNLOADED -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = stringResource(R.string.map_regions_routing_ready),
                                    tint = OSHIGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(onClick = onDeleteRouting, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.delete),
                                        tint = OSHIRed.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        RoutingDownloadState.ERROR -> {
                            IconButton(onClick = onDownloadRouting, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.map_retry),
                                    tint = OSHIRed,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── Street index download section (shown when map is downloaded) ──
            if (state == RegionDownloadState.DOWNLOADED) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Signpost,
                        contentDescription = null,
                        tint = when (streetState) {
                            StreetDownloadState.DOWNLOADED -> OSHIGreen
                            StreetDownloadState.DOWNLOADING -> OSHIPurple
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.map_regions_street_index_description),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        when (streetState) {
                            StreetDownloadState.NOT_DOWNLOADED -> {
                                Text(
                                    stringResource(R.string.map_regions_street_index_download_button),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 14.sp
                                )
                            }
                            StreetDownloadState.DOWNLOADING -> {
                                val pct = ((streetProgress?.progress ?: 0f) * 100).toInt()
                                Spacer(modifier = Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = streetProgress?.progress ?: 0f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp),
                                    color = OSHIPurple,
                                    trackColor = OSHIPurple.copy(alpha = 0.15f)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "$pct%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OSHIPurple
                                )
                            }
                            StreetDownloadState.DOWNLOADED -> {
                                val sizeText = if (streetSizeFormatted.isNotBlank()) " ($streetSizeFormatted)" else ""
                                Text(
                                    stringResource(R.string.map_regions_street_index_ready) + sizeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OSHIGreen
                                )
                            }
                            StreetDownloadState.ERROR -> {
                                Text(
                                    streetProgress?.error ?: stringResource(R.string.map_regions_download_failed),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OSHIRed
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    when (streetState) {
                        StreetDownloadState.NOT_DOWNLOADED -> {
                            IconButton(onClick = onDownloadStreetIndex, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.CloudDownload,
                                    contentDescription = stringResource(R.string.map_regions_street_index_download_button),
                                    tint = OSHIPurple,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        StreetDownloadState.DOWNLOADING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = OSHIPurple,
                                strokeWidth = 2.dp
                            )
                        }
                        StreetDownloadState.DOWNLOADED -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = stringResource(R.string.map_regions_street_index_ready),
                                    tint = OSHIGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(onClick = onDeleteStreetIndex, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.delete),
                                        tint = OSHIRed.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        StreetDownloadState.ERROR -> {
                            IconButton(onClick = onDownloadStreetIndex, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.map_retry),
                                    tint = OSHIRed,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureChip(icon: ImageVector, label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = OSHIPurple.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = OSHIPurple,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = OSHIPurple,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * \uD83D\uDD27 P1 FIX (device walkthrough #2, 2026-08-13).
 *
 * The section header rendered the **raw server key**: `\uD83C\uDF0D africa` \u2014 lowercase,
 * unlocalised, and with the generic globe because this `when` only ever matched
 * Title Case. The live manifest emits `africa`, `americas`, `asia`, `europe`,
 * `middle_east`, `north_america`, `oceania`, `south_america`, so all eight values
 * missed and every header on device was `\uD83C\uDF0D <raw key>`. (It only showed up with
 * the live manifest; `OfflineMapManager`'s hardcoded fallback happens to use
 * Title Case, which is why it was never caught locally.)
 *
 * [continentKey] normalises whatever the server sends, and [continentDisplayName]
 * resolves it to a localised string \u2014 the same shape as iOS
 * `MapRegionDownloadView.swift:719-728` \u2192 `:33-41 localizedTitle`.
 */
/**
 * __MAP_PICKER_SEARCH_2026_10_14__ Does [region] match the picker's filter?
 *
 * [query] NO LONGER has to arrive trimmed and lower-cased, and this line used to say
 * it did. `__REGION_ALIAS_2026_10_21__` below folds the query through
 * [foldForRegionSearch] like both sides of every comparison, so case and diacritics are
 * handled here whatever the caller passed. The caller still folds once per keystroke
 * rather than once per region — there are 259 of those — but that is now an
 * OPTIMISATION, not a correctness requirement, and folding is idempotent so doing it
 * twice costs nothing. The old wording outlived its contract by eight days and took a
 * test with it, which asserted that "FRANCE" must NOT match France.
 *
 * Matching on the country code and the manifest id as well as the name is what
 * makes the filter useful for the Gulf: six of those countries share one name
 * stem, and "AE" or "ae" is what someone types when they do not know whether
 * the app calls it "UAE" or "United Arab Emirates".
 */
internal fun regionMatchesQuery(region: MapRegion, query: String): Boolean {
    if (query.isEmpty()) return true
    // __REGION_ALIAS_2026_10_21__ The three fields above all missed the thing
    // people actually type most: the country's name in their OWN language.
    // `localizedCountryName` is CLDR, so "maroc" now finds Morocco AND its 23
    // cities where it previously returned nothing at all. Folded for diacritics
    // too, so "cote" matches "Côte d'Ivoire" — the moment names are localized,
    // the filter has to survive the accents those names carry.
    val q = foldForRegionSearch(query)
    if (q.isEmpty()) return true
    if (foldForRegionSearch(region.name).contains(q)) return true
    if (foldForRegionSearch(region.country).contains(q)) return true
    if (foldForRegionSearch(region.id).contains(q)) return true
    val localized = region.localizedCountryName ?: return false
    return foldForRegionSearch(localized).contains(q)
}

/**
 * Lower-cased and diacritic-stripped, the form both sides of the picker's
 * comparison are tested in. Mirrors iOS `String.regionSearchFolded`
 * (`MapModels.swift`), which uses `.diacriticInsensitive` folding.
 */
internal fun foldForRegionSearch(s: String): String {
    val decomposed = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
    return buildString(decomposed.length) {
        for (ch in decomposed) {
            // Drop the combining marks NFD split off, keep everything else.
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) append(ch)
        }
    }.lowercase().trim()
}

internal fun continentKey(continent: String): String =
    continent.trim().lowercase().replace(' ', '_').replace('-', '_')

private fun continentEmoji(continent: String): String = when (continentKey(continent)) {
    "europe" -> "\uD83C\uDDEA\uD83C\uDDFA"
    "north_america" -> "\uD83C\uDDFA\uD83C\uDDF8"
    "south_america" -> "\uD83C\uDDE7\uD83C\uDDF7"
    "americas" -> "\uD83C\uDF0E"
    "asia" -> "\uD83C\uDDEF\uD83C\uDDF5"
    "middle_east" -> "\uD83C\uDF0D"
    "africa" -> "\uD83C\uDDFF\uD83C\uDDE6"
    "oceania" -> "\uD83C\uDDE6\uD83C\uDDFA"
    else -> "\uD83C\uDF0D"
}

@Composable
private fun continentDisplayName(continent: String): String = when (continentKey(continent)) {
    "europe" -> stringResource(R.string.map_continent_europe)
    "north_america" -> stringResource(R.string.map_continent_north_america)
    "south_america" -> stringResource(R.string.map_continent_south_america)
    "americas" -> stringResource(R.string.map_continent_americas)
    "asia" -> stringResource(R.string.map_continent_asia)
    "middle_east" -> stringResource(R.string.map_continent_middle_east)
    "africa" -> stringResource(R.string.map_continent_africa)
    "oceania" -> stringResource(R.string.map_continent_oceania)
    // Unknown key from a future manifest: at least Title-Case it rather than
    // printing `middle_east` at the user.
    else -> continentKey(continent).split('_')
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
}
