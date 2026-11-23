import SwiftUI

// MARK: - MapRegionDownloadView

struct MapRegionDownloadView: View {

    @ObservedObject private var mapManager = OfflineMapManager.shared
    @ObservedObject private var routingManager = OfflineRoutingManager.shared
    /// Observed so the auto-download triggered by `OfflineMapManager.finalize`
    /// (after the tile bulk download finishes) is reflected in the UI without
    /// the user having to tap "Download street index" again.
    @ObservedObject private var streetDB = OfflineStreetDatabase.shared
    /// Observed so the aggregate progress ring refreshes when the per-region
    /// road graph download advances.
    @ObservedObject private var roadGraph = OfflineRoadGraphDownloader.shared
    @State private var searchText: String = ""
    @State private var regionToDelete: String?
    @State private var showDeleteConfirmation = false
    @Environment(\.dismiss) private var dismiss

    private let brandColor = Theme.brand

    // MARK: - Continent Grouping

    private enum Continent: String, CaseIterable {
        case europe = "Europe"
        case americas = "Americas"
        case africa = "Africa"
        case asiaOceania = "Asia / Oceania"
        case middleEast = "Middle East"

        /// Localized section header label.
        var localizedTitle: String {
            switch self {
            case .europe: return "map.continent.europe".localized
            case .americas: return "map.continent.americas".localized
            case .africa: return "map.continent.africa".localized
            case .asiaOceania: return "map.continent.asia".localized
            case .middleEast: return "map.continent.middle_east".localized
            }
        }
    }

    // MARK: - Body

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                searchBar
                ScrollView {
                    LazyVStack(spacing: 16) {
                        offlineInfoBanner
                        downloadedSection
                        availableSection
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                    .padding(.bottom, 24)
                    .frame(maxWidth: 700)
                    .frame(maxWidth: .infinity)
                }
                storageFooter
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("offline_maps_regions".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title3)
                            .foregroundColor(.secondary)
                    }
                    .accessibilityLabel("common.close".localized)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        mapManager.fetchManifest()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .font(.body.weight(.medium))
                    }
                    .accessibilityLabel("map.regions.refresh".localized)
                }
            }
            .alert("delete_region_title".localized, isPresented: $showDeleteConfirmation) {
                Button("cancel".localized, role: .cancel) {
                    regionToDelete = nil
                }
                Button("delete".localized, role: .destructive) {
                    if let regionId = regionToDelete {
                        mapManager.deleteRegion(regionId: regionId)
                    }
                    regionToDelete = nil
                }
            } message: {
                Text("delete_region_message".localized)
            }
            .onAppear {
                if mapManager.manifest == nil {
                    mapManager.fetchManifest()
                }
            }
            // The legacy `"Download offline navigation?"` alert that used to
            // fire here after every tile completion was removed. The unified
            // download path in `OfflineMapManager.downloadRegion` now kicks
            // off routing + streets + roadgraph in parallel the moment the
            // user taps Download once, so re-asking via an alert after tiles
            // finish is redundant and confusing — the user already opted in
            // by tapping Download. If a region has no routing on the server,
            // the in-row error message under the routing icon surfaces that
            // (instead of a modal). Same logic for the routingPromptSkipped_*
            // UserDefaults — no longer needed.
        }
    }

    // MARK: - Offline Info Banner (Organic Maps style)

    private var offlineInfoBanner: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "wifi.slash")
                    .font(.scaled(28))
                    .foregroundColor(brandColor)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 4) {
                    Text("map.regions.banner.title".localized)
                        .font(.scaled(15, weight: .semibold))
                    Text("map.regions.banner.subtitle".localized)
                        .font(.scaled(12))
                        .foregroundColor(.secondary)
                }
                Spacer()
            }
            .accessibilityElement(children: .combine)

            HStack(spacing: 16) {
                offlineFeatureChip(icon: "map.fill", label: "map.regions.banner.feature.tiles".localized)
                offlineFeatureChip(icon: "mappin.and.ellipse", label: "map.regions.banner.feature.poi".localized)
                offlineFeatureChip(icon: "arrow.triangle.turn.up.right.diamond.fill", label: "map.regions.banner.feature.routing".localized)
            }

            Text("map.regions.banner.description".localized)
                .font(.scaled(12))
                .foregroundColor(.secondary)
                .lineSpacing(2)
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(brandColor.opacity(0.06))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(brandColor.opacity(0.15), lineWidth: 1)
                )
        )
    }

    private func offlineFeatureChip(icon: String, label: String) -> some View {
        HStack(spacing: 5) {
            Image(systemName: icon)
                .font(.scaled(11, weight: .semibold))
                .foregroundColor(brandColor)
                .accessibilityHidden(true)
            Text(label)
                .font(.scaled(11, weight: .medium))
                .foregroundColor(.primary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .oledGlassCard(RoundedRectangle(cornerRadius: 8), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(8)
    }

    // MARK: - Search Bar

    private var searchBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundColor(.secondary)
                .font(.body)
                .accessibilityHidden(true)
            TextField("search_regions".localized, text: $searchText)
                .textFieldStyle(.plain)
                .autocorrectionDisabled()
            if !searchText.isEmpty {
                Button {
                    searchText = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.secondary)
                }
                .accessibilityLabel("common.clear".localized)
            }
        }
        .padding(10)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    // MARK: - Downloaded Regions

    @ViewBuilder
    private var downloadedSection: some View {
        let downloaded = filteredDownloadedRegions
        if !downloaded.isEmpty {
            sectionHeader(title: "downloaded_regions".localized, icon: "checkmark.circle.fill")
            ForEach(downloaded, id: \.regionId) { metadata in
                downloadedRegionCard(metadata: metadata)
            }
        }
    }

    private var filteredDownloadedRegions: [DownloadedRegionMetadata] {
        let all = mapManager.downloadedRegions.sorted { $0.regionId < $1.regionId }
        guard !searchText.isEmpty else { return all }
        let query = searchText.lowercased()
        return all.filter { meta in
            let region = regionFromManifest(id: meta.regionId)
            let name = region?.name ?? meta.regionId
            return name.lowercased().contains(query)
        }
    }

    private func downloadedRegionCard(metadata: DownloadedRegionMetadata) -> some View {
        let region = regionFromManifest(id: metadata.regionId)
        let displayName = region?.name ?? metadata.regionId
        let flag = region?.countryFlag ?? ""
        // __ROUTING_ROW_READS_THE_ROADGRAPH_2026_10_14__
        //
        // This row used to describe `files.routing` -- the legacy OSRM/GraphHopper
        // tarball -- in all three of its states. That file exists for 56 regions
        // and the manifest advertises it with `size: 0` for the rest, so on
        // 2026-10-14 the measured result was:
        //
        //   files/routing/fr-routing.tar.gz      404
        //   files/routing/fr-ara-routing.tar.gz  404
        //   files/routing/om-routing.tar.gz      404
        //   files/routing/de-routing.tar.gz      200
        //
        // So "Download offline navigation" 404'd for France, for every French
        // sub-region, and for the Gulf countries -- while the file the router
        // ACTUALLY reads (road_graph.db, from `files.roadgraph`) had already been
        // fetched by the unified Download path. The button offered a download
        // that could not succeed for data the user already had.
        //
        // Android has read `files.roadgraph` here since 2026-08-13
        // (OfflineRoutingManager.kt:132). This is the same rule.
        //
        // The legacy path is kept as a fallback, not deleted: 56 regions do have
        // a tarball, and a user who installed one before today still has its
        // `.routing_ready` marker on disk.
        let advertisesRoadGraph = region?.files.roadgraph?.isAvailable == true
        let hasRouting = OfflineRoadGraphDownloader.shared.isRoadGraphAvailable(regionId: metadata.regionId)
            || routingManager.isRoutingAvailable(regionId: metadata.regionId)
        let isRoutingDownloading = advertisesRoadGraph
            ? roadGraph.isDownloading[metadata.regionId] == true
            : routingManager.isDownloading[metadata.regionId] == true
        let routingProgress = advertisesRoadGraph
            ? (roadGraph.downloadProgress[metadata.regionId] ?? 0.0)
            : (routingManager.downloadProgress[metadata.regionId] ?? 0.0)
        let isUpdateAvailable: Bool = {
            if case .updateAvailable = mapManager.downloadStates[metadata.regionId] {
                return true
            }
            return false
        }()

        return VStack(spacing: 0) {
            HStack(spacing: 12) {
                Text(flag)
                    .font(.title2)

                VStack(alignment: .leading, spacing: 3) {
                    Text(displayName)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.primary)
                    HStack(spacing: 6) {
                        Text(formatBytes(metadata.totalSize))
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text("--")
                            .font(.caption)
                            .foregroundColor(.secondary.opacity(0.5))
                        Text(formattedDate(metadata.downloadDate))
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    // Routing status indicator
                    HStack(spacing: 4) {
                        Image(systemName: "map.fill").font(.scaled(9))
                            .foregroundColor(.green)
                            .accessibilityHidden(true)
                        Image(systemName: "mappin").font(.scaled(9))
                            .foregroundColor(.green)
                            .accessibilityHidden(true)
                        Image(systemName: "arrow.triangle.turn.up.right.diamond.fill").font(.scaled(9))
                            .foregroundColor(hasRouting ? .green : .secondary.opacity(0.4))
                            .accessibilityHidden(true)
                        if hasRouting {
                            Text("map.regions.routing.label".localized)
                                .font(.scaled(10))
                                .foregroundColor(.green)
                        }
                    }
                }

                Spacer()

                if isUpdateAvailable {
                    Button {
                        #if !targetEnvironment(macCatalyst)
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        #endif
                        if let region = regionFromManifest(id: metadata.regionId) {
                            mapManager.downloadRegion(region)
                        }
                    } label: {
                        Image(systemName: "arrow.down.circle")
                            .font(.title3)
                            .foregroundColor(brandColor)
                            .frame(width: 44, height: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("map.regions.update".localized)
                }

                Button {
                    #if !targetEnvironment(macCatalyst)
                    UIImpactFeedbackGenerator(style: .rigid).impactOccurred()
                    #endif
                    regionToDelete = metadata.regionId
                    showDeleteConfirmation = true
                } label: {
                    Image(systemName: "trash")
                        .font(.subheadline)
                        .foregroundColor(.red.opacity(0.8))
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("delete".localized)
            }
            .padding(14)

            // Routing download progress bar or download button
            if isRoutingDownloading {
                VStack(spacing: 4) {
                    ProgressView(value: routingProgress)
                        .tint(brandColor)
                    Text("map.regions.routing.downloading".localized)
                        .font(.scaled(11))
                        .foregroundColor(.secondary)
                }
                .padding(.horizontal, 14)
                .padding(.bottom, 10)
            } else if !hasRouting {
                Button {
                    #if !targetEnvironment(macCatalyst)
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    #endif
                    if let region = regionFromManifest(id: metadata.regionId) {
                        // The ROAD GRAPH is what `MapRoutingEngine` reads, so it is
                        // what "Download offline navigation" must fetch.
                        //
                        // 2026-11-02: the `else` here used to fall back to the OSRM
                        // archive, on the stated grounds that the road graph existed
                        // "for all but 56 regions". Counted against the live
                        // manifest: 272 of 272 regions ship one, so the fallback was
                        // unreachable — and it led to a 522 MB download that nothing
                        // in this app can open. A region genuinely missing a road
                        // graph has no offline navigation to offer, and saying so is
                        // the honest answer.
                        if region.files.roadgraph?.isAvailable == true {
                            OfflineRoadGraphDownloader.shared.downloadRoadGraph(region: region)
                        } else {
                            routingManager.reportNoRoutingData(regionId: metadata.regionId)
                        }
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "arrow.down.circle.fill")
                            .font(.scaled(13))
                            .accessibilityHidden(true)
                        Text("map.regions.routing.download_button".localized)
                            .font(.scaled(12, weight: .medium))
                        // 🔧 FIX (2026-07-28): the layer is optional — a region
                        // with no bundle shows the button without a bogus
                        // "0 MB" suffix.
                        //
                        // Reads the ROAD GRAPH's size first (see
                        // __ROUTING_ROW_READS_THE_ROADGRAPH_2026_10_14__). This is
                        // the number that decides whether the download is even
                        // possible -- Auvergne-Rhone-Alpes is 1052 MB against
                        // 9670 MB for all of France -- and `files.routing` is
                        // `size: 0` for almost every region, so the size used to
                        // be omitted exactly when it mattered most.
                        if let region = regionFromManifest(id: metadata.regionId),
                           let layer = region.files.roadgraph ?? region.files.routing,
                           layer.realSize > 0 {
                            let sizeMB = Double(layer.realSize) / (1024.0 * 1024.0)
                            Text("(\(String(format: "%.0f MB", sizeMB)))")
                                .font(.scaled(11))
                                .foregroundColor(.secondary)
                        }
                    }
                    .foregroundColor(brandColor)
                    .padding(.vertical, 6)
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 14)
                .padding(.bottom, 8)
            }

            // Routing error message
            if let error = routingManager.downloadError[metadata.regionId] {
                HStack(spacing: 4) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.scaled(10))
                        .foregroundColor(.red)
                    Text(error)
                        .font(.scaled(10))
                        .foregroundColor(.red)
                }
                .padding(.horizontal, 14)
                .padding(.bottom, 8)
            }

            // Street index download section
            streetIndexSection(regionId: metadata.regionId)
        }
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    // MARK: - Street Index Section

    @ViewBuilder
    private func streetIndexSection(regionId: String) -> some View {
        let hasStreetIndex = streetDB.isStreetIndexAvailable(regionId: regionId)
        let isDownloadingStreet = streetDB.isDownloading[regionId] == true
        let progress = streetDB.downloadProgress[regionId] ?? 0.0

        if isDownloadingStreet {
            VStack(spacing: 4) {
                ProgressView(value: progress)
                    .tint(.orange)
                Text("map.regions.street_index.downloading".localized)
                    .font(.scaled(11))
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 10)
        } else if hasStreetIndex {
            HStack(spacing: 4) {
                Image(systemName: "road.lanes").font(.scaled(9))
                    .foregroundColor(.green)
                Text("map.regions.street_index.label".localized)
                    .font(.scaled(10))
                    .foregroundColor(.green)
                Spacer()
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 8)
        } else {
            Button {
                #if !targetEnvironment(macCatalyst)
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                #endif
                downloadStreetIndex(regionId: regionId)
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.down.circle.fill")
                        .font(.scaled(13))
                        .accessibilityHidden(true)
                    Text("map.regions.street_index.download_button".localized)
                        .font(.scaled(12, weight: .medium))
                }
                .foregroundColor(.orange)
                .padding(.vertical, 6)
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 14)
            .padding(.bottom, 8)
        }

        if let error = streetDB.downloadError[regionId] {
            HStack(spacing: 4) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.scaled(10))
                    .foregroundColor(.red)
                Text(error)
                    .font(.scaled(10))
                    .foregroundColor(.red)
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 8)
        }
    }

    private func downloadStreetIndex(regionId: String) {
        guard let region = regionFromManifest(id: regionId) else { return }

        // The singleton publishes progress + errors via @Published dicts the
        // view observes; the completion handler is only used for legacy hooks
        // that aren't relevant here.
        OfflineStreetDatabase.shared.downloadStreetIndex(
            region: region,
            progress: { _ in },
            completion: { _ in }
        )
    }

    // MARK: - Available Regions

    @ViewBuilder
    private var availableSection: some View {
        if let manifest = mapManager.manifest {
            let available = filteredAvailableRegions(from: manifest.regions)
            ForEach(Continent.allCases, id: \.self) { continent in
                let regions = available.filter { continentFor($0) == continent }
                if !regions.isEmpty {
                    // Split into full countries (short ID, no dash) and cities/regions
                    // __REGION_ALIAS_2026_10_21__ Sorted by the name actually
                    // shown. Sorting on `name` while displaying `displayName`
                    // puts a localized list in English alphabetical order,
                    // which looks like a random shuffle to the user reading it.
                    let countries = regions.filter { !$0.id.contains("-") }
                        .sorted { $0.displayName.localizedStandardCompare($1.displayName) == .orderedAscending }
                    let cities = regions.filter { $0.id.contains("-") }
                        .sorted { $0.displayName.localizedStandardCompare($1.displayName) == .orderedAscending }

                    sectionHeader(title: continent.localizedTitle, icon: iconForContinent(continent))

                    if !countries.isEmpty {
                        subSectionHeader(title: "map.regions.subsection.full_countries".localized, icon: "globe")
                        ForEach(countries, id: \.id) { region in
                            availableRegionRow(region: region)
                        }
                    }

                    if !cities.isEmpty {
                        subSectionHeader(title: "map.regions.subsection.cities_regions".localized, icon: "building.2")
                        ForEach(cities, id: \.id) { region in
                            availableRegionRow(region: region)
                        }
                    }
                }
            }
        }
    }

    private func filteredAvailableRegions(from regions: [MapRegion]) -> [MapRegion] {
        let downloadedIds = Set(mapManager.downloadedRegions.map { $0.regionId })
        let notDownloaded = regions.filter { !downloadedIds.contains($0.id) }
        guard !searchText.isEmpty else { return notDownloaded }
        let query = searchText.regionSearchFolded
        guard !query.isEmpty else { return notDownloaded }
        // Name alone missed the two things people actually type for a country
        // they cannot spell: its code ("AE") and its manifest id ("fr-re").
        //
        // __REGION_ALIAS_2026_10_21__ …and all three missed the thing people
        // actually type most: the country's name in their OWN language.
        // `searchTerms` adds the CLDR name, so "maroc" finds Morocco (and its
        // 23 cities) where it previously returned nothing at all.
        return notDownloaded.filter { region in
            region.searchTerms.contains { $0.regionSearchFolded.contains(query) }
        }
    }

    private func availableRegionRow(region: MapRegion) -> some View {
        HStack(spacing: 12) {
            Text(region.countryFlag)
                .font(.title2)

            VStack(alignment: .leading, spacing: 3) {
                Text(region.displayName)
                    .font(.subheadline.weight(.medium))
                    .foregroundColor(.primary)
                HStack(spacing: 4) {
                    Text(region.displaySize)
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text("·")
                        .font(.caption)
                        .foregroundColor(.secondary.opacity(0.5))
                    HStack(spacing: 3) {
                        Image(systemName: "map.fill").font(.scaled(8))
                        Image(systemName: "mappin").font(.scaled(8))
                        Image(systemName: "location.fill").font(.scaled(8))
                    }
                    .foregroundColor(.secondary.opacity(0.6))
                    .accessibilityHidden(true)
                }
            }

            Spacer()

            downloadButton(for: region)
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    @ViewBuilder
    private func downloadButton(for region: MapRegion) -> some View {
        let state = mapManager.downloadStates[region.id] ?? .notDownloaded

        switch state {
        case .downloading(_):
            // Use the AGGREGATE byte-weighted progress, not the tile-only
            // sub-progress. Without this, a sub-region whose tiles are
            // already 99 % cached (because the parent country was downloaded
            // earlier) shows 99 % the moment the user taps — even though
            // the 5 GB shared routing file hasn't started transferring yet.
            // Observe the three singletons so the ring updates as each
            // component progresses.
            let progress = mapManager.aggregateProgress(for: region)
            // No-op reads to register Combine dependencies on the singletons
            // whose @Published progress dicts feed aggregateProgress.
            let _ = routingManager.downloadProgress[region.id]
            let _ = streetDB.downloadProgress[region.id]
            let _ = OfflineRoadGraphDownloader.shared.downloadProgress[region.id]
            // __MAP_STALLED_AT_24_PERCENT_2026_10_28__ …and on the ERROR dicts, which is
            // the dependency that was missing. `roadGraph` is already an @ObservedObject
            // here, but nothing in this body ever touched its `downloadError`, so a
            // roadgraph that died did not even redraw the row — it just stopped moving.
            let _ = roadGraph.downloadError[region.id]
            let _ = mapManager.poiDownloadError[region.id]
            let failure = mapManager.componentFailure(for: region)
            HStack(spacing: 6) {
                Text("\(Int(progress * 100))%")
                    .font(.caption2.monospacedDigit())
                    .foregroundColor(.secondary)
                    .accessibilityHidden(true)
                Button {
                    mapManager.cancelDownload(regionId: region.id)
                } label: {
                    ZStack {
                        Circle()
                            .stroke(Color.secondary.opacity(0.2), lineWidth: 3)
                            .frame(width: 28, height: 28)
                        Circle()
                            .trim(from: 0, to: CGFloat(progress))
                            .stroke(brandColor, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                            .frame(width: 28, height: 28)
                            .rotationEffect(.degrees(-90))
                            .animation(.linear(duration: 0.2), value: progress)
                        Image(systemName: "xmark")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.secondary)
                    }
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("cancel".localized)
                .accessibilityValue("\(Int(progress * 100))%")
            }
            // The number stops moving because a component stopped, and until now the
            // screen said nothing at all: `aggregateProgressFloor` holds the ring at the
            // last good value for ever, which reads as a download that is merely slow.
            // Say which part stopped and why, next to the number it is holding back.
            if let failure = failure {
                HStack(alignment: .top, spacing: 4) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.scaled(10))
                        .foregroundColor(.orange)
                    Text("\(failure.component.localized): \(failure.message)")
                        .font(.scaled(10))
                        .foregroundColor(.orange)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.top, 4)
                .accessibilityElement(children: .combine)
            }

        case .downloaded(_):
            Image(systemName: "checkmark.circle.fill")
                .font(.title3)
                .foregroundColor(.green)
                .accessibilityLabel("downloaded_regions".localized)

        case .updateAvailable(_, _):
            Button {
                #if !targetEnvironment(macCatalyst)
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                #endif
                mapManager.downloadRegion(region)
            } label: {
                Image(systemName: "arrow.down.circle")
                    .font(.title3)
                    .foregroundColor(.orange)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("map.regions.update".localized)

        case .failed(_):
            Button {
                #if !targetEnvironment(macCatalyst)
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                #endif
                mapManager.downloadRegion(region)
            } label: {
                Image(systemName: "exclamationmark.arrow.circlepath")
                    .font(.title3)
                    .foregroundColor(.red)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("map.regions.retry".localized)

        case .notDownloaded:
            Button {
                #if !targetEnvironment(macCatalyst)
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                #endif
                mapManager.downloadRegion(region)
            } label: {
                Image(systemName: "arrow.down.circle.fill")
                    .font(.title3)
                    .foregroundColor(brandColor)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("map.regions.download".localized)
        }
    }

    // MARK: - Storage Footer

    private var storageFooter: some View {
        HStack {
            Image(systemName: "internaldrive")
                .font(.caption)
                .foregroundColor(.secondary)
                .accessibilityHidden(true)
            Text(storageText)
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
    }

    private var storageText: String {
        let usedFormatted = mapManager.storageUsedFormatted
        let freeBytes = MapStorageManager.shared.deviceFreeSpace()
        let freeGB = Double(freeBytes) / (1024.0 * 1024.0 * 1024.0)
        return String(format: "%@ %@ | %.1f GB %@",
                       usedFormatted, "used".localized,
                       freeGB, "storage.free".localized)
    }

    // MARK: - Section Header

    private func subSectionHeader(title: String, icon: String) -> some View {
        HStack(spacing: 5) {
            Image(systemName: icon)
                .font(.caption2.weight(.semibold))
                .foregroundColor(brandColor)
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundColor(brandColor)
            Spacer()
        }
        .padding(.leading, 8)
        .padding(.top, 6)
    }

    private func sectionHeader(title: String, icon: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: icon)
                .font(.caption.weight(.semibold))
                .foregroundColor(brandColor)
            Text(title)
                .font(.footnote.weight(.semibold))
                .foregroundColor(.secondary)
                .textCase(.uppercase)
            Spacer()
        }
        .padding(.top, 8)
    }

    // MARK: - Helpers

    private func formatBytes(_ bytes: Int) -> String {
        let mb = Double(bytes) / (1024.0 * 1024.0)
        if mb >= 1024.0 {
            return String(format: "%.1f GB", mb / 1024.0)
        }
        return String(format: "%.0f MB", mb)
    }

    private func formattedDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter.string(from: date)
    }

    private func regionFromManifest(id: String) -> MapRegion? {
        mapManager.manifest?.regions.first { $0.id == id }
    }

    // __MAP_CONTINENT_NORMALISE_2026_10_14__ This switched on the literal string
    // the server sent, and its default arm is .asiaOceania. The manifest carried
    // two hyphenated one-offs — "south-america" (Uruguay) and "central-america"
    // (Nicaragua) — so both were listed under "Asia / Oceania". Android never had
    // the bug because MapRegionDownloadScreen.kt normalises with `continentKey`
    // before it looks the name up; `continentGroupKey` is the same normalisation.
    //
    // The manifest itself was cleaned up the same day, so this fixes nothing on
    // today's data — it keeps the next spelling variant from silently filing a
    // country on the wrong continent instead of being noticed.
    private func continentFor(_ region: MapRegion) -> Continent {
        // The decision itself lives on MapRegion (`continentSectionKey`) so it
        // can be tested without a view; this is only key -> section.
        switch region.continentSectionKey {
        case "europe": return .europe
        case "americas": return .americas
        case "africa": return .africa
        case "middle_east": return .middleEast
        default: return .asiaOceania
        }
    }

    private func iconForContinent(_ continent: Continent) -> String {
        switch continent {
        case .europe: return "globe.europe.africa"
        case .americas: return "globe.americas"
        case .africa: return "globe.europe.africa"
        case .asiaOceania: return "globe.asia.australia"
        case .middleEast: return "globe.asia.australia"
        }
    }
}

// MARK: - Preview

#if DEBUG
struct MapRegionDownloadView_Previews: PreviewProvider {
    static var previews: some View {
        MapRegionDownloadView()
            .preferredColorScheme(.dark)
    }
}
#endif
