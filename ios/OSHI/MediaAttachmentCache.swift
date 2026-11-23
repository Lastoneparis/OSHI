//
//  MediaAttachmentCache.swift
//  OSHI — decrypted media, kept for as long as the system allows.
//
//  Media bubbles used to rebuild their attachment from the encrypted blob on
//  every `.task`, and `.task` re-runs whenever LazyVStack re-materializes the
//  row: scroll past and back, or leave the conversation and return. Each time
//  cost a full decrypt plus decode on the way to the first frame, and reset the
//  placeholder — which is the lag reported when returning to a conversation
//  containing media.
//
//  NSCache, NOT a dictionary. This holds decrypted images and video payloads,
//  and this codebase has documented crashes from holding several
//  full-resolution videos at once. NSCache evicts under memory pressure on its
//  own; a dictionary would turn a scrolling gallery into a memory graph that
//  only grows.
//

import Foundation
import UIKit

@MainActor
final class MediaAttachmentCache {
    static let shared = MediaAttachmentCache()

    private let cache = NSCache<NSString, Entry>()

    /// NSCache needs a class type, and MediaAttachment is a struct.
    private final class Entry {
        let value: MediaManager.MediaAttachment
        init(_ value: MediaManager.MediaAttachment) { self.value = value }
    }

    /// Decoded images, kept beside the attachments.
    ///
    /// 🔴 __BLACK_TILES_ON_LAUNCH_2026_08_19__ Hugo: "when I open the app all my
    /// previous pictures are black and take a long time to appear."
    ///
    /// The cause is NOT decryption — `message.mediaAttachment` already holds the
    /// plaintext bytes. It is the JPEG DECODE, and it happens per row, inside the
    /// cell's `.task`, i.e. AFTER the row is already on screen. So every launch
    /// paints a grid of `systemGray5` placeholders — which in dark mode read as
    /// black — and fills them in one decode at a time.
    ///
    /// Nothing is written to disk. That was the other option and it was declined
    /// on purpose: a disk cache of decoded photos puts decrypted content at rest
    /// on the device, which is exactly what this app spends its effort avoiding.
    /// The cost of that choice is honest — a short delay on the very first paint
    /// after launch remains, it is just no longer serialised per row.
    private let images = NSCache<NSString, UIImage>()

    /// A decoded image, if one has already been produced for this message.
    func image(for messageId: String) -> UIImage? {
        images.object(forKey: messageId as NSString)
    }

    /// A decoded image under a composed key (`ChatImagePipeline.cacheKey`).
    func image(forKey key: String) -> UIImage? {
        images.object(forKey: key as NSString)
    }

    func setImage(_ image: UIImage, for messageId: String) {
        setImage(image, forKey: messageId)
    }

    func setImage(_ image: UIImage, forKey key: String) {
        // Cost in bytes so the byte ceiling means something: 4 bytes per pixel at
        // the decoded scale, which is what actually occupies memory.
        let cost = Int(image.size.width * image.scale * image.size.height * image.scale * 4)
        images.setObject(image, forKey: key as NSString, cost: cost)
    }

    /// A decoded image for this message, decoding once if needed.
    ///
    /// 🔴 Called from a view BODY on purpose, and safe there: an `NSCache` is not
    /// SwiftUI state, so writing to it during a render publishes nothing — the
    /// same pattern, for the same reason, as `ContactPresenceManager.getProfileImage`.
    ///
    /// The bug this exists to kill: `imagePreview` called `safeUIImage(...)`
    /// INSIDE its body, so an 800pt JPEG was re-decoded on the main thread on
    /// every single body evaluation — every scroll, every delivery tick, every
    /// unrelated state change. Not once per photo: once per re-render, forever.
    ///
    /// `id` may be nil for previews and one-off surfaces; those simply decode as
    /// before rather than poisoning the cache with a shared key.
    func decodedImage(from data: Data, id: String?, maxDimension: CGFloat) -> UIImage? {
        guard let id else { return MediaManager.safeUIImage(from: data, maxDimension: maxDimension) }
        // Keyed by size too: the same photo is drawn at 400pt in a quote and
        // 1920pt full-screen, and handing the small one to the viewer would look
        // like a broken image rather than a cache hit.
        let key = "\(id)@\(Int(maxDimension))"
        if let hit = images.object(forKey: key as NSString) { return hit }
        guard let decoded = MediaManager.safeUIImage(from: data, maxDimension: maxDimension) else { return nil }
        setImage(decoded, forKey: key)
        return decoded
    }

    /// Decode the newest media up front, off the main thread.
    ///
    /// Call this when a conversation opens. `limit` is deliberately small: the
    /// point is to fill the FIRST screen instantly, not to decode a year of
    /// photos into memory. Bounded concurrency for the same reason — eight
    /// simultaneous full-resolution decodes is how you get jetsammed.
    ///
    /// Idempotent and cheap to call again: anything already decoded is skipped.
    ///
    /// __CHAT_OPEN_PERF_2026_11_14__ The default was 1200 while the bubble reads
    /// `@800` — the "always warm and never hit" case the comment below warns
    /// about. It now matches the bubble.
    func warm(_ items: [(id: String, data: Data)], limit: Int = 12, maxDimension: CGFloat = 800) {
        // Same key shape the bubble will read, or the pre-warm fills a slot nobody
        // ever looks in — a cache that is always warm and never hit.
        let key = { (id: String) in "\(id)@\(Int(maxDimension))" }
        let pending = items.prefix(limit).filter { images.object(forKey: key($0.id) as NSString) == nil }
        guard !pending.isEmpty else { return }

        Task.detached(priority: .userInitiated) {
            await withTaskGroup(of: (String, UIImage?).self) { group in
                var running = 0
                var iterator = pending.makeIterator()

                func addNext() {
                    guard let item = iterator.next() else { return }
                    running += 1
                    group.addTask {
                        (key(item.id), ChatImagePipeline.prepare(item.data,
                                                                 maxDimension: maxDimension))
                    }
                }
                for _ in 0..<min(3, pending.count) { addNext() }

                for await (composedKey, image) in group {
                    running -= 1
                    if let image {
                        await MainActor.run { MediaAttachmentCache.shared.setImage(image, forKey: composedKey) }
                    }
                    addNext()
                }
            }
        }
    }

    private init() {
        // A bound in ITEMS as well as bytes: a handful of large videos should
        // evict long before the byte limit is reached, and `totalCostLimit`
        // alone cannot express that.
        cache.countLimit = 60
        cache.totalCostLimit = 96 * 1024 * 1024   // ~96 MB of decrypted payload

        // Decoded images are bounded separately: they are much smaller than the
        // originals (downscaled at decode) but there are many more of them.
        images.countLimit = 80
        images.totalCostLimit = 48 * 1024 * 1024

        // Drop everything on a memory warning rather than letting the system
        // decide which entries matter. Re-decrypting is slow but survivable;
        // being jetsammed mid-conversation is not.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.cache.removeAllObjects()
                self?.images.removeAllObjects()
                OshiLog.storage.info("🧹 Media cache cleared on memory warning")
            }
        }
    }

    func get(for messageId: String) -> MediaManager.MediaAttachment? {
        cache.object(forKey: messageId as NSString)?.value
    }

    func set(_ attachment: MediaManager.MediaAttachment, for messageId: String) {
        // Cost is the real payload size, so eviction reflects what is actually
        // held rather than counting a 30 KB thumbnail and a 40 MB video equally.
        let cost = attachment.data.count
        cache.setObject(Entry(attachment), forKey: messageId as NSString, cost: cost)
    }

    /// Called when a message is deleted or a view-once item is consumed —
    /// leaving decrypted bytes cached for something the user chose to destroy
    /// would defeat the point of the feature.
    func remove(_ messageId: String) {
        cache.removeObject(forKey: messageId as NSString)
    }

    func removeAll() { cache.removeAllObjects() }
}
