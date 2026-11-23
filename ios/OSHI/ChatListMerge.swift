//
//  ChatListMerge.swift
//  OSHI
//
//  __MERGED_CHATS_2026_11_18__ « Fusionner Messages et Groupes ».
//
//  An OPTION (Settings › Appearance) that replaces the "Messages" and
//  "Groups" segments of the Chats tab by one chronological list with filter
//  chips — All · Unread · Groups · People. Off, the tab is exactly what it was.
//
//  This file holds what is worth testing, free of SwiftUI and of the model
//  types' construction rules: the list turns its `Conversation`s and
//  `MessageGroup`s into `ChatListItem`s, `ChatListMerge.arrange` decides what
//  is shown and in which order, and the view maps each item back by `id`.
//  Mirrors `ChatListMerge.kt` on Android, rule for rule.
//

import Foundation

/// The chips above the merged list, in display order.
enum ChatListFilter: String, CaseIterable, Identifiable {
    case all, unread, groups, people
    var id: String { rawValue }

    var titleKey: String {
        switch self {
        case .all: return "chats.merged.filter_all"
        case .unread: return "chats.merged.filter_unread"
        case .groups: return "chats.merged.filter_groups"
        case .people: return "chats.merged.filter_people"
        }
    }
}

struct ChatListItem: Identifiable, Equatable {
    /// What a row IS. A LoRa channel is a room, so it files under Groups.
    enum Kind: Equatable { case person, group, radioChannel }

    /// `c:<public key>` for a 1:1 / radio thread, `g:<group uuid>` for a group.
    let id: String
    let kind: Kind
    /// The name shown on the row — what the search field matches.
    let title: String
    let date: Date
    let unread: Int
    /// The key this row is pinned under in `ConversationPrefsStore`.
    let pinKey: String

    /// __MERGED_GROUP_TYPES_2026_11_18__ A group's type (admin-only,
    /// collaborative, public) for the sub-filter under the "Groups" chip — the
    /// filters the classic Groups segment had. nil for people and LoRa channels.
    var groupType: GroupType? = nil

    var isGroupLike: Bool { kind != .person }
}

enum ChatListMerge {
    static let conversationPrefix = "c:"
    static let groupPrefix = "g:"

    /// Groups are pinned in the same store as 1:1 conversations, under a
    /// prefix no public key can carry (base64 has no ':'), so a pin can never
    /// land on the wrong row and the existing 1:1 pins keep working.
    static func pinKey(forGroup id: UUID) -> String { "group:" + id.uuidString.lowercased() }

    struct Counts: Equatable {
        /// Threads with something unread, per chip.
        let unread: Int
        let groups: Int
        let people: Int
    }

    static func matches(_ item: ChatListItem, _ filter: ChatListFilter) -> Bool {
        switch filter {
        case .all: return true
        case .unread: return item.unread > 0
        case .groups: return item.isGroupLike
        case .people: return item.kind == .person
        }
    }

    /// What the merged list shows, in order:
    ///  • newest activity first across both kinds, ties broken by id so two
    ///    rows of the same second never swap between two renders;
    ///  • pinned rows first, each half still newest-first — but not while
    ///    searching, where recency is the useful order (as in the Messages
    ///    search today);
    ///  • the chip, then the search, which matches the shown name.
    ///  • under the "Groups" chip, the optional group-type sub-filter
    ///    (`groupType`, ignored by every other chip) keeps only groups of that
    ///    type — exactly the classic Groups segment's Admin-only /
    ///    Collaborative / Public chips.
    static func arrange(_ items: [ChatListItem],
                        isPinned: (String) -> Bool,
                        filter: ChatListFilter,
                        query: String,
                        groupType: GroupType? = nil) -> [ChatListItem] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let typeFilter = filter == .groups ? groupType : nil
        let chosen = items
            .filter { matches($0, filter) }
            .filter { typeFilter == nil || $0.groupType == typeFilter }
            .filter { q.isEmpty || $0.title.localizedCaseInsensitiveContains(q) }
            .sorted { a, b in a.date != b.date ? a.date > b.date : a.id < b.id }
        guard q.isEmpty else { return chosen }
        return chosen.filter { isPinned($0.pinKey) } + chosen.filter { !isPinned($0.pinKey) }
    }

    /// "Scan an invite": the URL to hand to the app's own deep-link handler
    /// when a scanned code is a GROUP invite (`oshi://group/…` or
    /// `https://oshi-messenger.{com,org}/group…`), nil for anything else —
    /// which then goes down the contact path exactly as before.
    static func groupInviteURL(from raw: String) -> URL? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), let scheme = url.scheme?.lowercased() else { return nil }
        switch scheme {
        case "oshi":
            return url.host?.lowercased() == "group" ? url : nil
        case "https":
            guard let host = url.host?.lowercased(),
                  ["oshi-messenger.com", "oshi-messenger.org", "www.oshi-messenger.com", "www.oshi-messenger.org"].contains(host),
                  url.pathComponents.filter({ $0 != "/" }).first?.lowercased() == "group" else { return nil }
            return url
        default:
            return nil
        }
    }

    static func counts(_ items: [ChatListItem]) -> Counts {
        Counts(unread: items.filter { $0.unread > 0 }.count,
               groups: items.filter { $0.isGroupLike && $0.unread > 0 }.count,
               people: items.filter { $0.kind == .person && $0.unread > 0 }.count)
    }
}

/// The Chats tab's segments. OFF is exactly the three segments the app always
/// had, in the same order and with the same tags (0, 1, 2) — the option must
/// not move anything for someone who leaves it alone. ON keeps tag 0 for the
/// merged list and tag 2 for Email, so a deep link that selects "2" still
/// lands on the mailbox.
enum ChatsSegments {
    enum Segment: Equatable { case messages, groups, chats, email }

    static func segments(merged: Bool) -> [(segment: Segment, tag: Int)] {
        merged ? [(.chats, 0), (.email, 2)]
               : [(.messages, 0), (.groups, 1), (.email, 2)]
    }

    /// The segment that owns a thread of this kind.
    static func tag(for kind: ThreadRoute.Kind, merged: Bool) -> Int {
        merged ? 0 : (kind == .group ? 1 : 0)
    }
}

/// The persisted choice, per device (`UserDefaults`, like every Appearance
/// setting).
///
/// DEFAULT: OFF for everybody who already uses the app — an update must never
/// rearrange the screen they open twenty times a day. ON for a FRESH install
/// only: someone new has no habit to break, gets the layout every current
/// messenger uses, and sees "Discover public channels" on the first screen
/// instead of behind a second segment. Decided once, at the first launch that
/// knows about the option, and written down so it never flips afterwards.
enum MergedChatsSetting {
    static let storageKey = "chatsMergedEnabled"

    /// Anything an earlier launch leaves behind means this is not a new user.
    static let priorUseMarkers = ["hasSeenOnboarding", "RateApp_FirstLaunchDate", "contactAliases", "appTheme"]

    static func defaultValue(existingUser: Bool) -> Bool { !existingUser }

    /// Called once per launch, before any screen. Screenshot / UI-test runs
    /// (`-OSHIScreens`) are left alone: they keep the classic layout unless
    /// they pass `-chatsMergedEnabled YES` themselves.
    static func settleDefaultAtLaunch(defaults: UserDefaults = .standard,
                                      arguments: [String] = ProcessInfo.processInfo.arguments) {
        guard defaults.object(forKey: storageKey) == nil else { return }
        if arguments.contains("-OSHIScreens") { return }
        let existing = priorUseMarkers.contains { defaults.object(forKey: $0) != nil }
        defaults.set(defaultValue(existingUser: existing), forKey: storageKey)
    }
}
