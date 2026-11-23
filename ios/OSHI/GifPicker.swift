//
//  GifPicker.swift
//  OSHI — GIF picker sheet (Tenor)
//
//  Entry point for ChatView:
//
//      .sheet(isPresented: $showGifPicker) {
//          GifPickerSheet(onGifSelected: sendMediaMessage)
//      }
//
//  The sheet hands back a ready-to-send `MediaManager.MediaAttachment` and
//  dismisses itself, exactly like `MediaPickerView(onMediaSelected:)` and
//  `DocumentPicker` do — so the caller's send path is unchanged.
//

import SwiftUI

// MARK: - Public entry point

struct GifPickerSheet: View {

    /// Called once, on the main actor, with a fully-formed attachment. The sheet
    /// has already downloaded the bytes, chosen a rendition that fits the send
    /// budget and validated the GIF header by the time this fires — the caller
    /// just sends it.
    let onGifSelected: (MediaManager.MediaAttachment) -> Void

    init(onGifSelected: @escaping (MediaManager.MediaAttachment) -> Void) {
        self.onGifSelected = onGifSelected
    }

    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = GifPickerModel()
    @FocusState private var searchFocused: Bool

    private let columns = [
        GridItem(.adaptive(minimum: 104), spacing: 6)
    ]

    var body: some View {
        NavigationStack {
            // __GIF_2026_09_12__ `SwiftUI.Group` qualifié: l'app définit son PROPRE
            // `struct Group` (GroupManager.swift:97, les conversations de groupe),
            // qui masque celui de SwiftUI dans tout le module. Sans le préfixe, le
            // compilateur résout le modèle de groupe et se plaint d'un « trailing
            // closure sur un paramètre de type any Decoder ».
            SwiftUI.Group {
                if !TenorEndpoint.isConfigured {
                    unavailableState
                } else {
                    content
                }
            }
            .navigationTitle("gif.picker.title".tenorLocalized("GIFs"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".tenorLocalized("Cancel")) { dismiss() }
                }
            }
            .overlay {
                if model.isPreparingSend { sendingOverlay }
            }
            .alert(
                "gif.error.title".tenorLocalized("GIFs"),
                isPresented: Binding(get: { model.errorText != nil },
                                     set: { if !$0 { model.errorText = nil } })
            ) {
                Button("common.ok".tenorLocalized("OK"), role: .cancel) { model.errorText = nil }
            } message: {
                Text(model.errorText ?? "")
            }
        }
        .task { await model.loadInitialContent() }
    }

    // MARK: Content

    private var content: some View {
        VStack(spacing: 0) {
            searchField

            if !model.categories.isEmpty && model.query.isEmpty {
                categoryRow
            }

            resultsArea
        }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)

            TextField("gif.picker.search".tenorLocalized("Search GIFs"), text: $model.query)
                .textFieldStyle(.plain)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .submitLabel(.search)
                .focused($searchFocused)
                // Every keystroke goes through the model, which debounces and
                // cancels — see `GifPickerModel.query`'s didSet. The view never
                // fires a request itself.
                .onSubmit { model.searchNow() }

            if !model.query.isEmpty {
                Button {
                    model.query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .accessibilityLabel(Text("gif.picker.clear".tenorLocalized("Clear search")))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(Color(.secondarySystemBackground), in: Capsule())
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }

    private var categoryRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(model.categories) { category in
                    Button {
                        model.query = category.searchterm
                        searchFocused = false
                    } label: {
                        Text(category.displayName)
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 7)
                            .background(Theme.brand.opacity(0.12), in: Capsule())
                            .foregroundStyle(Theme.brand)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 12)
        }
        .padding(.bottom, 8)
    }

    @ViewBuilder
    private var resultsArea: some View {
        if model.isLoadingFirstPage {
            Spacer()
            ProgressView()
            Spacer()
        } else if model.gifs.isEmpty {
            Spacer()
            ContentUnavailableView(
                model.query.isEmpty
                    ? "gif.picker.empty".tenorLocalized("No GIFs right now")
                    : "gif.picker.no_results".tenorLocalized("No GIFs found"),
                systemImage: "photo.on.rectangle.angled"
            )
            Spacer()
        } else {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 6) {
                    ForEach(model.gifs) { gif in
                        GifGridCell(gif: gif) {
                            Task { await send(gif) }
                        }
                        .onAppear {
                            // Prefetch trigger. Anchored to the last item rather
                            // than a scroll offset so it works identically at
                            // every column count and Dynamic Type size.
                            if gif.id == model.gifs.last?.id {
                                Task { await model.loadNextPage() }
                            }
                        }
                    }
                }
                .padding(.horizontal, 8)

                if model.isLoadingMore {
                    ProgressView().padding(.vertical, 16)
                }
            }
            .scrollDismissesKeyboard(.immediately)
        }
    }

    private var unavailableState: some View {
        ContentUnavailableView(
            "gif.picker.unavailable.title".tenorLocalized("GIFs are unavailable"),
            systemImage: "wifi.slash",
            description: Text("gif.picker.unavailable.body".tenorLocalized(
                "GIF search isn’t configured in this build."))
        )
    }

    private var sendingOverlay: some View {
        ZStack {
            Color.black.opacity(0.25).ignoresSafeArea()
            ProgressView()
                .controlSize(.large)
                .padding(24)
                .oledAwareBackground(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .transition(.opacity)
    }

    // MARK: Send

    private func send(_ gif: TenorGif) async {
        guard let attachment = await model.prepareAttachment(for: gif) else { return }
        onGifSelected(attachment)
        dismiss()
    }
}

// MARK: - One cell

private struct GifGridCell: View {

    let gif: TenorGif
    let onTap: () -> Void

    @State private var previewData: Data?
    @State private var failed = false

    /// Fixed cell height. A masonry layout would look better, but it needs every
    /// cell's height before the grid lays out, and the heights come from the
    /// network — the result is a grid that reflows as pages arrive and moves the
    /// GIF out from under the user's thumb mid-tap. A uniform tile that fills is
    /// the honest trade.
    private let cellHeight: CGFloat = 104

    var body: some View {
        Button(action: onTap) {
            ZStack {
                Color(.tertiarySystemFill)

                if let previewData {
                    AnimatedImageView(data: previewData, contentMode: .scaleAspectFill)
                } else if failed {
                    Image(systemName: "exclamationmark.triangle")
                        .foregroundStyle(.secondary)
                } else {
                    ProgressView()
                }
            }
            .frame(height: cellHeight)
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(gif.accessibilityText))
        .accessibilityAddTraits(.isButton)
        // `.task` is cancelled automatically when the cell scrolls out of the
        // lazy grid, so an off-screen cell stops downloading as well as stops
        // animating.
        .task(id: gif.id) {
            previewData = nil
            failed = false
            do {
                previewData = try await GifPreviewLoader.shared.preview(for: gif)
            } catch is CancellationError {
                // Scrolled away. Not a failure, and showing the warning glyph
                // for it would make fast scrolling look broken.
            } catch {
                failed = true
            }
        }
    }
}

// MARK: - Preview byte cache

/// Small in-memory cache of grid-preview bytes.
///
/// An actor, not a `@MainActor` cache: the grid asks for twenty previews at once
/// and the dedup bookkeeping should not be main-thread work competing with the
/// frame callbacks the GIFs themselves post there.
///
/// Deliberately memory-only. Caching GIF previews to disk would leave a trail of
/// what the user browsed in a directory nothing else in OSHI cleans up — the
/// same reasoning that makes `TenorClient`'s URLSession ephemeral.
actor GifPreviewLoader {

    static let shared = GifPreviewLoader()

    private var cache: [String: Data] = [:]
    /// Insertion order, for eviction. `nanogif` previews are ~30–80 KB, so 120
    /// of them is roughly 6 MB — enough for a long scroll, small enough that the
    /// picker never becomes the reason the app is jetsammed.
    private var order: [String] = []
    private let capacity = 120

    /// In-flight requests, so twenty cells asking for the same trending GIF
    /// (which happens on every re-render) issue one download.
    private var inFlight: [String: Task<Data, Error>] = [:]

    func preview(for gif: TenorGif) async throws -> Data {
        if let cached = cache[gif.id] { return cached }

        if let existing = inFlight[gif.id] {
            return try await existing.value
        }

        let task = Task<Data, Error> {
            try await TenorClient.shared.downloadPreview(gif)
        }
        inFlight[gif.id] = task

        defer { inFlight[gif.id] = nil }
        let data = try await task.value
        store(data, for: gif.id)
        return data
    }

    private func store(_ data: Data, for id: String) {
        if cache[id] == nil {
            order.append(id)
        }
        cache[id] = data
        while order.count > capacity, let oldest = order.first {
            order.removeFirst()
            cache[oldest] = nil
        }
    }
}

// MARK: - Model

@MainActor
final class GifPickerModel: ObservableObject {

    enum Mode: Equatable {
        case featured
        case search(String)
    }

    @Published var query: String = "" {
        didSet {
            guard query != oldValue else { return }
            scheduleSearch()
        }
    }

    @Published private(set) var gifs: [TenorGif] = []
    @Published private(set) var categories: [TenorCategory] = []
    @Published private(set) var isLoadingFirstPage = false
    @Published private(set) var isLoadingMore = false
    @Published private(set) var isPreparingSend = false
    @Published var errorText: String?

    private var mode: Mode = .featured
    private var nextCursor: String?

    /// The one in-flight feed request. Replacing it cancels the previous one —
    /// this single property is both the debounce and the cancellation the picker
    /// needs: a user typing "celebration" must not leave nine requests for nine
    /// prefixes in flight, both because it is slow and because each one is a
    /// separate search term leaving the device (see `__TENOR_RELAY_2026_09_12__`).
    private var feedTask: Task<Void, Never>?

    /// Bumped on every new query. A page that comes back after the query changed
    /// is dropped instead of appended, which is the difference between "the grid
    /// updates" and "the grid mixes results for two different searches".
    private var generation: UInt64 = 0

    // MARK: Loading

    func loadInitialContent() async {
        guard TenorEndpoint.isConfigured else { return }
        // Categories are independent of the feed and must not gate it: if the
        // categories call fails the chips are simply absent, and trending still
        // fills the grid.
        async let categoriesResult: Void = loadCategories()
        await runFeed(reset: true)
        _ = await categoriesResult
    }

    private func loadCategories() async {
        do {
            categories = try await TenorClient.shared.categories()
        } catch {
            OshiLog.web.info("ℹ️ Tenor categories unavailable: \(error.localizedDescription)")
        }
    }

    private func scheduleSearch() {
        feedTask?.cancel()
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let newMode: Mode = trimmed.isEmpty ? .featured : .search(trimmed)

        feedTask = Task { [weak self] in
            // The debounce itself. `Task.sleep` throws on cancellation, so a
            // keystroke inside the window simply ends this task before it ever
            // touches the network.
            do { try await Task.sleep(for: TenorClient.searchDebounce) } catch { return }
            guard let self, !Task.isCancelled else { return }
            self.mode = newMode
            await self.runFeed(reset: true)
        }
    }

    /// Bypass the debounce (the keyboard's Search key).
    func searchNow() {
        feedTask?.cancel()
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        mode = trimmed.isEmpty ? .featured : .search(trimmed)
        feedTask = Task { [weak self] in
            await self?.runFeed(reset: true)
        }
    }

    func loadNextPage() async {
        guard !isLoadingMore, !isLoadingFirstPage,
              let cursor = nextCursor, !cursor.isEmpty else { return }
        await runFeed(reset: false)
    }

    private func runFeed(reset: Bool) async {
        guard TenorEndpoint.isConfigured else {
            errorText = TenorError.notConfigured.errorDescription
            return
        }

        generation &+= 1
        let myGeneration = generation

        if reset {
            isLoadingFirstPage = true
            nextCursor = nil
        } else {
            isLoadingMore = true
        }
        defer {
            if reset { isLoadingFirstPage = false } else { isLoadingMore = false }
        }

        let cursor = reset ? nil : nextCursor
        let requestedMode = mode

        do {
            let feed: TenorFeed
            switch requestedMode {
            case .featured:
                feed = try await TenorClient.shared.featured(cursor: cursor)
            case .search(let term):
                feed = try await TenorClient.shared.search(term, cursor: cursor)
            }

            // Late page for a query the user has moved on from.
            guard myGeneration == generation, requestedMode == mode else { return }

            if reset {
                gifs = feed.gifs
            } else {
                // Tenor repeats ids across pages often enough to matter, and a
                // duplicate id in a `ForEach` is a SwiftUI diffing bug that shows
                // up as cells swapping content at random while scrolling.
                let known = Set(gifs.map(\.id))
                gifs.append(contentsOf: feed.gifs.filter { !known.contains($0.id) })
            }
            nextCursor = feed.nextCursor
        } catch is CancellationError {
            // Superseded by a newer query. Nothing to report.
        } catch {
            guard myGeneration == generation else { return }
            // A failed *page* must not wipe results already on screen — only an
            // empty grid gets an error, otherwise the scroll silently stops
            // paginating, which is the lesser evil.
            if gifs.isEmpty {
                errorText = (error as? TenorError)?.errorDescription ?? error.localizedDescription
            }
            OshiLog.web.error("❌ Tenor feed failed: \(error.localizedDescription)")
        }
    }

    // MARK: Send

    /// Download the send-size rendition and wrap it for the message pipeline.
    ///
    /// Returns nil on failure, having already set `errorText` — the caller only
    /// has to decide what to do with a success.
    func prepareAttachment(for gif: TenorGif) async -> MediaManager.MediaAttachment? {
        guard !isPreparingSend else { return nil }
        isPreparingSend = true
        defer { isPreparingSend = false }

        do {
            let data = try await TenorClient.shared.downloadForSending(gif)
            // Wrapping goes through MediaManager, not through a local
            // `MediaAttachment(...)` literal, so the GIF header check and the
            // size budget are enforced in one place for every GIF source —
            // Tenor today, the share sheet or the keyboard tomorrow.
            return try await MediaManager().processGIF(
                data,
                fileName: "tenor_\(gif.id).gif",
                source: "tenor"
            )
        } catch is CancellationError {
            return nil
        } catch {
            errorText = (error as? TenorError)?.errorDescription
                ?? (error as? MediaError)?.errorDescription
                ?? error.localizedDescription
            OshiLog.web.error("❌ GIF send preparation failed for \(gif.id): \(error.localizedDescription)")
            return nil
        }
    }
}
