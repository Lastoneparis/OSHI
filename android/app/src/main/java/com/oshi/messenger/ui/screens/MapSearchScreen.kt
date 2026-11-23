package com.oshi.messenger.ui.screens

import androidx.compose.foundation.clickable
import com.oshi.messenger.ui.theme.OshiFilterChip
import com.oshi.messenger.ui.theme.OshiCard
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.oshi.messenger.R
import com.oshi.messenger.data.model.MapPOI
import com.oshi.messenger.data.model.POICategory
import com.oshi.messenger.data.model.StreetResult
import com.oshi.messenger.service.map.PoiCoverage
import com.oshi.messenger.ui.theme.OSHIPurple
import com.oshi.messenger.ui.theme.brandInk
import com.oshi.messenger.ui.theme.Oshi
import com.oshi.messenger.ui.viewmodel.MapViewModel

/**
 * MapSearchSheet — POI search content rendered inside a [ModalBottomSheet]
 * over the map (matches iOS UX). Replaces the old full-screen `MapSearchScreen`.
 *
 * Caller is responsible for hosting this in a `ModalBottomSheet { ... }` and
 * dismissing it (e.g. on POI selection or system back).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun MapSearchSheet(
    onPOISelected: (MapPOI) -> Unit = {},
    onCategorySelected: (POICategory) -> Unit = {},
    viewModel: MapViewModel = hiltViewModel()
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val addressResults by viewModel.addressResults.collectAsState()
    val streetResults by viewModel.streetResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val poiEmptyReason by viewModel.poiEmptyReason.collectAsState()
    val regionsWithoutPoiData by viewModel.regionsWithoutPoiData.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val activeCategoryFilters by viewModel.activeCategoryFilters.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 320.dp)
    ) {
        // Sheet header (replaces the old TopAppBar)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.map_search_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        // Body
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Search input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.map_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearSearch() }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.close))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        viewModel.performSearch(searchQuery)
                        keyboardController?.hide()
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OSHIPurple,
                    cursorColor = OSHIPurple
                )
            )

            // Category chips
            if (searchQuery.isEmpty()) {
                Text(
                    stringResource(R.string.map_search_categories),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp)
                )

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categoryItems) { item ->
                        // Multi-select: chip is "active" when its category is in
                        // the on-map filter set. Tap toggles add/remove.
                        val isSelected = item.category in activeCategoryFilters
                        OshiFilterChip(
                            selected = isSelected,
                            onClick = { onCategorySelected(item.category) },
                            label = { Text(stringResource(item.category.chipLabelRes)) },
                            leadingIcon = {
                                Icon(
                                    item.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = OSHIPurple,
                                selectedLabelColor = androidx.compose.ui.graphics.Color.White,
                                selectedLeadingIconColor = androidx.compose.ui.graphics.Color.White
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Category grid — taps toggle that category's markers on the map.
                CategoryGrid(
                    activeCategories = activeCategoryFilters,
                    onCategorySelected = onCategorySelected
                )
            }

            // Search results (grouped)
            if (searchQuery.isNotEmpty() || selectedCategory != null) {
                val totalResults = searchResults.size + addressResults.size + streetResults.size

                if (isSearching) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = OSHIPurple)
                    }
                } else if (totalResults == 0) {
                    // 🔧 P0 FIX (map audit 2026-08-23 §2.2b / §3): an empty result
                    // used to render identically whether the query genuinely
                    // matched nothing, no region was downloaded, or the downloaded
                    // region ships NO POI ARTIFACT AT ALL (22 of the 254 live
                    // regions — us, jp, br, ca, ru, id and 16 metros). The last
                    // case is permanent: no amount of retrying, and no network,
                    // will ever produce a hit, because there is no database to
                    // search. Blaming the connection for it was a lie.
                    EmptyResultsState(
                        reason = poiEmptyReason,
                        regionsWithoutPoiData = regionsWithoutPoiData
                    )
                } else {
                    Text(
                        stringResource(R.string.map_search_results_count, totalResults),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        // ── Street Index results ──
                        if (streetResults.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    icon = Icons.Default.Signpost,
                                    title = stringResource(R.string.map_search_streets)
                                )
                            }
                            items(streetResults, key = { "street_${it.name}_${it.lat}_${it.lon}" }) { street ->
                                StreetResultItem(
                                    street = street,
                                    onClick = {
                                        // Convert street to POI for map centering
                                        val poi = MapPOI(
                                            id = "street_${street.name}_${street.lat}",
                                            name = street.name,
                                            category = POICategory.OTHER,
                                            lat = street.lat,
                                            lon = street.lon,
                                            address = if (street.city.isNotBlank()) street.city else "",
                                            regionId = "street_index"
                                        )
                                        viewModel.selectPOI(poi)
                                        keyboardController?.hide()
                                        onPOISelected(poi)
                                    }
                                )
                            }
                        }

                        // ── Places (POI name matches) ──
                        if (searchResults.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    icon = Icons.Default.Place,
                                    title = stringResource(R.string.map_search_places)
                                )
                            }
                            items(searchResults, key = { it.id }) { poi ->
                                POIResultItem(
                                    poi = poi,
                                    onClick = {
                                        viewModel.selectPOI(poi)
                                        keyboardController?.hide()
                                        onPOISelected(poi)
                                    }
                                )
                            }
                        }

                        // ── Streets & Addresses (POI address matches) ──
                        if (addressResults.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    icon = Icons.Default.EditRoad,
                                    title = stringResource(R.string.map_search_streets_addresses)
                                )
                            }
                            items(addressResults, key = { "addr_${it.id}" }) { poi ->
                                POIResultItem(
                                    poi = poi,
                                    onClick = {
                                        viewModel.selectPOI(poi)
                                        keyboardController?.hide()
                                        onPOISelected(poi)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What an empty-result state actually renders. */
private data class EmptyResultCopy(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val emphasised: Boolean
)

/**
 * The four ways a POI search can come back with nothing, rendered as four
 * different things.
 *
 * Only [PoiCoverage.EmptyResultReason.NO_MATCH] may blame the query or the
 * connection. The other three are conditions of the *data*, and telling a user
 * to "check your connection" when their region was published without a places
 * index sends them to fix something that is not broken.
 */
@Composable
private fun EmptyResultsState(
    reason: PoiCoverage.EmptyResultReason?,
    regionsWithoutPoiData: List<String>
) {
    // A `when` EXPRESSION, so adding a reason without a message is a compile
    // error rather than a silently unhandled case.
    val copy: EmptyResultCopy = when (reason) {
        PoiCoverage.EmptyResultReason.NO_REGION_DOWNLOADED -> EmptyResultCopy(
            icon = Icons.Default.CloudDownload,
            title = stringResource(R.string.map_search_no_region_downloaded),
            body = stringResource(R.string.map_search_no_region_downloaded_desc),
            emphasised = true
        )
        PoiCoverage.EmptyResultReason.NO_POI_ARTIFACT -> EmptyResultCopy(
            icon = Icons.Default.LayersClear,
            title = stringResource(R.string.map_search_no_poi_data),
            body = stringResource(R.string.map_search_no_poi_data_desc),
            emphasised = true
        )
        PoiCoverage.EmptyResultReason.PARTIAL_POI_ARTIFACT -> EmptyResultCopy(
            icon = Icons.Default.LayersClear,
            title = stringResource(R.string.map_search_partial_poi_data),
            body = stringResource(
                R.string.map_search_partial_poi_data_desc,
                regionsWithoutPoiData.joinToString(", ")
            ),
            emphasised = true
        )
        // `null` means no search has finished yet; treat it exactly like a plain
        // miss rather than inventing a cause for it.
        PoiCoverage.EmptyResultReason.NO_MATCH, null -> EmptyResultCopy(
            icon = Icons.Default.SearchOff,
            title = stringResource(R.string.map_search_no_results),
            body = stringResource(R.string.map_search_no_results_desc),
            emphasised = false
        )
    }
    val icon = copy.icon
    val title = copy.title
    val body = copy.body
    val emphasised = copy.emphasised

    // `errorContainer`/`onErrorContainer` is an M3 pair guaranteed to contrast in
    // both schemes — the "brand ink on a brand tint" bug has already been found
    // four times on these screens.
    val tint =
        if (emphasised) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color =
                if (emphasised) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = tint
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = tint
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = tint
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = OSHIPurple,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = OSHIPurple
        )
    }
}

@Composable
private fun StreetResultItem(street: StreetResult, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                street.name,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            val subtitle = buildList {
                if (street.city.isNotBlank()) add(street.city)
                if (street.highwayType.isNotBlank()) add(street.highwayType.replaceFirstChar { it.uppercase() })
            }.joinToString(" - ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        leadingContent = {
            Surface(
                // 0.10 brand wash + raw brand ink = 1.9:1 in the dark scheme.
                // Oshi.ContainerTintAlpha (0.22) + brandInk() is the pair that
                // survives both schemes - see ui/theme/Tokens.kt.
                color = OSHIPurple.copy(alpha = Oshi.ContainerTintAlpha),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Signpost,
                        contentDescription = null,
                        tint = brandInk(),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        },
        trailingContent = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    stringResource(R.string.map_search_street_badge),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun POIResultItem(poi: MapPOI, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                poi.name,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                if (poi.address.isNotBlank()) {
                    Text(
                        poi.address,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        leadingContent = {
            Surface(
                // 0.10 brand wash + raw brand ink = 1.9:1 in the dark scheme.
                // Oshi.ContainerTintAlpha (0.22) + brandInk() is the pair that
                // survives both schemes - see ui/theme/Tokens.kt.
                color = OSHIPurple.copy(alpha = Oshi.ContainerTintAlpha),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        categoryToIcon(poi.category),
                        contentDescription = null,
                        tint = brandInk(),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        },
        trailingContent = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    stringResource(poi.category.displayNameRes),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun CategoryGrid(
    activeCategories: Set<POICategory> = emptySet(),
    onCategorySelected: (POICategory) -> Unit
) {
    // Hardcoded English labels removed; we now read the localized chip
    // label from POICategory.chipLabelRes inside the row, so the grid
    // automatically respects the user's locale.
    val gridCategories = listOf(
        CategoryGridItem(POICategory.RESTAURANT, Icons.Default.Restaurant),
        CategoryGridItem(POICategory.CAFE, Icons.Default.LocalCafe),
        CategoryGridItem(POICategory.SUPERMARKET, Icons.Default.ShoppingCart),
        CategoryGridItem(POICategory.GAS_STATION, Icons.Default.LocalGasStation),
        CategoryGridItem(POICategory.PHARMACY, Icons.Default.Medication),
        CategoryGridItem(POICategory.HOSPITAL, Icons.Default.LocalHospital),
        CategoryGridItem(POICategory.HOTEL, Icons.Default.Hotel),
        CategoryGridItem(POICategory.ATM, Icons.Default.Atm),
        CategoryGridItem(POICategory.PARKING, Icons.Default.LocalParking),
        CategoryGridItem(POICategory.EV_CHARGING, Icons.Default.ElectricBolt),
        CategoryGridItem(POICategory.BUS_STATION, Icons.Default.DirectionsBus),
        CategoryGridItem(POICategory.TRAIN_STATION, Icons.Default.Train)
    )

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        gridCategories.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { item ->
                    val isActive = item.category in activeCategories
                    OshiCard(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clickable { onCategorySelected(item.category) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActive) {
                                OSHIPurple
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                item.icon,
                                contentDescription = null,
                                tint = if (isActive) androidx.compose.ui.graphics.Color.White else brandInk(),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(item.category.chipLabelRes),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isActive) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Unspecified,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // Fill remaining slots in incomplete rows
                repeat(4 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private data class CategoryGridItem(
    val category: POICategory,
    val icon: ImageVector
)

private data class CategoryChipItem(
    val category: POICategory,
    val icon: ImageVector
)

// Labels are no longer hardcoded — the FilterChip resolves
// `category.chipLabelRes` via stringResource so the user's locale wins.
private val categoryItems = listOf(
    CategoryChipItem(POICategory.RESTAURANT, Icons.Default.Restaurant),
    CategoryChipItem(POICategory.CAFE, Icons.Default.LocalCafe),
    CategoryChipItem(POICategory.SUPERMARKET, Icons.Default.ShoppingCart),
    CategoryChipItem(POICategory.GAS_STATION, Icons.Default.LocalGasStation),
    CategoryChipItem(POICategory.PHARMACY, Icons.Default.Medication),
    CategoryChipItem(POICategory.HOSPITAL, Icons.Default.LocalHospital),
    CategoryChipItem(POICategory.HOTEL, Icons.Default.Hotel),
    CategoryChipItem(POICategory.ATM, Icons.Default.Atm),
    CategoryChipItem(POICategory.PARKING, Icons.Default.LocalParking),
    CategoryChipItem(POICategory.EV_CHARGING, Icons.Default.ElectricBolt),
    CategoryChipItem(POICategory.BUS_STATION, Icons.Default.DirectionsBus),
    CategoryChipItem(POICategory.TRAIN_STATION, Icons.Default.Train)
)

private fun categoryToIcon(category: POICategory): ImageVector = when (category) {
    POICategory.RESTAURANT -> Icons.Default.Restaurant
    POICategory.CAFE -> Icons.Default.LocalCafe
    POICategory.BAR -> Icons.Default.LocalBar
    POICategory.HOTEL -> Icons.Default.Hotel
    POICategory.HOSPITAL -> Icons.Default.LocalHospital
    POICategory.PHARMACY -> Icons.Default.Medication
    POICategory.GAS_STATION -> Icons.Default.LocalGasStation
    POICategory.ATM -> Icons.Default.Atm
    POICategory.BANK -> Icons.Default.AccountBalance
    POICategory.SUPERMARKET -> Icons.Default.ShoppingCart
    POICategory.PARKING -> Icons.Default.LocalParking
    POICategory.POLICE -> Icons.Default.LocalPolice
    POICategory.POST_OFFICE -> Icons.Default.LocalPostOffice
    POICategory.SCHOOL -> Icons.Default.School
    POICategory.MUSEUM -> Icons.Default.Museum
    POICategory.PARK -> Icons.Default.Park
    POICategory.BEACH -> Icons.Default.BeachAccess
    POICategory.AIRPORT -> Icons.Default.Flight
    POICategory.TRAIN_STATION -> Icons.Default.Train
    POICategory.BUS_STATION -> Icons.Default.DirectionsBus
    POICategory.EV_CHARGING -> Icons.Default.ElectricBolt
    POICategory.EMBASSY -> Icons.Default.AccountBalance
    POICategory.PLACE_OF_WORSHIP -> Icons.Default.Church
    POICategory.UNIVERSITY -> Icons.Default.School
    POICategory.GYM -> Icons.Default.FitnessCenter
    POICategory.TOILET -> Icons.Default.Wc
    POICategory.CINEMA -> Icons.Default.Movie
    POICategory.OTHER -> Icons.Default.Place
}
