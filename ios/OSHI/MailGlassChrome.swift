//
//  MailGlassChrome.swift
//  OSHI
//
//  __MAIL_LIQUID_GLASS_2026_11_25__ The mail screens dressed like a group
//  conversation. Owner: « improve the design of the view email for iOS to be
//  more like group conversation with Liquid Glass and so on ».
//
//  What is shared, not copied:
//    • the floating header island (`HeaderIslandLabel` / `GroupHeaderIsland`,
//      GroupChatHeader.swift) — mailbox: « Mail / address »; a message:
//      « sender / subject »; the composer: « New message / from address »;
//    • the glass (`groupHeaderGlass`): Liquid Glass on iOS / Catalyst 26, the
//      app's glass card + soft shadow before;
//    • the composer pill and round buttons (`ComposerPill`,
//      `ComposerRoundButtonFace`, SharedUIComponents.swift) for the reader's
//      reply bar and the composer's attach / send bar;
//    • `DefaultAvatar` (AvatarIdentity.swift), keyed by the e-mail ADDRESS so a
//      sender keeps one colour across the list, the reader and the chips.
//
//  Nothing here talks to the server. The mail protocol, the HTML sanitising and
//  the remote-content blocking are untouched (MailView.swift).
//

import SwiftUI
import UIKit

// MARK: - Text

enum MailChromeText {
    static func replyTo(_ name: String) -> String {
        String(format: "mail.reply.to".localized, name)
    }
    static var reply: String { "mail.action.reply".localized }
    static var forward: String { "mail.action.forward".localized }
    static var move: String { "mail.action.move".localized }
    static var copyAddress: String { "mail.action.copyAddress".localized }
    static var newEmail: String { "mail.action.newEmail".localized }
    static var detailsHint: String { "mail.reader.detailsHint".localized }
    static var emptyFolder: String { "mail.empty.folder".localized }
    static var emptySearch: String { "mail.empty.search".localized }
    static var from: String { "mail.compose.from".localized }
    static func removeRecipient(_ address: String) -> String {
        String(format: "mail.compose.removeRecipient".localized, address)
    }
}

// MARK: - Avatar

/// A correspondent's disc. The colour follows the bare address (lower-cased),
/// the letters the display name — the rule `DefaultAvatar` applies to people.
struct MailSenderAvatar: View {
    let header: String
    var size: CGFloat = 40

    var body: some View {
        let address = MailMessageParser.emailAddress(from: header).lowercased()
        let name = MailMessageParser.displayName(from: header)
        DefaultAvatar(key: address.isEmpty ? header.lowercased() : address,
                      name: name.contains("@") ? String(name.prefix(1)) : name,
                      size: size)
            .accessibilityHidden(true)
    }
}

// MARK: - Glass surfaces

/// Liquid Glass on 26; the pre-26 look the mail chips already had otherwise.
/// `selected` chips stay SOLID brand with white ink on every OS: tinted glass
/// over a light list drops the label under 4.5:1.
private struct MailChipSurface: ViewModifier {
    let selected: Bool
    let tint: Color

    func body(content: Content) -> some View {
        if selected {
            content.background(tint, in: Capsule())
        } else if #available(iOS 26.0, macCatalyst 26.0, *) {
            content.glassEffect(.regular.interactive(), in: Capsule())
        } else {
            content.background(Color.secondary.opacity(0.12), in: Capsule())
        }
    }
}

extension View {
    func mailChipSurface(selected: Bool, tint: Color = Theme.brand) -> some View {
        modifier(MailChipSurface(selected: selected, tint: tint))
    }

    /// A rounded card on the grouped ground: the message header, the body, the
    /// composer's fields. Solid (it holds text to read), with the OLED glass
    /// edge when that theme is on.
    func mailCard(padding: CGFloat = 14) -> some View {
        let shape = RoundedRectangle(cornerRadius: 20, style: .continuous)
        return self
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: shape)
            .oledGlassEdge(shape)
    }
}

/// Holds sibling glass shapes so they blend like the system's (26 only).
struct MailGlassGroup<Content: View>: View {
    var spacing: CGFloat = 8
    @ViewBuilder let content: () -> Content

    var body: some View {
        if #available(iOS 26.0, macCatalyst 26.0, *) {
            GlassEffectContainer(spacing: spacing) { content() }
        } else {
            content()
        }
    }
}

// MARK: - Folder chips

struct MailFolderChip: View {
    let folder: MailFolder
    let count: Int
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Image(systemName: selected ? "\(folder.icon).fill" : folder.icon)
                    .accessibilityHidden(true)
                Text(folder.title)
                if count > 0 {
                    Text("\(count)")
                        .font(.caption2.bold().monospacedDigit())
                        .padding(.horizontal, 6).padding(.vertical, 1)
                        .background(selected ? Color.white.opacity(0.25) : Theme.brand.opacity(0.18),
                                    in: Capsule())
                }
            }
            .font(.subheadline.weight(selected ? .semibold : .medium))
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 14)
            .frame(minHeight: 36)
            .foregroundStyle(selected ? Color.white : Color.primary)
            .mailChipSurface(selected: selected)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(folder.title)
        .accessibilityValue(count > 0 ? "\(count)" : "")
        .accessibilityAddTraits(selected ? [.isSelected] : [])
        .accessibilityIdentifier("mail.folder.\(folder.rawValue)")
    }
}

// MARK: - Empty / offline states

/// A floating glass card in the middle of the screen: an icon in a soft disc,
/// a line of text, and optionally one action.
struct MailStateCard: View {
    let icon: String
    let message: String
    var actionTitle: String?
    var action: (() -> Void)?
    var identifier: String = "mail.state"

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: icon)
                .font(.title.weight(.medium))
                .foregroundStyle(MailMessageView.linkColor)
                .frame(width: 64, height: 64)
                .background(Theme.brand.opacity(0.12), in: Circle())
                .accessibilityHidden(true)
            Text(message)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            if let actionTitle, let action {
                MailProminentButton(title: actionTitle, action: action)
            }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 22)
        .frame(maxWidth: 360)
        .groupHeaderGlass(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(identifier)
    }
}

/// The brand action: glass-prominent on 26, bordered-prominent before.
struct MailProminentButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        if #available(iOS 26.0, macCatalyst 26.0, *) {
            Button(action: action) { Text(title).padding(.horizontal, 8) }
                .buttonStyle(.glassProminent)
                .tint(Theme.brand)
        } else {
            Button(action: action) { Text(title).padding(.horizontal, 8) }
                .buttonStyle(.borderedProminent)
                .tint(Theme.brand)
        }
    }
}

// MARK: - Reader bottom bar

/// The reader's bottom bar, built like the conversation composer: the pill is
/// « Reply to Léa » (tap = reply) with Forward inside it; the round buttons are
/// Move (a menu) and Delete. Same height, same 8-pt gaps, same glass bar.
struct MailReaderActionBar<MoveMenu: View>: View {
    let replyName: String
    let canReply: Bool
    let canForward: Bool
    let deleteLabel: String
    let onReply: () -> Void
    let onForward: () -> Void
    let onDelete: () -> Void
    @ViewBuilder let moveMenu: () -> MoveMenu

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            ComposerPill(focused: false, tint: Theme.brand) {
                Button(action: onReply) {
                    HStack(spacing: 10) {
                        Image(systemName: "arrowshape.turn.up.left")
                            .font(.body.weight(.medium))
                            .foregroundStyle(MailMessageView.linkColor)
                            .accessibilityHidden(true)
                        // « Reply to Léa », or just « Reply » when that does not fit.
                        ViewThatFits(in: .horizontal) {
                            Text(MailChromeText.replyTo(replyName)).lineLimit(1)
                            Text(MailChromeText.reply).lineLimit(1)
                        }
                        .font(.body)
                        .foregroundStyle(.secondary)
                        Spacer(minLength: 0)
                    }
                    .padding(.leading, 12)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!canReply)
                .accessibilityLabel(MailChromeText.replyTo(replyName))
                .accessibilityIdentifier("mail.reply")

                Button(action: onForward) {
                    Image(systemName: "arrowshape.turn.up.right")
                        .font(.body.weight(.medium))
                        .foregroundStyle(canForward ? Color.primary : Color.gray)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!canForward)
                .accessibilityLabel(MailChromeText.forward)
                .accessibilityIdentifier("mail.forward")
            }
            .layoutPriority(1)

            Menu { moveMenu() } label: {
                ComposerRoundButtonFace(systemImage: "folder", primary: false)
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
            .accessibilityLabel(MailChromeText.move)
            .accessibilityIdentifier("mail.move")

            Button(action: onDelete) {
                ComposerRoundButtonFace(systemImage: "trash", primary: false)
            }
            .buttonStyle(PressScaleButtonStyle())
            .accessibilityLabel(deleteLabel)
            .accessibilityIdentifier("mail.delete")
        }
        .padding(.horizontal)
        .padding(.top, 10)
        .padding(.bottom, 8)
        // Bar chrome stops growing at the largest non-accessibility size: three
        // round buttons at AX sizes left the pill two letters wide. The message
        // itself still follows every size.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .oledGlassBar(fallback: .ultraThinMaterial)
    }
}

// MARK: - Recipients

/// The « To » line as chips. `to` stays the single comma-separated string the
/// composer, the drafts and `sendMail` always used: every part but the last is
/// a chip, the last one is what is being typed. A comma, a space or Return
/// after something that looks like an address turns it into a chip.
struct MailRecipientField: View {
    @Binding var to: String
    var focus: FocusState<Bool>.Binding

    static func parts(_ to: String) -> [String] {
        to.split(separator: ",", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
    }

    /// Committed recipients (chips).
    static func chips(_ to: String) -> [String] {
        Array(parts(to).dropLast()).filter { !$0.isEmpty }
    }

    /// What is being typed.
    static func pending(_ to: String) -> String {
        parts(to).last ?? ""
    }

    /// Rebuilds `to` from chips + the text being typed.
    static func join(_ chips: [String], pending: String) -> String {
        chips.isEmpty ? pending : chips.joined(separator: ", ") + ", " + pending
    }

    /// Every recipient, chips and the pending text alike (what Send uses).
    static func recipients(_ to: String) -> [String] {
        parts(to).filter { !$0.isEmpty }
    }

    static func looksLikeAddress(_ s: String) -> Bool {
        let address = MailMessageParser.emailAddress(from: s)
        guard let at = address.firstIndex(of: "@") else { return false }
        return at > address.startIndex && address[address.index(after: at)...].contains(".")
    }

    private var pendingBinding: Binding<String> {
        Binding(
            get: { Self.pending(to) },
            set: { new in
                let chips = Self.chips(to)
                if let last = new.last, last == "," || last == ";" || last == " " {
                    let candidate = String(new.dropLast()).trimmingCharacters(in: .whitespaces)
                    if Self.looksLikeAddress(candidate) {
                        to = Self.join(chips + [candidate], pending: "")
                        return
                    }
                    if last != " " {
                        to = Self.join(chips, pending: candidate)
                        return
                    }
                }
                to = Self.join(chips, pending: new)
            })
    }

    var body: some View {
        MailChipFlowLayout(spacing: 6) {
            Text("mail.compose.to".localized)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(minHeight: 32)
                .accessibilityHidden(true)
            ForEach(Array(Self.chips(to).enumerated()), id: \.offset) { index, address in
                MailRecipientChip(address: address) {
                    var chips = Self.chips(to)
                    guard index < chips.count else { return }
                    chips.remove(at: index)
                    to = Self.join(chips, pending: Self.pending(to))
                }
            }
            TextField("", text: pendingBinding)
                .font(.body)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.emailAddress)
                .textContentType(.emailAddress)
                .submitLabel(.next)
                .focused(focus)
                .onSubmit {
                    let p = Self.pending(to)
                    if Self.looksLikeAddress(p) { to = Self.join(Self.chips(to) + [p], pending: "") }
                }
                .frame(minWidth: 120, minHeight: 32)
                .accessibilityLabel("mail.compose.to".localized)
                .accessibilityIdentifier("mail.compose.to")
        }
    }
}

struct MailRecipientChip: View {
    let address: String
    let onRemove: () -> Void

    var body: some View {
        let valid = MailRecipientField.looksLikeAddress(address)
        HStack(spacing: 6) {
            MailSenderAvatar(header: address, size: 22)
            Text(MailMessageParser.displayName(from: address))
                .font(.subheadline)
                .lineLimit(1)
                .truncationMode(.middle)
            Button(action: onRemove) {
                Image(systemName: "xmark.circle.fill")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(minWidth: 24, minHeight: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(MailChromeText.removeRecipient(address))
        }
        .padding(.leading, 5)
        .padding(.trailing, 6)
        .frame(minHeight: 32)
        .background(Theme.brand.opacity(valid ? 0.12 : 0.0), in: Capsule())
        .overlay(Capsule().strokeBorder(valid ? Color.clear : Color.red.opacity(0.8), lineWidth: 1))
        .accessibilityElement(children: .contain)
    }
}

/// Left-to-right wrapping rows (mirrored by the system in RTL).
struct MailChipFlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        let rows = arrange(width: width, subviews: subviews)
        let height = rows.reduce(0) { $0 + $1.height } + spacing * CGFloat(max(rows.count - 1, 0))
        let used = rows.map(\.width).max() ?? 0
        return CGSize(width: proposal.width ?? used, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for item in row.items {
                let size = row.sizes[item] ?? .zero
                // The text field (the last subview) takes what is left of its row.
                let w = item == subviews.count - 1 ? max(bounds.maxX - x, size.width) : size.width
                let h = size.height
                subviews[item].place(at: CGPoint(x: x, y: y + (row.height - h) / 2),
                                     proposal: ProposedViewSize(width: w, height: h))
                x += w + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row: Equatable {
        var items: [Int] = []
        var sizes: [Int: CGSize] = [:]
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = [Row()]
        for i in subviews.indices {
            var size = subviews[i].sizeThatFits(.unspecified)
            size.width = min(size.width, width)
            let needed = rows[rows.count - 1].items.isEmpty ? size.width : rows[rows.count - 1].width + spacing + size.width
            if needed > width, !rows[rows.count - 1].items.isEmpty {
                rows.append(Row())
            }
            var row = rows[rows.count - 1]
            row.width = row.items.isEmpty ? size.width : row.width + spacing + size.width
            row.height = max(row.height, size.height)
            row.items.append(i)
            row.sizes[i] = size
            rows[rows.count - 1] = row
        }
        return rows
    }
}
