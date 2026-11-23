//
//  MediaGalleryView.swift
//  OSHI
//
//  Per-chat media gallery (P2 #20). Shows a 3-column grid of all photo/video
//  attachments in a 1-on-1 conversation (peerPublicKey) or group (groupId).
//
//  - Thumbnails use the same lazy/downsampled loading as LazyGroupPhotoView
//    (MediaManager.safeUIImage) so we never decode full-res blobs for grid cells.
//  - Tapping a cell opens the existing MediaFullScreenView, matching the rest
//    of the app's viewer UX.
//

import SwiftUI
import AVFoundation

private let galleryBrandColor = Theme.brand

// MARK: - Categories

/// The buckets the profile and the gallery agree on. `links` is derived from
/// message text rather than from an attachment, so it has no MediaType case.
enum MediaGalleryCategory: String, CaseIterable, Identifiable {
    case photos, videos, documents, audio, links

    var id: String { rawValue }

    var label: String {
        switch self {
        case .photos:    return "media_gallery.category.photos".localized
        case .videos:    return "media_gallery.category.videos".localized
        case .documents: return "media_gallery.category.documents".localized
        case .audio:     return "media_gallery.category.audio".localized
        case .links:     return "media_gallery.category.links".localized
        }
    }

    var systemImage: String {
        switch self {
        case .photos:    return "photo"
        case .videos:    return "play.rectangle"
        case .documents: return "doc"
        case .audio:     return "waveform"
        case .links:     return "link"
        }
    }
}

// MARK: - Shared index

/// One scan of a conversation, bucketed by category. The contact profile reads
/// the counts and the gallery reads the contents from THIS type, so a category
/// can never advertise "3" on the profile and then open empty.
struct ContactMediaIndex {
    var photos: [MediaGalleryItem] = []
    var videos: [MediaGalleryItem] = []
    var documents: [MediaFileItem] = []
    var audio: [MediaFileItem] = []
    var links: [MediaLinkItem] = []

    func count(_ category: MediaGalleryCategory) -> Int {
        switch category {
        case .photos:    return photos.count
        case .videos:    return videos.count
        case .documents: return documents.count
        case .audio:     return audio.count
        case .links:     return links.count
        }
    }

    var isEmpty: Bool { MediaGalleryCategory.allCases.allSatisfy { count($0) == 0 } }

    /// Newest first, both directions of the chat. Peer matching folds base64url
    /// and `=` padding — the same skew that made saved aliases miss would
    /// otherwise hide a whole conversation's media here too.
    /// @MainActor: reads MessageGroupManager.groupMessages and MessageManager.messages,
    /// both main-actor isolated. Every caller is a SwiftUI view body, so this costs nothing.
    @MainActor
    static func build(peerPublicKey: String?, groupId: UUID?) -> ContactMediaIndex {
        var index = ContactMediaIndex()

        if let groupId {
            let msgs = (MessageGroupManager.shared.groupMessages[groupId] ?? [])
                .sorted { $0.timestamp > $1.timestamp }
            for msg in msgs {
                if let data = msg.mediaData, let type = msg.mediaType {
                    let item = MediaGalleryItem(id: msg.id.uuidString, data: data,
                                                isVideo: type == .video, fileName: msg.mediaFileName)
                    if type == .photo { index.photos.append(item) }
                    else if type == .video { index.videos.append(item) }
                }
            }
            return index
        }

        guard let peerKey = peerPublicKey, let manager = MessageManager.shared else { return index }
        let wanted = MessageManager.normalizeAliasKey(peerKey)
        let msgs = manager.messages
            .filter { msg in
                MessageManager.normalizeAliasKey(msg.senderPublicKey) == wanted ||
                MessageManager.normalizeAliasKey(msg.recipientPublicKey) == wanted
            }
            .sorted { $0.timestamp > $1.timestamp }

        for msg in msgs {
            if let data = msg.mediaAttachment, let type = msg.mediaType {
                switch type {
                case .image:
                    index.photos.append(MediaGalleryItem(id: msg.id, data: data, isVideo: false, fileName: msg.mediaFileName))
                // __GIF_2026_09_12__ Un GIF va dans la galerie PHOTOS, pas dans
                // les fichiers: c'est là que l'utilisateur ira le rechercher.
                case .gif:
                    index.photos.append(MediaGalleryItem(id: msg.id, data: data, isVideo: false, fileName: msg.mediaFileName))
                case .video:
                    index.videos.append(MediaGalleryItem(id: msg.id, data: data, isVideo: true, fileName: msg.mediaFileName))
                case .document:
                    index.documents.append(MediaFileItem(id: msg.id, data: data,
                                                         fileName: msg.mediaFileName ?? "media.attachment".localized,
                                                         timestamp: msg.timestamp, isAudio: false))
                case .audio:
                    index.audio.append(MediaFileItem(id: msg.id, data: data,
                                                     fileName: msg.mediaFileName ?? "media.attachment".localized,
                                                     timestamp: msg.timestamp, isAudio: true))
                case .contact:
                    break   // a contact card is not a media the user browses for
                }
            }
            // Links live in the message text, not in an attachment.
            let raw = msg.editedContent ?? msg.plaintextContent ?? ""
            guard !raw.isEmpty else { continue }
            let text = OSHIControlPayload.previewText(for: raw)
            for url in Self.detectLinks(in: text) {
                index.links.append(MediaLinkItem(id: msg.id + "|" + url.absoluteString,
                                                 url: url, timestamp: msg.timestamp))
            }
        }
        return index
    }

    /// NSDataDetector rather than a hand-rolled regex: it is what the message
    /// bubbles already make tappable, so the profile lists exactly the links the
    /// user can see in the thread — no more, no less.
    private static func detectLinks(in text: String) -> [URL] {
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else { return [] }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return detector.matches(in: text, options: [], range: range).compactMap { $0.url }
    }
}

struct MediaGalleryView: View {
    /// Provide exactly one of these — the view picks the right data source.
    let peerPublicKey: String?
    let groupId: UUID?
    /// Which tab to land on. The contact profile deep-links straight to the
    /// category the user tapped; the chat's own button keeps the old behaviour.
    var initialCategory: MediaGalleryCategory = .photos

    @Environment(\.dismiss) private var dismiss
    @State private var selectedCategory: MediaGalleryCategory?

    /// Seeded from `initialCategory` rather than a stored default, so the view
    /// has one source of truth and no init boilerplate.
    private var category: MediaGalleryCategory { selectedCategory ?? initialCategory }

    // Grid spec — matches WhatsApp/iMessage style media tabs.
    private let columns: [GridItem] = Array(
        repeating: GridItem(.flexible(), spacing: 2),
        count: 3
    )

    var body: some View {
        NavigationStack {
            // SwiftUI.Group (fully qualified to avoid collision with the
            // messenger-domain `Group` model in GroupManager.swift).
            SwiftUI.Group {
                if index.isEmpty {
                    emptyState
                } else {
                    VStack(spacing: 0) {
                        categoryPicker
                        categoryContent
                    }
                }
            }
            .navigationTitle("media_gallery.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("common.done".localized) { dismiss() }
                }
            }
        }
        // Closing the gallery must silence a voice message that is still playing —
        // otherwise it keeps going with no visible control to stop it.
        .onDisappear { MediaAudioPlayback.shared.stop() }
    }

    // MARK: - Empty state

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "photo.on.rectangle.angled")
                .font(.scaled(48))
                .foregroundColor(.secondary)
                .accessibilityHidden(true)
            Text("media_gallery.empty".localized)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }

    // MARK: - Category picker + per-category content

    private var categoryPicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                // Only offer a category that has something in it — an empty tab
                // is a dead end the user has to back out of.
                ForEach(MediaGalleryCategory.allCases.filter { index.count($0) > 0 }) { cat in
                    Button {
                        selectedCategory = cat
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: cat.systemImage).font(.caption)
                            Text("\(cat.label) (\(index.count(cat)))").font(.subheadline)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(
                            Capsule().fill(category == cat ? galleryBrandColor.opacity(0.18) : Color(.systemGray6))
                        )
                        .overlay(
                            Capsule().strokeBorder(category == cat ? galleryBrandColor : .clear, lineWidth: 1)
                        )
                        .foregroundColor(category == cat ? galleryBrandColor : .primary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(category == cat ? [.isSelected, .isButton] : .isButton)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
    }

    @ViewBuilder
    private var categoryContent: some View {
        switch category {
        case .photos, .videos:
            ScrollView {
                LazyVGrid(columns: columns, spacing: 2) {
                    ForEach(category == .photos ? index.photos : index.videos) { item in
                        MediaGalleryCell(item: item)
                    }
                }
                .padding(.horizontal, 2)
                // Cap grid width on iPad/landscape so 3 columns don't
                // stretch into giant tiles edge-to-edge.
                .frame(maxWidth: 700)
                .frame(maxWidth: .infinity)
            }
        case .documents, .audio:
            List(category == .documents ? index.documents : index.audio) { item in
                MediaFileRow(item: item)
            }
            .listStyle(.plain)
        case .links:
            List(index.links) { item in
                MediaLinkRow(item: item)
            }
            .listStyle(.plain)
        }
    }

    // MARK: - Data source

    /// Built once per render pass from the shared index so the counts on the
    /// chips and the contents below can never disagree.
    private var index: ContactMediaIndex {
        ContactMediaIndex.build(peerPublicKey: peerPublicKey, groupId: groupId)
    }
}

// MARK: - Document / voice-message row

struct MediaFileItem: Identifiable {
    let id: String
    let data: Data
    let fileName: String
    let timestamp: Date
    let isAudio: Bool
}

/// Owns the one AVAudioPlayer the gallery is allowed to have. A singleton rather
/// than per-row state so that starting a voice message always stops the previous
/// one — two overlapping playbacks is the classic bug of a per-row player.
@MainActor
final class MediaAudioPlayback: NSObject, ObservableObject, AVAudioPlayerDelegate {
    static let shared = MediaAudioPlayback()

    @Published private(set) var playingId: String?
    @Published private(set) var progress: Double = 0
    @Published private(set) var elapsed: TimeInterval = 0

    private var player: AVAudioPlayer?
    private var ticker: Timer?

    private var durationCache: [String: TimeInterval] = [:]

    /// Duration without starting playback, so a row can show its length up front.
    /// Cached: this is read from a SwiftUI body, and decoding the blob on every
    /// render pass would stutter a list of voice messages.
    func duration(of item: MediaFileItem) -> TimeInterval {
        if let cached = durationCache[item.id] { return cached }
        let d = (try? AVAudioPlayer(data: item.data))?.duration ?? 0
        durationCache[item.id] = d
        return d
    }

    func toggle(_ item: MediaFileItem) {
        if playingId == item.id { stop(); return }
        stop()
        // .playback so a voice message is still audible with the ring/silent
        // switch on — matching how the chat bubbles already behave.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try? AVAudioSession.sharedInstance().setActive(true)
        guard let p = try? AVAudioPlayer(data: item.data) else { return }
        p.delegate = self
        p.prepareToPlay()
        p.play()
        player = p
        playingId = item.id
        progress = 0
        elapsed = 0
        ticker = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let p = self.player else { return }
                self.elapsed = p.currentTime
                self.progress = p.duration > 0 ? p.currentTime / p.duration : 0
            }
        }
    }

    func stop() {
        ticker?.invalidate(); ticker = nil
        player?.stop(); player = nil
        playingId = nil
        progress = 0
        elapsed = 0
        // Hand the session back so we never hold the route after playback —
        // leaving it active is what makes the NEXT call start on the wrong route.
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.stop() }
    }
}

private struct MediaFileRow: View {
    let item: MediaFileItem
    @State private var showShare = false
    @ObservedObject private var playback = MediaAudioPlayback.shared

    private var isPlaying: Bool { playback.playingId == item.id }

    var body: some View {
        HStack(spacing: 12) {
            Button {
                if item.isAudio { playback.toggle(item) } else { showShare = true }
            } label: {
                Image(systemName: item.isAudio
                      ? (isPlaying ? "pause.circle.fill" : "play.circle.fill")
                      : "doc.fill")
                    .font(.title2)
                    .foregroundColor(galleryBrandColor)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(item.isAudio
                ? (isPlaying ? "audio.pause".localized : "audio.play".localized)
                : item.fileName)

            VStack(alignment: .leading, spacing: 3) {
                Text(item.fileName)
                    .font(.subheadline)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if item.isAudio && isPlaying {
                    ProgressView(value: playback.progress)
                        .tint(galleryBrandColor)
                    Text(Self.clock(playback.elapsed))
                        .font(.caption.monospacedDigit())
                        .foregroundColor(.secondary)
                } else {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            Spacer(minLength: 0)

            // A voice message still needs a way out to Files/other apps; the row
            // tap is spent on playback, so give sharing its own affordance.
            if item.isAudio {
                Button { showShare = true } label: {
                    Image(systemName: "square.and.arrow.up")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("common.share".localized)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { if item.isAudio { playback.toggle(item) } else { showShare = true } }
        .sheet(isPresented: $showShare) {
            MediaFileShareSheet(item: item)
        }
    }

    private var subtitle: String {
        let size = ByteCountFormatter.string(fromByteCount: Int64(item.data.count), countStyle: .file)
        let when = item.timestamp.formatted(date: .abbreviated, time: .shortened)
        if item.isAudio {
            let d = playback.duration(of: item)
            if d > 0 { return Self.clock(d) + " · " + when }
        }
        return size + " · " + when
    }

    private static func clock(_ t: TimeInterval) -> String {
        let s = Int(t.rounded())
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}

/// Writes the blob to a temp file so the system share sheet (and Quick Look via
/// "Open in…") can handle any document type without us shipping a viewer per format.
private struct MediaFileShareSheet: UIViewControllerRepresentable {
    let item: MediaFileItem

    func makeUIViewController(context: Context) -> UIActivityViewController {
        // __MEDIA_AT_REST_2026_09_22__ Copie protégée, supprimée une fois
        // l'activité terminée (et balayée au lancement suivant sinon).
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ dossier de partage (purgé au
        // partage suivant / au lancement si l'activité ne se termine jamais).
        let url = (try? MediaPlaintextTemp.writeForShare(item.data, fileName: item.fileName))
            ?? MediaPlaintextTemp.url(fileName: item.fileName)
        let vc = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        vc.completionWithItemsHandler = { _, _, _, _ in MediaPlaintextTemp.remove(url) }
        return vc
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

// MARK: - Link row

struct MediaLinkItem: Identifiable {
    let id: String
    let url: URL
    let timestamp: Date
}

private struct MediaLinkRow: View {
    let item: MediaLinkItem

    var body: some View {
        Link(destination: item.url) {
            HStack(spacing: 12) {
                Image(systemName: "link.circle.fill")
                    .font(.title2)
                    .foregroundColor(galleryBrandColor)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.url.host ?? item.url.absoluteString)
                        .font(.subheadline)
                        .lineLimit(1)
                    Text(item.url.absoluteString)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .accessibilityHint("media_gallery.open_hint".localized)
    }
}

// MARK: - Item model (view-local, does not persist)

/// Internal, not private: ContactMediaIndex exposes these to the contact profile.
struct MediaGalleryItem: Identifiable {
    let id: String
    let data: Data
    let isVideo: Bool
    let fileName: String?
}

// MARK: - Grid cell

private struct MediaGalleryCell: View {
    let item: MediaGalleryItem
    @State private var thumbnail: UIImage?
    @State private var showFullScreen = false

    var body: some View {
        Button {
            showFullScreen = true
        } label: {
            ZStack {
                // Square tile — ensures a uniform grid regardless of source aspect ratio.
                Color(.systemGray5)

                if let thumbnail {
                    Image(uiImage: thumbnail)
                        .resizable()
                        .scaledToFill()
                        .transition(.opacity)
                } else {
                    ProgressView()
                        .tint(galleryBrandColor)
                }

                // Video badge
                if item.isVideo {
                    VStack {
                        Spacer()
                        HStack {
                            Image(systemName: "play.fill")
                                .font(.caption.bold())
                                .foregroundColor(.white)
                                .shadow(radius: 2)
                                .accessibilityHidden(true)
                            Spacer()
                        }
                        .padding(6)
                    }
                }
            }
            .aspectRatio(1, contentMode: .fit)
            .clipped()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(item.isVideo ? "media_gallery.video_item".localized : "media_gallery.photo_item".localized)
        .accessibilityHint("media_gallery.open_hint".localized)
        .accessibilityAddTraits(.isImage)
        .task(id: item.id) {
            if thumbnail != nil { return }
            let decoded = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                if item.isVideo {
                    let name = item.fileName ?? "video.mp4"
                    // __MEDIA_AT_REST_2026_09_22__ Copie protégée, supprimée juste après.
                    guard let tempURL = try? MediaPlaintextTemp.write(item.data, fileName: name) else { return nil }
                    defer { MediaPlaintextTemp.remove(tempURL) }
                    let asset = AVAsset(url: tempURL)
                    let generator = AVAssetImageGenerator(asset: asset)
                    generator.appliesPreferredTrackTransform = true
                    if let cgImage = try? generator.copyCGImage(at: .zero, actualTime: nil) {
                        return UIImage(cgImage: cgImage)
                    }
                    return nil
                } else {
                    // Downsampled image (max 300px on longest side) — never decodes full-res.
                    return MediaManager.safeUIImage(from: item.data, maxDimension: 300)
                }
            }.value
            await MainActor.run {
                withAnimation(.easeOut(duration: 0.15)) {
                    self.thumbnail = decoded
                }
            }
        }
        .sheet(isPresented: $showFullScreen) {
            MediaFullScreenView(
                attachment: MediaManager.MediaAttachment(
                    id: item.id,
                    type: item.isVideo ? .video : .image,
                    data: item.data,
                    thumbnailData: nil,
                    fileName: item.fileName ?? (item.isVideo ? "video.mp4" : "photo.jpg"),
                    fileSize: item.data.count,
                    timestamp: Date(),
                    originalSize: nil,
                    compressionRatio: nil,
                    isCompressed: false
                )
            )
        }
    }
}
