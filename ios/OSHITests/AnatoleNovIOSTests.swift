//
//  AnatoleNovIOSTests.swift
//  OSHITests
//
//  __ANATOLE_1311_IOS__ The iOS port of Anatole's 13/11 report (N2, N6, N8,
//  N10, N12, N18). Pure logic only: the stores take an injected UserDefaults
//  suite and an injected App Group writer, so nothing here touches the real
//  defaults or the shared container.
//

import XCTest
@testable import OSHI

final class AnatoleNovIOSTests: XCTestCase {

    private var suiteName: String!
    private var defaults: UserDefaults!
    private var published: [[String]] = []

    override func setUp() {
        super.setUp()
        suiteName = "AnatoleNovIOSTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        published = []
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    private func makeStore() -> ConversationPrefsStore {
        ConversationPrefsStore(defaults: defaults, publishMuted: { [weak self] in self?.published.append($0) })
    }

    // Standard and URL-safe spellings of the same 32-byte key.
    private let keyStd = "eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN/muFE3A="
    private let keyUrl = "eAbMQUdW6UtufTtaHqDJtkC24fKa3c1nhaGN_muFE3A"

    // MARK: N6 — the logo-tap wipe is opt-in

    func testPanicGestureIsOffByDefault() {
        XCTAssertFalse(PanicGesture.isArmed(defaults))
    }

    func testPanicGestureArmsOnlyWhenTurnedOn() {
        defaults.set(true, forKey: PanicGesture.enabledDefaultsKey)
        XCTAssertTrue(PanicGesture.isArmed(defaults))
        defaults.set(false, forKey: PanicGesture.enabledDefaultsKey)
        XCTAssertFalse(PanicGesture.isArmed(defaults))
    }

    // MARK: N8 — pin

    func testPinToggleAndSurvivesReload() {
        let store = makeStore()
        XCTAssertFalse(store.isPinned(keyStd))
        store.togglePinned(keyStd)
        XCTAssertTrue(store.isPinned(keyStd))
        XCTAssertTrue(store.isPinned(keyUrl), "base64url spelling is the same contact")
        XCTAssertTrue(makeStore().isPinned(keyStd), "persisted")
        store.togglePinned(keyUrl)
        XCTAssertFalse(store.isPinned(keyStd))
    }

    func testPinnedFirstKeepsRecencyInsideEachHalf() {
        let items = ["a", "b", "c", "d", "e"]
        let ordered = ConversationPrefsStore.pinnedFirst(items, pinned: ["d", "b"], key: { $0 })
        XCTAssertEqual(ordered, ["b", "d", "a", "c", "e"])
        XCTAssertEqual(ConversationPrefsStore.pinnedFirst(items, pinned: [], key: { $0 }), items)
    }

    // MARK: N8 — mute, mirrored for the notification extension

    func testMuteIsPublishedForTheExtensionInCanonicalForm() {
        let store = makeStore()
        store.toggleMuted(keyUrl)
        XCTAssertTrue(store.isMuted(keyStd))
        XCTAssertEqual(published.last, [MessageBannerPolicy.normalizedKey(keyStd)])
        // The extension decides with the blocked-sender rule on that snapshot.
        XCTAssertTrue(MessageBannerPolicy.senderIsBlocked(keyStd, blockedKeys: published.last ?? []))
        XCTAssertTrue(MessageBannerPolicy.senderIsBlocked(keyUrl, blockedKeys: published.last ?? []))
        store.toggleMuted(keyStd)
        XCTAssertFalse(store.isMuted(keyUrl))
        XCTAssertEqual(published.last, [])
    }

    // MARK: N18 — saved contacts survive a relaunch

    func testSavedContactPersistsAndKeepsItsFirstDate() {
        let first = Date(timeIntervalSince1970: 1_000)
        let store = makeStore()
        store.markSaved(keyStd, at: first)
        store.markSaved(keyUrl, at: Date(timeIntervalSince1970: 9_000))  // re-scan
        let reloaded = makeStore()
        XCTAssertTrue(reloaded.isSaved(keyUrl))
        XCTAssertEqual(reloaded.saved.count, 1)
        XCTAssertEqual(reloaded.saved.values.first, first)
    }

    func testDeletingTheThreadForgetsTheSavedContact() {
        let store = makeStore()
        store.markSaved(keyStd)
        store.forgetSaved(keyUrl)
        XCTAssertFalse(makeStore().isSaved(keyStd))
    }

    func testIdentitySwitchReplacesTheSavedSet() {
        let store = makeStore()
        store.markSaved("AAAA")
        store.replaceSaved([keyUrl: Date(timeIntervalSince1970: 5)])
        XCTAssertFalse(store.isSaved("AAAA"))
        XCTAssertTrue(store.isSaved(keyStd))
        store.forgetAllSaved()
        XCTAssertTrue(makeStore().saved.isEmpty)
    }

    // MARK: N10 — banners are cleared per conversation, never all at once

    func testOneToOneAndGroupBannersHaveDistinctConversationKeys() {
        let oneToOne: [AnyHashable: Any] = ["senderPublicKey": keyUrl, "messageId": "m1"]
        let group: [AnyHashable: Any] = ["groupId": "1f0c6b0e-0000-4000-8000-000000000001",
                                          "senderPublicKey": keyStd]
        XCTAssertEqual(MessageBannerPolicy.conversationKey(oneToOne),
                       "c:" + MessageBannerPolicy.normalizedKey(keyStd))
        XCTAssertEqual(MessageBannerPolicy.conversationKey(group),
                       "g:1F0C6B0E-0000-4000-8000-000000000001",
                       "a group banner is never matched as the sender's 1:1 thread")
    }

    func testAppActivationNoLongerClearsEveryBanner() throws {
        // Source guard: the blanket clear on activation is what made unread
        // banners vanish on app open. It may only live in the panic wipe.
        let src = try Self.source("OSHI/SceneDelegate.swift")
        XCTAssertFalse(src.contains(".removeAllDeliveredNotifications("))
    }

    // MARK: N2 — map types are MapKit or OSHI only

    func testMapBackgroundsOfferSatelliteWithoutThirdPartyTiles() {
        XCTAssertEqual(MapBackground.allCases, [.oshi, .apple, .appleSatellite])
        XCTAssertFalse(MapBackground.oshi.usesAppleBasemap)
        XCTAssertTrue(MapBackground.appleSatellite.usesAppleBasemap)
        for option in MapBackground.allCases {
            XCTAssertNotEqual(option.titleKey.localized, option.titleKey, "missing EN title for \(option)")
            XCTAssertNotEqual(option.detailKey.localized, option.detailKey, "missing EN detail for \(option)")
        }
    }

    // MARK: N12 — call discs still fit four across the narrowest pane

    func testFourCallDiscsFitA320PointPane() {
        // VoiceCallView's row: `.padding()` (32) and three 12 pt gaps.
        let share = (320.0 - 32.0 - 3 * 12.0) / 4.0
        XCTAssertLessThanOrEqual(CallControlButton.discSize, CGFloat(share))
        XCTAssertGreaterThan(CallControlButton.discSize, 56, "N12 asked for thicker buttons")
    }

    // MARK: New strings exist in EN and FR

    func testNewStringsAreInEnglishAndFrench() throws {
        let keys = ["conversation.open_contact", "conversation.saved_contact_preview",
                    "new.menu.contact", "settings.security.panicWipe",
                    "settings.security.panicWipe.footer", "map_layer_title",
                    "map_background_apple_satellite", "map_background_apple_satellite_detail"]
        for lang in ["en", "fr"] {
            let src = try Self.source("OSHI/\(lang).lproj/Localizable.strings")
            for key in keys {
                XCTAssertTrue(src.contains("\"\(key)\" = "), "\(key) missing in \(lang)")
            }
        }
    }

    // MARK: helpers

    private static func source(_ relative: String) throws -> String {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
        return try String(contentsOf: root.appendingPathComponent(relative), encoding: .utf8)
    }
}
