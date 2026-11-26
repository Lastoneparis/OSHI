//
//  AdvancedChatPrivacyTests.swift
//  OSHITests
//
//  __ADVANCED_CHAT_PRIVACY_2026_11_26__ The wire (shared with Android), the
//  last-writer-wins rule, the peer-clock clamp, the catalog classification, and
//  the enforcement rules (AI context, export, saving media, who is sent it).
//

import XCTest
@testable import OSHI

@MainActor
final class AdvancedChatPrivacyTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private static func key(_ byte: UInt8) -> String {
        Data(repeating: byte, count: 32).base64EncodedString()
    }
    private let me = AdvancedChatPrivacyTests.key(1)
    private let alice = AdvancedChatPrivacyTests.key(2)
    private let bob = AdvancedChatPrivacyTests.key(3)

    // MARK: - Wire

    func testEncodeIsTheDocumentedWireFormat() {
        let change = AdvancedPrivacyChange(on: true, at: 1_761_220_000)
        XCTAssertEqual(change.toMessageString(), #"🛡ADV_PRIVACY🛡{"v":1,"on":true,"at":1761220000}"#)
        XCTAssertEqual(AdvancedPrivacyChange(on: false, at: 5).toMessageString(),
                       #"🛡ADV_PRIVACY🛡{"v":1,"on":false,"at":5}"#)
    }

    func testRoundTrip() {
        for on in [true, false] {
            let change = AdvancedPrivacyChange(on: on, at: 1_761_220_123)
            XCTAssertEqual(AdvancedPrivacyChange.fromMessageString(change.toMessageString()), change)
        }
        // From a date: whole seconds, floored.
        let fromDate = AdvancedPrivacyChange(on: true, date: Date(timeIntervalSince1970: 1_761_220_000.9))
        XCTAssertEqual(fromDate.at, 1_761_220_000)
    }

    func testDecodeAcceptsAndroidSpellingsAndRejectsGarbage() {
        // Key order is not part of the format; a fractional `at` is floored.
        XCTAssertEqual(AdvancedPrivacyChange.fromMessageString(#"🛡ADV_PRIVACY🛡{"at":12.7,"on":false,"v":1}"#),
                       AdvancedPrivacyChange(on: false, at: 12))
        // Unknown future version, missing field, non-boolean `on`, not JSON, no prefix.
        XCTAssertNil(AdvancedPrivacyChange.fromMessageString(#"🛡ADV_PRIVACY🛡{"v":2,"on":true,"at":1}"#))
        XCTAssertNil(AdvancedPrivacyChange.fromMessageString(#"🛡ADV_PRIVACY🛡{"v":1,"at":1}"#))
        XCTAssertNil(AdvancedPrivacyChange.fromMessageString(#"🛡ADV_PRIVACY🛡{"v":1,"on":1,"at":1}"#))
        XCTAssertNil(AdvancedPrivacyChange.fromMessageString("🛡ADV_PRIVACY🛡not json"))
        XCTAssertNil(AdvancedPrivacyChange.fromMessageString(#"{"v":1,"on":true,"at":1}"#))
        // The U+FE0F spelling is accepted on receive (never sent).
        let withSelector = "\u{1F6E1}\u{FE0F}ADV_PRIVACY\u{1F6E1}\u{FE0F}" + #"{"v":1,"on":true,"at":1764150000}"#
        XCTAssertEqual(AdvancedPrivacyChange.fromMessageString(withSelector), AdvancedPrivacyChange(on: true, at: 1_764_150_000))
        XCTAssertTrue(OSHIControlPayload.isQuietNotice(withSelector))
        XCTAssertFalse(OSHIControlPayload.previewText(for: withSelector).contains("ADV_PRIVACY"))
        XCTAssertFalse(AdvancedPrivacyChange(on: true, at: 1).toMessageString().unicodeScalars.contains { $0.value == 0xFE0F },
                       "sent bare, as Android does")
        // A sentinel quoted mid-sentence is prose.
        XCTAssertNil(AdvancedPrivacyChange.fromMessageString(#"look: 🛡ADV_PRIVACY🛡{"v":1,"on":true,"at":1}"#))
    }

    // MARK: - Last writer wins

    func testResolveLastWriterWins() {
        let p = AdvancedChatPrivacyPolicy.self
        let first = p.resolve(existing: nil, incoming: .init(on: true, at: 100), by: alice, now: now)
        XCTAssertEqual(first, .init(on: true, at: 100, by: alice))
        // Older loses (mesh delivery is unordered).
        XCTAssertNil(p.resolve(existing: first, incoming: .init(on: false, at: 99), by: bob, now: now))
        // Newer wins.
        XCTAssertEqual(p.resolve(existing: first, incoming: .init(on: false, at: 101), by: bob, now: now),
                       .init(on: false, at: 101, by: bob))
        // Same second, opposite values: ON wins whichever arrives first.
        let off = AdvancedChatPrivacyPolicy.State(on: false, at: 200, by: alice)
        XCTAssertEqual(p.resolve(existing: off, incoming: .init(on: true, at: 200), by: bob, now: now)?.on, true)
        let on = AdvancedChatPrivacyPolicy.State(on: true, at: 200, by: alice)
        XCTAssertNil(p.resolve(existing: on, incoming: .init(on: false, at: 200), by: bob, now: now))
        // A replay of the same decision is not a change.
        XCTAssertNil(p.resolve(existing: on, incoming: .init(on: true, at: 200), by: alice, now: now))
    }

    func testStoreAppliesInOrderOfAtNotOfArrival() {
        let store = AdvancedChatPrivacyStore(inMemory: true)
        let conv = alice
        XCTAssertFalse(store.isOn(conv))
        XCTAssertTrue(store.apply(.init(on: true, at: 1_000), by: alice, for: conv, now: now))
        XCTAssertTrue(store.isOn(conv))
        // An OFF written earlier but delivered later does not undo it.
        XCTAssertFalse(store.apply(.init(on: false, at: 900), by: me, for: conv, now: now))
        XCTAssertTrue(store.isOn(conv))
        XCTAssertTrue(store.apply(.init(on: false, at: 1_001), by: me, for: conv, now: now))
        XCTAssertFalse(store.isOn(conv))
        XCTAssertEqual(store.state(conv)?.by, MessageManager.canonicalIdentity(me))
    }

    func testGroupAndOneToOneAreSeparateConversations() {
        let store = AdvancedChatPrivacyStore(inMemory: true)
        let group = UUID()
        store.apply(.init(on: true, at: 10), by: alice, for: group.uuidString, now: now)
        XCTAssertTrue(store.isOn(group.uuidString))
        XCTAssertTrue(store.isOn(group.uuidString.lowercased()), "group ids fold case")
        XCTAssertFalse(store.isOn(alice))
    }

    // MARK: - Future clamp

    func testPeerClockInTheFutureIsClampedToFiveMinutes() {
        let store = AdvancedChatPrivacyStore(inMemory: true)
        let conv = bob
        let year3000: Int64 = 32_503_680_000
        store.apply(.init(on: true, at: year3000), by: bob, for: conv, now: now)
        XCTAssertEqual(store.state(conv)?.at, Int64(now.timeIntervalSince1970) + 300)
        // A real change ten minutes later still wins: the far-future stamp did
        // not pin the setting.
        let later = now.addingTimeInterval(600)
        XCTAssertTrue(store.apply(.init(on: false, date: later), by: me, for: conv, now: later))
        XCTAssertFalse(store.isOn(conv))
        XCTAssertEqual(AdvancedChatPrivacyPolicy.clampedAt(1_799_999_000, now: now), 1_799_999_000)
    }

    // MARK: - Catalog classification

    func testCatalogClassification() {
        let payload = AdvancedPrivacyChange(on: true, at: 1).toMessageString()
        XCTAssertEqual(OSHIControlPayload.matchedPrefix(payload), OSHIControlPayload.advancedPrivacyPrefix)
        XCTAssertTrue(OSHIControlPayload.catalog.contains { $0.prefix == "🛡ADV_PRIVACY🛡" && $0.kind == .rendered })
        XCTAssertEqual(OSHIControlPayload.catalog.filter { $0.prefix == "🛡ADV_PRIVACY🛡" }.count, 1,
                       "declared once")
        // Never shown raw, never fed to AI (isControl), drawn (not silent / not
        // hidden), but neither counted as unread nor allowed to wake anyone.
        XCTAssertTrue(OSHIControlPayload.isControl(payload))
        XCTAssertFalse(OSHIControlPayload.isSilent(payload))
        XCTAssertFalse(OSHIControlPayload.isHiddenFromThread(payload))
        XCTAssertTrue(OSHIControlPayload.isQuietNotice(payload))
        XCTAssertFalse(OSHIControlPayload.countsAsUnread(payload))
        XCTAssertTrue(OSHIControlPayload.suppressesPush(payload))
        XCTAssertTrue(OSHIControlPayload.pushSilentPrefixes.contains(OSHIControlPayload.advancedPrivacyPrefix))
        XCTAssertFalse(MessageTextSplitter.isUserProse(payload))
        // Ordinary prose still counts and is still prose.
        XCTAssertTrue(OSHIControlPayload.countsAsUnread("hello"))
        XCTAssertFalse(OSHIControlPayload.isQuietNotice("hello"))
        // The check-in shield (with U+FE0F) is a different sentinel.
        XCTAssertFalse(OSHIControlPayload.isQuietNotice("🛡️CHECK_IN🛡️{}"))
    }

    func testPreviewIsAHumanSentenceNeverTheSentinel() {
        for on in [true, false] {
            let raw = AdvancedPrivacyChange(on: on, at: 1).toMessageString()
            let preview = OSHIControlPayload.previewText(for: raw)
            XCTAssertFalse(preview.contains("ADV_PRIVACY"), preview)
            XCTAssertFalse(preview.contains("adv_privacy."), "raw key leaked: \(preview)")
            XCTAssertFalse(preview.isEmpty)
            // The Watch bridge gets the same sentence.
            XCTAssertEqual(OSHIControlPayload.watchSafeText(raw, fallback: "x"), preview)
        }
        XCTAssertNotEqual(OSHIControlPayload.previewText(for: AdvancedPrivacyChange(on: true, at: 1).toMessageString()),
                          OSHIControlPayload.previewText(for: AdvancedPrivacyChange(on: false, at: 1).toMessageString()))
    }

    func testNoticeSentenceNamesWhoDidIt() {
        let named = AdvancedPrivacyText.notice(on: true, actorName: "Anatole")
        XCTAssertTrue(named.contains("Anatole"), named)
        XCTAssertFalse(named.contains("%@"))
        XCTAssertFalse(AdvancedPrivacyText.notice(on: false, actorName: nil).hasPrefix("adv_privacy."))
    }

    // MARK: - Enforcement rules

    func testAIContextIsWithheldFromAProtectedChat() {
        let ctx = "Them: hi\nYou: hello"
        XCTAssertNil(AdvancedChatPrivacyPolicy.aiContext(ctx, protected: true))
        XCTAssertEqual(AdvancedChatPrivacyPolicy.aiContext(ctx, protected: false), ctx)
        XCTAssertFalse(AdvancedChatPrivacyPolicy.allowsAI(protected: true))
        XCTAssertTrue(AdvancedChatPrivacyPolicy.allowsAI(protected: false))
        // And the payload itself never reaches a prompt from an unprotected chat.
        XCTAssertTrue(OSHIControlPayload.isControl(AdvancedPrivacyChange(on: false, at: 1).toMessageString()))
    }

    func testExportLeavesProtectedConversationsOut() throws {
        let gProtected = UUID(), gOpen = UUID()
        func direct(_ id: String, from: String, to: String, _ text: String) -> SecureMessage {
            SecureMessage(id: id, senderAddress: from, recipientAddress: to,
                          encryptedContent: EncryptedMessage(ciphertext: "", signature: "", senderPublicKey: from, timestamp: now),
                          timestamp: now, isRead: true, deliveryStatus: .sent,
                          senderPublicKey: from, recipientPublicKey: to, plaintextContent: text)
        }
        func group(_ gid: UUID, _ text: String) -> MessageExportV2.GroupItem {
            MessageExportV2.GroupItem(
                message: GroupMessage(id: UUID(), groupId: gid, senderPublicKey: alice, encryptedContent: Data(text.utf8),
                                      timestamp: now, plaintextContent: text),
                text: text, groupName: "g")
        }
        let snapshot = MessageExportV2.Snapshot(
            direct: [direct("1", from: alice, to: me, "secret-in"),
                     direct("2", from: me, to: alice, "secret-out"),
                     direct("3", from: bob, to: me, "open-bob")],
            groups: [group(gProtected, "secret-group"), group(gOpen, "open-group")],
            myKey: me)

        let protected: Set<String> = [MessageManager.canonicalIdentity(alice), gProtected.uuidString.uppercased()]
        XCTAssertFalse(AdvancedChatPrivacyPolicy.allowsExport(protected: true))
        let filtered = AdvancedChatPrivacyPolicy.filterForExport(snapshot) {
            protected.contains(AdvancedChatPrivacyStore.canonical($0))
        }
        XCTAssertEqual(filtered.direct.map(\.id), ["3"])
        XCTAssertEqual(filtered.groups.map { $0.text }, ["open-group"])

        let json = String(data: MessageExportV2.encode(filtered, now: now).data, encoding: .utf8) ?? ""
        XCTAssertFalse(json.contains("secret"), "a protected conversation reached the export")
        XCTAssertTrue(json.contains("open-bob"))
        XCTAssertTrue(json.contains("open-group"))
    }

    func testSavingMediaRules() {
        let p = AdvancedChatPrivacyPolicy.self
        XCTAssertTrue(p.allowsSavingMedia(protected: false, isFromMe: false))
        XCTAssertTrue(p.allowsSavingMedia(protected: true, isFromMe: true), "your own media stays yours")
        XCTAssertFalse(p.allowsSavingMedia(protected: true, isFromMe: false))
        XCTAssertFalse(p.allowsSavingMedia(protected: true, isFromMe: nil), "unknown sender = someone else")
        XCTAssertFalse(p.allowsAutoSave(protected: true))
        XCTAssertTrue(p.allowsForwarding(protected: true), "forwarding stays allowed")
    }

    // MARK: - Old clients

    func testOnlyPeersThatReadItAreSentIt() {
        let p = AdvancedChatPrivacyPolicy.self
        // iOS 1.0.43 advertises caps = 1 (resendList) and has never sent one.
        XCTAssertFalse(p.peerReads(accountCaps: 1, sentUsOne: false))
        // Builds from 2026-11-09 (padding, media padding) still do not know it.
        XCTAssertFalse(p.peerReads(accountCaps: 1 | 2 | 4 | 8, sentUsOne: false))
        XCTAssertTrue(p.peerReads(accountCaps: RatchetHealSignal.Caps.advancedPrivacy, sentUsOne: false))
        XCTAssertTrue(p.peerReads(accountCaps: 0, sentUsOne: true))
        XCTAssertEqual(RatchetHealSignal.Caps.advancedPrivacy, 16)
        XCTAssertTrue(RatchetHealSignal.Caps.has(RatchetHealSignal.Caps.mine, RatchetHealSignal.Caps.advancedPrivacy))
    }

    func testReadersRememberAPeerThatSentOne() {
        let suite = "advp.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        let saved = AdvancedChatPrivacyPolicy.Readers.defaults
        AdvancedChatPrivacyPolicy.Readers.defaults = defaults
        defer {
            AdvancedChatPrivacyPolicy.Readers.defaults = saved
            defaults.removePersistentDomain(forName: suite)
        }
        XCTAssertFalse(AdvancedChatPrivacyPolicy.Readers.contains(alice))
        AdvancedChatPrivacyPolicy.Readers.note(alice)
        XCTAssertTrue(AdvancedChatPrivacyPolicy.Readers.contains(alice))
        XCTAssertFalse(AdvancedChatPrivacyPolicy.Readers.contains(bob))
    }
}
