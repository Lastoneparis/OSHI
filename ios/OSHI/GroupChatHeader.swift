//
//  GroupChatHeader.swift
//  OSHI
//
//  __GROUP_HEADER_ISLAND_2026_11_24__ The group conversation's top bar.
//
//  « Keep the arrow we already have on the left, then the loupe, but make a
//  little island / cloud floating effect with name and number of members, and
//  on the right, instead of the button with the 3 dots, put the picture of the
//  group, clickable. »
//
//      (‹)  (🔍)      ( Swiss-Quant.CH 🇨🇭 )      (avatar)
//                     (    30 subscribers   )
//
//  The back button is the system one (a glass circle on iOS 26), the search
//  button a leading toolbar item, the island the principal item and the
//  avatar the trailing one. SwiftUI mirrors the toolbar in RTL by itself.
//
//  The ⋯ menu is gone from the bar. Its items moved into the group info sheet
//  (`GroupInfoChatActions`), and a LONG-PRESS on the avatar still opens the
//  very same menu: the avatar is covered by a clear UIButton whose tap opens
//  the info and whose context menu is the old `GroupOverflowMenu` sections —
//  UIKit-owned for the same reason as `StableMenuButton` (the toolbar is
//  re-evaluated on every typing tick; a SwiftUI `Menu` would be rebuilt under
//  the finger).
//

import SwiftUI
import UIKit

// MARK: - Text

enum GroupHeaderText {
    /// "30 members" / "30 subscribers" (admin-only groups read like a channel).
    /// Read straight from the bundle, not `.localized`: `MacVerbs` may rewrite
    /// the string on Catalyst, which would drop the plural rules attached to it.
    static func count(_ n: Int, channel: Bool) -> String {
        let key = channel ? "group_header_subscribers" : "group_header_members"
        let fallback = channel ? "%d subscribers" : "%d members"
        let fmt = Bundle.main.localizedString(forKey: key, value: fallback, table: nil)
        return String.localizedStringWithFormat(fmt, n)
    }

    static func infoLabel(_ name: String) -> String {
        String(format: "group_header.info_a11y".localized, name)
    }

    static var searchLabel: String { "group_header.search_a11y".localized }
}

// MARK: - Glass

/// Liquid Glass on iOS 26 / Catalyst 26; before that, the app's own glass card
/// (OLED / classic glass when « Effet verre » is on, a material otherwise) and a
/// soft shadow so the island floats.
private struct GroupHeaderGlass<S: Shape>: ViewModifier {
    let shape: S
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        if #available(iOS 26.0, macCatalyst 26.0, *) {
            content.glassEffect(.regular.interactive(), in: shape)
        } else {
            content
                .oledGlassCard(shape, fallback: Material.regularMaterial, blur: true)
                .overlay(shape.stroke(Color.primary.opacity(scheme == .dark ? 0.10 : 0.06), lineWidth: 0.5)
                            .allowsHitTesting(false))
                .shadow(color: .black.opacity(scheme == .dark ? 0.30 : 0.10), radius: 8, x: 0, y: 3)
        }
    }
}

extension View {
    func groupHeaderGlass<S: Shape>(_ shape: S) -> some View {
        modifier(GroupHeaderGlass(shape: shape))
    }
}

// MARK: - Island

/// The capsule itself, without the tap: shared with the mail screens
/// (`MailGlassChrome.swift`), which use it as a plain title or inside a menu.
struct HeaderIslandLabel: View {
    let name: String
    let subtitle: String
    var subtitleIsActivity = false
    var muted = false
    var activityColor: Color = Theme.brand

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                Text(name)
                    .font(.headline)
                    .foregroundColor(.primary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                if muted {
                    Image(systemName: "bell.slash.fill")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .accessibilityHidden(true)
                }
            }
            if !subtitle.isEmpty {
                Text(subtitle)
                    .font(.caption)
                    .foregroundColor(subtitleIsActivity ? activityColor : .secondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 5)
        .frame(minWidth: 96, minHeight: 44)
        .contentShape(Capsule())
        .groupHeaderGlass(Capsule())
    }
}

/// The floating capsule in the middle: name (bold, one line) over the count or
/// the typing line. Tap = group info. The mail screens pass their own spoken
/// label / hint / identifier.
struct GroupHeaderIsland: View {
    let name: String
    let subtitle: String
    var subtitleIsActivity = false
    var muted = false
    var activityColor: Color = Theme.brand
    var accessibilityLabelText: String? = nil
    var accessibilityHintText: String? = nil
    var identifier = "group_header_island"
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HeaderIslandLabel(name: name, subtitle: subtitle,
                              subtitleIsActivity: subtitleIsActivity,
                              muted: muted, activityColor: activityColor)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabelText ?? GroupHeaderText.infoLabel(name))
        .accessibilityValue(subtitle)
        .accessibilityHint(accessibilityHintText ?? "")
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier(identifier)
    }
}

// MARK: - Avatar

/// The group's picture where the ⋯ was. [avatar] is drawn by the caller (the
/// picture cache is private to GroupViews); tap = info, long-press = the old menu.
struct GroupHeaderAvatarButton<Avatar: View>: View {
    let name: String
    let sections: [[OverflowMenuItem]]
    let onTap: () -> Void
    let onSelect: (String) -> Void
    var accessibilityLabelText: String? = nil
    var identifier = "group_header_avatar"
    @ViewBuilder let avatar: () -> Avatar

    /// 44 pt: the toolbar's own button size, the minimum touch target.
    static var size: CGFloat { 44 }

    var body: some View {
        ZStack {
            if #available(iOS 26.0, macCatalyst 26.0, *) {
                // The toolbar wraps the item in its own glass; the picture sits inside it.
                avatar().frame(width: 38, height: 38).clipShape(Circle())
            } else {
                avatar().frame(width: 38, height: 38).clipShape(Circle())
                    .padding(3)
                    .groupHeaderGlass(Circle())
            }
            AvatarMenuOverlay(sections: sections,
                              label: accessibilityLabelText ?? GroupHeaderText.infoLabel(name),
                              identifier: identifier,
                              onTap: onTap,
                              onSelect: onSelect)
        }
        .frame(width: Self.size, height: Self.size)
        .accessibilityElement(children: .contain)
    }
}

/// A clear UIButton over the avatar: tap → [onTap]; long-press (right-click on
/// the Mac) → the menu. The menu is rebuilt only when what it shows changes.
private struct AvatarMenuOverlay: UIViewRepresentable {
    let sections: [[OverflowMenuItem]]
    let label: String
    var identifier = "group_header_avatar"
    let onTap: () -> Void
    let onSelect: (String) -> Void

    final class Coordinator: NSObject {
        var onTap: () -> Void = {}
        var onSelect: (String) -> Void = { _ in }
        var shown: [[OverflowMenuItem]] = []
        @objc func tapped() { onTap() }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIButton {
        let button = UIButton(type: .custom)
        button.backgroundColor = .clear
        button.showsMenuAsPrimaryAction = false   // tap = info, long-press = menu
        button.accessibilityIdentifier = identifier
        button.accessibilityLabel = label
        button.accessibilityTraits = .button
        button.accessibilityHint = "a11y.more_options".localized
        button.addTarget(context.coordinator, action: #selector(Coordinator.tapped), for: .touchUpInside)
        context.coordinator.onTap = onTap
        context.coordinator.onSelect = onSelect
        context.coordinator.shown = sections
        button.menu = Self.menu(sections, coordinator: context.coordinator)
        return button
    }

    func updateUIView(_ button: UIButton, context: Context) {
        context.coordinator.onTap = onTap
        context.coordinator.onSelect = onSelect
        if button.accessibilityLabel != label { button.accessibilityLabel = label }
        guard context.coordinator.shown != sections else { return }
        context.coordinator.shown = sections
        button.menu = Self.menu(sections, coordinator: context.coordinator)
    }

    private static func menu(_ sections: [[OverflowMenuItem]], coordinator: Coordinator) -> UIMenu {
        UIMenu(children: sections.map { section in
            UIMenu(options: .displayInline, children: section.map { item in
                UIAction(title: item.title,
                         image: UIImage(systemName: item.symbol),
                         attributes: item.destructive ? .destructive : []) { [weak coordinator] _ in
                    coordinator?.onSelect(item.id)
                }
            })
        })
    }
}

// MARK: - What the ⋯ menu held, in the info sheet

/// The old ⋯ menu's chat-level actions, handed to `GroupInfoView` by the chat
/// that presents it. Each closure closes the sheet first where it opens another.
struct GroupInfoChatActions {
    var canAddMembers: Bool
    var blocked: Bool
    var addMember: () -> Void
    var openWallpaper: () -> Void
    var toggleMute: () -> Void
    var report: () -> Void
    var block: () -> Void
    var unblock: () -> Void
}

/// The section `GroupInfoView` shows when it was opened from a group chat.
struct GroupInfoChatActionsSection: View {
    let actions: GroupInfoChatActions
    let muted: Bool

    var body: some View {
        Section {
            if actions.canAddMembers {
                Button(action: actions.addMember) {
                    Label("menu_add_member".localized, systemImage: "person.badge.plus")
                }
            }
            Button(action: actions.openWallpaper) {
                Label("wallpaper.title".localized, systemImage: "photo.artframe")
            }
            Button(action: actions.toggleMute) {
                Label(muted ? "group.unmute".localized : "group.mute".localized,
                      systemImage: muted ? "bell.fill" : "bell.slash.fill")
            }
            .accessibilityIdentifier("group_info_mute")
        }
        Section {
            Button(role: .destructive, action: actions.report) {
                Label("group.report_group".localized, systemImage: "flag.fill")
            }
            if actions.blocked {
                Button(action: actions.unblock) {
                    Label("group.unblock".localized, systemImage: "hand.raised.slash")
                }
            } else {
                Button(role: .destructive, action: actions.block) {
                    Label("group.block".localized, systemImage: "hand.raised.fill")
                }
            }
        }
    }
}
