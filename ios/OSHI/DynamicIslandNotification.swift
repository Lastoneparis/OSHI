//
//  DynamicIslandNotification.swift
//  OSHI — an in-app notification that emerges from the Dynamic Island, morphs
//  open to show a message for ~2s, then retracts back into the island with the
//  fluid spring motion iOS uses. Purely in-app (no ActivityKit); it reads as the
//  island expanding because it's a black rounded pill anchored at the island's
//  position that grows from the collapsed island size to a content-sized banner.
//
//  Usage — trigger from anywhere:
//      DynamicIslandNotifier.shared.show(
//          icon: "checkmark", iconColor: .green, title: "Sent", message: "Delivered securely")
//
//  Mount once near the app root:
//      ZStack { … }.overlay(alignment: .top) { DynamicIslandNotificationView() }
//

import SwiftUI

// MARK: - Notifier (trigger + lifecycle)

@MainActor
final class DynamicIslandNotifier: ObservableObject {
    static let shared = DynamicIslandNotifier()

    struct Payload: Equatable, Identifiable {
        let id = UUID()
        var icon: String
        var iconColor: Color
        var title: String
        var message: String?
    }

    /// The content to render (nil = nothing on screen).
    @Published private(set) var payload: Payload?
    /// Drives the morph: false = collapsed to island size, true = expanded.
    @Published private(set) var expanded = false

    private var dismissTask: Task<Void, Never>?

    /// Emerge from the island, hold for `duration`, then retract.
    func show(icon: String = "bell.fill",
              iconColor: Color = .white,
              title: String,
              message: String? = nil,
              duration: TimeInterval = 2.0) {
        dismissTask?.cancel()

        let new = Payload(icon: icon, iconColor: iconColor, title: title, message: message)
        // If something is already showing, snap collapsed first so the new one
        // re-emerges cleanly rather than cross-fading mid-morph.
        if payload != nil { expanded = false }
        payload = new

        // Next runloop: expand with a lively spring (the "emerge + morph open").
        DispatchQueue.main.async {
            withAnimation(.spring(response: 0.5, dampingFraction: 0.72)) {
                self.expanded = true
            }
            #if os(iOS)
            Haptics.tap(.soft)
            #endif
        }

        dismissTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(duration * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await self?.retract()
        }
    }

    /// Retract into the island, then remove after the motion settles.
    func retract() async {
        withAnimation(.spring(response: 0.42, dampingFraction: 0.85)) {
            expanded = false
        }
        try? await Task.sleep(nanoseconds: 460_000_000) // match the spring settle
        if !expanded { payload = nil }
    }

    func dismissNow() {
        dismissTask?.cancel()
        Task { await retract() }
    }
}

// MARK: - Overlay view (the morphing pill)

struct DynamicIslandNotificationView: View {
    @ObservedObject private var notifier = DynamicIslandNotifier.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    // Collapsed Dynamic Island metrics (approx. iPhone 14 Pro+ / 15 Pro / 16 Pro).
    private let islandWidth: CGFloat = 126
    private let islandHeight: CGFloat = 37
    private let islandTopInset: CGFloat = 11

    var body: some View {
        VStack(spacing: 0) {
            if let p = notifier.payload {
                banner(p)
                    .padding(.top, islandTopInset)
                    // Keeps the expanded banner inside the screen on every
                    // width, including the 320pt SE class.
                    .padding(.horizontal, 16)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
        .allowsHitTesting(notifier.payload != nil)
        // Tap to dismiss early.
        .onTapGesture { notifier.dismissNow() }
    }

    @ViewBuilder
    private func banner(_ p: DynamicIslandNotifier.Payload) -> some View {
        let expanded = notifier.expanded && !reduceMotion || (notifier.expanded && reduceMotion)
        HStack(spacing: expanded ? 11 : 0) {
            // Icon lives inside the collapsed island too (like the leading glyph).
            ZStack {
                Circle()
                    .fill(p.iconColor.opacity(0.22))
                    .frame(width: 28, height: 28)
                Image(systemName: p.icon)
                    .font(.scaled(14, weight: .bold))
                    .foregroundStyle(p.iconColor)
            }
            .scaleEffect(expanded ? 1 : 0.72)

            if expanded {
                VStack(alignment: .leading, spacing: 1) {
                    Text(p.title)
                        .font(.scaled(15, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    if let m = p.message, !m.isEmpty {
                        Text(m)
                            .font(.scaled(13))
                            .foregroundStyle(.white.opacity(0.72))
                            .lineLimit(2)
                            .truncationMode(.tail)
                    }
                }
                // No fixedSize / no maxWidth .infinity here: the text column
                // keeps its natural ideal width so a short banner still hugs
                // its content, while a long preview gets clamped by the
                // container's maxWidth and truncates instead of stretching
                // the banner past the screen edge.
                .transition(.opacity)   // text fades in as the island opens
            }
        }
        .padding(.horizontal, expanded ? 16 : (islandHeight - 28) / 2)
        .frame(minHeight: expanded ? 58 : islandHeight,
               maxHeight: expanded ? nil : islandHeight)
        .frame(minWidth: expanded ? nil : islandWidth,
               maxWidth: expanded ? 360 : islandWidth)
        .background(
            RoundedRectangle(cornerRadius: expanded ? 28 : islandHeight / 2, style: .continuous)
                .fill(Color.black)
                .shadow(color: .black.opacity(expanded ? 0.35 : 0),
                        radius: expanded ? 20 : 0, y: expanded ? 10 : 0)
        )
        // A hair of scale as it pops out / sinks back — the "emerge/retract" feel.
        .scaleEffect(expanded ? 1 : 0.94)
        .opacity(notifier.payload == nil ? 0 : 1)
    }
}

// MARK: - Convenience presets

extension DynamicIslandNotifier {
    func showSent() {
        show(icon: "checkmark", iconColor: .green, title: "notification.sent".fallback("Sent"))
    }
    func showIncoming(sender: String, preview: String) {
        // A pasted message can be up to 16 KB. Two truncated lines never need
        // more than this, and handing SwiftUI the whole blob to measure is a
        // waste on the main thread.
        let clamped = preview.count > 200
            ? String(preview.prefix(200)) + "\u{2026}"
            : preview
        show(icon: "bubble.left.fill", iconColor: Color(red: 0.62, green: 0.42, blue: 1.0),
             title: sender, message: clamped)
    }
}

private extension String {
    /// Localized value with an English fallback so a missing key never shows raw.
    func fallback(_ english: String) -> String {
        NSLocalizedString(self, value: english, comment: "")
    }
}

// MARK: - __ONE_NOTICE_IN_FOREGROUND_2026_11_09__
//
// Reported: with the app open, an incoming message showed BOTH this in-app
// banner AND the system (APNs) banner, the system one sliding over the in-app
// one and hiding it. Two notices for one message, and the better one covered.
//
// The rule, written once so the delegate, the 1:1 path and the group path
// cannot disagree:
//   * app ACTIVE + a message push  → the system banner is not shown (list only,
//     no sound); the in-app banner is the one notice;
//   * the in-app banner is skipped when the user is already looking at that
//     conversation — the message is landing on the screen they are reading;
//   * calls are never touched here (the delegate handles them first, and
//     CallKit / VoIP do not go through `willPresent` at all);
//   * app NOT active (inactive, background, a Mac window behind another app) →
//     the system banner as before, since the in-app banner only shows while
//     active and the user would otherwise get nothing.

/// The conversation currently on screen, so the in-app banner can stay quiet
/// for it. Written by `ChatView` (1:1); groups use
/// `MessageGroupManager.activeGroupId`, which already existed.
@MainActor
enum OpenConversation {
    /// Normalised (see `MessageBannerPolicy.normalizedKey`) public key of the
    /// open 1:1 thread, nil when none is open.
    private(set) static var oneToOneKey: String?

    static func opened(_ publicKey: String) {
        oneToOneKey = MessageBannerPolicy.normalizedKey(publicKey)
    }

    /// Only clears if `publicKey` is still the open one — a pushed second chat
    /// appearing before the first disappears must not be cleared by it.
    static func closed(_ publicKey: String) {
        if oneToOneKey == MessageBannerPolicy.normalizedKey(publicKey) { oneToOneKey = nil }
    }
}

enum ForegroundNoticePolicy {

    enum SystemPresentation: Equatable {
        /// Banner + sound + badge, the pre-existing behaviour.
        case full
        /// Notification Centre only: no banner over the app, no sound.
        case listOnly
    }

    /// Is this push a chat message? A push with no `type` is one: the
    /// notification extension treats a missing type as "message" too.
    static func isMessagePush(type: String?) -> Bool {
        guard let type, !type.isEmpty else { return true }
        return MessageBannerPolicy.messageTypes.contains(type)
    }

    /// What the system should show for a push arriving while the app runs.
    /// Callers must already have handled calls; `looksLikeCall` is taken
    /// anyway so the rule can never suppress one by itself.
    static func systemPresentation(type: String?,
                                   looksLikeCall: Bool,
                                   appActive: Bool) -> SystemPresentation {
        guard !looksLikeCall, appActive, isMessagePush(type: type) else { return .full }
        return .listOnly
    }

    /// Should the in-app banner show for a message from `conversationKey`?
    /// Keys are compared normalised, never raw — base64 vs base64url of the
    /// same key are the same person.
    static func showsInAppBanner(conversationKey: String,
                                 openConversationKey: String?,
                                 appActive: Bool) -> Bool {
        guard appActive else { return false }
        guard let open = openConversationKey else { return true }
        return MessageBannerPolicy.normalizedKey(open)
            != MessageBannerPolicy.normalizedKey(conversationKey)
    }
}
