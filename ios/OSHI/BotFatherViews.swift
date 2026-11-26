//
//  BotFatherViews.swift
//  OSHI — BotFather in the app, and bots inside groups and channels.
//
//  __BOTS_PHASE1_2026_11_24__ (owner re-scope: "like Telegram with BotFather,
//  very user friendly, and also for channels where there is interaction
//  inside. Not a bot chat.")
//
//    • `BotFatherFlowView`   — the guided creation: name → @username (live
//      availability) → what it does → commands → token + how to connect →
//      add it to a group or channel I administer.
//    • `BotProfileSheet`     — what a bot link opens: the bot's public card
//      with "Add to group" / "Add to channel".
//    • `BotAddToChatSheet`   — pick a group/channel where I am admin, choose
//      the bot's rights, read the remote-bot notice, add.
//    • `GroupBotsCard`       — group/channel info: its bots, "Add a bot".
//    • `BotPublicProfileSection` — My bots › a bot: @username, about, emoji,
//      public, share link + QR, revoke token.
//    • `BotBadge`            — the BOT capsule on bot posts.
//
//  Network through `BotPlatformService` (BotPlatform.swift).
//

import SwiftUI
import CoreImage.CIFilterBuiltins
import UIKit

// MARK: - Small pieces

/// The "BOT" capsule next to a bot's name (posts, cards, lists).
struct BotBadge: View {
    var tint: Color = .blue
    var body: some View {
        Text("bot.badge".localized)
            .font(.system(size: 9, weight: .heavy, design: .rounded))
            .foregroundColor(.white)
            .padding(.horizontal, 5)
            .padding(.vertical, 1.5)
            .background(Capsule().fill(tint))
            .accessibilityLabel(Text("bot.badge.a11y".localized))
    }
}

enum BotChatKind: Equatable {
    case group, channel

    init(_ group: MessageGroup) { self = group.groupType == .adminOnly ? .channel : .group }

    /// The exact contract sentence, with {group|channel}.
    var remoteNotice: String {
        self == .channel ? "bot.add.notice.channel".localized : "bot.add.notice.group".localized
    }
}

/// The groups and channels where I may add a bot: the ones I administer.
@MainActor
enum BotAdminChats {
    /// `includeNonAdmin`: a LOCAL bot (runs on this phone, answers through my
    /// own encrypted sends) could always be added to any group I am in.
    static func list(kind: BotChatKind? = nil, includeNonAdmin: Bool = false) -> [MessageGroup] {
        let mgr = MessageGroupManager.shared
        return mgr.groups
            .filter { includeNonAdmin || mgr.isCurrentUserAdmin(of: $0.id) }
            .filter { kind == nil || BotChatKind($0) == kind }
            .sorted { $0.lastMessageAt > $1.lastMessageAt }
    }
}

/// Link, copy, share and a QR code for a bot's `@username`.
struct BotShareCard: View {
    let username: String
    @State private var copied = false

    private var link: URL? { BotLink.url(username: username) }

    var body: some View {
        VStack(spacing: 12) {
            if let link, let qr = BotQRCode.image(for: link.absoluteString) {
                Image(uiImage: qr)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 170, height: 170)
                    .padding(10)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Color.white))
                    .accessibilityLabel(Text("bot.share.qr_a11y".localized))
            }
            Text(link?.absoluteString ?? "")
                .font(.footnote.monospaced())
                .foregroundColor(.secondary)
                .textSelection(.enabled)
                .multilineTextAlignment(.center)
            HStack(spacing: 10) {
                Button {
                    UIPasteboard.general.string = link?.absoluteString
                    copied = true
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                } label: {
                    Label(copied ? "bot.share.copied".localized : "bot.share.copy".localized,
                          systemImage: copied ? "checkmark" : "doc.on.doc")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                if let link {
                    ShareLink(item: link) {
                        Label("bot.share.share".localized, systemImage: "square.and.arrow.up")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
        }
        .frame(maxWidth: .infinity)
    }
}

enum BotQRCode {
    static func image(for string: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

// MARK: - @username field with live availability

/// Live availability of a `@username` (`GET /api/bot/usernameAvailable`,
/// contract S1) — nothing is changed on the server while the owner types. The
/// name is claimed with `setMyProfile` when the owner moves on (`claim`); a
/// 409 there (someone was faster) comes back as `.taken` with suggestions.
@MainActor
final class BotUsernameChecker: ObservableObject {
    enum State: Equatable {
        case idle
        case invalid(BotUsername.Problem)
        case checking
        case available(String)
        case taken([String])
        case failed
    }

    @Published private(set) var state: State = .idle
    private var task: Task<Void, Never>?
    private let service: BotPlatformService
    private let debounce: Duration

    init(service: BotPlatformService = HTTPBotPlatformService(), debounce: Duration = .milliseconds(500)) {
        self.service = service
        self.debounce = debounce
    }

    var available: String? { if case .available(let u) = state { return u } else { return nil } }

    func check(_ raw: String, botId: UUID, name: String) {
        task?.cancel()
        let u = BotUsername.normalize(raw)
        if u.isEmpty { state = .idle; return }
        if let p = BotUsername.problem(u) { state = .invalid(p); return }
        state = .checking
        let token = BotManager.shared.bots.first { $0.id == botId }?.token
        let service = self.service, debounce = self.debounce
        task = Task { [weak self] in
            try? await Task.sleep(for: debounce)
            guard !Task.isCancelled else { return }
            do {
                let reason = try await service.usernameUnavailableReason(u, token: token)
                guard !Task.isCancelled else { return }
                switch reason {
                case nil: self?.state = .available(u)
                case .usernameInvalid?: self?.state = .invalid(.invalidCharacters)
                default: self?.state = .taken(BotUsername.suggestions(forName: name, taken: [u]))
                }
            } catch {
                guard !Task.isCancelled else { return }
                self?.state = .failed
            }
        }
    }

    /// Claim the available name for the bot. `true` on success.
    func claim(botId: UUID, name: String) async -> Bool {
        guard let u = available else { return false }
        do {
            try await BotManager.shared.publishProfile(of: botId, update: BotProfileUpdate(username: u), service: service)
            return true
        } catch BotPlatformError.usernameTaken {
            state = .taken(BotUsername.suggestions(forName: name, taken: [u]))
        } catch BotPlatformError.usernameInvalid {
            state = .invalid(.invalidCharacters)
        } catch {
            state = .failed
        }
        return false
    }
}

struct BotUsernameField: View {
    @Binding var text: String
    @ObservedObject var checker: BotUsernameChecker
    let botId: UUID
    let name: String
    /// Shown as a "Save" button once the name is available (edit screen).
    var onClaim: (() -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 4) {
                Text("@").foregroundColor(.secondary)
                TextField("bot.username.placeholder".localized, text: $text)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.body.monospaced())
                    .accessibilityIdentifier("botfather.username")
                    .onChange(of: text) { _, new in
                        let clean = BotUsername.sanitizeWhileTyping(new)
                        if clean != new { text = clean; return }
                        checker.check(clean, botId: botId, name: name)
                    }
                stateIcon
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemGroupedBackground)))
            stateLine
        }
    }

    @ViewBuilder private var stateIcon: some View {
        switch checker.state {
        case .checking: ProgressView().controlSize(.small)
        case .available: Image(systemName: "checkmark.circle.fill").foregroundColor(.green)
        case .taken, .invalid: Image(systemName: "xmark.circle.fill").foregroundColor(.red)
        case .failed: Image(systemName: "wifi.exclamationmark").foregroundColor(.orange)
        case .idle: EmptyView()
        }
    }

    @ViewBuilder private var stateLine: some View {
        switch checker.state {
        case .idle:
            Text("bot.username.rule".localized).font(.footnote).foregroundColor(.secondary)
        case .invalid(let p):
            Text(p.messageKey.localized).font(.footnote).foregroundColor(.red)
        case .checking:
            Text("bot.username.checking".localized).font(.footnote).foregroundColor(.secondary)
        case .available(let u):
            Text(String(format: "bot.username.available".localized, "@" + u)).font(.footnote).foregroundColor(.green)
            if let onClaim {
                Button("bot.username.save".localized, action: onClaim)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
            }
        case .failed:
            Text("bot.username.offline".localized).font(.footnote).foregroundColor(.orange)
        case .taken(let suggestions):
            VStack(alignment: .leading, spacing: 6) {
                Text("bot.username.taken".localized).font(.footnote).foregroundColor(.red)
                if !suggestions.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack {
                            ForEach(suggestions, id: \.self) { s in
                                Button("@" + s) { text = s }
                                    .font(.footnote.monospaced())
                                    .buttonStyle(.bordered)
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - BotFather: guided creation

struct BotFatherFlowView: View {
    /// Set when started from a group/channel's "Add a bot": the last step
    /// offers that one first.
    var preselectedGroup: UUID? = nil
    var onFinish: (() -> Void)? = nil

    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var identityManager: IdentityManager
    @ObservedObject private var botManager = BotManager.shared
    @StateObject private var checker = BotUsernameChecker()

    enum Step: Int, CaseIterable { case name, username, about, commands, token, add }

    @State private var step: Step = .name
    @State private var name = ""
    @State private var username = ""
    @State private var emoji = "🤖"
    @State private var about = ""
    @State private var description = ""
    @State private var template: BotFatherTemplate = .custom
    @State private var chosenCommands: [BotCommandSpec] = []
    @State private var botId: UUID?
    @State private var registering = false
    @State private var registerFailed = false
    @State private var tokenCopied = false
    @State private var showTokenHelp = false
    @State private var addTarget: MessageGroup?
    @State private var added: Set<UUID> = []

    private let emojis = ["🤖", "🗳️", "👋", "⏰", "📰", "🛠️", "🎲", "🌦️", "📊", "🎵", "🧠", "🦊"]

    private var bot: OSHIBot? { botId.flatMap { id in botManager.bots.first { $0.id == id } } }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                progress
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        stepHeader
                        stepBody
                    }
                    .padding(20)
                    .frame(maxWidth: 560)
                    .frame(maxWidth: .infinity)
                }
                footer
            }
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("botfather.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(step == .add ? "common.done".localized : "common.cancel".localized) { close(cancelled: step.rawValue < Step.token.rawValue) }
                }
            }
            .sheet(item: $addTarget) { group in
                if let bot {
                    BotAddToChatSheet(target: .owned(bot), group: group) { added.insert(group.id) }
                }
            }
        }
        .interactiveDismissDisabled(step != .name)
    }

    // MARK: Chrome

    private var progress: some View {
        HStack(spacing: 4) {
            ForEach(Step.allCases, id: \.self) { s in
                Capsule().fill(s.rawValue <= step.rawValue ? Theme.brand : Color(.systemGray4)).frame(height: 3)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
        .accessibilityElement()
        .accessibilityLabel(Text(String(format: "botfather.step_of".localized, step.rawValue + 1, Step.allCases.count)))
    }

    private var stepHeader: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(("botfather.step." + key(step) + ".title").localized)
                .font(.title2.weight(.bold))
            Text(("botfather.step." + key(step) + ".help").localized)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func key(_ s: Step) -> String {
        switch s {
        case .name: return "name"
        case .username: return "username"
        case .about: return "about"
        case .commands: return "commands"
        case .token: return "token"
        case .add: return "add"
        }
    }

    private var footer: some View {
        HStack(spacing: 12) {
            if step != .name && step != .token && step != .add {
                Button("botfather.back".localized) { go(-1) }
                    .buttonStyle(.bordered)
            }
            Button {
                next()
            } label: {
                HStack {
                    if registering { ProgressView().tint(.white) }
                    Text(nextTitle).frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(!canAdvance || registering)
            .accessibilityIdentifier("botfather.next")
        }
        .padding(16)
        .background(.bar)
    }

    private var nextTitle: String {
        switch step {
        case .about, .commands: return "botfather.next".localized
        case .token: return "botfather.token.next".localized
        case .add: return "common.done".localized
        default: return "botfather.next".localized
        }
    }

    private var canAdvance: Bool {
        switch step {
        case .name: return !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        // Username is optional — a bot without one works, it just has no link.
        case .username:
            if username.isEmpty { return true }
            return checker.available != nil || checker.state == .failed
        default: return true
        }
    }

    // MARK: Steps

    @ViewBuilder private var stepBody: some View {
        switch step {
        case .name: nameStep
        case .username: usernameStep
        case .about: aboutStep
        case .commands: commandsStep
        case .token: tokenStep
        case .add: addStep
        }
    }

    private var nameStep: some View {
        VStack(alignment: .leading, spacing: 10) {
            TextField("botfather.name.placeholder".localized, text: $name)
                .font(.title3)
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemGroupedBackground)))
                .accessibilityIdentifier("botfather.name")
                .onChange(of: name) { _, v in if v.count > 64 { name = String(v.prefix(64)) } }
            if registerFailed {
                Label("botfather.register_failed".localized, systemImage: "wifi.exclamationmark")
                    .font(.footnote).foregroundColor(.orange)
            }
        }
    }

    private var usernameStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let id = botId {
                BotUsernameField(text: $username, checker: checker, botId: id, name: name)
            }
            let suggestions = BotUsername.suggestions(forName: name)
            if username.isEmpty, !suggestions.isEmpty {
                Text("botfather.username.ideas".localized).font(.footnote).foregroundColor(.secondary)
                HStack {
                    ForEach(suggestions, id: \.self) { s in
                        Button("@" + s) { username = s }
                            .font(.footnote.monospaced())
                            .buttonStyle(.bordered)
                    }
                }
            }
        }
    }

    private var aboutStep: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("botfather.about.emoji".localized).font(.subheadline.weight(.semibold))
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 6), spacing: 8) {
                ForEach(emojis, id: \.self) { e in
                    Button { emoji = e } label: {
                        Text(e).font(.title2)
                            .frame(width: 46, height: 46)
                            .background(Circle().fill(e == emoji ? Theme.brand.opacity(0.2) : Color(.secondarySystemGroupedBackground)))
                            .overlay(Circle().stroke(e == emoji ? Theme.brand : .clear, lineWidth: 2))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(e))
                }
            }
            labeledField("botfather.about.about".localized,
                         hint: String(format: "botfather.about.about_hint".localized, BotPublicProfile.maxAbout),
                         text: $about, limit: BotPublicProfile.maxAbout, lines: 1...3)
            labeledField("botfather.about.description".localized,
                         hint: "botfather.about.description_hint".localized,
                         text: $description, limit: BotPublicProfile.maxDescription, lines: 2...6)
        }
    }

    private func labeledField(_ title: String, hint: String, text: Binding<String>, limit: Int,
                              lines: ClosedRange<Int>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.subheadline.weight(.semibold))
            TextField(hint, text: text, axis: .vertical)
                .lineLimit(lines)
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemGroupedBackground)))
                .onChange(of: text.wrappedValue) { _, v in if v.count > limit { text.wrappedValue = String(v.prefix(limit)) } }
            Text("\(text.wrappedValue.count)/\(limit)").font(.caption2).foregroundColor(.secondary)
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
    }

    private var commandsStep: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("botfather.commands.kind".localized).font(.subheadline.weight(.semibold))
            ForEach(BotFatherTemplate.allCases) { t in
                Button { applyTemplate(t) } label: {
                    HStack(spacing: 12) {
                        Text(t.emoji).font(.title3)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.titleKey.localized).font(.body.weight(.semibold)).foregroundColor(.primary)
                            Text(t.detailKey.localized).font(.footnote).foregroundColor(.secondary)
                                .multilineTextAlignment(.leading)
                        }
                        Spacer()
                        if template == t { Image(systemName: "checkmark.circle.fill").foregroundColor(Theme.brand) }
                    }
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemGroupedBackground)))
                }
                .buttonStyle(.plain)
            }

            Text("botfather.commands.list".localized).font(.subheadline.weight(.semibold)).padding(.top, 6)
            ForEach(["start", "help"], id: \.self) { s in
                if !chosenCommands.contains(where: { $0.name == s }) {
                    Button {
                        chosenCommands.insert(BotCommandSpec(name: s, description: ("bot.commands.starter." + s).localized), at: 0)
                    } label: {
                        Label("/" + s, systemImage: "plus.circle")
                    }
                    .buttonStyle(.bordered)
                }
            }
            ForEach(chosenCommands) { c in
                HStack {
                    VStack(alignment: .leading) {
                        Text(c.slashed).font(.body.monospaced().weight(.semibold)).foregroundColor(Theme.brand)
                        Text(c.description).font(.footnote).foregroundColor(.secondary)
                    }
                    Spacer()
                    Button { chosenCommands.removeAll { $0.name == c.name } } label: {
                        Image(systemName: "minus.circle.fill").foregroundColor(.red)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("common.delete".localized))
                }
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(.secondarySystemGroupedBackground)))
            }
            Text("botfather.commands.later".localized).font(.footnote).foregroundColor(.secondary)
        }
    }

    private var tokenStep: some View {
        VStack(alignment: .leading, spacing: 14) {
            if let bot {
                Text(bot.token)
                    .font(.footnote.monospaced())
                    .textSelection(.enabled)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemGroupedBackground)))
                    .accessibilityIdentifier("botfather.token")
                Button {
                    UIPasteboard.general.string = bot.token
                    tokenCopied = true
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                } label: {
                    Label(tokenCopied ? "bot.share.copied".localized : "botfather.token.copy".localized,
                          systemImage: tokenCopied ? "checkmark" : "doc.on.doc")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                Label("botfather.token.secret".localized, systemImage: "lock.fill")
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(.orange)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color.orange.opacity(0.12)))

                DisclosureGroup(isExpanded: $showTokenHelp) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("botfather.token.how_help".localized).font(.footnote).foregroundColor(.secondary)
                        snippet("curl", BotConnectSnippets.curl(token: bot.token, groupId: preselectedGroup?.uuidString))
                        snippet("Python", BotConnectSnippets.python(token: bot.token, groupId: preselectedGroup?.uuidString))
                    }
                    .padding(.top, 8)
                } label: {
                    Text("botfather.token.how".localized).font(.subheadline.weight(.semibold))
                }
            }
        }
    }

    private func snippet(_ title: String, _ code: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title).font(.caption.weight(.bold)).foregroundColor(Theme.brand)
                Spacer()
                Button { UIPasteboard.general.string = code } label: {
                    Label("botfather.copy_code".localized, systemImage: "doc.on.doc").font(.caption)
                }
                .buttonStyle(.borderless)
            }
            ScrollView(.horizontal) {
                Text(code).font(.system(size: 11, design: .monospaced)).textSelection(.enabled).padding(8)
            }
            .background(RoundedRectangle(cornerRadius: 8).fill(Color(.tertiarySystemGroupedBackground)))
        }
    }

    private var addStep: some View {
        let chats = BotAdminChats.list()
        let ordered = chats.sorted { a, _ in a.id == preselectedGroup }
        return VStack(alignment: .leading, spacing: 10) {
            if let u = bot?.publishedUsername {
                BotShareCard(username: u).padding(.bottom, 8)
            }
            if ordered.isEmpty {
                Text("botfather.add.none".localized).font(.footnote).foregroundColor(.secondary)
            }
            ForEach(ordered) { g in
                Button { addTarget = g } label: {
                    HStack(spacing: 12) {
                        DefaultAvatar(key: g.id.uuidString, name: g.name, size: 40)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(g.name).font(.body.weight(.medium)).foregroundColor(.primary)
                            Text((BotChatKind(g) == .channel ? "bot.kind.channel" : "bot.kind.group").localized)
                                .font(.caption).foregroundColor(.secondary)
                        }
                        Spacer()
                        Image(systemName: added.contains(g.id) || bot?.assignedGroups.contains(g.id) == true
                              ? "checkmark.circle.fill" : "plus.circle")
                            .foregroundColor(added.contains(g.id) ? .green : Theme.brand)
                    }
                    .padding(10)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemGroupedBackground)))
                }
                .buttonStyle(.plain)
                .disabled(bot?.assignedGroups.contains(g.id) == true)
            }
        }
    }

    // MARK: Actions

    private func applyTemplate(_ t: BotFatherTemplate) {
        template = t
        if emoji == "🤖" || BotFatherTemplate.allCases.map(\.emoji).contains(emoji) { emoji = t.emoji }
        let keep = chosenCommands.filter { ["start", "help"].contains($0.name) }
        chosenCommands = keep + t.commandKeys.map { BotCommandSpec(name: $0.0, description: $0.1.localized) }
    }

    private func go(_ delta: Int) {
        if let s = Step(rawValue: step.rawValue + delta) { withAnimation { step = s } }
    }

    private func next() {
        switch step {
        case .name: createIfNeeded()
        case .username:
            guard checker.available != nil, let id = botId else { go(1); return }
            registering = true
            Task {
                let ok = await checker.claim(botId: id, name: name)
                registering = false
                if ok { go(1) }
            }
        case .about:
            saveProfile()
            go(1)
        case .commands:
            saveCommands()
            go(1)
        case .token: go(1)
        case .add: close(cancelled: false)
        }
    }

    /// The bot exists (and is registered, so it can claim a username) from
    /// the end of step 1. Cancelling before the token step deletes it.
    private func createIfNeeded() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if var existing = bot {
            existing.name = trimmed
            botManager.updateBot(existing)
            Task { _ = await botManager.registerBotWithServerNow(existing) }
            go(1)
            return
        }
        let created = botManager.createBot(name: trimmed,
                                           username: BotUsername.suggestions(forName: trimmed, count: 1).first ?? "",
                                           description: "",
                                           type: .webhook,     // driven by its token = a remote bot
                                           ownerPublicKey: identityManager.publicKey)
        botId = created.id
        registering = true
        registerFailed = false
        Task {
            let ok = await botManager.registerBotWithServerNow(created)
            registering = false
            registerFailed = !ok
            go(1)
        }
    }

    private func saveProfile() {
        guard var b = bot else { return }
        b.avatarEmoji = emoji
        b.about = about.isEmpty ? nil : about
        b.description = description
        botManager.updateBot(b)
        let id = b.id
        Task {
            try? await botManager.publishProfile(of: id, update: BotProfileUpdate(
                name: b.name, description: description, about: about, avatarEmoji: emoji))
        }
    }

    private func saveCommands() {
        guard var b = bot, !chosenCommands.isEmpty else { return }
        var triggers = b.triggers
        for c in chosenCommands {
            if case .success(let t) = BotCommandList.upsert(triggers, editing: nil, name: c.name,
                                                             description: c.description, reply: "") {
                triggers = t
            }
        }
        b.triggers = triggers
        botManager.updateBot(b)
        let list = BotCommandList.commands(of: b), token = b.token
        Task { try? await HTTPBotCommandsService().setCommands(list, token: token) }
    }

    private func close(cancelled: Bool) {
        if cancelled, let b = bot { botManager.deleteBot(b) }
        onFinish?()
        dismiss()
    }
}

// MARK: - Adding a bot to a group or channel

enum BotAddTarget {
    case owned(OSHIBot)
    case profile(BotPublicProfile)

    var name: String {
        switch self { case .owned(let b): return b.name; case .profile(let p): return p.name }
    }
    var emoji: String {
        switch self {
        case .owned(let b): return b.avatarEmoji
        case .profile(let p): return p.avatarEmoji.isEmpty ? "🤖" : p.avatarEmoji
        }
    }
    /// Remote = runs on its owner's server → the notice is shown.
    var isRemote: Bool {
        switch self {
        case .owned(let b): return BotManager.needsServerRegistration(b)
        case .profile(let p): return p.isRemote
        }
    }
}

struct BotAddToChatSheet: View {
    let target: BotAddTarget
    /// Chosen already (from the group's own "Add a bot"), else picked here.
    var group: MessageGroup? = nil
    var kind: BotChatKind? = nil
    var onAdded: (() -> Void)? = nil
    var service: BotPlatformService = HTTPBotPlatformService()

    @Environment(\.dismiss) private var dismiss
    @State private var picked: MessageGroup?
    @State private var permissions = BotGroupPermissions()
    @State private var adding = false
    @State private var errorText: String?

    private var chosen: MessageGroup? { group ?? picked }

    var body: some View {
        NavigationStack {
            SwiftUI.Group {
                if let chosen { form(chosen) } else { picker }
            }
            .navigationTitle(chosen == nil ? titleForPicker : "bot.add.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) { dismiss() }
                }
            }
        }
    }

    private var titleForPicker: String {
        switch kind {
        case .channel: return "bot.add.to_channel".localized
        case .group: return "bot.add.to_group".localized
        case nil: return "bot.add.title".localized
        }
    }

    private var picker: some View {
        let chats = BotAdminChats.list(kind: kind, includeNonAdmin: !target.isRemote)
        return List {
            if chats.isEmpty {
                Text((kind == .channel ? "bot.add.no_admin_channel" : "bot.add.no_admin_group").localized)
                    .font(.footnote).foregroundColor(.secondary)
            }
            ForEach(chats) { g in
                Button { picked = g } label: {
                    HStack(spacing: 12) {
                        DefaultAvatar(key: g.id.uuidString, name: g.name, size: 36)
                        Text(g.name).foregroundColor(.primary)
                        Spacer()
                        Text((BotChatKind(g) == .channel ? "bot.kind.channel" : "bot.kind.group").localized)
                            .font(.caption).foregroundColor(.secondary)
                    }
                }
            }
        }
    }

    private func form(_ g: MessageGroup) -> some View {
        let chatKind = BotChatKind(g)
        return Form {
            Section {
                HStack(spacing: 12) {
                    Text(target.emoji).font(.largeTitle)
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) { Text(target.name).font(.headline); BotBadge() }
                        Text(String(format: "bot.add.into".localized, g.name)).font(.footnote).foregroundColor(.secondary)
                    }
                }
            }
            Section {
                Toggle("bot.perm.post".localized, isOn: $permissions.canPost)
                Toggle("bot.perm.edit".localized, isOn: $permissions.canEdit)
                Toggle("bot.perm.delete".localized, isOn: $permissions.canDelete)
                Toggle("bot.perm.pin".localized, isOn: $permissions.canPin)
            } header: {
                Text("bot.perm.header".localized)
            } footer: {
                Text((chatKind == .channel ? "bot.perm.footer.channel" : "bot.perm.footer.group").localized)
            }
            if target.isRemote {
                Section {
                    Label {
                        Text(chatKind.remoteNotice).font(.footnote)
                    } icon: {
                        Image(systemName: "server.rack").foregroundColor(.orange)
                    }
                    .accessibilityIdentifier("bot.add.notice")
                }
            }
            if let errorText {
                Section { Text(errorText).foregroundColor(.red).font(.footnote) }
            }
            Section {
                Button {
                    add(to: g)
                } label: {
                    HStack {
                        Spacer()
                        if adding { ProgressView() }
                        Text("bot.add.confirm".localized).fontWeight(.semibold)
                        Spacer()
                    }
                }
                .disabled(adding)
                .accessibilityIdentifier("bot.add.confirm")
            }
        }
    }

    private func add(to g: MessageGroup) {
        errorText = nil
        switch target {
        case .owned(let bot):
            BotManager.shared.addOwnedBot(bot.id, to: g.id, permissions: permissions)
            onAdded?()
            dismiss()
        case .profile(let profile):
            guard let me = GroupBotDirectory.shared.callerPublicKey() else {
                errorText = "bot.add.failed".localized
                return
            }
            adding = true
            let request = BotAddRequest(botId: profile.botId, groupId: g.id.uuidString,
                                        kind: BotChatKind(g) == .channel ? "channel" : "group",
                                        members: g.members.map(\.publicKey),
                                        permissions: permissions, adderPublicKey: me)
            Task {
                do {
                    try await service.addBot(request)
                    GroupBotDirectory.shared.record(profile, in: g.id, permissions: permissions)
                    adding = false
                    onAdded?()
                    dismiss()
                } catch {
                    adding = false
                    errorText = (error as? BotPlatformError == .notFound ? "bot.profile.not_found" : "bot.add.failed").localized
                }
            }
        }
    }
}

// MARK: - What a bot link opens

struct BotProfileSheet: View {
    let link: BotLink
    var service: BotPlatformService = HTTPBotPlatformService()

    @Environment(\.dismiss) private var dismiss
    @State private var profile: BotPublicProfile?
    @State private var notFound = false
    @State private var failed = false
    @State private var addKind: BotChatKind?

    var body: some View {
        NavigationStack {
            SwiftUI.Group {
                if let profile {
                    BotProfileCard(profile: profile) { addKind = $0 }
                } else if notFound {
                    message("questionmark.circle", "bot.profile.not_found".localized)
                } else if failed {
                    VStack(spacing: 12) {
                        message("wifi.exclamationmark", "bot.profile.offline".localized)
                        Button("bot.profile.retry".localized) { Task { await load() } }.buttonStyle(.bordered)
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .navigationTitle("@" + link.username)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel("common.dismiss".localized)
                }
            }
            .task { await load() }
            .sheet(item: Binding(get: { addKind.map { KindBox(kind: $0) } }, set: { addKind = $0?.kind })) { box in
                if let profile { BotAddToChatSheet(target: .profile(profile), kind: box.kind, service: service) }
            }
        }
    }

    private struct KindBox: Identifiable { let kind: BotChatKind; var id: String { kind == .channel ? "c" : "g" } }

    private func message(_ icon: String, _ text: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 44)).foregroundColor(.secondary)
            Text(text).font(.headline).multilineTextAlignment(.center)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func load() async {
        failed = false
        guard BotUsername.isValid(link.username) else { notFound = true; return }
        do {
            profile = try await service.profile(username: link.username)
        } catch BotPlatformError.notFound {
            notFound = true
        } catch {
            failed = true
        }
    }
}

/// The bot's public card: avatar, name, BOT, @username, about, description,
/// commands, and the two ways to put it to work.
struct BotProfileCard: View {
    let profile: BotPublicProfile
    let onAdd: (BotChatKind) -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Text(profile.avatarEmoji.isEmpty ? "🤖" : profile.avatarEmoji)
                    .font(.system(size: 56))
                    .frame(width: 96, height: 96)
                    .background(Circle().fill(Theme.brand.opacity(0.12)))
                VStack(spacing: 4) {
                    HStack(spacing: 6) {
                        Text(profile.name).font(.title2.weight(.bold))
                        BotBadge()
                    }
                    Text("@" + profile.username).font(.subheadline.monospaced()).foregroundColor(.secondary)
                }
                if !profile.about.isEmpty {
                    Text(profile.about).font(.body).multilineTextAlignment(.center)
                }
                VStack(spacing: 10) {
                    Button { onAdd(.group) } label: {
                        Label("bot.add.to_group".localized, systemImage: "person.3.fill").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("bot.profile.add_group")
                    Button { onAdd(.channel) } label: {
                        Label("bot.add.to_channel".localized, systemImage: "megaphone.fill").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("bot.profile.add_channel")
                }
                .controlSize(.large)
                if !profile.description.isEmpty {
                    Text(profile.description)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                        .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemGroupedBackground)))
                }
                if !profile.commands.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("bot.commands.title".localized).font(.subheadline.weight(.semibold))
                        ForEach(profile.commands) { c in
                            HStack(alignment: .firstTextBaseline) {
                                Text(c.slashed).font(.subheadline.monospaced().weight(.semibold)).foregroundColor(Theme.brand)
                                Text(c.description).font(.footnote).foregroundColor(.secondary)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemGroupedBackground)))
                }
                if profile.isRemote {
                    Label("bot.profile.remote".localized, systemImage: "server.rack")
                        .font(.footnote).foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(20)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
    }
}

// MARK: - Finding a bot to add (search)

struct BotSearchSection: View {
    let onPick: (BotPublicProfile) -> Void
    var service: BotPlatformService = HTTPBotPlatformService()

    @State private var query = ""
    @State private var results: [BotPublicProfile] = []
    @State private var searching = false
    @State private var task: Task<Void, Never>?

    var body: some View {
        Section {
            TextField("bot.search.placeholder".localized, text: $query)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .accessibilityIdentifier("bot.search.field")
                .onChange(of: query) { _, q in run(q) }
            if searching { ProgressView() }
            ForEach(results) { p in
                Button { onPick(p) } label: {
                    HStack(spacing: 12) {
                        Text(p.avatarEmoji.isEmpty ? "🤖" : p.avatarEmoji).font(.title3)
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 6) { Text(p.name).foregroundColor(.primary); BotBadge() }
                            Text("@" + p.username).font(.caption.monospaced()).foregroundColor(.secondary)
                        }
                    }
                }
            }
            if !searching, results.isEmpty, query.trimmingCharacters(in: .whitespaces).count >= 2 {
                Text("bot.search.none".localized).font(.footnote).foregroundColor(.secondary)
            }
        } header: {
            Text("bot.search.header".localized)
        }
    }

    private func run(_ q: String) {
        task?.cancel()
        let trimmed = q.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.replacingOccurrences(of: "@", with: "").count >= 2 else { results = []; searching = false; return }
        searching = true
        let service = self.service
        task = Task {
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            let found = (try? await service.search(trimmed)) ?? []
            guard !Task.isCancelled else { return }
            results = found
            searching = false
        }
    }
}

// MARK: - Group / channel info: its bots

struct GroupBotsCard: View {
    let groupId: UUID
    let isAdmin: Bool

    @ObservedObject private var botManager = BotManager.shared
    @ObservedObject private var directory = GroupBotDirectory.shared
    @State private var showAdd = false

    private var owned: [OSHIBot] { botManager.bots.filter { $0.assignedGroups.contains(groupId) } }
    private var remote: [GroupRemoteBot] {
        let ownedIds = Set(owned.map(\.publicId))
        return directory.bots(in: groupId).filter { !ownedIds.contains($0.botId) }
    }

    var body: some View {
        if isAdmin || !owned.isEmpty || !remote.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Image(systemName: "cpu").foregroundColor(Theme.brand)
                    Text("bot.group.title".localized).font(.headline)
                    Spacer()
                    if isAdmin {
                        Button { showAdd = true } label: {
                            Label("bot.group.add".localized, systemImage: "plus.circle.fill")
                                .font(.subheadline.weight(.semibold))
                        }
                        .accessibilityIdentifier("group.bots.add")
                    }
                }
                ForEach(owned) { b in row(emoji: b.avatarEmoji, name: b.name, username: b.publishedUsername) }
                ForEach(remote) { b in
                    HStack {
                        row(emoji: b.avatarEmoji ?? "🤖", name: b.name, username: b.username)
                        Spacer()
                        if isAdmin {
                            Button { remove(b) } label: {
                                Image(systemName: "minus.circle.fill").foregroundColor(.red)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text("a11y.bot.remove_from_group".localized))
                        }
                    }
                }
                if owned.isEmpty && remote.isEmpty {
                    Text("bot.group.empty".localized).font(.footnote).foregroundColor(.secondary)
                }
            }
            .padding()
            .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))
            .cornerRadius(16)
            .sheet(isPresented: $showAdd) { BotPickerForGroupSheet(groupId: groupId) }
        }
    }

    /// S4 removeFromGroup (the adder or the owner may), then forget it here.
    private func remove(_ b: GroupRemoteBot) {
        let gid = groupId
        Task {
            if let me = directory.callerPublicKey() {
                try? await directory.service.removeBot(botId: b.botId, groupId: gid.uuidString, adderPublicKey: me)
            }
            directory.remove(botId: b.botId, from: gid)
        }
    }

    private func row(emoji: String, name: String, username: String?) -> some View {
        HStack(spacing: 10) {
            Text(emoji).font(.title3)
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 6) { Text(name).font(.subheadline.weight(.medium)); BotBadge() }
                if let username, !username.isEmpty {
                    Text("@" + username).font(.caption.monospaced()).foregroundColor(.secondary)
                }
            }
        }
    }
}

/// "Add a bot" from a group or channel: my bots, a search, or a new one.
struct BotPickerForGroupSheet: View {
    let groupId: UUID

    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var botManager = BotManager.shared
    @State private var target: BotAddTarget?
    @State private var showFather = false

    private var group: MessageGroup? { MessageGroupManager.shared.groups.first { $0.id == groupId } }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button { showFather = true } label: {
                        Label("bot.create_new".localized, systemImage: "wand.and.stars")
                    }
                    .accessibilityIdentifier("group.bots.create")
                }
                let mine = botManager.bots.filter { !$0.assignedGroups.contains(groupId) }
                if !mine.isEmpty {
                    Section("bot.your_bots".localized) {
                        ForEach(mine) { b in
                            Button { target = .owned(b) } label: {
                                HStack(spacing: 12) {
                                    Text(b.avatarEmoji).font(.title3)
                                    Text(b.name).foregroundColor(.primary)
                                }
                            }
                        }
                    }
                }
                BotSearchSection { target = .profile($0) }
            }
            .navigationTitle("bot.group.add".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("common.cancel".localized) { dismiss() } }
            }
            .sheet(isPresented: Binding(get: { target != nil }, set: { if !$0 { target = nil } })) {
                if let target, let group {
                    BotAddToChatSheet(target: target, group: group) { dismiss() }
                }
            }
            .sheet(isPresented: $showFather) {
                BotFatherFlowView(preselectedGroup: groupId)
            }
        }
    }
}

// MARK: - My bots › a bot: its public face

struct BotPublicProfileSection: View {
    @Binding var bot: OSHIBot
    @ObservedObject private var botManager = BotManager.shared
    @StateObject private var checker = BotUsernameChecker()
    @State private var usernameText = ""
    @State private var showShare = false
    @State private var confirmRevoke = false
    @State private var revoking = false
    @State private var revokeError = false
    @State private var saveTask: Task<Void, Never>?

    var body: some View {
        if BotManager.needsServerRegistration(bot) {
            Section {
                BotUsernameField(text: $usernameText, checker: checker, botId: bot.id, name: bot.name,
                                 onClaim: usernameText == (bot.publishedUsername ?? "") ? nil : {
                    let id = bot.id, name = bot.name
                    Task {
                        if await checker.claim(botId: id, name: name),
                           let fresh = botManager.bots.first(where: { $0.id == id }) { bot = fresh }
                    }
                })
                .onAppear { usernameText = bot.publishedUsername ?? "" }

                TextField("botfather.about.about".localized, text: Binding(
                    get: { bot.about ?? "" },
                    set: { bot.about = String($0.prefix(BotPublicProfile.maxAbout)); botManager.updateBot(bot); scheduleSave() }
                ), axis: .vertical)
                .lineLimit(1...3)

                Picker("botfather.about.emoji".localized, selection: Binding(
                    get: { bot.avatarEmoji },
                    set: { bot.avatarEmoji = $0; botManager.updateBot(bot); scheduleSave() }
                )) {
                    ForEach(Array(Set(["🤖", "🗳️", "👋", "⏰", "📰", "🛠️", "🎲", "🌦️", "📊", "🎵", "🧠", "🦊", bot.avatarEmoji])).sorted(), id: \.self) {
                        Text($0).tag($0)
                    }
                }

                Toggle("bot.public.toggle".localized, isOn: Binding(
                    get: { bot.isPublic ?? false },
                    set: { v in
                        bot.isPublic = v
                        botManager.updateBot(bot)
                        let id = bot.id
                        Task { try? await botManager.publishProfile(of: id, update: BotProfileUpdate(isPublic: v)) }
                    }
                ))

                if let u = bot.publishedUsername {
                    Button { showShare = true } label: {
                        Label("bot.share.title".localized, systemImage: "qrcode")
                    }
                    .sheet(isPresented: $showShare) {
                        NavigationStack {
                            BotShareCard(username: u).padding(24)
                                .navigationTitle("@" + u)
                                .navigationBarTitleDisplayMode(.inline)
                        }
                        .presentationDetents([.medium, .large])
                    }
                }

                Button(role: .destructive) { confirmRevoke = true } label: {
                    HStack {
                        Label("bot.revoke.title".localized, systemImage: "key.slash")
                        if revoking { Spacer(); ProgressView() }
                    }
                }
                .disabled(revoking)
                .confirmationDialog("bot.revoke.title".localized, isPresented: $confirmRevoke, titleVisibility: .visible) {
                    Button("bot.revoke.confirm".localized, role: .destructive) { revoke() }
                } message: {
                    Text("bot.revoke.message".localized)
                }
                if revokeError {
                    Text("bot.revoke.failed".localized).font(.footnote).foregroundColor(.red)
                }
            } header: {
                Text("bot.public.section".localized)
            } footer: {
                Text("bot.public.footer".localized)
            }
        }
    }

    /// About / emoji / name are pushed once the owner pauses.
    private func scheduleSave() {
        saveTask?.cancel()
        let id = bot.id, snapshot = bot
        saveTask = Task {
            try? await Task.sleep(for: .milliseconds(800))
            guard !Task.isCancelled else { return }
            try? await botManager.publishProfile(of: id, update: BotProfileUpdate(
                name: snapshot.name, description: snapshot.description,
                about: snapshot.about ?? "", avatarEmoji: snapshot.avatarEmoji))
        }
    }

    private func revoke() {
        revoking = true
        revokeError = false
        let id = bot.id
        Task {
            do {
                bot = try await botManager.revokeToken(of: id)
            } catch {
                revokeError = true
            }
            revoking = false
        }
    }
}
