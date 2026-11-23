//
//  ScheduledCallManager.swift
//  OSHI - Scheduled Call Events
//
//  Manages scheduled call events with timezone support,
//  accept/decline functionality, and reminder notifications
//

import SwiftUI
import UserNotifications
import Combine
import AudioToolbox

// MARK: - Scheduled Call Event

struct ScheduledCallEvent: Codable, Identifiable, Equatable {
    let id: UUID
    let conversationId: String  // Peer public key or group ID
    let conversationName: String
    let createdBy: String       // Public key of creator
    let createdByName: String
    let scheduledTime: Date     // UTC time
    let creatorTimezone: String // Creator's timezone identifier
    let title: String?
    let duration: TimeInterval? // Expected duration in minutes
    
    // Response tracking
    var responses: [String: EventResponse]  // [publicKey: response]
    let createdAt: Date
    
    // Status
    var status: EventStatus
    
    enum EventStatus: String, Codable {
        case pending    // Waiting for responses
        case confirmed  // At least one accepted
        case declined   // All declined
        case cancelled  // Creator cancelled
        case completed  // Time passed
    }
    
    enum EventResponse: String, Codable {
        case pending
        case accepted
        case declined
    }
    
    init(conversationId: String, conversationName: String, createdBy: String, createdByName: String, scheduledTime: Date, title: String? = nil, duration: TimeInterval? = nil) {
        self.id = UUID()
        self.conversationId = conversationId
        self.conversationName = conversationName
        self.createdBy = createdBy
        self.createdByName = createdByName
        self.scheduledTime = scheduledTime
        self.creatorTimezone = TimeZone.current.identifier
        self.title = title
        self.duration = duration
        self.responses = [:]
        self.createdAt = Date()
        self.status = .pending
    }
    
    // Get scheduled time in a specific timezone
    func scheduledTimeInTimezone(_ timezone: TimeZone) -> Date {
        return scheduledTime  // Date is always UTC, display converts
    }
    
    // Format time for display
    func formattedTime(in timezone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.timeZone = timezone
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: scheduledTime)
    }
    
    // Check if event is upcoming (within next hour)
    var isUpcoming: Bool {
        let now = Date()
        let hourFromNow = now.addingTimeInterval(3600)
        return scheduledTime > now && scheduledTime <= hourFromNow
    }
    
    // Check if event is now (within 5 minutes)
    var isNow: Bool {
        let now = Date()
        let fiveMinutes: TimeInterval = 300
        return abs(scheduledTime.timeIntervalSince(now)) <= fiveMinutes
    }
    
    // Check if event is past
    var isPast: Bool {
        return scheduledTime < Date()
    }
}

// MARK: - Scheduled Call Manager

class ScheduledCallManager: ObservableObject {
    static let shared = ScheduledCallManager()
    
    @Published private(set) var events: [ScheduledCallEvent] = []
    @Published var upcomingAlert: ScheduledCallEvent?
    
    private let storageKey = "scheduledCallEvents"
    private var reminderTimers15: [UUID: Timer] = [:]
    private var reminderTimers5: [UUID: Timer] = [:]
    private var startTimers: [UUID: Timer] = [:]
    private var cancellables = Set<AnyCancellable>()
    
    private init() {
        loadEvents()
        setupReminderTimers()
        startEventMonitor()
    }
    
    // MARK: - Create Event
    
    func createEvent(
        for conversationId: String,
        conversationName: String,
        scheduledTime: Date,
        title: String? = nil,
        duration: TimeInterval? = nil
    ) -> ScheduledCallEvent {
        // Get current user's identity from UserDefaults
        let myPublicKey = UserDefaults.standard.string(forKey: "publicKey") ?? "unknown"
        let myName = UserDefaults.standard.string(forKey: "userDisplayName") ?? "Me"
        
        let event = ScheduledCallEvent(
            conversationId: conversationId,
            conversationName: conversationName,
            createdBy: myPublicKey,
            createdByName: myName,
            scheduledTime: scheduledTime,
            title: title,
            duration: duration
        )
        
        events.append(event)
        saveEvents()
        scheduleReminder(for: event)
        scheduleLocalNotification(for: event)
        
        OshiLog.call.info("📅 Created scheduled call: \(event.formattedTime())")
        
        return event
    }
    
    // MARK: - Respond to Event
    
    func respondToEvent(_ eventId: UUID, response: ScheduledCallEvent.EventResponse) {
        guard let index = events.firstIndex(where: { $0.id == eventId }) else { return }
        
        let myPublicKey = UserDefaults.standard.string(forKey: "publicKey") ?? "unknown"
        events[index].responses[myPublicKey] = response
        
        // Update status based on responses
        updateEventStatus(at: index)
        saveEvents()
        
        OshiLog.call.info("📅 Responded to event: \(response.rawValue)")
    }
    
    // MARK: - Cancel Event
    
    func cancelEvent(_ eventId: UUID) {
        guard let index = events.firstIndex(where: { $0.id == eventId }) else { return }
        
        events[index].status = .cancelled
        cancelReminder(for: eventId)
        cancelLocalNotification(for: eventId)
        saveEvents()
        
        OshiLog.call.info("📅 Cancelled scheduled call")
    }
    
    // MARK: - Get Events
    
    func getEvents(for conversationId: String) -> [ScheduledCallEvent] {
        return events
            .filter { $0.conversationId == conversationId }
            .sorted { $0.scheduledTime < $1.scheduledTime }
    }
    
    func getUpcomingEvents() -> [ScheduledCallEvent] {
        let now = Date()
        return events
            .filter { $0.scheduledTime > now && $0.status != .cancelled }
            .sorted { $0.scheduledTime < $1.scheduledTime }
    }
    
    func getPendingEvents(for conversationId: String) -> [ScheduledCallEvent] {
        let myPublicKey = UserDefaults.standard.string(forKey: "publicKey") ?? ""
        return getEvents(for: conversationId)
            .filter { $0.status == .pending && $0.responses[myPublicKey] == nil }
    }
    
    // MARK: - Handle Received Event
    
    func handleReceivedEvent(data: Data, from peerPublicKey: String) {
        guard let event = try? JSONDecoder().decode(ScheduledCallEvent.self, from: data) else {
            OshiLog.call.info("❌ Failed to decode scheduled call event")
            return
        }
        
        // Check if blocked
        if BlockedContactsManager.shared.isBlocked(peerPublicKey) {
            OshiLog.call.info("🚫 Ignoring scheduled call from blocked contact")
            return
        }
        
        // Check if event already exists (update) or is new
        if let index = events.firstIndex(where: { $0.id == event.id }) {
            // Update existing event
            events[index] = event
        } else {
            // Add new event
            events.append(event)
            scheduleReminder(for: event)
            scheduleLocalNotification(for: event)
        }
        
        saveEvents()
        OshiLog.call.info("📅 Received scheduled call event from \(event.createdByName)")
    }
    
    // MARK: - Private Methods
    
    private func updateEventStatus(at index: Int) {
        let event = events[index]
        let responses = event.responses.values
        
        if responses.contains(.accepted) {
            events[index].status = .confirmed
        } else if responses.allSatisfy({ $0 == .declined }) && !responses.isEmpty {
            events[index].status = .declined
        }
    }
    
    private func scheduleReminder(for event: ScheduledCallEvent) {
        // Cancel existing timers if any
        cancelReminder(for: event.id)

        // Schedule 15 minute reminder
        let reminderTime15 = event.scheduledTime.addingTimeInterval(-900) // 15 minutes before
        if reminderTime15 > Date() {
            let timer15 = Timer(fire: reminderTime15, interval: 0, repeats: false) { [weak self] _ in
                DispatchQueue.main.async {
                    self?.upcomingAlert = event
                    self?.playAlarmSound()
                    OshiLog.call.info("⏰ 15-minute reminder for call with \(event.conversationName)")
                }
            }
            RunLoop.main.add(timer15, forMode: .common)
            reminderTimers15[event.id] = timer15
        }

        // Schedule 5 minute reminder
        let reminderTime5 = event.scheduledTime.addingTimeInterval(-300) // 5 minutes before
        if reminderTime5 > Date() {
            let timer5 = Timer(fire: reminderTime5, interval: 0, repeats: false) { [weak self] _ in
                DispatchQueue.main.async {
                    self?.upcomingAlert = event
                    self?.playAlarmSound()
                    OshiLog.call.info("⏰ 5-minute reminder for call with \(event.conversationName)")
                }
            }
            RunLoop.main.add(timer5, forMode: .common)
            reminderTimers5[event.id] = timer5  // 🔧 FIX: Properly store 5-min timer
        }

        // Schedule for when event actually starts (exact time)
        let startTimer = Timer(fire: event.scheduledTime, interval: 0, repeats: false) { [weak self] _ in
            DispatchQueue.main.async {
                self?.upcomingAlert = event
                self?.playAlarmSound()
                self?.playAlarmSound() // Double alarm at exact time
                OshiLog.call.info("🔔 CALL TIME! Call with \(event.conversationName) starting NOW")
            }
        }
        RunLoop.main.add(startTimer, forMode: .common)
        startTimers[event.id] = startTimer
    }
    
    private func playAlarmSound() {
        // Play system alarm sound
        AudioServicesPlayAlertSound(SystemSoundID(1005)) // Alarm sound
        
        // Also vibrate
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        
        // Play multiple times for attention
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
            AudioServicesPlayAlertSound(SystemSoundID(1005))
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            AudioServicesPlayAlertSound(SystemSoundID(1005))
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        }
    }
    
    private func cancelReminder(for eventId: UUID) {
        reminderTimers15[eventId]?.invalidate()
        reminderTimers15.removeValue(forKey: eventId)
        reminderTimers5[eventId]?.invalidate()
        reminderTimers5.removeValue(forKey: eventId)
        startTimers[eventId]?.invalidate()
        startTimers.removeValue(forKey: eventId)
    }
    
    private func scheduleLocalNotification(for event: ScheduledCallEvent) {
        let content = UNMutableNotificationContent()
        content.title = NSLocalizedString("event.notification.title", comment: "Scheduled Call")
        content.body = String(format: NSLocalizedString("event.notification.body", comment: "Call with %@ starting soon"), event.conversationName)
        content.sound = .default
        content.categoryIdentifier = "SCHEDULED_CALL"
        content.userInfo = ["eventId": event.id.uuidString]
        
        // 15 minutes before notification
        let triggerDate15 = event.scheduledTime.addingTimeInterval(-900)
        if triggerDate15 > Date() {
            let components15 = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: triggerDate15)
            let trigger15 = UNCalendarNotificationTrigger(dateMatching: components15, repeats: false)
            let request15 = UNNotificationRequest(identifier: "call_15min_\(event.id.uuidString)", content: content, trigger: trigger15)
            UNUserNotificationCenter.current().add(request15)
        }
        
        // At exact time notification
        let contentNow = UNMutableNotificationContent()
        contentNow.title = NSLocalizedString("event.notification.now.title", comment: "Call Starting Now!")
        contentNow.body = String(format: NSLocalizedString("event.notification.now.body", comment: "Your call with %@ is starting"), event.conversationName)
        contentNow.sound = UNNotificationSound.defaultCritical
        contentNow.categoryIdentifier = "SCHEDULED_CALL"
        contentNow.userInfo = ["eventId": event.id.uuidString]
        
        let componentsNow = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: event.scheduledTime)
        let triggerNow = UNCalendarNotificationTrigger(dateMatching: componentsNow, repeats: false)
        let requestNow = UNNotificationRequest(identifier: "call_now_\(event.id.uuidString)", content: contentNow, trigger: triggerNow)
        UNUserNotificationCenter.current().add(requestNow)
    }
    
    private func cancelLocalNotification(for eventId: UUID) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [
            "call_15min_\(eventId.uuidString)",
            "call_now_\(eventId.uuidString)"
        ])
    }
    
    private func setupReminderTimers() {
        for event in events where event.status != .cancelled && !event.isPast {
            scheduleReminder(for: event)
        }
    }
    
    private func startEventMonitor() {
        // Check every minute for events that are now
        Timer.publish(every: 60, on: .main, in: .common)
            .autoconnect()
            .sink { [weak self] _ in
                self?.checkForCurrentEvents()
            }
            .store(in: &cancellables)
    }
    
    private func checkForCurrentEvents() {
        for event in events where event.isNow && event.status == .confirmed {
            upcomingAlert = event
            // Mark as completed after showing
            if let index = events.firstIndex(where: { $0.id == event.id }) {
                events[index].status = .completed
                saveEvents()
            }
        }
    }
    
    // MARK: - Persistence
    
    private func loadEvents() {
        guard let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let fileURL = documentsURL.appendingPathComponent("\(storageKey).json")
        
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        
        do {
            let data = try AtRestFile.read(fileURL)  // __PLAINTEXT_LEFTOVERS_2026_09_24__
            events = try JSONDecoder().decode([ScheduledCallEvent].self, from: data)
            OshiLog.call.info("✅ Loaded \(events.count) scheduled events")
        } catch {
            OshiLog.call.info("❌ Error loading scheduled events: \(error)")
        }
    }
    
    private func saveEvents() {
        guard let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let fileURL = documentsURL.appendingPathComponent("\(storageKey).json")
        
        do {
            let data = try JSONEncoder().encode(events)
            try AtRestFile.write(data, to: fileURL)  // __PLAINTEXT_LEFTOVERS_2026_09_24__ chiffré au repos (ancien clair lu puis rechiffré)
        } catch {
            OshiLog.call.info("❌ Error saving scheduled events: \(error)")
        }
    }
    
    // MARK: - Encode for Sending
    
    func encodeEvent(_ event: ScheduledCallEvent) -> Data? {
        return try? JSONEncoder().encode(event)
    }
}

// MARK: - Event Message Type for Chat

struct ScheduledCallMessage: Codable {
    var type: String
    let event: ScheduledCallEvent
    
    init(event: ScheduledCallEvent) {
        self.type = "scheduled_call"
        self.event = event
    }
}
