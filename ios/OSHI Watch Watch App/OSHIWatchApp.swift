//
//  OSHIWatchApp.swift
//  OSHI Watch
//
//  __APPLE_WATCH_2027__ Rebuilt to the Wear OS app's model (Android branch
//  feat/wear-os): the watch is a remote VIEW of OSHI on the iPhone.
//
//    * It holds no identity key, encrypts nothing and never talks to an OSHI
//      server: every list / thread / send / reaction is a request to the
//      paired iPhone (WatchBridgeClient.swift), which answers from its own
//      store or sends through its own send path (WatchBridgeService.swift).
//    * The iPhone refuses everything until "Smartwatch" is turned on
//      (Settings › Linked devices, OFF by default) and while OSHI is locked.
//    * Nothing message-related is written to the watch's disk. The previous
//      app kept a conversation list on disk and received every incoming
//      message's decrypted text pushed from the phone, opt-in or not, locked or
//      not; both are gone.
//

import SwiftUI
import WatchKit
import UserNotifications

@main
struct OSHIWatchApp: App {
    @WKApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var store = WatchStore.shared
    @StateObject private var callManager = WatchCallManager.shared

    init() {
        LegacyWatchStorage.purge()
        #if DEBUG
        WatchFakeData.installIfRequested()
        #endif
        if !WatchFakeData.isActive {
            PhoneBridgeSource.shared.activate()
        }
    }

    var body: some Scene {
        WindowGroup {
            ZStack {
                WatchRootView()
                    .environmentObject(store)
                    .environmentObject(callManager)
                    .disabled(callManager.hasIncomingCall || callManager.isInCall)

                if callManager.hasIncomingCall {
                    WatchIncomingCallView()
                        .environmentObject(callManager)
                        .transition(.opacity)
                } else if callManager.isInCall {
                    WatchActiveCallView()
                        .environmentObject(callManager)
                        .transition(.opacity)
                }
            }
            .preferredColorScheme(.dark)
            .animation(.easeInOut, value: callManager.hasIncomingCall)
            .animation(.easeInOut, value: callManager.isInCall)
        }
    }
}

// MARK: - App delegate (notifications, background refresh)

final class AppDelegate: NSObject, WKApplicationDelegate {
    func applicationDidFinishLaunching() {
        if WatchFakeData.isActive { return }   // no permission alert over screenshots

        let center = UNUserNotificationCenter.current()
        center.delegate = WatchNotificationDelegate.shared
        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }

        // Same identifiers as the iPhone's MESSAGE_CATEGORY (NotificationManager),
        // so the actions of a mirrored OSHI notification land here.
        let reply = UNTextInputNotificationAction(
            identifier: "REPLY_ACTION",
            title: NSLocalizedString("notification.action.reply", comment: "Reply"),
            options: [],
            textInputButtonTitle: NSLocalizedString("send", comment: "Send"),
            textInputPlaceholder: NSLocalizedString("type_message", comment: "Type a message"))
        let markRead = UNNotificationAction(
            identifier: "MARK_READ_ACTION",
            title: NSLocalizedString("notification.action.mark_read", comment: "Mark Read"),
            options: [])
        let message = UNNotificationCategory(identifier: "MESSAGE_CATEGORY",
                                             actions: [reply, markRead], intentIdentifiers: [])
        let accept = UNNotificationAction(identifier: "ACCEPT_CALL",
                                          title: NSLocalizedString("call.action.accept", comment: ""),
                                          options: [.foreground])
        let decline = UNNotificationAction(identifier: "DECLINE_CALL",
                                           title: NSLocalizedString("call.action.decline", comment: ""),
                                           options: [.destructive])
        let call = UNNotificationCategory(identifier: "INCOMING_CALL", actions: [accept, decline],
                                          intentIdentifiers: [])
        center.setNotificationCategories([message, call])
    }

    func handle(_ backgroundTasks: Set<WKRefreshBackgroundTask>) {
        for task in backgroundTasks {
            if let wc = task as? WKWatchConnectivityRefreshBackgroundTask {
                PhoneBridgeSource.shared.handle(wc)
            } else {
                task.setTaskCompletedWithSnapshot(false)
            }
        }
    }
}

// MARK: - Notification actions

final class WatchNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    static let shared = WatchNotificationDelegate()

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    /// The conversation a notification is about — the iPhone's own userInfo
    /// keys (`groupId` for a group, `conversationId` for a 1:1).
    static func conversation(from info: [AnyHashable: Any]) -> WatchConversationRef? {
        if let gid = info["groupId"] as? String, !gid.isEmpty {
            return WatchConversationRef(kind: .group, cid: gid, name: "")
        }
        let key = info["conversationId"] as? String ?? info["senderKey"] as? String
        guard let key, !key.isEmpty else { return nil }
        return WatchConversationRef(kind: .direct, cid: key, name: "")
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let ref = Self.conversation(from: info)

        switch response.actionIdentifier {
        case "REPLY_ACTION":
            guard let ref, let text = (response as? UNTextInputNotificationResponse)?.userText else {
                completionHandler(); return
            }
            Task { @MainActor in
                // Same gates as the app (opt-in, unlocked); nothing queued.
                if await WatchStore.shared.send(text, to: ref) != nil {
                    Self.postNotSent()
                }
                completionHandler()
            }
        case "MARK_READ_ACTION":
            guard let ref else { completionHandler(); return }
            Task { @MainActor in
                await WatchStore.shared.markRead(ref)
                completionHandler()
            }
        case "ACCEPT_CALL":
            if let key = info["callerKey"] as? String { WatchCallManager.shared.acceptCall(from: key) }
            completionHandler()
        case "DECLINE_CALL":
            if let key = info["callerKey"] as? String { WatchCallManager.shared.declineCall(from: key) }
            completionHandler()
        default:
            if let ref {
                DispatchQueue.main.async { WatchStore.shared.pendingOpen = ref }
            }
            completionHandler()
        }
    }

    /// A reply that did not go must not vanish — but its TEXT never goes into a
    /// notification either.
    static func postNotSent() {
        let content = UNMutableNotificationContent()
        content.title = "OSHI"
        content.body = WS.sendFailed
        content.sound = .default
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: "oshiw.notsent.\(UUID().uuidString)", content: content, trigger: nil))
    }
}

// MARK: - What the previous watch app left on disk

/// __APPLE_WATCH_2027__ Builds up to 1.0.46 kept, on the WATCH's disk: a
/// conversation list (names, keys, unread counts) in Application Support, the
/// even older UserDefaults copy that held decrypted message text, and the
/// owner's public key. The new app writes none of it; this removes what an
/// update would otherwise leave behind forever. Idempotent, cheap.
enum LegacyWatchStorage {
    static func purge() {
        let d = UserDefaults.standard
        for key in ["cached_conversations", "reserve_owner_key"] where d.object(forKey: key) != nil {
            d.removeObject(forKey: key)
        }
        if let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent("watch_list_reserve.json"))
        }
    }
}
