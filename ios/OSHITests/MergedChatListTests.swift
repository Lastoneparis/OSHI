import XCTest
@testable import OSHI

/// __MERGED_CHATS_2026_11_18__ The merged Messages + Groups list: order, chips,
/// unread counts, search across both kinds, the default, and "option OFF = the
/// old three segments". Mirrors `MergedChatListTest.kt` on Android.
final class MergedChatListTests: XCTestCase {

    private func item(_ id: String, _ kind: ChatListItem.Kind, _ title: String, _ t: TimeInterval,
                      unread: Int = 0) -> ChatListItem {
        ChatListItem(id: (kind == .group ? "g:" : "c:") + id, kind: kind, title: title,
                     date: Date(timeIntervalSince1970: t), unread: unread,
                     pinKey: kind == .group ? "group:" + id.lowercased() : id)
    }

    private lazy var items: [ChatListItem] = [
        item("alice", .person, "Alice", 500, unread: 2),
        item("bob", .person, "Bob", 100),
        item("lora-ch:0", .radioChannel, "LongFast", 300, unread: 1),
        item("g-1", .group, "Book Club", 400, unread: 3),
        item("g-2", .group, "Family", 200),
    ]

    private func titles(_ list: [ChatListItem]) -> [String] { list.map(\.title) }
    private let none: (String) -> Bool = { _ in false }

    func testOneChronologicalListAcrossBothKinds() {
        XCTAssertEqual(titles(ChatListMerge.arrange(items, isPinned: none, filter: .all, query: "")),
                       ["Alice", "Book Club", "LongFast", "Family", "Bob"])
    }

    func testPinnedRowsFirstForBothKindsKeepingRecency() {
        let pinned: Set<String> = ["bob", "group:g-2"]
        XCTAssertEqual(titles(ChatListMerge.arrange(items, isPinned: { pinned.contains($0) }, filter: .all, query: "")),
                       ["Family", "Bob", "Alice", "Book Club", "LongFast"])
    }

    func testChipsFilterByKindAndUnread() {
        XCTAssertEqual(titles(ChatListMerge.arrange(items, isPinned: none, filter: .unread, query: "")),
                       ["Alice", "Book Club", "LongFast"])
        // A LoRa channel is a room: Groups, not People.
        XCTAssertEqual(titles(ChatListMerge.arrange(items, isPinned: none, filter: .groups, query: "")),
                       ["Book Club", "LongFast", "Family"])
        XCTAssertEqual(titles(ChatListMerge.arrange(items, isPinned: none, filter: .people, query: "")),
                       ["Alice", "Bob"])
        XCTAssertEqual(ChatListFilter.allCases, [.all, .unread, .groups, .people])
    }

    /// __MERGED_GROUP_TYPES_2026_11_18__ The classic Groups segment's type chips,
    /// under the merged list's "Groups" chip; ignored by every other chip.
    func testGroupTypeSubFilterOnlyNarrowsTheGroupsChip() {
        var typed = items
        typed[3].groupType = .publicGroup      // Book Club
        typed[4].groupType = .adminOnly        // Family
        typed.append(ChatListItem(id: "g:g-3", kind: .group, title: "Team", date: Date(timeIntervalSince1970: 350),
                                  unread: 0, pinKey: "group:g-3", groupType: .collaborative))
        XCTAssertEqual(titles(ChatListMerge.arrange(typed, isPinned: none, filter: .groups, query: "")),
                       ["Book Club", "Team", "LongFast", "Family"])
        XCTAssertEqual(titles(ChatListMerge.arrange(typed, isPinned: none, filter: .groups, query: "", groupType: .adminOnly)),
                       ["Family"])
        XCTAssertEqual(titles(ChatListMerge.arrange(typed, isPinned: none, filter: .groups, query: "", groupType: .collaborative)),
                       ["Team"])
        XCTAssertEqual(titles(ChatListMerge.arrange(typed, isPinned: none, filter: .groups, query: "", groupType: .publicGroup)),
                       ["Book Club"])
        // Search still applies inside the sub-filter.
        XCTAssertEqual(titles(ChatListMerge.arrange(typed, isPinned: none, filter: .groups, query: "fam", groupType: .publicGroup)), [])
        // Any other chip ignores a remembered type.
        XCTAssertEqual(titles(ChatListMerge.arrange(typed, isPinned: none, filter: .all, query: "", groupType: .adminOnly)).count, 6)
        XCTAssertEqual(GroupType.allCases, [.adminOnly, .collaborative, .publicGroup])
    }

    func testUnreadCountsCountThreadsPerChip() {
        XCTAssertEqual(ChatListMerge.counts(items), .init(unread: 3, groups: 2, people: 1))
    }

    func testSearchCoversBothKindsAndIgnoresPins() {
        let pinned: Set<String> = ["bob"]
        XCTAssertEqual(titles(ChatListMerge.arrange(items, isPinned: { pinned.contains($0) }, filter: .all, query: "  bo ")),
                       ["Book Club", "Bob"])
        XCTAssertEqual(titles(ChatListMerge.arrange(items, isPinned: none, filter: .all, query: "FAM")), ["Family"])
        XCTAssertEqual(titles(ChatListMerge.arrange(items, isPinned: none, filter: .people, query: "bo")), ["Bob"])
        XCTAssertTrue(ChatListMerge.arrange(items, isPinned: none, filter: .all, query: "zzz").isEmpty)
    }

    func testEqualDatesHaveAStableOrder() {
        let tied = [item("k2", .person, "Two", 10), item("k1", .person, "One", 10), item("g", .group, "Grp", 10)]
        XCTAssertEqual(titles(ChatListMerge.arrange(tied, isPinned: none, filter: .all, query: "")),
                       titles(ChatListMerge.arrange(tied.reversed(), isPinned: none, filter: .all, query: "")))
    }

    func testGroupPinKeysCannotCollideWithContactKeys() {
        let id = UUID(uuidString: "9F1C2B3A-0000-4000-8000-00000000ABCD")!
        XCTAssertEqual(ChatListMerge.pinKey(forGroup: id), "group:9f1c2b3a-0000-4000-8000-00000000abcd")
        XCTAssertTrue(ChatListMerge.pinKey(forGroup: id).contains(":"))   // base64 never does
        // The pin store canonicalises keys; a group pin must survive it.
        let canon = ConversationPrefsStore.canonical(ChatListMerge.pinKey(forGroup: id))
        XCTAssertEqual(canon, ConversationPrefsStore.canonical(canon))
    }

    func testOptionOffKeepsTheThreeSegmentsAndTheirTags() {
        let off = ChatsSegments.segments(merged: false)
        XCTAssertEqual(off.map(\.segment), [.messages, .groups, .email])
        XCTAssertEqual(off.map(\.tag), [0, 1, 2])
        XCTAssertEqual(ChatsSegments.tag(for: .group, merged: false), 1)
        XCTAssertEqual(ChatsSegments.tag(for: .conversation, merged: false), 0)

        let on = ChatsSegments.segments(merged: true)
        XCTAssertEqual(on.map(\.segment), [.chats, .email])
        XCTAssertEqual(on.map(\.tag), [0, 2], "Email keeps tag 2 so mail deep links still land")
        XCTAssertEqual(ChatsSegments.tag(for: .group, merged: true), 0)
    }

    func testDefaultIsOnOnlyForAFreshInstall() {
        let suite = "MergedChatListTests.\(UUID().uuidString)"
        let fresh = UserDefaults(suiteName: suite)!
        MergedChatsSetting.settleDefaultAtLaunch(defaults: fresh, arguments: [])
        XCTAssertEqual(fresh.object(forKey: MergedChatsSetting.storageKey) as? Bool, true)

        let existing = UserDefaults(suiteName: suite + ".old")!
        existing.set(true, forKey: "hasSeenOnboarding")
        MergedChatsSetting.settleDefaultAtLaunch(defaults: existing, arguments: [])
        XCTAssertEqual(existing.object(forKey: MergedChatsSetting.storageKey) as? Bool, false)

        // Decided once: a later launch never flips it.
        existing.set(true, forKey: MergedChatsSetting.storageKey)
        MergedChatsSetting.settleDefaultAtLaunch(defaults: existing, arguments: [])
        XCTAssertEqual(existing.object(forKey: MergedChatsSetting.storageKey) as? Bool, true)

        // Screenshot / UI-test runs keep the classic layout unless they ask.
        let shots = UserDefaults(suiteName: suite + ".shots")!
        MergedChatsSetting.settleDefaultAtLaunch(defaults: shots, arguments: ["-OSHIScreens"])
        XCTAssertNil(shots.object(forKey: MergedChatsSetting.storageKey))

        for s in [suite, suite + ".old", suite + ".shots"] { UserDefaults().removePersistentDomain(forName: s) }
    }

    func testScanAnInviteRecognisesGroupLinksOnly() {
        XCTAssertNotNil(ChatListMerge.groupInviteURL(from: "oshi://group/join?gid=9F1C2B3A-0000-4000-8000-00000000ABCD"))
        XCTAssertNotNil(ChatListMerge.groupInviteURL(from: " https://oshi-messenger.com/group?gid=abc&name=x "))
        XCTAssertNotNil(ChatListMerge.groupInviteURL(from: "https://www.oshi-messenger.org/group/9F1C2B3A-0000-4000-8000-00000000ABCD"))
        XCTAssertNil(ChatListMerge.groupInviteURL(from: "https://oshi-messenger.org/add?key=abc"))
        XCTAssertNil(ChatListMerge.groupInviteURL(from: "oshi://add?key=abc"))
        XCTAssertNil(ChatListMerge.groupInviteURL(from: "https://evil.example/group?gid=x"))
        XCTAssertNil(ChatListMerge.groupInviteURL(from: "MCowBQYDK2VwAyEAabcdefghijklmnopqrstuvwxyz0123456789=="))
    }

    func testEveryLocaleCarriesEveryMergedKey() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("OSHI")
        let english = try String(contentsOf: root.appendingPathComponent("en.lproj/Localizable.strings"), encoding: .utf8)
        let keys = english.components(separatedBy: "\n")
            .compactMap { line -> String? in
                guard line.hasPrefix("\"chats.merged.") else { return nil }
                return String(line.dropFirst().prefix { $0 != "\"" })
            }
        XCTAssertEqual(keys.count, 25)
        let locales = try FileManager.default.contentsOfDirectory(atPath: root.path).filter { $0.hasSuffix(".lproj") && $0 != "Base.lproj" }
        XCTAssertGreaterThanOrEqual(locales.count, 34)
        for l in locales {
            let text = try String(contentsOf: root.appendingPathComponent("\(l)/Localizable.strings"), encoding: .utf8)
            for k in keys { XCTAssertTrue(text.contains("\"\(k)\" = \""), "\(l) misses \(k)") }
            if l != "en.lproj" {
                // Not English left behind (the brand-neutral "Chats" is allowed).
                XCTAssertFalse(text.contains("\"chats.merged.new_group_desc\" = \"Start a group with your contacts\""), l)
            }
        }
    }
}
