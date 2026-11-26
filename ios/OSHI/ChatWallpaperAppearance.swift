//
//  ChatWallpaperAppearance.swift
//  OSHI — wallpaper TONE: light / dark, chosen independently of the system.
//
//  __WALLPAPER_TONE_2026_09_24__
//
//  The OSHI backdrops (signature blooms, the mesh and the other drawn ones)
//  paint over `systemBackground`. That made them follow the phone: the white
//  ground with violet mesh that people like became a BLACK ground in dark
//  mode, and nothing in the app could keep the white one.
//
//  The tone fixes that without touching the wallpaper itself:
//
//    • `.automatic` — follows light / dark, exactly as before (the default, so
//      nobody who never opens the picker sees anything change);
//    • `.light`     — always the white ground with violet mesh;
//    • `.dark`      — always the black ground with violet mesh.
//
//  READABILITY RULE. When the tone forces a scheme, the CHAT CONTENT on top of
//  the wallpaper (bubbles, text, timestamps, transport badges, composer, nav
//  bar) is rendered in that same scheme. A light wallpaper under dark-mode
//  bubbles would put white text on a pale ground; rendering the content in
//  its light-mode colours reuses the exact palette that already ships and is
//  already tuned for that ground. `ChatWallpaperAppearanceTests` measures it.
//
//  The tone is a LOCAL preference. It is not part of `ChatWallpaper` (which is
//  broadcast to the peer and to older builds that would fail to decode a new
//  case) and it is not synced: there is no appearance-sync mechanism to reuse.
//

import SwiftUI

// MARK: - Tone

enum ChatWallpaperTone: String, CaseIterable, Identifiable, Codable {
    case automatic
    case light
    case dark

    var id: String { rawValue }

    var titleKey: String {
        switch self {
        case .automatic: return "wallpaper.tone.automatic"
        case .light:     return "wallpaper.tone.light"
        case .dark:      return "wallpaper.tone.dark"
        }
    }

    var detailKey: String {
        switch self {
        case .automatic: return "wallpaper.tone.automatic.detail"
        case .light:     return "wallpaper.tone.light.detail"
        case .dark:      return "wallpaper.tone.dark.detail"
        }
    }

    var symbol: String {
        switch self {
        case .automatic: return "circle.lefthalf.filled"
        case .light:     return "sun.max.fill"
        case .dark:      return "moon.fill"
        }
    }
}

// MARK: - Which wallpapers the tone applies to

extension ChatWallpaper {
    /// True for the backdrops drawn over `systemBackground` — the ones that
    /// change with light / dark and therefore can be pinned to one of them.
    /// A solid colour, gradient, pattern or photo has its own colours; the
    /// tone leaves those (and the content over them) exactly as they were.
    var followsTone: Bool {
        switch self {
        case .none, .oshiSignature, .oshiDynamic:
            return true
        case .solidColor(let c):
            return c.isClear          // "clear" is rendered as systemBackground
        case .gradient, .emojiPattern, .oshiPattern, .gradientPreset, .customImage:
            return false
        }
    }
}

// MARK: - Resolution

/// What a conversation actually renders with: the scheme the wallpaper is
/// drawn in, and the scheme the content on top of it is drawn in.
struct ChatWallpaperAppearance: Equatable {
    let wallpaperScheme: ColorScheme
    let contentScheme: ColorScheme
    /// Whether the tone had any effect (false for wallpapers with fixed colours).
    let toneApplies: Bool

    /// Pure: setting × system appearance → schemes. Unit-tested.
    static func resolve(wallpaper: ChatWallpaper,
                        tone: ChatWallpaperTone,
                        system: ColorScheme) -> ChatWallpaperAppearance {
        guard wallpaper.followsTone else {
            return ChatWallpaperAppearance(wallpaperScheme: system,
                                           contentScheme: system,
                                           toneApplies: false)
        }
        let scheme = resolve(tone: tone, system: system)
        // Content follows the wallpaper, never the system, so text always sits
        // on the ground its colours were designed for.
        return ChatWallpaperAppearance(wallpaperScheme: scheme,
                                       contentScheme: scheme,
                                       toneApplies: true)
    }

    static func resolve(tone: ChatWallpaperTone, system: ColorScheme) -> ColorScheme {
        switch tone {
        case .automatic: return system
        case .light:     return .light
        case .dark:      return .dark
        }
    }
}

// MARK: - Mini chat preview

/// A wallpaper rendered as a tiny conversation: the backdrop plus one
/// received and one sent bubble, all drawn in `scheme`. Used for every tile of
/// the picker so each option shows what a chat will really look like, not a
/// swatch.
struct WallpaperMiniChat: View {
    let wallpaper: ChatWallpaper
    let scheme: ColorScheme
    var animated: Bool = false
    var compact: Bool = false

    @ObservedObject private var bubbleStyle = BubbleStyleManager.shared

    var body: some View {
        ZStack {
            backdrop
            VStack(spacing: compact ? 5 : 8) {
                HStack {
                    bubble(outgoing: false)
                    Spacer(minLength: 0)
                }
                HStack {
                    Spacer(minLength: 0)
                    bubble(outgoing: true)
                }
            }
            .padding(compact ? 8 : 14)
        }
        .environment(\.colorScheme, scheme)
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private var backdrop: some View {
        switch wallpaper {
        case .oshiDynamic(let index):
            OSHIWallpaperView(style: OSHIWallpaper(rawValue: index) ?? .constellation,
                              animated: animated)
        case .oshiSignature:
            OSHISignatureBackground(animated: animated)
        default:
            ChatWallpaperView(wallpaper: wallpaper)
        }
    }

    @ViewBuilder
    private func bubble(outgoing: Bool) -> some View {
        if compact {
            // Tiles: text would be unreadable at this size, so the bubbles are
            // drawn as lines of "text" — the shape and colours are what matter.
            VStack(alignment: .leading, spacing: 3) {
                Capsule().frame(width: outgoing ? 30 : 38, height: 3)
                Capsule().frame(width: outgoing ? 18 : 24, height: 3)
            }
            .foregroundStyle(outgoing ? AnyShapeStyle(bubbleStyle.accent.onAccent.opacity(0.9))
                                      : AnyShapeStyle(Color.primary.opacity(0.55)))
            .padding(.horizontal, 7)
            .padding(.vertical, 6)
            .bubbleSurface(fill: outgoing ? bubbleStyle.outgoingFill : bubbleStyle.incomingFill(scheme),
                           outline: bubbleStyle.outline(outgoing: outgoing, tail: true),
                           rim: bubbleStyle.rim(outgoing: outgoing, scheme))
        } else {
            Text((outgoing ? "wallpaper.sample_sent" : "wallpaper.sample_received").localized)
                .font(.subheadline)
                .foregroundColor(outgoing ? bubbleStyle.accent.onAccent : .primary)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .bubbleSurface(fill: outgoing ? bubbleStyle.outgoingFill : bubbleStyle.incomingFill(scheme),
                               outline: bubbleStyle.outline(outgoing: outgoing, tail: true),
                               rim: bubbleStyle.rim(outgoing: outgoing, scheme))
        }
    }
}

// MARK: - Tone card

/// One of the three tone choices, shown as a mini chat in that tone.
/// "Automatic" is split on the diagonal: light top-left, dark bottom-right.
struct WallpaperToneCard: View {
    let tone: ChatWallpaperTone
    let wallpaper: ChatWallpaper
    let isSelected: Bool
    var height: CGFloat = 118
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                ZStack(alignment: .topTrailing) {
                    preview
                        .frame(height: height)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .strokeBorder(isSelected ? AnyShapeStyle(Theme.brandGradient)
                                                         : AnyShapeStyle(Color.primary.opacity(0.14)),
                                              lineWidth: isSelected ? 3 : 1)
                        )
                    if isSelected {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.scaled(20, weight: .semibold))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(Color.white, Theme.brand)
                            .shadow(color: .black.opacity(0.18), radius: 2, y: 1)
                            .padding(6)
                            .transition(.scale.combined(with: .opacity))
                    }
                }
                HStack(spacing: 4) {
                    Image(systemName: tone.symbol)
                        .font(.caption)
                    Text(tone.titleKey.localized)
                        .font(.caption)
                        .fontWeight(isSelected ? .semibold : .regular)
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }
                .foregroundColor(isSelected ? selectedLabelInk : .primary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tone.titleKey.localized)
        .accessibilityHint(tone.detailKey.localized)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }

    @ViewBuilder
    private var preview: some View {
        let shown = wallpaper.followsTone ? wallpaper : .oshiDynamic(OSHIWallpaper.constellation.rawValue)
        switch tone {
        case .light:
            WallpaperMiniChat(wallpaper: shown, scheme: .light, compact: true)
        case .dark:
            WallpaperMiniChat(wallpaper: shown, scheme: .dark, compact: true)
        case .automatic:
            ZStack {
                WallpaperMiniChat(wallpaper: shown, scheme: .light, compact: true)
                WallpaperMiniChat(wallpaper: shown, scheme: .dark, compact: true)
                    .mask(DiagonalHalf())
            }
        }
    }
}

/// Brand violet as TEXT: the raw brand is ~2.5:1 on black, so dark mode
/// gets a lighter violet (≈ 8:1 on black) for the selected label.
private let selectedLabelInk = Color(UIColor { trait in
    trait.userInterfaceStyle == .dark
        ? UIColor(red: 0.725, green: 0.635, blue: 1.0, alpha: 1)
        : UIColor(red: 0.337, green: 0.086, blue: 0.925, alpha: 1)
})

/// The bottom-right triangle of a rectangle.
private struct DiagonalHalf: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.maxX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        p.closeSubpath()
        return p
    }
}

/// The three tone cards in a row.
struct WallpaperToneRow: View {
    let wallpaper: ChatWallpaper
    @Binding var tone: ChatWallpaperTone
    var cardHeight: CGFloat = 118

    var body: some View {
        HStack(spacing: 12) {
            ForEach(ChatWallpaperTone.allCases) { t in
                WallpaperToneCard(tone: t, wallpaper: wallpaper,
                                  isSelected: tone == t, height: cardHeight) {
                    withAnimation(Theme.Motion.smooth) { tone = t }
                }
            }
        }
    }
}

// MARK: - Settings → Appearance → Chat wallpaper

/// The app-wide default: tone first (the thing people could not change
/// before), then which OSHI backdrop. Every tap applies immediately and the
/// large preview cross-fades to it.
struct ChatWallpaperSettingsView: View {
    @ObservedObject private var manager = ChatWallpaperManager.shared
    @Environment(\.colorScheme) private var systemScheme

    private var resolved: ChatWallpaperAppearance {
        ChatWallpaperAppearance.resolve(wallpaper: manager.defaultWallpaper,
                                        tone: manager.defaultTone,
                                        system: systemScheme)
    }

    private var backdrops: [ChatWallpaper] {
        [.oshiSignature] + OSHIWallpaper.allCases.map { .oshiDynamic($0.rawValue) }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                // Live preview, animated, in the resolved scheme.
                WallpaperMiniChat(wallpaper: manager.defaultWallpaper,
                                  scheme: resolved.contentScheme,
                                  animated: true)
                    .id("\(manager.defaultTone.rawValue)-\(String(describing: manager.defaultWallpaper))-\(resolved.contentScheme == .dark)")
                    .transition(.opacity)
                    .frame(height: 190)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(Color.primary.opacity(0.12), lineWidth: 1))
                    .shadow(color: .black.opacity(0.08), radius: 10, y: 4)

                VStack(alignment: .leading, spacing: 10) {
                    sectionHeader("wallpaper.tone.section")
                    WallpaperToneRow(wallpaper: manager.defaultWallpaper,
                                     tone: Binding(get: { manager.defaultTone },
                                                   set: { manager.setDefaultTone($0) }))
                    Text((resolved.toneApplies ? manager.defaultTone.detailKey
                                               : "wallpaper.tone.fixed_note").localized)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .animation(nil, value: manager.defaultTone)
                }

                VStack(alignment: .leading, spacing: 10) {
                    sectionHeader("wallpaper.tone.backdrop")
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 3),
                              spacing: 14) {
                        ForEach(Array(backdrops.enumerated()), id: \.offset) { _, wp in
                            backdropTile(wp)
                        }
                    }
                    Text("wallpaper.tone.more".localized)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding()
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("settings.appearance.wallpaper".localized)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func sectionHeader(_ key: String) -> some View {
        Text(key.localized.uppercased())
            .font(.footnote)
            .fontWeight(.semibold)
            .foregroundColor(.secondary)
    }

    private func title(for wp: ChatWallpaper) -> String {
        switch wp {
        case .oshiSignature: return "wallpaper.oshi".localized
        case .oshiDynamic(let i): return (OSHIWallpaper(rawValue: i) ?? .constellation).titleKey.localized
        default: return ""
        }
    }

    private func backdropTile(_ wp: ChatWallpaper) -> some View {
        let selected = manager.defaultWallpaper == wp
        let tileScheme = ChatWallpaperAppearance.resolve(wallpaper: wp, tone: manager.defaultTone,
                                                         system: systemScheme).contentScheme
        return Button {
            withAnimation(Theme.Motion.smooth) { manager.setDefaultWallpaper(wp) }
        } label: {
            VStack(spacing: 6) {
                ZStack(alignment: .topTrailing) {
                    WallpaperMiniChat(wallpaper: wp, scheme: tileScheme, compact: true)
                        .frame(height: 112)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .strokeBorder(selected ? AnyShapeStyle(Theme.brandGradient)
                                                       : AnyShapeStyle(Color.primary.opacity(0.14)),
                                              lineWidth: selected ? 3 : 1)
                        )
                    if selected {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.scaled(18, weight: .semibold))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(Color.white, Theme.brand)
                            .padding(5)
                            .transition(.scale.combined(with: .opacity))
                    }
                }
                Text(title(for: wp))
                    .font(.caption)
                    .fontWeight(selected ? .semibold : .regular)
                    .foregroundColor(selected ? selectedLabelInk : .primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title(for: wp))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}

// MARK: - Frosted top bar over the wallpaper  __CHAT_CHROME_BLUR_2026_11_21__
//
// « un effet flou pour la barre du haut avec la photo de profil, le nom etc.
// pour avoir le fond sur tout l'écran » — asked for 1:1, groups and channels.
//
// The conversation's navigation bar used to be either the system's opaque bar
// (1:1: `.toolbarBackground(.visible)`), cutting the wallpaper off under the
// header, or fully transparent (group), where the name and the call buttons sat
// directly on top of passing bubbles. Now the system background is hidden and
// this draws the bar's ground itself: the thread's wallpaper and the messages
// scrolling under the header show through, frosted, edge to edge.
//
// The surface is the same as the composer's (`oledGlassBar`): `.ultraThinMaterial`
// with the glass option off; the smoked / milky glass with its lit hairline when
// "Glass effect" (or OLED black) is on. Reduce Transparency and Increase
// Contrast give the opaque bar (the modifiers already switch to solid), so the
// header always reads.
struct ChatTopBarBackdrop: ViewModifier {
    func body(content: Content) -> some View {
        content
            .toolbarBackground(.hidden, for: .navigationBar)
            .overlay(alignment: .top) {
                // A zero-height strip at the top of the safe area: its
                // background extends up through the bar and the status bar.
                Color.clear
                    .frame(maxWidth: .infinity)
                    .frame(height: 0)
                    .oledGlassBar(fallback: .ultraThinMaterial, edge: .bottom)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
    }
}

extension View {
    /// The conversation header as a frosted pane over the wallpaper.
    func chatTopBarBackdrop() -> some View { modifier(ChatTopBarBackdrop()) }
}
