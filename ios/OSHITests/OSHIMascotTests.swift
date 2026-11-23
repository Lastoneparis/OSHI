import XCTest
import SwiftUI
@testable import OSHI

/// __O_BUBBLE_MASCOT_2026_11_16__ The O-bubble mascot: emoji → animation
/// mapping, aliases, the wire staying the plain emoji, the setting's
/// fallback, bundled assets for all twenty, and the launch timing budget.
final class OSHIMascotTests: XCTestCase {

    private struct Manifest: Decodable {
        struct Anim: Decodable {
            let name: String
            let emoji: String
            let aliases: [String]
            let fps: Int
            let frames: Int
            let still_frame: Int
        }
        let animations: [Anim]
    }

    private func bundledManifest() throws -> Manifest {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "manifest", withExtension: "json",
                                                subdirectory: OSHIMascotCatalog.folder))
        return try JSONDecoder().decode(Manifest.self, from: Data(contentsOf: url))
    }

    // MARK: Catalog vs manifest vs bundle

    func testCatalogMatchesBundledManifest() throws {
        let m = try bundledManifest()
        XCTAssertEqual(m.animations.count, 20)
        XCTAssertEqual(OSHIMascotCatalog.all.map(\.name), m.animations.map(\.name))
        for (c, a) in zip(OSHIMascotCatalog.all, m.animations) {
            XCTAssertEqual(c.emoji, a.emoji, a.name)
            XCTAssertEqual(c.aliases, a.aliases, a.name)
            XCTAssertEqual(c.frameCount, a.frames, a.name)
            XCTAssertEqual(c.stillFrame, a.still_frame, a.name)
            XCTAssertEqual(Double(a.fps), OSHIMascot.fps, a.name)
        }
    }

    func testEveryAnimationIsBundled() {
        for m in OSHIMascotCatalog.all {
            XCTAssertTrue(OSHIMascotCatalog.isBundled(m), m.name)
            for i in 0..<m.frameCount {
                XCTAssertNotNil(OSHIMascotCatalog.frameURL(m, index: i), "\(m.name) frame \(i)")
            }
            XCTAssertNil(OSHIMascotCatalog.frameURL(m, index: m.frameCount), "\(m.name) has extra frames")
            let gif = OSHIMascotCatalog.gifURL(m)
            XCTAssertNotNil(gif, m.name)
            if let gif, let data = try? Data(contentsOf: gif) {
                XCTAssertTrue(MediaManager.isGIFData(data), m.name)
                XCTAssertLessThanOrEqual(data.count, MediaManager.gifPreferredMaxBytes, m.name)
            }
        }
        for i in MascotLaunchTiming.waveFrames {
            XCTAssertNotNil(OSHIMascotCatalog.launchWaveURL(index: i), "wave \(i)")
        }
        XCTAssertNotNil(UIImage(named: "LaunchMark"))
    }

    func testFramesDecodeAtTheRequestedSize() {
        let love = OSHIMascotCatalog.named("love")!
        let still = MascotFrameStore.shared.still(love, px: 64, scale: 2)
        XCTAssertEqual(still?.size, CGSize(width: 32, height: 32))
        let done = expectation(description: "frames")
        MascotFrameStore.shared.frames(love, px: 64, scale: 2) { frames in
            XCTAssertEqual(frames?.images.count, 32)
            XCTAssertEqual(frames?.duration ?? 0, 1.6, accuracy: 0.001)
            // Shared: a second request returns the same decoded frames.
            MascotFrameStore.shared.frames(love, px: 64, scale: 2) { again in
                XCTAssertTrue(again === frames)
                done.fulfill()
            }
        }
        wait(for: [done], timeout: 10)
        XCTAssertEqual(MascotFrameStore.pixelBucket(points: 18, scale: 3), 64)
        XCTAssertEqual(MascotFrameStore.pixelBucket(points: 38, scale: 3), 128)
        XCTAssertEqual(MascotFrameStore.pixelBucket(points: 4, scale: 1), 32)
    }

    // MARK: Mapping

    func testEachCanonicalEmojiMapsToItsAnimation() {
        for m in OSHIMascotCatalog.all {
            XCTAssertEqual(OSHIMascotCatalog.mascot(forEmoji: m.emoji)?.name, m.name, m.emoji)
        }
    }

    func testAliasesMapPerManifest() {
        let expected: [String: String] = [
            "🤣": "laugh", "🥳": "party", "🎊": "party", "😢": "cry", "❤": "love", "💜": "love",
            "🩷": "love", "♥️": "love", "😱": "wow", "🤬": "angry", "🥰": "hearteyes", "😚": "kiss",
            "🙌": "clap", "🧐": "thinking", "💤": "sleep", "✔️": "ok", "☑️": "ok", "👌": "ok",
            "🙋": "wave", "🙈": "peek",
        ]
        for (e, name) in expected {
            XCTAssertEqual(OSHIMascotCatalog.mascot(forEmoji: e)?.name, name, e)
        }
        for m in OSHIMascotCatalog.all {
            for a in m.aliases { XCTAssertEqual(OSHIMascotCatalog.mascot(forEmoji: a)?.name, m.name, a) }
        }
    }

    func testVariationSelectorAndSkinToneAreIgnored() {
        XCTAssertEqual(OSHIMascotCatalog.mascot(forEmoji: "\u{2764}")?.name, "love")
        XCTAssertEqual(OSHIMascotCatalog.mascot(forEmoji: "\u{2764}\u{FE0F}")?.name, "love")
        XCTAssertEqual(OSHIMascotCatalog.mascot(forEmoji: "👍🏽")?.name, "thumbs")
        XCTAssertEqual(OSHIMascotCatalog.mascot(forEmoji: "🙏🏿")?.name, "pray")
        XCTAssertEqual(OSHIMascotCatalog.mascot(forEmoji: "👋🏻")?.name, "wave")
    }

    func testEmojiWithoutAMascotStayPlain() {
        for e in ["🦄", "👎", "❌", "👀", "🖕", "\u{2B55}", "\u{2B55}\u{1F60A}", "✝️", "😊", "a"] {
            XCTAssertNil(OSHIMascotCatalog.mascot(forEmoji: e), e)
        }
    }

    // MARK: Wire

    /// The reaction that travels is the plain emoji string, in both payload
    /// formats, for every mascot — nothing mascot-specific on the wire.
    func testWireIsThePlainEmojiForAllTwenty() throws {
        for m in OSHIMascotCatalog.all {
            let r = try XCTUnwrap(ReactionEmoji(rawValue: m.emoji), "\(m.emoji) not a known reaction")
            XCTAssertEqual(r.rawValue, m.emoji)
            XCTAssertEqual(try JSONEncoder().encode(r), Data("\"\(m.emoji)\"".utf8), m.name)

            let p = ReactionPayload(messageId: "m", emoji: r, senderPublicKey: "K", senderName: "",
                                    timestamp: Date(), action: .add)
            let wire = p.toMessageString()
            let json = try XCTUnwrap(try JSONSerialization.jsonObject(
                with: Data(wire.dropFirst(ReactionPayload.messagePrefix.count).utf8)) as? [String: Any])
            XCTAssertEqual(json["emoji"] as? String, m.emoji, m.name)
            XCTAssertFalse(wire.contains(m.name), "mascot name leaked onto the wire")
            XCTAssertEqual(ReactionPayload.fromMessageString(wire)?.emoji, r, m.name)

            let android = ReactionPayload.androidPrefix
                + "{\"messageId\":\"m\",\"emoji\":\"\(m.emoji)\",\"isAdding\":true,\"senderPublicKey\":\"K\"}"
            XCTAssertEqual(ReactionPayload.fromMessageString(android)?.emoji, r, m.name)
            XCTAssertNotNil(r.accessibilityKey, m.name)
        }
    }

    /// Received aliases and unknown emoji are kept verbatim — never turned
    /// into 👍 or into the canonical mascot emoji.
    func testAliasesAndUnknownEmojiAreKeptVerbatim() throws {
        for e in ["🤣", "🥳", "😢", "💜", "👍🏽", "🦄"] {
            let android = ReactionPayload.androidPrefix
                + "{\"messageId\":\"m\",\"emoji\":\"\(e)\",\"isAdding\":true,\"senderPublicKey\":\"K\"}"
            XCTAssertEqual(ReactionPayload.fromMessageString(android)?.emoji.rawValue, e)
            let ios = try JSONDecoder().decode(ReactionEmoji.self, from: Data("\"\(e)\"".utf8))
            XCTAssertEqual(ios.rawValue, e)
            XCTAssertNotEqual(ios, .thumbsUp)
        }
        XCTAssertNil(MascotReactionPreference.mascot(for: ReactionEmoji(wire: "🦄")!, enabled: true))
    }

    /// Reactions stored by older builds (bare-string JSON) still decode.
    func testLegacyStoredReactionsDecode() throws {
        let json = #"[{"id":"1","messageId":"m","emoji":"👍","senderPublicKey":"K","senderName":"A","timestamp":0},{"id":"2","messageId":"m","emoji":"😢","senderPublicKey":"L","senderName":"B","timestamp":0}]"#
        let back = try JSONDecoder().decode([MessageReaction].self, from: Data(json.utf8))
        XCTAssertEqual(back.map(\.emoji), [.thumbsUp, .sad])
    }

    // MARK: Safe mapping (__MASCOT_SAFE_WIRE_2026_11_16__)

    /// What each mascot SENDS: its manifest emoji, except the crying one,
    /// which sends 😢 (known to iOS 1.0.43). Received 😭 still draws it.
    func testWireValuePerMascot() {
        let expected: [String: String] = [
            "love": "❤️", "laugh": "😂", "thumbs": "👍", "cry": "😢", "wow": "😮", "angry": "😡",
            "pray": "🙏", "fire": "🔥", "party": "🎉", "hearteyes": "😍", "kiss": "😘", "clap": "👏",
            "thinking": "🤔", "cool": "😎", "sleep": "😴", "ok": "✅", "hundred": "💯", "wave": "👋",
            "peek": "🫣", "tearsmile": "🥲",
        ]
        XCTAssertEqual(expected.count, 20)
        for m in OSHIMascotCatalog.all {
            XCTAssertEqual(MascotWirePolicy.wireEmoji(m), expected[m.name], m.name)
            XCTAssertEqual(ReactionEmoji.mascotReaction(m.name)?.rawValue, expected[m.name], m.name)
            // Whatever is sent draws the same mascot on a new client.
            XCTAssertEqual(OSHIMascotCatalog.mascot(forEmoji: MascotWirePolicy.wireEmoji(m))?.name, m.name)
        }
        XCTAssertEqual(OSHIMascotCatalog.mascot(forEmoji: "😭")?.name, "cry")
    }

    /// Bar = ⭕ + six emoji older builds know; the eight new ones only in the
    /// "+" picker under the hint.
    func testQuickBarOnlySendsEmojiOldBuildsKnow() {
        // What iOS ≤ 1.0.46 knows (its closed enum; anything else shows 👍 there).
        let old: Set<String> = ["✅", "❌", "😢", "🖕", "😘", "👍", "👎", "💯", "😂", "❤️", "🔥", "😮",
                                "👏", "🎉", "🤔", "👀", "✝️", "☪️", "✡️", "☸️", "\u{2B55}\u{1F60A}"]
        let bar = ReactionEmoji.quickReactions.dropFirst()
        XCTAssertEqual(bar.count, 6)
        for r in bar { XCTAssertTrue(old.contains(r.rawValue), r.rawValue) }
        for r in ReactionEmoji.pickerReactions where r != .oshiO {
            XCTAssertTrue(old.contains(r.rawValue), "main picker sends \(r.rawValue)")
        }
        XCTAssertEqual(Set(ReactionEmoji.pickerNewReactions.map(\.rawValue)),
                       ["😡", "🙏", "😍", "😎", "😴", "👋", "🫣", "🥲"])
        for r in ReactionEmoji.pickerNewReactions { XCTAssertFalse(old.contains(r.rawValue)) }
        XCTAssertNotEqual("reaction.new_hint".localized, "reaction.new_hint")
    }

    // MARK: Setting

    func testSettingDefaultsOnAndFallsBackToTheEmoji() {
        let d = UserDefaults.standard
        let saved = d.object(forKey: MascotReactionPreference.key)
        defer { d.set(saved, forKey: MascotReactionPreference.key) }

        d.removeObject(forKey: MascotReactionPreference.key)
        XCTAssertTrue(MascotReactionPreference.isEnabled)
        XCTAssertEqual(MascotReactionPreference.mascot(for: .heart, enabled: true)?.name, "love")
        XCTAssertNil(MascotReactionPreference.mascot(for: .heart, enabled: false))
        XCTAssertNil(MascotReactionPreference.mascot(for: .oshiO, enabled: true))

        d.set(false, forKey: MascotReactionPreference.key)
        XCTAssertFalse(MascotReactionPreference.isEnabled)
    }

    func testNewReactionNamesAreLocalizedEverywhere() throws {
        let keys = ["reaction.sob", "reaction.peek", "reaction.tear_smile", "reaction.pray", "reaction.angry",
                    "reaction.wave", "reaction.heart_eyes", "reaction.cool", "reaction.sleepy",
                    "settings.appearance.animatedReactions", "settings.appearance.animatedReactions.footer",
                    "reaction.new_hint"]
        let lprojs = Bundle.main.paths(forResourcesOfType: "lproj", inDirectory: nil)
            .filter { !$0.hasSuffix("Base.lproj") }
        XCTAssertGreaterThanOrEqual(lprojs.count, 34)
        for path in lprojs {
            let b = try XCTUnwrap(Bundle(path: path))
            for k in keys {
                let v = b.localizedString(forKey: k, value: "§missing§", table: nil)
                XCTAssertNotEqual(v, "§missing§", "\(k) in \((path as NSString).lastPathComponent)")
            }
        }
    }

    // MARK: Launch

    func testLaunchFitsTheBudget() {
        XCTAssertLessThanOrEqual(MascotLaunchTiming.total, 1.25)
        XCTAssertLessThanOrEqual(MascotLaunchTiming.totalReduceMotion, 1.0)
        XCTAssertEqual(MascotLaunchTiming.waveFrames.first, 0)
        XCTAssertEqual(MascotLaunchTiming.waveFrames.last, OSHIMascotCatalog.launchWaveFrameCount - 1,
                       "the wave must end on the neutral pose")
        // The in-app mark is drawn at the system launch image's own size.
        XCTAssertEqual(UIImage(named: "LaunchMark")?.size, CGSize(width: MascotLaunchTiming.side,
                                                                  height: MascotLaunchTiming.side))
    }
}
