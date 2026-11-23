//
//  ChatWallpaperManager.swift
//  OSHI - WhatsApp-style Chat Wallpapers
//
//  Allows users to set different backgrounds for each conversation
//

import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

// MARK: - Brand Color
private let appBrandColor = Theme.brand

// MARK: - Wallpaper Manager

class ChatWallpaperManager: ObservableObject {
    static let shared = ChatWallpaperManager()
    
    @Published var wallpapers: [String: ChatWallpaper] = [:]  // conversationId -> wallpaper
    /// What every conversation gets until someone says otherwise — 1:1 and
    /// groups alike, since both read this same value.
    ///
    /// The drifting mesh: peers and the links between them. It is the picture of
    /// what the app actually does, and it is the one backdrop that says
    /// something about the product rather than being decoration.
    ///
    /// `loadWallpapers` overwrites this from disk when a choice has been stored,
    /// so changing the initial value moves ONLY the people who never picked —
    /// including anyone who deliberately chose plain.
    @Published var defaultWallpaper: ChatWallpaper = .oshiDynamic(OSHIWallpaper.constellation.rawValue)
    
    private let wallpapersKey = "chatWallpapers"
    private let defaultWallpaperKey = "defaultChatWallpaper"

    // MARK: - Tone (light / dark), independent of the system appearance
    //
    // __WALLPAPER_TONE_2026_09_24__ The OSHI backdrops draw over
    // `systemBackground`, so in dark mode the white-with-violet mesh became a
    // black one and there was no way to keep the white one. The tone is a
    // LOCAL display preference: it is never broadcast with the wallpaper (the
    // peer's phone has its own appearance) and older builds never see it.
    // See `ChatWallpaperAppearance` for how it resolves.

    /// What every conversation uses unless it has its own tone.
    @Published var defaultTone: ChatWallpaperTone = .automatic
    /// Per-conversation overrides (conversationId -> tone).
    @Published var tones: [String: ChatWallpaperTone] = [:]

    private let defaultToneKey = "chatWallpaperTone"
    private let tonesKey = "chatWallpaperTones"
    
    // MARK: - Preset Colors
    //
    // Rebuilt 2026-08-05. The old set was six washed pastels and three muddy
    // darks — the palette every messenger shipped in 2015. These are picked for
    // the job a chat background actually does: sit under text at ~4.5:1 without
    // tinting it. Lights are near-white with a breath of hue rather than
    // saturated; darks are deep and slightly cool so white type stays crisp.
    static let presetColors: [Color] = [
        Color.clear,                                    // system default
        Color(red: 0.976, green: 0.976, blue: 0.984),   // paper
        Color(red: 0.961, green: 0.949, blue: 0.933),   // warm sand
        Color(red: 0.937, green: 0.953, blue: 0.976),   // cool mist
        Color(red: 0.929, green: 0.961, blue: 0.941),   // sage
        Color(red: 0.953, green: 0.941, blue: 0.969),   // lilac
        Color(red: 0.098, green: 0.098, blue: 0.118),   // graphite
        Color(red: 0.071, green: 0.086, blue: 0.137),   // midnight
        Color(red: 0.078, green: 0.114, blue: 0.106),   // pine
        Color(red: 0.125, green: 0.086, blue: 0.157),   // plum
    ]
    
    // MARK: - Preset Emoji Patterns
    static let emojiPatterns: [(name: String, emojis: [String], bgColor: Color)] = [
        ("Locks", ["🔒", "🔐", "🔑", "🛡️"], Color(red: 0.15, green: 0.20, blue: 0.30)),
        ("Secret", ["🤫", "👀", "🕵️", "🔮"], Color(red: 0.20, green: 0.15, blue: 0.25)),
        ("Mesh Network", ["📡", "🌐", "📶", "⚡"], Color(red: 0.10, green: 0.25, blue: 0.20)),
        ("Privacy", ["👁️‍🗨️", "🙈", "🔏", "✨"], Color(red: 0.25, green: 0.20, blue: 0.30)),
        ("Crypto", ["₿", "🪙", "💎", "🔗"], Color(red: 0.20, green: 0.18, blue: 0.12)),
        ("Love", ["❤️", "💕", "💖", "💝"], Color(red: 0.30, green: 0.15, blue: 0.20)),
        ("Stars", ["⭐", "✨", "💫", "🌟"], Color(red: 0.10, green: 0.15, blue: 0.30)),
        ("Nature", ["🌿", "🍃", "🌸", "🦋"], Color(red: 0.15, green: 0.25, blue: 0.18)),
        ("Night", ["🌙", "⭐", "🌌", "💜"], Color(red: 0.08, green: 0.08, blue: 0.15)),
        ("Ocean", ["🌊", "🐚", "🦀", "💙"], Color(red: 0.10, green: 0.20, blue: 0.30)),
    ]
    
    // MARK: - OSHI Themed Patterns
    //
    // Rebuilt 2026-08-05. The old set mixed an emoji with capital letters in one
    // motif ("🛡️ O S H I") — the emoji renders in full colour at a completely
    // different weight from the glyphs beside it, so the tile never read as a
    // pattern. Every motif is now a single coherent character family, drawn in
    // one colour, so it tiles as texture instead of as a row of mismatched
    // stickers. Backgrounds are deep and desaturated; foregrounds are picked to
    // land quiet against them.
    static let oshiPatterns: [(name: String, symbols: [String], bgColor: Color, fgColor: Color)] = [
        // The brand mark, tiled as typography.
        ("OSHI", ["O", "S", "H", "I"],
         Color(red: 0.055, green: 0.055, blue: 0.086), appBrandColor),
        // Ciphertext.
        ("Hex", ["A", "B", "C", "D", "E", "F", "0", "1", "2", "3", "4", "5"],
         Color(red: 0.047, green: 0.059, blue: 0.098), Color(red: 0.45, green: 0.72, blue: 1.0)),
        ("Binary", ["0", "1"],
         Color(red: 0.031, green: 0.071, blue: 0.055), Color(red: 0.30, green: 0.85, blue: 0.55)),
        // Nodes and links — the mesh, as punctuation.
        ("Mesh", ["·", "•", "◦", "○", "◍"],
         Color(red: 0.067, green: 0.067, blue: 0.086), Color(red: 0.70, green: 0.72, blue: 0.82)),
        // Signal strength.
        ("Signal", ["▁", "▃", "▅", "▇"],
         Color(red: 0.075, green: 0.055, blue: 0.110), Color(red: 0.78, green: 0.58, blue: 1.0)),
        // Waves — LoRa.
        ("Waves", ["∿", "≈", "~"],
         Color(red: 0.043, green: 0.078, blue: 0.106), Color(red: 0.40, green: 0.80, blue: 0.85)),
    ]
    
    // MARK: - Gradient Presets
    // Rebuilt 2026-08-05. The old set used raw system colours (.orange, .pink,
    // .cyan) at full saturation — vivid on their own, unreadable under text.
    // These are muted and tonal: each ramp stays within a narrow slice of the
    // wheel so it reads as one surface rather than as a rainbow behind a chat.
    static let gradientPresets: [(name: String, colors: [Color])] = [
        ("Dusk",    [Color(red: 0.184, green: 0.145, blue: 0.353),
                     Color(red: 0.298, green: 0.176, blue: 0.365),
                     Color(red: 0.106, green: 0.098, blue: 0.204)]),
        ("Deep",    [Color(red: 0.078, green: 0.145, blue: 0.290),
                     Color(red: 0.098, green: 0.243, blue: 0.353),
                     Color(red: 0.055, green: 0.098, blue: 0.180)]),
        ("Pine",    [Color(red: 0.075, green: 0.180, blue: 0.157),
                     Color(red: 0.114, green: 0.259, blue: 0.212),
                     Color(red: 0.047, green: 0.110, blue: 0.106)]),
        ("Ember",   [Color(red: 0.290, green: 0.145, blue: 0.145),
                     Color(red: 0.376, green: 0.208, blue: 0.145),
                     Color(red: 0.161, green: 0.086, blue: 0.098)]),
        ("Aurora",  [Color(red: 0.078, green: 0.216, blue: 0.243),
                     Color(red: 0.153, green: 0.180, blue: 0.353),
                     Color(red: 0.255, green: 0.153, blue: 0.322)]),
        ("Paper",   [Color(red: 0.976, green: 0.973, blue: 0.965),
                     Color(red: 0.945, green: 0.941, blue: 0.953),
                     Color(red: 0.925, green: 0.929, blue: 0.949)]),
    ]
    
    private init() {
        loadWallpapers()
    }
    
    // MARK: - Get Wallpaper for Conversation
    
    func getWallpaper(for conversationId: String) -> ChatWallpaper {
        return wallpapers[conversationId] ?? defaultWallpaper
    }
    
    // MARK: - Set Wallpaper
    
    func setWallpaper(_ wallpaper: ChatWallpaper, for conversationId: String) {
        wallpapers[conversationId] = wallpaper
        saveWallpapers()
        objectWillChange.send()
        NotificationCenter.default.post(
            name: .init("OSHIWallpaperChanged"),
            object: nil,
            userInfo: ["conversationId": conversationId]
        )
    }
    
    func setDefaultWallpaper(_ wallpaper: ChatWallpaper) {
        defaultWallpaper = wallpaper
        saveDefaultWallpaper()
        objectWillChange.send()
    }
    
    func removeWallpaper(for conversationId: String) {
        wallpapers.removeValue(forKey: conversationId)
        saveWallpapers()
        removeTone(for: conversationId)
        objectWillChange.send()
    }

    // MARK: - Tone

    func tone(for conversationId: String) -> ChatWallpaperTone {
        tones[conversationId] ?? defaultTone
    }

    func setTone(_ tone: ChatWallpaperTone, for conversationId: String) {
        tones[conversationId] = tone
        saveTones()
    }

    func setDefaultTone(_ tone: ChatWallpaperTone) {
        defaultTone = tone
        UserDefaults.standard.set(tone.rawValue, forKey: defaultToneKey)
    }

    func removeTone(for conversationId: String) {
        guard tones.removeValue(forKey: conversationId) != nil else { return }
        saveTones()
    }

    /// How a conversation actually renders right now, given the system scheme.
    func appearance(for conversationId: String, system: ColorScheme) -> ChatWallpaperAppearance {
        ChatWallpaperAppearance.resolve(wallpaper: getWallpaper(for: conversationId),
                                        tone: tone(for: conversationId),
                                        system: system)
    }

    private func saveTones() {
        let raw = tones.mapValues(\.rawValue)
        UserDefaults.standard.set(raw, forKey: tonesKey)
    }

    // MARK: - 🔧 Wallpaper Sync (Share wallpaper with conversation participants)

    /// Wallpaper update payload for syncing between users
    struct WallpaperUpdatePayload: Codable {
        var type: String = "wallpaper_update"
        let conversationId: String
        let wallpaperData: Data  // Encoded ChatWallpaper
        let timestamp: Double
        let senderName: String?
        var version: Int = 1
        var customImageBase64: String? = nil  // 🔧 FIX: Include custom image data for sync
    }

    /// Create wallpaper update payload for sending to participants
    func createWallpaperUpdatePayload(wallpaper: ChatWallpaper, conversationId: String, senderName: String?) -> WallpaperUpdatePayload? {
        guard let wallpaperData = try? JSONEncoder().encode(wallpaper) else {
            OshiLog.messaging.info("❌ Failed to encode wallpaper for sync")
            return nil
        }

        // 🔧 FIX: For custom images, include the actual image data
        var customImageBase64: String? = nil
        if case .customImage(let filename) = wallpaper {
            if let image = loadCustomImage(filename: filename),
               let imageData = image.jpegData(compressionQuality: 0.6) {
                customImageBase64 = imageData.base64EncodedString()
                OshiLog.messaging.info("🎨 Including custom image data in wallpaper sync (\(imageData.count) bytes)")
            }
        }

        return WallpaperUpdatePayload(
            conversationId: conversationId,
            wallpaperData: wallpaperData,
            timestamp: Date().timeIntervalSince1970,
            senderName: senderName,
            customImageBase64: customImageBase64
        )
    }

    /// Process incoming wallpaper update from another participant
    func processWallpaperUpdate(payload: WallpaperUpdatePayload) -> (wallpaper: ChatWallpaper, senderName: String?)? {
        guard var wallpaper = try? JSONDecoder().decode(ChatWallpaper.self, from: payload.wallpaperData) else {
            OshiLog.messaging.info("❌ Failed to decode wallpaper from sync payload")
            return nil
        }

        // 🔧 FIX: For custom images, save the received image data locally
        if case .customImage(_) = wallpaper, let base64 = payload.customImageBase64 {
            if let imageData = Data(base64Encoded: base64),
               let image = UIImage(data: imageData),
               let newFilename = saveCustomImage(image, for: payload.conversationId) {
                wallpaper = .customImage(newFilename)
                OshiLog.messaging.info("🎨 Saved received custom wallpaper image as \(newFilename)")
            } else {
                OshiLog.messaging.info("❌ Failed to decode custom wallpaper image from sync")
                // Fall back to no wallpaper if custom image sync fails
                wallpaper = .none
            }
        }

        // Apply the wallpaper locally
        wallpapers[payload.conversationId] = wallpaper
        saveWallpapers()
        NotificationCenter.default.post(
            name: .init("OSHIWallpaperChanged"),
            object: nil,
            userInfo: ["conversationId": payload.conversationId]
        )

        // 🔧 FIX: SwiftUI updates MUST happen on main thread
        DispatchQueue.main.async { [weak self] in
            self?.objectWillChange.send()
        }

        OshiLog.messaging.info("🎨 Applied synced wallpaper for conversation \(payload.conversationId.prefix(8))...")
        return (wallpaper, payload.senderName)
    }

    /// Broadcast wallpaper change to conversation participants
    func broadcastWallpaperUpdate(wallpaper: ChatWallpaper, conversationId: String, senderName: String?) {
        guard let payload = createWallpaperUpdatePayload(wallpaper: wallpaper, conversationId: conversationId, senderName: senderName),
              let payloadData = try? JSONEncoder().encode(payload) else {
            OshiLog.messaging.info("❌ Failed to create wallpaper update payload")
            return
        }

        // Post notification for MessageManager to handle broadcasting
        NotificationCenter.default.post(
            name: NSNotification.Name("BroadcastWallpaperUpdate"),
            object: nil,
            userInfo: [
                "payload": payloadData,
                "conversationId": conversationId,
                "type": "wallpaper_update"
            ]
        )

        OshiLog.messaging.info("🎨 Wallpaper update broadcast initiated for \(conversationId.prefix(8))...")
    }

    // MARK: - Save Custom Image
    
    func saveCustomImage(_ image: UIImage, for conversationId: String) -> String? {
        guard let data = image.jpegData(compressionQuality: 0.8) else { return nil }
        
        let filename = "wallpaper_\(conversationId)_\(Date().timeIntervalSince1970).jpg"
        let url = getDocumentsDirectory().appendingPathComponent(filename)
        
        do {
            try MediaCipher.write(data, to: url)  // __MEDIA_AT_REST_2026_09_22__ chiffré au repos
            return filename
        } catch {
            OshiLog.messaging.info("❌ Failed to save wallpaper: \(error)")
            return nil
        }
    }
    
    func loadCustomImage(filename: String) -> UIImage? {
        let url = getDocumentsDirectory().appendingPathComponent(filename)
        guard let data = try? MediaCipher.read(contentsOf: url) else { return nil }  // __MEDIA_AT_REST_2026_09_22__
        return UIImage(data: data)
    }
    
    private func getDocumentsDirectory() -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }
    
    // MARK: - Persistence
    
    private func saveWallpapers() {
        if let data = try? JSONEncoder().encode(wallpapers) {
            UserDefaults.standard.set(data, forKey: wallpapersKey)
        }
    }
    
    private func loadWallpapers() {
        if let data = UserDefaults.standard.data(forKey: wallpapersKey),
           let decoded = try? JSONDecoder().decode([String: ChatWallpaper].self, from: data) {
            wallpapers = decoded
        }
        
        // A stored value is a CHOICE and always wins — including a deliberate
        // "no background", which is why this cannot be a `?? .oshiSignature`
        // fallback on a decoded optional: someone who picked plain must keep
        // plain. Only the absence of any stored value means "never chose", and
        // that is the case the new default is for.
        if let data = UserDefaults.standard.data(forKey: defaultWallpaperKey),
           let decoded = try? JSONDecoder().decode(ChatWallpaper.self, from: data) {
            defaultWallpaper = decoded
        }

        if let raw = UserDefaults.standard.string(forKey: defaultToneKey),
           let tone = ChatWallpaperTone(rawValue: raw) {
            defaultTone = tone
        }
        if let raw = UserDefaults.standard.dictionary(forKey: tonesKey) as? [String: String] {
            tones = raw.compactMapValues(ChatWallpaperTone.init(rawValue:))
        }
    }
    
    private func saveDefaultWallpaper() {
        if let data = try? JSONEncoder().encode(defaultWallpaper) {
            UserDefaults.standard.set(data, forKey: defaultWallpaperKey)
        }
    }
}

// MARK: - Wallpaper Type

enum ChatWallpaper: Codable, Equatable {
    case none
    /// OSHI's own backdrop, and what a conversation looks like before anyone
    /// chooses anything. `.none` — a flat `systemBackground` — was the default,
    /// which made every chat look like a blank system view with coloured
    /// bubbles floating on it.
    ///
    /// Deliberately quiet: two very low-opacity brand blooms over the system
    /// background, so it adapts to light and dark on its own and leaves message
    /// contrast untouched. A backdrop that competes with the text is worse than
    /// no backdrop, and this one has to work under 34 languages and both themes
    /// without ever being looked at again.
    case oshiSignature
    /// An animated OSHI backdrop. Index into `OSHIDynamicStyle`.
    case oshiDynamic(Int)
    case solidColor(CodableColor)
    case gradient(CodableColor, CodableColor)
    case emojiPattern(Int)  // Index in emojiPatterns
    case oshiPattern(Int)   // Index in oshiPatterns
    case gradientPreset(Int)  // Index in gradientPresets
    case customImage(String)  // Filename in documents
    
    var isDefault: Bool {
        if case .none = self {
            return true
        }
        return false
    }
}

// MARK: - Codable Color Wrapper

struct CodableColor: Codable, Equatable {
    let red: Double
    let green: Double
    let blue: Double
    let alpha: Double
    
    var color: Color {
        Color(red: red, green: green, blue: blue, opacity: alpha)
    }
    
    var isClear: Bool {
        alpha == 0
    }
    
    init(_ color: Color) {
        let uiColor = UIColor(color)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        uiColor.getRed(&r, green: &g, blue: &b, alpha: &a)
        self.red = Double(r)
        self.green = Double(g)
        self.blue = Double(b)
        self.alpha = Double(a)
    }
}

// MARK: - Wallpaper Background View

struct ChatWallpaperView: View {
    let wallpaper: ChatWallpaper
    @Environment(\.colorScheme) var colorScheme
    
    var body: some View {
        wallpaperContent
            .ignoresSafeArea()
            .accessibilityHidden(true)
    }
    
    @ViewBuilder
    private var wallpaperContent: some View {
        switch wallpaper {
        case .none:
            Color(.systemBackground)

        case .oshiSignature:
            OSHISignatureBackground()

        case .oshiDynamic(let index):
            if let style = OSHIWallpaper(rawValue: index) {
                OSHIWallpaperView(style: style)
            } else {
                // An index this build does not know (older app, newer choice, or
                // a synced wallpaper from a peer) must not render a blank chat.
                OSHISignatureBackground()
            }

        case .solidColor(let codableColor):
            if codableColor.isClear {
                Color(.systemBackground)
            } else {
                codableColor.color
            }
            
        case .gradient(let color1, let color2):
            LinearGradient(
                colors: [color1.color, color2.color],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            
        case .emojiPattern(let index):
            if index < ChatWallpaperManager.emojiPatterns.count {
                let pattern = ChatWallpaperManager.emojiPatterns[index]
                EmojiPatternView(emojis: pattern.emojis, backgroundColor: pattern.bgColor)
            } else {
                Color(.systemBackground)
            }
            
        case .oshiPattern(let index):
            if index < ChatWallpaperManager.oshiPatterns.count {
                let pattern = ChatWallpaperManager.oshiPatterns[index]
                OSHIPatternView(
                    symbols: pattern.symbols,
                    backgroundColor: pattern.bgColor,
                    foregroundColor: pattern.fgColor
                )
            } else {
                Color(.systemBackground)
            }
            
        case .gradientPreset(let index):
            if index < ChatWallpaperManager.gradientPresets.count {
                let preset = ChatWallpaperManager.gradientPresets[index]
                LinearGradient(
                    colors: preset.colors,
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            } else {
                Color(.systemBackground)
            }
            
        case .customImage(let filename):
            if let image = ChatWallpaperManager.shared.loadCustomImage(filename: filename) {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            } else {
                Color(.systemBackground)
            }
        }
    }
}

// MARK: - OSHI Signature Background

/// The default conversation backdrop.
///
/// Two soft brand-violet blooms over `systemBackground`, plus a barely-there
/// vignette. Everything is expressed as an opacity ON TOP of the system colour
/// rather than as fixed hex, so it follows light and dark by construction — a
/// fixed brand tint would have been ~3:1 against text in dark mode, which is the
/// failure mode a hard-coded brand colour always has.
///
/// Opacities are low on purpose (0.10 / 0.07 light, 0.20 / 0.14 dark). They are
/// the highest values at which a violet wash still reads as texture rather than
/// as a coloured background competing with the bubbles.
struct OSHISignatureBackground: View {
    /// When false the blooms sit still — used for the picker's thumbnail, where
    /// several copies render at once and motion would be noise, not character.
    var animated: Bool = true

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// __OLED_BLACK_2026_11_16__ OLED black: the default backdrop is true black.
    @Environment(\.oledBlackActive) private var oledBlack

    @State private var drift = false

    private let brand = Theme.brand
    private let accent = Color(red: 0.180, green: 0.320, blue: 0.960)

    /// Whether the blooms actually move. Reduce Motion wins: someone who asked
    /// the system to stop animating gets the static backdrop, not a slower one.
    /// __CHAT_POLISH_2026_09_24__ Low Power Mode freezes it too.
    private var moves: Bool {
        animated && !reduceMotion && !reduceTransparency
            && !ProcessInfo.processInfo.isLowPowerModeEnabled
    }

    var body: some View {
        ZStack {
            // __CHAT_POLISH_2026_09_24__ The shared ground (ramp + static
            // glows) instead of a flat systemBackground, so "OSHI" sits in the
            // same family as the drawn backdrops and dark mode is deep indigo
            // rather than black.
            OSHIWallpaperGround()

            // Honour Reduce Transparency: someone who has asked the system for
            // flatter surfaces gets the flat surface, not a softer gradient.
            // OLED black wants the pixels OFF, so no blooms either.
            if !reduceTransparency && !oledBlack {
                GeometryReader { geo in
                    let d = max(geo.size.width, geo.size.height)
                    // Displacement is a FRACTION of the view, so the drift reads
                    // the same on a 6.1" phone and on a Mac window.
                    let dx = d * 0.055
                    let dy = d * 0.035
                    ZStack {
                        RadialGradient(
                            colors: [brand.opacity(topOpacity), .clear],
                            center: .topLeading,
                            startRadius: 0,
                            endRadius: d * 0.95
                        )
                        .offset(x: drift ? dx : -dx, y: drift ? dy : -dy)

                        RadialGradient(
                            colors: [accent.opacity(bottomOpacity), .clear],
                            center: .bottomTrailing,
                            startRadius: 0,
                            endRadius: d * 0.85
                        )
                        // Opposite phase, and a different period, so the two
                        // never line up into an obvious pulse.
                        .offset(x: drift ? -dx * 0.8 : dx * 0.8, y: drift ? -dy : dy)
                    }
                    // Long, gentle, and NOT synchronised between the two blooms.
                    // 26 s and 34 s are slow enough that the movement is felt
                    // rather than watched — a backdrop behind a scrolling list
                    // must never compete with the text on top of it.
                    .animation(moves ? .easeInOut(duration: 26).repeatForever(autoreverses: true)
                                     : nil,
                               value: drift)
                }
                .allowsHitTesting(false)
                // 🔧 FIX (2026-08-08): the SECOND `.drawingGroup()`, and the
                // same bug as the one removed from OSHIWallpapers — I missed
                // this one. It rasterises into a buffer sized to the SAFE-AREA
                // bounds, because the `.ignoresSafeArea()` that expands them is
                // applied further out in ChatWallpaperView. The bitmap is
                // narrower than the area it must fill and the difference shows
                // as an unpainted black strip down one edge — which is what
                // becomes visible when a media bubble pushes the layout.
                //
                // Nothing is lost: this subtree is two RadialGradients, already
                // a single composited layer, so the flatten pass had nothing to
                // flatten and charged a texture upload per frame for it.
            }
        }
        .onAppear { if moves { drift = true } }
    }

    private var topOpacity: Double { colorScheme == .dark ? 0.20 : 0.10 }
    private var bottomOpacity: Double { colorScheme == .dark ? 0.14 : 0.07 }
}

// The animated backdrops now live in OSHIWallpapers.swift — they are drawn
// rather than shipped as raster art, so they stay sharp at any size.

// MARK: - Emoji Pattern View

/// A slow diagonal drift for a tiled pattern.
///
/// The pattern is drawn ONCE into a canvas oversized by one tile in each
/// direction, then the whole canvas is moved with `.offset()`. That matters: an
/// animated `Canvas` would re-run its draw closure every frame — dozens of glyph
/// draws at 120 Hz behind a scrolling list — whereas offsetting a rendered layer
/// is composited on the GPU and costs essentially nothing.
///
/// Travel is exactly one tile, so when the animation loops the pattern lands
/// back on itself and the seam is invisible.
private struct DriftingTiles<Content: View>: View {
    let tile: CGFloat
    let period: Double
    @ViewBuilder var content: (CGSize) -> Content

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shift: CGFloat = 0

    var body: some View {
        GeometryReader { geo in
            let over = CGSize(width: geo.size.width + tile * 2,
                              height: geo.size.height + tile * 2)
            content(over)
                .frame(width: over.width, height: over.height)
                .offset(x: -tile + shift, y: -tile + shift * 0.6)
                .onAppear {
                    guard !reduceMotion else { return }
                    withAnimation(.linear(duration: period).repeatForever(autoreverses: false)) {
                        shift = tile
                    }
                }
        }
        .clipped()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

struct EmojiPatternView: View {
    let emojis: [String]
    let backgroundColor: Color

    private let spacing: CGFloat = 60

    var body: some View {
        ZStack {
            backgroundColor
            DriftingTiles(tile: spacing, period: 46) { size in
                Canvas { context, canvasSize in
                    let rows = Int(canvasSize.height / spacing) + 2
                    let cols = Int(canvasSize.width / spacing) + 2

                    for row in 0..<rows {
                        for col in 0..<cols {
                            // Brick offset so it doesn't read as a grid.
                            let offset: CGFloat = row % 2 == 0 ? 0 : spacing / 2
                            let x = CGFloat(col) * spacing + offset
                            let y = CGFloat(row) * spacing

                            let emoji = emojis[(row + col) % emojis.count]
                            context.draw(
                                Text(emoji).font(.scaled(20)),
                                at: CGPoint(x: x, y: y)
                            )
                        }
                    }
                }
                .frame(width: size.width, height: size.height)
            }
            .opacity(0.28)
        }
        .ignoresSafeArea()
    }
}

// MARK: - OSHI Pattern View (Binary/Hex/Matrix style)

/// A tiled OSHI motif, drifting.
///
/// 🔧 FIX (2026-08-05): this used `CGFloat.random` for jitter, opacity and glyph
/// size INSIDE the Canvas draw closure. Canvas re-runs that closure on every
/// layout pass, so every redraw produced a different pattern — the backdrop
/// shimmered continuously behind the messages, which is the opposite of what a
/// wallpaper is for. Variation now comes from a hash of the cell position:
/// still irregular, but identical on every frame and on every device.
///
/// The MOTION is separate from that, and deliberately so: the pattern is drawn
/// once and the rendered layer is offset, so the drift costs a GPU transform
/// rather than a full redraw per frame.
struct OSHIPatternView: View {
    let symbols: [String]
    let backgroundColor: Color
    let foregroundColor: Color

    private let spacing: CGFloat = 46

    /// Deterministic 0..<1 from a pair of integers. Cheap, and stable across
    /// launches and platforms — unlike `Hasher`, which is seeded per process.
    private func noise(_ a: Int, _ b: Int, _ salt: Int) -> Double {
        var h = UInt64(bitPattern: Int64(a &* 73856093 ^ b &* 19349663 ^ salt &* 83492791))
        h ^= h >> 33; h = h &* 0xFF51AFD7ED558CCD
        h ^= h >> 33; h = h &* 0xC4CEB9FE1A85EC53
        h ^= h >> 33
        return Double(h % 10_000) / 10_000
    }

    var body: some View {
        ZStack {
            backgroundColor

            DriftingTiles(tile: spacing, period: 54) { size in
                Canvas { context, canvasSize in
                    let rows = Int(canvasSize.height / spacing) + 2
                    let cols = Int(canvasSize.width / spacing) + 2

                    for row in 0..<rows {
                        for col in 0..<cols {
                            // Brick offset — a square grid reads as a spreadsheet.
                            let stagger: CGFloat = row % 2 == 0 ? 0 : spacing / 2
                            let jx = CGFloat(noise(row, col, 1) * 8 - 4)
                            let jy = CGFloat(noise(row, col, 2) * 8 - 4)
                            let x = CGFloat(col) * spacing + stagger + jx
                            let y = CGFloat(row) * spacing + jy

                            let symbol = symbols[abs((row * 7 + col * 13) % symbols.count)]
                            // Narrow bands: wide opacity variance made some
                            // glyphs pop out as if they meant something.
                            let opacity = 0.10 + noise(row, col, 3) * 0.14
                            let glyph = 13 + CGFloat(noise(row, col, 4) * 5)

                            context.opacity = opacity
                            context.draw(
                                Text(symbol)
                                    .font(.system(size: glyph, weight: .medium, design: .monospaced))
                                    .foregroundColor(foregroundColor),
                                at: CGPoint(x: x, y: y)
                            )
                        }
                    }
                }
                .frame(width: size.width, height: size.height)
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}

// MARK: - Wallpaper Picker View

struct WallpaperPickerView: View {
    let conversationId: String
    @ObservedObject var wallpaperManager = ChatWallpaperManager.shared
    @ObservedObject private var bubbleStyle = BubbleStyleManager.shared
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) var dismiss
    @State private var selectedWallpaper: ChatWallpaper
    @State private var showImagePicker = false
    @State private var selectedImage: UIImage?
    /// Opens on the OSHI tab, not Colors.
    ///
    /// The tags are historical — OSHI has always been `2` — but the segments
    /// were reordered so OSHI reads first. The default stayed at `0`, so the
    /// picker opened on Colors while highlighting the third segment: the app's
    /// own backdrops were the one thing you had to go looking for.
    @State private var selectedTab = 2
    /// __WALLPAPER_TONE_2026_09_24__ Light / dark / automatic for this chat.
    @State private var selectedTone: ChatWallpaperTone
    
    init(conversationId: String) {
        self.conversationId = conversationId
        _selectedWallpaper = State(initialValue: ChatWallpaperManager.shared.getWallpaper(for: conversationId))
        _selectedTone = State(initialValue: ChatWallpaperManager.shared.tone(for: conversationId))
    }

    /// The scheme the preview (and, once saved, the chat) renders in.
    private var previewScheme: ColorScheme {
        ChatWallpaperAppearance.resolve(wallpaper: selectedWallpaper,
                                        tone: selectedTone,
                                        system: colorScheme).contentScheme
    }
    
    var body: some View {
        // 🔧 FIX: Removed NavigationView - this view is already embedded in a NavigationLink
        // from ChatWallpaperPickerView, so having another NavigationView caused instant dismissal
        VStack(spacing: 0) {
            // Preview
            previewSection
                .padding()
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)

            // Tone: keep the white (or black) backdrop whatever the system
            // appearance. Only for the backdrops that have a light and a dark
            // version; the others carry their own colours.
            if selectedWallpaper.followsTone {
                WallpaperToneRow(wallpaper: selectedWallpaper, tone: $selectedTone, cardHeight: 76)
                    .padding(.horizontal)
                    .padding(.bottom, 12)
                    .frame(maxWidth: 640)
                    .frame(maxWidth: .infinity)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }

            // Tab selector
            Picker("Category", selection: $selectedTab) {
                Text("wallpaper.oshi".localized).tag(2)
                Text("bubble.section".localized).tag(4)
                Text("wallpaper.colors".localized).tag(0)
                Text("wallpaper.gradients".localized).tag(3)
                Text("wallpaper.emoji".localized).tag(1)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)

            // Content based on selected tab
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    switch selectedTab {
                    case 0:
                        colorSection
                        customImageSection
                    case 1:
                        emojiSection
                    case 2:
                        oshiSection
                    case 3:
                        gradientSection
                    case 4:
                        bubbleSection
                    default:
                        EmptyView()
                    }

                    actionSection
                }
                .padding()
                // Keep the picker grid a readable width on iPad / landscape
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
        }
        .navigationTitle("wallpaper.title".localized)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("button_save".localized) {
                    wallpaperManager.setWallpaper(selectedWallpaper, for: conversationId)
                    wallpaperManager.setTone(selectedTone, for: conversationId)
                    // 🎨 Broadcast wallpaper change to conversation participants
                    let senderName = UserDefaults.standard.string(forKey: "userName") ?? UserDefaults.standard.string(forKey: "displayName")
                    wallpaperManager.broadcastWallpaperUpdate(
                        wallpaper: selectedWallpaper,
                        conversationId: conversationId,
                        senderName: senderName
                    )
                    dismiss()
                }
                .fontWeight(.semibold)
            }
        }
        .sheet(isPresented: $showImagePicker) {
            WallpaperImagePicker(image: $selectedImage)
        }
        .onChange(of: selectedImage) { _, newImage in
            OshiLog.messaging.info("📷 Wallpaper: onChange triggered, image: \(newImage != nil ? "yes" : "nil")")
            if let image = newImage {
                OshiLog.messaging.info("📷 Wallpaper: Image size: \(image.size)")
                if let filename = wallpaperManager.saveCustomImage(image, for: conversationId) {
                    OshiLog.messaging.info("📷 Wallpaper: Saved as \(filename)")
                    selectedWallpaper = .customImage(filename)
                } else {
                    OshiLog.messaging.info("❌ Wallpaper: Failed to save image")
                }
            }
        }
    }
    
    // MARK: - Bubble Section

    /// Bubble accent, shape and text weight. Lives beside the wallpaper because
    /// the two are chosen together — a backdrop and a bubble colour that clash
    /// are only visible side by side, and the live preview above shows both.
    private var bubbleSection: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 10) {
                Text("bubble.colour".localized).font(.headline)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3),
                          spacing: 10) {
                    ForEach(BubbleAccent.allCases) { accent in
                        Button {
                            bubbleStyle.accent = accent
                        } label: {
                            VStack(spacing: 6) {
                                ZStack {
                                    // The real outgoing silhouette, not a plain
                                    // rounded rect: a swatch that is not the
                                    // shape you get is a swatch that lies.
                                    bubbleStyle.outline(outgoing: true, tail: true)
                                        .fill(LinearGradient(colors: accent.stops,
                                                             startPoint: .topLeading,
                                                             endPoint: .bottomTrailing))
                                        .frame(height: 46)
                                    if bubbleStyle.accent == accent {
                                        Image(systemName: "checkmark")
                                            .font(.scaled(15, weight: .bold))
                                            .foregroundColor(.white)
                                    }
                                }
                                Text(accent.titleKey.localized)
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                                    .lineLimit(1)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(accent.titleKey.localized)
                    }
                }
            }

            VStack(alignment: .leading, spacing: 10) {
                Text("bubble.shape".localized).font(.headline)
                HStack(spacing: 10) {
                    ForEach(BubbleShape.allCases) { shape in
                        Button {
                            bubbleStyle.shape = shape
                        } label: {
                            VStack(spacing: 6) {
                                // Each swatch previews ITS OWN shape (including
                                // the tail corner), which is the only thing that
                                // distinguishes the three options.
                                let swatch = BubbleOutline(radius: shape.radius,
                                                           isOutgoing: true, isTail: true)
                                swatch
                                    .fill(LinearGradient(colors: bubbleStyle.accent.stops,
                                                         startPoint: .topLeading,
                                                         endPoint: .bottomTrailing))
                                    .frame(height: 44)
                                    .overlay(
                                        swatch.stroke(Color.primary.opacity(
                                            bubbleStyle.shape == shape ? 0.9 : 0), lineWidth: 2)
                                    )
                                Text(shape.titleKey.localized)
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(shape.titleKey.localized)
                    }
                }
            }

            Toggle(isOn: $bubbleStyle.boldText) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("bubble.bold".localized)
                    Text("bubble.bold.detail".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(bubbleStyle.accent.representative)
        }
    }

    // MARK: - Preview Section
    
    private var previewSection: some View {
        ZStack {
            ChatWallpaperView(wallpaper: selectedWallpaper)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .id("\(selectedTone.rawValue)-\(previewScheme == .dark)")
                .transition(.opacity)
            
            VStack(spacing: 12) {
                // Sample received message. Drawn with the SAME surface modifier
                // as the chat — rim included — because the rim is precisely what
                // this preview exists to show: whether a pale bubble still has
                // an edge over the wallpaper being chosen.
                HStack {
                    Text("wallpaper.sample_received".localized)
                        .padding(12)
                        .bubbleSurface(fill: bubbleStyle.incomingFill(previewScheme),
                                       outline: bubbleStyle.outline(outgoing: false, tail: true),
                                       rim: bubbleStyle.rim(outgoing: false, previewScheme))
                    Spacer()
                }

                // Sample sent message
                HStack {
                    Spacer()
                    Text("wallpaper.sample_sent".localized)
                        .foregroundColor(bubbleStyle.accent.onAccent)
                        .padding(12)
                        .bubbleSurface(fill: bubbleStyle.outgoingFill,
                                       outline: bubbleStyle.outline(outgoing: true, tail: true),
                                       rim: bubbleStyle.rim(outgoing: true, previewScheme))
                }
            }
            .padding()
        }
        .environment(\.colorScheme, previewScheme)
        .animation(Theme.Motion.smooth, value: previewScheme)
        .frame(height: 180)
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color(.systemGray4), lineWidth: 1)
        )
    }
    
    // MARK: - Color Section
    
    private var colorSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("wallpaper.solid_colors".localized)
                .font(.headline)
            
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 5), spacing: 12) {
                ForEach(Array(ChatWallpaperManager.presetColors.enumerated()), id: \.offset) { index, color in
                    colorButton(color: color, index: index)
                }
            }
        }
    }
    
    private func colorButton(color: Color, index: Int) -> some View {
        Button {
            if index == 0 {
                selectedWallpaper = .none
            } else {
                selectedWallpaper = .solidColor(CodableColor(color))
            }
        } label: {
            ZStack {
                if color == .clear {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(Color(.systemBackground))
                        .overlay(
                            Image(systemName: "slash.circle")
                                .foregroundColor(.gray)
                        )
                } else {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(color)
                }
                
                if isColorSelected(index: index) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.white)
                        .shadow(radius: 2)
                }
            }
            .frame(height: 50)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color(.systemGray4), lineWidth: 1)
            )
        }
        .accessibilityLabel(index == 0 ? "wallpaper.no_background".localized : "wallpaper.color".localized)
        .accessibilityAddTraits(isColorSelected(index: index) ? .isSelected : [])
    }

    private func isColorSelected(index: Int) -> Bool {
        if index == 0 {
            return selectedWallpaper.isDefault
        }
        if case .solidColor(let selected) = selectedWallpaper {
            let colorCode = CodableColor(ChatWallpaperManager.presetColors[index])
            return selected.red == colorCode.red && selected.green == colorCode.green && selected.blue == colorCode.blue
        }
        return false
    }
    
    // MARK: - Emoji Section
    
    private var emojiSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("wallpaper.emoji_patterns".localized)
                .font(.headline)
            
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 2), spacing: 12) {
                ForEach(Array(ChatWallpaperManager.emojiPatterns.enumerated()), id: \.offset) { index, pattern in
                    emojiPatternButton(index: index, pattern: pattern)
                }
            }
        }
    }
    
    private func emojiPatternButton(index: Int, pattern: (name: String, emojis: [String], bgColor: Color)) -> some View {
        Button {
            selectedWallpaper = .emojiPattern(index)
        } label: {
            VStack(spacing: 4) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(pattern.bgColor)
                    
                    HStack(spacing: 8) {
                        ForEach(pattern.emojis.prefix(4), id: \.self) { emoji in
                            Text(emoji)
                                .font(.scaled(20))
                        }
                    }
                    .opacity(0.7)
                    .accessibilityHidden(true)

                    if case .emojiPattern(let selected) = selectedWallpaper, selected == index {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(.white)
                            .shadow(radius: 2)
                            .accessibilityHidden(true)
                    }
                }
                .frame(height: 60)

                Text(pattern.name)
                    .font(.caption)
                    .foregroundColor(.primary)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(pattern.name)
        .accessibilityAddTraits({ if case .emojiPattern(let selected) = selectedWallpaper, selected == index { return .isSelected } else { return [] } }())
    }

    // MARK: - OSHI Section
    
    private var oshiSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            // The default goes first and is labelled as the default, so someone
            // who changed it can find their way back without guessing which of
            // the patterns was the original.
            Button {
                selectedWallpaper = .oshiSignature
            } label: {
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 8)
                            .fill(Color(.systemBackground))
                        OSHISignatureBackground()
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color.primary.opacity(0.12), lineWidth: 1)
                        if selectedWallpaper == .oshiSignature {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(appBrandColor)
                                .accessibilityHidden(true)
                        }
                    }
                    .frame(width: 72, height: 52)

                    VStack(alignment: .leading, spacing: 2) {
                        Text("wallpaper.oshi_default".localized)
                            .font(.subheadline).bold()
                        Text("wallpaper.oshi_default.detail".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                }
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)

            Divider()

            Text("wallpaper.dyn_section".localized)
                .font(.headline)

            // Taller tiles in a 3-up grid: these are drawn artworks, and a 60pt
            // strip showed none of what distinguishes one from another.
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3),
                      spacing: 12) {
                ForEach(OSHIWallpaper.allCases) { style in
                    Button {
                        selectedWallpaper = .oshiDynamic(style.rawValue)
                    } label: {
                        VStack(spacing: 6) {
                            ZStack {
                                // Thumbnails render STILL: a grid of moving
                                // previews is noise, and the large preview above
                                // already shows the motion.
                                OSHIWallpaperView(style: style, animated: false)
                                    .clipShape(RoundedRectangle(cornerRadius: 10))
                                RoundedRectangle(cornerRadius: 10)
                                    .stroke(selectedWallpaper == .oshiDynamic(style.rawValue)
                                            ? appBrandColor : Color.primary.opacity(0.12),
                                            lineWidth: selectedWallpaper == .oshiDynamic(style.rawValue) ? 2.5 : 1)
                                if selectedWallpaper == .oshiDynamic(style.rawValue) {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundColor(appBrandColor)
                                        .background(Circle().fill(Color(.systemBackground)))
                                        .accessibilityHidden(true)
                                }
                            }
                            .frame(height: 108)
                            Text(style.titleKey.localized)
                                .font(.caption2)
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(style.titleKey.localized)
                }
            }

            Divider()

            Text("wallpaper.oshi_patterns".localized)
                .font(.headline)

            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 2), spacing: 12) {
                ForEach(Array(ChatWallpaperManager.oshiPatterns.enumerated()), id: \.offset) { index, pattern in
                    oshiPatternButton(index: index, pattern: pattern)
                }
            }
        }
    }
    
    private func oshiPatternButton(index: Int, pattern: (name: String, symbols: [String], bgColor: Color, fgColor: Color)) -> some View {
        Button {
            selectedWallpaper = .oshiPattern(index)
        } label: {
            VStack(spacing: 4) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(pattern.bgColor)
                    
                    HStack(spacing: 4) {
                        ForEach(pattern.symbols.prefix(5), id: \.self) { symbol in
                            Text(symbol)
                                .font(.scaled(14, weight: .medium, design: .monospaced))
                                .foregroundColor(pattern.fgColor)
                        }
                    }
                    .opacity(0.7)
                    .accessibilityHidden(true)

                    if case .oshiPattern(let selected) = selectedWallpaper, selected == index {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(.white)
                            .shadow(radius: 2)
                            .accessibilityHidden(true)
                    }
                }
                .frame(height: 60)

                Text(pattern.name)
                    .font(.caption)
                    .foregroundColor(.primary)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(pattern.name)
        .accessibilityAddTraits({ if case .oshiPattern(let selected) = selectedWallpaper, selected == index { return .isSelected } else { return [] } }())
    }

    // MARK: - Gradient Section
    
    private var gradientSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("wallpaper.gradient_presets".localized)
                .font(.headline)
            
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 2), spacing: 12) {
                ForEach(Array(ChatWallpaperManager.gradientPresets.enumerated()), id: \.offset) { index, preset in
                    gradientButton(index: index, preset: preset)
                }
            }
        }
    }
    
    private func gradientButton(index: Int, preset: (name: String, colors: [Color])) -> some View {
        Button {
            selectedWallpaper = .gradientPreset(index)
        } label: {
            VStack(spacing: 4) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(
                            LinearGradient(
                                colors: preset.colors,
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                    
                    if case .gradientPreset(let selected) = selectedWallpaper, selected == index {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(.white)
                            .shadow(radius: 2)
                            .accessibilityHidden(true)
                    }
                }
                .frame(height: 60)

                Text(preset.name)
                    .font(.caption)
                    .foregroundColor(.primary)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(preset.name)
        .accessibilityAddTraits({ if case .gradientPreset(let selected) = selectedWallpaper, selected == index { return .isSelected } else { return [] } }())
    }

    // MARK: - Custom Image Section
    
    private var customImageSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("wallpaper.custom_image".localized)
                .font(.headline)
            
            Button {
                showImagePicker = true
            } label: {
                HStack {
                    Image(systemName: "photo.on.rectangle")
                    Text("wallpaper.choose_photo".localized)
                }
                .frame(maxWidth: .infinity)
                .padding()
                .background(Color(.systemGray6))
                .cornerRadius(12)
            }
        }
    }
    
    // MARK: - Action Section

    private var actionSection: some View {
        VStack(spacing: 12) {
            // 🔧 FIX: Save for this conversation only (primary action)
            // 🎨 Now broadcasts wallpaper to all participants
            Button {
                wallpaperManager.setWallpaper(selectedWallpaper, for: conversationId)
                wallpaperManager.setTone(selectedTone, for: conversationId)
                // 🎨 Broadcast wallpaper change to conversation participants
                let senderName = UserDefaults.standard.string(forKey: "userName") ?? UserDefaults.standard.string(forKey: "displayName")
                wallpaperManager.broadcastWallpaperUpdate(
                    wallpaper: selectedWallpaper,
                    conversationId: conversationId,
                    senderName: senderName
                )
                dismiss()
            } label: {
                HStack {
                    Image(systemName: "checkmark.circle.fill")
                    Text("wallpaper.save_this_chat".localized)
                }
                .frame(maxWidth: .infinity)
                .padding()
                .background(appBrandColor)
                .foregroundColor(.white)
                .cornerRadius(12)
            }

            // Set as default for all chats (does NOT broadcast - only affects local default)
            Button {
                wallpaperManager.setDefaultWallpaper(selectedWallpaper)
                wallpaperManager.setDefaultTone(selectedTone)
                // Also apply to current conversation
                wallpaperManager.setWallpaper(selectedWallpaper, for: conversationId)
                wallpaperManager.setTone(selectedTone, for: conversationId)
                dismiss()
            } label: {
                HStack {
                    Image(systemName: "arrow.down.app")
                    Text("wallpaper.set_default".localized)
                }
                .frame(maxWidth: .infinity)
                .padding()
                .background(appBrandColor.opacity(0.1))
                .foregroundColor(appBrandColor)
                .cornerRadius(12)
            }

            // Reset to default (broadcasts reset to participants)
            Button(role: .destructive) {
                selectedWallpaper = wallpaperManager.defaultWallpaper
                wallpaperManager.removeWallpaper(for: conversationId)
                selectedTone = wallpaperManager.defaultTone
                // 🎨 Broadcast wallpaper reset to participants
                let senderName = UserDefaults.standard.string(forKey: "userName") ?? UserDefaults.standard.string(forKey: "displayName")
                wallpaperManager.broadcastWallpaperUpdate(
                    wallpaper: wallpaperManager.defaultWallpaper,
                    conversationId: conversationId,
                    senderName: senderName
                )
            } label: {
                HStack {
                    Image(systemName: "arrow.uturn.backward")
                    Text("wallpaper.reset".localized)
                }
                .frame(maxWidth: .infinity)
                .padding()
                .background(Color.red.opacity(0.1))
                .cornerRadius(12)
            }
        }
    }
}

// MARK: - Wallpaper Image Picker (renamed to avoid conflict)

struct WallpaperImagePicker: UIViewControllerRepresentable {
    @Binding var image: UIImage?
    @Environment(\.dismiss) var dismiss
    
    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration(photoLibrary: .shared())
        config.filter = .images
        config.selectionLimit = 1
        config.preferredAssetRepresentationMode = .current
        
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }
    
    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let parent: WallpaperImagePicker
        
        init(_ parent: WallpaperImagePicker) {
            self.parent = parent
        }
        
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let provider = results.first?.itemProvider else {
                OshiLog.messaging.info("❌ Wallpaper: No item provider found")
                picker.dismiss(animated: true)
                return
            }
            
            OshiLog.messaging.info("📷 Wallpaper: Processing image from provider...")
            
            // Try loading as UIImage first
            if provider.canLoadObject(ofClass: UIImage.self) {
                OshiLog.messaging.info("📷 Wallpaper: Can load as UIImage")
                provider.loadObject(ofClass: UIImage.self) { [weak self] object, error in
                    DispatchQueue.main.async {
                        if let error = error {
                            OshiLog.messaging.info("❌ Wallpaper: Failed to load image: \(error.localizedDescription)")
                            picker.dismiss(animated: true)
                            return
                        }
                        
                        guard let loadedImage = object as? UIImage else {
                            OshiLog.messaging.info("❌ Wallpaper: Object is not UIImage")
                            picker.dismiss(animated: true)
                            return
                        }
                        
                        OshiLog.messaging.info("✅ Wallpaper: Image loaded successfully (\(loadedImage.size))")
                        self?.parent.image = loadedImage
                        OshiLog.messaging.info("✅ Wallpaper: Image binding updated")
                        picker.dismiss(animated: true)
                    }
                }
            } else {
                // Fallback: try loading as Data
                OshiLog.messaging.info("📷 Wallpaper: Trying to load as data...")
                provider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { [weak self] data, error in
                    DispatchQueue.main.async {
                        if let error = error {
                            OshiLog.messaging.info("❌ Wallpaper: Failed to load data: \(error.localizedDescription)")
                            picker.dismiss(animated: true)
                            return
                        }
                        
                        guard let data = data, let loadedImage = UIImage(data: data) else {
                            OshiLog.messaging.info("❌ Wallpaper: Failed to create image from data")
                            picker.dismiss(animated: true)
                            return
                        }
                        
                        OshiLog.messaging.info("✅ Wallpaper: Image loaded from data (\(loadedImage.size))")
                        self?.parent.image = loadedImage
                        OshiLog.messaging.info("✅ Wallpaper: Image binding updated (from data)")
                        picker.dismiss(animated: true)
                    }
                }
            }
        }
    }
}

// MARK: - Contact Photo Manager

class ContactPhotoManager: ObservableObject {
    static let shared = ContactPhotoManager()
    
    // Use a non-published cache for synchronous reads to avoid "Publishing changes from within view updates"
    private var internalCache: [String: UIImage] = [:]
    
    // Published property only for triggering view updates after explicit changes
    @Published private var updateTrigger: Int = 0
    
    private init() {
        // 🔧 LAUNCH-FREEZE FIX: `loadAllPhotos()` enumerates the photo directory and
        // decodes EVERY contact JPEG. Doing that in `init()` put it on the main
        // thread during app construction. Nothing needs the full cache to draw the
        // first frame — `getPhoto` already falls back to a per-key disk load on a
        // miss, so a photo requested before the sweep finishes still resolves.
        DispatchQueue.global(qos: .utility).async { [weak self] in
            self?.loadAllPhotos()
        }
    }

    // MARK: - Get Photo for Contact (Safe for view body)
    
    func getPhoto(for publicKey: String) -> UIImage? {
        // Return from internal cache - no publishing during view body
        if let img = internalCache[publicKey] { return img }
        // Fallback: exact-filename disk load. loadAllPhotos() reconstructs keys from
        // filenames lossily (every "_" becomes "/"), so keys that legitimately contain
        // "_" never hit the cache. loadPhoto uses the same sanitisation as savePhoto,
        // so it always finds the file; the cache is non-published so this is safe to
        // touch from a view body.
        if let img = loadPhoto(for: publicKey) {
            internalCache[publicKey] = img
            return img
        }
        return nil
    }
    
    // MARK: - Preload photo if not in cache (call from onAppear, not body)
    
    func preloadPhoto(for publicKey: String) {
        guard internalCache[publicKey] == nil else { return }
        
        // Load on background thread
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            if let photo = self?.loadPhoto(for: publicKey) {
                DispatchQueue.main.async {
                    self?.internalCache[publicKey] = photo
                    self?.updateTrigger += 1  // Trigger view update
                    NotificationCenter.default.post(name: NSNotification.Name("ContactPhotoUpdated"), object: nil)
                }
            }
        }
    }
    
    // MARK: - Save Photo
    
    func savePhoto(_ image: UIImage, for publicKey: String) -> Bool {
        // Resize and compress
        let resizedImage = resizeImage(image, targetSize: CGSize(width: 200, height: 200))
        guard let data = resizedImage.jpegData(compressionQuality: 0.7) else { return false }
        
        let filename = "contact_\(publicKey.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "+", with: "_")).jpg"
        let url = getPhotosDirectory().appendingPathComponent(filename)
        
        do {
            try MediaCipher.write(data, to: url)  // __MEDIA_AT_REST_2026_09_22__ chiffré au repos
            DispatchQueue.main.async {
                self.internalCache[publicKey] = resizedImage
                self.updateTrigger += 1
                NotificationCenter.default.post(name: NSNotification.Name("ContactPhotoUpdated"), object: nil)
            }
            OshiLog.messaging.info("✅ Contact photo saved for \(publicKey.prefix(8))...")
            return true
        } catch {
            OshiLog.messaging.info("❌ Failed to save contact photo: \(error)")
            return false
        }
    }
    
    // MARK: - Delete Photo
    
    func deletePhoto(for publicKey: String) {
        let filename = "contact_\(publicKey.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "+", with: "_")).jpg"
        let url = getPhotosDirectory().appendingPathComponent(filename)
        
        try? FileManager.default.removeItem(at: url)
        internalCache.removeValue(forKey: publicKey)
        updateTrigger += 1
        NotificationCenter.default.post(name: NSNotification.Name("ContactPhotoUpdated"), object: nil)
    }
    
    // MARK: - Check if photo exists
    
    func hasPhoto(for publicKey: String) -> Bool {
        return internalCache[publicKey] != nil
    }
    
    // MARK: - Private
    
    private func loadPhoto(for publicKey: String) -> UIImage? {
        let filename = "contact_\(publicKey.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "+", with: "_")).jpg"
        let url = getPhotosDirectory().appendingPathComponent(filename)

        guard let data = try? MediaCipher.read(contentsOf: url) else { return nil }  // __MEDIA_AT_REST_2026_09_22__
        return UIImage(data: data)
    }
    
    private func loadAllPhotos() {
        let directory = getPhotosDirectory()
        guard let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) else { return }

        // Decode into a LOCAL dict; `internalCache` is only ever touched on the main
        // thread (this now runs off-main — see init) so the two can't race.
        var decoded: [String: UIImage] = [:]
        for file in files where file.pathExtension == "jpg" && file.lastPathComponent.hasPrefix("contact_") {
            if let data = try? MediaCipher.read(contentsOf: file),  // __MEDIA_AT_REST_2026_09_22__
               let image = UIImage(data: data) {
                // Extract public key from filename
                var publicKey = file.lastPathComponent
                    .replacingOccurrences(of: "contact_", with: "")
                    .replacingOccurrences(of: ".jpg", with: "")
                    .replacingOccurrences(of: "_", with: "/")
                // Fix base64 padding
                publicKey = publicKey.replacingOccurrences(of: "/", with: "+", options: [], range: publicKey.range(of: "/", options: .backwards))
                decoded[publicKey] = image
            }
        }
        guard !decoded.isEmpty else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            // Anything already cached came from a live `getPhoto` miss — keep it.
            self.internalCache.merge(decoded) { current, _ in current }
        }
    }
    
    private func getPhotosDirectory() -> URL {
        let documentsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let photosDir = documentsDir.appendingPathComponent("ContactPhotos")
        
        if !FileManager.default.fileExists(atPath: photosDir.path) {
            try? FileManager.default.createDirectory(at: photosDir, withIntermediateDirectories: true)
        }
        
        return photosDir
    }
    
    private func resizeImage(_ image: UIImage, targetSize: CGSize) -> UIImage {
        let size = image.size
        let widthRatio = targetSize.width / size.width
        let heightRatio = targetSize.height / size.height
        let ratio = min(widthRatio, heightRatio)
        
        let newSize = CGSize(width: size.width * ratio, height: size.height * ratio)
        let rect = CGRect(origin: .zero, size: newSize)
        
        UIGraphicsBeginImageContextWithOptions(newSize, false, 1.0)
        image.draw(in: rect)
        let newImage = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        
        return newImage ?? image
    }
}

// MARK: - Contact Photo Picker

struct ContactPhotoPicker: UIViewControllerRepresentable {
    @Binding var image: UIImage?
    @Environment(\.dismiss) var dismiss
    
    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration(photoLibrary: .shared())
        config.filter = .images
        config.selectionLimit = 1
        config.preferredAssetRepresentationMode = .current
        
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }
    
    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let parent: ContactPhotoPicker
        
        init(_ parent: ContactPhotoPicker) {
            self.parent = parent
        }
        
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let provider = results.first?.itemProvider else {
                OshiLog.messaging.info("❌ Contact photo: No item provider")
                picker.dismiss(animated: true)
                return
            }
            
            OshiLog.messaging.info("📷 Contact photo: Processing image from provider...")
            
            if provider.canLoadObject(ofClass: UIImage.self) {
                provider.loadObject(ofClass: UIImage.self) { [weak self] object, error in
                    DispatchQueue.main.async {
                        if let error = error {
                            OshiLog.messaging.info("❌ Contact photo: Failed to load: \(error.localizedDescription)")
                            picker.dismiss(animated: true)
                            return
                        }
                        
                        guard let loadedImage = object as? UIImage else {
                            OshiLog.messaging.info("❌ Contact photo: Object is not UIImage")
                            picker.dismiss(animated: true)
                            return
                        }
                        
                        OshiLog.messaging.info("✅ Contact photo: Image loaded (\(loadedImage.size))")
                        self?.parent.image = loadedImage
                        OshiLog.messaging.info("✅ Contact photo: Image binding updated")
                        picker.dismiss(animated: true)
                    }
                }
            } else {
                // Fallback: try loading as Data
                provider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { [weak self] data, error in
                    DispatchQueue.main.async {
                        if let data = data, let loadedImage = UIImage(data: data) {
                            OshiLog.messaging.info("✅ Contact photo: Image loaded from data (\(loadedImage.size))")
                            self?.parent.image = loadedImage
                            picker.dismiss(animated: true)
                        } else {
                            OshiLog.messaging.info("❌ Contact photo: Failed to load data: \(error?.localizedDescription ?? "unknown")")
                            picker.dismiss(animated: true)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Contact Photo Picker View

struct ContactPhotoPickerView: View {
    let publicKey: String
    @Environment(\.dismiss) var dismiss
    @ObservedObject var photoManager = ContactPhotoManager.shared
    @State private var selectedImage: UIImage?
    @State private var showImagePicker = false
    
    private var currentPhoto: UIImage? {
        photoManager.getPhoto(for: publicKey)
    }
    
    var body: some View {
        NavigationView {
            VStack(spacing: 24) {
                // Current photo preview
                ZStack {
                    if let photo = selectedImage ?? currentPhoto {
                        Image(uiImage: photo)
                            .resizable()
                            .scaledToFill()
                            .frame(width: 150, height: 150)
                            .clipShape(Circle())
                    } else {
                        Circle()
                            .fill(Color(.systemGray5))
                            .frame(width: 150, height: 150)
                            .overlay(
                                Image(systemName: "person.fill")
                                    .font(.scaled(60))
                                    .foregroundColor(.gray)
                                    .accessibilityHidden(true)
                            )
                    }

                    // Camera badge
                    Circle()
                        .fill(Color.blue)
                        .frame(width: 40, height: 40)
                        .overlay(
                            Image(systemName: "camera.fill")
                                .font(.scaled(18))
                                .foregroundColor(.white)
                        )
                        .offset(x: 50, y: 50)
                        .accessibilityHidden(true)
                }
                .contentShape(Circle())
                .onTapGesture {
                    showImagePicker = true
                }
                .accessibilityElement(children: .ignore)
                .accessibilityAddTraits(.isButton)
                .accessibilityLabel("chat.contact_photo".localized)
                .accessibilityHint("chat.tap_to_change_photo".localized)
                
                Text("chat.tap_to_change_photo".localized)
                    .font(.caption)
                    .foregroundColor(.secondary)
                
                Spacer()
                
                // Action buttons
                VStack(spacing: 12) {
                    Button {
                        showImagePicker = true
                    } label: {
                        HStack {
                            Image(systemName: "photo.on.rectangle")
                            Text("chat.choose_from_gallery".localized)
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color(.systemGray6))
                        .cornerRadius(12)
                    }
                    
                    if currentPhoto != nil || selectedImage != nil {
                        Button(role: .destructive) {
                            photoManager.deletePhoto(for: publicKey)
                            selectedImage = nil
                        } label: {
                            HStack {
                                Image(systemName: "trash")
                                Text("chat.remove_photo".localized)
                            }
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color.red.opacity(0.1))
                            .cornerRadius(12)
                        }
                    }
                }
                .padding(.horizontal)
            }
            .padding()
            // Keep controls a readable width on iPad / landscape
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
            .navigationTitle("chat.contact_photo".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("button_cancel".localized) {
                        dismiss()
                    }
                }
                
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("button_save".localized) {
                        if let image = selectedImage {
                            _ = photoManager.savePhoto(image, for: publicKey)
                        }
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .disabled(selectedImage == nil && currentPhoto == nil)
                }
            }
            .sheet(isPresented: $showImagePicker) {
                ContactPhotoPicker(image: $selectedImage)
            }
        }
    }
}
