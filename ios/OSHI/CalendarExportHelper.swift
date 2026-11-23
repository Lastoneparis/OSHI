//
//  CalendarExportHelper.swift
//  OSHI - Calendar Export for Scheduled Calls
//
//  Handles adding events to Apple Calendar and generating .ics files
//

import SwiftUI
@preconcurrency import EventKit
import EventKitUI

// MARK: - Calendar Export Helper

class CalendarExportHelper: NSObject, ObservableObject {
    static let shared = CalendarExportHelper()
    
    private let eventStore = EKEventStore()
    @Published var permissionGranted = false
    @Published var permissionDenied = false
    @Published var permissionNotDetermined = false
    @Published var showingError = false
    @Published var errorMessage = ""
    
    override init() {
        super.init()
        checkPermission()
    }
    
    func checkPermission() {
        let status = EKEventStore.authorizationStatus(for: .event)
        OshiLog.ui.info("📅 Calendar authorization status raw value: \(status.rawValue)")
        
        if #available(iOS 17.0, *) {
            // iOS 17+: 0=notDetermined, 1=restricted, 2=denied, 3=fullAccess, 4=writeOnly
            switch status.rawValue {
            case 3, 4: // fullAccess or writeOnly
                permissionGranted = true
                permissionDenied = false
                permissionNotDetermined = false
            case 2, 1: // denied or restricted
                permissionGranted = false
                permissionDenied = true
                permissionNotDetermined = false
            default: // 0 = notDetermined
                permissionGranted = false
                permissionDenied = false
                permissionNotDetermined = true
            }
            OshiLog.ui.info("📅 iOS 17+ - granted: \(permissionGranted), denied: \(permissionDenied), notDetermined: \(permissionNotDetermined)")
        } else {
            // iOS 16 and earlier: 0=notDetermined, 1=restricted, 2=denied, 3=authorized
            permissionGranted = status == .authorized
            permissionDenied = status == .denied || status == .restricted
            permissionNotDetermined = status == .notDetermined
            OshiLog.ui.info("📅 iOS 16 - granted: \(permissionGranted), denied: \(permissionDenied), notDetermined: \(permissionNotDetermined)")
        }
    }
    
    @MainActor
    func checkPermissionAsync() {
        checkPermission()
    }
    
    func requestPermission() async -> Bool {
        // First check current status
        checkPermission()
        OshiLog.ui.info("📅 Requesting permission - current state: granted=\(permissionGranted), denied=\(permissionDenied), notDetermined=\(permissionNotDetermined)")
        
        // If already granted, return true
        if permissionGranted {
            OshiLog.ui.info("📅 Permission already granted")
            return true
        }
        
        // If denied or restricted, user must go to Settings
        if permissionDenied {
            OshiLog.ui.info("📅 Permission was denied - user must enable in Settings")
            return false
        }
        
        // Status is notDetermined - request permission
        OshiLog.ui.info("📅 Status is notDetermined, requesting access...")
        
        var granted = false
        
        if #available(iOS 17.0, *) {
            // iOS 17+: Try writeOnly first (less intrusive, more likely to show dialog)
            // We only need to ADD events, not read them
            OshiLog.ui.info("📅 iOS 17+ - Requesting writeOnly access first...")
            
            do {
                // writeOnly is sufficient for adding calendar events
                granted = try await eventStore.requestWriteOnlyAccessToEvents()
                OshiLog.ui.info("📅 writeOnly result: \(granted)")
                
                if granted {
                    // Success with writeOnly - that's all we need
                    OshiLog.ui.info("📅 WriteOnly access granted - sufficient for adding events")
                }
            } catch {
                OshiLog.ui.info("📅 writeOnly error: \(error.localizedDescription)")
            }
            
            // If writeOnly failed, try fullAccess as fallback
            if !granted {
                OshiLog.ui.info("📅 Trying fullAccess as fallback...")
                do {
                    granted = try await eventStore.requestFullAccessToEvents()
                    OshiLog.ui.info("📅 fullAccess result: \(granted)")
                } catch {
                    OshiLog.ui.info("📅 fullAccess error: \(error.localizedDescription)")
                }
            }
            
        } else {
            // iOS 16 and earlier: Use completion handler wrapped in continuation
            OshiLog.ui.info("📅 Requesting access (iOS 16)...")
            granted = await withCheckedContinuation { continuation in
                eventStore.requestAccess(to: .event) { result, error in
                    if let error = error {
                        OshiLog.ui.info("📅 Permission error: \(error.localizedDescription)")
                    }
                    OshiLog.ui.info("📅 requestAccess result: \(result)")
                    continuation.resume(returning: result)
                }
            }
        }
        
        OshiLog.ui.info("📅 Permission request completed - granted: \(granted)")
        
        // Give system time to update status
        try? await Task.sleep(nanoseconds: 500_000_000) // 0.5 second
        
        // Re-check permission status after the request
        await MainActor.run {
            self.checkPermission()
        }
        
        // If the API returned false but status is now granted, treat as success
        // (This can happen if user granted permission in the dialog)
        if !granted && permissionGranted {
            OshiLog.ui.info("📅 API returned false but status is now granted - treating as success")
            granted = true
        }
        
        return granted
    }
    
    func addToCalendar(event: ScheduledCallEvent) async -> Bool {
        OshiLog.ui.info("📅 CalendarExportHelper.addToCalendar called")
        
        // Check current status on main thread
        await MainActor.run {
            checkPermissionAsync()
        }
        
        // Small delay to ensure state is updated
        try? await Task.sleep(nanoseconds: 100_000_000) // 0.1 second
        
        OshiLog.ui.info("📅 Permission - granted: \(permissionGranted), denied: \(permissionDenied), notDetermined: \(permissionNotDetermined)")
        
        // If permission was previously denied, show settings message
        if permissionDenied {
            await MainActor.run {
                self.errorMessage = "calendar.permission.denied.settings".localized
                self.showingError = true
            }
            return false
        }
        
        // Request permission if not yet granted (includes notDetermined case)
        if !permissionGranted {
            OshiLog.ui.info("📅 Permission not granted, requesting...")
            let granted = await requestPermission()
            OshiLog.ui.info("📅 Permission request result: \(granted)")
            
            if !granted {
                // Check if it was denied or just not determined
                await MainActor.run {
                    self.checkPermission()
                }
                
                // On iOS 17+, if permission request returned false but status is still notDetermined,
                // the dialog may not have been shown. Guide user to Settings.
                if #available(iOS 17.0, *) {
                    if permissionNotDetermined {
                        OshiLog.ui.info("📅 iOS 17+: Request failed but status still notDetermined - guide to Settings")
                        await MainActor.run {
                            // Guide user to Settings since iOS 17 has issues showing the dialog
                            self.errorMessage = "calendar.permission.ios17.settings".localized
                            self.showingError = true
                        }
                        return false
                    }
                }
                
                if permissionDenied {
                    await MainActor.run {
                        self.errorMessage = "calendar.permission.denied.settings".localized
                        self.showingError = true
                    }
                } else {
                    await MainActor.run {
                        self.errorMessage = "calendar.permission.required".localized
                        self.showingError = true
                    }
                }
                return false
            }
        }
        
        OshiLog.ui.info("📅 Creating calendar event...")
        
        // Create calendar event
        let calendarEvent = EKEvent(eventStore: eventStore)
        calendarEvent.title = "📞 " + (event.title ?? "OSHI " + "event.call.scheduled".localized)
        calendarEvent.notes = String(format: "calendar.event.notes".localized, event.conversationName)
        calendarEvent.startDate = event.scheduledTime
        
        OshiLog.ui.info("📅 Event title: \(calendarEvent.title ?? "nil")")
        OshiLog.ui.info("📅 Event start: \(event.scheduledTime)")
        
        // Set end date based on duration (duration is already in seconds)
        if let duration = event.duration {
            calendarEvent.endDate = event.scheduledTime.addingTimeInterval(duration)
            OshiLog.ui.info("📅 Duration: \(Int(duration / 60)) minutes (\(Int(duration)) seconds)")
        } else {
            calendarEvent.endDate = event.scheduledTime.addingTimeInterval(1800) // 30 min default
            OshiLog.ui.info("📅 Using default 30 min duration")
        }
        
        // Add alarm 5 minutes before
        let alarm = EKAlarm(relativeOffset: -300)
        calendarEvent.addAlarm(alarm)
        
        // Get default calendar, or find first writable calendar
        OshiLog.ui.info("📅 Getting calendar...")
        if let defaultCalendar = eventStore.defaultCalendarForNewEvents {
            calendarEvent.calendar = defaultCalendar
            OshiLog.ui.info("📅 Using default calendar: \(defaultCalendar.title)")
        } else {
            // Find first writable calendar
            let calendars = eventStore.calendars(for: .event)
            OshiLog.ui.info("📅 No default calendar, found \(calendars.count) calendars")
            if let writableCalendar = calendars.first(where: { $0.allowsContentModifications }) {
                calendarEvent.calendar = writableCalendar
                OshiLog.ui.info("📅 Using writable calendar: \(writableCalendar.title)")
            } else {
                OshiLog.ui.info("📅 No writable calendar found!")
                await MainActor.run {
                    self.errorMessage = "calendar.no.writable".localized
                    self.showingError = true
                }
                return false
            }
        }
        
        do {
            OshiLog.ui.info("📅 Saving event...")
            try eventStore.save(calendarEvent, span: .thisEvent)
            OshiLog.ui.info("📅 Event saved successfully!")
            return true
        } catch {
            OshiLog.ui.info("📅 Error saving event: \(error)")
            await MainActor.run {
                self.errorMessage = error.localizedDescription
                self.showingError = true
            }
            return false
        }
    }
    
    func generateICSFile(for event: ScheduledCallEvent) -> URL? {
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        dateFormatter.timeZone = TimeZone(identifier: "UTC")
        
        let startDate = dateFormatter.string(from: event.scheduledTime)
        let endDate: String
        if let duration = event.duration {
            endDate = dateFormatter.string(from: event.scheduledTime.addingTimeInterval(duration))
        } else {
            endDate = dateFormatter.string(from: event.scheduledTime.addingTimeInterval(1800))
        }
        
        let now = dateFormatter.string(from: Date())
        let uid = event.id.uuidString
        let title = (event.title ?? "OSHI " + "event.call.scheduled".localized).replacingOccurrences(of: ",", with: "\\,")
        let description = String(format: "calendar.event.notes".localized, event.conversationName).replacingOccurrences(of: ",", with: "\\,")
        
        let icsContent = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//OSHI//Encrypted Messenger//EN
        CALSCALE:GREGORIAN
        METHOD:PUBLISH
        BEGIN:VEVENT
        DTSTART:\(startDate)
        DTEND:\(endDate)
        DTSTAMP:\(now)
        UID:\(uid)@oshi.app
        SUMMARY:📞 \(title)
        DESCRIPTION:\(description)
        BEGIN:VALARM
        ACTION:DISPLAY
        DESCRIPTION:OSHI Call Reminder
        TRIGGER:-PT5M
        END:VALARM
        END:VEVENT
        END:VCALENDAR
        """
        
        // __PLAINTEXT_LEFTOVERS_2026_09_24__ Partagé vers une autre app: dossier
        // de partage protégé, purgé au partage suivant / au lancement.
        let fileName = "oshi_call_\(uid.prefix(8)).ics"
        
        do {
            return try MediaPlaintextTemp.writeForShare(Data(icsContent.utf8), fileName: fileName)
        } catch {
            OshiLog.ui.info("❌ Failed to create ICS file: \(error)")
            return nil
        }
    }
    
    /// Create an EKEvent for use with EKEventEditViewController
    /// This bypasses permission issues by letting the user save via native UI
    func createEKEvent(for event: ScheduledCallEvent) -> EKEvent {
        let calendarEvent = EKEvent(eventStore: eventStore)
        calendarEvent.title = "📞 " + (event.title ?? "OSHI " + "event.call.scheduled".localized)
        calendarEvent.notes = String(format: "calendar.event.notes".localized, event.conversationName)
        calendarEvent.startDate = event.scheduledTime
        
        // Set end date based on duration
        if let duration = event.duration {
            calendarEvent.endDate = event.scheduledTime.addingTimeInterval(duration)
        } else {
            calendarEvent.endDate = event.scheduledTime.addingTimeInterval(1800) // 30 min default
        }
        
        // Add alarm 5 minutes before
        let alarm = EKAlarm(relativeOffset: -300)
        calendarEvent.addAlarm(alarm)
        
        return calendarEvent
    }
    
    /// Share the ICS file using system share sheet
    /// This is the most reliable way to add calendar events without permission issues
    func shareICSFile(for event: ScheduledCallEvent) -> URL? {
        return generateICSFile(for: event)
    }
}

// MARK: - Calendar Event Edit View Controller Wrapper
struct CalendarEventEditView: UIViewControllerRepresentable {
    let event: EKEvent
    let eventStore: EKEventStore
    @Environment(\.dismiss) private var dismiss
    var onComplete: ((Bool) -> Void)?
    
    func makeUIViewController(context: Context) -> EKEventEditViewController {
        let controller = EKEventEditViewController()
        controller.event = event
        controller.eventStore = eventStore
        controller.editViewDelegate = context.coordinator
        return controller
    }
    
    func updateUIViewController(_ uiViewController: EKEventEditViewController, context: Context) {}
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    class Coordinator: NSObject, EKEventEditViewDelegate {
        let parent: CalendarEventEditView
        
        init(_ parent: CalendarEventEditView) {
            self.parent = parent
        }
        
        func eventEditViewController(_ controller: EKEventEditViewController, didCompleteWith action: EKEventEditViewAction) {
            let success = action == .saved
            parent.onComplete?(success)
            parent.dismiss()
        }
    }
}
