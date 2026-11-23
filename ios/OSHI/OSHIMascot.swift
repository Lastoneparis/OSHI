//
//  OSHIMascot.swift
//  OSHI — the "O bubble" mascot (__O_BUBBLE_MASCOT_2026_11_16__)
//
//  Twenty looping animations of the OSHI ring with a face, one per common
//  reaction emoji. Source pack: `oshi_mascot/pack` (README + manifest.json),
//  bundled as the folder reference `OSHIMascot/`:
//
//      OSHIMascot/manifest.json          copy of the pack manifest (tests read it)
//      OSHIMascot/frames/<name>/NN.png   128 px PNG sequence, 20 fps, seamless loop
//      OSHIMascot/gif/<name>.gif         240 px GIF, what the picker SENDS
//      OSHIMascot/launch/wave/NN.png     512 px wave for the launch screen
//                                        (even frames + 39: what is played)
//
//  THE WIRE NEVER CHANGES. A mascot is only a way of DRAWING a standard emoji:
//  a ❤️ reaction travels as "❤️" exactly as before, so iOS 1.0.43, old Android
//  and desktop keep seeing a normal emoji. The mapping is display-side only,
//  and it includes the manifest aliases (🤣 draws `laugh`, 🥳 draws `party`).
//
//  COST IN THE CHAT STACK. Frames are decoded once per (animation, pixel size)
//  off the main thread, downsampled to the size actually drawn, kept in an
//  NSCache (purged under memory pressure) and shared by every view that shows
//  that mascot. Playback is `UIImageView.animationImages`: Core Animation runs
//  it in the render server on one clock, so a chat full of reaction chips
//  costs no per-row timer and no main-thread work per frame.
//

import SwiftUI
import UIKit
import ImageIO

// MARK: - Catalog

struct OSHIMascot: Hashable, Identifiable {
    /// File name in the pack (`frames/<name>/`, `gif/<name>.gif`).
    let name: String
    /// Canonical wire emoji, exactly as sent.
    let emoji: String
    /// Other emoji drawn with the same animation (manifest `aliases`).
    let aliases: [String]
    let frameCount: Int
    /// The most readable single frame (Reduce Motion, setting off in pickers).
    let stillFrame: Int
    /// English label from the manifest; used for search only.
    let label: String

    var id: String { name }

    static let fps: Double = 20
    var duration: TimeInterval { Double(frameCount) / Self.fps }
}

enum OSHIMascotCatalog {
    static let folder = "OSHIMascot"

    /// Manifest order. Kept in code so nothing is parsed at launch; the unit
    /// test checks it against the bundled manifest and the files.
    static let all: [OSHIMascot] = [
        .init(name: "love", emoji: "❤️", aliases: ["❤", "♥️", "💜", "🩷", "🧡", "💛", "💚", "💙", "🤍", "🖤"], frameCount: 32, stillFrame: 3, label: "love"),
        .init(name: "laugh", emoji: "😂", aliases: ["🤣"], frameCount: 24, stillFrame: 2, label: "laughing to tears"),
        .init(name: "thumbs", emoji: "👍", aliases: [], frameCount: 28, stillFrame: 15, label: "thumbs up"),
        .init(name: "cry", emoji: "😭", aliases: ["😢"], frameCount: 32, stillFrame: 8, label: "loud crying"),
        .init(name: "wow", emoji: "😮", aliases: ["😯", "😲", "😱"], frameCount: 32, stillFrame: 14, label: "wow"),
        .init(name: "angry", emoji: "😡", aliases: ["😠", "🤬"], frameCount: 28, stillFrame: 8, label: "angry"),
        .init(name: "pray", emoji: "🙏", aliases: [], frameCount: 36, stillFrame: 16, label: "thank you / please"),
        .init(name: "fire", emoji: "🔥", aliases: [], frameCount: 24, stillFrame: 6, label: "on fire"),
        .init(name: "party", emoji: "🎉", aliases: ["🥳", "🎊"], frameCount: 36, stillFrame: 11, label: "party"),
        .init(name: "hearteyes", emoji: "😍", aliases: ["🥰"], frameCount: 28, stillFrame: 3, label: "heart eyes"),
        .init(name: "kiss", emoji: "😘", aliases: ["😗", "😚", "😙"], frameCount: 36, stillFrame: 20, label: "blowing a kiss"),
        .init(name: "clap", emoji: "👏", aliases: ["🙌"], frameCount: 24, stillFrame: 0, label: "clapping"),
        .init(name: "thinking", emoji: "🤔", aliases: ["🧐"], frameCount: 40, stillFrame: 20, label: "thinking"),
        .init(name: "cool", emoji: "😎", aliases: [], frameCount: 40, stillFrame: 22, label: "cool"),
        .init(name: "sleep", emoji: "😴", aliases: ["💤", "😪"], frameCount: 40, stillFrame: 14, label: "sleeping"),
        .init(name: "ok", emoji: "✅", aliases: ["✔️", "☑️", "👌"], frameCount: 32, stillFrame: 16, label: "done / OK"),
        .init(name: "hundred", emoji: "💯", aliases: [], frameCount: 32, stillFrame: 14, label: "hundred"),
        .init(name: "wave", emoji: "👋", aliases: ["🙋"], frameCount: 40, stillFrame: 17, label: "hi / wave"),
        .init(name: "peek", emoji: "🫣", aliases: ["🙈"], frameCount: 40, stillFrame: 20, label: "peeking"),
        .init(name: "tearsmile", emoji: "🥲", aliases: [], frameCount: 40, stillFrame: 25, label: "smiling with a tear"),
    ]


    static func named(_ name: String) -> OSHIMascot? { byName[name] }

    /// Matching key: U+FE0F (and FE0E) and the skin-tone modifiers
    /// U+1F3FB…1F3FF are removed, so ❤ / ❤️ and 👍🏽 / 👍 meet.
    static func normalize(_ s: String) -> String {
        var v = String.UnicodeScalarView()
        for u in s.unicodeScalars where !(u.value == 0xFE0F || u.value == 0xFE0E || (0x1F3FB...0x1F3FF).contains(u.value)) {
            v.append(u)
        }
        return String(v)
    }

    /// The mascot that draws this emoji (canonical or alias), nil if none —
    /// or if its frames are missing from the bundle, so a broken build falls
    /// back to the system emoji instead of an empty box.
    static func mascot(forEmoji emoji: String) -> OSHIMascot? {
        guard let m = byEmoji[normalize(emoji)], isBundled(m) else { return nil }
        return m
    }

    // MARK: Bundle

    static func frameURL(_ m: OSHIMascot, index: Int) -> URL? {
        Bundle.main.url(forResource: String(format: "%02d", index), withExtension: "png",
                        subdirectory: "\(folder)/frames/\(m.name)")
    }

    static func gifURL(_ m: OSHIMascot) -> URL? {
        Bundle.main.url(forResource: m.name, withExtension: "gif", subdirectory: "\(folder)/gif")
    }

    static func launchWaveURL(index: Int) -> URL? {
        Bundle.main.url(forResource: String(format: "%02d", index), withExtension: "png",
                        subdirectory: "\(folder)/launch/wave")
    }

    /// The pack's wave has 40 frames (00…39); only those the launch plays
    /// (`MascotLaunchTiming.waveFrames`: even ones + the last) are bundled.
    static let launchWaveFrameCount = 40

    private static let byName: [String: OSHIMascot] =
        Dictionary(uniqueKeysWithValues: all.map { ($0.name, $0) })

    private static let byEmoji: [String: OSHIMascot] = {
        var d: [String: OSHIMascot] = [:]
        for m in all {
            for e in [m.emoji] + m.aliases where d[normalize(e)] == nil { d[normalize(e)] = m }
        }
        return d
    }()

    private static var bundledCache: [String: Bool] = [:]
    private static let bundledLock = NSLock()

    /// First and last frame present: one cheap check per animation, cached.
    static func isBundled(_ m: OSHIMascot) -> Bool {
        bundledLock.lock(); defer { bundledLock.unlock() }
        if let hit = bundledCache[m.name] { return hit }
        let ok = frameURL(m, index: 0) != nil && frameURL(m, index: m.frameCount - 1) != nil
        bundledCache[m.name] = ok
        return ok
    }
}

// MARK: - What is SENT (safe mapping while iOS 1.0.43 is on sale)

/// __MASCOT_SAFE_WIRE_2026_11_16__ Which emoji a mascot SENDS, and which
/// mascots are offered where. Product decision (same on Android / desktop):
/// while iOS 1.0.43 is the version on sale, a reaction it does not know is
/// shown there as 👍, so the bar only sends emoji it knows.
///
///   * `sendAs`: the crying mascot sends 😢 (known to every OSHI build), not
///     the manifest's 😭. Received 😭 and 😢 both DRAW the crying mascot.
///   * `quickBar`: ⭕ then these six, all known to old builds.
///   * `newOnWire`: mascots whose emoji old iPhones do not know. Offered only
///     in the "+" picker, under a "may show 👍 on older iPhones" hint.
///
/// To revert once old builds are gone: empty `sendAs` and `newOnWire`, and
/// put the wanted six in `quickBar`. Nothing else reads these decisions.
enum MascotWirePolicy {
    static let sendAs: [String: String] = ["cry": "😢"]
    static let quickBar: [String] = ["love", "laugh", "cry", "thumbs", "wow", "fire"]
    static let newOnWire: Set<String> = ["angry", "pray", "hearteyes", "cool", "sleep", "wave", "peek", "tearsmile"]

    /// The emoji tapping this mascot sends.
    static func wireEmoji(_ m: OSHIMascot) -> String { sendAs[m.name] ?? m.emoji }
}

// MARK: - Setting

/// "Animated OSHI reactions". ON by default; OFF draws every reaction as the
/// system emoji, exactly as before the mascot existed.
enum MascotReactionPreference {
    static let key = "animatedOSHIReactions"
    static let defaultEnabled = true

    static var isEnabled: Bool {
        UserDefaults.standard.object(forKey: key) as? Bool ?? defaultEnabled
    }

    /// What a reaction is drawn with: a mascot, or nil for the plain emoji.
    static func mascot(for emoji: ReactionEmoji, enabled: Bool) -> OSHIMascot? {
        guard enabled else { return nil }
        return OSHIMascotCatalog.mascot(forEmoji: emoji.rawValue)
    }
}

// MARK: - Decoded frames, shared

final class MascotFrames {
    let images: [UIImage]
    let duration: TimeInterval
    init(images: [UIImage], duration: TimeInterval) { self.images = images; self.duration = duration }
    var cost: Int { images.reduce(0) { $0 + Int($1.size.width * $1.scale * $1.size.height * $1.scale * 4) } }
}

final class MascotFrameStore {
    static let shared = MascotFrameStore()

    private let cache: NSCache<NSString, MascotFrames> = {
        let c = NSCache<NSString, MascotFrames>()
        c.totalCostLimit = 40 * 1024 * 1024
        return c
    }()
    private let stills = NSCache<NSString, UIImage>()
    private let queue = DispatchQueue(label: "oshi.mascot.decode", qos: .userInitiated)
    /// Main-thread only: requests waiting for a decode already running.
    private var waiting: [String: [(MascotFrames?) -> Void]] = [:]

    /// Pixel size actually needed, in 16 px steps, never above the 128 px source.
    static func pixelBucket(points: CGFloat, scale: CGFloat) -> Int {
        let px = Int((points * scale).rounded(.up))
        return min(128, max(32, ((px + 15) / 16) * 16))
    }

    private static func key(_ m: OSHIMascot, _ px: Int) -> String { "\(m.name)@\(px)" }

    func cachedFrames(_ m: OSHIMascot, px: Int) -> MascotFrames? {
        cache.object(forKey: Self.key(m, px) as NSString)
    }

    /// One frame, decoded synchronously (a single small PNG) and cached.
    func still(_ m: OSHIMascot, px: Int, scale: CGFloat) -> UIImage? {
        let k = Self.key(m, px) as NSString
        if let hit = stills.object(forKey: k) { return hit }
        if let frames = cachedFrames(m, px: px), m.stillFrame < frames.images.count {
            return frames.images[m.stillFrame]
        }
        guard let url = OSHIMascotCatalog.frameURL(m, index: m.stillFrame),
              let img = Self.decode(url, px: px, scale: scale) else { return nil }
        stills.setObject(img, forKey: k)
        return img
    }

    /// All frames, decoded off the main thread once; `done` runs on main.
    func frames(_ m: OSHIMascot, px: Int, scale: CGFloat, done: @escaping (MascotFrames?) -> Void) {
        dispatchPrecondition(condition: .onQueue(.main))
        let k = Self.key(m, px)
        if let hit = cache.object(forKey: k as NSString) { done(hit); return }
        if waiting[k] != nil { waiting[k]!.append(done); return }
        waiting[k] = [done]
        queue.async { [weak self] in
            var images: [UIImage] = []
            images.reserveCapacity(m.frameCount)
            for i in 0..<m.frameCount {
                guard let url = OSHIMascotCatalog.frameURL(m, index: i),
                      let img = Self.decode(url, px: px, scale: scale) else { break }
                images.append(img)
            }
            let result = images.count == m.frameCount ? MascotFrames(images: images, duration: m.duration) : nil
            DispatchQueue.main.async {
                guard let self else { return }
                if let result { self.cache.setObject(result, forKey: k as NSString, cost: result.cost) }
                let callbacks = self.waiting.removeValue(forKey: k) ?? []
                callbacks.forEach { $0(result) }
            }
        }
    }

    /// Decoded NOW (not lazily at first draw), downsampled to `px`.
    static func decode(_ url: URL, px: Int, scale: CGFloat) -> UIImage? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: px,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        return UIImage(cgImage: cg, scale: scale, orientation: .up)
    }
}

// MARK: - Views

enum MascotMotion: Equatable {
    /// Loops while on screen (reaction bar, "+" picker, GIF picker).
    case loop
    /// Plays once when it appears, then rests on the still frame (chips).
    case once
    /// Never moves.
    case still
}

/// One mascot in a square of `side` points. Reduce Motion → still frame.
struct MascotGlyph: View {
    let mascot: OSHIMascot
    let side: CGFloat
    var motion: MascotMotion = .loop

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        MascotImageView(mascot: mascot,
                        px: MascotFrameStore.pixelBucket(points: side, scale: displayScale),
                        scale: displayScale,
                        motion: reduceMotion ? .still : motion)
            .frame(width: side, height: side)
    }
}

private struct MascotImageView: UIViewRepresentable {
    let mascot: OSHIMascot
    let px: Int
    let scale: CGFloat
    let motion: MascotMotion

    final class Coordinator {
        var key = ""
        var motion: MascotMotion = .still
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIImageView {
        let v = UIImageView()
        v.contentMode = .scaleAspectFit
        v.isUserInteractionEnabled = false
        v.isAccessibilityElement = false
        v.setContentHuggingPriority(.defaultLow, for: .horizontal)
        v.setContentHuggingPriority(.defaultLow, for: .vertical)
        v.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        v.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        apply(to: v, context.coordinator)
        return v
    }

    func updateUIView(_ v: UIImageView, context: Context) {
        let key = "\(mascot.name)@\(px)"
        // Re-renders of the row must not restart a one-shot animation.
        guard key != context.coordinator.key || motion != context.coordinator.motion else { return }
        apply(to: v, context.coordinator)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UIImageView, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? 20, height: proposal.height ?? 20)
    }

    private func apply(to v: UIImageView, _ c: Coordinator) {
        let key = "\(mascot.name)@\(px)"
        c.key = key
        c.motion = motion
        v.stopAnimating()
        v.animationImages = nil
        v.image = MascotFrameStore.shared.still(mascot, px: px, scale: scale)
        guard motion != .still else { return }
        let motion = self.motion
        MascotFrameStore.shared.frames(mascot, px: px, scale: scale) { [weak v] frames in
            guard let v, let frames, c.key == key, c.motion == motion else { return }
            v.animationImages = frames.images
            v.animationDuration = frames.duration
            v.animationRepeatCount = motion == .once ? 1 : 0
            v.startAnimating()
        }
    }
}
