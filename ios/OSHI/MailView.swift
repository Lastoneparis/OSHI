//
//  MailView.swift
//  OSHI
//
//  The mail surface: claim an address, read, write, manage aliases and the drive.
//
//  Everything shown here was decrypted on this device. The subjects in the list did not
//  come from the server as text — they arrived as sealed envelopes that `OSHIMailClient`
//  opened locally — which is why the empty and error states talk about THIS DEVICE rather
//  than about the account: on a zero-access mailbox, "can't open" means the wrong device,
//  not the wrong password.
//

import SwiftUI
import UniformTypeIdentifiers
import WebKit
import ImageIO

// MARK: - Wide layout  __DUO_POLISH_2026_11_18__

/// The open message of the Email segment, owned by ChatsTabView like
/// `ThreadSelection` is for chats. Wide (open iPhone Duo, iPad), the inbox
/// stays in the sidebar and this message is drawn in the detail pane; narrow,
/// MailView's stack pushes it. Folding or unfolding swaps which of the two
/// draws it — the selection does not move, so the same message stays open.
/// Holds the message itself (not only its id) so a list refresh that briefly
/// drops it does not blank the pane. Main thread only (SwiftUI).
final class MailSelection: ObservableObject {
    @Published var message: OSHIMailMessage?
}

private struct MailSelectionKey: EnvironmentKey {
    static let defaultValue: MailSelection? = nil
}

extension EnvironmentValues {
    /// nil when MailView is shown outside the Chats tab (no split there).
    var mailSelection: MailSelection? {
        get { self[MailSelectionKey.self] }
        set { self[MailSelectionKey.self] = newValue }
    }
}

/// The right-hand pane of the wide Email layout.
struct MailDetailPane: View {
    @ObservedObject var selection: MailSelection

    var body: some View {
        // A fresh stack per message: an attachment opened in the previous one
        // must not stay on top of the next.
        NavigationStack {
            if let message = selection.message {
                MailMessageView(message: message)
            } else {
                VStack(spacing: 14) {
                    Image(systemName: "envelope.open")
                        .font(.system(size: 44, weight: .regular))
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                    Text("mail.title".localized)
                        .font(.title3.weight(.semibold))
                }
                .padding(32)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color(.systemGroupedBackground))
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("mail.placeholder")
            }
        }
        .id(selection.message?.id)
        .environment(\.mailSelection, selection)
        .accessibilityIdentifier("mail.detail")
    }
}

/// The five server-side folders. Raw values match the `folder` string the API uses, so a
/// `MailFolder(rawValue:)` round-trips cleanly with what `OSHIMailMessage.folder` holds.
enum MailFolder: String, CaseIterable, Identifiable {
    case inbox, archive, draft, sent, trash

    var id: String { rawValue }

    var title: String {
        switch self {
        case .inbox: return "mail.folder.inbox".localized
        case .archive: return "mail.folder.archive".localized
        case .draft: return "mail.folder.drafts".localized
        case .sent: return "mail.folder.sent".localized
        case .trash: return "mail.folder.trash".localized
        }
    }

    var icon: String {
        switch self {
        case .inbox: return "tray"
        case .archive: return "archivebox"
        case .draft: return "doc.text"
        case .sent: return "paperplane"
        case .trash: return "trash"
        }
    }
}

struct MailView: View {
    @EnvironmentObject var identityManager: IdentityManager
    // No `@Environment(\.dismiss)` here on purpose: MailView is the Email
    // segment of a root tab, so there is nothing to dismiss. See the toolbar.
    @StateObject private var mail = OSHIMailClient.shared
    @State private var isLoading = true
    @State private var loadError: String?
    /// The server answered `no-mailbox` on the last account fetch — the only thing
    /// that may show `MailSetupView`.
    @State private var confirmedNoMailbox = false
    @State private var showCompose = false
    @State private var showSettings = false

    @State private var folder: MailFolder = .inbox
    @State private var searchText = ""
    @State private var deepSearch = false
    @State private var draftPrefill: DraftPrefill?
    @State private var pendingPermanentDelete: OSHIMailMessage?

    /// Set by a new-email notification tap (via ChatsTabView). The value is the mailbox
    /// id to open ("" = just show the Inbox). Consumed and cleared here once navigated,
    /// so the same message can be re-opened by a later tap. `.constant(nil)` when MailView
    /// is presented outside the tab flow (e.g. the Messages-list menu entry).
    @Binding var deepLinkMessageId: String?
    @State private var deepLinkedMessage: OSHIMailMessage?
    @State private var navigateToDeepLinkedMessage = false

    init(deepLinkMessageId: Binding<String?> = .constant(nil)) {
        self._deepLinkMessageId = deepLinkMessageId
    }

    // __DUO_POLISH_2026_11_18__ see `MailSelection`. Both nil/false outside
    // the Chats tab, where MailView behaves exactly as before.
    @Environment(\.mailSelection) private var mailSelection
    @Environment(\.threadSplitActive) private var splitActive

    /// A pushed message, as a navigation value (a bare String would collide
    /// with any other String destination in this stack).
    struct MailRoute: Hashable { let id: String }

    /// Narrow: the selected message is pushed. Wide: the stack stays at the
    /// inbox (the message is in the detail pane). Same selection either way,
    /// so folding with a message open keeps it open.
    private var mailPath: Binding<[MailRoute]> {
        Binding(
            get: {
                guard !splitActive, let m = mailSelection?.message else { return [] }
                return [MailRoute(id: m.id)]
            },
            set: { newPath in
                guard let mailSelection else { return }
                if let last = newPath.last {
                    if mailSelection.message?.id != last.id,
                       let m = mail.messages.first(where: { $0.id == last.id }) {
                        mailSelection.message = m
                    }
                } else if !splitActive {
                    mailSelection.message = nil
                }
            }
        )
    }

    var body: some View {
        NavigationStack(path: mailPath) {
            SwiftUI.Group {
                // __MAIL_OFFLINE_NOT_SETUP_2026_11_25__ The claim-an-address form is
                // shown ONLY after the server said "no-mailbox". Offline / timeout /
                // 5xx with no cached account is "can't reach mail", not "no mailbox".
                switch MailScreenState.resolve(isLoading: isLoading,
                                               hasAccount: mail.account != nil,
                                               confirmedNoMailbox: confirmedNoMailbox) {
                case .loading:
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                case .setup:
                    MailSetupView { await load() }
                case .unreachable:
                    mailUnreachable
                case .mailbox:
                    VStack(spacing: 0) {
                        freshnessBadge
                        inbox
                    }
                }
            }
            .sidebarColumnContent(splitActive)
            .navigationTitle(mail.account.map { _ in "mail.title".localized } ?? "")
            // __MAIL_LIQUID_GLASS_2026_11_25__ inline, like the Messages list and a
            // group: the title is the floating island below, not a large title.
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // __MAIL_CLOSE_WAS_A_DEAD_BUTTON_2026_11_06__ A leading
                // `xmark.circle.fill` used to sit here, next to the account
                // button. Its history: it started as `Button("common.done")`,
                // which reads "OK" in French (reported as "the OK button, which
                // I think does nothing"); on 03/11 it was re-labelled an xmark
                // because back then MailView was only ever a `fullScreenCover`
                // and this was the single way out, so removing it would have
                // trapped the user.
                //
                // That premise no longer holds. MailView is now the Email
                // segment of ChatsTabView, which is a root tab of the TabView —
                // nothing presents it, so `@Environment(\.dismiss)` had nothing
                // to dismiss and the cross genuinely did nothing. (The one
                // `fullScreenCover(isPresented: $showMail)` left in
                // MessagesListView was dead too: no code ever set `showMail`.
                // It has been removed with this change.)
                //
                // So: no close affordance here. The segment bar above is how
                // the user leaves the mailbox. If MailView is ever presented
                // modally again, it needs a way out added back — see
                // `MailCloseAffordanceTests`.
                if let account = mail.account {
                    // __MAIL_LIQUID_GLASS_2026_11_25__ (account)  ( Mail / address )  (compose),
                    // the group header's layout. The island opens the mailbox settings too.
                    ToolbarItem(placement: .principal) {
                        GroupHeaderIsland(
                            name: "mail.title".localized,
                            subtitle: account.address,
                            accessibilityLabelText: "mail.account.title".localized,
                            identifier: "mail.header.island",
                            onTap: { showSettings = true })
                    }
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button { showCompose = true } label: { Image(systemName: "square.and.pencil") }
                            .accessibilityLabel("mail.compose.title".localized)
                            .accessibilityIdentifier("mail.compose")
                    }
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button { showSettings = true } label: { Image(systemName: "person.crop.circle") }
                            .accessibilityLabel("mail.account.title".localized)
                    }
                    if !searchText.isEmpty {
                        ToolbarItem(placement: .navigationBarTrailing) {
                            Button {
                                deepSearch.toggle()
                            } label: {
                                Image(systemName: deepSearch ? "text.magnifyingglass" : "magnifyingglass")
                                    .foregroundStyle(deepSearch ? Theme.brand : .secondary)
                            }
                            .help("mail.deepsearch.help".localized)
                        }
                    }
                }
            }
            .sheet(isPresented: $showCompose) {
                #if DEBUG
                // `-OSHIMailDemoCompose`: a reply-shaped composer for screenshots.
                if ProcessInfo.processInfo.arguments.contains("-OSHIMailDemoCompose") {
                    MailComposeView(to: "Léa Martin <lea@example.org>, sofia@example.it",
                                    subject: "Re: Trip to Lisbon — final plan",
                                    messageBody: "Sounds perfect! Friday works for me.\n\nSee you at the airport,\nHugo")
                } else {
                    MailComposeView()
                }
                #else
                MailComposeView()
                #endif
            }
            .sheet(isPresented: $showSettings) { MailAccountView().environmentObject(identityManager) }
            .sheet(item: $draftPrefill) { prefill in
                MailComposeView(to: prefill.to, subject: prefill.subject,
                                messageBody: prefill.body, draftId: prefill.id)
            }
            .confirmationDialog(
                "mail.confirm.deleteForever".localized,
                isPresented: Binding(get: { pendingPermanentDelete != nil },
                                     set: { if !$0 { pendingPermanentDelete = nil } }),
                titleVisibility: .visible) {
                Button("mail.action.deleteForever".localized, role: .destructive) {
                    if let m = pendingPermanentDelete {
                        Task { try? await mail.delete(id: m.id, permanent: true) }
                    }
                    pendingPermanentDelete = nil
                }
                Button("common.cancel".localized, role: .cancel) { pendingPermanentDelete = nil }
            }
            // Programmatic push for a new-email tap. iOS 16-safe (isPresented:), matching
            // MessagesListView/GroupViews' notification-driven navigation.
            .navigationDestination(isPresented: $navigateToDeepLinkedMessage) {
                if let m = deepLinkedMessage { MailMessageView(message: m) }
            }
            .navigationDestination(for: MailRoute.self) { route in
                if let m = mailSelection?.message, m.id == route.id {
                    MailMessageView(message: m)
                } else if let m = mail.messages.first(where: { $0.id == route.id }) {
                    MailMessageView(message: m)
                }
            }
        }
        .task {
            // The client signs with the app's existing identity; wire it up before the
            // first request rather than letting one fail as unauthenticated.
            mail.configure(identity: identityManager)
            await load()
            // Cold-launch / just-switched-to-Email: a pending id is already set when
            // this view first mounts, so .onChange won't fire — consume it here.
            consumeMailDeepLink()
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-OSHIMailDemoCompose") {
                try? await Task.sleep(nanoseconds: 800_000_000)
                showCompose = true
            }
            #endif
        }
        // Warm path: MailView already mounted (user on the Email segment) when a new tap
        // lands and updates the binding.
        .onChange(of: deepLinkMessageId) { _, newValue in
            if newValue != nil { consumeMailDeepLink() }
        }
    }

    /// Routes a new-email tap to the specific message. Always lands on the Inbox; if the
    /// push carried a mailbox id, refreshes if needed (the row may not be fetched yet on a
    /// cold launch) and pushes MailMessageView. Clears the binding so a repeat tap works.
    private func consumeMailDeepLink() {
        guard let target = deepLinkMessageId else { return }
        folder = .inbox
        deepLinkMessageId = nil
        guard !target.isEmpty else { return } // no id → the Inbox itself is the destination
        Task {
            if mail.messages.first(where: { $0.id == target }) == nil {
                _ = try? await mail.refreshInbox()
            }
            if let m = mail.messages.first(where: { $0.id == target }) {
                if let mailSelection {
                    // Chats tab: the selection opens it in either layout.
                    mailSelection.message = m
                } else {
                    deepLinkedMessage = m
                    navigateToDeepLinkedMessage = true
                }
            }
        }
    }

    // MARK: Folder filtering + search

    /// Client-side only — a zero-access mailbox has nothing for the server to search.
    /// "Deep" additionally matches bodies already decrypted and cached this session,
    /// never by downloading every message just to search it.
    private var filteredMessages: [OSHIMailMessage] {
        let base = mail.messages.filter { $0.folder == folder.rawValue }
        let q = searchText.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return base }
        return base.filter { message in
            let e = message.envelope
            let haystack = [e?.from, e?.to, e?.subject, message.deliveredTo]
                .compactMap { $0 }.joined(separator: " ").lowercased()
            if haystack.contains(q) { return true }
            if deepSearch, let cached = mail.bodyCache[message.id], cached.lowercased().contains(q) { return true }
            return false
        }
    }

    private func folderCount(_ f: MailFolder) -> Int {
        let rows = mail.messages.filter { $0.folder == f.rawValue }
        // Only the Inbox counts unread. Archive is where read mail goes, so an unread
        // count there is permanently 0 and would make a non-empty folder read as empty.
        return f == .inbox ? rows.filter { !$0.seen }.count : rows.count
    }

    /// __MAIL_LIQUID_GLASS_2026_11_25__ Glass capsules, the selected one solid brand.
    private var folderPicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            MailGlassGroup(spacing: 8) {
                HStack(spacing: 8) {
                    ForEach(MailFolder.allCases) { f in
                        MailFolderChip(folder: f, count: folderCount(f), selected: folder == f) {
                            folder = f
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
            }
        }
    }

    /// __MAIL_LIQUID_GLASS_2026_11_25__ Content first, like the conversation list:
    /// folders, then the messages; the address, the quotas and the drive (which
    /// used to fill the first screen) follow the messages.
    private var inbox: some View {
        List {
            Section {
                folderPicker
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }

            Section {
                if filteredMessages.isEmpty {
                    MailStateCard(icon: searchText.isEmpty ? folder.icon : "magnifyingglass",
                                  message: searchText.isEmpty ? MailChromeText.emptyFolder : MailChromeText.emptySearch,
                                  identifier: "mail.empty")
                        .frame(minHeight: 240)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                } else {
                    let messages = filteredMessages
                    ForEach(messages) { message in
                        row(for: message)
                            .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
                            .alignmentGuide(.listRowSeparatorLeading) { _ in 56 }
                            .oledGlassListRow(first: message.id == messages.first?.id)  // __OLED_GLASS_2026_11_16__
                    }
                }
            }

            if let loadError {
                Text(loadError).font(.footnote).foregroundStyle(.red)
            }

            if let account = mail.account {
                Section {
                    MailQuotaRow(account: account, mail: mail)
                        .oledGlassListRow()  // __OLED_GLASS_2026_11_16__
                    NavigationLink { MailDriveView() } label: {
                        HStack {
                            Image(systemName: "folder").foregroundStyle(Theme.brand)
                            Text("mail.drive.title".localized)
                            Spacer()
                            if let drive = mail.account?.drive {
                                Text("\(drive.files) file\(drive.files == 1 ? "" : "s")")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                } header: {
                    Text(account.address).textCase(nil).font(.footnote.monospaced())
                }
            }
        }
        .listSectionSpacing(.compact)
        .searchable(text: $searchText, prompt: "Search mail")
        .refreshable { await load() }
    }

    @ViewBuilder
    private func row(for message: OSHIMailMessage) -> some View {
        SwiftUI.Group {
            if folder == .draft {
                Button { openDraft(message) } label: { MailRow(message: message) }
                    .buttonStyle(.plain)
            } else if let mailSelection, splitActive {
                // Wide: select into the detail pane, and show which is open.
                MailSelectableRow(message: message, selection: mailSelection)
            } else if mailSelection != nil {
                NavigationLink(value: MailRoute(id: message.id)) { MailRow(message: message) }
            } else {
                NavigationLink { MailMessageView(message: message) } label: { MailRow(message: message) }
            }
        }
        .swipeActions(edge: .leading) {
            if folder == .inbox {
                Button { Task { try? await mail.move(id: message.id, to: "archive") } } label: {
                    Label("mail.action.archive".localized, systemImage: "archivebox")
                }.tint(.gray)
                Button { Task { try? await mail.markSeen(id: message.id, seen: !message.seen) } } label: {
                    Label(message.seen ? "Unread" : "Read",
                         systemImage: message.seen ? "envelope.badge" : "envelope.open")
                }.tint(.indigo)
            } else if folder == .archive || folder == .trash {
                Button { Task { try? await mail.move(id: message.id, to: "inbox") } } label: {
                    Label("mail.action.restore".localized, systemImage: "tray.and.arrow.down")
                }.tint(Theme.brand)
            }
        }
        .swipeActions(edge: .trailing) {
            if folder == .draft {
                Button(role: .destructive) { Task { try? await mail.delete(id: message.id, permanent: true) } } label: {
                    Label("common.delete".localized, systemImage: "trash")
                }
            } else if folder == .trash {
                Button(role: .destructive) { pendingPermanentDelete = message } label: {
                    Label("mail.action.deleteForeverCap".localized, systemImage: "trash")
                }
            } else {
                Button(role: .destructive) { Task { try? await mail.delete(id: message.id) } } label: {
                    Label("mail.folder.trash".localized, systemImage: "trash")
                }
            }
        }
    }

    // MARK: Drafts

    private struct DraftPrefill: Identifiable {
        let id: String
        let to: String
        let subject: String
        let body: String
    }

    /// A draft's decrypted bytes are the plain body text, not an RFC822 message — it was
    /// sealed by `OSHIMailClient.saveDraft` as raw text, so it is decoded directly rather
    /// than run through `MailMessageParser`.
    private func openDraft(_ message: OSHIMailMessage) {
        Task {
            do {
                let data = try await mail.fetchMessage(id: message.id)
                let text = String(data: data, encoding: .utf8) ?? ""
                draftPrefill = DraftPrefill(id: message.id,
                                            to: message.envelope?.to ?? "",
                                            subject: message.envelope?.subject ?? "",
                                            body: text)
            } catch {
                loadError = error.localizedDescription
            }
        }
    }

    private func load() async {
        loadError = nil
        #if DEBUG
        // `-OSHIScreens -OSHIMailDemo -OSHIMailDemoOffline`: no account, no network —
        // the "can't reach mail" state, without touching any server.
        if ProcessInfo.processInfo.arguments.contains("-OSHIMailDemoOffline") {
            loadError = URLError(.notConnectedToInternet).localizedDescription
            isLoading = false
            return
        }
        #endif
        do {
            _ = try await mail.refreshAccount()
            confirmedNoMailbox = false
            _ = try await mail.refreshInbox()
        } catch OSHIMailError.noMailbox {
            // Not an error — this identity simply has no address yet. The client has
            // dropped its cached account; this is the one path to the setup form.
            confirmedNoMailbox = true
        } catch {
            // Offline / timeout / 5xx: says nothing about whether a mailbox exists.
            // A cached account keeps the mailbox on screen (badge: "Could not refresh").
            loadError = error.localizedDescription
        }
        isLoading = false
    }

    /// No known account and the server could not be reached: never the setup form.
    private var mailUnreachable: some View {
        MailStateCard(icon: "wifi.exclamationmark",
                      message: "mail.unreachable".localized,
                      actionTitle: "common.retry".localized,
                      action: {
                          isLoading = true
                          Task { await load() }
                      },
                      identifier: "mail.unreachable")
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
    }
}

// MARK: - Row

/// A row of the wide layout's inbox: a tap opens the message in the detail
/// pane; the open one is tinted, like a sidebar selection.
struct MailSelectableRow: View {
    let message: OSHIMailMessage
    @ObservedObject var selection: MailSelection

    var body: some View {
        let selected = selection.message?.id == message.id
        Button { selection.message = message } label: {
            MailRow(message: message)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(selected ? Theme.brand.opacity(0.16) : nil)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct MailRow: View {
    let message: OSHIMailMessage
    @Environment(\.dynamicTypeSize) private var typeSize

    /// Sent and Drafts are outgoing: who it went TO is the useful fact, not who it
    /// went from (that's always this mailbox).
    private var outgoing: Bool { message.folder == "sent" || message.folder == "draft" }

    private var correspondent: String? {
        guard let e = message.envelope else { return nil }
        return outgoing ? (e.to.isEmpty ? nil : e.to) : e.from
    }

    var body: some View {
        // __MAIL_LIQUID_GLASS_2026_11_25__ Drawn like a conversation row: the
        // correspondent's avatar, name and date on the first line, then subject
        // and preview; unread = bold name, brand date and a brand dot.
        HStack(alignment: .top, spacing: 12) {
            if !typeSize.isAccessibilitySize {
                SwiftUI.Group {
                    if let who = correspondent {
                        MailSenderAvatar(header: who, size: 44)
                    } else {
                        Image(systemName: message.envelope == nil ? "lock.fill" : "envelope")
                            .font(.body)
                            .foregroundStyle(.secondary)
                            .frame(width: 44, height: 44)
                            .background(Color.secondary.opacity(0.12), in: Circle())
                            .accessibilityHidden(true)
                    }
                }
            }

            VStack(alignment: .leading, spacing: 2) {
                // At accessibility text sizes a trailing date column squeezes the sender
                // and subject to a few letters; the markers and date get their own line.
                if typeSize.isAccessibilitySize { meta }
                if let envelope = message.envelope {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text(outgoing
                            ? (envelope.to.isEmpty ? "(no recipient)" : MailMessageParser.displayName(from: envelope.to))
                            : MailMessageParser.displayName(from: envelope.from))
                            .font(.body.weight(message.seen ? .regular : .semibold))
                            .lineLimit(1)
                        Spacer(minLength: 4)
                        if !typeSize.isAccessibilitySize { meta }
                    }
                    // Primary when a snippet follows, so subject and preview read as two lines.
                    Text(envelope.subject.isEmpty ? "(no subject)" : envelope.subject)
                        .font(.subheadline.weight(message.seen ? .regular : .medium))
                        .foregroundStyle(envelope.snippet == nil ? Color.secondary : Color.primary)
                        .lineLimit(1)
                    // Sealed list preview from the server; old mail has none and shows none.
                    if let snippet = envelope.snippet {
                        Text(snippet)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                } else {
                    HStack(alignment: .firstTextBaseline) {
                        // The envelope is sealed to a key this device does not hold.
                        Text("mail.msg.sealedOther".localized)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .italic()
                        Spacer(minLength: 4)
                        if !typeSize.isAccessibilitySize { meta }
                    }
                }

                // Only show the alias when it is not the primary address — that is the
                // one fact worth surfacing, since it says which alias is getting mail.
                if message.folder == "inbox",
                   let primary = OSHIMailClient.shared.account?.localpart,
                   message.deliveredTo != primary {
                    Text(String(format: "mail.msg.via".localized, message.deliveredTo))
                        .font(.caption2)
                        .foregroundStyle(MailMessageView.linkColor)
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityValue(Self.attachmentDescription(message.envelope))
    }

    /// Attachment / image markers right before the date, like every mail app. SF Symbols
    /// at the caption text style scale with Dynamic Type. Unread: brand date + dot.
    private var meta: some View {
        HStack(spacing: 4) {
            if message.envelope?.hasImages == true {
                Image(systemName: "photo")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if (message.envelope?.attachmentCount ?? 0) > 0 {
                Image(systemName: "paperclip")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(message.receivedAt, format: .dateTime.day().month(.abbreviated))
                .font(.caption)
                .foregroundStyle(message.seen ? Color.secondary : MailMessageView.linkColor)
                .lineLimit(1)
            if !message.seen {
                Circle()
                    .fill(Theme.brand)
                    .frame(width: 9, height: 9)
                    .accessibilityHidden(true)
            }
        }
        .fixedSize()
    }

    /// Spoken form of the row's markers (the icons themselves are decorative).
    static func attachmentDescription(_ envelope: OSHIMailMessage.Envelope?) -> String {
        guard let e = envelope else { return "" }
        var parts: [String] = []
        if e.attachmentCount == 1 { parts.append("1 attachment") }
        else if e.attachmentCount > 1 { parts.append("\(e.attachmentCount) attachments") }
        if e.hasImages { parts.append("contains images") }
        return parts.joined(separator: ", ")
    }
}

// MARK: - Quota

struct MailQuotaRow: View {
    let account: OSHIMailAccount
    @ObservedObject var mail: OSHIMailClient
    @State private var showPaywall = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            bar(title: "mail.quota.mailbox".localized,
                used: account.storage.used, quota: account.storage.quota,
                detail: "\(format(account.storage.used)) of \(format(account.storage.quota))")
            bar(title: "mail.quota.sentThisMonth".localized,
                used: account.sending.sent, quota: account.sending.quota,
                detail: "\(account.sending.sent) of \(account.sending.quota)")
            bar(title: "mail.quota.drive".localized,
                used: account.drive.used, quota: account.drive.quota,
                detail: "\(format(account.drive.used)) of \(format(account.drive.quota))")

            if account.isPremium {
                // A paying customer needs to see WHEN it renews far more than
                // they need to be congratulated.
                HStack(spacing: 5) {
                    Image(systemName: "checkmark.seal.fill").font(.caption2)
                    Text(renewalText).font(.caption2)
                }
                .foregroundStyle(Theme.brand)
                .padding(.top, 2)
            } else {
                Button {
                    showPaywall = true
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "arrow.up.circle.fill").font(.caption)
                        Text("premium.upgradeCTA".localized).font(.caption.weight(.medium))
                    }
                }
                .buttonStyle(.plain)
                .foregroundStyle(Theme.brand)
                .padding(.top, 2)
            }
        }
        .padding(.vertical, 4)
        .sheet(isPresented: $showPaywall) {
            PremiumPaywallView(mail: mail)
        }
    }

    private var renewalText: String {
        guard let raw = account.premiumUntil else { return "premium.active".localized }
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        // The server sends fractional seconds; a formatter configured without
        // them returns nil and the row would silently lose the date.
        let date = iso.date(from: raw) ?? ISO8601DateFormatter().date(from: raw)
        guard let date else { return "premium.active".localized }
        return String(format: "premium.renews".localized,
                      date.formatted(date: .abbreviated, time: .omitted))
    }

    private func bar(title: String, used: Int, quota: Int, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(title).font(.caption).foregroundStyle(.secondary)
                Spacer()
                Text(detail).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
            ProgressView(value: Double(used), total: Double(max(quota, 1)))
                .tint(Double(used) / Double(max(quota, 1)) > 0.9 ? .red : Theme.brand)
        }
    }

    private func format(_ bytes: Int) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }
}

// MARK: - Setup

struct MailSetupView: View {
    let onCreated: () async -> Void

    @StateObject private var mail = OSHIMailClient.shared
    @State private var localpart = ""
    @State private var availability: Bool?
    @State private var checking = false
    @State private var creating = false
    @State private var error: String?
    @State private var checkTask: Task<Void, Never>?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("mail.mailbox.title".localized)
                        .font(.title2.bold())
                    Text("mail.mailbox.explain".localized)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }

                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 2) {
                        TextField("mail.alias.namePlaceholder".localized, text: $localpart)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .textFieldStyle(.roundedBorder)
                        Text("@\(OSHIMailClient.mailDomain)")
                            .font(.footnote.monospaced())
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                    }

                    SwiftUI.Group {
                        if checking {
                            Text("mail.avail.checking".localized).foregroundStyle(.secondary)
                        } else if let availability {
                            availability
                                ? Text("mail.avail.free".localized).foregroundStyle(.green)
                                : Text("mail.avail.taken".localized).foregroundStyle(.red)
                        } else if !localpart.isEmpty {
                            Text("mail.avail.rules".localized)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .font(.caption)
                }

                Button {
                    Task { await create() }
                } label: {
                    if creating { ProgressView() } else { Text("mail.create.button".localized).frame(maxWidth: .infinity) }
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.brand)
                .disabled(availability != true || creating)

                if let error {
                    Text(error).font(.footnote).foregroundStyle(.red)
                }

                VStack(alignment: .leading, spacing: 6) {
                    Label("mail.feature.mailbox".localized, systemImage: "envelope")
                    Label("mail.feature.messages".localized, systemImage: "paperplane")
                    Label("mail.feature.drive".localized, systemImage: "folder")
                    Label("mail.feature.aliases".localized, systemImage: "at")
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
            .padding()
        }
        .onChange(of: localpart) { _, new in scheduleCheck(new) }
    }

    /// Debounced so typing an address does not fire one request per keystroke — the
    /// availability route is rate-limited server-side and would start returning 429.
    private func scheduleCheck(_ value: String) {
        checkTask?.cancel()
        availability = nil
        let candidate = value.lowercased()
        guard candidate.count >= 3 else { return }

        checkTask = Task {
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled else { return }
            checking = true
            defer { checking = false }
            do {
                let free = try await mail.isAvailable(candidate)
                guard !Task.isCancelled else { return }
                availability = free
            } catch {
                availability = nil
            }
        }
    }

    private func create() async {
        creating = true
        error = nil
        do {
            try await mail.register(localpart: localpart.lowercased())
            await onCreated()
        } catch {
            self.error = error.localizedDescription
        }
        creating = false
    }
}

// MARK: - Reading

struct MailMessageView: View {
    let message: OSHIMailMessage

    @Environment(\.dismiss) private var dismiss
    @StateObject private var mail = OSHIMailClient.shared
    @State private var parsed: ParsedMail?
    @State private var error: String?
    @State private var showReply = false
    @State private var confirmPermanentDelete = false
    @State private var savingAttachment: String?
    @State private var attachmentNote: String?
    @State private var attachmentNoteIsError = false
    @State private var htmlHeight: CGFloat = 60
    @State private var allowRemote = false
    @State private var remoteContent = false
    /// __MAIL_LIQUID_GLASS_2026_11_25__ the header island toggles the full headers.
    @State private var showDetails = false
    @State private var forwardDraft: ForwardDraft?
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.mailSelection) private var mailSelection

    /// Leaves this message after archive / delete / mark-unread. Pushed, that
    /// is a pop; in the wide layout's detail pane there is nothing to pop, so
    /// the selection is cleared instead (else the pane keeps a message that is
    /// no longer in the folder the list shows).
    private func close() {
        if let mailSelection, mailSelection.message?.id == message.id {
            mailSelection.message = nil
        }
        dismiss()
    }

    /// Reads the live folder from the client's message list rather than the `message`
    /// snapshot passed in, so the toolbar reflects an action (archive, restore) taken
    /// from this same screen without needing the parent to re-push the view.
    private var currentFolder: String {
        mail.messages.first(where: { $0.id == message.id })?.folder ?? message.folder
    }

    /// The brand violet, lifted in dark mode where the brand value itself is too dark
    /// to read on black.
    static let linkColor = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.66, green: 0.58, blue: 1.0, alpha: 1)
            : UIColor(red: 0.337, green: 0.086, blue: 0.925, alpha: 1)
    })

    /// Turns bare URLs in the plain-text body into tappable links. Runs entirely on
    /// this device; SwiftUI opens the tapped link in the system browser.
    static func linkified(_ text: String) -> AttributedString {
        var attr = AttributedString(text)
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else {
            return attr
        }
        let ns = text as NSString
        for match in detector.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            guard let url = match.url,
                  let r = Range(match.range, in: text),
                  let lo = AttributedString.Index(r.lowerBound, within: attr),
                  let hi = AttributedString.Index(r.upperBound, within: attr) else { continue }
            attr[lo..<hi].link = url
            attr[lo..<hi].underlineStyle = .single
            // Explicit colour: the app's AccentColor asset is WHITE, so `.tint(.accentColor)`
            // drew every link white on white in light mode — the URLs were invisible.
            attr[lo..<hi].foregroundColor = Self.linkColor
        }
        return attr
    }

    /// Seals a received attachment to this identity's mail key and stores it in the
    /// encrypted drive — the same zero-access path a file picked in the drive uses, so
    /// the server bills the size and never learns the name or the bytes.
    private func saveAttachmentToDrive(_ a: ParsedMail.Attachment) async {
        savingAttachment = a.filename
        attachmentNote = nil
        defer { savingAttachment = nil }
        do {
            let meta = try JSONSerialization.data(withJSONObject: ["name": a.filename])
            let sealedName = try mail.seal(meta)
            let sealedBody = try mail.seal(a.data)
            _ = try await mail.uploadToDrive(encrypted: sealedBody, sealedName: sealedName)
            attachmentNoteIsError = false
            attachmentNote = "Saved “\(a.filename)” to your drive."
        } catch {
            attachmentNoteIsError = true
            attachmentNote = error.localizedDescription
        }
    }

    /// Parts an HTML body already shows inline (cid:) are not listed again.
    private func visibleAttachments(_ parsed: ParsedMail) -> [ParsedMail.Attachment] {
        guard let html = parsed.htmlBody else { return parsed.attachments }
        let shown = MailHTMLDocument.referencedContentIds(in: html)
        return parsed.attachments.filter { a in
            guard let cid = a.contentId?.lowercased() else { return true }
            return !shown.contains(cid)
        }
    }

    private var remoteImagesBar: some View {
        // Side by side normally; stacked at accessibility text sizes, where the
        // explanation was squeezed into a one-word-per-line column next to the button.
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 10))
        return layout {
            Image(systemName: "eye.slash").foregroundStyle(.secondary)
            Text("mail.images.blocked".localized)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            Menu {
                Button("mail.images.loadOnce".localized) { allowRemote = true }
                if let from = message.envelope?.from, !MailMessageParser.emailAddress(from: from).isEmpty {
                    Button(String(format: "mail.images.alwaysSender".localized,
                                  MailMessageParser.emailAddress(from: from))) {
                        MailRemoteImagesPolicy.allow(sender: from)
                        allowRemote = true
                    }
                }
            } label: {
                Text("mail.images.load".localized)
                    .font(.footnote.weight(.semibold))
            } primaryAction: {
                allowRemote = true
            }
            .fixedSize()
            .accessibilityIdentifier("mail.loadImages")
        }
        .padding(10)
        .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
        .tint(Self.linkColor)
    }

    var body: some View {
        ScrollViewReader { proxy in
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let envelope = message.envelope {
                    headerCard(envelope)
                }

                if let parsed {
                    if let html = parsed.htmlBody, !html.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        // HTML is what the sender designed; text/plain is only the
                        // fallback. Showing the plain alternative of a newsletter threw
                        // away its layout and every image.
                        if remoteContent && !allowRemote {
                            remoteImagesBar
                        }
                        let styled = MailHTMLDocument.isStyled(html)
                        MailHTMLView(html: MailHTMLDocument.build(html: html, mail: parsed, allowRemote: allowRemote),
                                     allowRemote: allowRemote, height: $htmlHeight)
                            .frame(height: htmlHeight)
                            // A newsletter that paints its own (light) colours keeps them
                            // in dark mode; give it a card so it does not bleed into the
                            // black background edge to edge.
                            .padding(styled ? (colorScheme == .dark ? 6 : 0) : 10)
                            .background(styled ? Color.white : Color(.secondarySystemGroupedBackground),
                                        in: RoundedRectangle(cornerRadius: styled ? 10 : 20, style: .continuous))
                    } else {
                        // Links in the body are made tappable so a reader can open one
                        // directly instead of copying it out. Detection is local
                        // (NSDataDetector); nothing about the message leaves the device.
                        SwiftUI.Group {
                            if parsed.textBody.isEmpty {
                                Text("(empty message)").foregroundStyle(.secondary)
                            } else {
                                MailPlainTextBody(text: parsed.textBody)
                            }
                        }
                        .mailCard(padding: 16)
                    }

                    let files = visibleAttachments(parsed)
                    if !files.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            MailAttachmentsSection(attachments: files, savingAttachment: savingAttachment) { a in
                                Task { await saveAttachmentToDrive(a) }
                            }
                            if let attachmentNote {
                                Text(attachmentNote)
                                    .font(.caption)
                                    .foregroundStyle(attachmentNoteIsError ? .red : .green)
                            }
                        }
                        .mailCard()
                    }
                } else if let error {
                    Text(error).foregroundStyle(.red).font(.footnote)
                        .mailCard()
                } else {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 24)
                }
                Color.clear.frame(height: 1).id("mail.bottom")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        #if DEBUG
        // Fixture harness: `-OSHIMailScrollTo bottom` to capture the end of a message.
        .task(id: "\(parsed != nil)-\(htmlHeight)") {
            guard UserDefaults.standard.string(forKey: "OSHIMailScrollTo") == "bottom", parsed != nil else { return }
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            proxy.scrollTo("mail.bottom", anchor: .bottom)
        }
        #endif
        }
        // __MAIL_LIQUID_GLASS_2026_11_25__ (back)  ( sender / subject )  (avatar), the
        // group header; reply / forward / move / delete in the composer-style bar.
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { readerToolbar }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            MailReaderActionBar(
                replyName: replyName,
                canReply: message.envelope != nil,
                canForward: parsed != nil,
                deleteLabel: currentFolder == "trash"
                    ? "mail.action.deleteForever".localized : "common.delete".localized,
                onReply: { showReply = true },
                onForward: { forwardDraft = makeForward() },
                onDelete: deleteTapped) {
                moveMenuItems
            }
        }
        .sheet(item: $forwardDraft) { draft in
            MailComposeView(to: draft.to, subject: draft.subject, messageBody: draft.body,
                            attachments: draft.attachments)
        }
        .confirmationDialog(
            "mail.confirm.deleteForever".localized,
            isPresented: $confirmPermanentDelete, titleVisibility: .visible) {
            Button("mail.action.deleteForever".localized, role: .destructive) {
                Task { _ = try? await mail.delete(id: message.id, permanent: true); close() }
            }
            Button("common.cancel".localized, role: .cancel) {}
        }
        .sheet(isPresented: $showReply) {
            if let envelope = message.envelope {
                MailComposeView(
                    to: MailMessageParser.emailAddress(from: envelope.from),
                    subject: envelope.subject.lowercased().hasPrefix("re:") ? envelope.subject : "Re: \(envelope.subject)",
                    inReplyTo: envelope.messageId)
            }
        }
        .task { await load() }
    }

    // MARK: __MAIL_LIQUID_GLASS_2026_11_25__ Header, bar, actions

    private var senderName: String {
        guard let from = message.envelope?.from else { return "mail.msg.sealedOther".localized }
        return MailMessageParser.displayName(from: from)
    }

    private var replyName: String {
        // First name only when there is one, as a chat reply line would say it.
        let name = senderName
        if name.contains("@") { return name }
        return name.split(separator: " ").first.map(String.init) ?? name
    }

    private var subjectLine: String {
        guard let e = message.envelope else { return "" }
        return e.subject.isEmpty ? "(no subject)" : e.subject
    }

    @ToolbarContentBuilder
    private var readerToolbar: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            GroupHeaderIsland(
                name: senderName,
                subtitle: subjectLine,
                accessibilityLabelText: senderName,
                accessibilityHintText: MailChromeText.detailsHint,
                identifier: "mail.reader.island",
                onTap: { withAnimation(.easeInOut(duration: 0.2)) { showDetails.toggle() } })
        }
        if let from = message.envelope?.from {
            ToolbarItem(placement: .navigationBarTrailing) {
                GroupHeaderAvatarButton(
                    name: senderName,
                    sections: [[
                        OverflowMenuItem(id: "copy", title: MailChromeText.copyAddress, symbol: "doc.on.doc"),
                        OverflowMenuItem(id: "write", title: MailChromeText.newEmail, symbol: "square.and.pencil"),
                    ]],
                    onTap: { withAnimation(.easeInOut(duration: 0.2)) { showDetails.toggle() } },
                    onSelect: { id in
                        let address = MailMessageParser.emailAddress(from: from)
                        if id == "copy" {
                            UIPasteboard.general.string = address
                        } else if id == "write" {
                            forwardDraft = ForwardDraft(to: address, subject: "", body: "", attachments: [])
                        }
                    },
                    accessibilityLabelText: senderName,
                    identifier: "mail.reader.avatar") {
                    MailSenderAvatar(header: from, size: 38)
                }
            }
        }
    }

    /// Subject, then who sent it (avatar, name, address) and when; the island or
    /// the avatar shows the full To / Cc / date.
    private func headerCard(_ envelope: OSHIMailMessage.Envelope) -> some View {
        let name = MailMessageParser.displayName(from: envelope.from)
        let address = MailMessageParser.emailAddress(from: envelope.from)
        return VStack(alignment: .leading, spacing: 12) {
            Text(envelope.subject.isEmpty ? "(no subject)" : envelope.subject)
                .font(.title3.bold())
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            HStack(alignment: .center, spacing: 12) {
                if !dynamicTypeSize.isAccessibilitySize {
                    MailSenderAvatar(header: envelope.from, size: 40)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(name).font(.subheadline.weight(.semibold))
                    if address != name {
                        Text(address).font(.caption).foregroundStyle(.secondary)
                            .lineLimit(showDetails ? nil : 1)
                            .truncationMode(.middle)
                    }
                }
                Spacer(minLength: 4)
                if !dynamicTypeSize.isAccessibilitySize {
                    Text(message.receivedAt, format: .dateTime.day().month(.abbreviated))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .accessibilityElement(children: .combine)
            if showDetails || dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 3) {
                    Text(String(format: "mail.msg.to".localized, envelope.to))
                    if let cc = envelope.cc, !cc.isEmpty {
                        Text("Cc: \(cc)")
                    }
                    Text(message.receivedAt, format: .dateTime)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .transition(.opacity)
            } else {
                Text(String(format: "mail.msg.to".localized, envelope.to))
                    .font(.caption).foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .mailCard(padding: 16)
    }

    /// What the old ⋯ menu offered, minus Delete (its own button now).
    @ViewBuilder
    private var moveMenuItems: some View {
        if currentFolder == "trash" {
            Button {
                Task { try? await mail.move(id: message.id, to: "inbox"); close() }
            } label: {
                Label("mail.action.restore".localized, systemImage: "tray.and.arrow.down")
            }
        } else {
            if currentFolder == "archive" {
                Button {
                    Task { try? await mail.move(id: message.id, to: "inbox"); close() }
                } label: {
                    Label("mail.action.restoreInbox".localized, systemImage: "tray.and.arrow.down")
                }
            } else if currentFolder != "sent" && currentFolder != "draft" {
                Button {
                    Task { try? await mail.move(id: message.id, to: "archive"); close() }
                } label: {
                    Label("mail.action.archive".localized, systemImage: "archivebox")
                }
            }
            Button {
                Task { try? await mail.markSeen(id: message.id, seen: false); close() }
            } label: {
                Label("mail.action.markUnread".localized, systemImage: "envelope.badge")
            }
        }
    }

    private func deleteTapped() {
        if currentFolder == "trash" {
            confirmPermanentDelete = true
        } else {
            Task { _ = try? await mail.delete(id: message.id); close() }
        }
    }

    struct ForwardDraft: Identifiable {
        let id = UUID()
        var to: String = ""
        let subject: String
        let body: String
        let attachments: [OSHIMailClient.Attachment]
    }

    /// Forward = a new message: "Fwd:" subject, the original quoted as text under
    /// a header block, and the attachments the reader lists (within the send cap).
    /// Built from what this device already decrypted; nothing new is fetched.
    private func makeForward() -> ForwardDraft? {
        guard let parsed else { return nil }
        let e = message.envelope
        let subject = e?.subject ?? parsed.subject
        let fwdSubject = subject.lowercased().hasPrefix("fwd:") || subject.lowercased().hasPrefix("fw:")
            ? subject : "Fwd: \(subject)"
        var text = parsed.textBody
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, let html = parsed.htmlBody {
            text = Self.plainText(fromHTML: html)
        }
        var header = "\n\n---------- \("mail.forward.header".localized) ----------\n"
        header += "From: \(e?.from ?? parsed.from)\n"
        header += "Date: \(message.receivedAt.formatted(date: .abbreviated, time: .shortened))\n"
        header += "Subject: \(subject)\n"
        header += "To: \(e?.to ?? parsed.to)\n\n"
        var files: [OSHIMailClient.Attachment] = []
        var total = 0
        for a in visibleAttachments(parsed) {
            guard total + a.data.count <= OSHIMailClient.maxAttachmentTotalBytes else { continue }
            total += a.data.count
            files.append(.init(filename: a.filename, mimeType: a.mimeType, data: a.data))
        }
        return ForwardDraft(subject: fwdSubject, body: header + text, attachments: files)
    }

    /// Tags stripped by a regex — never `NSAttributedString(html:)`, which would
    /// fetch the remote images this reader blocks.
    static func plainText(fromHTML html: String) -> String {
        var s = html.replacingOccurrences(of: #"(?is)<(script|style|head)\b.*?</\1>"#, with: "", options: .regularExpression)
        s = s.replacingOccurrences(of: #"(?i)<br\s*/?>|</p>|</div>|</tr>|</h[1-6]>|</li>"#, with: "\n", options: .regularExpression)
        s = s.replacingOccurrences(of: #"<[^>]+>"#, with: "", options: .regularExpression)
        for (entity, char) in [("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", "\""), ("&#39;", "'")] {
            s = s.replacingOccurrences(of: entity, with: char)
        }
        s = s.replacingOccurrences(of: #"\n{3,}"#, with: "\n\n", options: .regularExpression)
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func load() async {
        do {
            let data = try await mail.fetchMessage(id: message.id)
            // Off the main thread: a message with a 20 MB photo is 27 MB of base64, and
            // parsing it here froze the UI for seconds.
            let parsedMail = await Task.detached(priority: .userInitiated) {
                MailMessageParser.parse(data)
            }.value
            if let html = parsedMail.htmlBody {
                remoteContent = MailHTMLDocument.hasRemoteContent(html)
                allowRemote = remoteContent && MailRemoteImagesPolicy.isAllowed(sender: parsedMail.from)
            }
            parsed = parsedMail
            // Cached so "deep" search can match inside this message without ever
            // re-fetching it, and without the plaintext leaving this device.
            mail.cacheBody(id: message.id, "\(parsedMail.subject)\n\(parsedMail.from)\n\(parsedMail.textBody)")
            if !message.seen { try? await mail.markSeen(id: message.id) }
        } catch {
            self.error = error.localizedDescription
        }
    }
}

// MARK: - Composing

struct MailComposeView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var mail = OSHIMailClient.shared

    @State private var to: String
    @State private var subject: String
    @State private var messageBody: String
    let inReplyTo: String?

    @State private var sending = false
    @State private var error: String?
    @State private var selectedFrom: String?
    @State private var attachments: [OSHIMailClient.Attachment] = []
    @State private var showFilePicker = false
    @State private var showDrivePicker = false

    // MARK: Drafts — autosaved, debounced ~1.5s
    @State private var draftId: String?
    @State private var draftStatus: String?
    @State private var draftSaveTask: Task<Void, Never>?
    @State private var didSend = false

    // MARK: Address book — suggestions built locally from decrypted envelopes
    @State private var toSuggestions: [OSHIMailClient.MailContact] = []

    @FocusState private var toFocused: Bool
    @FocusState private var subjectFocused: Bool
    @FocusState private var bodyFocused: Bool
    @ScaledMetric(relativeTo: .body) private var bodyMinHeight: CGFloat = 220

    /// Every recipient, chips and what is still being typed.
    private var recipients: [String] { MailRecipientField.recipients(to) }

    /// Explicit init so a caller can prefill To/Subject/Body (reply, or reopening a
    /// draft) while the fields stay `@State` — `State(initialValue:)` is only honoured
    /// the first time the view is constructed, exactly like SwiftUI expects.
    init(to: String = "", subject: String = "", messageBody: String = "",
         inReplyTo: String? = nil, draftId: String? = nil,
         attachments: [OSHIMailClient.Attachment] = []) {
        // __MAIL_LIQUID_GLASS_2026_11_25__ A prefilled recipient (reply, draft) opens
        // as a chip: every part before the last comma is one (`MailRecipientField`).
        let trimmedTo = to.trimmingCharacters(in: .whitespaces)
        _to = State(initialValue: trimmedTo.isEmpty || trimmedTo.hasSuffix(",") ? trimmedTo : trimmedTo + ", ")
        _attachments = State(initialValue: attachments)
        _subject = State(initialValue: subject)
        _messageBody = State(initialValue: messageBody)
        self.inReplyTo = inReplyTo
        _draftId = State(initialValue: draftId)
    }

    private var fromAddress: String? {
        guard let account = mail.account else { return nil }
        return selectedFrom ?? account.address
    }

    /// __MAIL_LIQUID_GLASS_2026_11_25__ The island: « New message / from-address ».
    /// With aliases it is the From picker (a menu); otherwise a plain title.
    @ViewBuilder
    private var composeIsland: some View {
        let label = HeaderIslandLabel(name: "mail.compose.title".localized, subtitle: fromAddress ?? "")
        if let account = mail.account, !account.aliases.isEmpty {
            Menu {
                Picker(MailChromeText.from, selection: Binding(
                    get: { selectedFrom ?? account.address },
                    set: { selectedFrom = $0 })) {
                    Text(account.address).tag(account.address)
                    ForEach(account.aliases, id: \.self) { alias in
                        Text(alias).tag(alias)
                    }
                }
            } label: { label }
            .buttonStyle(.plain)
            .accessibilityLabel(MailChromeText.from)
            .accessibilityValue(fromAddress ?? "")
            .accessibilityIdentifier("mail.compose.from")
        } else {
            label
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isHeader)
        }
    }

    private var suggestionsList: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(toSuggestions) { contact in
                Button {
                    applySuggestion(contact.address)
                } label: {
                    HStack(spacing: 10) {
                        MailSenderAvatar(header: contact.name.isEmpty ? contact.address : "\(contact.name) <\(contact.address)>",
                                         size: 30)
                        VStack(alignment: .leading, spacing: 1) {
                            if !contact.name.isEmpty {
                                Text(contact.name).font(.callout)
                            }
                            Text(contact.address)
                                .font(contact.name.isEmpty ? .callout : .caption)
                                .foregroundStyle(contact.name.isEmpty ? .primary : .secondary)
                        }
                        Spacer()
                    }
                    .padding(.vertical, 6)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var fieldsCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            MailRecipientField(to: $to, focus: $toFocused)
                .padding(.vertical, 8)
                .onChange(of: to) { _, new in
                    updateSuggestions(new)
                    scheduleDraftSave()
                }
            if !toSuggestions.isEmpty {
                Divider()
                suggestionsList.padding(.vertical, 4)
            }
            Divider()
            TextField("mail.compose.subject".localized, text: $subject, axis: .vertical)
                .font(.body.weight(.semibold))
                .privateTypingComposer()  // __KEYBOARD_PRIVACY_2026_10_01__ no autocorrect learning when Private typing is on
                .focused($subjectFocused)
                .padding(.vertical, 12)
                .onChange(of: subject) { _, _ in scheduleDraftSave() }
                .accessibilityIdentifier("mail.compose.subject")
            Divider()
            TextEditor(text: $messageBody)
                .privateTypingComposer()  // __KEYBOARD_PRIVACY_2026_10_01__ no autocorrect learning when Private typing is on
                .focused($bodyFocused)
                .scrollContentBackground(.hidden)
                .frame(minHeight: bodyMinHeight)
                .padding(.horizontal, -5)
                .padding(.vertical, 6)
                .onChange(of: messageBody) { _, _ in scheduleDraftSave() }
                .accessibilityIdentifier("mail.compose.body")
        }
        .mailCard(padding: 14)
    }

    private var attachmentsCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(attachments.enumerated()), id: \.offset) { index, attachment in
                HStack(spacing: 10) {
                    Image(systemName: "paperclip").foregroundStyle(Theme.brand)
                        .accessibilityHidden(true)
                    Text(attachment.filename).lineLimit(1).truncationMode(.middle)
                    Spacer()
                    Text(ByteCountFormatter.string(fromByteCount: Int64(attachment.data.count), countStyle: .file))
                        .font(.caption).foregroundStyle(.secondary)
                    Button { attachments.remove(at: index) } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                            .frame(minWidth: 32, minHeight: 32)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(MailChromeText.removeRecipient(attachment.filename))
                }
                .font(.footnote)
            }
            let total = attachments.reduce(0) { $0 + $1.data.count }
            Text(String(format: "mail.compose.attached".localized, ByteCountFormatter.string(fromByteCount: Int64(total), countStyle: .file), ByteCountFormatter.string(fromByteCount: Int64(OSHIMailClient.maxAttachmentTotalBytes), countStyle: .file)))
                .font(.caption2).foregroundStyle(.secondary)
        }
        .mailCard()
    }

    /// The composer pill of a conversation: attach (file, drive) inside the pill
    /// with the monthly allowance, Send as the brand circle beside it.
    private var composeBar: some View {
        HStack(alignment: .bottom, spacing: 8) {
            ComposerPill(focused: false, tint: Theme.brand) {
                Button { showFilePicker = true } label: {
                    Image(systemName: "paperclip")
                        .font(.body.weight(.medium))
                        .foregroundStyle(MailMessageView.linkColor)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("mail.compose.attachFile".localized)
                .accessibilityIdentifier("mail.compose.attachFile")
                Button { showDrivePicker = true } label: {
                    Image(systemName: "folder")
                        .font(.body.weight(.medium))
                        .foregroundStyle(MailMessageView.linkColor)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("mail.compose.attachDrive".localized)
                .accessibilityIdentifier("mail.compose.attachDrive")
                Spacer(minLength: 4)
                // The status line gives way (rather than truncating) when it does not fit.
                ViewThatFits(in: .horizontal) {
                    SwiftUI.Group {
                        if let draftStatus {
                            Text(draftStatus)
                        } else if let account = mail.account {
                            Text(String(format: "mail.quota.messages".localized, account.sending.remaining, account.sending.quota))
                                .foregroundStyle(account.sending.remaining == 0 ? Color.red : Color.secondary)
                        }
                    }
                    .fixedSize()
                    Color.clear.frame(width: 0, height: 0)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(minHeight: 44)
                .padding(.trailing, 12)
            }
            .layoutPriority(1)

            Button { Task { await send() } } label: {
                if sending {
                    ProgressView()
                        .frame(width: 48, height: 48)
                } else {
                    ComposerRoundButtonFace(systemImage: "arrow.up", primary: true,
                                            enabled: !recipients.isEmpty)
                }
            }
            .buttonStyle(PressScaleButtonStyle())
            .disabled(recipients.isEmpty || sending)
            .accessibilityLabel("common.send".localized)
            .accessibilityIdentifier("mail.compose.send")
        }
        .padding(.horizontal)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)  // chrome, see MailReaderActionBar
        .oledGlassBar(fallback: .ultraThinMaterial)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    fieldsCard
                    if !attachments.isEmpty { attachmentsCard }
                    if let error {
                        Text(error).font(.footnote).foregroundStyle(.red)
                            .mailCard()
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            .safeAreaInset(edge: .bottom, spacing: 0) { composeBar }
            .fileImporter(isPresented: $showFilePicker, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
                addFiles(result)
            }
            .sheet(isPresented: $showDrivePicker) {
                MailDriveAttachPicker { attachment in
                    appendIfItFits(attachment)
                    showDrivePicker = false
                }
            }
            .navigationTitle("mail.compose.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) { cancelAndSaveDraft() }
                }
                ToolbarItem(placement: .principal) { composeIsland }
            }
            // The lifted violet: the brand itself is too dark for "Cancel" on dark glass.
            .tint(MailMessageView.linkColor)
            .onAppear {
                // Straight to what is missing: the address, or (reply) the text.
                if recipients.isEmpty { toFocused = true } else if !subject.isEmpty { bodyFocused = true }
            }
        }
        // Covers interactive (swipe-down) dismissal too, which the Cancel button's
        // action alone would not catch — dismissing the composer must SAVE, not discard.
        .onDisappear { saveDraftOnDisappear() }
    }

    // MARK: Address book

    private func updateSuggestions(_ value: String) {
        let lastToken = value.split(separator: ",", omittingEmptySubsequences: false)
            .last.map { $0.trimmingCharacters(in: .whitespaces) } ?? value
        guard !lastToken.isEmpty else { toSuggestions = []; return }
        let q = lastToken.lowercased()
        let already = Set(value.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces).lowercased() })
        toSuggestions = mail.contacts
            .filter { !already.contains($0.address) && ($0.address.contains(q) || $0.name.lowercased().contains(q)) }
            .prefix(5)
            .map { $0 }
    }

    private func applySuggestion(_ address: String) {
        var parts = to.split(separator: ",", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
        if parts.isEmpty {
            to = address + ", "
        } else {
            parts[parts.count - 1] = address
            // Trailing ", ": the picked address becomes a chip, the field is free again.
            to = parts.joined(separator: ", ") + ", "
        }
        toSuggestions = []
    }

    // MARK: Drafts

    private func scheduleDraftSave() {
        draftSaveTask?.cancel()
        guard !(to.isEmpty && subject.isEmpty && messageBody.isEmpty) else { return }
        draftStatus = "Saving…"
        draftSaveTask = Task {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled else { return }
            await saveDraftNow(silent: false)
        }
    }

    @discardableResult
    private func saveDraftNow(silent: Bool) async -> Bool {
        guard !(to.isEmpty && subject.isEmpty && messageBody.isEmpty) else { return false }
        do {
            let id = try await mail.saveDraft(id: draftId, to: recipients.joined(separator: ", "),
                                              subject: subject, body: messageBody)
            draftId = id
            if !silent { draftStatus = "Draft saved" }
            return true
        } catch {
            if !silent { draftStatus = nil }
            return false
        }
    }

    private func cancelAndSaveDraft() {
        draftSaveTask?.cancel()
        Task {
            await saveDraftNow(silent: true)
            dismiss()
        }
    }

    private func saveDraftOnDisappear() {
        guard !didSend else { return }
        draftSaveTask?.cancel()
        Task { await saveDraftNow(silent: true) }
    }

    private func addFiles(_ result: Result<[URL], Error>) {
        do {
            for url in try result.get() {
                // A file from another app arrives security-scoped; reading it without
                // this yields nothing and looks like an empty attachment.
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                let data = try Data(contentsOf: url)
                appendIfItFits(.init(filename: url.lastPathComponent,
                                     mimeType: mimeType(for: url),
                                     data: data))
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func appendIfItFits(_ attachment: OSHIMailClient.Attachment) {
        let total = attachments.reduce(0) { $0 + $1.data.count } + attachment.data.count
        guard total <= OSHIMailClient.maxAttachmentTotalBytes else {
            error = "Attachments can total \(ByteCountFormatter.string(fromByteCount: Int64(OSHIMailClient.maxAttachmentTotalBytes), countStyle: .file))."
            return
        }
        error = nil
        attachments.append(attachment)
    }

    private func mimeType(for url: URL) -> String {
        if let type = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType { return type }
        return "application/octet-stream"
    }

    private func send() async {
        sending = true
        error = nil
        // One address per comma; a stray space around them is the user's, not an error.
        let recipients = self.recipients
        do {
            _ = try await mail.sendMail(to: recipients,
                                        subject: subject,
                                        text: messageBody,
                                        from: selectedFrom,
                                        inReplyTo: inReplyTo,
                                        attachments: attachments)
            // The draft became a real, sent message — remove it so it does not linger
            // in the Drafts folder as a stale duplicate.
            if let draftId {
                _ = try? await mail.delete(id: draftId, permanent: true)
            }
            draftSaveTask?.cancel()
            didSend = true
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
        sending = false
    }
}

// MARK: - Account & aliases

struct MailAccountView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var identityManager: IdentityManager
    @StateObject private var mail = OSHIMailClient.shared

    @State private var newAlias = ""
    @State private var working = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List {
                if let account = mail.account {
                    Section("mail.section.address".localized) {
                        Text(account.address).font(.callout.monospaced())
                    }

                    Section {
                        ForEach(account.aliases, id: \.self) { alias in
                            Text(alias).font(.callout.monospaced())
                        }
                        .onDelete { indexSet in
                            Task {
                                for i in indexSet where i < account.aliases.count {
                                    try? await mail.removeAlias(account.aliases[i])
                                }
                            }
                        }

                        if account.aliases.count < account.aliasLimit {
                            HStack {
                                TextField("mail.alias.newPlaceholder".localized, text: $newAlias)
                                    .textInputAutocapitalization(.never)
                                    .autocorrectionDisabled()
                                Text("@\(OSHIMailClient.mailDomain)")
                                    .font(.caption).foregroundStyle(.secondary).lineLimit(1).minimumScaleFactor(0.6)
                                Button("common.add".localized) { Task { await add() } }
                                    .disabled(newAlias.count < 3 || working)
                            }
                            // A one-tap alias that is your public key instead of a chosen
                            // name — harder to link to you than "boulot" or your first name.
                            if let keyLocal = mail.keyBasedLocalpart {
                                Button {
                                    newAlias = keyLocal
                                } label: {
                                    Label(String(format: "mail.alias.usekey".localized, keyLocal), systemImage: "key.fill")
                                        .font(.footnote)
                                }
                            }
                        }
                    } header: {
                        Text(String(format: "mail.alias.count".localized, account.aliases.count, account.aliasLimit))
                    } footer: {
                        Text("mail.alias.explain".localized)
                    }

                    Section {
                        NavigationLink { MailBrowserPairingView() } label: {
                            Label("mail.browser.connect".localized, systemImage: "desktopcomputer")
                        }
                    } footer: {
                        Text("mail.browser.hint".localized)
                    }

                    Section("mail.section.usage".localized) {
                        MailQuotaRow(account: account, mail: mail)
                    }
                }

                if let error {
                    Text(error).font(.footnote).foregroundStyle(.red)
                }
            }
            .navigationTitle("mail.account.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("common.done".localized) { dismiss() } }
            }
        }
    }

    private func add() async {
        working = true
        error = nil
        do {
            _ = try await mail.addAlias(newAlias.lowercased())
            newAlias = ""
        } catch {
            self.error = error.localizedDescription
        }
        working = false
    }
}

// MARK: - Mailbox freshness

extension MailView {
    /// __MAILBOX_FRESHNESS_BADGE_2026_11_04__ Whether what you are looking at
    /// is current.
    ///
    /// The mailbox used to give no sign of this. An inbox that FAILED to
    /// refresh looked exactly like an inbox with nothing new, and both looked
    /// exactly like one that had never loaded — three states rendered as one,
    /// so the only honest reading of a quiet mailbox was "I do not know".
    ///
    /// Four states, each wired to something real: refreshing, refreshed (with
    /// the time, formatted by `RelativeDateTimeFormatter` so it is localised
    /// without a string of ours), failed, and never-tried, which shows nothing
    /// rather than a reassurance it has not earned.
    @ViewBuilder
    var freshnessBadge: some View {
        if mail.isRefreshing {
            badgeRow(icon: "arrow.triangle.2.circlepath",
                     text: "mail.title".localized,
                     tint: .secondary)
        } else if mail.lastRefreshFailed {
            badgeRow(icon: "exclamationmark.triangle.fill",
                     text: "mail.refresh_failed".localized,
                     tint: .orange)
        } else if let at = mail.lastSyncedAt {
            badgeRow(icon: "checkmark.circle.fill",
                     text: "mail.up_to_date".localized + " · " + Self.relative(at),
                     tint: .green)
        }
        // Never synced in this session: nothing. A badge that says "up to
        // date" before the first successful round trip is the failure mode
        // this exists to remove, not a friendlier empty state.
    }

    /// "now", not "in 0 seconds", right after a refresh.
    static func relative(_ date: Date) -> String {
        let fmt = RelativeDateTimeFormatter()
        fmt.dateTimeStyle = .named
        return fmt.localizedString(for: min(date, Date()), relativeTo: Date())
    }

    private func badgeRow(icon: String, text: String, tint: Color) -> some View {
        HStack(spacing: 6) {
            Image(systemName: icon)
                .font(.caption2)
                .foregroundColor(tint)
            Text(text)
                .font(.caption2)
                .foregroundColor(.secondary)
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Reading: HTML body

/// Builds the document the reader's web view loads from a received HTML body.
///
/// Privacy first: a received email is hostile content. The document carries a
/// Content-Security-Policy that allows NO network load at all unless the reader asked
/// for remote images (then images only, never scripts, frames, fonts or stylesheets),
/// and `MailHTMLView` additionally runs a WKContentRuleList that blocks every http(s)
/// request and a navigation delegate that refuses every navigation but the first.
/// A tracking pixel or a remote logo therefore costs the reader nothing — no IP
/// address, no "opened at" — until they tap "Load images".
///
/// Pure (no WebKit) so the rewriting is unit-testable.
enum MailHTMLDocument {

    /// `cid:` images (multipart/related) are inlined as `data:` URIs from the parsed
    /// parts, so they show without any network and without a custom URL scheme.
    static func build(html: String, mail: ParsedMail, allowRemote: Bool) -> String {
        let body = inlineContentIds(in: html, mail: mail)
        let styled = isStyled(html)
        let imgSrc = allowRemote ? "data: https: http:" : "data:"
        let csp = "default-src 'none'; img-src \(imgSrc); style-src 'unsafe-inline' data:; font-src data:; media-src data:; form-action 'none'; frame-src 'none'; child-src 'none'"
        // Unstyled mail (Gmail-composed, Apple Mail, a reply) follows the system
        // appearance and Dynamic Type like the rest of the app. Mail that paints its own
        // colours is kept light: inverting a designed newsletter makes dark text land on
        // dark backgrounds the sender chose.
        let scheme = styled
            ? ":root{color-scheme:light only} html{background:#ffffff}"
            : ":root{color-scheme:light dark} html,body{background:transparent} body{font:-apple-system-body}"
        let css = """
        \(scheme)
        html{-webkit-text-size-adjust:100%;overflow:hidden;font-family:-apple-system,Helvetica,sans-serif}
        body{margin:0;padding:0;overflow-wrap:break-word;word-wrap:break-word}
        #oshi-root{padding:0 2px;overflow:hidden}
        img{max-width:100% !important;height:auto !important}
        pre,code{white-space:pre-wrap !important;word-break:break-word}
        iframe,frame,frameset,object,embed,applet{display:none !important}
        blockquote{margin-left:0.6em;margin-right:0;padding-left:0.8em;border-left:3px solid rgba(127,127,127,0.45)}
        a{word-break:break-word}
        """
        return """
        <!DOCTYPE html><html><head><meta charset="utf-8">\
        <meta http-equiv="Content-Security-Policy" content="\(csp)">\
        <meta http-equiv="x-dns-prefetch-control" content="off">\
        <meta name="viewport" content="width=device-width,initial-scale=1,shrink-to-fit=no">\
        <meta name="referrer" content="no-referrer">\
        <style>\(css)</style></head><body><div id="oshi-root">\(body)</div></body></html>
        """
    }

    /// Whether the body references anything that would be fetched from the network —
    /// what the "Load images" bar is offered for.
    static func hasRemoteContent(_ html: String) -> Bool {
        let pattern = #"(?i)(\b(src|background|poster)\s*=\s*["']?\s*(https?:)?//)|(url\(\s*["']?\s*(https?:)?//)|(<link\b[^>]*\bhref\s*=\s*["']?\s*(https?:)?//)"#
        return html.range(of: pattern, options: .regularExpression) != nil
    }

    /// Content-IDs the HTML displays inline; those parts are not listed again as
    /// attachments under the body.
    static func referencedContentIds(in html: String) -> Set<String> {
        guard let re = try? NSRegularExpression(pattern: #"(?i)cid:([^"'\s)>]+)"#) else { return [] }
        let ns = html as NSString
        var out = Set<String>()
        for m in re.matches(in: html, range: NSRange(location: 0, length: ns.length)) {
            let raw = ns.substring(with: m.range(at: 1))
            out.insert((raw.removingPercentEncoding ?? raw).lowercased())
        }
        return out
    }

    static func inlineContentIds(in html: String, mail: ParsedMail) -> String {
        guard html.range(of: "cid:", options: .caseInsensitive) != nil,
              let re = try? NSRegularExpression(pattern: #"(?i)cid:([^"'\s)>]+)"#) else { return html }
        let ns = html as NSString
        var out = ""
        var last = 0
        for m in re.matches(in: html, range: NSRange(location: 0, length: ns.length)) {
            out += ns.substring(with: NSRange(location: last, length: m.range.location - last))
            let raw = ns.substring(with: m.range(at: 1))
            if let part = mail.attachment(forContentId: raw.removingPercentEncoding ?? raw) {
                let type = part.mimeType.hasPrefix("image/") ? part.mimeType : "image/png"
                out += "data:\(type);base64,\(part.data.base64EncodedString())"
            } else {
                out += ns.substring(with: m.range)
            }
            last = m.range.location + m.range.length
        }
        out += ns.substring(from: last)
        return out
    }

    /// The sender painted its own colours (a newsletter, a receipt).
    static func isStyled(_ html: String) -> Bool {
        html.range(of: #"(?i)(bgcolor\s*=|background(-color)?\s*:|[;"'\s{]color\s*:|<style\b)"#,
                   options: .regularExpression) != nil
    }
}

/// The senders whose remote images the reader chose to always load. Stored on this
/// device only.
enum MailRemoteImagesPolicy {
    private static let key = "mail.remoteImages.allowedSenders"

    static func isAllowed(sender: String) -> Bool {
        let address = MailMessageParser.emailAddress(from: sender).lowercased()
        guard !address.isEmpty else { return false }
        return (UserDefaults.standard.stringArray(forKey: key) ?? []).contains(address)
    }

    static func allow(sender: String) {
        let address = MailMessageParser.emailAddress(from: sender).lowercased()
        guard !address.isEmpty else { return }
        var list = UserDefaults.standard.stringArray(forKey: key) ?? []
        if !list.contains(address) { list.append(address) }
        UserDefaults.standard.set(list, forKey: key)
    }
}

/// A received HTML body, sized to its content so it scrolls with the header and the
/// attachments in one ScrollView.
///
/// Locked down for hostile content: page JavaScript off, a non-persistent data store
/// (no cookies, no storage), every http(s) load blocked by a content rule list unless
/// the reader allowed remote images, and every navigation refused — a tapped http(s),
/// mailto or tel link is handed to the system instead; `javascript:`, `file:` and
/// anything else goes nowhere.
struct MailHTMLView: UIViewRepresentable {
    let html: String
    let allowRemote: Bool
    @Binding var height: CGFloat

    func makeCoordinator() -> Coordinator { Coordinator(height: $height) }

    func makeUIView(context: Context) -> FittingWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.defaultWebpagePreferences.allowsContentJavaScript = false
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        config.dataDetectorTypes = []
        config.suppressesIncrementalRendering = true
        let webView = FittingWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false
        webView.allowsLinkPreview = false
        webView.onWidthChange = { [weak coordinator = context.coordinator] web in coordinator?.fit(web) }
        context.coordinator.load(html: html, allowRemote: allowRemote, into: webView)
        return webView
    }

    func updateUIView(_ webView: FittingWebView, context: Context) {
        context.coordinator.height = $height
        context.coordinator.load(html: html, allowRemote: allowRemote, into: webView)
    }

    final class FittingWebView: WKWebView {
        var onWidthChange: ((WKWebView) -> Void)?
        private var lastWidth: CGFloat = 0
        override func layoutSubviews() {
            super.layoutSubviews()
            if bounds.width > 0, abs(bounds.width - lastWidth) > 0.5 {
                lastWidth = bounds.width
                onWidthChange?(self)
            }
        }
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        var height: Binding<CGFloat>
        private var loadedKey: String?
        private var finished = false

        init(height: Binding<CGFloat>) { self.height = height }

        private static var blockList: WKContentRuleList?
        private static let blockRules = """
        [{"trigger":{"url-filter":"^https?:"},"action":{"type":"block"}},
         {"trigger":{"url-filter":"^wss?:"},"action":{"type":"block"}},
         {"trigger":{"url-filter":"^ftp:"},"action":{"type":"block"}}]
        """

        func load(html: String, allowRemote: Bool, into webView: WKWebView) {
            let key = "\(allowRemote)|\(html.count)|\(html.hashValue)"
            guard key != loadedKey else { return }
            loadedKey = key
            finished = false
            let controller = webView.configuration.userContentController
            controller.removeAllContentRuleLists()
            let go = { webView.loadHTMLString(html, baseURL: nil) }
            if allowRemote { go(); return }
            if let list = Self.blockList {
                controller.add(list)
                go()
                return
            }
            WKContentRuleListStore.default().compileContentRuleList(
                forIdentifier: "oshi.mail.block-remote", encodedContentRuleList: Self.blockRules) { list, _ in
                DispatchQueue.main.async {
                    if let list { Self.blockList = list; controller.add(list) }
                    // The CSP in the document blocks remote loads on its own; the rule
                    // list is the second lock, so a compile failure still loads.
                    go()
                }
            }
        }

        // Reads every width first and writes after, so the page is laid out once
        // instead of once per element. Tables and blocks wider than the screen are
        // brought down to 100%; only what still overflows is scaled as a whole.
        private static let fitScript = """
        (function(){
          var root = document.getElementById('oshi-root'); if (!root) return 0;
          root.style.transform = ''; root.style.width = '';
          var W = document.documentElement.clientWidth;
          var all = root.getElementsByTagName('*'), wide = [];
          for (var i = 0; i < all.length; i++) {
            var e = all[i], t = e.tagName;
            if (t === 'IMG') continue;
            var aw = parseInt(e.getAttribute('width') || '0', 10);
            if (aw > W || e.offsetWidth > W) wide.push(e);
          }
          for (var j = 0; j < wide.length; j++) {
            var e = wide[j], t = e.tagName;
            if (e.hasAttribute('width')) e.removeAttribute('width');
            e.style.minWidth = '0';
            if (t === 'TD' || t === 'TH') { e.style.width = 'auto'; }
            else { e.style.width = '100%'; e.style.maxWidth = '100%'; }
          }
          var z = 1, sw = root.scrollWidth;
          if (sw > W + 1) {
            z = W / sw;
            root.style.width = sw + 'px';
            root.style.transformOrigin = '0 0';
            root.style.transform = 'scale(' + z + ')';
          }
          return Math.ceil(root.offsetHeight * z) + 4;
        })()
        """

        func fit(_ webView: WKWebView) {
            guard finished else { return }
            webView.evaluateJavaScript(Self.fitScript) { [weak self] result, _ in
                guard let self, let h = result as? Double, h > 0 else { return }
                if abs(self.height.wrappedValue - CGFloat(h)) > 1 {
                    self.height.wrappedValue = CGFloat(h)
                }
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            finished = true
            fit(webView)
            // Late layout (web fonts are blocked, but image decoding is not synchronous).
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self, weak webView] in
                if let webView { self?.fit(webView) }
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            let url = action.request.url
            // The document itself (loadHTMLString → about:blank) is the one load allowed.
            if action.navigationType == .other, action.targetFrame?.isMainFrame == true,
               url?.absoluteString == "about:blank", !finished {
                decisionHandler(.allow)
                return
            }
            decisionHandler(.cancel)
            // Only a link the reader TAPPED leaves the mail view, and only to schemes the
            // system handles safely. A meta refresh, an iframe or a form gets nowhere.
            guard action.navigationType == .linkActivated, let url,
                  let scheme = url.scheme?.lowercased(),
                  ["http", "https", "mailto", "tel"].contains(scheme) else { return }
            UIApplication.shared.open(url)
        }

        // target="_blank": same rule, never a second web view.
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            if let url = action.request.url, let scheme = url.scheme?.lowercased(),
               ["http", "https", "mailto", "tel"].contains(scheme) {
                UIApplication.shared.open(url)
            }
            return nil
        }
    }
}

// MARK: - Reading: plain-text body

/// A plain-text body laid out the way mail is read: each paragraph in its own writing
/// direction (an Arabic or Hebrew paragraph aligns right even in an English UI, and an
/// English one aligns left in an Arabic UI), quoted lines (`>`) set off with a bar,
/// and the signature after "-- " dimmed.
struct MailPlainTextBody: View {
    let text: String

    struct Block: Identifiable {
        enum Kind: Equatable { case body, quote(Int), signature }
        let id: Int
        let kind: Kind
        let text: String
        let rightToLeft: Bool
    }

    static func blocks(_ raw: String) -> [Block] {
        let lines = raw.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        var blocks: [Block] = []
        var current: [String] = []
        var currentKind: Block.Kind = .body
        var inSignature = false

        func flush() {
            // Trim blank lines at the edges of a block; keep the ones inside.
            while current.first?.trimmingCharacters(in: .whitespaces).isEmpty == true { current.removeFirst() }
            while current.last?.trimmingCharacters(in: .whitespaces).isEmpty == true { current.removeLast() }
            if !current.isEmpty {
                let joined = current.joined(separator: "\n")
                blocks.append(Block(id: blocks.count, kind: currentKind, text: joined,
                                    rightToLeft: isRightToLeft(joined)))
            }
            current = []
        }

        for line in lines {
            if !inSignature, line == "-- " || line == "--" {
                flush()
                inSignature = true
                currentKind = .signature
                continue
            }
            var kind: Block.Kind = inSignature ? .signature : .body
            var content = line
            if !inSignature {
                var level = 0
                var rest = Substring(line)
                while let first = rest.first, first == ">" || (level > 0 && first == " " && rest.dropFirst().first == ">") {
                    if first == ">" { level += 1 }
                    rest = rest.dropFirst()
                }
                if level > 0 {
                    kind = .quote(level)
                    content = String(rest.hasPrefix(" ") ? rest.dropFirst() : rest)
                }
            }
            let blank = content.trimmingCharacters(in: .whitespaces).isEmpty
            if kind != currentKind || (blank && kind == .body) {
                flush()
                currentKind = kind
            }
            if !(blank && kind == .body) { current.append(content) }
        }
        flush()
        return blocks
    }

    /// The direction of a paragraph is the direction of its first strong character
    /// (Unicode bidi rule P2), which is what Mail and Gmail use.
    static func isRightToLeft(_ s: String) -> Bool {
        for scalar in s.unicodeScalars {
            switch scalar.value {
            case 0x0590...0x08FF, 0xFB1D...0xFDFF, 0xFE70...0xFEFF: return true
            case 0x41...0x5A, 0x61...0x7A, 0xC0...0x24F, 0x370...0x58F, 0x900...0x1FFF,
                 0x3040...0x9FFF, 0xAC00...0xD7AF: return false
            default: continue
            }
        }
        return false
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(Self.blocks(text)) { block in
                blockView(block)
                    .environment(\.layoutDirection, block.rightToLeft ? .rightToLeft : .leftToRight)
            }
        }
    }

    @ViewBuilder
    private func blockView(_ block: Block) -> some View {
        let content = Text(MailMessageView.linkified(block.text))
            .textSelection(.enabled)
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
        switch block.kind {
        case .body:
            content.font(.body)
        case .quote(let level):
            HStack(alignment: .top, spacing: 8) {
                ForEach(0..<min(level, 4), id: \.self) { _ in
                    RoundedRectangle(cornerRadius: 1.5)
                        .fill(Theme.brand.opacity(0.45))
                        .frame(width: 3)
                }
                content.font(.callout).foregroundStyle(.secondary)
            }
        case .signature:
            content.font(.footnote).foregroundStyle(.secondary)
        }
    }
}

// MARK: - Reading: attachments

/// Images first as thumbnails (decoded at thumbnail size with ImageIO, so a 6000-px
/// photo never becomes a full bitmap here), then every attachment as a row with its
/// size, an Open action (QuickLook: zoom, share, PDF, calendar) and Save to drive.
struct MailAttachmentsSection: View {
    let attachments: [ParsedMail.Attachment]
    let savingAttachment: String?
    let onSave: (ParsedMail.Attachment) -> Void

    @State private var preview: MailAttachmentPreview?
    @State private var share: MailAttachmentPreview?
    @State private var openError: String?

    private var images: [(Int, ParsedMail.Attachment)] {
        attachments.enumerated().filter { $0.element.isImage }.map { ($0.offset, $0.element) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if !images.isEmpty {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 8)], spacing: 8) {
                    ForEach(images, id: \.0) { _, a in
                        Button { open(a) } label: { MailAttachmentThumbnail(attachment: a) }
                            .buttonStyle(.plain)
                            .accessibilityLabel(a.filename)
                    }
                }
            }
            ForEach(Array(attachments.enumerated()), id: \.offset) { _, a in
                HStack(spacing: 10) {
                    Button { open(a) } label: {
                        HStack(spacing: 10) {
                            Image(systemName: Self.icon(for: a))
                                .font(.title3)
                                .foregroundStyle(Theme.brand)
                                .frame(width: 28)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(a.filename).font(.footnote).lineLimit(2)
                                    .foregroundStyle(.primary)
                                Text(ByteCountFormatter.string(fromByteCount: Int64(a.bytes), countStyle: .file))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 6)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("mail.attachment.open".localized)

                    Button { shareAttachment(a) } label: { Image(systemName: "square.and.arrow.up") }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("common.share".localized)
                    Button { onSave(a) } label: {
                        if savingAttachment == a.filename { ProgressView() } else { Image(systemName: "folder.badge.plus") }
                    }
                    .buttonStyle(.borderless)
                    .disabled(savingAttachment != nil)
                    .accessibilityLabel("mail.action.saveToDrive".localized)
                }
                .padding(.vertical, 2)
            }
            if let openError {
                Text(openError).font(.caption).foregroundStyle(.red)
            }
        }
        .tint(Theme.brand)
        .sheet(item: $preview) { item in
            NavigationStack {
                QuickLookPreview(url: item.url)
                    .ignoresSafeArea(edges: .bottom)
                    .navigationTitle(item.name)
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("common.done".localized) { preview = nil }
                        }
                    }
            }
        }
        // writeForShare's folder is purged at the next share and at launch.
        .sheet(item: $share) { item in
            ShareSheet(items: [item.url])
        }
    }

    static func icon(for a: ParsedMail.Attachment) -> String {
        let ext = (a.filename as NSString).pathExtension.lowercased()
        if a.isImage { return "photo" }
        if a.mimeType == "application/pdf" || ext == "pdf" { return "doc.richtext" }
        if a.mimeType == "text/calendar" || ext == "ics" { return "calendar" }
        if a.mimeType.hasPrefix("audio/") { return "waveform" }
        if a.mimeType.hasPrefix("video/") { return "film" }
        if ["zip", "gz", "tar", "7z", "rar"].contains(ext) { return "doc.zipper" }
        return "doc"
    }

    /// The plaintext goes to the protected temp folder the drive preview uses, and is
    /// removed when the preview closes (`QuickLookPreview.Coordinator.deinit`) or at the
    /// next launch.
    private func open(_ a: ParsedMail.Attachment) {
        do {
            let url = try MediaPlaintextTemp.write(a.data, fileName: Self.safeName(a))
            preview = MailAttachmentPreview(url: url, name: a.filename)
        } catch {
            openError = error.localizedDescription
        }
    }

    private func shareAttachment(_ a: ParsedMail.Attachment) {
        do {
            let url = try MediaPlaintextTemp.writeForShare(a.data, fileName: Self.safeName(a))
            share = MailAttachmentPreview(url: url, name: a.filename)
        } catch {
            openError = error.localizedDescription
        }
    }

    /// A sender-chosen name must not climb out of the temp folder ("../../x").
    static func safeName(_ a: ParsedMail.Attachment) -> String {
        let base = (a.filename as NSString).lastPathComponent
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
        let name = base.isEmpty || base == "." || base == ".." ? "attachment" : base
        // QuickLook picks the viewer from the extension.
        if (name as NSString).pathExtension.isEmpty,
           let ext = UTType(mimeType: a.mimeType)?.preferredFilenameExtension {
            return name + "." + ext
        }
        return name
    }
}

struct MailAttachmentPreview: Identifiable {
    let url: URL
    let name: String
    var id: String { url.path }
}

struct MailAttachmentThumbnail: View {
    let attachment: ParsedMail.Attachment
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        // The image is an OVERLAY of a fixed-size cell, so `scaledToFill` crops to the
        // cell instead of widening it (it pushed the grid past the screen edge).
        RoundedRectangle(cornerRadius: 10)
            .fill(Color.secondary.opacity(0.12))
            .frame(maxWidth: .infinity)
            .frame(height: 104)
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                } else if failed {
                    Image(systemName: "photo").foregroundStyle(.secondary)
                } else {
                    ProgressView()
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 10))
        .contentShape(RoundedRectangle(cornerRadius: 10))
        .task(id: attachment.filename) {
            let data = attachment.data
            let thumb = await Task.detached(priority: .userInitiated) {
                Self.thumbnail(data, maxPixel: 420)
            }.value
            if let thumb { image = thumb } else { failed = true }
        }
    }

    /// ImageIO decodes straight to the requested size — the full-resolution bitmap of a
    /// 6000×4000 photo (~96 MB) is never allocated. Handles JPEG, PNG, HEIC, GIF, WebP.
    nonisolated static func thumbnail(_ data: Data, maxPixel: Int) -> UIImage? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, options) else { return nil }
        let thumbOptions = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ] as CFDictionary
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbOptions) else { return nil }
        return UIImage(cgImage: cg)
    }
}

// MARK: - DEBUG fixture harness

/// DEBUG-only: `-OSHIMailFixtureDir <dir> -OSHIMailFixture <name|list>` replaces the app
/// root with the mail reader fed from `.eml` files on disk, so how real-world mail
/// renders can be checked on a simulator without a server or a mailbox. Always false in
/// Release.
enum MailFixtureHarness {
    static var isActive: Bool {
        #if DEBUG
        return UserDefaults.standard.string(forKey: "OSHIMailFixture") != nil
        #else
        return false
        #endif
    }
}

#if DEBUG
/// __DUO_POLISH_2026_11_18__ A local demo mailbox for `-OSHIScreens -OSHIMailDemo`:
/// installs an account and a few plain-text messages into the shared client
/// (the same debug-fixture path as the harness below), so the real Email
/// segment can be captured without a server or an address.
@MainActor
enum MailDemoFixture {
    static let openId = "demo-1"

    static func install() {
        let rows: [(String, String, String, String)] = [
            ("Léa Martin <lea@example.org>", "Trip to Lisbon — final plan",
             "Hi! Here is the plan for next week. We land on Friday at 10:40, the flat is near Alfama, and Saturday is free for the coast. Tell me if the Sunday train still works for you.",
             "Here is the plan for next week. We land on Friday at 10:40…"),
            ("OSHI Team <hello@oshi-messenger.com>", "Your mailbox is ready",
             "Welcome to OSHI Mail. Every message is sealed for this device before it is stored: the server keeps ciphertext only. Aliases, the drive and the encrypted search all live in this tab.",
             "Welcome to OSHI Mail. Every message is sealed for this device…"),
            ("Marc Dubois <marc@example.com>", "Photos from Saturday",
             "The photos are in the shared folder. The ones from the harbour came out really well, especially at sunset.",
             "The photos are in the shared folder. The ones from the harbour…"),
            ("Library <noreply@example.net>", "Reminder: 2 books due Monday",
             "This is a reminder that two items are due on Monday. You can renew them once from your account.",
             "This is a reminder that two items are due on Monday…"),
            ("Sofia Rossi <sofia@example.it>", "Re: Mesh meetup",
             "Count me in for Thursday. I will bring two LoRa nodes so we can test the range from the park.",
             "Count me in for Thursday. I will bring two LoRa nodes…"),
        ]
        var messages: [OSHIMailMessage] = []
        var bodies: [String: Data] = [:]
        for (i, row) in rows.enumerated() {
            let id = "demo-\(i + 1)"
            let date = Date().addingTimeInterval(TimeInterval(-5400 * i))
            let raw = "From: \(row.0)\r\nTo: me@mail.oshi-messenger.com\r\nSubject: \(row.1)\r\n"
                + "Date: \(DateFormatter.mailDemo.string(from: date))\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n\(row.2)\r\n"
            bodies[id] = Data(raw.utf8)
            var envelope = OSHIMailMessage.Envelope(
                from: row.0, to: "me@mail.oshi-messenger.com", cc: nil, subject: row.1,
                date: DateFormatter.mailDemo.string(from: date), messageId: nil, bytes: raw.utf8.count)
            envelope.snippet = row.3
            messages.append(OSHIMailMessage(
                id: id, receivedAt: date, bytes: raw.utf8.count, seen: i >= 2, folder: "inbox",
                deliveredTo: "me", envelope: envelope))
        }
        let json = #"{"address":"me@mail.oshi-messenger.com","localpart":"me","aliases":[],"aliasLimit":5,"createdAt":"2026-01-01T00:00:00Z","storage":{"used":12,"quota":100,"remaining":88,"messages":5},"sending":{"sent":3,"quota":10,"remaining":7,"month":"2026-11"},"drive":{"used":0,"quota":10,"remaining":10,"files":0},"tier":"free","premiumUntil":null}"#
        if let account = try? JSONDecoder().decode(OSHIMailAccount.self, from: Data(json.utf8)) {
            OSHIMailClient.shared.installDebugFixtures(account: account, messages: messages, bodies: bodies)
        }
    }
}

private extension DateFormatter {
    static let mailDemo: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss Z"
        return f
    }()
}

struct MailFixtureHarnessView: View {
    @StateObject private var mail = OSHIMailClient.shared
    @State private var ready = false
    private let target = UserDefaults.standard.string(forKey: "OSHIMailFixture") ?? "list"

    var body: some View {
        NavigationStack {
            if !ready {
                ProgressView()
            } else if target == "list" {
                List(mail.messages) { m in
                    NavigationLink { MailMessageView(message: m) } label: { MailRow(message: m) }
                        .oledGlassListRow(first: m.id == mail.messages.first?.id)
                }
                .navigationTitle("mail.title".localized)
            } else if let m = mail.messages.first(where: { $0.id == target }) {
                MailMessageView(message: m)
            } else {
                Text("No fixture named \(target)")
            }
        }
        .task { install() ; ready = true }
    }

    private func install() {
        let dir = URL(fileURLWithPath: UserDefaults.standard.string(forKey: "OSHIMailFixtureDir") ?? NSTemporaryDirectory())
        let files = ((try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "eml" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        var messages: [OSHIMailMessage] = []
        var bodies: [String: Data] = [:]
        for (i, f) in files.enumerated() {
            guard let data = try? Data(contentsOf: f) else { continue }
            let id = f.deletingPathExtension().lastPathComponent
            let p = MailMessageParser.parse(data)
            bodies[id] = data
            messages.append(OSHIMailMessage(
                id: id, receivedAt: Date().addingTimeInterval(TimeInterval(-3600 * i)),
                bytes: data.count, seen: i % 3 == 0, folder: "inbox", deliveredTo: "me",
                envelope: .init(from: p.from, to: p.to, cc: nil, subject: p.subject,
                                date: p.date, messageId: nil, bytes: data.count)))
        }
        // inbox.jsonl: one server inbox item per line, {"id","receivedAt","seen","folder",
        // "deliveredTo","envelope":{…opened envelope JSON…}}, fed through the same envelope
        // decoder the inbox uses after opening a sealed envelope.
        if let jsonl = try? String(contentsOf: dir.appendingPathComponent("inbox.jsonl"), encoding: .utf8) {
            let iso = ISO8601DateFormatter()
            for line in jsonl.split(separator: "\n") where !line.trimmingCharacters(in: .whitespaces).isEmpty {
                guard let obj = (try? JSONSerialization.jsonObject(with: Data(line.utf8))) as? [String: Any],
                      let id = obj["id"] as? String else { continue }
                let envelope = (obj["envelope"] as? [String: Any])
                    .flatMap { try? JSONSerialization.data(withJSONObject: $0) }
                    .flatMap { OSHIMailMessage.Envelope.decode($0) }
                messages.append(OSHIMailMessage(
                    id: id, receivedAt: iso.date(from: obj["receivedAt"] as? String ?? "") ?? Date(),
                    bytes: obj["bytes"] as? Int ?? 0, seen: obj["seen"] as? Bool ?? false,
                    folder: obj["folder"] as? String ?? "inbox", deliveredTo: obj["deliveredTo"] as? String ?? "me",
                    envelope: envelope))
            }
        }
        let json = #"{"address":"me@mail.oshi-messenger.com","localpart":"me","aliases":[],"aliasLimit":5,"createdAt":"2026-01-01T00:00:00Z","storage":{"used":1,"quota":100,"remaining":99,"messages":1},"sending":{"sent":0,"quota":10,"remaining":10,"month":"2026-11"},"drive":{"used":0,"quota":10,"remaining":10,"files":0},"tier":"free","premiumUntil":null}"#
        if let account = try? JSONDecoder().decode(OSHIMailAccount.self, from: Data(json.utf8)) {
            mail.installDebugFixtures(account: account, messages: messages, bodies: bodies)
        }
    }
}
#endif
