import XCTest
import SwiftUI
@testable import OSHI

/// Guards the 2026-11-09 bubble/list pass: avatar colour identity, reply quote
/// legibility, the one-notice-in-foreground rule, the OSHI smile reaction's
/// wire value, and list timestamps that follow the clock.
final class BubbleUIPolishTests: XCTestCase {

    // MARK: - Helpers

    private func luminance(_ c: UIColor) -> CGFloat {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        c.getRed(&r, green: &g, blue: &b, alpha: &a)
        func ch(_ v: CGFloat) -> CGFloat { v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)
    }

    private func contrast(_ a: UIColor, _ b: UIColor) -> CGFloat {
        let la = luminance(a), lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private func resolved(_ c: UIColor, dark: Bool) -> UIColor {
        c.resolvedColor(with: UITraitCollection(userInterfaceStyle: dark ? .dark : .light))
    }

    /// `top` at `alpha` over opaque `bottom`.
    private func over(_ top: UIColor, alpha: CGFloat, _ bottom: UIColor) -> UIColor {
        var r1: CGFloat = 0, g1: CGFloat = 0, b1: CGFloat = 0, a1: CGFloat = 0
        var r2: CGFloat = 0, g2: CGFloat = 0, b2: CGFloat = 0, a2: CGFloat = 0
        top.getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        bottom.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        return UIColor(red: r1 * alpha + r2 * (1 - alpha),
                       green: g1 * alpha + g2 * (1 - alpha),
                       blue: b1 * alpha + b2 * (1 - alpha), alpha: 1)
    }

    private static func source(_ relative: String) throws -> String {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let raw = try String(contentsOf: root.appendingPathComponent(relative), encoding: .utf8)
        return SourceScanning.strippingComments(raw)
    }

    // MARK: - 1. Avatar colour: list == conversation

    func testEveryNoPhotoAvatarUsesTheListRowsFunction() throws {
        // The list row's fill.
        let list = try Self.source("OSHI/MessagesListView.swift")
        XCTAssertTrue(list.contains("AvatarFill(key: conversation.participantPublicKey"))
        // Header, profile and call screens go through the same function.
        XCTAssertTrue(try Self.source("OSHI/ChatView.swift").contains("DefaultAvatar(key: key"))
        XCTAssertTrue(try Self.source("OSHI/ContactProfileView.swift").contains("DefaultAvatar(key: publicKey"))
        XCTAssertTrue(try Self.source("OSHI/VoiceCallView.swift").contains("DefaultAvatar(key: photoKey"))
        XCTAssertTrue(try Self.source("OSHI/IncomingCallOverlay.swift").contains("DefaultAvatar("))
        let video = try Self.source("OSHI/VideoCallView.swift")
        XCTAssertFalse(video.contains("[.blue.opacity(0.6), .purple.opacity(0.6)]"),
                       "the video-call placeholder drew one colour for everyone again")
        XCTAssertTrue(video.contains("AvatarFill(key: contactPublicKey"))
        // Group: list row and info page on the group id.
        let groups = try Self.source("OSHI/GroupViews.swift")
        XCTAssertTrue(groups.contains("AvatarFill(key: group.id.uuidString"))
        XCTAssertTrue(groups.contains("DefaultAvatar(key: liveGroup.id.uuidString"))
    }

    func testTheSameKeyGivesTheSameGradientEverywhere() {
        let key = "MCowBQYDK2VwAyEAq1w8Zk3nU5yYd2l3m4n5o6p7q8r9s0t1u2v3w4x5y6z="
        let a = AvatarIdentity.gradient(for: key).map { UIColor($0) }
        let b = AvatarIdentity.gradient(for: String(key)).map { UIColor($0) }
        XCTAssertEqual(a, b)
    }

    // MARK: - 2. Reply quote legible on the wallpaper

    func testReplyQuoteInkClearsAAOnItsSurfaceInBothSchemes() {
        for dark in [false, true] {
            let ground = resolved(dark ? ChatPalette.groundDarkBottom : ChatPalette.groundLightBottom, dark: dark)
            let surface = over(resolved(ChatPalette.quoteSurfaceUI, dark: dark),
                               alpha: CGFloat(ChatPalette.chipSurfaceAlpha), ground)
            let accent = resolved(ChatPalette.quoteAccentUI, dark: dark)
            let body = resolved(ChatPalette.quoteBodyUI, dark: dark)
            XCTAssertGreaterThanOrEqual(contrast(accent, surface), 4.5, "accent, dark=\(dark)")
            XCTAssertGreaterThanOrEqual(contrast(body, surface), 4.5, "body, dark=\(dark)")
        }
    }

    func testQuotesNoLongerUseBubbleInkOnTheWallpaper() throws {
        let chat = try Self.source("OSHI/ChatView.swift")
        XCTAssertFalse(chat.contains("isFromCurrentUser ? .white : appBrandColor"),
                       "the 1:1 quote is drawn above the bubble — white ink there is white on the light wallpaper")
        // Scoped to the quote function: inside-the-bubble media rows rightly
        // keep white ink on the coloured bubble.
        let groups = try Self.source("OSHI/GroupViews.swift")
        let start = try XCTUnwrap(groups.range(of: "private func groupReplyQuote("))
        let quote = String(groups[start.lowerBound...].prefix(1500))
        XCTAssertFalse(quote.contains(".white"), "group quote uses bubble ink on the wallpaper again")
    }

    // MARK: - 3. One notice in the foreground

    func testForegroundMessagePushIsListOnly() {
        for type in ["message", "group_message", "group", "bot", "bot_message", ""] {
            XCTAssertEqual(ForegroundNoticePolicy.systemPresentation(type: type, looksLikeCall: false, appActive: true),
                           .listOnly, type)
        }
        XCTAssertEqual(ForegroundNoticePolicy.systemPresentation(type: nil, looksLikeCall: false, appActive: true),
                       .listOnly)
    }

    func testCallsOtherTypesAndInactiveAppKeepTheSystemBanner() {
        XCTAssertEqual(ForegroundNoticePolicy.systemPresentation(type: "message", looksLikeCall: true, appActive: true), .full)
        XCTAssertEqual(ForegroundNoticePolicy.systemPresentation(type: "call", looksLikeCall: false, appActive: true), .full)
        XCTAssertEqual(ForegroundNoticePolicy.systemPresentation(type: "mail", looksLikeCall: false, appActive: true), .full)
        XCTAssertEqual(ForegroundNoticePolicy.systemPresentation(type: "message", looksLikeCall: false, appActive: false), .full)
    }

    func testInAppBannerIsSilentForTheOpenConversation() {
        let k = "abc-_def=="
        XCTAssertFalse(ForegroundNoticePolicy.showsInAppBanner(conversationKey: k, openConversationKey: "abc+/def",
                                                               appActive: true),
                       "base64url and base64 of one key are one conversation")
        XCTAssertTrue(ForegroundNoticePolicy.showsInAppBanner(conversationKey: k, openConversationKey: "other",
                                                              appActive: true))
        XCTAssertTrue(ForegroundNoticePolicy.showsInAppBanner(conversationKey: k, openConversationKey: nil,
                                                              appActive: true))
        XCTAssertFalse(ForegroundNoticePolicy.showsInAppBanner(conversationKey: k, openConversationKey: nil,
                                                               appActive: false))
    }

    // MARK: - 4. OSHI smile reaction wire value

    func testOSHISmileWireBytesAreExact() {
        XCTAssertEqual(Array(ReactionEmoji.oshiSmile.rawValue.utf8),
                       [0xE2, 0xAD, 0x95, 0xF0, 0x9F, 0x98, 0x8A])
        XCTAssertEqual(ReactionEmoji.oshiSmile.rawValue.unicodeScalars.map(\.value), [0x2B55, 0x1F60A])
    }

    func testOnlyTheExactValueIsTheOSHIMark() {
        XCTAssertEqual(ReactionEmoji(rawValue: "\u{2B55}\u{1F60A}"), .oshiSmile)
        // A lone "⭕" is now `.oshiO` (its own reaction), never the smile.
        XCTAssertNotEqual(ReactionEmoji(rawValue: "\u{2B55}"), .oshiSmile)
        for other in ["\u{1F60A}", "\u{2B55}\u{FE0F}\u{1F60A}", "\u{1F60A}\u{2B55}", " \u{2B55}\u{1F60A}"] {
            XCTAssertNil(ReactionEmoji(rawValue: other), other)
            // The Android-format parse must not turn it into the mark either.
            let json = "{\"messageId\":\"m1\",\"emoji\":\"\(other)\",\"isAdding\":true}"
            let parsed = ReactionPayload.fromMessageString(ReactionPayload.androidPrefix + json)
            XCTAssertNotEqual(parsed?.emoji, .oshiSmile, other)
        }
        // A user-chosen standard emoji keeps being itself.
        XCTAssertFalse(ReactionEmoji.heart.isOSHIMark)
    }

    func testOSHISmileRoundTripsInBothFormats() throws {
        let p = ReactionPayload(messageId: "m1", emoji: .oshiSmile, senderPublicKey: "K",
                                senderName: "You", timestamp: Date(), action: .add)
        let wire = p.toMessageString()
        XCTAssertTrue(wire.hasPrefix(ReactionPayload.messagePrefix))
        XCTAssertTrue(wire.contains("\"emoji\":\"\u{2B55}\u{1F60A}\""), wire)
        XCTAssertEqual(ReactionPayload.fromMessageString(wire)?.emoji, .oshiSmile)

        let android = ReactionPayload.androidPrefix
            + "{\"messageId\":\"m1\",\"emoji\":\"\u{2B55}\u{1F60A}\",\"isAdding\":true,\"senderPublicKey\":\"K\"}"
        let parsed = ReactionPayload.fromMessageString(android)
        XCTAssertEqual(parsed?.emoji, .oshiSmile)
        XCTAssertEqual(parsed?.action, .add)
    }

    /// Renders the mark so it can be eyeballed (written to the temp dir).
    @MainActor
    func testTheMarkRenders() throws {
        let view = HStack(spacing: 12) {
            OSHISmileMark(size: 120)
            OSHISmileMark(size: 28)
            OSHISmileMark(size: 16)
        }
        .padding(8)
        .background(Color.white)
        let renderer = ImageRenderer(content: view)
        renderer.scale = 2
        let image = try XCTUnwrap(renderer.uiImage)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("oshi_smile_mark.png")
        try image.pngData()?.write(to: url)
        print("OSHI_SMILE_MARK_PNG \(url.path)")
        XCTAssertGreaterThan(image.size.width, 100)
    }

    // MARK: - 5. OSHI O reaction (wire "⭕") — __OSHI_O_REACTION_2026_11_15__
    /// __OSHI_O_REACTION_V2_2026_11_16__ Renders the "O" asset as it appears
    /// in a reaction chip, light and dark, at 20 pt and 60 pt, for eyeballing
    /// (PNG paths printed as OSHI_O_PNG).
    @MainActor
    func testTheOAssetRendersLightAndDark() throws {
        for scheme in [ColorScheme.light, .dark] {
            for size in [CGFloat(20), 60] {
                let view = HStack(spacing: size * 0.3) {
                    ReactionGlyph(emoji: .oshiO, size: size, scalesWithText: false)
                    Text("👍").font(.system(size: size))
                    ReactionGlyph(emoji: .oshiSmile, size: size, scalesWithText: false)
                }
                .padding(size * 0.4)
                .background(Capsule().fill(Color(uiColor: .tertiarySystemBackground)))
                .padding(size * 0.4)
                .background(Color(uiColor: .systemBackground))
                .environment(\.colorScheme, scheme)
                let renderer = ImageRenderer(content: view)
                renderer.scale = 3
                let image = try XCTUnwrap(renderer.uiImage)
                let name = "oshi_o_\(scheme == .dark ? "dark" : "light")_\(Int(size))pt@3x.png"
                let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
                try image.pngData()?.write(to: url)
                print("OSHI_O_PNG \(url.path)")
                XCTAssertGreaterThan(image.size.width, size * 2)
            }
        }
    }


    func testOSHIOWireBytesAreExact() {
        XCTAssertEqual(Array(ReactionEmoji.oshiO.rawValue.utf8), [0xE2, 0xAD, 0x95])
        XCTAssertEqual(ReactionEmoji.oshiO.rawValue.unicodeScalars.map(\.value), [0x2B55])
        XCTAssertEqual(ReactionEmoji(rawValue: "\u{2B55}"), .oshiO)
        XCTAssertTrue(ReactionEmoji.oshiO.isOSHIMark)
        // The smile is untouched and still a different reaction.
        XCTAssertNotEqual(ReactionEmoji.oshiO, .oshiSmile)
        XCTAssertEqual(ReactionEmoji.oshiSmile.rawValue, "\u{2B55}\u{1F60A}")
    }

    func testOSHIORejectsTheVariationSelector() {
        let withFE0F = "\u{2B55}\u{FE0F}"
        XCTAssertNil(ReactionEmoji(rawValue: withFE0F))
        // Both formats keep it as the plain emoji "⭕️" — never the O, and
        // (since __UNKNOWN_REACTION_AS_ITSELF_2026_11_16__) never 👍 either.
        let ios = ReactionPayload.messagePrefix
            + "{\"messageId\":\"m1\",\"emoji\":\"\(withFE0F)\",\"senderPublicKey\":\"K\",\"senderName\":\"\",\"timestamp\":0,\"action\":\"add\"}"
        let android = ReactionPayload.androidPrefix
            + "{\"messageId\":\"m1\",\"emoji\":\"\(withFE0F)\",\"isAdding\":true}"
        for wire in [ios, android] {
            let parsed = ReactionPayload.fromMessageString(wire)
            XCTAssertEqual(parsed?.emoji.rawValue.unicodeScalars.map(\.value), [0x2B55, 0xFE0F], wire)
            XCTAssertNotEqual(parsed?.emoji, .oshiO)
            XCTAssertNotEqual(parsed?.emoji, .thumbsUp)
            XCTAssertEqual(parsed?.emoji.isOSHIMark, false)
        }
    }

    // MARK: - 6. Unknown reactions shown as themselves — __UNKNOWN_REACTION_AS_ITSELF_2026_11_16__

    func testUnknownEmojiIsKeptInBothFormats() {
        for raw in ["🦄", "🇫🇷", "👍🏽", "🧑‍💻", "🫶", "😊⭕"] {
            XCTAssertNil(ReactionEmoji(rawValue: raw), "not offered by this build: \(raw)")
            let ios = ReactionPayload.messagePrefix
                + "{\"messageId\":\"m1\",\"emoji\":\"\(raw)\",\"senderPublicKey\":\"K\",\"senderName\":\"N\",\"timestamp\":0,\"action\":\"remove\"}"
            let android = ReactionPayload.androidPrefix
                + "{\"messageId\":\"m1\",\"emoji\":\"\(raw)\",\"isAdding\":true,\"senderPublicKey\":\"K\"}"
            let a = ReactionPayload.fromMessageString(ios)
            XCTAssertEqual(a?.emoji.rawValue, raw)
            XCTAssertEqual(a?.action, .remove)
            let b = ReactionPayload.fromMessageString(android)
            XCTAssertEqual(b?.emoji.rawValue, raw)
            XCTAssertEqual(b?.action, .add)
            XCTAssertEqual(b?.emoji.displayEmoji, raw)
            XCTAssertEqual(b?.emoji.accessibilityLabel, raw, "VoiceOver reads the emoji itself")
            XCTAssertEqual(b?.emoji.isKnown, false)
            XCTAssertEqual(b?.emoji.isOSHIMark, false)
            // Re-sent unchanged (round trip through our own format).
            if let e = b?.emoji {
                let wire = ReactionPayload(messageId: "m1", emoji: e, senderPublicKey: "K", senderName: "",
                                           timestamp: Date(), action: .add).toMessageString()
                XCTAssertEqual(ReactionPayload.fromMessageString(wire)?.emoji.rawValue, raw)
            }
        }
    }

    func testKnownEmojiStillMapsToTheKnownReaction() {
        let android = ReactionPayload.androidPrefix + "{\"messageId\":\"m1\",\"emoji\":\"❤️\",\"isAdding\":true}"
        XCTAssertEqual(ReactionPayload.fromMessageString(android)?.emoji, .heart)
        XCTAssertEqual(ReactionEmoji(wire: "\u{2B55}"), .oshiO)
        XCTAssertEqual(ReactionEmoji(wire: "\u{2B55}\u{1F60A}"), .oshiSmile)
        XCTAssertTrue(ReactionEmoji.oshiO.isKnown)
    }

    func testJunkReactionPayloadIsDropped() {
        for junk in ["", " ", "hello", "👍 ", "\n", "\u{7}", "abc", String(repeating: "👍", count: 3)] {
            XCTAssertNil(ReactionEmoji(wire: junk), junk.debugDescription)
            let android = ReactionPayload.androidPrefix
                + "{\"messageId\":\"m1\",\"emoji\":\(String(data: try! JSONEncoder().encode(junk), encoding: .utf8)!),\"isAdding\":true}"
            XCTAssertNil(ReactionPayload.fromMessageString(android), junk.debugDescription)
        }
    }

    func testUnknownEmojiCountsTogglesAndPersistsByExactString() throws {
        let rm = MessageReactionManager()
        let msg = "unknown-emoji-test-\(UUID().uuidString)"
        defer { rm.removeReactionsForMessage(msg) }
        let pray = try XCTUnwrap(ReactionEmoji(wire: "🙏"))
        let prayDark = try XCTUnwrap(ReactionEmoji(wire: "🙏🏿"))

        for (key, e) in [("A", pray), ("B", pray), ("C", prayDark)] {
            rm.processIncomingReaction(ReactionPayload(messageId: msg, emoji: e, senderPublicKey: key,
                                                       senderName: key, timestamp: Date(), action: .add))
        }
        let applied = expectation(description: "incoming applied")
        DispatchQueue.main.async { applied.fulfill() }
        wait(for: [applied], timeout: 2)

        let counts = rm.getReactionCounts(for: msg)
        XCTAssertEqual(counts.first { $0.emoji.rawValue == "🙏" }?.count, 2)
        XCTAssertEqual(counts.first { $0.emoji.rawValue == "🙏🏿" }?.count, 1, "skin tone is a different reaction")
        XCTAssertNil(counts.first { $0.emoji == .thumbsUp }, "never rewritten to 👍")

        // Remove only matches the exact string.
        rm.processIncomingReaction(ReactionPayload(messageId: msg, emoji: prayDark, senderPublicKey: "A",
                                                   senderName: "A", timestamp: Date(), action: .remove))
        rm.processIncomingReaction(ReactionPayload(messageId: msg, emoji: prayDark, senderPublicKey: "C",
                                                   senderName: "C", timestamp: Date(), action: .remove))
        let removed = expectation(description: "incoming remove applied")
        DispatchQueue.main.async { removed.fulfill() }
        wait(for: [removed], timeout: 2)
        XCTAssertTrue(rm.hasUserReacted(messageId: msg, senderPublicKey: "A", emoji: pray))
        XCTAssertFalse(rm.hasUserReacted(messageId: msg, senderPublicKey: "C"))

        // Toggling an unknown reaction locally (same string) removes it.
        rm.addReaction(to: msg, emoji: pray, senderPublicKey: "B", senderName: "B", broadcast: false)
        XCTAssertFalse(rm.hasUserReacted(messageId: msg, senderPublicKey: "B"))

        // Persistence: the stored map decodes back to the same string.
        let data = try JSONEncoder().encode(rm.reactions[msg] ?? [])
        let back = try JSONDecoder().decode([MessageReaction].self, from: data)
        XCTAssertEqual(back.map(\.emoji.rawValue), ["🙏"])
        XCTAssertEqual(try JSONEncoder().encode(ReactionEmoji.thumbsUp), Data("\"👍\"".utf8))
    }

    func testReactionAccessibilityLabelsAreLocalizedKeys() {
        for e in ReactionEmoji.allCases {
            let key = try? XCTUnwrap(e.accessibilityKey, e.rawValue)
            XCTAssertNotNil(key, e.rawValue)
            XCTAssertNotEqual(e.accessibilityLabel, key, "missing string for \(key ?? "")")
            XCTAssertFalse(e.accessibilityLabel.isEmpty)
        }
        XCTAssertEqual(Set(ReactionEmoji.allCases.compactMap(\.accessibilityKey)).count, ReactionEmoji.allCases.count)
    }

    func testOSHIORoundTripsInBothFormats() throws {
        for action in [ReactionPayload.ReactionAction.add, .remove] {
            let p = ReactionPayload(messageId: "m1", emoji: .oshiO, senderPublicKey: "K",
                                    senderName: "You", timestamp: Date(), action: action)
            let wire = p.toMessageString()
            XCTAssertTrue(wire.hasPrefix(ReactionPayload.messagePrefix))
            XCTAssertTrue(wire.contains("\"emoji\":\"\u{2B55}\""), wire)
            XCTAssertFalse(wire.contains("\u{FE0F}"), wire)
            let back = ReactionPayload.fromMessageString(wire)
            XCTAssertEqual(back?.emoji, .oshiO)
            XCTAssertEqual(back?.action, action)
        }
        for (isAdding, action) in [(true, ReactionPayload.ReactionAction.add), (false, .remove)] {
            let android = ReactionPayload.androidPrefix
                + "{\"messageId\":\"m1\",\"emoji\":\"\u{2B55}\",\"isAdding\":\(isAdding),\"senderPublicKey\":\"K\"}"
            let parsed = ReactionPayload.fromMessageString(android)
            XCTAssertEqual(parsed?.emoji, .oshiO)
            XCTAssertEqual(parsed?.action, action)
            XCTAssertEqual(parsed?.messageId, "m1")
        }
    }

    func testOSHIOAddRemoveSwitchAndCount() {
        // A fresh manager loads the persisted map; removing only this test's
        // message at the end saves that map back unchanged.
        let rm = MessageReactionManager()
        let msg = "oshi-o-test-\(UUID().uuidString)"
        defer { rm.removeReactionsForMessage(msg) }

        // Add.
        rm.addReaction(to: msg, emoji: .oshiO, senderPublicKey: "A", senderName: "A", broadcast: false)
        XCTAssertTrue(rm.hasUserReacted(messageId: msg, senderPublicKey: "A", emoji: .oshiO))
        XCTAssertEqual(rm.getUserReaction(messageId: msg, senderPublicKey: "A"), .oshiO)

        // Count: a second person with the same O.
        rm.addReaction(to: msg, emoji: .oshiO, senderPublicKey: "B", senderName: "B", broadcast: false)
        XCTAssertEqual(rm.getReactionCounts(for: msg).first { $0.emoji == .oshiO }?.count, 2)

        // Switch: A moves to the smile — one reaction per person.
        rm.addReaction(to: msg, emoji: .oshiSmile, senderPublicKey: "A", senderName: "A", broadcast: false)
        XCTAssertEqual(rm.getUserReaction(messageId: msg, senderPublicKey: "A"), .oshiSmile)
        XCTAssertEqual(rm.getReactionCounts(for: msg).first { $0.emoji == .oshiO }?.count, 1)
        XCTAssertEqual(rm.getReactions(for: msg).count, 2)

        // Remove (toggle): B taps the O again.
        rm.toggleReaction(to: msg, emoji: .oshiO, senderPublicKey: "B", senderName: "B")
        XCTAssertFalse(rm.hasUserReacted(messageId: msg, senderPublicKey: "B"))
        XCTAssertNil(rm.getReactionCounts(for: msg).first { $0.emoji == .oshiO })

        // Incoming add / remove from another device behave the same.
        let add = ReactionPayload(messageId: msg, emoji: .oshiO, senderPublicKey: "C",
                                  senderName: "C", timestamp: Date(), action: .add)
        rm.processIncomingReaction(add)
        let added = expectation(description: "incoming add applied")
        DispatchQueue.main.async { added.fulfill() }
        wait(for: [added], timeout: 2)
        XCTAssertTrue(rm.hasUserReacted(messageId: msg, senderPublicKey: "C", emoji: .oshiO))
        let remove = ReactionPayload(messageId: msg, emoji: .oshiO, senderPublicKey: "C",
                                     senderName: "C", timestamp: Date(), action: .remove)
        rm.processIncomingReaction(remove)
        let removed = expectation(description: "incoming remove applied")
        DispatchQueue.main.async { removed.fulfill() }
        wait(for: [removed], timeout: 2)
        XCTAssertFalse(rm.hasUserReacted(messageId: msg, senderPublicKey: "C"))
    }

    /// __MASCOT_SAFE_WIRE_2026_11_16__ the O, then six mascots whose emoji
    /// every OSHI build knows (the crying one sends 😢).
    func testQuickBarOrder() {
        XCTAssertEqual(ReactionEmoji.quickReactions,
                       [.oshiO, .heart, .laugh, .sad, .thumbsUp, .wow, .fire])
        XCTAssertEqual(ReactionEmoji.quickReactions.map(\.rawValue).joined(),
                       "\u{2B55}❤️😂😢👍😮🔥")
    }

    func testQuickBarFitsA375PointScreenWith44PointTargets() {
        XCTAssertEqual(QuickBarMetrics.button, 44)
        XCTAssertEqual(QuickBarMetrics.spacing, 0)
        XCTAssertEqual(QuickBarMetrics.padding, 4)
        XCTAssertEqual(QuickBarMetrics.screenInset, 6)
        let n = ReactionEmoji.quickReactions.count
        XCTAssertEqual(QuickBarMetrics.barWidth(reactionCount: n), 360)
        XCTAssertLessThanOrEqual(QuickBarMetrics.requiredScreenWidth(reactionCount: n), 375)
    }

    func testPlusPickerOrder() {
        let main = ReactionEmoji.pickerReactions
        let new = ReactionEmoji.pickerNewReactions
        XCTAssertEqual(Array(main.prefix(2)), [.oshiO, .oshiSmile])
        XCTAssertEqual(Array(main.dropFirst(2).prefix(12)),
                       [.heart, .laugh, .thumbsUp, .sad, .wow, .fire, .party, .kiss, .clap, .think, .check, .hundred])
        XCTAssertEqual(Array(main.suffix(8)),
                       [.cross, .thumbsDown, .middleFinger, .eyes,
                        .christian, .muslim, .jewish, .buddhist])
        XCTAssertEqual(new, [.angry, .pray, .heartEyes, .cool, .sleepy, .wave, .peek, .tearSmile])
        let all = ReactionEmoji.allReactions
        XCTAssertEqual(all, main + new)
        XCTAssertEqual(all.count, 30)
        XCTAssertEqual(Set(all).count, all.count, "no duplicates")
        XCTAssertEqual(Set(all), Set(ReactionEmoji.allCases).subtracting(ReactionEmoji.notOffered))
    }

    func testOverlayStaysOnScreen() {
        // Near the bottom: pulled up so the whole content fits.
        XCTAssertEqual(ReactionOverlay.clampTop(desired: 700, contentHeight: 140,
                                                availableHeight: 760, margin: 8), 612)
        // Near the top: never above the safe area.
        XCTAssertEqual(ReactionOverlay.clampTop(desired: -40, contentHeight: 140,
                                                availableHeight: 760, margin: 8), 8)
        // In the middle: where it was asked.
        XCTAssertEqual(ReactionOverlay.clampTop(desired: 300, contentHeight: 140,
                                                availableHeight: 760, margin: 8), 300)
        // Taller than the screen: pinned to the top margin.
        XCTAssertEqual(ReactionOverlay.clampTop(desired: 300, contentHeight: 900,
                                                availableHeight: 760, margin: 8), 8)
    }

    /// Lays the real bar out and checks its measured width, then writes it in
    /// light and dark for eyeballing (temp dir).
    @MainActor
    func testTheQuickBarRendersAt360Points() throws {
        for (name, scheme) in [("light", ColorScheme.light), ("dark", .dark)] {
            let bar = WhatsAppReactionPicker(messageId: "render", senderPublicKey: "K", senderName: "K",
                                             onReact: { _ in }, onDismiss: {}, onShowMore: {})
                .padding(12)
                .background(scheme == .dark ? Color.black : Color(white: 0.9))
                .environment(\.colorScheme, scheme)
            let renderer = ImageRenderer(content: bar)
            renderer.scale = 3
            let image = try XCTUnwrap(renderer.uiImage)
            XCTAssertEqual(image.size.width, 360 + 24, accuracy: 1)
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("oshi_quick_bar_\(name).png")
            try image.pngData()?.write(to: url)
            print("OSHI_QUICK_BAR_PNG \(url.path)")
        }
    }

    @MainActor
    func testTheOSHIOAssetExists() {
        XCTAssertNotNil(UIImage(named: "ReactionOSHIO"))
    }

    // MARK: - List timestamps follow the clock

    func testListTimestampFollowsNow() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Europe/Paris")!
        cal.firstWeekday = 2
        let msg = cal.date(from: DateComponents(year: 2026, month: 11, day: 11, hour: 23, minute: 58))!
        let fiveMinLater = msg.addingTimeInterval(300)       // past midnight
        XCTAssertEqual(ListTimestamp.kind(for: msg, now: msg, includeWeekday: true, calendar: cal), .time)
        XCTAssertEqual(ListTimestamp.kind(for: msg, now: fiveMinLater, includeWeekday: true, calendar: cal), .yesterday)
        let twoDays = msg.addingTimeInterval(2 * 86_400)
        XCTAssertEqual(ListTimestamp.kind(for: msg, now: twoDays, includeWeekday: true, calendar: cal), .weekday)
        XCTAssertEqual(ListTimestamp.kind(for: msg, now: twoDays, includeWeekday: false, calendar: cal), .date)
    }
}

// MARK: - __ANCHOR_SIGHTED_EARLY_2026_11_16__ "landed at the bottom"

/// The open / incoming landing loops may stop only when the real gap below the
/// viewport is closed: content still hidden below is not a landing, and neither
/// is a viewport past the end of the content (bare wallpaper after an overshoot,
/// seen on the simulator after a very long send).
final class ChatLandingGapTests: XCTestCase {
    func testOnlyAClosedGapCountsAsLanded() {
        XCTAssertTrue(ChatScrollTracker.gapIsClosed(0))
        XCTAssertTrue(ChatScrollTracker.gapIsClosed(20))
        XCTAssertTrue(ChatScrollTracker.gapIsClosed(-20))
        XCTAssertFalse(ChatScrollTracker.gapIsClosed(80), "newer rows still below the fold")
        XCTAssertFalse(ChatScrollTracker.gapIsClosed(-300), "viewport past the end: only wallpaper")
        XCTAssertTrue(ChatScrollTracker.gapIsClosed(nil), "no geometry (iOS 17) must not block the landing")
    }
}
