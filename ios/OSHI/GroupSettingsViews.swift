//
//  GroupSettingsViews.swift
//  OSHI
//
//  __GROUP_INFO_SETTINGS_2026_11_20__ The Telegram-style rows of the group info
//  page (GROUP_INFO_SETTINGS_SPEC §5) and the public-link join sheet (§3.1).
//  Admin-only rows (A) are editable by admins; everyone else sees the value
//  read-only, or nothing where the spec says "hidden". The actions are in
//  GroupSettingsManager.swift; the rules in GroupSettings.swift.
//

import SwiftUI
import NaturalLanguage
#if canImport(Translation)
@preconcurrency import Translation
#endif

// MARK: - Names

enum GroupNames {
    static func displayName(_ key: String, in group: MessageGroup?, me: String?) -> String {
        let k = MessageManager.canonicalIdentity(key)
        if let me, MessageManager.canonicalIdentity(me) == k { return "gs_you".localized }
        if let alias = group?.members.first(where: { MessageManager.canonicalIdentity($0.publicKey) == k })?.alias,
           !alias.isEmpty { return alias }
        if let data = UserDefaults.standard.data(forKey: "contactAliases"),
           let aliases = try? JSONDecoder().decode([String: String].self, from: data),
           let a = aliases[key], !a.isEmpty { return a }
        return String(key.prefix(10)) + "…"
    }
}

// MARK: - Settings sections (inserted into GroupInfoView's List)

struct GroupSettingsSections: View {
    let groupId: UUID
    var onMessageAdmin: (String) -> Void

    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager
    @State private var autoTranslate = false
    @State private var copied = false

    private var group: MessageGroup? { groupManager.groups.first { $0.id == groupId } }
    private var settings: GroupSettings { group?.settings ?? GroupSettings() }
    private var isAdmin: Bool { group.map { GroupSettingsPolicy.isAdmin(identityManager.publicKey, in: $0) } ?? false }
    private var canEditDescription: Bool {
        guard var g = group else { return false }
        g.setCurrentUser(identityManager.publicKey)
        return g.canChangeName
    }

    var body: some View {
        if let group {
            descriptionSection(group)
            typeSection(group)
            permissionsSection(group)
                .id("gs-anchor-mid")
            // __GROUP_COMMENTS_2026_11_21__ §2.12.6 row 5b: admins, channel mode only
            // (hidden otherwise; the value is kept and re-emitted either way).
            if isAdmin && settings.postPolicy == .admins { commentsSection }
            if settings.welcome.isEmpty == false || isAdmin { welcomeSection }
            dmSection(group)
            if GroupAutoTranslate.isAvailable { translateSection }
        }
    }

    // 2. Description
    @ViewBuilder
    private func descriptionSection(_ group: MessageGroup) -> some View {
        let text = group.groupDescription ?? ""
        if canEditDescription || !text.isEmpty {
            Section("gs_description".localized) {
                if canEditDescription {
                    NavigationLink {
                        GroupTextEditorView(title: "gs_description".localized,
                                            placeholder: "gs_description_hint".localized,
                                            initial: text, limit: GroupSettings.maxDescription) { new in
                            groupManager.updateGroupDescription(groupId, text: new)
                        }
                    } label: {
                        Text(text.isEmpty ? "gs_description_hint".localized : text)
                            .foregroundColor(text.isEmpty ? .secondary : .primary)
                            .lineLimit(4)
                    }
                    .accessibilityIdentifier("gs.description")
                } else {
                    Text(text).textSelection(.enabled)
                }
            }
        }
    }

    // 3. Group type + link
    @ViewBuilder
    private func typeSection(_ group: MessageGroup) -> some View {
        let s = settings
        let showInvite = isAdmin || group.type != .adminOnly
        Section {
            if isAdmin {
                NavigationLink {
                    GroupTypeEditorView(groupId: groupId)
                } label: {
                    LabeledContent("gs_type".localized,
                                   value: (s.isPublic ? "gs_type_public" : "gs_type_private").localized)
                }
                .accessibilityIdentifier("gs.type")
            } else {
                LabeledContent("gs_type".localized,
                               value: (s.isPublic ? "gs_type_public" : "gs_type_private").localized)
            }
            if s.isPublic, let h = s.handle {
                linkRow(title: "gs_public_link".localized,
                        display: "oshi-messenger.com/g/\(h)",
                        url: GroupSettings.publicLink(handle: h))
            } else if showInvite {
                linkRow(title: "gs_invite_link".localized,
                        display: "oshi-messenger.com/group?gid=…",
                        url: group.inviteLink(inviter: identityManager.publicKey))
            }
        } footer: {
            Text((s.isPublic ? "gs_type_public_footer" : "gs_type_private_footer").localized)
        }
    }

    private func linkRow(title: String, display: String, url: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.caption).foregroundColor(.secondary)
            Text(display)
                .font(.system(.body, design: .monospaced))
                .foregroundColor(Theme.brand)
                .lineLimit(1)
                .truncationMode(.middle)
            HStack(spacing: 12) {
                Button {
                    SecureClipboard.copy(url)
                    copied = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copied = false }
                } label: {
                    Label(copied ? "copied".localized : "gs_copy_link".localized,
                          systemImage: copied ? "checkmark" : "doc.on.doc")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                ShareLink(item: url) {
                    Label("gs_share_link".localized, systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
            .labelStyle(.titleAndIcon)
            .font(.subheadline)
        }
        .padding(.vertical, 4)
    }

    // 4–6. Permissions, who can post, reactions
    @ViewBuilder
    private func permissionsSection(_ group: MessageGroup) -> some View {
        let s = settings
        Section {
            if isAdmin {
                Picker("gs_permissions".localized, selection: Binding(
                    get: { group.type },
                    set: { groupManager.setGroupType(groupId, $0) })) {
                    Text("gs_perm_admins".localized).tag(GroupType.adminOnly)
                    Text("gs_perm_all".localized).tag(GroupType.collaborative)
                    Text("gs_perm_open".localized).tag(GroupType.publicGroup)
                }
                .pickerStyle(.navigationLink)
                .accessibilityIdentifier("gs.permissions")
                Picker("gs_who_can_post".localized, selection: Binding(
                    get: { s.postPolicy },
                    set: { v in groupManager.updateGroupSettings(groupId) { $0.postPolicy = v } })) {
                    Text("gs_post_everyone".localized).tag(GroupSettings.PostPolicy.everyone)
                    Text("gs_post_admins".localized).tag(GroupSettings.PostPolicy.admins)
                }
                .accessibilityIdentifier("gs.post")
                NavigationLink {
                    GroupReactionsEditorView(groupId: groupId)
                } label: {
                    LabeledContent("gs_reactions".localized, value: reactionsSummary(s))
                }
                .accessibilityIdentifier("gs.reactions")
            } else {
                LabeledContent("gs_permissions".localized, value: permLabel(group.type))
                LabeledContent("gs_who_can_post".localized,
                               value: (s.postPolicy == .admins ? "gs_post_admins" : "gs_post_everyone").localized)
                LabeledContent("gs_reactions".localized, value: reactionsSummary(s))
            }
        } footer: {
            if !isAdmin { Text("gs_admin_only_hint".localized) }
        }
    }

    // 5b. Comments under posts (§2.12)
    private var commentsSection: some View {
        Section {
            Toggle("gs_comments".localized, isOn: Binding(
                get: { settings.comments },
                set: { v in groupManager.updateGroupSettings(groupId) { $0.comments = v } }))
                .tint(Theme.brand)
                .accessibilityIdentifier("gs.comments")
        } footer: {
            Text("gs_comments_footer".localized)
        }
    }

    private func permLabel(_ t: GroupType) -> String {
        switch t {
        case .adminOnly: return "gs_perm_admins".localized
        case .collaborative: return "gs_perm_all".localized
        case .publicGroup: return "gs_perm_open".localized
        }
    }

    private func reactionsSummary(_ s: GroupSettings) -> String {
        switch s.effectiveReactions {
        case .all: return "gs_reactions_all".localized
        case .none: return "gs_reactions_none".localized
        case .some: return s.allowedReactions.prefix(6).joined(separator: " ")
        }
    }

    // 7. Welcome message
    private var welcomeSection: some View {
        Section("gs_welcome".localized) {
            if isAdmin {
                NavigationLink {
                    GroupTextEditorView(title: "gs_welcome".localized,
                                        placeholder: "gs_welcome_hint".localized,
                                        initial: settings.welcome, limit: GroupSettings.maxWelcome) { new in
                        groupManager.updateGroupSettings(groupId) { $0.welcome = new }
                    }
                } label: {
                    Text(settings.welcome.isEmpty ? "gs_welcome_off".localized : settings.welcome)
                        .foregroundColor(settings.welcome.isEmpty ? .secondary : .primary)
                        .lineLimit(3)
                }
                .accessibilityIdentifier("gs.welcome")
            } else {
                Text(settings.welcome).textSelection(.enabled)
            }
        }
    }

    // 8. Direct messages to admins
    @ViewBuilder
    private func dmSection(_ group: MessageGroup) -> some View {
        let admins = group.members.filter { $0.isAdmin && MessageManager.canonicalIdentity($0.publicKey) != MessageManager.canonicalIdentity(identityManager.publicKey) }
        if isAdmin {
            Section {
                Toggle("gs_admin_dms".localized, isOn: Binding(
                    get: { settings.adminDMs },
                    set: { v in groupManager.updateGroupSettings(groupId) { $0.adminDMs = v } }))
                    .tint(Theme.brand)
                    .accessibilityIdentifier("gs.adminDMs")
            } footer: {
                Text("gs_admin_dms_footer".localized)
            }
        } else if settings.adminDMs, !admins.isEmpty {
            Section {
                Menu {
                    ForEach(admins) { m in
                        Button(GroupNames.displayName(m.publicKey, in: group, me: identityManager.publicKey)) {
                            onMessageAdmin(m.publicKey)
                        }
                    }
                } label: {
                    // Explicit icon colour: in light mode a Menu label's SF Symbol
                    // inherited a white tint and drew nothing (the "Media gallery"
                    // row next to it shows the same pre-existing defect).
                    HStack(spacing: 12) {
                        Image(systemName: "bubble.left.and.bubble.right").foregroundStyle(Theme.brand)
                        Text("gs_message_admin".localized).foregroundStyle(Theme.brand)
                    }
                }
                .accessibilityIdentifier("gs.messageAdmin")
            }
        }
    }

    // 9. Auto-translate (local)
    private var translateSection: some View {
        Section {
            Toggle("gs_auto_translate".localized, isOn: Binding(
                get: { autoTranslate },
                set: { v in autoTranslate = v; GroupLocalFlags.shared.setAutoTranslate(groupId, v) }))
                .tint(Theme.brand)
                .onAppear { autoTranslate = GroupLocalFlags.shared.autoTranslate(groupId) }
        } footer: {
            Text("gs_auto_translate_footer".localized)
        }
    }
}

// MARK: - Administrators / banned / recent events

struct GroupAdminsSection: View {
    let group: MessageGroup
    let me: String

    var body: some View {
        let admins = GroupHandleRegistry.adminKeys(group)
        Section("gs_admins".localized) {
            ForEach(admins, id: \.self) { key in
                HStack(spacing: 10) {
                    DefaultAvatar(key: key, name: group.members.first { $0.publicKey == key }?.alias, size: 30, initialCount: 1)
                    Text(GroupNames.displayName(key, in: group, me: me))
                    Spacer()
                    Image(systemName: "crown.fill").foregroundColor(.orange).font(.caption)
                }
            }
        }
    }
}

struct GroupAdminToolsSection: View {
    let groupId: UUID
    @EnvironmentObject var groupManager: MessageGroupManager

    var body: some View {
        let banned = groupManager.groups.first { $0.id == groupId }?.evictedMemberKeys?.count ?? 0
        Section {
            NavigationLink {
                GroupBannedUsersView(groupId: groupId)
            } label: {
                LabeledContent("gs_banned".localized, value: "\(banned)")
            }
            .accessibilityIdentifier("gs.banned")
            NavigationLink {
                GroupRecentEventsView(groupId: groupId)
            } label: {
                Text("gs_recent_events".localized)
            }
            .accessibilityIdentifier("gs.events")
        }
    }
}

struct GroupBannedUsersView: View {
    let groupId: UUID
    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager

    var body: some View {
        let group = groupManager.groups.first { $0.id == groupId }
        let keys = group?.evictedMemberKeys ?? []
        List {
            if keys.isEmpty {
                Text("gs_banned_empty".localized).foregroundColor(.secondary)
            }
            ForEach(keys, id: \.self) { key in
                HStack {
                    DefaultAvatar(key: key, name: nil, size: 30, initialCount: 1)
                    Text(GroupNames.displayName(key, in: group, me: identityManager.publicKey))
                        .font(.system(.body, design: .monospaced))
                    Spacer()
                    Button("gs_unban".localized) {
                        groupManager.unbanMember(groupId, publicKey: key)
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
        .navigationTitle("gs_banned".localized)
    }
}

struct GroupRecentEventsView: View {
    let groupId: UUID
    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager
    @State private var events: [GroupAdminEvent] = []

    var body: some View {
        let group = groupManager.groups.first { $0.id == groupId }
        List {
            if events.isEmpty {
                Text("gs_recent_events_empty".localized).foregroundColor(.secondary)
            }
            ForEach(events) { e in
                VStack(alignment: .leading, spacing: 2) {
                    Text(Self.text(e, group: group, me: identityManager.publicKey))
                    Text(e.at, style: .relative).font(.caption).foregroundColor(.secondary)
                }
            }
        }
        .navigationTitle("gs_recent_events".localized)
        .onAppear { events = GroupAdminLogStore.shared.events(groupId) }
    }

    static func text(_ e: GroupAdminEvent, group: MessageGroup?, me: String) -> String {
        let actor = GroupNames.displayName(e.actor, in: group, me: me)
        let target = e.detail.map { GroupNames.displayName($0, in: group, me: me) } ?? ""
        func setting(_ key: String) -> String { String(format: "gs_ev_settings".localized, actor, key.localized) }
        switch e.kind {
        case "renamed": return String(format: "gs_ev_renamed".localized, actor, e.detail ?? "")
        case "description_changed": return String(format: "gs_ev_description".localized, actor)
        case "photo_changed": return setting("group.change_picture")
        case "member_added": return String(format: "gs_ev_member_added".localized, actor, target)
        case "member_removed": return String(format: "gs_ev_member_removed".localized, actor, target)
        case "member_left": return String(format: "gs_ev_member_left".localized, actor)
        case "admin_promoted": return String(format: "gs_ev_admin_promoted".localized, actor, target)
        case "admin_demoted": return String(format: "gs_ev_admin_demoted".localized, actor, target)
        case "visibility_changed", "handle_changed": return setting("gs_type")
        case "invite_link_reset": return setting("gs_invite_link")
        case "reactions_changed": return setting("gs_reactions")
        case "welcome_changed": return setting("gs_welcome")
        case "post_policy_changed": return setting("gs_who_can_post")
        case "admin_dms_changed": return setting("gs_admin_dms")
        case "comments_changed": return setting("gs_comments")
        case "joined_via_link": return String(format: "gs_ev_joined_link".localized, actor)
        case "dissolved": return String(format: "gs_ev_dissolved".localized, actor)
        default: return setting(e.kind)
        }
    }
}

// MARK: - Editors

struct GroupTextEditorView: View {
    let title: String
    let placeholder: String
    let initial: String
    let limit: Int
    let onSave: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    var body: some View {
        Form {
            Section {
                ZStack(alignment: .topLeading) {
                    if text.isEmpty {
                        Text(placeholder).foregroundColor(.secondary).padding(.top, 8).padding(.leading, 4)
                    }
                    TextEditor(text: $text)
                        .frame(minHeight: 140)
                        .accessibilityIdentifier("gs.editor")
                        .onChange(of: text) { _, v in
                            if v.utf16.count > limit { text = GroupSettings.truncateUTF16(v, max: limit) }
                        }
                }
            } footer: {
                Text("\(text.utf16.count)/\(limit)")
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("gs_save".localized) { onSave(text); dismiss() }
                    .fontWeight(.semibold)
            }
        }
        .onAppear { text = initial }
    }
}

struct GroupReactionsEditorView: View {
    let groupId: UUID
    @EnvironmentObject var groupManager: MessageGroupManager
    @State private var mode: GroupSettings.Reactions = .all
    @State private var picked: [String] = []

    var body: some View {
        Form {
            Section {
                Picker("gs_reactions".localized, selection: $mode) {
                    Text("gs_reactions_all".localized).tag(GroupSettings.Reactions.all)
                    Text("gs_reactions_some".localized).tag(GroupSettings.Reactions.some)
                    Text("gs_reactions_none".localized).tag(GroupSettings.Reactions.none)
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }
            if mode == .some {
                Section {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 5), spacing: 12) {
                        ForEach(GroupSettings.defaultReactionChoices, id: \.self) { e in
                            let on = picked.contains(e)
                            Button {
                                if on { picked.removeAll { $0 == e } } else if picked.count < GroupSettings.maxAllowedReactions { picked.append(e) }
                            } label: {
                                Text(e).font(.system(size: 30))
                                    .frame(width: 52, height: 52)
                                    .background(Circle().fill(on ? Theme.brand.opacity(0.25) : Color.clear))
                                    .overlay(Circle().stroke(on ? Theme.brand : Color.secondary.opacity(0.3), lineWidth: on ? 2 : 1))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 6)
                }
            }
        }
        .navigationTitle("gs_reactions".localized)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("gs_save".localized) {
                    groupManager.updateGroupSettings(groupId) {
                        $0.reactions = mode
                        $0.allowedReactions = mode == .some ? picked : $0.allowedReactions
                    }
                }
                .fontWeight(.semibold)
            }
        }
        .onAppear {
            let s = groupManager.groups.first { $0.id == groupId }?.settings ?? GroupSettings()
            mode = s.reactions
            picked = s.allowedReactions.isEmpty ? Array(GroupSettings.defaultReactionChoices.prefix(3)) : s.allowedReactions
        }
    }
}

struct GroupTypeEditorView: View {
    let groupId: UUID
    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager
    @State private var isPublic = false
    @State private var handle = ""
    @State private var showPreview = false
    @State private var saving = false
    @State private var error: String?
    @State private var confirmReset = false

    private var group: MessageGroup? { groupManager.groups.first { $0.id == groupId } }
    private var normalized: String? { GroupSettings.normalizedHandle(handle) }

    var body: some View {
        Form {
            Section {
                Picker("gs_type".localized, selection: $isPublic) {
                    Text("gs_type_private".localized).tag(false)
                    Text("gs_type_public".localized).tag(true)
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } footer: {
                Text((isPublic ? "gs_type_public_footer" : "gs_type_private_footer").localized)
            }
            if isPublic {
                Section {
                    HStack(spacing: 0) {
                        Text("oshi-messenger.com/g/").foregroundColor(.secondary)
                        TextField("handle", text: $handle)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .accessibilityIdentifier("gs.handle")
                    }
                    .font(.system(.body, design: .monospaced))
                    Toggle("gs_show_preview".localized, isOn: $showPreview).tint(Theme.brand)
                } header: {
                    Text("gs_public_link".localized)
                } footer: {
                    if let error {
                        Text(error).foregroundColor(.red)
                    } else if !handle.isEmpty && normalized == nil {
                        Text("gs_handle_invalid".localized).foregroundColor(.red)
                    } else {
                        Text(saving ? "gs_handle_saving".localized : "gs_handle_rules".localized)
                    }
                }
            } else if let group {
                Section {
                    Button("gs_reset_link".localized, role: .destructive) { confirmReset = true }
                } header: {
                    Text("gs_invite_link".localized)
                } footer: {
                    Text(group.inviteLink(inviter: identityManager.publicKey))
                        .font(.caption2.monospaced())
                        .lineLimit(2)
                        .truncationMode(.middle)
                }
            }
        }
        .navigationTitle("gs_type".localized)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("gs_save".localized) { save() }
                    .fontWeight(.semibold)
                    .disabled(saving || (isPublic && normalized == nil))
            }
        }
        .confirmationDialog("gs_reset_link".localized, isPresented: $confirmReset, titleVisibility: .visible) {
            Button("gs_reset_link".localized, role: .destructive) { groupManager.resetInviteLink(groupId) }
            Button("button_cancel".localized, role: .cancel) {}
        } message: {
            Text("gs_reset_link_confirm".localized)
        }
        .onAppear {
            let s = group?.settings ?? GroupSettings()
            isPublic = s.isPublic
            handle = s.handle ?? ""
            showPreview = s.showPreview
        }
    }

    private func save() {
        error = nil
        saving = true
        let h = isPublic ? normalized : nil
        let preview = showPreview
        Task { @MainActor in
            let r = await groupManager.setVisibility(groupId, handle: h, showPreview: preview)
            saving = false
            switch r {
            case .ok: break
            case .taken: error = "gs_handle_taken".localized
            case .invalid: error = "gs_handle_invalid".localized
            case .error: error = "gs_handle_error".localized
            }
        }
    }
}

// MARK: - Composer replacement (§2.4, §2.9)

struct GroupComposerBlockedBanner: View {
    let state: GroupSettingsPolicy.ComposerState
    var onDeleteChat: () -> Void = {}

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: state == .dissolved ? "xmark.octagon" : "megaphone")
                .foregroundColor(.secondary)
            Text((state == .dissolved ? "gs_dissolved_banner" : "gs_only_admins_banner").localized)
                .font(.subheadline)
                .foregroundColor(.secondary)
            Spacer(minLength: 0)
            if state == .dissolved {
                Button("gs_delete_for_me".localized, role: .destructive, action: onDeleteChat)
                    .font(.subheadline.weight(.semibold))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity)
        .background(.regularMaterial)
        .accessibilityIdentifier("gs.composerBanner")
    }
}

// MARK: - Auto-translate (§2.10)

enum GroupAutoTranslate {
    /// A translation path exists: Apple Translation (iOS 18+) or the on-device NLP/LLM.
    @MainActor static var isAvailable: Bool {
        if #available(iOS 18.0, macCatalyst 26.0, *) { return true }
        return LocalLLMManager.shared.isModelDownloaded
    }

    /// Already in the device language ⇒ nothing to show.
    static func needsTranslation(_ text: String) -> Bool {
        guard text.count >= 2 else { return false }
        let device = Locale.current.language.languageCode?.identifier ?? "en"
        guard let lang = NLLanguageRecognizer.dominantLanguage(for: text)?.rawValue else { return false }
        return !lang.hasPrefix(device)
    }
}

/// Shown under a received text message when auto-translate is on for the group.
/// On-device only: the NLP path first, Apple Translation as the fallback.
struct GroupAutoTranslatedLine: View {
    let messageId: UUID
    let text: String
    @State private var translated: String?
    @State private var triedNLP = false

    private static var cache: [UUID: String] = [:]

    var body: some View {
        SwiftUI.Group {
            if let translated, !translated.isEmpty, translated != text {
                HStack(alignment: .top, spacing: 6) {
                    Image(systemName: "character.bubble").font(.caption2)
                    Text(translated).font(.footnote)
                }
                .foregroundColor(.secondary)
                .padding(.horizontal, 14)
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                Color.clear.frame(height: 0)
            }
        }
        .task(id: messageId) {
            if let c = Self.cache[messageId] { translated = c; return }
            guard GroupAutoTranslate.needsTranslation(text) else { return }
            if let t = await NLPManager.shared.processTranslateReceived(text) {
                Self.cache[messageId] = t
                translated = t
            }
            triedNLP = true
        }
        .modifier(AppleTranslationFallback(text: text, enabled: triedNLP && translated == nil) { t in
            Self.cache[messageId] = t
            translated = t
        })
    }
}

private struct AppleTranslationFallback: ViewModifier {
    let text: String
    let enabled: Bool
    let onResult: (String) -> Void

    func body(content: Content) -> some View {
        #if canImport(Translation)
        if #available(iOS 18.0, macCatalyst 26.0, *), enabled {
            content.translationTask(TranslationSession.Configuration(source: nil, target: nil)) { session in
                if let r = try? await session.translate(text) { onResult(r.targetText) }
            }
        } else {
            content
        }
        #else
        content
        #endif
    }
}

// MARK: - Public link join (§3.1)

struct PublicGroupJoinSheet: View {
    let rawHandle: String
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var groupManager = MessageGroupManager.shared

    enum Phase: Equatable { case loading, invalid, notFound, error, ready(GroupHandleRecord), sending, sent }
    @State private var phase: Phase = .loading

    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                Image(systemName: "person.3.sequence.fill")
                    .font(.system(size: 52))
                    .foregroundStyle(Theme.brand)
                    .padding(.top, 28)
                content
                Spacer()
            }
            .padding(.horizontal)
            .navigationTitle("gs_join_title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("cancel".localized) { dismiss() }
                }
            }
        }
        .task { await load() }
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case .loading:
            ProgressView()
        case .invalid:
            Text("gs_link_invalid".localized).foregroundColor(.secondary)
        case .notFound:
            Text("gs_link_not_found".localized).foregroundColor(.secondary)
        case .error:
            Text("gs_handle_error".localized).foregroundColor(.secondary)
        case .ready(let r):
            details(r)
        case .sending:
            ProgressView()
        case .sent:
            Label("gs_join_request_sent".localized, systemImage: "checkmark.circle.fill")
                .foregroundColor(.green)
                .multilineTextAlignment(.center)
        }
    }

    @ViewBuilder
    private func details(_ r: GroupHandleRecord) -> some View {
        Text(r.preview?.name ?? "@\(r.handle)").font(.title2.bold())
        if r.preview?.name != nil { Text("@\(r.handle)").foregroundColor(.secondary) }
        if let d = r.preview?.description, !d.isEmpty {
            Text(d).multilineTextAlignment(.center).foregroundColor(.secondary)
        }
        if let n = r.preview?.memberCount {
            Text(String(format: "group_member_count".localized, n)).font(.subheadline).foregroundColor(.secondary)
        }
        Button {
            phase = .sending
            Task { @MainActor in
                let ok = await groupManager.sendPublicJoinRequest(handle: r.handle, joinKeys: r.joinKeys)
                phase = ok ? .sent : .error
            }
        } label: {
            Text("group_invite_join_button".localized)
                .font(.headline).foregroundColor(.white)
                .frame(maxWidth: .infinity).padding()
                .background(Theme.brand).cornerRadius(14)
        }
        .padding(.top, 8)
    }

    private func load() async {
        guard let h = GroupSettings.normalizedHandle(rawHandle) else { phase = .invalid; return }
        do {
            if let r = try await GroupHandleRegistry.lookup(h) { phase = .ready(r) } else { phase = .notFound }
        } catch {
            phase = .error
        }
    }
}

#if DEBUG
// MARK: - Screenshot host (DEBUG only)

/// Seeds one demo group carrying every setting and shows the real
/// `GroupInfoView` for it, as an admin or as a plain member. Never compiled into
/// Release; never touches the network (the registry is not called for display).
struct GroupInfoPreviewHost: View {
    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager
    @State private var gid: UUID?

    static let demoId = UUID(uuidString: "6E5E7700-0000-4000-8000-0000000DE0DE")!

    var body: some View {
        SwiftUI.Group {
            if let gid {
                GroupInfoView(groupId: gid)
            } else {
                ProgressView()
            }
        }
        .onAppear { seed() }
    }

    private func arg(_ name: String) -> String? {
        let a = ProcessInfo.processInfo.arguments
        guard let i = a.firstIndex(of: name), i + 1 < a.count else { return nil }
        return a[i + 1]
    }

    private func seed() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
            let me = identityManager.publicKey.isEmpty ? (groupManager.currentUserPublicKey ?? "ME") : identityManager.publicKey
            let asAdmin = arg("-OSHIGroupInfoRole") != "member"
            let fr = Locale.current.language.languageCode?.identifier == "fr"
            let alice = DemoDataManager.aliceKey, bob = DemoDataManager.bobKey
            let charlie = DemoDataManager.charlieKey, dave = DemoDataManager.daveKey
            let t0 = Date().addingTimeInterval(-86400 * 30)
            var g = MessageGroup(
                id: Self.demoId, name: "Swiss Quant", type: .adminOnly,
                adminPublicKey: asAdmin ? me : alice,
                members: [
                    GroupMember(publicKey: asAdmin ? me : alice, alias: asAdmin ? nil : "Alice", joinedAt: t0, isAdmin: true),
                    GroupMember(publicKey: asAdmin ? alice : me, alias: asAdmin ? "Alice" : nil, joinedAt: t0, isAdmin: asAdmin),
                    GroupMember(publicKey: bob, alias: "Bob", joinedAt: t0, isAdmin: false),
                    GroupMember(publicKey: charlie, alias: "Charlie", joinedAt: t0, isAdmin: false)
                ],
                createdAt: t0, lastActivity: Date())
            g.groupDescription = fr ? "Recherche quantitative, notes quotidiennes. Pas de conseil financier."
                                    : "Quant research, daily notes. Not financial advice."
            var s = GroupSettings()
            s.visibility = .public
            s.handle = "swiss_quant"
            s.showPreview = true
            s.reactions = .some
            s.allowedReactions = ["👍", "❤️", "🔥"]
            s.welcome = fr ? "Bienvenue ! Lisez le message épinglé." : "Welcome! Read the pinned message first."
            s.postPolicy = .admins
            s.adminDMs = true
            g.settings = s
            g.evictedMemberKeys = [dave]
            g.stateVersion = 7
            g.setCurrentUser(me)
            groupManager.groups.removeAll { $0.id == Self.demoId }
            groupManager.groups.append(g)
            let now = Date()
            GroupAdminLogStore.shared.forget(Self.demoId)
            GroupAdminLogStore.shared.append(Self.demoId, [
                GroupAdminEvent(at: now.addingTimeInterval(-7200), actor: alice, kind: "member_removed", detail: dave),
                GroupAdminEvent(at: now.addingTimeInterval(-3600), actor: me, kind: "post_policy_changed", detail: "admins"),
                GroupAdminEvent(at: now.addingTimeInterval(-600), actor: charlie, kind: "joined_via_link", detail: nil),
            ])
            gid = Self.demoId
        }
    }
}

/// `-OSHIGroupInfoAnchor top|center|bottom` — where the info list opens, so a
/// long page can be captured in three shots without touch injection.
struct GroupInfoDebugScrollAnchor: ViewModifier {
    let proxy: ScrollViewProxy
    func body(content: Content) -> some View {
        content.onAppear {
            let a = ProcessInfo.processInfo.arguments
            guard let v = a.firstIndex(of: "-OSHIGroupInfoAnchor").flatMap({ $0 + 1 < a.count ? a[$0 + 1] : nil }),
                  ["mid", "center", "bottom"].contains(v) else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                proxy.scrollTo("gs-anchor-\(v)", anchor: v == "bottom" ? .bottom : .top)
            }
        }
    }
}
#endif
