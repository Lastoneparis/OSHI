//
//  ContentView.swift
//  OSHI - Main Content View
//
//  FIXED: Tab preloading + Badges + LIGHT MODE VISIBILITY + Group Unread Count
//  Brand color #5616EC for selected tab icons
//

import SwiftUI

// MARK: - Brand Color
private let appBrandColor = UIColor(red: 0.337, green: 0.086, blue: 0.925, alpha: 1.0)

struct ContentView: View {
    @EnvironmentObject var identityManager: IdentityManager
    @EnvironmentObject var meshManager: MeshNetworkManager
    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var groupManager: GroupManager
    @EnvironmentObject var messageGroupManager: MessageGroupManager
    @StateObject private var permissionHelper = PermissionHelper()
    @StateObject private var voiceCallManager = VoiceCallManager.shared
    @ObservedObject private var notificationManager = NotificationManager.shared
    // 🔧 FIX (2026-07-27): observed purely for `incomingCallSurfaceOwner` — the
    // single source of truth for "who is drawing the incoming-call UI".
    @ObservedObject private var voipPushManager = VoIPPushManager.shared

    // Nearby group discovery banner
    @State private var nearbyGroupName: String?
    @State private var nearbyGroupMemberCount: Int = 0
    @State private var showNearbyGroupBanner = false
    /// __DUO_POLISH_2026_11_18__ The banner belongs to the list column when
    /// the window splits (open iPhone Duo, iPad): across both panes it covered
    /// the open conversation's header.
    @Environment(\.horizontalSizeClass) private var rootSizeClass
    @State private var rootWidth: CGFloat = 0
    private var nearbyBannerWidth: CGFloat? {
        guard rootWidth > 0, ThreadSplitMetrics.isWide(sizeClass: rootSizeClass, width: rootWidth) else { return nil }
        return ThreadSplitMetrics.sidebarWidth(for: rootWidth)
    }
    /// __MERGED_CHATS_2026_11_18__ With the merged chat list on, "group nearby"
    /// is a card IN that list (`NearbyGroupsCard`), not this banner floating
    /// over whatever pane is open. Off: the banner, exactly as before.
    @AppStorage(MergedChatsSetting.storageKey) private var mergedChats = false

    // 🔧 2026-06-08: Forces the call-view branch (VoiceCallView/VideoCallView) to
    // be rebuilt with a fresh SwiftUI identity when a video upgrade is accepted.
    // Without this kick, a stale @Published diff sometimes left VoiceCallView on
    // screen even though isCurrentCallVideo had flipped to true — user only saw
    // the video stream when they put the call into PIP mode.
    @State private var callViewRebuildTick: Int = 0

    var body: some View {
        ZStack {
            if identityManager.isConnected {
                MainTabView()
            } else if identityManager.keysLockedByDevice {
                // Keys exist but the device hasn't been unlocked since boot —
                // showing IdentitySetupView would confuse the user into thinking
                // their identity was wiped. Show a brief unlocking state; the
                // protectedDataDidBecomeAvailable / willEnterForeground observers
                // in IdentityManager will retry the load and flip isConnected.
                VStack(spacing: 16) {
                    ProgressView()
                    Text("content.unlocking".localized)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel("content.unlocking".localized)
            } else {
                IdentitySetupView()
            }

            // Nearby group discovery banner
            if showNearbyGroupBanner, !mergedChats, let groupName = nearbyGroupName {
                VStack {
                    HStack(spacing: 12) {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .font(.scaled(18, weight: .semibold))
                            .foregroundColor(.white)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Group nearby")
                                .font(.caption)
                                .foregroundColor(.white.opacity(0.8))
                            Text("\(groupName) (\(nearbyGroupMemberCount) members)")
                                .font(.subheadline)
                                .fontWeight(.semibold)
                                .foregroundColor(.white)
                        }
                        .accessibilityElement(children: .combine)
                        Spacer()
                        Button {
                            withAnimation { showNearbyGroupBanner = false }
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.white.opacity(0.7))
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                        }
                        .accessibilityLabel("common.dismiss".localized)
                    }
                    .padding()
                    .background(Theme.brand.cornerRadius(14))
                    .shadow(color: .black.opacity(0.2), radius: 8, y: 4)
                    .frame(maxWidth: 600)
                    .padding(.horizontal)
                    .padding(.top, 50)
                    Spacer()
                }
                .frame(width: nearbyBannerWidth)
                .frame(maxWidth: .infinity, alignment: .leading)
                .transition(.move(edge: .top).combined(with: .opacity))
                .zIndex(900)
            }

            // 🔧 v1.0.19 build 38 (Group B fix): persistent banner when iOS
            // notification permission is .denied. Users who previously tapped
            // "Don't Allow" (or had it revoked) end up in this state — the
            // launch-time re-prompt does nothing because iOS only honors one
            // requestAuthorization decision. The banner deep-links straight to
            // OSHI's notification settings page (iOS 16+) or the app's general
            // settings (iOS 15) so they can flip the switch in 2 taps.
            // Auto-dismisses when status becomes .authorized via the
            // didBecomeActive observer in NotificationManager.
            if notificationManager.authorizationStatus == .denied {
                VStack {
                    Button {
                        notificationManager.openNotificationSettings()
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "bell.slash.fill")
                                .font(.scaled(18, weight: .semibold))
                                .foregroundColor(.white)
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("notification.banner.disabled.title".localized)
                                    .font(.caption)
                                    .foregroundColor(.white.opacity(0.85))
                                Text("notification.banner.disabled.subtitle".localized)
                                    .font(.subheadline)
                                    .fontWeight(.semibold)
                                    .foregroundColor(.white)
                                    .lineLimit(2)
                                    .multilineTextAlignment(.leading)
                            }
                            Spacer(minLength: 8)
                            Image(systemName: "arrow.up.right.square.fill")
                                .foregroundColor(.white.opacity(0.85))
                                .accessibilityHidden(true)
                        }
                        .padding()
                        .background(Color.orange.cornerRadius(14))
                        .shadow(color: .black.opacity(0.2), radius: 8, y: 4)
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("notification.banner.disabled.hint".localized)
                    .frame(maxWidth: 600)
                    .padding(.horizontal)
                    .padding(.top, 50)
                    Spacer()
                }
                .frame(maxWidth: .infinity)
                .transition(.move(edge: .top).combined(with: .opacity))
                .zIndex(901)
            }

            // Global incoming call overlay — the in-app incoming-call surface, shown
            // on ANY screen for calls CallKit is NOT presenting: the China /
            // callKitDisabled bypass, mesh/WebSocket-delivered calls (the server only
            // sends a VoIP push when the callee's socket is offline), and the case
            // where reportNewIncomingCall failed outright.
            //
            // 🔧 FIX (2026-07-27) — THE duplicate-surface bug. This branch had no
            // CallKit guard at all, so a VoIP-push call drew the CallKit banner AND
            // this full-screen overlay simultaneously (exactly what the owner saw on
            // device). The guard that was supposed to prevent it
            // (`!isCallKitHandlingIncoming`) lived in the IncomingCallOverlay
            // ViewModifier, which was never installed on any view and so never ran —
            // that is why "duplication was anticipated but not prevented".
            // The rule now: if CallKit is presenting, CallKit owns the UI, full stop.
            //
            // 🔧 FIX (2026-07-27b): this check is now an OPTIMISATION, not the
            // guarantee. It avoids building the overlay at all in the common case, but
            // IncomingCallOverlayView refuses to draw itself while
            // `incomingCallSurfaceOwner == .callKit`, so correctness no longer depends
            // on every presenter remembering this condition.
            if voiceCallManager.callState == .ringing &&
               voiceCallManager.incomingCall != nil &&
               !voiceCallManager.isOutgoingCall &&
               voipPushManager.incomingCallSurfaceOwner == .inApp {
                IncomingCallOverlayView(
                    callerName: voiceCallManager.incomingCall?.peerName ?? "Incoming Call",
                    callerPublicKey: voiceCallManager.incomingCall?.peerPublicKey ?? "",
                    isVideoCall: voiceCallManager.incomingCall?.isVideoCall ?? false,
                    voiceCallManager: voiceCallManager,
                    onDismiss: {
                        // Dismiss handled by the overlay
                    },
                    onAccepted: { isVideo in
                        // Acceptance handled by the overlay
                    }
                )
                .transition(.opacity)
                .zIndex(1000)
            }
            
            // 🔧 CRITICAL FIX: Single conditional block for all active call states
            // Previously this was TWO separate blocks (connecting/ringing AND inCall),
            // causing SwiftUI to destroy and recreate the view on state transition,
            // which triggered onDisappear → endCall() → killed the call
            // __CALL_MINIMIZE_2026_08_16__ `!isCallMinimized`: the overlay steps
            // aside so the app underneath becomes reachable during a call. The
            // call is NOT torn down — `VoiceCallView.onDisappear` only ends a call
            // when the state has already left `.inCall`, which minimizing does not
            // touch. The compact bar below takes over as the way back.
            if voiceCallManager.callState == .connecting ||
               (voiceCallManager.callState == .ringing && voiceCallManager.isOutgoingCall) ||
               voiceCallManager.callState == .inCall {
                if voiceCallManager.isCurrentCallVideo {
                    // __CALL_MINIMIZE_VIDEO_2026_08_20__ NOT gated on
                    // `!isCallMinimized`. VideoCallView owns the whole video
                    // pipeline in a `@StateObject` — capture session, encoder,
                    // decoder, display layer — so taking it out of the hierarchy
                    // to minimize would deallocate all of it and force a fresh
                    // session on restore. It stays mounted and renders its own
                    // compact pill instead; see `VideoCallView.minimizedVideoPill`.
                    VideoCallView(
                        voiceCallManager: voiceCallManager,
                        contactName: voiceCallManager.currentCallPeerName ?? "Video Call"
                    )
                    .id("videoCall-\(callViewRebuildTick)")
                    .transition(.opacity)
                    .zIndex(1000)
                } else if !voiceCallManager.isCallMinimized {
                    VoiceCallView(
                        voiceCallManager: voiceCallManager,
                        contactName: voiceCallManager.currentCallPeerName ?? "Call",
                        contactPublicKey: voiceCallManager.currentCallPeer ?? ""
                    )
                    .id("voiceCall-\(callViewRebuildTick)")
                    .transition(.opacity)
                    .zIndex(1000)
                }
            }

            // __CALL_MINIMIZE_2026_08_16__ The way back. Sits at the TOP: the tab
            // bar floats over the bottom on iOS 26, and a bar down there would
            // either hide behind it or push it around.
            if voiceCallManager.isCallMinimized, voiceCallManager.callState == .inCall,
               !voiceCallManager.isCurrentCallVideo {
                MinimizedCallBar(voiceCallManager: voiceCallManager)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .zIndex(999)
            }
        }
        .onAppear {
            voiceCallManager.configure(meshManager: meshManager)
            #if DEBUG
            // 📸 App Store screenshot capture: `-OSHIScreens` seeds the existing
            // demo content (Alice/Bob/Charlie, groups, AI) and skips onboarding so
            // localized screenshots can be captured headlessly via simctl. Compiled
            // OUT of release builds (#if DEBUG) → zero production effect.
            if ProcessInfo.processInfo.arguments.contains("-OSHIScreens"), !identityManager.isConnected {
                // `-OSHIEmpty` forces an identity but skips demo seeding so the
                // first-run empty-state guide can be captured.
                if !ProcessInfo.processInfo.arguments.contains("-OSHIEmpty") {
                    DemoDataManager.loadDemoData(identityManager: identityManager)
                }
            }
            #endif
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("VideoUpgradeActivated"))) { _ in
            // 🔧 2026-06-08: Bump the rebuild tick so SwiftUI tears down
            // VoiceCallView and instantiates VideoCallView with a fresh identity.
            // The @Published flip alone wasn't reliably driving the swap when a
            // transition was already in flight.
            withAnimation(.easeInOut(duration: 0.25)) {
                callViewRebuildTick &+= 1
            }
        }
        .measureWidth(into: $rootWidth)
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("NearbyGroupDiscovered"))) { notification in
            if let name = notification.userInfo?["groupName"] as? String,
               let count = notification.userInfo?["memberCount"] as? Int {
                nearbyGroupName = name
                nearbyGroupMemberCount = count
                withAnimation(.spring(response: 0.4)) { showNearbyGroupBanner = true }
                // Auto-dismiss after 8 seconds
                DispatchQueue.main.asyncAfter(deadline: .now() + 8) {
                    withAnimation { showNearbyGroupBanner = false }
                }
            }
        }
        .animation(.easeInOut(duration: 0.3), value: voiceCallManager.callState == .ringing)
        .alert("bluetooth_off_title".localized, isPresented: $permissionHelper.showBluetoothAlert) {
            Button("open_settings".localized) {
                permissionHelper.openSettings()
            }
            Button("cancel".localized, role: .cancel) {
                permissionHelper.showBluetoothAlert = false
            }
        } message: {
            Text("bluetooth_off_message".localized)
        }
        .alert("network_required_title".localized, isPresented: $permissionHelper.showNetworkAlert) {
            Button("ok".localized) {
                permissionHelper.showNetworkAlert = false
            }
        } message: {
            Text("network_required_message".localized)
        }
    }
}

// MARK: - Main Tab View

struct MainTabView: View {
    @EnvironmentObject var identityManager: IdentityManager
    @EnvironmentObject var meshManager: MeshNetworkManager
    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var groupManager: GroupManager
    @EnvironmentObject var messageGroupManager: MessageGroupManager
    
    @Environment(\.colorScheme) var colorScheme

    // 📸 Screenshot capture: the requested tab is read from UserDefaults, which the
    // launcher sets via `-OSHITab <n>` (NSArgumentDomain parses `-key value` into
    // UserDefaults). NB it must NOT be applied as the @State *initial* value: the
    // TabView is backed by a UITabBarController that starts at index 0 and only
    // follows the SwiftUI selection when it observes a *transition*. Initialising
    // selectedTab straight to 1 leaves the binding at 1 but the UIKit controller
    // stuck on tab 0 (no 0→1 change ever fired). So we start at 0 and drive the
    // switch from .onAppear on the next runloop — that real transition moves the
    // controller. Release builds have no such flag and stay on tab 0.
    static func requestedCaptureTab() -> Int? {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-OSHIScreens") {
            let t = UserDefaults.standard.integer(forKey: "OSHITab")
            if (1...4).contains(t) { return t }
        }
        #endif
        return nil
    }

    @State private var selectedTab = 0
    @State private var showContactPicker = false
    @State private var documentToShare: URL?
    #if DEBUG
    // 📸 Screenshot capture: present the "Thinking of You" composer / group
    // discovery over the tab UI for App Store shots.
    @State private var captureThinking = false
    @State private var captureDiscover = false
    // 📸 iPad: the `-OSHITab` direct-root swap doesn't reliably switch on the
    // regular-width layout, so AI/Map are presented via fullScreenCover (the same
    // mechanism as discover/thinking, which works on every idiom).
    @State private var captureAI = false
    @State private var captureMap = false
    @State private var captureGroupChat = false
    @State private var captureVideoNoteChat = false  // __VIDEO_NOTE_2026_09_24__
    @State private var captureMeshCoreSettings = false  // __MESHCORE_INTEROP_2026_11_25__
    @State private var captureVideoNoteConversation: Conversation?
    @State private var captureShareLoc = false
    @State private var captureGroupObj: MessageGroup?
    // 📸 The App Store Connect review screenshot for the two subscriptions. The
    // paywall normally lives behind Mail → account → quota row, which needs a
    // registered mailbox; this presents it directly so the shot does not depend
    // on network state the capture run cannot control.
    @State private var capturePaywall = false
    /// __WALLPAPER_TONE_2026_09_24__ `-OSHIWallpaperSettings`: the chat
    /// wallpaper picker (Settings → Appearance → Chat wallpaper) for capture.
    @State private var captureWallpaperSettings = false
    #endif

    @ObservedObject private var updateManager = AppUpdateManager.shared
    /// __MAIL_UNREAD_REFRESH_2026_11_22__ observed so the Chats tab badge counts unread mail.
    @ObservedObject private var mailClient = OSHIMailClient.shared

    // Notification deep link: pending navigation targets
    @State private var pendingGroupId: String?
    @State private var pendingConversationId: String?
    /// Non-nil drives ChatsTabView to the Email segment; the value is the mailbox id
    /// to open ("" = open the Inbox with no specific message). Set from a new-email
    /// notification tap; cleared by MailView once it has navigated.
    @State private var pendingMailMessageId: String?

    // The real 5-tab interface. Extracted so the DEBUG capture path can swap in a
    // single full-screen root without duplicating the tab definitions.
    private var tabViewBody: some View {
        TabView(selection: $selectedTab) {
            // Tab 1: Chats (Messages + Groups merged)
            ChatsTabView(
                pendingConversationId: $pendingConversationId,
                pendingGroupId: $pendingGroupId,
                pendingMailMessageId: $pendingMailMessageId
            )
            .tabItem {
                Label("tab_messages".localized, systemImage: "message.fill")
            }
            // __MAIL_UNREAD_REFRESH_2026_11_22__ + unread Inbox mail: the Email segment lives
            // in this tab, and a mail-only unread left the tab bar blank.
            .badge(ChatsTabBadge.count(oneToOne: totalUnreadCount, groups: totalGroupUnreadCount,
                                       mailInbox: mailClient.unreadInboxCount))
            .tag(0)

            // Tab 2: AI Chat (Offline AI Assistant)
            OfflineAITabView()
            .tabItem {
                // Localized via the key itself: NSLocalizedString falls back
                // to "AI" when no translation exists, so this stays correct
                // even before the i18n team adds a dedicated value. "AI" is
                // also a brand-acronym that reads the same in most locales.
                Label("AI".localized, systemImage: "sparkles")
            }
            .tag(1)

            // Tab 3: New Message (Center - prominent)
            NewMessageView()
            .tabItem {
                Label("tab_new".localized, systemImage: "plus.circle.fill")
            }
            .tag(2)

            // Tab 4: Map (Offline Maps)
            MapTabView()
            .tabItem {
                // Reuse the existing "map.title" key (present in all 34
                // locales = "Map") instead of a hardcoded English literal.
                Label("map.title".localized, systemImage: "map")
            }
            .tag(3)

            // Tab 5: More (Settings + Nearby + Portal)
            MoreTabView(nearbyPeersCount: nearbyPeersCount)
            .tabItem {
                Label("tab_more".localized, systemImage: "ellipsis")
            }
            .tag(4)
        }
        .tint(Theme.brand)
    }

    #if DEBUG
    // 📸 Direct full-screen render of one tab's root for deterministic capture.
    @ViewBuilder
    private func captureRoot(for tab: Int) -> some View {
        switch tab {
        case 1: OfflineAITabView()
        case 2: NewMessageView()
        case 3: MapTabView()
        case 4: MoreTabView(nearbyPeersCount: nearbyPeersCount)
        default: tabViewBody
        }
    }
    #endif

    var body: some View {
        #if DEBUG
        // 📸 __PEER_LOST_UX_2026_09_29__ The "peer went silent" card, on the call
        // gradient, for review screenshots (`-OSHIPeerLostPreview`, add
        // `-OSHIOffline` for the you're-offline variant).
        // __KEYBOARD_PRIVACY_2026_10_01__ Settings › Privacy straight away, for the
        // on-device keyboard-privacy UI test (OSHIUITests KeyboardPrivacyDeviceUITests).
        // __GROUP_INFO_SETTINGS_2026_11_20__ group info page screenshots
        // (`-OSHIScreens -OSHIGroupInfoPreview -OSHIGroupInfoRole admin|member`).
        if ProcessInfo.processInfo.arguments.contains("-OSHIGroupInfoPreview") {
            return AnyView(GroupInfoPreviewHost())
        }
        // __GROUP_COMMENTS_2026_11_21__ `-OSHIScreens -OSHICommentsPreview chat|thread|settings`.
        if ProcessInfo.processInfo.arguments.contains("-OSHICommentsPreview") {
            return AnyView(GroupCommentsPreviewHost())
        }
        // __BUBBLE_MEDIA_2026_11_21__ `-OSHIScreens -OSHIBubblePreview group|group2|channel|direct`.
        if ProcessInfo.processInfo.arguments.contains("-OSHIBubblePreview") {
            return AnyView(BubbleMediaPreviewHost())
        }
        // __STRICT_SECURITY_MODE_2026_11_22__ review screenshots:
        // `-OSHIScreens -OSHIStrictSettings` / `-OSHIStrictExplainer [-OSHIStrictConfirm]`.
        if ProcessInfo.processInfo.arguments.contains("-OSHIStrictSettings") {
            return AnyView(NavigationStack { StrictSecurityModeView() })
        }
        if ProcessInfo.processInfo.arguments.contains("-OSHIStrictExplainer") {
            let confirm = ProcessInfo.processInfo.arguments.contains("-OSHIStrictConfirm")
            return AnyView(StrictSecurityExplainerView(onEnable: confirm ? {} : nil))
        }
        if ProcessInfo.processInfo.arguments.contains("-OSHIPrivacySettings") {
            return AnyView(NavigationStack { PrivacySettingsView() })
        }
        // __OLED_GLASS_2026_11_16__ Settings root / Appearance straight away,
        // for the OLED-glass review screenshots.
        if ProcessInfo.processInfo.arguments.contains("-OSHISettingsRoot") {
            return AnyView(SettingsView())
        }
        if ProcessInfo.processInfo.arguments.contains("-OSHIAppearanceSettings") {
            return AnyView(NavigationStack { AppearanceSettingsView() })
        }
        if ProcessInfo.processInfo.arguments.contains("-OSHISchedulePreview") {
            return AnyView(Color(.systemGroupedBackground).ignoresSafeArea().sheet(isPresented: .constant(true)) {
                CreateScheduledCallView(conversationId: DemoDataManager.aliceKey,
                                        conversationName: "Yassine") { _ in }
            })
        }
        if ProcessInfo.processInfo.arguments.contains("-OSHIPeerLostPreview") {
            return AnyView(ZStack {
                LinearGradient(colors: [Color.orange.opacity(0.8), Color.red.opacity(0.6)],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
                    .ignoresSafeArea()
                PeerLostCard(peerName: "Yassine", deadline: Date().addingTimeInterval(7),
                             totalSeconds: 12,
                             selfOffline: ProcessInfo.processInfo.arguments.contains("-OSHIOffline"))
                    .padding()
            })
        }
        if ProcessInfo.processInfo.arguments.contains("-OSHICallPreview") {
            return AnyView(IncomingCallOverlayView(
                callerName: "Alice", callerPublicKey: DemoDataManager.aliceKey, isVideoCall: false,
                voiceCallManager: VoiceCallManager.shared, onDismiss: {}, onAccepted: { _ in }))
        }
        #endif
        return AnyView(mainBody)
    }

    private var mainBody: some View {
        ZStack {
            #if DEBUG
            // 📸 Screenshot capture: SwiftUI's TabView keeps its backing
            // UITabBarController stuck on index 0 no matter how we drive the
            // `selection` binding at launch (the binding reports the right value
            // but the controller never transitions). Rather than fight that, in
            // capture mode we render the requested tab's root view directly, so
            // the shot is deterministic. Never hit in Release (no -OSHIScreens).
            if let capTab = MainTabView.requestedCaptureTab(),
               ProcessInfo.processInfo.arguments.contains("-OSHIScreens") {
                captureRoot(for: capTab)
            } else {
                tabViewBody
            }
            #else
            tabViewBody
            #endif
        }
        #if DEBUG
        .fullScreenCover(isPresented: $captureThinking) {
            CreateScheduledMessageView(
                isPresented: $captureThinking,
                conversationId: DemoDataManager.aliceKey,
                conversationName: "Alice",
                conversationContext: "")
        }
        .fullScreenCover(isPresented: $captureDiscover) {
            NavigationView { DiscoverPublicGroupsView() }
        }
        .fullScreenCover(isPresented: $capturePaywall) {
            PremiumPaywallView(mail: OSHIMailClient.shared)
        }
        .fullScreenCover(isPresented: $captureWallpaperSettings) {
            NavigationStack { ChatWallpaperSettingsView() }
        }
        .fullScreenCover(isPresented: $captureAI) {
            OfflineAITabView()
        }
        .fullScreenCover(isPresented: $captureMap) {
            MapTabView()
        }
        .fullScreenCover(isPresented: $captureGroupChat) {
            if let g = captureGroupObj {
                NavigationView { GroupChatView(group: g) }
            }
        }
        .fullScreenCover(isPresented: $captureVideoNoteChat) {
            if let c = captureVideoNoteConversation {
                NavigationView { ChatView(conversation: c) }
            }
        }
        .fullScreenCover(isPresented: $captureMeshCoreSettings) {
            NavigationStack { MeshtasticSettingsView() }
        }
        .fullScreenCover(isPresented: $captureShareLoc) {
            LocationShareSheet(onShareLocation: { _ in }, conversationId: DemoDataManager.aliceKey)
        }
        #endif
        .onAppear {
            configureTabBarAppearance()

            #if DEBUG
            // 📸 Screenshot capture: `-OSHITab <0-4>` selects which tab to show
            // (Messages/AI/New/Map/More). Release-stripped.
            let args = ProcessInfo.processInfo.arguments
            let requestedTab = MainTabView.requestedCaptureTab()   // nil or 1...4
            if args.contains("-OSHIScreens") {
                // Drive the requested tab as a *transition* from 0 on the next
                // runloop — a straight init-to-target leaves the backing
                // UITabBarController stuck on tab 0 (see requestedCaptureTab docs).
                if let t = requestedTab {
                    DispatchQueue.main.async { selectedTab = t }
                }
                // Seed demo conversations now the tab UI + managers are up (reuse the
                // launch identity, no wipe) so screenshots show populated content.
                // `-OSHIEmpty` skips this so the empty-state first-run guide shows.
                if !args.contains("-OSHIEmpty") {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                    DemoDataManager.loadDemoData(identityManager: identityManager, regenerateIdentity: false)
                    // __DUO_POLISH_2026_11_18__ `-OSHINearbyDemo`: show the
                    // "Group nearby" banner (to check where it sits when wide).
                    if args.contains("-OSHINearbyDemo") {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                            NotificationCenter.default.post(name: NSNotification.Name("NearbyGroupDiscovered"),
                                                            object: nil,
                                                            userInfo: ["groupName": "Café Lumière", "memberCount": 7])
                        }
                    }
                    // __MESHCORE_INTEROP_2026_11_25__ DEBUG capture of the MeshCore screens.
                    if MeshCoreDemo.screen != nil {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
                            if let conv = MeshCoreDemo.seed(identity: identityManager) {
                                captureVideoNoteConversation = conv
                                captureVideoNoteChat = true
                            } else if MeshCoreDemo.screen == "settings" {
                                captureMeshCoreSettings = true
                            }
                        }
                    }
                    // __VIDEO_NOTE_2026_09_24__ DEBUG capture: seed round notes, open Alice.
                    VideoNoteDemo.seedIfRequested { conv in
                        guard let conv else { return }
                        captureVideoNoteConversation = conv
                        captureVideoNoteChat = true
                    }

                    // 📸 `-OSHIOpenGroup` / `-OSHIOpenChat` navigate straight into a
                    // demo group or 1:1 conversation interior so it can be captured
                    // with a plain `simctl io screenshot` — far more reliable than
                    // driving XCUITest taps. These use the real navigation push (not
                    // a tab switch), so they work despite the TabView selection quirk.
                    if args.contains("-OSHIOpenGroup") {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                            if let g = MessageGroupManager.shared.groups.first {
                                selectedTab = 0
                                pendingGroupId = g.id.uuidString
                                if args.contains("-OSHIGroupTrickle") {
                                    DemoDataManager.trickleLateGroupMessages(into: g.id)
                                }
                                DemoDataManager.scheduleScrollLabIncoming(groupId: g.id, myKey: identityManager.publicKey)
                            }
                        }
                    } else if args.contains("-OSHIOpenChat") || args.contains("-OSHIVoiceChat") {
                        // Open the Alice 1:1 conversation (seeded by loadDemoData) —
                        // shows the encrypted-bubble chat interior for the store shot.
                        // FIX: use the real participant key (aliceKey), not "Alice" —
                        // the alias never matched participantPublicKey so the push
                        // silently no-op'd and the list showed instead.
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                            // __COVER_WAITING_BUBBLE_2026_11_18__ `-OSHICoverWaitingDemo` (with
                            // `-covertChannelEnabled YES`): one outgoing message to Alice that
                            // cover mode is holding, to capture the "waiting" bubble.
                            if args.contains("-OSHICoverWaitingDemo"), let mm = MessageManager.shared {
                                let me = identityManager.publicKey
                                let id = "cover-waiting-demo"
                                mm.messages.removeAll { $0.id == id }
                                mm.messages.append(SecureMessage(
                                    id: id, senderAddress: me, recipientAddress: DemoDataManager.aliceKey,
                                    encryptedContent: EncryptedMessage(ciphertext: "", signature: "demo_signature",
                                                                       senderPublicKey: me, timestamp: Date()),
                                    timestamp: Date(), isRead: true, deliveryStatus: .failed,
                                    senderPublicKey: me, recipientPublicKey: DemoDataManager.aliceKey,
                                    plaintextContent: "On my way — see you at the station."))
                                mm.addToRetryQueue(messageId: id, content: "On my way — see you at the station.",
                                                   recipientKey: DemoDataManager.aliceKey)
                                // __RESTRICTED_NETWORK_HINT_2026_11_18__ and Alice's last
                                // message wears the "restricted network" chip.
                                if let last = mm.messages.last(where: { $0.senderPublicKey == DemoDataManager.aliceKey }) {
                                    RestrictedNetworkHintStore.insert(last.id)
                                }
                                mm.refreshConversations()
                            }
                            selectedTab = 0
                            pendingConversationId = DemoDataManager.aliceKey
                            DemoDataManager.scheduleScrollLabIncoming(groupId: nil, myKey: identityManager.publicKey)
                        }
                    } else if args.contains("-OSHIThinking") {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { captureThinking = true }
                    } else if args.contains("-OSHIDiscover") {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { captureDiscover = true }
                    } else if args.contains("-OSHIWallpaperChat") {
                        // __WALLPAPER_TONE_2026_09_24__ open Alice's thread directly
                        // (same cover as the video-note capture) to shoot the tone.
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                            let alice = MessageManager.canonicalIdentity(DemoDataManager.aliceKey)
                            MessageManager.shared?.refreshConversations()
                            if let c = MessageManager.shared?.conversations.first(where: {
                                MessageManager.canonicalIdentity($0.participantPublicKey) == alice
                            }) {
                                captureVideoNoteConversation = c
                                captureVideoNoteChat = true
                            }
                        }
                    } else if args.contains("-OSHIMailDemo") {
                        // __DUO_POLISH_2026_11_18__ The real Email segment (not the
                        // fixture harness, which replaces the app root) on a local
                        // demo mailbox — no server, no account — to capture the
                        // inbox | message split. `-OSHIMailDemoOpen` also opens one.
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                            // `-OSHIMailDemoOffline`: no mailbox installed (MailView fakes the
                            // offline failure), to capture "can't reach mail".
                            if !args.contains("-OSHIMailDemoOffline") { MailDemoFixture.install() }
                            selectedTab = 0
                            pendingMailMessageId = args.contains("-OSHIMailDemoOpen") ? MailDemoFixture.openId : ""
                        }
                    } else if args.contains("-OSHIWallpaperSettings") {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { captureWallpaperSettings = true }
                    } else if args.contains("-OSHIPaywall") {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { capturePaywall = true }
                    } else if args.contains("-OSHIAI") {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { captureAI = true }
                    } else if args.contains("-OSHIMap") {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { captureMap = true }
                    } else if args.contains("-OSHIShareLoc") {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { captureShareLoc = true }
                    } else if args.contains("-OSHIGroupChat") {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) {
                            captureGroupObj = MessageGroupManager.shared.groups.first
                            captureGroupChat = captureGroupObj != nil
                        }
                    } else if let t = requestedTab {
                        // Re-assert after the manager republish settles, in case
                        // the reload rebuilt the tab container back to 0.
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) {
                            selectedTab = t
                        }
                    }
                }
                } // end !-OSHIEmpty
            }
            #endif

            // Sync app badge with actual unread count on launch
            messageManager.syncAppBadge()

            // Start periodic public group broadcasting
            messageGroupManager.startPeriodicPublicGroupBroadcast()

            // Consume any pending deep link stashed by NotificationDelegate before
            // this view subscribed to NSNotifications (cold launch race).
            //
            // Single-set without retries: the consumer (MessagesListView /
            // GroupsListView) does synchronous capture-and-clear of the
            // pending id, so re-firing produced a visible chat→list→chat
            // bounce. A 50 ms delay lets SwiftUI mount the tab's children
            // first; after that the .onAppear / .onChange handler catches
            // the value on the very next runloop.
            if let target = PendingNotificationDeepLink.shared.consume() {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                    withTransaction(Transaction(animation: nil)) {
                        selectedTab = 0
                        switch target {
                        case .peer(let key): self.pendingConversationId = key
                        case .group(let gid): self.pendingGroupId = gid
                        case .mail(let mid): self.pendingMailMessageId = mid ?? ""
                        }
                    }
                }
            }

            // Listen for document sharing via OSHI
            NotificationCenter.default.addObserver(
                forName: NSNotification.Name("ShareDocumentViaOSHI"),
                object: nil,
                queue: .main
            ) { notification in
                if let fileURL = notification.userInfo?["fileURL"] as? URL {
                    documentToShare = fileURL
                    showContactPicker = true
                }
            }
        }
        // __ANATOLE_1311_IOS__ (N4) "New contact" in the Messages compose menu
        // lands on the New tab, the one place that scans, shows and pastes a key.
        .onReceive(NotificationCenter.default.publisher(for: MessagesListView.selectNewTabNotification)) { _ in
            selectedTab = 2
        }
        // MARK: - Notification Deep Link: Store pending target + switch tab
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("OpenGroupConversation"))) { notification in
            if let groupId = notification.userInfo?["groupId"] as? String {
                // Single atomic set; consumer captures synchronously. See the
                // OpenChat handler below for the rationale (re-arming
                // pending*Id triggered a visible chat→list→chat bounce).
                withTransaction(Transaction(animation: nil)) {
                    selectedTab = 0
                    self.pendingGroupId = groupId
                }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("OpenGroupChat"))) { notification in
            if let groupId = notification.userInfo?["groupId"] as? String {
                withTransaction(Transaction(animation: nil)) {
                    selectedTab = 0
                    self.pendingGroupId = groupId
                }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("OpenConversation"))) { notification in
            if let conversationId = notification.userInfo?["conversationId"] as? String {
                withTransaction(Transaction(animation: nil)) {
                    selectedTab = 0
                    self.pendingConversationId = conversationId
                }
            }
        }
        // 📧 New-email tap: land on the Chats tab (which hosts the Email segment) and
        // hand the mailbox id to ChatsTabView → MailView. Empty string when the push
        // carried no id, so the tap still opens the Inbox.
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("OpenMailMessage"))) { notification in
            let mailId = notification.userInfo?["mailId"] as? String
            withTransaction(Transaction(animation: nil)) {
                selectedTab = 0
                self.pendingMailMessageId = mailId ?? ""
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("OpenChat"))) { notification in
            if let peerKey = notification.userInfo?["peerPublicKey"] as? String {
                // Match Android's single-transaction nav: skip the tab-switch
                // animation so QR-scan → chat is one frame, no visible
                // "Messages list flash". Both state changes flushed in the
                // same no-animation transaction so SwiftUI commits them
                // together — `pendingConversationId` is then captured by
                // MessagesListView's .onAppear / .onChange (whichever fires
                // first, both have synchronous capture-and-clear).
                //
                // The previous code re-armed pendingConversationId at 0.4s
                // and 1.0s "as a safety net" — but that re-fired
                // navigateToConversation AFTER the chat had already pushed,
                // causing a visible bounce: chat opens → list flashes → chat
                // re-pushes. The single-set is enough now that the consumer
                // captures synchronously.
                withTransaction(Transaction(animation: nil)) {
                    selectedTab = 0
                    self.pendingConversationId = peerKey
                }
            }
        }
        .sheet(isPresented: $showContactPicker) {
            if let documentURL = documentToShare {
                NavigationView {
                    ContactPickerForDocument(documentURL: documentURL)
                }
            }
        }
        .onChange(of: colorScheme) { _, _ in
            configureTabBarAppearance()
        }
        .alert("update_available_title".localized, isPresented: $updateManager.updateAvailable) {
            Button("update_now".localized) {
                updateManager.openAppStore()
            }
            Button("update_later".localized, role: .cancel) {
                updateManager.dismissUpdate()
            }
        } message: {
            Text(String(format: "update_available_message".localized, updateManager.latestVersion ?? ""))
        }
    }
    
    // MARK: - Tab Bar Appearance

    /// Builds the opaque tab-bar appearance with brand-colored selected items and
    /// secondaryLabel idle items.
    ///
    /// 🔧 ITEM 5 FIX (2026-06-25): The "Map" tab label rendered white/invisible
    /// when the full-bleed MapKit map (OfflineMapView in MapTabView) scrolled
    /// under the bar. Root causes, all addressed here:
    ///   1. Only `stackedLayoutAppearance` was configured. On iOS 18 a tab bar can
    ///      fall back to the inline / compact-inline layouts (e.g. certain size
    ///      classes / large-content states), whose UNCONFIGURED title attributes
    ///      defaulted to a near-invisible color over the map. We now set the title
    ///      + icon colors on ALL THREE layout appearances.
    ///   2. The appearance was applied ONLY to the shared `UITabBar.appearance()`
    ///      proxy, which does not retroactively restyle an ALREADY-BUILT bar. We
    ///      now also push the appearance onto the live `UITabBar` instance so the
    ///      colors stick regardless of when this runs relative to bar creation.
    ///   3. `configureWithOpaqueBackground()` is kept so the bar never goes
    ///      translucent over scrolling map content.
    private func makeTabBarAppearance() -> UITabBarAppearance {
        let appearance = UITabBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = UIColor.systemBackground

        // Apply identical normal/selected colors to every layout the bar may use
        // (stacked = iPhone portrait, inline / compactInline = regular width &
        // landscape). Leaving any of these unset is what let the Map title render
        // invisible over the full-bleed map.
        for item in [appearance.stackedLayoutAppearance,
                     appearance.inlineLayoutAppearance,
                     appearance.compactInlineLayoutAppearance] {
            item.normal.iconColor = UIColor.secondaryLabel
            item.normal.titleTextAttributes = [.foregroundColor: UIColor.secondaryLabel]
            item.selected.iconColor = appBrandColor
            item.selected.titleTextAttributes = [.foregroundColor: appBrandColor]
        }

        return appearance
    }

    private func configureTabBarAppearance() {
        let appearance = makeTabBarAppearance()

        // 1. Proxy — styles tab bars created from now on.
        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance

        // 2. Live bar — restyle the bar that's already on screen. The proxy alone
        //    doesn't reach an existing instance, which is the iOS 18 interaction
        //    that left the Map label uncolored. Defer to the next runloop so the
        //    tab bar controller is guaranteed to exist on first .onAppear.
        DispatchQueue.main.async {
            for bar in Self.liveTabBars() {
                bar.standardAppearance = appearance
                bar.scrollEdgeAppearance = appearance
                bar.setNeedsLayout()
            }
        }
    }

    /// Walks the active window scenes to find every live `UITabBar` so the freshly
    /// built appearance can be applied to the instance, not just the proxy.
    private static func liveTabBars() -> [UITabBar] {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .compactMap { $0.rootViewController }
            .flatMap { tabBars(in: $0) }
    }

    private static func tabBars(in vc: UIViewController) -> [UITabBar] {
        var found: [UITabBar] = []
        if let tab = vc as? UITabBarController {
            found.append(tab.tabBar)
        }
        if let presented = vc.presentedViewController {
            found.append(contentsOf: tabBars(in: presented))
        }
        for child in vc.children {
            found.append(contentsOf: tabBars(in: child))
        }
        return found
    }
    
    // MARK: - Badge Counts
    
    private var totalUnreadCount: Int {
        messageManager.conversations.reduce(0) { $0 + $1.unreadCount }
    }
    
    private var totalGroupUnreadCount: Int {
        messageGroupManager.getTotalUnreadCount()
    }
    
    /// __NEARBY_LISTED_EVERY_PHONE_TWICE_2026_11_02__ Same shared census as the
    /// Nearby screen and the top-bar badge: distinct phones, counted once across
    /// both transports, discovered as well as connected.
    private var nearbyPeersCount: Int {
        NearbyPeerCensus.nearbyCount(mesh: meshManager, crossPlatform: CrossPlatformMesh.shared)
    }
}

// MARK: - Contact Picker for Document Sharing

struct ContactPickerForDocument: View {
    let documentURL: URL
    @Environment(\.dismiss) var dismiss
    @EnvironmentObject var messageManager: MessageManager
    @EnvironmentObject var meshManager: MeshNetworkManager
    
    @AppStorage("contactAliases") private var contactAliasesData: Data = Data()
    
    private let brandColor = Theme.brand
    
    private var contactAliases: [String: String] {
        guard !contactAliasesData.isEmpty else { return [:] }
        return (try? JSONDecoder().decode([String: String].self, from: contactAliasesData)) ?? [:]
    }

    var body: some View {
        let sortedConversations = messageManager.conversations.sorted(by: { getAlias($0) < getAlias($1) })
        
        List {
            if sortedConversations.isEmpty {
                Text("contact_selector.no_contacts".localized)
                    .foregroundColor(.secondary)
                    .padding()
            } else {
                ForEach(sortedConversations) { conversation in
                    Button {
                        shareDocument(to: conversation)
                    } label: {
                        HStack(spacing: 12) {
                            // __ONE_AVATAR_NOT_THREE_2026_11_07__ the document
                            // share picker drew a flat brand circle, so every
                            // row looked identical while the conversation list
                            // behind it was fourteen colours.
                            DefaultAvatar(key: conversation.participantPublicKey,
                                          name: getAlias(conversation), size: 44)
                            
                            VStack(alignment: .leading, spacing: 2) {
                                Text(getAlias(conversation))
                                    .font(.headline)
                                    .foregroundColor(.primary)
                                
                                Text(conversation.participantPublicKey.prefix(16) + "...")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            
                            Spacer()
                            
                            Image(systemName: "chevron.right")
                                .foregroundColor(.secondary)
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
        }
        .navigationTitle("document.share_title".localized)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("common.cancel".localized) {
                    dismiss()
                }
            }
        }
    }
    
    private func getAlias(_ conversation: Conversation) -> String {
        // __SHARED_NICKNAME_2026_09_22__ alias local > pseudo partage.
        return SharedNickname.resolve(alias: contactAliases[conversation.participantPublicKey],
                                      nickname: SharedNickname.nickname(for: conversation.participantPublicKey))
            ?? String(conversation.participantPublicKey.prefix(8)) + "..."
    }
    
    private func shareDocument(to conversation: Conversation) {
        // Share document implementation
        dismiss()
    }
}

// MARK: - Preview

// Note: Preview disabled due to singleton dependencies
// Use simulator or device for testing

// MARK: - __CALL_MINIMIZE_2026_08_16__

/// The compact bar that replaces the full-screen call UI once it is minimized.
///
/// It exists so a call and the rest of the app can be used at the same time —
/// reading the address someone just sent you, checking a name, copying a code —
/// without hanging up. Before this, the call view covered everything at
/// `zIndex(1000)` and "end the call" was the only way back to a message.
///
/// Deliberately small in surface: return, mute, hang up. Anything more belongs
/// on the full screen, one tap away.
struct MinimizedCallBar: View {
    @ObservedObject var voiceCallManager: VoiceCallManager

    private var peerName: String {
        let name = voiceCallManager.currentCallPeerName ?? ""
        return name.isEmpty
            ? NSLocalizedString("call.contact.default", value: "Call", comment: "Fallback name for the person on the call")
            : name
    }

    /// Reuses the shared duration formatter — digits only, no localization, and
    /// the same shape the call summary in the thread already uses.
    private var duration: String {
        OSHIControlPayload.formatDuration(voiceCallManager.callDuration)
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "phone.fill")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(.white)

            VStack(alignment: .leading, spacing: 1) {
                Text(peerName)
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(.white)
                    .lineLimit(1)
                Text(duration)
                    .font(.caption2.monospacedDigit())
                    .foregroundColor(.white.opacity(0.85))
            }

            Spacer(minLength: 8)

            Button {
                voiceCallManager.toggleMute()
            } label: {
                Image(systemName: voiceCallManager.isMuted ? "mic.slash.fill" : "mic.fill")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(width: 32, height: 32)
                    .background(Circle().fill(Color.white.opacity(voiceCallManager.isMuted ? 0.32 : 0.16)))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(voiceCallManager.isMuted
                ? NSLocalizedString("call.unmute", value: "Unmute", comment: "Accessibility label on the minimized call bar")
                : NSLocalizedString("call.mute", value: "Mute", comment: "Accessibility label on the minimized call bar")))

            Button {
                voiceCallManager.endCall()
            } label: {
                Image(systemName: "phone.down.fill")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(width: 32, height: 32)
                    .background(Circle().fill(Color.red))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(NSLocalizedString("call.end", value: "End call", comment: "Accessibility label on the minimized call bar")))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .background(
            Capsule().fill(Color.green.opacity(0.92))
                .shadow(color: .black.opacity(0.22), radius: 10, y: 3)
        )
        // 🔴 FIX (2026-08-20) — ORDER, NOT DECORATION. `.contentShape` was applied
        // AFTER `.frame(maxWidth: .infinity, maxHeight: .infinity)`, so the tap
        // target was a capsule the size of the SCREEN, not the size of the bar.
        // This layer sits at zIndex 999 over the whole app: every tap anywhere —
        // a chat row, the tab bar, a text field — hit the bar and reopened the
        // call. The one thing minimizing exists for, using the app during a call,
        // was therefore impossible; it looked like navigation was broken.
        //
        // The gesture now binds to the capsule-shaped bar itself. The frames
        // below only POSITION it, and a frame with no drawn content does not
        // hit-test, so taps outside the bar fall through to the app.
        //
        // The two buttons above still take their own taps first.
        .contentShape(Capsule())
        .onTapGesture {
            withAnimation(.easeInOut(duration: 0.22)) {
                voiceCallManager.isCallMinimized = false
            }
        }
        .padding(.horizontal, 14)
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .accessibilityElement(children: .contain)
    }
}
