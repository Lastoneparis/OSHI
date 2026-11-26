//
//  ChannelWebViews.swift
//  OSHI
//
//  __CHANNEL_WEB_2026_11_26__ The UI of the public channel web preview
//  (PUBLIC_CHANNEL_WEB_SPEC §9): the admin switch in the channel info page, the
//  "Web link" sheet of a post (copy / share / embed), and the deep-link router for
//  `oshi://channel?h=&n=` and `https://oshi-messenger.com/c/<handle>[/<n>]`.
//  The machinery is in ChannelWeb.swift.
//

import SwiftUI

// MARK: - Which posts offer a web link

enum ChannelWebShare {
    /// A channel post by an admin, in a channel whose preview is on, with
    /// something the web copy can carry. Old posts (before the switch) also match
    /// here; the sheet then says "not on the web" after looking it up.
    static func isEligible(_ m: GroupMessage, in group: MessageGroup?) -> Bool {
        guard let group, let s = group.settings, s.webPreviewActive,
              !m.isSystemMessage, m.commentOn == nil,
              GroupSettingsPolicy.isAdmin(m.senderPublicKey, in: group) else { return false }
        return ChannelWebContent.source(for: m) != nil
    }
}

// MARK: - Channel info: the admin switch

struct ChannelWebSection: View {
    let groupId: UUID

    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager
    @State private var confirmOn = false
    @State private var confirmOff = false
    @State private var busy = false
    @State private var failure: String?
    @State private var copied = false
    @State private var pending = 0

    private var group: MessageGroup? { groupManager.groups.first { $0.id == groupId } }
    private var settings: GroupSettings { group?.settings ?? GroupSettings() }
    private var isAdmin: Bool { group.map { GroupSettingsPolicy.isAdmin(identityManager.publicKey, in: $0) } ?? false }

    var body: some View {
        if settings.postPolicy == .admins {
            if isAdmin {
                adminSection
            } else if settings.webPreviewActive, let h = settings.handle {
                Section {
                    linkRow(handle: h)
                } header: {
                    Text("cw_title".localized)
                } footer: {
                    Text("cw_reader_footer".localized)
                }
            }
        }
    }

    @ViewBuilder
    private var adminSection: some View {
        let s = settings
        Section {
            if let h = s.isPublic ? s.handle : nil {
                Toggle(isOn: Binding(
                    get: { s.webPreview },
                    set: { on in if on { confirmOn = true } else { confirmOff = true } })) {
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("cw_title".localized)
                            Text("oshi-messenger.com/c/\(h)")
                                .font(.caption.monospaced())
                                .foregroundStyle(.secondary)
                                .lineLimit(1).truncationMode(.middle)
                        }
                    } icon: {
                        Image(systemName: "globe").foregroundStyle(Theme.brand)
                    }
                }
                .tint(Theme.brand)
                .disabled(busy)
                .accessibilityIdentifier("cw.toggle")
                if busy { HStack { ProgressView(); Text("cw_working".localized).foregroundStyle(.secondary) } }
                if s.webPreview {
                    Toggle("cw_search_engines".localized, isOn: Binding(
                        get: { s.webIndexing },
                        set: { v in groupManager.updateGroupSettings(groupId) { $0.webIndexing = v } }))
                        .tint(Theme.brand)
                        .accessibilityIdentifier("cw.indexing")
                    linkRow(handle: h)
                    if pending > 0 {
                        Label(String(format: "cw_pending".localized, pending), systemImage: "arrow.triangle.2.circlepath")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
            } else {
                // No public handle yet: the web page lives at the public link's name.
                VStack(alignment: .leading, spacing: 6) {
                    Label("cw_title".localized, systemImage: "globe")
                    Text("cw_needs_handle".localized).font(.footnote).foregroundStyle(.secondary)
                }
                NavigationLink {
                    GroupTypeEditorView(groupId: groupId)
                } label: {
                    Text("cw_create_link".localized).foregroundStyle(Theme.brand)
                }
                .accessibilityIdentifier("cw.createLink")
            }
        } footer: {
            Text(s.webPreview ? "cw_footer_on".localized : "cw_footer_off".localized)
        }
        .onAppear { refreshPending() }
        .onReceive(NotificationCenter.default.publisher(for: ChannelWebPublisher.didChange)) { _ in refreshPending() }
        .alert("cw_confirm_on_title".localized, isPresented: $confirmOn) {
            Button("cw_confirm_on_button".localized) { turnOn() }
            Button("cancel".localized, role: .cancel) {}
        } message: {
            Text(String(format: "cw_confirm_on_body".localized, "oshi-messenger.com/c/\(s.handle ?? "")"))
        }
        .alert("cw_confirm_off_title".localized, isPresented: $confirmOff) {
            Button("cw_confirm_off_button".localized, role: .destructive) { turnOff() }
            Button("cancel".localized, role: .cancel) {}
        } message: {
            Text("cw_confirm_off_body".localized)
        }
        .alert("cw_title".localized, isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
            Button("common.ok".localized, role: .cancel) { failure = nil }
        } message: {
            Text(failure ?? "")
        }
    }

    private func refreshPending() {
        pending = settings.handle.map { ChannelWebPublisher.shared.pendingJobs(handle: $0) } ?? 0
    }

    private func turnOn() {
        guard let g = group else { return }
        busy = true
        Task { @MainActor in
            let r = await ChannelWebPublisher.shared.enable(group: g, searchIndexing: settings.webIndexing)
            busy = false
            switch r {
            case .ok: groupManager.updateGroupSettings(groupId) { $0.webPreview = true }
            case .notRegistered: failure = "cw_err_not_registered".localized
            case .notController: failure = "cw_err_not_controller".localized
            case .offline: failure = "cw_err_offline".localized
            case .failed: failure = "cw_err_failed".localized
            }
        }
    }

    private func turnOff() {
        guard let h = settings.handle else { return }
        groupManager.updateGroupSettings(groupId) { $0.webPreview = false; $0.webIndexing = false }
        ChannelWebPublisher.shared.disable(groupId: groupId, handle: h)
    }

    private func linkRow(handle h: String) -> some View {
        let url = ChannelWeb.channelURL(handle: h)
        return HStack(spacing: 12) {
            Button {
                SecureClipboard.copy(url)
                copied = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copied = false }
            } label: {
                Label(copied ? "copied".localized : "cw_copy_channel_link".localized,
                      systemImage: copied ? "checkmark" : "doc.on.doc")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            if let u = URL(string: url) {
                ShareLink(item: u) {
                    Label("gs_share_link".localized, systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
        }
        .labelStyle(.titleAndIcon)
        .font(.subheadline)
        .padding(.vertical, 4)
    }
}

// MARK: - A post's web link: copy, share, embed

struct ChannelWebPostSheet: View {
    let message: GroupMessage
    let handle: String
    let isAdmin: Bool

    @Environment(\.dismiss) private var dismiss
    @State private var n: Int?
    @State private var loading = true
    @State private var dark = false
    @State private var copiedLink = false
    @State private var copiedCode = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if loading {
                        HStack { Spacer(); ProgressView(); Spacer() }.padding(.top, 40)
                    } else if let n {
                        content(n)
                    } else {
                        VStack(spacing: 10) {
                            Image(systemName: "globe.badge.chevron.backward")
                                .font(.system(size: 40)).foregroundStyle(.secondary)
                            Text("cw_not_on_web".localized)
                                .multilineTextAlignment(.center).foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity).padding(.top, 40)
                    }
                }
                .padding()
            }
            .navigationTitle("cw_post_menu".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("common.done".localized) { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
        .task {
            n = await ChannelWebPublisher.shared.resolveN(message: message, handle: handle, isAdmin: isAdmin)
            loading = false
        }
    }

    @ViewBuilder
    private func content(_ n: Int) -> some View {
        let link = ChannelWeb.postURL(handle: handle, n: n)
        let code = ChannelWeb.embedSnippet(handle: handle, n: n, dark: dark ? true : nil)
        ChannelWebCard {
            VStack(alignment: .leading, spacing: 12) {
                Text(link.replacingOccurrences(of: "https://", with: ""))
                    .font(.system(.body, design: .monospaced))
                    .foregroundStyle(Theme.brand)
                    .lineLimit(1).truncationMode(.middle)
                    .textSelection(.enabled)
                HStack(spacing: 12) {
                    Button {
                        SecureClipboard.copy(link)
                        copiedLink = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copiedLink = false }
                    } label: {
                        Label(copiedLink ? "copied".localized : "cw_copy_link".localized,
                              systemImage: copiedLink ? "checkmark" : "link")
                            .frame(maxWidth: .infinity)
                    }
                    .accessibilityIdentifier("cw.copyLink")
                    if let u = URL(string: link) {
                        // ShareLink with a URL: the system share sheet fetches the
                        // page's Open Graph tags itself, so the preview is rich.
                        ShareLink(item: u) {
                            Label("cw_share".localized, systemImage: "square.and.arrow.up")
                                .frame(maxWidth: .infinity)
                        }
                    }
                }
                .modifier(ChannelWebButtonStyle())
            }
        }
        Text("cw_embed_title".localized).font(.headline)
        ChannelWebCard {
            VStack(alignment: .leading, spacing: 12) {
                Text(code)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("cw.embedCode")
                Toggle("cw_embed_dark".localized, isOn: $dark).tint(Theme.brand)
                Button {
                    SecureClipboard.copy(code)
                    copiedCode = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copiedCode = false }
                } label: {
                    Label(copiedCode ? "copied".localized : "cw_copy_code".localized,
                          systemImage: copiedCode ? "checkmark" : "chevron.left.forwardslash.chevron.right")
                        .frame(maxWidth: .infinity)
                }
                .modifier(ChannelWebButtonStyle())
                .accessibilityIdentifier("cw.copyCode")
            }
        }
        Text("cw_embed_footer".localized).font(.footnote).foregroundStyle(.secondary)
    }
}

/// Liquid Glass on 26, the app's material before.
private struct ChannelWebCard<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 22, style: .continuous)
        if #available(iOS 26.0, macCatalyst 26.0, *) {
            content.padding(16).frame(maxWidth: .infinity, alignment: .leading).glassEffect(.regular, in: shape)
        } else {
            content.padding(16).frame(maxWidth: .infinity, alignment: .leading).background(shape.fill(.regularMaterial))
        }
    }
}

private struct ChannelWebButtonStyle: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, macCatalyst 26.0, *) {
            content.buttonStyle(.glass).tint(Theme.brand).labelStyle(.titleAndIcon)
        } else {
            content.buttonStyle(.bordered).tint(Theme.brand).labelStyle(.titleAndIcon)
        }
    }
}

// MARK: - Deep links

enum ChannelWebRouter {
    /// Opens the channel when this device is a member (then scrolls to post `n`
    /// when it can be matched), else starts the public `/g/<handle>` join flow.
    /// Returns false when `url` is not a channel link.
    @MainActor
    static func handle(_ url: URL, joinFlow: (String) -> Void) -> Bool {
        guard let link = ChannelWeb.parseLink(url) else { return false }
        let mgr = MessageGroupManager.shared
        guard let h = GroupSettings.normalizedHandle(link.handle),
              let g = mgr.groups.first(where: { $0.settings?.handle == h && $0.settings?.isPublic == true }) else {
            joinFlow(link.handle)
            return true
        }
        NotificationCenter.default.post(name: NSNotification.Name("OpenGroupChat"), object: nil,
                                        userInfo: ["groupId": g.id.uuidString])
        if let n = link.n {
            Task { @MainActor in
                guard let id = await ChannelWebPublisher.shared.localMessageId(handle: h, n: n, groupId: g.id) else { return }
                // The thread may still be opening: ask twice.
                for delay in [0.6, 1.6] {
                    try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                    NotificationCenter.default.post(name: NSNotification.Name("ScrollToOriginalGroupMessage"),
                                                    object: nil, userInfo: ["messageId": id.uuidString])
                }
            }
        }
        return true
    }
}
