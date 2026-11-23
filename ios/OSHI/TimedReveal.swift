//
//  TimedReveal.swift
//  OSHI
//
//  TIMED PHOTO REVEAL — client-side model.
//
//  A photo is sent as N standalone JPEGs ("layers"), from a genuinely tiny one
//  (48px on the long side) up to the original bytes. Layer 0 arrives at once;
//  every later layer is held by the relay and lands on a schedule, so the
//  picture SHARPENS over the chosen duration instead of appearing at once.
//
//  WHY LAYERS AND NOT A BLUR. A blur applied to the full image is cosmetic: the
//  4000px picture is already on the device and any modified client — or anyone
//  reading the file store — has all of it. A 48px JPEG contains 48px of
//  information and nothing more, because the other pixels were never encoded.
//  That is the whole security argument, and it only holds if the small layers
//  are produced by DOWNSCALING AND RE-ENCODING (this file) and never by
//  blurring a full-size image at render time.
//
//  Honest limit: this makes the recipient WAIT. It does not stop them
//  screenshotting each layer as it lands. It is a suspense feature, not DRM.
//
//  SCOPE OF THIS FILE. Pure model + a small view-facing store. Deliberately no
//  networking, no UIApplication/UIScreen, no MessageManager: `TimedRevealEncoder`
//  and `TimedRevealSchedule` are ordinary values that a unit test can drive with
//  a Data blob and a fixed Date.
//

import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import UIKit

// MARK: - Layers

/// One rung of the ladder: a STANDALONE JPEG, not a residual.
/// A lost layer therefore degrades quality, never correctness — each layer
/// decodes entirely on its own.
struct TimedRevealLayer: Equatable {
    /// 0-based. Layer 0 is the smallest and is the one that is never held.
    let index: Int
    /// Long-side pixel budget this layer was rendered to.
    /// `nil` on the final layer, which is the original bytes untouched.
    let maxDimension: Int?
    let jpeg: Data

    var isFinal: Bool { maxDimension == nil }
}

/// Pure encoder. Input Data in, Array of Data out — no shared state, no
/// singletons, no main thread requirement.
enum TimedRevealEncoder {

    /// Long-side budgets, ascending. Each rung is ×2 the previous, so every
    /// step is a visible jump in detail rather than a barely-perceptible one.
    /// With the final full-size layer this caps N at 6.
    static let defaultLadder: [Int] = [48, 96, 192, 384, 768]

    static let defaultQuality: CGFloat = 0.8

    /// Pixel dimensions read from the container — no full bitmap decode.
    static func pixelSize(of data: Data) -> (width: Int, height: Int)? {
        guard !data.isEmpty,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int,
              let h = props[kCGImagePropertyPixelHeight] as? Int,
              w > 0, h > 0 else { return nil }
        return (w, h)
    }

    /// Downscale to `maxDimension` on the long side and RE-ENCODE as JPEG.
    ///
    /// `kCGImageSourceCreateThumbnailWithTransform` bakes the EXIF orientation
    /// into the pixels. Without it a portrait shot taken on iPhone (which is
    /// stored landscape + an orientation tag) would come back rotated, because
    /// the thumbnail we write out carries no orientation tag of its own — the
    /// early layers would appear sideways and then "snap" upright on the last
    /// layer, which reads as a bug.
    static func downscaledJPEG(_ data: Data,
                               maxDimension: Int,
                               quality: CGFloat = defaultQuality) -> Data? {
        guard !data.isEmpty, maxDimension > 0,
              let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }

        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxDimension
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            return nil
        }

        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else {
            return nil
        }
        let props: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: max(0.1, min(1.0, quality))
        ]
        CGImageDestinationAddImage(dest, cgImage, props as CFDictionary)
        guard CGImageDestinationFinalize(dest), out.length > 0 else { return nil }
        return out as Data
    }

    /// Build the ladder for one photo.
    ///
    /// - The final layer is the CALLER'S OWN bytes, byte-identical to what a
    ///   normal send would have transmitted, so a finished reveal is
    ///   indistinguishable from an ordinary photo.
    /// - A rung is emitted only when it is strictly smaller than the source in
    ///   pixels, under HALF the full JPEG in bytes, and strictly larger than the
    ///   rung before it. The half rule is what keeps the overhead sane on a
    ///   picture that is already small: a 512px avatar re-encoded at 384px costs
    ///   two thirds of the original, so the ladder would nearly double the send
    ///   for a photo nobody would ask to reveal. Rungs grow monotonically, so
    ///   dropping one drops every larger one with it.
    /// - Result count is 1...6. **A count of 1 means this photo cannot be
    ///   revealed** (it is already tiny) and the caller must send it normally.
    static func layers(from fullJPEG: Data,
                       ladder: [Int] = defaultLadder,
                       quality: CGFloat = defaultQuality) -> [TimedRevealLayer] {
        guard !fullJPEG.isEmpty else { return [] }

        let longSide = pixelSize(of: fullJPEG).map { max($0.width, $0.height) } ?? 0

        var result: [TimedRevealLayer] = []
        for rung in ladder.sorted() where rung < longSide {
            guard let jpeg = downscaledJPEG(fullJPEG, maxDimension: rung, quality: quality) else { continue }
            guard jpeg.count * 2 < fullJPEG.count else { continue }
            if let previous = result.last, previous.jpeg.count >= jpeg.count { continue }
            result.append(TimedRevealLayer(index: result.count, maxDimension: rung, jpeg: jpeg))
        }
        result.append(TimedRevealLayer(index: result.count, maxDimension: nil, jpeg: fullJPEG))
        return result
    }
}

// MARK: - Schedule

/// When each layer becomes due. A DURATION anchored on a locally observed
/// start, never an absolute epoch chosen by the sender: a skewed sender clock
/// must not be able to corrupt the recipient's countdown (nor pin a photo in
/// the future forever).
struct TimedRevealSchedule: Codable, Equatable {
    /// Total number of layers, 2...64. 1 is not a reveal.
    let layerCount: Int
    /// Seconds from layer 0 to the final layer.
    let totalDuration: TimeInterval
    /// Local wall clock, stamped when layer 0 ARRIVED (receiver) or was sent.
    let startedAt: Date

    init?(layerCount: Int, totalDuration: TimeInterval, startedAt: Date) {
        guard Self.isWithinBounds(layerCount: layerCount, totalDuration: totalDuration,
                                  startedAt: startedAt) else { return nil }
        self.layerCount = layerCount
        self.totalDuration = totalDuration
        self.startedAt = startedAt
    }

    init?(layerCount: Int, totalMs: Int, startedAt: Date) {
        self.init(layerCount: layerCount,
                  totalDuration: TimeInterval(totalMs) / 1000.0,
                  startedAt: startedAt)
    }

    // MARK: - 🔴 __REVEAL_AUDIT_2026_08_23__ Le décodage doit REJOUER les bornes
    //
    // L'`init?` ci-dessus refuse `layerCount < 2` et une durée absurde. Le
    // `Codable` SYNTHÉTISÉ ne passait pas par lui: `JSONDecoder` acceptait
    // n'importe quoi.
    //
    // Ce n'est pas théorique. `TimedRevealStore.load()` décode exactement ce
    // type au lancement, depuis les préférences. Une entrée abîmée — disque,
    // migration, ou une version future qui écrit autre chose — donnait
    // `layerCount = 1`, donc `finalIndex = 0`, donc `Double(k)/Double(0)` dans
    // `unlockDate`, donc une **Date NaN** dans un compte à rebours affiché.
    //
    // Un initialiseur qui valide et qu'on peut contourner ne valide rien. Les
    // bornes vivent maintenant dans une seule fonction, appelée par les deux
    // chemins.

    private enum CodingKeys: String, CodingKey {
        case layerCount, totalDuration, startedAt
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let layerCount = try c.decode(Int.self, forKey: .layerCount)
        let totalDuration = try c.decode(TimeInterval.self, forKey: .totalDuration)
        let startedAt = try c.decode(Date.self, forKey: .startedAt)
        guard Self.isWithinBounds(layerCount: layerCount, totalDuration: totalDuration,
                                  startedAt: startedAt) else {
            throw DecodingError.dataCorrupted(.init(
                codingPath: decoder.codingPath,
                debugDescription: "planification hors bornes: layerCount=\(layerCount) "
                    + "totalDuration=\(totalDuration) — une entrée pareille produit une Date NaN"))
        }
        self.layerCount = layerCount
        self.totalDuration = totalDuration
        self.startedAt = startedAt
    }

    /// La règle, à un seul endroit. `init?` et `init(from:)` l'appellent tous
    /// les deux — c'est ce qui rend le contournement inécrivable.
    static func isWithinBounds(layerCount: Int, totalDuration: TimeInterval,
                               startedAt: Date) -> Bool {
        layerCount >= 2 && layerCount <= 64
            && totalDuration.isFinite && totalDuration > 0
            && totalDuration <= 60 * 60 * 24 * 7
            && startedAt.timeIntervalSince1970.isFinite
    }

    var finalIndex: Int { layerCount - 1 }
    var completionDate: Date { startedAt.addingTimeInterval(totalDuration) }

    /// Linear spacing: layer k is due at start + total·k/(n−1), so "1 hour"
    /// means fully sharp at exactly one hour.
    func unlockDate(forLayer index: Int) -> Date {
        let clamped = min(max(index, 0), finalIndex)
        let fraction = Double(clamped) / Double(finalIndex)
        return startedAt.addingTimeInterval(totalDuration * fraction)
    }

    /// Highest layer index that SHOULD have arrived by `now`.
    func dueLayer(at now: Date) -> Int {
        let elapsed = now.timeIntervalSince(startedAt)
        guard elapsed.isFinite, elapsed > 0 else { return 0 }
        if elapsed >= totalDuration { return finalIndex }
        let raw = (elapsed / totalDuration) * Double(finalIndex)
        return min(max(Int(raw.rounded(.down)), 0), finalIndex)
    }

    /// Never negative — a finished reveal reports 0, not a past interval.
    func remaining(at now: Date) -> TimeInterval {
        max(0, completionDate.timeIntervalSince(now))
    }

    /// 0...1, clamped at both ends.
    func progress(at now: Date) -> Double {
        let elapsed = now.timeIntervalSince(startedAt)
        guard elapsed.isFinite, totalDuration > 0 else { return 0 }
        return min(max(elapsed / totalDuration, 0), 1)
    }

    /// Next moment a new layer is due, for a client that wants to poll exactly
    /// then instead of spinning. `nil` once everything is due.
    func nextUnlockDate(after now: Date) -> Date? {
        let due = dueLayer(at: now)
        guard due < finalIndex else { return nil }
        return unlockDate(forLayer: due + 1)
    }
}

// MARK: - Sending plan (pure math, no networking)

/// Per-layer send parameters. Kept here so the send path is arithmetic that a
/// test can check, not something buried in a networking function.
struct TimedRevealPlan: Equatable {
    struct Item: Equatable {
        let index: Int
        /// Distinct per layer: a queue that dedups by message id would drop
        /// every layer after the first if they shared one.
        let msgId: String
        /// RELATIVE hold, milliseconds. 0 for layer 0 — it is never held.
        let releaseAfterMs: Int
    }

    let revealId: String
    let items: [Item]

    /// - Parameters:
    ///   - revealId: message id of layer 0; groups every layer into one bubble.
    ///   - layerCount: from `TimedRevealEncoder.layers(from:)`.
    ///   - totalMs: total reveal duration.
    /// - Returns: nil when `layerCount < 2` — that photo cannot be revealed.
    static func make(revealId: String, layerCount: Int, totalMs: Int) -> TimedRevealPlan? {
        guard layerCount >= 2, totalMs > 0 else { return nil }
        let last = layerCount - 1
        let items = (0...last).map { k in
            Item(index: k,
                 msgId: k == 0 ? revealId : "\(revealId)#\(k)",
                 releaseAfterMs: Int((Double(totalMs) * Double(k) / Double(last)).rounded()))
        }
        return TimedRevealPlan(revealId: revealId, items: items)
    }
}

// MARK: - Selection

enum TimedRevealSelection {
    /// The sharpest layer that has ACTUALLY arrived — never the one that is
    /// merely due. Out-of-order arrival (a retried download) must never
    /// un-reveal the picture, so selection is a max, not a last-write.
    static func sharpestArrived(_ arrived: [Int: Data]) -> (index: Int, data: Data)? {
        guard let index = arrived.keys.max(), let data = arrived[index] else { return nil }
        return (index, data)
    }

    static func sharpestArrivedIndex<S: Sequence>(_ arrived: S) -> Int? where S.Element == Int {
        arrived.max()
    }
}

// MARK: - What the bubble needs to draw itself

struct TimedRevealPresentation: Equatable {
    let messageId: String
    let schedule: TimedRevealSchedule
    /// Sharpest layer that has arrived. Monotonic by construction.
    let layerIndex: Int
    /// Bytes of that layer when the store still holds them; `nil` means
    /// "render whatever the message attachment already carries", which is the
    /// normal case once the receive path has written the layer into the message.
    let imageData: Data?

    /// True once the full-resolution layer is in. The bubble then goes back to
    /// being an ordinary photo bubble — no capsule, no countdown.
    var isFinished: Bool { layerIndex >= schedule.finalIndex }

    func remaining(at now: Date) -> TimeInterval { schedule.remaining(at: now) }
    func progress(at now: Date) -> Double { schedule.progress(at: now) }

    /// True when the clock says a layer is due but it has not landed yet —
    /// the countdown is over and we are simply waiting on bytes.
    func isWaitingForLate(at now: Date) -> Bool {
        !isFinished && schedule.dueLayer(at: now) > layerIndex
    }
}

// MARK: - Store

/// Per-message reveal state, observed by the bubble.
///
/// Metadata (schedule + how far the reveal got) is persisted; LAYER BYTES ARE
/// NOT. Writing plaintext photo layers into UserDefaults would put the picture
/// on disk outside the encrypted message store, which is exactly what this
/// feature is supposed to avoid.
///
/// Main-thread only (it drives `@Published`). Not marked `@MainActor` because
/// the project builds in Swift 5 mode where that would ripple into every call
/// site; callers hop to the main queue via `apply`/`begin`.
final class TimedRevealStore: ObservableObject {

    static let shared = TimedRevealStore()

    struct Entry: Equatable {
        var schedule: TimedRevealSchedule
        /// Sharpest layer applied so far. Monotonic.
        var highestArrived: Int
        /// Only the sharpest layer's bytes are kept: every lower layer is
        /// strictly worse, so caching them would cost memory for nothing.
        var currentBytes: Data?
    }

    @Published private(set) var entries: [String: Entry] = [:]

    private let defaults: UserDefaults
    private let storageKey = "timedReveal.state.v1"

    private struct Persisted: Codable {
        var schedule: TimedRevealSchedule
        var highestArrived: Int
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        load()
    }

    // MARK: Reads

    func entry(for messageId: String) -> Entry? { entries[messageId] }

    /// `nil` ⇒ this message is not a reveal ⇒ the bubble draws the plain photo.
    func presentation(for messageId: String) -> TimedRevealPresentation? {
        guard let entry = entries[messageId] else { return nil }
        return TimedRevealPresentation(messageId: messageId,
                                       schedule: entry.schedule,
                                       layerIndex: entry.highestArrived,
                                       imageData: entry.currentBytes)
    }

    /// Next instant a layer is due across ALL active reveals — what a poller
    /// should wake on. `nil` when nothing is pending.
    func nextDueDate(after now: Date = Date()) -> Date? {
        entries.values
            .filter { $0.highestArrived < $0.schedule.finalIndex }
            .compactMap { $0.schedule.nextUnlockDate(after: now) }
            .min()
    }

    // MARK: Writes

    /// Register layer 0. `startedAt` is the LOCAL arrival time.
    @discardableResult
    func begin(messageId: String, schedule: TimedRevealSchedule, layer0: Data? = nil) -> Bool {
        assert(Thread.isMainThread, "TimedRevealStore must be mutated on the main thread")
        guard entries[messageId] == nil else { return false }
        entries[messageId] = Entry(schedule: schedule, highestArrived: 0, currentBytes: layer0)
        collectGarbage()
        persist()
        return true
    }

    /// Apply a later layer.
    /// - Returns: false when there is nothing to apply it to (unknown message).
    ///   A STALE layer (lower than what we already show) returns true: it is not
    ///   an error, it just changes nothing.
    @discardableResult
    func apply(messageId: String, layerIndex: Int, bytes: Data?) -> Bool {
        assert(Thread.isMainThread, "TimedRevealStore must be mutated on the main thread")
        guard var entry = entries[messageId] else { return false }
        guard layerIndex > entry.highestArrived else { return true }
        entry.highestArrived = min(layerIndex, entry.schedule.finalIndex)
        entry.currentBytes = bytes
        entries[messageId] = entry
        if entry.highestArrived >= entry.schedule.finalIndex {
            // Finished: the message now carries the full photo, so the bubble
            // renders it the ordinary way and this copy is dead weight.
            entries[messageId]?.currentBytes = nil
        }
        persist()
        return true
    }

    func forget(_ messageId: String) {
        assert(Thread.isMainThread, "TimedRevealStore must be mutated on the main thread")
        guard entries.removeValue(forKey: messageId) != nil else { return }
        persist()
    }

    // MARK: Persistence

    /// Finished reveals are kept for a week so a re-render right after
    /// completion still knows what the bubble was, then dropped.
    private func collectGarbage(now: Date = Date()) {
        // 🔴 __REVEAL_AUDIT_2026_08_23__ C'était:
        //     highestArrived < finalIndex || completionDate > cutoff
        // La PREMIÈRE clause est vraie pour toute révélation INCOMPLÈTE, quel
        // que soit son âge. Une révélation dont l'expéditeur n'est jamais revenu
        // — app désinstallée, message supprimé, couches perdues — gardait donc
        // son entrée À VIE, et le compte à rebours d'une photo qui n'arrivera
        // jamais avec elle.
        //
        // Une entrée est gardée tant qu'elle a une chance d'aboutir: soit elle
        // est finie et récente (un rendu juste après la fin doit savoir ce
        // qu'était la bulle), soit elle est en cours ET pas plus vieille que la
        // plus longue révélation possible, plus une marge.
        let cutoff = now.addingTimeInterval(-7 * 24 * 3600)
        entries = entries.filter { _, entry in
            let finished = entry.highestArrived >= entry.schedule.finalIndex
            if finished { return entry.schedule.completionDate > cutoff }
            // En cours: on abandonne au-delà d'une journée après l'échéance de
            // fin — au-delà, ce n'est plus « en retard », c'est perdu.
            return entry.schedule.completionDate.addingTimeInterval(24 * 3600) > now
        }
    }

    private func persist() {
        var out: [String: Persisted] = [:]
        for (id, entry) in entries {
            out[id] = Persisted(schedule: entry.schedule, highestArrived: entry.highestArrived)
        }
        guard let data = try? JSONEncoder().encode(out) else { return }
        defaults.set(data, forKey: storageKey)
    }

    private func load() {
        guard let data = defaults.data(forKey: storageKey),
              let stored = try? JSONDecoder().decode([String: Persisted].self, from: data) else { return }
        for (id, value) in stored {
            entries[id] = Entry(schedule: value.schedule,
                                highestArrived: value.highestArrived,
                                currentBytes: nil)
        }
        collectGarbage()
    }
}

// MARK: - Image cache

/// Layer bytes are decoded on every body evaluation otherwise, and a reveal
/// bubble re-evaluates once a second while the countdown runs.
final class TimedRevealImageCache {
    static let shared = TimedRevealImageCache()
    private let cache = NSCache<NSString, UIImage>()

    private init() { cache.countLimit = 32 }

    /// No downsampling here on purpose: a 48px layer is being scaled UP, and
    /// `MediaManager.safeUIImage(maxDimension:)` would only ever shrink it.
    func image(for key: String, data: Data) -> UIImage? {
        let nsKey = key as NSString
        if let cached = cache.object(forKey: nsKey) { return cached }
        guard let image = UIImage(data: data) else { return nil }
        cache.setObject(image, forKey: nsKey)
        return image
    }
}

// MARK: - Duration text

enum TimedRevealFormatter {

    private static let secondsFormatter: DateComponentsFormatter = {
        let f = DateComponentsFormatter()
        f.allowedUnits = [.second]
        f.unitsStyle = .abbreviated
        f.maximumUnitCount = 1
        return f
    }()

    private static let minutesFormatter: DateComponentsFormatter = {
        let f = DateComponentsFormatter()
        f.allowedUnits = [.minute]
        f.unitsStyle = .abbreviated
        f.maximumUnitCount = 1
        return f
    }()

    private static let hoursFormatter: DateComponentsFormatter = {
        let f = DateComponentsFormatter()
        f.allowedUnits = [.hour, .minute]
        f.unitsStyle = .abbreviated
        f.maximumUnitCount = 2
        return f
    }()

    /// A remaining-time label with NO English literal in it: every word comes
    /// from `DateComponentsFormatter`, which is localized by the system in all
    /// 34 languages the app ships.
    ///
    /// Rounded UP, so a reveal with 0.4s left reads "1s" and never "0s".
    static func remaining(_ interval: TimeInterval) -> String {
        guard interval.isFinite, interval > 0 else { return "" }
        let seconds = interval.rounded(.up)
        // Round to the displayed granularity FIRST, then pick the formatter from
        // the rounded value. Picking it first is what would print "60 min" for
        // 3599s: minute granularity chosen, then rounded up past the hour.
        let value: TimeInterval = seconds < 60 ? seconds : (seconds / 60).rounded(.up) * 60
        let formatter: DateComponentsFormatter
        if value >= 3600 {
            formatter = hoursFormatter
        } else if value >= 60 {
            formatter = minutesFormatter
        } else {
            formatter = secondsFormatter
        }
        return formatter.string(from: value) ?? ""
    }
}
