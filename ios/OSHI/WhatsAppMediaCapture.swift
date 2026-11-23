//
//  WhatsAppMediaCapture.swift
//  WhatsApp-style camera capture with view-once support
//

import SwiftUI
import PhotosUI
import AVFoundation
import AVKit

// MARK: - WhatsApp Style Media Capture Sheet

struct WhatsAppMediaCaptureSheet: View {
    @Environment(\.dismiss) var dismiss
    let onSend: (MediaManager.MediaAttachment, String) -> Void  // ✅ Now includes caption
    /// The composer can deliberately enter camera or short-video mode. Keeping this
    /// as an initializer value avoids a second mode-switch interaction for a note.
    let initialCameraMode: CameraMode
    
    @State private var capturedImage: UIImage?
    @State private var capturedVideoURL: URL?
    @State private var isViewOnce = false
    @State private var showCamera = true
    @State private var cameraMode: CameraMode = .photo
    @State private var captionText = ""  // ✅ Caption support
    
    enum CameraMode {
        case photo
        case video
    }

    init(initialCameraMode: CameraMode = .photo,
         onSend: @escaping (MediaManager.MediaAttachment, String) -> Void) {
        self.initialCameraMode = initialCameraMode
        self.onSend = onSend
        _cameraMode = State(initialValue: initialCameraMode)
    }
    
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            
            if showCamera {
                // Camera view
                WhatsAppCameraView(
                    cameraMode: $cameraMode,
                    onPhotoCaptured: { image in
                        capturedImage = image
                        showCamera = false
                    },
                    onVideoCaptured: { url in
                        capturedVideoURL = url
                        showCamera = false
                    },
                    onCancel: {
                        dismiss()
                    }
                )
            } else {
                // Preview with view-once option and caption
                MediaPreviewWithViewOnce(
                    image: capturedImage,
                    videoURL: capturedVideoURL,
                    isViewOnce: $isViewOnce,
                    captionText: $captionText,
                    showViewOnceOption: true,  // ✅ Show view-once for 1:1 chats
                    onSend: {
                        sendMedia()
                    },
                    onRetake: {
                        capturedImage = nil
                        if let u = capturedVideoURL { try? FileManager.default.removeItem(at: u) }  // __PLAINTEXT_LEFTOVERS_2026_09_24__
                        capturedVideoURL = nil
                        captionText = ""
                        showCamera = true
                    },
                    onCancel: {
                        dismiss()
                    }
                )
            }
        }
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ La vidéo brute de la caméra (tmp
        // d'UIImagePickerController) ne survit pas à la feuille: envoyée (octets
        // déjà en mémoire) ou annulée, elle est supprimée.
        .onDisappear {
            if let u = capturedVideoURL { try? FileManager.default.removeItem(at: u) }
        }
    }
    
    private func sendMedia() {
        if let image = capturedImage {
            // 🔧 SPEED FIX: same 1600 px / ~200 KB share budget the picker uses
            // (MediaPickerView.shareMaxDimension + encodeToShareBudget). The old
            // 1920 px / 2 MB ceiling was ~10× too generous for a chat photo, and
            // every source byte is amplified ~5.6× by the nested-base64 envelope —
            // a 1.8 MB capture became a 10 MB upload. One helper, one budget.
            let maxDim = MediaPickerView.shareMaxDimension
            let longestSide = max(image.size.width, image.size.height)
            let workingImage: UIImage
            if longestSide > maxDim {
                let scale = maxDim / longestSide
                let newSize = CGSize(
                    width: round(image.size.width * scale),
                    height: round(image.size.height * scale)
                )
                let renderer = UIGraphicsImageRenderer(size: newSize)
                workingImage = renderer.image { _ in
                    image.draw(in: CGRect(origin: .zero, size: newSize))
                }
                OshiLog.storage.info("📐 Resized from \(Int(image.size.width))x\(Int(image.size.height)) → \(Int(newSize.width))x\(Int(newSize.height))")
            } else {
                workingImage = image
            }

            guard let finalData = MediaPickerView.encodeToShareBudget(workingImage) else { return }

            let attachment = MediaManager.MediaAttachment(
                id: UUID().uuidString,
                type: .image,
                data: finalData,
                thumbnailData: nil,
                fileName: "photo_\(Date().timeIntervalSince1970).jpg",
                fileSize: finalData.count,
                timestamp: Date(),
                originalSize: nil,
                compressionRatio: nil,
                isCompressed: true,
                isViewOnce: isViewOnce
            )
            onSend(attachment, captionText)
            dismiss()
        } else if let videoURL = capturedVideoURL, let data = try? Data(contentsOf: videoURL) {
            let thumbnail = generateVideoThumbnail(from: videoURL)
            let attachment = MediaManager.MediaAttachment(
                id: UUID().uuidString,
                type: .video,
                data: data,
                thumbnailData: thumbnail?.jpegData(compressionQuality: 0.5),
                fileName: videoURL.lastPathComponent,
                fileSize: data.count,
                timestamp: Date(),
                originalSize: nil,
                compressionRatio: nil,
                isCompressed: false,
                isViewOnce: isViewOnce
            )
            onSend(attachment, captionText)  // ✅ Include caption
            dismiss()
        }
    }
    
    private func generateVideoThumbnail(from url: URL) -> UIImage? {
        let asset = AVAsset(url: url)
        let imageGenerator = AVAssetImageGenerator(asset: asset)
        imageGenerator.appliesPreferredTrackTransform = true
        
        do {
            let time = CMTime(seconds: 0.5, preferredTimescale: 60)
            let cgImage = try imageGenerator.copyCGImage(at: time, actualTime: nil)
            return UIImage(cgImage: cgImage)
        } catch {
            return nil
        }
    }
}

// MARK: - WhatsApp Camera View

struct WhatsAppCameraView: UIViewControllerRepresentable {
    @Binding var cameraMode: WhatsAppMediaCaptureSheet.CameraMode
    let onPhotoCaptured: (UIImage) -> Void
    let onVideoCaptured: (URL) -> Void
    let onCancel: () -> Void
    
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        picker.mediaTypes = ["public.image", "public.movie"]
        // A video note is a quick message, not a long-form upload. This bounds
        // compression, encrypted blob size and accidental mobile-data use.
        picker.videoMaximumDuration = 30
        picker.cameraCaptureMode = cameraMode == .photo ? .photo : .video
        picker.cameraFlashMode = .auto
        return picker
    }
    
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: WhatsAppCameraView
        
        init(_ parent: WhatsAppCameraView) {
            self.parent = parent
        }
        
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]) {
            if let image = info[.originalImage] as? UIImage {
                // ✅ Fix image orientation before passing
                let fixedImage = fixImageOrientation(image)
                parent.onPhotoCaptured(fixedImage)
            } else if let videoURL = info[.mediaURL] as? URL {
                parent.onVideoCaptured(videoURL)
            }
        }
        
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.onCancel()
        }
        
        // Fix image orientation for camera photos
        private func fixImageOrientation(_ image: UIImage) -> UIImage {
            if image.imageOrientation == .up {
                return image
            }
            // Use modern renderer instead of deprecated UIGraphicsBeginImageContextWithOptions
            let renderer = UIGraphicsImageRenderer(size: image.size, format: {
                let fmt = UIGraphicsImageRendererFormat()
                fmt.scale = image.scale
                return fmt
            }())
            return renderer.image { _ in
                image.draw(in: CGRect(origin: .zero, size: image.size))
            }
        }
    }
}

// MARK: - Media Preview with View Once Toggle (WhatsApp style)

struct MediaPreviewWithViewOnce: View {
    let image: UIImage?
    let videoURL: URL?
    @Binding var isViewOnce: Bool
    @Binding var captionText: String  // ✅ Caption support
    let showViewOnceOption: Bool      // ✅ Hide for groups
    let onSend: () -> Void
    let onRetake: () -> Void
    let onCancel: () -> Void
    
    @State private var player: AVPlayer?
    @FocusState private var isCaptionFocused: Bool
    @Environment(\.verticalSizeClass) var verticalSizeClass
    @Environment(\.horizontalSizeClass) var horizontalSizeClass
    
    // ✅ Detect if we're in landscape
    private var isLandscape: Bool {
        verticalSizeClass == .compact
    }
    
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Color.black.ignoresSafeArea()
                
                if isLandscape {
                    // ✅ Landscape layout - side by side
                    HStack(spacing: 0) {
                        // Media preview on the left
                        mediaPreviewView
                            .frame(width: geometry.size.width * 0.65)
                        
                        // Controls on the right
                        VStack(spacing: 16) {
                            // Top buttons
                            HStack {
                                Button(action: onCancel) {
                                    Image(systemName: "xmark")
                                        .font(.title2)
                                        .foregroundColor(.white)
                                        .padding()
                                }
                                .accessibilityLabel("common.cancel".localized)

                                Spacer()

                                Button(action: onRetake) {
                                    Text("chat.retake".localized)
                                        .foregroundColor(.white)
                                        .padding()
                                }
                                .accessibilityHint("chat.retake_hint".localized)
                            }
                            
                            Spacer()
                            
                            // Caption field
                            captionField
                            
                            // View once toggle
                            if showViewOnceOption {
                                viewOnceToggle
                            }
                            
                            // Send button
                            sendButton
                            
                            Spacer()
                        }
                        .frame(width: geometry.size.width * 0.35)
                        .padding()
                    }
                } else {
                    // ✅ Portrait layout - stacked
                    VStack(spacing: 0) {
                        // Top bar
                        HStack {
                            Button(action: onCancel) {
                                Image(systemName: "xmark")
                                    .font(.title2)
                                    .foregroundColor(.white)
                                    .padding()
                            }
                            .accessibilityLabel("common.cancel".localized)

                            Spacer()

                            Button(action: onRetake) {
                                Text("chat.retake".localized)
                                    .foregroundColor(.white)
                                    .padding()
                            }
                            .accessibilityHint("chat.retake_hint".localized)
                        }
                        
                        Spacer()
                        
                        // Media preview
                        mediaPreviewView
                        
                        Spacer()
                        
                        // Bottom controls
                        VStack(spacing: 16) {
                            captionField

                            if showViewOnceOption {
                                viewOnceToggle
                            }

                            sendButton
                        }
                        // Keep caption/controls centered on iPad/landscape.
                        .frame(maxWidth: 700)
                        .frame(maxWidth: .infinity)
                        .padding(.bottom, 30)
                    }
                }
            }
        }
        .onTapGesture {
            isCaptionFocused = false
        }
    }
    
    // MARK: - Subviews
    
    @ViewBuilder
    private var mediaPreviewView: some View {
        if let image = image {
            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityLabel("media.view_once.photo".localized)
        } else if let videoURL = videoURL {
            CaptureVideoPreviewPlayer(url: videoURL)
                .accessibilityLabel("media.view_once.video".localized)
        } else {
            Color.clear
        }
    }
    
    private var captionField: some View {
        HStack(spacing: 12) {
            TextField("chat.add_caption".localized, text: $captionText, axis: .vertical)
                .privateTypingComposer()  // __KEYBOARD_PRIVACY_2026_10_01__ no autocorrect learning when Private typing is on
                .textFieldStyle(.plain)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Color.white.opacity(0.2))
                .cornerRadius(20)
                .foregroundColor(.white)
                .lineLimit(1...4)
                .focused($isCaptionFocused)
                .accessibilityLabel("chat.add_caption".localized)
        }
        .padding(.horizontal, 16)
    }
    
    private var viewOnceToggle: some View {
        Button {
            isViewOnce.toggle()
            let generator = UIImpactFeedbackGenerator(style: .medium)
            generator.impactOccurred()
        } label: {
            HStack(spacing: 8) {
                ZStack {
                    Circle()
                        .fill(isViewOnce ? Color.green : Color.white.opacity(0.3))
                        .frame(width: 32, height: 32)

                    Text("1")
                        .font(.scaled(14, weight: .bold))
                        .foregroundColor(isViewOnce ? .white : .white.opacity(0.7))
                }

                Text(isViewOnce ? "media_picker.view_once.enabled".localized : "media_picker.view_once".localized)
                    .font(.subheadline)
                    .foregroundColor(.white)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(minHeight: 44)
            .background(
                Capsule()
                    .fill(Color.white.opacity(0.2))
            )
        }
        .animation(.easeInOut(duration: 0.2), value: isViewOnce)
        .accessibilityLabel("media_picker.view_once".localized)
        .accessibilityValue(isViewOnce ? "common.on".localized : "common.off".localized)
        .accessibilityHint("media_picker.view_once.description".localized)
    }
    
    private var sendButton: some View {
        HStack {
            Spacer()

            Button {
                let generator = UIImpactFeedbackGenerator(style: .medium)
                generator.impactOccurred()
                onSend()
            } label: {
                ZStack {
                    Circle()
                        .fill(Color.green)
                        .frame(width: 60, height: 60)

                    Image(systemName: "paperplane.fill")
                        .font(.title2)
                        .foregroundColor(.white)
                        .offset(x: -2)
                }
            }
            .accessibilityLabel("common.send".localized)
            .accessibilityHint("media_picker.send_hint".localized)
            .padding(.trailing, 20)
        }
    }
}

// MARK: - Capture Video Preview Player (renamed to avoid conflict)

struct CaptureVideoPreviewPlayer: View {
    let url: URL
    @State private var player: AVPlayer?
    
    var body: some View {
        ZStack {
            if let player = player {
                CaptureAVPlayerView(player: player)
                    .onAppear {
                        player.play()
                    }
            } else {
                ProgressView()
                    .tint(.white)
            }
        }
        .onAppear {
            player = AVPlayer(url: url)
            player?.actionAtItemEnd = .none
            
            // Loop video
            NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: player?.currentItem,
                queue: .main
            ) { _ in
                player?.seek(to: .zero)
                player?.play()
            }
        }
    }
}

// MARK: - Capture AV Player View (renamed to avoid conflict)

struct CaptureAVPlayerView: UIViewControllerRepresentable {
    let player: AVPlayer
    
    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.showsPlaybackControls = true
        controller.videoGravity = .resizeAspect
        return controller
    }
    
    func updateUIViewController(_ uiViewController: AVPlayerViewController, context: Context) {}
}

// MARK: - Quick Media Action Sheet (Camera + Gallery + Documents)

struct QuickMediaActionSheet: View {
    @Environment(\.dismiss) var dismiss
    let onCameraSelected: () -> Void
    let onGallerySelected: () -> Void
    let onDocumentSelected: () -> Void
    
    var body: some View {
        VStack(spacing: 0) {
            // Handle bar
            RoundedRectangle(cornerRadius: 3)
                .fill(Color.gray.opacity(0.5))
                .frame(width: 40, height: 5)
                .padding(.top, 10)
                .padding(.bottom, 20)
            
            // Options
            HStack(spacing: 40) {
                // Camera
                VStack(spacing: 8) {
                    Button {
                        let generator = UIImpactFeedbackGenerator(style: .light)
                        generator.impactOccurred()
                        dismiss()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                            onCameraSelected()
                        }
                    } label: {
                        ZStack {
                            Circle()
                                .fill(Color.pink.gradient)
                                .frame(width: 60, height: 60)

                            Image(systemName: "camera.fill")
                                .font(.title2)
                                .foregroundColor(.white)
                        }
                    }

                    Text("chat.camera".localized)
                        .font(.caption)
                        .foregroundColor(.primary)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("chat.camera".localized)
                .accessibilityAddTraits(.isButton)

                // Gallery
                VStack(spacing: 8) {
                    Button {
                        let generator = UIImpactFeedbackGenerator(style: .light)
                        generator.impactOccurred()
                        dismiss()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                            onGallerySelected()
                        }
                    } label: {
                        ZStack {
                            Circle()
                                .fill(Color.purple.gradient)
                                .frame(width: 60, height: 60)

                            Image(systemName: "photo.fill")
                                .font(.title2)
                                .foregroundColor(.white)
                        }
                    }

                    Text("chat.gallery".localized)
                        .font(.caption)
                        .foregroundColor(.primary)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("chat.gallery".localized)
                .accessibilityAddTraits(.isButton)

                // Document
                VStack(spacing: 8) {
                    Button {
                        let generator = UIImpactFeedbackGenerator(style: .light)
                        generator.impactOccurred()
                        dismiss()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                            onDocumentSelected()
                        }
                    } label: {
                        ZStack {
                            Circle()
                                .fill(Color.indigo.gradient)
                                .frame(width: 60, height: 60)

                            Image(systemName: "doc.fill")
                                .font(.title2)
                                .foregroundColor(.white)
                        }
                    }

                    Text("chat.document".localized)
                        .font(.caption)
                        .foregroundColor(.primary)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("chat.document".localized)
                .accessibilityAddTraits(.isButton)
            }
            .padding(.bottom, 30)
        }
        .frame(maxWidth: .infinity)
        .background(Color(.systemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

// MARK: - Group Media Capture Sheet (No View-Once)

struct GroupMediaCaptureSheet: View {
    @Environment(\.dismiss) var dismiss
    let onSend: (MediaManager.MediaAttachment, String) -> Void  // ✅ Includes caption
    
    @State private var capturedImage: UIImage?
    @State private var capturedVideoURL: URL?
    @State private var showCamera = true
    @State private var cameraMode: WhatsAppMediaCaptureSheet.CameraMode = .photo
    @State private var captionText = ""
    @State private var isViewOnce = false  // Always false for groups
    
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            
            if showCamera {
                WhatsAppCameraView(
                    cameraMode: $cameraMode,
                    onPhotoCaptured: { image in
                        capturedImage = image
                        showCamera = false
                    },
                    onVideoCaptured: { url in
                        capturedVideoURL = url
                        showCamera = false
                    },
                    onCancel: {
                        dismiss()
                    }
                )
            } else {
                // Preview WITHOUT view-once option for groups
                MediaPreviewWithViewOnce(
                    image: capturedImage,
                    videoURL: capturedVideoURL,
                    isViewOnce: $isViewOnce,
                    captionText: $captionText,
                    showViewOnceOption: false,  // ✅ No view-once for groups
                    onSend: {
                        sendMedia()
                    },
                    onRetake: {
                        capturedImage = nil
                        if let u = capturedVideoURL { try? FileManager.default.removeItem(at: u) }  // __PLAINTEXT_LEFTOVERS_2026_09_24__
                        capturedVideoURL = nil
                        captionText = ""
                        showCamera = true
                    },
                    onCancel: {
                        dismiss()
                    }
                )
            }
        }
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ La vidéo brute de la caméra (tmp
        // d'UIImagePickerController) ne survit pas à la feuille: envoyée (octets
        // déjà en mémoire) ou annulée, elle est supprimée.
        .onDisappear {
            if let u = capturedVideoURL { try? FileManager.default.removeItem(at: u) }
        }
    }
    
    private func sendMedia() {
        if let image = capturedImage {
            // Same share budget as the picker + the other capture flow above — see
            // MediaPickerView.encodeToShareBudget for why the ceiling is 1600 px /
            // ~200 KB rather than 1920 px / 2 MB.
            let maxDim = MediaPickerView.shareMaxDimension
            let longestSide = max(image.size.width, image.size.height)
            let workingImage: UIImage
            if longestSide > maxDim {
                let scale = maxDim / longestSide
                let newSize = CGSize(
                    width: round(image.size.width * scale),
                    height: round(image.size.height * scale)
                )
                let renderer = UIGraphicsImageRenderer(size: newSize)
                workingImage = renderer.image { _ in
                    image.draw(in: CGRect(origin: .zero, size: newSize))
                }
            } else {
                workingImage = image
            }

            guard let finalData = MediaPickerView.encodeToShareBudget(workingImage) else { return }

            let attachment = MediaManager.MediaAttachment(
                id: UUID().uuidString,
                type: .image,
                data: finalData,
                thumbnailData: nil,
                fileName: "photo_\(Date().timeIntervalSince1970).jpg",
                fileSize: finalData.count,
                timestamp: Date(),
                originalSize: nil,
                compressionRatio: nil,
                isCompressed: true,
                isViewOnce: false
            )
            onSend(attachment, captionText)
            dismiss()
        } else if let videoURL = capturedVideoURL, let data = try? Data(contentsOf: videoURL) {
            let thumbnail = generateVideoThumbnail(from: videoURL)
            let attachment = MediaManager.MediaAttachment(
                id: UUID().uuidString,
                type: .video,
                data: data,
                thumbnailData: thumbnail?.jpegData(compressionQuality: 0.5),
                fileName: videoURL.lastPathComponent,
                fileSize: data.count,
                timestamp: Date(),
                originalSize: nil,
                compressionRatio: nil,
                isCompressed: false,
                isViewOnce: false
            )
            onSend(attachment, captionText)
            dismiss()
        }
    }
    
    private func generateVideoThumbnail(from url: URL) -> UIImage? {
        let asset = AVAsset(url: url)
        let imageGenerator = AVAssetImageGenerator(asset: asset)
        imageGenerator.appliesPreferredTrackTransform = true
        
        do {
            let time = CMTime(seconds: 0.5, preferredTimescale: 60)
            let cgImage = try imageGenerator.copyCGImage(at: time, actualTime: nil)
            return UIImage(cgImage: cgImage)
        } catch {
            return nil
        }
    }
}
