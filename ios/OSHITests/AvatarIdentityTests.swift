import XCTest
import SwiftUI
@testable import OSHI

/// Guards `AvatarIdentity` — the default-avatar palette (v2, 2026-11-17).
///
/// The source of truth is `docs/avatar-palette.json`, shared with Android
/// (`AvatarIdentityTest.kt`) and desktop (`AvatarParityTest.kt`). These tests
/// read it back and check, from the FILE rather than from the Swift literals:
///  * every literal here equals the spec (so iOS cannot drift alone);
///  * every identifier→slot vector (so the hash cannot drift alone);
///  * every ink clears 3:1 on start, end and highlighted centre (re-measured,
///    so a "prettier" lighter entry cannot silently make initials unreadable);
///  * no two entries are perceptually close (OKLab), the reported bug class.
final class AvatarIdentityTests: XCTestCase {

    // MARK: - Spec

    private struct Spec: Decodable {
        struct Entry: Decodable { let index: Int; let name: String; let start: String; let end: String; let ink: String; let nameLight: String; let nameDark: String }
        struct Vector: Decodable { let id: String; let slot: Int }
        struct Highlight: Decodable { let centerX: Double; let centerY: Double; let radius: Double; let alphaAtCenter: Double }
        struct Contrast: Decodable { let minimum: Double }
        let palette: [Entry]
        let vectors: [Vector]
        let highlight: Highlight
        let contrast: Contrast
    }

    private static func loadSpec() throws -> Spec {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("docs/avatar-palette.json"))
        return try JSONDecoder().decode(Spec.self, from: data)
    }

    private static func hex(_ s: String) -> UInt32 { UInt32(s.dropFirst(), radix: 16)! }

    private typealias RGB = (Double, Double, Double)
    private static func rgb(_ h: UInt32) -> RGB {
        (Double((h >> 16) & 0xFF) / 255, Double((h >> 8) & 0xFF) / 255, Double(h & 0xFF) / 255)
    }
    private static func lin(_ v: Double) -> Double { v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
    private static func luminance(_ c: RGB) -> Double { 0.2126 * lin(c.0) + 0.7152 * lin(c.1) + 0.0722 * lin(c.2) }
    private static func contrast(_ a: RGB, _ b: RGB) -> Double {
        let la = luminance(a), lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
    private static func mix(_ a: RGB, _ b: RGB, _ t: Double) -> RGB {
        (a.0 + (b.0 - a.0) * t, a.1 + (b.1 - a.1) * t, a.2 + (b.2 - a.2) * t)
    }
    private static func oklab(_ c: RGB) -> RGB {
        let r = lin(c.0), g = lin(c.1), b = lin(c.2)
        let l = cbrt(0.4122214708 * r + 0.5363031944 * g + 0.0514459929 * b)
        let m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        let s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
    }

    // MARK: - The literals are the spec

    func testPaletteLiteralsMatchTheSharedSpec() throws {
        let spec = try Self.loadSpec()
        XCTAssertEqual(AvatarIdentity.entries.count, 16)
        XCTAssertEqual(spec.palette.count, AvatarIdentity.entries.count)
        for e in spec.palette {
            let mine = AvatarIdentity.entries[e.index]
            XCTAssertEqual(mine, AvatarIdentity.Entry(start: Self.hex(e.start), end: Self.hex(e.end), ink: Self.hex(e.ink)),
                           "entry \(e.index) (\(e.name)) drifted from docs/avatar-palette.json")
        }
        XCTAssertEqual(AvatarIdentity.highlightCenter.x, spec.highlight.centerX, accuracy: 1e-9)
        XCTAssertEqual(AvatarIdentity.highlightCenter.y, spec.highlight.centerY, accuracy: 1e-9)
        XCTAssertEqual(Double(AvatarIdentity.highlightRadius), spec.highlight.radius, accuracy: 1e-9)
        XCTAssertEqual(AvatarIdentity.highlightAlpha, spec.highlight.alphaAtCenter, accuracy: 1e-9)
    }

    func testNameColourLiteralsMatchTheSharedSpec() throws {
        let spec = try Self.loadSpec()
        XCTAssertEqual(AvatarIdentity.nameColorsLight.count, spec.palette.count)
        XCTAssertEqual(AvatarIdentity.nameColorsDark.count, spec.palette.count)
        for e in spec.palette {
            XCTAssertEqual(UIColor(AvatarIdentity.nameColorsLight[e.index]), UIColor(AvatarIdentity.color(Self.hex(e.nameLight))), "nameLight \(e.index)")
            XCTAssertEqual(UIColor(AvatarIdentity.nameColorsDark[e.index]), UIColor(AvatarIdentity.color(Self.hex(e.nameDark))), "nameDark \(e.index)")
        }
    }

    // MARK: - The cross-platform contract

    /// THE test: the same vectors run on Android and desktop. If a platform's
    /// hash or normalisation drifts, its suite names the identifier.
    func testSharedVectorsGiveTheSpecSlots() throws {
        let spec = try Self.loadSpec()
        XCTAssertGreaterThanOrEqual(spec.vectors.count, 10)
        for v in spec.vectors {
            XCTAssertEqual(AvatarIdentity.slot(for: v.id), v.slot, "slot for '\(v.id)' diverged from the shared spec")
        }
    }

    /// iOS spells `uuidString` in UPPERCASE; Android and desktop lowercase.
    func testAGroupIdIsTheSameSlotInEitherCase() {
        let upper = UUID().uuidString
        XCTAssertEqual(AvatarIdentity.slot(for: upper), AvatarIdentity.slot(for: upper.lowercased()))
        XCTAssertEqual(AvatarIdentity.normalizedKey(upper), upper.lowercased())
    }

    /// Base64 is case-SENSITIVE: a public key must never be case-folded.
    func testAPublicKeyIsNotCaseFolded() {
        let key = "BHq2Y7qkzL0m1yQe0kq9XW3sV1d6c7m3pPq9yZ4b8tE="
        XCTAssertEqual(AvatarIdentity.normalizedKey(key), key)
    }

    // MARK: - Legibility

    func testEveryInkClearsLargeTextContrastOnItsGradient() throws {
        let spec = try Self.loadSpec()
        let h = spec.highlight
        let centreAlpha = h.alphaAtCenter * max(0, 1 - hypot(0.5 - h.centerX, 0.5 - h.centerY) / h.radius)
        for e in spec.palette {
            let s = Self.rgb(Self.hex(e.start)), t = Self.rgb(Self.hex(e.end)), ink = Self.rgb(Self.hex(e.ink))
            let centre = Self.mix(Self.mix(s, t, 0.5), (1, 1, 1), centreAlpha)
            for (label, bg) in [("start", s), ("end", t), ("centre", centre)] {
                let ratio = Self.contrast(ink, bg)
                XCTAssertGreaterThanOrEqual(ratio, spec.contrast.minimum,
                    "entry \(e.index) (\(e.name)) ink is \(String(format: "%.2f", ratio)):1 on its \(label)")
            }
        }
    }

    /// Re-measured, not trusted: name colours are TEXT on a bubble.
    func testEveryNameColourIsLegibleOnItsBubble() throws {
        let spec = try Self.loadSpec()
        let lightBubble: RGB = (0xF2 / 255.0, 0xF2 / 255.0, 0xF7 / 255.0)
        let darkBubble: RGB = (0x2C / 255.0, 0x2C / 255.0, 0x2E / 255.0)
        for e in spec.palette {
            XCTAssertGreaterThanOrEqual(Self.contrast(Self.rgb(Self.hex(e.nameLight)), lightBubble), 4.5, "nameLight \(e.index)")
            XCTAssertGreaterThanOrEqual(Self.contrast(Self.rgb(Self.hex(e.nameDark)), darkBubble), 4.5, "nameDark \(e.index)")
        }
    }

    // MARK: - Variety, the reported bug

    /// "Two main colours, green and purple" was hue families collapsing. Hue
    /// alone is the wrong ruler for a set with pastels and a neutral (slate and
    /// ocean share a hue, yet nobody confuses them), so this measures OKLab
    /// distance between gradient midpoints.
    func testNoTwoEntriesArePerceptuallyClose() throws {
        let spec = try Self.loadSpec()
        let mids = spec.palette.map { e in
            Self.oklab(Self.mix(Self.rgb(Self.hex(e.start)), Self.rgb(Self.hex(e.end)), 0.5))
        }
        for i in mids.indices {
            for j in mids.indices where j > i {
                let d = sqrt(pow(mids[i].0 - mids[j].0, 2) + pow(mids[i].1 - mids[j].1, 2) + pow(mids[i].2 - mids[j].2, 2))
                XCTAssertGreaterThan(d, 0.05, "entries \(i) and \(j) are only ΔE_ok \(String(format: "%.3f", d)) apart")
            }
        }
    }

    // MARK: - Stability

    func testPathologicalKeysDoNotTrap() {
        for key in ["", String(repeating: "x", count: 100_000), "🙂🙂🙂", "\u{0}"] {
            XCTAssertTrue((0..<16).contains(AvatarIdentity.slot(for: key)))
        }
        XCTAssertEqual(AvatarIdentity.stableIndex(for: "anything", modulo: 0), 0,
                       "a zero-size palette must not divide by zero")
    }

    func testKeysSpreadAcrossThePalette() {
        var used = Set<Int>()
        for i in 0..<500 { used.insert(AvatarIdentity.slot(for: "contact-\(i)-key")) }
        XCTAssertEqual(used.count, AvatarIdentity.entries.count,
                       "500 keys reached only \(used.count) of \(AvatarIdentity.entries.count) colours")
    }

    func testTheNameColourUsesTheSameSlotAsTheAvatar() {
        for key in ["alice", "bob", "a-very-long-base64url-looking-key_-123", ""] {
            let slot = AvatarIdentity.slot(for: key)
            XCTAssertEqual(AvatarIdentity.gradient(for: key), AvatarIdentity.gradients[slot])
            XCTAssertEqual(AvatarIdentity.ink(for: key), AvatarIdentity.inks[slot])
            XCTAssertLessThan(slot, AvatarIdentity.nameColorsLight.count)
        }
    }

    // MARK: - __ONE_AVATAR_NOT_THREE_2026_11_07__

    /// The initials rule, lifted out of `ContactAvatarWithBadge` so it is one
    /// rule rather than per-screen guesswork.
    func testInitialsFollowTheName() {
        XCTAssertEqual(AvatarIdentity.initials(name: "Anatole Dupont", key: "zzz"), "AD")
        XCTAssertEqual(AvatarIdentity.initials(name: "raph", key: "zzz"), "RA")
        XCTAssertEqual(AvatarIdentity.initials(name: "raph", key: "zzz", count: 1), "R")
        XCTAssertEqual(AvatarIdentity.initials(name: "  Sarah   Connor ", key: "zzz"), "SC")
    }

    /// A key is never empty, so this always answers — an avatar with no letters
    /// is worse than one with two meaningless ones.
    func testInitialsFallBackToTheKey() {
        XCTAssertEqual(AvatarIdentity.initials(name: nil, key: "abcdef"), "AB")
        XCTAssertEqual(AvatarIdentity.initials(name: "", key: "abcdef"), "AB")
        XCTAssertEqual(AvatarIdentity.initials(name: "   ", key: "abcdef"), "AB")
    }

    /// The point of the whole fix: the COLOUR follows the key, so renaming
    /// someone does not recolour them, and two screens showing the same person
    /// cannot disagree.
    func testTheColourFollowsTheKeyNotTheName() {
        let a = AvatarIdentity.gradient(for: "KEY-1")
        let b = AvatarIdentity.gradient(for: "KEY-1")
        XCTAssertEqual(a, b)
        // A different identity should, for these two, land elsewhere.
        XCTAssertNotEqual(AvatarIdentity.slot(for: "KEY-1"), AvatarIdentity.slot(for: "KEY-3"))
    }

    /// Source guard: the screens that drew their own colour must not grow one
    /// back. Comments are stripped first — the explanations above each call
    /// site name the very constants being banned.
    func testNoScreenDrawsItsOwnDefaultAvatarColour() throws {
        let offenders: [(String, String)] = [
            ("OSHI/ChatView.swift", "appBrandColor.opacity(0.18)"),
            ("OSHI/ContactProfileView.swift", "brandColor.opacity(0.18)"),
            ("OSHI/MessageActionsManager.swift", "appBrandColor.opacity(0.2)"),
            ("OSHI/ContactPresenceManager.swift", "[Color.purple, Color.blue]"),
        ]
        for (file, banned) in offenders {
            let text = try Self.strippedSource(file)
            XCTAssertFalse(text.contains(banned),
                           "\(file) is drawing its own default-avatar colour again (\(banned)) — "
                         + "that is how the same person ends up three colours in one app")
        }
    }

    func testTheConversationHeaderUsesTheSharedAvatar() throws {
        XCTAssertTrue(try Self.strippedSource("OSHI/ChatView.swift").contains("DefaultAvatar("),
                      "the 1:1 header no longer uses DefaultAvatar")
        XCTAssertTrue(try Self.strippedSource("OSHI/GroupViews.swift").contains("DefaultAvatar("),
                      "the group header no longer uses DefaultAvatar")
    }

    private static func strippedSource(_ relative: String) throws -> String {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        dir = dir.deletingLastPathComponent()
        let url = dir.appendingPathComponent(relative)
        guard let raw = try? String(contentsOf: url, encoding: .utf8) else {
            XCTFail("\(relative) not readable at \(url.path)")
            throw NSError(domain: "AvatarIdentityTests", code: 1)
        }
        // __A_STRING_IS_NOT_A_COMMENT_2026_11_07__ see MeshReconnectTests for
        // the full story: a comment marker inside a string literal can blank
        // thousands of lines, and an "is this pattern absent?" guard then passes
        // vacuously over the blanked region. This test is exactly that shape.
        return SourceScanning.strippingComments(raw)
    }
}

/// __A_STRING_IS_NOT_A_COMMENT_2026_11_07__ Comment-stripping that knows what a
/// string literal is. See `SourceText.kt` on the Android side for the incident.
enum SourceScanning {
    static func strippingComments(_ source: String) -> String {
        var out = ""
        var i = source.startIndex
        while i < source.endIndex {
            let c = source[i]
            let next = source.index(after: i)
            let n: Character = next < source.endIndex ? source[next] : " "

            if c == "\"" {
                out.append(c)
                i = next
                while i < source.endIndex {
                    let d = source[i]
                    out.append(d)
                    i = source.index(after: i)
                    if d == "\\", i < source.endIndex {
                        out.append(source[i]); i = source.index(after: i); continue
                    }
                    if d == "\"" || d == "\n" { break }
                }
                continue
            }
            if c == "/" && n == "*" {
                i = source.index(after: next)
                while i < source.endIndex {
                    if source[i] == "*" {
                        let after = source.index(after: i)
                        if after < source.endIndex && source[after] == "/" {
                            i = source.index(after: after); break
                        }
                    }
                    if source[i] == "\n" { out.append("\n") }
                    i = source.index(after: i)
                }
                continue
            }
            if c == "/" && n == "/" {
                while i < source.endIndex && source[i] != "\n" { i = source.index(after: i) }
                continue
            }
            out.append(c)
            i = source.index(after: i)
        }
        return out
    }
}
