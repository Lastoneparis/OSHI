//
//  NotificationManager.swift
//  OSHI - Fully Localized Version
//

import Foundation
import UserNotifications
import UIKit

// MARK: - Non-blocking main-thread notification observers

extension NotificationCenter {
    /// Register a block observer that runs on the main thread **without blocking the poster**.
    ///
    /// MECHANISM (0x8BADF00D watchdog fix). `addObserver(forName:object:queue: .main)` does
    /// not simply "hop to main". When the notification is posted from any thread other than
    /// main, NSNotificationCenter enqueues an NSOperation on that OperationQueue and calls
    /// `-[NSOperation waitUntilFinished]`, so the **posting thread is blocked** until the main
    /// runloop drains. Every watchdog crash report we collected shows the same cycle:
    ///
    ///   • a background serial queue (`com.oshi.messages.save`, `com.app.ratchet.manager`, or a
    ///     Swift Concurrency cooperative thread) parked in
    ///     `postNotificationName -> _CFXNotificationPost -> -[NSOperation waitUntilFinished]`,
    ///     still holding `FileStorage.writeLock` / owning the serial queue;
    ///   • the main thread parked in `DispatchQueue.sync` or `pthread_mutex_lock` waiting for
    ///     that exact queue/lock.
    ///
    /// Neither side can advance, the scene-update watchdog expires, and FrontBoard SIGKILLs us.
    ///
    /// Registering with `queue: nil` runs the block synchronously on the posting thread; we then
    /// hop to main ourselves with `async`, so the poster is never blocked and the cycle cannot
    /// form. Observers that were already on main keep running inline, so ordering relative to
    /// main-thread posts is unchanged.
    @discardableResult
    func addMainThreadObserver(
        forName name: NSNotification.Name?,
        object: Any? = nil,
        using block: @escaping (Notification) -> Void
    ) -> NSObjectProtocol {
        return addObserver(forName: name, object: object, queue: nil) { note in
            if Thread.isMainThread {
                block(note)
            } else {
                DispatchQueue.main.async { block(note) }
            }
        }
    }
}


class NotificationManager: ObservableObject {
    
    // MARK: - Debug File Logger
    private func debugLog(_ message: String) {
        // 🔒 PRIVACY: never emit notification/sender metadata to NSLog (syslog) or
        // persist it to a plaintext `group_debug.log` on a shipping build. Both are
        // readable off-device in Release. Development-only trace, mirroring the
        // MessageGroupManager fix (GroupMessaging.swift).
        #if DEBUG
        let timestamp = ISO8601DateFormatter().string(from: Date())
        let line = "[\(timestamp)] [NotifMgr] \(message)\n"
        NSLog("🔔 [NotifMgr] %@", message)
        if let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            let logFile = docs.appendingPathComponent("group_debug.log")
            if let data = line.data(using: .utf8) {
                if FileManager.default.fileExists(atPath: logFile.path) {
                    if let handle = try? FileHandle(forWritingTo: logFile) {
                        handle.seekToEndOfFile()
                        handle.write(data)
                        handle.closeFile()
                    }
                } else {
                    try? data.write(to: logFile)
                }
            }
        }
        #endif
    }
    static let shared = NotificationManager()

    @Published var notificationPermissionGranted = false

    /// 🔧 v1.0.19 build 38 (Group B fix): observable authorization status.
    /// Drives the in-app "Notifications are off" banner. Refreshed on launch
    /// AND every time the app becomes active (so when the user comes back from
    /// flipping the Settings toggle, the banner disappears immediately).
    @Published var authorizationStatus: UNAuthorizationStatus = .notDetermined

    private var becomeActiveObserver: NSObjectProtocol?

    private init() {
        setupNotificationCategories()
        checkPermissionStatus()

        // Seed authorizationStatus on init.
        refreshAuthorizationStatus()

        // Re-check whenever the app foregrounds. Catches the user flipping the
        // Settings toggle and returning to OSHI: status updates → banner hides.
        becomeActiveObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.refreshAuthorizationStatus()
        }
    }

    deinit {
        if let obs = becomeActiveObserver {
            NotificationCenter.default.removeObserver(obs)
        }
    }

    /// 🔧 Refresh @Published authorizationStatus from the OS. Cheap, safe to spam.
    func refreshAuthorizationStatus() {
        UNUserNotificationCenter.current().getNotificationSettings { [weak self] settings in
            DispatchQueue.main.async {
                let newStatus = settings.authorizationStatus
                if self?.authorizationStatus != newStatus {
                    self?.authorizationStatus = newStatus
                    OshiLog.ui.info("🔔 Notification authorization status: \(newStatus.rawValue)")
                }
                // Keep legacy bool in sync.
                self?.notificationPermissionGranted = (newStatus == .authorized || newStatus == .provisional || newStatus == .ephemeral)
            }
        }
    }

    /// 🔧 Group B fix: open OSHI's notification settings directly.
    /// iOS 16+ has a dedicated URL that jumps straight to the app's
    /// notification settings page. iOS 15 falls back to the app's root
    /// settings page (one extra tap for the user).
    func openNotificationSettings() {
        let urlString: String
        // __SETTINGS_LINK_IS_IOS_ONLY_2026_10_16__ `openNotificationSettingsURLString`
        // is the iOS `app-settings:` scheme. On Mac Catalyst nothing handles it,
        // so the orange "notifications are off" banner opened NOTHING: the one
        // control the app offers for fixing the problem did nothing at all on
        // the Mac, and the user has no other way in from the app. macOS wants
        // the System Settings pane URL instead. Reported from the Mac build.
        #if targetEnvironment(macCatalyst)
        urlString = "x-apple.systempreferences:com.apple.Notifications-Settings.extension"
        #else
        if #available(iOS 16.0, *) {
            urlString = UIApplication.openNotificationSettingsURLString
        } else {
            urlString = UIApplication.openSettingsURLString
        }
        #endif
        // __ASK_BEFORE_SENDING_THEM_TO_SETTINGS_2026_10_16__ Opening Settings
        // only helps if the OS has a record of this app to show. Measured on a
        // Mac where the banner was up: OSHI appeared in NONE of the 128 apps in
        // `com.apple.ncprefs`, so System Settings had no OSHI row to toggle and
        // the user was sent to a page that could not fix anything — they came
        // back reporting they had "already activated" it.
        //
        // So ask first. `requestAuthorization` is a no-op once the user has
        // actually decided, and it is the ONLY call that registers the app with
        // the notification system in the first place. If it registers or grants,
        // the banner clears itself through the existing didBecomeActive refresh
        // and Settings never needs to open.
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            if settings.authorizationStatus == .notDetermined {
                OshiLog.ui.info("🔔 banner tapped while .notDetermined — asking instead of opening Settings")
                self.requestPermissions { _ in self.refreshAuthorizationStatus() }
                return
            }
            DispatchQueue.main.async {
                guard let url = URL(string: urlString) else { return }
                UIApplication.shared.open(url) { ok in
                    OshiLog.ui.info("🔔 openNotificationSettings → success=\(ok)")
                }
            }
        }
    }
    
    // MARK: - Setup
    
    /// __KEYBOARD_PRIVACY_2026_10_01__ Factored out of `setupNotificationCategories`
    /// so a unit test can assert that, while a keyboard protection is on, "Reply"
    /// is NOT an inline text-input action (typed in SpringBoard, unprotected).
    static func makeReplyAction(
        protected: Bool = KeyboardPrivacy.protectsNotificationReplies
    ) -> UNNotificationAction {
        let replyTitle = NSLocalizedString("notification.action.reply", comment: "Reply to message")
        // __LOCKED_MEANS_LOCKED_IOS_2026_10_28__ `.authenticationRequired` on BOTH
        // branches. Anatole reported this on Android (N12) and it was fixed there on
        // 2026-10-28; iOS was not checked at the time, and it had the same hole.
        //
        // Without it, the shipped MESSAGE_CATEGORY lets anyone holding a LOCKED phone act
        // as its owner: a reply goes out over a channel that authenticates it as genuine,
        // and the recipient has no way to tell. The content was already hidden on the lock
        // screen — the ACTIONS were not.
        //
        // A correctly-authenticated copy of these actions already existed in
        // `PushNotificationManager`, with `.authenticationRequired` on both, and its own
        // comment records that it "n'a aujourd'hui aucun appelant". The right code was in
        // the repository and nothing called it; the live path is this one.
        if protected {
            return UNNotificationAction(
                identifier: "REPLY_ACTION",
                title: replyTitle,
                options: [.foreground, .authenticationRequired]
            )
        }
        return UNTextInputNotificationAction(
            identifier: "REPLY_ACTION",
            title: replyTitle,
            options: [.foreground, .authenticationRequired]
        )
    }

    /// Internal (was private) so Settings › Privacy › Keyboard can re-register the
    /// categories when a keyboard-privacy toggle changes.
    func setupNotificationCategories() {
        // __KEYBOARD_PRIVACY_2026_10_01__
        // WHY: an inline notification reply is typed in SpringBoard's process, not
        // OSHI's, so `shouldAllowExtensionPointIdentifier` (third-party keyboard
        // block) and `privateTypingComposer()` (no autocorrect) do NOT apply to it.
        // While either keyboard protection is on, "Reply" opens the chat in OSHI
        // instead, where the composer is protected. Same REPLY_ACTION id: the
        // handler routes a non-text response to the conversation.
        let replyAction = Self.makeReplyAction()

        // __LOCKED_MEANS_LOCKED_IOS_2026_10_28__ This was `options: []` — no foreground,
        // no authentication — so it ran in the BACKGROUND on a locked phone. Marking read
        // is not a harmless local flag: it sends a read receipt the sender will believe,
        // from a phone its owner was not holding. Same reasoning as the reply above, and
        // the same fix Android took.
        let markReadAction = UNNotificationAction(
            identifier: "MARK_READ_ACTION",
            title: NSLocalizedString("notification.action.mark_read", comment: "Mark message as read"),
            options: [.authenticationRequired]
        )
        
        let category = UNNotificationCategory(
            identifier: "MESSAGE_CATEGORY",
            actions: [replyAction, markReadAction],
            intentIdentifiers: [],
            options: []
        )

        // Check-In warning notification actions
        let safeAction = UNNotificationAction(
            identifier: "CHECKIN_SAFE_ACTION",
            title: NSLocalizedString("checkin.im_safe", comment: "I'm Safe"),
            options: [.foreground]
        )
        let arrivedAction = UNNotificationAction(
            identifier: "CHECKIN_ARRIVED_ACTION",
            title: NSLocalizedString("checkin.arrived", comment: "I've Arrived"),
            options: [.foreground]
        )
        let extendAction = UNNotificationAction(
            identifier: "CHECKIN_EXTEND_ACTION",
            title: NSLocalizedString("checkin.extend_15", comment: "+15 min"),
            options: []
        )
        let checkInCategory = UNNotificationCategory(
            identifier: "CHECKIN_WARNING",
            actions: [safeAction, arrivedAction, extendAction],
            intentIdentifiers: [],
            options: []
        )

        // __CALL_BANNER_CANNOT_ANSWER_2026_08_25__ Un SEUL propriétaire des
        // catégories, et la catégorie d'appel est enregistrée ICI.
        //
        // Deux défauts se cumulaient et rendaient la bannière d'appel entrante
        // inutilisable — « il faut attendre l'écran plein pour décrocher ».
        //
        // 1. `setNotificationCategories` REMPLACE tout le jeu, il n'ajoute pas.
        //    `MeshDirectCallManager` en appelait une seconde à l'arrivée d'un
        //    appel avec `[MESH_CALL]` seul, effaçant MESSAGE_CATEGORY et
        //    CHECKIN_WARNING; et ce setup-ci effaçait MESH_CALL en retour. Le
        //    dernier à s'exécuter gagnait, l'autre perdait ses actions.
        //
        // 2. Les identifiants ne correspondaient PAS. La catégorie déclarait
        //    `ANSWER` / `DECLINE`, alors que `userNotificationCenter(_:didReceive:)`
        //    teste `ACCEPT_CALL` / `DECLINE_CALL` — les deux noms utilisés par
        //    l'app Watch. Appuyer sur Répondre tombait donc dans la branche par
        //    défaut: l'app s'ouvrait, l'appel n'était pas décroché.
        //
        // Les deux se corrigent au même endroit: un seul enregistrement, et les
        // noms alignés sur ceux que le gestionnaire attend réellement.
        let answerCall = UNNotificationAction(
            identifier: "ACCEPT_CALL",
            title: NSLocalizedString("call.action.accept", value: "Answer", comment: "Answer an incoming call"),
            options: [.foreground]
        )
        let declineCall = UNNotificationAction(
            identifier: "DECLINE_CALL",
            title: NSLocalizedString("call.action.decline", value: "Decline", comment: "Decline an incoming call"),
            options: [.destructive]
        )
        let callCategory = UNNotificationCategory(
            identifier: "INCOMING_CALL",
            actions: [answerCall, declineCall],
            intentIdentifiers: []
        )
        // `MESH_CALL` porte les MÊMES actions: une bannière d'appel par le mesh
        // doit se décrocher exactement comme une autre.
        let meshCallCategory = UNNotificationCategory(
            identifier: "MESH_CALL",
            actions: [answerCall, declineCall],
            intentIdentifiers: []
        )
        // 💓 __ALIVE_CHECK_IN_2026_11_04__ The one-tap answer to "your
        // check-in is due". It exists because that reminder arrives at the moment
        // the user is least likely to open an app — the deadline — and a reminder
        // whose only off-ramp is "unlock, find the conversation, scroll, tap" is a
        // reminder that produces missed check-ins.
        //
        // Added to the array below rather than registered separately: this call
        // REPLACES the whole set, and a second one from the alive store would
        // erase the message, check-in and call categories. See
        // `__CALL_BANNER_CANNOT_ANSWER_2026_08_25__` right above.
        let aliveConfirmAction = UNNotificationAction(
            identifier: AliveCheckInStore.confirmActionIdentifier,
            title: "alive.action.im_alive".localized,
            options: []
        )
        let aliveCategory = UNNotificationCategory(
            identifier: AliveCheckInStore.notificationCategory,
            actions: [aliveConfirmAction],
            intentIdentifiers: [],
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories(
            [category, checkInCategory, callCategory, meshCallCategory, aliveCategory]
        )
        OshiLog.ui.info("✅ Notification categories configured")
    }
    
    // MARK: - Permission Management
    
    /// Request notification permissions from user
    func requestPermissions(completion: ((Bool) -> Void)? = nil) {
        #if DEBUG
        // 📸 Screenshot capture: suppress the system notification prompt so it
        // doesn't cover the UI. Release-stripped.
        if ProcessInfo.processInfo.arguments.contains("-OSHIScreens") || MailFixtureHarness.isActive
            || UserDefaults.standard.integer(forKey: "onboardingStartPage") > 0 { completion?(false); return }
        #endif
        OshiLog.ui.info("🔔 \(NSLocalizedString("notification.permission.requesting", comment: "Requesting notification permissions"))")

        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, error in
            DispatchQueue.main.async {
                if granted {
                    OshiLog.ui.info("✅ \(NSLocalizedString("notification.permission.granted", comment: "Permission granted"))")
                    self?.notificationPermissionGranted = true
                    
                    // ✅ Register for remote notifications (APNs)
                    UIApplication.shared.registerForRemoteNotifications()
                    
                    // Send welcome notification
                    self?.sendWelcomeNotification()
                } else if let error = error {
                    OshiLog.ui.info("❌ \(NSLocalizedString("notification.permission.error", comment: "Permission error")): \(error.localizedDescription)")
                    self?.notificationPermissionGranted = false
                } else {
                    OshiLog.ui.info("⚠️ \(NSLocalizedString("notification.permission.denied", comment: "Permission denied"))")
                    self?.notificationPermissionGranted = false
                }
                
                completion?(granted)
            }
        }
    }
    
    /// Check current permission status
    func checkPermissionStatus(completion: ((UNAuthorizationStatus) -> Void)? = nil) {
        UNUserNotificationCenter.current().getNotificationSettings { [weak self] settings in
            DispatchQueue.main.async {
                let granted = settings.authorizationStatus == .authorized
                self?.notificationPermissionGranted = granted
                
                if granted {
                    OshiLog.ui.info("✅ \(NSLocalizedString("notification.status.authorized", comment: "Notifications authorized"))")
                } else {
                    OshiLog.ui.info("⚠️ \(NSLocalizedString("notification.status.not_authorized", comment: "Not authorized")): \(settings.authorizationStatus.rawValue)")
                }
                
                completion?(settings.authorizationStatus)
            }
        }
    }
    
    private func sendWelcomeNotification() {
        let content = UNMutableNotificationContent()
        content.title = NSLocalizedString("notification.welcome.title", comment: "Welcome to OSHI")
        content.body = NSLocalizedString("notification.welcome.body", comment: "Notifications enabled message")
        content.sound = .default
        content.badge = 0
        
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 2, repeats: false)
        let request = UNNotificationRequest(identifier: "welcome", content: content, trigger: trigger)
        
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                OshiLog.ui.info("❌ \(NSLocalizedString("notification.welcome.error", comment: "Failed to schedule welcome")): \(error)")
            } else {
                OshiLog.ui.info("✅ \(NSLocalizedString("notification.welcome.scheduled", comment: "Welcome notification scheduled"))")
            }
        }
    }
    
    // MARK: - Security: linked-device alert

    /// Warn the user, loudly, that a new device was linked to their account.
    ///
    /// This is the surfacing half of `LinkedDeviceWatch`. The watch DETECTS a device that
    /// appeared since the last check; on its own that only wrote a log line the user never
    /// sees, which is no defence at all — the covert companion-device method (a controlled
    /// computer linked to a victim's account, no encryption broken) works precisely because
    /// the victim is never told. This delivers that "you were told" as a real, time-sensitive
    /// local notification so it fires even when the app was woken in the background by the
    /// pairing push. Tapping it deep-links to the linked-devices screen so revoking is one tap.
    func sendLinkedDeviceAlert(count: Int) {
        let content = UNMutableNotificationContent()
        content.title = NSLocalizedString("notification.linkedDevice.title",
                                          comment: "New device linked warning title")
        content.body = count > 1
            ? String(format: NSLocalizedString("notification.linkedDevice.body.plural",
                                               comment: "N new devices linked"), count)
            : NSLocalizedString("notification.linkedDevice.body",
                                comment: "A new device linked to your account")
        content.sound = .default
        content.categoryIdentifier = "LINKED_DEVICE"
        content.userInfo = ["deepLink": "linkedDevices"]
        if #available(iOS 15.0, *) { content.interruptionLevel = .timeSensitive }

        // Fire immediately; a security warning must not sit behind a delay.
        let request = UNNotificationRequest(
            identifier: "linked-device-\(Date().timeIntervalSince1970)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                OshiLog.ui.info("❌ Failed to schedule linked-device alert: \(error)")
            } else {
                OshiLog.ui.info("🔔 Linked-device security alert delivered (\(count))")
            }
        }
    }

    // MARK: - Send Message Notifications

    /// Send notification for new message (main entry point)
    func sendMessageNotification(
        from senderAddress: String,
        messageText: String,
        messageId: String,
        conversationId: String
    ) {
        // 🔧 FIX: Skip the rich local notification if the app is active —
        // BUT still sweep the stale generic remote-push banner ("Vous avez un
        // nouveau message chiffré" / "You have a new encrypted message") that
        // iOS already displayed via `willPresent`. Without this sweep, the
        // generic banner lingered in Notification Center even after the user
        // had read the decrypted message in the chat — exactly the "IPFS
        // message" / "Message chiffré" notification users were seeing stick.
        // The cleanup keys on `messageId` in userInfo, the same dedup logic the
        // background path uses inside createNotification().
        guard UIApplication.shared.applicationState != .active else {
            OshiLog.ui.info("⏭️ Notification skipped - app is active")
            let center = UNUserNotificationCenter.current()
            center.getDeliveredNotifications { delivered in
                let staleIds = delivered.compactMap { notif -> String? in
                    let info = notif.request.content.userInfo
                    if let mid = info["messageId"] as? String, mid == messageId {
                        return notif.request.identifier
                    }
                    return nil
                }
                if !staleIds.isEmpty {
                    center.removeDeliveredNotifications(withIdentifiers: staleIds)
                    OshiLog.ui.info("🧹 Foreground: cleared \(staleIds.count) stale generic banner(s) for messageId=\(messageId.prefix(8))...")
                }
            }
            return
        }

        // __ANATOLE_1311_IOS__ (N8) Conversation muted from the chat list. The
        // message is stored and counted as usual; only the banner is skipped.
        if ConversationPrefsStore.shared.isMuted(senderAddress)
            || ConversationPrefsStore.shared.isMuted(conversationId) {
            OshiLog.ui.info("🔕 Notification skipped - conversation muted")
            return
        }

        // 🛡️ Skip notifications for check-in location UPDATES (silent updates)
        if messageText.hasPrefix("\u{1F6E1}\u{FE0F}CHECK_IN\u{1F6E1}\u{FE0F}"),
           let checkIn = CheckInMessage.fromMessageString(messageText),
           checkIn.type == .update {
            OshiLog.ui.info("🛡️ Skipping notification for silent check-in location update")
            return
        }

        // 📍 __LOCATION_PING_SPAM_2026_08_24__ Now asks the SAME question as the
        // push path (FallbackService), through the one helper that owns it.
        //
        // The local check used to live here as a private `LocationUpdateCheck`
        // decoded with `try?`, and it differed from the shared rule in two ways
        // that both mattered. It silenced on `isUpdate` ALONE — and `stopped()`
        // is built with `isUpdate: true`, so the end of a share raised no banner
        // either. And a decode that threw fell through to NOTIFY, so any payload
        // shape it did not expect became a banner.
        if LocationMessage.isSilentPushContent(messageText) {
            OshiLog.ui.info("📍 Skipping notification for silent live location ping")
            return
        }
        
        // Check if notifications are authorized
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized else {
                OshiLog.ui.info("⚠️ \(NSLocalizedString("notification.skipped.not_authorized", comment: "Skipping - not authorized"))")
                return
            }
            
            DispatchQueue.main.async {
                self.createNotification(
                    from: senderAddress,
                    messageText: messageText,
                    messageId: messageId,
                    conversationId: conversationId
                )
            }
        }
    }
    
    // Helper struct for checking if location message is an update
    /// Alternative method with sender name
    func sendNotification(from senderName: String, preview: String) {
        sendMessageNotification(
            from: senderName,
            messageText: preview,
            messageId: UUID().uuidString,
            conversationId: senderName
        )
    }
    
    private func createNotification(
        from senderAddress: String,
        messageText: String,
        messageId: String,
        conversationId: String
    ) {
        // __ONE_BANNER_PER_MESSAGE_2026_09_23__ Titre = nom/alias du contact,
        // corps = « Nouveau message chiffré » localisé. JAMAIS le texte (sauf si
        // le réglage caché `showMessagePreviews` a été posé explicitement à true —
        // aucune UI ne l'écrit; le défaut était `true`, il est désormais `false`).
        let legacyShowSender = UserDefaults.standard.object(forKey: "showSenderName") != nil
            ? UserDefaults.standard.bool(forKey: "showSenderName") : true
        // __NOTIFICATION_NAMES_HIDDEN_2026_11_20__ the visible "Show Preview" toggle.
        let shouldShowSender = legacyShowSender
            && MessageBannerPolicy.showsNames(UserDefaults.standard)
        let senderName = formatSenderName(senderAddress)

        let content = UNMutableNotificationContent()
        content.title = shouldShowSender
            ? senderName
            : NSLocalizedString("notification.message.title.generic", comment: "New Message")
        content.body = MessageBannerPolicy.showsMessageText()
            ? formatNotificationText(messageText)
            : NSLocalizedString(MessageBannerPolicy.genericBodyKey, comment: "You have a new encrypted message")

        // Add sound - use critical for check-in emergencies
        if messageText.hasPrefix("\u{1F6E1}\u{FE0F}CHECK_IN\u{1F6E1}\u{FE0F}"),
           let checkIn = CheckInMessage.fromMessageString(messageText),
           checkIn.type == .emergency {
            content.sound = UNNotificationSound.defaultCritical
        } else {
            content.sound = .default
        }
        content.categoryIdentifier = "MESSAGE_CATEGORY"
        content.threadIdentifier = senderAddress
        content.userInfo = [
            "messageId": messageId,
            "conversationId": conversationId,
            "senderAddress": senderAddress,
            "senderPublicKey": senderAddress,
            "type": "message",
            MessageBannerPolicy.localMarkerKey: true
        ]

        // Badge only when the banner is really posted (see postLocalMessageBanner).
        postLocalMessageBanner(content: content,
                               identifier: messageId,
                               messageId: messageId,
                               bumpBadge: true,
                               logTag: "1:1")
    }

    /// __ONE_BANNER_PER_MESSAGE_2026_09_23__ Pose une bannière de message LOCALE
    /// seulement si aucune bannière DISTANTE (APNs, déjà décorée par l'extension)
    /// ne couvre ce message ou cette conversation.
    ///
    /// Avant, on RETIRAIT la bannière APNs pour la remplacer par une « riche ».
    /// Deux défauts: le `messageId` du push n'est souvent pas celui du message
    /// (repli serveur, bot) donc rien n'était retiré, et quand l'app passait
    /// AVANT l'extension la distante arrivait ensuite — deux bannières dans les
    /// deux cas, dont une avec le texte en clair.
    ///
    /// Le délai de grâce laisse à l'extension le temps de rendre la distante
    /// (le push réveille l'app ET l'extension en parallèle). L'extension fait le
    /// test symétrique (`localBannerCovers`) pour le cas où la locale gagne.
    /// Identifiant = messageId: une re-pose du même message REMPLACE au lieu
    /// d'empiler.
    private func postLocalMessageBanner(content: UNMutableNotificationContent,
                                        identifier: String,
                                        messageId: String,
                                        bumpBadge: Bool,
                                        logTag: String) {
        let convKey = MessageBannerPolicy.conversationKey(content.userInfo)
        var bgTask: UIBackgroundTaskIdentifier = .invalid
        bgTask = UIApplication.shared.beginBackgroundTask(withName: "oshi.localBanner") {
            if bgTask != .invalid { UIApplication.shared.endBackgroundTask(bgTask); bgTask = .invalid }
        }
        let finish = {
            DispatchQueue.main.async {
                if bgTask != .invalid { UIApplication.shared.endBackgroundTask(bgTask); bgTask = .invalid }
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.localBannerGrace) {
            let center = UNUserNotificationCenter.current()
            center.getDeliveredNotifications { delivered in
                let snapshot = delivered.map {
                    MessageBannerPolicy.Delivered(identifier: $0.request.identifier,
                                                  userInfo: $0.request.content.userInfo,
                                                  date: $0.date)
                }
                if MessageBannerPolicy.remoteBannerCovers(messageId: messageId,
                                                          conversationKey: convKey,
                                                          delivered: snapshot) {
                    OshiLog.ui.info("🔕 [\(logTag)] local banner skipped — APNs banner already covers \(messageId.prefix(8))")
                    CallFileLogger.shared.log("DIAG_BANNER | \(logTag) | skip_local_remote_covers | id=\(messageId.prefix(8))")
                    finish()
                    return
                }
                DispatchQueue.main.async {
                    if bumpBadge {
                        let newBadge = UserDefaults.standard.integer(forKey: "appBadgeCount") + 1
                        UserDefaults.standard.set(newBadge, forKey: "appBadgeCount")
                        content.badge = NSNumber(value: newBadge)
                        // Active: the truth is the unread counts, not a running tally.
                        if UIApplication.shared.applicationState == .active {
                            MessageManager.shared?.syncAppBadge()
                        }
                    }
                    // __NOTIFICATION_HAS_NO_FACE_2026_10_27__ Owner report N7, and it
                    // was true on iOS as well: a message banner carried no attachment,
                    // so Notification Centre never showed the sender although the photo
                    // was already decoded and cached for the conversation list. Done
                    // here rather than in `createNotification` for two reasons: this
                    // block is on the main queue, which `ContactPresenceManager`'s
                    // decode cache requires, and it covers the group path too.
                    if let key = content.userInfo["senderPublicKey"] as? String,
                       let attachment = Self.avatarAttachment(forPublicKey: key,
                                                              identifier: identifier) {
                        content.attachments = [attachment]
                    }
                    let request = UNNotificationRequest(identifier: identifier, content: content, trigger: nil)
                    center.add(request) { error in
                        if let error {
                            OshiLog.ui.info("❌ [\(logTag)] local banner add failed: \(error.localizedDescription)")
                        } else {
                            // Security: never log title/body.
                            CallFileLogger.shared.log("DIAG_BANNER | \(logTag) | posted_local | id=\(messageId.prefix(8))")
                        }
                        finish()
                    }
                }
            }
        }
    }

    /// Délai avant de décider de poser une locale (voir `postLocalMessageBanner`).
    static var localBannerGrace: TimeInterval = 2.0

    /// __NOTIFICATION_HAS_NO_FACE_2026_10_27__ The sender's photo as a banner
    /// attachment, or nil when they have none.
    ///
    /// `UNNotificationAttachment` only takes a FILE URL, so the cached image is
    /// written out first. The initialiser then takes ownership and moves the file
    /// into the attachment store, which is why nothing here cleans up — and why
    /// the name carries the notification identifier rather than the contact's
    /// key: two banners must never race on one path.
    ///
    /// Any failure returns nil. A missing face must never cost the notification.
    static func avatarAttachment(forPublicKey publicKey: String,
                                 identifier: String) -> UNNotificationAttachment? {
        guard let image = ContactPresenceManager.shared.getProfileImage(publicKey: publicKey),
              let png = image.pngData() else { return nil }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("oshi-banner-avatars", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let safe = identifier.replacingOccurrences(of: "/", with: "_")
            let url = dir.appendingPathComponent("\(safe).png")
            try png.write(to: url, options: .atomic)
            return try UNNotificationAttachment(identifier: "avatar-\(safe)",
                                                url: url, options: nil)
        } catch {
            return nil
        }
    }

    // MARK: - Text Formatting
    
    /// Format notification text for special content types (call summaries, media, etc.)
    private func formatNotificationText(_ text: String) -> String {
        // Handle call summaries: 📱CALL_SUMMARY📱📞↗️call.outgoing|1:23
        if text.hasPrefix("📱CALL_SUMMARY📱") {
            let content = text.replacingOccurrences(of: "📱CALL_SUMMARY📱", with: "")
            return localizeCallContent(content)
        }
        
        // Handle missed calls: 📱MISSED_CALL📱❌call.missed
        if text.hasPrefix("📱MISSED_CALL📱") {
            let content = text.replacingOccurrences(of: "📱MISSED_CALL📱", with: "")
            return localizeCallContent(content)
        }
        
        // Handle call signals (shouldn't show in notifications)
        if text.hasPrefix("📞CALL_SIGNAL📞") {
            return NSLocalizedString("call.state.connecting", comment: "Connecting")
        }
        
        // Handle scheduled calls
        if text.hasPrefix("📅SCHEDULED_CALL📅") {
            return NSLocalizedString("event.call.scheduled", comment: "Scheduled Call")
        }
        
        // 📍 Handle location messages - show clean text without JSON
        if text.hasPrefix("📍LOCATION📍") {
            // Try to parse the location for a nice display
            if let jsonData = text.dropFirst("📍LOCATION📍".count).data(using: .utf8),
               let locationDict = try? JSONDecoder().decode(LocationNotificationData.self, from: jsonData) {
                if locationDict.isLive {
                    return "📍 " + NSLocalizedString("location.sharing_live", comment: "Sharing live location")
                } else if let address = locationDict.address, !address.isEmpty {
                    // Show short address
                    let shortAddress = address.components(separatedBy: ",").first ?? address
                    return "📍 " + String(shortAddress.prefix(40))
                }
            }
            return "📍 " + NSLocalizedString("location.shared_location", comment: "Shared location")
        }
        
        // 🛡️ Handle Check-In messages - show friendly text instead of JSON
        if text.hasPrefix("\u{1F6E1}\u{FE0F}CHECK_IN\u{1F6E1}\u{FE0F}") {
            if let checkIn = CheckInMessage.fromMessageString(text) {
                switch checkIn.type {
                case .started:
                    let dest = checkIn.destinationAddress ?? ""
                    return "\u{1F6E1}\u{FE0F} " + String(format: NSLocalizedString("checkin.notification.started", comment: "Check-In started to %@"), dest)
                case .arrived:
                    return "\u{2705} " + NSLocalizedString("checkin.notification.arrived", comment: "Arrived safely!")
                case .warning:
                    return "\u{26A0}\u{FE0F} " + NSLocalizedString("checkin.notification.warning", comment: "Hasn't arrived yet")
                case .emergency:
                    return "\u{1F6A8} " + NSLocalizedString("checkin.notification.emergency", comment: "Emergency! Location shared")
                case .extended:
                    return "\u{23F0} " + NSLocalizedString("checkin.notification.extended", comment: "Extended check-in time")
                case .cancelled:
                    return "\u{274C} " + NSLocalizedString("checkin.notification.cancelled", comment: "Check-In cancelled")
                case .update:
                    return "\u{1F4CD} " + NSLocalizedString("checkin.notification.update", comment: "Location update")
                }
            }
            return "\u{1F6E1}\u{FE0F} " + NSLocalizedString("checkin.title", comment: "Check-In")
        }

        // 🔧 Handle message actions (delete/edit/pin sync) - don't show
        if text.hasPrefix("\u{1F527}ACTION\u{1F527}") {
            return NSLocalizedString("notification.message.body.generic", comment: "You have a new message")
        }

        // 🔥 Handle reaction messages - don't show raw JSON (both iOS and Android prefixes)
        if text.hasPrefix("🔥REACTION🔥") || text.hasPrefix("👍 REACTION👍") {
            return "🔥 " + NSLocalizedString("notification.reaction", comment: "Reacted to a message")
        }
        
        // Handle media
        if text.hasPrefix("MEDIA|||") {
            let components = text.components(separatedBy: "|||")
            if components.count >= 2 {
                let mediaType = components[1].lowercased()
                if mediaType == "image" {
                    return "📷 " + NSLocalizedString("media_image", comment: "Image")
                } else {
                    return "🎥 " + NSLocalizedString("media_video", comment: "Video")
                }
            }
            return "📎 " + NSLocalizedString("media_file", comment: "Media")
        }

        // 🔧 RAW-JSON FIX: `return text` was the catch-all, and a notification
        // body is the ONE surface the user cannot dismiss unseen — it lands on
        // the lock screen. Everything the chain above does not name arrived
        // here verbatim: `💬REPLY💬{…}` (the whole envelope, on every reply),
        // `➡️FORWARDED➡️{…}`, `📸PROFILE_UPDATE📸{…}`, `🎨WALLPAPER_UPDATE🎨{…}`,
        // `🔗LINKS_VISIBILITY🔗{…}`, both receipt markers, both typing
        // spellings, `🛡️CHECKIN🛡️` (the no-underscore Android spelling) and
        // `🔑KEY_ROTATION🔑`.
        //
        // A silent payload must not produce a banner at all; it should never
        // reach this method, but if it does, the generic body is shown rather
        // than the marker.
        if OSHIControlPayload.isSilent(text) {
            return NSLocalizedString("notification.message.body.generic", comment: "You have a new message")
        }
        // __TEXT_FORMATTING_2026_11_24__ a banner cannot style a run: no markers.
        return InlineMarkup.stripped(OSHIControlPayload.previewText(for: text))
    }
    
    // Helper struct for location notification parsing
    private struct LocationNotificationData: Decodable {
        let isLive: Bool
        let address: String?
    }
    
    /// Localize call content that contains localization keys
    private func localizeCallContent(_ content: String) -> String {
        let text = content
        
        // Handle completed calls with duration: "📞↗️call.outgoing|0:45"
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
                
                // Localize the key if it looks like a localization key
                if key.contains(".") {
                    let localizedType = NSLocalizedString(key, comment: "")
                    return "\(icon) \(localizedType) • \(duration)"
                }
                return "\(icon) \(key) • \(duration)"
            }
        }
        
        // Handle missed/no answer: "📞↗️call.no.answer" or "❌call.missed"
        let icons = ["📞↗️", "📞↙️", "❌"]
        for icon in icons {
            if text.hasPrefix(icon) {
                let key = String(text.dropFirst(icon.count))
                if key.contains(".") {
                    let localizedType = NSLocalizedString(key, comment: "")
                    return "\(icon) \(localizedType)"
                }
            }
        }
        
        // Check if whole content is a localization key
        if text.contains(".") && !text.contains(" ") {
            return NSLocalizedString(text, comment: "")
        }
        
        return text
    }
    
    // MARK: - Group Notifications
    
    func sendGroupMessageNotification(
        groupName: String,
        senderName: String,
        messageText: String,
        groupId: String,
        messageId: String
    ) {
        debugLog("sendGroupMessageNotification CALLED: id=\(messageId.prefix(8))")
        CallFileLogger.shared.log("DIAG_GROUP_NOTIF | group=\(groupName.prefix(20)) | sender=\(senderName.prefix(20)) | id=\(messageId.prefix(8))")

        // 🔧 FIX (2026-06-03): parity with 1:1 sendMessageNotification —
        // (a) skip if the app is .active (avoids stacking a rich local banner
        //     on top of the generic remote-push banner iOS already displayed),
        // (b) still sweep the stale generic remote banner from Notification
        //     Center so it doesn't linger once the user is reading in-app, and
        //     (c) gate on notification permission so a denied authorization
        //     doesn't silently fail later inside add(_:).
        if UIApplication.shared.applicationState == .active {
            OshiLog.ui.info("⏭️ Group notification skipped — app is active")
            let center = UNUserNotificationCenter.current()
            center.getDeliveredNotifications { delivered in
                let staleIds = delivered.compactMap { notif -> String? in
                    let info = notif.request.content.userInfo
                    if let mid = info["messageId"] as? String {
                        return mid == messageId ? notif.request.identifier : nil
                    }
                    // Same reasoning as the sweep in `scheduleGroupNotification`:
                    // a generic v2 group push carries no message id, and the user
                    // is reading this group in the foreground right now.
                    if info["groupId"] as? String == groupId {
                        return notif.request.identifier
                    }
                    return nil
                }
                if !staleIds.isEmpty {
                    center.removeDeliveredNotifications(withIdentifiers: staleIds)
                    CallFileLogger.shared.log("DIAG_GROUP_NOTIF | foreground cleared \(staleIds.count) stale banner(s) for \(messageId.prefix(8))")
                }
            }
            return
        }

        UNUserNotificationCenter.current().getNotificationSettings { [weak self] settings in
            guard settings.authorizationStatus == .authorized else {
                self?.debugLog("Group notification skipped — not authorized (status=\(settings.authorizationStatus.rawValue))")
                CallFileLogger.shared.log("DIAG_GROUP_NOTIF | skipped not_authorized status=\(settings.authorizationStatus.rawValue)")
                return
            }
            DispatchQueue.main.async {
                self?.scheduleGroupNotification(
                    groupName: groupName, senderName: senderName,
                    messageText: messageText, groupId: groupId, messageId: messageId
                )
            }
        }
    }

    /// Internal: actually schedule the group notification. Extracted from
    /// sendGroupMessageNotification so the auth-gated path stays readable.
    private func scheduleGroupNotification(
        groupName: String,
        senderName: String,
        messageText: String,
        groupId: String,
        messageId: String
    ) {
        // __ONE_BANNER_PER_MESSAGE_2026_09_23__ Titre = groupe, sous-titre =
        // alias de l'expéditeur, corps = générique localisé. JAMAIS le texte
        // (c'était « <expéditeur>: <texte> » — la 2e bannière signalée).
        let content = UNMutableNotificationContent()
        // __NOTIFICATION_NAMES_HIDDEN_2026_11_20__ "Show Preview" off: neither
        // the group's name nor the sender's reaches the lock screen.
        if MessageBannerPolicy.showsNames(UserDefaults.standard) {
            content.title = groupName
            if !senderName.isEmpty { content.subtitle = senderName }
        } else {
            content.title = NSLocalizedString("notification.message.title.generic", comment: "New Message")
        }
        content.body = MessageBannerPolicy.showsMessageText()
            ? formatNotificationText(messageText)
            : NSLocalizedString(MessageBannerPolicy.genericBodyKey, comment: "You have a new encrypted message")
        content.sound = .default
        content.threadIdentifier = groupId.uppercased()
        content.categoryIdentifier = "MESSAGE_CATEGORY"
        content.userInfo = [
            "messageId": messageId,
            "conversationId": groupId,
            "groupId": groupId,
            "type": "group_message",
            MessageBannerPolicy.localMarkerKey: true
        ]
        postLocalMessageBanner(content: content,
                               identifier: messageId,
                               messageId: messageId,
                               bumpBadge: true,
                               logTag: "group")
    }

    // MARK: - Helper Methods
    
    private func formatSenderName(_ address: String) -> String {
        // __SHARED_NICKNAME_2026_09_22__ Le titre de la bannière suit la règle
        // d'affichage du reste de l'app: alias local > pseudo partagé par le
        // contact. Avant, il montrait TOUJOURS `abcdef...wxyz`, même pour un
        // contact nommé. (Le réglage `showSenderName` s'applique toujours en aval.)
        if let name = MessageManager.lookupContactDisplayName(address) {
            return name
        }
        // __MESHCORE_INTEROP_2026_11_25__ A MeshCore person or channel, or a LoRa room: its name, never the id.
        if let name = MeshCoreInterop.threadTitle(forKey: address) { return name }
        if LoRaChannelThread.isChannelKey(address) { return LoRaChannelThread.displayName(address) }
        // Show first 6 and last 4 characters
        if address.count > 10 {
            return "\(address.prefix(6))...\(address.suffix(4))"
        }
        return address
    }
    
    // ✅ iOS 17+ compatible badge count getter
    private func getCurrentBadgeCount(completion: @escaping (Int) -> Void) {
        if #available(iOS 17.0, *) {
            // Use stored badge count for iOS 17+
            let storedBadge = UserDefaults.standard.integer(forKey: "appBadgeCount")
            DispatchQueue.main.async {
                completion(storedBadge)
            }
        } else {
            // Use older API for iOS 16 and below
            DispatchQueue.main.async {
                let badge = UIApplication.shared.applicationIconBadgeNumber
                completion(badge)
            }
        }
    }
    
    // MARK: - Badge Management
    
    func updateBadgeCount(_ count: Int) {
        // Store badge count for iOS 17+
        UserDefaults.standard.set(count, forKey: "appBadgeCount")
        
        // ✅ iOS 17+ compatible badge update (FIXED: No trailing closure syntax)
        if #available(iOS 17.0, *) {
            UNUserNotificationCenter.current().setBadgeCount(count, withCompletionHandler: { error in
                if let error = error {
                    OshiLog.ui.info("❌ \(NSLocalizedString("notification.badge.error", comment: "Failed to update badge")): \(error)")
                } else {
                    OshiLog.ui.info("✅ \(NSLocalizedString("notification.badge.updated", comment: "Badge updated")): \(count)")
                }
            })
        } else {
            // Fallback for iOS 16 and below
            DispatchQueue.main.async {
                UIApplication.shared.applicationIconBadgeNumber = count
            }
        }
    }
    
    func incrementBadge() {
        getCurrentBadgeCount { current in
            self.updateBadgeCount(current + 1)
        }
    }
    
    func clearBadge() {
        updateBadgeCount(0)
        OshiLog.ui.info("🔕 \(NSLocalizedString("notification.badge.cleared", comment: "Badge cleared"))")
    }
    
    // MARK: - Clear Notifications
    
    func clearAllNotifications() {
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        clearBadge()
        OshiLog.ui.info("🔕 \(NSLocalizedString("notification.cleared.all", comment: "Cleared all notifications"))")
    }
    
    func clearNotification(for messageId: String) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [messageId])
        OshiLog.ui.info("🔕 \(NSLocalizedString("notification.cleared.single", comment: "Cleared notification")): \(messageId.prefix(8))...")
    }

    /// __PHANTOM_BANNER_2026_10_11__ Retire les bannières (livrées ET encore
    /// programmées) qui annoncent un de ces messages.
    ///
    /// Appelé quand l'app conclut qu'un message ne peut PAS être affiché — garé
    /// en attente de déchiffrement après un désync du ratchet. La bannière a déjà
    /// sonné (l'extension la dessine sans déchiffrer, par construction), donc la
    /// laisser en place revient à affirmer qu'il y a quelque chose à lire.
    /// Règle dans `MessageBannerPolicy.deliveredMatching`, partagée avec les tests.
    func withdrawBanners(forMessageIds ids: [String]) {
        let wanted = Set(ids.filter { !$0.isEmpty })
        guard !wanted.isEmpty else { return }
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: Array(wanted))
        center.getDeliveredNotifications { delivered in
            let victims = MessageBannerPolicy.deliveredMatching(
                messageIds: wanted,
                delivered: delivered.map {
                    MessageBannerPolicy.Delivered(identifier: $0.request.identifier,
                                                  userInfo: $0.request.content.userInfo,
                                                  date: $0.date)
                })
            guard !victims.isEmpty else { return }
            center.removeDeliveredNotifications(withIdentifiers: victims)
            OshiLog.ui.info("🔕 Withdrew \(victims.count) phantom banner(s) for message(s) the app cannot display yet")
        }
    }

    /// Remove any delivered banners for a conversation when the user reads it.
    /// The only broad clear fires on app *activation* (SceneDelegate), so a
    /// foreground banner for a thread the user then opens would otherwise linger
    /// in Notification Center as a stale banner for an already-read message.
    /// Matches by the sender/conversation keys set in the message userInfo.
    func clearDeliveredNotifications(forConversation participantPublicKey: String) {
        guard !participantPublicKey.isEmpty else { return }
        // __ANATOLE_1311_IOS__ (N10) Matched through `MessageBannerPolicy
        // .conversationKey`, the rule the extension already uses, so a banner
        // whose key arrived base64url-spelled is cleared too. Group banners
        // carry a `groupId` and are never matched here.
        clearDelivered(matchingConversationKey: "c:" + MessageBannerPolicy.normalizedKey(participantPublicKey),
                       logLabel: "conversation \(participantPublicKey.prefix(8))…")
    }

    /// __ANATOLE_1311_IOS__ (N10) Same, for a group the user has just read.
    /// Before this a group's banners only ever left Notification Centre with
    /// the blanket clear on app activation, which is gone.
    func clearDeliveredNotifications(forGroup groupId: String) {
        let gid = groupId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !gid.isEmpty else { return }
        clearDelivered(matchingConversationKey: "g:" + gid.uppercased(),
                       logLabel: "group \(gid.prefix(8))…")
    }

    private func clearDelivered(matchingConversationKey wanted: String, logLabel: String) {
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { delivered in
            let ids = delivered.filter { n in
                MessageBannerPolicy.conversationKey(n.request.content.userInfo) == wanted
            }.map { $0.request.identifier }
            guard !ids.isEmpty else { return }
            center.removeDeliveredNotifications(withIdentifiers: ids)
            OshiLog.ui.info("🔕 Cleared \(ids.count) delivered banner(s) for read \(logLabel)")
        }
    }
    
    // MARK: - Test Notification (Debug only)
    
    #if DEBUG
    func sendTestNotification() {
        let content = UNMutableNotificationContent()
        content.title = NSLocalizedString("notification.test.title", comment: "Test Notification")
        content.body = NSLocalizedString("notification.test.body", comment: "This is a test notification")
        content.sound = .default
        content.badge = 1
        
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        let request = UNNotificationRequest(
            identifier: "test-\(UUID().uuidString)",
            content: content,
            trigger: trigger
        )
        
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                OshiLog.ui.info("❌ \(NSLocalizedString("notification.test.error", comment: "Test failed")): \(error)")
            } else {
                OshiLog.ui.info("✅ \(NSLocalizedString("notification.test.success", comment: "Test sent"))")
            }
        }
    }
    
    func printPermissionStatus() {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            OshiLog.ui.info("📋 \(NSLocalizedString("notification.status.title", comment: "Permission Status")):")
            OshiLog.ui.info("   \(NSLocalizedString("notification.status.authorization", comment: "Authorization")): \(settings.authorizationStatus.rawValue)")
            OshiLog.ui.info("   0 = \(NSLocalizedString("notification.status.not_determined", comment: "Not Determined"))")
            OshiLog.ui.info("   1 = \(NSLocalizedString("notification.status.denied", comment: "Denied"))")
            OshiLog.ui.info("   2 = \(NSLocalizedString("notification.status.authorized", comment: "Authorized"))")
            OshiLog.ui.info("   3 = \(NSLocalizedString("notification.status.provisional", comment: "Provisional"))")
            OshiLog.ui.info("   4 = \(NSLocalizedString("notification.status.ephemeral", comment: "Ephemeral"))")
            OshiLog.ui.info("   \(NSLocalizedString("notification.status.alert", comment: "Alert")): \(settings.alertSetting.rawValue)")
            OshiLog.ui.info("   \(NSLocalizedString("notification.status.badge", comment: "Badge")): \(settings.badgeSetting.rawValue)")
            OshiLog.ui.info("   \(NSLocalizedString("notification.status.sound", comment: "Sound")): \(settings.soundSetting.rawValue)")
        }
    }
    #endif
}

// MARK: - Pending Deep Link (cold-start safe)

/// Holds the deep-link target from a notification tap until the SwiftUI view tree
/// mounts and can consume it. Prevents the race where `NotificationDelegate.didReceive`
/// fires before `MainTabView.onReceive` subscribes (cold launch).
final class PendingNotificationDeepLink {
    static let shared = PendingNotificationDeepLink()
    private init() {}

    enum Target {
        case peer(publicKey: String)
        case group(groupId: String)
        /// A new-email tap. `messageId` is the mailbox id to open; nil means the
        /// push carried no id, so the tap lands on the Inbox without pushing a
        /// specific message.
        case mail(messageId: String?)
    }

    private(set) var target: Target?
    /// 🔧 2026-06-09: when set() was last called, regardless of whether the
    /// target has been consumed yet. ChatView uses this (via
    /// `wasTappedRecently(for:within:)`) to decide whether to show the
    /// "Loading new message…" pill — i.e. we know iOS just brought us
    /// forward from a tap, so a new message is almost certainly inbound
    /// but the IPFS fetch + decrypt hasn't completed yet.
    private(set) var lastTappedAt: Date?
    /// Mirror of the last target value — survives `consume()` so views
    /// that appear AFTER the consume call can still tell which chat the
    /// tap was for.
    private(set) var lastTappedTarget: Target?

    func set(_ target: Target) {
        self.target = target
        self.lastTappedAt = Date()
        self.lastTappedTarget = target
    }

    /// Consume the pending target (returns it and clears the slot).
    /// `lastTappedAt` and `lastTappedTarget` are preserved for the recency
    /// query below.
    func consume() -> Target? {
        let t = target
        target = nil
        return t
    }

    /// True if the user tapped a notification within the window AND the
    /// target was a peer matching `peerPublicKey`. Used by ChatView to show
    /// a transient "Loading new message…" pill on open.
    func wasTappedRecently(forPeer peerPublicKey: String, within seconds: TimeInterval = 5) -> Bool {
        guard let last = lastTappedAt, Date().timeIntervalSince(last) < seconds else { return false }
        if case let .peer(pk) = lastTappedTarget, pk == peerPublicKey { return true }
        return false
    }

    /// True if the user tapped a notification within the window AND the
    /// target was a group matching `groupId`. Used by GroupChatView for the
    /// same "Loading new message…" pill UX as 1-to-1.
    func wasTappedRecently(forGroup groupId: String, within seconds: TimeInterval = 5) -> Bool {
        guard let last = lastTappedAt, Date().timeIntervalSince(last) < seconds else { return false }
        if case let .group(gid) = lastTappedTarget, gid == groupId { return true }
        return false
    }
}

// MARK: - Notification Delegate

class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    
    // ✅ Handle notification when app is in foreground
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        OshiLog.ui.info("📬 \(NSLocalizedString("notification.received.foreground", comment: "Received while app in foreground"))")

        let userInfo = notification.request.content.userInfo
        let notificationType = userInfo["type"] as? String ?? ""

        // 🔧 DIAG (2026-06-03): every foreground push delivery shows here so
        // call_debug.log lets us correlate "I sent a message but the other
        // device didn't get a notification" against actual APNs delivery vs
        // local-rich-notif scheduling.
        let mid = (userInfo["messageId"] as? String) ?? "?"
        CallFileLogger.shared.log("DIAG_PUSH_RECV | fg | type=\(notificationType) | mid=\(mid.prefix(8)) | identifier=\(notification.request.identifier.prefix(20))")
        let categoryId = notification.request.content.categoryIdentifier
        let aps = userInfo["aps"] as? [String: Any]
        let apsCategory = aps?["category"] as? String ?? ""
        let body = (aps?["alert"] as? [String: Any])?["body"] as? String
            ?? aps?["alert"] as? String ?? ""
        let title = (aps?["alert"] as? [String: Any])?["title"] as? String ?? ""

        // 📞 Suppress banner for any call-related notification — full-screen IncomingCallOverlay handles these
        // Covers: explicit type=call, INCOMING_CALL category, OR an active CallKit call already in progress.
        let looksLikeCall = notificationType == "call"
            || categoryId == "INCOMING_CALL"
            || apsCategory == "INCOMING_CALL"
            || userInfo["callerPublicKey"] != nil
            || userInfo["callId"] != nil
            || title.lowercased().contains("call")
            || body.lowercased().contains("incoming call")
        let callInProgress = VoIPPushManager.shared.hasActiveCallUUID
        // __MAIL_UNREAD_REFRESH_2026_11_22__ New mail announced while open: fetch it so the
        // Email badge moves with the banner instead of waiting for the Email screen.
        if notificationType == "mail" {
            NotificationCenter.default.post(name: .oshiMailPushReceived, object: nil)
        }
        if looksLikeCall || callInProgress {
            OshiLog.ui.info("📞 Suppressing call notification banner — using full-screen overlay instead")
            completionHandler([])  // No banner, no sound (ringtone plays from call UI)
            return
        }

        // __ONE_NOTICE_IN_FOREGROUND_2026_11_09__ App open + a chat message:
        // the in-app banner (DynamicIslandNotifier) is the one notice, so the
        // system banner must not slide over it. Kept in Notification Centre
        // (list) so nothing is lost; the message-arrival sweep clears it.
        // Rule and tests: `ForegroundNoticePolicy`.
        let foregroundPresentation = ForegroundNoticePolicy.systemPresentation(
            type: userInfo["type"] as? String,
            looksLikeCall: looksLikeCall || callInProgress,
            appActive: UIApplication.shared.applicationState == .active)
        if foregroundPresentation == .listOnly {
            if notificationType == "group_message" || notificationType == "group" || notificationType == "message" || notificationType.isEmpty {
                MessageManager.shared?.checkPendingIPFSMessages()
            }
            CallFileLogger.shared.log("DIAG_PUSH_RECV | fg | list_only_inapp_banner | mid=\(mid.prefix(8))")
            // My own group message echoed back stays fully silent, as below.
            let isOwn = (userInfo["senderPublicKey"] as? String).map {
                $0 == UserDefaults.standard.string(forKey: "publicKey")
            } ?? false
            completionHandler(isOwn ? [] : [.list])
            return
        }

        // ✅ FIX: Trigger immediate message fetch for ALL group-related notifications
        if notificationType == "group_message" || notificationType == "group" || notificationType == "message" || notificationType == "group_update" {
            MessageManager.shared?.checkPendingIPFSMessages()
        }

        // 🔧 FIX: Do NOT store bot messages from push payload as text-only.
        // The push payload has no media (images are too large for APNs 4KB limit).
        // Storing text-only here races with processBotEnvelope() which has the full media,
        // causing either: (a) text-only message with no image, or (b) duplicate messages.
        // Instead, let checkPendingIPFSMessages() → processBotEnvelope() handle bot messages
        // properly with full media extraction. The IPFS fetch was already triggered above (line 643-644).

        // ✅ FIX: Suppress group_update notifications (member sync) — no banner needed
        if notificationType == "group_update" {
            OshiLog.ui.info("⏭️ Suppressing group_update notification banner (member sync)")
            completionHandler([])
            return
        }

        // ✅ FIX: Suppress notification for own group messages
        if notificationType == "group_message" || notificationType == "group" {
            if let senderKey = userInfo["senderPublicKey"] as? String,
               let myKey = UserDefaults.standard.string(forKey: "publicKey"),
               senderKey == myKey {
                OshiLog.ui.info("⏭️ Suppressing notification for own group message")
                completionHandler([])
                return
            }
            // ✅ FIX: Suppress group notification if user is not a member with current identity
            // After identity change, old group push tokens may still deliver notifications
            // ✅ FIX: completionHandler MUST be called synchronously — calling it inside an async
            // Task caused EXC_BAD_ACCESS when iOS deallocated it before the Task ran.
            // Use assumeIsolated on main thread, otherwise default to showing notification.
            if let groupIdStr = userInfo["groupId"] as? String,
               let _ = UUID(uuidString: groupIdStr) {
                // Default: show notification (safe fallback — better to show than crash)
                if #available(iOS 14.0, *) {
                    completionHandler([.banner, .sound, .badge])
                } else {
                    completionHandler([.alert, .sound, .badge])
                }
                return
            }
        }

        // Show notification for non-call types (messages, etc.)
        if #available(iOS 14.0, *) {
            completionHandler([.banner, .sound, .badge])
        } else {
            completionHandler([.alert, .sound, .badge])
        }
    }
    
    // ✅ Handle notification tap
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        OshiLog.ui.info("👆 \(NSLocalizedString("notification.tapped", comment: "User tapped notification"))")
        
        let userInfo = response.notification.request.content.userInfo

        let notificationType = userInfo["type"] as? String ?? "message"
        let messageId = userInfo["messageId"] as? String
        let conversationId = userInfo["conversationId"] as? String
        let groupId = userInfo["groupId"] as? String

        // 📧 New-email tap: route to the Email tab and open that message.
        //
        // The mail push rides the same /send-push service as chat, so the mailbox id
        // arrives under the allowlisted `messageId` key (a `mailId` key would be
        // dropped by the server's PUSH_DATA_ALLOWED_KEYS filter). Fall back to `mailId`
        // in case the server contract is later widened. If no id is present we still
        // land the tap on the Inbox rather than the app home.
        if notificationType == "mail" {
            NotificationCenter.default.post(name: .oshiMailPushReceived, object: nil)  // __MAIL_UNREAD_REFRESH_2026_11_22__
            let mailId = messageId ?? (userInfo["mailId"] as? String)
            OshiLog.ui.info("📧 Mail notification tapped — mailId: \(mailId?.prefix(8) ?? "nil")")
            PendingNotificationDeepLink.shared.set(.mail(messageId: mailId))
            NotificationCenter.default.post(
                name: NSNotification.Name("OpenMailMessage"),
                object: nil,
                userInfo: mailId.map { ["mailId": $0] } ?? [:]
            )
            completionHandler()
            return
        }

        if messageId != nil || conversationId != nil || groupId != nil {
            OshiLog.ui.info("   messageId: \(messageId?.prefix(8) ?? "nil"), conversationId: \(conversationId?.prefix(8) ?? "nil"), groupId: \(groupId?.prefix(8) ?? "nil"), type: \(notificationType)")

            // Handle different actions
            switch response.actionIdentifier {
            case "REPLY_ACTION":
                // 🔧 FIX (2026-08-07, "I type a reply on the notification and
                // nothing is sent"): the category installs a
                // UNTextInputNotificationAction, so iOS shows a keyboard and
                // takes the text — and this branch THREW IT AWAY, posting an
                // internal "OpenReply" that only mattered if the app happened to
                // be foreground and listening. From the lock screen, or from
                // inside another app, the message was simply lost.
                //
                // The typed text arrives as UNTextInputNotificationResponse. Read
                // it and actually send.
                if let textResponse = response as? UNTextInputNotificationResponse {
                    let body = textResponse.userText.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !body.isEmpty, let conversationId = conversationId {
                        Task { @MainActor in
                            guard let mm = MessageManager.shared,
                                  let identity = MessageManager.sharedIdentityManager,
                                  let mesh = MessageManager.sharedMeshManager else {
                                OshiLog.messaging.info("💬 Quick reply dropped: managers not ready")
                                return
                            }
                            let outcome = mm.sendMessage(content: body,
                                                         recipientAddress: conversationId,
                                                         identityManager: identity,
                                                         meshManager: mesh)
                            // The send can now be refused (100 msg/min, or a body
                            // too long to send even split up). There is no UI on
                            // the lock screen to refuse into, and the typed text
                            // is gone the moment this closure returns — so post a
                            // notification saying so. A quick reply that vanishes
                            // silently is exactly the bug the comment above this
                            // branch was written about.
                            guard outcome.didSend else {
                                OshiLog.messaging.info("💬 Quick reply REFUSED: \(String(describing: outcome))")
                                let warn = UNMutableNotificationContent()
                                warn.title = "alert.error".localized
                                warn.body = outcome.userFacingMessage ?? "alert.error".localized
                                warn.sound = .default
                                UNUserNotificationCenter.current().add(
                                    UNNotificationRequest(identifier: "quickReplyRefused-\(UUID().uuidString)",
                                                          content: warn,
                                                          trigger: nil))
                                return
                            }
                            OshiLog.messaging.info("💬 Quick reply sent from notification to \(conversationId.prefix(8))")
                        }
                    }
                } else {
                    // __KEYBOARD_PRIVACY_2026_10_01__ Not a text response: with a
                    // keyboard protection on, "Reply" is a plain .foreground action
                    // so the reply is typed in OSHI's protected composer. Open the
                    // thread exactly like a tap does. (The old "OpenReply" post had
                    // no listener, so this path used to do nothing.)
                    MessageManager.shared?.checkPendingIPFSMessages()
                    if notificationType == "group" || notificationType == "group_message" {
                        if let gid = groupId ?? conversationId, !gid.isEmpty {
                            PendingNotificationDeepLink.shared.set(.group(groupId: gid))
                            NotificationCenter.default.post(
                                name: NSNotification.Name("OpenGroupConversation"),
                                object: nil,
                                userInfo: ["groupId": gid]
                            )
                        }
                    } else if let cid = conversationId, !cid.isEmpty {
                        PendingNotificationDeepLink.shared.set(.peer(publicKey: cid))
                        NotificationCenter.default.post(
                            name: NSNotification.Name("OpenConversation"),
                            object: nil,
                            userInfo: ["conversationId": cid]
                        )
                    }
                }

            case "MARK_READ_ACTION":
                if let messageId = messageId {
                    NotificationCenter.default.post(
                        name: NSNotification.Name("MarkMessageRead"),
                        object: nil,
                        userInfo: ["messageId": messageId]
                    )
                }

            case UNNotificationDefaultActionIdentifier:
                // Post notification to navigate to conversation. Also store in
                // PendingNotificationDeepLink so cold launches (view tree not yet
                // subscribed) can consume the target once mounted.
                //
                // 🔧 2026-06-09: ALWAYS trigger a poll on tap. Previously this
                // was group-only — but a tap on a 1:1 notification can land
                // BEFORE the background-wake poll has finished decrypting the
                // new hash, so without a kick the user opens an empty chat
                // and has to wait for the next 1s poll tick.
                MessageManager.shared?.checkPendingIPFSMessages()

                if notificationType == "group" || notificationType == "group_message" {
                    if let gid = groupId ?? conversationId, !gid.isEmpty {
                        PendingNotificationDeepLink.shared.set(.group(groupId: gid))
                        NotificationCenter.default.post(
                            name: NSNotification.Name("OpenGroupConversation"),
                            object: nil,
                            userInfo: ["groupId": gid]
                        )
                    }
                } else if let cid = conversationId, !cid.isEmpty {
                    PendingNotificationDeepLink.shared.set(.peer(publicKey: cid))
                    NotificationCenter.default.post(
                        name: NSNotification.Name("OpenConversation"),
                        object: nil,
                        userInfo: ["conversationId": cid]
                    )
                } else if let senderKey = userInfo["senderPublicKey"] as? String, !senderKey.isEmpty {
                    // 🔧 2026-06-09: REQUIRE non-empty. Pre-b43 the server's
                    // queue-fallback push carried senderPublicKey="" and the
                    // old code passed that straight to OpenChat, which then
                    // failed to match any conversation and dumped the user on
                    // the chat list. Empty-string now falls through to the
                    // no-deep-link branch below.
                    PendingNotificationDeepLink.shared.set(.peer(publicKey: senderKey))
                    NotificationCenter.default.post(
                        name: NSNotification.Name("OpenChat"),
                        object: nil,
                        userInfo: ["peerPublicKey": senderKey]
                    )
                } else {
                    // No deep-link target available. The poll we kicked above
                    // will surface the message in whatever view the user
                    // currently has open; that's the best we can do without
                    // sender info.
                    OshiLog.ui.info("ℹ️ Notification tap: no deep-link target (sender/convo/group all empty) — poll kicked, user stays on current view")
                }

            default:
                break
            }
        }

        // 🛡️ Handle Check-In notification actions
        switch response.actionIdentifier {
        case "CHECKIN_SAFE_ACTION":
            OshiLog.ui.info("🛡️ Check-In: User confirmed safe from notification")
            Task { @MainActor in
                CheckInManager.shared.confirmSafe()
            }
        case "CHECKIN_ARRIVED_ACTION":
            OshiLog.ui.info("🛡️ Check-In: User marked arrived from notification")
            Task { @MainActor in
                CheckInManager.shared.stopCheckIn(reason: .arrived)
            }
        case "CHECKIN_EXTEND_ACTION":
            OshiLog.ui.info("🛡️ Check-In: User extended time from notification")
            Task { @MainActor in
                CheckInManager.shared.extendTime(minutes: 15)
            }
        case AliveCheckInStore.confirmActionIdentifier:
            // 💓 __ALIVE_CHECK_IN_2026_11_04__ "I'm alive", from the shade.
            //
            // The local state is written first and the send follows, in that
            // order and deliberately: the deadline is computed from the local
            // half, and it is true the moment the user presses the button. A send
            // that fails must not leave someone who DID check in with a deadline
            // that never moved — that is the one outcome that turns a safety
            // feature into a false alarm about its own owner.
            if let conversationId = userInfo[AliveCheckInStore.conversationUserInfoKey] as? String,
               !conversationId.isEmpty {
                Task { @MainActor in
                    let event = AliveCheckInStore.shared.confirmMine(conversationId)
                    guard let manager = MessageManager.shared,
                          let identityManager = MessageManager.sharedIdentityManager,
                          let meshManager = MessageManager.sharedMeshManager else { return }
                    _ = manager.sendMessage(
                        content: event.messageString,
                        recipientAddress: conversationId,
                        identityManager: identityManager,
                        meshManager: meshManager)
                }
            }
        default:
            break
        }

        // 🇨🇳 China/bypass mode: Handle incoming call notification actions (APNs-based calls)
        if response.actionIdentifier == "ACCEPT_CALL" {
            OshiLog.ui.info("📞 Native mode: User accepted call from notification")
            let callerKey = userInfo["callerPublicKey"] as? String ?? ""
            // __LEAK_FIX_2026_09_25__ resolved locally; the push carries no real name.
            let callerName = VoIPPushManager.resolveCallerDisplayName(
                callerKey: callerKey, pushName: userInfo["callerName"] as? String)
            let callId = userInfo["callId"] as? String ?? ""

            // Show in-app call UI, then auto-accept
            VoiceCallManager.shared.setupIncomingCallFromPush(
                callerPublicKey: callerKey,
                callerName: callerName,
                callId: callId,
                isVideoCall: false
            )
            // Auto-accept after a short delay to let UI appear
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                Task {
                    try? await VoiceCallManager.shared.acceptCall()
                }
            }
        } else if response.actionIdentifier == "DECLINE_CALL" {
            OshiLog.ui.info("📞 China mode: User declined call from notification")
            let callerKey = userInfo["callerPublicKey"] as? String ?? ""
            if !callerKey.isEmpty {
                VoiceCallManager.shared.declineIncomingCall(from: callerKey)
            }
        }

        completionHandler()
    }
}

// MARK: - Integration with MessageManager

extension MessageManager {
    /// Call this when you receive a new message
    func notifyNewMessage(_ message: SecureMessage, from senderAddress: String) {
        // Only send notification if app is not active
        guard UIApplication.shared.applicationState != .active else {
            OshiLog.ui.info("⏭️ \(NSLocalizedString("notification.skipped.app_active", comment: "Skipping - app is active"))")
            return
        }
        
        // ✅ FIXED: Use plaintextContent instead of content
        let messageText = message.plaintextContent ?? NSLocalizedString("notification.message.default", comment: "New message")
        
        NotificationManager.shared.sendMessageNotification(
            from: senderAddress,
            messageText: messageText,
            messageId: message.id,
            conversationId: senderAddress
        )
    }
}

// MARK: - Integration with GroupManager

extension GroupManager {
    /// Call this when you receive a new group message
    func notifyNewGroupMessage(
        groupName: String,
        senderName: String,
        messageText: String,
        groupId: String,
        messageId: String
    ) {
        guard UIApplication.shared.applicationState != .active else {
            OshiLog.ui.info("⏭️ \(NSLocalizedString("notification.skipped.app_active", comment: "Skipping - app is active"))")
            return
        }
        
        NotificationManager.shared.sendGroupMessageNotification(
            groupName: groupName,
            senderName: senderName,
            messageText: messageText,
            groupId: groupId,
            messageId: messageId
        )
    }
}
