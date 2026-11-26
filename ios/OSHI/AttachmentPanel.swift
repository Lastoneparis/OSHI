//
//  AttachmentPanel.swift
//  OSHI
//
//  __ATTACH_PANEL_2027_2026_11_17__ What opens on "+" in a 1:1 AND in a group:
//  one component for both, the twin of Android's `AttachmentPanel.kt`.
//
//    ┌ Recent ·························· All photos ┐   header ("N selected")
//    │ [Camera] [photo] [video 0:07]               │   recent grid, PhotoKit,
//    │ [photo ①] [GIF] [photo ②] …                 │   numbered multi-select
//    ├─────────────────────────────────────────────┤
//    │ (Gallery)(Camera)(File)(GIF)(Location)(… →  │   option row — or, once
//    └─────────────────────────────────────────────┘   picked: caption + Send N
//
//  iPhone: a sheet that opens at ~60 % and drags to full height (scrolling the
//  grid up expands it first). iPad / Mac: a popover on the "+" sized to the chat
//  column. The panel never sends by itself: it hands the host the SAME
//  `MediaAttachment`s the system picker produced (same encode, same video
//  transcode) and the host sends them through its existing path, after the
//  panel is gone — so a LoRa confirmation or a markup sheet can present.
//
//  Rules (selection order, badges, cap, captions, which permission card, which
//  options) live in `AttachmentPanelLogic` and are unit-tested.
//

import SwiftUI
import Photos
import AVFoundation
import UIKit

// MARK: - What the panel hands back

/// A send the host performs once the panel has closed.
struct AttachPanelSend {
    /// In selection order (= badge order = send order).
    let attachments: [MediaManager.MediaAttachment]
    /// Typed once; the host puts it on the FIRST item (`AttachmentPanelLogic.captions`).
    let caption: String
    /// 1:1 only: open the markup sheet on the single photo instead of sending.
    let markup: Bool
}

// MARK: - Recent media (PhotoKit)

/// The recent-photos model: access level, a paged window over the library,
/// the ordered selection, and the thumbnail cache. Watches the library so a
/// limited-access edit, a deletion or a new screenshot shows up live.
@MainActor
final class RecentMediaStore: NSObject, ObservableObject {
    @Published private(set) var access: GalleryAccess
    /// The materialised window, newest first.
    @Published private(set) var assets: [PHAsset] = []
    @Published private(set) var total = 0
    @Published private(set) var loaded = false
    /// Ordered `localIdentifier`s.
    @Published private(set) var selection: [String] = []

    let imageManager = PHCachingImageManager()
    private var fetch: PHFetchResult<PHAsset>?
    private var observing = false
    private var lastPreheat = -1000
    /// Pixel side of a thumbnail request (set by the grid from its layout).
    var thumbnailPixels: CGFloat = 300

    override init() {
        access = Self.currentAccess()
        super.init()
        imageManager.allowsCachingHighQualityImages = false
    }

    static func currentAccess() -> GalleryAccess {
        GalleryAccess(photoKitRawStatus: PHPhotoLibrary.authorizationStatus(for: .readWrite).rawValue)
    }

    func start() {
        access = Self.currentAccess()
        guard access.canRead else { return }
        observe()
        reload()
    }

    func stop() {
        if observing {
            PHPhotoLibrary.shared().unregisterChangeObserver(self)
            observing = false
        }
        imageManager.stopCachingImagesForAllAssets()
    }

    func requestAccess() async {
        let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        access = GalleryAccess(photoKitRawStatus: status.rawValue)
        if access.canRead {
            observe()
            reload()
        }
    }

    private func observe() {
        guard !observing else { return }
        PHPhotoLibrary.shared().register(self)
        observing = true
    }

    /// __RECENT_PHOTOS_ORDER_2026_11_21__ The recent grid's fetch: photos and
    /// videos, NEWEST capture date first, the whole library (no `fetchLimit` —
    /// the window is paged from the sorted result). Android shipped this grid
    /// oldest-first (a sort direction that only reached the last column, user
    /// report 21/11); this is pinned by `AttachmentPanelLogicTests` so the iOS
    /// twin cannot drift the same way. An asset with no `creationDate` sorts
    /// last (PhotoKit orders nil below every date).
    static func recentFetchOptions() -> PHFetchOptions {
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        options.predicate = NSPredicate(format: "mediaType == %d OR mediaType == %d",
                                        PHAssetMediaType.image.rawValue, PHAssetMediaType.video.rawValue)
        return options
    }

    private func reload() {
        let result = PHAsset.fetchAssets(with: Self.recentFetchOptions())
        fetch = result
        total = result.count
        materialise(min(result.count, max(assets.count, AttachmentPanelLogic.pageSize)))
        loaded = true
    }

    private func materialise(_ count: Int) {
        guard let fetch else { assets = []; return }
        let n = min(count, fetch.count)
        assets = n > 0 ? fetch.objects(at: IndexSet(integersIn: 0..<n)) : []
        // An item deleted (or taken out of a limited selection) while picked.
        let present = Set(assets.map(\.localIdentifier))
        let pruned = AttachmentPanelLogic.prune(selection, present: present)
        if pruned != selection { selection = pruned }
    }

    /// A tile became visible: grow the window near its end, and warm the cache
    /// for the next screenful so fast scrolling never shows grey squares.
    func tileAppeared(_ index: Int) {
        if AttachmentPanelLogic.shouldLoadMore(appearedIndex: index, loaded: assets.count, total: total) {
            materialise(AttachmentPanelLogic.nextWindow(loaded: assets.count, total: total))
        }
        guard abs(index - lastPreheat) >= 12 else { return }
        lastPreheat = index
        let upper = min(assets.count, index + 45)
        guard index < upper else { return }
        let side = thumbnailPixels
        imageManager.startCachingImages(for: Array(assets[index..<upper]),
                                        targetSize: CGSize(width: side, height: side),
                                        contentMode: .aspectFill, options: Self.thumbOptions)
    }

    static let thumbOptions: PHImageRequestOptions = {
        let o = PHImageRequestOptions()
        o.deliveryMode = .opportunistic
        o.resizeMode = .fast
        o.isNetworkAccessAllowed = true
        return o
    }()

    @discardableResult
    func toggle(_ id: String) -> AttachToggleOutcome {
        let r = AttachmentPanelLogic.toggle(selection, id)
        selection = r.selection
        return r.outcome
    }

    func clearSelection() { selection = [] }

    func badge(_ id: String) -> Int? { AttachmentPanelLogic.badgeNumber(selection, id) }

    /// Selected assets, in selection order.
    var selectedAssets: [PHAsset] {
        let byId = Dictionary(assets.map { ($0.localIdentifier, $0) }, uniquingKeysWith: { a, _ in a })
        return selection.compactMap { byId[$0] }
    }

    var firstSelectedIsPhoto: Bool { selectedAssets.first?.mediaType == .image }
}

extension RecentMediaStore: PHPhotoLibraryChangeObserver {
    nonisolated func photoLibraryDidChange(_ changeInstance: PHChange) {
        Task { @MainActor in
            self.access = Self.currentAccess()
            guard self.access.canRead else {
                self.assets = []; self.total = 0; self.selection = []
                return
            }
            if let fetch = self.fetch, let details = changeInstance.changeDetails(for: fetch) {
                self.fetch = details.fetchResultAfterChanges
                self.total = details.fetchResultAfterChanges.count
                self.materialise(min(self.total, max(self.assets.count, AttachmentPanelLogic.pageSize)))
            } else if self.fetch == nil {
                self.reload()
            }
        }
    }
}

// MARK: - Turning picked assets into attachments

/// Reads a picked asset and produces the SAME `MediaAttachment` the system
/// picker path produces: a photo through `MediaPickerView.processImageDataSafely`
/// (1600 px, byte budget), a video through `MediaManager.processVideo` from a
/// protected plaintext temp file that is removed afterwards.
enum AttachAssetExport {
    static func attachment(for asset: PHAsset, index: Int,
                           mediaManager: MediaManager) async throws -> MediaManager.MediaAttachment {
        if asset.mediaType == .video {
            let url = try await writeVideo(asset)
            defer { MediaPlaintextTemp.remove(url) }
            return try await mediaManager.processVideo(at: url, deliveryMethod: .auto)
        }
        let data = try await imageData(asset)
        let name = "photo_\(Int(Date().timeIntervalSince1970))_\(index + 1).jpg"
        return try await MediaPickerView.processImageDataSafely(data, fileName: name)
    }

    private static func imageData(_ asset: PHAsset) async throws -> Data {
        try await withCheckedThrowingContinuation { cont in
            let o = PHImageRequestOptions()
            o.isNetworkAccessAllowed = true
            o.deliveryMode = .highQualityFormat
            o.version = .current
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: o) { data, _, _, info in
                if let data {
                    cont.resume(returning: data)
                } else {
                    cont.resume(throwing: (info?[PHImageErrorKey] as? Error) ?? MediaError.processingFailed)
                }
            }
        }
    }

    private static func writeVideo(_ asset: PHAsset) async throws -> URL {
        let resources = PHAssetResource.assetResources(for: asset)
        guard let resource = resources.first(where: { $0.type == .fullSizeVideo })
                ?? resources.first(where: { $0.type == .video }) else {
            throw MediaError.processingFailed
        }
        let ext = (resource.originalFilename as NSString).pathExtension.lowercased()
        let url = MediaPlaintextTemp.scratchURL(ext: ext.isEmpty ? "mov" : ext)
        try? FileManager.default.removeItem(at: url)
        let o = PHAssetResourceRequestOptions()
        o.isNetworkAccessAllowed = true
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            PHAssetResourceManager.default().writeData(for: resource, toFile: url, options: o) { error in
                if let error { cont.resume(throwing: error) } else { cont.resume() }
            }
        }
        try? FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
        return url
    }
}

// MARK: - Presentation

extension View {
    /// Presents the attachment panel from the composer: a sheet with detents on
    /// iPhone, a popover anchored on the "+" (at `plusAnchor`, in this view's
    /// coordinates) on iPad and Mac. The chosen option or send runs AFTER the
    /// panel has gone, so whatever it presents next can present.
    func attachmentPanel(isPresented: Binding<Bool>,
                         kind: AttachChatKind,
                         options: [AttachOption],
                         tint: Color,
                         plusAnchor: CGRect,
                         onOption: @escaping (AttachOption) -> Void,
                         onSend: @escaping (AttachPanelSend) -> Void) -> some View {
        modifier(AttachmentPanelPresenter(isPresented: isPresented, kind: kind, options: options,
                                          tint: tint, plusAnchor: plusAnchor,
                                          onOption: onOption, onSend: onSend))
    }
}

private enum PendingAttachAction {
    case option(AttachOption)
    case send(AttachPanelSend)
}

private struct AttachmentPanelPresenter: ViewModifier {
    @Binding var isPresented: Bool
    let kind: AttachChatKind
    let options: [AttachOption]
    let tint: Color
    let plusAnchor: CGRect
    let onOption: (AttachOption) -> Void
    let onSend: (AttachPanelSend) -> Void

    @Environment(\.horizontalSizeClass) private var hSize
    @State private var pending: PendingAttachAction?

    private var usesPopover: Bool { hSize == .regular }

    /// __DUO_POLISH_2026_11_18__ In the list | thread split the composer is the
    /// detail pane's width (≈390 pt on an open iPhone Duo). A 420 pt popover
    /// hung on the "+" at its left edge spilled over the conversation list.
    /// There the popover hangs from the middle of the composer and is no wider
    /// than the pane, so it stays over the thread it attaches to.
    @Environment(\.threadSplitActive) private var splitActive
    @State private var composerWidth: CGFloat = 0

    private var paneCentered: Bool { splitActive && composerWidth > 0 }

    private var popoverAnchor: CGRect {
        paneCentered ? CGRect(x: composerWidth / 2 - 20, y: 0, width: 40, height: 4) : plusAnchor
    }

    private var popoverMaxWidth: CGFloat {
        paneCentered ? min(440, max(320, composerWidth - 24)) : 440
    }

    func body(content: Content) -> some View {
        // Both presentations stay attached, each behind its own binding, so a
        // size-class change never swaps the composer's view identity.
        content
            .measureWidth(into: $composerWidth)
            .sheet(isPresented: Binding(get: { isPresented && !usesPopover },
                                        set: { if !$0 { isPresented = false } }),
                   onDismiss: runPending) {
                panel(inPopover: false)
            }
            .popover(isPresented: Binding(get: { isPresented && usesPopover },
                                          set: { if !$0 { isPresented = false } }),
                     attachmentAnchor: .rect(.rect(popoverAnchor)), arrowEdge: .bottom) {
                panel(inPopover: true)
                    .frame(idealWidth: min(420, popoverMaxWidth), maxWidth: popoverMaxWidth,
                           idealHeight: 640, maxHeight: 680)
                    .presentationCompactAdaptation(.sheet)
            }
            .onChange(of: isPresented) { _, shown in
                // A popover has no onDismiss; give its fade time to finish.
                guard !shown, usesPopover, pending != nil else { return }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { runPending() }
            }
    }

    private func panel(inPopover: Bool) -> some View {
        AttachmentPanel(kind: kind, options: options, tint: tint, inPopover: inPopover,
                        onOption: { option in
                            pending = .option(option)
                            isPresented = false
                        },
                        onSend: { send in
                            pending = .send(send)
                            isPresented = false
                        },
                        onClose: { isPresented = false })
    }

    private func runPending() {
        guard let action = pending else { return }
        pending = nil
        switch action {
        case .option(let o): onOption(o)
        case .send(let s): onSend(s)
        }
    }
}

// MARK: - The panel

struct AttachmentPanel: View {
    let kind: AttachChatKind
    let options: [AttachOption]
    let tint: Color
    let inPopover: Bool
    let onOption: (AttachOption) -> Void
    let onSend: (AttachPanelSend) -> Void
    let onClose: () -> Void

    @StateObject private var store = RecentMediaStore()
    @StateObject private var mediaManager = MediaManager()
    @State private var detent: PresentationDetent = AttachmentPanel.collapsedDetent
    @State private var caption = ""
    @State private var sendOptions = MediaSendOptions.none
    @State private var preparing = false
    @State private var prepareError: String?
    @State private var limitToast = false
    @State private var selectTick = 0
    @State private var limitTick = 0
    @FocusState private var captionFocused: Bool

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.oledBlackActive) private var oled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.displayScale) private var displayScale

    static let collapsedDetent = PresentationDetent.fraction(0.6)

    private var isMac: Bool {
        #if targetEnvironment(macCatalyst)
        return true
        #else
        return false
        #endif
    }

    private var hasSelection: Bool { !store.selection.isEmpty }
    private var expanded: Bool { detent == .large }
    private var spring: Animation {
        reduceMotion ? .easeInOut(duration: 0.15) : .spring(response: 0.32, dampingFraction: 0.82)
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            content
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { bottomBar }
        .overlay(alignment: .bottom) { toast }
        .background(panelBackground.ignoresSafeArea())
        .presentationDetents(inPopover ? [.large] : [Self.collapsedDetent, .large], selection: $detent)
        .presentationDragIndicator(inPopover ? .hidden : .visible)
        .presentationContentInteraction(.resizes)
        .presentationCornerRadius(inPopover ? nil : 28)
        .presentationBackground(panelBackground)
        .sensoryFeedback(.selection, trigger: selectTick)
        .sensoryFeedback(.warning, trigger: limitTick)
        .onAppear { if !isMac { store.start() } }
        .onDisappear { store.stop() }
        .animation(spring, value: hasSelection)
        .alert("common.error".localized, isPresented: Binding(get: { prepareError != nil },
                                                               set: { if !$0 { prepareError = nil } })) {
            Button("common.ok".localized) { prepareError = nil }
        } message: {
            Text(prepareError ?? "")
        }
    }

    private var panelBackground: Color {
        oled ? .black : Color(.systemBackground)
    }

    /// The tint for TEXT and icons: brand purple is too dark to read on a dark
    /// or black ground, so it is lifted toward white there (as Android does).
    private var accent: Color { AttachPanelColors.accent(tint, dark: colorScheme == .dark) }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 12) {
            Text(hasSelection
                 ? "attach.selected_count".localized(with: store.selection.count)
                 : "attach.recent".localized)
                .font(.title2.weight(.bold))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .contentTransition(.numericText())
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("attach.title")
            Spacer(minLength: 8)
            if expanded && !inPopover {
                Button {
                    withAnimation(spring) { detent = Self.collapsedDetent }
                } label: {
                    Image(systemName: "chevron.down")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("attach.collapse".localized)
            }
            if !isMac {
                Button { onOption(.gallery) } label: {
                    Label("attach.all_photos".localized, systemImage: "photo.on.rectangle.angled")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(accent)
                        .lineLimit(1)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("attach.allPhotos")
            }
        }
        .padding(.leading, 20)
        .padding(.trailing, 14)
        .padding(.top, inPopover ? 12 : 22)
        .padding(.bottom, 6)
    }

    // MARK: Content

    @ViewBuilder
    private var content: some View {
        if isMac {
            macContent
        } else {
            switch GalleryPermissionCard(store.access) {
            case .none, .limitedBanner:
                grid
            case .askAccess:
                permissionCard(denied: false)
            case .openSettings:
                permissionCard(denied: true)
            }
        }
    }

    private var grid: some View {
        GeometryReader { geo in
            let spacing: CGFloat = 2
            let columns = max(3, Int((geo.size.width + spacing) / 132))
            let side = floor((geo.size.width - spacing * CGFloat(columns - 1)) / CGFloat(columns))
            ScrollView {
                VStack(spacing: 8) {
                    if store.access == .limited { limitedBanner.padding(.horizontal, 12) }
                    LazyVGrid(columns: Array(repeating: GridItem(.fixed(side), spacing: spacing), count: columns),
                              spacing: spacing) {
                        cameraTile(side: side)
                        ForEach(Array(store.assets.enumerated()), id: \.element.localIdentifier) { index, asset in
                            tile(asset, index: index, side: side)
                        }
                    }
                    if store.loaded && store.assets.isEmpty && store.access == .full {
                        Text("attach.empty".localized)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .padding(.top, 12)
                    }
                }
                .padding(.bottom, 12)
            }
            .scrollIndicators(.hidden)
            .onAppear { store.thumbnailPixels = side * displayScale }
        }
    }

    private func cameraTile(side: CGFloat) -> some View {
        Button { onOption(.camera) } label: {
            VStack(spacing: 8) {
                Image(systemName: "camera.fill")
                    .font(.system(size: 26, weight: .semibold))
                Text("camera_permission".localized)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .foregroundStyle(accent)
            .frame(width: side, height: side)
            .background {
                if oled {
                    Rectangle().fill(OLEDGlass.solid)
                        .overlay(Rectangle().fill(OLEDGlass.topHighlight))
                } else {
                    Rectangle().fill(tint.opacity(colorScheme == .dark ? 0.20 : 0.10))
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleStyle())
        .accessibilityLabel("camera_permission".localized)
        .accessibilityIdentifier("attach.cameraTile")
    }

    private func tile(_ asset: PHAsset, index: Int, side: CGFloat) -> some View {
        let id = asset.localIdentifier
        let badge = store.badge(id)
        let atCap = badge == nil && store.selection.count >= AttachmentPanelLogic.maxSelection
        return Button {
            toggle(id)
        } label: {
            RecentAssetTile(asset: asset, manager: store.imageManager, side: side,
                            pixels: store.thumbnailPixels, badge: badge, dimmed: atCap, tint: tint,
                            reduceMotion: reduceMotion)
        }
        .buttonStyle(.plain)
        .onAppear { store.tileAppeared(index) }
        .accessibilityLabel(RecentAssetTile.accessibilityLabel(asset))
        .accessibilityValue(badge.map { "attach.selected_number".localized(with: $0) } ?? "")
        .accessibilityAddTraits(badge != nil ? [.isSelected] : [])
        .accessibilityIdentifier("attach.asset.\(index)")
    }

    private func toggle(_ id: String) {
        let outcome: AttachToggleOutcome
        outcome = withAnimation(spring) { store.toggle(id) }
        switch outcome {
        case .added, .removed:
            selectTick &+= 1
            if store.selection.isEmpty { sendOptions = .none; caption = "" }
            if !AttachmentPanelLogic.revealAvailable(selectedCount: store.selection.count,
                                                     firstIsPhoto: store.firstSelectedIsPhoto),
               sendOptions.revealDuration != nil {
                sendOptions.toggleReveal(sendOptions.revealDuration!)
            }
        case .limit:
            limitTick &+= 1
            withAnimation(spring) { limitToast = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.2) {
                withAnimation(spring) { limitToast = false }
            }
        }
    }

    private var limitedBanner: some View {
        HStack(spacing: 12) {
            Image(systemName: "photo.badge.checkmark")
                .font(.title3)
                .foregroundStyle(accent)
                .accessibilityHidden(true)
            Text("attach.limited.banner".localized)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 4)
            Menu {
                Button {
                    presentLimitedPicker()
                } label: {
                    Label("attach.limited.select_more".localized, systemImage: "plus.circle")
                }
                Button {
                    openSettings()
                } label: {
                    Label("attach.limited.allow_all".localized, systemImage: "gear")
                }
            } label: {
                Text("attach.limited.manage".localized)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(accent)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("attach.limited.manage")
        }
        .padding(.leading, 14)
        .padding(.trailing, 8)
        .padding(.vertical, 4)
        .oledGlassCard(RoundedRectangle(cornerRadius: 16, style: .continuous),
                       fallback: tint.opacity(colorScheme == .dark ? 0.16 : 0.08))
        .accessibilityElement(children: .contain)
    }

    private func permissionCard(denied: Bool) -> some View {
        ScrollView {
            VStack(spacing: 14) {
                ZStack {
                    Circle()
                        .fill(tint.opacity(colorScheme == .dark ? 0.22 : 0.12))
                        .frame(width: 76, height: 76)
                        .oledGlassEdge(Circle())
                    Image(systemName: denied ? "photo.badge.exclamationmark" : "photo.on.rectangle.angled")
                        .font(.system(size: 30, weight: .semibold))
                        .foregroundStyle(accent)
                }
                .accessibilityHidden(true)
                .padding(.top, 8)
                Text(denied ? "attach.access.denied_title".localized : "attach.access.title".localized)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                Text(denied ? "attach.access.denied_body".localized : "attach.access.body".localized)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 12)
                Button {
                    if denied {
                        openSettings()
                    } else {
                        Task { await store.requestAccess() }
                    }
                } label: {
                    Text(denied ? "common.open_settings".localized : "attach.access.allow".localized)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 36)
                        .frame(minHeight: 50)
                        .background(Capsule().fill(tint))
                        .oledFocusGlow(tint)
                }
                .buttonStyle(PressScaleStyle())
                .padding(.top, 6)
                .accessibilityIdentifier(denied ? "attach.openSettings" : "attach.allowAccess")
                Button { onOption(.gallery) } label: {
                    Text("attach.access.choose".localized)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(accent)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("attach.chooseFromLibrary")
            }
            .frame(maxWidth: 420)
            .padding(.horizontal, 24)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
    }

    /// Mac: Photos access would need a new sandbox entitlement, and a Mac user
    /// picks from Photos or Finder anyway — so the recent grid gives way to the
    /// system picker (no permission) and the options, as a grid of tiles.
    private var macContent: some View {
        ScrollView {
            VStack(spacing: 16) {
                Button { onOption(.gallery) } label: {
                    HStack(spacing: 14) {
                        Image(systemName: "photo.on.rectangle.angled")
                            .font(.title2)
                            .foregroundStyle(tint)
                            .frame(width: 48, height: 48)
                            .background(Circle().fill(tint.opacity(0.14)))
                        VStack(alignment: .leading, spacing: 2) {
                            Text("attach.access.choose".localized).font(.headline)
                            Text("attach.mac.photos_hint".localized)
                                .font(.subheadline).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                    }
                    .padding(14)
                    .oledGlassCard(RoundedRectangle(cornerRadius: 18, style: .continuous),
                                   fallback: Color(.secondarySystemBackground))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 84), spacing: 8)], spacing: 14) {
                    ForEach(options.filter { $0 != .gallery }) { option in
                        AttachOptionTile(option: option, oled: oled) { onOption(option) }
                    }
                }
            }
            .padding(16)
        }
    }

    // MARK: Bottom bar

    @ViewBuilder
    private var bottomBar: some View {
        if isMac {
            EmptyView()
        } else {
            VStack(spacing: 0) {
                if hasSelection {
                    captionBar
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                } else {
                    optionRow
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .padding(.top, 10)
            .padding(.bottom, inPopover ? 10 : 4)
            .frame(maxWidth: .infinity)
            .oledGlassBar(fallback: AnyShapeStyle(.bar))
        }
    }

    private var optionRow: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 4) {
                ForEach(options) { option in
                    AttachOptionTile(option: option, oled: oled) { onOption(option) }
                }
            }
            .padding(.horizontal, 10)
        }
        .scrollIndicators(.hidden)
        .accessibilityIdentifier("attach.options")
    }

    private var captionBar: some View {
        HStack(alignment: .bottom, spacing: 10) {
            if AttachmentPanelLogic.supportsSendOptions(kind) {
                sendOptionsMenu
            }
            TextField("attach.caption_placeholder".localized, text: $caption, axis: .vertical)
                .privateTypingComposer()
                .lineLimit(1...4)
                .focused($captionFocused)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(minHeight: 48)
                .background(RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(Color(.secondarySystemFill)))
                .oledGlassEdge(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .accessibilityIdentifier("attach.caption")
            sendButton
        }
        .padding(.horizontal, 14)
        .padding(.bottom, 6)
    }

    private var sendOptionsActive: Bool { !sendOptions.isPlain }

    private var sendOptionsMenu: some View {
        let revealOK = AttachmentPanelLogic.revealAvailable(selectedCount: store.selection.count,
                                                           firstIsPhoto: store.firstSelectedIsPhoto)
        return Menu {
            Toggle(isOn: Binding(get: { sendOptions.isViewOnce },
                                 set: { sendOptions.setViewOnce($0) })) {
                Label("media_picker.view_once".localized, systemImage: "eye.slash")
            }
            if revealOK {
                Menu {
                    ForEach(RevealMode.presets, id: \.self) { preset in
                        Toggle(isOn: Binding(get: { sendOptions.revealDuration == preset },
                                             set: { _ in sendOptions.toggleReveal(preset) })) {
                            Text(RevealDurationFormat.short(preset))
                        }
                    }
                } label: {
                    Label("media_picker.reveal".localized, systemImage: "hourglass")
                }
                Divider()
                Button {
                    prepareAndSend(markup: true)
                } label: {
                    Label("attach.draw".localized, systemImage: "pencil.tip.crop.circle")
                }
            }
        } label: {
            ZStack {
                Circle()
                    .fill(sendOptions.isViewOnce ? Color.orange
                          : sendOptions.revealDuration != nil ? Color.purple
                          : Color(.secondarySystemFill))
                    .frame(width: 48, height: 48)
                Image(systemName: sendOptions.isViewOnce ? "1.circle"
                      : sendOptions.revealDuration != nil ? "hourglass" : "timer")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(sendOptionsActive ? .white : accent)
            }
            .oledGlassEdge(Circle())
            .frame(width: 48, height: 48)
            .contentShape(Circle())
        }
        .accessibilityLabel("attach.send_options".localized)
        .accessibilityValue(sendOptions.isViewOnce ? "media_picker.view_once".localized
                            : sendOptions.revealDuration.map { RevealDurationFormat.short($0) } ?? "")
        .accessibilityIdentifier("attach.sendOptions")
        .disabled(preparing)
    }

    private var sendButton: some View {
        Button {
            prepareAndSend(markup: false)
        } label: {
            ZStack(alignment: .topTrailing) {
                ZStack {
                    Circle().fill(sendOptions.isViewOnce ? Color.orange : tint)
                        .frame(width: 52, height: 52)
                        .oledFocusGlow(tint)
                    if preparing {
                        ProgressView().tint(.white)
                    } else {
                        Image(systemName: "arrow.up")
                            .font(.system(size: 21, weight: .bold))
                            .foregroundStyle(.white)
                    }
                }
                Text("\(store.selection.count)")
                    .font(.caption2.weight(.bold).monospacedDigit())
                    .foregroundStyle(accent)
                    .contentTransition(.numericText())
                    .frame(minWidth: 20, minHeight: 20)
                    .padding(.horizontal, 2)
                    .background(Circle().fill(Color(.systemBackground)))
                    .overlay(Circle().stroke(tint, lineWidth: 1.5))
                    .offset(x: 5, y: -5)
                    .accessibilityHidden(true)
            }
            .frame(width: 56, height: 56)
            .contentShape(Circle())
        }
        .buttonStyle(PressScaleStyle())
        .disabled(preparing)
        .accessibilityLabel("attach.send_count".localized(with: store.selection.count))
        .accessibilityIdentifier("attach.send")
        .sensoryFeedback(.impact(weight: .medium), trigger: preparing) { _, new in new }
    }

    @ViewBuilder
    private var toast: some View {
        if limitToast {
            Text("attach.limit_reached".localized(with: AttachmentPanelLogic.maxSelection))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Capsule().fill(Color.black.opacity(0.82)))
                .padding(.bottom, 110)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .accessibilityAddTraits(.isStaticText)
                .onAppear {
                    UIAccessibility.post(notification: .announcement,
                                         argument: "attach.limit_reached".localized(with: AttachmentPanelLogic.maxSelection))
                }
        }
    }

    // MARK: Actions

    private func prepareAndSend(markup: Bool) {
        let assets = store.selectedAssets
        guard !assets.isEmpty, !preparing else { return }
        captionFocused = false
        preparing = true
        let typed = caption
        let opts = sendOptions
        Task {
            var out: [MediaManager.MediaAttachment] = []
            for (i, asset) in assets.enumerated() {
                do {
                    var a = try await AttachAssetExport.attachment(for: asset, index: i, mediaManager: mediaManager)
                    a.isViewOnce = opts.isViewOnce
                    if i == 0, assets.count == 1, a.type == .image { a.revealDuration = opts.revealDuration }
                    out.append(a)
                } catch {
                    OshiLog.storage.info("⚠️ Attach panel: could not prepare item \(i + 1): \(error)")
                }
            }
            await MainActor.run {
                preparing = false
                guard !out.isEmpty else {
                    prepareError = (assets.first?.mediaType == .video
                                    ? "media_picker.error_process_video" : "media_picker.error_process_image").localized
                    return
                }
                onSend(AttachPanelSend(attachments: out, caption: typed, markup: markup && out.count == 1))
            }
        }
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    private func presentLimitedPicker() {
        guard let top = Self.topViewController() else { return }
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: top) { _ in
            // The change observer refreshes the grid; nothing else to do.
        }
    }

    static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? scenes.first?.windows.first
        var vc = window?.rootViewController
        while let presented = vc?.presentedViewController { vc = presented }
        return vc
    }
}

// MARK: - Tiles

/// One recent photo/video: thumbnail from the shared caching manager, the
/// selection ring or numbered badge, a duration chip on videos, a GIF tag on
/// animated images. Selected tiles inset with rounded corners, like Android.
private struct RecentAssetTile: View {
    let asset: PHAsset
    let manager: PHCachingImageManager
    let side: CGFloat
    let pixels: CGFloat
    let badge: Int?
    let dimmed: Bool
    let tint: Color
    let reduceMotion: Bool

    @State private var image: UIImage?
    @State private var requestID: PHImageRequestID = PHInvalidImageRequestID

    private var selected: Bool { badge != nil }
    private var isGif: Bool { asset.playbackStyle == .imageAnimated }

    var body: some View {
        ZStack {
            Rectangle().fill(selected ? tint.opacity(0.14) : Color(.tertiarySystemFill))
            thumb
                .frame(width: side, height: side)
                .clipShape(RoundedRectangle(cornerRadius: selected ? 12 : 0, style: .continuous))
                .scaleEffect(selected ? 0.86 : 1)
            if dimmed {
                Color.black.opacity(0.35).allowsHitTesting(false)
            }
        }
        .frame(width: side, height: side)
        .clipped()
        .overlay(alignment: .topTrailing) { selector.padding(selected ? 4 : 6) }
        .overlay(alignment: .bottomLeading) { chip.padding(selected ? 12 : 6) }
        .animation(reduceMotion ? .easeInOut(duration: 0.12) : .spring(response: 0.28, dampingFraction: 0.75),
                   value: selected)
        .contentShape(Rectangle())
        .onAppear(perform: load)
        .onDisappear(perform: cancel)
    }

    @ViewBuilder
    private var thumb: some View {
        if let image {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
        } else {
            Color(.tertiarySystemFill)
        }
    }

    @ViewBuilder
    private var selector: some View {
        if let badge {
            Text("\(badge)")
                .font(.system(size: 14, weight: .bold).monospacedDigit())
                .foregroundStyle(.white)
                .frame(width: 26, height: 26)
                .background(Circle().fill(tint))
                .overlay(Circle().stroke(.white, lineWidth: 2))
                .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
                .transition(.scale.combined(with: .opacity))
        } else {
            Circle()
                .fill(Color.black.opacity(0.18))
                .overlay(Circle().stroke(.white, lineWidth: 1.6))
                .frame(width: 24, height: 24)
                .shadow(color: .black.opacity(0.3), radius: 1.5)
        }
    }

    @ViewBuilder
    private var chip: some View {
        if asset.mediaType == .video {
            HStack(spacing: 3) {
                Image(systemName: "play.fill").font(.system(size: 9, weight: .bold))
                Text(AttachmentPanelLogic.formatDuration(asset.duration))
                    .font(.caption2.weight(.semibold).monospacedDigit())
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(Capsule().fill(Color.black.opacity(0.55)))
        } else if isGif {
            Text("GIF")
                .font(.caption2.weight(.heavy))
                .foregroundStyle(.white)
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(Capsule().fill(Color.black.opacity(0.55)))
        }
    }

    private func load() {
        guard image == nil, requestID == PHInvalidImageRequestID else { return }
        let id = asset.localIdentifier
        requestID = manager.requestImage(for: asset, targetSize: CGSize(width: pixels, height: pixels),
                                         contentMode: .aspectFill,
                                         options: RecentMediaStore.thumbOptions) { img, info in
            guard let img, id == asset.localIdentifier else { return }
            if (info?[PHImageCancelledKey] as? Bool) == true { return }
            image = img
            if (info?[PHImageResultIsDegradedKey] as? Bool) != true { requestID = PHInvalidImageRequestID }
        }
    }

    private func cancel() {
        if requestID != PHInvalidImageRequestID {
            manager.cancelImageRequest(requestID)
            requestID = PHInvalidImageRequestID
        }
    }

    static func accessibilityLabel(_ asset: PHAsset) -> String {
        let date = asset.creationDate.map {
            DateFormatter.localizedString(from: $0, dateStyle: .medium, timeStyle: .short)
        }
        let kind: String
        if asset.mediaType == .video {
            let d = DateComponentsFormatter()
            d.unitsStyle = .full
            d.allowedUnits = asset.duration >= 3600 ? [.hour, .minute, .second] : [.minute, .second]
            kind = "attach.a11y.video".localized + ", " + (d.string(from: asset.duration.rounded()) ?? "")
        } else if asset.playbackStyle == .imageAnimated {
            kind = "GIF"
        } else {
            kind = "attach.a11y.photo".localized
        }
        return [kind, date].compactMap { $0 }.joined(separator: ", ")
    }
}

/// An option of the bottom row: a round coloured icon and a short label.
/// The accessibility label is the full action name the old "+" menu used.
struct AttachOptionTile: View {
    let option: AttachOption
    let oled: Bool
    let action: () -> Void

    @ScaledMetric(relativeTo: .caption) private var circle: CGFloat = 54
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                ZStack {
                    if oled {
                        Circle().fill(OLEDGlass.solid)
                            .overlay(Circle().fill(OLEDGlass.topHighlight))
                            .overlay(Circle().stroke(OLEDGlass.hairline, lineWidth: OLEDGlass.hairlineWidth))
                    } else {
                        Circle().fill(LinearGradient(colors: [style.color.opacity(0.88), style.color],
                                                     startPoint: .top, endPoint: .bottom))
                            .shadow(color: style.color.opacity(colorScheme == .dark ? 0 : 0.28), radius: 6, y: 3)
                    }
                    Image(systemName: style.icon)
                        .font(.system(size: circle * 0.4, weight: .semibold))
                        .foregroundStyle(oled ? style.color : .white)
                }
                .frame(width: circle, height: circle)
                Text(style.short)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
            .frame(width: max(72, circle + 18))
            .padding(.vertical, 2)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleStyle())
        .accessibilityLabel(style.full)
        .accessibilityIdentifier("attach.option.\(option.rawValue)")
    }

    private struct Style { let icon: String; let color: Color; let short: String; let full: String }

    private var style: Style {
        switch option {
        case .gallery:
            return Style(icon: "photo.on.rectangle", color: Color(red: 0.16, green: 0.50, blue: 0.98),
                         short: "chat.gallery".localized, full: "chat.gallery".localized)
        case .camera:
            return Style(icon: "camera.fill", color: Color(red: 0.42, green: 0.36, blue: 0.95),
                         short: "camera_permission".localized, full: "camera_permission".localized)
        case .file:
            return Style(icon: "doc.fill", color: Color(red: 1.0, green: 0.62, blue: 0.10),
                         short: "attach.option.file".localized, full: "chat.document".localized)
        case .gif:
            return Style(icon: "face.smiling.inverse", color: Color(red: 0.72, green: 0.33, blue: 0.93),
                         short: "GIF", full: "gif.picker.title".localizedOrFallback("GIFs & Stickers"))
        case .location:
            return Style(icon: "location.fill", color: Color(red: 0.20, green: 0.75, blue: 0.40),
                         short: "chat.location".localized, full: "location.share".localized)
        case .contact:
            return Style(icon: "person.crop.circle.fill", color: Color(red: 0.55, green: 0.45, blue: 0.36),
                         short: "attach.option.contact".localized, full: "media_picker.share_contact".localized)
        case .poll:
            return Style(icon: "chart.bar.fill", color: Color(red: 0.10, green: 0.66, blue: 0.85),
                         short: "attach.option.poll".localized, full: "poll.create".localized)
        case .checkIn:
            return Style(icon: "shield.checkered", color: Color(red: 0.93, green: 0.30, blue: 0.30),
                         short: "checkin.title".localized, full: "checkin.title".localized)
        case .videoNote:
            return Style(icon: "video.circle.fill", color: Color(red: 0.10, green: 0.70, blue: 0.68),
                         short: "attach.option.video_note".localized, full: "videonote.button".localized)
        case .cashu:
            return Style(icon: "bitcoinsign", color: Color(red: 0.97, green: 0.58, blue: 0.10),
                         short: "attach.option.cashu".localized,
                         full: NSLocalizedString("cashu_send_title", comment: "Send bitcoin (Cashu)"))
        case .thinkingOfYou:
            return Style(icon: "heart.fill", color: Color(red: 0.96, green: 0.32, blue: 0.56),
                         short: "scheduled_msg.menu".localized, full: "scheduled_msg.menu".localized)
        case .disguisePhoto:
            return Style(icon: "eye.slash.fill", color: Color(red: 0.45, green: 0.45, blue: 0.50),
                         short: "disguise.photo.short".localized, full: "disguise.photo.title".localized)
        case .scheduleCall:
            return Style(icon: "calendar.badge.clock", color: Color(red: 0.36, green: 0.40, blue: 0.95),
                         short: "attach.option.schedule_call".localized, full: "event.create.title".localized)
        }
    }
}

enum AttachPanelColors {
    /// `tint` lifted 45 % toward white on dark grounds; unchanged on light.
    static func accent(_ tint: Color, dark: Bool) -> Color {
        guard dark else { return tint }
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(tint).getRed(&r, green: &g, blue: &b, alpha: &a)
        let k: CGFloat = 0.45
        return Color(red: r + (1 - r) * k, green: g + (1 - g) * k, blue: b + (1 - b) * k, opacity: a)
    }
}

/// A light press: scales a touch down, never under Reduce Motion.
private struct PressScaleStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.94 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}
