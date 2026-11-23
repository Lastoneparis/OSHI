//
//  MediaManager.swift
//  ENHANCED: Intelligent compression + 50MB IPFS support + Better quality
//

import Foundation
import SwiftUI
import PhotosUI
@preconcurrency import AVFoundation
import CryptoKit
import ImageIO

class MediaManager: ObservableObject {
    @Published var isProcessing = false
    @Published var compressionProgress: Double = 0.0
    @Published var uploadProgress: Double = 0.0
    
    // ✅ SMART LIMITS: Different for mesh vs IPFS
    private let meshMaxFileSize: Int = 10 * 1024 * 1024      // 10 MB for mesh network
    // Cloud ceiling raised 50 → 200 MB (2026-07-19). The V2 blob path now
    // streams-encrypts to disk and uploads chunk-by-chunk (V2MessageRouter.sendFile
    // → uploadBlobFromDisk), so the "whole-file Data → ~2× RAM jetsam" risk that
    // pinned this at 50 MB is gone. Legacy IPFS sends above the 50 MB nginx wall
    // still go through the RESUMABLE chunked path (ResumableUploader +
    // ServerVPS/resumable_upload.js, 300 MB server cap). This gates attachment
    // BUILD size (getMaxSize), so it must be ≥ the V2 user-facing cap for a
    // 200 MB file to become a sendable attachment at all.
    private let ipfsMaxFileSize: Int = 200 * 1024 * 1024      // 200 MB cloud ceiling
    
    // ✅ IMPROVED QUALITY: Higher quality with better compression
    private let imageQuality: CGFloat = 0.85                  // 0.85 = high quality, good compression
    private let thumbnailQuality: CGFloat = 0.6               // Thumbnails can be lower quality
    
    // ✅ INTELLIGENT COMPRESSION: Only compress if needed
    private let compressionThreshold: Int = 5 * 1024 * 1024  // Start compressing above 5MB

    // MARK: - GIF budgets
    //
    // __GIF_BUDGET_2026_09_12__ Every other media kind here can be made smaller
    // by re-encoding it. A GIF cannot: the only lever is picking a SMALLER
    // RENDITION at the source (Tenor serves `gif` / `tinygif` / `nanogif` of the
    // same clip), because re-encoding through `CGImageSourceCreateThumbnailAtIndex`
    // — which is what the rest of this file does to images — throws away every
    // frame but the first. A "compressed" GIF is a still picture.
    //
    // So the budget has to be enforced at CHOICE time, not at compression time,
    // and these two numbers are what the picker chooses against.
    //
    // 5 MB preferred: the mesh path takes anything under 10 MB and the IPFS/cloud
    // path takes the rest. The cloud path is not just slower, it is a different
    // trust story (the blob leaves the device). A reaction GIF is the least
    // important payload in the app and has no business on the slow path, so we
    // aim well under the line rather than at it.
    //
    // 8 MB hard: the raw bytes are not the whole envelope — the legacy media path
    // base64s the attachment inside JSON, which is ×1.33. 8 MB of GIF is ~10.7 MB
    // on the wire and already over the mesh ceiling; anything beyond that is
    // refused outright rather than silently promoted to the cloud path.
    static let gifPreferredMaxBytes: Int = 5 * 1024 * 1024
    static let gifHardMaxBytes: Int = 8 * 1024 * 1024

    enum MediaType: String, Codable {
        case image
        case video
        case audio
        case document    // ✅ NEW: PDF, TXT, DOCX, etc.
        case contact     // ✅ NEW: Public key contact card

        // __GIF_2026_09_12__ A GIF is not "an image with a different extension":
        // it animates, it is never re-encoded on send (re-encoding is exactly
        // what flattens it), and the gallery wants to filter it apart. Hence its
        // own case rather than smuggling it inside `.image`.
        //
        // ⚠️ THIS CASE IS WIRE-VISIBLE AND THAT IS THE DANGEROUS PART. Read the
        // comment on `SecureMessage.init(from:)` (`__TOLERANT_MEDIA_TYPE_2026_09_12__`)
        // before sending it anywhere: a peer on an older build decodes an
        // unrecognised `mediaType` raw value to **nil**, and nil `mediaType` is
        // the one thing `MediaMessageView.loadMedia()` refuses to render — the
        // bubble comes up empty. It no longer destroys the whole message store
        // (that was the fix), but "GIF arrives as a blank bubble on every client
        // that hasn't updated" is still a bad send.
        //
        // So: the case exists for LOCAL use (gallery filtering, badges, picking
        // an extension), and `MediaManager.gifWireType` decides what actually
        // goes on the wire. See that constant. Rendering must never depend on
        // this case being present — sniff the bytes with `isGIFData` instead,
        // which works on every build, every platform, and when the field is nil.
        case gif

        /// Anything a still-image decoder can open. The point of the property is
        /// the `.gif` line: every existing `case .image` branch that only needs
        /// "can I put this in an image view" should read this instead of adding
        /// a second branch, so a GIF degrades to its first frame everywhere it
        /// has not been taught to animate.
        var isImageLike: Bool {
            self == .image || self == .gif
        }

        /// Whether the payload animates. Only a hint from the label — the
        /// authority is `MediaManager.isGIFData(_:)`, because the label can be
        /// nil (older peer) while the bytes are still a perfectly good GIF.
        var isAnimated: Bool { self == .gif }

        /// Single source of truth for the MIME label, so the four scattered
        /// `switch type { case .image: return "image/jpeg" }` tables can each
        /// gain GIF support with one line instead of a new guess.
        var mimeType: String {
            switch self {
            case .image:    return "image/jpeg"
            case .gif:      return "image/gif"
            case .video:    return "video/mp4"
            case .audio:    return "audio/m4a"
            case .contact:  return "text/vcard"
            case .document: return "application/octet-stream"
            }
        }

        /// Extension used when a message carries no `mediaFileName` (very common
        /// for anything that came off the wire).
        var defaultFileExtension: String {
            switch self {
            case .image:    return "jpg"
            case .gif:      return "gif"
            case .video:    return "mp4"
            case .audio:    return "m4a"
            case .contact:  return "vcf"
            case .document: return "dat"
            }
        }
    }

    // MARK: - GIF wire compatibility

    /// __GIF_WIRE_TYPE_2026_09_12__ What `mediaType` a GIF is actually stamped
    /// with when it leaves this device.
    ///
    /// `.gif`, with a documented escape hatch — and the escape hatch is the
    /// reason this is a constant instead of a literal at the send site.
    ///
    /// What `.gif` buys, on a peer running this build or newer: the V2 blob is
    /// labelled `image/gif` (labelled `image/jpeg`, some receivers re-encode and
    /// the animation is gone), the export filename keeps its extension, the
    /// gallery can filter GIFs apart, and the bubble knows to animate without
    /// touching the bytes.
    ///
    /// 🔴 **What it costs, and it is not nothing.** The fleet is not on this
    /// build. Older iOS clients, and the Android client (`MediaType.valueOf()`
    /// in a try/catch — verified, and its enum has IMAGE/VIDEO/AUDIO/DOCUMENT/
    /// LOCATION/CONTACT and no GIF), decode `"gif"` to **nil**. That no longer
    /// destroys their whole message store — `__TOLERANT_MEDIA_TYPE_2026_09_12__`
    /// in `SecureMessage.init(from:)` fixed that, and it is the only reason this
    /// change is shippable at all — but a nil `mediaType` is exactly what
    /// `MediaMessageView.loadMedia()` bails on, before it ever looks at the
    /// bytes. **On those clients a GIF arrives as an empty bubble.**
    ///
    /// The fix on the receiving side is one line and it is already written:
    /// `MediaManager.inferredType(from:fileName:)` recovers the kind from the
    /// `GIF8` header when the label is nil. Once that is in each client's
    /// `loadMedia()`, this stops being a trade-off. Until then it is one, and
    /// the mitigation is that the FIRST FRAME still decodes everywhere: any
    /// client that renders a nil type as image data shows a still picture rather
    /// than nothing.
    ///
    /// Flip this to `.image` to buy interop at the cost of the metadata above —
    /// the bytes are identical either way, and animation on this build is driven
    /// by sniffing (`isGIFData`), never by this label. One knob, one line, no
    /// other code to revisit. That is the point.
    static let gifWireType: MediaType = .gif

    /// True when the bytes are a GIF, whatever the message claims to carry.
    ///
    /// This is the ONLY reliable GIF test in the app. `mediaType` can be nil
    /// (unknown raw value from a newer/other-platform peer), `mediaFileName` can
    /// be absent or lying, but the first six bytes of a GIF are always
    /// `GIF87a` or `GIF89a`. Sniffing is also what makes a GIF sent by a build
    /// that stamps `.gif` still animate on a build that decoded that label to nil.
    static func isGIFData(_ data: Data) -> Bool {
        guard data.count >= 6 else { return false }
        // Compare on a COPY of the prefix: `data` may be a slice with a non-zero
        // startIndex (it very often is, coming out of a JSON decode), and
        // indexing a slice from 0 traps.
        let head = [UInt8](data.prefix(6))
        return head == Array("GIF87a".utf8) || head == Array("GIF89a".utf8)
    }

    /// Best guess at what a payload is when the message did not say — i.e. when
    /// an older or newer peer's `mediaType` degraded to nil in
    /// `SecureMessage.init(from:)`.
    ///
    /// Call site intended for the `guard let mediaType = message.mediaType`
    /// in `MediaMessageView.loadMedia()`: `message.mediaType ?? MediaManager
    /// .inferredType(from: data, fileName: message.mediaFileName)`. Without it,
    /// a nil label means the bubble renders nothing at all — the media is right
    /// there in memory and we throw it away over a missing string.
    static func inferredType(from data: Data, fileName: String?) -> MediaType? {
        if isGIFData(data) { return .gif }

        // Magic bytes first, filename only as a tiebreak: the name is attacker-
        // and peer-controlled, the bytes are not.
        let head = [UInt8](data.prefix(12))
        if head.starts(with: [0xFF, 0xD8, 0xFF]) { return .image }                       // JPEG
        if head.starts(with: [0x89, 0x50, 0x4E, 0x47]) { return .image }                 // PNG
        if head.count >= 12, Array(head[4..<8]) == Array("ftyp".utf8) {
            // ISO base media: HEIC is a still, MP4/MOV is a video. `ftypheic`,
            // `ftypmif1`, `ftypheix` are the still brands we actually receive.
            let brand = String(decoding: head[8..<12], as: UTF8.self)
            return ["heic", "heix", "mif1", "msf1", "avif"].contains(brand) ? .image : .video
        }
        if head.starts(with: Array("RIFF".utf8)) { return .image }                       // WebP
        if head.starts(with: [0x25, 0x50, 0x44, 0x46]) { return .document }              // %PDF

        switch (fileName as NSString?)?.pathExtension.lowercased() {
        case "gif":                       return .gif
        case "jpg", "jpeg", "png", "heic", "webp": return .image
        case "mp4", "mov", "m4v":         return .video
        case "m4a", "caf", "aac", "mp3":  return .audio
        case "vcf", "vcard":              return .contact
        case .some(let ext) where !ext.isEmpty: return .document
        default:                          return nil
        }
    }


    enum DeliveryMethod {
        case mesh      // Direct P2P, max 10MB
        case ipfs      // IPFS storage, max 200MB
        case auto      // Automatically choose based on size
    }
    
    struct MediaAttachment: Codable, Identifiable {
        let id: String
        let type: MediaType
        let data: Data
        let thumbnailData: Data?
        let fileName: String
        let fileSize: Int
        let timestamp: Date
        let originalSize: Int?           // ✅ Track original size before compression
        let compressionRatio: Double?    // ✅ How much we compressed
        let isCompressed: Bool           // ✅ Was compression applied?
        var isViewOnce: Bool = false     // ✅ NEW: Ephemeral media - view once then delete

        /// __REVEAL_OPTION_2026_08_22__ Durée de révélation progressive choisie à
        /// l'envoi, `nil` = aucune. EXCLUSIVE avec `isViewOnce` — l'écran d'aperçu
        /// décoche l'une quand on coche l'autre, parce que les deux ensemble
        /// n'auraient pas de sens: une photo qui met vingt minutes à devenir nette
        /// et disparaît à la première ouverture ne serait jamais vue.
        ///
        /// Non persistée sur le fil pour l'instant: elle est portée jusqu'à l'envoi.
        /// Le transport jusqu'au pair est l'étape suivante — voir le plan.
        var revealDuration: TimeInterval? = nil
        
        // Custom decoder for backward compatibility
        enum CodingKeys: String, CodingKey {
            case id, type, data, thumbnailData, fileName, fileSize, timestamp
            case originalSize, compressionRatio, isCompressed, isViewOnce
        }
        
        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            id = try container.decode(String.self, forKey: .id)
            type = try container.decode(MediaType.self, forKey: .type)
            data = try container.decode(Data.self, forKey: .data)
            thumbnailData = try container.decodeIfPresent(Data.self, forKey: .thumbnailData)
            fileName = try container.decode(String.self, forKey: .fileName)
            fileSize = try container.decode(Int.self, forKey: .fileSize)
            timestamp = try container.decode(Date.self, forKey: .timestamp)
            originalSize = try container.decodeIfPresent(Int.self, forKey: .originalSize)
            compressionRatio = try container.decodeIfPresent(Double.self, forKey: .compressionRatio)
            isCompressed = try container.decodeIfPresent(Bool.self, forKey: .isCompressed) ?? false
            isViewOnce = try container.decodeIfPresent(Bool.self, forKey: .isViewOnce) ?? false
        }
        
        // Standard initializer
        init(
            id: String,
            type: MediaType,
            data: Data,
            thumbnailData: Data?,
            fileName: String,
            fileSize: Int,
            timestamp: Date,
            originalSize: Int?,
            compressionRatio: Double?,
            isCompressed: Bool,
            isViewOnce: Bool = false
        ) {
            self.id = id
            self.type = type
            self.data = data
            self.thumbnailData = thumbnailData
            self.fileName = fileName
            self.fileSize = fileSize
            self.timestamp = timestamp
            self.originalSize = originalSize
            self.compressionRatio = compressionRatio
            self.isCompressed = isCompressed
            self.isViewOnce = isViewOnce
        }
        
        var fileSizeFormatted: String {
            ByteCountFormatter.string(fromByteCount: Int64(fileSize), countStyle: .file)
        }

        /// Whether this bubble should ANIMATE. Sniffs the bytes rather than
        /// trusting `type`, because `type` is the one thing that does not
        /// survive the trip from a peer this build has never met: an unknown
        /// `mediaType` raw value is degraded to nil in `SecureMessage.init(from:)`,
        /// and by the time we get here it has become whatever the caller
        /// defaulted to. The `GIF8` header survives everything.
        var isAnimatedGIF: Bool {
            MediaManager.isGIFData(data)
        }

        /// Le type à RENDRE, qui n'est pas toujours le type annoncé.
        ///
        /// Un GIF envoyé par un pair Android sur la maille locale arrive
        /// étiqueté `image`: son chemin d'envoi LAN lit l'énumération au lieu de
        /// renifler les octets, alors que son chemin internet, lui, renifle.
        /// Rendu par l'étiquette, ce GIF s'affiche figé sur sa première image et
        /// s'exporte en `.jpg`.
        ///
        /// On ne CONVERTIT rien ici: les octets sont intacts des deux côtés, il
        /// n'y a que l'étiquette qui ment. L'en-tête `GIF8`, lui, survit à tout —
        /// c'est la seule chose fiable une fois qu'un message a traversé un pair
        /// que cette version n'a jamais rencontré.
        ///
        /// Corriger ici plutôt qu'au point de réception protège de la même
        /// erreur commise par N'IMPORTE quel client, présent ou à venir.
        var renderType: MediaType {
            (type == .image && isAnimatedGIF) ? .gif : type
        }
        
        var originalSizeFormatted: String? {
            guard let original = originalSize else { return nil }
            return ByteCountFormatter.string(fromByteCount: Int64(original), countStyle: .file)
        }
        
        var compressionInfo: String? {
            guard let ratio = compressionRatio, isCompressed else { return nil }
            return String(format: "Compressed by %.1f%%", (1 - ratio) * 100)
        }
    }
    
    // MARK: - Safe Image Size Constants

    /// Maximum pixel dimension (longest side) for outgoing images.
    /// A 1920px image at 80% JPEG quality is typically 300-500KB.
    private let maxImageDimension: CGFloat = 1920

    // MARK: - Image Processing

    /// Process image with intelligent compression
    /// - Parameters:
    ///   - image: UIImage to process
    ///   - deliveryMethod: How the image will be sent (mesh/IPFS/auto)
    /// - Returns: Processed MediaAttachment
    func processImage(_ image: UIImage, deliveryMethod: DeliveryMethod = .auto) async throws -> MediaAttachment {
        await setProcessing(true)
        defer { Task { await setProcessing(false) } }

        return try await Task.detached(priority: .userInitiated) { [weak self] in
            guard let self = self else { throw MediaError.processingFailed }

            // Step 1: Downscale to max 1920px on longest side BEFORE getting JPEG data.
            // This prevents a 4000x3000 image from inflating to ~48MB in memory.
            let downsampledImage: UIImage = autoreleasepool {
                let longestSide = max(image.size.width, image.size.height)
                if longestSide > self.maxImageDimension {
                    let scale = self.maxImageDimension / longestSide
                    let newSize = CGSize(
                        width: round(image.size.width * scale),
                        height: round(image.size.height * scale)
                    )
                    OshiLog.storage.info("📐 Downscaling from \(Int(image.size.width))x\(Int(image.size.height)) → \(Int(newSize.width))x\(Int(newSize.height))")
                    let renderer = UIGraphicsImageRenderer(size: newSize)
                    return renderer.image { _ in
                        image.draw(in: CGRect(origin: .zero, size: newSize))
                    }
                }
                return image
            }

            // Step 2: Get JPEG data from the (possibly downscaled) image
            guard let originalData = autoreleasepool(invoking: {
                downsampledImage.jpegData(compressionQuality: 1.0)
            }) else {
                throw MediaError.processingFailed
            }
            let originalSize = originalData.count

            OshiLog.storage.info("📸 Image size after downscale: \(self.formatBytes(originalSize))")

            // Determine max size based on delivery method
            let maxSize = self.getMaxSize(for: deliveryMethod, currentSize: originalSize)

            // Smart compression: only if needed
            let finalData: Data
            let isCompressed: Bool
            let compressionRatio: Double

            if originalSize > maxSize {
                // Need to compress
                OshiLog.storage.info("🔄 Compressing image (target: \(self.formatBytes(maxSize)))")
                finalData = try await self.compressImageIntelligently(downsampledImage, maxSize: maxSize, originalSize: originalSize)
                isCompressed = true
                compressionRatio = Double(finalData.count) / Double(originalSize)
                OshiLog.storage.info("✅ Compressed: \(self.formatBytes(originalSize)) → \(self.formatBytes(finalData.count)) (\(String(format: "%.1f%%", (1 - compressionRatio) * 100)) reduction)")
            } else if originalSize > self.compressionThreshold {
                // Optimize quality without aggressive compression
                OshiLog.storage.info("🎨 Optimizing quality...")
                guard let optimizedData = autoreleasepool(invoking: {
                    downsampledImage.jpegData(compressionQuality: self.imageQuality)
                }) else {
                    throw MediaError.compressionFailed
                }

                if optimizedData.count < originalSize {
                    finalData = optimizedData
                    isCompressed = true
                    compressionRatio = Double(optimizedData.count) / Double(originalSize)
                    OshiLog.storage.info("✅ Optimized: \(self.formatBytes(originalSize)) → \(self.formatBytes(optimizedData.count))")
                } else {
                    // Optimization didn't help, use original
                    finalData = originalData
                    isCompressed = false
                    compressionRatio = 1.0
                    OshiLog.storage.info("✅ Using original (already optimal)")
                }
            } else {
                // Small enough, use original
                finalData = originalData
                isCompressed = false
                compressionRatio = 1.0
                OshiLog.storage.info("✅ Using original (small size)")
            }

            // Generate thumbnail from the already-downscaled image (safe)
            let thumbnailData = await self.generateThumbnail(from: downsampledImage)

            let attachment = MediaAttachment(
                id: UUID().uuidString,
                type: .image,
                data: finalData,
                thumbnailData: thumbnailData,
                fileName: "image_\(Date().timeIntervalSince1970).jpg",
                fileSize: finalData.count,
                timestamp: Date(),
                originalSize: originalSize,
                compressionRatio: compressionRatio,
                isCompressed: isCompressed
            )

            return attachment
        }.value
    }
    
    // MARK: - GIF Processing

    /// Wrap already-encoded GIF bytes as a sendable attachment.
    ///
    /// Deliberately the shortest processing path in this file, and deliberately
    /// NOT `processImage`:
    ///
    ///  * **The bytes are never touched.** `processImage` downsamples, re-encodes
    ///    to JPEG and picks a quality. Run a GIF through any of that and it comes
    ///    out as one still frame — that is the whole bug this function exists to
    ///    avoid. The animation IS the file; the only correct transform is none.
    ///  * **The size check is a refusal, not a compression loop.** See
    ///    `__GIF_BUDGET_2026_09_12__`. There is no quality knob to turn, so an
    ///    over-budget GIF is rejected here and the caller is expected to have
    ///    picked a smaller rendition upstream (`TenorGif.bestFormat`).
    ///
    /// The thumbnail is a **static JPEG of frame 0**. It costs ~10 KB and it is
    /// what a peer that cannot animate (older build, Watch, notification
    /// extension, the gallery grid) shows instead of nothing.
    ///
    /// - Parameters:
    ///   - data: raw GIF bytes, exactly as downloaded.
    ///   - fileName: kept `.gif` — some receivers decide by extension alone.
    ///   - source: free-form provenance for the log ("tenor", "share sheet"…).
    func processGIF(_ data: Data, fileName: String? = nil, source: String = "unknown") async throws -> MediaAttachment {
        await setProcessing(true)
        defer { Task { await setProcessing(false) } }

        guard MediaManager.isGIFData(data) else {
            // A file called .gif that isn't one would animate as nothing and,
            // stamped image/gif, would confuse every receiver. Refuse early.
            OshiLog.storage.error("❌ GIF rejected: bytes are not GIF87a/GIF89a (source: \(source))")
            throw MediaError.invalidMedia
        }

        guard data.count <= MediaManager.gifHardMaxBytes else {
            OshiLog.storage.error("❌ GIF too large: \(formatBytes(data.count)) > \(formatBytes(MediaManager.gifHardMaxBytes)) (source: \(source))")
            throw MediaError.fileTooLarge
        }

        if data.count > MediaManager.gifPreferredMaxBytes {
            // Not fatal, but it means the picker failed to find a small enough
            // rendition and this send is about to take the cloud path.
            OshiLog.storage.warning("⚠️ GIF over the mesh-friendly budget: \(formatBytes(data.count)) (source: \(source))")
        }

        let poster = MediaManager.gifPosterFrame(from: data)
        let name = fileName ?? "gif_\(Int(Date().timeIntervalSince1970)).gif"

        OshiLog.storage.info("🎞️ GIF ready: \(name) \(formatBytes(data.count)) — bytes untouched (source: \(source))")

        return MediaAttachment(
            id: UUID().uuidString,
            type: MediaManager.gifWireType,   // see __GIF_WIRE_TYPE_2026_09_12__
            data: data,
            thumbnailData: poster,
            fileName: name,
            fileSize: data.count,
            timestamp: Date(),
            originalSize: data.count,
            compressionRatio: nil,   // nothing was compressed, and saying 1.0 would print "Compressed by 0.0%"
            isCompressed: false
        )
    }

    /// First frame of a GIF as a small JPEG, for anything that shows a still.
    ///
    /// `CGImageSourceCreateThumbnailAtIndex` at index 0 is exactly the call that
    /// RUINS a GIF when it is used as the send path — here it is the right tool,
    /// because a poster frame is all we want.
    static func gifPosterFrame(from data: Data, maxPixelSize: CGFloat = 400) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            return nil
        }
        return UIImage(cgImage: cgImage).jpegData(compressionQuality: 0.6)
    }

    /// Pixel size of a GIF without decoding a single frame — the grid needs the
    /// aspect ratio before the bytes finish arriving, and decoding a 500-frame
    /// GIF to measure it would be absurd.
    static func gifPixelSize(from data: Data) -> CGSize? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? CGFloat,
              let h = props[kCGImagePropertyPixelHeight] as? CGFloat,
              w > 0, h > 0 else { return nil }
        return CGSize(width: w, height: h)
    }

    /// Intelligently compress image to target size with minimal quality loss
    private func compressImageIntelligently(_ image: UIImage, maxSize: Int, originalSize: Int) async throws -> Data {
        var currentImage = image
        var currentSize = originalSize
        var currentQuality = imageQuality
        var resizeSteps = 0
        let maxResizeSteps = 5
        
        // Strategy: Try quality reduction first, then resize if needed
        
        // Phase 1: Quality reduction (faster, less quality loss)
        while currentSize > maxSize && currentQuality > 0.3 {
            currentQuality -= 0.1
            
            await updateProgress(1.0 - currentQuality)
            
            guard let compressedData = currentImage.jpegData(compressionQuality: currentQuality) else {
                throw MediaError.compressionFailed
            }
            
            currentSize = compressedData.count
            
            if currentSize <= maxSize {
                OshiLog.storage.info("✅ Reached target with quality reduction (quality: \(String(format: "%.1f", currentQuality)))")
                return compressedData
            }
        }
        
        // Phase 2: Resize if quality reduction wasn't enough
        OshiLog.storage.info("🔄 Quality reduction insufficient, starting resize...")
        
        while currentSize > maxSize && resizeSteps < maxResizeSteps {
            // Calculate scale factor (reduce by 20% each step)
            let scaleFactor: CGFloat = 0.8
            let newSize = CGSize(
                width: currentImage.size.width * scaleFactor,
                height: currentImage.size.height * scaleFactor
            )
            
            OshiLog.storage.info("📐 Resizing to \(Int(newSize.width))x\(Int(newSize.height))")
            
            currentImage = await self.resizeImage(currentImage, to: newSize)
            resizeSteps += 1
            
            await updateProgress(0.5 + (Double(resizeSteps) / Double(maxResizeSteps)) * 0.5)
            
            // Try with better quality after resize
            guard let resizedData = currentImage.jpegData(compressionQuality: 0.85) else {
                throw MediaError.compressionFailed
            }
            
            currentSize = resizedData.count
            
            if currentSize <= maxSize {
                OshiLog.storage.info("✅ Reached target with resize (steps: \(resizeSteps))")
                return resizedData
            }
        }
        
        // Phase 3: Final aggressive compression if still too large
        if currentSize > maxSize {
            OshiLog.storage.info("⚠️ Still too large, applying aggressive compression...")
            var finalQuality: CGFloat = 0.5
            
            while currentSize > maxSize && finalQuality > 0.1 {
                finalQuality -= 0.1
                
                guard let finalData = currentImage.jpegData(compressionQuality: finalQuality) else {
                    throw MediaError.compressionFailed
                }
                
                currentSize = finalData.count
                
                if currentSize <= maxSize {
                    OshiLog.storage.info("✅ Reached target with aggressive compression (quality: \(String(format: "%.1f", finalQuality)))")
                    return finalData
                }
            }
        }
        
        // If we still can't reach target, return best effort
        guard let finalData = currentImage.jpegData(compressionQuality: 0.1) else {
            throw MediaError.compressionFailed
        }
        
        if finalData.count > maxSize {
            OshiLog.storage.info("⚠️ Warning: Could not reach target size. Final: \(formatBytes(finalData.count))")
        }
        
        return finalData
    }
    
    private func resizeImage(_ image: UIImage, to size: CGSize) async -> UIImage {
        await Task.detached {
            autoreleasepool {
                let renderer = UIGraphicsImageRenderer(size: size)
                return renderer.image { _ in
                    image.draw(in: CGRect(origin: .zero, size: size))
                }
            }
        }.value
    }
    
    private func generateThumbnail(from image: UIImage) async -> Data? {
        let thumbnailSize = CGSize(width: 200, height: 200)

        // Calculate aspect-fit size
        let aspectRatio = image.size.width / image.size.height
        var targetSize = thumbnailSize

        if aspectRatio > 1 {
            // Landscape
            targetSize.height = thumbnailSize.width / aspectRatio
        } else {
            // Portrait or square
            targetSize.width = thumbnailSize.height * aspectRatio
        }

        let thumbnail = await resizeImage(image, to: targetSize)
        return autoreleasepool {
            thumbnail.jpegData(compressionQuality: thumbnailQuality)
        }
    }

    // MARK: - Safe Image Loading (for receiving large images)

    /// Safely create a display-ready UIImage from raw data, downsampling if needed.
    /// Uses ImageIO to avoid loading the full bitmap into memory.
    static func safeUIImage(from data: Data, maxDimension: CGFloat = 1920) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            return nil
        }

        // Read pixel dimensions without decoding the full image
        guard let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let pixelWidth = properties[kCGImagePropertyPixelWidth] as? CGFloat,
              let pixelHeight = properties[kCGImagePropertyPixelHeight] as? CGFloat else {
            // Fallback: just decode normally (small image or unknown format)
            return UIImage(data: data)
        }

        let longestSide = max(pixelWidth, pixelHeight)

        if longestSide <= maxDimension {
            // Image is small enough, decode normally
            return UIImage(data: data)
        }

        // Downsample using ImageIO (memory-efficient, does not decode full bitmap)
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxDimension,
            kCGImageSourceShouldCacheImmediately: true
        ]

        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            // Fallback
            return UIImage(data: data)
        }

        return UIImage(cgImage: cgImage)
    }
    
    // MARK: - Video Processing
    
    /// Process video with intelligent compression
    func processVideo(at url: URL, deliveryMethod: DeliveryMethod = .auto) async throws -> MediaAttachment {
        await setProcessing(true)
        defer { Task { await setProcessing(false) } }
        
        let asset = AVAsset(url: url)
        
        // Get original size
        let originalSize = try await getFileSize(at: url)
        OshiLog.storage.info("🎥 Original video size: \(formatBytes(originalSize))")
        
        // Check duration
        let duration = try await asset.load(.duration)
        let durationSeconds = CMTimeGetSeconds(duration)
        
        // Determine max size
        let maxSize = getMaxSize(for: deliveryMethod, currentSize: originalSize)

        // __VIDEO_MESH_BUDGET_2026_09_18__ Viser petit n'est pas refuser gros.
        //
        // `maxSize` sert DEUX choses dans cette fonction: il oriente l'échelle de
        // préréglages, et il sert de plafond de refus plus bas. Depuis que `.auto`
        // prend le budget maillé quand un pair est joignable, confondre les deux
        // ferait ÉCHOUER un clip long dont même le dernier barreau dépasse 10 Mo —
        // alors qu'il partait très bien par le nuage la veille. L'échelle le dit
        // elle-même: « quand rien ne tient, on prend le plus petit préréglage et le
        // résultat est simplement plus gros que le budget; c'est à l'appelant de
        // décider ». Voilà la décision: on vise le budget, on ne refuse qu'au
        // plafond réel. Un appelant qui demande explicitement `.mesh` ou `.ipfs`
        // garde son plafond d'avant, au byte près.
        let refusalCeiling = deliveryMethod == .auto ? ipfsMaxFileSize : maxSize
        
        // Check if video is too long for mesh
        if deliveryMethod == .mesh && durationSeconds > 60 {
            OshiLog.storage.info("⚠️ Video too long for mesh network (\(Int(durationSeconds))s > 60s)")
            throw MediaError.videoTooLong
        }
        
        // Compress video
        let compressedURL = try await compressVideo(url, maxSize: maxSize, originalSize: originalSize)
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ supprimée sur TOUS les chemins (une
        // erreur de miniature la laissait avant dans tmp).
        defer { MediaPlaintextTemp.remove(compressedURL) }
        let fileSize = try FileManager.default.attributesOfItem(atPath: compressedURL.path)[.size] as? Int ?? 0
        guard fileSize < 200_000_000 else {   // 200 MB — V2 streaming send ceiling
            try? FileManager.default.removeItem(at: compressedURL)
            throw MediaError.fileTooLarge
        }
        let videoData = try Data(contentsOf: compressedURL)
        
        // Check final size
        guard videoData.count <= refusalCeiling else {
            try? FileManager.default.removeItem(at: compressedURL)
            throw MediaError.fileTooLarge
        }
        
        let compressionRatio = Double(videoData.count) / Double(originalSize)
        OshiLog.storage.info("✅ Video compressed: \(formatBytes(originalSize)) → \(formatBytes(videoData.count))")
        
        // Generate thumbnail
        let thumbnailData = try await generateVideoThumbnail(from: url)
        
        let attachment = MediaAttachment(
            id: UUID().uuidString,
            type: .video,
            data: videoData,
            thumbnailData: thumbnailData,
            fileName: "video_\(Date().timeIntervalSince1970).mp4",
            fileSize: videoData.count,
            timestamp: Date(),
            originalSize: originalSize,
            compressionRatio: compressionRatio,
            isCompressed: videoData.count < originalSize
        )
        
        // Cleanup
        try? FileManager.default.removeItem(at: compressedURL)
        
        return attachment
    }
    
    private func compressVideo(_ url: URL, maxSize: Int, originalSize: Int) async throws -> URL {
        let outputURL = MediaPlaintextTemp.scratchURL(ext: "mp4")  // __PLAINTEXT_LEFTOVERS_2026_09_24__
        
        // 🔧 (2026-08-08) RESOLUTION-CAPPED PRESETS, the way WhatsApp does it.
        //
        // The old ladder picked HighestQuality / MediumQuality / LowQuality by
        // target size. Two problems. `HighestQuality` preserves the SOURCE
        // resolution — a 4K clip from a recent iPhone stays 4K, which is tens of
        // megabytes for a few seconds and the reason videos felt heavy. And
        // `LowQuality` is genuinely bad: it drops to roughly 480p with heavy
        // artefacts, so the small tier looked broken rather than compact.
        //
        // Capping RESOLUTION instead of quality is what makes a chat video both
        // small and watchable: 720p is the size a phone screen actually shows,
        // and h.264 at that resolution costs a fraction of 4K while looking
        // nearly identical in a bubble. 1080p is kept for the generous tier
        // rather than "whatever the camera shot".
        // 🔧 FIX (2026-08-12, "a 100 MB video should send as a few MB"): the preset
        // was chosen from the SIZE TIER alone and the result was never checked. A
        // preset caps RESOLUTION, not bytes — 1080p for two minutes is tens of
        // megabytes however generous the tier was meant to be. So a long video
        // shipped at whatever the encoder happened to produce.
        //
        // AVFoundation can tell us the answer before we spend the encode:
        // `estimateOutputFileLength()` returns what a preset would actually
        // produce for THIS asset. Walk the presets best-first and take the first
        // one that fits the budget, instead of guessing from the tier.
        //
        // Deliberately NOT `fileLengthLimit`: that makes the export stop writing
        // when it hits the cap, which truncates the video. A shorter video is a
        // worse failure than a larger one — the user loses content and nothing
        // says so.
        // __CHAT_VIDEO_BITRATE_2026_10_09__ Try the bitrate-capped encoder FIRST.
        //
        // The preset ladder below cannot set a bitrate — a preset caps
        // resolution only — so whenever the budget was generous it picked
        // 1080p and AVFoundation encoded it at 12-15 Mbit/s: the measured
        // "17 MB for 9 seconds". `ChatVideoTranscoder` asks for 720p at
        // ~2.3 Mbit/s instead, which is the same clip at roughly 2.5-3 MB,
        // and marks the file for streaming so the receiver can start playing
        // before the whole thing has arrived.
        //
        // The ladder is kept as the fallback rather than deleted: it is the
        // path that has shipped, and an explicit reader/writer encode has more
        // ways to fail on an exotic source (a track the composition cannot
        // read, an unusual codec) than a system preset does. A send must not
        // fail because the new encoder did.
        do {
            let fast = MediaPlaintextTemp.scratchURL(ext: "mp4")
            let plan = try await ChatVideoTranscoder.transcode(source: url,
                                                              to: fast,
                                                              budgetBytes: maxSize)
            let produced = (try? FileManager.default.attributesOfItem(atPath: fast.path)[.size] as? Int) ?? 0
            // Only keep it if it actually came out smaller. Re-encoding an
            // already-compact clip (a video forwarded from another chat, a
            // screen recording) can legitimately make it bigger, and shipping
            // the larger of the two would be the very bug this fixes.
            if produced > 0 && produced < originalSize {
                OshiLog.storage.info("🎬 Chat video encode: \(plan.label) → \(ByteCountFormatter.string(fromByteCount: Int64(produced), countStyle: .file)) (was \(ByteCountFormatter.string(fromByteCount: Int64(originalSize), countStyle: .file)))")
                await MainActor.run { self.uploadProgress = 0.0 }
                return fast
            }
            OshiLog.storage.info("🎬 Chat video encode gave \(produced) B for a \(originalSize) B source — keeping the preset path")
            MediaPlaintextTemp.remove(fast)
        } catch {
            OshiLog.storage.info("⚠️ Chat video encode unavailable (\(error.localizedDescription)) — falling back to the preset ladder")
        }

        let asset = AVAsset(url: url)
        let candidates = [
            (AVAssetExportPreset1920x1080, "1080p"),
            (AVAssetExportPreset1280x720, "720p"),
            (AVAssetExportPreset960x540, "540p")
        ]

        var preset = AVAssetExportPreset960x540
        var presetLabel = "540p"
        var estimated: Int64 = -1
        for (candidate, label) in candidates {
            guard let probe = AVAssetExportSession(asset: asset, presetName: candidate) else { continue }
            // The async form is not exposed on this SDK; bridge the callback one.
            let bytes: Int64 = await withCheckedContinuation { cont in
                probe.estimateOutputFileLength { length, _ in cont.resume(returning: length) }
            }
            if bytes > 0 && bytes <= Int64(maxSize) {
                preset = candidate; presetLabel = label; estimated = bytes
                break
            }
            // Remember the smallest estimate so the log says something true even
            // when nothing fits.
            estimated = bytes
        }
        OshiLog.storage.info("🎬 Video preset: \(presetLabel) (estimated \(estimated > 0 ? ByteCountFormatter.string(fromByteCount: estimated, countStyle: .file) : "unknown"), budget \(ByteCountFormatter.string(fromByteCount: Int64(maxSize), countStyle: .file)))")

        guard let exportSession = AVAssetExportSession(
            asset: asset,
            presetName: preset
        ) else {
            throw MediaError.compressionFailed
        }
        
        exportSession.outputURL = outputURL
        exportSession.outputFileType = .mp4
        
        // Export without progress monitoring to avoid Sendable warnings
        // Progress monitoring in background tasks causes Swift 6 concurrency issues
        await exportSession.export()
        
        await MainActor.run {
            self.uploadProgress = 0.0
        }
        
        guard exportSession.status == .completed else {
            // 🔧 FIX R23: Fallback to original video without compression
            OshiLog.storage.info("⚠️ Video compression failed (status: \(exportSession.status.rawValue)), using original")
            if let error = exportSession.error {
                OshiLog.storage.info("⚠️ Export error: \(error.localizedDescription)")
            }
            // Clean up failed output
            try? FileManager.default.removeItem(at: outputURL)
            // Copy original to temp location as fallback
            let fallbackURL = MediaPlaintextTemp.scratchURL(ext: "mp4")  // __PLAINTEXT_LEFTOVERS_2026_09_24__
            try FileManager.default.copyItem(at: url, to: fallbackURL)
            let fallbackSize = try FileManager.default.attributesOfItem(atPath: fallbackURL.path)[.size] as? Int ?? 0
            guard fallbackSize < 200_000_000 else {   // 200 MB — V2 streaming send ceiling
                try? FileManager.default.removeItem(at: fallbackURL)
                throw MediaError.fileTooLarge
            }
            return fallbackURL
        }

        return outputURL
    }
    
    private func generateVideoThumbnail(from url: URL) async throws -> Data? {
        let asset = AVAsset(url: url)
        let imageGenerator = AVAssetImageGenerator(asset: asset)
        imageGenerator.appliesPreferredTrackTransform = true
        
        // Get thumbnail from 1 second in (or start if shorter)
        let duration = try await asset.load(.duration)
        let thumbnailTime = min(CMTime(seconds: 1, preferredTimescale: 60), duration)
        
        do {
            let cgImage = try imageGenerator.copyCGImage(at: thumbnailTime, actualTime: nil)
            let thumbnail = UIImage(cgImage: cgImage)
            
            // Resize and compress thumbnail
            let resizedThumbnail = await resizeImage(thumbnail, to: CGSize(width: 200, height: 200))
            return resizedThumbnail.jpegData(compressionQuality: thumbnailQuality)
        } catch {
            OshiLog.storage.info("⚠️ Failed to generate video thumbnail: \(error)")
            return nil
        }
    }

    /// In-memory cache of generated video poster frames (JPEG data), keyed by
    /// message id, so a video bubble scrolling back into view doesn't re-run
    /// AVAssetImageGenerator. Poster JPEGs are tiny (~10–30 KB). Lock-guarded
    /// because `videoPosterFrame` is nonisolated (runs off the main actor).
    nonisolated(unsafe) private static var posterFrameCache: [String: Data] = [:]
    private static let posterCacheLock = NSLock()

    /// Poster frame for a video we only hold as in-memory bytes (received
    /// messages, and re-rendered sent ones). Returns a small JPEG, or nil if a
    /// frame can't be extracted — the caller then falls back to a placeholder.
    /// This is what turns a video bubble's generic icon into a real preview + ▶︎.
    ///
    /// `nonisolated` so the AVAssetImageGenerator work runs OFF the main actor —
    /// MediaManager is @MainActor by default and `copyCGImage` is synchronous, so
    /// generating on-actor would stutter chat scrolling the first time each video
    /// bubble appears.
    nonisolated func videoPosterFrame(forMessageId id: String, videoData: Data) async -> Data? {
        MediaManager.posterCacheLock.lock()
        if let cached = MediaManager.posterFrameCache[id] {
            MediaManager.posterCacheLock.unlock()
            return cached
        }
        MediaManager.posterCacheLock.unlock()

        // AVAssetImageGenerator needs a file URL, so spill the bytes to a temp file.
        // __MEDIA_AT_REST_2026_09_22__ Copie protégée (`MediaPlaintextTemp`).
        do {
            let tmpURL = try MediaPlaintextTemp.write(videoData, fileName: "poster.mp4")
            defer { MediaPlaintextTemp.remove(tmpURL) }

            let asset = AVURLAsset(url: tmpURL)
            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            // Cap the rendered frame — the bubble only shows ~200pt, so 400px
            // covers @2x and keeps the JPEG (and decode cost) small.
            generator.maximumSize = CGSize(width: 400, height: 400)

            let duration = try await asset.load(.duration)
            let frameTime = min(CMTime(seconds: 1, preferredTimescale: 60), duration)
            let cgImage = try generator.copyCGImage(at: frameTime, actualTime: nil)
            guard let poster = UIImage(cgImage: cgImage).jpegData(compressionQuality: 0.6) else { return nil }

            MediaManager.posterCacheLock.lock()
            MediaManager.posterFrameCache[id] = poster
            MediaManager.posterCacheLock.unlock()
            return poster
        } catch {
            OshiLog.storage.info("⚠️ Failed to generate video poster from data: \(error)")
            return nil
        }
    }

    // MARK: - Audio Processing
    
    /// Process audio (from AudioRecorderManager)
    func processAudio(_ audioData: Data, fileName: String) async throws -> MediaAttachment {
        await setProcessing(true)
        defer { Task { await setProcessing(false) } }
        
        let originalSize = audioData.count
        OshiLog.storage.info("🎵 Audio size: \(formatBytes(originalSize))")
        
        // Audio is already compressed by AudioRecorderManager
        // Just verify size
        let maxSize = meshMaxFileSize // Audio always uses mesh
        
        guard audioData.count <= maxSize else {
            throw MediaError.fileTooLarge
        }
        
        let attachment = MediaAttachment(
            id: UUID().uuidString,
            type: .audio,
            data: audioData,
            thumbnailData: nil,
            fileName: fileName,
            fileSize: audioData.count,
            timestamp: Date(),
            originalSize: originalSize,
            compressionRatio: 1.0,
            isCompressed: false
        )
        
        return attachment
    }
    
    // MARK: - Delivery Method Selection
    
    private func getMaxSize(for method: DeliveryMethod, currentSize: Int) -> Int {
        switch method {
        case .mesh:
            return meshMaxFileSize
        case .ipfs:
            return ipfsMaxFileSize
        case .auto:
            // __VIDEO_MESH_BUDGET_2026_09_18__ Demander d'abord PAR OÙ ça part,
            // et seulement ensuite quelle taille ça fait.
            //
            // Cette branche ne regardait que la taille de la SOURCE, ce qui dit
            // exactement l'inverse de ce qu'il faut: « c'est déjà petit, garde-le
            // petit; c'est énorme, laisse-le énorme ». Une vidéo de 50 Mo partait
            // donc avec un budget de 200 Mo, l'échelle de préréglages choisissait
            // 1080p (`compressVideo`, plus haut), et ce fichier-là traversait
            // ensuite un lien maillé dimensionné pour 10 Mo.
            //
            // Quand un pair est effectivement joignable en maillage, c'est SON
            // budget qui décide — l'échelle descend alors en 720p ou 540p, ce qui
            // est la compression qu'on attend d'une vidéo de discussion et ce
            // dont le clip avait de toute façon besoin pour passer. Parité avec
            // Android, `MessageRepository` (branche `MediaType.VIDEO`).
            let meshReachable = !(MessageManager.sharedMeshManager?.connectedPeers.isEmpty ?? true)
            if meshReachable {
                OshiLog.storage.info("📡 Mesh peer connected — sizing for the mesh budget")
                return meshMaxFileSize
            }
            if currentSize <= meshMaxFileSize {
                OshiLog.storage.info("📡 Using mesh network (< 10MB)")
                return meshMaxFileSize
            } else {
                OshiLog.storage.info("🌐 Using IPFS (> 10MB, up to 200MB)")
                return ipfsMaxFileSize
            }
        }
    }
    
    /// Determine best delivery method for a file
    func recommendDeliveryMethod(fileSize: Int) -> DeliveryMethod {
        if fileSize <= meshMaxFileSize {
            return .mesh
        } else if fileSize <= ipfsMaxFileSize {
            return .ipfs
        } else {
            return .ipfs // Will fail, but indicates IPFS is needed
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
        try MediaCipher.write(data, to: fileURL)  // __MEDIA_AT_REST_2026_09_22__

        OshiLog.storage.info("💾 Saved attachment: \(attachment.fileName) (\(attachment.fileSizeFormatted))")
        
        if let info = attachment.compressionInfo {
            OshiLog.storage.info("   \(info)")
        }
    }
    
    func loadAttachment(id: String) throws -> MediaAttachment {
        let fileURL = getAttachmentURL(for: id)
        let data = try MediaCipher.read(contentsOf: fileURL)  // __MEDIA_AT_REST_2026_09_22__ clair hérité accepté

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
    
    // MARK: - Utilities
    
    private func setProcessing(_ value: Bool) async {
        await MainActor.run {
            self.isProcessing = value
        }
    }
    
    private func updateProgress(_ value: Double) async {
        await MainActor.run {
            self.compressionProgress = value
        }
    }
    
    private func getFileSize(at url: URL) async throws -> Int {
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        return attributes[.size] as? Int ?? 0
    }
    
    private func formatBytes(_ bytes: Int) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }
    
    // MARK: - Document Processing
    
    /// Process document file (PDF, TXT, DOCX, etc.)
    func processDocument(from url: URL, deliveryMethod: DeliveryMethod = .auto) async throws -> MediaAttachment {
        await setProcessing(true)
        defer { Task { await setProcessing(false) } }
        
        return try await Task.detached(priority: .userInitiated) { [weak self] in
            guard let self = self else { throw MediaError.processingFailed }
            
            // Read document data
            // __PLAINTEXT_LEFTOVERS_2026_09_24__ Une copie temporaire à nous
            // (sélecteur, glisser-déposer) est supprimée dès qu'elle est lue.
            defer { MediaPlaintextTemp.removeIfOwned(url) }
            let data = try Data(contentsOf: url)
            let fileSize = data.count

            // R13 FIX: Block dangerous file types by magic bytes
            let header = [UInt8](data.prefix(4))
            let dangerousHeaders: [[UInt8]] = [
                [0x4D, 0x5A],                   // MZ — Windows PE executable
                [0x7F, 0x45, 0x4C, 0x46],       // ELF — Linux executable
                [0x23, 0x21],                    // #! — Shell script / shebang
            ]
            for dangerous in dangerousHeaders {
                if header.starts(with: dangerous) {
                    throw MediaError.unsupportedFileType
                }
            }

            OshiLog.storage.info("📄 Document size: \(self.formatBytes(fileSize))")
            
            // Check size limits
            let maxSize = self.getMaxSize(for: deliveryMethod, currentSize: fileSize)
            
            guard fileSize <= maxSize else {
                OshiLog.storage.info("❌ Document too large: \(self.formatBytes(fileSize)) > \(self.formatBytes(maxSize))")
                throw MediaError.fileTooLarge
            }
            
            let fileName = url.lastPathComponent
            let id = UUID().uuidString
            
            OshiLog.storage.info("✅ Document processed: \(fileName) (\(self.formatBytes(fileSize)))")
            
            return MediaAttachment(
                id: id,
                type: .document,
                data: data,
                thumbnailData: nil,
                fileName: fileName,
                fileSize: fileSize,
                timestamp: Date(),
                originalSize: fileSize,
                compressionRatio: nil,
                isCompressed: false
            )
        }.value
    }
    
    // MARK: - Contact Card Processing
    
    /// Create contact card for sharing public key
    func createContactCard(publicKey: String, alias: String? = nil) async throws -> MediaAttachment {
        await setProcessing(true)
        defer { Task { await setProcessing(false) } }
        
        return try await Task.detached(priority: .userInitiated) {
            let contact = ContactCard(
                publicKey: publicKey,
                alias: alias,
                timestamp: Date()
            )
            
            let encoder = JSONEncoder()
            let data = try encoder.encode(contact)
            let id = UUID().uuidString
            
            let displayName = alias ?? String(publicKey.prefix(16))
            OshiLog.storage.info("👤 Contact card created for: \(displayName)...")
            
            return MediaAttachment(
                id: id,
                type: .contact,
                data: data,
                thumbnailData: nil,
                fileName: "contact_\(String(publicKey.prefix(8))).vcard",
                fileSize: data.count,
                timestamp: Date(),
                originalSize: data.count,
                compressionRatio: nil,
                isCompressed: false
            )
        }.value
    }
    
    /// Decode contact card from MediaAttachment
    ///
    /// __CONTACT_CARD_COMPAT_2026_09_27__ Goes through `ContactCard.parse`, which
    /// accepts every shape a peer has actually sent — see that function.
    func decodeContactCard(from attachment: MediaAttachment) throws -> ContactCard {
        guard attachment.type == .contact else {
            throw MediaError.invalidMedia
        }
        guard let card = ContactCard.parse(attachment.data) else {
            throw MediaError.invalidMedia
        }
        return card
    }
}

// MARK: - Contact Card Model

struct ContactCard: Codable {
    let publicKey: String
    let alias: String?
    let timestamp: Date

    private enum CodingKeys: String, CodingKey {
        case publicKey, alias, timestamp
    }

    init(publicKey: String, alias: String?, timestamp: Date) {
        self.publicKey = publicKey
        self.alias = alias
        self.timestamp = timestamp
    }

    /// __CONTACT_CARD_COMPAT_2026_09_27__ Tolerant decoder.
    ///
    /// Reported: "I received a kind of contact picture of 75 bytes but nothing I
    /// can click." Those 75 bytes are exactly
    /// `{"publicKey":"eAbM…E3A=","alias":"Hugo"}` — the welcome-message contact
    /// card Android builds with kotlinx defaults (`WelcomeMessage.contactCardJson`),
    /// which drops the null `timestamp`. Forwarded to an iPhone, the synthesized
    /// decoder threw `keyNotFound(timestamp)` and the bubble fell back to a grey
    /// `person.crop.circle` square with no action.
    ///
    /// The date is metadata nobody renders, so it must never cost the card:
    /// missing → now; a number is Apple reference-date seconds (what our own
    /// `JSONEncoder` writes), or Unix seconds/millis from a sender that got it
    /// wrong; an ISO-8601 string is accepted too. `alias` may be absent, null or
    /// blank. Only `publicKey` is required — without it there is nobody to chat
    /// with. Encoding is unchanged (still synthesized), so what we send is
    /// byte-for-byte what shipped builds on both platforms already read.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let pk = try c.decode(String.self, forKey: .publicKey)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !pk.isEmpty else {
            throw DecodingError.dataCorruptedError(forKey: .publicKey, in: c,
                                                   debugDescription: "empty publicKey")
        }
        publicKey = pk
        let rawAlias = (try? c.decodeIfPresent(String.self, forKey: .alias)) ?? nil
        let trimmedAlias = rawAlias?.trimmingCharacters(in: .whitespacesAndNewlines)
        alias = (trimmedAlias?.isEmpty ?? true) ? nil : trimmedAlias
        if let n = (try? c.decodeIfPresent(Double.self, forKey: .timestamp)) ?? nil {
            timestamp = ContactCard.date(fromNumber: n)
        } else if let text = (try? c.decodeIfPresent(String.self, forKey: .timestamp)) ?? nil,
                  let date = ISO8601DateFormatter().date(from: text) {
            timestamp = date
        } else {
            timestamp = Date()
        }
    }

    /// A number on the wire → Date. Our encoder writes reference-date seconds
    /// (~8.1e8 in 2026); Unix seconds (~1.7e9) and Unix millis (~1.7e12) are what
    /// a confused sender would write instead.
    static func date(fromNumber n: Double) -> Date {
        if n > 100_000_000_000 { return Date(timeIntervalSince1970: n / 1000) }
        if n > 1_500_000_000 { return Date(timeIntervalSince1970: n) }
        return Date(timeIntervalSinceReferenceDate: n)
    }

    /// Every shape a contact card has travelled as, JSON first.
    ///
    /// 1. JSON `{publicKey, alias?, timestamp?}` — iOS `createContactCard`,
    ///    Android `ContactCardWire.encode`, and the timestamp-less Android welcome
    ///    card (see `init(from:)`).
    /// 2. vCard with the key in `NOTE:OSHI Public Key: …` — what Android's
    ///    `ContactShareManager.exportToVCard` hands to the system share sheet, and
    ///    so what can come back into OSHI as a `.vcf`.
    ///
    /// Returns nil when neither yields a public key, so the caller can show the
    /// bytes as a saveable file instead of a dead tile.
    static func parse(_ data: Data) -> ContactCard? {
        if let card = try? JSONDecoder().decode(ContactCard.self, from: data) {
            return card
        }
        guard let text = String(data: data, encoding: .utf8),
              text.range(of: "BEGIN:VCARD", options: .caseInsensitive) != nil else { return nil }
        var key: String?
        var name: String?
        for rawLine in text.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            let upper = line.uppercased()
            if upper.hasPrefix("NOTE"),
               let r = line.range(of: "OSHI Public Key:", options: .caseInsensitive) {
                key = String(line[r.upperBound...]).trimmingCharacters(in: .whitespaces)
            } else if upper.hasPrefix("FN:") || upper.hasPrefix("FN;"),
                      let colon = line.firstIndex(of: ":") {
                name = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            }
        }
        guard let pk = key, !pk.isEmpty else { return nil }
        return ContactCard(publicKey: pk, alias: (name?.isEmpty ?? true) ? nil : name, timestamp: Date())
    }

    var displayName: String {
        alias ?? formatAddress(publicKey)
    }
    
    private func formatAddress(_ address: String) -> String {
        guard address.count > 10 else { return address }
        let start = address.prefix(6)
        let end = address.suffix(4)
        return "\(start)...\(end)"
    }
}

// MARK: - Errors

enum MediaError: LocalizedError {
    case processingFailed
    case compressionFailed
    case encryptionFailed
    case fileTooLarge
    case videoTooLong
    case invalidMedia
    case unsupportedFileType    // ✅ NEW
    
    var errorDescription: String? {
        switch self {
        case .processingFailed:
            return NSLocalizedString("media_error_processing", comment: "")
        case .compressionFailed:
            return NSLocalizedString("media_error_compression", comment: "")
        case .encryptionFailed:
            return NSLocalizedString("media_error_encryption", comment: "")
        case .fileTooLarge:
            return NSLocalizedString("media_error_too_large", comment: "")
        case .videoTooLong:
            return NSLocalizedString("media_error_video_long", comment: "")
        case .invalidMedia:
            return NSLocalizedString("media_error_invalid", comment: "")
        case .unsupportedFileType:
            return NSLocalizedString("media_error_unsupported", comment: "")
        }
    }
}
