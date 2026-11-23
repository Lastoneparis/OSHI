//
//  SecureWeb3MessengerApp.swift
//  ✅ FULLY LOCALIZED: All user-facing strings now use .localized
//  ✅ SHARE EXTENSION: Added SharedContentHandler integration
//

import SwiftUI
import AVFoundation

// MARK: - Brand Color
private let appBrandColor = Theme.brand
import LocalAuthentication
import UserNotifications
import UniformTypeIdentifiers
import MachO  // __DEVICE_INTEGRITY_2026_10_01__ _dyld_image_count

@main
struct SecureWeb3MessengerApp: App {
    @UIApplicationDelegateAdaptor(OshiAppDelegate.self) var appDelegate  // ✅ RENAMED to avoid conflicts
    @StateObject private var identityManager = IdentityManager()
    @StateObject private var meshManager = MeshNetworkManager()
    @StateObject private var messageManager = MessageManager()
    @StateObject private var groupManager = GroupManager.shared  // ✅ FIXED: Use singleton
    @StateObject private var messageGroupManager = MessageGroupManager.shared  // ✅ ADDED: For encrypted messaging
    @StateObject private var notificationManager = NotificationManager.shared
    @StateObject private var sharedContentHandler = SharedContentHandler.shared  // ✅ SHARE EXTENSION
    @Environment(\.scenePhase) private var scenePhase  // ✅ Track app state for share extension
    @State private var isUnlocked = false
    @State private var isInitializing = true
    // __LAUNCH_MOTION_2026_06_21__ branded video splash on cold launch.
    //
    // 🔧 FIX (2026-08-10, "the message should appear almost immediately when I tap
    // a notification"): this was unconditionally `true`. It renders full-screen and
    // opaque at zIndex(1000) for ~5.1 s, skippable only by tapping. So a
    // notification tap that COLD-LAUNCHES the app played five seconds of logo
    // animation before the conversation was visible at all — by far the largest
    // single delay on that path, and pure decoration.
    //
    // Navigation and the pull proceed underneath, so the message is usually
    // already there when the video ends. The user just could not see it.
    //
    // Someone arriving from a notification has asked for one specific thing. Show
    // them that, not the logo. A normal cold launch is unchanged.
    @State private var showLaunchMotion = !OshiAppDelegate.launchedFromNotification
        && PendingNotificationDeepLink.shared.target == nil
    @State private var showScreenshotWarning = false  // ✅ Screenshot warning
    @State private var showOnboarding = false  // ✅ Onboarding
    @AppStorage("biometricEnabled") private var biometricEnabled = false
    @AppStorage("autoLockEnabled") private var autoLockEnabled = false
    @AppStorage("hasSeenOnboarding") private var hasSeenOnboarding = false
    @AppStorage("appTheme") private var appTheme = "system"
    /// __OLED_BLACK_2026_11_16__ « Noir OLED »: true black whenever the app is dark.
    @AppStorage(OLEDBlack.storageKey) private var oledBlack = OLEDBlack.defaultEnabled
    /// __GLASS_ALL_THEMES_2026_11_21__ « Effet verre »: glass surfaces in every theme.
    @AppStorage(GlassEffect.storageKey) private var glassEffect = GlassEffect.defaultEnabled
    @AppStorage(GlassEffect.oledStorageKey) private var glassEffectOLED = GlassEffect.oledDefaultEnabled

    init() {
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ Avant toute requête et tout écran:
        // URLCache mémoire seulement + balayage des copies en clair d'un lancement
        // précédent (tmp, enregistrements vocaux, partages non consommés).
        PlaintextLeftovers.applyAtLaunch()
        // __MERGED_CHATS_2026_11_18__ ON for a fresh install, OFF for anyone
        // who already used the app — decided once, before the first screen.
        MergedChatsSetting.settleDefaultAtLaunch()
    }

    /// __STORE_UPDATE_PROMPT_2026_11_21__ The main UI is what is on screen: not
    /// the splash, not the launch video, not onboarding, not the biometric lock.
    private var storeUpdatePromptAllowed: Bool {
        !isInitializing && !showLaunchMotion && hasSeenOnboarding && !showOnboarding
            && (!biometricEnabled || isUnlocked)
    }

    /// Convert stored theme string to ColorScheme
    private var preferredColorScheme: ColorScheme? {
        switch appTheme {
        case "light": return .light
        case "dark": return .dark
        default: return nil  // "system" uses device setting
        }
    }

    var body: some Scene {
        WindowGroup {
            // __FORCED_UPDATE_2026_08_29__ Avant TOUT le reste, y compris le
            // deverrouillage biometrique: une build declaree trop ancienne ne
            // doit rien faire d'autre que renvoyer vers l'App Store.
            //
            // Le test echoue OUVERT — sans config recuperee il rend false et
            // rien ne change. Une panne reseau ne murera jamais une app qui
            // marche.
            //
            // Ecrit en enveloppe AUTOUR du ZStack, et pas comme une branche de
            // plus dans sa chaine de `if`: ajoute a l'interieur, le corps
            // devenait trop complexe pour l'inference de SwiftUI et le
            // compilateur abandonnait (« unable to type-check this expression
            // in reasonable time »). Une enveloppe n'ajoute qu'un niveau.
            if V2FeatureFlag.updateRequired || MailFixtureHarness.isActive {
                #if DEBUG
                if MailFixtureHarness.isActive { MailFixtureHarnessView().environment(\.oledBlack, OLEDBlack.isEnabled) } else { UpdateRequiredView() }
                #else
                UpdateRequiredView()
                #endif
            } else {
              ZStack {
                if isInitializing {
                    // Show splash screen while initializing
                    LaunchScreenView()
                } else if !biometricEnabled {
                    // Biometric disabled
                    ContentView()
                        .environmentObject(identityManager)
                        .environmentObject(meshManager)
                        .environmentObject(messageManager)
                        .environmentObject(groupManager)
                        .environmentObject(messageGroupManager)
                        .environmentObject(notificationManager)
                } else if isUnlocked {
                    // Biometric enabled and unlocked
                    ContentView()
                        .environmentObject(identityManager)
                        .environmentObject(meshManager)
                        .environmentObject(messageManager)
                        .environmentObject(groupManager)
                        .environmentObject(messageGroupManager)
                        .environmentObject(notificationManager)
                } else {
                    // Biometric enabled but not unlocked
                    BiometricLockView(isUnlocked: $isUnlocked)
                }

                // __LAUNCH_MOTION_2026_06_21__ Branded OSHI logo-reveal video on top of
                // everything at cold launch; fades out when it finishes (or on tap).
                //
                // __O_BUBBLE_MASCOT_2026_11_16__ Now the ~1.15 s O-bubble hello
                // (`MascotLaunchView`) instead of the 5.1 s video: it starts
                // from the system launch screen's own image, never takes
                // touches and never outlasts readiness by more than its fade.
                if showLaunchMotion {
                    MascotLaunchView(isAppReady: !isInitializing) {
                        showLaunchMotion = false
                    }
                    .ignoresSafeArea()
                    .zIndex(1000)
                }
            }
            // In-app notification that emerges from / retracts into the Dynamic
            // Island. Above everything, ignores the keyboard, non-blocking.
            .overlay(alignment: .top) {
                DynamicIslandNotificationView()
                    .ignoresSafeArea(.keyboard)
                    .zIndex(2000)
            }
            // __VOICE_MINIPLAYER_2026_10_09__ A playing voice note keeps playing
            // when the user leaves the conversation, and this compact player
            // follows them so they can pause, replay, change speed or close it
            // from anywhere. Below the island banner, above the app content,
            // and it only takes touches while it is actually on screen.
            .overlay(alignment: .bottom) {
                VoiceNoteMiniPlayerView()
                    .ignoresSafeArea(.keyboard)
                    .zIndex(1500)
            }
            // ── Mac (Catalyst) window behaviour ──────────────────────────────
            // `NavigationView` is deprecated and, on a wide window, defaults to
            // the two-column split style. There are 60 of them across 33 files,
            // and on the Mac every single one rendered the app inside a ~660 pt
            // sidebar with the rest of the window empty and black — measured on
            // the first Catalyst launch, on the onboarding screen.
            //
            // `navigationViewStyle` is read from the environment by every
            // NavigationView in the subtree, so ONE modifier at the scene root
            // fixes all of them, including the ones inside sheets. Scoped to
            // Catalyst deliberately: iPad's shipping layout is a separate
            // decision and this is not the change that should make it silently.
            .modifier(MacWindowChrome())
            .secureScreen()  // ✅ WhatsApp-style screenshot protection
            .rateAppPrompt()  // ⭐️ Apple's own review request, delivered when the screen is free
            // __CALL_RATING_2026_09_22__ "How was the call quality?" after a
            // finished call. Sits next to the App Store ask on purpose — the two
            // are triggered by the same moment and coordinate through
            // `RateSignals.isUIBusy` so only one of them is ever on screen.
            .callRatingPrompt()
            // __MIN_VERSION_PROMPT_2026_09_09__ Rappel de mise a jour, doux.
            //
            // A NE PAS CONFONDRE avec `V2FeatureFlag.updateRequired` juste
            // au-dessus, qui remplace toute l'interface: celui-ci est une
            // banniere refermable posee PAR-DESSUS l'app qui continue de
            // marcher. Il n'existe aucun etat de `MinimumVersionGate` qui
            // empeche d'ecrire, d'envoyer ou de lire, ni qui touche a la maille.
            // Serveur muet ou injoignable => rien ne s'affiche.
            //
            // Pose ici, en modificateur du ZStack et pas en branche de son `if`,
            // pour la meme raison que l'enveloppe plus haut: le corps de cette
            // scene est deja a la limite de l'inference de type de SwiftUI.
            .updateAvailablePrompt()
            // __STORE_UPDATE_PROMPT_2026_11_21__ « Update available »: the App
            // Store has a newer version than this build (StoreUpdateCheck.swift).
            // Informative only, dismissible, snoozable; never over the splash,
            // onboarding, lock screen, a call or another sheet.
            .storeUpdatePrompt(allowed: storeUpdatePromptAllowed)
            .fullScreenCover(isPresented: $showOnboarding) {
                OnboardingView(isPresented: $showOnboarding)
            }
            .onChange(of: showOnboarding) { _, isShowing in
            }
            // 📨 __NO_SHEET_WHEN_THE_CHOICE_IS_MADE_2026_08_18__
            //
            // The recipient was already chosen inside the share sheet, so there is
            // nothing to ask and nothing to present. Two earlier attempts still
            // presented `SharePickerView` and only changed what it CONTAINED —
            // which is why the panel kept flashing for about a second: presenting a
            // sheet at all was the problem, not its contents.
            //
            // The send runs here, at scene level, with no view of its own. The user
            // sees the app come forward, a toast, and nothing else.
            .onChange(of: sharedContentHandler.pendingAutoSendRecipient) { _, recipient in
                guard let recipient else { return }
                sharedContentHandler.pendingAutoSendRecipient = nil
                let refusal = sharedContentHandler.performAutoSend(
                    to: recipient,
                    messageManager: messageManager,
                    identityManager: identityManager,
                    meshManager: meshManager)
                // Say something either way. A share that silently does nothing is
                // indistinguishable from one that worked.
                sharedContentHandler.autoSendToast =
                    refusal?.userFacingMessage ?? "share.sent".localized
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.2) {
                    sharedContentHandler.autoSendToast = nil
                }
            }
            .overlay(alignment: .top) {
                if let toast = sharedContentHandler.autoSendToast {
                    Text(toast)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 11)
                        .background(Capsule().fill(Theme.brand))
                        .shadow(color: .black.opacity(0.18), radius: 12, y: 4)
                        .padding(.top, 8)
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .allowsHitTesting(false)
                }
            }
            .animation(.spring(response: 0.35, dampingFraction: 0.85),
                       value: sharedContentHandler.autoSendToast)
            // ✅ SHARE EXTENSION: Handle shared content picker
            .sheet(isPresented: $sharedContentHandler.showSharePicker) {
                SharePickerView(sharedContentHandler: sharedContentHandler)
                    .environmentObject(messageManager)
                    .environmentObject(identityManager)
                    .environmentObject(meshManager)
            }
            // ✅ DEEP LINK: Handle add contact from link
            .sheet(isPresented: $sharedContentHandler.showAddContact) {
                AddContactFromLinkView(sharedContentHandler: sharedContentHandler)
                    .environmentObject(messageManager)
                    .environmentObject(identityManager)
            }
            // ✅ DEEP LINK: Handle group join from link
            .sheet(isPresented: $sharedContentHandler.showJoinGroup) {
                if let groupId = sharedContentHandler.pendingGroupId,
                   let uuid = UUID(uuidString: groupId),
                   let group = MessageGroupManager.shared.groups.first(where: { $0.id == uuid }) {
                    NavigationView {
                        GroupChatView(group: group)
                            .environmentObject(MessageGroupManager.shared)
                            .environmentObject(identityManager)
                    }
                } else if let groupId = sharedContentHandler.pendingGroupId,
                          let uuid = UUID(uuidString: groupId) {
                    // 🆕 Group not local yet — offer to join via invite
                    NavigationView {
                        VStack(spacing: 20) {
                            Image(systemName: "person.3.sequence.fill")
                                .font(.scaled(56))
                                .foregroundStyle(Theme.brand)
                                .padding(.top, 32)

                            Text("group_invite_title".localized)
                                .font(.title2.bold())

                            if let name = sharedContentHandler.pendingGroupName, !name.isEmpty {
                                Text(name)
                                    .font(.title3)
                                    .foregroundColor(.primary)
                            }
                            Text(String(format: "group_invite_id_format".localized, uuid.uuidString))
                                .font(.system(.caption, design: .monospaced))
                                .foregroundColor(.secondary)
                                .padding(.horizontal)
                                .multilineTextAlignment(.center)
                                .lineLimit(2)

                            if let inviter = sharedContentHandler.pendingGroupInviter, !inviter.isEmpty {
                                Text(String(format: "group_invite_from".localized,
                                            String(inviter.prefix(12)) + "…"))
                                    .font(.subheadline)
                                    .foregroundColor(.secondary)
                            }

                            Spacer()

                            Button {
                                MessageGroupManager.shared.joinGroupFromInvite(
                                    gid: uuid,
                                    inviterKey: sharedContentHandler.pendingGroupInviter,
                                    name: sharedContentHandler.pendingGroupName,
                                    inviteToken: sharedContentHandler.pendingGroupInviteToken
                                )
                                sharedContentHandler.pendingGroupInviteToken = nil
                                sharedContentHandler.showJoinGroup = false
                                sharedContentHandler.pendingGroupId = nil
                                sharedContentHandler.pendingGroupName = nil
                                sharedContentHandler.pendingGroupInviter = nil
                            } label: {
                                Text("group_invite_join_button".localized)
                                    .font(.headline)
                                    .foregroundColor(.white)
                                    .frame(maxWidth: .infinity)
                                    .padding()
                                    .background(Theme.brand)
                                    .cornerRadius(14)
                            }
                            .padding(.horizontal)

                            Button("cancel".localized) {
                                sharedContentHandler.showJoinGroup = false
                                sharedContentHandler.pendingGroupId = nil
                                sharedContentHandler.pendingGroupName = nil
                                sharedContentHandler.pendingGroupInviter = nil
                            }
                            .padding(.bottom, 24)
                        }
                        .navigationTitle("OSHI")
                        .navigationBarTitleDisplayMode(.inline)
                    }
                } else {
                    // Invalid invite link
                    NavigationView {
                        VStack(spacing: 16) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .font(.scaled(48))
                                .foregroundColor(.orange)
                            Text("group_invite_invalid_title".localized)
                                .font(.headline)
                            Text("group_invite_invalid_message".localized)
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal)
                            Button("button_ok".localized) {
                                sharedContentHandler.showJoinGroup = false
                                sharedContentHandler.pendingGroupId = nil
                                sharedContentHandler.pendingGroupName = nil
                                sharedContentHandler.pendingGroupInviter = nil
                            }
                            .buttonStyle(.borderedProminent)
                        }
                        .navigationTitle("OSHI")
                        .navigationBarTitleDisplayMode(.inline)
                    }
                }
            }
            // __GROUP_INFO_SETTINGS_2026_11_20__ §3.1 public group link: confirm first.
            .sheet(isPresented: $sharedContentHandler.showPublicJoin) {
                PublicGroupJoinSheet(rawHandle: sharedContentHandler.pendingPublicHandle ?? "")
            }
            // ✅ SHARE EXTENSION: Handle oshi:// URL scheme
            .onOpenURL { url in
                OshiLog.app.info("📥 Received URL: \(url)")
                sharedContentHandler.handleURL(url)
            }
            // __APPLE_WATCH_2027__ The watch bridge refuses everything while
            // OSHI's own lock is engaged; it reads that through `AppLockState`,
            // which only this App can feed.
            .onChange(of: isUnlocked) { _, unlocked in
                AppLockState.setUnlocked(unlocked)
            }
            .onChange(of: biometricEnabled) { _, _ in
                WatchBridgeService.requestSnapshotPush()
            }
            .onChange(of: scenePhase) { _, newPhase in
                if newPhase == .active {
                    // 🔧 FIX (2026-08-06, badge stuck at 50): every notification
                    // increments the STORED `appBadgeCount` — a running tally
                    // that only ever grows. `syncAppBadge()` is the only thing
                    // that recomputes it from the real unread counts, and it was
                    // called from `ContentView.onAppear`, which fires ONCE at
                    // launch. Reopening a backgrounded app never reconciled, so
                    // 50 notifications left 50 on the icon no matter how much
                    // had been read. NotificationManager's own comment already
                    // promised "the next foreground reconciles" — this is the
                    // foreground hook that was missing.
                    //
                    // Also runs AFTER the poll burst below, so anything pulled on
                    // resume is counted rather than being missed by a sync that
                    // ran too early.
                    MessageManager.shared?.syncAppBadge()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                        MessageManager.shared?.syncAppBadge()
                    }

                    // 🔧 FIX (1.0.10 v6): Tighter burst on foreground. Intervals chosen to
                    // respect the isPollingInProgress guard — typical poll completes in
                    // 300–600 ms (cache hit + VPS round-trip). 0 / 300 / 800 / 1500 ms
                    // gives 4 attempts that each have a real chance of firing after the
                    // previous one finishes, instead of being skipped by the guard.
                    MessageManager.shared?.checkPendingIPFSMessages()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                        MessageManager.shared?.checkPendingIPFSMessages()
                    }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
                        MessageManager.shared?.checkPendingIPFSMessages()
                    }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                        MessageManager.shared?.checkPendingIPFSMessages()
                    }

                    // Check for shared content when app becomes active
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        sharedContentHandler.checkForPendingContent()
                    }

                    // 📱 MULTI-DEVICE: Sync on app foreground
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                        MultiDeviceSyncManager.shared.performFullSync()
                    }

                    // __DEVSYNC_DIRECT_2026_09_23__ Direct device sync runs only in the
                    // foreground (owner decision D12). No-op while the flag is off.
                    DevSyncManager.shared.appDidBecomeActive()

                    // 🕵️ Resume active covert polling
                    CovertChannelReceiver.shared.setActivePolling(true)
                } else if newPhase == .background || newPhase == .inactive {
                    if newPhase == .background { DevSyncManager.shared.appDidEnterBackground() }  // __DEVSYNC_DIRECT_2026_09_23__
                    // 🔐 AUTO-LOCK: Re-lock app when going to background
                    if biometricEnabled && autoLockEnabled {
                        isUnlocked = false
                        OshiLog.app.info("🔐 App locked (auto-lock enabled)")
                    }

                    // 🕵️ Slow down covert polling in background
                    CovertChannelReceiver.shared.setActivePolling(false)
                }
            }
            .onAppear {
                if MailFixtureHarness.isActive { return }  // DEBUG mail fixtures: no app start-up
                LaunchTimingLogger.mark("App.WindowGroup.onAppear")
                DevSyncManager.shared.appDidBecomeActive()  // __DEVSYNC_DIRECT_2026_09_23__ first .active never reaches onChange(of: scenePhase)
                #if DEBUG
                // __AUDIOSESSION_TRACER_2026_08_27__ AVANT `initializeApp()`, et
                // c'est tout l'intérêt: ce qui coupe la musique au lancement se
                // trouve quelque part DANS cette initialisation, et un traceur
                // armé après elle ne verrait rien.
                AudioSessionTracer.start()
                #endif
                Self.adoptMixingAudioSessionAtLaunch()
                OshiLog.app.info("🎬 App initialization starting...")
                initializeApp()

                // 💳 Arm the StoreKit transaction listener at launch, before any
                // paywall can be opened. Renewals, refunds, Ask-to-Buy approvals
                // and purchases made on another device all arrive through
                // Transaction.updates rather than through the call that started
                // them — and a purchase whose server grant failed mid-flight is
                // redelivered here. Starting this only when the paywall opens
                // would mean a customer who paid, lost signal, and never
                // reopened the sheet stays charged and un-upgraded.
                PremiumManager.shared.start(mail: OSHIMailClient.shared)
                #if DEBUG
                AudioSessionTracer.log("afterInitializeApp")
                #endif

                // 🔒 Arm the linked-device watcher on the MAIN runloop at launch.
                // WebSessionManager is a lazy singleton whose 60s fetchActiveSessions timer is
                // scheduled (in init) on whatever runloop first touches `.shared`. If a
                // background thread initialises it first (e.g. notifyNewMessage off the message
                // path), the timer lands on a runloop that never runs and the linked-device
                // security alert silently never fires — the covert companion-device defence
                // depends on this timer. Forcing init here, on main, guarantees the watcher is
                // always armed. Cheap and idempotent: fetchActiveSessions no-ops until an
                // identity exists, and re-touching an initialised singleton does nothing.
                _ = WebSessionManager.shared

                #if DEBUG
                // 📸 App Store screenshot capture: `-OSHIScreens` force-skips the
                // launch video + onboarding cover and seeds the existing demo
                // content so localized screenshots can be captured headlessly.
                // Release-stripped (#if DEBUG) → zero production effect.
                if ProcessInfo.processInfo.arguments.contains("-OSHIScreens") {
                    // Screenshot capture: skip launch video + onboarding, and force an
                    // identity so the main UI (tabs) shows. Demo MESSAGES are then
                    // seeded from MainTabView.onAppear (UI up → managers ready).
                    hasSeenOnboarding = true
                    showOnboarding = false
                    showLaunchMotion = false
                    isInitializing = false
                    if !identityManager.isConnected { identityManager.connectWallet() }
                }

                // TEST-ONLY call driver for the two-simulator call matrix. Places
                // or answers a call directly via the same public API the UI uses,
                // bypassing the fragile ChatView navigation so the audio-path
                // verify loop is deterministic. Release-stripped (#if DEBUG).
                //   -OSHIAutoCall <peerPublicKey> : place a voice call once identity is up
                //   -OSHIDialAt <epochSeconds>    : (with AutoCall) wait to this wall
                //                                   clock before dialing — for glare
                //   -OSHIAutoAnswer               : auto-accept the first incoming call
                let dbgArgs = ProcessInfo.processInfo.arguments
                if let ci = dbgArgs.firstIndex(of: "-OSHIAutoCall"), ci + 1 < dbgArgs.count {
                    let peerKey = dbgArgs[ci + 1]
                    Task { @MainActor in
                        for _ in 0..<120 where !identityManager.isConnected {
                            try? await Task.sleep(nanoseconds: 500_000_000)
                        }
                        if let bi = dbgArgs.firstIndex(of: "-OSHIDialAt"), bi + 1 < dbgArgs.count,
                           let epoch = Double(dbgArgs[bi + 1]) {
                            while Date().timeIntervalSince1970 < epoch {
                                try? await Task.sleep(nanoseconds: 50_000_000)
                            }
                        } else {
                            try? await Task.sleep(nanoseconds: 3_000_000_000)
                        }
                        NSLog("CALLMATRIX autodrive placing call to \(peerKey.prefix(12))")
                        try? await VoiceCallManager.shared.startCall(to: peerKey, peerName: "AutoPeer")
                    }
                }
                // __AUTOIDENTITY_TEST_HOOK_2026_08_15__ Create an identity without
                // the onboarding UI, so a two-simulator message test can be driven
                // from a script instead of by tapping through first-run screens.
                // `connectWallet()` with no seed is the same call onboarding makes.
                if dbgArgs.contains("-OSHIAutoIdentity") && identityManager.publicKey.isEmpty {
                    hasSeenOnboarding = true
                    identityManager.connectWallet()
                    NSLog("MSGMATRIX autodrive creating identity")
                }

                // __AUTOSEND_TEST_HOOK_2026_08_15__ The message counterpart of
                // -OSHIAutoCall, and it exists for one reason: on a simulator there
                // is no mesh, so a send here exercises the INTERNET path on its own.
                // That is precisely the path we cannot observe on two phones sitting
                // next to each other, where the mesh always wins the race.
                //
                //   -OSHIAutoSendTo <peerPublicKey> [-OSHIAutoSendText <text>]
                //
                // DEBUG-only, like the call hooks above: it changes no shipping
                // behaviour and is compiled out of every release build.
                if let si = dbgArgs.firstIndex(of: "-OSHIAutoSendTo"), si + 1 < dbgArgs.count {
                    let peerKey = dbgArgs[si + 1]
                    let text: String = {
                        if let ti = dbgArgs.firstIndex(of: "-OSHIAutoSendText"), ti + 1 < dbgArgs.count {
                            return dbgArgs[ti + 1]
                        }
                        return "autotest"
                    }()
                    Task { @MainActor in
                        // Same readiness ladder as the call hook: wait for the
                        // identity, then let the first poll/registration settle so
                        // the send is not racing app launch.
                        for _ in 0..<120 where !identityManager.isConnected {
                            try? await Task.sleep(nanoseconds: 500_000_000)
                        }
                        try? await Task.sleep(nanoseconds: 4_000_000_000)
                        let outcome = messageManager.sendMessage(
                            content: text,
                            recipientAddress: peerKey,
                            identityManager: identityManager,
                            meshManager: meshManager)
                        NSLog("MSGMATRIX autodrive sent didSend=\(outcome.didSend) to \(peerKey.prefix(12)) len=\(text.count)")
                        CallFileLogger.shared.log("MSGMATRIX_SEND | didSend=\(outcome.didSend) | to=\(peerKey.prefix(12)) | len=\(text.count)")
                    }
                }
                if dbgArgs.contains("-OSHIAutoAnswer") {
                    Task { @MainActor in
                        for _ in 0..<1200 {
                            if VoiceCallManager.shared.incomingCall != nil,
                               VoiceCallManager.shared.callState == .ringing {
                                try? await Task.sleep(nanoseconds: 1_200_000_000)
                                NSLog("CALLMATRIX autodrive answering incoming call")
                                try? await VoiceCallManager.shared.acceptCall()
                                break
                            }
                            try? await Task.sleep(nanoseconds: 300_000_000)
                        }
                    }
                }
                #endif

                // Show onboarding if first time
                if !hasSeenOnboarding {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                        showOnboarding = true
                    }
                }
                
                // ✅ SHARE EXTENSION: Check for pending shared content on app launch
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                    sharedContentHandler.checkForPendingContent()
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("ScreenshotTaken"))) { _ in
                showScreenshotWarning = true
            }
            .alert("screenshot_alert_title".localized, isPresented: $showScreenshotWarning) {
                Button("button_ok".localized) {
                    showScreenshotWarning = false
                }
            } message: {
                Text("screenshot_alert_message".localized)
            }
            // __DEVICE_INTEGRITY_2026_10_01__ jailbreak signs → one-time warning
            .deviceIntegrityLaunchAlert()
            .preferredColorScheme(preferredColorScheme)
            // __OLED_BLACK_2026_11_16__ The choice goes into the SwiftUI
            // environment here AND onto the UIKit windows (trait + base level),
            // so sheets, covers and UIKit-drawn surfaces follow it live.
            .environment(\.oledBlack, oledBlack)
            .environment(\.glassEffect, glassEffect)
            .environment(\.glassEffectOLED, glassEffectOLED)
            .onAppear {
                OLEDBlackWindows.installPresentationHook()
                OLEDBlackWindows.apply(enabled: oledBlack)
                GlassEffectWindows.apply(enabled: glassEffect, oledEnabled: glassEffectOLED)
            }
            .onChange(of: oledBlack) { _, enabled in
                OLEDBlackWindows.apply(enabled: enabled)
            }
            .onChange(of: glassEffect) { _, enabled in
                GlassEffectWindows.apply(enabled: enabled, oledEnabled: glassEffectOLED)
            }
            .onChange(of: glassEffectOLED) { _, enabled in
                GlassEffectWindows.apply(enabled: glassEffect, oledEnabled: enabled)
            }
            // 🎨 Brand tint at the ROOT, not just on the TabView.
            //
            // The AccentColor asset is pure white in BOTH appearances. Inside
            // the TabView that never showed, because tabViewBody sets
            // .tint(Theme.brand) itself — but a sheet or fullScreenCover is
            // presented outside that hierarchy and falls back to the asset. In
            // light mode (the default, since appTheme is "system") every plain
            // Button label, Link and Toggle in a modal renders white on white:
            // present in the view tree, hittable by a UI test, invisible to a
            // human. The Premium paywall shipped that way and lost both its
            // Restore control and its EULA link, which are Apple 3.1.2
            // requirements.
            //
            // Setting it here matches what the TabView already does, so the
            // main UI is unchanged and modals stop being the odd ones out.
            .tint(Theme.brand)
            #if targetEnvironment(macCatalyst)
            // ⇧⌘R from the menu bar opens Reveal from anywhere.
            .modifier(PrivacyDisguiseRevealHost())
            #endif
        }
            }   // fin de l'enveloppe __FORCED_UPDATE_2026_08_29__
        #if targetEnvironment(macCatalyst)
        .commands { PrivacyDisguiseCommands() }
        #endif
    }
    
    /// __MIXING_BY_DEFAULT_2026_08_27__ Ne jamais rester sur la catégorie audio
    /// que le système donne par défaut.
    ///
    /// SIGNALÉ: « je tue OSHI, j'ouvre Spotify, j'écoute de la musique; si je
    /// rouvre OSHI la musique se met en pause. Si l'app n'est PAS tuée, ça ne
    /// coupe pas. »
    ///
    /// MESURÉ au démarrage à froid, par `AudioSessionTracer`:
    ///
    ///     14:39:12.011  launch              cat=SoloAmbient  otherAudio=true
    ///     14:39:12.026  afterInitializeApp  cat=SoloAmbient  otherAudio=true
    ///     14:39:16.852  interruption        cat=SoloAmbient  otherAudio=FALSE
    ///
    /// `AVAudioSessionCategorySoloAmbient` est le DÉFAUT d'iOS, et l'app ne l'a
    /// jamais changé. Son nom le dit: SOLO. Elle est non-mixante par
    /// définition — activer une session posée dessus fait taire tout le reste.
    /// Il suffit donc que N'IMPORTE QUOI touche la session pendant le démarrage
    /// pour tuer la musique, et il ne reste plus qu'à chercher lequel des vingt
    /// candidats c'était.
    ///
    /// Cette fonction supprime la question. `.ambient` avec `.mixWithOthers` est
    /// la catégorie d'une app qui n'a rien à jouer: elle NE PEUT PAS voler le
    /// focus audio, quoi qu'il arrive ensuite au démarrage.
    ///
    /// POURQUOI C'EST SANS RISQUE: chaque chemin audio délibéré pose DÉJÀ sa
    /// propre catégorie avant de s'en servir — appels (`VoiceCallManager`, 10
    /// sites), messages vocaux, médias, dictée, mesh, cartes. Vérifié un par un.
    /// Aucun ne dépendait du défaut; il n'était qu'un héritage.
    ///
    /// On ne fait PAS `setActive(true)`: poser une catégorie sur une session
    /// inactive ne dérange personne. C'est précisément ce qu'on veut — être
    /// inoffensif tant qu'on n'a rien à dire.
    ///
    /// ⚠️ Et ça explique le « seulement après un kill »: un démarrage à froid
    /// remonte l'arriéré (39 hachages dans le premier sondage VPS de ce
    /// journal), donc une rafale d'activité que le lancement à chaud n'a pas.
    private static func adoptMixingAudioSessionAtLaunch() {
        #if os(iOS)
        do {
            try AVAudioSession.sharedInstance().setCategory(.ambient, mode: .default,
                                                            options: [.mixWithOthers])
            OshiLog.app.info("🔈 Audio session: .ambient + mixWithOthers — other audio is safe")
        } catch {
            OshiLog.app.info("⚠️ Audio session: could not adopt mixing category: \(error.localizedDescription)")
        }
        #endif
        #if DEBUG
        AudioSessionTracer.log("afterAdoptMixing")
        #endif
    }

    private func initializeApp() {
        LaunchTimingLogger.mark("initializeApp.start")
        // Launch cost on the media-bearing stores scales with their on-disk size, so
        // record it once per launch — a freeze report then names the file.
        LaunchTimingLogger.markStoreSizes()
        // Note: Notification delegate is configured in OshiAppDelegate.didFinishLaunchingWithOptions
        OshiLog.app.info("✅ Notification system ready")

        // 🔑 Copy the identity + own key material into the shared Keychain access
        // group so OSHIShare can send without waking the app. Copies, never moves
        // — see `KeychainHelper.migrateSharedItems`.
        //
        // The REPORT is logged, not an "ok". A migration that silently found
        // nothing looks exactly like one that worked, and the difference only
        // shows up later as an extension that can send to nobody.
        //
        // Off the main thread: this is four Keychain round-trips and the first
        // one can block while the device finishes unlocking.
        DispatchQueue.global(qos: .utility).async {
            let report = KeychainHelper.migrateSharedItems()
            if report.failed.isEmpty {
                OshiLog.app.info("🔑 Keychain shared group: \(report.description)")
            } else {
                OshiLog.app.error("🔑 Keychain shared group INCOMPLETE: \(report.description)")
            }

            // 🔧 (2026-08-20) …and the per-peer ratchet sessions, which
            // `migrateSharedItems` cannot reach: it walks a FIXED list of keys and
            // these have one account per contact. Left to the lazy path in
            // `loadSession`, the first share to each contact after an upgrade
            // still fell back to opening the app. See
            // `V2SessionStore.migrateAllPeerSessionsToSharedGroup`.
            let sessions = V2SessionStore().migrateAllPeerSessionsToSharedGroup()
            if sessions.failed == 0 {
                OshiLog.app.info("🔑 Peer sessions shared: migrated=\(sessions.migrated) alreadyShared=\(sessions.alreadyShared)")
            } else {
                OshiLog.app.error("🔑 Peer sessions shared INCOMPLETE: migrated=\(sessions.migrated) alreadyShared=\(sessions.alreadyShared) failed=\(sessions.failed)")
            }

            // 🔒 The ratchet lock now sits on the send AND receive paths, and it
            // THROWS when it cannot be taken rather than proceeding unlocked — so an
            // unavailable lock is an unsendable message. That trade is deliberate,
            // but it makes "is the lock actually available?" a question worth
            // answering at launch instead of discovering during someone's send.
            switch CrossProcessLock.probeAvailability() {
            case .success:
                OshiLog.app.info("🔒 Cross-process lock available")
            case .failure(let error):
                OshiLog.app.error("🔒 Cross-process lock UNAVAILABLE — ratchet writes will refuse: \(error)")
            }
        }
        
        // Connect MessageManager to IdentityManager and MeshManager
        DispatchQueue.main.async {
            MessageManager.sharedIdentityManager = identityManager
            MessageManager.sharedMeshManager = meshManager
            OshiLog.app.info("✅ MessageManager connected to IdentityManager + MeshManager")
        }
        
        // NOTE: Location permission will be requested when user taps location button
        // We don't request it here because iOS requires visible UI for the permission dialog
        OshiLog.app.info("📍 LocationSharingManager ready (permission requested on demand)")
        let _ = LocationSharingManager.shared // Just initialize, don't request permission
        
        // ✅ Initialize IPFS Group Uploader
        OshiLog.app.info("🚀 Initializing IPFS Group Uploader...")
        let _ = IPFSGroupUploader.shared
        OshiLog.app.info("✅ IPFS Group Uploader ready for group messages")
        
        // ✅ Initialize IPFS Ephemeral Manager (auto-cleanup after delivery)
        // Note: IPFSEphemeralManager handles message cleanup automatically
        OshiLog.app.info("🔐 IPFS Ephemeral cleanup is handled by delivery receipts")
        
        // ✅ Initialize Delivery Receipt Manager (bidirectional confirmation)
        OshiLog.app.info("📬 Initializing Delivery Receipt Manager...")
        let _ = DeliveryReceiptManager.shared
        OshiLog.app.info("✅ Delivery Receipt Manager ready - bidirectional confirmation enabled")
        
        // ✅ Initialize Cloud Group Service for message delivery
        OshiLog.app.info("☁️ Initializing Cloud Group Service...")
        let _ = CloudGroupService.shared
        OshiLog.app.info("✅ Cloud Group Service ready")
        
        // ✅ Initialize DiscoveredGroupsManager for public group discovery
        OshiLog.app.info("📡 Initializing DiscoveredGroupsManager...")
        let _ = DiscoveredGroupsManager.shared
        OshiLog.app.info("✅ DiscoveredGroupsManager ready")

        // ✅ Initialize CrossPlatformMesh for iOS ↔ Android mesh communication
        OshiLog.app.info("🌐 Initializing CrossPlatformMesh (iOS ↔ Android)...")
        let publicKey = identityManager.publicKey
        if !publicKey.isEmpty {
            let displayName = UserDefaults.standard.string(forKey: "userName") ?? "OSHI User"
            CrossPlatformMesh.shared.start(publicKey: publicKey, displayName: displayName)
            OshiLog.app.info("✅ CrossPlatformMesh started - iOS ↔ Android mesh enabled")
        } else {
            OshiLog.app.info("⚠️ CrossPlatformMesh deferred - waiting for identity")
        }

        // 📡 Wake the paired LoRa radio at launch, not on first visit to Settings.
        // MeshtasticManager is a lazy singleton and reconnectIfPaired() only runs
        // off centralManagerDidUpdateState — so until something touched .shared,
        // a paired node stayed disconnected and the first off-grid send found
        // isReady == false and failed. Only for people who actually paired one:
        // this is what brings up CoreBluetooth, and nobody else should pay for it.
        if UserDefaults.standard.string(forKey: "oshi.meshtastic.peripheralId") != nil {
            _ = MeshtasticManager.shared
            OshiLog.app.info("📡 LoRa: paired node found — reconnecting in the background")
        }

        // 📡 The CALLER of the LoRa position beacon — what makes "share my position over
        // LoRa, like Meshtastic" something a person can switch on. Before it,
        // `broadcastPosition` had no non-test caller on either platform.
        //
        // Installing it transmits NOTHING by itself: the switch it reads
        // (`MeshtasticManager.positionBeaconKey`) is false on a fresh install and
        // persisted, and `decide` answers `.disabled` before it asks CoreLocation
        // anything. Only a radio owner can ever reach the switch — the toggle is disabled
        // without a connected node — so there is no reason to gate the timer too.
        //
        // Here rather than in the Settings view because a beacon that dies when that
        // screen closes is not a beacon. See `LoRaPositionBeacon` for the airtime rules
        // and for what the switch exposes.
        LoRaPositionBeacon.shared.install()

        // 🕵️ Initialize Covert Channel Receiver (polls relay for steganographic messages)
        OshiLog.app.info("🕵️ Initializing CovertChannelReceiver...")
        CovertChannelReceiver.shared.configure(
            identityManager: identityManager,
            messageManager: messageManager
        )
        // Start polling after a delay (identity + message manager need to be ready)
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
            CovertChannelReceiver.shared.startPolling()
            OshiLog.app.info("✅ CovertChannelReceiver polling started")
        }
        
        // Note: MessageGroupManager.shared already starts periodic public group broadcasting in its init()
        
        // Test Pinata connection (optional - silent fail if VPS down)
        Task {
            do {
                let connected = try await PinataService.shared.testConnection()
                if connected {
                    OshiLog.app.info("✅ Pinata IPFS fallback available")
                }
            } catch {
                OshiLog.app.info("⚠️ Pinata unavailable - will use mesh only: \(error.localizedDescription)")
            }
        }
        
        // ✅ Check notification permissions (but don't request here)
        checkNotificationPermissions()

        // 📱 MULTI-DEVICE: Initial sync on app launch
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
            MultiDeviceSyncManager.shared.performFullSync()
        }

        LaunchTimingLogger.mark("initializeApp.synchronousWorkDone")

        // 🚀 FIX (2026-08-06, launch lag): the splash used to hang on a
        // hard-coded `asyncAfter(0.3)` — comment: "Wait a bit for
        // initialization". Measured on the simulator, the gap between
        // `synchronousWorkDone` and the splash actually leaving was 891 ms, so
        // the fixed delay was not even the whole cost: it QUEUES BEHIND
        // everything already on the main queue, including the initial sync
        // scheduled just above. Waiting "a bit" is a guess that gets worse the
        // more work launch does.
        //
        // The synchronous work is done at this point — that is what the mark
        // above says — so hop the main queue once and dismiss on the next
        // runloop pass instead. Faster whenever the queue is clear, and no
        // slower when it is not.
        DispatchQueue.main.async {
            LaunchTimingLogger.mark("isInitializing→false (splash dismissing)")
            withAnimation(.easeInOut(duration: 0.3)) {
                isInitializing = false
                OshiLog.app.info("✅ App initialization complete")
                OshiLog.app.info("   📡 Mesh network: Starting")
                OshiLog.app.info("   ☁️ IPFS fallback: Ready")
                OshiLog.app.info("   📤 Group message upload: Ready")
                OshiLog.app.info("   🔔 Notifications: Ready")
                OshiLog.app.info("   🌐 Public group discovery: Ready")
                OshiLog.app.info("   📤 Share Extension: Ready")
            }
        }
        
        // Mesh network starts automatically in MeshNetworkManager init()
    }
    
    private func checkNotificationPermissions() {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            switch settings.authorizationStatus {
            case .notDetermined:
                OshiLog.app.info("🔔 Notification permission not determined - will request in permissions view")
                
            case .denied:
                OshiLog.app.info("⚠️ Notification permission denied")
                
            case .authorized:
                OshiLog.app.info("✅ Notification permission already granted")
                
            case .provisional:
                OshiLog.app.info("✅ Notification permission (provisional)")
                
            case .ephemeral:
                OshiLog.app.info("✅ Notification permission (ephemeral)")
                
            @unknown default:
                OshiLog.app.info("⚠️ Unknown notification authorization status")
            }
        }
    }
}

// MARK: - Keyboard privacy (__KEYBOARD_PRIVACY_2026_10_01__)

/// WHY: the text of a message exists in clear inside the keyboard before OSHI
/// encrypts it. These two settings shrink who sees it there:
/// - `blockThirdPartyKeyboards` (default ON): custom keyboards are refused in OSHI.
/// - `privateKeyboard` (default OFF, it costs autocorrect): message composers turn
///   off autocorrection — and with it spell checking and predictions. iOS documents
///   no switch that stops the system lexicon from learning, so the explainer only
///   claims what this does: fewer of your words reach the keyboard's suggestions.
/// Neither can protect against a compromised OS (spyware) — said so in the explainer.
enum KeyboardPrivacy {
    static let blockThirdPartyKeyboardsKey = "blockThirdPartyKeyboards"
    static let privateKeyboardKey = "privateKeyboard"

    /// Default TRUE when the key was never written.
    static var blocksThirdPartyKeyboards: Bool {
        UserDefaults.standard.object(forKey: blockThirdPartyKeyboardsKey) as? Bool ?? true
    }

    /// Default FALSE when the key was never written.
    static var privateTyping: Bool {
        UserDefaults.standard.bool(forKey: privateKeyboardKey)
    }

    /// __KEYBOARD_PRIVACY_2026_10_01__ An inline notification reply is typed outside
    /// OSHI's process, where neither protection applies. While either is on, the
    /// notification "Reply" opens the chat in OSHI instead of an inline text field.
    static var protectsNotificationReplies: Bool {
        blocksThirdPartyKeyboards || privateTyping
    }
}

/// Applied to every message composer. SwiftUI's `autocorrectionDisabled` sets
/// `autocorrectionType = .no` on the backing UITextField/UITextView, and their
/// `spellCheckingType = .default` follows autocorrection, so spell checking goes
/// off too. Sentence capitalisation is deliberately kept.
private struct PrivateTypingModifier: ViewModifier {
    @AppStorage(KeyboardPrivacy.privateKeyboardKey) private var privateKeyboard = false

    func body(content: Content) -> some View {
        content.autocorrectionDisabled(privateKeyboard)
    }
}

extension View {
    /// __KEYBOARD_PRIVACY_2026_10_01__ — use on any field where a message is composed.
    func privateTypingComposer() -> some View {
        modifier(PrivateTypingModifier())
    }
}

// MARK: - Device integrity (__DEVICE_INTEGRITY_2026_10_01__)

/// WHY: on a jailbroken (or otherwise tampered) phone, any other code can hook
/// the keyboard, read OSHI's memory and the screen — before encryption and after
/// decryption. No app-side measure fixes that, but the person deserves to KNOW,
/// so OSHI looks for the common signs and says so (launch alert + red row in
/// Settings › Privacy › Keyboard).
///
/// HONESTY: this is a heuristic. A jailbreak that hides itself defeats every
/// check below, so a clean result proves nothing and the UI never says "safe".
/// It only ever WARNS: it never deletes keys or data and never blocks the app —
/// a false positive must cost a sentence, not someone's messages.
enum DeviceIntegrity {
    /// One app version = one alert; a new build may detect differently.
    static let warnedVersionKey = "deviceIntegrity.warnedVersion"

    /// Files a stock iOS install does not have (and that the sandbox lets us stat
    /// when they DO exist on the common jailbreaks). Rootful + rootless (/var/jb).
    static let suspiciousPaths: [String] = [
        "/Applications/Cydia.app",
        "/Applications/Sileo.app",
        "/Applications/Zebra.app",
        "/var/jb",
        "/private/var/lib/apt",
        "/etc/apt",
        "/usr/sbin/sshd",
        "/bin/bash",
        "/Library/MobileSubstrate/MobileSubstrate.dylib",
    ]

    /// Stock iOS refuses any write outside the sandbox.
    static let sandboxProbePath = "/private/jailbreak_probe"

    /// Substrings (lower-cased) of the injected-code frameworks used by tweaks and
    /// by Frida. Matched against loaded image paths and DYLD_INSERT_LIBRARIES.
    static let hookMarkers: [String] = [
        "mobilesubstrate", "substrate", "substitute", "libhooker",
        "ellekit", "tweakinject", "fridagadget", "frida",
    ]

    /// Xcode injects these on a debug run on a device (Main Thread Checker,
    /// backtrace recording, view debugger…). A developer build must not warn.
    static let developerToolLibraries: Set<String> = [
        "libBacktraceRecording.dylib", "libMainThreadChecker.dylib",
        "libViewDebuggerSupport.dylib", "libLogRedirect.dylib", "libRPAC.dylib",
    ]

    enum Signal: Equatable {
        case suspiciousPath(String)
        case sandboxWritable
        case injectedLibraryEnv(String)
        case hookedImage(String)
    }

    /// Simulator, Mac Catalyst and "iPad app on Mac" are never flagged: a Mac
    /// legitimately has /bin/bash, /usr/sbin/sshd, writable paths, and the
    /// simulator runs on top of macOS.
    static var isExemptEnvironment: Bool {
        #if targetEnvironment(simulator) || targetEnvironment(macCatalyst)
        return true
        #else
        return ProcessInfo.processInfo.isiOSAppOnMac
        #endif
    }

    /// Pure, injectable core so the heuristics are unit-testable on a simulator.
    static func evaluate(
        fileExists: (String) -> Bool,
        canWriteOutsideSandbox: () -> Bool,
        insertedLibraries: String?,
        loadedImages: [String]
    ) -> [Signal] {
        var signals: [Signal] = []
        for path in suspiciousPaths where fileExists(path) {
            signals.append(.suspiciousPath(path))
        }
        if canWriteOutsideSandbox() {
            signals.append(.sandboxWritable)
        }
        if let env = insertedLibraries {
            // Stock iOS honours DYLD_INSERT_LIBRARIES only for debuggable (Xcode)
            // builds, and Xcode injects many tools (XCTest bundle inject, sanitizer
            // runtimes, GPU capture…). Flag only entries that LOOK like hooking:
            // a hook marker or a jailbreak prefix — real hooks are also caught by
            // the loaded-image scan below.
            let jailbreakPrefixes = ["/var/jb", "/library/mobilesubstrate", "/usr/lib/tweakinject", "/private/preboot/jb"]
            for lib in env.split(separator: ":").map(String.init) where !lib.isEmpty {
                let name = (lib as NSString).lastPathComponent
                if developerToolLibraries.contains(name) { continue }
                let lower = lib.lowercased()
                guard hookMarkers.contains(where: { lower.contains($0) })
                        || jailbreakPrefixes.contains(where: { lower.hasPrefix($0) }) else { continue }
                signals.append(.injectedLibraryEnv(lib))
            }
        }
        for image in loadedImages {
            let lower = image.lowercased()
            if hookMarkers.contains(where: { lower.contains($0) }) {
                signals.append(.hookedImage(image))
            }
        }
        return signals
    }

    /// Computed ONCE per process (the environment cannot become un-jailbroken
    /// while we run, and the probe writes a file — no reason to repeat it).
    static let signals: [Signal] = {
        guard !isExemptEnvironment else { return [] }
        return evaluate(
            fileExists: { FileManager.default.fileExists(atPath: $0) },
            canWriteOutsideSandbox: {
                do {
                    try "probe".write(toFile: sandboxProbePath, atomically: false, encoding: .utf8)
                    try? FileManager.default.removeItem(atPath: sandboxProbePath)
                    return true
                } catch {
                    return false
                }
            },
            insertedLibraries: ProcessInfo.processInfo.environment["DYLD_INSERT_LIBRARIES"],
            loadedImages: loadedImageNames()
        )
    }()

    /// "OSHI detected signs that…" — never read as "the device is safe" when false.
    static var detectedSigns: Bool { !signals.isEmpty }

    private static func loadedImageNames() -> [String] {
        var names: [String] = []
        let count = _dyld_image_count()
        names.reserveCapacity(Int(count))
        for i in 0..<count {
            if let c = _dyld_get_image_name(i) { names.append(String(cString: c)) }
        }
        return names
    }

    private static var currentVersionTag: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(short)(\(build))"
    }

    /// True when the launch alert is due: signs found and not yet acknowledged
    /// for this app version.
    static var shouldWarnAtLaunch: Bool {
        detectedSigns
            && UserDefaults.standard.string(forKey: warnedVersionKey) != currentVersionTag
    }

    /// Recorded only when the person taps the button, so an alert iOS could not
    /// present (another modal on screen) is shown again next launch.
    static func markWarned() {
        UserDefaults.standard.set(currentVersionTag, forKey: warnedVersionKey)
    }
}

/// __DEVICE_INTEGRITY_2026_10_01__ One-time (per app version) launch alert.
/// A modifier rather than one more `.alert` on the WindowGroup root, whose body
/// already sits at the edge of what the type checker accepts.
private struct DeviceIntegrityLaunchAlert: ViewModifier {
    @State private var showWarning = false

    func body(content: Content) -> some View {
        content
            .task {
                guard DeviceIntegrity.shouldWarnAtLaunch else { return }
                // After the launch video / unlock, not on top of the splash.
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                showWarning = true
            }
            .alert("deviceIntegrity.alert.title".localized, isPresented: $showWarning) {
                Button("deviceIntegrity.alert.ok".localized) {
                    DeviceIntegrity.markWarned()
                }
            } message: {
                Text("deviceIntegrity.alert.message".localized)
            }
    }
}

extension View {
    func deviceIntegrityLaunchAlert() -> some View {
        modifier(DeviceIntegrityLaunchAlert())
    }
}

// MARK: - Clipboard hygiene (__CLIPBOARD_HYGIENE_2026_10_01__)

/// WHY: `UIPasteboard.general.string = x` keeps x on the clipboard forever and
/// syncs it to every Apple device on the account (Universal Clipboard), where
/// any app can read it later. Message text, recovery keys and bot tokens copied
/// out of OSHI go through here instead: this device only, gone after 60 s.
/// Public identifiers (own public key, address, group id, safety number) are
/// meant to be shared and still use the plain pasteboard.
enum SecureClipboard {
    static let lifetime: TimeInterval = 60

    static func options(now: Date = Date()) -> [UIPasteboard.OptionsKey: Any] {
        [
            .localOnly: true,
            .expirationDate: now.addingTimeInterval(lifetime),
        ]
    }

    /// __MAC_COPY_WROTE_A_TYPE_NOBODY_READS_2026_11_06__ Owner report: "the
    /// current macos version of oshi on my mac when i click on copy a message
    /// it do not copy the message".
    ///
    /// This used to be `UTType.plainText.identifier`, which is
    /// `"public.plain-text"` — the ABSTRACT PARENT UTI. It has no declared data
    /// representation, so AppKit never resolves it. Measured on this Mac, with
    /// only a `SecureClipboard.copy` on the pasteboard:
    ///
    ///     NSPasteboard types: ["public.plain-text"]
    ///     string(forType: .string)        → nil
    ///     availableType(from: [.string])  → nil
    ///     readObjects(forClasses:[NSString]) → []
    ///     pbpaste                         → (nothing)
    ///
    /// `NSPasteboard.PasteboardType.string` is `"public.utf8-plain-text"`, so
    /// every other Mac app — TextEdit, Safari, Notes, Mail, the shell — asked
    /// for a type OSHI had not written and got nothing. ⌘V pasted nothing.
    ///
    /// iOS was never affected and that is exactly why this survived: on iOS the
    /// only reader is `UIPasteboard`, which DOES resolve the abstract parent, so
    /// `pb.string` round-tripped and every test passed. The bug lived entirely
    /// in the one place no iOS test can look — another app's paste.
    ///
    /// `public.utf8-plain-text` is the concrete type and both frameworks read
    /// it; it is also what a plain `pasteboard.string = x` writes.
    /// `"public.utf8-plain-text"`. Named so tests can pin it without asking the
    /// implementation which UTI it picked — the shape of the assertion that let
    /// this ship (see `testTheCopiedTypeIsTheOneOtherAppsAskFor`).
    static let textType: String = UTType.utf8PlainText.identifier

    static func items(for text: String) -> [[String: Any]] {
        [[textType: text]]
    }

    static func copy(_ text: String, to pasteboard: UIPasteboard = .general) {
        pasteboard.setItems(items(for: text), options: options())
        scheduleExpiry(on: pasteboard)
    }

    /// macOS honours NEITHER `.expirationDate` NOR `.localOnly` — Apple DTS has
    /// said as much on the Forums ("macOS traditionally has not supported
    /// expiring pasteboard items"). So the 60-second promise in this type's doc
    /// comment was true on iPhone and silently false on Mac, which for message
    /// text and recovery keys is the half of the feature that matters.
    ///
    /// Enforced here instead. `changeCount` is the guard: if anything has
    /// touched the pasteboard since we wrote it — the user copying something
    /// else, another app, Universal Clipboard — the count has moved and we leave
    /// it alone. Clearing unconditionally would eat a newer copy.
    private static func scheduleExpiry(on pasteboard: UIPasteboard) {
        #if targetEnvironment(macCatalyst)
        let stamp = pasteboard.changeCount
        DispatchQueue.main.asyncAfter(deadline: .now() + lifetime) {
            guard pasteboard.changeCount == stamp else { return }
            pasteboard.items = []
        }
        #endif
    }
}

// MARK: - App Delegate (Renamed to avoid conflicts)

class OshiAppDelegate: NSObject, UIApplicationDelegate {
    /// True when the PROCESS was started by a notification tap (as opposed to the
    /// app being resumed by one). Only the cold-launch case can be missed by the
    /// deep-link check, because that runs before any tap is delivered.
    static var launchedFromNotification = false

    // NotificationDelegate is defined in NotificationManager.swift
    private let notificationDelegate = NotificationDelegate()

    // 🔧 Dynamic orientation lock — VideoCallView unlocks to .allButUpsideDown during calls
    static var orientationLock: UIInterfaceOrientationMask = .portrait

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        // __FOLDABLE_LAYOUT_2026_11_16__ The portrait lock is a PHONE decision.
        // On iPad it contradicted the app's own Info.plist (UISupportedInterface-
        // Orientations~ipad lists all four) and pinned a tablet to portrait;
        // the layout now adapts to any width (two panes when wide), so the iPad
        // follows its Info.plist. iPhone Duo's inner display ignores supported
        // orientations altogether (Apple, "Prepare your app for iPhone Duo"), so
        // a landscape-shaped canvas has to work either way.
        if UIDevice.current.userInterfaceIdiom == .pad { return .all }
        #if DEBUG
        // UI tests: turn a Pro Max sideways to drive a LIVE compact → regular
        // width change (the same event as unfolding), which XCUITest cannot
        // otherwise produce. See FoldableLayoutUITests.
        if ProcessInfo.processInfo.arguments.contains("-OSHIAllowLandscape") { return .allButUpsideDown }
        #endif
        return OshiAppDelegate.orientationLock
    }

    // __KEYBOARD_PRIVACY_2026_10_01__
    // WHY: everything typed in the composer goes through the keyboard BEFORE OSHI
    // encrypts it. A third-party keyboard granted Full Access can read every
    // keystroke and send it anywhere, so OSHI refuses custom keyboards app-wide
    // (iOS falls back to the system keyboard). Opt-out in Settings › Privacy ›
    // Keyboard; read on each query so the toggle needs no relaunch where iOS
    // re-asks. Harmless on Mac Catalyst (no keyboard extensions there).
    func application(
        _ application: UIApplication,
        shouldAllowExtensionPointIdentifier extensionPointIdentifier: UIApplication.ExtensionPointIdentifier
    ) -> Bool {
        if extensionPointIdentifier == .keyboard {
            return !KeyboardPrivacy.blocksThirdPartyKeyboards
        }
        return true
    }
    
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey : Any]? = nil
    ) -> Bool {
        OshiLog.app.info("📱 OshiAppDelegate: Setting up notification system...")
        PlaintextLeftovers.applyAtLaunch()  // __PLAINTEXT_LEFTOVERS_2026_09_24__ idempotent
        PrivacyCoverController.shared.install()  // __LEAK_FIX_2026_09_25__ app-switcher cover
        MessageBannerPolicy.mirrorShowsNames()   // __NOTIFICATION_NAMES_HIDDEN_2026_11_20__ for the NSE
        // __POISONED_TILE_PURGE_2026_11_16__ delete OSM 'Access blocked' tiles saved
        // since 13/11 (background queue, once per app version).
        PoisonedTile.purgeAtLaunchIfNeeded()

        // ✅ Register BGTaskScheduler for scheduled message delivery (even when app killed)
        ScheduledMessageManager.registerBackgroundTask()
        OshiLog.app.info("📋 BGTaskScheduler: Scheduled message delivery registered")

        // ⌚️ Activate the Watch session HERE.
        //
        // 🔧 FIX (2026-08-10, "the Watch app is useless"): the watch app is not a
        // stub — it has a conversation list, a real chat view, a composer and
        // call screens. It could not WORK because the phone never activated its
        // WCSession on a normal launch.
        //
        // `WCSession.activate()` lives in WatchConnectivityManager's private
        // init, and the singleton was only ever touched from VoiceCallManager —
        // four call-handling sites. So the phone set its WCSession delegate only
        // if a CALL happened to occur in that process lifetime. Every request the
        // watch made (conversations, messages, send) was undeliverable until
        // then, including on the background launch watchOS triggers when the
        // watch sends something.
        //
        // Touching the singleton is the whole fix: activation is idempotent and
        // costs nothing when no watch is paired.
        _ = WatchConnectivityManager.shared
        OshiLog.app.info("⌚️ WatchConnectivity: session activated at launch")

        // ✅ Configure audio session to NOT interrupt music playback
        configureAudioSession()
        
        // ✅ Setup notification delegate (from NotificationManager.swift)
        UNUserNotificationCenter.current().delegate = notificationDelegate
        
        // ✅ Initialize VoIP Push Manager (registers for VoIP pushes)
        _ = VoIPPushManager.shared
        OshiLog.app.info("📞 VoIPPushManager initialized")

        // Register call notification category for APNs-based incoming calls (when CallKit bypassed)
        if ChinaRegionDetector.shared.shouldBypassCallKit {
            let acceptAction = UNNotificationAction(
                identifier: "ACCEPT_CALL",
                title: NSLocalizedString("call.action.accept", comment: "Accept"),
                options: [.foreground]
            )
            let declineAction = UNNotificationAction(
                identifier: "DECLINE_CALL",
                title: NSLocalizedString("call.action.decline", comment: "Decline"),
                options: [.destructive]
            )
            let callCategory = UNNotificationCategory(
                identifier: "INCOMING_CALL",
                actions: [acceptAction, declineAction],
                intentIdentifiers: [],
                options: [.customDismissAction]
            )
            // __CALL_BANNER_CANNOT_ANSWER_2026_08_25__ Ne plus REMPLACER ici.
            //
            // Cette ligne posait `[callCategory]` seule, effaçant du même coup
            // MESSAGE_CATEGORY, CHECKIN_WARNING et MESH_CALL. Et surtout, elle
            // était le SEUL endroit où `INCOMING_CALL` était enregistrée — dans
            // une branche « mode Chine ». Partout ailleurs, la bannière d'appel
            // entrante n'avait donc aucune action: impossible de décrocher
            // depuis le bandeau du haut, il fallait attendre l'écran plein.
            //
            // `INCOMING_CALL` est maintenant enregistrée pour TOUT LE MONDE dans
            // `NotificationManager.setupNotificationCategories()`, avec les
            // mêmes identifiants `ACCEPT_CALL`/`DECLINE_CALL` que ceux d'ici —
            // ceux-là étaient corrects, c'est leur portée qui ne l'était pas.
            OshiLog.app.info("📞 China mode: INCOMING_CALL est enregistrée globalement (NotificationManager)")
        }

        // ✅ Register for remote notifications (APNs)
        application.registerForRemoteNotifications()

        // __WELCOME_IS_A_REAL_CONVERSATION_2026_10_16__ Existing installs carry
        // a welcome thread addressed to the literal string "oshi.welcome",
        // which no send can reach. Seeding only runs for NEW identities, so
        // without this every current user keeps the broken thread. Idempotent
        // and cheap: a no-op once nothing carries the legacy key.
        DispatchQueue.main.async {
            WelcomeMessageManager.migrateLegacyWelcomeThread()
        }

        // __ASK_DONT_WAIT_2026_10_19__ Ask contacts for the groups we share.
        // A device added to a group while it could not receive never learns
        // about it: the roster broadcast is not replayed, and the existing
        // self-heal only fires on an envelope we cannot match — which never
        // arrives for a group we are not yet in. 25 s, so the v2 transport and
        // the contact list are both up; it is one request, once per launch.
        DispatchQueue.main.asyncAfter(deadline: .now() + 25) {
            MessageGroupManager.shared.requestGroupSyncFromAllContacts()
        }

        // 🔧 FIX (2026-06-07, v1.0.20): ALSO ensure UNUserNotificationCenter
        // display permission is requested at launch. Previously this was only
        // triggered from PermissionRequestView during onboarding — TestFlight
        // upgrade installs that skipped onboarding ended up with an APNs token
        // (because the aps-environment entitlement is enough for that) but NO
        // display permission, so iOS silently dropped every visible push and
        // the app didn't even show up in Settings → Notifications. APNs still
        // returned 200 for our server's pushes, hiding the bug end-to-end.
        //
        // This call is idempotent: if the user has already chosen (granted or
        // denied), iOS no-ops. If still `.notDetermined`, iOS shows the system
        // prompt on the next foreground run. Either way, after the call the
        // app appears in Settings → Notifications and visible pushes work.
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            if settings.authorizationStatus == .notDetermined, !MailFixtureHarness.isActive {
                OshiLog.app.info("🔔 Authorization status .notDetermined at launch — requesting via NotificationManager")
                DispatchQueue.main.async {
                    NotificationManager.shared.requestPermissions { granted in
                        OshiLog.app.info("🔔 Launch-time auth request returned: granted=\(granted)")
                    }
                }
            } else {
                OshiLog.app.info("🔔 Notification auth status at launch: \(settings.authorizationStatus.rawValue)")
            }
        }

        // ✅ Setup screenshot detection
        setupScreenshotDetection()
        
        // ✅ Check if launched from notification
        if let notificationPayload = launchOptions?[.remoteNotification] as? [String: AnyObject] {
            OshiLog.app.info("📬 App launched from notification")
            // Recorded BEFORE handling, because the launch-video gate reads it
            // from a SwiftUI @State initialiser and must not depend on whether
            // the payload handler has finished resolving a deep-link target yet.
            // didFinishLaunchingWithOptions runs before the first scene is built,
            // so this is set in time; the deep-link check below is the belt to
            // this braces for the warm-launch tap, which never comes through here.
            OshiAppDelegate.launchedFromNotification = true
            handleNotificationPayload(notificationPayload)
        }
        
        return true
    }
    
    // ✅ Configure audio session to not interrupt other audio (e.g., music)
    // WhatsApp-like behavior: Don't configure audio at all on app launch
    // Audio session will ONLY be configured when actually needed (calls, recording, playing audio)
    private func configureAudioSession() {
        // 🔧 FIX: Do NOT configure audio session on app launch
        // This is what WhatsApp does - it only touches audio when needed
        // Configuring audio session (even with .ambient) can still interrupt music on cold launch
        OshiLog.app.info("🎵 Audio session NOT configured on launch - will not interrupt music")
        OshiLog.app.info("   📱 Audio will only be configured when needed (calls, voice messages)")

        // NOTE: Each audio feature (VoiceCallManager, AudioRecorderManager, etc.)
        // will configure its own audio session when activated, and deactivate when done
    }
    
    // ✅ Background URLSession events (LLM model download).
    // iOS relaunches the app in the background to deliver completion for the
    // background download session. We forward the system-supplied completion
    // handler to LocalLLMManager so it can invoke it once
    // urlSessionDidFinishEvents(forBackgroundURLSession:) fires.
    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        if identifier == LocalLLMManager.backgroundSessionIdentifier {
            Task { @MainActor in
                LocalLLMManager.shared.handleBackgroundEvents(completionHandler)
            }
        } else if identifier == OfflineMapManager.backgroundSessionIdentifier {
            // Long-haul region tile downloads. iOS relaunches the app when a
            // batch completes; OfflineMapManager invokes the handler from
            // urlSessionDidFinishEvents so iOS can resuspend us.
            OfflineMapManager.shared.handleBackgroundEvents(completionHandler)
        } else if identifier == BackgroundMediaUploader.backgroundSessionIdentifier {
            // Media (image/file) uploads to IPFS. iOS relaunches us in the
            // background when a transfer finishes; the uploader completes the
            // send (notifyVPS) and invokes this handler from
            // urlSessionDidFinishEvents so iOS can resuspend us.
            BackgroundMediaUploader.shared.handleBackgroundEvents(completionHandler)
        } else if identifier == V2BackgroundBlobUploader.backgroundSessionIdentifier {
            // Background upload of V2 blob chunks (the media SEND path). Without
            // this branch a post-relaunch completion fell into the `else` below and
            // the handler was invoked without ever reaching the uploader.
            V2BackgroundBlobUploader.shared.handleBackgroundEvents(completionHandler)
            // 🔧 FIX: the chunks survive the process, the SEND did not. Chunk PUTs
            // run on the background session and finish even after we are killed,
            // but `commit` + the ratchet key-message envelope run in-process, so the
            // media reached the server and the message never did — it waited for the
            // user to reopen the app, because the only drain trigger is
            // didBecomeActive, which a background relaunch never posts. Re-drive the
            // existing retry queue here instead. Off by default
            // (`oshi_media_bg_relaunch_drive`); see the method's doc comment.
            Task { @MainActor in
                MessageManager.shared?.driveUnsentTransfersAfterBackgroundRelaunch()
            }
        } else if identifier == V2BackgroundBlobDownloader.backgroundSessionIdentifier {
            // Resumable, background download of received V2 file/media blob
            // chunks. iOS relaunches us when chunks land; the downloader stages
            // them on disk and invokes this handler from urlSessionDidFinishEvents.
            V2BackgroundBlobDownloader.shared.handleBackgroundEvents(completionHandler)
        } else {
            completionHandler()
        }
    }

    // ✅ Called when APNs registration succeeds
    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let token = deviceToken.map { String(format: "%02.2hhx", $0) }.joined()
        OshiLog.app.info("✅ APNs device token: \(token)")
        CallFileLogger.shared.log("✅ APNs: device token received (\(token.prefix(16))...)")

        // Save token for future use
        UserDefaults.standard.set(token, forKey: "deviceToken")

        // ✅ Register with VoIP Push Manager
        VoIPPushManager.shared.registerAPNsToken(deviceToken)
    }

    // ✅ Called when APNs registration fails
    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        OshiLog.app.info("❌ APNs registration failed: \(error.localizedDescription)")
        CallFileLogger.shared.log("❌ APNs: registration FAILED: \(error.localizedDescription)")
    }
    
    // ✅ Handle remote notification in background
    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        OshiLog.app.info("📬 Received remote notification (background)")
        // 🔧 DIAG (2026-06-03): every remote push that iOS actually delivers
        // to the app shows up here, so call_debug.log will tell us whether
        // "missing notification" reports are a delivery failure (no line) or
        // a UI/handling issue (line present but no rich notif followed).
        let pushType = (userInfo["type"] as? String) ?? "?"
        let mid = (userInfo["messageId"] as? String) ?? "?"
        CallFileLogger.shared.log("DIAG_PUSH_RECV | bg | type=\(pushType) | mid=\(mid.prefix(8)) | state=\(application.applicationState.rawValue)")

        // Check if this is a web sync wake push
        if let type = userInfo["type"] as? String, type == "web_sync" {
            OshiLog.app.info("🌐 Web sync wake push received")
            handleWebSyncPush(userInfo: userInfo, completionHandler: completionHandler)
            return
        }

        // 🔧 2026-06-09 (b44): silent call-keepalive push fired by the server
        // when our WS dropped during an active call and the peer's audio is
        // being buffered. Immediately force a WS reconnect so audio resumes.
        // Without this, we'd rely on the periodic WS-staleness check (>8s)
        // which is already long enough for the caller to hear ~10s of silence.
        if let type = userInfo["type"] as? String, type == "callKeepalive" {
            let cid = (userInfo["callId"] as? String) ?? ""
            OshiLog.app.info("📞 callKeepalive push received (callId=\(cid.prefix(8))) — forcing WS reconnect")
            CallFileLogger.shared.log("DIAG_PUSH_RECV | callKeepalive | callId=\(cid.prefix(8))")
            Task { @MainActor in
                VoiceCallManager.shared.forceWebSocketReconnectFromPush()
            }
            // Don't poll messages or anything else for this push type.
            completionHandler(.newData)
            return
        }

        // 🔔 RING-CANCEL FIX: caller cancelled/ended the call BEFORE we answered.
        // The server tags this push type="callEnd" (the same terminal event it logs
        // as "[CALLEND PUSH] Triggered APNs alert ... signalType=callEnd"). If the
        // server delivers it as a background push (aps.content-available:1 — see the
        // server proposal in ops/callend_voip_push_proposal.md), iOS wakes us here
        // even when locked/backgrounded, and we dismiss the CallKit ring immediately
        // (CXProvider.reportCall endedAt + stopRingtone + state reset, all via
        // VoiceCallManager.dismissRingOnCallerCancel → endCall). This is the path
        // that fixes "B keeps ringing until timeout when A cancels".
        if let type = userInfo["type"] as? String,
           type == "callEnd" || type == "callCancelled" {
            let callerKey = (userInfo["callerPublicKey"] as? String)
                ?? (userInfo["senderPublicKey"] as? String)
                ?? (userInfo["sender"] as? String)
            let cid = (userInfo["callId"] as? String) ?? ""
            // __SEALED_CANCEL_ONLY_2026_09_23__ SECURITY: this push is PLAINTEXT and
            // UNAUTHENTICATED — the server's /send-push and /signal accept it from anyone
            // who knows our public key. It used to tear the ring down directly
            // (`dismissRingOnCallerCancel` with the push's callerPublicKey/callId, and an
            // "unknown caller matches" branch for the locked path), so a stranger could
            // silence every incoming call. It is now only a WAKE-UP: fetch the call
            // server's queue at once, and let the SEALED callEnd — which only the real
            // caller can produce (ECDH, `decryptFromPeer`) — end the ring through
            // `handleReceivedCallSignal`. Same rule as Android (`decryptCallSignal`) and
            // Desktop (`CallLane`: a packet that does not open is never played).
            OshiLog.app.info("📞 callEnd push received (callId=\(cid.prefix(8)), caller=\(callerKey?.prefix(12) ?? "?")) — fetching the sealed signal")
            CallFileLogger.shared.log("DIAG_PUSH_RECV | callEnd | callId=\(cid.prefix(8)) | caller=\(callerKey?.prefix(12) ?? "?") | action=poll-sealed")
            Task { @MainActor in
                await VoiceCallManager.shared.pollCallSignalsForCancelPush()
                completionHandler(.newData)
            }
            return
        }

        // 🔧 FIX: Do NOT navigate on background push — only fetch messages.
        // Navigation happens when user taps a LOCAL notification (handled by NotificationDelegate.didReceive).
        // Previously called handleNotificationPayload(userInfo) here which caused black screen.

        // 🔧 FIX (1.0.10 v6): Tighter burst on background push. Same reasoning as
        // foreground burst: respect the isPollingInProgress guard with intervals
        // that allow each poll to actually fire after the previous one finishes
        // (~300–600 ms typical). Going from 0/500/1500 to 0/300/800/1500 squeezes
        // ~500 ms off the time-to-first-bubble.
        MessageManager.shared?.checkPendingIPFSMessages()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            MessageManager.shared?.checkPendingIPFSMessages()
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
            MessageManager.shared?.checkPendingIPFSMessages()
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            MessageManager.shared?.checkPendingIPFSMessages()
        }

        // 🔧 FIX: Do NOT store bot messages from push payload as text-only.
        // Push payloads can't carry images (4KB APNs limit). Storing text-only here
        // races with processBotEnvelope() (called by checkPendingIPFSMessages above)
        // which extracts full media. The text-only version would either:
        // (a) block the media upgrade if plaintextContent matches, or
        // (b) create a duplicate text-only message if content differs.
        // Let processBotEnvelope() handle bot messages with full media extraction.

        // Give time for fetch to complete before telling iOS we're done
        DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) {
            completionHandler(.newData)
        }
    }

    /// Handle web sync push - wake up and connect WebSocket to sync data with web client
    private func handleWebSyncPush(
        userInfo: [AnyHashable: Any],
        completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        var backgroundTask: UIBackgroundTaskIdentifier = .invalid
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "WebSyncPush") {
            OshiLog.app.info("⚠️ Web sync background task expiring")
            WebSessionManager.shared.disconnectWebSocket()
            if backgroundTask != .invalid {
                UIApplication.shared.endBackgroundTask(backgroundTask)
                backgroundTask = .invalid
            }
        }

        OshiLog.app.info("🌐 Starting web sync background task")

        WebSessionManager.shared.connectForBackgroundSync { syncCompleted in
            OshiLog.app.info("🌐 Web sync completed: \(syncCompleted)")
            completionHandler(syncCompleted ? .newData : .noData)

            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                WebSessionManager.shared.disconnectWebSocket()
                if backgroundTask != .invalid {
                    UIApplication.shared.endBackgroundTask(backgroundTask)
                    backgroundTask = .invalid
                }
                OshiLog.app.info("🌐 Web sync background task ended")
            }
        }
    }
    
    // ✅ Handle notification payload
    private func handleNotificationPayload(_ payload: [AnyHashable: Any]) {
        guard let type = payload["type"] as? String else { return }

        switch type {
        case "message":
            if let senderKey = payload["senderPublicKey"] as? String {
                PendingNotificationDeepLink.shared.set(.peer(publicKey: senderKey))
                NotificationCenter.default.post(
                    name: NSNotification.Name("OpenChat"),
                    object: nil,
                    userInfo: ["peerPublicKey": senderKey]
                )
            }

        case "call":
            // __LEAK_FIX_2026_09_25__ `callerName` optional: resolved locally from the key.
            if let callerKey = payload["callerPublicKey"] as? String {
                let callerName = VoIPPushManager.resolveCallerDisplayName(
                    callerKey: callerKey, pushName: payload["callerName"] as? String)

                // Native mode: Handle APNs call notification (show in-app UI)
                if ChinaRegionDetector.shared.shouldBypassCallKit {
                    let callId = payload["callId"] as? String ?? UUID().uuidString
                    let isVideoCall = (payload["isVideoCall"] as? Bool) ?? false
                    OshiLog.app.info("📞 Native mode: Received APNs call notification from \(callerName)")

                    // reportIncomingCall handles everything in bypass mode:
                    // posts ShowInAppIncomingCall + polls for session key
                    VoIPPushManager.shared.reportIncomingCall(
                        callerPublicKey: callerKey,
                        callerName: callerName,
                        callId: callId,
                        isVideoCall: isVideoCall
                    )
                } else {
                    NotificationCenter.default.post(
                        name: NSNotification.Name("IncomingCall"),
                        object: nil,
                        userInfo: [
                            "callerPublicKey": callerKey,
                            "callerName": callerName
                        ]
                    )
                }
            }

        case "group_message":
            if let groupId = payload["groupId"] as? String {
                PendingNotificationDeepLink.shared.set(.group(groupId: groupId))
                NotificationCenter.default.post(
                    name: NSNotification.Name("OpenGroupChat"),
                    object: nil,
                    userInfo: ["groupId": groupId]
                )
            }

        case "mail":
            // New-email wake/tap on the background path. Mirror the tap handler in
            // NotificationDelegate: stash the mailbox id (allowlisted `messageId`,
            // else `mailId`) so a cold-launched view tree can consume it, and post
            // OpenMailMessage so an already-mounted tree routes immediately.
            let mailId = (payload["messageId"] as? String) ?? (payload["mailId"] as? String)
            PendingNotificationDeepLink.shared.set(.mail(messageId: mailId))
            NotificationCenter.default.post(
                name: NSNotification.Name("OpenMailMessage"),
                object: nil,
                userInfo: mailId.map { ["mailId": $0] } ?? [:]
            )

        case "web_sync":
            OshiLog.app.info("📬 Web sync notification (handled in didReceiveRemoteNotification)")

        default:
            OshiLog.app.info("📬 Unknown notification type: \(type)")
        }
    }
    
    // ✅ Setup screenshot detection
    private func setupScreenshotDetection() {
        NotificationCenter.default.addObserver(
            forName: UIApplication.userDidTakeScreenshotNotification,
            object: nil,
            queue: .main
        ) { _ in
            OshiLog.app.info("📸 Screenshot detected!")
            NotificationCenter.default.post(name: NSNotification.Name("ScreenshotTaken"), object: nil)
        }
    }
}

// MARK: - Biometric Lock View

struct BiometricLockView: View {
    @Binding var isUnlocked: Bool
    @State private var showError = false
    @State private var errorMessage = ""
    @State private var isAuthenticating = false
    @State private var animateLogo = false
    
    var body: some View {
        ZStack {
            // Background matching onboarding style
            LinearGradient(
                colors: [
                    Color(red: 0.02, green: 0.08, blue: 0.12),
                    Color(red: 0.05, green: 0.15, blue: 0.25)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()
            
            // Floating particles effect
            GeometryReader { geometry in
                ForEach(0..<15, id: \.self) { index in
                    Circle()
                        .fill(appBrandColor.opacity(Double.random(in: 0.1...0.3)))
                        .frame(width: CGFloat.random(in: 20...60))
                        .position(
                            x: CGFloat.random(in: 0...geometry.size.width),
                            y: CGFloat.random(in: 0...geometry.size.height)
                        )
                        .blur(radius: 2)
                }
            }
            
            VStack(spacing: 40) {
                Spacer()
                
                // App Logo with animation
                ZStack {
                    // Glow effect
                    Circle()
                        .fill(appBrandColor.opacity(0.3))
                        .frame(width: 160, height: 160)
                        .blur(radius: 30)
                        .scaleEffect(animateLogo ? 1.2 : 1.0)
                    
                    // Logo circle
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: [appBrandColor, appBrandColor.opacity(0.7)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 120, height: 120)
                        .shadow(color: appBrandColor.opacity(0.5), radius: 20)
                    
                    // Lock icon
                    Image(systemName: "lock.shield.fill")
                        .font(.scaled(50, weight: .medium))
                        .foregroundColor(.white)
                }
                .scaleEffect(animateLogo ? 1.05 : 1.0)
                .animation(.easeInOut(duration: 2).repeatForever(autoreverses: true), value: animateLogo)
                
                VStack(spacing: 12) {
                    Text("app_name".localized)
                        .font(.scaled(36, weight: .bold, design: .rounded))
                        .foregroundColor(.white)
                    
                    Text("lock_screen_locked".localized)
                        .font(.title3)
                        .foregroundColor(.white.opacity(0.7))
                }
                
                Spacer()
                
                // Unlock button
                Button {
                    authenticate()
                } label: {
                    HStack(spacing: 12) {
                        if isAuthenticating {
                            ProgressView()
                                .tint(.white)
                        } else {
                            Image(systemName: biometricType == .faceID ? "faceid" : "touchid")
                                .font(.title2)
                        }
                        Text("lock_screen_unlock_face_id".localized)
                            .fontWeight(.semibold)
                    }
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
                    .background(
                        LinearGradient(
                            colors: [appBrandColor, appBrandColor.opacity(0.8)],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .cornerRadius(16)
                    .shadow(color: appBrandColor.opacity(0.4), radius: 10, y: 5)
                }
                .disabled(isAuthenticating)
                .padding(.horizontal, 40)
                
                // Swipe hint
                VStack(spacing: 8) {
                    Image(systemName: "chevron.up")
                        .font(.caption)
                        .foregroundColor(.white.opacity(0.5))
                    Text("lock_screen_tap_to_unlock".localized)
                        .font(.caption)
                        .foregroundColor(.white.opacity(0.5))
                }
                .padding(.bottom, 40)
            }
        }
        .onAppear {
            animateLogo = true
            // Auto-authenticate when view appears
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                authenticate()
            }
        }
        .alert("lock_screen_auth_failed".localized, isPresented: $showError) {
            Button("lock_screen_try_again".localized) {
                authenticate()
            }
            Button("button_cancel".localized, role: .cancel) {}
        } message: {
            Text(errorMessage)
        }
    }
    
    private var biometricType: LABiometryType {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        return context.biometryType
    }
    
    func authenticate() {
        isAuthenticating = true
        let context = LAContext()
        var error: NSError?
        
        // Check if biometrics are available
        if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) {
            let reason = "lock_screen_unlock_reason".localized
            
            context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason) { success, authenticationError in
                DispatchQueue.main.async {
                    isAuthenticating = false
                    if success {
                        OshiLog.app.info("✅ Biometric authentication successful")
                        withAnimation {
                            isUnlocked = true
                        }
                    } else {
                        errorMessage = authenticationError?.localizedDescription ?? "lock_screen_auth_failed_message".localized
                        OshiLog.app.info("❌ Biometric authentication failed: \(errorMessage)")
                        showError = true
                    }
                }
            }
        } else {
            // No biometrics available, use device passcode
            OshiLog.app.info("⚠️ Biometrics not available, falling back to passcode")
            
            context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "lock_screen_unlock_reason".localized) { success, authenticationError in
                DispatchQueue.main.async {
                    isAuthenticating = false
                    if success {
                        OshiLog.app.info("✅ Passcode authentication successful")
                        withAnimation {
                            isUnlocked = true
                        }
                    } else {
                        errorMessage = authenticationError?.localizedDescription ?? "lock_screen_auth_failed_message".localized
                        OshiLog.app.info("❌ Passcode authentication failed: \(errorMessage)")
                        showError = true
                    }
                }
            }
        }
    }
}

// MARK: - Screenshot Protection (Aggressive Mode)
// This uses UITextField.isSecureTextEntry to make iOS render a secure layer
// that appears in screenshots instead of your app content

// MARK: - Screenshot Protection
// When user takes screenshot, they see OSHI logo instead of chat messages

// MARK: - Screenshot Protection (WhatsApp Technique)

/// Makes a window secure from screenshots using UITextField.isSecureTextEntry trick
/// This is the same technique WhatsApp uses for View Once photos
private func makeSecure(window: UIWindow) {
    let field = UITextField()
    field.isSecureTextEntry = true  // ← THE MAGIC FLAG
    
    // Create a view to hold the OSHI logo
    let view = UIView(frame: field.frame)
    
    // Create OSHI logo using LaunchScreenView
    let logoHost = UIHostingController(rootView: LaunchScreenView())
    // __FOLDABLE_LAYOUT_2026_11_16__ The window's own screen, not "the main
    // screen": Apple asks apps not to read `UIScreen.main` on iPhone Duo (two
    // displays, ambiguous). Identical on every single-display device.
    logoHost.view.frame = window.windowScene?.screen.bounds ?? UIScreen.main.bounds
    logoHost.view.backgroundColor = UIColor(red: 0.02, green: 0.08, blue: 0.12, alpha: 1.0)
    
    // Add field to window
    window.addSubview(field)
    
    // Add logo to view
    view.addSubview(logoHost.view)
    
    // CRITICAL: Layer manipulation - this is the WhatsApp technique
    // Reparent window's layer under secure field's layer
    if let superlayer = window.layer.superlayer {
        superlayer.addSublayer(field.layer)
        if let lastLayer = field.layer.sublayers?.last {
            lastLayer.addSublayer(window.layer)
        }
    }
    
    // Set the logo view as leftView - this is what appears in screenshots
    field.leftView = view
    field.leftViewMode = .always
    
    OshiLog.app.info("✅ Screenshot protection ENABLED (WhatsApp technique)")
    OshiLog.app.info("   🔒 Window layer reparented under secure text field")
    OshiLog.app.info("   📸 OSHI logo will appear in screenshots")
    OshiLog.app.info("   ✅ Chat messages will NOT appear in screenshots")
}

/// SwiftUI bridge to access UIWindow and apply protection
private struct WindowSecureLayerView: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        DispatchQueue.main.async {
            if let window = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .first?.windows.first(where: { $0.isKeyWindow }) {
                makeSecure(window: window)
            }
        }
        return UIView()
    }
    
    func updateUIView(_ uiView: UIView, context: Context) {}
}

/// SwiftUI ViewModifier for screenshot protection
struct SecureScreenModifier: ViewModifier {
    func body(content: Content) -> some View {
        ZStack {
            content
            WindowSecureLayerView()
                .frame(width: 0, height: 0)
        }
    }
}

/// View extension for easy usage
extension View {
    func secureScreen() -> some View {
        #if DEBUG
        // 📸 Screenshot capture: disable the anti-screen-recording shield, which
        // otherwise blanks the app to black when XCUITest records the screen.
        if ProcessInfo.processInfo.arguments.contains("-OSHIScreens") {
            return AnyView(self)
        }
        #endif
        return AnyView(self.modifier(SecureScreenModifier()))
    }
}

/// Legacy manager for backwards compatibility
final class ScreenshotProtectionManager {
    static let shared = ScreenshotProtectionManager()
    private var isEnabled = false
    private init() {}
    
    func enable() {
        guard !isEnabled else { return }
        
        if let window = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first?.windows.first(where: { $0.isKeyWindow }) {
            makeSecure(window: window)
            isEnabled = true
        }
    }
}

// MARK: - Mac (Catalyst) window chrome

/// Everything the Mac build needs that the iOS build must not get.
///
/// Kept as one modifier so the scene body has a single line for it and the
/// `#if` lives in exactly one place — the alternative is a `#if` around each
/// modifier at the call site, which is where this sort of thing rots.
struct MacWindowChrome: ViewModifier {
    func body(content: Content) -> some View {
        #if targetEnvironment(macCatalyst)
        content
            // The fix for the empty detail pane: force every NavigationView in
            // the subtree to behave as a stack instead of a two-column split.
            .navigationViewStyle(.stack)
            // A Mac window can be dragged to any size; below this the message
            // list and the composer start colliding. Applied to the scene's
            // UIWindowScene rather than as a SwiftUI frame, because a frame
            // would letterbox the content instead of constraining the window.
            .onAppear {
                Self.applyWindowLimits()
                MacCaptureShield.shared.install()   // __MAC_CAPTURE_SHIELD_2026_11_20__
            }
        #else
        content
        #endif
    }

    #if targetEnvironment(macCatalyst)
    /// Minimum and default window size, applied once the scene exists.
    ///
    /// `sizeRestrictions` is nil on iOS and non-nil only under Catalyst, which
    /// is why this is behind the `#if` rather than an availability check.
    private static func applyWindowLimits() {
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene,
                  let restrictions = windowScene.sizeRestrictions else { continue }
            // 420 x 620: narrow enough to keep as a side-by-side column next to
            // another app, wide enough that the composer and the send button do
            // not overlap at the largest Dynamic Type size.
            restrictions.minimumSize = CGSize(width: 420, height: 620)
            // CGFloat spelled out: `.greatestFiniteMagnitude` on its own is
            // ambiguous between Double/Float/CGFloat in a CGSize initialiser.
            restrictions.maximumSize = CGSize(width: CGFloat.greatestFiniteMagnitude,
                                              height: CGFloat.greatestFiniteMagnitude)
        }
    }
    #endif
}

extension View {
    /// Caps a full-screen view at a readable width on the Mac and centres it.
    ///
    /// A Mac window is dragged wide; an onboarding column that stretches to
    /// 1400 pt puts a single settings row and a full-width primary button on
    /// opposite sides of an empty field. iOS is untouched — a phone has no room
    /// to spare and the iPad layouts already have their own `maxContentWidth`.
    @ViewBuilder
    func macReadableWidth(_ limit: CGFloat = 720) -> some View {
        #if targetEnvironment(macCatalyst)
        self.frame(maxWidth: limit).frame(maxWidth: .infinity)
        #else
        self
        #endif
    }
}

// MARK: - Mac capture shield (__MAC_CAPTURE_SHIELD_2026_11_20__)

/// The iPhone build keeps chats out of screenshots, screen recordings and
/// mirroring with the secure-layer trick (`secureScreen()`), unconditionally.
/// That trick is a UIKit text-field behaviour with no AppKit equivalent, so on
/// the Mac every window was readable by any app holding Screen Recording
/// permission — exactly what commercial spy software asks for.
///
/// AppKit's answer is `NSWindow.sharingType = .none` (NSWindowSharingNone = 0):
/// the window server leaves the window out of other processes' captures
/// (`CGWindowListCreateImage`, legacy screen recording; ScreenCaptureKit on
/// recent macOS honours it for window capture but not necessarily for a whole-
/// display capture — so this raises the bar, it is not a guarantee).
///
/// Catalyst cannot import AppKit, so the windows are reached through the ObjC
/// runtime, and every call is guarded by `responds(to:)`: if a future macOS
/// renames anything this becomes a no-op, never a crash. Same policy as iOS:
/// always on (the app has no "allow screenshots" setting to follow).
final class MacCaptureShield {
    static let shared = MacCaptureShield()
    private var installed = false

    /// NSWindowSharingNone.
    static let sharingNone = 0

    func install() {
        #if targetEnvironment(macCatalyst)
        guard !installed else { return }
        installed = true
        applyToAllWindows()
        let nc = NotificationCenter.default
        // AppKit posts these on the default centre; new windows (sheets that get
        // their own NSWindow, a second scene) are covered when they become key.
        for name in ["NSWindowDidBecomeKeyNotification", "NSWindowDidBecomeMainNotification"] {
            nc.addObserver(forName: Notification.Name(name), object: nil, queue: .main) { [weak self] n in
                if let w = n.object as? NSObject { Self.shield(w) } else { self?.applyToAllWindows() }
            }
        }
        nc.addObserver(forName: UIScene.didActivateNotification, object: nil, queue: .main) { [weak self] _ in
            self?.applyToAllWindows()
        }
        #endif
    }

    func applyToAllWindows() {
        // Only Catalyst has an NSApplication class; on iOS this is nil and returns.
        // KVC on the class object is the usual Catalyst way to reach AppKit.
        guard let windows = NSClassFromString("NSApplication")?
                .value(forKeyPath: "sharedApplication.windows") as? [NSObject] else { return }
        windows.forEach { Self.shield($0) }
    }

    /// Sets `sharingType = none` on anything that has that property. Pure
    /// runtime, so it is testable with a stand-in object.
    @discardableResult
    static func shield(_ window: NSObject) -> Bool {
        guard window.responds(to: NSSelectorFromString("setSharingType:")) else { return false }
        window.setValue(NSNumber(value: sharingNone), forKey: "sharingType")
        return true
    }
}

// MARK: - Privacy cover (__LEAK_FIX_2026_09_25__)

/// Hides the app's content the moment a scene stops being active, so the open chat never
/// shows in the iPhone/iPad app switcher (nor in the snapshot iOS takes on background).
///
/// A separate `UIWindow` above everything (sheets, full-screen covers, call UI included),
/// shown on `willDeactivate`, removed on `didActivate`. It is never made key, so it can't
/// steal first responder, and it adds nothing while the app stays active — the photo
/// picker, share sheet and document picker never deactivate the scene. A Face ID prompt
/// or a system alert does: they simply appear over the cover. Off on Mac Catalyst (no app
/// switcher, and a Mac window deactivates on every focus change). Default ON; setting
/// `privacyCoverEnabled` (Settings › Security).
final class PrivacyCoverController {
    static let shared = PrivacyCoverController()
    static let defaultsKey = "privacyCoverEnabled"
    static var isEnabled: Bool { UserDefaults.standard.object(forKey: defaultsKey) as? Bool ?? true }

    private var covers: [ObjectIdentifier: UIWindow] = [:]
    private var installed = false

    func install() {
        #if !targetEnvironment(macCatalyst)
        guard !installed else { return }
        installed = true
        let nc = NotificationCenter.default
        nc.addObserver(forName: UIScene.willDeactivateNotification, object: nil, queue: .main) { [weak self] n in
            if let scene = n.object as? UIWindowScene { self?.show(on: scene) }
        }
        nc.addObserver(forName: UIScene.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] n in
            if let scene = n.object as? UIWindowScene { self?.show(on: scene) }
        }
        nc.addObserver(forName: UIScene.didActivateNotification, object: nil, queue: .main) { [weak self] n in
            if let scene = n.object as? UIScene { self?.hide(scene) }
        }
        nc.addObserver(forName: UIScene.didDisconnectNotification, object: nil, queue: .main) { [weak self] n in
            if let scene = n.object as? UIScene { self?.hide(scene) }
        }
        #endif
    }

    private func show(on scene: UIWindowScene) {
        let id = ObjectIdentifier(scene)
        guard Self.isEnabled, covers[id] == nil else { return }
        let window = UIWindow(windowScene: scene)
        window.windowLevel = UIWindow.Level.alert + 1
        window.isUserInteractionEnabled = false
        window.accessibilityElementsHidden = true
        let vc = UIViewController()
        vc.view.backgroundColor = UIColor(named: "LaunchBackground") ?? .systemBackground
        if let logo = UIImage(named: "LaunchLogo") {
            let iv = UIImageView(image: logo)
            iv.contentMode = .scaleAspectFit
            iv.translatesAutoresizingMaskIntoConstraints = false
            vc.view.addSubview(iv)
            NSLayoutConstraint.activate([
                iv.centerXAnchor.constraint(equalTo: vc.view.centerXAnchor),
                iv.centerYAnchor.constraint(equalTo: vc.view.centerYAnchor),
                iv.widthAnchor.constraint(lessThanOrEqualToConstant: 120),
                iv.heightAnchor.constraint(lessThanOrEqualToConstant: 120),
            ])
        }
        window.rootViewController = vc
        window.isHidden = false   // visible, never key
        covers[id] = window
    }

    private func hide(_ scene: UIScene) {
        guard let window = covers.removeValue(forKey: ObjectIdentifier(scene)) else { return }
        window.isHidden = true
        window.rootViewController = nil
    }
}
