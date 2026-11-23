//
//  MergedChatsViews.swift
//  OSHI
//
//  __MERGED_CHATS_2026_11_18__ « Fusionner Messages et Groupes » — the views.
//
//  `MessagesListView(merged: true)` keeps its navigation stack, toolbar, 1:1
//  sheets, deep links and search; what it draws INSIDE is `MergedChatsList`:
//  filter chips, people and groups in one chronological list, the "group
//  nearby" card and the Discover teaser. The "New…" sheet gathers every way to
//  start something — the Groups segment's create / discover actions included —
//  under the one compose button. Rules (order, chips, counts) live in
//  ChatListMerge.swift, where they are tested.
//
//  Lists only. Thread rendering is untouched (no LazyVStack anywhere near a
//  chat thread).
//

import SwiftUI

// MARK: - The list

struct MergedChatsList: View {
    @EnvironmentObject private var messageManager: MessageManager
    @EnvironmentObject private var groupManager: MessageGroupManager
    @EnvironmentObject private var identityManager: IdentityManager
    @EnvironmentObject private var threadSelection: ThreadSelection
    @Environment(\.threadSplitActive) private var splitActive
    @ObservedObject private var conversationPrefs = ConversationPrefsStore.shared
    @ObservedObject private var discovered = DiscoveredGroupsManager.shared

    /// The navigation bar's search text (owned by MessagesListView).
    let searchText: String
    /// Message-content hits above the rows while searching (MessagesListView's).
    let searchResults: AnyView?
    @Binding var pendingGroupId: String?
    let displayName: (String) -> String?
    // First-run actions (the classic empty state).
    let onShowMyCode: () -> Void
    let onScanCode: () -> Void
    let onInviteByLink: () -> Void
    // Group actions, presented by MessagesListView.
    let onDiscover: () -> Void
    let onCreateGroup: () -> Void
    // 1:1 row actions, the same as the classic list's.
    let onShowAddress: (Conversation) -> Void
    let onOpenContact: (Conversation) -> Void
    let onDeleteConversation: (Conversation) -> Void

    @SceneStorage("chats.merged.filter") private var filterRaw = ChatListFilter.all.rawValue
    /// __MERGED_GROUP_TYPES_2026_11_18__ "" = every type.
    @SceneStorage("chats.merged.groupType") private var groupTypeRaw = ""
    @State private var groupInfo: MessageGroup?
    @State private var leaveCandidate: MessageGroup?
    @State private var teaserDismissed = false
    @State private var nearbyDismissed: Set<UUID> = []

    private var filter: ChatListFilter { ChatListFilter(rawValue: filterRaw) ?? .all }
    private var groupType: GroupType? { GroupType(rawValue: groupTypeRaw) }

    private var items: [ChatListItem] {
        let people = messageManager.conversations.map { c in
            ChatListItem(id: ChatListMerge.conversationPrefix + c.participantPublicKey,
                         kind: LoRaChannelThread.isChannelKey(c.participantPublicKey) ? .radioChannel : .person,
                         title: displayName(c.participantPublicKey) ?? c.participantAddress,
                         date: c.lastMessage.timestamp,
                         unread: c.unreadCount,
                         pinKey: c.participantPublicKey)
        }
        let rooms = groupManager.groups.map { g in
            ChatListItem(id: ChatListMerge.groupPrefix + g.id.uuidString,
                         kind: .group,
                         title: g.name,
                         date: g.lastMessageAt,
                         unread: groupManager.getUnreadCount(for: g.id),
                         pinKey: ChatListMerge.pinKey(forGroup: g.id),
                         groupType: g.groupType)
        }
        return people + rooms
    }

    private var nearbyNew: [PublicGroupAd] {
        let joined = Set(groupManager.groups.map(\.id))
        return discovered.discoveredGroups
            .filter { !joined.contains($0.groupId) && !nearbyDismissed.contains($0.groupId) }
            .sorted { $0.timestamp > $1.timestamp }
    }

    var body: some View {
        let all = items
        SwiftUI.Group {
            if all.isEmpty {
                // First run: the classic "add your first contact" screen, with
                // the Discover card under it — the one thing a newcomer can do
                // on their own.
                ScrollView {
                    VStack(spacing: 24) {
                        EmptyStateView(onShowMyCode: onShowMyCode,
                                       onScanCode: onScanCode,
                                       onInviteByLink: onInviteByLink)
                        if !teaserDismissed {
                            DiscoverTeaserCard(onDiscover: onDiscover, onCreate: onCreateGroup,
                                               onDismiss: { withAnimation { teaserDismissed = true } })
                                .frame(maxWidth: 460)
                                .padding(.horizontal)
                        }
                    }
                    .padding(.vertical, 24)
                    .frame(maxWidth: .infinity)
                }
                .background(DynamicMeshBackground().ignoresSafeArea())
            } else {
                list(all)
            }
        }
        .onChange(of: pendingGroupId) { _, id in
            if let id { navigateToGroup(id) }
        }
        .onAppear {
            if let id = pendingGroupId { navigateToGroup(id) }
            // Same immediate poll the Groups segment fired when it appeared, so
            // a group opened from here is not empty for the first 2 s.
            Task.detached(priority: .userInitiated) {
                MessageManager.shared?.checkPendingIPFSMessages()
            }
        }
        .sheet(item: $groupInfo) { group in
            GroupInfoSheet(groupId: group.id)
        }
        .alert("confirm_leave_group".localized,
               isPresented: Binding(get: { leaveCandidate != nil }, set: { if !$0 { leaveCandidate = nil } })) {
            Button("button_leave_group".localized, role: .destructive) {
                if let g = leaveCandidate {
                    groupManager.leaveGroup(g.id, userPublicKey: identityManager.publicKey)
                }
                leaveCandidate = nil
            }
            Button("cancel".localized, role: .cancel) { leaveCandidate = nil }
        } message: {
            Text("confirm_leave_message".localized)
        }
    }

    @ViewBuilder
    private func list(_ all: [ChatListItem]) -> some View {
        let shown = ChatListMerge.arrange(all, isPinned: { conversationPrefs.isPinned($0) },
                                          filter: filter, query: searchText, groupType: groupType)
        let conversationsById = Dictionary(messageManager.conversations.map {
            (ChatListMerge.conversationPrefix + $0.participantPublicKey, $0)
        }, uniquingKeysWith: { a, _ in a })
        let groupsById = Dictionary(groupManager.groups.map {
            (ChatListMerge.groupPrefix + $0.id.uuidString, $0)
        }, uniquingKeysWith: { a, _ in a })
        let showCards = searchText.isEmpty && (filter == .all || filter == .groups)

        VStack(spacing: 0) {
            MergedFilterChips(selected: Binding(get: { filter }, set: { filterRaw = $0.rawValue }),
                              counts: ChatListMerge.counts(all))
            if filter == .groups {
                MergedGroupTypeChips(selected: Binding(get: { groupType }, set: { groupTypeRaw = $0?.rawValue ?? "" }))
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
            if let searchResults, !searchText.isEmpty { searchResults }

            List {
                if showCards, !nearbyNew.isEmpty {
                    NearbyGroupsCard(ads: nearbyNew, onOpen: onDiscover,
                                     onDismiss: { withAnimation { nearbyDismissed.formUnion(nearbyNew.map(\.groupId)) } })
                        .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                }

                if shown.isEmpty {
                    Text(emptyMessage)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 32)
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                        .accessibilityIdentifier("merged_empty_filter")
                }

                ForEach(Array(shown.enumerated()), id: \.element.id) { index, item in
                    if let group = groupsById[item.id] {
                        groupRow(group, index: index)
                    } else if let conversation = conversationsById[item.id] {
                        conversationRow(conversation, index: index)
                    }
                }

                if showCards, groupManager.groups.isEmpty, !teaserDismissed {
                    DiscoverTeaserCard(onDiscover: onDiscover, onCreate: onCreateGroup,
                                       onDismiss: { withAnimation { teaserDismissed = true } })
                        .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                }

                // Clears iOS 26's floating tab bar — see
                // __CLEAR_THE_FLOATING_TABBAR_2026_08_19__ in MessagesListView.
                Color.clear
                    .frame(height: 96)
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
                    .accessibilityHidden(true)
            }
            .listStyle(.plain)
            .animation(.snappy(duration: 0.25), value: filterRaw)
        }
        // Same width rule as the classic list (__MAC_WIDTH_2026_08_13__).
        #if targetEnvironment(macCatalyst)
        .frame(maxWidth: .infinity)
        #else
        .frame(maxWidth: 700)
        .frame(maxWidth: .infinity)
        #endif
    }

    private var emptyMessage: String {
        if !searchText.isEmpty { return "search.no_results".localized }
        switch filter {
        case .unread: return "chats.merged.empty_unread".localized
        case .groups: return "empty_groups_title".localized
        case .people, .all: return "chats.merged.empty_people".localized
        }
    }

    // MARK: Rows

    private func conversationRow(_ conversation: Conversation, index: Int) -> some View {
        let key = conversation.participantPublicKey
        let open = splitActive && threadSelection.isOpen(conversation)
        return NavigationLink(value: ThreadRoute.conversation(for: conversation)) {
            ConversationRow(conversation: conversation, identityManager: identityManager,
                            alias: displayName(key))
        }
        .accessibilityIdentifier(index == 0 ? "merged_row_first" : "merged_row_\(index)")
        .listRowBackground(open ? Theme.brand.opacity(0.12) : nil)
        .accessibilityAddTraits(open ? .isSelected : [])
        .listRowInsets(EdgeInsets(top: 1, leading: 16, bottom: 1, trailing: 12))
        .alignmentGuide(.listRowSeparatorLeading) { _ in 56 }
        .swipeActions(edge: .leading, allowsFullSwipe: false) {
            Button { conversationPrefs.togglePinned(key) } label: {
                Label(conversationPrefs.isPinned(key) ? "message.unpin".localized : "message.pin".localized,
                      systemImage: conversationPrefs.isPinned(key) ? "pin.slash" : "pin")
            }
            .tint(.orange)
            Button { onShowAddress(conversation) } label: {
                Label("a11y.contact_qr".localized, systemImage: "qrcode")
            }
            .tint(Theme.brand)
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button(role: .destructive) { onDeleteConversation(conversation) } label: {
                Label("common.delete".localized, systemImage: "trash")
            }
            Button { conversationPrefs.toggleMuted(key) } label: {
                Label(conversationPrefs.isMuted(key) ? "chat.unmute".localized : "chat.mute".localized,
                      systemImage: conversationPrefs.isMuted(key) ? "bell" : "bell.slash")
            }
            .tint(.indigo)
        }
        .contextMenu {
            Button { conversationPrefs.togglePinned(key) } label: {
                Label(conversationPrefs.isPinned(key) ? "message.unpin".localized : "message.pin".localized,
                      systemImage: conversationPrefs.isPinned(key) ? "pin.slash" : "pin")
            }
            Button { conversationPrefs.toggleMuted(key) } label: {
                Label(conversationPrefs.isMuted(key) ? "chat.unmute".localized : "chat.mute".localized,
                      systemImage: conversationPrefs.isMuted(key) ? "bell" : "bell.slash")
            }
            if !LoRaChannelThread.isChannelKey(key) {
                Button { onOpenContact(conversation) } label: {
                    Label("conversation.open_contact".localized, systemImage: "person.crop.circle")
                }
            }
            Button { onShowAddress(conversation) } label: {
                Label("a11y.contact_qr".localized, systemImage: "qrcode")
            }
        }
    }

    private func groupRow(_ group: MessageGroup, index: Int) -> some View {
        let pinKey = ChatListMerge.pinKey(forGroup: group.id)
        let pinned = conversationPrefs.isPinned(pinKey)
        let open = splitActive && threadSelection.route == .group(group.id)
        return NavigationLink(value: ThreadRoute.group(group.id)) {
            ModernGroupRowView(group: group, isPinned: pinned)
                .accessibilityValue("chats.merged.group_a11y".localized)
        }
        .accessibilityIdentifier(index == 0 ? "merged_row_first" : "merged_row_\(index)")
        .listRowBackground(open ? Theme.brand.opacity(0.12) : nil)
        .accessibilityAddTraits(open ? .isSelected : [])
        .listRowInsets(EdgeInsets(top: 1, leading: 16, bottom: 1, trailing: 12))
        .alignmentGuide(.listRowSeparatorLeading) { _ in 56 }
        .swipeActions(edge: .leading, allowsFullSwipe: false) {
            Button { conversationPrefs.togglePinned(pinKey) } label: {
                Label(pinned ? "message.unpin".localized : "message.pin".localized,
                      systemImage: pinned ? "pin.slash" : "pin")
            }
            .tint(.orange)
            Button { groupInfo = group } label: {
                Label("a11y.group.info".localized, systemImage: "info.circle")
            }
            .tint(Theme.brand)
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            // The Groups segment's own swipe-to-delete, kept as it was.
            Button(role: .destructive) { groupManager.deleteGroup(group.id) } label: {
                Label("common.delete".localized, systemImage: "trash")
            }
            Button { groupManager.toggleMuteGroup(group.id) } label: {
                Label(group.isMuted ? "chat.unmute".localized : "chat.mute".localized,
                      systemImage: group.isMuted ? "bell" : "bell.slash")
            }
            .tint(.indigo)
        }
        .contextMenu {
            Button { conversationPrefs.togglePinned(pinKey) } label: {
                Label(pinned ? "message.unpin".localized : "message.pin".localized,
                      systemImage: pinned ? "pin.slash" : "pin")
            }
            Button { groupManager.toggleMuteGroup(group.id) } label: {
                Label(group.isMuted ? "chat.unmute".localized : "chat.mute".localized,
                      systemImage: group.isMuted ? "bell" : "bell.slash")
            }
            Button { groupInfo = group } label: {
                Label("a11y.group.info".localized, systemImage: "info.circle")
            }
            Divider()
            Button(role: .destructive) { leaveCandidate = group } label: {
                Label("button_leave_group".localized, systemImage: "rectangle.portrait.and.arrow.right")
            }
        }
    }

    // MARK: Deep link

    /// GroupsListView's own lookup (with its retries — groups may still be
    /// loading on a cold start from a notification).
    private func navigateToGroup(_ idString: String, attempt: Int = 1) {
        let found = UUID(uuidString: idString).flatMap { id in groupManager.groups.first { $0.id == id } }
            ?? groupManager.groups.first { $0.id.uuidString == idString }
        guard let group = found else {
            if attempt < 5 {
                DispatchQueue.main.asyncAfter(deadline: .now() + Double(attempt) * 0.3) {
                    navigateToGroup(idString, attempt: attempt + 1)
                }
            } else {
                pendingGroupId = nil
            }
            return
        }
        threadSelection.open(group)
        pendingGroupId = nil
    }
}

// MARK: - Filter chips

struct MergedFilterChips: View {
    @Binding var selected: ChatListFilter
    let counts: ChatListMerge.Counts

    private func count(_ f: ChatListFilter) -> Int {
        switch f {
        case .all: return 0
        case .unread: return counts.unread
        case .groups: return counts.groups
        case .people: return counts.people
        }
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(ChatListFilter.allCases) { f in
                    chip(f)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("merged_filters")
    }

    private func chip(_ f: ChatListFilter) -> some View {
        let isOn = selected == f
        let n = count(f)
        return Button {
            UISelectionFeedbackGenerator().selectionChanged()
            withAnimation(.snappy(duration: 0.2)) { selected = f }
        } label: {
            HStack(spacing: 6) {
                Text(f.titleKey.localized)
                    .font(.subheadline.weight(isOn ? .semibold : .medium))
                    .lineLimit(1)
                if n > 0 {
                    Text(n > 99 ? "99+" : "\(n)")
                        .font(.caption2.weight(.bold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 1)
                        .background(Capsule().fill(isOn ? Color.white.opacity(0.25) : Theme.brand))
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .foregroundColor(isOn ? .white : .primary)
            .background {
                if isOn {
                    Capsule().fill(Theme.brand)
                } else {
                    Capsule().fill(Color.clear)
                        .oledGlassCard(Capsule(), fallback: Color(.secondarySystemFill))
                }
            }
            .oledFocusGlow(isOn ? Theme.brand : .clear, radius: isOn ? 8 : 0)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(n > 0 ? "\(f.titleKey.localized), \(n)" : f.titleKey.localized)
        .accessibilityAddTraits(isOn ? [.isSelected] : [])
        .accessibilityIdentifier("merged_filter_\(f.rawValue)")
    }
}

// MARK: - Group type sub-filter

/// __MERGED_GROUP_TYPES_2026_11_18__ The classic Groups segment's chips (All ·
/// Admin-only · Collaborative · Public), shown under "Groups" in the merged list.
struct MergedGroupTypeChips: View {
    @Binding var selected: GroupType?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ModernFilterChip(title: "filter_all".localized, isSelected: selected == nil) {
                    withAnimation(.snappy(duration: 0.2)) { selected = nil }
                }
                .accessibilityIdentifier("merged_group_type_all")
                ForEach(GroupType.allCases, id: \.self) { type in
                    ModernFilterChip(title: type.displayName, icon: type.icon, color: type.color,
                                     isSelected: selected == type) {
                        withAnimation(.snappy(duration: 0.2)) { selected = type }
                    }
                    .accessibilityIdentifier("merged_group_type_\(type.rawValue)")
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 6)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("merged_group_types")
    }
}

// MARK: - Cards

struct DiscoverTeaserCard: View {
    let onDiscover: () -> Void
    let onCreate: () -> Void
    let onDismiss: () -> Void
    @Environment(\.oledBlackActive) private var oled

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 12) {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(LinearGradient(colors: [Theme.brand, Color(red: 0.94, green: 0.28, blue: 0.56)],
                                         startPoint: .topLeading, endPoint: .bottomTrailing))
                    .frame(width: 44, height: 44)
                    .overlay(Image(systemName: "globe").font(.system(size: 20, weight: .semibold)).foregroundColor(.white))
                    .accessibilityHidden(true)
                Text("chats.merged.teaser_title".localized)
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 4)
                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.secondary)
                        .frame(width: 28, height: 28)
                        .background(Circle().fill(Color(.tertiarySystemFill)))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("common.dismiss".localized)
            }
            Text("chats.merged.teaser_body".localized)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 10) {
                Button(action: onDiscover) {
                    Label("chats.merged.teaser_cta".localized, systemImage: "safari")
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 16).padding(.vertical, 9)
                        .foregroundColor(.white)
                        .background(Capsule().fill(Theme.brand))
                        .oledFocusGlow()
                }
                .buttonStyle(PressScaleButtonStyle())
                .accessibilityIdentifier("merged_teaser_discover")
                Button(action: onCreate) {
                    Label("chats.merged.new_group".localized, systemImage: "person.2.badge.plus")
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 16).padding(.vertical, 9)
                        .foregroundColor(.primary)
                        .background(Capsule().strokeBorder(Color.primary.opacity(0.18), lineWidth: 1))
                }
                .buttonStyle(PressScaleButtonStyle())
            }
        }
        .padding(18)
        .background {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(LinearGradient(colors: [Theme.brand.opacity(oled ? 0.30 : 0.14),
                                              Color(red: 0.94, green: 0.28, blue: 0.56).opacity(oled ? 0.06 : 0.08)],
                                     startPoint: .topLeading, endPoint: .bottomTrailing))
        }
        .oledGlassEdge(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("merged_discover_teaser")
    }
}

/// "Group nearby" — public groups heard on the mesh that the user has not
/// joined. A card IN the list (tap → Discover, ✕ to dismiss), replacing, in
/// merged mode, the banner that floated over whatever pane was open.
struct NearbyGroupsCard: View {
    let ads: [PublicGroupAd]
    let onOpen: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        let first = ads[0]
        HStack(spacing: 12) {
            Circle().fill(Theme.brand)
                .frame(width: 36, height: 36)
                .overlay(Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.system(size: 15, weight: .semibold)).foregroundColor(.white))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text("chats.merged.nearby_title".localized + " · "
                     + String(format: "group_member_count".localized, first.memberCount))
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
                Text(first.groupName + (ads.count > 1 ? " +\(ads.count - 1)" : ""))
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 4)
            Button(action: onOpen) {
                Text("button_join".localized)
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 14).padding(.vertical, 6)
                    .background(Capsule().fill(Theme.brand))
            }
            .buttonStyle(.borderless)
            Button(action: onDismiss) {
                Image(systemName: "xmark").font(.system(size: 12, weight: .bold)).foregroundColor(.secondary)
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("common.dismiss".localized)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Theme.brand.opacity(0.10)))
        .oledGlassEdge(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        .accessibilityIdentifier("merged_nearby_card")
    }
}

// MARK: - The "New…" sheet

/// What the "New…" sheet asked for. Performed by MessagesListView once the
/// sheet is gone, so the next sheet is never presented on top of a closing one.
enum MergedNewAction: Identifiable {
    case chat, group, discover, scanInvite, voiceCall, videoCall
    var id: Self { self }
}

struct NewChatSheetView: View {
    let onPick: (MergedNewAction) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 8) {
                    row(.chat, icon: "person.crop.circle.badge.plus", title: "chats.merged.new_chat",
                        detail: "chats.merged.new_chat_desc", colors: [Theme.brand, Color(red: 0.48, green: 0.25, blue: 0.95)],
                        shortcut: "⌘N")
                    row(.group, icon: "person.2.badge.plus", title: "chats.merged.new_group",
                        detail: "chats.merged.new_group_desc",
                        colors: [Color(red: 0.48, green: 0.25, blue: 0.95), Color(red: 0.94, green: 0.28, blue: 0.56)],
                        shortcut: "⇧⌘N")
                    row(.discover, icon: "globe", title: "chats.merged.discover",
                        detail: "chats.merged.discover_desc",
                        colors: [Color(red: 0.04, green: 0.62, blue: 0.43), Color(red: 0.17, green: 0.77, blue: 0.63)],
                        shortcut: nil)
                    row(.scanInvite, icon: "qrcode.viewfinder", title: "chats.merged.scan_invite",
                        detail: "chats.merged.scan_invite_desc",
                        colors: [Color(red: 0.18, green: 0.44, blue: 0.93), Color(red: 0.31, green: 0.76, blue: 0.97)],
                        shortcut: nil)

                    Text("chats.merged.calls_header".localized)
                        .font(.footnote.weight(.semibold))
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 10)
                        .padding(.leading, 6)
                    HStack(spacing: 10) {
                        callButton(.voiceCall, icon: "phone.fill", title: "chat.voice_call")
                        callButton(.videoCall, icon: "video.fill", title: "chat.video_call")
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .navigationTitle("chats.merged.new_title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                    }
                    .accessibilityLabel("common.dismiss".localized)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .accessibilityIdentifier("merged_new_sheet")
    }

    private func pick(_ action: MergedNewAction) {
        onPick(action)
        dismiss()
    }

    private func row(_ action: MergedNewAction, icon: String, title: String, detail: String,
                     colors: [Color], shortcut: String?) -> some View {
        Button { pick(action) } label: {
            HStack(spacing: 14) {
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .fill(LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing))
                    .frame(width: 44, height: 44)
                    .overlay(Image(systemName: icon).font(.system(size: 19, weight: .semibold)).foregroundColor(.white))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title.localized).font(.body.weight(.semibold)).foregroundColor(.primary).lineLimit(1)
                    Text(detail.localized).font(.footnote).foregroundColor(.secondary).lineLimit(2)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 6)
                #if targetEnvironment(macCatalyst)
                if let shortcut {
                    Text(shortcut).font(.footnote.monospaced()).foregroundColor(.secondary)
                        .accessibilityHidden(true)
                }
                #endif
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundColor(.secondary.opacity(0.6))
                    .accessibilityHidden(true)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color(.secondarySystemGroupedBackground)))
            .oledGlassEdge(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("merged_new_\(action)")
    }

    private func callButton(_ action: MergedNewAction, icon: String, title: String) -> some View {
        Button { pick(action) } label: {
            Label(title.localized, systemImage: icon)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .foregroundColor(.primary)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.brand.opacity(0.14)))
                .oledGlassEdge(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(PressScaleButtonStyle())
    }
}
