import SwiftUI
import UniformTypeIdentifiers

struct DocumentPicker: UIViewControllerRepresentable {
    let onSelect: (URL) -> Void
    
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        // ✅ CRITICAL FIX: Use specific content types for better security-scoped access
        let picker = UIDocumentPickerViewController(
            forOpeningContentTypes: [.pdf, .image, .plainText, .data, .audio, .movie],
            asCopy: true
        )
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = false
        picker.shouldShowFileExtensions = true
        return picker
    }
    
    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}
    
    func makeCoordinator() -> Coordinator {
        Coordinator(onSelect: onSelect)
    }
    
    class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onSelect: (URL) -> Void
        
        init(onSelect: @escaping (URL) -> Void) {
            self.onSelect = onSelect
        }
        
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else {
                OshiLog.storage.info("❌ No URL selected")
                return
            }
            
            OshiLog.storage.info("📄 Document picked: \(url.lastPathComponent)")
            OshiLog.storage.info("   Path: \(url.path)")
            OshiLog.storage.info("   Is file URL: \(url.isFileURL)")
            
            // ✅ Try multiple approaches to access the file
            
            // Approach 1: Direct access (works for local files)
            if FileManager.default.fileExists(atPath: url.path) {
                OshiLog.storage.info("✅ File exists at path, using direct access")
                do {
                    let data = try Data(contentsOf: url)
                    OshiLog.storage.info("✅ File loaded directly: \(data.count) bytes")
                    
                    // __PLAINTEXT_LEFTOVERS_2026_09_24__ Copie protégée, supprimée par
                    // `MediaManager.processDocument` dès qu'elle est lue (avant:
                    // `tmp/<nom>`, sans protection, jamais supprimée).
                    let destinationURL = try MediaPlaintextTemp.write(data, fileName: url.lastPathComponent)
                    OshiLog.storage.info("✅ File written to temp: \(destinationURL.path)")
                    
                    DispatchQueue.main.async {
                        self.onSelect(destinationURL)
                    }
                    return
                } catch {
                    OshiLog.storage.info("⚠️ Direct access failed: \(error), trying security-scoped approach")
                }
            }
            
            // Approach 2: Security-scoped resource (for iCloud/external)
            OshiLog.storage.info("🔓 Requesting security-scoped access...")
            let accessGranted = url.startAccessingSecurityScopedResource()
            OshiLog.storage.info("   Access granted: \(accessGranted)")
            
            if !accessGranted {
                OshiLog.storage.info("❌ Failed to access security-scoped resource")
                // Try to use URL anyway as last resort
                DispatchQueue.main.async {
                    self.onSelect(url)
                }
                return
            }
            
            defer {
                url.stopAccessingSecurityScopedResource()
                OshiLog.storage.info("🔓 Released security-scoped resource")
            }
            
            do {
                // Load data within security scope
                OshiLog.storage.info("📥 Loading file data...")
                let data = try Data(contentsOf: url)
                OshiLog.storage.info("✅ File loaded into memory: \(data.count) bytes")
                
                // Write to temp directory
                // __PLAINTEXT_LEFTOVERS_2026_09_24__ idem: copie protégée, consommée une fois.
                let destinationURL = try MediaPlaintextTemp.write(data, fileName: url.lastPathComponent)
                OshiLog.storage.info("✅ File written to temp: \(destinationURL.path)")
                OshiLog.storage.info("   Final size: \(data.count) bytes")
                
                // Verify file was written
                if FileManager.default.fileExists(atPath: destinationURL.path) {
                    OshiLog.storage.info("✅ Verified: File exists at destination")
                    DispatchQueue.main.async {
                        self.onSelect(destinationURL)
                    }
                } else {
                    OshiLog.storage.info("❌ ERROR: File not found at destination!")
                    throw NSError(domain: "DocumentPicker", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to write file"])
                }
            } catch {
                OshiLog.storage.info("❌ Failed to process document: \(error.localizedDescription)")
                OshiLog.storage.info("   Error details: \(error)")
                
                // Last resort: try to use original URL
                OshiLog.storage.info("⚠️ Attempting to use original URL as fallback")
                DispatchQueue.main.async {
                    self.onSelect(url)
                }
            }
        }
        
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            OshiLog.storage.info("ℹ️ Document picker cancelled by user")
        }
    }
}

struct DocumentExportPicker: UIViewControllerRepresentable {
    let fileURL: URL
    let onComplete: () -> Void
    
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forExporting: [fileURL], asCopy: true)
        picker.delegate = context.coordinator
        return picker
    }
    
    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}
    
    func makeCoordinator() -> Coordinator {
        Coordinator(onComplete: onComplete)
    }
    
    class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onComplete: () -> Void
        
        init(onComplete: @escaping () -> Void) {
            self.onComplete = onComplete
        }
        
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            OshiLog.storage.info("✅ File exported successfully to: \(urls.first?.lastPathComponent ?? "unknown")")
            onComplete()
        }
        
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            OshiLog.storage.info("❌ Document export cancelled")
            onComplete()
        }
    }
}

// Helper view for exporting files
struct FileExportView: View {
    let fileURL: URL
    @State private var showingExporter = false
    
    var body: some View {
        Button(action: {
            showingExporter = true
        }) {
            Label("save_to_files".localized(), systemImage: "folder.badge.plus")
        }
        .sheet(isPresented: $showingExporter) {
            DocumentExportPicker(fileURL: fileURL) {
                showingExporter = false
            }
        }
    }
}
