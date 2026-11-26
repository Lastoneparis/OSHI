//
//  AdvancedChatPrivacyViews.swift
//  OSHI
//
//  __ADVANCED_CHAT_PRIVACY_2026_11_26__ The visible half of advanced chat
//  privacy: the centred notice in the thread, the "Learn more" sheet, the menu
//  row, and the capture shield. The rules live in AdvancedChatPrivacy.swift.
//

import SwiftUI
import UIKit

// MARK: - Strings

enum AdvancedPrivacyText {
    /// The thread notice. `actorName == nil` means "you".
    static func notice(on: Bool, actorName: String?) -> String {
        if let actorName {
            return String(format: (on ? "adv_privacy.notice.on.other" : "adv_privacy.notice.off.other").localized,
                          actorName)
        }
        return (on ? "adv_privacy.notice.on.you" : "adv_privacy.notice.off.you").localized
    }
}

// MARK: - Environment

/// What a view inside a conversation needs to know about the switch.
struct AdvancedChatPrivacyContext {
    var isOn: Bool = false
    /// Flips the switch for this conversation; nil outside a conversation.
    var toggle: (() -> Void)? = nil
}

private struct AdvancedChatPrivacyKey: EnvironmentKey {
    static let defaultValue = AdvancedChatPrivacyContext()
}

extension EnvironmentValues {
    var advancedChatPrivacy: AdvancedChatPrivacyContext {
        get { self[AdvancedChatPrivacyKey.self] }
        set { self[AdvancedChatPrivacyKey.self] = newValue }
    }
}

extension View {
    /// Marks this subtree as one conversation's thread: publishes the switch to
    /// the media viewers and notice rows, and shields the screen when it is on.
    func advancedChatPrivacy(isOn: Bool, toggle: (() -> Void)?) -> some View {
        modifier(AdvancedChatPrivacyShield(isOn: isOn, toggle: toggle))
    }
}

/// The capture shield.
///
/// iOS already keeps every OSHI window out of screenshots and recordings with the
/// secure-layer trick (`secureScreen()`, always on). That trick works on the key
/// window it was installed on; this adds, for a protected chat only, a visible
/// cover whenever iOS reports the screen as CAPTURED (recording, AirPlay / cable
/// mirroring, QuickTime) — so the thread is unreadable on the other screen too,
/// and the person is told why. On the Mac, `MacCaptureShield` (sharingType =
/// none) is re-applied to every window when a protected chat appears, so a
/// window opened after launch is covered as well.
private struct AdvancedChatPrivacyShield: ViewModifier {
    let isOn: Bool
    let toggle: (() -> Void)?
    @State private var captured = false

    func body(content: Content) -> some View {
        content
            .environment(\.advancedChatPrivacy, AdvancedChatPrivacyContext(isOn: isOn, toggle: toggle))
            .overlay {
                if isOn && captured {
                    AdvancedPrivacyCaptureCover()
                        .transition(.opacity)
                }
            }
            .onAppear { refresh() }
            .onChange(of: isOn) { _, _ in refresh() }
            .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
                refresh()
            }
    }

    private func refresh() {
        #if targetEnvironment(macCatalyst)
        if isOn { MacCaptureShield.shared.applyToAllWindows() }
        captured = false
        #else
        captured = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .contains { $0.activationState == .foregroundActive && $0.screen.isCaptured }
        #endif
    }
}

private struct AdvancedPrivacyCaptureCover: View {
    var body: some View {
        ZStack {
            Rectangle().fill(.ultraThickMaterial).ignoresSafeArea()
            VStack(spacing: 14) {
                Image(systemName: "eye.slash.fill")
                    .font(.system(size: 40))
                    .foregroundColor(.secondary)
                    .accessibilityHidden(true)
                Text("adv_privacy.capture.title".localized)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                Text("adv_privacy.capture.body".localized)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(32)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("advPrivacy.captureCover")
    }
}

// MARK: - Menu row

enum AdvancedChatPrivacyMenu {
    static let itemId = "advPrivacy"

    /// A toggle row: checkmark when on, the explanation as its second line.
    static func item(isOn: Bool) -> OverflowMenuItem {
        OverflowMenuItem(id: itemId,
                         title: "adv_privacy.title".localized,
                         symbol: isOn ? "lock.shield.fill" : "lock.shield",
                         subtitle: "adv_privacy.menu.subtitle".localized,
                         isOn: isOn)
    }
}

// MARK: - Glass

/// Liquid Glass on iOS 26 / Catalyst 26, the app's material before that.
private struct AdvancedPrivacyGlass<S: Shape>: ViewModifier {
    let shape: S
    func body(content: Content) -> some View {
        if #available(iOS 26.0, macCatalyst 26.0, *) {
            content.glassEffect(.regular, in: shape)
        } else {
            content.background(shape.fill(.regularMaterial))
        }
    }
}

// MARK: - Thread notice

/// "Hugo turned on advanced chat privacy. No one can auto-save media, export the
/// chat, or use AI on messages in this chat. Learn more" — centred, not a bubble:
/// it is the conversation announcing a rule, not something someone said.
struct AdvancedPrivacyNoticeRow: View {
    let change: AdvancedPrivacyChange
    /// nil = this device's user.
    let actorName: String?
    let timestamp: Date

    @Environment(\.advancedChatPrivacy) private var context
    @State private var showInfo = false

    var body: some View {
        HStack {
            Spacer(minLength: 24)
            VStack(spacing: 6) {
                Image(systemName: change.on ? "lock.shield.fill" : "lock.shield")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .accessibilityHidden(true)
                Text(AdvancedPrivacyText.notice(on: change.on, actorName: actorName))
                    .font(.caption)
                    .fontWeight(.medium)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                Button("adv_privacy.learn_more".localized) { showInfo = true }
                    .font(.caption.weight(.semibold))
                    .buttonStyle(.borderless)
                    .accessibilityIdentifier("advPrivacy.learnMore")
                Text(OshiRowFormatters.string(timestamp, format: "HH:mm"))
                    .font(.caption2)
                    .foregroundColor(.secondary.opacity(0.7))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .modifier(AdvancedPrivacyGlass(shape: RoundedRectangle(cornerRadius: 18, style: .continuous)))
            .frame(maxWidth: 420)
            Spacer(minLength: 24)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("advPrivacy.notice")
        .sheet(isPresented: $showInfo) {
            AdvancedPrivacyInfoSheet(isOn: context.isOn, onToggle: context.toggle)
        }
    }
}

// MARK: - "Learn more" sheet

struct AdvancedPrivacyInfoSheet: View {
    /// The switch as it is NOW (not as the notice that opened this said).
    let isOn: Bool
    /// nil: informational only (no conversation in scope).
    var onToggle: (() -> Void)? = nil
    /// Members / the contact that will not apply it (older OSHI). Optional line.
    var notAppliedBy: Int = 0

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack(spacing: 12) {
                        Image(systemName: "lock.shield.fill")
                            .font(.system(size: 34))
                            .foregroundStyle(Theme.brand)
                            .accessibilityHidden(true)
                        Text("adv_privacy.title".localized)
                            .font(.title2.weight(.bold))
                    }
                    Text("adv_privacy.sheet.intro".localized)
                        .font(.body)
                        .fixedSize(horizontal: false, vertical: true)

                    VStack(alignment: .leading, spacing: 14) {
                        row("photo.badge.exclamationmark", "adv_privacy.sheet.media")
                        row("square.and.arrow.up.trianglebadge.exclamationmark", "adv_privacy.sheet.export")
                        row("sparkles", "adv_privacy.sheet.ai")
                        row("eye.slash", "adv_privacy.sheet.capture")
                        row("arrowshape.turn.up.right", "adv_privacy.sheet.forward")
                        row("person.2", "adv_privacy.sheet.anyone")
                    }
                    .padding(16)
                    .modifier(AdvancedPrivacyGlass(shape: RoundedRectangle(cornerRadius: 20, style: .continuous)))

                    Text("adv_privacy.sheet.limits".localized)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if notAppliedBy > 0 {
                        Text(String(format: "adv_privacy.sheet.not_applied".localized, notAppliedBy))
                            .font(.footnote)
                            .foregroundColor(.orange)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    if let onToggle {
                        Button {
                            onToggle()
                            dismiss()
                        } label: {
                            Text((isOn ? "adv_privacy.turn_off" : "adv_privacy.turn_on").localized)
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 6)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(isOn ? .secondary : Theme.brand)
                        .accessibilityIdentifier("advPrivacy.sheetToggle")
                    }
                }
                .padding(20)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.close".localized) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func row(_ symbol: String, _ key: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .frame(width: 24)
                .foregroundStyle(Theme.brand)
                .accessibilityHidden(true)
            Text(key.localized)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - Group info row

/// The row in the group info page. Any member may flip it.
struct AdvancedPrivacyGroupSection: View {
    let groupId: UUID
    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager
    @ObservedObject private var store = AdvancedChatPrivacyStore.shared
    @State private var showInfo = false

    private var isOn: Bool { store.isOn(groupId.uuidString) }

    private var nonReaders: Int {
        guard let g = groupManager.groups.first(where: { $0.id == groupId }) else { return 0 }
        return groupManager.advancedPrivacyNonReaders(g, me: identityManager.publicKey).count
    }

    var body: some View {
        Section {
            Toggle(isOn: Binding(get: { isOn }, set: { v in
                guard v != isOn else { return }
                groupManager.setAdvancedChatPrivacy(v, groupId: groupId, senderPublicKey: identityManager.publicKey)
            })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("adv_privacy.title".localized)
                    Text("adv_privacy.menu.subtitle".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .tint(Theme.brand)
            .accessibilityIdentifier("gs.advancedPrivacy")
            Button("adv_privacy.learn_more".localized) { showInfo = true }
                .font(.footnote)
        } footer: {
            if nonReaders > 0 {
                Text(String(format: "adv_privacy.sheet.not_applied".localized, nonReaders))
            }
        }
        .sheet(isPresented: $showInfo) {
            AdvancedPrivacyInfoSheet(isOn: isOn, onToggle: {
                groupManager.setAdvancedChatPrivacy(!isOn, groupId: groupId, senderPublicKey: identityManager.publicKey)
            }, notAppliedBy: nonReaders)
        }
    }
}
