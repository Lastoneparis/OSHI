//
//  WatchConnectivityManager.swift
//  OSHI
//
//  Manages communication between iPhone and Apple Watch
//

import Foundation
import WatchConnectivity
import SwiftUI

class WatchConnectivityManager: NSObject, ObservableObject {
    static let shared = WatchConnectivityManager()
    
    @Published var isWatchPaired: Bool = false
    @Published var isWatchReachable: Bool = false
    @Published var watchAppInstalled: Bool = false
    @Published var lastSyncDate: Date?
    
    private var session: WCSession?
    
    private override init() {
        super.init()
        
        if WCSession.isSupported() {
            session = WCSession.default
            session?.delegate = self
            session?.activate()
            OshiLog.ui.info("⌚ WatchConnectivity: Session initialized")
        } else {
            OshiLog.ui.info("⌚ WatchConnectivity: Not supported on this device")
        }
    }
    
    func sendMessage(_ message: [String: Any], replyHandler: (([String: Any]) -> Void)? = nil, errorHandler: ((Error) -> Void)? = nil) {
        guard let session = session, session.isReachable else {
            OshiLog.ui.info("⌚ WatchConnectivity: Watch not reachable")
            errorHandler?(WatchError.notReachable)
            return
        }
        session.sendMessage(message, replyHandler: replyHandler, errorHandler: errorHandler)
    }
    
    /// __APPLE_WATCH_2027__ Message content NO LONGER crosses on arrival.
    ///
    /// This used to push every received message — decrypted text, sender name,
    /// key — to the watch, falling back to `transferUserInfo`, a queue the system
    /// PERSISTS on both devices until delivered, and it did so whatever the app
    /// lock said and with no opt-in. The watch now asks for what it shows
    /// (`WatchBridgeService`) and only a count + three names are pushed, and
    /// only while "Smartwatch" is on and OSHI is unlocked.
    func sendChatMessageToWatch(_ message: SecureMessage, contactAlias: String?) {
        WatchBridgeService.scheduleSnapshotPush()
    }

    // MARK: - ✅ Call Functions for Watch

    /// Forward incoming call to Watch.
    ///
    /// __APPLE_WATCH_2027__ Only while "Smartwatch" is on, and never the
    /// caller's name while OSHI is locked. Live `sendMessage` only: the old
    /// `transferUserInfo` fallback was a queue the system persists and delivers
    /// whenever it likes — a ring minutes after the call, with a name in it.
    func forwardIncomingCallToWatch(callerKey: String, callerName: String, isVideo: Bool) {
        guard WatchBridgeSettings.isEnabled,
              let session = session, session.activationState == .activated, session.isReachable else { return }

        let payload: [String: Any] = [
            "type": "incomingCall",
            "callerKey": callerKey,
            "callerName": AppLockState.isLocked ? "OSHI" : callerName,
            "isVideo": isVideo,
            "timestamp": Date().timeIntervalSince1970
        ]
        sendMessage(payload)
        OshiLog.ui.info("⌚📞 WatchConnectivity: Forwarded incoming call to Watch")
    }

    /// Update call state on Watch
    func updateCallStateOnWatch(state: String) {
        guard WatchBridgeSettings.isEnabled, let session = session, session.activationState == .activated else { return }

        let payload: [String: Any] = [
            "type": "callStateUpdate",
            "state": state,
            "timestamp": Date().timeIntervalSince1970
        ]

        if session.isReachable {
            sendMessage(payload)
            OshiLog.ui.info("⌚📞 WatchConnectivity: Updated call state to '\(state)' on Watch")
        }
    }

    /// Notify Watch that call ended
    func notifyCallEndedOnWatch() {
        guard WatchBridgeSettings.isEnabled, let session = session, session.activationState == .activated else { return }

        let payload: [String: Any] = [
            "type": "callEnded",
            "timestamp": Date().timeIntervalSince1970
        ]

        if session.isReachable {
            sendMessage(payload)
            OshiLog.ui.info("⌚📞 WatchConnectivity: Notified Watch that call ended")
        }
    }

    enum WatchError: Error {
        case notReachable
        case notPaired
        case sessionNotActivated
    }
}

// MARK: - WCSessionDelegate

extension WatchConnectivityManager: WCSessionDelegate {

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        DispatchQueue.main.async { [weak self] in
            self?.isWatchPaired = session.isPaired
            self?.watchAppInstalled = session.isWatchAppInstalled
            self?.isWatchReachable = session.isReachable
        }

        if let error = error {
            OshiLog.ui.info("⌚ WatchConnectivity: Activation failed: \(error)")
        } else {
            OshiLog.ui.info("⌚ WatchConnectivity: Activated with state \(activationState.rawValue)")
            // The widget's state (count + names, or "off" / "locked") follows
            // the CURRENT settings from the first activation on — which also
            // replaces whatever an older build left in the persisted context.
            WatchBridgeService.requestSnapshotPush()
        }
    }

    func sessionDidBecomeInactive(_ session: WCSession) {
        OshiLog.ui.info("⌚ WatchConnectivity: Session became inactive")
    }

    func sessionDidDeactivate(_ session: WCSession) {
        OshiLog.ui.info("⌚ WatchConnectivity: Session deactivated")
        session.activate()
    }

    func sessionWatchStateDidChange(_ session: WCSession) {
        DispatchQueue.main.async { [weak self] in
            self?.isWatchPaired = session.isPaired
            self?.watchAppInstalled = session.isWatchAppInstalled
        }
        WatchBridgeService.requestSnapshotPush()
    }

    func sessionReachabilityDidChange(_ session: WCSession) {
        DispatchQueue.main.async { [weak self] in
            self?.isWatchReachable = session.isReachable
        }
        OshiLog.ui.info("⌚ WatchConnectivity: Reachability changed to \(session.isReachable)")
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        handleWatchMessage(message)
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        handleWatchMessage(message, replyHandler: replyHandler)
    }

    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        // __APPLE_WATCH_2027__ The watch no longer queues anything (a queued
        // reply is message text persisted on the wrist). Whatever an older watch
        // build left in the queue is dropped, never acted on: it would be a send
        // nobody can see the outcome of, possibly hours late.
        OshiLog.ui.info("⌚ WatchConnectivity: queued userInfo ignored")
    }

    func session(_ session: WCSession, didReceive file: WCSessionFile) {
        // Same: the watch app sends no files any more.
        OshiLog.ui.info("⌚ WatchConnectivity: file transfer ignored")
    }

    // MARK: - Message Handling

    private func handleWatchMessage(_ message: [String: Any], replyHandler: (([String: Any]) -> Void)? = nil) {
        // The bridge (list / thread / send / react / read): gated inside.
        if WatchBridgeService.shared.handle(message, reply: replyHandler) { return }

        guard let type = message["type"] as? String else {
            replyHandler?(["success": false])
            return
        }
        OshiLog.ui.info("⌚ WatchConnectivity: Received message type: \(type)")

        switch type {
        case "initiateCall":
            handleInitiateCall(message, replyHandler: replyHandler)
        case "callAction":
            handleCallAction(message, replyHandler: replyHandler)
        default:
            // __APPLE_WATCH_2027__ The pre-bridge data messages
            // (requestConversations, requestMessages, sendTextMessage, markAsRead,
            // requestMediaThumbnail, start/stop/cancelAudioRecording) are gone:
            // they answered with decrypted content whatever the app lock said and
            // with no opt-in, and the audio ones drove the iPHONE's microphone
            // from the wrist. Refused, never served.
            replyHandler?(["success": false, "error": WatchStatus.invalid.rawValue])
        }
    }

    // MARK: - ✅ Call Handling from Watch

    private func handleInitiateCall(_ message: [String: Any], replyHandler: (([String: Any]) -> Void)?) {
        guard let recipientKey = message["recipientPublicKey"] as? String else {
            replyHandler?(["success": false, "error": "Missing recipient key"])
            return
        }
        // __APPLE_WATCH_2027__ Placing a call is acting as the user: same gates
        // as a send (opt-in, unlocked). The watch never asks for video.
        if let refused = WatchBridgeService.gate(enabled: WatchBridgeSettings.isEnabled,
                                                 locked: AppLockState.isLocked, ready: true) {
            replyHandler?(["success": false, "error": refused.rawValue])
            return
        }

        let isVideo = false

        OshiLog.ui.info("⌚📞 WatchConnectivity: Initiating \(isVideo ? "video" : "voice") call from Watch to \(recipientKey.prefix(8))...")

        DispatchQueue.main.async {
            let voiceCallManager = VoiceCallManager.shared

            // Get contact name from aliases
            let aliasData = UserDefaults.standard.data(forKey: "contactAliases") ?? Data()
            let aliases = (try? JSONDecoder().decode([String: String].self, from: aliasData)) ?? [:]
            // __SHARED_NICKNAME_2026_09_22__ alias local > pseudo partage.
            let contactName = SharedNickname.resolve(
                alias: aliases[recipientKey],
                nickname: SharedNickname.nickname(for: recipientKey)) ?? String(recipientKey.prefix(8))

            Task {
                do {
                    try await voiceCallManager.startCall(to: recipientKey, peerName: contactName, isVideoCall: isVideo)
                    replyHandler?(["success": true])
                    OshiLog.ui.info("⌚📞 WatchConnectivity: Call initiated successfully from Watch")
                } catch {
                    OshiLog.ui.info("⌚📞 WatchConnectivity: Failed to initiate call: \(error)")
                    replyHandler?(["success": false, "error": error.localizedDescription])
                }
            }
        }
    }

    private func handleCallAction(_ message: [String: Any], replyHandler: (([String: Any]) -> Void)?) {
        guard let action = message["action"] as? String else {
            replyHandler?(["success": false, "error": "Missing action"])
            return
        }
        // Answering / hanging up a ringing call is what CallKit already allows
        // from a locked phone; it only needs the opt-in.
        guard WatchBridgeSettings.isEnabled else {
            replyHandler?(["success": false, "error": WatchStatus.disabled.rawValue])
            return
        }

        let callerKey = message["callerKey"] as? String ?? ""

        OshiLog.ui.info("⌚📞 WatchConnectivity: Received call action '\(action)' from Watch")

        DispatchQueue.main.async {
            let voiceCallManager = VoiceCallManager.shared

            switch action {
            case "accept":
                Task {
                    do {
                        try await voiceCallManager.acceptCall()
                        replyHandler?(["success": true])
                        OshiLog.ui.info("⌚📞 WatchConnectivity: Call accepted from Watch")
                    } catch {
                        replyHandler?(["success": false, "error": error.localizedDescription])
                    }
                }

            case "decline":
                voiceCallManager.declineIncomingCall(from: callerKey)
                replyHandler?(["success": true])
                OshiLog.ui.info("⌚📞 WatchConnectivity: Call declined from Watch")

            case "end":
                voiceCallManager.endCall()
                replyHandler?(["success": true])
                OshiLog.ui.info("⌚📞 WatchConnectivity: Call ended from Watch")

            case "mute":
                voiceCallManager.isMuted = true
                replyHandler?(["success": true])

            case "unmute":
                voiceCallManager.isMuted = false
                replyHandler?(["success": true])

            case "speakerOn":
                if !voiceCallManager.isSpeakerOn {
                    voiceCallManager.toggleSpeaker()
                }
                replyHandler?(["success": true])

            case "speakerOff":
                if voiceCallManager.isSpeakerOn {
                    voiceCallManager.toggleSpeaker()
                }
                replyHandler?(["success": true])

            default:
                OshiLog.ui.info("⌚📞 WatchConnectivity: Unknown call action: \(action)")
                replyHandler?(["success": false, "error": "Unknown action"])
            }
        }
    }
    
    /// Wraps `content` in the project's ONE reply envelope when the Watch named
    /// a message to quote. Returns `content` untouched otherwise.
    ///
    /// Falls back to a plain send when the id matches nothing on the phone —
    /// the user's words must not be lost because a quote could not be built
    /// (the original was deleted, or the wrist's cache outran the phone's). The
    /// loss of the quote is logged, never silent.
    static func contentWithWatchReply(content: String,
                                              replyToMessageId: String?,
                                              recipientKey: String,
                                              manager: MessageManager) -> String {
        guard let replyToId = replyToMessageId, !replyToId.isEmpty else {
            return content
        }

        // Deliberately wider than the Watch's 50-message window: the wrist can
        // be scrolled back further than the last page the phone shipped it.
        guard let original = manager
            .getMessages(for: recipientKey, limit: 500)
            .first(where: { $0.id == replyToId }) else {
            OshiLog.ui.info("⌚ WatchConnectivity: reply target \(replyToId.prefix(8))… not found — sending plain")
            return content
        }

        let reply = ReplyMessage(
            originalMessageId: original.id,
            originalSenderKey: original.senderPublicKey,
            // Same catalog + same 180-char clip as ChatView's swipe-to-reply,
            // so a quoted call summary / location / reaction never travels as
            // raw sentinel JSON.
            originalText: String(OSHIControlPayload
                .previewText(for: original.plaintextContent ?? "")
                .prefix(180)),
            originalTimestamp: original.timestamp,
            originalMediaType: original.mediaType?.rawValue
        )

        OshiLog.ui.info("⌚ WatchConnectivity: replying to \(original.id.prefix(8))…")
        return MessageWithReply(content: content, replyTo: reply).toMessageString()
    }

}
