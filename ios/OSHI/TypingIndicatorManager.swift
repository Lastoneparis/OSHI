//
//  TypingIndicatorManager.swift
//  OSHI - Typing Indicators (WhatsApp-style)
//
//  Shows "typing..." when contact is composing a message
//

import Foundation
import SwiftUI
import Combine

// MARK: - Typing Status

struct TypingStatus: Codable {
    static let messagePrefix = "⌨️TYPING⌨️"

    let senderPublicKey: String
    let isTyping: Bool
    let timestamp: Date
    // Optional group scope — when nil, this is a 1-on-1 typing ping (back-compat with old clients).
    var groupId: String? = nil
    var senderName: String? = nil

    enum CodingKeys: String, CodingKey {
        case senderPublicKey, isTyping, timestamp, groupId, senderName
    }

    init(senderPublicKey: String, isTyping: Bool, timestamp: Date, groupId: String? = nil, senderName: String? = nil) {
        self.senderPublicKey = senderPublicKey
        self.isTyping = isTyping
        self.timestamp = timestamp
        self.groupId = groupId
        self.senderName = senderName
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        senderPublicKey = try c.decode(String.self, forKey: .senderPublicKey)
        isTyping = try c.decode(Bool.self, forKey: .isTyping)
        timestamp = try c.decode(Date.self, forKey: .timestamp)
        groupId = try c.decodeIfPresent(String.self, forKey: .groupId)
        senderName = try c.decodeIfPresent(String.self, forKey: .senderName)
    }

    func toMessageString() -> String {
        guard let data = try? JSONEncoder().encode(self),
              let json = String(data: data, encoding: .utf8) else {
            return ""
        }
        return "\(Self.messagePrefix)\(json)"
    }

    static func fromMessageString(_ string: String) -> TypingStatus? {
        guard string.hasPrefix(messagePrefix) else { return nil }
        let jsonString = String(string.dropFirst(messagePrefix.count))
        guard let data = jsonString.data(using: .utf8),
              let status = try? JSONDecoder().decode(TypingStatus.self, from: data) else {
            return nil
        }
        return status
    }
}

// MARK: - Typing Indicator Manager

class TypingIndicatorManager: ObservableObject {
    static let shared = TypingIndicatorManager()

    // publicKey -> isTyping
    @Published var typingUsers: [String: Bool] = [:]

    // groupId -> [publicKey: senderName] of members currently typing in that group.
    @Published var groupTypingUsers: [String: [String: String]] = [:]

    // Timers to auto-clear typing status after 5 seconds
    private var clearTimers: [String: Timer] = [:]
    // Per-group-per-user auto-clear timers, keyed by "\(groupId)|\(publicKey)".
    private var groupClearTimers: [String: Timer] = [:]

    // Debounce timer for sending typing status
    private var sendTimer: Timer?
    private var lastSentTyping: Date?
    // Per-group debounce so group and 1-on-1 pings don't starve each other.
    private var lastSentGroupTyping: [String: Date] = [:]
    
    // Configuration
    private let typingTimeout: TimeInterval = 5.0      // Clear after 5 seconds of no update
    private let sendDebounce: TimeInterval = 2.0       // Don't send more often than every 2 seconds
    
    private init() {
        setupNotificationObservers()
        OshiLog.messaging.info("⌨️ TypingIndicatorManager initialized")
    }
    
    // MARK: - Send Typing Status
    
    /// Call this when user starts/stops typing
    func setTyping(_ isTyping: Bool, to recipientPublicKey: String) {
        // Honor the user's privacy setting — suppress all outbound typing pings when disabled.
        // Default true when key absent, so legacy installs behave as before.
        let defaults = UserDefaults.standard
        let enabled = (defaults.object(forKey: "typingIndicatorsEnabled") as? Bool) ?? true
        if !enabled { return }

        // Debounce - don't spam typing updates
        if isTyping {
            if let lastSent = lastSentTyping,
               Date().timeIntervalSince(lastSent) < sendDebounce {
                return // Too soon, skip
            }
        }
        
        lastSentTyping = Date()
        
        // Post notification to send typing status
        NotificationCenter.default.post(
            name: NSNotification.Name("SendTypingStatus"),
            object: nil,
            userInfo: [
                "recipientPublicKey": recipientPublicKey,
                "isTyping": isTyping
            ]
        )
    }
    
    // MARK: - Receive Typing Status
    
    /// Call this when typing status is received
    func receiveTypingStatus(_ status: TypingStatus) {
        // Route to group-scoped state when the status carries a groupId — keeps the 1-on-1 path untouched.
        if status.groupId != nil {
            receiveGroupTypingStatus(status)
            return
        }
        DispatchQueue.main.async {
            // Update typing status
            self.typingUsers[status.senderPublicKey] = status.isTyping

            // Cancel existing timer
            self.clearTimers[status.senderPublicKey]?.invalidate()

            // If typing, set timer to auto-clear
            if status.isTyping {
                self.clearTimers[status.senderPublicKey] = Timer.scheduledTimer(
                    withTimeInterval: self.typingTimeout,
                    repeats: false
                ) { [weak self] _ in
                    DispatchQueue.main.async {
                        self?.typingUsers[status.senderPublicKey] = false
                    }
                }
            }
        }
    }
    
    /// Check if a specific user is typing
    func isUserTyping(_ publicKey: String) -> Bool {
        return typingUsers[publicKey] ?? false
    }

    // MARK: - Group typing (Android-parity)

    /// Broadcast a group typing ping. `senderName` is embedded so the receiver can render it without a member lookup.
    func userStartedTyping(in groupId: String, senderName: String? = nil) {
        setGroupTyping(true, in: groupId, senderName: senderName)
    }

    func userStoppedTyping(in groupId: String, senderName: String? = nil) {
        setGroupTyping(false, in: groupId, senderName: senderName)
    }

    private func setGroupTyping(_ isTyping: Bool, in groupId: String, senderName: String?) {
        let defaults = UserDefaults.standard
        let enabled = (defaults.object(forKey: "typingIndicatorsEnabled") as? Bool) ?? true
        if !enabled { return }
        if isTyping, let last = lastSentGroupTyping[groupId],
           Date().timeIntervalSince(last) < sendDebounce {
            return
        }
        lastSentGroupTyping[groupId] = Date()

        // Fire-and-forget: the GroupChatView listens for this and broadcasts to the group via its own transport.
        NotificationCenter.default.post(
            name: NSNotification.Name("SendGroupTypingStatus"),
            object: nil,
            userInfo: [
                "groupId": groupId,
                "isTyping": isTyping,
                "senderName": senderName ?? ""
            ]
        )
    }

    /// Apply an incoming typing status when it has a group scope.
    func receiveGroupTypingStatus(_ status: TypingStatus) {
        guard let groupId = status.groupId else { return }
        DispatchQueue.main.async {
            var map = self.groupTypingUsers[groupId] ?? [:]
            if status.isTyping {
                map[status.senderPublicKey] = status.senderName ?? ""
            } else {
                map.removeValue(forKey: status.senderPublicKey)
            }
            self.groupTypingUsers[groupId] = map.isEmpty ? nil : map

            let key = "\(groupId)|\(status.senderPublicKey)"
            self.groupClearTimers[key]?.invalidate()
            if status.isTyping {
                self.groupClearTimers[key] = Timer.scheduledTimer(withTimeInterval: self.typingTimeout, repeats: false) { [weak self] _ in
                    DispatchQueue.main.async {
                        guard let self = self else { return }
                        var m = self.groupTypingUsers[groupId] ?? [:]
                        m.removeValue(forKey: status.senderPublicKey)
                        self.groupTypingUsers[groupId] = m.isEmpty ? nil : m
                    }
                }
            }
        }
    }

    /// Display string for a group's typing state. Empty when nobody is typing.
    func typingSummary(forGroup groupId: String, excludingPublicKey: String? = nil) -> String {
        guard let map = groupTypingUsers[groupId] else { return "" }
        let filtered = map.filter { excludingPublicKey == nil || $0.key != excludingPublicKey }
        guard !filtered.isEmpty else { return "" }
        // Pick the first-typing member for a concise header. Android uses the same single-name style.
        let firstName = filtered.values.first.flatMap { $0.isEmpty ? nil : $0 }
            ?? filtered.keys.first.map { String($0.prefix(6)) + "..." }
            ?? ""
        if filtered.count == 1 {
            return String(format: "typing.indicator".localized, firstName)
        }
        return String(format: "typing.indicator".localized, "\(firstName) +\(filtered.count - 1)")
    }

    // MARK: - Notification Observers
    
    private func setupNotificationObservers() {
        // 🔴 WATCHDOG (0x8BADF00D): these notifications are posted from background
        // threads. `queue: .main` would park the POSTER on `-[NSOperation waitUntilFinished]`
        // until the main runloop drained — deadlocking whenever main was itself waiting on
        // that thread's queue/lock. addMainThreadObserver hops to main without blocking it.
        // See NotificationCenter+MainThread in NotificationManager.swift.
        NotificationCenter.default.addMainThreadObserver(
            forName: NSNotification.Name("ReceivedTypingStatus")
        ) { [weak self] notification in
            if let status = notification.userInfo?["status"] as? TypingStatus {
                self?.receiveTypingStatus(status)
            }
        }
    }
    
    // MARK: - Cleanup
    
    func clearTypingStatus(for publicKey: String) {
        typingUsers.removeValue(forKey: publicKey)
        clearTimers[publicKey]?.invalidate()
        clearTimers.removeValue(forKey: publicKey)
    }
    
    func clearAll() {
        typingUsers.removeAll()
        clearTimers.values.forEach { $0.invalidate() }
        clearTimers.removeAll()
        groupTypingUsers.removeAll()
        groupClearTimers.values.forEach { $0.invalidate() }
        groupClearTimers.removeAll()
        lastSentGroupTyping.removeAll()
    }
}

// MARK: - Typing Indicator View

struct TypingIndicatorView: View {
    let contactName: String
    
    @State private var animationPhase = 0
    
    var body: some View {
        HStack(spacing: 4) {
            Text(String(format: "typing.indicator".localized, contactName))
                .font(.caption)
                .foregroundColor(.secondary)
            
            HStack(spacing: 3) {
                ForEach(0..<3, id: \.self) { index in
                    Circle()
                        .fill(Color.secondary)
                        .frame(width: 5, height: 5)
                        .offset(y: animationPhase == index ? -3 : 0)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Color(.systemGray6))
        .cornerRadius(12)
        .onAppear {
            startAnimation()
        }
    }
    
    private func startAnimation() {
        Timer.scheduledTimer(withTimeInterval: 0.3, repeats: true) { timer in
            withAnimation(.easeInOut(duration: 0.2)) {
                animationPhase = (animationPhase + 1) % 3
            }
        }
    }
}

// MARK: - Compact Typing Dots (for chat header)

struct TypingDotsView: View {
    @State private var animationPhase = 0
    
    var body: some View {
        HStack(spacing: 2) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(Color.secondary)
                    .frame(width: 4, height: 4)
                    .scaleEffect(animationPhase == index ? 1.3 : 1.0)
                    .opacity(animationPhase == index ? 1.0 : 0.5)
            }
        }
        .onAppear {
            startAnimation()
        }
    }
    
    private func startAnimation() {
        Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { _ in
            withAnimation(.easeInOut(duration: 0.2)) {
                animationPhase = (animationPhase + 1) % 3
            }
        }
    }
}

// MARK: - Preview

#Preview {
    VStack(spacing: 20) {
        TypingIndicatorView(contactName: "Alice")
        
        HStack {
            Text("Alice")
            TypingDotsView()
        }
    }
    .padding()
}
