//
//  AttachmentPanelLogic.swift
//  OSHI
//
//  __ATTACH_PANEL_2027_2026_11_17__ The pure half of the attachment panel —
//  the iOS twin of Android's `AttachmentPanelLogic.kt`. No PhotoKit and no
//  SwiftUI here: every rule the panel follows (ordered selection, numbered
//  badges, the cap, which permission card to show, which option tiles a chat
//  offers, where the caption goes) is pinned by `AttachmentPanelLogicTests`.
//

import Foundation

/// How much of the photo library the app may read — the panel's three faces.
enum GalleryAccess: Equatable {
    /// Everything. The recent grid shows the whole library.
    case full
    /// iOS 14+ "Limit Access": only what the user picked. The grid shows those,
    /// under a "You've shared only some photos" banner with Manage.
    case limited
    /// Never asked. An explanation card with "Allow access" — and "Choose from
    /// library", the system picker, which needs no permission at all.
    case notDetermined
    /// Refused (or restricted by parental controls / MDM). Asking again shows
    /// nothing, so the button opens Settings instead of doing nothing.
    case denied

    /// Whether thumbnails can be read at all.
    var canRead: Bool { self == .full || self == .limited }

    /// From `PHAuthorizationStatus.rawValue` (0 notDetermined, 1 restricted,
    /// 2 denied, 3 authorized, 4 limited). Raw so this file stays PhotoKit-free;
    /// an unknown future case is treated as "not readable, ask".
    init(photoKitRawStatus raw: Int) {
        switch raw {
        case 3: self = .full
        case 4: self = .limited
        case 1, 2: self = .denied
        default: self = .notDetermined
        }
    }
}

/// What the permission area shows, decided from the access level alone.
enum GalleryPermissionCard: Equatable {
    /// No card: the grid.
    case none
    /// Grid, under the limited-access banner.
    case limitedBanner
    /// "See your recent photos here" + Allow access + Choose from library.
    case askAccess
    /// Same explanation, but the primary button opens Settings.
    case openSettings

    init(_ access: GalleryAccess) {
        switch access {
        case .full: self = .none
        case .limited: self = .limitedBanner
        case .notDetermined: self = .askAccess
        case .denied: self = .openSettings
        }
    }
}

enum AttachToggleOutcome: Equatable { case added, removed, limit }

/// One tile of the option row. The SAME actions the "+" menu had; which ones a
/// chat shows is `AttachmentPanelLogic.options(for:)`.
enum AttachOption: String, CaseIterable, Identifiable, Equatable {
    case gallery, camera, file, gif, location, contact, poll, checkIn
    case videoNote, cashu, thinkingOfYou, scheduleCall
    /// STEGO-v1 "Disguise as photo" — 1:1 only (the image is encrypted for ONE contact).
    case disguisePhoto
    var id: String { rawValue }
}

/// The two chat kinds the panel serves.
enum AttachChatKind: Equatable {
    /// 1:1. `cashu` is optional because the 1:1 composer only offers it when the
    /// conversation can receive it (`onCashuTap` non-nil).
    case direct(cashu: Bool)
    case group
}

enum AttachmentPanelLogic {

    /// Pick at most this many at once — the same cap as the system picker the
    /// app already opens (`maxSelectionCount: 10`) and as Android.
    static let maxSelection = 10

    /// Thumbnails materialised per page. A screenful is ~15–20.
    static let pageSize = 90

    /// Grow the window when the user is this close to the end of what is shown.
    static let prefetchDistance = 30

    /// Toggle `id` in an ORDERED selection. The order is the send order and the
    /// number on each badge, so removing #2 of 4 renumbers the last two —
    /// exactly what a numbered badge promises. A pick past `max` is refused.
    static func toggle(_ selection: [String], _ id: String,
                       max: Int = maxSelection) -> (selection: [String], outcome: AttachToggleOutcome) {
        if let i = selection.firstIndex(of: id) {
            var s = selection
            s.remove(at: i)
            return (s, .removed)
        }
        if selection.count >= max { return (selection, .limit) }
        return (selection + [id], .added)
    }

    /// 1-based badge number of `id`, or nil when it is not selected.
    static func badgeNumber(_ selection: [String], _ id: String) -> Int? {
        selection.firstIndex(of: id).map { $0 + 1 }
    }

    /// Keep only ids still present — an item deleted from the library, or
    /// removed from a limited selection, while the panel was open.
    static func prune(_ selection: [String], present: Set<String>) -> [String] {
        selection.filter { present.contains($0) }
    }

    /// `0:07`, `3:45`, `1:02:03` — the duration chip on a video tile.
    static func formatDuration(_ seconds: Double) -> String {
        let total = Int((max(0, seconds.isFinite ? seconds : 0)).rounded())
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    /// Whether the visible window should grow, given the index of a tile that
    /// just appeared and how many are materialised out of `total`.
    static func shouldLoadMore(appearedIndex: Int, loaded: Int, total: Int) -> Bool {
        loaded < total && loaded > 0 && appearedIndex >= loaded - prefetchDistance
    }

    /// The next window size after a load-more.
    static func nextWindow(loaded: Int, total: Int) -> Int {
        min(total, loaded + pageSize)
    }

    /// The caption for each of N outgoing items. ONE caption field for the whole
    /// pick; it rides the FIRST item (an album caption, as every messenger shows
    /// it) and the rest go bare. Blank ⇒ no caption at all.
    static func captions(count: Int, typed: String) -> [String] {
        let c = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        return (0..<max(0, count)).map { $0 == 0 ? c : "" }
    }

    /// The option row for a chat, in display order. 1:1 and group list the same
    /// tiles in the same order; a group adds "Schedule a call" (1:1 has calls in
    /// its nav bar), and 1:1 shows Cashu only when its composer offers it.
    static func options(for kind: AttachChatKind) -> [AttachOption] {
        var o: [AttachOption] = [.gallery, .camera, .file, .gif, .location, .contact,
                                 .poll, .checkIn, .videoNote]
        switch kind {
        case .direct(let cashu):
            if cashu { o.append(.cashu) }
            o.append(.thinkingOfYou)
            o.append(.disguisePhoto)
        case .group:
            o.append(.cashu)
            o.append(.thinkingOfYou)
            o.append(.scheduleCall)
        }
        return o
    }

    /// View once / gradual reveal are 1:1 features (the group wire has neither).
    static func supportsSendOptions(_ kind: AttachChatKind) -> Bool {
        if case .direct = kind { return true }
        return false
    }

    /// Gradual reveal layers ONE photo; it is offered only for a single image.
    static func revealAvailable(selectedCount: Int, firstIsPhoto: Bool) -> Bool {
        selectedCount == 1 && firstIsPhoto
    }

    /// The header title: "Recent" until something is picked, then "N selected".
    static func headerIsSelection(_ count: Int) -> Bool { count > 0 }
}
