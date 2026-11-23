//
//  WatchCalls.swift
//  OSHI Watch
//
//  Incoming / active call overlays. The call itself runs on the iPhone
//  (VoiceCallManager); the watch is a remote: accept, decline, mute,
//  speaker, hang up — audio only, the Apple Watch has no camera.
//  __APPLE_WATCH_2027__ Moved out of OSHIWatchApp.swift, unchanged in
//  behaviour, talking through `PhoneBridgeSource`; OLED black backgrounds.
//

import SwiftUI
import Combine
import WatchKit
import UserNotifications

// MARK: - Watch Call Manager

class WatchCallManager: ObservableObject {
    static let shared = WatchCallManager()

    @Published var hasIncomingCall = false
    @Published var isInCall = false
    @Published var isMuted = false
    @Published var isSpeaker = false
    @Published var callDuration: TimeInterval = 0
    @Published var callerName: String = ""
    @Published var callerKey: String = ""
    @Published var isVideoCall = false
    @Published var callState: String = "idle" // idle, ringing, connecting, inCall, ended

    private var callTimer: Timer?
    private var callStartTime: Date?

    private init() {}

    // MARK: - Incoming Call

    func showIncomingCall(from callerKey: String, callerName: String, isVideo: Bool) {
        DispatchQueue.main.async {
            // Guard: auto-decline if already in a call or already have an incoming call
            guard !self.isInCall && !self.hasIncomingCall else {
                Self.sendCallAction(action: "decline", callerKey: callerKey) { _ in }
                return
            }

            self.callerKey = callerKey
            self.callerName = callerName
            self.isVideoCall = isVideo
            self.hasIncomingCall = true
            self.callState = "ringing"

            // Play haptic
            WKInterfaceDevice.current().play(.notification)

            // Show local notification as backup
            self.showCallNotification(callerName: callerName, isVideo: isVideo)
        }
    }

    func acceptCall(from callerKey: String? = nil) {
        let key = callerKey ?? self.callerKey

        Self.sendCallAction(action: "accept", callerKey: key) { [weak self] success in
            DispatchQueue.main.async {
                if success {
                    self?.hasIncomingCall = false
                    self?.isInCall = true
                    // Set to "connecting" — wait for iPhone's "inCall" state update
                    self?.callState = "connecting"
                    WKInterfaceDevice.current().play(.success)
                } else {
                    WKInterfaceDevice.current().play(.failure)
                }
            }
        }
    }

    func declineCall(from callerKey: String? = nil) {
        let key = callerKey ?? self.callerKey

        Self.sendCallAction(action: "decline", callerKey: key) { [weak self] _ in
            DispatchQueue.main.async {
                self?.hasIncomingCall = false
                self?.callState = "idle"
                self?.resetCallState()
                WKInterfaceDevice.current().play(.click)
            }
        }
    }

    // MARK: - Outgoing Call

    /// Ouvre un appel depuis la montre. TOUJOURS en audio.
    ///
    /// __WATCH_AUDIO_ONLY_2026_08_28__ Le parametre `isVideo` a ete SUPPRIME
    /// plutot que passe a `false` par defaut: un defaut se reecrit au prochain
    /// appelant, une signature absente ne se reecrit pas. L'Apple Watch n'a pas
    /// de camera, donc il n'existe aucun appelant legitime avec `true`.
    func startCall(to recipientKey: String, recipientName: String) {
        // Check reachability before showing any call UI
        guard PhoneBridgeSource.shared.isReachable else {
            WKInterfaceDevice.current().play(.failure)
            return
        }

        DispatchQueue.main.async {
            self.callerKey = recipientKey
            self.callerName = recipientName
            self.isVideoCall = false
            self.callState = "connecting"
            self.isInCall = true
        }

        PhoneBridgeSource.shared.sendLegacy([
            "type": "initiateCall",
            "recipientPublicKey": recipientKey,
            "isVideo": false
        ]) { [weak self] success in
            DispatchQueue.main.async {
                if success {
                    self?.callState = "ringing"
                    WKInterfaceDevice.current().play(.start)
                } else {
                    // Reset immediately without showing "ended" screen for a call that never started
                    self?.resetCallState()
                    WKInterfaceDevice.current().play(.failure)
                }
            }
        }
    }

    // MARK: - Call Controls

    func endCall() {
        let key = callerKey // Capture before reset
        Self.sendCallAction(action: "end", callerKey: key) { _ in }

        DispatchQueue.main.async { [weak self] in
            self?.callState = "ended"
            WKInterfaceDevice.current().play(.stop)

            // Reset after brief delay
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                // Only reset if still in "ended" state (avoid resetting a new call)
                guard self?.callState == "ended" else { return }
                self?.resetCallState()
            }
        }
    }

    func toggleMute() {
        isMuted.toggle()
        Self.sendCallAction(action: isMuted ? "mute" : "unmute", callerKey: callerKey) { _ in }
        WKInterfaceDevice.current().play(.click)
    }

    func toggleSpeaker() {
        isSpeaker.toggle()
        Self.sendCallAction(action: isSpeaker ? "speakerOn" : "speakerOff", callerKey: callerKey) { _ in }
        WKInterfaceDevice.current().play(.click)
    }

    // MARK: - Call State Updates from iPhone

    func updateCallState(_ state: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.callState = state

            switch state {
            case "inCall":
                self.hasIncomingCall = false
                self.isInCall = true
                // Only start timer if not already running (avoid reset on redundant updates)
                if self.callTimer == nil {
                    self.startCallTimer()
                }
                WKInterfaceDevice.current().play(.success)

            case "ended":
                WKInterfaceDevice.current().play(.stop)
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                    guard self?.callState == "ended" else { return }
                    self?.resetCallState()
                }

            case "ringing":
                WKInterfaceDevice.current().play(.notification)

            default:
                break
            }
        }
    }

    // MARK: - Phone link

    static func sendCallAction(action: String, callerKey: String, completion: @escaping (Bool) -> Void) {
        PhoneBridgeSource.shared.sendLegacy([
            "type": "callAction",
            "action": action,
            "callerKey": callerKey
        ], completion: completion)
    }

    /// Live call messages from the phone (it only sends them while
    /// "Smartwatch" is on, and without the caller's name while OSHI is locked).
    func handlePhoneMessage(_ message: [String: Any]) {
        switch message["type"] as? String {
        case "incomingCall":
            let callerKey = message["callerKey"] as? String ?? ""
            let callerName = message["callerName"] as? String ?? NSLocalizedString("watch.call.unknown", comment: "")
            showIncomingCall(from: callerKey, callerName: callerName, isVideo: message["isVideo"] as? Bool ?? false)
        case "callStateUpdate":
            if let state = message["state"] as? String { updateCallState(state) }
        case "callEnded":
            updateCallState("ended")
        default:
            break
        }
    }

    // MARK: - Private

    private func startCallTimer() {
        callStartTime = Date()
        callTimer?.invalidate()
        callTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self = self, let start = self.callStartTime else { return }
            DispatchQueue.main.async {
                self.callDuration = Date().timeIntervalSince(start)
            }
        }
    }

    private func resetCallState() {
        hasIncomingCall = false
        isInCall = false
        isMuted = false
        isSpeaker = false
        callDuration = 0
        callerName = ""
        callerKey = ""
        isVideoCall = false
        callState = "idle"
        callTimer?.invalidate()
        callTimer = nil
        callStartTime = nil
    }

    private func showCallNotification(callerName: String, isVideo: Bool) {
        let content = UNMutableNotificationContent()
        content.title = isVideo ? NSLocalizedString("watch.call.incoming_video", comment: "") : NSLocalizedString("watch.call.incoming_voice", comment: "")
        // __LEAK_FIX_2026_09_25__ generic: no caller name on the notification (the call
        // screen inside the app still shows who is calling).
        content.body = "OSHI"
        content.sound = .default
        content.categoryIdentifier = "INCOMING_CALL"
        content.userInfo = ["callerKey": callerKey, "callerName": callerName]

        // Categories registered once in AppDelegate — no need to re-register here
        let request = UNNotificationRequest(identifier: "incomingCall_\(callerKey)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}

// MARK: - Watch Incoming Call View

struct WatchIncomingCallView: View {
    @EnvironmentObject var callManager: WatchCallManager
    @State private var pulseAnimation = false

    var body: some View {
        ZStack {
            // Background
            // OLED black with a soft green glow behind the caller.
            Color.black.ignoresSafeArea()
            RadialGradient(colors: [Color.green.opacity(0.35), .clear], center: .top,
                           startRadius: 0, endRadius: 140)
                .ignoresSafeArea()

            VStack(spacing: 12) {
                // Pulsing avatar
                ZStack {
                    Circle()
                        .fill(Color.white.opacity(0.2))
                        .frame(width: 70, height: 70)
                        .scaleEffect(pulseAnimation ? 1.2 : 1.0)
                        .opacity(pulseAnimation ? 0.3 : 0.6)
                        .animation(.easeInOut(duration: 1.0).repeatForever(autoreverses: true), value: pulseAnimation)

                    Image(systemName: callManager.isVideoCall ? "video.fill" : "phone.fill")
                        .font(.title)
                        .foregroundColor(.white)
                }

                Text(callManager.callerName)
                    .font(.headline)
                    .foregroundColor(.white)
                    .lineLimit(1)

                Text(callManager.isVideoCall ? NSLocalizedString("watch.call.video", comment: "") : NSLocalizedString("watch.call.voice", comment: ""))
                    .font(.caption)
                    .foregroundColor(.white.opacity(0.8))

                Spacer()

                // Accept/Decline buttons
                HStack(spacing: 30) {
                    // Decline
                    Button {
                        callManager.declineCall()
                    } label: {
                        Image(systemName: "phone.down.fill")
                            .font(.title3)
                            .foregroundColor(.white)
                            .frame(width: 50, height: 50)
                            .background(Color.red)
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(NSLocalizedString("call.action.decline", comment: "")))

                    // Accept
                    Button {
                        callManager.acceptCall()
                    } label: {
                        Image(systemName: callManager.isVideoCall ? "video.fill" : "phone.fill")
                            .font(.title3)
                            .foregroundColor(.white)
                            .frame(width: 50, height: 50)
                            .background(Color.green)
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(NSLocalizedString("call.action.accept", comment: "")))
                }
            }
            .padding()
        }
        .onAppear {
            pulseAnimation = true
        }
    }
}

// MARK: - Watch Active Call View

struct WatchActiveCallView: View {
    @EnvironmentObject var callManager: WatchCallManager

    var body: some View {
        ZStack {
            // Background based on state
            Color.black.ignoresSafeArea()
            RadialGradient(colors: [(callManager.callState == "inCall" ? Color.green : Color.orange).opacity(0.3), .clear],
                           center: .top, startRadius: 0, endRadius: 140)
                .ignoresSafeArea()

            VStack(spacing: 8) {
                // Call state
                Text(callStateText)
                    .font(.caption2)
                    .foregroundColor(.secondary)

                // Contact name
                Text(callManager.callerName)
                    .font(.headline)
                    .lineLimit(1)

                // Duration or connecting indicator
                if callManager.callState == "inCall" {
                    Text(formatDuration(callManager.callDuration))
                        .font(.system(.title2, design: .monospaced))
                        .foregroundColor(.primary)
                } else {
                    ProgressView()
                        .tint(.white)
                }

                Spacer()

                // Controls
                HStack(spacing: 20) {
                    // Mute
                    Button {
                        callManager.toggleMute()
                    } label: {
                        Image(systemName: callManager.isMuted ? "mic.slash.fill" : "mic.fill")
                            .font(.title3)
                            .foregroundColor(.white)
                            .frame(width: 44, height: 44)
                            .background(callManager.isMuted ? Color.red : Color.gray.opacity(0.5))
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(L("w2.mute", "Mute")))

                    // End call
                    Button {
                        callManager.endCall()
                    } label: {
                        Image(systemName: "phone.down.fill")
                            .font(.title3)
                            .foregroundColor(.white)
                            .frame(width: 50, height: 50)
                            .background(Color.red)
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(L("w2.end_call", "End call")))

                    // Speaker
                    Button {
                        callManager.toggleSpeaker()
                    } label: {
                        Image(systemName: callManager.isSpeaker ? "speaker.wave.3.fill" : "speaker.fill")
                            .font(.title3)
                            .foregroundColor(.white)
                            .frame(width: 44, height: 44)
                            .background(callManager.isSpeaker ? Color.blue : Color.gray.opacity(0.5))
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(L("w2.speaker", "Speaker")))
                }
            }
            .padding()
        }
    }

    private var callStateText: String {
        switch callManager.callState {
        case "connecting": return NSLocalizedString("watch.call.connecting", comment: "")
        case "ringing": return NSLocalizedString("watch.call.ringing", comment: "")
        case "inCall": return NSLocalizedString("watch.call.in_call", comment: "")
        case "ended": return NSLocalizedString("watch.call.ended", comment: "")
        default: return ""
        }
    }

    private func formatDuration(_ duration: TimeInterval) -> String {
        let minutes = Int(duration) / 60
        let seconds = Int(duration) % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }
}

