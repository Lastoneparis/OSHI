//
//  MediaPickerView.swift
//  COMPLETE FIX: Light mode visibility + No syntax errors
//

import SwiftUI
import PhotosUI
import AVFoundation

struct MediaPickerView: View {
    @Environment(\.dismiss) var dismiss
    @Environment(\.colorScheme) var colorScheme  // ✅ Add for adaptive colors
    @StateObject private var mediaManager = MediaManager()
    
    let onMediaSelected: (MediaManager.MediaAttachment) -> Void
    
    @State private var selectedItem: PhotosPickerItem?
    @State private var showCamera = false
    @State private var showVideoPicker = false
    @State private var errorMessage: String?
    @State private var showError = false
    
    // ✅ Adaptive colors
    private var primaryTextColor: Color {
        colorScheme == .dark ? .white : .black
    }
    
    private var sectionHeaderColor: Color {
        colorScheme == .dark ? .gray : .secondary
    }
    
    var body: some View {
        NavigationView {
            List {
                Section {
                    Button {
                        showCamera = true
                    } label: {
                        Label("Take Photo", systemImage: "camera.fill")
                            .foregroundColor(primaryTextColor)  // ✅ Adaptive
                    }
                    
                    Button {
                        showVideoPicker = true
                    } label: {
                        Label("Record Video", systemImage: "video.fill")
                            .foregroundColor(primaryTextColor)  // ✅ Adaptive
                    }
                } header: {
                    Text("CAPTURE")
                        .foregroundColor(sectionHeaderColor)
                }
                
                Section {
                    PhotosPicker(selection: $selectedItem, matching: .images) {
                        Label("Choose Photo", systemImage: "photo.on.rectangle")
                            .foregroundColor(primaryTextColor)  // ✅ Adaptive
                    }
                    
                    PhotosPicker(selection: $selectedItem, matching: .videos) {
                        Label("Choose Video", systemImage: "video.badge.plus")
                            .foregroundColor(primaryTextColor)  // ✅ Adaptive
                    }
                } header: {
                    Text("LIBRARY")
                        .foregroundColor(sectionHeaderColor)
                }
                
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Image(systemName: "info.circle")
                                .foregroundColor(.blue)
                            Text("Media Limits")
                                .font(.subheadline)
                                .fontWeight(.semibold)
                                .foregroundColor(primaryTextColor)  // ✅ Adaptive
                        }
                        
                        Text("• Maximum file size: 10 MB")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        
                        Text("• Videos: Up to 60 seconds")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        
                        Text("• Files are compressed and encrypted")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .padding(.vertical, 4)
                } header: {
                    Text("INFORMATION")
                        .foregroundColor(sectionHeaderColor)
                }
            }
            .navigationTitle("Add Media")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Cancel") {
                        dismiss()
                    }
                    .foregroundColor(primaryTextColor)  // ✅ Adaptive
                }
            }
            .sheet(isPresented: $showCamera) {
                CameraView(onCapture: handleImage)
            }
            .sheet(isPresented: $showVideoPicker) {
                VideoCaptureView(onCapture: handleVideo)
            }
            .onChange(of: selectedItem) { _, newItem in
                Task {
                    await handlePhotosPickerSelection(newItem)
                }
            }
            .alert("Error", isPresented: $showError) {
                Button("OK") {
                    showError = false
                }
            } message: {
                if let errorMessage {
                    Text(errorMessage)
                }
            }
        }
    }
    
    private func handlePhotosPickerSelection(_ item: PhotosPickerItem?) async {
        guard let item = item else { return }
        
        do {
            if let imageData = try await item.loadTransferable(type: Data.self) {
                if let image = UIImage(data: imageData) {
                    handleImage(image)
                } else {
                    errorMessage = "Failed to load image"
                    showError = true
                }
            }
        } catch {
            errorMessage = "Error loading media: \(error.localizedDescription)"
            showError = true
        }
    }
    
    private func handleImage(_ image: UIImage) {
        Task {
            do {
                let attachment = try await mediaManager.processImage(image)
                
                await MainActor.run {
                    onMediaSelected(attachment)
                    dismiss()
                }
            } catch {
                await MainActor.run {
                    errorMessage = "Failed to process image: \(error.localizedDescription)"
                    showError = true
                }
            }
        }
    }
    
    private func handleVideo(_ url: URL) {
        Task {
            do {
                // ✅ FIX: Include 'at:' parameter label
                let attachment = try await mediaManager.processVideo(at: url)
                
                await MainActor.run {
                    onMediaSelected(attachment)
                    dismiss()
                }
            } catch {
                await MainActor.run {
                    errorMessage = "Failed to process video: \(error.localizedDescription)"
                    showError = true
                }
            }
        }
    }
}

struct ProcessingOverlay: View {
    let progress: Double
    
    var body: some View {
        ZStack {
            Color.black.opacity(0.4)
                .ignoresSafeArea()
            
            VStack(spacing: 20) {
                ProgressView()
                    .scaleEffect(1.5)
                    .tint(.white)
                
                VStack(spacing: 8) {
                    Text("Processing Media...")
                        .font(.headline)
                        .foregroundColor(.white)
                    
                    if progress > 0 {
                        Text("\(Int(progress * 100))% compressed")
                            .font(.subheadline)
                            .foregroundColor(.white.opacity(0.8))
                    }
                }
            }
            .padding(30)
            .background(Color.black.opacity(0.7))
            .cornerRadius(15)
        }
    }
}

// MARK: - Camera View

struct CameraView: UIViewControllerRepresentable {
    let onCapture: (UIImage) -> Void
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
                parent.onCapture(image)
            }
            parent.dismiss()
        }
        
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}

// MARK: - Video Capture View

struct VideoCaptureView: UIViewControllerRepresentable {
    let onCapture: (URL) -> Void
    @Environment(\.dismiss) var dismiss
    
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = ["public.movie"]
        picker.videoMaximumDuration = 60
        picker.videoQuality = .typeMedium
        picker.delegate = context.coordinator
        
        // ✅ Check camera permission
        if AVCaptureDevice.authorizationStatus(for: .video) != .authorized {
            print("⚠️ Camera permission not granted")
        }
        
        return picker
    }
    
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: VideoCaptureView
        
        init(_ parent: VideoCaptureView) {
            self.parent = parent
        }
        
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]) {
            // ✅ Safely unwrap URL
            guard let url = info[.mediaURL] as? URL else {
                print("❌ Failed to get video URL")
                parent.dismiss()
                return
            }
            
            print("✅ Video captured: \(url.lastPathComponent)")
            parent.onCapture(url)
            parent.dismiss()
        }
        
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}

// MARK: - Transferable Movie Type

struct Movie: Transferable {
    let url: URL
    
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let copy = URL.documentsDirectory.appending(path: "movie.mp4")
            
            if FileManager.default.fileExists(atPath: copy.path()) {
                try FileManager.default.removeItem(at: copy)
            }
            
            // ✅ Correct syntax with 'at:' labels
            try FileManager.default.copyItem(at: received.file, to: copy)
            return Self(url: copy)
        }
    }
}

#Preview {
    MediaPickerView { _ in }
}
