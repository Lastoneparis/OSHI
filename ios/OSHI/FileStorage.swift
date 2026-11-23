//
//  FileStorage.swift
//  OSHI - Complete file-based storage system
//  ✅ ALL DECODER ISSUES FIXED
//

import Foundation
import UIKit
import CryptoKit
import AVFoundation

class FileStorage {
    static let shared = FileStorage()
    
    private let fileManager = FileManager.default
    private let maxUserDefaultsSize = 4_000_000 // 4MB
    private let writeLock = NSLock()
    
    private init() {
        createAllDirectories()
        excludeDocumentsFromBackup()
        migrateLegacyPlaintextStores()
        // __MEDIA_AT_REST_2026_09_22__ Balaye les copies en clair d'un lancement
        // précédent puis rechiffre les médias encore en clair, en tâche de fond.
        MediaAtRestMigration.startAtLaunch()
        #if DEBUG
        StorageSelfTest.runIfRequested()
        MultiDeviceSelfTest.runIfRequested()   // __PER_DEVICE_MAILBOX_2026_09_23__ + websession v2
        #endif
    }

    /// __AT_REST_ENCRYPTION_2026_09_22__ Rechiffre tout de suite les magasins
    /// encore en clair au lieu d'attendre leur prochaine écriture — les groupes
    /// silencieux pouvaient rester en clair des semaines. Sous `writeLock`, donc
    /// jamais en travers d'une sauvegarde; relit le fichier sous le verrou, donc
    /// réécrit exactement ce qui est sur disque. Avant le premier déverrouillage
    /// la lecture échoue et on réessaiera au prochain lancement.
    private func migrateLegacyPlaintextStores() {
        DispatchQueue.global(qos: .utility).async { [self] in
            guard let names = try? fileManager.contentsOfDirectory(atPath: documentsDirectory.path) else { return }
            for key in Self.encryptedStoreKeys where names.contains("\(key).json") {
                writeLock.lock()
                defer { writeLock.unlock() }
                let url = documentsDirectory.appendingPathComponent("\(key).json")
                guard let raw = try? Data(contentsOf: url),
                      raw.prefix(8) != Data("OSHIENC1".utf8),
                      let sealed = try? AtRestCipher.shared.seal(raw),
                      (try? AtRestCipher.shared.open(sealed)) == raw else { continue }
                do {
                    try sealed.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                    OshiLog.storage.info("🔐 Encrypted legacy store \(key) (\(raw.count) bytes)")
                } catch {
                    OshiLog.storage.info("❌ At-rest migration failed for \(key): \(error)")
                }
            }
        }
    }

    /// Magasins écrits par `save(forKey:)` qui portent du contenu de message ou
    /// des métadonnées de conversation. Les autres clés passent au format
    /// chiffré à leur prochaine écriture.
    static let encryptedStoreKeys = [
        "messages", "groupMessages_v2", "messageGroups_v2", "pending_decryption",
        "message_retry_queue", "contact_presence", "discoveredPublicGroups"
    ]

    /// __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ Tout Documents hors des
    /// sauvegardes iCloud / Finder.
    ///
    /// CONSTATÉ sur l'iPhone 17 le 2026-09-22: l'historique vit en clair dans
    /// `Documents/messages.json` (7,4 Mo) et `groupMessages_v2.json` — écrits par
    /// `save(forKey:)`, qui n'excluait RIEN. Seul `saveMessages()` excluait son
    /// fichier, et il écrit dans `Messages/`, resté vide: le mauvais chemin était
    /// protégé. Une sauvegarde iCloud sans Protection avancée des données est
    /// lisible par Apple; une sauvegarde Finder non chiffrée, par quiconque tient
    /// le Mac. Les médias (`MediaMessages/`, `AudioMessages/`) et les exports
    /// laissés là prenaient le même chemin.
    ///
    /// Le drapeau posé sur le DOSSIER couvre son contenu, y compris les fichiers
    /// que les écritures `.atomic` remplacent. Migrer d'appareil passe désormais
    /// par l'export chiffré (`EncryptedMessageExport`), lisible avec la clé du
    /// compte seulement.
    private func excludeDocumentsFromBackup() {
        var url = documentsDirectory
        var rv = URLResourceValues()
        rv.isExcludedFromBackup = true
        try? url.setResourceValues(rv)
        // Ancien export EN CLAIR laissé dans Documents par `exportChatMessages`
        // (220 Mo mesurés): il ne doit pas survivre à la mise à jour.
        try? fileManager.removeItem(at: documentsDirectory.appendingPathComponent("OSHI_Messages_Export.json"))
    }
    
    // MARK: - Directory Paths
    
    private var documentsDirectory: URL {
        fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }
    
    private var messagesDirectory: URL {
        let dir = documentsDirectory.appendingPathComponent("Messages", isDirectory: true)
        createDirectoryIfNeeded(at: dir)
        return dir
    }
    
    private var mediaDirectory: URL {
        let dir = documentsDirectory.appendingPathComponent("Media", isDirectory: true)
        createDirectoryIfNeeded(at: dir)
        return dir
    }
    
    private var thumbnailsDirectory: URL {
        let dir = documentsDirectory.appendingPathComponent("Thumbnails", isDirectory: true)
        createDirectoryIfNeeded(at: dir)
        return dir
    }
    
    private var documentsSharedDirectory: URL {
        let dir = documentsDirectory.appendingPathComponent("SharedDocuments", isDirectory: true)
        createDirectoryIfNeeded(at: dir)
        return dir
    }
    
    private var sessionsDirectory: URL {
        let dir = documentsDirectory.appendingPathComponent("Sessions", isDirectory: true)
        createDirectoryIfNeeded(at: dir)
        return dir
    }
    
    // MARK: - Directory Management
    
    private func createAllDirectories() {
        _ = messagesDirectory
        _ = mediaDirectory
        _ = thumbnailsDirectory
        _ = documentsSharedDirectory
        _ = sessionsDirectory
    }
    
    private func createDirectoryIfNeeded(at url: URL) {
        if !fileManager.fileExists(atPath: url.path) {
            try? fileManager.createDirectory(at: url, withIntermediateDirectories: true)
        }
    }

    /// Drop the pre-FileStorage UserDefaults copy of `key`, but only if one is actually there.
    ///
    /// MECHANISM (0x8BADF00D watchdog fix): `UserDefaults.removeObject` posts
    /// `UserDefaults.didChangeNotification` **synchronously on the calling thread**, and a block
    /// observer registered against an OperationQueue blocks that thread on
    /// `-[NSOperation waitUntilFinished]` until the queue drains. Every save below runs on a
    /// background serial queue *while holding `writeLock`*, so purging unconditionally on each
    /// save turned every routine write into a rendezvous with the main runloop — and deadlocked
    /// outright whenever the main thread was itself waiting on `writeLock`. The legacy value
    /// only ever needs removing once, so read first and write only when there is something
    /// to remove. (See NotificationCenter+MainThread in NotificationManager.swift for the other half of the fix.)
    private func purgeLegacyUserDefaults(_ key: String) {
        guard UserDefaults.standard.object(forKey: key) != nil else { return }
        UserDefaults.standard.removeObject(forKey: key)
    }
    
    // MARK: - Message Storage
    
    func saveMessages<T: Codable>(_ messages: [T]) {
        writeLock.lock()
        defer { writeLock.unlock() }
        do {
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            let data = try encoder.encode(messages)

            var fileURL = messagesDirectory.appendingPathComponent("messages.json")
            try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])

            // Keep the plaintext message store out of iCloud/iTunes backups: the
            // whole conversation history should not leak into an unencrypted or
            // separately-keyed backup surface.
            var rv = URLResourceValues()
            rv.isExcludedFromBackup = true
            try? fileURL.setResourceValues(rv)

            OshiLog.storage.info("💾 Saved \(messages.count) messages (\(data.count) bytes)")
            purgeLegacyUserDefaults("messages")
        } catch {
            OshiLog.storage.info("❌ Error saving messages: \(error)")
        }
    }
    
    func loadMessages<T: Codable>() -> [T] {
        let fileURL = messagesDirectory.appendingPathComponent("messages.json")
        
        guard fileManager.fileExists(atPath: fileURL.path) else {
            // Try migration from UserDefaults
            if let oldData = UserDefaults.standard.data(forKey: "messages") {
                do {
                    let decoder = JSONDecoder()
                    decoder.dateDecodingStrategy = .iso8601  // ✅ FIXED: was dateEncodingStrategy
                    let messages = try decoder.decode([T].self, from: oldData)
                    saveMessages(messages)
                    UserDefaults.standard.removeObject(forKey: "messages")
                    return messages
                } catch {
                    OshiLog.storage.info("❌ Migration error: \(error)")
                }
            }
            return []
        }
        
        do {
            let data = try Data(contentsOf: fileURL)
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601  // ✅ CORRECT
            return try decoder.decode([T].self, from: data)
        } catch {
            OshiLog.storage.info("❌ Error loading messages: \(error)")
            return []
        }
    }
    
    // MARK: - Double Ratchet Sessions
    
    func saveDoubleRatchetSessions<T: Codable>(_ sessions: [String: T]) {
        writeLock.lock()
        defer { writeLock.unlock() }
        do {
            let encoder = JSONEncoder()
            let data = try encoder.encode(sessions)

            let fileURL = sessionsDirectory.appendingPathComponent("ratchet_sessions.json")
            try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            
            OshiLog.storage.info("🔐 Saved \(sessions.count) ratchet sessions")
            purgeLegacyUserDefaults("doubleRatchetSessions_v4")
        } catch {
            OshiLog.storage.info("❌ Error saving sessions: \(error)")
        }
    }
    
    func loadDoubleRatchetSessions<T: Codable>() -> [String: T] {
        let fileURL = sessionsDirectory.appendingPathComponent("ratchet_sessions.json")
        
        guard fileManager.fileExists(atPath: fileURL.path) else {
            // Try migration
            if let oldData = UserDefaults.standard.data(forKey: "doubleRatchetSessions_v4") {
                do {
                    let sessions = try JSONDecoder().decode([String: T].self, from: oldData)
                    saveDoubleRatchetSessions(sessions)
                    UserDefaults.standard.removeObject(forKey: "doubleRatchetSessions_v4")
                    return sessions
                } catch {
                    OshiLog.storage.info("❌ Migration error: \(error)")
                }
            }
            return [:]
        }
        
        do {
            let data = try Data(contentsOf: fileURL)
            return try JSONDecoder().decode([String: T].self, from: data)
        } catch {
            OshiLog.storage.info("❌ Error loading sessions: \(error)")
            return [:]
        }
    }
    
    // MARK: - Generic Save/Load (MAIN API)
    
    /// Save any Codable data to file storage
    func save<T: Codable>(_ data: T, forKey key: String) {
        writeLock.lock()
        defer { writeLock.unlock() }
        do {
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            let encoded = try encoder.encode(data)

            let fileURL = documentsDirectory.appendingPathComponent("\(key).json")
            // __AT_REST_ENCRYPTION_2026_09_22__ Jamais en clair: sans clé, on
            // n'écrit pas (le fichier précédent reste intact).
            let sealed = try AtRestCipher.shared.seal(encoded)
            try sealed.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            
            OshiLog.storage.info("💾 Saved \(key) (\(encoded.count) bytes)")
            purgeLegacyUserDefaults(key)
        } catch {
            OshiLog.storage.info("❌ Error saving \(key): \(error)")
        }
    }
    
    /// Load any Codable data from file storage
    func load<T: Codable>(forKey key: String) -> T? {
        let fileURL = documentsDirectory.appendingPathComponent("\(key).json")
        
        guard fileManager.fileExists(atPath: fileURL.path) else {
            return nil
        }
        
        do {
            let data = try AtRestCipher.shared.open(Data(contentsOf: fileURL))
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601  // ✅ FIXED: was dateEncodingStrategy
            return try decoder.decode(T.self, from: data)
        } catch {
            OshiLog.storage.info("❌ Error loading \(key): \(error)")
            return nil
        }
    }
    
    /// Load with default value
    func load<T: Codable>(forKey key: String, defaultValue: T) -> T {
        return load(forKey: key) ?? defaultValue
    }
    
    // MARK: - Compatibility Methods (for GroupManager)
    
    /// Load raw Data (compatibility method)
    func loadData(for key: String) -> Data? {
        let fileURL = documentsDirectory.appendingPathComponent("\(key).json")
        guard fileManager.fileExists(atPath: fileURL.path) else {
            return nil
        }
        return (try? Data(contentsOf: fileURL)).flatMap { try? AtRestCipher.shared.open($0) }
    }
    
    /// Save raw Data (compatibility method)
    func saveData(_ data: Data, for key: String) {
        writeLock.lock()
        defer { writeLock.unlock() }
        let fileURL = documentsDirectory.appendingPathComponent("\(key).json")
        do {
            try AtRestCipher.shared.seal(data).write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            OshiLog.storage.info("💾 Saved \(key) (\(data.count) bytes)")
        } catch {
            OshiLog.storage.info("❌ Error saving \(key): \(error)")
        }
    }
    
    // MARK: - UserDefaults Migration
    
    func migrateFromUserDefaults<T: Codable>(key: String, type: T.Type) {
        let fileURL = documentsDirectory.appendingPathComponent("\(key).json")
        if fileManager.fileExists(atPath: fileURL.path) {
            OshiLog.storage.info("✓ \(key) already migrated")
            return
        }
        
        guard let data = UserDefaults.standard.data(forKey: key) else {
            return
        }
        
        do {
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601  // ✅ CORRECT
            let decoded = try decoder.decode(T.self, from: data)
            save(decoded, forKey: key)
            UserDefaults.standard.removeObject(forKey: key)
            OshiLog.storage.info("🔄 Migrated \(key)")
        } catch {
            OshiLog.storage.info("❌ Migration error: \(error)")
        }
    }
    
    func migrateFromUserDefaults<T: Codable>(key: String, arrayType: [T].Type) {
        let fileURL = documentsDirectory.appendingPathComponent("\(key).json")
        if fileManager.fileExists(atPath: fileURL.path) {
            OshiLog.storage.info("✓ \(key) already migrated")
            return
        }
        
        guard let data = UserDefaults.standard.data(forKey: key) else {
            return
        }
        
        do {
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601  // ✅ FIXED: was dateEncodingStrategy
            let decoded = try decoder.decode([T].self, from: data)
            save(decoded, forKey: key)
            UserDefaults.standard.removeObject(forKey: key)
            OshiLog.storage.info("🔄 Migrated \(key) array")
        } catch {
            OshiLog.storage.info("❌ Migration error: \(error)")
        }
    }
    
    func migrateFromUserDefaults<T: Codable>(key: String, dictionaryType: [String: T].Type) {
        let fileURL = documentsDirectory.appendingPathComponent("\(key).json")
        if fileManager.fileExists(atPath: fileURL.path) {
            OshiLog.storage.info("✓ \(key) already migrated")
            return
        }
        
        guard let data = UserDefaults.standard.data(forKey: key) else {
            return
        }
        
        do {
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601  // ✅ FIXED: was dateEncodingStrategy
            let decoded = try decoder.decode([String: T].self, from: data)
            save(decoded, forKey: key)
            UserDefaults.standard.removeObject(forKey: key)
            OshiLog.storage.info("🔄 Migrated \(key) dictionary")
        } catch {
            OshiLog.storage.info("❌ Migration error: \(error)")
        }
    }
    
    // MARK: - Media File Storage
    
    func saveMedia(data: Data, filename: String) -> URL? {
        let fileURL = mediaDirectory.appendingPathComponent(filename)

        do {
            // __MEDIA_AT_REST_2026_09_22__ Chiffré (`MediaCipher`); l'URL rendue
            // désigne donc un fichier CHIFFRÉ — le relire par `loadMedia`.
            try MediaCipher.write(data, to: fileURL)
            let sizeMB = Double(data.count) / 1_000_000.0
            OshiLog.storage.info("💾 Saved media: \(filename) (\(String(format: "%.2f", sizeMB)) MB)")
            return fileURL
        } catch {
            OshiLog.storage.info("❌ Error saving media: \(error)")
            return nil
        }
    }
    
    func loadMedia(filename: String) -> Data? {
        let fileURL = mediaDirectory.appendingPathComponent(filename)
        
        guard fileManager.fileExists(atPath: fileURL.path) else {
            return nil
        }

        return try? MediaCipher.read(contentsOf: fileURL)
    }
    
    func deleteMedia(filename: String) {
        let fileURL = mediaDirectory.appendingPathComponent(filename)
        try? fileManager.removeItem(at: fileURL)
        deleteThumbnail(for: filename)
    }
    
    // MARK: - Thumbnail Generation
    
    func generateAndSaveThumbnail(for fileURL: URL, maxSize: CGSize = CGSize(width: 200, height: 200)) -> URL? {
        let filename = fileURL.lastPathComponent
        let thumbnailFilename = "thumb_\(filename).jpg"
        let thumbnailURL = thumbnailsDirectory.appendingPathComponent(thumbnailFilename)
        
        if fileManager.fileExists(atPath: thumbnailURL.path) {
            return thumbnailURL
        }
        
        let fileExtension = fileURL.pathExtension.lowercased()

        // __MEDIA_AT_REST_2026_09_22__ La source peut être un média chiffré
        // (`saveMedia`): AVAsset / CGPDFDocument veulent le clair, donc copie
        // temporaire protégée, supprimée dès la miniature faite.
        if (try? MediaCipher.isEncryptedFile(at: fileURL)) == true {
            guard let plain = try? MediaPlaintextTemp.decryptedCopy(of: fileURL) else { return nil }
            defer { MediaPlaintextTemp.remove(plain) }
            return generateAndSaveThumbnail(for: plain, maxSize: maxSize)
        }

        if ["jpg", "jpeg", "png", "heic", "heif"].contains(fileExtension) {
            return generateImageThumbnail(from: fileURL, to: thumbnailURL, maxSize: maxSize)
        } else if ["mp4", "mov", "m4v"].contains(fileExtension) {
            return generateVideoThumbnail(from: fileURL, to: thumbnailURL, maxSize: maxSize)
        } else if ["pdf"].contains(fileExtension) {
            return generatePDFThumbnail(from: fileURL, to: thumbnailURL, maxSize: maxSize)
        } else {
            return generateDocumentThumbnail(for: fileExtension, to: thumbnailURL)
        }
    }
    
    private func generateImageThumbnail(from sourceURL: URL, to thumbnailURL: URL, maxSize: CGSize) -> URL? {
        guard let imageData = try? MediaCipher.read(contentsOf: sourceURL),
              let image = MediaManager.safeUIImage(from: imageData, maxDimension: max(maxSize.width, maxSize.height)) else {
            return nil
        }

        let thumbnail = image.preparingThumbnail(of: maxSize) ?? image
        
        guard let thumbnailData = thumbnail.jpegData(compressionQuality: 0.7) else {
            return nil
        }
        
        do {
            try MediaCipher.write(thumbnailData, to: thumbnailURL)  // __MEDIA_AT_REST_2026_09_22__
            return thumbnailURL
        } catch {
            return nil
        }
    }
    
    private func generateVideoThumbnail(from sourceURL: URL, to thumbnailURL: URL, maxSize: CGSize) -> URL? {
        let asset = AVAsset(url: sourceURL)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = maxSize
        
        do {
            let cgImage = try generator.copyCGImage(at: .zero, actualTime: nil)
            let image = UIImage(cgImage: cgImage)
            
            guard let thumbnailData = image.jpegData(compressionQuality: 0.7) else {
                return nil
            }
            
            try MediaCipher.write(thumbnailData, to: thumbnailURL)  // __MEDIA_AT_REST_2026_09_22__
            return thumbnailURL
        } catch {
            return nil
        }
    }
    
    private func generatePDFThumbnail(from sourceURL: URL, to thumbnailURL: URL, maxSize: CGSize) -> URL? {
        guard let pdfDocument = CGPDFDocument(sourceURL as CFURL),
              let page = pdfDocument.page(at: 1) else {
            return nil
        }
        
        let pageRect = page.getBoxRect(.mediaBox)
        let scale = min(maxSize.width / pageRect.width, maxSize.height / pageRect.height)
        let scaledSize = CGSize(width: pageRect.width * scale, height: pageRect.height * scale)
        
        let renderer = UIGraphicsImageRenderer(size: scaledSize)
        let image = renderer.image { context in
            context.cgContext.setFillColor(UIColor.white.cgColor)
            context.cgContext.fill(CGRect(origin: .zero, size: scaledSize))
            
            context.cgContext.translateBy(x: 0, y: scaledSize.height)
            context.cgContext.scaleBy(x: scale, y: -scale)
            context.cgContext.drawPDFPage(page)
        }
        
        guard let thumbnailData = image.jpegData(compressionQuality: 0.7) else {
            return nil
        }
        
        do {
            try MediaCipher.write(thumbnailData, to: thumbnailURL)  // __MEDIA_AT_REST_2026_09_22__
            return thumbnailURL
        } catch {
            return nil
        }
    }
    
    private func generateDocumentThumbnail(for fileExtension: String, to thumbnailURL: URL) -> URL? {
        let size = CGSize(width: 200, height: 200)
        let renderer = UIGraphicsImageRenderer(size: size)
        
        let image = renderer.image { context in
            let backgroundColor: UIColor
            switch fileExtension {
            case "doc", "docx":
                backgroundColor = UIColor(red: 0.2, green: 0.4, blue: 0.8, alpha: 1.0)
            case "xls", "xlsx":
                backgroundColor = UIColor(red: 0.1, green: 0.6, blue: 0.3, alpha: 1.0)
            case "ppt", "pptx":
                backgroundColor = UIColor(red: 0.8, green: 0.3, blue: 0.2, alpha: 1.0)
            case "zip", "rar", "7z":
                backgroundColor = UIColor(red: 0.6, green: 0.5, blue: 0.2, alpha: 1.0)
            default:
                backgroundColor = UIColor.gray
            }
            
            backgroundColor.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            
            let text = ".\(fileExtension)"
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.boldSystemFont(ofSize: 32),
                .foregroundColor: UIColor.white
            ]
            let textSize = text.size(withAttributes: attributes)
            let textRect = CGRect(
                x: (size.width - textSize.width) / 2,
                y: (size.height - textSize.height) / 2,
                width: textSize.width,
                height: textSize.height
            )
            text.draw(in: textRect, withAttributes: attributes)
        }
        
        guard let thumbnailData = image.jpegData(compressionQuality: 0.7) else {
            return nil
        }
        
        do {
            try MediaCipher.write(thumbnailData, to: thumbnailURL)  // __MEDIA_AT_REST_2026_09_22__
            return thumbnailURL
        } catch {
            return nil
        }
    }
    
    /// __MEDIA_AT_REST_2026_09_22__ L'URL rendue est celle d'un fichier CHIFFRÉ
    /// (`MediaCipher`): la lire par `MediaCipher.read(contentsOf:)`, ou
    /// `MediaPlaintextTemp.decryptedCopy(of:)` s'il faut une URL de clair.
    func getThumbnail(for filename: String) -> URL? {
        let thumbnailFilename = "thumb_\(filename).jpg"
        let thumbnailURL = thumbnailsDirectory.appendingPathComponent(thumbnailFilename)
        
        if fileManager.fileExists(atPath: thumbnailURL.path) {
            return thumbnailURL
        }
        
        return nil
    }
    
    func deleteThumbnail(for filename: String) {
        let thumbnailFilename = "thumb_\(filename).jpg"
        let thumbnailURL = thumbnailsDirectory.appendingPathComponent(thumbnailFilename)
        try? fileManager.removeItem(at: thumbnailURL)
    }
    
    // MARK: - File Export
    
    func prepareFileForExport(filename: String) -> URL? {
        let sourceURL = mediaDirectory.appendingPathComponent(filename)
        
        guard fileManager.fileExists(atPath: sourceURL.path) else {
            return nil
        }
        
        // __MEDIA_AT_REST_2026_09_22__ Copie EN CLAIR pour le partage: plus
        // dans `Documents/SharedDocuments/` (persistant, jamais effacé avant 30
        // jours) mais dans le dossier temporaire protégé, balayé au lancement.
        // L'appelant la supprime par `MediaPlaintextTemp.remove` une fois partagée.
        return try? MediaPlaintextTemp.decryptedCopy(of: sourceURL, fileName: filename)
    }
    
    // MARK: - Storage Management
    
    func getStorageSize() -> (messages: Int, media: Int, thumbnails: Int, sessions: Int) {
        func directorySize(_ url: URL) -> Int {
            var totalSize: Int = 0
            
            if let enumerator = fileManager.enumerator(at: url, includingPropertiesForKeys: [.fileSizeKey]) {
                for case let fileURL as URL in enumerator {
                    if let fileSize = try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                        totalSize += fileSize
                    }
                }
            }
            
            return totalSize
        }
        
        return (
            messages: directorySize(messagesDirectory),
            media: directorySize(mediaDirectory),
            thumbnails: directorySize(thumbnailsDirectory),
            sessions: directorySize(sessionsDirectory)
        )
    }
    
    func cleanupOldFiles(olderThan days: Int = 30) {
        let cutoffDate = Calendar.current.date(byAdding: .day, value: -days, to: Date()) ?? Date()
        
        func cleanDirectory(_ url: URL) {
            if let enumerator = fileManager.enumerator(at: url, includingPropertiesForKeys: [.creationDateKey]) {
                for case let fileURL as URL in enumerator {
                    if let creationDate = try? fileURL.resourceValues(forKeys: [.creationDateKey]).creationDate,
                       creationDate < cutoffDate {
                        try? fileManager.removeItem(at: fileURL)
                    }
                }
            }
        }
        
        cleanDirectory(thumbnailsDirectory)
        cleanDirectory(documentsSharedDirectory)
    }
    
    func checkUserDefaultsSize() {
        let defaults = UserDefaults.standard
        var totalSize = 0
        
        if let dict = defaults.dictionaryRepresentation() as NSDictionary? {
            if let data = try? NSKeyedArchiver.archivedData(withRootObject: dict, requiringSecureCoding: false) {
                totalSize = data.count
            }
        }
        
        let sizeMB = Double(totalSize) / 1_000_000.0
        OshiLog.storage.info("📊 UserDefaults: \(String(format: "%.2f", sizeMB)) MB")
        
        if totalSize > maxUserDefaultsSize {
            OshiLog.storage.info("⚠️ WARNING: UserDefaults exceeds 4MB!")
        }
    }
}

// MARK: - Encrypted message export

/// __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ Export de l'historique chiffré avec
/// la clé du compte, et import dans une autre session OSHI qui détient ce compte.
///
/// Les deux exports d'avant écrivaient l'historique EN CLAIR (JSON complet avec
/// `plaintextContent` et les médias, ou texte lisible) puis le passaient à la
/// feuille de partage: AirDrop, Mail, Fichiers, iCloud Drive. Une messagerie
/// chiffrée de bout en bout qui remet toute sa mémoire en clair à qui la demande.
///
/// Format (`.oshiexport`):
///
///     "OSHIEXP1" (8) | version (1) | sel (32) | tag de compte (8) | nonce (12) | AES-256-GCM(ct ‖ tag 16)
///
/// Clé = HKDF-SHA256(clé privée X25519 du compte, sel, "oshi-message-export-v1").
/// L'en-tête entier est la donnée associée (AAD): changer le tag de compte ou la
/// version casse l'authentification. Le tag de compte permet de dire « cet
/// export appartient à un autre compte » au lieu d'un échec de déchiffrement muet.
enum EncryptedMessageExport {
    static let fileExtension = "oshiexport"
    private static let magic = Data("OSHIEXP1".utf8)
    private static let version: UInt8 = 1
    private static let saltLen = 32, tagLen = 8, nonceLen = 12
    private static var headerLen: Int { magic.count + 1 + saltLen + tagLen }

    enum ExportError: LocalizedError {
        case noAccount, notAnExport, unsupportedVersion, otherAccount, corrupted, otherPlatform(String)

        var errorDescription: String? {
            switch self {
            case .noAccount:
                return NSLocalizedString("export.error.no_account", value: "No OSHI account on this device.", comment: "")
            case .notAnExport:
                return NSLocalizedString("export.error.not_export", value: "This file is not an OSHI message export.", comment: "")
            case .unsupportedVersion:
                return NSLocalizedString("export.error.version", value: "This export was made by a newer OSHI. Update the app first.", comment: "")
            case .otherAccount:
                return NSLocalizedString("export.error.other_account", value: "This export belongs to another OSHI account. Restore that account's key first.", comment: "")
            case .corrupted:
                return NSLocalizedString("export.error.corrupted", value: "The export is damaged or was modified.", comment: "")
            case .otherPlatform(let p):
                return String(format: NSLocalizedString("export.error.other_platform", value: "This export was made by OSHI for %@ and cannot be imported here yet.", comment: ""), p)
            }
        }
    }

    /// `platform` dit quel schéma porte `messages`: le conteneur est commun à
    /// iOS, Android et Desktop, les objets message ne le sont pas encore.
    private struct Payload: Codable {
        let version: Int
        let platform: String?
        let exportedAt: Date
        let messages: [SecureMessage]
    }

    /// Chiffre `messages` et l'écrit dans le dossier temporaire (jamais dans
    /// Documents). L'appelant supprime le fichier une fois partagé.
    ///
    /// __EXPORT_V2_INTEROP_2026_09_22__ Écrit la charge NEUTRE version 2
    /// (`MessageExportV2`, `docs/OSHI_EXPORT_FORMAT.md`), lisible par Android et
    /// Desktop. Sans les groupes: préférer `write(_ snapshot:)`.
    static func write(_ messages: [SecureMessage], identity: IdentityManager) throws -> URL {
        let me = identity.privateKey?.publicKey.rawRepresentation.base64EncodedString() ?? identity.publicKey
        return try write(MessageExportV2.Snapshot(direct: messages, myKey: me), identity: identity)
    }

    /// Instantané complet (1:1 + groupes + alias), pris sur le main actor par
    /// `MessageExportV2.capture`, chiffré ici hors du main thread.
    static func write(_ snapshot: MessageExportV2.Snapshot, identity: IdentityManager) throws -> URL {
        let plaintext = MessageExportV2.encode(snapshot).data
        return try seal(plaintext, identity: identity)
    }

    #if DEBUG
    /// Charge version 1 native iOS — uniquement pour prouver que la lecture v1
    /// reste possible (`StorageSelfTest`).
    static func writeLegacyV1(_ messages: [SecureMessage], identity: IdentityManager) throws -> URL {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return try seal(encoder.encode(Payload(version: 1, platform: "ios", exportedAt: Date(), messages: messages)),
                        identity: identity)
    }

    /// Charge v1 native iOS en clair (auto-test de `decodePayload` sans compte).
    static func legacyV1Plaintext(_ messages: [SecureMessage]) throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return try encoder.encode(Payload(version: 1, platform: "ios", exportedAt: Date(), messages: messages))
    }
    #endif

    private static func seal(_ plaintext: Data, identity: IdentityManager) throws -> URL {
        guard let accountTag = identity.messageExportAccountTag() else { throw ExportError.noAccount }
        var salt = Data(count: saltLen)
        let ok = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, saltLen, $0.baseAddress!) }
        guard ok == errSecSuccess, let key = identity.deriveMessageExportKey(salt: salt) else {
            throw ExportError.noAccount
        }

        var header = magic
        header.append(version)
        header.append(salt)
        header.append(accountTag)
        let sealed = try AES.GCM.seal(plaintext, using: key, authenticating: header)

        var file = header
        file.append(contentsOf: sealed.nonce)
        file.append(sealed.ciphertext)
        file.append(sealed.tag)

        let stamp = ISO8601DateFormatter().string(from: Date()).prefix(10)
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("OSHI_Messages_\(stamp).\(fileExtension)")
        try file.write(to: url, options: [.atomic, .completeFileProtection])
        return url
    }

    /// Déchiffre un export. Échoue sans rien renvoyer si le fichier n'est pas à
    /// ce compte ou a été altéré — jamais de résultat partiel.
    ///
    /// __EXPORT_V2_INTEROP_2026_09_22__ Lit la version 2 de N'IMPORTE QUELLE
    /// plateforme, et la version 1 d'iOS seulement (une v1 Android/Desktop porte
    /// les objets natifs de cette plateforme).
    static func read(_ url: URL, identity: IdentityManager) throws -> MessageExportV2.Imported {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let file = try Data(contentsOf: url)

        guard file.count > headerLen + nonceLen + 16,
              file.prefix(magic.count) == magic else { throw ExportError.notAnExport }
        guard file[file.startIndex + magic.count] == version else { throw ExportError.unsupportedVersion }

        let header = file.prefix(headerLen)
        let saltStart = file.startIndex + magic.count + 1
        let salt = file[saltStart..<(saltStart + saltLen)]
        let tag = file[(saltStart + saltLen)..<(saltStart + saltLen + tagLen)]

        guard let accountTag = identity.messageExportAccountTag() else { throw ExportError.noAccount }
        guard Data(tag) == accountTag else { throw ExportError.otherAccount }
        guard let key = identity.deriveMessageExportKey(salt: Data(salt)) else { throw ExportError.noAccount }

        let body = file.suffix(from: file.startIndex + headerLen)
        let plaintext: Data
        do {
            let box = try AES.GCM.SealedBox(combined: Data(body))
            plaintext = try AES.GCM.open(box, using: key, authenticating: Data(header))
        } catch {
            throw ExportError.corrupted
        }

        let me = identity.privateKey?.publicKey.rawRepresentation.base64EncodedString() ?? identity.publicKey
        return try decodePayload(plaintext, myKey: me)
    }

    /// Charge authentifiée → import: v2 de toute plateforme, v1 iOS seulement,
    /// > 2 refusée. Séparé de `read` pour être vérifiable sans compte.
    static func decodePayload(_ plaintext: Data, myKey me: String) throws -> MessageExportV2.Imported {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        struct Head: Decodable { let platform: String?; let version: Int? }
        let head = try? decoder.decode(Head.self, from: plaintext)
        if let v = head?.version, v >= MessageExportV2.version {
            guard v == MessageExportV2.version else { throw ExportError.unsupportedVersion }
            guard let imported = MessageExportV2.parse(plaintext, myKey: me) else { throw ExportError.corrupted }
            return imported
        }
        if let p = head?.platform, p != "ios" {
            throw ExportError.otherPlatform(p)
        }
        guard let payload = try? decoder.decode(Payload.self, from: plaintext) else { throw ExportError.corrupted }
        return MessageExportV2.Imported(direct: payload.messages, sourcePlatform: "ios", payloadVersion: 1)
    }
}

// MARK: - Export payload v2 (platform-neutral)

/// __EXPORT_V2_INTEROP_2026_09_22__ La charge NEUTRE (version 2) du conteneur
/// `.oshiexport`. Spéc: `docs/OSHI_EXPORT_FORMAT.md`. Fixture partagé:
/// `docs/fixtures/export_v2_sample.json` — lu par les tests Android et Desktop et
/// par `StorageSelfTest` ici: les trois qui le mappent vers leur modèle natif,
/// c'est la preuve qu'un export fait n'importe où s'importe partout.
///
/// La version 1 portait les objets NATIFS de chaque plateforme (`SecureMessage`
/// ici), donc un export iPhone ne s'importait ni sur Android ni sur Desktop.
enum MessageExportV2 {
    static let version = 2
    /// Octets de médias bruts par export (même budget sur les trois plateformes).
    static let mediaBudget = 48 * 1024 * 1024
    /// Charges de contrôle qui ne sont pas de la conversation: jamais exportées.
    static let controlPrefixes = ["📸PROFILE_UPDATE📸", "📸PROFILE_REQUEST📸", "📖READ_RECEIPT📖",
                                  "📬DELIVERY_RECEIPT📬", "🔗LINKS_VISIBILITY🔗", "🎨WALLPAPER_UPDATE🎨",
                                  // __GROUP_RECEIPTS_2026_11_02__ (N8)
                                  GroupReceipts.prefix]

    static func isControl(_ s: String?) -> Bool {
        guard let s else { return false }
        return controlPrefixes.contains(where: s.hasPrefix)
    }

    // MARK: Modèle neutre (champs optionnels = tolérance à la lecture)

    struct Account: Codable { var publicKey: String }
    struct Contact: Codable { var publicKey: String; var alias: String?; var nickname: String? }
    struct Conversation: Codable {
        var id: String
        var kind: String
        var peerPublicKey: String?
        var groupId: String?
        var groupName: String?
    }
    struct Media: Codable {
        var type: String
        var fileName: String?
        var mime: String?
        var dataBase64: String?
        var omitted: String?
    }
    struct Edited: Codable { var text: String; var at: String? }
    struct Msg: Codable {
        var id: String
        var conversation: String
        var direction: String
        var senderPublicKey: String
        var senderName: String?
        var timestamp: String
        var text: String?
        var status: String?
        var read: Bool?
        var edited: Edited?
        var deleted: Bool?
        var replyToId: String?
        var viewOnce: Bool?
        var media: Media?
        var reactions: [String: [String]]?
    }
    struct Payload: Encodable {
        var version = MessageExportV2.version
        var platform = "ios"
        var exportedAt: String
        var account: Account?
        var contacts: [Contact]
        var conversations: [Conversation]
        var messages: [Msg]
    }

    /// Un élément qui échoue à se décoder devient `nil` au lieu de faire échouer tout le tableau.
    private struct Lenient<T: Decodable>: Decodable {
        let value: T?
        init(from decoder: Decoder) throws { value = try? T(from: decoder) }
    }
    private struct LenientPayload: Decodable {
        let version: Int
        let platform: String?
        let account: Account?
        let contacts: [Lenient<Contact>]?
        let conversations: [Lenient<Conversation>]?
        let messages: [Lenient<Msg>]?
    }

    // MARK: Utilitaires

    private static let isoMs: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private static let isoPlain = ISO8601DateFormatter()

    static func iso(_ d: Date) -> String { isoMs.string(from: d) }
    static func date(_ s: String) -> Date? { isoMs.date(from: s) ?? isoPlain.date(from: s) }

    /// Base64 standard avec remplissage des 32 octets — la seule graphie comparée.
    static func canonicalKey(_ k: String) -> String { MessageManager.canonicalIdentity(k) }

    static func v2Type(_ t: MediaManager.MediaType) -> String {
        switch t {
        case .image: return "image"
        case .gif: return "gif"
        case .video: return "video"
        case .audio: return "audio"
        case .document: return "document"
        case .contact: return "contact"
        }
    }
    static func v2Type(_ t: MediaType) -> String {
        switch t {
        case .photo: return "image"
        case .video: return "video"
        case .audio: return "audio"
        case .document: return "document"
        case .contact: return "contact"
        }
    }
    /// v2 → iOS 1:1. `location` n'a pas de case iOS: nil (le texte reste).
    static func iosType(_ s: String) -> MediaManager.MediaType? {
        switch s {
        case "image", "photo": return .image
        case "gif": return .gif
        case "video": return .video
        case "audio", "voice": return .audio
        case "contact": return .contact
        case "location": return nil
        default: return .document
        }
    }
    static func iosGroupType(_ s: String) -> MediaType? {
        switch s {
        case "image", "photo", "gif": return .photo
        case "video": return .video
        case "audio", "voice": return .audio
        case "contact": return .contact
        case "location": return nil
        default: return .document
        }
    }

    static func guessMime(_ type: String, _ fileName: String?) -> String {
        switch (fileName as NSString?)?.pathExtension.lowercased() ?? "" {
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "gif": return "image/gif"
        case "heic": return "image/heic"
        case "webp": return "image/webp"
        case "mp4": return "video/mp4"
        case "mov": return "video/quicktime"
        case "m4a": return "audio/mp4"
        case "aac": return "audio/aac"
        case "ogg", "opus": return "audio/ogg"
        case "mp3": return "audio/mpeg"
        case "wav": return "audio/wav"
        case "pdf": return "application/pdf"
        case "txt": return "text/plain"
        case "vcf": return "text/vcard"
        default:
            switch type {
            case "image": return "image/jpeg"
            case "gif": return "image/gif"
            case "video": return "video/mp4"
            case "audio": return "audio/mp4"
            case "contact", "location": return "application/json"
            default: return "application/octet-stream"
            }
        }
    }

    // MARK: Instantané (main actor) → charge v2 (hors main)

    struct GroupItem {
        let message: GroupMessage
        let text: String?
        let groupName: String
    }

    struct Snapshot {
        var direct: [SecureMessage]
        var groups: [GroupItem] = []
        var aliases: [String: String] = [:]
        var myKey: String
        /// Octets d'un média 1:1 absent de la mémoire (relu du disque, déchiffré).
        var directMedia: ((SecureMessage) -> Data?)? = nil
        var groupMedia: ((GroupMessage) -> Data?)? = nil
    }

    /// Capture sur le main actor: l'historique, les groupes (texte résolu) et les alias.
    @MainActor
    static func capture(_ messageManager: MessageManager, identity: IdentityManager) -> Snapshot {
        let me = identity.privateKey?.publicKey.rawRepresentation.base64EncodedString() ?? identity.publicKey
        let gm = MessageGroupManager.shared
        var items: [GroupItem] = []
        for g in gm.groups {
            for m in gm.groupMessages[g.id] ?? [] where !m.isSystemMessage {
                // Un média de groupe porte ses octets en base64 dans `encryptedContent`:
                // le « déchiffrer » en texte exporterait ce base64 comme légende.
                let text = m.mediaType != nil ? m.plaintextContent : (m.plaintextContent ?? gm.decryptMessage(m))
                items.append(GroupItem(message: m, text: text, groupName: g.name))
            }
        }
        var aliases: [String: String] = [:]
        if let data = UserDefaults.standard.data(forKey: "contactAliases"),
           let dict = try? JSONDecoder().decode([String: String].self, from: data) {
            aliases = dict
        }
        return Snapshot(
            direct: messageManager.messages, groups: items, aliases: aliases, myKey: me,
            directMedia: { [weak messageManager] m in messageManager?.exportMediaBytes(for: m) },
            groupMedia: { m in m.mediaType.flatMap { gm.loadGroupMediaAttachment(for: m.id.uuidString, type: $0) } }
        )
    }

    /// Instantané → JSON v2. `mediaSkipped` = médias laissés hors budget.
    static func encode(_ s: Snapshot, now: Date = Date()) -> (data: Data, mediaSkipped: Int) {
        let me = canonicalKey(s.myKey)
        var budget = mediaBudget
        var skipped = 0
        var convs: [String: Conversation] = [:]
        var order: [String] = []
        var msgs: [Msg] = []
        var peers = Set<String>()

        func addConv(_ c: Conversation) {
            if convs[c.id] == nil { convs[c.id] = c; order.append(c.id) }
        }
        func media(_ type: String, _ fileName: String?, viewOnce: Bool, _ load: () -> Data?) -> Media {
            var m = Media(type: type, fileName: fileName, mime: guessMime(type, fileName))
            if viewOnce { m.omitted = "viewOnce"; return m }
            guard let bytes = load() else { m.omitted = "unavailable"; return m }
            guard bytes.count <= budget else { m.omitted = "sizeBudget"; skipped += 1; return m }
            budget -= bytes.count
            m.dataBase64 = bytes.base64EncodedString()
            return m
        }

        for m in s.direct {
            if isControl(m.plaintextContent) { continue }
            let sender = canonicalKey(m.senderPublicKey.isEmpty ? m.senderAddress : m.senderPublicKey)
            let out = sender == me
            let peer = out ? canonicalKey(m.recipientPublicKey.isEmpty ? m.recipientAddress : m.recipientPublicKey) : sender
            guard !peer.isEmpty, peer != me else { continue }
            let cid = "direct:" + peer
            addConv(Conversation(id: cid, kind: "direct", peerPublicKey: peer))
            peers.insert(peer)
            var x = Msg(id: m.id, conversation: cid, direction: out ? "out" : "in", senderPublicKey: sender,
                        timestamp: iso(m.timestamp),
                        text: m.isDeletedForEveryone ? nil : m.plaintextContent,
                        status: m.deliveryStatus.rawValue, read: m.isRead)
            if let e = m.editedContent { x.edited = Edited(text: e, at: m.editedAt.map(iso)) }
            if m.isDeletedForEveryone { x.deleted = true }
            x.replyToId = m.replyToId
            if m.isViewOnce { x.viewOnce = true }
            if let t = m.mediaType {
                x.media = media(v2Type(t), m.mediaFileName, viewOnce: m.isViewOnce) {
                    m.mediaAttachment ?? m.originalMediaData ?? s.directMedia?(m)
                }
            }
            msgs.append(x)
        }

        for item in s.groups {
            let m = item.message
            if m.senderPublicKey == "SYSTEM" || isControl(item.text) { continue }
            let gid = m.groupId.uuidString.uppercased()
            let cid = "group:" + gid
            addConv(Conversation(id: cid, kind: "group", groupId: gid, groupName: item.groupName))
            let sender = canonicalKey(m.senderPublicKey)
            let out = sender == me
            var x = Msg(id: m.id.uuidString, conversation: cid, direction: out ? "out" : "in", senderPublicKey: sender,
                        timestamp: iso(m.timestamp), text: item.text,
                        status: m.deliveryStatus?.rawValue ?? (out ? "sent" : "delivered"), read: m.isRead)
            if let e = m.editedContent { x.edited = Edited(text: e, at: m.editedAt.map(iso)) }
            if let t = m.mediaType {
                x.media = media(v2Type(t), m.mediaFileName, viewOnce: false) { m.mediaData ?? s.groupMedia?(m) }
            }
            msgs.append(x)
        }

        let contacts = peers.sorted().compactMap { pk -> Contact? in
            let alias = s.aliases[pk] ?? s.aliases.first(where: { canonicalKey($0.key) == pk })?.value
            return Contact(publicKey: pk, alias: alias)
        }
        let payload = Payload(exportedAt: iso(now), account: Account(publicKey: me), contacts: contacts,
                              conversations: order.compactMap { convs[$0] }, messages: msgs)
        let data = (try? JSONEncoder().encode(payload)) ?? Data()
        return (data, skipped)
    }

    // MARK: Charge v2 → modèles iOS

    struct ImportedGroupMessage {
        let message: GroupMessage
        let media: Data?
    }

    struct Imported {
        var direct: [SecureMessage]
        var group: [ImportedGroupMessage] = []
        var aliases: [String: String] = [:]
        /// Pairs des conversations 1:1 importées (pour n'ajouter que leurs alias).
        var peers = Set<String>()
        /// Messages mal formés ou dans une conversation non déclarée.
        var invalid = 0
        /// Messages d'un type de conversation inconnu (écrivain plus récent, `desktop.local`).
        var skippedUnknown = 0
        var sourcePlatform: String
        var payloadVersion: Int
    }

    struct ImportCounts {
        var imported = 0
        var alreadyPresent = 0
        var skippedGroups = 0
        var invalid = 0
        var skippedUnknown = 0
    }

    /// Analyse une charge v2 (déjà authentifiée). nil si le document entier n'en est pas une.
    static func parse(_ plaintext: Data, myKey: String) -> Imported? {
        guard let p = try? JSONDecoder().decode(LenientPayload.self, from: plaintext), p.version == version else { return nil }
        let me = canonicalKey(myKey)
        var convs: [String: Conversation] = [:]
        for c in p.conversations ?? [] { if let c = c.value { convs[c.id] = c } }
        var out = Imported(direct: [], sourcePlatform: p.platform ?? "?", payloadVersion: version)
        for c in p.contacts ?? [] {
            if let c = c.value, let a = c.alias, !a.isEmpty { out.aliases[canonicalKey(c.publicKey)] = a }
        }
        for raw in p.messages ?? [] {
            guard let m = raw.value, let ts = date(m.timestamp), !m.id.isEmpty,
                  m.direction == "in" || m.direction == "out",
                  let c = convs[m.conversation] else { out.invalid += 1; continue }
            let outgoing = m.direction == "out"
            let sender = outgoing ? me : canonicalKey(m.senderPublicKey)
            let bytes = m.media?.dataBase64.flatMap { Data(base64Encoded: $0) }
            let deleted = m.deleted ?? false
            let viewOnce = m.viewOnce ?? false
            switch c.kind {
            case "direct":
                guard let peerRaw = c.peerPublicKey else { out.invalid += 1; continue }
                let peer = canonicalKey(peerRaw)
                guard peer != me else { out.invalid += 1; continue }
                out.peers.insert(peer)
                let recipient = outgoing ? peer : me
                let type = m.media.flatMap { iosType($0.type) }
                var text = deleted ? nil : m.text
                if text == nil, !deleted, m.media != nil, type == nil { text = m.media?.fileName }
                out.direct.append(SecureMessage(
                    id: m.id, senderAddress: sender, recipientAddress: recipient,
                    encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: sender, timestamp: ts),
                    timestamp: ts,
                    // L'historique importé n'est jamais « non lu »: ouvrir la
                    // conversation ne doit émettre aucun accusé de lecture.
                    isRead: true,
                    deliveryStatus: DeliveryStatus(rawValue: m.status ?? "") ?? (outgoing ? .sent : .delivered),
                    senderPublicKey: sender, recipientPublicKey: recipient,
                    plaintextContent: text,
                    mediaAttachment: type == nil ? nil : bytes,
                    deliveryMethod: nil,
                    mediaType: type,
                    originalMediaData: (type == nil || viewOnce) ? nil : bytes,
                    mediaFileName: m.media?.fileName,
                    isViewOnce: viewOnce,
                    hasBeenViewed: viewOnce && bytes == nil,
                    editedAt: m.edited?.at.flatMap(date),
                    editedContent: m.edited?.text,
                    isDeletedForEveryone: deleted,
                    replyToId: m.replyToId))
            case "group":
                guard let gidRaw = c.groupId, let gid = UUID(uuidString: gidRaw),
                      let mid = UUID(uuidString: m.id) else { out.invalid += 1; continue }
                if deleted { continue } // un message de groupe supprimé n'a rien à restaurer
                let type = m.media.flatMap { iosGroupType($0.type) }
                let text = m.text ?? (type == nil ? m.media?.fileName : nil)
                out.group.append(ImportedGroupMessage(message: GroupMessage(
                    id: mid, groupId: gid, senderPublicKey: sender,
                    encryptedContent: Data((text ?? "").utf8), timestamp: ts, isRead: true,
                    mediaType: type, mediaFileName: m.media?.fileName, plaintextContent: text,
                    editedAt: m.edited?.at.flatMap(date), editedContent: m.edited?.text,
                    deliveryStatus: outgoing ? (DeliveryStatus(rawValue: m.status ?? "") ?? .sent) : nil),
                    media: type == nil ? nil : bytes))
            default:
                out.skippedUnknown += 1
            }
        }
        return out
    }

    /// Fusionne un import. Aucun effet de bord d'un message qui ARRIVE (pas de
    /// notification, d'accusé, de profil, de réaction): uniquement la remise en
    /// place. Messages de groupe: seulement dans les groupes présents ici.
    @MainActor
    static func apply(_ imp: Imported, to messageManager: MessageManager) -> ImportCounts {
        var counts = ImportCounts(invalid: imp.invalid, skippedUnknown: imp.skippedUnknown)
        let added = messageManager.importMessages(imp.direct)
        counts.imported += added
        counts.alreadyPresent += imp.direct.count - added

        if !imp.group.isEmpty {
            let gm = MessageGroupManager.shared
            let held = Set(gm.groups.map(\.id))
            var changed = false
            for item in imp.group {
                let m = item.message
                guard held.contains(m.groupId) else { counts.skippedGroups += 1; continue }
                var list = gm.groupMessages[m.groupId] ?? []
                if list.contains(where: { $0.id == m.id }) { counts.alreadyPresent += 1; continue }
                if let data = item.media, let t = m.mediaType {
                    _ = gm.saveGroupMediaAttachmentPublic(data, for: m.id.uuidString, type: t)
                }
                list.append(m)
                list.sort { $0.timestamp < $1.timestamp }
                gm.groupMessages[m.groupId] = list
                counts.imported += 1
                changed = true
            }
            if changed { gm.saveGroupMessagesPublic() }
        }

        // Alias des pairs importés, sans jamais écraser un nom choisi.
        if !imp.aliases.isEmpty {
            var dict: [String: String] = [:]
            if let data = UserDefaults.standard.data(forKey: "contactAliases"),
               let d = try? JSONDecoder().decode([String: String].self, from: data) { dict = d }
            var touched = false
            for (pk, alias) in imp.aliases where imp.peers.contains(pk) && dict[pk] == nil {
                dict[pk] = alias; touched = true
            }
            if touched, let data = try? JSONEncoder().encode(dict) {
                UserDefaults.standard.set(data, forKey: "contactAliases")
            }
        }
        return counts
    }
}

#if DEBUG
/// __EXPORT_V2_INTEROP_2026_09_22__ Vérifications v2 de `StorageSelfTest`.
///
/// A. Le fixture PARTAGÉ `docs/fixtures/export_v2_sample.json` (celui des tests
///    Android et Desktop), passé par `SIMCTL_CHILD_OSHI_EXPORT_FIXTURE=<chemin>`
///    ou `-OSHIExportFixture <chemin>` — le simulateur lit le disque de l'hôte.
/// B. Aller-retour natif → v2 → natif sur des messages synthétiques (sans
///    toucher l'historique réel).
extension StorageSelfTest {
    static func exportV2Checks() -> [String] {
        var lines: [String] = []
        func check(_ name: String, _ ok: Bool) { lines.append("\(ok ? "PASS" : "FAIL") \(name)") }
        let me = "B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9/AsrhtHHw="
        let bob = "gbY32PzSxtpjWeaWMROhFw3nleS3JbhNHgtM/Z7FjOk="
        let carol = "TCbZB0wn2J7eWScMCsFLceBxsVI5UZ91R0svO6Y0gfU="
        let gid = UUID(uuidString: "5B0E7C1A-3F2D-4E8B-9A61-2C7D4F0B8E13")!
        let png = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==")!
        func near(_ a: Date?, _ t: TimeInterval) -> Bool { a.map { abs($0.timeIntervalSince1970 - t) < 0.0005 } ?? false }

        // A. Fixture partagé
        let args = ProcessInfo.processInfo.arguments
        let argPath = args.firstIndex(of: "-OSHIExportFixture").flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil }
        let path = ProcessInfo.processInfo.environment["OSHI_EXPORT_FIXTURE"] ?? argPath
        if let path, let data = FileManager.default.contents(atPath: path) {
            if let imp = MessageExportV2.parse(data, myKey: me) {
                check("v2.fixture.counts(direct=\(imp.direct.count),group=\(imp.group.count),invalid=\(imp.invalid),unknown=\(imp.skippedUnknown))",
                      imp.direct.count == 6 && imp.group.count == 2 && imp.invalid == 1 && imp.skippedUnknown == 1)
                let d = Dictionary(imp.direct.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
                if let m = d["6F1C2A3B-0000-4000-8000-000000000001"] {
                    check("v2.fixture.m1.outgoing", m.senderPublicKey == me && m.recipientPublicKey == bob
                          && m.plaintextContent == "Hello Bob" && m.deliveryStatus == .read && near(m.timestamp, 1_789_891_200))
                } else { check("v2.fixture.m1.present", false) }
                if let m = d["6f1c2a3b-0000-4000-8000-000000000002"] {
                    check("v2.fixture.m2.incoming", m.senderPublicKey == bob && m.recipientPublicKey == me
                          && m.plaintextContent == "Hi! été 👋" && m.isRead
                          && m.replyToId == "6F1C2A3B-0000-4000-8000-000000000001" && near(m.timestamp, 1_789_891_290.25))
                } else { check("v2.fixture.m2.present", false) }
                if let m = d["6F1C2A3B-0000-4000-8000-000000000003"] {
                    check("v2.fixture.m3.media", m.mediaType == .image && m.mediaAttachment == png && m.mediaFileName == "dot.png")
                } else { check("v2.fixture.m3.present", false) }
                if let m = d["6F1C2A3B-0000-4000-8000-000000000004"] {
                    check("v2.fixture.m4.edited", m.plaintextContent == "See you at 9" && m.editedContent == "See you at 10"
                          && near(m.editedAt, 1_789_891_440))
                } else { check("v2.fixture.m4.present", false) }
                check("v2.fixture.m5.deleted", d["6F1C2A3B-0000-4000-8000-000000000005"]?.isDeletedForEveryone == true)
                if let m = d["6F1C2A3B-0000-4000-8000-000000000006"] {
                    check("v2.fixture.m6.viewOnce", m.isViewOnce && m.hasBeenViewed && m.deliveryStatus == .failed
                          && m.mediaType == .video && m.mediaAttachment == nil)
                } else { check("v2.fixture.m6.present", false) }
                let g = Dictionary(imp.group.map { ($0.message.id.uuidString, $0.message) }, uniquingKeysWith: { a, _ in a })
                if let g1 = g["7A000000-0000-4000-8000-000000000001"], let g2 = g["7A000000-0000-4000-8000-000000000002"] {
                    check("v2.fixture.group", g1.groupId == gid && g1.senderPublicKey == carol && g1.plaintextContent == "group hello"
                          && g2.senderPublicKey == me && g2.deliveryStatus == .sent)
                } else { check("v2.fixture.group.present", false) }
                check("v2.fixture.aliases", imp.aliases[bob] == "Bob" && imp.peers == [bob])
            } else { check("v2.fixture.parse", false) }
        } else { lines.append("SKIP v2.fixture (set SIMCTL_CHILD_OSHI_EXPORT_FIXTURE)") }

        // B. Aller-retour natif → v2 → natif
        func sm(_ id: String, out: Bool, _ text: String?, _ t: TimeInterval) -> SecureMessage {
            let s = out ? me : bob, r = out ? bob : me
            return SecureMessage(id: id, senderAddress: s, recipientAddress: r,
                                 encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: s,
                                                                    timestamp: Date(timeIntervalSince1970: t)),
                                 timestamp: Date(timeIntervalSince1970: t), isRead: true, deliveryStatus: out ? .delivered : .read,
                                 senderPublicKey: s, recipientPublicKey: r, plaintextContent: text)
        }
        var r1 = sm("r1", out: true, "hello", 1_789_891_200.123); r1.replyToId = "r0"
        var r2 = sm("r2", out: false, nil, 1_789_891_201); r2.mediaType = .image; r2.mediaAttachment = png; r2.mediaFileName = "p.png"
        var r3 = sm("r3", out: true, "typo", 1_789_891_202); r3.editedContent = "fixed"; r3.editedAt = Date(timeIntervalSince1970: 1_789_891_300)
        var r4 = sm("r4", out: false, nil, 1_789_891_203); r4.isDeletedForEveryone = true
        var r5 = sm("r5", out: false, nil, 1_789_891_204); r5.mediaType = .gif; r5.mediaAttachment = png
        let r6 = sm("r6", out: false, "📬DELIVERY_RECEIPT📬r1", 1_789_891_205)
        let gIn = GroupMessage(id: UUID(), groupId: gid, senderPublicKey: carol, encryptedContent: Data(), timestamp: Date(timeIntervalSince1970: 1_789_891_206),
                               isRead: true, plaintextContent: "hey all")
        var gOut = GroupMessage(id: UUID(), groupId: gid, senderPublicKey: me, encryptedContent: Data(), timestamp: Date(timeIntervalSince1970: 1_789_891_207),
                                isRead: true, mediaType: .audio, mediaFileName: "note.m4a", deliveryStatus: .sent)
        gOut.mediaData = png
        let originals = [r1, r2, r3, r4, r5]
        let snap = MessageExportV2.Snapshot(
            direct: originals + [r6],
            groups: [.init(message: gIn, text: "hey all", groupName: "Team"), .init(message: gOut, text: nil, groupName: "Team")],
            aliases: [bob: "Bob"], myKey: me)
        let encoded = MessageExportV2.encode(snap)
        let json = (try? JSONSerialization.jsonObject(with: encoded.data)) as? [String: Any]
        check("v2.roundTrip.header", json?["version"] as? Int == 2 && json?["platform"] as? String == "ios")
        if let back = MessageExportV2.parse(encoded.data, myKey: me) {
            check("v2.roundTrip.controlDropped", back.direct.count == originals.count && !back.direct.contains { $0.id == "r6" })
            let b = Dictionary(back.direct.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            var ok = true
            for o in originals {
                guard let x = b[o.id] else { ok = false; lines.append("FAIL v2.roundTrip.missing \(o.id)"); continue }
                let same = x.senderPublicKey == o.senderPublicKey && x.recipientPublicKey == o.recipientPublicKey
                    && abs(x.timestamp.timeIntervalSince(o.timestamp)) < 0.0005
                    && x.plaintextContent == (o.isDeletedForEveryone ? nil : o.plaintextContent)
                    && x.mediaType == o.mediaType && x.mediaAttachment == o.mediaAttachment
                    && x.mediaFileName == o.mediaFileName && x.replyToId == o.replyToId
                    && x.editedContent == o.editedContent && x.editedAt == o.editedAt
                    && x.isDeletedForEveryone == o.isDeletedForEveryone && x.deliveryStatus == o.deliveryStatus
                if !same { ok = false; lines.append("FAIL v2.roundTrip.field \(o.id)") }
            }
            check("v2.roundTrip.direct(\(originals.count))", ok)
            let g = Dictionary(back.group.map { ($0.message.id, $0) }, uniquingKeysWith: { a, _ in a })
            check("v2.roundTrip.group", g[gIn.id]?.message.plaintextContent == "hey all" && g[gIn.id]?.message.senderPublicKey == carol
                  && g[gOut.id]?.message.mediaType == .audio && g[gOut.id]?.media == png
                  && g[gOut.id]?.message.mediaFileName == "note.m4a" && g[gOut.id]?.message.groupId == gid)
            check("v2.roundTrip.alias", back.aliases[bob] == "Bob")
        } else { check("v2.roundTrip.parse", false) }

        // C. Aiguillage de version (sans compte): v2 toute plateforme, v1 iOS seule, v3 refusée.
        check("v2.dispatch.v2", (try? EncryptedMessageExport.decodePayload(encoded.data, myKey: me))?.payloadVersion == 2)
        if let v1 = try? EncryptedMessageExport.legacyV1Plaintext(originals) {
            let back = try? EncryptedMessageExport.decodePayload(v1, myKey: me)
            check("v2.dispatch.v1ios", back?.payloadVersion == 1 && back?.direct.map(\.id) == originals.map(\.id))
        } else { check("v2.dispatch.v1ios.encode", false) }
        do {
            _ = try EncryptedMessageExport.decodePayload(Data(#"{"version":1,"platform":"android","messages":[]}"#.utf8), myKey: me)
            check("v2.dispatch.v1android.refused", false)
        } catch EncryptedMessageExport.ExportError.otherPlatform(let p) { check("v2.dispatch.v1android.refused", p == "android") }
        catch { check("v2.dispatch.v1android.refused", false) }
        do {
            _ = try EncryptedMessageExport.decodePayload(Data(#"{"version":3,"platform":"ios"}"#.utf8), myKey: me)
            check("v2.dispatch.v3.refused", false)
        } catch EncryptedMessageExport.ExportError.unsupportedVersion { check("v2.dispatch.v3.refused", true) }
        catch { check("v2.dispatch.v3.refused", false) }
        return lines
    }
}
#endif

// MARK: - At-rest encryption

/// __AT_REST_ENCRYPTION_2026_09_22__ Chiffre ce que `FileStorage` écrit sous
/// `Documents/<clé>.json`: l'historique 1:1 (`messages`), les groupes
/// (`groupMessages_v2`, `messageGroups_v2`), la file de déchiffrement, etc.
///
/// Avant: du JSON en clair avec `plaintextContent`, protégé seulement par la
/// classe de protection iOS « jusqu'au premier déverrouillage » — lisible par
/// tout ce qui lit le conteneur d'un téléphone déverrouillé une fois depuis le
/// démarrage (extraction forensique, sauvegarde, outil de bureau).
///
/// Clé: 32 octets aléatoires dans le trousseau, `AfterFirstUnlockThisDeviceOnly`
/// — jamais sauvegardée, jamais migrée vers un autre appareil. Même classe de
/// disponibilité que les fichiers: si le fichier se lit, la clé aussi, donc pas
/// de nouvelle fenêtre où l'app démarrerait « vide » et écraserait l'historique.
///
/// Format: "OSHIENC1" | AES-256-GCM combiné (nonce 12 ‖ ct ‖ tag 16), AAD =
/// l'en-tête. Un fichier sans l'en-tête est l'ancien format en clair: il est lu
/// tel quel une fois, puis réécrit chiffré à la sauvegarde suivante.
final class AtRestCipher {
    static let shared = AtRestCipher()
    private static let magic = Data("OSHIENC1".utf8)
    private static let keychainKey = "oshi.storage.atRestKey.v1"

    enum CipherError: Error { case keyUnavailable, corrupted }

    private let lock = NSLock()
    private var cachedKey: SymmetricKey?

    private func key() throws -> SymmetricKey {
        lock.lock(); defer { lock.unlock() }
        if let k = cachedKey { return k }
        do {
            let raw = try KeychainHelper.load(key: Self.keychainKey)
            guard raw.count == 32 else { throw CipherError.keyUnavailable }
            cachedKey = SymmetricKey(data: raw)
        } catch KeychainError.itemNotFound {
            // Première utilisation. Un « verrouillé » n'arrive JAMAIS ici: on ne
            // remplace pas une clé qu'on ne peut simplement pas encore lire.
            let fresh = SymmetricKey(size: .bits256)
            try KeychainHelper.save(key: Self.keychainKey, data: fresh.withUnsafeBytes { Data($0) })
            cachedKey = fresh
            OshiLog.storage.info("🔐 At-rest storage key created")
        } catch {
            throw CipherError.keyUnavailable
        }
        return cachedKey!
    }

    /// __AT_REST_KEY_SURVIVED_WIPE_2026_11_10__ Oublie la clé en mémoire.
    ///
    /// Le mode panique efface TOUT le trousseau, dont `keychainKey`, mais ce cache
    /// survivait: l'utilisateur recréait une identité dans le même processus et
    /// tout ce qu'il enregistrait était scellé avec l'ANCIENNE clé, déjà détruite.
    /// Au lancement suivant une clé neuve était créée et ces fichiers ne
    /// s'ouvraient plus (`load` rend nil): conversations et contacts vides, en
    /// silence. Même défaut que la clé SQLCipher d'Android (10/11). À appeler
    /// juste après l'effacement du trousseau.
    func forgetCachedKey() {
        lock.lock(); defer { lock.unlock() }
        cachedKey = nil
    }

    /// __MEDIA_AT_REST_2026_09_22__ Sous-clé dérivée de la clé au repos (HKDF-SHA256),
    /// pour que les médias (`MediaCipher`) n'utilisent jamais la clé maîtresse
    /// directement. Même disponibilité que la clé: lève `keyUnavailable` avant le
    /// premier déverrouillage.
    func deriveSubkey(salt: Data, info: String) throws -> SymmetricKey {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: try key(), salt: salt,
                               info: Data(info.utf8), outputByteCount: 32)
    }

    func seal(_ plaintext: Data) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: key(), authenticating: Self.magic)
        guard let combined = box.combined else { throw CipherError.corrupted }
        return Self.magic + combined
    }

    /// Déchiffre; laisse passer tel quel un fichier de l'ancien format en clair.
    func open(_ stored: Data) throws -> Data {
        guard stored.count >= Self.magic.count, stored.prefix(Self.magic.count) == Self.magic else {
            return stored
        }
        let body = stored.suffix(from: stored.startIndex + Self.magic.count)
        do {
            let box = try AES.GCM.SealedBox(combined: Data(body))
            return try AES.GCM.open(box, using: key(), authenticating: Self.magic)
        } catch let e as CipherError {
            throw e
        } catch {
            throw CipherError.corrupted
        }
    }
}

/// __PLAINTEXT_LEFTOVERS_2026_09_24__ Petits magasins JSON hors `FileStorage`
/// (messages programmés, rappels d'appel, file de push, liens de profil,
/// signalements, identités périmées): écrits en clair dans `Documents/`. Même
/// format `OSHIENC1` que `FileStorage`; un fichier de l'ancien format est lu
/// tel quel et rechiffré sur place à la première lecture.
enum AtRestFile {
    static func write(_ data: Data, to url: URL) throws {
        try AtRestCipher.shared.seal(data)
            .write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    static func read(_ url: URL) throws -> Data {
        let raw = try Data(contentsOf: url)
        let plain = try AtRestCipher.shared.open(raw)
        if plain == raw, !raw.isEmpty { try? write(raw, to: url) }  // migration de l'ancien clair
        return plain
    }
}

// MARK: - Media at-rest encryption

/// __MEDIA_AT_REST_2026_09_22__ Chiffre au repos les MÉDIAS des messages: photos,
/// vidéos, notes vocales, documents, miniatures, médias de groupe, photos de
/// contact, fonds d'écran personnalisés.
///
/// Avant: `Documents/MediaMessages/<id>.mp4|jpg|gif`, `AudioMessages/<id>.m4a`,
/// `GroupMedia/`… écrits par `mediaData.write(to:)` SANS option — en clair, et
/// même pas en classe « jusqu'au premier déverrouillage » explicite. Les JSON
/// étaient chiffrés (`AtRestCipher`), les octets les plus sensibles non.
///
/// Format "OSHIMED1" v1, par BLOCS pour ne jamais tenir une vidéo de plusieurs
/// centaines de Mo en mémoire (ni au chiffrement, ni à la migration):
///
///     en-tête (32 o) = "OSHIMED1" (8) | version 0x01 (1) | réservé 0x000000 (3)
///                    | taille de bloc T, u32 big-endian (4) | graine du fichier (16, aléatoire)
///     puis N ≥ 1 blocs = AES-256-GCM(clair_i) ‖ tag (16)
///         clair_i fait EXACTEMENT T octets, sauf le dernier: 0…T octets
///         (un fichier vide = un seul bloc final de 0 octet, donc 16 octets de tag).
///
///     clé du fichier = HKDF-SHA256(clé `AtRestCipher`, sel = graine, info = "oshi-media-at-rest-v1")
///     nonce du bloc i = graine[0..<8] ‖ i (u32 big-endian)
///     AAD du bloc i   = en-tête entier (32) ‖ i (u32 big-endian) ‖ final (1 octet: 1 = dernier bloc, 0 sinon)
///
/// Ce que ça interdit: TRONQUER à une frontière de bloc (le nouveau dernier bloc
/// a été scellé avec final = 0 → échec), AJOUTER un bloc (l'ancien dernier était
/// final = 1), PERMUTER deux blocs (l'indice est dans le nonce ET l'AAD), changer
/// la taille de bloc ou la graine (l'en-tête est dans chaque AAD). Une clé par
/// fichier: aucune réutilisation de nonce possible d'un fichier à l'autre.
///
/// Un fichier qui ne commence pas par "OSHIMED1" est l'ancien format en clair:
/// lu tel quel (`read`, `open`), puis rechiffré en tâche de fond par
/// `MediaAtRestMigration`. Aucune écriture ne produit plus jamais de clair.
enum MediaCipher {
    static let magic = Data("OSHIMED1".utf8)
    static let version: UInt8 = 1
    static let headerLength = 32
    static let tagLength = 16
    static let defaultChunkSize = 256 * 1024
    private static let maxChunkSize = 16 * 1024 * 1024
    private static let hkdfInfo = "oshi-media-at-rest-v1"

    enum MediaCipherError: Error, Equatable { case keyUnavailable, corrupted, io(String) }

    /// Sérialise le RENOMMAGE final entre les écrivains et la migration: la
    /// migration revérifie sous ce verrou que le fichier n'a pas changé avant de
    /// le remplacer, un écrivain ne peut donc pas voir sa version écrasée par un
    /// rechiffrement de l'ancienne.
    static let replaceLock = NSLock()

    struct Header {
        let raw: Data
        let chunkSize: Int
        let seed: Data
    }

    // MARK: API

    static func isEncrypted(_ data: Data) -> Bool {
        data.count >= magic.count && data.prefix(magic.count) == magic
    }

    /// Lève si le fichier ne s'ouvre pas (absent, ou appareil verrouillé).
    static func isEncryptedFile(at url: URL) throws -> Bool {
        let h = try FileHandle(forReadingFrom: url)
        defer { try? h.close() }
        return try readFully(h, magic.count) == magic
    }

    /// Chiffre en mémoire (petites charges, auto-test).
    static func seal(_ plaintext: Data, chunkSize: Int = defaultChunkSize) throws -> Data {
        let src = Data(plaintext)
        var out = Data()
        out.reserveCapacity(headerLength + src.count + (src.count / chunkSize + 1) * tagLength)
        var offset = 0
        try encrypt(chunkSize: chunkSize, next: { n in
            let end = min(offset + n, src.count)
            defer { offset = end }
            return src.subdata(in: offset..<end)
        }, emit: { out.append($0) })
        return out
    }

    /// Déchiffre en mémoire; laisse passer tel quel l'ancien format en clair.
    static func open(_ stored: Data) throws -> Data {
        guard isEncrypted(stored) else { return stored }
        let src = Data(stored)
        guard src.count >= headerLength else { throw MediaCipherError.corrupted }
        let header = try parseHeader(src.subdata(in: 0..<headerLength))
        var offset = headerLength
        var out = Data()
        out.reserveCapacity(src.count - headerLength)
        try decrypt(header: header, bodyLength: src.count - headerLength, read: { n in
            let end = min(offset + n, src.count)
            defer { offset = end }
            return src.subdata(in: offset..<end)
        }, emit: { out.append($0) })
        return out
    }

    /// Écrit `plaintext` chiffré à `url`, bloc par bloc (pic mémoire ≈ un bloc),
    /// dans un fichier de préparation renommé atomiquement à la fin: un lecteur
    /// voit l'ancien fichier entier ou le nouveau entier, jamais un demi-fichier.
    /// Sans clé (avant le premier déverrouillage), lève et n'écrit RIEN — jamais
    /// de repli en clair.
    static func write(_ plaintext: Data, to url: URL,
                      protection: FileProtectionType = .completeUntilFirstUserAuthentication) throws {
        let src = Data(plaintext)
        let part = stagingURL()
        try createEmptyFile(at: part, protection: protection)
        do {
            let h = try FileHandle(forWritingTo: part)
            do {
                var offset = 0
                try encrypt(chunkSize: defaultChunkSize, next: { n in
                    let end = min(offset + n, src.count)
                    defer { offset = end }
                    return src.subdata(in: offset..<end)
                }, emit: { try h.write(contentsOf: $0) })
                try h.synchronize()
                try h.close()
            } catch {
                try? h.close()
                throw error
            }
            replaceLock.lock()
            defer { replaceLock.unlock() }
            try replace(url, with: part)
        } catch {
            try? FileManager.default.removeItem(at: part)
            throw error
        }
    }

    /// Lit et déchiffre un fichier média; un fichier de l'ancien format en clair
    /// est rendu tel quel. Pic mémoire ≈ le clair + un bloc.
    static func read(contentsOf url: URL) throws -> Data {
        let h = try FileHandle(forReadingFrom: url)
        defer { try? h.close() }
        let size = Int(try h.seekToEnd())
        try h.seek(toOffset: 0)
        let head = try readFully(h, min(headerLength, size))
        guard isEncrypted(head) else {
            return try Data(contentsOf: url)
        }
        guard head.count == headerLength else { throw MediaCipherError.corrupted }
        let header = try parseHeader(head)
        var out = Data()
        out.reserveCapacity(size - headerLength)
        try decrypt(header: header, bodyLength: size - headerLength,
                    read: { try readFully(h, $0) }, emit: { out.append($0) })
        return out
    }

    /// Chiffre un fichier en CLAIR vers `dst`, en flux. Rend l'empreinte et la
    /// longueur du clair LU, pour que l'appelant vérifie avant de remplacer.
    @discardableResult
    static func encryptFile(at src: URL, to dst: URL,
                            protection: FileProtectionType = .completeUntilFirstUserAuthentication)
    throws -> (digest: SHA256.Digest, length: Int) {
        let input = try FileHandle(forReadingFrom: src)
        defer { try? input.close() }
        try createEmptyFile(at: dst, protection: protection)
        var hasher = SHA256()
        var total = 0
        do {
            let output = try FileHandle(forWritingTo: dst)
            do {
                try encrypt(chunkSize: defaultChunkSize, next: { n in
                    let d = try readFully(input, n)
                    hasher.update(data: d)
                    total += d.count
                    return d
                }, emit: { try output.write(contentsOf: $0) })
                try output.synchronize()
                try output.close()
            } catch {
                try? output.close()
                throw error
            }
        } catch {
            try? FileManager.default.removeItem(at: dst)
            throw error
        }
        return (hasher.finalize(), total)
    }

    /// Déchiffre `src` en flux vers `dst` (ou seulement vers l'empreinte si
    /// `dst` est nil — vérification de la migration). Accepte l'ancien format.
    @discardableResult
    static func decryptFile(at src: URL, to dst: URL?,
                            protection: FileProtectionType = .completeUnlessOpen)
    throws -> (digest: SHA256.Digest, length: Int) {
        let input = try FileHandle(forReadingFrom: src)
        defer { try? input.close() }
        let size = Int(try input.seekToEnd())
        try input.seek(toOffset: 0)
        let head = try readFully(input, min(headerLength, size))
        var output: FileHandle?
        if let dst {
            try createEmptyFile(at: dst, protection: protection)
            output = try FileHandle(forWritingTo: dst)
        }
        var hasher = SHA256()
        var total = 0
        let sink: (Data) throws -> Void = { d in
            hasher.update(data: d)
            total += d.count
            try output?.write(contentsOf: d)
        }
        do {
            if isEncrypted(head) {
                guard head.count == headerLength else { throw MediaCipherError.corrupted }
                try decrypt(header: try parseHeader(head), bodyLength: size - headerLength,
                            read: { try readFully(input, $0) }, emit: sink)
            } else {
                try sink(head)
                while true {
                    let d = try readFully(input, defaultChunkSize)
                    if d.isEmpty { break }
                    try sink(d)
                }
            }
            try output?.close()
        } catch {
            try? output?.close()
            if let dst { try? FileManager.default.removeItem(at: dst) }
            throw error
        }
        return (hasher.finalize(), total)
    }

    // MARK: Fichiers de préparation

    /// `Documents/.oshi-media-staging/`: même volume que tous les dossiers de
    /// médias (le renommage final est atomique) mais HORS de ces dossiers, que
    /// des nettoyages énumèrent et purgent par identifiant de message.
    static var stagingDirectory: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(".oshi-media-staging", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    static func stagingURL() -> URL {
        stagingDirectory.appendingPathComponent("\(UUID().uuidString).oshipart")
    }

    /// rename(2): remplace `url` atomiquement (et le crée s'il n'existe pas).
    static func replace(_ url: URL, with part: URL) throws {
        let dir = url.deletingLastPathComponent()
        if !FileManager.default.fileExists(atPath: dir.path) {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        guard rename(part.path, url.path) == 0 else {
            throw MediaCipherError.io("rename: errno \(errno)")
        }
    }

    private static func createEmptyFile(at url: URL, protection: FileProtectionType) throws {
        try? FileManager.default.removeItem(at: url)
        guard FileManager.default.createFile(atPath: url.path, contents: nil,
                                             attributes: [.protectionKey: protection]) else {
            throw MediaCipherError.io("create \(url.lastPathComponent)")
        }
    }

    // MARK: Cœur

    private static func readFully(_ h: FileHandle, _ count: Int) throws -> Data {
        var out = Data()
        while out.count < count {
            guard let d = try h.read(upToCount: count - out.count), !d.isEmpty else { break }
            out.append(d)
        }
        return out
    }

    private static func bigEndian(_ v: UInt32) -> Data {
        withUnsafeBytes(of: v.bigEndian) { Data($0) }
    }

    private static func makeHeader(chunkSize: Int) throws -> Header {
        guard chunkSize > 0, chunkSize <= maxChunkSize else { throw MediaCipherError.io("chunk size") }
        var seed = Data(count: 16)
        let ok = seed.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        guard ok == errSecSuccess else { throw MediaCipherError.io("random") }
        var raw = magic
        raw.append(version)
        raw.append(contentsOf: [0, 0, 0])
        raw.append(bigEndian(UInt32(chunkSize)))
        raw.append(seed)
        return Header(raw: raw, chunkSize: chunkSize, seed: seed)
    }

    private static func parseHeader(_ bytes: Data) throws -> Header {
        let raw = Data(bytes)
        guard raw.count == headerLength, raw.prefix(magic.count) == magic,
              raw[8] == version, raw[9] == 0, raw[10] == 0, raw[11] == 0 else {
            throw MediaCipherError.corrupted
        }
        let chunkSize = raw[12..<16].reduce(0) { ($0 << 8) | Int($1) }
        guard chunkSize > 0, chunkSize <= maxChunkSize else { throw MediaCipherError.corrupted }
        return Header(raw: raw, chunkSize: chunkSize, seed: raw.subdata(in: 16..<32))
    }

    private static func fileKey(_ header: Header) throws -> SymmetricKey {
        do {
            return try AtRestCipher.shared.deriveSubkey(salt: header.seed, info: hkdfInfo)
        } catch {
            throw MediaCipherError.keyUnavailable
        }
    }

    private static func nonce(_ header: Header, _ index: UInt32) throws -> AES.GCM.Nonce {
        try AES.GCM.Nonce(data: header.seed.subdata(in: 0..<8) + bigEndian(index))
    }

    private static func aad(_ header: Header, _ index: UInt32, final: Bool) -> Data {
        var a = header.raw
        a.append(bigEndian(index))
        a.append(final ? 1 : 0)
        return a
    }

    /// `next(n)` rend jusqu'à n octets de clair (vide = fin). Lecture d'un bloc
    /// d'avance: un bloc n'est scellé qu'une fois connu s'il est le DERNIER.
    private static func encrypt(chunkSize: Int, next: (Int) throws -> Data,
                                emit: (Data) throws -> Void) throws {
        let header = try makeHeader(chunkSize: chunkSize)
        let key = try fileKey(header)
        try emit(header.raw)
        var index: UInt32 = 0
        var current = try next(chunkSize)
        while true {
            let following = current.count == chunkSize ? try next(chunkSize) : Data()
            let isFinal = following.isEmpty
            try autoreleasepool {
                let box = try AES.GCM.seal(current, using: key, nonce: nonce(header, index),
                                           authenticating: aad(header, index, final: isFinal))
                try emit(box.ciphertext + box.tag)
            }
            if isFinal { return }
            guard index < UInt32.max else { throw MediaCipherError.io("too many chunks") }
            index += 1
            current = following
        }
    }

    /// Le découpage vient de la LONGUEUR du corps: tous les blocs font T + 16
    /// sauf le dernier (16…T + 16). Le dernier est ouvert avec final = 1, les
    /// autres avec final = 0 — c'est ce qui fait échouer toute troncature.
    private static func decrypt(header: Header, bodyLength: Int, read: (Int) throws -> Data,
                                emit: (Data) throws -> Void) throws {
        let stride = header.chunkSize + tagLength
        guard bodyLength >= tagLength else { throw MediaCipherError.corrupted }
        let count = (bodyLength + stride - 1) / stride
        guard count - 1 <= Int(UInt32.max) else { throw MediaCipherError.corrupted }
        let lastLength = bodyLength - (count - 1) * stride
        guard lastLength >= tagLength else { throw MediaCipherError.corrupted }
        let key = try fileKey(header)
        for i in 0..<count {
            let isFinal = i == count - 1
            let length = isFinal ? lastLength : stride
            let chunk = try read(length)
            guard chunk.count == length else { throw MediaCipherError.corrupted }
            try autoreleasepool {
                do {
                    let box = try AES.GCM.SealedBox(nonce: nonce(header, UInt32(i)),
                                                    ciphertext: chunk.prefix(length - tagLength),
                                                    tag: chunk.suffix(tagLength))
                    try emit(try AES.GCM.open(box, using: key,
                                              authenticating: aad(header, UInt32(i), final: isFinal)))
                } catch let e as MediaCipherError {
                    throw e
                } catch {
                    throw MediaCipherError.corrupted
                }
            }
        }
    }
}

/// __MEDIA_AT_REST_2026_09_22__ Copies EN CLAIR temporaires, quand une API
/// système exige une URL de fichier: AVPlayer, AVAudioPlayer,
/// AVAssetImageGenerator, QuickLook, feuille de partage.
///
/// Choix: déchiffrer vers un fichier temporaire protégé plutôt qu'un
/// `AVAssetResourceLoaderDelegate`. Le délégué ne sert qu'AVPlayer/AVURLAsset;
/// AVAudioPlayer, QuickLook, `UIActivityViewController` et `CGPDFDocument`
/// veulent un VRAI fichier. Il faut de toute façon cette voie pour eux; une
/// seule voie, éprouvée partout, vaut mieux qu'un schéma d'URL maison propre à
/// la vidéo (AirPlay, recherche dans le flux, requêtes de plage à servir à la
/// main). Et tous ces appelants détiennent DÉJÀ les octets en mémoire (les
/// bulles lisent `Data`): la seule nouveauté est OÙ atterrit la copie.
///
/// Avant: `temporaryDirectory/<nom>` à la racine, sans protection, souvent
/// jamais supprimé (lecteur vidéo plein écran, feuilles de partage, miniatures
/// vidéo de la galerie). Maintenant: `tmp/OSHIPlainMedia/<uuid>/<nom>` en classe
/// `completeUnlessOpen` — illisible une fois l'appareil verrouillé, SAUF pour un
/// fichier déjà ouvert (une note vocale ou une vidéo en cours de lecture ne se
/// coupe pas au verrouillage, ce que `.complete` aurait fait). Supprimé par
/// l'appelant quand il a fini, et TOUT le dossier est balayé au lancement
/// suivant (`sweepLeftovers`), avec les anciennes copies laissées à la racine.
enum MediaPlaintextTemp {
    static let protection = FileProtectionType.completeUnlessOpen

    static var directory: URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("OSHIPlainMedia", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true,
                                                     attributes: [.protectionKey: protection])
        }
        return dir
    }

    /// Emplacement neuf dans un sous-dossier unique: le nom d'origine est gardé
    /// (c'est lui que la feuille de partage montre) sans collision possible.
    static func url(fileName: String) -> URL {
        let sub = directory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: sub, withIntermediateDirectories: true,
                                                 attributes: [.protectionKey: protection])
        var name = (fileName as NSString).lastPathComponent.replacingOccurrences(of: ":", with: "_")
        if name.isEmpty || name == "." || name == ".." { name = "file" }
        return sub.appendingPathComponent(name)
    }

    static func write(_ data: Data, fileName: String) throws -> URL {
        let url = url(fileName: fileName)
        do {
            try data.write(to: url, options: [.completeFileProtectionUnlessOpen])
        } catch {
            remove(url)
            throw error
        }
        return url
    }

    /// Déchiffre en flux un fichier du magasin vers une copie temporaire (vidéo
    /// de plusieurs centaines de Mo: jamais entière en mémoire).
    static func decryptedCopy(of stored: URL, fileName: String? = nil) throws -> URL {
        let url = url(fileName: fileName ?? stored.lastPathComponent)
        do {
            try MediaCipher.decryptFile(at: stored, to: url, protection: protection)
        } catch {
            remove(url)
            throw error
        }
        return url
    }

    /// Supprime la copie ET son sous-dossier unique.
    static func remove(_ url: URL) {
        let fm = FileManager.default
        try? fm.removeItem(at: url)
        let parent = url.deletingLastPathComponent()
        let grand = parent.deletingLastPathComponent().standardizedFileURL.path
        guard parent.lastPathComponent != "work" else { return }  // dossier partagé des `scratchURL`
        if grand == directory.standardizedFileURL.path || grand == shareDirectory.standardizedFileURL.path {
            try? fm.removeItem(at: parent)
        }
    }

    /// __PLAINTEXT_LEFTOVERS_2026_09_24__ Fichier de travail pour les sorties
    /// d'AVFoundation (export vidéo, rendu d'effet vocal, forme d'onde): avant,
    /// `tmp/<uuid>.mp4|caf|m4a` à la racine, sans protection, et laissé sur place
    /// à chaque chemin d'erreur. Dans `OSHIPlainMedia/work/` (même balayage), en
    /// classe « après 1er déverrouillage » et non `completeUnlessOpen`: une
    /// compression de vidéo dure, l'utilisateur verrouille, et la relecture du
    /// fichier FERMÉ échouerait — l'envoi serait perdu. Supprimé après usage.
    static func scratchURL(ext: String) -> URL {
        let work = directory.appendingPathComponent("work", isDirectory: true)
        if !FileManager.default.fileExists(atPath: work.path) {
            try? FileManager.default.createDirectory(at: work, withIntermediateDirectories: true,
                                                     attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        }
        return work.appendingPathComponent("\(UUID().uuidString).\(ext)")
    }

    /// Vrai pour une copie de CE dossier (jamais une copie de partage).
    static func owns(_ url: URL) -> Bool {
        url.resolvingSymlinksInPath().path.hasPrefix(directory.resolvingSymlinksInPath().path + "/")
    }

    /// Consommé → supprimé: pour les copies qu'un appelant lit une fois (sélecteur
    /// de document, glisser-déposer) puis n'utilise plus.
    static func removeIfOwned(_ url: URL) {
        if owns(url) { remove(url) }
    }

    /// __PLAINTEXT_LEFTOVERS_2026_09_24__ Partage vers une AUTRE app (feuille de
    /// partage, « Enregistrer dans Fichiers », AirDrop): le fichier doit exister
    /// tant que l'autre app le lit, et on ne sait pas quand elle a fini. Il vit
    /// donc dans `tmp/OSHIShareOut/<uuid>/`, purgé au partage SUIVANT et au
    /// lancement suivant — jamais plus d'un partage en clair à la fois.
    static var shareDirectory: URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("OSHIShareOut", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true,
                                                     attributes: [.protectionKey: protection])
        }
        return dir
    }

    static func writeForShare(_ data: Data, fileName: String) throws -> URL {
        purgeShares()
        let sub = shareDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: sub, withIntermediateDirectories: true,
                                                attributes: [.protectionKey: protection])
        var name = (fileName as NSString).lastPathComponent.replacingOccurrences(of: ":", with: "_")
        if name.isEmpty || name == "." || name == ".." { name = "file" }
        let url = sub.appendingPathComponent(name)
        do {
            try data.write(to: url, options: [.completeFileProtectionUnlessOpen])
        } catch {
            remove(url)
            throw error
        }
        return url
    }

    /// Supprime les partages précédents (tous, ou ceux d'avant `cutoff`).
    @discardableResult
    static func purgeShares(olderThan cutoff: Date = .distantFuture) -> Int {
        let fm = FileManager.default
        let keys: [URLResourceKey] = [.contentModificationDateKey]
        guard let subs = try? fm.contentsOfDirectory(at: shareDirectory, includingPropertiesForKeys: keys) else { return 0 }
        var removed = 0
        for sub in subs {
            let date = (try? sub.resourceValues(forKeys: Set(keys)))?.contentModificationDate ?? .distantPast
            if date < cutoff, (try? fm.removeItem(at: sub)) != nil { removed += 1 }
        }
        return removed
    }

    /// Heure de démarrage du PROCESSUS (sysctl): tout ce qui est plus ancien a
    /// été laissé par un lancement précédent et n'est plus utilisé par personne.
    static let processStart: Date = {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        if sysctl(&mib, 4, &info, &size, nil, 0) == 0 {
            let tv = info.kp_proc.p_un.__p_starttime
            let start = Date(timeIntervalSince1970: TimeInterval(tv.tv_sec) + TimeInterval(tv.tv_usec) / 1_000_000)
            if start.timeIntervalSince1970 > 0 { return start }
        }
        return Date()
    }()

    /// Extensions des copies de médias que les anciennes versions laissaient à
    /// la RACINE de tmp (lecteurs, partages, miniatures, captures à envoyer).
    private static let legacyLooseExtensions: Set<String> = [
        "mp4", "mov", "m4v", "m4a", "aac", "caf", "wav", "mp3", "jpg", "jpeg", "png", "gif", "heic", "heif",
        "webp", "pdf", "dat", "vcf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv",
        "zip", "pages", "numbers", "key", "ics"
    ]

    /// Balayage au lancement: tout `OSHIPlainMedia/`, l'ancien `oshi-media/`,
    /// les dossiers `v2send-*` / `v2groupsend-*` (clair `plain.bin` des envois
    /// interrompus), et les copies de médias en vrac à la racine — seulement ce
    /// qui date d'AVANT ce processus. `oshi_v2_chunk_*` (déjà chiffré, peut
    /// servir à une session d'envoi en arrière-plan) n'est jamais touché.
    @discardableResult
    static func sweepLeftovers(olderThan cutoff: Date = processStart) -> Int {
        let fm = FileManager.default
        let keys: [URLResourceKey] = [.contentModificationDateKey, .isDirectoryKey]
        func isOld(_ u: URL) -> Bool {
            ((try? u.resourceValues(forKeys: Set(keys)))?.contentModificationDate ?? .distantPast) < cutoff
        }
        var removed = 0
        if let subs = try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys) {
            for sub in subs where sub.lastPathComponent != "work" && isOld(sub) {
                if (try? fm.removeItem(at: sub)) != nil { removed += 1 }
            }
        }
        let work = directory.appendingPathComponent("work", isDirectory: true)
        for item in (try? fm.contentsOfDirectory(at: work, includingPropertiesForKeys: keys)) ?? [] where isOld(item) {
            if (try? fm.removeItem(at: item)) != nil { removed += 1 }
        }
        removed += purgeShares(olderThan: cutoff)  // __PLAINTEXT_LEFTOVERS_2026_09_24__
        guard let items = try? fm.contentsOfDirectory(at: fm.temporaryDirectory, includingPropertiesForKeys: keys) else {
            return removed
        }
        for item in items {
            let name = item.lastPathComponent
            if name == "OSHIPlainMedia" || name == "OSHIShareOut" || name.hasPrefix("oshi_v2_chunk_") { continue }
            let isDir = (try? item.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory ?? false
            // __PLAINTEXT_LEFTOVERS_2026_09_24__ + les dossiers `tmp/<UUID>/` des
            // anciennes versions (aperçu QuickLook du Drive mail, extraction
            // docx/pptx de l'IA) et les `.ics` de rappel d'appel partagés.
            let target = isDir
                ? (name == "oshi-media" || name.hasPrefix("v2send-") || name.hasPrefix("v2groupsend-")
                   || UUID(uuidString: name) != nil)
                : (name.hasPrefix("playback_") || legacyLooseExtensions.contains(item.pathExtension.lowercased()))
            guard target, isOld(item) else { continue }
            if (try? fm.removeItem(at: item)) != nil { removed += 1 }
        }
        return removed
    }
}

/// __PLAINTEXT_LEFTOVERS_2026_09_24__ Clair laissé HORS de `tmp/` par les
/// versions précédentes, supprimé au lancement (rien de tout ça n'est une
/// donnée à garder: chaque octet a déjà été lu et rangé ailleurs, chiffré).
///   - `Documents/recording_<uuid>.m4a`: chaque note vocale enregistrée, en
///     clair, jamais effacée (l'enregistreur lisait le fichier puis l'oubliait).
///   - `Application Support/blobdl/<id>/plain.bin`: média V2 déchiffré, laissé
///     si l'app mourait entre le déchiffrement et sa lecture.
///   - App Group `shared_*`: entrées de l'extension de partage jamais consommées
///     (la fenêtre de reprise est de 5 minutes; on garde une heure de marge).
enum PlaintextLeftovers {
    static let appGroupIdentifier = "group.Moriceau-lab.Genesis"

    @discardableResult
    static func sweep(olderThan cutoff: Date = MediaPlaintextTemp.processStart) -> Int {
        let fm = FileManager.default
        var removed = 0
        func date(_ u: URL) -> Date {
            (try? u.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
        }
        let docs = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        if let items = try? fm.contentsOfDirectory(at: docs, includingPropertiesForKeys: [.contentModificationDateKey]) {
            for u in items where u.lastPathComponent.hasPrefix("recording_") && u.pathExtension == "m4a" && date(u) < cutoff {
                if (try? fm.removeItem(at: u)) != nil { removed += 1 }
            }
        }
        let blobdl = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("blobdl", isDirectory: true)
        if let dirs = try? fm.contentsOfDirectory(at: blobdl, includingPropertiesForKeys: nil) {
            for d in dirs {
                let plain = d.appendingPathComponent("plain.bin")
                if fm.fileExists(atPath: plain.path), date(plain) < cutoff,
                   (try? fm.removeItem(at: plain)) != nil { removed += 1 }
            }
        }
        if let group = fm.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier),
           let items = try? fm.contentsOfDirectory(at: group, includingPropertiesForKeys: [.contentModificationDateKey]) {
            let stale = Date().addingTimeInterval(-3600)
            for u in items where u.lastPathComponent.hasPrefix("shared_") && date(u) < stale {
                if (try? fm.removeItem(at: u)) != nil { removed += 1 }
            }
        }
        return removed
    }

    /// Réglages à appliquer AVANT la première requête réseau (appelé depuis
    /// `App.init` et `didFinishLaunching`, idempotent).
    ///
    /// `URLCache.shared` gardait sur disque (`Caches/<bundle>/Cache.db` +
    /// `fsCachedData/`) les réponses de `URLSession.shared`: pages et images des
    /// liens reçus en message (aperçus), réponses du relais. Mémoire seulement,
    /// et l'ancien cache disque est vidé une fois.
    static func applyAtLaunch() { _ = once }

    private static let once: Void = {
        URLCache.shared.removeAllCachedResponses()
        URLCache.shared = URLCache(memoryCapacity: 16 * 1024 * 1024, diskCapacity: 0, directory: nil)
        // Couches de révélation progressive: chiffrées au repos (ancien clair lisible).
        RevealOutbox.seal = { try MediaCipher.seal($0) }
        RevealOutbox.open = { MediaCipher.isEncrypted($0) ? try MediaCipher.open($0) : $0 }
        MediaAtRestMigration.startAtLaunch()
    }()
}

/// __MEDIA_AT_REST_2026_09_22__ Rechiffre en tâche de fond les médias restés
/// dans l'ancien format en clair. Par fichier: chiffre vers un fichier de
/// préparation en calculant l'empreinte du clair lu, redéchiffre ce fichier et
/// compare empreinte et longueur, puis — sous `MediaCipher.replaceLock`, après
/// avoir revérifié que l'original n'a pas bougé (taille, date, toujours en
/// clair) — le renomme par-dessus l'original. Tout échec laisse l'original
/// INTACT: on ne supprime jamais un média qu'on n'a pas su rechiffrer.
///
/// Appareil verrouillé (avant le premier déverrouillage: trousseau et fichiers
/// indisponibles): la passe s'arrête sans rien toucher et reprend à
/// `protectedDataDidBecomeAvailable`, ou au lancement suivant.
enum MediaAtRestMigration {
    private static let queue = DispatchQueue(label: "com.oshi.media.atrest", qos: .utility)
    private static var observer: NSObjectProtocol?

    struct PassResult { var encrypted = 0, alreadyEncrypted = 0, failed = 0, deferred = false }
    enum Outcome { case encrypted, alreadyEncrypted, unavailable, failed }

    /// Dossiers de `Documents/` qui ne contiennent QUE des médias.
    static let mediaDirectoryNames = [
        "MediaMessages", "AudioMessages", "GroupMedia", "Thumbnails", "Media",
        "ContactPhotos", "Attachments", "SharedDocuments"
    ]

    private static var started = false  // __PLAINTEXT_LEFTOVERS_2026_09_24__ appelé de plusieurs endroits

    static func startAtLaunch() {
        let first: Bool = queue.sync { defer { started = true }; return !started }
        guard first else { return }
        queue.async {
            let swept = MediaPlaintextTemp.sweepLeftovers()
            let staged = cleanStaging()
            let other = PlaintextLeftovers.sweep()  // __PLAINTEXT_LEFTOVERS_2026_09_24__
            if swept + staged + other > 0 {
                OshiLog.storage.info("🧹 Removed \(swept) leftover plaintext media copies, \(staged) staging files, \(other) other plaintext leftovers")
            }
        }
        DispatchQueue.main.async {
            guard observer == nil else { return }
            observer = NotificationCenter.default.addObserver(
                forName: UIApplication.protectedDataDidBecomeAvailableNotification,
                object: nil, queue: nil) { _ in schedule() }
        }
        schedule()
    }

    static func schedule() {
        queue.async { runPass() }
    }

    /// Fichiers de préparation d'une écriture interrompue (déjà chiffrés, inutiles).
    private static func cleanStaging() -> Int {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(at: MediaCipher.stagingDirectory,
                                                      includingPropertiesForKeys: [.contentModificationDateKey]) else { return 0 }
        var removed = 0
        for item in items {
            let date = (try? item.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
            if date < MediaPlaintextTemp.processStart, (try? fm.removeItem(at: item)) != nil { removed += 1 }
        }
        return removed
    }

    static func candidateFiles() -> [URL] {
        let fm = FileManager.default
        let docs = fm.urls(for: .documentDirectory, in: .userDomainMask)[0]
        var out: [URL] = []
        for name in mediaDirectoryNames {
            // RÉCURSIF et fichiers cachés compris: un identifiant de message tiré
            // d'une clé base64 peut contenir "/" — `MediaMessages/<id>.<ext>`
            // devient alors `MediaMessages/oshi-welcome-card-xxxx/.vcf`
            // (constaté sur simulateur le 2026-09-22).
            let dir = docs.appendingPathComponent(name, isDirectory: true)
            guard let e = fm.enumerator(at: dir, includingPropertiesForKeys: [.isRegularFileKey]) else { continue }
            for case let url as URL in e
            where (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true {
                out.append(url)
            }
        }
        // Fonds d'écran personnalisés (`ChatWallpaperManager.saveCustomImage`).
        if let items = try? fm.contentsOfDirectory(at: docs, includingPropertiesForKeys: nil) {
            out += items.filter { $0.lastPathComponent.hasPrefix("wallpaper_") && $0.pathExtension == "jpg" }
        }
        return out
    }

    @discardableResult
    static func runPass() -> PassResult {
        var result = PassResult()
        // Sonde de la clé AVANT de toucher quoi que ce soit.
        guard (try? AtRestCipher.shared.deriveSubkey(salt: Data(count: 16), info: "oshi-media-probe")) != nil else {
            result.deferred = true
            OshiLog.storage.info("🔒 Media at-rest migration deferred: key unavailable (device locked?)")
            return result
        }
        for url in candidateFiles() {
            switch migrate(url) {
            case .encrypted: result.encrypted += 1
            case .alreadyEncrypted: result.alreadyEncrypted += 1
            case .failed: result.failed += 1
            case .unavailable:
                result.deferred = true
                OshiLog.storage.info("🔒 Media at-rest migration paused (protected data unavailable)")
                return result
            }
        }
        if result.encrypted + result.failed > 0 {
            OshiLog.storage.info("🔐 Media at-rest migration: \(result.encrypted) encrypted, \(result.alreadyEncrypted) already, \(result.failed) failed (kept as-is)")
        }
        return result
    }

    static func migrate(_ url: URL) -> Outcome {
        let fm = FileManager.default
        func stamp() throws -> (size: Int, date: Date?) {
            let a = try fm.attributesOfItem(atPath: url.path)
            return ((a[.size] as? NSNumber)?.intValue ?? -1, a[.modificationDate] as? Date)
        }
        let before: (size: Int, date: Date?)
        do {
            if try MediaCipher.isEncryptedFile(at: url) { return .alreadyEncrypted }
            before = try stamp()
        } catch {
            return .failed
        }
        let part = MediaCipher.stagingURL()
        do {
            let enc = try MediaCipher.encryptFile(at: url, to: part)
            let check = try MediaCipher.decryptFile(at: part, to: nil)
            guard enc.length == before.size, check.length == enc.length, check.digest == enc.digest else {
                throw MediaCipher.MediaCipherError.corrupted
            }
            MediaCipher.replaceLock.lock()
            defer { MediaCipher.replaceLock.unlock() }
            let now = try stamp()
            guard now.size == before.size, now.date == before.date,
                  try !MediaCipher.isEncryptedFile(at: url) else {
                try? fm.removeItem(at: part)
                return .failed   // modifié entre-temps: la passe suivante le reprendra
            }
            try MediaCipher.replace(url, with: part)
            return .encrypted
        } catch {
            try? fm.removeItem(at: part)
            if (error as? MediaCipher.MediaCipherError) == .keyUnavailable { return .unavailable }
            OshiLog.storage.info("❌ Media at-rest migration failed for \(url.lastPathComponent): \(error)")
            return .failed
        }
    }
}

#if DEBUG
/// __AT_REST_ENCRYPTION_2026_09_22__ Auto-test lancé par `-OSHISelfTestStorage`
/// (le target de tests n'est pas synchronisé au dossier). Écrit
/// `Documents/selftest_storage.txt`: une ligne PASS/FAIL par vérification.
enum StorageSelfTest {
    static func runIfRequested() {
        guard ProcessInfo.processInfo.arguments.contains("-OSHISelfTestStorage") else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 12) { run() }
    }

    private static func run() {
        var lines: [String] = []
        func check(_ name: String, _ ok: Bool) { lines.append("\(ok ? "PASS" : "FAIL") \(name)") }

        // 1. Chiffrement au repos
        let sample = Data("{\"hello\":\"at-rest\"}".utf8)
        if let sealed = try? AtRestCipher.shared.seal(sample) {
            check("atRest.magic", sealed.prefix(8) == Data("OSHIENC1".utf8))
            check("atRest.noPlaintext", sealed.range(of: Data("at-rest".utf8)) == nil)
            check("atRest.roundTrip", (try? AtRestCipher.shared.open(sealed)) == sample)
            var bad = sealed; bad[bad.count - 1] ^= 0x01
            check("atRest.tamperRejected", (try? AtRestCipher.shared.open(bad)) == nil)
        } else { check("atRest.seal", false) }
        check("atRest.legacyPassthrough", (try? AtRestCipher.shared.open(sample)) == sample)

        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        for key in ["messages", "groupMessages_v2", "messageGroups_v2"] {
            let url = docs.appendingPathComponent("\(key).json")
            if let raw = try? Data(contentsOf: url) {
                check("store.\(key).encryptedOnDisk", raw.prefix(8) == Data("OSHIENC1".utf8))
            } else { lines.append("SKIP store.\(key) (absent)") }
        }
        let excluded = (try? docs.resourceValues(forKeys: [.isExcludedFromBackupKey]))?.isExcludedFromBackup ?? false
        check("documents.excludedFromBackup", excluded)

        // 2. Export chiffré
        if let identity = MessageManager.sharedIdentityManager,
           let tag = identity.messageExportAccountTag(),
           let pub = identity.privateKey?.publicKey.rawRepresentation {
            let msgs = MessageManager.shared?.messages ?? []
            do {
                let url = try EncryptedMessageExport.write(msgs, identity: identity)
                let file = try Data(contentsOf: url)
                check("export.header.magic", file.prefix(8) == Data("OSHIEXP1".utf8))
                check("export.header.version", file[8] == 1)
                check("export.header.accountTag", file[41..<49] == tag
                      && Data(SHA256.hash(data: pub).prefix(8)) == tag)
                if let first = msgs.first(where: { ($0.plaintextContent ?? "").count > 6 })?.plaintextContent {
                    check("export.noPlaintext", file.range(of: Data(first.utf8)) == nil)
                }
                // __EXPORT_V2_INTEROP_2026_09_22__ Charge v2: les charges de contrôle et
                // les messages sans pair sont écartés, jamais d'id inventé.
                let back = try EncryptedMessageExport.read(url, identity: identity)
                let ids = Set(msgs.map(\.id))
                check("export.v2.payloadVersion", back.payloadVersion == 2 && back.sourcePlatform == "ios")
                check("export.roundTrip.count(\(back.direct.count)/\(msgs.count))",
                      back.direct.count <= msgs.count && back.direct.allSatisfy { ids.contains($0.id) }
                      && (msgs.isEmpty || !back.direct.isEmpty))
                // Rétro-compatibilité: une charge v1 iOS se relit toujours.
                let v1URL = try EncryptedMessageExport.writeLegacyV1(Array(msgs.prefix(20)), identity: identity)
                let v1 = try EncryptedMessageExport.read(v1URL, identity: identity)
                check("export.v1.stillReadable", v1.payloadVersion == 1 && v1.direct.map(\.id) == Array(msgs.prefix(20)).map(\.id))
                try? FileManager.default.removeItem(at: v1URL)
                var bad = file; bad[bad.count / 2] ^= 0x01
                let badURL = url.deletingLastPathComponent().appendingPathComponent("tampered.oshiexport")
                try bad.write(to: badURL)
                check("export.tamperRejected", (try? EncryptedMessageExport.read(badURL, identity: identity)) == nil)
                var other = file; other[41] ^= 0x01
                try other.write(to: badURL)
                do { _ = try EncryptedMessageExport.read(badURL, identity: identity); check("export.otherAccountRejected", false) }
                catch EncryptedMessageExport.ExportError.otherAccount { check("export.otherAccountRejected", true) }
                catch { check("export.otherAccountRejected", false) }
                try? FileManager.default.removeItem(at: url)
                try? FileManager.default.removeItem(at: badURL)
            } catch {
                lines.append("FAIL export: \(error)")
            }
        } else { lines.append("SKIP export (no identity)") }
        lines += exportV2Checks()

        // 3. Médias au repos — __MEDIA_AT_REST_2026_09_22__
        let fm = FileManager.default
        do {
            let marker = Data("photo-at-rest-marker 0123456789".utf8)
            let sealed = try MediaCipher.seal(marker)
            check("media.magic", sealed.prefix(8) == MediaCipher.magic)
            check("media.noPlaintext", sealed.range(of: Data("photo-at-rest-marker".utf8)) == nil)
            check("media.roundTrip.small", try MediaCipher.open(sealed) == marker)
            check("media.roundTrip.empty", try MediaCipher.open(MediaCipher.seal(Data())) == Data())
            check("media.twoSealsDiffer", try MediaCipher.seal(marker) != sealed)
            check("media.legacyPassthrough", try MediaCipher.open(marker) == marker)
            let exact = Data(repeating: 7, count: MediaCipher.defaultChunkSize * 2)
            check("media.roundTrip.exactChunkMultiple", try MediaCipher.open(MediaCipher.seal(exact)) == exact)

            // Multi-bloc, en flux, fichier → fichier (5 Mo + 123 octets = 21 blocs de 256 Kio)
            var big = Data(count: 5 * 1024 * 1024 + 123)
            _ = big.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, $0.count, $0.baseAddress!) }
            let work = fm.temporaryDirectory.appendingPathComponent("selftest-media-\(UUID().uuidString)", isDirectory: true)
            try fm.createDirectory(at: work, withIntermediateDirectories: true)
            defer { try? fm.removeItem(at: work) }
            let bigURL = work.appendingPathComponent("big.mp4")
            let t0 = Date()
            try MediaCipher.write(big, to: bigURL)
            let t1 = Date()
            let onDisk = try Data(contentsOf: bigURL)
            let chunkCount = (big.count + MediaCipher.defaultChunkSize - 1) / MediaCipher.defaultChunkSize
            check("media.5MB.onDiskEncrypted(\(chunkCount) chunks, \(onDisk.count) B)",
                  MediaCipher.isEncrypted(onDisk)
                  && onDisk.count == MediaCipher.headerLength + big.count + chunkCount * MediaCipher.tagLength
                  && onDisk.range(of: big.subdata(in: 1000..<1064)) == nil)
            let back = try MediaCipher.read(contentsOf: bigURL)
            let t2 = Date()
            check("media.5MB.roundTrip", back == big)
            lines.append(String(format: "INFO media.5MB write %.0f ms, read %.0f ms", t1.timeIntervalSince(t0) * 1000, t2.timeIntervalSince(t1) * 1000))
            let copy = try MediaPlaintextTemp.decryptedCopy(of: bigURL, fileName: "big.mp4")
            check("media.5MB.streamDecryptToTemp", try Data(contentsOf: copy) == big)
            check("media.temp.inProtectedDir", copy.path.contains("/OSHIPlainMedia/"))
            let prot = (try? fm.attributesOfItem(atPath: copy.path))?[.protectionKey] as? FileProtectionType
            lines.append("INFO media.temp.protection=\(prot?.rawValue ?? "nil (simulator)")")
            MediaPlaintextTemp.remove(copy)
            check("media.temp.removedWithSubdir", !fm.fileExists(atPath: copy.deletingLastPathComponent().path))

            // Attaques — blocs de 1 Kio pour en avoir plusieurs dans un petit échantillon
            let cs = 1024, h = MediaCipher.headerLength, st = cs + MediaCipher.tagLength
            let sample = Data((0..<(cs * 3 + 100)).map { UInt8($0 % 251) })
            let s = try MediaCipher.seal(sample, chunkSize: cs)
            check("media.smallChunks.roundTrip", try MediaCipher.open(s) == sample)
            func rejected(_ d: Data) -> Bool { (try? MediaCipher.open(d)) == nil }
            check("media.truncation.lastChunkDropped", rejected(s.prefix(h + 3 * st)))
            check("media.truncation.midChunk", rejected(s.prefix(s.count - 5)))
            check("media.truncation.headerOnly", rejected(s.prefix(h)))
            var swapped = Data(s.prefix(h))
            swapped += s.subdata(in: (h + st)..<(h + 2 * st))
            swapped += s.subdata(in: h..<(h + st))
            swapped += s.subdata(in: (h + 2 * st)..<s.count)
            check("media.chunkSwapRejected", swapped.count == s.count && rejected(swapped))
            check("media.chunkAppendRejected", rejected(s + s.subdata(in: h..<(h + st))))
            var tampered = s; tampered[h + st + 10] ^= 0x01
            check("media.tamperRejected", rejected(tampered))
            var badTag = s; badTag[s.count - 1] ^= 0x01
            check("media.tagTamperRejected", rejected(badTag))
            var badSize = s; badSize[15] ^= 0x01
            check("media.headerChunkSizeTamperRejected", rejected(badSize))
            var badSeed = s; badSeed[20] ^= 0x01
            check("media.headerSeedTamperRejected", rejected(badSeed))

            // Migration d'un fichier hérité en clair, dans un vrai dossier de médias
            let docsMedia = docs.appendingPathComponent("MediaMessages", isDirectory: true)
            try? fm.createDirectory(at: docsMedia, withIntermediateDirectories: true)
            let legacyURL = docsMedia.appendingPathComponent("selftest-legacy-\(UUID().uuidString).jpg")
            let legacy = Data(repeating: 0xAB, count: 700_000) + marker
            try legacy.write(to: legacyURL)
            defer { try? fm.removeItem(at: legacyURL) }
            check("media.legacyFile.readAsIs", try MediaCipher.read(contentsOf: legacyURL) == legacy)
            let outcome = MediaAtRestMigration.migrate(legacyURL)
            check("media.migration.outcome(\(outcome))", outcome == .encrypted)
            check("media.migration.onDiskEncrypted", try MediaCipher.isEncryptedFile(at: legacyURL)
                  && (try Data(contentsOf: legacyURL)).range(of: marker) == nil)
            check("media.migration.readBack", try MediaCipher.read(contentsOf: legacyURL) == legacy)
            check("media.migration.idempotent", MediaAtRestMigration.migrate(legacyURL) == .alreadyEncrypted)

            // Balayage des copies en clair d'un « lancement précédent »
            let past = Date().addingTimeInterval(-3600)
            let stale = try MediaPlaintextTemp.write(marker, fileName: "stale.jpg")
            try fm.setAttributes([.modificationDate: past], ofItemAtPath: stale.deletingLastPathComponent().path)
            let loose = fm.temporaryDirectory.appendingPathComponent("selftest-\(UUID().uuidString).mp4")
            try marker.write(to: loose)
            try fm.setAttributes([.modificationDate: past], ofItemAtPath: loose.path)
            let fresh = try MediaPlaintextTemp.write(marker, fileName: "fresh.jpg")
            MediaPlaintextTemp.sweepLeftovers(olderThan: Date().addingTimeInterval(-600))
            check("media.sweep.staleRemoved", !fm.fileExists(atPath: stale.path) && !fm.fileExists(atPath: loose.path))
            check("media.sweep.freshKept", fm.fileExists(atPath: fresh.path))
            MediaPlaintextTemp.remove(fresh)
        } catch {
            lines.append("FAIL media: \(error)")
        }

        // 3b. Chemin RÉEL de l'app: écriture/relecture privées de MessageManager
        if let mm = MessageManager.shared {
            let size = CGSize(width: 480, height: 320)
            let jpeg = UIGraphicsImageRenderer(size: size).image { ctx in
                UIColor(red: 0.10, green: 0.45, blue: 0.95, alpha: 1).setFill()
                ctx.fill(CGRect(origin: .zero, size: size))
                UIColor(red: 0.95, green: 0.35, blue: 0.55, alpha: 1).setFill()
                ctx.cgContext.fillEllipse(in: CGRect(x: 300, y: 40, width: 150, height: 150))
                ("OSHIMED1\ndecrypted from disk" as NSString).draw(
                    in: CGRect(x: 24, y: 200, width: 440, height: 110),
                    withAttributes: [.font: UIFont.boldSystemFont(ofSize: 34), .foregroundColor: UIColor.white])
            }.jpegData(compressionQuality: 0.85) ?? Data()
            let id = "selftest-media-\(UUID().uuidString)"
            let back = mm.debugMediaStoreRoundTrip(jpeg, id: id, type: .image)
            let file = docs.appendingPathComponent("MediaMessages/\(id).jpg")
            check("media.messageStore.image.roundTrip", back == jpeg && !jpeg.isEmpty)
            check("media.messageStore.image.encryptedOnDisk", (try? MediaCipher.isEncryptedFile(at: file)) == true)
            let voice = Data(repeating: 0x42, count: 300_000)
            let audioFile = docs.appendingPathComponent("AudioMessages/\(id).m4a")
            check("media.messageStore.audio.roundTrip", mm.debugMediaStoreRoundTrip(voice, id: id, type: .audio) == voice)
            check("media.messageStore.audio.encryptedOnDisk", (try? MediaCipher.isEncryptedFile(at: audioFile)) == true)
            try? fm.removeItem(at: audioFile)

            if ProcessInfo.processInfo.arguments.contains("-OSHIOpenChat"), let back {
                // Bulle image dans la conversation Alice, avec les octets RELUS du
                // disque chiffré — la capture prouve que le rendu passe encore.
                let me = MessageManager.sharedIdentityManager?.publicKey ?? ""
                let msg = SecureMessage(
                    id: id, senderAddress: "Alice", recipientAddress: String(me.prefix(10)),
                    encryptedContent: EncryptedMessage(ciphertext: "", signature: "demo_signature",
                                                       senderPublicKey: DemoDataManager.aliceKey, timestamp: Date()),
                    // Placée entre deux bulles de démo visibles à l'ouverture du fil
                    // (la capture `simctl` ne peut pas faire défiler).
                    timestamp: Date().addingTimeInterval(-3_000), isRead: true, deliveryStatus: .delivered,
                    senderPublicKey: DemoDataManager.aliceKey, recipientPublicKey: me,
                    plaintextContent: "", mediaAttachment: back, deliveryMethod: .ipfs,
                    mediaType: .image, mediaFileName: "photo.jpg")
                mm.messages.append(msg)
                mm.refreshConversations()
            } else {
                try? fm.removeItem(at: file)
            }
        } else { lines.append("SKIP media.messageStore (no MessageManager)") }

        // 3c. Après la migration de lancement: plus un seul média en clair
        let pass = MediaAtRestMigration.runPass()
        lines.append("INFO media.migration.pass encrypted=\(pass.encrypted) already=\(pass.alreadyEncrypted) failed=\(pass.failed) deferred=\(pass.deferred)")
        let candidates = MediaAtRestMigration.candidateFiles()
        for name in MediaAtRestMigration.mediaDirectoryNames {
            let prefix = docs.appendingPathComponent(name, isDirectory: true).standardizedFileURL.path + "/"
            let files = candidates.filter { $0.standardizedFileURL.path.hasPrefix(prefix) }
            if files.isEmpty { lines.append("SKIP mediaDir.\(name) (empty)"); continue }
            let plain = files.filter { (try? MediaCipher.isEncryptedFile(at: $0)) != true }
            check("mediaDir.\(name).encryptedOnDisk(\(files.count - plain.count)/\(files.count))", plain.isEmpty)
        }
        // Empreinte du CLAIR des médias migrés (à comparer, hors app, à celle
        // des fichiers plantés avant le lancement).
        for url in candidates where url.lastPathComponent.hasPrefix("legacy-") {
            if let d = try? MediaCipher.read(contentsOf: url) {
                let hex = SHA256.hash(data: d).map { String(format: "%02x", $0) }.joined()
                lines.append("INFO media.plainSHA256 \(url.lastPathComponent) \(hex)")
            }
        }
        let leftovers = (try? fm.contentsOfDirectory(atPath: MediaPlaintextTemp.directory.path)) ?? []
        lines.append("INFO media.plaintextTempEntries=\(leftovers.count)")

        // __SHARED_NICKNAME_2026_09_22__ pseudo partage (ContactPresenceManager.swift)
        lines.append(contentsOf: SharedNicknameSelfTest.run())

        // __EXPORT_DEVICE_ROUNDTRIP_2026_09_22__ Aller-retour réel entre deux
        // sessions du même compte: `SIMCTL_CHILD_OSHI_EXPORT_OUT=<chemin>` écrit un
        // export complet (capture v2 + scellement), `SIMCTL_CHILD_OSHI_IMPORT_IN=
        // <chemin>` le relit et l'applique comme le bouton Import des Réglages.
        let env = ProcessInfo.processInfo.environment
        if let identity = MessageManager.sharedIdentityManager, let mm = MessageManager.shared {
            if let outPath = env["OSHI_EXPORT_OUT"] {
                do {
                    let snap = MainActor.assumeIsolated { MessageExportV2.capture(mm, identity: identity) }
                    let url = try EncryptedMessageExport.write(snap, identity: identity)
                    let dest = URL(fileURLWithPath: outPath)
                    try? FileManager.default.removeItem(at: dest)
                    try FileManager.default.copyItem(at: url, to: dest)
                    try? FileManager.default.removeItem(at: url)
                    lines.append("PASS roundtrip.export(messages=\(mm.messages.count))")
                } catch { lines.append("FAIL roundtrip.export: \(error)") }
            }
            if let inPath = env["OSHI_IMPORT_IN"] {
                do {
                    let imp = try EncryptedMessageExport.read(URL(fileURLWithPath: inPath), identity: identity)
                    let before = mm.messages.count
                    let c = MainActor.assumeIsolated { MessageExportV2.apply(imp, to: mm) }
                    lines.append("PASS roundtrip.import(before=\(before) imported=\(c.imported) alreadyPresent=\(c.alreadyPresent) skippedGroups=\(c.skippedGroups) invalid=\(c.invalid) after=\(mm.messages.count))")
                } catch { lines.append("FAIL roundtrip.import: \(error)") }
            }
        }

        let out = lines.joined(separator: "\n") + "\n"
        try? out.write(to: docs.appendingPathComponent("selftest_storage.txt"), atomically: true, encoding: .utf8)
        OshiLog.storage.info("SELFTEST\n\(out)")
    }
}
#endif
