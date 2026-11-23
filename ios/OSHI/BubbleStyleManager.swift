//
//  BubbleStyleManager.swift
//  OSHI — how message bubbles look, and who decides.
//
//  Bubble colour and shape used to be hard-coded in three places (1:1 chat,
//  group chat, media). This centralises them so a single choice drives every
//  surface, and so the choice can be the user's.
//
//  Two things are deliberately NOT configurable: the text colour on an outgoing
//  bubble, and the incoming surface. Both are derived from the chosen accent so
//  a user cannot pick a combination they then cannot read — a chat app whose
//  text disappears at a certain setting is broken, not customised.
//

import SwiftUI
import ImageIO

// MARK: - Accent

/// The palette an outgoing bubble can take.
///
/// Every entry is a two-stop gradient rather than a flat fill: a single colour
/// across a large rounded shape reads as a sticker, whereas a short diagonal
/// ramp catches the light like a physical surface. The stops are close together
/// on purpose — a wide ramp turns into a rainbow and fights the text.
enum BubbleAccent: Int, CaseIterable, Identifiable, Codable {
    case oshi = 0        // the brand violet → indigo
    case ocean           // blue → cyan
    case sunset          // pink → orange
    case forest          // green → teal
    case graphite        // near-black, for people who want no colour at all
    case rose            // magenta → deep pink

    var id: Int { rawValue }

    var titleKey: String {
        switch self {
        case .oshi:     return "bubble.accent.oshi"
        case .ocean:    return "bubble.accent.ocean"
        case .sunset:   return "bubble.accent.sunset"
        case .forest:   return "bubble.accent.forest"
        case .graphite: return "bubble.accent.graphite"
        case .rose:     return "bubble.accent.rose"
        }
    }

    /// Light-to-dark diagonal stops for the outgoing bubble.
    var stops: [Color] {
        switch self {
        case .oshi:
            // __BUBBLE_MEDIA_2026_11_21__ (BUBBLE_MEDIA_SPEC §2) Light: a pale
            // OSHI lavender #EEE7FF → #E5DBFF read with near-black ink — white
            // on the saturated violet was heavy for long reading. Dark (and
            // OLED): a deep OSHI purple #5B47B3 → #4A3B8F with white ink.
            // Resolved per trait, so a subtree forced light/dark follows.
            return [Color(ChatPalette.outgoingTopUI), Color(ChatPalette.outgoingBottomUI)]
        case .ocean:
            return [Color(red: 0.16, green: 0.52, blue: 0.98),
                    Color(red: 0.05, green: 0.31, blue: 0.80)]
        case .sunset:
            return [Color(red: 0.98, green: 0.40, blue: 0.52),
                    Color(red: 0.90, green: 0.28, blue: 0.24)]
        case .forest:
            return [Color(red: 0.16, green: 0.72, blue: 0.52),
                    Color(red: 0.05, green: 0.50, blue: 0.42)]
        case .graphite:
            return [Color(red: 0.26, green: 0.27, blue: 0.31),
                    Color(red: 0.14, green: 0.15, blue: 0.18)]
        case .rose:
            return [Color(red: 0.93, green: 0.30, blue: 0.66),
                    Color(red: 0.76, green: 0.15, blue: 0.52)]
        }
    }

    /// A single representative colour, for small previews and tints.
    var representative: Color { stops[0] }

    /// Text on the outgoing bubble.
    ///
    /// ⚠️ AN EARLIER VERSION OF THIS COMMENT CLAIMED every accent is dark enough
    /// for white to clear 4.5:1. That is FALSE, and it was measured: computing
    /// WCAG relative luminance from the sRGB values in `stops` gives, for white
    /// on stop 0 / stop 1 —
    ///
    ///     oshi 6.04/8.61 ✓   graphite 9.57/15.07 ✓
    ///     ocean 3.62✗/6.98   forest 2.54✗/4.89   rose 3.38✗/5.38
    ///     sunset 2.88✗/3.94✗   ← fails at BOTH stops
    ///
    /// Stop 0 is `.topLeading`, i.e. exactly where the first line of text starts
    /// in LTR. So four of six accents put white below AA at the top of the bubble,
    /// and `sunset` never reaches it at all.
    ///
    /// White is kept because it is what the app has always shipped and changing
    /// the ink per accent is a design decision, not a comment fix — but the
    /// justification is removed rather than left standing as a false claim. The
    /// real fix is to darken the failing stops; tracked in
    /// MASTERPLAN_SHARE_WATCH.md, not silently pretended away here.
    var onAccent: Color { self == .oshi ? Color(ChatPalette.outgoingInkUI) : .white }

    /// Icons, waveforms, links on MY bubble: brand violet on the light
    /// lavender, white on the dark purple; white on every other accent.
    var accentInk: Color { self == .oshi ? Color(ChatPalette.outgoingAccentInkUI) : .white }

    /// Secondary ink on MY bubble (sizes, site names, in-bubble time).
    var secondaryInk: Color {
        self == .oshi ? Color(ChatPalette.outgoingSecondaryInkUI) : .white.opacity(0.8)
    }
}

// MARK: - Shape

/// How square or round a bubble is. Corner radius is expressed in points and
/// applied to the same rounded rectangle everywhere, so a chat does not mix
/// shapes between text, media and replies.
enum BubbleShape: Int, CaseIterable, Identifiable, Codable {
    case round = 0       // pill-like, the friendliest
    case soft            // the current look
    case square          // architectural, close to a card
    // ⚠️ NEW CASES GO AT THE END, ALWAYS. The raw values are what is persisted;
    // inserting a case in the middle silently repaints every existing user's
    // chat with a shape they never chose.
    case capsule         // fully rounded — the ends are semicircles
    case slab            // barely rounded, wide corners: a printed card

    var id: Int { rawValue }

    var radius: CGFloat {
        switch self {
        case .round:   return 22
        case .soft:    return 18   // __CHAT_POLISH_2026_09_24__ was 15; 18 = Theme.Radius.lg = Android Modern
        case .square:  return 6
        case .capsule: return 26
        case .slab:    return 10
        }
    }

    /// How much the corner on the sender's side is flattened, as a fraction of
    /// `radius`. This is the asymmetry that makes a bubble read as pointing at
    /// someone rather than floating: the corner nearest the speaker tightens.
    /// `1.0` = no flattening.
    var tailCornerScale: CGFloat {
        switch self {
        case .round:   return 0.30
        case .soft:    return 0.30
        case .square:  return 1.00   // a card has no tail; flattening it looks broken
        case .capsule: return 0.18   // the roundest shape needs the strongest anchor
        case .slab:    return 1.00
        }
    }

    var titleKey: String {
        switch self {
        case .round:   return "bubble.shape.round"
        case .soft:    return "bubble.shape.soft"
        case .square:  return "bubble.shape.square"
        case .capsule: return "bubble.shape.capsule"
        case .slab:    return "bubble.shape.slab"
        }
    }
}

// MARK: - Manager

/// Reads and writes the user's bubble preferences.
///
/// `@AppStorage` is deliberately not used in the views: three screens need the
/// same values, and a shared observable object keeps them from drifting apart
/// (and re-renders all of them on a change).
final class BubbleStyleManager: ObservableObject {
    static let shared = BubbleStyleManager()

    private enum Keys {
        static let accent = "oshi.bubble.accent"
        static let shape = "oshi.bubble.shape"
        static let bold = "oshi.bubble.boldText"
        static let silhouette = "oshi.bubble.silhouette"
    }

    @Published var accent: BubbleAccent {
        didSet { UserDefaults.standard.set(accent.rawValue, forKey: Keys.accent) }
    }
    @Published var shape: BubbleShape {
        didSet { UserDefaults.standard.set(shape.rawValue, forKey: Keys.shape) }
    }
    /// Renders message text at semibold. Some people simply read a chat more
    /// easily that way, and it is a common request that is otherwise only
    /// reachable through the system-wide accessibility setting.
    @Published var boldText: Bool {
        didSet { UserDefaults.standard.set(boldText, forKey: Keys.bold) }
    }

    /// A playful outline, or `nil` for the ordinary rounded bubble.
    ///
    /// Stored as `rawValue + 1` so that 0 — the value `UserDefaults` returns for
    /// a key that was never written — means "off" rather than silently selecting
    /// `.comic` for every existing user on first launch after the update.
    @Published var silhouette: BubbleSilhouette? {
        didSet {
            UserDefaults.standard.set((silhouette?.rawValue ?? -1) + 1, forKey: Keys.silhouette)
        }
    }

    private init() {
        // An unknown stored value (an older build, a synced preference from a
        // newer one) must fall back to the default, never render nothing.
        let storedSilhouette = UserDefaults.standard.integer(forKey: Keys.silhouette)
        silhouette = storedSilhouette > 0 ? BubbleSilhouette(rawValue: storedSilhouette - 1) : nil
        accent = BubbleAccent(rawValue: UserDefaults.standard.integer(forKey: Keys.accent)) ?? .oshi
        shape = BubbleShape(rawValue: UserDefaults.standard.integer(forKey: Keys.shape)) ?? .soft
        boldText = UserDefaults.standard.bool(forKey: Keys.bold)
    }

    // MARK: Derived styling

    var outgoingFill: AnyShapeStyle {
        AnyShapeStyle(LinearGradient(colors: accent.stops,
                                     startPoint: .topLeading,
                                     endPoint: .bottomTrailing))
    }

    /// 🔧 (2026-08-18) The incoming surface is now `receivedBubbleColour`.
    ///
    /// The doc comment on `receivedBubbleColour` below has claimed since
    /// 2026-08-06 that 1:1 and group share one received surface. They did not:
    /// groups called `receivedBubbleColour` (GroupViews) while 1:1, media and
    /// the wallpaper preview all came through here and got the plain system
    /// grey. Two chats in the same app, two different bubbles. This is the line
    /// that makes the comment true.
    ///
    /// `scheme` is still taken so every call site is unchanged, but it is no
    /// longer branched on by hand: `receivedBubbleColour` resolves through a
    /// `UIColor { trait in … }`, which also follows an override on a subtree
    /// (`.environment(\.colorScheme)`) that a hand-rolled `if` here would miss.
    func incomingFill(_ scheme: ColorScheme) -> AnyShapeStyle {
        AnyShapeStyle(BubbleStyleManager.receivedBubbleColour)
    }

    var cornerRadius: CGFloat { shape.radius }

    /// How wide a message bubble may get.
    ///
    /// 55% of the screen, rather than the fixed 260/280pt that used to be
    /// hard-coded separately in the 1:1 view and the group view. A fixed point
    /// value was tuned for a 375pt iPhone: on a 440pt Pro Max it left the column
    /// looking narrow, and on an SE it crowded the edge. A fraction holds the
    /// same proportion on every device, which is what actually reads as
    /// deliberate.
    ///
    /// Bubbles hug their content, so this only bites on LONG messages — and it
    /// is those that set the visual rhythm of a thread.
    ///
    /// The absolute cap matters on Mac Catalyst and iPad, where the "screen" can
    /// be far wider than the chat column: 55% of a 1400pt window would be a
    /// paragraph 770pt across, which is past the point where a line is
    /// comfortable to read.
    // MARK: - Bubble colours

    /// The tint under a message SOMEONE ELSE sent.
    ///
    /// 🔧 (2026-08-06) Received bubbles were `Color(.systemGray5)` — the system
    /// grey every other app uses. Two problems: it says nothing about OSHI, and
    /// it was not even consistent, because the 1:1 view and the group view had
    /// each written their own. This is a brand-tinted surface: unmistakably
    /// OSHI, still clearly DIFFERENT from a sent bubble, and still quiet enough
    /// to read black or white text on.
    ///
    /// Deliberately NOT the full brand violet. A received bubble is the one you
    /// read most, and a saturated ground under body text is tiring — the tint is
    /// carried at low opacity over the system surface so it adapts to light and
    /// dark on its own.
    ///
    /// __CHAT_POLISH_2026_09_24__ Now a neutral, frosted-looking surface
    /// rather than a lavender blend: white in light (the wallpaper ground is
    /// lavender-tinted, so white separates from it instead of melting into
    /// the mesh), deep indigo `#1E1B2E` in dark. Opaque on purpose — see
    /// `UIColor.blended` for why a translucent bubble over an animated
    /// wallpaper is avoided. The edge is carried by `rim` + elevation.
    /// Same two values as Android `Oshi.Bubble.receivedLight/Dark`.
    static var receivedBubbleColour: Color {
        Color(ChatPalette.incomingUI)
    }

    /// The tint under a message YOU sent, given the surface's own accent.
    ///
    /// Groups pass their per-group colour so a group still reads as itself; a
    /// group with no colour set falls back to the brand.
    static func sentBubbleColour(_ accent: Color?) -> Color {
        // __BUBBLE_MEDIA_2026_11_21__ nil = the OSHI outgoing surface (pale
        // lavender in light, deep purple in dark), same as a 1:1 bubble.
        accent ?? Color(ChatPalette.outgoingSolidUI)
    }

    /// Ink on a group bubble I sent (the group surface is always the OSHI one).
    static var sentInk: Color { Color(ChatPalette.outgoingInkUI) }
    static var sentAccentInk: Color { Color(ChatPalette.outgoingAccentInkUI) }
    static var sentSecondaryInk: Color { Color(ChatPalette.outgoingSecondaryInkUI) }

    /// Static because it depends only on the screen, not on any user choice —
    /// so the four group-bubble sites can use it without each view having to
    /// observe the manager just to read a width.
    static var maxBubbleWidth: CGFloat {
        #if os(iOS)
        let screen = UIScreen.main.bounds.width
        #else
        let screen: CGFloat = 420
        #endif
        // __CHAT_POLISH_2026_09_24__ 55 % → 70 %, the same rule as Android
        // (`Oshi.Bubble.maxWidth`). At 55 % a paragraph on a 402 pt iPhone
        // wrapped at ~220 pt — five-word lines and tall bubbles; 70 % is the
        // messaging norm and leaves the other side's column clearly readable.
        return min(screen * 0.70, 420)
    }

    /// __FOLDABLE_LAYOUT_2026_11_16__ The same rule, applied to the width of
    /// the THREAD (`EnvironmentValues.chatColumnWidth`) rather than the screen.
    /// On a phone the two are equal, so nothing moves there. They differ in the
    /// wide layout's detail pane, in iPad Split View / Stage Manager, in a Mac
    /// window, and on iPhone Duo — where Apple asks apps not to read the main
    /// screen at all (two displays, ambiguous). 0 = not measured yet: the old
    /// screen-based value for that first frame.
    static func maxBubbleWidth(in column: CGFloat) -> CGFloat {
        guard column > 0 else { return maxBubbleWidth }
        return min(column * 0.70, 420)
    }

    /// Group TEXT bubbles run wider (Telegram-style, 82 %), but not without
    /// limit: past ~520 pt a line is too long to read, which a wide pane or an
    /// unfolded iPhone Duo in landscape easily exceeds.
    static func maxGroupTextBubbleWidth(in column: CGFloat) -> CGFloat {
        guard column > 0 else {
            #if os(iOS)
            return UIScreen.main.bounds.width * 0.82
            #else
            return 420 * 0.82
            #endif
        }
        return min(column * 0.82, 520)
    }

    /// Weight for message text. `.semibold` rather than `.bold`: at body size a
    /// full bold in a long thread reads as shouting.
    var textWeight: Font.Weight { boldText ? .semibold : .regular }

    // MARK: Shape & surface

    /// The outline one bubble is painted and clipped to.
    ///
    /// `tail` is the message's position in a run, not a user setting: only the
    /// LAST bubble of a run is anchored, so a burst of five messages reads as
    /// one column that touches down once instead of five identical lozenges.
    /// Making it a preference would need new strings in all 34 languages for a
    /// choice nobody asks to make.
    func outline(outgoing: Bool, tail: Bool) -> BubbleOutline {
        BubbleOutline(radius: cornerRadius, isOutgoing: outgoing, isTail: tail)
    }

    /// A hairline rim around the bubble.
    ///
    /// THIS is the readability fix, not decoration. A received bubble is a pale
    /// surface drawn with no stroke and no shadow, and `.customImage`
    /// (ChatWallpaperManager) lets a user put an ARBITRARY photo behind it —
    /// over a light photo the bubble's edge simply disappears and the text
    /// floats on the picture. The rim is drawn from the bubble's own ink
    /// (white on the dark outgoing accent, ink on the pale incoming surface),
    /// so it is defined on every backdrop without a colour that would have to
    /// be re-tuned per wallpaper.
    ///
    /// Kept under 0.15 alpha: a rim you can NAME is a border, and a border on
    /// every message turns a thread into a table.
    func rim(outgoing: Bool, _ scheme: ColorScheme) -> Color {
        if outgoing {
            // A white rim vanishes on the pale lavender: brand hairline there.
            return accent == .oshi ? Color(ChatPalette.outgoingRimUI) : .white.opacity(0.14)
        }
        // __CHAT_POLISH_2026_09_24__ A white bubble on a pale ground needs a
        // slightly firmer, violet-grey hairline than the old black 7 %.
        return scheme == .dark ? .white.opacity(0.08) : Color(ChatPalette.incomingRimLight)
    }

    /// Link ink inside a bubble.
    ///
    /// Outgoing stays WHITE deliberately. A link on the accent gradient is
    /// already marked by an underline (ClickableTextView sets
    /// `.underlineStyle = .single`), which is the one link cue that survives
    /// colour blindness — tinting it, as a "make links look different" change
    /// would, only LOWERS its contrast against the same background the body
    /// text has to clear.
    ///
    /// Incoming is the case that was actually broken: the raw brand violet
    /// (0.337, 0.086, 0.925) is ~2.1:1 on the dark received surface — a link
    /// you cannot read is worse than a link that looks like text. Dark mode
    /// therefore gets a lightened brand; light mode keeps the brand itself,
    /// which already clears 4.5:1 on the pale surface.
    func linkColour(outgoing: Bool, _ scheme: ColorScheme) -> Color {
        if outgoing { return accent.accentInk }
        return scheme == .dark
            ? Color(red: 0.72, green: 0.60, blue: 1.0)
            : Theme.brand
    }
}

// MARK: - Bubble outline

/// The bubble silhouette: continuous corners, with the corner on the TAIL side
/// tightened.
///
/// Two deliberate choices:
///
/// * `.continuous` rather than the `.circular` corners `.cornerRadius(_:)`
///   gives. A circular corner meets the straight edge with a visible break in
///   curvature; a squircle does not, which is why every system surface on iOS
///   uses one. At radius 22 the difference is plainly visible.
/// * leading/trailing rather than left/right. `UnevenRoundedRectangle` mirrors
///   itself under a right-to-left layout, so the tail lands on the same side as
///   the bubble does in Arabic and Hebrew — both of which OSHI ships — without
///   a second rule that could disagree with the `Spacer` that positions it.
struct BubbleOutline: Shape {
    let radius: CGFloat
    let isOutgoing: Bool
    let isTail: Bool

    func path(in rect: CGRect) -> Path {
        // Never a hard 90°: a right angle on a coloured bubble reads as a card,
        // not as speech. ~28% keeps the tail corner clearly tighter than its
        // three neighbours at all three user radii (22 / 15 / 6), and the floor
        // keeps it from collapsing to a point on the squarest setting.
        let tail = max(radius * 0.28, 4)
        return UnevenRoundedRectangle(
            topLeadingRadius:     radius,
            bottomLeadingRadius:  (!isOutgoing && isTail) ? tail : radius,
            bottomTrailingRadius: ( isOutgoing && isTail) ? tail : radius,
            topTrailingRadius:    radius,
            style: .continuous
        ).path(in: rect)
    }
}

/// Paint + rim + elevation for one bubble, in the one order that works.
///
/// __BUBBLE_HUGS_2026_08_14__ still applies at every call site: this modifier
/// must be applied BEFORE any `.frame(maxWidth:)`. A `.frame(maxWidth:)` does
/// not shrink to its child, so capping first makes the fill paint the full cap
/// width and a one-word message gets a paragraph-wide bubble.
struct BubbleSurface: ViewModifier {
    let fill: AnyShapeStyle
    let outline: BubbleOutline
    let rim: Color
    /// __OLED_GLASS_2026_11_16__ Under OLED black a RECEIVED bubble is dark
    /// glass: #1C1C1E at `OLEDGlass.cheapFillAlpha`, a top sheen and the
    /// gradient hairline. No backdrop blur — this runs once per row of an eager
    /// stack. Reduce Transparency keeps it opaque.
    /// __GLASS_ALL_THEMES_2026_11_21__ With the glass option on, the classic
    /// themes get the same treatment: Light = white at 0.80 (the wallpaper's
    /// lavender and doodles show through, frosted) with a lit white rim over the
    /// usual indigo rim; Dark = the indigo surface at 0.92 with the white
    /// hairline and sheen. Increase Contrast keeps the bubble opaque too.
    @Environment(\.glassStyle) private var glassStyle
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    private var glass: Bool { glassStyle.isGlass && !outline.isOutgoing }
    private var translucent: Bool { glass && !reduceTransparency && contrast != .increased }
    private var fillAlpha: Double {
        switch glassStyle {
        case .oled: return OLEDGlass.cheapFillAlpha
        case .dark: return ClassicGlass.darkFillAlpha
        case .light: return ClassicGlass.lightFillAlpha
        case .none: return 1
        }
    }

    func body(content: Content) -> some View {
        content
            // __CHAT_POLISH_2026_09_24__ Outgoing only: a faint top sheen and
            // a lit upper edge, so the violet reads as a surface catching the
            // light rather than a flat sticker. The sheen lives IN the
            // background layer (under the glyphs) and is capped by
            // `ChatPalette.outgoingSheen`, which the contrast test composites
            // onto the light stop before measuring white on it.
            .background {
                ZStack {
                    outline.fill(fill)
                        .opacity(translucent ? fillAlpha : 1)
                    if translucent {
                        switch glassStyle {
                        case .oled: outline.fill(OLEDGlass.bubbleSheen)
                        case .dark: outline.fill(OLEDGlass.bubbleSheen)
                        case .light: outline.fill(ClassicGlass.lightSheenGradient)
                        case .none: EmptyView()
                        }
                    }
                    if outline.isOutgoing {
                        outline.fill(LinearGradient(colors: [.white.opacity(ChatPalette.outgoingSheen),
                                                             .white.opacity(0)],
                                                    startPoint: .top, endPoint: .center))
                    }
                }
            }
            .overlay {
                if glass {
                    switch glassStyle {
                    case .light:
                        // The usual indigo rim keeps the bubble's outline on a
                        // pale wallpaper; the white rim inside it is the light.
                        ZStack {
                            outline.stroke(rim, lineWidth: 0.5)
                            outline.stroke(ClassicGlass.rim(.light, increasedContrast: contrast == .increased),
                                           lineWidth: 1)
                                .padding(0.5)
                        }
                    default:
                        outline.stroke(ClassicGlass.rim(glassStyle, increasedContrast: contrast == .increased),
                                       lineWidth: OLEDGlass.hairlineWidth)
                    }
                } else {
                    outline.stroke(rim, lineWidth: 0.5)
                }
            }
            .overlay {
                if outline.isOutgoing {
                    outline.stroke(LinearGradient(colors: [.white.opacity(0.28), .white.opacity(0)],
                                                  startPoint: .top, endPoint: .center),
                                   lineWidth: 0.75)
                        .allowsHitTesting(false)
                }
            }
            // The shadow is read from the COMPOSITED alpha, and the fill above
            // is opaque and covers the whole silhouette — so this shadows the
            // bubble's outline only and never blurs the glyphs sitting on it.
            // `Elevation.low` rather than a new literal: it is already the
            // app's lowest step, and a bubble is the last thing that should
            // look like it is floating off the wallpaper.
            .elevation(Theme.Elevation.low)
    }
}

extension View {
    /// Apply the standard bubble surface. See `BubbleSurface` for the ordering
    /// rule this must respect.
    ///
    /// `silhouette` swaps the rounded rectangle for one of the playful outlines
    /// in `BubbleFunShapes` — comic burst, heart, car, ball, cat, dog. When it is
    /// set, the text first gets that shape's `coreInsets` so the message keeps a
    /// clean rectangle to live in and no glyph ever lands in a wheel or an ear.
    /// See the header of `BubbleFunShapes.swift` for why that is the whole trick.
    @ViewBuilder
    func bubbleSurface(silhouetteOverride: BubbleSilhouette? = nil,
                       fill: AnyShapeStyle, outline: BubbleOutline, rim: Color) -> some View {
        if let silhouette = silhouetteOverride {
            self
                .padding(silhouette.coreInsets)
                .background(silhouette.filled(with: fill))
                .overlay(BubbleMarkings(silhouette: silhouette, ink: rim))
                .elevation(Theme.Elevation.low)
        } else {
            modifier(BubbleSurface(fill: fill, outline: outline, rim: rim))
        }
    }
}

// MARK: - Chat palette

/// __CHAT_POLISH_2026_09_24__ The colours of a conversation that are NOT a
/// user choice: the wallpaper ground, the incoming surface, and every piece of
/// ink that sits directly on the wallpaper (timestamps, "edited", ticks,
/// transport chips).
///
/// One table, the same hex values as Android `Oshi.Chat` / `Oshi.Bubble`, so
/// the two apps cannot drift. Each ink is measured against the WORST ground it
/// can land on (lavender ground + brand glow + a mesh node) in
/// `ChatWallpaperAppearanceTests` — the old `.secondary` timestamp measured
/// 3.44:1 on the mesh, which is what this replaces.
///
/// Everything is a trait-resolved `UIColor`, so an `.environment(\.colorScheme)`
/// override (the wallpaper TONE forcing light content on a dark phone) picks
/// the right half automatically.
enum ChatPalette {
    static func rgb(_ hex: UInt32, _ alpha: CGFloat = 1) -> UIColor {
        UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255,
                green: CGFloat((hex >> 8) & 0xFF) / 255,
                blue: CGFloat(hex & 0xFF) / 255,
                alpha: alpha)
    }

    private static func dynamic(_ light: UInt32, _ dark: UInt32) -> UIColor {
        UIColor { $0.userInterfaceStyle == .dark ? rgb(dark) : rgb(light) }
    }

    // Incoming bubble.
    static let incomingLight = rgb(0xFFFFFF)
    /// __BUBBLE_MEDIA_2026_11_21__ #1E1B2E → #22252B: a neutral elevated
    /// surface on the new neutral dark ground (spec §2).
    static let incomingDark = rgb(0x22252B)
    static let incomingRimLight = rgb(0x2A1F4D, 0.12)
    /// __OLED_BLACK_2026_11_16__ The received surface for every scheme: white /
    /// indigo `#1E1B2E` / `#1C1C1E` on true black when OLED black is active
    /// (the indigo reads as a purple smear on a black ground).
    static let incomingUI = UIColor { trait in
        if OLEDBlack.isActive(in: trait) { return OLEDBlack.incomingBubbleUI }
        return trait.userInterfaceStyle == .dark ? incomingDark : incomingLight
    }

    // Outgoing (BubbleAccent.oshi and every group bubble I sent).
    // __BUBBLE_MEDIA_2026_11_21__ measured in BubblePaletteContrastTests.
    static let outgoingTopUI = dynamic(0xEEE7FF, 0x5B47B3)
    static let outgoingBottomUI = dynamic(0xE5DBFF, 0x4A3B8F)
    /// Flat version for surfaces that take one colour (group bubbles).
    static let outgoingSolidUI = dynamic(0xEAE2FF, 0x5241A1)
    static let outgoingInkUI = dynamic(0x1C1631, 0xFFFFFF)
    static let outgoingAccentInkUI = dynamic(0x5616EC, 0xFFFFFF)
    static let outgoingSecondaryInkUI = UIColor {
        $0.userInterfaceStyle == .dark ? rgb(0xFFFFFF, 0.8) : rgb(0x5A5275)
    }
    /// Glyph on an accent-ink circle (voice-note play button) on my bubble.
    static let outgoingGlyphOnAccentUI = dynamic(0xFFFFFF, 0x5616EC)
    static let outgoingRimUI = UIColor {
        $0.userInterfaceStyle == .dark ? rgb(0xFFFFFF, 0.14) : rgb(0x5616EC, 0.16)
    }

    /// White sheen at the top of an outgoing bubble (fades out by mid-height).
    static let outgoingSheen: Double = 0.07

    // Wallpaper ground: a vertical ramp, plus two static glows for depth.
    // __BUBBLE_MEDIA_2026_11_21__ Light: a touch deeper and more neutral
    // (#F4F2F9 → #ECE9F4, was #FBFAFF → #F1EEFB) so a WHITE incoming bubble
    // separates from it. Dark: deep neutral #17181C → #0F1115 (was indigo
    // #120E24 → #07060D) with a much quieter violet glow; OLED stays black.
    static let groundLightTop = rgb(0xF4F2F9)
    static let groundLightBottom = rgb(0xECE9F4)
    static let groundDarkTop = rgb(0x17181C)
    static let groundDarkBottom = rgb(0x0F1115)
    static func brandGlow(dark: Bool) -> Double { dark ? 0.10 : 0.05 }
    static func accentGlow(dark: Bool) -> Double { dark ? 0.05 : 0.04 }

    /// Timestamps, "edited", sent/delivered ticks — anything on the wallpaper.
    static let metaInkUI = dynamic(0x524D66, 0xABA6C4)
    static var metaInk: Color { Color(metaInkUI) }

    /// Read receipt (two ticks). Green, as before — but a green that clears
    /// 4.5:1 on the light ground (system green is 2.2:1 on white).
    /// __READ_TICK_WAS_INVISIBLE_IN_LIGHT_2026_11_03__ The light value was
    /// `0x0B5A2E`, which sat 1.04:1 from the meta ink it has to differ from —
    /// a hue-only change at 14pt, invisible. See `Tokens.kt readTick` for the
    /// measurements; `0x11A24A` is 3.34:1 on white, which is what WCAG 1.4.11
    /// asks of a non-text graphic.
    static let readTickUI = dynamic(0x0B5A2E, 0x4ADE80)
    static var readTick: Color { Color(readTickUI) }

    enum Transport: CaseIterable { case internet, mesh, lora, pending, failed }

    /// Ink of a transport chip. The chip is a small frosted capsule — the
    /// incoming-bubble surface at `chipSurfaceAlpha` — tinted with this ink at
    /// `chipFill`, so it reads the same over a mesh node, a glow or a link.
    static func transportInkUI(_ t: Transport) -> UIColor {
        switch t {
        case .internet: return dynamic(0x5616EC, 0xB899FF)
        case .mesh:     return dynamic(0x0B5A2E, 0x5EE08A)
        case .lora:     return dynamic(0x0B5F59, 0x5EEAD4)
        case .pending:  return dynamic(0x92400E, 0xFDBA74)
        case .failed:   return dynamic(0xA51818, 0xFF8A8A)
        }
    }
    static func transportInk(_ t: Transport) -> Color { Color(transportInkUI(t)) }
    static let chipFill: Double = 0.10
    static let chipSurfaceAlpha: Double = 0.9
    static var chipSurface: Color {
        Color(incomingUI)
            .opacity(chipSurfaceAlpha)
    }

    // MARK: Reply quote
    //
    // __REPLY_QUOTE_ON_THE_WALLPAPER_2026_11_09__ Reported: "when I reply in
    // white mode, the quoted message is white text on a very light background".
    //
    // The 1:1 and group reply quotes are drawn ABOVE their bubble, in the row's
    // outer stack — NOT inside the bubble's fill. Their colours, though, were
    // picked as if they were inside it: `.white` / `white 0.9` on my side (the
    // ink of the purple bubble) and no surface at all, "because the bubble is
    // already a coloured surface". It is not under them. So on the light
    // lavender ground every quote of one of MY messages was white on near-white.
    //
    // A quote on the wallpaper needs its own surface and wallpaper-safe ink:
    // the incoming-bubble surface (`chipSurface`, so it also holds on a custom
    // photo wallpaper), a brand-ink accent and title, and a near-label body —
    // all dynamic, so they follow the wallpaper-pinned `chatScheme`.
    // `ChatPaletteQuoteTests` re-measures the contrast in both schemes.
    static let quoteAccentUI = dynamic(0x5616EC, 0xB899FF)
    static let quoteBodyUI = dynamic(0x2E2A3D, 0xD6D2E8)
    static var quoteAccent: Color { Color(quoteAccentUI) }
    static var quoteBody: Color { Color(quoteBodyUI) }
    /// Opaque version of the surface, for contrast measurement.
    static let quoteSurfaceUI = incomingUI
    static var quoteSurface: Color { chipSurface }
}

/// The ground every drawn OSHI backdrop paints first: a soft vertical ramp and
/// two static, very wide glows (brand top-trailing, blue bottom-leading) that
/// give the flat page some depth — lavender-white in light, deep indigo with a
/// violet glow in dark instead of flat black. Static: costs one gradient pass,
/// never animates, and Reduce Transparency drops the glows.
struct OSHIWallpaperGround: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.oledBlackActive) private var oledBlack

    var body: some View {
        let dark = scheme == .dark
        if oledBlack {
            // __OLED_BLACK_2026_11_16__ True black: no ramp, no glows.
            Color.black.allowsHitTesting(false)
        } else {
        GeometryReader { geo in
            let d = max(geo.size.width, geo.size.height)
            ZStack {
                LinearGradient(colors: dark
                                   ? [Color(ChatPalette.groundDarkTop), Color(ChatPalette.groundDarkBottom)]
                                   : [Color(ChatPalette.groundLightTop), Color(ChatPalette.groundLightBottom)],
                               startPoint: .top, endPoint: .bottom)
                if !reduceTransparency {
                    RadialGradient(colors: [Theme.brand.opacity(ChatPalette.brandGlow(dark: dark)), .clear],
                                   center: UnitPoint(x: 0.92, y: 0.04),
                                   startRadius: 0, endRadius: d * 0.75)
                    RadialGradient(colors: [Color(red: 0.180, green: 0.320, blue: 0.960)
                                                .opacity(ChatPalette.accentGlow(dark: dark)), .clear],
                                   center: UnitPoint(x: 0.05, y: 0.96),
                                   startRadius: 0, endRadius: d * 0.65)
                }
            }
        }
        .allowsHitTesting(false)
        }
    }
}

#if canImport(UIKit)
import UIKit

extension UIColor {
    /// Mix `amount` of `other` into this colour, in sRGB.
    ///
    /// Used for the received-bubble tint. Kept as a UIColor operation rather
    /// than a SwiftUI `.opacity` overlay so the result is a single opaque
    /// colour: a translucent bubble over a moving wallpaper shimmers, and these
    /// sit on top of the animated backdrops.
    func blended(with other: UIColor, amount: CGFloat) -> UIColor {
        var r1: CGFloat = 0, g1: CGFloat = 0, b1: CGFloat = 0, a1: CGFloat = 0
        var r2: CGFloat = 0, g2: CGFloat = 0, b2: CGFloat = 0, a2: CGFloat = 0
        guard getRed(&r1, green: &g1, blue: &b1, alpha: &a1),
              other.getRed(&r2, green: &g2, blue: &b2, alpha: &a2) else { return self }
        let t = max(0, min(1, amount))
        return UIColor(red: r1 + (r2 - r1) * t,
                       green: g1 + (g2 - g1) * t,
                       blue: b1 + (b2 - b1) * t,
                       alpha: a1)
    }
}
#endif

// MARK: - Media in a bubble

/// __BUBBLE_MEDIA_2026_11_21__ How big a photo / video / GIF is drawn inside a
/// message bubble. Pure (no view, no screen) so `BubbleMediaLayoutTests` can
/// pin every rule. Same rules as Android and desktop (BUBBLE_MEDIA_SPEC §1):
///
///  * the bubble's width IS the picture's width — nothing beside it;
///  * width = natural width, clamped to [minWidth, maxWidth] (a small picture is
///    scaled UP to the minimum rather than leaving a gap);
///  * the aspect ratio is kept, but the height is clamped to
///    [0.5 × width, 1.3 × width]: a very tall picture or a panorama is
///    centre-cropped to that, like Telegram, instead of letterboxed.
///
/// THE BUG THIS CLOSES ("a part that is empty on the side" in group/channel
/// posts): the group photo was drawn `.scaledToFit()` inside
/// `.frame(maxWidth: 260, maxHeight: 300)`, so a portrait came out ~187 pt
/// wide, while the caption under it was free to wrap at the bubble cap
/// (70 % of the screen, ~281 pt). The bubble took the CAPTION's width and the
/// picture sat on its leading edge with a band of bubble fill beside it.
/// Here the picture fixes the width and the caption wraps inside it.
enum BubbleMediaLayout {
    static let minWidth: CGFloat = 200
    static let maxHeightRatio: CGFloat = 1.3
    static let minHeightRatio: CGFloat = 0.5
    static let chatFraction: CGFloat = 0.75
    static let chatCap: CGFloat = 420
    static let channelFraction: CGFloat = 0.90
    static let channelCap: CGFloat = 560

    struct Result: Equatable {
        /// Size the media is drawn at (points, whole numbers).
        let size: CGSize
        /// True when the picture does not fit that box at its own ratio and is
        /// centre-cropped (`scaledToFill`) to it.
        let cropped: Bool
    }

    /// The widest a media bubble may be in a chat (1:1 and groups) or as a
    /// channel post (a card, not a chat line) of `containerWidth`.
    static func maxWidth(containerWidth: CGFloat, channel: Bool) -> CGFloat {
        guard containerWidth.isFinite, containerWidth > 0 else { return minWidth }
        let w = channel ? min(containerWidth * channelFraction, channelCap)
                        : min(containerWidth * chatFraction, chatCap)
        return w.rounded(.down)
    }

    /// Display size for a picture of `natural` size (pixels or points — only the
    /// ratio and the width matter) in a bubble at most `maxWidth` wide.
    /// An unknown / invalid size gets a 4:3 box at full width, so a placeholder
    /// still has a stable height.
    static func displaySize(natural: CGSize, maxWidth: CGFloat) -> Result {
        let cap = max(1, maxWidth.isFinite ? maxWidth : minWidth)
        let floor = min(minWidth, cap)
        guard natural.width.isFinite, natural.height.isFinite,
              natural.width > 0, natural.height > 0 else {
            return Result(size: CGSize(width: cap.rounded(), height: (cap * 0.75).rounded()), cropped: false)
        }
        let width = min(cap, max(floor, natural.width)).rounded()
        let ratio = natural.height / natural.width
        let clamped = min(maxHeightRatio, max(minHeightRatio, ratio))
        let height = (width * clamped).rounded()
        return Result(size: CGSize(width: width, height: height), cropped: abs(clamped - ratio) > 0.0001)
    }

    /// The width a chat column offers. `UIScreen` like `maxBubbleWidth`: rows
    /// are built outside any GeometryReader. On Mac Catalyst / iPad the caps
    /// above keep a wide window from producing a poster-sized bubble.
    static var screenWidth: CGFloat {
        #if os(iOS)
        return UIScreen.main.bounds.width
        #else
        return 420
        #endif
    }

    /// Pixel size of encoded image bytes, read from the header only (no decode),
    /// with the EXIF orientation applied — a portrait phone photo is stored
    /// landscape + "rotate 90°". Lets the placeholder take the FINAL size before
    /// the picture is decoded, so the list does not jump when it arrives.
    static func pixelSize(of data: Data) -> CGSize? {
        guard let src = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue,
              let h = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue,
              w > 0, h > 0 else { return nil }
        let orientation = (props[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
        return (5...8).contains(orientation) ? CGSize(width: h, height: w) : CGSize(width: w, height: h)
    }
}

/// __BUBBLE_MEDIA_2026_11_21__ Time (and, on my side, ticks) in a small dark
/// translucent pill on the picture's bottom-trailing corner, for a media-only
/// bubble — instead of a line under the picture. White on black 55 % clears
/// 4.7:1 even over a pure-white photo (BubblePaletteContrastTests).
struct MediaTimePill: View {
    let time: String
    var status: DeliveryStatus? = nil

    static let backdropOpacity: Double = 0.55

    var body: some View {
        HStack(spacing: 3) {
            Text(time)
                .font(.caption2.weight(.medium))
                .monospacedDigit()
            if let status {
                switch status {
                case .sent:
                    Image(systemName: "checkmark").font(.scaled(9, weight: .bold))
                case .delivered:
                    HStack(spacing: -3) {
                        Image(systemName: "checkmark").font(.scaled(9, weight: .bold))
                        Image(systemName: "checkmark").font(.scaled(9, weight: .bold))
                    }
                    .accessibilityLabel("status.delivered".localized)
                case .read:
                    // A light green (#86EFAC): ≥ 3:1 on the pill even over a
                    // white photo (BubblePaletteContrastTests).
                    HStack(spacing: -3) {
                        Image(systemName: "checkmark").font(.scaled(9, weight: .bold))
                        Image(systemName: "checkmark").font(.scaled(9, weight: .bold))
                    }
                    .foregroundColor(Color(ChatPalette.rgb(0x86EFAC)))
                    .accessibilityLabel("status.read".localized)
                default:
                    EmptyView()
                }
            }
        }
        .foregroundColor(.white)
        .padding(.horizontal, 7)
        .padding(.vertical, 3)
        .background(Capsule().fill(Color.black.opacity(Self.backdropOpacity)))
        .padding(6)
        .accessibilityElement(children: .combine)
    }
}
