import Foundation
import UserNotifications
import UIKit

class PushNotificationManager: NSObject, ObservableObject {
    static let shared = PushNotificationManager()
    
    @Published var isAuthorized = false
    @Published var notificationSettings: UNNotificationSettings?
    
    private let notificationCenter = UNUserNotificationCenter.current()
    
    // ✅ Badge count cache (since we can't directly read badge count in iOS 17+)
    @Published private var cachedBadgeCount: Int = 0
    
    override init() {
        super.init()
        // ⚠️ Do NOT claim the UNUserNotificationCenter delegate here. The app
        // installs `NotificationDelegate` (NotificationManager.swift) at launch as
        // the single, full-featured delegate — it handles foreground call-banner
        // suppression, group/own-message suppression, CHECKIN actions, and
        // China-mode ACCEPT/DECLINE_CALL, and is a strict superset of this class's
        // willPresent/didReceive. This class is lazily created on the first badge
        // sync (well after launch); setting `delegate = self` here silently
        // overwrote NotificationDelegate and disabled all of that rich handling
        // (e.g. a message banner would render on top of the full-screen call UI).
        // PushNotificationManager stays a badge/scheduling helper only.
        checkAuthorizationStatus()
        // Initialize badge count from UIApplication
        Task { @MainActor in
            if #available(iOS 17.0, *) {
                // In iOS 17+, we maintain our own badge count
                self.cachedBadgeCount = 0
            } else {
                self.cachedBadgeCount = UIApplication.shared.applicationIconBadgeNumber
            }
        }
    }
    
    // ✅ iOS 17+ compatible badge count setter
    private func setBadgeCount(_ count: Int) {
        Task { @MainActor in
            if #available(iOS 17.0, *) {
                try? await self.notificationCenter.setBadgeCount(count)
            } else {
                UIApplication.shared.applicationIconBadgeNumber = count
            }
            self.cachedBadgeCount = count
        }
    }
    
    // MARK: - Authorization
    
    func requestAuthorization() {
        #if DEBUG
        // 📸 Screenshot capture: suppress the system notification prompt. Release-stripped.
        // Also suppressed when previewing a specific onboarding page for screenshots.
        if ProcessInfo.processInfo.arguments.contains("-OSHIScreens") || MailFixtureHarness.isActive
            || UserDefaults.standard.integer(forKey: "onboardingStartPage") > 0 { return }
        #endif
        notificationCenter.requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
            DispatchQueue.main.async {
                self.isAuthorized = granted
                
                if granted {
                    OshiLog.ui.info("✅ Push notifications authorized")
                    UIApplication.shared.registerForRemoteNotifications()
                } else {
                    OshiLog.ui.info("❌ Push notifications denied")
                }
                
                if let error = error {
                    OshiLog.ui.info("❌ Error requesting authorization: \(error)")
                }
            }
        }
    }
    
    func checkAuthorizationStatus() {
        notificationCenter.getNotificationSettings { settings in
            DispatchQueue.main.async {
                self.notificationSettings = settings
                self.isAuthorized = settings.authorizationStatus == .authorized
                
                OshiLog.ui.info("📱 Notification status: \(settings.authorizationStatus.rawValue)")
            }
        }
    }
    
    // MARK: - Local Notifications
    
    func scheduleMessageNotification(
        from senderName: String,
        message: String,
        senderPublicKey: String,
        messageId: String
    ) {
        // 🚫 Suppress notifications from blocked contacts
        if BlockedContactsManager.shared.isBlocked(senderPublicKey) {
            OshiLog.ui.info("🚫 Notification suppressed: sender \(senderPublicKey.prefix(10))... is blocked")
            return
        }

        let content = UNMutableNotificationContent()
        content.title = senderName
        // 🔧 RAW-JSON FIX: raw `message` on the lock screen. Unlike
        // NotificationManager this path had NO formatting at all, so every
        // control payload that reached it was printed verbatim.
        // __ONE_BANNER_PER_MESSAGE_2026_09_23__ No message text on the lock
        // screen, ever (no caller today; kept safe in case one appears).
        content.body = MessageBannerPolicy.showsMessageText()
            ? InlineMarkup.stripped(OSHIControlPayload.previewText(for: message))
            : NSLocalizedString(MessageBannerPolicy.genericBodyKey, comment: "")
        if content.body.isEmpty { content.body = NSLocalizedString(MessageBannerPolicy.genericBodyKey, comment: "") }
        content.sound = .default
        content.badge = NSNumber(value: cachedBadgeCount + 1)
        
        // Add user info for handling tap
        content.userInfo = [
            "senderPublicKey": senderPublicKey,
            "messageId": messageId,
            "type": "message"
        ]
        
        // Add action buttons
        let replyAction = UNTextInputNotificationAction(
            identifier: "REPLY_ACTION",
            title: "reply".localized(),
            options: [.authenticationRequired],
            textInputButtonTitle: "send".localized(),
            textInputPlaceholder: "type_message".localized()
        )
        
        let markReadAction = UNNotificationAction(
            identifier: "MARK_READ_ACTION",
            title: "mark_read".localized(),
            options: [.authenticationRequired]
        )
        
        let category = UNNotificationCategory(
            identifier: "MESSAGE_CATEGORY",
            actions: [replyAction, markReadAction],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        
        // __CALL_BANNER_CANNOT_ANSWER_2026_08_25__ Quatrième remplacement en
        // gros du même jeu de catégories. Celui-ci n'a aujourd'hui aucun
        // appelant, mais s'il en gagnait un il effacerait INCOMING_CALL,
        // MESH_CALL et CHECKIN_WARNING d'un coup. Les catégories ont un seul
        // propriétaire: `NotificationManager.setupNotificationCategories()`.
        // MESSAGE_CATEGORY y est déjà déclarée, avec les mêmes actions.
        content.categoryIdentifier = "MESSAGE_CATEGORY"
        
        // Immediate delivery
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 0.1, repeats: false)
        
        let request = UNNotificationRequest(
            identifier: messageId,
            content: content,
            trigger: trigger
        )
        
        notificationCenter.add(request) { error in
            if let error = error {
                OshiLog.ui.info("❌ Error scheduling notification: \(error)")
            } else {
                OshiLog.ui.info("📬 Notification scheduled for message from \(senderName)")
            }
        }
    }
    
    func scheduleGroupMessageNotification(
        from senderName: String,
        groupName: String,
        message: String,
        groupId: String,
        messageId: String,
        senderPublicKey: String? = nil
    ) {
        // 🚫 Suppress notifications from blocked groups
        if let groupUUID = UUID(uuidString: groupId),
           BlockedContactsManager.shared.isGroupBlocked(groupUUID) {
            OshiLog.ui.info("🚫 Group notification suppressed: group \(groupId.prefix(10))... is blocked")
            return
        }

        // 🚫 Suppress notifications from blocked senders in groups
        if let senderKey = senderPublicKey, BlockedContactsManager.shared.isBlocked(senderKey) {
            OshiLog.ui.info("🚫 Group notification suppressed: sender \(senderKey.prefix(10))... is blocked")
            return
        }

        let content = UNMutableNotificationContent()
        content.title = groupName
        content.subtitle = senderName
        // Same fix as the 1:1 path above — this is the GROUP banner, which is
        // where the reported reaction/call JSON surfaced most often.
        content.body = MessageBannerPolicy.showsMessageText()
            ? InlineMarkup.stripped(OSHIControlPayload.previewText(for: message))
            : NSLocalizedString(MessageBannerPolicy.genericBodyKey, comment: "")
        if content.body.isEmpty { content.body = NSLocalizedString(MessageBannerPolicy.genericBodyKey, comment: "") }
        content.sound = .default
        content.badge = NSNumber(value: cachedBadgeCount + 1)
        
        content.userInfo = [
            "groupId": groupId,
            "messageId": messageId,
            "type": "group_message"
        ]
        
        content.categoryIdentifier = "MESSAGE_CATEGORY"
        
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 0.1, repeats: false)
        
        let request = UNNotificationRequest(
            identifier: messageId,
            content: content,
            trigger: trigger
        )
        
        notificationCenter.add(request) { error in
            if let error = error {
                OshiLog.ui.info("❌ Error scheduling group notification: \(error)")
            } else {
                OshiLog.ui.info("📬 Group notification scheduled from \(senderName) in \(groupName)")
            }
        }
    }
    
    // MARK: - Badge Management
    
    func updateBadgeCount(_ count: Int) {
        setBadgeCount(count)
    }
    
    func clearBadge() {
        setBadgeCount(0)
    }
    
    func incrementBadge() {
        let newCount = cachedBadgeCount + 1
        setBadgeCount(newCount)
    }
    
    func decrementBadge() {
        let newCount = max(0, cachedBadgeCount - 1)
        setBadgeCount(newCount)
    }
    
    // MARK: - Notification Management
    
    func removeNotification(withId id: String) {
        notificationCenter.removeDeliveredNotifications(withIdentifiers: [id])
        notificationCenter.removePendingNotificationRequests(withIdentifiers: [id])
    }
    
    func removeAllNotifications() {
        notificationCenter.removeAllDeliveredNotifications()
        notificationCenter.removeAllPendingNotificationRequests()
        clearBadge()
    }
    
    func getDeliveredNotifications(completion: @escaping ([UNNotification]) -> Void) {
        notificationCenter.getDeliveredNotifications { notifications in
            DispatchQueue.main.async {
                completion(notifications)
            }
        }
    }
    
    // MARK: - Settings
    
    func openNotificationSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }
}

// MARK: - UNUserNotificationCenterDelegate

extension PushNotificationManager: UNUserNotificationCenterDelegate {
    // Handle notification when app is in foreground
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        OshiLog.ui.info("📬 Received notification while in foreground: \(notification.request.identifier)")

        // Kick the IPFS pending queue NOW. Without this the user sees a banner
        // for a message whose envelope hasn't been pulled from VPS yet, and
        // tapping it opens an empty thread until the next 2s polling tick.
        // `didReceiveRemoteNotification` covers the background path; the
        // foreground path lands here instead and previously did nothing.
        let userInfo = notification.request.content.userInfo
        if let type = userInfo["type"] as? String, (type == "message" || type == "group_message") {
            MessageManager.shared?.checkPendingIPFSMessages()
        }

        // Show banner, play sound, and update badge even when app is in foreground
        completionHandler([.banner, .sound, .badge])
    }
    
    // Handle notification tap
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        
        OshiLog.ui.info("📬 User interacted with notification: \(response.actionIdentifier)")
        
        switch response.actionIdentifier {
        case "REPLY_ACTION":
            if let textResponse = response as? UNTextInputNotificationResponse {
                handleReply(text: textResponse.userText, userInfo: userInfo)
            }
            
        case "MARK_READ_ACTION":
            handleMarkAsRead(userInfo: userInfo)
            
        case UNNotificationDefaultActionIdentifier:
            // User tapped the notification
            handleNotificationTap(userInfo: userInfo)
            
        case UNNotificationDismissActionIdentifier:
            // User dismissed the notification
            OshiLog.ui.info("❌ Notification dismissed")
            
        default:
            break
        }
        
        completionHandler()
    }
    
    private func handleReply(text: String, userInfo: [AnyHashable: Any]) {
        guard let senderPublicKey = userInfo["senderPublicKey"] as? String else { return }
        
        OshiLog.ui.infoPublic("💬 Quick reply received (\(text.count) chars)")
        
        // Post notification to send message
        NotificationCenter.default.post(
            name: NSNotification.Name("SendQuickReply"),
            object: nil,
            userInfo: [
                "recipientPublicKey": senderPublicKey,
                "message": text
            ]
        )
    }
    
    private func handleMarkAsRead(userInfo: [AnyHashable: Any]) {
        guard let messageId = userInfo["messageId"] as? String else { return }
        
        OshiLog.ui.info("✓ Marking message as read: \(messageId)")
        
        // Post notification to mark message as read
        NotificationCenter.default.post(
            name: NSNotification.Name("MarkMessageAsRead"),
            object: nil,
            userInfo: ["messageId": messageId]
        )
        
        decrementBadge()
    }
    
    private func handleNotificationTap(userInfo: [AnyHashable: Any]) {
        if let type = userInfo["type"] as? String {
            if type == "message" || type == "group_message" {
                // Force a poll BEFORE OpenChat so the conversation has the
                // message rendered by the time the chat view loads. The chat
                // view subscribes to the messages store; if the pull lands
                // 50–500 ms after navigation, the user sees the bubble appear
                // — without this they'd see an empty thread until the next
                // 2s polling tick.
                MessageManager.shared?.checkPendingIPFSMessages()
            }
            if type == "message", let senderPublicKey = userInfo["senderPublicKey"] as? String {
                PendingNotificationDeepLink.shared.set(.peer(publicKey: senderPublicKey))
                NotificationCenter.default.post(
                    name: NSNotification.Name("OpenChat"),
                    object: nil,
                    userInfo: ["peerPublicKey": senderPublicKey]
                )
            } else if type == "group_message", let groupId = userInfo["groupId"] as? String {
                PendingNotificationDeepLink.shared.set(.group(groupId: groupId))
                NotificationCenter.default.post(
                    name: NSNotification.Name("OpenGroupChat"),
                    object: nil,
                    userInfo: ["groupId": groupId]
                )
            }
        }
    }
}

// MARK: - Background Task Support

class BackgroundTaskManager {
    static let shared = BackgroundTaskManager()
    
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    
    func startBackgroundTask() {
        backgroundTask = UIApplication.shared.beginBackgroundTask { [weak self] in
            self?.endBackgroundTask()
        }
        
        OshiLog.ui.info("🔄 Background task started: \(backgroundTask.rawValue)")
    }
    
    func endBackgroundTask() {
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
            OshiLog.ui.info("✅ Background task ended")
        }
    }
}
