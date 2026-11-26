//
//  MessagesListView.swift
//  ✅ IMPROVED: Search within messages + Modern UX + Address/QR button
//  ✅ FULLY LOCALIZED: All user-facing strings use .localized
//

import SwiftUI

// MARK: - Brand Color
private let appBrandColor = Theme.brand
import CoreImage.CIFilterBuiltins

// MARK: - Brand Color

// MARK: - Brand Color
/// OSHI Brand Color: #5616EC
let oshiBrandColor = Theme.brand

struct MessagesListView: View {
    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var identityManager: IdentityManager
    @EnvironmentObject var meshManager: MeshNetworkManager
    @ObservedObject private var liveLocation = LocationSharingManager.shared
    /// __ANATOLE_1311_IOS__ (N8) Pin / mute per conversation.
    @ObservedObject private var conversationPrefs = ConversationPrefsStore.shared
    /// (N4) Which call the compose menu's contact picker will place.
    @State private var callPicker: ComposeCallKind?
    /// (N8) "Open contact" from a row's long-press menu.
    @State private var profileConversation: Conversation?
    @State private var callErrorMessage: String?

    /// (N4) Posted by the compose menu's "New contact"; MainTabView selects the
    /// New tab.
    static let selectNewTabNotification = Notification.Name("OSHISelectNewTab")

    enum ComposeCallKind: String, Identifiable {
        case voice, video
        var id: String { rawValue }
        var isVideo: Bool { self == .video }
    }
    @State private var searchText = ""
    @State private var isSearching = false
    @State private var searchResults: [SearchResult] = []
    @State private var isSearchingContent = false
    @AppStorage("contactAliases") private var contactAliasesData: Data = Data()
    // __SHARED_NICKNAME_2026_09_22__ observe pour redessiner quand un pseudo arrive.
    @AppStorage(SharedNickname.defaultsKey) private var sharedNicknamesData: Data = Data()

    // QR/Address sheet
    @State private var showAddressSheet = false
    @State private var showMyQRCode = false
    /// __FIRST_CONTACT_REMOTE_2026_10_10__ The share sheet carrying my invite link.
    @State private var showInviteShare = false
    @State private var selectedConversation: Conversation?

    // Export backup
    @State private var showExportSheet = false
    @State private var exportURL: URL?
    @State private var isExporting = false

    // Navigation for new chat (push navigation, not modal sheet)
    @Binding var pendingConversationId: String?

    /// __MERGED_CHATS_2026_11_18__ « Fusionner Messages et Groupes » (Settings ›
    /// Appearance). True: this list shows the groups too (`MergedChatsList`), the
    /// compose button opens the "New…" sheet, and ⌘N / ⇧⌘N start a chat / a
    /// group. False — the default for existing users — is this view unchanged.
    var merged: Bool = false
    /// Merged mode: the group a notification / deep link asked to open.
    var pendingGroupId: Binding<String?> = .constant(nil)
    @State private var showNewSheet = false
    @State private var pendingNewAction: MergedNewAction?
    @State private var showCreateGroup = false
    @State private var showDiscoverGroups = false
    /// __FOLDABLE_LAYOUT_2026_11_16__ The open thread is a value owned by the
    /// Chats tab (see AdaptiveThreadLayout.swift): pushed here on a phone, shown
    /// beside this list when the window is wide. Replaces the old
    /// `newChatConversation` + `navigateToNotificationChat` pair.
    @EnvironmentObject private var threadSelection: ThreadSelection
    @Environment(\.threadSplitActive) private var splitActive

    /// The conversation the deep-link / start-chat paths consider "on screen".
    private var openConversationKey: String? {
        if case .conversation(let key)? = threadSelection.route { return key }
        return nil
    }

    // QR Scanner (scan → open/create conversation, like Android)
    @State private var showQRScanner = false
    /// __ANTENNA_IS_A_DOOR_2026_10_27__ Owner report N6. The toolbar antenna
    /// reported the mesh state and swallowed every tap on BOTH platforms. It now
    /// opens the nearby-peers screen, presented exactly as `MoreTabView` already
    /// presents it so the two entry points behave identically.
    @State private var showNearbyPeers = false
    @State private var scannedQRAddress = ""
    
    private var contactAliases: [String: String] {
        // WHY: empty Data() is the AppStorage default — don't log a decode
        // failure for it. Computed properties run on every SwiftUI body
        // invocation; combined with file-backed logging that's 20k log
        // writes/sec post-call, which froze the app for ~10s.
        guard !contactAliasesData.isEmpty else { return [:] }
        return ContactAliasesMemo.map(contactAliasesData)
    }

    /// __SHARED_NICKNAME_2026_09_22__ Nom affiche: alias local > pseudo partage
    /// par le contact > nil (la ligne retombe alors sur l'adresse courte).
    ///
    /// __LAUNCH_LAG_2026_11_22__ Called once per conversation ROW on every body
    /// pass. It used to JSON-decode both tables and scan the whole nickname map
    /// per row — measured as ~2 s of main thread in the first 8 s of a cold
    /// launch on the owner's iPhone 17 Pro Max. Both tables are now memoised on
    /// their bytes and the nickname lookup is a single hash hit.
    private func displayName(for publicKey: String) -> String? {
        SharedNickname.resolve(
            alias: contactAliases[publicKey],
            nickname: SharedNickname.table(sharedNicknamesData).nickname(for: publicKey)
        )
    }

    // Search result model
    struct SearchResult: Identifiable {
        let id = UUID()
        let conversation: Conversation
        let matchType: MatchType
        let matchedText: String?
        let messageTimestamp: Date?
        
        enum MatchType {
            case contactName
            case address
            case messageContent
        }
    }
    
    var filteredConversations: [Conversation] {
        if searchText.isEmpty {
            // __ANATOLE_1311_IOS__ (N8) pinned threads first, each half still
            // newest-first. Search results stay in plain recency order.
            return ConversationPrefsStore.pinnedFirst(messageManager.conversations,
                                                      pinned: conversationPrefs.pinned,
                                                      key: { $0.participantPublicKey })
        } else {
            // Return conversations that match by contact name or address
            return messageManager.conversations.filter { conversation in
                let alias = displayName(for: conversation.participantPublicKey) ?? conversation.participantAddress
                return alias.localizedCaseInsensitiveContains(searchText) ||
                       conversation.participantAddress.localizedCaseInsensitiveContains(searchText) ||
                       conversation.participantPublicKey.localizedCaseInsensitiveContains(searchText)
            }
        }
    }
    
    /// __LIVE_SHARE_COUNTER_2026_09_22__ Pastille « je partage ma position »:
    /// le nombre de partages en cours. Un seul → un appui ouvre la conversation.
    /// Plusieurs → un menu, un contact par ligne, avec l'heure de fin.
    /// Extrait de `body` pour rester sous le budget du vérificateur de types.
    /// The name the conversation ROW shows for this contact (alias, else the
    /// shortened address) — so the menu and the list never disagree.
    private func liveShareName(_ share: LocationSharingManager.ActiveLiveShare) -> String {
        if let name = displayName(for: share.conversationId) { return name }
        let address = messageManager.conversations
            .first { $0.participantPublicKey == share.conversationId }?.participantAddress ?? share.conversationId
        guard address.count > 10 else { return address }
        return "\(address.prefix(6))...\(address.suffix(4))"
    }

    @ViewBuilder
    private var liveShareCounter: some View {
        let shares = liveLocation.activeLiveShares
        if shares.count == 1, let share = shares.first {
            Button {
                navigateToConversation(byPublicKey: share.conversationId)
            } label: {
                LiveShareCounterLabel(count: 1)
            }
            .accessibilityLabel("location.sharing_live".localized + ", " + liveShareName(share))
            .accessibilityIdentifier("live_share_counter")
        } else if shares.count > 1 {
            Menu {
                ForEach(shares) { share in
                    Button {
                        navigateToConversation(byPublicKey: share.conversationId)
                    } label: {
                        // Two Texts directly in the label = title + subtitle in a menu.
                        Text(liveShareName(share))
                        Text("\("location.expires_in".localized) \(share.endTime.formatted(date: .omitted, time: .shortened))")
                    }
                }
            } label: {
                LiveShareCounterLabel(count: shares.count)
            }
            .accessibilityLabel("location.sharing_live".localized + ", \(shares.count)")
            .accessibilityIdentifier("live_share_counter")
        }
    }

    var body: some View {
        NavigationStack(path: merged ? threadSelection.pathForAnyKind(split: splitActive)
                                     : threadSelection.path(for: .conversation, split: splitActive)) {
            ZStack {
                if merged {
                    mergedContent
                } else {
                // Dynamic mesh backdrop for the empty state — a royalty-free
                // generated network image with a slow parallax drift.
                if messageManager.conversations.isEmpty {
                    DynamicMeshBackground()
                        .ignoresSafeArea()
                }

                if messageManager.conversations.isEmpty {
                    EmptyStateView(
                        onShowMyCode: { showMyQRCode = true },
                        onScanCode: { showQRScanner = true },
                        onInviteByLink: { showInviteShare = true }
                    )
                } else {
                    VStack(spacing: 0) {
                        // Search Results Section (when searching content)
                        if !searchText.isEmpty && !searchResults.isEmpty {
                            searchResultsSection
                        }

                        // Conversations List
                        conversationsList
                    }
                    // Cap content width on iPad / landscape so rows don't
                    // stretch edge-to-edge; centered for a balanced layout.
                    // __MAC_WIDTH_2026_08_13__ The same 700pt cap on the Mac left
                    // the conversation rows in a narrow centre column while the
                    // title and the search field spanned the whole window — a
                    // mismatch that reads as an iPad app dropped into a Mac window,
                    // with two empty white gutters. A Mac window is sized
                    // deliberately by the user, so honour the width they chose.
                    #if targetEnvironment(macCatalyst)
                    .frame(maxWidth: .infinity)
                    #else
                    .frame(maxWidth: 700)
                    .frame(maxWidth: .infinity)
                    #endif
                }
                }
            }
            .sidebarColumnContent(splitActive)
            .navigationTitle("nav_title_messages".localized)
            .searchable(
                text: $searchText,
                placement: .navigationBarDrawer(displayMode: .always),
                // __ANATOLE_1311_IOS__ (N1) One word. "Search messages &
                // contacts" / "Rechercher messages et contacts" was truncated on
                // a narrow phone; the field is already a single rounded line.
                prompt: "search.title".localized
            )
            .onChange(of: searchText) { _, newValue in
                if newValue.isEmpty {
                    searchResults = []
                    isSearchingContent = false
                } else {
                    performSearch(query: newValue)
                }
            }
            // __PANIC_WIPE_2026_11_03__ The title becomes a tappable wordmark.
            //
            // A large "Messages" title cannot carry a gesture, and the panic
            // switch has to live somewhere a hand can find without looking: no
            // menu entry and no settings row, because a switch you have to go
            // and find is not one, and a labelled button reading "erase
            // everything" is a button somebody can be made to press.
            //
            // This is a VISIBLE change — an inline "OSHI" in place of the large
            // localised title. It moves iOS toward Android, whose top bar has
            // read "OSHI" since the toolbar work in September, and it is where
            // bitchat puts the same gesture, so the muscle memory transfers.
            .navigationBarTitleDisplayMode(.inline)
            // __GLASS_ALL_THEMES_2026_11_21__ let ChatsTabView's ground through.
            .glassNavigationSeeThrough()
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("OSHI")
                        .font(.headline)
                        .fontWeight(.bold)
                        // Three taps, and nothing visible to say so: no
                        // highlight, no ripple, nothing that hints the gesture
                        // exists on a screen somebody else is looking at.
                        //
                        // __ANATOLE_1311_IOS__ (N6) Only when the user armed it
                        // in Settings › Security; off by default.
                        .onTapGesture(count: PanicGesture.tapsRequired) {
                            guard PanicGesture.isArmed() else { return }
                            PanicWipe.run(mesh: meshManager, identity: identityManager)
                        }
                }
                // __DUO_POLISH_2026_11_18__ A group, not an HStack in one item:
                // on a folded iPhone Duo each bar item becomes one round button
                // in the side rail, and the pair overflowed a single circle.
                // Both are still always present (see __TOOLBAR_DRIFT__ below).
                ToolbarItemGroup(placement: .navigationBarLeading) {
                        Button {
                            showQRScanner = true
                        } label: {
                            Image(systemName: "qrcode.viewfinder")
                                .foregroundColor(.primary)
                        }
                        .accessibilityLabel("a11y.scan_qr".localized)
                        .accessibilityHint("a11y.scan_qr.hint".localized)

                        MeshStatusBadge { showNearbyPeers = true }
                }
                // Mesh-connected indicator: only visible when at least one peer
                // is connected over the local mesh (MultipeerConnectivity).
                //
                // 🔧 FIX (2026-08-05): the item itself must ALWAYS exist. It used
                // to be `if !connectedPeers.isEmpty { badge }`, so the toolbar
                // item vanished and reappeared — and MultipeerConnectivity peers
                // flap constantly (they are appended on connect and swept on a
                // staleness pass). Every flip re-laid out the navigation bar,
                // which with a large title plus `.searchable` reads as the
                // "Messages" heading dropping out and coming back while the list
                // appears to shift under it.
                //
                // Swapping an item's CONTENT is cheap; adding and removing the
                // item is what moves the bar.
                //
                // __TOOLBAR_DRIFT_2026_09_22__ Second half of the same bug, the
                // part that was still there: the badge started its pulse with
                // `withAnimation(.repeatForever)` in `onAppear`. In a navigation
                // bar that transaction also catches the badge's FIRST placement,
                // so its position is animated back and forth forever — and it
                // re-appeared on every peer flap, re-arming it each time. The
                // status now lives in the SAME item as the QR button, is always
                // drawn (grey when off-grid, green when connected), and no longer
                // runs a repeat-forever transaction at all. See `MeshStatusBadge`.
                ToolbarItem(placement: .navigationBarTrailing) {
                    HStack(spacing: 10) {
                        liveShareCounter
                        if merged {
                            mergedNewButton
                        } else {
                            composeMenu
                        }
                        Menu {
                            Button {
                                // Export backup
                                exportChatBackup()
                            } label: {
                                Label("menu_export_backup".localized, systemImage: "square.and.arrow.up")
                            }
                            .disabled(isExporting)

                            Button(role: .destructive) {
                                messageManager.clearAllMessages()
                            } label: {
                                Label("menu_clear_all".localized, systemImage: "trash")
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                                .foregroundColor(.primary)
                        }
                        .accessibilityLabel("a11y.more_options".localized)
                    }
                }
            }
            .sheet(isPresented: $showQRScanner) {
                QRScannerView(scannedAddress: $scannedQRAddress)
            }
            // __MAIL_COVER_WAS_UNREACHABLE_2026_11_06__ A
            // `fullScreenCover(isPresented: $showMail) { MailView() }` used to
            // live here, from when the mailbox was a Messages-list menu entry.
            // Nothing ever set `showMail` once Email became its own segment in
            // ChatsTabView, so this presentation was unreachable — and it was
            // the sole justification for MailView's leading close button, which
            // therefore did nothing at all. Both are gone.
            .onChange(of: showQRScanner) { _, isShowing in
                // __MERGED_CHATS_2026_11_18__ "Scan an invite": a GROUP invite goes
                // to the app's own deep-link handler (the same join sheet as a
                // tapped link) instead of being mistaken for a contact key.
                if merged, !isShowing, let invite = ChatListMerge.groupInviteURL(from: scannedQRAddress) {
                    scannedQRAddress = ""
                    SharedContentHandler.shared.handleURL(invite)
                    return
                }
                if !isShowing && !scannedQRAddress.isEmpty {
                    let trimmed = scannedQRAddress.trimmingCharacters(in: .whitespacesAndNewlines)
                    // QR codes encode our deep-link URL: `<base>?key=<percent-encoded-pubkey>`.
                    // Extract the raw public key from the URL — without this, the
                    // whole URL was being stored as `participantPublicKey`, so the
                    // conversation existed but was un-routable for sends and the
                    // navigation lookup compared URL-against-URL (matched but
                    // useless). Manual-entry path also goes through here, so we
                    // accept both: a bare public key OR a URL with a `key=` query.
                    let extractedKey = Self.extractPublicKeyFromQRPayload(trimmed)
                    guard extractedKey.count >= 20, extractedKey != identityManager.publicKey else {
                        scannedQRAddress = ""
                        return
                    }

                    // 🪦 Auto-stale on contact replacement: the actual alias-
                    // collision check runs in `handleStartChatNotification`
                    // (where the alias is set) — that's the only point where
                    // we have BOTH the new key AND the alias the user wants
                    // to reuse. The pure QR-scan path here doesn't yet know
                    // the alias the user will assign, so a check here would
                    // be a guess. See `StaleIdentityManager.shared.markStale`
                    // call in handleStartChatNotification.

                    // Create conversation if needed
                    messageManager.addPendingConversation(publicKey: extractedKey, identityManager: identityManager)
                    // Navigate directly (we're already on the Messages tab)
                    scannedQRAddress = ""
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                        navigateToConversation(byPublicKey: extractedKey)
                    }
                }
            }
            #if DEBUG
            .task {
                // Demo conversations land after the first frame.
                try? await Task.sleep(for: .seconds(2))
                Self.applyStatusHarness(conversations: messageManager.conversations)
            }
            #endif
            .onAppear {
                MessageManager.sharedIdentityManager = identityManager
                messageManager.refreshConversations()
                // __EARLIER_ASK_2026_09_06__ natural pause after a conversation.
                RateAppManager.shared.evaluatePendingMilestone(source: "conversation-list")
            }
            // Every way into a conversation (row, search result, notification,
            // QR scan, "start chat") is a `ThreadRoute`; on a phone it is pushed
            // here, when wide the stack stays at its root (see `path(for:)`).
            .navigationDestination(for: ThreadRoute.self) { route in
                ThreadDestinationView(route: route)
            }
        }
        // __ANATOLE_1311_IOS__ (N4) Pick a contact, then call them.
        .sheet(item: $callPicker) { kind in
            ContactSelectorView(
                onContactSelected: { key, _ in startCall(to: key, video: kind.isVideo) },
                confirmTitle: kind.isVideo ? "chat.video_call".localized : "chat.voice_call".localized,
                includeKey: { Self.isCallable($0) }
            )
            .environmentObject(messageManager)
            .environmentObject(identityManager)
        }
        // __ANATOLE_1311_IOS__ (N8) The contact card from the row's menu, with
        // the same call buttons the chat hands it.
        .sheet(item: $profileConversation) { conversation in
            ContactProfileView(
                publicKey: conversation.participantPublicKey,
                alias: displayName(for: conversation.participantPublicKey),
                onCall: Self.isCallable(conversation.participantPublicKey)
                    ? { video in startCall(to: conversation.participantPublicKey, video: video) }
                    : nil
            )
        }
        // __MERGED_CHATS_2026_11_18__ the "New…" sheet and the two group sheets
        // the Groups segment used to present. Inert while `merged` is false:
        // nothing sets their flags.
        .sheet(isPresented: $showNewSheet, onDismiss: performPendingNewAction) {
            NewChatSheetView { pendingNewAction = $0 }
        }
        .sheet(isPresented: $showCreateGroup) {
            CreateGroupView()
        }
        .sheet(isPresented: $showDiscoverGroups) {
            DiscoverPublicGroupsView()
        }
        .alert("common.error".localized,
               isPresented: Binding(get: { callErrorMessage != nil },
                                    set: { if !$0 { callErrorMessage = nil } })) {
            Button("common.ok".localized, role: .cancel) {}
        } message: {
            Text(callErrorMessage ?? "")
        }
        .sheet(isPresented: $showAddressSheet) {
            if let conversation = selectedConversation {
                ContactAddressSheet(
                    conversation: conversation,
                    alias: displayName(for: conversation.participantPublicKey)
                )
            }
        }
        // "Afficher mon code" from the empty-state onboarding shows THIS user's own QR,
        // not a contact's address sheet (which needs a selectedConversation and rendered
        // blank/black from the empty state).
        .sheet(isPresented: $showMyQRCode) {
            QRCodeView(address: identityManager.identityAddress, publicKey: identityManager.publicKey)
        }
        // __ANTENNA_IS_A_DOOR_2026_10_27__ Same presentation as MoreTabView's
        // "Nearby" row: PeersView brings its own navigation, so the close button
        // is supplied here.
        .fullScreenCover(isPresented: $showNearbyPeers) {
            NavigationStack {
                PeersView()
                    .toolbar {
                        ToolbarItem(placement: .navigationBarLeading) {
                            Button { showNearbyPeers = false } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.title3)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
            }
        }
        // __FIRST_CONTACT_REMOTE_2026_10_10__ The path that works when the
        // other person is somewhere else. The system share sheet on purpose:
        // it lists the apps this person already uses — Messages, WhatsApp,
        // Mail — rather than asking them to learn ours.
        .sheet(isPresented: $showInviteShare) {
            ShareSheet(items: InviteLink.shareItems(
                forMyPublicKey: identityManager.publicKey,
                senderName: UserDefaults.standard.string(forKey: "userName")))
        }
        .sheet(isPresented: $showExportSheet, onDismiss: {
            if let url = exportURL { try? FileManager.default.removeItem(at: url) }
            exportURL = nil
        }) {
            if let url = exportURL {
                ShareSheet(items: [url])
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("StartChatWithContact"))) { notification in
            handleStartChatNotification(notification)
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("NavigateToChat"))) { notification in
            // Handle NavigateToChat as well for backward compatibility
            handleStartChatNotification(notification)
        }
        // MARK: - Notification Deep Link: handled via pendingConversationId from MainTabView
        // "OpenChat" and "OpenConversation" are caught by MainTabView which switches tab first,
        // then sets pendingConversationId — avoids race where navigation fires before tab is visible.
        //
        // Both .onChange and .onAppear may fire for the SAME value-set on tab mount
        // (SwiftUI doesn't guarantee an order). Without a synchronous capture-and-clear,
        // the chat pushed twice — visible as "open → bounce back to list → re-open".
        // Setting pendingConversationId = nil at the top of each handler makes the
        // other handler observe nil and short-circuit.
        .onChange(of: pendingConversationId) { _, newValue in
            guard let publicKey = newValue else { return }
            pendingConversationId = nil
            navigateToConversation(byPublicKey: publicKey)
        }
        .onAppear {
            // Check for pending navigation when view appears (tab switch).
            // navigateToConversation has its own 5-attempt retry chain, so we
            // don't need to wait an additional 0.3s here — the previous wait
            // was a major contributor to the visible "Messages list flash"
            // before the chat opened. Android does this in a single nav-graph
            // transaction; this brings iOS closer to that.
            guard let publicKey = pendingConversationId else { return }
            pendingConversationId = nil
            navigateToConversation(byPublicKey: publicKey)
        }
    }
    
    #if DEBUG
    /// Simulator harness for the top-bar status (no GPS radio, no LoRa radio there).
    /// Drives the REAL code paths so the rendering can be checked:
    ///   `-OSHIFakeLiveShares N` starts N real live shares on the first N
    ///      conversations (location must be granted: `simctl privacy grant location`).
    ///   `-OSHIFakeLoRa N` marks the radio ready with N nodes heard now, plus our
    ///      own node and one heard 3 h ago — neither may be counted.
    private static var didApplyStatusHarness = false
    private static func applyStatusHarness(conversations: [Conversation]) {
        guard !didApplyStatusHarness else { return }
        let defaults = UserDefaults.standard
        let shares = defaults.integer(forKey: "OSHIFakeLiveShares")
        let lora = defaults.integer(forKey: "OSHIFakeLoRa")
        guard shares > 0 || lora > 0 else { return }
        didApplyStatusHarness = true

        if lora > 0 {
            let radio = MeshtasticManager.shared
            radio.myNodeNum = 0x0A11CE
            func node(_ num: UInt32, _ name: String, _ heard: Date) -> MeshtasticNode {
                MeshtasticNode(num: num, userId: String(format: "!%08x", num), longName: name, shortName: String(name.prefix(4)),
                               lastHeard: heard, snr: 5, latitude: nil, longitude: nil, hopsAway: 0, rssi: -90)
            }
            var nodes = [node(0x0A11CE, "Me", Date()), node(0x0DEAD0, "Stale", Date().addingTimeInterval(-3 * 3600))]
            for i in 0..<lora { nodes.append(node(UInt32(0x100 + i), "Node\(i)", Date().addingTimeInterval(-Double(i) * 60))) }
            radio.nodes = nodes
            radio.state = .ready
        }
        let location = LocationSharingManager.shared
        for (i, conversation) in conversations.prefix(shares).enumerated() {
            location.startLiveSharingWithCustomDuration(durationSeconds: TimeInterval(15 + 15 * i) * 60,
                                                        conversationId: conversation.participantPublicKey,
                                                        onUpdate: { _ in })
        }
    }
    #endif

    // MARK: - Start Chat Handler
    
    private func handleStartChatNotification(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let publicKey = userInfo["publicKey"] as? String else {
            OshiLog.messaging.info("❌ StartChatWithContact: Missing publicKey")
            return
        }
        
        let alias = userInfo["alias"] as? String ?? ""
        
        OshiLog.messaging.info("📱 StartChatWithContact received:")
        OshiLog.messaging.info("   Public Key: \(publicKey.prefix(20))...")
        OshiLog.messaging.info("   Alias: \(alias)")
        
        // Check if conversation already exists.
        // __KEY_FOLD_2026_08_13__ Exact match missed a conversation stored under a
        // different encoding of the SAME key and silently created a duplicate. The
        // author key shipped in the welcome card is standard base64 with `/` and a
        // trailing `=`; anything that came back through the relay is base64url and
        // unpadded. Fold both before comparing.
        var targetConversation: Conversation
        let wantedKey = MessageManager.normalizeAliasKey(publicKey)
        if let existing = messageManager.conversations.first(where: {
            MessageManager.normalizeAliasKey($0.participantPublicKey) == wantedKey
        }) {
            OshiLog.messaging.info("✅ Found existing conversation")
            targetConversation = existing
        } else {
            // Create new conversation with a placeholder message
            OshiLog.messaging.info("➕ Creating new conversation")

            // Create a placeholder encrypted message
            let placeholderEncrypted = EncryptedMessage(
                ciphertext: "",
                signature: "",
                senderPublicKey: "",
                timestamp: Date()
            )

            // Create placeholder SecureMessage for new conversation
            let placeholderMessage = SecureMessage(
                id: UUID().uuidString,
                senderAddress: "",
                recipientAddress: String(publicKey.prefix(8)),
                encryptedContent: placeholderEncrypted,
                timestamp: Date(),
                isRead: true,
                deliveryStatus: .delivered
            )

            let newConversation = Conversation(
                participantAddress: String(publicKey.prefix(8)),
                participantPublicKey: publicKey,
                lastMessage: placeholderMessage,
                unreadCount: 0
            )

            // __CONTACT_CARD_ALIAS_2026_11_20__ Every poster of this notification
            // is a RECEIVED contact card, so `alias` is whatever the sender typed.
            // It used to be saved as our local name for that key, and if another
            // contact already had that name ("Maman"), that REAL contact's key was
            // marked stale (stops ringing, group traffic diverted) "as replaced
            // by" the card's key — one tap on "Start chat" let anyone take over a
            // contact's name. The suggestion is now kept only when it collides
            // with nobody, and nothing is ever marked stale from a card.
            let aliases = (try? JSONDecoder().decode([String: String].self, from: contactAliasesData)) ?? [:]
            if let safe = Self.cardAliasToSave(alias, for: publicKey, existing: aliases) {
                var updated = aliases
                updated[publicKey] = safe
                if let data = try? JSONEncoder().encode(updated) {
                    contactAliasesData = data
                    OshiLog.messaging.info("💾 Saved alias from card")
                }
            }

            // Add to conversations
            messageManager.conversations.insert(newConversation, at: 0)
            targetConversation = newConversation
            // __EARLY_ASK_2026_10_09__ Same moment as `addPendingConversation`,
            // reached from a shared contact card instead of the QR sheet.
            RateAppManager.shared.recordContactAdded()
        }

        // Navigate to chat (push navigation into NavigationView)
        // __START_CHAT_FROM_INSIDE_A_CHAT_2026_08_13__ This set the flag to true
        // without ever clearing it. When the tap comes from a contact card that is
        // ITSELF inside an open chat — the welcome message's card for the OSHI
        // author is exactly that — `navigateToNotificationChat` is ALREADY true,
        // so assigning true again is a no-op and the button did nothing at all.
        // navigateToConversation(byPublicKey:) below already solved this; the same
        // rule belongs here. Only bounce the flag when a chat for a DIFFERENT peer
        // is on screen, so a first open still pushes immediately with no flash.
        let targetKey = MessageManager.normalizeAliasKey(targetConversation.participantPublicKey)
        let isAlreadyOpen = openConversationKey == targetKey

        if isAlreadyOpen {
            // The card points at the conversation the user is ALREADY reading.
            // Pushing it again is a no-op and reads as a dead button, so say
            // "you're here, write" instead: focus the composer.
            OshiLog.messaging.info("✍️ Already in this chat — focusing composer")
            NotificationCenter.default.post(name: NSNotification.Name("FocusChatComposer"), object: nil)
        } else {
            // A different chat (or none) is on screen. The route replaces it in
            // one step — the old "lower the flag, wait 0.15 s, raise it" dance
            // was needed only by `navigationDestination(isPresented:)`.
            threadSelection.open(targetConversation)
            OshiLog.messaging.info("🚀 Navigating to chat...")
            Self.focusComposerOnceMounted()
        }
    }

    /// __CONTACT_CARD_ALIAS_2026_11_20__ The name a received card suggests, or
    /// nil when it must not be stored: empty, or already used (case-/space-
    /// insensitively) by a DIFFERENT key. Control characters stripped, 64 max.
    static func cardAliasToSave(_ alias: String, for key: String, existing: [String: String]) -> String? {
        let cleaned = String(alias.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) })
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let safe = String(cleaned.prefix(64))
        guard !safe.isEmpty else { return nil }
        let norm = safe.lowercased()
        let taken = existing.contains { otherKey, otherAlias in
            otherKey != key && otherAlias.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == norm
        }
        return taken ? nil : safe
    }

    /// "Start chat" means the user intends to WRITE, so the freshly opened chat
    /// should be ready for it. Delayed because the ChatView that listens for this
    /// does not exist yet at the moment the push is requested — posting
    /// immediately would reach nobody.
    private static func focusComposerOnceMounted() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
            NotificationCenter.default.post(name: NSNotification.Name("FocusChatComposer"), object: nil)
        }
    }
    
    // MARK: - Open Chat from Notification (Deep Link)

    /// Handles "OpenConversation" — posted by NotificationManager with conversationId (= publicKey)
    private func handleOpenConversationNotification(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let conversationId = userInfo["conversationId"] as? String else {
            OshiLog.messaging.info("❌ OpenConversation: Missing conversationId")
            return
        }
        OshiLog.messaging.info("📱 OpenConversation notification: \(conversationId.prefix(20))...")
        navigateToConversation(byPublicKey: conversationId)
    }

    /// Handles "OpenChat" — posted by PushNotificationManager/SecureWeb3MessengerApp with peerPublicKey
    private func handleOpenChatNotification(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let peerPublicKey = userInfo["peerPublicKey"] as? String else {
            OshiLog.messaging.info("❌ OpenChat: Missing peerPublicKey")
            return
        }
        OshiLog.messaging.info("📱 OpenChat notification: \(peerPublicKey.prefix(20))...")
        navigateToConversation(byPublicKey: peerPublicKey)
    }

    /// Pull the raw public-key string out of a scanned/manual QR payload.
    /// Accepts:
    ///   - `https://oshi-messenger.org/add?key=<percent-encoded-pubkey>` (our QR format)
    ///   - any other URL with `?key=<value>` somewhere
    ///   - a bare public key (manual entry / legacy QR)
    /// Returns the trimmed input unchanged if no `key` query is found.
    static func extractPublicKeyFromQRPayload(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if let url = URL(string: trimmed),
           let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
           let keyItem = components.queryItems?.first(where: { $0.name == "key" }),
           let keyValue = keyItem.value,
           !keyValue.isEmpty {
            return keyValue.removingPercentEncoding ?? keyValue
        }

        // 🔧 (2026-08-07) Shared keys are wrapped in ⟦…⟧ so iOS data detectors
        // stop at a boundary instead of appending a unit conversion to the
        // trailing base64 — see the share text in NewMessageView. Accept the
        // wrapper here, or a pasted key would arrive with the brackets still on
        // it and fail to match any contact.
        //
        // Also pull a bracketed key out of a LONGER paste: people paste the
        // whole "Add me on OSHI!" message, not just the key.
        if let open = trimmed.firstIndex(of: "⟦"),
           let close = trimmed.lastIndex(of: "⟧"),
           open < close {
            let inner = trimmed[trimmed.index(after: open)..<close]
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !inner.isEmpty { return inner }
        }

        // Trailing junk on an UNWRAPPED paste — the exact corruption reported
        // ("…muFE3A=0.0741 acre"). A key is base64 and ends at the last `=`;
        // anything a detector glued on after it is not part of it.
        if let eq = trimmed.lastIndex(of: "="), trimmed.index(after: eq) < trimmed.endIndex {
            let head = String(trimmed[..<trimmed.index(after: eq)])
            let tail = trimmed[trimmed.index(after: eq)...]
            // Only when the tail is clearly NOT key material: a real key never
            // contains a space.
            if head.count >= 20, tail.contains(" ") || tail.contains(",") {
                return head
            }
        }

        return trimmed
    }

    /// Shared logic: find conversation by public key and open it (with retries)
    private func navigateToConversation(byPublicKey publicKey: String, attempt: Int = 1) {
        // __KEY_FOLD_2026_08_13__ see handleStartChatNotification — an exact match
        // here meant a notification deep-link could retry 5× and give up on a
        // conversation that was sitting in the list under another encoding.
        let wanted = MessageManager.normalizeAliasKey(publicKey)
        if let existing = messageManager.conversations.first(where: {
            MessageManager.normalizeAliasKey($0.participantPublicKey) == wanted
        }) {
            OshiLog.messaging.info("✅ Found existing conversation for key: \(publicKey.prefix(12))...")
            // First-time open: just set the state synchronously and push.
            // The previous code did `navigateToNotificationChat = false` →
            // 150 ms wait → `= true` to "handle repeated taps", but on the
            // first open that meant the flag toggled false→true after a
            // delay, producing a 150 ms visible flash of the Messages list
            // before the chat pushed. Now we only reset the flag when a
            // chat is ALREADY active for a DIFFERENT peer (true repeated-tap
            // case). Otherwise we set both fields atomically and push
            // immediately.
            threadSelection.open(existing)
            pendingConversationId = nil
        } else if attempt < 5 {
            let delay = Double(attempt) * 0.3
            OshiLog.messaging.info("⏳ Conversation not found yet for \(publicKey.prefix(12))..., retry in \(delay)s")
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                navigateToConversation(byPublicKey: publicKey, attempt: attempt + 1)
            }
        } else {
            OshiLog.messaging.info("⚠️ No conversation found after 5 attempts for key: \(publicKey.prefix(12))...")
            pendingConversationId = nil
        }
    }

    // MARK: - Export Backup

    /// __ENCRYPTED_MESSAGE_EXPORT_2026_09_22__ Même export chiffré que les
    /// Réglages. Avant: un .txt lisible de TOUTES les conversations, avec les
    /// alias des contacts, remis à la feuille de partage.
    private func exportChatBackup() {
        isExporting = true
        // __EXPORT_V2_INTEROP_2026_09_22__ 1:1 + groupes + alias, charge v2 neutre.
        let snapshot = MessageExportV2.capture(messageManager, identity: identityManager)
        Task.detached(priority: .userInitiated) {
            do {
                let url = try EncryptedMessageExport.write(snapshot, identity: identityManager)
                await MainActor.run {
                    exportURL = url
                    showExportSheet = true
                    isExporting = false
                }
            } catch {
                OshiLog.messaging.info("❌ Export failed: \(error)")
                await MainActor.run { isExporting = false }
            }
        }
    }
    
    // MARK: - Search Results Section
    
    @ViewBuilder
    private var searchResultsSection: some View {
        if searchResults.contains(where: { $0.matchType == .messageContent }) {
            VStack(alignment: .leading, spacing: 0) {
                // Header
                HStack {
                    Image(systemName: "text.magnifyingglass")
                        .foregroundColor(oshiBrandColor)
                        .font(.caption)
                    
                    Text("search.in_messages".localized)
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundColor(.secondary)
                    
                    Spacer()
                    
                    if isSearchingContent {
                        ProgressView()
                            .scaleEffect(0.7)
                    }
                    
                    Text("\(searchResults.filter { $0.matchType == .messageContent }.count)")
                        .font(.caption)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(oshiBrandColor)
                        .cornerRadius(10)
                }
                .padding(.horizontal)
                .padding(.vertical, 10)
                .background(Color(.systemGray6))
                
                // Message results
                ForEach(searchResults.filter { $0.matchType == .messageContent }.prefix(5)) { result in
                    NavigationLink(value: ThreadRoute.conversation(for: result.conversation)) {
                        MessageSearchResultRow(
                            result: result,
                            alias: displayName(for: result.conversation.participantPublicKey),
                            searchText: searchText
                        )
                    }
                    .buttonStyle(PlainButtonStyle())
                    
                    Divider()
                        .padding(.leading, 70)
                }
                
                if searchResults.filter({ $0.matchType == .messageContent }).count > 5 {
                    HStack {
                        Spacer()
                        Text(String(format: "search.more_results".localized, searchResults.filter({ $0.matchType == .messageContent }).count - 5))
                            .font(.caption)
                            .foregroundColor(oshiBrandColor)
                        Spacer()
                    }
                    .padding(.vertical, 8)
                    .background(Color(.systemGray6).opacity(0.5))
                }
            }
        }
    }
    
    // MARK: - Compose menu & row menu (Anatole 13/11)

    /// __ANATOLE_1311_IOS__ (N4) "Nouveau" as a menu that opens from the
    /// button: a new contact, or a voice / video call to someone already in
    /// the list. The New TAB stays (it is the QR / key screen); this is the
    /// compose button iOS users look for in the top bar.
    private var composeMenu: some View {
        Menu {
            Button {
                NotificationCenter.default.post(name: Self.selectNewTabNotification, object: nil)
            } label: {
                Label("new.menu.contact".localized, systemImage: "person.badge.plus")
            }
            Button {
                callPicker = .voice
            } label: {
                Label("chat.voice_call".localized, systemImage: "phone.fill")
            }
            Button {
                callPicker = .video
            } label: {
                Label("chat.video_call".localized, systemImage: "video.fill")
            }
        } label: {
            Image(systemName: "square.and.pencil")
                .foregroundColor(.primary)
        }
        .accessibilityLabel("tab_new".localized)
        .accessibilityIdentifier("compose_menu")
    }

    // MARK: - Merged list (__MERGED_CHATS_2026_11_18__)

    /// The compose button of the merged list: one sheet with every way to
    /// start something (chat, group, public channel, invite QR, call).
    private var mergedNewButton: some View {
        Button {
            showNewSheet = true
        } label: {
            Image(systemName: "square.and.pencil")
                .foregroundColor(.primary)
        }
        .accessibilityLabel("chats.merged.new_title".localized)
        .accessibilityIdentifier("compose_new")
    }

    private var mergedContent: some View {
        MergedChatsList(
            searchText: searchText,
            searchResults: searchResults.isEmpty ? nil : AnyView(searchResultsSection),
            pendingGroupId: pendingGroupId,
            displayName: { displayName(for: $0) },
            onShowMyCode: { showMyQRCode = true },
            onScanCode: { showQRScanner = true },
            onInviteByLink: { showInviteShare = true },
            onDiscover: { showDiscoverGroups = true },
            onCreateGroup: { showCreateGroup = true },
            onShowAddress: { conversation in
                selectedConversation = conversation
                showAddressSheet = true
            },
            onOpenContact: { profileConversation = $0 },
            onDeleteConversation: { deleteConversation($0) }
        )
        // ⌘N new chat, ⇧⌘N new group — hardware keyboards on iPad and the Mac.
        .background {
            VStack {
                Button("chats.merged.new_chat".localized) { perform(.chat) }
                    .keyboardShortcut("n", modifiers: .command)
                Button("chats.merged.new_group".localized) { perform(.group) }
                    .keyboardShortcut("n", modifiers: [.command, .shift])
            }
            .opacity(0)
            .frame(width: 0, height: 0)
            .accessibilityHidden(true)
        }
    }

    private func performPendingNewAction() {
        guard let action = pendingNewAction else { return }
        pendingNewAction = nil
        perform(action)
    }

    private func perform(_ action: MergedNewAction) {
        switch action {
        case .chat:
            // The compose menu's "New contact": MainTabView selects the New tab
            // (the QR / key screen).
            NotificationCenter.default.post(name: Self.selectNewTabNotification, object: nil)
        case .group: showCreateGroup = true
        case .discover: showDiscoverGroups = true
        case .scanInvite: showQRScanner = true
        case .voiceCall: callPicker = .voice
        case .videoCall: callPicker = .video
        }
    }

    /// __LAUNCH_LAG_2026_11_22__ The row menu's labels, looked up once per list
    /// pass instead of six `.localized` bundle lookups per ROW per pass (the
    /// menu content is built with the row, not when it opens).
    struct RowMenuLabels {
        let pin = "message.pin".localized
        let unpin = "message.unpin".localized
        let mute = "chat.mute".localized
        let unmute = "chat.unmute".localized
        let contact = "conversation.open_contact".localized
        let qr = "a11y.contact_qr".localized
    }

    @ViewBuilder
    private func conversationContextMenu(for conversation: Conversation, labels: RowMenuLabels) -> some View {
        let key = conversation.participantPublicKey
        let pinned = conversationPrefs.isPinned(key)
        let muted = conversationPrefs.isMuted(key)
        Button {
            conversationPrefs.togglePinned(key)
        } label: {
            Label(pinned ? labels.unpin : labels.pin,
                  systemImage: pinned ? "pin.slash" : "pin")
        }
        Button {
            conversationPrefs.toggleMuted(key)
        } label: {
            Label(muted ? labels.unmute : labels.mute,
                  systemImage: muted ? "bell" : "bell.slash")
        }
        if !LoRaChannelThread.isChannelKey(key) && !MeshCoreInterop.isContactKey(key) {
            Button {
                profileConversation = conversation
            } label: {
                Label(labels.contact, systemImage: "person.crop.circle")
            }
        }
        Button {
            selectedConversation = conversation
            showAddressSheet = true
        } label: {
            Label(labels.qr, systemImage: "qrcode")
        }
    }

    /// A person on the other end: not a LoRa channel, not the legacy welcome id.
    static func isCallable(_ key: String) -> Bool {
        !LoRaChannelThread.isChannelKey(key) && !MeshCoreInterop.isContactKey(key)
            && key != WelcomeMessageManager.legacySystemSenderKey
    }

    /// The chat's own rule (`ChatView.startHeaderCall`): a contact we blocked,
    /// or one who told us they block us, is refused locally and nothing is sent.
    private func startCall(to key: String, video: Bool) {
        if BlockedContactsManager.shared.isBlocked(key) {
            callErrorMessage = "call.error.blocked".localized
            return
        }
        if BlockedByPeerStore.shared.isBlockedBy(key) {
            callErrorMessage = "call.error.unavailable".localized
            return
        }
        let name = displayName(for: key) ?? MessageManager.resolveContactAlias(key)
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        // The call UI is drawn by ContentView's own ZStack from the manager's
        // state, so starting it while the picker sheet is still closing is
        // fine — the same reason ContactProfileView dismisses and then calls.
        Task { try? await VoiceCallManager.shared.startCall(to: key, peerName: name, isVideoCall: video) }
    }

    // MARK: - Conversations List
    
    private var conversationsList: some View {
        let menuLabels = RowMenuLabels()
        return List {
            ForEach(Array(filteredConversations.enumerated()), id: \.element.id) { index, conversation in
                SwiftUI.Group {
                    if splitActive {
                        // __DUO_POLISH_2026_11_18__ Wide, a row SELECTS into the
                        // detail pane (sidebar style): no push, so no disclosure
                        // chevron promising one. Same effect as the link below,
                        // whose path setter opens the route.
                        Button { threadSelection.open(conversation) } label: {
                            ConversationRow(
                                conversation: conversation,
                                identityManager: identityManager,
                                alias: displayName(for: conversation.participantPublicKey)
                            )
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    } else {
                        NavigationLink(value: ThreadRoute.conversation(for: conversation)) {
                            ConversationRow(
                                conversation: conversation,
                                identityManager: identityManager,
                                alias: displayName(for: conversation.participantPublicKey)
                            )
                        }
                    }
                }
                // Stable identifier for deterministic screenshot automation.
                .accessibilityIdentifier(index == 0 ? "conversation_row_first" : "conversation_row_\(index)")
                // Wide layout: mark the row whose thread is in the detail pane.
                // nil keeps the glass/system background, so the phone list is unchanged.
                .glassClearListRow(selectedTint: splitActive && threadSelection.isOpen(conversation)
                                   ? Theme.brand.opacity(0.12) : nil)  // __GLASS_ALL_THEMES_2026_11_21__
                .accessibilityAddTraits(splitActive && threadSelection.isOpen(conversation) ? .isSelected : [])
                // __ROW_PITCH_2026_08_22__ THE HEIGHT WAS NEVER IN THE ROW.
                //
                // The compact pass earlier today shrank the avatar, the fonts and
                // the row's own padding, and the measured pitch did not move: 92.6
                // pt before, 92.6 pt after. All of that work was inside a row whose
                // height a plain `List` was already padding by ~11 pt top AND
                // bottom of its own accord — ~22 pt per row that no amount of
                // editing the row's contents can reach.
                //
                // Measured with XCUIElement.frame on a 16 Pro (see
                // RowDensityUITests), against the Telegram list Hugo sent as the
                // reference: ~52 pt of pitch there for the same three lines.
                // __ROW_PITCH_TELEGRAM_2026_08_22__ Deuxième passe, sur mesure.
                // `RowDensityUITests` donnait 61,8 pt de pas contre ~52 chez
                // Telegram. Le reste de l'écart n'est PAS dans les polices: une
                // rangée OSHI à UNE ligne d'aperçu fait déjà 56 pt, la hauteur
                // exacte d'une rangée Telegram — sauf que Telegram y loge DEUX
                // lignes. Tout ce qui manquait était de l'interligne et de la
                // marge, pas du corps de texte. Le nom reste à 15 et l'aperçu à
                // 13: rétrécir la police aurait payé la densité en lisibilité,
                // ce qui n'est pas ce qui était demandé.
                .listRowInsets(EdgeInsets(top: 1, leading: 16, bottom: 1, trailing: 12))
                // SwiftUI derives the separator's leading edge from whatever it
                // finds first in the row, so it landed under the avatar on some
                // rows and under the text on others — visible as a ragged left
                // edge down the list. Pin it past the avatar (44 + the HStack's
                // 12), which is where the groups list and Telegram both put it.
                .alignmentGuide(.listRowSeparatorLeading) { _ in 56 }
                // __COMPACT_ROWS_2026_08_22__ Le QR/adresse était un bouton de
                // 44×44 DANS chaque rangée — la seule chose qui en fixait la
                // hauteur. Il devient un balayage vers la droite, et un appui
                // long pour qui ne balaye pas. Le balayage est à GAUCHE parce que
                // celui de droite appartient déjà à `.onDelete`.
                .swipeActions(edge: .leading, allowsFullSwipe: false) {
                    Button {
                        selectedConversation = conversation
                        showAddressSheet = true
                    } label: {
                        Label(menuLabels.qr, systemImage: "qrcode")
                    }
                    .tint(oshiBrandColor)
                }
                .contextMenu {
                    // __ANATOLE_1311_IOS__ (N8) Pin, mute and the contact card
                    // from a long press on the row — the native iOS form of
                    // the bottom sheet Android opens for the same gesture.
                    conversationContextMenu(for: conversation, labels: menuLabels)
                }
            }
            .onDelete(perform: deleteConversations)

            // 🔴 __CLEAR_THE_FLOATING_TABBAR_2026_08_19__ App Review, guideline 4:
            // "the screen was cropped", iPhone 17 Pro Max / iOS 26.6.
            //
            // iOS 26's tab bar FLOATS over the content instead of sitting under it.
            // A `TabView` normally hands its scrolling children a bottom safe-area
            // inset for that, but it does not reach this List — verified on a
            // 17 Pro Max simulator, where the last conversation sat permanently
            // half-hidden behind the bar and could never be scrolled clear of it.
            // The reviewer saw exactly that.
            //
            // An explicit terminal row is used rather than `.safeAreaInset`,
            // because the inset would apply to the SEARCH results list too, where
            // it is not wanted, and because a row is visible in a screenshot — the
            // fix can be checked rather than believed.
            Color.clear
                .frame(height: 96)
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
                .accessibilityHidden(true)
        }
        .listStyle(.plain)
        // __GLASS_ALL_THEMES_2026_11_21__ With glass on (Light / Dark), the
        // list is see-through: ChatsTabView paints the OSHI ground (the chat's
        // own backdrop) under the segment strip AND the rows, so the floating
        // search field, tab bar and header buttons are glass OVER colour.
        .glassSeeThroughList()
    }
    
    // MARK: - Search Logic
    
    private func performSearch(query: String) {
        guard query.count >= 2 else {
            searchResults = []
            return
        }
        
        isSearchingContent = true
        
        // Search in background
        Task {
            var results: [SearchResult] = []
            
            // Search through all conversations' messages
            for conversation in messageManager.conversations {
                // Get messages for this conversation
                let messages = messageManager.getMessages(for: conversation.participantPublicKey)
                
                for message in messages {
                    // Check plaintext content
                    if let plaintext = message.plaintextContent,
                       plaintext.localizedCaseInsensitiveContains(query) {
                        // Skip internal markers.
                        //
                        // 🔧 RAW-JSON FIX: this knew two of twenty-four, so a
                        // search for a common word ("call", "type", "content")
                        // matched INSIDE a control payload's JSON and printed
                        // the payload as the search result. Silent payloads are
                        // dropped outright; the rest are shown as their summary.
                        if OSHIControlPayload.isSilent(plaintext) { continue }
                        // Media matches were skipped before this change and stay
                        // skipped — the match is against a base64 blob, not text.
                        if plaintext.hasPrefix(OSHIControlPayload.mediaPrefix) { continue }
                        let readable = OSHIControlPayload.previewText(for: plaintext)
                        if readable.isEmpty { continue }

                        results.append(SearchResult(
                            conversation: conversation,
                            matchType: .messageContent,
                            matchedText: readable,
                            messageTimestamp: message.timestamp
                        ))
                    }
                }
            }
            
            // Sort by timestamp (most recent first)
            results.sort { ($0.messageTimestamp ?? .distantPast) > ($1.messageTimestamp ?? .distantPast) }
            
            await MainActor.run {
                searchResults = results
                isSearchingContent = false
            }
        }
    }
    
    private func deleteConversations(at offsets: IndexSet) {
        for index in offsets {
            deleteConversation(filteredConversations[index])
        }
    }

    private func deleteConversation(_ conversation: Conversation) {
        let publicKey = conversation.participantPublicKey
        messageManager.deleteConversation(with: publicKey)

        // 🔧 FIX R20: Clean up orphaned contact alias on conversation deletion
        var aliases = contactAliases
        if aliases.removeValue(forKey: publicKey) != nil {
            if let data = try? JSONEncoder().encode(aliases) {
                contactAliasesData = data
                OshiLog.messaging.info("🧹 Cleaned up alias for deleted conversation: \(publicKey.prefix(12))...")
            }
        }
    }
}

// MARK: - Message Search Result Row

struct MessageSearchResultRow: View {
    let result: MessagesListView.SearchResult
    let alias: String?
    let searchText: String
    
    private var displayName: String {
        alias ?? MeshCoreInterop.threadTitle(forKey: result.conversation.participantPublicKey) ?? formatAddress(result.conversation.participantAddress)
    }
    
    var body: some View {
        HStack(spacing: 12) {
            // Avatar with search icon overlay
            ZStack(alignment: .bottomTrailing) {
                // __ONE_AVATAR_NOT_THREE_2026_11_07__ a search HIT drew a flat
                // brand circle while the very same conversation one list up
                // drew its identity colour, so searching made everyone look
                // alike at exactly the moment you are scanning for one person.
                DefaultAvatar(key: result.conversation.participantPublicKey,
                              name: displayName, size: 46)
                
                // Search indicator
                Circle()
                    .fill(oshiBrandColor)
                    .frame(width: 18, height: 18)
                    .overlay(
                        Image(systemName: "text.magnifyingglass")
                            .font(.scaled(9, weight: .bold))
                            .foregroundColor(.white)
                    )
                    .offset(x: 2, y: 2)
            }
            
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(displayName)
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundColor(.primary)
                    
                    Spacer()
                    
                    if let timestamp = result.messageTimestamp {
                        Text(formatTimestamp(timestamp))
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }
                
                // Highlighted matched text
                if let matchedText = result.matchedText {
                    highlightedText(matchedText, highlight: searchText)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                }
            }
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 12)
        .background(oshiBrandColor.opacity(0.05))
        .cornerRadius(8)
        .padding(.horizontal, 4)
        .padding(.vertical, 2)
    }
    
    @ViewBuilder
    private func highlightedText(_ text: String, highlight: String) -> some View {
        let lowercasedText = text.lowercased()
        let lowercasedHighlight = highlight.lowercased()
        
        if let range = lowercasedText.range(of: lowercasedHighlight) {
            let startIndex = text.distance(from: text.startIndex, to: range.lowerBound)
            let endIndex = text.distance(from: text.startIndex, to: range.upperBound)
            
            let prefix = String(text.prefix(startIndex))
            let highlighted = String(text[text.index(text.startIndex, offsetBy: startIndex)..<text.index(text.startIndex, offsetBy: endIndex)])
            let suffix = String(text.suffix(text.count - endIndex))
            
            // Truncate if needed
            let displayPrefix = prefix.count > 20 ? "..." + String(prefix.suffix(20)) : prefix
            let displaySuffix = suffix.count > 30 ? String(suffix.prefix(30)) + "..." : suffix
            
            HStack(spacing: 0) {
                Text(displayPrefix)
                Text(highlighted)
                    .fontWeight(.bold)
                    .foregroundColor(oshiBrandColor)
                    .background(oshiBrandColor.opacity(0.2))
                Text(displaySuffix)
            }
        } else {
            Text(text.prefix(60) + (text.count > 60 ? "..." : ""))
        }
    }
    
    private func formatAddress(_ address: String) -> String {
        guard address.count > 10 else { return address }
        let start = address.prefix(6)
        let end = address.suffix(4)
        return "\(start)...\(end)"
    }
    
    /// 🔧 PERF: same fix as `ConversationRow.formatTimestamp` — this one draws
    /// the timestamp on each search-result row. See `OshiRowFormatters`.
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

// MARK: - Conversation Row

struct ConversationRow: View {
    let conversation: Conversation
    let identityManager: IdentityManager
    let alias: String?

    /// 💓 __ALIVE_CHECK_IN_2026_11_04__ Observed, deliberately, and worth
    /// weighing against the `@EnvironmentObject` warning below.
    ///
    /// That warning is about `MessageManager`, which publishes on every arriving
    /// message, every receipt and every IPFS poll — so subscribing invalidated
    /// every visible row constantly. This store publishes when someone presses
    /// "I'm alive" or a peer's event arrives: a handful of times a day, for the
    /// few conversations that use the feature at all. The alternative is a row
    /// whose dot and countdown only refresh when something else happens to
    /// redraw it, which for a safety indicator is the wrong trade.
    @ObservedObject private var aliveStore = AliveCheckInStore.shared
    /// __ANATOLE_1311_IOS__ (N8) pin / mute markers. Publishes only when the
    /// user pins or mutes something, so it does not churn the list.
    @ObservedObject private var conversationPrefs = ConversationPrefsStore.shared
    // __COMPACT_ROWS_2026_08_22__ `onAddressTap` a disparu avec le bouton QR
    // qu'il servait. Le garder aurait laissé un rappel qu'on peut passer et que
    // plus rien n'appelle. L'accès au code du contact est un balayage et un
    // appui long, câblés dans `conversationsList`.
    
    // 🔴 PERF: this was `@EnvironmentObject var messageManager: MessageManager`.
    // `@EnvironmentObject` is a SUBSCRIPTION, not a lookup: it wires this row's
    // body to `MessageManager.objectWillChange`, which fires on every publish of
    // `messages`, `conversations`, `uploadProgress`, `uploadStatus` and
    // `pinnedMessages` — i.e. on every arriving message, every delivery and read
    // receipt, and every IPFS poll that touches the array. So one incoming
    // message invalidated the body of EVERY visible conversation row, and each
    // of those bodies then rebuilt a DateFormatter (see formatTimestamp) and a
    // six-entry gradient table.
    //
    // 🔑 The row never read the manager from `body` — the single use was inside
    // the async `decryptPreview()` below, off the main actor. Nothing displayed
    // here comes from the manager: `conversation` (incl. `unreadCount`) is a
    // plain value pushed down by the parent list, which DOES observe the
    // manager, so unread badges and previews still update. The subscription was
    // pure invalidation cost.
    private var messageManager: MessageManager? { MessageManager.shared }
    @State private var previewText: String = ""
    @State private var isDecrypting = false
    /// The message `previewText` was decoded from — see __ROW_JITTER_2026_09_22__.
    @State private var previewMessageId: String?
    @State private var contactPhoto: UIImage? = nil
    // __COMPACT_ROWS_2026_08_22__ `@Environment(\.colorScheme)` est parti avec le
    // bouton QR, seul endroit de cette rangée qui distinguait clair et sombre.

    private var displayName: String {
        alias ?? MeshCoreInterop.threadTitle(forKey: conversation.participantPublicKey) ?? formatAddress(conversation.participantAddress)
    }

    // Modern gradient colors based on public key hash - includes brand color.
    //
    // 🔧 PERF: the palette used to be a local `let` built INSIDE this computed
    // property, so every row re-render allocated a 6-element array of 2-element
    private var avatarGradient: [Color] {
        AvatarIdentity.gradient(for: conversation.participantPublicKey)
    }

    // __COMPACT_ROWS_2026_08_22__ Demande de Hugo: « plus compact et plus petit,
    // comme Telegram », et « deux lignes d'aperçu au maximum ».
    //
    // CE QUI PRENAIT LA PLACE
    // La hauteur de la ligne n'était pas fixée par l'avatar (54 pt) mais par la
    // COLONNE DE DROITE: une pastille de 22 pt, 8 pt d'espace, et un bouton QR
    // encadré à 44×44 — soit 74 pt, plus 16 pt de marges verticales. Chaque
    // conversation occupait donc ~90 pt pour afficher un nom et une ligne
    // d'aperçu tronquée à un seul rang.
    //
    // CE QUI CHANGE
    // L'aperçu peut aller à DEUX lignes, et la ligne est malgré tout plus courte:
    //  • la pastille de non-lus passe en bout de la ligne d'aperçu, là où
    //    Telegram la met — elle ne fabrique plus de hauteur à elle seule;
    //  • le bouton QR quitte la ligne. Il reste atteignable par un balayage vers
    //    la droite ET par un appui long (voir `conversationsList`) — deux gestes
    //    système, plutôt qu'une cible de 44 pt dans chaque rangée;
    //  • avatar 54 → 48, corps du nom et de l'aperçu d'un cran plus petits,
    //    marge verticale 8 → 5.
    //
    var body: some View {
        // __LIST_ROWS_NAME_FIRST_2026_11_17__ Owner, on build 204: "the name of
        // the conversation should be on top of the preview, and in bold." The
        // 2026-11-09 redesign had moved the name UNDER the avatar as a 10 pt
        // caption, which left the first text line holding only the time. The
        // brand ring stays; the name goes back where every messenger puts it:
        //   [ring avatar]  NAME (bold, 1 line) ······ mute pin  time
        //                  preview (secondary, ≤ 2 lines) ·· ⏱  (unread)
        // VoiceOver reads ONE element, name first (the avatar's initials are
        // hidden from it), then the time, the preview and the unread count.
        HStack(alignment: .center, spacing: 12) {
            ZStack(alignment: .bottomTrailing) {
                ZStack {
                    Circle()
                        .stroke(oshiBrandColor, lineWidth: 2)
                        .frame(width: 50, height: 50)

                    if let photo = contactPhoto {
                        Image(uiImage: photo)
                            .resizable()
                            .scaledToFill()
                            .frame(width: 44, height: 44)
                            .clipShape(Circle())
                            .shadow(color: oshiBrandColor.opacity(0.3), radius: 3, x: 0, y: 1)
                    } else {
                        // __AVATAR_PALETTE_V2_2026_11_17__ gradient + light blob.
                        AvatarFill(key: conversation.participantPublicKey, size: 44)
                            .shadow(color: avatarGradient[1].opacity(0.3), radius: 3, x: 0, y: 1)

                        // 🔴 __MONOGRAM_CENTRED_2026_08_19__ App Review, guideline 4.
                        //
                        // The enclosing ZStack is `alignment: .bottomTrailing` — set so
                        // the presence dot sits on the corner. The initials had no
                        // frame, so they inherited that alignment too: they were pinned
                        // to the bottom-right of the circle and CLIPPED by its edge, on
                        // every avatar without a photo. Giving the text the circle's own
                        // frame centres it regardless of what the stack aligns for.
                        Text((alias ?? conversation.participantAddress).prefix(2).uppercased())
                            .font(.scaled(16, weight: .semibold, design: .rounded))
                            .foregroundColor(AvatarIdentity.ink(for: conversation.participantPublicKey))
                            .frame(width: 44, height: 44)
                    }
                }
                .frame(width: 50, height: 50)

                // Online/Offline Status Badge — now rides the ring's outer
                // edge instead of the bare photo's, so it stays clear of
                // the brand stroke rather than overlapping it.
                let badge = ContactPresenceManager.shared.getStatusBadge(publicKey: conversation.participantPublicKey)
                if badge != .offline {
                    Circle()
                        .fill(badge.color)
                        .frame(width: 13, height: 13)
                        .overlay(
                            Circle()
                                .stroke(Color(.systemBackground), lineWidth: 2)
                        )
                        .offset(x: 1, y: 1)
                }
            }
            .frame(width: 50, height: 50)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(displayName)
                        .font(.scaled(16, weight: .bold))
                        .foregroundColor(.primary)
                        .lineLimit(1)
                        .truncationMode(.tail)

                    // 💓 __ALIVE_CHECK_IN_2026_11_04__ Draws nothing at all when no
                    // check-in is configured; fixed size, so a long name yields.
                    AliveRowMarker(entry: aliveStore.entry(for: conversation.participantPublicKey))
                        .fixedSize()

                    if conversationPrefs.isMuted(conversation.participantPublicKey) {
                        Image(systemName: "bell.slash.fill")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                            .accessibilityLabel("chat.mute".localized)
                    }
                    if conversationPrefs.isPinned(conversation.participantPublicKey) {
                        Image(systemName: "pin.fill")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                            .accessibilityLabel("message.pin".localized)
                    }

                    Spacer(minLength: 6)

                    // __LIST_TIME_TICKS_2026_11_09__ self-refreshing label;
                    // see `ListTimestampText`.
                    ListTimestampText(date: conversation.lastMessage.timestamp, includeWeekday: true)
                        .font(.caption)
                        .foregroundColor(conversation.unreadCount > 0 ? oshiBrandColor : .secondary)
                        .lineLimit(1)
                        .fixedSize()
                }

                // Message preview — two lines at most, under the name.
                HStack(alignment: .top, spacing: 6) {
                    if isDecrypting && previewText.isEmpty {
                        HStack(spacing: 4) {
                            ProgressView()
                                .scaleEffect(0.55)
                            Text("messages_decrypting".localized)
                                .font(.subheadline)
                        }
                        .foregroundColor(.secondary)
                    } else if previewText.isEmpty {
                        HStack(spacing: 4) {
                            Image(systemName: "lock.fill")
                                .font(.caption2)
                            Text("messages_encrypted".localized)
                                .font(.subheadline)
                        }
                        .foregroundColor(.secondary)
                    } else {
                        Text(previewText)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .lineLimit(2)
                            .truncationMode(.tail)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Spacer(minLength: 4)

                    // 💓 __ALIVE_CHECK_IN_2026_11_04__ The countdown, after the
                    // preview and before the unread pill. That order is the
                    // ranking: an unread message is what the row is for, and a
                    // check-in state must not outrank it.
                    AliveRowMarker(
                        entry: aliveStore.entry(for: conversation.participantPublicKey),
                        showCountdown: true)

                    // Unread pill, at the end of the preview line.
                    if conversation.unreadCount > 0 {
                        Text("\(conversation.unreadCount)")
                            .font(.caption)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .frame(minWidth: 20)
                            .background(
                                Capsule()
                                    .fill(LinearGradient(
                                        colors: [oshiBrandColor, oshiBrandColor.opacity(0.8)],
                                        startPoint: .top,
                                        endPoint: .bottom
                                    ))
                            )
                            .fixedSize()
                    }
                }
            }
        }
        .accessibilityElement(children: .combine)
        .padding(.vertical, 4)
        .task(id: conversation.lastMessage.id) {
            // 🔧 FIX: Use task(id:) so preview re-decrypts when lastMessage changes
            //
            // __ROW_JITTER_2026_09_22__ « la barre de défilement bouge toute seule ».
            // Le texte n'est PLUS vidé ici. Il l'était à chaque nouveau message:
            // la rangée tombait sur « Déchiffrement… » (UNE ligne) puis remontait
            // à l'aperçu (DEUX lignes) — la hauteur du contenu changeait sous le
            // doigt, et l'indicateur de défilement sautait sans que personne ne
            // touche l'écran. L'ancien aperçu reste affiché jusqu'à ce que le
            // nouveau soit prêt, puis il est remplacé d'un coup.
            isDecrypting = false
            await decryptPreview()
        }
        .onAppear {
            loadContactPhoto()
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("ContactPhotoUpdated"))) { _ in
            loadContactPhoto()
        }
        .onReceive(NotificationCenter.default.publisher(for: .contactPresenceUpdated)) { notification in
            // 📸 Reload photo when contact's profile is updated from network
            if let publicKey = notification.object as? String,
               publicKey == conversation.participantPublicKey {
                loadContactPhoto()
            }
        }
    }
    
    private func loadContactPhoto() {
        // 🔧 FIX: Contact's actual profile picture (that THEY set) takes priority
        // This is WhatsApp behavior - you see the profile picture your contact chose
        // NOT a photo you manually set for them

        // First: Check received profile pictures from contacts (their actual profile)
        if let photo = ContactPresenceManager.shared.getProfileImage(publicKey: conversation.participantPublicKey) {
            contactPhoto = photo
            return
        }

        // Fallback: Check locally saved photos (only if no profile received)
        // This is just a fallback for contacts who haven't shared their profile yet
        if let photo = ContactPhotoManager.shared.getPhoto(for: conversation.participantPublicKey) {
            contactPhoto = photo
        }
    }
    
    private func decryptPreview() async {
        let message = conversation.lastMessage
        guard !isDecrypting && previewMessageId != message.id else { return }

        await MainActor.run { isDecrypting = true }

        let preview: String
        
        if message.encryptedContent.ciphertext.isEmpty && message.plaintextContent == nil {
            // __ANATOLE_1311_IOS__ (N18) a saved contact nobody has written to
            // yet: say so, instead of "encrypted message" for a thread that
            // holds nothing at all.
            preview = "conversation.saved_contact_preview".localized
        } else if message.senderPublicKey == message.recipientPublicKey {
            preview = message.plaintextContent ?? "messages_encrypted_short".localized
        } else if let plaintext = message.plaintextContent {
            preview = plaintext
        } else {
            // `messageManager` is now the shared singleton rather than an
            // @EnvironmentObject (see the property's comment). It is optional
            // only because `MessageManager.shared` is set during app start-up;
            // by the time a conversation row exists it is always populated. Fall
            // back to the same "encrypted" placeholder the decrypt failure path
            // uses rather than force-unwrapping.
            guard let manager = messageManager else {
                await MainActor.run {
                    previewText = "messages_encrypted_short".localized
                    isDecrypting = false
                }
                return
            }
            let wallet = identityManager
            // Several rows can request a preview together when this tab becomes
            // visible. Do not let background preview decryption compete with the
            // tab transition or a user's first tap/scroll.
            preview = await Task.detached(priority: .utility) {
                manager.decryptMessage(message, using: wallet) ?? "messages_encrypted_short".localized
            }.value
        }
        
        await MainActor.run {
            if message.isVideoNote {
                previewText = "🎥 " + "videonote.title".localized  // __VIDEO_NOTE_2026_09_24__
            } else if preview.hasPrefix("MEDIA|||") {
                let components = preview.components(separatedBy: "|||")
                if components.count >= 2 {
                    let mediaType = components[1].lowercased()
                    previewText = mediaType == "image" ? "📷 " + "media_image".localized : "🎥 " + "media_video".localized
                } else {
                    previewText = "📎 " + "media_file".localized
                }
            } else if preview.hasPrefix("📱CALL_SUMMARY📱") {
                let rawContent = preview.replacingOccurrences(of: "📱CALL_SUMMARY📱", with: "")
                previewText = localizeCallContent(rawContent)
            } else if preview.hasPrefix("📱MISSED_CALL📱") {
                let rawContent = preview.replacingOccurrences(of: "📱MISSED_CALL📱", with: "")
                previewText = localizeCallContent(rawContent)
            } else if preview.hasPrefix("📞CALL_SIGNAL📞") {
                previewText = "📞 " + "call.state.connecting".localized
            } else if preview.hasPrefix("📅SCHEDULED_CALL📅") {
                previewText = "📅 " + "event.call.scheduled".localized
            } else if preview.hasPrefix("📍LOCATION📍") {
                // Parse location message for nice preview
                if let locationMsg = LocationMessage.fromMessageString(preview) {
                    if locationMsg.isLive && !locationMsg.isExpired {
                        // Live location - show with indicator
                        if let remaining = locationMsg.remainingTime {
                            let minutes = Int(remaining) / 60
                            previewText = "📍🟢 " + "location.sharing_live".localized + " (\(minutes)m)"
                        } else {
                            previewText = "📍🟢 " + "location.sharing_live".localized
                        }
                    } else if locationMsg.isLive && locationMsg.isExpired {
                        previewText = "📍 " + "location.expired".localized
                    } else if let address = locationMsg.address, !address.isEmpty {
                        // One-time location with address
                        let shortAddress = address.components(separatedBy: ",").first ?? address
                        previewText = "📍 " + String(shortAddress.prefix(30))
                    } else {
                        previewText = "📍 " + "location.shared_location".localized
                    }
                } else {
                    previewText = "📍 " + "location.shared_location".localized
                }
            } else if preview.hasPrefix("📸PROFILE_UPDATE📸") {
                // Profile update - show friendly text
                let isSentByMe = conversation.lastMessage.senderPublicKey == identityManager.publicKey
                previewText = isSentByMe
                    ? "📸 " + "system.profile_updated".localized
                    : "📸 " + "system.contact_profile_updated".localized.replacingOccurrences(of: "%@", with: "")
            } else if preview.hasPrefix("🎨WALLPAPER_UPDATE🎨") {
                // Wallpaper update - show friendly text
                previewText = "🎨 " + "system.wallpaper_changed".localized
            } else if preview.hasPrefix("🛡️CHECK_IN🛡️") {
                // Check-In (Accompagnement) - show friendly text based on type
                if let checkIn = CheckInMessage.fromMessageString(preview) {
                    previewText = "🛡️ " + checkIn.displayTitle
                } else {
                    previewText = "🛡️ " + "checkin.title".localized
                }
            } else if preview.hasPrefix("📸PROFILE_REQUEST📸") {
                // Profile request - hide raw protocol text
                previewText = "📸 " + "system.profile_requested".localized
            } else if preview.hasPrefix("🔗LINKS_VISIBILITY🔗") {
                // Links visibility - show friendly text
                previewText = "🔗 " + "profile_links.shared".localized
            } else if preview.hasPrefix("🔑KEY_ROTATION🔑") {
                // Key rotation - show friendly text
                previewText = "🔑 " + "key_rotation_title".localized
            } else if preview.hasPrefix("🔧ACTION🔧") {
                // Action sync message (pin/unpin/edit/delete) - don't show raw JSON
                previewText = ""
            } else if preview.hasPrefix("📬DELIVERY_RECEIPT📬")
                        || preview.hasPrefix("📖READ_RECEIPT📖")
                        || preview.hasPrefix("⌨️TYPING⌨️")
                        || preview.hasPrefix("✍️TYPING✍️") {
                // Protocol sentinels — never show raw marker as preview
                previewText = ""
            } else {
                // 🔧 RAW-JSON FIX: `previewText = preview` was the catch-all,
                // and everything the chain above did not name landed in it —
                // most visibly `💬REPLY💬{"content":…,"replyTo":{…}}`, which is
                // the ENTIRE reply envelope printed under the contact's name in
                // the conversation list. Also caught here now: forwards,
                // reactions in 1:1, the no-underscore CHECKIN spelling, session
                // resets, group-update JSON, and bare media JSON.
                //
                // The named branches above are kept, not replaced: they produce
                // richer text (a live-location countdown, a check-in title) than
                // the catalog's generic summary.
                // __TEXT_FORMATTING_2026_11_24__ a row cannot style a run: no markers.
                previewText = InlineMarkup.stripped(OSHIControlPayload.previewText(for: preview))
            }
            previewMessageId = message.id
            isDecrypting = false
        }
    }
    
    private func localizeCallContent(_ content: String) -> String {
        let text = content
        
        if text.contains("|") {
            let parts = text.components(separatedBy: "|")
            if parts.count >= 2 {
                let prefix = parts[0]
                let duration = parts[1]
                
                let icons = ["📞↗️", "📞↙️", "❌"]
                var icon = ""
                var key = prefix
                
                for ic in icons {
                    if prefix.hasPrefix(ic) {
                        icon = ic
                        key = String(prefix.dropFirst(ic.count))
                        break
                    }
                }
                
                if key.contains(".") {
                    return "\(icon) \(key.localized) • \(duration)"
                }
                return "\(icon) \(key) • \(duration)"
            }
        }
        
        let icons = ["📞↗️", "📞↙️", "❌"]
        for icon in icons {
            if text.hasPrefix(icon) {
                let key = String(text.dropFirst(icon.count))
                if key.contains(".") {
                    return "\(icon) \(key.localized)"
                }
            }
        }
        
        if text.contains(".") && !text.contains(" ") {
            return text.localized
        }
        
        return text
    }
    
    private func formatAddress(_ address: String) -> String {
        guard address.count > 10 else { return address }
        let start = address.prefix(6)
        let end = address.suffix(4)
        return "\(start)...\(end)"
    }
    
    /// 🔧 PERF: called from `ConversationRow.body`, i.e. once per visible
    /// conversation on every re-render of the list — and the list re-renders on
    /// every `MessageManager` publish, not just when a conversation changes.
    /// Building a `DateFormatter` here was the single most expensive thing in
    /// the row; see `OshiRowFormatters` (ChatView.swift) for the reasoning.
    /// Same four branches, same formats, same output.
    private func formatTimestamp(_ date: Date) -> String {
        if Calendar.current.isDateInToday(date) {
            return OshiRowFormatters.string(date, format: "HH:mm")
        } else if Calendar.current.isDateInYesterday(date) {
            return "date_yesterday".localized
        } else if Calendar.current.isDate(date, equalTo: Date(), toGranularity: .weekOfYear) {
            return OshiRowFormatters.string(date, format: "EEEE")
        } else {
            return OshiRowFormatters.string(date, format: "MMM d")
        }
    }
}

// MARK: - Contact Address Sheet

struct ContactAddressSheet: View {
    let conversation: Conversation
    let alias: String?
    @Environment(\.dismiss) var dismiss
    @Environment(\.colorScheme) var colorScheme
    @State private var qrImage: UIImage?
    @State private var copied = false
    // 🪦 Stale-identity confirmation alert state — see "Mark old identity"
    // button below; the confirm step prevents accidental drops.
    @State private var showMarkStaleConfirm = false
    // Tap the avatar to view the contact's photo full-screen.
    @State private var showFullPhoto = false

    private var displayName: String {
        alias ?? MeshCoreInterop.threadTitle(forKey: conversation.participantPublicKey) ?? formatAddress(conversation.participantAddress)
    }

    // Get contact's profile photo (same as conversation view)
    private var contactProfilePhoto: UIImage? {
        // First: Check received profile pictures from contacts (their actual profile)
        if let photo = ContactPresenceManager.shared.getProfileImage(publicKey: conversation.participantPublicKey) {
            return photo
        }
        // Fallback: Check locally saved photos
        if let photo = ContactPhotoManager.shared.getPhoto(for: conversation.participantPublicKey) {
            return photo
        }
        return nil
    }

    var body: some View {
        NavigationView {
            ZStack {
                // Background
                LinearGradient(
                    colors: colorScheme == .dark
                        ? [Color(.systemGray6), Color(.systemBackground)]
                        : [Color(.systemGray6).opacity(0.5), Color(.systemBackground)],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 24) {
                        // Header with avatar (profile photo or initials)
                        VStack(spacing: 12) {
                            if let profilePhoto = contactProfilePhoto {
                                // Show contact's profile photo — tap to view full-screen.
                                Image(uiImage: profilePhoto)
                                    .resizable()
                                    .scaledToFill()
                                    .frame(width: 100, height: 100)
                                    .clipShape(Circle())
                                    .shadow(color: oshiBrandColor.opacity(0.3), radius: 8, x: 0, y: 4)
                                    .overlay(
                                        Image(systemName: "arrow.up.left.and.arrow.down.right")
                                            .font(.scaled(11, weight: .bold))
                                            .foregroundColor(.white)
                                            .padding(5)
                                            .background(Circle().fill(Color.black.opacity(0.45)))
                                            .padding(4),
                                        alignment: .bottomTrailing
                                    )
                                    .contentShape(Circle())
                                    .onTapGesture { showFullPhoto = true }
                                    .accessibilityAddTraits(.isButton)
                                    .accessibilityHint("contact.photo.tap_to_enlarge".localized)
                                    .fullScreenCover(isPresented: $showFullPhoto) {
                                        FullScreenImageViewer(image: profilePhoto)
                                    }
                            } else {
                                // __ONE_AVATAR_NOT_THREE_2026_11_07__ brand-only
                                // before, so a contact's own detail header was
                                // a different colour from their row.
                                DefaultAvatar(key: conversation.participantPublicKey,
                                              name: displayName, size: 100)
                                    .shadow(color: oshiBrandColor.opacity(0.3), radius: 8, x: 0, y: 4)
                            }

                            Text(displayName)
                                .font(.title2)
                                .fontWeight(.bold)
                        }
                        .padding(.top, 20)
                        
                        // QR Code Card
                        VStack(spacing: 16) {
                            if let qrImage = qrImage {
                                Image(uiImage: qrImage)
                                    .interpolation(.none)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(width: 200, height: 200)
                                    .padding(16)
                                    .background(Color.white)
                                    .cornerRadius(16)
                                    .shadow(color: .black.opacity(0.1), radius: 10, x: 0, y: 5)
                            } else {
                                ProgressView()
                                    .frame(width: 200, height: 200)
                            }
                            
                            Text("address.scan_to_add".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        .padding(20)
                        .background(
                            RoundedRectangle(cornerRadius: 20)
                                .fill(colorScheme == .dark ? Color(.systemGray5) : Color.white)
                        )
                        .shadow(color: .black.opacity(0.05), radius: 10, x: 0, y: 5)
                        .padding(.horizontal)
                        
                        // Address Section
                        VStack(alignment: .leading, spacing: 12) {
                            Label("address.public_key".localized, systemImage: "key.fill")
                                .font(.caption)
                                .fontWeight(.semibold)
                                .foregroundColor(.secondary)
                            
                            HStack {
                                Text(conversation.participantPublicKey)
                                    .font(.system(.caption, design: .monospaced))
                                    .foregroundColor(.primary)
                                    .lineLimit(3)
                                
                                Spacer()
                                
                                Button {
                                    UIPasteboard.general.string = conversation.participantPublicKey
                                    withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                                        copied = true
                                    }
                                    let generator = UINotificationFeedbackGenerator()
                                    generator.notificationOccurred(.success)

                                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                                        withAnimation { copied = false }
                                    }
                                } label: {
                                    Image(systemName: copied ? "checkmark.circle.fill" : "doc.on.doc")
                                        .foregroundColor(copied ? .green : oshiBrandColor)
                                        .font(.scaled(20))
                                        .frame(width: 44, height: 44)
                                        .contentShape(Rectangle())
                                }
                                .accessibilityLabel(copied ? "a11y.copied".localized : "a11y.copy_public_key".localized)
                            }
                            .padding()
                            .background(
                                RoundedRectangle(cornerRadius: 12)
                                    .fill(colorScheme == .dark ? Color(.systemGray5) : Color(.systemGray6))
                            )
                        }
                        .padding(.horizontal)
                        
                        // Wallet Address Section (if different)
                        if conversation.participantAddress != conversation.participantPublicKey {
                            VStack(alignment: .leading, spacing: 12) {
                                Label("address.wallet".localized, systemImage: "wallet.pass.fill")
                                    .font(.caption)
                                    .fontWeight(.semibold)
                                    .foregroundColor(.secondary)
                                
                                HStack {
                                    Text(conversation.participantAddress)
                                        .font(.system(.caption, design: .monospaced))
                                        .foregroundColor(.primary)
                                        .lineLimit(2)
                                    
                                    Spacer()
                                    
                                    Button {
                                        UIPasteboard.general.string = conversation.participantAddress
                                        let generator = UINotificationFeedbackGenerator()
                                        generator.notificationOccurred(.success)
                                    } label: {
                                        Image(systemName: "doc.on.doc")
                                            .foregroundColor(oshiBrandColor)
                                            .font(.scaled(20))
                                            .frame(width: 44, height: 44)
                                            .contentShape(Rectangle())
                                    }
                                    .accessibilityLabel("a11y.copy_wallet".localized)
                                }
                                .padding()
                                .background(
                                    RoundedRectangle(cornerRadius: 12)
                                        .fill(colorScheme == .dark ? Color(.systemGray5) : Color(.systemGray6))
                                )
                            }
                            .padding(.horizontal)
                        }
                        
                        // Share Button
                        Button {
                            shareContact()
                        } label: {
                            HStack(spacing: 10) {
                                Image(systemName: "square.and.arrow.up")
                                Text("address.share".localized)
                                    .fontWeight(.semibold)
                            }
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(
                                LinearGradient(
                                    colors: [oshiBrandColor, oshiBrandColor.opacity(0.8)],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                            )
                            .cornerRadius(12)
                        }
                        .padding(.horizontal)

                        // 🪦 Mark old identity — manual entry point for the
                        // stale-identity mechanism. Use when the contact has
                        // told you they regenerated their keys and you don't
                        // have a fresh QR yet, or to retroactively flag a
                        // previously-known key after rotation.
                        Button {
                            showMarkStaleConfirm = true
                        } label: {
                            HStack(spacing: 10) {
                                Image(systemName: "key.slash")
                                Text("contact.mark_old_identity".localized)
                                    .fontWeight(.semibold)
                            }
                            .foregroundColor(.orange)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.orange.opacity(0.6), lineWidth: 1)
                                    .background(
                                        RoundedRectangle(cornerRadius: 12)
                                            .fill(Color.orange.opacity(0.08))
                                    )
                            )
                        }
                        .padding(.horizontal)
                        .padding(.bottom, 30)
                        .alert("contact.mark_old_identity_title".localized, isPresented: $showMarkStaleConfirm) {
                            Button("common.cancel".localized, role: .cancel) {}
                            Button("contact.mark_old_identity_confirm".localized, role: .destructive) {
                                StaleIdentityManager.shared.markStale(
                                    publicKey: conversation.participantPublicKey,
                                    displayName: alias,
                                    reason: .manual
                                )
                                dismiss()
                            }
                        } message: {
                            Text("contact.mark_old_identity_message".localized)
                        }
                    }
                    // Cap content width so the QR/key cards stay centered and
                    // readable on iPad / landscape rather than stretching wide.
                    .frame(maxWidth: 540)
                    .frame(maxWidth: .infinity)
                }
            }
            .navigationTitle("address.contact_info".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.secondary)
                            .font(.title3)
                    }
                    .accessibilityLabel("common.close".localized)
                }
            }
            .task {
                await generateQRCode()
            }
        }
    }
    
    private func generateQRCode() async {
        await Task.detached(priority: .userInitiated) {
            let context = CIContext()
            let filter = CIFilter.qrCodeGenerator()
            
            filter.message = Data(conversation.participantPublicKey.utf8)
            filter.correctionLevel = "M"
            
            if let outputImage = filter.outputImage {
                let transform = CGAffineTransform(scaleX: 10, y: 10)
                let scaledImage = outputImage.transformed(by: transform)
                
                if let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) {
                    let image = UIImage(cgImage: cgImage)
                    
                    await MainActor.run {
                        self.qrImage = image
                    }
                }
            }
        }.value
    }
    
    private func shareContact() {
        let key = conversation.participantPublicKey
        let encodedKey = key.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? key
        let shareText = """
        \(displayName) — OSHI

        https://oshi-messenger.org/add?key=\(encodedKey)

        Public key:
        \(key)
        """
        
        var items: [Any] = [shareText]
        if let qr = qrImage {
            items.append(qr)
        }
        
        let activityVC = UIActivityViewController(activityItems: items, applicationActivities: nil)
        
        if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           let rootVC = windowScene.windows.first?.rootViewController {
            if let popover = activityVC.popoverPresentationController {
                popover.sourceView = rootVC.view
                popover.sourceRect = CGRect(x: rootVC.view.bounds.midX, y: rootVC.view.bounds.midY, width: 0, height: 0)
                popover.permittedArrowDirections = []
            }
            rootVC.present(activityVC, animated: true)
        }
    }
    
    private func formatAddress(_ address: String) -> String {
        guard address.count > 10 else { return address }
        let start = address.prefix(6)
        let end = address.suffix(4)
        return "\(start)...\(end)"
    }
}

// MARK: - Empty State View

struct EmptyStateView: View {
    @Environment(\.colorScheme) var colorScheme
    /// First-run actions. Optional so existing call sites keep compiling; when
    /// provided, the guide shows tappable buttons wired to the real flows.
    var onShowMyCode: (() -> Void)? = nil
    var onScanCode: (() -> Void)? = nil
    /// __FIRST_CONTACT_REMOTE_2026_10_10__ The action for "they are not here".
    var onInviteByLink: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 22) {
            // Friendly brand illustration (Nano-generated, on-brand): two chat
            // bubbles + a padlock = private, encrypted conversation. Replaces the
            // plain hand-wave glyph for a warmer first impression. Transparent
            // background so it reads in light + dark mode.
            Image("EmptyStateArt")
                .resizable()
                .scaledToFit()
                .frame(width: 168, height: 168)
                .accessibilityHidden(true)

            VStack(spacing: 8) {
                Text("onboarding_start_title".localized)   // "You're all set!"
                    .font(.title2).fontWeight(.bold)
                    .multilineTextAlignment(.center)
                Text("onboarding_start_subtitle".localized) // no phone number, fully private
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }
            .accessibilityElement(children: .combine)

            // The 3 concrete first steps — the fix for "I opened it and understood
            // nothing." Each step is one plain sentence tied to a real action.
            VStack(alignment: .leading, spacing: 14) {
                OSHIFirstStepRow(number: 1, icon: "qrcode",
                          text: "onboarding_start_step1".localized)   // Share your code / scan theirs
                OSHIFirstStepRow(number: 2, icon: "checkmark.seal.fill",
                          text: "onboarding_start_step2".localized)   // You're connected — no signup
                OSHIFirstStepRow(number: 3, icon: "lock.fill",
                          text: "onboarding_start_step3".localized)   // Chat, call, share — end-to-end encrypted
            }
            .padding(18)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(colorScheme == .dark ? Color(.systemGray6) : Color.white)
                    .shadow(color: .black.opacity(0.06), radius: 10, y: 4)
            )
            .padding(.horizontal, 8)

            // __FIRST_CONTACT_REMOTE_2026_10_10__ Grouped by SITUATION, and the
            // remote case comes first.
            //
            // It used to be two buttons, "Show my code" and "Scan a friend's
            // code", and both need the other phone in the room. Someone adding
            // their first contact from their own kitchen had nothing to tap —
            // which is the reported "older users get lost when they are not
            // together". Sending a link is also the action that works in BOTH
            // situations, so it is the one that earns the primary slot; the QR
            // pair is still here, labelled for when you are side by side.
            if onInviteByLink != nil || onShowMyCode != nil || onScanCode != nil {
                VStack(spacing: 10) {
                    if let onInviteByLink {
                        Button(action: onInviteByLink) {
                            VStack(spacing: 2) {
                                Label("onboarding_invite_link".localized,
                                      systemImage: "paperplane.fill")
                                    .font(.headline)
                                Text("onboarding_invite_link_sub".localized)
                                    .font(.caption)
                                    .foregroundColor(.white.opacity(0.9))
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(
                                LinearGradient(colors: [oshiBrandColor, Color(red: 0.94, green: 0.28, blue: 0.56)],
                                               startPoint: .leading, endPoint: .trailing))
                            .foregroundColor(.white)
                            .cornerRadius(14)
                        }
                        .buttonStyle(PressScaleButtonStyle())
                        .accessibilityHint("onboarding_invite_link_sub".localized)
                    }

                    if onShowMyCode != nil || onScanCode != nil {
                        Text("onboarding_together_header".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .padding(.top, 4)
                    }

                    if let onShowMyCode {
                        Button(action: onShowMyCode) {
                            Label("onboarding_start_show_code".localized, systemImage: "qrcode")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                                // Demoted from the brand gradient to the tinted
                                // pill: there is only one primary action now,
                                // and two gradients side by side is the thing
                                // that makes a first-run screen unreadable.
                                .foregroundColor(.primary)
                                .background(oshiBrandColor.opacity(0.22))
                                .cornerRadius(14)
                        }
                        .buttonStyle(PressScaleButtonStyle())
                    }
                    if let onScanCode {
                        Button(action: onScanCode) {
                            Label("onboarding_start_scan_code".localized, systemImage: "qrcode.viewfinder")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                                // __PILL_CONTRAST_2026_08_22__ voir LocationSharingView:
                                // encre de marque sur fond de la même marque = illisible
                                // en mode sombre.
                                .foregroundColor(.primary)
                                .background(oshiBrandColor.opacity(0.22))
                                .cornerRadius(14)
                        }
                        .buttonStyle(PressScaleButtonStyle())
                    }
                }
                .padding(.horizontal, 8)
            }
        }
        .frame(maxWidth: 460)
        .padding(.horizontal)
    }
}

/// One numbered step in the first-run guide.
private struct OSHIFirstStepRow: View {
    let number: Int
    let icon: String
    let text: String
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                Circle().fill(oshiBrandColor.opacity(0.15)).frame(width: 30, height: 30)
                Text("\(number)").font(.scaled(14, weight: .bold)).foregroundColor(oshiBrandColor)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(text)
                    .font(.subheadline)
                    .foregroundColor(.primary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            Image(systemName: icon)
                .font(.scaled(15))
                .foregroundColor(.secondary)
                .padding(.top, 2)
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Mesh / LoRa status indicator

/// The green antenna in the top bar: how many devices this phone can reach
/// with no internet — nearby phones over the mesh (iPhone MultipeerConnectivity
/// + the Android bridge) and, when a Meshtastic radio is connected, the LoRa
/// nodes it has heard within Meshtastic's "online" window.
///
/// __TOOLBAR_DRIFT_2026_09_22__ Always drawn, grey when nothing is reachable.
/// It used to be inserted when the first peer connected and removed when the
/// last one dropped — and MultipeerConnectivity peers flap constantly — with a
/// `withAnimation(.repeatForever)` started in `onAppear`, which in a navigation
/// bar also animates the badge's own placement: the badge (and the bar around
/// it) slid by itself and never stopped. The motion is now the symbol effect
/// alone, which animates the glyph and never the layout, and a peer drop is
/// only shown after it has lasted a few seconds.
struct MeshStatusBadge: View {
    /// __ANTENNA_IS_A_DOOR_2026_10_27__ What a tap does. Nil keeps the badge the
    /// pure indicator it was, so no other call site changes behaviour.
    var onTap: (() -> Void)? = nil
    @EnvironmentObject private var meshManager: MeshNetworkManager
    @ObservedObject private var crossPlatform = CrossPlatformMesh.shared
    @ObservedObject private var lora = MeshtasticManager.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Mesh peer count as displayed — lags a DROP by `dropGrace` so a flap
    /// does not redraw the bar.
    @State private var shownMeshCount = 0
    private static let dropGrace: Duration = .seconds(8)

    /// __NEARBY_LISTED_EVERY_PHONE_TWICE_2026_11_02__ One shared census
    /// (`NearbyPeerCensus`), the same one the Nearby screen lists from, so the
    /// badge and the list cannot disagree about how many phones are around.
    ///
    /// This used to be `meshManager.connectedPeers.count +
    /// crossPlatform.connectedPeers.count`, which was wrong twice over. It
    /// double-counted any iPhone found by BOTH transports — the normal case,
    /// since both run at once — and it counted only ESTABLISHED SESSIONS, so a
    /// phone that had been discovered but not yet connected contributed
    /// nothing. With no session up the digits are hidden entirely, which is how
    /// the badge came to show no number at all in a room full of devices the
    /// Nearby screen was happily listing.
    private var liveMeshCount: Int {
        NearbyPeerCensus.nearbyCount(mesh: meshManager, crossPlatform: crossPlatform)
    }

    var body: some View {
        // Re-evaluated every 30 s so a LoRa node ages out of the count on
        // time, without any packet having to arrive.
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let loraReady = lora.isReady
            let loraCount = loraReady ? lora.onlineNodeCount(now: context.date) : 0
            let connected = shownMeshCount > 0 || loraReady

            HStack(spacing: 4) {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.scaled(13, weight: .semibold))
                    .symbolEffect(.variableColor.iterative, options: .repeating,
                                  isActive: connected && !reduceMotion)
                if shownMeshCount > 0 {
                    Text("\(shownMeshCount)")
                        .font(.scaled(12, weight: .bold, design: .rounded))
                        .monospacedDigit()
                }
                if shownMeshCount > 0 && loraReady {
                    Text("·")
                        .font(.scaled(12, weight: .bold))
                        .opacity(0.6)
                }
                if loraReady {
                    Text("LoRa \(loraCount)")
                        .font(.scaled(11, weight: .bold, design: .rounded))
                        .monospacedDigit()
                }
            }
            .foregroundColor(connected ? .green : .secondary)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Capsule().fill((connected ? Color.green : Color.secondary).opacity(0.15)))
            .fixedSize()
            .accessibilityElement()
            .accessibilityLabel(accessibilityText(loraReady: loraReady, loraCount: loraCount))
            .accessibilityIdentifier("mesh_status_badge")
            // The capsule is the tap target, not just the glyph and digits.
            .contentShape(Capsule())
            .accessibilityAddTraits(onTap == nil ? [] : .isButton)
            .accessibilityHint(onTap == nil ? "" : "peers.title".localized)
            .onTapGesture { onTap?() }
        }
        .task(id: liveMeshCount) {
            let count = liveMeshCount
            if count < shownMeshCount {
                try? await Task.sleep(for: Self.dropGrace)
                guard !Task.isCancelled else { return }
            }
            shownMeshCount = count
        }
    }

    private func accessibilityText(loraReady: Bool, loraCount: Int) -> String {
        var parts: [String] = []
        switch shownMeshCount {
        case 0: break
        case 1: parts.append("mesh.connected.one".localized)
        default: parts.append(String(format: "mesh.connected.many".localized, shownMeshCount))
        }
        if loraReady {
            parts.append("meshtastic.title".localized + ", " + "lora.node.peers".localized + ": \(loraCount)")
        }
        return parts.isEmpty ? "mesh.no_peers".localized : parts.joined(separator: ". ")
    }
}

/// The pill behind the live-location counter in the top bar.
struct LiveShareCounterLabel: View {
    let count: Int

    var body: some View {
        HStack(spacing: 3) {
            Image(systemName: "location.fill")
                .font(.scaled(11, weight: .semibold))
            Text("\(count)")
                .font(.scaled(12, weight: .bold, design: .rounded))
                .monospacedDigit()
        }
        .foregroundColor(.white)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(Capsule().fill(Color.green))
        .fixedSize()
    }
}

// MARK: - Dynamic mesh backdrop

/// Ambient empty-state background: the system background with a royalty-free
/// generated mesh-network image drifting slowly at the bottom. Kept subtle and
/// layered over the adaptive system color so foreground content stays readable
/// in both light and dark mode.
struct DynamicMeshBackground: View {
    @Environment(\.colorScheme) private var colorScheme
    @State private var drift = false

    var body: some View {
        ZStack {
            Color(.systemBackground)

            GeometryReader { geo in
                Image("MeshBackdrop")
                    .resizable()
                    .scaledToFill()
                    .frame(width: geo.size.width * 1.2, height: geo.size.height * 1.2)
                    .offset(x: drift ? -geo.size.width * 0.06 : geo.size.width * 0.06,
                            y: drift ? -12 : 12)
                    .opacity(colorScheme == .dark ? 0.9 : 0.35)
                    .animation(.easeInOut(duration: 14).repeatForever(autoreverses: true), value: drift)
                    .ignoresSafeArea()
            }

            // Top scrim so titles/toolbar stay legible over the artwork.
            LinearGradient(
                colors: [Color(.systemBackground).opacity(colorScheme == .dark ? 0.7 : 0.85),
                         Color(.systemBackground).opacity(0)],
                startPoint: .top, endPoint: .center
            )
            .ignoresSafeArea()
        }
        .onAppear { drift = true }
        .accessibilityHidden(true)
    }
}

// MARK: - Full-screen image viewer

/// A full-screen, pinch-to-zoom / drag-to-pan viewer for a single image, with a
/// close button and swipe-down-to-dismiss. Used to enlarge a contact's photo.
struct FullScreenImageViewer: View {
    let image: UIImage
    @Environment(\.dismiss) private var dismiss

    @State private var scale: CGFloat = 1
    @GestureState private var pinch: CGFloat = 1
    @State private var offset: CGSize = .zero
    @GestureState private var drag: CGSize = .zero

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .scaleEffect(max(1, scale * pinch))
                .offset(x: offset.width + drag.width, y: offset.height + drag.height)
                .gesture(
                    MagnificationGesture()
                        .updating($pinch) { v, s, _ in s = v }
                        .onEnded { v in
                            scale = min(max(1, scale * v), 5)
                            if scale <= 1 { offset = .zero }
                        }
                )
                .simultaneousGesture(
                    DragGesture()
                        .updating($drag) { v, s, _ in
                            // Only pan when zoomed in; otherwise let swipe-down dismiss.
                            if scale > 1 { s = v.translation }
                        }
                        .onEnded { v in
                            if scale > 1 {
                                offset.width += v.translation.width
                                offset.height += v.translation.height
                            } else if v.translation.height > 100 {
                                dismiss()
                            }
                        }
                )
                .onTapGesture(count: 2) {
                    withAnimation(.spring(response: 0.3)) {
                        scale = scale > 1 ? 1 : 2.5
                        if scale <= 1 { offset = .zero }
                    }
                }

            VStack {
                HStack {
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.scaled(16, weight: .bold))
                            .foregroundColor(.white)
                            .padding(12)
                            .background(Circle().fill(Color.white.opacity(0.2)))
                    }
                    .accessibilityLabel("common.close".localized)
                    .padding(.trailing, 16)
                    .padding(.top, 8)
                }
                Spacer()
            }
        }
        .statusBarHidden()
    }
}


// MARK: - __LIST_TIME_TICKS_2026_11_09__ List timestamps that keep up

/// Reported: "in Chats the time of the last message only updates when I go to
/// another screen — the tab open, phone locked, back 5 minutes later, it still
/// says the old thing." A row's label was computed in the row's `body` against
/// `Date()` and only recomputed when the row happened to re-render, i.e. when
/// some data changed or the screen came back. Nothing told it that TIME had
/// moved: "today 23:58" stayed a clock time past midnight instead of becoming
/// "Yesterday", "Yesterday" stayed so on the next day, and so on.
///
/// ONE clock for the whole app, not a timer per row: it ticks every 60 s
/// (labels are minute-granular) and jumps immediately when the app returns to
/// the foreground — so after unlocking the label is right at once instead of
/// up to a minute later. Only the small `ListTimestampText` label observes it,
/// so a tick re-renders the visible LABELS, never the rows or the list.
@MainActor
final class ListClock: ObservableObject {
    static let shared = ListClock()

    @Published private(set) var now = Date()
    private var timer: Timer?
    private var observers: [NSObjectProtocol] = []

    private init() {
        let t = Timer(timeInterval: 60, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        t.tolerance = 5
        RunLoop.main.add(t, forMode: .common)
        timer = t
        let nc = NotificationCenter.default
        for name in [UIApplication.willEnterForegroundNotification,
                     UIApplication.didBecomeActiveNotification,
                     UIApplication.significantTimeChangeNotification] {
            observers.append(nc.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.tick() }
            })
        }
    }

    func tick() { now = Date() }
}

/// The label rule, pure so it is testable against any `now`.
enum ListTimestamp {
    enum Kind: Equatable { case time, yesterday, weekday, date }

    static func kind(for date: Date, now: Date, includeWeekday: Bool,
                     calendar: Calendar = .current) -> Kind {
        if calendar.isDate(date, inSameDayAs: now) { return .time }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now),
           calendar.isDate(date, inSameDayAs: yesterday) { return .yesterday }
        if includeWeekday, calendar.isDate(date, equalTo: now, toGranularity: .weekOfYear) {
            return .weekday
        }
        return .date
    }

    static func label(for date: Date, now: Date, includeWeekday: Bool) -> String {
        switch kind(for: date, now: now, includeWeekday: includeWeekday) {
        case .time:      return OshiRowFormatters.string(date, format: "HH:mm")
        case .yesterday: return "date_yesterday".localized
        case .weekday:   return OshiRowFormatters.string(date, format: "EEEE")
        case .date:      return OshiRowFormatters.string(date, format: "MMM d")
        }
    }
}

/// A conversation-list timestamp that re-reads `ListClock` by itself.
struct ListTimestampText: View {
    let date: Date
    /// 1:1 rows show the weekday for this week; group rows never did.
    var includeWeekday: Bool = false
    @ObservedObject private var clock = ListClock.shared

    var body: some View {
        Text(ListTimestamp.label(for: date, now: clock.now, includeWeekday: includeWeekday))
    }
}
