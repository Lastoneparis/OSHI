import SwiftUI
import CryptoKit

/// AvatarIdentity — the colour a contact or group gets when it has no picture.
///
/// __AVATAR_PALETTE_V2_2026_11_17__ (owner: "more agreeable and diversified
/// colours or texture"). Version 1 was fourteen flat, fairly saturated hues,
/// each darkened until WHITE initials cleared 5.2:1 — legible, but the price
/// of "white everywhere" was that the yellow-green band could only exist as
/// olive and ochre, and a long list read as a row of heavy, similar discs.
///
/// Version 2 is sixteen two-tone soft gradients: nine mid tones that carry
/// WHITE initials and seven pastels that carry a deep ink of their own hue
/// (honey, mint, sky… are only possible as pastels). Each disc also gets a
/// baked-in radial light blob (top-leading), which reads as a soft "glass"
/// texture without an image asset and without per-frame work.
///
/// ONE SPEC, THREE PLATFORMS
/// -------------------------
/// The source of truth is `docs/avatar-palette.json`: hex values, gradient
/// direction, highlight geometry, the selection rule and the identifier→slot
/// test vectors. Android (`AvatarIdentity.kt`) and desktop (`OshiTheme.kt`)
/// carry the same literals, and all three test suites re-read that file, so a
/// contact cannot drift to a different colour on another device.
///
/// SELECTION: trim; a UUID (group id) is lowercased — iOS spells
/// `uuidString` in UPPERCASE, Android and desktop lowercase — anything else
/// (a contact's canonical base64 public key) is used verbatim; SHA-256 over
/// the UTF-8 bytes; slot = first digest byte mod 16. The slot is cached, so a
/// list row pays for the digest once per identity, not once per body pass.
///
/// CONTRAST: every entry's ink clears 3:1 (initials are large text) against
/// the gradient's start, end and highlighted centre — `AvatarIdentityTests`
/// re-measures it from the JSON rather than trusting these literals.
enum AvatarIdentity {

    struct Entry: Equatable {
        let start: UInt32
        let end: UInt32
        let ink: UInt32
    }

    /// The palette, in spec order. Do not reorder: the index IS the identity.
    static let entries: [Entry] = [
        Entry(start: 0xCB6C30, end: 0xB63924, ink: 0xFFFFFF),  //  0 coral
        Entry(start: 0xFDD7AC, end: 0xF6A97A, ink: 0x743B1F),  //  1 apricot
        Entry(start: 0xF8E9A3, end: 0xF1BF5B, ink: 0x6B4716),  //  2 honey
        Entry(start: 0xD8EFB4, end: 0x9AD387, ink: 0x31552A),  //  3 pistachio
        Entry(start: 0x43963C, end: 0x027048, ink: 0xFFFFFF),  //  4 fern
        Entry(start: 0xC2F5E0, end: 0x7ED8C6, ink: 0x07564F),  //  5 mint
        Entry(start: 0x179696, end: 0x066674, ink: 0xFFFFFF),  //  6 teal
        Entry(start: 0xC2ECFE, end: 0x87C6F2, ink: 0x264B7A),  //  7 sky
        Entry(start: 0x3E8CC9, end: 0x2953A5, ink: 0xFFFFFF),  //  8 ocean
        Entry(start: 0x7B7FC9, end: 0x5E45AE, ink: 0xFFFFFF),  //  9 periwinkle
        Entry(start: 0xE3DCFF, end: 0xC9AFF4, ink: 0x553781),  // 10 lavender
        Entry(start: 0xB76BC3, end: 0x7D32A3, ink: 0xFFFFFF),  // 11 grape
        Entry(start: 0xC96598, end: 0x9E3082, ink: 0xFFFFFF),  // 12 orchid
        Entry(start: 0xFEDAE1, end: 0xF0A9C4, ink: 0x7E314D),  // 13 blush
        Entry(start: 0xDB6371, end: 0xA92552, ink: 0xFFFFFF),  // 14 raspberry
        Entry(start: 0x768997, end: 0x47536A, ink: 0xFFFFFF),  // 15 slate
    ]

    /// Highlight geometry, fractions of the diameter (spec `highlight`).
    static let highlightCenter = UnitPoint(x: 0.30, y: 0.20)
    static let highlightRadius: CGFloat = 0.80
    static let highlightAlpha: Double = 0.24

    static func color(_ hex: UInt32) -> Color {
        Color(.sRGB,
              red: Double((hex >> 16) & 0xFF) / 255,
              green: Double((hex >> 8) & 0xFF) / 255,
              blue: Double(hex & 0xFF) / 255,
              opacity: 1)
    }

    /// `[start, end]` per entry — kept as the shape existing call sites use
    /// (`.map { $0.opacity(0.2) }` tints, `[0]` shadows).
    static let gradients: [[Color]] = entries.map { [color($0.start), color($0.end)] }
    static let inks: [Color] = entries.map { color($0.ink) }

    // MARK: - Selection

    /// Spec `selection.normalise`: a UUID is lowercased, anything else verbatim.
    static func normalizedKey(_ key: String) -> String {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        return isUUID(trimmed) ? trimmed.lowercased() : key
    }

    private static func isUUID(_ s: String) -> Bool {
        let u = Array(s.utf8)
        guard u.count == 36 else { return false }
        for (i, c) in u.enumerated() {
            if i == 8 || i == 13 || i == 18 || i == 23 {
                if c != UInt8(ascii: "-") { return false }
            } else {
                let hex = (c >= 48 && c <= 57) || (c >= 65 && c <= 70) || (c >= 97 && c <= 102)
                if !hex { return false }
            }
        }
        return true
    }

    /// Thread-safe; SwiftUI rows and background decoders both ask.
    private static let slotCache: NSCache<NSString, NSNumber> = {
        let c = NSCache<NSString, NSNumber>()
        c.countLimit = 4096
        return c
    }()

    /// The first byte of SHA-256(UTF-8(normalised key)) — spec `selection`.
    static func digestByte(for key: String) -> UInt8 {
        let normalized = normalizedKey(key)
        if let hit = slotCache.object(forKey: normalized as NSString) { return hit.uint8Value }
        let byte = Array(SHA256.hash(data: Data(normalized.utf8)))[0]
        slotCache.setObject(NSNumber(value: byte), forKey: normalized as NSString)
        return byte
    }

    /// A stable slot for `key`, identical across launches, devices and platforms.
    static func stableIndex(for key: String, modulo count: Int) -> Int {
        guard count > 0 else { return 0 }
        return Int(digestByte(for: key)) % count
    }

    static func slot(for key: String) -> Int { stableIndex(for: key, modulo: entries.count) }

    /// The gradient for `key` — a public key, a group id, whatever identifies
    /// the thing with no picture. Same key, same colour, always.
    static func gradient(for key: String) -> [Color] { gradients[slot(for: key)] }

    /// The colour of the initials drawn on `gradient(for: key)`.
    static func ink(for key: String) -> Color { inks[slot(for: key)] }

    /// __A_NAME_COLOUR_PER_PERSON_2026_11_07__ (Anatole N11) Sender names in a
    /// group, one colour family per person — the SAME slot and hue as their
    /// avatar, with lightness solved per appearance for 4.75:1 against the
    /// bubble it sits on (`systemGray6` light, `systemGray5` dark). Values from
    /// the spec's `nameLight` / `nameDark`.
    static let nameColorsLight: [Color] = [
        0xBE4106, 0xA15905, 0x846706, 0x4B7722, 0x007C37, 0x087965, 0x07777E, 0x05739F,
        0x156BC6, 0x6B5ACB, 0x7A5CAD, 0x984AB2, 0xB23E89, 0xA84D70, 0xBF395B, 0x576D88,
    ].map(color)

    static let nameColorsDark: [Color] = [
        0xE87750, 0xCB884F, 0xB5932E, 0x79A35B, 0x43AB60, 0x32A98F, 0x13A7B0, 0x45A0CF,
        0x5699EC, 0x948AF1, 0xA48AD4, 0xC27CD9, 0xDC74B3, 0xD27E9B, 0xE97188, 0x8499B1,
    ].map(color)

    #if !os(watchOS)
    /// The name colour for `key`, following the system appearance.
    /// (Not on watchOS: no dynamic UIColor there, and the watch is always
    /// dark — it reads `nameColorsDark` directly.)
    static func nameColor(for key: String) -> Color {
        let slot = slot(for: key)
        return Color(UIColor { traits in
            UIColor(traits.userInterfaceStyle == .dark
                    ? nameColorsDark[slot] : nameColorsLight[slot])
        })
    }
    #endif

    /// __ONE_AVATAR_NOT_THREE_2026_11_07__ The letters to draw on it.
    ///
    /// Pure, so the rule is testable without a view: two initials from a
    /// two-word name, else the first `count` characters of the name, else the
    /// key's first characters (a key is never empty, so this always answers).
    static func initials(name: String?, key: String, count: Int = 2) -> String {
        let trimmed = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            let parts = trimmed.split(separator: " ").filter { !$0.isEmpty }
            if count >= 2, parts.count >= 2 {
                return String(parts[0].prefix(1) + parts[1].prefix(1)).uppercased()
            }
            return String(trimmed.prefix(count)).uppercased()
        }
        return String(key.prefix(count)).uppercased()
    }
}

/// __AVATAR_PALETTE_V2_2026_11_17__ The picture-less disc: the slot's diagonal
/// gradient plus the soft top-leading light blob, both from the spec. Two
/// shape fills, no images, no animation — as cheap as the flat fill it
/// replaces. Every default avatar draws through this, so the texture cannot
/// exist on one screen and be missing on the next.
struct AvatarFill: View {
    let key: String
    var size: CGFloat = 44

    var body: some View {
        let slot = AvatarIdentity.slot(for: key)
        let colors = AvatarIdentity.gradients[slot]
        Circle()
            .fill(LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing))
            .overlay(
                Circle().fill(RadialGradient(
                    colors: [Color.white.opacity(AvatarIdentity.highlightAlpha), Color.white.opacity(0)],
                    center: AvatarIdentity.highlightCenter,
                    startRadius: 0,
                    endRadius: size * AvatarIdentity.highlightRadius))
            )
            .frame(width: size, height: size)
    }
}

/// __ONE_AVATAR_NOT_THREE_2026_11_07__ The default avatar, everywhere.
///
/// THE BUG THIS CLOSES. Reported twice on the same day: Anatole's N12 ("the
/// profile-photo colour works on the home page but not in conversations") and
/// the owner's ("someone with no picture shows up BLUE in the conversation").
/// Both are the same thing. `AvatarIdentity` — the contrast-solved palette
/// guarded by `AvatarIdentityTests` — had
/// exactly ONE caller for contacts, the conversation LIST row. Everywhere else
/// had grown its own:
///
///   * seven sites filled a flat `brandColor.opacity(0.15…0.2)`, so every
///     person without a picture was the same blue circle — the exact failure
///     the palette was written to fix, reintroduced one screen over;
///   * seven more hardcoded their own `LinearGradient`, mostly
///     `[.purple, .blue]`, which is the same colour for everyone AND a
///     different colour from the list row for the same person.
///
/// So a contact could be teal in the list, purple-blue on their profile and
/// flat blue in the conversation header. An avatar colour exists to be
/// recognised; three answers for one person is worse than one dull answer.
///
/// This view is the single answer. It takes the IDENTITY (`key`) separately
/// from the TEXT (`name`), because the colour must follow the key — a rename
/// must not recolour someone — while the letters follow the name.
struct DefaultAvatar: View {
    /// Public key, group id — whatever identifies the thing. Drives the colour.
    let key: String
    /// Alias or display name. Drives the letters only.
    var name: String? = nil
    var size: CGFloat = 44
    /// 1 for tight headers, 2 for list rows.
    var initialCount: Int = 2

    var body: some View {
        AvatarFill(key: key, size: size)
            .overlay(
                Text(AvatarIdentity.initials(name: name, key: key, count: initialCount))
                    // Scaled to the circle so one component serves a 24 pt
                    // header badge and a 96 pt profile hero without a second
                    // set of numbers to keep in step.
                    .font(.system(size: size * 0.38, weight: .semibold, design: .rounded))
                    // The slot's own ink: white on the mid tones, a deep
                    // tone of the same hue on the pastels (spec `ink`).
                    .foregroundColor(AvatarIdentity.ink(for: key))
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                    .padding(.horizontal, size * 0.1)
            )
    }
}
