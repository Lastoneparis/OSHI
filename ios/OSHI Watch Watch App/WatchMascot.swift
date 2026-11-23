//
//  WatchMascot.swift
//  OSHI Watch
//
//  __APPLE_WATCH_2027__ The O-bubble mascot on the wrist.
//
//  Frames are the phone pack's (`OSHI/OSHIMascot/frames/<name>/NN.png`, 128 px),
//  every other frame (10 fps), in `Mascot.xcassets` as `mascot-<name>-NN`.
//  The phone's `OSHIMascot.swift` is UIKit (`UIImageView.animationImages`) and
//  does not exist on watchOS; this is a SwiftUI `TimelineView` stepping the
//  frames instead.
//
//  Animates only when it can be seen and it is wanted: Reduce Motion, and the
//  always-on (wrist down, luminance reduced) state, draw the still frame — so
//  the watch never spends power animating a dimmed screen.
//

import SwiftUI

enum WatchMascot: String, CaseIterable {
    case love, laugh, cry, thumbs, wow, fire, sleep, peek, thinking, wave, ok

    /// Frames bundled (every other frame of the pack).
    var frameCount: Int {
        switch self {
        case .love, .cry, .wow, .ok: return 16
        case .laugh, .fire: return 12
        case .thumbs: return 14
        case .sleep, .peek, .thinking, .wave: return 20
        }
    }

    /// The pack's `stillFrame` (OSHIMascotCatalog), halved.
    var stillFrame: Int {
        switch self {
        case .love: return 1
        case .laugh: return 1
        case .thumbs: return 7
        case .cry: return 4
        case .wow: return 7
        case .fire: return 3
        case .sleep: return 7
        case .peek: return 10
        case .thinking: return 10
        case .wave: return 8
        case .ok: return 8
        }
    }

    func frameName(_ i: Int) -> String { String(format: "mascot-%@-%02d", rawValue, i) }

    /// The mascot that draws a quick-reaction WIRE emoji (nil for ⭕, which is
    /// the OSHI "O" mark). Same mapping as the phone (`MascotWirePolicy`):
    /// 😢 draws the crying mascot.
    static func forReaction(_ wire: String) -> WatchMascot? {
        switch wire {
        case "\u{2764}\u{FE0F}", "\u{2764}": return .love
        case "\u{1F602}", "\u{1F923}": return .laugh
        case "\u{1F622}", "\u{1F62D}": return .cry
        case "\u{1F44D}": return .thumbs
        case "\u{1F62E}", "\u{1F62F}", "\u{1F632}": return .wow
        case "\u{1F525}": return .fire
        default: return nil
        }
    }
}

struct WatchMascotView: View {
    let mascot: WatchMascot
    var size: CGFloat = 64
    var animated = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.isLuminanceReduced) private var isLuminanceReduced

    var body: some View {
        Group {
            if animated && !reduceMotion && !isLuminanceReduced {
                TimelineView(.periodic(from: .now, by: 0.1)) { ctx in
                    let i = Int((ctx.date.timeIntervalSinceReferenceDate * 10).rounded(.down)) % mascot.frameCount
                    Image(mascot.frameName(i)).resizable().interpolation(.high)
                }
            } else {
                Image(mascot.frameName(mascot.stillFrame)).resizable().interpolation(.high)
            }
        }
        .scaledToFit()
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// A quick reaction as drawn on the watch: the O mark for ⭕, the mascot for
/// the six others, the plain emoji for anything else (received reactions
/// outside the quick set).
struct ReactionGlyph: View {
    let wire: String
    var size: CGFloat = 30
    var animated = false

    var body: some View {
        if wire == "\u{2B55}" {
            OSHIOMarkView(size: size * 0.86)
        } else if let m = WatchMascot.forReaction(wire) {
            WatchMascotView(mascot: m, size: size, animated: animated)
        } else {
            Text(wire).font(.system(size: size * 0.7))
        }
    }
}

/// Avatar with initials on the shared 16-gradient palette (OSHI/AvatarIdentity.swift,
/// spec docs/avatar-palette.json): the SAME colour a contact or group has on the
/// iPhone, Android, desktop and Wear OS.
struct WatchAvatar: View {
    let key: String
    let name: String
    var isGroup = false
    var size: CGFloat = 34

    var body: some View {
        ZStack {
            AvatarFill(key: key, size: size)
            Text(AvatarIdentity.initials(name: name, key: key, count: 2))
                .font(.system(size: size * 0.38, weight: .semibold, design: .rounded))
                .foregroundColor(AvatarIdentity.ink(for: key))
                .minimumScaleFactor(0.5)
                .lineLimit(1)
                .padding(.horizontal, size * 0.1)
        }
        .frame(width: size, height: size)
        .overlay(alignment: .bottomTrailing) {
            if isGroup {
                Image(systemName: "person.2.fill")
                    .font(.system(size: size * 0.22, weight: .bold))
                    .foregroundColor(.white)
                    .padding(size * 0.06)
                    .background(Circle().fill(Color.black))
                    .offset(x: size * 0.08, y: size * 0.08)
            }
        }
        .accessibilityHidden(true)
    }
}
