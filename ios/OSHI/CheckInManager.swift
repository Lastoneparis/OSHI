//
//  CheckInManager.swift
//  OSHI
//
//  Accompagnement/Check-In Safety Feature Manager
//  Handles session tracking, geofencing, ETA calculation, and emergency escalation
//

import Foundation
import CoreLocation
import MapKit
import UIKit
import Combine

// MARK: - Check-In Manager

@MainActor
class CheckInManager: NSObject, ObservableObject {

    // MARK: - Singleton

    static let shared = CheckInManager()

    // MARK: - Published Properties

    @Published var activeSession: CheckInSession?
    @Published var isMonitoring: Bool = false
    @Published var currentLocation: CLLocation?
    @Published var distanceToDestination: CLLocationDistance?
    @Published var showWarningAlert: Bool = false

    // MARK: - Configuration

    private let arrivalRadius: CLLocationDistance = 100  // 100 meters
    private let warningTimeBeforeEmergency: TimeInterval = 15 * 60  // 15 minutes after ETA
    private let emergencyTimeAfterWarning: TimeInterval = 15 * 60   // 15 more minutes
    private let locationUpdateInterval: TimeInterval = 30  // Update every 30 seconds

    // MARK: - Private Properties

    private var locationManager: CLLocationManager?
    private var monitoringTimer: Timer?
    private var locationUpdateTimer: Timer?
    private var onMessageSend: ((CheckInMessage) -> Void)?
    private var cancellables = Set<AnyCancellable>()

    // MARK: - Persistence Keys

    private let sessionStorageKey = "oshi_checkin_active_session"

    // MARK: - Initialization

    private override init() {
        super.init()
        setupLocationManager()
        loadPersistedSession()
    }

    private func setupLocationManager() {
        // Do NOT enable background updates at init. This singleton wakes on app
        // launch, and turning background location on here causes iOS to count
        // location accesses against the app even when no check-in is active —
        // producing the "OSHI a utilisé votre position en arrière-plan"
        // notification the user complained about. Background updates are
        // toggled on/off around the actual `startCheckIn` / `stopCheckIn`
        // sessions only (mirrors LocationSharingManager's correct pattern).
        locationManager = CLLocationManager()
        locationManager?.delegate = self
        locationManager?.desiredAccuracy = kCLLocationAccuracyBest
        locationManager?.distanceFilter = 50  // Update every 50 meters
        locationManager?.allowsBackgroundLocationUpdates = false
        locationManager?.pausesLocationUpdatesAutomatically = true
        locationManager?.showsBackgroundLocationIndicator = false
    }

    // MARK: - Session Management

    func startCheckIn(
        contactPublicKey: String,
        destination: CLLocationCoordinate2D,
        destinationAddress: String,
        transportMode: TransportMode,
        extraTimeMinutes: Int = 0,
        onMessageSend: @escaping (CheckInMessage) -> Void
    ) async {
        // Calculate ETA
        guard let eta = await calculateETA(to: destination, mode: transportMode) else {
            OshiLog.messaging.info("❌ CheckIn: Failed to calculate ETA")
            return
        }

        // Create session
        let session = CheckInSession(
            contactPublicKey: contactPublicKey,
            destination: destination,
            destinationAddress: destinationAddress,
            transportMode: transportMode,
            estimatedArrivalTime: eta,
            extraTimeMinutes: extraTimeMinutes
        )

        self.activeSession = session
        self.onMessageSend = onMessageSend
        self.isMonitoring = true

        // Persist session
        persistSession(session)

        // Start monitoring
        startLocationMonitoring()
        startGeofencing(destination: destination, sessionId: session.id)
        startTimerMonitoring()

        // Send initial message
        let senderName = UserDefaults.standard.string(forKey: "user_display_name")
        let message = CheckInMessage.started(session: session, senderName: senderName)
        onMessageSend(message)

        OshiLog.messaging.info("🛡️ CheckIn: Started session \(session.id)")
        OshiLog.messaging.info("   Destination: \(destinationAddress)")
        OshiLog.messaging.info("   ETA: \(session.formattedETA)")
        OshiLog.messaging.info("   Mode: \(transportMode.localizedName)")
    }

    func stopCheckIn(reason: CheckInStatus = .cancelled) {
        guard var session = activeSession else { return }

        // Stop monitoring
        stopLocationMonitoring()
        stopGeofencing()
        stopTimerMonitoring()

        // Update status
        switch reason {
        case .arrived:
            session.markArrived()
            let senderName = UserDefaults.standard.string(forKey: "user_display_name")
            let message = CheckInMessage.arrived(
                sessionId: session.id,
                destinationAddress: session.destinationAddress,
                senderName: senderName
            )
            onMessageSend?(message)
            OshiLog.messaging.info("✅ CheckIn: Arrived safely at \(session.destinationAddress)")

        case .cancelled:
            session.markCancelled()
            let message = CheckInMessage.cancelled(sessionId: session.id)
            onMessageSend?(message)
            OshiLog.messaging.info("🚫 CheckIn: Cancelled by user")

        default:
            break
        }

        // Clear session
        activeSession = nil
        isMonitoring = false
        clearPersistedSession()

        // Post notification
        NotificationCenter.default.post(
            name: NSNotification.Name("CheckInEnded"),
            object: nil,
            userInfo: ["sessionId": session.id, "status": reason.rawValue]
        )
    }

    func extendTime(minutes: Int) {
        guard var session = activeSession else { return }

        session.extend(minutes: minutes)
        activeSession = session
        persistSession(session)

        // Send extended message
        let message = CheckInMessage.extended(sessionId: session.id, newETA: session.effectiveDeadline)
        onMessageSend?(message)

        // Reset warning state if shown
        showWarningAlert = false

        OshiLog.messaging.info("⏰ CheckIn: Extended by \(minutes) minutes. New deadline: \(session.formattedRemainingTime)")
    }

    func confirmSafe() {
        showWarningAlert = false

        // Extend by 15 minutes
        extendTime(minutes: 15)

        OshiLog.messaging.info("👍 CheckIn: User confirmed safe, extended time")
    }

    // MARK: - ETA Calculation

    func calculateETA(to destination: CLLocationCoordinate2D, mode: TransportMode) async -> Date? {
        let request = MKDirections.Request()
        request.source = MKMapItem.forCurrentLocation()
        request.destination = MKMapItem(placemark: MKPlacemark(coordinate: destination))

        switch mode {
        case .walking:
            request.transportType = .walking
        case .driving:
            request.transportType = .automobile
        case .transit:
            request.transportType = .transit
        }

        let directions = MKDirections(request: request)

        do {
            let response = try await directions.calculate()
            if let route = response.routes.first {
                let eta = Date().addingTimeInterval(route.expectedTravelTime)
                OshiLog.messaging.info("📍 CheckIn: Calculated ETA \(route.expectedTravelTime / 60) minutes")
                return eta
            }
        } catch {
            OshiLog.messaging.info("❌ CheckIn: ETA calculation failed: \(error.localizedDescription)")
        }

        return nil
    }

    // MARK: - Location Monitoring

    private func startLocationMonitoring() {
        locationManager?.requestAlwaysAuthorization()
        // Turn ON background location ONLY for the duration of an active
        // check-in session. Paired with the OFF flip in
        // `stopLocationMonitoring`. Without this gate, the manager keeps
        // accessing location in the background after the check-in ends,
        // triggering the iOS privacy notification.
        locationManager?.allowsBackgroundLocationUpdates = true
        locationManager?.pausesLocationUpdatesAutomatically = false
        locationManager?.showsBackgroundLocationIndicator = true
        locationManager?.startUpdatingLocation()

        // Also start a timer for periodic checks
        locationUpdateTimer = Timer.scheduledTimer(withTimeInterval: locationUpdateInterval, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.checkProgress()
            }
        }
        RunLoop.main.add(locationUpdateTimer!, forMode: .common)

        OshiLog.messaging.info("📍 CheckIn: Started location monitoring (background ON)")
    }

    private func stopLocationMonitoring() {
        locationManager?.stopUpdatingLocation()
        // Flip background updates OFF as soon as the check-in is over, so
        // iOS stops counting silent location accesses against the app.
        locationManager?.allowsBackgroundLocationUpdates = false
        locationManager?.showsBackgroundLocationIndicator = false
        locationManager?.pausesLocationUpdatesAutomatically = true
        locationUpdateTimer?.invalidate()
        locationUpdateTimer = nil
        OshiLog.messaging.info("📍 CheckIn: Stopped location monitoring (background OFF)")
    }

    // MARK: - Geofencing

    private func startGeofencing(destination: CLLocationCoordinate2D, sessionId: String) {
        let region = CLCircularRegion(
            center: destination,
            radius: arrivalRadius,
            identifier: "checkin_\(sessionId)"
        )
        region.notifyOnEntry = true
        region.notifyOnExit = false

        locationManager?.startMonitoring(for: region)
        OshiLog.messaging.info("🎯 CheckIn: Started geofencing (radius: \(arrivalRadius)m)")
    }

    private func stopGeofencing() {
        if let regions = locationManager?.monitoredRegions {
            for region in regions where region.identifier.hasPrefix("checkin_") {
                locationManager?.stopMonitoring(for: region)
            }
        }
        OshiLog.messaging.info("🎯 CheckIn: Stopped geofencing")
    }

    // MARK: - Timer Monitoring

    private func startTimerMonitoring() {
        monitoringTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.checkDeadline()
            }
        }
        RunLoop.main.add(monitoringTimer!, forMode: .common)
        OshiLog.messaging.info("⏱️ CheckIn: Started deadline monitoring")
    }

    private func stopTimerMonitoring() {
        monitoringTimer?.invalidate()
        monitoringTimer = nil
        OshiLog.messaging.info("⏱️ CheckIn: Stopped deadline monitoring")
    }

    // MARK: - Progress Checking

    private func checkProgress() {
        guard let session = activeSession,
              let currentLoc = currentLocation else { return }

        // Calculate distance to destination
        let destinationLoc = CLLocation(
            latitude: session.destinationLatitude,
            longitude: session.destinationLongitude
        )
        let distance = currentLoc.distance(from: destinationLoc)
        distanceToDestination = distance

        // Check if within arrival radius
        if distance <= arrivalRadius {
            handleArrival()
        }

        // Send periodic location update (every 5 minutes)
        let timeSinceLastUpdate = Date().timeIntervalSince(session.lastUpdateTime)
        if timeSinceLastUpdate >= 5 * 60 {
            let message = CheckInMessage.update(sessionId: session.id, location: currentLoc.coordinate)
            onMessageSend?(message)

            var updatedSession = session
            updatedSession.lastUpdateTime = Date()
            activeSession = updatedSession
            persistSession(updatedSession)
        }
    }

    private func checkDeadline() {
        guard var session = activeSession else { return }

        let now = Date()
        let deadline = session.effectiveDeadline

        // Check if past deadline
        if now > deadline {
            // Check if warning was shown
            if session.warningShownAt == nil {
                // Show warning first
                session.markWarningShown()
                activeSession = session
                persistSession(session)

                showWarningAlert = true
                sendLocalWarningNotification()

                // Send warning message to contact (with last-known coord so
                // the receiver's bubble can render a map preview).
                let message = CheckInMessage.warning(
                    sessionId: session.id,
                    location: currentLocation?.coordinate
                )
                onMessageSend?(message)

                OshiLog.messaging.info("⚠️ CheckIn: Warning - past ETA, waiting for response")

            } else if let warningTime = session.warningShownAt,
                      now.timeIntervalSince(warningTime) > emergencyTimeAfterWarning {
                // Emergency escalation
                triggerEmergency()
            }
        }
    }

    // MARK: - Arrival Handling

    private func handleArrival() {
        guard activeSession != nil else { return }

        OshiLog.messaging.info("🎉 CheckIn: Detected arrival at destination!")

        // Auto-confirm arrival
        stopCheckIn(reason: .arrived)

        // Send local notification
        sendArrivalNotification()
    }

    // MARK: - Emergency Escalation

    private func triggerEmergency() {
        guard var session = activeSession else { return }

        OshiLog.messaging.info("🚨 CheckIn: EMERGENCY - Sending emergency data to contact")

        // Collect emergency data
        let emergencyData = collectEmergencyData()

        // Update session
        session.markEmergency()
        activeSession = session
        persistSession(session)

        // Send emergency message
        let senderName = UserDefaults.standard.string(forKey: "user_display_name")
        let message = CheckInMessage.emergency(
            sessionId: session.id,
            emergencyData: emergencyData,
            destinationAddress: session.destinationAddress,
            senderName: senderName
        )
        onMessageSend?(message)

        // Send urgent local notification
        sendEmergencyNotification()

        // Stop monitoring
        stopLocationMonitoring()
        stopGeofencing()
        stopTimerMonitoring()

        // Post notification
        NotificationCenter.default.post(
            name: NSNotification.Name("CheckInEmergency"),
            object: nil,
            userInfo: ["sessionId": session.id]
        )
    }

    // MARK: - Emergency Data Collection

    private func collectEmergencyData() -> EmergencyData {
        // Enable battery monitoring
        UIDevice.current.isBatteryMonitoringEnabled = true

        let location = currentLocation ?? CLLocation(latitude: 0, longitude: 0)
        let batteryLevel = Double(UIDevice.current.batteryLevel)
        let isCharging = UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full

        // Get network type
        let networkType = NetworkReachabilityManager.shared.connectionType.localizedString

        return EmergencyData(
            lastKnownLatitude: location.coordinate.latitude,
            lastKnownLongitude: location.coordinate.longitude,
            lastLocationTime: location.timestamp,
            batteryLevel: max(0, batteryLevel),  // -1 if unknown
            isCharging: isCharging,
            networkType: networkType,
            deviceName: UIDevice.current.name,
            lastMovementTime: location.timestamp
        )
    }

    // MARK: - Local Notifications

    private func sendLocalWarningNotification() {
        let content = UNMutableNotificationContent()
        content.title = NSLocalizedString("checkin.warning.title", comment: "Are you okay?")
        content.body = NSLocalizedString("checkin.warning.body", comment: "You haven't arrived yet. Tap to confirm you're safe.")
        content.sound = .default
        content.categoryIdentifier = "CHECKIN_WARNING"

        let request = UNNotificationRequest(
            identifier: "checkin_warning_\(activeSession?.id ?? "")",
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request)
    }

    private func sendArrivalNotification() {
        let content = UNMutableNotificationContent()
        content.title = NSLocalizedString("checkin.arrived.title", comment: "You've arrived!")
        content.body = String(format: NSLocalizedString("checkin.arrived.body", comment: "Your contact has been notified that you arrived safely at %@."), activeSession?.destinationAddress ?? "")
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: "checkin_arrived_\(activeSession?.id ?? "")",
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request)
    }

    private func sendEmergencyNotification() {
        let content = UNMutableNotificationContent()
        content.title = NSLocalizedString("checkin.emergency.title", comment: "Emergency Alert Sent")
        content.body = NSLocalizedString("checkin.emergency.body", comment: "Your location and device info have been shared with your contact.")
        content.sound = UNNotificationSound.defaultCritical

        let request = UNNotificationRequest(
            identifier: "checkin_emergency_\(activeSession?.id ?? "")",
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request)
    }

    // MARK: - Persistence

    // __LEAK_FIX_2026_09_25__ The active session (destination address + GPS coordinates)
    // used to be a cleartext UserDefaults entry, included in iCloud/Finder backups. It now
    // lives in `Documents/checkin_session.json` sealed with `AtRestFile` (Documents is
    // excluded from backup). A session saved by the previous version is migrated on load.
    private static var sessionFileURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("checkin_session.json")
    }

    private func persistSession(_ session: CheckInSession) {
        guard let data = try? JSONEncoder().encode(session) else { return }
        do {
            try AtRestFile.write(data, to: Self.sessionFileURL)
            UserDefaults.standard.removeObject(forKey: sessionStorageKey)
        } catch {
            // Keychain/file not available yet: never lose a live safety check-in.
            UserDefaults.standard.set(data, forKey: sessionStorageKey)
        }
    }

    private func loadPersistedSession() {
        var data = try? AtRestFile.read(Self.sessionFileURL)
        if data == nil, let legacy = UserDefaults.standard.data(forKey: sessionStorageKey) {
            data = legacy
            if (try? AtRestFile.write(legacy, to: Self.sessionFileURL)) != nil {
                UserDefaults.standard.removeObject(forKey: sessionStorageKey)
            }
        }
        guard let data,
              let session = try? JSONDecoder().decode(CheckInSession.self, from: data) else {
            return
        }

        // Check if session is still valid
        if session.status == .active {
            activeSession = session
            isMonitoring = true

            // Resume monitoring
            startLocationMonitoring()
            startGeofencing(destination: session.destination, sessionId: session.id)
            startTimerMonitoring()

            OshiLog.messaging.info("🛡️ CheckIn: Resumed active session \(session.id)")
        } else {
            clearPersistedSession()
        }
    }

    private func clearPersistedSession() {
        UserDefaults.standard.removeObject(forKey: sessionStorageKey)
        try? FileManager.default.removeItem(at: Self.sessionFileURL)
    }

    // MARK: - Public Helpers

    var hasActiveSession: Bool {
        activeSession != nil && activeSession?.status == .active
    }

    func getActiveSessionForContact(_ contactKey: String) -> CheckInSession? {
        guard let session = activeSession,
              session.contactPublicKey == contactKey,
              session.status == .active else {
            return nil
        }
        return session
    }
}

// MARK: - CLLocationManagerDelegate

extension CheckInManager: CLLocationManagerDelegate {

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }

        Task { @MainActor in
            self.currentLocation = location
            self.checkProgress()
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        guard region.identifier.hasPrefix("checkin_") else { return }

        Task { @MainActor in
            OshiLog.messaging.info("🎯 CheckIn: Entered geofence region")
            self.handleArrival()
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        OshiLog.messaging.info("❌ CheckIn: Location error: \(error.localizedDescription)")
    }

    nonisolated func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {
        OshiLog.messaging.info("❌ CheckIn: Geofence monitoring error: \(error.localizedDescription)")
    }
}
