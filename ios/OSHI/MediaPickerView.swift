//
//  MediaPickerView.swift
//  ✅ FULLY LOCALIZED: All user-facing strings use .localized
//  ✅ UPDATED: WhatsApp-style per-item view-once toggle
//

import SwiftUI
import PhotosUI
import AVFoundation
import ImageIO

/// Dans quel mode le sélecteur s'ouvre — et le fait QU'IL s'ouvre.
///
/// 🔴 __PICKER_MODE_RACE_2026_08_22__ Le mode était un `Bool` à part du drapeau
/// de présentation, copié par valeur dans un `ViewModifier`. `.sheet(isPresented:)`
/// capturait son contenu à la présentation, donc la feuille pouvait s'ouvrir avec
/// le mode d'AVANT: les deux entrées du menu donnaient le même écran, et « vue
/// unique » — qui ne vit que dans la feuille d'aperçu du mode simple — restait
/// introuvable.
///
/// Rendre le mode `Identifiable` et piloter la feuille par `.sheet(item:)` rend
/// la course impossible à écrire: on ne peut pas présenter sans dire lequel.
enum MediaPickerMode: String, Identifiable {
    /// La pellicule, directement. Une pièce ou plusieurs, photos et vidéos.
    /// Une sélection UNIQUE passe par l'écran d'options (« vue unique »,
    /// révélation progressive); plusieurs passent par la bande de sélection.
    case library
    /// L'écran de choix complet (appareil photo, document, contact…).
    case chooser
    var id: String { rawValue }
}

struct MediaPickerView: View {
    @Environment(\.dismiss) var dismiss
    @Environment(\.colorScheme) var colorScheme
    @StateObject private var mediaManager = MediaManager()
    
    // ✅ Support both single and multi-select
    let onMediaSelected: (MediaManager.MediaAttachment) -> Void
    let onMultipleMediaSelected: (([MediaManager.MediaAttachment]) -> Void)?
    let allowMultipleSelection: Bool
    
    @State private var selectedItems: [PhotosPickerItem] = []
    @State private var selectedAttachments: [MediaManager.MediaAttachment] = []
    @State private var showCamera = false
    @State private var showVideoPicker = false
    @State private var showDocumentPicker = false
    @State private var showContactSelector = false
    @State private var errorMessage: String?
    @State private var showError = false
    @State private var isProcessing = false
    
    // ✅ REMOVED: Global isViewOnce toggle - now per-item
    // ✅ NEW: Show media preview for single selection with view-once option
    @State private var pendingAttachment: MediaManager.MediaAttachment?
    @State private var showMediaPreview = false
    @State private var previewIsViewOnce = false
    
    // ✅ Initialize with optional multi-select support
    /// __GALLERY_DIRECT_2026_08_22__ Ouvrir la pellicule SANS écran intermédiaire.
    ///
    /// Signalé deux fois par Hugo: « Galerie » et « Sélectionner un média »
    /// donnaient exactement la même vue. C'était vrai, et je regardais le mauvais
    /// niveau: les deux entrées mènent à cet écran, qui est une LISTE DE BOUTONS
    /// portant le MÊME libellé (`media_picker.photo_or_video`) dans les deux
    /// modes. La différence entre les modes n'apparaît qu'APRÈS la sélection.
    /// Depuis le pouce de l'utilisateur, c'est deux taps pour atteindre ses
    /// photos, et deux chemins indiscernables.
    ///
    /// Le reste de l'écran — appareil photo, document, contact — a déjà ses
    /// propres entrées dans le menu « + » de la conversation.
    let autoOpenLibrary: Bool

    @State private var libraryPresented = false

    // ✅ Initialize with optional multi-select support
    init(
        allowMultipleSelection: Bool = false,
        autoOpenLibrary: Bool = false,
        onMediaSelected: @escaping (MediaManager.MediaAttachment) -> Void,
        onMultipleMediaSelected: (([MediaManager.MediaAttachment]) -> Void)? = nil
    ) {
        self.allowMultipleSelection = allowMultipleSelection
        self.autoOpenLibrary = autoOpenLibrary
        self.onMediaSelected = onMediaSelected
        self.onMultipleMediaSelected = onMultipleMediaSelected
    }
    
    // MARK: - Computed Views for Compiler Performance
    
    private var captureSection: some View {
        Section {
            Button {
                showCamera = true
            } label: {
                Label("media_picker.take_photo".localized, systemImage: "camera.fill")
                    .foregroundColor(.primary)
            }
            .tint(.blue)
            
            Button {
                showVideoPicker = true
            } label: {
                Label("media_picker.record_video".localized, systemImage: "video.fill")
                    .foregroundColor(.primary)
            }
            .tint(.blue)
        } header: {
            Text("media_picker.capture".localized)
                .font(.headline)
                .foregroundColor(.primary)
                .textCase(nil)
        }
    }
    
    private var librarySection: some View {
        Section {
            if allowMultipleSelection {
                PhotosPicker(
                    selection: $selectedItems,
                    maxSelectionCount: 10,
                    matching: .any(of: [.images, .videos])
                ) {
                    // 🔧 (2026-08-08) One entry point for one OR several items.
                    // "Choose multiple" was the only row that accepted VIDEO
                    // (the single-item row below filtered `.images`), so sending
                    // one video meant discovering that a button labelled for
                    // several was the only one that worked.
                    Label("media_picker.photo_or_video".localized,
                          systemImage: "photo.on.rectangle.angled")
                        .foregroundColor(.primary)
                }
                .tint(.blue)
            } else {
                PhotosPicker(
                    selection: Binding(
                        get: { selectedItems.first },
                        set: { if let item = $0 { selectedItems = [item] } }
                    ),
                    // Videos accepted here too. The restriction to `.images` is
                    // what forced the multi-select detour.
                    matching: .any(of: [.images, .videos])
                ) {
                    Label("media_picker.photo_or_video".localized,
                          systemImage: "photo.on.rectangle")
                        .foregroundColor(.primary)
                }
                .tint(.blue)
                
                PhotosPicker(
                    selection: Binding(
                        get: { selectedItems.first },
                        set: { if let item = $0 { selectedItems = [item] } }
                    ),
                    matching: .videos
                ) {
                    Label("media_picker.choose_video".localized, systemImage: "video.badge.plus")
                        .foregroundColor(.primary)
                }
                .tint(.blue)
            }
        } header: {
            Text("media_picker.library".localized)
                .font(.headline)
                .foregroundColor(.primary)
                .textCase(nil)
        }
    }
    
    private var filesSection: some View {
        Section {
            Button {
                showDocumentPicker = true
            } label: {
                Label("media_picker.choose_document".localized, systemImage: "doc.fill")
                    .foregroundColor(.primary)
            }
            .tint(.blue)
            
            Button {
                showContactSelector = true
            } label: {
                Label("media_picker.share_contact".localized, systemImage: "person.crop.circle.fill")
                    .foregroundColor(.primary)
            }
            .tint(.blue)
        } header: {
            Text("media_picker.files".localized)
                .font(.headline)
                .foregroundColor(.primary)
                .textCase(nil)
        }
    }
    
    private var mainContent: some View {
        VStack(spacing: 0) {
            // 🔴 __GALLERY_FLASH_2026_08_22__ Le clignotement signalé par Hugo.
            //
            // En mode pellicule directe, cet écran s'affichait quand même — sa
            // liste de boutons, son titre — pendant la fraction de seconde qui
            // sépare son apparition de l'ouverture du sélecteur système, armée
            // dans `.onAppear`. On voyait donc passer l'écran qu'on venait
            // précisément de retirer du chemin.
            //
            // Tant qu'aucune pièce n'est choisie, on ne dessine RIEN: un fond
            // uni, que le sélecteur recouvre aussitôt. Dès qu'il y a une
            // sélection, la bande d'aperçu revient — c'est elle qu'on veut voir
            // au retour du sélecteur, pas la liste de boutons.
            if autoOpenLibrary && selectedAttachments.isEmpty {
                Color(.systemBackground).ignoresSafeArea()
            } else {
            // ✅ Preview of selected items (multi-select mode)
            if allowMultipleSelection && !selectedAttachments.isEmpty {
                selectedItemsPreview
            }
            
            List {
                captureSection
                librarySection
                filesSection

                if allowMultipleSelection {
                    Section {
                        Text("media_picker.select_limit".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }
            .listStyle(.insetGrouped)
            // Center the option list on iPad/landscape so rows don't run
            // edge-to-edge across a wide screen.
            .frame(maxWidth: 700)
            .frame(maxWidth: .infinity)
            }
        }
    }
    
    private var navigationContent: some View {
        mainContent
            // Pas de titre non plus tant qu'on n'a rien choisi: une barre de
            // navigation qui apparaît puis disparaît est le même clignotement.
            .navigationTitle(autoOpenLibrary && selectedAttachments.isEmpty
                             ? ""
                             : (allowMultipleSelection ? "media_picker.select_media".localized
                                                       : "media_picker.add_media".localized))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) {
                        dismiss()
                    }
                }
                
                if allowMultipleSelection && !selectedAttachments.isEmpty {
                    ToolbarItem(placement: .confirmationAction) {
                        sendButton
                    }
                }
            }
    }
    
    private var sendButton: some View {
        Button {
            if let handler = onMultipleMediaSelected {
                handler(selectedAttachments)
            }
            dismiss()
        } label: {
            Text("media_picker.add_count".localized(with: selectedAttachments.count))
                .fontWeight(.semibold)
                .foregroundColor(.white)
        }
        .buttonStyle(.borderedProminent)
        .tint(.blue)
    }
    
    private var sheetModifiers: some View {
        navigationContent
            .sheet(isPresented: $showCamera) {
                CameraView { image in
                    handleCapturedImage(image)
                }
            }
            .sheet(isPresented: $showVideoPicker) {
                VideoRecorderView { url in
                    handleRecordedVideo(url)
                }
            }
            .sheet(isPresented: $showDocumentPicker) {
                DocumentPicker(onSelect: { url in
                    handleSelectedDocument(url)
                })
            }
            .sheet(isPresented: $showContactSelector) {
                ContactSelectorView { publicKey, alias in
                    handleSelectedContact(publicKey: publicKey, alias: alias)
                }
            }
            // ✅ NEW: Media preview sheet with view-once option
            .sheet(isPresented: $showMediaPreview) {
                if let attachment = pendingAttachment {
                    MediaPreviewSheet(
                        attachment: attachment,
                        isViewOnce: $previewIsViewOnce,
                        onSend: { finalAttachment in
                            onMediaSelected(finalAttachment)
                            pendingAttachment = nil
                            dismiss()
                        },
                        onCancel: {
                            pendingAttachment = nil
                            previewIsViewOnce = false
                            // 🔴 __GALLERY_DEAD_END_2026_08_23__ En mode pellicule
                            // directe, annuler l'aperçu laissait l'utilisateur sur
                            // le FOND VIDE mis en place pour supprimer le
                            // clignotement: pas de titre, pas de contenu, et
                            // `libraryPresented` déjà à faux — donc le `.onChange`
                            // qui referme ne se redéclenchait jamais. Écran mort,
                            // introduit par le correctif du clignotement lui-même.
                            //
                            // Annuler l'aperçu, c'est annuler l'envoi: on referme
                            // tout, comme le ferait « Annuler » sur le sélecteur.
                            if autoOpenLibrary && selectedAttachments.isEmpty {
                                dismiss()
                            }
                        }
                    )
                }
            }
            .onChange(of: selectedItems) { oldValue, newValue in
                Task {
                    await processSelectedItems(newValue)
                }
            }
            // __GALLERY_DIRECT_2026_08_22__ La pellicule s'ouvre d'elle-même quand
            // l'écran a été demandé pour ça. `.photosPicker` accepte images ET
            // vidéos, et jusqu'à 10 pièces: « une ou plusieurs » est le MÊME geste,
            // ce qui supprime la question à laquelle l'utilisateur devait répondre
            // avant même de voir ses photos.
            .photosPicker(isPresented: $libraryPresented,
                          selection: $selectedItems,
                          maxSelectionCount: autoOpenLibrary ? 10 : 1,
                          matching: .any(of: [.images, .videos]))
            .onAppear {
                if autoOpenLibrary && selectedItems.isEmpty && selectedAttachments.isEmpty {
                    libraryPresented = true
                }
            }
            // Annuler le sélecteur système doit refermer TOUT: sinon on retombe
            // sur le fond vide, un écran sans titre et sans contenu dont on ne
            // comprend pas ce qu'il attend.
            .onChange(of: libraryPresented) { _, isPresented in
                guard autoOpenLibrary, !isPresented else { return }
                if selectedItems.isEmpty && selectedAttachments.isEmpty && pendingAttachment == nil {
                    dismiss()
                }
            }
            .alert("common.error".localized, isPresented: $showError) {
                Button("common.ok".localized) { }
            } message: {
                if let message = errorMessage {
                    Text(message)
                }
            }
    }
    
    var body: some View {
        NavigationView {
            sheetModifiers
        }
    }
    
    // ✅ Preview of selected items (multi-select with per-item view-once toggle)
    private var selectedItemsPreview: some View {
        VStack(spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(Array(selectedAttachments.enumerated()), id: \.offset) { index, attachment in
                        MediaThumbnailWithViewOnce(
                            attachment: attachment,
                            onRemove: {
                                removeAttachment(at: index)
                            },
                            onToggleViewOnce: {
                                toggleViewOnce(at: index)
                            }
                        )
                    }
                }
                .padding(.horizontal)
                .padding(.vertical, 8)
            }
            
            // ✅ View once hint
            if selectedAttachments.contains(where: { $0.isViewOnce }) {
                HStack(spacing: 6) {
                    Image(systemName: "eye.slash.circle.fill")
                        .foregroundColor(.orange)
                        .accessibilityHidden(true)
                    Text("media_picker.view_once.enabled".localized)
                        .font(.caption)
                        .foregroundColor(.orange)
                }
                .padding(.bottom, 4)
                .transition(.opacity.combined(with: .move(edge: .top)))
                .accessibilityElement(children: .combine)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: selectedAttachments.contains(where: { $0.isViewOnce }))
        .background(Color(.systemGray6))
    }
    
    // ✅ Toggle view-once for a specific attachment
    private func toggleViewOnce(at index: Int) {
        guard index < selectedAttachments.count else { return }
        selectedAttachments[index].isViewOnce.toggle()
        
        // Haptic feedback
        let generator = UIImpactFeedbackGenerator(style: .light)
        generator.impactOccurred()
    }
    
    private func removeAttachment(at index: Int) {
        guard index < selectedAttachments.count && index < selectedItems.count else { return }
        selectedAttachments.remove(at: index)
        selectedItems.remove(at: index)
    }
    
    private func processSelectedItems(_ items: [PhotosPickerItem]) async {
        guard !items.isEmpty else { return }

        await MainActor.run {
            isProcessing = true
        }

        var newAttachments: [MediaManager.MediaAttachment] = []

        for item in items {
            // Check if already processed
            if selectedAttachments.contains(where: { $0.fileName == item.itemIdentifier }) {
                continue
            }

            let isVideo = item.supportedContentTypes.contains(where: { $0.conforms(to: .movie) })

            // __CHAT_VIDEO_BITRATE_2026_10_09__ A video is fetched as a FILE,
            // never as `Data`.
            //
            // Both branches used to go through `loadTransferable(type: Data.self)`
            // and the video branch then spilled those bytes straight back out to
            // a temp file for `processVideo`. For a 4K clip that is a ~100 MB
            // allocation and a ~100 MB write before the encoder has even
            // started — a large part of "it takes a while to show the ephemeral
            // video panel", and a memory spike on top of it. PhotosUI can hand
            // us the file directly, so the copy happens inside the file system
            // and never passes through RAM.
            if isVideo {
                do {
                    guard let movie = try await item.loadTransferable(type: PickedMovie.self) else {
                        OshiLog.storage.info("⚠️ Picker returned no file for a video item")
                        continue
                    }
                    defer { MediaPlaintextTemp.remove(movie.url) }
                    let attachment = try await mediaManager.processVideo(at: movie.url,
                                                                        deliveryMethod: .auto)
                    newAttachments.append(attachment)
                } catch {
                    OshiLog.storage.info("⚠️ Failed to process video from picker: \(error)")
                }
                continue
            }

            if let data = try? await item.loadTransferable(type: Data.self) {
                let type: MediaManager.MediaType = .image

                if type == .image {
                    // Process image through MediaManager for safe resize + compression
                    do {
                        let attachment = try await MediaPickerView.processImageDataSafely(
                            data,
                            fileName: item.itemIdentifier ?? UUID().uuidString
                        )
                        newAttachments.append(attachment)
                    } catch {
                        OshiLog.storage.info("⚠️ Failed to process image from picker: \(error)")
                    }
                }
            }
        }

        await MainActor.run {
            // __GALLERY_DIRECT_2026_08_22__ UNE seule pièce choisie, et rien déjà
            // dans la bande: c'est un envoi simple, il a droit à l'écran d'options
            // (« vue unique », révélation progressive) même si le sélecteur est
            // ouvert en mode multiple. Sinon choisir « une » photo depuis la
            // pellicule ferait perdre les options — exactement la panne qu'on
            // vient de corriger, sous une autre forme.
            if allowMultipleSelection, newAttachments.count == 1, selectedAttachments.isEmpty,
               let only = newAttachments.first {
                pendingAttachment = only
                previewIsViewOnce = false
                showMediaPreview = true
            } else if allowMultipleSelection {
                selectedAttachments.append(contentsOf: newAttachments)
            } else if let first = newAttachments.first {
                // Single select: Show preview with view-once option
                pendingAttachment = first
                previewIsViewOnce = false
                showMediaPreview = true
            }
            isProcessing = false
        }
    }

    /// Longest-side cap applied to every shared photo. 1600 px is the WhatsApp-class
    /// target: indistinguishable on a phone screen, but a third of the pixels of 1920.
    static let shareMaxDimension: CGFloat = 1600

    /// Soft byte budget per shared photo.
    ///
    /// 🔧 (2026-08-12) Was a flat 200 KB, chosen when the legacy envelope
    /// amplified every byte 5.92× — a 1.8 MB photo really did cost ~10 MB of
    /// upload, so starving the source was the only lever available.
    ///
    /// With the v2 signing-key fix (8f83eec) media rides the blob store at ~1.05×
    /// instead, and that trade is no longer worth making: 200 KB was visibly
    /// costing image quality to save bytes that are no longer expensive.
    ///
    /// So the budget follows the link instead of assuming the worst one:
    ///   • Wi-Fi / 5G / unmetered → 1 MB. ~1.05 MB on the wire, about a second on
    ///     any decent uplink, and enough for a 1600 px photo at q≈0.85 — better
    ///     looking than today, where the ladder bottomed out at q=0.45.
    ///   • Expensive or constrained (cellular the OS flags as metered, Low Data
    ///     Mode) → 400 KB. Someone on a weak or capped link should not pay a
    ///     megabyte per photo just because the code was tuned on Wi-Fi.
    ///
    /// Read at encode time, not cached: the link can change between two sends.
    static var shareTargetBytes: Int {
        let net = NetworkReachabilityManager.shared
        return (net.isExpensive || net.isConstrained) ? 400 * 1024 : 1_000_000
    }

    /// Encode to JPEG against a byte BUDGET rather than a fixed quality. A flat
    /// q=0.80 ships 0.8–2 MB per photo regardless of content; a quality ladder lands
    /// detailed and flat images alike near the budget. Bounded at 4 encodes (~15 ms
    /// each on A-series) and floored at 0.45, below which artefacts show at 1600 px.
    /// 🔧 FIX (2026-08-12, "25 s to send a photo"): the ladder searched QUALITY
    /// only, and stopped at its 0.45 floor. A detailed 1600 px photo does not
    /// reach a 200 KB budget on quality alone, so it simply gave up and shipped
    /// whatever it had — measured on a real send, 641 KB against a 200 KB budget,
    /// more than 3× over.
    ///
    /// Dropping the floor further is the wrong move: below q≈0.45 at 1600 px the
    /// artefacts are visible, and the whole point of the budget is to keep photos
    /// looking right. Resolution is the axis with headroom — halving the linear
    /// size quarters the pixel count, so a step down buys roughly 4× the bytes at
    /// UNCHANGED quality, which reads far better than a smeared full-size image.
    ///
    /// So: try quality at the current size, and if the floor is hit while still
    /// over budget, step the size down and try again. 1600 → 1280 → 1024 → 800
    /// is 4 steps of ~0.8 linear, gentle enough that no single step is visible on
    /// a phone screen.
    ///
    /// Every byte saved here is multiplied on the wire, which is why this is worth
    /// the extra encodes: the legacy envelope amplifies ~5.9×, so 440 KB shaved off
    /// the source is ~2.6 MB never uploaded.
    static func encodeToShareBudget(_ image: UIImage) -> Data? {
        var working = image
        var best: Data? = nil
        var lastQuality: CGFloat = 0.85

        for step in 0..<4 {
            var quality: CGFloat = 0.85
            var encoded = autoreleasepool { working.jpegData(compressionQuality: quality) }
            while let d = encoded, d.count > shareTargetBytes, quality > 0.45 {
                quality -= 0.09
                encoded = autoreleasepool { working.jpegData(compressionQuality: quality) }
            }
            lastQuality = quality
            // Keep the smallest result seen, so a failure to reach the budget
            // still returns the best attempt rather than the first one.
            if let d = encoded, best == nil || d.count < best!.count { best = d }
            if let d = encoded, d.count <= shareTargetBytes { break }

            // Still over budget at the quality floor — shrink and retry.
            let next = CGSize(width: (working.size.width * 0.8).rounded(),
                              height: (working.size.height * 0.8).rounded())
            guard step < 3, next.width >= 640, let smaller = resized(working, to: next) else { break }
            working = smaller
        }

        if let d = best {
            OshiLog.storage.info("🗜️ Encoded at q=\(String(format: "%.2f", lastQuality)) \(Int(working.size.width))px → \(ByteCountFormatter.string(fromByteCount: Int64(d.count), countStyle: .file))")
        }
        return best
    }

    /// Redraw at `size`. `UIGraphicsImageRenderer` with an opaque format: a photo
    /// has no alpha, and carrying one would add a channel to every pixel of an
    /// image we are trying to make smaller.
    private static func resized(_ image: UIImage, to size: CGSize) -> UIImage? {
        guard size.width > 0, size.height > 0 else { return nil }
        let format = UIGraphicsImageRendererFormat.default()
        format.opaque = true
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    /// Safely process raw image Data: downsample to max 1600px, compress to a byte budget.
    /// Static so the attachment panel (__ATTACH_PANEL_2027_2026_11_17__) sends a
    /// photo from its recent grid through exactly the same encode as this picker.
    static func processImageDataSafely(_ data: Data, fileName: String) async throws -> MediaManager.MediaAttachment {
        return try await Task.detached(priority: .userInitiated) {
            let originalSize = data.count

            // Use ImageIO to downsample without loading full bitmap
            let maxDimension: CGFloat = MediaPickerView.shareMaxDimension
            guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
                throw MediaError.processingFailed
            }

            // Read dimensions
            var needsDownsample = false
            if let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
               let pw = props[kCGImagePropertyPixelWidth] as? CGFloat,
               let ph = props[kCGImagePropertyPixelHeight] as? CGFloat {
                let longestSide = max(pw, ph)
                needsDownsample = longestSide > maxDimension
                OshiLog.storage.info("📸 Picker image: \(Int(pw))x\(Int(ph)), \(ByteCountFormatter.string(fromByteCount: Int64(originalSize), countStyle: .file))")
            }

            let processedImage: UIImage
            if needsDownsample {
                let options: [CFString: Any] = [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: maxDimension,
                    kCGImageSourceShouldCacheImmediately: true
                ]
                guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
                    throw MediaError.processingFailed
                }
                processedImage = UIImage(cgImage: cgImage)
                OshiLog.storage.info("📐 Downsampled to \(processedImage.size.width)x\(processedImage.size.height)")
            } else {
                guard let img = UIImage(data: data) else {
                    throw MediaError.processingFailed
                }
                processedImage = img
            }

            guard let compressedData = MediaPickerView.encodeToShareBudget(processedImage) else {
                throw MediaError.compressionFailed
            }

            let isCompressed = compressedData.count < originalSize
            let ratio = Double(compressedData.count) / Double(originalSize)
            OshiLog.storage.info("✅ Picker image processed: \(ByteCountFormatter.string(fromByteCount: Int64(originalSize), countStyle: .file)) → \(ByteCountFormatter.string(fromByteCount: Int64(compressedData.count), countStyle: .file))")

            return MediaManager.MediaAttachment(
                id: UUID().uuidString,
                type: .image,
                data: compressedData,
                thumbnailData: nil,
                fileName: fileName,
                fileSize: compressedData.count,
                timestamp: Date(),
                originalSize: originalSize,
                compressionRatio: ratio,
                isCompressed: isCompressed,
                isViewOnce: false
            )
        }.value
    }
    
    private func handleCapturedImage(_ image: UIImage) {
        // Resize to max 1600px on longest side before compressing
        let maxDim: CGFloat = MediaPickerView.shareMaxDimension
        let longestSide = max(image.size.width, image.size.height)
        let resizedImage: UIImage
        if longestSide > maxDim {
            let scale = maxDim / longestSide
            let newSize = CGSize(
                width: round(image.size.width * scale),
                height: round(image.size.height * scale)
            )
            let renderer = UIGraphicsImageRenderer(size: newSize)
            resizedImage = renderer.image { _ in
                image.draw(in: CGRect(origin: .zero, size: newSize))
            }
        } else {
            resizedImage = image
        }

        guard let data = MediaPickerView.encodeToShareBudget(resizedImage) else {
            errorMessage = "media_picker.error_process_image".localized
            showError = true
            return
        }

        let attachment = MediaManager.MediaAttachment(
            id: UUID().uuidString,
            type: .image,
            data: data,
            thumbnailData: nil,
            fileName: "photo_\(Date().timeIntervalSince1970).jpg",
            fileSize: data.count,
            timestamp: Date(),
            originalSize: nil,
            compressionRatio: nil,
            isCompressed: true,
            isViewOnce: false
        )

        if allowMultipleSelection {
            selectedAttachments.append(attachment)
        } else {
            pendingAttachment = attachment
            previewIsViewOnce = false
            showMediaPreview = true
        }
    }
    
    private func handleRecordedVideo(_ url: URL) {
        // Compress the recorded clip through MediaManager (AVAssetExportSession)
        // instead of holding the raw capture in memory — keeps recorded videos on
        // the same compressed path as picked ones.
        Task {
            // __PLAINTEXT_LEFTOVERS_2026_09_24__ La capture brute de la caméra
            // (`tmp/<id>.MOV` d'UIImagePickerController) n'a plus d'usage une fois
            // compressée: supprimée qu'on ait réussi ou non.
            defer { try? FileManager.default.removeItem(at: url) }
            do {
                let attachment = try await mediaManager.processVideo(at: url, deliveryMethod: .auto)
                await MainActor.run {
                    if allowMultipleSelection {
                        selectedAttachments.append(attachment)
                    } else {
                        // ✅ Show preview with view-once option
                        pendingAttachment = attachment
                        previewIsViewOnce = false
                        showMediaPreview = true
                    }
                }
            } catch {
                OshiLog.storage.info("⚠️ Failed to process recorded video: \(error)")
                await MainActor.run {
                    errorMessage = "media_picker.error_process_video".localized
                    showError = true
                }
            }
        }
    }
    
    private func generateVideoThumbnail(from data: Data) -> Data? {
        let tempURL = MediaPlaintextTemp.scratchURL(ext: "mp4")  // __PLAINTEXT_LEFTOVERS_2026_09_24__
        
        do {
            try data.write(to: tempURL, options: [.completeFileProtectionUnlessOpen])
            
            let asset = AVAsset(url: tempURL)
            let imageGenerator = AVAssetImageGenerator(asset: asset)
            imageGenerator.appliesPreferredTrackTransform = true
            
            let time = CMTime(seconds: 1, preferredTimescale: 60)
            let cgImage = try imageGenerator.copyCGImage(at: time, actualTime: nil)
            let image = UIImage(cgImage: cgImage)
            
            try? FileManager.default.removeItem(at: tempURL)
            
            return image.jpegData(compressionQuality: 0.7)
        } catch {
            try? FileManager.default.removeItem(at: tempURL)
            return nil
        }
    }
    
    // ✅ Handle selected document
    private func handleSelectedDocument(_ url: URL) {
        OshiLog.storage.info("📄 handleSelectedDocument called with: \(url.lastPathComponent)")
        
        showDocumentPicker = false
        isProcessing = true
        
        Task {
            do {
                let attachment = try await mediaManager.processDocument(from: url)
                
                await MainActor.run {
                    isProcessing = false
                    
                    if allowMultipleSelection {
                        selectedAttachments.append(attachment)
                    } else {
                        onMediaSelected(attachment)
                        dismiss()
                    }
                }
            } catch {
                await MainActor.run {
                    isProcessing = false
                    errorMessage = "Failed to process document: \(error.localizedDescription)"
                    showError = true
                }
            }
        }
    }
    
    // ✅ Handle selected contact
    private func handleSelectedContact(publicKey: String, alias: String?) {
        isProcessing = true
        Task {
            do {
                let attachment = try await mediaManager.createContactCard(
                    publicKey: publicKey,
                    alias: alias
                )
                
                await MainActor.run {
                    isProcessing = false
                    if allowMultipleSelection {
                        selectedAttachments.append(attachment)
                    } else {
                        onMediaSelected(attachment)
                        dismiss()
                    }
                }
            } catch {
                await MainActor.run {
                    isProcessing = false
                    errorMessage = error.localizedDescription
                    showError = true
                }
            }
        }
    }
}

// MARK: - Media Thumbnail with View Once Toggle (WhatsApp style)

struct MediaThumbnailWithViewOnce: View {
    let attachment: MediaManager.MediaAttachment
    let onRemove: () -> Void
    let onToggleViewOnce: () -> Void
    
    @ViewBuilder
    private var thumbnailView: some View {
        if attachment.type == .image, let image = MediaManager.safeUIImage(from: attachment.data, maxDimension: 200) {
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fill)
        } else if let thumbnailData = attachment.thumbnailData,
                  let thumbnail = UIImage(data: thumbnailData) {
            ZStack {
                Image(uiImage: thumbnail)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                
                // Video indicator
                Image(systemName: "play.circle.fill")
                    .font(.title)
                    .foregroundColor(.white)
            }
        } else {
            ZStack {
                Rectangle()
                    .fill(Color.gray.opacity(0.3))
                
                Image(systemName: attachment.type == .video ? "video.fill" : "photo.fill")
                    .foregroundColor(.gray)
            }
        }
    }
    
    var body: some View {
        ZStack(alignment: .topTrailing) {
            // Thumbnail
            thumbnailView
                .frame(width: 80, height: 80)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(attachment.isViewOnce ? Color.orange : Color.clear, lineWidth: 2)
                )
                .animation(.easeInOut(duration: 0.2), value: attachment.isViewOnce)
                .accessibilityHidden(true)
            
            // Remove button (top right) — visible glyph kept at the corner,
            // hit area padded out to a comfortable target via contentShape.
            Button(action: onRemove) {
                Image(systemName: "xmark.circle.fill")
                    .font(.scaled(20))
                    .foregroundColor(.white)
                    .background(
                        Circle()
                            .fill(Color.black.opacity(0.6))
                    )
                    .padding(8)
                    .contentShape(Circle())
            }
            .offset(x: 6, y: -6)
            .accessibilityLabel("media_picker.remove_item".localized)

            // ✅ View once toggle (bottom right) - WhatsApp style "1" icon
            Button(action: onToggleViewOnce) {
                ZStack {
                    Circle()
                        .fill(attachment.isViewOnce ? Color.orange : Color.black.opacity(0.6))
                        .frame(width: 24, height: 24)

                    if attachment.isViewOnce {
                        Text("1")
                            .font(.scaled(12, weight: .bold))
                            .foregroundColor(.white)
                    } else {
                        Image(systemName: "eye.slash")
                            .font(.scaled(10))
                            .foregroundColor(.white)
                    }
                }
                .padding(10)
                .contentShape(Circle())
            }
            .offset(x: 6, y: 46)
            .accessibilityLabel("media_picker.view_once".localized)
            .accessibilityValue(attachment.isViewOnce ? "common.on".localized : "common.off".localized)
            .accessibilityHint("media_picker.view_once.description".localized)
        }
        .frame(width: 80, height: 80)
    }
}

// MARK: - Media Preview Sheet (Single selection with view-once option)

struct MediaPreviewSheet: View {
    let attachment: MediaManager.MediaAttachment
    @Binding var isViewOnce: Bool

    /// __REVEAL_OPTION_2026_08_22__ Les options cochées pour CETTE photo.
    ///
    /// `MediaSendOptions` porte l'exclusion mutuelle et repart de `.none` à
    /// chaque présentation — une option ne peut pas déborder sur l'envoi suivant.
    @State private var options: MediaSendOptions = .none

    private var revealDuration: TimeInterval? { options.revealDuration }
    let onSend: (MediaManager.MediaAttachment) -> Void
    let onCancel: () -> Void
    
    @Environment(\.dismiss) var dismiss
    @Environment(\.colorScheme) var colorScheme
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Media preview
                Spacer()
                
                if attachment.type == .image, let image = MediaManager.safeUIImage(from: attachment.data, maxDimension: 1200) {
                    Image(uiImage: image)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(maxWidth: .infinity, maxHeight: 400)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .padding()
                        .accessibilityLabel("media.view_once.photo".localized)
                } else if let thumbnailData = attachment.thumbnailData,
                          let thumbnail = UIImage(data: thumbnailData) {
                    ZStack {
                        Image(uiImage: thumbnail)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(maxWidth: .infinity, maxHeight: 400)
                            .clipShape(RoundedRectangle(cornerRadius: 12))

                        Image(systemName: "play.circle.fill")
                            .font(.scaled(60))
                            .foregroundColor(.white.opacity(0.9))
                            .accessibilityHidden(true)
                    }
                    .padding()
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("media.view_once.video".localized)
                }
                
                Spacer()
                
                // ✅ View once toggle - WhatsApp style
                VStack(spacing: 16) {
                    Divider()
                    
                    Button {
                        options.setViewOnce(!isViewOnce)
                        isViewOnce = options.isViewOnce
                        let generator = UIImpactFeedbackGenerator(style: .light)
                        generator.impactOccurred()
                    } label: {
                        HStack(spacing: 12) {
                            ZStack {
                                Circle()
                                    .fill(isViewOnce ? Color.orange : Color.gray.opacity(0.3))
                                    .frame(width: 36, height: 36)
                                
                                if isViewOnce {
                                    Text("1")
                                        .font(.scaled(16, weight: .bold))
                                        .foregroundColor(.white)
                                } else {
                                    Image(systemName: "eye.slash")
                                        .font(.scaled(14))
                                        .foregroundColor(.gray)
                                }
                            }
                            
                            VStack(alignment: .leading, spacing: 2) {
                                Text("media_picker.view_once".localized)
                                    .font(.body)
                                    .foregroundColor(.primary)
                                Text("media_picker.view_once.description".localized)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            
                            Spacer()
                            
                            Image(systemName: isViewOnce ? "checkmark.circle.fill" : "circle")
                                .font(.title2)
                                .foregroundColor(isViewOnce ? .orange : .gray)
                        }
                        .padding()
                        .background(
                            RoundedRectangle(cornerRadius: 12)
                                .fill(colorScheme == .dark ? Color(.systemGray6) : Color(.systemGray6))
                        )
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal)
                    
                    // ✅ Warning when enabled
                    if isViewOnce {
                        HStack(spacing: 6) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundColor(.orange)
                                .accessibilityHidden(true)
                            Text("media_picker.view_once.warning".localized)
                                .font(.caption)
                                .foregroundColor(.orange)
                        }
                        .padding(.horizontal)
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                        .accessibilityElement(children: .combine)
                    }

                    // __REVEAL_OPTION_2026_08_22__ Révélation progressive.
                    //
                    // Proposée UNIQUEMENT pour une photo: une vidéo qui se révèle
                    // n'a pas de sens, et l'encodeur en couches ne traite que des
                    // images.
                    //
                    // Les durées passent par `RevealDurationFormat.short`, donc par
                    // `MeasurementFormatter`: « 5 min », « 1 h » dans la langue du
                    // système, sans une seule chaîne à traduire. Seuls le titre et
                    // sa description sont des clés, ajoutées aux 34 locales.
                    if attachment.type == .image {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(spacing: 12) {
                                ZStack {
                                    Circle()
                                        .fill(revealDuration != nil ? Color.purple : Color.gray.opacity(0.3))
                                        .frame(width: 36, height: 36)
                                    Image(systemName: "hourglass")
                                        .font(.scaled(15))
                                        .foregroundColor(revealDuration != nil ? .white : .gray)
                                }
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("media_picker.reveal".localized)
                                        .font(.body)
                                        .foregroundColor(.primary)
                                    Text("media_picker.reveal.description".localized)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                                Spacer()
                            }
                            .accessibilityElement(children: .combine)

                            // Une pastille = une durée. Retoucher celle qui est
                            // active la désarme: l'option se referme du même geste
                            // qu'elle s'ouvre.
                            // Grille souple, pas une rangée: huit durées ne
                            // tiennent pas sur une ligne, et une largeur en dur est
                            // exactement ce qui déborde des iPhone étroits.
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 8)], spacing: 8) {
                                ForEach(RevealMode.presets, id: \.self) { preset in
                                    let isOn = revealDuration == preset
                                    Button {
                                        options.toggleReveal(preset)
                                        isViewOnce = options.isViewOnce
                                    } label: {
                                        Text(RevealDurationFormat.short(preset))
                                            .font(.footnote.weight(.medium))
                                            .lineLimit(1)
                                            .minimumScaleFactor(0.8)
                                            .foregroundColor(.primary)
                                            .frame(maxWidth: .infinity)
                                            .padding(.vertical, 10)
                                            .background(Color.purple.opacity(isOn ? 0.30 : 0.12), in: Capsule())
                                            .overlay(Capsule().stroke(Color.purple.opacity(isOn ? 0.75 : 0.30), lineWidth: 1))
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityLabel("\("media_picker.reveal".localized) — \(RevealDurationFormat.short(preset))")
                                    .accessibilityAddTraits(isOn ? [.isSelected, .isButton] : .isButton)
                                }
                            }
                        }
                        .padding()
                        .background(
                            RoundedRectangle(cornerRadius: 12)
                                .fill(Color(.systemGray6))
                        )
                        .padding(.horizontal)
                    }

                    // Send button
                    Button {
                        let generator = UIImpactFeedbackGenerator(style: .medium)
                        generator.impactOccurred()
                        var finalAttachment = attachment
                        finalAttachment.isViewOnce = isViewOnce
                        finalAttachment.revealDuration = revealDuration
                        onSend(finalAttachment)
                        dismiss()
                    } label: {
                        HStack {
                            if isViewOnce {
                                Image(systemName: "eye.slash.circle.fill")
                                    .accessibilityHidden(true)
                            }
                            Text(isViewOnce ? "media_picker.view_once".localized : "common.send".localized)
                                .fontWeight(.semibold)
                        }
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .padding(.vertical, 14)
                        .background(isViewOnce ? Color.orange : Color.blue)
                        .foregroundColor(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                    .accessibilityHint("media_picker.send_hint".localized)
                    .padding(.horizontal)
                    .padding(.bottom, 16)
                }
                .animation(.easeInOut(duration: 0.2), value: isViewOnce)
                .animation(.easeInOut(duration: 0.2), value: revealDuration)
                // Ceinture ET bretelles. `@State` repart normalement à neuf à
                // chaque présentation de la feuille — mais `isViewOnce` est un
                // `@Binding` sur un état du PARENT, que le parent doit remettre à
                // faux à la main (il le fait). Poser les deux ici rend l'envoi
                // suivant propre même si un jour ce n'est plus le cas.
                .onAppear { options = .none; isViewOnce = false }
                // Keep the toggle/send controls centered on iPad/landscape.
                .frame(maxWidth: 600)
                .frame(maxWidth: .infinity)
            }
            .navigationTitle(attachment.type == .video ? "media.view_once.video".localized : "media.view_once.photo".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) {
                        onCancel()
                        dismiss()
                    }
                }
            }
        }
    }
}

// MARK: - Camera View

struct CameraView: UIViewControllerRepresentable {
    let onImageCaptured: (UIImage) -> Void
    @Environment(\.dismiss) var dismiss
    
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }
    
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraView
        
        init(_ parent: CameraView) {
            self.parent = parent
        }
        
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]) {
            if let image = info[.originalImage] as? UIImage {
                parent.onImageCaptured(image)
            }
            parent.dismiss()
        }
        
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}

// MARK: - Video Recorder View

struct VideoRecorderView: UIViewControllerRepresentable {
    let onVideoRecorded: (URL) -> Void
    @Environment(\.dismiss) var dismiss
    
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = ["public.movie"]
        // Keep every iOS entry point consistent with the direct composer note.
        picker.videoMaximumDuration = 30
        picker.delegate = context.coordinator
        return picker
    }
    
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: VideoRecorderView
        
        init(_ parent: VideoRecorderView) {
            self.parent = parent
        }
        
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]) {
            if let url = info[.mediaURL] as? URL {
                parent.onVideoRecorded(url)
            }
            parent.dismiss()
        }
        
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}

// MARK: - Picked movie (file, not bytes)

/// __CHAT_VIDEO_BITRATE_2026_10_09__ Lets `PhotosPicker` hand a chosen video
/// over as a FILE instead of a `Data`.
///
/// `loadTransferable(type: Data.self)` on a 4K clip allocates the whole clip in
/// memory; the caller then wrote those same bytes back out to disk because the
/// encoder needs a URL. This representation skips both: PhotosUI writes the
/// file, we copy it inside the file system, and nothing is held in RAM.
///
/// The URL PhotosUI provides is valid only for the duration of the `importing`
/// closure, so the copy is mandatory — and it lands in `MediaPlaintextTemp`, in
/// the same protection class and under the same sweep as every other plaintext
/// working copy (__PLAINTEXT_LEFTOVERS_2026_09_24__). The caller removes it.
struct PickedMovie: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let ext = received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension
            let destination = MediaPlaintextTemp.scratchURL(ext: ext)
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.copyItem(at: received.file, to: destination)
            // `copyItem` carries the SOURCE's attributes across, so the
            // destination does not necessarily inherit the protection class of
            // the directory it landed in. Set it explicitly.
            try? FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                ofItemAtPath: destination.path)
            return PickedMovie(url: destination)
        }
    }
}
