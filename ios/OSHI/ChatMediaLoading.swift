//
//  ChatMediaLoading.swift
//  OSHI
//
//  __CHAT_OPEN_PERF_2026_11_14__ Media in a conversation is prepared OFF the
//  main thread and remembered, in 1:1 chats and in groups.
//
//  What the main thread used to do while a media-heavy conversation opened or
//  scrolled (measured in `ChatOpenPerformanceTests`):
//
//   • 1:1 photo bubble (`MediaMessageView.imagePreview`): ImageIO downsample of
//     the full JPEG, synchronously, inside the view body, on the first render
//     of every photo row — one decode per row as it scrolls in.
//   • 1:1 video bubble: `UIImage(data:)` of the poster on every body pass; the
//     JPEG itself was inflated at draw time, on main.
//   • 1:1 voice note: `AVAudioPlayer(data:)` (parses the whole file) on the
//     main actor, for every voice note row that appears, just to read the
//     duration.
//   • Group media bubble (`GroupChatView.loadGroupMediaData`): read the file
//     from disk AND decrypt it (`MediaCipher`, AES-GCM per 256 KB chunk), on
//     main, on EVERY body pass of the group view — every typing tick, every
//     arriving message — for every media row of the window (the group list is
//     an eager `VStack` on iOS 18+, so that is all 50 rows, not just the visible
//     ones). A message still holding its bytes in memory was even RE-WRITTEN to
//     disk (encrypt + fsync) on every pass.
//   • Group photo/video: decoded again on every visit; a video poster was a
//     full-resolution AVAssetImageGenerator frame written through a temp file.
//
//  Now: decoded images live in `MediaAttachmentCache` (NSCache, same keys the
//  1:1 bubble always used, so warm-up, bubble and viewer share entries), are
//  force-decoded (`preparingForDisplay`) on a background task so nothing is
//  inflated at draw time, and a cache miss renders a placeholder of the SAME
//  size as the final picture, so a row never changes height when its image
//  lands. Group media bytes live in `GroupMediaStore` (NSCache) and are read
//  and decrypted off main once per message.
//
//  Nothing new is written to disk: decrypted media stays in memory only, as
//  `MediaAttachmentCache` already documents.
//

import Foundation
import SwiftUI
import UIKit
import AVFoundation

// MARK: - Image decode off main

enum ChatImagePipeline {
    /// Downsample to `maxDimension` px and decode fully, so drawing the result
    /// costs nothing on the main thread. Thread-safe; call it off main.
    static func prepare(_ data: Data, maxDimension: CGFloat) -> UIImage? {
        guard let image = MediaManager.safeUIImage(from: data, maxDimension: maxDimension) else { return nil }
        return image.preparingForDisplay() ?? image
    }

    /// The `MediaAttachmentCache` image key: the one `decodedImage(from:id:maxDimension:)`
    /// has always used, so every reader finds every writer's entry.
    static func cacheKey(_ id: String, _ maxDimension: CGFloat) -> String {
        "\(id)@\(Int(maxDimension))"
    }
}

/// Single-flight image loads keyed like `MediaAttachmentCache`: two rows (or a
/// warm-up and a row) asking for the same picture share one decode.
@MainActor
final class ChatImageLoader {
    static let shared = ChatImageLoader()

    private var inFlight: [String: Task<UIImage?, Never>] = [:]

    /// A decoded image already in memory. O(1), safe in a view body.
    func cached(id: String, maxDimension: CGFloat) -> UIImage? {
        MediaAttachmentCache.shared.image(forKey: ChatImagePipeline.cacheKey(id, maxDimension))
    }

    /// Decode off main (once), store, return. `produce` runs on a background task.
    func load(id: String, maxDimension: CGFloat,
              produce: @escaping @Sendable () -> UIImage?) async -> UIImage? {
        let key = ChatImagePipeline.cacheKey(id, maxDimension)
        if let hit = MediaAttachmentCache.shared.image(forKey: key) { return hit }
        if let running = inFlight[key] { return await running.value }
        let task = Task.detached(priority: .userInitiated) { produce() }
        inFlight[key] = task
        let image = await task.value
        inFlight[key] = nil
        if let image { MediaAttachmentCache.shared.setImage(image, forKey: key) }
        return image
    }

    /// Photo bytes → display image.
    func load(id: String, data: Data, maxDimension: CGFloat) async -> UIImage? {
        await load(id: id, maxDimension: maxDimension) {
            ChatImagePipeline.prepare(data, maxDimension: maxDimension)
        }
    }

    /// Decode the newest photos of a thread that is opening, before their rows
    /// ask. Bounded: the point is the first screen, not the history.
    func warm(_ items: [(id: String, data: Data)], maxDimension: CGFloat, limit: Int = 12) {
        for item in items.prefix(limit) where cached(id: item.id, maxDimension: maxDimension) == nil {
            let data = item.data
            Task { _ = await load(id: item.id, data: data, maxDimension: maxDimension) }
        }
    }
}

/// A picture drawn in a fixed box: the cached image at once when there is one,
/// otherwise a placeholder of the same size while the decode runs off main.
/// `fallback` is shown only when the bytes do not decode.
struct ChatAsyncImage<Fallback: View>: View {
    let id: String
    let maxDimension: CGFloat
    /// __BUBBLE_MEDIA_2026_11_21__ the box is the picture's own display size
    /// (`BubbleMediaLayout`), no longer always a square; `cornerRadius` 0 when
    /// the bubble clips the corners itself.
    let box: CGSize
    let cornerRadius: CGFloat
    let produce: @Sendable () -> UIImage?
    let fallback: () -> Fallback

    init(id: String, maxDimension: CGFloat, side: CGFloat,
         produce: @escaping @Sendable () -> UIImage?,
         @ViewBuilder fallback: @escaping () -> Fallback) {
        self.init(id: id, maxDimension: maxDimension, size: CGSize(width: side, height: side),
                  cornerRadius: 15, produce: produce, fallback: fallback)
    }

    init(id: String, maxDimension: CGFloat, size: CGSize, cornerRadius: CGFloat,
         produce: @escaping @Sendable () -> UIImage?,
         @ViewBuilder fallback: @escaping () -> Fallback) {
        self.id = id
        self.maxDimension = maxDimension
        self.box = size
        self.cornerRadius = cornerRadius
        self.produce = produce
        self.fallback = fallback
    }

    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        let shown = image ?? ChatImageLoader.shared.cached(id: id, maxDimension: maxDimension)
        SwiftUI.Group {
            if let shown {
                Image(uiImage: shown)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: box.width, height: box.height)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
            } else if failed {
                fallback()
            } else {
                RoundedRectangle(cornerRadius: cornerRadius)
                    .fill(Color(.systemGray5))
                    .frame(width: box.width, height: box.height)
            }
        }
        .task(id: id) {
            // A hit here still has to be ASSIGNED: the body that ran before this
            // task may have drawn the placeholder (the warm-up finished in
            // between), and a cache is not observable — nothing else would
            // re-render the row.
            if image != nil { return }
            if let hit = ChatImageLoader.shared.cached(id: id, maxDimension: maxDimension) { image = hit; return }
            let loaded = await ChatImageLoader.shared.load(id: id, maxDimension: maxDimension, produce: produce)
            if let loaded { image = loaded } else { failed = true }
        }
    }
}

// MARK: - Voice notes

/// Duration of a voice note, read once per note and off main.
///
/// `AVAudioPlayer(data:)` parses the whole file; `InlineVoiceNoteView` built one
/// on the main actor for every voice-note row that appeared, only to read
/// `duration`.
enum VoiceNoteDurationCache {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var durations: [String: TimeInterval] = [:]

    static func cached(_ id: String) -> TimeInterval? {
        lock.lock(); defer { lock.unlock() }
        return durations[id]
    }

    static func duration(id: String, data: Data) async -> TimeInterval? {
        if let hit = cached(id) { return hit }
        let value = await Task.detached(priority: .userInitiated) { () -> TimeInterval? in
            (try? AVAudioPlayer(data: data))?.duration
        }.value
        if let value { store(value, for: id) }
        return value
    }

    private static func store(_ value: TimeInterval, for id: String) {
        lock.lock(); defer { lock.unlock() }
        if durations.count > 500 { durations.removeAll() }
        durations[id] = value
    }
}

// MARK: - Group media bytes

/// Decrypted group media, read from disk once per message and kept in memory.
///
/// Replaces the synchronous `loadGroupMediaData` that `GroupChatView` ran in
/// every media row's body. Same three sources in the same order: the bytes
/// already on the message, the encrypted file, the media embedded in the
/// message JSON (then the raw blob, as before).
@MainActor
final class GroupMediaStore {
    static let shared = GroupMediaStore()

    private let cache = NSCache<NSString, NSData>()
    private var inFlight: [UUID: Task<Data?, Never>] = [:]
    /// Ids whose in-memory bytes have been handed to the disk writer once.
    private var persisted = Set<UUID>()

    private init() {
        cache.countLimit = 120
        cache.totalCostLimit = 160 * 1024 * 1024
        NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil, queue: .main
        ) { _ in
            Task { @MainActor in GroupMediaStore.shared.cache.removeAllObjects() }
        }
    }

    /// The bytes when they are already in memory. O(1), safe in a view body.
    ///
    /// Bytes still on the message are returned as they are; they are written to
    /// disk once, off main (they used to be re-written on every render).
    func cachedData(for message: GroupMessage) -> Data? {
        if let inMemory = message.mediaData, !inMemory.isEmpty {
            persistOnce(inMemory, message: message)
            return inMemory
        }
        return cache.object(forKey: message.id.uuidString as NSString) as Data?
    }

    /// Load (disk + decrypt, or JSON extraction) off main, once per message.
    func load(_ message: GroupMessage) async -> Data? {
        if let hit = cachedData(for: message) { return hit }
        guard let mediaType = message.mediaType else { return nil }
        if let running = inFlight[message.id] { return await running.value }
        let id = message.id
        let encrypted = message.encryptedContent
        let task = Task.detached(priority: .userInitiated) { () -> Data? in
            GroupMediaStore.resolve(id: id, type: mediaType, encryptedContent: encrypted)
        }
        inFlight[id] = task
        let data = await task.value
        inFlight[id] = nil
        if let data { put(data, for: id) }
        return data
    }

    func put(_ data: Data, for id: UUID) {
        cache.setObject(data as NSData, forKey: id.uuidString as NSString, cost: data.count)
    }

    func remove(_ id: UUID) {
        cache.removeObject(forKey: id.uuidString as NSString)
    }

    /// Steps 2-4 of the old `loadGroupMediaData`, unchanged, minus the main thread.
    nonisolated static func resolve(id: UUID, type: MediaType, encryptedContent: Data) -> Data? {
        if let data = MessageGroupManager.readGroupMediaFile(for: id.uuidString, type: type) {
            return data
        }
        if let contentString = String(data: encryptedContent, encoding: .utf8),
           contentString.contains("\"type\":\"media\""),
           let jsonData = contentString.data(using: .utf8),
           let mediaJson = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
           let base64Content = mediaJson["content"] as? String,
           let mediaData = Data(base64Encoded: base64Content) {
            // Save for future loads (as before), from this background task.
            _ = MessageGroupManager.writeGroupMediaFile(mediaData, for: id.uuidString, type: type)
            return mediaData
        }
        if encryptedContent.count > 100 {
            return encryptedContent
        }
        return nil
    }

    private func persistOnce(_ data: Data, message: GroupMessage) {
        guard let type = message.mediaType, !persisted.contains(message.id) else { return }
        persisted.insert(message.id)
        let id = message.id.uuidString
        Task.detached(priority: .utility) {
            _ = MessageGroupManager.writeGroupMediaFile(data, for: id, type: type)
        }
    }

    // MARK: Caption text of media messages

    private var textMemo: [UUID: (length: Int, text: String?)] = [:]

    /// `resolveText` for a media message decodes `encryptedContent` (the whole
    /// media blob) as UTF-8 — twice — on every body pass. The answer only
    /// depends on those bytes, so it is remembered per message and length.
    func memoizedText(for message: GroupMessage, compute: () -> String?) -> String? {
        let length = message.encryptedContent.count
        if let hit = textMemo[message.id], hit.length == length { return hit.text }
        let text = compute()
        if textMemo.count > 2000 { textMemo.removeAll() }
        textMemo[message.id] = (length, text)
        return text
    }
}

/// Hands a group media row its bytes: synchronously when they are in memory,
/// otherwise after an off-main load. `content` gets `(data, isLoading)`.
///
/// `refresh` is the list's version (`GroupChatView.messageListVersion`): the
/// old synchronous read looked at the disk again on every pass, so a file that
/// landed after the row was drawn showed up on the next update. A miss is
/// retried when the list changes; a hit costs one cache lookup.
struct GroupMediaDataReader<Content: View>: View {
    let message: GroupMessage
    var refresh: Int = 0
    @ViewBuilder let content: (Data?, Bool) -> Content

    @State private var loaded: Data?
    @State private var finished = false

    private struct LoadKey: Equatable {
        let id: UUID
        let refresh: Int
    }

    var body: some View {
        let data = GroupMediaStore.shared.cachedData(for: message) ?? loaded
        content(data, data == nil && !finished && message.mediaType != nil)
            .task(id: LoadKey(id: message.id, refresh: refresh)) {
                // Assign a hit (see `ChatAsyncImage`): the body may have run
                // before another row's load filled the cache.
                if let hit = GroupMediaStore.shared.cachedData(for: message) {
                    if loaded == nil { loaded = hit }
                    finished = true
                    return
                }
                if let data = await GroupMediaStore.shared.load(message) { loaded = data }
                finished = true
            }
    }
}
