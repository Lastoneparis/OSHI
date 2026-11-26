//
//  GroupViews.swift
//  ✅ IMPROVED: Modern UX design + Brand color #5616EC + Gradient avatars
//  ✅ FULLY LOCALIZED: All user-facing strings use .localized
//

import SwiftUI
import AVFoundation
import AVKit
import PDFKit
import CoreImage.CIFilterBuiltins
import MapKit
// CodeScanner is optional - if not available, QR buttons will show alert

// MARK: - Brand Color
private let groupBrandColor = Theme.brand

// MARK: - Contact-alias memo (perf)

/// Memoised view of the `contactAliases` blob in `UserDefaults`.
///
/// 🔑 The blob is written from several unrelated places — the alias editor,
/// `MultiDeviceSyncManager` (contact sync from another device),
/// `IdentityManager` — so a notification-driven cache would rot the first time
/// someone adds a write path and forgets to post. Instead the memo is keyed on
/// the raw `Data` itself: if the bytes are unchanged we hand back the decoded
/// map, otherwise we decode once and remember. A `Data ==` is a length check
/// plus a memcmp over a few hundred bytes; the `JSONDecoder` pass it replaces
/// allocates a `String` per key AND per value. Staleness is impossible by
/// construction.
///
/// Callers are SwiftUI view bodies (main actor), so the un-synchronised statics
/// are never touched concurrently. Do not call this from a background queue.
private enum GroupContactAliases {
    private static var cachedData: Data?
    private static var cachedMap: [String: String] = [:]

    static var map: [String: String] {
        let data = UserDefaults.standard.data(forKey: "contactAliases") ?? Data()
        if let previous = cachedData, previous == data { return cachedMap }
        cachedData = data
        cachedMap = (try? JSONDecoder().decode([String: String].self, from: data)) ?? [:]
        return cachedMap
    }

    // __SHARED_NICKNAME_2026_09_22__ Meme memo, pour les pseudos PARTAGES par
    // les contacts (`SharedNickname`), cle sur les octets bruts pour la meme
    // raison: aucune invalidation a oublier.
    private static var cachedNickData: Data?
    private static var cachedNickMap: [String: String] = [:]

    static var nicknames: [String: String] {
        let data = UserDefaults.standard.data(forKey: SharedNickname.defaultsKey) ?? Data()
        if let previous = cachedNickData, previous == data { return cachedNickMap }
        cachedNickData = data
        cachedNickMap = SharedNickname.decode(data)
        return cachedNickMap
    }

    /// Nom d'affichage: alias local > pseudo partage > nil.
    static func displayName(_ key: String) -> String? {
        SharedNickname.resolve(alias: map[key],
                               nickname: SharedNickname.nickname(for: key, in: nicknames))
    }
}

// MARK: - Group picture decode cache (perf)

/// Memoised `UIImage` for a group's `groupPictureData`.
///
/// 🔴 This is the one that actually costs frames in the groups list.
/// `MessageGroup.groupPictureData` is a `Data` blob compressed to **up to
/// 500 KB** (`MessageGroupManager.updateGroupPicture`, GroupMessaging.swift
/// ~1892). Three view bodies built a brand-new `UIImage(data:)` from it on
/// EVERY body pass: `ModernGroupRowView.groupPictureImage` (once per group row
/// in the list), `GroupChatView.groupToolbarContent` (the group chat title bar,
/// which re-renders on every arriving message and every typing tick) and
/// `GroupInfoView.groupPictureImage`.
///
/// 🔑 The expensive part is not `UIImage(data:)` itself — it is that a UIImage
/// caches its *decoded bitmap* internally, and a fresh instance every pass
/// throws that cache away, so UIKit re-inflates the JPEG at draw time, on the
/// main thread, for a 32–54 pt circle. Reusing the instance keeps the decoded
/// bitmap alive.
///
/// 🔴🔴 And "500 KB" says nothing about how big that bitmap is.
/// `compressImageData` (GroupMessaging.swift ~1892) only walks the JPEG
/// *quality* down — it NEVER resizes. A 4032×3024 iPhone photo squeezed to
/// 500 KB is still 4032×3024, i.e. **~48 MB** once decoded. So this must
/// downsample, not just memoise: caching full-resolution bitmaps for every
/// group would trade a per-frame stall for an out-of-memory kill.
/// `MediaManager.safeUIImage` already does the ImageIO thumbnail dance
/// (`CGImageSourceCreateThumbnailAtIndex`), which decodes *directly* at the
/// target size and never materialises the full bitmap — reuse it rather than
/// writing a fourth copy.
///
/// 400 px covers the largest place a group picture is drawn (100 pt at @3x =
/// 300 px, in `GroupInfoView`) with headroom, so nothing gets softer. Each
/// entry is then ≤ 400×400×4 ≈ 640 KB, and the cache is capped, so the worst
/// case is a few MB instead of hundreds.
///
/// Keyed on the group id, validated against the raw `Data`, exactly like
/// `GroupContactAliases`: if the bytes changed we re-decode, so a group picture
/// edit or an incoming picture update can never show stale. No notification to
/// forget to post.
///
/// Callers are SwiftUI view bodies (main actor); do not call off-main.
private enum GroupPictureCache {
    /// Largest pixel size any caller draws, plus headroom. See above.
    private static let maxPixelSize: CGFloat = 400
    /// Hard cap on resident entries. A user with 50 groups scrolling the list
    /// must not accumulate 50 bitmaps; the visible window is a handful of rows.
    private static let capacity = 12

    private static var cache: [UUID: (data: Data, image: UIImage?)] = [:]
    /// Insertion order, oldest first — enough to evict deterministically without
    /// pulling in NSCache (which would also need a class-boxed value type).
    private static var order: [UUID] = []

    static func image(for id: UUID, data: Data?) -> UIImage? {
        guard let data = data else {
            // Picture removed — drop the entry so the memory goes with it.
            cache.removeValue(forKey: id)
            order.removeAll { $0 == id }
            return nil
        }
        if let entry = cache[id], entry.data == data { return entry.image }

        let decoded = MediaManager.safeUIImage(from: data, maxDimension: maxPixelSize)
        if cache[id] == nil {
            order.append(id)
            if order.count > capacity {
                let evicted = order.removeFirst()
                cache.removeValue(forKey: evicted)
            }
        }
        cache[id] = (data, decoded)
        return decoded
    }
}

// MARK: - Keyboard Helper
private func hideKeyboard() {
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
}

// MARK: - Groups List View

struct GroupsListView: View {
    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager
    @State private var showCreateGroup = false
    @State private var showDiscoverGroups = false
    @State private var selectedFilter: GroupType?
    @State private var searchText = ""
    @State private var showGroupInfo: MessageGroup?

    // Notification deep link: open specific group chat
    @Binding var pendingGroupId: String?
    /// __FOLDABLE_LAYOUT_2026_11_16__ The open group is the Chats tab's
    /// `ThreadSelection.route` (see AdaptiveThreadLayout.swift): pushed here on a
    /// phone, drawn beside this list when wide.
    @EnvironmentObject private var threadSelection: ThreadSelection
    @Environment(\.threadSplitActive) private var splitActive
    
    var sortedGroups: [MessageGroup] {
        let searchLower = searchText.lowercased()
        return groupManager.groups
            .filter { group in
                (selectedFilter == nil || group.groupType == selectedFilter) &&
                (searchText.isEmpty ||
                 group.name.localizedCaseInsensitiveContains(searchLower) ||
                 group.groupType.displayName.localizedCaseInsensitiveContains(searchLower))
            }
            .sorted { $0.lastMessageAt > $1.lastMessageAt }
    }
    
    var body: some View {
        // 🔧 FIX (2026-08-05): tapping a group notification did not open the
        // group.
        //
        // The deep link was wired correctly end to end — the delegate posts
        // `OpenGroupConversation`, ContentView sets `pendingGroupId`,
        // ChatsTabView switches to the Groups segment, and this view consumes it
        // from both `onChange` and `onAppear`. The last step was the broken one:
        // navigation is driven by `.navigationDestination(isPresented:)`, which
        // is a NavigationStack API and does NOTHING inside a `NavigationView`.
        // The state flipped, the log line printed "Navigating to group", and no
        // push ever happened — which is exactly why this looked like it should
        // work on a read-through.
        //
        // `NavigationLink(destination:)` below keeps working under a Stack, so
        // the swap is contained.
        NavigationStack(path: threadSelection.path(for: .group, split: splitActive)) {
            VStack(spacing: 0) {
                // Search bar
                HStack(spacing: 10) {
                    HStack {
                        Image(systemName: "magnifyingglass")
                            .foregroundColor(.secondary)
                        TextField("search_groups".localized, text: $searchText)
                            .font(.body)
                            .submitLabel(.done)
                            .onSubmit {
                                hideKeyboard()
                            }
                    }
                    .padding(10)
                    .background(Color(.systemGray6))
                    .cornerRadius(10)
                    
                    // Cancel/Clear button - always visible when searching
                    if !searchText.isEmpty {
                        Button {
                            searchText = ""
                            hideKeyboard()
                        } label: {
                            Text("cancel".localized)
                                .foregroundColor(groupBrandColor)
                        }
                    }
                }
                .padding(.horizontal)
                .padding(.vertical, 8)
                
                // Filter tabs
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ModernFilterChip(title: "filter_all".localized, isSelected: selectedFilter == nil) {
                            selectedFilter = nil
                        }
                        
                        ForEach(GroupType.allCases, id: \.self) { type in
                            ModernFilterChip(
                                title: type.displayName,
                                icon: type.icon,
                                color: type.color,
                                isSelected: selectedFilter == type
                            ) {
                                selectedFilter = type
                            }
                        }
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 8)
                }
                
                List {
                    ForEach(Array(sortedGroups.enumerated()), id: \.element.id) { index, group in
                        // __GROUP_INFO_WAS_A_LONG_PRESS_ONLY_2026_11_04__ (Anatole,
                        // on Android, and iOS had the identical fault) Info lived
                        // ONLY behind a leading swipe and a long-press context menu.
                        // Both are invisible: nothing on screen said either existed,
                        // so renaming a group, changing its photo and managing its
                        // members were all behind a gesture a user has to be told
                        // about. Reported in exactly those words.
                        //
                        // __COMPACT_ROWS_2026_08_22__ still holds, because that note
                        // is about ROW HEIGHT: the control it removed was a 44×44
                        // square, and this one is capped at 28 and sits beside a
                        // 52-point avatar. It cannot be what sets the height.
                        //
                        // `.buttonStyle(.borderless)` is load-bearing inside a List
                        // row: without it the row's own tap target swallows the
                        // button, and SwiftUI gives the whole row the button's
                        // action — tapping anywhere would open info instead of the
                        // chat.
                        HStack(spacing: 0) {
                            if splitActive {
                                // __DUO_POLISH_2026_11_18__ Wide: select into the
                                // detail pane, no push chevron (see MessagesListView).
                                Button { threadSelection.open(group) } label: {
                                    ModernGroupRowView(group: group)
                                        .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                            } else {
                                NavigationLink(value: ThreadRoute.group(group.id)) {
                                    ModernGroupRowView(group: group)
                                }
                            }
                            Button {
                                showGroupInfo = group
                            } label: {
                                Image(systemName: "ellipsis")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundColor(.secondary)
                                    .frame(width: 28, height: 28)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("a11y.group.info".localized)
                        }
                        // Stable identifier for deterministic screenshot automation.
                        .accessibilityIdentifier(index == 0 ? "group_row_first" : "group_row_\(index)")
                        // Wide layout: the group shown in the detail pane.
                        .listRowBackground(splitActive && threadSelection.route == .group(group.id)
                                           ? groupBrandColor.opacity(0.12) : nil)
                        .accessibilityAddTraits(splitActive && threadSelection.route == .group(group.id) ? .isSelected : [])
                        // __ROW_PITCH_2026_08_22__ Same correction as the
                        // conversation list, same reason: a plain `List` pads every
                        // row ~11 pt top and bottom on its own, which is where the
                        // height actually was. See MessagesListView.
                        // __ROW_PITCH_TELEGRAM_2026_08_22__ Deuxième passe, même
                        // mesure et mêmes trois leviers que la liste des
                        // conversations: interligne et marges, jamais le corps du
                        // texte. Voir MessagesListView.
                        .listRowInsets(EdgeInsets(top: 1, leading: 16, bottom: 1, trailing: 12))
                        // __COMPACT_ROWS_2026_08_22__ Le bouton « infos » était un
                        // carré de 44×44 DANS la rangée, et c'est lui qui en fixait
                        // la hauteur. Balayage vers la droite (celui de gauche est
                        // libre, `.onDelete` occupe celui de droite) et appui long.
                        .swipeActions(edge: .leading, allowsFullSwipe: false) {
                            Button {
                                showGroupInfo = group
                            } label: {
                                Label("a11y.group.info".localized, systemImage: "info.circle")
                            }
                            .tint(groupBrandColor)
                        }
                        .contextMenu {
                            Button {
                                showGroupInfo = group
                            } label: {
                                Label("a11y.group.info".localized, systemImage: "info.circle")
                            }
                        }
                    }
                    .onDelete(perform: deleteGroups)
                }
                .listStyle(.plain)
                .sidebarColumnContent(splitActive)
                .navigationTitle("nav_title_groups".localized)
                .toolbar {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button {
                            showDiscoverGroups = true
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "globe")
                                    .foregroundColor(groupBrandColor)
                                Text("button_discover".localized)
                                    .font(.caption)
                                    .foregroundColor(groupBrandColor)
                            }
                        }
                        .accessibilityLabel("button_discover".localized)
                        .accessibilityHint("a11y.group.discover_hint".localized)
                    }

                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button {
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            showCreateGroup = true
                        } label: {
                            Image(systemName: "plus.circle.fill")
                                .font(.title3)
                                .foregroundColor(groupBrandColor)
                        }
                        .accessibilityLabel("button_create_group".localized)
                    }
                }
                .sheet(isPresented: $showCreateGroup) {
                    CreateGroupView()
                }
                .sheet(isPresented: $showDiscoverGroups) {
                    DiscoverPublicGroupsView()
                }
                .sheet(item: $showGroupInfo) { group in
                    GroupInfoSheet(groupId: group.id)
                }
                .overlay {
                    if groupManager.groups.isEmpty {
                        ModernEmptyGroupsView(showCreateGroup: $showCreateGroup)
                    }
                }
                // Rows and notification deep links alike (see `path(for:)`).
                .navigationDestination(for: ThreadRoute.self) { route in
                    ThreadDestinationView(route: route)
                }
            }
        }
        // MARK: - Notification Deep Link: Open group chat from pending ID
        // Navigation is driven solely by pendingGroupId binding from ContentView
        .onChange(of: pendingGroupId) { _, newValue in
            if let groupId = newValue {
                navigateToGroup(groupId)
            }
        }
        .onAppear {
            // Check for pending navigation when view appears (tab switch or cold launch)
            if let groupId = pendingGroupId {
                navigateToGroup(groupId)
            }
            // Fire an immediate IPFS poll so by the time the user taps into a
            // group, today's queued group messages are already merged into
            // `groupManager.groupMessages`. Without this the chat view opens
            // empty (dark-mode wallpaper = solid black) until the next 2s
            // polling tick lands.
            Task.detached(priority: .userInitiated) {
                MessageManager.shared?.checkPendingIPFSMessages()
            }
        }
    }

    // MARK: - Navigate to Group

    private func navigateToGroup(_ groupIdString: String, attempt: Int = 1) {
        OshiLog.messaging.info("📱 OpenGroupChat: \(groupIdString.prefix(20))... (attempt \(attempt))")

        var foundGroup: MessageGroup?
        if let uuid = UUID(uuidString: groupIdString) {
            foundGroup = groupManager.groups.first(where: { $0.id == uuid })
        }
        if foundGroup == nil {
            foundGroup = groupManager.groups.first(where: { $0.id.uuidString == groupIdString })
        }

        guard let group = foundGroup else {
            // Retry up to 5 times with increasing delay (data may still be loading)
            if attempt < 5 {
                let delay = Double(attempt) * 0.3
                OshiLog.messaging.info("⏳ Group not found yet, retrying in \(delay)s...")
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                    navigateToGroup(groupIdString, attempt: attempt + 1)
                }
            } else {
                OshiLog.messaging.info("⚠️ Group not found after 5 attempts: \(groupIdString)")
                pendingGroupId = nil
            }
            return
        }

        // One step: the route replaces whatever thread was open (the old
        // false → 0.15 s → true bounce was only needed by `isPresented:`).
        threadSelection.open(group)
        pendingGroupId = nil
        OshiLog.messaging.info("✅ Navigating to group: \(group.name)")
    }

    private func deleteGroups(at offsets: IndexSet) {
        for index in offsets {
            let group = sortedGroups[index]
            groupManager.deleteGroup(group.id)
        }
    }
}

// MARK: - Modern Filter Chip

struct ModernFilterChip: View {
    let title: String
    var icon: String?
    var color: Color = groupBrandColor
    let isSelected: Bool
    let action: () -> Void
    
    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let icon = icon {
                    Image(systemName: icon)
                        .font(.caption)
                }
                Text(title)
                    .fontWeight(isSelected ? .semibold : .regular)
            }
            .font(.subheadline)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(
                Capsule()
                    .fill(isSelected ? color : Color(.systemGray6))
            )
            .foregroundColor(isSelected ? .white : .primary)
            .overlay(
                Capsule()
                    .stroke(isSelected ? color : Color.clear, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}

// Keep old FilterChip for backward compatibility
struct FilterChip: View {
    let title: String
    var icon: String?
    var color: Color = groupBrandColor
    let isSelected: Bool
    let action: () -> Void
    
    var body: some View {
        ModernFilterChip(title: title, icon: icon, color: color, isSelected: isSelected, action: action)
    }
}

// MARK: - Modern Group Row

struct ModernGroupRowView: View {
    let group: MessageGroup
    /// __MERGED_CHATS_2026_11_18__ The merged chat list pins groups too; the
    /// Groups segment never passes it, so its rows are unchanged.
    var isPinned: Bool = false
    // __COMPACT_ROWS_2026_08_22__ `onInfoTap` a disparu avec le bouton qu'il
    // servait. Le laisser dans la signature aurait fabriqué le pire des deux
    // mondes: un rappel qu'on peut passer et qui n'est plus jamais appelé.
    // L'accès aux infos est un balayage et un appui long, câblés dans
    // `GroupsListView`.
    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager
    
    // 🔴 PERF: `getUnreadCount` is `groupMessages[id]?.filter { !$0.isRead }.count`
    // (GroupMessaging.swift ~3323) — an O(messages-in-group) scan. This computed
    // property was read THREE times per body pass (the timestamp's foreground
    // colour, the `if unreadCount > 0` badge test, and the badge's own label),
    // so a list of 20 groups holding 500 messages each walked 30 000 messages
    // per frame, on the main thread. `body` now reads it once into a local and
    // reuses that; see the `let unread` at the top of `body`.
    var unreadCount: Int {
        groupManager.getUnreadCount(for: group.id)
    }

    // __AVATAR_COLOURS_WERE_TWO_COLOURS_2026_11_05__ See `AvatarIdentity`.
    // This was a verbatim copy of the conversation-list palette, including the
    // per-launch-unstable `hashValue` keying; both are now shared and stable.
    private var avatarGradient: [Color] {
        AvatarIdentity.gradient(for: group.id.uuidString)
    }

    // 🖼️ Convert group picture data to UIImage.
    //
    // 🔴 PERF: this built a fresh `UIImage` from a blob of up to 500 KB on every
    // body pass, for every group row on screen — and a fresh `UIImage` has no
    // decoded bitmap, so UIKit re-inflated the JPEG at draw time. See
    // `GroupPictureCache` at the top of this file.
    private var groupPictureImage: UIImage? {
        GroupPictureCache.image(for: group.id, data: group.groupPictureData)
    }
    
    /// __COMPACT_ROWS_2026_08_22__ L'aperçu du dernier message, en clair.
    ///
    /// Le contenu des messages de groupe est stocké en UTF-8 dans
    /// `encryptedContent` côté appareil (voir `MessageGroupManager.decryptMessage`),
    /// donc l'aperçu ne coûte qu'une lecture — pas de déchiffrement asynchrone
    /// comme dans `ConversationRow`.
    ///
    /// Les payloads de contrôle (position, résumé d'appel, réponse, média…) ne
    /// doivent JAMAIS s'afficher bruts: `OSHIControlPayload.previewText(for:)` est
    /// le catalogue qui les résume, le même que la liste des conversations.
    private var lastMessagePreview: String? {
        guard let last = groupManager.groupMessages[group.id]?.last else { return nil }
        if last.isSystemMessage { return nil }

        // ⚠️ Les médias d'abord. Dans un GROUPE, la pièce jointe vit dans
        // `encryptedContent` — c'est le blob lui-même, pas du texte (voir le
        // commentaire de `uploadGroupMessagesForSync`). Le décoder en UTF-8
        // rendrait nil neuf fois sur dix, et une bouillie la dixième.
        // ⚠️ `GroupMessage.mediaType` est le `MediaType` de GroupMessaging.swift,
        // PAS `MediaManager.MediaType` de la messagerie 1:1 — deux énumérations
        // homonymes dont le premier cas ne porte pas le même nom (`photo` ici,
        // `image` là).
        if last.isVideoNote { return "🎥 " + "videonote.title".localized }  // __VIDEO_NOTE_2026_09_24__
        if let mediaType = last.mediaType {
            switch mediaType {
            case .photo:    return "📷 " + "media_image".localized
            case .video:    return "🎥 " + "media_video".localized
            case .audio:    return "🎤 " + "media_audio".localized
            case .document: return "📎 " + "media_file".localized
            case .contact:  return "👤 " + "media_file".localized
            }
        }

        let raw = last.plaintextContent
            ?? String(data: last.encryptedContent, encoding: .utf8)
            ?? ""
        guard !raw.isEmpty else { return nil }
        // __TEXT_FORMATTING_2026_11_24__ a row cannot style a run: no markers.
        let summary = InlineMarkup.stripped(OSHIControlPayload.previewText(for: raw))
        return summary.isEmpty ? nil : summary
    }

    // __COMPACT_ROWS_2026_08_22__ Demande de Hugo: « plus compact et plus petit,
    // comme Telegram », et « deux lignes d'aperçu au maximum ».
    //
    // Deux choses fixaient la hauteur de cette rangée: l'avatar de 54 pt, et un
    // bouton « infos » encadré à 44×44 posé DANS la ligne. Le bouton part sur un
    // balayage (voir `GroupsListView`), l'avatar descend à 48.
    //
    // Et la ligne du milieu montre enfin le DERNIER MESSAGE. Elle affichait
    // « Collaboratif • 7 membres » — deux faits qui ne changent presque jamais,
    // à l'endroit exact où l'œil cherche ce qui vient d'arriver. Le type reste
    // lisible: c'est la pastille colorée sur l'avatar. La mention type + membres
    // revient telle quelle quand le groupe n'a encore aucun message, où elle est
    // la seule chose à dire.
    var body: some View {
        // 🔑 Read the O(n) unread scan ONCE per body pass instead of three
        // times. `body` is `@ViewBuilder`, which permits local declarations —
        // the same trick `ConversationRow.body` already uses for `badge`
        // (MessagesListView.swift ~1019).
        let unread = unreadCount
        let preview = lastMessagePreview
        // __LIST_ROWS_NAME_FIRST_2026_11_17__ Same row as `ConversationRow`:
        // the group NAME in bold on the first line (mute icon, time on the
        // right), the last message under it, unread pill at its end. The
        // 2026-11-09 name-under-avatar caption is gone; the brand ring stays.
        HStack(alignment: .center, spacing: 12) {
            ZStack(alignment: .bottomTrailing) {
                ZStack {
                    Circle()
                        .stroke(groupBrandColor, lineWidth: 2)
                        .frame(width: 50, height: 50)

                    // 🖼️ Group picture takes priority
                    if let image = groupPictureImage {
                        Image(uiImage: image)
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                            .frame(width: 44, height: 44)
                            .clipShape(Circle())
                            .shadow(color: .black.opacity(0.15), radius: 3, x: 0, y: 2)
                    } else if let avatar = group.avatar, !avatar.isEmpty {
                        Text(avatar)
                            .font(.scaled(25))
                            .frame(width: 44, height: 44)
                            .background(
                                Circle()
                                    .fill(LinearGradient(
                                        colors: avatarGradient.map { $0.opacity(0.2) },
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    ))
                            )
                    } else {
                        // __AVATAR_PALETTE_V2_2026_11_17__ gradient + light blob.
                        AvatarFill(key: group.id.uuidString, size: 44)
                            .overlay(
                                Text(group.name.prefix(2).uppercased())
                                    .font(.scaled(16, weight: .semibold, design: .rounded))
                                    .foregroundColor(AvatarIdentity.ink(for: group.id.uuidString))
                            )
                            .shadow(color: avatarGradient[1].opacity(0.3), radius: 3, x: 0, y: 1)
                    }
                }
                .frame(width: 50, height: 50)

                // Type badge — rides the ring's outer edge instead of the
                // bare avatar's, so it clears the brand stroke.
                Circle()
                    .fill(group.groupType.color)
                    .frame(width: 16, height: 16)
                    .overlay(
                        Image(systemName: group.groupType.icon)
                            .font(.scaled(9, weight: .bold))
                            .foregroundColor(.white)
                    )
                    .offset(x: 1, y: 1)
            }
            .frame(width: 50, height: 50)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(group.name)
                        .font(.scaled(16, weight: .bold))
                        .foregroundColor(.primary)
                        .lineLimit(1)
                        .truncationMode(.tail)

                    if group.isMuted {
                        Image(systemName: "bell.slash.fill")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                            .accessibilityLabel("chat.mute".localized)
                    }
                    if isPinned {
                        Image(systemName: "pin.fill")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                            .accessibilityLabel("message.pin".localized)
                    }

                    Spacer(minLength: 6)

                    // __LIST_TIME_TICKS_2026_11_09__ self-refreshing label.
                    ListTimestampText(date: group.lastMessageAt)
                        .font(.caption)
                        .foregroundColor(unread > 0 ? groupBrandColor : .secondary)
                        .lineLimit(1)
                        .fixedSize()
                }

                HStack(alignment: .top, spacing: 6) {
                    if let preview {
                        Text(preview)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                    } else {
                        // Groupe encore vide: dire ce qu'il EST, faute de dire ce
                        // qui s'y est passé.
                        HStack(spacing: 4) {
                            Text(group.groupType.displayName)
                                .foregroundColor(group.groupType.color)
                            Text("•")
                                .foregroundColor(.secondary)
                            Text(String(format: "group_member_count".localized, group.memberCount))
                                .foregroundColor(.secondary)
                        }
                        .font(.subheadline)
                        .lineLimit(1)
                    }

                    Spacer(minLength: 4)

                    // Unread badge, at the end of the preview line.
                    if unread > 0 {
                        Text("\(unread)")
                            .font(.caption)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .frame(minWidth: 20)
                            .background(
                                Capsule()
                                    .fill(LinearGradient(
                                        colors: [groupBrandColor, groupBrandColor.opacity(0.8)],
                                        startPoint: .leading,
                                        endPoint: .trailing
                                    ))
                            )
                            .fixedSize()
                    }
                }
            }
        }
        .accessibilityElement(children: .combine)
        .padding(.vertical, 4)
    }

    /// 🔧 PERF: drawn once per group row in the groups list, on every body pass.
    /// This is the exact twin of `ConversationRow.formatTimestamp`
    /// (MessagesListView.swift) — it was missed in the first pass because the
    /// groups LIST was never looked at, only the group THREAD. See
    /// `OshiRowFormatters` in ChatView.swift. Same branches, same output.
    private func formatTimestamp(_ date: Date) -> String {
        if Calendar.current.isDateInToday(date) {
            return OshiRowFormatters.string(date, format: "HH:mm")
        } else if Calendar.current.isDateInYesterday(date) {
            return "date_yesterday".localized
        } else {
            return OshiRowFormatters.string(date, format: "MMM d")
        }
    }
}

// Keep old GroupRowView for backward compatibility
struct GroupRowView: View {
    let group: MessageGroup
    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager
    
    var body: some View {
        ModernGroupRowView(group: group)
    }
}

// MARK: - Modern Empty Groups View

struct ModernEmptyGroupsView: View {
    @Binding var showCreateGroup: Bool
    
    var body: some View {
        VStack(spacing: 24) {
            ZStack {
                Circle()
                    .fill(LinearGradient(
                        colors: [groupBrandColor.opacity(0.1), groupBrandColor.opacity(0.05)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ))
                    .frame(width: 100, height: 100)
                
                Image(systemName: "person.3.fill")
                    .font(.scaled(40))
                    .foregroundStyle(
                        LinearGradient(
                            colors: [groupBrandColor, groupBrandColor.opacity(0.7)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
            }
            .accessibilityHidden(true)

            VStack(spacing: 8) {
                Text("empty_groups_title".localized)
                    .font(.title2)
                    .fontWeight(.bold)

                Text("empty_groups_subtitle".localized)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)
            }
            .accessibilityElement(children: .combine)

            Button {
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                showCreateGroup = true
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "plus.circle.fill")
                    Text("button_create_group".localized)
                }
                .fontWeight(.semibold)
                .foregroundColor(.white)
                .padding(.horizontal, 30)
                .padding(.vertical, 14)
                .background(
                    LinearGradient(
                        colors: [groupBrandColor, groupBrandColor.opacity(0.8)],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                )
                .cornerRadius(25)
                .shadow(color: groupBrandColor.opacity(0.3), radius: 8, x: 0, y: 4)
            }
            
            Text("groups_empty_hint".localized)
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding()
    }
}

// Keep old EmptyGroupsView for backward compatibility
struct EmptyGroupsView: View {
    @Binding var showCreateGroup: Bool
    
    var body: some View {
        ModernEmptyGroupsView(showCreateGroup: $showCreateGroup)
    }
}

// MARK: - Group Info Sheet

struct GroupInfoSheet: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager
    let groupId: UUID

    @State private var qrCodeImage: UIImage?
    @State private var copied = false
    @State private var showShareSheet = false
    @State private var showImagePicker = false
    @State private var selectedImage: UIImage?
    @State private var showRemovePictureAlert = false
    @State private var isUpdatingPicture = false
    @State private var editedGroupName = ""
    @State private var isEditingName = false

    // __MEMBER_COULD_ONLY_BE_REPORTED_AS_A_ROOM_2026_11_06__ per-member
    // moderation. See `membersSection` for why this row stopped being inert.
    @ObservedObject private var blockedManager = BlockedContactsManager.shared
    @State private var memberReportTarget: GroupMember?

    // Live group from manager (always up-to-date)
    private var group: MessageGroup {
        groupManager.groups.first(where: { $0.id == groupId }) ?? MessageGroup(
            id: groupId, name: "?", type: .adminOnly, adminPublicKey: "",
            members: [], createdAt: Date(), lastActivity: Date()
        )
    }

    // Check if current user is admin
    private var isAdmin: Bool {
        let myPublicKey = identityManager.publicKey
        return group.adminPublicKey == myPublicKey ||
               group.members.first(where: { $0.publicKey == myPublicKey })?.isAdmin == true
    }

    // Permission-aware group with current user set
    private var permGroup: MessageGroup {
        var g = group
        g.setCurrentUser(identityManager.publicKey)
        return g
    }

    // Get group picture image.
    // 🔧 PERF: same per-body-pass JPEG re-inflate as the row and the toolbar,
    // just on a sheet rather than a scrolling list. See `GroupPictureCache`.
    private var groupPictureImage: UIImage? {
        GroupPictureCache.image(for: group.id, data: group.groupPictureData)
    }
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 24) {
                    // 🖼️ Group Picture Header
                    groupPictureSection
                    
                    // Group Info
                    VStack(spacing: 8) {
                        if permGroup.canChangeName {
                            if isEditingName {
                                HStack(spacing: 8) {
                                    TextField("group_name".localized, text: $editedGroupName)
                                        .font(.title2)
                                        .fontWeight(.bold)
                                        .multilineTextAlignment(.center)
                                        .textFieldStyle(.roundedBorder)

                                    Button {
                                        let trimmed = editedGroupName.trimmingCharacters(in: .whitespacesAndNewlines)
                                        if !trimmed.isEmpty {
                                            let _ = groupManager.renameGroup(
                                                groupId: groupId,
                                                newName: trimmed,
                                                renamedBy: identityManager.publicKey
                                            )
                                        }
                                        isEditingName = false
                                    } label: {
                                        Image(systemName: "checkmark.circle.fill")
                                            .foregroundColor(.green)
                                            .font(.title3)
                                    }

                                    Button {
                                        isEditingName = false
                                    } label: {
                                        Image(systemName: "xmark.circle.fill")
                                            .foregroundColor(.secondary)
                                            .font(.title3)
                                    }
                                }
                                .padding(.horizontal)
                            } else {
                                Button {
                                    editedGroupName = group.name
                                    isEditingName = true
                                } label: {
                                    HStack(spacing: 4) {
                                        Text(group.name)
                                            .font(.title2)
                                            .fontWeight(.bold)
                                            .foregroundColor(.primary)
                                        Image(systemName: "pencil")
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                    }
                                }
                            }
                        } else {
                            Text(group.name)
                                .font(.title2)
                                .fontWeight(.bold)
                        }

                        HStack(spacing: 8) {
                            Image(systemName: group.groupType.icon)
                                .foregroundColor(group.groupType.color)
                            Text(group.groupType.displayName)
                                .foregroundColor(group.groupType.color)
                            Text("•")
                                .foregroundColor(.secondary)
                            Text(String(format: "group_member_count".localized, group.memberCount))
                                .foregroundColor(.secondary)
                        }
                        .font(.subheadline)
                    }
                    
                    // 🔇 Mute / Unmute Notifications
                    notificationToggleSection
                    
                    // __BOTS_PHASE1_2026_11_24__ the bots of this group / channel,
                    // and "Add a bot" for its admins.
                    GroupBotsCard(groupId: groupId, isAdmin: isAdmin)

                    // Quick Actions for members with permissions (admin for adminOnly, anyone for collaborative/public)
                    if permGroup.canChangeGroupPicture {
                        adminActionsSection
                    }
                    
                    // __GROUP_INFO_SETTINGS_2026_11_20__ §3.2: in an admin-only group the
                    // invite link (QR, share) is for admins only.
                    if isAdmin || group.type != .adminOnly {
                        // QR Code Card
                        qrCodeSection
                    }
                    
                    // Group ID Section
                    groupIdSection
                    
                    // Members Section
                    membersSection
                    
                    if isAdmin || group.type != .adminOnly {
                        // Share Button
                        shareButton
                    }
                }
                .padding()
            }
            .background(Color(.systemGroupedBackground))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("done".localized) {
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .foregroundColor(groupBrandColor)
                }
            }
            .onAppear {
                generateQRCode()
            }
            .sheet(isPresented: $showShareSheet) {
                if let qrImage = qrCodeImage {
                    ShareSheet(items: [
                        String(format: "group_invite_share_text".localized, group.name),
                        group.inviteLink(inviter: identityManager.publicKey),
                        qrImage
                    ])
                }
            }
            .sheet(isPresented: $showImagePicker) {
                OSHIGroupPhotoPicker(selectedImage: $selectedImage)
            }
            .onChange(of: selectedImage) { oldValue, newValue in
                if let image = newValue {
                    updateGroupPicture(image)
                }
            }
            .alert("group.remove_picture.title".localized, isPresented: $showRemovePictureAlert) {
                Button("cancel".localized, role: .cancel) {}
                Button("remove".localized, role: .destructive) {
                    removeGroupPicture()
                }
            } message: {
                Text("group.remove_picture.message".localized)
            }
        }
    }
    
    // MARK: - Group Picture Section
    
    private var groupPictureSection: some View {
        ZStack {
            // Background blur
            if let image = groupPictureImage {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(height: 120)
                    .blur(radius: 20)
                    .opacity(0.3)
                    .clipped()
            }
            
            VStack(spacing: 12) {
                ZStack(alignment: .bottomTrailing) {
                    // Group picture or placeholder — wrapped in a Button so the entire
                    // image area is tappable (1.0.10 v6 UX fix). Tap → image picker.
                    // Long-press on an existing picture → remove confirm.
                    // Only enabled for users with permission (admin for adminOnly,
                    // any member for collaborative/public).
                    Button {
                        if permGroup.canChangeGroupPicture {
                            showImagePicker = true
                        }
                    } label: {
                        // 🔧 Note: bare `Group { ... }` would resolve to the codebase's
                        // `MessageGroup`-related type in this scope, not SwiftUI.Group.
                        // Using if/else directly inside the @ViewBuilder label closure.
                        if let image = groupPictureImage {
                            Image(uiImage: image)
                                .resizable()
                                .aspectRatio(contentMode: .fill)
                                .frame(width: 100, height: 100)
                                .clipShape(Circle())
                                .shadow(color: .black.opacity(0.2), radius: 8, x: 0, y: 4)
                        } else if let avatar = group.avatar, !avatar.isEmpty {
                            Text(avatar)
                                .font(.scaled(50))
                                .frame(width: 100, height: 100)
                                .background(
                                    Circle()
                                        .fill(LinearGradient(
                                            colors: AvatarIdentity.gradient(for: group.id.uuidString).map { $0.opacity(0.2) },
                                            startPoint: .topLeading, endPoint: .bottomTrailing))
                                )
                        } else {
                            // __AVATAR_SAME_COLOUR_EVERYWHERE_2026_11_09__ was the
                            // group-type brand colour; the group list gives this
                            // room its `AvatarIdentity` colour, so does its info page.
                            AvatarFill(key: group.id.uuidString, size: 100)
                                .overlay(
                                    Text(group.name.prefix(2).uppercased())
                                        .font(.scaled(36, weight: .bold, design: .rounded))
                                        .foregroundColor(AvatarIdentity.ink(for: group.id.uuidString))
                                )
                                .shadow(color: AvatarIdentity.gradient(for: group.id.uuidString)[1].opacity(0.3), radius: 8, x: 0, y: 4)
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(!permGroup.canChangeGroupPicture || isUpdatingPicture)
                    .contextMenu {
                        if permGroup.canChangeGroupPicture {
                            Button {
                                showImagePicker = true
                            } label: {
                                Label("group.change_picture".localized, systemImage: "photo")
                            }
                            if groupPictureImage != nil {
                                Button(role: .destructive) {
                                    showRemovePictureAlert = true
                                } label: {
                                    Label("group.remove_picture.title".localized, systemImage: "trash")
                                }
                            }
                        }
                    }

                    // Camera badge — visual affordance that the picture is tappable.
                    // Still tappable on its own (redundant trigger).
                    if permGroup.canChangeGroupPicture {
                        Button {
                            showImagePicker = true
                        } label: {
                            ZStack {
                                Circle()
                                    .fill(groupBrandColor)
                                    .frame(width: 32, height: 32)

                                if isUpdatingPicture {
                                    ProgressView()
                                        .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                        .scaleEffect(0.7)
                                } else {
                                    Image(systemName: "camera.fill")
                                        .font(.scaled(14, weight: .semibold))
                                        .foregroundColor(.white)
                                }
                            }
                        }
                        .disabled(isUpdatingPicture)
                        .offset(x: 4, y: 4)
                    }
                }

                // Hint for users with permission
                if permGroup.canChangeGroupPicture && groupPictureImage == nil {
                    Text("group.tap_to_add_picture".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .padding(.vertical, 16)
        }
    }
    
    // MARK: - Notification Toggle Section
    
    private var notificationToggleSection: some View {
        let isMuted = groupManager.isGroupMuted(group.id)
        
        return Button {
            groupManager.toggleMuteGroup(group.id)
        } label: {
            HStack(spacing: 16) {
                ZStack {
                    Circle()
                        .fill(isMuted ? Color.red.opacity(0.12) : groupBrandColor.opacity(0.12))
                        .frame(width: 44, height: 44)
                    
                    Image(systemName: isMuted ? "bell.slash.fill" : "bell.fill")
                        .font(.scaled(18))
                        .foregroundColor(isMuted ? .red : groupBrandColor)
                }
                
                VStack(alignment: .leading, spacing: 2) {
                    Text(isMuted ? "group.notifications.muted".localized : "group.notifications.active".localized)
                        .font(.body)
                        .fontWeight(.medium)
                        .foregroundColor(.primary)
                    
                    Text(isMuted ? "group.notifications.muted.description".localized : "group.notifications.active.description".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                
                Spacer()
                
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding()
            .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
            .cornerRadius(16)
        }
    }
    
    // MARK: - Admin Actions Section
    
    private var adminActionsSection: some View {
        VStack(spacing: 12) {
            HStack {
                Image(systemName: isAdmin ? "crown.fill" : "photo.on.rectangle.angled")
                    .foregroundColor(isAdmin ? .orange : groupBrandColor)
                Text(isAdmin ? "group.admin_actions".localized : "group.quick_actions".localized)
                    .font(.headline)
                Spacer()
            }
            
            HStack(spacing: 12) {
                // Change Picture Button
                Button {
                    showImagePicker = true
                } label: {
                    VStack(spacing: 8) {
                        Image(systemName: "photo.badge.plus")
                            .font(.title2)
                        Text(groupPictureImage == nil ? "group.add_picture".localized : "group.change_picture".localized)
                            .font(.caption)
                    }
                    .foregroundColor(groupBrandColor)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(groupBrandColor.opacity(0.1))
                    .cornerRadius(12)
                }
                
                // Remove Picture Button (only if picture exists)
                if groupPictureImage != nil {
                    Button {
                        showRemovePictureAlert = true
                    } label: {
                        VStack(spacing: 8) {
                            Image(systemName: "photo.badge.minus")
                                .font(.title2)
                            Text("group.remove_picture".localized)
                                .font(.caption)
                        }
                        .foregroundColor(.red)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(Color.red.opacity(0.1))
                        .cornerRadius(12)
                    }
                }
            }
        }
        .padding()
        .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(16)
    }
    
    // MARK: - QR Code Section
    
    private var qrCodeSection: some View {
        VStack(spacing: 16) {
            Text("group_qr_code".localized)
                .font(.headline)
            
            if let qrImage = qrCodeImage {
                Image(uiImage: qrImage)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 180, height: 180)
                    .padding(12)
                    .background(Color.white)
                    .cornerRadius(16)
                    .shadow(color: .black.opacity(0.1), radius: 8, x: 0, y: 4)
            } else {
                ProgressView()
                    .frame(width: 180, height: 180)
            }
            
            Text("group_qr_scan_hint".localized)
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
        .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(16)
    }
    
    // MARK: - Group ID Section
    
    private var groupIdSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "number")
                    .foregroundColor(groupBrandColor)
                Text("group_id".localized)
                    .font(.headline)
            }
            
            Text(group.id.uuidString)
                .font(.system(.caption, design: .monospaced))
                .foregroundColor(.secondary)
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(.systemBackground))
                .cornerRadius(10)
            
            Button {
                UIPasteboard.general.string = group.id.uuidString
                copied = true
                let generator = UINotificationFeedbackGenerator()
                generator.notificationOccurred(.success)
                
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                    copied = false
                }
            } label: {
                HStack {
                    Image(systemName: copied ? "checkmark.circle.fill" : "doc.on.doc")
                    Text(copied ? "copied".localized : "copy_id".localized)
                }
                .font(.subheadline)
                .fontWeight(.medium)
                .foregroundColor(copied ? .green : groupBrandColor)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(
                    (copied ? Color.green : groupBrandColor).opacity(0.1)
                )
                .cornerRadius(10)
            }
        }
        .padding()
        .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(16)
    }
    
    // MARK: - Members Section
    
    private var membersSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "person.2.fill")
                    .foregroundColor(groupBrandColor)
                Text("group_members".localized)
                    .font(.headline)
                
                Spacer()
                
                Text("\(group.memberCount)")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            
            ForEach(group.members, id: \.publicKey) { member in
                // __MEMBER_COULD_ONLY_BE_REPORTED_AS_A_ROOM_2026_11_06__
                //
                // This row was an inert `HStack`. Not "a menu with the wrong
                // entries" like Android's — no gesture at all. On iPhone a
                // member of a group could not be blocked, reported, replied to
                // privately or removed from anywhere in the app; the only
                // moderation reachable from a group was reporting the ROOM.
                //
                // That is what the 2026-11-06 abuse reports look like from the
                // inside: four reports in 33 minutes, every `subjectType` a
                // group, and the spammer's name — "Danielle Bespoke", "Sarah",
                // "Detee now" — typed into the free-text box because there was
                // no field for a person. Three were the same group id, 14 and
                // 25 seconds apart.
                //
                // A `Menu` rather than a `.contextMenu`: a long press nobody is
                // told about is the same as no action. Blocking itself is not
                // new — group receive has dropped blocked members' messages
                // since __BLOCKED_MEMBER_DROP_2026_09_24__ — it was only
                // unreachable, which for the user is indistinguishable from
                // absent.
                Menu {
                    if blockedManager.isBlocked(member.publicKey) {
                        Button {
                            blockedManager.unblockContact(publicKey: member.publicKey)
                        } label: {
                            Label("group.unblock_member".localized, systemImage: "lock.open")
                        }
                    } else {
                        Button(role: .destructive) {
                            blockedManager.blockContact(
                                publicKey: member.publicKey,
                                displayName: member.alias,
                                reason: nil
                            )
                        } label: {
                            Label("group.block_member".localized, systemImage: "hand.raised.fill")
                        }
                    }
                    Button(role: .destructive) {
                        memberReportTarget = member
                    } label: {
                        Label("group.report_member".localized, systemImage: "flag.fill")
                    }
                    Divider()
                    Button {
                        SecureClipboard.copy(member.publicKey)
                    } label: {
                        Label("message.action.copy".localized, systemImage: "doc.on.doc")
                    }
                } label: {
                    memberRow(member)
                }
                .buttonStyle(PlainButtonStyle())
            }

            // All members are shown above
        }
        .padding()
        .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
        .cornerRadius(16)
        // One MEMBER, filed as a contact, so moderation receives a key it can
        // act on rather than a room id. Reuses `ReportSheet` — the same sheet
        // the 1:1 chat and the group report use.
        .sheet(item: $memberReportTarget) { member in
            ReportSheet(
                subject: .contact,
                subjectId: member.publicKey,
                subjectName: member.alias,
                onReported: { alsoBlock in
                    if alsoBlock {
                        blockedManager.blockContact(
                            publicKey: member.publicKey,
                            displayName: member.alias,
                            reason: nil
                        )
                    }
                }
            )
        }
    }

    private func memberRow(_ member: GroupMember) -> some View {
        HStack(spacing: 12) {
                    Circle()
                        .fill(LinearGradient(
                            colors: [groupBrandColor.opacity(0.6), groupBrandColor.opacity(0.3)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ))
                        .frame(width: 40, height: 40)
                        .overlay(
                            Text(String(member.publicKey.prefix(2)).uppercased())
                                .font(.caption)
                                .fontWeight(.semibold)
                                .foregroundColor(.white)
                        )
                    
                    VStack(alignment: .leading, spacing: 2) {
                        Text(member.alias ?? formatAddress(member.publicKey))
                            .font(.subheadline)
                            .fontWeight(.medium)
                        
                        if member.isAdmin {
                            HStack(spacing: 4) {
                                Image(systemName: "crown.fill")
                                    .font(.caption2)
                                Text("group_admin".localized)
                                    .font(.caption2)
                            }
                            .foregroundColor(.orange)
                        }
                    }
                    
                    Spacer()

                    // The row is now a control, so it says so.
                    Image(systemName: "ellipsis.circle")
                        .foregroundColor(.secondary)
                        .accessibilityHidden(true)
                }
                .padding(.vertical, 4)
                .contentShape(Rectangle())
    }

    // MARK: - Share Button
    
    private var shareButton: some View {
        Button {
            showShareSheet = true
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "square.and.arrow.up")
                Text("share_group".localized)
            }
            .font(.headline)
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(
                LinearGradient(
                    colors: [groupBrandColor, groupBrandColor.opacity(0.8)],
                    startPoint: .leading,
                    endPoint: .trailing
                )
            )
            .cornerRadius(14)
            .shadow(color: groupBrandColor.opacity(0.3), radius: 8, x: 0, y: 4)
        }
    }
    
    // MARK: - Helper Functions
    
    private func formatAddress(_ address: String) -> String {
        guard address.count > 12 else { return address }
        let start = address.prefix(6)
        let end = address.suffix(4)
        return "\(start)...\(end)"
    }
    
    private func updateGroupPicture(_ image: UIImage) {
        guard let imageData = image.jpegData(compressionQuality: 0.8) else { return }
        
        isUpdatingPicture = true
        let myPublicKey = identityManager.publicKey
        
        Task { @MainActor in
            let success = groupManager.updateGroupPicture(
                groupId: group.id,
                imageData: imageData,
                updatedBy: myPublicKey
            )
            
            isUpdatingPicture = false
            if success {
                let generator = UINotificationFeedbackGenerator()
                generator.notificationOccurred(.success)
            }
            selectedImage = nil
        }
    }
    
    private func removeGroupPicture() {
        let myPublicKey = identityManager.publicKey
        
        Task { @MainActor in
            let _ = groupManager.removeGroupPicture(
                groupId: group.id,
                removedBy: myPublicKey
            )
            
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.success)
        }
    }
    
    private func generateQRCode() {
        // 🆕 Use the new invite-link format with group name + inviter so the
        // recipient can join even if they don't have the group locally yet.
        let inviteLink = group.inviteLink(inviter: identityManager.publicKey)
        Task.detached {
            let context = CIContext()
            let filter = CIFilter.qrCodeGenerator()
            filter.message = Data(inviteLink.utf8)
            filter.correctionLevel = "M"
            
            if let outputImage = filter.outputImage {
                let transform = CGAffineTransform(scaleX: 10, y: 10)
                let scaledImage = outputImage.transformed(by: transform)
                
                if let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) {
                    let uiImage = UIImage(cgImage: cgImage)
                    await MainActor.run {
                        self.qrCodeImage = uiImage
                    }
                }
            }
        }
    }
}

// MARK: - Group Image Picker

struct OSHIGroupPhotoPicker: UIViewControllerRepresentable {
    @Binding var selectedImage: UIImage?
    @Environment(\.dismiss) var dismiss
    
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.delegate = context.coordinator
        picker.sourceType = .photoLibrary
        picker.allowsEditing = true
        return picker
    }
    
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: OSHIGroupPhotoPicker
        
        init(_ parent: OSHIGroupPhotoPicker) {
            self.parent = parent
        }
        
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let editedImage = info[.editedImage] as? UIImage {
                parent.selectedImage = editedImage
            } else if let originalImage = info[.originalImage] as? UIImage {
                parent.selectedImage = originalImage
            }
            parent.dismiss()
        }
        
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}

// MARK: - Discover Public Groups

// Observable class to manage discovered public groups
class DiscoveredGroupsManager: ObservableObject {
    static let shared = DiscoveredGroupsManager()
    
    @Published var discoveredGroups: [PublicGroupAd] = []
    
    private init() {
        setupNotificationObserver()
        loadDiscoveredGroups()
    }
    
    private func setupNotificationObserver() {
        NotificationCenter.default.addObserver(
            forName: NSNotification.Name("PublicGroupDiscovered"),
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let groupAd = notification.userInfo?["groupAd"] as? PublicGroupAd else { return }
            self?.addDiscoveredGroup(groupAd)
        }
        
        OshiLog.messaging.info("📡 DiscoveredGroupsManager listening for public groups")
    }
    
    func addDiscoveredGroup(_ ad: PublicGroupAd) {
        // Don't add duplicates
        if !discoveredGroups.contains(where: { $0.groupId == ad.groupId }) {
            discoveredGroups.append(ad)
            saveDiscoveredGroups()
            OshiLog.messaging.info("✅ Discovered new public group: \(ad.groupName)")

            // Post in-app notification for nearby group discovery
            NotificationCenter.default.post(
                name: NSNotification.Name("NearbyGroupDiscovered"),
                object: nil,
                userInfo: ["groupName": ad.groupName, "memberCount": ad.memberCount, "groupId": ad.groupId.uuidString]
            )
        } else {
            // Update existing if newer
            if let index = discoveredGroups.firstIndex(where: { $0.groupId == ad.groupId }) {
                if ad.timestamp > discoveredGroups[index].timestamp {
                    discoveredGroups[index] = ad
                    saveDiscoveredGroups()
                    OshiLog.messaging.info("🔄 Updated public group: \(ad.groupName)")
                }
            }
        }
    }
    
    func removeDiscoveredGroup(_ groupId: UUID) {
        discoveredGroups.removeAll { $0.groupId == groupId }
        saveDiscoveredGroups()
    }
    
    private func saveDiscoveredGroups() {
        FileStorage.shared.save(discoveredGroups, forKey: "discoveredPublicGroups")
    }
    
    private func loadDiscoveredGroups() {
        if let groups: [PublicGroupAd] = FileStorage.shared.load(forKey: "discoveredPublicGroups") {
            discoveredGroups = groups
            OshiLog.messaging.info("📂 Loaded \(groups.count) discovered public groups")
        }
    }
    
    func clearOldGroups() {
        let cutoff = Date().addingTimeInterval(-24 * 60 * 60) // 24 hours ago
        discoveredGroups.removeAll { $0.timestamp < cutoff }
        saveDiscoveredGroups()
    }
}

struct DiscoverPublicGroupsView: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager
    @StateObject private var discoveryManager = DiscoveredGroupsManager.shared
    @State private var joiningGroupId: UUID?
    @State private var showJoinAlert = false
    @State private var joinAlertMessage = ""
    
    // Auto-refresh timer (2 seconds)
    let refreshTimer = Timer.publish(every: 2, on: .main, in: .common).autoconnect()
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Info banner
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 8) {
                        Image(systemName: "info.circle.fill")
                            .foregroundColor(groupBrandColor)
                        Text("discover_how_it_works".localized)
                            .font(.headline)
                    }
                    
                    Text("discover_instructions".localized)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                    
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 8) {
                            Text("step_one".localized)
                                .fontWeight(.medium)
                            Text("discover_step_1".localized)
                                .font(.caption)
                        }
                        
                        HStack(spacing: 8) {
                            Text("step_two".localized)
                                .fontWeight(.medium)
                            Text("discover_step_2".localized)
                                .font(.caption)
                        }
                        
                        HStack(spacing: 8) {
                            Text("step_three".localized)
                                .fontWeight(.medium)
                            Text("discover_step_3".localized)
                                .font(.caption)
                        }
                    }
                    .foregroundColor(.secondary)
                }
                .padding()
                .background(Color(.systemGray6))
                
                // Groups list
                if discoveryManager.discoveredGroups.isEmpty {
                    VStack(spacing: 16) {
                        Image(systemName: "antenna.radiowaves.left.and.right.slash")
                            .font(.scaled(60))
                            .foregroundColor(.gray)
                            .accessibilityHidden(true)

                        Text("discover_no_groups_found".localized)
                            .font(.headline)
                        
                        Text("discover_no_groups_hint".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                        
                        // Scanning indicator (auto-refresh every 2 seconds)
                        HStack(spacing: 8) {
                            ProgressView()
                                .scaleEffect(0.8)
                            Text("discovery_scanning".localized)
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                        .padding(.top, 8)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding()
                } else {
                    List {
                        ForEach(discoveryManager.discoveredGroups, id: \.groupId) { ad in
                            DiscoveredGroupRow(
                                ad: ad,
                                isJoining: joiningGroupId == ad.groupId,
                                alreadyJoined: groupManager.groups.contains { $0.id == ad.groupId && $0.members.contains(where: { $0.publicKey == identityManager.publicKey }) }
                            ) {
                                joinPublicGroup(ad)
                            }
                        }
                    }
                    .refreshable {
                        refreshDiscovery()
                    }
                }
            }
            .frame(maxWidth: 700)
            .frame(maxWidth: .infinity)
            .navigationTitle("nav_title_public_groups".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    // Scanning indicator instead of refresh button
                    HStack(spacing: 4) {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .foregroundColor(.green)
                            .symbolEffect(.variableColor.iterative, options: .repeating)
                    }
                    .accessibilityLabel("discovery_scanning".localized)
                }
                
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("button_done".localized) {
                        dismiss()
                    }
                }
            }
            .onReceive(refreshTimer) { _ in
                refreshDiscovery()
            }
            .onAppear {
                // Initial refresh when view appears
                refreshDiscovery()
            }
            .alert("alert_join_group".localized, isPresented: $showJoinAlert) {
                Button("button_ok".localized, role: .cancel) { }
            } message: {
                Text(joinAlertMessage)
            }
        }
    }
    
    private func refreshDiscovery() {
        // Clear old groups and trigger a broadcast request
        discoveryManager.clearOldGroups()
        
        // Request nearby devices to broadcast their public groups
        NotificationCenter.default.post(
            name: NSNotification.Name("RequestPublicGroupBroadcast"),
            object: nil
        )
        
        OshiLog.messaging.info("🔄 Refreshing public group discovery...")
    }
    
    private func joinPublicGroup(_ ad: PublicGroupAd) {
        let myKey = identityManager.publicKey

        // Check if ACTUALLY a member (not just group UUID exists)
        if let existingGroup = groupManager.groups.first(where: { $0.id == ad.groupId }),
           existingGroup.members.contains(where: { $0.publicKey == myKey }) {
            joinAlertMessage = "alert_already_joined".localized
            showJoinAlert = true
            return
        }

        joiningGroupId = ad.groupId

        // Remove zombie group if present (left but not cleaned up)
        if let existingIndex = groupManager.groups.firstIndex(where: { $0.id == ad.groupId }) {
            groupManager.groups.remove(at: existingIndex)
            OshiLog.messaging.info("🧟 Removed zombie group before rejoin")
        }

        // Create local placeholder group with admin + self
        // This is a TEMPORARY copy — admin will broadcast the full member list back
        var newGroup = MessageGroup(
            id: ad.groupId,
            name: ad.groupName,
            type: .publicGroup,
            adminPublicKey: ad.adminPublicKey,
            members: [
                GroupMember(publicKey: ad.adminPublicKey, alias: nil, joinedAt: ad.timestamp, isAdmin: true),
                GroupMember(publicKey: myKey, alias: nil, joinedAt: Date(), isAdmin: false)
            ],
            createdAt: ad.timestamp,
            lastActivity: Date()
        )
        newGroup.setCurrentUser(myKey)

        groupManager.groups.append(newGroup)
        groupManager.groupMessages[newGroup.id] = groupManager.groupMessages[newGroup.id] ?? []
        groupManager.saveGroups()

        // Add system message locally
        groupManager.addSystemMessage(to: ad.groupId, type: .memberJoined, byPublicKey: myKey)

        // ✅ FIX: Send LIGHTWEIGHT "member_added" notification — NOT a full group broadcast.
        // A full broadcast would overwrite admin's complete member list with our 2-member copy.
        // Admin receives this, calls addMember on their side, and broadcasts the FULL correct
        // member list back to everyone (including us — replacing our placeholder).
        groupManager.broadcastMemberJoinedNotification(groupId: ad.groupId, memberPublicKey: myKey)

        // ✅ Also request member sync immediately to get the full member list
        groupManager.requestMemberSync(groupId: ad.groupId)

        // Notify server to add our key to any bots for this group
        Task { @MainActor in
            BotManager.shared.notifyServerMemberJoined(groupId: ad.groupId, memberPublicKey: myKey)
        }

        // ✅ Send push to admin to wake their device for member sync
        //
        // 🔒 __PUSH_LISTS_DRIFTED_2026_09_02__ Titre GÉNÉRIQUE. `ad.groupName`
        // partait ici en clair chez APNs et sur l'écran verrouillé de l'admin,
        // contre la note du 2026-07-12 que le chemin legacy applique déjà
        // (`GroupMessaging.broadcastGroupMessageViaRelay`, `title: "OSHI"`).
        // `groupId` suffit à router; l'app résout le nom localement.
        //
        // Reste une ALERTE, contrairement à son voisin `requestMemberSync` qui
        // est passé en `silent:`: là-bas la poussée n'annonçait rien du tout
        // (la sync arrive par le sondage), ici « quelqu'un a rejoint » est un
        // évènement réel pour l'admin. Le réveil pour la sync est un bonus, pas
        // la raison d'être.
        Task {
            do {
                try await VoIPPushManager.requestPushNotification(
                    to: ad.adminPublicKey,
                    type: "group_message",
                    title: "OSHI",
                    body: "New member joined",
                    additionalData: [
                        "groupId": ad.groupId.uuidString,
                        "senderPublicKey": myKey
                    ]
                )
                OshiLog.messaging.info("📲 Sent wake push to admin for member sync")
            } catch {
                OshiLog.messaging.info("⚠️ Failed to push admin: \(error.localizedDescription)")
            }
        }

        // Remove from discovered list
        discoveryManager.removeDiscoveredGroup(ad.groupId)

        joiningGroupId = nil

        joinAlertMessage = String(format: "alert_joined_group".localized, ad.groupName)
        showJoinAlert = true

        OshiLog.messaging.info("✅ Joined public group: \(ad.groupName)")
    }
}

// MARK: - Discovered Group Row

struct DiscoveredGroupRow: View {
    let ad: PublicGroupAd
    let isJoining: Bool
    let alreadyJoined: Bool
    let onJoin: () -> Void
    
    var body: some View {
        HStack(spacing: 15) {
            // Avatar
            ZStack(alignment: .bottomTrailing) {
                Text(ad.avatar ?? "👥")
                    .font(.scaled(44))

                Image(systemName: "globe")
                    .font(.scaled(12))
                    .foregroundColor(.white)
                    .padding(4)
                    .background(Color.green)
                    .clipShape(Circle())
            }
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                Text(ad.groupName)
                    .font(.headline)

                HStack(spacing: 6) {
                    Image(systemName: "globe")
                        .font(.caption)
                        .foregroundColor(.green)
                        .accessibilityHidden(true)

                    Text("group_type_public".localized)
                        .font(.caption)
                        .foregroundColor(.green)
                    
                    Text("•")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    
                    Text(String(format: "group_member_count".localized, ad.memberCount))
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                
                // Timestamp
                Text(ad.timestamp, style: .relative)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            
            Spacer()
            
            // Join button
            if alreadyJoined {
                Text("label_joined".localized)
                    .font(.caption)
                    .foregroundColor(.green)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.green.opacity(0.1))
                    .cornerRadius(15)
            } else if isJoining {
                ProgressView()
                    .scaleEffect(0.8)
            } else {
                Button {
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    onJoin()
                } label: {
                    Text("button_join".localized)
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundColor(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .frame(minHeight: 44)
                        .background(Color.green)
                        .cornerRadius(15)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("button_join".localized)
                .accessibilityHint("a11y.channel.join_hint".localized)
            }
        }
        .padding(.vertical, 8)
    }
}

// MARK: - Create Group View

struct CreateGroupView: View {
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager

    var preselectedType: GroupType? = nil

    @State private var groupName = ""
    @State private var selectedGroupType: GroupType = .collaborative
    @State private var selectedAvatar = "👥"
    @State private var newMemberKey = ""
    @State private var addedMembers: [String] = []
    @State private var showingScanner = false  // ✅ For QR scanner
    @State private var showingContactPicker = false
    @State private var showingAlert = false
    @State private var alertMessage = ""
    @State private var alertTitle = ""
    
    let avatars = ["👥", "💬", "🚀", "🎯", "💡", "🌟", "🔥", "⚡", "🎨", "🎮", "🌍", "🔒"]
    
    // ✅ FIX: Better validation logic
    var canCreate: Bool {
        // Group name is required
        guard !groupName.isEmpty else { return false }
        
        // Public groups don't need members
        if selectedGroupType == .publicGroup {
            return true
        }
        
        // Non-public groups need at least one member (besides creator)
        return !addedMembers.isEmpty
    }
    
    var body: some View {
        NavigationView {
            Form {
                Section("section_group_name".localized) {
                    TextField("placeholder_group_name".localized, text: $groupName)
                }
                
                Section {
                    ForEach(GroupType.allCases, id: \.self) { type in
                        Button {
                            UISelectionFeedbackGenerator().selectionChanged()
                            withAnimation(.easeInOut(duration: 0.15)) { selectedGroupType = type }
                        } label: {
                            HStack {
                                Image(systemName: type.icon)
                                    .foregroundColor(type.color)
                                    .frame(width: 30)
                                    .accessibilityHidden(true)

                                VStack(alignment: .leading, spacing: 4) {
                                    Text(type.displayName)
                                        .foregroundColor(.primary)
                                        .fontWeight(selectedGroupType == type ? .semibold : .regular)

                                    Text(type.description)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }

                                Spacer()

                                if selectedGroupType == type {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundColor(type.color)
                                        .accessibilityHidden(true)
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)  // ✅ FIX: Prevent button from intercepting taps
                        .accessibilityElement(children: .combine)
                        .accessibilityAddTraits(selectedGroupType == type ? [.isSelected, .isButton] : .isButton)
                    }
                } header: {
                    Text("section_group_type".localized)
                }
                
                Section("section_avatar".localized) {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 6), spacing: 12) {
                        ForEach(avatars, id: \.self) { avatar in
                            Button {
                                UISelectionFeedbackGenerator().selectionChanged()
                                selectedAvatar = avatar
                                OshiLog.messaging.info("✅ Selected avatar: \(avatar)")
                            } label: {
                                Text(avatar)
                                    .font(.title)
                                    .frame(width: 44, height: 44)  // ✅ FIX: Explicit frame for better tap area
                                    .background(selectedAvatar == avatar ? selectedGroupType.color.opacity(0.2) : Color.clear)
                                    .cornerRadius(8)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)  // ✅ FIX: Prevent Form from intercepting taps
                            .accessibilityLabel(avatar)
                            .accessibilityAddTraits(selectedAvatar == avatar ? [.isSelected, .isButton] : .isButton)
                        }
                    }
                    .padding(.vertical, 8)
                }
                
                // Only show member addition for non-public groups
                if selectedGroupType != .publicGroup {
                    Section(selectedGroupType == .adminOnly ? "section_add_members_admin".localized : "section_add_members".localized) {
                        // Pick from existing contacts
                        Button {
                            showingContactPicker = true
                        } label: {
                            HStack {
                                Image(systemName: "person.crop.circle.badge.plus")
                                    .font(.title3)
                                    .foregroundColor(groupBrandColor)
                                Text("group.pick_from_contacts".localized)
                                    .foregroundColor(groupBrandColor)
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }

                        HStack {
                            TextField("placeholder_member_key".localized, text: $newMemberKey)
                                .font(.system(.body, design: .monospaced))
                                .autocapitalization(.none)

                            // ✅ QR Scanner button
                            Button {
                                showingScanner = true
                            } label: {
                                Image(systemName: "qrcode.viewfinder")
                                    .font(.title3)
                                    .foregroundColor(groupBrandColor)
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("a11y.group.scan_qr".localized)

                            Button {
                                addMember()
                            } label: {
                                Image(systemName: "plus.circle.fill")
                                    .foregroundColor(selectedGroupType.color)
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("a11y.group.add_member".localized)
                            .disabled(newMemberKey.isEmpty)
                        }
                    }
                    
                    if !addedMembers.isEmpty {
                        Section("section_added_members".localized + " (\(addedMembers.count))") {
                            ForEach(addedMembers, id: \.self) { publicKey in
                                HStack {
                                    Image(systemName: "person.circle.fill")
                                        .foregroundColor(selectedGroupType.color)
                                    
                                    Text(publicKey.prefix(16) + "...")
                                        .font(.system(.body, design: .monospaced))
                                    
                                    Spacer()
                                    
                                    Button {
                                        removeMember(publicKey)
                                    } label: {
                                        Image(systemName: "minus.circle.fill")
                                            .foregroundColor(.red)
                                    }
                                    .buttonStyle(.borderless)
                                    .accessibilityLabel("a11y.group.remove_member".localized)
                                }
                            }
                        }
                    }
                } else {
                    Section {
                        HStack {
                            Image(systemName: "info.circle")
                                .foregroundColor(.green)
                            Text("info_public_group".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }
            }
            .frame(maxWidth: 700)
            .frame(maxWidth: .infinity)
            .navigationTitle("nav_title_new_group".localized)
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                if let preset = preselectedType {
                    selectedGroupType = preset
                }
            }
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("button_cancel".localized) {
                        dismiss()
                    }
                }

                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("button_create".localized) {
                        UINotificationFeedbackGenerator().notificationOccurred(.success)
                        createGroup()
                    }
                    .disabled(!canCreate)  // ✅ FIX: Use computed property
                    .foregroundColor(canCreate ? groupBrandColor : .gray)  // ✅ Visual feedback
                }
            }
            .sheet(isPresented: $showingScanner) {
                QRScannerView(scannedAddress: $newMemberKey)
                    .onDisappear {
                        showingScanner = false
                    }
            }
            .sheet(isPresented: $showingContactPicker) {
                ContactPickerForGroup(
                    addedMembers: $addedMembers,
                    myPublicKey: identityManager.publicKey
                )
            }
            .alert(alertTitle, isPresented: $showingAlert) {
                Button("button_ok".localized, role: .cancel) { }
            } message: {
                Text(alertMessage)
            }
            .onChange(of: newMemberKey) { oldValue, newValue in
                // ✅ Auto-add when QR code is scanned
                if !newValue.isEmpty && newValue != oldValue && newValue.count > 20 {
                    // Likely a scanned QR code (long key)
                    // Small delay to allow scanner to dismiss
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                        addMemberAutomatically()
                    }
                }
            }
        }
    }
    
    private func addMemberAutomatically() {
        let trimmed = newMemberKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        
        // ✅ Check if already added
        if addedMembers.contains(trimmed) {
            alertTitle = "alert_already_added".localized
            alertMessage = "alert_member_in_list".localized
            showingAlert = true
            newMemberKey = ""
            OshiLog.messaging.info("ℹ️ Member already in add list: \(trimmed.prefix(10))...")
            return
        }
        
        addedMembers.append(trimmed)
        newMemberKey = ""
        OshiLog.messaging.info("✅ Auto-added member: \(trimmed.prefix(16))...")
        OshiLog.messaging.info("   Total members to add: \(addedMembers.count)")
    }
    
    private func addMember() {
        let trimmed = newMemberKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        
        // ✅ Check if already added
        if addedMembers.contains(trimmed) {
            alertTitle = "alert_already_added".localized
            alertMessage = "alert_member_in_list".localized
            showingAlert = true
            OshiLog.messaging.info("ℹ️ Member already in add list: \(trimmed.prefix(10))...")
            return
        }
        
        addedMembers.append(trimmed)
        newMemberKey = ""
        OshiLog.messaging.info("✅ Added member: \(trimmed.prefix(16))...")
        OshiLog.messaging.info("   Total members to add: \(addedMembers.count)")
    }
    
    private func removeMember(_ publicKey: String) {
        addedMembers.removeAll { $0 == publicKey }
        OshiLog.messaging.info("✅ Removed member: \(publicKey.prefix(16))...")
    }
    
    private func createGroup() {
        OshiLog.messaging.info("🔨 Creating group:")
        OshiLog.messaging.info("   Name: \(groupName)")
        OshiLog.messaging.info("   Type: \(selectedGroupType.rawValue)")
        OshiLog.messaging.info("   Avatar: \(selectedAvatar)")
        OshiLog.messaging.info("   Members: \(addedMembers.count)")

        let newGroup = groupManager.createGroup(
            name: groupName,
            type: selectedGroupType,
            adminPublicKey: identityManager.publicKey,
            avatar: selectedAvatar == "👥" ? nil : selectedAvatar
        )

        // Add additional members
        for memberKey in addedMembers {
            groupManager.addMember(to: newGroup.id, publicKey: memberKey, alias: nil)
        }

        // Broadcast the complete group (with all members) to everyone
        if let createdGroup = groupManager.groups.first(where: { $0.id == newGroup.id }) {
            groupManager.broadcastFullGroupUpdate(group: createdGroup)
        }

        OshiLog.messaging.info("✅ Group created successfully!")
        dismiss()
    }
}

// MARK: - Contact Picker for Group Creation

struct ContactPickerForGroup: View {
    @Environment(\.dismiss) var dismiss
    @Binding var addedMembers: [String]
    let myPublicKey: String

    @AppStorage("contactAliases") private var contactAliasesData: Data = Data()
    @AppStorage(SharedNickname.defaultsKey) private var sharedNicknamesData: Data = Data()
    @State private var selectedKeys: Set<String> = []

    private var contactAliases: [String: String] {
        guard !contactAliasesData.isEmpty else { return [:] }
        return (try? JSONDecoder().decode([String: String].self, from: contactAliasesData)) ?? [:]
    }

    private var conversations: [Conversation] {
        (MessageManager.shared?.conversations ?? []).filter {
            $0.participantPublicKey != myPublicKey && !addedMembers.contains($0.participantPublicKey)
        }
    }

    var body: some View {
        NavigationView {
            List {
                if conversations.isEmpty {
                    Text("group.no_contacts".localized)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding()
                } else {
                    ForEach(conversations) { conversation in
                        let pk = conversation.participantPublicKey
                        let isSelected = selectedKeys.contains(pk)
                        // __SHARED_NICKNAME_2026_09_22__ alias local > pseudo partage.
                        let alias = SharedNickname.resolve(
                            alias: contactAliases[pk],
                            nickname: SharedNickname.table(sharedNicknamesData).nickname(for: pk))

                        Button {
                            if isSelected {
                                selectedKeys.remove(pk)
                            } else {
                                selectedKeys.insert(pk)
                            }
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "person.circle.fill")
                                    .font(.title2)
                                    .foregroundColor(.blue)

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(alias ?? String(pk.prefix(16)) + "...")
                                        .font(.body)
                                        .foregroundColor(.primary)
                                    if alias != nil {
                                        Text(String(pk.prefix(12)) + "...")
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                    }
                                }

                                Spacer()

                                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                                    .foregroundColor(isSelected ? .blue : .gray)
                                    .font(.title3)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .navigationTitle("group.select_contacts".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("common.cancel".localized) { dismiss() }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(String(format: "group.add_count".localized, selectedKeys.count)) {
                        addedMembers.append(contentsOf: selectedKeys)
                        dismiss()
                    }
                    .disabled(selectedKeys.isEmpty)
                }
            }
        }
    }
}

// MARK: - Identifiable wrapper for fullscreen image data

struct IdentifiableImageData: Identifiable {
    let id = UUID()
    let data: Data
    /// 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ nil = sender unknown (treated as others').
    var isMine: Bool? = nil
}

// MARK: - Group Chat View

struct GroupChatView: View {
    /// __GROUP_RESEND_2026_11_25__ own messages some members have not received yet.
    @ObservedObject private var pendingDeliveries = GroupPendingDeliveryCenter.shared
    @State private var partialDeliveryShown: GroupPendingDeliveryStore.Entry?
    let group: MessageGroup
    // __FOLDABLE_LAYOUT_2026_11_16__ see AdaptiveThreadLayout.swift / ChatView.
    @State private var groupColumnWidth: CGFloat = 0
    private var chatColumnWidth: CGFloat { groupColumnWidth }
    @State private var handoffToken = UUID()
    @State private var handoffAttached = false
    @FocusState private var groupComposerFocused: Bool
    @State private var handoffReadingAnchor: UUID?
    /// A hand-off landing owns the scroll; the open / growth landings stand down.
    @State private var groupHandoffActive = false
    private var handoffKey: String { ThreadRoute.group(group.id).handoffKey }

    /// See `ChatView.restoreReadingAnchor`: lands on `id` at the top, re-aims
    /// while bubbles decrypt and grow, stops as soon as the reader drags.
    private func restoreGroupReadingAnchor(_ id: UUID, proxy: ScrollViewProxy) {
        let start = Date()
        for delay in [0.05, 0.25, 0.6, 1.2] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                if let drag = scrollTracker.lastUserDragAt, drag > start { return }
                withAnimation(nil) { proxy.scrollTo(id, anchor: .top) }
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { groupHandoffActive = false }
    }

    /// See `ChatView.attachHandoffIfNeeded`. Idempotent; called from whichever
    /// `onAppear` (the view's, the list's) runs first.
    private func attachGroupHandoffIfNeeded() {
        guard !handoffAttached else { return }
        handoffAttached = true
        guard let carried = ThreadHandoff.shared.attach(handoffKey, token: handoffToken,
                                                         currentText: messageText) else { return }
        if messageText.isEmpty, !carried.text.isEmpty { messageText = carried.text }
        if !carried.atBottom, let anchor = carried.readingAnchor { handoffReadingAnchor = UUID(uuidString: anchor) }
        if carried.composerFocused { ThreadHandoff.requestComposerFocus(handoffKey, token: handoffToken) }
    }
    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager
    @Environment(\.dismiss) private var dismissGroupChat
    @ObservedObject private var typingManager = TypingIndicatorManager.shared
    /// __BOT_INLINE_KEYBOARD_2026_09_16__ Holds the keyboards that arrived with
    /// bot messages, the in-flight state of the button being tapped, and the
    /// toast/alert/web sheet a tap produces.
    @ObservedObject private var botManager = BotManager.shared

    @State private var messageText = ""
    /// __BOT_COMMANDS_2026_11_24__ Row of the `/` menu highlighted by the
    /// hardware keyboard (↑/↓); `nil` until an arrow is pressed.
    @State private var slashHighlight: Int?
    /// The bot whose command editor is open from the `/` menu ("Add commands").
    @State private var slashEditorBot: OSHIBot?
    /// __CASHU_2026_10_25__ A token held for one confirmation before it reaches
    /// the whole group.
    @State private var cashuAboutToGoPublic: String?
    /// Set when a send is refused (rate limit, or too long even split up).
    /// The group composer had no error surface at all — a refusal here would
    /// otherwise be a silent drop, which is the one outcome that is not allowed.
    @State private var sendErrorMessage: String? = nil
    /// __BOT_NO_BOT_HINT_2026_08_17__ Affiché une seule fois par groupe, après un
    /// message qui ressemble à une commande alors qu'aucun bot n'écoute ici.
    @State private var showNoBotHint = false
    @State private var showBotManagement = false
    @State private var showGroupInfo = false
    @State private var showAddMember = false
    @State private var showMediaPicker = false
    @State private var showAttachPanel = false  // __ATTACH_PANEL_2027_2026_11_17__
    @State private var selectedMedia: [MediaManager.MediaAttachment] = []
    @State private var loadedMessages: [GroupMessage] = []
    // ✅ OPEN-LAG FIX: cache bot-merge so displayMessages doesn't recompute on every body pass.
    @State private var displayMessagesCache: [GroupMessage] = []
    // 🔧 2026-06-09: track whether THIS view is currently on-screen.
    // SwiftUI keeps the GroupChatView in memory after the user navigates
    // back to the chat list, so its `.onChange(loadedMessages.count)` was
    // firing for new messages even when the view wasn't visible — and the
    // old logic called markGroupAsRead unconditionally, wiping unread
    // badges for messages the user hadn't actually seen. Now we only mark
    // read while the view is visible.
    @State private var isViewVisible: Bool = false

    // 🔧 2026-06-10 (b49): "Loading new message…" pill — same UX as the
    // 1-to-1 ChatView b47 fix. When the user reached this group via a
    // notification tap, the push banner arrives 0.5-3s before the IPFS
    // fetch + decrypt finishes adding the new message to groupMessages.
    // Without this pill the user lands in the group, sees only the old
    // messages, and thinks the notification was a phantom. Auto-hides
    // when displayMessages.count grows or after 8s safety timeout.
    @State private var showLoadingNewMessagePill: Bool = false
    @State private var loadingPillHideTask: DispatchWorkItem?

    // Pagination: only load last N messages initially, load more on scroll up
    @State private var messageDisplayLimit: Int = 50
    /// How many messages the active search / date filter matched (0 without one).
    @State private var filteredMatchCount = 0
    private let messagePageSize: Int = 50
    
    // ✅ WhatsApp-style camera capture (no view-once for groups)
    @State private var showGroupCamera = false
    @State private var showGroupVideoNote = false  // __VIDEO_NOTE_2026_09_24__
    @Environment(\.accessibilityReduceMotion) private var composerReduceMotion
    @State private var showDocumentPicker = false
    /// __GROUP_GIF_PICKER_2026_11_16__ GIFs & Stickers (incl. the OSHI mascots).
    @State private var showGifPicker = false
    /// __GROUP_ATTACH_PARITY_2026_11_16__ Share a contact card, as in 1:1.
    @State private var showContactPicker = false
    @State private var documentShareURL: URL? = nil
    @State private var showDocumentShare = false
    @State private var fullScreenImageItem: IdentifiableImageData? = nil
    @State private var showLocationPicker = false  // 📍 Location sharing for groups
    @State private var showScheduledMessage = false  // 💌 Thinking of You for groups
    @State private var showCheckIn = false           // 🛡️ Check-In for groups
    @State private var showPoll = false              // 📊 Sondage de groupe
    @State private var cashuDraft: CashuSendDraft?   // 🪙 __CASHU_SEND_READY_2026_11_09__
    @State private var showScheduledCall = false     // 📅 Scheduled Call for groups

    // Map & AI shortcuts
    @State private var showMapView = false
    @State private var showAIChatView = false

    // ✅ Audio recording support
    @StateObject private var audioRecorder = AudioRecorderManager()
    @State private var isRecordingAudio = false
    @State private var recordingMode: RecordingMode = .holdToRecord
    @State private var recordedAudio: AudioRecorderManager.AudioAttachment?
    @State private var showAudioPreview = false
    
    // ✅ Audio output toggle (speaker/earpiece)
    @State private var useExternalSpeaker = true
    /// Drives the speaker-route toggle's visibility — `item` is non-nil exactly
    /// while a voice note is loaded for playback. See `groupInputBar`.
    @ObservedObject private var voiceNotePlayback = VoiceNotePlayback.shared
    
    // ✅ Wallpaper support
    @ObservedObject private var wallpaperManager = ChatWallpaperManager.shared
    @State private var showWallpaperPicker = false
    /// __WALLPAPER_TONE_2026_09_24__ system scheme, before the wallpaper tone.
    @Environment(\.colorScheme) private var systemScheme
    
    enum RecordingMode {
        case holdToRecord
        case tapToRecord
    }
    
    // Search & Navigation
    @State private var showSearch = false
    @State private var searchText = ""
    @State private var showDatePicker = false
    @State private var showDateSearch = false
    @State private var selectedDate = Date()
    @State private var selectedSearchDate: Date?
    @State private var dateRangeStart: Date?
    @State private var dateRangeEnd: Date?
    @State private var scrollToMessageId: UUID?
    @State private var showScrollToBottom = false
    // 🔧 2026-06-11 (b57): verified-landing scroll state — see ScrollViewReader.onAppear.
    @State private var bottomAnchorSighted = false
    /// Set the moment the reader drags the thread. Until then, on open, the settle
    /// keeps re-aiming at the bottom and the native size-change anchor stays engaged
    /// so an async-growing GIF is followed to the true bottom. A drag disengages both.
    @State private var userScrolledSinceOpen = false
    /// __GROUP_OPEN_ANCHOR_2026_09_23__ Ground-truth "keep the newest message in
    /// view" flag for `ChatBottomFollower` (iOS 18+). True on open; a drag that
    /// ends away from the bottom clears it, returning to the bottom or sending
    /// re-arms it.
    @State private var followBottom = true
    /// __LONG_SEND_LANDING_2026_09_28__ See ChatView / `landAtBottomAfterOwnSend`.
    @State private var scrollTracker = ChatScrollTracker()
    @State private var ownSendLandingTask: Task<Void, Never>?
    @State private var openScrollRetryTask: Task<Void, Never>? = nil

    /// __OPEN_AT_THE_FIRST_UNREAD_2026_10_17__ Where this group should LAND.
    ///
    /// Groups did what 1:1 did before it was fixed: jump to the newest message
    /// and then mark the whole thread read. Coming back to a busy group with
    /// forty unread messages put you on the last one, destroyed the boundary
    /// before you could see it, and left you scrolling up to guess where you
    /// had stopped. Telegram lands on the first unread and reads forward; so
    /// does the 1:1 view now, and a group is the case that needs it MOST.
    ///
    /// Captured in `onAppear`, because `markGroupAsRead` a beat later is
    /// exactly what erases the answer.
    @State private var openUnreadAnchorId: String?

    /// Oldest message in this group we have not read and did not send.
    /// nil ⇒ caught up ⇒ the bottom is the right place.
    ///
    /// __UNREAD_ANCHOR_CANNOT_OUTRUN_THE_WINDOW_2026_11_07__ Searched over the
    /// SAME window the list renders, not the whole history.
    ///
    /// THE BUG THIS CLOSES: the behaviour flipped on how much you had missed,
    /// and flipped the wrong way. This used to scan every message ever, while
    /// the list draws only the newest `messageDisplayLimit`. So with 40 unread
    /// you were taken back to the boundary, and with 200 unread the anchor was
    /// outside the drawn rows, `scrollTo` hit nothing, and the retry loop fell
    /// back to the bottom — losing the boundary in exactly the busy group this
    /// feature was written for.
    ///
    /// Clamped to the window, "everything in view is unread" becomes the
    /// honest answer: the anchor is the first row, you land at the top of the
    /// window with the divider above it, and `load_earlier_messages` is right
    /// there if you want more. No case silently degrades to the bottom.
    private func firstUnreadIncomingGroupId() -> String? {
        let me = MessageManager.canonicalIdentity(identityManager.publicKey)
        let raw = groupManager.groupMessages[group.id] ?? []
        let all = Self.isChronological(raw) ? raw : raw.sorted { $0.timestamp < $1.timestamp }
        // Mirrors `loadMessages()`: the newest `messageDisplayLimit` are the rows.
        let window = all.count > messageDisplayLimit ? Array(all.suffix(messageDisplayLimit)) : all
        return window.first {
            !$0.isRead && MessageManager.canonicalIdentity($0.senderPublicKey) != me
        }?.id.uuidString
    }

    /// __UNREAD_HAD_NO_LINE_2026_11_07__ The id the open scroll actually aims at.
    ///
    /// Landing on the first unread is half of the design it was modelled on
    /// (`__OPEN_AT_THE_FIRST_UNREAD_2026_10_17__` cites Telegram). The other
    /// half is the SEPARATOR that says why you are not at the bottom. Without
    /// it the app silently parks you above the newest message and looks broken
    /// — reported as "it comes back higher than the newer message".
    ///
    /// The scroll aims at the divider, not at the message: anchoring `.top` on
    /// the bubble puts the divider one row ABOVE the viewport, where the reader
    /// never sees the thing that explains their position.
    static let unreadDividerId = "groupUnreadDivider"
    @State private var floatingDateHeader: String? = nil
    /// __CHAT_OPEN_PERF_2026_11_14__ Reference box, see `DeferredAction`: the
    /// header geometry callback restarted a `@State` work item on every scroll
    /// frame, re-rendering the whole group view (all 50 eager rows) per frame.
    @State private var floatingDateHider = DeferredAction()
    @State private var isAtBottom = true
    @State private var scrollProxy: ScrollViewProxy?
    
    // Block functionality
    @State private var showingBlockSheet = false
    @State private var showingUnblockAlert = false
    @ObservedObject private var blockedManager = BlockedContactsManager.shared
    // 🚩 Reporting (App Store guideline 1.2) — sits beside Block, same surface.
    @State private var showingReportSheet = false

    // 🔧 FIX: Leave/Delete group from menu
    @State private var showLeaveGroupAlert = false
    @State private var showAIShortcuts = false
    // Full-screen location map panel
    @State private var showFullMapPanel = false
    @State private var fullMapLatitude: Double = 0
    @State private var fullMapLongitude: Double = 0
    @State private var fullMapAddress: String? = nil
    @State private var fullMapIsLive: Bool = false
    @State private var replyingToMessage: ReplyMessage? = nil
    /// Message-action state for the LIVE bubble path — see GroupMessageActions.
    @State private var actionMessage: GroupMessage? = nil
    @State private var actionFrame: CGRect = .zero
    @State private var showMessageActions = false
    /// __CHANNEL_WEB_2026_11_26__ the post whose web link sheet is open.
    @State private var webLinkMessage: GroupMessage?
    @State private var showGroupForwardPicker = false
    // Parity with the 1:1 long-press menu (delete / edit / translate). These
    // actions existed only on `GroupMessageBubble`, which is instantiated
    // nowhere — see the comment on `groupMessageActionsOverlay`.
    @State private var showGroupDeleteSheet = false
    @State private var showGroupEditSheet = false
    /// __GROUP_EDIT_WAS_STILL_AN_ALERT_2026_11_05__ The body as it stood when
    /// the sheet opened, so the sheet can show "Original message" above the
    /// field. Separate from `groupEditingText`, which the user is editing.
    @State private var groupEditingOriginal = ""
    @State private var groupEditingText = ""
    @State private var groupTranslatedText = ""
    @State private var showGroupTranslationResult = false
    @ObservedObject private var nlpManager = NLPManager.shared
    /// 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ observed: any member can flip it.
    @ObservedObject private var advancedPrivacyStore = AdvancedChatPrivacyStore.shared
    private var isAdvancedPrivacyOn: Bool { advancedPrivacyStore.isOn(group.id.uuidString) }
    @Environment(\.dismiss) private var dismiss

    // ✅ FIX: Force LazyVStack re-render when messages change
    @State private var messageListVersion: Int = 0

    // ✅ FIX #5: Debounce handle for objectWillChange burst-collapse (200 ms window).
    // Under IPFS catch-up, objectWillChange fires ~10×/s — we want ~1 reload per burst.
    @State private var reloadDebounceTask: Task<Void, Never>? = nil

    private var isGroupBlocked: Bool {
        blockedManager.isGroupBlocked(group.id)
    }

    // ✅ AI: Conversation context for AI — last 5 human-readable group messages (filtered)
    private var conversationContextForAI: String? {
        // 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ nothing from a protected group
        // reaches smart replies, the AI shortcuts or the scheduled-message writer.
        guard AdvancedChatPrivacyPolicy.allowsAI(protected: isAdvancedPrivacyOn) else { return nil }
        let messages = groupManager.groupMessages[group.id] ?? []
        let readable = messages.filter { msg in
            // Skip system messages
            guard msg.systemMessageType == nil else { return false }
            // Get text content
            let text = msg.plaintextContent ?? groupManager.decryptMessage(msg)
            guard let t = text, !t.isEmpty else { return false }
            // Control payloads (receipts, polls, the privacy switch…) are not
            // conversation; the 1:1 context already dropped them.
            return !OSHIControlPayload.isControl(t)
        }
        let recent = readable.suffix(5)
        guard !recent.isEmpty else { return nil }
        let myKey = identityManager.publicKey
        let lines = recent.map { msg -> String in
            let text = msg.plaintextContent ?? groupManager.decryptMessage(msg) ?? ""
            if msg.senderPublicKey == myKey {
                return "You: \(text)"
            } else {
                // Show sender alias in group context
                let alias = liveGroup.members.first(where: { $0.publicKey == msg.senderPublicKey })?.alias ?? "Someone"
                return "\(alias): \(text)"
            }
        }
        return lines.joined(separator: "\n")
    }

    // Live group from manager (always up-to-date for pin/permission changes)
    private var liveGroup: MessageGroup {
        groupManager.groups.first(where: { $0.id == group.id }) ?? group
    }

    private var permGroup: MessageGroup {
        var g = liveGroup
        g.setCurrentUser(identityManager.publicKey)
        return g
    }

    private var isAdmin: Bool {
        liveGroup.adminPublicKey == identityManager.publicKey ||
        liveGroup.members.first(where: { $0.publicKey == identityManager.publicKey })?.isAdmin == true
    }

    var canAddMembers: Bool {
        permGroup.canAddMembers
    }
    
    // 🔧 DEBUG: sorted live messages from manager
    var sortedLiveMessages: [GroupMessage] {
        let sorted = (groupManager.groupMessages[group.id] ?? []).sorted { $0.timestamp < $1.timestamp }
        return mergeBotMessagePairs(sorted)
    }

    /// Merge consecutive bot text+media message pairs into a single combined bubble.
    /// Bots send two separate API calls (text then image), so we merge them like Telegram.
    private func mergeBotMessagePairs(_ messages: [GroupMessage]) -> [GroupMessage] {
        guard messages.count >= 2 else { return messages }
        var result: [GroupMessage] = []
        var skipNext = false
        for i in 0..<messages.count {
            if skipNext { skipNext = false; continue }
            let msg = messages[i]
            // Only merge consecutive bot messages from the same sender within 10 seconds
            if i + 1 < messages.count,
               msg.senderPublicKey.hasPrefix("bot:"),
               messages[i + 1].senderPublicKey == msg.senderPublicKey,
               abs(messages[i + 1].timestamp.timeIntervalSince(msg.timestamp)) < 10 {
                let next = messages[i + 1]
                let msgHasMedia = msg.mediaType != nil
                let nextHasMedia = next.mediaType != nil
                // One has media, the other is text-only → merge
                if msgHasMedia != nextHasMedia {
                    let mediaMsg = msgHasMedia ? msg : next
                    let textMsg = msgHasMedia ? next : msg
                    let caption = textMsg.plaintextContent
                        ?? String(data: textMsg.encryptedContent, encoding: .utf8)
                        ?? ""
                    // Strip "[BotName] " prefix from caption if present
                    let cleanCaption: String = {
                        if let range = caption.range(of: "] "), caption.hasPrefix("[") {
                            return String(caption[range.upperBound...])
                        }
                        return caption
                    }()
                    var merged = GroupMessage(
                        id: mediaMsg.id,
                        groupId: mediaMsg.groupId,
                        senderPublicKey: mediaMsg.senderPublicKey,
                        encryptedContent: Data(cleanCaption.utf8),
                        timestamp: msg.timestamp,  // use earlier timestamp
                        isRead: msg.isRead && next.isRead,
                        messageChainIndex: mediaMsg.messageChainIndex,
                        mediaType: mediaMsg.mediaType,
                        mediaFileName: mediaMsg.mediaFileName,
                        plaintextContent: textMsg.plaintextContent
                    )
                    merged.mediaData = mediaMsg.mediaData
                    result.append(merged)
                    skipNext = true
                    continue
                }
            }
            result.append(msg)
        }
        return result
    }

    /// Helper: check if current user sent this message
    private func isFromCurrentUser(_ message: GroupMessage) -> Bool {
        message.senderPublicKey == (groupManager.currentUserPublicKey ?? identityManager.publicKey)
    }

    /// __TELEGRAM_WIDE_BUBBLES_2026_09_15__ Telegram-style wide group text
    /// bubbles: a text bubble runs to ~82% of the screen before it wraps, so a
    /// long message fills the row instead of the 55% cap the other group cards
    /// (media/audio) share via `BubbleStyleManager.maxBubbleWidth`. Text-only —
    /// the shared cap is left untouched so the 1:1 thread is unaffected.
    /// __FOLDABLE_LAYOUT_2026_11_16__ 82 % of the THREAD's width (detail
    /// pane, Split View, iPhone Duo — not the screen), capped for readability.
    private var groupTextBubbleMaxWidth: CGFloat {
        BubbleStyleManager.maxGroupTextBubbleWidth(in: groupColumnWidth)
    }

    /// Clean inline bubble that always renders — bypasses heavy GroupMessageBubble
    @ViewBuilder
    private func cleanBubble(for message: GroupMessage) -> some View {
        let isMine = isFromCurrentUser(message)
        let text = resolveText(for: message)
        let isSystem = message.isSystemMessage
        // 🔧 PERF FIX (2026-06-02): For media messages `encryptedContent` is the
        // binary ciphertext blob (often hundreds of KB). The previous code ran
        // `String(data:encoding:.utf8)` over that blob on EVERY body re-render
        // of every visible bubble — UTF-8 validates the full byte array even
        // when it returns nil. With ~10 image bubbles and frequent re-renders
        // (typing indicator ticks, presence updates) this was burning real CPU
        // and adding to the open-group lag. Skip the decode when we have plain
        // OR when the message clearly carries media (binary ciphertext, not
        // text).
        let rawPlain: String = {
            if let p = message.plaintextContent { return p }
            if message.mediaType != nil { return "" }
            return String(data: message.encryptedContent, encoding: .utf8) ?? ""
        }()

        if isSystem {
            systemBubble(for: message)
        } else if OSHIControlPayload.isQuietNotice(rawPlain) {
            // 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ centred notice, never a bubble.
            if let change = AdvancedPrivacyChange.fromMessageString(rawPlain) {
                AdvancedPrivacyNoticeRow(
                    change: change,
                    actorName: isMine ? nil
                        : (liveGroup.members.first(where: { $0.publicKey == message.senderPublicKey })?.alias
                           ?? String(message.senderPublicKey.prefix(10))),
                    timestamp: message.timestamp)
            } else {
                EmptyView()
            }
        } else if rawPlain.hasPrefix("📍LOCATION📍") {
            // Location message — show map card
            groupLocationBubble(rawText: rawPlain, message: message, isMine: isMine)
        } else if rawPlain.hasPrefix("📅SCHEDULED_CALL📅") {
            // Scheduled call — show rich card
            groupScheduledCallBubble(rawText: rawPlain, message: message, isMine: isMine)
        } else if rawPlain.hasPrefix("🛡️CHECK_IN🛡️") || rawPlain.hasPrefix("🛡️CHECKIN🛡️") || rawPlain.hasPrefix("\u{1F6E1}\u{FE0F}CHECK_IN\u{1F6E1}\u{FE0F}") || rawPlain.hasPrefix("\u{1F6E1}\u{FE0F}CHECKIN\u{1F6E1}\u{FE0F}") {
            // Check-in — show status card
            groupCheckInBubble(rawText: rawPlain, message: message, isMine: isMine)
        } else if PollMessage.isPollMessage(rawPlain) {
            // 📊 Sondage — carte interactive
            groupPollBubble(rawText: rawPlain, message: message, isMine: isMine)
        } else if message.mediaType != nil && message.mediaType != .audio {
            // Media message (photo/video/doc) — show media bubble with optional caption
            mediaWithCaptionBubble(message: message, isMine: isMine, caption: text)
        } else if message.mediaType == .audio {
            // Audio message — show playable audio bubble
            audioPlaybackBubble(message: message, isMine: isMine)
        } else if let displayText = text, !displayText.isEmpty {
            messageBubble(text: displayText, message: message, isMine: isMine)
        } else if isFilteredPayload(message) {
            EmptyView()
        } else if message.senderPublicKey.hasPrefix("bot:") {
            // Bot message where resolveText returned nil — try raw content
            if !rawPlain.isEmpty {
                messageBubble(text: rawPlain, message: message, isMine: isMine)
            } else {
                EmptyView()
            }
        } else if !rawPlain.isEmpty {
            // Fallback: resolveText() returned nil but raw content exists — show it
            messageBubble(text: rawPlain, message: message, isMine: isMine)
        } else {
            // Truly empty/undecryptable — show spinner as last resort
            HStack {
                if isMine { Spacer() }
                ProgressView()
                    .padding(12)
                    .background(Color(.systemGray6))
                    .cornerRadius(15)
                if !isMine { Spacer() }
            }
            .padding(.horizontal, 8)
        }
    }

    /// Check if message contains a filtered system payload (already stored but shouldn't display)
    private func isFilteredPayload(_ message: GroupMessage) -> Bool {
        if let plain = message.plaintextContent, isSystemPayload(plain) { return true }
        if let text = String(data: message.encryptedContent, encoding: .utf8), isSystemPayload(text) { return true }
        return false
    }

    /// Extract visible text from a group message
    private func resolveText(for message: GroupMessage) -> String? {
        // Edited content takes priority
        if let edited = message.editedContent, !edited.isEmpty { return edited }
        // plaintextContent (set by sender, preserved through relay)
        if let plain = message.plaintextContent, !plain.isEmpty {
            // Strip bot prefix for display
            if message.senderPublicKey.hasPrefix("bot:"), plain.hasPrefix("["),
               let close = plain.firstIndex(of: "]") {
                let after = String(plain[plain.index(after: close)...].drop(while: { $0 == " " }))
                // Check if remaining content is a system payload — hide it
                if after.isEmpty || isSystemPayload(after) { return nil }
                return OSHIControlPayload.displayTextPreservingEnvelopes(for: after)
            }
            // Filter out system payloads
            if isSystemPayload(plain) { return nil }
            // Convert special message types to human-readable text
            if let humanText = humanReadableSpecialMessage(plain) { return humanText }
            // 🔧 RAW-JSON FIX: `return plain` was the group leak. A group call
            // summary, a reaction, a profile/wallpaper update or a key rotation
            // is not "special" (no dedicated bubble) and is not "system" (it has
            // a meaning), so it fell here and rendered its payload as chat text.
            // Envelopes are preserved — the bubble below decodes them itself to
            // draw the quote box.
            return OSHIControlPayload.displayTextPreservingEnvelopes(for: plain)
        }
        // __CHAT_OPEN_PERF_2026_11_14__ For a media message `encryptedContent`
        // is the media blob (often MBs): the two UTF-8 decodes below validated
        // all of it on every body pass of every media row. Same answer,
        // computed once per message.
        if message.mediaType != nil {
            return GroupMediaStore.shared.memoizedText(for: message) { resolveEncryptedText(for: message) }
        }
        return resolveEncryptedText(for: message)
    }

    /// The `encryptedContent` half of `resolveText`.
    private func resolveEncryptedText(for message: GroupMessage) -> String? {
        // Try UTF-8 decode of encryptedContent
        if let text = String(data: message.encryptedContent, encoding: .utf8), !text.isEmpty {
            if isSystemPayload(text) { return nil }
            if text.starts(with: "{") && text.contains("\"type\":\"media\"") { return nil }
            // Filter raw JSON that shouldn't be shown as text (location, scheduling, etc.)
            if text.starts(with: "{") && (text.contains("\"latitude\"") || text.contains("\"scheduledCall\"") || text.contains("\"thinkingOfYou\"")) { return nil }
            if let humanText = humanReadableSpecialMessage(text) { return humanText }
            return OSHIControlPayload.displayTextPreservingEnvelopes(for: text)
        }
        // Try group decryption
        if let decrypted = groupManager.decryptMessage(message), !decrypted.isEmpty {
            if isSystemPayload(decrypted) { return nil }
            if let humanText = humanReadableSpecialMessage(decrypted) { return humanText }
            return OSHIControlPayload.displayTextPreservingEnvelopes(for: decrypted)
        }
        return nil
    }

    /// Convert special message formats to human-readable text (for types without dedicated bubbles)
    private func humanReadableSpecialMessage(_ text: String) -> String? {
        // These prefixed types are kept raw for routing to dedicated bubbles in cleanBubble
        if text.hasPrefix("📍LOCATION📍") { return text }
        if text.hasPrefix("📅SCHEDULED_CALL📅") { return text }
        if text.hasPrefix("🛡️CHECK_IN🛡️") { return text }
        if text.hasPrefix("🛡️CHECKIN🛡️") { return text }
        if PollMessage.isPollMessage(text) { return text }
        return nil
    }

    /// Check if content is a system/internal payload that should not be displayed
    ///
    /// 🔧 RAW-JSON FIX: this knew THREE sentinels out of twenty-four. A group
    /// read receipt, a delivery receipt, the second typing spelling, a call
    /// signal, a profile request — all fell straight through to
    /// `messageBubble(text:)` and rendered their payload. Delegated to the
    /// catalog, which also carries the bare-JSON group-sync recognisers.
    private func isSystemPayload(_ text: String) -> Bool {
        OSHIControlPayload.isSilent(text)
    }

    /// Inline system message bubble
    private func systemBubble(for message: GroupMessage) -> some View {
        HStack {
            Spacer()
            Text(OSHIControlPayload.previewText(for: message.plaintextContent ?? ""))
                .font(.caption)
                .foregroundColor(.secondary)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Capsule().fill(Color(.systemGray6)))
            Spacer()
        }
        .padding(.vertical, 4)
    }

    /// Inline text message bubble
    private func messageBubble(text: String, message: GroupMessage, isMine: Bool) -> some View {
        let isBot = message.senderPublicKey.hasPrefix("bot:")
        // 🔧 FIX (2026-08-12): this is the LIVE group text path, and it fed the
        // string straight to `GroupInlineLinkBubble`. `resolveText` hands reply
        // envelopes back UNTOUCHED on purpose — `displayTextPreservingEnvelopes`
        // says "the bubble decodes them itself to draw the quote box" — but the
        // bubble that does is `GroupMessageBubble`, which is instantiated
        // nowhere (see groupMessageActionsOverlay). So a group reply rendered
        // its raw 💬REPLY💬{…} envelope as chat text, and there was no quote box
        // in a group at all.
        let parsed = MessageWithReply.fromMessageString(text)
        // __FORWARD_BADGE_2026_08_18__ `ForwardedBadgeView` had existed and been
        // localized in 34 languages since forwarding shipped, and was
        // instantiated NOWHERE outside its own #Preview — so a forwarded
        // message landed in a group looking exactly like something the sender
        // wrote himself, which is the one thing the badge exists to deny.
        //
        // Read BEFORE `previewText`: that call unwraps the ➡️FORWARDED➡️
        // envelope down to the body, so after it there is nothing left to
        // detect. `parsed?.content ?? text` covers both nestings — the wire
        // always puts the forward outermost (every send site runs the content
        // through `previewText` first, which strips any reply), but a peer that
        // wrapped the other way still gets its badge.
        let forwardInfo = MessageActionsManager.shared.getForwardInfo(parsed?.content ?? text)
        let body = OSHIControlPayload.previewText(for: parsed?.content ?? text)
        // __BUBBLE_MEDIA_2026_11_21__ A channel post is a card: incoming
        // surface, leading, fixed card width, comments bar inside.
        let card = isChannelCard(message)
        let mineStyle = isMine && !card
        let embedsComments = card && cardEmbedsComments(message)
        return HStack {
            if mineStyle { Spacer() }
            VStack(alignment: mineStyle ? .trailing : .leading, spacing: 4) {
                if let reply = parsed?.replyTo {
                    groupReplyQuote(reply, isMine: mineStyle)
                }
                // Combined link preview + message in single bubble
                GroupInlineLinkBubble(
                    text: body,
                    isMine: mineStyle,
                    bubbleColor: mineStyle ? BubbleStyleManager.sentBubbleColour(nil)
                                           : BubbleStyleManager.receivedBubbleColour,
                    textColor: mineStyle ? BubbleStyleManager.sentInk : .primary,
                    forwardCount: forwardInfo?.forwardCount,
                    cardWidth: card ? mediaMaxWidth(channel: true) : nil,
                    commentsPost: embedsComments ? (message.groupId, message.id) : nil
                )
                // 🔧 FIX (2026-08-06, "group bubbles take 85% of the screen"):
                // this is the LIVE render path — `cleanBubble` → `messageBubble`
                // → GroupInlineLinkBubble — and it had no width cap at all,
                // while GroupInlineLinkBubble's own body uses
                // `.frame(maxWidth: .infinity)`. The 55% cap added earlier
                // landed on five OTHER bubble sites, none of which draw a plain
                // group text message. That is why the number never moved.
                // __TELEGRAM_WIDE_BUBBLES_2026_09_15__ Widened from the shared
                // 55% cap to ~82% so group text reads like Telegram.
                .frame(maxWidth: card ? nil : groupTextBubbleMaxWidth,
                       alignment: mineStyle ? .trailing : .leading)

                // __TICKER_CHIPS_WIRED_2026_08_25__ La pastille de cours, qui
                // existait mais n'était instanciée nulle part. Ne rend rien si
                // le texte ne porte aucun `@TICKER`, et ne va chercher un prix
                // qu'au premier appui.
                TickerChipsForText(text: body, tint: groupBrandColor,
                                   messageId: message.id.uuidString,
                                   autoLoad: isMine)   // __TICKER_RECEIVED_NO_AUTOLOAD_2026_11_20__
                    .frame(maxWidth: groupTextBubbleMaxWidth,
                           alignment: mineStyle ? .trailing : .leading)

                HStack(spacing: 5) {
                    if !isMine {
                        if isBot {
                            Image(systemName: "cpu").font(.caption2).foregroundColor(.blue)
                            Text(botName(from: message)).font(.caption2).fontWeight(.medium).foregroundColor(.blue)
                            BotBadge()
                        } else {
                            // __TELEGRAM_WIDE_BUBBLES_2026_09_15__ Telegram tints
                            // the sender name in the group accent and gives it a
                            // touch of weight; `groupBrandColor` is the same
                            // theme-aware token the bubble fill uses.
                            Text(senderDisplayName(message.senderPublicKey)).font(.caption2).fontWeight(.medium)
                                .foregroundColor(AvatarIdentity.nameColor(for: message.senderPublicKey))
                        }
                        Text("•").font(.caption2).foregroundColor(.secondary)
                    }
                    if message.editedAt != nil {
                        Text("message.edited".localized).font(.caption2).italic().foregroundColor(.secondary)
                    }
                    Text(chatFormatTimestamp(message.timestamp)).font(.caption2).foregroundColor(.secondary)
                    partialDeliveryBadge(message, ink: .secondary)   // __GROUP_RESEND_2026_11_25__
                }
                // __BOT_INLINE_KEYBOARD_2026_09_16__ Under the bubble, above the
                // reactions — where Telegram puts it, and the only place where a
                // row of buttons reads as belonging to this message.
                botInlineKeyboard(for: message)
                // Reactions
                MessageReactionsView(messageId: message.id.uuidString, isFromCurrentUser: mineStyle)
            }
            if !mineStyle { Spacer(minLength: 0) }
        }
        .padding(.vertical, 8)
        .padding(.horizontal, card ? 0 : 8)
    }

    /// The quote box above a group reply: who is quoted, what they said, and a
    /// thumbnail when the quoted message was a picture (see `ReplyQuoteBody`).
    ///
    /// Its own function, not inlined into `messageBubble`, for the type-checker
    /// budget this file has hit before.
    private func groupReplyQuote(_ reply: ReplyMessage, isMine: Bool) -> some View {
        // __REPLY_QUOTE_2026_08_17__ Même traitement qu'en 1:1: la citation se
        // teinte à partir de la bulle qui la porte. Un `systemGray5` fixe se
        // lisait comme un rectangle collé, et sur MA bulle — peinte à la couleur
        // du groupe — le texte `.secondary` par-dessus était illisible.
        // __REPLY_QUOTE_ON_THE_WALLPAPER_2026_11_09__ This box sits ABOVE the
        // bubble (see the caller), on the wallpaper — so `.white` on my side was
        // white on the light lavender ground. Wallpaper-safe tokens, own surface.
        // __BUBBLE_MEDIA_2026_11_21__ spec §2: the bar and title take the
        // QUOTED sender's name colour (same hue as their name in the thread);
        // every hue clears 4.5:1 on the incoming surface (BubblePaletteContrastTests).
        let accent: Color = AvatarIdentity.nameColor(for: reply.originalSenderKey)
        // __REPLY_QUOTE_2026_08_19__ Same change as the 1:1 quote, for the same
        // reason: a filled block inside a bubble reads as a bubble inside a bubble.
        // On my own bubble there is no fill at all — the bubble is already a
        // coloured surface. Received keeps a 7 % wash so the quote holds together
        // on a pale bubble over a pale wallpaper.
        let bodyColor: Color = ChatPalette.quoteBody
        return HStack(spacing: 9) {
            Capsule(style: .continuous)
                .fill(accent)
                .frame(width: 3)

            ReplyQuoteBody(
                reply: reply,
                title: senderDisplayName(reply.originalSenderKey),
                titleColor: accent,
                textColor: bodyColor,
                thumbnailSide: 34
            )
        }
        // Same fix as the 1:1 quote: constrain the row to its content, or the
        // accent capsule stretches to whatever height the bubble offers.
        .fixedSize(horizontal: false, vertical: true)
        .padding(.vertical, 3)
        .padding(.trailing, 4)
        // __BUBBLE_HUGS_2026_08_14__ fill BEFORE the cap: a `.frame(maxWidth:)`
        // fills what the parent offers rather than shrinking to its child, so
        // capping first painted every short reply at full width.
        .background(
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .fill(ChatPalette.quoteSurface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .stroke(accent.opacity(0.18), lineWidth: 0.5)
        )
        // __TELEGRAM_WIDE_BUBBLES_2026_09_15__ Match the widened text bubble so
        // the quote box lines up with the message it sits above.
        .frame(maxWidth: groupTextBubbleMaxWidth,
               alignment: isMine ? .trailing : .leading)
        .contentShape(Rectangle())
        .onTapGesture {
            NotificationCenter.default.post(
                name: NSNotification.Name("ScrollToOriginalGroupMessage"),
                object: nil,
                userInfo: ["messageId": reply.originalMessageId]
            )
        }
    }

    /// __BOT_INLINE_KEYBOARD_2026_09_16__
    ///
    /// The rows of buttons a bot hung under this message, if any. Renders
    /// nothing at all for a human message, for a bot message with no
    /// `replyMarkup`, and for one whose keyboard did not survive parsing — the
    /// three cases that are indistinguishable to the reader and must be.
    @ViewBuilder
    private func botInlineKeyboard(for message: GroupMessage) -> some View {
        if message.senderPublicKey.hasPrefix("bot:"),
           let record = botManager.replyMarkup(forMessageId: message.id.uuidString),
           !record.keyboard.isEmpty {
            BotInlineKeyboardView(
                record: record,
                localMessageId: message.id.uuidString,
                senderKey: message.senderPublicKey,
                tint: groupBrandColor,
                maxWidth: groupTextBubbleMaxWidth
            )
        }
    }

    /// Bot name from message
    private func botName(from message: GroupMessage) -> String {
        if let plain = message.plaintextContent, plain.hasPrefix("["),
           let end = plain.firstIndex(of: "]") {
            return String(plain[plain.index(after: plain.startIndex)..<end])
        }
        return "Bot"
    }

    /// Location message bubble — parse 📍LOCATION📍{json} and show a map-like card
    // MARK: - Location Bubble (with Map)

    /// Settings › Maps (see MapOpener): Apple/Google open the place directly;
    /// OSHI keeps the in-app full-map panel. Before, the group bubble ALWAYS
    /// opened OSHI's panel whatever the setting said.
    private func openGroupLocation(lat: Double, lon: Double, address: String?, isLive: Bool) {
        if MapOpener.opensExternally {
            MapOpener.open(coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon), name: address)
            return
        }
        fullMapLatitude = lat
        fullMapLongitude = lon
        fullMapAddress = address
        fullMapIsLive = isLive
        showFullMapPanel = true
    }

    private func groupLocationBubble(rawText: String, message: GroupMessage, isMine: Bool) -> some View {
        let jsonStr = String(rawText.dropFirst("📍LOCATION📍".count))
        // Extract coordinates from LocationMessage or JSON
        var lat: Double = 0
        var lon: Double = 0
        var address: String? = nil
        var isLive = false

        if let locationMessage = LocationMessage.fromMessageString(rawText) {
            lat = locationMessage.coordinate.latitude
            lon = locationMessage.coordinate.longitude
            address = locationMessage.address
            isLive = locationMessage.isLive
        } else {
            let info = parseLocationJSON(jsonStr)
            lat = info.latitude
            lon = info.longitude
            address = info.address
            isLive = info.isLive
        }

        let hasCoords = lat != 0 || lon != 0

        return HStack {
            if isMine { Spacer() }
            VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
                if hasCoords {
                    VStack(spacing: 0) {
                        // Map preview — zoomed out (0.05 span) to show surrounding area
                        Map {
                            Annotation("", coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon)) {
                                ZStack {
                                    Circle()
                                        .fill(groupBrandColor.opacity(0.3))
                                        .frame(width: 40, height: 40)
                                    if isLive {
                                        Circle()
                                            .stroke(groupBrandColor, lineWidth: 2)
                                            .frame(width: 40, height: 40)
                                            .scaleEffect(1.3)
                                            .opacity(0.5)
                                    }
                                    Circle()
                                        .fill(groupBrandColor)
                                        .frame(width: 16, height: 16)
                                    Text("📍")
                                        .font(.scaled(10))
                                }
                            }
                        }
                        .mapStyle(.standard)
                        .frame(width: 280, height: 180)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .allowsHitTesting(false)
                        .overlay(alignment: .topTrailing) {
                            if isLive {
                                HStack(spacing: 4) {
                                    Circle().fill(groupBrandColor).frame(width: 8, height: 8)
                                    Text("LIVE")
                                        .font(.scaled(10, weight: .bold))
                                        .foregroundColor(.white)
                                }
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(Color.black.opacity(0.6))
                                .cornerRadius(10)
                                .padding(8)
                            }
                        }

                        // Info section
                        VStack(alignment: .leading, spacing: 6) {
                            HStack(spacing: 6) {
                                Image(systemName: isLive ? "location.fill" : "mappin.circle.fill")
                                    .font(.caption)
                                    .foregroundColor(isLive ? groupBrandColor : .red)
                                Text(isLive ? "location.sharing_live".localized : "location.shared_location".localized)
                                    .font(.caption).fontWeight(.medium)
                                Spacer()
                            }
                            if let addr = address, !addr.isEmpty {
                                Text(addr).font(.caption2).foregroundColor(.secondary).lineLimit(2)
                            }
                            // Tap to open full-screen map
                            Button {
                                openGroupLocation(lat: lat, lon: lon, address: address, isLive: isLive)
                            } label: {
                                HStack {
                                    Image(systemName: "arrow.up.left.and.arrow.down.right").font(.caption)
                                    Text(NSLocalizedString("location.view_full_map", value: "View full map", comment: "")).font(.caption)
                                }
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 8)
                                .background(groupBrandColor.opacity(0.15))
                                .foregroundColor(groupBrandColor)
                                .cornerRadius(8)
                            }
                        }
                        .padding(10)
                        .frame(width: 280, alignment: .leading)
                        .background(Color(.systemGray6))
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .onTapGesture {
                        openGroupLocation(lat: lat, lon: lon, address: address, isLive: isLive)
                    }
                } else {
                    messageBubble(text: "📍 Location", message: message, isMine: isMine)
                }
                senderTimestampRow(message: message, isMine: isMine)
            }
            if !isMine { Spacer() }
        }
        .padding(8)
    }

    /// Parse location JSON into components
    private func parseLocationJSON(_ jsonStr: String) -> (latitude: Double, longitude: Double, address: String?, isLive: Bool) {
        guard let data = jsonStr.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return (0, 0, nil, false)
        }
        let lat = json["latitude"] as? Double ?? 0
        let lon = json["longitude"] as? Double ?? 0
        let address = json["address"] as? String
        let isLive = json["isLive"] as? Bool ?? false
        return (lat, lon, address, isLive)
    }

    // MARK: - Scheduled Call Bubble

    private func groupScheduledCallBubble(rawText: String, message: GroupMessage, isMine: Bool) -> some View {
        let jsonStr = String(rawText.dropFirst("📅SCHEDULED_CALL📅".count))
        return HStack {
            if isMine { Spacer() }
            VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
                GroupScheduledCallCard(
                    jsonStr: jsonStr,
                    senderKey: message.senderPublicKey,
                    isMine: isMine
                )
                .frame(maxWidth: 300)
                senderTimestampRow(message: message, isMine: isMine)
            }
            if !isMine { Spacer() }
        }
        .padding(8)
    }

    // MARK: - Poll Bubble

    /// Un sondage dans un groupe est le même sondage que dans un 1:1 — plus de
    /// votants, et des noms que cette vue sait résoudre (`senderDisplayName`),
    /// ce qu'un 1:1 ne peut pas faire pour vingt personnes.
    private func groupPollBubble(rawText: String, message: GroupMessage, isMine: Bool) -> some View {
        guard let poll = PollMessage.fromMessageString(rawText) else {
            return AnyView(messageBubble(text: "📊 " + "poll.title".localized,
                                         message: message, isMine: isMine))
        }
        let me = identityManager.publicKey
        return AnyView(
            HStack {
                if isMine { Spacer() }
                VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
                    PollMessageBubble(
                        poll: poll,
                        myPublicKey: me,
                        onVote: { picks in sendGroupPollVote(poll: poll, picks: picks) },
                        displayName: { key in
                            // __POLL_VOTER_KEYS_ARE_CANONICAL_2026_10_23__
                            // `PollStore` keys ballots by `canonicalIdentity`, which
                            // ADDS base64 padding — and `senderDisplayName` matches a
                            // member with `==`, so a padded key missed every member and
                            // the voter list printed a truncated key instead of a name.
                            // Caught by the parity harness, not by a build.
                            let want = MessageManager.canonicalIdentity(key)
                            if want == MessageManager.canonicalIdentity(me) { return "you".localized }
                            let g = groupManager.groups.first(where: { $0.id == group.id }) ?? group
                            if let alias = g.members.first(where: {
                                MessageManager.canonicalIdentity($0.publicKey) == want
                            })?.alias, !alias.isEmpty {
                                return alias
                            }
                            return senderDisplayName(key)
                        }
                    )
                    .background(RoundedRectangle(cornerRadius: 18).fill(Color.secondary.opacity(0.12)))
                    senderTimestampRow(message: message, isMine: isMine)
                }
                if !isMine { Spacer() }
            }
            .padding(8)
        )
    }

    /// Même forme qu'une réaction de groupe: on inscrit le bulletin localement,
    /// puis on l'envoie. L'inscription d'abord, pour que la barre bouge sous le
    /// doigt sans attendre le réseau.
    private func sendGroupPollVote(poll: PollMessage, picks: [Int]) {
        let me = identityManager.publicKey
        let vote = PollVotePayload(pollId: poll.id, picks: picks)
        PollStore.shared.record(vote: vote, from: me)
        groupManager.sendMessage(to: group.id,
                                 content: vote.toMessageString(),
                                 senderPublicKey: me)
    }

    // MARK: - Check-In Bubble

    private func groupCheckInBubble(rawText: String, message: GroupMessage, isMine: Bool) -> some View {
        // Try to parse with CheckInMessage (handles 🛡️CHECK_IN🛡️ prefix)
        if let checkIn = CheckInMessage.fromMessageString(rawText) {
            return AnyView(
                HStack {
                    if isMine { Spacer() }
                    VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
                        CheckInMessageBubble(message: checkIn, isFromCurrentUser: isMine)
                        senderTimestampRow(message: message, isMine: isMine)
                    }
                    if !isMine { Spacer() }
                }
                .padding(8)
            )
        }
        // Try old format: 🛡️CHECKIN🛡️{json} (without underscore)
        let oldPrefix = "🛡️CHECKIN🛡️"
        if rawText.hasPrefix(oldPrefix) {
            let jsonStr = String(rawText.dropFirst(oldPrefix.count))
            if let data = jsonStr.data(using: .utf8),
               let checkIn = try? JSONDecoder().decode(CheckInMessage.self, from: data) {
                return AnyView(
                    HStack {
                        if isMine { Spacer() }
                        VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
                            CheckInMessageBubble(message: checkIn, isFromCurrentUser: isMine)
                            senderTimestampRow(message: message, isMine: isMine)
                        }
                        if !isMine { Spacer() }
                    }
                    .padding(8)
                )
            }
        }
        // Fallback: show a clean card instead of raw JSON
        return AnyView(
            HStack {
                if isMine { Spacer() }
                VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
                    HStack(spacing: 10) {
                        Image(systemName: "shield.checkered")
                            .font(.title2)
                            .foregroundColor(.blue)
                        Text("🛡️ Check-in")
                            .font(.subheadline.bold())
                    }
                    .padding(12)
                    .background(Color(.systemGray6))
                    .cornerRadius(15)
                    senderTimestampRow(message: message, isMine: isMine)
                }
                if !isMine { Spacer() }
            }
            .padding(8)
        )
    }

    // MARK: - Sender/Timestamp Row Helper

    private func senderTimestampRow(message: GroupMessage, isMine: Bool) -> some View {
        let isBot = message.senderPublicKey.hasPrefix("bot:")
        return HStack(spacing: 5) {
            if !isMine {
                if isBot {
                    Image(systemName: "cpu").font(.caption2).foregroundColor(.blue)
                    Text(botName(from: message)).font(.caption2).fontWeight(.medium).foregroundColor(.blue)
                    BotBadge()
                } else {
                    Text(chatFormatAddress(message.senderPublicKey)).font(.caption2).foregroundColor(.secondary)
                }
                Text("•").font(.caption2).foregroundColor(.secondary)
            }
            Text(chatFormatTimestamp(message.timestamp)).font(.caption2).foregroundColor(.secondary)
        }
    }

    /// __CONTACT_CARD_COMPAT_2026_09_27__ A shared contact in a group.
    ///
    /// This used to be a static "Contact" label with no action at all, whoever
    /// sent it — Android has drawn a tappable card since 2026-08-24
    /// (`GroupChatScreen.kt`, `ContactCardBubble`). Same parser and same card as
    /// the 1-on-1 thread (`ContactCard.parse`, `ContactCardView`); tapping opens
    /// the 1-on-1 chat through `OpenChat`, the route `replyInPrivate` uses. Bytes
    /// no parser understands are offered as a file to share or save instead.
    @ViewBuilder
    private func groupContactCard(message: GroupMessage, data: Data?) -> some View {
        if let data, let card = ContactCard.parse(data) {
            let open = {
                guard card.publicKey != identityManager.publicKey else { return }
                MessageManager.shared?.addPendingConversation(publicKey: card.publicKey,
                                                              identityManager: identityManager)
                NotificationCenter.default.post(
                    name: NSNotification.Name("OpenChat"),
                    object: nil,
                    userInfo: ["peerPublicKey": card.publicKey]
                )
            }
            Button(action: open) {
                ContactCardView(contactCard: card, onStartChat: open)
            }
            .buttonStyle(.plain)
            .frame(maxWidth: BubbleStyleManager.maxBubbleWidth(in: chatColumnWidth))
        } else {
            Button {
                if let data {
                    // __GROUP_FILENAME_TRAVERSAL_2026_11_20__ `mediaFileName` is the
                    // SENDER's string. Joined straight onto tmp/, "../Library/Preferences/…"
                    // wrote attacker bytes anywhere in the sandbox, in clear. The shared
                    // helper keeps only the last path component, inside its own folder.
                    let fileName = message.mediaFileName ?? "contact.vcf"
                    if let tempURL = try? MediaPlaintextTemp.writeForShare(data, fileName: fileName) {
                        documentShareURL = tempURL
                        showDocumentShare = true
                    }
                }
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "person.crop.circle.fill")
                        .font(.scaled(32))
                        .foregroundColor(groupBrandColor)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(message.mediaFileName ?? "Contact")
                            .font(.subheadline).fontWeight(.medium)
                            .foregroundColor(.primary)
                            .lineLimit(2)
                        if let data {
                            Text(ByteCountFormatter.string(fromByteCount: Int64(data.count), countStyle: .file))
                                .font(.caption).foregroundColor(.secondary)
                        }
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption).foregroundColor(.secondary)
                }
                .padding()
                .background(Color(.systemGray6))
                .cornerRadius(12)
            }
            .buttonStyle(PlainButtonStyle())
            .frame(maxWidth: BubbleStyleManager.maxBubbleWidth(in: chatColumnWidth))
        }
    }

    /// Inline media bubble (photo/video/audio/doc)
    private func mediaBubble(message: GroupMessage, isMine: Bool) -> some View {
        // __CHAT_OPEN_PERF_2026_11_14__ The bytes come from `GroupMediaDataReader`
        // (memory, else one off-main disk read + decrypt) instead of a
        // synchronous `loadGroupMediaData` in this body — see ChatMediaLoading.swift.
        GroupMediaDataReader(message: message, refresh: messageListVersion) { mediaData, isLoading in
        HStack {
            if isMine { Spacer() }
            VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
                if isLoading, let side = Self.mediaLoadingBox(for: message.mediaType) {
                    Self.mediaLoadingPlaceholder(side)
                } else if message.mediaType == .photo, let data = mediaData {
                    // ✅ FIX #2: Lazy decode — UIImage(data:) is no longer called inside body.
                    LazyGroupPhotoView(id: message.id.uuidString, data: data, maxDimension: 800) {
                        fullScreenImageItem = IdentifiableImageData(data: data, isMine: isMine)
                    }
                    .cornerRadius(15)
                } else if message.mediaType == .document {
                    // Document card with file info and tap-to-share
                    Button {
                        // 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ others' files stay in OSHI.
                        if AdvancedChatPrivacyPolicy.allowsSavingMedia(protected: isAdvancedPrivacyOn, isFromMe: isMine),
                           let data = mediaData {
                            let fileName = message.mediaFileName ?? "document"
                            // __PLAINTEXT_LEFTOVERS_2026_09_24__ dossier de partage, protégé.
                            documentShareURL = try? MediaPlaintextTemp.writeForShare(data, fileName: fileName)
                            showDocumentShare = true
                        }
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "doc.fill")
                                .font(.scaled(32))
                                .foregroundColor(groupBrandColor)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(message.mediaFileName ?? "Document")
                                    .font(.subheadline).fontWeight(.medium)
                                    .foregroundColor(.primary)
                                    .lineLimit(2)
                                if let data = mediaData {
                                    Text(ByteCountFormatter.string(fromByteCount: Int64(data.count), countStyle: .file))
                                        .font(.caption).foregroundColor(.secondary)
                                }
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption).foregroundColor(.secondary)
                        }
                        .padding()
                        .background(Color(.systemGray6))
                        .cornerRadius(12)
                    }
                    .buttonStyle(PlainButtonStyle())
                    .frame(maxWidth: BubbleStyleManager.maxBubbleWidth(in: chatColumnWidth))
                } else if message.mediaType == .video, let data = mediaData {
                    // Video thumbnail
                    videoThumbnail(data: data, message: message)
                } else if message.mediaType == .contact {
                    groupContactCard(message: message, data: mediaData)
                } else if message.mediaType == .audio {
                    // Audio player placeholder
                    HStack(spacing: 8) {
                        Image(systemName: "waveform")
                            .font(.title3).foregroundColor(groupBrandColor)
                        Text("Audio").font(.subheadline).foregroundColor(.secondary)
                        if let data = mediaData {
                            Text(ByteCountFormatter.string(fromByteCount: Int64(data.count), countStyle: .file))
                                .font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    .padding(12)
                    .background(Color(.systemGray5))
                    .cornerRadius(15)
                } else {
                    HStack(spacing: 8) {
                        Image(systemName: mediaIcon(for: message.mediaType))
                            .font(.title3)
                            .foregroundColor(isMine ? BubbleStyleManager.sentAccentInk : groupBrandColor)
                        Text(mediaLabel(for: message.mediaType, fileName: message.mediaFileName))
                            .font(.subheadline)
                            .foregroundColor(isMine ? BubbleStyleManager.sentSecondaryInk : .secondary)
                    }
                    .padding(12)
                    .background(isMine ? BubbleStyleManager.sentBubbleColour(nil) : BubbleStyleManager.receivedBubbleColour)
                    .cornerRadius(15)
                }
                senderTimestampRow(message: message, isMine: isMine)
            }
            if !isMine { Spacer() }
        }
        .padding(8)
        }
    }

    /// While a photo or video's bytes load: a box the size the lazy views use
    /// for their own placeholder, so the row keeps its height. Other kinds keep
    /// their existing (data-less) rendering.
    private static func mediaLoadingBox(for type: MediaType?) -> CGSize? {
        switch type {
        case .photo: return CGSize(width: 240, height: 200)
        case .video: return CGSize(width: 250, height: 150)
        default: return nil
        }
    }

    private static func mediaLoadingPlaceholder(_ size: CGSize) -> some View {
        RoundedRectangle(cornerRadius: 15)
            .fill(Color(.systemGray5))
            .frame(width: size.width, height: size.height)
            .overlay(ProgressView())
    }

    // MARK: - Channel cards & media layout (__BUBBLE_MEDIA_2026_11_21__)

    /// A post of a channel (posting restricted to admins, sent by an admin):
    /// drawn as a CARD — incoming surface, no tail, wider, leading — not as a
    /// chat line (BUBBLE_MEDIA_SPEC §2).
    private func isChannelCard(_ m: GroupMessage) -> Bool {
        guard liveGroup.settings?.postPolicy == .admins, !m.isSystemMessage, m.commentOn == nil else { return false }
        return GroupSettingsPolicy.isAdmin(m.senderPublicKey, in: liveGroup)
    }

    /// The card draws the "💬 comments" bar INSIDE itself, under a hairline,
    /// for the two kinds it owns (media, plain text). Everything else (poll,
    /// location, check-in…) keeps the bar under it, as before.
    private func cardEmbedsComments(_ m: GroupMessage) -> Bool {
        guard GroupCommentPolicy.isPost(m, in: liveGroup) else { return false }
        if m.mediaType != nil { return m.mediaType != .audio }
        let raw = m.plaintextContent ?? String(data: m.encryptedContent, encoding: .utf8) ?? ""
        if raw.hasPrefix("📍LOCATION📍") || raw.hasPrefix("📅SCHEDULED_CALL📅")
            || raw.hasPrefix("🛡️CHECK_IN🛡️") || raw.hasPrefix("🛡️CHECKIN🛡️")
            || PollMessage.isPollMessage(raw) { return false }
        return true
    }

    /// Width the list gives a row: the screen minus the list's 16-pt insets.
    /// A channel card drops the row's own 8-pt padding, so it may use 90 % of it.
    private var channelColumnWidth: CGFloat { mediaColumnWidth - 32 }

    /// __FOLDABLE_LAYOUT_2026_11_16__ the thread's own column when known (two
    /// panes on an open iPhone Duo / iPad), the screen otherwise.
    private var mediaColumnWidth: CGFloat { chatColumnWidth > 0 ? chatColumnWidth : BubbleMediaLayout.screenWidth }

    /// The scheme the thread is drawn in (the wallpaper tone may force one).
    private var threadScheme: ColorScheme {
        wallpaperManager.appearance(for: group.id.uuidString, system: systemScheme).contentScheme
    }

    private func mediaMaxWidth(channel: Bool) -> CGFloat {
        channel ? BubbleMediaLayout.maxWidth(containerWidth: channelColumnWidth, channel: true)
                : BubbleMediaLayout.maxWidth(containerWidth: mediaColumnWidth, channel: false)
    }

    /// Media bubble with optional caption.
    ///
    /// __BUBBLE_MEDIA_2026_11_21__ "There is a part that is empty on the side"
    /// (channel/group photos). The picture used to be `.scaledToFit()` inside
    /// `.frame(maxWidth: 260, maxHeight: 300)` while the caption wrapped at the
    /// bubble cap, so a portrait (≈187 pt) sat in a bubble as wide as its
    /// caption (≈281 pt) with bands of bubble fill beside it. Now the picture's
    /// display size comes from `BubbleMediaLayout` (read from the image HEADER,
    /// so the placeholder has the final size), the bubble is EXACTLY that wide,
    /// and the caption wraps inside it.
    private func mediaWithCaptionBubble(message: GroupMessage, isMine: Bool, caption: String?) -> some View {
        // __CHAT_OPEN_PERF_2026_11_14__ see `mediaBubble`: the bytes load off
        // the body pass; the bubble itself is drawn by `mediaWithCaptionContent`
        // (kept out of the reader's closure so the type-checker stays fast).
        GroupMediaDataReader(message: message, refresh: messageListVersion) { mediaData, isLoading in
            mediaWithCaptionContent(message: message, isMine: isMine, caption: caption,
                                    mediaData: mediaData, isLoading: isLoading)
        }
    }

    private func mediaWithCaptionContent(message: GroupMessage, isMine: Bool, caption: String?,
                                         mediaData: Data?, isLoading: Bool) -> some View {
        let isBot = message.senderPublicKey.hasPrefix("bot:")
        let card = isChannelCard(message)
        // A channel post is a card on the incoming surface, whoever wrote it.
        let mineStyle = isMine && !card
        let embedsComments = card && cardEmbedsComments(message)
        let maxW = mediaMaxWidth(channel: card)
        let isVisual = (message.mediaType == .photo || message.mediaType == .video) && mediaData != nil
        let hasCaption = !(caption ?? "").isEmpty
        let natural: CGSize? = {
            guard let data = mediaData else { return nil }
            if message.mediaType == .photo { return BubbleMediaLayout.pixelSize(of: data) }
            return CGSize(width: 16, height: 9)
        }()
        let layout = BubbleMediaLayout.displaySize(natural: natural ?? .zero, maxWidth: maxW)
        let ink: Color = mineStyle ? BubbleStyleManager.sentInk : .primary
        let secondaryInk: Color = mineStyle ? BubbleStyleManager.sentSecondaryInk : ChatPalette.metaInk
        let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
        let time = chatFormatTimestamp(message.timestamp)
        let status: DeliveryStatus? = isMine ? message.deliveryStatus : nil
        let showsName = !isMine
        return HStack {
            if isMine && !card { Spacer() }
            VStack(alignment: (isMine && !card) ? .trailing : .leading, spacing: 4) {
                VStack(alignment: .leading, spacing: 0) {
                    // Sender name INSIDE the bubble, above the media (spec §1).
                    if showsName {
                        HStack(spacing: 4) {
                            if isBot {
                                Image(systemName: "cpu").font(.caption2).foregroundColor(.blue)
                                Text(botName(from: message)).font(.caption.weight(.semibold)).foregroundColor(.blue); BotBadge()
                            } else {
                                Text(senderDisplayName(message.senderPublicKey))
                                    .font(.caption.weight(.semibold))
                                    .foregroundColor(AvatarIdentity.nameColor(for: message.senderPublicKey))
                            }
                        }
                        .lineLimit(1)
                        .padding(.horizontal, 12)
                        .padding(.top, 8)
                        .padding(.bottom, 6)
                    }
                    // Media content
                    if isLoading, let side = Self.mediaLoadingBox(for: message.mediaType) {
                        Self.mediaLoadingPlaceholder(side)
                    } else if message.mediaType == .photo, let data = mediaData {
                        LazyGroupPhotoView(id: message.id.uuidString, data: data, maxDimension: 1200, size: layout.size) {
                            fullScreenImageItem = IdentifiableImageData(data: data, isMine: isMine)
                        }
                        .overlay(alignment: .bottomTrailing) {
                            if !hasCaption { mediaTimePill(message, time: time, status: status) }
                        }
                    } else if message.mediaType == .video, let data = mediaData {
                        LazyGroupVideoThumbnailView(id: message.id.uuidString, data: data, fileName: message.mediaFileName, width: layout.size.width)
                            .overlay(alignment: .bottomTrailing) {
                                if !hasCaption { mediaTimePill(message, time: time, status: status) }
                            }
                    } else if message.mediaType == .document {
                        Button {
                            // 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ others' files stay in OSHI.
                            if AdvancedChatPrivacyPolicy.allowsSavingMedia(protected: isAdvancedPrivacyOn, isFromMe: isMine),
                               let data = mediaData {
                                let fileName = message.mediaFileName ?? "document"
                                // __PLAINTEXT_LEFTOVERS_2026_09_24__ dossier de partage, protégé.
                                documentShareURL = try? MediaPlaintextTemp.writeForShare(data, fileName: fileName)
                                showDocumentShare = true
                            }
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "doc.fill")
                                    .font(.scaled(32))
                                    .foregroundColor(mineStyle ? BubbleStyleManager.sentAccentInk : groupBrandColor)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(message.mediaFileName ?? "Document")
                                        .font(.subheadline).fontWeight(.medium)
                                        .foregroundColor(ink).lineLimit(2)
                                    if let data = mediaData {
                                        Text(ByteCountFormatter.string(fromByteCount: Int64(data.count), countStyle: .file))
                                            .font(.caption).foregroundColor(secondaryInk)
                                    }
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption).foregroundColor(secondaryInk)
                            }
                            .padding()
                        }
                        .buttonStyle(PlainButtonStyle())
                    } else if message.mediaType == .contact {
                        groupContactCard(message: message, data: mediaData)
                    } else {
                        HStack(spacing: 8) {
                            Image(systemName: mediaIcon(for: message.mediaType))
                                .font(.title3).foregroundColor(mineStyle ? BubbleStyleManager.sentAccentInk : groupBrandColor)
                            Text(mediaLabel(for: message.mediaType, fileName: message.mediaFileName))
                                .font(.subheadline).foregroundColor(secondaryInk)
                        }
                        .padding(12)
                    }
                    // Caption under the media, inside the bubble, with the time
                    // on its last line's trailing side.
                    if let captionText = caption, !captionText.isEmpty {
                        // __LONG_MESSAGE_RERENDER_2026_11_09__ see
                        // ClickableTextView.swift.
                        ClickableTextView(
                            text: captionText,
                            textColor: ink,
                            linkColor: mineStyle ? BubbleStyleManager.sentAccentInk : BubbleStyleManager.shared.linkColour(outgoing: false, threadScheme)
                        )
                            .equatable()
                            .lineLimit(nil)
                            .multilineTextAlignment(.leading)
                            .font(.subheadline)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 12)
                            .padding(.top, 8)
                    }
                    if hasCaption || !isVisual {
                        HStack(spacing: 4) {
                            Spacer(minLength: 0)
                            Text(time).font(.caption2).foregroundColor(secondaryInk)
                            groupStatus(message, status, ink: secondaryInk)
                        }
                        .padding(.horizontal, 12)
                        .padding(.top, 2)
                        .padding(.bottom, 7)
                    }
                    if embedsComments {
                        Rectangle()
                            .fill(Color.primary.opacity(0.10))
                            .frame(height: 0.5)
                        GroupCommentsBar(groupId: message.groupId, postId: message.id,
                                         isMine: false, maxWidth: .infinity, attached: true)
                    }
                }
                // THE width: the picture's. Non-visual media keep hugging.
                .frame(width: isVisual ? layout.size.width : nil)
                .background(mineStyle ? BubbleStyleManager.sentBubbleColour(nil)
                                      : BubbleStyleManager.receivedBubbleColour)
                .clipShape(shape)
                .overlay(shape.stroke(mineStyle ? Color(ChatPalette.outgoingRimUI)
                                                : BubbleStyleManager.shared.rim(outgoing: false, threadScheme),
                                      lineWidth: 0.5))
                .modifier(OLEDGlassIncomingEdge(isMine: mineStyle, radius: 18))
                .elevation(Theme.Elevation.low)
                .frame(maxWidth: isVisual ? nil : (card ? maxW : BubbleStyleManager.maxBubbleWidth(in: chatColumnWidth)),
                       alignment: (isMine && !card) ? .trailing : .leading)
                // __BOT_INLINE_KEYBOARD_2026_09_16__ A bot that sends a picture
                // with buttons is the common shape (a poster plus "Details"), so
                // the media bubble carries the keyboard too.
                botInlineKeyboard(for: message)
                MessageReactionsView(messageId: message.id.uuidString, isFromCurrentUser: isMine && !card)
            }
            if !isMine || card { Spacer(minLength: 0) }
        }
        .padding(.vertical, 8)
        .padding(.horizontal, card ? 0 : 8)
    }

    /// Group tick inside a bubble: groups have no per-member read receipts, so
    /// it stops at one check (see GroupMessageBubble's metadata row).
    @ViewBuilder
    private func groupTick(_ status: DeliveryStatus, ink: Color) -> some View {
        switch status {
        case .pending:
            Image(systemName: "clock").font(.scaled(9)).foregroundColor(ink)
        case .failed:
            Image(systemName: "exclamationmark.circle.fill").font(.scaled(10)).foregroundColor(.red)
        default:
            Image(systemName: "checkmark").font(.scaled(9, weight: .semibold)).foregroundColor(ink)
        }
    }

    /// __GROUP_RESEND_2026_11_25__ An own message some members have not received yet: a clock
    /// and "delivered/total" where the tick goes (never the Sent tick while members are pending).
    /// Tap → who is missing + "Retry now". Nothing at all once everybody has it.
    @ViewBuilder
    private func partialDeliveryBadge(_ message: GroupMessage, ink: Color) -> some View {
        if isFromCurrentUser(message), let e = pendingDeliveries.entry(for: message.id) {
            Button { partialDeliveryShown = e } label: {
                HStack(spacing: 2) {
                    Image(systemName: e.gaveUp ? "exclamationmark.circle" : "clock").font(.scaled(9))
                    Text(GroupPartialDelivery.compact(e)).font(.caption2).monospacedDigit()
                }
                .foregroundColor(e.gaveUp ? .red : ink)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(GroupPartialDelivery.title(e))
        }
    }

    /// The tick, or the partial-delivery badge while members are pending.
    @ViewBuilder
    private func groupStatus(_ message: GroupMessage, _ status: DeliveryStatus?, ink: Color) -> some View {
        if isFromCurrentUser(message), pendingDeliveries.entry(for: message.id) != nil {
            partialDeliveryBadge(message, ink: ink)
        } else if let status { groupTick(status, ink: ink) }
    }

    /// The time pill over a photo/video; the partial-delivery badge replaces its tick.
    @ViewBuilder
    private func mediaTimePill(_ message: GroupMessage, time: String, status: DeliveryStatus?) -> some View {
        if isFromCurrentUser(message), pendingDeliveries.entry(for: message.id) != nil {
            HStack(spacing: 3) {
                Text(time).font(.caption2.weight(.medium)).monospacedDigit().foregroundColor(.white)
                partialDeliveryBadge(message, ink: .white)
            }
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Capsule().fill(Color.black.opacity(MediaTimePill.backdropOpacity)))
            .padding(6)
        } else {
            MediaTimePill(time: time, status: status)
        }
    }

    /// The details behind the badge: how many got it, who has not, and "Retry now".
    private var partialDeliveryAlertMessage: String {
        guard let e = partialDeliveryShown else { return "" }
        let names = (pendingDeliveries.entries[e.msgId] ?? e).pending.map { senderDisplayName($0) }
        return GroupPartialDelivery.body(e) + "\n\n" + "group.receipts.pending".localized + ":\n" + names.joined(separator: "\n")
    }

    /// Audio playback bubble with play/pause, waveform, and speaker/earpiece routing
    private func audioPlaybackBubble(message: GroupMessage, isMine: Bool) -> some View {
        let isBot = message.senderPublicKey.hasPrefix("bot:")
        return HStack {
            if isMine { Spacer() }
            VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
                GroupAudioPlayerView(message: message, isMine: isMine, useExternalSpeaker: $useExternalSpeaker, groupBrandColor: groupBrandColor, groupManager: groupManager)
                HStack(spacing: 5) {
                    if !isMine {
                        if isBot {
                            Image(systemName: "cpu").font(.caption2).foregroundColor(.blue)
                            Text(botName(from: message)).font(.caption2).fontWeight(.medium).foregroundColor(.blue)
                            BotBadge()
                        } else {
                            Text(senderDisplayName(message.senderPublicKey)).font(.caption2)
                                .foregroundColor(AvatarIdentity.nameColor(for: message.senderPublicKey))
                        }
                        Text("\u{2022}").font(.caption2).foregroundColor(.secondary)
                    }
                    Text(chatFormatTimestamp(message.timestamp)).font(.caption2).foregroundColor(.secondary)
                    partialDeliveryBadge(message, ink: .secondary)   // __GROUP_RESEND_2026_11_25__
                }
                MessageReactionsView(messageId: message.id.uuidString, isFromCurrentUser: isMine)
            }
            if !isMine { Spacer() }
        }
        .padding(8)
    }

    // ✅ FIX #2: Video thumbnail now uses LazyGroupVideoThumbnailView, which runs
    // file write + AVAssetImageGenerator off-body via .task { }. The old synchronous
    // generateVideoThumbnail(url:) helper is removed — no remaining callers inside
    // the view body.
    private func videoThumbnail(data: Data, message: GroupMessage) -> some View {
        LazyGroupVideoThumbnailView(id: message.id.uuidString, data: data, fileName: message.mediaFileName)
    }

    private func mediaIcon(for type: MediaType?) -> String {
        switch type {
        case .photo: return "photo"
        case .video: return "video"
        case .audio: return "waveform"
        case .document: return "doc"
        case .contact: return "person.crop.circle"
        case nil: return "questionmark"
        }
    }

    private func mediaLabel(for type: MediaType?, fileName: String?) -> String {
        switch type {
        case .photo: return "Photo"
        case .video: return "Video"
        case .audio: return "Audio"
        case .document: return fileName ?? "Document"
        case .contact: return "Contact"
        case nil: return "Media"
        }
    }

    // (`loadGroupMediaData` moved off the main thread: `GroupMediaStore.resolve`.)

    private func chatFormatAddress(_ address: String) -> String {
        guard address.count > 10 else { return address }
        return "\(address.prefix(6))..."
    }

    /// Resolve a group message sender to a human name: group-member alias first,
    /// then the saved contact alias, then a shortened address as a last resort.
    ///
    /// 🔴 PERF (this was the group "new message" stutter): the contact-alias
    /// fallback used to be a `UserDefaults.data(forKey:)` **plus a full
    /// `JSONDecoder().decode([String: String].self, …)`** — and this function is
    /// called from inside `body`, on the label under every incoming bubble
    /// (text at ~2512, media at ~2980, audio at ~3006, plus every reply quote).
    /// So a group with 20 visible rows from people who have no *group* alias set
    /// ran 20 JSON decodes on the main thread, per re-render — and the group
    /// chat re-renders on every arriving message, typing tick and receipt.
    /// The decode is now memoised in `GroupContactAliases`.
    private func senderDisplayName(_ key: String) -> String {
        let g = groupManager.groups.first(where: { $0.id == group.id }) ?? group
        // __ROSTER_COMPARED_RAW_KEYS_2026_11_07__ Canonical, not raw.
        //
        // This was `$0.publicKey == key`. iOS carries each member's alias on the
        // group itself, so unlike Android it HAS the name — but it only finds it
        // when the two spellings of the key match character for character. A
        // roster entry stored base64url against a sender key in base64 missed,
        // fell through both fallbacks, and rendered the formatted ADDRESS.
        //
        // Which is Anatole's N2 ("it shows their key") arriving on iOS by a
        // different route than the one fixed on Android. Found by checking his
        // points on the other platforms rather than only the one he reported on.
        let wanted = MessageManager.canonicalIdentity(key)
        if let alias = g.members.first(where: {
            MessageManager.canonicalIdentity($0.publicKey) == wanted
        })?.alias, !alias.isEmpty { return alias }
        // __SHARED_NICKNAME_2026_09_22__ alias local, sinon pseudo partage.
        if let name = GroupContactAliases.displayName(key) { return name }
        return chatFormatAddress(key)
    }

    /// 🔧 PERF: called from the LazyVStack for every day boundary in the window.
    /// See `OshiRowFormatters` (ChatView.swift) for why a per-call
    /// `DateFormatter()` was costing frames.
    ///
    /// 🔴 The two early returns used to be hard-coded English `"Today"` /
    /// `"Yesterday"`, so a French user reading a group saw an English day header
    /// above today's messages while the 1:1 thread two taps away said
    /// "Aujourd'hui". Fixed by reusing the keys the 1:1 thread already uses
    /// (`ChatView.computeMessagesByDate`) — `messages.today` and
    /// `messages.yesterday` are present in **all 34** `.lproj` catalogues
    /// (verified by grep), so this needs no new strings and no catalogue edit.
    ///
    /// 🔑 The `.localized` has to happen HERE, not at the call site: the caller
    /// renders this through `Text(dateLabel)`, and `Text(String)` does not
    /// localise. Nothing compares against the returned value — it is display
    /// only — so changing it cannot break the day-boundary logic, which works
    /// off `startOfDay` comparisons in the `ForEach` above.
    private func groupDateLabel(for date: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            return "messages.today".localized
        } else if calendar.isDateInYesterday(date) {
            return "messages.yesterday".localized
        } else {
            return OshiRowFormatters.string(date, format: "EEEE, MMMM d, yyyy")
        }
    }

    /// 🔧 PERF: drawn by all four group bubble variants (text, media, audio,
    /// location) — so once per visible bubble per re-render, and the group chat
    /// re-renders on every `MessageGroupManager` publish. See `OshiRowFormatters`.
    ///
    /// __BUBBLE_TIME_2026_08_17__ L'HEURE, toujours — comme en 1:1.
    ///
    /// 🔴 Ceci renvoyait « Hier » pour un message d'hier et « 12 août » pour un
    /// plus ancien: passé minuit, l'heure d'envoi d'un message de groupe
    /// devenait INTROUVABLE, alors que le fil 1:1 deux taps plus loin la donne
    /// (`ChatView.MessageBubble.formatTimestamp`, corrigé le 2026-08-17 — la
    /// bulle de groupe a été manquée). Or la date est DÉJÀ l'en-tête de jour
    /// juste au-dessus (`groupDateLabel`, posé par le `ForEach` sur
    /// `displayMessages` dès que `startOfDay` change): l'étiquette de la bulle
    /// répétait donc l'information qu'on avait et supprimait celle qu'on
    /// n'avait pas.
    ///
    /// 🔑 Affichage uniquement — rien ne compare cette chaîne, le groupement par
    /// jour travaille sur `startOfDay`, pas sur ce libellé. La clé
    /// `date_yesterday` reste utilisée par les LISTES (`ModernGroupRowView`,
    /// `ConversationRow`), où elle est juste: là il n'y a pas d'en-tête de jour.
    private func chatFormatTimestamp(_ date: Date) -> String {
        OshiRowFormatters.string(date, format: "HH:mm")
    }

    /// Whether there are older messages that can be loaded
    private var hasMoreMessages: Bool {
        let allMessages = groupManager.groupMessages[group.id] ?? []
        // __FILTERED_RESULTS_WINDOW_2026_11_16__ Searching or filtering by date
        // pages through the MATCHES, `messageDisplayLimit` at a time.
        if !searchText.isEmpty || selectedSearchDate != nil || dateRangeStart != nil {
            return filteredMatchCount > messageDisplayLimit
        }
        return allMessages.count > messageDisplayLimit
    }

    /// __GROUP_LIST_RENDER_LOOP_2026_08_24__ Runs the search / date pipeline ONCE
    /// per `loadMessages()` tick.
    ///
    /// This used to be a computed property named `filteredMessages`, and
    /// `displayMessages` — its only consumer — is read once per rendered ROW
    /// (`displayMessages[index - 1]` in the ForEach body) plus three more times
    /// per body pass. So with a search or date filter active, every single row
    /// re-sorted and re-filtered the WHOLE history, running a locale-aware
    /// `localizedCaseInsensitiveContains` over every message.
    ///
    /// That is not a hypothesis: the watchdog kill of 2026-08-21 (build 95)
    /// caught the exact chain — ForEachChild.updateValue → displayMessages.getter
    /// → filteredMessages.getter → filter → CFStringFindWithOptionsAndLocale —
    /// after burning 10.492 s of CPU out of a 10 s scene-update allowance.
    /// Twelve more watchdog kills share the signature (scene-update, app in the
    /// BACKGROUND, CPU time ≈ wall time: it spins, it does not wait).
    ///
    /// Measured on this device's real store (7 763 group messages, 5 768 in one
    /// group): one evaluation = 45.9 ms on an M-series Mac, ×50 rows = 2.3 s,
    /// and the phone is 2–4× slower.
    ///
    /// `ChatView` — the 1:1 sibling — received exactly this fix in 1.0.15
    /// (`cachedFilteredMessages`, see ChatView.swift:156, whose comment names the
    /// same shape: "re-evaluated on every body pass AND once per row"). The group
    /// side never got it.
    ///
    /// - Parameter sortedAscending: the full history, already sorted oldest→newest.
    ///   Passed in rather than re-sorted here — `loadMessages()` has just produced
    ///   exactly that array on the filtered path.
    /// __GROUP_LIST_RENDER_LOOP_2026_08_24__ One value that changes whenever any
    /// filter input does, so a SINGLE `.onChange` can invalidate
    /// `displayMessagesCache`.
    ///
    /// Four separate `.onChange` modifiers is the obvious way to write this and
    /// it does not compile: this view's modifier chain is already so long that
    /// adding four more made the type-checker give up
    /// ("unable to type-check this expression in reasonable time"). That is the
    /// same over-large view type that puts `_swift_getGenericMetadata` at the top
    /// of two of the watchdog stacks — worth remembering before adding another
    /// modifier here.
    private var filterSignature: String {
        let d = { (date: Date?) in date.map { String($0.timeIntervalSince1970) } ?? "-" }
        return "\(searchText)|\(d(selectedSearchDate))|\(d(dateRangeStart))|\(d(dateRangeEnd))"
    }

    private func computeFilteredMessages(from sortedAscending: [GroupMessage]) -> [GroupMessage] {
        var messages: [GroupMessage] = sortedAscending

        // ✅ Filter by date if selected
        let calendar = Calendar.current

        if let singleDate = selectedSearchDate {
            messages = messages.filter { message in
                calendar.isDate(message.timestamp, inSameDayAs: singleDate)
            }
        } else if let start = dateRangeStart, let end = dateRangeEnd {
            messages = messages.filter { message in
                let startOfDay = calendar.startOfDay(for: start)
                let endOfDay = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: end)) ?? end
                return message.timestamp >= startOfDay && message.timestamp < endOfDay
            }
        }

        // ✅ Filter by search text
        if !searchText.isEmpty {
            messages = messages.filter { message in
                // Search in plaintextContent first (sent messages store text here)
                if let plaintext = message.plaintextContent, !plaintext.isEmpty {
                    if plaintext.localizedCaseInsensitiveContains(searchText) {
                        return true
                    }
                }

                // Search in decrypted content (received messages)
                if let decrypted = groupManager.decryptMessage(message) {
                    if decrypted.localizedCaseInsensitiveContains(searchText) {
                        return true
                    }
                }

                // Search in sender alias
                if let group = groupManager.groups.first(where: { $0.id == message.groupId }),
                   let member = group.members.first(where: { $0.publicKey == message.senderPublicKey }),
                   let alias = member.alias,
                   alias.localizedCaseInsensitiveContains(searchText) {
                    return true
                }

                return false
            }
        }

        return messages
    }
    
    // MARK: - Group Toolbar Content (extracted for type-checker)
    @ToolbarContentBuilder
    private var groupToolbarContent: some ToolbarContent {
        // __GROUP_HEADER_ISLAND_2026_11_24__ (system back) (search)  ( name / count )  (avatar).
        // See GroupChatHeader.swift. The ⋯ menu's items live in the group info sheet
        // and behind a long-press on the avatar.
        ToolbarItem(placement: .principal) {
            // Prefer the live typing summary when someone is typing; otherwise the count.
            let typingLine = typingManager.typingSummary(forGroup: group.id.uuidString, excludingPublicKey: identityManager.publicKey)
            GroupHeaderIsland(
                name: liveGroup.name,
                subtitle: typingLine.isEmpty
                    ? GroupHeaderText.count(liveGroup.members.count, channel: liveGroup.type == .adminOnly)
                    : typingLine,
                subtitleIsActivity: !typingLine.isEmpty,
                muted: groupManager.isGroupMuted(group.id),
                activityColor: groupBrandColor,
                onTap: { showGroupInfo = true })
        }
        ToolbarItem(placement: .navigationBarLeading) {
            Button {
                withAnimation { showSearch.toggle(); if !showSearch { searchText = "" } }
            } label: {
                Image(systemName: showSearch ? "xmark" : "magnifyingglass")
            }
            .accessibilityLabel(GroupHeaderText.searchLabel)
            .accessibilityIdentifier("group_header_search")
        }
        ToolbarItem(placement: .navigationBarTrailing) {
            // __MENU_SCROLL_RESET_2026_11_21__ The long-press menu is the same value-typed
            // `GroupOverflowMenu.sections` the ⋯ button used, rebuilt only when they change.
            let menu = GroupOverflowMenu(
                state: GroupOverflowMenu.MenuState(
                    canAddMembers: canAddMembers,
                    muted: groupManager.isGroupMuted(group.id),
                    blocked: isGroupBlocked,
                    admin: isAdmin,
                    advancedPrivacy: isAdvancedPrivacyOn),
                actions: groupOverflowActions)
            GroupHeaderAvatarButton(
                name: liveGroup.name,
                sections: menu.sections,
                onTap: { showGroupInfo = true },
                onSelect: menu.select) {
                groupHeaderAvatar
            }
        }
    }

    /// What the old ⋯ menu did, shared by the avatar's long-press menu.
    private var groupOverflowActions: GroupOverflowMenu.Actions {
        GroupOverflowMenu.Actions(
            openInfo: { showGroupInfo = true },
            addMember: { showAddMember = true },
            openWallpaper: { showWallpaperPicker = true },
            toggleMute: { groupManager.toggleMuteGroup(group.id) },
            toggleAdvancedPrivacy: {
                groupManager.setAdvancedChatPrivacy(!isAdvancedPrivacyOn, groupId: group.id,
                                                    senderPublicKey: identityManager.publicKey)
            },
            report: { showingReportSheet = true },
            unblock: { showingUnblockAlert = true },
            block: { showingBlockSheet = true },
            leave: { showLeaveGroupAlert = true })
    }

    /// The same actions for the info sheet: it is closed first, and what it opens
    /// is presented once it has gone (two sheets cannot be up at once).
    private var groupInfoChatActions: GroupInfoChatActions {
        let after: (@escaping () -> Void) -> () -> Void = { open in
            {
                showGroupInfo = false
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { open() }
            }
        }
        let a = groupOverflowActions
        return GroupInfoChatActions(
            canAddMembers: canAddMembers,
            blocked: isGroupBlocked,
            addMember: after(a.addMember),
            openWallpaper: after(a.openWallpaper),
            toggleMute: a.toggleMute,
            report: after(a.report),
            block: after(a.block),
            unblock: after(a.unblock))
    }

    /// The group's picture, emoji or initial — the header's right-hand button.
    @ViewBuilder
    private var groupHeaderAvatar: some View {
        // 🔴 PERF: never `UIImage(data:)` here — the toolbar is re-evaluated on
        // every typing tick and message. See `GroupPictureCache`.
        if let image = GroupPictureCache.image(for: liveGroup.id,
                                               data: liveGroup.groupPictureData) {
            Image(uiImage: image).resizable().aspectRatio(contentMode: .fill)
        } else if let avatar = liveGroup.avatar, !avatar.isEmpty {
            // __AVATAR_SAME_COLOUR_EVERYWHERE_2026_11_09__ the group's own gradient at 0.2.
            Text(avatar).font(.scaled(20))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Circle().fill(LinearGradient(
                    colors: AvatarIdentity.gradient(for: liveGroup.id.uuidString).map { $0.opacity(0.2) },
                    startPoint: .topLeading, endPoint: .bottomTrailing)))
        } else {
            // __ONE_AVATAR_NOT_THREE_2026_11_07__ keyed on the GROUP id.
            DefaultAvatar(key: liveGroup.id.uuidString, name: liveGroup.name,
                          size: 38, initialCount: 1)
        }
    }

    // MARK: - Display messages (derived from @State displayMessagesCache for reliable SwiftUI updates)
    //
    // 🔧 PERF FIX (2026-06-03): for the no-filter case, return the cached,
    // already-sorted+merged window populated by `loadMessages()`. The previous
    // implementation re-sorted the FULL groupManager.groupMessages array on
    // every body re-render — fine at 50 messages, but on groups with hundreds/
    // thousands of historical messages it was O(n log n) every single render
    // (typing-indicator ticks, presence updates, every keystroke). Opening a
    // long-history group was visibly laggy because of this.
    //
    // Reactivity: `displayMessagesCache` is @State; SwiftUI re-renders the
    // body whenever it changes. `loadMessages()` updates the cache and is
    // called from BOTH `.onChange(of: count)` (new messages) AND a new
    // `.onReceive(groupManager.$groupMessages)` (catches in-place edits like
    // delivery-status changes, which don't change count). The earlier comment
    // claiming "must read live for SwiftUI to track" was wrong — @State reads
    // are tracked just fine; the original bug was a missing observer.
    //
    // Pagination still uses messageDisplayLimit; the cached `loadedMessages`
    // path remains for the search/date-filter branch (uses filteredMessages).
    // __GROUP_LIST_RENDER_LOOP_2026_08_24__ Now O(1) on BOTH paths.
    //
    // The filtered branch used to run `mergeBotMessagePairs(filteredMessages)`
    // right here, and this getter is read once per rendered ROW plus three more
    // times per body pass. `loadMessages()` now does that work once per tick and
    // parks the result in `displayMessagesCache`, so a read is an array
    // reference and nothing else.
    private var displayMessages: [GroupMessage] {
        displayMessagesCache
    }

    // MARK: - Messages ZStack (extracted for type-checker)
    private var messagesZStack: some View {
        ZStack {
            // 🔧 2026-06-10: opaque fallback background — when ChatWallpaperView
            // renders the .none case (no custom wallpaper) it returns
            // .systemBackground which is pure black in dark mode. During
            // SwiftUI's initial layout pass + the brief window before
            // messages render, the user saw a fully black screen ("all
            // black, scroll up to see messages"). A neutral grey under the
            // wallpaper ensures the chat never looks like a frozen view.
            //
            // __CHAT_CHROME_BLUR_2026_11_21__ That grey and the wallpaper moved
            // to `mainContentBody`'s background: drawn here they stopped at the
            // composer and at the banners, so the backdrop never reached the
            // bottom of the screen in a group.

            ScrollViewReader { proxy in
                ScrollView {
                    ChatMessageStack(spacing: 8, scrollTargets: true) {
                        // Load earlier messages button (pagination)
                        if hasMoreMessages {
                            Button {
                                messageDisplayLimit += messagePageSize
                                loadMessages()
                            } label: {
                                HStack(spacing: 6) {
                                    Image(systemName: "arrow.up.circle")
                                        .font(.caption)
                                    Text("load_earlier_messages".localized)
                                        .font(.caption)
                                }
                                .foregroundColor(groupBrandColor)
                                .padding(.vertical, 8)
                                .frame(maxWidth: .infinity)
                            }
                            .id("loadMoreButton")
                        }

                        // 🔧 2026-06-11 (b57): STABLE row identity — id by message.id,
                        // NOT by index. displayMessages is a sliding window of the
                        // newest 50: with `id: \.self` on indices, every appended
                        // catch-up message shifted ALL row contents under the same
                        // identities, so the LazyVStack relaid out the whole list
                        // under the viewport while opening — the user landed in
                        // blank space far from the newest message. zip is cheap
                        // here (window ≤ 50 rows after pagination) and keeps the
                        // index available for the date-header lookback.
                        ForEach(Array(zip(displayMessages.indices, displayMessages)), id: \.1.id) { index, message in
                            // Inline date header when day changes
                            let showDateHeader: Bool = {
                                if index == 0 { return true }
                                let prevDate = Calendar.current.startOfDay(for: displayMessages[index - 1].timestamp)
                                let thisDate = Calendar.current.startOfDay(for: message.timestamp)
                                return prevDate != thisDate
                            }()

                            if showDateHeader {
                                let dateLabel = groupDateLabel(for: message.timestamp)
                                Text(dateLabel)
                                    .font(.caption)
                                    .fontWeight(.semibold)
                                    .foregroundColor(.secondary)
                                    .padding(.vertical, 6)
                                    .padding(.horizontal, 12)
                                    .background(Capsule().fill(Color(.systemGray6)))
                                    .padding(.vertical, 4)
                                    .frame(maxWidth: .infinity)
                                    .background(
                                        GeometryReader { geo in
                                            Color.clear
                                                .onChange(of: geo.frame(in: .named("groupScroll")).minY) { _, minY in
                                                    if minY < 80 && minY > -50 {
                                                        if floatingDateHeader != dateLabel {
                                                            floatingDateHeader = dateLabel
                                                        }
                                                        floatingDateHider.restart {
                                                            withAnimation(.easeOut(duration: 0.3)) {
                                                                floatingDateHeader = nil
                                                            }
                                                        }
                                                    }
                                                }
                                        }
                                    )
                            }

                            // __UNREAD_HAD_NO_LINE_2026_11_07__ The boundary the
                            // open scroll lands on, said out loud. Drawn from the
                            // id captured in `onAppear`, NOT from `isRead`:
                            // `markGroupAsRead` flips every flag a beat later, so
                            // a live `!isRead` test would erase the line before
                            // the reader could see it — the same reason the
                            // anchor itself is captured rather than recomputed.
                            if let anchor = openUnreadAnchorId,
                               anchor == message.id.uuidString {
                                HStack(spacing: 8) {
                                    Rectangle()
                                        .fill(groupBrandColor.opacity(0.35))
                                        .frame(height: 1)
                                    Text("chat_unread_divider".localized)
                                        .font(.caption2)
                                        .fontWeight(.semibold)
                                        .foregroundColor(groupBrandColor)
                                        .fixedSize()
                                    Rectangle()
                                        .fill(groupBrandColor.opacity(0.35))
                                        .frame(height: 1)
                                }
                                .padding(.horizontal, 12)
                                .padding(.vertical, 2)
                                .id(Self.unreadDividerId)
                            }

                            cleanBubble(for: message)
                                .id(message.id)
                                // 🔧 FIX (2026-08-08): groups had NO message
                                // actions at all — no reply, forward, react or
                                // pin. Not because they were unwritten:
                                // `GroupMessageBubble` (line ~4790) contains all
                                // of them and is instantiated NOWHERE. The list
                                // renders `cleanBubble`, which had no gestures.
                                // Rather than revive 1100 lines of parallel
                                // bubble code, the actions are attached here, on
                                // the path that actually draws.
                                .modifier(GroupMessageActions(
                                    message: message,
                                    isMine: isFromCurrentUser(message),
                                    onReply: { beginReply(to: message) },
                                    onLongPress: { frame in
                                        actionMessage = message
                                        actionFrame = frame
                                        showMessageActions = true
                                    },
                                    onDoubleTap: { reactToGroupMessage(message, emoji: .oshiSmile) },
                                    onReact: { reactToGroupMessage(message, emoji: $0) },
                                    onWebLink: webLinkAction(for: message)
                                ))
                            // __GROUP_INFO_SETTINGS_2026_11_20__ §2.10 auto-translate (local pref).
                            if !message.isSystemMessage, message.mediaType == nil,
                               !isFromCurrentUser(message),
                               // 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ auto-translate runs the
                               // on-device model over every message: off in a protected group.
                               AdvancedChatPrivacyPolicy.allowsAI(protected: isAdvancedPrivacyOn),
                               GroupLocalFlags.shared.autoTranslate(message.groupId),
                               let t = resolveText(for: message), !t.isEmpty,
                               !t.hasPrefix("📍"), !t.hasPrefix("📅") {
                                GroupAutoTranslatedLine(messageId: message.id,
                                                        text: OSHIControlPayload.previewText(for: t))
                            }
                            // __GROUP_COMMENTS_2026_11_21__ §2.12.6 "💬 N comments" under
                            // every post of an Active channel (admin-authored, not a comment).
                            if GroupCommentPolicy.isPost(message, in: liveGroup),
                               !cardEmbedsComments(message) {
                                GroupCommentsBar(groupId: message.groupId, postId: message.id,
                                                 isMine: isFromCurrentUser(message),
                                                 maxWidth: groupTextBubbleMaxWidth)
                            }
                                // 🔧 (2026-08-21) Les deux callbacks qui pilotaient
                                // `showScrollToBottom` d'ici ont été retirés:
                                // `displayMessages.last?.id` CHANGE à chaque
                                // message reçu, donc la nouvelle dernière ligne
                                // n'a jamais été à l'écran et ne peut ni
                                // apparaître ni disparaître. Le drapeau gelait.
                                // L'ancre du bas, qui garde la même identité,
                                // s'en charge — un seul écrivain.
                        }
                        // __BOTTOM_LANDING_2026_09_12__ 8 pt, pas 1 pt — voir
                        // ChatView: un `LazyVStack` ne matérialise que ce qui
                        // approche du viewport, et tout le « suis-je en bas »
                        // dépend des callbacks de cette vue.
                        Color.clear.frame(height: 8).id("groupBottomAnchor")
                            // 🔧 2026-06-11 (b57): ground truth for "did we actually
                            // land at the bottom?" — drives the verified retry loop
                            // in onAppear instead of blind timed scrollTo shots.
                            // __GROUP_OPEN_ANCHOR_2026_09_23__ iOS 17 only: on 18+ the
                            // stack is eager, so these fire at build time rather than
                            // on visibility; `ChatBottomFollower` reports instead.
                            .onAppear {
                                if #available(iOS 18.0, *) { return }
                                bottomAnchorSighted = true
                                // __GROUP_OPEN_UNREAD_2026_10_09__ Ne PAS annuler
                                // la boucle d'atterrissage ici. `landAtBottom`
                                // réinterroge `isAtBottom` toutes les 80 ms et
                                // s'arrête d'elle-même dès que l'ancre est en bas
                                // — `bottomAnchorSighted` est justement son
                                // prédicat. L'annulation dure n'ajoutait donc
                                // rien, SAUF qu'une vue fugace de l'ancre pendant
                                // le calage du LazyVStack tuait la boucle pour de
                                // bon: plus aucune passe ne pouvait corriger la
                                // position, et le groupe restait ouvert au-dessus
                                // du message le plus récent. C'est exactement le
                                // court-circuit que la note du 12/09 décrit.
                                withAnimation { showScrollToBottom = false }
                            }
                            .onDisappear {
                                if #available(iOS 18.0, *) { return }
                                bottomAnchorSighted = false
                                withAnimation { showScrollToBottom = true }
                            }
                    }
                    .padding()
                }
                .modifier(ChatBottomAnchored(pinnedToBottom: bottomAnchorSighted || !userScrolledSinceOpen))
                .modifier(ChatBottomFollower(following: $followBottom, proxy: proxy, anchorID: "groupBottomAnchor",
                                             onBottomChange: { atBottom in
                                                 // iOS 18+: geometry is the single writer of
                                                 // "at the bottom" (the eager stack's anchor
                                                 // onAppear no longer tracks visibility).
                                                 bottomAnchorSighted = atBottom
                                                 ThreadHandoff.shared.setAtBottom(handoffKey, atBottom)
                                                 // __GROUP_OPEN_UNREAD_2026_10_09__ Même
                                                 // raison que l'`onAppear` de l'ancre: le
                                                 // prédicat de `landAtBottom` lit déjà
                                                 // `bottomAnchorSighted`, et sur iOS 18 ce
                                                 // rappel est le SEUL auteur de ce drapeau.
                                                 // Une première mesure « en bas » arrivant
                                                 // avant que les lignes paresseuses ne
                                                 // soient matérialisées annulait la boucle
                                                 // définitivement.
                                                 withAnimation { showScrollToBottom = !atBottom }
                                             }))
                .simultaneousGesture(
                    DragGesture(minimumDistance: 12).onChanged { _ in
                        userScrolledSinceOpen = true
                        scrollTracker.lastUserDragAt = Date()
                    }
                )
                .modifier(ChatScrollGapReader(tracker: scrollTracker))
                // __FOLDABLE_LAYOUT_2026_11_16__ see ChatView.
                .modifier(ThreadReadingPositionReporterUUID(handoffKey: handoffKey))
                // Same view, new width: see ChatView.
                .onChange(of: groupColumnWidth) { old, new in
                    guard old > 0, abs(new - old) > 40,
                          let raw = ThreadHandoff.shared.readingAnchorIfScrolledUp(handoffKey),
                          let anchor = UUID(uuidString: raw) else { return }
                    for delay in [0.05, 0.3] {
                        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                            withAnimation(nil) { proxy.scrollTo(anchor, anchor: .top) }
                        }
                    }
                }
                .scrollDismissesKeyboard(.interactively)
                .background(Color.clear)
                .coordinateSpace(name: "groupScroll")
                .onAppear {
                    scrollProxy = proxy
                    // __FOLDABLE_LAYOUT_2026_11_16__ Re-laid out mid-read: land
                    // where the reader was; skip the bottom / unread landing.
                    attachGroupHandoffIfNeeded()
                    if let anchor = handoffReadingAnchor {
                        openScrollRetryTask?.cancel()
                        userScrolledSinceOpen = true
                        followBottom = false
                        groupHandoffActive = true
                        // Rows not loaded yet: the count handler lands once they are.
                        if !displayMessages.isEmpty {
                            handoffReadingAnchor = nil
                            restoreGroupReadingAnchor(anchor, proxy: proxy)
                        }
                        return
                    }
                    // 🔧 2026-06-11 (b57): REPLACE the blind staggered scrollTo
                    // shots (+0.15/0.5/1.2s) with a VERIFIED landing loop.
                    //
                    // Two problems with the old approach:
                    // 1. On iOS 18+ the role-based defaultScrollAnchor
                    //    (ChatBottomAnchored) already owns the initial bottom
                    //    position — firing scrollTo during the lazy-layout
                    //    settle counts as a programmatic scroll, which
                    //    DISENGAGES the system's size-change pinning. The view
                    //    then drifted away from the bottom as bubble heights
                    //    settled: "opens black / far from the newest message".
                    // 2. The shots were timed blindly; image-heavy layouts can
                    //    settle AFTER the last shot, so the bug survived.
                    //
                    // Now: only intervene while the bottom anchor has provably
                    // NOT been on screen (tracked by its onAppear), checking
                    // every 250 ms for up to 3 s, no animation. The instant the
                    // anchor becomes visible the loop stops forever, so it can
                    // never fight the system anchor nor yank a reading user.
                    // (Le tir immédiat réservé à iOS 17 est retiré: `landAtBottom`
                    // fait lui-même sa première passe, sur toutes les versions.)
                    openScrollRetryTask?.cancel()
                    bottomAnchorSighted = false
                    userScrolledSinceOpen = false
                    followBottom = true
                    // 🔧 SINGLE-TAP / OPEN LANDING FIX: the previous loop stopped
                    // the instant `bottomAnchorSighted` flipped true. On a long
                    // thread the anchor's onAppear (or the iOS 18 system anchor)
                    // can report "sighted" after only a few rows materialize —
                    // BEFORE the view is truly pinned to the newest message — so
                    // the loop self-cancelled too early and the group opened above
                    // the latest message.
                    //
                    // Fix: do NOT let an early sighting short-circuit the FIRST
                    // landing. Keep re-issuing the scroll across the initial
                    // layout settle (every 200 ms for ~1.2 s) so each pass
                    // materializes the rows the previous pass revealed and the
                    // real content extent grows in under us. Like ChatView's
                    // settle, this lands at the genuine bottom in one open.
                    // __BOTTOM_LANDING_2026_09_12__ « ON OUVRE TROP BAS, IL FAUT
                    // REMONTER, ON NE VOIT QUE LE FOND D'ÉCRAN ».
                    //
                    // L'ancienne version sortait immédiatement sur iOS 18+
                    // (`if #available(iOS 18.0, *) { return }`) pour ne pas
                    // désengager `.defaultScrollAnchor(.bottom, for: .sizeChanges)`.
                    // Le raisonnement était juste, la conséquence ne l'était pas:
                    // plus RIEN ne corrigeait la position d'ouverture. Or
                    // `.initialOffset` place la vue d'après les hauteurs ESTIMÉES
                    // du `LazyVStack`; quand l'estimation dépasse la réalité (des
                    // lignes de remplacement plus hautes que le contenu final),
                    // on atterrit APRÈS la fin du contenu — c'est-à-dire dans le
                    // vide, sur le fond d'écran. Et comme l'ancre se retrouve
                    // au-dessus du viewport, son `onAppear` ne part jamais,
                    // `bottomAnchorSighted` reste faux, l'ancrage `.sizeChanges`
                    // n'est jamais armé, et rien ne rattrape quoi que ce soit.
                    //
                    // La boucle adaptative peut tourner sur TOUTES les versions
                    // parce qu'elle s'arrête sur la RÉUSSITE: dès que l'ancre est
                    // vue, elle ne tire plus une seule fois, donc elle ne
                    // désengage plus l'ancrage système d'iOS 18 — ce qui était
                    // tout le problème que la sortie anticipée cherchait à éviter.
                    // __OPEN_AT_THE_FIRST_UNREAD_2026_10_17__ see the property.
                    openUnreadAnchorId = firstUnreadIncomingGroupId()
                    if let unreadId = openUnreadAnchorId {
                        // __GROUP_UNREAD_FOLLOWER_2026_11_16__ Landing on the
                        // boundary is a deliberate stop ABOVE the bottom, exactly
                        // like a jump to a pinned message (`scrollToMessageId`
                        // clears this too). Left on, the iOS 18 geometry follower
                        // saw the gap below the divider as "drift" and closed it
                        // on the next layout. Measured (`-OSHIScrollLab
                        // -OSHIScrollUnread`, 40 unread): the anchor was found
                        // (logged unread=40, anchor set) yet the group opened on
                        // the newest message with no divider in sight. Reaching
                        // the bottom by hand turns following back on.
                        followBottom = false
                        openScrollRetryTask?.cancel()
                        openScrollRetryTask = Task { @MainActor in
                            // Same shape as landAtBottom: a LazyVStack row that
                            // has not been built cannot be scrolled to, and
                            // media bubbles grow as they decode.
                            withAnimation(nil) { proxy.scrollTo(Self.unreadDividerId, anchor: .top) }
                            let deadline = Date().addingTimeInterval(2.0)
                            while Date() < deadline {
                                try? await Task.sleep(for: .milliseconds(80))
                                if Task.isCancelled || userScrolledSinceOpen { return }

                                // __UNREAD_ANCHOR_MUST_BE_A_RENDERED_ROW_2026_10_23__
                                //
                                // THE BUG THIS CLOSES: you open a group, every
                                // message in it has already been read, and you
                                // land at the very TOP of the thread with the
                                // whole history to scroll back down. Reported on
                                // a group with a bot in it, which is the case
                                // that makes it near-certain.
                                //
                                // `firstUnreadIncomingGroupId()` searches the
                                // WHOLE history (`groupManager.groupMessages`).
                                // The list renders two things less than that:
                                //
                                //   * only the last `messageDisplayLimit` (50)
                                //     messages — `loadMessages()`;
                                //   * and then `mergeBotMessagePairs` folds a bot
                                //     command and its reply into ONE row, so an
                                //     id inside that window can still stop being
                                //     a row at all.
                                //
                                // `proxy.scrollTo` on an id that is not a row is
                                // not an error and not a no-op you can see: it is
                                // silently ignored. The old code then `return`ed,
                                // so `landAtBottom` never ran, and a LazyVStack
                                // left alone sits where it started — the top.
                                // Two seconds of retries aimed at nothing.
                                //
                                // So: once there ARE rows, the anchor has to be
                                // one of them. If it is not, stop guessing and go
                                // where the reader expects to be. An empty list
                                // means layout has not caught up yet, which is
                                // worth waiting out rather than giving up on.
                                let rows = displayMessages
                                if !rows.isEmpty,
                                   !rows.contains(where: { $0.id.uuidString == unreadId }) {
                                    openUnreadAnchorId = nil
                                    followBottom = true
                                    openScrollRetryTask = landAtBottom(
                                        proxy, to: "groupBottomAnchor",
                                        animateFirstPass: false,
                                        isAtBottom: {
                                            bottomAnchorSighted || userScrolledSinceOpen
                                        })
                                    return
                                }

                                withAnimation(nil) { proxy.scrollTo(Self.unreadDividerId, anchor: .top) }
                            }
                        }
                        return
                    }
                    openScrollRetryTask = landAtBottom(proxy, to: "groupBottomAnchor",
                                                       animateFirstPass: false,
                                                       // Stop as soon as the real bottom anchor is
                                                       // visible, or if the reader deliberately
                                                       // starts moving. Previously this predicate
                                                       // checked only `userScrolledSinceOpen`, so a
                                                       // cold group kept issuing scroll commands for
                                                       // 2.5 seconds even after it had landed. On a
                                                       // lazy, image-heavy thread those commands can
                                                       // race the initial layout and leave the
                                                       // viewport in empty wallpaper below the list.
                                                       isAtBottom: {
                                                           bottomAnchorSighted || userScrolledSinceOpen
                                                       })
                    // 🚀 OPEN-LAG FIX (round 2): bot sync + server-bot member
                    // registration moved to the body.onAppear's detached Task so
                    // the first frame lands before any network calls fire.
                }
                // ✅ FIX #3: Single instant scroll on first non-empty render. Guards against
                // empty-at-onAppear case (loadedMessages may still be loading).
                // 🔧 b57: only when the bottom anchor isn't already on screen,
                // so it can't disengage the iOS 18 system anchor needlessly.
                .onChange(of: displayMessages.count) { oldCount, newCount in
                    // __FOLDABLE_LAYOUT_2026_11_16__ A hand-off landing owns this open.
                    if groupHandoffActive {
                        if let anchor = handoffReadingAnchor, newCount > 0 {
                            handoffReadingAnchor = nil
                            restoreGroupReadingAnchor(anchor, proxy: proxy)
                        }
                        return
                    }
                    // 🔧 OPEN LANDING FIX: when messages finish loading AFTER
                    // onAppear (first-time / cold conversations), pin to the
                    // newest message. Use the full settle (not a single shot)
                    // and DON'T gate on `bottomAnchorSighted` — an early
                    // sighting must not prevent the real first landing. If the
                    // thread was already loaded this 0→N transition never fires
                    // while visible, which the onAppear settle loop above covers.
                    if oldCount == 0 && newCount > 0 {
                        settleScrollToBottom(proxy, to: "groupBottomAnchor", animated: false,
                                             isAtBottom: {
                                                 bottomAnchorSighted || userScrolledSinceOpen
                                             })
                    }
                    // 🔧 2026-06-11 (b54): incremental scroll-to-bottom for
                    // late-arriving messages. Mirrors ChatView's 1-to-1
                    // logic. Without this, opening a group during overnight
                    // catch-up showed the OLD messages with the new ones
                    // scrolling in BELOW the visible area — user had to
                    // manually scroll down to see them. Only auto-scroll
                    // when we're already near the bottom (`!showScrollToBottom`)
                    // OR the new message is one I just sent, so we don't
                    // yank the chat while the user is reading old history.
                    //
                    // 🔧 FIX (2026-08-20): the b54 note above names the exact
                    // symptom — "user had to manually scroll down to see them" —
                    // but the remedy was ONE blind `scrollTo`. In a LazyVStack
                    // that advances toward an estimated extent, i.e. about one
                    // screenful, so an overnight catch-up of twenty messages
                    // still lands near the OLDEST of the batch. Settle instead,
                    // exactly like the scroll-to-bottom button does.
                    if newCount > oldCount && oldCount > 0 {
                        let isMyOwnSend = loadedMessages.last?.senderPublicKey == identityManager.publicKey
                        let wasPinnedToBottom = !showScrollToBottom
                        // `!userScrolledSinceOpen` is the cold-open case: on a fresh open
                        // the thread loads its messages in more than one step (and media
                        // like GIFs decode later), so the FIRST landing isn't pinned to the
                        // bottom yet — `wasPinnedToBottom` is false and this block used to
                        // skip, leaving the group parked above the newest message ("trop
                        // haut, il faut slider"). While the reader hasn't dragged, keep
                        // settling to the true bottom as content grows.
                        if isMyOwnSend {
                            followBottom = true
                            // __LONG_SEND_LANDING_2026_09_28__ Own send: hold the
                            // bottom through the insert + composer collapse and verify
                            // on real geometry, instead of one animated pass (iOS 17
                            // stopped there as soon as the reader had EVER dragged,
                            // which parks a long message on its top).
                            ownSendLandingTask?.cancel()
                            ownSendLandingTask = landAtBottomAfterOwnSend(
                                proxy, to: "groupBottomAnchor",
                                tracker: scrollTracker,
                                anchorSighted: { bottomAnchorSighted })
                        }
                        // __GROUP_OPEN_ANCHOR_2026_09_23__ iOS 18+: `followBottom` is the
                        // ground truth (cleared by a drag away from the bottom AND by a
                        // jump to a pinned/searched message, which `userScrolledSinceOpen`
                        // does not see). iOS 17 keeps the previous rule.
                        let shouldFollow: Bool
                        if #available(iOS 18.0, *) {
                            shouldFollow = isMyOwnSend || followBottom
                        } else {
                            shouldFollow = isMyOwnSend || wasPinnedToBottom || !userScrolledSinceOpen
                        }
                        if shouldFollow && !isMyOwnSend {
                            settleScrollToBottom(proxy, to: "groupBottomAnchor",
                                                 isAtBottom: { userScrolledSinceOpen })
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                                // Don't yank a reader who has scrolled up to read history;
                                // otherwise (cold open, just sent, or already at bottom) do
                                // the late follow-up so tall/lazy content lands at the bottom.
                                guard !(userScrolledSinceOpen && showScrollToBottom) else { return }
                                if #available(iOS 18.0, *), !followBottom { return }
                                withAnimation(.easeOut(duration: 0.2)) {
                                    proxy.scrollTo("groupBottomAnchor", anchor: .bottom)
                                }
                            }
                        }
                        // (Plus de levée manuelle: l'ancre est hors champ dans ce
                        // cas, son `onDisappear` a déjà levé le bouton.)
                    }
                    // 🔧 2026-06-10 (b49): hide the loading pill the moment
                    // a new message lands. Don't wait for the 8 s safety
                    // timeout — feels snappier this way.
                    if newCount > oldCount && showLoadingNewMessagePill {
                        loadingPillHideTask?.cancel()
                        withAnimation(.easeIn(duration: 0.25)) {
                            showLoadingNewMessagePill = false
                        }
                    }
                }
                .onChange(of: scrollToMessageId) { oldValue, newValue in
                    if let messageId = newValue {
                        // A jump to a pinned/searched/quoted message is a
                        // deliberate move away from the bottom: stop following,
                        // or the follower would pull straight back down.
                        followBottom = false
                        withAnimation {
                            proxy.scrollTo(messageId, anchor: .center)
                        }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                            scrollToMessageId = nil
                        }
                    }
                }
            }

            // 📥 Pending-fetch indicator: shown while groupMessages is empty AND
            // we're catching up IPFS for this user. Without it, opening a chat
            // with 100+ pending messages renders a blank wallpaper for several
            // seconds — users assume the chat is broken and back out.
            // 🔧 2026-06-10: also shown during the brief SwiftUI initial layout
            // window where loadMessages() hasn't populated the cache yet —
            // covers the "all black" perception users reported even on groups
            // with existing history.
            if displayMessages.isEmpty {
                VStack(spacing: 12) {
                    ProgressView()
                        .scaleEffect(1.2)
                    Text("Loading messages…")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                .padding()
                .oledAwareBackground(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
                .allowsHitTesting(false)
            }

            // 🔧 2026-06-10 (b49): "Loading new message…" pill for the case
            // where displayMessages is NOT empty (so the loading-messages
            // overlay above doesn't fire) but a notification just told us a
            // new message is incoming. Without this, the user opens the
            // group from a tap, sees only old messages, and thinks the
            // notification was a phantom. Mirrors ChatView's b47 fix.
            if showLoadingNewMessagePill {
                VStack {
                    HStack(spacing: 8) {
                        ProgressView()
                            .scaleEffect(0.7)
                            .tint(.white)
                        Text("chat.loading_new_message".localized)
                            .font(.caption)
                            .fontWeight(.medium)
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .background(
                        Capsule()
                            .fill(groupBrandColor.opacity(0.92))
                            .shadow(color: .black.opacity(0.15), radius: 4, x: 0, y: 2)
                    )
                    .padding(.top, 8)
                    Spacer()
                }
                .transition(.move(edge: .top).combined(with: .opacity))
                .zIndex(2)
            }

            // Floating date header (Telegram-style)
            if let dateText = floatingDateHeader {
                VStack {
                    Text(dateText)
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundColor(.white)
                        .padding(.vertical, 6)
                        .padding(.horizontal, 14)
                        .background(
                            Capsule()
                                .fill(Color.black.opacity(0.6))
                        )
                        .transition(.opacity.combined(with: .scale(scale: 0.8)))
                        .padding(.top, 8)
                    Spacer()
                }
                .frame(maxWidth: .infinity)
                .allowsHitTesting(false)
            }

            // Scroll-to-bottom button (floating above messages, like ChatView)
            if showScrollToBottom {
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        Button {
                            Haptics.tap(.light)
                            followBottom = true
                            // 🔧 SINGLE-TAP FIX (mirrors ChatView): settle the
                            // scroll across a few runloop ticks so the
                            // LazyVStack's late-materialized rows are accounted
                            // for and we reach the true bottom on one tap.
                            // 🔴 FIX (2026-08-21): le tap masquait le bouton
                            // sans vérifier qu'on avait atterri — et rien ne
                            // pouvait le relever ensuite. C'est l'onAppear de
                            // l'ancre qui le masque désormais. Voir ChatView.
                            settleScrollToBottom(scrollProxy, to: "groupBottomAnchor",
                                                 isAtBottom: { bottomAnchorSighted })
                        } label: {
                            Image(systemName: "arrow.down.circle.fill")
                                .font(.scaled(36))
                                .foregroundColor(.white)
                                .background(
                                    Circle()
                                        .fill(groupBrandColor)
                                        .frame(width: 44, height: 44)
                                )
                                .shadow(color: .black.opacity(0.2), radius: 4, x: 0, y: 2)
                        }
                        .padding(.trailing, 16)
                        .padding(.bottom, 8)
                    }
                }
                .transition(.scale.combined(with: .opacity))
            }
        }
    }

    // MARK: - Group Input Bar (extracted for type-checker)
    private var groupInputBar: some View {
        VStack(spacing: 0) {
            // __GROUP_SPEAKER_TOGGLE_WAS_ALWAYS_THERE_2026_11_05__ Shown only
            // while a voice message is actually playing.
            //
            // Reported from a device: this control sits in the corner of every
            // group permanently, and in a 1:1 chat there is no such thing. Both
            // observations were right, and the difference was the bug: in a 1:1
            // the identical toggle lives INSIDE the audio-player bar
            // (`ChatView.swift`), so it exists exactly while there is audio to
            // route. Here it was the first element of the input bar, so it
            // advertised an audio route in a group where nobody had ever sent a
            // voice message.
            //
            // Not deleted: the preference is real — earpiece is the private way
            // to listen to a voice note in public, and routing is shared process
            // state via `VoiceMessageAudioRoute`, so it must stay reachable at
            // the moment it matters. Made conditional instead, which is what the
            // 1:1 side has always done.
            if voiceNotePlayback.item != nil {
                HStack {
                    Spacer()
                    Button {
                        useExternalSpeaker.toggle()
                        // Routage centralisé — voir `VoiceMessageAudioRoute`.
                        VoiceMessageAudioRoute.shared.setPrefersSpeaker(useExternalSpeaker)
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: useExternalSpeaker ? "speaker.wave.2.fill" : "ear")
                                .font(.caption)
                                .contentTransition(.symbolEffect(.replace))
                            Text(useExternalSpeaker ? "audio.speaker".localized : "audio.earpiece".localized)
                                .font(.caption)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(useExternalSpeaker ? groupBrandColor.opacity(0.2) : Color(.systemGray5))
                        .cornerRadius(12)
                    }
                    .accessibilityLabel(useExternalSpeaker ? "audio.speaker".localized : "audio.earpiece".localized)
                    .padding(.trailing)
                    .padding(.top, 4)
                }
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }

            if isGroupBlocked {
                HStack(spacing: 12) {
                    Image(systemName: "hand.raised.fill").font(.title3).foregroundColor(.white)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("group.blocked_title".localized).font(.headline).foregroundColor(.white)
                        Text("group.blocked_message".localized).font(.caption).foregroundColor(.white.opacity(0.9))
                    }
                    Spacer()
                    Button { showingUnblockAlert = true } label: {
                        Text("group.unblock_button".localized)
                            .font(.subheadline).fontWeight(.semibold).foregroundColor(.red)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(Color.white).cornerRadius(8)
                    }
                }
                .padding().background(Color.red)
            }

            if isRecordingAudio {
                HStack(spacing: 12) {
                    Circle().fill(Color.red).frame(width: 10, height: 10).opacity(0.8)
                    Text(formatDuration(audioRecorder.recordingDuration))
                        .font(.scaled(16, weight: .medium, design: .monospaced)).foregroundColor(.red)
                    Spacer()
                    Text(recordingMode == .tapToRecord ? "audio.tap_stop_to_send".localized : "audio.release_to_send".localized)
                        .font(.caption).foregroundColor(.red)
                }
                .padding().background(Color.red.opacity(0.1))
            }

            if !nlpManager.smartReplies.isEmpty && messageText.isEmpty && !isGroupBlocked {
                SmartRepliesView(replies: nlpManager.smartReplies) { reply in messageText = reply }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if let reply = replyingToMessage {
                // `contactAlias: nil` made the banner fall back to a truncated
                // public key, so replying in a group named the member by hex.
                // `senderDisplayName` is the group's own naming rule.
                ReplyPreviewView(reply: reply,
                                 contactAlias: senderDisplayName(reply.originalSenderKey),
                                 onCancel: { replyingToMessage = nil })
            }

            // __SLASH_MENU_2026_08_26__ Le menu `/`, comme sur Telegram.
            // Pose juste au-dessus du champ, comme `SmartRepliesView`, et jamais
            // construit vide: `matches` rend `[]` quand il n'y a rien a montrer.
            // __BOT_COMMANDS_2026_11_24__ per-command descriptions, keyboard
            // highlight, tap = send for a bot command, and a friendly line
            // instead of nothing in a chat that has bots.
            if let menu = slashMenu, !isGroupBlocked {
                SlashCommandBar(commands: menu.commands,
                                tint: groupBrandColor,
                                highlightedIndex: slashHighlight,
                                hint: menu.hint,
                                onAddCommands: { botID in
                                    slashEditorBot = BotManager.shared.bots.first { $0.id == botID }
                                },
                                onInsert: { insertSlashCommand($0) },
                                onPick: { pickSlashCommand($0) })
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            groupInputRow

            if !selectedMedia.isEmpty {
                HStack {
                    Text(String(format: "attachments_selected".localized, selectedMedia.count))
                        .font(.caption).foregroundColor(.secondary)
                    Spacer()
                    Button("button_clear".localized) { selectedMedia = [] }.font(.caption)
                }
                .padding(.horizontal).padding(.bottom, 8)
            }
        }
        // __OLED_GLASS_2026_11_16__ crystal-glass bar under OLED black.
        // __CHAT_CHROME_BLUR_2026_11_21__ frosted, not opaque (was
        // `systemBackground` with the glass option off): the full-screen
        // wallpaper runs under the whole input area, as in a 1:1 thread whose
        // composer has been `.ultraThinMaterial` since 07/11.
        .oledGlassBar(fallback: .ultraThinMaterial)
    }

    /// __BOT_COMMANDS_2026_11_24__ The bots that serve this group.
    private var groupBots: [OSHIBot] { BotManager.shared.botsForGroup(group.id) }

    /// __BOTS_PHASE1_2026_11_24__ Commands of the group's REMOTE bots that were
    /// not created on this device (GroupBotDirectory, `/api/bot/commands`).
    private var remoteSlashCommands: [SlashCommand] {
        GroupBotDirectory.shared.slashCommands(in: group.id, owned: BotManager.shared.bots)
    }

    /// In a channel only admins type; members use the buttons (contract §ADD 2).
    private var canTypeCommands: Bool {
        group.groupType != .adminOnly || groupManager.isCurrentUserAdmin(of: group.id)
    }

    /// What the `/` bar shows (nil = no bar). See `SlashMenuContent.make`.
    private var slashMenu: SlashMenuContent? {
        guard canTypeCommands else { return nil }
        return SlashMenuContent.make(for: messageText, bots: groupBots, extra: remoteSlashCommands)
    }

    /// Tap (or Return): a bot command goes out at once, like Telegram; a
    /// built-in that needs an argument (`/pay`) fills the composer instead.
    private func pickSlashCommand(_ cmd: SlashCommand) {
        slashHighlight = nil
        if cmd.sendsOnPick {
            messageText = cmd.command
            sendMessage()
        } else {
            messageText = SlashCommands.completion(for: cmd)
            groupComposerFocused = true
        }
    }

    /// The arrow on a row (or Tab): `/name ` in the composer, to add arguments.
    private func insertSlashCommand(_ cmd: SlashCommand) {
        slashHighlight = nil
        messageText = SlashCommands.completion(for: cmd)
        groupComposerFocused = true
    }

    private enum SlashKey { case up, down, pick, insert, close }

    /// Hardware keyboard in the `/` menu. `.ignored` whenever the menu is
    /// closed, so ↑/↓/Return/Tab keep their usual meaning in the field.
    private func handleSlashKey(_ key: SlashKey) -> KeyPress.Result {
        guard let menu = slashMenu, !menu.commands.isEmpty, !isGroupBlocked else { return .ignored }
        let count = menu.commands.count
        switch key {
        case .up: slashHighlight = SlashMenuNavigation.move(slashHighlight, by: -1, count: count)
        case .down: slashHighlight = SlashMenuNavigation.move(slashHighlight, by: 1, count: count)
        case .pick:
            guard let i = SlashMenuNavigation.target(slashHighlight, count: count) else { return .ignored }
            pickSlashCommand(menu.commands[i])
        case .insert:
            guard let i = SlashMenuNavigation.target(slashHighlight, count: count) else { return .ignored }
            insertSlashCommand(menu.commands[i])
        case .close:
            // __BOTS_PHASE1_2026_11_24__ Esc closes the menu (contract §4.4).
            slashHighlight = nil
            messageText = ""
        }
        return .handled
    }

    /// The `/` button shows in a chat with bots while the field is empty or
    /// already holds a command being chosen.
    private var showsSlashMenuButton: Bool {
        (!groupBots.isEmpty || !remoteSlashCommands.isEmpty) && canTypeCommands && !isGroupBlocked
            && (messageText.isEmpty || slashMenu != nil)
    }

    private func toggleSlashMenu() {
        slashHighlight = nil
        if slashMenu != nil { messageText = "" } else { messageText = "/" }
        groupComposerFocused = true
    }

    // MARK: - Group Input Row (further extracted)
    private var groupInputRow: some View {
        HStack(alignment: .bottom, spacing: 8) {
            // __COMPOSER_PILL_2026_11_23__ Google-app style (user request 23/11,
            // Android 99c1844d): "+", the field, AI and the capture shortcuts share
            // ONE pill; the shortcuts still fold away while there is text.
            ComposerPill(focused: groupComposerFocused, tint: groupBrandColor) {
                // __ATTACH_PANEL_2027_2026_11_17__ "+" opens the attachment panel
                // shared with 1:1 (recent photos, multi-select + caption, and every
                // option the menu listed: Gallery, Camera, File, GIFs & Stickers,
                // Location, Contact, Poll, Check-In, Video note, Cashu, Thinking of
                // You, Schedule a Call).
                Button { showAttachPanel = true } label: {
                    Image(systemName: "plus").font(.scaled(22, weight: .medium)).foregroundColor(isGroupBlocked ? .gray : groupBrandColor)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .frame(width: 40, height: 44).disabled(isRecordingAudio || isGroupBlocked)
                .accessibilityLabel(Text("attach.button".localized))
                .accessibilityIdentifier("composer.attach")

                if showsSlashMenuButton {
                    SlashMenuButton(isOpen: slashMenu != nil, tint: groupBrandColor) { toggleSlashMenu() }
                        .disabled(isRecordingAudio)
                        .transition(.opacity)
                }

                TextField("composer.placeholder_short".localized, text: $messageText, axis: .vertical)
                    .privateTypingComposer()  // __KEYBOARD_PRIVACY_2026_10_01__ no autocorrect learning when Private typing is on
                    .textFieldStyle(.plain)
                    // __COMPOSER_PILL_2026_11_23__ bare field: the pill carries fill,
                    // outline (brand while focused) and the glass edge.
                    .padding(.horizontal, 6)
                    .padding(.vertical, 10)
                    .lineLimit(ComposerChrome.lineLimit(expanded: !groupShowsCaptureButtons))
                    .frame(minHeight: 44)
                    .layoutPriority(1)
                    .disabled(isRecordingAudio || isGroupBlocked)
                    .accessibilityIdentifier("group.composer")
                    // __FOLDABLE_LAYOUT_2026_11_16__ keyboard state for the hand-off.
                    .focused($groupComposerFocused)
                    .onChange(of: groupComposerFocused) { _, focused in
                        ThreadHandoff.shared.setComposerFocused(handoffKey, focused)
                    }
                    .onReceive(NotificationCenter.default.publisher(for: ThreadHandoff.restoreComposerFocus)) { note in
                        guard ThreadHandoff.isComposerFocusRequest(note, key: handoffKey, token: handoffToken) else { return }
                        groupComposerFocused = true
                    }
                    // __BOT_COMMANDS_2026_11_24__ ↑/↓/Return/Tab in the `/` menu
                    // (iPad and Mac hardware keyboards). Shift-Return still
                    // breaks the line.
                    .onKeyPress(.upArrow) { handleSlashKey(.up) }
                    .onKeyPress(.downArrow) { handleSlashKey(.down) }
                    .onKeyPress(.tab) { handleSlashKey(.insert) }
                    .onKeyPress(.escape) { handleSlashKey(.close) }
                    .onKeyPress(keys: [.return]) { press in
                        press.modifiers.contains(.shift) ? .ignored : handleSlashKey(.pick)
                    }
                    .onChange(of: messageText) { _, newValue in
                        if slashHighlight != nil { slashHighlight = nil }
                        ThreadHandoff.shared.setText(handoffKey, newValue)
                        // Announce typing/stopped to the group through the shared manager (debounced internally).
                        let displayName = UserDefaults.standard.string(forKey: "userName") ?? ""
                        if newValue.isEmpty {
                            TypingIndicatorManager.shared.userStoppedTyping(in: group.id.uuidString, senderName: displayName)
                        } else {
                            TypingIndicatorManager.shared.userStartedTyping(in: group.id.uuidString, senderName: displayName)
                        }
                    }

                // __TEXT_FORMATTING_2026_11_24__ Bold / Underline, while writing.
                if groupComposerFocused && !isGroupBlocked {
                    ComposerFormatMenu(text: $messageText, tint: groupBrandColor) {
                        groupComposerFocused = true
                    }
                    .disabled(isRecordingAudio)
                    .transition(.opacity)
                }

                // 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ not offered in a protected group.
                if AdvancedChatPrivacyPolicy.allowsAI(protected: isAdvancedPrivacyOn) {
                Button { showAIShortcuts = true } label: {
                    Image(systemName: "sparkles").font(.scaled(18))
                        .foregroundColor(isGroupBlocked ? .gray : groupBrandColor).frame(width: 36, height: 44)
                        .contentShape(Rectangle())
                }
                .disabled(isRecordingAudio || isGroupBlocked)
                }

                // __VIDEO_NOTE_2026_09_24__ Camera + round-video buttons fold away while
                // there is text, and the field takes their width (same rule as 1:1,
                // `ComposerChrome`).
                if groupShowsCaptureButtons {
                    Button { showGroupCamera = true } label: {
                        Image(systemName: "camera.fill").font(.title3).foregroundColor(isGroupBlocked ? .gray : groupBrandColor)
                            .frame(width: 36, height: 44).contentShape(Rectangle())
                    }
                    .accessibilityLabel(Text("camera_permission".localized))
                    .disabled(isRecordingAudio || isGroupBlocked)
                    .transition(groupCaptureTransition)

                    Button {
                        var t = Transaction()
                        t.disablesAnimations = true
                        withTransaction(t) { showGroupVideoNote = true }
                    } label: {
                        Image(systemName: "video.circle.fill").font(.scaled(24))
                            .foregroundColor(isGroupBlocked || !groupVideoNoteAvailable ? .gray : groupBrandColor)
                            .frame(width: 36, height: 44).contentShape(Rectangle())
                    }
                    .accessibilityLabel(Text("videonote.button".localized))
                    .help(groupVideoNoteAvailable ? "videonote.button".localized : "videonote.no_camera".localized)
                    .disabled(isRecordingAudio || isGroupBlocked || !groupVideoNoteAvailable)
                    .transition(groupCaptureTransition)
                }

            }
            .layoutPriority(1)

            // __COMPOSER_PILL_2026_11_23__ mic / send: a separate circle of the
            // pill's height beside it.
            if !messageText.isEmpty || !selectedMedia.isEmpty {
                Button { sendMessage() } label: {
                    ComposerRoundButtonFace(systemImage: "arrow.up", primary: true,
                                            tint: groupBrandColor, enabled: !isGroupBlocked)
                }
                .buttonStyle(PressScaleButtonStyle())
                .disabled(isGroupBlocked)
                .accessibilityLabel(Text("general.send".localized))
                .accessibilityIdentifier("group.send")
            } else {
                audioRecordButton.disabled(isGroupBlocked)
            }
        }
        .padding()
        // __CHAT_CHROME_BLUR_2026_11_21__ the bar's surface moved up to
        // `groupInputBar`, so it also covers the quick replies, the reply
        // preview and the `/` bar above this row.
        .animation(composerReduceMotion ? .easeInOut(duration: 0.18) : .spring(response: 0.36, dampingFraction: 0.78),
                   value: groupShowsCaptureButtons)
        .attachmentPanel(isPresented: $showAttachPanel,
                         kind: .group,
                         options: groupAttachOptions,
                         tint: groupBrandColor,
                         plusAnchor: CGRect(x: 16, y: 0, width: 36, height: 4),
                         onOption: performGroupAttachOption,
                         onSend: sendGroupFromAttachPanel)
    }

    // MARK: - __ATTACH_PANEL_2027_2026_11_17__ Attachment panel

    private var groupAttachOptions: [AttachOption] {
        var o = AttachmentPanelLogic.options(for: .group)
        if !groupVideoNoteAvailable { o.removeAll { $0 == .videoNote } }
        return o
    }

    /// Runs once the panel has closed — the same state flips the menu made.
    private func performGroupAttachOption(_ option: AttachOption) {
        guard !isGroupBlocked else { return }
        switch option {
        case .gallery: showMediaPicker = true
        case .camera: showGroupCamera = true
        case .file: showDocumentPicker = true
        case .gif: showGifPicker = true
        case .location: showLocationPicker = true
        case .contact: showContactPicker = true
        case .poll: showPoll = true
        case .checkIn: showCheckIn = true
        case .videoNote:
            var t = Transaction()
            t.disablesAnimations = true
            withTransaction(t) { showGroupVideoNote = true }
        case .cashu: cashuDraft = CashuSendDraft()
        case .thinkingOfYou: showScheduledMessage = true
        case .scheduleCall: showScheduledCall = true
        case .disguisePhoto: break  // 1:1 only: the photo is encrypted for one contact
        }
    }

    /// Photos/videos picked in the panel go out through the GROUP media path
    /// (`groupManager.sendMessage` with media), one message each, in pick
    /// order; the caption (and a pending reply) rides the first.
    private func sendGroupFromAttachPanel(_ send: AttachPanelSend) {
        guard !isGroupBlocked, !send.attachments.isEmpty else { return }
        let captions = AttachmentPanelLogic.captions(count: send.attachments.count, typed: send.caption)
        for (index, attachment) in send.attachments.enumerated() {
            var content = captions[index]
            if index == 0, let reply = replyingToMessage {
                content = MessageWithReply(content: content, replyTo: reply).toMessageString()
            }
            groupManager.sendMessage(
                to: group.id,
                content: content,
                senderPublicKey: identityManager.publicKey,
                mediaData: attachment.data,
                mediaType: convertToGroupMediaType(attachment.type),
                mediaFileName: attachment.fileName
            )
        }
        OshiLog.messaging.info("✅ Attach panel: \(send.attachments.count) media sent to group")
        replyingToMessage = nil
        loadMessages()
    }

    private var groupShowsCaptureButtons: Bool {
        ComposerChrome.showsCaptureButtons(text: messageText) && selectedMedia.isEmpty
    }

    private var groupCaptureTransition: AnyTransition {
        composerReduceMotion
            ? .opacity
            : .asymmetric(insertion: .scale(scale: 0.4, anchor: .trailing).combined(with: .opacity),
                          removal: .scale(scale: 0.4, anchor: .leading).combined(with: .opacity))
    }

    private var groupVideoNoteAvailable: Bool {
        #if targetEnvironment(macCatalyst)
        return VideoNoteCaptureController.cameraPresent
        #else
        return true
        #endif
    }

    /// __VIDEO_NOTE_2026_09_24__ Same group media path as any video; the note
    /// keys ride the GroupMessage and the v2 file key (spec §2.B/§2.D).
    private func sendGroupVideoNote(_ data: Data, meta: VideoNoteMetadata) {
        guard !isGroupBlocked else { return }
        groupManager.sendMessage(
            to: group.id,
            content: "",
            senderPublicKey: identityManager.publicKey,
            mediaData: data,
            mediaType: .video,
            mediaFileName: VideoNote.fileName(),
            videoNote: meta
        )
        loadMessages()
    }

    // MARK: - Pinned Message Banner
    /// __GROUP_E2E_V2_2026_09_23__ spec §6: members on a build without v2 receive nothing
    /// (no leaking fallback). Say so, by name, instead of letting them silently miss messages.
    @ViewBuilder
    private var mustUpdateBanner: some View {
        let keys = groupManager.v2UnreachableMembers[group.id] ?? []
        if !keys.isEmpty {
            let names = keys.map { GroupContactAliases.displayName($0) ?? String($0.prefix(8)) }
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundColor(.orange)
                Text(names.map { "group.member_must_update".localized(with: $0) }.joined(separator: "\n"))
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color.orange.opacity(0.08))
            .accessibilityElement(children: .combine)
        }
    }

    @ViewBuilder
    private var pinnedMessageBanner: some View {
        if let pinnedId = liveGroup.pinnedMessageId,
           let pinnedMsg = loadedMessages.first(where: { $0.id == pinnedId }) {
            Button {
                scrollToMessageId = pinnedId
            } label: {
                HStack(spacing: 10) {
                    RoundedRectangle(cornerRadius: 2)
                        .fill(groupBrandColor)
                        .frame(width: 3, height: 30)

                    Image(systemName: "pin.fill")
                        .font(.caption)
                        .foregroundColor(groupBrandColor)
                        .rotationEffect(.degrees(45))

                    VStack(alignment: .leading, spacing: 2) {
                        Text("pinned.title".localized)
                            .font(.caption2)
                            .fontWeight(.semibold)
                            .foregroundColor(groupBrandColor)

                        if let text = pinnedMsg.plaintextContent {
                            // Pinned banner: a pinned control payload used to
                            // sit at the top of the group until someone unpinned it.
                            // `InlineMarkup.stripped`: one unstyled caption line,
                            // so the markers must go rather than be printed. The
                            // strip is applied AFTER `previewText` — that call
                            // unwraps payload envelopes and must still see the
                            // string it expects.
                            Text(InlineMarkup.stripped(OSHIControlPayload.previewText(for: text)))
                                .font(.caption)
                                .foregroundColor(.primary)
                                .lineLimit(1)
                        } else if pinnedMsg.mediaType != nil {
                            Text(pinnedMsg.mediaType == .photo ? "media.photo".localized : pinnedMsg.mediaType == .video ? "media.video".localized : "storage.media_size".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                        }
                    }

                    Spacer()

                    if permGroup.canPinMessages {
                        Button {
                            let _ = groupManager.unpinMessage(in: group.id, by: identityManager.publicKey)
                        } label: {
                            Image(systemName: "xmark")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                                .padding(6)
                        }
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color(.secondarySystemGroupedBackground))
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - Search Bar View (extracted to help type-checker)
    @ViewBuilder
    private var searchBarView: some View {
        if showSearch {
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.gray)
                    
                    TextField("placeholder_search_messages".localized, text: $searchText)
                        .textFieldStyle(.plain)
                        .accessibilityIdentifier("group.search.field")
                        .submitLabel(.done)
                        .onSubmit {
                            hideKeyboard()
                        }
                    
                    if !searchText.isEmpty {
                        Button {
                            searchText = ""
                            hideKeyboard()
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.gray)
                        }
                    }
                    
                    // Close search button
                    Button {
                        showSearch = false
                        searchText = ""
                        hideKeyboard()
                    } label: {
                        Text("done".localized)
                            .font(.subheadline)
                            .foregroundColor(groupBrandColor)
                    }
                    
                    // ✅ Enhanced calendar button
                    Button {
                        showDateSearch = true
                    } label: {
                        Image(systemName: "calendar")
                            .foregroundColor(selectedSearchDate != nil || dateRangeStart != nil ? groupBrandColor : .gray)
                    }
                }
                .padding()
                .background(Color(.systemGray6))
                
                // ✅ Date filter badge
                if selectedSearchDate != nil || dateRangeStart != nil {
                    HStack {
                        DateFilterBadge(
                            date: selectedSearchDate,
                            startDate: dateRangeStart,
                            endDate: dateRangeEnd,
                            onClear: {
                                selectedSearchDate = nil
                                dateRangeStart = nil
                                dateRangeEnd = nil
                            }
                        )
                        Spacer()
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 8)
                    .background(Color(.systemGray6))
                }
            }
        }
    }
    
    // MARK: - Main Content (extracted for type-checker)
    /// __GROUP_CHAT_TYPE_DEPTH_2026_10_28__ `AnyView`, deliberately, and it is a
    /// CRASH FIX rather than a style choice.
    ///
    /// Reported live on an iPhone 17: opening a group chat killed the app with
    /// `EXC_BAD_ACCESS` / `KERN_PROTECTION_FAILURE` in a stack guard region — a
    /// main-thread STACK OVERFLOW, 135 frames of `libswiftCore`'s
    /// `decodeMangledType` ↔ `decodeGenericArgs` mutual recursion under
    /// `__swift_instantiateConcreteTypeFromMangledNameV2`, entered from
    /// `GroupChatView.body` → `contentWithSheetsC` → `B` → `A` → `mainContent`
    /// → `groupInputBar` → `groupInputRow`.
    ///
    /// The runtime was demangling ONE generic type: every stack, every branch
    /// and all eleven-plus presentation modifiers compose into a single nested
    /// type, and instantiating it recursed deeper than the main thread's stack.
    ///
    /// SPLITTING THE CHAIN DOES NOT FIX THIS, which is the trap here. The
    /// `contentWithSheetsA/B/C` chain and the extracted overlays above were
    /// introduced for the COMPILE-TIME type-check budget, and they work for
    /// that — but computed properties with opaque result types still compose
    /// into the same type at runtime, so the mangled name kept growing. Only
    /// erasing the type truncates it.
    ///
    /// Erased HERE because it is the widest seam: everything below — the
    /// message list, the composer, the input row — collapses into one opaque
    /// node, so the sheets chain above wraps `AnyView` instead of the whole
    /// subtree. The cost is that SwiftUI can no longer see this subtree's type
    /// for diffing; the benefit is a group chat that opens.
    ///
    /// Why it showed up only now: a release build instantiates most of this
    /// metadata ahead of time, so the App Store builds survived it. The debug
    /// build installed on the device resolves it at runtime, from a mangled
    /// name, on the main thread. The fragility was always there.
    private var mainContent: AnyView {
        AnyView(mainContentBody)
    }

    private var mainContentBody: some View {
        VStack(spacing: 0) {
            searchBarView
            pinnedMessageBanner
            mustUpdateBanner
            // __WALLPAPER_TONE_2026_09_24__ the thread renders in the
            // wallpaper's tone (light mesh kept in dark mode ⇒ light bubbles).
            messagesZStack
                .environment(\.colorScheme,
                             wallpaperManager.appearance(for: group.id.uuidString,
                                                         system: systemScheme).contentScheme)
            // __GROUP_INFO_SETTINGS_2026_11_20__ §2.4 channel mode / §2.9 dissolved:
            // the composer is replaced by a muted banner (typing never sent).
            let composer = GroupSettingsPolicy.composerState(me: groupManager.currentUserPublicKey, group: liveGroup)
            if composer == .open {
                groupInputBar
                    // __VOICE_MINIPLAYER_2026_10_09__ Keeps the floating
                    // voice-note pill off the composer in a group too.
                    .voiceNotePlayerObstruction()
            } else {
                GroupComposerBlockedBanner(state: composer) {
                    groupManager.leaveGroup(liveGroup.id, userPublicKey: groupManager.currentUserPublicKey ?? "")
                    dismissGroupChat()
                }
            }
        }
        // __CHAT_CHROME_BLUR_2026_11_21__ « les fonds d'écran sur les groupes
        // comme sur les conversations privées … pour avoir le fond sur tout
        // l'écran ». The wallpaper is the ground of the WHOLE screen, edge to
        // edge — under the frosted header, the banners and the frosted composer
        // — exactly as in a 1:1 thread. It keeps the thread's tone.
        .background {
            ZStack {
                Color(.systemGray6)
                ChatWallpaperView(wallpaper: wallpaperManager.getWallpaper(for: group.id.uuidString))
            }
            .environment(\.colorScheme, threadScheme)
            .ignoresSafeArea()
        }
        .chatTopBarBackdrop()
        .navigationTitle(liveGroup.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { groupToolbarContent }
    }

    // MARK: - Long-press message menu (extracted for type-checker)
    //
    // 🔧 FIX (2026-08-12, "long-pressing a group message opens a broken emoji
    // screen"):
    //
    //   1. THE bug — this `ReactionOverlay` was presented in a `fullScreenCover`
    //      with NO `ClearBackgroundView()` behind it. `ReactionOverlay` draws
    //      its own dimmed scrim over the conversation and positions the picker
    //      at the message's own frame; on an OPAQUE system cover there is no
    //      conversation to dim, so what shipped was a full page of flat
    //      systemBackground with a row of emoji floating in the middle of it.
    //      The 1:1 path (ChatView.swift:4074) and the unreachable
    //      `GroupMessageBubble` (line ~5600) both already do this; only the path
    //      that actually draws was missing it.
    //   2. `pinLabel` was left at its default — the literal, UNLOCALIZED string
    //      "Pin" in `ReactionOverlay` — and `isPinnedMessage` at `false`, so the
    //      button read "Pin" in all 34 languages and offered to pin a message
    //      that was already pinned.
    //   3. Delete / edit / translate were missing outright, so the group menu
    //      offered four buttons where 1:1 offers seven.
    //
    // Extracted rather than nested: `contentWithSheetsA` is already a chain of
    // eleven presentation modifiers, and this project has a documented
    // view-builder type-check budget.
    @ViewBuilder
    private var groupMessageActionsOverlay: some View {
        if let m = actionMessage {
            let mine = isFromCurrentUser(m)
            let isPinned = liveGroup.pinnedMessageId == m.id
            ReactionOverlay(
                messageId: m.id.uuidString,
                senderPublicKey: groupManager.currentUserPublicKey ?? "",
                senderName: "you".localized,
                isPresented: $showMessageActions,
                messageFrame: actionFrame,
                isFromCurrentUser: mine,
                onReact: { emoji in reactToGroupMessage(m, emoji: emoji) },
                messageContent: resolveText(for: m),
                onForward: {
                    // Deferred: the cover has to finish dismissing before the
                    // picker can present, or the second presentation is
                    // swallowed and the button reads as broken.
                    guard !MessagePrivacy.blockForwardAndCopy else { return }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
                        showGroupForwardPicker = true
                    }
                },
                onCopy: {
                    // Belt and braces: the button is hidden when blocked, so this
                    // closure should be unreachable then. Guarding anyway means a
                    // future caller cannot reintroduce the path by accident.
                    guard !MessagePrivacy.blockForwardAndCopy else { return }
                    // Through the catalog, like the 1:1 path: copying a call
                    // summary must not put sentinel JSON on the pasteboard.
                    let text = OSHIControlPayload.previewText(for: resolveText(for: m) ?? "")
                    guard !text.isEmpty else { return }
                    // __CLIPBOARD_HYGIENE_2026_10_01__ message text: this device only, 60 s
                    SecureClipboard.copy(text)
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                },
                onReply: { beginReply(to: m) },
                onDelete: mine ? { showGroupDeleteSheet = true } : nil,
                onTranslate: { translateGroupMessage(m) },
                onPin: {
                    // `pinMessage` existed with ZERO callers — a group pin
                    // could never be created, only removed. It broadcasts to
                    // every member via broadcastFullGroupUpdate, so this is
                    // a pin for everyone, not a local bookmark.
                    if isPinned {
                        _ = groupManager.unpinMessage(in: group.id, by: identityManager.publicKey)
                    } else {
                        _ = groupManager.pinMessage(in: group.id,
                                                    messageId: m.id,
                                                    by: identityManager.publicKey)
                    }
                },
                pinLabel: isPinned ? "pinned.unpin_message".localized
                                   : "pinned.pin_message".localized,
                isPinnedMessage: isPinned,
                onEdit: (mine && Date().timeIntervalSince(m.timestamp) <= 600 && m.mediaType == nil) ? {
                    // __EDIT_SHEET_RACED_THE_OVERLAY_2026_11_05__ Same 0.2 s
                    // defer as the 1:1 path and the Forward action beside it:
                    // this overlay is a `fullScreenCover`, and a second
                    // presentation requested before it finishes dismissing is
                    // swallowed, leaving the button looking broken.
                    let prefill = resolveText(for: m) ?? ""
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                        groupEditingText = prefill
                        groupEditingOriginal = prefill
                        showGroupEditSheet = true
                    }
                } : nil,
                onWebLink: deferredWebLinkAction(for: m),
                reactionChoices: groupReactionChoices
            )
            // THE fix: without this the cover is opaque and the overlay's own
            // scrim has nothing to sit on.
            .background(ClearBackgroundView())
        }
    }

    /// __CHANNEL_WEB_2026_11_26__ nil unless this is a channel post on the public web.
    private func webLinkAction(for m: GroupMessage) -> (() -> Void)? {
        guard ChannelWebShare.isEligible(m, in: liveGroup) else { return nil }
        return { webLinkMessage = m }
    }

    /// Same, from the long-press overlay: deferred until the cover has dismissed
    /// (a second presentation requested earlier is swallowed).
    private func deferredWebLinkAction(for m: GroupMessage) -> (() -> Void)? {
        guard let open = webLinkAction(for: m) else { return nil }
        return { DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { open() } }
    }

    /// Toggle locally so the tap is immediate, then broadcast the add/remove so
    /// every member converges. Lifted out of the overlay for the type-checker.
    /// __GROUP_INFO_SETTINGS_2026_11_20__ §2.3 sender side: the picker offers only
    /// the allowed emoji (nil = all, [] = picker hidden).
    private var groupReactionChoices: [ReactionEmoji]? {
        guard let s = liveGroup.settings, s.effectiveReactions != .all else { return nil }
        return ReactionEmoji.allCases.filter { GroupSettingsPolicy.reactionAllowed($0.rawValue, settings: s) }
    }

    private func reactToGroupMessage(_ m: GroupMessage, emoji: ReactionEmoji) {
        let me = groupManager.currentUserPublicKey ?? identityManager.publicKey
        let rm = MessageReactionManager.shared
        let isRemoving = rm.hasUserReacted(
            messageId: m.id.uuidString,
            senderPublicKey: me,
            emoji: emoji
        )
        // §2.3: an `add` the group does not allow is never sent (receivers drop it).
        if !isRemoving, !GroupSettingsPolicy.reactionAllowed(emoji.rawValue, settings: liveGroup.settings) {
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
            return
        }
        rm.toggleReaction(
            to: m.id.uuidString,
            emoji: emoji,
            senderPublicKey: me,
            senderName: "you".localized
        )
        let payload = ReactionPayload(
            messageId: m.id.uuidString,
            emoji: emoji,
            senderPublicKey: me,
            senderName: "you".localized,
            timestamp: Date(),
            action: isRemoving ? .remove : .add
        )
        groupManager.sendMessage(to: group.id,
                                 content: payload.toMessageString(),
                                 senderPublicKey: me)
    }

    /// Same two-step as the 1:1 menu: the on-device NLP translator first, the
    /// iOS 18 Translation sheet as the fallback.
    private func translateGroupMessage(_ m: GroupMessage) {
        let source = OSHIControlPayload.previewText(for: resolveText(for: m) ?? "")
        Task {
            let translated = await NLPManager.shared.processTranslateReceived(source)
            await MainActor.run {
                groupTranslatedText = translated ?? "ai.translate_failed".localized
                showGroupTranslationResult = true
            }
        }
    }

    // MARK: - Sheets Group A (extracted for type-checker)
    private var contentWithSheetsA: some View {
        mainContent
        // Message actions for the LIVE bubble path. Presented here rather than
        // per-bubble: a fullScreenCover on every row is one presentation context
        // per message, which SwiftUI charges for whether or not it is shown.
        .alert(partialDeliveryShown.map { GroupPartialDelivery.title($0) } ?? "",
               isPresented: Binding(get: { partialDeliveryShown != nil },
                                    set: { if !$0 { partialDeliveryShown = nil } })) {
            Button("group.retry_now".localized) {
                if let e = partialDeliveryShown, let id = UUID(uuidString: e.msgId) {
                    groupManager.retryPendingDeliveryNow(messageId: id)
                }
                partialDeliveryShown = nil
            }
            Button("peers_done".localized, role: .cancel) { partialDeliveryShown = nil }
        } message: {
            Text(partialDeliveryAlertMessage)
        }
        .fullScreenCover(isPresented: $showMessageActions) {
            groupMessageActionsOverlay
        }
        .confirmationDialog("message.action.delete".localized,
                            isPresented: $showGroupDeleteSheet, titleVisibility: .visible) {
            if let m = actionMessage {
                Button("group.delete.for_me".localized, role: .destructive) {
                    groupManager.deleteGroupMessage(groupId: m.groupId, messageId: m.id)
                }
                Button("group.delete.for_everyone".localized, role: .destructive) {
                    groupManager.deleteGroupMessageForEveryone(
                        groupId: m.groupId,
                        messageId: m.id,
                        senderPublicKey: m.senderPublicKey
                    )
                }
            }
            Button("common.cancel".localized, role: .cancel) { }
        }
        // __GROUP_EDIT_WAS_STILL_AN_ALERT_2026_11_05__ The same sheet 1:1 uses.
        //
        // This was a `UIAlertController` with a single-line `TextField` — the
        // exact shape the 1:1 editor was replaced for on 2026-09-12 after "the
        // box to modify my text is not user friendly when the text is a bit
        // long". Groups were left behind: no wrapping, horizontal scrolling, a
        // caret you cannot aim, and no sight of the message you are editing.
        //
        // It also carried the old SILENT failure that fix removed — Save ran
        // `guard !groupEditingText.isEmpty else { return }`, so saving an empty
        // field closed the alert and told the user nothing. `MessageEditSheet`
        // disables Save instead, and refuses an unchanged body too, which
        // stopped a no-op edit from burning a mesh round-trip and stamping a
        // misleading "edited" marker on everyone's copy.
        .sheet(isPresented: $showGroupEditSheet) {
            MessageEditSheet(originalText: groupEditingOriginal) { newContent in
                guard let m = actionMessage else { return }
                groupManager.editGroupMessageForEveryone(
                    groupId: m.groupId,
                    messageId: m.id,
                    newContent: newContent,
                    senderPublicKey: m.senderPublicKey
                )
            }
        }
        .alert("message.action.translate".localized, isPresented: $showGroupTranslationResult) {
            Button("common.ok".localized, role: .cancel) { }
            if !MessagePrivacy.blockForwardAndCopy {
                Button("message.action.copy".localized) {
                    // __CLIPBOARD_HYGIENE_2026_10_01__ message text: this device only, 60 s
                    SecureClipboard.copy(groupTranslatedText)
                }
            }
        } message: {
            Text(groupTranslatedText)
        }
        .sheet(isPresented: $showGroupForwardPicker) {
            if let m = actionMessage {
                ForwardContactPickerView(
                    isPresented: $showGroupForwardPicker,
                    messageContent: resolveText(for: m) ?? ""
                ) { recipient in
                    forwardGroupMessage(to: recipient, m)
                }
            }
        }
        .sheet(isPresented: $showWallpaperPicker) {
            // Without the group permission: this device only (see GroupOverflowMenu).
            WallpaperPickerView(conversationId: group.id.uuidString,
                                broadcast: permGroup.canChangeWallpaper)
        }
        .sheet(isPresented: $showDatePicker) {
            DatePickerSheet(selectedDate: $selectedDate, messages: loadedMessages,
                            onSelectMessage: { messageId in scrollToMessageId = messageId })
        }
        .sheet(isPresented: $showDateSearch) { groupDateSearchSheet }
        .sheet(isPresented: $showGroupInfo) { GroupInfoView(groupId: group.id, chatActions: groupInfoChatActions) }
        // 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ see ChatView: media viewers,
        // notice rows and the capture shield read this.
        .advancedChatPrivacy(isOn: isAdvancedPrivacyOn, toggle: {
            groupManager.setAdvancedChatPrivacy(!isAdvancedPrivacyOn, groupId: group.id,
                                                senderPublicKey: identityManager.publicKey)
        })
        .sheet(isPresented: $showAddMember) { AddMemberView(group: group) }
        .sheet(isPresented: $showMediaPicker) {
            // __ATTACH_PANEL_2027_2026_11_17__ "All photos" / "Choose from
            // library" open the system library directly (no permission needed),
            // one or several, like 1:1. Picks are staged in the composer as before.
            MediaPickerView(allowMultipleSelection: true, autoOpenLibrary: true,
                            onMediaSelected: { mediaManagerAttachment in
                                DispatchQueue.main.async { selectedMedia.append(mediaManagerAttachment); showMediaPicker = false }
                            },
                            onMultipleMediaSelected: { picked in
                                DispatchQueue.main.async { selectedMedia.append(contentsOf: picked); showMediaPicker = false }
                            })
        }
        .fullScreenCover(isPresented: $showGroupCamera) {
            GroupMediaCaptureSheet { attachment, caption in sendGroupMediaWithCaption(attachment, caption: caption) }
        }
        .fullScreenCover(isPresented: $showGroupVideoNote) {
            VideoNoteRecorderOverlay(
                onSend: { data, meta in sendGroupVideoNote(data, meta: meta) },
                onClose: {
                    var t = Transaction()
                    t.disablesAnimations = true
                    withTransaction(t) { showGroupVideoNote = false }
                })
            .presentationBackground(.clear)
        }
    }

    // MARK: - Sheets Group B (extracted for type-checker)
    private var contentWithSheetsB: some View {
        contentWithSheetsA
        .sheet(item: $webLinkMessage) { m in
            ChannelWebPostSheet(message: m, handle: liveGroup.settings?.handle ?? "",
                                isAdmin: GroupSettingsPolicy.isAdmin(identityManager.publicKey, in: liveGroup))
        }
        .sheet(isPresented: $showContactPicker) {
            ContactSelectorView { publicKey, alias in
                sendGroupContactCard(publicKey: publicKey, alias: alias)
            }
        }
        .sheet(isPresented: $showGifPicker) {
            StickerGifPicker { attachment in
                sendGroupGif(attachment)
            }
        }
        .sheet(isPresented: $showDocumentPicker) {
            DocumentPicker { url in
                let picked = try? Data(contentsOf: url)
                MediaPlaintextTemp.removeIfOwned(url)  // __PLAINTEXT_LEFTOVERS_2026_09_24__ lue → supprimée
                if let data = picked {
                    let attachment = MediaManager.MediaAttachment(
                        id: UUID().uuidString, type: .document, data: data, thumbnailData: nil,
                        fileName: url.lastPathComponent, fileSize: data.count, timestamp: Date(),
                        originalSize: nil, compressionRatio: nil, isCompressed: false, isViewOnce: false
                    )
                    selectedMedia.append(attachment)
                }
                showDocumentPicker = false
            }
        }
        .sheet(isPresented: $showDocumentShare, onDismiss: {
            // __MEDIA_AT_REST_2026_09_22__ Copie en clair supprimée après le
            // partage (délai: une activité peut encore lire le fichier juste
            // après la fermeture); le balayage au lancement rattrape le reste.
            if let url = documentShareURL {
                documentShareURL = nil
                DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 60) { MediaPlaintextTemp.remove(url) }
            }
        }) {
            if let url = documentShareURL {
                ShareSheet(items: [url])
            }
        }
        .fullScreenCover(item: $fullScreenImageItem) { imageItem in
            MediaFullScreenView(attachment: MediaManager.MediaAttachment(
                id: UUID().uuidString,
                type: .image,
                data: imageItem.data,
                thumbnailData: nil,
                fileName: "photo.jpg",
                fileSize: imageItem.data.count,
                timestamp: Date(),
                originalSize: nil,
                compressionRatio: nil,
                isCompressed: false
            ), isFromCurrentUser: imageItem.isMine)
            // 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ this cover sits outside the
            // `.advancedChatPrivacy` modifier, so the switch is handed in here.
            .environment(\.advancedChatPrivacy, AdvancedChatPrivacyContext(isOn: isAdvancedPrivacyOn))
        }
        .sheet(isPresented: $showLocationPicker) {
            LocationShareSheet(onShareLocation: { sendLocationMessage($0) },
                               conversationId: group.id.uuidString,
                               recipientName: group.name)
        }
        .sheet(isPresented: $showScheduledMessage) {
            CreateScheduledMessageView(isPresented: $showScheduledMessage, conversationId: group.id.uuidString,
                                       conversationName: liveGroup.name, conversationContext: conversationContextForAI ?? "")
        }
        .sheet(isPresented: $showCheckIn) {
            CheckInSheet(contactPublicKey: group.id.uuidString, contactName: liveGroup.name) { checkInMessage in
                messageText = checkInMessage.toMessageString()
                sendMessage()
            }
        }
        // 🪙 Send bitcoin (Cashu). The sheet states that everyone here can
        // redeem it, so pressing Send in it IS the group confirmation.
        .sheet(item: $cashuDraft) { draft in
            CashuSendSheet(draft: draft, isGroup: true) { raw in sendCashuToken(raw) }
        }
        // 📊 Sondage de groupe — même feuille que dans un 1:1.
        .sheet(isPresented: $showPoll) {
            PollComposerSheet(
                onSend: { poll in
                    messageText = poll.toMessageString()
                    sendMessage()
                },
                creatorPublicKey: identityManager.publicKey
            )
        }
        .sheet(isPresented: $showScheduledCall) {
            CreateScheduledCallView(conversationId: group.id.uuidString, conversationName: liveGroup.name) { event in
                if let d = try? JSONEncoder().encode(event), let s = String(data: d, encoding: .utf8) {
                    messageText = "📅SCHEDULED_CALL📅\(s)"
                    sendMessage()
                }
            }
        }
        .sheet(isPresented: $showAudioPreview) {
            if let audio = recordedAudio {
                AudioPreviewSheet(audioAttachment: audio,
                                  onSend: { sendAudioMessage($0); showAudioPreview = false; recordedAudio = nil },
                                  onCancel: { showAudioPreview = false; recordedAudio = nil })
            }
        }
    }

    // MARK: - Sheets Group C (extracted for type-checker)
    private var contentWithSheetsC: some View {
        contentWithSheetsB
        .sheet(item: $slashEditorBot) { bot in
            BotCommandsEditorSheet(bot: bot)
        }
        .sheet(isPresented: $showAIShortcuts) {
            AIShortcutsView(messageText: $messageText, isPresented: $showAIShortcuts, replyingToMessage: conversationContextForAI)
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showingBlockSheet) {
            BlockGroupSheet(groupId: group.id, groupName: group.name, onBlock: { })
        }
        // 🚩 Report group (App Store guideline 1.2). Reporting offers to block
        // in the same flow; the block stays owned by BlockedContactsManager.
        .sheet(isPresented: $showingReportSheet) {
            ReportSheet(
                subject: .group,
                subjectId: group.id.uuidString,
                subjectName: group.name,
                onReported: { alsoBlock in
                    if alsoBlock {
                        blockedManager.blockGroup(groupId: group.id, groupName: group.name, reason: nil)
                    }
                }
            )
        }
        .alert("group.unblock_title".localized, isPresented: $showingUnblockAlert) {
            Button("common.cancel".localized, role: .cancel) { }
            Button("group.unblock_button".localized, role: .destructive) { blockedManager.unblockGroup(groupId: group.id) }
        } message: {
            Text(String(format: "group.unblock_message".localized, group.name))
        }
        .alert(isAdmin ? "confirm_delete_group".localized : "confirm_leave_group".localized,
               isPresented: $showLeaveGroupAlert) {
            Button("common.cancel".localized, role: .cancel) { }
            Button(isAdmin ? "button_delete_group".localized : "button_leave_group".localized, role: .destructive) {
                if isAdmin { groupManager.deleteGroup(group.id) }
                else { groupManager.leaveGroup(group.id, userPublicKey: identityManager.publicKey) }
                dismiss()
            }
        } message: {
            Text(isAdmin ? "confirm_delete_message".localized : "confirm_leave_message".localized)
        }
    }

    var body: some View {
        contentWithSheetsC
        .fullScreenCover(isPresented: $showFullMapPanel) {
            GroupFullMapPanel(
                latitude: fullMapLatitude,
                longitude: fullMapLongitude,
                address: fullMapAddress,
                isLive: fullMapIsLive,
                isPresented: $showFullMapPanel
            )
        }
        .fullScreenCover(isPresented: $showMapView) {
            NavigationStack {
                OfflineMapView(presentedModally: true)
            }
        }
        .fullScreenCover(isPresented: $showAIChatView) {
            OfflineAITabView()
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("SendScheduledMessage"))) { notification in
            if let recipient = notification.userInfo?["recipient"] as? String,
               let content = notification.userInfo?["content"] as? String,
               recipient == group.id.uuidString {
                messageText = content
                sendMessage()
            }
        }
        // ✅ FIX: Listen for NewGroupMessage notification — sync loadedMessages for scroll tracking
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("NewGroupMessage"))) { notification in
            // Accept ALL NewGroupMessage notifications for this group — don't rely on object cast
            let isForThisGroup: Bool
            if let message = notification.object as? GroupMessage {
                isForThisGroup = message.groupId == group.id
            } else {
                // Fallback: refresh anyway (cast may fail for struct boxing edge cases)
                isForThisGroup = true
            }
            if isForThisGroup {
                loadMessages()
            }
        }
        // ✅ FIX: Also listen for ReceivedGroupMessageFromIPFS (covers IPFS path)
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("ReceivedGroupMessageFromIPFS"))) { notification in
            if let message = notification.userInfo?["groupMessage"] as? GroupMessage,
               message.groupId == group.id {
                // Delay slightly to let receiveGroupMessage() finish storing
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                    loadMessages()
                }
            }
        }
        // ✅ FIX: Refresh when group metadata changes (member sync, member added, etc.)
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("GroupUpdated"))) { notification in
            if let groupIdStr = notification.userInfo?["groupId"] as? String,
               groupIdStr == group.id.uuidString {
                // Reload messages in case member list change unblocked pending messages
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                    loadMessages()
                }
            }
        }
        // ✅ FIX #5: Debounced objectWillChange. Previously every send collapsed 10+
        // rapid objectWillChange notifications into 10+ loadMessages() calls, causing
        // 400-600ms bursts of layout work during IPFS catch-up. 200 ms window is
        // imperceptible but collapses a burst into a single reload.
        // NOTE: Send-side uses direct loadMessages() call in sendMessage(), so user
        // input is not affected by this debounce (see Risk register).
        .onReceive(groupManager.objectWillChange) { _ in
            reloadDebounceTask?.cancel()
            reloadDebounceTask = Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(200))
                if !Task.isCancelled {
                    loadMessages()
                }
            }
        }
        // __GROUP_LIST_RENDER_LOOP_2026_08_24__ The filter inputs now have to say
        // so. While `displayMessages` computed the search pipeline live, typing in
        // the search field refreshed the list for free — at the cost of running
        // that pipeline once per rendered row. Now that the result is cached in
        // `displayMessagesCache`, these four are the ONLY things that can
        // invalidate it, and without them a search would type into a list that
        // never changed. Every other refresh path already calls `loadMessages()`.
        .onChange(of: filterSignature) { _, _ in loadMessages() }
        // __FOLDABLE_LAYOUT_2026_11_16__ Bubble caps follow THIS view's width.
        .providesChatColumnWidth($groupColumnWidth)
        .onAppear {
            // __BOTS_PHASE1_2026_11_24__ the remote bots' command lists, for the `/` menu.
            Task { await GroupBotDirectory.shared.refreshCommands(in: group.id) }
            isViewVisible = true
            attachGroupHandoffIfNeeded()
            #if DEBUG
            // __LONG_SEND_LANDING_2026_09_28__ `-OSHISendLong`: send a long
            // multi-paragraph message through the real `sendMessage()`.
            if ProcessInfo.processInfo.arguments.contains("-OSHISendLong") {
                let para = "This is a long pasted paragraph that wraps over many lines in the bubble, "
                    + "the kind of message that is taller than the whole screen once it is sent. "
                let body = (1...14).map { "Paragraph \($0). " + String(repeating: para, count: 3) }
                    .joined(separator: "\n\n") + "\n\nEND OF THE LONG MESSAGE"
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { messageText = body }
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.8) { sendMessage() }
            }
            // __FILTERED_RESULTS_WINDOW_2026_11_16__ `-OSHIOpenSearch`: open the
            // search bar for the scroll lab's search test.
            if ProcessInfo.processInfo.arguments.contains("-OSHIOpenSearch") {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { showSearch = true }
            }
            // __GROUP_OPEN_ANCHOR_2026_09_23__ `-OSHIGroupReadHistory`: at +4 s jump
            // to an old message, as a reader scrolling up would, so the harness
            // can check a late message does not pull the view back down.
            if ProcessInfo.processInfo.arguments.contains("-OSHIGroupReadHistory") {
                DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                    if displayMessages.count > 10 { scrollToMessageId = displayMessages[10].id }
                }
            }
            #endif
            // 🔔 UNREAD FIX: tell GroupManager this group is on screen so messages
            // arriving while we're viewing are marked read on arrival (no unread badge).
            groupManager.activeGroupId = group.id
            loadMessages()

            // 🔧 2026-06-10 (b49): arriving from a recent group-notification
            // tap → surface the loading pill + fire a poll burst so the new
            // message is fetched ASAP rather than waiting up to 2 s for the
            // periodic IPFS poll cycle.
            if PendingNotificationDeepLink.shared.wasTappedRecently(
                forGroup: group.id.uuidString, within: 6
            ) {
                withAnimation(.easeOut(duration: 0.2)) {
                    showLoadingNewMessagePill = true
                }
                loadingPillHideTask?.cancel()
                let task = DispatchWorkItem {
                    withAnimation(.easeIn(duration: 0.3)) {
                        showLoadingNewMessagePill = false
                    }
                }
                loadingPillHideTask = task
                DispatchQueue.main.asyncAfter(deadline: .now() + 8, execute: task)
                // Aggressive poll burst — first IPFS fetch typically lands in
                // 0.5-1.5 s; we kick three of them so the message arrives as
                // close to the banner as possible.
                MessageManager.shared?.checkPendingIPFSMessages()
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                    MessageManager.shared?.checkPendingIPFSMessages()
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                    MessageManager.shared?.checkPendingIPFSMessages()
                }
            }

            // 🔧 2026-06-11 (b57): the old instant scrollTo(lastMessage) +
            // 0.15/0.5s deferred shots are GONE. On iOS 18+ they disengaged
            // the system bottom anchor mid-settle (the black-on-open bug);
            // on iOS 17 the verified retry loop in ScrollViewReader.onAppear
            // now covers late layout settle with ground truth instead of
            // blind timing.

            // 🚀 OPEN-LAG FIX (round 2): consolidate ALL post-render work into a
            // single detached task so the first frame lands without ANY blocking
            // call competing for the main thread. Previously markGroupAsRead
            // (50 ms), syncBotsForGroup, autoShareProfileLinksInGroup, and
            // requestMemberSync were spread across 3 entry points and at least
            // one of them (auto-share / member-sync) ran inline on main, firing
            // NotificationCenter broadcasts + queueing IPFS messages BEFORE the
            // chat had finished laying out — visible as a 200-500 ms hitch
            // between the tap and the chat appearing.
            Task.detached(priority: .userInitiated) {
                // IPFS catch-up first (covers the user's actual goal: see new messages).
                MessageManager.shared?.checkPendingIPFSMessages()

                // 🚀 FIX (2026-08-05, ~1s open lag): wait for a REAL signal, not a
                // guess. The 120 ms sleep here was a bet that the first frame had
                // landed; when it had not — a long thread, a cold image cache, a
                // slower device — everything below ran DURING layout, and it
                // contains a network call and a message send.
                //
                // `Task.yield()` inside a run-loop hop returns after the current
                // render pass has been committed, so this resumes when the chat is
                // actually on screen rather than after an arbitrary delay. On a
                // fast open it is quicker than 120 ms; on a slow one it waits as
                // long as it genuinely takes.
                await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                    DispatchQueue.main.async { c.resume() }
                }
                await Task.yield()

                // Only what the user's next action depends on runs here.
                await MainActor.run {
                    groupManager.markGroupAsRead(group.id)
                }

                // 🚀 Everything below is HOUSEKEEPING — a bot registration, a
                // profile-link broadcast, a member-sync request. None of it is
                // needed for the chat the user is now looking at, and two of them
                // hit the network or send a message. Held back so the open path
                // owns the first second uncontested.
                try? await Task.sleep(for: .milliseconds(900))
                await MainActor.run {
                    BotManager.shared.syncBotsForGroup(group.id)
                    if let myKey = identityManager.publicKey as String?, !myKey.isEmpty {
                        BotManager.shared.notifyServerMemberJoined(groupId: group.id, memberPublicKey: myKey)
                    }
                    autoShareProfileLinksInGroup()
                    if let currentGroup = groupManager.groups.first(where: { $0.id == group.id }),
                       currentGroup.members.count <= 3,
                       currentGroup.adminPublicKey != identityManager.publicKey {
                        groupManager.requestMemberSync(groupId: group.id)
                    }
                }
            }
        }
        // ✅ CRITICAL FIX: Mirror ChatView's .onChange(of:) pattern for reactive message updates
        // ChatView uses .onChange(of: messageManager.messages) — same pattern adapted for group dictionary
        .onChange(of: groupManager.groupMessages[group.id]?.count ?? 0) { _, _ in
            loadMessages()
        }
        // ✅ FIX: Auto-scroll to new messages only when user is at the bottom
        .onChange(of: loadedMessages.count) { oldCount, newCount in
            // 🔧 2026-06-09: ONLY mark as read while the view is actually on-screen.
            // Without this guard the badge silently disappeared the moment a new
            // message landed because SwiftUI keeps the chat view in memory after
            // the user navigates back to the chat list, and .onChange fires for
            // every dictionary mutation regardless of visibility.
            if isViewVisible {
                groupManager.markGroupAsRead(group.id)
            }
            // showScrollToBottom is toggled by the last-message .onAppear/.onDisappear —
            // when it's false, the last bubble is visible, i.e. we're near the bottom.
            // 🔧 FIX (2026-08-20): settle, don't fire one shot at an estimated
            // extent — see the matching note on `displayMessages.count` above.
            if newCount > oldCount, !showScrollToBottom {
                settleScrollToBottom(scrollProxy, to: "groupBottomAnchor",
                                                 isAtBottom: { bottomAnchorSighted })
            }
            // ✅ AI: Generate smart replies when a new message arrives from someone else.
            // Skip system/protocol content (call summaries, receipts…) so raw
            // localization keys like "call.no.answer" never reach the LLM prompt.
            if newCount > oldCount,
               // 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ no model run on a protected group.
               AdvancedChatPrivacyPolicy.allowsAI(protected: isAdvancedPrivacyOn),
               let lastMsg = loadedMessages.last,
               lastMsg.senderPublicKey != identityManager.publicKey,
               lastMsg.systemMessageType == nil,
               let content = lastMsg.plaintextContent ?? groupManager.decryptMessage(lastMsg),
               !content.isEmpty {
                // 🔧 FIX (2026-08-10, "JSON showing on messages") landed HERE —
                // and this is the SMART-REPLY PROMPT filter. It decides what
                // gets sent to the LLM; it does not render a single pixel. That
                // is why adding the missing prefixes here did not stop the user
                // seeing raw JSON: the leak was in the bubble, preview, quote
                // and notification paths, none of which consulted this array.
                //
                // Kept (a control payload really must not reach the LLM), now
                // reading the one catalog instead of a 16th private copy.
                let isSystem = OSHIControlPayload.isControl(content)
                if !isSystem {
                    Task {
                        await nlpManager.generateSmartReplies(to: content, conversationContext: conversationContextForAI)
                    }
                } else if !nlpManager.smartReplies.isEmpty {
                    nlpManager.smartReplies = []
                }
            }
        }
        .onDisappear {
            ThreadHandoff.shared.detach(handoffKey, token: handoffToken)
            handoffAttached = false
            // 🔧 2026-06-09: pair with the .onAppear isViewVisible toggle so
            // new messages arriving after the user leaves this view DON'T get
            // silently marked as read by the .onChange(loadedMessages.count)
            // handler above.
            isViewVisible = false
            // 🔔 UNREAD FIX: clear the active-group marker, but only if it still
            // points at us — when navigating A→B, B's .onAppear runs before A's
            // .onDisappear, so a blind clear would wipe B's marker.
            // (Unwrapped first: the Optional<UUID> == UUID form is at the type
            // checker's time limit inside this modifier chain under Xcode 27.)
            if let active = groupManager.activeGroupId, active == group.id {
                groupManager.activeGroupId = nil
            }
            // 🔧 b57: stop the verified-landing scroll loop when leaving.
            openScrollRetryTask?.cancel()
        }
        // 📍 Handle "Share mine" location from LocationMessageBubble
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("SendLocationFromBubble"))) { notification in
            if let locationMsg = notification.userInfo?["locationMessage"] as? LocationMessage {
                sendLocationMessage(locationMsg)
            }
        }
        // 🤖 __BOT_RUNTIME_OWNS_DELIVERY_2026_08_16__ (étape 2) The bot reply used
        // to be SENT FROM HERE, which meant a bot could only answer while this
        // view existed. Delivery now belongs to `BotManager.emit`, in the manager
        // layer; the message arrives through the normal group funnel like any
        // other, so this view has nothing left to do about it.
        // 💬 Handle reply notification from ReactionOverlay
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("ReplyToMessage"))) { notification in
            if let reply = notification.userInfo?["replyMessage"] as? ReplyMessage {
                replyingToMessage = reply
            }
        }
        // Scroll to the quoted message when the quoted preview is tapped.
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("ScrollToOriginalGroupMessage"))) { notification in
            if let idString = notification.userInfo?["messageId"] as? String, let id = UUID(uuidString: idString) {
                scrollToMessageId = id
            }
        }
        // Group typing broadcast bridge — the manager posts this; we forward it on the mesh/IPFS transport.
        // __XCODE27_2026_11_18__ A named method: inline, Xcode 27.2 gives up
        // type-checking this view body ("unable to type-check in reasonable time").
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("SendGroupTypingStatus")),
                   perform: forwardGroupTypingStatus)
        .alert("alert.error".localized,
               isPresented: Binding(get: { sendErrorMessage != nil },
                                    set: { if !$0 { sendErrorMessage = nil } })) {
            Button("common.ok".localized, role: .cancel) { sendErrorMessage = nil }
        } message: {
            Text(sendErrorMessage ?? "")
        }
        // __CASHU_2026_10_25__ One confirmation before money reaches a room. A
        // dialog rather than making the user retype the message with the word
        // `public`: the point is that they understand what is about to happen,
        // not that they prove it by typing. The amount is named, because "a
        // token" and "47,000 sat" are not the same decision.
        .alert(NSLocalizedString("cashu_group_confirm_title", comment: "Confirm sending eCash to a group"),
               isPresented: Binding(get: { cashuAboutToGoPublic != nil },
                                    set: { if !$0 { cashuAboutToGoPublic = nil } })) {
            Button(NSLocalizedString("cashu_group_confirm_send", comment: "Send the token anyway"), role: .destructive) {
                if let pending = cashuAboutToGoPublic {
                    // __CASHU_SEND_READY_2026_11_09__ Text cleared only if it left.
                    if let line = sendCashuToken(pending) {
                        sendErrorMessage = line
                    } else {
                        messageText = ""
                    }
                }
                cashuAboutToGoPublic = nil
            }
            Button("common.cancel".localized, role: .cancel) { cashuAboutToGoPublic = nil }
        } message: {
            let summary = CashuTokens.find(in: cashuAboutToGoPublic ?? "")
                .map { $0.token.displayAmount }.joined(separator: ", ")
            Text(String(format: NSLocalizedString("cashu_group_confirm_body",
                                                  comment: "Everyone in the group can redeem it"), summary))
        }
        // __BOT_NO_BOT_HINT_2026_08_17__ Un bandeau, pas une alerte: la personne
        // vient d'envoyer un message, elle n'a rien fait de faux, et une modale
        // qu'il faut fermer punirait une commande tapée de bonne foi.
        .overlay(alignment: .bottom) {
            if showNoBotHint {
                noBotHintBanner
                    .padding(.horizontal, 14)
                    .padding(.bottom, 78)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .sheet(isPresented: $showBotManagement) {
            NavigationView { BotManagementView() }
        }
        // __BOT_INLINE_KEYBOARD_2026_09_16__ The toast for a `bot_callback_answer`,
        // the alert for one with `showAlert`, and the sheet for a `url` or
        // `web_app` button. Hosted by the CHAT: a bubble in a lazy stack can be
        // torn down while its own sheet is open.
        .botPlatformSurfaces()
    }

    /// Group typing broadcast bridge — the manager posts this; we forward it on the mesh/IPFS transport.
    private func forwardGroupTypingStatus(_ notification: Notification) {
        guard let info = notification.userInfo,
              let gid = info["groupId"] as? String,
              gid == group.id.uuidString,
              let isTyping = info["isTyping"] as? Bool else { return }
        let senderName = info["senderName"] as? String
        groupManager.sendGroupTypingStatus(group.id, isTyping: isTyping, senderName: senderName)
    }

    /// __BOT_NO_BOT_HINT_2026_08_17__
    private var noBotHintBanner: some View {
        HStack(spacing: 10) {
            Image(systemName: "cpu")
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(.white.opacity(0.9))

            Text(NSLocalizedString(
                "bot.hint.none_in_group",
                value: "No bot is listening in this group yet.",
                comment: "Shown once after someone sends a /command in a group that has no bot"))
                .font(.footnote)
                .foregroundColor(.white)
                .fixedSize(horizontal: false, vertical: true)

            Spacer(minLength: 6)

            Button {
                withAnimation { showNoBotHint = false }
                showBotManagement = true
            } label: {
                Text("bot.management_title".localized)
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(Color.white.opacity(0.22)))
            }
            .buttonStyle(.plain)

            Button {
                withAnimation { showNoBotHint = false }
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(.white.opacity(0.8))
                    .frame(width: 24, height: 24)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("common.close".localized))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.black.opacity(0.82))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(Color.white.opacity(0.12), lineWidth: 1))
                .shadow(color: .black.opacity(0.3), radius: 12, y: 4)
        )
        .frame(maxWidth: 520)
    }

    // MARK: - Group Date Search Sheet
    
    private var groupDateSearchSheet: some View {
        DateSearchView(
            isPresented: $showDateSearch,
            selectedDate: $selectedSearchDate,
            dateRangeStart: $dateRangeStart,
            dateRangeEnd: $dateRangeEnd,
            onDateSelected: { date in
                scrollToFirstGroupMessageOnDate(date)
            },
            onRangeSelected: { start, end in
                scrollToFirstGroupMessageInRange(start: start, end: end)
            },
            onClearFilter: {
                clearGroupDateFilter()
            }
        )
    }
    
    // MARK: - Group Date Search Helpers
    
    private func scrollToFirstGroupMessageOnDate(_ date: Date) {
        if let firstMessage = loadedMessages.first(where: { msg in
            Calendar.current.isDate(msg.timestamp, inSameDayAs: date)
        }) {
            scrollToMessageId = firstMessage.id
        }
    }
    
    private func scrollToFirstGroupMessageInRange(start: Date, end: Date) {
        if let firstMessage = loadedMessages.first(where: { msg in
            msg.timestamp >= start && msg.timestamp <= end
        }) {
            scrollToMessageId = firstMessage.id
        }
    }
    
    private func clearGroupDateFilter() {
        selectedSearchDate = nil
        dateRangeStart = nil
        dateRangeEnd = nil
    }
    
    // Removed old-style setupMessageListener() — now using .onReceive Combine publishers
    // which auto-clean on view disappear (no observer leak)
    
    /// True when `messages` is already oldest-first (O(n), no allocation).
    static func isChronological(_ messages: [GroupMessage]) -> Bool {
        var i = 1
        while i < messages.count {
            if messages[i].timestamp < messages[i - 1].timestamp { return false }
            i += 1
        }
        return true
    }

    private func loadMessages() {
        // ✅ PERF: cap loadedMessages to the visible window. Previously this sorted the FULL
        // history on every notification (200-300 ms on groups with thousands of messages).
        // displayMessages already truncates to messageDisplayLimit for rendering, but
        // loadedMessages was kept full for .onChange handlers — those only need a tail
        // count, not every historical message. Sort only the most-recent window.
        let raw = groupManager.groupMessages[group.id] ?? []
        let noFilter = searchText.isEmpty && selectedSearchDate == nil && dateRangeStart == nil
        let newMessages: [GroupMessage]
        // __CHAT_OPEN_PERF_2026_11_14__ The store is kept in arrival order, which
        // is chronological in the common case: then the window is just the tail,
        // no O(n log n) sort of every message (GroupMessage is a large struct).
        if noFilter && raw.count > messageDisplayLimit && Self.isChronological(raw) {
            newMessages = Array(raw.suffix(messageDisplayLimit))
        } else if noFilter && raw.count > messageDisplayLimit {
            // Sort descending then keep the newest k and flip — same big-O on the sort
            // (still O(n log n)) but the resulting array is bounded to k = messageDisplayLimit,
            // so every downstream pass (.onChange, smart-replies, scroll target lookups) is
            // O(k) instead of O(n). Matches the truncate-before-sort logic in filteredMessages.
            let sortedDesc = raw.sorted { $0.timestamp > $1.timestamp }
            newMessages = Array(sortedDesc.prefix(messageDisplayLimit)).reversed()
        } else {
            newMessages = raw.sorted { $0.timestamp < $1.timestamp }
        }
        // Always update — SwiftUI will diff efficiently via ForEach(id:)
        loadedMessages = newMessages
        // ✅ OPEN-LAG FIX: precompute bot-merge once per loadMessages tick so every body
        // pass (and every onAppear / scrollProxy lookup) reads it from @State without
        // running the O(n) merge again.
        //
        // __GROUP_LIST_RENDER_LOOP_2026_08_24__ The FILTERED path now lands here
        // too. It used to be evaluated live inside `displayMessages` — which is
        // read once per rendered row — so the search pipeline ran over the whole
        // history once per row. On the filtered path `newMessages` is already
        // `raw` sorted ascending (the `else` branch above), which is exactly what
        // the pipeline wants, so this also drops a redundant second sort.
        let noFilterNow = searchText.isEmpty && selectedSearchDate == nil && dateRangeStart == nil
        if noFilterNow {
            filteredMatchCount = 0
            displayMessagesCache = mergeBotMessagePairs(newMessages)
        } else {
            // __FILTERED_RESULTS_WINDOW_2026_11_16__ The list is an eager stack
            // (`ChatMessageStack`): every match used to be laid out at once, so a
            // date filter or a common word over a long group built the whole
            // history in one pass. The newest `messageDisplayLimit` matches are
            // drawn, "load earlier" pages back through the rest — the thread's
            // own rule, applied to the results.
            let matches = computeFilteredMessages(from: newMessages)
            filteredMatchCount = matches.count
            displayMessagesCache = mergeBotMessagePairs(
                matches.count > messageDisplayLimit ? Array(matches.suffix(messageDisplayLimit)) : matches)
        }
        messageListVersion += 1
    }
    
    /// Load `message` into the composer as the thing being replied to.
    ///
    /// Reuses `ReplyMessage` and the existing `MessageWithReply` wire format, so
    /// a group reply travels exactly like a 1:1 one and older builds that do not
    /// know about swipe still render it.
    private func beginReply(to message: GroupMessage) {
        // 🔧 RAW-JSON FIX: `resolveText` hands back LOCATION / SCHEDULED_CALL /
        // CHECK_IN payloads RAW on purpose, so `cleanBubble` can route them to
        // their dedicated bubbles — which means swiping to reply on one quoted
        // its JSON. A quote is a preview, so it goes through the catalog even
        // though the bubble does not.
        let body = OSHIControlPayload.previewText(for: resolveText(for: message) ?? "")
        replyingToMessage = ReplyMessage(
            originalMessageId: message.id.uuidString,
            originalSenderKey: message.senderPublicKey,
            // The quoted preview is truncated here rather than at render time:
            // a reply to a 4000-character message must not carry all of it into
            // every future copy of the thread.
            originalText: String(body.prefix(180)),
            originalTimestamp: message.timestamp,
            originalMediaType: message.mediaType?.rawValue
        )
    }

    /// Forward `message` to another conversation.
    private func forwardGroupMessage(to recipientPublicKey: String, _ message: GroupMessage) {
        guard let identity = MessageManager.sharedIdentityManager,
              let mesh = MessageManager.sharedMeshManager,
              let mm = MessageManager.shared else { return }
        let body = MessageActionsManager.shared.createForwardedMessage(
            originalContent: OSHIControlPayload.previewText(for: resolveText(for: message) ?? ""),
            originalSenderName: senderDisplayName(message.senderPublicKey)
        )
        mm.sendMessage(content: body,
                       recipientAddress: recipientPublicKey,
                       identityManager: identity,
                       meshManager: mesh)
    }

    /// __CASHU_SEND_READY_2026_11_09__ The one place a Cashu token leaves the
    /// group composer. Returns the refusal to show, or nil once sent.
    private func sendCashuToken(_ raw: String) -> String? {
        let outcome = groupManager.sendMessage(to: group.id, content: raw,
                                               senderPublicKey: identityManager.publicKey)
        guard outcome.didSend else {
            return CashuPayCommand.refusalMessage(for: outcome) ?? "alert.error".localized
        }
        return nil
    }

    private func sendMessage() {
        guard !messageText.isEmpty || !selectedMedia.isEmpty else { return }

        // __CASHU_2026_10_24__ `/pay <token> [public]` in a GROUP, and the
        // `isGroup: true` is the whole difference: everyone who can read this
        // thread can spend the token, and the first to redeem wins. So the
        // plain form does not send — it names the amount that was about to be
        // exposed and asks for the word `public`. One extra word against
        // giving a stranger money by reflex.
        // __CASHU_2026_10_25__ However the token reached the composer — pasted,
        // forwarded, typed — a bearer token in a group is spendable by everyone
        // who can read the thread. `/pay` asked before sending; a guard only one
        // way of typing honours is not a guard.
        if !CashuPayCommand.handles(messageText), CashuTokens.containsToken(messageText) {
            cashuAboutToGoPublic = messageText
            return
        }
        if CashuPayCommand.handles(messageText) {
            let outcome = CashuPayCommand.parse(messageText, isGroup: true)
            // `/pay` alone opens the send sheet rather than explaining syntax.
            if case .usage = outcome {
                cashuDraft = CashuSendDraft()
                messageText = ""
                return
            }
            if case let .send(token, _) = outcome {
                // __CASHU_SEND_READY_2026_11_09__ The outcome used to be
                // discarded: a refused token cleared the box and said "Sent".
                if let refusal = sendCashuToken(token.raw) {
                    sendErrorMessage = refusal
                    return
                }
                messageText = ""
            }
            // A refusal keeps what was typed: a token is tedious to paste
            // twice, and the user has to see it to fix it.
            // Reuses this view's own send-error alert: one place where a
            // composer refusal appears, not two that can drift apart.
            if let line = CashuPayCommand.message(for: outcome) {
                sendErrorMessage = line
            }
            return
        }

        OshiLog.messaging.info("📤 Sending message with \(selectedMedia.count) attachment(s)")

        // 💬 Wrap with reply metadata if replying
        let messageContent: String
        if let reply = replyingToMessage {
            messageContent = MessageWithReply(content: messageText, replyTo: reply).toMessageString()
        } else {
            messageContent = messageText
        }

        if !selectedMedia.isEmpty {
            // Send each media attachment as a separate message
            for attachment in selectedMedia {
                let mediaType = convertToGroupMediaType(attachment.type)
                groupManager.sendMessage(
                    to: group.id,
                    content: messageContent,
                    senderPublicKey: identityManager.publicKey,
                    mediaData: attachment.data,
                    mediaType: mediaType,
                    mediaFileName: attachment.fileName
                )
                OshiLog.messaging.info("✅ Media message sent: \(attachment.fileName) (\(attachment.data.count / 1024) KB)")
            }
        } else {
            // Text only message
            let outcome = groupManager.sendMessage(
                to: group.id,
                content: messageContent,
                senderPublicKey: identityManager.publicKey
            )
            // Refused (rate limit / too long even split): keep the text in the
            // box and tell the user why. Never a silent drop.
            guard outcome.didSend else {
                sendErrorMessage = outcome.userFacingMessage ?? "alert.error".localized
                return
            }
        }

        OshiLog.messaging.info("✅ Message sent successfully")

        // Process bot triggers for this message
        let sentText = messageText
        messageText = ""
        selectedMedia = []
        replyingToMessage = nil  // 💬 Clear reply state
        nlpManager.smartReplies = []  // ✅ Clear smart replies after sending
        // Send an immediate "stopped typing" so peers clear the indicator right away.
        TypingIndicatorManager.shared.userStoppedTyping(in: group.id.uuidString, senderName: UserDefaults.standard.string(forKey: "userName") ?? "")

        // ✅ FIX: Force immediate reload — read directly from manager.
        // ✅ PERF: use the windowed loadMessages() instead of re-sorting the full history
        // (was O(n log n) with n in the thousands — visible hitch right after sending).
        loadMessages()
        OshiLog.messaging.info("📤 Post-send: \(loadedMessages.count) messages, version=\(messageListVersion)")

        // __BOT_RUNTIME_OWNS_DELIVERY_2026_08_16__ (étape 2) The trigger hook moved
        // to `MessageGroupManager.sendMessage`, which every send goes through —
        // including the ones this view knows nothing about.

        // __BOT_NO_BOT_HINT_2026_08_17__ …but a command typed into a group with no
        // bot still deserves an answer, and the answer is "there is no bot here".
        // The app ships a `/help` TEMPLATE, so the command looks like something
        // the app should handle; nothing said otherwise until now.
        // __BOTS_PHASE1_2026_11_24__ A typed `/cmd` also reaches the group's
        // REMOTE bots (`kind:"command"`, first line only — never the thread).
        let dispatched = GroupBotDirectory.shared.dispatchIfCommand(sentText, groupId: group.id,
                                                                    owned: BotManager.shared.bots)
        if dispatched.isEmpty, GroupBotDirectory.shared.bots(in: group.id).isEmpty,
           BotManager.shared.shouldShowNoBotHint(for: sentText, groupId: group.id) {
            BotManager.shared.markNoBotHintShown(groupId: group.id)
            withAnimation(.easeInOut(duration: 0.25)) { showNoBotHint = true }
        }
    }
    
    // 🔗 Auto-share profile links to group members via group message
    private func autoShareProfileLinksInGroup() {
        let linksManager = ProfileLinksManager.shared
        guard linksManager.myLinks.hasAnyLink else { return }
        if let message = linksManager.autoShareMessageIfNeeded(for: group.id.uuidString) {
            groupManager.sendMessage(
                to: group.id,
                content: message,
                senderPublicKey: identityManager.publicKey
            )
            OshiLog.messaging.info("🔗 Auto-shared profile links in group \(liveGroup.name)")
        }
    }

    // ✅ NEW: Send media with caption from camera capture
    /// __GROUP_GIF_PICKER_2026_11_16__ A GIF from the picker goes out through
    /// the ordinary group media path, bytes untouched (`GroupGifSend`).
    private func sendGroupGif(_ attachment: MediaManager.MediaAttachment) {
        guard !isGroupBlocked, let media = GroupGifSend.groupMedia(attachment) else { return }
        groupManager.sendMessage(
            to: group.id,
            content: "",
            senderPublicKey: identityManager.publicKey,
            mediaData: media.data,
            mediaType: media.type,
            mediaFileName: media.fileName
        )
        OshiLog.messaging.info("🎞️ GIF sent to group: \(media.fileName) \(media.data.count / 1024) KB")
        loadMessages()
    }

    /// __GROUP_ATTACH_PARITY_2026_11_16__ Same card bytes as the 1:1 path
    /// (`MediaManager.createContactCard`: `{"publicKey","alias","timestamp"}`).
    private func sendGroupContactCard(publicKey: String, alias: String?) {
        guard !isGroupBlocked else { return }
        Task {
            do {
                let card = try await MediaManager().createContactCard(publicKey: publicKey, alias: alias)
                await MainActor.run {
                    groupManager.sendMessage(
                        to: group.id,
                        content: "",
                        senderPublicKey: identityManager.publicKey,
                        mediaData: card.data,
                        mediaType: .contact,
                        mediaFileName: card.fileName
                    )
                    loadMessages()
                }
            } catch {
                OshiLog.messaging.info("❌ Group contact card error: \(error)")
            }
        }
    }

    private func sendGroupMediaWithCaption(_ attachment: MediaManager.MediaAttachment, caption: String) {
        let mediaType = convertToGroupMediaType(attachment.type)
        groupManager.sendMessage(
            to: group.id,
            content: caption,  // ✅ Include caption with media
            senderPublicKey: identityManager.publicKey,
            mediaData: attachment.data,
            mediaType: mediaType,
            mediaFileName: attachment.fileName
        )
        OshiLog.messaging.info("✅ Camera media sent with caption: \(attachment.fileName)")
        loadMessages()
    }
    
    // 📍 NEW: Send location message
    // 📍 NEW: Send location message
    private func sendLocationMessage(_ locationMessage: LocationMessage) {
        let messageContent = locationMessage.toMessageString()
        
        // For live location updates, the groupManager.sendMessage will handle updating
        // existing message instead of creating new one
        groupManager.sendMessage(
            to: group.id,
            content: messageContent,
            senderPublicKey: identityManager.publicKey
        )
        
        if locationMessage.isUpdate {
            OshiLog.messaging.info("📍 Live location UPDATE sent to group")
        } else {
            OshiLog.messaging.info("📍 Location message sent to group")
        }
        
        loadMessages()
    }
    
    private func convertToGroupMediaType(_ type: MediaManager.MediaType) -> MediaType {
        switch type {
        case .image: return .photo
        // __GIF_2026_09_12__ Le type de groupe n'a pas de cas « gif »; un GIF y
        // voyage donc comme une photo, ce qui est exact côté transport.
        case .gif: return .photo
        case .video: return .video
        case .audio: return .audio
        case .document: return .document
        case .contact: return .contact
        }
    }
    
    // ✅ Audio recording button - Fixed for iPad compatibility
    private var audioRecordButton: some View {
        // __COMPOSER_PILL_2026_11_23__ the Google bar's voice button: a quiet
        // tonal circle beside the pill; red with a stop glyph while recording.
        ComposerRoundButtonFace(systemImage: isRecordingAudio ? "stop.fill" : "mic.fill",
                                primary: false,
                                recording: isRecordingAudio,
                                tint: groupBrandColor,
                                enabled: !isGroupBlocked)
            .contentShape(Circle())
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(isRecordingAudio ? "audio.recording".localized : "audio.hold_to_record".localized))
            .accessibilityAddTraits(.isButton)
            .allowsHitTesting(true)  // ✅ Explicitly enable hit testing for iPad
            .onTapGesture {
                if !isRecordingAudio {
                    recordingMode = .tapToRecord
                    startRecording()
                } else if recordingMode == .tapToRecord {
                    stopAndSendRecording()
                }
            }
            .onLongPressGesture(minimumDuration: 0.3, maximumDistance: 50) {
                // On long press release
                if !isRecordingAudio {
                    recordingMode = .holdToRecord
                    startRecording()
                }
            } onPressingChanged: { pressing in
                // During long press
                if !pressing && recordingMode == .holdToRecord && isRecordingAudio {
                    stopAndSendRecording()
                }
            }
    }
    
    private func startRecording() {
        guard !isRecordingAudio else { return }
        
        Task {
            do {
                try audioRecorder.startRecording()
                await MainActor.run {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        isRecordingAudio = true
                    }
                }
            } catch {
                OshiLog.messaging.info("❌ Failed to start recording: \(error)")
            }
        }
    }
    
    private func stopAndSendRecording() {
        guard isRecordingAudio else { return }
        
        Task {
            do {
                let attachment = try await audioRecorder.stopRecording()
                await MainActor.run {
                    // ✅ Show preview with voice effects instead of sending directly
                    recordedAudio = attachment
                    showAudioPreview = true
                    withAnimation(.easeInOut(duration: 0.2)) {
                        isRecordingAudio = false
                    }
                }
            } catch {
                await MainActor.run {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        isRecordingAudio = false
                    }
                    OshiLog.messaging.info("❌ Failed to stop recording: \(error)")
                }
            }
        }
    }
    
    private func sendAudioMessage(_ audio: AudioRecorderManager.AudioAttachment) {
        // Send actual audio data
        let audioType: MediaType = .audio
        groupManager.sendMessage(
            to: group.id,
            content: "",
            senderPublicKey: identityManager.publicKey,
            mediaData: audio.data,
            mediaType: audioType,
            mediaFileName: "audio_\(UUID().uuidString.prefix(8)).m4a"
        )
        OshiLog.messaging.info("✅ Audio message sent: \(audio.data.count / 1024) KB")
        loadMessages()
    }
    
    private func formatDuration(_ duration: TimeInterval?) -> String {
        guard let duration = duration else { return "0:00" }
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
    
    private func convertMediaType(_ type: MediaManager.MediaType) -> MediaManager.MediaType {
        switch type {
        case .image: return .image
        case .gif: return .image
        case .video: return .video
        case .audio: return .audio
        case .document: return .document
        case .contact: return .contact
        }
    }
    
    private func getMimeType(for type: MediaManager.MediaType) -> String {
        switch type {
        case .image: return "image/jpeg"
        // Le MIME doit rester `image/gif`, sinon le GIF arrive figé.
        case .gif: return "image/gif"
        case .video: return "video/mp4"
        case .audio: return "audio/m4a"
        case .document: return "application/octet-stream"
        case .contact: return "application/json"
        }
    }
}

// MARK: - Lazy Media Decode Views (Fix #2)
// These views move UIImage(data:) / generateVideoThumbnail off the View.body
// evaluation path. The decode runs inside .task { } on a detached priority-.userInitiated
// Task, and the result is set on MainActor. A ProgressView placeholder shows until ready.
// Sizes match the previous inline implementations.

fileprivate struct LazyGroupPhotoView: View {
    /// Message id: the cache key. __CHAT_OPEN_PERF_2026_11_14__ The decode used
    /// to restart from scratch on every visit to the group (no cache) and the
    /// task was keyed on the media `Data` itself, compared byte for byte on
    /// every body pass.
    let id: String
    let data: Data
    let maxDimension: CGFloat
    /// __BUBBLE_MEDIA_2026_11_21__ The display size from `BubbleMediaLayout`.
    /// The picture fills it (`scaledToFill`, centre-cropped when its ratio is
    /// out of range) and the placeholder takes it too, so nothing jumps.
    /// nil = the old free-sizing path (unused by the live bubble).
    var size: CGSize? = nil
    let onTap: (() -> Void)?
    @State private var image: UIImage?

    init(id: String, data: Data, maxDimension: CGFloat, size: CGSize? = nil, onTap: (() -> Void)?) {
        self.id = id
        self.data = data
        self.maxDimension = maxDimension
        self.size = size
        self.onTap = onTap
    }

    var body: some View {
        let shown = image ?? ChatImageLoader.shared.cached(id: id, maxDimension: maxDimension)
        SwiftUI.Group {
            // __GROUP_GIF_2026_09_27__ The group media enum has no `.gif` (a GIF travels
            // as `photo` from both platforms), and `safeUIImage` below decodes the FIRST
            // frame only. Ask the bytes, as the 1:1 bubble does.
            if MediaManager.isGIFData(data) {
                GIFBubbleView(data: data, maxWidth: size?.width ?? 260)
                    .frame(width: size?.width, height: size?.height)
                    .clipped()
                    .onTapGesture { onTap?() }
            } else if let shown, let size {
                Image(uiImage: shown)
                    .resizable()
                    .scaledToFill()
                    .frame(width: size.width, height: size.height)
                    .clipped()
                    .contentShape(Rectangle())
                    .onTapGesture { onTap?() }
                    .transition(.opacity)
            } else if let shown {
                Image(uiImage: shown)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 260, maxHeight: 300)
                    .clipped()
                    .onTapGesture { onTap?() }
                    .transition(.opacity)
            } else {
                Rectangle()
                    .fill(Color.primary.opacity(0.06))
                    .frame(width: size?.width ?? 240, height: size?.height ?? 200)
                    .overlay(ProgressView())
            }
        }
        .task(id: id) {
            if MediaManager.isGIFData(data) { return }
            if image != nil { return }
            if let hit = ChatImageLoader.shared.cached(id: id, maxDimension: maxDimension) { image = hit; return }
            let decoded = await ChatImageLoader.shared.load(id: id, data: data, maxDimension: maxDimension)
            withAnimation(.easeOut(duration: 0.2)) {
                self.image = decoded
            }
        }
    }
}

fileprivate struct LazyGroupVideoThumbnailView: View {
    let id: String
    let data: Data
    let fileName: String?
    /// __BUBBLE_MEDIA_2026_11_21__ Bubble width; the height follows the
    /// poster's ratio through `BubbleMediaLayout` (16:9 until it is known).
    var width: CGFloat? = nil
    @State private var thumbnail: UIImage?

    /// Poster cache key (decoded, in `MediaAttachmentCache`).
    private static let posterSide: CGFloat = 600

    private var boxSize: CGSize? {
        guard let width else { return nil }
        let poster = thumbnail ?? ChatImageLoader.shared.cached(id: id + "#gposter", maxDimension: Self.posterSide)
        return BubbleMediaLayout.displaySize(natural: poster?.size ?? CGSize(width: 16, height: 9),
                                             maxWidth: width).size
    }

    var body: some View {
        let shown = thumbnail ?? ChatImageLoader.shared.cached(id: id + "#gposter", maxDimension: Self.posterSide)
        ZStack {
            if let shown, let box = boxSize {
                Image(uiImage: shown)
                    .resizable()
                    .scaledToFill()
                    .frame(width: box.width, height: box.height)
                    .clipped()
                    .transition(.opacity)
            } else if let shown {
                Image(uiImage: shown)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 250, maxHeight: 200)
                    .cornerRadius(15)
                    .transition(.opacity)
            } else {
                Rectangle()
                    .fill(Color.primary.opacity(0.06))
                    .frame(width: boxSize?.width ?? 250, height: boxSize?.height ?? 150)
                    .overlay(ProgressView())
            }
            Image(systemName: "play.circle.fill")
                .font(.scaled(44))
                .foregroundColor(.white.opacity(0.9))
                .shadow(radius: 4)
        }
        .task(id: id) {
            if thumbnail != nil { return }
            if let hit = ChatImageLoader.shared.cached(id: id + "#gposter", maxDimension: Self.posterSide) { thumbnail = hit; return }
            let data = data
            let fileName = fileName
            let side = Self.posterSide
            // __CHAT_OPEN_PERF_2026_11_14__ Generated once per video and cached;
            // capped at 600 px (the bubble is 250 pt) instead of a full-size frame.
            let decoded = await ChatImageLoader.shared.load(id: id + "#gposter", maxDimension: Self.posterSide) {
                let name = fileName ?? "video.mp4"
                // __MEDIA_AT_REST_2026_09_22__ Copie protégée, supprimée juste après.
                guard let tempURL = try? MediaPlaintextTemp.write(data, fileName: name) else { return nil }
                defer { MediaPlaintextTemp.remove(tempURL) }
                let asset = AVAsset(url: tempURL)
                let generator = AVAssetImageGenerator(asset: asset)
                generator.appliesPreferredTrackTransform = true
                generator.maximumSize = CGSize(width: side, height: side)
                if let cgImage = try? generator.copyCGImage(at: .zero, actualTime: nil) {
                    return UIImage(cgImage: cgImage)
                }
                return nil
            }
            withAnimation(.easeOut(duration: 0.2)) {
                self.thumbnail = decoded
            }
        }
    }
}

// MARK: - UIImage Extension

extension UIImage {
    func resized(to size: CGSize) -> UIImage? {
        UIGraphicsBeginImageContextWithOptions(size, false, scale)
        defer { UIGraphicsEndImageContext() }
        draw(in: CGRect(origin: .zero, size: size))
        return UIGraphicsGetImageFromCurrentImageContext()
    }
}

// MARK: - Inline Group Audio Player

struct GroupAudioPlayerView: View {
    /// __FOLDABLE_LAYOUT_2026_11_16__ The thread's width (see `maxBubbleWidth(in:)`).
    @Environment(\.chatColumnWidth) private var chatColumnWidth
    let message: GroupMessage
    let isMine: Bool
    @Binding var useExternalSpeaker: Bool
    let groupBrandColor: Color
    let groupManager: MessageGroupManager
    // __VOICE_MINIPLAYER_2026_10_09__ Shared, app-lifetime player: a group
    // voice note now keeps playing when the user leaves the group, and the
    // floating mini player takes over its controls.
    @ObservedObject private var audioPlayer = VoiceNotePlayback.shared
    @State private var loadedData: Data?
    @State private var bars: [CGFloat] = []
    /// __VOICE_SCRUB_2026_10_26__ Measured width of the waveform strip, and the
    /// position the finger is holding while it drags. Unlike the 1-to-1 bubble
    /// this strip stretches (it shares an HStack with a Spacer), so the width
    /// has to be measured rather than assumed.
    @State private var stripWidth: CGFloat = 0
    @State private var scrubProgress: Double?
    @State private var isScrubbing: Bool?

    private var noteID: String { message.id.uuidString }
    private var isActive: Bool { audioPlayer.isActive(noteID) }
    private var isPlaying: Bool { isActive && audioPlayer.isPlaying }
    private var progress: Double {
        if let scrubProgress { return scrubProgress }
        return isActive ? audioPlayer.progress : 0
    }

    var body: some View {
        // __CHAT_OPEN_PERF_2026_11_14__ Was `loadedData ?? loadMedia()`: a
        // synchronous disk read + decrypt in the body until `onAppear`'s
        // background read landed. The bytes now arrive only from that read (or
        // the message itself); a tap before they do reads them once, on tap.
        let mediaData = loadedData ?? message.mediaData
        HStack(spacing: 10) {
            // Play/Pause button
            Button {
                if isPlaying {
                    audioPlayer.pause()
                } else if let data = mediaData ?? loadMedia() {
                    configureAudioOutput()
                    audioPlayer.play(id: noteID,
                                     title: senderLabel,
                                     data: data)
                }
            } label: {
                Image(systemName: isPlaying ? "pause.circle.fill" : "play.circle.fill")
                    .font(.scaled(32))
                    .foregroundColor(isMine ? BubbleStyleManager.sentAccentInk : groupBrandColor)
            }
            // Waveform visualization.
            //
            // Heights are derived from the audio bytes, not `CGFloat.random`:
            // random heights were re-rolled on every redraw, and now that the
            // shared player publishes a time tick ten times a second that
            // would be a permanent flicker. Deterministic bars also let the
            // played part of the note be filled in.
            HStack(spacing: 2) {
                ForEach(bars.indices, id: \.self) { i in
                    let frac = bars.count > 1 ? Double(i) / Double(bars.count - 1) : 0
                    let played = frac <= progress
                    RoundedRectangle(cornerRadius: 1)
                        .fill(isMine
                              ? BubbleStyleManager.sentAccentInk.opacity(played ? 1.0 : 0.40)
                              : groupBrandColor.opacity(played ? 1.0 : 0.35))
                        .frame(width: 2, height: max(4, bars[i] * 16))
                }
            }
            .frame(height: 20)
            .animation(.easeOut(duration: 0.15), value: progress)
            // __VOICE_SCRUB_2026_10_26__ Same scrubber as the 1-to-1 bubble: tap
            // to play from a point, drag to move the head, and a vertical drag
            // stays with the conversation's scroll.
            .contentShape(Rectangle())
            .background(
                GeometryReader { g in
                    Color.clear
                        .onAppear { stripWidth = g.size.width }
                        .onChange(of: g.size.width) { _, w in stripWidth = w }
                }
            )
            .gesture(
                SpatialTapGesture()
                    .onEnded { v in
                        #if os(iOS)
                        Haptics.tap(.light)
                        #endif
                        seek(toX: v.location.x, startIfIdle: true, data: mediaData)
                    }
            )
            .gesture(
                DragGesture(minimumDistance: 8)
                    .onChanged { v in
                        if isScrubbing == nil {
                            isScrubbing = abs(v.translation.width) > abs(v.translation.height)
                        }
                        guard isScrubbing == true else { return }
                        let p = clampedProgress(forX: v.location.x)
                        scrubProgress = p
                        seek(to: p, startIfIdle: false, data: mediaData)
                    }
                    .onEnded { v in
                        let wasScrubbing = isScrubbing == true
                        isScrubbing = nil
                        scrubProgress = nil
                        guard wasScrubbing else { return }
                        seek(toX: v.location.x, startIfIdle: false, data: mediaData)
                    }
            )
            Spacer()
            // Duration / file size
            if let data = mediaData {
                Text(ByteCountFormatter.string(fromByteCount: Int64(data.count), countStyle: .file))
                    .font(.caption2)
                    .foregroundColor(isMine ? BubbleStyleManager.sentSecondaryInk : ChatPalette.metaInk)
            }
        }
        .padding(12)
        // __BUBBLE_MEDIA_2026_11_21__ same surfaces as the text bubbles.
        .background(isMine ? BubbleStyleManager.sentBubbleColour(nil) : BubbleStyleManager.receivedBubbleColour)
        .cornerRadius(15)
        .frame(maxWidth: BubbleStyleManager.maxBubbleWidth(in: chatColumnWidth))
        .onAppear {
            audioPlayer.ownerAppeared(noteID)
            // 🔧 OPEN-LAG FIX (2026-06-02): loadMedia() is a synchronous file
            // read. With ~10 image bubbles each running it in onAppear on the
            // main thread, opening a media-heavy group blocked layout for
            // 100–500 ms. Move the disk read to a background queue; the bubble
            // renders immediately (with the placeholder/nil data), then swaps
            // to the loaded data when ready. mediaData inline fallback is fast
            // (in-memory) so we honor it first to avoid a flicker for the
            // already-decrypted-and-in-RAM case.
            if let inline = message.mediaData {
                loadedData = inline
            }
            let msgId = message.id.uuidString
            let mediaType = message.mediaType
            DispatchQueue.global(qos: .userInitiated).async { [weak gm = groupManager] in
                guard let mediaType = mediaType,
                      let gm = gm,
                      let data = gm.loadGroupMediaAttachment(for: msgId, type: mediaType) else { return }
                DispatchQueue.main.async {
                    loadedData = data
                }
            }
        }
        .onDisappear { audioPlayer.ownerDisappeared(noteID) }
        .onChange(of: loadedData) { _, newValue in
            guard let newValue else { return }
            bars = InlineVoiceNoteView.makeBars(from: newValue, count: 20)
        }
    }

    // MARK: - Scrubbing

    private func clampedProgress(forX x: CGFloat) -> Double {
        guard stripWidth > 0 else { return 0 }
        return min(1, max(0, Double(x / stripWidth)))
    }

    private func seek(toX x: CGFloat, startIfIdle: Bool, data: Data?) {
        seek(to: clampedProgress(forX: x), startIfIdle: startIfIdle, data: data)
    }

    private func seek(to p: Double, startIfIdle: Bool, data: Data?) {
        guard let data else { return }
        if startIfIdle, !isActive { configureAudioOutput() }
        audioPlayer.seek(id: noteID,
                         title: senderLabel,
                         data: data,
                         toProgress: p,
                         startIfIdle: startIfIdle)
    }

    /// Who the floating player names while this note plays outside the group.
    private var senderLabel: String {
        if isMine { return "audio.you".localized }
        let alias = groupManager.groups
            .flatMap(\.members)
            .first(where: { $0.publicKey == message.senderPublicKey })?
            .alias
        return alias ?? "audio.voice_message".localized
    }

    private func loadMedia() -> Data? {
        guard let mediaType = message.mediaType else { return nil }
        if let data = groupManager.loadGroupMediaAttachment(for: message.id.uuidString, type: mediaType) {
            return data
        }
        return message.mediaData
    }

    private func configureAudioOutput() {
        // Routage centralisé — voir `VoiceMessageAudioRoute`.
        VoiceMessageAudioRoute.shared.setPrefersSpeaker(useExternalSpeaker)
        VoiceMessageAudioRoute.shared.beginPlayback()
    }
}

// MARK: - Group Message Bubble

struct GroupMessageBubble: View {
    /// __FOLDABLE_LAYOUT_2026_11_16__ The thread's width (see `maxBubbleWidth(in:)`).
    @Environment(\.chatColumnWidth) private var chatColumnWidth
    let message: GroupMessage
    let isFromCurrentUser: Bool
    var isHighlighted: Bool = false
    @Binding var useExternalSpeaker: Bool  // ✅ Speaker/earpiece toggle
    @EnvironmentObject var groupManager: MessageGroupManager
    @State private var decryptedText: String?

    // ✅ CRITICAL FIX: Computed property for immediate text display
    // Does NOT depend on @State or .onAppear — always available from message data
    private var immediateText: String? {
        // Priority 1: plaintextContent (set by sender, bot, or processSimpleGroupMessage)
        if let plain = message.plaintextContent, !plain.isEmpty,
           !plain.hasPrefix("🔗LINKS_VISIBILITY🔗"),
           !plain.hasPrefix("🔧ACTION🔧") {
            return plain
        }
        // Priority 2: decode encryptedContent as UTF-8
        if !message.isSystemMessage,
           let text = String(data: message.encryptedContent, encoding: .utf8), !text.isEmpty,
           !text.hasPrefix("🔗LINKS_VISIBILITY🔗"),
           !text.hasPrefix("🔧ACTION🔧") {
            if text.starts(with: "{") && text.contains("\"type\":\"media\"") {
                return "" // media-only message
            }
            return text
        }
        return nil
    }

    // Effective text: decryptedText (from .onAppear) or immediateText (computed)
    private var effectiveText: String? {
        decryptedText ?? immediateText
    }

    init(message: GroupMessage, isFromCurrentUser: Bool, isHighlighted: Bool = false, useExternalSpeaker: Binding<Bool>, onPin: (() -> Void)? = nil, isPinned: Bool = false) {
        self.message = message
        self.isFromCurrentUser = isFromCurrentUser
        self.isHighlighted = isHighlighted
        self._useExternalSpeaker = useExternalSpeaker
        self.onPin = onPin
        self.isPinned = isPinned
    }
    @State private var loadedImage: UIImage?
    @State private var loadedMediaData: Data?
    @State private var isLoadingMedia = false
    @State private var mediaLoadError = false
    @State private var showingImageFullScreen = false
    
    // Audio playback
    @State private var isPlayingAudio = false
    @State private var audioProgress: Double = 0
    @StateObject private var audioPlayer = AudioPlayerManager()
    
    // Document viewer
    @State private var showingDocumentViewer = false
    
    // Video player
    @State private var showingVideoPlayer = false
    
    // ✅ Link preview support
    @StateObject private var linkPreviewFetcher = LinkPreviewFetcher()
    @State private var detectedURL: String?
    
    // 🔥 Reaction support
    @State private var showReactionPicker = false
    @State private var showTranslationResult = false
    @State private var translatedMessageText: String = ""
    @State private var showTranslationSheet = false
    @State private var translationSourceText: String = ""
    @State private var messageFrame: CGRect = .zero
    @ObservedObject private var reactionManager = MessageReactionManager.shared

    // ➡️ Forward message support
    @State private var showForwardPicker = false
    /// __GROUP_RECEIPTS_2026_11_02__ (N8)
    @State private var showReceiptsSheet = false

    /// Members of this message's group, as plain keys. Pulled out of the view
    /// builder for the same type-checking reason as `receiptsSheet` itself.
    private var receiptMemberKeys: [String] {
        let g = groupManager.groups.first { $0.id == message.groupId }
        return (g?.members ?? []).map { $0.publicKey }
    }

    @ViewBuilder
    private var receiptsSheet: some View {
        GroupReceiptsSheet(
            groupId: message.groupId,
            msgId: message.id.uuidString,
            members: receiptMemberKeys,
            sender: message.senderPublicKey,
            // The same name the thread shows, so the sheet and the bubbles
            // cannot disagree about what someone is called.
            displayName: { key in GroupContactAliases.displayName(key) ?? String(key.prefix(8)) },
            onDismiss: { showReceiptsSheet = false }
        )
    }
    @ObservedObject private var messageActionsManager = MessageActionsManager.shared
    @EnvironmentObject var messageManager: MessageManager
    @State private var showPrivateChat = false
    @State private var privateConversation: Conversation? = nil

    // 📌 Pin message support
    var onPin: (() -> Void)? = nil
    var isPinned: Bool = false

    // 🗑️ Delete message support (for me / for everyone)
    @State private var showDeleteSheet = false

    // ✏️ Edit message support
    @State private var showEditSheet = false
    @State private var editingText = ""

    private func openPrivateChat() {
        let senderKey = message.senderPublicKey
        let currentKey = groupManager.currentUserPublicKey ?? ""
        if let existing = messageManager.conversations.first(where: { $0.participantPublicKey == senderKey }) {
            privateConversation = existing
        } else {
            let emptyEncrypted = EncryptedMessage(
                ciphertext: "",
                signature: "",
                senderPublicKey: currentKey,
                timestamp: Date()
            )
            let placeholderMsg = SecureMessage(
                id: UUID().uuidString,
                senderAddress: currentKey,
                recipientAddress: senderKey,
                encryptedContent: emptyEncrypted,
                timestamp: Date(),
                isRead: true,
                deliveryStatus: .sent,
                senderPublicKey: currentKey,
                recipientPublicKey: senderKey
            )
            let placeholder = Conversation(
                participantAddress: senderKey,
                participantPublicKey: senderKey,
                lastMessage: placeholderMsg,
                unreadCount: 0
            )
            privateConversation = placeholder
        }
        if let text = effectiveText {
            let reply = messageActionsManager.createReply(
                from: message.id.uuidString,
                senderKey: message.senderPublicKey,
                text: text,
                timestamp: message.timestamp,
                mediaType: message.mediaType?.rawValue
            )
            messageActionsManager.startReply(to: reply)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            showPrivateChat = true
        }
    }

    private var hasMedia: Bool {
        message.mediaType != nil
    }

    private var isBot: Bool {
        message.senderPublicKey.hasPrefix("bot:")
    }

    private var botDisplayName: String {
        // Extract bot name from plaintextContent "[BotName] text" or fallback
        if let plain = message.plaintextContent, plain.hasPrefix("[") {
            if let end = plain.firstIndex(of: "]") {
                return String(plain[plain.index(after: plain.startIndex)..<end])
            }
        }
        return "Bot"
    }

    /// For bot messages, strip the "[BotName] " prefix from displayed text
    private var botStrippedText: String? {
        guard let text = effectiveText else { return nil }
        if isBot, text.hasPrefix("["), let closeBracket = text.firstIndex(of: "]") {
            let afterBracket = text.index(after: closeBracket)
            let stripped = text[afterBracket...].drop(while: { $0 == " " })
            return stripped.isEmpty ? nil : String(stripped)
        }
        return text
    }
    
    var body: some View {
        VStack(spacing: 0) {
            if message.isSystemMessage {
                systemMessageView
            } else if effectiveText != nil || hasMedia {
                regularMessageView
            } else {
                // Fallback: show raw content as plain text bubble instead of invisible
                let rawText = message.plaintextContent ?? String(data: message.encryptedContent, encoding: .utf8)
                if let text = rawText, !text.isEmpty,
                   !text.hasPrefix("🔗LINKS_VISIBILITY🔗"),
                   !text.hasPrefix("🔧ACTION🔧") {
                    fallbackBubble(text: text)
                } else {
                    // Truly hidden (control message) or empty
                    Color.clear.frame(height: 0)
                }
            }
        }
        .onAppear {
            if decryptedText == nil && !message.isSystemMessage {
                decryptMessage()
            }
            loadMediaIfNeeded()
        }
        // __GROUP_RECEIPTS_2026_11_02__ (N8) Who received and who read this one.
        //
        // Attached to THIS body, not to `regularMessageView`'s modifier chain,
        // and that is not a style choice: that chain is long enough that adding
        // one more modifier tipped the whole view into "the compiler is unable
        // to type-check this expression in reasonable time". The body here is
        // small, so a sheet costs nothing, and the sheet's own contents live in
        // `receiptsSheet` for the same reason.
        .sheet(isPresented: $showReceiptsSheet) { receiptsSheet }
    }

    /// Lightweight fallback bubble — no modals, no reactions, no heavy modifiers
    private func fallbackBubble(text: String) -> some View {
        HStack {
            if isFromCurrentUser { Spacer() }
            VStack(alignment: isFromCurrentUser ? .trailing : .leading, spacing: 4) {
                Text(text)
                    .padding(12)
                    .background(isFromCurrentUser ? groupBrandColor : Color(.systemGray5))
                    .foregroundColor(isFromCurrentUser ? .white : .primary)
                    .cornerRadius(15)
                HStack(spacing: 5) {
                    if !isFromCurrentUser {
                        Text(formatAddress(message.senderPublicKey))
                            .font(.caption2)
                            .foregroundColor(.secondary)
                        Text("•")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                    Text(formatTimestamp(message.timestamp))
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }
            if !isFromCurrentUser { Spacer() }
        }
        .padding(8)
    }
    
    // MARK: - System Message View
    
    @ViewBuilder
    private var systemMessageView: some View {
        HStack {
            Spacer()
            
            HStack(spacing: 8) {
                if let type = message.systemMessageType {
                    Image(systemName: type.icon)
                        .font(.caption)
                        .foregroundColor(groupBrandColor)
                }
                
                Text(systemMessageText)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(
                Capsule()
                    .fill(Color(.systemGray6))
            )
            
            Spacer()
        }
        .padding(.vertical, 4)
    }
    
    private var systemMessageText: String {
        guard let type = message.systemMessageType,
              let data = message.systemMessageData else {
            return ""
        }
        
        // Get actor name (who performed the action)
        let actorName = getDisplayName(for: data.actorPublicKey)
        
        switch type {
        case .groupPictureChanged:
            return String(format: "group.system.picture_changed_by".localized, actorName)
        case .groupPictureRemoved:
            return String(format: "group.system.picture_removed_by".localized, actorName)
        case .memberJoined:
            if let target = data.targetPublicKey {
                let targetName = getDisplayName(for: target)
                return String(format: "group.system.member_joined_by".localized, targetName, actorName)
            }
            return String(format: "group.system.member_joined".localized, actorName)
        case .memberLeft:
            return String(format: "group.system.member_left".localized, actorName)
        case .memberRemoved:
            if let target = data.targetPublicKey {
                let targetName = getDisplayName(for: target)
                return String(format: "group.system.member_removed_by".localized, targetName, actorName)
            }
            return type.localizedDescription
        case .groupCreated:
            return String(format: "group.system.created_by".localized, actorName)
        case .groupNameChanged:
            return String(format: "group.system.name_changed_by".localized, actorName)
        case .adminChanged:
            if let target = data.targetPublicKey {
                let targetName = getDisplayName(for: target)
                return String(format: "group.system.admin_changed_to".localized, targetName)
            }
            return type.localizedDescription
        case .messagePinned:
            return String(format: "group.system.message_pinned_by".localized, actorName)
        case .messageUnpinned:
            return String(format: "group.system.message_unpinned_by".localized, actorName)
        case .wallpaperChanged:
            return String(format: "group.system.wallpaper_changed_by".localized, actorName)
        case .messageDeleted:
            return String(format: "group.system.message_deleted".localized, actorName)
        case .messageEdited:
            return String(format: "group.system.message_edited".localized, actorName)
        case .groupInfo:
            return message.plaintextContent ?? ""
        }
    }
    
    private func getDisplayName(for publicKey: String) -> String {
        // Try to get alias from contacts or use shortened key
        if publicKey == groupManager.currentUserPublicKey {
            return "you".localized
        }
        
        // Check if member has alias
        if let group = groupManager.getGroup(byId: message.groupId),
           let member = group.members.first(where: { $0.publicKey == publicKey }),
           let alias = member.alias {
            return alias
        }
        
        // Use shortened public key
        return String(publicKey.prefix(8)) + "..."
    }
    
    @ViewBuilder
    private func locationBubbleView(locationMsg: LocationMessage) -> some View {
        LocationMessageBubble(
            locationMessage: locationMsg,
            isFromCurrentUser: isFromCurrentUser,
            conversationId: message.groupId.uuidString,
            contactName: String(message.senderPublicKey.prefix(8)),
            contactPublicKey: message.senderPublicKey
        )
    }

    // MARK: - Regular Message View

    @ViewBuilder
    private var regularMessageView: some View {
        HStack {
            if isFromCurrentUser { Spacer() }
            
            VStack(alignment: isFromCurrentUser ? .trailing : .leading, spacing: 8) {
                // Show media content based on type
                if hasMedia {
                    mediaContentView
                }
                
                // Show text message (if any) - or location bubble
                if let text = botStrippedText, !text.isEmpty, text != " " {
                    // 📍 Check for location message
                    if text.hasPrefix(LocationMessage.messagePrefix),
                       let locationMsg = LocationMessage.fromMessageString(text) {
                        locationBubbleView(locationMsg: locationMsg)
                    } else {
                        // 💬 Parse reply format if present
                        let parsedMessage = MessageWithReply.fromMessageString(text)
                        // `?? text` printed the whole 💬REPLY💬{…} envelope when
                        // the JSON failed to decode — see the 1:1 bubble.
                        let displayText = OSHIControlPayload.previewText(for: parsedMessage?.content ?? text)
                        let replyInfo = parsedMessage?.replyTo

                        VStack(alignment: isFromCurrentUser ? .trailing : .leading, spacing: 4) {
                            // 💬 Reply quote box
                            if let reply = replyInfo {
                                HStack(spacing: 6) {
                                    RoundedRectangle(cornerRadius: 2)
                                        .fill(groupBrandColor)
                                        .frame(width: 3)

                                    // `ReplyQuoteBody` (not `originalText`): the
                                    // sanitised, truncated form — the group
                                    // quote box was the one reply surface still
                                    // printing the raw field — plus the picture
                                    // when the quoted message was a photo.
                                    ReplyQuoteBody(
                                        reply: reply,
                                        title: getDisplayName(for: reply.originalSenderKey),
                                        titleColor: ChatPalette.quoteAccent,
                                        textColor: ChatPalette.quoteBody
                                    )
                                }
                                .padding(8)
                                // __REPLY_QUOTE_ON_THE_WALLPAPER_2026_11_09__
                                // above the bubble, on the wallpaper: own surface.
                                .background(
                                    RoundedRectangle(cornerRadius: 8)
                                        .fill(ChatPalette.quoteSurface)
                                )
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    // Scroll the group chat to the original quoted message.
                                    NotificationCenter.default.post(
                                        name: NSNotification.Name("ScrollToOriginalGroupMessage"),
                                        object: nil,
                                        userInfo: ["messageId": reply.originalMessageId]
                                    )
                                }
                            }

                            // Single bubble: OG image on top + message text below
                            // Constrained to normal bubble width (max 280pt)
                            VStack(alignment: .leading, spacing: 0) {
                                // OG image header (tappable link)
                                if let url = detectedURL, let previewData = linkPreviewFetcher.previewData {
                                    Button {
                                        if let openURL = URL(string: url) {
                                            if !MapOpener.handleLink(openURL) { UIApplication.shared.open(openURL) } // place links → Settings › Maps
                                        }
                                    } label: {
                                        VStack(alignment: .leading, spacing: 0) {
                                            if let uiImage = linkPreviewFetcher.previewUIImage {
                                                Image(uiImage: uiImage)
                                                    .resizable()
                                                    .aspectRatio(contentMode: .fill)
                                                    .frame(height: 150)
                                                    .clipped()
                                            }
                                            VStack(alignment: .leading, spacing: 3) {
                                                if let siteName = previewData.siteName {
                                                    Text(siteName.uppercased())
                                                        .font(.caption2)
                                                        .foregroundColor(isFromCurrentUser ? .white.opacity(0.7) : .secondary)
                                                }
                                                Text(previewData.displayTitle)
                                                    .font(.scaled(14, weight: .semibold))
                                                    .foregroundColor(isFromCurrentUser ? .white : .primary)
                                                    .lineLimit(2)
                                                    .multilineTextAlignment(.leading)
                                            }
                                            .padding(.horizontal, 12)
                                            .padding(.top, 8)
                                            .padding(.bottom, 6)
                                        }
                                    }
                                    .buttonStyle(PlainButtonStyle())
                                } else if linkPreviewFetcher.isLoading && detectedURL != nil {
                                    LinkPreviewLoadingView()
                                        .padding(.bottom, 4)
                                }

                                // Message text — always visible in same bubble
                                Text(displayText)
                                    .foregroundColor(isFromCurrentUser ? .white : .primary)
                                    .multilineTextAlignment(.leading)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 10)
                            }
                            // 260 rather than 280. A bubble hugs its content, so
                            // this cap only bites on the LONG messages — and it is
                            // those that set the visual rhythm of the thread. At
                            // 280 they ran wide enough that a short reply beside
                            // them looked stranded; 260 keeps the long ones
                            // comfortable and the column calmer.
                            .frame(maxWidth: BubbleStyleManager.maxBubbleWidth(in: chatColumnWidth))
                            .background(isFromCurrentUser ? groupBrandColor : Color(.systemGray5))
                            .clipShape(RoundedRectangle(cornerRadius: 15))
                        }
                    }
                } else if !hasMedia && effectiveText == nil {
                    ProgressView()
                        .padding(12)
                        .background(Color(.systemGray6))
                        .cornerRadius(15)
                }
                
                // Metadata
                HStack(spacing: 5) {
                    if !isFromCurrentUser {
                        if message.senderPublicKey.hasPrefix("bot:") {
                            Image(systemName: "cpu")
                                .font(.caption2)
                                .foregroundColor(.blue)
                            Text(botDisplayName)
                                .font(.caption2)
                                .fontWeight(.medium)
                                .foregroundColor(.blue)
                            BotBadge()
                        } else {
                            Text(formatAddress(message.senderPublicKey))
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }

                        Text("•")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                    
                    if message.editedAt != nil {
                        Text("message.edited".localized)
                            .font(.caption2)
                            .italic()
                            .foregroundColor(.secondary)
                    }

                    Text(formatTimestamp(message.timestamp))
                        .font(.caption2)
                        .foregroundColor(.secondary)

                    // Delivery status for OUR own group messages — consistent
                    // with 1:1 (clock → check, tappable retry on failure). Groups
                    // have no per-member read receipts, so `.sent` is terminal.
                    if isFromCurrentUser, let status = message.deliveryStatus {
                        switch status {
                        case .pending:
                            Image(systemName: "clock")
                                .font(.scaled(9))
                                .foregroundColor(.secondary)
                        // Groups have no per-member read receipts, so a group
                        // tick can never HONESTLY claim "read" — one member
                        // opening it says nothing about the other nine. It stops
                        // at a single check on purpose: showing two, or green,
                        // would promise knowledge nobody has.
                        case .sent, .delivered, .read:
                            Image(systemName: "checkmark")
                                .font(.scaled(9, weight: .semibold))
                                .foregroundColor(.secondary)
                        case .failed:
                            Button {
                                MessageGroupManager.shared.retryGroupMessage(
                                    groupId: message.groupId, messageId: message.id)
                            } label: {
                                HStack(spacing: 2) {
                                    Image(systemName: "exclamationmark.circle.fill")
                                        .font(.scaled(10))
                                    Text("message.tap_to_retry".localized)
                                        .font(.scaled(9, weight: .medium))
                                }
                                .foregroundColor(.red)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                // 🔒 __BOT_E2E_2026_09_23__ Honest encryption caption for bot messages.
                if isBot, !isFromCurrentUser {
                    BotSealCaption(groupId: message.groupId)
                }

                // 🔥 Show reactions below message
                MessageReactionsView(
                    messageId: message.id.uuidString,
                    isFromCurrentUser: isFromCurrentUser
                )
            }
            
            if !isFromCurrentUser { Spacer() }
        }
        .padding(8)
        // 🔥 Long press for reaction picker
        .onLongPressGesture(minimumDuration: 0.5) {
            let generator = UIImpactFeedbackGenerator(style: .medium)
            generator.impactOccurred()
            showReactionPicker = true
        }
        .background(
            GeometryReader { geo in
                RoundedRectangle(cornerRadius: 12)
                    .fill(isHighlighted ? Color.yellow.opacity(0.3) : Color.clear)
                    .onAppear {
                        messageFrame = geo.frame(in: .global)
                    }
            }
        )
        .animation(.easeInOut(duration: 0.3), value: isHighlighted)
        .fullScreenCover(isPresented: $showReactionPicker) {
            ReactionOverlay(
                messageId: message.id.uuidString,
                senderPublicKey: groupManager.currentUserPublicKey ?? "",
                senderName: "you".localized,
                isPresented: $showReactionPicker,
                messageFrame: messageFrame,
                isFromCurrentUser: message.senderPublicKey == groupManager.currentUserPublicKey,
                onReact: { emoji in
                    let isRemoving = reactionManager.hasUserReacted(
                        messageId: message.id.uuidString,
                        senderPublicKey: groupManager.currentUserPublicKey ?? "",
                        emoji: emoji
                    )
                    reactionManager.toggleReaction(
                        to: message.id.uuidString,
                        emoji: emoji,
                        senderPublicKey: groupManager.currentUserPublicKey ?? "",
                        senderName: "you".localized
                    )
                    let payload = ReactionPayload(
                        messageId: message.id.uuidString,
                        emoji: emoji,
                        senderPublicKey: groupManager.currentUserPublicKey ?? "",
                        senderName: "you".localized,
                        timestamp: Date(),
                        action: isRemoving ? .remove : .add
                    )
                    groupManager.sendMessage(
                        to: message.groupId,
                        content: payload.toMessageString(),
                        senderPublicKey: groupManager.currentUserPublicKey ?? ""
                    )
                },
                messageContent: effectiveText,
                onForward: {
                    guard !MessagePrivacy.blockForwardAndCopy else { return }
                    // Show forward picker after dismissing reaction overlay
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                        showForwardPicker = true
                    }
                },
                onCopy: {
                    guard !MessagePrivacy.blockForwardAndCopy else { return }
                    if let text = effectiveText {
                        // Add OSHI website link at the end
                        let textWithLink = "\(text)\n\n— Sent via OSHI Messenger\nhttps://oshimessenger.com"
                        // __CLIPBOARD_HYGIENE_2026_10_01__ message text: this device only, 60 s
                        SecureClipboard.copy(textWithLink)
                        let generator = UINotificationFeedbackGenerator()
                        generator.notificationOccurred(.success)
                    }
                },
                onReply: {
                    if let text = effectiveText {
                        let reply = messageActionsManager.createReply(
                            from: message.id.uuidString,
                            senderKey: message.senderPublicKey,
                            text: text,
                            timestamp: message.timestamp,
                            mediaType: message.mediaType?.rawValue
                        )
                        messageActionsManager.startReply(to: reply)
                    }
                },
                onDelete: (message.senderPublicKey == groupManager.currentUserPublicKey) ? {
                    showDeleteSheet = true
                } : nil,
                onTranslate: {
                    let textToTranslate = effectiveText ?? ""
                    Task {
                        if let translated = await NLPManager.shared.processTranslateReceived(textToTranslate) {
                            await MainActor.run {
                                translatedMessageText = translated
                                showTranslationResult = true
                            }
                        } else {
                            await MainActor.run {
                                if #available(iOS 18.0, *) {
                                    translationSourceText = textToTranslate
                                    showTranslationSheet = true
                                } else {
                                    translatedMessageText = "ai.translate_failed".localized
                                    showTranslationResult = true
                                }
                            }
                        }
                    }
                },
                onReplyPrivate: message.senderPublicKey != groupManager.currentUserPublicKey ? { openPrivateChat() } : nil,
                onPin: onPin,
                pinLabel: isPinned ? "pinned.unpin_message".localized : "pinned.pin_message".localized,
                isPinnedMessage: isPinned,
                onEdit: (message.senderPublicKey == groupManager.currentUserPublicKey && Date().timeIntervalSince(message.timestamp) <= 600 && message.mediaType == nil) ? {
                    editingText = effectiveText ?? message.plaintextContent ?? ""
                    showEditSheet = true
                } : nil,
                // __GROUP_RECEIPTS_2026_11_02__ (N8) Offered ONLY on our own
                // message: a receipt is addressed to the sender, so for anyone
                // else's message this device has nothing to show, and a button
                // that opened an always-empty sheet would be a lie.
                onInfo: message.senderPublicKey == groupManager.currentUserPublicKey ? {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                        showReceiptsSheet = true
                    }
                } : nil
            )
            .background(ClearBackgroundView())
        }
        .alert("message.action.translate".localized, isPresented: $showTranslationResult) {
            Button("common.ok".localized, role: .cancel) { }
            if !MessagePrivacy.blockForwardAndCopy {
                Button("message.action.copy".localized) {
                    // __CLIPBOARD_HYGIENE_2026_10_01__ message text: this device only, 60 s
                    SecureClipboard.copy(translatedMessageText)
                }
            }
        } message: {
            Text(translatedMessageText)
        }
        .if_available_translation(sourceText: translationSourceText, isPresented: $showTranslationSheet)
        // ➡️ Forward contact picker sheet
        .sheet(isPresented: $showForwardPicker) {
            ForwardContactPickerView(
                isPresented: $showForwardPicker,
                messageContent: effectiveText ?? ""
            ) { recipientPublicKey in
                forwardMessage(to: recipientPublicKey)
            }
        }
        .sheet(isPresented: $showPrivateChat) {
            if let conversation = privateConversation {
                NavigationView {
                    ChatView(conversation: conversation)
                        .environmentObject(messageManager)
                }
            }
        }
        .sheet(isPresented: $showingDocumentViewer) {
            if let data = loadedMediaData {
                DocumentViewerSheet(data: data, fileName: message.mediaFileName ?? "document")
            }
        }
        .fullScreenCover(isPresented: $showingVideoPlayer) {
            if let data = loadedMediaData {
                VideoPlayerView(videoData: data)
            }
        }
        .fullScreenCover(isPresented: $showingImageFullScreen) {
            if let image = loadedImage {
                GroupImageFullScreenView(image: image)
            }
        }
        .confirmationDialog("message.action.delete".localized, isPresented: $showDeleteSheet, titleVisibility: .visible) {
            Button("group.delete.for_me".localized, role: .destructive) {
                groupManager.deleteGroupMessage(groupId: message.groupId, messageId: message.id)
            }
            Button("group.delete.for_everyone".localized, role: .destructive) {
                groupManager.deleteGroupMessageForEveryone(
                    groupId: message.groupId,
                    messageId: message.id,
                    senderPublicKey: message.senderPublicKey
                )
            }
            Button("common.cancel".localized, role: .cancel) { }
        }
        .alert("message.action.edit".localized, isPresented: $showEditSheet) {
            TextField("message.edit_placeholder".localized, text: $editingText)
                .privateTypingComposer()  // __KEYBOARD_PRIVACY_2026_10_01__ no autocorrect learning when Private typing is on
            Button("common.save".localized) {
                guard !editingText.isEmpty else { return }
                groupManager.editGroupMessageForEveryone(
                    groupId: message.groupId,
                    messageId: message.id,
                    newContent: editingText,
                    senderPublicKey: message.senderPublicKey
                )
            }
            Button("common.cancel".localized, role: .cancel) { }
        }
    }
    
    // MARK: - Media Content View
    
    @ViewBuilder
    private var mediaContentView: some View {
        switch message.mediaType {
        case .photo:
            imageView
        case .video:
            if message.isVideoNote {
                // __VIDEO_NOTE_2026_09_24__ round, inline, with sound.
                VideoNoteBubbleView(messageId: message.id.uuidString,
                                    videoData: loadedMediaData,
                                    durationMs: message.durationMs,
                                    isOutgoing: isFromCurrentUser,
                                    poster: loadedImage)
            } else {
                videoThumbnailView
            }
        case .audio:
            audioPlayerView
        case .document:
            documentView
        case .contact:
            contactView
        case .none:
            EmptyView()
        }
    }
    
    // MARK: - Image View
    
    @ViewBuilder
    private var imageView: some View {
        if isLoadingMedia {
            ProgressView()
                .frame(width: 200, height: 150)
                .background(Color(.systemGray6))
                .cornerRadius(12)
        } else if let image = loadedImage {
            Button {
                showingImageFullScreen = true
            } label: {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(maxWidth: 250, maxHeight: 250)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color.gray.opacity(0.2), lineWidth: 1)
                    )
            }
            .buttonStyle(.plain)
        } else if mediaLoadError {
            mediaErrorView
        }
    }
    
    // MARK: - Video Thumbnail View
    
    @ViewBuilder
    private var videoThumbnailView: some View {
        Button {
            showingVideoPlayer = true
        } label: {
            ZStack {
                if let image = loadedImage {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 200, height: 150)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                } else {
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color(.systemGray5))
                        .frame(width: 200, height: 150)
                }
                
                // Play button overlay
                Circle()
                    .fill(Color.black.opacity(0.6))
                    .frame(width: 50, height: 50)
                    .overlay(
                        Image(systemName: "play.fill")
                            .foregroundColor(.white)
                            .font(.title2)
                    )
            }
        }
    }
    
    // MARK: - Audio Player View
    
    @ViewBuilder
    private var audioPlayerView: some View {
        HStack(spacing: 12) {
            Button {
                toggleAudioPlayback()
            } label: {
                Image(systemName: audioPlayer.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                    .font(.scaled(40))
                    .foregroundColor(isFromCurrentUser ? .white : groupBrandColor)
            }
            
            // Waveform visualization
            HStack(spacing: 2) {
                ForEach(0..<20, id: \.self) { index in
                    RoundedRectangle(cornerRadius: 1)
                        .fill(isFromCurrentUser ? Color.white.opacity(0.7) : groupBrandColor.opacity(0.5))
                        .frame(width: 3, height: CGFloat.random(in: 8...25))
                }
            }
            .frame(height: 30)
            
            // File size instead of duration (AudioPlayerManager doesn't expose duration)
            if let data = loadedMediaData ?? message.mediaData {
                Text(formatFileSize(data.count))
                    .font(.caption)
                    .foregroundColor(isFromCurrentUser ? .white.opacity(0.8) : .secondary)
            }
        }
        .padding(12)
        .background(isFromCurrentUser ? groupBrandColor : Color(.systemGray5))
        .cornerRadius(15)
    }
    
    // MARK: - Document View
    
    @ViewBuilder
    private var documentView: some View {
        Button {
            showingDocumentViewer = true
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "doc.fill")
                    .font(.title2)
                    .foregroundColor(isFromCurrentUser ? .white : groupBrandColor)
                
                VStack(alignment: .leading, spacing: 2) {
                    Text(message.mediaFileName ?? "document.file".localized)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .foregroundColor(isFromCurrentUser ? .white : .primary)
                        .lineLimit(1)
                    
                    if let data = loadedMediaData ?? message.mediaData {
                        Text(formatFileSize(data.count))
                            .font(.caption)
                            .foregroundColor(isFromCurrentUser ? .white.opacity(0.7) : .secondary)
                    }
                }
                
                Spacer()
                
                Image(systemName: "arrow.down.circle")
                    .foregroundColor(isFromCurrentUser ? .white.opacity(0.7) : .secondary)
            }
            .padding(12)
            .background(isFromCurrentUser ? groupBrandColor : Color(.systemGray5))
            .cornerRadius(15)
            .frame(maxWidth: 250)
        }
    }
    
    // MARK: - Contact View
    
    @ViewBuilder
    private var contactView: some View {
        HStack(spacing: 12) {
            Image(systemName: "person.crop.circle.fill")
                .font(.title)
                .foregroundColor(isFromCurrentUser ? .white : groupBrandColor)
            
            Text("contact.shared".localized)
                .font(.subheadline)
                .foregroundColor(isFromCurrentUser ? .white : .primary)
        }
        .padding(12)
        .background(isFromCurrentUser ? groupBrandColor : Color(.systemGray5))
        .cornerRadius(15)
    }
    
    // MARK: - Error View
    
    private var mediaErrorView: some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle")
                .foregroundColor(.orange)
            Text("media.failed_load".localized)
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding(12)
        .background(Color(.systemGray6))
        .cornerRadius(12)
    }
    
    // MARK: - Helper Functions
    
    private func decryptMessage() {
        // First check plaintextContent (for sent messages)
        if let plaintext = message.plaintextContent, !plaintext.isEmpty {
            // 🔗 Process links visibility silently
            if plaintext.hasPrefix(ProfileLinksManager.messagePrefix) {
                ProfileLinksManager.shared.processIncomingVisibility(from: message.senderPublicKey, content: plaintext)
                decryptedText = nil
                return
            }
            if plaintext.hasPrefix("🔧ACTION🔧") {
                decryptedText = nil
                return
            }
            decryptedText = plaintext
            checkForURLs(in: plaintext)
            return
        }

        // Then try to decrypt
        if let plaintext = groupManager.decryptMessage(message) {
            // 🔗 Process links visibility silently
            if plaintext.hasPrefix(ProfileLinksManager.messagePrefix) {
                ProfileLinksManager.shared.processIncomingVisibility(from: message.senderPublicKey, content: plaintext)
                decryptedText = nil
                return
            }
            if plaintext.hasPrefix("🔧ACTION🔧") {
                decryptedText = nil
                return
            }
            // Check if it's a media JSON
            if plaintext.starts(with: "{") && plaintext.contains("\"type\":\"media\"") {
                // Parse media JSON to get text (if any)
                if let jsonData = plaintext.data(using: .utf8),
                   let _ = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any] {
                    // Media message - text might be empty
                    decryptedText = ""
                }
            } else {
                decryptedText = plaintext
                checkForURLs(in: plaintext)
            }
        } else {
            decryptedText = "[Unable to decrypt]"
        }
    }
    
    private func checkForURLs(in text: String) {
        if let url = text.detectFirstURL() {
            detectedURL = url
            linkPreviewFetcher.fetchPreview(for: url, allowNetwork: isFromCurrentUser) // __LEAK_FIX_2026_09_25__
        }
    }
    
    private func loadMediaIfNeeded() {
        guard hasMedia else { return }
        
        isLoadingMedia = true
        
        // First check if data is already in the message (for newly sent/received)
        if let data = message.mediaData, !data.isEmpty {
            OshiLog.messaging.info("📱 GroupMessage: Using in-memory media data (\(data.count / 1024) KB)")
            processMediaData(data)
            return
        }
        
        // Try to load from disk
        if let type = message.mediaType,
           let data = groupManager.loadGroupMediaAttachment(for: message.id.uuidString, type: type) {
            OshiLog.messaging.info("📱 GroupMessage: Loaded media from disk (\(data.count / 1024) KB)")
            processMediaData(data)
            return
        }

        // Fallback: extract embedded base64 media from encryptedContent JSON
        // This handles cases where media was received but not saved to disk (reinstall, etc.)
        if let contentString = String(data: message.encryptedContent, encoding: .utf8),
           contentString.contains("\"type\":\"media\""),
           let jsonData = contentString.data(using: .utf8),
           let mediaJson = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
           let base64Content = mediaJson["content"] as? String,
           let mediaData = Data(base64Encoded: base64Content) {
            OshiLog.messaging.info("📱 GroupMessage: Extracted media from encryptedContent (\(mediaData.count / 1024) KB)")
            // Save to disk for future loads
            if let type = message.mediaType {
                _ = groupManager.saveGroupMediaAttachmentPublic(mediaData, for: message.id.uuidString, type: type)
            }
            processMediaData(mediaData)
            return
        }

        OshiLog.messaging.info("❌ GroupMessage: Failed to load media for \(message.id), mediaType=\(message.mediaType?.rawValue ?? "nil"), encryptedContent size=\(message.encryptedContent.count), encryptedContent preview=\(String(data: message.encryptedContent.prefix(100), encoding: .utf8) ?? "binary")")
        // Last resort: try loading with common extensions
        if message.mediaType != nil {
            let fileManager = FileManager.default
            if let docsURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first {
                let mediaDir = docsURL.appendingPathComponent("GroupMedia")
                let extensions = ["jpg", "png", "jpeg", "mp4", "m4a", "dat", "vcf"]
                for ext in extensions {
                    let path = mediaDir.appendingPathComponent("\(message.id.uuidString).\(ext)")
                    if let data = try? MediaCipher.read(contentsOf: path), !data.isEmpty {  // __MEDIA_AT_REST_2026_09_22__
                        OshiLog.messaging.info("📱 GroupMessage: Found media with extension .\(ext) (\(data.count / 1024) KB)")
                        processMediaData(data)
                        return
                    }
                }
            }
        }
        isLoadingMedia = false
        mediaLoadError = true
    }
    
    private func processMediaData(_ data: Data) {
        loadedMediaData = data
        
        switch message.mediaType {
        case .photo:
            if let image = MediaManager.safeUIImage(from: data, maxDimension: 1920) {
                loadedImage = image
            } else {
                mediaLoadError = true
            }
        case .video:
            // Generate thumbnail
            generateVideoThumbnail(from: data)
        case .audio:
            // Audio data is loaded, will play on tap
            break
        case .document, .contact:
            // Data is loaded, will be used when viewing
            break
        case .none:
            break
        }
        
        isLoadingMedia = false
    }
    
    private func generateVideoThumbnail(from data: Data) {
        // Save temporarily to generate thumbnail
        // __MEDIA_AT_REST_2026_09_22__ Copie protégée (`MediaPlaintextTemp`).
        guard let tempURL = try? MediaPlaintextTemp.write(data, fileName: "video.mp4") else { return }
        defer { MediaPlaintextTemp.remove(tempURL) }

        let asset = AVAsset(url: tempURL)
        let imageGenerator = AVAssetImageGenerator(asset: asset)
        imageGenerator.appliesPreferredTrackTransform = true
        
        do {
            let cgImage = try imageGenerator.copyCGImage(at: .zero, actualTime: nil)
            loadedImage = UIImage(cgImage: cgImage)
        } catch {
            OshiLog.messaging.info("❌ Failed to generate video thumbnail: \(error)")
        }
        
        try? FileManager.default.removeItem(at: tempURL)
    }
    
    private func toggleAudioPlayback() {
        if let data = loadedMediaData ?? message.mediaData {
            // ✅ Configure audio output BEFORE playback — routage centralisé,
            // voir `VoiceMessageAudioRoute`. `AudioPlayerManager.play` appelle
            // lui-même `beginPlayback()`; on ne fixe ici que la préférence.
            VoiceMessageAudioRoute.shared.setPrefersSpeaker(useExternalSpeaker)

            audioPlayer.togglePlayback(data)
        }
    }
    
    private func formatAddress(_ address: String) -> String {
        guard address.count > 10 else { return address }
        return "\(address.prefix(6))..."
    }
    
    /// __BUBBLE_TIME_2026_08_17__ Même correctif que `chatFormatTimestamp`
    /// ci-dessus: l'heure, toujours. `GroupMessageBubble` n'est instancié NULLE
    /// PART aujourd'hui (le fil rend `cleanBubble`), mais le jour où on le
    /// rebranche il ne doit pas ramener le défaut avec lui.
    private func formatTimestamp(_ date: Date) -> String {
        OshiRowFormatters.string(date, format: "HH:mm")
    }
    
    private func formatDuration(_ duration: TimeInterval?) -> String {
        guard let duration = duration else { return "0:00" }
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
    
    private func formatFileSize(_ bytes: Int) -> String {
        if bytes < 1024 {
            return "\(bytes) B"
        } else if bytes < 1024 * 1024 {
            return String(format: "%.1f KB", Double(bytes) / 1024)
        } else {
            return String(format: "%.1f MB", Double(bytes) / (1024 * 1024))
        }
    }

    // MARK: - Forward Message Support

    private func getSenderAlias() -> String {
        if message.senderPublicKey == groupManager.currentUserPublicKey {
            return "you".localized
        }
        // Bot sender - show bot name
        if message.senderPublicKey.hasPrefix("bot:") {
            return botDisplayName
        }
        if let group = groupManager.getGroup(byId: message.groupId),
           let member = group.members.first(where: { $0.publicKey == message.senderPublicKey }),
           let alias = member.alias {
            return alias
        }
        return formatAddress(message.senderPublicKey)
    }

    private func forwardMessage(to recipientPublicKey: String) {
        guard let content = effectiveText else { return }

        // Create forwarded message with original sender info
        let senderName = getSenderAlias()
        let forwardedContent = messageActionsManager.createForwardedMessage(
            originalContent: OSHIControlPayload.previewText(for: content),
            originalSenderName: senderName
        )

        // Send to recipient via MessageManager using shared managers
        guard let messageManager = MessageManager.shared,
              let identityManager = MessageManager.sharedIdentityManager,
              let meshManager = MessageManager.sharedMeshManager else {
            OshiLog.messaging.info("❌ Cannot forward: Managers not available")
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.error)
            return
        }

        messageManager.sendMessage(
            content: forwardedContent,
            recipientAddress: recipientPublicKey,
            identityManager: identityManager,
            meshManager: meshManager
        )

        OshiLog.messaging.info("➡️ Forwarded message to: \(recipientPublicKey.prefix(8))...")

        // Haptic feedback for success
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.success)
    }
}

// Note: AudioPlayerManager is defined in AudioRecorderManager.swift

// MARK: - Document Viewer Sheet

struct DocumentViewerSheet: View {
    let data: Data
    let fileName: String
    @Environment(\.dismiss) var dismiss
    @State private var showShareSheet = false
    
    var body: some View {
        NavigationView {
            VStack {
                // Preview based on file type
                if fileName.lowercased().hasSuffix(".pdf") {
                    PDFKitView(data: data)
                } else {
                    // Generic document view
                    VStack(spacing: 20) {
                        Image(systemName: "doc.fill")
                            .font(.scaled(80))
                            .foregroundColor(groupBrandColor)
                        
                        Text(fileName)
                            .font(.headline)
                        
                        Text(formatFileSize(data.count))
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                        
                        Button {
                            showShareSheet = true
                        } label: {
                            Label("document.open_in".localized, systemImage: "square.and.arrow.up")
                                .font(.headline)
                                .foregroundColor(.white)
                                .padding()
                                .background(groupBrandColor)
                                .cornerRadius(10)
                        }
                    }
                    .padding()
                }
            }
            .navigationTitle(fileName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("common.close".localized) {
                        dismiss()
                    }
                }
                
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showShareSheet = true
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                    }
                }
            }
            .sheet(isPresented: $showShareSheet) {
                ShareSheet(items: [data])
            }
        }
    }
    
    private func formatFileSize(_ bytes: Int) -> String {
        if bytes < 1024 {
            return "\(bytes) B"
        } else if bytes < 1024 * 1024 {
            return String(format: "%.1f KB", Double(bytes) / 1024)
        } else {
            return String(format: "%.1f MB", Double(bytes) / (1024 * 1024))
        }
    }
}

// MARK: - Video Player View

struct VideoPlayerView: View {
    let videoData: Data
    @Environment(\.dismiss) var dismiss
    @State private var player: AVPlayer?
    @State private var tempURL: URL?  // __MEDIA_AT_REST_2026_09_22__ copie en clair, supprimée à la fermeture

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            
            if let player = player {
                VideoPlayer(player: player)
                    .ignoresSafeArea()
            }
            
            VStack {
                HStack {
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title)
                            .foregroundColor(.white)
                            .padding()
                    }
                }
                Spacer()
            }
        }
        .onAppear {
            setupPlayer()
        }
        .onDisappear {
            player?.pause()
            player = nil
            if let url = tempURL { MediaPlaintextTemp.remove(url); tempURL = nil }
        }
    }

    private func setupPlayer() {
        guard let url = tempURL ?? (try? MediaPlaintextTemp.write(videoData, fileName: "video.mp4")) else { return }
        tempURL = url
        player = AVPlayer(url: url)
        player?.play()
    }
}

// Note: ShareSheet is defined in QRCodeDisplayView.swift

// MARK: - PDF View

struct PDFKitView: UIViewRepresentable {
    let data: Data
    
    func makeUIView(context: Context) -> PDFView {
        let pdfView = PDFView()
        pdfView.document = PDFDocument(data: data)
        pdfView.autoScales = true
        return pdfView
    }
    
    func updateUIView(_ uiView: PDFView, context: Context) {}
}


// MARK: - Add Member View

struct AddMemberView: View {
    let group: MessageGroup
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager
    @State private var memberKey = ""
    @State private var showingScanner = false  // ✅ QR scanner
    @State private var showingAlert = false
    @State private var alertMessage = ""
    @State private var alertTitle = ""
    
    var body: some View {
        NavigationView {
            Form {
                Section {
                    HStack {
                        Image(systemName: group.groupType.icon)
                            .foregroundColor(group.groupType.color)
                        Text(group.groupType.displayName)
                            .fontWeight(.semibold)
                    }
                    
                    Text(group.groupType.description)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                
                Section("section_member_key".localized) {
                    HStack {
                        TextField("placeholder_enter_key".localized, text: $memberKey)
                            .font(.system(.body, design: .monospaced))
                            .autocapitalization(.none)

                        // ✅ QR Scanner button
                        Button {
                            showingScanner = true
                        } label: {
                            Image(systemName: "qrcode.viewfinder")
                                .font(.title3)
                                .foregroundColor(groupBrandColor)
                        }
                    }
                }

                // Pick from existing OSHI conversations (skip self + already-in-group)
                let existingMembers = Set(group.memberPublicKeys)
                let myKey = identityManager.publicKey
                let pickable = (MessageManager.shared?.conversations ?? [])
                    .filter { $0.participantPublicKey != myKey && !existingMembers.contains($0.participantPublicKey) }
                if !pickable.isEmpty {
                    Section("group.add_from_contacts".localized) {
                        ForEach(pickable) { conv in
                            let pk = conv.participantPublicKey
                            // 🔧 PERF: was a fresh UserDefaults read + full JSON
                            // decode per row of this picker. Same memo as
                            // senderDisplayName.
                            let alias = GroupContactAliases.displayName(pk)
                            Button {
                                memberKey = pk
                                addMember()
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: "person.circle.fill")
                                        .font(.title2)
                                        .foregroundColor(.blue)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(alias ?? (pk.prefix(16) + "..."))
                                            .foregroundColor(.primary)
                                        if alias != nil {
                                            Text(pk.prefix(12) + "...")
                                                .font(.caption)
                                                .foregroundColor(.secondary)
                                        }
                                    }
                                    Spacer()
                                    Image(systemName: "plus.circle.fill")
                                        .foregroundColor(groupBrandColor)
                                }
                            }
                        }
                    }
                }

                Section {
                    Button {
                        addMember()
                    } label: {
                        HStack {
                            Spacer()
                            Text("button_add_to_group".localized)
                            Spacer()
                        }
                    }
                    .disabled(memberKey.isEmpty)
                }
            }
            .navigationTitle("nav_title_add_member".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("button_cancel".localized) {
                        dismiss()
                    }
                }
            }
            .sheet(isPresented: $showingScanner) {
                QRScannerView(scannedAddress: $memberKey)
                    .onDisappear {
                        showingScanner = false
                    }
            }
            .alert(alertTitle, isPresented: $showingAlert) {
                Button("button_ok".localized, role: .cancel) { }
            } message: {
                Text(alertMessage)
            }
            .onChange(of: memberKey) { oldValue, newValue in
                // ✅ Auto-add when QR code is scanned
                if !newValue.isEmpty && newValue != oldValue && newValue.count > 20 {
                    // Likely a scanned QR code (long key)
                    // Small delay to allow scanner to dismiss
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                        addMemberAutomatically()
                    }
                }
            }
        }
    }
    
    private func addMemberAutomatically() {
        let trimmed = memberKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        
        OshiLog.messaging.info("🔍 [ADD MEMBER] Attempting to add: \(trimmed.prefix(10))...")
        OshiLog.messaging.info("   Current group: '\(group.name)'")
        OshiLog.messaging.info("   Current members: \(group.memberPublicKeys.count)")
        
        // ✅ Check if already a member
        if group.memberPublicKeys.contains(trimmed) {
            OshiLog.messaging.info("❌ [ADD MEMBER] Member already in group!")
            OshiLog.messaging.info("   Member key: \(trimmed.prefix(10))...")
            alertTitle = "alert_already_member".localized
            alertMessage = String(format: "alert_user_in_group_name".localized, group.name)
            showingAlert = true
            memberKey = ""
            return
        }
        
        // Try to add
        groupManager.addMember(
            to: group.id,
            publicKey: trimmed,
            alias: nil
        )
        
        OshiLog.messaging.info("✅ [ADD MEMBER] Successfully added member")
        dismiss()
    }
    
    private func addMember() {
        let trimmed = memberKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        
        OshiLog.messaging.info("🔍 [ADD MEMBER] Manual add attempt: \(trimmed.prefix(10))...")
        OshiLog.messaging.info("   Current group: '\(group.name)'")
        OshiLog.messaging.info("   Current members: \(group.memberPublicKeys.count)")
        
        // ✅ Check if already a member
        if group.memberPublicKeys.contains(trimmed) {
            OshiLog.messaging.info("❌ [ADD MEMBER] Member already in group!")
            OshiLog.messaging.info("   Member key: \(trimmed.prefix(10))...")
            alertTitle = "alert_already_member".localized
            alertMessage = String(format: "alert_user_in_group_name".localized, group.name)
            showingAlert = true
            return
        }
        
        groupManager.addMember(
            to: group.id,
            publicKey: trimmed,
            alias: nil
        )
        
        OshiLog.messaging.info("✅ [ADD MEMBER] Successfully added member")
        dismiss()
    }
}

// MARK: - Group Info View

struct GroupInfoView: View {
    let groupId: UUID
    /// __GROUP_HEADER_ISLAND_2026_11_24__ the old ⋯ menu's actions, when opened from the chat.
    var chatActions: GroupInfoChatActions? = nil
    @Environment(\.dismiss) var dismiss
    @Environment(\.presentationMode) var presentationMode  // For proper navigation pop
    @EnvironmentObject var groupManager: MessageGroupManager
    @EnvironmentObject var identityManager: IdentityManager
    @State private var showLeaveConfirmation = false
    @State private var copiedKey: String?
    @State private var showCopyConfirmation = false
    @State private var editedGroupName = ""
    @State private var isEditingName = false
    // 🔧 (1.0.10 v6) Group identity picture editing — tap the avatar to pick a new
    // image. State & helpers mirror what the other GroupInfoView (line 730) has.
    @State private var showImagePicker = false
    @State private var selectedImage: UIImage?
    @State private var isUpdatingPicture = false
    @State private var showRemovePictureAlert = false
    // __GROUP_INFO_SETTINGS_2026_11_20__ remove/ban + delete-for-everyone.
    @State private var removeTarget: GroupMember?
    @State private var showDeleteChoices = false
    @State private var showDissolveConfirm = false

    // Live group from manager (always up-to-date)
    private var group: MessageGroup {
        groupManager.groups.first(where: { $0.id == groupId }) ?? MessageGroup(
            id: groupId, name: "?", type: .adminOnly, adminPublicKey: "",
            members: [], createdAt: Date(), lastActivity: Date()
        )
    }

    /// __GROUP_INFO_SETTINGS_2026_11_20__ Any admin of OUR copy (spec "Who is
    /// admin"), not only the creator: the creator-only test hid every admin
    /// control from promoted admins.
    var isAdmin: Bool {
        group.adminPublicKey == identityManager.publicKey
            || GroupSettingsPolicy.isAdmin(identityManager.publicKey, in: group)
    }

    private var permGroup: MessageGroup {
        var g = group
        g.setCurrentUser(identityManager.publicKey)
        return g
    }

    var canRemoveMembers: Bool {
        permGroup.canRemoveMembers
    }

    var body: some View {
        NavigationStack {
            ScrollViewReader { scrollProxy in
            List {
                Section {
                    HStack {
                        Spacer()
                        VStack(spacing: 12) {
                            ZStack(alignment: .bottomTrailing) {
                                // 🔧 (1.0.10 v6) Tap-to-change-picture. Wraps the avatar in a
                                // Button so the user can tap directly on the image to pick a
                                // new one. Long-press → context menu with Change/Remove.
                                // Permission gated by canChangeGroupPicture (admin for adminOnly,
                                // any member for collaborative/public).
                                Button {
                                    if permGroup.canChangeGroupPicture {
                                        showImagePicker = true
                                    }
                                } label: {
                                    // 🔧 PERF: cached decode, same as everywhere
                                    // else the group picture is drawn.
                                    if let uiImage = GroupPictureCache.image(for: group.id,
                                                                             data: group.groupPictureData) {
                                        Image(uiImage: uiImage)
                                            .resizable()
                                            .scaledToFill()
                                            .frame(width: 100, height: 100)
                                            .clipShape(Circle())
                                    } else {
                                        Text(group.avatar ?? "👥")
                                            .font(.scaled(80))
                                    }
                                }
                                .buttonStyle(.plain)
                                .disabled(!permGroup.canChangeGroupPicture || isUpdatingPicture)
                                .contextMenu {
                                    if permGroup.canChangeGroupPicture {
                                        Button {
                                            showImagePicker = true
                                        } label: {
                                            Label("group.change_picture".localized, systemImage: "photo")
                                        }
                                        if group.groupPictureData != nil {
                                            Button(role: .destructive) {
                                                showRemovePictureAlert = true
                                            } label: {
                                                Label("group.remove_picture.title".localized, systemImage: "trash")
                                            }
                                        }
                                    }
                                }

                                // Camera badge (visual affordance + redundant trigger)
                                if permGroup.canChangeGroupPicture {
                                    Button {
                                        showImagePicker = true
                                    } label: {
                                        Image(systemName: isUpdatingPicture ? "hourglass" : "camera.fill")
                                            .font(.scaled(14, weight: .semibold))
                                            .foregroundColor(.white)
                                            .padding(8)
                                            .background(group.groupType.color)
                                            .clipShape(Circle())
                                    }
                                    .disabled(isUpdatingPicture)
                                } else {
                                    Image(systemName: group.groupType.icon)
                                        .font(.scaled(24))
                                        .foregroundColor(.white)
                                        .padding(8)
                                        .background(group.groupType.color)
                                        .clipShape(Circle())
                                }
                            }

                            // Editable group name (permission-based)
                            if permGroup.canChangeName {
                                if isEditingName {
                                    HStack(spacing: 8) {
                                        TextField("group_name".localized, text: $editedGroupName)
                                            .font(.title2)
                                            .fontWeight(.bold)
                                            .multilineTextAlignment(.center)
                                            .textFieldStyle(.roundedBorder)

                                        Button {
                                            let trimmed = editedGroupName.trimmingCharacters(in: .whitespacesAndNewlines)
                                            if !trimmed.isEmpty {
                                                let _ = groupManager.renameGroup(
                                                    groupId: groupId,
                                                    newName: trimmed,
                                                    renamedBy: identityManager.publicKey
                                                )
                                            }
                                            isEditingName = false
                                        } label: {
                                            Image(systemName: "checkmark.circle.fill")
                                                .foregroundColor(.green)
                                                .font(.title3)
                                        }

                                        Button {
                                            isEditingName = false
                                        } label: {
                                            Image(systemName: "xmark.circle.fill")
                                                .foregroundColor(.secondary)
                                                .font(.title3)
                                        }
                                    }
                                } else {
                                    Button {
                                        editedGroupName = group.name
                                        isEditingName = true
                                    } label: {
                                        HStack(spacing: 4) {
                                            Text(group.name)
                                                .font(.title2)
                                                .fontWeight(.bold)
                                                .foregroundColor(.primary)
                                            Image(systemName: "pencil")
                                                .font(.caption)
                                                .foregroundColor(.secondary)
                                        }
                                    }
                                }
                            } else {
                                Text(group.name)
                                    .font(.title2)
                                    .fontWeight(.bold)
                            }

                            HStack(spacing: 8) {
                                Text(group.groupType.displayName)
                                    .font(.subheadline)
                                    .foregroundColor(group.groupType.color)
                                
                                Text("•")
                                    .foregroundColor(.secondary)
                                
                                Text(String(format: "group_member_count".localized, group.memberCount))
                                    .font(.subheadline)
                                    .foregroundColor(.secondary)
                            }
                            
                            Text(group.groupType.description)
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .multilineTextAlignment(.center)
                        }
                        Spacer()
                    }
                    .listRowBackground(Color.clear)
                }

                // __GROUP_HEADER_ISLAND_2026_11_24__ what the chat's ⋯ menu held.
                if let chatActions {
                    GroupInfoChatActionsSection(actions: chatActions,
                                                muted: groupManager.isGroupMuted(groupId))
                }

                // __GROUP_INFO_SETTINGS_2026_11_20__ GROUP_INFO_SETTINGS_SPEC §5 items 2–9.
                GroupSettingsSections(groupId: groupId, onMessageAdmin: { replyInPrivate(to: $0) })

                // Per-chat media gallery (P2 #20)
                Section {
                    NavigationLink {
                        MediaGalleryView(peerPublicKey: nil, groupId: groupId)
                    } label: {
                        Label("media_gallery.title".localized, systemImage: "photo.on.rectangle.angled")
                    }
                }

                // §5 item 10 — Administrators.
                GroupAdminsSection(group: group, me: identityManager.publicKey)
                    .id("gs-anchor-center")

                Section("gs_members".localized) {
                    ForEach(group.memberPublicKeys, id: \.self) { publicKey in
                        // Admin callbacks are only supplied when the current user is an admin (creator OR in admin list).
                        let currentIsAdmin = group.members.first(where: { $0.publicKey == identityManager.publicKey })?.isAdmin == true || isAdmin
                        MemberRowView(
                            publicKey: publicKey,
                            group: group,
                            identityManager: identityManager,
                            onCopy: copyPublicKey,
                            onPromote: currentIsAdmin ? { key in groupManager.promoteToAdmin(in: groupId, publicKey: key) } : nil,
                            onDemote: currentIsAdmin ? { key in groupManager.demoteFromAdmin(in: groupId, publicKey: key) } : nil,
                            // 1-1 parity actions (mirrors iOS contact-row UX). Hidden for the
                            // current user themselves; "add to contacts" hidden when already
                            // added, "reply in private" routes through the existing OpenChat
                            // notification (same path as push notifications and QR add-contact).
                            onAddToContacts: { key, alias in addMemberToContacts(publicKey: key, alias: alias) },
                            onReplyInPrivate: { key in replyInPrivate(to: key) },
                            onRemove: currentIsAdmin ? { key in
                                removeTarget = group.members.first { $0.publicKey == key }
                            } : nil
                        )
                    }
                }

                // §5 items 12–13 — Banned users, Recent events (admins only).
                if isAdmin {
                    GroupAdminToolsSection(groupId: groupId)
                }

                // ✅ Leave/Delete Group Section — §5 item 14 / §2.9.
                Section {
                    Button(role: .destructive) {
                        if isAdmin { showDeleteChoices = true } else { showLeaveConfirmation = true }
                    } label: {
                        HStack {
                            Spacer()
                            Text(isAdmin ? "gs_delete_group".localized : "gs_leave_group".localized)
                            Spacer()
                        }
                    }
                    .accessibilityIdentifier("gs.delete")
                }
                .id("gs-anchor-bottom")
            }
            #if DEBUG
            .modifier(GroupInfoDebugScrollAnchor(proxy: scrollProxy))
            #endif
            }
            .navigationTitle("nav_title_group_info".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("button_close".localized) {
                        dismiss()
                    }
                }
            }
            // ✅ Copy confirmation toast
            .overlay(alignment: .top) {
                if showCopyConfirmation {
                    VStack {
                        HStack(spacing: 12) {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(.green)
                            Text("toast_key_copied".localized)
                                .fontWeight(.medium)
                        }
                        .padding()
                        .background(Color(.systemBackground))
                        .cornerRadius(12)
                        .shadow(radius: 10)
                    }
                    .padding(.top, 50)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .animation(.spring(), value: showCopyConfirmation)
                }
            }
            .confirmationDialog(
                isAdmin ? "confirm_delete_group".localized : "confirm_leave_group".localized,
                isPresented: $showLeaveConfirmation,
                titleVisibility: .visible
            ) {
                Button(isAdmin ? "button_delete_group".localized : "button_leave_group".localized, role: .destructive) {
                    leaveOrDeleteGroup()
                }
                Button("button_cancel".localized, role: .cancel) {}
            } message: {
                Text(isAdmin ? "confirm_delete_message".localized : "confirm_leave_message".localized)
            }
            .confirmationDialog("gs_delete_group".localized, isPresented: $showDeleteChoices, titleVisibility: .visible) {
                Button("gs_delete_for_everyone".localized, role: .destructive) { showDissolveConfirm = true }
                Button("gs_delete_for_me".localized, role: .destructive) {
                    groupManager.leaveGroup(group.id, userPublicKey: identityManager.publicKey)
                    closeAfterLeaving()
                }
                Button("button_cancel".localized, role: .cancel) {}
            }
            .alert("gs_delete_for_everyone".localized, isPresented: $showDissolveConfirm) {
                Button("button_cancel".localized, role: .cancel) {}
                Button("gs_delete_group".localized, role: .destructive) {
                    groupManager.dissolveGroupForEveryone(group.id)
                    closeAfterLeaving()
                }
            } message: {
                Text("gs_delete_confirm".localized)
            }
            .confirmationDialog("gs_ban".localized,
                                isPresented: Binding(get: { removeTarget != nil }, set: { if !$0 { removeTarget = nil } }),
                                titleVisibility: .visible,
                                presenting: removeTarget) { m in
                Button("gs_ban".localized, role: .destructive) {
                    groupManager.removeMember(from: groupId, publicKey: m.publicKey)
                    groupManager.refreshHandleRegistration(groupId)
                }
                Button("button_cancel".localized, role: .cancel) {}
            } message: { m in
                Text(m.alias ?? String(m.publicKey.prefix(16)) + "…")
            }
            // 🔧 (1.0.10 v6) Group identity picture: image picker + remove confirm.
            // Reuses the same OSHIGroupPhotoPicker the other GroupInfoView (line 730) uses.
            // updateGroupPicture broadcasts the new image to all members via mesh + IPFS + VPS.
            .sheet(isPresented: $showImagePicker) {
                OSHIGroupPhotoPicker(selectedImage: $selectedImage)
            }
            .onChange(of: selectedImage) { _, newValue in
                if let img = newValue {
                    updateGroupPictureFromImage(img)
                }
            }
            .alert("group.remove_picture.title".localized, isPresented: $showRemovePictureAlert) {
                Button("cancel".localized, role: .cancel) {}
                Button("remove".localized, role: .destructive) {
                    let _ = groupManager.removeGroupPicture(groupId: groupId, removedBy: identityManager.publicKey)
                }
            } message: {
                Text("group.remove_picture.message".localized)
            }
        }
    }

    /// Compress + push selected image into the group; the manager broadcasts to all members.
    private func updateGroupPictureFromImage(_ image: UIImage) {
        guard let imageData = image.jpegData(compressionQuality: 0.85) else { return }
        isUpdatingPicture = true
        let success = groupManager.updateGroupPicture(
            groupId: groupId,
            imageData: imageData,
            updatedBy: identityManager.publicKey
        )
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            isUpdatingPicture = false
            selectedImage = nil
            if !success {
                OshiLog.messaging.info("⚠️ Group picture update failed (permission denied or group not found)")
            }
        }
    }

    // ✅ Copy public key to clipboard
    private func copyPublicKey(_ publicKey: String) {
        UIPasteboard.general.string = publicKey
        copiedKey = publicKey
        
        OshiLog.messaging.info("📋 Copied public key to clipboard: \(publicKey.prefix(10))...")
        
        // Show confirmation toast
        withAnimation {
            showCopyConfirmation = true
        }
        
        // Hide toast after 2 seconds
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            withAnimation {
                showCopyConfirmation = false
            }
        }
    }
    
    // ✅ Add a group member to the user's contacts. Reuses MessageManager's
    // addPendingConversation (same flow as the QR add-contact path) and persists the
    // alias in the same UserDefaults bucket the rest of the app reads from.
    private func addMemberToContacts(publicKey: String, alias: String?) {
        guard publicKey != identityManager.publicKey else { return }
        guard let mgr = MessageManager.shared else { return }
        // Skip if a real (non-pending) conversation already exists.
        if mgr.conversations.contains(where: { $0.participantPublicKey == publicKey }) {
            return
        }
        mgr.addPendingConversation(publicKey: publicKey, identityManager: identityManager)
        if let alias = alias, !alias.isEmpty {
            let key = "contactAliases"
            var aliases: [String: String] = [:]
            if let data = UserDefaults.standard.data(forKey: key),
               let decoded = try? JSONDecoder().decode([String: String].self, from: data) {
                aliases = decoded
            }
            aliases[publicKey] = alias
            if let encoded = try? JSONEncoder().encode(aliases) {
                UserDefaults.standard.set(encoded, forKey: key)
            }
        }
        // Light haptic confirmation
        let generator = UINotificationFeedbackGenerator()
        generator.notificationOccurred(.success)
    }

    // ✅ Open a 1-1 chat with the given member. Posts the same OpenChat notification
    // the rest of the app uses (push notifications, NewMessageView, etc.) so the
    // tab switch + ChatView push happens via the existing routing path in ContentView.
    private func replyInPrivate(to publicKey: String) {
        guard publicKey != identityManager.publicKey else { return }
        // Ensure conversation exists so the deep-link target resolves immediately.
        MessageManager.shared?.addPendingConversation(publicKey: publicKey, identityManager: identityManager)
        // Dismiss the info sheet first so OpenChat lands on the Messages tab cleanly.
        dismiss()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
            NotificationCenter.default.post(
                name: NSNotification.Name("OpenChat"),
                object: nil,
                userInfo: ["peerPublicKey": publicKey]
            )
        }
    }

    private func closeAfterLeaving() {
        dismiss()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            presentationMode.wrappedValue.dismiss()
        }
    }

    // ✅ FIXED: Proper leave/delete functionality
    private func leaveOrDeleteGroup() {
        if isAdmin {
            // Admin deletes the entire group
            OshiLog.messaging.info("🗑️ Admin deleting group: \(group.name)")
            groupManager.deleteGroup(group.id)
        } else {
            OshiLog.messaging.info("👋 Leaving group: \(group.name)")
            groupManager.leaveGroup(group.id, userPublicKey: identityManager.publicKey)
        }
        
        // ✅ Dismiss both the info sheet and pop back to group list
        dismiss()
        
        // Small delay to ensure smooth navigation
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            presentationMode.wrappedValue.dismiss()
        }
    }
}

// MARK: - Date Picker Sheet

struct DatePickerSheet: View {
    @Binding var selectedDate: Date
    let messages: [GroupMessage]
    let onSelectMessage: (UUID) -> Void
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var groupManager: MessageGroupManager
    
    var messagesOnDate: [GroupMessage] {
        let calendar = Calendar.current
        return messages.filter { message in
            calendar.isDate(message.timestamp, inSameDayAs: selectedDate)
        }
    }
    
    var body: some View {
        NavigationView {
            VStack {
                DatePicker(
                    "button_select_date".localized,
                    selection: $selectedDate,
                    in: ...Date(),
                    displayedComponents: .date
                )
                .datePickerStyle(.graphical)
                .padding()
                
                Divider()
                
                if messagesOnDate.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "calendar.badge.exclamationmark")
                            .font(.scaled(48))
                            .foregroundColor(.gray)
                        
                        Text("info_no_messages_date".localized)
                            .font(.headline)
                            .foregroundColor(.gray)
                        
                        Text(selectedDate, style: .date)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    .padding()
                    
                    Spacer()
                } else {
                    List {
                        Section {
                            Text(String(format: "messages_count".localized, messagesOnDate.count))
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                        
                        ForEach(messagesOnDate) { message in
                            Button {
                                onSelectMessage(message.id)
                                dismiss()
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack {
                                        Text(formatAddress(message.senderPublicKey))
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                        
                                        Spacer()
                                        
                                        Text(message.timestamp, style: .time)
                                            .font(.caption)
                                            .foregroundColor(.secondary)
                                    }
                                    
                                    if let decrypted = groupManager.decryptMessage(message) {
                                        Text(decrypted)
                                            .font(.body)
                                            .lineLimit(2)
                                            .foregroundColor(.primary)
                                    } else {
                                        Text("message_encrypted_placeholder".localized)
                                            .font(.body)
                                            .italic()
                                            .foregroundColor(.secondary)
                                    }
                                }
                                .padding(.vertical, 4)
                            }
                        }
                    }
                }
            }
            .navigationTitle("nav_title_jump_to_date".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("button_done".localized) {
                        dismiss()
                    }
                }
            }
        }
    }
    
    private func formatAddress(_ address: String) -> String {
        guard address.count > 10 else { return address }
        return "\(address.prefix(6))..."
    }
}

// MARK: - Helper Views

// MARK: - Full Screen Image Viewer for Group Photos

struct GroupImageFullScreenView: View {
    let image: UIImage
    @Environment(\.dismiss) var dismiss
    @State private var scale: CGFloat = 1.0

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .scaleEffect(scale)
                .gesture(
                    MagnifyGesture()
                        .onChanged { value in
                            scale = value.magnification
                        }
                        .onEnded { _ in
                            withAnimation { scale = max(1.0, scale) }
                        }
                )
                .onTapGesture(count: 2) {
                    withAnimation {
                        scale = scale > 1 ? 1.0 : 2.5
                    }
                }
        }
        .overlay(alignment: .topTrailing) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title)
                    .foregroundStyle(.white.opacity(0.8))
                    .padding()
            }
        }
        .statusBarHidden()
    }
}

struct MemberRowView: View {
    let publicKey: String
    let group: MessageGroup
    let identityManager: IdentityManager
    let onCopy: (String) -> Void
    // Admin-only callbacks: promote/demote. nil => current user is not an admin, so the options are hidden.
    var onPromote: ((String) -> Void)? = nil
    var onDemote: ((String) -> Void)? = nil
    // 1-1 parity callbacks: add-to-contacts (publicKey, alias) and reply-in-private (publicKey).
    var onAddToContacts: ((String, String?) -> Void)? = nil
    var onReplyInPrivate: ((String) -> Void)? = nil
    // __GROUP_INFO_SETTINGS_2026_11_20__ §2.7 Remove (= ban). nil ⇒ not an admin.
    var onRemove: ((String) -> Void)? = nil

    private var isCurrentUser: Bool { publicKey == identityManager.publicKey }
    private var isAlreadyContact: Bool {
        MessageManager.shared?.conversations.contains(where: { $0.participantPublicKey == publicKey }) ?? false
    }

    @ObservedObject private var linksManager = ProfileLinksManager.shared
    @ObservedObject private var presenceManager = ContactPresenceManager.shared
    @StateObject private var faviconLoader = FaviconLoader()

    private var memberAlias: String? {
        group.members.first(where: { $0.publicKey == publicKey })?.alias
    }

    private var memberLinks: ProfileLinks? {
        linksManager.getVisibleLinks(for: publicKey)
    }

    /// Get member's synced profile picture (from presence or own identity)
    private var memberProfileImage: UIImage? {
        if publicKey == identityManager.publicKey {
            // Current user — use own profile image
            if let data = identityManager.profileImageData {
                return UIImage(data: data)
            }
            return nil
        }
        // Other members — use synced profile from ContactPresenceManager
        return presenceManager.getProfileImage(publicKey: publicKey)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                // Profile picture (synced) or fallback icon
                if let profileImage = memberProfileImage {
                    Image(uiImage: profileImage)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 36, height: 36)
                        .clipShape(Circle())
                } else {
                    // __ONE_AVATAR_NOT_THREE_2026_11_07__ keyed on the MEMBER,
                    // not the group type: every member of a group shared one
                    // colour, which is the opposite of what a member row is for.
                    DefaultAvatar(key: publicKey, name: memberAlias, size: 36, initialCount: 1)
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(memberAlias ?? (publicKey.prefix(16) + "..."))
                        .font(memberAlias != nil ? .body : .system(.body, design: .monospaced))

                    if group.groupType == .collaborative || group.groupType == .publicGroup {
                        Text("hint_tap_to_copy".localized)
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }

                Spacer()

                if publicKey == group.adminPublicKey
                    || group.members.first(where: { $0.publicKey == publicKey })?.isAdmin == true {
                    badgeView(text: "label_admin".localized, color: group.groupType.color)
                } else if publicKey == identityManager.publicKey {
                    badgeView(text: "label_you".localized, color: groupBrandColor)
                }
            }

            // Profile links row (if member shared their links)
            if let links = memberLinks, links.hasAnyLink {
                HStack(spacing: 10) {
                    ForEach(links.filledLinks, id: \.type) { link in
                        Button {
                            ProfileLinks.open(link.type, value: link.url)
                        } label: {
                            if link.type == .website, let favicon = faviconLoader.favicons[link.url] {
                                Image(uiImage: favicon)
                                    .resizable()
                                    .aspectRatio(contentMode: .fit)
                                    .frame(width: 16, height: 16)
                                    .clipShape(RoundedRectangle(cornerRadius: 3))
                            } else {
                                Image(systemName: link.type.icon)
                                    .font(.scaled(14))
                                    .foregroundColor(link.type.color)
                            }
                        }
                        .buttonStyle(PlainButtonStyle())
                    }
                }
                .padding(.leading, 44)
                .onAppear {
                    // Load favicons for website links
                    if let links = memberLinks {
                        for link in links.filledLinks where link.type == .website {
                            faviconLoader.loadFavicon(for: link.url)
                        }
                    }
                }
            }
        }
        .contextMenu {
            if group.groupType == .collaborative || group.groupType == .publicGroup {
                Button {
                    onCopy(publicKey)
                } label: {
                    Label("menu_copy_public_key".localized, systemImage: "doc.on.doc")
                }
            }
            // 1-1 parity: Add to contacts + Reply in private (hidden for the user themselves).
            if !isCurrentUser {
                if !isAlreadyContact, let onAddToContacts = onAddToContacts {
                    Button {
                        onAddToContacts(publicKey, memberAlias)
                    } label: {
                        Label("group.member.add_to_contacts".localized, systemImage: "person.crop.circle.badge.plus")
                    }
                }
                if let onReplyInPrivate = onReplyInPrivate {
                    Button {
                        onReplyInPrivate(publicKey)
                    } label: {
                        Label("group.member.reply_in_private".localized, systemImage: "arrowshape.turn.up.left.circle")
                    }
                }
            }
            // Admin controls — hidden when caller is not an admin or when this row is the group creator.
            if publicKey != identityManager.publicKey && publicKey != group.adminPublicKey {
                let isMemberAdmin = group.members.first(where: { $0.publicKey == publicKey })?.isAdmin == true
                if isMemberAdmin, let onDemote = onDemote {
                    Button(role: .destructive) {
                        onDemote(publicKey)
                    } label: {
                        Label("group.member.remove_admin".localized, systemImage: "crown.slash")
                    }
                } else if !isMemberAdmin, let onPromote = onPromote {
                    Button {
                        onPromote(publicKey)
                    } label: {
                        Label("group.member.make_admin".localized, systemImage: "crown")
                    }
                }
                if let onRemove {
                    Button(role: .destructive) {
                        onRemove(publicKey)
                    } label: {
                        Label("gs_ban".localized, systemImage: "person.fill.xmark")
                    }
                }
            }
        }
        // 1-1 parity: trailing swipe actions for fast access on the member row.
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if !isCurrentUser {
                if let onReplyInPrivate = onReplyInPrivate {
                    Button {
                        onReplyInPrivate(publicKey)
                    } label: {
                        Label("group.member.reply_in_private".localized, systemImage: "arrowshape.turn.up.left.circle")
                    }
                    .tint(groupBrandColor)
                }
                if !isAlreadyContact, let onAddToContacts = onAddToContacts {
                    Button {
                        onAddToContacts(publicKey, memberAlias)
                    } label: {
                        Label("group.member.add_to_contacts".localized, systemImage: "person.crop.circle.badge.plus")
                    }
                    .tint(.green)
                }
            }
        }
    }

    private func badgeView(text: String, color: Color) -> some View {
        Text(text)
            .font(.caption)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color.opacity(0.2))
            .foregroundColor(color)
            .cornerRadius(8)
    }
}

// MARK: - Block Group Sheet

struct BlockGroupSheet: View {
    let groupId: UUID
    let groupName: String
    let onBlock: () -> Void
    
    @Environment(\.presentationMode) var presentationMode
    @State private var selectedReason: BlockReason? = nil
    @State private var showingConfirmation = false
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Header
                VStack(spacing: 16) {
                    ZStack {
                        Circle()
                            .fill(Color.red.opacity(0.1))
                            .frame(width: 80, height: 80)
                        
                        Image(systemName: "person.3.fill")
                            .font(.scaled(35))
                            .foregroundColor(.red)
                    }
                    
                    Text("group.block_title".localized)
                        .font(.title2)
                        .fontWeight(.bold)
                    
                    Text(String(format: "group.block_subtitle".localized, groupName))
                        .font(.body)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 20)
                }
                .padding(.top, 30)
                .padding(.bottom, 20)
                
                Divider()
                
                // Reasons list
                ScrollView {
                    VStack(spacing: 0) {
                        Text("block_contact_select_reason".localized)
                            .font(.headline)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding()
                        
                        ForEach(BlockReason.allCases, id: \.self) { reason in
                            GroupBlockReasonRow(
                                reason: reason,
                                isSelected: selectedReason == reason
                            ) {
                                selectedReason = reason
                            }
                        }
                    }
                }
                
                Divider()
                
                // Action buttons
                VStack(spacing: 12) {
                    Button(action: {
                        showingConfirmation = true
                    }) {
                        HStack {
                            Image(systemName: "hand.raised.fill")
                            Text("group.block_button".localized)
                                .fontWeight(.semibold)
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.red)
                        .foregroundColor(.white)
                        .cornerRadius(12)
                    }
                    .disabled(selectedReason == nil)
                    .opacity(selectedReason == nil ? 0.5 : 1.0)
                    
                    Button(action: {
                        presentationMode.wrappedValue.dismiss()
                    }) {
                        Text("common.cancel".localized)
                            .fontWeight(.medium)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color(.systemGray6))
                            .foregroundColor(.primary)
                            .cornerRadius(12)
                    }
                }
                .padding()
            }
            .navigationBarHidden(true)
            .alert(isPresented: $showingConfirmation) {
                Alert(
                    title: Text("group.block_confirm_title".localized),
                    message: Text("group.block_confirm_message".localized),
                    primaryButton: .destructive(Text("common.block".localized)) {
                        BlockedContactsManager.shared.blockGroup(
                            groupId: groupId,
                            groupName: groupName,
                            reason: selectedReason
                        )
                        onBlock()
                        presentationMode.wrappedValue.dismiss()
                    },
                    secondaryButton: .cancel()
                )
            }
        }
    }
}

// MARK: - Block Reason Row (for GroupViews)

struct GroupBlockReasonRow: View {
    let reason: BlockReason
    let isSelected: Bool
    let onSelect: () -> Void
    
    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 16) {
                Image(systemName: iconForReason)
                    .font(.title3)
                    .foregroundColor(isSelected ? .white : .red)
                    .frame(width: 30)
                
                VStack(alignment: .leading, spacing: 4) {
                    Text(reason.localizedDescription)
                        .font(.body)
                        .fontWeight(.medium)
                        .foregroundColor(isSelected ? .white : .primary)
                    
                    Text(descriptionForReason)
                        .font(.caption)
                        .foregroundColor(isSelected ? .white.opacity(0.8) : .secondary)
                }
                
                Spacer()
                
                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.title3)
                        .foregroundColor(.white)
                }
            }
            .padding()
            .background(isSelected ? Color.red : Color(.systemBackground))
            .cornerRadius(12)
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isSelected ? Color.clear : Color(.systemGray4), lineWidth: 1)
            )
            .padding(.horizontal)
            .padding(.vertical, 4)
        }
        .buttonStyle(PlainButtonStyle())
    }
    
    private var iconForReason: String {
        switch reason {
        case .spam:
            return "envelope.badge.fill"
        case .harassment:
            return "exclamationmark.triangle.fill"
        case .inappropriate:
            return "hand.raised.fill"
        case .other:
            return "ellipsis.circle.fill"
        }
    }
    
    private var descriptionForReason: String {
        switch reason {
        case .spam:
            return NSLocalizedString("block_reason_spam_desc", comment: "")
        case .harassment:
            return NSLocalizedString("block_reason_harassment_desc", comment: "")
        case .inappropriate:
            return NSLocalizedString("block_reason_inappropriate_desc", comment: "")
        case .other:
            return NSLocalizedString("block_reason_other_desc", comment: "")
        }
    }
}

// MARK: - Full-Screen Location Map Panel (WhatsApp-style)

struct GroupFullMapPanel: View {
    let latitude: Double
    let longitude: Double
    let address: String?
    let isLive: Bool
    @Binding var isPresented: Bool

    @State private var myLocation: CLLocationCoordinate2D? = nil
    @State private var mapPosition: MapCameraPosition = .automatic
    @State private var showOshiMapFullScreen = false
    @StateObject private var locationFetcher = QuickLocationFetcher()

    private var sharedCoordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    var body: some View {
        NavigationView {
            ZStack(alignment: .bottom) {
                Map(position: $mapPosition) {
                    // Shared location pin
                    Annotation("", coordinate: sharedCoordinate) {
                        ZStack {
                            Circle()
                                .fill(Theme.brand.opacity(0.3))
                                .frame(width: 48, height: 48)
                            if isLive {
                                Circle()
                                    .stroke(Theme.brand, lineWidth: 2)
                                    .frame(width: 48, height: 48)
                                    .scaleEffect(1.4)
                                    .opacity(0.4)
                            }
                            Circle()
                                .fill(Theme.brand)
                                .frame(width: 20, height: 20)
                            Image(systemName: "person.fill")
                                .font(.scaled(10, weight: .bold))
                                .foregroundColor(.white)
                        }
                    }

                    // My location pin
                    if let myLoc = myLocation {
                        Annotation("", coordinate: myLoc) {
                            ZStack {
                                Circle()
                                    .fill(Color.blue.opacity(0.3))
                                    .frame(width: 44, height: 44)
                                Circle()
                                    .fill(Color.blue)
                                    .frame(width: 18, height: 18)
                                Text("location.me".localized)
                                    .font(.scaled(8, weight: .bold))
                                    .foregroundColor(.white)
                            }
                        }
                    }
                }
                .mapStyle(.standard)
                .mapControls {
                    MapCompass()
                    MapScaleView()
                    MapUserLocationButton()
                }
                .ignoresSafeArea(edges: .bottom)

                // Bottom info card
                VStack(spacing: 12) {
                    // Live badge
                    if isLive {
                        HStack(spacing: 6) {
                            Circle().fill(Color.green).frame(width: 8, height: 8)
                            Text("location.sharing_live".localized)
                                .font(.subheadline.bold())
                                .foregroundColor(.green)
                        }
                    }

                    if let addr = address, !addr.isEmpty {
                        HStack {
                            Image(systemName: "mappin.circle.fill")
                                .foregroundColor(.red)
                            Text(addr)
                                .font(.subheadline)
                                .lineLimit(2)
                            Spacer()
                        }
                    }

                    // Open in Maps button
                    Button {
                        MapOpener.open(coordinate: sharedCoordinate, name: address) {
                            showOshiMapFullScreen = true
                        }
                    } label: {
                        HStack {
                            Image(systemName: "arrow.triangle.turn.up.right.diamond.fill")
                            Text("location.open_in_maps".localized)
                        }
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(Theme.brand)
                        .foregroundColor(.white)
                        .cornerRadius(14)
                    }
                }
                .padding(20)
                .background(
                    RoundedRectangle(cornerRadius: 20)
                        .fill(.ultraThinMaterial)
                        .shadow(color: .black.opacity(0.15), radius: 10, y: -5)
                )
                .padding(.horizontal, 12)
                .padding(.bottom, 8)
            }
            .navigationTitle(isLive ? "location.sharing_live".localized : "location.shared_location".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        isPresented = false
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title3)
                            .foregroundColor(.secondary)
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    // Re-center button to fit both pins
                    Button {
                        fitBothPins()
                    } label: {
                        Image(systemName: "arrow.up.left.and.arrow.down.right")
                            .font(.body)
                    }
                }
            }
        }
        .onAppear {
            locationFetcher.requestLocation()
            // Start zoomed out to show context around the pin
            mapPosition = .region(MKCoordinateRegion(
                center: sharedCoordinate,
                span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
            ))
        }
        .onReceive(locationFetcher.$location) { loc in
            if let loc = loc {
                myLocation = loc
                // Fit both pins on screen
                fitBothPins()
            }
        }
        .fullScreenCover(isPresented: $showOshiMapFullScreen) {
            OfflineMapView(presentedModally: true, focusCoordinate: sharedCoordinate)
        }
    }

    private func fitBothPins() {
        guard let myLoc = myLocation else {
            // Just zoom to shared location with wide view
            mapPosition = .region(MKCoordinateRegion(
                center: sharedCoordinate,
                span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
            ))
            return
        }
        // Calculate region that fits both pins with padding
        let minLat = min(sharedCoordinate.latitude, myLoc.latitude)
        let maxLat = max(sharedCoordinate.latitude, myLoc.latitude)
        let minLon = min(sharedCoordinate.longitude, myLoc.longitude)
        let maxLon = max(sharedCoordinate.longitude, myLoc.longitude)
        let center = CLLocationCoordinate2D(
            latitude: (minLat + maxLat) / 2,
            longitude: (minLon + maxLon) / 2
        )
        let spanLat = max((maxLat - minLat) * 1.5, 0.01)
        let spanLon = max((maxLon - minLon) * 1.5, 0.01)
        mapPosition = .region(MKCoordinateRegion(
            center: center,
            span: MKCoordinateSpan(latitudeDelta: spanLat, longitudeDelta: spanLon)
        ))
    }
}

/// Quick location fetcher for the full map panel
class QuickLocationFetcher: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var location: CLLocationCoordinate2D?
    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func requestLocation() {
        let locManager = CLLocationManager()
        if locManager.authorizationStatus == .authorizedWhenInUse ||
           locManager.authorizationStatus == .authorizedAlways {
            manager.requestLocation()
        } else {
            manager.requestWhenInUseAuthorization()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        location = locations.last?.coordinate
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        OshiLog.messaging.info("QuickLocationFetcher error: \(error)")
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways {
            manager.requestLocation()
        }
    }
}

// MARK: - Group Scheduled Call Card (with real Calendar integration)

struct GroupScheduledCallCard: View {
    let jsonStr: String
    let senderKey: String
    let isMine: Bool

    @State private var event: ScheduledCallEvent?
    @State private var timeRemaining: String = ""
    @State private var timer: Timer?
    @State private var addedToCalendar = false
    @State private var showCalendarError = false
    @State private var calendarErrorMsg = ""
    @StateObject private var scheduledCallManager = ScheduledCallManager.shared

    private let brandColor = Theme.brand

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let event = event {
                // Header with calendar icon
                HStack(spacing: 10) {
                    ZStack {
                        Circle()
                            .fill(LinearGradient(colors: [.orange, .red], startPoint: .topLeading, endPoint: .bottomTrailing))
                            .frame(width: 44, height: 44)
                        Image(systemName: "calendar.badge.clock")
                            .font(.scaled(20))
                            .foregroundColor(.white)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(event.title ?? "event.call.scheduled".localized)
                            .font(.headline)
                        Text(event.createdByName)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    // Status badge
                    statusBadge(for: event)
                }

                Divider()

                // Date & Time
                HStack(spacing: 16) {
                    VStack(alignment: .leading, spacing: 4) {
                        Label {
                            Text(formattedDate(event.scheduledTime)).font(.subheadline)
                        } icon: {
                            Image(systemName: "calendar").foregroundColor(.orange)
                        }
                        Label {
                            Text(formattedTime(event.scheduledTime)).font(.subheadline.bold())
                        } icon: {
                            Image(systemName: "clock.fill").foregroundColor(brandColor)
                        }
                    }
                    Spacer()
                    if let duration = event.duration {
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(formatDuration(duration)).font(.title3.bold()).foregroundColor(.orange)
                            Text("event.duration".localized).font(.caption2).foregroundColor(.secondary)
                        }
                    }
                }

                // Countdown timer
                if !event.isPast {
                    HStack {
                        Image(systemName: event.isNow ? "phone.fill" : "timer")
                            .foregroundColor(event.isNow ? .green : .orange)
                        Text(timeRemaining)
                            .font(.subheadline.bold())
                            .foregroundColor(event.isNow ? .green : .primary)
                        Spacer()
                    }
                    .padding(.vertical, 8)
                    .padding(.horizontal, 12)
                    .background(
                        RoundedRectangle(cornerRadius: 8)
                            .fill(event.isNow ? Color.green.opacity(0.15) : Color.orange.opacity(0.1))
                    )
                }

                // Add to Calendar button
                if !event.isPast {
                    Button {
                        let generator = UIImpactFeedbackGenerator(style: .medium)
                        generator.impactOccurred()
                        Task {
                            let success = await CalendarExportHelper.shared.addToCalendar(event: event)
                            await MainActor.run {
                                if success {
                                    let gen = UINotificationFeedbackGenerator()
                                    gen.notificationOccurred(.success)
                                    addedToCalendar = true
                                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                                        addedToCalendar = false
                                    }
                                } else {
                                    calendarErrorMsg = CalendarExportHelper.shared.errorMessage
                                    showCalendarError = true
                                }
                            }
                        }
                    } label: {
                        HStack {
                            Image(systemName: addedToCalendar ? "checkmark.circle.fill" : "calendar.badge.plus")
                            Text(addedToCalendar ? "calendar.added".localized : "calendar.add".localized)
                                .font(.subheadline.bold())
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(addedToCalendar ? Color.green.opacity(0.15) : brandColor.opacity(0.15))
                        .foregroundColor(addedToCalendar ? .green : brandColor)
                        .cornerRadius(8)
                    }
                }
            } else {
                // Fallback if parsing fails
                HStack(spacing: 10) {
                    Image(systemName: "calendar.badge.clock")
                        .font(.title2)
                        .foregroundColor(.orange)
                    Text("event.call.scheduled".localized)
                        .font(.headline)
                }
            }
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(.systemBackground))
                .shadow(color: .black.opacity(0.1), radius: 8, y: 4)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(LinearGradient(colors: [.orange.opacity(0.5), .red.opacity(0.3)], startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1.5)
        )
        .alert("calendar.error.title".localized, isPresented: $showCalendarError) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(calendarErrorMsg)
        }
        .onAppear {
            loadEvent()
            startTimer()
        }
        .onDisappear {
            timer?.invalidate()
        }
    }

    private func loadEvent() {
        if let data = jsonStr.data(using: .utf8),
           let parsed = try? JSONDecoder().decode(ScheduledCallEvent.self, from: data) {
            // Check for newer stored version
            if let stored = scheduledCallManager.events.first(where: { $0.id == parsed.id }) {
                event = stored
            } else {
                event = parsed
                scheduledCallManager.handleReceivedEvent(data: data, from: senderKey)
            }
        }
    }

    private func startTimer() {
        updateTimeRemaining()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            updateTimeRemaining()
        }
    }

    private func updateTimeRemaining() {
        guard let event = event else { return }
        let diff = event.scheduledTime.timeIntervalSince(Date())
        if diff <= 0 {
            if diff > -3600 {
                timeRemaining = "event.starting.soon".localized
            } else {
                timeRemaining = "event.status.completed".localized
            }
        } else if diff < 60 {
            timeRemaining = String(format: "event.in.seconds".localized, Int(diff))
        } else if diff < 3600 {
            timeRemaining = String(format: "event.in.minutes".localized, Int(diff / 60))
        } else if diff < 86400 {
            timeRemaining = String(format: "event.in.hours".localized, Int(diff / 3600))
        } else {
            timeRemaining = String(format: "event.in.days".localized, Int(diff / 86400))
        }
    }

    @ViewBuilder
    private func statusBadge(for event: ScheduledCallEvent) -> some View {
        let (text, color): (String, Color) = {
            switch event.status {
            case .pending: return ("event.status.pending".localized, .orange)
            case .confirmed: return ("event.status.confirmed".localized, .green)
            case .declined: return ("event.status.declined".localized, .red)
            case .cancelled: return ("event.status.cancelled".localized, .gray)
            case .completed: return ("event.status.completed".localized, .secondary)
            }
        }()
        Text(text)
            .font(.caption2).fontWeight(.bold)
            .padding(.horizontal, 8).padding(.vertical, 4)
            .background(color.opacity(0.2))
            .foregroundColor(color)
            .cornerRadius(8)
    }

    // 🔧 PERF: `GroupScheduledCallCard` is an inline bubble variant, so both of
    // these ran per visible card per re-render. The originals left the other
    // style at its default, which for a fresh `DateFormatter` is `.none` —
    // passing `.none` explicitly keeps the output identical. See
    // `OshiRowFormatters` in ChatView.swift.
    private func formattedDate(_ date: Date) -> String {
        return OshiRowFormatters.string(date, dateStyle: .medium, timeStyle: .none)
    }

    private func formattedTime(_ date: Date) -> String {
        return OshiRowFormatters.string(date, dateStyle: .none, timeStyle: .short)
    }

    private func formatDuration(_ seconds: TimeInterval) -> String {
        let minutes = Int(seconds / 60)
        if minutes >= 60 {
            let h = minutes / 60
            let m = minutes % 60
            return m > 0 ? "\(h)h\(m)" : "\(h)h"
        }
        return "\(minutes) min"
    }
}

// MARK: - Group Inline Link Bubble (OG image on top + message text in single bubble)

/// Single bubble: OG image on top, site name + title, message text at bottom. Tap opens URL.
struct GroupInlineLinkBubble: View {
    let text: String
    let isMine: Bool
    let bubbleColor: Color
    let textColor: Color
    // __FORWARD_BADGE_2026_08_18__ nil = not forwarded. Passed in rather than
    // decoded here because this view receives `text` ALREADY unwrapped by
    // `previewText` — the ➡️FORWARDED➡️ envelope is gone by the time it
    // arrives, so the only place that can still see it is the caller.
    var forwardCount: Int? = nil
    /// __BUBBLE_MEDIA_2026_11_21__ Channel card: fixed width (nil = hug).
    var cardWidth: CGFloat? = nil
    /// Channel post whose comments bar is drawn inside the card.
    var commentsPost: (groupId: UUID, postId: UUID)? = nil

    @StateObject private var fetcher = LinkPreviewFetcher()
    @State private var detectedURL: String?

    private var hasURL: Bool { text.detectFirstURL() != nil }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // OG image on top — tappable to open link
            if let url = detectedURL, let preview = fetcher.previewData {
                Button {
                    if let openURL = URL(string: url) {
                        if !MapOpener.handleLink(openURL) { UIApplication.shared.open(openURL) } // place links → Settings › Maps
                    }
                } label: {
                    VStack(alignment: .leading, spacing: 0) {
                        if let uiImage = fetcher.previewUIImage {
                            GeometryReader { geo in
                                Image(uiImage: uiImage)
                                    .resizable()
                                    .aspectRatio(contentMode: .fill)
                                    .frame(width: geo.size.width, height: 180)
                                    .clipped()
                            }
                            .frame(height: 180)
                        }
                        // Site name + title
                        VStack(alignment: .leading, spacing: 3) {
                            if let siteName = preview.siteName {
                                Text(siteName.uppercased())
                                    .font(.caption2)
                                    .foregroundColor(isMine ? BubbleStyleManager.sentSecondaryInk : .secondary)
                            }
                            Text(preview.displayTitle)
                                .font(.scaled(14, weight: .semibold))
                                .foregroundColor(textColor)
                                .lineLimit(2)
                                .multilineTextAlignment(.leading)
                        }
                        .padding(.horizontal, 12)
                        .padding(.top, 8)
                        .padding(.bottom, 6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .buttonStyle(PlainButtonStyle())
            } else if fetcher.isLoading && detectedURL != nil {
                LinkPreviewLoadingView()
                    .padding(.bottom, 4)
            }

            // Message text — always shown inside the same bubble
            //
            // __FORWARD_BADGE_2026_08_18__ The badge is INSIDE the bubble,
            // directly above the body, because its ink is `.white.opacity(0.7)`
            // on my own message — readable on the accent fill, invisible over a
            // wallpaper. The 12/10 padding moved from the text onto this
            // wrapper: with no badge the geometry is byte-for-byte what it was,
            // and with one the badge shares the same inset instead of needing
            // its own.
            VStack(alignment: .leading, spacing: 0) {
                if let forwardCount {
                    ForwardedBadgeView(forwardCount: forwardCount, isFromCurrentUser: isMine)
                }
                // __TELEGRAM_WIDE_BUBBLES_2026_09_15__ Explicit `.body` at a
                // regular weight — Telegram's message body is a comfortable,
                // un-bold read. `weight: .regular` is the ClickableTextView
                // default, made explicit here so the group body can never
                // inherit a heavier environment font.
                // __LONG_MESSAGE_RERENDER_2026_11_09__ see ClickableTextView.swift
                // — this is the group's plain text bubble.
                ClickableTextView(text: text, textColor: textColor,
                                  linkColor: isMine ? BubbleStyleManager.sentAccentInk : Color(ChatPalette.quoteAccentUI),
                                  weight: .regular)
                    .equatable()
                    .font(.body)
                    .lineLimit(nil)
                    .multilineTextAlignment(.leading)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(maxWidth: cardWidth == nil ? nil : .infinity, alignment: .leading)

            if let post = commentsPost {
                Rectangle().fill(Color.primary.opacity(0.10)).frame(height: 0.5)
                GroupCommentsBar(groupId: post.groupId, postId: post.postId,
                                 isMine: false, maxWidth: .infinity, attached: true)
            }
        }
        .frame(width: cardWidth)
        .background(bubbleColor)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
            .stroke(isMine ? Color(ChatPalette.outgoingRimUI) : Color(ChatPalette.incomingRimLight).opacity(0.8),
                    lineWidth: 0.5))
        // __OLED_GLASS_2026_11_16__ received bubble = dark glass edge (no blur).
        .modifier(OLEDGlassIncomingEdge(isMine: isMine, radius: 18))
        // __GROUP_OPEN_HITCH_2026_11_22__ `.task`, not `.onAppear`: the
        // first-link scan runs off the main thread (LinkDetection) instead of
        // inside the open's first layout, once per bubble.
        .task(id: text) {
            guard detectedURL == nil,
                  let url = await LinkDetection.firstURLAsync(in: text),
                  detectedURL == nil else { return }
            detectedURL = url
            fetcher.fetchPreview(for: url, allowNetwork: isMine) // __LEAK_FIX_2026_09_25__
        }
    }

}

// MARK: - Message actions on the live group bubble path

/// Long-press for the action menu, plus the shared swipe. Attached to whatever
/// bubble view the group list actually renders.
///
/// Written as a ViewModifier rather than folded into `cleanBubble` because that
/// function is already near the type-checker's budget — SwiftUI gives up on
/// large view builders with an unhelpful "unable to type-check in reasonable
/// time", and this file has hit that before.
struct GroupMessageActions: ViewModifier {
    let message: GroupMessage
    let isMine: Bool
    let onReply: () -> Void
    let onLongPress: (CGRect) -> Void
    /// __OSHI_SMILE_REACTION_2026_11_09__ double-tap → toggle the OSHI smile.
    var onDoubleTap: () -> Void = {}
    /// __REACTION_MAC_MENU_2026_11_15__ Mac right-click → "React" submenu.
    var onReact: ((ReactionEmoji) -> Void)? = nil
    /// __CHANNEL_WEB_2026_11_26__ Mac right-click → "Web link…" (channel posts on the web).
    var onWebLink: (() -> Void)? = nil

    @State private var frame: CGRect = .zero

    func body(content: Content) -> some View {
        content
            .background(
                GeometryReader { geo in
                    Color.clear
                        .onAppear { frame = geo.frame(in: .global) }
                        .onChange(of: geo.frame(in: .global)) { _, f in frame = f }
                }
            )
            .modifier(SwipeToReply(isMine: isMine, onReply: onReply))
            // __BUBBLE_GESTURES_2026_11_09__ was `.onLongPressGesture(0.45)`,
            // which lost the touch to any child with its own gesture (link
            // card, quote, media, chip). See `BubbleReactionGestures`.
            .modifier(BubbleReactionGestures(
                onDoubleTap: onDoubleTap,
                onLongPress: {
                    Haptics.tap()
                    onLongPress(frame)
                }))
            #if targetEnvironment(macCatalyst)
            // Mac ONLY: on iOS a `.contextMenu` replaces the long-press
            // overlay (see the 19/10 note in ChatView). The Mac has no
            // long-press, so it gets the same "React" submenu as 1:1.
            .contextMenu {
                if let onReact {
                    ReactionContextMenu(onReact: onReact)
                }
                if let onWebLink {
                    Button { onWebLink() } label: { Label("cw_post_menu".localized, systemImage: "globe") }
                }
            }
            #endif
    }
}

/// Swipe right to reply — the gesture every messenger uses, shared by the 1:1
/// and group surfaces so they cannot drift apart.
struct SwipeToReply: ViewModifier {
    let isMine: Bool
    let onReply: () -> Void

    @State private var dx: CGFloat = 0
    @State private var fired = false

    /// How far the bubble travels before the reply commits.
    ///
    /// 56pt, with the bubble rubber-banded to 72 so it never leaves the screen.
    /// Too short and a horizontal wobble during a vertical scroll fires a reply;
    /// too long and the gesture feels stuck.
    private let threshold: CGFloat = 56

    func body(content: Content) -> some View {
        content
            .offset(x: dx)
            .overlay(alignment: .leading) {
                // Fades in with the drag, so the gesture explains itself the
                // first time instead of having to be discovered.
                Image(systemName: "arrowshape.turn.up.left.fill")
                    .font(.scaled(15))
                    .foregroundColor(.secondary)
                    .opacity(Double(min(abs(dx) / threshold, 1)) * 0.9)
                    .offset(x: -28)
                    .allowsHitTesting(false)
            }
            .simultaneousGesture(
                DragGesture(minimumDistance: 18)
                    .onChanged { g in
                        // Horizontal intent only. Without this a diagonal scroll
                        // drags every bubble sideways, which reads as the list
                        // being broken.
                        guard abs(g.translation.width) > abs(g.translation.height) * 1.5 else { return }
                        // Always the same direction regardless of who sent it:
                        // the gesture means one thing, so it should feel like
                        // one thing.
                        guard g.translation.width > 0 else { return }
                        dx = min(g.translation.width * 0.55, 72)
                        if dx >= threshold && !fired {
                            fired = true
                            Haptics.tap()
                        }
                    }
                    .onEnded { _ in
                        let commit = dx >= threshold
                        withAnimation(.spring(response: 0.28, dampingFraction: 0.75)) { dx = 0 }
                        fired = false
                        if commit { onReply() }
                    }
            )
    }
}

// MARK: - 🔒 __BOT_E2E_2026_09_23__ Bot encryption caption (BOT_SEAL_SPEC.md §3)

/// One short line under a bot message saying what protects it — and what does
/// not. Tap for the full sentence. Reads the latest seal kind recorded for the
/// group by `processBotEnvelope`; nothing is shown when none was recorded
/// (on-device bots, history from before this build): no claim beats a wrong one.
struct BotSealCaption: View {
    /// __FOLDABLE_LAYOUT_2026_11_16__ The thread's width (see `maxBubbleWidth(in:)`).
    @Environment(\.chatColumnWidth) private var chatColumnWidth
    let groupId: UUID
    @State private var expanded = false

    var body: some View {
        if let kind = BotSealedEnvelope.lastKind(groupId: groupId) {
            let keys = BotSealedEnvelope.captionKeys(kind)
            Button {
                expanded.toggle()
            } label: {
                HStack(alignment: .top, spacing: 4) {
                    Image(systemName: kind == .legacyPlain ? "lock.open" : "lock.fill")
                        .font(.caption2)
                    Text(expanded ? keys.full.localized : keys.short.localized)
                        .font(.caption2)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundColor(.secondary)
                .frame(maxWidth: BubbleStyleManager.maxBubbleWidth(in: chatColumnWidth), alignment: .leading)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(keys.full.localized)
        }
    }
}


// MARK: - GIFs in groups (__GROUP_GIF_PICKER_2026_11_16__)

/// What a GIF picked in `StickerGifPicker` becomes in a GROUP message.
///
/// The group `MediaType` has no `gif` case: a GIF travels as `photo`, raw GIF
/// bytes, file name `*.gif` — the format group GIFs from the gallery and from
/// Android already use. Every group receiver decides "GIF" from the BYTES
/// (`GIF87a`/`GIF89a`), never from the label: iOS `LazyGroupPhotoView`
/// (`MediaManager.isGIFData` → `GIFBubbleView`), Android `GroupChatScreen`
/// (`BubbleAsyncImage`, Coil with `ImageDecoderDecoder`/`GifDecoder`), desktop
/// `MessageBubble` (`GifBubble.isGifFile`). So the bytes must never be
/// re-encoded on the way: nil for anything that is not a real GIF.
enum GroupGifSend {
    static func groupMedia(_ attachment: MediaManager.MediaAttachment)
        -> (data: Data, type: MediaType, fileName: String)? {
        guard MediaManager.isGIFData(attachment.data) else { return nil }
        let name = attachment.fileName.isEmpty ? "gif_\(Int(Date().timeIntervalSince1970)).gif" : attachment.fileName
        let fileName = name.lowercased().hasSuffix(".gif") ? name : name + ".gif"
        return (attachment.data, .photo, fileName)
    }
}


// MARK: - Group "…" menu  __MENU_SCROLL_RESET_2026_11_21__
//
// See `ChatOverflowMenu` (ChatView.swift): a SwiftUI menu refreshed while open
// is replaced by UIKit and loses its scroll position. Compared by value, and
// the UIMenu is owned by `StableMenuButton`.
struct GroupOverflowMenu: View, Equatable {
    struct MenuState: Equatable {
        var canAddMembers: Bool
        var muted: Bool
        var blocked: Bool
        var admin: Bool
        /// 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__
        var advancedPrivacy: Bool = false
    }

    struct Actions {
        var openInfo: () -> Void
        var addMember: () -> Void
        var openWallpaper: () -> Void
        var toggleMute: () -> Void
        var toggleAdvancedPrivacy: () -> Void = {}
        var report: () -> Void
        var unblock: () -> Void
        var block: () -> Void
        var leave: () -> Void
    }

    let state: MenuState
    let actions: Actions

    static func == (lhs: GroupOverflowMenu, rhs: GroupOverflowMenu) -> Bool {
        lhs.state == rhs.state
    }

    var sections: [[OverflowMenuItem]] {
        var first: [OverflowMenuItem] = [.init(id: "info", title: "menu_group_info".localized, symbol: "info.circle")]
        if state.canAddMembers {
            first.append(.init(id: "add", title: "menu_add_member".localized, symbol: "person.badge.plus"))
        }
        // __WALLPAPER_FOR_EVERY_MEMBER_2026_11_21__ Always offered. Without the
        // group permission (admin-only group, channel) the choice is kept on
        // this device only instead of being sent to the members — it used to be
        // hidden outright, so a channel reader could not choose a backdrop.
        first.append(.init(id: "wallpaper", title: "wallpaper.title".localized, symbol: "photo.artframe"))
        first.append(state.muted
            ? .init(id: "mute", title: "group.unmute".localized, symbol: "bell.fill")
            : .init(id: "mute", title: "group.mute".localized, symbol: "bell.slash.fill"))
        return [
            first,
            // 🛡 __ADVANCED_CHAT_PRIVACY_2026_11_26__ any member may flip it.
            [AdvancedChatPrivacyMenu.item(isOn: state.advancedPrivacy)],
            // 🚩 Report (App Store guideline 1.2) — always available, even for an
            // already-blocked group.
            [.init(id: "report", title: "group.report_group".localized, symbol: "flag.fill"),
             state.blocked
                ? .init(id: "unblock", title: "group.unblock".localized, symbol: "hand.raised.slash")
                : .init(id: "block", title: "group.block".localized, symbol: "hand.raised.fill")],
            [.init(id: "leave",
                   title: state.admin ? "button_delete_group".localized : "button_leave_group".localized,
                   symbol: state.admin ? "trash" : "rectangle.portrait.and.arrow.right",
                   destructive: true)],
        ]
    }

    /// __GROUP_HEADER_ISLAND_2026_11_24__ also run by the header avatar's long-press menu.
    func select(_ id: String) { perform(id) }

    private func perform(_ id: String) {
        switch id {
        case "info": actions.openInfo()
        case "add": actions.addMember()
        case "wallpaper": actions.openWallpaper()
        case "mute": actions.toggleMute()
        case AdvancedChatPrivacyMenu.itemId: actions.toggleAdvancedPrivacy()
        case "report": actions.report()
        case "unblock": actions.unblock()
        case "block": actions.block()
        case "leave": actions.leave()
        default: break
        }
    }

    var body: some View {
        StableMenuButton(sections: sections, identifier: "group_overflow_menu", onSelect: perform)
    }
}
