import Foundation
import Vision
import PDFKit
import UIKit

class AIDocumentProcessor {
    static let shared = AIDocumentProcessor()

    enum ProcessingResult {
        case text(String)
        case error(String)
    }

    // MARK: - Image OCR (Vision framework)

    func extractText(from image: UIImage) async -> ProcessingResult {
        guard let cgImage = image.cgImage else {
            return .error("ai_no_text_found".localized)
        }

        return await withCheckedContinuation { continuation in
            let request = VNRecognizeTextRequest { request, error in
                if let error = error {
                    continuation.resume(returning: .error(error.localizedDescription))
                    return
                }

                guard let observations = request.results as? [VNRecognizedTextObservation] else {
                    continuation.resume(returning: .error("ai_no_text_found".localized))
                    return
                }

                let recognizedStrings = observations.compactMap { observation in
                    observation.topCandidates(1).first?.string
                }

                let fullText = recognizedStrings.joined(separator: "\n")

                if fullText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    continuation.resume(returning: .error("ai_no_text_found".localized))
                } else {
                    continuation.resume(returning: .text(fullText))
                }
            }

            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            request.recognitionLanguages = [
                "en-US", "fr-FR", "es-ES", "de-DE", "it-IT",
                "pt-BR", "pt-PT", "ru-RU", "uk-UA", "pl-PL",
                "ja-JP", "zh-Hans", "ar-SA", "he-IL", "hi-IN"
            ]

            let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
            do {
                try handler.perform([request])
            } catch {
                continuation.resume(returning: .error(error.localizedDescription))
            }
        }
    }

    // MARK: - PDF Text Extraction

    func extractText(from pdfURL: URL) -> ProcessingResult {
        guard let document = PDFDocument(url: pdfURL) else {
            return .error("ai_no_text_found".localized)
        }

        var fullText = ""
        for i in 0..<document.pageCount {
            if let page = document.page(at: i), let pageText = page.string {
                fullText += pageText + "\n"
            }
        }

        let trimmed = fullText.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return .error("ai_no_text_found".localized)
        }
        return .text(trimmed)
    }

    // MARK: - Plain Text and Office Files

    func extractText(from fileURL: URL, type: String) -> ProcessingResult {
        let ext = type.lowercased()

        switch ext {
        case "txt", "md", "csv", "json", "xml", "html", "htm", "log", "rtf":
            return extractPlainText(from: fileURL)

        case "docx":
            return extractDocxText(from: fileURL)

        case "pptx":
            return extractPptxText(from: fileURL)

        default:
            // Try as plain text
            return extractPlainText(from: fileURL)
        }
    }

    private func extractPlainText(from fileURL: URL) -> ProcessingResult {
        do {
            let text = try String(contentsOf: fileURL, encoding: .utf8)
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty {
                return .error("ai_no_text_found".localized)
            }
            return .text(trimmed)
        } catch {
            // Try other encodings
            if let data = try? Data(contentsOf: fileURL),
               let text = String(data: data, encoding: .isoLatin1) {
                let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
                if trimmed.isEmpty {
                    return .error("ai_no_text_found".localized)
                }
                return .text(trimmed)
            }
            return .error(error.localizedDescription)
        }
    }

    private func extractDocxText(from fileURL: URL) -> ProcessingResult {
        // .docx files are ZIP archives containing word/document.xml
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ sous tmp/OSHIPlainMedia (protégé, balayé).
        let tempDir = MediaPlaintextTemp.directory.appendingPathComponent(UUID().uuidString)

        do {
            try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: tempDir) }

            // Unzip the docx
            guard unzipFile(at: fileURL, to: tempDir) else {
                return .error("ai_no_text_found".localized)
            }

            let documentXML = tempDir.appendingPathComponent("word/document.xml")
            if FileManager.default.fileExists(atPath: documentXML.path) {
                let xmlData = try Data(contentsOf: documentXML)
                let xmlString = String(data: xmlData, encoding: .utf8) ?? ""
                let stripped = stripXMLTags(xmlString)
                let trimmed = stripped.trimmingCharacters(in: .whitespacesAndNewlines)
                if trimmed.isEmpty {
                    return .error("ai_no_text_found".localized)
                }
                return .text(trimmed)
            }

            return .error("ai_no_text_found".localized)
        } catch {
            return .error(error.localizedDescription)
        }
    }

    private func extractPptxText(from fileURL: URL) -> ProcessingResult {
        // .pptx files are ZIP archives containing ppt/slides/slide*.xml
        let tempDir = MediaPlaintextTemp.directory.appendingPathComponent(UUID().uuidString)  // __PLAINTEXT_LEFTOVERS_2026_09_24__

        do {
            try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: tempDir) }

            guard unzipFile(at: fileURL, to: tempDir) else {
                return .error("ai_no_text_found".localized)
            }

            let slidesDir = tempDir.appendingPathComponent("ppt/slides")
            guard FileManager.default.fileExists(atPath: slidesDir.path) else {
                return .error("ai_no_text_found".localized)
            }

            let files = try FileManager.default.contentsOfDirectory(at: slidesDir, includingPropertiesForKeys: nil)
            let slideFiles = files.filter { $0.lastPathComponent.hasPrefix("slide") && $0.pathExtension == "xml" }
                .sorted { $0.lastPathComponent < $1.lastPathComponent }

            var fullText = ""
            for slideFile in slideFiles {
                let xmlData = try Data(contentsOf: slideFile)
                let xmlString = String(data: xmlData, encoding: .utf8) ?? ""
                let stripped = stripXMLTags(xmlString)
                let trimmed = stripped.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty {
                    fullText += trimmed + "\n\n"
                }
            }

            let result = fullText.trimmingCharacters(in: .whitespacesAndNewlines)
            if result.isEmpty {
                return .error("ai_no_text_found".localized)
            }
            return .text(result)
        } catch {
            return .error(error.localizedDescription)
        }
    }

    // MARK: - Helpers

    private func stripXMLTags(_ xml: String) -> String {
        // Remove XML tags and decode basic entities
        var result = xml.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
        result = result.replacingOccurrences(of: "&amp;", with: "&")
        result = result.replacingOccurrences(of: "&lt;", with: "<")
        result = result.replacingOccurrences(of: "&gt;", with: ">")
        result = result.replacingOccurrences(of: "&quot;", with: "\"")
        result = result.replacingOccurrences(of: "&apos;", with: "'")
        result = result.replacingOccurrences(of: "&#160;", with: " ")
        // Collapse multiple whitespace
        result = result.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        return result
    }

    private func unzipFile(at sourceURL: URL, to destinationURL: URL) -> Bool {
        // Use Process/NSTask is not available on iOS, use built-in Archive support
        // iOS approach: use Foundation's archive extraction via FileManager
        // Actually, for iOS we need to use a manual ZIP reader or the Archive framework
        // Simplest approach: use the built-in zip support via NSFileCoordinator

        do {
            let data = try Data(contentsOf: sourceURL)

            // Minimal ZIP extraction - find local file headers and extract
            return extractZipEntries(from: data, to: destinationURL)
        } catch {
            return false
        }
    }

    private func extractZipEntries(from data: Data, to destinationURL: URL) -> Bool {
        // ZIP local file header signature: 0x04034b50 (PK\x03\x04)
        let signature: [UInt8] = [0x50, 0x4B, 0x03, 0x04]
        var offset = 0
        var foundAny = false

        while offset < data.count - 30 {
            // Find next local file header
            let headerBytes = [data[offset], data[offset+1], data[offset+2], data[offset+3]]
            guard headerBytes == signature else {
                offset += 1
                continue
            }

            // Parse local file header
            let compressionMethod = UInt16(data[offset+8]) | (UInt16(data[offset+9]) << 8)
            let compressedSize = UInt32(data[offset+18]) | (UInt32(data[offset+19]) << 8) | (UInt32(data[offset+20]) << 16) | (UInt32(data[offset+21]) << 24)
            let uncompressedSize = UInt32(data[offset+22]) | (UInt32(data[offset+23]) << 8) | (UInt32(data[offset+24]) << 16) | (UInt32(data[offset+25]) << 24)
            let fileNameLength = UInt16(data[offset+26]) | (UInt16(data[offset+27]) << 8)
            let extraFieldLength = UInt16(data[offset+28]) | (UInt16(data[offset+29]) << 8)

            let fileNameStart = offset + 30
            let fileNameEnd = fileNameStart + Int(fileNameLength)

            guard fileNameEnd <= data.count else { break }

            let fileNameData = data[fileNameStart..<fileNameEnd]
            guard let fileName = String(data: fileNameData, encoding: .utf8) else {
                offset = fileNameEnd + Int(extraFieldLength) + Int(compressedSize)
                continue
            }

            let dataStart = fileNameEnd + Int(extraFieldLength)
            let dataEnd = dataStart + Int(compressedSize)

            guard dataEnd <= data.count else { break }

            // Only extract stored (uncompressed) files - compression method 0
            // For deflated files (method 8), we need NSData decompression
            // __PLAINTEXT_LEFTOVERS_2026_09_24__ « zip slip »: un nom d'entrée en
            // `../` (document reçu d'un tiers) écrivait HORS du dossier temporaire.
            if !fileName.hasSuffix("/"), !fileName.hasPrefix("/"),
               !fileName.split(separator: "/").contains("..") {
                let fileURL = destinationURL.appendingPathComponent(fileName)
                let dirURL = fileURL.deletingLastPathComponent()
                try? FileManager.default.createDirectory(at: dirURL, withIntermediateDirectories: true)

                if compressionMethod == 0 {
                    // Stored (no compression)
                    let fileData = data[dataStart..<dataEnd]
                    try? Data(fileData).write(to: fileURL)
                    foundAny = true
                } else if compressionMethod == 8 {
                    // Deflate - use NSData decompression
                    let compressedData = data[dataStart..<dataEnd]
                    if let decompressed = decompressDeflate(Data(compressedData), expectedSize: Int(uncompressedSize)) {
                        try? decompressed.write(to: fileURL)
                        foundAny = true
                    }
                }
            }

            offset = dataEnd
        }

        return foundAny
    }

    private func decompressDeflate(_ data: Data, expectedSize: Int) -> Data? {
        // Try NSData's built-in decompression with zlib header
        var zlibData = Data([0x78, 0x9C])
        zlibData.append(data)

        if let decompressed = try? (zlibData as NSData).decompressed(using: .zlib) {
            return decompressed as Data
        }

        // Fallback: try raw data with zlib decompression
        if let decompressed = try? (data as NSData).decompressed(using: .zlib) {
            return decompressed as Data
        }

        return nil
    }

    // MARK: - Format Context for LLM

    func formatAsContext(_ text: String, sourceType: String) -> String {
        let maxChars = 4000
        let truncated: String
        if text.count > maxChars {
            truncated = String(text.prefix(maxChars)) + "\n... [truncated]"
        } else {
            truncated = text
        }
        return "[Extracted from \(sourceType)]\n\(truncated)\n[End of document]"
    }

    // MARK: - File Size Check

    func fileSizeOK(at url: URL, maxMB: Int = 50) -> Bool {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = attrs[.size] as? UInt64 else {
            return true // Can't determine size, let it proceed
        }
        return size <= UInt64(maxMB) * 1024 * 1024
    }
}
