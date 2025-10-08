//
//  MediaManager.swift
//  FIXED: Proper concurrency handling for compression progress
//

import Foundation
import SwiftUI
import PhotosUI
import AVFoundation
import CryptoKit

class MediaManager: ObservableObject {
    @Published var isProcessing = false
    @Published var compressionProgress: Double = 0.0
    
    private let maxFileSize: Int = 10 * 1024 * 1024 // 10 MB
    private let imageQuality: CGFloat = 0.7
    
    enum MediaType: String, Codable {
        case image
        case video
    }
    
    struct MediaAttachment: Codable, Identifiable {
        let id: String
        let type: MediaType
        let data: Data
        let thumbnailData: Data?
        let fileName: String
        let fileSize: Int
        let timestamp: Date
        
        var fileSizeFormatted: String {
            ByteCountFormatter.string(fromByteCount: Int64(fileSize), countStyle: .file)
        }
    }
    
    // MARK: - Image Processing
    
    func processImage(_ image: UIImage) async throws -> MediaAttachment {
        await setProcessing(true)
        defer { Task { await setProcessing(false) } }
        
        return try await Task.detached(priority: .userInitiated) { [weak self] in
            guard let self = self else { throw MediaError.processingFailed }
            
            // Compress image
            guard let compressedData = await self.compressImage(image, maxSize: self.maxFileSize) else {
                throw MediaError.compressionFailed
            }
            
            // Generate thumbnail
            let thumbnailData = await self.generateThumbnail(from: image)
            
            let attachment = MediaAttachment(
                id: UUID().uuidString,
                type: .image,
                data: compressedData,
                thumbnailData: thumbnailData,
                fileName: "image_\(Date().timeIntervalSince1970).jpg",
                fileSize: compressedData.count,
                timestamp: Date()
            )
            
            print("✅ Image processed: \(attachment.fileSizeFormatted)")
            return attachment
        }.value
    }
    
    private func setProcessing(_ value: Bool) async {
        await MainActor.run {
            self.isProcessing = value
        }
    }
    
    private func compressImage(_ image: UIImage, maxSize: Int) async -> Data? {
        var currentCompression: CGFloat = imageQuality
        var imageData = image.jpegData(compressionQuality: currentCompression)
        
        // Iteratively reduce quality if needed
        while let data = imageData, data.count > maxSize && currentCompression > 0.1 {
            currentCompression -= 0.1
            
            // Capture value before await
            let progressValue = Double(1.0 - currentCompression)
            await MainActor.run {
                self.compressionProgress = progressValue
            }
            
            imageData = image.jpegData(compressionQuality: currentCompression)
        }
        
        // If still too large, resize
        if let data = imageData, data.count > maxSize {
            let scaleFactor = sqrt(Double(maxSize) / Double(data.count))
            let newSize = CGSize(
                width: image.size.width * scaleFactor,
                height: image.size.height * scaleFactor
            )
            
            let resizedImage = await resizeImage(image, to: newSize)
            imageData = resizedImage.jpegData(compressionQuality: 0.8)
        }
        
        await MainActor.run {
            self.compressionProgress = 0.0
        }
        
        return imageData
    }
    
    private func resizeImage(_ image: UIImage, to size: CGSize) async -> UIImage {
        await Task.detached {
            let renderer = UIGraphicsImageRenderer(size: size)
            return renderer.image { _ in
                image.draw(in: CGRect(origin: .zero, size: size))
            }
        }.value
    }
    
    private func generateThumbnail(from image: UIImage) async -> Data? {
        let thumbnailSize = CGSize(width: 100, height: 100)
        let thumbnail = await resizeImage(image, to: thumbnailSize)
        return thumbnail.jpegData(compressionQuality: 0.5)
    }
    
    // MARK: - Video Processing
    
    func processVideo(at url: URL) async throws -> MediaAttachment {
        await setProcessing(true)
        defer { Task { await setProcessing(false) } }
        
        let asset = AVAsset(url: url)
        
        // Check duration (max 60 seconds for 10MB limit)
        let duration = try await asset.load(.duration)
        let durationSeconds = CMTimeGetSeconds(duration)
        
        guard durationSeconds <= 60 else {
            throw MediaError.videoTooLong
        }
        
        // Compress video
        let compressedURL = try await compressVideo(url)
        let videoData = try Data(contentsOf: compressedURL)
        
        // Check size
        guard videoData.count <= maxFileSize else {
            try? FileManager.default.removeItem(at: compressedURL)
            throw MediaError.fileTooLarge
        }
        
        // Generate thumbnail
        let thumbnailData = try await generateVideoThumbnail(from: url)
        
        let attachment = MediaAttachment(
            id: UUID().uuidString,
            type: .video,
            data: videoData,
            thumbnailData: thumbnailData,
            fileName: "video_\(Date().timeIntervalSince1970).mp4",
            fileSize: videoData.count,
            timestamp: Date()
        )
        
        // Cleanup
        try? FileManager.default.removeItem(at: compressedURL)
        
        print("✅ Video processed: \(attachment.fileSizeFormatted)")
        return attachment
    }
    
    private func compressVideo(_ url: URL) async throws -> URL {
        let outputURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("mp4")
        
        guard let exportSession = AVAssetExportSession(
            asset: AVAsset(url: url),
            presetName: AVAssetExportPresetMediumQuality
        ) else {
            throw MediaError.compressionFailed
        }
        
        exportSession.outputURL = outputURL
        exportSession.outputFileType = .mp4
        
        await exportSession.export()
        
        guard exportSession.status == .completed else {
            throw MediaError.compressionFailed
        }
        
        return outputURL
    }
    
    private func generateVideoThumbnail(from url: URL) async throws -> Data? {
        let asset = AVAsset(url: url)
        let imageGenerator = AVAssetImageGenerator(asset: asset)
        imageGenerator.appliesPreferredTrackTransform = true
        
        let time = CMTime(seconds: 1, preferredTimescale: 60)
        
        do {
            let cgImage = try imageGenerator.copyCGImage(at: time, actualTime: nil)
            let thumbnail = UIImage(cgImage: cgImage)
            let resizedThumbnail = await resizeImage(thumbnail, to: CGSize(width: 100, height: 100))
            return resizedThumbnail.jpegData(compressionQuality: 0.5)
        } catch {
            print("⚠️ Failed to generate video thumbnail: \(error)")
            return nil
        }
    }
    
    // MARK: - Encryption
    
    func encryptMedia(_ attachment: MediaAttachment, with key: SymmetricKey) throws -> Data {
        let encoder = JSONEncoder()
        let attachmentData = try encoder.encode(attachment)
        
        let sealedBox = try AES.GCM.seal(attachmentData, using: key)
        guard let combined = sealedBox.combined else {
            throw MediaError.encryptionFailed
        }
        
        return combined
    }
    
    func decryptMedia(_ data: Data, with key: SymmetricKey) throws -> MediaAttachment {
        let sealedBox = try AES.GCM.SealedBox(combined: data)
        let decryptedData = try AES.GCM.open(sealedBox, using: key)
        
        let decoder = JSONDecoder()
        return try decoder.decode(MediaAttachment.self, from: decryptedData)
    }
    
    // MARK: - Storage
    
    func saveAttachment(_ attachment: MediaAttachment) throws {
        let encoder = JSONEncoder()
        let data = try encoder.encode(attachment)
        
        let fileURL = getAttachmentURL(for: attachment.id)
        try data.write(to: fileURL)
        
        print("💾 Saved attachment: \(attachment.fileName)")
    }
    
    func loadAttachment(id: String) throws -> MediaAttachment {
        let fileURL = getAttachmentURL(for: id)
        let data = try Data(contentsOf: fileURL)
        
        let decoder = JSONDecoder()
        return try decoder.decode(MediaAttachment.self, from: data)
    }
    
    func deleteAttachment(id: String) throws {
        let fileURL = getAttachmentURL(for: id)
        try FileManager.default.removeItem(at: fileURL)
    }
    
    private func getAttachmentURL(for id: String) -> URL {
        let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let attachmentsPath = documentsPath.appendingPathComponent("Attachments", isDirectory: true)
        
        // Create directory if needed
        try? FileManager.default.createDirectory(at: attachmentsPath, withIntermediateDirectories: true)
        
        return attachmentsPath.appendingPathComponent("\(id).dat")
    }
}

enum MediaError: LocalizedError {
    case processingFailed
    case compressionFailed
    case encryptionFailed
    case fileTooLarge
    case videoTooLong
    case invalidMedia
    
    var errorDescription: String? {
        switch self {
        case .processingFailed:
            return "Failed to process media file"
        case .compressionFailed:
            return "Failed to compress media"
        case .encryptionFailed:
            return "Failed to encrypt media"
        case .fileTooLarge:
            return "File size exceeds 10MB limit"
        case .videoTooLong:
            return "Video duration exceeds 60 seconds"
        case .invalidMedia:
            return "Invalid media file"
        }
    }
}
